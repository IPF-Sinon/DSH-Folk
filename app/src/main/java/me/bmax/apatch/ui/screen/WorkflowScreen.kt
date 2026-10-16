package me.bmax.apatch.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
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
import me.bmax.apatch.dsh.WorkflowRunState
import me.bmax.apatch.dsh.WorkflowStore
import me.bmax.apatch.dsh.WorkflowValidator
import me.bmax.apatch.ui.component.WorkflowDefinitionEditor
import me.bmax.apatch.ui.component.WorkflowGraphPreview
import java.io.File
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
    var detailRunId by remember { mutableStateOf<String?>(null) }
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
    var editing by remember { mutableStateOf<WorkflowDefinition?>(null) }
    val newFlowName = stringResource(R.string.dsh_workflow_new_name)

    val workflows = remember(listKey, tick) { WorkflowStore.list(ctx) }
    val runs = remember(listKey, tick) { WorkflowStore.listRuns(ctx, limit = 12) }
    val detail = detailRunId?.let { id -> runs.firstOrNull { it.runId == id } ?: WorkflowEngine.status(ctx, id) }
    val toolStatus = remember(listKey) { readToolStatus(ctx) }

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
            Text(stringResource(R.string.dsh_workflow_tools_status, toolStatus), style = MaterialTheme.typography.bodySmall)
            if (toolStatus != "ok") {
                Text(
                    stringResource(R.string.dsh_workflow_tools_restart),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.dsh_workflow_list_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { editing = starter(newFlowName) }, enabled = enabled) {
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
                    onEdit = { editing = def },
                    onOpenDetail = {
                        detailRunId = runs.firstOrNull { it.workflowId == def.id }?.runId
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
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier
                                .weight(1f)
                                .clickableRow { detailRunId = if (detailRunId == run.runId) null else run.runId },
                        ) {
                            Text(
                                "${run.workflowName} · ${run.state.id}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "${run.runId} · ${run.steps.size} " + stringResource(R.string.dsh_workflow_steps),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (run.running) {
                            TextButton(onClick = {
                                WorkflowEngine.cancel(ctx, run.runId, "canceled by user")
                                listKey++
                            }) { Text(stringResource(R.string.dsh_workflow_action_cancel)) }
                        } else {
                            TextButton(onClick = { detailRunId = run.runId }) {
                                Text(stringResource(R.string.dsh_workflow_detail))
                            }
                        }
                    }
                    if (detailRunId == run.runId) {
                        val steps = run.steps
                        for (s in steps) {
                            Text(
                                "· ${s.name} [${s.state.id}]" +
                                    (s.exitCode?.let { " exit=$it" } ?: "") +
                                    (if (s.reason.isBlank()) "" else " (${s.reason})"),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            if (s.outputTail.isNotBlank()) {
                                Text(
                                    s.outputTail.lines().takeLast(6).joinToString("\n"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (run.diff.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.dsh_workflow_run_diff), style = MaterialTheme.typography.labelLarge)
                            Text(
                                run.diff.joinToString("\n") { it.humanize() },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (detail != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.dsh_workflow_detail_hint, detail.runId),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // 编辑器铺满整页（它自带全屏 Surface），保存后回到列表并重新读盘。
    editing?.let { target ->
        WorkflowDefinitionEditor(
            initial = target,
            title = stringResource(R.string.dsh_workflow_editor_title),
            onSave = { edited ->
                WorkflowStore.save(ctx, edited)
                editing = null
                listKey++
            },
            onCancel = { editing = null },
        )
    }
}

/**
 * 用户自建流程的起点：入口 + 一条 shell 命令，已经是**合法**定义（能直接跑）。
 *
 * 为什么默认给一条 echo 而不是空命令：空 cmd 会被校验器判为不合法，用户点完「新建」第一件
 * 事就是撞见红色错误 —— 从一个能跑的最小流程开始改，才符合「自己建」的心理模型。
 */
private fun starter(name: String): WorkflowDefinition =
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
private fun readToolStatus(ctx: android.content.Context): String {
    val f = File(DshEnv.dshHome(ctx), "workflow-tool-status.json")
    if (!f.isFile) return "unknown"
    return runCatching { JSONObject(f.readText()).optString("state", "unknown") }.getOrDefault("unknown")
}

/** 让整行可点（行里没有别的可点元素时用它，触摸范围就是整行）。 */
private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
