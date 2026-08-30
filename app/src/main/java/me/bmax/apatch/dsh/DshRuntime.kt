package me.bmax.apatch.dsh

import android.content.Context
import android.os.StatFs
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import org.json.JSONObject

/** 运行时阶段。 */
enum class DshPhase { NOT_READY, DOWNLOADING, EXTRACTING, STARTING, RUNNING, ERROR }

/** 运行时状态快照（首页卡片与启动日志面板消费）。 */
data class DshState(
    val phase: DshPhase = DshPhase.NOT_READY,
    val progress: Float = 0f,
    val speedBytesPerSec: Long = 0L,
    val message: String = "",
    val port: Int = DshEnv.DEFAULT_PORT,
    val pid: Long? = null,
    val runtimeVersion: String? = null,
    val installed: Boolean = false,
    /**
     * 容器体积（字节），0 表示还没算过。
     *
     * 走缓存 + 后台重算而不是现算：见 [DshEnv.KEY_ROOTFS_SIZE]。
     */
    val rootfsSizeBytes: Long = 0L,
) {
    val webUrl: String get() = "http://127.0.0.1:$port/"
}

/** 运行时下载元数据（由 CI 生成的 metadata.json 提供）。 */
data class DshMeta(
    val version: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val mirrors: List<String> = emptyList(),
    val arch: String = "",
    val dsh: String = "",
    val nodeVersion: String = "",
    val builtAt: String = "",
)

/**
 * DSH 运行时管理：在线下载 rootfs → 解压 → 用 proot/proroot 进容器起 `dsh web`。
 *
 * 组合了两个上游的做法：
 * - 容器执行层（proot argv / 环境变量 / 硬链接探测 / proroot 三振出局回退）来自 DSHA；
 * - 在线交付层（metadata.json + 多镜像测速竞速 + sha256 + 进度）来自 DSHM。
 *
 * 关键约束（Android 平台限制，不是选择）：
 * - 可执行的 proot/proroot 只能从 `nativeLibraryDir` 运行（app 私有目录是 SELinux noexec）；
 * - rootfs 必须落在 filesDir（可写），容器内的一切执行由 proot 代理。
 */
object DshRuntime {
    private const val TAG = "DSH-Folk"
    /**
     * 等服务就绪的上限。
     *
     * 从 180s 压到 90s：proroot 现在失败一次就回退（见 [PROROOT_FAIL_LIMIT]），
     * 而「卡住不退」的 proroot 唯一表现就是耗尽这个超时 —— 等待越久，用户从
     * 坏运行时走到可用运行时的时间就越长。node 起 dsh web 正常在 10s 内。
     */
    private const val READY_TIMEOUT_MS = 90_000L
    /**
     * proroot 连续失败到此次数即强制回退 proot 并清掉用户选择。
     *
     * 取 1（失败即回退）：proroot 的失败模式是内核相关的确定性失败，重试同一台
     * 设备不会有不同结果，让用户白等第二、三次没有意义。
     */
    private const val PROROOT_FAIL_LIMIT = 1

    /** profile pnpm 设置里固定导入方式（见 [ensureProfilePnpmSettings]）。 */
    private const val PNPM_IMPORT_KEY = "packageImportMethod"
    private const val PNPM_IMPORT_LINE = "packageImportMethod: copy"

    /** v1.3 写进 /root/.npmrc 的无效行，只为清理它而保留。 */
    private const val NPMRC_LEGACY_LINE = "package-import-method=copy"

    /** web profile 在 rootfs 内的相对路径（guest 侧是 /root/.dsh/profiles/web）。 */
    private const val PROFILE_GUEST_REL = "root/.dsh/profiles/web"

    /**
     * 首启预装的插件（npm 包名，已人工验证可装）。
     *
     * 手机上没有这几个体验差很多：dsh-web-mobile 做移动端适配，dshmarket 提供
     * WebUI 内的插件市场，dsh-config-manager 则是**本应用配置备份的依赖** ——
     * 设置里的导出/导入走的正是它的回环 HTTP API（见 [DshConfigBackup]），
     * 没装的话那一页直接不可用。
     */
    private val SEED_PLUGINS = listOf("dsh-web-mobile", "dshmarket", "dsh-config-manager")

    /**
     * 插件树/客户端包加载失败的日志签名。
     *
     * 命中这些就**不能**判定为 proroot 故障：它与容器运行时无关，换 proot 重试
     * 照样失败（真机实测就是这样），而 [PROROOT_FAIL_LIMIT] = 1 会一次就把用户的
     * proroot 偏好静默清掉。
     */
    private val PLUGIN_FAILURE_MARKS = listOf(
        "plugin tree failed to load",
        "client bundles not found",
        "failed to apply loader entry modules",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(DshState())
    val state: StateFlow<DshState> = _state.asStateFlow()

    private lateinit var appContext: Context
    private var serverProcess: Process? = null
    private var startedAt: Long = 0L

    @Volatile private var hardlinkOk: Boolean? = null

    /**
     * 硬链接探测失败的原因（异常类名 + message），成功时为空。
     *
     * 必须留下来：原来只走 Log.i，而 logcat 在 bugreport 里只有最近几分钟，
     * App 早已启动完，那一行永远抓不到 —— 于是「为什么这台设备不支持硬链接」
     * 每次都只能靠猜。现在它会随 [startServer] 一起写进 dsh.log。
     */
    @Volatile private var hardlinkDetail: String = ""

    /**
     * 只绑定 Context，不做任何 IO。**必须在任何 Composable 读取本对象之前调用**
     * （APApplication.onCreate 里）。
     *
     * 原来只有 attach() 一个入口，而它是在首页的 LaunchedEffect 里调的 —— 那时
     * 首次 composition 已经跑完了，composition 期间读 runtimeId() / port() 会撞上
     * 未初始化的 lateinit 直接崩掉（真机实测：进首页几秒后
     * UninitializedPropertyAccessException）。
     */
    fun init(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
            // 进程重启后旧日志不该残留：清一次，让启动日志按「本次运行」呈现。
            // startServer() 里还会再清一次，这里主要覆盖「只开 App 不启动服务」的情况。
            clearLog()
        }
    }

    /** Context 是否已绑定。未绑定时所有读接口给默认值而不是抛异常。 */
    private val ready: Boolean get() = ::appContext.isInitialized

    fun attach(context: Context) {
        init(context)
        val installed = DshEnv.isRuntimeInstalled(appContext)
        val cachedSize = prefs().getLong(DshEnv.KEY_ROOTFS_SIZE, 0L)
        _state.update {
            it.copy(
                installed = installed,
                port = port(),
                runtimeVersion = prefs().getString(DshEnv.KEY_RUNTIME_VERSION, null),
                rootfsSizeBytes = if (installed) cachedSize else 0L,
                phase = if (installed) it.phase else DshPhase.NOT_READY,
            )
        }
        // 装好了但还没量过（升级上来的旧安装）：后台补一次，别让界面一直显示「—」
        if (installed && cachedSize <= 0L) refreshRootfsSize()
    }

    /**
     * 后台重算容器体积并落盘缓存。
     *
     * 只允许从这里进入 [dirSize]：它要遍历十万级文件，在组合期同步调用会让
     * 每次导航回首页都卡两秒以上（真机 MIUIScout 实测 duration=2505ms）。
     */
    fun refreshRootfsSize() {
        if (!ready) return
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { dirSize(DshEnv.rootfs(appContext)) }
            prefs().edit().putLong(DshEnv.KEY_ROOTFS_SIZE, bytes).apply()
            _state.update { it.copy(rootfsSizeBytes = bytes) }
        }
    }

    private fun prefs() = appContext.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)

    fun port(): Int {
        if (!ready) return DshEnv.DEFAULT_PORT
        return prefs().getInt(DshEnv.KEY_PORT, DshEnv.DEFAULT_PORT).let {
            if (it in 1..65535) it else DshEnv.DEFAULT_PORT
        }
    }

    fun setPort(p: Int) {
        if (!ready || p !in 1..65535) return
        prefs().edit().putInt(DshEnv.KEY_PORT, p).apply()
        _state.update { it.copy(port = p) }
    }

    // ────────────────────────── 容器运行时选择 ──────────────────────────

    /**
     * 当前选择的运行时 id。
     *
     * 默认 proroot：它不走 ptrace，容器内进程开销明显低于 proot。代价是在部分
     * 内核上会卡在 seccomp/ptrace 上起不来 —— 这条路由 [noteProrootFailure]
     * 兜底，失败一次就自动切回 proot，所以默认选快的那个是合理的。
     */
    fun runtimeId(): String =
        if (!ready) "proroot" else prefs().getString(DshEnv.KEY_RUNTIME, "proroot") ?: "proroot"

    fun setRuntimeId(id: String) {
        if (!ready) return
        prefs().edit().putString(DshEnv.KEY_RUNTIME, id).putInt(DshEnv.KEY_PROROOT_FAIL, 0).apply()
    }

    /** 解析出实际可用的运行时；选中的不可用时静默回退 proot。 */
    fun runtime(): ContainerRuntime {
        val proot = ContainerRuntime.Proot(appContext, nativeLib("libproot.so"))
        if (runtimeId() != "proroot") return proot
        val proroot = ContainerRuntime.Proroot(appContext, ContainerRuntime.Proroot.defaultDir(appContext))
        return if (proroot.available()) proroot else proot
    }

    /**
     * 刚刚发生过 proroot → proot 的自动回退，等着用 proot 重试一次。
     *
     * 存在的理由：[PROROOT_FAIL_LIMIT] 是 1，失败即回退。如果只写一行
     * 「已切回 proot，请重新启动」，用户首次开应用就会撞上一次失败并被
     * 要求手动重试——而此时我们已经知道该用 proot 了。
     */
    @Volatile
    private var prorootFellBack = false

    /**
     * 记一次 proroot 启动失败；达上限强制切回 proot。返回是否触发了回退。
     *
     * 插件层面的失败不算：见 [PLUGIN_FAILURE_MARKS]。
     */
    fun noteProrootFailure(why: String): Boolean {
        if (runtimeId() != "proroot") return false
        if (lastLogSuggestsPluginFailure()) {
            appendLog("! 失败发生在插件树加载阶段，与容器运行时无关，保留 proroot")
            return false
        }
        val n = prefs().getInt(DshEnv.KEY_PROROOT_FAIL, 0) + 1
        return if (n >= PROROOT_FAIL_LIMIT) {
            prefs().edit().putString(DshEnv.KEY_RUNTIME, "proot").putInt(DshEnv.KEY_PROROOT_FAIL, 0).apply()
            val times = if (n > 1) "连续 $n 次" else ""
            appendLog("! proroot ${times}启动失败（$why），已自动切回 proot")
            prorootFellBack = true
            true
        } else {
            prefs().edit().putInt(DshEnv.KEY_PROROOT_FAIL, n).apply()
            false
        }
    }

    /**
     * 启动日志尾部是否指向插件树加载失败。
     *
     * 保守匹配：宁可漏判（退回旧的误降级行为）也不误判（把真正的 proroot 故障
     * 当成插件问题、让用户一直卡在坏运行时上）。
     */
    private fun lastLogSuggestsPluginFailure(): Boolean {
        if (!ready) return false
        val tail = runCatching {
            LogStore.named(DshEnv.serverLog(appContext)).tail(200)
        }.getOrDefault("")
        if (tail.isEmpty()) return false
        return PLUGIN_FAILURE_MARKS.any { tail.contains(it, ignoreCase = true) }
    }

    /** 插件树加载失败的可修复提示；无此迹象时返回 null。 */
    fun pluginTreeFailureHint(): String? =
        if (lastLogSuggestsPluginFailure()) str(R.string.dsh_err_plugin_tree_failed) else null

    private fun nativeLib(name: String): File = File(DshEnv.nativeLibDir(appContext), name)

    /**
     * rootfs 所在文件系统是否支持真实硬链接。
     *
     * 支持时 proot 不加 `--link2symlink`：该扩展把 `link()` 目标改写成指向临时中间文件的
     * 符号链接，而 dsh 的 write 工具正是用 `link(临时文件, 目标)` 发布后立刻删临时目录 ——
     * 于是新建文件 100% 变悬空链接（write 报成功但读不出来）。
     */
    fun hardlinkSupported(): Boolean {
        hardlinkOk?.let { return it }
        synchronized(this) {
            hardlinkOk?.let { return it }
            val dir = DshEnv.rootfs(appContext).takeIf { it.isDirectory } ?: appContext.filesDir
            val src = File(dir, ".dshfolk-linkprobe")
            val dst = File(dir, ".dshfolk-linkprobe.hl")
            var ok = false
            var detail = ""
            try {
                dir.mkdirs()
                src.delete(); dst.delete()
                Files.write(src.toPath(), byteArrayOf('o'.code.toByte(), 'k'.code.toByte()))
                Files.createLink(dst.toPath(), src.toPath())
                ok = dst.isFile && dst.length() == 2L
                if (!ok) detail = "link 成功但目标不可读"
            } catch (e: Throwable) {
                ok = false
                detail = "${e.javaClass.simpleName}: ${e.message}"
            } finally {
                runCatching { src.delete() }
                runCatching { dst.delete() }
            }
            android.util.Log.i(TAG, "硬链接支持=$ok${if (detail.isEmpty()) "" else "（$detail）"}")
            hardlinkOk = ok
            hardlinkDetail = detail
            return ok
        }
    }

    /** 硬链接探测失败的原因；成功时为空。供启动日志记录用。 */
    fun hardlinkDetail(): String = hardlinkDetail

    /**
     * guest 里的 `link()` 是否会被改写成符号链接。
     *
     * 不能只看 [hardlinkSupported]：proroot **无条件**加 `--link2symlink`
     * （见 ContainerRuntime.Proroot.baseArgv），所以哪怕宿主文件系统支持真硬链接，
     * 容器内拿到的仍然是符号链接。pnpm 正是用 `link()` 从内容存储装包，一旦被改写，
     * `require.resolve` 的 realpath 就会跳进 CAS、把包目录结构解析没了。
     */
    fun linkBecomesSymlink(): Boolean = !hardlinkSupported() || runtimeId() == "proroot"

    /** 复制 proot 的 NEEDED 依赖到可写 lib 目录（proroot 不需要）。 */
    private fun ensureRuntimeFiles() {
        val libDir = File(appContext.filesDir, "lib").apply { mkdirs() }
        DshEnv.tmpDir(appContext).mkdirs()
        // 每条 exec 路径都要保证 DNS 在：冷启动直接进插件页 / 终端页时不会走 bootstrap，
        // 少了这一步容器里 pnpm、apt 全是 EAI_AGAIN。已存在则原样保留（用户可能改过）。
        ensureContainerDns()
        ensureProfilePnpmSettings()
        if (runtime().id() != "proot") return
        // jniLibs 里叫 libtalloc.so / libandroidshmem.so，proot 按 SONAME 找
        copyExec(nativeLib("libtalloc.so"), File(libDir, "libtalloc.so.2"))
        copyExec(nativeLib("libandroidshmem.so"), File(libDir, "libandroid-shmem.so"))
    }

    private fun copyExec(src: File, dst: File) {
        if (!src.isFile) return
        if (dst.isFile && dst.length() == src.length()) return
        runCatching {
            src.inputStream().use { i -> FileOutputStream(dst).use { o -> i.copyTo(o) } }
            dst.setExecutable(true, false)
        }
    }

    // ────────────────────────── 进容器执行 ──────────────────────────

    private fun baseArgv(): MutableList<String> =
        runtime().baseArgv(DshEnv.rootfs(appContext), hardlinkSupported()).toMutableList()

    /** 容器内与宿主无关的 guest 环境 + 运行时专用环境。 */
    private fun applyEnv(pb: ProcessBuilder) {
        val rt = runtime()
        val libDir = File(appContext.filesDir, "lib")
        val env = pb.environment()
        if (rt.id() == "proot") {
            env["PROOT_TMP_DIR"] = DshEnv.tmpDir(appContext).absolutePath
            env["PROOT_LOADER"] = nativeLib("libprootloader.so").absolutePath
            env["PROOT_LOADER_32"] = nativeLib("libprootloader32.so").absolutePath
            env["LD_LIBRARY_PATH"] = "${libDir.absolutePath}:${DshEnv.nativeLibDir(appContext).absolutePath}"
            // 无硬链接时把 l2s 中间文件集中到 rootfs 内（默认就近存放会随 tmp 被清掉）
            if (!hardlinkSupported()) {
                val l2s = DshEnv.l2sDir(appContext).apply { mkdirs() }
                env["PROOT_L2S_DIR"] = l2s.absolutePath
            }
        }
        runCatching { rt.applyEnv(env, appContext.filesDir, libDir, DshEnv.tmpDir(appContext)) }
        // guest 侧环境：PATH 必须覆盖，否则继承 Android 的 /system/bin 找不到 bash 工具链
        env["HOME"] = "/root"
        env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        env["TMPDIR"] = "/tmp"
        env["LANG"] = "C.UTF-8"
        env["DEBIAN_FRONTEND"] = "noninteractive"
        env["TERM"] = "xterm-256color"
    }

    /** 在 rootfs 内执行一条 bash 命令（stderr 合并进 stdout）。 */
    fun execRootfs(bashCommand: String): Process {
        ensureRuntimeFiles()
        val argv = baseArgv()
        argv += listOf("/bin/bash", "-c", bashCommand)
        val pb = ProcessBuilder(argv).redirectErrorStream(true)
        pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        applyEnv(pb)
        return pb.start()
    }

    /** 启动交互式 bash（供终端页；cd/export 状态保持）。 */
    fun execRootfsInteractive(): Process {
        ensureRuntimeFiles()
        val argv = baseArgv()
        argv += "/bin/bash"
        val pb = ProcessBuilder(argv).redirectErrorStream(true)
        applyEnv(pb)
        pb.environment()["DSH_INTERACTIVE"] = "1"
        return pb.start()
    }

    /**
     * PTY 会话的 argv（终端页用）。默认 `bash -l`，也可指定 guest 命令。
     *
     * 与 [execRootfs] 共用 [baseArgv]：各写一份的话，PTY 里的 shell 会跑在和普通命令
     * 不一样的环境里，少一个 PROOT_LOADER 就直接起不来。
     */
    fun ptyArgv(vararg guestCmd: String): Array<String> {
        ensureRuntimeFiles()
        val argv = baseArgv()
        if (guestCmd.isEmpty()) {
            argv += "/bin/bash"
            argv += "-l"
        } else {
            argv += guestCmd
        }
        return argv.toTypedArray()
    }

    /**
     * PTY 会话的环境变量（`KEY=VALUE` 形式）。
     *
     * 借一个临时 ProcessBuilder 收集，而不是重抄一份：环境构造分散在 [applyEnv] 与
     * [ContainerRuntime.applyEnv]，还随 proot/proroot 分叉，手抄必漏。
     */
    fun ptyEnv(): Array<String> {
        val probe = ProcessBuilder("/system/bin/true")
        applyEnv(probe)
        return probe.environment()
            .filter { it.key != null && it.value != null }
            .map { "${it.key}=${it.value}" }
            .toTypedArray()
    }

    /**
     * 同步执行并读回输出（带超时，默认 60s）。
     *
     * 读流必须放到单独线程：readText() 会一直阻塞到 EOF，先读再 waitFor(timeout) 的写法
     * 里超时是彻底无效的 —— 子进程挂住不退出（等 stdin、卡在网络、pnpm 死锁）就会把调用
     * 线程永久钉死，容器进程也泄漏。这里改成读线程 + join(超时) + destroyForcibly。
     *
     * 超时返回已读到的部分而不是空串：pnpm / pip 那种半途卡死的情况，前面几百行输出
     * 往往正好说明卡在哪。
     */
    fun execRootfsForOutput(bashCommand: String, timeoutMs: Long = 60_000L): String =
        execRootfsStreaming(bashCommand, timeoutMs) {}

    /**
     * 同上，但每读到一整行就回调一次。
     *
     * 存在的理由：[execRootfsForOutput] 把输出攒到结束才一次返回，而
     * `dsh plugin add` 背后的 pnpm 可能跑几分钟——这段时间里界面拿不到
     * 任何进展，用户无法判断是在装还是卡死了。
     *
     * onLine 在读线程上调用（不是主线程），回调里不要直接碰 Compose 状态。
     */
    fun execRootfsStreaming(
        bashCommand: String,
        timeoutMs: Long = 60_000L,
        onLine: (String) -> Unit,
    ): String = runCatching {
        val p = execRootfs(bashCommand)
        val sb = StringBuilder()
        val reader = Thread {
            runCatching {
                p.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(8192)
                    // 手写行切分而不用 readLine()：pnpm 的进度行以 \r 结尾且不带 \n，
                    // readLine() 会一直等到下一个 \n 才吐——进度就又成了攒一批才出。
                    val line = StringBuilder()
                    fun flush() {
                        if (line.isEmpty()) return
                        val text = line.toString()
                        line.setLength(0)
                        runCatching { onLine(text) }
                    }
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(sb) { sb.appendRange(buf, 0, n) }
                        for (i in 0 until n) {
                            val ch = buf[i]
                            if (ch == '\n' || ch == '\r') flush() else line.append(ch)
                        }
                    }
                    flush()
                }
            }
        }
        reader.isDaemon = true
        reader.start()
        reader.join(timeoutMs)
        if (reader.isAlive) {
            // 超时：先杀进程让读流拿到 EOF，再给读线程一点时间收尾
            p.destroyForcibly()
            reader.join(1_000)
            appendLog("! 命令超时（${timeoutMs / 1000}s）已终止: ${bashCommand.take(120)}")
        } else if (!p.waitFor(2, TimeUnit.SECONDS)) {
            // 流已关但进程还在（少见：子孙进程继承了 stdout 又提前关掉）
            p.destroyForcibly()
        }
        synchronized(sb) { sb.toString() }
    }.getOrElse { "" }

    // ────────────────────────── 引导（下载 + 解压 + 启动）──────────────────────────

    /** 引导/重启/重装共用的串行锁：三者都会动 rootfs 与服务进程。 */
    private val bootMutex = Mutex()

    /**
     * 一键引导：未装则下载安装，然后拉起 web 服务。
     *
     * [startServer] 的守卫现在只看进程，不再兼当「防重复下载」；
     * 而 HarnessService 在 DOWNLOADING / EXTRACTING 阶段依然会放行 bootstrap，
     * 连点两次启动就会开两条 135MB 下载。用互斥锁 + 阶段判定拦住。
     */
    fun bootstrap() {
        scope.launch {
            if (busy()) return@launch
            bootMutex.withLock {
                if (!DshEnv.isRuntimeInstalled(appContext)) {
                    downloadAndInstall()
                    if (_state.value.phase == DshPhase.ERROR) return@withLock
                }
                setupResolvConf()
                seedPlugins()
                startAndAwait()
            }
        }
    }

    /**
     * 首次安装运行时后预装两个插件。
     *
     * 放在服务启动**之前**：首启本来就要下 135MB + 解压，再加一轮 pnpm 是等比例的；
     * 而服务只启动一次、插件已经生效，不会出现「就绪了又要重启」的突兀体验。
     *
     * 无论成功失败都置位标记：失败不该在每次冷启动重试（用户可以自己去商店装），
     * 否则每次开应用都要多等一轮 pnpm。
     *
     * 这里**不做**安装后验证：插件是我们自己挑的、已人工验证过，在首启路径上再跑
     * 一次 `dsh web --port 0` 只会把首启拖长几分钟。
     */
    private suspend fun seedPlugins() {
        if (!DshEnv.isRuntimeInstalled(appContext)) return
        if (prefs().getBoolean(DshEnv.KEY_SEED_PLUGINS_DONE, false)) return
        _state.update {
            it.copy(phase = DshPhase.EXTRACTING, progress = 0f, message = str(R.string.dsh_plugin_seeding))
        }
        for (pkg in SEED_PLUGINS) {
            appendLog("> 预装插件 $pkg …")
            val out = runCatching {
                DshPluginRepo.install(pkg) { line -> appendLog(line) }
            }.getOrElse { "预装异常: ${it.message ?: it.javaClass.simpleName}" }
            val code = out.lineSequence()
                .lastOrNull { it.startsWith(DshPluginRepo.EXIT_MARKER) }
                ?.removePrefix(DshPluginRepo.EXIT_MARKER)?.trim()?.toIntOrNull()
            appendLog(if (code == 0) "> 预装完成 $pkg" else "! 预装失败 $pkg（可稍后在插件商店手动安装）")
        }
        prefs().edit().putBoolean(DshEnv.KEY_SEED_PLUGINS_DONE, true).apply()
        // 预装会大幅改变 node_modules 体积，顺手重算一次缓存
        refreshRootfsSize()
        _state.update { it.copy(phase = DshPhase.NOT_READY, progress = 1f) }
    }

    /**
     * 启动并等待就绪；如果这一轮触发了 proroot → proot 回退，就用 proot
     * 再试一次（只重试一次：proot 也起不来就是真错了，再试无意义）。
     */
    private suspend fun startAndAwait() {
        prorootFellBack = false
        startServer()
        awaitReady()
        if (!prorootFellBack) return
        prorootFellBack = false
        appendLog("> 用 proot 重试启动…")
        stopServer()
        delay(500)
        startServer()
        awaitReady()
    }

    /** 下载/解压/启动中：不接受新的引导请求。 */
    private fun busy(): Boolean = _state.value.phase.let {
        it == DshPhase.DOWNLOADING || it == DshPhase.EXTRACTING || it == DshPhase.STARTING
    }

    /** 强制重启 web 服务。 */
    fun restart() {
        scope.launch {
            bootMutex.withLock {
                stopServer()
                delay(500)
                startAndAwait()
            }
        }
    }

    /** 重新下载并安装运行时（覆盖 rootfs）。 */
    fun reinstallRuntime() {
        scope.launch {
            bootMutex.withLock {
                stopServer()
                // 重装等于换了一套全新 rootfs，容器里的插件确实没了，该重新预装
                prefs().edit().putBoolean(DshEnv.KEY_SEED_PLUGINS_DONE, false).apply()
                downloadAndInstall()
                if (_state.value.phase != DshPhase.ERROR) {
                    setupResolvConf()
                    seedPlugins()
                    startAndAwait()
                }
            }
        }
    }

    private suspend fun downloadAndInstall() {
        clearLog()
        _state.update {
            it.copy(
                phase = DshPhase.DOWNLOADING,
                progress = 0f,
                speedBytesPerSec = 0L,
                message = str(R.string.dsh_msg_fetching_meta),
            )
        }
        if (DshSource.setting(appContext) == DshSource.SOURCE_AUTO) {
            appendLog("> 自动测速选择下载源…")
            val results = DshSource.speedTest()
            for (r in results.sortedBy { it.estimatedMs }) {
                val speed = if (r.speedKBps > 0.0) String.format("%.1f MB/s", r.speedKBps / 1024.0) else "未测速"
                appendLog("> 测速 ${DshSource.displayName(r.source)}: 延迟 ${r.latencyMs}ms · $speed")
            }
            appendLog("> 已选择下载源: ${DshSource.displayName(DshSource.pickBest(results, appContext))}")
        }
        appendLog("> 获取运行时信息…")
        val meta = fetchMeta()
        if (meta == null) {
            fail(str(R.string.dsh_err_meta_failed))
            return
        }
        // 架构必须先对上：自定义源可以指向任何 metadata.json，下错架构的 rootfs 要到
        // 启动 node 时才报 "Exec format error"，白下 130 MB 还看不懂错在哪。
        if (meta.arch.isNotEmpty() && !android.os.Build.SUPPORTED_ABIS.contains(meta.arch)) {
            fail(str(R.string.dsh_err_arch_mismatch, meta.arch, android.os.Build.SUPPORTED_ABIS.joinToString("/")))
            return
        }
        // 空间检查放在下载之前：rootfs 解压后约为压缩包的 3 倍，加上压缩包自身
        // 需要约 4 倍余量。等下载完再查等于白下 100 多 MB。
        if (meta.sizeBytes > 0) {
            val free = availableSpace(appContext.filesDir)
            val need = meta.sizeBytes * 4
            if (free in 1..need) {
                fail(str(R.string.dsh_err_no_space, free / 1024 / 1024, need / 1024 / 1024))
                return
            }
        }
        val tarball = DshEnv.downloadZip(appContext)
        appendLog("> 开始下载运行时 v${meta.version}（node ${meta.nodeVersion} · dsh ${meta.dsh}）")
        if (!downloadWithFallback(meta, tarball)) {
            fail(str(R.string.dsh_err_download_failed))
            return
        }
        appendLog("> 下载完成（${tarball.length() / 1024 / 1024} MB），校验 sha256…")
        if (!verifySha256(tarball, meta.sha256)) {
            // 校验失败说明这份文件本身是坏的（续传时拼错、镜像给了旧包）——
            // 必须删掉，否则下次续传会一直基于这份坏数据往后接，永远校验不过。
            runCatching { tarball.delete() }
            fail(str(R.string.dsh_err_sha_mismatch))
            return
        }
        // 二次确认：metadata 里的 sizeBytes 可能与实际有偏差，用真实体积再算一次
        val free = availableSpace(appContext.filesDir)
        val need = (tarball.length() * 3.0).toLong()
        if (free in 1..need) {
            fail(str(R.string.dsh_err_no_space, free / 1024 / 1024, need / 1024 / 1024))
            return
        }
        appendLog("> sha256 校验通过，开始解压安装…")
        _state.update {
            it.copy(phase = DshPhase.EXTRACTING, progress = 0f, message = str(R.string.dsh_msg_installing))
        }
        val ok = withContext(Dispatchers.IO) { extractRootfs(tarball) }
        tarball.delete()
        if (!ok) {
            fail(str(R.string.dsh_err_extract_failed))
            return
        }
        appendLog("> 运行时安装完成")
        prefs().edit().putString(DshEnv.KEY_RUNTIME_VERSION, meta.version).apply()
        // phase 必须回 NOT_READY，不能直接置 STARTING：[bootstrap] 下一步就调
        // [startServer]，而它的防重入守卫会把 STARTING 当成「已经在启动了」直接
        // return——首次安装后服务永远起不来就是这个自我拦截造成的。
        _state.update {
            it.copy(
                phase = DshPhase.NOT_READY,
                installed = true,
                runtimeVersion = meta.version,
                progress = 1f,
                speedBytesPerSec = 0L,
                message = str(R.string.dsh_msg_runtime_ready),
            )
        }
        // 刚解压完，体积是全新的：后台量一次并落缓存，界面随 state 自动更新
        refreshRootfsSize()
    }

    private fun fetchMeta(): DshMeta? = runCatching {
        val conn = URL(DshSource.effectiveMetaUrl(appContext)).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.instanceFollowRedirects = true
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        DshMeta(
            version = json.optString("version", "unknown"),
            url = json.getString("url"),
            sha256 = json.optString("sha256", ""),
            sizeBytes = json.optLong("sizeBytes", 0L),
            mirrors = json.optJSONArray("mirrors")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                ?: emptyList(),
            arch = json.optString("arch", ""),
            dsh = json.optString("dsh", ""),
            nodeVersion = json.optString("nodeVersion", ""),
            builtAt = json.optString("builtAt", ""),
        )
    }.getOrNull()

    /**
     * 逐个下载源尝试，第一个成功即返回。
     *
     * metadata 的 mirrors 本身就是加了代理前缀的 URL，再给它们叠一次前缀只会产生
     * 重复项 —— 去重前实测 6 个候选里有 3 个是重的，等于同一个失败的 URL 连试两次。
     */
    private fun downloadWithFallback(meta: DshMeta, target: File): Boolean {
        val prefix = DshSource.proxyPrefix(DshSource.resolve(appContext))
        val raw = listOf(meta.url) + meta.mirrors
        val candidates = (
            if (prefix.isEmpty()) raw
            else raw.map { if (it.startsWith("https://github.com/")) prefix + it else it } + raw
            ).distinct()
        for ((i, url) in candidates.withIndex()) {
            appendLog("> 尝试下载源 [${i + 1}/${candidates.size}]: $url")
            _state.update {
                it.copy(message = str(R.string.dsh_msg_downloading, meta.version), speedBytesPerSec = 0L)
            }
            if (downloadFile(url, target, meta.sizeBytes)) {
                appendLog("> 下载源 [${i + 1}] 成功")
                return true
            }
            appendLog("! 下载源 [${i + 1}] 失败")
        }
        return false
    }

    /**
     * 下载单个文件，支持断点续传。
     *
     * 130 MB 的包在手机网络上断一次很常见，原来每次失败都从 0 开始重下。GitHub Release
     * 与两个代理都支持 Range（实测返回 206 + content-range），所以已下载部分够大时带上
     * `Range: bytes=N-` 续传；服务端不给 206 就退回从头下载并截断旧文件。
     */
    private fun downloadFile(url: String, target: File, sizeBytes: Long): Boolean {
        var conn: HttpURLConnection? = null
        var input: InputStream? = null
        var out: OutputStream? = null
        var ok = false
        return try {
            // 太小的残片不值得续传（可能是上次刚建好文件就断了），直接重下
            val have = if (target.isFile && target.length() > 1L * 1024 * 1024) target.length() else 0L
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            if (conn.responseCode !in 200..299) return false
            // 206 才是真的续传；服务端忽略 Range 返回 200 时必须从头写
            val resumed = have > 0 && conn.responseCode == 206
            if (have > 0 && !resumed) appendLog("> 服务端不支持续传，从头下载")
            if (resumed) appendLog("> 断点续传：已有 ${have / 1024 / 1024} MB")
            val contentLength = if (sizeBytes > 0) sizeBytes
                else conn.contentLengthLong.let { if (it > 0 && resumed) it + have else it }
            target.parentFile?.mkdirs()
            // stream 是非空局部量：循环里写它，out 只留给 finally 关句柄。
            // （out 是 OutputStream? 且循环内会被置空，直接用它写会丢智能转换）
            val stream = BufferedOutputStream(FileOutputStream(target, resumed))
            out = stream
            input = conn.inputStream
            val buf = ByteArray(64 * 1024)
            var total = if (resumed) have else 0L
            var lastUpdate = total
            var lastLoggedBucket = -1
            var speedBps = 0L
            var lastSpeedAt = System.currentTimeMillis()
            var lastSpeedTotal = total
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                stream.write(buf, 0, n)
                total += n
                val now = System.currentTimeMillis()
                if (now - lastSpeedAt >= 500) {
                    val dt = (now - lastSpeedAt) / 1000.0
                    if (dt > 0.0) speedBps = ((total - lastSpeedTotal) / dt).toLong()
                    lastSpeedAt = now
                    lastSpeedTotal = total
                }
                if (total - lastUpdate > 512 * 1024 || (contentLength > 0 && total >= contentLength)) {
                    lastUpdate = total
                    if (contentLength > 0) {
                        val pct = (total.toDouble() / contentLength).coerceIn(0.0, 1.0)
                        val pctInt = (pct * 100).toInt()
                        if (pctInt / 5 > lastLoggedBucket) {
                            lastLoggedBucket = pctInt / 5
                            appendLog("> 下载中 $pctInt%（${formatSpeed(speedBps)}）")
                        }
                        _state.update {
                            it.copy(
                                progress = pct.toFloat(),
                                speedBytesPerSec = speedBps,
                                message = str(R.string.dsh_msg_downloading_pct, pctInt, formatSpeed(speedBps)),
                            )
                        }
                    }
                }
                // 超出预期大小：文件已经错了，删掉再报失败 —— 留着的话下次续传会
                // 从一个比目标还长的偏移接着请求，服务端只会回 416。
                if (contentLength > 0 && total > contentLength) {
                    appendLog("! 下载超出预期大小（$total > $contentLength），丢弃重下")
                    // 先关流再删：否则 finally 里的 close 会把缓冲区刷回一个刚被删掉的路径
                    runCatching { stream.close() }
                    out = null
                    runCatching { target.delete() }
                    return false
                }
            }
            if (contentLength > 0 && total != contentLength) {
                appendLog("! 下载不完整: 预期 $contentLength 实际 $total")
                return false
            }
            ok = true
            true
        } catch (e: Exception) {
            appendLog("! 下载异常: ${e.javaClass.simpleName}: ${e.message}")
            false
        } finally {
            runCatching { input?.close() }
            runCatching { out?.close() }
            runCatching { conn?.disconnect() }
            // 失败时保留已下载部分供下次续传；只有明显不可续（大小超出预期）时才删，
            // 那种情况由上面的 return false 之前就地处理。
            if (!ok && target.length() < 1L * 1024 * 1024) runCatching { target.delete() }
        }
    }

    /** 解压 rootfs.tar.gz 到 filesDir/rootfs（整体替换）。 */
    private fun extractRootfs(tarball: File): Boolean = runCatching {
        val dest = DshEnv.rootfs(appContext)
        if (dest.exists()) dest.deleteRecursively()
        dest.mkdirs()
        TarGzipExtractor.extractRootfs(tarball, dest)
        // 关键文件自检：解压不完整（断流 / 空间耗尽）时越早发现越好，
        // 否则要等到启动 dsh web 才报一句看不懂的错。
        // File.exists() 跟随符号链接，所以 python3 -> python3.12 这类条目也一并验证了。
        val missing = listOf(
            "usr/bin/bash" to "bash",
            "usr/local/bin/node" to "node",
            "usr/local/lib/node_modules/@deepseek-ai/dsh/package.json" to "dsh",
        ).filterNot { File(dest, it.first).exists() }
        if (missing.isNotEmpty()) {
            appendLog("! 解压后 rootfs 缺少: " + missing.joinToString("、") { it.second })
            return@runCatching false
        }
        // 下面两个缺了不致命（插件装不了 / 无线 ADB 配不了，但 DSH 本身能跑），只记一行
        if (!File(dest, "usr/bin/python3").exists()) appendLog("! 运行时缺少 python3，无线 ADB 配对不可用")
        if (!File(dest, "usr/local/bin/pnpm").exists()) appendLog("! 运行时缺少 pnpm，插件安装不可用")
        true
    }.getOrElse {
        appendLog("! 解压失败: ${it.javaClass.simpleName}: ${it.message}")
        false
    }

    /** 写容器 DNS（国内解析优先，谷歌/CF 兜底）。 */
    /**
     * 写入容器的 DNS 与 hosts。
     *
     * Android 不暴露 /etc/resolv.conf（DNS 走 netd 的 binder 接口），容器里的 glibc
     * 只会读文件，所以必须自己写一份，否则容器内所有域名解析都失败 —— 表现是
     * npm/pnpm/apt 全部 EAI_AGAIN。base rootfs 里的 resolv.conf 与 hosts 都是 0 字节。
     *
     * localhost 也得手动补：dsh web 绑 127.0.0.1，容器内插件回连 http://localhost:3080
     * 时没有这一行就解析不出来。
     */
    private fun setupResolvConf() {
        runCatching {
            // 安装/重装后强制重写：换网络环境后旧的 nameserver 可能已不可达
            File(DshEnv.rootfs(appContext), "etc/resolv.conf").delete()
        }
        ensureContainerDns()
    }

    /** 缺失或空文件才写（不覆盖用户自己改过的内容）。 */
    private fun ensureContainerDns() {
        runCatching {
            val rootfs = DshEnv.rootfs(appContext)
            if (!File(rootfs, "etc").isDirectory) return@runCatching
            val rc = File(rootfs, "etc/resolv.conf")
            if (rc.length() == 0L) {
                rc.writeText(
                    "nameserver 223.5.5.5\nnameserver 119.29.29.29\n" +
                        "nameserver 8.8.8.8\nnameserver 1.1.1.1\n",
                    StandardCharsets.UTF_8,
                )
            }
            val hosts = File(rootfs, "etc/hosts")
            if (hosts.length() == 0L) {
                hosts.writeText(
                    "127.0.0.1\tlocalhost\n::1\tlocalhost ip6-localhost ip6-loopback\n",
                    StandardCharsets.UTF_8,
                )
            }
        }
    }

    /**
     * 让容器内的 pnpm 用「复制」而不是「硬链接」从内容存储装包。
     *
     * 为什么必须这么做：pnpm 用 `link()` 把 CAS 里的文件装进 node_modules，而
     * proot/proroot 的 `--link2symlink` 会把它改写成符号链接（见 [linkBecomesSymlink]）。
     * Node 的 `require.resolve` 默认 realpath，于是
     * `node_modules/<pkg>/package.json` 解析成 `<store>/files/<xx>/<hash>`，
     * dsh 再按 `join(dirname(pkgPath), "./lib/client.cjs")` 拼客户端包路径，
     * 得到 `<store>/files/<xx>/lib/client.cjs` —— CAS 里只有扁平的哈希文件，
     * 没有 lib/ 目录，必然 ENOENT。真机表现是任何带 `exports["./client"]` 的插件
     * 装完就让 `dsh web` 起不来（MissingClientBundleError）。
     *
     * 选 copy 而不是设法让硬链接可用：真机探测报的是
     * `AccessDeniedException`（系统不允许在 App 私有目录建硬链接），不在 App 能改的
     * 范围内。copy 绕开整个链接语义，代价是 CAS 去重失效、rootfs 变大。
     *
     * **写 pnpm-workspace.yaml 而不是 .npmrc**：pnpm 11 起这类 pnpm 专有设置已从
     * `.npmrc` 迁到 `pnpm-workspace.yaml`。实测 `pnpm config get package-import-method`
     * 对 .npmrc 里的同名项返回 undefined，而 workspace 里的 `packageImportMethod`
     * 返回 copy（.npmrc 本身没失效 —— 同文件里的 registry= 照样生效）。v1.3 写
     * .npmrc 那一版因此完全没生效。
     *
     * **只在文件已存在时追加**：dsh 的 `initProfile` 见到文件存在就不写自己的模板
     * （dsh-app-boot 里 `if (!existsSync(workspacePath))`），抢先创建会把
     * `nodeLinker: hoisted` 与 `autoInstallPeers: false` 弄丢。profile 尚未初始化时
     * 靠安装命令上的 `--package-import-method copy` 兜住那一次。
     *
     * 用户显式写过这一项时**不覆盖** —— 显式配置优先于我们的推断。
     */
    private fun ensureProfilePnpmSettings() {
        runCatching { removeLegacyNpmrcImportLine() }
        if (!linkBecomesSymlink()) return
        runCatching {
            val ws = File(DshEnv.rootfs(appContext), "$PROFILE_GUEST_REL/pnpm-workspace.yaml")
            // 不存在就不建：见 KDoc，抢在 dsh initProfile 之前会弄丢它的模板
            if (!ws.isFile) return@runCatching
            val old = ws.readText(StandardCharsets.UTF_8)
            if (old.lineSequence().any { it.trimStart().startsWith(PNPM_IMPORT_KEY) }) return@runCatching
            val sep = if (old.isEmpty() || old.endsWith("\n")) "" else "\n"
            // 追加顶层键而不是解析重写整个 YAML：这文件里还有 minimumReleaseAgeExclude、
            // allowBuilds 等结构化内容，字符串追加最不容易弄坏别人的配置
            ws.writeText(old + sep + PNPM_IMPORT_LINE + "\n", StandardCharsets.UTF_8)
            android.util.Log.i(TAG, "已写入 profile pnpm 设置: $PNPM_IMPORT_LINE")
        }
    }

    /**
     * 清掉 v1.3 写进 `/root/.npmrc` 的那行 —— 它已知无效（pnpm 11 不读），
     * 留着只会在排查时误导。只删精确匹配的那一行，用户自己写的其它行一律不碰；
     * 文件因此变空就把文件删掉。
     */
    private fun removeLegacyNpmrcImportLine() {
        val rc = File(DshEnv.rootfs(appContext), "root/.npmrc")
        if (!rc.isFile) return
        val old = rc.readText(StandardCharsets.UTF_8)
        if (!old.contains(NPMRC_LEGACY_LINE)) return
        val kept = old.lineSequence().filter { it.trim() != NPMRC_LEGACY_LINE }.toList()
        if (kept.all { it.isBlank() }) {
            rc.delete()
        } else {
            rc.writeText(kept.joinToString("\n").trimEnd() + "\n", StandardCharsets.UTF_8)
        }
        android.util.Log.i(TAG, "已清理无效的 npmrc 行: $NPMRC_LEGACY_LINE")
    }

    /**
     * pnpm 实际读到的导入方式，供启动日志用。
     *
     * 读文件而不是回答「我们写过没有」：v1.3 的教训正是日志宣称了一件没生效的事。
     */
    private fun pnpmImportMethodLine(): String {
        val ws = File(DshEnv.rootfs(appContext), "$PROFILE_GUEST_REL/pnpm-workspace.yaml")
        if (!ws.isFile) return "未配置（profile 未初始化）"
        val line = runCatching {
            ws.readLines().firstOrNull { it.trimStart().startsWith(PNPM_IMPORT_KEY) }
        }.getOrNull()
            ?: return "hardlink（默认）"
        val value = line.substringAfter(':', "").trim().ifEmpty { "?" }
        return "$value（profile pnpm-workspace.yaml）"
    }

    // ────────────────────────── web 服务 ──────────────────────────

    /**
     * 启动 `dsh web`。
     *
     * `--expose-internals` 必带：dsh 的 profile-boot 会无条件创建 cordis-plugin-hmr，
     * 那个插件第一行就检查 loader.internal，缺这个标志会把整个启动带崩；
     * 而 NODE_OPTIONS 传不了它（node 明确拒绝），只能作为命令行参数。
     *
     * 默认端口 3080 不显式传 --port，避免 commander 的 'argument missing'。
     * 服务只绑 127.0.0.1，不提供 --host 0.0.0.0 开关；鉴权由 dsh 自己的登录页负责。
     */
    fun startServer() {
        // 守卫只看真实进程，不看 phase。看 phase 会把上一步（例如安装完成）
        // 自己写下的 STARTING 误当成「已有人在启动」。
        if (serverProcess?.isAlive == true) return
        if (!DshEnv.isRuntimeInstalled(appContext)) {
            _state.update { it.copy(phase = DshPhase.NOT_READY, message = str(R.string.dsh_msg_not_installed)) }
            return
        }
        DshEnv.dshHome(appContext).mkdirs()
        DshEnv.tmpDir(appContext).mkdirs()
        DshEnv.serverLog(appContext).parentFile?.mkdirs()
        clearLog()

        val port = port()
        val opts = if (port == DshEnv.DEFAULT_PORT) "" else " --port $port"
        val cmd = buildString {
            append("export DSH_HOME=/root/.dsh && ")
            // 不让 dsh 拉系统浏览器：我们用 Intent 打开 WebUI
            append("export BROWSER=true && ")
            append("mkdir -p /root/workspace 2>/dev/null; ")
            append("cd /root/workspace && ")
            append("if command -v dsh >/dev/null 2>&1 && test -f \"\$(command -v dsh)\"; then ")
            // dsh 是 wrapper，先 readlink 出真正的 bin.js 再交给 node（才能带 --expose-internals）
            append("DSH_REAL=\$(readlink -f \"\$(command -v dsh)\" 2>/dev/null || command -v dsh); ")
            append("exec node --expose-internals \"\$DSH_REAL\" web" + opts + " 2>&1; ")
            append("else echo '[DSH-Folk] 容器内找不到 dsh，请在设置中重装运行时'; exit 1; fi")
        }

        appendLog("> 运行时: ${runtime().displayName()}")
        appendLog("> 硬链接: " + hardlinkLogLine())
        appendLog("> pnpm 导入方式: " + pnpmImportMethodLine())
        appendLog("> 启动 dsh web，端口 $port")

        serverProcess = try {
            execRootfs(cmd)
        } catch (e: Exception) {
            // 不能把上一轮已死的进程留在字段里：[awaitReady] 会把它读成
            // 「进程已退出」，盖掉真正的启动失败原因。
            serverProcess = null
            val detail = str(R.string.dsh_err_start_failed, e.message ?: e.javaClass.simpleName)
            appendLog("! $detail")
            if (runtimeId() == "proroot" && noteProrootFailure(detail)) {
                appendLog("> 已切回 proot，请重新启动服务")
            }
            _state.update { it.copy(phase = DshPhase.ERROR, message = detail) }
            return
        }
        forwardOutput(serverProcess)
        startedAt = System.currentTimeMillis()
        _state.update { it.copy(phase = DshPhase.STARTING, port = port, message = str(R.string.dsh_msg_starting)) }
    }

    /** 逐行转发容器输出到日志（node 重定向到文件是块缓冲，不逐行读日志面板会空白）。 */
    private fun forwardOutput(proc: Process?) {
        if (proc == null) return
        val log = LogStore.named(DshEnv.serverLog(appContext))
        scope.launch {
            val reader = proc.inputStream.bufferedReader()
            try {
                for (line in reader.lineSequence()) log.append(line)
            } catch (_: Exception) {
                // 进程被销毁时读流中断，属预期
            } finally {
                log.flushForExit()
                runCatching { reader.close() }
            }
        }
    }

    /**
     * 轮询直到服务真能响应 HTTP 或超时。
     *
     * 只探 TCP 端口是不够的：端口被别的进程占着（上一次没退干净、用户自己在容器里
     * 跑了东西）也会 connect 成功，于是首页显示「已就绪」，点开却是别人的页面或直接
     * 连不上。这里在端口通之后再要一次 HTTP 响应 —— 任何状态码都算（dsh 未登录时
     * 返回登录页，403 也证明是它在服务）。
     */
    private suspend fun awaitReady() {
        // 没有进程就没什么可等。不能依赖下面那句
        // `serverProcess?.isAlive == false` —— serverProcess 为 null 时它是 false
        // （null == false），于是「压根没启动」会一声不响地等到超时。
        // [startServer] 已经报错的情况下直接返回，否则这里的超时/无进程
        // 文案会盖掉那个更具体的原因。
        if (_state.value.phase == DshPhase.ERROR) return
        if (serverProcess == null) {
            val detail = str(R.string.dsh_err_no_process)
            appendLog("! $detail")
            _state.update { it.copy(phase = DshPhase.ERROR, message = detail) }
            return
        }
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (portOpen(port()) && httpResponds(port())) {
                _state.update {
                    it.copy(
                        phase = DshPhase.RUNNING,
                        progress = 1f,
                        message = str(R.string.dsh_msg_service_ready, it.webUrl),
                    )
                }
                appendLog("> 服务已就绪: http://127.0.0.1:${port()}/")
                prefs().edit().putInt(DshEnv.KEY_PROROOT_FAIL, 0).apply()
                return
            }
            if (serverProcess?.isAlive == false) {
                val base = str(R.string.dsh_err_process_exited)
                appendLog("! $base")
                if (runtimeId() == "proroot" && noteProrootFailure(base)) {
                    appendLog("> 已切回 proot，请重新启动服务")
                }
                // 插件树失败时给出可执行的下一步，而不是只说「进程已退出」
                val detail = pluginTreeFailureHint() ?: base
                _state.update { it.copy(phase = DshPhase.ERROR, message = detail) }
                return
            }
            delay(1_000)
        }
        // 超时同样算 proroot 一次失败：进程没退但服务始终起不来（proroot 在部分内核上
        // 卡在 seccomp/ptrace 上就是这种表现），只记「进程退出」那一路会让用户永远
        // 卡在坏运行时上，自动回退 proot 的兜底形同不存在。
        val detail = str(R.string.dsh_err_start_timeout)
        appendLog("! $detail")
        if (runtimeId() == "proroot" && noteProrootFailure(detail)) {
            appendLog("> 已切回 proot，请重新启动服务")
        }
        _state.update { it.copy(phase = DshPhase.ERROR, message = detail) }
    }

    private fun portOpen(port: Int): Boolean = runCatching {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1", port), 800)
            true
        }
    }.getOrDefault(false)

    /**
     * 回环 HTTP 是否有响应。
     *
     * 任何 HTTP 状态码都算就绪 —— dsh 未登录时返回登录页（200）或 403，都说明服务在跑。
     * 只有连不上 / 不是 HTTP / 超时才算没起来。
     */
    private fun httpResponds(port: Int): Boolean = runCatching {
        val conn = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 1_500
            conn.readTimeout = 2_500
            conn.instanceFollowRedirects = false
            conn.responseCode > 0
        } finally {
            runCatching { conn.disconnect() }
        }
    }.getOrDefault(false)

    /** 停止 web 服务：先杀容器内的 dsh web，再销毁 proot 进程。 */
    fun stopServer() {
        runCatching {
            // proot 不隔离 PID，容器内 /proc 看到宿主全部进程 —— 只杀 bin.js web / dsh web，
            // 绝不裸 pkill -f node（会误杀 agent 或用户自己的 node 进程）。
            //
            // 必须排除自己：这条清理命令的 cmdline 里就含 'bin.js web' 字面量，pgrep -f 会
            // 把执行它的 bash、以及 $() 命令替换 fork 出的子 shell 一起匹配上（子 shell 与
            // 父进程共享 cmdline，所以比对 PID 挡不住它）。做法是在命令里埋一个哨兵字符串，
            // 再按 /proc/<pid>/cmdline 把带哨兵的进程全部跳过 —— 否则 kill -KILL 会先把这条
            // 清理命令自己杀掉，真正的 dsh web 反而留着。
            execRootfs(
                ": DSHFOLK_STOP_SENTINEL; " +
                "_kill() { for _p in " +
                "\$(pgrep -f 'bin.js web' 2>/dev/null; pgrep -f 'dsh web' 2>/dev/null); do " +
                "grep -qa DSHFOLK_STOP_SENTINEL \"/proc/\$_p/cmdline\" 2>/dev/null && continue; " +
                "kill \"\$1\" \"\$_p\" 2>/dev/null; done; }; " +
                "_kill -TERM; sleep 1; _kill -KILL"
            ).waitFor(8, TimeUnit.SECONDS)
        }
        runCatching { serverProcess?.destroyForcibly() }
        serverProcess = null
        startedAt = 0L
        _state.update { it.copy(phase = DshPhase.NOT_READY, pid = null, message = str(R.string.dsh_msg_stopped)) }
    }

    fun uptimeMillis(): Long = if (startedAt == 0L) 0L else System.currentTimeMillis() - startedAt

    // ────────────────────────── 日志 ──────────────────────────

    fun tailLog(lines: Int = 200): String =
        if (::appContext.isInitialized) LogStore.named(DshEnv.serverLog(appContext)).tail(lines) else ""

    fun clearLog() {
        if (::appContext.isInitialized) LogStore.named(DshEnv.serverLog(appContext)).clear()
    }

    fun appendLog(line: String) {
        if (::appContext.isInitialized) LogStore.named(DshEnv.serverLog(appContext)).append(line)
    }

    /**
     * 硬链接状态的日志行，**带上探测失败的原因**。
     *
     * 只写结论是不够的：App 私有目录本该支持硬链接，报「不支持」是反常的，
     * 而 bugreport 里的 logcat 只覆盖最近几分钟、抓不到启动时那条 Log.i。
     * 把原因写进 dsh.log 才能在下一份 bugreport 里直接看到 —— 真机上就是靠这条
     * 才拿到 `AccessDeniedException`（系统不允许在 App 私有目录建硬链接）。
     *
     * 这里**不再**声称 pnpm 的导入方式：那是 [pnpmImportMethodLine] 的事，它读
     * 真实配置。v1.3 在这行里写「pnpm 已切为 copy 导入」，而那一版的配置压根没生效。
     */
    private fun hardlinkLogLine(): String {
        val ok = hardlinkSupported()
        val detail = hardlinkDetail()
        return buildString {
            if (ok) append("支持") else append("不支持")
            if (detail.isNotEmpty()) append("（$detail）")
            when {
                // proroot 无条件加 --link2symlink，探测结果在它下面不代表 guest 的实际能力
                runtimeId() == "proroot" -> append(" · proroot 无条件启用 l2s")
                !ok -> append(" · 启用 l2s 模拟")
                else -> append(" · 不加 --link2symlink")
            }
        }
    }

    private fun fail(message: String) {
        appendLog("! $message")
        _state.update { it.copy(phase = DshPhase.ERROR, message = message, speedBytesPerSec = 0L) }
    }

    /**
     * 取本地化字符串。
     *
     * 状态消息会直接显示在首页大卡上，必须跟随应用语言 —— 原来这一层全是硬编码中文，
     * 英文界面下首页照样弹中文。启动日志里的行仍保持中文原样：那是给排障看的技术输出，
     * 混进资源里既难维护、也让日志在不同语言下对不上。
     */
    private fun str(resId: Int, vararg args: Any): String =
        if (::appContext.isInitialized) appContext.getString(resId, *args) else ""

    // ────────────────────────── 工具 ──────────────────────────

    fun formatSpeed(bytesPerSec: Long): String = when {
        bytesPerSec <= 0 -> "…"
        bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / 1024.0 / 1024.0)
        bytesPerSec >= 1024 -> String.format("%.0f KB/s", bytesPerSec / 1024.0)
        else -> "$bytesPerSec B/s"
    }

    private fun verifySha256(file: File, expected: String): Boolean {
        if (expected.isBlank()) return true
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            BufferedInputStream(file.inputStream(), 256 * 1024).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }.equals(expected, ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun availableSpace(dir: File): Long = runCatching {
        dir.mkdirs()
        StatFs(dir.absolutePath).availableBytes
    }.getOrDefault(-1L)

    /**
     * 运行时占用统计（设置页存储信息用）。
     *
     * **别在组合期调用**：它递归遍历整个 rootfs。首页要显示体积请读
     * `state.rootfsSizeBytes`（缓存值），需要刷新走 [refreshRootfsSize]。
     */
    fun rootfsSizeBytes(): Long = if (!ready) 0L else dirSize(DshEnv.rootfs(appContext))

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.listFiles()?.sumOf { dirSize(it) } ?: 0L
    }
}
