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
        val row = MatrixCursor.RowBuilder(cursor)
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
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "")
        row.add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, stats.availableBytes)
        row.add(DocumentsContract.Root.COLUMN_CAPACITY_BYTES, stats.totalBytes)
        row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
        return cursor
    }

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        if (!shouldInit) return MatrixCursor(projection ?: DEFAULT_DOC_PROJECTION)
        val file = resolve(documentId.orEmpty())
        return documentCursor(file, projection ?: DEFAULT_DOC_PROJECTION)
    }

    override fun queryChildDocuments(
        parentDocumentId: String?,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        if (!shouldInit) return MatrixCursor(projection ?: DEFAULT_DOC_PROJECTION)
        val parent = resolve(parentDocumentId.orEmpty())
        val cursor = MatrixCursor(projection ?: DEFAULT_DOC_PROJECTION)
        val root = context!!.dataDir.canonicalFile
        val children = parent.listFiles()
            ?.filter { f ->
                // 只在根层排除会被系统随时回收的目录
                parent == root && f.name !in EXCLUDED_TOP
            }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: emptyList()
        for (child in children) {
            addDocumentRow(cursor, child)
        }
        return cursor
    }

    override fun openDocument(
        documentId: String?,
        mode: String?,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (!shouldInit) throw FileNotFoundException("Not available in this process")
        val file = resolve(documentId.orEmpty())
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
        val parent = resolve(parentDocumentId.orEmpty())
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
        val file = resolve(documentId.orEmpty())
        if (file == context?.dataDir) throw FileNotFoundException("Cannot delete root")
        val ok = file.deleteRecursively()
        if (!ok) throw FileNotFoundException("Cannot delete $documentId")
    }

    override fun renameDocument(documentId: String?, displayName: String?): String? {
        if (!shouldInit) return null
        val file = resolve(documentId.orEmpty())
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
        val src = resolve(sourceDocumentId.orEmpty())
        val targetParent = resolve(targetParentDocumentId.orEmpty())
        if (!targetParent.isDirectory) throw FileNotFoundException("Target is not a directory")
        val dest = File(targetParent, src.name)
        if (!src.renameTo(dest)) throw FileNotFoundException("Cannot move")
        return getDocIdForFile(dest)
    }

    // ────────────────────────── helpers ──────────────────────────

    /**
     * documentId → dataDir 下的真实文件。越界抛 [FileNotFoundException]，这是唯一边界。
     */
    private fun resolve(documentId: String): File {
        val ctx = context ?: throw FileNotFoundException("Provider not ready")
        val base = ctx.dataDir.canonicalFile
        val target = if (documentId.isEmpty()) base else File(base, documentId)
        val canon = target.canonicalFile
        val basePath = base.path
        if (canon.path != basePath && !canon.path.startsWith(basePath + File.separator)) {
            throw FileNotFoundException("Path escapes data dir: $documentId")
        }
        return canon
    }

    private fun getDocIdForFile(file: File): String {
        val ctx = context ?: return file.name
        val base = ctx.dataDir.canonicalFile.path
        val canon = file.canonicalPath
        return if (canon == base) "" else canon.removePrefix(base + File.separator)
    }

    private fun documentCursor(file: File, projection: Array<out String>): Cursor {
        val cursor = MatrixCursor(projection)
        addDocumentRow(cursor, file)
        return cursor
    }

    private fun addDocumentRow(cursor: MatrixCursor, file: File) {
        val row = MatrixCursor.RowBuilder(cursor)
        val isDir = file.isDirectory
        val flags = if (isDir) 0 else DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
            DocumentsContract.Document.FLAG_SUPPORTS_RENAME or
            DocumentsContract.Document.FLAG_SUPPORTS_WRITE
        for (col in cursor.columnNames) {
            when (col) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> row.add(getDocIdForFile(file))
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
