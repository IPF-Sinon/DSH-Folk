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
 * - auto → 逐个测延迟（metadata.json 小文件），对最优的做 Range 吞吐测速（跳过慢启动
 *   热身段），按「估算下载 100MB 耗时」评分（速度为主、延迟兜底），结果缓存 24h。
 *
 * 不可达用 `SpeedResult.latencyMs == null` 表达，不用哨兵大数 —— 见 [SpeedResult]。
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

    // ── 吞吐测速窗口 ──
    /** Range 请求的上限：够跑完热身 + 计时窗口，多要的服务端也不会真发完。 */
    private const val SPEED_PROBE_BYTES = 4L * 1024 * 1024

    /** 热身字节数：这段只读不计时，避开 TCP 慢启动。 */
    private const val SPEED_WARMUP_BYTES = 512L * 1024

    /** 热身时长上限：慢链路上热身不完 512KB 也要起表，否则单源就能耗掉十几秒。 */
    private const val SPEED_WARMUP_MAX_MS = 1_500L

    /** 计时窗口字节上限：快链路上取够样本就停。 */
    private const val SPEED_TIMED_BYTES = 2L * 1024 * 1024

    /** 计时窗口时长上限：慢链路上到点就停，单源开销钉在这个量级。 */
    private const val SPEED_TIMED_MAX_MS = 2_500L

    /** 计时窗口最少字节：不到这个量的样本噪声太大，当测速失败。 */
    private const val SPEED_TIMED_MIN_BYTES = 128L * 1024

    /** 吞吐测速的 read 超时：比延迟探测宽松，但不能让单源挂太久。 */
    private const val SPEED_READ_TIMEOUT_MS = 6_000

    private const val SCORE_REF_BYTES = 100L * 1024 * 1024

    /** 未参与测速的候选（自定义源／第三方镜像）在下载排序里的权重：排在实测可达的源之后、不可达之前。 */
    private const val UNRANKED_WEIGHT = Long.MAX_VALUE / 2

    private const val KEY_SOURCE = "download_source"
    private const val KEY_CUSTOM_URL = "custom_meta_url"
    private const val KEY_AUTO_SOURCE = "auto_source"
    private const val KEY_AUTO_SOURCE_AT = "auto_source_at"

    /** 稳定版与测试版是两个互不复用 metadata / rootfs 的滚动发布位置。 */
    private const val RUNTIME_STABLE_BASE =
        "https://github.com/IPF-Sinon/DSH-Folk/releases/download/runtime-latest/"
    private const val RUNTIME_BETA_BASE =
        "https://github.com/IPF-Sinon/DSH-Folk/releases/download/runtime-beta-latest/"
    private const val KEY_RUNTIME_BETA = "runtime_accept_beta"

    private fun runtimeBase(): String =
        if (acceptRuntimeBeta(me.bmax.apatch.apApp)) RUNTIME_BETA_BASE else RUNTIME_STABLE_BASE

    fun acceptRuntimeBeta(ctx: Context): Boolean =
        ctx.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE).getBoolean(KEY_RUNTIME_BETA, false)

    fun setAcceptRuntimeBeta(ctx: Context, on: Boolean) {
        prefs(ctx).edit()
            .putBoolean(KEY_RUNTIME_BETA, on)
            .remove(KEY_AUTO_SOURCE)
            .remove(KEY_AUTO_SOURCE_AT)
            .apply()
        memCache = null
        memCachedAt = 0L
        lastResults = emptyList()
    }

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
    fun metaUrl(): String = runtimeBase() + "metadata" + assetSuffix() + ".json"

    /** 吞吐测速目标（Range 拉前 1MB）：打本机真正会下载的那个 rootfs。 */
    private fun speedProbeUrl(): String = runtimeBase() + "rootfs" + assetSuffix() + ".tar.gz"

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

    /**
     * 单个源的测速结果。
     *
     * `latencyMs == null` 表示**不可达**（连不上／非 2xx／超时）。这里用 null 而不是
     * 一个「极大的哨兵值」：老实现返回 `Long.MAX_VALUE / 4` 参与排序，三个渲染处各自
     * 用 `>= Long.MAX_VALUE / 4` 判断，漏判一处就把 `2305843009213693951ms` 原样打给
     * 用户看（启动日志就漏了）。让类型系统强制调用方处理这个分支，那类 bug 就不存在。
     */
    data class SpeedResult(val source: String, val latencyMs: Long?, val speedKBps: Double = 0.0) {
        val reachable: Boolean get() = latencyMs != null

        /** 估算下载 100MB 的耗时（毫秒）；不可达排最后，速度未测得时仅按延迟粗排。 */
        val estimatedMs: Long
            get() {
                val latency = latencyMs ?: return Long.MAX_VALUE
                return if (speedKBps > 0.0) {
                    latency + (SCORE_REF_BYTES / 1024.0 / speedKBps * 1000.0).toLong()
                } else {
                    latency + SCORE_REF_BYTES / 1024 / 1024 * 60_000L
                }
            }
    }

    @Volatile private var memCache: String? = null
    @Volatile private var memCachedAt: Long = 0L

    /** 最近一次 [speedTest] 的结果，供下载 fallback 排序用（见 [downloadRank]）。 */
    @Volatile private var lastResults: List<SpeedResult> = emptyList()

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
        // 全部不可达时不要记缓存：那是「当时没网」，不是「这个源最好」。记下来会让
        // 之后 24h 内都用这个随便挑的源，即使网络已经恢复。
        val reachable = results.filter { it.reachable }
        if (reachable.isEmpty()) return SOURCE_GHPROXY_CF
        val picked = reachable.minByOrNull { it.estimatedMs }?.source ?: SOURCE_GHPROXY_CF
        val now = System.currentTimeMillis()
        memCache = picked
        memCachedAt = now
        prefs(ctx).edit()
            .putString(KEY_AUTO_SOURCE, picked)
            .putLong(KEY_AUTO_SOURCE_AT, now)
            .apply()
        return picked
    }

    /**
     * 下载候选 URL 的排序权重：越小越先试。
     *
     * 测速已经知道哪个源不可达了，fallback 却按 metadata 里 mirrors 的**固定顺序**
     * 试 —— 于是刚测出 SSL 握手失败的 AxisNow 仍然排在 GitHub 直连前面，用户白等
     * 一次超时。这里把测速结论接进来：不可达的源沉底，其余按估算耗时升序。
     *
     * 认不出来的 URL（自定义源、metadata 里的第三方镜像）给一个中间值，保持原相对
     * 顺序 —— 没测过不代表不好，但也不该抢在实测最快的源前面。
     */
    fun downloadRank(url: String): Long {
        val src = when {
            url.startsWith("https://v6.gh-proxy.org/") -> SOURCE_GHPROXY_CF
            url.startsWith("https://axisnow.gh-proxy.org/") -> SOURCE_GHPROXY_AXISNOW
            url.startsWith("https://github.com/") -> SOURCE_GITHUB
            else -> return UNRANKED_WEIGHT
        }
        val r = lastResults.firstOrNull { it.source == src } ?: return UNRANKED_WEIGHT
        return r.estimatedMs
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
        // 只在**可达**的源里挑最快的两个做吞吐测速：不可达的没有延迟可比，
        // 把它塞进 top2 只会浪费一次注定失败的拉取。
        val top = latency
            .filter { it.reachable }
            .sortedBy { it.latencyMs!! }
            .take(2)
            .map { it.source }
            .toSet()
        val results = latency.map { r ->
            if (r.source !in top) r else r.copy(speedKBps = probeSpeed(proxyPrefix(r.source) + probe))
        }
        lastResults = results
        return results
    }

    /**
     * 延迟探测：拉 metadata.json 头部。**不可达返回 null**（不是哨兵大数）。
     */
    private fun probeLatency(url: String): Long? {
        val start = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { it.read(ByteArray(512)) }
            System.currentTimeMillis() - start
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 吞吐探测：Range 拉一段，返回 KB/s（测不出返回 0）。
     *
     * 两个纪律，都是为了让测出来的数字跟真实下载对得上：
     *
     * 1. **跳过 TCP 慢启动**。前 [SPEED_WARMUP_BYTES] 只读不计时。拥塞窗口没涨起来时的
     *    速率不代表稳态，算进去会系统性低估 —— 实测这条链路测出 0.7 MB/s，真正下载时
     *    4.3 MB/s，差 6 倍，足以让评分选错源。
     * 2. **计时窗口双限**（字节 [SPEED_TIMED_BYTES] 或时长 [SPEED_TIMED_MAX_MS]，先到先停）。
     *    只限字节的老实现在慢链路上会拖很久：0.1 MB/s 拉满 1MB 要 10 秒，三个源轮下来
     *    用户以为卡死了。限时长就把单源开销钉在 ~2.5s 上限，同时慢链路也拿得到真实速率。
     *
     * 提前断流（读到 EOF 却两个上限都没到）算测速失败：服务端只吐几十 KB 就关，
     * 分子分母同时很小，相除会得出一个虚高的速度，让这个源赢下测速、再在真正下载
     * 130MB 时暴露。
     */
    private fun probeSpeed(url: String): Double {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = SPEED_READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Range", "bytes=0-${SPEED_PROBE_BYTES - 1}")
            if (conn.responseCode !in 200..299) return 0.0
            val opened = System.currentTimeMillis()
            var total = 0L
            var timedBytes = 0L
            var timedFrom = 0L
            var complete = false
            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (timedFrom == 0L) {
                        // 热身段：字节数或时长任一到线就起表。慢链路上靠时长兜住，
                        // 否则光热身 512KB 就要等半天。
                        val warm = total >= SPEED_WARMUP_BYTES ||
                            System.currentTimeMillis() - opened >= SPEED_WARMUP_MAX_MS
                        if (warm) timedFrom = System.currentTimeMillis()
                        continue
                    }
                    timedBytes += n
                    if (timedBytes >= SPEED_TIMED_BYTES ||
                        System.currentTimeMillis() - timedFrom >= SPEED_TIMED_MAX_MS
                    ) {
                        complete = true
                        break
                    }
                }
            }
            if (!complete) return 0.0
            val dtSec = (System.currentTimeMillis() - timedFrom) / 1000.0
            if (dtSec <= 0.0 || timedBytes < SPEED_TIMED_MIN_BYTES) 0.0
            else timedBytes / 1024.0 / dtSec
        } catch (e: Exception) {
            0.0
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
