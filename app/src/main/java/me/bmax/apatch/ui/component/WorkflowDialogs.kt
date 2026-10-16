package me.bmax.apatch.ui.component

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.delay
import me.bmax.apatch.R
import me.bmax.apatch.dsh.WorkflowApprovals
import me.bmax.apatch.dsh.WorkflowDefinition
import me.bmax.apatch.dsh.WorkflowNode
import me.bmax.apatch.dsh.WorkflowNodeType
import me.bmax.apatch.dsh.WorkflowPatch
import me.bmax.apatch.dsh.WorkflowPermissions
import me.bmax.apatch.dsh.definitionFromJson
import me.bmax.apatch.dsh.workflowDiff

/**
 * 工作流的两个弹窗 + 编辑页。
 *
 * ## 为什么是「两个弹窗一个宿主」
 *
 * 审批（黄=建 / 红=跑）与运行前权限检查都是**阻塞式**的：桥那边的连接正等在结论上。两件事
 * 必须都在前台可见，所以宿主 `[WorkflowDialogHosts]` 一次挂进 MainActivity 与 DshWebUiActivity
 * —— WebUI 是独立 Activity，不挂过去，用户在 WebUI 里让 agent 建工作流就会石沉大海。
 *
 * ## 编辑页为什么是宿主内的全屏层，而不是一个 `@Destination`
 *
 * 宿主挂在两个 Activity 上，其中 WebUI 那个没有 nav 图可跳。做成全屏覆盖层之后，两个宿主
 * 行为一致、也不需要把 navigator 一路传进来；代价是失去了目的地切换动画（可接受：
 * 用户此刻的注意力在「这份流程要不要放行」上，不是在页面转场上）。
 *
 * 编辑期间审批的倒计时是暂停的（见 [WorkflowApprovals.startEditing]）——用户在改东西的时候
 * 被判定超时，那不是安全设计。
 */
@Composable
fun WorkflowDialogHosts() {
    val ctx = LocalContext.current
    val approval by WorkflowApprovals.pending.collectAsState()
    val waiting by WorkflowPermissions.Gate.pending.collectAsState()

    approval?.let { request ->
        if (request.editing) {
            WorkflowEditorOverlay(
                originalJson = request.originalJson,
                onSave = { edited ->
                    WorkflowApprovals.stopEditing(request.id, edited)
                },
                onCancel = { WorkflowApprovals.stopEditing(request.id, null) },
            )
        } else {
            WorkflowApprovalDialog(
                request = request,
                onApprove = { dangerous -> WorkflowApprovals.approve(request.id, dangerous) },
                onReject = { reason -> WorkflowApprovals.reject(request.id, reason) },
                onEdit = { WorkflowApprovals.startEditing(request.id) },
            )
        }
    }

    waiting?.let { request ->
        WorkflowPermissionDialog(
            request = request,
            onStart = { WorkflowPermissions.Gate.start(request.id) },
            onCancel = { WorkflowPermissions.Gate.cancel(request.id) },
            check = { WorkflowPermissions.missing(ctx.applicationContext, request.caps) },
            onOpen = { item ->
                val intent = WorkflowPermissions.settingsIntent(ctx, item)
                if (intent != null) {
                    runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            },
        )
    }
}

@Composable
private fun WorkflowApprovalDialog(
    request: WorkflowApprovals.Request,
    onApprove: (Boolean) -> Unit,
    onReject: (String) -> Unit,
    onEdit: () -> Unit,
) {
    val creating = request.kind == WorkflowApprovals.Kind.CREATE
    val accent = if (creating) Color(0xFFB58900) else MaterialTheme.colorScheme.error
    var rejectOpen by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    var allowDangerous by remember { mutableStateOf(false) }
    val definition = remember(request.id) { definitionFromJson(request.originalJson) }

    // 倒计时：每秒重算一次剩余时间，归零后由状态机自己过期（这里不主动拒绝，
    // 免得「弹窗自己消失」和「服务端判定超时」变成两套真相）。
    var remaining by remember(request.id) { mutableStateOf(WorkflowApprovals.remainingMs(request)) }
    LaunchedEffect(request.id, request.expiresAtMs) {
        while (true) {
            remaining = WorkflowApprovals.remainingMs(request)
            delay(500)
        }
    }

    AlertDialog(
        onDismissRequest = { onReject("") },
        icon = { Icon(Icons.Filled.Warning, null, tint = accent) },
        title = {
            Text(
                stringResource(
                    if (creating) R.string.dsh_workflow_dialog_create_title else R.string.dsh_workflow_dialog_run_title,
                    request.workflowName,
                ),
                color = accent,
            )
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(
                        if (creating) R.string.dsh_workflow_dialog_create_body else R.string.dsh_workflow_dialog_run_body,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.dsh_workflow_dialog_reason, request.reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(request.summary, style = MaterialTheme.typography.bodySmall)
                if (definition != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.dsh_workflow_dialog_preview),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.height(6.dp))
                    WorkflowGraphPreview(definition)
                }
                if (request.dangerous) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = allowDangerous, onCheckedChange = { allowDangerous = it })
                        Text(
                            stringResource(R.string.dsh_workflow_dialog_allow_dangerous),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.dsh_workflow_dialog_timeout, (remaining / 1000).toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (remaining < 10_000) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onApprove(allowDangerous) }) {
                Text(
                    stringResource(
                        if (creating) R.string.dsh_workflow_action_save else R.string.dsh_workflow_action_run,
                    ),
                )
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.dsh_workflow_action_edit))
                }
                TextButton(onClick = { rejectOpen = true }) {
                    Text(stringResource(R.string.dsh_workflow_action_reject))
                }
            }
        },
    )

    if (rejectOpen) {
        AlertDialog(
            onDismissRequest = { rejectOpen = false },
            title = { Text(stringResource(R.string.dsh_workflow_reject_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.dsh_workflow_reject_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    rejectOpen = false
                    onReject(reason)
                }) { Text(stringResource(R.string.dsh_workflow_action_reject_send)) }
            },
            dismissButton = {
                TextButton(onClick = { rejectOpen = false }) {
                    Text(stringResource(R.string.dsh_workflow_action_cancel))
                }
            },
        )
    }
}

/**
 * 运行前的权限检查。
 *
 * 每次重组（用户从设置页回来时）重新算 [check] —— 所以「已经给好了」的项会立刻从清单里消失，
 * 而不是停在他离开时那份快照上。
 */
@Composable
private fun WorkflowPermissionDialog(
    request: WorkflowPermissions.Gate.Request,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    check: () -> List<WorkflowPermissions.Item>,
    onOpen: (WorkflowPermissions.Item) -> Unit,
) {
    val ctx = LocalContext.current
    var missing by remember(request.id) { mutableStateOf(check()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        missing = check()
    }
    LaunchedEffect(request.id) { missing = check() }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.dsh_workflow_perm_title, request.workflowName)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.dsh_workflow_perm_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                if (missing.isEmpty()) {
                    Text(
                        stringResource(R.string.dsh_workflow_perm_all_granted),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    for (item in missing) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(item.labelRes),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                if (item.kind == WorkflowPermissions.Kind.RUNTIME) {
                                    launcher.launch(item.permissions.toTypedArray())
                                } else {
                                    onOpen(item)
                                }
                            }) {
                                Text(stringResource(R.string.dsh_workflow_perm_grant))
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.dsh_workflow_perm_still_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.dsh_workflow_perm_recheck_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onStart, enabled = missing.isEmpty()) {
                Text(stringResource(R.string.dsh_workflow_action_start))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { missing = check() }) {
                    Text(stringResource(R.string.dsh_workflow_perm_recheck))
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.dsh_workflow_action_cancel))
                }
            }
        },
    )
}

/** 流程预览：按拓扑分层铺开，判断节点标出 true/false 两条出边。 */
@Composable
internal fun WorkflowGraphPreview(definition: WorkflowDefinition) {
    val layers = remember(definition) { graphLayers(definition) }
    Column(Modifier.fillMaxWidth()) {
        for ((depth, nodes) in layers.withIndex()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$depth",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(16.dp),
                )
                for (n in nodes) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = nodeColor(n.type).copy(alpha = 0.18f),
                        modifier = Modifier.padding(end = 6.dp),
                    ) {
                        Text(
                            n.name.ifBlank { nodeLabel(n) },
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            val edges = definition.edges.filter { e -> nodes.any { it.id == e.from } }
            if (edges.isNotEmpty()) {
                Text(
                    edges.joinToString("　") { e ->
                        val label = if (e.whenExpr.isBlank()) "" else "[${e.whenExpr}] "
                        "↓ $label${definition.node(e.to)?.name?.ifBlank { e.to } ?: e.to}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 4.dp),
                )
            }
        }
    }
}

/**
 * 编辑页（全屏层）：改节点、改参数、挪位置、加删连线。
 *
 * 位置是**画布上的真实坐标**（拖完写回 `posX/posY`），与预览一样落进定义里 —— 蓝图编辑器
 * 的摆位如果不存，用户摆好的图下次打开就自己跳了。
 */
@Composable
private fun WorkflowEditorOverlay(
    originalJson: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val ctx = LocalContext.current
    var draft by remember { mutableStateOf(definitionFromJson(originalJson)) }
    var selected by remember { mutableStateOf<String?>(null) }
    var linkFrom by remember { mutableStateOf<String?>(null) }
    var linkWhen by remember { mutableStateOf("") }
    val def = draft ?: return
    // 差异在组合期算成不可变列表：写 state 会造成重组循环。
    val patchLines: List<WorkflowPatch> =
        remember(def) { definitionFromJson(originalJson)?.let { workflowDiff(it, def) } ?: emptyList() }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text(stringResource(R.string.dsh_workflow_editor_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.dsh_workflow_editor_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .verticalScroll(rememberScrollState()),
            ) {
                Column {
                    for (node in def.nodes) {
                        var dx by remember(node.id) { mutableStateOf(node.posX.toFloat()) }
                        var dy by remember(node.id) { mutableStateOf(node.posY.toFloat()) }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = nodeColor(node.type).copy(alpha = 0.25f),
                            modifier = Modifier
                                .padding(start = dx.dp.coerceIn(0.dp, 220.dp), top = dy.dp.coerceIn(0.dp, 400.dp))
                                .clickable {
                                    val from = linkFrom
                                    if (from == null) {
                                        selected = if (selected == node.id) null else node.id
                                    } else if (from != node.id) {
                                        draft = def.copy(
                                            edges = def.edges + me.bmax.apatch.dsh.WorkflowEdge(from, node.id, linkWhen),
                                        )
                                        linkFrom = null
                                        linkWhen = ""
                                    }
                                }
                                .pointerInput(node.id) {
                                    detectDragGestures { change, amount ->
                                        change.consume()
                                        dx += amount.x
                                        dy += amount.y
                                    }
                                },
                        ) {
                            Text(
                                (if (selected == node.id) "▸ " else "") + node.name.ifBlank { nodeLabel(node) },
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.dsh_workflow_editor_drag_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    // 新增一个 shell 节点（最常用）；其余类型在下面的类型选择里
                    val id = "n" + (def.nodes.size + 1)
                    draft = def.copy(
                        nodes = def.nodes + WorkflowNode(
                            id = id,
                            type = WorkflowNodeType.EXECUTE,
                            name = id,
                            posX = 20 + def.nodes.size * 8,
                            posY = 20 + def.nodes.size * 30,
                            config = mapOf("action" to "shell", "cmd" to ""),
                        ),
                    )
                }) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.dsh_workflow_editor_add_node))
                }
            }
            val sel = selected?.let { def.node(it) }
            if (sel != null) {
                Spacer(Modifier.height(4.dp))
                NodeEditor(
                    node = sel,
                    canLink = linkFrom == null,
                    onLink = { linkFrom = sel.id },
                    onDelete = {
                        draft = def.copy(
                            nodes = def.nodes.filterNot { it.id == sel.id },
                            edges = def.edges.filterNot { e -> e.from == sel.id || e.to == sel.id },
                        )
                        selected = null
                    },
                    onChange = { updated ->
                        draft = def.copy(nodes = def.nodes.map { if (it.id == updated.id) updated else it })
                    },
                )
            }
            if (def.edges.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.dsh_workflow_editor_edges), style = MaterialTheme.typography.labelLarge)
                for (e in def.edges) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${e.from} → ${e.to}" + if (e.whenExpr.isBlank()) "" else " [${e.whenExpr}]",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Filled.Delete,
                            null,
                            Modifier.size(18.dp).clickable {
                                draft = def.copy(edges = def.edges.filterNot { it == e })
                            },
                        )
                    }
                }
            }
            if (patchLines.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.dsh_workflow_editor_diff), style = MaterialTheme.typography.labelLarge)
                Text(
                    patchLines.joinToString("\n") { it.humanize() },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.dsh_workflow_action_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSave(draft?.toJson()?.toString()) }) {
                    Text(stringResource(R.string.dsh_workflow_editor_save))
                }
            }
        }
    }
}

/** 单个节点的参数编辑：按类型只显示这个类型认识的键。 */
@Composable
private fun NodeEditor(
    node: WorkflowNode,
    canLink: Boolean,
    onLink: () -> Unit,
    onDelete: () -> Unit,
    onChange: (WorkflowNode) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${node.id} · ${node.type.id}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            if (canLink) {
                TextButton(onClick = onLink) { Text(stringResource(R.string.dsh_workflow_editor_link)) }
            }
            Icon(Icons.Filled.Delete, null, Modifier.size(18.dp).clickable { onDelete() })
        }
        OutlinedTextField(
            value = node.name,
            onValueChange = { onChange(node.copy(name = it)) },
            label = { Text(stringResource(R.string.dsh_workflow_editor_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        for (key in editableKeys(node)) {
            OutlinedTextField(
                value = node.config(key).orEmpty(),
                onValueChange = { v -> onChange(node.copy(config = node.config + (key to v))) },
                label = { Text(key) },
                singleLine = key != "cmd",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 这个类型允许编辑的 config 键（与 [WorkflowValidator] 认的键一致）。 */
private fun editableKeys(node: WorkflowNode): List<String> = when (node.type) {
    WorkflowNodeType.TRIGGER -> listOf("requireDsh", "autostartDsh")
    WorkflowNodeType.EXECUTE -> when (node.actionType?.id) {
        "notify" -> listOf("action", "title", "text")
        else -> listOf("action", "cmd")
    }
    WorkflowNodeType.CONDITION -> listOf("source", "op", "value")
    WorkflowNodeType.LOGIC -> listOf("op", "sources")
    WorkflowNodeType.EXTRACT -> listOf("source", "mode", "pattern", "path", "var")
    WorkflowNodeType.WAIT -> listOf("ms", "untilDsh")
}

private fun nodeLabel(node: WorkflowNode): String = when (node.type) {
    WorkflowNodeType.TRIGGER -> "trigger"
    WorkflowNodeType.EXECUTE -> node.actionType?.id ?: "execute"
    WorkflowNodeType.CONDITION -> "condition"
    WorkflowNodeType.LOGIC -> "logic"
    WorkflowNodeType.EXTRACT -> "extract"
    WorkflowNodeType.WAIT -> "wait"
}

private fun nodeColor(type: WorkflowNodeType): Color = when (type) {
    WorkflowNodeType.TRIGGER -> Color(0xFF3B82F6)
    WorkflowNodeType.EXECUTE -> Color(0xFF10B981)
    WorkflowNodeType.CONDITION, WorkflowNodeType.LOGIC -> Color(0xFFB58900)
    WorkflowNodeType.EXTRACT -> Color(0xFF8B5CF6)
    WorkflowNodeType.WAIT -> Color(0xFF6B7280)
}

/** 从 trigger 出发做 BFS，按深度分层（预览用；编辑器用的是落盘坐标）。 */
private fun graphLayers(definition: WorkflowDefinition): List<List<WorkflowNode>> {
    val entry = definition.trigger ?: return listOf(definition.nodes)
    val depth = mutableMapOf(entry.id to 0)
    val queue = ArrayDeque(listOf(entry.id))
    while (queue.isNotEmpty()) {
        val cur = queue.removeFirst()
        for (e in definition.outgoing(cur)) {
            val next = (depth[cur] ?: 0) + 1
            if ((depth[e.to] ?: Int.MAX_VALUE) > next) {
                depth[e.to] = next
                queue += e.to
            }
        }
    }
    val maxDepth = depth.values.maxOrNull() ?: 0
    return (0..maxDepth).map { d ->
        definition.nodes.filter { (depth[it.id] ?: 0) == d }
    }.filter { it.isNotEmpty() }
}
