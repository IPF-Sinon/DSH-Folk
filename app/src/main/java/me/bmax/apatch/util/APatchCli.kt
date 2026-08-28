package me.bmax.apatch.util

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.system.Os
import android.util.Log
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import com.topjohnwu.superuser.io.SuFile
import me.bmax.apatch.APApplication
import me.bmax.apatch.APApplication.Companion.SUPERCMD
import me.bmax.apatch.BuildConfig
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

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

fun createRootShell(globalMnt: Boolean = false): Shell {
    Shell.enableVerboseLogging = BuildConfig.DEBUG
    val builder = Shell.Builder.create().setInitializers(RootShellInitializer::class.java)

    if (android.os.Process.myUid() == 0 && !globalMnt) {
        try {
            return buildWithTimeout(builder, "sh")
        } catch (e: Throwable) {
            Log.e(TAG, "sh failed for root process", e)
        }
    }

    return try {
        buildWithTimeout(
            builder, SUPERCMD, APApplication.superKey, "-Z", APApplication.MAGISK_SCONTEXT
        )
    } catch (e: Throwable) {
        Log.e(TAG, "su failed: ", e)
        return try {
            Log.e(TAG, "retry compat kpatch su")
            if (globalMnt) {
                buildWithTimeout(
                    builder,
                    getKPatchPath(), APApplication.superKey, "su", "-Z", APApplication.MAGISK_SCONTEXT, "--mount-master"
                )
            }else{
                buildWithTimeout(
                    builder,
                    getKPatchPath(), APApplication.superKey, "su", "-Z", APApplication.MAGISK_SCONTEXT
                )
            }
        } catch (e: Throwable) {
            Log.e(TAG, "retry kpatch su failed: ", e)
            return try {
                Log.e(TAG, "retry su: ", e)
                if (globalMnt) {
                    buildWithTimeout(builder, "su","-mm")
                }else{
                    buildWithTimeout(builder, "su")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "retry su failed: ", e)
                try {
                    buildWithTimeout(builder, "sh")
                } catch (e2: Throwable) {
                    Log.e(TAG, "final sh fallback failed: ", e2)
                    throw IOException("Unable to create any shell", e2)
                }
            }
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

fun tryGetRootShell(): Shell {
    Shell.enableVerboseLogging = BuildConfig.DEBUG
    val builder = Shell.Builder.create()
    return try {
        builder.build(
            SUPERCMD, APApplication.superKey, "-Z", APApplication.MAGISK_SCONTEXT
        )
    } catch (e: Throwable) {
        Log.e(TAG, "su failed: ", e)
        return try {
            Log.e(TAG, "retry compat kpatch su")
            builder.build(
                getKPatchPath(), APApplication.superKey, "su", "-Z", APApplication.MAGISK_SCONTEXT
            )
        } catch (e: Throwable) {
            Log.e(TAG, "retry kpatch su failed: ", e)
            return try {
                Log.e(TAG, "retry su: ", e)
                builder.build("su")
            } catch (e: Throwable) {
                Log.e(TAG, "retry su failed: ", e)
                builder.build("sh")
            }
        }
    }
}

fun shellForResult(shell: Shell, vararg cmds: String): Shell.Result {
    val out = ArrayList<String>()
    val err = ArrayList<String>()
    return shell.newJob().add(*cmds).to(out, err).exec()
}

fun rootShellForResult(vararg cmds: String): Shell.Result {
    val out = ArrayList<String>()
    val err = ArrayList<String>()
    return getRootShell().newJob().add(*cmds).to(out, err).exec()
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
