package me.bmax.apatch.dsh

import android.content.Context
import android.util.Log
import me.bmax.apatch.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * GreasyFork 脚本市场：只做两件事 —— **搜**、**取源码**。装进哪、怎么开关都由
 * [Userscripts] 那一套管（市场只是喂给它一段 `.user.js` 正文）。
 *
 * ## 形状（都是实测出来的，别照着「想当然的 API」改）
 *
 * - 入口用 **`api.greasyfork.org`**：`greasyfork.org` 上每个 `.json` 都是 308 跳转，
 *   而 `HttpURLConnection` 对 308 的支持随版本而变，不值得赌。
 * - 响应有**两种**：`{"model":…,"query":[…]}`，以及翻过 2000 条窗口时的**裸 `[]`**。
 *   两种都要认（[parseHits]）。
 * - 安装地址取响应里的 `code_url`（已经是 `https://update.greasyfork.org/…`）；服务端
 *   **忽略** URL 里的 slug，所以别自己拼。
 * - 结果里的名字/说明是按 **locale** 本地化的（服务端把 `@name:zh-CN` 之类拼进去），
 *   locale 走 URL **路径**，写错会让整页 406 —— 所以只发 [LOCALES] 里认得的，
 *   其余一律 `en`。
 * - JSON 不压缩、不需要鉴权；`update.greasyfork.org` 那边会协商 gzip，交给
 *   `HttpURLConnection` 默认处理即可（别自己设 `Accept-Encoding`）。robots 只要求
 *   `Crawl-delay: 1`，我们一次搜索一页 20 条，够客气；UA 仍给一个可辨识的。
 */
internal object ScriptMarket {

    /** 一条搜索结果。只留界面真的要显示的字段 —— 解析成的对象越小，越不容易悄悄错。 */
    data class Hit(
        val id: Long,
        val name: String,
        val description: String,
        val version: String,
        val author: String,
        val installs: Long,
        /** `code_updated_at` 的日期部分（`YYYY-MM-DD`），空串表示没给。 */
        val updated: String,
        /** 直接取 `.user.js` 的地址（来自响应的 `code_url`）。 */
        val codeUrl: String,
        /** 人去浏览器 / 应用内网页页看的那一页（脚本的作者、说明、评分都在那儿）。 */
        val pageUrl: String,
    )

    /** 失败原因：界面按这个翻文案，细节进日志（`url` 与状态码）。 */
    enum class Fail { NETWORK, SERVER, CONTENT, SIZE }

    class MarketException(val fail: Fail, val code: Int = 0) : Exception("market:" + fail + ":" + code)

    private const val TAG = "ScriptMarket"
    private const val API = "https://api.greasyfork.org"

    /** 脚本正文的规范地址前缀；[normalizeInstallUrl] 用它把脚本页链接拼成可直接取正文的地址。 */
    private const val UPDATE_BASE = "https://update.greasyfork.org"

    /**
     * 镜像站**浏览入口**。
     *
     * 它是一张**静态导航页**（GitHub Pages），不是 JSON API，也没有镜像的脚本正文：实测
     * `…/scripts/<id>/x.user.js`、`…/en/scripts.json` 全是 404，页面里只有一个跳去第三方站点的
     * 链接。所以这里只用它做「主源失败时的浏览入口」—— 由界面用一个应用内网页页打开
     * （见 ScriptMarketScreen 的 `openMirrorPage`），绝不进我们的 HTTP 客户端、更不拿它装脚本。
     * 正文层面的镜像回落**做不到**：没有可改写的镜像路径。
     */
    internal const val MIRROR_INDEX = "https://greasyfork-mirror.github.io/index.html"

    /** 一页多少条；界面用它判断「还有下一页」——两处必须一致，所以是同一个常量。 */
    internal const val PER_PAGE = 20

    /** 单次响应上限：正常脚本 30KB 上下，2MB 已经很宽松，再大多半是被中间层换了内容。 */
    private const val MAX_BYTES = 2 * 1024 * 1024

    /**
     * GreasyFork 认得的 locale（写错 406）。注意它用的是 `zh-CN` / `pt-BR` 这种带地区的写法。
     */
    private val LOCALES = setOf(
        "en", "de", "es", "fr", "hu", "it", "ja", "ko", "nl", "pl",
        "pt", "ro", "ru", "sk", "sr", "sv", "tr", "uk", "vi", "zh",
    )

    /** 当前界面语言对应的 locale 路径；不认得的一律 `en`。 */
    internal fun localePath(ctx: Context): String {
        val lang = runCatching {
            ctx.resources.configuration.locales[0].language.lowercase()
        }.getOrDefault("en")
        if (lang !in LOCALES) return "en"
        return when (lang) {
            "pt" -> "pt-BR"
            "zh" -> "zh-CN"
            else -> lang
        }
    }

    /** 搜索一页（20 条）。[page] 从 1 起。抛 [MarketException] 由界面翻文案。 */
    fun search(ctx: Context, query: String, page: Int): List<Hit> {
        val url = URL(
            "$API/" + localePath(ctx) + "/scripts.json?q=" +
                URLEncoder.encode(query, "UTF-8") +
                "&page=" + page.coerceAtLeast(1) +
                "&per_page=$PER_PAGE",
        )
        return parseHits(httpGet(ctx, url))
    }

    /**
     * 热门一页（「精选」的脚本侧来源）：同一个 `scripts.json` 外壳，加 `sort=total_installs`
     * 要「按安装量排」。q 留空是有意的 —— 服务端把它当 `term:"*"`（看返回的 `term` 字段）。
     *
     * 实测（2026-10）返回 200、`order.total_installs = desc`，结果形状与搜索**完全一样**，
     * 所以直接走 [parseHits]：热门与搜索共用一份解析，不会各自漂。
     */
    fun popular(ctx: Context, page: Int): List<Hit> {
        val url = URL(
            "$API/" + localePath(ctx) + "/scripts.json?q=&sort=total_installs" +
                "&page=" + page.coerceAtLeast(1) +
                "&per_page=$PER_PAGE",
        )
        return parseHits(httpGet(ctx, url))
    }

    /** `https://update.greasyfork.org/scripts/<id>/<name>.user.js`（name 里不含 `/`）。 */
    private val CODE_URL_RE = Regex(
        "^https://update\\.greasyfork\\.org/scripts/\\d+/[^/?#]+\\.user\\.js$"
    )

    /** 脚本页：`greasyfork.org[/<locale>]/scripts/<id>[-<slug>][?query][#frag]`（https 由调用方认）。 */
    private val PAGE_URL_RE = Regex(
        "^https?://greasyfork\\.org/(?:[A-Za-z][A-Za-z0-9-]*/)?scripts/(\\d+)(?:-[^/?#]*)?/?(?:[?#][^\\s]*)?$"
    )

    /**
     * 把用户粘贴的 greasyfork 链接归一成**可直接取正文**的地址；认不出返回 null。
     *
     * 纯函数（只做字符串判断，显式 regex），收两种形状（都实测过）：
     *  - 脚本页 `greasyfork.org/scripts/<id>-<slug>`（也认带 locale 的
     *    `/zh-CN/scripts/…`）→ `https://update.greasyfork.org/scripts/<id>/script.user.js`；
     *  - 正文地址 `https://update.greasyfork.org/scripts/<id>/<任意名>.user.js` → 原样返回。
     *
     * 为什么脚本页那条敢自己拼：服务端**忽略** URL 里的 slug/文件名 —— 实测把名字换成 `x`
     * 照样回同一段正文。所以不必再打一次 API 就能拿到正文地址。
     *
     * 只认 https 的 greasyfork 域：归一结果接下来交给 [fetch]，而 [fetch] 只收 `https` +
     * `*.greasyfork.org`；在这里先挡一道，用户看到的是「链接认不出」而不是一条网络失败。
     * http 的脚本页会**升到 https**（旧书签里常见），但只拼到 greasyfork 自己的域上，
     * 不会把任意 URL 拉进来。
     */
    internal fun normalizeInstallUrl(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        // 正文地址：已经是要取的那个东西，原样收
        if (CODE_URL_RE.matches(s)) return s
        val id = PAGE_URL_RE.matchEntire(s)?.groupValues?.get(1) ?: return null
        return "$UPDATE_BASE/scripts/$id/script.user.js"
    }

    /**
     * 取一段 `.user.js` 正文。
     *
     * 两处硬约束：**https** + **greasyfork 的域**。`code_url` 来自网络响应，网络上
     * 任何一环被换掉都可能把地址指到别处；限定域之后，「装脚本」最多是把
     * greasyfork 上的东西装进来，而不是把一个任意 URL 拉进页面。
     */
    fun fetch(ctx: Context, codeUrl: String): String {
        val url = runCatching { URL(codeUrl) }.getOrElse { throw MarketException(Fail.CONTENT) }
        val host = url.host.orEmpty().lowercase()
        val hostOk = host == "greasyfork.org" || host.endsWith(".greasyfork.org")
        if (url.protocol != "https" || !hostOk) {
            Log.w(TAG, "拒绝非 greasyfork 的安装地址: $codeUrl")
            throw MarketException(Fail.CONTENT)
        }
        val body = httpGet(ctx, url)
        // 服务端出错时会回 HTML / JSON（而不是脚本）：没有元数据块就不是 UserScript，
        // 装进去也只是一段跑不起来的 JS，不如当场说清楚。
        if (!body.contains("==UserScript==")) throw MarketException(Fail.CONTENT)
        return body
    }

    /**
     * 解析搜索结果。
     *
     * `internal` 是为了让门禁能对着**真实响应形状**跑一遍：两种外壳（对象 / 裸数组）、
     * 缺字段、`code_url` 为空都要能落到「跳过这一条」而不是崩。
     */
    internal fun parseHits(body: String): List<Hit> {
        val root = runCatching { JSONTokener(body).nextValue() }
            .getOrElse { throw MarketException(Fail.CONTENT) }
        val arr: JSONArray = when (root) {
            is JSONObject -> root.optJSONArray("query") ?: JSONArray()
            // 翻过 2000 条窗口时服务端回的就是**裸数组**（实测）
            is JSONArray -> root
            else -> throw MarketException(Fail.CONTENT)
        }
        val hits = ArrayList<Hit>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val code = o.optString("code_url")
            // 没有安装地址的条目对市场没用：点了「安装」也没东西可拉
            if (code.isBlank()) continue
            hits += Hit(
                id = o.optLong("id"),
                name = o.optString("name").ifBlank { "script" },
                description = o.optString("description"),
                version = o.optString("version"),
                author = authorOf(o),
                installs = o.optLong("total_installs"),
                updated = o.optString("code_updated_at").take(10),
                codeUrl = code,
                pageUrl = o.optString("url"),
            )
        }
        return hits
    }

    /** 作者：API 没有 `author` 字段，只有 `users[]`（取第一个）与 `namespace`。 */
    private fun authorOf(o: JSONObject): String =
        o.optJSONArray("users")?.optJSONObject(0)?.optString("name").orEmpty()
            .ifBlank { o.optString("namespace") }

    /**
     * GET 一段文本。超时短、响应上限硬：市场是**页面上的一块**，不该让网络把它按住。
     */
    private fun httpGet(ctx: Context, url: URL): String {
        val conn = runCatching { url.openConnection() as HttpURLConnection }.getOrElse {
            throw MarketException(Fail.NETWORK)
        }
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 20_000
            conn.setRequestProperty(
                "User-Agent",
                "DSH-Folk/" + BuildConfig.VERSION_NAME + " (Android)",
            )
            conn.setRequestProperty("Accept", "application/json, text/javascript, */*")
            val code = runCatching { conn.responseCode }.getOrElse {
                throw MarketException(Fail.NETWORK)
            }
            if (code !in 200..299) throw MarketException(Fail.SERVER, code)
            val bytes = runCatching { conn.inputStream.use { it.readBounded(MAX_BYTES) } }
                .getOrElse { e ->
                    if (e is MarketException) throw e
                    throw MarketException(Fail.NETWORK)
                }
            return String(bytes, Charsets.UTF_8)
        } catch (e: MarketException) {
            Log.w(TAG, "market GET 失败: $url (${e.fail})")
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "market GET 异常: $url", e)
            throw MarketException(Fail.NETWORK)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 读满 [max] 字节；还有第 max+1 个字节就算超限（截断的 JS 比报错更难查）。 */
    private fun InputStream.readBounded(max: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(max, 64 * 1024))
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (total < max) {
            val n = read(buf, 0, minOf(buf.size, max - total))
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        if (read() >= 0) throw MarketException(Fail.SIZE)
        return out.toByteArray()
    }
}
