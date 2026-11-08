package me.bmax.apatch.ui.screen

import android.app.Activity.RESULT_OK
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Segment
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.DshPluginStoreScreenDestination
import com.ramcosta.composedestinations.generated.destinations.ScriptMarketScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshEnv
import me.bmax.apatch.dsh.DshHostPrompt
import me.bmax.apatch.dsh.DshPlugin
import me.bmax.apatch.dsh.DshPluginRepo
import me.bmax.apatch.ui.screen.settings.UserscriptAddFab
import me.bmax.apatch.ui.screen.settings.UserscriptsContent
import me.bmax.apatch.ui.screen.settings.rememberUserscriptInstallRequest
import me.bmax.apatch.dsh.DshRuntime
import me.bmax.apatch.dsh.ext.ExtensionEngine
import me.bmax.apatch.dsh.ext.ExtensionRecord
import me.bmax.apatch.dsh.ext.ExtensionLayout
import me.bmax.apatch.dsh.ext.ExtensionRegistry
import me.bmax.apatch.dsh.ext.ExtensionSafetyGuard
import me.bmax.apatch.dsh.ext.ExtensionUiRegistry
import me.bmax.apatch.ui.component.AddMenuEntry
import me.bmax.apatch.ui.component.ExtensionMarketSheet
import me.bmax.apatch.ui.component.AddMenuFab
import me.bmax.apatch.ui.component.DshPluginDetailSheet
import me.bmax.apatch.ui.component.DshPluginProgressDialog
import me.bmax.apatch.ui.component.ModuleLabel
import me.bmax.apatch.ui.component.ScrollableEmptyState
import me.bmax.apatch.ui.component.SearchAppBar
import me.bmax.apatch.ui.component.WallpaperAwareDropdownMenu
import me.bmax.apatch.ui.component.WallpaperAwareDropdownMenuItem
import me.bmax.apatch.ui.viewmodel.DshPluginViewModel
import me.bmax.apatch.util.ui.HomeBottomSpacer
import me.bmax.apatch.util.ui.LocalSnackbarHost

/**
 * DSH 插件页（底栏「插件」）。
 *
 * 沿用 FolkPatch 模块页的视觉语言（SearchAppBar + 卡片 + ModuleLabel 小标签 + 右下 FAB），
 * 但内容与逻辑换成 DSH 插件：
 * - 标签从「大小 / 模块 id」换成 **下载量 + 星标**；
 * - 保留 **可更新** 标签；
 * - 右上角进 **插件商店**（dsh-market）；脚本那一组则进 **脚本市场**；
 * - 右下 FAB 是 **本地安装**，两组都有：插件组选一个 `.tgz`，脚本组选一个 `.user.js`。
 */
/**
 * 这一页的两组内容。用户脚本的入口原本挂在「设置 → 功能」右上角，现在收到这里 ——
 * 它和 DSH 插件是同一件事的两半（都往容器/页面里塞东西），分成两页反而要用户记住入口在哪。
 */
private const val GROUP_PLUGINS = "plugins"
private const val GROUP_SCRIPTS = "scripts"

/**
 * 第三页：扩展。
 *
 * 它和另外两页不是一回事：插件与脚本装的是「容器里的东西」，扩展会被加载进 **App 自己的
 * 进程**、与宿主同权（root 通道、无障碍、全部文件）。所以这一页的重点是「怎么把它们关掉」，
 * 见 [ExtensionsPane]。
 */
private const val GROUP_EXTENSIONS = "extensions"

/** 「移除」二次确认态自己复位的时间：变红了却忘了再点一下，按钮不该一直停在危险态。 */
private const val ARMED_REVERT_MS = 8_000L

/** 切换器里的一页：[id] 是分组常量，[titleRes] 是菜单与标题栏上的名字。 */
private class PluginPage(val id: String, val titleRes: Int)

/** 三页平级，顺序即菜单顺序 —— 切换器与标题栏都照这一份走，不会再各写一遍 when。 */
private val PLUGIN_PAGES = listOf(
    PluginPage(GROUP_PLUGINS, R.string.dsh_plugins),
    PluginPage(GROUP_SCRIPTS, R.string.dsh_userscripts_title),
    PluginPage(GROUP_EXTENSIONS, R.string.dsh_plugins_extensions),
)

@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun DshPluginScreen(navigator: DestinationsNavigator) {
    val viewModel = viewModel<DshPluginViewModel>()
    val snackBarHost = LocalSnackbarHost.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val runtimeInstalled = remember { DshEnv.isRuntimeInstalled(context) }

    // 当前显示哪一组；以及脚本那一组的过滤词（与插件那边同样是**过滤**语义，所以共用搜索栏）。
    var group by rememberSaveable { mutableStateOf(GROUP_PLUGINS) }
    var scriptFilter by rememberSaveable { mutableStateOf("") }
    // 扩展页自己的过滤词（与插件页的 viewModel.search 分开：两页的列表来源完全不同，
    // 共用一个词会让「切页之后搜索框里还留着上一页的词、结果却是另一份列表」）
    var extensionFilter by rememberSaveable { mutableStateOf("") }
    // 扩展市场弹层开关（扩展页 ＋ 菜单里那颗）
    var showMarket by remember { mutableStateOf(false) }
    // 市场装完要让扩展列表重读。列表的事实来源在 ExtensionsPane 内部（磁盘 + prefs，没有可观察流），
    // 所以用一个自增信号从外面捅它一下，而不是把整份列表状态提到页面级。
    var extensionRefresh by remember { mutableStateOf(0) }
    // 「隐藏内置」与脚本页那一页共用同一个 pref（两处看的是同一件事）。
    var hideBuiltins by remember { mutableStateOf(DshEnv.userscriptsHideBuiltins(context)) }
    // 脚本那组的安装请求：这一页的顶栏链接图标与右下角 FAB 只发请求，选择器与弹窗仍在
    // 共用的 UserscriptsContent 里（两处宿主不会各写一套安装逻辑）。
    val scriptInstall = rememberUserscriptInstallRequest()

    // 右上角那颗「切换页面」的菜单是否展开
    var groupMenu by remember { mutableStateOf(false) }
    // 「从链接安装」弹窗：入口从插件商店搬到了插件页的 ＋ 里（商店那边只留列表与详情）。
    var showLinkInstall by remember { mutableStateOf(false) }
    var linkInput by remember { mutableStateOf("") }
    var linkError by remember { mutableStateOf(false) }

    LaunchedEffect(runtimeInstalled) {
        if (runtimeInstalled && viewModel.plugins.isEmpty()) viewModel.refresh()
    }

    // 本地安装：容器内只看得到 rootfs 内的路径，所以先把用户选的 .tgz 落到
    // /root/.dsh/incoming，再把容器绝对路径交给 dsh plugin add
    val pickTarball = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@rememberLauncherForActivityResult
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        scope.launch {
            val guest = withContext(Dispatchers.IO) { DshPluginRepo.stageTarball(context, uri) }
            if (guest == null) {
                snackBarHost.showSnackbar(context.getString(R.string.dsh_plugin_local_read_failed))
                return@launch
            }
            viewModel.installLocal(guest)
        }
    }

    // 扩展市场弹层：放在**页面级**而不是 ExtensionsPane 里 —— 它装完要让列表重读，
    // 而列表的事实来源在 ExtensionsPane 内部，所以靠 extensionRefresh 这个自增信号从外面捅它。
    if (showMarket) {
        ExtensionMarketSheet(
            onDismiss = { showMarket = false },
            // 装完只重读列表、不自动启用：信任确认必须是用户看得见的那一步，
            // 市场替用户点掉它等于绕过「这个扩展将获得完整权限」的告知。
            onInstalled = { extensionRefresh++ },
        )
    }

    Scaffold(
        topBar = {
            SearchAppBar(
                title = {
                    Text(
                        stringResource(
                            when (group) {
                                GROUP_SCRIPTS -> R.string.dsh_userscripts_title
                                // 标题栏用扩展自己的串（与菜单里那行「扩展」同一份文案）：
                                // 菜单是给三页做索引的，标题说的是「你现在在哪一页」，两者分开取。
                                GROUP_EXTENSIONS -> R.string.dsh_ext_title
                                else -> R.string.dsh_plugins
                            }
                        )
                    )
                },
                // 两组的搜索都是「过滤已加载的列表」：插件过滤已装插件，脚本过滤「我装的」。
                // 语义一样才敢共用一个框 —— 换成"去 GreasyFork 搜"就必须分开（那个框在市场那一页上）。
                searchText = when (group) {
                    GROUP_SCRIPTS -> scriptFilter
                    GROUP_EXTENSIONS -> extensionFilter
                    else -> viewModel.search
                },
                onSearchTextChange = {
                    when (group) {
                        GROUP_SCRIPTS -> scriptFilter = it
                        GROUP_EXTENSIONS -> extensionFilter = it
                        else -> viewModel.search = it
                    }
                },
                onClearClick = {
                    when (group) {
                        GROUP_SCRIPTS -> scriptFilter = ""
                        GROUP_EXTENSIONS -> extensionFilter = ""
                        else -> viewModel.search = ""
                    }
                },
                // 三页都给搜索：扩展页按 名称/id/说明/声明能力 过滤（与插件页同一套「过滤已加载列表」语义）。
                searchEnabled = true,
                dropdownContent = {
                    // 刷新只对插件那组有意义；脚本那组的「重读列表」在安装/删除后自己会做。
                    if (group == GROUP_PLUGINS) {
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                        }
                    }
                    // 隐藏内置脚本：只作用于脚本组那份列表（hideBuiltins 传给 UserscriptsContent），
                    // 所以也只在脚本组显示 —— 在别的组里按了没有可见变化，那是按钮失灵的样子。
                    // 图标说「按下去会发生什么」：正在显示内置 → 给「隐藏」的眼睛；已经隐藏 → 给睁开的眼睛。
                    if (group == GROUP_SCRIPTS) {
                        IconButton(onClick = {
                            val next = !hideBuiltins
                            hideBuiltins = next
                            DshEnv.setUserscriptsHideBuiltins(context, next)
                        }) {
                            Icon(
                                if (hideBuiltins) Icons.Outlined.Visibility
                                else Icons.Outlined.VisibilityOff,
                                contentDescription = stringResource(
                                    if (hideBuiltins) R.string.dsh_userscripts_show_builtin
                                    else R.string.dsh_userscripts_hide_builtin
                                ),
                            )
                        }
                    }
                    // 页面切换器：原先底下那排 FilterChip 撤了，收进这颗按钮（用户：「右上角增加一个
                    // 按钮（原来的商店入口处）供切换」）。三页平级，所以菜单里列出三页并标出当前页。
                    IconButton(onClick = { groupMenu = true }) {
                        Icon(
                            Icons.Filled.Segment,
                            contentDescription = stringResource(R.string.dsh_plugins_switch),
                        )
                    }
                    WallpaperAwareDropdownMenu(
                        expanded = groupMenu,
                        onDismissRequest = { groupMenu = false },
                    ) {
                        for (page in PLUGIN_PAGES) {
                            WallpaperAwareDropdownMenuItem(
                                text = { Text(stringResource(page.titleRes)) },
                                onClick = {
                                    group = page.id
                                    groupMenu = false
                                },
                                leadingIcon = {
                                    // 当前页打勾（而不是换颜色）：菜单收起后三行长得一样，
                                    // 勾一眼能看出「现在在哪一页」
                                    if (page.id == group) {
                                        Icon(Icons.Filled.Check, contentDescription = null)
                                    } else {
                                        Spacer(Modifier.size(24.dp))
                                    }
                                },
                            )
                        }
                    }
                    // 顶栏不再有商店入口：三页的商店与安装都收进了右下角那个 ＋（见 [AddMenuFab]）。
                    // 用户要撤掉的正是「右上角那个商店入口」—— 顶栏再留一份就是同一件事的第二个说法。
                },
            )
        },
        floatingActionButton = {
            // 三页共用一个「＋ 环绕菜单」（[AddMenuFab]）：
            //   - 插件页 → 链接 + 文件 + 商店（链接安装这一颗是从插件商店搬过来的）；
            //   - 脚本页 → 链接 + 文件 + 商店（[UserscriptAddFab] 把三个动作交给同一份实现）；
            //   - 扩展页 → 没有 App 内的安装入口（装法是把目录放进 files/extensions/），不画。
            when (group) {
                GROUP_SCRIPTS -> {
                    // 正文（UserscriptsContent）只在运行时已装时组合；没装时这一组的界面是「需要先装
                    // 运行时」，脚本装了也无处可跑。所以这一格干脆不画，而不是画一个点了没反应的 ＋
                    // （旧写法就是这样：按钮在，点了什么也不会发生）。
                    if (runtimeInstalled) {
                        UserscriptAddFab(
                            installRequest = scriptInstall,
                            onOpenMarket = { navigator.navigate(ScriptMarketScreenDestination(initialQuery = "")) },
                        )
                    }
                }
                // 扩展页的 ＋ 只有「装」这一个语义：市场（远端）与从目录（本地）。
                // 市场是这一轮才开放的；仍然不画「商店」那一条 —— 扩展没有 BSD 那种第三方源。
                GROUP_EXTENSIONS -> AddMenuFab(
                    listOf(
                        AddMenuEntry(
                            icon = Icons.Outlined.Storefront,
                            labelRes = R.string.dsh_ext_market,
                            onClick = { showMarket = true },
                        ),
                    ),
                )
                GROUP_PLUGINS -> AddMenuFab(
                    listOf(
                        AddMenuEntry(
                            icon = Icons.Outlined.Link,
                            labelRes = R.string.dsh_plugin_install_link,
                            onClick = { showLinkInstall = true },
                        ),
                        AddMenuEntry(
                            icon = Icons.Outlined.FolderOpen,
                            labelRes = R.string.dsh_local_install,
                            onClick = {
                                pickTarball.launch(
                                    Intent(Intent.ACTION_GET_CONTENT).apply {
                                        type = "*/*"
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                    }
                                )
                            },
                        ),
                        AddMenuEntry(
                            icon = Icons.Outlined.Storefront,
                            labelRes = R.string.dsh_plugin_store,
                            onClick = { navigator.navigate(DshPluginStoreScreenDestination) },
                        ),
                    )
                )
                else -> Unit
            }
        },
    ) { innerPadding ->
        // 扩展页不看运行时：扩展跑在 App 自己的进程里，与容器毫无关系。没装运行时的用户
        // 同样要能进这一页 —— 尤其是某个扩展把 App 弄崩之后，这里是他唯一能把它关掉的地方。
        if (!runtimeInstalled && group != GROUP_EXTENSIONS) {
            DshRuntimeNeeded(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                onGoHome = { navigator.popBackStack() },
            )
        } else {
            Column(Modifier.fillMaxSize().padding(innerPadding)) {
                // 切换器在顶栏（原来那排 FilterChip 撤了），所以这里直接按当前页铺内容。
                when (group) {
                    // 用户脚本：与「设置 → 用户脚本」那一页**同一份正文**（UserscriptsContent）。
                    // 入口搬过来之后两处各留一份实现，很快就会各长各的 —— 那正是"对齐 UI"要防的事。
                    GROUP_SCRIPTS -> UserscriptsContent(
                        modifier = Modifier.weight(1f),
                        filter = scriptFilter,
                        hideBuiltins = hideBuiltins,
                        installRequest = scriptInstall,
                        // 没记来源的那条：更新去市场按名字找一遍
                        onOpenMarket = { navigator.navigate(ScriptMarketScreenDestination(initialQuery = it)) },
                    )
                    // 扩展页：真实列表 + 安全模式横幅（见 [ExtensionsPane]）。
                    GROUP_EXTENSIONS -> ExtensionsPane(
                        modifier = Modifier.weight(1f),
                        snackBarHost = snackBarHost,
                        filter = extensionFilter,
                        externalRefresh = extensionRefresh,
                    )
                    else -> DshPluginList(
                        // 外层 Column 已经吃掉 scaffold 的 inset，这里不能再吃一遍
                        innerPadding = PaddingValues(0.dp),
                        viewModel = viewModel,
                        snackBarHost = snackBarHost,
                    )
                }
            }
        }
    }

    // 「从链接安装」（插件页的 ＋ 里那一颗）：与脚本那套一样，粘 owner/name 或仓库地址即可。
    // 这段弹窗原来在插件商店页，用户要求把入口从商店移到 ＋ 下面，于是整段搬过来。
    if (showLinkInstall) {
        AlertDialog(
            onDismissRequest = { showLinkInstall = false },
            title = { Text(stringResource(R.string.dsh_plugin_install_link)) },
            text = {
                Column {
                    Text(stringResource(R.string.dsh_plugin_install_link_desc))
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = linkInput,
                        onValueChange = { linkInput = it; linkError = false },
                        singleLine = true,
                        isError = linkError,
                        placeholder = { Text("owner/name") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (linkError) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.dsh_plugin_install_link_invalid),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // 归一化受「GitHub 插件镜像」开关约束：install() 内部会走 installGitSpec
                    val spec = DshPluginRepo.normalizeInstallSpec(linkInput)
                    if (spec == null) {
                        linkError = true
                    } else {
                        showLinkInstall = false
                        linkInput = ""
                        viewModel.install(spec)
                    }
                }) { Text(stringResource(R.string.dsh_plugin_install_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showLinkInstall = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    // 安装/卸载进度：pnpm 可能跑几分钟，不能只在结束后弹一条 snackbar
    PluginProgressHost(viewModel)
}

/**
 * 安装进度对话框的宿主（商店页与已安装页共用）。
 *
 * 运行中、或运行完但日志还没被关掉时都显示 —— 失败日志必须能被留住阅读，
 * 而不是一闪而过。
 */
@Composable
internal fun PluginProgressHost(viewModel: DshPluginViewModel) {
    val clipboard = LocalClipboardManager.current

    // 构建脚本放行确认。摆在进度对话框之前：它是对刚失败那次安装的处置，
    // 用户该先看到「要不要放行」，而不是先关掉日志再自己想起来重装。
    viewModel.buildApproval?.let { ask ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissBuildApproval() },
            title = { Text(stringResource(R.string.dsh_plugin_allow_builds_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.dsh_plugin_allow_builds_text, ask.target),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    for (p in ask.packages) {
                        Text(
                            text = "• $p",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.dsh_plugin_allow_builds_warn),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.approveBuilds() }) {
                    Text(stringResource(R.string.dsh_plugin_allow_builds_go))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissBuildApproval() }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    // 「隐藏窗口」只是把对话框收起来，操作还在跑 —— 不能因为收起来就把它当成结束
    val visible = (viewModel.installing || viewModel.installLog.isNotEmpty()) && !viewModel.progressHidden
    if (!visible) return
    DshPluginProgressDialog(
        target = viewModel.installTarget,
        lines = viewModel.installLog,
        running = viewModel.installing,
        failed = viewModel.installFailed,
        // 停止是用户的意图：只有操作真的结束之后才按「已停止」显示
        stopped = viewModel.installStopped && !viewModel.installing,
        // 点了停止但容器还没收尾：标题给「正在停止…」，让点击立刻有反馈
        stopping = viewModel.installStopped && viewModel.installing,
        actionKind = viewModel.installActionKind,
        onStop = { viewModel.stopInstall() },
        onHide = { viewModel.hideProgress() },
        onDismiss = { viewModel.dismissInstallLog() },
        onCopy = { clipboard.setText(AnnotatedString(it)) },
        onRestart = {
            viewModel.clearNeedsRestart()
            DshRuntime.restart()
        },
        // 装全局 CLI 那类操作不动插件树，别提示「重启后生效」。
        // needsRestart 由 run() 在成功时按操作类型置位。
        needsRestart = viewModel.needsRestart,
    )
}

/**
 * 运行时未安装时的引导卡（已安装页与商店页共用）。
 *
 * 插件与商店都依赖容器里的 dsh：未装运行时既查不到已装插件，也不该发网络请求。
 */
@Composable
internal fun DshRuntimeNeeded(
    modifier: Modifier = Modifier,
    onGoHome: () -> Unit,
) {
    // 可滚动：否则底栏自动隐藏后无法下拉唤回（见 ScrollableEmptyState）
    ScrollableEmptyState(modifier) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.dsh_plugin_needs_runtime),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onGoHome) {
                Text(stringResource(R.string.dsh_plugin_go_home))
            }
        }
    }
}

@Composable
private fun DshPluginList(
    innerPadding: PaddingValues,
    viewModel: DshPluginViewModel,
    snackBarHost: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val list = viewModel.filtered
    val builtInVisible = viewModel.search.isBlank() ||
        context.getString(R.string.dsh_host_plugin_name).contains(viewModel.search, ignoreCase = true)
    // 只存 id，实体每次从列表取：装/卸/切换之后列表会刷新，
    // 存快照的话详情面板里的开关会停在打开那一刻的状态（与商店页同一纪律）
    var detailId by remember { mutableStateOf<String?>(null) }
    val detail = detailId?.let { id ->
        viewModel.plugins.firstOrNull { (it.pkg.ifEmpty { it.id }) == id }
    }

    // 「移除」的二次确认态：存的是列表键，同一时刻最多一项是红的。
    // 第一下只变红，第二下才真的卸载；点别处、8 秒不动、列表刷新都会复位。
    var pendingUninstall by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(pendingUninstall) {
        if (pendingUninstall != null) {
            delay(ARMED_REVERT_MS)
            pendingUninstall = null
        }
    }
    // 搜索变了、列表重载了，之前那颗红按钮对应的项可能已经不在列表里：复位，免得指向幽灵
    LaunchedEffect(viewModel.search, list) { pendingUninstall = null }
    // 滚动也算「点别处」：滚走了还留着红按钮没有意义
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) pendingUninstall = null
    }
    // 「为什么这次点击没生效」这类一次性提示（例如运行中又点了别的动作）
    LaunchedEffect(viewModel.transientMessage) {
        viewModel.transientMessage?.let {
            viewModel.consumeTransientMessage()
            snackBarHost.showSnackbar(it)
        }
    }

    detail?.let { p ->
        DshPluginDetailSheet(
            plugin = p,
            onDismiss = { detailId = null; pendingUninstall = null },
            // 用 addSpec 而不是 pkg：目录里 23 条没有 npm 包名的条目，pkg 是空串，
            // 走 addSpec 才会取到 github:owner/name。商店页一直用的是 addSpec，
            // 详情页这里漏了 —— 同一份数据在两个入口给出不同结果，正是要避免的。
            onInstall = { viewModel.install(p.addSpec, fallbackTgz = p.tarball) },
            onUpdate = { viewModel.update(p.pkg.ifEmpty { p.addSpec }) },
            onUninstall = { viewModel.uninstall(p.pkg) },
            onToggle = { viewModel.setDisabled(p.pkg, !p.disabled) },
            onOpenRepo = { openPluginRepo(context, p) { msg -> scope.launch { snackBarHost.showSnackbar(msg) } } },
        )
    }

    if (list.isEmpty() && !builtInVisible) {
        // 必须可滚动：否则底栏自动隐藏后无法下拉唤回（见 ScrollableEmptyState）
        ScrollableEmptyState(Modifier.padding(innerPadding)) {
            // 三态分开：正在读 / 一个都没装 / 装了但搜索没命中。
            // 合成一条会让搜不到时误报「尚未安装任何插件」。
            val query = viewModel.search
            Text(
                text = when {
                    viewModel.isRefreshing -> stringResource(R.string.dsh_plugin_loading)
                    viewModel.plugins.isNotEmpty() && query.isNotBlank() ->
                        stringResource(R.string.dsh_plugin_no_match, query)
                    else -> stringResource(R.string.dsh_plugin_empty)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            // 点空白处 = 「别处」：被按钮/卡片消费掉的手势不会冒到这里，所以它们照常工作
            .pointerInput(pendingUninstall) {
                detectTapGestures { if (pendingUninstall != null) pendingUninstall = null }
            },
        contentPadding = PaddingValues(
            top = innerPadding.calculateTopPadding() + 8.dp,
            start = 16.dp,
            end = 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 刷新失败要看得见：旧实现把失败吞掉，用户只看到「刷新不管用」
        if (viewModel.refreshError.isNotEmpty()) {
            item(key = "refresh-error") {
                Text(
                    text = stringResource(R.string.dsh_plugin_refresh_failed, viewModel.refreshError),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        // 窗口被「隐藏窗口」收起来时，这里就是把它叫回来的入口：
        // 更新还在跑，不能让用户找不到它（以前运行中那颗「关闭」还是禁用的）。
        if (viewModel.installing && viewModel.progressHidden) {
            item(key = "plugin-progress-hidden") {
                TextButton(onClick = { viewModel.showProgress() }) {
                    Text(stringResource(R.string.dsh_plugin_show_progress))
                }
            }
        }
        if (builtInVisible) {
            item(key = "builtin:dsh-folk-host") {
                BuiltInHostPluginItem()
            }
        }
        items(list, key = { it.pkg.ifEmpty { it.id } }) { plugin ->
            val key = plugin.pkg.ifEmpty { plugin.id }
            DshPluginItem(
                plugin = plugin,
                showMoreInfo = viewModel.showMoreInfo,
                onUpdate = {
                    pendingUninstall = null
                    viewModel.update(plugin.pkg)
                },
                uninstallArmed = pendingUninstall == key,
                onUninstallClick = {
                    if (pendingUninstall == key) {
                        // 第二下：确认过了，真的卸载
                        pendingUninstall = null
                        viewModel.uninstall(plugin.pkg)
                    } else {
                        // 第一下：只进入确认态，不发任何命令
                        pendingUninstall = key
                    }
                },
                onToggle = {
                    pendingUninstall = null
                    viewModel.setDisabled(plugin.pkg, !plugin.disabled)
                },
                onOpenDetail = {
                    pendingUninstall = null
                    detailId = key
                },
            )
        }
        item { HomeBottomSpacer() }
    }
}

/**
 * 扩展页正文：已安装扩展列表 + 安全模式横幅。
 *
 * 与插件 / 用户脚本两页最大的不同：扩展不是容器里的东西，它会被加载进**本进程**、与宿主同权。
 * 所以这一页的重点不是「装了哪些」，而是「哪几个开着、怎么把它们关掉」：
 *  - 安全模式横幅固定在最上面、不随列表滚动 —— 熔断之后它就是唯一的出口；
 *  - 开关一关就立刻 unload，不是等下次启动（否则开关写着「未启用」而扩展的行为还在）；
 *  - 移除沿用插件页那套两段式确认（第一下变红，第二下才真的删目录）。
 */
@Composable
private fun ExtensionsPane(
    modifier: Modifier = Modifier,
    snackBarHost: SnackbarHostState,
    /** 顶栏搜索框里的词（由插件页统一持有，与脚本页各用各的）。 */
    filter: String = "",
    /** 外部要求重读列表的信号（市场装完一个扩展之后会 +1）。 */
    externalRefresh: Int = 0,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 注册表的事实来源是磁盘（files/extensions/）加 SharedPreferences，没有可观察的数据流。
    // 所以拿自增的 refreshTick 当 remember 的 key：开关 / 移除之后 +1，等于重读一次磁盘。
    var refreshTick by remember { mutableStateOf(0) }
    val allRecords = remember(refreshTick, externalRefresh) {
        ExtensionRegistry.listInstalled(context)
    }
    // 过滤 + 排序：与插件页同一套「过滤已加载列表」语义。排序改成「启用在前、名称升序」——
    // 插件页那套「有更新优先」在这里没有对应概念（扩展没有远端版本可对比）。
    val records = remember(allRecords, filter) {
        val q = filter.trim()
        allRecords
            .filter { rec ->
                q.isEmpty() ||
                    rec.manifest.name.contains(q, ignoreCase = true) ||
                    rec.id.contains(q, ignoreCase = true) ||
                    rec.manifest.description.contains(q, ignoreCase = true) ||
                    rec.manifest.declaredCaps.any { it.contains(q, ignoreCase = true) }
            }
            .sortedWith(
                compareByDescending<ExtensionRecord> { it.enabled }
                    .thenBy { it.manifest.name.lowercase() },
            )
    }
    // 安全模式不进 remember：刚在崩溃页点过「禁用全部扩展」，回到这一页时熔断位已经变了，
    // 缓存住就会显示成「一切正常」，恰好把用户刚做的处置藏起来。
    val safeMode = ExtensionSafetyGuard.isSafeMode(context)
    // 原因优先用本进程熔断时记下的那句；若磁盘上只是有标志文件（比如用户自己建的），
    // 就把那个文件的绝对路径摆出来 —— 至少让他知道该去删哪个文件。
    val safeReason = if (safeMode) {
        ExtensionSafetyGuard.reason.ifEmpty {
            ExtensionSafetyGuard.findKillSwitch(context)?.absolutePath.orEmpty()
        }
    } else {
        ""
    }
    // 「重新启用扩展」的二次确认：它等于允许那些可能把 App 弄崩的代码下次启动时回来。
    var confirmReenable by remember { mutableStateOf(false) }
    // 「这个扩展将获得完整权限」的确认：非空 = 正在为这一项做授权确认。
    // 为什么放在**打开开关**这一刻而不是安装时：当前没有 App 内安装入口（装法是把目录放进
    // files/extensions/），而「开关打开」才是扩展真正获得宿主权限的瞬间。用户从别处拷进来一个
    // 扩展，界面上最该看到这句话的就是这里。
    var trustRecord by remember { mutableStateOf<ExtensionRecord?>(null) }
    // 详情弹层：只存「哪一条」，实体每次从 records 里按 id 取 —— 开关/移除之后列表会重读，
    // 存快照的话弹层里的开关会停在打开那一刻的状态（与插件详情弹层同一纪律）。
    var detailId by remember { mutableStateOf<String?>(null) }
    val detailRecord = records.firstOrNull { it.id == detailId }
        ?: (allRecords.firstOrNull { it.id == detailId })

    // 「移除」的二次确认态：存扩展 id，同一时刻最多一项是红的。
    // 范式与下方插件列表的 pendingUninstall 完全一致（第一下只变红、第二下才执行，
    // 点别处 / 8 秒 / 滚动 / 列表重读都会复位）。同一页里两种「移除」手感不同是最糟的。
    var pendingUninstall by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(pendingUninstall) {
        if (pendingUninstall != null) {
            delay(ARMED_REVERT_MS)
            pendingUninstall = null
        }
    }
    // 列表重读（启用 / 停用 / 移除）之后，那颗红按钮对应的项可能已经不在了：复位，免得指向幽灵
    LaunchedEffect(refreshTick) { pendingUninstall = null }
    // 滚动也算「点别处」：滚走了还留着红按钮没有意义
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) pendingUninstall = null
    }

    Column(modifier.fillMaxSize()) {
        // 扩展页顶部槽位。这是扩展系统里第一处真正被宿主渲染的槽位 ——
        // 以前 slotEntries 只有注册、没有渲染点，所以「能插内容」这件事在代码层面并不成立。
        // 渲染放在安全模式横幅之前：扩展内容属于「页面内容」，不该盖在熔断横幅上面
        // （熔断时 slotEntries 本身也会返回空）。
        ExtensionSlotHost(
            slot = ExtensionUiRegistry.Slot.EXTENSIONS_PAGE_TOP,
            modifier = Modifier.fillMaxWidth(),
        )
        if (safeMode) {
            ExtensionSafeModeBanner(
                reason = safeReason,
                onReenable = { confirmReenable = true },
            )
        }
        if (records.isEmpty()) {
            // 空态分两种：一个都没装（该告诉他怎么装）与搜索没命中（该告诉他换个词）。
            // 混成一句「还没有安装扩展」会让搜过东西的人以为自己的扩展被弄丢了。
            val filteredOut = allRecords.isNotEmpty() && filter.isNotBlank()
            // 必须可滚动：否则底栏自动隐藏后无法下拉唤回（见 ScrollableEmptyState）
            ScrollableEmptyState(Modifier.weight(1f)) {
                Column(
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(
                            if (filteredOut) R.string.dsh_ext_empty_filtered else R.string.dsh_ext_empty,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(
                            if (filteredOut) {
                                R.string.dsh_ext_empty_filtered_hint
                            } else {
                                R.string.dsh_ext_install_hint
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // 点空白处 = 「别处」：被按钮 / 卡片消费掉的手势不会冒到这里，所以它们照常工作
                    .pointerInput(pendingUninstall) {
                        detectTapGestures { if (pendingUninstall != null) pendingUninstall = null }
                    },
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(records, key = { it.id }) { record ->
                    ExtensionItem(
                        record = record,
                        removeArmed = pendingUninstall == record.id,
                        onClick = { detailId = record.id },
                        onToggle = { checked ->
                            pendingUninstall = null
                            val appCtx = context.applicationContext
                            // 打开 = 授予完整权限，必须先让用户看懂这件事；关闭是安全方向，直接执行。
                            if (checked) {
                                trustRecord = record
                            } else {
                                ExtensionRegistry.setEnabled(appCtx, record.id, false)
                                // 关掉不只是「下次启动别加载」：这个实例现在还在内存里跑，
                                // 它注册的 Hook / SPI / UI 槽位都还挂着。立即卸掉，否则开关
                                // 显示「未启用」而扩展的行为还在 —— 那是最容易误判的一种状态。
                                ExtensionEngine.unload(appCtx, record.id)
                                refreshTick++
                            }
                            // 打开的方向不在这里生效：先走信任确认（见下面的信任对话框），
                            // 用户点「我了解」之后才真正 setEnabled。否则一次误触就等于授权。
                        },
                        onRemoveClick = {
                            if (pendingUninstall == record.id) {
                                // 第二下：确认过了。先卸载再删目录 —— 反过来的话 onUnload 跑在
                                // 目录已经消失之后，扩展自己收尾时会踩空。
                                pendingUninstall = null
                                val appCtx = context.applicationContext
                                ExtensionEngine.unload(appCtx, record.id)
                                ExtensionRegistry.remove(appCtx, record.id)
                                refreshTick++
                            } else {
                                // 第一下：只进入确认态，什么都不删
                                pendingUninstall = record.id
                            }
                        },
                    )
                }
                item { HomeBottomSpacer() }
            }
        }
    }

    // 详情弹层：单击卡片进这里。动作（开关 / 移除）仍然留在卡片上 ——
    // 进弹层是为了「看清楚它是什么」，不是为了把常用动作藏起来。
    detailRecord?.let { rec ->
        ExtensionDetailSheet(
            record = rec,
            onDismiss = { detailId = null },
        )
    }

    // 信任确认：把「它会以完整权限运行」这件事说清楚，并列出扩展自己声明的能力。
    trustRecord?.let { rec ->
        AlertDialog(
            onDismissRequest = { trustRecord = null },
            title = { Text(stringResource(R.string.dsh_ext_trust_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.dsh_ext_trust_body, rec.manifest.name),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (rec.manifest.declaredCaps.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(
                                R.string.dsh_ext_caps,
                                rec.manifest.declaredCaps.joinToString(", "),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val appCtx = context.applicationContext
                    ExtensionRegistry.setEnabled(appCtx, rec.id, true)
                    trustRecord = null
                    refreshTick++
                    // 启用后要重启才真正加载（运行期不做热加载，理由见 onToggle）
                    scope.launch {
                        snackBarHost.showSnackbar(context.getString(R.string.dsh_ext_restart_hint))
                    }
                }) {
                    Text(stringResource(R.string.dsh_ext_trust_accept))
                }
            },
            dismissButton = {
                TextButton(onClick = { trustRecord = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (confirmReenable) {
        AlertDialog(
            onDismissRequest = { confirmReenable = false },
            title = { Text(stringResource(R.string.dsh_ext_reenable)) },
            text = { Text(stringResource(R.string.dsh_ext_reenable_hint)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReenable = false
                    // 只清标志、解除熔断位：不在这一刻加载任何扩展（运行期热加载会与已经跑着的
                    // 宿主状态打架），统一「重启后生效」。
                    ExtensionEngine.reEnable(context.applicationContext)
                    refreshTick++
                    scope.launch {
                        snackBarHost.showSnackbar(context.getString(R.string.dsh_ext_reenable_hint))
                    }
                }) {
                    Text(stringResource(R.string.dsh_ext_reenable))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReenable = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

/**
 * 安全模式横幅（原生红色，不可被扩展接管）。
 *
 * 为什么它必须固定在最上面、而不是混在列表里：熔断之后列表里的每一项都可能已经不在了，
 * 「怎么退出安全模式」是这一页唯一还确定有效的东西，不能要求用户先滚到某一行。
 */
@Composable
private fun ExtensionSafeModeBanner(
    reason: String,
    onReenable: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 12.dp, end = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.dsh_ext_safe_mode),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
            }
            if (reason.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.dsh_ext_safe_mode_reason, reason),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onReenable) {
                Text(stringResource(R.string.dsh_ext_reenable))
            }
        }
    }
}

/**
 * 单个扩展卡片：名字 + 版本 + 能力标签 + 说明 + 启用开关 + 移除。
 *
 * 能力（declaredCaps）最多显示三个，逗号分隔：这是扩展**自己声明的**东西，卡片上给一眼
 * 扫过的量就够了；要逐条核对就去看它的 manifest.json，别把列表撑成一面墙。
 */
@Composable
private fun ExtensionItem(
    record: ExtensionRecord,
    removeArmed: Boolean,
    onToggle: (Boolean) -> Unit,
    onRemoveClick: () -> Unit,
    onClick: () -> Unit,
) {
    val caps = record.manifest.declaredCaps
    // 清单解析失败时 ExtensionRegistry 会造一条 version="?" 的占位记录（否则那个目录永远删不掉）。
    // 它同时意味着「这个扩展没有任何可用信息」，徽章上必须说清楚，否则用户会以为是自己装错了。
    val broken = record.manifest.version == "?"
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f),
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = record.manifest.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (broken) "—" else "v" + record.manifest.version,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // 徽章按「用户最需要先知道的那件事」排序：坏了 > 没有代码 > 未启用。
                        if (broken) {
                            Spacer(Modifier.size(6.dp))
                            ModuleLabel(
                                text = stringResource(R.string.dsh_ext_badge_broken),
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        } else if (!record.manifest.hasCode) {
                            // 纯声明式扩展（只注册槽位/主题，不带 dex）：不是错误，但值得说明，
                            // 否则用户会奇怪「为什么它没有 classes.dex 也能启用」。
                            Spacer(Modifier.size(6.dp))
                            ModuleLabel(
                                text = stringResource(R.string.dsh_ext_badge_nocode),
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!record.enabled) {
                            Spacer(Modifier.size(6.dp))
                            ModuleLabel(
                                text = stringResource(R.string.dsh_ext_badge_disabled),
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                ModuleLabel(
                    text = stringResource(
                        if (record.enabled) R.string.dsh_ext_enable else R.string.dsh_ext_disable,
                    ),
                    containerColor = if (record.enabled) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (record.enabled) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.size(8.dp))
                Switch(checked = record.enabled, onCheckedChange = onToggle)
            }

            if (caps.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.dsh_ext_caps, caps.take(3).joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (record.manifest.description.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = record.manifest.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                // 两段式：第一下只把按钮变红（确认移除），第二下才真的删；
                // contentColor 同时管住图标与文字，默认的 primary 就是原来的蓝色。
                TextButton(
                    onClick = onRemoveClick,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (removeArmed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    ),
                ) {
                    Icon(Icons.Outlined.Delete, null, Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(
                        stringResource(
                            if (removeArmed) R.string.dsh_plugin_confirm_remove else R.string.apm_remove,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun BuiltInHostPluginItem() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(DshHostPrompt.enabled(context)) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f),
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            ModuleLabel(
                text = stringResource(R.string.dsh_plugin_builtin_label),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.dsh_host_plugin_name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.dsh_host_plugin_version), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        DshHostPrompt.setEnabled(context.applicationContext, it)
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.dsh_host_plugin_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 单个插件卡片：标签行为「下载量 · 星标 · 可更新」。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DshPluginItem(
    plugin: DshPlugin,
    showMoreInfo: Boolean,
    onUpdate: () -> Unit,
    /** 这颗「移除」是否已经处在二次确认态（第一下变红，第二下才真的卸载）。 */
    uninstallArmed: Boolean,
    onUninstallClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onOpenDetail: () -> Unit,
) {
    // 长按仍展开描述（原来的单击行为），单击改为打开详情弹层
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpenDetail,
                onLongClick = { expanded = !expanded },
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f),
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                if (plugin.seeded) {
                    ModuleLabel(
                        text = stringResource(R.string.dsh_plugin_seeded_label),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ModuleLabel(
                    text = "↓ " + formatCount(plugin.downloads),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                ModuleLabel(
                    text = "★ " + formatCount(plugin.stars),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                // 点赞来自 dsh-market，只有目录收录的插件有；未知（-1）时不占位
                if (plugin.likes >= 0) {
                    ModuleLabel(
                        text = stringResource(R.string.dsh_plugin_likes, formatCount(plugin.likes)),
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                if (plugin.updatable) {
                    ModuleLabel(
                        text = stringResource(R.string.apm_update),
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                if (!plugin.enabled) {
                    ModuleLabel(
                        text = stringResource(R.string.dsh_plugin_inactive),
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                if (plugin.disabled) {
                    ModuleLabel(
                        text = stringResource(R.string.dsh_plugin_disabled_label),
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = plugin.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = !plugin.disabled,
                    onCheckedChange = onToggle,
                    enabled = plugin.entryIds.isNotEmpty(),
                )
            }
            // 开关灰着却不给原因，等于让用户以为「开关坏了」：说清是读不到 entry id
            if (plugin.entryIds.isEmpty()) {
                Text(
                    text = stringResource(R.string.dsh_plugin_toggle_state_unknown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (plugin.updatable) "${plugin.installedVersion} → ${plugin.version}"
                else plugin.installedVersion.ifEmpty { plugin.version },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showMoreInfo) {
                Text(
                    text = listOf(plugin.pkg.ifEmpty { plugin.id }, plugin.author)
                        .filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!plugin.enabled) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.dsh_plugin_inactive_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (plugin.description.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = plugin.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) 12 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (plugin.updatable) {
                    TextButton(onClick = onUpdate) {
                        Icon(Icons.Outlined.SystemUpdate, null, Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.apm_update))
                    }
                }
                // 两段式：第一下只把按钮变红（确认移除），第二下才真的卸载；
                // contentColor 同时管住图标与文字，默认的 primary 就是原来的蓝色。
                TextButton(
                    onClick = onUninstallClick,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (uninstallArmed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    ),
                ) {
                    Icon(Icons.Outlined.Delete, null, Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(
                        stringResource(
                            if (uninstallArmed) R.string.dsh_plugin_confirm_remove else R.string.apm_remove,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * 打开插件仓库页。没浏览器时把提示交给调用方（商店页与详情弹层共用）。
 */
internal fun openPluginRepo(
    context: android.content.Context,
    plugin: DshPlugin,
    onNoBrowser: (String) -> Unit,
) {
    val url = plugin.homepage.ifEmpty { plugin.repo }
    if (url.isEmpty()) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { onNoBrowser(context.getString(R.string.dsh_no_browser)) }
}

/** 下载量/星标的紧凑写法；-1 表示还没取到。 */
internal fun formatCount(n: Long): String = when {
    n < 0 -> "—"
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format("%.1fk", n / 1_000.0)
    else -> n.toString()
}
/**
 * 扩展详情弹层：把「它到底是什么」一次说全。
 *
 * 为什么动作不搬进来：开关与移除留在卡片上（那一页的主要操作就是反复开关），
 * 弹层只负责看清楚 —— id、入口类、安装目录、**完整**的能力清单。
 * 卡片上最多显示三个能力，是因为那只是给一眼扫过的；真要核对就点开这里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExtensionDetailSheet(
    record: ExtensionRecord,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val manifest = record.manifest
    val broken = manifest.version == "?"
    val dir = remember(record.id) { ExtensionLayout.dirOf(context, record.id) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            Text(
                text = manifest.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (broken) "—" else "v" + manifest.version,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (manifest.description.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(text = manifest.description, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            DetailRow(stringResource(R.string.dsh_ext_detail_id), manifest.id)
            DetailRow(
                stringResource(R.string.dsh_ext_detail_entry),
                manifest.entryClass ?: stringResource(R.string.dsh_ext_badge_nocode),
            )
            // 目录直接给绝对路径：用户要删/要改 manifest 时，这句话就是唯一有用的信息。
            DetailRow(stringResource(R.string.dsh_ext_detail_dir), dir.absolutePath)

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.dsh_ext_detail_caps_all),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(6.dp))
            if (manifest.declaredCaps.isEmpty()) {
                Text(
                    text = stringResource(R.string.dsh_ext_detail_nocaps),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // 一条一行，不 Join 成一句：能力名可能很长，逗号串起来会看不清到底声明了几条。
                for (cap in manifest.declaredCaps) {
                    Text(
                        text = "· " + cap,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 详情弹层里的一行「标签 + 值」（值是等宽可选的，这里统一正文样式）。 */
@Composable
private fun DetailRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}



/**
 * 把某个具名槽位里的扩展内容渲染出来。
 *
 * 语义按注册顺序依次施加：
 *  - INSERT_BEFORE / INSERT_AFTER：在宿主内容前后各插一段；
 *  - REPLACE：替换宿主内容（有它时宿主原本那块不画，取最后注册的那条）；
 *  - WRAP：包住宿主内容；
 *  - REMOVE：抑制宿主内容。
 *
 * 为什么做成通用组件而不是每处手写：槽位是宿主定义的固定位置，五条语义如果每处各写一遍，
 * 「REPLACE 到底算不算 INSERT_AFTER」这类问题很快各页不一样。
 *
 * [hostContent] 为空表示这个位置本身没有宿主内容（扩展页顶部就是纯插入位），
 * 此时 REPLACE / WRAP / REMOVE 无对象可作用，只处理 INSERT_*。
 */
@Composable
private fun ExtensionSlotHost(
    slot: String,
    modifier: Modifier = Modifier,
    hostContent: (@Composable (Modifier) -> Unit)? = null,
) {
    val entries = ExtensionUiRegistry.slotEntries(slot)
    if (entries.isEmpty()) {
        hostContent?.invoke(modifier)
        return
    }
    val replace = entries.lastOrNull { it.op == ExtensionUiRegistry.SlotOp.REPLACE }
    val remove = entries.any { it.op == ExtensionUiRegistry.SlotOp.REMOVE }
    val before = entries.filter { it.op == ExtensionUiRegistry.SlotOp.INSERT_BEFORE }
    val after = entries.filter { it.op == ExtensionUiRegistry.SlotOp.INSERT_AFTER }
    val wrap = entries.filter { it.op == ExtensionUiRegistry.SlotOp.WRAP }

    Column(modifier) {
        for (e in before) e.content(Modifier.fillMaxWidth())
        when {
            replace != null -> replace.content(Modifier.fillMaxWidth())
            remove -> Unit
            wrap.isNotEmpty() && hostContent != null -> {
                // 从后往前套，最后注册的在最外层。用局部 val 逐层闭包，
                // 不能在 for 里直接捕获循环变量（Kotlin 的 e 是同一个引用，套完全变成最后一个）。
                val layers = wrap.map { entry -> entry.content }
                var current: @Composable (Modifier) -> Unit = { m -> hostContent(m) }
                for (i in layers.indices.reversed()) {
                    val outer = layers[i]
                    val inner = current
                    current = { m -> outer(m) }
                }
                current(Modifier.fillMaxWidth())
            }
            else -> hostContent?.invoke(Modifier.fillMaxWidth())
        }
        for (e in after) e.content(Modifier.fillMaxWidth())
    }
}

