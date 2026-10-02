package me.bmax.apatch.ui.screen.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import java.io.File
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
 * 「分享/以…打开 → 交给 DSH 处理」的落地页（方案 A）。
 *
 * 进来时若 DSH 没跑就启动它并等到 RUNNING，再读工作区注册表（[DshFileHandoff.listWorkspaces]，
 * 纯读 `workspace.json`，不碰 dsh 的会话 RPC）。用户选一个工作区后：把暂存的文件复制进该
 * 工作区目录 → 弹「复制成功」→ 把提示词放进剪贴板 → 打开 Web UI（会话树/选会话都在 Web UI 里做）。
 *
 * [stagedPath] 是分享进来的文件在 App 可控缓存里的副本（Uri 不能跨进程久留，先落盘）；
 * 离开这一页时清理它。[fileName] 是展示与落盘用的原始文件名。
 */
@Destination<RootGraph>
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DshFileHandoffScreen(
    navigator: DestinationsNavigator,
    stagedPath: String,
    fileName: String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val state by DshRuntime.state.collectAsStateWithLifecycle()

    var workspaces by remember { mutableStateOf<List<DshFileHandoff.Workspace>?>(null) }
    var busy by remember { mutableStateOf(false) }
    // 只主动启动一次：避免 dsh 进入 ERROR 后 LaunchedEffect 反复重启，陷入循环。
    var requestedStart by remember { mutableStateOf(false) }

    // 没跑就启动一次；跑起来后读一次工作区。ERROR 态不重试，交由 UI 提示。
    androidx.compose.runtime.LaunchedEffect(state.phase) {
        when (state.phase) {
            DshPhase.RUNNING -> {
                if (workspaces == null) {
                    workspaces = withContext(Dispatchers.IO) { DshFileHandoff.listWorkspaces(context) }
                }
            }
            DshPhase.DOWNLOADING, DshPhase.EXTRACTING, DshPhase.STARTING, DshPhase.ERROR -> Unit
            DshPhase.NOT_READY -> {
                if (!requestedStart) {
                    requestedStart = true
                    DshRuntime.bootstrap()
                }
            }
        }
    }

    fun cleanUp() {
        val staged = File(stagedPath)
        if (staged.parentFile?.name == "dsh-handoff") staged.delete()
    }

    fun leave() {
        cleanUp()
        navigator.popBackStack()
    }

    BackHandler { leave() }

    fun pick(ws: DshFileHandoff.Workspace) {
        if (busy) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                DshFileHandoff.copyInto(context, File(stagedPath), ws.guestPath, fileName)
            }
            busy = false
            result.onSuccess { guestFilePath ->
                showToast(context, R.string.dsh_handoff_copied)
                clipboard.setText(
                    AnnotatedString(context.getString(R.string.dsh_handoff_prompt, guestFilePath))
                )
                cleanUp()
                val webUrl = DshRuntime.webUrl()
                when (DshWebUi.mode(context)) {
                    DshWebUi.MODE_BROWSER -> DshWebUi.openExternal(context, webUrl)
                    else -> DshWebUi.openInApp(context, webUrl)
                }
                navigator.popBackStack()
            }.onFailure {
                showToast(
                    context,
                    context.getString(R.string.dsh_handoff_copy_failed, it.message ?: "")
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dsh_handoff_title)) },
                navigationIcon = {
                    IconButton(onClick = { leave() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Text(
                text = stringResource(R.string.dsh_handoff_file_label, fileName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )

            val list = workspaces
            when {
                state.phase == DshPhase.ERROR && list == null -> Text(
                    text = state.message.ifBlank { stringResource(R.string.dsh_handoff_starting) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(20.dp),
                )

                list == null -> CenteredProgress(
                    label = if (state.phase == DshPhase.RUNNING)
                        stringResource(R.string.dsh_handoff_loading)
                    else
                        stringResource(R.string.dsh_handoff_starting),
                )

                else -> {
                    Text(
                        text = stringResource(R.string.dsh_handoff_pick),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                    if (list.isEmpty()) {
                        Text(
                            text = stringResource(R.string.dsh_handoff_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(list) { ws ->
                            WorkspaceRow(ws = ws, enabled = !busy, onClick = { pick(ws) })
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceRow(
    ws: DshFileHandoff.Workspace,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val title = ws.title.ifBlank { stringResource(R.string.dsh_handoff_default_workspace) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(2.dp))
        Text(
            text = ws.guestPath,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.dsh_handoff_sessions_count, ws.sessionCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CenteredProgress(label: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
