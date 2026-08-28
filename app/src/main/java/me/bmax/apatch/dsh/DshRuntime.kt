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
import kotlinx.coroutines.withContext
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
    private const val READY_TIMEOUT_MS = 180_000L
    /** proroot 连续失败到此次数即强制回退 proot 并清掉用户选择。 */
    private const val PROROOT_FAIL_LIMIT = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(DshState())
    val state: StateFlow<DshState> = _state.asStateFlow()

    private lateinit var appContext: Context
    private var serverProcess: Process? = null
    private var startedAt: Long = 0L

    @Volatile private var hardlinkOk: Boolean? = null

    fun attach(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
        val installed = DshEnv.isRuntimeInstalled(appContext)
        _state.update {
            it.copy(
                installed = installed,
                port = port(),
                runtimeVersion = prefs().getString(DshEnv.KEY_RUNTIME_VERSION, null),
                phase = if (installed) it.phase else DshPhase.NOT_READY,
            )
        }
    }

    private fun prefs() = appContext.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)

    fun port(): Int = prefs().getInt(DshEnv.KEY_PORT, DshEnv.DEFAULT_PORT).let {
        if (it in 1..65535) it else DshEnv.DEFAULT_PORT
    }

    fun setPort(p: Int) {
        if (p !in 1..65535) return
        prefs().edit().putInt(DshEnv.KEY_PORT, p).apply()
        _state.update { it.copy(port = p) }
    }

    // ────────────────────────── 容器运行时选择 ──────────────────────────

    /** 当前选择的运行时 id（默认 proot：稳定优先）。 */
    fun runtimeId(): String = prefs().getString(DshEnv.KEY_RUNTIME, "proot") ?: "proot"

    fun setRuntimeId(id: String) {
        prefs().edit().putString(DshEnv.KEY_RUNTIME, id).putInt(DshEnv.KEY_PROROOT_FAIL, 0).apply()
    }

    /** 解析出实际可用的运行时；选中的不可用时静默回退 proot。 */
    fun runtime(): ContainerRuntime {
        val proot = ContainerRuntime.Proot(appContext, nativeLib("libproot.so"))
        if (runtimeId() != "proroot") return proot
        val proroot = ContainerRuntime.Proroot(appContext, ContainerRuntime.Proroot.defaultDir(appContext))
        return if (proroot.available()) proroot else proot
    }

    /** 记一次 proroot 启动失败；达上限强制切回 proot。返回是否触发了回退。 */
    fun noteProrootFailure(why: String): Boolean {
        if (runtimeId() != "proroot") return false
        val n = prefs().getInt(DshEnv.KEY_PROROOT_FAIL, 0) + 1
        return if (n >= PROROOT_FAIL_LIMIT) {
            prefs().edit().putString(DshEnv.KEY_RUNTIME, "proot").putInt(DshEnv.KEY_PROROOT_FAIL, 0).apply()
            appendLog("! proroot 连续 $n 次启动失败（$why），已自动切回 proot")
            true
        } else {
            prefs().edit().putInt(DshEnv.KEY_PROROOT_FAIL, n).apply()
            false
        }
    }

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
            return ok
        }
    }

    /** 复制 proot 的 NEEDED 依赖到可写 lib 目录（proroot 不需要）。 */
    private fun ensureRuntimeFiles() {
        val libDir = File(appContext.filesDir, "lib").apply { mkdirs() }
        DshEnv.tmpDir(appContext).mkdirs()
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

    /** 同步执行并读回输出（带超时，默认 60s）。 */
    fun execRootfsForOutput(bashCommand: String, timeoutMs: Long = 60_000L): String = runCatching {
        val p = execRootfs(bashCommand)
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) p.destroyForcibly()
        out
    }.getOrElse { "" }

    // ────────────────────────── 引导（下载 + 解压 + 启动）──────────────────────────

    /** 一键引导：未装则下载安装，然后拉起 web 服务。 */
    fun bootstrap() {
        scope.launch {
            if (!DshEnv.isRuntimeInstalled(appContext)) {
                downloadAndInstall()
                if (_state.value.phase == DshPhase.ERROR) return@launch
            }
            setupResolvConf()
            startServer()
            awaitReady()
        }
    }

    /** 强制重启 web 服务。 */
    fun restart() {
        scope.launch {
            stopServer()
            delay(500)
            startServer()
            awaitReady()
        }
    }

    /** 重新下载并安装运行时（覆盖 rootfs）。 */
    fun reinstallRuntime() {
        scope.launch {
            stopServer()
            downloadAndInstall()
            if (_state.value.phase != DshPhase.ERROR) {
                setupResolvConf()
                startServer()
                awaitReady()
            }
        }
    }

    private suspend fun downloadAndInstall() {
        clearLog()
        _state.update {
            it.copy(phase = DshPhase.DOWNLOADING, progress = 0f, speedBytesPerSec = 0L, message = "正在获取运行时信息…")
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
            fail("获取运行时信息失败，请检查网络或切换镜像源")
            return
        }
        // 空间检查放在下载之前：rootfs 解压后约为压缩包的 3 倍，加上压缩包自身
        // 需要约 4 倍余量。等下载完再查等于白下 100 多 MB。
        if (meta.sizeBytes > 0) {
            val free = availableSpace(appContext.filesDir)
            val need = meta.sizeBytes * 4
            if (free in 1..need) {
                fail("存储空间不足（可用 ${free / 1024 / 1024}MB，需约 ${need / 1024 / 1024}MB），请清理后重试")
                return
            }
        }
        val tarball = DshEnv.downloadZip(appContext)
        appendLog("> 开始下载运行时 v${meta.version}（node ${meta.nodeVersion} · dsh ${meta.dsh}）")
        if (!downloadWithFallback(meta, tarball)) {
            fail("运行时下载失败，请检查网络或切换镜像源")
            return
        }
        appendLog("> 下载完成（${tarball.length() / 1024 / 1024} MB），校验 sha256…")
        if (!verifySha256(tarball, meta.sha256)) {
            fail("运行时校验失败（sha256 不匹配）")
            return
        }
        // 二次确认：metadata 里的 sizeBytes 可能与实际有偏差，用真实体积再算一次
        val free = availableSpace(appContext.filesDir)
        val need = (tarball.length() * 3.0).toLong()
        if (free in 1..need) {
            fail("存储空间不足（可用 ${free / 1024 / 1024}MB，需约 ${need / 1024 / 1024}MB），请清理后重试")
            return
        }
        appendLog("> sha256 校验通过，开始解压安装…")
        _state.update { it.copy(phase = DshPhase.EXTRACTING, progress = 0f, message = "正在安装运行时…") }
        val ok = withContext(Dispatchers.IO) { extractRootfs(tarball) }
        tarball.delete()
        if (!ok) {
            fail("运行时安装失败（解压后 rootfs 不完整）")
            return
        }
        appendLog("> 运行时安装完成")
        prefs().edit().putString(DshEnv.KEY_RUNTIME_VERSION, meta.version).apply()
        _state.update {
            it.copy(
                phase = DshPhase.STARTING,
                installed = true,
                runtimeVersion = meta.version,
                progress = 1f,
                speedBytesPerSec = 0L,
                message = "运行时已就绪，正在准备环境…",
            )
        }
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

    private fun downloadWithFallback(meta: DshMeta, target: File): Boolean {
        val prefix = DshSource.proxyPrefix(DshSource.resolve(appContext))
        val raw = listOf(meta.url) + meta.mirrors
        val candidates = if (prefix.isEmpty()) raw else
            raw.map { if (it.startsWith("https://github.com/")) prefix + it else it } + raw
        for ((i, url) in candidates.withIndex()) {
            appendLog("> 尝试下载源 [${i + 1}/${candidates.size}]: $url")
            _state.update { it.copy(message = "正在下载运行时（${meta.version}）…", speedBytesPerSec = 0L) }
            if (downloadFile(url, target, meta.sizeBytes)) {
                appendLog("> 下载源 [${i + 1}] 成功")
                return true
            }
            appendLog("! 下载源 [${i + 1}] 失败")
        }
        return false
    }

    private fun downloadFile(url: String, target: File, sizeBytes: Long): Boolean {
        var conn: HttpURLConnection? = null
        var input: InputStream? = null
        var out: OutputStream? = null
        var ok = false
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) return false
            val contentLength = if (sizeBytes > 0) sizeBytes else conn.contentLengthLong
            target.parentFile?.mkdirs()
            out = BufferedOutputStream(FileOutputStream(target))
            input = conn.inputStream
            val buf = ByteArray(64 * 1024)
            var total = 0L
            var lastUpdate = 0L
            var lastLoggedBucket = -1
            var speedBps = 0L
            var lastSpeedAt = System.currentTimeMillis()
            var lastSpeedTotal = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
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
                                message = "正在下载运行时（$pctInt%）· ${formatSpeed(speedBps)}",
                            )
                        }
                    }
                }
                if (contentLength > 0 && total > contentLength) return false
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
            if (!ok) runCatching { target.delete() }
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
    private fun setupResolvConf() {
        runCatching {
            val rc = File(DshEnv.rootfs(appContext), "etc/resolv.conf")
            rc.parentFile?.mkdirs()
            if (rc.exists()) rc.delete()
            rc.writeText(
                "nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 8.8.8.8\nnameserver 1.1.1.1\n",
                StandardCharsets.UTF_8,
            )
        }
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
     * 服务只绑 127.0.0.1（无鉴权），不提供 --host 0.0.0.0 开关。
     */
    fun startServer() {
        val p = _state.value.phase
        if (p == DshPhase.RUNNING || p == DshPhase.STARTING) return
        if (!DshEnv.isRuntimeInstalled(appContext)) {
            _state.update { it.copy(phase = DshPhase.NOT_READY, message = "运行时未安装") }
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
            append("mkdir -p /root/.dsh/plugins /root/workspace 2>/dev/null; ")
            append("cd /root/workspace && ")
            append("if command -v dsh >/dev/null 2>&1 && test -f \"\$(command -v dsh)\"; then ")
            // dsh 是 wrapper，先 readlink 出真正的 bin.js 再交给 node（才能带 --expose-internals）
            append("DSH_REAL=\$(readlink -f \"\$(command -v dsh)\" 2>/dev/null || command -v dsh); ")
            append("exec node --expose-internals \"\$DSH_REAL\" web" + opts + " 2>&1; ")
            append("else echo '[DSH-Folk] 容器内找不到 dsh，请在设置中重装运行时'; exit 1; fi")
        }

        appendLog("> 运行时: ${runtime().displayName()}")
        appendLog("> 硬链接: ${if (hardlinkSupported()) "支持（不加 --link2symlink）" else "不支持（启用 l2s 模拟）"}")
        appendLog("> 启动 dsh web，端口 $port")

        serverProcess = try {
            execRootfs(cmd)
        } catch (e: Exception) {
            val detail = "启动失败：${e.message ?: e.javaClass.simpleName}"
            appendLog("! $detail")
            if (runtimeId() == "proroot") noteProrootFailure(detail)
            _state.update { it.copy(phase = DshPhase.ERROR, message = detail) }
            return
        }
        forwardOutput(serverProcess)
        startedAt = System.currentTimeMillis()
        _state.update { it.copy(phase = DshPhase.STARTING, port = port, message = "正在启动服务…") }
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

    /** 轮询端口直到服务可访问或超时。 */
    private suspend fun awaitReady() {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (portOpen(port())) {
                _state.update {
                    it.copy(phase = DshPhase.RUNNING, progress = 1f, message = "服务已就绪 · ${it.webUrl}")
                }
                appendLog("> 服务已就绪: http://127.0.0.1:${port()}/")
                prefs().edit().putInt(DshEnv.KEY_PROROOT_FAIL, 0).apply()
                return
            }
            if (serverProcess?.isAlive == false) {
                val detail = "服务进程已退出（见启动日志）"
                appendLog("! $detail")
                if (runtimeId() == "proroot") noteProrootFailure(detail)
                _state.update { it.copy(phase = DshPhase.ERROR, message = detail) }
                return
            }
            delay(1_000)
        }
        _state.update { it.copy(phase = DshPhase.ERROR, message = "启动超时（见启动日志）") }
    }

    private fun portOpen(port: Int): Boolean = runCatching {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1", port), 800)
            true
        }
    }.getOrDefault(false)

    /** 停止 web 服务：先杀容器内的 dsh web，再销毁 proot 进程。 */
    fun stopServer() {
        runCatching {
            // proot 不隔离 PID，容器内 /proc 看到宿主全部进程 —— 只杀 bin.js web / dsh web，
            // 绝不裸 pkill -f node（会误杀 agent 或用户自己的 node 进程）
            execRootfs(
                "for _p in \$(pgrep -f 'bin.js web' 2>/dev/null; pgrep -f 'dsh web' 2>/dev/null); do " +
                    "kill \"\$_p\" 2>/dev/null; done; sleep 1; " +
                    "for _p in \$(pgrep -f 'bin.js web' 2>/dev/null; pgrep -f 'dsh web' 2>/dev/null); do " +
                    "kill -9 \"\$_p\" 2>/dev/null; done"
            ).waitFor(8, TimeUnit.SECONDS)
        }
        runCatching { serverProcess?.destroyForcibly() }
        serverProcess = null
        startedAt = 0L
        _state.update { it.copy(phase = DshPhase.NOT_READY, pid = null, message = "服务已停止") }
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

    private fun fail(message: String) {
        appendLog("! $message")
        _state.update { it.copy(phase = DshPhase.ERROR, message = message, speedBytesPerSec = 0L) }
    }

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

    /** 运行时占用统计（设置页存储信息用）。 */
    fun rootfsSizeBytes(): Long = dirSize(DshEnv.rootfs(appContext))

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.listFiles()?.sumOf { dirSize(it) } ?: 0L
    }
}
