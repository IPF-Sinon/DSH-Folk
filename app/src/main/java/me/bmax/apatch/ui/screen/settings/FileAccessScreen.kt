package me.bmax.apatch.ui.screen.settings

import android.os.Build
import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshFileAccess
import me.bmax.apatch.dsh.DshRuntime
import java.io.File

/**
 * 「文件访问范围」子页：管理容器可访问手机目录的黑白名单。
 *
 * 规则见 [DshFileAccess]。改动只写偏好，**真正生效在容器启动那一刻的 bind 挂载**，所以
 * 名单一改就显示「需重启 DSH」横幅，用户点「重启 DSH」（[DshRuntime.restart]）后新挂载才生效。
 */
@Destination<RootGraph>
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileAccessScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current

    // 初始快照，用来判断「有没有改动过、要不要提示重启」
    val initialAllow = remember { DshFileAccess.allowDirs(context) }
    val initialDeny = remember { DshFileAccess.denyDirs(context) }
    val initialWsMount = remember { DshFileAccess.wsMountEnabled(context) }
    val initialWsMounts = remember { DshFileAccess.workspaceMounts(context) }
    val allow = remember { mutableStateListOf<String>().apply { addAll(initialAllow) } }
    val deny = remember { mutableStateListOf<String>().apply { addAll(initialDeny) } }
    var wsMount by remember { mutableStateOf(initialWsMount) }
    val wsMounts = remember {
        mutableStateListOf<DshFileAccess.WsMount>().apply { addAll(initialWsMounts) }
    }

    // 目录选择器：pickerFor = "allow" | "deny" | "ws" | null
    var pickerFor by remember { mutableStateOf<String?>(null) }
    // 工作区映射：选完 src 后填 dest 的挂起态
    var wsPendingSrc by remember { mutableStateOf<String?>(null) }
    // 共享存储是否支持真硬链接（决定要不要提示「write 工具在此会失败」）。可在页内重新检测。
    var storageLinkOk by remember { mutableStateOf(DshFileAccess.storageLinkSupported(context)) }

    val dirty = allow.toList() != initialAllow || deny.toList() != initialDeny ||
        wsMount != initialWsMount || wsMounts.toList() != initialWsMounts

    fun persist() {
        DshFileAccess.setAllowDirs(context, allow.toList())
        DshFileAccess.setDenyDirs(context, deny.toList())
        DshFileAccess.setWsMountEnabled(context, wsMount)
        DshFileAccess.setWorkspaceMounts(context, wsMounts.toList())
    }

    fun addTo(which: String, rel: String) {
        val target = if (which == "allow") allow else deny
        val merged = DshFileAccess.normalize(target.toList() + rel)
        target.clear(); target.addAll(merged)
        persist()
    }

    fun addWsMount(src: String, dest: String) {
        val d = DshFileAccess.normalizeDest(dest)
        // 同一 dest 只留一条：先移除既有同名，再追加
        val filtered = wsMounts.filter { it.dest != d }
        wsMounts.clear()
        wsMounts.addAll(filtered)
        wsMounts.add(DshFileAccess.WsMount(src, d))
        persist()
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.dsh_fs_access_title)) },
            navigationIcon = {
                IconButton(onClick = { navigator.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                }
            },
        )
    }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.dsh_fs_access_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (dirty) {
                Spacer(Modifier.height(12.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.dsh_fs_restart_needed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = { persist(); DshRuntime.restart() }) {
                            Text(stringResource(R.string.dsh_fs_restart_now))
                        }
                    }
                }
            }

            // ── 白名单 ──
            Spacer(Modifier.height(16.dp))
            DirListSection(
                header = stringResource(R.string.dsh_fs_allow_header),
                entries = allow,
                emptyHint = stringResource(R.string.dsh_fs_empty_allow),
                onAdd = { pickerFor = "allow" },
                onRemove = { allow.remove(it); persist() },
            )

            // ── 黑名单 ──
            Spacer(Modifier.height(16.dp))
            DirListSection(
                header = stringResource(R.string.dsh_fs_deny_header),
                entries = deny,
                emptyHint = stringResource(R.string.dsh_fs_empty_deny),
                onAdd = { pickerFor = "deny" },
                onRemove = { deny.remove(it); persist() },
                extra = {
                    TextButton(onClick = {
                        deny.clear(); deny.addAll(DshFileAccess.DEFAULT_DENY); persist()
                    }) { Text(stringResource(R.string.dsh_fs_reset_deny_default)) }
                },
            )

            // ── 挂载进工作区 ──
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.dsh_ws_mount_header),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.dsh_ws_mount_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = wsMount, onCheckedChange = { wsMount = it; persist() })
            }
            if (wsMount) {
                Spacer(Modifier.height(8.dp))
                if (wsMounts.isEmpty()) {
                    Text(
                        text = stringResource(R.string.dsh_ws_mount_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    for (m in wsMounts) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.dsh_ws_mount_source) + "  " +
                                        (if (m.src.isEmpty()) "/sdcard" else "/sdcard/" + m.src),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    text = stringResource(R.string.dsh_ws_mount_destination) +
                                        "  /root/workspace/" + m.dest,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { wsMounts.remove(m); persist() }) {
                                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.dsh_fs_remove))
                            }
                        }
                    }
                }
                // 详细限制注入 AI 的宿主提示词；这里给人类保留紧凑提醒，避免占满屏幕。
                if (!storageLinkOk) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.dsh_ws_mount_warn_title),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            DshFileAccess.resetStorageLinkProbe()
                            storageLinkOk = DshFileAccess.storageLinkSupported(context)
                        }) { Text(stringResource(R.string.dsh_ws_mount_recheck)) }
                    }
                }
                Text(
                    text = stringResource(R.string.dsh_ws_mount_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                TextButton(onClick = { pickerFor = "ws" }) {
                    Text(stringResource(R.string.dsh_ws_mount_add))
                }
            }
        }
    }

    if (pickerFor != null) {
        DirPickerDialog(
            allowRoot = pickerFor == "ws",
            onDismiss = { pickerFor = null },
            onPick = { rel ->
                val which = pickerFor
                pickerFor = null
                when (which) {
                    "ws" -> wsPendingSrc = rel // 选完 src，接着填 dest
                    "allow", "deny" -> if (rel.isNotEmpty()) addTo(which, rel)
                }
            },
        )
    }

    if (wsPendingSrc != null) {
        WsDestDialog(
            src = wsPendingSrc!!,
            onDismiss = { wsPendingSrc = null },
            onConfirm = { dest ->
                val src = wsPendingSrc!!
                wsPendingSrc = null
                addWsMount(src, dest)
            },
        )
    }
}

@Composable
private fun DirListSection(
    header: String,
    entries: List<String>,
    emptyHint: String,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    Text(header, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    if (entries.isEmpty()) {
        Text(
            text = emptyHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        for (e in entries) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = e,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onRemove(e) }) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.dsh_fs_remove))
                }
            }
        }
    }
    Row {
        TextButton(onClick = onAdd) { Text(stringResource(R.string.dsh_fs_add_dir)) }
        extra?.invoke()
    }
}

/** 工作区映射：选完 src 后填「工作区下目的子路径」的对话框（默认预填 sdcard）。 */
@Composable
private fun WsDestDialog(
    src: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var dest by remember { mutableStateOf("sdcard") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dsh_ws_mount_dest_title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = "/sdcard/" + src,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = dest,
                    onValueChange = { dest = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.dsh_ws_mount_dest_label)) },
                    prefix = { Text("/root/workspace/") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.dsh_ws_mount_dest_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = dest.isNotBlank(),
                onClick = { onConfirm(dest) },
            ) { Text(stringResource(R.string.dsh_fs_picker_choose)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/** 极简目录浏览器：直接用 java.io.File 遍历 /sdcard（App 已有「所有文件访问」时才列得出）。 */
@Composable
private fun DirPickerDialog(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    /** 允许直接选「当前目录」（含根 /sdcard）——工作区映射用它把整棵 /sdcard 挂进去。 */
    allowRoot: Boolean = false,
) {
    val root = remember { Environment.getExternalStorageDirectory() }
    val hasPerm = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true
    }
    var current by remember { mutableStateOf(root) }
    // 相对 /sdcard 的相对路径（根为空串）
    fun relOf(f: File): String = f.absolutePath.removePrefix(root.absolutePath).trim('/')
    val subDirs = remember(current, hasPerm) {
        if (!hasPerm) emptyList()
        else (current.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() } ?: emptyList())
    }
    val atRoot = current.absolutePath == root.absolutePath

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dsh_fs_picker_title)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                Text(
                    text = "/sdcard/" + relOf(current),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (!hasPerm) {
                    Text(
                        text = stringResource(R.string.dsh_fs_picker_need_perm),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        if (!atRoot) {
                            item {
                                Text(
                                    text = stringResource(R.string.dsh_fs_picker_up),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { current.parentFile?.let { current = it } }
                                        .padding(vertical = 10.dp),
                                )
                            }
                        }
                        items(subDirs) { d ->
                            Row(
                                Modifier.fillMaxWidth().clickable { current = d }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(d.name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                // 工作区映射允许选根（整棵 /sdcard，src=""）；黑白名单仍要求进到子目录再选
                enabled = hasPerm && (allowRoot || !atRoot),
                onClick = { onPick(relOf(current)) },
            ) { Text(stringResource(R.string.dsh_fs_picker_choose)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
