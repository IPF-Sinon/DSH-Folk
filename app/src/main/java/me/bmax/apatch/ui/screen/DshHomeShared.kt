package me.bmax.apatch.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.system.Os
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.bmax.apatch.APApplication
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshPhase
import me.bmax.apatch.dsh.DshRuntime
import me.bmax.apatch.dsh.HarnessService
import me.bmax.apatch.dsh.PermissionManager
import me.bmax.apatch.ui.component.copyableInfo
import me.bmax.apatch.ui.theme.BackgroundConfig
import me.bmax.apatch.util.getSELinuxStatus
import me.bmax.apatch.util.ui.showToast

/**
 * 所有首页布局共用的 DSH 运行时状态层。
 *
 * FolkPatch 的六套首页布局原本各自读 kpState/apState 画「内核已打补丁 / 未打补丁」，
 * DSH-Folk 不打内核补丁，所以这里把它们统一改喂同一份运行时状态：
 * 运行阶段、进度、运行时版本、WebUI 地址、生效的提权通道。
 *
 * 六套布局只负责各自的视觉形制（列表 / 网格大卡 / 圆环 / 仪表盘 / 统计），
 * 语义与动作全部来自这里，改一处六套一起对。
 */
@Stable
class DshHomeUiState internal constructor(
    private val context: Context,
    val phase: DshPhase,
    val message: String,
    val progress: Float,
    val speedBytesPerSec: Long,
    val version: String?,
    val port: Int,
    val webUrl: String,
    val installed: Boolean,
    val runtimeId: String,
    val perm: PermissionManager.Status,
) {
    val isRunning: Boolean get() = phase == DshPhase.RUNNING
    val isBusy: Boolean
        get() = phase == DshPhase.DOWNLOADING || phase == DshPhase.EXTRACTING || phase == DshPhase.STARTING
    val isError: Boolean get() = phase == DshPhase.ERROR

    /** 运行方式显示名（proot / proroot）。 */
    val runtimeLabel: String get() = if (runtimeId == "proroot") "proroot" else "proot"

    /** 权限通道显示名，直接来自 PermissionManager。 */
    val permLabel: String get() = perm.label

    fun start() = HarnessService.start(context)

    fun stop() = HarnessService.stop(context)

    fun restart() = DshRuntime.restart()

    fun openWeb() {
        val ok = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(webUrl))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (!ok) showToast(context, context.getString(R.string.dsh_no_browser))
    }

    /**
     * 卡片主操作：运行中打开 WebUI，忙碌时不响应，其余情况启动。
     * 六套布局的大卡点击与主按钮都走这一个入口。
     */
    fun primaryAction() {
        when {
            isRunning -> openWeb()
            isBusy -> Unit
            else -> start()
        }
    }
}

val LocalDshHomeState = staticCompositionLocalOf<DshHomeUiState> {
    error("DshHomeUiState is not available; wrap the layout in ProvideDshHomeState")
}

/** 在所有首页布局外层提供运行时状态，并做一次权限探测。 */
@Composable
fun ProvideDshHomeState(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val runtime by DshRuntime.state.collectAsStateWithLifecycle()
    val perm by PermissionManager.status.collectAsStateWithLifecycle()
    val appContext = remember(context) { context.applicationContext }

    LaunchedEffect(appContext) {
        DshRuntime.attach(appContext)
        withContext(Dispatchers.IO) { PermissionManager.refresh(appContext) }
    }

    val ui = remember(runtime, perm, appContext) {
        DshHomeUiState(
            context = appContext,
            phase = runtime.phase,
            message = runtime.message,
            progress = runtime.progress,
            speedBytesPerSec = runtime.speedBytesPerSec,
            version = runtime.runtimeVersion,
            port = runtime.port,
            webUrl = runtime.webUrl,
            installed = runtime.installed,
            runtimeId = DshRuntime.runtimeId(),
            perm = perm,
        )
    }

    CompositionLocalProvider(LocalDshHomeState provides ui) { content() }
}

/** 阶段名：未安装 / 下载中 / 安装中 / 启动中 / 运行中 / 出错。 */
@Composable
fun dshPhaseLabel(phase: DshPhase): String = stringResource(
    when (phase) {
        DshPhase.NOT_READY -> R.string.dsh_phase_not_ready
        DshPhase.DOWNLOADING -> R.string.dsh_phase_downloading
        DshPhase.EXTRACTING -> R.string.dsh_phase_extracting
        DshPhase.STARTING -> R.string.dsh_phase_starting
        DshPhase.RUNNING -> R.string.dsh_phase_running
        DshPhase.ERROR -> R.string.dsh_phase_error
    }
)

/** 卡片副标题：优先显示运行时消息，没有消息时给一句能指导下一步的提示。 */
@Composable
fun dshPhaseDetail(state: DshHomeUiState): String {
    if (state.message.isNotEmpty()) return state.message
    return when {
        state.isRunning -> state.webUrl
        state.phase == DshPhase.NOT_READY && !state.installed ->
            stringResource(R.string.dsh_hint_tap_to_install)
        state.phase == DshPhase.NOT_READY -> stringResource(R.string.dsh_hint_tap_to_start)
        else -> ""
    }
}

/** 主操作按钮文案。 */
@Composable
fun dshPrimaryActionLabel(state: DshHomeUiState): String = stringResource(
    when {
        state.isRunning -> R.string.dsh_open_webui
        state.isBusy -> R.string.dsh_working
        !state.installed -> R.string.dsh_install_and_start
        else -> R.string.dsh_start
    }
)

/** 状态图标。 */
fun dshPhaseIcon(state: DshHomeUiState): ImageVector = when {
    state.isRunning -> Icons.Filled.CheckCircle
    state.phase == DshPhase.DOWNLOADING -> Icons.Outlined.CloudDownload
    state.isBusy -> Icons.Outlined.Cached
    state.isError -> Icons.Outlined.ErrorOutline
    else -> Icons.Outlined.PlayArrow
}

/**
 * 大状态卡的容器色 / 内容色。
 *
 * 保留 FolkPatch 原来对自定义背景的处理：开了壁纸就把容器色按 opacity 透明化，
 * 并在近乎全透明时把文字改成纯黑/纯白，否则壁纸上的文字会看不清。
 */
@Composable
fun dshStatusColors(state: DshHomeUiState, isDarkTheme: Boolean): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    val (base, onBase) = when {
        state.isRunning -> scheme.primary to scheme.onPrimary
        state.isBusy -> scheme.secondary to scheme.onSecondary
        state.isError -> scheme.errorContainer to scheme.onErrorContainer
        else -> scheme.secondaryContainer to scheme.onSecondaryContainer
    }
    if (!BackgroundConfig.isCustomBackgroundEnabled) return base to onBase

    val opacity = BackgroundConfig.customBackgroundOpacity
    val content = if (opacity <= 0.1f) {
        if (isDarkTheme) Color.White else Color.Black
    } else {
        onBase
    }
    return base.copy(alpha = opacity) to content
}

/** 读取「跟随系统 / 手动」夜间模式设置，供上面的取色函数使用。 */
@Composable
fun dshIsDarkTheme(): Boolean {
    val prefs = APApplication.sharedPreferences
    val followSys = prefs.getBoolean("night_mode_follow_sys", false)
    return if (followSys) {
        androidx.compose.foundation.isSystemInDarkTheme()
    } else {
        prefs.getBoolean("night_mode_enabled", true)
    }
}

/**
 * 设备/运行时信息卡的数据源。
 *
 * 原来这张卡前几行是 KernelPatch 版本、su 路径、Zygisk/挂载实现，DSH-Folk 里都不存在，
 * 换成运行时自己的信息 + 设备信息。「隐藏指纹」等原有开关继续生效。
 */
data class DshInfoRow(val icon: ImageVector, val label: String, val value: String)

@Composable
fun rememberDshInfoRows(state: DshHomeUiState): List<DshInfoRow> {
    val hideFingerprint = APApplication.sharedPreferences.getBoolean("hide_fingerprint", false)

    val runtimeLabel = stringResource(R.string.dsh_runtime_version)
    val portLabel = stringResource(R.string.dsh_web_port)
    val modeLabel = stringResource(R.string.dsh_run_mode)
    val permLabel = stringResource(R.string.dsh_permission)
    val deviceLabel = stringResource(R.string.home_device_info)
    val kernelLabel = stringResource(R.string.home_kernel)
    val systemLabel = stringResource(R.string.home_system_version)
    val fingerprintLabel = stringResource(R.string.home_fingerprint)
    val selinuxLabel = stringResource(R.string.home_selinux_status)

    val kernelRelease = remember { Os.uname().release }
    val selinux = remember { runCatching { getSELinuxStatus() }.getOrDefault("Unknown") }

    return buildList {
        state.version?.let { add(DshInfoRow(Icons.Outlined.Layers, runtimeLabel, it)) }
        add(DshInfoRow(Icons.Outlined.Speed, modeLabel, state.runtimeLabel))
        add(DshInfoRow(Icons.Outlined.Security, permLabel, state.permLabel))
        if (state.isRunning) {
            add(DshInfoRow(Icons.Outlined.Info, portLabel, state.port.toString()))
        }
        add(DshInfoRow(Icons.Outlined.PhoneAndroid, deviceLabel, getDeviceInfo()))
        add(DshInfoRow(Icons.Outlined.DeveloperBoard, kernelLabel, kernelRelease))
        add(DshInfoRow(Icons.Outlined.Info, systemLabel, getSystemVersion()))
        if (!hideFingerprint) {
            add(DshInfoRow(Icons.Filled.Fingerprint, fingerprintLabel, Build.FINGERPRINT))
        }
        add(DshInfoRow(Icons.Outlined.Shield, selinuxLabel, selinux))
    }
}

/**
 * 运行时/设备信息卡（列表形制），取代原来的 InfoCard / ListInfoCard。
 *
 * 每行长按可复制（copyableInfo），showIcons 沿用「列表信息显示图标」开关。
 */
@Composable
fun DshInfoCard(showIcons: Boolean = false) {
    val rows = rememberDshInfoRows(LocalDshHomeState.current)

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (BackgroundConfig.isCustomBackgroundEnabled) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            rows.forEach { row ->
                if (showIcons) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .copyableInfo(row.label, row.value),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = row.icon,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(text = row.label, style = MaterialTheme.typography.bodyLarge)
                            Text(text = row.value, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .copyableInfo(row.label, row.value)
                    ) {
                        Text(text = row.label, style = MaterialTheme.typography.bodyLarge)
                        Text(text = row.value, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(0.dp))
        }
    }
}
