package me.bmax.apatch.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.APApplication
import me.bmax.apatch.apApp
import me.bmax.apatch.dsh.DshPlugin
import me.bmax.apatch.dsh.DshPluginRepo

/**
 * DSH 插件页状态。
 *
 * 列表 = 容器内已安装插件（权威），线上目录只用来补齐下载量 / star / 可更新判断。
 * 这样离线也能看到已装插件，只是标签显示为未知。
 */
class DshPluginViewModel : ViewModel() {

    var isRefreshing by mutableStateOf(false)
        private set

    /** 已安装插件（含线上补齐信息）。 */
    var plugins by mutableStateOf<List<DshPlugin>>(emptyList())
        private set

    /** 线上目录（插件商店页复用）。 */
    var catalog by mutableStateOf<List<DshPlugin>>(emptyList())
        private set

    var search by mutableStateOf("")

    // ---- 商店：全量目录 + 本地检索 ----
    /**
     * 完整目录（约 2600 条）。搜索与分类都在这份数据上做。
     *
     * 为什么整份拉下来：npm search 的 `keywords:` 之后的自由文本只影响排序、不做
     * 过滤（实测加不加 `theme` 都返回 total=2577），所以服务端搜索给不出「某个词的
     * 全部命中」。要让用户搜到全部插件，只能先取完整目录再本地匹配。
     */
    var storeAll by mutableStateOf<List<DshPlugin>>(emptyList())
        private set

    /** 目录自报的更新日期，用于「离线快照」提示。 */
    var storeUpdated by mutableStateOf("")
        private set

    /** 目录来自过期的本地缓存（线上源都不可用）。 */
    var storeOffline by mutableStateOf(false)
        private set

    /** 目录自带的分类标题，App 未内置该 slug 时兜底。 */
    var storeCategoryTitles by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** 目录加载中。 */
    var storeRefreshing by mutableStateOf(false)
        private set

    /** 当前分类 slug（空 = 全部）。 */
    var storeCategory by mutableStateOf("")
        private set

    /** 目录总条数（当前分类下）。 */
    val storeTotal: Int
        get() = if (storeCategory.isBlank()) storeAll.size
        else storeAll.count { it.category == storeCategory }

    /** 按分类 + 搜索词过滤后的可见条目。 */
    val storeItems: List<DshPlugin>
        get() {
            val cat = storeCategory
            val q = search.trim()
            return storeAll.filter { p ->
                (cat.isBlank() || p.category == cat) &&
                    (q.isEmpty() ||
                        p.name.contains(q, true) ||
                        p.pkg.contains(q, true) ||
                        p.author.contains(q, true) ||
                        p.description.contains(q, true))
            }
        }

    /** 最近一次操作输出（安装/卸载日志尾巴），供 snackbar / 对话框展示。 */
    var lastOutput by mutableStateOf("")
        private set

    /**
     * 装/卸正在进行中。
     *
     * 不复用 [isRefreshing]：刷新列表只要一、两秒，而 pnpm 装包可能几分钟，
     * 两者对应的 UI（顶部细进度条 vs 安装日志对话框）完全不同。
     */
    var installing by mutableStateOf(false)
        private set

    /** 当前操作的对象名（包名或文件名），用作对话框标题。 */
    var installTarget by mutableStateOf("")
        private set

    /** 实时日志行。封顶防止 pnpm 刷出上万行把列表渲染拖垮。 */
    var installLog by mutableStateOf<List<String>>(emptyList())
        private set

    /** 本次操作是否失败（结束后才有意义）。 */
    var installFailed by mutableStateOf(false)
        private set

    fun dismissInstallLog() {
        if (installing) return
        installLog = emptyList()
        installTarget = ""
        installFailed = false
    }

    /** 插件设置页的开关（key 沿用 FolkPatch 模块页的名字，兼容旧配置）。 */
    private val prefs get() = APApplication.sharedPreferences

    /** 「显示插件详细信息」：卡片是否显示包名与作者。 */
    val showMoreInfo: Boolean get() = prefs.getBoolean("show_more_module_info", true)

    private val sortUpdatableFirst: Boolean get() = prefs.getBoolean("module_sort_optimization", true)

    private val disableUpdateCheck: Boolean get() = prefs.getBoolean("disable_module_update_check", false)

    val filtered: List<DshPlugin>
        get() {
            val matched = if (search.isBlank()) plugins else plugins.filter {
                it.id.contains(search, true) || it.name.contains(search, true) ||
                    it.description.contains(search, true)
            }
            return if (sortUpdatableFirst) {
                matched.sortedWith(compareByDescending<DshPlugin> { it.updatable }.thenBy { it.id })
            } else {
                matched
            }
        }

    val updatableCount: Int get() = plugins.count { it.updatable }

    /** 有装/卸成功过：需要重启 DSH 才会加载新的 profile patch 层。 */
    var needsRestart by mutableStateOf(false)
        private set

    fun clearNeedsRestart() { needsRestart = false }

    fun refresh() {
        if (isRefreshing) return
        isRefreshing = true
        viewModelScope.launch {
            val installed = withContext(Dispatchers.IO) { DshPluginRepo.listInstalled() }
            // 先把已安装列表放出来，网络慢时页面不空白
            plugins = installed
            if (disableUpdateCheck) {
                // 关掉更新检查就不再拉线上目录：没有远端版本号，updatable 恒为 false
                catalog = emptyList()
                isRefreshing = false
                return@launch
            }
            val online = withContext(Dispatchers.IO) { DshPluginRepo.fetchCatalog() }
            catalog = online
            // 目录按 npm 包名对齐已安装列表：dsh-market 的 id 与包名不一定相同
            val byPkg = online.filter { it.pkg.isNotEmpty() }.associateBy { it.pkg }
            val merged = installed.map { local ->
                val remote = byPkg[local.pkg]
                local.copy(
                    id = remote?.id ?: local.id,
                    name = remote?.name?.takeIf { it.isNotBlank() } ?: local.name,
                    description = local.description.ifBlank { remote?.description ?: "" },
                    author = local.author.ifBlank { remote?.author ?: "" },
                    repo = remote?.repo ?: "",
                    homepage = remote?.homepage ?: "",
                    version = remote?.version?.takeIf { it.isNotBlank() } ?: local.version,
                    downloads = remote?.downloads ?: -1L,
                    stars = remote?.stars ?: -1L,
                    likes = remote?.likes ?: -1L,
                )
            }
            // 目录里查不到的本地插件（自建/私有包）也要显示版本与下载量
            plugins = withContext(Dispatchers.IO) {
                DshPluginRepo.enrich(merged.map { if (byPkg.containsKey(it.pkg)) it else it.copy(version = "") })
            }
            isRefreshing = false
        }
    }

    /** 商店页补一份已安装列表，仅用于「已安装」角标，不发线上目录请求。 */
    fun loadInstalledForStore() {
        if (plugins.isNotEmpty()) return
        viewModelScope.launch {
            plugins = withContext(Dispatchers.IO) { DshPluginRepo.listInstalled() }
        }
    }

    /** 切换商店分类。目录已在本地，只是换个过滤条件，不发网络请求。 */
    fun selectStoreCategory(category: String) {
        if (storeCategory == category) return
        storeCategory = category
    }

    /**
     * 拉取完整目录。商店页唯一的网络入口。
     *
     * @param force 忽略缓存 TTL 强制重拉（下拉刷新 / 右上角刷新按钮）。
     */
    fun refreshCatalog(force: Boolean = false) {
        if (storeRefreshing) return
        storeRefreshing = true
        viewModelScope.launch {
            try {
                val ctx = apApp
                val cat = withContext(Dispatchers.IO) { DshPluginRepo.catalog(ctx, force) }
                storeAll = cat.plugins
                storeUpdated = cat.updated
                storeOffline = cat.offline
                storeCategoryTitles = cat.categoryTitles
            } finally {
                storeRefreshing = false
            }
        }
    }

    fun install(pkg: String, version: String = "", onDone: (String) -> Unit = {}) {
        run(pkg, { DshPluginRepo.install(pkg, version, it) }, onDone)
    }

    fun uninstall(pkg: String, onDone: (String) -> Unit = {}) {
        run(pkg, { DshPluginRepo.uninstall(pkg, it) }, onDone)
    }

    fun installLocal(containerPath: String, onDone: (String) -> Unit = {}) {
        run(containerPath.substringAfterLast('/'), { DshPluginRepo.installLocal(containerPath, it) }, onDone)
    }

    /**
     * 重建插件依赖。
     *
     * 与安装共用 [run] 的进度对话框：它同样是一次几分钟的 pnpm 操作，
     * 需要实时日志和失败留存，没有理由另做一套 UI。
     */
    fun repairStore(onDone: (String) -> Unit = {}) {
        run("重建插件依赖", { DshPluginRepo.repairStore(it) }, onDone)
    }

    /**
     * 装 / 卸 / 本地装共用的执行壳。
     *
     * 成功后置 [needsRestart]：dsh 在启动时组合 profile 的 patch 层，
     * 装完不重启进程新插件不会加载 —— 这与 dsh plugin 自己的 needsRestart 语义一致。
     */
    private fun run(
        target: String,
        action: suspend ((String) -> Unit) -> String,
        onDone: (String) -> Unit,
    ) {
        if (installing) return
        viewModelScope.launch {
            installing = true
            installFailed = false
            installTarget = target
            installLog = emptyList()
            // onLine 在容器输出的读线程上被调，不能直接写 Compose 状态，
            // 所以绕回 viewModelScope（主调度器）再追加。
            val raw = action { line ->
                viewModelScope.launch {
                    if (line.startsWith(DshPluginRepo.EXIT_MARKER)) return@launch
                    val next = installLog + line
                    installLog = if (next.size > MAX_LOG_LINES) next.takeLast(MAX_LOG_LINES) else next
                }
            }
            val failed = looksFailed(raw)
            // 退出码标记是给程序看的，别显示给用户
            val out = raw.lineSequence()
                .filterNot { it.startsWith(DshPluginRepo.EXIT_MARKER) }
                .joinToString("\n")
                .trim()
                .ifEmpty { raw }
            lastOutput = out
            installFailed = failed
            installing = false
            if (!failed) needsRestart = true
            onDone(out)
            refresh()
        }
    }

    /**
     * 这次 dsh plugin 是不是失败了。
     *
     * 首选 [DshPluginRepo.EXIT_MARKER] 那行里的真实退出码 —— 输出里找关键字是猜：
     * pnpm 换个措辞失败就会被当成成功，用户看到
     * 「已安装」但插件其实没进 bundles。找不到标记行时（超时、容器没起来）才退回
     * 关键字匹配。
     */
    private fun looksFailed(out: String): Boolean {
        val marker = out.lineSequence().lastOrNull { it.startsWith(DshPluginRepo.EXIT_MARKER) }
        if (marker != null) {
            val code = marker.removePrefix(DshPluginRepo.EXIT_MARKER).trim().toIntOrNull()
            if (code != null) return code != 0
        }
        return out.contains("pnpm failed") || out.contains("pnpm not found") ||
            out.contains("[DSH-Folk]") || out.contains("ERR_PNPM")
    }

    private companion object {
        /** 安装日志保留行数上限：pnpm 能刷出上万行，全留会拖垮列表渲染。 */
        const val MAX_LOG_LINES = 400
    }
}
