package me.bmax.apatch.dsh

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.util.Log
import me.bmax.apatch.R
import me.bmax.apatch.util.appString
import me.bmax.apatch.util.PermissionUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * SAF 桥路由（saf 端点族）：挂不上的那一类提供器（Termux…）经宿主 App 用 ContentResolver /
 * DocumentsContract 代办。
 *
 * 从 DshFsBridge.kt 整块搬出（交接规范阶段 4）：原先它与 /fs 路由同处一个 1387 行的文件，
 * 边界清楚（只碰 Uri/Cursor/DocumentsContract），是**真正可搬**的部分。
 *
 * 写成 DshFsBridge 的扩展函数：宿主的私有助手（appCtx / str / errorJson / parseQuery 等）
 * 不跨文件可见，所以需要的那几个改成 internal（仓库内外部调用为 0），调用点写法零改动。
 */

    /**
     * SAF 桥分发（`/saf/…`）。
     *
     * 挂不上真实路径、但有持久化授权的那棵树（典型 Termux），经宿主 App 用 ContentResolver /
     * DocumentsContract 代办简单文件操作。路径解析**逐段**在树里按 display name 下行成
     * documentId（SAF 没有真实路径，只能逐段查子文档），拒 `..` / 空段 / 绝对路径 / 反斜杠。
     *
     * 准入判定一律交给 [DshNativeBridge.safGate]（读 App 侧 prefs 的 [DshNativeBridge.Cap.SAF]
     * 档位，**不信任容器**）：读端点要读档、写端点要读写档、默认关。它和 `/native/…` 的 gate
     * 落在同一个 cap 系统里，只是端点挂在这个 socket 上。
     */
internal fun DshFsBridge.dispatchSaf(
        method: String,
        path: String,
        params: Map<String, String>,
        input: InputStream,
        headers: Map<String, String>,
        out: OutputStream,
    ): Pair<Int, String>? {
        val ctx = appCtx ?: return 500 to errorJson(str(R.string.dsh_fs_err_native_uninit), "native_uninit")
        // 权限判定在服务端、落在 Cap.SAF —— 和 /native/… 一样不信容器。
        DshNativeBridge.safGate(ctx, method, path)?.let { denial ->
            DshNativeBridge.safAudit(ctx, method, path, params, denial)
            return denial
        }
        val result: Pair<Int, String>? = when {
            method == "GET" && path == "/saf/sources" -> safSources(ctx)
            method == "GET" && path == "/saf/list" -> safList(ctx, params)
            method == "GET" && path == "/saf/stat" -> safStat(ctx, params)
            method == "GET" && path == "/saf/read" -> safRead(ctx, params, out)
            method == "PUT" && path == "/saf/write" -> safWrite(ctx, params, input, headers)
            method == "POST" && path == "/saf/mkdir" -> safMkdir(ctx, params)
            method == "POST" && path == "/saf/move" -> safMove(ctx, params)
            method == "POST" && path == "/saf/copy" -> safCopy(ctx, params)
            method == "DELETE" && path == "/saf/delete" -> safDelete(ctx, params)
            else -> 404 to errorJson(str(R.string.dsh_fs_err_unknown_endpoint, method, path), "unknown_endpoint")
        }
        // read 自己写出二进制流（result==null）时不记录响应体，记一条成功的占位，和别的端点
        // 一样进 native-capability.jsonl。
        DshNativeBridge.safAudit(ctx, method, path, params, result ?: (200 to "{\"ok\":true,\"stream\":true}"))
        return result
    }

    /** SAF 来源名 → tree URI 的解析入口；找不到就回明确错误。 */
internal fun DshFsBridge.safSource(ctx: Context, params: Map<String, String>): DshSafSources.Source? {
        val name = params["src"]?.takeIf { it.isNotBlank() } ?: return null
        return DshSafSources.find(ctx, name)
    }

    /**
     * SAF 相对路径逐段校验 → 段列表；非法返回 null。
     *
     * 与 `/fs/…` 的 [resolve] 同一套纪律：拒反斜杠、盘符、控制字符、`.`/`..`/空段、绝对路径。
     * SAF 没有真实路径，所以这里只产出"干净的段"，真正下行解析成 documentId 由
     * [resolveSafDoc] 逐段查子文档完成。
     */
internal fun DshFsBridge.safSegments(rel: String?): List<String>? {
        val raw = rel ?: ""
        if (raw.contains('\\')) return null
        if (raw.length >= 2 && raw[1] == ':') return null
        if (raw.any { it.code < 0x20 }) return null
        val trimmed = raw.trim('/')
        if (trimmed.isEmpty() || trimmed == ".") return emptyList()
        val parts = trimmed.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return parts
    }

    /** 一个 SAF 文档节点：documentId + display name + mime + 大小 + mtime。 */
    // internal 而不是 private：本文件里那些 internal 扩展函数以它为返回/参数类型，
    // private-in-file 的类型不能让 internal 函数暴露（Kotlin 会直接报错）。
    internal data class SafDoc(
        val docId: String,
        val name: String,
        val mime: String,
        val size: Long,
        val mtime: Long,
    ) {
        val isDir: Boolean get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    }

    /**
     * 从树根逐段下行解析到目标文档。
     *
     * SAF 不提供"按路径取文档"，只能从 tree document id 开始，逐段用
     * [DocumentsContract.buildChildDocumentsUriUsingTree] 列子文档、按 display name 精确匹配，
     * 匹配到的子文档 id 作为下一段的父。任意一段匹配不到即返回 null（not_found）。
     *
     * [segments] 为空 = 树根本身。
     */
internal fun DshFsBridge.resolveSafDoc(ctx: Context, src: DshSafSources.Source, segments: List<String>): SafDoc? {
        val treeId = src.treeDocId ?: return null
        var currentId = treeId
        for ((i, seg) in segments.withIndex()) {
            val child = childByName(ctx, src.uri, currentId, seg) ?: return null
            // 中间段必须是目录，否则路径不成立（在文件里再下行是错的）
            if (i < segments.size - 1 && !child.isDir) return null
            currentId = child.docId
        }
        return statSafDoc(ctx, src.uri, currentId)
    }

    /** 列 [parentId] 下 display name == [name] 的子文档（精确匹配）。 */
internal fun DshFsBridge.childByName(ctx: Context, tree: Uri, parentId: String, name: String): SafDoc? {
        for (c in listChildren(ctx, tree, parentId)) {
            if (c.name == name) return c
        }
        return null
    }

    /** 列一个目录文档的全部直接子文档。 */
internal fun DshFsBridge.listChildren(ctx: Context, tree: Uri, parentId: String): List<SafDoc> {
        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<SafDoc>()
        runCatching {
            ctx.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null, null, null,
            )?.use { c -> while (c.moveToNext()) readSafRow(c)?.let { out.add(it) } }
        }
        return out
    }

    /** 取单个文档的元数据（documentId 已知）。 */
internal fun DshFsBridge.statSafDoc(ctx: Context, tree: Uri, docId: String): SafDoc? {
        val docUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        }.getOrNull() ?: return null
        return runCatching {
            ctx.contentResolver.query(
                docUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null, null, null,
            )?.use { c -> if (c.moveToFirst()) readSafRow(c, fallbackId = docId) else null }
        }.getOrNull()
    }

internal fun DshFsBridge.readSafRow(c: Cursor, fallbackId: String? = null): SafDoc? {
        val id = runCatching { c.getString(0) }.getOrNull() ?: fallbackId ?: return null
        val name = runCatching { c.getString(1) }.getOrNull().orEmpty()
        val mime = runCatching { c.getString(2) }.getOrNull().orEmpty()
        val size = runCatching { if (c.isNull(3)) 0L else c.getLong(3) }.getOrDefault(0L)
        val mtime = runCatching { if (c.isNull(4)) 0L else c.getLong(4) }.getOrDefault(0L)
        return SafDoc(id, name, mime, size, mtime)
    }

internal fun DshFsBridge.safDocJson(doc: SafDoc, path: String): JSONObject = JSONObject()
        .put("name", doc.name)
        .put("path", path)
        .put("dir", doc.isDir)
        .put("size", doc.size)
        .put("mime", doc.mime)
        .put("mtime", doc.mtime)

internal fun DshFsBridge.safNotFound(): Pair<Int, String> =
        404 to errorJson(str(R.string.dsh_fs_err_not_found), "not_found")

internal fun DshFsBridge.safBadSource(): Pair<Int, String> =
        404 to errorJson(str(R.string.dsh_saf_err_unknown_source), "unknown_source")

internal fun DshFsBridge.safBadPath(): Pair<Int, String> =
        400 to errorJson(str(R.string.dsh_fs_err_bad_path), "bad_path")

internal fun DshFsBridge.safSources(ctx: Context): Pair<Int, String> {
        val arr = JSONArray()
        for (s in DshSafSources.sources(ctx)) {
            arr.put(
                JSONObject()
                    .put("name", s.name)
                    .put("treeDocId", s.treeDocId ?: JSONObject.NULL)
                    .put("authority", s.authority ?: JSONObject.NULL),
            )
        }
        return 200 to JSONObject().put("ok", true).put("sources", arr).toString()
    }

internal fun DshFsBridge.safList(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val src = safSource(ctx, params) ?: return safBadSource()
        val segs = safSegments(params["path"]) ?: return safBadPath()
        val doc = resolveSafDoc(ctx, src, segs) ?: return safNotFound()
        val base = segs.joinToString("/")
        val entries = JSONArray()
        if (!doc.isDir) {
            entries.put(safDocJson(doc, base))
            return 200 to JSONObject().put("ok", true).put("entries", entries)
                .put("truncated", false).toString()
        }
        val limit = clampInt(params["limit"], DEFAULT_LIST_LIMIT, 1, MAX_LIST_LIMIT)
        val children = listChildren(ctx, src.uri, doc.docId)
            .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        var truncated = false
        for (child in children) {
            if (entries.length() >= limit) {
                truncated = true
                break
            }
            val childPath = if (base.isEmpty()) child.name else "$base/${child.name}"
            entries.put(safDocJson(child, childPath))
        }
        return 200 to JSONObject().put("ok", true).put("entries", entries)
            .put("truncated", truncated).toString()
    }

internal fun DshFsBridge.safStat(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val src = safSource(ctx, params) ?: return safBadSource()
        val segs = safSegments(params["path"]) ?: return safBadPath()
        val doc = resolveSafDoc(ctx, src, segs) ?: return safNotFound()
        return 200 to JSONObject().put("ok", true)
            .put("entry", safDocJson(doc, segs.joinToString("/"))).toString()
    }

internal fun DshFsBridge.safRead(ctx: Context, params: Map<String, String>, out: OutputStream): Pair<Int, String>? {
        val src = safSource(ctx, params) ?: return safBadSource()
        val segs = safSegments(params["path"]) ?: return safBadPath()
        val doc = resolveSafDoc(ctx, src, segs) ?: return safNotFound()
        if (doc.isDir) return 400 to errorJson(str(R.string.dsh_fs_err_is_dir), "is_dir")
        val offset = params["offset"]?.let {
            it.toLongOrNull() ?: return 400 to errorJson(str(R.string.dsh_fs_err_not_int, "offset"), "not_int")
        } ?: 0L
        if (offset < 0) return 400 to errorJson(str(R.string.dsh_fs_err_negative, "offset"), "negative")
        val requested = params["length"]?.let {
            it.toLongOrNull() ?: return 400 to errorJson(str(R.string.dsh_fs_err_not_int, "length"), "not_int")
        }
        if (requested != null && requested < 0) return 400 to errorJson(str(R.string.dsh_fs_err_negative, "length"), "negative")
        if (requested != null && requested > MAX_READ_BYTES) {
            return 400 to errorJson(str(R.string.dsh_fs_err_read_too_big, requested), "read_too_big")
        }
        val docUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(src.uri, doc.docId)
        }.getOrNull() ?: return safNotFound()
        val stream = runCatching { ctx.contentResolver.openInputStream(docUri) }.getOrNull()
            ?: return 500 to errorJson(str(R.string.dsh_fs_err_open_failed, ""), "open_failed")
        stream.use { ins ->
            // SAF 流不保证可随机定位：offset 用 skip 跳过（提供器大多实现了 skip，没有也退化成读弃）。
            var toSkip = offset
            while (toSkip > 0) {
                val skipped = ins.skip(toSkip)
                if (skipped <= 0) break
                toSkip -= skipped
            }
            // 头一旦写出就改不回 JSON 错误；SAF 的 size 可能不准，所以用 chunked，不写 Content-Length。
            runCatching {
                val head = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/octet-stream\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "Connection: close\r\n\r\n"
                out.write(head.toByteArray(StandardCharsets.US_ASCII))
                val buf = ByteArray(64 * 1024)
                var remaining = requested ?: Long.MAX_VALUE
                while (remaining > 0) {
                    val want = minOf(buf.size.toLong(), remaining).toInt()
                    val n = ins.read(buf, 0, want)
                    if (n < 0) break
                    if (n > 0) {
                        out.write(Integer.toHexString(n).toByteArray(StandardCharsets.US_ASCII))
                        out.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
                        out.write(buf, 0, n)
                        out.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
                        remaining -= n
                    }
                }
                out.write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                out.flush()
            }.onFailure { e -> Log.w(TAG, "saf read 传输中断: ${e.message}") }
        }
        return null
    }

internal fun DshFsBridge.safWrite(
        ctx: Context,
        params: Map<String, String>,
        input: InputStream,
        headers: Map<String, String>,
    ): Pair<Int, String> {
        val src = safSource(ctx, params) ?: return safBadSource()
        val segs = safSegments(params["path"]) ?: return safBadPath()
        if (segs.isEmpty()) return safBadPath()
        val len = headers["content-length"]?.toLongOrNull()
            ?: return 400 to errorJson(str(R.string.dsh_fs_err_missing_length), "missing_length")
        if (len < 0) return 400 to errorJson(str(R.string.dsh_fs_err_bad_length), "bad_length")
        val append = boolParam(params["append"])
        // 先把 body 收进内存：SAF 的 openOutputStream 要先有文档，而文档可能需要先 create；
        // 一次性读进来（受 MAX_READ_BYTES 约束）比边收边建简单也更可控。
        if (len > MAX_READ_BYTES) {
            return 400 to errorJson(str(R.string.dsh_fs_err_read_too_big, len), "read_too_big")
        }
        val buf = ByteArrayOutputStream()
        val received = pump(input, buf, len)
        if (received != len) {
            return 400 to errorJson(str(R.string.dsh_fs_err_short_body, len, received), "short_body")
        }
        val data = buf.toByteArray()

        val existing = resolveSafDoc(ctx, src, segs)
        if (existing != null && existing.isDir) {
            return 400 to errorJson(str(R.string.dsh_fs_err_is_dir), "is_dir")
        }
        val docId = existing?.docId ?: run {
            // 不存在：在父目录下 createDocument。父目录必须已存在。
            val parentSegs = segs.dropLast(1)
            val parent = resolveSafDoc(ctx, src, parentSegs) ?: return safNotFound()
            if (!parent.isDir) return 400 to errorJson(str(R.string.dsh_fs_err_not_dir), "not_dir")
            createSafChild(ctx, src.uri, parent.docId, segs.last(), isDir = false)
                ?: return 500 to errorJson(str(R.string.dsh_saf_err_create_failed), "create_failed")
        }
        val docUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(src.uri, docId)
        }.getOrNull() ?: return safNotFound()
        // append 用 "wa"；覆盖用 "wt"（truncate）。有些提供器不支持 "wt"，退回 "w"。
        val mode = if (append) "wa" else "wt"
        val written = runCatching {
            val os = ctx.contentResolver.openOutputStream(docUri, mode)
                ?: ctx.contentResolver.openOutputStream(docUri, if (append) "wa" else "w")
            os?.use { it.write(data); it.flush() }
            os != null
        }.getOrDefault(false)
        if (!written) return 500 to errorJson(str(R.string.dsh_fs_err_write_failed), "write_failed")
        return 200 to JSONObject().put("ok", true).put("path", segs.joinToString("/"))
            .put("append", append).put("bytes", data.size).toString()
    }

internal fun DshFsBridge.safMkdir(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val src = safSource(ctx, params) ?: return safBadSource()
        val segs = safSegments(params["path"]) ?: return safBadPath()
        if (segs.isEmpty()) return safBadPath()
        if (resolveSafDoc(ctx, src, segs) != null) {
            return 400 to errorJson(str(R.string.dsh_fs_err_file_exists), "file_exists")
        }
        val parent = resolveSafDoc(ctx, src, segs.dropLast(1)) ?: return safNotFound()
        if (!parent.isDir) return 400 to errorJson(str(R.string.dsh_fs_err_not_dir), "not_dir")
        val created = createSafChild(ctx, src.uri, parent.docId, segs.last(), isDir = true)
            ?: return 500 to errorJson(str(R.string.dsh_fs_err_mkdir_failed), "mkdir_failed")
        return 200 to JSONObject().put("ok", true).put("docId", created).toString()
    }

internal fun DshFsBridge.safDelete(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val src = safSource(ctx, params) ?: return safBadSource()
        val segs = safSegments(params["path"]) ?: return safBadPath()
        if (segs.isEmpty()) {
            // 不允许删树根：那是用户授权的来源本体，撤销来源走 App 侧的「文件访问范围」页。
            return 400 to errorJson(str(R.string.dsh_fs_err_no_delete_root), "no_delete_root")
        }
        val doc = resolveSafDoc(ctx, src, segs) ?: return safNotFound()
        val docUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(src.uri, doc.docId)
        }.getOrNull() ?: return safNotFound()
        val ok = runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, docUri) }
            .getOrDefault(false)
        return if (ok) 200 to JSONObject().put("ok", true).toString()
        else 500 to errorJson(str(R.string.dsh_fs_err_delete_failed), "delete_failed")
    }

internal fun DshFsBridge.safMove(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val src = safSource(ctx, params) ?: return safBadSource()
        val fromSegs = safSegments(params["from"]) ?: return safBadPath()
        val toSegs = safSegments(params["to"]) ?: return safBadPath()
        if (fromSegs.isEmpty() || toSegs.isEmpty()) return safBadPath()
        val fromDoc = resolveSafDoc(ctx, src, fromSegs) ?: return safNotFound()
        val fromUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(src.uri, fromDoc.docId)
        }.getOrNull() ?: return safNotFound()
        val sameParent = fromSegs.dropLast(1) == toSegs.dropLast(1)
        // 同一父目录内改名 → renameDocument；跨父目录 → moveDocument（API 24+）。
        if (sameParent && fromSegs.last() != toSegs.last()) {
            val renamed = runCatching {
                DocumentsContract.renameDocument(ctx.contentResolver, fromUri, toSegs.last())
            }.getOrNull()
            return if (renamed != null) 200 to JSONObject().put("ok", true).put("mode", "rename").toString()
            else 500 to errorJson(str(R.string.dsh_saf_err_move_failed), "move_failed")
        }
        // 跨父目录：目标父目录必须存在，且文件名不能同时变（SAF moveDocument 不改名）。
        if (fromSegs.last() != toSegs.last()) {
            return 400 to errorJson(str(R.string.dsh_saf_err_move_rename), "move_rename_unsupported")
        }
        val oldParent = resolveSafDoc(ctx, src, fromSegs.dropLast(1)) ?: return safNotFound()
        val newParent = resolveSafDoc(ctx, src, toSegs.dropLast(1)) ?: return safNotFound()
        if (!newParent.isDir) return 400 to errorJson(str(R.string.dsh_fs_err_not_dir), "not_dir")
        val oldParentUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(src.uri, oldParent.docId)
        }.getOrNull() ?: return safNotFound()
        val newParentUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(src.uri, newParent.docId)
        }.getOrNull() ?: return safNotFound()
        val moved = runCatching {
            DocumentsContract.moveDocument(ctx.contentResolver, fromUri, oldParentUri, newParentUri)
        }.getOrNull()
        return if (moved != null) 200 to JSONObject().put("ok", true).put("mode", "move").toString()
        else 500 to errorJson(str(R.string.dsh_saf_err_move_failed), "move_failed")
    }

internal fun DshFsBridge.safCopy(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val src = safSource(ctx, params) ?: return safBadSource()
        val fromSegs = safSegments(params["from"]) ?: return safBadPath()
        val toSegs = safSegments(params["to"]) ?: return safBadPath()
        if (fromSegs.isEmpty() || toSegs.isEmpty()) return safBadPath()
        // SAF copyDocument 只能复制进一个目标父目录、保留原名，不能顺带改名。要求同名、父目录存在。
        if (fromSegs.last() != toSegs.last()) {
            return 400 to errorJson(str(R.string.dsh_saf_err_copy_rename), "copy_rename_unsupported")
        }
        val fromDoc = resolveSafDoc(ctx, src, fromSegs) ?: return safNotFound()
        val fromUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(src.uri, fromDoc.docId)
        }.getOrNull() ?: return safNotFound()
        val newParent = resolveSafDoc(ctx, src, toSegs.dropLast(1)) ?: return safNotFound()
        if (!newParent.isDir) return 400 to errorJson(str(R.string.dsh_fs_err_not_dir), "not_dir")
        val newParentUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(src.uri, newParent.docId)
        }.getOrNull() ?: return safNotFound()
        val copied = runCatching {
            DocumentsContract.copyDocument(ctx.contentResolver, fromUri, newParentUri)
        }.getOrNull()
        // 不是所有提供器都支持 copyDocument（能力位 FLAG_SUPPORTS_COPY），回明确错误而不是假装成功。
        return if (copied != null) 200 to JSONObject().put("ok", true).toString()
        else 500 to errorJson(str(R.string.dsh_saf_err_copy_unsupported), "copy_unsupported")
    }

    /** 在父目录文档下建一个子文档/子目录；返回新文档 id，失败 null。 */
internal fun DshFsBridge.createSafChild(ctx: Context, tree: Uri, parentId: String, name: String, isDir: Boolean): String? {
        val parentUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        }.getOrNull() ?: return null
        val mime = if (isDir) DocumentsContract.Document.MIME_TYPE_DIR else guessMime(name)
        return runCatching {
            DocumentsContract.createDocument(ctx.contentResolver, parentUri, mime, name)?.let { uri ->
                DocumentsContract.getDocumentId(uri)
            }
        }.getOrNull()
    }

    /** 粗略的 mime 猜测（SAF create 要一个 mime；拿不准就用通用二进制流）。 */
internal fun DshFsBridge.guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt", "log", "md", "json", "xml", "sh", "conf", "ini", "yml", "yaml" -> "text/plain"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    // ────────────────────────── 端点 ──────────────────────────

    /**
     * 自检。
     *
     * 存在的理由：其它端点在没有「所有文件访问」时一律 403，容器侧只能靠猜。这里把
     * 「桥活着」「根在哪」「存储权限有没有」分开告诉调用方。**不需要存储权限**。
     */
