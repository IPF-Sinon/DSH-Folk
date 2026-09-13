package me.bmax.apatch.ui.screen.settings

import me.bmax.apatch.util.ui.showToast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import me.bmax.apatch.R
import me.bmax.apatch.ui.component.ExpressiveCard
import me.bmax.apatch.ui.component.SplicedColumnGroup
import me.bmax.apatch.ui.component.ToggleSettingCard
import me.bmax.apatch.dsh.DshConfigBackup
import me.bmax.apatch.ui.theme.BackupConfig
import me.bmax.apatch.util.BackupLogManager
import me.bmax.apatch.util.WebDavUtils
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon

/**
 * 救急 CLI 的常用命令。
 *
 * 注意 `--omit=peer`：不带它 npm 会去装 16 个 `@deepseek-ai/…` peer 包，
 * 在手机上又慢又容易失败，而离线 CLI 一个都不需要（只用 js-yaml）。
 */
private val RESCUE_COMMANDS = listOf(
    "npm install -g dsh-config-manager@latest --omit=peer",
    "dsh-config-manager snapshots",
    "dsh-config-manager restore --dry-run",
    "dsh-config-manager reinstall --list",
)

/** 导入冲突策略的三个选项（顺序即界面顺序，默认第一项）。 */
private val IMPORT_STRATEGIES = listOf(
    Triple(DshConfigBackup.STRATEGY_MERGE, R.string.dsh_bk_strategy_merge, R.string.dsh_bk_strategy_merge_desc),
    Triple(DshConfigBackup.STRATEGY_REPLACE, R.string.dsh_bk_strategy_replace, R.string.dsh_bk_strategy_replace_desc),
    Triple(
        DshConfigBackup.STRATEGY_SKIP_EXISTING,
        R.string.dsh_bk_strategy_skip,
        R.string.dsh_bk_strategy_skip_desc,
    ),
).map { StrategyOption(it.first, it.second, it.third) }

private data class StrategyOption(val id: String, val label: Int, val desc: Int)

/** 快照/远端条目的时间。解析不出来返回 null —— 由调用方说「时间未知」，不画一个假的 1970。 */
private fun formatSnapshotTime(ms: Long): String? =
    if (ms <= 0L) null else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(ms))

/** 云端条目的大小 + 时间一行摘要；两项都拿不到时返回空串（那一行就不显示副标题）。 */
private fun formatRemoteMeta(entry: WebDavUtils.RemoteEntry): String {
    val size = when {
        entry.sizeBytes <= 0L -> null
        entry.sizeBytes < 1024 -> "${entry.sizeBytes} B"
        entry.sizeBytes < 1024 * 1024 -> "${entry.sizeBytes / 1024} KB"
        else -> "${entry.sizeBytes / (1024 * 1024)} MB"
    }
    return listOfNotNull(size, formatSnapshotTime(entry.lastModifiedMs)).joinToString(" ｜ ")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupSettingsContent(
    /** DSH 配置备份：是否正在跑（导出/导入期间禁用按钮）。 */
    dshBusy: Boolean,
    /** 最近一次导出/导入的结果文本。 */
    dshMessage: String,
    dshPassword: String,
    onDshPasswordChange: (String) -> Unit,
    /** 导出是否带上 sessions（会话记录）；默认关。 */
    includeSessions: Boolean = false,
    onIncludeSessionsChange: (Boolean) -> Unit = {},
    /** 导入是否恢复 sessions（会话记录）；默认关。 */
    importSessions: Boolean = false,
    onImportSessionsChange: (Boolean) -> Unit = {},
    onDshExport: () -> Unit,
    onDshImport: () -> Unit,
    onDshOpenDir: () -> Unit,
    /** 导入冲突策略（merge / replace / skipExisting）；以前写死 merge，用户没得选。 */
    importStrategy: String = DshConfigBackup.STRATEGY_MERGE,
    onImportStrategyChange: (String) -> Unit = {},
    /** 云端（WebDAV）备份列表；空表示还没列过或确实没有。 */
    cloudEntries: List<WebDavUtils.RemoteEntry> = emptyList(),
    cloudBusy: Boolean = false,
    cloudMessage: String = "",
    onCloudList: () -> Unit = {},
    onCloudRestore: (WebDavUtils.RemoteEntry) -> Unit = {},
    /** 插件保留的快照（恢复的最后依靠）。 */
    snapshots: List<DshConfigBackup.Snapshot> = emptyList(),
    snapshotBusy: Boolean = false,
    snapshotMessage: String = "",
    onSnapshotList: () -> Unit = {},
    onSnapshotRestore: (DshConfigBackup.Snapshot) -> Unit = {},
    /** 运行时 exports 目录里的备份（容器内，文件管理器看不到）。 */
    dshRemoteBackups: List<String>,
    onDshListRemote: () -> Unit,
    /**
     * dsh-config-manager 插件是否就绪。null = 还在检测。
     *
     * 导出/导入完全走这个插件的回环 API，没它这一页做不了事 —— 所以状态必须在
     * 进页面时就摆出来，而不是等用户点了「导出」再报错。
     */
    pluginReady: Boolean? = null,
    /** 插件版本（就绪时显示），或未就绪的原因。 */
    pluginDetail: String = "",
    onGoInstallPlugin: () -> Unit = {},
    /** 在容器内安装独立的救急 CLI（与插件是两回事，见卡片说明）。 */
    onInstallRescueCli: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    flat: Boolean = false,
    highlightKey: String? = null,
) {
    val context = LocalContext.current

    val showWebDavDialog = remember { mutableStateOf(false) }

    SplicedColumnGroup(flat = flat, highlightKey = highlightKey) {
        // DSH 配置备份 —— 直接复用容器内 dsh-config-manager 的导出格式，保证与桌面端互通
        item(key = "backup_dsh_config") {
            ExpressiveCard(flat = flat) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.SettingsBackupRestore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.dsh_backup_section),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(R.string.dsh_backup_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // 插件状态行：这一页的所有能力都建立在它之上
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when (pluginReady) {
                            null -> {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.dsh_backup_plugin_checking),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            true -> Text(
                                text = stringResource(R.string.dsh_backup_plugin_ready, pluginDetail),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            false -> Column {
                                Text(
                                    text = stringResource(R.string.dsh_backup_plugin_not_ready, pluginDetail),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                TextButton(onClick = onGoInstallPlugin) {
                                    Text(stringResource(R.string.dsh_backup_plugin_install))
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = dshPassword,
                        onValueChange = onDshPasswordChange,
                        label = { Text(stringResource(R.string.dsh_backup_password)) },
                        supportingText = {
                            Text(stringResource(R.string.dsh_backup_password_summary))
                        },
                        singleLine = true,
                        enabled = !dshBusy,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Switch(
                            checked = includeSessions,
                            onCheckedChange = onIncludeSessionsChange,
                            enabled = !dshBusy,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.dsh_backup_include_sessions),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(R.string.dsh_backup_include_sessions_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Switch(
                            checked = importSessions,
                            onCheckedChange = onImportSessionsChange,
                            enabled = !dshBusy,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.dsh_backup_import_sessions),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(R.string.dsh_backup_import_sessions_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.dsh_bk_strategy_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (opt in IMPORT_STRATEGIES) {
                            FilterChip(
                                selected = importStrategy == opt.id,
                                enabled = !dshBusy,
                                onClick = { onImportStrategyChange(opt.id) },
                                label = { Text(stringResource(opt.label)) },
                            )
                        }
                    }
                    Text(
                        text = stringResource(
                            (IMPORT_STRATEGIES.firstOrNull { it.id == importStrategy }
                                ?: IMPORT_STRATEGIES.first()).desc,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 插件未就绪时禁用，而不是让用户点了再收一条报错。
                        // 还在检测（null）时放行：不确定就别拦，检测本身可能超时。
                        val canRun = !dshBusy && pluginReady != false
                        Button(onClick = onDshExport, enabled = canRun) {
                            Text(stringResource(R.string.dsh_backup_export))
                        }
                        OutlinedButton(onClick = onDshImport, enabled = canRun) {
                            Text(stringResource(R.string.dsh_backup_import))
                        }
                        TextButton(onClick = onDshOpenDir) {
                            Text(stringResource(R.string.dsh_backup_open_dir))
                        }
                        TextButton(onClick = onDshListRemote, enabled = canRun) {
                            Text(stringResource(R.string.dsh_backup_remote_refresh))
                        }
                        if (dshBusy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.dsh_backup_export_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (dshRemoteBackups.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.dsh_backup_remote_list),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 160.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            for (line in dshRemoteBackups) {
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (dshMessage.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = dshMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }

        // ───────── 恢复到快照 ─────────
        // 快照目录永不被自动清理（插件源码注释：「它是恢复的最后依靠」）。
        // 恢复会覆盖设置文件并卸载快照里没有的插件，所以流程是「先预览（零写入）→ 确认 → 执行」。
        item(key = "backup_snapshot") {
            ExpressiveCard(flat = flat) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column {
                        Text(
                            text = stringResource(R.string.dsh_bk_snapshot_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.dsh_bk_snapshot_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    // canRun 提到 Column 作用域：下面的列表行也要用它（放在 Row 里就只有
                    // 那一行可见，行外的按钮引用会编译不过）
                    val canRun = !snapshotBusy && pluginReady != false
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = onSnapshotList, enabled = canRun) {
                            Text(stringResource(R.string.dsh_bk_snapshot_list))
                        }
                        if (snapshotBusy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                    if (snapshots.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            for (snap in snapshots) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = snap.id,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                        Text(
                                            text = formatSnapshotTime(snap.createdAtMs)
                                                ?: stringResource(R.string.dsh_bk_snapshot_time_unknown),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(onClick = { onSnapshotRestore(snap) }, enabled = canRun) {
                                        Text(stringResource(R.string.dsh_bk_snapshot_restore_now))
                                    }
                                }
                            }
                        }
                    } else if (!snapshotBusy && snapshotMessage.isBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.dsh_bk_snapshot_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (snapshotMessage.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(snapshotMessage, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // ───────── 救急 CLI ─────────
        // 这一整页（含上面的导出/导入）都走 dsh-config-manager 插件的回环 HTTP API，
        // 而插件住在 DSH 里面 —— DSH 起不来时它也用不了。它的 CLI 则完全独立于 DSH
        // 运行时（只依赖 js-yaml，16 个 @deepseek-ai/* 全在 peerDependencies；已实测
        // peer 全缺时 snapshots / help 均正常），所以配置损坏时的第一救急手段是 CLI。
        item(key = "backup_rescue_cli") {
            ExpressiveCard(flat = flat) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    var expanded by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.HealthAndSafety,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.dsh_backup_rescue_section),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(
                                stringResource(
                                    if (expanded) R.string.dsh_backup_rescue_collapse
                                    else R.string.dsh_backup_rescue_expand
                                )
                            )
                        }
                    }

                    if (expanded) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.dsh_backup_rescue_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(onClick = onInstallRescueCli, enabled = !dshBusy) {
                                Text(stringResource(R.string.dsh_backup_rescue_install_cli))
                            }
                            TextButton(onClick = onOpenTerminal) {
                                Text(stringResource(R.string.dsh_backup_rescue_open_terminal))
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.dsh_backup_rescue_cmds_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        for (cmd in RESCUE_COMMANDS) {
                            Text(
                                text = cmd,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }

        item(key = "backup_cloud") {
            ToggleSettingCard(
                flat = flat,
                icon = Icons.Filled.Cloud,
                title = stringResource(id = R.string.settings_enable_cloud_backup),
                description = stringResource(id = R.string.settings_enable_cloud_backup_summary),
                checked = BackupConfig.isBackupEnabled,
                onCheckedChange = {
                    BackupConfig.isBackupEnabled = it
                    BackupConfig.save(context)
                }
            )
        }

        // ───────── 从云端恢复 ─────────
        // 以前只有上传：换机 / 清机之后，WebDAV 上那份备份在 App 里取不回来，
        // 得自己去文件管理器下载再走「导入备份」。这里把列目录 + 下载并进同一条导入管道。
        item(key = "backup_cloud_restore", visible = BackupConfig.isBackupEnabled) {
            ExpressiveCard(flat = flat) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column {
                        Text(
                            text = stringResource(R.string.dsh_bk_cloud_restore_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.dsh_bk_cloud_restore_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    // 同快照区块：canRun 提到 Column 作用域，列表行里的「恢复」按钮也要用
                    val hasUrl = BackupConfig.webdavUrl.isNotBlank()
                    val canRun = !cloudBusy && hasUrl && pluginReady != false
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = onCloudList, enabled = canRun) {
                            Text(stringResource(R.string.dsh_bk_cloud_list))
                        }
                        if (cloudBusy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                    if (BackupConfig.webdavUrl.isBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.dsh_bk_cloud_no_url),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (cloudEntries.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            for (entry in cloudEntries) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(entry.name, style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            text = formatRemoteMeta(entry),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(
                                        onClick = { onCloudRestore(entry) },
                                        enabled = canRun,
                                    ) {
                                        Text(stringResource(R.string.dsh_bk_snapshot_restore_now))
                                    }
                                }
                            }
                        }
                    } else if (!cloudBusy && cloudMessage.isBlank() && BackupConfig.webdavUrl.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.dsh_bk_cloud_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (cloudMessage.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(cloudMessage, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item(key = "backup_webdav", visible = BackupConfig.isBackupEnabled) {
            val configureWebDavTitle = stringResource(id = R.string.settings_configure_webdav)
            ExpressiveCard(
                flat = flat,
                onClick = {
                    showWebDavDialog.value = true
                }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            text = configureWebDavTitle,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }

    if (showWebDavDialog.value) {
        WebDavConfigDialog(showWebDavDialog)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavConfigDialog(showDialog: MutableState<Boolean>) {
    val context = LocalContext.current
    var url by remember { mutableStateOf(BackupConfig.webdavUrl) }
    var username by remember { mutableStateOf(BackupConfig.webdavUsername) }
    var password by remember { mutableStateOf(BackupConfig.webdavPassword) }
    var path by remember { mutableStateOf(BackupConfig.webdavPath) }
    var isTesting by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    BasicAlertDialog(
        onDismissRequest = { showDialog.value = false },
        properties = DialogProperties(
            decorFitsSystemWindows = true,
            usePlatformDefaultWidth = false,
        )
    ) {
        Surface(
            modifier = Modifier
                .width(400.dp)
                .wrapContentHeight(),
            shape = MaterialTheme.shapes.large,
            tonalElevation = AlertDialogDefaults.TonalElevation,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 1f)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.webdav_config_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.webdav_url)) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    singleLine = true
                )

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.webdav_username)) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    singleLine = true
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.webdav_password)) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )

                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text(stringResource(R.string.webdav_path_label)) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { showDialog.value = false }) {
                        Text(stringResource(android.R.string.cancel))
                    }

                    TextButton(onClick = { showLogDialog = true }) {
                        Text(stringResource(R.string.webdav_view_logs))
                    }

                    TextButton(
                        onClick = {
                            scope.launch {
                                isTesting = true
                                val result = WebDavUtils.testConnection(url, username, password)
                                isTesting = false
                                if (result.isSuccess) {
                                    showToast(context, context.getString(R.string.webdav_test_success))
                                } else {
                                    showToast(context, context.getString(R.string.webdav_test_failed, result.exceptionOrNull()?.message))
                                }
                            }
                        },
                        enabled = !isTesting
                    ) {
                        Text(stringResource(R.string.test))
                    }

                    Button(onClick = {
                        BackupConfig.webdavUrl = url
                        BackupConfig.webdavUsername = username
                        BackupConfig.webdavPassword = password
                        BackupConfig.webdavPath = path
                        BackupConfig.save(context)
                        showDialog.value = false
                    }) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
    }

    if (showLogDialog) {
        BackupLogDialog(showDialog = remember { mutableStateOf(true) }, onDismiss = { showLogDialog = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupLogDialog(showDialog: MutableState<Boolean>, onDismiss: () -> Unit) {
    var logs by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        logs = BackupLogManager.readLogs()
    }

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            decorFitsSystemWindows = true,
            usePlatformDefaultWidth = false,
        )
    ) {
        Surface(
            modifier = Modifier
                .width(350.dp)
                .height(500.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = AlertDialogDefaults.TonalElevation,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 1f)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.webdav_backup_logs_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Surface(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    val scrollState = rememberScrollState()
                    Text(
                        text = logs.ifEmpty { stringResource(R.string.webdav_no_logs) },
                        modifier = Modifier
                            .padding(8.dp)
                            .verticalScroll(scrollState),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        scope.launch {
                            BackupLogManager.clearLogs()
                            logs = ""
                        }
                    }) {
                        Text(stringResource(R.string.webdav_clear_logs))
                    }
                    Button(onClick = onDismiss) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        }
    }
}
