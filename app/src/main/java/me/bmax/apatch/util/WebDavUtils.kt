package me.bmax.apatch.util

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.net.URL
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale

object WebDavUtils {
    private val client = OkHttpClient()

    suspend fun testConnection(url: String, user: String, pass: String): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val credential = Credentials.basic(user, pass)
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", credential)
                    .method("PROPFIND", null) // WebDAV check
                    .header("Depth", "0")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful || response.code == 207) { // 207 Multi-Status is typical for PROPFIND
                         Result.success(true)
                    } else {
                         Result.failure(IOException("HTTP ${response.code}"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun uploadFile(baseUrl: String, user: String, pass: String, file: File, subDir: String, remoteFileName: String? = null): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                var cleanBaseUrl = if (baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl
            
                
                val parts = subDir.split("/").filter { it.isNotEmpty() }
                var currentPath = ""
                for (part in parts) {
                    currentPath = if (currentPath.isEmpty()) part else "$currentPath/$part"
                    createDir(cleanBaseUrl, user, pass, currentPath)
                }

                val fileName = remoteFileName ?: file.name
                // subDir 为空（用户把远端路径填成 "/" 或留空）时不能直接拼 —— 那会变成
                // base//file，多数 WebDAV 服务端会返回 409/404。
                val fullUrl = if (currentPath.isEmpty()) "$cleanBaseUrl/$fileName"
                    else "$cleanBaseUrl/$currentPath/$fileName"
                BackupLogManager.log("Uploading to: $fullUrl")
                
                val credential = Credentials.basic(user, pass)
                val request = Request.Builder()
                    .url(fullUrl)
                    .header("Authorization", credential)
                    .put(file.asRequestBody("application/octet-stream".toMediaTypeOrNull()))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful || response.code == 201 || response.code == 204) {
                        BackupLogManager.log("Upload success: $fileName")
                        Result.success(true)
                    } else {
                        val msg = "Upload failed: ${response.code} - ${response.message}"
                        BackupLogManager.log(msg)
                        Result.failure(IOException(msg))
                    }
                }
            } catch (e: Exception) {
                BackupLogManager.log("Upload exception: ${e.message}")
                Result.failure(e)
            }
        }
    }
    
    /** WebDAV 目录里的一个文件（只列文件，不列集合）。 */
    data class RemoteEntry(
        /** 解码后的展示名（文件名）。 */
        val name: String,
        /** 原样保留的 href（仍是百分号编码），直接可用于 GET。 */
        val path: String,
        val sizeBytes: Long = 0L,
        /** 服务端给的修改时间（毫秒），解析不出来为 0。 */
        val lastModifiedMs: Long = 0L,
    )

    /**
     * 列出远端目录里的备份文件（PROPFIND Depth: 1）。
     *
     * 为什么需要它：以前只有 [uploadFile]，云端的备份在 App 里**取不回来** —— 换机或清机
     * 之后那份备份只能靠文件管理器手动下载再导入。
     *
     * 服务端差异很大（Nextcloud / 坚果云 / Alist 的 href 形式与命名空间前缀都不同），所以：
     * - 比较标签名时统一去掉命名空间前缀并转小写；
     * - href 可能是绝对 URL 也可能是绝对路径，两种都归一化；
     * - 只收 `.zip`，并按 mtime 倒序；
     * - 失败一律返回 Result.failure（调用方提示「请手动下载后再导入」），不吞成空列表。
     */
    suspend fun listRemote(
        baseUrl: String,
        user: String,
        pass: String,
        subDir: String,
    ): Result<List<RemoteEntry>> = withContext(Dispatchers.IO) {
        try {
            val cleanBase = baseUrl.trim().trimEnd('/')
            if (cleanBase.isEmpty()) return@withContext Result.failure(IOException("no base url"))
            val dirPath = subDir.split("/").filter { it.isNotEmpty() }.joinToString("/")
            val dirUrl = if (dirPath.isEmpty()) "$cleanBase/" else "$cleanBase/$dirPath/"
            val credential = Credentials.basic(user, pass)
            val request = Request.Builder()
                .url(dirUrl)
                .header("Authorization", credential)
                .header("Depth", "1")
                .method("PROPFIND", null)
                .build()
            client.newCall(request).execute().use { response ->
                // 207 Multi-Status 是 PROPFIND 的正常返回；有些服务端直接 200
                if (response.code != 207 && !response.isSuccessful) {
                    return@withContext Result.failure(IOException("PROPFIND HTTP ${response.code}"))
                }
                val xml = response.body?.string().orEmpty()
                Result.success(parsePropfind(xml, dirUrl))
            }
        } catch (e: Exception) {
            BackupLogManager.log("WebDAV list exception: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * 解析 PROPFIND 的 207 响应。
     *
     * 手写 XmlPullParser 而不是引 XML 解析库：只关心四个字段，而且必须对命名空间前缀
     * 不敏感（`D:response` / `d:response` / 无前缀都合法）。
     */
    private fun parsePropfind(xml: String, dirUrl: String): List<RemoteEntry> {
        val out = mutableListOf<RemoteEntry>()
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())
        var href = ""
        var length = 0L
        var mtime = 0L
        var isCollection = false
        var inResponse = false
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                when (parser.name.substringAfterLast(':').lowercase(Locale.US)) {
                    "response" -> {
                        inResponse = true
                        href = ""
                        length = 0L
                        mtime = 0L
                        isCollection = false
                    }
                    "href" -> if (inResponse) href = parser.nextText().trim()
                    "getcontentlength" -> if (inResponse) length = parser.nextText().trim().toLongOrNull() ?: 0L
                    "getlastmodified" -> if (inResponse) mtime = parseHttpDate(parser.nextText().trim())
                    "collection" -> if (inResponse) isCollection = true
                }
            } else if (event == org.xmlpull.v1.XmlPullParser.END_TAG &&
                parser.name.substringAfterLast(':').equals("response", ignoreCase = true)
            ) {
                inResponse = false
                // 目录项本身（href 与请求目录相同）与集合都不要
                if (!isCollection && href.isNotEmpty() && !isSameDir(href, dirUrl)) {
                    val decoded = decodeHref(href)
                    val name = decoded.trimEnd('/').substringAfterLast('/')
                    if (name.endsWith(".zip", ignoreCase = true)) {
                        out += RemoteEntry(name = name, path = href, sizeBytes = length, lastModifiedMs = mtime)
                    }
                }
            }
            event = parser.next()
        }
        return out.sortedByDescending { it.lastModifiedMs }
    }

    /** href 与请求目录是不是同一个（服务器会返回目录自身那一条）。 */
    private fun isSameDir(href: String, dirUrl: String): Boolean {
        val a = normalizeHref(href)
        val b = normalizeHref(dirUrl)
        return a.trimEnd('/') == b.trimEnd('/')
    }

    /** href → 绝对路径（绝对 URL 取 path，绝对路径原样，相对路径按目录拼接）。 */
    private fun normalizeHref(href: String): String {
        val h = href.trim()
        return runCatching { URL(h).path }.getOrNull() ?: h
    }

    /**
     * href 的百分号解码。
     *
     * 先把 `+` 换成 `%2B` 再交给 URLDecoder：URLDecoder 是给 form 用的，会把 `+` 当空格，
     * 而文件名里的 `+` 是字面量 —— 不处理的话「备份+1.zip」会变成「备份 1.zip」。
     */
    private fun decodeHref(href: String): String =
        runCatching { URLDecoder.decode(href.replace("+", "%2B"), "UTF-8") }.getOrDefault(href)

    /** RFC 1123 的 `Wed, 21 Oct 2015 07:28:00 GMT`；也认 ISO 形式。解析不了返回 0。 */
    private fun parseHttpDate(text: String): Long {
        if (text.isBlank()) return 0L
        runCatching {
            return SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(text)!!.time
        }
        runCatching {
            return java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli()
        }
        return 0L
    }

    /**
     * 把远端文件下载到本地（流式，不整包进内存）。
     *
     * 失败时删掉半截文件：留一个坏 zip 在那儿，用户下次导入会拿到看不懂的解析错误。
     */
    suspend fun downloadTo(
        baseUrl: String,
        user: String,
        pass: String,
        remotePath: String,
        dest: File,
    ): Result<Long> = withContext(Dispatchers.IO) {
        var wrote = false
        try {
            val url = if (remotePath.startsWith("http://") || remotePath.startsWith("https://")) {
                remotePath
            } else {
                baseUrl.trim().trimEnd('/') + "/" + remotePath.trimStart('/')
            }
            val credential = Credentials.basic(user, pass)
            val request = Request.Builder().url(url).header("Authorization", credential).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("GET HTTP ${response.code}"))
                }
                val body = response.body ?: return@withContext Result.failure(IOException("empty body"))
                dest.parentFile?.mkdirs()
                body.byteStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
                wrote = true
                BackupLogManager.log("Downloaded ${dest.name} (${dest.length()} bytes)")
                Result.success(dest.length())
            }
        } catch (e: Exception) {
            BackupLogManager.log("WebDAV download exception: ${e.message}")
            Result.failure(e)
        } finally {
            if (!wrote && dest.exists()) dest.delete()
        }
    }

    private fun createDir(baseUrl: String, user: String, pass: String, path: String) {
        val fullUrl = "$baseUrl/$path"
        val credential = Credentials.basic(user, pass)
        
        // Check if exists
        val checkRequest = Request.Builder()
            .url(fullUrl)
            .header("Authorization", credential)
            .method("HEAD", null)
            .build()
            
        try {
            client.newCall(checkRequest).execute().use { response ->
                if (response.code == 404) {
                    // Create it
                    val mkcolRequest = Request.Builder()
                        .url(fullUrl)
                        .header("Authorization", credential)
                        .method("MKCOL", null)
                        .build()
                    client.newCall(mkcolRequest).execute().close()
                }
            }
        } catch (e: Exception) {
            // Ignore errors in directory creation, maybe it exists or we can't create it
        }
    }
}
