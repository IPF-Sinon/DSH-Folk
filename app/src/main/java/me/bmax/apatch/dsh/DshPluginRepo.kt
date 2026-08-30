package me.bmax.apatch.dsh

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import org.json.JSONArray
import org.json.JSONObject

/** 一个 DSH 插件条目。 */
data class DshPlugin(
    /**
     * 目录里的稳定 id。与 npm 包名**不一定相同** —— 全量目录用 `owner/name`
     * （不同作者的同名插件并不少见），dsh-market 用它自己的 id。
     * 所以列表 key 用 id，安装/下载量一律用 [pkg]。
     */
    val id: String,
    /** npm 包名；目录里没登记 npm 的条目为空（1284/2495 条如此）。 */
    val pkg: String = "",
    val name: String,
    val version: String = "",
    val description: String = "",
    val author: String = "",
    /** 上游仓库，形如 `owner/name`（用于取 star）。 */
    val repo: String = "",
    val homepage: String = "",
    /** 已安装版本，未安装为空。 */
    val installedVersion: String = "",
    /** 近一周 npm 下载量，-1 表示未知。 */
    val downloads: Long = -1L,
    /** GitHub star，-1 表示未知。 */
    val stars: Long = -1L,
    /** dsh-market 上的点赞数，-1 表示未知。 */
    val likes: Long = -1L,
    val category: String = "",
    /**
     * 传给 `dsh plugin add` 的安装规格。
     *
     * 通常等于 [pkg]；目录里没登记 npm 的条目是 `github:owner/name`。
     * 空表示没有可用的安装方式。
     */
    val installSpec: String = "",
    /**
     * 是否作为 profile 层生效。
     *
     * 判据是包名在 profile `package.json` 的 `dsh.profile.bundles` 里 —— dsh 只加载
     * 这个列表；装了但没声明 `dsh.bundle.patch` 的依赖只是普通库，不是生效的插件。
     */
    val enabled: Boolean = true,
) {
    val installed: Boolean get() = installedVersion.isNotEmpty()

    /** 可一键安装：有 npm 包名或 github: 规格。 */
    val installable: Boolean get() = pkg.isNotEmpty() || installSpec.isNotEmpty()

    /** 传给安装命令的实参。 */
    val addSpec: String get() = installSpec.ifEmpty { pkg }

    /** 已安装且线上版本更高 → 可更新。 */
    val updatable: Boolean
        get() = installed && version.isNotEmpty() && compareVersions(version, installedVersion) > 0
}

/**
 * 完整插件目录的一次快照。
 *
 * @param updated 目录自报的更新日期（`2026-08-29`）。
 * @param categoryTitles 目录自带的分类标题，App 没内置该 slug 时用它兜底。
 * @param offline 数据来自过期的本地缓存（三条线上源都失败）。
 */
data class PluginCatalog(
    val updated: String,
    val categoryTitles: Map<String, String>,
    val plugins: List<DshPlugin>,
    val offline: Boolean = false,
)

/**
 * 插件商店分类。`slug` 与目录 `category` 字段一致，`label` 是内置字符串资源 id。
 */
data class PluginCategory(
    val slug: String,
    val label: Int,
)

/**
 * DSH 插件数据源。
 *
 * 各来源只管一件事，缺一个不影响其它：
 * - **全量目录**（[catalog]）是商店的数据源：`awesome-dsh-plugin.com/plugins.json`，
 *   约 2600 条，自带分类 / 描述 / star / 下载量，所以商店列表**不需要**再逐包打
 *   npm 或 GitHub API。走 npm 包 `dsh-plugin-catalog` 作为国内回落，与 DSH 官方
 *   市场插件（dshmarket）同一条路径；
 * - **dsh-market** 的 `manifest/plugins.json` 是**精选**列表（几十条），只用于
 *   已安装页的点赞数与整表周下载量；
 * - **npm registry** 提供最新版本号（`registry.npmjs.org/<pkg>/latest`），
 *   只对已安装的少量包查，用来判断可更新；
 * - **GitHub API** 提供 star 数（匿名 60 次/小时，缓存 6 小时并限并发），
 *   仅在目录没给出 star 时兜底。
 *
 * 已安装列表来自容器内 profile 目录，走 proot 读，不依赖网络。
 */
object DshPluginRepo {
    private const val TAG = "DSH-Folk-Plugins"

    private const val MARKET_MANIFEST = "https://dsh-market.com/manifest/plugins.json"
    private const val MARKET_STATS = "https://dsh-market.com/api/stats"
    private const val MARKET_DOWNLOADS = "https://dsh-market.com/api/npm-downloads"
    private const val NPM_SEARCH = "https://registry.npmjs.org/-/v1/search?size=100&text="
    private const val NPM_DOWNLOADS_POINT = "https://api.npmjs.org/downloads/point/last-week/"

    /** 全量目录的官方地址（GitHub Pages + CDN）。 */
    private const val CATALOG_ORIGIN = "https://awesome-dsh-plugin.com/plugins.json"

    /** 同一份目录发布成的 npm 包，用于国内回落。 */
    private const val CATALOG_PACKAGE = "dsh-plugin-catalog"
    private const val NPM_REGISTRY = "https://registry.npmjs.org"
    private const val NPM_MIRROR_CN = "https://registry.npmmirror.com"
    private const val CATALOG_CACHE_FILE = "plugin-catalog.json"

    // tar 头布局：512 字节一块，name@0(100)、八进制 size@124(12)、type@156
    private const val TAR_BLOCK = 512
    private const val TAR_NAME_LEN = 100
    private const val TAR_SIZE_OFF = 124
    private const val TAR_SIZE_LEN = 12
    private const val TAR_TYPE_OFF = 156

    /** dsh 的 profile 名。web 界面就是这个 profile，插件必须装进它才会被加载。 */
    private const val PROFILE = "web"
    private const val PROFILE_DIR = "/root/.dsh/profiles/web"

    /** git 已就绪的标记文件（避免每次 git 规格安装都探一遍 apt）。 */
    private const val GIT_READY_MARK = "/root/.dsh/.git-ready"

    /** 验证通过的自制标记（`dsh web: http` 出现即写）。 */
    private const val VERIFY_OK_MARK = "[DSH-Folk-verify-ok]"

    /** 验证进程的输出落盘位置（不能走管道，见 [verifyBoot]）。 */
    private const val VERIFY_LOG = "/root/.dsh/verify-boot.log"

    /** 轮询次数上限，每轮 1s；比 [VERIFY_TIMEOUT_MS] 略小，让脚本自己先收尾。 */
    private const val VERIFY_POLLS = 170

    /**
     * 验证等待上限。本环境实测约 25s 就打印就绪行，180s 是保守值 ——
     * 误判的后果是插件被回滚、用户可关掉开关重装，不是数据损坏。
     *
     * 这是宿主侧的**兜底**：正常路径下脚本命中就绪行就自己退出（真机实测 3s 内）。
     */
    private const val VERIFY_TIMEOUT_MS = 180_000L

    /** 插件树加载失败的日志签名（与 DshRuntime 那份同源，用于挑出关键行）。 */
    private val VERIFY_FAILURE_MARKS = listOf(
        "plugin tree failed to load",
        "client bundles not found",
        "failed to apply loader entry modules",
        "ERR_PNPM",
    )

    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L
    /** 目录/下载量表变动没那么快，缓存 30 分钟，避免每次进页面都全量重拉。 */
    private const val CATALOG_TTL_MS = 30 * 60 * 1000L

    private val starCache = ConcurrentHashMap<String, Pair<Long, Long>>()      // repo -> (star, at)
    private val versionCache = ConcurrentHashMap<String, Pair<String, Long>>() // pkg  -> (ver, at)

    @Volatile private var downloadsTable: Map<String, Long> = emptyMap()
    @Volatile private var downloadsAt: Long = 0L
    @Volatile private var likesTable: Map<String, Long> = emptyMap()
    @Volatile private var likesAt: Long = 0L

    /** 同时最多这么多个网络请求，别把 GitHub 的匿名配额一次打光。 */
    private val gate = Semaphore(6)

    /** 拉线上插件目录并补齐版本 / 下载量 / star。 */
    suspend fun fetchCatalog(): List<DshPlugin> = withContext(Dispatchers.IO) {
        val base = fetchMarket().ifEmpty { fetchNpmFallback() }
        if (base.isEmpty()) return@withContext emptyList()
        // 已安装条目的 id 就是 npm 包名，目录条目的 id 是 market id ——
        // 两者对 12/28 条并不相同，必须按 pkg 关联，否则「已安装」永远匹配不上
        val installed = listInstalled().associateBy { it.pkg }
        enrich(base.map { p ->
            p.copy(installedVersion = installed[p.pkg]?.installedVersion ?: "")
        })
    }

    /**
     * 并发补齐版本、下载量、star、点赞（各自失败只让那一项保持 -1 / 空）。
     *
     * `version` 语义是**远端最新版**：非空即视为已知，不会再查 registry。
     * 想让已安装条目算出 updatable，调用方必须先把 version 清空
     * （`installedVersion` 不受影响）。
     */
    suspend fun enrich(list: List<DshPlugin>): List<DshPlugin> = coroutineScope {
        // 下载量与点赞是整表接口，先各取一次
        val downloads = async { downloadsTable() }
        val likes = async { likesTable() }
        val dlMap = downloads.await()
        val likeMap = likes.await()

        list.map { p ->
            async {
                gate.withPermit {
                    p.copy(
                        version = p.version.ifEmpty {
                            if (p.pkg.isEmpty()) "" else latestVersionOf(p.pkg)
                        },
                        stars = if (p.repo.isEmpty()) -1L else starsOf(p.repo),
                        downloads = p.pkg.takeIf { it.isNotEmpty() }
                            ?.let { dlMap[it] ?: downloadsOf(it) } ?: -1L,
                        likes = likeMap[p.id] ?: -1L,
                    )
                }
            }
        }.awaitAll()
    }

    /** dsh-market 目录（`manifest/plugins.json`）。 */
    private fun fetchMarket(): List<DshPlugin> = runCatching {
        val json = httpGet(MARKET_MANIFEST) ?: return@runCatching emptyList()
        val arr = JSONObject(json).optJSONArray("items") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").ifEmpty { o.optString("npm") }
            if (id.isEmpty()) return@mapNotNull null
            DshPlugin(
                id = id,
                pkg = o.optString("npm"),
                name = o.optString("name").ifEmpty { id },
                description = o.optString("description").ifEmpty { o.optString("descriptionEn") },
                author = o.optString("author"),
                repo = normalizeRepo(o.optString("repo")),
                homepage = o.optString("repo"),
                category = o.optString("category"),
            )
        }
    }.getOrElse {
        Log.w(TAG, "dsh-market 目录获取失败: ${it.message}")
        emptyList()
    }

    /** dsh-market 不可用时回退 npm 搜索关键字 `dsh-plugin`。 */
    private fun fetchNpmFallback(): List<DshPlugin> = runCatching {
        val json = httpGet(NPM_SEARCH + "keywords:dsh-plugin") ?: return@runCatching emptyList()
        val objs = JSONObject(json).optJSONArray("objects") ?: JSONArray()
        (0 until objs.length()).mapNotNull { i ->
            val pkg = objs.optJSONObject(i)?.optJSONObject("package") ?: return@mapNotNull null
            val id = pkg.optString("name")
            if (id.isEmpty()) return@mapNotNull null
            DshPlugin(
                id = id,
                pkg = id,
                name = id,
                version = pkg.optString("version"),
                description = pkg.optString("description"),
                author = pkg.optJSONObject("author")?.optString("name") ?: "",
                repo = normalizeRepo(pkg.optJSONObject("links")?.optString("repository") ?: ""),
                homepage = pkg.optJSONObject("links")?.optString("homepage") ?: "",
            )
        }
    }.getOrElse {
        Log.w(TAG, "npm 搜索回退失败: ${it.message}")
        emptyList()
    }

    /**
     * 商店的分类列表，顺序即 tab 顺序（不含「全部」，由界面在开头单独加）。
     *
     * slug 与 [CATALOG_ORIGIN] 目录里 `category` 字段一致（实测 22 个）。标题优先用
     * 内置字符串资源（能跟随应用语言），目录将来新增分类时回落到目录自带的
     * `categories[slug]` 文案，不必改 App。
     */
    fun categories(): List<PluginCategory> = listOf(
        PluginCategory("agi", R.string.dsh_plugin_cat_agi),
        PluginCategory("ui", R.string.dsh_plugin_cat_ui),
        PluginCategory("usage", R.string.dsh_plugin_cat_usage),
        PluginCategory("theme", R.string.dsh_plugin_cat_theme),
        PluginCategory("model", R.string.dsh_plugin_cat_model),
        PluginCategory("identity", R.string.dsh_plugin_cat_identity),
        PluginCategory("session", R.string.dsh_plugin_cat_session),
        PluginCategory("memory", R.string.dsh_plugin_cat_memory),
        PluginCategory("tools", R.string.dsh_plugin_cat_tools),
        PluginCategory("browser", R.string.dsh_plugin_cat_browser),
        PluginCategory("vision", R.string.dsh_plugin_cat_vision),
        PluginCategory("voice", R.string.dsh_plugin_cat_voice),
        PluginCategory("docs", R.string.dsh_plugin_cat_docs),
        PluginCategory("skill", R.string.dsh_plugin_cat_skill),
        PluginCategory("workflow", R.string.dsh_plugin_cat_workflow),
        PluginCategory("git", R.string.dsh_plugin_cat_git),
        PluginCategory("notify", R.string.dsh_plugin_cat_notify),
        PluginCategory("dev", R.string.dsh_plugin_cat_dev),
        PluginCategory("security", R.string.dsh_plugin_cat_security),
        PluginCategory("remote", R.string.dsh_plugin_cat_remote),
        PluginCategory("market", R.string.dsh_plugin_cat_market),
        PluginCategory("fun", R.string.dsh_plugin_cat_fun),
    )

    /**
     * 完整插件目录。**商店的唯一数据源。**
     *
     * 为什么不是 npm search：`registry.npmjs.org/-/v1/search` 的 `text` 里，
     * `keywords:` 限定词之后的自由文本只参与**排序**，不做过滤 —— 实测
     * `text=keywords:dsh-plugin,dsh theme` 返回的 total 仍是 2577，只是主题类
     * 被排到了前面。也就是说 npm 搜不出「某个词的全部命中」，只能给出前 N 个最相关。
     * 而用户要的是「能搜到全部插件」，所以必须先把完整目录取下来，再本地检索。
     *
     * 这条路径与 DSH 官方市场插件（dshmarket）完全一致：
     * 1. [CATALOG_ORIGIN] —— GitHub Pages 上的完整目录（约 2.2MB，gzip 后约 600KB）；
     * 2. [CATALOG_PACKAGE] 的 npm 包 —— 目录也发布成 npm 包，因为公共 GitHub 代理
     *    只接受 github.com 自己的域名、拿不到 Pages 域（官方注释实测 403），
     *    而 npm 镜像在国内一定通；读的是 tarball 里的 `package/plugins.json`；
     * 3. npmmirror 上的同一个包。
     *
     * 结果缓存到 cacheDir，TTL [CATALOG_TTL_MS]；三条源全挂时回落到过期缓存
     * （目录是只增不减的列表，旧快照仍然可用）。
     */
    suspend fun catalog(ctx: Context, force: Boolean = false): PluginCatalog =
        withContext(Dispatchers.IO) {
            val cacheFile = File(ctx.cacheDir, CATALOG_CACHE_FILE)
            val fresh = cacheFile.isFile &&
                System.currentTimeMillis() - cacheFile.lastModified() < CATALOG_TTL_MS
            if (!force && fresh) {
                parseCatalog(runCatching { cacheFile.readText() }.getOrNull(), offline = false)
                    ?.let { return@withContext it }
            }

            for (fetch in catalogSources()) {
                val text = runCatching { fetch() }.getOrNull()
                val parsed = parseCatalog(text, offline = false)
                if (parsed != null) {
                    runCatching { cacheFile.writeText(text!!) }
                    return@withContext parsed
                }
            }

            // 三条源都挂了：过期缓存也比空列表有用，标记成离线快照让界面能说明情况
            Log.w(TAG, "插件目录三条源均失败，回落磁盘缓存")
            parseCatalog(runCatching { cacheFile.readText() }.getOrNull(), offline = true)
                ?: PluginCatalog(updated = "", categoryTitles = emptyMap(), plugins = emptyList(), offline = true)
        }

    /** 目录源，按顺序尝试。每个返回原始 JSON 文本或抛异常。 */
    private fun catalogSources(): List<() -> String?> = listOf(
        { httpGet(CATALOG_ORIGIN) },
        { catalogFromNpm(NPM_REGISTRY) },
        { catalogFromNpm(NPM_MIRROR_CN) },
    )

    /**
     * 从 npm 包里取 `package/plugins.json`。
     *
     * 跟着 `dist.tarball` 走而不是自己拼 URL：镜像会把这个字段改写成自己的地址，
     * 自己拼就会把下载弹回官方 registry，正好绕掉了用镜像的意义。
     */
    private fun catalogFromNpm(registry: String): String? {
        val meta = httpGet("$registry/$CATALOG_PACKAGE/latest") ?: return null
        val tarball = JSONObject(meta).optJSONObject("dist")?.optString("tarball")
        if (tarball.isNullOrBlank()) return null
        val gz = httpGetBytes(tarball) ?: return null
        val bytes = fileFromTarball(gz, "package/plugins.json") ?: return null
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * gzip tar 里取一个指定条目。
     *
     * 自己读而不是加依赖：格式就是 512 字节头（name@0、八进制 size@124、type@156），
     * 为一个已知文件名写读取器比为插件运行时多加一个包更划算。
     */
    private fun fileFromTarball(gz: ByteArray, wanted: String): ByteArray? = runCatching {
        val buf = java.util.zip.GZIPInputStream(gz.inputStream()).use { it.readBytes() }
        var offset = 0
        while (offset + TAR_BLOCK <= buf.size) {
            val name = String(buf, offset, TAR_NAME_LEN, Charsets.UTF_8).substringBefore('\u0000')
            // 连着两个空头才是结尾，但一个就足够停下
            if (name.isEmpty()) break
            val rawSize = String(buf, offset + TAR_SIZE_OFF, TAR_SIZE_LEN, Charsets.US_ASCII)
                .substringBefore('\u0000').trim()
            val size = rawSize.toLongOrNull(8) ?: break
            if (size < 0) break
            val type = buf[offset + TAR_TYPE_OFF].toInt().toChar()
            offset += TAR_BLOCK
            // '0' 与 NUL 都表示普通文件；目录、链接、pax 头一概跳过
            if ((type == '0' || type == '\u0000') && name == wanted) {
                return@runCatching buf.copyOfRange(offset, (offset + size).toInt())
            }
            offset += (((size + TAR_BLOCK - 1) / TAR_BLOCK) * TAR_BLOCK).toInt()
        }
        null
    }.getOrNull()

    /** 解析目录 JSON。结构不对（比如取到一个 HTML 错误页）时返回 null，让调用方换源。 */
    private fun parseCatalog(text: String?, offline: Boolean): PluginCatalog? = runCatching {
        if (text.isNullOrBlank()) return@runCatching null
        val root = JSONObject(text)
        val arr = root.optJSONArray("plugins") ?: return@runCatching null
        if (arr.length() == 0) return@runCatching null

        val zh = java.util.Locale.getDefault().language == "zh"
        val titles = HashMap<String, String>()
        root.optJSONObject("categories")?.let { cats ->
            val keys = cats.keys()
            while (keys.hasNext()) {
                val slug = keys.next()
                val o = cats.optJSONObject(slug) ?: continue
                val label = (if (zh) o.optString("zh") else o.optString("en"))
                    .ifBlank { o.optString("en") }
                if (label.isNotBlank()) titles[slug] = label
            }
        }

        val plugins = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name")
            if (name.isEmpty()) return@mapNotNull null
            val owner = o.optString("owner")
            // npm 字段可能是 JSON null（1284/2495 条如此）：optString 会给出 "null"
            val npm = o.optString("npm").takeIf { it.isNotEmpty() && it != "null" } ?: ""
            val desc = o.optJSONObject("description")?.let { d ->
                (if (zh) d.optString("zh") else d.optString("en")).ifBlank { d.optString("en") }
            } ?: o.optString("description")
            DshPlugin(
                // owner/name 才唯一：不同作者的同名插件在目录里并不少见
                id = if (owner.isEmpty()) name else "$owner/$name",
                pkg = npm,
                name = name,
                description = desc,
                author = owner,
                repo = normalizeRepo(o.optString("url")),
                homepage = o.optString("page").ifEmpty { o.optString("url") },
                category = o.optString("category"),
                // 目录自带这两个数字，商店列表不必再逐包打 npm / GitHub API
                stars = if (o.isNull("stars")) -1L else o.optLong("stars", -1L),
                downloads = if (o.isNull("downloads")) -1L else o.optLong("downloads", -1L),
                // 没登记 npm 的条目用 install 命令里的 github: 规格安装
                installSpec = o.optString("install").substringAfterLast(' ').ifEmpty { npm },
            )
        }
        if (plugins.isEmpty()) return@runCatching null
        PluginCatalog(
            updated = root.optString("updated"),
            categoryTitles = titles,
            plugins = plugins,
            offline = offline,
        )
    }.getOrNull()

    /** dsh-market 的整表周下载量（key 是 npm 包名）。 */
    private fun downloadsTable(): Map<String, Long> {
        val now = System.currentTimeMillis()
        if (downloadsTable.isNotEmpty() && now - downloadsAt < CATALOG_TTL_MS) return downloadsTable
        val table = runCatching {
            val json = httpGet(MARKET_DOWNLOADS) ?: return@runCatching emptyMap()
            val o = JSONObject(json).optJSONObject("downloads") ?: return@runCatching emptyMap()
            buildMap {
                for (k in o.keys()) put(k, o.optLong(k, -1L))
            }
        }.getOrElse {
            Log.w(TAG, "下载量表获取失败: ${it.message}")
            emptyMap()
        }
        if (table.isNotEmpty()) {
            downloadsTable = table
            downloadsAt = now
        }
        return table
    }

    /** dsh-market 的点赞表（key 是目录 id）。 */
    private fun likesTable(): Map<String, Long> {
        val now = System.currentTimeMillis()
        if (likesTable.isNotEmpty() && now - likesAt < CATALOG_TTL_MS) return likesTable
        val table = runCatching {
            val json = httpGet(MARKET_STATS) ?: return@runCatching emptyMap()
            val o = JSONObject(json).optJSONObject("plugin") ?: return@runCatching emptyMap()
            buildMap {
                for (k in o.keys()) put(k, o.optLong(k, -1L))
            }
        }.getOrElse { emptyMap() }
        if (table.isNotEmpty()) {
            likesTable = table
            likesAt = now
        }
        return table
    }

    /** 整表里没有这个包时的单包兜底查询。 */
    fun downloadsOf(pkg: String): Long = runCatching {
        val json = httpGet(NPM_DOWNLOADS_POINT + pkg) ?: return@runCatching -1L
        JSONObject(json).optLong("downloads", -1L)
    }.getOrDefault(-1L)

    /** npm 上的最新版本号。 */
    fun latestVersionOf(pkg: String): String {
        versionCache[pkg]?.let { (v, at) ->
            if (System.currentTimeMillis() - at < CACHE_TTL_MS) return v
        }
        val v = runCatching {
            val json = httpGet("https://registry.npmjs.org/$pkg/latest") ?: return@runCatching ""
            JSONObject(json).optString("version")
        }.getOrDefault("")
        if (v.isNotEmpty()) versionCache[pkg] = v to System.currentTimeMillis()
        return v
    }

    /** GitHub star。repo 形如 `owner/name`。 */
    fun starsOf(repo: String): Long {
        starCache[repo]?.let { (v, at) ->
            if (System.currentTimeMillis() - at < CACHE_TTL_MS) return v
        }
        val v = runCatching {
            val json = httpGet("https://api.github.com/repos/$repo") ?: return@runCatching -1L
            JSONObject(json).optLong("stargazers_count", -1L)
        }.getOrDefault(-1L)
        if (v >= 0) starCache[repo] = v to System.currentTimeMillis()
        return v
    }

    // 容器内已安装插件：读 profile 的 package.json dependencies，再从 node_modules 取实际版本。
    // 依赖清单才是权威来源 —— node_modules 里还躺着一大堆传递依赖，直接扫目录会把
    // 上千个无关包全当成「已安装插件」。@deepseek-ai/dsh-base 之类出厂 bundle 不是依赖，
    // 天然不会出现在这里。
    // 注意不要把这段写成 KDoc：路径里的通配符会提前闭合块注释。
    suspend fun listInstalled(): List<DshPlugin> = withContext(Dispatchers.IO) {
        val script = "const fs=require('fs'),path=require('path');" +
            "const dir=process.argv[1];" +
            "let m;try{m=JSON.parse(fs.readFileSync(path.join(dir,'package.json'),'utf8'))}catch(e){process.exit(0)}" +
            "const b=new Set((m.dsh&&m.dsh.profile&&m.dsh.profile.bundles)||[]);" +
            "for(const n of Object.keys(m.dependencies||{})){" +
            "let v='',d='';" +
            "try{const q=JSON.parse(fs.readFileSync(path.join(dir,'node_modules',n,'package.json'),'utf8'));" +
            "v=q.version||'';d=q.description||''}catch(e){}" +
            "console.log([n,v,d,b.has(n)?'1':'0'].join('\\t'))}"
        val out = DshRuntime.execRootfsForOutput(
            "node -e \"$script\" " + PROFILE_DIR + " 2>/dev/null",
            60_000,
        )
        out.lines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 2 || parts[0].isBlank()) return@mapNotNull null
            val pkg = parts[0].trim()
            val ver = parts[1].trim()
            DshPlugin(
                id = pkg,
                pkg = pkg,
                name = pkg,
                version = ver,
                description = parts.getOrElse(2) { "" }.trim(),
                installedVersion = ver,
                // 缺这一列（旧格式输出）时按生效算，别把已装插件全标成停用
                enabled = parts.getOrElse(3) { "1" }.trim() != "0",
            )
        }.distinctBy { it.pkg }
    }

    /**
     * 安装/更新一个插件。
     *
     * 必须走 `dsh plugin --profile web add`，不能自己 npm install：
     * dsh 只加载 profile package.json 里 `dsh.profile.bundles` 列出的包，而这个列表是
     * `dsh plugin` 成功后 reconcile 出来的（判据是包自己声明 `dsh.bundle.patch`）。
     * 裸装进任何目录都只是躺在磁盘上，不会被加载。
     */
    suspend fun install(
        pkg: String,
        version: String = "",
        onLine: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        if (pkg.isBlank()) return@withContext "包名为空，无法安装"
        val resolved = resolveSpec(pkg, onLine)
        val spec = if (version.isBlank()) resolved else "$resolved@$version"
        if (spec.startsWith("github:") || spec.startsWith("git+")) ensureGit(onLine)
        val out = dshPlugin("add ${importFlag()}'$spec'", 900_000, onLine)
        out + repairIfLinkageBroken(onLine)
    }

    /**
     * git 规格安装前确保容器里有 git。
     *
     * 目录里 2659 条有 1357 条（51%）的 install 是 `github:` 规格，而 ubuntu-base
     * 里没有 git —— pnpm 直接报 `git ls-remote failed: git executable not found`。
     *
     * 新版 runtime 已把 git 预解包进 rootfs（见 runtime-builder/build-rootfs.sh），
     * 这里只兜存量安装：装过一次就写标记，不必每次都探。
     *
     * apt 在 proot 下不保证成功（builder 当初正是因为这个才改成预解包 python3），
     * 所以失败也继续往下走原安装命令 —— 用户至少能看到完整的 apt 报错，
     * 而不是一句没有上下文的 `git executable not found`。
     */
    private fun ensureGit(onLine: (String) -> Unit) {
        val probe = DshRuntime.execRootfsForOutput(
            "test -f '$GIT_READY_MARK' && command -v git >/dev/null 2>&1 && echo HAVE_GIT; " +
                "command -v git >/dev/null 2>&1 && echo GIT_ON_PATH",
            60_000,
        )
        if (probe.contains("GIT_ON_PATH")) {
            if (!probe.contains("HAVE_GIT")) {
                runCatching { DshRuntime.execRootfsForOutput("touch '$GIT_READY_MARK'", 30_000) }
            }
            return
        }
        onLine("[DSH-Folk] 这个插件来自 git 源，但容器里没有 git，正在安装（apt，可能要几分钟）…")
        val out = DshRuntime.execRootfsStreaming(
            "apt-get update -qq 2>&1 | tail -5; " +
                "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends git 2>&1 | tail -30; " +
                "command -v git >/dev/null 2>&1 && { mkdir -p \"\$(dirname '$GIT_READY_MARK')\"; " +
                "touch '$GIT_READY_MARK'; echo '[DSH-Folk] git 已就绪'; } || " +
                "echo '[DSH-Folk] git 安装失败，下面的安装大概率也会失败；请更新运行时（新版已内置 git）'",
            600_000,
            onLine,
        )
        android.util.Log.i("DshPluginRepo", "ensureGit: ${out.takeLast(200)}")
    }

    /**
     * 把目录给的 `github:owner/name` 规格换成 npm 包名 —— **仅当能证明是同一个包**。
     *
     * 起因：目录里 `dsh-web-mobile` 的 npm 字段是 null，商店因此发
     * `add github:mexiaosqwq/dsh-web-mobile`（容器没 git → 失败），而这个包其实在
     * npm 上有（2.3.0，repository 指向同一个仓库），用户在终端手敲
     * `add dsh-web-mobile` 就成功了。目录数据陈旧。
     *
     * **必须校验 repository**：实测抽 60 条 github: 条目，23 条在 npm 上有同名包，
     * 其中只有 1 条 repo 对得上 —— 另外 22 条是**别的作者的同名包**（例如目录里
     * `dsh-skin-switcher` 属 tsdfy，npm 上那个是 zhtx2024 的）。照名安装等于装错东西，
     * 比装不上更糟。校验后命中率约 2%，这条回退只解决"目录漏登记 npm"这一种情况，
     * 不能替代容器里的 git。
     *
     * 探测失败、超时、repo 不匹配 —— 一律沿用原 git 规格。
     */
    private fun resolveSpec(spec: String, onLine: (String) -> Unit): String {
        if (!spec.startsWith("github:")) return spec
        val ownerName = spec.removePrefix("github:")
        // 只处理干净的 owner/name；带 #path: / #ref 的子目录规格换不成 npm 名
        if (!Regex("^[\\w.-]+/[\\w.-]+$").matches(ownerName)) return spec
        specCache[ownerName]?.let { return it.ifEmpty { spec } }
        val name = ownerName.substringAfter('/')
        val resolved = runCatching {
            val json = httpGet("$NPM_REGISTRY/$name/latest") ?: return@runCatching ""
            val o = JSONObject(json)
            val repo = o.optJSONObject("repository")?.optString("url")
                ?: o.optString("repository")
            if (repo.contains(ownerName, ignoreCase = true)) name else ""
        }.getOrDefault("")
        specCache[ownerName] = resolved
        if (resolved.isEmpty()) return spec
        onLine("[DSH-Folk] 目录未登记 npm 包名；已核对 npm 上的 $name 指向同一仓库（$ownerName），改用 npm 安装以避开 git")
        return resolved
    }

    /** `github:owner/name` → 已核实的 npm 名；空串表示核实过但不可用。 */
    private val specCache = mutableMapOf<String, String>()

    /** 卸载一个插件（同样交给 dsh plugin，才会从 bundles 里摘掉）。 */
    suspend fun uninstall(
        pkg: String,
        onLine: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        if (pkg.isBlank()) return@withContext "包名为空，无法卸载"
        // remove 不从 store 导入文件，不需要 importFlag
        dshPlugin("remove '$pkg'", 600_000, onLine)
    }

    /** 本地安装：宿主 tgz 路径已由调用方复制进容器可见位置。 */
    suspend fun installLocal(
        containerPath: String,
        onLine: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        // 绝对路径原样传给 pnpm（dsh 只重写相对路径 spec），tgz 装完同样会被 reconcile
        val out = dshPlugin("add ${importFlag()}'$containerPath'", 900_000, onLine)
        out + repairIfLinkageBroken(onLine)
    }

    /**
     * 安装命令上的导入方式标志（带尾随空格，无需时为空串）。
     *
     * 为什么命令行和 pnpm-workspace.yaml 两处都要：profile **首次**初始化时
     * `pnpm-workspace.yaml` 是 dsh 在同一次 `dsh plugin` 调用里现写的，我们来不及
     * 提前追加（抢先创建会把 dsh 的模板弄丢，见 DshRuntime.ensureProfilePnpmSettings），
     * 那一次只有 CLI 标志能覆盖。之后由 workspace 配置长期生效，也覆盖用户在终端页
     * 手敲的 pnpm。
     *
     * 位置放在子命令之后、包名之前：已实测 dsh 的 anchorPathSpec 只重写 `./`、`../`
     * 开头的相对路径 spec，不碰 `--` 开头的参数。
     */
    private fun importFlag(): String =
        if (DshRuntime.linkBecomesSymlink()) "--package-import-method copy " else ""

    /**
     * 装完顺手检查依赖是不是被 l2s 装成了指向内容存储的符号链接；是就立刻重建。
     *
     * 为什么装完就修而不是只给个按钮：症状出现在**下一次启动服务**时
     * （`MissingClientBundleError`），用户很难从「装好了」推断出「要重建依赖」。
     *
     * npmrc 里的 `package-import-method=copy` 只影响**新**安装，已经装坏的包必须
     * 重跑一次才会变成真实副本，所以两者必须同时存在。
     */
    private suspend fun repairIfLinkageBroken(onLine: (String) -> Unit): String {
        if (!DshRuntime.linkBecomesSymlink()) return ""
        if (!storeLinkageBroken()) return ""
        onLine("[DSH-Folk] 检测到依赖被装成指向内容存储的链接，正在重建（清空 node_modules 后重装，可能要几分钟）…")
        return "\n" + repairStore(onLine)
    }

    /**
     * profile 依赖里是否有包的 realpath 落在 pnpm 内容存储内。
     *
     * 这正是插件起不来的判据：dsh 用 `require.resolve(<pkg>/package.json)` 定位包，
     * Node 默认 realpath，链接被 l2s 改写后就解析成 `<store>/files/<xx>/<hash>`，
     * 再拼相对的 `./lib/client.cjs` 必然 ENOENT（CAS 里只有扁平哈希文件）。
     *
     * 只认 `/pnpm/store/`：若 pnpm 将来改了 CAS 布局，这里退化成「不修」，
     * 不会反过来误触发一次几分钟的重建。
     */
    suspend fun storeLinkageBroken(): Boolean = withContext(Dispatchers.IO) {
        val script = "const fs=require('fs'),path=require('path');" +
            "const dir=process.argv[1];" +
            "let m;try{m=JSON.parse(fs.readFileSync(path.join(dir,'package.json'),'utf8'))}catch(e){process.exit(0)}" +
            "for(const n of Object.keys(m.dependencies||{})){" +
            "try{const r=fs.realpathSync(path.join(dir,'node_modules',n,'package.json'));" +
            "if(r.includes('/pnpm/store/')){console.log('BROKEN');break}}catch(e){}}"
        val out = DshRuntime.execRootfsForOutput(
            "node -e \"$script\" " + PROFILE_DIR + " 2>/dev/null",
            120_000,
        )
        out.contains("BROKEN")
    }

    /**
     * 重建 profile 依赖：**先清空 node_modules 再重装**。
     *
     * 不能只用 `pnpm install --force`。已实测：lockfile 与 node_modules 都满足时
     * `--force` 只报 `Already up to date` 就退出（真机日志里 561ms 就跑完了），
     * 它的语义是「跳过 up-to-date 检查、重新解析」，不是「重新导入已就位的文件」。
     * 删掉 node_modules 后重装才真正换成 copy 导入（实测 nlink 从 3 变 1）。
     *
     * **保留 pnpm-lock.yaml**：删掉它会让所有依赖重新解析版本，可能把用户装好的
     * 东西升到别的版本。留着则解析结果不变，只是重新导入。
     *
     * 走 [dshPlugin] 而不是直接调 pnpm：`dsh plugin` 会把参数原样转发给 pnpm，
     * 同时保留它自己的 bundles reconcile —— 绕过它会让 `dsh.profile.bundles`
     * 与实际安装状态失步。
     *
     * 超时给到 15 分钟：copy 模式下几十个依赖全量复制比硬链接慢得多。
     */
    suspend fun repairStore(onLine: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        // 删之前确认这确实是个已初始化的 profile：目录异常时不对着空路径递归删。
        // 路径写死为常量，不接受任何外部输入拼接。
        val probe = DshRuntime.execRootfsForOutput(
            "test -f '$PROFILE_DIR/package.json' && echo OK",
            30_000,
        )
        if (!probe.contains("OK")) {
            onLine("[DSH-Folk] profile 尚未初始化（找不到 $PROFILE_DIR/package.json），跳过重建")
            return@withContext "profile 尚未初始化，跳过重建"
        }
        onLine("[DSH-Folk] 清空 node_modules（保留 pnpm-lock.yaml）…")
        DshRuntime.execRootfsForOutput("rm -rf '$PROFILE_DIR/node_modules'", 120_000)
        dshPlugin("install ${importFlag()}".trimEnd(), 900_000, onLine)
    }

    // ────────────────────────── 安装后验证 / 回滚 ──────────────────────────

    /** profile `dsh.profile.bundles` 当前列表（判定安装带来了哪个新包）。 */
    suspend fun bundles(): List<String> = withContext(Dispatchers.IO) {
        val script = "const fs=require('fs'),path=require('path');" +
            "try{const m=JSON.parse(fs.readFileSync(path.join(process.argv[1],'package.json'),'utf8'));" +
            "for(const b of (m.dsh&&m.dsh.profile&&m.dsh.profile.bundles)||[])console.log(b)}catch(e){}"
        DshRuntime.execRootfsForOutput("node -e \"$script\" $PROFILE_DIR 2>/dev/null", 60_000)
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * 用 `dsh web --port 0` 就地验证插件树能不能结算。
     *
     * 判据可靠：`dsh web: http://…` 这一行只在 loader 结算之后才打印 ——
     * dsh-web-app 里是 `settled.then(() => announceReady())`，插件树没结算就永远
     * 不会有这行。实测 `--port 0` 会挑一个系统空闲端口（例如 38689），不碰 3080。
     *
     * 为什么就地验证而不是在临时 DSH_HOME 里试装：`packageImportMethod: copy`
     * 已经关掉了 CAS 去重，复制整个真实 profile 的依赖（真机上两百多个包）会实打实
     * 多占几百 MB，手机上不可接受；而只按模板建轻量组合又测不出与用户已装插件的冲突，
     * 那恰恰是主要价值。代价是失败时真实 profile 被短暂改动过 —— 但新插件要重启才
     * 生效，验证期间正在跑的服务不受影响，回滚也是我们自己发起的确定动作。
     *
     * **不能用 `node … | while read` 那种管道**：`while` 里 break 只结束了读循环，
     * 而 `dsh web` 打完就绪行就一直在提供 HTTP 服务、不再写 stdout，所以它既收不到
     * SIGPIPE 也不会退出；bash 要等齐整条管道的所有进程，于是自己也不退出，
     * 宿主侧的读流拿不到 EOF —— 界面就卡在「验证中」直到 [VERIFY_TIMEOUT_MS] 兜底
     * （真机表现：装完插件对话框卡住，强杀应用后发现其实早装好了）。
     *
     * 改成：后台起 node 并把输出重定向到文件，主循环轮询文件增量转发，命中就绪行后
     * 显式 kill 掉它。这样 bash 自己掌握 node 的生命周期，命中即退出。
     */
    suspend fun verifyBoot(onLine: (String) -> Unit): String? = withContext(Dispatchers.IO) {
        onLine("[DSH-Folk] 正在验证插件能否启动（临时端口，不影响当前服务）…")
        val script = buildString {
            append("export DSH_HOME=/root/.dsh; export BROWSER=true; ")
            append("mkdir -p /root/workspace /root/.dsh 2>/dev/null; cd /root/workspace; ")
            append("DSH_REAL=\$(readlink -f \"\$(command -v dsh)\" 2>/dev/null || command -v dsh); ")
            append("LOG='$VERIFY_LOG'; : > \"\$LOG\"; ")
            // 后台跑，输出进文件：绝不能让它待在管道里（见 KDoc）
            append("node --expose-internals \"\$DSH_REAL\" web --port 0 > \"\$LOG\" 2>&1 & ")
            append("PID=\$!; SENT=0; OK=0; I=0; ")
            append("while [ \$I -lt $VERIFY_POLLS ]; do ")
            append("I=\$((I+1)); ")
            // 增量转发：只吐新增的行，界面才有实时进度
            append("TOTAL=\$(wc -l < \"\$LOG\" 2>/dev/null | tr -d ' '); [ -n \"\$TOTAL\" ] || TOTAL=0; ")
            append("if [ \"\$TOTAL\" -gt \"\$SENT\" ]; then tail -n +\$((SENT+1)) \"\$LOG\"; SENT=\$TOTAL; fi; ")
            append("if grep -q '^dsh web: http' \"\$LOG\" 2>/dev/null; then OK=1; break; fi; ")
            // 进程提前退出 = 启动失败，再收一次尾巴就够了
            append("kill -0 \"\$PID\" 2>/dev/null || break; ")
            append("sleep 1; done; ")
            append("TOTAL=\$(wc -l < \"\$LOG\" 2>/dev/null | tr -d ' '); [ -n \"\$TOTAL\" ] || TOTAL=0; ")
            append("[ \"\$TOTAL\" -gt \"\$SENT\" ] && tail -n +\$((SENT+1)) \"\$LOG\"; ")
            append("kill \"\$PID\" 2>/dev/null; sleep 1; kill -9 \"\$PID\" 2>/dev/null; ")
            append("[ \"\$OK\" = 1 ] && echo '$VERIFY_OK_MARK'; true")
        }
        val out = DshRuntime.execRootfsStreaming(script, VERIFY_TIMEOUT_MS, onLine)
        // 收尾：node 可能有子孙进程没随 kill 一起走
        runCatching {
            DshRuntime.execRootfsForOutput("pkill -f 'web --port 0' 2>/dev/null; true", 30_000)
        }
        if (out.contains(VERIFY_OK_MARK)) return@withContext null
        val key = out.lineSequence().firstOrNull { l ->
            VERIFY_FAILURE_MARKS.any { l.contains(it, ignoreCase = true) }
        }
        key ?: "验证超时或进程提前退出（未出现就绪行）"
    }

    /**
     * 回滚一次失败的安装。
     *
     * 包名必须取 `dsh.profile.bundles` 的新增项，不能用安装规格：
     * `github:owner/name` 装出来的包名跟规格根本不是一回事，拿规格去 remove 会失败。
     */
    suspend fun rollback(pkg: String, onLine: (String) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            if (pkg.isBlank()) return@withContext false
            onLine("[DSH-Folk] 验证未通过，正在自动卸载 $pkg …")
            val out = dshPlugin("remove '$pkg'", 600_000, onLine)
            val marker = out.lineSequence().lastOrNull { it.startsWith(EXIT_MARKER) }
            val code = marker?.removePrefix(EXIT_MARKER)?.trim()?.toIntOrNull()
            val ok = code == 0
            if (!ok) onLine("[DSH-Folk] 自动卸载失败，请到「已安装」页手动卸载 $pkg")
            ok
        }

    /** 暂存目录在容器里的绝对路径。 */
    private const val INCOMING_GUEST = "/root/.dsh/incoming"

    /**
     * 把用户选的 .tgz 落进容器可见目录，返回容器内绝对路径；失败返回 null。
     *
     * 容器只看得到 rootfs 内的路径，SAF 的 content:// Uri 更是传不进去，所以必须先拷。
     *
     * 校验不能省：`openInputStream` 对已撤销授权 / 已删除的 Uri 会返回 **null**，
     * 而 `?.use {}` 在 null 时整块跳过且不抛异常 —— 原来两处调用点因此会把一个不存在
     * 或 0 字节的路径交给 `dsh plugin add`，用户看到的是 pnpm 的一句 ENOENT。
     *
     * 顺带清掉上次留下的暂存文件：每次本地安装都写一个带时间戳的新文件，
     * 装完谁也不删，rootfs 里会一直堆着。
     */
    fun stageTarball(ctx: Context, uri: Uri): String? {
        val dir = File(DshEnv.dshHome(ctx), "incoming")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        // 只留这一次的文件：tgz 动辄几 MB，堆在 rootfs 里没人清
        runCatching { dir.listFiles()?.forEach { it.delete() } }
        val dst = File(dir, "local-plugin-${System.currentTimeMillis()}.tgz")
        val ok = runCatching {
            val input = ctx.contentResolver.openInputStream(uri) ?: return@runCatching false
            input.use { src -> dst.outputStream().use { src.copyTo(it) } }
            dst.isFile && dst.length() > 0L
        }.getOrDefault(false)
        if (!ok) {
            runCatching { dst.delete() }
            return null
        }
        return "$INCOMING_GUEST/${dst.name}"
    }

    /**
     * 跑一条 `dsh plugin --profile <PROFILE> <args>`。
     *
     * dsh 是 wrapper 脚本，和启动 web 一样得先 readlink 出真正的 bin.js 再交给 node
     * （`--expose-internals` 只能作为命令行参数传）。pnpm 装包要几分钟，输出只留尾部。
     */
    /** 成功与否的唯一可靠依据：这一行里的退出码。 */
    const val EXIT_MARKER = "[DSH-Folk-exit]"

    /**
     * 跑 `dsh plugin --profile web <args>` 并回读输出，逐行回调 onLine。
     *
     * 结尾必须带退出码：不带的话只能靠在输出里找 "pnpm failed" 之类的字样猜
     * 成败——pnpm 换个措辞就会把失败当成功（表现为「装好了但插件不在」）。
     */
    private fun dshPlugin(
        args: String,
        timeoutMs: Long,
        onLine: (String) -> Unit = {},
    ): String {
        val out = DshRuntime.execRootfsStreaming(
            "export DSH_HOME=/root/.dsh; cd /root; " +
                "if ! command -v dsh >/dev/null 2>&1; then echo '[DSH-Folk] 容器内找不到 dsh'; exit 1; fi; " +
                "if ! command -v pnpm >/dev/null 2>&1; then " +
                "echo '[DSH-Folk] 容器内找不到 pnpm，请在设置中重装运行时'; exit 1; fi; " +
                "DSH_REAL=\$(readlink -f \"\$(command -v dsh)\" 2>/dev/null || command -v dsh); " +
                // 不再接 `| tail -30`：输出现在是逐行流式回调的，界面自己保留
                // 滚动日志；而 tail 会把 pnpm 的输出攒到结束才一次吐出来，正好把
                // 实时进度堵死。失败时也不再因为被截而丢掉关键报错行。
                "node --expose-internals \"\$DSH_REAL\" plugin --profile " + PROFILE + " " + args +
                " 2>&1; " +
                "echo \"" + EXIT_MARKER + " \$?\"",
            timeoutMs,
            onLine,
        )
        return out.ifBlank { "没有输出（可能超时或容器未启动）" }
    }

    private fun httpGet(url: String): String? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 12_000
        conn.readTimeout = 12_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "DSH-Folk")
        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            return@runCatching null
        }
        conn.inputStream.bufferedReader().use { it.readText() }
    }.getOrNull()

    /** 二进制下载（目录的 npm tarball 回落用）。超时给得宽些：几百 KB 的包。 */
    private fun httpGetBytes(url: String): ByteArray? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "DSH-Folk")
        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            return@runCatching null
        }
        conn.inputStream.use { it.readBytes() }
    }.getOrNull()

    /** 各种 repository 写法统一成 `owner/name`。 */
    private fun normalizeRepo(raw: String): String {
        if (raw.isBlank()) return ""
        var s = raw.trim()
            .removePrefix("git+")
            .removePrefix("git://")
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("ssh://git@")
            .removePrefix("git@")
            .removeSuffix(".git")
        s = s.replace("github.com:", "github.com/")
        val idx = s.indexOf("github.com/")
        if (idx >= 0) s = s.substring(idx + "github.com/".length)
        val parts = s.split('/').filter { it.isNotBlank() }
        return if (parts.size >= 2) "${parts[0]}/${parts[1]}" else ""
    }
}

/**
 * semver 比较（够用版）：先比 major.minor.patch，相同再比预发布标识。
 *
 * 预发布必须参与比较，否则 `1.0.0-rc.1` 与 `1.0.0` 会判成相等 —— 用户装着 rc
 * 却看不到正式版的更新。规则同 semver：有预发布标识的版本小于没有的，
 * 都有则按点分段逐段比（数字段按数值，其余按字典序）。
 */
internal fun compareVersions(a: String, b: String): Int {
    fun clean(v: String) = v.trim().removePrefix("v").substringBefore('+')
    fun core(v: String) = clean(v).substringBefore('-')
    fun pre(v: String) = clean(v).substringAfter('-', "")

    val pa = core(a).split('.')
    val pb = core(b).split('.')
    for (i in 0 until maxOf(pa.size, pb.size)) {
        // 缺的段按 0 补：1.0 与 1.0.0 是同一个版本，别比出 -1
        val sa = pa.getOrNull(i) ?: "0"
        val sb = pb.getOrNull(i) ?: "0"
        val x = sa.toIntOrNull()
        val y = sb.toIntOrNull()
        if (x == null || y == null) {
            val c = sa.compareTo(sb)
            if (c != 0) return c
        } else if (x != y) {
            return x.compareTo(y)
        }
    }

    val qa = pre(a)
    val qb = pre(b)
    if (qa == qb) return 0
    // 正式版 > 预发布版
    if (qa.isEmpty()) return 1
    if (qb.isEmpty()) return -1
    val ra = qa.split('.')
    val rb = qb.split('.')
    for (i in 0 until maxOf(ra.size, rb.size)) {
        val x = ra.getOrNull(i)
        val y = rb.getOrNull(i)
        if (x == null) return -1
        if (y == null) return 1
        val nx = x.toIntOrNull()
        val ny = y.toIntOrNull()
        val c = if (nx != null && ny != null) nx.compareTo(ny) else x.compareTo(y)
        if (c != 0) return c
    }
    return 0
}
