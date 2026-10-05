package me.bmax.apatch.ui.screen.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import me.bmax.apatch.R
import me.bmax.apatch.dsh.Userscripts
import me.bmax.apatch.dsh.WebScripts
import me.bmax.apatch.util.DshWebCompat
import me.bmax.apatch.util.ui.showToast

/**
 * 「用户脚本」管理页：一个不需要发版的扩展口。
 *
 * 装一个 `.user.js`，它会在页面脚本之前跑（document-start），于是能改我们自己的界面 —— 与
 * 应用内置的那几段（见 [WebScripts.BUILTINS]）走的是**同一条注入管道**，区别只在于把"贴一段
 * 试试"的门槛降到不用发版。语义与边界（只服务本应用自己的页面、`@run-at` 三档、SPA 路由不
 * 重跑、同名不覆盖）都写在 [Userscripts] 的 KDoc 里。
 *
 * 这一页**故意是原生的**（不在 WebView 里）：一个坏脚本把页面弄白时，这里是唯一的退路。
 * 所以页面分成上下两段，顺序是有意的：
 *
 * - **上：应用内置**（不可删，逐条开关在行上）。它回答「应用往页面里注入了什么」。
 * - **下：我装的脚本**（总开关 + 粘贴/选文件 + 已导入列表）。它回答「我要不要跑自己的 JS」。
 *
 * 总开关只管下段：一个坏脚本把页面弄白时，内边距/无障碍/兼容垫片还得在 —— 那正是这一页
 * 能把界面救回来的前提。
 */
@Destination<RootGraph>
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserscriptsScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    var master by remember { mutableStateOf(Userscripts.masterEnabled(context)) }
    var scripts by remember { mutableStateOf(Userscripts.list(context)) }
    // 内置那两条有开关的：勾选状态取**当前生效值**（compat 在 auto 下就是按内核算出来的结果）。
    // 拨动即落成 on / off —— auto 这一档只在设置页选，这里不重复表达三态。
    var compatOn by remember { mutableStateOf(DshWebCompat.shouldInject(context)) }
    var compatAuto by remember { mutableStateOf(DshWebCompat.mode(context) == DshWebCompat.MODE_AUTO) }
    var composerOn by remember { mutableStateOf(DshWebCompat.enterNewline(context)) }
    var showPaste by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scripts = Userscripts.list(context)
    }

    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        val text = Userscripts.read(context, uri)
        if (text.isNullOrBlank()) {
            showToast(context, context.getString(R.string.dsh_userscripts_read_failed))
            return@rememberLauncherForActivityResult
        }
        if (Userscripts.install(context, text) == null) {
            showToast(context, context.getString(R.string.dsh_userscripts_read_failed))
        } else {
            reload()
            showToast(context, context.getString(R.string.dsh_userscripts_installed))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dsh_userscripts_title)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── 应用内置：与「我装的脚本」走同一条注入管道，但**不受总开关约束** ──
            // 所以放在最上面：这段说明的是「页面被注入什么」，与下面「我要不要跑自己的脚本」是
            // 两件事。也是「一处看得全」的那一处 —— 以前这些散在设置与代码里。
            Text(
                stringResource(R.string.dsh_userscripts_builtin_section),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
            )
            Text(
                stringResource(R.string.dsh_userscripts_builtin_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            for (entry in WebScripts.BUILTINS) {
                // 这条内置有没有开关、接到哪：与 WebScripts.builtinEnabled 的分支一一对应
                // （compat / composer 有；inset / a11y-labels / blob-download 常开）。
                val onToggle: ((Boolean) -> Unit)?
                val checked: Boolean?
                when (entry.id) {
                    "compat" -> {
                        onToggle = { on ->
                            compatOn = on
                            compatAuto = false
                            DshWebCompat.setMode(
                                context,
                                if (on) DshWebCompat.MODE_ON else DshWebCompat.MODE_OFF,
                            )
                        }
                        checked = compatOn
                    }
                    "composer" -> {
                        onToggle = { on ->
                            composerOn = on
                            DshWebCompat.setEnterNewline(context, on)
                        }
                        checked = composerOn
                    }
                    // 其余三条没有开关：内边距是布局前提，无障碍名字/blob 下载是补页面缺陷
                    // （见 WebScripts 的类 KDoc）。给它们开关只会多出「用户关了之后来报 bug」。
                    else -> {
                        onToggle = null
                        checked = null
                    }
                }
                BuiltinRow(
                    title = stringResource(entry.titleRes),
                    summary = stringResource(entry.summaryRes),
                    checked = checked,
                    autoTag = if (entry.id == "compat" && compatAuto) {
                        stringResource(R.string.dsh_webui_compat_auto)
                    } else {
                        null
                    },
                    onToggle = onToggle,
                )
            }

            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

            // ── 总开关：只管「我装的」那些脚本 ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.dsh_userscripts_master), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.dsh_userscripts_master_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = master,
                    onCheckedChange = {
                        master = it
                        Userscripts.setMasterEnabled(context, it)
                    },
                )
            }
            Text(
                stringResource(R.string.dsh_userscripts_scope),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            // ── 装：贴一段，或从文件选 ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { showPaste = true }) {
                    Text(stringResource(R.string.dsh_userscripts_paste))
                }
                TextButton(onClick = {
                    pickFile.launch(
                        Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }
                    )
                }) {
                    Text(stringResource(R.string.dsh_userscripts_pick))
                }
            }

            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            if (scripts.isEmpty()) {
                Text(
                    stringResource(R.string.dsh_userscripts_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                Text(
                    stringResource(R.string.dsh_userscripts_count, scripts.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
                )
                for (s in scripts) {
                    ScriptRow(
                        script = s,
                        onToggle = { want ->
                            Userscripts.setEnabled(context, s.id, want)
                            reload()
                        },
                        onDelete = { pendingDelete = s.id },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showPaste) {
        AlertDialog(
            onDismissRequest = { showPaste = false },
            title = { Text(stringResource(R.string.dsh_userscripts_paste)) },
            text = {
                Column {
                    Text(stringResource(R.string.dsh_userscripts_paste_hint))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pasted,
                        onValueChange = { pasted = it },
                        placeholder = { Text("==UserScript==") },
                        minLines = 4,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = pasted.isNotBlank(),
                    onClick = {
                        if (Userscripts.install(context, pasted) == null) {
                            showToast(context, context.getString(R.string.dsh_userscripts_read_failed))
                        } else {
                            pasted = ""
                            showPaste = false
                            reload()
                            showToast(context, context.getString(R.string.dsh_userscripts_installed))
                        }
                    },
                ) { Text(stringResource(R.string.dsh_userscripts_install)) }
            },
            dismissButton = {
                TextButton(onClick = { showPaste = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    val deleting = pendingDelete
    if (deleting != null) {
        val title = scripts.firstOrNull { it.id == deleting }?.title ?: deleting
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.dsh_userscripts_delete)) },
            text = { Text(title) },
            confirmButton = {
                TextButton(onClick = {
                    Userscripts.remove(context, deleting)
                    pendingDelete = null
                    reload()
                }) { Text(stringResource(R.string.dsh_userscripts_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/**
 * 内置脚本一行：标题 + 摘要 + （有开关就给开关，没有就标「常开」）。
 *
 * [checked] 为 null 表示这条没有开关（常开）；[autoTag] 是 compat 在 auto 档时的标记，
 * 说明勾选状态是**按内核自动算出来的**，不是用户上次点的那一下。
 */
@Composable
private fun BuiltinRow(
    title: String,
    summary: String,
    checked: Boolean?,
    autoTag: String?,
    onToggle: ((Boolean) -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (autoTag != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        autoTag,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (checked != null && onToggle != null) {
            Switch(checked = checked, onCheckedChange = onToggle)
        } else {
            Text(
                stringResource(R.string.dsh_userscripts_builtin_always_on),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** 一行：名字 + 版本/时机/大小 + 说明与 `@match` + 开关 + 删除。 */
@Composable
private fun ScriptRow(
    script: Userscripts.Script,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(script.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                listOfNotNull(
                    script.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                    "run-at " + script.runAt,
                    "${script.bytes} B",
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (script.description.isNotBlank()) {
                Text(
                    script.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (script.matches.isNotEmpty()) {
                Text(
                    script.matches.joinToString("  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        Switch(checked = script.enabled, onCheckedChange = onToggle)
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.dsh_userscripts_delete))
        }
    }
}
