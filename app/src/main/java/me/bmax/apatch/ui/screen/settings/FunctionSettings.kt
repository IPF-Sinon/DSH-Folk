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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshSource
import me.bmax.apatch.dsh.PermissionManager
import me.bmax.apatch.ui.DshWebUi
import me.bmax.apatch.ui.component.ExpressiveCard
import me.bmax.apatch.ui.component.ExpressiveSwitch
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
    /** WebUI 打开方式：in | browser | ask。 */
    webuiMode: String,
    onWebuiModeChange: (String) -> Unit,
    /** 权限通道首选（auto | root | shizuku | adb）。 */
    permPrefName: String,
    onPermPrefChange: (String) -> Unit,
    /** 运行时是否已安装（无线 ADB 需要容器内的 python）。 */
    runtimeInstalled: Boolean,
    /** 已安装的运行时版本；未安装时为空。 */
    runtimeVersion: String,
    /** 重新下载并覆盖容器。 */
    onReinstallRuntime: () -> Unit,
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
    adbShellAllowed: Boolean,
    onAdbShellAllowedChange: (Boolean) -> Unit,
    adbRootAllowed: Boolean,
    onAdbRootAllowedChange: (Boolean) -> Unit,
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

        // ───────── Web 界面打开方式 ─────────
        item(key = "function_webui_mode") {
            ExpressiveCard(flat = flat) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    SectionHeader(
                        icon = { Icon(Icons.Filled.Layers, null, Modifier.size(20.dp)) },
                        title = stringResource(R.string.dsh_webui_mode),
                        summary = stringResource(R.string.dsh_webui_mode_summary),
                    )
                    Spacer(Modifier.height(12.dp))

                    RuntimeOption(
                        selected = webuiMode == DshWebUi.MODE_IN_APP,
                        enabled = true,
                        title = stringResource(R.string.dsh_webui_mode_in_app),
                        summary = stringResource(R.string.dsh_webui_mode_in_app_desc),
                        onSelect = { onWebuiModeChange(DshWebUi.MODE_IN_APP) },
                    )
                    RuntimeOption(
                        selected = webuiMode == DshWebUi.MODE_BROWSER,
                        enabled = true,
                        title = stringResource(R.string.dsh_webui_mode_browser),
                        summary = stringResource(R.string.dsh_webui_mode_browser_desc),
                        onSelect = { onWebuiModeChange(DshWebUi.MODE_BROWSER) },
                    )
                    RuntimeOption(
                        selected = webuiMode == DshWebUi.MODE_ASK,
                        enabled = true,
                        title = stringResource(R.string.dsh_webui_mode_ask),
                        summary = stringResource(R.string.dsh_webui_mode_ask_desc),
                        onSelect = { onWebuiModeChange(DshWebUi.MODE_ASK) },
                    )
                }
            }
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

        // ───────── 运行时重装 ─────────
        // DshRuntime.reinstallRuntime() 早就存在，但之前 UI 里没有任何入口，
        // 而好几处报错文案（缺 pnpm / 缺 dsh）都写着「请在设置中重装运行时」。
        item(key = "function_runtime") {
            ExpressiveCard(flat = flat) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    SectionHeader(
                        icon = { Icon(Icons.Filled.Refresh, null, Modifier.size(20.dp)) },
                        title = stringResource(R.string.dsh_runtime_section),
                        summary = stringResource(R.string.dsh_runtime_summary),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = if (runtimeInstalled && runtimeVersion.isNotEmpty()) {
                            stringResource(R.string.dsh_runtime_installed_version, runtimeVersion)
                        } else if (runtimeInstalled) {
                            stringResource(R.string.dsh_runtime_section)
                        } else {
                            stringResource(R.string.dsh_runtime_not_installed)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    var confirming by remember { mutableStateOf(false) }
                    OutlinedButton(
                        onClick = { confirming = true },
                        enabled = runtimeInstalled,
                    ) {
                        Text(stringResource(R.string.dsh_runtime_reinstall))
                    }
                    // 重装会连带删掉容器内的插件与 ADB 密钥，必须确认
                    if (confirming) {
                        AlertDialog(
                            onDismissRequest = { confirming = false },
                            title = { Text(stringResource(R.string.dsh_runtime_reinstall_confirm_title)) },
                            text = { Text(stringResource(R.string.dsh_runtime_reinstall_confirm_text)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    confirming = false
                                    onReinstallRuntime()
                                }) {
                                    Text(stringResource(R.string.dsh_runtime_reinstall_go))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { confirming = false }) {
                                    Text(stringResource(android.R.string.cancel))
                                }
                            },
                        )
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
                    val shizukuUidLabel = when (perm.shizukuUid) {
                        0 -> stringResource(R.string.dsh_perm_shizuku_uid_root)
                        2000 -> stringResource(R.string.dsh_perm_shizuku_uid_shell)
                        else -> "uid ${perm.shizukuUid}"
                    }
                    Text(
                        text = stringResource(
                            R.string.dsh_perm_shizuku_detail_uid,
                            yesNo(perm.shizukuRunning),
                            yesNo(perm.shizukuGranted),
                            shizukuUidLabel,
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

                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.dsh_perm_prefer),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    RuntimeOption(
                        selected = permPrefName == PermissionManager.PREF_AUTO,
                        enabled = true,
                        title = stringResource(R.string.dsh_perm_prefer_auto),
                        summary = stringResource(R.string.dsh_perm_prefer_auto_desc),
                        onSelect = { onPermPrefChange(PermissionManager.PREF_AUTO) },
                    )
                    RuntimeOption(
                        selected = permPrefName == PermissionManager.PREF_ROOT,
                        enabled = true,
                        title = stringResource(R.string.dsh_perm_root),
                        summary = stringResource(R.string.dsh_perm_hint_root),
                        onSelect = { onPermPrefChange(PermissionManager.PREF_ROOT) },
                    )
                    RuntimeOption(
                        selected = permPrefName == PermissionManager.PREF_SHIZUKU,
                        enabled = true,
                        title = stringResource(R.string.dsh_perm_shizuku),
                        summary = stringResource(R.string.dsh_perm_hint_shizuku),
                        onSelect = { onPermPrefChange(PermissionManager.PREF_SHIZUKU) },
                    )
                    RuntimeOption(
                        selected = permPrefName == PermissionManager.PREF_ADB,
                        enabled = true,
                        title = stringResource(R.string.dsh_perm_adb),
                        summary = stringResource(R.string.dsh_perm_hint_adb),
                        onSelect = { onPermPrefChange(PermissionManager.PREF_ADB) },
                    )

                    if (perm.preferenceFellBack) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(
                                R.string.dsh_perm_fell_back,
                                perm.label(LocalContext.current),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
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

                    // 写操作授权：adb-shell.py 读 rootfs 里的标记文件，只读命令不受影响
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.dsh_adb_shell_allow),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(R.string.dsh_adb_shell_allow_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        ExpressiveSwitch(
                            checked = adbShellAllowed,
                            onCheckedChange = onAdbShellAllowedChange,
                            enabled = runtimeInstalled && !adbBusy,
                        )
                    }

                    // root shell：只有手机本身已 root 才有意义，所以顺带用权限通道判断
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.dsh_adb_root_allow),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(R.string.dsh_adb_root_allow_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        ExpressiveSwitch(
                            checked = adbRootAllowed,
                            onCheckedChange = onAdbRootAllowedChange,
                            enabled = runtimeInstalled && !adbBusy && (perm.suPresent || perm.shizukuIsRoot),
                        )
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
