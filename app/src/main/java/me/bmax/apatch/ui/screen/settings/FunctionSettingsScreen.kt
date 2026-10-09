package me.bmax.apatch.ui.screen.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.NavGraphs
import com.ramcosta.composedestinations.generated.destinations.GeneralSettingsScreenDestination
import com.ramcosta.composedestinations.generated.destinations.PermissionHubScreenDestination
import com.ramcosta.composedestinations.generated.destinations.FileAccessScreenDestination
import com.ramcosta.composedestinations.generated.destinations.DshDeepCleanScreenDestination
import com.ramcosta.composedestinations.generated.destinations.HomeScreenDestination
import com.ramcosta.composedestinations.generated.destinations.PermissionLogScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.dsh.ContainerRuntime
import me.bmax.apatch.dsh.DshAutostart
import me.bmax.apatch.dsh.DshConfigBackup
import me.bmax.apatch.dsh.DshEnv
import me.bmax.apatch.dsh.DshFileAccess
import me.bmax.apatch.dsh.ExportPlan
import me.bmax.apatch.dsh.DshHostPrompt
import me.bmax.apatch.dsh.DshNativeBridge
import me.bmax.apatch.dsh.DshRuntime
import me.bmax.apatch.dsh.DshSource
import me.bmax.apatch.dsh.PermissionManager
import me.bmax.apatch.ui.DshWebUi
import me.bmax.apatch.ui.screen.PluginProgressHost
import me.bmax.apatch.ui.viewmodel.DshPluginViewModel
import me.bmax.apatch.util.PermissionUtils
import me.bmax.apatch.util.ui.LocalSnackbarHost
import me.bmax.apatch.util.ui.NavigationBarsSpacer
import rikka.shizuku.Shizuku

/**
 * 「功能」设置页：配置 DSH 的运行方式与权限通道。
 *
 * 权限通道是**探测**出来的，不是这里开出来的 —— root / Shizuku 由设备上已有的实现提供，
 * 这一页只做三件事：显示探测结果、代为申请 Shizuku 授权、驱动无线 ADB 配对。
 */
@Destination<RootGraph>
@Composable
fun FunctionSettingsScreen(navigator: DestinationsNavigator, highlightKey: String? = null) {
    DshSettingsScreen(navigator, highlightKey, permissionOnly = false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DshSettingsScreen(
    navigator: DestinationsNavigator,
    highlightKey: String?,
    permissionOnly: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackBarHost = LocalSnackbarHost.current
    // 插件依赖重建复用插件页那套进度对话框与忙碌锁
    val pluginViewModel = viewModel<DshPluginViewModel>()

    val dshPrefs = context.getSharedPreferences(DshEnv.PREF, android.content.Context.MODE_PRIVATE)

    var runtimeBeta by rememberSaveable { mutableStateOf(DshSource.acceptRuntimeBeta(context)) }
    // 精简版与测试版是两个正交开关（精简版一样有测试通道），所以不做成三选一
    var runtimeSlim by rememberSaveable { mutableStateOf(DshSource.acceptRuntimeSlim(context)) }
    // 自动检查更新是运行时自己的开关（默认开），与 App 那个自动检查互不影响
    var runtimeAutoCheck by rememberSaveable { mutableStateOf(DshRuntime.autoCheckEnabled(context)) }
    var runtimeCheckRevision by rememberSaveable { mutableStateOf(0) }
    var importCandidate by remember { mutableStateOf<RuntimeImportCandidate?>(null) }
    val runtimeImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val metadata = context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) null else {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
                    (name ?: uri.lastPathSegment ?: "runtime.tar.gz") to size
                }
            } ?: ((uri.lastPathSegment ?: "runtime.tar.gz") to -1L)
            withContext(Dispatchers.Main) {
                importCandidate = RuntimeImportCandidate(uri, metadata.first, metadata.second)
            }
        }
    }
    var runtimeId by rememberSaveable { mutableStateOf(DshRuntime.runtimeId()) }
    var webuiMode by rememberSaveable {
        mutableStateOf(dshPrefs.getString(DshEnv.KEY_WEBUI_MODE, DshWebUi.MODE_IN_APP) ?: DshWebUi.MODE_IN_APP)
    }
    // 画中画主开关（默认开）：关掉后悬浮菜单不显示按钮、也不自动进小窗
    var webuiPipMain by rememberSaveable { mutableStateOf(DshEnv.webuiPipMain(context)) }
    // 离开应用时自动缩成画中画小窗（默认开）：只管「自动」这条路，手动按钮不受它影响。
    // 读的是**副开关自己的原始值**（不是 DshEnv.webuiPipAuto —— 那个已经把主开关乘进去了），
    // 否则主开关关着时这一行会显示成「本来就是关的」。
    var webuiPipAuto by rememberSaveable {
        mutableStateOf(dshPrefs.getBoolean(DshEnv.KEY_WEBUI_PIP_AUTO, true))
    }
    // 手机回车换行（默认开）：它管的是 WebUI 输入框里回车是换行还是发送
    // WebView 内核版本只用于显示；读包信息不会触发 WebView 加载，但也没必要每次重组都读
    // 自启动：方式 + 是否同时拉容器。Mode 不是 Parcelable，用 remember 就够
    // （返回本页会重读 prefs，那才是权威值）。
    var autostartMode by remember { mutableStateOf(DshAutostart.mode(context)) }
    var autostartContainer by remember { mutableStateOf(DshAutostart.startContainer(context)) }
    // 应用启动行为：打开 App 时自启服务、服务就绪后自动打开页面（两者默认关）
    var autoStartOnLaunch by remember { mutableStateOf(DshRuntime.autoStartOnLaunch()) }
    var autoOpenWebUi by remember { mutableStateOf(DshRuntime.autoOpenWebUi()) }
    // 脚本状态要走 root shell 读（/data/adb 对普通应用连 exists() 都是 false），
    // 所以只在 IO 线程查，初值按「没装」显示 —— 宁可少说也不要假称装好了。
    var scriptInstalled by remember { mutableStateOf(false) }
    var scriptOutdated by remember { mutableStateOf(false) }
    var scriptBusy by remember { mutableStateOf(false) }
    var a11yEnabled by remember { mutableStateOf(DshAutostart.a11yEnabled(context)) }
    var port by rememberSaveable { mutableStateOf(DshRuntime.port()) }
    var lanEnabled by rememberSaveable { mutableStateOf(DshRuntime.lanEnabled()) }
    var raceMaster by rememberSaveable { mutableStateOf(DshRuntime.raceMasterEnabled()) }
    var racePlugins by rememberSaveable { mutableStateOf(DshRuntime.raceEnabled(DshRuntime.RACE_PLUGINS)) }
    var raceAppUpdate by rememberSaveable { mutableStateOf(DshRuntime.raceEnabled(DshRuntime.RACE_APP_UPDATE)) }
    var raceRuntime by rememberSaveable { mutableStateOf(DshRuntime.raceEnabled(DshRuntime.RACE_RUNTIME)) }
    var verifyAfterInstall by rememberSaveable {
        mutableStateOf(dshPrefs.getBoolean(DshEnv.KEY_VERIFY_AFTER_INSTALL, true))
    }
    // 竞速通道里勾选的镜像线路（三条通道共用这一份，见 DshSource.enabledMirrors）
    var raceMirrors by rememberSaveable { mutableStateOf(DshSource.enabledMirrors()) }
    // 「自定义源」= 用用户自己的 metadata 地址（DshSource.SOURCE_CUSTOM），其余情况一律 auto
    var customSourceEnabled by rememberSaveable { mutableStateOf(DshSource.setting(context) == DshSource.SOURCE_CUSTOM) }
    var customMetaUrl by rememberSaveable { mutableStateOf(DshSource.customMetaUrl(context)) }
    // 生效源：竞速时是测速/缓存结果。解析要走网络，所以只在 IO 线程算，初值用设置值兜底。
    var effectiveSource by rememberSaveable { mutableStateOf(DshSource.setting(context)) }
    var speedTesting by rememberSaveable { mutableStateOf(false) }
    // 存的是测速**原始结果**而不是拼好的字符串：展示全在竞速弹窗里（每条线路贴在自己那一行）
    var speedResults by remember { mutableStateOf<List<DshSource.SpeedResult>>(emptyList()) }
    // 每一项能力的权限是否齐了。任何一项都可能在系统设置里被撤销，而撤销之后开关
    // 还是亮的 —— 所以必须每次回到本页重读（见下面的 LifecycleResumeEffect），
    // 不能只在首次组合时读一次。
    var capsWithPermission by remember {
        mutableStateOf(DshNativeBridge.capsWithPermission(context))
    }
    var allFilesGranted by remember {
        mutableStateOf(PermissionUtils.hasAllFilesAccess(context))
    }
    var storageMount by remember { mutableStateOf(DshFileAccess.mountEnabled(context)) }

    /**
     * 运行时权限申请器。
     *
     * 只为「所有文件访问」在 Android 10 及以下那条路径服务（见 [openAllFilesSettings]）——
     * 该权限在 R+ 上是 appop，运行时申请永远返回拒绝，只能跳系统页。
     * 用 launcher 而不是直接 startActivity：API 26–29 上它走的是普通运行时权限。
     */
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        capsWithPermission = DshNativeBridge.capsWithPermission(context)
        // 权限变了，提示词里的能力清单也得跟着变
        DshHostPrompt.writeFacts(context.applicationContext)
    }

    /**
     * 跳「所有文件访问」的系统设置页。
     *
     * 这一项**不能**用 requestPermissions：MANAGE_EXTERNAL_STORAGE 的 protectionLevel 是
     * `signature|appop|preinstalled`，运行时申请永远返回拒绝。带包名的 Intent 在少数
     * ROM 上不被支持，所以失败后退回不带包名的全局列表页。
     */
    val openAllFilesSettings: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val withPackage = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData(Uri.fromParts("package", context.packageName, null))
            val ok = runCatching { context.startActivity(withPackage) }.isSuccess
            if (!ok) {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        } else {
            // Android 10 及以下没有这个页面，走的是普通运行时权限
            permissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                )
            )
        }
    }

    // 安装状态得是个 state 而不是每次重组现算：重组不一定发生，而它会在别处变化 ——
    // 用户可能刚从首页装完运行时回来（见下面的 LifecycleResumeEffect）。
    var runtimeInstalled by remember { mutableStateOf(DshEnv.isRuntimeInstalled(context)) }
    // 已装版本从运行时状态读（同一份 prefs，下载成功时写入）
    val runtimeState by DshRuntime.state.collectAsStateWithLifecycle()


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
        val refresh = {
            scope.launch(Dispatchers.IO) {
                PermissionManager.refresh(app)
                // 状态卡片自己会跟着 StateFlow 变，但**容器侧看不到** —— 提示词里那段
                // 「用户有没有特权、是哪条通道」是按事实文件渲染的。少了这一行，用户刚
                // 给 Shizuku 授权、agent 那边还是旧答案（要等 App 重启或回到本页）。
                DshHostPrompt.writeFacts(app)
            }
        }
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

    // 权限与原生能力开关都可能在系统设置 / 另一处被改，回到本页时重读一遍
    LifecycleResumeEffect(Unit) {
        // 这些状态都可能在系统设置页里被改（「所有文件访问」「修改系统设置」
        // 「勿扰访问」「安装未知应用」**只能**在那里改），而用户从设置页返回走的正是
        // onResume —— 少了这几行，回来看到的还是旧状态。
        capsWithPermission = DshNativeBridge.capsWithPermission(context)
        allFilesGranted = PermissionUtils.hasAllFilesAccess(context)
        // 无障碍开关同样只能在系统设置里改。少了这一行，用户点「打开无障碍设置」、开好、
        // 返回，看到的还是「服务尚未启用」—— 他会以为没生效，再去开一遍。
        a11yEnabled = DshAutostart.a11yEnabled(context)
        // 运行时可能刚在首页装好，那句「还没装所以自启没东西可拉」得跟着消失
        runtimeInstalled = DshEnv.isRuntimeInstalled(context)
        // 权限是提示词里的事实（没授权时对应能力会失败），跟着一起刷新
        DshHostPrompt.writeFacts(context.applicationContext)
        onPauseOrDispose { }
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

    // 运行时替换（重装/切版本/导入）前的「建议先备份」拦截：不为 null 时先弹提示，用户选「继续」才跑
    var pendingRuntimeOp by remember { mutableStateOf<(() -> Unit)?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (permissionOnly) R.string.settings_category_security
                            else R.string.settings_category_function
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                actions = {
                    if (permissionOnly) {
                        IconButton(onClick = { navigator.navigate(PermissionLogScreenDestination) }) {
                            Icon(Icons.Outlined.ReceiptLong, contentDescription = stringResource(R.string.dsh_permission_log_title))
                        }
                    }
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
                    autostartMode = autostartMode,
                    onAutostartModeChange = { m ->
                        val prev = autostartMode
                        autostartMode = m
                        DshAutostart.setMode(context, m)
                        // 从脚本模式切走时把脚本删掉。不删的话它下次开机还会跑 ——
                        // trigger() 会因为模式不符而拒绝启动，但一个明明「已关掉」的开机
                        // 脚本继续存在于 service.d 里，本身就是件不该发生的事。
                        if (prev == DshAutostart.Mode.SCRIPT && m != DshAutostart.Mode.SCRIPT) {
                            scope.launch(Dispatchers.IO) {
                                val r = DshAutostart.removeScript(context)
                                val installed = DshAutostart.scriptInstalled(context)
                                withContext(Dispatchers.Main) {
                                    scriptInstalled = installed
                                    scriptOutdated = false
                                    // 只在删失败时打扰用户：成功是他刚才那一下的预期结果。
                                    if (!r.ok) {
                                        snackBarHost.showSnackbar(
                                            context.getString(r.messageRes, DshAutostart.scriptPath())
                                        )
                                    }
                                }
                            }
                        }
                        if (m == DshAutostart.Mode.SCRIPT || m == DshAutostart.Mode.ACCESSIBILITY) {
                            scope.launch(Dispatchers.IO) {
                                val installed = DshAutostart.scriptInstalled(context)
                                val outdated = installed && DshAutostart.scriptNeedsUpdate(context)
                                withContext(Dispatchers.Main) {
                                    scriptInstalled = installed
                                    scriptOutdated = outdated
                                }
                            }
                        }
                    },
                    autostartContainer = autostartContainer,
                    onAutostartContainerChange = { on ->
                        autostartContainer = on
                        DshAutostart.setStartContainer(context, on)
                    },
                    autoStartOnLaunch = autoStartOnLaunch,
                    onAutoStartOnLaunchChange = { on ->
                        autoStartOnLaunch = on
                        DshRuntime.setAutoStartOnLaunch(on)
                    },
                    autoOpenWebUi = autoOpenWebUi,
                    onAutoOpenWebUiChange = { on ->
                        autoOpenWebUi = on
                        DshRuntime.setAutoOpenWebUi(on)
                    },
                    autostartScriptInstalled = scriptInstalled,
                    autostartScriptOutdated = scriptOutdated,
                    autostartScriptBusy = scriptBusy,
                    onInstallAutostartScript = {
                        scriptBusy = true
                        scope.launch(Dispatchers.IO) {
                            val r = DshAutostart.installScript(context)
                            val installed = DshAutostart.scriptInstalled(context)
                            val outdated = installed && DshAutostart.scriptNeedsUpdate(context)
                            withContext(Dispatchers.Main) {
                                scriptBusy = false
                                scriptInstalled = installed
                                scriptOutdated = outdated
                                snackBarHost.showSnackbar(
                                    context.getString(r.messageRes, DshAutostart.scriptPath())
                                )
                            }
                        }
                    },
                    onRemoveAutostartScript = {
                        scriptBusy = true
                        scope.launch(Dispatchers.IO) {
                            val r = DshAutostart.removeScript(context)
                            val installed = DshAutostart.scriptInstalled(context)
                            withContext(Dispatchers.Main) {
                                scriptBusy = false
                                scriptInstalled = installed
                                scriptOutdated = false
                                snackBarHost.showSnackbar(
                                    context.getString(r.messageRes, DshAutostart.scriptPath())
                                )
                            }
                        }
                    },
                    autostartA11yEnabled = a11yEnabled,
                    onOpenA11ySettings = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    port = port,
                    onPortChange = { p ->
                        port = p
                        DshRuntime.setPort(p)
                    },
                    lanEnabled = lanEnabled,
                    onLanChange = { on ->
                        lanEnabled = on
                        DshRuntime.setLanEnabled(on)
                    },
                    raceMasterEnabled = raceMaster,
                    onRaceMasterChange = { on ->
                        raceMaster = on
                        DshRuntime.setRaceMasterEnabled(on)
                    },
                    racePlugins = racePlugins,
                    raceAppUpdate = raceAppUpdate,
                    raceRuntime = raceRuntime,
                    onRaceChannelChange = { channel, on ->
                        DshRuntime.setRaceEnabled(channel, on)
                        when (channel) {
                            DshRuntime.RACE_PLUGINS -> racePlugins = on
                            DshRuntime.RACE_APP_UPDATE -> raceAppUpdate = on
                            DshRuntime.RACE_RUNTIME -> raceRuntime = on
                        }
                    },
                    raceMirrors = raceMirrors,
                    onRaceMirrorToggle = { id, on ->
                        val next = if (on) raceMirrors + id else raceMirrors - id
                        raceMirrors = next
                        DshSource.setEnabledMirrors(next)
                        // 勾选变了，生效源就不再有效：重解析一次（会用到刚写下的勾选）
                        scope.launch(Dispatchers.IO) {
                            val r = runCatching { DshSource.resolve(context.applicationContext) }
                                .getOrDefault(effectiveSource)
                            withContext(Dispatchers.Main) { effectiveSource = r }
                        }
                    },
                    customSourceEnabled = customSourceEnabled,
                    onCustomSourceToggle = { on ->
                        customSourceEnabled = on
                        DshSource.setSetting(context, if (on) DshSource.SOURCE_CUSTOM else DshSource.SOURCE_AUTO)
                        scope.launch(Dispatchers.IO) {
                            val r = runCatching { DshSource.resolve(context.applicationContext) }
                                .getOrDefault(if (on) DshSource.SOURCE_CUSTOM else DshSource.SOURCE_AUTO)
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
                            // 手动测速：全部线路都测吞吐，并且每条测完就回报一次（先延迟后吞吐，
                            // 逐条填进弹窗里各自那一行），用户不用对着「测速中」干等十几秒
                            val results = runCatching {
                                DshSource.speedTest(probeAll = true) { partial ->
                                    scope.launch(Dispatchers.Main.immediate) { speedResults = partial }
                                }
                            }.getOrDefault(emptyList())
                            val picked = runCatching {
                                DshSource.pickBest(results, context.applicationContext)
                            }.getOrDefault(effectiveSource)
                            withContext(Dispatchers.Main) {
                                speedResults = results
                                speedTesting = false
                                effectiveSource = picked
                            }
                        }
                    },
                    webuiMode = webuiMode,
                    onWebuiModeChange = { mode ->
                        webuiMode = mode
                        DshWebUi.setMode(context.applicationContext, mode)
                    },
                    webuiPipMain = webuiPipMain,
                    onWebuiPipMainChange = { on ->
                        webuiPipMain = on
                        DshEnv.setWebuiPipMain(context, on)
                    },
                    webuiPipAuto = webuiPipAuto,
                    onWebuiPipAutoChange = { on ->
                        webuiPipAuto = on
                        dshPrefs.edit().putBoolean(DshEnv.KEY_WEBUI_PIP_AUTO, on).apply()
                    },
                    capsWithPermission = capsWithPermission,
                    allFilesGranted = allFilesGranted,
                    onOpenAllFilesSettings = { openAllFilesSettings() },
                    onOpenFileAccess = { navigator.navigate(FileAccessScreenDestination) },
                    onOpenDeepClean = { navigator.navigate(DshDeepCleanScreenDestination) },
                    onOpenPermissionHub = { navigator.navigate(PermissionHubScreenDestination) },
                    mountEnabled = storageMount,
                    onSetMount = { on ->
                        storageMount = on
                        DshFileAccess.setMountEnabled(context, on)
                        DshHostPrompt.writeFacts(context)
                    },
                    runtimeInstalled = runtimeInstalled,
                    runtimeVersion = runtimeState.runtimeVersion ?: "",
                    appUpdateRequired = runtimeState.appUpdateRequired,
                    requiredAppVersion = runtimeState.requiredAppVersion,
                    onGoUpdateApp = {
                        navigator.navigate(GeneralSettingsScreenDestination("general_check_update"))
                    },
                    onReinstallRuntime = { preserve ->
                        // 会替换整个 rootfs（rootfs/tmp 等不在保留清单）——先弹「建议备份」，用户决定后再跑
                        pendingRuntimeOp = {
                            DshRuntime.reinstallRuntime(preserve)
                            navigator.navigate(HomeScreenDestination) {
                                popUpTo(NavGraphs.root)
                                launchSingleTop = true
                            }
                        }
                    },
                    runtimeCheckRevision = runtimeCheckRevision,
                    onCheckRuntimeUpdateRequested = { runtimeCheckRevision++ },
                    onCheckRuntimeUpdate = { DshRuntime.checkRuntimeUpdate() },
                    onListRuntimeVersions = { DshRuntime.listRuntimeVersions() },
                    onSwitchRuntimeVersion = { entry ->
                        pendingRuntimeOp = {
                            DshRuntime.switchRuntimeVersion(entry, true)
                            // 与重装同理：下载/解压的进度在首页，切版本后立刻回首页看着它走
                            navigator.navigate(HomeScreenDestination) {
                                popUpTo(NavGraphs.root)
                                launchSingleTop = true
                            }
                        }
                    },
                    onImportRuntime = {
                        runtimeImportLauncher.launch(arrayOf("application/gzip", "application/x-gzip", "application/x-tar", "application/octet-stream"))
                    },
                    runtimeAutoCheck = runtimeAutoCheck,
                    onRuntimeAutoCheckChange = { on ->
                        runtimeAutoCheck = on
                        DshRuntime.setAutoCheckEnabled(context, on)
                    },
                    runtimeBeta = runtimeBeta,
                    onRuntimeBetaChange = { on ->
                        runtimeBeta = on
                        DshSource.setAcceptRuntimeBeta(context, on)
                    },
                    runtimeSlim = runtimeSlim,
                    onRuntimeSlimChange = { on ->
                        runtimeSlim = on
                        DshSource.setAcceptRuntimeSlim(context, on)
                    },
                    onRepairPlugins = { pluginViewModel.repairStore() },
                    repairBusy = pluginViewModel.installing,
                    verifyAfterInstall = verifyAfterInstall,
                    onVerifyAfterInstallChange = { on ->
                        verifyAfterInstall = on
                        dshPrefs.edit().putBoolean(DshEnv.KEY_VERIFY_AFTER_INSTALL, on).apply()
                    },
                    permissionOnly = permissionOnly,
                    highlightKey = highlightKey,
                )
            }
            if (permissionOnly) {
                item {
                    SecuritySettingsContent(
                        snackBarHost = snackBarHost,
                        highlightKey = highlightKey,
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
            item { NavigationBarsSpacer() }
        }
    }

    importCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { importCandidate = null },
            title = { Text(stringResource(R.string.dsh_runtime_import_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.dsh_runtime_import_confirm_text,
                        candidate.name,
                        if (candidate.size >= 0) Formatter.formatFileSize(context, candidate.size)
                        else stringResource(R.string.dsh_runtime_import_size_unknown),
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    importCandidate = null
                    scope.launch(Dispatchers.IO) {
                        val file = File(context.cacheDir, "runtime-import-${System.currentTimeMillis()}.tar.gz")
                        runCatching {
                            context.contentResolver.openInputStream(candidate.uri)!!.use { input ->
                                file.outputStream().use { input.copyTo(it) }
                            }
                        }.onSuccess {
                            pendingRuntimeOp = { DshRuntime.importRuntime(file, preserveData = true) }
                        }
                            .onFailure { file.delete() }
                    }
                }) { Text(stringResource(R.string.dsh_runtime_import_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { importCandidate = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }

    // 运行时替换前的「建议先备份」：复用备份页的导出组件，用户可先导出再继续
    pendingRuntimeOp?.let { op ->
        RuntimeBackupAdviceDialog(
            onContinue = {
                pendingRuntimeOp = null
                op()
            },
            onCancel = { pendingRuntimeOp = null },
        )
    }

    // 重建插件依赖的实时日志（与插件页共用同一套对话框）
    PluginProgressHost(pluginViewModel)
}

/**
 * 运行时替换（重装 / 切版本 / 导入）前的「建议先备份」提示。
 *
 * 这些操作会换掉整个 rootfs（且 `rootfs/tmp` 等不在保留清单），出问题不易回退，所以先提示。
 * 「导出备份」就地打开备份页同一套导出组件（[BackupExportOptionsDialog]），导完仍留在本框，
 * 用户再点「继续」跑真正的运行时操作；导出走 [DshConfigBackup.exportArchive]（与备份页同一通路）。
 *
 * `internal` 而不是 `private`：首页那条运行时更新提示（MainActivity 的 Activity 级弹窗，可能在
 * 任何页面弹出）也走它 —— 两条入口必须给同一份提示，否则从首页点更新的人看不到备份建议。
 */
@Composable
internal fun RuntimeBackupAdviceDialog(
    onContinue: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showExport by remember { mutableStateOf(false) }
    var scopeIndex by rememberSaveable { mutableStateOf(3) }
    var sessionLimit by rememberSaveable { mutableStateOf(0) }
    var password by remember { mutableStateOf("") }
    var exporting by remember { mutableStateOf(false) }
    var exportMsg by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.dsh_runtime_backup_advice_title)) },
        text = {
            Column {
                Text(stringResource(R.string.dsh_runtime_backup_advice_text))
                if (exportMsg.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = exportMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onContinue, enabled = !exporting) {
                Text(stringResource(R.string.dsh_runtime_backup_advice_continue))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { showExport = true }, enabled = !exporting) {
                    Text(stringResource(R.string.dsh_runtime_backup_advice_export))
                }
                TextButton(onClick = onCancel, enabled = !exporting) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        },
    )

    if (showExport) {
        BackupExportOptionsDialog(
            dshBusy = exporting,
            scopeIndex = scopeIndex,
            onScopeIndexChange = { scopeIndex = it },
            sessionLimit = sessionLimit,
            onSessionLimitChange = { sessionLimit = it },
            password = password,
            onPasswordChange = { password = it },
            onDismiss = { showExport = false },
            onConfirm = { plan ->
                showExport = false
                exporting = true
                exportMsg = context.getString(R.string.dsh_backup_exporting)
                scope.launch(Dispatchers.IO) {
                    val status = DshConfigBackup.status(context)
                    val text = if (!status.ready) {
                        status.error.ifEmpty { context.getString(R.string.dsh_backup_plugin_missing) }
                    } else {
                        val r = DshConfigBackup.exportArchive(
                            context,
                            plan,
                            onLine = { line -> withContext(Dispatchers.Main) { exportMsg = line } },
                        )
                        if (!r.ok) r.message else {
                            val loc = r.location.ifBlank { r.file?.absolutePath ?: "" }
                            r.file?.takeIf { it.absolutePath != r.location }?.delete()
                            "${r.message}\n$loc"
                        }
                    }
                    withContext(Dispatchers.Main) {
                        exportMsg = text
                        exporting = false
                    }
                }
            },
        )
    }
}

private data class RuntimeImportCandidate(val uri: Uri, val name: String, val size: Long)
