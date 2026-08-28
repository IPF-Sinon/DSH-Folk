package me.bmax.apatch.dsh

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/**
 * 运行时下载源解析与测速（移植 DSHM SourceManager 的思路，简化为三候选 + 自定义）。
 *
 * 逻辑：
 * - 用户固定源 → 原样返回；
 * - auto → 并行测延迟（metadata.json 小文件），对最优的做 1MB Range 吞吐测速，
 *   按「估算下载 100MB 耗时」评分（速度为主、延迟兜底），结果缓存 24h。
 */
object DshSource {
    const val SOURCE_AUTO = "auto"
    const val SOURCE_GITHUB = "github"
    const val SOURCE_GHPROXY_CF = "ghproxy_cf"
    const val SOURCE_GHPROXY_AXISNOW = "ghproxy_axisnow"
    const val SOURCE_CUSTOM = "custom"

    private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L
    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val READ_TIMEOUT_MS = 3_000
    private const val SPEED_PROBE_BYTES = 1L * 1024 * 1024
    private const val SCORE_REF_BYTES = 100L * 1024 * 1024

    private const val KEY_SOURCE = "download_source"
    private const val KEY_CUSTOM_URL = "custom_meta_url"
    private const val KEY_AUTO_SOURCE = "auto_source"
    private const val KEY_AUTO_SOURCE_AT = "auto_source_at"

    /** DSH-Folk 运行时发布位置：runtime-latest tag 的 metadata.json 常驻最新。 */
    const val META_URL =
        "https://github.com/IPF-Sinon/DSH-Folk/releases/download/runtime-latest/metadata.json"

    /** 吞吐测速目标（Range 拉前 1MB）。 */
    private const val SPEED_PROBE_URL =
        "https://github.com/IPF-Sinon/DSH-Folk/releases/download/runtime-latest/rootfs.tar.gz"

    fun proxyPrefix(source: String): String = when (source) {
        SOURCE_GHPROXY_CF -> "https://v6.gh-proxy.org/"
        SOURCE_GHPROXY_AXISNOW -> "https://axisnow.gh-proxy.org/"
        else -> ""
    }

    fun displayName(source: String): String = when (source) {
        SOURCE_AUTO -> "自动（测速选优）"
        SOURCE_GITHUB -> "GitHub 直连"
        SOURCE_GHPROXY_CF -> "gh-proxy (CF)"
        SOURCE_GHPROXY_AXISNOW -> "gh-proxy (AxisNow)"
        SOURCE_CUSTOM -> "自定义镜像"
        else -> source
    }

    data class SpeedResult(val source: String, val latencyMs: Long, val speedKBps: Double = 0.0) {
        /** 估算下载 100MB 的耗时（毫秒）；速度未测得时仅按延迟粗排。 */
        val estimatedMs: Long
            get() = if (speedKBps > 0.0) {
                latencyMs + (SCORE_REF_BYTES / 1024.0 / speedKBps * 1000.0).toLong()
            } else {
                latencyMs + SCORE_REF_BYTES / 1024 / 1024 * 60_000L
            }
    }

    @Volatile private var memCache: String? = null
    @Volatile private var memCachedAt: Long = 0L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)

    fun setting(ctx: Context): String = prefs(ctx).getString(KEY_SOURCE, SOURCE_AUTO) ?: SOURCE_AUTO

    fun setSetting(ctx: Context, source: String) {
        prefs(ctx).edit().putString(KEY_SOURCE, source).apply()
        memCache = null
    }

    fun customMetaUrl(ctx: Context): String = prefs(ctx).getString(KEY_CUSTOM_URL, "") ?: ""

    fun setCustomMetaUrl(ctx: Context, url: String) {
        prefs(ctx).edit().putString(KEY_CUSTOM_URL, url.trim()).apply()
    }

    /** 实际生效的源（auto → 测速最优源，命中缓存不重测）。 */
    fun resolve(ctx: Context): String {
        val s = setting(ctx)
        if (s != SOURCE_AUTO) return s
        return cachedAuto(ctx) ?: pickBest(speedTest(), ctx)
    }

    /** 当前生效的 metadata.json 地址。 */
    fun effectiveMetaUrl(ctx: Context): String {
        val resolved = resolve(ctx)
        if (resolved == SOURCE_CUSTOM) {
            val custom = customMetaUrl(ctx)
            if (custom.isNotEmpty()) return custom
        }
        return proxyPrefix(resolved) + META_URL
    }

    private fun cachedAuto(ctx: Context): String? {
        val now = System.currentTimeMillis()
        memCache?.let { if (now - memCachedAt < CACHE_TTL_MS) return it }
        val at = prefs(ctx).getLong(KEY_AUTO_SOURCE_AT, 0L)
        if (at > 0 && now - at < CACHE_TTL_MS) {
            prefs(ctx).getString(KEY_AUTO_SOURCE, null)?.let {
                memCache = it
                memCachedAt = now
                return it
            }
        }
        return null
    }

    fun pickBest(results: List<SpeedResult>, ctx: Context): String {
        val picked = results.minByOrNull { it.estimatedMs }?.source ?: SOURCE_GHPROXY_AXISNOW
        val now = System.currentTimeMillis()
        memCache = picked
        memCachedAt = now
        prefs(ctx).edit()
            .putString(KEY_AUTO_SOURCE, picked)
            .putLong(KEY_AUTO_SOURCE_AT, now)
            .apply()
        return picked
    }

    /** 三候选源全部测一遍（延迟 + 对最优两个测吞吐）。同步阻塞，调用方放 IO 线程。 */
    fun speedTest(): List<SpeedResult> {
        val candidates = listOf(
            SOURCE_GHPROXY_AXISNOW to "https://axisnow.gh-proxy.org/$META_URL",
            SOURCE_GHPROXY_CF to "https://v6.gh-proxy.org/$META_URL",
            SOURCE_GITHUB to META_URL,
        )
        val latency = candidates.map { (src, url) -> SpeedResult(src, probeLatency(url)) }
        val top = latency.sortedBy { it.latencyMs }.take(2).map { it.source }.toSet()
        return latency.map { r ->
            if (r.source !in top || r.latencyMs >= Long.MAX_VALUE / 4) r
            else r.copy(speedKBps = probeSpeed(proxyPrefix(r.source) + SPEED_PROBE_URL))
        }
    }

    /** 延迟探测：拉 metadata.json 头部。不可达返回一个极大值参与排序。 */
    private fun probeLatency(url: String): Long {
        val start = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            if (conn.responseCode !in 200..299) return Long.MAX_VALUE / 4
            conn.inputStream.use { it.read(ByteArray(512)) }
            System.currentTimeMillis() - start
        } catch (e: Exception) {
            Long.MAX_VALUE / 4
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 吞吐探测：Range 拉前 1MB，返回 KB/s（失败 0）。 */
    private fun probeSpeed(url: String): Double {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = 8_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Range", "bytes=0-${SPEED_PROBE_BYTES - 1}")
            if (conn.responseCode !in 200..299) return 0.0
            val start = System.currentTimeMillis()
            var total = 0L
            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                while (total < SPEED_PROBE_BYTES) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                }
            }
            val dtSec = (System.currentTimeMillis() - start) / 1000.0
            // 必须拿满一整块才算：服务端提前断流时 total 很小、dtSec 也很小，
            // 相除会得出一个虚高的速度（100KB / 0.05s = 2 MB/s），让这个源赢下测速
            // 然后在真正下载 130MB 时暴露。拿不满就当测速失败（0 = 仅按延迟排序）。
            if (dtSec <= 0.0 || total < SPEED_PROBE_BYTES) 0.0 else total / 1024.0 / dtSec
        } catch (e: Exception) {
            0.0
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
