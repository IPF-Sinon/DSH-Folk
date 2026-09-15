package me.bmax.apatch.ui.screen.settings

import me.bmax.apatch.util.ui.showToast
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.ui.component.ExpressiveCard
import me.bmax.apatch.ui.component.SplicedColumnGroup
import me.bmax.apatch.ui.component.ToggleSettingCard
import me.bmax.apatch.dsh.BackupScope
import me.bmax.apatch.dsh.DshBackupArchive
import me.bmax.apatch.dsh.DshConfigBackup
import me.bmax.apatch.dsh.ExportPlan
import me.bmax.apatch.dsh.SessionPick
import me.bmax.apatch.ui.theme.BackupConfig
import me.bmax.apatch.util.BackupLogManager
import me.bmax.apatch.util.WebDavUtils
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import java.security.SecureRandom

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

/** 导出数据范围的四个档位（枚举顺序即滑块顺序，默认 BOTH）。 */
private data class ScopeOption(val scope: BackupScope, val label: Int, val summary: Int)

private val SCOPE_OPTIONS = listOf(
    ScopeOption(BackupScope.APP_ONLY, R.string.dsh_bk_scope_app_only, R.string.dsh_bk_scope_app_only_summary),
    ScopeOption(BackupScope.DSH_ONLY, R.string.dsh_bk_scope_dsh_only, R.string.dsh_bk_scope_dsh_only_summary),
    ScopeOption(BackupScope.BOTH, R.string.dsh_bk_scope_both, R.string.dsh_bk_scope_both_summary),
    ScopeOption(BackupScope.BOTH_VAULT, R.string.dsh_bk_scope_vault, R.string.dsh_bk_scope_vault_summary),
)

/** 会话数量的五个档位（枚举顺序即滑块顺序，默认 NONE）。 */
private data class SessionOption(val pick: SessionPick, val label: Int)

private val SESSION_OPTIONS = listOf(
    SessionOption(SessionPick.NONE, R.string.dsh_bk_sessions_pick_none),
    SessionOption(SessionPick.P5, R.string.dsh_bk_sessions_pick_p5),
    SessionOption(SessionPick.P20, R.string.dsh_bk_sessions_pick_p20),
    SessionOption(SessionPick.P50, R.string.dsh_bk_sessions_pick_p50),
    SessionOption(SessionPick.ALL, R.string.dsh_bk_sessions_pick_all),
)

/** 密码强度四档的文案资源（下标即档位）。 */
private val PASSWORD_STRENGTH_LABELS = listOf(
    R.string.dsh_bk_pw_weak,
    R.string.dsh_bk_pw_fair,
    R.string.dsh_bk_pw_good,
    R.string.dsh_bk_pw_strong,
)

/**
 * 密码强度（0..3）：长度 + 字符种类。空串返回 -1 —— 界面此时显示「留空不加密」提示，
 * 而不是一句强度文案。
 */
private fun passwordStrength(pw: String): Int {
    if (pw.isEmpty()) return -1
    var classes = 0
    if (pw.any { it.isLowerCase() }) classes++
    if (pw.any { it.isUpperCase() }) classes++
    if (pw.any { it.isDigit() }) classes++
    if (pw.any { !it.isLetterOrDigit() }) classes++
    val lenScore = when {
        pw.length >= 16 -> 3
        pw.length >= 12 -> 2
        pw.length >= 8 -> 1
        else -> 0
    }
    val classScore = when {
        classes >= 4 -> 2
        classes >= 3 -> 1
        else -> 0
    }
    // 注意这里必须用无主语 when：Kotlin 的带主语 when 只接受相等/包含判定，
    // 写 ">= 4 ->" 会直接是语法错误（编译期才发现）。
    return when {
        lenScore + classScore >= 4 -> 3
        lenScore + classScore == 3 -> 2
        lenScore + classScore == 2 -> 1
        else -> 0
    }
}

/**
 * 随机密码：20 位，字符集去掉易混字符（0/O/1/l/I）。
 * 用 SecureRandom 而不是 Math.random：这是要拿去当加密口令的，不能用弱随机源。
 */
private fun randomPassword(): String {
    val charset = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
    val rnd = SecureRandom()
    return buildString(20) {
        repeat(20) { append(charset[rnd.nextInt(charset.length)]) }
    }
}

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
    /**
     * 导出。参数是一个完整的 [ExportPlan]（数据范围 + 会话数量 + 密码），
     * 由本页面组装好后交出去；含 vault 却没密码时 plan.valid == false，
     * 界面已经禁了按钮，这里再挡一道。
     */
    onDshExport: (ExportPlan) -> Unit,
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
    /** 会话归组：是否在跑（走停机 → 归组 → 起服务，见 DshRuntime.withServiceStopped）。 */
    groupBusy: Boolean = false,
    groupMessage: String = "",
    onTidySessions: () -> Unit = {},
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

    // ── 导出选项：数据范围 / 会话数量 / 加密密码 ──
    // 档位存索引而不是枚举：滑块拖动是连续值，Dialog 内用预览值（scopePreview），
    // 确认了才写回正式值（scopeIndex）—— 含 vault 那档还要先过一道警告框。
    var scopeIndex by rememberSaveable { mutableStateOf(2) } // 默认 BOTH
    var sessionIndex by rememberSaveable { mutableStateOf(0) } // 默认 NONE
    var showScopeDialog by remember { mutableStateOf(false) }
    var showSessionsDialog by remember { mutableStateOf(false) }
    var showVaultWarnDialog by remember { mutableStateOf(false) }
    var scopePreview by remember { mutableStateOf(2) }
    var sessionPreview by remember { mutableStateOf(0) }
    // 本机（设备上）的会话总数，给「共 N 个」用。文件遍历放 IO 做一次并缓存，
    // 拿不到就干脆不显示 —— 不为它新造字符串。
    var localSessionCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        localSessionCount = withContext(Dispatchers.IO) {
            runCatching { DshBackupArchive.sessionCount(context) }.getOrNull()
        }
    }

    val scopeOption = SCOPE_OPTIONS[scopeIndex.coerceIn(0, SCOPE_OPTIONS.lastIndex)]
    val currentScope = scopeOption.scope
    val sessionOption = SESSION_OPTIONS[sessionIndex.coerceIn(0, SESSION_OPTIONS.lastIndex)]
    val currentPick = sessionOption.pick

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

                    // ── 导出内容：数据范围 ──
                    // 滑块式设置项（照日志窗口那套交互）：一行显示当前档位，点开是对话框内
                    // Slider 拖动即时更新文案。确认含 vault 那档之前先弹警告（那里有凭据原文）。
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !dshBusy) {
                                scopePreview = scopeIndex
                                showScopeDialog = true
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.dsh_bk_scope_title),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(scopeOption.label),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(scopeOption.summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // ── 导出内容：会话数量（仅软件数据的包里没有 DSH 会话，滑块不显示）──
                    if (currentScope != BackupScope.APP_ONLY) {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !dshBusy) {
                                    sessionPreview = sessionIndex
                                    showSessionsDialog = true
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.dsh_bk_sessions_title),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = stringResource(sessionOption.label),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                localSessionCount?.let { count ->
                                    Text(
                                        text = stringResource(R.string.dsh_bk_sessions_total, count),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    // ── 加密密码：随机生成 + 实时强度；含 vault 时必填 ──
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = dshPassword,
                            onValueChange = onDshPasswordChange,
                            label = { Text(stringResource(R.string.dsh_bk_pw_title)) },
                            singleLine = true,
                            enabled = !dshBusy,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { onDshPasswordChange(randomPassword()) },
                            enabled = !dshBusy,
                        ) {
                            Text(stringResource(R.string.dsh_bk_pw_random))
                        }
                    }
                    val strength = passwordStrength(dshPassword)
                    val vaultNeedsPassword = currentScope == BackupScope.BOTH_VAULT && dshPassword.isEmpty()
                    Text(
                        text = when {
                            vaultNeedsPassword -> stringResource(R.string.dsh_bk_pw_required)
                            strength < 0 -> stringResource(R.string.dsh_bk_pw_hint)
                            else -> stringResource(
                                R.string.dsh_bk_pw_strength,
                                stringResource(PASSWORD_STRENGTH_LABELS[strength]),
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (vaultNeedsPassword) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = onTidySessions, enabled = !groupBusy && pluginReady != false) {
                            Text(stringResource(R.string.dsh_bk_tidy_sessions))
                        }
                        if (groupBusy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                    Text(
                        text = stringResource(R.string.dsh_bk_tidy_sessions_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (groupMessage.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = groupMessage,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState()),
                        )
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
                        val exportPlan = ExportPlan(
                            scope = currentScope,
                            sessions = currentPick,
                            password = dshPassword,
                        )
                        val canRun = !dshBusy && pluginReady != false
                        Button(
                            onClick = { onDshExport(exportPlan) },
                            // 含 vault 却没密码：plan.valid == false，不允许开始导出
                            enabled = canRun && exportPlan.valid,
                        ) {
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

    // ── 数据范围滑块：预览档位即时更新文案，确认才写回 ──
    if (showScopeDialog) {
        val preview = SCOPE_OPTIONS[scopePreview.coerceIn(0, SCOPE_OPTIONS.lastIndex)]
        AlertDialog(
            onDismissRequest = { showScopeDialog = false },
            title = { Text(stringResource(R.string.dsh_bk_scope_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(preview.summary),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = scopePreview.toFloat(),
                        onValueChange = { scopePreview = it.toInt() },
                        valueRange = 0f..SCOPE_OPTIONS.lastIndex.toFloat(),
                        steps = SCOPE_OPTIONS.lastIndex - 1,
                    )
                    Text(
                        text = stringResource(preview.label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showScopeDialog = false
                    val next = scopePreview.coerceIn(0, SCOPE_OPTIONS.lastIndex)
                    if (SCOPE_OPTIONS[next].scope == BackupScope.BOTH_VAULT && next != scopeIndex) {
                        // 含 vault 会把凭据原文带进包里：确认选中之前先让用户过目警告
                        showVaultWarnDialog = true
                    } else {
                        scopeIndex = next
                    }
                }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showScopeDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    // ── 含 vault 档的警告：确认才真正选中，取消保持原档位 ──
    if (showVaultWarnDialog) {
        AlertDialog(
            onDismissRequest = { showVaultWarnDialog = false },
            title = { Text(stringResource(R.string.dsh_bk_scope_vault_warn_title)) },
            text = { Text(stringResource(R.string.dsh_bk_scope_vault_warn_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showVaultWarnDialog = false
                    scopeIndex = scopePreview.coerceIn(0, SCOPE_OPTIONS.lastIndex)
                }) {
                    Text(stringResource(R.string.dsh_bk_scope_vault_warn_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showVaultWarnDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    // ── 会话数量滑块 ──
    if (showSessionsDialog) {
        val preview = SESSION_OPTIONS[sessionPreview.coerceIn(0, SESSION_OPTIONS.lastIndex)]
        AlertDialog(
            onDismissRequest = { showSessionsDialog = false },
            title = { Text(stringResource(R.string.dsh_bk_sessions_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(preview.label),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = sessionPreview.toFloat(),
                        onValueChange = { sessionPreview = it.toInt() },
                        valueRange = 0f..SESSION_OPTIONS.lastIndex.toFloat(),
                        steps = SESSION_OPTIONS.lastIndex - 1,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSessionsDialog = false
                    sessionIndex = sessionPreview.coerceIn(0, SESSION_OPTIONS.lastIndex)
                }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSessionsDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
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
