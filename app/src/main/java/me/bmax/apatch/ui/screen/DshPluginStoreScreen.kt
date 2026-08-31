package me.bmax.apatch.ui.screen

import android.app.Activity.RESULT_OK
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshEnv
import me.bmax.apatch.dsh.DshPlugin
import me.bmax.apatch.dsh.DshPluginRepo
import me.bmax.apatch.ui.component.DshPluginDetailSheet
import me.bmax.apatch.ui.component.ModuleLabel
import me.bmax.apatch.ui.component.ScrollableEmptyState
import me.bmax.apatch.ui.component.SearchAppBar
import me.bmax.apatch.ui.viewmodel.DshPluginViewModel
import me.bmax.apatch.util.ui.HomeBottomSpacer
import me.bmax.apatch.util.ui.LocalSnackbarHost

/**
 * 插件商店。
 *
 * 数据源是**完整目录**（`awesome-dsh-plugin.com/plugins.json`，约 2600 条），与 DSH
 * 官方市场插件同一条路径，国内回落 npm 包 `dsh-plugin-catalog`。
 *
 * 为什么不用 npm search 做服务端搜索：`text` 里 `keywords:` 之后的自由文本只影响
 * **排序**、不做过滤 —— 实测 `keywords:dsh-plugin,dsh theme` 返回的 total 仍是 2577。
 * 也就是说服务端给不出「某个词的全部命中」，只能给前 N 个最相关的。用户要的是
 * 「能搜到全部插件」，所以整份目录取下来、在本地检索。
 *
 * 列表用瀑布流（staggered grid）—— Lazy 只组合可见项，2600 条不构成渲染压力，
 * 因此不需要分页。下载量与 star 由目录自带，不再逐包打 npm / GitHub API。
 *
 * 右下角 FAB 为**本地安装**：选一个 npm 包 tarball（.tgz），复制进 rootfs 后交给 dsh plugin add。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun DshPluginStoreScreen(navigator: DestinationsNavigator) {
    val viewModel = viewModel<DshPluginViewModel>()
    val snackBarHost = LocalSnackbarHost.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 未装运行时：既不查已装列表、也不发网络请求，直接引导去首页。
    val runtimeInstalled = remember { DshEnv.isRuntimeInstalled(context) }

    LaunchedEffect(runtimeInstalled) {
        if (!runtimeInstalled) return@LaunchedEffect
        viewModel.loadInstalledForStore()
        if (viewModel.storeAll.isEmpty()) viewModel.refreshCatalog()
    }

    // 只存 id，实体每次从 storeItems 取：装完/卸完列表会刷新，
    // 存快照的话详情页按钮会停在打开那一刻的状态
    var detailId by remember { mutableStateOf<String?>(null) }
    val detail = detailId?.let { id -> viewModel.storeItems.firstOrNull { it.id == id } }
    detail?.let { p ->
        DshPluginDetailSheet(
            plugin = p,
            onDismiss = { detailId = null },
            onInstall = { viewModel.install(p.addSpec) },
            onUpdate = { viewModel.install(p.addSpec) },
            onUninstall = { viewModel.uninstall(p.pkg) },
            onToggle = { viewModel.setDisabled(p.pkg, !p.disabled) },
            onOpenRepo = { openPluginRepo(context, p) { msg -> scope.launch { snackBarHost.showSnackbar(msg) } } },
        )
    }

    // 安装进度：与已安装页共用一份对话框实现
    PluginProgressHost(viewModel)

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
                title = { Text(stringResource(R.string.dsh_plugin_store)) },
                searchText = viewModel.search,
                onSearchTextChange = { viewModel.search = it },
                onClearClick = { viewModel.search = "" },
                onBackClick = { navigator.popBackStack() },
                dropdownContent = {
                    IconButton(onClick = { viewModel.refreshCatalog(force = true) }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    pickTarball.launch(intent)
                },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(
                    Icons.Outlined.FolderOpen,
                    contentDescription = stringResource(R.string.dsh_local_install),
                )
            }
        },
    ) { innerPadding ->
        if (!runtimeInstalled) {
            DshRuntimeNeeded(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                onGoHome = { navigator.popBackStack() },
            )
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            CategoryRow(viewModel)
            StoreStatusLine(viewModel)
            StoreGrid(
                viewModel = viewModel,
                onInstall = { spec -> viewModel.install(spec) },
                onOpenDetail = { detailId = it.id },
                onOpenRepo = { openPluginRepo(context, it) { msg -> scope.launch { snackBarHost.showSnackbar(msg) } } },
            )
        }
    }
}

/**
 * 计数 + 离线提示。
 *
 * 显示「已显示 N / 共 M」而不是只显示总数：搜索现在覆盖全量目录，把两个数字
 * 都摆出来，用户才看得见「搜的是全部，不是已加载的那一页」。
 */
@Composable
private fun StoreStatusLine(viewModel: DshPluginViewModel) {
    val total = viewModel.storeTotal
    if (total <= 0) return
    val shown = viewModel.storeItems.size
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = if (shown == total) {
                stringResource(R.string.dsh_plugin_total, formatCount(total.toLong()))
            } else {
                stringResource(
                    R.string.dsh_plugin_shown_of_total,
                    formatCount(shown.toLong()),
                    formatCount(total.toLong()),
                )
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (viewModel.storeOffline) {
            Text(
                text = stringResource(
                    R.string.dsh_plugin_catalog_offline,
                    viewModel.storeUpdated.ifEmpty { "?" },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun CategoryRow(viewModel: DshPluginViewModel) {
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 1f)
    )
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = viewModel.storeCategory.isBlank(),
            onClick = { viewModel.selectStoreCategory("") },
            label = { Text(stringResource(R.string.dsh_plugin_category_all)) },
            colors = chipColors,
        )
        DshPluginRepo.categories().forEach { cat ->
            FilterChip(
                selected = viewModel.storeCategory == cat.slug,
                onClick = { viewModel.selectStoreCategory(cat.slug) },
                label = { Text(stringResource(cat.label)) },
                colors = chipColors,
            )
        }
        // 目录新增了 App 还没内置的分类时，用目录自带的标题补上，不必等 App 更新
        val extra = viewModel.storeCategoryTitles.keys -
            DshPluginRepo.categories().map { it.slug }.toSet()
        extra.sorted().forEach { slug ->
            FilterChip(
                selected = viewModel.storeCategory == slug,
                onClick = { viewModel.selectStoreCategory(slug) },
                label = { Text(viewModel.storeCategoryTitles[slug] ?: slug) },
                colors = chipColors,
            )
        }
    }
}

@Composable
private fun StoreGrid(
    viewModel: DshPluginViewModel,
    onInstall: (String) -> Unit,
    onOpenDetail: (DshPlugin) -> Unit,
    onOpenRepo: (DshPlugin) -> Unit,
) {
    val gridState = rememberLazyStaggeredGridState()
    // 分类与搜索的过滤都在 ViewModel 的 storeItems 里做，覆盖整份目录
    val list = viewModel.storeItems
    val query = viewModel.search

    when {
        viewModel.storeRefreshing && viewModel.storeAll.isEmpty() -> {
            // 可滚动：否则底栏自动隐藏后无法下拉唤回（见 ScrollableEmptyState）
            ScrollableEmptyState {
                CircularProgressIndicator()
            }
        }
        list.isEmpty() -> {
            ScrollableEmptyState {
                Text(
                    text = when {
                        viewModel.storeRefreshing ->
                            stringResource(R.string.dsh_plugin_catalog_loading)
                        query.isNotBlank() ->
                            stringResource(R.string.dsh_plugin_no_match, query)
                        else -> stringResource(R.string.dsh_plugin_catalog_failed)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        else -> {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Adaptive(minSize = 160.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalItemSpacing = 12.dp,
            ) {
                items(list, key = { it.id }) { plugin ->
                    StorePluginTile(
                        plugin = plugin,
                        // 已装状态由 storeItems 统一补齐，瓦片与详情页判据一致
                        installed = plugin.installed,
                        onInstall = { onInstall(plugin.addSpec) },
                        onOpenRepo = { onOpenRepo(plugin) },
                        onOpenDetail = { onOpenDetail(plugin) },
                    )
                }
                item(span = StaggeredGridItemSpan.FullLine) { HomeBottomSpacer() }
            }
        }
    }
}

@Composable
private fun StorePluginTile(
    plugin: DshPlugin,
    installed: Boolean,
    onInstall: () -> Unit,
    onOpenRepo: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    Card(
        onClick = onOpenDetail,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = plugin.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (plugin.author.isNotEmpty()) {
                        Text(
                            text = plugin.author,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // 商店条目都是 npm 包，可一键装；「打开仓库」只在极少数没登记 npm 的
                // 精选条目里兜底（这里不会出现，保留 onOpenRepo 以防万一）。
                val canOpenRepo = plugin.homepage.isNotEmpty() || plugin.repo.isNotEmpty()
                FilledTonalIconButton(
                    onClick = if (plugin.installable) onInstall else onOpenRepo,
                    enabled = if (plugin.installable) !installed else canOpenRepo,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = when {
                            !plugin.installable -> Icons.Outlined.OpenInNew
                            installed -> Icons.Outlined.Check
                            else -> Icons.Outlined.Download
                        },
                        contentDescription = stringResource(
                            when {
                                !plugin.installable -> R.string.dsh_plugin_open_repo
                                installed -> R.string.dsh_plugin_installed
                                else -> R.string.dsh_plugin_install
                            }
                        ),
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                if (plugin.version.isNotEmpty()) {
                    ModuleLabel(
                        text = "v${plugin.version}",
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (plugin.description.isNotEmpty()) {
                Text(
                    text = plugin.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
