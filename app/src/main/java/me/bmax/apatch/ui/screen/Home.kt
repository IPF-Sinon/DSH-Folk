package me.bmax.apatch.ui.screen

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.AboutScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import me.bmax.apatch.APApplication
import me.bmax.apatch.R
import me.bmax.apatch.dsh.PermissionManager
import me.bmax.apatch.ui.component.WallpaperAwareDropdownMenu
import me.bmax.apatch.ui.component.WallpaperAwareDropdownMenuItem
import me.bmax.apatch.ui.component.WelcomeGuideDialog
import me.bmax.apatch.ui.component.rememberConfirmDialog
import me.bmax.apatch.ui.theme.BackgroundConfig
import me.bmax.apatch.ui.theme.MusicConfig
import me.bmax.apatch.ui.theme.refreshTheme
import me.bmax.apatch.util.MusicManager
import me.bmax.apatch.util.reboot
import me.bmax.apatch.util.ui.HomeBottomSpacer

@Destination<RootGraph>(start = true)
@Composable
fun HomeScreen(navigator: DestinationsNavigator) {
    var homeLayout by remember {
        mutableStateOf(APApplication.sharedPreferences.getString("home_layout_style", "dsh"))
    }
    var showListInfoIcons by remember {
        mutableStateOf(APApplication.sharedPreferences.getBoolean("list_info_show_icons", false))
    }
    val homeRefreshObserver by refreshTheme.observeAsState(false)
    if (homeRefreshObserver) {
        homeLayout = APApplication.sharedPreferences.getString("home_layout_style", "dsh")
        showListInfoIcons = APApplication.sharedPreferences.getBoolean("list_info_show_icons", false)
    }

    // 迁移旧版SignUI: 合并至ListUI + "信息区图标"开关
    if (homeLayout == "sign") {
        APApplication.sharedPreferences.edit()
            .putString("home_layout_style", "default")
            .putBoolean("list_info_show_icons", true)
            .apply()
        homeLayout = "default"
        showListInfoIcons = true
    }

    // 首次启动欢迎引导
    var showWelcomeGuide by remember {
        mutableStateOf(!APApplication.sharedPreferences.getBoolean("welcome_guide_shown", false))
    }
    if (showWelcomeGuide) {
        WelcomeGuideDialog(
            onDismiss = {
                APApplication.sharedPreferences.edit()
                    .putBoolean("welcome_guide_shown", true)
                    .apply()
                showWelcomeGuide = false
            }
        )
    }

    // 六套布局共用同一份 DSH 运行时状态（见 DshHomeShared.kt），
    // TopBar 也要读它来决定是否显示重启菜单，所以 Provide 包在 Scaffold 外面。
    ProvideDshHomeState {
        Scaffold(topBar = {
            TopBar(navigator)
        }) { innerPadding ->
            when (homeLayout) {
                // DSH-Folk 默认首页：DeepSeek Harness 运行时面板
                "dsh" -> HomeScreenDsh(innerPadding, navigator)
                "kernelsu" -> HomeScreenV2(innerPadding, navigator, showListInfoIcons)
                "focus" -> HomeScreenV3(innerPadding, navigator)
                "circle" -> HomeScreenCircle(innerPadding, navigator)
                "dashboard_ui" -> HomeScreenV4(innerPadding, navigator)
                "stats" -> HomeScreenStats(innerPadding, navigator)
                else -> HomeScreenV1(innerPadding, navigator, showListInfoIcons)
            }
        }
    }
}

/** 列表风格首页（原 ListUI）：一张状态卡 + 信息卡 + 了解更多卡。 */
@Composable
fun HomeScreenV1(
    innerPadding: PaddingValues,
    navigator: DestinationsNavigator,
    showInfoIcons: Boolean = false
) {
    Column(
        modifier = Modifier
            .padding(innerPadding)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(0.dp))
        DshStatusCardList()
        DshInfoCard(showInfoIcons)
        val hideAboutCard = APApplication.sharedPreferences.getBoolean("hide_apatch_card", false)
        if (!hideAboutCard) {
            LearnMoreCard()
        }
        HomeBottomSpacer()
    }
}

/**
 * 列表风格的大状态卡。
 *
 * 沿用原 KStatusCard 的形制与自定义背景处理（含 list_working_card_mode_hidden
 * 这个「隐藏模式角标」开关和 custom_badge_text），内容换成 DSH 运行时状态。
 */
@Composable
private fun DshStatusCardList() {
    val state = LocalDshHomeState.current
    val isDarkTheme = dshIsDarkTheme()
    val (containerColor, contentColor) = dshStatusColors(state, isDarkTheme)

    Card(
        onClick = { state.primaryAction() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(dshPhaseIcon(state), contentDescription = null)

                Column(
                    Modifier
                        .weight(2f)
                        .padding(start = 16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (BackgroundConfig.isListWorkingCardModeHidden && state.isRunning) {
                                dshPhaseLabel(state.phase) + "😋"
                            } else {
                                dshPhaseLabel(state.phase)
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (!BackgroundConfig.isListWorkingCardModeHidden) {
                            Spacer(Modifier.width(8.dp))
                            StatusBadge(
                                text = BackgroundConfig.getCustomBadgeText()
                                    ?: state.runtimeLabel.uppercase()
                            )
                        }
                    }

                    val detail = dshPhaseDetail(state)
                    if (detail.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    state.version?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "$it · " + state.runtimeLabel,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    if (state.isBusy) {
                        Spacer(Modifier.height(8.dp))
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

                Column(modifier = Modifier.align(Alignment.CenterVertically)) {
                    Button(
                        onClick = { state.primaryAction() },
                        enabled = !state.isBusy,
                        colors = if (BackgroundConfig.isCustomBackgroundEnabled && state.isRunning) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                                    .copy(alpha = BackgroundConfig.customBackgroundOpacity),
                                contentColor = contentColor
                            )
                        } else {
                            ButtonDefaults.buttonColors()
                        },
                    ) {
                        Text(text = dshPrimaryActionLabel(state))
                    }
                }
            }
        }
    }
}

private data class RebootOption(
    @param:StringRes val titleRes: Int,
    val reason: String,
    val icon: ImageVector
)

@Composable
private fun getRebootOptions(): List<RebootOption> = listOf(
    RebootOption(R.string.reboot, "", Icons.Filled.Refresh),
    RebootOption(R.string.reboot_recovery, "recovery", Icons.Outlined.SystemUpdate),
    RebootOption(R.string.reboot_bootloader, "bootloader", Icons.Outlined.Memory),
    RebootOption(R.string.reboot_download, "download", Icons.Outlined.Download),
    RebootOption(R.string.reboot_edl, "edl", Icons.Outlined.DeveloperMode),
    RebootOption(R.string.reboot_fastbootd, "fastboot", Icons.Outlined.RestartAlt),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    navigator: DestinationsNavigator
) {
    val uriHandler = LocalUriHandler.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var showDropdownMoreOptions by remember { mutableStateOf(false) }
    var showDropdownReboot by remember { mutableStateOf(false) }
    val prefs = APApplication.sharedPreferences
    val darkThemeFollowSys = prefs.getBoolean("night_mode_follow_sys", false)
    val nightModeEnabled = prefs.getBoolean("night_mode_enabled", true)
    val isDarkTheme = if (darkThemeFollowSys) {
        isSystemInDarkTheme()
    } else {
        nightModeEnabled
    }
    
    val currentTitle = prefs.getString("app_title", "dsh") ?: "dsh"
    val customAppTitle = prefs.getString("custom_app_title", "DSH-Folk") ?: "DSH-Folk"
    val isCustomTitle = currentTitle == "custom"
    val titleResId = when (currentTitle) {
        "custom" -> null
        "fpatch" -> R.string.app_title_fpatch
        "apatch_folk" -> R.string.app_title_apatch_folk
        "apatchx" -> R.string.app_title_apatchx
        "apatch" -> R.string.app_title_apatch
        "kernelpatch" -> R.string.app_title_kernelpatch
        "kernelsu" -> R.string.app_title_kernelsu
        "supersu" -> R.string.app_title_supersu
        "folksu" -> R.string.app_title_fpatch
        "superuser" -> R.string.app_title_superuser
        "superpatch" -> R.string.app_title_superpatch
        "magicpatch" -> R.string.app_title_magicpatch
        "folkpatch" -> R.string.app_title_folkpatch
        else -> R.string.app_title_dsh
    }

    val useAdvancedTitleStyle = BackgroundConfig.isAdvancedTitleStyleEnabled && 
                                !BackgroundConfig.titleImageUri.isNullOrEmpty()
    val titleOpacity = if (useAdvancedTitleStyle) {
        BackgroundConfig.getEffectiveTitleImageOpacity(isDarkTheme)
    } else 1f
    val titleDim = if (useAdvancedTitleStyle) {
        BackgroundConfig.getEffectiveTitleImageDim(isDarkTheme)
    } else 0f
    val titleOffsetX = if (useAdvancedTitleStyle) {
        BackgroundConfig.titleImageOffsetX * 100f
    } else 0f

    TopAppBar(title = {
        if (useAdvancedTitleStyle) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(BackgroundConfig.titleImageUri)
                    .crossfade(true)
                    .build(),
                contentDescription = titleResId?.let { stringResource(it) } ?: customAppTitle,
                modifier = Modifier
                    .height(40.dp)
                    .offset(x = titleOffsetX.dp)
                    .alpha(titleOpacity)
                    .graphicsLayer {
                        if (titleDim > 0f) {
                            colorFilter = ColorFilter.colorMatrix(
                                ColorMatrix().apply {
                                    setToScale(
                                        1f - titleDim,
                                        1f - titleDim,
                                        1f - titleDim,
                                        1f
                                    )
                                }
                            )
                        }
                    },
                contentScale = ContentScale.Fit
            )
        } else {
            Text(if (isCustomTitle) customAppTitle else stringResource(titleResId!!))
        }
    }, actions = {
        // 重启菜单需要 root 才能真正生效，没有 root 通道时不显示
        if (PermissionManager.Channel.ROOT == LocalDshHomeState.current.perm.channel) {
            // Download/EDL drop the device into flashing modes that look dead to
            // a normal user, so they get a confirmation step first.
            val downloadTitle = stringResource(id = R.string.reboot_download)
            val downloadConfirmText = stringResource(id = R.string.reboot_download_confirm)
            val edlTitle = stringResource(id = R.string.reboot_edl)
            val edlConfirmText = stringResource(id = R.string.reboot_edl_confirm)
            var pendingRebootReason by remember { mutableStateOf<String?>(null) }
            val rebootConfirmDialog = rememberConfirmDialog(onConfirm = {
                pendingRebootReason?.let { reboot(it) }
            })

            Box {
                IconButton(onClick = { showDropdownReboot = true }) {
                    Icon(
                        imageVector = Icons.Filled.PowerSettingsNew,
                        contentDescription = stringResource(id = R.string.reboot)
                    )
                }
                WallpaperAwareDropdownMenu(
                    expanded = showDropdownReboot,
                    onDismissRequest = { showDropdownReboot = false }
                ) {
                    getRebootOptions().forEach { option ->
                        WallpaperAwareDropdownMenuItem(
                            text = { Text(stringResource(option.titleRes)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = option.icon,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                showDropdownReboot = false
                                when (option.reason) {
                                    "download" -> {
                                        pendingRebootReason = "download"
                                        rebootConfirmDialog.showConfirm(
                                            title = downloadTitle, content = downloadConfirmText
                                        )
                                    }
                                    "edl" -> {
                                        pendingRebootReason = "edl"
                                        rebootConfirmDialog.showConfirm(
                                            title = edlTitle, content = edlConfirmText
                                        )
                                    }
                                    else -> reboot(option.reason)
                                }
                            }
                        )
                    }
                }
            }
        }

        Box {
            IconButton(onClick = { showDropdownMoreOptions = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(id = R.string.settings)
                )
                WallpaperAwareDropdownMenu(
                    expanded = showDropdownMoreOptions,
                    onDismissRequest = { showDropdownMoreOptions = false }
                ) {
                    if (MusicConfig.isMusicEnabled) {
                        val isPlaying by MusicManager.isPlaying.collectAsStateWithLifecycle()
                        WallpaperAwareDropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (isPlaying) R.string.home_more_menu_music_pause
                                        else R.string.home_more_menu_music_play
                                    )
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                showDropdownMoreOptions = false
                                MusicManager.toggle()
                            }
                        )
                    }
                    WallpaperAwareDropdownMenuItem(
                        text = { Text(stringResource(R.string.home_more_menu_feedback_or_suggestion)) },
                        onClick = {
                            showDropdownMoreOptions = false
                            uriHandler.openUri("https://github.com/IPF-Sinon/DSH-Folk/issues/new/choose")
                        }
                    )
                    WallpaperAwareDropdownMenuItem(
                        text = { Text(stringResource(R.string.home_more_menu_about)) },
                        onClick = {
                            navigator.navigate(AboutScreenDestination)
                            showDropdownMoreOptions = false
                        }
                    )
                }
            }
        }
    })
}

fun getSystemVersion(): String {
    return "${Build.VERSION.RELEASE} ${if (Build.VERSION.PREVIEW_SDK_INT != 0) "Preview" else ""} (API ${Build.VERSION.SDK_INT})"
}

fun getDeviceInfo(): String {
    var manufacturer =
        Build.MANUFACTURER[0].uppercaseChar().toString() + Build.MANUFACTURER.substring(1)
    if (!Build.BRAND.equals(Build.MANUFACTURER, ignoreCase = true)) {
        manufacturer += " " + Build.BRAND[0].uppercaseChar() + Build.BRAND.substring(1)
    }
    manufacturer += " " + Build.MODEL + " "
    return manufacturer
}

@Composable
fun StatusBadge(
    text: String,
    containerColor: Color = MaterialTheme.colorScheme.onPrimary,
    contentColor: Color = MaterialTheme.colorScheme.primary
) {
    Surface(
        color = containerColor.copy(alpha = 1f),
        shape = RoundedCornerShape(4.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = 1f),
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun LearnMoreCard() {
    val uriHandler = LocalUriHandler.current

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = if (BackgroundConfig.isCustomBackgroundEnabled) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
        })
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    uriHandler.openUri("https://github.com/IPF-Sinon/DSH-Folk")
                }
                .padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(
                    text = stringResource(R.string.dsh_learn_title),
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.dsh_learn_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
