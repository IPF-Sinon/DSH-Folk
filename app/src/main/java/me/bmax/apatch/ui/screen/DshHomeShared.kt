package me.bmax.apatch.ui.screen

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import me.bmax.apatch.APApplication
import me.bmax.apatch.BuildConfig
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshPhase
import me.bmax.apatch.dsh.DshRuntime
import me.bmax.apatch.dsh.HarnessService
import me.bmax.apatch.dsh.PermissionManager
import me.bmax.apatch.ui.DshWebUi
import me.bmax.apatch.ui.component.copyInfoToClipboard
import me.bmax.apatch.ui.component.copyableInfo
import me.bmax.apatch.ui.theme.BackgroundConfig
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
    /** 「每次询问」模式下请求弹选择框。 */
    private val onAskWebUi: () -> Unit,
) {
    val isRunning: Boolean get() = phase == DshPhase.RUNNING
    val isBusy: Boolean
        get() = phase == DshPhase.DOWNLOADING || phase == DshPhase.EXTRACTING || phase == DshPhase.STARTING
    val isError: Boolean get() = phase == DshPhase.ERROR

    /** 运行方式显示名（proot / proroot）。 */
    val runtimeLabel: String get() = if (runtimeId == "proroot") "proroot" else "proot"

    /** 权限通道显示名（已本地化）。 */
    val permLabel: String get() = perm.label(context)

    fun start() = HarnessService.start(context)

    fun stop() = HarnessService.stop(context)

    fun restart() = DshRuntime.restart()

    /**
     * 打开 WebUI。六套布局共用这一个入口，所以打开方式只需在这里分流。
     *
     * 「每次询问」不能在这个非 Composable 方法里弹窗，所以只置一个标志，
     * 由 [ProvideDshHomeState] 里的对话框消费。
     */
    fun openWeb() {
        when (DshWebUi.mode(context)) {
            DshWebUi.MODE_BROWSER -> DshWebUi.openExternal(context, webUrl)
            DshWebUi.MODE_ASK -> onAskWebUi()
            else -> DshWebUi.openInApp(context, webUrl)
        }
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

    // 「每次询问」的选择框
    var askWebUi by remember { mutableStateOf(false) }
    if (askWebUi) {
        DshWebUiModeDialog(
            onDismiss = { askWebUi = false },
            onPick = { mode, remember ->
                askWebUi = false
                if (remember) DshWebUi.setMode(appContext, mode)
                val url = "http://127.0.0.1:${runtime.port}/"
                if (mode == DshWebUi.MODE_BROWSER) DshWebUi.openExternal(appContext, url)
                else DshWebUi.openInApp(appContext, url)
            },
        )
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
            onAskWebUi = { askWebUi = true },
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
    val runtimeLabel = stringResource(R.string.dsh_runtime_version)
    val portLabel = stringResource(R.string.dsh_web_port)
    val modeLabel = stringResource(R.string.dsh_run_mode)
    val permLabel = stringResource(R.string.dsh_permission)
    val sizeLabel = stringResource(R.string.dsh_rootfs_size)
    val webLabel = stringResource(R.string.dsh_webui_address)

    // 容器体积在安装后基本不变，只在「是否已安装」翻转时重算，别每次重组都走一遍磁盘扫描
    val rootfsSize = remember(state.installed) {
        if (state.installed) DshRuntime.rootfsSizeBytes() else 0L
    }

    return buildList {
        state.version?.let { add(DshInfoRow(Icons.Outlined.Layers, runtimeLabel, it)) }
        add(DshInfoRow(Icons.Outlined.Speed, modeLabel, state.runtimeLabel))
        add(DshInfoRow(Icons.Outlined.Security, permLabel, state.permLabel))
        if (state.installed) {
            add(DshInfoRow(Icons.Outlined.Storage, sizeLabel, formatRootfsSize(rootfsSize)))
        }
        if (state.isRunning) {
            add(DshInfoRow(Icons.Outlined.Info, portLabel, state.port.toString()))
            add(DshInfoRow(Icons.Outlined.Language, webLabel, state.webUrl))
        }
    }
}

/** 容器体积的可读写法（B / KB / MB / GB）。 */
private fun formatRootfsSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
    bytes < 1024L * 1024 * 1024 -> String.format("%.1fMB", bytes / (1024.0 * 1024))
    else -> String.format("%.1fGB", bytes / (1024.0 * 1024 * 1024))
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

/**
 * 「每次询问」时的打开方式选择框。
 *
 * 带「记住我的选择」：一个每次都问的对话框如果没法关掉，本身就是烦扰。
 */
@Composable
private fun DshWebUiModeDialog(
    onDismiss: () -> Unit,
    onPick: (mode: String, remember: Boolean) -> Unit,
) {
    var remember by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dsh_webui_mode_ask_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.dsh_webui_mode_ask_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { remember = !remember },
                ) {
                    Checkbox(checked = remember, onCheckedChange = { remember = it })
                    Text(
                        text = stringResource(R.string.dsh_webui_mode_remember),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(DshWebUi.MODE_IN_APP, remember) }) {
                Text(stringResource(R.string.dsh_webui_mode_in_app))
            }
        },
        dismissButton = {
            TextButton(onClick = { onPick(DshWebUi.MODE_BROWSER, remember) }) {
                Text(stringResource(R.string.dsh_webui_mode_browser))
            }
        },
    )
}

/** 启动日志卡：等宽字体滚动 + 右上角复制按钮。六套布局共用。 */
@Composable
internal fun DshLogCard() {
    val context = LocalContext.current
    var log by remember { mutableStateOf("") }

    // 轮询而非监听：LogStore 的 tail 只读内存环形缓冲，开销极低。
    // 内容没变就不写 state，避免每秒一次的重组；间隔 2s 进一步降低频率。
    LaunchedEffect(Unit) {
        var last = ""
        while (true) {
            val next = withContext(Dispatchers.IO) { DshRuntime.tailLog(200) }
            if (next != last) {
                last = next
                log = next
            }
            delay(2_000)
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
                IconButton(onClick = {
                    val uri = exportLogFile(context, log)
                    val share = Intent(Intent.ACTION_SEND).apply {
                        putExtra(Intent.EXTRA_STREAM, uri)
                        type = "text/plain"
                        clipData = ClipData.newRawUri(null, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(
                        Intent.createChooser(share, context.getString(R.string.dsh_export_log)),
                    )
                }) {
                    Icon(
                        Icons.Outlined.Share,
                        contentDescription = stringResource(R.string.dsh_export_log),
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
                    text = log.ifEmpty { stringResource(R.string.dsh_log_empty) },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** 把启动日志写成 cache 里的 txt，并返回可分享的 content:// URI。 */
private fun exportLogFile(context: Context, log: String): Uri {
    val dir = File(context.cacheDir, "dsh-logs")
    dir.mkdirs()
    val file = File(dir, "dsh-startup-${System.currentTimeMillis()}.txt")
    file.writeText(log.ifEmpty { "(empty)" })
    return FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
}


