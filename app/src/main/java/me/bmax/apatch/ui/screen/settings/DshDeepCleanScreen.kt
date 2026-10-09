package me.bmax.apatch.ui.screen.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshDeepClean
import me.bmax.apatch.ui.component.ExpressiveCard
import me.bmax.apatch.util.ui.showToast

/**
 * 「深度清理」页：功能页那张「清理资源和缓存」卡片**长按**进来的。
 *
 * 与点进去的浅清理分开，是因为删的东西不是一个量级：浅清理只动应用自己的主题/媒体/缓存，
 * 而这里会动**运行时（rootfs）里的临时文件、apt/npm 缓存、日志**，以及**共享存储里的
 * 大文件与大文件夹**。所以这一页有三条硬性纪律：
 *
 * 1. **先警告**：页头常驻一段说明（删除直接生效、不进回收站，请自己确认里面没有要留的东西），
 *    确认弹窗里再说一次。
 * 2. **按分区展示**：运行时 / 应用内部存储 / 共享存储三块，每块用 `StatFs` 报出所在分区的
 *    总量与可用量 —— 用户看的不该只是"这几项一共多大"，还得知道删了能腾出哪个分区。
 * 3. **失败如实反馈**：删除别的项成功、这一项失败（被占用、没权限）时逐条列出来，不合并成
 *    一句"清理完成"。
 *
 * 清单与阈值在 [DshDeepClean] 里，那里也写清了**哪些路径绝不能进清单**。
 */
@Destination<RootGraph>
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DshDeepCleanScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sections by remember { mutableStateOf<List<DshDeepClean.Section>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }
    var scanKey by remember { mutableStateOf(0) }
    // 勾选集合只存**顶层选择**：勾父目录时把已选的子项剔掉（父覆盖子，统计不重复）
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirming by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<List<String>>(emptyList()) }

    // 扫描必须在 IO 上：rootfs 是十万级文件（见 DshDeepClean 的 KDoc）
    LaunchedEffect(scanKey) {
        scanning = true
        selected = emptySet()
        sections = withContext(Dispatchers.IO) { DshDeepClean.scan(context) }
        scanning = false
    }

    val allTargets = sections.flatMap { it.targets }

    /** 这一行显示为勾选 = 它自己被选中，或它的某个祖先被选中（父目录覆盖子项）。 */
    fun checked(path: String): Boolean =
        selected.any { it == path || DshDeepClean.isUnder(path, it) }

    fun toggle(path: String, on: Boolean) {
        val next = selected.toMutableSet()
        if (on) {
            // 勾父目录：把已经勾上的子项剔掉 —— 否则同一份数据被算两次
            next.removeAll { it != path && DshDeepClean.isUnder(it, path) }
            next.add(path)
        } else {
            next.remove(path)
            // 取消父目录：它的子项也跟着取消（它们本来是被父覆盖着显示勾选的）
            next.removeAll { DshDeepClean.isUnder(it, path) }
        }
        selected = next
    }

    val chosen = allTargets.filter { checked(it.path) }
    val chosenBytes = chosen.sumOf { it.bytes }

    fun clean() {
        val paths = chosen.map { it.path }
        if (paths.isEmpty()) return
        scope.launch {
            val result = withContext(Dispatchers.IO) { DshDeepClean.delete(context, paths) }
            failed = result.failed
            showToast(context, context.getString(R.string.dsh_clean_done, result.deleted))
            // 删完重扫：留下的与没删掉的要按真实情况重新列
            scanKey++
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dsh_clean_title)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { scanKey++ }, enabled = !scanning) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.dsh_clean_rescan),
                        )
                    }
                },
            )
        },
        bottomBar = {
            // 底栏常驻：选中项与合计在任何滚动位置都看得见
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(
                                R.string.dsh_clean_selected,
                                chosen.size,
                                Formatter.formatFileSize(context, chosenBytes),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { confirming = true }, enabled = chosen.isNotEmpty()) {
                        Text(stringResource(R.string.dsh_clean_action))
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 页头常驻警告：删除直接生效，没有回收站
            item(key = "warning") {
                ExpressiveCard(flat = true) {
                    Text(
                        text = stringResource(R.string.dsh_clean_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
            }

            if (scanning) {
                item(key = "scanning") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.dsh_clean_scanning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            for (section in sections) {
                item(key = "section:" + section.titleRes) {
                    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(
                            text = stringResource(section.titleRes),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(
                                R.string.dsh_clean_partition,
                                section.partitionPath,
                                Formatter.formatFileSize(context, section.freeBytes),
                                Formatter.formatFileSize(context, section.totalBytes),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                section.noteRes?.let { note ->
                    item(key = "note:" + section.titleRes) {
                        Text(
                            text = stringResource(note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                items(section.targets, key = { it.path }) { target ->
                    CleanTargetRow(
                        kind = stringResource(target.kindRes),
                        path = target.path,
                        sizeText = Formatter.formatFileSize(context, target.bytes),
                        checked = checked(target.path),
                        onToggle = { on -> toggle(target.path, on) },
                    )
                }
            }

            if (!scanning && allTargets.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.dsh_clean_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.dsh_clean_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.dsh_clean_confirm_text,
                        Formatter.formatFileSize(context, chosenBytes),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    clean()
                }) { Text(stringResource(R.string.dsh_clean_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (failed.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { failed = emptyList() },
            title = { Text(stringResource(R.string.dsh_clean_failed_title, failed.size)) },
            text = {
                // 限高 + 自己滚，不把对话框顶出屏幕；里面的 Text 不单独限高
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = stringResource(R.string.dsh_clean_failed_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    for (p in failed) {
                        Text(
                            text = p,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { failed = emptyList() }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}

/**
 * 一行清理目标：类别 + 路径 + 体积，整行可勾。
 *
 * 勾选按仓库既有写法：`Modifier.toggleable(value, role = Role.Checkbox, …)` 拿手势与语义，
 * `Checkbox(checked, onCheckedChange = null)` 只做显示 —— 两处都能点会出现"点文字不勾、
 * 点方块才勾"这种不一致。
 */
@Composable
private fun CleanTargetRow(
    kind: String,
    path: String,
    sizeText: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    ExpressiveCard(flat = true) {
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = onToggle)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(kind, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = path,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = sizeText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
