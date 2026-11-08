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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Job
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
    val maxDialogHeight = (LocalConfiguration.current.screenHeightDp.dp - 48.dp).coerceAtLeast(320.dp)
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf<AppUpdater.Phase>(AppUpdater.Phase.Idle) }
    var results by remember { mutableStateOf<List<DshSource.SpeedResult>>(emptyList()) }
                // 下载一旦开始：测速立即作废、列表顺序冻结（见 DshSource.cancelSpeedTest）
                var downloadStarted by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<String?>(null) }
    // 用户是否**自己点过**渠道。
    //
    // 这个标志存在的唯一理由：把「默认选中第一项」和「用户的选择」分开。
    // 没点过 → 选中项跟住排序后的第一项（始终停在最上面，符合直觉）；
    // 点过 → 任何自动重排都不许动它（否则用户刚选好的渠道会被测速结果挤走）。
    var userPicked by remember { mutableStateOf(false) }
    var needPermission by remember { mutableStateOf(false) }
    // 测速是否在跑（与下载/校验分开）：测速期间只是「结果在陆续补」，
    // 不该把渠道选择与「开始下载」也一起锁死——用户拿到任一条可达结果就能选、能下。
    var testing by remember { mutableStateOf(false) }
    var speedJob by remember { mutableStateOf<Job?>(null) }

    // 「硬忙」＝下载 / 校验：这两步才真正独占、要锁住所有按钮与关闭。
    // 测速**不算**硬忙：它逐条出结果，边测边可选可下（点了下载就取消剩余测速）。
    val busy = phase is AppUpdater.Phase.Downloading ||
        phase is AppUpdater.Phase.Verifying

    BasicAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !busy,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier.width(340.dp).heightIn(max = maxDialogHeight),
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = status.latestTag,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        // 测试版必须有醒目标记：两种更新提示长得一样，而按下「更新」之后
                        // 装上的东西风险完全不同。不标出来等于偷偷把人推上测试通道。
                        if (status.isPrerelease) {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                            ) {
                                Text(
                                    text = stringResource(R.string.update_beta_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }

                // 对话框整体限高；标题留在顶部、按钮留在底部，中间内容统一滚动。
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (status?.isPrerelease == true) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.update_beta_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.update_available_message),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    // 更新内容（release body）。**不要**给它限高：这里是长文本（几段说明），
                    // 而 Text 一旦拿到有界高度又没有 overflow 配置，多出来的行是**静默裁掉**
                    // 的 —— 用户看到的正是「更新内容显示不全」，而且对话框里没有任何滚动条
                    // 提示他下面还有东西。外层 Column 已经是「整体限高 + verticalScroll」，
                    // 正文自然跟着滚，所以这里只需要占满宽度。
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
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                // ── 测速结果 ──
                if (results.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    for ((i, r) in results.withIndex()) {
                        val label = stringResource(sourceLabelRes(r.source))
                        val latency = r.latencyMs
                        val unreachable = latency == null
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = chosen == r.source,
                                onClick = { chosen = r.source; userPicked = true },
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
                                            latency!!,
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

                        // 还没测速：先测速再让用户选渠道（竞速通道关掉时跳过测速，直接直连下载）
                        status?.canInstallInApp == true && results.isEmpty() -> Button(
                            onClick = {
                                if (!AppUpdater.raceEnabled()) {
                                    // 竞速关闭：不测速、不给渠道可选，直接按直连下载
                                    chosen = DshSource.SOURCE_GITHUB
                                    scope.launch {
                                        AppUpdater.downloadAndVerify(
                                            context, status, DshSource.SOURCE_GITHUB,
                                        ) { phase = it }
                                    }
                                    return@Button
                                }
                                testing = true
                                phase = AppUpdater.Phase.Testing
                                // 逐条回报：先满屏出延迟，最快测完吞吐的那条排到最前，其余陆续补上。
                                // 展示顺序＝「已测出吞吐的按估算耗时最快在前 → 只有延迟的按延迟 → 不可达垫底」，
                                // 并把选择停在当前最快的可达渠道上——用户不必等全部测完，拿到任一条就能选、能下。
                                speedJob = scope.launch {
                                    try {
                                        // 列表继续按「估算耗时最快在前」排序 —— 用户等测速时就该先看到最快的那条。
                                        //
                                        // 但要区分「用户选过没有」：
                                        //  - **没选过**：选中项跟住排序后的第一项。排序一变它就跟着变，
                                        //    始终停在最上面那一行 —— 这正是用户期望的默认行为。
                                        //  - **选过**：用户的选择优先，排序再怎么变也不动它。
                                        //
                                        // 之前没有这个区分：每次 partial 回调都重排，而 `chosen == null`
                                        // 的判断又只对「一次都没选」成立 —— 一旦第一次回调把 chosen 填上，
                                        // 后续重排就不再更新它，于是选中项停在某个**已经不在第一行**的位置，
                                        // 视觉上就是「自动往下滚到已固定的那个」。
                                        val r = AppUpdater.speedTest { partial ->
                                            // 下载态下不再覆盖：用户点过下载之后，这张表就是
                                            // "当时选定的结论"，后续回调（哪怕线程还在跑）一律丢弃，
                                            // 否则顺序会当着用户的面跳。
                                            if (!downloadStarted) {
                                                val ordered = partial.sortedBy { rankKey(it) }
                                                results = ordered
                                                if (!userPicked) chosen = ordered.firstOrNull()?.source
                                            }
                                        }
                                        // 同上：终态也不覆盖已冻结的结论。
                                        if (!downloadStarted) {
                                            val orderedFinal = r.sortedBy { rankKey(it) }
                                            results = orderedFinal
                                            if (!userPicked) chosen = orderedFinal.firstOrNull()?.source
                                            phase = if (results.none { it.reachable }) {
                                                AppUpdater.Phase.Failed(
                                                    context.getString(R.string.update_all_channels_down)
                                                )
                                            } else {
                                                AppUpdater.Phase.Idle
                                            }
                                        }
                                    } finally {
                                        testing = false
                                    }
                                }
                            },
                            enabled = !busy && !testing,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(
                                    if (AppUpdater.raceEnabled()) R.string.update_in_app
                                    else R.string.update_download_now
                                )
                            )
                        }

                        // 已选渠道：开始下载（测速可能还在跑——先取消剩余测速，用当前选中的线路直接下）
                        status?.canInstallInApp == true -> Button(
                            onClick = {
                                val src = chosen ?: return@Button
                                // 两件事都要做，缺一不可：
                                //  1) cancelSpeedTest()：让**已经在阻塞读 socket** 的探测线程
                                //     在下一处检查点自行放弃（Job.cancel 打不断它）；
                                //  2) downloadStarted：界面不再接受任何后续回调，顺序就此冻结。
                                downloadStarted = true
                                DshSource.cancelSpeedTest()
                                speedJob?.cancel()
                                testing = false
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

/**
 * 测速结果的展示排序键：越小越靠前。
 *
 * 让「最快测完吞吐的那条完整结果」排到最前，其余按测完程度依次补上：
 * ① 已测出吞吐（reachable 且 speedKBps>0）——按估算耗时 estimatedMs 升序；
 * ② 只测出延迟、吞吐还没回来——排在已完成之后，按延迟升序；
 * ③ 不可达——垫底。
 */
private fun rankKey(r: me.bmax.apatch.dsh.DshSource.SpeedResult): Long = when {
    !r.reachable -> Long.MAX_VALUE
    r.speedKBps > 0.0 -> r.estimatedMs
    else -> 4_000_000_000L + (r.latencyMs ?: 0L)
}
