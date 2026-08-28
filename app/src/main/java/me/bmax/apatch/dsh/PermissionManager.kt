package me.bmax.apatch.dsh

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.bmax.apatch.R
import rikka.shizuku.Shizuku

/**
 * 权限通道探测。
 *
 * DSH-Folk **不打内核补丁**（apd/fpd/KernelPatch 那一整套已移除），而是探测并复用设备上
 * 已有的提权途径，按优先级选一条生效通道：
 *
 * 1. [Channel.ROOT]     —— Magisk / KernelSU / APatch 提供的 `su`（用 libsu 验证真能拿到 uid 0）；
 * 2. [Channel.SHIZUKU]  —— 已安装并授权的 Shizuku / Sui（uid=2000 shell，或 Sui 的 uid 0）；
 * 3. [Channel.ADB]      —— 容器内经无线调试配对直连本机 adbd（DSHA 方案，见 [AdbBridge]）；
 * 4. [Channel.NONE]     —— 都没有。DSH 本身在 proot 容器里跑，无权限也能用，
 *                          只是访问 /data/data 等系统路径与部分插件功能受限。
 *
 * 首页「权限」卡展示当前生效通道。
 */
object PermissionManager {
    private const val TAG = "DSH-Folk-Perm"

    enum class Channel { NONE, ROOT, SHIZUKU, ADB }

    data class Status(
        val channel: Channel = Channel.NONE,
        /** 是否检测到 su 可执行（不代表已授权）。 */
        val suPresent: Boolean = false,
        /** Shizuku 服务在跑（未必已授权本应用）。 */
        val shizukuRunning: Boolean = false,
        /** Shizuku 已授权本应用。 */
        val shizukuGranted: Boolean = false,
        /** 容器内 adb 通道已配对（存在 adbkey）。 */
        val adbPaired: Boolean = false,
        /** 提供 root 的实现名（Magisk / KernelSU / APatch / 未知）。 */
        val rootProvider: String = "",
    ) {
        /**
         * 通道名的字符串资源 id。
         *
         * 不在这里直接拼中文：这个类被首页六套布局和功能页共用，
         * 显示层才有 Context/Composable 能取到当前语言。
         * root 实现名（Magisk / KernelSU / …）由 [rootProvider] 单独给出。
         */
        val labelRes: Int
            get() = when (channel) {
                Channel.ROOT -> R.string.dsh_perm_root
                Channel.SHIZUKU -> R.string.dsh_perm_shizuku
                Channel.ADB -> R.string.dsh_perm_adb
                Channel.NONE -> R.string.dsh_perm_none
            }

        /** 已本地化的通道名，root 时带上实现名。 */
        fun label(ctx: Context): String {
            val base = ctx.getString(labelRes)
            return if (channel == Channel.ROOT && rootProvider.isNotEmpty()) "$base · $rootProvider"
            else base
        }
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** 全量探测。会执行 shell，放 IO 线程调用。 */
    fun refresh(ctx: Context): Status {
        val su = detectSu()
        val provider = if (su) detectRootProvider(ctx) else ""
        val rootOk = su && verifyRoot()
        val shizukuRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val shizukuGranted = shizukuRunning && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val adbPaired = File(DshEnv.dshHome(ctx), "adbkeys/adbkey").isFile

        val channel = when {
            rootOk -> Channel.ROOT
            shizukuGranted -> Channel.SHIZUKU
            adbPaired -> Channel.ADB
            else -> Channel.NONE
        }
        val s = Status(
            channel = channel,
            suPresent = su,
            shizukuRunning = shizukuRunning,
            shizukuGranted = shizukuGranted,
            adbPaired = adbPaired,
            rootProvider = provider,
        )
        _status.value = s
        Log.i(TAG, "权限通道=$channel su=$su shizuku=$shizukuRunning/$shizukuGranted adb=$adbPaired")
        return s
    }

    /** PATH 上是否存在 su（存在≠已授权）。 */
    private fun detectSu(): Boolean = SU_PATHS.any { File(it).exists() }

    /** 真跑一次 `su -c id` 确认能拿到 uid 0（会弹授权框，由用户决定）。 */
    private fun verifyRoot(): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        if (!p.waitFor(10, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return@runCatching false
        }
        out.lines().lastOrNull()?.trim() == "0"
    }.getOrDefault(false)

    /** 通过已安装包名判断 root 实现（只读包管理器，无副作用）。 */
    private fun detectRootProvider(ctx: Context): String {
        val pm = ctx.packageManager
        for ((pkg, name) in ROOT_PROVIDERS) {
            val installed = runCatching {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
                true
            }.getOrDefault(false)
            if (installed) return name
        }
        return ""
    }

    private val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/debug_ramdisk/su",
    )

    private val ROOT_PROVIDERS = listOf(
        "com.topjohnwu.magisk" to "Magisk",
        "me.weishu.kernelsu" to "KernelSU",
        "me.bmax.apatch" to "APatch",
        "io.github.a13e300.ksuwebui" to "KernelSU",
    )
}
