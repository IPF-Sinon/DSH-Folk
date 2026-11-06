package me.bmax.apatch.dsh.ext

import android.content.Context
import android.os.Environment
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 扩展系统的最高安全铁律守卫。
 *
 * 为什么必须有它：扩展与宿主同权（root 通道、无障碍、全部文件）。一旦某个扩展把启动路径
 * 写坏（在 Application.onCreate 里死循环、或早期初始化就崩），用户会陷入「打开就闪退」的
 * 死循环 —— 而那时界面根本进不去，也就没有任何入口能关掉它。
 *
 * 所以留一条完全不依赖 App 界面的逃生通道：在文件系统上放一个约定名字的文件，冷启动时
 * 同步检测到就禁用全部三方扩展。三条路径覆盖三种现实场景：
 *
 * 1. dataDir/files/extensions/.disable_extensions —— 能进私有目录的用户（本 App 的文件提供器、MT 管理器）；
 * 2. getExternalFilesDir()/disable_extensions —— 插电脑用 MTP 就能新建，不需要任何权限；
 * 3. /sdcard/Download/DSH-Folk/.disable_extensions —— 手机自带文件管理器直接建。
 *
 * 连续崩溃达 CRASH_THRESHOLD 次时也会自动落这个标志（见 APatchApp），
 * 用户不需要知道这个机制也能自救。
 */
object ExtensionSafetyGuard {
    /** 连续崩溃多少次后自动熔断。取 3：偶发一次崩溃不该把扩展全杀掉。 */
    const val CRASH_THRESHOLD = 3

    /** 标志文件名（带点 / 不带点 / .txt 三种写法都认，照顾不同文件管理器的新建习惯）。 */
    val KILL_SWITCH_NAMES = listOf(
        ".disable_extensions",
        "disable_extensions",
        "disable_extensions.txt",
    )

    /** 本次进程是否已熔断（一旦为真，整个进程生命周期不再加载任何三方扩展）。 */
    private val latched = AtomicBoolean(false)

    /** 熔断原因（供首页 / 扩展页显示不可遮蔽的原生横幅）。 */
    @Volatile
    var reason: String = ""
        private set

    /** 是否处于安全模式（内存熔断位，或磁盘上发现标志文件）。 */
    fun isSafeMode(ctx: Context?): Boolean =
        latched.get() || (ctx != null && findKillSwitch(ctx) != null)

    /** 只看内存熔断位（不含磁盘探测）。 */
    fun isLatched(): Boolean = latched.get()

    /**
     * 改熔断位时**同时**同步到 :ext-api 的 [ExtensionGate]。
     *
     * 为什么必须两边都改：注册中心（Service/Hook/Ui）在 ext-api 模块里，它们不认识 Context、
     * 也不该认识熔断文件，只能读那个门。只改本对象的 latched 会让「熔断后扩展仍在生效」——
     * 那是这套系统最不能出现的一种状态。
     */
    private fun setLatched(value: Boolean) {
        latched.set(value)
        ExtensionGate.latched = value
    }

    /**
     * 把扩展侧日志接到宿主日志（ExtLog 在 ext-api 里，默认空实现）。
     *
     * 由 [me.bmax.apatch.APatchApp] 在启动时调用一次；不装也不会崩，只是扩展的日志进不了 dsh.log。
     */
    fun installLogSink() {
        // **扩展日志一律只进 logcat**，绝不写 dsh.log。
        //
        // 为什么：dsh.log 是「启动 dsh 运行时」的日志，用户会去「设置 → 日志」里读它。
        // 扩展的加载诊断混在里面，既干扰用户看启动过程，也把真正的 dsh 故障淹没掉
        // （这条是用户明确要求改的）。排查时看 logcat 即可 —— bugreport 里本来就带 logcat.txt。
        ExtLog.sink = { line -> android.util.Log.w("DSH-Ext", line) }
        ExtLog.infoSink = { line -> android.util.Log.i("DSH-Ext", line) }
    }

    /**
     * 所有受监控的标志文件候选路径。
     *
     * 顺序即优先级：内部目录最可靠（一定写得了），公共下载目录最方便（一定不需要权限）。
     */
    fun killSwitchCandidates(ctx: Context): List<File> {
        val out = ArrayList<File>()
        val extDir = ExtensionLayout.root(ctx)
        for (name in KILL_SWITCH_NAMES) {
            out.add(File(extDir, name))
            out.add(File(ctx.filesDir, name))
        }
        runCatching {
            ctx.getExternalFilesDir(null)?.let { extFiles ->
                for (name in KILL_SWITCH_NAMES) out.add(File(extFiles, name))
            }
        }
        runCatching {
            @Suppress("DEPRECATION")
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = File(downloads, "DSH-Folk")
            for (name in KILL_SWITCH_NAMES) out.add(File(dir, name))
        }
        return out
    }

    /** 找到第一个存在的标志文件（没有则 null）。 */
    fun findKillSwitch(ctx: Context): File? =
        killSwitchCandidates(ctx).firstOrNull { runCatching { it.exists() }.getOrDefault(false) }

    /**
     * 进程启动时第一件事：同步检查标志文件，命中就熔断。
     *
     * 必须在任何扩展类被加载之前调用（APatchApp.onCreate 最顶端），且只做文件 stat、
     * 绝不抛异常 —— 它自己崩了，逃生通道就没了。
     */
    fun checkAndEnforce(ctx: Context) {
        runCatching {
            val marker = findKillSwitch(ctx)
            if (marker != null) {
                setLatched(true)
                reason = "检测到禁用标志文件：" + marker.absolutePath
                ExtLog.log("安全模式：" + reason + "（本次启动不加载任何扩展）")
                ExtensionRegistry.clearEnabled(ctx)
            }
        }.onFailure {
            ExtLog.log("安全模式检测异常（已忽略）：" + (it.message ?: it.javaClass.simpleName))
        }
    }

    /**
     * 熔断：清空已启用集合，并在 writeMarker 为真时落一个标志文件（让下次冷启动仍然安全）。
     *
     * @param writeMarker 崩溃自救 / 用户主动禁用时为 true；仅本次启动临时禁用为 false。
     */
    fun latch(ctx: Context, why: String, writeMarker: Boolean) {
        runCatching {
            setLatched(true)
            reason = why
            ExtensionRegistry.clearEnabled(ctx)
            ExtLog.log("进入安全模式：" + why)
            if (writeMarker) writeKillSwitch(ctx, why)
        }.onFailure {
            ExtLog.log("写入禁用标志失败（已忽略）：" + (it.message ?: it.javaClass.simpleName))
        }
    }

    /** 在第一个可写位置落标志文件。 */
    fun writeKillSwitch(ctx: Context, why: String): File? {
        for (file in killSwitchCandidates(ctx)) {
            val ok = runCatching {
                file.parentFile?.mkdirs()
                file.writeText(why + "\n", Charsets.UTF_8)
                true
            }.getOrDefault(false)
            if (ok) {
                ExtLog.log("已写入禁用标志：" + file.absolutePath)
                return file
            }
        }
        return null
    }

    /** 用户主动「重新启用扩展」：删掉标志文件并解除熔断位。 */
    fun clearSafeMode(ctx: Context): Boolean {
        for (file in killSwitchCandidates(ctx)) {
            runCatching { file.delete() }
        }
        setLatched(false)
        reason = ""
        resetCrashCount(ctx)
        ExtLog.log("已退出安全模式")
        return true
    }

    /**
     * 这条路径是否属于扩展不得触碰的保护范围。
     *
     * 判定用 canonicalPath：扩展完全可以用符号链接指到标志文件再删掉，只比字符串会被绕过。
     */
    fun isProtectedPath(ctx: Context, target: File): Boolean {
        val canon = runCatching { target.canonicalPath }.getOrDefault(target.absolutePath)
        for (file in killSwitchCandidates(ctx)) {
            val protected = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            if (canon == protected) return true
            // 父目录也算：扩展不能删掉整个 extensions 目录来「顺带」清掉标志
            if (canon == runCatching { file.parentFile?.canonicalPath }.getOrNull()) return true
        }
        return false
    }

    // ── 崩溃计数（供 APatchApp 的 uncaughtException 用） ──

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("dsh_ext_state", Context.MODE_PRIVATE)

    /** 启动成功一次就清零。由 APatchApp 在服务就绪后调用。 */
    fun resetCrashCount(ctx: Context) {
        runCatching { prefs(ctx).edit().putInt("crash_count", 0).apply() }
    }

    /** 记一次崩溃；达到阈值时熔断并落标志文件。返回是否触发了自动熔断。 */
    fun noteCrash(ctx: Context, detail: String): Boolean {
        val n = runCatching {
            val p = prefs(ctx)
            val next = p.getInt("crash_count", 0) + 1
            p.edit().putInt("crash_count", next).apply()
            next
        }.getOrDefault(CRASH_THRESHOLD)
        if (n < CRASH_THRESHOLD) return false
        latch(ctx, "连续崩溃 " + n + " 次，已自动禁用全部扩展（" + detail + "）", writeMarker = true)
        return true
    }

    /** 崩溃栈是否来自扩展（决定要不要计入扩展崩溃）。 */
    fun stackTouchesExtension(trace: String?): Boolean {
        if (trace.isNullOrEmpty()) return false
        return trace.contains("me.bmax.apatch.dsh.ext") || trace.contains("DexClassLoader")
    }
}
