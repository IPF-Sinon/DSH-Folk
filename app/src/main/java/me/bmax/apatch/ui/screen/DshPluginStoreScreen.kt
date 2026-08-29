package me.bmax.apatch.ui.screen

import android.app.Activity.RESULT_OK
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.derivedStateOf
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
import me.bmax.apatch.ui.component.SearchAppBar
import me.bmax.apatch.ui.viewmodel.DshPluginViewModel
import me.bmax.apatch.util.ui.HomeBottomSpacer
import me.bmax.apatch.util.ui.LocalSnackbarHost

/**
 * 插件商店。
 *
 * 数据源是 npm 上同时打 `dsh-plugin` 与 `dsh` 两个 keyword 的包（官方商店「全部 (2.5k)」
 * 就是它，每页 24 条），不是 dsh-market 的 44 条精选目录。分类 = 更窄的第三个 npm keyword，
 * 走服务端分页（`keywords:a,b,c` 是 AND）。
 *
 * 列表用瀑布流（双列 staggered grid）+ 滚动到底部自动加载下一页，首屏只拉一页，
 * 避免一次性 2500+ 条把列表渲染拖垮。下载量/星标仍是「未知 → 惰性补齐」，
 * 不为一页之外的东西发额外请求。
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
        if (viewModel.storeItems.isEmpty()) viewModel.loadStore(reset = true)
    }

    var detail by remember { mutableStateOf<DshPlugin?>(null) }
    detail?.let { p ->
        DshPluginDetailSheet(
            plugin = p,
            onDismiss = { detail = null },
            onInstall = { viewModel.install(p.pkg) },
            onUpdate = { viewModel.install(p.pkg) },
            onUninstall = { viewModel.uninstall(p.pkg) },
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
                    IconButton(onClick = { viewModel.loadStore(reset = true) }) {
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
            val total = viewModel.storeTotal
            if (total > 0L) {
                Text(
                    text = stringResource(R.string.dsh_plugin_total, formatCount(total)),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StoreGrid(
                viewModel = viewModel,
                installedPkgs = viewModel.plugins.map { it.pkg }.toSet(),
                onInstall = { pkg -> viewModel.install(pkg) },
                onOpenDetail = { detail = it },
                onOpenRepo = { openPluginRepo(context, it) { msg -> scope.launch { snackBarHost.showSnackbar(msg) } } },
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
    }
}

@Composable
private fun StoreGrid(
    viewModel: DshPluginViewModel,
    installedPkgs: Set<String>,
    onInstall: (String) -> Unit,
    onOpenDetail: (DshPlugin) -> Unit,
    onOpenRepo: (DshPlugin) -> Unit,
) {
    val gridState = rememberLazyStaggeredGridState()
    val query = viewModel.search
    // 搜索是本地过滤（npm 无法把自由文本和 keywords: 限定词用 AND 组合），
    // 过滤的是已累积的条目；继续往下滚会再加载下一页，命中会越来越多。
    val list = if (query.isBlank()) viewModel.storeItems else viewModel.storeItems.filter {
        it.id.contains(query, true) || it.name.contains(query, true) ||
            it.description.contains(query, true)
    }

    // 滚到倒数第 6 项时预取下一页；搜索态同样允许继续翻页，好找到更多命中。
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            viewModel.storeItems.isNotEmpty() &&
                !viewModel.storeEndReached &&
                last >= viewModel.storeItems.size - 6
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) viewModel.loadStore()
    }

    when {
        viewModel.storeRefreshing && viewModel.storeItems.isEmpty() -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        list.isEmpty() -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                        installed = plugin.pkg in installedPkgs,
                        onInstall = { onInstall(plugin.pkg) },
                        onOpenRepo = { onOpenRepo(plugin) },
                        onOpenDetail = { onOpenDetail(plugin) },
                    )
                }
                if (viewModel.storeLoadingMore) {
                    item(span = StaggeredGridItemSpan.FullLine) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.dsh_plugin_loading_more),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
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
