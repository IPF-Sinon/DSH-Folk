package me.bmax.apatch.dsh

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import me.bmax.apatch.R
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
 * 宿主侧回环桥：一个只绑 127.0.0.1 的最小 HTTP 服务。
 *
 * 两组端点共用同一个 socket、同一个 token、同一套回环守卫：
 *
 * - **文件**（本文件实现）：让容器内的 DSH 通过随附的 `dsh-fs` CLI 对**容器私有目录之外**
 *   的手机文件（默认共享存储 /sdcard）做受控操作。
 * - **原生能力**（[DshNativeBridge] 实现，路径前缀 `/native/`）：通知、振动、剪贴板等。
 *
 * ## 为什么是宿主侧 HTTP，而不是继续加 bind mount
 *
 * `/storage/emulated/0` 已经 bind 进容器（见 [ContainerRuntime.BINDS]），但那是「整棵树
 * 直接可见」，既没有边界也没有鉴权 —— 一旦 App 拿到「所有文件访问」，容器里任何进程
 * （包括用户装的插件）都能读整个 /sdcard。这里要做的是一个**带白名单根 + token + 回环
 * 守卫**的窄接口：只暴露一个根、只能按相对路径访问、拿不到 token 的调用一律 403。
 * 原生能力更是只能走这条路 —— 容器里根本没有 Binder。
 *
 * ## 安全模型
 *
 * - 只绑 127.0.0.1，且逐连接校验 remote 是回环地址（Android 上回环接口所有 App 共享，
 *   所以仅靠回环不够）。
 * - 每个请求必须带 `X-Dsh-Fs-Token`，与 App 生成、写进容器私有目录的随机 token 一致；
 *   其它 App 读不到本 App 的 filesDir/prefs，也就拿不到 token。**token 是真正的边界**，
 *   所以用定长比较，不用 String.equals。
 * - 路径逐段拒绝空段/`.`/`..`/绝对路径/反斜杠/盘符，再 canonicalPath 二次确认落在根内，
 *   防符号链接逃逸。递归操作（list/find/copy/delete）的**每一层**都重新确认一次，
 *   不是只校验入口。
 */
object DshFsBridge {
    private const val TAG = "DshFsBridge"
    private const val HEADER_TOKEN = "X-Dsh-Fs-Token"

    /** 单次 /read 的上限。超过就让调用方用 offset/length 分段，而不是塞满内存。 */
    private const val MAX_READ_BYTES = 64L * 1024 * 1024

    /** 递归遍历的默认/最大深度。 */
    private const val DEFAULT_MAX_DEPTH = 8
    private const val MAX_MAX_DEPTH = 32

    /** list/find 的默认/最大返回条数。 */
    private const val DEFAULT_LIST_LIMIT = 1000
    private const val MAX_LIST_LIMIT = 10_000
    private const val DEFAULT_FIND_LIMIT = 500
    private const val MAX_FIND_LIMIT = 5_000

    /**
     * 递归删除/复制的条目硬上限。
     *
     * 超限就整个拒绝，不做「删一半」—— 半个删除操作比不删更难收拾。
     */
    private const val MAX_RECURSIVE_ENTRIES = 20_000

    private const val CR: Byte = 13
    private const val LF: Byte = 10

    /** 桥接默认端口基准；被占用则向后扫描。 */
    const val PORT_BASE = 3081

    @Volatile private var server: ServerSocket? = null
    @Volatile private var token: String = ""

    /** 原生能力端点需要 Context；由 [start] 注入 application context。 */
    @Volatile private var appCtx: Context? = null

    /** 白名单根：共享存储。 */
    private val root: File get() = Environment.getExternalStorageDirectory()

    fun start(port: Int, token: String, context: Context) {
        stop()
        this.token = token
        this.appCtx = context.applicationContext
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
        appCtx = null
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
            // 谁都可能提前把响应写出去（read 是流式的），兜底时不能重复写
            var responded = false
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
                    responded = true
                    respondJson(s, 403, errorJson(str(R.string.dsh_fs_err_loopback_only), "loopback_only"))
                    return@runCatching
                }
                if (!tokenMatches(headers[HEADER_TOKEN.lowercase()])) {
                    responded = true
                    respondJson(s, 403, errorJson(str(R.string.dsh_fs_err_bad_token), "bad_token"))
                    return@runCatching
                }

                val path = rawTarget.substringBefore('?')
                val params = parseQuery(rawTarget.substringAfter('?', ""))
                val out = s.getOutputStream()

                val result: Pair<Int, String>? = when {
                    path.startsWith("/native/") -> dispatchNative(method, path, params)
                    method == "GET" && path == "/health" -> handleHealth()
                    method == "GET" && path == "/list" -> handleList(params)
                    method == "GET" && path == "/stat" -> handleStat(params["path"])
                    method == "GET" && path == "/read" -> handleRead(params, out)
                    method == "GET" && path == "/find" -> handleFind(params)
                    method == "GET" && path == "/space" -> handleSpace(params["path"])
                    method == "PUT" && path == "/write" -> handleWrite(params, input, headers)
                    method == "POST" && path == "/mkdir" -> handleMkdir(params["path"])
                    method == "POST" && path == "/move" -> handleMove(params["src"], params["dst"])
                    method == "POST" && path == "/copy" -> handleCopy(params)
                    method == "DELETE" && path == "/delete" -> handleDelete(params)
                    else -> 404 to errorJson(str(R.string.dsh_fs_err_unknown_endpoint, method, path), "unknown_endpoint")
                }
                // null = 处理函数自己写完了响应体（read 的二进制流）
                responded = true
                result?.let { respondJson(s, it.first, it.second) }
            }.onFailure { e ->
                Log.w(TAG, "文件桥处理失败: ${e.message}")
                // 之前只记日志不回话，客户端只能看到 socket EOF，分不清「服务没起」和「请求崩了」
                if (!responded) {
                    runCatching { respondJson(s, 500, errorJson(str(R.string.dsh_fs_err_internal, e.message ?: ""), "internal")) }
                }
            }
        }
    }

    private fun dispatchNative(
        method: String,
        path: String,
        params: Map<String, String>,
    ): Pair<Int, String>? {
        val ctx = appCtx ?: return 500 to errorJson(str(R.string.dsh_fs_err_native_uninit), "native_uninit")
        return DshNativeBridge.handle(ctx, method, path, params)
    }

    // ────────────────────────── 端点 ──────────────────────────

    /**
     * 自检。
     *
     * 存在的理由：其它端点在没有「所有文件访问」时一律 403，容器侧只能靠猜。这里把
     * 「桥活着」「根在哪」「存储权限有没有」分开告诉调用方。**不需要存储权限**。
     */
    private fun handleHealth(): Pair<Int, String> = 200 to JSONObject()
        .put("ok", true)
        .put("root", runCatching { root.absolutePath }.getOrDefault(""))
        .put("storageGranted", checkStorageAccess())
        .put("api", API_VERSION)
        .toString()

    /** 返回 null 表示响应已直接写出（如 read 的二进制体）。 */
    private fun handleList(params: Map<String, String>): Pair<Int, String>? {
        if (!checkStorageAccess()) return storageDenied()
        val target = resolve(params["path"]) ?: return 400 to errorJson(str(R.string.dsh_fs_err_bad_path), "bad_path")
        if (!target.exists()) return 404 to errorJson(str(R.string.dsh_fs_err_not_found), "not_found")
        val limit = clampInt(params["limit"], DEFAULT_LIST_LIMIT, 1, MAX_LIST_LIMIT)
        val recursive = boolParam(params["recursive"])

        val entries = JSONArray()
        if (!target.isDirectory) {
            entries.put(entryJson(target, relativeOf(target)))
            return 200 to JSONObject().put("ok", true).put("entries", entries)
                .put("truncated", false).toString()
        }

        var truncated = false
        if (recursive) {
            val depth = clampInt(params["maxDepth"], DEFAULT_MAX_DEPTH, 1, MAX_MAX_DEPTH)
            truncated = !walk(target, depth) { f ->
                if (entries.length() >= limit) return@walk false
                entries.put(entryJson(f, relativeOf(f)))
                true
            }
        } else {
            val children = target.listFiles()
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                .orEmpty()
            for (f in children) {
                if (entries.length() >= limit) {
                    truncated = true
                    break
                }
                if (!within(f)) continue
                entries.put(entryJson(f, relativeOf(f)))
            }
        }
        return 200 to JSONObject().put("ok", true).put("entries", entries)
            .put("truncated", truncated).toString()
    }

    private fun handleStat(rel: String?): Pair<Int, String>? {
        if (!checkStorageAccess()) return storageDenied()
        val target = resolve(rel) ?: return 400 to errorJson(str(R.string.dsh_fs_err_bad_path), "bad_path")
        if (!target.exists()) return 404 to errorJson(str(R.string.dsh_fs_err_not_found), "not_found")
        return 200 to JSONObject().put("ok", true)
            .put("entry", entryJson(target, relativeOf(target))).toString()
    }

    /**
     * 分段读。
     *
     * 与旧实现的两个区别：
     * 1. 不再 `readBytes()` 把整个文件读进内存，改 [RandomAccessFile] 流式拷贝 ——
     *    64MB 的堆峰值在低端机上就是一次 OOM。
     * 2. 打开/定位失败时会真的回一个 JSON 错误。旧代码把异常吞在 runCatching 里却仍然
     *    `return null`（含义是「已自行响应」），于是客户端只看到 socket EOF。
     */
    private fun handleRead(params: Map<String, String>, out: OutputStream): Pair<Int, String>? {
        if (!checkStorageAccess()) return storageDenied()
        val target = resolve(params["path"]) ?: return 400 to errorJson(str(R.string.dsh_fs_err_bad_path), "bad_path")
        if (!target.exists()) return 404 to errorJson(str(R.string.dsh_fs_err_not_found), "not_found")
        if (target.isDirectory) return 400 to errorJson(str(R.string.dsh_fs_err_is_dir), "is_dir")

        val size = target.length()
        val offset = params["offset"]?.let {
            it.toLongOrNull() ?: return 400 to errorJson(str(R.string.dsh_fs_err_not_int, "offset"), "not_int")
        } ?: 0L
        if (offset < 0) return 400 to errorJson(str(R.string.dsh_fs_err_negative, "offset"), "negative")
        if (offset > size) return 400 to errorJson(str(R.string.dsh_fs_err_offset_past_end, size), "offset_past_end")
        val requested = params["length"]?.let {
            it.toLongOrNull() ?: return 400 to errorJson(str(R.string.dsh_fs_err_not_int, "length"), "not_int")
        }
        if (requested != null && requested < 0) return 400 to errorJson(str(R.string.dsh_fs_err_negative, "length"), "negative")
        val count = if (requested == null) size - offset else minOf(requested, size - offset)
        if (count > MAX_READ_BYTES) {
            return 400 to errorJson(str(R.string.dsh_fs_err_read_too_big, count), "read_too_big")
        }

        val raf = runCatching { RandomAccessFile(target, "r") }.getOrElse { e ->
            return 500 to errorJson(str(R.string.dsh_fs_err_open_failed, e.message ?: ""), "open_failed")
        }
        raf.use { f ->
            runCatching { f.seek(offset) }.getOrElse { e ->
                return 500 to errorJson(str(R.string.dsh_fs_err_seek_failed, e.message ?: ""), "seek_failed")
            }
            // 头一旦写出就没法再改成 JSON 错误了，所以放在 seek 之后
            runCatching {
                val head = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/octet-stream\r\n" +
                    "Content-Length: $count\r\n" +
                    "Connection: close\r\n\r\n"
                out.write(head.toByteArray(StandardCharsets.US_ASCII))
                val buf = ByteArray(64 * 1024)
                var remaining = count
                while (remaining > 0) {
                    val n = f.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
                out.flush()
            }.onFailure { e -> Log.w(TAG, "read 传输中断: ${e.message}") }
        }
        return null
    }

    /**
     * 写入。`append=1` 时追加，否则临时文件 + rename 原子替换。
     *
     * 追加不能走 rename 那条路（要先把原文件读出来），所以直接 append 打开。
     */
    private fun handleWrite(
        params: Map<String, String>,
        input: InputStream,
        headers: Map<String, String>,
    ): Pair<Int, String>? {
        if (!checkStorageAccess()) return storageDenied()
        val rel = params["path"]
        val target = resolve(rel) ?: return 400 to errorJson(str(R.string.dsh_fs_err_bad_path), "bad_path")
        if (target.isDirectory) return 400 to errorJson(str(R.string.dsh_fs_err_is_dir), "is_dir")
        val parent = target.parentFile ?: return 400 to errorJson(str(R.string.dsh_fs_err_bad_path), "bad_path")
        val len = headers["content-length"]?.toLongOrNull()
            ?: return 400 to errorJson(str(R.string.dsh_fs_err_missing_length), "missing_length")
        if (len < 0) return 400 to errorJson(str(R.string.dsh_fs_err_bad_length), "bad_length")
        val append = boolParam(params["append"])

        var written = -1L
        val ok = runCatching {
            parent.mkdirs()
            if (append) {
                java.io.FileOutputStream(target, true).use { o -> written = pump(input, o, len) }
                true
            } else {
                val tmp = File(parent, target.name + ".dsh-fs.tmp")
                tmp.outputStream().use { o -> written = pump(input, o, len) }
                if (written != len) {
                    // 请求体没收完就不要替换目标：宁可留下 .tmp 也别把好文件换成半份
                    tmp.delete()
                    false
                } else {
                    if (target.exists()) target.delete()
                    tmp.renameTo(target).also { moved -> if (!moved) tmp.delete() }
                }
            }
        }.getOrElse { e ->
            Log.w(TAG, "write 失败: ${e.message}")
            false
        }
        if (!ok) return 500 to errorJson(str(R.string.dsh_fs_err_write_failed), "write_failed")
        if (written != len) {
            return 400 to errorJson(str(R.string.dsh_fs_err_short_body, len, written), "short_body")
        }
        return 200 to JSONObject().put("ok", true).put("path", rel)
            .put("append", append).put("bytes", written).toString()
    }

    private fun handleMkdir(rel: String?): Pair<Int, String>? {
        if (!checkStorageAccess()) return storageDenied()
        val target = resolve(rel) ?: return 400 to errorJson(str(R.string.dsh_fs_err_bad_path), "bad_path")
        if (target.isFile) return 400 to errorJson(str(R.string.dsh_fs_err_file_exists), "file_exists")
        val ok = target.isDirectory || target.mkdirs()
        return if (ok) 200 to okJson() else 500 to errorJson(str(R.string.dsh_fs_err_mkdir_failed), "mkdir_failed")
    }

    /**
     * 移动。
     *
     * `renameTo` 跨挂载点必然失败（共享存储上 Android/data 与主区常常就是不同挂载），
     * 所以失败后回退到「复制 + 删除」，而不是直接把失败甩给调用方。
     */
    private fun handleMove(src: String?, dst: String?): Pair<Int, String>? {
        if (!checkStorageAccess()) return storageDenied()
        val s = resolve(src) ?: return 400 to errorJson(str(R.string.dsh_fs_err_bad_arg, "src"), "bad_arg")
        val d = resolve(dst) ?: return 400 to errorJson(str(R.string.dsh_fs_err_bad_arg, "dst"), "bad_arg")
        if (!s.exists()) return 404 to errorJson(str(R.string.dsh_fs_err_src_missing), "src_missing")
        if (isParentOf(s, d)) return 400 to errorJson(str(R.string.dsh_fs_err_dst_inside_src), "dst_inside_src")
        d.parentFile?.mkdirs()
        if (s.renameTo(d)) {
            return 200 to JSONObject().put("ok", true).put("mode", "rename").toString()
        }

        val copied = copyRecursive(s, d, overwrite = true)
        if (copied.first != 200) return copied
        val removed = deleteRecursive(s)
        return if (removed) {
            200 to JSONObject().put("ok", true).put("mode", "copy+delete").toString()
        } else {
            500 to errorJson(str(R.string.dsh_fs_err_move_partial), "move_partial")
        }
    }

    private fun handleCopy(params: Map<String, String>): Pair<Int, String>? {
        if (!checkStorageAccess()) return storageDenied()
        val s = resolve(params["src"]) ?: return 400 to errorJson(str(R.string.dsh_fs_err_bad_arg, "src"), "bad_arg")
        val d = resolve(params["dst"]) ?: return 400 to errorJson(str(R.string.dsh_fs_err_bad_arg, "dst"), "bad_arg")
        if (!s.exists()) return 404 to errorJson(str(R.string.dsh_fs_err_src_missing), "src_missing")
        if (isParentOf(s, d)) return 400 to errorJson(str(R.string.dsh_fs_err_dst_inside_src_recursive), "dst_inside_src_recursive")
        val overwrite = boolParam(params["overwrite"])
        if (!overwrite && d.exists()) return 400 to errorJson(str(R.string.dsh_fs_err_dst_exists), "dst_exists")
        return copyRecursive(s, d, overwrite)
    }

    private fun handleDelete(params: Map<String, String>): Pair<Int, String>? {
        if (!checkStorageAccess()) return storageDenied()
        val target = resolve(params["path"]) ?: return 400 to errorJson(str(R.string.dsh_fs_err_bad_path), "bad_path")
        // 不允许删根：那是「清空用户整个共享存储」，绝不该是一条 HTTP 请求能做到的事
        if (relativeOf(target).isEmpty()) return 400 to errorJson(str(R.string.dsh_fs_err_no_delete_root), "no_delete_root")
        if (!target.exists()) return 200 to okJson()
        val recursive = boolParam(params["recursive"])

        if (target.isDirectory && !recursive && (target.listFiles()?.isNotEmpty() == true)) {
            return 400 to errorJson(str(R.string.dsh_fs_err_dir_not_empty), "dir_not_empty")
        }
        if (target.isDirectory && recursive) {
            val n = countRecursive(target)
            if (n < 0) return 400 to errorJson(str(R.string.dsh_fs_err_too_many_delete, MAX_RECURSIVE_ENTRIES), "too_many_delete")
        }
        val ok = deleteRecursive(target)
        return if (ok) 200 to okJson() else 500 to errorJson(str(R.string.dsh_fs_err_delete_failed), "delete_failed")
    }

    /**
     * 按名字 glob 查找。
     *
     * 只认 `*` 和 `?`，其余字符全部按字面量转义 —— 不能让调用方的输入变成一个可控正则
     * （灾难性回溯在这个进程里就是一次 ANR）。
     */
    private fun handleFind(params: Map<String, String>): Pair<Int, String>? {
        if (!checkStorageAccess()) return storageDenied()
        val base = resolve(params["path"]) ?: return 400 to errorJson(str(R.string.dsh_fs_err_bad_path), "bad_path")
        if (!base.exists()) return 404 to errorJson(str(R.string.dsh_fs_err_not_found), "not_found")
        if (!base.isDirectory) return 400 to errorJson(str(R.string.dsh_fs_err_not_dir), "not_dir")
        val glob = params["glob"] ?: return 400 to errorJson(str(R.string.dsh_fs_err_missing_glob), "missing_glob")
        val re = globToRegex(glob) ?: return 400 to errorJson(str(R.string.dsh_fs_err_bad_glob), "bad_glob")
        val limit = clampInt(params["limit"], DEFAULT_FIND_LIMIT, 1, MAX_FIND_LIMIT)
        val depth = clampInt(params["maxDepth"], DEFAULT_MAX_DEPTH, 1, MAX_MAX_DEPTH)

        val entries = JSONArray()
        val complete = walk(base, depth) { f ->
            if (entries.length() >= limit) return@walk false
            if (re.matches(f.name)) entries.put(entryJson(f, relativeOf(f)))
            true
        }
        return 200 to JSONObject().put("ok", true).put("entries", entries)
            .put("truncated", !complete).toString()
    }

    private fun handleSpace(rel: String?): Pair<Int, String>? {
        if (!checkStorageAccess()) return storageDenied()
        val target = resolve(rel) ?: return 400 to errorJson(str(R.string.dsh_fs_err_bad_path), "bad_path")
        val dir = if (target.isDirectory) target else target.parentFile ?: root
        val st = runCatching { StatFs(dir.absolutePath) }.getOrElse { e ->
            return 500 to errorJson(str(R.string.dsh_fs_err_statfs, e.message ?: ""), "statfs")
        }
        val block = st.blockSizeLong
        return 200 to JSONObject()
            .put("ok", true)
            .put("path", relativeOf(dir))
            .put("total", st.blockCountLong * block)
            .put("free", st.freeBlocksLong * block)
            .put("available", st.availableBlocksLong * block)
            .toString()
    }

    // ────────────────────────── 递归工具 ──────────────────────────

    /**
     * 深度优先遍历，逐个交给 [visit]；[visit] 返回 false 表示「够了」。
     *
     * @return true = 走完了整棵（子）树，false = 被 [visit] 或预算截断。
     *
     * 每个条目都重新过一次 [within]：入口合法不代表子项合法，符号链接可以把遍历带出根外。
     */
    private fun walk(base: File, maxDepth: Int, visit: (File) -> Boolean): Boolean {
        var budget = MAX_RECURSIVE_ENTRIES
        fun rec(dir: File, depth: Int): Boolean {
            val children = dir.listFiles() ?: return true
            for (f in children.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))) {
                if (budget-- <= 0) return false
                if (!within(f)) continue
                if (!visit(f)) return false
                if (f.isDirectory && depth < maxDepth && !isSymlink(f)) {
                    if (!rec(f, depth + 1)) return false
                }
            }
            return true
        }
        return rec(base, 1)
    }

    /** 条目总数；超过 [MAX_RECURSIVE_ENTRIES] 返回 -1。 */
    private fun countRecursive(base: File): Int {
        var n = 0
        val complete = walk(base, MAX_MAX_DEPTH) {
            n++
            n <= MAX_RECURSIVE_ENTRIES
        }
        return if (complete && n <= MAX_RECURSIVE_ENTRIES) n else -1
    }

    /** 深度优先删除。符号链接只删链接本身，不跟进去。 */
    private fun deleteRecursive(target: File): Boolean {
        if (!within(target)) return false
        if (target.isDirectory && !isSymlink(target)) {
            target.listFiles()?.forEach { child ->
                if (!deleteRecursive(child)) return false
            }
        }
        return !target.exists() || target.delete()
    }

    private fun copyRecursive(src: File, dst: File, overwrite: Boolean): Pair<Int, String> {
        if (!within(src) || !within(dst)) return 400 to errorJson(str(R.string.dsh_fs_err_escape), "escape")
        if (src.isDirectory && countRecursive(src) < 0) {
            return 400 to errorJson(str(R.string.dsh_fs_err_too_many_copy, MAX_RECURSIVE_ENTRIES), "too_many_copy")
        }
        var files = 0
        runCatching {
            fun rec(s: File, d: File) {
                // 这些 message 会被下面的 onFailure 拼进 dsh_fs_err_copy_failed 交给用户，
                // 所以它们也得本地化，不能留中文字面量
                if (!within(s) || !within(d)) {
                    throw java.io.IOException(str(R.string.dsh_fs_err_escape_named, s.name))
                }
                if (s.isDirectory && !isSymlink(s)) {
                    if (!d.isDirectory && !d.mkdirs()) {
                        throw java.io.IOException(str(R.string.dsh_fs_err_mkdir_named, d.name))
                    }
                    s.listFiles()?.forEach { child -> rec(child, File(d, child.name)) }
                } else {
                    if (d.exists()) {
                        if (!overwrite) {
                            throw java.io.IOException(str(R.string.dsh_fs_err_exists_named, d.name))
                        }
                        if (!d.delete()) {
                            throw java.io.IOException(
                                str(R.string.dsh_fs_err_overwrite_named, d.name),
                            )
                        }
                    }
                    d.parentFile?.mkdirs()
                    s.inputStream().use { i -> d.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
                    files++
                }
            }
            rec(src, dst)
        }.onFailure { e ->
            Log.w(TAG, "copy 失败: ${e.message}")
            // 已复制的部分保留：删掉它需要再一次递归删除，风险比留下半份更大
            return 500 to errorJson(str(R.string.dsh_fs_err_copy_failed, e.message ?: "", files), "copy_failed")
        }
        return 200 to JSONObject().put("ok", true).put("files", files).toString()
    }

    // ────────────────────────── 工具 ──────────────────────────

    /** 接口版本；容器侧 CLI 用它判断宿主是否支持新子命令。 */
    private const val API_VERSION = 3

    /**
     * 有没有整个共享存储的读写权限。
     *
     * 判据交给 [PermissionUtils.hasAllFilesAccess]：那里做了 SDK 分支。以前这里直接调
     * `Environment.isExternalStorageManager()`，那是 **API 30** 才有的方法，而 minSdk 是
     * 26 —— Android 8/9 上会抛 NoSuchMethodError，被外层 runCatching 吞掉后变成 500，
     * 而那些系统本来靠 READ/WRITE_EXTERNAL_STORAGE 就够用。
     */
    private fun checkStorageAccess(): Boolean {
        val ctx = appCtx ?: return false
        return PermissionUtils.hasAllFilesAccess(ctx)
    }

    private fun storageDenied(): Pair<Int, String> =
        403 to errorJson(str(R.string.dsh_fs_err_no_storage), "no_storage")

    /**
     * 取一条本地化文案。
     *
     * 这些串会经 `dsh-fs` 的输出出现在**用户**眼前，所以要跟随应用语言。
     * 拿不到 Context（桥还没 start）时退回资源名，不让报错本身再抛一次。
     */
    private fun str(resId: Int, vararg args: Any): String {
        val ctx = appCtx ?: return "error"
        return runCatching {
            if (args.isEmpty()) ctx.getString(resId) else ctx.getString(resId, *args)
        }.getOrElse { ctx.resources.getResourceEntryName(resId) ?: "error" }
    }

    /** 定长比较：token 是这条接口唯一的真实边界，不用 String.equals。 */
    private fun tokenMatches(provided: String?): Boolean {
        val expected = token
        if (expected.isEmpty() || provided.isNullOrEmpty()) return false
        return MessageDigest.isEqual(
            provided.toByteArray(StandardCharsets.UTF_8),
            expected.toByteArray(StandardCharsets.UTF_8),
        )
    }

    /**
     * 相对路径 → 根内规范化文件；越界/非法返回 null。
     *
     * 与旧版的区别：空串、`"."` 与结尾斜杠都归一化为「根目录本身」。旧版一律判非法，
     * 于是 `dsh-fs list` 不带参数根本列不出 /sdcard —— 那是这个接口最自然的一次调用。
     */
    private fun resolve(rel: String?): File? {
        val raw = rel ?: ""
        if (raw.contains('\\')) return null
        if (raw.length >= 2 && raw[1] == ':') return null
        if (raw.any { it.code < 0x20 }) return null
        // 前后的 / 只是书写习惯，不是「绝对路径」语义：这个接口只有相对路径
        val trimmed = raw.trim('/')
        val normalized = if (trimmed == ".") "" else trimmed
        if (normalized.isNotEmpty()) {
            val parts = normalized.split('/')
            if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        }
        val rootCanon = runCatching { root.canonicalPath }.getOrNull() ?: return null
        val targetCanon = runCatching {
            if (normalized.isEmpty()) root.canonicalPath else File(root, normalized).canonicalPath
        }.getOrNull() ?: return null
        if (targetCanon != rootCanon && !targetCanon.startsWith(rootCanon + File.separator)) return null
        return File(targetCanon)
    }

    /** 这个 File 的规范路径是否仍落在根内（递归遍历里逐层复查用）。 */
    private fun within(f: File): Boolean {
        val rootCanon = runCatching { root.canonicalPath }.getOrNull() ?: return false
        val canon = runCatching { f.canonicalPath }.getOrNull() ?: return false
        return canon == rootCanon || canon.startsWith(rootCanon + File.separator)
    }

    /** 根内相对路径（根本身 = 空串）。 */
    private fun relativeOf(f: File): String {
        val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return f.name
        val canon = runCatching { f.canonicalPath }.getOrNull() ?: return f.name
        if (canon == rootPath) return ""
        val prefix = rootPath + File.separator
        return if (canon.startsWith(prefix)) canon.substring(prefix.length) else f.name
    }

    /**
     * 是否符号链接。递归时不跟进去，避免链接环与「跳出根」。
     *
     * 用 `Files.isSymbolicLink`（API 26 起可用，minSdk 正是 26）而不是比较
     * canonicalPath 与 absolutePath：共享存储的根在不少 ROM 上本身就经过 fuse/绑定，
     * 两者天然不相等，那种写法会把**每个**文件都判成链接，递归直接不下探。
     */
    private fun isSymlink(f: File): Boolean = runCatching {
        java.nio.file.Files.isSymbolicLink(f.toPath())
    }.getOrDefault(true)

    private fun isParentOf(parent: File, child: File): Boolean {
        val p = runCatching { parent.canonicalPath }.getOrNull() ?: return false
        val c = runCatching { child.canonicalPath }.getOrNull() ?: return false
        return c == p || c.startsWith(p + File.separator)
    }

    /**
     * 从 [input] 搬 [len] 字节到 [out]，返回**实际**搬了多少。
     *
     * 返回值不能省：客户端中途断开时 read 会提前返回 -1，如果照 Content-Length 报
     * `bytes`，调用方会以为整份文件写成功了，实际磁盘上是个被截断的文件。
     */
    private fun pump(input: InputStream, out: OutputStream, len: Long): Long {
        val buf = ByteArray(64 * 1024)
        var remaining = len
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
        out.flush()
        return len - remaining
    }

    private fun boolParam(v: String?): Boolean =
        v != null && (v == "1" || v.equals("true", ignoreCase = true))

    private fun clampInt(v: String?, def: Int, min: Int, max: Int): Int {
        val n = v?.toIntOrNull() ?: return def
        return n.coerceIn(min, max)
    }

    /** glob → 锚定正则。只有 `*` `?` 有语义，其余字符按字面量处理。 */
    private fun globToRegex(glob: String): Regex? {
        if (glob.isEmpty() || glob.length > 200) return null
        val sb = StringBuilder("^")
        for (c in glob) {
            when {
                c == '*' -> sb.append("[^/]*")
                c == '?' -> sb.append("[^/]")
                c.code < 0x20 -> return null
                c in REGEX_META -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
        }
        sb.append('$')
        return runCatching { Regex(sb.toString()) }.getOrNull()
    }

    private const val REGEX_META = "\\^\$.|+()[]{}"

    private fun entryJson(f: File, rel: String): JSONObject = JSONObject()
        .put("name", f.name)
        .put("path", rel)
        .put("dir", f.isDirectory)
        .put("size", f.length())
        .put("mtime", f.lastModified())

    private fun okJson(): String = JSONObject().put("ok", true).toString()

    /**
     * 错误体。
     *
     * [reason] 是**机器可读**的失败原因，与 [DshNativeBridge] 的错误体同形。
     * 为什么必须有：`error` 现在跟随应用语言，容器里的 agent 不能再靠匹配中文来判断
     * 「是路径写错了」还是「没授权」—— 那种判断在用户把手机切成英文之后就会失效。
     * 换语言不该改变程序行为。
     */
    private fun errorJson(msg: String, reason: String): String =
        JSONObject().put("ok", false).put("error", msg).put("reason", reason).toString()

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
        409 -> "Conflict"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
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
