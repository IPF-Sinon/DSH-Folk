package me.bmax.apatch.ui.screen

import android.app.Activity.RESULT_OK
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import me.bmax.apatch.dsh.DshRuntime
import me.bmax.apatch.ui.component.ModuleLabel
import me.bmax.apatch.ui.component.SearchAppBar
import me.bmax.apatch.ui.viewmodel.DshPluginViewModel
import me.bmax.apatch.util.ui.HomeBottomSpacer
import me.bmax.apatch.util.ui.LocalSnackbarHost
import java.io.File

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
    val announce = rememberPluginAnnouncer(viewModel, snackBarHost)

    LaunchedEffect(Unit) {
        if (viewModel.plugins.isEmpty()) viewModel.refresh()
    }

    // 本地安装：容器内只看得到 rootfs 内的路径，所以先把用户选的 .tgz 落到
    // /root/.dsh/incoming，再把容器绝对路径交给 dsh plugin add
    val pickTarball = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@rememberLauncherForActivityResult
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        scope.launch {
            val guest = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(DshEnv.dshHome(context), "incoming").apply { mkdirs() }
                    val dst = File(dir, "local-plugin-${System.currentTimeMillis()}.tgz")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dst.outputStream().use { input.copyTo(it) }
                    }
                    "/root/.dsh/incoming/${dst.name}"
                }.getOrNull()
            }
            if (guest == null) {
                snackBarHost.showSnackbar(context.getString(R.string.dsh_plugin_local_read_failed))
                return@launch
            }
            viewModel.installLocal(guest) { out ->
                scope.launch { announce(out) }
            }
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
        DshPluginList(
            innerPadding = innerPadding,
            viewModel = viewModel,
            snackBarHost = snackBarHost,
        )
    }
}

@Composable
private fun DshPluginList(
    innerPadding: PaddingValues,
    viewModel: DshPluginViewModel,
    snackBarHost: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    val announce = rememberPluginAnnouncer(viewModel, snackBarHost)
    val list = viewModel.filtered

    if (list.isEmpty()) {
        Box(
            Modifier.fillMaxSize().padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(
                    if (viewModel.isRefreshing) R.string.dsh_plugin_loading
                    else R.string.dsh_plugin_empty
                ),
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
                onUpdate = {
                    viewModel.install(plugin.pkg) { out ->
                        scope.launch { announce(out) }
                    }
                },
                onUninstall = {
                    viewModel.uninstall(plugin.pkg) { out ->
                        scope.launch { announce(out) }
                    }
                },
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
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { expanded = !expanded }),
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
            }

            Text(
                text = plugin.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
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

/** 下载量/星标的紧凑写法；-1 表示还没取到。 */
internal fun formatCount(n: Long): String = when {
    n < 0 -> "—"
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format("%.1fk", n / 1_000.0)
    else -> n.toString()
}

/**
 * 装 / 卸完成后的统一提示。
 *
 * 成功时给一条带「立即重启」动作的 snackbar：dsh 只在启动时组合 profile 的 patch 层，
 * 不重启进程新装的插件不会加载（与 dsh plugin 自己的 needsRestart 语义一致）。
 */
@Composable
internal fun rememberPluginAnnouncer(
    viewModel: DshPluginViewModel,
    snackBarHost: SnackbarHostState,
): suspend (String) -> Unit {
    val done = stringResource(R.string.dsh_plugin_done)
    val needsRestartText = stringResource(R.string.dsh_plugin_needs_restart)
    val restartNowText = stringResource(R.string.dsh_plugin_restart_now)
    return remember(viewModel, snackBarHost, done, needsRestartText, restartNowText) {
        { out: String ->
            val tail = out.lines().lastOrNull { it.isNotBlank() } ?: done
            if (viewModel.needsRestart) {
                val result = snackBarHost.showSnackbar(
                    message = needsRestartText,
                    actionLabel = restartNowText,
                    duration = SnackbarDuration.Long,
                )
                viewModel.clearNeedsRestart()
                if (result == SnackbarResult.ActionPerformed) DshRuntime.restart()
            } else {
                snackBarHost.showSnackbar(tail)
            }
        }
    }
}
