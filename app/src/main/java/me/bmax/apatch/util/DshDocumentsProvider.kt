package me.bmax.apatch.util

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * 把应用私有数据目录（`context.dataDir`）作为一个 root 暴露给系统文件选择器。
 *
 * 这是免 root 让文件管理器浏览 `/data/data/<pkg>` 的唯一正路：SAF 允许应用把自己的
 * 目录注册成 `DOCUMENTS_PROVIDER`，系统「文件」应用会在侧栏显示它。`MANAGE_DOCUMENTS`
 * 是签名级权限、只有系统持有，所以普通应用拿不到目录列表，只能通过用户在选择器里
 * 主动挑中后授予的 URI 访问单个文件 —— 不是把私有目录对全世界敞开。
 *
 * ## 安全边界
 *
 * documentId 是外部输入（不可信），拼进 `dataDir` 后必须仍落在 `dataDir` 内，否则抛
 * [FileNotFoundException]。这道检查分三层，按操作语义各取所需：
 *
 * | 解析器 | 末段跟随链接 | 用在哪 |
 * |---|---|---|
 * | [resolveLexical] | 否，且完全不碰文件系统 | [isChildDocument]（纯 id 层面的父子关系） |
 * | [resolve] | 是 | [openDocument] —— 读写真实字节 |
 * | [resolveDirExisting] | 是（但返回词法路径） | 列目录、新建、移动的目标目录 |
 * | [resolveLeaf] | 否（父目录仍校验） | 查询、删除、改名、`mt:` 改权限/时间/建链接 |
 *
 * rootfs 里有上千个符号链接（其中约 195 个指向绝对路径），所以「末段是否跟随」不是
 * 细节而是语义：删一个链接不该删掉它指向的东西，而读它的内容必须跟随。
 *
 * ## 符号链接必须用 lstat 判存在
 *
 * `File.exists()` 会跟随链接，于是指向容器外（`etc/mtab -> /proc/mounts`）或指向缺失
 * 目标（`etc/alternatives` 里那些 man 页链接）的链接一律被判为「不存在」——
 * 1.7.8 用它做过滤和存在性检查，结果这些行要么整条消失，要么点开报错。
 * 现在存在性统一走 [lexists]（`Os.lstat`），类型、大小、mtime 也都取自同一次 lstat。
 *
 * ## documentId 必须能往返
 *
 * 列目录与新建返回的路径保持**词法**形状，不做 canonicalize。否则点开 `bin/`
 * （容器里 `bin -> usr/bin`）列出的子项 id 会变成 `…/rootfs/usr/bin/…`；当 tree URI
 * 的根正好是 `…/rootfs/bin` 时，这些 id 不再是树根的后代，`enforceTree` 直接
 * `SecurityException`。
 *
 * ## MT 管理器扩展
 *
 * 额外提供 `mt_path` / `mt_extras` 两列与三个 `mt:` [call] 方法（改权限、改时间、
 * 建软链接），对齐 MT 管理器「注入文件提供器」的线上约定。别的客户端不请求这两列
 * 就不会看到它们（[MatrixCursor.RowBuilder.add] 对 projection 外的列静默忽略）。
 *
 * ## 排除项
 *
 * `cache/`、`code_cache/`、`no_backup/` 不列出：噪音大且随时被系统回收。
 * `rootfs/` 正常列出 —— 这是容器运行时；用户数据（sessions、profiles）在
 * `rootfs/root/.dsh/` 里，更新运行时由 extractRootfs 暂存/恢复，不会丢。
 *
 * ## documentId 不能是空串
 *
 * 1.7.3～1.7.7 把 root 的 `COLUMN_DOCUMENT_ID` 写成 `""`，浏览必然失败（真机现象：系统
 * 选择器里能看到 DSH-Folk 这个 root，点进去只有「暂时无法加载内容」）。原因在
 * [DocumentsProvider] 自带的 `UriMatcher`：空 id 拼进 URI 会产生**空路径段**，而
 * `Uri.getPathSegments()` 会把空段丢掉，于是所有模式全部错位 ——
 *
 * | 构造 | 实际 path | 分段 | 匹配 |
 * |---|---|---|---|
 * | `buildDocumentUri(a, "")` | `/document/` | `[document]` | 无匹配 → `Unsupported Uri` |
 * | `buildChildDocumentsUri(a, "")` | `/document//children` | `[document, children]` | 错配到 `document` + 通配段 → 调到 `queryDocument("children")` |
 * | `buildTreeDocumentUri(a, "")` | `/tree/` | `[tree]` | 无匹配 |
 *
 * 所以 root 文档的 id 是 [ROOT_DOC_ID]（`"/"`），其余 id 形如 `/files/rootfs`：
 * 非空、且因为 `Uri.Builder.appendPath` 会把 `/` 编码成 `%2F` 而始终是**单个**路径段。
 *
 * ## 声明 FLAG_SUPPORTS_IS_CHILD 就必须重写 isChildDocument
 *
 * [DocumentsProvider.isChildDocument] 的默认实现**恒返回 false**。tree URI 的每次访问
 * 都要过 `enforceTree()` → `isChildDocument()`，false 就是 `SecurityException`，
 * 于是 `ACTION_OPEN_DOCUMENT_TREE` + `DocumentFile.listFiles()` 这条路（MT 管理器
 * 「添加本地存储」走的正是它）被完全堵死。声明了 flag 却不实现，比不声明更糟。
 */
class DshDocumentsProvider : DocumentsProvider() {

    private val rootId = "dshfolk"

    private var shouldInit = false

    override fun onCreate(): Boolean {
        val processName = getProcessNameCompat()
        shouldInit = !processName.endsWith(":root") && !processName.endsWith(":webui")
        return shouldInit
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        if (!shouldInit) return MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val ctx = context ?: return MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val base = ctx.dataDir
        val stats = android.os.StatFs(base.absolutePath)
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val row = cursor.newRow()
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, rootId)
        row.add(
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.FLAG_SUPPORTS_CREATE or
                DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD or
                DocumentsContract.Root.FLAG_LOCAL_ONLY,
        )
        row.add(DocumentsContract.Root.COLUMN_TITLE, "DSH-Folk")
        row.add(
            DocumentsContract.Root.COLUMN_SUMMARY,
            "rootfs/root/.dsh (sessions, profiles) — edit container files at your own risk",
        )
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
        row.add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, stats.availableBytes)
        row.add(DocumentsContract.Root.COLUMN_CAPACITY_BYTES, stats.totalBytes)
        row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
        // 侧栏图标：不给的话某些文件管理器（MT 管理器就是）画一个空白占位
        row.add(DocumentsContract.Root.COLUMN_ICON, ctx.applicationInfo.icon)
        return cursor
    }

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        if (!shouldInit) return MatrixCursor(projection ?: DEFAULT_DOC_PROJECTION)
        // 末段不跟随链接：符号链接自身也要能被描述（列表里看得见就必须查得到）
        val file = resolveLeafExisting(documentId.orEmpty())
        return documentCursor(file, projection ?: DEFAULT_DOC_PROJECTION)
    }

    override fun queryChildDocuments(
        parentDocumentId: String?,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        if (!shouldInit) return MatrixCursor(projection ?: DEFAULT_DOC_PROJECTION)
        // 列目录会跟随末段链接（要看的是目标里的内容），所以边界按 canonical 校验；
        // 但返回词法路径，子项 documentId 才留在用户点进来的那条路径下（见 resolveDirExisting）
        val parent = resolveDirExisting(parentDocumentId.orEmpty())
        if (!parent.isDirectory) throw FileNotFoundException("Not a directory: $parentDocumentId")
        val cursor = MatrixCursor(projection ?: DEFAULT_DOC_PROJECTION)
        val root = context!!.dataDir.canonicalFile
        val children = parent.listFiles()
            ?.filter { f ->
                // 只在根层排除会被系统随时回收的目录。
                // 谓词必须是 `parent != root ||`：写成 `parent == root &&` 会让**所有**
                // 子目录返回空列表（1.7.3～1.7.7 的 bug，进一级就什么都没有）。
                parent != root || f.name !in EXCLUDED_TOP
            }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: emptyList()
        for (child in children) {
            addDocumentRow(cursor, child)
        }
        return cursor
    }

    /**
     * tree URI 的访问校验（`enforceTree`）靠这个方法。
     *
     * 默认实现恒返回 false，会让整棵树无法浏览 —— 见类 KDoc。
     *
     * 用**词法**包含关系判断，不做 canonicalize：这里回答的只是「这个 id 是不是那个
     * id 的后代」，是纯 id 层面的问题。若在这里跟随符号链接，rootfs 里指向容器外的
     * 链接（`etc/mtab -> /proc/mounts` 之类）会被判成非后代，于是连它那一行都打不开。
     * 真正的文件系统边界由 [resolve] / [resolveLeaf] 在每个操作里各自把关。
     */
    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean =
        runCatching {
            val parent = resolveLexical(parentDocumentId.orEmpty())
            val child = resolveLexical(documentId.orEmpty())
            child.path == parent.path || child.path.startsWith(parent.path + File.separator)
        }.getOrDefault(false)

    override fun openDocument(
        documentId: String?,
        mode: String?,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (!shouldInit) throw FileNotFoundException("Not available in this process")
        // 读写真实字节：必须**跟随**符号链接后仍在 dataDir 内
        val file = resolveExisting(documentId.orEmpty())
        val accessMode = when (mode.orEmpty()) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            "w", "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            "wa" -> ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_APPEND
            "rw", "rwt" -> ParcelFileDescriptor.MODE_READ_WRITE or
                ParcelFileDescriptor.MODE_CREATE
            else -> throw IllegalArgumentException("Unsupported mode: $mode")
        }
        return ParcelFileDescriptor.open(file, accessMode)
    }

    override fun createDocument(
        parentDocumentId: String?,
        mimeType: String?,
        displayName: String?,
    ): String? {
        if (!shouldInit) return null
        val parent = resolveDirExisting(parentDocumentId.orEmpty())
        if (!parent.isDirectory) throw FileNotFoundException("Parent is not a directory")
        val name = safeName(displayName) ?: "untitled"
        val target = File(parent, name)
        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            if (!lexists(target) && !target.mkdirs()) throw FileNotFoundException("Cannot create directory")
        } else {
            if (lexists(target)) throw FileNotFoundException("Already exists: $name")
            target.outputStream().use { it.write(ByteArray(0)) }
        }
        return getDocIdForFile(target)
    }

    override fun deleteDocument(documentId: String?) {
        if (!shouldInit) return
        // 删的是这条目录项本身（可能是符号链接），所以用 leaf 解析：末段不跟随
        val file = resolveLeafExisting(documentId.orEmpty())
        // 与**规范化**的 base 比：resolve 返回 canonicalFile，而 dataDir 可能是
        // /data/user/0/<pkg>（软链到 /data/data/<pkg>），直接比对象会让这道防护失效
        val base = context?.dataDir?.canonicalFile
        if (base != null && file.path == base.path) throw FileNotFoundException("Cannot delete root")
        if (!deleteTree(file)) throw FileNotFoundException("Cannot delete $documentId")
    }

    override fun renameDocument(documentId: String?, displayName: String?): String? {
        if (!shouldInit) return null
        val file = resolveLeafExisting(documentId.orEmpty())
        val parent = file.parentFile ?: throw FileNotFoundException("No parent")
        val name = safeName(displayName) ?: file.name
        val target = File(parent, name)
        if (!file.renameTo(target)) throw FileNotFoundException("Cannot rename")
        return getDocIdForFile(target)
    }

    override fun moveDocument(
        sourceDocumentId: String?,
        sourceParentDocumentId: String?,
        targetParentDocumentId: String?,
    ): String? {
        if (!shouldInit) return null
        val src = resolveLeafExisting(sourceDocumentId.orEmpty())
        val targetParent = resolveDirExisting(targetParentDocumentId.orEmpty())
        if (!targetParent.isDirectory) throw FileNotFoundException("Target is not a directory")
        val dest = File(targetParent, src.name)
        if (!src.renameTo(dest)) throw FileNotFoundException("Cannot move")
        return getDocIdForFile(dest)
    }

    /**
     * MT 管理器的扩展方法（`mt:` 前缀）。
     *
     * MT 管理器靠这三个 `call()` 方法做「修改权限 / 修改时间 / 创建软链接」；
     * 不实现的话它只能浏览和增删改名。方法名与参数是 MT 定下的线上约定
     * （见 mt2.cn「注入文件提供器」），照实现即可，返回值放
     * `result: Boolean`，失败再带 `message: String`。
     *
     * 注意 [DocumentsProvider.call] 只认 `android:` 前缀，别的一律转给
     * `ContentProvider.call`（返回 null），所以必须先调 super、拿到 null 再自己处理。
     *
     * 安全性：`call()` 本身**不做** tree URI 校验（`ContentProvider.Transport.call`
     * 里没有任何 URI 权限检查），所以路径边界完全由 [resolveLeaf] 负责 ——
     * 一切操作都被限制在本应用 dataDir 内。
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val handled = super.call(method, arg, extras)
        if (handled != null) return handled
        if (!method.startsWith("mt:")) return null
        if (!shouldInit) return failure("Not available in this process")
        val args = extras ?: return failure("Missing extras")
        val out = Bundle()
        try {
            val uri = uriExtra(args) ?: return failure("Missing uri")
            val segments = uri.pathSegments
            // content://<auth>/document/<id>             → [document, id]
            // content://<auth>/tree/<tree>/document/<id>  → [tree, tree, document, id]
            val documentId = when {
                segments.size >= 4 -> segments[3]
                segments.size >= 2 -> segments[1]
                else -> return failure("Unsupported uri: $uri")
            }
            when (method) {
                METHOD_SET_LAST_MODIFIED -> {
                    val file = resolveLeafExisting(documentId)
                    out.putBoolean(KEY_RESULT, file.setLastModified(args.getLong("time")))
                }
                METHOD_SET_PERMISSIONS -> {
                    val file = resolveLeafExisting(documentId)
                    Os.chmod(file.path, args.getInt("permissions"))
                    out.putBoolean(KEY_RESULT, true)
                }
                METHOD_CREATE_SYMLINK -> {
                    // 链接**位置**必须在 dataDir 内（不检查存在性，它还没建）；
                    // 链接内容是容器视角的路径，不做限制 —— rootfs 里到处都是绝对链接
                    val file = resolveLeaf(documentId)
                    val target = args.getString("path") ?: return failure("Missing path")
                    Os.symlink(target, file.path)
                    out.putBoolean(KEY_RESULT, true)
                }
                else -> return failure("Unsupported method: $method")
            }
        } catch (e: ErrnoException) {
            return failure(e.message ?: e.toString())
        } catch (e: Exception) {
            Log.w(TAG, "call($method) failed", e)
            return failure(e.toString())
        }
        return out
    }

    // ────────────────────────── helpers ──────────────────────────

    /**
     * documentId → dataDir 下的路径，**只做词法拼接与词法越界检查**，不碰文件系统。
     *
     * 用途只有一个：回答「这个 id 属不属于这棵树」（[isChildDocument]、以及
     * [resolveLeaf] 的父目录部分）。挡的是 `..` 与绝对路径拼接。
     */
    private fun resolveLexical(documentId: String): File {
        val ctx = context ?: throw FileNotFoundException("Provider not ready")
        val base = ctx.dataDir.canonicalFile
        // id 形如 "/" 或 "/files/rootfs"；去掉前导斜杠才能当相对路径拼
        val rel = documentId.trim('/')
        val target = if (rel.isEmpty()) base else File(base, rel)
        // File 构造不会规约 ".."，用 normalize 把它算掉再比，否则 "/../x" 能溜出去
        val lex = File(target.path).normalize()
        val basePath = base.path
        if (lex.path != basePath && !lex.path.startsWith(basePath + File.separator)) {
            throw FileNotFoundException("Path escapes data dir: $documentId")
        }
        return lex
    }

    /**
     * documentId → 真实文件，**跟随符号链接**后仍须落在 dataDir 内。
     *
     * 读写字节、列目录用这个：容器里一个指向 `/system` 的链接不能变成逃逸通道。
     * 不检查存在性。
     */
    private fun resolve(documentId: String): File {
        val ctx = context ?: throw FileNotFoundException("Provider not ready")
        val base = ctx.dataDir.canonicalFile
        val lex = resolveLexical(documentId)
        val canon = lex.canonicalFile
        val basePath = base.path
        if (canon.path != basePath && !canon.path.startsWith(basePath + File.separator)) {
            throw FileNotFoundException("Path escapes data dir: $documentId")
        }
        return canon
    }

    /**
     * documentId → 目录项本身：**父目录跟随链接做边界校验**，但返回的路径保持词法形状。
     *
     * 两件事都必须做到：
     * - 安全：整条路径的目录部分 canonicalize 后仍须在 dataDir 内，否则一个指向
     *   `/system` 的目录链接就是逃逸通道。
     * - documentId 可往返：返回值若换成 canonical 路径，`/files/rootfs/bin/awk`
     *   会变成 `/files/rootfs/usr/bin/awk`（容器里 `bin -> usr/bin`），
     *   再经 [isChildDocument] 与 tree URI 的 `enforceTree` 一比就不是后代了，
     *   于是从 tree URI 进去浏览 `bin/` 直接 `SecurityException`。
     *
     * 末段本身不跟随链接：查询 / 删除 / 改名 / chmod / 改时间 / 建链接都作用在目录项上，
     * 先 canonicalize 末段的话，删一个链接会变成删它指向的东西。中间各段由内核解析，
     * 指向同一个目标，而边界已在上面校验过。
     */
    private fun resolveLeaf(documentId: String): File {
        val ctx = context ?: throw FileNotFoundException("Provider not ready")
        val base = ctx.dataDir.canonicalFile
        val lex = resolveLexical(documentId)
        // 根文档没有「末段」，它本身就是 base
        if (lex.path == base.path) return base
        val parent = lex.parentFile ?: return base
        requireWithinBase(parent, base)
        return lex
    }

    /** 目录 canonicalize 后仍须在 dataDir 内，否则抛 [FileNotFoundException]。 */
    private fun requireWithinBase(dir: File, base: File): File {
        val canon = dir.canonicalFile
        if (canon.path != base.path && !canon.path.startsWith(base.path + File.separator)) {
            throw FileNotFoundException("Path escapes data dir: ${dir.path}")
        }
        return canon
    }

    /** [resolve] + 存在性检查（跟随链接）。打开文件字节、创建的父目录用这个。 */
    private fun resolveExisting(documentId: String): File {
        val file = resolve(documentId)
        if (!file.exists()) throw FileNotFoundException("No such document: $documentId")
        return file
    }

    /**
     * 待列举 / 待写入的目录：**边界按 canonical 校验**（含末段，因为这些操作本就要跟随它），
     * 但返回**词法**路径。
     *
     * 返回词法路径是为了让派生出的 documentId 保持在用户点进来的那条路径下：
     * 若返回 canonical，点开 `bin/`（容器里 `bin -> usr/bin`）列出的子项 id 会变成
     * `…/rootfs/usr/bin/…`。当 tree URI 的根正好是 `…/rootfs/bin` 时，这些 id 便不再是
     * 树根的后代，`enforceTree` 会直接 `SecurityException`。新建、移动的目标目录同理。
     */
    private fun resolveDirExisting(documentId: String): File {
        val ctx = context ?: throw FileNotFoundException("Provider not ready")
        val base = ctx.dataDir.canonicalFile
        val lex = resolveLexical(documentId)
        requireWithinBase(lex, base)
        if (!lex.exists()) throw FileNotFoundException("No such document: $documentId")
        return lex
    }

    /**
     * [resolveLeaf] + `lstat` 存在性检查（不跟随末段）。
     *
     * 查询单个文档、删除、改名、移动、chmod、改时间都用这个：符号链接自己也必须
     * 能被描述和操作，否则列表里看得见、点开就报错。
     */
    private fun resolveLeafExisting(documentId: String): File {
        val file = resolveLeaf(documentId)
        if (!lexists(file)) throw FileNotFoundException("No such document: $documentId")
        return file
    }

    /**
     * 文件 → documentId，形如 `/files/rootfs`；base 自身是 [ROOT_DOC_ID]。
     *
     * 用 `absolutePath` 而**不是** `canonicalPath` 算相对部分：rootfs 里有大量符号链接
     * （`etc/mtab -> /proc/mounts` 之类），canonicalize 会把 id 变成外部路径，
     * 再经 [resolveLexical] 就成了越界、点开即报错。
     */
    private fun getDocIdForFile(file: File): String {
        val ctx = context ?: return ROOT_DOC_ID
        val base = ctx.dataDir.canonicalFile.path
        val path = file.absolutePath
        if (path == base) return ROOT_DOC_ID
        return ROOT_DOC_ID + path.removePrefix(base + File.separator)
    }

    /**
     * `lstat` 存在性：**不跟随**符号链接。
     *
     * `File.exists()` 会跟随链接，于是指向容器外（`etc/mtab -> /proc/mounts`）或
     * 指向缺失目标的链接一律「不存在」，那一行就此消失或点不开。容器 rootfs 里
     * 这种链接有上千个（195 个指向绝对路径），所以存在性判断必须用 lstat。
     */
    private fun lexists(file: File): Boolean = runCatching { Os.lstat(file.path) }.isSuccess

    /** `lstat`，失败返回 null。 */
    private fun lstatOrNull(file: File): StructStat? = runCatching { Os.lstat(file.path) }.getOrNull()

    /**
     * 递归删除，**不跟随**符号链接。
     *
     * `File.deleteRecursively()` 用 `isDirectory` 判断（跟随链接），一个指向目录的
     * 链接会让它去删链接目标里的内容。这里先 lstat：是链接就只删这条目录项。
     */
    private fun deleteTree(file: File): Boolean {
        val stat = lstatOrNull(file)
        val isLink = stat != null && OsConstants.S_ISLNK(stat.st_mode)
        if (!isLink && stat != null && OsConstants.S_ISDIR(stat.st_mode)) {
            file.listFiles()?.forEach { child ->
                if (!deleteTree(child)) return false
            }
        }
        return file.delete()
    }

    /** 拒掉带路径分隔符的名字：新建/改名只允许在当前目录里产生一个条目。 */
    private fun safeName(displayName: String?): String? {
        val name = displayName?.takeIf { it.isNotBlank() } ?: return null
        if (name == "." || name == ".." || name.contains('/') || name.contains('\u0000')) {
            throw FileNotFoundException("Invalid name: $name")
        }
        return name
    }

    private fun failure(message: String): Bundle = Bundle().apply {
        putBoolean(KEY_RESULT, false)
        putString(KEY_MESSAGE, message)
    }

    @Suppress("DEPRECATION")
    private fun uriExtra(extras: Bundle): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelable("uri", Uri::class.java)
        } else {
            extras.getParcelable("uri") as? Uri
        }

    private fun documentCursor(file: File, projection: Array<out String>): Cursor {
        val cursor = MatrixCursor(projection)
        addDocumentRow(cursor, file)
        return cursor
    }

    private fun addDocumentRow(cursor: MatrixCursor, file: File) {
        val row = cursor.newRow()
        // 类型判断走 lstat：符号链接不跟随，否则一个指向目录的链接会被当成目录、
        // 点进去列的是链接目标的内容；指向容器外或缺失目标的链接则整行判为「不存在」
        val stat = lstatOrNull(file)
        val isLink = stat != null && OsConstants.S_ISLNK(stat.st_mode)
        val isDir = if (stat != null) OsConstants.S_ISDIR(stat.st_mode) else file.isDirectory
        val docId = getDocIdForFile(file)
        // 只声明真正实现了的能力：copyDocument / querySearchDocuments 没实现，
        // 所以绝不声明 FLAG_SUPPORTS_COPY / FLAG_DIR_SUPPORTS_SEARCH —— 声明了就是骗客户端。
        // 目录原来 flags 恒为 0，导致「新建文件夹」在任何目录里都不可用，与 root 声明的
        // FLAG_SUPPORTS_CREATE 自相矛盾。
        var flags = if (isDir) {
            DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
        } else {
            DocumentsContract.Document.FLAG_SUPPORTS_WRITE
        }
        // 根文档不给删除/改名/移动：删了它就是清掉整个应用数据
        if (docId != ROOT_DOC_ID) {
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                DocumentsContract.Document.FLAG_SUPPORTS_RENAME or
                DocumentsContract.Document.FLAG_SUPPORTS_MOVE
        }
        for (col in cursor.columnNames) {
            when (col) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> row.add(docId)
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> row.add(file.name.ifEmpty { "DSH-Folk" })
                DocumentsContract.Document.COLUMN_MIME_TYPE -> row.add(mimeTypeFor(file, isDir))
                DocumentsContract.Document.COLUMN_SIZE ->
                    row.add(if (isDir) null else stat?.st_size ?: file.length())
                // 用 lstat 的 mtime：File.lastModified() 跟随链接，悬空链接会返回 0
                DocumentsContract.Document.COLUMN_LAST_MODIFIED ->
                    row.add(stat?.let { it.st_mtime * 1000L } ?: file.lastModified())
                DocumentsContract.Document.COLUMN_FLAGS -> row.add(flags)
                // MT 管理器的扩展列（别的客户端不请求就不会出现在 projection 里）：
                // mt_path = 宿主绝对路径；mt_extras = st_mode|st_uid|st_gid[|链接目标]
                COLUMN_MT_PATH -> row.add(file.absolutePath)
                COLUMN_MT_EXTRAS -> row.add(mtExtras(file, stat, isLink))
                else -> row.add(null)
            }
        }
    }

    /** MT 的 `mt_extras`：`st_mode|st_uid|st_gid`，是链接再追加 `|目标`。 */
    private fun mtExtras(file: File, stat: StructStat?, isLink: Boolean): String? {
        if (stat == null) return null
        val sb = StringBuilder()
            .append(stat.st_mode).append('|')
            .append(stat.st_uid).append('|')
            .append(stat.st_gid)
        if (isLink) {
            runCatching { Os.readlink(file.path) }.getOrNull()?.let { sb.append('|').append(it) }
        }
        return sb.toString()
    }

    private fun mimeTypeFor(file: File, isDir: Boolean): String =
        if (isDir) DocumentsContract.Document.MIME_TYPE_DIR
        else MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"

    private fun getProcessNameCompat(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName()
        }
        val ctx = context ?: return ""
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return ""
        return am.runningAppProcesses?.find { it.pid == Process.myPid() }?.processName
            ?: ctx.packageName
    }

    private companion object {
        private const val TAG = "DshDocs"

        /**
         * 根文档的 documentId。
         *
         * **必须非空**，否则 [DocumentsProvider] 内建的 `UriMatcher` 会因为空路径段被
         * `Uri.getPathSegments()` 丢弃而全部错位（见类 KDoc 的对照表）。取 `"/"` 让
         * 所有 id 都是一个绝对路径形状；`Uri.Builder.appendPath` 会把它编码成 `%2F`，
         * 所以含斜杠的 id 在 URI 里仍是单个路径段。
         */
        const val ROOT_DOC_ID = "/"

        val EXCLUDED_TOP = setOf("cache", "code_cache", "no_backup")

        // ── MT 管理器约定的扩展列与方法名（mt2.cn「注入文件提供器」）──
        const val COLUMN_MT_EXTRAS = "mt_extras"
        const val COLUMN_MT_PATH = "mt_path"
        const val METHOD_SET_LAST_MODIFIED = "mt:setLastModified"
        const val METHOD_SET_PERMISSIONS = "mt:setPermissions"
        const val METHOD_CREATE_SYMLINK = "mt:createSymlink"
        const val KEY_RESULT = "result"
        const val KEY_MESSAGE = "message"

        val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
            DocumentsContract.Root.COLUMN_CAPACITY_BYTES,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_ICON,
        )

        val DEFAULT_DOC_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            COLUMN_MT_EXTRAS,
            COLUMN_MT_PATH,
        )
    }
}
