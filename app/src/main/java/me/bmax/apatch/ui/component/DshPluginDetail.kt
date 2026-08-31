package me.bmax.apatch.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshPlugin
import me.bmax.apatch.ui.screen.formatCount

/**
 * 插件详情底部弹层。
 *
 * 商店页与已安装页共用一份：两边卡片上的信息都被压缩过（描述截 3 行、包名要开
 * 「显示详细信息」才有），而卡片点下去原本没有任何反应 —— 这个弹层就是那个「点开」。
 *
 * 操作按钮按状态给：可更新→更新，已安装→卸载，未安装且登记了 npm 包名→安装，
 * 没登记 npm 包名（目录里 13/41 条如此）→只剩打开仓库。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DshPluginDetailSheet(
    plugin: DshPlugin,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onUninstall: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onOpenRepo: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp)
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = plugin.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            if (plugin.author.isNotEmpty()) {
                Text(
                    text = plugin.author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModuleLabel(
                    text = "↓ " + formatCount(plugin.downloads),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                ModuleLabel(
                    text = "★ " + formatCount(plugin.stars),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (plugin.likes >= 0) {
                    ModuleLabel(
                        text = stringResource(R.string.dsh_plugin_likes, formatCount(plugin.likes)),
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }

            if (plugin.installed) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (plugin.disabled) {
                        ModuleLabel(
                            text = stringResource(R.string.dsh_plugin_disabled_label),
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = stringResource(
                            if (plugin.disabled) R.string.dsh_plugin_toggle_on
                            else R.string.dsh_plugin_toggle_off
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = !plugin.disabled,
                        onCheckedChange = onToggle,
                        enabled = plugin.entryIds.isNotEmpty(),
                    )
                }
                if (plugin.entryIds.isEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.dsh_plugin_toggle_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (plugin.description.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(text = plugin.description, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(2.dp))
            DetailRow(stringResource(R.string.dsh_plugin_field_pkg), plugin.pkg.ifEmpty { plugin.id })
            if (plugin.version.isNotEmpty()) {
                DetailRow(stringResource(R.string.dsh_plugin_field_latest), plugin.version)
            }
            if (plugin.installedVersion.isNotEmpty()) {
                DetailRow(stringResource(R.string.dsh_plugin_field_installed), plugin.installedVersion)
            }
            if (plugin.category.isNotEmpty()) {
                DetailRow(stringResource(R.string.dsh_plugin_field_category), plugin.category)
            }
            val repo = plugin.homepage.ifEmpty { plugin.repo }
            if (repo.isNotEmpty()) {
                DetailRow(stringResource(R.string.dsh_plugin_field_repo), repo)
            }
            if (plugin.installed && !plugin.enabled) {
                Text(
                    text = stringResource(R.string.dsh_plugin_inactive_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!plugin.installable) {
                Text(
                    text = stringResource(R.string.dsh_plugin_no_npm),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    plugin.updatable -> Button(onClick = { onDismiss(); onUpdate() }) {
                        Icon(Icons.Outlined.SystemUpdate, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.apm_update))
                    }
                    plugin.installed -> OutlinedButton(onClick = { onDismiss(); onUninstall() }) {
                        Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.apm_remove))
                    }
                    plugin.installable -> Button(onClick = { onDismiss(); onInstall() }) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.dsh_plugin_install))
                    }
                }
                if (repo.isNotEmpty()) {
                    OutlinedButton(onClick = onOpenRepo) {
                        Icon(Icons.Outlined.OpenInNew, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.dsh_plugin_open_repo))
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 安装 / 卸载进度对话框。
 *
 * pnpm 装包动辄几分钟，而原来只在结束后弹一条 snackbar —— 中途界面毫无反馈，
 * 用户无法判断是在装还是已经卡死。这里把容器输出逐行显示出来。
 *
 * 用不确定进度条而不是百分比：pnpm 不给可靠的总进度，画一个假百分比比没有更糟。
 */
@Composable
fun DshPluginProgressDialog(
    target: String,
    lines: List<String>,
    running: Boolean,
    failed: Boolean,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    onRestart: () -> Unit,
    /**
     * 这次操作是否需要重启 DSH 才生效。
     *
     * 装插件要（dsh 只在启动时组合 profile 的 patch 层）；而装全局 CLI 之类的操作
     * 与插件树无关，提示「重启后生效」是错的。
     */
    needsRestart: Boolean = true,
) {
    val scroll = rememberScrollState()
    // 新日志到达就滚到底，否则用户得一直手动追着滚
    LaunchedEffect(lines.size) { scroll.animateScrollTo(scroll.maxValue) }

    AlertDialog(
        // 运行中不允许点外面关掉：关了就再也看不到这次的日志了
        onDismissRequest = { if (!running) onDismiss() },
        title = {
            Text(
                text = when {
                    running -> stringResource(R.string.dsh_plugin_working_on, target)
                    failed -> stringResource(R.string.dsh_plugin_failed_on, target)
                    else -> stringResource(R.string.dsh_plugin_done_on, target)
                },
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (running) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                } else if (!failed && needsRestart) {
                    // dsh 只在启动时组合 profile 的 patch 层，不重启新插件不会加载
                    Text(
                        text = stringResource(R.string.dsh_plugin_needs_restart),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp),
                ) {
                    Text(
                        text = lines.joinToString("\n").ifEmpty {
                            stringResource(R.string.dsh_plugin_waiting_output)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(12.dp).verticalScroll(scroll),
                    )
                }
            }
        },
        confirmButton = {
            // 装成功后主操作是重启 DSH：不重启新插件不会被加载
            if (!running && !failed && needsRestart) {
                TextButton(onClick = { onDismiss(); onRestart() }) {
                    Text(stringResource(R.string.dsh_plugin_restart_now))
                }
            } else {
                TextButton(onClick = onDismiss, enabled = !running) {
                    Text(stringResource(R.string.close))
                }
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { onCopy(lines.joinToString("\n")) },
                    enabled = lines.isNotEmpty(),
                ) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.dsh_copy_log),
                        modifier = Modifier.size(18.dp),
                    )
                }
                if (!running && !failed && needsRestart) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                }
            }
        },
    )
}
