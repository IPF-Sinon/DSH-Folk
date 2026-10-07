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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshEnv
import me.bmax.apatch.dsh.DshHostPrompt
import me.bmax.apatch.dsh.DshPlugin
import me.bmax.apatch.dsh.DshPluginRepo
import me.bmax.apatch.ui.screen.settings.UserscriptsContent
import me.bmax.apatch.dsh.DshRuntime
import me.bmax.apatch.ui.component.DshPluginDetailSheet
import me.bmax.apatch.ui.component.DshPluginProgressDialog
import me.bmax.apatch.ui.component.ModuleLabel
import me.bmax.apatch.ui.component.ScrollableEmptyState
import me.bmax.apatch.ui.component.SearchAppBar
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
 * - 右上角进 **插件商店**（dsh-market）；
 * - 右下 FAB 是 **本地安装**（选一个 .tgz）。
 */
/**
 * 这一页的两组内容。用户脚本的入口原本挂在「设置 → 功能」右上角，现在收到这里 ——
 * 它和 DSH 插件是同一件事的两半（都往容器/页面里塞东西），分成两页反而要用户记住入口在哪。
 */
private const val GROUP_PLUGINS = "plugins"
private const val GROUP_SCRIPTS = "scripts"

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
    // 商店按钮在脚本这一组要把市场滚进视野：用计数触发，连点两次也各有一次反应。
    var marketRequest by rememberSaveable { mutableIntStateOf(0) }

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
                            if (group == GROUP_SCRIPTS) R.string.dsh_userscripts_title
                            else R.string.dsh_plugins
                        )
                    )
                },
                // 两组的搜索都是「过滤已加载的列表」：插件过滤已装插件，脚本过滤「我装的」。
                // 语义一样才敢共用一个框 —— 换成"去 GreasyFork 搜"就必须分开（市场那个框在正文里）。
                searchText = if (group == GROUP_SCRIPTS) scriptFilter else viewModel.search,
                onSearchTextChange = {
                    if (group == GROUP_SCRIPTS) scriptFilter = it else viewModel.search = it
                },
                onClearClick = {
                    if (group == GROUP_SCRIPTS) scriptFilter = "" else viewModel.search = ""
                },
                dropdownContent = {
                    // 刷新只对插件那组有意义；脚本那组的「重读列表」在安装/删除后自己会做。
                    if (group != GROUP_SCRIPTS) {
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                        }
                    }
                    // 同一个按钮按**当前显示的是哪一组**分流：插件 → 插件商店（另一页）；
                    // 脚本 → 脚本市场。市场那一段就在脚本正文里（搜索框 + 结果 + 安装），
                    // 所以这里是把它滚进视野，而不是再开一页长得一样的页面。
                    IconButton(onClick = {
                        if (group == GROUP_SCRIPTS) {
                            marketRequest++
                        } else {
                            navigator.navigate(DshPluginStoreScreenDestination)
                        }
                    }) {
                        Icon(
                            Icons.Outlined.Storefront,
                            contentDescription = stringResource(
                                if (group == GROUP_SCRIPTS) R.string.dsh_userscripts_market_section
                                else R.string.dsh_plugin_store
                            ),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            // 本地安装：留在右下角，与 FolkPatch 模块页一致。
            // 只在「插件」那一组出现 —— 它选的是 .tgz（插件包），脚本那一组装的是 .user.js，
            // 摆在脚本列表上只会让人点错（脚本的安装入口在正文里的粘贴 / 选文件）。
            if (group != GROUP_SCRIPTS) {
                FloatingActionButton(
                    onClick = {
                        pickTarball.launch(
                            Intent(Intent.ACTION_GET_CONTENT).apply {
                                type = "*/*"
                                addCategory(Intent.CATEGORY_OPENABLE)
                            }
                        )
                    },
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = stringResource(R.string.dsh_local_install))
                }
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
                ModuleGroupRow(group = group, onSelect = { group = it })
                when (group) {
                    // 用户脚本：与「设置 → 用户脚本」那一页**同一份正文**（UserscriptsContent）。
                    // 入口搬过来之后两处各留一份实现，很快就会各长各的 —— 那正是"对齐 UI"要防的事。
                    GROUP_SCRIPTS -> UserscriptsContent(
                        modifier = Modifier.weight(1f),
                        revealMarket = marketRequest,
                        filter = scriptFilter,
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

    // 安装/卸载进度：pnpm 可能跑几分钟，不能只在结束后弹一条 snackbar
    PluginProgressHost(viewModel)
}

/**
 * 两组内容的切换：DSH 插件 / 用户脚本。
 *
 * 用 FilterChip 而不是 TabRow / SegmentedButton：商店的分类行就是这一套（同样的选中色与
 * 间距），两页挨在一起时视觉上才像同一个应用（本项目此前没有用过 TabRow，不新引入一种）。
 */
@Composable
private fun ModuleGroupRow(group: String, onSelect: (String) -> Unit) {
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 1f)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = group != GROUP_SCRIPTS,
            onClick = { onSelect(GROUP_PLUGINS) },
            label = { Text(stringResource(R.string.dsh_plugins)) },
            colors = chipColors,
        )
        FilterChip(
            selected = group == GROUP_SCRIPTS,
            onClick = { onSelect(GROUP_SCRIPTS) },
            label = { Text(stringResource(R.string.dsh_userscripts_title)) },
            colors = chipColors,
        )
    }
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

