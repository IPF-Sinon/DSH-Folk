package me.bmax.apatch.dsh

import android.os.Environment
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * 宿主侧文件桥：一个只绑 127.0.0.1 的最小 HTTP 服务，让容器内的 DSH 通过随附的
 * `dsh-fs` CLI 对**容器私有目录之外**的手机文件（默认共享存储 /sdcard）做受控操作。
 *
 * ## 为什么是宿主侧 HTTP，而不是继续加 bind mount
 *
 * `/storage/emulated/0` 已经 bind 进容器（见 [ContainerRuntime.BINDS]），但那是「整棵树
 * 直接可见」，既没有边界也没有鉴权 —— 一旦 App 拿到「所有文件访问」，容器里任何进程
 * （包括用户装的插件）都能读整个 /sdcard。这里要做的是一个**带白名单根 + token + 回环
 * 守卫**的窄接口：只暴露一个根、只能按相对路径访问、拿不到 token 的调用一律 403。
 *
 * ## 安全模型
 *
 * - 只绑 127.0.0.1，且逐连接校验 remote 是回环地址（Android 上回环接口所有 App 共享，
 *   所以仅靠回环不够）。
 * - 每个请求必须带 `X-Dsh-Fs-Token`，与 App 生成、写进容器私有目录的随机 token 一致；
 *   其它 App 读不到本 App 的 filesDir/prefs，也就拿不到 token。
 * - 路径逐段拒绝空段/`.`/`..`/绝对路径/反斜杠/盘符，再 canonicalPath 二次确认落在根内，
 *   防符号链接逃逸。
 */
object DshFsBridge {
    private const val TAG = "DshFsBridge"
    private const val HEADER_TOKEN = "X-Dsh-Fs-Token"
    private const val MAX_READ_BYTES = 64L * 1024 * 1024
    private const val CR: Byte = 13
    private const val LF: Byte = 10

    /** 桥接默认端口基准；被占用则向后扫描。 */
    const val PORT_BASE = 3081

    @Volatile private var server: ServerSocket? = null
    @Volatile private var token: String = ""

    /** 白名单根：共享存储。 */
    private val root: File get() = Environment.getExternalStorageDirectory()

    fun start(port: Int, token: String) {
        stop()
        this.token = token
        server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
            }
        } catch (e: Exception) {
            Log.e(TAG, "文件桥启动失败: ${e.message}")
            null
        }
        server?.let { srv ->
            Thread({ acceptLoop(srv) }, "dsh-fs-bridge").apply { isDaemon = true; start() }
            Log.i(TAG, "文件桥已启动 127.0.0.1:$port")
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        token = ""
    }

    private fun acceptLoop(srv: ServerSocket) {
        while (true) {
            val socket = try {
                srv.accept()
            } catch (e: Exception) {
                break
            }
            Thread({ handle(socket) }, "dsh-fs-conn").apply { isDaemon = true }.start()
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            runCatching {
                s.soTimeout = 30_000
                val input = s.getInputStream()
                val headerBlock = readHeaderBlock(input) ?: return@runCatching
                val lines = headerBlock.toString(StandardCharsets.UTF_8).split("\r\n")
                val requestLine = lines.firstOrNull()?.split(' ') ?: return@runCatching
                if (requestLine.size < 2) return@runCatching
                val method = requestLine[0].uppercase()
                val rawTarget = requestLine[1]
                val headers = parseHeaders(lines.drop(1))

                // 回环守卫
                val remote = s.inetAddress
                if (remote == null || !remote.isLoopbackAddress) {
                    respondJson(s, 403, errorJson("仅允许回环访问"))
                    return@runCatching
                }
                if (headers[HEADER_TOKEN.lowercase()] != token) {
                    respondJson(s, 403, errorJson("token 不匹配"))
                    return@runCatching
                }

                val path = rawTarget.substringBefore('?')
                val params = parseQuery(rawTarget.substringAfter('?', ""))
                val out = s.getOutputStream()

                val result: Pair<Int, String>? = when {
                    method == "GET" && path == "/list" -> handleList(params["path"])
                    method == "GET" && path == "/stat" -> handleStat(params["path"])
                    method == "GET" && path == "/read" -> handleRead(params["path"], out)
                    method == "PUT" && path == "/write" -> handleWrite(params["path"], input, headers)
                    method == "POST" && path == "/mkdir" -> handleMkdir(params["path"])
                    method == "POST" && path == "/move" -> handleMove(params["src"], params["dst"])
                    method == "DELETE" && path == "/delete" -> handleDelete(params["path"])
                    else -> 404 to errorJson("未知端点 $method $path")
                }
                result?.let { respondJson(s, it.first, it.second) }
            }.onFailure { e ->
                Log.w(TAG, "文件桥处理失败: ${e.message}")
            }
        }
    }

    // ────────────────────────── 端点 ──────────────────────────

    /** 返回 null 表示响应已直接写出（如 read 的二进制体）。 */
    private fun handleList(rel: String?): Pair<Int, String>? {
        if (!checkStorageAccess()) return 403 to errorJson("未授予「所有文件访问」，请在系统设置中开启")
        val target = resolve(rel) ?: return 400 to errorJson("非法路径")
        if (!target.exists()) return 404 to errorJson("路径不存在")
        val entries = JSONArray()
        if (target.isDirectory) {
            target.listFiles()
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?.forEach { entries.put(entryJson(it)) }
        } else {
            entries.put(entryJson(target))
        }
        val body = JSONObject().put("ok", true).put("entries", entries).toString()
        return 200 to body
    }

    private fun handleStat(rel: String?): Pair<Int, String>? {
        if (!checkStorageAccess()) return 403 to errorJson("未授予「所有文件访问」，请在系统设置中开启")
        val target = resolve(rel) ?: return 400 to errorJson("非法路径")
        if (!target.exists()) return 404 to errorJson("路径不存在")
        return 200 to JSONObject().put("ok", true).put("entry", entryJson(target)).toString()
    }

    private fun handleRead(rel: String?, out: java.io.OutputStream): Pair<Int, String>? {
        if (!checkStorageAccess()) return 403 to errorJson("未授予「所有文件访问」，请在系统设置中开启")
        val target = resolve(rel) ?: return 400 to errorJson("非法路径")
        if (!target.exists()) return 404 to errorJson("路径不存在")
        if (target.isDirectory) return 400 to errorJson("是目录")
        if (target.length() > MAX_READ_BYTES) return 400 to errorJson("文件过大（>64MB）")
        runCatching {
            val bytes = target.readBytes()
            val head = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            out.write(head.toByteArray(StandardCharsets.US_ASCII))
            out.write(bytes)
            out.flush()
        }
        return null
    }

    private fun handleWrite(rel: String?, input: InputStream, headers: Map<String, String>): Pair<Int, String>? {
        if (!checkStorageAccess()) return 403 to errorJson("未授予「所有文件访问」，请在系统设置中开启")
        val target = resolve(rel) ?: return 400 to errorJson("非法路径")
        val parent = target.parentFile ?: return 400 to errorJson("非法路径")
        val len = headers["content-length"]?.toLongOrNull() ?: return 400 to errorJson("缺 Content-Length")
        val ok = runCatching {
            parent.mkdirs()
            val tmp = File(parent, target.name + ".tmp")
            tmp.outputStream().use { o ->
                val buf = ByteArray(8192)
                var remaining = len
                while (remaining > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    o.write(buf, 0, n)
                    remaining -= n
                }
            }
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        }.getOrElse { e ->
            Log.w(TAG, "write 失败: ${e.message}")
            false
        }
        return if (ok) {
            200 to JSONObject().put("ok", true).put("path", rel).toString()
        } else {
            500 to errorJson("写入失败")
        }
    }

    private fun handleMkdir(rel: String?): Pair<Int, String>? {
        if (!checkStorageAccess()) return 403 to errorJson("未授予「所有文件访问」，请在系统设置中开启")
        val target = resolve(rel) ?: return 400 to errorJson("非法路径")
        val ok = target.exists() || target.mkdirs()
        return (if (ok) 200 else 500) to (if (ok) JSONObject().put("ok", true).toString() else errorJson("创建失败"))
    }

    private fun handleMove(src: String?, dst: String?): Pair<Int, String>? {
        if (!checkStorageAccess()) return 403 to errorJson("未授予「所有文件访问」，请在系统设置中开启")
        val s = resolve(src) ?: return 400 to errorJson("非法 src")
        val d = resolve(dst) ?: return 400 to errorJson("非法 dst")
        if (!s.exists()) return 404 to errorJson("src 不存在")
        d.parentFile?.mkdirs()
        val ok = s.renameTo(d)
        return (if (ok) 200 else 500) to (if (ok) JSONObject().put("ok", true).toString() else errorJson("移动失败"))
    }

    private fun handleDelete(rel: String?): Pair<Int, String>? {
        if (!checkStorageAccess()) return 403 to errorJson("未授予「所有文件访问」，请在系统设置中开启")
        val target = resolve(rel) ?: return 400 to errorJson("非法路径")
        if (!target.exists()) return 200 to JSONObject().put("ok", true).toString()
        if (target.isDirectory && (target.listFiles()?.isNotEmpty() == true)) {
            return 400 to errorJson("目录非空")
        }
        val ok = target.delete()
        return (if (ok) 200 else 500) to (if (ok) JSONObject().put("ok", true).toString() else errorJson("删除失败"))
    }

    // ────────────────────────── 工具 ──────────────────────────

    private fun checkStorageAccess(): Boolean = Environment.isExternalStorageManager()

    /** 相对路径 → 根内规范化文件；越界/非法返回 null。 */
    private fun resolve(rel: String?): File? {
        if (rel.isNullOrEmpty() || rel.startsWith("/") || rel.contains('\\')) return null
        if (rel.length >= 2 && rel[1] == ':') return null
        val parts = rel.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        val rootCanon = runCatching { root.canonicalPath }.getOrNull() ?: return null
        val targetCanon = runCatching { File(root, rel).canonicalPath }.getOrNull() ?: return null
        if (targetCanon != rootCanon && !targetCanon.startsWith(rootCanon + File.separator)) return null
        return File(targetCanon)
    }

    private fun entryJson(f: File): JSONObject = JSONObject()
        .put("name", f.name)
        .put("dir", f.isDirectory)
        .put("size", f.length())
        .put("mtime", f.lastModified())

    private fun errorJson(msg: String): String = JSONObject().put("ok", false).put("error", msg).toString()

    private fun respondJson(socket: Socket, status: Int, json: String) {
        runCatching {
            val body = json.toByteArray(StandardCharsets.UTF_8)
            val head = "HTTP/1.1 $status ${statusText(status)}\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().use { o ->
                o.write(head.toByteArray(StandardCharsets.US_ASCII))
                o.write(body)
                o.flush()
            }
        }
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "Status"
    }

    /** 读请求行 + 头部（直到空行），剥离结尾的 CRLFCRLF。 */
    private fun readHeaderBlock(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        while (out.size() < 16 * 1024) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            val buf = out.toByteArray()
            val n = buf.size
            if (n >= 4 && buf[n - 4] == CR && buf[n - 3] == LF && buf[n - 2] == CR && buf[n - 1] == LF) {
                return buf.copyOf(n - 4)
            }
        }
        return null
    }

    private fun parseHeaders(lines: List<String>): Map<String, String> {
        val map = HashMap<String, String>()
        for (line in lines) {
            val i = line.indexOf(':')
            if (i > 0) map[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        return map
    }

    private fun parseQuery(query: String): Map<String, String> {
        val map = HashMap<String, String>()
        if (query.isEmpty()) return map
        for (pair in query.split('&')) {
            val i = pair.indexOf('=')
            if (i < 0) continue
            val k = decode(pair.substring(0, i))
            val v = decode(pair.substring(i + 1))
            map[k] = v
        }
        return map
    }

    /** URL 解码（用 String 编码重载，兼容低 API；解码失败原样返回）。 */
    private fun decode(s: String): String = runCatching {
        java.net.URLDecoder.decode(s, "UTF-8")
    }.getOrDefault(s)
}
