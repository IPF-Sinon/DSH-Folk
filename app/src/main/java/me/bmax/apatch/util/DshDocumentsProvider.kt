package me.bmax.apatch.util

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
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
 * [resolve] 是本 provider 唯一的信任边界：documentId 是外部输入（不可信），拼接进
 * `dataDir` 后必须 `canonicalPath` 仍以 `dataDir` 开头，否则抛 [FileNotFoundException]。
 * 所有读写入口都经过它。rootfs 里有大量符号链接，`canonicalPath` 会把指向外部的链接
 * 挡掉 —— 这是想要的行为（防止容器里一个指向 `/system` 的链接被用来逃逸）。
 *
 * ## 排除项
 *
 * `cache/`、`code_cache/`、`no_backup/` 不列出：噪音大且随时被系统回收。
 * `rootfs/` 正常列出 —— 这正是用户要浏览的东西（`rootfs/root/.dsh` 含 sessions、profiles）。
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
        return cursor
    }

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        if (!shouldInit) return MatrixCursor(projection ?: DEFAULT_DOC_PROJECTION)
        val file = resolveExisting(documentId.orEmpty())
        return documentCursor(file, projection ?: DEFAULT_DOC_PROJECTION)
    }

    override fun queryChildDocuments(
        parentDocumentId: String?,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        if (!shouldInit) return MatrixCursor(projection ?: DEFAULT_DOC_PROJECTION)
        val parent = resolveExisting(parentDocumentId.orEmpty())
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
            ?.filter { f -> withinRoot(f, root) }
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
     * 默认实现恒返回 false，会让整棵树无法浏览 —— 见类 KDoc。语义对齐 AOSP
     * `ExternalStorageProvider`：相等或后代都算 child，任何异常按 false 处理。
     */
    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean =
        runCatching {
            val parent = resolve(parentDocumentId.orEmpty())
            val child = resolve(documentId.orEmpty())
            child.path == parent.path || child.path.startsWith(parent.path + File.separator)
        }.getOrDefault(false)

    override fun openDocument(
        documentId: String?,
        mode: String?,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (!shouldInit) throw FileNotFoundException("Not available in this process")
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
        val parent = resolveExisting(parentDocumentId.orEmpty())
        if (!parent.isDirectory) throw FileNotFoundException("Parent is not a directory")
        val name = displayName?.takeIf { it.isNotBlank() } ?: "untitled"
        val target = File(parent, name)
        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            if (!target.exists() && !target.mkdirs()) throw FileNotFoundException("Cannot create directory")
        } else {
            if (target.exists()) throw FileNotFoundException("Already exists: $name")
            target.outputStream().use { it.write(ByteArray(0)) }
        }
        return getDocIdForFile(target)
    }

    override fun deleteDocument(documentId: String?) {
        if (!shouldInit) return
        val file = resolveExisting(documentId.orEmpty())
        // 与**规范化**的 base 比：resolve 返回 canonicalFile，而 dataDir 可能是
        // /data/user/0/<pkg>（软链到 /data/data/<pkg>），直接比对象会让这道防护失效
        val base = context?.dataDir?.canonicalFile
        if (base != null && file.path == base.path) throw FileNotFoundException("Cannot delete root")
        val ok = file.deleteRecursively()
        if (!ok) throw FileNotFoundException("Cannot delete $documentId")
    }

    override fun renameDocument(documentId: String?, displayName: String?): String? {
        if (!shouldInit) return null
        val file = resolveExisting(documentId.orEmpty())
        val parent = file.parentFile ?: throw FileNotFoundException("No parent")
        val target = File(parent, displayName ?: file.name)
        if (!file.renameTo(target)) throw FileNotFoundException("Cannot rename")
        return getDocIdForFile(target)
    }

    override fun moveDocument(
        sourceDocumentId: String?,
        sourceParentDocumentId: String?,
        targetParentDocumentId: String?,
    ): String? {
        if (!shouldInit) return null
        val src = resolveExisting(sourceDocumentId.orEmpty())
        val targetParent = resolveExisting(targetParentDocumentId.orEmpty())
        if (!targetParent.isDirectory) throw FileNotFoundException("Target is not a directory")
        val dest = File(targetParent, src.name)
        if (!src.renameTo(dest)) throw FileNotFoundException("Cannot move")
        return getDocIdForFile(dest)
    }

    // ────────────────────────── helpers ──────────────────────────

    /**
     * documentId → dataDir 下的真实文件。越界抛 [FileNotFoundException]，这是唯一边界。
     *
     * **不检查存在性**：[isChildDocument] 需要对还不存在的路径也能回答父子关系。
     * 需要「必须存在」的入口用 [resolveExisting]。
     */
    private fun resolve(documentId: String): File {
        val ctx = context ?: throw FileNotFoundException("Provider not ready")
        val base = ctx.dataDir.canonicalFile
        // id 形如 "/" 或 "/files/rootfs"；去掉前导斜杠才能当相对路径拼
        val rel = documentId.trim('/')
        val target = if (rel.isEmpty()) base else File(base, rel)
        val canon = target.canonicalFile
        val basePath = base.path
        if (canon.path != basePath && !canon.path.startsWith(basePath + File.separator)) {
            throw FileNotFoundException("Path escapes data dir: $documentId")
        }
        return canon
    }

    /** [resolve] + 存在性检查。查询/打开/改名/删除/移动都必须用这个。 */
    private fun resolveExisting(documentId: String): File {
        val file = resolve(documentId)
        if (!file.exists()) throw FileNotFoundException("No such document: $documentId")
        return file
    }

    /** 这个文件（按符号链接解析后）是否仍在 dataDir 里。 */
    private fun withinRoot(file: File, root: File): Boolean = runCatching {
        val canon = file.canonicalPath
        canon == root.path || canon.startsWith(root.path + File.separator)
    }.getOrDefault(false)

    /**
     * 文件 → documentId，形如 `/files/rootfs`；base 自身是 [ROOT_DOC_ID]。
     *
     * 用 `absolutePath` 而**不是** `canonicalPath` 算相对部分：rootfs 里有大量符号链接
     * （`etc/mtab -> /proc/self/mounts` 之类），canonicalize 会把 id 变成外部路径，
     * 再经 [resolve] 就成了越界、点开即报错。链接指向哪里由 [withinRoot] 单独把关。
     */
    private fun getDocIdForFile(file: File): String {
        val ctx = context ?: return ROOT_DOC_ID
        val base = ctx.dataDir.canonicalFile.path
        val path = file.absolutePath
        if (path == base) return ROOT_DOC_ID
        return ROOT_DOC_ID + path.removePrefix(base + File.separator)
    }

    private fun documentCursor(file: File, projection: Array<out String>): Cursor {
        val cursor = MatrixCursor(projection)
        addDocumentRow(cursor, file)
        return cursor
    }

    private fun addDocumentRow(cursor: MatrixCursor, file: File) {
        val row = cursor.newRow()
        val isDir = file.isDirectory
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
                DocumentsContract.Document.COLUMN_MIME_TYPE -> row.add(mimeTypeFor(file))
                DocumentsContract.Document.COLUMN_SIZE -> row.add(if (isDir) null else file.length())
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> row.add(file.lastModified())
                DocumentsContract.Document.COLUMN_FLAGS -> row.add(flags)
                else -> row.add(null)
            }
        }
    }

    private fun mimeTypeFor(file: File): String =
        if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR
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

        val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
            DocumentsContract.Root.COLUMN_CAPACITY_BYTES,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
        )

        val DEFAULT_DOC_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }
}
