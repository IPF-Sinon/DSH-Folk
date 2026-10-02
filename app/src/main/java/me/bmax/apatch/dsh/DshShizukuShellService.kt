package me.bmax.apatch.dsh

import android.util.Log
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 跑在 **Shizuku 进程里**、以它的身份（shell 2000 或 root 0）执行命令的用户服务。
 *
 * ## 这个类为什么是 Binder，而不是 `Service`
 *
 * Shizuku 的用户服务**不是** Android 的绑定服务：它由 Shizuku 自己在自己的进程里用反射
 * **实例化**，所以
 *
 *  - 这个类必须**自己就是 `IBinder`**（即 AIDL 的 `Stub`），不能是 `Service` 再靠 `onBind`
 *    把 binder 交出去 —— Shizuku 拿到一个不是 Binder 的实例就什么也回传不了，
 *    表现为 `bindUserService` 提交成功、却**永远等不到 `onServiceConnected`**；
 *  - 它**不能也不需要**写进清单：没有框架服务可声明（官方 demo 的清单里就没有这一项）。
 *
 * 第一版正是写成了 `class X : Service()` + 清单声明，于是应用侧每次都是 5 秒超时后报
 * 「通道不可用」，而权限页显示 Shizuku 一切正常 —— 两边的说法互相矛盾，极难自查。
 * 依据：Shizuku-API 的 README「Unlike Bound service, the service class must implement
 * `IBinder` interface」与官方 demo 的 `UserService extends IUserService.Stub`。
 *
 * ## R8
 *
 * release 变体开了 minify，而这类要被**另一个进程反射实例化**的类不会被静态引用到，
 * 混淆或删除都会让它在release 里凭空失效。所以有 proguard 的 keep 规则，且
 * [DshShizukuShell] 传了固定的 `tag` —— 身份不能依赖类名。
 *
 * ## 这一层为什么必须自己截断输出
 *
 * binder 事务有 1MB 上限，超了抛 `TransactionTooLargeException`，而在应用侧看起来只是
 * 「通道坏了、reason 说不清」。所以输出在**这一侧**就截断并注明原长度。
 */
class DshShizukuShellService : IDshShellService.Stub() {

    /**
     * Shizuku 的保留事务：停止用户服务时调它做清理。
     *
     * 进程不会被自动杀掉（Shizuku 文档原话），所以要在这里自己退出，否则每换一次通道就
     * 多留一个以 shell/root 身份活着的进程。
     */
    override fun destroy() {
        Log.i(TAG, "destroy：用户服务退出")
        System.exit(0)
    }

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
