package me.bmax.apatch.ui.screen.settings

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshSource
import me.bmax.apatch.dsh.PermissionManager
import me.bmax.apatch.ui.component.ExpressiveCard
import me.bmax.apatch.ui.component.SplicedColumnGroup
import me.bmax.apatch.ui.component.ToggleSettingCard

/**
 * 功能设置内容：**运行方式** 与 **权限通道**（含无线 ADB 配对）。
 *
 * 这一页在 FolkPatch 里是内核补丁相关的开关（越狱模式、隐藏服务、umount、UTS 伪装、
 * 路径隐藏、网络隔离、内置 Shizuku Server）。DSH-Folk 不打内核补丁，那些开关全部没有
 * 对应实现，所以整页换成 DSH 自己需要配置的两件事 —— 首页那两张小卡就指到这里。
 */
@Composable
fun FunctionSettingsContent(
    /** 当前容器运行时 id：proot / proroot。 */
    runtimeId: String,
    onRuntimeIdChange: (String) -> Unit,
    /** proroot 是否在本机可用（不可用时禁选并说明原因）。 */
    prorootAvailable: Boolean,
    prorootUnavailableReason: String,
    /** 开机自启（BootCompletedReceiver 会读同一个 pref）。 */
    autostart: Boolean,
    onAutostartChange: (Boolean) -> Unit,
    /** 运行时下载源：DshSource.SOURCE_* 之一。 */
    downloadSource: String,
    onDownloadSourceChange: (String) -> Unit,
    customMetaUrl: String,
    onCustomMetaUrlChange: (String) -> Unit,
    /** 已解析的生效源（auto 时是测速结果）。 */
    effectiveSource: String,
    speedTesting: Boolean,
    /** 测速结果行，已格式化好。 */
    speedResults: List<String>,
    onSpeedTest: () -> Unit,
    perm: PermissionManager.Status,
    onRefreshPerm: () -> Unit,
    onRequestShizuku: () -> Unit,
    /** 运行时是否已安装（无线 ADB 需要容器内的 python）。 */
    runtimeInstalled: Boolean,
    adbPairCode: String,
    onAdbPairCodeChange: (String) -> Unit,
    adbPairPort: String,
    onAdbPairPortChange: (String) -> Unit,
    adbConnectPort: String,
    onAdbConnectPortChange: (String) -> Unit,
    adbHost: String,
    onAdbHostChange: (String) -> Unit,
    adbBusy: Boolean,
    adbOutput: String,
    onInstallAdbDeps: () -> Unit,
    onPair: () -> Unit,
    onOpenDevSettings: () -> Unit,
    flat: Boolean = false,
    highlightKey: String? = null,
) {
    SplicedColumnGroup(flat = flat, highlightKey = highlightKey) {
        // ───────── 运行方式 ─────────
        item(key = "function_run_mode") {
            ExpressiveCard(flat = flat) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    SectionHeader(
                        icon = { Icon(Icons.Filled.Layers, null, Modifier.size(20.dp)) },
                        title = stringResource(R.string.dsh_run_mode),
                        summary = stringResource(R.string.dsh_run_mode_summary),
                    )
                    Spacer(Modifier.height(12.dp))

                    RuntimeOption(
                        selected = runtimeId != "proroot",
                        enabled = true,
                        title = stringResource(R.string.dsh_mode_proot),
                        summary = stringResource(R.string.dsh_mode_proot_desc),
                        onSelect = { onRuntimeIdChange("proot") },
                    )
                    RuntimeOption(
                        selected = runtimeId == "proroot",
                        enabled = prorootAvailable,
                        title = stringResource(R.string.dsh_mode_proroot),
                        summary = if (prorootAvailable) stringResource(R.string.dsh_mode_proroot_desc)
                        else prorootUnavailableReason,
                        onSelect = { onRuntimeIdChange("proroot") },
                    )

                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.dsh_run_mode_restart_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ───────── 开机自启 ─────────
        item(key = "function_autostart") {
            ToggleSettingCard(
                flat = flat,
                icon = Icons.Filled.PowerSettingsNew,
                title = stringResource(R.string.dsh_autostart),
                description = stringResource(R.string.dsh_autostart_summary),
                checked = autostart,
                onCheckedChange = onAutostartChange,
            )
        }

        // ───────── 运行时下载源 ─────────
        item(key = "function_download_source") {
            ExpressiveCard(flat = flat) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    SectionHeader(
                        icon = { Icon(Icons.Filled.CloudDownload, null, Modifier.size(20.dp)) },
                        title = stringResource(R.string.dsh_source_section),
                        summary = stringResource(R.string.dsh_source_summary),
                    )
                    Spacer(Modifier.height(12.dp))

                    RuntimeOption(
                        selected = downloadSource == DshSource.SOURCE_AUTO,
                        enabled = true,
                        title = stringResource(R.string.dsh_source_auto),
                        summary = stringResource(R.string.dsh_source_auto_desc),
                        onSelect = { onDownloadSourceChange(DshSource.SOURCE_AUTO) },
                    )
                    for (src in listOf(
                        DshSource.SOURCE_GHPROXY_AXISNOW,
                        DshSource.SOURCE_GHPROXY_CF,
                        DshSource.SOURCE_GITHUB,
                        DshSource.SOURCE_CUSTOM,
                    )) {
                        RuntimeOption(
                            selected = downloadSource == src,
                            enabled = true,
                            title = stringResource(sourceLabelRes(src)),
                            summary = "",
                            onSelect = { onDownloadSourceChange(src) },
                        )
                    }

                    AnimatedVisibility(visible = downloadSource == DshSource.SOURCE_CUSTOM) {
                        OutlinedTextField(
                            value = customMetaUrl,
                            onValueChange = onCustomMetaUrlChange,
                            label = { Text(stringResource(R.string.dsh_source_custom_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.dsh_source_effective,
                            stringResource(sourceLabelRes(effectiveSource)),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = onSpeedTest, enabled = !speedTesting) {
                            Text(
                                stringResource(
                                    if (speedTesting) R.string.dsh_source_testing
                                    else R.string.dsh_source_speedtest
                                )
                            )
                        }
                        if (speedTesting) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }

                    if (speedResults.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        for (line in speedResults) {
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // ───────── 权限通道 ─────────
        item(key = "function_permission") {
            ExpressiveCard(flat = flat) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    SectionHeader(
                        icon = { Icon(Icons.Filled.Security, null, Modifier.size(20.dp)) },
                        title = stringResource(R.string.dsh_perm_section),
                        summary = stringResource(R.string.dsh_perm_summary),
                    )
                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = stringResource(
                            R.string.dsh_perm_current,
                            perm.label(LocalContext.current),
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(
                            R.string.dsh_perm_root_detail,
                            yesNo(perm.suPresent),
                            perm.rootProvider.ifEmpty { "-" },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(
                            R.string.dsh_perm_shizuku_detail,
                            yesNo(perm.shizukuRunning),
                            yesNo(perm.shizukuGranted),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (perm.channel == PermissionManager.Channel.NONE) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.dsh_perm_none_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRefreshPerm) {
                            Text(stringResource(R.string.dsh_perm_refresh))
                        }
                        AnimatedVisibility(visible = perm.shizukuRunning && !perm.shizukuGranted) {
                            Button(onClick = onRequestShizuku) {
                                Text(stringResource(R.string.dsh_perm_request_shizuku))
                            }
                        }
                    }
                }
            }
        }

        // ───────── 无线 ADB 配对 ─────────
        item(key = "function_wireless_adb") {
            ExpressiveCard(flat = flat) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    SectionHeader(
                        icon = { Icon(Icons.Filled.Wifi, null, Modifier.size(20.dp)) },
                        title = stringResource(R.string.dsh_adb_section),
                        summary = stringResource(R.string.dsh_adb_summary),
                    )
                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = stringResource(
                            if (perm.adbPaired) R.string.dsh_adb_paired
                            else R.string.dsh_adb_not_paired
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )

                    if (!runtimeInstalled) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.dsh_adb_needs_runtime),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = adbPairCode,
                        onValueChange = onAdbPairCodeChange,
                        label = { Text(stringResource(R.string.dsh_adb_pair_code)) },
                        singleLine = true,
                        enabled = runtimeInstalled && !adbBusy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = adbPairPort,
                            onValueChange = onAdbPairPortChange,
                            label = { Text(stringResource(R.string.dsh_adb_pair_port)) },
                            singleLine = true,
                            enabled = runtimeInstalled && !adbBusy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = adbConnectPort,
                            onValueChange = onAdbConnectPortChange,
                            label = { Text(stringResource(R.string.dsh_adb_connect_port)) },
                            singleLine = true,
                            enabled = runtimeInstalled && !adbBusy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = adbHost,
                        onValueChange = onAdbHostChange,
                        label = { Text(stringResource(R.string.dsh_adb_host)) },
                        singleLine = true,
                        enabled = runtimeInstalled && !adbBusy,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = onPair,
                            enabled = runtimeInstalled && !adbBusy && adbPairCode.isNotBlank(),
                        ) {
                            Text(stringResource(R.string.dsh_adb_pair))
                        }
                        OutlinedButton(onClick = onInstallAdbDeps, enabled = runtimeInstalled && !adbBusy) {
                            Text(stringResource(R.string.dsh_adb_install_deps))
                        }
                        TextButton(onClick = onOpenDevSettings) {
                            Text(stringResource(R.string.dsh_adb_open_devsettings))
                        }
                        if (adbBusy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }

                    if (adbOutput.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.dsh_output),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = adbOutput,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    icon: @Composable () -> Unit,
    title: String,
    summary: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RuntimeOption(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    summary: String,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 下载源 id → 可本地化标签；DshSource.displayName 只用于日志。 */
internal fun sourceLabelRes(source: String): Int = when (source) {
    DshSource.SOURCE_AUTO -> R.string.dsh_source_auto
    DshSource.SOURCE_GITHUB -> R.string.dsh_source_github
    DshSource.SOURCE_GHPROXY_CF -> R.string.dsh_source_ghproxy_cf
    DshSource.SOURCE_GHPROXY_AXISNOW -> R.string.dsh_source_ghproxy_axisnow
    DshSource.SOURCE_CUSTOM -> R.string.dsh_source_custom
    else -> R.string.dsh_source_auto
}

private fun yesNo(b: Boolean): String = if (b) "✓" else "✗"
