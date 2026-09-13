package me.bmax.apatch.ui.screen.settings

import android.net.Uri
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import com.ramcosta.composedestinations.generated.destinations.DshPluginStoreScreenDestination
import com.ramcosta.composedestinations.generated.destinations.DshTerminalScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshConfigBackup
import me.bmax.apatch.dsh.DshRuntime
import me.bmax.apatch.util.BackupLogManager
import me.bmax.apatch.ui.component.DshPluginProgressDialog
import me.bmax.apatch.ui.screen.PluginProgressHost
import me.bmax.apatch.ui.theme.BackgroundConfig
import me.bmax.apatch.ui.theme.BackupConfig
import me.bmax.apatch.ui.viewmodel.DshPluginViewModel
import me.bmax.apatch.util.WebDavUtils
import me.bmax.apatch.util.ui.LocalSnackbarHost
import me.bmax.apatch.util.ui.NavigationBarsSpacer

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
    var dshRemote by rememberSaveable { mutableStateOf(listOf<String>()) }
    // 是否把 sessions（会话记录）也导进去：体积能到几百 MB 且含敏感信息，默认关。
    var dshIncludeSessions by rememberSaveable { mutableStateOf(false) }
    // 导入时是否恢复备份包里的 sessions（会话记录）：默认关，与导出开关独立。
    var dshImportSessions by rememberSaveable { mutableStateOf(false) }
    // 冲突策略：落盘记住（BackupConfig），下次进来还是上次那档
    var importStrategy by rememberSaveable { mutableStateOf(BackupConfig.importStrategy) }

    // 云端备份 / 快照列表
    var cloudEntries by remember { mutableStateOf<List<WebDavUtils.RemoteEntry>>(emptyList()) }
    var cloudBusy by remember { mutableStateOf(false) }
    var cloudMessage by remember { mutableStateOf("") }
    var snapshots by remember { mutableStateOf<List<DshConfigBackup.Snapshot>>(emptyList()) }
    var snapshotBusy by remember { mutableStateOf(false) }
    var snapshotMessage by remember { mutableStateOf("") }

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

    // 插件状态：进页面就查一次，别等用户点了「导出」才报错。
    // null = 检测中；下面的 LaunchedEffect 只跑一次（备份页不是热路径）。
    var pluginReady by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var pluginDetail by rememberSaveable { mutableStateOf("") }
    val pluginViewModel = viewModel<DshPluginViewModel>()

    LaunchedEffect(Unit) {
        val st = withContext(Dispatchers.IO) { DshConfigBackup.status(context) }
        pluginReady = st.ready
        // 就绪时报版本，不就绪时报原因 —— status.error 已能区分
        // 「DSH 没运行」和「插件缺失」，别把两者混成一句
        pluginDetail = if (st.ready) {
            st.pluginVersion.ifEmpty { "—" }
        } else {
            st.error.ifEmpty { context.getString(R.string.dsh_backup_plugin_missing) }
        }
    }

    val notRunning = stringResource(R.string.dsh_backup_needs_running)
    val pluginMissing = stringResource(R.string.dsh_backup_plugin_missing)
    val exporting = stringResource(R.string.dsh_backup_exporting)
    val importing = stringResource(R.string.dsh_backup_importing)
    val openDirFailed = stringResource(R.string.dsh_backup_open_dir_failed)
    val webdavOk = stringResource(R.string.dsh_backup_webdav_ok)
    val webdavFailed = stringResource(R.string.dsh_backup_webdav_failed)
    val remoteEmpty = stringResource(R.string.dsh_backup_remote_empty)
    val importTarget = stringResource(R.string.dsh_backup_import)
    val cloudTarget = stringResource(R.string.dsh_bk_cloud_restore_title)
    val snapshotTarget = stringResource(R.string.dsh_bk_snapshot_title)
    val clipboard = LocalClipboardManager.current

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        dshBusy = true
        dshMessage = importing
        runVisible = true
        runTarget = importTarget
        runLines = emptyList()
        runRunning = true
        runFailed = false
        runNeedsRestart = false
        scope.launch(Dispatchers.IO) {
            val staged = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    DshConfigBackup.stage(context, input, "import-${System.currentTimeMillis()}.zip")
                }
            }.getOrNull()
            val text: String
            var restartNeeded = false
            var failed = false
            if (staged == null) {
                failed = true
                text = context.getString(R.string.dsh_plugin_local_read_failed)
            } else {
                val r = DshConfigBackup.import(
                    context, staged,
                    strategy = importStrategy,
                    password = dshPassword,
                    includeSessions = dshImportSessions,
                    // 阶段进度直接进对话框：不然用户只看到一个转圈，不知道在干什么
                    onLine = { line -> withContext(Dispatchers.Main) { runLines = runLines + line } },
                )
                staged.delete()
                text = if (r.detail.isBlank()) r.message else "${r.message}\n${r.detail}"
                restartNeeded = r.ok && r.needsRestart
                failed = !r.ok
                BackupLogManager.log("import strategy=$importStrategy ok=${r.ok} restart=$restartNeeded")
            }
            withContext(Dispatchers.Main) {
                dshMessage = text
                dshBusy = false
                runRunning = false
                runFailed = failed
                runNeedsRestart = restartNeeded
                runLines = runLines + text
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_category_backup), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
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
                    onDshExport = {
                        dshBusy = true
                        dshMessage = exporting
                        scope.launch(Dispatchers.IO) {
                            // 先确认插件在：DSH 没起来时直接报「需要先启动」，比让 HTTP 超时更清楚
                            val status = DshConfigBackup.status(context)
                            val text = if (!status.ready) {
                                if (status.error.isEmpty()) pluginMissing else notRunning
                            } else {
                                val r = DshConfigBackup.export(
                                    context,
                                    sections = DshConfigBackup.sections(dshIncludeSessions),
                                    password = dshPassword,
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
                    onDshImport = { importPicker.launch("*/*") },
                    dshRemoteBackups = dshRemote,
                    onDshListRemote = {
                        dshBusy = true
                        scope.launch(Dispatchers.IO) {
                            val list = DshConfigBackup.listRemoteBackups()
                            val lines = list.map { b ->
                                buildString {
                                    append(b.name)
                                    if (b.sizeBytes > 0) {
                                        append("  ").append(b.sizeBytes / 1024).append(" KB")
                                    }
                                    if (b.note.isNotEmpty()) append("  ").append(b.note)
                                }
                            }
                            withContext(Dispatchers.Main) {
                                dshRemote = lines
                                if (lines.isEmpty()) dshMessage = remoteEmpty
                                dshBusy = false
                            }
                        }
                    },
                    onDshOpenDir = {
                        val opened = DshConfigBackup.openBackupDir(context)
                        if (!opened) dshMessage = openDirFailed
                    },
                    importStrategy = importStrategy,
                    onImportStrategyChange = {
                        importStrategy = it
                        BackupConfig.importStrategy = it
                        BackupConfig.save(context)
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
                        // 云端那条只是「源」：下载到暂存后走与本地导入同一条管道，
                        // 策略/密码/会话选项完全一致，不另起一套逻辑。
                        dshBusy = true
                        runVisible = true
                        runTarget = cloudTarget
                        runLines = listOf(context.getString(R.string.dsh_bk_cloud_downloading, entry.name))
                        runRunning = true
                        runFailed = false
                        runNeedsRestart = false
                        cloudMessage = ""
                        scope.launch(Dispatchers.IO) {
                            val dest = File(
                                File(context.cacheDir, "config-import").apply { mkdirs() },
                                entry.name.ifBlank { "cloud-backup.zip" },
                            )
                            val dl = WebDavUtils.downloadTo(
                                baseUrl = BackupConfig.webdavUrl,
                                user = BackupConfig.webdavUsername,
                                pass = BackupConfig.webdavPassword,
                                remotePath = entry.path,
                                dest = dest,
                            )
                            var restartNeeded = false
                            val text: String
                            if (dl.isFailure) {
                                text = context.getString(
                                    R.string.dsh_bk_cloud_download_failed,
                                    dl.exceptionOrNull()?.message ?: "",
                                )
                            } else {
                                val r = DshConfigBackup.import(
                                    context, dest,
                                    strategy = importStrategy,
                                    password = dshPassword,
                                    includeSessions = dshImportSessions,
                                    onLine = { line -> withContext(Dispatchers.Main) { runLines = runLines + line } },
                                )
                                dest.delete()
                                text = if (r.detail.isBlank()) r.message else "${r.message}\n${r.detail}"
                                restartNeeded = r.ok && r.needsRestart
                            }
                            withContext(Dispatchers.Main) {
                                cloudMessage = text
                                dshBusy = false
                                runRunning = false
                                runFailed = dl.isFailure
                                runNeedsRestart = restartNeeded
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
                            withContext(Dispatchers.Main) {
                                if (!p.ok) {
                                    pendingSnapshot = null
                                    snapshotMessage = context.getString(
                                        R.string.dsh_bk_snapshot_preview_failed,
                                        p.message,
                                    )
                                } else {
                                    pendingActions = p.actions
                                }
                            }
                        }
                    },
                    includeSessions = dshIncludeSessions,
                    onIncludeSessionsChange = { dshIncludeSessions = it },
                    importSessions = dshImportSessions,
                    onImportSessionsChange = { dshImportSessions = it },
                    pluginReady = pluginReady,
                    pluginDetail = pluginDetail,
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

    // 快照恢复确认：把预览出来的动作数摆在这里，用户点「恢复」才真的写盘
    pendingSnapshot?.let { snap ->
        if (pendingActions >= 0) {
            AlertDialog(
                onDismissRequest = { pendingSnapshot = null },
                title = { Text(stringResource(R.string.dsh_bk_snapshot_confirm_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.dsh_bk_snapshot_confirm_body,
                            snap.id,
                            pendingActions,
                        ),
                    )
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
                            BackupLogManager.log("snapshot restore ${snap.id} ok=${r.ok}")
                            withContext(Dispatchers.Main) {
                                snapshotBusy = false
                                snapshotMessage = if (r.detail.isBlank()) r.message else "${r.message}\n${r.detail}"
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
