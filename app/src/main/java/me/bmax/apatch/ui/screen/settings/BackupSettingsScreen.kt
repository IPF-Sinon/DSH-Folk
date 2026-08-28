package me.bmax.apatch.ui.screen.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.BuildConfig
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshConfigBackup
import me.bmax.apatch.ui.theme.BackgroundConfig
import me.bmax.apatch.ui.theme.BackupConfig
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

    val notRunning = stringResource(R.string.dsh_backup_needs_running)
    val pluginMissing = stringResource(R.string.dsh_backup_plugin_missing)
    val exporting = stringResource(R.string.dsh_backup_exporting)
    val importing = stringResource(R.string.dsh_backup_importing)
    val openDirFailed = stringResource(R.string.dsh_backup_open_dir_failed)
    val webdavOk = stringResource(R.string.dsh_backup_webdav_ok)
    val webdavFailed = stringResource(R.string.dsh_backup_webdav_failed)
    val remoteEmpty = stringResource(R.string.dsh_backup_remote_empty)

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        dshBusy = true
        dshMessage = importing
        scope.launch(Dispatchers.IO) {
            val staged = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    DshConfigBackup.stage(context, input, "import-${System.currentTimeMillis()}.zip")
                }
            }.getOrNull()
            val text = if (staged == null) {
                context.getString(R.string.dsh_plugin_local_read_failed)
            } else {
                val r = DshConfigBackup.import(context, staged, password = dshPassword)
                staged.delete()
                if (r.detail.isBlank()) r.message else "${r.message}\n${r.detail}"
            }
            withContext(Dispatchers.Main) {
                dshMessage = text
                dshBusy = false
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
                                val r = DshConfigBackup.export(context, password = dshPassword)
                                if (!r.ok) r.message else {
                                    val local = "${r.message}\n${r.file?.absolutePath ?: ""}"
                                    // 开了云备份就顺手推一份到 WebDAV，失败只追加一行说明，不影响本地备份
                                    val zip = r.file
                                    if (BackupConfig.isBackupEnabled && zip != null && BackupConfig.webdavUrl.isNotBlank()) {
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
                        val dir = DshConfigBackup.backupDir(context)
                        dir.mkdirs()
                        val opened = runCatching {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${BuildConfig.APPLICATION_ID}.fileprovider",
                                dir,
                            )
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(uri, "resource/folder")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                            true
                        }.getOrDefault(false)
                        if (!opened) dshMessage = openDirFailed
                    },
                    flat = flat,
                    highlightKey = highlightKey,
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
            item { NavigationBarsSpacer() }
        }
    }
}
