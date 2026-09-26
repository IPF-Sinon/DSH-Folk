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
import androidx.compose.material3.Scaffold
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
    val allow = remember { mutableStateListOf<String>().apply { addAll(initialAllow) } }
    val deny = remember { mutableStateListOf<String>().apply { addAll(initialDeny) } }

    // 目录选择器：pickerFor = "allow" | "deny" | null
    var pickerFor by remember { mutableStateOf<String?>(null) }

    val dirty = allow.toList() != initialAllow || deny.toList() != initialDeny

    fun persist() {
        DshFileAccess.setAllowDirs(context, allow.toList())
        DshFileAccess.setDenyDirs(context, deny.toList())
    }

    fun addTo(which: String, rel: String) {
        val target = if (which == "allow") allow else deny
        val merged = DshFileAccess.normalize(target.toList() + rel)
        target.clear(); target.addAll(merged)
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
        }
    }

    if (pickerFor != null) {
        DirPickerDialog(
            onDismiss = { pickerFor = null },
            onPick = { rel ->
                val which = pickerFor
                pickerFor = null
                if (which != null && rel.isNotEmpty()) addTo(which, rel)
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

/** 极简目录浏览器：直接用 java.io.File 遍历 /sdcard（App 已有「所有文件访问」时才列得出）。 */
@Composable
private fun DirPickerDialog(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
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
                enabled = hasPerm && !atRoot,
                onClick = { onPick(relOf(current)) },
            ) { Text(stringResource(R.string.dsh_fs_picker_choose)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
