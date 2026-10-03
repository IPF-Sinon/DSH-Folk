package me.bmax.apatch.ui.screen.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.FunctionSettingsScreenDestination
import com.ramcosta.composedestinations.generated.destinations.WirelessAdbScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.dsh.AdbBridge
import me.bmax.apatch.dsh.DshEnv
import me.bmax.apatch.dsh.DshHostPrompt
import me.bmax.apatch.dsh.PermissionManager
import me.bmax.apatch.dsh.PrivPolicy
import me.bmax.apatch.dsh.PrivStrictness
import me.bmax.apatch.ui.component.ExpressiveCard
import me.bmax.apatch.ui.component.ExpressiveSwitch
import me.bmax.apatch.util.ui.LocalSnackbarHost
import rikka.shizuku.Shizuku

/*
 * 「权限通道」与「无线 ADB 配对」两块从功能设置页（FunctionSettings.kt）搬出来后的独立页。
 *
 * 为什么要独立成页：这两块管的都是提权途径（root / Shizuku / 无线 ADB），与「运行方式、
 * 自启动、运行时」那些日常开关不是一类东西；混在同一页里，日常只调端口的人要滚过一整屏
 * 与提权相关的说明。搬成页面后各自有入口、各自可深链。
 *
 * 状态全部在新页内自持：原来这两块的 remember 与回调都挂在 FunctionSettingsScreen
 * （DshSettingsScreen）上，而那一页不能改，所以探测结果、偏好、ADB 输入框这些状态
 * 都在这里重新落一份，持久化一律走与原来同一批 API（PermissionManager / PrivPolicy /
 * AdbBridge / DshEnv 的 prefs），不新增任何存储位置。
 */

/** Shizuku 授权请求码。与功能页里的同值，只在 requestPermission 时用来对回调。 */
private const val SHIZUKU_REQ_CODE = 4210

/**
 * 「特权通道」独立页：显示当前生效的通道、选首选通道、定特权严格程度，并可重新探测。
 *
 * 探测结果是**设备上已有的实现**给出的，不是这里开出来的 —— 这一页只显示探测结果、
 * 代为申请 Shizuku 授权、并在用户主动点「重新探测」时才允许弹 su 授权框。
 */
@Destination<RootGraph>
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivilegedChannelScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackBarHost = LocalSnackbarHost.current
    val perm by PermissionManager.status.collectAsStateWithLifecycle()

    val dshPrefs = context.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)
    // 存的是字符串，rememberSaveable 不能直接存 enum?
    // 缺省 PREF_OFF：默认不提权，老用户由 PermissionManager.migratePreference 迁移。
    var permPrefName by rememberSaveable {
        mutableStateOf(
            dshPrefs.getString(DshEnv.KEY_PERM_CHANNEL, PermissionManager.PREF_OFF)
                ?: PermissionManager.PREF_OFF
        )
    }
    var privStrictness by remember { mutableStateOf(PrivPolicy.of(context)) }

    // 非 null = 正在为该状态显示引导弹窗。存状态而不是存 prefName：弹窗内容只取决于
    // 「差在哪一步」，而这一步选完就固定了。
    var guideReadiness by remember { mutableStateOf<ChannelReadiness?>(null) }

    /**
     * 进页面先全量探测一次。
     *
     * 探测要跑 shell，放 IO 线程；默认不弹授权框（[PermissionManager.refresh] 的
     * allowRootPrompt 默认 false），只有下面「重新探测」那一下才允许真跑 `su`。
     */
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            PermissionManager.refresh(context.applicationContext)
        }
    }

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

    // 用户主动点刷新才允许弹 su 授权框（refresh 默认不弹）
    val onRefreshPerm: () -> Unit = {
        scope.launch(Dispatchers.IO) {
            PermissionManager.refresh(context.applicationContext, allowRootPrompt = true)
            // 验过之后通道才真的可用，这一刻的事实必须落盘：否则用户点了
            // 「刷新权限」、su 也授权了，agent 那边还是「没验过」
            DshHostPrompt.writeFacts(context.applicationContext)
        }
    }

    val onRequestShizuku: () -> Unit = {
        runCatching { Shizuku.requestPermission(SHIZUKU_REQ_CODE) }
            .onFailure { e ->
                // 失败必须给一句话：抛异常基本是 binder 断了 / 服务没起来，这时什么都
                // 不弹，用户看到的就是「按了没反应」——正是要避免的形态。
                val msg = e.message ?: context.getString(R.string.dsh_perm_request_shizuku_failed)
                scope.launch { snackBarHost.showSnackbar(msg) }
            }
    }

    val onPermPrefChange: (String) -> Unit = { name ->
        permPrefName = name
        // 这条偏好是全应用「要不要提权」的总闸：选「未启用」时硬件监控、
        // 日志采集、root 文件兜底都会走非特权路径，首页重启菜单也不出现。
        // 容器执行本身不依赖它（proot/proroot 从来不需要 root）。
        // 「自动」= 按 root > shizuku > adb 的优先级挑一条可用的。
        //
        // 偏好**照旧落盘**，即使这条通道现在还没就绪：它表达的是「我想用哪条」，
        // 用户把配对/授权做完之后应当自动生效，而不是再回来重选一次。
        val ch = when (name) {
            PermissionManager.PREF_OFF -> PermissionManager.Channel.NONE
            PermissionManager.PREF_ROOT -> PermissionManager.Channel.ROOT
            PermissionManager.PREF_SHIZUKU -> PermissionManager.Channel.SHIZUKU
            PermissionManager.PREF_ADB -> PermissionManager.Channel.ADB
            else -> null
        }
        PermissionManager.setPreference(context.applicationContext, ch)
        // 选了还没就绪的通道：别只留一句「已回退」，直接把用户送到能修好它的那一步。
        // 用当前快照判定即可 —— 配对/授权状态不会因为「选了一下」而改变。
        readinessOf(name, perm).takeIf { it != ChannelReadiness.READY }?.let { guideReadiness = it }
        scope.launch(Dispatchers.IO) {
            PermissionManager.refresh(context.applicationContext)
            // 「我刚把通道设成 root」是用户最期待立刻生效的一步：这里不写，
            // agent 会一直以为设备上没有提权途径，连试都不试
            DshHostPrompt.writeFacts(context.applicationContext)
        }
    }

    val onPrivStrictnessChange: (PrivStrictness) -> Unit = { level ->
        privStrictness = level
        PrivPolicy.set(context.applicationContext, level)
        // 严格程度写进了提示词事实（agent 据此决定「这件事要不要拆成十条命令」），
        // 所以改完就得让容器侧看到新值
        DshHostPrompt.writeFacts(context.applicationContext)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.dsh_perm_cat_privileged),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navigator.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackBarHost) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            ExpressiveCard {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    SectionHeader(
                        icon = { Icon(Icons.Filled.Security, null, Modifier.size(20.dp)) },
                        title = stringResource(R.string.dsh_perm_section),
                        summary = stringResource(R.string.dsh_perm_summary),
                    )
                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = stringResource(
                            R.string.dsh_perm_current,
                            perm.label(LocalContext.current),
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(
                            R.string.dsh_perm_root_detail,
                            yesNo(perm.suPresent),
                            perm.rootProvider.ifEmpty { "-" },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (perm.shizukuGranted) {
                        val shizukuUidLabel = when (perm.shizukuUid) {
                            0 -> stringResource(R.string.dsh_perm_shizuku_uid_root)
                            2000 -> stringResource(R.string.dsh_perm_shizuku_uid_shell)
                            else -> stringResource(R.string.dsh_perm_shizuku_uid_other, perm.shizukuUid)
                        }
                        Text(
                            text = stringResource(
                                R.string.dsh_perm_shizuku_detail_uid,
                                yesNo(perm.shizukuRunning),
                                yesNo(perm.shizukuGranted),
                                shizukuUidLabel,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (perm.channel == PermissionManager.Channel.NONE) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (!perm.elevationEnabled &&
                                (perm.suPresent || perm.shizukuRunning || perm.adbPaired)
                            ) {
                                // 有通道可用、只是用户没启用：别让他以为设备不支持
                                stringResource(R.string.dsh_perm_detected_not_enabled)
                            } else {
                                stringResource(R.string.dsh_perm_none_hint)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.dsh_perm_prefer),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    RuntimeOption(
                        selected = permPrefName == PermissionManager.PREF_OFF,
                        enabled = true,
                        title = stringResource(R.string.dsh_perm_prefer_off),
                        summary = stringResource(R.string.dsh_perm_prefer_off_desc),
                        onSelect = { onPermPrefChange(PermissionManager.PREF_OFF) },
                    )
                    RuntimeOption(
                        selected = permPrefName == PermissionManager.PREF_AUTO,
                        enabled = true,
                        title = stringResource(R.string.dsh_perm_prefer_auto),
                        summary = stringResource(R.string.dsh_perm_prefer_auto_desc),
                        onSelect = { onPermPrefChange(PermissionManager.PREF_AUTO) },
                    )
                    RuntimeOption(
                        selected = permPrefName == PermissionManager.PREF_ROOT,
                        enabled = true,
                        title = stringResource(R.string.dsh_perm_root),
                        summary = channelOptionSummary(
                            R.string.dsh_perm_prefer_root_desc,
                            readinessOf(PermissionManager.PREF_ROOT, perm),
                        ),
                        onSelect = { onPermPrefChange(PermissionManager.PREF_ROOT) },
                    )
                    RuntimeOption(
                        selected = permPrefName == PermissionManager.PREF_SHIZUKU,
                        enabled = true,
                        title = stringResource(R.string.dsh_perm_shizuku),
                        summary = channelOptionSummary(
                            R.string.dsh_perm_prefer_shizuku_desc,
                            readinessOf(PermissionManager.PREF_SHIZUKU, perm),
                        ),
                        onSelect = { onPermPrefChange(PermissionManager.PREF_SHIZUKU) },
                    )
                    RuntimeOption(
                        selected = permPrefName == PermissionManager.PREF_ADB,
                        enabled = true,
                        title = stringResource(R.string.dsh_perm_adb),
                        summary = channelOptionSummary(
                            R.string.dsh_perm_prefer_adb_desc,
                            readinessOf(PermissionManager.PREF_ADB, perm),
                        ),
                        onSelect = { onPermPrefChange(PermissionManager.PREF_ADB) },
                    )

                    if (perm.preferenceFellBack) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(
                                R.string.dsh_perm_fell_back,
                                perm.label(LocalContext.current),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        // 「还没配置好」必须有个出口：上次选的那条通道可能是在这之后失效的
                        // （系统里撤了授权、Shizuku 被停掉、adbkey 被删），这时用户要的是
                        // 同一个引导，而不是自己去猜哪一步没做。
                        val fellBackReadiness = readinessOf(permPrefName, perm)
                        if (fellBackReadiness != ChannelReadiness.READY) {
                            TextButton(onClick = { guideReadiness = fellBackReadiness }) {
                                Text(stringResource(R.string.dsh_perm_fell_back_go))
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    // 严格程度只管「要不要问」，与选哪条通道是两件事，所以排在同一张卡里、
                    // 通道选择之后：用户先决定用哪条通道，再决定它有多自由。
                    Text(
                        text = stringResource(R.string.dsh_priv_strictness_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.dsh_priv_strictness_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    RuntimeOption(
                        selected = privStrictness == PrivStrictness.STRICT,
                        enabled = true,
                        title = stringResource(R.string.dsh_priv_strict),
                        summary = stringResource(R.string.dsh_priv_strict_desc),
                        onSelect = { onPrivStrictnessChange(PrivStrictness.STRICT) },
                    )
                    RuntimeOption(
                        selected = privStrictness == PrivStrictness.NORMAL,
                        enabled = true,
                        title = stringResource(R.string.dsh_priv_normal),
                        summary = stringResource(R.string.dsh_priv_normal_desc),
                        onSelect = { onPrivStrictnessChange(PrivStrictness.NORMAL) },
                    )
                    RuntimeOption(
                        selected = privStrictness == PrivStrictness.LOOSE,
                        enabled = true,
                        title = stringResource(R.string.dsh_priv_loose),
                        summary = stringResource(R.string.dsh_priv_loose_desc),
                        onSelect = { onPrivStrictnessChange(PrivStrictness.LOOSE) },
                    )

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRefreshPerm) {
                            Text(stringResource(R.string.dsh_perm_refresh))
                        }
                        AnimatedVisibility(visible = perm.shizukuRunning && !perm.shizukuGranted) {
                            Button(onClick = onRequestShizuku) {
                                Text(stringResource(R.string.dsh_perm_request_shizuku))
                            }
                        }
                    }
                }
            }
        }
    }

    guideReadiness?.let { readiness ->
        ChannelGuideDialog(
            readiness = readiness,
            navigator = navigator,
            onRequestShizuku = onRequestShizuku,
            onRefreshPerm = onRefreshPerm,
            onDismiss = { guideReadiness = null },
        )
    }
}

/**
 * 一条引导弹窗的内容。
 *
 * 抽成规格对象是为了让下面那个 `when` 的每个分支都只负责「给出这一步该说什么、点了去哪」，
 * 弹窗本身的形状（标题、按钮位置、关闭语义）只有一处。
 */
private data class ChannelGuideSpec(
    val titleRes: Int,
    val bodyRes: Int,
    val actionRes: Int,
    val action: () -> Unit,
)

/**
 * 选中一条还没就绪的通道之后的引导。
 *
 * `when` 是**穷尽**的：新增一种未就绪状态却忘了给它出口，会直接编译不过。这类「弹出来
 * 却没有任何办法解决」的弹窗正是要避免的形态 —— 所以让编译器替我们盯住。
 */
@Composable
private fun ChannelGuideDialog(
    readiness: ChannelReadiness,
    navigator: DestinationsNavigator,
    onRequestShizuku: () -> Unit,
    onRefreshPerm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (readiness == ChannelReadiness.READY) return
    val context = LocalContext.current
    // ADB 的引导分两种：运行时没装就先装运行时，装了才谈得上配对
    val runtimeInstalled = remember(context) { DshEnv.isRuntimeInstalled(context) }
    val spec = when (readiness) {
        ChannelReadiness.ADB_UNCONFIGURED ->
            if (runtimeInstalled) {
                ChannelGuideSpec(
                    R.string.dsh_chguide_adb_title,
                    R.string.dsh_chguide_adb_body,
                    R.string.dsh_chguide_adb_go_pair,
                ) { navigator.navigate(WirelessAdbScreenDestination) }
            } else {
                ChannelGuideSpec(
                    R.string.dsh_chguide_adb_runtime_title,
                    R.string.dsh_chguide_adb_runtime_body,
                    R.string.dsh_chguide_adb_go_runtime,
                ) { navigator.navigate(FunctionSettingsScreenDestination("function_runtime")) }
            }

        ChannelReadiness.SHIZUKU_UNGRANTED -> ChannelGuideSpec(
            R.string.dsh_chguide_shizuku_ungranted_title,
            R.string.dsh_chguide_shizuku_ungranted_body,
            R.string.dsh_chguide_shizuku_request,
            onRequestShizuku,
        )

        ChannelReadiness.SHIZUKU_ABSENT -> ChannelGuideSpec(
            R.string.dsh_chguide_shizuku_absent_title,
            R.string.dsh_chguide_shizuku_absent_body,
            R.string.dsh_chguide_shizuku_retry,
            onRefreshPerm,
        )

        ChannelReadiness.ROOT_UNVERIFIED -> ChannelGuideSpec(
            R.string.dsh_chguide_root_unverified_title,
            R.string.dsh_chguide_root_unverified_body,
            R.string.dsh_chguide_root_verify,
            onRefreshPerm,
        )

        ChannelReadiness.ROOT_ABSENT -> ChannelGuideSpec(
            R.string.dsh_chguide_root_absent_title,
            R.string.dsh_chguide_root_absent_body,
            R.string.dsh_chguide_ok,
            onDismiss,
        )

        ChannelReadiness.READY -> return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dsh_chguide_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(spec.titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(stringResource(spec.bodyRes))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                spec.action()
            }) { Text(stringResource(spec.actionRes)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dsh_chguide_later)) }
        },
    )
}

/**
 * 「无线 ADB 配对」独立页：向系统的无线调试服务配对，拿到 shell（uid 2000）。
 *
 * 配对、断开、写授权这些动作的状态与回调原来都在功能设置页上，这里自持一份；真正落盘的
 * 三处（配对键、shell / root 写授权标记）仍由 [AdbBridge] 写，不新增存储。
 */
@Destination<RootGraph>
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WirelessAdbScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val perm by PermissionManager.status.collectAsStateWithLifecycle()

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

    // 安装状态得是个 state 而不是每次重组现算：重组不一定发生，而它会在别处变化 ——
    // 用户可能刚从首页装完运行时回来（见下面的 LifecycleResumeEffect）。
    var runtimeInstalled by remember { mutableStateOf(DshEnv.isRuntimeInstalled(context)) }

    // 配对状态来自全量探测，进页面刷新一次，免得显示上一页留下的旧结果
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            PermissionManager.refresh(context.applicationContext)
        }
    }

    // 运行时可能在首页刚装好，回到本页要重读；少了这一行，「还没装」的提示会一直挂着
    LifecycleResumeEffect(Unit) {
        runtimeInstalled = DshEnv.isRuntimeInstalled(context)
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.dsh_perm_cat_wireless_adb),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navigator.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            ExpressiveCard {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    SectionHeader(
                        icon = { Icon(Icons.Filled.Wifi, null, Modifier.size(20.dp)) },
                        title = stringResource(R.string.dsh_adb_section),
                        summary = stringResource(R.string.dsh_adb_summary),
                    )
                    Spacer(Modifier.height(12.dp))

                    Text(
                        // 与特权通道页那条选项用的是同一套状态词：两处对同一件事说不同的话，
                        // 用户会以为它们不是一回事。
                        text = stringResource(
                            if (perm.adbPaired) R.string.dsh_adb_paired
                            else R.string.dsh_perm_state_adb_unconfigured
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )

                    if (!runtimeInstalled) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.dsh_adb_needs_runtime),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = adbPairCode,
                        onValueChange = { adbPairCode = it.filter { c -> c.isDigit() }.take(6) },
                        label = { Text(stringResource(R.string.dsh_adb_pair_code)) },
                        singleLine = true,
                        enabled = runtimeInstalled && !adbBusy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = adbPairPort,
                            onValueChange = { adbPairPort = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text(stringResource(R.string.dsh_adb_pair_port)) },
                            singleLine = true,
                            enabled = runtimeInstalled && !adbBusy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = adbConnectPort,
                            onValueChange = { adbConnectPort = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text(stringResource(R.string.dsh_adb_connect_port)) },
                            singleLine = true,
                            enabled = runtimeInstalled && !adbBusy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = adbHost,
                        onValueChange = { adbHost = it.trim() },
                        label = { Text(stringResource(R.string.dsh_adb_host)) },
                        singleLine = true,
                        enabled = runtimeInstalled && !adbBusy,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(12.dp))
                    var disconnectConfirming by remember { mutableStateOf(false) }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = {
                                adbBusy = true
                                scope.launch(Dispatchers.IO) {
                                    val out = runCatching {
                                        // 配对脚本必须先在容器里就位，且依赖装好，否则直接报 ImportError
                                        if (!AdbBridge.injected()) AdbBridge.inject(context.applicationContext)
                                        if (!AdbBridge.depsOk()) AdbBridge.installDeps(context.applicationContext)
                                        AdbBridge.pair(adbPairCode, adbPairPort, adbConnectPort, adbHost)
                                    }.getOrElse { it.message ?: context.getString(R.string.dsh_adb_pair_failed) }
                                    PermissionManager.refresh(context.applicationContext)
                                    withContext(Dispatchers.Main) {
                                        adbOutput = out
                                        adbBusy = false
                                    }
                                }
                            },
                            enabled = runtimeInstalled && !adbBusy && adbPairCode.isNotBlank(),
                        ) {
                            Text(stringResource(R.string.dsh_adb_pair_with_deps))
                        }
                        if (perm.adbPaired) {
                            OutlinedButton(
                                onClick = { disconnectConfirming = true },
                                enabled = !adbBusy,
                            ) {
                                Text(stringResource(R.string.dsh_adb_disconnect))
                            }
                        }
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }) {
                            Text(stringResource(R.string.dsh_adb_open_devsettings))
                        }
                        if (adbBusy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                    if (disconnectConfirming) {
                        AlertDialog(
                            onDismissRequest = { disconnectConfirming = false },
                            title = { Text(stringResource(R.string.dsh_adb_disconnect_confirm_title)) },
                            text = { Text(stringResource(R.string.dsh_adb_disconnect_confirm_text)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    disconnectConfirming = false
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
                                }) {
                                    Text(stringResource(R.string.dsh_adb_disconnect))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { disconnectConfirming = false }) {
                                    Text(stringResource(android.R.string.cancel))
                                }
                            },
                        )
                    }

                    // 写操作授权：adb-shell.py 读 rootfs 里的标记文件，只读命令不受影响
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.dsh_adb_shell_allow),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(R.string.dsh_adb_shell_allow_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        ExpressiveSwitch(
                            checked = adbShellAllowed,
                            onCheckedChange = { on ->
                                AdbBridge.setGranted(context, AdbBridge.ShellGrant.WRITE, on)
                                adbShellAllowed = AdbBridge.granted(context, AdbBridge.ShellGrant.WRITE)
                            },
                            enabled = runtimeInstalled && !adbBusy,
                        )
                    }

                    // root shell：只有手机本身已 root 才有意义，所以顺带用权限通道判断
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.dsh_adb_root_allow),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(R.string.dsh_adb_root_allow_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        ExpressiveSwitch(
                            checked = adbRootAllowed,
                            onCheckedChange = { on ->
                                AdbBridge.setGranted(context, AdbBridge.ShellGrant.ROOT, on)
                                adbRootAllowed = AdbBridge.granted(context, AdbBridge.ShellGrant.ROOT)
                            },
                            enabled = runtimeInstalled && !adbBusy && (perm.suPresent || perm.shizukuIsRoot),
                        )
                    }

                    if (adbOutput.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.dsh_output),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = adbOutput,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    icon: @Composable () -> Unit,
    title: String,
    summary: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RuntimeOption(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    summary: String,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun yesNo(b: Boolean): String = if (b) "✓" else "✗"

/**
 * 一条通道「准备好没有」。
 *
 * 为什么不是布尔：用户看到「当前不可用」时最需要知道的是**差在哪一步** —— 是没授权、
 * 本机压根没检测到，还是根本没配置过。这三件事的下一步动作完全不同（请求授权 / 去装
 * 或去启动 / 去配对），一句「不可用」把三种情况糊成一种，用户只能猜。
 *
 * 与「当前生效通道」是两件事：这里只说选中它之后能不能立刻用。
 * 做成枚举是为了让下面每个 `when` 都**穷尽**——新增一种未就绪状态时，引导弹窗漏了分支
 * 会直接编译不过，而不是让用户看到一个没有出口的弹窗。
 */
private enum class ChannelReadiness {
    READY,
    ROOT_UNVERIFIED,
    ROOT_ABSENT,
    SHIZUKU_UNGRANTED,
    SHIZUKU_ABSENT,
    ADB_UNCONFIGURED,
}

private fun readinessOf(prefName: String, perm: PermissionManager.Status): ChannelReadiness =
    when (prefName) {
        PermissionManager.PREF_ROOT -> when {
            perm.rootVerified -> ChannelReadiness.READY
            perm.suPresent -> ChannelReadiness.ROOT_UNVERIFIED
            else -> ChannelReadiness.ROOT_ABSENT
        }

        PermissionManager.PREF_SHIZUKU -> when {
            perm.shizukuGranted -> ChannelReadiness.READY
            perm.shizukuRunning -> ChannelReadiness.SHIZUKU_UNGRANTED
            else -> ChannelReadiness.SHIZUKU_ABSENT
        }

        PermissionManager.PREF_ADB ->
            if (perm.adbPaired) ChannelReadiness.READY else ChannelReadiness.ADB_UNCONFIGURED

        // OFF = 显式不提权，AUTO = 由系统按可用性自己挑：两者都没有「就绪」可言
        else -> ChannelReadiness.READY
    }

/** 通道选项的副标题：中性说明 + 未就绪时的「（差在哪一步）」。 */
@Composable
private fun channelOptionSummary(baseRes: Int, readiness: ChannelReadiness): String {
    val base = stringResource(baseRes)
    val stateRes = when (readiness) {
        ChannelReadiness.READY -> return base
        ChannelReadiness.ROOT_UNVERIFIED -> R.string.dsh_perm_state_root_unverified
        ChannelReadiness.ROOT_ABSENT -> R.string.dsh_perm_state_root_absent
        ChannelReadiness.SHIZUKU_UNGRANTED -> R.string.dsh_perm_state_shizuku_ungranted
        ChannelReadiness.SHIZUKU_ABSENT -> R.string.dsh_perm_state_shizuku_absent
        ChannelReadiness.ADB_UNCONFIGURED -> R.string.dsh_perm_state_adb_unconfigured
    }
    return base + "\n" + stringResource(R.string.dsh_perm_state_suffix, stringResource(stateRes))
}
