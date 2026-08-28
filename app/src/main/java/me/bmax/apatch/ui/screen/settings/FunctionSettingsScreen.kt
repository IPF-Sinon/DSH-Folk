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

    val dshPrefs = context.getSharedPreferences(DshEnv.PREF, android.content.Context.MODE_PRIVATE)

    var runtimeId by rememberSaveable { mutableStateOf(DshRuntime.runtimeId()) }
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
                        scope.launch(Dispatchers.IO) {
                            PermissionManager.refresh(context.applicationContext)
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
                    runtimeInstalled = runtimeInstalled,
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
                    onInstallAdbDeps = {
                        adbBusy = true
                        scope.launch(Dispatchers.IO) {
                            val out = runCatching {
                                AdbBridge.inject(context.applicationContext)
                                AdbBridge.installDeps(context.applicationContext)
                            }.getOrElse { it.message ?: "install failed" }
                            withContext(Dispatchers.Main) {
                                adbOutput = out
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
}

private const val SHIZUKU_REQ_CODE = 4210
