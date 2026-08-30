package me.bmax.apatch.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshDownloader
import me.bmax.apatch.dsh.DshSource
import me.bmax.apatch.ui.screen.settings.sourceLabelRes
import me.bmax.apatch.util.AppUpdater
import me.bmax.apatch.util.UpdateChecker

/**
 * 更新对话框：显示版本与更新内容，可选应用内更新（多渠道测速）或去浏览器。
 *
 * 为什么把测速摆在用户面前而不是自动选：这三条渠道（GitHub 直连 / 两个 gh-proxy）
 * 在国内网络下的表现差异极大且随时段变化，自动选优会选错；把延迟和速度摊开让用户
 * 一眼看到差距，比一个「智能」黑箱更有用。默认仍高亮推荐项。
 *
 * 下载中不允许关闭（关掉会让协程在后台继续写文件却没人收结果）；空闲/失败态必须
 * 可关，否则用户被困在对话框里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateDialog(
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
    /** 检查结果；null 表示调用方还没拿到（退化成只有「去浏览器」的旧行为）。 */
    status: UpdateChecker.Status? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf<AppUpdater.Phase>(AppUpdater.Phase.Idle) }
    var results by remember { mutableStateOf<List<DshSource.SpeedResult>>(emptyList()) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var needPermission by remember { mutableStateOf(false) }

    val busy = phase is AppUpdater.Phase.Testing ||
        phase is AppUpdater.Phase.Downloading ||
        phase is AppUpdater.Phase.Verifying

    BasicAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !busy,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier.width(340.dp).wrapContentHeight(),
            shape = RoundedCornerShape(20.dp),
            tonalElevation = AlertDialogDefaults.TonalElevation,
            color = AlertDialogDefaults.containerColor,
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.update_available_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                if (status != null && status.latestTag.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = status.latestTag,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.update_available_message),
                    style = MaterialTheme.typography.bodyMedium,
                )

                // 更新内容（release body）。限高可滚动：notes 可能很长。
                if (status != null && status.notes.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.update_notes_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = status.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }

                // ── 测速结果 ──
                if (results.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    for ((i, r) in results.withIndex()) {
                        val label = stringResource(sourceLabelRes(r.source))
                        val unreachable = r.latencyMs >= Long.MAX_VALUE / 4
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = chosen == r.source,
                                onClick = { chosen = r.source },
                                enabled = !busy && !unreachable,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = if (i == 0 && !unreachable) {
                                        stringResource(R.string.update_channel_recommended, label)
                                    } else {
                                        label
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = if (unreachable) {
                                        stringResource(R.string.update_channel_unreachable)
                                    } else {
                                        stringResource(
                                            R.string.update_channel_result,
                                            r.latencyMs,
                                            if (r.speedKBps > 0) {
                                                DshDownloader.formatSpeed((r.speedKBps * 1024).toLong())
                                            } else {
                                                "—"
                                            },
                                        )
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // ── 阶段提示 ──
                Spacer(Modifier.height(12.dp))
                when (val p = phase) {
                    is AppUpdater.Phase.Testing -> {
                        Text(
                            text = stringResource(R.string.update_speed_testing),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    is AppUpdater.Phase.Downloading -> {
                        Text(
                            text = stringResource(R.string.update_downloading, p.percent, p.speed),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { p.percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is AppUpdater.Phase.Verifying -> {
                        Text(
                            text = stringResource(R.string.update_verifying),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    is AppUpdater.Phase.Ready -> Text(
                        text = stringResource(R.string.update_ready),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    is AppUpdater.Phase.Failed -> Text(
                        text = p.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    AppUpdater.Phase.Idle -> Unit
                }

                if (needPermission) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.update_install_permission_needed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(16.dp))
                Column(Modifier.fillMaxWidth()) {
                    val ready = phase as? AppUpdater.Phase.Ready
                    when {
                        // 校验通过：只剩安装
                        ready != null -> Button(
                            onClick = {
                                if (!AppUpdater.canInstall(context)) {
                                    needPermission = true
                                    AppUpdater.requestInstallPermission(context)
                                } else {
                                    needPermission = false
                                    AppUpdater.install(context, ready.file)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.update_install)) }

                        // 还没测速：先测速再让用户选渠道
                        status?.canInstallInApp == true && results.isEmpty() -> Button(
                            onClick = {
                                scope.launch {
                                    phase = AppUpdater.Phase.Testing
                                    val r = AppUpdater.speedTest()
                                    results = r
                                    chosen = r.firstOrNull { it.latencyMs < Long.MAX_VALUE / 4 }?.source
                                    phase = if (chosen == null) {
                                        AppUpdater.Phase.Failed(
                                            context.getString(R.string.update_all_channels_down)
                                        )
                                    } else {
                                        AppUpdater.Phase.Idle
                                    }
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.update_in_app)) }

                        // 已选渠道：开始下载
                        status?.canInstallInApp == true -> Button(
                            onClick = {
                                val src = chosen ?: return@Button
                                scope.launch {
                                    AppUpdater.downloadAndVerify(context, status, src) { phase = it }
                                }
                            },
                            enabled = !busy && chosen != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.update_download_now)) }

                        else -> Unit
                    }

                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss, enabled = !busy) {
                            Text(stringResource(R.string.update_close))
                        }
                        TextButton(onClick = onUpdate, enabled = !busy) {
                            Text(stringResource(R.string.update_open_browser))
                        }
                    }
                }
            }
        }
    }

    // 从「需要授权」返回后自动复查一次，省得用户再点一遍
    LaunchedEffect(needPermission) {
        if (needPermission && AppUpdater.canInstall(context)) needPermission = false
    }
}
