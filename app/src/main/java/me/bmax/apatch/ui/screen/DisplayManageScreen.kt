package me.bmax.apatch.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.DisplayPreviewScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DisplayServer

/**
 * 虚拟屏管理：把服务端进程里**所有**活着的虚拟屏列出来，能逐个预览、逐个终止。
 *
 * ## 为什么需要这一页
 *
 * 「能力卡上那个开关」回答的是"允不允许用虚拟屏"，回答不了"现在到底有几块屏、都是谁的"。
 * 而这两件事经常不一致：agent 改了尺寸就会**新建**一块（[DisplayServer.startSession] 的复用
 * 条件是宽高 dpi 完全相同），旧的不会自动销毁；服务端又是常驻的，App 重启后那些屏还在。
 * 用户因此会遇到"我明明没在用它，怎么还占着编码器"，却没有任何地方能看到。
 *
 * ## 这一页不做的事
 *
 * **不会为了看列表而把服务端拉起来**（见 [DisplayServer.listDisplays]）：打开设置顺手拉起一个
 * root 进程是纯粹的副作用。服务端没在跑就如实显示"没有活着的虚拟屏"。
 *
 * 终止单个屏走 [DisplayServer.stopSession]，与悬浮小窗上的 ✕ 是同一条路（都会让 agent 的
 * 下一步拿到 `display_terminated_by_user`）；「全部终止」才是 `pkill` 整个服务端，
 * 所以它单独问一次。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun DisplayManageScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // null = 还在读第一遍。与"读到了空列表"必须分开，否则会先闪一下"没有虚拟屏"再列出内容。
    var infos by remember { mutableStateOf<List<DisplayServer.DisplayInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmStopAll by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            val r = withContext(Dispatchers.IO) { DisplayServer.listDisplays() }
            r.onSuccess {
                infos = it
                error = null
            }.onFailure {
                infos = emptyList()
                error = it.message ?: context.getString(R.string.dsh_display_manage_failed)
            }
        }
    }

    // 读一遍：进页面、以及每次终止之后
    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dsh_display_manage_title)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { refresh() }) {
                        Text(stringResource(R.string.dsh_display_manage_refresh))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.dsh_display_manage_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            val list = infos
            if (list == null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (list.isEmpty()) {
                Text(
                    text = stringResource(R.string.dsh_display_manage_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }

            list?.forEach { info ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            text = stringResource(R.string.dsh_display_manage_id, info.id),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(
                                R.string.dsh_display_manage_size,
                                info.width,
                                info.height,
                                info.dpi,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            // 「有人在看」是**画面上有人收帧**，不是"agent 正在操作"：没有 sink
                            // 往往正是用户想知道的那些屏（预览页关掉后就变成这样，画面不再编码）。
                            text = stringResource(
                                if (info.hasSink) R.string.dsh_display_manage_watching
                                else R.string.dsh_display_manage_unwatched
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = if (info.packageName.isBlank()) {
                                stringResource(R.string.dsh_display_manage_pkg_unknown)
                            } else {
                                stringResource(R.string.dsh_display_manage_pkg, info.packageName)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = {
                                    navigator.navigate(DisplayPreviewScreenDestination(info.id))
                                },
                            ) {
                                Text(stringResource(R.string.dsh_display_manage_preview))
                            }
                            TextButton(
                                onClick = {
                                    // Binder 调用可能慢（要销毁虚拟屏 + 停编码器），放 IO 上
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            DisplayServer.stopSession(context, info.id)
                                        }
                                        refresh()
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.dsh_display_manage_stop_one))
                            }
                        }
                    }
                }
            }

            if (!list.isNullOrEmpty()) {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { confirmStopAll = true }) {
                    Text(stringResource(R.string.dsh_display_manage_stop_all))
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (confirmStopAll) {
        AlertDialog(
            onDismissRequest = { confirmStopAll = false },
            title = { Text(stringResource(R.string.dsh_display_manage_stop_all_title)) },
            text = { Text(stringResource(R.string.dsh_display_manage_stop_all_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmStopAll = false
                        scope.launch {
                            withContext(Dispatchers.IO) { DisplayServer.stop(context) }
                            refresh()
                        }
                    },
                ) {
                    Text(stringResource(R.string.dsh_display_manage_stop_all_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmStopAll = false }) {
                    Text(stringResource(R.string.dsh_display_manage_cancel))
                }
            },
        )
    }
}
