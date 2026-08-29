package me.bmax.apatch.ui.screen

import android.app.Activity.RESULT_OK
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import me.bmax.apatch.dsh.DshPlugin
import me.bmax.apatch.dsh.DshPluginRepo
import me.bmax.apatch.ui.component.DshPluginDetailSheet
import me.bmax.apatch.ui.component.ModuleLabel
import me.bmax.apatch.ui.component.SearchAppBar
import me.bmax.apatch.ui.viewmodel.DshPluginViewModel
import me.bmax.apatch.util.ui.HomeBottomSpacer
import me.bmax.apatch.util.ui.LocalSnackbarHost

/**
 * 插件商店（dsh-market）。
 *
 * 目录来自 dsh-market.com，下载量取自 npm registry，星标取自 GitHub —— 三者独立，
 * 任一不可用时对应标签显示「—」而不是让整页失败。
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

    LaunchedEffect(Unit) {
        if (viewModel.catalog.isEmpty()) viewModel.refresh()
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
                    IconButton(onClick = { viewModel.refresh() }) {
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
        // 已安装判定按 npm 包名：目录 id 与包名不一定相同（dsh-tui vs @deepseek-harness-tui/dsh-tui）
        val installedPkgs = remember(viewModel.plugins) { viewModel.plugins.map { it.pkg }.toSet() }
        val query = viewModel.search
        val list = if (query.isBlank()) viewModel.catalog else viewModel.catalog.filter {
            it.id.contains(query, true) || it.name.contains(query, true) ||
                it.description.contains(query, true)
        }

        Column(Modifier.fillMaxSize()) {
            if (viewModel.isRefreshing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = innerPadding.calculateTopPadding()),
                )
            }
            if (list.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    // 三态分开：正在拉 / 拉不到目录 / 目录有但搜索没命中。
                    // 原来只判 list.isEmpty()，而 list 是过滤后的结果 —— 于是搜不到
                    // 任何插件时会误报「暂时取不到插件目录，请检查网络」。
                    Text(
                        text = when {
                            viewModel.isRefreshing ->
                                stringResource(R.string.dsh_plugin_catalog_loading)
                            viewModel.catalog.isNotEmpty() && query.isNotBlank() ->
                                stringResource(R.string.dsh_plugin_no_match, query)
                            else -> stringResource(R.string.dsh_plugin_catalog_failed)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = if (viewModel.isRefreshing) 8.dp else innerPadding.calculateTopPadding() + 8.dp,
                        start = 16.dp,
                        end = 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(list, key = { it.id }) { plugin ->
                        StorePluginCard(
                            plugin = plugin,
                            installed = plugin.pkg.isNotEmpty() && plugin.pkg in installedPkgs,
                            onInstall = { viewModel.install(plugin.pkg) },
                            onOpenRepo = {
                                openPluginRepo(context, plugin) { msg -> scope.launch { snackBarHost.showSnackbar(msg) } }
                            },
                            onOpenDetail = { detail = plugin },
                        )
                    }
                    item { HomeBottomSpacer() }
                }
            }
        }
    }
}

@Composable
private fun StorePluginCard(
    plugin: DshPlugin,
    installed: Boolean,
    onInstall: () -> Unit,
    onOpenRepo: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    Card(
        onClick = onOpenDetail,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
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
                        if (plugin.likes >= 0) {
                            ModuleLabel(
                                text = stringResource(
                                    R.string.dsh_plugin_likes,
                                    formatCount(plugin.likes),
                                ),
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                        if (plugin.version.isNotEmpty()) {
                            ModuleLabel(
                                text = "v${plugin.version}",
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = plugin.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (plugin.author.isNotEmpty()) {
                        Text(
                            text = plugin.author,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.size(12.dp))
                // 目录里 13/41 条没登记 npm 包名，装不了 —— 但仓库地址都有。
                // 给一个永远点不动的下载键等于死路，这里直接换成「打开仓库」。
                val canOpenRepo = plugin.homepage.isNotEmpty() || plugin.repo.isNotEmpty()
                FilledTonalIconButton(
                    onClick = if (plugin.installable) onInstall else onOpenRepo,
                    enabled = if (plugin.installable) !installed else canOpenRepo,
                    modifier = Modifier.size(48.dp),
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
            if (!plugin.installable) {
                Text(
                    text = stringResource(R.string.dsh_plugin_no_npm),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
