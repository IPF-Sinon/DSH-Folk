package me.bmax.apatch.dsh.ext

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.bmax.apatch.BuildConfig
import me.bmax.apatch.dsh.DshRuntime
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * 市场目录（index.json）里的一条扩展记录。
 *
 * 字段与后台生成的目录**一一对应**，不做任何「智能」映射：目录里有什么就是什么，
 * 解析时缺什么由 [ExtensionMarket] 决定是「跳过这一条」还是「整份目录不可用」。
 *
 * [downloadUrl] 是 GitHub Release 上的 .dshx（本质是一个 zip）；[sha256] 是那个文件的
 * 摘要，安装前必须比对 —— 目录和安装包都来自网络，中间任何一环都能被换掉，摘要比对
 * 是「装进来的确实是目录里登记的那一份」的唯一证据。
 */
data class MarketEntry(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val declaredCaps: List<String>,
    val downloadUrl: String,
    val sha256: String,
    val minApi: Int,
)

/**
 * 市场侧的失败。它的 message 就是**可以直接显示给用户的中文原因**（界面不需要再翻一层
 * 文案表），原始异常挂在 cause 上，只有写日志/排查时才用得到堆栈。
 */
private class MarketFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 扩展市场：只做两件事 —— **拉目录**、**装一条**。
 *
 * ## 为什么单独有这一层
 *
 * 安装的落盘、启用位、日志都已经由 [ExtensionRegistry] 在管；市场不该另开一套目录结构，
 * 否则「扩展页」与「市场页」看到的东西会慢慢对不上。所以这里的职责被压到最小：
 * 拿目录 → 下载到缓存 → 校验 → 解压到缓存 → 交给 [ExtensionRegistry.installFromDir] → 清理。
 *
 * ## 几条刻意的取舍（改动之前先读这里）
 *
 * 1. **不引入 okhttp**：这是 app 模块，加依赖要走版本目录（libs.versions.toml）并改构建
 *    脚本，为一次「下载一个 zip」不值得。JDK 自带的 [HttpURLConnection] 够用，代价是要
 *    自己处理超时、跳转与编码 —— 都收在这一个文件里，边界清楚。
 * 2. **下载即算摘要**：sha256 在 [MessageDigest] 里边读边更新，落盘完就有结果，不需要
 *    再整读一遍文件（大包在低端机上多读一遍是实打实的几秒）。
 * 3. **下载/解压都在 cacheDir**：cacheDir 是系统可以随时回收的目录，装扩展这种一次性
 *    中间产物放这里最合适；真正要长期保留的东西由 installFromDir 复制进 filesDir。
 * 4. **解压必须防 zip slip**：安装包里可以出现任意 entry 名，包括带 ../ 的相对路径与
 *    绝对路径。每个目标路径都要 canonical 化后仍然落在解压根目录内，否则中止并清理 ——
 *    不然一个恶意扩展能往 App 私有目录外面写文件（配合 root 通道就是灾难）。
 * 5. **失败用 Result + 中文原因，不抛**：市场是界面上的一块，任何失败都只该显示成一句话；
 *    区分「连不上 / HTTP 码 / 目录格式 / 校验 / 解压 / 安装」是为了让用户知道该找谁。
 *    唯一例外是协程取消：取消必须继续抛出（结构化并发的约定），见各函数的 catch 顺序。
 */
object ExtensionMarket {

    /**
     * 市场目录地址。默认指向扩展仓库 main 分支的 index.json。
     *
     * 放在这里而不是资源/配置：目录地址是**协议的一部分**（后台按这个路径生成），只有改了
     * 仓库结构才需要动它；做成参数是为了让镜像/内测目录能复用同一个实现。
     */
    const val DEFAULT_INDEX_URL = "https://raw.githubusercontent.com/IPF-Sinon/dsh-folk-plugins/main/index.json"

    /** 连接超时：市场页面不该等一个连不上的域名太久。 */
    private const val CONNECT_TIMEOUT_MS = 10_000

    /** 读取超时：安装包可能几 MB，给 30s；再长的等待用户会以为卡死。 */
    private const val READ_TIMEOUT_MS = 30_000

    /** 目录本身很小（几 KB），1MB 已经很宽松；再大多半是被中间层换了内容。 */
    private const val MAX_INDEX_BYTES = 1 * 1024 * 1024

    /** 安装包上限：正常扩展（dex + 资源）远小于它；超过就当中毒处理，别把设备写满。 */
    private const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024

    /** 解压后上限：防压缩炸弹（1KB 的包解出几 GB，把 cache 分区吃光）。 */
    private const val MAX_UNZIPPED_BYTES = 128L * 1024 * 1024

    /** 目录里没有 minApi 时的默认值：1 表示「不限制」，与最早那批扩展的语义一致。 */
    private const val DEFAULT_MIN_API = 1

    /** sha256 只认 64 位小写十六进制（目录里也这么登记；大写由调用方先统一）。 */
    private val SHA256_RE = Regex("^[0-9a-f]{64}$")

    private const val HEX = "0123456789abcdef"

    /** 给一个可辨识的 UA：GitHub 的 raw 与 Release 都按 UA 做限流/统计，匿名请求容易被拦。 */
    private val USER_AGENT = "DSH-Folk/" + BuildConfig.VERSION_NAME + " (Android; ExtensionMarket)"

    /**
     * 拉取并解析市场目录。
     *
     * 做什么：GET 目录地址（默认 [DEFAULT_INDEX_URL]，也可传镜像地址）→ 解析成 [MarketEntry]。
     * 为什么这样设计：
     *  - 内部自己切 [Dispatchers.IO]：调用方是界面，不该为了一个网络请求记住「要换线程」；
     *  - 失败一律返回 [Result.failure] 且 message 是中文原因，**绝不抛**（除了协程取消，
     *    那必须继续传播，否则取消语义就丢了）；
     *  - 单条坏数据只跳过它自己（见 parseIndex），一条坏记录不该废掉整个市场。
     *
     * @return 成功时是目录里的可用条目（可能为空列表 —— 目录合法但还没发布任何扩展）。
     */
    suspend fun fetchIndex(url: String = DEFAULT_INDEX_URL): Result<List<MarketEntry>> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(fetchIndexBlocking(url))
            } catch (ce: CancellationException) {
                // 取消不是「失败」：调用方的协程已经取消，吞掉它会让上游一直挂着
                throw ce
            } catch (e: MarketFailure) {
                DshRuntime.appendLog("[ext] 市场目录拉取失败：" + e.message)
                Result.failure<List<MarketEntry>>(e)
            } catch (e: Exception) {
                // 兜底：意料之外的运行时异常也只能变成一句话，不能把市场页崩掉
                DshRuntime.appendLog("[ext] 市场目录拉取异常：" + reasonOf(e))
                Result.failure<List<MarketEntry>>(MarketFailure("拉取市场目录失败：" + reasonOf(e), e))
            }
        }

    /**
     * 安装一条市场条目。
     *
     * 做什么：校验条目 → 下载 .dshx 到缓存 → 比对 sha256 → 解压到缓存 → 交给
     * [ExtensionRegistry.installFromDir]（它负责复制进 filesDir/extensions 并置「启用」）→
     * 无论成败都清掉临时目录。
     *
     * 为什么把最后一步交出去：启用位、日志文案、目录布局都是扩展系统既有的事实，
     * 市场另写一份就会和扩展页不一致。市场只保证「交给它的目录是干净且校验过的」。
     *
     * 额外拦一条：安装包 manifest 的 id 必须与目录条目的 id 一致。否则一个市场条目可以
     * 借 installFromDir 覆盖**另一个**已安装扩展的目录（条目 id 由后台生成，但安装包是
     * 外部文件，两者之间必须自己核对）。
     *
     * @return 成功时是安装好的 manifest；失败时 message 是可直接显示的中文原因。
     */
    suspend fun install(ctx: Context, entry: MarketEntry): Result<ExtensionManifest> =
        withContext(Dispatchers.IO) {
            // applicationContext：安装可能持续几秒，期间 Activity 完全可能已经销毁
            val appCtx = ctx.applicationContext
            try {
                Result.success(installBlocking(appCtx, entry))
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: MarketFailure) {
                DshRuntime.appendLog("[ext] 市场安装失败：" + entry.id + " —— " + e.message)
                Result.failure<ExtensionManifest>(e)
            } catch (e: Exception) {
                DshRuntime.appendLog("[ext] 市场安装异常：" + entry.id + " —— " + reasonOf(e))
                Result.failure<ExtensionManifest>(MarketFailure("安装失败：" + reasonOf(e), e))
            }
        }

    /**
     * 已安装的 id → version，给「已安装 / 可更新」判断用。
     *
     * 为什么直接读 [ExtensionRegistry.listInstalled] 而不是自己扫盘：市场与扩展页必须看到
     * **同一份**事实（含「清单损坏」的占位记录），否则同一台机器上两页的说法会互相矛盾。
     *
     * 注意：这一步会读文件系统（每个扩展解析一次 manifest），所以**界面侧应在 IO 线程调用**
     * （例如放进已有的 withContext 块）。之所以不做成 suspend，是让只需要一个 Map 的调用方
     * 不必为此额外开协程。
     */
    fun installedVersions(ctx: Context): Map<String, String> =
        runCatching {
            ExtensionRegistry.listInstalled(ctx).associate { it.manifest.id to it.manifest.version }
        }.getOrDefault(emptyMap())

    // ────────────────────── 以下都是阻塞实现（调用方保证已在 IO 线程） ──────────────────────

    /** 拉目录 + 解析。同步版：[fetchIndex] 负责线程与异常边界，这里只管「做对」。 */
    private fun fetchIndexBlocking(rawUrl: String): List<MarketEntry> {
        val url = parseHttpsUrl(rawUrl, "市场目录地址")
        return parseIndex(httpGetText(url))
    }

    /** 下载 → 校验 → 解压 → 安装 → 清理。同步版：[install] 负责线程与异常边界。 */
    private fun installBlocking(ctx: Context, entry: MarketEntry): ExtensionManifest {
        // 1) 先校验条目本身，避免为一条坏数据下载几 MB 之后才报错
        if (!ExtensionLayout.isValidId(entry.id)) {
            throw MarketFailure("市场条目缺少合法的 id（不能用作目录名），拒绝安装：" + entry.id)
        }
        if (!SHA256_RE.matches(entry.sha256.trim().lowercase())) {
            throw MarketFailure("市场条目缺少合法的 sha256（应为 64 位十六进制），拒绝安装")
        }
        val url = parseHttpsUrl(entry.downloadUrl, "扩展下载地址")

        // 2) 临时目录 cacheDir/extension_download/<id>/。id 已由 isValidId 保证不含分隔符
        //    与 ..，所以这里拼路径是安全的；目录名带 id 也便于把残留对上号。
        val tempRoot = File(File(ctx.cacheDir, "extension_download"), entry.id)
        try {
            // 上一次失败可能留下半截文件：先清干净，避免「旧包通过了新校验」这种最坏情况
            if (tempRoot.exists()) tempRoot.deleteRecursively()
            if (!tempRoot.mkdirs() && !tempRoot.isDirectory) {
                throw MarketFailure("安装失败：无法创建临时目录（缓存分区不可写）")
            }
            val archive = File(tempRoot, "package.dshx")
            downloadTo(url, archive, entry.sha256.trim())

            // 解压进单独的子目录：安装包本身（package.dshx）不该被 installFromDir 一起复制进扩展目录
            val unpacked = File(tempRoot, "unpacked")
            if (!unpacked.mkdirs() && !unpacked.isDirectory) {
                throw MarketFailure("安装失败：无法创建解压目录（缓存分区不可写）")
            }
            unzip(archive, unpacked)

            // 3) 清单核对：缺 manifest.json 说明这不是一个扩展包；id 不一致说明它在冒充别的扩展
            val parsed = ExtensionManifest.read(unpacked)
                ?: throw MarketFailure("安装失败：安装包里没有可用的 manifest.json（缺失或字段不合法）")
            if (parsed.id != entry.id) {
                throw MarketFailure(
                    "安装失败：安装包清单 id（" + parsed.id + "）与市场条目 id（" + entry.id + "）不一致"
                )
            }

            // 4) 交给扩展系统：复制到 filesDir、置 enabled=true、写日志都在它内部完成
            val manifest = ExtensionRegistry.installFromDir(ctx, unpacked)
                ?: throw MarketFailure("安装失败：写入扩展目录失败（详见 dsh.log）")
            DshRuntime.appendLog(
                "[ext] 市场安装完成：" + manifest.name + " v" + manifest.version + "（" + manifest.id + "）"
            )
            return manifest
        } finally {
            // 无论成败都清理：成功的部分已经复制进 filesDir，失败的半截包留在缓存里只会占地方
            runCatching { if (tempRoot.exists()) tempRoot.deleteRecursively() }
        }
    }

    /**
     * 解析 index.json。
     *
     * 容错策略（对应「一条坏数据不该废掉整个列表」）：
     *  - 单条条目缺 id / 缺 downloadUrl / sha256 不合法 → **跳过这一条**；
     *  - 同一 id 重复出现 → 只认第一条（列表里出现两个「可安装」的同名项，用户无从选择）；
     *  - 没有 extensions 数组 = 整份目录不可用（结构不对，继续解析没有意义）；
     *  - 数组非空却一条都没通过 = 目录内容有问题，也必须报出来。静默返回空列表会被界面
     *    显示成「市场里没有扩展」，把真正的生成 bug 藏起来。
     */
    private fun parseIndex(body: String): List<MarketEntry> {
        // 有些镜像/CDN 会在 JSON 前面带一个 UTF-8 BOM，org.json 会直接当语法错误 —— 先剥掉
        val json = body.removePrefix("\uFEFF").trim()
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw MarketFailure("市场目录格式不对（JSON 解析失败）", e)
        }
        val arr = root.optJSONArray("extensions")
            ?: throw MarketFailure("市场目录格式不对：缺少 extensions 数组")

        val out = ArrayList<MarketEntry>(arr.length())
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            // id 会被拼进缓存路径与扩展目录名：不合法就跳过（绝不放行 . / .. / 带分隔符的）
            if (!ExtensionLayout.isValidId(id)) continue
            val downloadUrl = o.optString("downloadUrl").trim()
            // 没有下载地址的条目对市场没用：点了「安装」也没东西可拉
            if (downloadUrl.isEmpty()) continue
            val sha256 = o.optString("sha256").trim().lowercase()
            // 没有校验值等于装一个来源不明的 zip：宁可整条不显示，也不给「安装」按钮
            if (!SHA256_RE.matches(sha256)) continue
            if (!seen.add(id)) continue

            out.add(
                MarketEntry(
                    id = id,
                    // name 缺失时退回 id：宁可显示得难看，也不让整条消失（id 本身就是合法可显示串）
                    name = o.optString("name").trim().ifEmpty { id },
                    version = o.optString("version").trim(),
                    description = o.optString("description").trim(),
                    author = o.optString("author").trim(),
                    declaredCaps = capsOf(o),
                    downloadUrl = downloadUrl,
                    sha256 = sha256,
                    // 老目录没有这个字段时按 1 处理（= 不限制），与后台的默认值一致
                    minApi = o.optInt("minApi", DEFAULT_MIN_API),
                )
            )
        }
        if (out.isEmpty() && arr.length() > 0) {
            throw MarketFailure("市场目录里没有一条可用条目（id / downloadUrl / sha256 缺失或不合法）")
        }
        return out
    }

    /** 读 declaredCaps；非数组/空项一律忽略（能力列表只用于信任确认框展示，缺失不算错）。 */
    private fun capsOf(o: JSONObject): List<String> {
        val arr = o.optJSONArray("declaredCaps") ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val cap = arr.optString(i).trim()
            if (cap.isNotEmpty()) out.add(cap)
        }
        return out
    }

    /** GET 一段文本（目录）。超时与 UA 统一在 openConnection 里设，这里只管状态码与读取上限。 */
    private fun httpGetText(url: URL): String {
        val conn = openConnection(url)
        try {
            val code = try {
                conn.responseCode
            } catch (e: Exception) {
                // 连接阶段的失败（DNS / 拒连 / TLS 握手）：原因给用户，细节进 cause
                throw MarketFailure("连不上市场服务器：" + reasonOf(e), e)
            }
            if (code != HttpURLConnection.HTTP_OK) {
                // 404 多半是仓库名/分支写错；5xx 是 GitHub 自己出问题。两者都该让用户看到码
                throw MarketFailure("市场服务器返回 HTTP " + code + "（目录拉取失败）")
            }
            val bytes = try {
                conn.inputStream.use {
                    it.readBounded(MAX_INDEX_BYTES) { MarketFailure("市场目录异常大（超过 1MB），已拒绝") }
                }
            } catch (e: MarketFailure) {
                throw e
            } catch (e: Exception) {
                throw MarketFailure("读取市场目录时网络中断：" + reasonOf(e), e)
            }
            return String(bytes, Charsets.UTF_8)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * 把 url 的内容下载到 dest，**边下边算 sha256**，结束后与 expectedSha256 比对。
     *
     * 为什么边下边算：落盘后再整读一遍是 2 倍 IO，几 MB 的包在低端机上要多花几秒；
     * 而且「读第二遍时文件被改」这种窗口也就不存在了。
     * 不一致时先删文件再报错：调用方（以及用户）不该看到一个「看起来下好了」的包。
     */
    private fun downloadTo(url: URL, dest: File, expectedSha256: String) {
        val conn = openConnection(url)
        try {
            val code = try {
                conn.responseCode
            } catch (e: Exception) {
                throw MarketFailure("连不上扩展下载地址：" + reasonOf(e), e)
            }
            if (code != HttpURLConnection.HTTP_OK) {
                // Release 资源正常是 200（302 已由 HttpURLConnection 跟随）；其它码意味着地址失效
                throw MarketFailure("下载扩展失败：服务器返回 HTTP " + code)
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            try {
                conn.inputStream.use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            total += n
                            if (total > MAX_ARCHIVE_BYTES) {
                                throw MarketFailure(
                                    "安装包异常大（超过 " + (MAX_ARCHIVE_BYTES / 1024 / 1024) + "MB），已中止下载"
                                )
                            }
                            // 关键：先喂摘要再落盘，两者覆盖的字节完全一致
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                        }
                    }
                }
            } catch (e: MarketFailure) {
                throw e
            } catch (e: Exception) {
                throw MarketFailure("下载扩展失败：网络中断（" + reasonOf(e) + "）", e)
            }
            if (total == 0L) throw MarketFailure("下载扩展失败：服务器返回了空文件")
            val actual = digest.digest().toHex()
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                dest.delete()
                throw MarketFailure("校验失败：文件可能被篡改或不完整")
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * 解压 zip 到 destDir，逐条做 **zip slip** 校验。
     *
     * 判据是「canonical 化之后仍在解压根目录内」：只比较字符串（比如判断是否以 ../ 开头）
     * 挡不住 a/../../x、a//../x、绝对路径这些写法，canonical 化会把它们全部归一。目标文件
     * 此时还不存在，但 File.canonicalFile 对不存在的路径同样做词法归一（解析 .. 与多余分隔符）。
     *
     * 同时限制解压总字节数：zip 的压缩比可以到几千倍，一个 1KB 的「炸弹」能把缓存分区写满
     * （那会让整个 App 的图片缓存、更新下载一起失败）。
     */
    private fun unzip(zip: File, destDir: File) {
        val base = destDir.canonicalFile
        val basePrefix = base.path + File.separator
        var written = 0L
        try {
            ZipInputStream(BufferedInputStream(zip.inputStream())).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val target = File(base, entry.name).canonicalFile
                    // 越界（含绝对路径被规范化到根目录之外的情形）→ 立刻中止
                    if (target != base && !target.path.startsWith(basePrefix)) {
                        throw MarketFailure("解压失败：安装包内含越界路径（" + entry.name + "），已中止")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out ->
                            val buf = ByteArray(16 * 1024)
                            while (true) {
                                val n = zis.read(buf)
                                if (n <= 0) break
                                written += n
                                if (written > MAX_UNZIPPED_BYTES) {
                                    throw MarketFailure("解压失败：解压后体积超过上限（可能是压缩炸弹）")
                                }
                                out.write(buf, 0, n)
                            }
                        }
                    }
                    zis.closeEntry()
                }
            }
        } catch (e: MarketFailure) {
            throw e
        } catch (e: Exception) {
            // ZipException / IOException 都落这里：包被截断、根本不是 zip、或磁盘写不进去
            throw MarketFailure("解压失败：安装包已损坏（" + reasonOf(e) + "）", e)
        }
    }

    /**
     * 建连并统一超时 / UA / 跳转设置。
     *
     * 两个超时都要设：只设 connectTimeout 时，连上之后对方不回数据会一直挂着（默认 0 =
     * 无限等待），市场页看起来就是「永远在转圈」。
     */
    private fun openConnection(url: URL): HttpURLConnection {
        val conn = try {
            url.openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw MarketFailure("连接地址不合法：" + url, e)
        }
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        // GitHub Release 的下载会 302 到 objects.githubusercontent.com；默认跟随，
        // 这里显式写出来，免得以后有人以为「没设就是没跳转」而自己重写一遍重定向逻辑
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "application/json, application/octet-stream, */*")
        return conn
    }

    /**
     * 校验并解析成 [URL]，只收 **https**。
     *
     * 为什么连目录地址也强制 https：目录是「安装来源的源头」，明文 HTTP 下中间人可以连
     * 目录带 sha256 一起换掉（换目录 + 换包，校验全绿）。这一条把整条信任链钉在 TLS 上。
     */
    private fun parseHttpsUrl(raw: String, what: String): URL {
        val s = raw.trim()
        if (s.isEmpty()) throw MarketFailure(what + "为空")
        val url = try {
            URL(s)
        } catch (e: Exception) {
            throw MarketFailure(what + "不合法：" + s, e)
        }
        if (url.protocol != "https") throw MarketFailure(what + "必须是 https：" + s)
        return url
    }

    /** 读满 max 字节；若还有第 max+1 个字节就抛 onTooLarge（截断的 JSON 比「太大了」更难查）。 */
    private fun InputStream.readBounded(max: Int, onTooLarge: () -> MarketFailure): ByteArray {
        val out = ByteArrayOutputStream(minOf(max, 64 * 1024))
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (total < max) {
            val n = read(buf, 0, minOf(buf.size, max - total))
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        if (read() >= 0) throw onTooLarge()
        return out.toByteArray()
    }

    /** 字节数组转小写十六进制（与目录里登记的 sha256 写法一致）。 */
    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /** 异常的可读原因：message 为空时（很多 IOException 都是）退回类名，别让日志出现空串。 */
    private fun reasonOf(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
}
