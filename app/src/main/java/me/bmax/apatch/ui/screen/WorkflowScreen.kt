package me.bmax.apatch.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.WorkflowEditorScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshEnv
import me.bmax.apatch.dsh.DshNativeBridge
import me.bmax.apatch.dsh.DshRuntime
import me.bmax.apatch.dsh.WorkflowApprovals
import me.bmax.apatch.dsh.WorkflowDefinition
import me.bmax.apatch.dsh.WorkflowEdge
import me.bmax.apatch.dsh.WorkflowEngine
import me.bmax.apatch.dsh.WorkflowNode
import me.bmax.apatch.dsh.WorkflowNodeType
import me.bmax.apatch.dsh.WorkflowOnError
import me.bmax.apatch.dsh.WorkflowPermissions
import me.bmax.apatch.dsh.WorkflowRun
import me.bmax.apatch.dsh.WorkflowRunState
import me.bmax.apatch.dsh.WorkflowStore
import me.bmax.apatch.dsh.WorkflowValidator
import me.bmax.apatch.dsh.toJson
import me.bmax.apatch.ui.component.WorkflowDefinitionEditor
import me.bmax.apatch.ui.component.WorkflowGraphPreview
import org.json.JSONObject

/**
 * 设置 → 功能 → 自动化工作流。
 *
 * 这一页回答用户三个问题：**开没开**、**AI 到底能不能调它**、**刚才那次跑成什么样了**。
 * 第三个尤其重要 —— 工作流可以在后台跑很久，用户需要一个不依赖 agent 的窗口自己看。
 *
 * 这里点「运行」时不弹审批框：按按钮的人就是批准的人，而且这一页已经把流程图、能力清单、
 * 危险命令警告都摆在同一屏了。容器里的 AI 走的是另一条路（黄/红弹窗 + 编辑 + diff），
 * 因为它不是站在屏幕前的人。两条路的**权限检查**是一致的（都要先过 [WorkflowPermissions]）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun WorkflowScreen(navigator: DestinationsNavigator) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences(DshEnv.PREF, android.content.Context.MODE_PRIVATE) }
    var enabled by remember { mutableStateOf(prefs.getBoolean(DshEnv.KEY_WORKFLOW_ENABLED, false)) }
    var listKey by remember { mutableIntStateOf(0) }
    var detailRun by remember { mutableStateOf<WorkflowRun?>(null) }
    var dangerousAck by remember { mutableStateOf(false) }
    // 运行/取消之后列表与详情要跟着变；引擎的 tick 是所有状态推进的汇合点。
    val tick by WorkflowEngine.tick.collectAsState()

    // 打开这一页也做一次对账：桥平时只在有请求时才跑 reconcileInterrupted，而用户完全可能
    // 一直没让 AI 碰过工作流 —— 那一页上就会永久挂着一个 running（其实是进程被杀掉的）运行。
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            WorkflowStore.reconcileInterrupted(ctx.applicationContext)
            // 升级 App 后容器可能一直没重启：这里的 dsh-workflow / 指南 / 样例就还是旧的（甚至没有）。
            DshRuntime.refreshContainerCli()
        }
        listKey++
    }
    // 用户自己建/改流程：与审批时的编辑页是**同一个** [WorkflowDefinitionEditor]。
    val newFlowName = stringResource(R.string.dsh_workflow_new_name)

    val workflows = remember(listKey, tick) { WorkflowStore.list(ctx) }
    val runs = remember(listKey, tick) { WorkflowStore.listRuns(ctx, limit = 12) }
    // 自检：agent 到底有没有拿到「工具 + 指南」。缺哪一项、下一步做什么，都摆在这一屏上 ——
    // 否则用户只能看到「AI 不会用」，而没有任何可查的证据。
    val checks = remember(listKey) { containerChecks(ctx) }
    var restartOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dsh_workflow_title)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.dsh_workflow_switch), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.dsh_workflow_switch_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        enabled = on
                        prefs.edit().putBoolean(DshEnv.KEY_WORKFLOW_ENABLED, on).apply()
                        if (on) scope.launch { withContext(Dispatchers.IO) { DshRuntime.refreshContainerCli() } }
                        listKey++
                    },
                )
            }
            if (!enabled) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.dsh_workflow_disabled_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))
            val tool = readToolStatus(ctx)
            Text(
                stringResource(R.string.dsh_workflow_tools_status, tool.state),
                style = MaterialTheme.typography.bodySmall,
            )
            if (tool.state != "ok") {
                Text(
                    stringResource(R.string.dsh_workflow_tools_restart),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.dsh_workflow_selfcheck), style = MaterialTheme.typography.labelLarge)
            for (c in checks) {
                Text(
                    (if (c.ok) "\u2713 " else if (c.warn) "\u26a0 " else "\u2717 ") + stringResource(c.labelRes) +
                        (if (c.detail.isBlank()) "" else "：" + c.detail),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (c.ok) MaterialTheme.colorScheme.onSurfaceVariant
                    else if (c.warn) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
            }
            if (checks.any { !it.ok && !it.warn }) {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { DshRuntime.refreshContainerCli() }
                        listKey++
                    }
                }) { Text(stringResource(R.string.dsh_workflow_fix_assets)) }
            }
            TextButton(onClick = { restartOpen = true }, enabled = enabled) {
                Text(stringResource(R.string.dsh_workflow_restart_dsh))
            }
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.dsh_workflow_list_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { navigator.navigate(WorkflowEditorScreenDestination(isNew = true)) }, enabled = enabled) {
                    Text(stringResource(R.string.dsh_workflow_new))
                }
            }
            if (workflows.isEmpty()) {
                Text(
                    stringResource(R.string.dsh_workflow_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            for (def in workflows) {
                Spacer(Modifier.height(8.dp))
                // 校验结果就地算：定义不合法时**运行按钮点不动**，并把同一批错误显示给用户
                // （与 AI 收到的那份错误同源，省得「AI 说能跑、这里跑不了」）。
                val errors = remember(def) {
                    WorkflowValidator.validate(def, DshNativeBridge.Cap.entries.map { it.id }.toSet()).errors
                }
                WorkflowRow(
                    def = def,
                    lastState = runs.firstOrNull { it.workflowId == def.id }?.state,
                    enabled = enabled,
                    valid = errors.isEmpty(),
                    errors = errors,
                    dangerousAck = dangerousAck,
                    onDangerousAck = { dangerousAck = it },
                    onEdit = { navigator.navigate(WorkflowEditorScreenDestination(workflowId = def.id)) },
                    onOpenDetail = {
                        detailRun = runs.firstOrNull { it.workflowId == def.id }
                    },
                    onRun = {
                        scope.launch {
                            if (withContext(Dispatchers.IO) { awaitPermissions(ctx, def) }) {
                                withContext(Dispatchers.IO) {
                                    WorkflowEngine.start(
                                        ctx.applicationContext,
                                        def,
                                        emptyList(),
                                        // 页面上的勾选就是「含危险命令也放行」的凭据；没勾就 false。
                                        allowDangerous = dangerousAck && def.declaresDangerous,
                                    )
                                }
                                listKey++
                            }
                        }
                    },
                    onDelete = {
                        WorkflowStore.delete(ctx, def.id)
                        listKey++
                    },
                )
            }

            if (runs.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.dsh_workflow_runs_title), style = MaterialTheme.typography.titleSmall)
                for (run in runs) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        tonalElevation = 1.dp,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(run.workflowName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    stringResource(
                                        R.string.dsh_workflow_run_summary,
                                        run.state.id,
                                        run.steps.size,
                                        run.runId,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (run.running) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (run.cancelReason.isNotBlank()) {
                                    Text(
                                        run.cancelReason,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            if (run.running) {
                                TextButton(onClick = {
                                    WorkflowEngine.cancel(ctx, run.runId, "canceled by user")
                                    listKey++
                                }) { Text(stringResource(R.string.dsh_workflow_action_cancel)) }
                            } else {
                                // 详情走弹窗：里面给**当时那一版定义**与逐步结果 —— 用户要看的正是
                                // 「它当时到底跑了什么、结果是什么」，而不是列表里挤出来的几行。
                                TextButton(onClick = { detailRun = run }) {
                                    Text(stringResource(R.string.dsh_workflow_detail))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    detailRun?.let { run ->
        RunDetailDialog(run = run, onDismiss = { detailRun = null })
    }

    if (restartOpen) {
        AlertDialog(
            onDismissRequest = { restartOpen = false },
            title = { Text(stringResource(R.string.dsh_workflow_restart_title)) },
            text = {
                Text(
                    stringResource(R.string.dsh_workflow_restart_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    restartOpen = false
                    scope.launch {
                        withContext(Dispatchers.IO) { DshRuntime.refreshContainerCli() }
                        DshRuntime.restart()
                        listKey++
                    }
                }) { Text(stringResource(R.string.dsh_workflow_restart_now)) }
            },
            dismissButton = {
                TextButton(onClick = { restartOpen = false }) {
                    Text(stringResource(R.string.dsh_workflow_action_cancel))
                }
            },
        )
    }

    // 编辑与新建都走次级页面（WorkflowEditorScreen），不再在本页盖一层。

}

/**
 * 用户自建流程的起点：入口 + 一条 shell 命令，已经是**合法**定义（能直接跑）。
 *
 * 为什么默认给一条 echo 而不是空命令：空 cmd 会被校验器判为不合法，用户点完「新建」第一件
 * 事就是撞见红色错误 —— 从一个能跑的最小流程开始改，才符合「自己建」的心理模型。
 */
internal fun starter(name: String): WorkflowDefinition =
    run {
        WorkflowDefinition(
            id = WorkflowStore.newId(),
            name = name,
            description = "",
            declaredCaps = emptyList(),
            nodes = listOf(
                WorkflowNode(
                    id = "trigger",
                    type = WorkflowNodeType.TRIGGER,
                    name = "trigger",
                    posX = 16,
                    posY = 16,
                    timeoutMs = 60_000L,
                    onError = WorkflowOnError.STOP,
                    config = mapOf("requireDsh" to "0"),
                ),
                WorkflowNode(
                    id = "step1",
                    type = WorkflowNodeType.EXECUTE,
                    name = "step1",
                    posX = 16,
                    posY = 72,
                    onError = WorkflowOnError.STOP,
                    config = mapOf("action" to "shell", "cmd" to "echo hello"),
                ),
            ),
            edges = listOf(WorkflowEdge("trigger", "step1", "")),
        )
    }

@Composable
private fun WorkflowRow(
    def: WorkflowDefinition,
    lastState: WorkflowRunState?,
    enabled: Boolean,
    valid: Boolean,
    errors: List<String>,
    dangerousAck: Boolean,
    onDangerousAck: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onOpenDetail: () -> Unit,
    onRun: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .weight(1f)
                    .clickableRow { expanded = !expanded },
            ) {
                Text(def.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.dsh_workflow_row_summary, def.nodes.size, def.edges.size) +
                        (lastState?.let { " · " + it.id } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onRun, enabled = enabled && valid) { Text(stringResource(R.string.dsh_workflow_action_run)) }
        }
        if (!valid) {
            Text(
                stringResource(R.string.dsh_workflow_invalid_run),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                errors.take(4).joinToString("\n"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            WorkflowGraphPreview(def)
            if (def.declaredCaps.isNotEmpty()) {
                Text(
                    stringResource(R.string.dsh_workflow_row_caps, def.declaredCaps.joinToString("、")),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (def.declaresDangerous) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = dangerousAck, onCheckedChange = onDangerousAck)
                    Text(
                        stringResource(R.string.dsh_workflow_row_dangerous),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onEdit) { Text(stringResource(R.string.dsh_workflow_action_edit)) }
                TextButton(onClick = onOpenDetail) { Text(stringResource(R.string.dsh_workflow_detail)) }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.dsh_workflow_action_delete)) }
            }
        }
    }
}

/** 运行前把权限检查弹出来并等结论（弹窗在 [me.bmax.apatch.ui.component.WorkflowDialogHosts]）。 */
private suspend fun awaitPermissions(
    ctx: android.content.Context,
    def: WorkflowDefinition,
): Boolean {
    val request = WorkflowPermissions.Gate.submit(
        workflowName = def.name,
        caps = def.declaredCaps,
        reason = def.name,
    ) ?: return false
    return WorkflowPermissions.Gate.awaitDecision(request.id) == WorkflowPermissions.Gate.Decision.START
}

/**
 * 容器里的工具注册状态（插件自己写的）。
 *
 * 如实显示：`ok` = 四个工具都注册上了；`degraded`/`unavailable` = agent 只能用 `dsh-workflow`
 * CLI。用户看到这句才知道「agent 说没有 workflow_run 这个工具」是不是正常现象。
 */
/**
 * 一次运行的详情弹窗：**当时那一版定义**（流程图 + JSON）、逐步结果、用户改动 diff、日志尾部。
 *
 * 为什么是弹窗而不是就地把列表撑开：定义 + 结果 + 日志三块放在列表里会把一页拉得很长，而用户
 * 看详情时想的是「这一次到底跑了什么」，独立出来才读得清。
 */
@Composable
private fun RunDetailDialog(run: WorkflowRun, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val logs = remember(run.runId) { WorkflowStore.readLog(ctx, run.runId, fromLine = 1, maxLines = 200).first }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dsh_workflow_detail_title, run.workflowName)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.dsh_workflow_run_summary, run.state.id, run.steps.size, run.runId),
                    style = MaterialTheme.typography.labelMedium,
                )
                val def = run.definition
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.dsh_workflow_detail_definition),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (def != null) {
                    // 预览可缩放（真机反馈「预览图不能缩放」）：外层双向滚动 + graphicsLayer 缩放，
                    // 并按缩放后的尺寸占位 —— 只加 scale 不占位的话，放大后右半边会被裁掉。
                    var zoom by remember(run.runId) { mutableStateOf(1f) }
                    var content by remember(run.runId) { mutableStateOf(IntSize.Zero) }
                    val density = LocalDensity.current
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { zoom = (zoom - 0.25f).coerceAtLeast(0.75f) }) { Text("-") }
                        Text(
                            stringResource(R.string.dsh_workflow_zoom, (zoom * 100).toInt()),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        TextButton(onClick = { zoom = (zoom + 0.25f).coerceAtMost(2.5f) }) { Text("+") }
                        TextButton(onClick = { zoom = 1f }) {
                            Text(stringResource(R.string.dsh_workflow_zoom_reset))
                        }
                    }
                    Box(
                        Modifier
                            .height(320.dp)
                            .horizontalScroll(rememberScrollState())
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Box(
                            Modifier
                                .requiredSize(
                                    with(density) { (content.width * zoom).toDp() },
                                    with(density) { (content.height * zoom).toDp() },
                                )
                                .graphicsLayer(
                                    scaleX = zoom,
                                    scaleY = zoom,
                                    transformOrigin = TransformOrigin(0f, 0f),
                                ),
                        ) {
                            Box(Modifier.onSizeChanged { content = it }) { WorkflowGraphPreview(def) }
                        }
                    }
                    // 定义 JSON 默认收起：展开的长 JSON 会把流程图和结果挤在一起（真机截图就是糊的）
                    var showJson by remember(run.runId) { mutableStateOf(false) }
                    TextButton(onClick = { showJson = !showJson }) {
                        Text(
                            stringResource(
                                if (showJson) R.string.dsh_workflow_detail_json_hide
                                else R.string.dsh_workflow_detail_json_show,
                            ),
                        )
                    }
                    if (showJson) {
                        Text(
                            def.toJson().toString(2),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.dsh_workflow_detail_no_definition),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.dsh_workflow_detail_result),
                    style = MaterialTheme.typography.labelLarge,
                )
                for (st in run.steps) {
                    Text(
                        st.name + " · " + st.state.id +
                            (st.exitCode?.let { " exit=" + it } ?: "") +
                            " · " + st.durationMs + "ms" +
                            (if (st.reason.isBlank()) "" else " · " + st.reason),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (st.outputTail.isNotBlank()) {
                        Text(
                            st.outputTail.takeLast(600),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (run.diff.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.dsh_workflow_run_diff),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        run.diff.joinToString("\n") { it.humanize() },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (logs.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.dsh_workflow_detail_logs),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        logs.takeLast(120).joinToString("\n"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dsh_workflow_action_close)) }
        },
    )
}

/** 自检的一项。三态而不是两态：`warn` 表示「不是坏的，只是这一条现在不成立」—— 例如工具插件
 * 没注册上（agent 还能走 CLI）或者状态文件还没写出来。把 warn 画成红叉，用户就会去修一个
 * 其实能用的东西（实测里那个假红点就是这么来的）。
 */
private data class ContainerCheck(
    val labelRes: Int,
    val ok: Boolean,
    val detail: String = "",
    val warn: Boolean = false,
)

/**
 * 容器侧自检：agent 要能看到工作流，必需的东西到底在不在。
 *
 * 这几项**都是文件系统事实**（不是猜），所以用户报「AI 不会用」时，看一眼就知道缺哪一环：
 * 命令/指南/样例是即时可用的；插件与挂载项决定 agent 是否知道它存在，而插件要 DSH 重启才加载。
 */
private fun containerChecks(ctx: android.content.Context): List<ContainerCheck> {
    val rootfs = DshEnv.rootfs(ctx)
    val home = DshEnv.dshHome(ctx)
    val cli = File(rootfs, "usr/local/bin/dsh-workflow")
    val guide = File(home, "workflow-dsl.md")
    val samples = File(home, "workflow-samples")
    val plugin = File(home, "plugins/dsh-folk-workflow.mjs")
    val patch = File(home, "cordis.patch.yml")
    val sampleCount = samples.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.size ?: 0
    val patchHasEntry = runCatching { patch.isFile && patch.readText().contains("dsh-folk-workflow") }
        .getOrDefault(false)
    val tool = readToolStatus(ctx)
    return listOf(
        ContainerCheck(R.string.dsh_workflow_selfcheck_cli, cli.isFile && cli.length() > 0L),
        ContainerCheck(R.string.dsh_workflow_selfcheck_guide, guide.isFile && guide.length() > 0L, "${guide.length()} B"),
        ContainerCheck(R.string.dsh_workflow_selfcheck_samples, sampleCount > 0, "$sampleCount"),
        ContainerCheck(R.string.dsh_workflow_selfcheck_plugin, plugin.isFile && plugin.length() > 0L, "${plugin.length()} B"),
        ContainerCheck(R.string.dsh_workflow_selfcheck_patch, patchHasEntry),
        // 工具注册：只有「红」的条件是**两条路都不通**（没有 CLI 也没有工具）。只差工具那一条
        // 时算黄：agent 照样能用 dsh-workflow 干活，用户没必要为此去修什么。
        ContainerCheck(
            R.string.dsh_workflow_selfcheck_tools,
            ok = tool.state == "ok",
            detail = if (tool.reason.isBlank()) tool.state else tool.state + " — " + tool.reason.take(120),
            warn = tool.state != "ok" && cli.isFile && cli.length() > 0L,
        ),
    )
}

/** 插件写回来的诊断（工具注册状态 + 原因）。 */
private data class ToolStatus(val state: String, val reason: String)

private fun readToolStatus(ctx: android.content.Context): ToolStatus {
    val f = File(DshEnv.dshHome(ctx), "workflow-tool-status.json")
    if (!f.isFile) return ToolStatus("unknown", "")
    return runCatching {
        val o = JSONObject(f.readText())
        ToolStatus(
            state = o.optString("toolsState", o.optString("state", "unknown")),
            reason = o.optString("toolsReason", o.optString("reason", "")),
        )
    }.getOrDefault(ToolStatus("unknown", ""))
}

/** 让整行可点（行里没有别的可点元素时用它，触摸范围就是整行）。 */
private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
