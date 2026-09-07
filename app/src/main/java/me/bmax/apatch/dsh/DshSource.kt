package me.bmax.apatch.dsh

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import me.bmax.apatch.R

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

    /** 运行时发布位置（滚动 tag runtime-latest；资产名按架构区分）。 */
    private const val RUNTIME_BASE =
        "https://github.com/IPF-Sinon/DSH-Folk/releases/download/runtime-latest/"

    /**
     * 本机要用的运行时架构。
     *
     * **判据是「本 APK 里的原生库是什么架构」，不是 `SUPPORTED_ABIS` 里有什么。**
     *
     * 1.7.6 用「列表里有 arm64-v8a 就选 arm64」，在带 arm 转译层的 x86_64 设备上直接
     * 错了：那类设备（真实案例 OPPO PJJ110，Intel i5-10400 + houdini）报的
     * `SUPPORTED_ABIS = [x86_64, arm64-v8a, x86]`，于是 x86_64 包下载了 arm64 rootfs，
     * 而 APK 里的 proot 是 x86_64 原生二进制、**不经过转译层**，一执行 arm64 的
     * `ld-linux-aarch64.so.1` 就 SIGILL（日志里的 `terminated with signal 4`）。
     *
     * 转译层只服务于 Dalvik/JNI 里的 arm64 .so，救不了我们自己 fork 出来的 proot 子进程 ——
     * 所以能跑的 rootfs 架构必须与 **APK 内 proot 的架构** 一致，而后者由构建时的
     * ABI split 唯一确定。用 `Build.SUPPORTED_ABIS` 猜是错的，用装了哪个包才是对的。
     *
     * 实现上读 `nativeLibraryDir` 里 `libproot.so` 的 ELF `e_machine`：那就是「本机
     * 真正会执行的那个二进制」自己的架构，没有比它更权威的来源。读不到时（文件缺失、
     * 权限异常）退回按 `SUPPORTED_ABIS` 的**第一项**判断 —— 首项是设备的原生 ABI，
     * 转译 ABI 总排在后面。
     *
     * 返回值与 metadata.json 的 `arch` 字段、`Build.SUPPORTED_ABIS` 命名对齐
     * （`arm64-v8a` / `x86_64`）。
     */
    fun runtimeArch(): String = archFromProot() ?: archFromPrimaryAbi()

    /** ELF e_machine：183 = AArch64，62 = x86-64。 */
    private const val EM_AARCH64 = 183
    private const val EM_X86_64 = 62

    @Volatile
    private var cachedArch: String? = null

    /**
     * 读 APK 提取出来的 `libproot.so` 的 ELF 头，取它的真实架构。
     *
     * 只读前 20 字节（magic + e_machine），成本可忽略；结果缓存，因为它在一个安装
     * 生命周期内不可能变。
     */
    private fun archFromProot(): String? {
        cachedArch?.let { return it }
        // apApp 是 lateinit：极早期（Application.onCreate 之前）访问会抛，runCatching 兜住
        val dir = runCatching { me.bmax.apatch.apApp.applicationInfo.nativeLibraryDir }
            .getOrNull() ?: return null
        val f = java.io.File(dir, "libproot.so")
        val machine = runCatching {
            java.io.FileInputStream(f).use { s ->
                val head = ByteArray(20)
                if (s.read(head) < 20) return@runCatching null
                // 0x7f 'E' 'L' 'F'
                if (head[0] != 0x7f.toByte() || head[1] != 'E'.code.toByte() ||
                    head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()
                ) {
                    return@runCatching null
                }
                // e_machine 在偏移 0x12，2 字节；EI_DATA(head[5]) == 1 表示小端
                val lo = head[18].toInt() and 0xff
                val hi = head[19].toInt() and 0xff
                if (head[5] == 1.toByte()) lo or (hi shl 8) else hi or (lo shl 8)
            }
        }.getOrNull() ?: return null
        val arch = when (machine) {
            EM_AARCH64 -> "arm64-v8a"
            EM_X86_64 -> "x86_64"
            else -> return null
        }
        cachedArch = arch
        return arch
    }

    /**
     * 兜底：按 `SUPPORTED_ABIS` 的**首项**判断。
     *
     * 首项是设备的原生 ABI（转译出来的 ABI 排在后面），所以这条在转译设备上也成立；
     * 只有在 libproot.so 读不到时才会走到这里。
     */
    private fun archFromPrimaryAbi(): String =
        if (android.os.Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") "arm64-v8a" else "x86_64"

    /**
     * 运行时资产名后缀。
     *
     * arm64 **必须沿用无后缀的旧名**（`metadata.json` / `rootfs.tar.gz`）：1.7.5 及更早
     * 把这两个名字写死在代码里，改名等于让所有存量用户拉不到运行时。x86_64 是新增
     * 架构、没有存量，用独立后缀。
     */
    private fun assetSuffix(): String = if (runtimeArch() == "arm64-v8a") "" else "-x86_64"

    /** 本机架构对应的 metadata.json 地址（不含镜像前缀）。 */
    fun metaUrl(): String = RUNTIME_BASE + "metadata" + assetSuffix() + ".json"

    /** 吞吐测速目标（Range 拉前 1MB）：打本机真正会下载的那个 rootfs。 */
    private fun speedProbeUrl(): String = RUNTIME_BASE + "rootfs" + assetSuffix() + ".tar.gz"

    fun proxyPrefix(source: String): String = when (source) {
        SOURCE_GHPROXY_CF -> "https://v6.gh-proxy.org/"
        SOURCE_GHPROXY_AXISNOW -> "https://axisnow.gh-proxy.org/"
        else -> ""
    }

    /**
     * 源 id → 字符串资源 id。**界面与启动日志共用这一份**。
     *
     * 原来这里是一个硬编码中文的 `displayName()`，理由是「它只喂启动日志」；界面另有
     * 一份 `sourceLabelRes()`。日志 i18n 之后那个理由不成立了，而两份平行的映射本身
     * 就是漂移源头（曾经一份写 `gh-proxy (CF)`、另一份写 `gh-proxy（Cloudflare）`）。
     *
     * 取名 labelRes 而不是 displayName：返回的是资源 id，取字符串要调用方自己决定用
     * `stringResource`（Composable）还是 `appString`（后台 / 日志）—— 这个区别在
     * API 33 以下是实打实的（应用内语言只作用于 Activity）。
     */
    fun labelRes(source: String): Int = when (source) {
        SOURCE_AUTO -> R.string.dsh_source_auto
        SOURCE_GITHUB -> R.string.dsh_source_github
        SOURCE_GHPROXY_CF -> R.string.dsh_source_ghproxy_cf
        SOURCE_GHPROXY_AXISNOW -> R.string.dsh_source_ghproxy_axisnow
        SOURCE_CUSTOM -> R.string.dsh_source_custom
        else -> R.string.dsh_source_auto
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
        return proxyPrefix(resolved) + metaUrl()
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
        val meta = metaUrl()
        val probe = speedProbeUrl()
        val candidates = listOf(
            SOURCE_GHPROXY_AXISNOW to "https://axisnow.gh-proxy.org/$meta",
            SOURCE_GHPROXY_CF to "https://v6.gh-proxy.org/$meta",
            SOURCE_GITHUB to meta,
        )
        val latency = candidates.map { (src, url) -> SpeedResult(src, probeLatency(url)) }
        val top = latency.sortedBy { it.latencyMs }.take(2).map { it.source }.toSet()
        return latency.map { r ->
            if (r.source !in top || r.latencyMs >= Long.MAX_VALUE / 4) r
            else r.copy(speedKBps = probeSpeed(proxyPrefix(r.source) + probe))
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
