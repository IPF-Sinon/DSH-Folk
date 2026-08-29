package me.bmax.apatch.ui.screen

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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ramcosta.composedestinations.generated.destinations.FunctionSettingsScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshPhase
import me.bmax.apatch.dsh.DshRuntime
import me.bmax.apatch.dsh.HarnessService
import me.bmax.apatch.dsh.PermissionManager
import me.bmax.apatch.ui.theme.BackgroundConfig
import me.bmax.apatch.util.ui.HomeBottomSpacer

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
    // openWeb 分流住在 DshHomeUiState 上（state 是 DshRuntime.State，没有这个方法）
    val uiState = LocalDshHomeState.current

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
            onOpenWeb = { uiState.openWeb() },
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
                onClick = { navigator.navigate(FunctionSettingsScreenDestination(null)) },
            )
            DshSmallCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.Security,
                title = stringResource(R.string.dsh_permission),
                value = perm.label(context),
                subtitle = permHint(perm),
                onClick = { navigator.navigate(FunctionSettingsScreenDestination(null)) },
            )
        }

        DshLogCard()

        HomeBottomSpacer()
    }
}

@Composable
private fun permHint(s: PermissionManager.Status): String = when (s.channel) {
    PermissionManager.Channel.ROOT -> stringResource(R.string.dsh_perm_hint_root)
    PermissionManager.Channel.SHIZUKU -> stringResource(R.string.dsh_perm_hint_shizuku)
    PermissionManager.Channel.ADB -> stringResource(R.string.dsh_perm_hint_adb)
    PermissionManager.Channel.NONE ->
        if (s.shizukuRunning) stringResource(R.string.dsh_perm_hint_shizuku_ungranted)
        else stringResource(R.string.dsh_perm_hint_none)
}

/** Hero 卡：DeepSeek Harness 大标题 + 阶段 + 进度 + 操作。 */
@OptIn(ExperimentalLayoutApi::class)
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
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
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
