package me.bmax.apatch.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import me.bmax.apatch.R
import me.bmax.apatch.dsh.WorkflowStore
import me.bmax.apatch.ui.component.WorkflowDefinitionEditor

/**
 * 工作流编辑页 —— **次级页面**（导航进出的那种）。
 *
 * 以前编辑是覆盖层：设置页里点「编辑」只是把一个 Surface 盖在当前页上，返回栈里没有它，用户
 * 按返回时不知道会回到哪里；审批那条路又要在弹窗里再套一层弹窗。现在它是导航图里的一个目的地：
 *
 *  - 设置页：新建 / 编辑 → `navigate(WorkflowEditorScreenDestination(...))`
 *  - 保存 → 写盘 + `popBackStack()`；取消（含内容里的「放弃改动」确认）→ `popBackStack()`
 *  - 审批那条（AI 发起、可能在任何前台页面弹出）仍走 `WorkflowEditorDialog` 的全屏弹窗，
 *    内容与这一页**完全同一份**（[WorkflowDefinitionEditor]），不会各写一套。
 */
@Destination<RootGraph>
@Composable
fun WorkflowEditorScreen(
    navigator: DestinationsNavigator,
    workflowId: String = "",
    isNew: Boolean = false,
) {
    val ctx = LocalContext.current
    val initial = remember(workflowId, isNew) {
        when {
            workflowId.isNotEmpty() -> WorkflowStore.list(ctx).firstOrNull { it.id == workflowId }
            isNew -> starter(ctx.getString(R.string.dsh_workflow_new_name))
            else -> null
        }
    }
    // 定义读不到（被删了 / 文件坏了）：不留一屏空白，退回列表。
    if (initial == null) {
        LaunchedEffect(Unit) { navigator.popBackStack() }
        return
    }
    WorkflowDefinitionEditor(
        initial = initial,
        title = stringResource(R.string.dsh_workflow_editor_title),
        onSave = { edited ->
            WorkflowStore.save(ctx, edited)
            navigator.popBackStack()
        },
        onCancel = { navigator.popBackStack() },
    )
}
