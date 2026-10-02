package me.bmax.apatch.ui.component

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshFileHandoff
import me.bmax.apatch.dsh.DshPhase
import me.bmax.apatch.dsh.DshRuntime
import me.bmax.apatch.ui.DshWebUi
import me.bmax.apatch.util.ui.showToast

/**
 * 「分享/以…打开 → 用途选择 → 交给 DSH 处理」的**弹窗**（方案 A，全程不离开本页）。
 *
 * 之前「交给 DSH」是跳到独立页面、选完工作区又拉起 Web UI 把 App 挤到后台——很突兀。
 * 改成单个多步弹窗：
 *   1) 用途选择（交给 DSH / 恢复备份 / 导入主题）；
 *   2) 交给 DSH：没跑先启动，纯读注册表列工作区（含会话数），选一个 → 把文件复制进去
 *      → 弹「复制成功」+ 提示词进剪贴板；
 *   3) 完成页：把「是否打开 Web 界面」交回用户（点才跳，不再强行把 App 挤到后台）。
 *
 * 恢复备份 / 导入主题仍交回宿主（[onBackup] / [onTheme]）——它们本来就各有恰当的落点
 * （恢复向导页 / 主题导入确认框）。
 */
@Composable
fun DshFileHandoffDialog(
    uri: Uri,
    fileName: String,
    onDismiss: () -> Unit,
    onTheme: (Uri) -> Unit,
    onBackup: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val state by DshRuntime.state.collectAsStateWithLifecycle()

    // CHOOSER = 用途选择；DSH = 选工作区；DONE = 复制完成
    var step by remember { mutableStateOf("CHOOSER") }
    var workspaces by remember { mutableStateOf<List<DshFileHandoff.Workspace>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var requestedStart by remember { mutableStateOf(false) }
    var doneWorkspace by remember { mutableStateOf("") }

    // 进入 DSH 步后：没跑就启动一次（ERROR 不重试），跑起来读一次工作区。
    LaunchedEffect(step, state.phase) {
        if (step != "DSH") return@LaunchedEffect
        when (state.phase) {
            DshPhase.RUNNING ->
                if (workspaces == null) {
                    workspaces = withContext(Dispatchers.IO) { DshFileHandoff.listWorkspaces(context) }
                }
            DshPhase.NOT_READY ->
                if (!requestedStart) {
                    requestedStart = true
                    DshRuntime.bootstrap()
                }
            else -> Unit
        }
    }

    fun pick(ws: DshFileHandoff.Workspace) {
        if (busy) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        DshFileHandoff.copyInto(context, input, ws.guestPath, fileName).getOrThrow()
                    } ?: throw java.io.IOException("open failed")
                }
            }
            busy = false
            result.onSuccess { guestFilePath ->
                showToast(context, R.string.dsh_handoff_copied)
                clipboard.setText(
                    AnnotatedString(context.getString(R.string.dsh_handoff_prompt, guestFilePath))
                )
                doneWorkspace = ws.title
                step = "DONE"
            }.onFailure {
                showToast(context, context.getString(R.string.dsh_handoff_copy_failed, it.message ?: ""))
            }
        }
    }

    fun openWeb() {
        val webUrl = DshRuntime.webUrl()
        when (DshWebUi.mode(context)) {
            DshWebUi.MODE_BROWSER -> DshWebUi.openExternal(context, webUrl)
            else -> DshWebUi.openInApp(context, webUrl)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                when (step) {
                    "CHOOSER" -> {
                        DialogHeader(
                            title = stringResource(R.string.dsh_share_chooser_title),
                            subtitle = stringResource(R.string.dsh_share_chooser_message, fileName),
                        )
                        Spacer(Modifier.height(12.dp))
                        OptionRow(Icons.Outlined.Terminal, stringResource(R.string.dsh_share_use_dsh)) {
                            step = "DSH"
                        }
                        OptionRow(Icons.Outlined.SettingsBackupRestore, stringResource(R.string.dsh_share_use_backup)) {
                            onBackup(uri); onDismiss()
                        }
                        OptionRow(Icons.Outlined.Palette, stringResource(R.string.dsh_share_use_theme)) {
                            onTheme(uri); onDismiss()
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
                        }
                    }

                    "DSH" -> {
                        DialogHeader(
                            title = stringResource(R.string.dsh_handoff_title),
                            subtitle = stringResource(R.string.dsh_handoff_file_label, fileName),
                        )
                        Spacer(Modifier.height(8.dp))
                        val list = workspaces
                        when {
                            state.phase == DshPhase.ERROR && list == null ->
                                Text(
                                    text = state.message.ifBlank { stringResource(R.string.dsh_handoff_starting) },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(vertical = 24.dp),
                                )

                            list == null ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Text(stringResource(R.string.dsh_handoff_starting))
                                }

                            else -> {
                                Text(
                                    text = stringResource(R.string.dsh_handoff_pick),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(4.dp))
                                if (list.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.dsh_handoff_empty),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 12.dp),
                                    )
                                }
                                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                                    items(list) { ws -> WorkspaceRow(ws, enabled = !busy) { pick(ws) } }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
                        }
                    }

                    else -> { // DONE
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = stringResource(
                                    R.string.dsh_handoff_done_message,
                                    doneWorkspace.ifBlank { stringResource(R.string.dsh_handoff_default_workspace) },
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dsh_bk_wiz_stage_done)) }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { openWeb(); onDismiss() }) {
                                Text(stringResource(R.string.dsh_open_webui))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogHeader(title: String, subtitle: String) {
    Text(text = title, style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun OptionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(text = label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun WorkspaceRow(ws: DshFileHandoff.Workspace, enabled: Boolean, onClick: () -> Unit) {
    val title = ws.title.ifBlank { stringResource(R.string.dsh_handoff_default_workspace) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(2.dp))
        Text(
            text = ws.guestPath,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.dsh_handoff_sessions_count, ws.sessionCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
