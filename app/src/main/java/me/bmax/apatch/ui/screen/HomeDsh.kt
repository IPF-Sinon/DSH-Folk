package me.bmax.apatch.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshPhase
import me.bmax.apatch.dsh.DshRuntime
import me.bmax.apatch.dsh.HarnessService
import me.bmax.apatch.dsh.PermissionManager
import me.bmax.apatch.ui.component.copyInfoToClipboard
import me.bmax.apatch.ui.theme.BackgroundConfig
import me.bmax.apatch.util.ui.HomeBottomSpacer
import me.bmax.apatch.util.ui.showToast

/**
 * DSH-Folk 默认首页。
 *
 * 复用 FolkPatch 的卡片视觉语言（渐变 Hero + 小信息卡 + surfaceColorAtElevation，
 * 全部走 BackgroundConfig 的壁纸/透明度设置，所以主题商店与 theme.json 依旧生效），
 * 但内容换成 DSH 运行时：
 *
 * - Hero：DeepSeek Harness 大标题 + 运行阶段 + 进度 + 操作按钮；
 * - 右侧两张小卡：运行方式（proot/proroot）、权限（root/shizuku/adb）；
 * - 信息面板：启动日志 + 右上角复制按钮。
 */
@Composable
fun HomeScreenDsh(
    innerPadding: PaddingValues,
    navigator: DestinationsNavigator,
) {
    val context = LocalContext.current
    val state by DshRuntime.state.collectAsStateWithLifecycle()
    val perm by PermissionManager.status.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        DshRuntime.attach(context.applicationContext)
        withContext(Dispatchers.IO) { PermissionManager.refresh(context.applicationContext) }
    }

    Column(
        modifier = Modifier
            .padding(innerPadding)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(0.dp))

        DshHeroCard(
            phase = state.phase,
            message = state.message,
            progress = state.progress,
            version = state.runtimeVersion,
            webUrl = state.webUrl,
            onStart = { HarnessService.start(context) },
            onStop = { HarnessService.stop(context) },
            onRestart = { DshRuntime.restart() },
            onOpenWeb = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.webUrl)))
                }.onFailure { showToast(context, "没有可用的浏览器") }
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DshSmallCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.Layers,
                title = stringResource(R.string.dsh_run_mode),
                value = if (DshRuntime.runtimeId() == "proroot") "proroot" else "proot",
                subtitle = stringResource(
                    if (DshRuntime.runtimeId() == "proroot") R.string.dsh_mode_proroot_desc
                    else R.string.dsh_mode_proot_desc
                ),
                onClick = { navigator.navigate(com.ramcosta.composedestinations.generated.destinations.FunctionSettingsScreenDestination) },
            )
            DshSmallCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.Security,
                title = stringResource(R.string.dsh_permission),
                value = perm.label,
                subtitle = permHint(perm),
                onClick = { navigator.navigate(com.ramcosta.composedestinations.generated.destinations.FunctionSettingsScreenDestination) },
            )
        }

        DshLogCard()

        HomeBottomSpacer()
    }
}

private fun permHint(s: PermissionManager.Status): String = when (s.channel) {
    PermissionManager.Channel.ROOT -> "已获得 root，完整能力"
    PermissionManager.Channel.SHIZUKU -> "shell 权限（uid 2000）"
    PermissionManager.Channel.ADB -> "已配对，shell 权限"
    PermissionManager.Channel.NONE ->
        if (s.shizukuRunning) "Shizuku 在运行但未授权" else "点此配置提权通道"
}

/** Hero 卡：DeepSeek Harness 大标题 + 阶段 + 进度 + 操作。 */
@Composable
private fun DshHeroCard(
    phase: DshPhase,
    message: String,
    progress: Float,
    version: String?,
    webUrl: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onOpenWeb: () -> Unit,
) {
    val running = phase == DshPhase.RUNNING
    val busy = phase == DshPhase.DOWNLOADING || phase == DshPhase.EXTRACTING || phase == DshPhase.STARTING
    val error = phase == DshPhase.ERROR

    // 运行中呼吸动画，与 FolkPatch HeroStatusCard 一致的观感
    val transition = rememberInfiniteTransition(label = "dshBreathing")
    val breath by transition.animateFloat(
        initialValue = 0.65f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "dshBreathAlpha",
    )

    val container by animateColorAsState(
        targetValue = when {
            running -> MaterialTheme.colorScheme.primary
            busy -> MaterialTheme.colorScheme.secondary
            error -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = tween(500),
        label = "dshContainer",
    )
    val content by animateColorAsState(
        targetValue = when {
            running -> MaterialTheme.colorScheme.onPrimary
            busy -> MaterialTheme.colorScheme.onSecondary
            error -> MaterialTheme.colorScheme.onErrorContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(500),
        label = "dshContent",
    )

    val bgOpacity = if (BackgroundConfig.isCustomBackgroundEnabled) BackgroundConfig.customBackgroundOpacity else 1f
    val gradient = Brush.linearGradient(
        listOf(
            container.copy(alpha = (if (running) breath else 1f) * bgOpacity),
            container.copy(alpha = 0.8f * bgOpacity),
        )
    )

    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent, contentColor = content),
    ) {
        Box(Modifier.fillMaxWidth().background(gradient)) {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text(
                    text = stringResource(R.string.dsh_app_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = content,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = phaseLabel(phase) + (version?.let { " · v$it" } ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                    color = content.copy(alpha = 0.9f),
                )
                if (message.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = content.copy(alpha = 0.85f),
                        maxLines = 3,
                    )
                }
                if (busy && progress > 0f) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = content,
                        trackColor = content.copy(alpha = 0.25f),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (running) {
                        Button(
                            onClick = onOpenWeb,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = content.copy(alpha = 0.18f),
                                contentColor = content,
                            ),
                        ) {
                            Icon(Icons.Outlined.OpenInBrowser, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.dsh_open_webui))
                        }
                        OutlinedButton(onClick = onRestart) {
                            Icon(Icons.Outlined.RestartAlt, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.dsh_restart))
                        }
                        OutlinedButton(onClick = onStop) {
                            Icon(Icons.Outlined.Stop, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.dsh_stop))
                        }
                    } else {
                        Button(
                            onClick = onStart,
                            enabled = !busy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = content.copy(alpha = 0.18f),
                                contentColor = content,
                            ),
                        ) {
                            Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.dsh_start))
                        }
                    }
                }
                if (running) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = webUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = content.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

/** 小信息卡（沿用 FolkPatch SmallInfoCard 的形制，多一行副标题）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DshSmallCard(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val containerColor = if (BackgroundConfig.isCustomBackgroundEnabled) {
        MaterialTheme.colorScheme.surface.copy(alpha = BackgroundConfig.customBackgroundOpacity)
    } else {
        MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
    }
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

/** 启动日志卡：等宽字体滚动 + 右上角复制按钮。 */
@Composable
private fun DshLogCard() {
    val context = LocalContext.current
    var log by remember { mutableStateOf("") }

    // 轮询而非监听：LogStore 的 tail 只读内存环形缓冲，开销极低
    LaunchedEffect(Unit) {
        while (true) {
            log = withContext(Dispatchers.IO) { DshRuntime.tailLog(200) }
            delay(1_000)
        }
    }

    val containerColor = if (BackgroundConfig.isCustomBackgroundEnabled) {
        MaterialTheme.colorScheme.surface.copy(alpha = BackgroundConfig.customBackgroundOpacity)
    } else {
        MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Article, null, Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.dsh_boot_log),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    copyInfoToClipboard(
                        context,
                        context.getString(R.string.dsh_boot_log),
                        log.ifEmpty { "(empty)" },
                    )
                    showToast(context, R.string.dsh_log_copied)
                }) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.dsh_copy_log),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = log.ifEmpty { "尚无日志。点击「启动」开始安装并运行 DeepSeek Harness。" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun phaseLabel(phase: DshPhase): String = stringResource(
    when (phase) {
        DshPhase.NOT_READY -> R.string.dsh_phase_not_ready
        DshPhase.DOWNLOADING -> R.string.dsh_phase_downloading
        DshPhase.EXTRACTING -> R.string.dsh_phase_extracting
        DshPhase.STARTING -> R.string.dsh_phase_starting
        DshPhase.RUNNING -> R.string.dsh_phase_running
        DshPhase.ERROR -> R.string.dsh_phase_error
    }
)
