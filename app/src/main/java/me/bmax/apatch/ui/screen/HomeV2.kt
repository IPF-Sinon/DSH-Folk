package me.bmax.apatch.ui.screen

import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.rememberAsyncImagePainter
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.ramcosta.composedestinations.generated.destinations.FunctionSettingsScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.launch
import me.bmax.apatch.R
import me.bmax.apatch.ui.component.BackgroundOptionsDialog
import me.bmax.apatch.ui.component.rememberConfirmDialog
import me.bmax.apatch.ui.theme.BackgroundConfig
import me.bmax.apatch.ui.theme.BackgroundManager
import me.bmax.apatch.util.PermissionUtils
import me.bmax.apatch.util.ui.HomeBottomSpacer
import me.bmax.apatch.util.ui.showToast

/**
 * 网格风格首页（原 KernelSU 风格布局）。
 *
 * 形制不变：左侧一张大状态卡（支持自定义卡片背景图 + 长按换图），右侧上下两张小卡，
 * 下面一张信息卡。语义换成 DSH 运行时：大卡显示运行阶段，小卡显示运行方式与权限通道。
 */
@Composable
fun HomeScreenV2(
    paddingValues: PaddingValues,
    navigator: DestinationsNavigator,
    showInfoIcons: Boolean = false
) {
    val scrollState = rememberScrollState()
    val state = LocalDshHomeState.current

    Column(
        modifier = Modifier
            .padding(paddingValues)
            .padding(horizontal = 16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(0.dp))

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
                // 运行方式：proot / proroot，点进权限与功能设置切换
                SmallInfoCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.dsh_run_mode),
                    value = state.runtimeLabel,
                    icon = Icons.Outlined.Speed,
                    onClick = { navigator.navigate(FunctionSettingsScreenDestination(null)) }
                )

                // 权限通道：Root / Shizuku / 无线 ADB / 未获取
                SmallInfoCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.dsh_permission),
                    value = state.permLabel,
                    icon = Icons.Outlined.Security,
                    onClick = { navigator.navigate(FunctionSettingsScreenDestination(null)) }
                )
            }
        }

        DshInfoCard(showInfoIcons)

        DshLogCard()

        HomeBottomSpacer()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusCardBig(
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val state = LocalDshHomeState.current
    val isDark = dshIsDarkTheme()

    // 卡片背景图只在「运行中」这一态开放，跟原来 isWorking 的语义对齐
    val useCustomGridBg = BackgroundConfig.isGridWorkingCardBackgroundEnabled &&
        !BackgroundConfig.gridWorkingCardBackgroundUri.isNullOrEmpty()

    val (baseContainerColor, baseContentColor) = dshStatusColors(state, isDark)
    val containerColor = if (useCustomGridBg) Color.Transparent else baseContainerColor
    val contentColor = if (useCustomGridBg) Color.White else baseContentColor

    val scope = rememberCoroutineScope()
    var showCardOptionsDialog by remember { mutableStateOf(false) }
    val isLongPressEnabled = state.isRunning && BackgroundConfig.isGridWorkingCardBackgroundEnabled

    val pickGridImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val success = BackgroundManager.saveAndApplyGridWorkingCardBackground(context, it)
                if (success) {
                    showToast(context, R.string.settings_grid_working_card_background_saved)
                } else {
                    showToast(context, R.string.settings_grid_working_card_background_error)
                }
            }
        }
    }

    val clearGridBackgroundDialog = rememberConfirmDialog(
        onConfirm = {
            scope.launch {
                BackgroundManager.clearGridWorkingCardBackground(context)
                showToast(context, context.getString(R.string.settings_grid_working_card_background_cleared))
            }
        }
    )

    Card(
        modifier = modifier
            .then(
                if (isLongPressEnabled) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onClick() },
                            onLongPress = { showCardOptionsDialog = true }
                        )
                    }
                } else {
                    Modifier.clickable { onClick() }
                }
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (useCustomGridBg) {
                val imageLoader = ImageLoader.Builder(context)
                    .components {
                        if (Build.VERSION.SDK_INT >= 28) {
                            add(ImageDecoderDecoder.Factory())
                        } else {
                            add(GifDecoder.Factory())
                        }
                    }
                    .build()

                Image(
                    painter = rememberAsyncImagePainter(
                        model = ImageRequest.Builder(context)
                            .data(BackgroundConfig.gridWorkingCardBackgroundUri)
                            .crossfade(true)
                            .build(),
                        imageLoader = imageLoader,
                        contentScale = ContentScale.Crop
                    ),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(BackgroundConfig.getEffectiveGridBackgroundOpacity(isDark))
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = BackgroundConfig.gridWorkingCardBackgroundDim))
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.align(Alignment.BottomStart)) {
                    if (!BackgroundConfig.isGridWorkingCardTextHidden) {
                        Text(
                            text = dshPhaseLabel(state.phase),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = contentColor
                        )
                    }
                    if (state.isRunning && !BackgroundConfig.isGridWorkingCardModeHidden) {
                        Spacer(Modifier.height(4.dp))
                        val customText = BackgroundConfig.getCustomBadgeText()
                        Text(
                            text = customText?.let { "<$it>" } ?: ":${state.port}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = contentColor.copy(alpha = 0.8f)
                        )
                    }
                    if (state.isBusy && !BackgroundConfig.isGridWorkingCardTextHidden) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { state.progress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                            color = contentColor,
                            trackColor = contentColor.copy(alpha = 0.24f),
                        )
                    }
                }
            }

            if (!BackgroundConfig.isGridWorkingCardCheckHidden) {
                Icon(
                    imageVector = dshPhaseIcon(state),
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(48.dp),
                    tint = contentColor
                )
            }
        }
    }

    BackgroundOptionsDialog(
        showDialog = showCardOptionsDialog,
        onDismiss = { showCardOptionsDialog = false },
        title = stringResource(R.string.settings_grid_working_card_background),
        selectLabel = stringResource(R.string.settings_select_background_image),
        clearLabel = stringResource(R.string.settings_clear_grid_working_card_background),
        hasExisting = !BackgroundConfig.gridWorkingCardBackgroundUri.isNullOrEmpty(),
        onSelectImage = {
            if (PermissionUtils.hasExternalStoragePermission(context)) {
                try {
                    pickGridImageLauncher.launch("image/*")
                } catch (e: ActivityNotFoundException) {
                    showToast(context, e.message ?: "")
                }
            } else {
                showToast(context, context.getString(R.string.settings_background_permission_required))
            }
        },
        onClearImage = {
            clearGridBackgroundDialog.showConfirm(
                title = context.getString(R.string.settings_clear_grid_working_card_background),
                content = context.getString(R.string.settings_clear_grid_working_card_background_confirm),
                markdown = false,
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmallInfoCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    icon: ImageVector,
    onClick: () -> Unit
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
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
