package me.bmax.apatch.ui.screen

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.rememberAsyncImagePainter
import com.ramcosta.composedestinations.generated.destinations.FunctionSettingsScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.bmax.apatch.APApplication
import me.bmax.apatch.R
import me.bmax.apatch.ui.component.BackgroundOptionsDialog
import me.bmax.apatch.ui.component.copyableInfo
import me.bmax.apatch.ui.component.rememberConfirmDialog
import me.bmax.apatch.ui.theme.BackgroundConfig
import me.bmax.apatch.ui.theme.BackgroundManager
import me.bmax.apatch.util.PermissionUtils
import me.bmax.apatch.util.SystemInfoCollector
import me.bmax.apatch.util.ui.HomeBottomSpacer
import me.bmax.apatch.util.ui.showToast

/**
 * Dashboard Pro 风格首页布局。
 *
 * 形制不变：Hero 大卡（支持卡片壁纸 + 长按换图 + 运行中呼吸动画）、系统信息卡、
 * 设备状态圆环卡、存储卡、了解更多卡；宽屏双栏。
 * 语义换成 DSH 运行时：Hero 卡显示运行阶段与 WebUI 入口，信息卡显示运行时与权限通道。
 */
@Composable
fun HomeScreenV4(
    innerPadding: PaddingValues,
    navigator: DestinationsNavigator
) {
    val prefs = APApplication.sharedPreferences
    val isWallpaperMode = BackgroundConfig.isCustomBackgroundEnabled &&
        (BackgroundConfig.customBackgroundUri != null || BackgroundConfig.isMultiBackgroundEnabled)

    val configuration = LocalConfiguration.current
    val isWide = configuration.screenWidthDp >= 600

    Column(
        modifier = Modifier
            .padding(innerPadding)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(0.dp))

        HeroStatusCard()

        if (isWide) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                DshSystemInfoCard(navigator, modifier = Modifier.weight(1f))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    DeviceStatusCard(isWallpaperMode = isWallpaperMode)
                    StorageInfoCard()
                }
            }
        } else {
            DshSystemInfoCard(navigator)
            DeviceStatusCard(isWallpaperMode = isWallpaperMode)
            StorageInfoCard()
        }

        DshLogCard()

        HomeBottomSpacer()
    }
}

/**
 * Hero 状态卡。
 *
 * 运行中走「渐变 + 呼吸动画 + 卡片壁纸」的大卡；未运行/出错走紧凑的一行卡。
 * 卡片壁纸键沿用 dashboard_card_bg_*，老 theme.json 继续生效。
 */
@Composable
private fun HeroStatusCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = LocalDshHomeState.current

    val wallpaperEnabled = BackgroundConfig.isDashboardCardBackgroundEnabled
    val wallpaperUri = BackgroundConfig.dashboardCardBgUri
    val hasWallpaper = wallpaperEnabled && !wallpaperUri.isNullOrEmpty()
    val isDarkTheme = dshIsDarkTheme()
    val wallpaperDim = BackgroundConfig.getEffectiveDashboardCardBgDim(isDarkTheme)
    val wallpaperOpacity = BackgroundConfig.getEffectiveDashboardCardBgOpacity(isDarkTheme)

    var showBackgroundOptions by remember { mutableStateOf(false) }
    val pickBackground = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val success = BackgroundManager.saveAndApplyDashboardCardBackground(context, it)
                showToast(
                    context,
                    if (success) R.string.dashboard_card_background_saved
                    else R.string.dashboard_card_background_error
                )
            }
        }
    }
    val clearBackgroundDialog = rememberConfirmDialog(
        onConfirm = {
            BackgroundManager.clearDashboardCardBackground(context)
            showToast(context, context.getString(R.string.dashboard_card_background_cleared))
        }
    )

    // 运行中的呼吸动画
    val infiniteTransition = rememberInfiniteTransition(label = "breathing")
    val breathAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathAlpha"
    )

    val baseColors = dshStatusColors(state, isDarkTheme)
    val containerColor by animateColorAsState(
        targetValue = baseColors.first,
        animationSpec = tween(500),
        label = "containerColor"
    )
    val contentColor by animateColorAsState(
        targetValue = if (hasWallpaper && state.isRunning) Color.White else baseColors.second,
        animationSpec = tween(500),
        label = "contentColor"
    )

    val gradientBrush = Brush.linearGradient(
        colors = listOf(
            containerColor.copy(alpha = if (state.isRunning) breathAlpha else 1f),
            containerColor.copy(alpha = 0.8f)
        )
    )

    if (state.isRunning) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp)
                .then(
                    if (wallpaperEnabled) {
                        Modifier.pointerInput(Unit) {
                            detectTapGestures(onLongPress = { showBackgroundOptions = true })
                        }
                    } else {
                        Modifier
                    }
                ),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.Transparent,
                contentColor = contentColor
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (!hasWallpaper) Modifier.background(gradientBrush) else Modifier)
            ) {
                if (hasWallpaper) {
                    Image(
                        painter = rememberAsyncImagePainter(wallpaperUri),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .matchParentSize()
                            .alpha(wallpaperOpacity),
                    )
                    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = wallpaperDim)))
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = dshPhaseIcon(state),
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = contentColor
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.dsh_app_title),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(4.dp))
                                ModeLabelChip(
                                    label = BackgroundConfig.getCustomBadgeText()
                                        ?: state.runtimeLabel.uppercase(),
                                    contentColor = contentColor
                                )
                            }
                        }

                        Spacer(Modifier.width(8.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { state.openWeb() },
                                colors = IconButtonDefaults.iconButtonColors(contentColor = contentColor),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.OpenInBrowser,
                                    contentDescription = stringResource(R.string.dsh_open_webui),
                                )
                            }
                            OutlinedButton(
                                onClick = { state.stop() },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    contentColor.copy(alpha = 0.5f)
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Stop,
                                    contentDescription = stringResource(R.string.dsh_stop),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider(
                        color = contentColor.copy(alpha = 0.2f),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    Spacer(Modifier.height(12.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        VersionInfoColumn(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.dsh_runtime_version),
                            value = state.version ?: "-"
                        )
                        VersionInfoColumn(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.dsh_web_port),
                            value = state.port.toString()
                        )
                        VersionInfoColumn(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.dsh_permission),
                            value = state.permLabel
                        )
                    }
                }
            }
        }
    } else {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !state.isBusy) { state.primaryAction() },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = containerColor,
                contentColor = contentColor
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = dshPhaseIcon(state),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp)
                )

                Spacer(Modifier.width(20.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        text = dshPhaseLabel(state),
                        style = MaterialTheme.typography.titleMedium
                    )
                    val detail = dshPhaseDetail(state)
                    if (detail.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodyMedium,
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
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = contentColor,
                            trackColor = contentColor.copy(alpha = 0.24f),
                        )
                    }
                }

                if (state.isBusy) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = contentColor
                    )
                }
            }
        }
    }

    if (wallpaperEnabled) {
        BackgroundOptionsDialog(
            showDialog = showBackgroundOptions,
            onDismiss = { showBackgroundOptions = false },
            title = stringResource(R.string.dashboard_card_background_title),
            selectLabel = stringResource(R.string.settings_select_background_image),
            clearLabel = stringResource(R.string.dashboard_card_background_clear),
            hasExisting = hasWallpaper,
            onSelectImage = {
                if (PermissionUtils.hasExternalStoragePermission(context)) {
                    try {
                        pickBackground.launch("image/*")
                    } catch (e: ActivityNotFoundException) {
                        showToast(context, e.message ?: "")
                    }
                } else {
                    showToast(context, context.getString(R.string.focus_card_permission_required))
                }
            },
            onClearImage = {
                clearBackgroundDialog.showConfirm(
                    title = context.getString(R.string.dashboard_card_background_clear),
                    content = context.getString(R.string.dashboard_card_background_clear_confirm),
                    markdown = false,
                )
            },
            onRestoreDefault = {
                val restored = BackgroundManager.provisionDefaultDashboardCardBg(context)
                val message = if (restored) {
                    R.string.dashboard_card_background_restored
                } else {
                    R.string.dashboard_card_background_error
                }
                showToast(context, context.getString(message))
            },
            restoreLabel = stringResource(R.string.dashboard_card_background_restore)
        )
    }
}

@Composable
private fun DeviceStatusCard(isWallpaperMode: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var deviceStatus by remember { mutableStateOf(SystemInfoCollector.DeviceStatus()) }

    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            deviceStatus = SystemInfoCollector.collectDeviceStatus(context)
            delay(10000)
        }
    }

    MagiskStyleCard(
        title = stringResource(R.string.home_device_status_title),
        icon = Icons.Outlined.Settings,
        actionText = "",
        showAction = false,
        isWallpaperMode = isWallpaperMode,
        onActionClick = {},
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatusCircle(
                value = "${deviceStatus.batteryTemp}°C",
                label = stringResource(R.string.home_device_status_battery_temp),
                progress = (deviceStatus.batteryTemp / 50f).coerceIn(0f, 1f),
                color = MaterialTheme.colorScheme.primary
            )
            StatusCircle(
                value = "${deviceStatus.cpuUsage}%",
                label = stringResource(R.string.home_device_status_cpu_load),
                progress = (deviceStatus.cpuUsage / 100f).coerceIn(0f, 1f),
                color = MaterialTheme.colorScheme.secondary
            )
            StatusCircle(
                value = "${deviceStatus.batteryLevel}%",
                label = stringResource(R.string.home_device_status_battery_level),
                progress = (deviceStatus.batteryLevel / 100f).coerceIn(0f, 1f),
                color = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}

@Composable
private fun StatusCircle(
    value: String,
    label: String,
    progress: Float,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(80.dp)
        ) {
            CircularProgressIndicator(
                progress = { 1f },
                modifier = Modifier.fillMaxSize(),
                color = color.copy(alpha = 0.2f),
                strokeWidth = 8.dp,
            )
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxSize(),
                color = color,
                strokeWidth = 8.dp,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MagiskStyleCard(
    title: String,
    icon: ImageVector,
    actionText: String,
    showAction: Boolean,
    actionEnabled: Boolean = true,
    isWallpaperMode: Boolean,
    onActionClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    TonalCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                
                if (showAction) {
                    Button(
                        onClick = onActionClick,
                        enabled = actionEnabled,
                        contentPadding = PaddingValues(horizontal = 24.dp)
                    ) {
                        Text(text = actionText)
                    }
                }
            }
            
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
            
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                content()
            }
        }
    }
}

/**
 * 模式标签芯片
 */
@Composable
private fun ModeLabelChip(label: String, contentColor: Color) {
    Surface(
        color = contentColor.copy(alpha = 0.2f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = contentColor
        )
    }
}

/**
 * 版本信息列
 */
@Composable
private fun VersionInfoColumn(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally
) {
    Column(
        modifier = modifier,
        horizontalAlignment = horizontalAlignment
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 系统信息卡：条目来自共用的 rememberDshInfoRows，仪表盘风格自己画一遍行。
 */
@Composable
private fun DshSystemInfoCard(
    navigator: DestinationsNavigator,
    modifier: Modifier = Modifier
) {
    val rows = rememberDshInfoRows(LocalDshHomeState.current)

    TonalCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { navigator.navigate(FunctionSettingsScreenDestination(null)) }
                    .padding(bottom = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.dsh_status),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(bottom = 12.dp)
            )

            rows.forEach { row ->
                InfoItem(row.icon, row.label, row.value)
            }
        }
    }
}

/**
 * 信息项
 */
@Composable
private fun InfoItem(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .copyableInfo(label, value)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier
                .size(18.dp)
                .padding(top = 2.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 存储信息卡片
 */
@Composable
private fun StorageInfoCard(modifier: Modifier = Modifier) {
    var storageStatus by remember { mutableStateOf(SystemInfoCollector.StorageStatus()) }

    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            storageStatus = SystemInfoCollector.collectStorageStatus()
            delay(5000)
        }
    }

    TonalCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 标题
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.SdStorage,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.home_storage_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // 存储进度
            StorageProgressBar(
                label = stringResource(R.string.home_storage_internal),
                used = storageStatus.storageUsed,
                total = storageStatus.storageTotal,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(12.dp))

            StorageProgressBar(
                label = stringResource(R.string.home_storage_ram),
                used = storageStatus.ramUsed,
                total = storageStatus.ramTotal,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

/**
 * 存储进度条
 */
@Composable
private fun StorageProgressBar(
    label: String,
    used: Long,
    total: Long,
    color: Color
) {
    val context = LocalContext.current
    val progress = if (total > 0) used.toFloat() / total.toFloat() else 0f
    val usedStr = android.text.format.Formatter.formatFileSize(context, used)
    val totalStr = android.text.format.Formatter.formatFileSize(context, total)

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "$usedStr / $totalStr",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = color,
            trackColor = color.copy(alpha = 0.2f),
        )
    }
}
