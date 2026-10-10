package me.bmax.apatch.ui.screen.settings

import android.content.Intent
import android.provider.DocumentsContract
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.bmax.apatch.BuildConfig
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshAutostart
import me.bmax.apatch.dsh.DshEnv
import me.bmax.apatch.dsh.DshNativeBridge
import me.bmax.apatch.dsh.DshRuntime
import me.bmax.apatch.dsh.RuntimeCheckResult
import me.bmax.apatch.dsh.RuntimeVersion
import me.bmax.apatch.dsh.compareVersions
import me.bmax.apatch.dsh.DshSource
import me.bmax.apatch.dsh.PermissionManager
import me.bmax.apatch.ui.DshWebUi
import me.bmax.apatch.ui.component.ExpressiveCard
import me.bmax.apatch.ui.component.ExpressiveSwitch
import me.bmax.apatch.ui.component.ModuleLabel
import me.bmax.apatch.ui.component.SplicedColumnGroup
import me.bmax.apatch.ui.component.ToggleSettingCard
import me.bmax.apatch.util.DshDocsAccess
import me.bmax.apatch.util.ui.showToast
import me.bmax.apatch.ui.screen.settings.general.CleanStorageDialog

/**
 * 功能设置内容：**运行方式** 与 **权限通道**（含无线 ADB 配对）。
 *
 * 这一页在 FolkPatch 里是内核补丁相关的开关（越狱模式、隐藏服务、umount、UTS 伪装、
 * 路径隐藏、网络隔离、内置 Shizuku Server）。DSH-Folk 不打内核补丁，那些开关全部没有
 * 对应实现，所以整页换成 DSH 自己需要配置的两件事 —— 首页那两张小卡就指到这里。
 */
@Composable
fun FunctionSettingsContent(
    /** 当前容器运行时 id：proot / proroot。 */
    runtimeId: String,
    onRuntimeIdChange: (String) -> Unit,
    /** proroot 是否在本机可用（不可用时禁选并说明原因）。 */
    prorootAvailable: Boolean,
    prorootUnavailableReason: String,
    /** 自启动方式（三条路径，见 [DshAutostart.Mode]）。 */
    autostartMode: DshAutostart.Mode,
    onAutostartModeChange: (DshAutostart.Mode) -> Unit,
    /** 自启时是否连容器一起拉起。 */
    autostartContainer: Boolean,
    onAutostartContainerChange: (Boolean) -> Unit,
    /** 开机脚本在设备上的状态（root 读，可能读不到）。 */
    autostartScriptInstalled: Boolean,
    autostartScriptOutdated: Boolean,
    autostartScriptBusy: Boolean,
    onInstallAutostartScript: () -> Unit,
    onRemoveAutostartScript: () -> Unit,
    /** 无障碍服务当前是否被用户启用了。 */
    autostartA11yEnabled: Boolean,
    onOpenA11ySettings: () -> Unit,
    /** 打开 App（不是开机）时自动启动服务。 */
    autoStartOnLaunch: Boolean,
    onAutoStartOnLaunchChange: (Boolean) -> Unit,
    /** 服务就绪后自动打开 DSH 页面。 */
    autoOpenWebUi: Boolean,
    onAutoOpenWebUiChange: (Boolean) -> Unit,
    /** Web 服务监听端口。 */
    port: Int,
    onPortChange: (Int) -> Unit,
    /** 局域网访问开关（默认关；开则 dsh web 绑 0.0.0.0）。 */
    lanEnabled: Boolean,
    onLanChange: (Boolean) -> Unit,
    /** 竞速通道**总开关**（默认开）。关掉时三条通道一律直连，分开关的勾选都不生效。 */
    raceMasterEnabled: Boolean,
    onRaceMasterChange: (Boolean) -> Unit,
    /** 竞速通道：被勾选的通道（总开关关掉时不生效）。 */
    racePlugins: Boolean,
    raceAppUpdate: Boolean,
    raceRuntime: Boolean,
    /** 勾选/取消某个通道（channel 取 [DshRuntime.RACE_PLUGINS] 等）。 */
    onRaceChannelChange: (String, Boolean) -> Unit,
    /** 竞速通道里勾选的镜像线路（id 取自 DshSource.allSourceIds()）。 */
    raceMirrors: Set<String>,
    onRaceMirrorToggle: (String, Boolean) -> Unit,
    /** 自定义 metadata 地址（弹窗里的「自定义源」一项；勾上才用）。 */
    customSourceEnabled: Boolean,
    onCustomSourceToggle: (Boolean) -> Unit,
    customMetaUrl: String,
    onCustomMetaUrlChange: (String) -> Unit,
    /** 已解析的生效源（竞速时是测速结果）。 */
    effectiveSource: String,
    speedTesting: Boolean,
    /** 测速结果行，已格式化好。 */
    /** 最近一次测速的原始结果（展示在弹窗里每条线路自己那一行上）。 */
    speedResults: List<DshSource.SpeedResult>,
    onSpeedTest: () -> Unit,
    /** WebUI 打开方式：in | browser | ask。 */
    webuiMode: String,
    onWebuiModeChange: (String) -> Unit,
    /** 画中画**主开关**（默认开）：关掉后悬浮菜单不显示画中画按钮，也不自动进小窗。 */
    webuiPipMain: Boolean,
    onWebuiPipMainChange: (Boolean) -> Unit,
    /** 画中画副开关：离开应用时自动缩成小窗（默认开）。主开关关掉时置灰。 */
    webuiPipAuto: Boolean,
    onWebuiPipAutoChange: (Boolean) -> Unit,
    /**
     * 用户脚本**总开关**（默认开）。
     *
     * 就是注入管道里那道闸（`Userscripts.masterEnabled`）：关掉后本应用自己的页面一段用户脚本
     * 都不注入；随包发的内置那几段不受它约束。原来在用户脚本页上，按用户要求搬到设置里。
     */
    userscriptsMaster: Boolean,
    onUserscriptsMasterChange: (Boolean) -> Unit,
    /**
     * 权限已经齐了的能力集合。
     *
     * 原来是三个布尔（通知/媒体/麦克风），加到十几项之后那种写法会变成一串参数 ——
     * 而且每加一项能力都要改三处签名。改成集合后界面只问「这一项齐了吗」。
     */
    capsWithPermission: Set<DshNativeBridge.Cap>,
    /** 「所有文件访问」是否已授予（appop 特殊权限，只能跳系统设置页）。 */
    allFilesGranted: Boolean,
    /** 跳「所有文件访问」的系统设置页。 */
    onOpenAllFilesSettings: () -> Unit,
    /** 打开「细化文件访问范围（黑白名单）」子页。 */
    onOpenFileAccess: () -> Unit = {},
    /** 打开「深度清理」页（长按「清理资源和缓存」那张卡进来）。 */
    onOpenDeepClean: () -> Unit = {},
    /** 打开「权限管理」：特权通道、无线 ADB 与各项原生能力都在那一页里分类 + 可搜索。 */
    onOpenPermissionHub: () -> Unit = {},
    /** 共享存储挂载总开关（挂载 /sdcard + dsh-fs 桥，两者都受黑白名单约束）。 */
    mountEnabled: Boolean = true,
    onSetMount: (Boolean) -> Unit = {},
    /**
     * 运行时是否已安装。
     *
     * 两处用到：无线 ADB 需要容器内的 python；开机自启在运行时没装时不会启动任何东西，
     * 那句提示也得跟着出现。
     */
    runtimeInstalled: Boolean,
    /** 已安装的运行时版本；未安装时为空。 */
    runtimeVersion: String,
    /**
     * 已装运行时要求的最低 App 版本，当前 App 不满足。
     *
     * 置位时整张运行时卡（启动/更新/重装入口）变成「请先更新应用」，否则旧 App 会
     * 撞上一串 node 堆栈（0.1.5 起上游内置的 entry id 与旧 App 预装插件冲突）。
     */
    appUpdateRequired: Boolean,
    /** [appUpdateRequired] 为 true 时这份运行时要求的最低版本（给人看）。 */
    requiredAppVersion: String?,
    /** 跳去 设置 → 常规 → 检查更新（App 自身的更新）。 */
    onGoUpdateApp: () -> Unit,
    /** 重新下载并覆盖容器。 */
    onReinstallRuntime: (Boolean) -> Unit,
    runtimeCheckRevision: Int,
    onCheckRuntimeUpdateRequested: () -> Unit,
    onCheckRuntimeUpdate: suspend () -> RuntimeCheckResult,
    /** 列出仓库里所有运行时版本（长按「更新」唤出的版本列表）。 */
    onListRuntimeVersions: suspend () -> List<RuntimeVersion>,
    /** 安装版本列表里选中的那一份（升级 / 降级 / 换通道共用）。 */
    onSwitchRuntimeVersion: (RuntimeVersion) -> Unit,
    onImportRuntime: () -> Unit,
    /** App 启动后自动检查运行时更新（独立开关，默认开）。 */
    runtimeAutoCheck: Boolean,
    onRuntimeAutoCheckChange: (Boolean) -> Unit,
    runtimeBeta: Boolean,
    onRuntimeBetaChange: (Boolean) -> Unit,
    /** 精简版运行时（砍掉文档预览/转换等，产物约小 63MB）。与测试通道正交。 */
    runtimeSlim: Boolean,
    onRuntimeSlimChange: (Boolean) -> Unit,
    /** 重建 profile 插件依赖（清空 node_modules 后重装）。 */
    onRepairPlugins: () -> Unit,
    /** 重建正在进行中（与安装共用同一把锁）。 */
    repairBusy: Boolean,
    /** 安装插件后是否验证一次能否启动。 */
    verifyAfterInstall: Boolean,
    onVerifyAfterInstallChange: (Boolean) -> Unit,
    permissionOnly: Boolean = false,
    flat: Boolean = false,
    highlightKey: String? = null,
) {
    val context = LocalContext.current
    val showCleanStorageDialog = remember { mutableStateOf(false) }
    val showGrantDocsDialog = remember { mutableStateOf(false) }
    val docsCandidates = remember { DshDocsAccess.candidates(context) }
    // 已直接授权的包名（本对象自己记的）：授权/撤销后就地刷新
    val docsGranted = remember { mutableStateOf(DshDocsAccess.grantedPackages(context)) }

    SplicedColumnGroup(flat = flat, highlightKey = highlightKey) {
        // ───────── 运行方式 ─────────
        item(key = "function_run_mode", visible = !permissionOnly) {
            ExpressiveCard(flat = flat) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    SectionHeader(
                        icon = { Icon(Icons.Filled.Layers, null, Modifier.size(20.dp)) },
                        title = stringResource(R.string.dsh_run_mode),
                        summary = stringResource(R.string.dsh_run_mode_summary),
                    )
                    Spacer(Modifier.height(12.dp))

                    RuntimeOption(
                        selected = runtimeId != "proroot",
                        enabled = true,
                        title = stringResource(R.string.dsh_mode_proot),
                        summary = stringResource(R.string.dsh_mode_proot_desc),
                        onSelect = { onRuntimeIdChange("proot") },
                    )
                    RuntimeOption(
                        selected = runtimeId == "proroot",
                        enabled = prorootAvailable,
                        title = stringResource(R.string.dsh_mode_proroot),
                        summary = if (prorootAvailable) stringResource(R.string.dsh_mode_proroot_desc)
                        else prorootUnavailableReason,
                        onSelect = { onRuntimeIdChange("proroot") },
                    )

                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.dsh_run_mode_restart_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ───────── 开机自启 ─────────
        //
        // 三条路径按「代价从小到大」排：广播（零成本、但可能不生效）→ 无障碍（要开一个
        // 吓人的开关）→ 脚本（要 root）。不按可靠性排：把「需要 root」放在第一条会让
        // 没 root 的人以为这功能与自己无关。
        item(key = "function_autostart", visible = !permissionOnly) {
            ExpressiveCard(flat = flat) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    SectionHeader(
                        icon = { Icon(Icons.Filled.PowerSettingsNew, null, Modifier.size(20.dp)) },
                        title = stringResource(R.string.dsh_autostart),
                        summary = stringResource(R.string.dsh_autostart_summary),
                    )
                    var autostartExpanded by remember { mutableStateOf(false) }
                    TextButton(onClick = { autostartExpanded = !autostartExpanded }) {
                        Icon(
                            if (autostartExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(if (autostartExpanded) R.string.dsh_section_collapse else R.string.dsh_section_expand))
                    }
                    AnimatedVisibility(visible = autostartExpanded) {
                        Column {
                    Spacer(Modifier.height(12.dp))

                    RuntimeOption(
                        selected = autostartMode == DshAutostart.Mode.OFF,
                        enabled = true,
                        title = stringResource(R.string.dsh_autostart_mode_off),
                        summary = stringResource(R.string.dsh_autostart_mode_off_desc),
                        onSelect = { onAutostartModeChange(DshAutostart.Mode.OFF) },
                    )
                    RuntimeOption(
                        selected = autostartMode == DshAutostart.Mode.RECEIVER,
                        enabled = true,
                        title = stringResource(R.string.dsh_autostart_mode_receiver),
                        summary = stringResource(R.string.dsh_autostart_mode_receiver_desc),
                        onSelect = { onAutostartModeChange(DshAutostart.Mode.RECEIVER) },
                    )
                    RuntimeOption(
                        selected = autostartMode == DshAutostart.Mode.ACCESSIBILITY,
                        enabled = true,
                        title = stringResource(R.string.dsh_autostart_mode_a11y),
                        summary = stringResource(R.string.dsh_autostart_mode_a11y_desc),
                        onSelect = { onAutostartModeChange(DshAutostart.Mode.ACCESSIBILITY) },
                    )
                    RuntimeOption(
                        selected = autostartMode == DshAutostart.Mode.SCRIPT,
                        enabled = true,
                        title = stringResource(R.string.dsh_autostart_mode_script),
                        summary = stringResource(R.string.dsh_autostart_mode_script_desc),
                        onSelect = { onAutostartModeChange(DshAutostart.Mode.SCRIPT) },
                    )

                    // 选中的那条路径各自的后续动作。只显示当前选中的那一组：三组同时铺开
                    // 会让人以为要全部做完。
                    when (autostartMode) {
                        DshAutostart.Mode.SCRIPT -> {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = when {
                                    autostartScriptOutdated ->
                                        stringResource(R.string.dsh_autostart_script_state_outdated)
                                    autostartScriptInstalled -> stringResource(
                                        R.string.dsh_autostart_script_state_installed,
                                        DshAutostart.scriptPath(),
                                    )
                                    else -> stringResource(R.string.dsh_autostart_script_state_missing)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (autostartScriptInstalled && !autostartScriptOutdated) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.tertiary
                                },
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = onInstallAutostartScript,
                                    enabled = !autostartScriptBusy,
                                ) {
                                    Text(
                                        stringResource(
                                            if (autostartScriptInstalled) {
                                                R.string.dsh_autostart_script_reinstall
                                            } else {
                                                R.string.dsh_autostart_script_install
                                            }
                                        )
                                    )
                                }
                                if (autostartScriptInstalled) {
                                    OutlinedButton(
                                        onClick = onRemoveAutostartScript,
                                        enabled = !autostartScriptBusy,
                                    ) {
                                        Text(stringResource(R.string.dsh_autostart_script_remove))
                                    }
                                }
                            }
                        }

                        DshAutostart.Mode.ACCESSIBILITY -> {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = if (autostartA11yEnabled) {
                                    stringResource(R.string.dsh_autostart_a11y_state_on)
                                } else {
                                    stringResource(
                                        R.string.dsh_autostart_a11y_state_off,
                                        stringResource(R.string.app_name),
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (autostartA11yEnabled) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.tertiary
                                },
                            )
                            if (!autostartA11yEnabled) {
                                // 旁加载安装的应用会被 Android 13+ 挡在「受限设置」后面，
                                // 那时无障碍开关是灰的、点了没反应 —— 不说清楚的话用户只会
                                // 以为我们写坏了。
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.dsh_autostart_a11y_restricted),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = onOpenA11ySettings) {
                                Text(stringResource(R.string.dsh_autostart_a11y_open))
                            }
                        }

                        DshAutostart.Mode.OFF, DshAutostart.Mode.RECEIVER -> Unit
                    }

                    if (autostartMode != DshAutostart.Mode.OFF) {
                        if (!runtimeInstalled) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.dsh_autostart_not_installed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.dsh_autostart_container),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = stringResource(R.string.dsh_autostart_container_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            ExpressiveSwitch(
                                checked = autostartContainer,
                                onCheckedChange = onAutostartContainerChange,
                            )
                        }
                    }
                        }
                    }
                }
            }
        }

        // ───────── 应用启动行为 ─────────
        //
        // 与上面「开机自启」分开：那一条讲的是设备重启后要不要自己起来，这两条讲的是
        // **用户打开 App** 时的行为。开关也各自独立 —— 只让服务在后台待命、或者每次
        // 打开都直接进页面，都是合理用法。
        item(key = "function_auto_start_service", visible = !permissionOnly) {
            ToggleSettingCard(
                flat = flat,
                icon = Icons.Filled.PowerSettingsNew,
                title = stringResource(R.string.dsh_auto_start_service),
                description = stringResource(R.string.dsh_auto_start_service_summary),
                checked = autoStartOnLaunch,
                onCheckedChange = onAutoStartOnLaunchChange,
            )
        }

        item(key = "function_auto_open_webui", visible = !permissionOnly) {
            ToggleSettingCard(
                flat = flat,
                icon = Icons.Filled.OpenInNew,
                title = stringResource(R.string.dsh_auto_open_webui),
                description = stringResource(R.string.dsh_auto_open_webui_summary),
                checked = autoOpenWebUi,
                onCheckedChange = onAutoOpenWebUiChange,
            )
        }

        // ───────── 端口 ─────────
        item(key = "function_port", visible = !permissionOnly) {
            ExpressiveCard(flat = flat) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    SectionHeader(
                        icon = { Icon(Icons.Filled.Tune, null, Modifier.size(20.dp)) },
                        title = stringResource(R.string.dsh_port_title),
                        summary = stringResource(R.string.dsh_port_summary),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.dsh_port_current, port),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(8.dp))
                    var editing by remember { mutableStateOf(false) }
                    OutlinedButton(onClick = { editing = true }) {
                        Text(stringResource(R.string.dsh_port_edit))
                    }
                    if (editing) {
                        var input by remember { mutableStateOf(port.toString()) }
                        var inputError by remember { mutableStateOf(false) }
                        AlertDialog(
                            onDismissRequest = { editing = false },
                            title = { Text(stringResource(R.string.dsh_port_title)) },
                            text = {
                                OutlinedTextField(
                                    value = input,
                                    onValueChange = {
                                        input = it.filter(Char::isDigit)
                                        inputError = false
                                    },
                                    label = { Text(stringResource(R.string.dsh_port_input_hint)) },
                                    isError = inputError,
                                    supportingText = if (inputError) {
                                        { Text(stringResource(R.string.dsh_port_invalid)) }
                                    } else {
                                        null
                                    },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    val p = input.toIntOrNull()
                                    if (p == null || p !in 1..65535) {
                                        inputError = true
                                    } else {
                                        onPortChange(p)
                                        editing = false
                                    }
                                }) { Text(stringResource(R.string.dsh_port_confirm)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { editing = false }) {
                                    Text(stringResource(android.R.string.cancel))
                                }
                            },
                        )
                    }
                }
            }
        }

        // ───────── 局域网访问 ─────────
        item(key = "function_lan", visible = !permissionOnly) {
            ToggleSettingCard(
                flat = flat,
                icon = Icons.Filled.Public,
                title = stringResource(R.string.dsh_lan_title),
                description = stringResource(R.string.dsh_lan_summary),
                checked = lanEnabled,
                onCheckedChange = onLanChange,
            )
        }

        // ───────── 竞速通道（镜像/测速）─────────
        // 长按卡片出通道勾选弹窗：勾了的通道走测速竞速，没勾的一律直连；总开关关掉则全部不生效。
        item(key = "function_gh_mirror", visible = !permissionOnly) {
            var showRaceDialog by remember { mutableStateOf(false) }
            ToggleSettingCard(
                flat = flat,
                icon = Icons.Filled.CloudDownload,
                title = stringResource(R.string.dsh_race_title),
                description = stringResource(R.string.dsh_race_summary),
                checked = raceMasterEnabled,
                onLongClick = { showRaceDialog = true },
                onCheckedChange = onRaceMasterChange,
            )
            if (showRaceDialog) {
                RaceChannelDialog(
                    masterEnabled = raceMasterEnabled,
                    racePlugins = racePlugins,
                    raceAppUpdate = raceAppUpdate,
                    raceRuntime = raceRuntime,
                    onChannelChange = onRaceChannelChange,
                    mirrors = raceMirrors,
                    onMirrorToggle = onRaceMirrorToggle,
                    customSourceEnabled = customSourceEnabled,
                    onCustomSourceToggle = onCustomSourceToggle,
                    customMetaUrl = customMetaUrl,
                    onCustomMetaUrlChange = onCustomMetaUrlChange,
                    speedTesting = speedTesting,
                    speedResults = speedResults,
                    effectiveSource = effectiveSource,
                    onSpeedTest = onSpeedTest,
                    onDismiss = { showRaceDialog = false },
                )
            }
        }

        // ───────── Web 界面打开方式 ─────────
        item(key = "function_webui_mode", visible = !permissionOnly) {
            ExpressiveCard(flat = flat) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    SectionHeader(
                        icon = { Icon(Icons.Filled.Layers, null, Modifier.size(20.dp)) },
                        title = stringResource(R.string.dsh_webui_mode),
                        summary = stringResource(R.string.dsh_webui_mode_summary),
                    )
                    Spacer(Modifier.height(12.dp))

                    RuntimeOption(
                        selected = webuiMode == DshWebUi.MODE_IN_APP,
                        enabled = true,
                        title = stringResource(R.string.dsh_webui_mode_in_app),
                        summary = stringResource(R.string.dsh_webui_mode_in_app_desc),
                        onSelect = { onWebuiModeChange(DshWebUi.MODE_IN_APP) },
                    )
                    RuntimeOption(
                        selected = webuiMode == DshWebUi.MODE_BROWSER,
                        enabled = true,
                        title = stringResource(R.string.dsh_webui_mode_browser),
                        summary = stringResource(R.string.dsh_webui_mode_browser_desc),
                        onSelect = { onWebuiModeChange(DshWebUi.MODE_BROWSER) },
                    )
                    RuntimeOption(
                        selected = webuiMode == DshWebUi.MODE_ASK,
                        enabled = true,
                        title = stringResource(R.string.dsh_webui_mode_ask),
                        summary = stringResource(R.string.dsh_webui_mode_ask_desc),
                        onSelect = { onWebuiModeChange(DshWebUi.MODE_ASK) },
                    )

                    // 用户脚本总开关（默认开）：用户脚本只作用于本应用自己的 Web 界面，所以
                    // 与上面「怎么打开它」同处一张卡。开关行几何与画中画卡一致（见 PipSwitchRow）。
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(4.dp))
                    PipSwitchRow(
                        title = stringResource(R.string.dsh_userscripts_master),
                        summary = stringResource(R.string.dsh_userscripts_master_summary),
                        checked = userscriptsMaster,
                        enabled = true,
                        onCheckedChange = onUserscriptsMasterChange,
                    )
                }
            }
        }

        // ───────── 画中画（主/副两个开关，独立一张卡）─────────
        // 主开关管「这个应用能不能进小窗」：悬浮菜单里的按钮、以及离开时自动进入都看它。
        // 副开关只管「离开应用时自动缩」——两者是包含关系，所以摆在同一个卡里，
        // 主开关关掉时副开关置灰（留着可点的副开关会让人以为「关了它还能自动缩」）。
        item(key = "function_webui_pip", visible = !permissionOnly) {
            ExpressiveCard(flat = flat) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    SectionHeader(
                        icon = { Icon(Icons.Outlined.PictureInPictureAlt, null, Modifier.size(20.dp)) },
                        title = stringResource(R.string.dsh_webui_pip),
                        summary = stringResource(R.string.dsh_webui_pip_summary),
                    )
                    Spacer(Modifier.height(8.dp))
                    PipSwitchRow(
                        title = stringResource(R.string.dsh_webui_pip_main),
                        summary = stringResource(R.string.dsh_webui_pip_main_summary),
                        checked = webuiPipMain,
                        enabled = true,
                        onCheckedChange = onWebuiPipMainChange,
                    )
                    PipSwitchRow(
                        title = stringResource(R.string.dsh_webui_pip_auto),
                        summary = stringResource(R.string.dsh_webui_pip_auto_summary),
                        checked = webuiPipAuto,
                        enabled = webuiPipMain,
                        onCheckedChange = onWebuiPipAutoChange,
                    )
                }
            }
        }

        // ───────── 运行时重装 ─────────
        // DshRuntime.reinstallRuntime() 早就存在，但之前 UI 里没有任何入口，
        // 而好几处报错文案（缺 pnpm / 缺 dsh）都写着「请在设置中重装运行时」。
        item(key = "function_runtime", visible = !permissionOnly) {
            ExpressiveCard(flat = flat) {
                // 「点一下 = 立即检查」这个标志要挂在**外层整卡**的点击上（见下面的 Column），
                // 而它同时要被卡片里的那次检查读到，所以声明在 Column 之前。
                var confirmAfterCheck by remember { mutableStateOf(false) }
                Column(
                    Modifier
                        .fillMaxWidth()
                        // 用户报「运行时那张外层卡片点了没反应」：里层的开关卡片（下面的
                        // ToggleSettingCard）自己接走了它那一块的点击，而外层这圈（标题行、
                        // 版本行、留白）根本没有点击接点。整张卡点一下 = 检查更新：
                        // 与里层同一个动作，查完真有可装的更新才弹确认框。
                        .combinedClickable(onClick = {
                            confirmAfterCheck = true
                            onCheckRuntimeUpdateRequested()
                        })
                        .padding(16.dp),
                ) {
                    SectionHeader(
                        icon = { Icon(Icons.Filled.Refresh, null, Modifier.size(20.dp)) },
                        title = stringResource(R.string.dsh_runtime_section),
                        summary = stringResource(R.string.dsh_runtime_summary),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = if (runtimeInstalled && runtimeVersion.isNotEmpty()) {
                            stringResource(R.string.dsh_runtime_installed_version, runtimeVersion)
                        } else if (runtimeInstalled) {
                            stringResource(R.string.dsh_runtime_section)
                        } else {
                            stringResource(R.string.dsh_runtime_not_installed)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // 有新运行时可用时明确说出来。之前完全没有这个检测：
                    // r1 的 git 依赖不全，修好了也没人告诉用户该更新。
                    var latest by remember { mutableStateOf<RuntimeCheckResult?>(null) }
                    var checking by remember { mutableStateOf(false) }
                    // confirmAfterCheck 声明在外层（整卡点击要用），这里只声明其余状态。
                    var updateConfirming by remember { mutableStateOf(false) }
                    var versionListOpen by remember { mutableStateOf(false) }
                    LaunchedEffect(runtimeInstalled, runtimeVersion, runtimeBeta, runtimeSlim, runtimeCheckRevision) {
                        if (!runtimeInstalled) {
                            latest = null
                            return@LaunchedEffect
                        }
                        if (runtimeCheckRevision == 0) return@LaunchedEffect
                        checking = true
                        latest = runCatching { onCheckRuntimeUpdate() }.getOrNull()
                        checking = false
                        val result = latest
                        // 只有「确实装得上」才弹确认框：要求更高 App 版本的更新弹了也装不了
                        if (confirmAfterCheck && result?.version != null && result.minAppVersion.isEmpty()) {
                            updateConfirming = true
                        }
                        confirmAfterCheck = false
                    }

                    // 重装确认/全新重装确认：声明在卡片层而不是下面的分支里 ——
                    // 两个 AlertDialog 在分支外渲染，变量必须在同一层可见。
                    var reinstallChoice by remember { mutableStateOf(false) }
                    var cleanConfirming by remember { mutableStateOf(false) }

                    // 已装运行时要求更高的 App 版本：整张卡先变成「请先更新应用」。
                    // 继续给「更新/重装」按钮没有意义 —— 那两条路会立刻被闸门拦下。
                    if (appUpdateRequired) {
                        requiredAppVersion?.let { req ->
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.dsh_runtime_min_app_required, req),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = onGoUpdateApp) {
                                Text(stringResource(R.string.dsh_runtime_min_app_go_update))
                            }
                        }
                    } else {
                        latest?.let { result ->
                            val line = when {
                                result.version != null && result.minAppVersion.isNotEmpty() ->
                                    stringResource(R.string.dsh_runtime_update_requires_app, result.minAppVersion)
                                result.version != null ->
                                    stringResource(R.string.dsh_runtime_update_available, result.version)
                                result.failure -> stringResource(R.string.dsh_runtime_check_failed)
                                else -> stringResource(R.string.dsh_runtime_up_to_date)
                            }
                            val color = when {
                                result.version != null && result.minAppVersion.isNotEmpty() ->
                                    MaterialTheme.colorScheme.error
                                result.version != null -> MaterialTheme.colorScheme.primary
                                result.failure -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(text = line, style = MaterialTheme.typography.bodySmall, color = color)
                        }

                        // 手动检查搬到了下面的开关卡片上（点一下卡片 = 检查），这里不再有
                        // 按钮行：更新 / 重装 / 导入 三件事分别落在 点卡片 / 版本菜单的当前行 /
                        // 菜单左下角的导入。
                        if (checking) {
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.dsh_runtime_checking),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // 运行时卡片现在就是一张**开关卡片**：整行点一下 = 立即检查更新
                    // （结果 / 确认框照旧走 confirmAfterCheck 那条路），长按 = 版本菜单。
                    // 精简/完整、测试/正式两个滑块搬进了菜单 —— 它们是「要装哪一版」的
                    // 参数，和版本列表同屏才有意义；留在卡片上就成了第二处状态。
                    ToggleSettingCard(
                        flat = true,
                        icon = Icons.Filled.CloudDownload,
                        title = stringResource(R.string.dsh_runtime_auto_check),
                        description = stringResource(R.string.dsh_runtime_auto_check_summary),
                        checked = runtimeAutoCheck,
                        onCheckedChange = onRuntimeAutoCheckChange,
                        onClick = {
                            val result = latest
                            when {
                                // 已经查到有更新：直接弹确认框，别让用户再等一次网络
                                result?.version != null && result.minAppVersion.isEmpty() ->
                                    updateConfirming = true
                                // 新运行时要求更高 App 版本：点下去也是被闸门拦下，直接指路
                                result?.version != null -> onGoUpdateApp()
                                else -> {
                                    // 长按菜单之外的这条检查路径：查完真有可装的更新就弹确认框
                                    confirmAfterCheck = true
                                    onCheckRuntimeUpdateRequested()
                                }
                            }
                        },
                        // 长按**不要求已装**：没装过的人也要能从版本菜单里挑一版装上，
                        // 而菜单里选任意一行本来就是走 switchRuntimeVersion 的安装路径。
                        onLongClick = { versionListOpen = true },
                    )
                    if (updateConfirming) {
                        AlertDialog(
                            onDismissRequest = { updateConfirming = false },
                            title = { Text(stringResource(R.string.dsh_runtime_update_confirm_title)) },
                            text = {
                                Text(
                                    stringResource(
                                        R.string.dsh_runtime_switch_confirm_text,
                                        latest?.version.orEmpty(),
                                    )
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    updateConfirming = false
                                    onReinstallRuntime(true)
                                }) { Text(stringResource(R.string.dsh_runtime_update_go)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { updateConfirming = false }) {
                                    Text(stringResource(android.R.string.cancel))
                                }
                            },
                        )
                    }
                    if (versionListOpen) {
                        RuntimeVersionDialog(
                            currentVersion = runtimeVersion,
                            slim = runtimeSlim,
                            beta = runtimeBeta,
                            onSlimChange = onRuntimeSlimChange,
                            onBetaChange = onRuntimeBetaChange,
                            onDismiss = { versionListOpen = false },
                            onLoad = onListRuntimeVersions,
                            onInstall = { entry ->
                                versionListOpen = false
                                onSwitchRuntimeVersion(entry)
                            },
                            // 当前那一行 = 重装：沿用卡片原来那个「保留数据 / 全新重装」二选一
                            onReinstallCurrent = {
                                versionListOpen = false
                                reinstallChoice = true
                            },
                            onGoUpdateApp = {
                                versionListOpen = false
                                onGoUpdateApp()
                            },
                            onImport = {
                                versionListOpen = false
                                onImportRuntime()
                            },
                        )
                    }
                    if (reinstallChoice) {
                        AlertDialog(
                            onDismissRequest = { reinstallChoice = false },
                            title = { Text(stringResource(R.string.dsh_runtime_reinstall_confirm_title)) },
                            text = { Text(stringResource(R.string.dsh_runtime_preserve_question)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    reinstallChoice = false
                                    onReinstallRuntime(true)
                                }) { Text(stringResource(R.string.dsh_runtime_preserve)) }
                            },
                            dismissButton = {
                                Row {
                                    TextButton(onClick = {
                                        reinstallChoice = false
                                        cleanConfirming = true
                                    }) { Text(stringResource(R.string.dsh_runtime_clean)) }
                                    TextButton(onClick = { reinstallChoice = false }) { Text(stringResource(android.R.string.cancel)) }
                                }
                            },
                        )
                    }
                    if (cleanConfirming) {
                        AlertDialog(
                            onDismissRequest = { cleanConfirming = false },
                            title = { Text(stringResource(R.string.dsh_runtime_clean_confirm_title)) },
                            text = { Text(stringResource(R.string.dsh_runtime_clean_confirm_text)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    cleanConfirming = false
                                    onReinstallRuntime(false)
                                }) { Text(stringResource(R.string.dsh_runtime_clean_confirm_go)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { cleanConfirming = false }) {
                                    Text(stringResource(android.R.string.cancel))
                                }
                            },
                        )
                    }
                }
            }
        }

        // ───────── 插件依赖修复 ─────────
        item(key = "function_repair_plugins", visible = !permissionOnly) {
            ExpressiveCard(flat = flat) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    SectionHeader(
                        icon = { Icon(Icons.Filled.Build, null, Modifier.size(20.dp)) },
                        title = stringResource(R.string.dsh_plugin_repair),
                        summary = stringResource(R.string.dsh_plugin_repair_summary),
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onRepairPlugins,
                        enabled = runtimeInstalled && !repairBusy,
                    ) {
                        Text(stringResource(R.string.dsh_plugin_repair_go))
                    }
                }
            }
        }

        // ───────── 安装后验证 ─────────
        item(key = "function_verify_install", visible = !permissionOnly) {
            ToggleSettingCard(
                flat = flat,
                icon = Icons.Filled.VerifiedUser,
                title = stringResource(R.string.dsh_verify_after_install),
                description = stringResource(R.string.dsh_verify_after_install_summary),
                checked = verifyAfterInstall,
                onCheckedChange = onVerifyAfterInstallChange,
            )
        }

        // ───────── 数据目录 ─────────
        //
        // **一个入口两条路**。原来是两个卡片（「在文件管理器中打开」/「直接授权给 MT 管理器」），
        // 但对用户这是同一件事：让第三方应用能访问本应用的数据目录。第二个卡片还只在装了
        // MT 管理器时出现，看起来像另一个功能，其实只是 MT 那套持久化授权的替代路径 ——
        // 它在侧栏选中本应用后会报"没有可持久化的授权"（见 [DshDocsAccess]）。
        // 所以合成一张卡：说明说一次，两条路各一个按钮，装没装 MT 只影响第二个按钮在不在。
        item(key = "function_docs_access", visible = !permissionOnly) {
            ExpressiveCard(flat = flat) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.FolderOpen, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(stringResource(R.string.dsh_docs_access_title), style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.dsh_docs_access_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // 路一：系统选择器（任何文件管理器都走这条）
                        TextButton(onClick = {
                            val authority = "${BuildConfig.APPLICATION_ID}.documents"
                            val dshHome = DshEnv.dshHome(context)
                            val base = context.dataDir.canonicalFile.path
                            val initialDocId = runCatching {
                                val p = dshHome.absolutePath
                                if (dshHome.isDirectory && p.startsWith("$base/")) "/" + p.removePrefix("$base/") else "/"
                            }.getOrDefault("/")
                            val target = DocumentsContract.buildDocumentUri(authority, initialDocId)
                            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                                .putExtra(DocumentsContract.EXTRA_INITIAL_URI, target)
                                .addFlags(
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                                )
                            if (!runCatching { context.startActivity(intent) }.isSuccess) {
                                showToast(context, R.string.dsh_docs_open_failed)
                            }
                        }) { Text(stringResource(R.string.dsh_docs_open_action)) }
                        // 路二：缺陷 ROM 兼容通道（由本应用自己发放同一份授权）。
                        // 候选按「能力」算，见 DshDocsAccess.candidates —— 默认勾选系统文件
                        // 管理器与 MT 管理器，其余能打开文件夹的应用列出来由用户勾。
                        // 已经授过（哪怕被授权方后来卸了）也要留着这个入口：撤销只在弹窗里。
                        if (docsCandidates.isNotEmpty() || docsGranted.value.isNotEmpty()) {
                            TextButton(onClick = { showGrantDocsDialog.value = true }) {
                                Text(stringResource(R.string.dsh_docs_grant_action))
                            }
                        }
                    }
                    // 已授权时把「授给了谁」写在卡片上：弹窗只在候选非空或已授权时才打得开，
                    // 而那行提示是用户判断"要不要撤销"的唯一线索。被授权方卸载后名字查不到，
                    // 退化成包名也要显示出来。
                    if (docsGranted.value.isNotEmpty()) {
                        val labels = docsGranted.value.joinToString("、") { pkg ->
                            docsCandidates.firstOrNull { it.packageName == pkg }?.label ?: pkg
                        }
                        Text(
                            text = stringResource(R.string.dsh_docs_granted_label, labels),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
        item(key = "function_clean_storage", visible = !permissionOnly) {
            // 点 = 原有的浅清理（主题 / 媒体 / 音效 / 应用缓存）；长按 = 深度清理页
            // （运行时临时文件、apt/npm 缓存、日志，以及共享存储的大文件/大文件夹）。
            // 长按挂在内层 Row 上：ExpressiveCard 的 onClick 不接受 onLongClick，而这张卡里
            // 没有别的可点元素、Row 又铺满整卡，所以触摸范围就是整张卡（与共享存储那张卡
            // 同一个写法）。
            ExpressiveCard(flat = flat) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { showCleanStorageDialog.value = true },
                            onLongClick = onOpenDeepClean,
                        )
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.CleaningServices, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(stringResource(R.string.settings_clean_storage), style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.settings_clean_storage_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // ───────── 权限管理 ─────────
        // 通道、配对与每一项原生能力都收进了这一页（分类 + 搜索）。原先它们挤在安全页上一条
        // 越接越长的列表里，想找某一项只能一路滚到底 —— 见 PermissionHubScreen 的 KDoc。
        item(key = "function_permission", visible = permissionOnly) {
            ExpressiveCard(flat = flat, onClick = onOpenPermissionHub) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    SectionHeader(
                        icon = { Icon(Icons.Filled.Security, null, Modifier.size(20.dp)) },
                        title = stringResource(R.string.dsh_perm_hub_title),
                        summary = stringResource(R.string.dsh_perm_hub_summary),
                    )
                }
            }
        }

        // ───────── 共享存储（挂载 /sdcard） ─────────
        // 它从来不是"原生能力"的一部分：原生能力是"拿到身份后能动什么"，而它管的是容器里
        // 有没有 /sdcard。原来只是凑巧画在那张卡片里，还被桥的总开关一起藏了起来 ——
        // 桥关着它照样有用（挂载层与 dsh-fs 桥都不经过原生能力桥），所以现在独立成卡。
        item(key = "function_storage_mount", visible = permissionOnly) {
            ExpressiveCard(flat = flat) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    // ── 共享存储（挂载 /sdcard） ──
                    // 一个开关同时管两件事：容器 bind 挂载 + dsh-fs 桥（两者都受黑白名单约束）。
                    // 关＝容器彻底看不到 /sdcard。长按整行进黑白名单设置。改动需重启 dsh 才在
                    // 挂载层生效（dsh-fs 侧立即生效）。
                    // 长按进黑白名单要覆盖**整张卡片**：combinedClickable 原来只挂在标题 Row 上，
                    // 而 `if (mountEnabled)` 里那几行（已授权提示 / 重启提示）在 Row 之外，
                    // 于是卡片看着一大块、实际只有标题那一小块能长按。把 clickable 提到外层
                    // Column，卡片范围内（按钮自身除外）都能长按。
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onSetMount(!mountEnabled) },
                                onLongClick = onOpenFileAccess,
                            ),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.dsh_storage_cap_title),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = stringResource(R.string.dsh_storage_mount_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            ExpressiveSwitch(
                                checked = mountEnabled,
                                onCheckedChange = onSetMount,
                            )
                        }
                        if (mountEnabled) {
                            if (allFilesGranted) {
                                Text(
                                    text = stringResource(R.string.dsh_storage_granted),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                TextButton(onClick = onOpenAllFilesSettings) {
                                    Text(stringResource(R.string.dsh_storage_need_perm))
                                }
                            }
                            Text(
                                text = stringResource(R.string.dsh_storage_restart_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

    }

    if (showCleanStorageDialog.value) CleanStorageDialog(showCleanStorageDialog)
    if (showGrantDocsDialog.value) {
        DocsGrantDialog(
            candidates = docsCandidates,
            onDismiss = { showGrantDocsDialog.value = false },
            onChanged = { docsGranted.value = DshDocsAccess.grantedPackages(context) },
        )
    }
}

/**
 * 「兼容缺陷 ROM：直接授权」弹窗 —— 多选 + 搜索。
 *
 * 候选名单由 `DshDocsAccess.candidates` 按能力算好（能选文件夹的、能打开文件夹的、以及
 * MT 管理器），默认勾选系统文件管理器与 MT。这里只负责收集勾选、逐个授权、整体撤销。
 *
 * 「撤销全部」撤的是**本对象记录下来的**那些包（不是勾选项）：一次授权可能横跨几次弹窗，
 * 勾选状态只反映这一次的操作，撤销要跟着实际授过的记录走才不会漏。
 */
@Composable
private fun DocsGrantDialog(
    candidates: List<DshDocsAccess.Candidate>,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    var checked by remember {
        mutableStateOf(candidates.filter { it.defaultOn }.map { it.packageName }.toSet())
    }
    var query by remember { mutableStateOf("") }
    val shown = candidates.filter {
        query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dsh_docs_grant_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.dsh_docs_grant_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.dsh_docs_grant_search)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (shown.isEmpty()) {
                    Text(
                        text = stringResource(R.string.dsh_docs_grant_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp).padding(top = 4.dp)) {
                        items(shown, key = { it.packageName }) { candidate ->
                            val on = candidate.packageName in checked
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .toggleable(
                                        value = on,
                                        role = Role.Checkbox,
                                        onValueChange = {
                                            checked =
                                                if (it) checked + candidate.packageName
                                                else checked - candidate.packageName
                                        },
                                    )
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // 点击与语义交给整行的 toggleable，Checkbox 本身不再吃点击
                                Checkbox(checked = on, onCheckedChange = null)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = candidate.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val picked = candidates.filter { it.packageName in checked }
                if (picked.isEmpty()) {
                    // 不关弹窗：勾一个再点，比"点了没反应"清楚
                    showToast(context, R.string.dsh_docs_grant_none)
                } else {
                    val granted = picked.count { DshDocsAccess.grant(context, it.packageName) }
                    onChanged()
                    onDismiss()
                    showToast(
                        context,
                        if (granted > 0) R.string.dsh_docs_grant_done else R.string.dsh_docs_grant_failed,
                    )
                }
            }) { Text(stringResource(R.string.dsh_docs_grant_confirm)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    DshDocsAccess.revokeAll(context)
                    onChanged()
                    onDismiss()
                    showToast(context, R.string.dsh_docs_grant_revoked)
                }) { Text(stringResource(R.string.dsh_docs_grant_revoke)) }
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            }
        },
    )
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

/**
 * 卡片里的一行布尔开关（画中画主/副两条与「Web 界面打开方式」卡里的用户脚本总开关共用）。
 *
 * 与 [RuntimeOption] 同一套行几何（左侧文字、右侧控件、vertical 8dp）；[enabled] 为 false 时
 * 文字一起变淡 —— 只把开关灰掉、字还正常黑，会让人以为「这行还能点」。
 */
@Composable
private fun PipSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
            )
        }
        Spacer(Modifier.width(8.dp))
        ExpressiveSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

/** 原生能力 → 标题串。 */
internal fun nativeCapTitleRes(cap: DshNativeBridge.Cap): Int = when (cap) {
    DshNativeBridge.Cap.NOTIFY -> R.string.dsh_native_cap_notify
    DshNativeBridge.Cap.FULL_SCREEN_NOTIFY -> R.string.dsh_native_cap_full_screen_notify
    DshNativeBridge.Cap.TOAST -> R.string.dsh_native_cap_toast
    DshNativeBridge.Cap.VIBRATE -> R.string.dsh_native_cap_vibrate
    DshNativeBridge.Cap.TORCH -> R.string.dsh_native_cap_torch
    DshNativeBridge.Cap.CLIPBOARD -> R.string.dsh_native_cap_clipboard
    DshNativeBridge.Cap.INTENT -> R.string.dsh_native_cap_intent
    DshNativeBridge.Cap.DEVICE -> R.string.dsh_native_cap_device
    DshNativeBridge.Cap.MEDIA -> R.string.dsh_native_cap_media
    DshNativeBridge.Cap.MIC -> R.string.dsh_native_cap_mic
    DshNativeBridge.Cap.CAMERA -> R.string.dsh_native_cap_camera
    DshNativeBridge.Cap.TTS -> R.string.dsh_native_cap_tts
    DshNativeBridge.Cap.CALENDAR -> R.string.dsh_native_cap_calendar
    DshNativeBridge.Cap.CONTACTS -> R.string.dsh_native_cap_contacts
    DshNativeBridge.Cap.LOCATION -> R.string.dsh_native_cap_location
    DshNativeBridge.Cap.PHONE -> R.string.dsh_native_cap_phone
    DshNativeBridge.Cap.SENSORS -> R.string.dsh_native_cap_sensors
    DshNativeBridge.Cap.NETWORK -> R.string.dsh_native_cap_network
    DshNativeBridge.Cap.VOLUME -> R.string.dsh_native_cap_volume
    DshNativeBridge.Cap.SETTINGS -> R.string.dsh_native_cap_settings
    DshNativeBridge.Cap.INSTALL -> R.string.dsh_native_cap_install
    DshNativeBridge.Cap.USAGE -> R.string.dsh_native_cap_usage
    DshNativeBridge.Cap.SMS -> R.string.dsh_native_cap_sms
    DshNativeBridge.Cap.SHELL -> R.string.dsh_native_cap_shell
    DshNativeBridge.Cap.A11Y -> R.string.dsh_native_cap_a11y
    DshNativeBridge.Cap.DISPLAY -> R.string.dsh_native_cap_display
}

internal fun accessLabelRes(cap: DshNativeBridge.Cap, access: DshNativeBridge.Access): Int = when {
    cap == DshNativeBridge.Cap.NOTIFY && access == DshNativeBridge.Access.WRITE -> R.string.dsh_native_access_send_only
    cap == DshNativeBridge.Cap.NOTIFY && access == DshNativeBridge.Access.READ -> R.string.dsh_native_access_view_only
    cap == DshNativeBridge.Cap.NOTIFY && access == DshNativeBridge.Access.READ_WRITE -> R.string.dsh_native_access_view_send
    cap == DshNativeBridge.Cap.SMS && access == DshNativeBridge.Access.WRITE -> R.string.dsh_native_access_send_only
    cap == DshNativeBridge.Cap.SMS && access == DshNativeBridge.Access.READ -> R.string.dsh_native_access_view_only
    cap == DshNativeBridge.Cap.SMS && access == DshNativeBridge.Access.READ_WRITE -> R.string.dsh_native_access_view_send
    access == DshNativeBridge.Access.WRITE -> R.string.dsh_native_access_allow
    access == DshNativeBridge.Access.READ -> R.string.dsh_native_access_read
    else -> R.string.dsh_native_access_read_write
}

/** 原生能力 → 说明串。 */
internal fun nativeCapSummaryRes(cap: DshNativeBridge.Cap): Int = when (cap) {
    DshNativeBridge.Cap.NOTIFY -> R.string.dsh_native_cap_notify
    DshNativeBridge.Cap.FULL_SCREEN_NOTIFY -> R.string.dsh_native_cap_full_screen_notify_desc
    DshNativeBridge.Cap.TOAST -> R.string.dsh_native_cap_toast_desc
    DshNativeBridge.Cap.VIBRATE -> R.string.dsh_native_cap_vibrate_desc
    DshNativeBridge.Cap.TORCH -> R.string.dsh_native_cap_torch_desc
    DshNativeBridge.Cap.CLIPBOARD -> R.string.dsh_native_cap_clipboard_desc
    DshNativeBridge.Cap.INTENT -> R.string.dsh_native_cap_intent_desc
    DshNativeBridge.Cap.DEVICE -> R.string.dsh_native_cap_device_desc
    DshNativeBridge.Cap.MEDIA -> R.string.dsh_native_cap_media_desc
    DshNativeBridge.Cap.MIC -> R.string.dsh_native_cap_mic_desc
    DshNativeBridge.Cap.CAMERA -> R.string.dsh_native_cap_camera_desc
    DshNativeBridge.Cap.TTS -> R.string.dsh_native_cap_tts_desc
    DshNativeBridge.Cap.CALENDAR -> R.string.dsh_native_cap_calendar_desc
    DshNativeBridge.Cap.CONTACTS -> R.string.dsh_native_cap_contacts_desc
    DshNativeBridge.Cap.LOCATION -> R.string.dsh_native_cap_location_desc
    DshNativeBridge.Cap.PHONE -> R.string.dsh_native_cap_phone_desc
    DshNativeBridge.Cap.SENSORS -> R.string.dsh_native_cap_sensors_desc
    DshNativeBridge.Cap.NETWORK -> R.string.dsh_native_cap_network_desc
    DshNativeBridge.Cap.VOLUME -> R.string.dsh_native_cap_volume_desc
    DshNativeBridge.Cap.SETTINGS -> R.string.dsh_native_cap_settings_desc
    DshNativeBridge.Cap.INSTALL -> R.string.dsh_native_cap_install_desc
    DshNativeBridge.Cap.USAGE -> R.string.dsh_native_cap_usage_desc
    DshNativeBridge.Cap.SMS -> R.string.dsh_native_cap_sms_desc
    DshNativeBridge.Cap.SHELL -> R.string.dsh_native_cap_shell_desc
    DshNativeBridge.Cap.A11Y -> R.string.dsh_native_cap_a11y_desc
    DshNativeBridge.Cap.DISPLAY -> R.string.dsh_native_cap_display_desc
}

/**
 * 这项能力缺权限时显示的那一行。
 *
 * 措辞按「点下去会发生什么」分成两类：运行时权限说「点这里授权」（会弹系统框），
 * 特殊权限说「点这里打开系统页」（会离开应用）。把两者写成同一句话是这类界面最常见的
 * 骗人写法 —— 用户点了以为要弹框，结果被丢进设置里。
 *
 * 「所有文件访问」不在这里：它不是 Cap，走独立的一行。
 */
internal fun capPermissionHintRes(cap: DshNativeBridge.Cap): Int = when (cap) {
    DshNativeBridge.Cap.NOTIFY -> R.string.dsh_native_need_notif_perm
    DshNativeBridge.Cap.FULL_SCREEN_NOTIFY -> R.string.dsh_native_need_full_screen_perm
    DshNativeBridge.Cap.MEDIA -> R.string.dsh_native_need_media_perm
    DshNativeBridge.Cap.MIC -> R.string.dsh_native_need_mic_perm
    DshNativeBridge.Cap.CAMERA -> R.string.dsh_native_need_camera_perm
    DshNativeBridge.Cap.CALENDAR -> R.string.dsh_native_need_calendar_perm
    DshNativeBridge.Cap.CONTACTS -> R.string.dsh_native_need_contacts_perm
    DshNativeBridge.Cap.LOCATION -> R.string.dsh_native_need_location_perm
    DshNativeBridge.Cap.PHONE -> R.string.dsh_native_need_phone_perm
    DshNativeBridge.Cap.SENSORS -> R.string.dsh_native_need_sensors_perm
    // 下面三项是特殊权限：点了会跳系统设置页，不会弹授权框
    DshNativeBridge.Cap.SETTINGS -> R.string.dsh_native_need_write_settings
    DshNativeBridge.Cap.VOLUME -> R.string.dsh_native_need_dnd_access
    DshNativeBridge.Cap.INSTALL -> R.string.dsh_native_need_install_perm
    DshNativeBridge.Cap.USAGE -> R.string.dsh_native_need_usage_perm
    DshNativeBridge.Cap.SMS -> R.string.dsh_native_need_sms_perm
    // 特权命令缺的不是 Android 权限，而是「还没选通道」：点下去跳到本页的权限通道那一段。
    // 虚拟屏也是同一条约束 —— 它要靠那条通道才能把服务端以特权身份拉起来。
    DshNativeBridge.Cap.SHELL, DshNativeBridge.Cap.DISPLAY -> R.string.dsh_native_cap_shell_need_channel
    DshNativeBridge.Cap.A11Y -> R.string.dsh_native_need_a11y_perm
    // 剩下的（toast/振动/剪贴板/分享/设备信息/网络）不需要任何权限。
    // 界面只在 cap !in capsWithPermission 时才取这一行，而这些项恒在集合里，
    // 所以这个分支实际不会被显示；给一个中性串而不是抛，免得将来加了新能力就崩。
    else -> R.string.dsh_native_need_notif_perm
}

/**
 * 能力分组。
 *
 * 顺序就是界面顺序，按「这项能力能碰到什么」递进：只是让设备发声/发光 → 只读设备状态 →
 * 读用户本人的内容 → 动采集硬件 → 看得见或接管屏幕 → 改系统状态并动用特权通道。
 * 越往下越该慎重，用户从上往下扫的时候压力是递增的。
 *
 * 分六组而不是四组的理由：原先「个人数据」一组塞了 10 项，把三类完全不同的东西混在
 * 一起 —— 用户本人的内容（日历/通讯录/短信）、采集硬件（摄像头/麦克风/媒体库/位置）、
 * 以及**读屏与操作屏幕**（无障碍/虚拟屏）。前两类拆开才看得清；后两类是全表里权限
 * 最大的两项，埋在 10 项长列表里看不见，理应单独成组。
 *
 * [caps] 必须覆盖 [DshNativeBridge.Cap] 的每一项，否则新加的能力会在界面上凭空消失，
 * 却仍然可以被 prefs 里的旧值打开。`check-native-caps.js` 盯着覆盖与不重复。
 */
internal enum class CapGroup(val titleRes: Int, val caps: List<DshNativeBridge.Cap>) {
    /**
     * 只影响这台设备的即时表现，不读也不改任何持久状态。
     *
     * 这一组的判据是**严格**的：只产出（发声、发光、震动、弹提示），不读回任何东西。
     * 剪贴板与分享面板曾经在这里，但它们能读走用户复制的内容 / 把内容交给外部 App，
     * 已经挪到各自更该在的组。
     */
    INTERACT(
        R.string.dsh_native_group_interact,
        listOf(
            DshNativeBridge.Cap.NOTIFY,
            DshNativeBridge.Cap.FULL_SCREEN_NOTIFY,
            DshNativeBridge.Cap.TOAST,
            DshNativeBridge.Cap.VIBRATE,
            DshNativeBridge.Cap.TORCH,
            // TTS 放这一组：它做的是「对着这台设备发声」，和 toast / 振动同类 ——
            // 即时表现、不读也不改任何持久状态。放在「个人内容」组会让人误以为它
            // 要读什么东西。
            DshNativeBridge.Cap.TTS,
        ),
    ),

    /**
     * 读设备与环境状态，都是只读、都不涉及个人内容。
     *
     * 「安装权限状态」在这一组：它只**读**「允许安装未知应用」这个开关，自己不会装任何
     * 东西（见 [DshNativeBridge.Cap.INSTALL] 的说明）。原先放在「更改系统状态」里，
     * 与它自己的语义自相矛盾。
     */
    SENSE(
        R.string.dsh_native_group_sense,
        listOf(
            DshNativeBridge.Cap.DEVICE,
            DshNativeBridge.Cap.NETWORK,
            DshNativeBridge.Cap.PHONE,
            DshNativeBridge.Cap.SENSORS,
            DshNativeBridge.Cap.INSTALL,
        ),
    ),

    /** 读（少数情况下写）属于用户本人的内容。这一组最该逐项想清楚。 */
    PERSONAL(
        R.string.dsh_native_group_personal,
        listOf(
            // 剪贴板放这一组：档位里有 view / view_send，它是能**读**用户复制的东西的
            // （可能是刚复制的密码）。INTERACT 那组的判据是「不读任何持久状态」，它不合。
            DshNativeBridge.Cap.CLIPBOARD,
            DshNativeBridge.Cap.CALENDAR,
            DshNativeBridge.Cap.CONTACTS,
            DshNativeBridge.Cap.SMS,
            DshNativeBridge.Cap.USAGE,
        ),
    ),

    /** 动采集硬件：拿得到画面、声音与所在位置。需要应用在前台的几项也都在这里。 */
    CAPTURE(
        R.string.dsh_native_group_capture,
        listOf(
            DshNativeBridge.Cap.MEDIA,
            DshNativeBridge.Cap.CAMERA,
            DshNativeBridge.Cap.MIC,
            DshNativeBridge.Cap.LOCATION,
        ),
    ),

    /**
     * 看得见、或者能接管用户屏幕。
     *
     * 无障碍与虚拟屏原先在「个人数据」组里，当时的理由是要让用户在**同一个地方**权衡
     * 这两条读屏路径（见 [DshNativeBridge.Cap.A11Y] / [DshNativeBridge.Cap.DISPLAY]）。
     * 独立成组保住了「同一处权衡」，同时让全表权限最大的两项不再淹没在长列表里。
     *
     * 「分享与打开链接」在这一组：它把用户交给外部 App（分享面板 / 浏览器），
     * 同样是「影响到本应用之外的界面」。
     */
    SCREEN(
        R.string.dsh_native_group_screen,
        listOf(
            // 读屏更进一步：它读的是用户此刻看的那个界面（可能是聊天窗口）
            DshNativeBridge.Cap.A11Y,
            // 虚拟屏同理，而且更甚：它拿到的是**画面本身**，还多一项「动手」的能力。
            DshNativeBridge.Cap.DISPLAY,
            DshNativeBridge.Cap.INTENT,
        ),
    ),

    /** 改系统的全局状态，或直接动用特权通道。改完不会自动恢复，所以放在最后。 */
    CONTROL(
        R.string.dsh_native_group_control,
        listOf(
            // 特权命令是全组里权限最大的一项：它不通过 Android 权限，而是借整条通道
            DshNativeBridge.Cap.SHELL,
            DshNativeBridge.Cap.VOLUME,
            DshNativeBridge.Cap.SETTINGS,
        ),
    ),
}

/**
 * 下载源 id → 可本地化标签。
 *
 * 保留这个名字只为不改动两处 Composable 的调用；真正的映射在 [DshSource.labelRes]，
 * 界面与启动日志共用同一份（原先各有一份，文案曾经漂移过）。
 */
internal fun sourceLabelRes(source: String): Int = DshSource.labelRes(source)

/**
 * 运行时版本菜单：长按运行时卡片唤出。
 *
 * 界面按用户 2026-10 定的形状：**两个两档滑块**（完整/精简、正式/测试）在顶上，改一下
 * 就重拉一次列表；列表只显示所选组合（[RuntimeVersion.matchesFilter]），当前已装那一版
 * 即使被筛掉也**钉在最上面** —— 否则滑到别的类型/通道时，"我在跑哪一版"就消失了。
 * 点当前版本 = 重装（沿用保留数据/全新重装二选一），点其它版本 = 切换到那一版。
 * 左下角是导入本地包（[AlertDialog] 的 dismissButton 就在左下角）。
 *
 * 为什么还要列历史版本：滚动 tag 只有四个位置（latest / slim / beta / slim-beta），内容
 * 会被就地覆盖，降级或回到某个具体版本只能靠带版本号的历史 release。
 */
@Composable
private fun RuntimeVersionDialog(
    currentVersion: String,
    slim: Boolean,
    beta: Boolean,
    onSlimChange: (Boolean) -> Unit,
    onBetaChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onLoad: suspend () -> List<RuntimeVersion>,
    onInstall: (RuntimeVersion) -> Unit,
    onReinstallCurrent: () -> Unit,
    onGoUpdateApp: () -> Unit,
    onImport: () -> Unit,
) {
    var versions by remember { mutableStateOf<List<RuntimeVersion>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    // 两个滑块也在 key 里：改了就重拉（列表内容由它们筛选，旧的列表已经不代表当前选择）。
    // 滑块的**落盘**在外面做（onSlimChange/onBetaChange 直接写 prefs），这里只负责重拉。
    LaunchedEffect(reloadKey, slim, beta) {
        versions = null
        failed = false
        val loaded = runCatching { onLoad() }.getOrNull()
        if (loaded.isNullOrEmpty()) failed = true else versions = loaded
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dsh_runtime_versions_title)) },
        text = {
            Column {
                RuntimeFlavorSlider(slim = slim, onSelect = onSlimChange)
                // 选了非默认那一端才解释它是什么（精简版砍了什么 / 测试版可能不稳定）。
                // 常显会把菜单顶下去，而且默认端本来就不需要解释。
                if (slim) RuntimeSliderNote(text = stringResource(R.string.dsh_runtime_slim_summary))
                Spacer(Modifier.height(6.dp))
                RuntimeChannelSlider(beta = beta, onSelect = onBetaChange)
                if (beta) RuntimeSliderNote(text = stringResource(R.string.dsh_runtime_beta_summary))
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.dsh_runtime_menu_filter_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                val list = versions
                when {
                    failed -> Column {
                        Text(stringResource(R.string.dsh_runtime_versions_failed))
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { reloadKey++ }) {
                            Text(stringResource(R.string.dsh_runtime_versions_retry))
                        }
                    }
                    list == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.dsh_runtime_versions_loading))
                    }
                    else -> {
                        val visible = list.filter { RuntimeVersion.matchesFilter(it, slim, beta) }
                        val currentVisible = visible.any { it.version == currentVersion }
                        Text(
                            text = stringResource(R.string.dsh_runtime_versions_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            // 当前已装被筛掉时钉一行在最上面：重装这条路任何时候都要够得着
                            if (currentVersion.isNotEmpty() && !currentVisible) {
                                item(key = "current-pinned") {
                                    RuntimeCurrentVersionRow(
                                        version = currentVersion,
                                        onReinstall = onReinstallCurrent,
                                    )
                                }
                            }
                            if (visible.isEmpty()) {
                                item(key = "empty") {
                                    Text(
                                        text = stringResource(R.string.dsh_runtime_menu_empty),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            items(visible, key = { it.tag + "|" + it.version }) { entry ->
                                RuntimeVersionRow(
                                    entry = entry,
                                    current = entry.version == currentVersion,
                                    onInstall = { onInstall(entry) },
                                    onReinstall = onReinstallCurrent,
                                    onGoUpdateApp = onGoUpdateApp,
                                )
                            }
                        }
                    }
                }
            }
        },
        // 左下角是导入（dismissButton 就在确认键左边），右下角是关闭
        dismissButton = {
            TextButton(onClick = onImport) { Text(stringResource(R.string.dsh_runtime_import)) }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dsh_runtime_menu_close)) }
        },
    )
}

/**
 * 版本类型滑块：完整版 ↔ 精简版。
 *
 * 用两档 `Slider`（steps = 1）而不是分段按钮：用户要的就是「滑一下切过去」，而且滑块
 * 把两端的名字摆在轨道两头，「往哪边滑是什么」一眼可见。
 */
@Composable
private fun RuntimeFlavorSlider(slim: Boolean, onSelect: (Boolean) -> Unit) {
    RuntimeTwoStopSlider(
        caption = stringResource(R.string.dsh_runtime_menu_flavor),
        leftLabel = stringResource(R.string.dsh_runtime_flavor_full),
        rightLabel = stringResource(R.string.dsh_runtime_flavor_slim),
        rightSelected = slim,
        onSelect = onSelect,
    )
}

/** 更新通道滑块：正式版 ↔ 测试版。 */
@Composable
private fun RuntimeChannelSlider(beta: Boolean, onSelect: (Boolean) -> Unit) {
    RuntimeTwoStopSlider(
        caption = stringResource(R.string.dsh_runtime_menu_channel),
        leftLabel = stringResource(R.string.dsh_runtime_channel_stable_short),
        rightLabel = stringResource(R.string.dsh_runtime_channel_beta_short),
        rightSelected = beta,
        onSelect = onSelect,
    )
}

/**
 * 两档滑块。
 *
 * @param rightSelected 当前选的是右边那一档
 * @param onSelect **只在真的换了档**时回调（落盘 + 重拉列表）；拖动过程中的中间值不回调
 *   —— 每帧都写 prefs 再触发一次网络请求，会把列表刷成幻灯片。
 */
@Composable
private fun RuntimeTwoStopSlider(
    caption: String,
    leftLabel: String,
    rightLabel: String,
    rightSelected: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    var dragging by remember { mutableStateOf(rightSelected) }
    // 外部状态（落盘后的回读、别处改了同一个开关）变了要跟着走，否则滑块会和实际选择不符
    LaunchedEffect(rightSelected) { dragging = rightSelected }
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = caption,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = if (dragging) 1f else 0f,
            onValueChange = { dragging = it >= 0.5f },
            onValueChangeFinished = { if (dragging != rightSelected) onSelect(dragging) },
            valueRange = 0f..1f,
            steps = 1,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            RuntimeSliderEndLabel(text = leftLabel, selected = !dragging)
            RuntimeSliderEndLabel(text = rightLabel, selected = dragging)
        }
    }
}

@Composable
private fun RuntimeSliderEndLabel(text: String, selected: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 滑块下面那行解释（只在选了非默认端时出现）。 */
@Composable
private fun RuntimeSliderNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** 当前已装那一版被筛选条件排除时的钉子行：只有版本号和一个「重装」。 */
@Composable
private fun RuntimeCurrentVersionRow(version: String, onReinstall: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
            .combinedClickable(onClick = onReinstall)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = version,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            RuntimeRowActionLabel(text = stringResource(R.string.dsh_runtime_reinstall))
        }
        Spacer(Modifier.height(4.dp))
        ModuleLabel(
            text = stringResource(R.string.dsh_runtime_current),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** 版本列表里的一行：版本号 + 通道标签 + dsh/node/体积，点了就切过去。 */
@Composable
private fun RuntimeVersionRow(
    entry: RuntimeVersion,
    current: Boolean,
    onInstall: () -> Unit,
    onReinstall: () -> Unit,
    onGoUpdateApp: () -> Unit,
) {
    // 这份运行时要求比当前 App 更高的版本：装上也起不来，点它只能去更新应用
    val tooOld = entry.minAppVersion.isNotEmpty() &&
        compareVersions(entry.minAppVersion, BuildConfig.VERSION_NAME) > 0
    val container =
        if (current) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(container)
            // 当前版本 = 重装（走保留数据/全新重装二选一），别的版本 = 切换过去
            .combinedClickable(
                onClick = {
                    when {
                        tooOld -> onGoUpdateApp()
                        current -> onReinstall()
                        else -> onInstall()
                    }
                },
            )
            .padding(10.dp),
    ) {
        // 版本号单独占一行：`0.1.5-rc.1-ubuntunoble-r3-beta` 这种串在等宽字体下
        // 已经接近对话框宽度，再和两个标签挤一行就会被压成两行断字
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.version,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 「这一行点下去会发生什么」直接写在行上（当前=重装，其它=切换）
            if (!tooOld) {
                RuntimeRowActionLabel(
                    text = stringResource(
                        if (current) R.string.dsh_runtime_reinstall else R.string.dsh_runtime_row_switch,
                    ),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModuleLabel(
                text = stringResource(channelLabelRes(entry.channel)),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (current) {
                ModuleLabel(
                    text = stringResource(R.string.dsh_runtime_current),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(
                R.string.dsh_runtime_version_detail,
                entry.dsh.ifEmpty { "?" },
                entry.nodeVersion.ifEmpty { "?" },
                entry.sizeBytes / 1024 / 1024,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (tooOld) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.dsh_runtime_min_app_required, entry.minAppVersion),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** 行右侧那个动作标签（重装 / 切换）。 */
@Composable
private fun RuntimeRowActionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp),
    )
}

/** 通道标签文案：正式 / 精简 / 测试 / 精简测试 / 历史版本。 */
private fun channelLabelRes(channel: String): Int = when (channel) {
    RuntimeVersion.CHANNEL_STABLE -> R.string.dsh_runtime_channel_stable
    RuntimeVersion.CHANNEL_SLIM -> R.string.dsh_runtime_channel_slim
    RuntimeVersion.CHANNEL_BETA -> R.string.dsh_runtime_channel_beta
    RuntimeVersion.CHANNEL_SLIM_BETA -> R.string.dsh_runtime_channel_slim_beta
    else -> R.string.dsh_runtime_channel_archive
}

/**
 * 竞速通道弹窗（长按卡片打开）：选通道 + 选镜像源 + 测速。
 *
 * 三件事在这里收口（用户 2026-09-25 定）：
 * 1. **通道**：勾中的通道走竞速，没勾的一律直连；总开关关掉时所有勾选都不生效（整组置灰）。
 *    运行时那一条同时取代了原来的「运行时下载源」卡片 —— 不再有第二处能设下载源。
 * 2. **镜像源**：多选、默认全选，三条通道**共用这一份**。没勾的既不被测速也不被使用；
 *    一条都不勾就是「只直连 github」。另有「自定义源」一项，填自己的 metadata 地址。
 * 3. **测速**：就地跑一轮，把每条线路的延迟/吞吐显示在它自己那一行上 —— 勾选与实测同屏，
 *    用户才能判断该留哪几条。
 */
@Composable
private fun RaceChannelDialog(
    masterEnabled: Boolean,
    racePlugins: Boolean,
    raceAppUpdate: Boolean,
    raceRuntime: Boolean,
    onChannelChange: (String, Boolean) -> Unit,
    mirrors: Set<String>,
    onMirrorToggle: (String, Boolean) -> Unit,
    customSourceEnabled: Boolean,
    onCustomSourceToggle: (Boolean) -> Unit,
    customMetaUrl: String,
    onCustomMetaUrlChange: (String) -> Unit,
    speedTesting: Boolean,
    speedResults: List<DshSource.SpeedResult>,
    effectiveSource: String,
    onSpeedTest: () -> Unit,
    onDismiss: () -> Unit,
) {
    val channels = listOf(
        Triple(DshRuntime.RACE_PLUGINS, R.string.dsh_race_channel_plugins, racePlugins),
        Triple(DshRuntime.RACE_APP_UPDATE, R.string.dsh_race_channel_app, raceAppUpdate),
        Triple(DshRuntime.RACE_RUNTIME, R.string.dsh_race_channel_runtime, raceRuntime),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dsh_race_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.dsh_race_dialog_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                channels.forEach { (id, labelRes, checked) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = checked,
                                enabled = masterEnabled,
                                role = Role.Checkbox,
                                onClick = { onChannelChange(id, !checked) },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { onChannelChange(id, !checked) },
                            enabled = masterEnabled,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(labelRes),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (masterEnabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    }
                }

                if (!masterEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.dsh_race_master_off_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.dsh_race_mirrors_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.dsh_race_mirrors_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                for (id in DshSource.allSourceIds()) {
                    val checked = id in mirrors
                    // 测速结果贴在对应那一行上：勾选与实测同屏，用户才知道该留哪几条
                    val result = speedResults.firstOrNull { it.source == id }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = checked,
                                enabled = masterEnabled,
                                role = Role.Checkbox,
                                onClick = { onMirrorToggle(id, !checked) },
                            )
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { onMirrorToggle(id, !checked) },
                            enabled = masterEnabled,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = stringResource(DshSource.labelRes(id)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (masterEnabled) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            )
                            if (result != null) {
                                val latency = result.latencyMs
                                val detail = when {
                                    latency == null -> stringResource(R.string.dsh_race_mirror_unreachable)
                                    result.speedKBps > 0.0 -> String.format(
                                        stringResource(R.string.dsh_race_mirror_measured_speed),
                                        latency.toInt(),
                                        result.speedKBps.toInt(),
                                    )
                                    else -> String.format(
                                        stringResource(R.string.dsh_race_mirror_measured_latency),
                                        latency.toInt(),
                                    )
                                }
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = onSpeedTest, enabled = !speedTesting) {
                        Text(
                            stringResource(
                                if (speedTesting) R.string.dsh_source_testing
                                else R.string.dsh_source_speedtest
                            )
                        )
                    }
                    if (speedTesting) {
                        // 进度就地报：手动测速是全量逐条测吞吐，没进度的话十来秒像个死按钮
                        val measured = speedResults.count { it.speedKBps > 0.0 }
                        val reachable = speedResults.count { it.reachable }
                        Text(
                            text = if (reachable > 0) {
                                stringResource(R.string.dsh_race_testing_progress, measured, reachable)
                            } else {
                                stringResource(R.string.dsh_source_testing)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = stringResource(
                                R.string.dsh_source_effective,
                                stringResource(DshSource.labelRes(effectiveSource)),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = customSourceEnabled,
                            enabled = masterEnabled,
                            role = Role.Checkbox,
                            onClick = { onCustomSourceToggle(!customSourceEnabled) },
                        )
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = customSourceEnabled,
                        onCheckedChange = { onCustomSourceToggle(!customSourceEnabled) },
                        enabled = masterEnabled,
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.dsh_race_custom_title),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (masterEnabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                        Text(
                            text = stringResource(R.string.dsh_race_custom_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (customSourceEnabled) {
                    OutlinedTextField(
                        value = customMetaUrl,
                        onValueChange = onCustomMetaUrlChange,
                        label = { Text(stringResource(R.string.dsh_source_custom_hint)) },
                        singleLine = true,
                        enabled = masterEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}
