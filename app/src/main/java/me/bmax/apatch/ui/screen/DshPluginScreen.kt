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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import me.bmax.apatch.dsh.DshPlugin
import me.bmax.apatch.dsh.DshPluginRepo
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
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun DshPluginScreen(navigator: DestinationsNavigator) {
    val viewModel = viewModel<DshPluginViewModel>()
    val snackBarHost = LocalSnackbarHost.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val runtimeInstalled = remember { DshEnv.isRuntimeInstalled(context) }

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
                title = { Text(stringResource(R.string.dsh_plugins)) },
                searchText = viewModel.search,
                onSearchTextChange = { viewModel.search = it },
                onClearClick = { viewModel.search = "" },
                dropdownContent = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = {
                        navigator.navigate(DshPluginStoreScreenDestination)
                    }) {
                        Icon(
                            Icons.Outlined.Storefront,
                            contentDescription = stringResource(R.string.dsh_plugin_store),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            // 本地安装：留在右下角，与 FolkPatch 模块页一致
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
        },
    ) { innerPadding ->
        if (!runtimeInstalled) {
            DshRuntimeNeeded(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                onGoHome = { navigator.popBackStack() },
            )
        } else {
            DshPluginList(
                innerPadding = innerPadding,
                viewModel = viewModel,
                snackBarHost = snackBarHost,
            )
        }
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
    var detail by remember { mutableStateOf<DshPlugin?>(null) }

    detail?.let { p ->
        DshPluginDetailSheet(
            plugin = p,
            onDismiss = { detail = null },
            onInstall = { viewModel.install(p.pkg) },
            onUpdate = { viewModel.install(p.pkg) },
            onUninstall = { viewModel.uninstall(p.pkg) },
            onToggle = { viewModel.setDisabled(p.pkg, !p.disabled) },
            onOpenRepo = { openPluginRepo(context, p) { msg -> scope.launch { snackBarHost.showSnackbar(msg) } } },
        )
    }

    if (list.isEmpty()) {
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
        items(list, key = { it.pkg.ifEmpty { it.id } }) { plugin ->
            DshPluginItem(
                plugin = plugin,
                showMoreInfo = viewModel.showMoreInfo,
                onUpdate = { viewModel.install(plugin.pkg) },
                onUninstall = { viewModel.uninstall(plugin.pkg) },
                onToggle = { viewModel.setDisabled(plugin.pkg, !plugin.disabled) },
                onOpenDetail = { detail = plugin },
            )
        }
        item { HomeBottomSpacer() }
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

