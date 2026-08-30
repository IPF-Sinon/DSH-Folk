package me.bmax.apatch.ui.screen.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.dsh.AdbBridge
import me.bmax.apatch.dsh.ContainerRuntime
import me.bmax.apatch.dsh.DshEnv
import me.bmax.apatch.dsh.DshRuntime
import me.bmax.apatch.dsh.DshSource
import me.bmax.apatch.dsh.PermissionManager
import me.bmax.apatch.ui.DshWebUi
import me.bmax.apatch.ui.screen.PluginProgressHost
import me.bmax.apatch.ui.viewmodel.DshPluginViewModel
import me.bmax.apatch.util.ui.LocalSnackbarHost
import me.bmax.apatch.util.ui.NavigationBarsSpacer
import rikka.shizuku.Shizuku

/**
 * 「功能」设置页：配置 DSH 的运行方式与权限通道。
 *
 * 权限通道是**探测**出来的，不是这里开出来的 —— root / Shizuku 由设备上已有的实现提供，
 * 这一页只做三件事：显示探测结果、代为申请 Shizuku 授权、驱动无线 ADB 配对。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun FunctionSettingsScreen(navigator: DestinationsNavigator, highlightKey: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackBarHost = LocalSnackbarHost.current
    val perm by PermissionManager.status.collectAsStateWithLifecycle()
    // 插件依赖重建复用插件页那套进度对话框与忙碌锁
    val pluginViewModel = viewModel<DshPluginViewModel>()

    val dshPrefs = context.getSharedPreferences(DshEnv.PREF, android.content.Context.MODE_PRIVATE)

    var runtimeId by rememberSaveable { mutableStateOf(DshRuntime.runtimeId()) }
    // null = 自动（按优先级）。存的是字符串，rememberSaveable 不能直接存 enum?
    var permPrefName by rememberSaveable {
        mutableStateOf(
            dshPrefs.getString(DshEnv.KEY_PERM_CHANNEL, PermissionManager.PREF_AUTO)
                ?: PermissionManager.PREF_AUTO
        )
    }
    var webuiMode by rememberSaveable {
        mutableStateOf(dshPrefs.getString(DshEnv.KEY_WEBUI_MODE, DshWebUi.MODE_IN_APP) ?: DshWebUi.MODE_IN_APP)
    }
    var autostart by rememberSaveable { mutableStateOf(dshPrefs.getBoolean(DshEnv.KEY_AUTOSTART, false)) }
    var downloadSource by rememberSaveable { mutableStateOf(DshSource.setting(context)) }
    var customMetaUrl by rememberSaveable { mutableStateOf(DshSource.customMetaUrl(context)) }
    // 生效源：auto 时是缓存/测速结果。解析要走网络，所以只在 IO 线程算，初值用设置值兜底。
    var effectiveSource by rememberSaveable { mutableStateOf(downloadSource) }
    var speedTesting by rememberSaveable { mutableStateOf(false) }
    var speedResults by rememberSaveable { mutableStateOf(listOf<String>()) }
    var adbPairCode by rememberSaveable { mutableStateOf("") }
    var adbPairPort by rememberSaveable { mutableStateOf("") }
    var adbConnectPort by rememberSaveable { mutableStateOf("") }
    var adbHost by rememberSaveable { mutableStateOf("") }
    var adbBusy by rememberSaveable { mutableStateOf(false) }
    var adbOutput by rememberSaveable { mutableStateOf("") }
    // 授权状态存 rootfs 里的标记文件（adb-shell.py 直接读），不是 SharedPreferences
    var adbShellAllowed by rememberSaveable {
        mutableStateOf(AdbBridge.granted(context, AdbBridge.ShellGrant.WRITE))
    }
    var adbRootAllowed by rememberSaveable {
        mutableStateOf(AdbBridge.granted(context, AdbBridge.ShellGrant.ROOT))
    }

    val runtimeInstalled = DshEnv.isRuntimeInstalled(context)
    // 已装版本从运行时状态读（同一份 prefs，下载成功时写入）
    val runtimeState by DshRuntime.state.collectAsStateWithLifecycle()

    val noSpeedText = stringResource(R.string.dsh_source_no_speed)
    val resultFmt = stringResource(R.string.dsh_source_result)
    val sourceNames = DshSource.let {
        mapOf(
            DshSource.SOURCE_AUTO to stringResource(R.string.dsh_source_auto),
            DshSource.SOURCE_GITHUB to stringResource(R.string.dsh_source_github),
            DshSource.SOURCE_GHPROXY_CF to stringResource(R.string.dsh_source_ghproxy_cf),
            DshSource.SOURCE_GHPROXY_AXISNOW to stringResource(R.string.dsh_source_ghproxy_axisnow),
            DshSource.SOURCE_CUSTOM to stringResource(R.string.dsh_source_custom),
        )
    }

    // proroot 的可用性要读它自己的目录，放 IO 线程算一次即可。
    var prorootAvailable by rememberSaveable { mutableStateOf(false) }
    var prorootReason by rememberSaveable { mutableStateOf("") }

    /**
     * B5：Shizuku 授权后自动重新探测。
     *
     * 原来只调 Shizuku.requestPermission()，从不注册结果回调 —— 用户在弹窗里
     * 点了「允许」，权限卡却还显示未授权，必须手动再点一次「刷新权限」。
     *
     * binder 监听用 sticky 版：用户可能先打开本页、再去启动 Shizuku 服务，
     * 那时才拿得到 binder；而已经拿到时 sticky 会立即回调一次。
     */
    DisposableEffect(Unit) {
        val app = context.applicationContext
        val refresh = { scope.launch(Dispatchers.IO) { PermissionManager.refresh(app) } }
        val onResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }
        val onBinder = Shizuku.OnBinderReceivedListener { refresh() }
        runCatching {
            Shizuku.addRequestPermissionResultListener(onResult)
            Shizuku.addBinderReceivedListenerSticky(onBinder)
        }
        onDispose {
            runCatching {
                Shizuku.removeRequestPermissionResultListener(onResult)
                Shizuku.removeBinderReceivedListener(onBinder)
            }
        }
    }

    LaunchedEffect(Unit) {
        DshRuntime.attach(context.applicationContext)
        withContext(Dispatchers.IO) {
            PermissionManager.refresh(context.applicationContext)
            val proroot = ContainerRuntime.Proroot(
                context.applicationContext,
                ContainerRuntime.Proroot.defaultDir(context.applicationContext),
            )
            val ok = proroot.available()
            val reason = if (ok) "" else proroot.unavailableReason()
            // resolve() 在 auto 且无缓存时会真的测速，所以放在同一个 IO 块里
            val resolved = runCatching { DshSource.resolve(context.applicationContext) }
                .getOrDefault(DshSource.setting(context.applicationContext))
            withContext(Dispatchers.Main) {
                prorootAvailable = ok
                prorootReason = reason
                effectiveSource = resolved
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_category_function),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navigator.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackBarHost) },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.padding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FunctionSettingsContent(
                    runtimeId = runtimeId,
                    onRuntimeIdChange = { id ->
                        runtimeId = id
                        DshRuntime.setRuntimeId(id)
                    },
                    prorootAvailable = prorootAvailable,
                    prorootUnavailableReason = prorootReason,
                    autostart = autostart,
                    onAutostartChange = { on ->
                        autostart = on
                        dshPrefs.edit().putBoolean(DshEnv.KEY_AUTOSTART, on).apply()
                    },
                    downloadSource = downloadSource,
                    onDownloadSourceChange = { src ->
                        downloadSource = src
                        DshSource.setSetting(context, src)
                        if (src != DshSource.SOURCE_AUTO) effectiveSource = src
                        else scope.launch(Dispatchers.IO) {
                            val r = runCatching { DshSource.resolve(context.applicationContext) }
                                .getOrDefault(src)
                            withContext(Dispatchers.Main) { effectiveSource = r }
                        }
                    },
                    customMetaUrl = customMetaUrl,
                    onCustomMetaUrlChange = { url ->
                        customMetaUrl = url
                        DshSource.setCustomMetaUrl(context, url)
                    },
                    effectiveSource = effectiveSource,
                    speedTesting = speedTesting,
                    speedResults = speedResults,
                    onSpeedTest = {
                        speedTesting = true
                        scope.launch(Dispatchers.IO) {
                            val results = runCatching { DshSource.speedTest() }.getOrDefault(emptyList())
                            val lines = results
                                .sortedBy { it.estimatedMs }
                                .map { r ->
                                    val speed = if (r.speedKBps > 0.0) {
                                        String.format("%.0f KB/s", r.speedKBps)
                                    } else {
                                        noSpeedText
                                    }
                                    val latency = if (r.latencyMs >= Long.MAX_VALUE / 4) -1
                                    else r.latencyMs.toInt()
                                    String.format(
                                        resultFmt,
                                        sourceNames[r.source] ?: r.source,
                                        latency,
                                        speed,
                                    )
                                }
                            val picked = runCatching {
                                DshSource.pickBest(results, context.applicationContext)
                            }.getOrDefault(effectiveSource)
                            withContext(Dispatchers.Main) {
                                speedResults = lines
                                speedTesting = false
                                if (downloadSource == DshSource.SOURCE_AUTO) effectiveSource = picked
                            }
                        }
                    },
                    perm = perm,
                    onRefreshPerm = {
                        // 用户主动点刷新才允许弹 su 授权框（refresh 默认不弹）
                        scope.launch(Dispatchers.IO) {
                            PermissionManager.refresh(context.applicationContext, allowRootPrompt = true)
                        }
                    },
                    onRequestShizuku = {
                        runCatching { Shizuku.requestPermission(SHIZUKU_REQ_CODE) }
                            .onFailure {
                                scope.launch {
                                    snackBarHost.showSnackbar(it.message ?: "Shizuku request failed")
                                }
                            }
                    },
                    webuiMode = webuiMode,
                    onWebuiModeChange = { mode ->
                        webuiMode = mode
                        DshWebUi.setMode(context.applicationContext, mode)
                    },
                    permPrefName = permPrefName,
                    onPermPrefChange = { name ->
                        permPrefName = name
                        // 手动选通道只是显示/菜单偏好，容器执行仍走 proot/proroot；
                        // 不选 = 自动按 root > shizuku > adb 的优先级挑一条可用的
                        val ch = when (name) {
                            PermissionManager.PREF_ROOT -> PermissionManager.Channel.ROOT
                            PermissionManager.PREF_SHIZUKU -> PermissionManager.Channel.SHIZUKU
                            PermissionManager.PREF_ADB -> PermissionManager.Channel.ADB
                            else -> null
                        }
                        PermissionManager.setPreference(context.applicationContext, ch)
                        scope.launch(Dispatchers.IO) {
                            PermissionManager.refresh(context.applicationContext)
                        }
                    },
                    runtimeInstalled = runtimeInstalled,
                    runtimeVersion = runtimeState.runtimeVersion ?: "",
                    onReinstallRuntime = {
                        // 重装是长任务，DshRuntime 自己起协程并把进度打进启动日志；
                        // 回首页就能看到进度，这里不再阻塞设置页
                        DshRuntime.reinstallRuntime()
                    },
                    onRepairPlugins = { pluginViewModel.repairStore() },
                    repairBusy = pluginViewModel.installing,
                    adbPairCode = adbPairCode,
                    onAdbPairCodeChange = { adbPairCode = it.filter { c -> c.isDigit() }.take(6) },
                    adbPairPort = adbPairPort,
                    onAdbPairPortChange = { adbPairPort = it.filter { c -> c.isDigit() }.take(5) },
                    adbConnectPort = adbConnectPort,
                    onAdbConnectPortChange = { adbConnectPort = it.filter { c -> c.isDigit() }.take(5) },
                    adbHost = adbHost,
                    onAdbHostChange = { adbHost = it.trim() },
                    adbBusy = adbBusy,
                    adbOutput = adbOutput,
                    adbShellAllowed = adbShellAllowed,
                    onAdbShellAllowedChange = { on ->
                        AdbBridge.setGranted(context, AdbBridge.ShellGrant.WRITE, on)
                        adbShellAllowed = AdbBridge.granted(context, AdbBridge.ShellGrant.WRITE)
                    },
                    adbRootAllowed = adbRootAllowed,
                    onAdbRootAllowedChange = { on ->
                        AdbBridge.setGranted(context, AdbBridge.ShellGrant.ROOT, on)
                        adbRootAllowed = AdbBridge.granted(context, AdbBridge.ShellGrant.ROOT)
                    },
                    onDisconnectAdb = {
                        adbBusy = true
                        scope.launch(Dispatchers.IO) {
                            val out = runCatching {
                                AdbBridge.disconnect(context.applicationContext)
                            }.getOrDefault("")
                            PermissionManager.refresh(context.applicationContext)
                            withContext(Dispatchers.Main) {
                                adbOutput = if (out.contains("DISCONNECTED")) {
                                    context.getString(R.string.dsh_adb_disconnected)
                                } else {
                                    context.getString(R.string.dsh_adb_disconnect_failed)
                                }
                                adbBusy = false
                            }
                        }
                    },
                    onPair = {
                        adbBusy = true
                        scope.launch(Dispatchers.IO) {
                            val out = runCatching {
                                // 配对脚本必须先在容器里就位，且依赖装好，否则直接报 ImportError
                                if (!AdbBridge.injected()) AdbBridge.inject(context.applicationContext)
                                if (!AdbBridge.depsOk()) AdbBridge.installDeps(context.applicationContext)
                                AdbBridge.pair(adbPairCode, adbPairPort, adbConnectPort, adbHost)
                            }.getOrElse { it.message ?: "pair failed" }
                            PermissionManager.refresh(context.applicationContext)
                            withContext(Dispatchers.Main) {
                                adbOutput = out
                                adbBusy = false
                            }
                        }
                    },
                    onOpenDevSettings = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    highlightKey = highlightKey,
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
            item { NavigationBarsSpacer() }
        }
    }

    // 重建插件依赖的实时日志（与插件页共用同一套对话框）
    PluginProgressHost(pluginViewModel)
}

private const val SHIZUKU_REQ_CODE = 4210
