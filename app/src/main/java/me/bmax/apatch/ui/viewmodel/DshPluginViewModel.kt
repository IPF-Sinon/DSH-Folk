package me.bmax.apatch.ui.viewmodel

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.APApplication
import me.bmax.apatch.R
import me.bmax.apatch.apApp
import me.bmax.apatch.util.appString
import me.bmax.apatch.dsh.DshEnv
import me.bmax.apatch.dsh.DshPlugin
import me.bmax.apatch.dsh.DshPluginRepo
import me.bmax.apatch.dsh.DshRuntime

/**
 * DSH 插件页状态。
 *
 * 列表 = 容器内已安装插件（权威），线上目录只用来补齐下载量 / star / 可更新判断。
 * 这样离线也能看到已装插件，只是标签显示为未知。
 */
class DshPluginViewModel : ViewModel() {

    var isRefreshing by mutableStateOf(false)
        private set

    /**
     * 上一次刷新失败的原因（成功时为空）。
     *
     * 为什么要有：容器读不出插件树时旧代码把 [isRefreshing] 永久留在 true，之后用户点刷新、
     * 点切换全都静默无反应 —— 界面上看起来就是「刷新不管用、开关不动」。失败必须说出来。
     */
    var refreshError by mutableStateOf("")
        private set

    /** 刷新请求被合并的次数（见 [refresh]）：有请求在跑时置位，跑完补跑一次。 */
    private var refreshQueued = false

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

    /**
     * 按分类 + 搜索词过滤后的可见条目，**并补齐已安装状态**。
     *
     * 为什么要在这里补：商店的 DshPlugin 来自目录，`installedVersion` 永远是空，
     * 于是 `installed` / `updatable` 恒为 false —— 详情页因此对已装插件也显示
     * 「安装」按钮，且永远不显示「更新」。网格瓦片之前是另算了一个 installedPkgs
     * 集合绕过去的，那让判据分了两处、只修好了一半。在这里补齐后两处同时正确。
     *
     * 只能按 npm 包名匹配：目录没登记 npm 的条目（pkg 为空）匹配不上已装状态，
     * 这是目录数据的直接后果，不为此再引入按仓库地址反查的猜测逻辑。
     */
    val storeItems: List<DshPlugin>
        get() {
            val cat = storeCategory
            val q = search.trim()
            val installedByPkg = plugins.filter { it.pkg.isNotEmpty() }.associateBy { it.pkg }
            return storeAll.asSequence().filter { p ->
                (cat.isBlank() || p.category == cat) &&
                    (q.isEmpty() ||
                        p.name.contains(q, true) ||
                        p.pkg.contains(q, true) ||
                        p.author.contains(q, true) ||
                        p.description.contains(q, true))
            }.map { p ->
                val local = installedByPkg[p.pkg]
                val seeded = DshRuntime.isSeedPlugin(p.pkg)
                if (local == null) return@map p.copy(seeded = seeded)
                p.copy(
                    installedVersion = local.installedVersion,
                    enabled = local.enabled,
                    entryIds = local.entryIds,
                    disabled = local.disabled,
                    seeded = seeded,
                )
            }.toList()
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

    /**
     * pnpm 拦下了构建脚本，等用户决定是否放行。
     *
     * 为什么要问：执行依赖自带的 install/postinstall/prepare 脚本，等于在容器里跑
     * 这个包作者的任意代码。pnpm 11 默认拦住并**让安装失败**，官方出路是交互式
     * `pnpm approve-builds`（容器里没有 TTY，跑不了）。所以由 App 代问一次，
     * 用户点头才写 allowBuilds 并重试。绝不静默放行。
     */
    data class BuildApproval(
        /** 待放行的包名（不含版本）。 */
        val packages: List<String>,
        /** 触发它的安装目标，显示用。 */
        val target: String,
    )

    var buildApproval by mutableStateOf<BuildApproval?>(null)
        private set

    /** 用户拒绝：什么都不写，保留失败日志。 */
    fun dismissBuildApproval() { buildApproval = null }

    /** 用户同意：写 allowBuilds 后重跑同一次安装。 */
    fun approveBuilds() {
        val pending = buildApproval ?: return
        buildApproval = null
        val retry = pendingRetry ?: return
        pendingRetry = null
        retry(pending.packages)
    }

    /** [approveBuilds] 要重放的动作，由 [run] 在识别出拦截时登记。 */
    private var pendingRetry: ((List<String>) -> Unit)? = null

    /**
     * 拉已安装列表（+ 更新检查目录）。
     *
     * **并发请求合并而不是丢弃**：旧实现在 [isRefreshing] 为真时直接 return，于是「切换插件之后
     * 那次刷新」只要和页面首次刷新/上一次刷新重叠就被静默丢掉，界面停在写入前的快照上 ——
     * 真机表现就是「停用/启用后开关很久不变、过一会手动刷新又好了、被别的操作一碰又对上了」。
     * 现在改成：有请求在跑就记一个待跑标志，跑完立刻补跑一次，保证「写入之后」一定有一次真读盘。
     */
    fun refresh() {
        if (isRefreshing) {
            refreshQueued = true
            return
        }
        isRefreshing = true
        refreshError = ""
        viewModelScope.launch {
            try {
                val installed = withContext(Dispatchers.IO) { DshPluginRepo.listInstalled() }
                // 先把已安装列表放出来，网络慢时页面不空白
                plugins = installed
                if (disableUpdateCheck) {
                    // 关掉更新检查就不再拉线上目录：没有远端版本号，updatable 恒为 false
                    catalog = emptyList()
                    return@launch
                }
                val ctx = apApp
                // 1) 先用上次的缓存渲染一遍：「有没有新版本可更新」立刻可见，不必干等一整轮网络
                val cached = withContext(Dispatchers.IO) { DshPluginRepo.cachedCatalogRows(ctx) }
                if (cached.isNotEmpty()) {
                    catalog = cached
                    plugins = mergeCatalog(installed, cached)
                }
                // 2) 再拉真目录并覆盖（同时把这份写回缓存，供下次进入页面用）
                val online = withContext(Dispatchers.IO) { DshPluginRepo.fetchCatalogAndCache(ctx) }
                if (online.isNotEmpty()) {
                    catalog = online
                    plugins = mergeCatalog(installed, online)
                }
            } catch (e: Exception) {
                // 保留上一次的列表（比清空成「尚未安装任何插件」诚实），把原因交给界面显示
                refreshError = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "refresh failed", e)
            } finally {
                isRefreshing = false
                if (refreshQueued) {
                    refreshQueued = false
                    refresh()
                }
            }
        }
    }

    /**
     * 把目录行的展示字段与「远端最新版」并到已安装列表上。
     *
     * 目录按 npm 包名对齐已安装列表：dsh-market 的 id 与包名不一定相同。
     */
    private suspend fun mergeCatalog(
        installed: List<DshPlugin>,
        online: List<DshPlugin>,
    ): List<DshPlugin> {
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
        return withContext(Dispatchers.IO) {
            DshPluginRepo.enrich(merged.map { if (byPkg.containsKey(it.pkg)) it else it.copy(version = "") })
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
        run(
            pkg,
            { DshPluginRepo.install(pkg, version, it) },
            onDone,
            verify = true,
            // 被 pnpm 拦下构建脚本时，把「放行后重试」记成待确认动作
            retryWithBuilds = { allow, cb ->
                run(
                    pkg,
                    { DshPluginRepo.install(pkg, version, it, allowBuilds = allow) },
                    cb,
                    verify = true,
                )
            },
        )
    }

    /**
     * 插件页「更新」：显式取 npm 最新版（[DshPluginRepo.VERSION_LATEST]）。
     *
     * 不能走 [install] 的裸包名：pnpm 对**已声明过**的 registry 依赖，`add <裸包名>` 是空操作
     * （实测打印 "Already up to date"，版本与声明范围都不动）——真机「点更新→日志正常→版本不变」
     * 就是这个原因；显式 `@latest` 才会重新解析并改写范围（顺带越过 `^2.4.1` 这类 caret 天花板，
     * 3.0.0 这种 major 升级才进得来）。git 规格的裸规格本来就会重新解析到最新提交，[install] 会
     * 原样保留。
     */
    fun update(pkg: String, onDone: (String) -> Unit = {}) =
        install(pkg, DshPluginRepo.VERSION_LATEST, onDone)

    fun uninstall(pkg: String, onDone: (String) -> Unit = {}) {
        run(pkg, { DshPluginRepo.uninstall(pkg, it) }, onDone)
    }

    /**
     * 停用/启用一个插件（不卸载）。
     *
     * 复用 [run] 的进度壳（有日志留存与「重启 DSH」提示），但不跑安装后验证：这只是
     * 改一行 profile 的 cordis.patch.yml，不需要为它起一次服务去验插件树。
     */
    fun setDisabled(pkg: String, disabled: Boolean, onDone: (String) -> Unit = {}) {
        run(
            pkg,
            { onLine ->
                val ok = DshPluginRepo.setPluginDisabled(pkg, disabled, onLine)
                if (ok) {
                    // 乐观更新：写盘已经成功，开关立刻跟着动，不等那一轮读盘回来。
                    // 随后的合并刷新会以文件为准再校正一次（写盘与读盘之间用户看到的一直是旧状态，
                    // 真机反馈就是「开关很大概率不变、实际状态其实变了」）。
                    plugins = plugins.map { if (it.pkg == pkg) it.copy(disabled = disabled) else it }
                    apApp.appString(R.string.dsh_plugin_toggle_restart_hint) +
                        "\n" + DshPluginRepo.EXIT_MARKER + " 0"
                } else {
                    DshPluginRepo.EXIT_MARKER + " 1"
                }
            },
            onDone,
            verify = false,
        )
    }

    fun installLocal(containerPath: String, onDone: (String) -> Unit = {}) {
        run(
            containerPath.substringAfterLast('/'),
            { DshPluginRepo.installLocal(containerPath, it) },
            onDone,
            verify = true,
        )
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
     * 在容器内安装 dsh-config-manager 的独立 CLI（备份页的救急入口）。
     *
     * 同样复用 [run]：它是一次分钟级的 npm 操作，需要实时日志和失败留存。
     * 不置 needsRestart —— 这是个全局命令，与 profile 的插件树无关。
     */
    fun installRescueCli(onDone: (String) -> Unit = {}) {
        run(
            "安装救急 CLI",
            { DshPluginRepo.installRescueCli(it) },
            onDone,
            affectsPluginTree = false,
        )
    }

    /**
     * 装 / 卸 / 本地装共用的执行壳。
     *
     * 成功后置 [needsRestart]：dsh 在启动时组合 profile 的 patch 层，
     * 装完不重启进程新插件不会加载 —— 这与 dsh plugin 自己的 needsRestart 语义一致。
     *
     * @param verify 安装成功后跑一次启动验证；失败则自动回滚（仅安装路径需要）。
     * @param affectsPluginTree 这次操作会不会改变 profile 的插件树。装全局 CLI 不会 ——
     *        对它提示「重启 DSH 后生效」是错的。
     * @param retryWithBuilds 非空时，若失败原因是 pnpm 拦下构建脚本，就登记一次
     *        「放行后重试」待用户确认，而不是直接把失败甩给用户。
     */
    private fun run(
        target: String,
        action: suspend ((String) -> Unit) -> String,
        onDone: (String) -> Unit,
        verify: Boolean = false,
        affectsPluginTree: Boolean = true,
        retryWithBuilds: ((List<String>, (String) -> Unit) -> Unit)? = null,
    ) {
        if (installing) return
        viewModelScope.launch {
            try {
                installing = true
                installFailed = false
                installTarget = target
                installLog = emptyList()
                // onLine 在容器输出的读线程上被调，不能直接写 Compose 状态，
                // 所以绕回 viewModelScope（主调度器）再追加。
                val append: (String) -> Unit = { line ->
                    viewModelScope.launch {
                        if (line.startsWith(DshPluginRepo.EXIT_MARKER)) return@launch
                        val next = installLog + line
                        installLog = if (next.size > MAX_LOG_LINES) next.takeLast(MAX_LOG_LINES) else next
                    }
                }
                // 装之前先记下 bundles，装完的差集就是这次真正生效的新插件 ——
                // 回滚必须用这个包名，不能用安装规格（github:owner/name 装出来的
                // 包名跟规格根本不是一回事，拿规格 remove 会失败）
                val before = if (verify) withContext(Dispatchers.IO) { DshPluginRepo.bundles() } else emptyList()
                val raw = action(append)
                var failed = looksFailed(raw)
                var extra = ""

                if (verify && !failed && verifyAfterInstall()) {
                    val reason = DshPluginRepo.verifyBoot(append)
                    if (reason != null) {
                        failed = true
                        val added = withContext(Dispatchers.IO) { DshPluginRepo.bundles() } - before.toSet()
                        val victim = added.firstOrNull().orEmpty()
                        val rolled = if (victim.isEmpty()) false else DshPluginRepo.rollback(victim, append)
                        extra = if (rolled) {
                            apApp.appString(R.string.dsh_plugin_rolled_back, victim, reason)
                        } else {
                            apApp.appString(R.string.dsh_plugin_verify_failed, reason)
                        }
                        append("[DSH-Folk] $extra")
                    }
                }

                // 退出码标记是给程序看的，别显示给用户
                val out = raw.lineSequence()
                    .filterNot { it.startsWith(DshPluginRepo.EXIT_MARKER) }
                    .joinToString("\n")
                    .trim()
                    .ifEmpty { raw }
                lastOutput = if (extra.isEmpty()) out else "$extra\n$out"
                installFailed = failed
                installing = false
                // 回滚过就等于什么都没装，不该提示重启；不动插件树的操作同样不提示
                if (!failed && affectsPluginTree) needsRestart = true

                // 失败原因是 pnpm 拦下构建脚本时，不把「失败」当终局：拿出包名问用户
                if (failed && retryWithBuilds != null) {
                    val pending = DshPluginRepo.pendingBuildApproval(raw)
                    if (pending.isNotEmpty()) {
                        pendingRetry = { allow -> retryWithBuilds(allow, onDone) }
                        buildApproval = BuildApproval(packages = pending, target = target)
                    }
                }

                onDone(lastOutput)
                refresh()
            } catch (e: Exception) {
                // 任何一步抛异常都不能把 installing 留在 true —— 那会让之后所有安装/切换静默失效
                installFailed = true
                lastOutput = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "plugin action failed", e)
            } finally {
                installing = false
            }
        }
    }

    /** 安装后验证开关（默认开）。 */
    private fun verifyAfterInstall(): Boolean =
        apApp.getSharedPreferences(DshEnv.PREF, android.content.Context.MODE_PRIVATE)
            .getBoolean(DshEnv.KEY_VERIFY_AFTER_INSTALL, true)

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
        return out.contains("pnpm failed") || out.contains(DshPluginRepo.NO_PNPM) ||
            out.contains("[DSH-Folk]") || out.contains("ERR_PNPM")
    }

    private companion object {
        const val TAG = "DshPluginVM"

        /** 安装日志保留行数上限：pnpm 能刷出上万行，全留会拖垮列表渲染。 */
        const val MAX_LOG_LINES = 400
    }
}
