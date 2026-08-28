package me.bmax.apatch.dsh

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 一个 DSH 插件条目。 */
data class DshPlugin(
    /** npm 包名，如 `dsh-tui`。 */
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val author: String = "",
    /** 上游仓库（用于取 star）。 */
    val repo: String = "",
    val homepage: String = "",
    /** 已安装版本，未安装为空。 */
    val installedVersion: String = "",
    /** 近一周 npm 下载量，-1 表示未知。 */
    val downloads: Long = -1L,
    /** GitHub star，-1 表示未知。 */
    val stars: Long = -1L,
    val enabled: Boolean = true,
) {
    val installed: Boolean get() = installedVersion.isNotEmpty()

    /** 已安装且线上版本更高 → 可更新。 */
    val updatable: Boolean
        get() = installed && version.isNotEmpty() && compareVersions(version, installedVersion) > 0
}

/**
 * DSH 插件数据源。
 *
 * 三个来源各管一件事，缺一个不影响其它：
 * - **dsh-market.com** 提供插件目录（名称/描述/仓库）。它的 `api/stats` 只有点赞数，
 *   没有下载量，所以下载量另取；
 * - **npm registry** 提供版本与近一周下载量（`api.npmjs.org/downloads/point/last-week/<pkg>`）；
 * - **GitHub API** 提供 star 数（匿名 60 次/小时，所以本地缓存 6 小时）。
 *
 * 已安装列表来自容器内 `DSH_HOME/plugins`，走 proot 读，不依赖网络。
 */
object DshPluginRepo {
    private const val TAG = "DSH-Folk-Plugins"
    private const val MARKET_LIST = "https://dsh-market.com/api/plugins"
    private const val NPM_SEARCH = "https://registry.npmjs.org/-/v1/search?size=100&text="
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L

    private val starCache = ConcurrentHashMap<String, Pair<Long, Long>>()      // repo -> (star, at)
    private val downloadCache = ConcurrentHashMap<String, Pair<Long, Long>>()  // pkg  -> (dl, at)

    /** 拉线上插件目录并补齐下载量 / star。 */
    suspend fun fetchCatalog(): List<DshPlugin> = withContext(Dispatchers.IO) {
        val base = fetchMarket().ifEmpty { fetchNpmFallback() }
        if (base.isEmpty()) return@withContext emptyList()
        val installed = listInstalled().associateBy { it.id }
        enrich(base.map { p -> p.copy(installedVersion = installed[p.id]?.installedVersion ?: "") })
    }

    /** 并发补齐下载量与 star（各自失败只让那一项保持 -1）。 */
    private suspend fun enrich(list: List<DshPlugin>): List<DshPlugin> = coroutineScope {
        list.map { p ->
            async {
                val dl = async { downloadsOf(p.id) }
                val st = async { if (p.repo.isEmpty()) -1L else starsOf(p.repo) }
                p.copy(downloads = dl.await(), stars = st.await())
            }
        }.map { it.await() }
    }

    /** dsh-market 目录。 */
    private fun fetchMarket(): List<DshPlugin> = runCatching {
        val json = httpGet(MARKET_LIST) ?: return@runCatching emptyList()
        val arr = runCatching { JSONArray(json) }.getOrElse {
            JSONObject(json).optJSONArray("plugins") ?: JSONArray()
        }
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("name").ifEmpty { o.optString("id") }
            if (id.isEmpty()) return@mapNotNull null
            DshPlugin(
                id = id,
                name = o.optString("displayName").ifEmpty { id },
                version = o.optString("version"),
                description = o.optString("description"),
                author = o.optString("author"),
                repo = normalizeRepo(o.optString("repository").ifEmpty { o.optString("repo") }),
                homepage = o.optString("homepage"),
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

    /** 近一周 npm 下载量。 */
    fun downloadsOf(pkg: String): Long {
        cached(downloadCache, pkg)?.let { return it }
        val v = runCatching {
            val json = httpGet("https://api.npmjs.org/downloads/point/last-week/$pkg")
                ?: return@runCatching -1L
            JSONObject(json).optLong("downloads", -1L)
        }.getOrDefault(-1L)
        if (v >= 0) downloadCache[pkg] = v to System.currentTimeMillis()
        return v
    }

    /** GitHub star。repo 形如 `owner/name`。 */
    fun starsOf(repo: String): Long {
        cached(starCache, repo)?.let { return it }
        val v = runCatching {
            val json = httpGet("https://api.github.com/repos/$repo") ?: return@runCatching -1L
            JSONObject(json).optLong("stargazers_count", -1L)
        }.getOrDefault(-1L)
        if (v >= 0) starCache[repo] = v to System.currentTimeMillis()
        return v
    }

    /** 容器内已安装插件（读 `DSH_HOME/plugins/*/package.json`）。 */
    suspend fun listInstalled(): List<DshPlugin> = withContext(Dispatchers.IO) {
        val out = DshRuntime.execRootfsForOutput(
            "for d in /root/.dsh/plugins/*/; do " +
                "test -f \"\$d/package.json\" || continue; " +
                "n=\$(node -e 'try{const p=require(process.argv[1]+\"/package.json\");" +
                "console.log([p.name,p.version,p.description||\"\"].join(\"\\t\"))}catch(e){}' \"\$d\" 2>/dev/null); " +
                "test -n \"\$n\" && echo \"\$n\"; done",
            45_000,
        )
        out.lines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 2 || parts[0].isBlank()) return@mapNotNull null
            DshPlugin(
                id = parts[0].trim(),
                name = parts[0].trim(),
                version = parts[1].trim(),
                description = parts.getOrElse(2) { "" }.trim(),
                installedVersion = parts[1].trim(),
            )
        }
    }

    /** 安装/更新一个插件（容器内 npm 安装到 DSH_HOME/plugins）。 */
    suspend fun install(pkg: String, version: String = ""): String = withContext(Dispatchers.IO) {
        val spec = if (version.isBlank()) pkg else "$pkg@$version"
        DshRuntime.execRootfsForOutput(
            "export DSH_HOME=/root/.dsh; mkdir -p /root/.dsh/plugins && cd /root/.dsh && " +
                "npm install --prefix /root/.dsh/plugins-store --no-audit --no-fund '$spec' 2>&1 | tail -25",
            600_000,
        )
    }

    /** 卸载一个插件。 */
    suspend fun uninstall(pkg: String): String = withContext(Dispatchers.IO) {
        DshRuntime.execRootfsForOutput(
            "export DSH_HOME=/root/.dsh; " +
                "npm uninstall --prefix /root/.dsh/plugins-store '$pkg' 2>&1 | tail -15",
            300_000,
        )
    }

    /** 本地安装：宿主 tgz 路径已由调用方复制进容器可见位置。 */
    suspend fun installLocal(containerPath: String): String = withContext(Dispatchers.IO) {
        DshRuntime.execRootfsForOutput(
            "export DSH_HOME=/root/.dsh; mkdir -p /root/.dsh/plugins && " +
                "npm install --prefix /root/.dsh/plugins-store --no-audit --no-fund '$containerPath' 2>&1 | tail -25",
            600_000,
        )
    }

    private fun <K> cached(map: ConcurrentHashMap<K, Pair<Long, Long>>, key: K): Long? {
        val (v, at) = map[key] ?: return null
        return if (System.currentTimeMillis() - at < CACHE_TTL_MS) v else null
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

/** 语义化版本比较（缺位按 0；非数字段落退化为字典序）。 */
internal fun compareVersions(a: String, b: String): Int {
    fun norm(v: String) = v.trim().removePrefix("v").substringBefore('+').split('-')[0]
    val pa = norm(a).split('.')
    val pb = norm(b).split('.')
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val x = pa.getOrNull(i)?.toIntOrNull()
        val y = pb.getOrNull(i)?.toIntOrNull()
        if (x == null || y == null) {
            val c = (pa.getOrNull(i) ?: "").compareTo(pb.getOrNull(i) ?: "")
            if (c != 0) return c
        } else if (x != y) {
            return x.compareTo(y)
        }
    }
    return 0
}
