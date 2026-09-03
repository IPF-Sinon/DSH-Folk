package me.bmax.apatch.util

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell
import me.bmax.apatch.APApplication
import me.bmax.apatch.BuildConfig
import java.io.IOException

private const val TAG = "APatchCli"
private const val SHELL_TIMEOUT_MS = 10_000L

data class ApdExecResult(
    val success: Boolean,
    val commandLabel: String,
    val exitCode: Int? = null,
    val output: String = "",
    val errorMessage: String? = null,
)

class RootShellInitializer : Shell.Initializer() {
    override fun onInit(context: Context, shell: Shell): Boolean {
        shell.newJob().add(
            "export PATH=\$PATH:/system_ext/bin:/vendor/bin:${APApplication.APATCH_FOLDER}bin",
            "export BUSYBOX=${APApplication.APATCH_FOLDER}bin/busybox"
        ).exec()
        return true
    }
}

private fun buildWithTimeout(builder: Shell.Builder, vararg commands: String): Shell {
    var result: Shell? = null
    var error: Throwable? = null
    val t = Thread {
        try {
            result = builder.build(*commands)
        } catch (e: Throwable) {
            error = e
        }
    }
    t.name = "shell-build-${commands.firstOrNull() ?: "unknown"}"
    t.start()
    t.join(SHELL_TIMEOUT_MS)
    if (t.isAlive) {
        t.interrupt()
        throw IOException("Shell creation timed out after ${SHELL_TIMEOUT_MS}ms: ${commands.joinToString(" ")}")
    }
    return result ?: throw (error ?: IOException("Shell creation failed"))
}

/**
 * 建一个尽力而为的 shell。
 *
 * 原 APatch 会先走 KernelPatch 的 truncate <superkey> 通道再退回 su；DSH-Folk 不打内核
 * 补丁，能用的只有设备上已有的 su（Magisk / KernelSU / APatch），所以链条是
 * su [-mm] 然后 sh。拿不到 root 时返回的是普通 sh —— 调用方要用 Shell.isRoot 判断。
 */
fun createRootShell(globalMnt: Boolean = false): Shell {
    Shell.enableVerboseLogging = BuildConfig.DEBUG
    val builder = Shell.Builder.create().setInitializers(RootShellInitializer::class.java)

    if (android.os.Process.myUid() == 0) {
        try {
            return buildWithTimeout(builder, "sh")
        } catch (e: Throwable) {
            Log.e(TAG, "sh failed for root process", e)
        }
    }

    return try {
        if (globalMnt) buildWithTimeout(builder, "su", "-mm") else buildWithTimeout(builder, "su")
    } catch (e: Throwable) {
        Log.e(TAG, "su failed: ", e)
        try {
            buildWithTimeout(builder, "sh")
        } catch (e2: Throwable) {
            Log.e(TAG, "final sh fallback failed: ", e2)
            throw IOException("Unable to create any shell", e2)
        }
    }
}

private fun closeQuietly(shell: Shell?) {
    try {
        shell?.close()
    } catch (_: Throwable) {
    }
}

private fun ensureRootShell(shell: Shell, reason: String): Shell {
    if (shell.isRoot) return shell
    closeQuietly(shell)
    throw IOException("Expected root shell for $reason, but received a non-root shell")
}

object APatchCli {
    @Volatile
    private var _shell: Shell? = null
    @Volatile
    private var _globalMntShell: Shell? = null

    val SHELL: Shell
        get() = _shell ?: try {
            createRootShellSafe(false).also { _shell = it }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to create SHELL", e)
            throw e
        }

    val GLOBAL_MNT_SHELL: Shell
        get() = _globalMntShell ?: try {
            createRootShellSafe(true).also { _globalMntShell = it }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to create GLOBAL_MNT_SHELL", e)
            throw e
        }

    fun refresh() {
        val old = _shell
        try {
            _shell = createRootShellSafe(false)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to refresh shell", e)
        }
        try { old?.close() } catch (_: Throwable) {}
    }
}

internal fun createRootShellSafe(globalMnt: Boolean = false): Shell {
    return try {
        createRootShell(globalMnt)
    } catch (e: Throwable) {
        Log.e(TAG, "Root shell creation failed, falling back to sh", e)
        try {
            Shell.Builder.create().setInitializers(RootShellInitializer::class.java).build("sh")
        } catch (e2: Throwable) {
            Log.e(TAG, "Even sh fallback failed, returning non-root shell", e2)
            try {
                Shell.Builder.create().build("sh")
            } catch (e3: Throwable) {
                Log.e(TAG, "All shell creation failed, app may not function correctly", e3)
                throw IOException("Unable to create any shell. Root functionality is unavailable.", e3)
            }
        }
    }
}

internal fun createRootShellStrict(
    globalMnt: Boolean = false,
    reason: String = "unknown"
): Shell {
    return try {
        ensureRootShell(createRootShell(globalMnt), reason)
    } catch (primaryError: Throwable) {
        Log.e(TAG, "Strict root shell creation failed for $reason", primaryError)
        val fallback = createRootShellSafe(globalMnt)
        ensureRootShell(fallback, reason)
    }
}

fun getRootShell(globalMnt: Boolean = false): Shell {

    return if (globalMnt) APatchCli.GLOBAL_MNT_SHELL else {
        APatchCli.SHELL
    }
}

inline fun <T> withNewRootShell(
    globalMnt: Boolean = false,
    block: Shell.() -> T
): T {
    return createRootShell(globalMnt).use(block)
}

fun rootAvailable(): Boolean {
    val shell = getRootShell()
    return shell.isRoot
}

/**
 * 不带 initializer 的一次性 shell（bugreport 采集用）。
 *
 * 传了 [ctx] 且用户选了「特权未启用」时直接建普通 `sh`，不去碰 `su` —— 否则用户只是想
 * 导一份日志，却先被弹一次授权框。降级的代价是 dmesg / tombstones / dropbox 那几段采不到，
 * basic.txt 里的 `ElevationEnabled` 会记下这件事。
 */
fun tryGetRootShell(ctx: Context? = null): Shell {
    Shell.enableVerboseLogging = BuildConfig.DEBUG
    val builder = Shell.Builder.create()
    if (ctx != null && !me.bmax.apatch.dsh.PermissionManager.elevationEnabled(ctx)) {
        Log.i(TAG, "特权未启用，bugreport 采集使用普通 sh")
        return builder.build("sh")
    }
    return try {
        builder.build("su")
    } catch (e: Throwable) {
        Log.e(TAG, "su failed, falling back to sh: ", e)
        builder.build("sh")
    }
}

fun shellForResult(shell: Shell, vararg cmds: String): Shell.Result {
    val out = ArrayList<String>()
    val err = ArrayList<String>()
    return shell.newJob().add(*cmds).to(out, err).exec()
}

/**
 * 无条件用 root shell 跑命令。
 *
 * 现在没有调用方：只读采集全部改走 [dataShellForResult]（受「特权是否启用」约束）。
 * 保留是因为它是 FolkPatch 上游的公开工具函数，删掉会让后续 merge 无谓地冲突。
 */
@Suppress("unused")
fun rootShellForResult(vararg cmds: String): Shell.Result {
    val out = ArrayList<String>()
    val err = ArrayList<String>()
    return getRootShell().newJob().add(*cmds).to(out, err).exec()
}

@Volatile
private var _dataShell: Shell? = null

/**
 * 只读数据用的 shell：特权启用时是 root shell，未启用时是普通 `sh`。
 *
 * 存在的理由是「特权默认未启用」（见 [me.bmax.apatch.dsh.PermissionManager]）。硬件监控
 * 那一组 `cat /proc/...` 每隔几秒就跑一次，直接用 [getRootShell] 会让 libsu 自己去 spawn
 * `su` —— 用户明明选了不启用，却还是被弹授权框。而 /proc/stat、/proc/meminfo、
 * /sys/class/thermal 这些节点绝大多数设备对普通应用就是可读的，降级到 sh 只会少几行数据，
 * 调用方本来就都有 `isSuccess` 判空分支。
 */
fun dataShell(ctx: Context): Shell {
    if (me.bmax.apatch.dsh.PermissionManager.elevationEnabled(ctx)) {
        return getRootShell()
    }
    _dataShell?.let { if (it.isAlive) return it }
    return synchronized(APatchCli) {
        _dataShell?.let { if (it.isAlive) return@synchronized it }
        val sh = try {
            Shell.Builder.create().build("sh")
        } catch (e: Throwable) {
            Log.e(TAG, "non-root sh creation failed", e)
            throw IOException("Unable to create a non-root shell", e)
        }
        _dataShell = sh
        sh
    }
}

/** [dataShell] 上跑一组命令。失败不抛，交给调用方看 [Shell.Result.isSuccess]。 */
fun dataShellForResult(ctx: Context, vararg cmds: String): Shell.Result {
    val out = ArrayList<String>()
    val err = ArrayList<String>()
    return dataShell(ctx).newJob().add(*cmds).to(out, err).exec()
}

private fun configureRootProcessEnv(builder: ProcessBuilder) {
    val basePath = System.getenv("PATH").orEmpty()
    builder.environment().apply {
        this["PATH"] = "$basePath:/system_ext/bin:/vendor/bin:${APApplication.APATCH_FOLDER}bin"
        this["BUSYBOX"] = "${APApplication.APATCH_FOLDER}bin/busybox"
    }
}

fun reboot(reason: String = "") {
    if (reason == "recovery") {
        // KEYCODE_POWER = 26, hide incorrect "Factory data reset" message
        getRootShell().newJob().add("/system/bin/input keyevent 26").exec()
    }
    getRootShell().newJob()
        .add("/system/bin/svc power reboot $reason || /system/bin/reboot $reason").exec()
}
