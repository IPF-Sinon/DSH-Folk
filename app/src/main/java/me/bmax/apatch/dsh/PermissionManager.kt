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
 * 4. [Channel.NONE]     —— 都没有，或者用户没有启用特权。DSH 本身在 proot 容器里跑，
 *                          无权限也能用，只是访问 /data/data 等系统路径与部分插件功能受限。
 *
 * **默认不提权**：出厂偏好是 [PREF_OFF]（「未启用」），[refresh] 只做无副作用的探测，
 * 不会跑 `su`、不会弹授权框，[elevationEnabled] 为 false 时全应用的提权调用点都走
 * 非特权路径。用户在功能设置里选了具体通道（或「自动」）之后才真的开始用。
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
        /** su 存在且已确认拿到 uid 0（验证或缓存通过）。 */
        val rootVerified: Boolean = false,
        /** Shizuku 服务在跑（未必已授权本应用）。 */
        val shizukuRunning: Boolean = false,
        /** Shizuku 已授权本应用。 */
        val shizukuGranted: Boolean = false,
        /** 容器内 adb 通道已配对（存在 adbkey）。 */
        val adbPaired: Boolean = false,
        /** 提供 root 的实现名（Magisk / KernelSU / APatch / 未知）。 */
        val rootProvider: String = "",
        /**
         * Shizuku 服务端的 uid；Sui / root 模式跑的服务是 **0**，
         * 普通 adb 模式是 **2000**；-1 = 未知（未授权 / pre-v11 / 未接收 binder）。
         */
        val shizukuUid: Int = -1,
        /**
         * 用户首选的通道。
         *
         * - [Channel.NONE] = 显式「未启用」（出厂默认）；
         * - null = 「自动」，按可用性挑优先级最高的一条。
         */
        val preferred: Channel? = null,
        /** 首选通道不可用、已回退到 [channel]。 */
        val preferenceFellBack: Boolean = false,
    ) {
        /** Shizuku 本身就跑在 root 下（Sui）。 */
        val shizukuIsRoot: Boolean get() = shizukuUid == 0

        /**
         * 用户是否允许本应用提权。
         *
         * 与「有没有可用通道」是两件事：设备上装着 Magisk 但用户选了「未启用」时，
         * [suPresent] 仍为 true 而这里是 false —— 权限卡要能把这个区别说清楚。
         */
        val elevationEnabled: Boolean get() = preferred != Channel.NONE

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

    /**
     * 全量探测。会执行 shell，放 IO 线程调用。
     *
     * @param allowRootPrompt 是否允许真跑 `su -c id`。默认 **false**：那条命令会让
     *   Magisk/KernelSU 弹授权框，而 refresh 在首页与功能页的 LaunchedEffect 里被调用 ——
     *   于是用户每次打开首页都被弹一次，什么也没做。只有用户主动点「刷新权限」时才该弹。
     *   验过的结果记在 prefs 里，后续免弹框即可判定。
     */
    fun refresh(ctx: Context, allowRootPrompt: Boolean = false): Status {
        migratePreference(ctx)
        val preferred = readPreference(ctx)
        val enabled = preferred != Channel.NONE
        val su = detectSu()
        val provider = if (su) detectRootProvider(ctx) else ""
        // 之前验过就直接信：su 授权是持久的，撤销后用户会来点「刷新权限」重验。
        // 特权未启用时不验、也不写缓存 —— verifyRoot 会真的跑 su。
        val rootOk = su && enabled && if (allowRootPrompt) {
            verifyRoot().also { prefs(ctx).edit().putBoolean(KEY_ROOT_VERIFIED, it).apply() }
        } else {
            prefs(ctx).getBoolean(KEY_ROOT_VERIFIED, false)
        }
        val shizukuRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val shizukuGranted = shizukuRunning && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        // Sui / root 模式跑的 Shizuku 服务端 uid 是 0，普通 adb 模式是 2000。
        // getUid() 在未接收 binder 时会抛 IllegalStateException，必须包 runCatching。
        val shizukuUid = if (shizukuGranted) {
            runCatching { Shizuku.getUid() }.getOrDefault(-1)
        } else {
            -1
        }
        val adbPaired = File(DshEnv.dshHome(ctx), "adbkeys/adbkey").isFile

        // 先算出哪些通道真的可用，再按用户首选挑——有 root 不等于必须用 root。
        // 探测本身（文件是否存在 / pingBinder / 包管理器）无副作用，即使特权未启用
        // 也照常跑：权限卡要能显示「检测到 su，但你没启用」。
        val available = buildList {
            if (rootOk) add(Channel.ROOT)
            if (shizukuGranted) add(Channel.SHIZUKU)
            if (adbPaired) add(Channel.ADB)
        }
        val auto = available.firstOrNull() ?: Channel.NONE
        val channel = when {
            // 显式未启用：不挑通道，也不算「回退」
            preferred == Channel.NONE -> Channel.NONE
            preferred == null -> auto
            preferred in available -> preferred
            else -> auto
        }
        val s = Status(
            channel = channel,
            suPresent = su,
            rootVerified = rootOk,
            shizukuRunning = shizukuRunning,
            shizukuGranted = shizukuGranted,
            adbPaired = adbPaired,
            rootProvider = provider,
            shizukuUid = shizukuUid,
            preferred = preferred,
            preferenceFellBack = preferred != null &&
                preferred != Channel.NONE &&
                preferred !in available,
        )
        _status.value = s
        Log.i(
            TAG,
            "权限通道=$channel(首选=$preferred 回退=${s.preferenceFellBack}) " +
                "su=$su shizuku=$shizukuRunning/$shizukuGranted/uid=$shizukuUid adb=$adbPaired",
        )
        return s
    }

    /**
     * 用户是否允许本应用提权（不依赖 [status] 是否已经 refresh 过）。
     *
     * 给非 Compose 的调用方用：[me.bmax.apatch.util.HardwareMonitor] 之类在后台线程里
     * 直接读 prefs，不能等 StateFlow 先被填。
     */
    fun elevationEnabled(ctx: Context): Boolean {
        migratePreference(ctx)
        return readPreference(ctx) != Channel.NONE
    }

    /**
     * 读用户首选通道。
     *
     * - [PREF_OFF] → [Channel.NONE]（显式未启用，出厂默认）；
     * - [PREF_AUTO] / 未设 / 写坏了 → null（= 自动优先级）。
     *
     * 注意默认值给的是 [PREF_OFF]：这里是全应用「要不要提权」的唯一判据，
     * 缺省必须落在最保守的一边。老用户由 [migratePreference] 迁移。
     */
    fun readPreference(ctx: Context): Channel? = when (
        prefs(ctx).getString(DshEnv.KEY_PERM_CHANNEL, PREF_OFF)
    ) {
        PREF_OFF -> Channel.NONE
        PREF_ROOT -> Channel.ROOT
        PREF_SHIZUKU -> Channel.SHIZUKU
        PREF_ADB -> Channel.ADB
        else -> null
    }

    /** 写首选通道（null = 自动，[Channel.NONE] = 未启用）。调用方自己负责随后 refresh。 */
    fun setPreference(ctx: Context, channel: Channel?) {
        val v = when (channel) {
            Channel.ROOT -> PREF_ROOT
            Channel.SHIZUKU -> PREF_SHIZUKU
            Channel.ADB -> PREF_ADB
            Channel.NONE -> PREF_OFF
            null -> PREF_AUTO
        }
        prefs(ctx).edit()
            .putString(DshEnv.KEY_PERM_CHANNEL, v)
            // 迁移只该在「用户从没表态过」时跑一次；用户亲手选完之后不能再被覆盖
            .putBoolean(KEY_PERM_MIGRATED, true)
            .apply()
    }

    /**
     * 一次性迁移：把**所有**存量安装的权限偏好重置为 [PREF_OFF]。
     *
     * 旧版本默认「自动」，只要设备上有已授权的 su / Shizuku / 配对过的 ADB，应用就会
     * 自己挑一条用上 —— 用户从没被问过。所以这次不做「保留老用户既有能力」的宽容迁移：
     * 无条件回到未启用，让每个人重新明确表态一次。哪怕他此前在设置里手选过 root，
     * 那也是在「反正默认就开着」的语境下选的通道，不等于同意默认提权。
     *
     * 代价是升级后首页重启菜单消失、仪表盘少几行、bugreport 少几段（见 release notes）。
     * [KEY_ROOT_VERIFIED] 不清：它只是「授权框点过允许」的缓存，用户重新启用时
     * 不必再弹一次框。
     */
    private fun migratePreference(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean(KEY_PERM_MIGRATED, false)) return
        val explicit = p.getString(DshEnv.KEY_PERM_CHANNEL, null)
        p.edit()
            .putString(DshEnv.KEY_PERM_CHANNEL, PREF_OFF)
            .putBoolean(KEY_PERM_MIGRATED, true)
            .apply()
        Log.i(TAG, "权限偏好迁移：${explicit ?: "(未设)"} -> $PREF_OFF（无条件重置）")
    }

    /** PATH 上是否存在 su（存在≠已授权）。 */
    private fun detectSu(): Boolean = SU_PATHS.any { File(it).exists() }

    /**
     * 真跑一次 `su -c id` 确认能拿到 uid 0（会弹授权框，由用户决定）。
     *
     * 读流放单独线程：readText() 阻塞到 EOF，而授权框摆在那儿没人点时 su 不会退出 ——
     * 先读再 waitFor(10s) 的写法里那个超时是无效的，调用线程会被永久钉住。
     */
    private fun verifyRoot(): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
        val sb = StringBuilder()
        val reader = Thread {
            runCatching {
                p.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(256)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(sb) { sb.appendRange(buf, 0, n) }
                    }
                }
            }
        }
        reader.isDaemon = true
        reader.start()
        reader.join(TimeUnit.SECONDS.toMillis(10))
        if (reader.isAlive) {
            p.destroyForcibly()
            reader.join(500)
            return@runCatching false
        }
        if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
        synchronized(sb) { sb.toString() }.trim().lines().lastOrNull()?.trim() == "0"
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

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)

    /** 上次 `su -c id` 是否真的拿到了 uid 0（避免每次进首页都弹授权框）。 */
    private const val KEY_ROOT_VERIFIED = "root_verified"

    /** 存量安装重置为「未启用」的一次性迁移是否跑过（见 [migratePreference]）。 */
    private const val KEY_PERM_MIGRATED = "perm_pref_migrated"

    /** 未启用：不提权，也不跑任何会弹授权框的命令。**出厂默认**。 */
    const val PREF_OFF = "off"
    const val PREF_AUTO = "auto"
    const val PREF_ROOT = "root"
    const val PREF_SHIZUKU = "shizuku"
    const val PREF_ADB = "adb"

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
