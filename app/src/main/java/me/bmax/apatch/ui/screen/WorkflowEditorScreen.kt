package me.bmax.apatch.ui.screen

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.dsh.WorkflowSchema
import me.bmax.apatch.dsh.WorkflowStore
import me.bmax.apatch.ui.component.WorkflowDefinitionEditor

/**
 * 工作流编辑页 —— **次级页面**（导航进出的那种）。
 *
 * 以前编辑是覆盖层：设置页里点「编辑」只是把一个 Surface 盖在当前页上，返回栈里没有它，用户
 * 按返回时不知道会回到哪里；审批那条路又要在弹窗里再套一层弹窗。现在它是导航图里的一个目的地：
 *
 *  - 设置页：新建（先填名字）/ 编辑 → `navigate(WorkflowEditorScreenDestination(...))`
 *  - 保存 → 写盘 + `popBackStack()`；取消（含内容里的「放弃改动」确认）→ `popBackStack()`
 *  - 审批那条（AI 发起、可能在任何前台页面弹出）仍走 `WorkflowEditorDialog` 的全屏弹窗，
 *    内容与这一页**完全同一份**（[WorkflowDefinitionEditor]），不会各写一套。
 *
 * ## 两条与审批弹窗**不同**的规则（都只在这一页生效）
 *
 * 1. **自动保存**：普通编辑每改一次就排队落盘（见 `WorkflowDefinitionEditor` 的 autosave 分支）。
 *    AI 审批那一份是临时草稿 —— 未批准的改动绝不能提前覆盖正式流程定义。
 * 2. **名称唯一**：新建要先填名字（重名/空名都不许建），改名也走同一判据。
 *    审批编辑不改名（它改的是"要不要照这份跑"，不是"这叫什么"）。
 */
@Destination<RootGraph>
@Composable
fun WorkflowEditorScreen(
    navigator: DestinationsNavigator,
    workflowId: String = "",
    /** 新建：由列表页先用 [WorkflowNameDialog] 收名字，创建成功后才带着 id 进来。 */
    newName: String = "",
) {
    val ctx = LocalContext.current
    val initial = remember(workflowId) {
        when {
            workflowId.isNotEmpty() -> WorkflowStore.list(ctx).firstOrNull { it.id == workflowId }
            else -> null
        }
    }
    // 定义读不到（被删了 / 文件坏了 / 新建时没带 id）：不留一屏空白，退回列表。
    if (initial == null) {
        LaunchedEffect(Unit) { navigator.popBackStack() }
        return
    }
    WorkflowDefinitionEditor(
        initial = initial,
        title = stringResource(R.string.dsh_workflow_editor_title),
        // 自动保存：界面每次改动都会走这里，返回的是落盘后的定义（含新 revision）。
        // 冲突/失败返回 null，编辑器会显示状态并保留草稿，不会假装已保存。
        autosave = { edited -> WorkflowStore.saveIfRevision(ctx, edited, initial.revision) },
        onSave = { edited ->
            // 手动保存 = 立刻落盘并退出。它同样走零冲突路径：磁盘 revision 变了就不覆盖。
            WorkflowStore.saveIfRevision(ctx, edited, initial.revision)
            navigator.popBackStack()
        },
        onCancel = { navigator.popBackStack() },
        newName = newName,
    )
}

/**
 * 新建流程前收名字的弹窗。
 *
 * 为什么先收名字再进编辑页：编辑页里改名字要多一次进出（先建一个无名的，进去改，再返回看列表），
 * 而列表页那一行**只会显示名字** —— 名字缺失时用户面对的是「未命名」，选不中自己刚建的那个。
 *
 * 判据与 [WorkflowStore.nameTaken] 同一处：trim + 忽略大小写，排除自己。
 * 建成功（真的写盘了）才导航 —— 只创建内存对象然后导航，会在编辑页开局就读不到定义。
 */
@Composable
internal fun WorkflowNameDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    fun validate(raw: String): String? {
        val name = raw.trim()
        return when {
            name.isEmpty() -> ctx.getString(R.string.dsh_workflow_name_empty)
            name.length > WorkflowSchema.MAX_NAME_CHARS ->
                ctx.getString(R.string.dsh_workflow_name_too_long, WorkflowSchema.MAX_NAME_CHARS)
            WorkflowStore.nameTaken(ctx, name) -> ctx.getString(R.string.dsh_workflow_name_taken)
            else -> null
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.dsh_workflow_name_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    error = 0
                },
                label = { Text(stringResource(R.string.dsh_workflow_name_hint)) },
                isError = error != 0,
                singleLine = true,
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
            )
            if (error != 0) {
                Text(
                    text = ctx.getString(error),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    val problem = validate(text)
                    if (problem != null) {
                        error = when {
                            text.trim().isEmpty() -> R.string.dsh_workflow_name_empty
                            text.trim().length > WorkflowSchema.MAX_NAME_CHARS ->
                                R.string.dsh_workflow_name_too_long
                            else -> R.string.dsh_workflow_name_taken
                        }
                        return@TextButton
                    }
                    val name = text.trim()
                    busy = true
                    scope.launch {
                        // 先把文件**建出来**再导航：编辑页开头是按 id 读盘的，
                        // 只造一个内存对象、不写盘，进去就立刻"读不到 → 自动退回"。
                        val created = withContext(Dispatchers.IO) {
                            val def = starter(name)
                            WorkflowStore.saveIfRevision(ctx, def, 0)?.id
                        }
                        busy = false
                        if (created != null) onCreate(created) else error = R.string.dsh_workflow_name_create_failed
                    }
                },
            ) { Text(stringResource(R.string.dsh_workflow_name_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
