package me.bmax.apatch.ui.screen

import android.app.Activity.RESULT_OK
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import me.bmax.apatch.ui.component.AddMenuEntry
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

/** 第三页：扩展。目前只有入口与一句「暂不可用」，占位是为了让切换器先把三页的位置定下来。 */
private const val GROUP_EXTENSIONS = "extensions"

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

    Scaffold(
        topBar = {
            SearchAppBar(
                title = {
                    Text(
                        stringResource(
                            when (group) {
                                GROUP_SCRIPTS -> R.string.dsh_userscripts_title
                                GROUP_EXTENSIONS -> R.string.dsh_plugins_extensions
                                else -> R.string.dsh_plugins
                            }
                        )
                    )
                },
                // 两组的搜索都是「过滤已加载的列表」：插件过滤已装插件，脚本过滤「我装的」。
                // 语义一样才敢共用一个框 —— 换成"去 GreasyFork 搜"就必须分开（那个框在市场那一页上）。
                searchText = if (group == GROUP_SCRIPTS) scriptFilter else viewModel.search,
                onSearchTextChange = {
                    if (group == GROUP_SCRIPTS) scriptFilter = it else viewModel.search = it
                },
                onClearClick = {
                    if (group == GROUP_SCRIPTS) scriptFilter = "" else viewModel.search = ""
                },
                // 扩展页目前是占位：这一页没有可搜的东西，就不给一颗点开什么都搜不到的放大镜。
                searchEnabled = group != GROUP_EXTENSIONS,
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
            //   - 扩展页 → 还没有可装的东西，不画。
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
        if (!runtimeInstalled) {
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
                    // 扩展页：入口先占位（用户要的三页切换里它标着「暂不可用」）。
                    // 不画一个空列表：空列表看起来像「装完就没了」，而不是「这页还没做」。
                    GROUP_EXTENSIONS -> ScrollableEmptyState(Modifier.weight(1f)) {
                        Column(
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = stringResource(R.string.dsh_plugins_extensions_unavailable),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 32.dp),
                            )
                        }
                    }
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

    val visible = viewModel.installing || viewModel.installLog.isNotEmpty()
    if (!visible) return
    DshPluginProgressDialog(
        target = viewModel.installTarget,
        lines = viewModel.installLog,
        running = viewModel.installing,
        failed = viewModel.installFailed,
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

    detail?.let { p ->
        DshPluginDetailSheet(
            plugin = p,
            onDismiss = { detailId = null },
            onInstall = { viewModel.install(p.pkg) },
            onUpdate = { viewModel.update(p.pkg) },
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
        modifier = Modifier.fillMaxSize(),
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
        if (builtInVisible) {
            item(key = "builtin:dsh-folk-host") {
                BuiltInHostPluginItem()
            }
        }
        items(list, key = { it.pkg.ifEmpty { it.id } }) { plugin ->
            DshPluginItem(
                plugin = plugin,
                showMoreInfo = viewModel.showMoreInfo,
                onUpdate = { viewModel.update(plugin.pkg) },
                onUninstall = { viewModel.uninstall(plugin.pkg) },
                onToggle = { viewModel.setDisabled(plugin.pkg, !plugin.disabled) },
                onOpenDetail = { detailId = plugin.pkg.ifEmpty { plugin.id } },
            )
        }
        item { HomeBottomSpacer() }
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
    onUninstall: () -> Unit,
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
                TextButton(onClick = onUninstall) {
                    Icon(Icons.Outlined.Delete, null, Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.apm_remove))
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

