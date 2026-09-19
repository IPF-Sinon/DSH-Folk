package me.bmax.apatch.ui.screen.settings

import java.io.File
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.RestoreWizardScreenDestination
import com.ramcosta.composedestinations.generated.destinations.DshPluginStoreScreenDestination
import com.ramcosta.composedestinations.generated.destinations.DshTerminalScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshBackupCrypto
import me.bmax.apatch.dsh.DshConfigBackup
import me.bmax.apatch.dsh.DshAppDataSnapshot
import me.bmax.apatch.dsh.DshPluginRepo
import me.bmax.apatch.dsh.DshRuntime
import me.bmax.apatch.dsh.DshSessionGroup
import me.bmax.apatch.util.BackupLogManager
import me.bmax.apatch.ui.component.DshPluginProgressDialog
import me.bmax.apatch.ui.screen.PluginProgressHost
import me.bmax.apatch.ui.theme.BackgroundConfig
import me.bmax.apatch.ui.theme.BackupConfig
import me.bmax.apatch.ui.viewmodel.DshPluginViewModel
import me.bmax.apatch.util.WebDavUtils
import me.bmax.apatch.util.ui.LocalSnackbarHost
import me.bmax.apatch.util.ui.NavigationBarsSpacer

/** 备份依赖的那个插件（应用侧查「装没装」时用，不需要 DSH 在跑）。 */
private const val DSH_CONFIG_MANAGER_PKG = "dsh-config-manager"

/**
 * 界面侧给这次插件探活的封顶时长。
 *
 * 比 [DshConfigBackup.STATUS_TIMEOUT_MS] 略长：让底层先超时、把「连不上」的具体原因
 * 带回来；这一层只兜住更外层的意外（取消不生效、IO 卡死），保证按钮一定会走到一个
 * 确定状态，而不是永远停在「检测中」。
 */
private const val STATUS_PROBE_TIMEOUT_MS = 20_000L

// 向导自己的常量（WIZARD_TEMP_DIRS / THEME_*）跟着 [RestoreWizardScreen] 走：
// Kotlin 顶层 private 是**文件私有**，这里放着也读不到。

@Destination<RootGraph>
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(navigator: DestinationsNavigator, highlightKey: String? = null) {
    val snackBarHost = LocalSnackbarHost.current
    val flat = BackgroundConfig.isCustomBackgroundEnabled || BackgroundConfig.settingsBackgroundUri != null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // DSH 配置备份状态。密码只放在 Compose 状态里，不写 prefs（落盘等于把加密密码明文存起来）。
    var dshBusy by rememberSaveable { mutableStateOf(false) }
    var dshMessage by rememberSaveable { mutableStateOf("") }
    var dshPassword by rememberSaveable { mutableStateOf("") }
    var dshRemote by remember { mutableStateOf<List<DshConfigBackup.RemoteBackup>>(emptyList()) }
    // 待确认的三个动作：从 DSH 内恢复、删 DSH 内备份、删快照。
    // 都是写操作，一律先问一句 —— 尤其删快照，删掉的是导入前的回滚点。
    var pendingRemoteRestore by remember { mutableStateOf<DshConfigBackup.RemoteBackup?>(null) }
    var pendingRemoteDelete by remember { mutableStateOf<DshConfigBackup.RemoteBackup?>(null) }
    var pendingSnapshotDelete by remember { mutableStateOf<DshConfigBackup.Snapshot?>(null) }
    // 「DSH 内已有的备份」那一块自己的忙状态与消息：它是独立的一块，
    // 不再蹭配置备份的 dshBusy/dshMessage（否则两个卡片会互相冲掉对方的状态）
    var dshBackupBusy by remember { mutableStateOf(false) }
    var dshBackupMessage by remember { mutableStateOf("") }

    // 云端备份 / 快照列表
    var cloudEntries by remember { mutableStateOf<List<WebDavUtils.RemoteEntry>>(emptyList()) }
    var cloudBusy by remember { mutableStateOf(false) }
    var cloudMessage by remember { mutableStateOf("") }
    var snapshots by remember { mutableStateOf<List<DshConfigBackup.Snapshot>>(emptyList()) }
    var snapshotBusy by remember { mutableStateOf(false) }
    var snapshotMessage by remember { mutableStateOf("") }
    // 整理未分组会话（全树扫描；停机做，改动不会被 dsh 的整份写回盖掉）
    var groupBusy by remember { mutableStateOf(false) }
    var groupMessage by remember { mutableStateOf("") }

    // 进度对话框：复用插件页那套（进度条 + 逐行日志 + 结束后「重启服务」）。
    // pnpm 装插件要几分钟，只在结束时弹一条 snackbar 的话，中途界面毫无反馈。
    var runVisible by remember { mutableStateOf(false) }
    var runTarget by remember { mutableStateOf("") }
    var runLines by remember { mutableStateOf(listOf<String>()) }
    var runRunning by remember { mutableStateOf(false) }
    var runFailed by remember { mutableStateOf(false) }
    var runNeedsRestart by remember { mutableStateOf(false) }
    // 待确认的快照恢复（预览结果）
    var pendingSnapshot by remember { mutableStateOf<DshConfigBackup.Snapshot?>(null) }
    var pendingActions by remember { mutableStateOf(0) }
    // 「恢复快照」要不要连软件设置一起退回。默认**选上**：用户点「恢复快照」的意图是
    // 「回到那个时候」，只回一半（DSH 配置回去了、设置还是导入后的）才是意外结果。
    // 只有确实存了设置副本（[DshAppDataSnapshot.has]）时才给出这个选项。
    var snapshotWithAppData by rememberSaveable { mutableStateOf(true) }
    var snapshotHasAppData by remember { mutableStateOf(false) }

    // 插件状态：进页面就查一次，别等用户点了「导出」才报错。
    // null = 检测中；下面的 LaunchedEffect 只跑一次（备份页不是热路径）。
    var pluginReady by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var pluginDetail by rememberSaveable { mutableStateOf("") }
    // 插件到底装没装：应用侧直接查容器里的插件目录（DshPluginRepo），不需要 DSH 在跑。
    // 不确定时一律当作「装了」—— 宁可少给一个「去安装」按钮，也不要指错路。
    var pluginAbsent by rememberSaveable { mutableStateOf(false) }
    // 用户点「重新检测」时 +1，让下面那个 LaunchedEffect 再跑一遍
    var pluginProbe by rememberSaveable { mutableStateOf(0) }
    val pluginViewModel = viewModel<DshPluginViewModel>()

    LaunchedEffect(pluginProbe) {
        // 探活自己封顶：那一页所有按钮的可点性都挂在这次往返上，不能让它无限期地悬着。
        // `status()` 内部已把这条请求压到 15s，这里再包一层是为了兜住「连上了但不回话」
        // 之外的意外（例如 DNS/代理层卡住），超时按**不就绪**处理 —— 用户拿到的是确定
        // 的状态加一个「重新检测」，而不是一个永远转圈、按钮却已经亮着的页面。
        val st = withTimeoutOrNull(STATUS_PROBE_TIMEOUT_MS) {
            withContext(Dispatchers.IO) { DshConfigBackup.status(context) }
        }
        val installed = withContext(Dispatchers.IO) {
            runCatching {
                DshPluginRepo.listInstalled().any { it.pkg == DSH_CONFIG_MANAGER_PKG }
            }.getOrDefault(true)
        }
        // 只有明确拿到 ready=true 才算就绪：null（检测中）与超时都不放行按钮。
        pluginReady = st?.ready == true
        pluginAbsent = st?.ready != true && !installed
        // 就绪时报版本，不就绪时报**插件自己给的原因** —— status.error 现在会把
        // 插件的 error 字段带出来（未授权、DSH 没起来、响应不是 JSON 都分得开），
        // 拿不到才退到「插件缺失」这句话。
        pluginDetail = when {
            st == null -> context.getString(R.string.dsh_backup_plugin_timeout)
            st.ready -> st.pluginVersion.ifEmpty { "—" }
            else -> st.error.ifEmpty { context.getString(R.string.dsh_backup_needs_running) }
        }
    }

    val notRunning = stringResource(R.string.dsh_backup_needs_running)
    val pluginMissing = stringResource(R.string.dsh_backup_plugin_missing)
    val exporting = stringResource(R.string.dsh_backup_exporting)
    val openDirFailed = stringResource(R.string.dsh_backup_open_dir_failed)
    val webdavOk = stringResource(R.string.dsh_backup_webdav_ok)
    val webdavFailed = stringResource(R.string.dsh_backup_webdav_failed)
    val remoteEmpty = stringResource(R.string.dsh_backup_remote_empty)
    val cloudTarget = stringResource(R.string.dsh_bk_cloud_restore_title)
    val snapshotTarget = stringResource(R.string.dsh_bk_snapshot_title)
    val tidyTarget = stringResource(R.string.dsh_bk_tidy_sessions)
    val clipboard = LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_category_backup),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    // 返回交给导航栈：向导已经是独立页面，这里不再有「页内退一步」这回事
                    IconButton(onClick = { navigator.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    }
                }
            )
        },
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackBarHost) },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.padding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                BackupSettingsContent(
                    dshBusy = dshBusy,
                    dshMessage = dshMessage,
                    dshPassword = dshPassword,
                    onDshPasswordChange = { dshPassword = it },
                    onDshExport = { plan ->
                        if (!plan.valid) {
                            // 含 vault 却没密码：界面已禁用按钮，这里再挡一道
                            dshMessage = context.getString(R.string.dsh_bk_vault_needs_password)
                            return@BackupSettingsContent
                        }
                        dshBusy = true
                        dshMessage = exporting
                        scope.launch(Dispatchers.IO) {
                            // 先确认插件在：DSH 没起来时直接报「需要先启动」，比让 HTTP 超时更清楚
                            val status = DshConfigBackup.status(context)
                            val text = if (!status.ready) {
                                // status.error 现在是插件/DSH 自己给的原因（未授权、没起来、非 JSON…），
                                // 原样显示比一律说「先启动 DSH」有用
                                status.error.ifEmpty { pluginMissing }
                            } else {
                                val r = DshConfigBackup.exportArchive(
                                    context,
                                    plan,
                                    // 阶段进度直接进结果区：不然用户只看到一个转圈，不知道在干什么
                                    onLine = { line -> withContext(Dispatchers.Main) { dshMessage = line } },
                                )
                                if (!r.ok) r.message else {
                                    val local = "${r.message}\n${r.location.ifBlank { r.file?.absolutePath ?: "" }}"
                                    // 开了云备份就顺手推一份到 WebDAV，失败只追加一行说明，不影响本地备份
                                    val zip = r.file
                                    val result = if (BackupConfig.isBackupEnabled && zip != null && BackupConfig.webdavUrl.isNotBlank()) {
                                        val up = WebDavUtils.uploadFile(
                                            baseUrl = BackupConfig.webdavUrl,
                                            user = BackupConfig.webdavUsername,
                                            pass = BackupConfig.webdavPassword,
                                            file = zip,
                                            // 用户没填远端路径时给个固定子目录，别把备份散在 WebDAV 根上
                                            subDir = BackupConfig.webdavPath.trim('/').ifEmpty { "DSH-Folk" },
                                        )
                                        local + "\n" + if (up.isSuccess) webdavOk
                                            else webdavFailed.format(up.exceptionOrNull()?.message ?: "")
                                    } else local
                                    // 暂存文件只是「下载→复制进公共目录」的中转：公共目录里已有正式副本，
                                    // 这里删掉避免导几次就攒出几百 MB。只有「连兜底目录都写不进」的极端
                                    // 情况 location 才指向暂存文件本身，那种情况不能删。
                                    zip?.takeIf { it.absolutePath != r.location }?.delete()
                                    result
                                }
                            }
                            withContext(Dispatchers.Main) {
                                dshMessage = text
                                dshBusy = false
                            }
                        }
                    },
                    // 「导入备份」进的是**独立的**恢复向导页（RestoreWizardScreen）。
                    // 曾经它是这一页里的一个分支，靠手写的「返回箭头」假装成页面 ——
                    // 而系统返回手势不经过那段代码，一划就把整个备份页弹掉、落到设置页
                    // （真机反馈）。现在它是一个真正的 @Destination：返回交给导航栈，
                    // 手势与箭头行为天然一致（权限记录页就是这个模式）。
                    // 这个 destination 带参数，生成的 Destination 对象必须**调用**之后才是 Direction
                    // （无参 destination 才能直接当 Direction 用 —— 权限记录页就是那种）。
                    onDshImport = {
                        navigator.navigate(RestoreWizardScreenDestination(stagedPath = null))
                    },
                    dshBackups = dshRemote,
                    dshBackupBusy = dshBackupBusy,
                    dshBackupMessage = dshBackupMessage,
                    onDshListRemote = {
                        dshBackupBusy = true
                        dshBackupMessage = ""
                        scope.launch(Dispatchers.IO) {
                            val list = DshConfigBackup.listRemoteBackups()
                            withContext(Dispatchers.Main) {
                                dshRemote = list
                                if (list.isEmpty()) dshBackupMessage = remoteEmpty
                                dshBackupBusy = false
                            }
                        }
                    },
                    onDshBackupRestore = { backup -> pendingRemoteRestore = backup },
                    onDshBackupDelete = { backup -> pendingRemoteDelete = backup },
                    onSnapshotDelete = { snap -> pendingSnapshotDelete = snap },
                    onDshOpenDir = {
                        val opened = DshConfigBackup.openBackupDir(context)
                        if (!opened) dshMessage = openDirFailed
                    },

                    cloudEntries = cloudEntries,
                    cloudBusy = cloudBusy,
                    cloudMessage = cloudMessage,
                    onCloudList = {
                        cloudBusy = true
                        cloudMessage = ""
                        scope.launch(Dispatchers.IO) {
                            val sub = BackupConfig.webdavPath.trim('/').ifEmpty { "DSH-Folk" }
                            val r = WebDavUtils.listRemote(
                                baseUrl = BackupConfig.webdavUrl,
                                user = BackupConfig.webdavUsername,
                                pass = BackupConfig.webdavPassword,
                                subDir = sub,
                            )
                            withContext(Dispatchers.Main) {
                                cloudBusy = false
                                cloudEntries = r.getOrDefault(emptyList())
                                cloudMessage = if (r.isSuccess) {
                                    ""
                                } else {
                                    // 服务端不支持列目录（405/501）与口令错、网络错要分开说：
                                    // 都糊成「失败」的话，用户不知道该改服务端还是改密码。
                                    val msg = r.exceptionOrNull()?.message ?: ""
                                    val code = Regex("HTTP (\\d+)").find(msg)?.groupValues?.get(1)
                                    if (code == "405" || code == "501") {
                                        context.getString(R.string.dsh_bk_cloud_unsupported, code)
                                    } else {
                                        context.getString(R.string.dsh_backup_webdav_failed, msg)
                                    }
                                }
                            }
                        }
                    },
                    onCloudRestore = { entry ->
                        // 云端只是「源」：下载到暂存后走与本地导入同一条管道
                        dshBusy = true
                        runVisible = true
                        runTarget = cloudTarget
                        runLines = listOf(context.getString(R.string.dsh_bk_cloud_downloading, entry.name))
                        runRunning = true
                        runFailed = false
                        runNeedsRestart = false
                        cloudMessage = ""
                        scope.launch(Dispatchers.IO) {
                            val dest = File(File(context.cacheDir, "config-import").apply { mkdirs() }, entry.name.ifBlank { "cloud-backup.zip" })
                            val dl = WebDavUtils.downloadTo(baseUrl = BackupConfig.webdavUrl, user = BackupConfig.webdavUsername, pass = BackupConfig.webdavPassword, remotePath = entry.path, dest = dest)
                            if (dl.isFailure) {
                                dest.delete()
                                val text = context.getString(R.string.dsh_bk_cloud_download_failed, dl.exceptionOrNull()?.message ?: "")
                                withContext(Dispatchers.Main) { cloudMessage = text; dshBusy = false; runRunning = false; runFailed = true; runLines = runLines + text }
                                return@launch
                            }
                            // 下载成功后与本地导入完全同一条管道：进恢复向导页，
                            // 把这份下载好的包直接交给它（用户不必再选一次文件）
                            val encrypted = withContext(Dispatchers.IO) { DshBackupCrypto.isArchiveBlobFile(dest) }
                            withContext(Dispatchers.Main) {
                                dshBusy = false
                                runVisible = false
                                runRunning = false
                                navigator.navigate(
                                    RestoreWizardScreenDestination(
                                        stagedPath = dest.absolutePath,
                                        stagedEncrypted = encrypted,
                                    ),
                                )
                            }
                        }
                    },
                    groupBusy = groupBusy,
                    groupMessage = groupMessage,
                    onTidySessions = {
                        groupBusy = true
                        groupMessage = ""
                        runVisible = true
                        runTarget = tidyTarget
                        runLines = emptyList()
                        runRunning = true
                        runFailed = false
                        // 归组只改注册表、不动插件树，但仍需重启才在界面生效
                        runNeedsRestart = true
                        scope.launch(Dispatchers.IO) {
                            val r = runCatching {
                                DshRuntime.withServiceStopped {
                                    DshSessionGroup.tidyAllSessions(context) { line ->
                                        withContext(Dispatchers.Main) { runLines = runLines + line }
                                    }
                                }
                            }.getOrNull()
                            val report = r ?: DshSessionGroup.Report(
                                failure = context.getString(R.string.dsh_bk_group_service_failed),
                            )
                            val text = buildString {
                                append(report.summary(context))
                                val detail = report.details()
                                if (detail.isNotEmpty()) append("\n").append(detail)
                            }
                            BackupLogManager.log("tidy sessions grouped=${report.grouped} of ${report.total}")
                            withContext(Dispatchers.Main) {
                                groupBusy = false
                                groupMessage = text
                                runRunning = false
                                runFailed = report.failure.isNotEmpty()
                                runNeedsRestart = report.grouped > 0
                                runLines = runLines + text
                            }
                        }
                    },
                    snapshots = snapshots,
                    snapshotBusy = snapshotBusy,
                    snapshotMessage = snapshotMessage,
                    onSnapshotList = {
                        snapshotBusy = true
                        snapshotMessage = ""
                        scope.launch(Dispatchers.IO) {
                            val list = DshConfigBackup.listSnapshots()
                            withContext(Dispatchers.Main) {
                                snapshots = list
                                snapshotBusy = false
                            }
                        }
                    },
                    onSnapshotRestore = { snap ->
                        // 先预览（dryRun=true，插件侧零写入）再把计划摆给用户确认：
                        // 恢复会覆盖设置并卸载快照里没有的插件，不能让用户事后才知道。
                        pendingSnapshot = snap
                        pendingActions = -1
                        snapshotMessage = ""
                        scope.launch(Dispatchers.IO) {
                            val p = DshConfigBackup.previewSnapshot(snap.id)
                            // 有没有软件设置副本要读文件，别放在主线程上查
                            val hasAppData = DshAppDataSnapshot.has(context, snap.id)
                            withContext(Dispatchers.Main) {
                                if (!p.ok) {
                                    pendingSnapshot = null
                                    snapshotMessage = context.getString(
                                        R.string.dsh_bk_snapshot_preview_failed,
                                        p.message,
                                    )
                                } else {
                                    pendingActions = p.actions
                                    snapshotWithAppData = true
                                    snapshotHasAppData = hasAppData
                                }
                            }
                        }
                    },
                    pluginReady = pluginReady,
                    pluginDetail = pluginDetail,
                    pluginAbsent = pluginAbsent,
                    onRecheckPlugin = { pluginProbe++ },
                    onGoInstallPlugin = { navigator.navigate(DshPluginStoreScreenDestination) },
                    onInstallRescueCli = { pluginViewModel.installRescueCli() },
                    onOpenTerminal = { navigator.navigate(DshTerminalScreenDestination) },
                    flat = flat,
                    highlightKey = highlightKey,
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
            item { NavigationBarsSpacer() }
        }
    }

    // 从 DSH 内的备份恢复：确认之后把文件取到本地，交给同一条导入流程
    // （密码 → 预检 → 会话/冲突询问 → 执行），不另开一条通道。
    pendingRemoteRestore?.let { backup ->
        AlertDialog(
            onDismissRequest = { pendingRemoteRestore = null },
            title = { Text(stringResource(R.string.dsh_bk_remote_restore_confirm_title)) },
            text = { Text(stringResource(R.string.dsh_bk_remote_restore_confirm_body, backup.name)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemoteRestore = null
                    dshBackupBusy = true
                    dshBackupMessage = ""
                    scope.launch(Dispatchers.IO) {
                        val fetched = DshConfigBackup.fetchRemoteBackup(context, backup)
                        val encrypted = fetched != null && DshBackupCrypto.isArchiveBlobFile(fetched)
                        withContext(Dispatchers.Main) {
                            dshBackupBusy = false
                            if (fetched == null) {
                                dshBackupMessage = context.getString(R.string.dsh_bk_remote_fetch_failed)
                            } else {
                                dshBackupMessage = ""
                                // 后面完全复用同一条路：进恢复向导页，包直接交给它
                                navigator.navigate(
                                    RestoreWizardScreenDestination(
                                        stagedPath = fetched.absolutePath,
                                        stagedEncrypted = encrypted,
                                    ),
                                )
                            }
                        }
                    }
                }) {
                    Text(stringResource(R.string.dsh_bk_backup_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoteRestore = null }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }

    // 删 DSH 内的备份
    pendingRemoteDelete?.let { backup ->
        AlertDialog(
            onDismissRequest = { pendingRemoteDelete = null },
            title = { Text(stringResource(R.string.dsh_bk_remote_delete_confirm_title)) },
            text = { Text(stringResource(R.string.dsh_bk_remote_delete_confirm_body, backup.name)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemoteDelete = null
                    dshBackupBusy = true
                    dshBackupMessage = ""
                    scope.launch(Dispatchers.IO) {
                        val err = DshConfigBackup.deleteRemoteBackup(context, backup)
                        BackupLogManager.log("remote backup delete " + backup.name + " err=" + err.ifEmpty { "none" })
                        val list = DshConfigBackup.listRemoteBackups()
                        withContext(Dispatchers.Main) {
                            dshRemote = list
                            dshBackupMessage = if (err.isEmpty()) {
                                context.getString(R.string.dsh_backup_remote_deleted, backup.name)
                            } else {
                                err
                            }
                            dshBackupBusy = false
                        }
                    }
                }) {
                    Text(stringResource(R.string.dsh_bk_backup_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoteDelete = null }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }

    // 删快照（导入前的回滚点，删掉就回不去了，所以确认文案要说清）
    pendingSnapshotDelete?.let { snap ->
        AlertDialog(
            onDismissRequest = { pendingSnapshotDelete = null },
            title = { Text(stringResource(R.string.dsh_bk_snapshot_delete_confirm_title)) },
            text = { Text(stringResource(R.string.dsh_bk_snapshot_delete_confirm_body, snap.id)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingSnapshotDelete = null
                    snapshotBusy = true
                    scope.launch(Dispatchers.IO) {
                        val r = DshConfigBackup.deleteSnapshot(context, snap.id)
                        BackupLogManager.log("snapshot delete " + snap.id + " ok=" + r.ok)
                        val list = DshConfigBackup.listSnapshots()
                        withContext(Dispatchers.Main) {
                            snapshots = list
                            snapshotBusy = false
                            snapshotMessage = if (r.detail.isBlank()) {
                                r.message
                            } else {
                                r.message + "\n" + r.detail
                            }
                        }
                    }
                }) {
                    Text(stringResource(R.string.dsh_bk_snapshot_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSnapshotDelete = null }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }

    // 快照恢复确认：把预览出来的动作数摆在这里，用户点「恢复」才真的写盘
    pendingSnapshot?.let { snap ->
        if (pendingActions >= 0) {
            AlertDialog(
                onDismissRequest = { pendingSnapshot = null },
                title = { Text(stringResource(R.string.dsh_bk_snapshot_confirm_title)) },
                text = {
                    Column {
                        Text(
                            stringResource(
                                R.string.dsh_bk_snapshot_confirm_body,
                                snap.id,
                                pendingActions,
                            ),
                        )
                        Spacer(Modifier.height(12.dp))
                        // 软件设置不在这份快照里（插件够不到 SharedPreferences），是我们自己
                        // 在导入前另外存的一份；存过才给这个选项，否则它会是一个骗人的开关。
                        if (snapshotHasAppData) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = snapshotWithAppData,
                                    onCheckedChange = { snapshotWithAppData = it },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.dsh_bk_snapshot_appdata_option),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        text = stringResource(R.string.dsh_bk_snapshot_appdata_option_note),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.dsh_bk_snapshot_appdata_absent),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        pendingSnapshot = null
                        snapshotBusy = true
                        runVisible = true
                        runTarget = snapshotTarget
                        runLines = emptyList()
                        runRunning = true
                        runFailed = false
                        runNeedsRestart = true
                        scope.launch(Dispatchers.IO) {
                            val r = DshConfigBackup.restoreSnapshot(context, snap.id)
                            // 软件设置由我们自己回（插件够不到 SharedPreferences）。
                            // 顺序：先插件快照、后软件设置 —— 与导入时的 插件→App数据→主题 一致，
                            // 两边都会写外观参数，后写的才是最终生效的那份。
                            val appDataOutcome = if (snapshotWithAppData && r.ok) {
                                DshAppDataSnapshot.restore(context, snap.id)
                            } else {
                                null
                            }
                            BackupLogManager.log(
                                "snapshot restore ${snap.id} ok=${r.ok} appdata=${appDataOutcome ?: "skipped"}",
                            )
                            withContext(Dispatchers.Main) {
                                snapshotBusy = false
                                val appDataNote = when (appDataOutcome) {
                                    DshAppDataSnapshot.Outcome.RESTORED ->
                                        context.getString(R.string.dsh_bk_snapshot_appdata_restored)
                                    DshAppDataSnapshot.Outcome.FAILED ->
                                        context.getString(R.string.dsh_bk_snapshot_appdata_failed)
                                    DshAppDataSnapshot.Outcome.MISSING ->
                                        context.getString(R.string.dsh_bk_snapshot_appdata_absent)
                                    null -> ""
                                }
                                snapshotMessage = (if (r.detail.isBlank()) r.message else "${r.message}\n${r.detail}") +
                                    if (appDataNote.isNotEmpty()) "\n" + appDataNote else ""
                                runRunning = false
                                runFailed = !r.ok
                                runLines = listOfNotNull(
                                    r.message.takeIf { it.isNotBlank() },
                                    r.detail.takeIf { it.isNotBlank() },
                                )
                            }
                        }
                    }) {
                        Text(stringResource(R.string.dsh_bk_snapshot_restore_now))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingSnapshot = null }) {
                        Text(stringResource(R.string.close))
                    }
                },
            )
        }
    }

    // 导入 / 云端恢复 / 快照恢复共用一个进度对话框：
    // 结束后若插件说「需重启才生效」，主按钮就是「重启服务」—— 以前这句话只出现在文案里，
    // 用户看到却找不到按钮，回头就以为恢复没生效。
    if (runVisible) {
        DshPluginProgressDialog(
            target = runTarget,
            lines = runLines,
            running = runRunning,
            failed = runFailed,
            onDismiss = { runVisible = false },
            onCopy = { clipboard.setText(AnnotatedString(it)) },
            onRestart = {
                runVisible = false
                // BackupLogManager.log 是 suspend，这里不是挂起上下文，得自己开一个
                scope.launch { BackupLogManager.log("restart DSH after backup/restore") }
                DshRuntime.restart()
            },
            needsRestart = runNeedsRestart,
        )
    }

    // CLI 安装与插件安装共用同一套进度对话框（都是分钟级的 npm/pnpm 操作）
    PluginProgressHost(pluginViewModel)
}
