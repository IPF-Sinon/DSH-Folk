package me.bmax.apatch.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ramcosta.composedestinations.generated.destinations.FunctionSettingsScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import me.bmax.apatch.APApplication
import me.bmax.apatch.R
import me.bmax.apatch.ui.component.chart.ModulePieChart
import me.bmax.apatch.ui.component.chart.SystemAreaChart
import me.bmax.apatch.ui.component.chart.SystemLineChart
import me.bmax.apatch.ui.component.chart.rememberPieSliceDataFromCounts
import me.bmax.apatch.ui.theme.BackgroundConfig
import me.bmax.apatch.ui.viewmodel.DashboardViewModel
import me.bmax.apatch.ui.viewmodel.DshPluginViewModel
import me.bmax.apatch.ui.viewmodel.SystemMonitorState
import me.bmax.apatch.ui.viewmodel.TimeSeriesData
import me.bmax.apatch.util.ui.HomeBottomSpacer

/**
 * 统计风格首页。
 *
 * 形制不变：顶部状态区（列表卡或网格双卡，由 stats_top_layout 决定）+ 系统监控图表 +
 * 饼图统计 + 信息卡 + 了解更多卡；宽屏双栏。
 * 语义换成 DSH：状态区显示运行时，饼图从「KPM/APM/超级用户」改成插件的启用/停用/可更新。
 */
@Composable
fun HomeScreenStats(
    innerPadding: PaddingValues,
    navigator: DestinationsNavigator
) {
    val viewModel: DashboardViewModel = viewModel()
    val uiState by viewModel.dashboardUiState.collectAsStateWithLifecycle()
    val timeSeries by viewModel.timeSeriesData.collectAsStateWithLifecycle()

    val pluginViewModel: DshPluginViewModel = viewModel()
    LaunchedEffect(Unit) {
        if (pluginViewModel.plugins.isEmpty()) pluginViewModel.refresh()
    }

    val state = LocalDshHomeState.current
    // 图表与统计只在运行时已装好后才有意义，未安装时不启动轮询
    val isReady = state.installed

    val hideAboutCard = APApplication.sharedPreferences.getBoolean("hide_apatch_card", false)
    val statsTopLayout = APApplication.sharedPreferences.getString("stats_top_layout", "list") ?: "list"
    val useGridTop = statsTopLayout == "grid"
    val isWallpaperMode = BackgroundConfig.isCustomBackgroundEnabled &&
        (BackgroundConfig.customBackgroundUri != null || BackgroundConfig.isMultiBackgroundEnabled)

    LifecycleStartEffect(isReady) {
        if (isReady) {
            viewModel.startPeriodicPolling()
        }
        onStopOrDispose {
            viewModel.stopPeriodicPolling()
        }
    }

    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp >= 600

    if (isWideScreen) {
        Row(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isWallpaperMode) { Spacer(Modifier.height(8.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (useGridTop) {
                    StatsGridTopSection(navigator)
                } else {
                    StatusCardCircle()
                }
                if (isReady) {
                    SystemMonitoringSection(uiState.systemMonitor, timeSeries)
                }
                DshLogCard()
                HomeBottomSpacer()
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PluginStatisticsSection(pluginViewModel)
                InfoCardCircle()
                if (!hideAboutCard) {
                    LearnMoreCardV4()
                }
                HomeBottomSpacer()
            }
        }
    } else {
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isWallpaperMode) { Spacer(Modifier.height(8.dp)) }
            if (useGridTop) {
                StatsGridTopSection(navigator)
            } else {
                StatusCardCircle()
            }
            if (isReady) {
                SystemMonitoringSection(uiState.systemMonitor, timeSeries)
            }
            PluginStatisticsSection(pluginViewModel)
            InfoCardCircle()
            if (!hideAboutCard) {
                LearnMoreCardV4()
            }
            DshLogCard()
            HomeBottomSpacer()
        }
    }
}

@Composable
private fun SystemMonitoringSection(
    systemMonitor: SystemMonitorState,
    timeSeries: TimeSeriesData,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme

    val cpuDataPoints = timeSeries.cpuHistory
    val gpuDataPoints = timeSeries.gpuHistory
    val cpuTempDataPoints = timeSeries.cpuTempHistory

    TonalCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.home_device_status_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SystemLineChart(
                    title = stringResource(R.string.home_device_status_cpu_load),
                    dataPoints = cpuDataPoints,
                    modifier = Modifier.weight(1f),
                    color = colors.primary
                )
                SystemLineChart(
                    title = stringResource(R.string.home_device_status_gpu_load),
                    dataPoints = gpuDataPoints,
                    modifier = Modifier.weight(1f),
                    color = colors.tertiary
                )
            }

            if (cpuTempDataPoints.isNotEmpty() && cpuTempDataPoints.last() > 0f) {
                SystemLineChart(
                    title = stringResource(R.string.home_device_status_cpu_temp),
                    dataPoints = cpuTempDataPoints,
                    unit = "°C",
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.error
                )
            }

            if (timeSeries.ramHistory.isNotEmpty()) {
                SystemAreaChart(
                    title = stringResource(R.string.home_device_status_memory_trend),
                    dataPoints = timeSeries.ramHistory,
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.secondary
                )
            }

            if (systemMonitor.cpuFrequencies.isNotEmpty()) {
                CpuFrequencyBars(systemMonitor.cpuFrequencies)
            }

            systemMonitor.memoryInfo?.let { mem ->
                val ramPercent = if (mem.ramTotal > 0) {
                    (mem.ramUsed.toFloat() / mem.ramTotal.toFloat() * 100f)
                } else 0f

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Memory,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = colors.onSurfaceVariant
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.home_storage_ram),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                        Text(
                            text = formatBytes(mem.ramUsed) + " / " + formatBytes(mem.ramTotal),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                    LinearProgressIndicator(
                        progress = { ramPercent.coerceIn(0f, 100f) / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = when {
                            ramPercent > 85f -> colors.error
                            ramPercent > 70f -> colors.tertiary
                            else -> colors.primary
                        },
                        trackColor = colors.surfaceContainerHighest
                    )
                }

                if (mem.zramTotal > 0) {
                    val zramPercent = mem.zramUsed.toFloat() / mem.zramTotal.toFloat() * 100f
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.SdStorage,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = colors.onSurfaceVariant
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.home_storage_zram),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                            Text(
                                text = formatBytes(mem.zramUsed) + " / " + formatBytes(mem.zramTotal),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                        LinearProgressIndicator(
                            progress = { zramPercent.coerceIn(0f, 100f) / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = colors.tertiary,
                            trackColor = colors.surfaceContainerHighest
                        )
                    }
                }

                if (mem.swapTotal > 0) {
                    val swapPercent = mem.swapUsed.toFloat() / mem.swapTotal.toFloat() * 100f
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.Storage,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = colors.onSurfaceVariant
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.home_storage_swap),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                            Text(
                                text = formatBytes(mem.swapUsed) + " / " + formatBytes(mem.swapTotal),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                        LinearProgressIndicator(
                            progress = { swapPercent.coerceIn(0f, 100f) / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = colors.error,
                            trackColor = colors.surfaceContainerHighest
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (systemMonitor.batteryCharging) {
                            Icons.Outlined.BatteryChargingFull
                        } else {
                            Icons.Outlined.BatteryStd
                        },
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (systemMonitor.batteryCharging) colors.primary else colors.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.home_device_status_battery_level),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant
                    )
                }
                Text(
                    text = buildString {
                        append("${systemMonitor.batteryLevel}%")
                        if (systemMonitor.batteryCharging) append(" · ${stringResource(R.string.home_device_status_battery_charging)}")
                        if (systemMonitor.batteryTemp > 0) {
                            append("  ${systemMonitor.batteryTemp.toInt()}°C")
                        }
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface
                )
                LinearProgressIndicator(
                    progress = { systemMonitor.batteryLevel.coerceIn(0, 100) / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = when {
                        systemMonitor.batteryCharging -> colors.primary
                        systemMonitor.batteryLevel <= 20 -> colors.error
                        systemMonitor.batteryLevel <= 50 -> colors.tertiary
                        else -> colors.primary
                    },
                    trackColor = colors.surfaceContainerHighest
                )
            }

            if (systemMonitor.networkRxBytes > 0 || systemMonitor.networkTxBytes > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Download,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = colors.onSurfaceVariant
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.home_network_rx),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                        Text(
                            text = formatBytes(systemMonitor.networkRxBytes),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface
                        )
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Upload,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = colors.onSurfaceVariant
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.home_network_tx),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                        Text(
                            text = formatBytes(systemMonitor.networkTxBytes),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginStatisticsSection(
    viewModel: DshPluginViewModel,
    modifier: Modifier = Modifier
) {
    val plugins = viewModel.plugins
    val enabledCount = plugins.count { it.enabled }
    val disabledCount = plugins.size - enabledCount
    val updatableCount = viewModel.updatableCount

    TonalCard(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ModulePieChart(
                data = rememberPieSliceDataFromCounts(
                    kernelModules = enabledCount,
                    apmModules = disabledCount,
                    superusers = updatableCount,
                    kpmLabel = stringResource(R.string.dsh_stats_enabled),
                    apmLabel = stringResource(R.string.dsh_stats_disabled),
                    suLabel = stringResource(R.string.dsh_stats_updatable),
                ),
                centerLabel = if (plugins.isNotEmpty()) plugins.size.toString() else "--",
                modifier = Modifier.size(140.dp)
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.align(Alignment.CenterVertically)
            ) {
                StatRow(
                    label = stringResource(R.string.dsh_stats_enabled),
                    value = enabledCount.toString(),
                    icon = Icons.Outlined.CheckCircle
                )
                StatRow(
                    label = stringResource(R.string.dsh_stats_disabled),
                    value = disabledCount.toString(),
                    icon = Icons.Outlined.Block
                )
                StatRow(
                    label = stringResource(R.string.dsh_stats_updatable),
                    value = updatableCount.toString(),
                    icon = Icons.Outlined.SystemUpdate
                )
            }
        }
    }
}

@Composable
private fun StatRow(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun CpuFrequencyBars(
    cpuFrequencies: List<me.bmax.apatch.util.HardwareMonitor.CpuFreqInfo>,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme

    val clusters = cpuFrequencies
        .groupBy { it.maxFreqKhz }
        .values
        .map { cores ->
            val label = if (cores.size == 1) "CPU${cores[0].coreIndex}" else "CPU${cores.first().coreIndex}-${cores.last().coreIndex}"
            val avgFreq = cores.map { it.currentFreqKhz }.sum() / cores.size
            val maxFreq = cores.first().maxFreqKhz
            CpuCluster(label, avgFreq, maxFreq)
        }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.DeveloperBoard,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = colors.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.home_device_status_cpu_freq),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant
            )
        }
        clusters.forEach { cluster ->
            val percent = if (cluster.maxFreq > 0) {
                (cluster.avgFreq.toFloat() / cluster.maxFreq.toFloat() * 100f).coerceIn(0f, 100f)
            } else 0f

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = cluster.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.width(52.dp)
                )
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = when {
                        percent > 80f -> colors.error
                        percent > 50f -> colors.tertiary
                        else -> colors.primary
                    },
                    trackColor = colors.surfaceContainerHighest
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatFreq(cluster.avgFreq),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurface,
                    modifier = Modifier.width(64.dp)
                )
            }
        }
    }
}

private data class CpuCluster(
    val label: String,
    val avgFreq: Long,
    val maxFreq: Long
)

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        bytes < 1024 * 1024 * 1024 -> String.format("%.1fMB", bytes / (1024.0 * 1024))
        else -> String.format("%.1fGB", bytes / (1024.0 * 1024 * 1024))
    }
}

private fun formatFreq(khz: Long): String {
    return when {
        khz >= 1_000_000 -> String.format("%.2fGHz", khz / 1_000_000.0)
        khz >= 1000 -> String.format("%.0fMHz", khz / 1000.0)
        else -> "${khz}KHz"
    }
}


@Composable
private fun StatsGridTopSection(navigator: DestinationsNavigator) {
    val state = LocalDshHomeState.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatusCardBig(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            onClick = { state.primaryAction() }
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SmallInfoCard(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.dsh_run_mode),
                value = state.runtimeLabel,
                icon = Icons.Outlined.Speed,
                onClick = { navigator.navigate(FunctionSettingsScreenDestination(null)) }
            )

            SmallInfoCard(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.dsh_permission),
                value = state.permLabel,
                icon = Icons.Outlined.Security,
                onClick = { navigator.navigate(FunctionSettingsScreenDestination(null)) }
            )
        }
    }
}
