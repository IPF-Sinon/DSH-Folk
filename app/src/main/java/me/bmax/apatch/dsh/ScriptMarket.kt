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
        /** 人在浏览器里看的那一页。 */
        val pageUrl: String,
    )

    /** 失败原因：界面按这个翻文案，细节进日志（`url` 与状态码）。 */
    enum class Fail { NETWORK, SERVER, CONTENT, SIZE }

    class MarketException(val fail: Fail, val code: Int = 0) : Exception("market:" + fail + ":" + code)

    private const val TAG = "ScriptMarket"
    private const val API = "https://api.greasyfork.org"
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
