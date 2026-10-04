package me.bmax.apatch.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Update
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.bmax.apatch.APApplication
import me.bmax.apatch.R
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import me.bmax.apatch.ui.component.ExpressiveCard
import me.bmax.apatch.ui.component.SplicedColumnGroup
import me.bmax.apatch.ui.component.ToggleSettingCard

/**
 * 「插件」设置页。
 *
 * FolkPatch 这一页原本是 APM/KPM 模块列表的显示开关（折叠系统模块、批量安装全流程、
 * KPM 状态角标……）。DSH-Folk 的插件是容器内的 npm 包，没有系统模块也没有批量安装流程，
 * 所以只保留三条**真的接到插件页逻辑上**的开关，其余全部删掉。
 *
 * 三个 SharedPreferences key 沿用原名，这样已导入的 theme.json / 旧配置不会失效。
 */
@Composable
fun ModuleSettingsContent(
    flat: Boolean = false,
    highlightKey: String? = null,
    onOpenUserscripts: () -> Unit = {},
) {
    val prefs = APApplication.sharedPreferences

    var disableUpdateCheck by remember {
        mutableStateOf(prefs.getBoolean("disable_module_update_check", false))
    }
    var showMoreInfo by remember {
        mutableStateOf(prefs.getBoolean("show_more_module_info", true))
    }
    var sortUpdatableFirst by remember {
        mutableStateOf(prefs.getBoolean("module_sort_optimization", true))
    }

    SplicedColumnGroup(flat = flat, highlightKey = highlightKey) {
        // 用户脚本：不打包的扩展方式（与"插件带 client bundle"并列的那一档）。
        // 放在插件页，因为要回答的正是"这个应用能怎么扩展"。
        item(key = "module_userscripts") {
            ExpressiveCard(flat = flat, onClick = onOpenUserscripts) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Code, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.dsh_userscripts_entry),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(R.string.dsh_userscripts_entry_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        item(key = "module_disable_update") {
            ToggleSettingCard(
                icon = Icons.Filled.Update,
                flat = flat,
                title = stringResource(R.string.dsh_plugin_disable_update_check),
                description = stringResource(R.string.dsh_plugin_disable_update_check_summary),
                checked = disableUpdateCheck,
                onCheckedChange = {
                    disableUpdateCheck = it
                    prefs.edit().putBoolean("disable_module_update_check", it).apply()
                }
            )
        }

        item(key = "module_more_info") {
            ToggleSettingCard(
                icon = Icons.Filled.Info,
                flat = flat,
                title = stringResource(R.string.dsh_plugin_more_info),
                description = stringResource(R.string.dsh_plugin_more_info_summary),
                checked = showMoreInfo,
                onCheckedChange = {
                    showMoreInfo = it
                    prefs.edit().putBoolean("show_more_module_info", it).apply()
                }
            )
        }

        item(key = "module_sort_opt") {
            ToggleSettingCard(
                icon = Icons.Filled.Sort,
                flat = flat,
                title = stringResource(R.string.dsh_plugin_sort_updatable_first),
                description = stringResource(R.string.dsh_plugin_sort_updatable_first_summary),
                checked = sortUpdatableFirst,
                onCheckedChange = {
                    sortUpdatableFirst = it
                    prefs.edit().putBoolean("module_sort_optimization", it).apply()
                }
            )
        }
    }
}
