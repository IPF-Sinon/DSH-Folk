package me.bmax.apatch.dsh

import android.content.Context
import java.net.HttpURLConnection
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.net.URL
import me.bmax.apatch.R
import org.json.JSONArray

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

    /** gh-proxy 主站（CF，v4 全球分发）。2026-09-25 实测 git/Range 均可用。 */
    const val SOURCE_GHPROXY_MAIN = "ghproxy_main"

    /** gh-proxy v4 优选（仅 IPv4 智能解析）。2026-09-25 实测可用。 */
    const val SOURCE_GHPROXY_V4 = "ghproxy_v4"

    /** gh-proxy Fastly CDN 线路（v4）。2026-09-25 实测可用。 */
    const val SOURCE_GHPROXY_CDN = "ghproxy_cdn"
    const val SOURCE_CUSTOM = "custom"

    /**
     * 全部镜像线路（源 id → 前缀），**唯一事实来源**。
     *
     * 三件事都从这里派生，避免三份清单各改各的：测速候选（[speedTest]）、下载排序（[downloadRank]）、
     * 以及插件安装清 git 重写时要覆盖的前缀（见 [allProxyPrefixes]）。加一条线路只改这一处。
     *
     * 2026-09-25 全量实测（git ls-remote 3/3 通过、release 资产 Range 206、codeload tarball 可取）：
     * 五条线路能力都够用；其中主站/v4/CDN 是新纳入的，AxisNow 与 v6 是原有的。
     */
    private val MIRRORS: List<Pair<String, String>> = listOf(
        SOURCE_GHPROXY_AXISNOW to "https://axisnow.gh-proxy.org/",
        SOURCE_GHPROXY_CF to "https://v6.gh-proxy.org/",
        SOURCE_GHPROXY_V4 to "https://v4.gh-proxy.org/",
        SOURCE_GHPROXY_CDN to "https://cdn.gh-proxy.org/",
        SOURCE_GHPROXY_MAIN to "https://gh-proxy.org/",
    )

    /**
     * 全部线路前缀，含空串 = 直连 github（垫底）。
     *
     * 插件安装用它来**清 git 重写**：之前配过哪条线路的 `insteadOf` 就必须能清掉哪条，
     * 否则换顺序后旧前缀会留在 `.gitconfig` 里继续生效。
     */
    fun allProxyPrefixes(): List<String> = MIRRORS.map { it.second } + ""

    /** 全部线路 id（顺序即 MIRRORS 顺序）——竞速通道弹窗按它渲染勾选列表。 */
    fun allSourceIds(): List<String> = MIRRORS.map { it.first }

    /**
     * 当前启用的镜像线路（竞速通道弹窗里勾选的那些）。缺失 = 全选；空 = 一条都不用（只直连）。
     *
     * 用户设定（2026-09-25）：镜像勾选**三条通道共用一份** —— 勾了的才参与测速与竞速，
     * 没勾的既不会被测速、也不会被下载。所以这里返回的集合同时决定测速范围与下载候选。
     *
     * 首次读取（pref 不存在）时做一次**老设置迁移**：以前「运行时下载源」卡片里的固定选择
     * 会被翻译成对应的勾选，而不是被无声重置成全选 —— 用户当初明确选了某条线路，升级后
     * 不该变成「随便挑一条最快的」。
     */
    fun enabledMirrors(): Set<String> {
        val prefs = runCatching { prefs() }.getOrNull() ?: return allSourceIds().toSet()
        val raw = prefs.getString(DshEnv.KEY_RACE_MIRRORS, null)
            ?: return migrateLegacySourceChoice(prefs).also {
                prefs.edit().putString(DshEnv.KEY_RACE_MIRRORS, JSONArray(it.toList()).toString()).apply()
            }
        val parsed = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { id -> id.isNotEmpty() } }
        }.getOrNull() ?: return allSourceIds().toSet()
        // 只保留仍然存在的线路（线路下线后旧勾选不该把候选集算错）
        return parsed.filter { it in allSourceIds() }.toSet()
    }

    /** 写回勾选（落盘的就是集合本身，空集合也照存 —— 那是「只直连」的合法表达）。 */
    fun setEnabledMirrors(ids: Collection<String>) {
        val known = ids.filter { it in allSourceIds() }.distinct()
        prefs().edit().putString(DshEnv.KEY_RACE_MIRRORS, JSONArray(known).toString()).apply()
    }

    /**
     * 老「运行时下载源」的固定选择 → 镜像勾选（只在第一次读勾选时跑一次）。
     *
     * - 选过某条镜像线路 → 只勾那条（他的意图就是「走这条」）；
     * - 选过直连 github → 全不勾（意图是「别绕」）；
     * - auto / custom / 从没设过 → 全选（竞速的默认语义）。
     */
    private fun migrateLegacySourceChoice(prefs: android.content.SharedPreferences): Set<String> {
        val legacy = prefs.getString(KEY_SOURCE, SOURCE_AUTO) ?: SOURCE_AUTO
        return when (legacy) {
            SOURCE_GITHUB -> emptySet()
            SOURCE_CUSTOM -> allSourceIds().toSet()
            in allSourceIds() -> setOf(legacy)
            else -> allSourceIds().toSet()
        }
    }

    /** 参与竞速的镜像线路（id → 前缀），顺序同上表。 */
    private fun activeMirrors(): List<Pair<String, String>> {
        val on = enabledMirrors()
        return MIRRORS.filter { it.first in on }
    }

    /** 手动选源时可选的固定线路（不含 auto 与 custom）。 */
    fun fixedSources(): List<String> = listOf(SOURCE_GITHUB) + MIRRORS.map { it.first }

    /**
     * 一条 github 直链的**全部**下载候选（各线路前缀 + 直连原址），按测速结论排序。
     *
     * 为什么需要它：原来下载候选只来自 metadata.json 的 `mirrors` 数组，而老运行时里只写了
     * v6 + axisnow 两条 —— 后来新增的线路永远排不进去。改从 [MIRRORS] 派生后，线路清单扩到几条，
     * 下载回退就有几条。非 github 直链（自定义源、第三方镜像）原样返回。
     *
     * **竞速通道关掉时不要调它**：那种情况下用户要的是「直连」，调用方自己拼原址。
     */
    fun proxyCandidates(url: String): List<String> {
        if (!url.startsWith("https://github.com/")) return listOf(url)
        // 只用**勾选过**的线路 + 直连原址（未勾选的一律不给候选，见 [enabledMirrors]）
        return (activeMirrors().map { it.second + url } + url).distinct().sortedBy { downloadRank(it) }
    }

    /**
     * 插件截图直链的镜像改写：`raw.githubusercontent.com` / `github.com` 图床在国内基本连不上，
     * Coil 直接拉会一直空白。这里套一层**测速最快的已勾选线路**前缀（gh-proxy 系同样代理 raw 域），
     * 交给 Coil 加载单个 URL —— 截图是非关键内容，取最优一条即可，失败就空白，不做多候选回退。
     *
     * 没勾选任何线路（用户选「只直连」→ [activeMirrors] 为空）或非 github 图床时原样返回，
     * 尊重用户的直连选择、也不给第三方图床平白套前缀。
     */
    fun mirrorImageUrl(url: String): String {
        val isGh = url.startsWith("https://raw.githubusercontent.com/") ||
            url.startsWith("https://github.com/") ||
            url.startsWith("https://user-images.githubusercontent.com/")
        if (!isGh) return url
        val mirrors = activeMirrors()
        if (mirrors.isEmpty()) return url
        return mirrors.map { it.second + url }.minByOrNull { downloadRank(it) } ?: url
    }

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

    /**
     * 并行延迟探测里，单个探测最多等多久。
     *
     * 比单次探测自身的 connect+read 超时略宽松（3s+3s）：并行只是把等待叠在一起，不该因为调度
     * 抖动把本来能成的探测判死。真超时了 [probeAllInParallel] 会退回串行，不会变成「测不出来」。
     */
    private const val PROBE_POOL_TIMEOUT_MS = 12_000L

    /**
     * 测速结果的复用窗口（与 [CACHE_TTL_MS] 那个 24h 的「自动源选择」缓存是两件事）。
     *
     * 24h 是给「下载 200MB 运行时」这种大件用的：结论稳定、重测代价高。而插件安装/更新是
     * 即时操作，用户点完就在等 —— 只要 10 分钟内测过一次就直接复用，不再让他先等一轮探测。
     */
    private const val SPEED_RESULT_TTL_MS = 10 * 60 * 1000L

    /** 测不出任何可达源时的兜底顺序：两条 gh-proxy 在前、直连 github 垫底。 */
    private val FALLBACK_ORDER = listOf(SOURCE_GHPROXY_CF, SOURCE_GHPROXY_AXISNOW, SOURCE_GITHUB)

    /** npm registry 候选：官方 + 国内镜像（npmmirror 是 npm 的完整同步镜像）。 */
    private val NPM_REGISTRIES = listOf("https://registry.npmjs.org", "https://registry.npmmirror.com")

    /** 探 registry 延迟用的包：存在且体量极小，两个 registry 都有。 */
    private const val REGISTRY_PROBE_PKG = "dsh-config-manager"

    /** 未参与测速的候选（自定义源／第三方镜像）在下载排序里的权重：排在实测可达的源之后、不可达之前。 */
    private const val UNRANKED_WEIGHT = Long.MAX_VALUE / 2

    private const val KEY_SOURCE = "download_source"
    private const val KEY_CUSTOM_URL = "custom_meta_url"
    private const val KEY_AUTO_SOURCE = "auto_source"
    private const val KEY_AUTO_SOURCE_AT = "auto_source_at"

    /** 稳定版与测试版是两个互不复用 metadata / rootfs 的滚动发布位置。 */
    private const val RELEASE_DOWNLOAD_BASE =
        "https://github.com/IPF-Sinon/DSH-Folk/releases/download/"
    private const val RUNTIME_STABLE_BASE = RELEASE_DOWNLOAD_BASE + "runtime-latest/"
    private const val RUNTIME_BETA_BASE = RELEASE_DOWNLOAD_BASE + "runtime-beta-latest/"
    private const val KEY_RUNTIME_BETA = "runtime_accept_beta"

    /**
     * 任意 runtime release tag 的资产前缀。
     *
     * 给版本列表用：历史版本只能按各自的 tag 去取（`runtime-0.1.1-rc.2` 这种），
     * 滚动通道那两个地址永远只有最新一份。
     */
    fun releaseBase(tag: String): String = RELEASE_DOWNLOAD_BASE + tag + "/"

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
        lastResultsAt = 0L
        registryCache = emptyList()
        registryCacheAt = 0L
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

    fun proxyPrefix(source: String): String =
        MIRRORS.firstOrNull { it.first == source }?.second ?: ""

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
        SOURCE_GHPROXY_MAIN -> R.string.dsh_source_ghproxy_main
        SOURCE_GHPROXY_V4 -> R.string.dsh_source_ghproxy_v4
        SOURCE_GHPROXY_CDN -> R.string.dsh_source_ghproxy_cdn
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

    /** [lastResults] 的产出时刻；用于 [rankedSources] 的复用窗口判定。 */
    @Volatile private var lastResultsAt: Long = 0L

    /** npm registry 的测速结论（见 [rankedNpmRegistries]）与其产出时刻。 */
    @Volatile private var registryCache: List<String> = emptyList()
    @Volatile private var registryCacheAt: Long = 0L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)

    /**
     * 不带 Context 的偏好入口（给镜像勾选这类「调用点拿不到 ctx」的地方用）。
     *
     * 走全局 application context：本对象现有 API 全是不带 ctx 的同步方法（[rankedSources]、
     * [proxyCandidates]、[speedTest] 会被插件安装、APK 下载、运行时下载三处调用），为读一个
     * 偏好把它们全改成 suspend/带 ctx 不划算。[APApplication] 在 App 启动时就绑定，读到未
     * 初始化时下面调用点都做了兜底（返回全选 → 行为退化成改动前的样子，不是崩）。
     */
    private fun prefs(): android.content.SharedPreferences =
        me.bmax.apatch.apApp.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)

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
        val src = MIRRORS.firstOrNull { url.startsWith(it.second) }?.first
            ?: if (url.startsWith("https://github.com/")) SOURCE_GITHUB else return UNRANKED_WEIGHT
        val r = lastResults.firstOrNull { it.source == src } ?: return UNRANKED_WEIGHT
        return r.estimatedMs
    }

    /**
     * 按测速结论排好序的源 id（最快在前），供「竞速通道」用。
     *
     * 复用 [lastResults]：近期测过就直接用，不重复探测（装插件这种即时操作不该每次先等一轮
     * 测速）。超过 [SPEED_RESULT_TTL_MS] 或还没测过才重新测。全部不可达时回退到
     * [FALLBACK_ORDER] —— 那是「测不出来」的兜底顺序（先两条 gh-proxy、最后直连），
     * 不回退就等于把用户的插件安装直接卡死。
     */
    fun rankedSources(force: Boolean = false): List<String> {
        val results = if (!force && resultsFresh()) lastResults else speedTest()
        // 只保留勾选的线路（+ 直连 github，它不在勾选列表里、永远是兜底那条）
        val on = enabledMirrors()
        val ranked = results
            .sortedBy { it.estimatedMs }
            .map { it.source }
            .filter { it == SOURCE_GITHUB || it in on }
        if (ranked.isEmpty() || ranked.none { s -> results.any { it.source == s && it.reachable } }) {
            return FALLBACK_ORDER
        }
        // 不可达的源仍留在尾部：直连 github 常常是最后一条能成的路
        return ranked.distinct()
    }

    /** [lastResults] 是否还在复用窗口内。 */
    private fun resultsFresh(): Boolean {
        val at = lastResultsAt
        if (at == 0L || lastResults.isEmpty()) return false
        return System.currentTimeMillis() - at < SPEED_RESULT_TTL_MS
    }

    /**
     * npm registry 的候选，按测速结论排序（最快在前），最后一条永远是官方源。
     *
     * npm 规格的插件（dsh-config-manager / dsh-web-mobile 之类）走的是 pnpm，跟 gh-proxy
     * 那套 git 重写毫无关系 —— 插件镜像开关对它们一直不起作用，国内装它们只能干等官方源。
     * 这里测一次两个 registry，把最快的那个用 `--registry` 传给 pnpm（不改 .npmrc、不动 rootfs）。
     * 结果同样带缓存：[registryCacheAt]，窗口内不重测。
     *
     * 兜底：官方源永远在列表里（镜像同步延迟/缺包时还能退回官方），且**全部探测失败时只回官方源**。
     */
    fun rankedNpmRegistries(ctx: Context, force: Boolean = false): List<String> {
        val now = System.currentTimeMillis()
        val cached = registryCache
        if (!force && cached.isNotEmpty() && now - registryCacheAt < SPEED_RESULT_TTL_MS) return cached

        val probed = NPM_REGISTRIES.map { reg -> reg to probeRegistryLatency(reg) }
        val reachable = probed.filter { it.second != null }.sortedBy { it.second }
        val order = (reachable.map { it.first } + NPM_REGISTRIES).distinct()
        registryCache = order
        registryCacheAt = now
        return order
    }

    /** 探一个 npm registry 的延迟：拉某个一定存在的包的 latest 元数据头。不可达返回 null。 */
    private fun probeRegistryLatency(registry: String): Long? {
        val start = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        return try {
            conn = URL("$registry/$REGISTRY_PROBE_PKG/latest").openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode in 200..399) System.currentTimeMillis() - start else null
        } catch (_: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 所有候选源测一遍（延迟 + 吞吐）。同步阻塞，调用方放 IO 线程。
     *
     * @param probeAll 是否给**每条可达线路**都测吞吐。
     *   自动路径（装插件 / 下载运行时）传 false：它只关心「谁最快」，给最快的两条测吞吐就够，
     *   其余是陪跑 —— 而这条路径是**同步等待**的，不能为了一张好看的表格多等十几秒。
     *   用户在竞速弹窗里手动点「测速」时传 true：那时候他要的就是每条线路的真实速度，
     *   而且有进度回调兜着（先出全部延迟，再按延迟从快到慢逐条填吞吐）。
     * @param onProgress 进度回调（可能在工作线程上被调用，UI 侧自己切主线程）：
     *   probeAll 时先回调一次「只有延迟」的结果，之后每测完一条线路的吞吐再回调一次。
     *   中途也会更新 [lastResults] —— 用户看两眼就关掉弹窗，结论也该留下。
     */
    fun speedTest(
        probeAll: Boolean = false,
        onProgress: ((List<SpeedResult>) -> Unit)? = null,
    ): List<SpeedResult> {
        val meta = metaUrl()
        val probe = speedProbeUrl()
        // 候选＝每条镜像线路 + 直连 github（垫底）。清单从 MIRRORS 派生，加线路只改一处。
        // 只测勾选过的线路：没勾的既不该占用测速时间，也不该出现在测速结果里
        val candidates = activeMirrors().map { (src, prefix) -> src to "$prefix$meta" } +
            (SOURCE_GITHUB to meta)
        // 延迟探测**并行**：候选从 3 条涨到 6 条，串行最坏要等 6×6s 超时；并行把这段钉在单次超时量级。
        // 这条路径在用户点「安装插件」时是同步等待的（测速服务端结论带 10 分钟缓存），不能拖。
        val latency = probeAllInParallel(candidates.size) { i ->
            val (src, url) = candidates[i]
            SpeedResult(src, probeLatency(url))
        }
        // 只在**可达**的源里挑：不可达的没有延迟可比，也没有吞吐可测（注定失败的拉取纯浪费时间）。
        // 按延迟从快到慢排：手动全量测速时，最有用的那几条先出结果。
        val reachable = latency.filter { it.reachable }.sortedBy { it.latencyMs!! }
        if (probeAll) {
            // 先把「只有延迟」的结果交出去，界面立刻满屏有数，不用干等吞吐
            var acc = latency
            lastResults = acc
            lastResultsAt = System.currentTimeMillis()
            onProgress?.invoke(acc)
            for (r in reachable) {
                val speed = probeSpeed(proxyPrefix(r.source) + probe)
                acc = acc.map { if (it.source == r.source) it.copy(speedKBps = speed) else it }
                lastResults = acc
                lastResultsAt = System.currentTimeMillis()
                onProgress?.invoke(acc)
            }
            return acc
        }
        val top = reachable.take(2).map { it.source }.toSet()
        val results = latency.map { r ->
            if (r.source !in top) r else r.copy(speedKBps = probeSpeed(proxyPrefix(r.source) + probe))
        }
        lastResults = results
        lastResultsAt = System.currentTimeMillis()
        return results
    }

    /**
     * 把 [count] 个探测并行跑完，保持下标顺序返回。
     *
     * 用线程池而不是协程：本对象全是同步 API（[resolve]/[rankedSources] 会在非 suspend 上下文被调），
     * 为了并行把整条链路改成 suspend 得不偿失。探测本身是纯阻塞 IO，线程池正合适。
     */
    private fun <T> probeAllInParallel(count: Int, block: (Int) -> T): List<T> {
        if (count <= 1) return (0 until count).map(block)
        val pool = Executors.newFixedThreadPool(count) { r ->
            Thread(r, "dsh-source-probe").apply { isDaemon = true }
        }
        return try {
            val futures = (0 until count).map { i -> pool.submit(Callable { block(i) }) }
            futures.map { it.get(PROBE_POOL_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        } catch (_: Exception) {
            // 并行拿不到结果就退回串行 —— 宁可慢，不能把「测速」变成「测不出来」
            (0 until count).map(block)
        } finally {
            pool.shutdownNow()
        }
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
