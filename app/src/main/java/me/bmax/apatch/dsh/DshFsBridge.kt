package me.bmax.apatch.dsh

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
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
                    respondJson(s, 403, errorJson("仅允许回环访问"))
                    return@runCatching
                }
                if (!tokenMatches(headers[HEADER_TOKEN.lowercase()])) {
                    responded = true
                    respondJson(s, 403, errorJson("token 不匹配"))
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
                    else -> 404 to errorJson("未知端点 $method $path")
                }
                // null = 处理函数自己写完了响应体（read 的二进制流）
                responded = true
                result?.let { respondJson(s, it.first, it.second) }
            }.onFailure { e ->
                Log.w(TAG, "文件桥处理失败: ${e.message}")
                // 之前只记日志不回话，客户端只能看到 socket EOF，分不清「服务没起」和「请求崩了」
                if (!responded) {
                    runCatching { respondJson(s, 500, errorJson("内部错误：${e.message}")) }
                }
            }
        }
    }

    private fun dispatchNative(
        method: String,
        path: String,
        params: Map<String, String>,
    ): Pair<Int, String>? {
        val ctx = appCtx ?: return 500 to errorJson("原生桥未初始化")
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
        val target = resolve(params["path"]) ?: return 400 to errorJson("非法路径")
        if (!target.exists()) return 404 to errorJson("路径不存在")
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
        val target = resolve(rel) ?: return 400 to errorJson("非法路径")
        if (!target.exists()) return 404 to errorJson("路径不存在")
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
        val target = resolve(params["path"]) ?: return 400 to errorJson("非法路径")
        if (!target.exists()) return 404 to errorJson("路径不存在")
        if (target.isDirectory) return 400 to errorJson("是目录")

        val size = target.length()
        val offset = params["offset"]?.let {
            it.toLongOrNull() ?: return 400 to errorJson("offset 不是整数")
        } ?: 0L
        if (offset < 0) return 400 to errorJson("offset 不能为负")
        if (offset > size) return 400 to errorJson("offset 超过文件长度 $size")
        val requested = params["length"]?.let {
            it.toLongOrNull() ?: return 400 to errorJson("length 不是整数")
        }
        if (requested != null && requested < 0) return 400 to errorJson("length 不能为负")
        val count = if (requested == null) size - offset else minOf(requested, size - offset)
        if (count > MAX_READ_BYTES) {
            return 400 to errorJson("单次最多读 64MB（本次 $count），请用 offset/length 分段")
        }

        val raf = runCatching { RandomAccessFile(target, "r") }.getOrElse { e ->
            return 500 to errorJson("打开失败：${e.message}")
        }
        raf.use { f ->
            runCatching { f.seek(offset) }.getOrElse { e ->
                return 500 to errorJson("定位失败：${e.message}")
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
        val target = resolve(rel) ?: return 400 to errorJson("非法路径")
        if (target.isDirectory) return 400 to errorJson("是目录")
        val parent = target.parentFile ?: return 400 to errorJson("非法路径")
        val len = headers["content-length"]?.toLongOrNull()
            ?: return 400 to errorJson("缺 Content-Length")
        if (len < 0) return 400 to errorJson("Content-Length 非法")
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
        if (!ok) return 500 to errorJson("写入失败")
        if (written != len) {
            return 400 to errorJson("请求体不完整：声明 $len 字节，实收 $written")
        }
        return 200 to JSONObject().put("ok", true).put("path", rel)
            .put("append", append).put("bytes", written).toString()
    }

    private fun handleMkdir(rel: String?): Pair<Int, String>? {
        if (!checkStorageAccess()) return storageDenied()
        val target = resolve(rel) ?: return 400 to errorJson("非法路径")
        if (target.isFile) return 400 to errorJson("同名文件已存在")
        val ok = target.isDirectory || target.mkdirs()
        return if (ok) 200 to okJson() else 500 to errorJson("创建失败")
    }

    /**
     * 移动。
     *
     * `renameTo` 跨挂载点必然失败（共享存储上 Android/data 与主区常常就是不同挂载），
     * 所以失败后回退到「复制 + 删除」，而不是直接把失败甩给调用方。
     */
    private fun handleMove(src: String?, dst: String?): Pair<Int, String>? {
        if (!checkStorageAccess()) return storageDenied()
        val s = resolve(src) ?: return 400 to errorJson("非法 src")
        val d = resolve(dst) ?: return 400 to errorJson("非法 dst")
        if (!s.exists()) return 404 to errorJson("src 不存在")
        if (isParentOf(s, d)) return 400 to errorJson("dst 在 src 内部")
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
            500 to errorJson("已复制到 dst，但删除 src 失败")
        }
    }

    private fun handleCopy(params: Map<String, String>): Pair<Int, String>? {
        if (!checkStorageAccess()) return storageDenied()
        val s = resolve(params["src"]) ?: return 400 to errorJson("非法 src")
        val d = resolve(params["dst"]) ?: return 400 to errorJson("非法 dst")
        if (!s.exists()) return 404 to errorJson("src 不存在")
        if (isParentOf(s, d)) return 400 to errorJson("dst 在 src 内部，会无限递归")
        val overwrite = boolParam(params["overwrite"])
        if (!overwrite && d.exists()) return 400 to errorJson("dst 已存在（加 overwrite=1 覆盖）")
        return copyRecursive(s, d, overwrite)
    }

    private fun handleDelete(params: Map<String, String>): Pair<Int, String>? {
        if (!checkStorageAccess()) return storageDenied()
        val target = resolve(params["path"]) ?: return 400 to errorJson("非法路径")
        // 不允许删根：那是「清空用户整个共享存储」，绝不该是一条 HTTP 请求能做到的事
        if (relativeOf(target).isEmpty()) return 400 to errorJson("不能删除根目录")
        if (!target.exists()) return 200 to okJson()
        val recursive = boolParam(params["recursive"])

        if (target.isDirectory && !recursive && (target.listFiles()?.isNotEmpty() == true)) {
            return 400 to errorJson("目录非空（加 recursive=1 递归删除）")
        }
        if (target.isDirectory && recursive) {
            val n = countRecursive(target)
            if (n < 0) return 400 to errorJson("条目超过 $MAX_RECURSIVE_ENTRIES，拒绝递归删除")
        }
        val ok = deleteRecursive(target)
        return if (ok) 200 to okJson() else 500 to errorJson("删除失败")
    }

    /**
     * 按名字 glob 查找。
     *
     * 只认 `*` 和 `?`，其余字符全部按字面量转义 —— 不能让调用方的输入变成一个可控正则
     * （灾难性回溯在这个进程里就是一次 ANR）。
     */
    private fun handleFind(params: Map<String, String>): Pair<Int, String>? {
        if (!checkStorageAccess()) return storageDenied()
        val base = resolve(params["path"]) ?: return 400 to errorJson("非法路径")
        if (!base.exists()) return 404 to errorJson("路径不存在")
        if (!base.isDirectory) return 400 to errorJson("不是目录")
        val glob = params["glob"] ?: return 400 to errorJson("缺 glob")
        val re = globToRegex(glob) ?: return 400 to errorJson("glob 非法")
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
        val target = resolve(rel) ?: return 400 to errorJson("非法路径")
        val dir = if (target.isDirectory) target else target.parentFile ?: root
        val st = runCatching { StatFs(dir.absolutePath) }.getOrElse { e ->
            return 500 to errorJson("statfs 失败：${e.message}")
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
        if (!within(src) || !within(dst)) return 400 to errorJson("路径越界")
        if (src.isDirectory && countRecursive(src) < 0) {
            return 400 to errorJson("条目超过 $MAX_RECURSIVE_ENTRIES，拒绝递归复制")
        }
        var files = 0
        runCatching {
            fun rec(s: File, d: File) {
                if (!within(s) || !within(d)) throw java.io.IOException("路径越界：${s.name}")
                if (s.isDirectory && !isSymlink(s)) {
                    if (!d.isDirectory && !d.mkdirs()) throw java.io.IOException("建目录失败：${d.name}")
                    s.listFiles()?.forEach { child -> rec(child, File(d, child.name)) }
                } else {
                    if (d.exists()) {
                        if (!overwrite) throw java.io.IOException("已存在：${d.name}")
                        if (!d.delete()) throw java.io.IOException("覆盖失败：${d.name}")
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
            return 500 to errorJson("复制失败：${e.message}（已复制 $files 个文件）")
        }
        return 200 to JSONObject().put("ok", true).put("files", files).toString()
    }

    // ────────────────────────── 工具 ──────────────────────────

    /** 接口版本；容器侧 CLI 用它判断宿主是否支持新子命令。 */
    private const val API_VERSION = 2

    private fun checkStorageAccess(): Boolean = Environment.isExternalStorageManager()

    private fun storageDenied(): Pair<Int, String> =
        403 to errorJson("未授予「所有文件访问」，请在系统设置中开启")

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

    private fun errorJson(msg: String): String =
        JSONObject().put("ok", false).put("error", msg).toString()

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
