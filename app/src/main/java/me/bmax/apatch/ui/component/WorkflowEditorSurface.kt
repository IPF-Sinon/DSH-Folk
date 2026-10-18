package me.bmax.apatch.ui.component

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshNativeBridge
import me.bmax.apatch.dsh.WorkflowActionType
import me.bmax.apatch.dsh.WorkflowDefinition
import me.bmax.apatch.dsh.WorkflowEdge
import me.bmax.apatch.dsh.WorkflowNode
import me.bmax.apatch.dsh.WorkflowNodeType
import me.bmax.apatch.dsh.WorkflowPatch
import me.bmax.apatch.dsh.WorkflowSchema
import me.bmax.apatch.dsh.WorkflowValidator
import me.bmax.apatch.dsh.workflowDiff

private data class EditorNodeKind(val type: WorkflowNodeType, val labelRes: Int, val defaults: Map<String, String>)

private val EDITOR_NODE_CATALOG = listOf(
    EditorNodeKind(WorkflowNodeType.EXECUTE, R.string.dsh_workflow_kind_shell, editorConfigForAction(WorkflowActionType.SHELL)),
    EditorNodeKind(WorkflowNodeType.EXECUTE, R.string.dsh_workflow_kind_dsh, editorConfigForAction(WorkflowActionType.DSH)),
    EditorNodeKind(WorkflowNodeType.EXECUTE, R.string.dsh_workflow_kind_notify, editorConfigForAction(WorkflowActionType.NOTIFY)),
    EditorNodeKind(WorkflowNodeType.CONDITION, R.string.dsh_workflow_kind_condition, mapOf("source" to "exitCode", "op" to "equals", "value" to "0")),
    EditorNodeKind(WorkflowNodeType.EXTRACT, R.string.dsh_workflow_kind_extract, mapOf("source" to "lastOutput", "mode" to "text", "var" to "value")),
    EditorNodeKind(WorkflowNodeType.WAIT, R.string.dsh_workflow_kind_wait, mapOf("ms" to "1000")),
    EditorNodeKind(WorkflowNodeType.LOGIC, R.string.dsh_workflow_kind_logic, mapOf("op" to "and", "sources" to "")),
)

private fun editorConfigForAction(action: WorkflowActionType): Map<String, String> = when (action) {
    WorkflowActionType.SHELL -> mapOf("action" to "shell", "cmd" to "echo hello")
    WorkflowActionType.DSH -> mapOf("action" to "dsh", "cmd" to "dsh --version")
    WorkflowActionType.NOTIFY -> mapOf("action" to "notify", "text" to "流程完成")
}

private fun editorNodeKeyOf(node: WorkflowNode): String = when (node.type) {
    WorkflowNodeType.EXECUTE -> if (node.actionType == WorkflowActionType.NOTIFY) "text" else "cmd"
    WorkflowNodeType.CONDITION -> "value"
    WorkflowNodeType.EXTRACT -> "var"
    WorkflowNodeType.WAIT -> "ms"
    WorkflowNodeType.LOGIC -> "sources"
    WorkflowNodeType.TRIGGER -> ""
}

/**
 * 工作流编辑页的**内容本体**（页面与审批弹窗共用）。
 *
 * 布局按点击助手：顶栏（状态栏留白 + 返回/标题/保存）→ 画布（吃掉剩余高度）→ 底部一条小工具条。
 * 节点目录收进「＋ 节点」拉起的抽屉里：抽屉里的卡片可以**往外拖**（向上拖出抽屉 = 在画布上新建
 * 这个节点），也可以直接点一下在画布中央新建。
 *
 * 手势分工（这是「卡片拖不动」的根因所在）：
 *  - 画布：单指按在卡片上 → 拖这张卡；按在空白 → 平移；从右侧输出口拖出 → 松手弹「接哪个节点」。
 *  - 卡片与抽屉里的条目：不放任何拖动手势，只保留「点」。
 */
@Composable
internal fun WorkflowEditorSurface(
    initial: WorkflowDefinition,
    title: String,
    onSave: (WorkflowDefinition) -> Unit,
    onCancel: () -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    val def = draft
    var selected by remember { mutableStateOf<String?>(null) }
    var addAfter by remember { mutableStateOf<String?>(null) }
    var sheetOpen by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val patchLines: List<WorkflowPatch> = remember(def) { workflowDiff(initial, def) }
    // 系统返回与「取消」同一条路：先问要不要放弃改动。
    androidx.activity.compose.BackHandler {
        if (patchLines.isEmpty()) onCancel() else confirmDiscard = true
    }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val errors: List<String> = remember(def) {
        WorkflowValidator.validate(def, DshNativeBridge.Cap.entries.map { it.id }.toSet()).errors
    }

    fun addNode(kind: EditorNodeKind, afterId: String?) {
        val id = "n" + (def.nodes.size + 1) + "_" + (System.currentTimeMillis() % 1000)
        val index = def.nodes.size
        val node = WorkflowNode(
            id = id,
            type = kind.type,
            name = "",
            posX = 24 + (index % 3) * (WorkflowNodeCardWidth.value.toInt() + 16),
            posY = 24 + (index / 3) * 96,
            config = kind.defaults,
        )
        draft = def.copy(
            nodes = def.nodes + node,
            edges = if (afterId != null) def.edges + WorkflowEdge(afterId, id, "") else def.edges,
        )
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // ── 顶栏：真正占一行，画布不会和它重叠 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = RoundedCornerShape(50), tonalElevation = 5.dp) {
                    IconButton(onClick = {
                        if (patchLines.isEmpty()) onCancel() else confirmDiscard = true
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.dsh_workflow_action_cancel),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Surface(shape = RoundedCornerShape(50), tonalElevation = 5.dp) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { onSave(def) }, shape = RoundedCornerShape(50)) {
                    Text(stringResource(R.string.dsh_workflow_editor_save))
                }
            }

            // ── 画布 ──
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val placed = layoutFor(def)
                WorkflowBlueprintCanvas(
                    definition = def,
                    positions = placed.associate { it.first to Offset(it.second, it.third) },
                    readOnly = false,
                    selectedNodeId = selected,
                    onNodeClick = { id -> selected = if (selected == id) null else id },
                    // 像素 → dp → 吸附 12dp 网格，全部走 WorkflowGeometry；这里不再裸除密度，
                    // 也不再用 Int 截断（那正是「拖动有点快/回弹」的来源）。
                    onNodeDrag = { id, dxPx, dyPx ->
                        val node = def.nodes.firstOrNull { it.id == id }
                        if (node != null) {
                            val nx = WorkflowGeometry.snapToGridDp(node.posX + WorkflowGeometry.pxToDp(dxPx, density))
                                .coerceIn(0, 2000)
                            val ny = WorkflowGeometry.snapToGridDp(node.posY + WorkflowGeometry.pxToDp(dyPx, density))
                                .coerceIn(0, 2000)
                            draft = def.copy(nodes = def.nodes.map { if (it.id == id) it.copy(posX = nx, posY = ny) else it })
                        }
                    },
                    onWireConnect = { fromId, toId ->
                        if (def.edges.none { it.from == fromId && it.to == toId }) {
                            draft = def.copy(edges = def.edges + WorkflowEdge(fromId, toId, ""))
                        }
                    },
                    onNodeAddAfter = { id -> addAfter = id },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // ── 底部小工具条：默认只有这一行 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { sheetOpen = true }, shape = RoundedCornerShape(50)) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.dsh_workflow_editor_add_node))
                }
                OutlinedButton(
                    onClick = { draft = def.copy(nodes = def.nodes.map { it.copy(posX = 0, posY = 0) }) },
                    shape = RoundedCornerShape(50),
                ) { Text(stringResource(R.string.dsh_workflow_editor_auto_layout)) }
                Spacer(Modifier.weight(1f))
                if (errors.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.dsh_workflow_editor_errors) + " " + errors.size,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.dsh_workflow_editor_edges) + " " + def.edges.size,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── 节点抽屉：往上拖可以「拖出」一个节点；点一下在画布中央新建 ──
        if (sheetOpen) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    tonalElevation = 6.dp,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.dsh_workflow_editor_add_node),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { sheetOpen = false }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.dsh_workflow_action_cancel))
                            }
                        }
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            for (kind in EDITOR_NODE_CATALOG) {
                                SheetNodeRow(
                                    label = stringResource(kind.labelRes),
                                    onTap = {
                                        addNode(kind, null)
                                        sheetOpen = false
                                    },
                                    onDragOut = {
                                        addNode(kind, null)
                                        sheetOpen = false
                                    },
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }

    // ── 点卡片 → 修改窗 ──
    selected?.let { sid ->
        val sel = def.node(sid)
        if (sel != null) {
            AlertDialog(
                onDismissRequest = { selected = null },
                title = { Text(sel.name.ifBlank { sel.id }) },
                text = {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        NodeParamEditor(
                            node = sel,
                            onChange = { updated ->
                                draft = def.copy(nodes = def.nodes.map { if (it.id == updated.id) updated else it })
                            },
                        )
                        if (def.edges.any { it.from == sel.id || it.to == sel.id }) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.dsh_workflow_editor_edges),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            for (e in def.edges.filter { it.from == sel.id || it.to == sel.id }) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${e.from} → ${e.to}" + (if (e.whenExpr.isBlank()) "" else " (${e.whenExpr})"),
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = {
                                        draft = def.copy(edges = def.edges.filterNot { it == e })
                                    }) { Text(stringResource(R.string.dsh_workflow_action_cancel)) }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { selected = null }) {
                        Text(stringResource(R.string.dsh_workflow_editor_save))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        draft = def.copy(
                            nodes = def.nodes.filterNot { it.id == sel.id },
                            edges = def.edges.filterNot { e -> e.from == sel.id || e.to == sel.id },
                        )
                        selected = null
                    }) { Text(stringResource(R.string.dsh_workflow_editor_delete_node)) }
                },
            )
        }
    }

    // ── 从输出口拉出：选一个类型，建节点并连上 ──
    addAfter?.let { after ->
        AlertDialog(
            onDismissRequest = { addAfter = null },
            title = { Text(stringResource(R.string.dsh_workflow_editor_add_node)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    for (kind in EDITOR_NODE_CATALOG) {
                        TextButton(onClick = { addNode(kind, after); addAfter = null }) {
                            Text(stringResource(kind.labelRes))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { addAfter = null }) {
                    Text(stringResource(R.string.dsh_workflow_action_cancel))
                }
            },
        )
    }

    // ── 放弃改动确认 ──
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.dsh_workflow_editor_discard_title)) },
            text = {
                Text(
                    stringResource(R.string.dsh_workflow_editor_discard_body, patchLines.size),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onCancel() }) {
                    Text(stringResource(R.string.dsh_workflow_editor_discard_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text(stringResource(R.string.dsh_workflow_editor_keep_editing))
                }
            },
        )
    }
}

/**
 * 抽屉里的一行：点一下在画布中央新建；**向上拖出抽屉**同样新建（拖出 = 触摸助手那种「拖一张卡
 * 到画布上」的手感）。这里不放画布坐标 —— 拖出后落到默认位置，用户再拖到位即可。
 */
@Composable
private fun SheetNodeRow(label: String, onTap: () -> Unit, onDragOut: () -> Unit) {
    var dragged by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(label) {
                var total = 0f
                detectDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total < -32f) onDragOut() else if (!dragged) onTap(); dragged = false },
                    onDragCancel = { dragged = false },
                ) { change, amount ->
                    change.consume()
                    dragged = true
                    total += amount.y
                }
            },
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.dsh_workflow_editor_drag_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** 节点参数编辑（点卡片弹出的窗里用）：按类型只显示这个类型认识的键。 */
@Composable
private fun NodeParamEditor(node: WorkflowNode, onChange: (WorkflowNode) -> Unit) {
    val key = editorNodeKeyOf(node)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            node.type.id + (node.actionType?.let { " · " + it.id } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (node.type == WorkflowNodeType.EXECUTE) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (action in WorkflowActionType.entries) {
                    val picked = node.actionType == action
                    if (picked) {
                        Button(onClick = {}, shape = RoundedCornerShape(50)) { Text(action.id) }
                    } else {
                        OutlinedButton(
                            onClick = {
                                onChange(node.copy(config = editorConfigForAction(action) + mapOf("action" to action.id)))
                            },
                            shape = RoundedCornerShape(50),
                        ) { Text(action.id) }
                    }
                }
            }
        }
        if (key.isNotEmpty()) {
            OutlinedTextField(
                value = node.config(key),
                onValueChange = { v -> onChange(node.copy(config = node.config + (key to v))) },
                label = { Text(key) },
                singleLine = node.type != WorkflowNodeType.CONDITION,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
        }
        if (node.type == WorkflowNodeType.CONDITION) {
            OutlinedTextField(
                value = node.config("op"),
                onValueChange = { v -> onChange(node.copy(config = node.config + ("op" to v))) },
                label = { Text("op") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
        }
    }
}

/** 摆位：有落盘坐标就用，全是 0 就按拓扑分层。与预览共用同一条规则（放在这里避免两套）。 */
private fun layoutFor(def: WorkflowDefinition): List<Triple<String, Float, Float>> {
    val anyPlaced = def.nodes.any { it.posX != 0 || it.posY != 0 }
    if (anyPlaced) return def.nodes.map { Triple(it.id, it.posX.toFloat(), it.posY.toFloat()) }
    // 分层：从入口节点按出边 BFS 算深度。只是「没摆过」时的初始排布，不参与执行。
    val trigger = def.trigger?.id ?: def.nodes.firstOrNull()?.id ?: return emptyList()
    val depth = mutableMapOf(trigger to 0)
    val queue = ArrayDeque(listOf(trigger))
    while (queue.isNotEmpty()) {
        val cur = queue.removeFirst()
        val d = depth[cur] ?: 0
        for (e in def.outgoing(cur)) {
            if (!depth.containsKey(e.to)) {
                depth[e.to] = d + 1
                queue.add(e.to)
            }
        }
    }
    return def.nodes.mapIndexed { i, node ->
        Triple(node.id, 24f + (i % 3) * (WorkflowNodeCardWidth.value + 16f), 24f + (depth[node.id] ?: 0) * 128f)
    }
}
