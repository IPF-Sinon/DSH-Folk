package me.bmax.apatch.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    val filtered: List<DshPlugin>
        get() = if (search.isBlank()) plugins else plugins.filter {
            it.id.contains(search, true) || it.name.contains(search, true) ||
                it.description.contains(search, true)
        }

    val updatableCount: Int get() = plugins.count { it.updatable }

    fun refresh() {
        if (isRefreshing) return
        isRefreshing = true
        viewModelScope.launch {
            val installed = withContext(Dispatchers.IO) { DshPluginRepo.listInstalled() }
            // 先把已安装列表放出来，网络慢时页面不空白
            plugins = installed
            val online = withContext(Dispatchers.IO) { DshPluginRepo.fetchCatalog() }
            catalog = online
            val byId = online.associateBy { it.id }
            plugins = installed.map { local ->
                val remote = byId[local.id]
                local.copy(
                    name = remote?.name?.takeIf { it.isNotBlank() } ?: local.name,
                    description = local.description.ifBlank { remote?.description ?: "" },
                    author = local.author.ifBlank { remote?.author ?: "" },
                    repo = remote?.repo ?: "",
                    homepage = remote?.homepage ?: "",
                    version = remote?.version?.takeIf { it.isNotBlank() } ?: local.version,
                    downloads = remote?.downloads ?: -1L,
                    stars = remote?.stars ?: -1L,
                )
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
