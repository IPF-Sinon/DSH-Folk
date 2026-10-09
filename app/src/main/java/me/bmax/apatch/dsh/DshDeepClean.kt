package me.bmax.apatch.dsh

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import androidx.annotation.StringRes
import me.bmax.apatch.R
import java.io.File

/**
 * 「深度清理」的扫描与删除（功能页那张「清理资源和缓存」卡片**长按**进的页）。
 *
 * 与那张卡片上原有的浅清理（主题 / 媒体 / 音效 / 应用缓存）不是一件事：这里扫的是
 * **运行时（rootfs）里的垃圾**与**共享存储里的大文件 / 大文件夹**，所以：
 *
 * - 清单是**先侦察再定**的，不是凭想象写的路径。rootfs 是 Ubuntu 24.04 base + Node + dsh +
 *   pnpm（见 `runtime-builder/build-rootfs.sh`），所以真实存在、且删了不影响运行的是：
 *   `tmp` / `var/tmp`（容器 TMPDIR）、`var/cache/apt/archives`（apt 下载的 .deb）、
 *   `var/lib/apt/lists`（apt 索引，`apt update` 会重下）、`var/log`、`root/.npm`（npm 的
 *   `_cacache` 与 `_logs`）、`root/.cache`、`root/.node-gyp`，以及 `root/.dsh` 下三层内的
 *   `*.log`（dsh 与插件自己落盘的日志）。
 * - **绝不列入**的东西（列进去就是数据事故）：`root/.dsh`（会话 / 插件 / 配置）、
 *   `root/.local`（pnpm 的内容存储 —— `dsh` 原子提交与 pnpm 的硬链接真身都在里面，
 *   删了 `node_modules` 全变悬空链接）、`.l2s`（proot 的 link2symlink 真身目录）、
 *   `root/workspace`（用户挂进来的手机目录），以及 `filesDir/runtime-download.tar.gz`
 *   （它可能是**正在下载**的运行时，删了会变成一次 SHA 校验失败）。
 *
 * 删除走的是与 [DshDocumentsProvider.deleteTree]、[DshFsBridge] 同一条安全纪律：只删白名单
 * 根（rootfs / filesDir / cacheDir / 共享存储）**canonicalPath 之内**的路径；**不跟随符号
 * 链接**（`File.isDirectory` 是跟随链接的，rootfs 里上千个链接会因此把删除带到根外）；
 * 结果逐个上报，失败如实返回，不吞。
 */
internal object DshDeepClean {

    /** 一个可勾选的清理目标。[path] 是它的唯一身份（勾选集合里存的就是它）。 */
    data class Target(
        val path: String,
        @StringRes val kindRes: Int,
        val bytes: Long,
        val files: Int,
    )

    /**
     * 一块区域（运行时 / 应用内部 / 共享存储）的扫描结果。
     *
     * @param noteRes 这块为什么没扫出东西（权限不足等），由界面负责翻译 —— 领域层不碰
     *   `Context.getString`（那会拿到系统语言而不是应用内语言，见 `appString` 那条纪律）。
     */
    data class Section(
        @StringRes val titleRes: Int,
        val partitionPath: String,
        val totalBytes: Long,
        val freeBytes: Long,
        val targets: List<Target>,
        @StringRes val noteRes: Int? = null,
    )

    /** 删除结果：成功几项、哪几项失败（失败路径原样带回给界面显示）。 */
    data class DeleteResult(val deleted: Int, val failed: List<String>)

    /**
     * 大文件阈值：单个文件 ≥ 32MB。
     *
     * 定这个值是因为手机的共享存储里 32MB 以上基本就是视频 / 安装包 / 模型文件这类真正占
     * 地方的东西；再小（比如 8MB）会把相册里的连拍和离线地图碎片都翻出来，清单没法看。
     */
    private const val LARGE_FILE_BYTES = 32L * 1024 * 1024

    /**
     * 大文件夹阈值：递归合计 ≥ 64MB。
     *
     * 与单文件阈值同样量级：一个文件夹要顶得上两个大文件才值一条。命中之后**不再列它的
     * 子项**（见 [largeTargets]），所以清单里不会出现父子嵌套，勾选与合计天然一致。
     */
    private const val LARGE_DIR_BYTES = 64L * 1024 * 1024

    /** 共享存储往下翻的层数：够看到 `Download/xxx/` 这一档，又不至于把整张卡翻一遍。 */
    private const val LARGE_SCAN_DEPTH = 3

    /** 运行时里的垃圾清单（rootfs 内相对路径 → 界面上的类别）。 */
    private val RUNTIME_JUNK = listOf(
        "tmp" to R.string.dsh_clean_kind_temp,
        "var/tmp" to R.string.dsh_clean_kind_temp,
        "var/cache/apt/archives" to R.string.dsh_clean_kind_pkg,
        "var/lib/apt/lists" to R.string.dsh_clean_kind_pkg,
        "var/log" to R.string.dsh_clean_kind_log,
        "root/.npm" to R.string.dsh_clean_kind_pkg,
        "root/.cache" to R.string.dsh_clean_kind_cache,
        "root/.node-gyp" to R.string.dsh_clean_kind_cache,
    )

    /** 扫描三块区域。**阻塞**（rootfs 是十万级文件），调用方必须在 IO 线程上跑。 */
    fun scan(ctx: Context): List<Section> {
        val out = ArrayList<Section>()

        // ── 1. 运行时（rootfs） ──
        val rootfs = DshEnv.rootfs(ctx)
        val runtime = ArrayList<Target>()
        for ((rel, kind) in RUNTIME_JUNK) {
            val f = File(rootfs, rel)
            if (!f.exists() && !isSymlink(f)) continue
            val (bytes, files) = sizeOf(f)
            runtime.add(Target(f.absolutePath, kind, bytes, files))
        }
        // dsh 与插件自己落盘的日志。整棵 /root/.dsh 都不能删，只挑出 *.log 这几个文件。
        for (f in logsUnder(File(rootfs, "root/.dsh"), 3)) {
            val (bytes, files) = sizeOf(f)
            runtime.add(Target(f.absolutePath, R.string.dsh_clean_kind_log, bytes, files))
        }
        out.add(section(rootfs, R.string.dsh_clean_section_runtime, runtime, null))

        // ── 2. 应用内部存储 ──
        // 只有应用缓存这一项。三条刻意不列的：
        //   - `filesDir/runtime-download.tar.gz`：它可能是**正在下载**的那一份，删了会变成一次
        //     SHA 校验失败；
        //   - `filesDir/logs`（应用自己的 dsh 日志）：LogStore 持有它们的写句柄，直接删文件会让
        //     后续输出写进已被删除的 inode（内存里还在、盘上没了），要清得走 DshRuntime.clearLog；
        //   - `cacheDir/shm` 与 `filesDir` 下的其它目录：前者就在缓存里，后者是数据不是垃圾。
        val app = ArrayList<Target>()
        addIfPresent(app, ctx.cacheDir, R.string.dsh_clean_kind_cache)
        out.add(section(ctx.filesDir, R.string.dsh_clean_section_app, app, null))

        // ── 3. 共享存储的大文件 / 大文件夹 ──
        val ext = Environment.getExternalStorageDirectory()
        val shared = ArrayList<Target>()
        var note: Int? = null
        val readable = runCatching { ext.listFiles() }.getOrNull() != null
        if (!readable) {
            // 没有「所有文件访问」时 listFiles 拿不到东西；不猜原因，照实说
            note = R.string.dsh_clean_note_no_perm
        } else {
            shared.addAll(largeTargets(ext))
            if (shared.isEmpty()) note = R.string.dsh_clean_note_none
        }
        out.add(section(ext, R.string.dsh_clean_section_shared, shared, note))

        return out
    }

    /**
     * 删除这些路径。返回成功数与失败清单；失败**不吞**。
     *
     * 白名单根：rootfs / filesDir / cacheDir / 共享存储。清单本来只可能来自 [scan]，这里再
     * 用 canonicalPath 确认一遍 —— 扫描结果到删除之间隔着一次点击，越界检查不能省。
     */
    fun delete(ctx: Context, paths: List<String>): DeleteResult {
        val roots = allowedRoots(ctx)
        var deleted = 0
        val failed = ArrayList<String>()
        for (p in paths) {
            val f = File(p)
            val canonical = runCatching { f.canonicalPath }.getOrNull()
            if (canonical == null || !insideRoots(canonical, roots)) {
                failed.add(p)
                continue
            }
            // 白名单根**本身**不删那个目录项：删掉 `/data/…/cache` 会让后续
            // `File(cacheDir, "x").createNewFile()` 直接抛 IOException（并非每个调用点都会先
            // mkdirs）。清空内容、目录留着 —— 与浅清理那边的做法一致。
            val ok = if (roots.any { it == canonical }) clearChildren(f) else deleteTree(f)
            if (ok) deleted++ else failed.add(p)
        }
        return DeleteResult(deleted, failed)
    }

    /** [child] 是否严格落在 [parent] 之下（用于父子勾选，按目录段边界）。 */
    fun isUnder(child: String, parent: String): Boolean =
        child != parent && child.startsWith(parent.trimEnd('/') + "/")

    // ────────────────────────── 内部实现 ──────────────────────────

    private fun section(
        partition: File,
        @StringRes title: Int,
        targets: List<Target>,
        @StringRes note: Int?,
    ): Section {
        // 路径也过 runCatching：Environment.getExternalStorageDirectory() 是平台类型，
        // 真机上没挂载共享存储时可能为 null —— 那一下不该把整页扫崩。
        val stat = runCatching { StatFs(partition.absolutePath) }.getOrNull()
        return Section(
            titleRes = title,
            partitionPath = runCatching { partition.absolutePath }.getOrDefault(""),
            totalBytes = runCatching { stat?.totalBytes ?: 0L }.getOrDefault(0L),
            freeBytes = runCatching { stat?.availableBytes ?: 0L }.getOrDefault(0L),
            targets = targets,
            noteRes = note,
        )
    }

    private fun addIfPresent(out: MutableList<Target>, f: File?, @StringRes kind: Int) {
        if (f == null) return
        if (!f.exists() && !isSymlink(f)) return
        val (bytes, files) = sizeOf(f)
        out.add(Target(f.absolutePath, kind, bytes, files))
    }

    /**
     * 共享存储里的大文件 / 大文件夹。
     *
     * 只在**没命中大文件夹**的分支里继续往下走：一个 2GB 的目录已经列出来了，再列它里面的
     * 十几个大文件只会让清单变长、勾选变糊（父与子都勾上就重复统计了）。
     */
    private fun largeTargets(root: File): List<Target> {
        val out = ArrayList<Target>()

        fun walk(dir: File, depth: Int) {
            val children = dir.listFiles() ?: return
            for (f in children) {
                if (isSymlink(f)) continue
                if (f.isFile) {
                    if (f.length() >= LARGE_FILE_BYTES) {
                        out.add(Target(f.absolutePath, R.string.dsh_clean_kind_large_file, f.length(), 1))
                    }
                    continue
                }
                if (!f.isDirectory) continue
                val (bytes, files) = sizeOf(f)
                if (bytes >= LARGE_DIR_BYTES) {
                    out.add(Target(f.absolutePath, R.string.dsh_clean_kind_large_dir, bytes, files))
                } else if (depth < LARGE_SCAN_DEPTH) {
                    walk(f, depth + 1)
                }
            }
        }

        walk(root, 1)
        return out.sortedByDescending { it.bytes }
    }

    /** 递归合计 (字节, 文件数)。符号链接只当**一项**算，不跟进去（否则会数到根外）。 */
    private fun sizeOf(f: File): Pair<Long, Int> {
        if (isSymlink(f)) return 0L to 1
        if (!f.exists()) return 0L to 0
        if (f.isFile) return f.length() to 1
        var bytes = 0L
        var files = 0
        for (c in f.listFiles() ?: emptyArray<File>()) {
            val (b, n) = sizeOf(c)
            bytes += b
            files += n
        }
        return bytes to files
    }

    /** `dir` 下 [maxDepth] 层以内的 `*.log` 文件（不跟随符号链接）。 */
    private fun logsUnder(dir: File, maxDepth: Int): List<File> {
        if (!dir.isDirectory) return emptyList()
        val out = ArrayList<File>()

        fun walk(d: File, depth: Int) {
            for (f in d.listFiles() ?: emptyArray<File>()) {
                if (isSymlink(f)) continue
                when {
                    f.isFile && f.name.endsWith(".log") -> out.add(f)
                    f.isDirectory && depth < maxDepth -> walk(f, depth + 1)
                }
            }
        }

        walk(dir, 1)
        return out.sortedBy { it.absolutePath }
    }

    /** `lstat`：链接**本身**也是要删的条目，所以不能用跟随链接的 `File.isDirectory`。 */
    private fun isSymlink(f: File): Boolean =
        runCatching { OsConstants.S_ISLNK(Os.lstat(f.path).st_mode) }.getOrDefault(false)

    /**
     * 递归删除，**不跟随符号链接**（与 [DshDocumentsProvider] 的 `deleteTree` 同一写法：
     * 一个指向目录的链接会让 `File.deleteRecursively()` 去删链接目标里的内容）。
     */
    private fun deleteTree(f: File): Boolean {
        if (isSymlink(f)) return runCatching { f.delete() }.getOrDefault(false)
        if (f.isDirectory) {
            for (c in f.listFiles() ?: emptyArray<File>()) {
                if (!deleteTree(c)) return false
            }
        }
        return runCatching { f.delete() }.getOrDefault(false)
    }

    private fun allowedRoots(ctx: Context): List<String> = listOfNotNull(
        runCatching { DshEnv.rootfs(ctx).canonicalPath }.getOrNull(),
        runCatching { ctx.filesDir.canonicalPath }.getOrNull(),
        runCatching { ctx.cacheDir.canonicalPath }.getOrNull(),
        runCatching { Environment.getExternalStorageDirectory().canonicalPath }.getOrNull(),
    )

    /** 清空目录里的内容，但**保留目录本身**（用于白名单根本身，见 [delete]）。 */
    private fun clearChildren(dir: File): Boolean {
        val children = dir.listFiles()
        if (children == null) return !dir.exists() // 读不了 = 失败；本来就不存在 = 已经是目标状态
        for (c in children) {
            if (!deleteTree(c)) return false
        }
        return true
    }

    private fun insideRoots(canonical: String, roots: List<String>): Boolean =
        roots.any { canonical == it || canonical.startsWith(it.trimEnd('/') + "/") }
}
