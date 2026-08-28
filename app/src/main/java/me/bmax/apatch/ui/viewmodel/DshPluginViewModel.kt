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

    /** 最近一次操作输出（安装/卸载日志尾巴），供 snackbar / 对话框展示。 */
    var lastOutput by mutableStateOf("")
        private set

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

    fun install(pkg: String, version: String = "", onDone: (String) -> Unit = {}) {
        viewModelScope.launch {
            isRefreshing = true
            val out = DshPluginRepo.install(pkg, version)
            lastOutput = out
            isRefreshing = false
            onDone(out)
            refresh()
        }
    }

    fun uninstall(pkg: String, onDone: (String) -> Unit = {}) {
        viewModelScope.launch {
            isRefreshing = true
            val out = DshPluginRepo.uninstall(pkg)
            lastOutput = out
            isRefreshing = false
            onDone(out)
            refresh()
        }
    }

    fun installLocal(containerPath: String, onDone: (String) -> Unit = {}) {
        viewModelScope.launch {
            isRefreshing = true
            val out = DshPluginRepo.installLocal(containerPath)
            lastOutput = out
            isRefreshing = false
            onDone(out)
            refresh()
        }
    }
}
