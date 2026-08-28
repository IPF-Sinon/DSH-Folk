package me.bmax.apatch.util

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import me.bmax.apatch.dsh.DshPluginRepo
import me.bmax.apatch.dsh.DshRuntime

/**
 * AppData —— DSH-Folk 的角标数据中心。
 *
 * 原 APatch 版本统计的是「超级用户 / APM 模块 / 内核模块」三类内核补丁数据；
 * DSH-Folk 没有内核补丁栈，这里改为统计容器内 DSH 插件：
 *  - [DataRefreshManager.pluginCount]     已安装插件数（插件页角标）
 *  - [DataRefreshManager.updatableCount]  可更新插件数
 *
 * 运行时未安装/未启动时全部为 0，且不会去 exec 容器命令。
 */
object AppData {
    private const val TAG = "AppData"

    object DataRefreshManager {
        private val _pluginCount = MutableStateFlow(0)
        private val _updatableCount = MutableStateFlow(0)

        private var lastRefreshAt = 0L

        val pluginCount: StateFlow<Int> = _pluginCount.asStateFlow()
        val updatableCount: StateFlow<Int> = _updatableCount.asStateFlow()

        /**
         * 刷新插件角标计数。
         *
         * @param enablePluginBadge 角标总开关关闭时直接跳过，避免无谓的容器 exec。
         * @param minIntervalMs 最小刷新间隔，避免 3s 轮询把容器打满。
         */
        suspend fun refreshData(
            enablePluginBadge: Boolean,
            minIntervalMs: Long = 30_000L,
            force: Boolean = false,
        ) = withContext(Dispatchers.IO) {
            if (!enablePluginBadge) return@withContext

            val now = SystemClock.elapsedRealtime()
            if (!force && now - lastRefreshAt < minIntervalMs) return@withContext
            lastRefreshAt = now

            // 容器没跑起来时不做任何 exec：execRootfsForOutput 会阻塞等超时。
            if (!DshRuntime.state.value.installed) {
                _pluginCount.value = 0
                _updatableCount.value = 0
                return@withContext
            }

            try {
                val installed = DshPluginRepo.listInstalled()
                _pluginCount.value = installed.size

                // 可更新数直接对「已安装列表」补齐远端版本，而不是数商店目录里可更新的条目：
                // dsh-market 只收录了一部分插件（dsh-config-manager 就不在目录里），
                // 数目录会漏掉这些本地插件的更新。补齐失败时保持上一次的值而不是清零。
                val enriched = runCatching { DshPluginRepo.enrich(installed) }.getOrDefault(emptyList())
                if (enriched.isNotEmpty()) {
                    _updatableCount.value = enriched.count { it.updatable }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh DSH plugin counts", e)
            }
        }

        /** 首次进入界面时加载一次（同样受运行时状态保护）。 */
        suspend fun ensureCountsLoaded(force: Boolean = false) =
            refreshData(enablePluginBadge = true, force = force)
    }
}
