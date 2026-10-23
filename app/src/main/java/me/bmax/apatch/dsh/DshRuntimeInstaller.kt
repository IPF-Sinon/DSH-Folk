package me.bmax.apatch.dsh

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.bmax.apatch.R
import me.bmax.apatch.util.LocaleCtx
import me.bmax.apatch.util.UpdateChecker
import me.bmax.apatch.util.appString
import org.json.JSONArray
import org.json.JSONObject

/**
 * 运行时的安装/校验/解压：下载（多镜像竞速）→ sha256 → 解压 → 保留数据 → 落盘版本。
 *
 * 从 DshRuntime.kt 里整簇搬出（交接规范阶段 4 的第二刀）。这些函数天然要动单例的状态机、
 * prefs 与服务器生命周期，而 Kotlin 同包看不见 private —— 所以走 [DshRuntimeHost] 接口交接
 * （不是把 private 放宽成 internal；那样只是拆文件，封装更差）。
 *
 * 变成扩展函数是为了**调用点零改动**：`DshRuntime.installMeta(...)` 的写法原样保留。
 */

internal const val ELF_MACHINE_AARCH64 = 183

internal const val ELF_MACHINE_X86_64 = 62

    /** 读 ELF 头的 `e_machine`（偏移 0x12，2 字节）。不是 ELF 或读不到返回 null。 */
internal fun DshRuntime.elfMachine(f: File): Int? = runCatching {
    if (!f.isFile) return@runCatching null
    FileInputStream(f).use { s ->
        val head = ByteArray(20)
        if (s.read(head) < 20) return@runCatching null
        if (head[0] != 0x7f.toByte() || head[1] != 'E'.code.toByte() ||
            head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()
        ) {
            return@runCatching null
        }
        val lo = head[18].toInt() and 0xff
        val hi = head[19].toInt() and 0xff
        if (head[5] == 1.toByte()) lo or (hi shl 8) else hi or (lo shl 8)
    }
}.getOrNull()

internal suspend fun DshRuntime.downloadAndInstall(preserveData: Boolean = true, newest: Boolean = true) {
    this.clearLog()
    // lambda 里的 this 指向 lambda 自身，不是 DshRuntime：文本先算好再传进去。
    val fetchingMsg = this.str(R.string.dsh_msg_fetching_meta)
    this.setState { s ->
        s.copy(
            phase = DshPhase.DOWNLOADING,
            progress = 0f,
            speedBytesPerSec = 0L,
            message = fetchingMsg,
        )
    }
    if (DshSource.setting(appContext) == DshSource.SOURCE_AUTO &&
        this.raceEnabled(RACE_RUNTIME)
    ) {
        this.logInfo(R.string.dsh_log_speedtest_start)
        val results = DshSource.speedTest()
        for (r in results.sortedBy { it.estimatedMs }) {
            val latency = r.latencyMs
            if (latency == null) {
                // 不可达就说不可达：老实现把哨兵值 Long.MAX_VALUE/4 当延迟打出来，
                // 用户看到的是「延迟 2305843009213693951ms」。
                this.logInfo(R.string.dsh_log_speedtest_unreachable, this.sourceName(r.source))
                continue
            }
            val speed = if (r.speedKBps > 0.0) String.format("%.1f MB/s", r.speedKBps / 1024.0)
            else this.str(R.string.dsh_log_speedtest_untested)
            this.logInfo(R.string.dsh_log_speedtest_row, this.sourceName(r.source), latency, speed)
        }
        this.logInfo(R.string.dsh_log_source_chosen, this.sourceName(DshSource.pickBest(results, appContext)))
    }
    this.logInfo(R.string.dsh_log_fetching_meta)
    // newest=false 只给「重装当前版本」用：那条路要装的必须是已装那一份，不能顺手升级。
    val meta = if (newest) this.fetchNewestMeta() else this.fetchMeta()
    if (meta == null) {
        this.fail(this.str(R.string.dsh_err_meta_failed))
        return
    }
    this.installMeta(meta, preserveData)
}

/**
 * 用一份**已知**的 metadata 走完安装：架构闸门 → 最低 App 闸门 → 空间 → 下载
 * （多镜像竞速）→ sha256 → 解压 → 落盘版本。
 *
 * 从 [downloadAndInstall] 里拆出来是为了版本列表：那条路径的 metadata 来自用户
 * 选中的那个 release，而不是当前通道 —— 否则「切换到历史版本」会变成「又装了一遍
 * 最新版」。
 */
internal suspend fun DshRuntime.installMeta(meta: DshMeta, preserveData: Boolean) {
    // 架构必须先对上：自定义源可以指向任何 metadata.json，下错架构的 rootfs 要到
    // 启动 node 时才报 "Exec format error"，白下 130 MB 还看不懂错在哪。
    //
    // 判据是 [DshSource.runtimeArch]（= APK 里 proot 的真实架构），**不是**
    // `Build.SUPPORTED_ABIS.contains(...)`：带 arm 转译层的 x86_64 设备两个 ABI 都报，
    // 用 contains 判会把 arm64 的 rootfs 放行，而 proot 是 x86_64 原生二进制、
    // 不走转译层，执行 arm64 的 ld.so 直接 SIGILL（1.7.6 真机上就是这么炸的）。
    val want = DshSource.runtimeArch()
    if (meta.arch.isNotEmpty() && meta.arch != want) {
        this.fail(this.str(R.string.dsh_err_arch_mismatch, meta.arch, want))
        return
    }
    // 最低 App 版本闸门：在下载之前拦住（同 startServer 里那道闸门）。置位
    // appUpdateRequired 让 UI 显示「请先更新应用」，而不是下 150MB 装完才炸。
    if (meta.minAppVersion.isNotEmpty() && !this.appSatisfies(meta.minAppVersion)) {
        this.setState { s ->
            s.copy(appUpdateRequired = true, requiredAppVersion = meta.minAppVersion)
        }
        this.fail(this.str(R.string.dsh_runtime_min_app_required, meta.minAppVersion))
        return
    }
    // 空间检查放在下载之前：rootfs 解压后约为压缩包的 3 倍，加上压缩包自身
    // 需要约 4 倍余量。等下载完再查等于白下 100 多 MB。
    if (meta.sizeBytes > 0) {
        val free = this.availableSpace(appContext.filesDir)
        val need = meta.sizeBytes * 4
        if (free in 1..need) {
            this.fail(this.str(R.string.dsh_err_no_space, free / 1024 / 1024, need / 1024 / 1024))
            return
        }
    }
    val tarball = DshEnv.downloadZip(appContext)
    this.logInfo(R.string.dsh_log_download_start, meta.version, meta.nodeVersion, meta.dsh)
    if (!this.downloadWithFallback(meta, tarball)) {
        this.fail(this.str(R.string.dsh_err_download_failed))
        return
    }
    this.logInfo(R.string.dsh_log_download_done, tarball.length() / 1024 / 1024)
    if (!this.verifySha256(tarball, meta.sha256)) {
        // 校验失败说明这份文件本身是坏的（续传时拼错、镜像给了旧包）——
        // 必须删掉，否则下次续传会一直基于这份坏数据往后接，永远校验不过。
        runCatching { tarball.delete() }
        this.fail(this.str(R.string.dsh_err_sha_mismatch))
        return
    }
    // 二次确认：metadata 里的 sizeBytes 可能与实际有偏差，用真实体积再算一次
    val free = this.availableSpace(appContext.filesDir)
    val need = (tarball.length() * 3.0).toLong()
    if (free in 1..need) {
        this.fail(this.str(R.string.dsh_err_no_space, free / 1024 / 1024, need / 1024 / 1024))
        return
    }
    this.logInfo(R.string.dsh_log_sha_ok)
    // 注意：lambda 里的 this 指向 lambda 自身，不是 DshRuntime —— 文本先取出来再传进去。
    val extractingMsg = this.str(R.string.dsh_msg_installing)
    this.setState { s ->
        s.copy(phase = DshPhase.EXTRACTING, progress = 0f, message = extractingMsg)
    }
    // 同上：withContext 的 lambda 里 this 是协程作用域，不是 DshRuntime —— 先取接收者再调用。
    val self = this
    val ok = withContext(Dispatchers.IO) { self.installRuntimeArchive(tarball, preserveData) }
    tarball.delete()
    if (!ok) {
        this.fail(this.str(R.string.dsh_err_extract_failed))
        return
    }
    this.logInfo(R.string.dsh_log_install_done)
    // 要求与版本一起落盘：下一次 attach() 才能离线判断「这份运行时需不需要
    // 更新的 App」。App 升级后 compareVersions 重算，满足即自动放行。
    this.prefs().edit()
        .putString(DshEnv.KEY_RUNTIME_VERSION, meta.version)
        .putString(DshEnv.KEY_RUNTIME_MIN_APP, meta.minAppVersion)
        .apply()
    // phase 必须回 NOT_READY，不能直接置 STARTING：[bootstrap] 下一步就调
    // [startServer]，而它的防重入守卫会把 STARTING 当成「已经在启动了」直接
    // return——首次安装后服务永远起不来就是这个自我拦截造成的。
    val readyMsg = this.str(R.string.dsh_msg_runtime_ready)
    this.setState { s ->
        s.copy(
            phase = DshPhase.NOT_READY,
            installed = true,
            runtimeVersion = meta.version,
            progress = 1f,
            speedBytesPerSec = 0L,
            message = readyMsg,
        )
    }
    // 刚解压完，体积是全新的：后台量一次并落缓存，界面随 state 自动更新
    this.refreshRootfsSize()
}

internal fun DshRuntime.fetchMeta(): DshMeta? = this.fetchMetaFrom(this.runtimeMetaUrl())

/**
 * 本通道当前该用的那份 metadata：同口味的候选里取**更新的一份**。
 *
 * 规则（真机反馈定下的）：测试通道 = 在正式通道的基础上「也接受」测试版 —— 所以候选是
 * 正式与测试两份，谁新用谁；正式通道不变，只认正式那份，绝不自动把人推到测试版。
 *
 * 为什么不做进 [fetchMeta]：它同时被「重装」用着（重装要装的是**已装那一份**）。若让它永远
 * 返回最新候选，用户点重装就会被装成另一份。所以候选逻辑只加在「检查更新 + 更新」这条路上。
 */
internal fun DshRuntime.fetchNewestMeta(): DshMeta? {
    val slim = DshSource.acceptRuntimeSlim(appContext)
    val suffix = if (DshSource.runtimeArch() == "arm64-v8a") "" else "-x86_64"
    val stableTag = if (slim) "runtime-slim-latest" else "runtime-latest"
    val betaTag = if (slim) "runtime-slim-beta-latest" else "runtime-beta-latest"
    val tags = if (DshSource.acceptRuntimeBeta(appContext)) listOf(stableTag, betaTag)
    else listOf(stableTag)
    val metas = tags.mapNotNull { tag -> this.fetchMetaOfTag(tag, suffix) }
    if (metas.isEmpty()) return null
    // 定序用 metadata 里的 dsh（0.2.1-rc.2 > 0.2.0-rc.2）；rootfs 修订号（-r6）不参与定序，
    // 因为两段相同内容不同修订谁新无从判断 —— 那时保持候选顺序（正式优先）。
    return metas.reduce { best, cur ->
        if (cur.dsh.isNotBlank() && (best.dsh.isBlank() || compareVersions(cur.dsh, best.dsh) > 0)) cur
        else best
    }
}

/**
 * 逐个下载源尝试，第一个成功即返回。
 *
 * metadata 的 mirrors 本身就是加了代理前缀的 URL，再给它们叠一次前缀只会产生
 * 重复项 —— 去重前实测 6 个候选里有 3 个是重的，等于同一个失败的 URL 连试两次。
 *
 * 顺序按 [DshSource.downloadRank]（= 刚才的测速结论）排，**不是** metadata 里
 * mirrors 的书写顺序：实测过 AxisNow 在测速阶段就 SSL 握手失败，却因为在
 * mirrors 里排第二而抢在 GitHub 直连前面被试一遍，白等一次超时。
 * `sortedBy` 是稳定排序，所以同权重（含未参与测速的自定义源）保持原相对顺序。
 */
internal fun DshRuntime.downloadWithFallback(meta: DshMeta, target: File): Boolean {
    val prefix = DshSource.proxyPrefix(this.runtimeSource(appContext))
    val candidates = if (this.raceEnabled(RACE_RUNTIME)) {
        // 竞速：候选＝全部已知线路 × 官方直链（按测速结论排序），再加 metadata 里发布方给的备选。
        // 早先候选只来自 metadata.mirrors，老运行时里只写了两条，新增线路就永远用不上。
        (DshSource.proxyCandidates(meta.url) + meta.mirrors)
            .distinct().sortedBy { DshSource.downloadRank(it) }
    } else {
        // 不竞速：只走当前选定的那一条（手选源，或 auto 退化成直连 github）+ 原址/发布方备选，
        // 顺序保持发布方原样，不按测速排序。
        val preferred = if (prefix.isEmpty()) emptyList() else listOf(prefix + meta.url)
        (preferred + listOf(meta.url) + meta.mirrors).distinct()
    }
    for ((i, url) in candidates.withIndex()) {
        this.logInfo(R.string.dsh_log_source_try, i + 1, candidates.size, url)
        val downloadingMsg = this.str(R.string.dsh_msg_downloading, meta.version)
        this.setState { s ->
            s.copy(message = downloadingMsg, speedBytesPerSec = 0L)
        }
        if (this.downloadFile(url, target, meta.sizeBytes)) {
            this.logInfo(R.string.dsh_log_source_ok, i + 1)
            return true
        }
        this.logWarn(R.string.dsh_log_source_fail, i + 1)
    }
    return false
}

/**
 * 下载单个文件，支持断点续传。
 *
 * 续传逻辑本身住在 [DshDownloader]（APK 更新也用同一套）；这里只把进度接到
 * 运行时的状态与启动日志上。
 */
internal fun DshRuntime.downloadFile(url: String, target: File, sizeBytes: Long): Boolean {
    var lastLoggedBucket = -1
    return DshDownloader.download(
        url = url,
        target = target,
        expectedSize = sizeBytes,
        onLog = { this.appendLog(it) },
        onProgress = { p ->
            if (p.contentLength <= 0) return@download
            val pctInt = p.percent
            // 日志每 5% 一行就够了，否则 400 行上限很快被下载进度占满
            if (pctInt / 5 > lastLoggedBucket) {
                lastLoggedBucket = pctInt / 5
                this.logInfo(R.string.dsh_log_downloading, pctInt, this.formatSpeed(p.speedBytesPerSec))
            }
            val pctMsg = this.str(R.string.dsh_msg_downloading_pct, pctInt, this.formatSpeed(p.speedBytesPerSec))
            this.setState { s ->
                s.copy(
                    progress = p.fraction,
                    speedBytesPerSec = p.speedBytesPerSec,
                    message = pctMsg,
                )
            }
        },
    )
}

/**
 * 目录改名，失败时回退「复制 + 删除」。
 *
 * 为什么要回退：Android 上 `File.renameTo` 只要**跨任何一层边界**（不同挂载点、厂商 ROM 上
 * 被特殊处理的目录、目标名已存在…）就会返回 false 而不抛异常。rootfs 是一棵 ~800MB、几十万
 * 个条目的树，正是最容易踩到的形状。Issue #12 里「解压 38 秒后无声失败」就发生在这一步：
 * `pending.renameTo(dest)` 返回 false，而代码此前**既不重试也不记录**，直接返回 false，
 * 用户只看到一句「rootfs 不完整」。
 *
 * @return 成功返回 true；失败时把原因写进 [note]。
 */
private fun renameOrCopy(src: File, dst: File, note: (String) -> Unit): Boolean {
    if (src.renameTo(dst)) return true
    note("renameTo 失败，回退复制：${src.name} → ${dst.name}")
    return runCatching {
        if (dst.exists()) dst.deleteRecursively()
        src.copyRecursiveTo(dst)
        src.deleteRecursively()
        true
    }.getOrElse {
        note("复制回退也失败：${it.javaClass.simpleName}: ${it.message}")
        false
    }
}

/** 递归复制（保留可执行位；rootfs 里的 bash/node/pnpm 必须可执行）。 */
private fun File.copyRecursiveTo(dst: File) {
    if (isDirectory) {
        dst.mkdirs()
        listFiles()?.forEach { child -> child.copyRecursiveTo(File(dst, child.name)) }
    } else {
        dst.parentFile?.mkdirs()
        inputStream().use { input -> dst.outputStream().use { output -> input.copyTo(output) } }
        // 可执行位必须显式带上：copyTo 只搬字节，权限是 inode 属性
        if (canExecute()) runCatching { dst.setExecutable(true, false) }
    }
}

/**
 * 在线下载与本地导入共用的安全安装流程：先解压到旁路目录，完成关键文件和架构校验，
 * 再用目录改名切换；切换失败或进程在中途被杀时，旧 rootfs 可恢复。
 *
 * **每个失败分支都必须留下日志**：这里有 5 个 `return false`，原先只有「校验失败」那一条
 * 说话，其余四条静默返回 —— Issue #12 的日志里只有最后那句 generic 报错，正是撞上了静默分支，
 * 排查因此卡了好几天。门禁会核对「每个 return false 之前都有一行日志」。
 */
internal fun DshRuntime.installRuntimeArchive(tarball: File, preserveData: Boolean): Boolean = runCatching {
    val dest = DshEnv.rootfs(appContext)
    val pending = File(appContext.filesDir, ".runtime-install")
    val backup = File(appContext.filesDir, ".runtime-rollback")

    // 安装全程的诊断：既进运行日志（用户能看到），也写独立文件（不被任何 clearLog/轮转冲掉）。
    val steps = mutableListOf<String>()
    fun note(line: String) {
        steps += line
        this.appendLog("! [安装] $line")
        this.writeInstallDiag(steps)
    }

    this.recoverInterruptedRuntimeInstall()
    if (pending.exists()) pending.deleteRecursively()
    pending.mkdirs()
    note("开始解压到 ${pending.name}（tar 大小 ${tarball.length()} 字节，可用 ${this.availableSpace(appContext.filesDir)}）")

    TarGzipExtractor.extractRootfs(tarball, pending)
    note(
        "解压完成：跳过 ${TarGzipExtractor.lastSkipped} 条, chmod 失败 ${TarGzipExtractor.lastChmodFailures} 条" +
            (if (TarGzipExtractor.lastSkipNote.isNotEmpty()) "，跳过项：${TarGzipExtractor.lastSkipNote}" else ""),
    )

    if (!this.validateRuntimeRoot(pending)) {
        // validateRuntimeRoot 内部已经打出诊断三联（缺失/跳过/chmod），这里补一句总结
        note("校验失败：rootfs 不完整（详见上面的必需文件清单）")
        pending.deleteRecursively()
        return@runCatching false
    }
    note("校验通过（bash/node/pnpm/dsh 齐全，架构正确）")

    if (preserveData && !this.stashPreservedData(dest)) {
        note("保留数据失败：未能把 ${dest.name} 下的用户数据搬进暂存区")
        pending.deleteRecursively()
        return@runCatching false
    }

    if (backup.exists()) backup.deleteRecursively()
    if (dest.exists()) {
        if (!renameOrCopy(dest, backup, ::note)) {
            note("移走旧 rootfs 失败：${dest.name} → ${backup.name}（改名与复制回退都不成）")
            if (preserveData) DshEnv.recoverPreserved(appContext)
            pending.deleteRecursively()
            return@runCatching false
        }
    } else {
        backup.mkdirs()
    }
    note("旧 rootfs 已让位（${if (dest.exists()) "移入备份" else "原本就不存在"}）")

    if (!renameOrCopy(pending, dest, ::note)) {
        note("切换到新 rootfs 失败：${pending.name} → ${dest.name}（改名与复制回退都不成）")
        if (backup.exists()) backup.renameTo(dest)
        if (preserveData) DshEnv.recoverPreserved(appContext)
        return@runCatching false
    }
    note("新 rootfs 已就位")

    if (preserveData && !this.restorePreservedData(dest)) {
        note("恢复用户数据失败：暂存区内容没能放回新 rootfs")
        this.returnRestoredDataToStash(dest)
        dest.deleteRecursively()
        if (backup.exists()) backup.renameTo(dest)
        DshEnv.recoverPreserved(appContext)
        return@runCatching false
    }
    if (backup.exists()) backup.deleteRecursively()
    note("安装完成")
    this.clearInstallDiag()
    true
}.getOrElse {
    this.logWarn(R.string.dsh_log_extract_failed, "${it.javaClass.simpleName}: ${it.message}")
    this.appendLog("! [安装] 抛出异常：${it.javaClass.simpleName}: ${it.message}")
    this.recoverInterruptedRuntimeInstall()
    false
}

internal fun DshRuntime.stashPreservedData(root: File): Boolean {
    val stash = DshEnv.dshPreserve(appContext)
    DshEnv.recoverPreserved(appContext)
    if (stash.exists()) stash.deleteRecursively()
    for (rel in DshEnv.PRESERVED_PATHS) {
        val src = File(root, rel)
        if (!src.exists()) continue
        val dst = File(stash, rel)
        dst.parentFile?.mkdirs()
        if (!src.renameTo(dst)) {
            this.logWarn(R.string.dsh_log_preserve_failed, rel)
            DshEnv.recoverPreserved(appContext)
            return false
        }
    }
    return true
}

internal fun DshRuntime.restorePreservedData(root: File): Boolean {
    val stash = DshEnv.dshPreserve(appContext)
    for (rel in DshEnv.PRESERVED_PATHS) {
        val src = File(stash, rel)
        if (!src.exists()) continue
        val dst = File(root, rel)
        dst.parentFile?.mkdirs()
        if (dst.exists()) dst.deleteRecursively()
        if (!src.renameTo(dst)) {
            this.logWarn(R.string.dsh_log_restore_failed, rel, stash.name)
            return false
        }
    }
    if (stash.exists()) stash.deleteRecursively()
    return true
}

internal fun DshRuntime.returnRestoredDataToStash(root: File) {
    val stash = DshEnv.dshPreserve(appContext)
    for (rel in DshEnv.PRESERVED_PATHS) {
        val src = File(root, rel)
        val dst = File(stash, rel)
        if (!src.exists() || dst.exists()) continue
        dst.parentFile?.mkdirs()
        src.renameTo(dst)
    }
}

internal fun DshRuntime.validateRuntimeRoot(root: File): Boolean {
    val required = listOf(
        "usr/bin/bash" to "bash",
        "usr/local/bin/node" to "node",
        "usr/local/bin/pnpm" to "pnpm",
        "usr/local/lib/node_modules/@deepseek-ai/dsh/package.json" to "dsh",
    )
    val missing = required.filterNot { File(root, it.first).exists() }
    if (missing.isNotEmpty()) {
        this.logWarn(R.string.dsh_log_missing_after_extract, this.joinForLog(missing.map { it.second }))
        // 只说「缺了什么」不足以定位 —— Issue #12 的用户就卡在这里：
        //   「是下载坏了？解压跳过了？还是解出来但大小/权限不对？」
        // 所以补两份实测证据：解压时的跳过计数，以及每个必需文件的实际大小与可执行位。
        this.logExtractDiagnostics(root, required)
        return false
    }
    val machine = this.elfMachine(File(root, "usr/local/bin/node"))
    val want = if (DshSource.runtimeArch() == "arm64-v8a") ELF_MACHINE_AARCH64 else ELF_MACHINE_X86_64
    if (machine != null && machine != want) {
        this.logWarn(R.string.dsh_log_node_machine, machine, want)
        this.logExtractDiagnostics(root, required)
        return false
    }
    if (!File(root, "usr/bin/python3").exists()) this.logWarn(R.string.dsh_log_missing_python)
    if (!File(root, "usr/local/bin/pnpm").exists()) this.logWarn(R.string.dsh_log_missing_pnpm)
    return true
}

/**
 * 把安装过程的每一步写进 `<files>/audit/install-diag.txt`。
 *
 * 为什么要有这个**独立文件**（而不是只写运行日志）：运行日志会被 `clearLog()` 轮转、被 200 行
 * 的卡片窗口截断、也可能被后续启动覆盖。Issue #12 里用户贴出来的日志恰好缺了关键几行，
 * 于是「到底败在哪一步」只能靠猜。这个文件只记安装步骤、每步覆盖写、安装成功后自动删除，
 * 与 audit 目录同处 —— bugreport 会把它一起收走，用户也可以在文件管理里直接看到。
 */
internal fun DshRuntime.writeInstallDiag(steps: List<String>) {
    runCatching {
        val dir = File(appContext.filesDir, "audit").apply { mkdirs() }
        File(dir, "install-diag.txt").writeText(
            steps.joinToString("\n", postfix = "\n"), StandardCharsets.UTF_8,
        )
    }.onFailure { android.util.Log.w("DSH-Folk", "写安装诊断失败: ${it.message}") }
}

/** 安装成功后删掉诊断文件：它只为「失败后排查」而存在，成功时留着会占位并造成误解。 */
internal fun DshRuntime.clearInstallDiag() {
    runCatching { File(File(appContext.filesDir, "audit"), "install-diag.txt").delete() }
}

/**
 * 解压后的**诊断三联**：跳过计数 / 必需文件的实测状态 / chmod 失败。
 *
 * 为什么要把这些搬进日志：`TarGzipExtractor.lastSkipped`、`lastChmodFailures` 以前全仓无人读取 ——
 * 宽容路径跳过了条目、`Os.chmod` 失败了，用户那边只看到一句「解压后 rootfs 不完整」，手上没有任何
 * 可判断的线索（Issue #12 正是如此）。这三行日志落在运行日志卡片上，且随诊断包导出。
 */
internal fun DshRuntime.logExtractDiagnostics(root: File, required: List<Pair<String, String>>) {
    runCatching {
        val skipped = TarGzipExtractor.lastSkipped
        if (skipped > 0) {
            this.appendLog(
                "! 解压时跳过了 $skipped 个条目（可能是包损坏，也可能是条目超出安全上限）：" +
                    TarGzipExtractor.lastSkipNote,
            )
        }
        for ((rel, label) in required) {
            val f = File(root, rel)
            val exists = f.exists()
            val size = if (exists) f.length() else -1L
            val exec = if (exists) f.canExecute() else false
            this.appendLog("! 必需文件 $label：存在=$exists 大小=$size 可执行=$exec（$rel）")
        }
        val chmodFails = TarGzipExtractor.lastChmodFailures
        if (chmodFails > 0) {
            this.appendLog(
                "! 解压时有 $chmodFails 个文件 chmod 失败（首个：${TarGzipExtractor.lastChmodFailNote}）" +
                    " —— 可执行位没落上会让 bash/node/pnpm 起不来",
            )
        }
    }
}

internal fun DshRuntime.recoverInterruptedRuntimeInstall() {
    val dest = DshEnv.rootfs(appContext)
    val pending = File(appContext.filesDir, ".runtime-install")
    val backup = File(appContext.filesDir, ".runtime-rollback")
    if (!dest.exists() && backup.exists()) {
        backup.renameTo(dest)
        DshEnv.recoverPreserved(appContext)
    } else if (dest.exists() && backup.exists()) {
        val stash = DshEnv.dshPreserve(appContext)
        if (!stash.exists() || this.restorePreservedData(dest)) {
            backup.deleteRecursively()
        } else {
            this.returnRestoredDataToStash(dest)
            dest.deleteRecursively()
            backup.renameTo(dest)
            DshEnv.recoverPreserved(appContext)
        }
    } else {
        DshEnv.recoverPreserved(appContext)
    }
    if (pending.exists()) pending.deleteRecursively()
}
