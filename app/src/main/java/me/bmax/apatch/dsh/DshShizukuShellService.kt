package me.bmax.apatch.dsh

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 跑在 **Shizuku 进程里**的特权命令执行服务。
 *
 * ## 为什么是用户服务
 *
 * Shizuku 提供的 `Shizuku.newProcess` 在本项目的依赖版本里返回内部类型
 * （`ShizukuRemoteProcess`，@RestrictTo），应用侧根本编译不过 —— 那是库在表达「别走这条路」。
 * 官方的做法是用户服务：Shizuku 在自己的进程里实例化这个类，于是 `Runtime.exec` 拿到的身份
 * 就是 Shizuku 自己的身份（Sui/root 模式下是 uid 0，普通 adb 模式是 2000）。
 *
 * ## 这一层为什么必须自己截断输出
 *
 * binder 事务有 1MB 上限，超了抛 `TransactionTooLargeException`，而在应用侧看起来只是
 * 「通道坏了、reason 说不清」。所以输出在**这一侧**就截断并注明原长度。
 */
class DshShizukuShellService : Service() {
    private val binder = object : IDshShellService.Stub() {
        override fun exec(command: String?, timeoutMs: Int): String? {
            val cmd = command.orEmpty()
            return runCatching { runCommand(cmd, timeoutMs) }.getOrElse { e ->
                Log.w(TAG, "执行失败: ${e.message}")
                JSONObject()
                    .put("exit", -1)
                    .put("stdout", "")
                    .put("stderr", e.message.orEmpty())
                    .put("timedOut", false)
                    .put("failed", true)
                    .toString()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun runCommand(command: String, timeoutMs: Int): String {
        val limit = timeoutMs.coerceIn(1_000, 120_000).toLong()
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(false)
            .start()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        // 必须边读边等：只 waitFor 的话，输出超过管道缓冲时进程会阻塞在写上，永远不退出
        val outReader = reader { process.inputStream.bufferedReader().forEachLine { stdout.appendLine(it) } }
        val errReader = reader { process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } }
        val finished = runCatching { process.waitFor(limit, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!finished) {
            runCatching { process.destroyForcibly() }
            outReader.join(500)
            errReader.join(500)
            return JSONObject()
                .put("exit", -1)
                .put("stdout", clip(stdout.toString()))
                .put("stderr", clip(stderr.toString()))
                .put("timedOut", true)
                .toString()
        }
        outReader.join(500)
        errReader.join(500)
        return JSONObject()
            .put("exit", runCatching { process.exitValue() }.getOrDefault(-1))
            .put("stdout", clip(stdout.toString()))
            .put("stderr", clip(stderr.toString()))
            .put("timedOut", false)
            .toString()
    }

    private fun reader(block: () -> Unit): Thread = Thread(block).apply { isDaemon = true; start() }

    private fun clip(text: String): String =
        if (text.length <= MAX_CHARS) text else text.take(MAX_CHARS) + "\n…(已截断，完整长度 ${text.length})"

    private companion object {
        const val TAG = "DshShizukuShell"
        /** binder 事务上限是 1MB，两路输出各留 100K 字符足够，剩下的交回上层再截。 */
        const val MAX_CHARS = 100_000
    }
}
