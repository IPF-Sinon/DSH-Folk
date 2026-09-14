package me.bmax.apatch.dsh

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import org.json.JSONObject
import rikka.shizuku.Shizuku

/**
 * 绑定 Shizuku 用户服务并在那边执行命令。
 *
 * 与应用侧那几条通道的区别：这里**没有**本地进程可以跑 —— 命令在 Shizuku 的进程里执行，
 * uid 由 Shizuku 决定（Sui/root 模式 0，普通 adb 模式 2000）。这一层因此只做三件事：
 * 绑定、等绑定成功、把结果解析回来。
 *
 * 绑定是**按需**的（第一次调用时才绑）并且常驻：服务在 Shizuku 自己的进程里，一次绑定可以
 * 服务后续所有调用；只有 binder 掉了才清引用、由下一次调用重绑。
 */
internal object DshShizukuShell {
    private const val TAG = "DshShizukuShell"

    /** 等 binder 连上的上限：Shizuku 要先 fork 进程再加载类，第一次可能要一两秒。 */
    private const val BIND_TIMEOUT_MS = 5_000L

    private val lock = Any()

    @Volatile private var remote: IDshShellService? = null

    @Volatile private var args: Shizuku.UserServiceArgs? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = service?.let { IDshShellService.Stub.asInterface(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // 掉线只清引用，不在这里重绑：把那件事埋在回调里，出错时就看不到是谁在调用了
            remote = null
        }
    }

    private fun serviceArgs(context: Context): Shizuku.UserServiceArgs =
        args ?: Shizuku.UserServiceArgs(
            ComponentName(context.packageName, DshShizukuShellService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix("dshshell")
            .debuggable(false)
            .version(1)
            .also { args = it }

    /** 执行一条命令；连不上或调用失败都返回 null（调用方据此回 channel_lost）。 */
    fun exec(context: Context, command: String, timeoutMs: Long): JSONObject? {
        val service = ensureBound(context) ?: return null
        return runCatching {
            val raw = service.exec(
                command,
                timeoutMs.coerceIn(1_000L, PrivilegedShell.MAX_TIMEOUT_MS).toInt(),
            )
            JSONObject(raw ?: return null)
        }.onFailure { e ->
            Log.w(TAG, "调用失败: ${e.message}")
            // DeadObjectException / SecurityException 都说明这次绑定已经没用：清掉引用，
            // 让下一次调用重新绑定，而不是一路撞同一个尸体
            remote = null
        }.getOrNull()
    }

    private fun ensureBound(context: Context): IDshShellService? {
        remote?.let { return it }
        synchronized(lock) {
            remote?.let { return it }
            val requested = runCatching {
                Shizuku.bindUserService(serviceArgs(context), connection)
            }.onFailure { e -> Log.w(TAG, "绑定用户服务失败: ${e.message}") }.isSuccess
            if (!requested) return null
            // 轮询等回调：这里不用 latch 是因为连接回调与这次调用在同一个进程里，
            // 而等不到时我们只想尽快返回失败（上层还要把原因讲给 agent 听）
            val deadline = System.currentTimeMillis() + BIND_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                remote?.let { return it }
                runCatching { Thread.sleep(50) }
            }
            Log.w(TAG, "等用户服务连接超时")
            return null
        }
    }

    /** 主动解绑（切换通道时用）。平时留着绑定能让下一次调用少一次 fork 等待。 */
    fun unbind() {
        val serviceArgs = args ?: return
        runCatching { Shizuku.unbindUserService(serviceArgs, connection, true) }
        remote = null
        args = null
        Log.i(TAG, "已解绑用户服务")
    }
}
