package me.bmax.apatch.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ramcosta.composedestinations.generated.NavGraphs
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import me.bmax.apatch.R
import me.bmax.apatch.ui.component.copyableInfo
import me.bmax.apatch.ui.theme.BackgroundConfig
import me.bmax.apatch.util.ui.HomeBottomSpacer

/**
 * 圆角卡片风格首页。
 *
 * 形制不变：一张大状态卡 + 两张并排入口卡 + 一张信息卡 + 一张了解更多卡。
 * 语义换成 DSH：状态卡显示运行阶段，两张入口卡改成「终端」与「插件」。
 */
@Composable
fun HomeScreenCircle(
    innerPadding: PaddingValues,
    navigator: DestinationsNavigator
) {
    Column(
        modifier = Modifier
            .padding(innerPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (BackgroundConfig.isCustomBackgroundEnabled) {
            Spacer(Modifier.height(0.dp))
        }

        StatusCardCircle()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            EntryCardCircle(
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.Terminal,
                title = stringResource(R.string.dsh_terminal),
                subtitle = stringResource(R.string.dsh_run_mode) + ": " + LocalDshHomeState.current.runtimeLabel,
                onClick = {
                    navigator.navigate(BottomBarDestination.Terminal.direction) {
                        popUpTo(NavGraphs.root) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )

            EntryCardCircle(
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.Widgets,
                title = stringResource(R.string.dsh_plugins),
                subtitle = stringResource(R.string.dsh_plugin_store),
                onClick = {
                    navigator.navigate(BottomBarDestination.Plugin.direction) {
                        popUpTo(NavGraphs.root) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }

        InfoCardCircle()

        DshLogCard()

        HomeBottomSpacer()
    }
}

/** 大状态卡：整卡可点，动作与其他布局一致（运行中打开 WebUI，否则启动）。 */
@Composable
fun StatusCardCircle() {
    val state = LocalDshHomeState.current
    val isDark = dshIsDarkTheme()
    val (container, content) = dshStatusColors(state, isDark)

    TonalCard(containerColor = container) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { state.primaryAction() }
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = dshPhaseIcon(state),
                contentDescription = null,
                tint = content
            )
            Column(
                Modifier
                    .padding(start = 20.dp)
                    .weight(1f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = dshPhaseLabel(state.phase),
                        style = MaterialTheme.typography.titleMedium,
                        color = content
                    )
                    Spacer(Modifier.width(8.dp))
                    val badge = BackgroundConfig.getCustomBadgeText()
                        ?: state.runtimeLabel.uppercase()
                    ModeLabelText(label = badge)
                }
                Spacer(Modifier.height(4.dp))
                val detail = dshPhaseDetail(state)
                if (detail.isNotEmpty()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = content.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (state.isBusy) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { state.progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        color = content,
                        trackColor = content.copy(alpha = 0.24f),
                    )
                }
            }
            if (state.isRunning) {
                IconButton(onClick = { state.stop() }) {
                    Icon(
                        imageVector = Icons.Outlined.Stop,
                        contentDescription = stringResource(R.string.dsh_stop),
                        tint = content
                    )
                }
            }
        }
    }
}

/** 并排入口卡：图标 + 标题 + 一行副标题。 */
@Composable
private fun EntryCardCircle(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    TonalCard(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 信息卡：条目来自共用的 rememberDshInfoRows，圆角风格自己画一遍行。 */
@Composable
fun InfoCardCircle() {
    val rows = rememberDshInfoRows(LocalDshHomeState.current)

    TonalCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            rows.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .copyableInfo(row.label, row.value),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = row.icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(text = row.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = row.value,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ModeLabelText(
    label: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onPrimary,
    containerColor: Color = MaterialTheme.colorScheme.primary
) {
    Box(
        modifier = modifier
            .padding(end = 4.dp)
            .background(
                color = containerColor,
                shape = RoundedCornerShape(4.dp)
            )
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(vertical = 2.dp, horizontal = 5.dp),
            style = TextStyle(
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
        )
    }
}

@Composable
fun TonalCard(
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    shape: Shape = RoundedCornerShape(20.dp),
    content: @Composable () -> Unit
) {
    val finalContainerColor = containerColor ?: if (BackgroundConfig.isCustomBackgroundEnabled) {
        MaterialTheme.colorScheme.surface.copy(alpha = BackgroundConfig.customBackgroundOpacity)
    } else {
        MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
    }
    
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = finalContainerColor),
        shape = shape
    ) {
        content()
    }
}
