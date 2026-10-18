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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshNativeBridge
import me.bmax.apatch.dsh.WorkflowActionType
import me.bmax.apatch.dsh.WorkflowDefinition
import me.bmax.apatch.dsh.WorkflowEdge
import me.bmax.apatch.dsh.WorkflowNode
import me.bmax.apatch.dsh.WorkflowNodeType
import me.bmax.apatch.dsh.WorkflowPatch
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
 * 结构（对齐点击助手）：顶栏（状态栏留白 + 返回/标题/保存）→ 画布（吃满剩余高度）→ 底部小工具条。
 * 节点目录收进「＋ 节点」拉起的抽屉里：抽屉里的卡片可以**往外拖**（拖到画布上，松手即落点）。
 *
 * 手势（关键）：卡片自己接点/拖/拉线，画布只接空白处平移 —— 没有手算命中，也就没有坐标系比错的
 * 空间；抽屉拖出用**窗口坐标**判定落点。拖动开始时抽屉**视觉收起**（保持组合，手势才不断）。
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
    // 抽屉拖出：幽灵卡（null = 没在拖）、根容器与画布在窗口里的位置。
    var ghost by remember { mutableStateOf<Pair<String, Offset>?>(null) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var canvasOrigin by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density

    val patchLines: List<WorkflowPatch> = remember(def) { workflowDiff(initial, def) }
    val errors: List<String> = remember(def) {
        WorkflowValidator.validate(def, DshNativeBridge.Cap.entries.map { it.id }.toSet()).errors
    }
    // 系统返回与「取消」同一条路：先问要不要放弃改动。
    androidx.activity.compose.BackHandler {
        if (patchLines.isEmpty()) onCancel() else confirmDiscard = true
    }

    /** 在指定 dp 位置新建节点（抽屉拖出用：落点 = 松手位置，已吸附 12dp 网格）。 */
    fun addNodeAt(kind: EditorNodeKind, xDp: Int, yDp: Int) {
        val id = "n" + (def.nodes.size + 1) + "_" + (System.currentTimeMillis() % 1000)
        draft = def.copy(
            nodes = def.nodes + WorkflowNode(
                id = id,
                type = kind.type,
                name = "",
                posX = xDp,
                posY = yDp,
                config = kind.defaults,
            ),
        )
    }

    /** 落在默认网格位新建；afterId 非空时顺手连一条出边。 */
    fun addNode(kind: EditorNodeKind, afterId: String?) {
        val index = def.nodes.size
        val x = 24 + (index % 3) * 232
        val y = 24 + (index / 3) * 128
        val id = "n" + (index + 1) + "_" + (System.currentTimeMillis() % 1000)
        val node = WorkflowNode(id = id, type = kind.type, name = "", posX = x, posY = y, config = kind.defaults)
        draft = def.copy(
            nodes = def.nodes + node,
            edges = if (afterId != null) def.edges + WorkflowEdge(afterId, id, "") else def.edges,
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootOrigin = it.positionInWindow() },
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                // ── 顶栏（真正占一行，画布不会与它重叠）──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(shape = CircleShape, tonalElevation = 5.dp) {
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
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { onSave(def) }, shape = RoundedCornerShape(50)) {
                        Text(stringResource(R.string.dsh_workflow_editor_save))
                    }
                }

                // ── 画布 ──
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onGloballyPositioned {
                            canvasOrigin = it.positionInWindow()
                            canvasSize = it.size
                        },
                ) {
                    WorkflowBlueprintCanvas(
                        definition = def,
                        positions = editorLayoutFor(def).associate { it.first to Offset(it.second, it.third) },
                        readOnly = false,
                        selectedNodeId = selected,
                        onNodeClick = { id -> selected = if (selected == id) null else id },
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
                        onWireDrop = { fromId, toId ->
                            if (toId != null) {
                                if (def.edges.none { it.from == fromId && it.to == toId }) {
                                    draft = def.copy(edges = def.edges + WorkflowEdge(fromId, toId, ""))
                                }
                            } else {
                                addAfter = fromId
                            }
                        },
                        onBlankTap = { selected = null },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // ── 底部小工具条 ──
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

            // ── 幽灵卡：拖出时跟着手指，一眼看出松手会落在哪 ──
            ghost?.let { (label, pos) ->
                Surface(
                    shape = RoundedCornerShape(50),
                    tonalElevation = 8.dp,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.offset {
                        androidx.compose.ui.unit.IntOffset(
                            (pos.x - rootOrigin.x - 40 * density).toInt(),
                            (pos.y - rootOrigin.y - 20 * density).toInt(),
                        )
                    },
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            // ── 节点抽屉：往外拖到画布 = 在那个位置新建；点一下 = 画布中央新建 ──
            if (sheetOpen) {
                // 拖动中把抽屉与遮罩**视觉收起**（保持组合：移除会让拖动的手势源消失）。
                if (ghost == null) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)),
                    )
                }
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .alpha(if (ghost != null) 0f else 1f),
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    tonalElevation = 6.dp,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.dsh_workflow_editor_add_node),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { sheetOpen = false }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.dsh_workflow_action_cancel),
                                )
                            }
                        }
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            for (chunk in EDITOR_NODE_CATALOG.chunked(2)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    for (kind in chunk) {
                                        val kindLabel = stringResource(kind.labelRes)
                                        SheetNodeCard(
                                            label = kindLabel,
                                            subtitle = kind.type.id,
                                            accent = workflowNodeAccent(kind.type),
                                            modifier = Modifier.weight(1f),
                                            onTap = {
                                                addNode(kind, null)
                                                sheetOpen = false
                                            },
                                            onDragStart = { windowPos -> ghost = kindLabel to windowPos },
                                            onDragMove = { windowPos -> ghost = kindLabel to windowPos },
                                            onDragEnd = { windowPos ->
                                                ghost = null
                                                val inCanvas = windowPos.x >= canvasOrigin.x &&
                                                    windowPos.x <= canvasOrigin.x + canvasSize.width &&
                                                    windowPos.y >= canvasOrigin.y &&
                                                    windowPos.y <= canvasOrigin.y + canvasSize.height
                                                if (inCanvas) {
                                                    val xDp = WorkflowGeometry.snapToGridDp(
                                                        WorkflowGeometry.pxToDp(windowPos.x - canvasOrigin.x, density),
                                                    ).coerceAtLeast(0)
                                                    val yDp = WorkflowGeometry.snapToGridDp(
                                                        WorkflowGeometry.pxToDp(windowPos.y - canvasOrigin.y, density),
                                                    ).coerceAtLeast(0)
                                                    addNodeAt(kind, xDp, yDp)
                                                }
                                                sheetOpen = false
                                            },
                                        )
                                    }
                                    if (chunk.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                            Spacer(Modifier.height(10.dp))
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
                        val related = def.edges.filter { it.from == sel.id || it.to == sel.id }
                        if (related.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.dsh_workflow_editor_edges),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            for (e in related) {
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

    // ── 从输出口拉出后落在空白：选一个类型，建节点并连上 ──
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
 * 抽屉里的一张类型卡：色点 + 名称 + 类型；按住往外拖 = 拖到画布上新建，点一下 = 画布中央新建。
 *
 * 拖动事件的坐标是**本卡局部坐标**，加上本卡的窗口位置才是窗口坐标 —— 落点判定统一在窗口坐标里做。
 */
@Composable
private fun SheetNodeCard(
    label: String,
    subtitle: String,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: (Offset) -> Unit,
) {
    var dragged by remember { mutableStateOf(false) }
    var rowOrigin by remember { mutableStateOf(Offset.Zero) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = modifier
            .onGloballyPositioned { rowOrigin = it.positionInWindow() }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { start ->
                        dragged = false
                        pointer = rowOrigin + start
                        onDragStart(pointer)
                    },
                    onDragEnd = {
                        if (dragged) onDragEnd(pointer) else onTap()
                        dragged = false
                    },
                    onDragCancel = { dragged = false },
                ) { change, amount ->
                    change.consume()
                    dragged = true
                    pointer += amount
                    onDragMove(pointer)
                }
            },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).background(accent, CircleShape))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
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
                    if (node.actionType == action) {
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

/** 摆位：有落盘坐标就用，全是 0 就按拓扑分层（BFS 深度）。与预览共用同一套规则。 */
private fun editorLayoutFor(def: WorkflowDefinition): List<Triple<String, Float, Float>> {
    val anyPlaced = def.nodes.any { it.posX != 0 || it.posY != 0 }
    if (anyPlaced) return def.nodes.map { Triple(it.id, it.posX.toFloat(), it.posY.toFloat()) }
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
