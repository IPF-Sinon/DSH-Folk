package me.bmax.apatch.ui.component

import android.widget.Toast

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
import androidx.compose.ui.platform.LocalContext
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
import me.bmax.apatch.dsh.NativeCapCatalog
import me.bmax.apatch.dsh.WorkflowActionType
import me.bmax.apatch.dsh.WorkflowDefinition
import me.bmax.apatch.dsh.WorkflowEdge
import me.bmax.apatch.dsh.WorkflowNode
import me.bmax.apatch.dsh.WorkflowNodeType
import me.bmax.apatch.dsh.WorkflowPatch
import me.bmax.apatch.dsh.WorkflowValidator
import me.bmax.apatch.dsh.workflowDiff

private data class EditorNodeKind(
    val type: WorkflowNodeType,
    val labelRes: Int,
    val defaults: Map<String, String>,
    /**
     * 这个节点来自「原生能力目录」的哪一项（非空时它带命令骨架，见 [NativeCapCatalog]）。
     *
     * 为什么要在节点类型里带上它：抽屉里既要显示能力名（「通知」「剪贴板」…），
     * 又要让新建出来的节点**直接带上该能力的命令**，而不是先建一个空 shell 节点再手抄命令。
     * 存 CapItem 而不是把它拆成几个字段，是为了「点选 → 生成」之间不丢参数提示。
     */
    val cap: NativeCapCatalog.CapItem? = null,
)

private val EDITOR_NODE_CATALOG = listOf(
    EditorNodeKind(WorkflowNodeType.EXECUTE, R.string.dsh_workflow_kind_shell, editorConfigForAction(WorkflowActionType.SHELL)),
    EditorNodeKind(WorkflowNodeType.EXECUTE, R.string.dsh_workflow_kind_dsh, editorConfigForAction(WorkflowActionType.DSH)),
    EditorNodeKind(WorkflowNodeType.EXECUTE, R.string.dsh_workflow_kind_notify, editorConfigForAction(WorkflowActionType.NOTIFY)),
    EditorNodeKind(WorkflowNodeType.CONDITION, R.string.dsh_workflow_kind_condition, mapOf("source" to "exitCode", "op" to "equals", "value" to "0")),
    EditorNodeKind(WorkflowNodeType.EXTRACT, R.string.dsh_workflow_kind_extract, mapOf("source" to "lastOutput", "mode" to "text", "var" to "value")),
    EditorNodeKind(WorkflowNodeType.WAIT, R.string.dsh_workflow_kind_wait, mapOf("ms" to "1000")),
    EditorNodeKind(WorkflowNodeType.LOGIC, R.string.dsh_workflow_kind_logic, mapOf("op" to "and", "sources" to "")),
)

/**
 * 抽屉的一级分类：用户先选「大类」，再选具体节点。
 *
 * ## 为什么改成两级
 * 之前是一页平铺：7 个基础节点 + 23 个原生能力，每行两张卡，全部堆在同一个 Sheet 里。
 * 三十张卡在手机上必然超出屏幕，只能靠 Sheet 撑满全屏来容纳 —— 打开抽屉就像跳进另一个页面，
 * 而且没有层次：用户根本看不出「等待」和「发短信」不是一类东西。
 *
 * 二级结构是这类节点编辑器的通行做法（点击助手的动作库同样按类别收拢）：
 * 一级只显示九个分类（每个带一句用途说明），点进去才看该类的节点。
 *
 * ## 分类按「用户想干什么」切，不按代码里的枚举切
 * 基础节点在代码里是 EXECUTE / CONDITION / WAIT …，但用户找的是「让它跑一条命令」
 * 还是「让它等一下」。所以「执行」与「控制流」分开，前者是"做什么"，
 * 后者是"什么时候做、做几次"。
 */
private data class EditorCategory(
    val titleRes: Int,
    val hintRes: Int,
    val accent: WorkflowNodeType,
    val kinds: () -> List<EditorNodeKind>,
)

private val EDITOR_CATEGORIES: List<EditorCategory> = listOf(
    EditorCategory(
        titleRes = R.string.dsh_wf_cat_exec,
        hintRes = R.string.dsh_wf_cat_exec_hint,
        accent = WorkflowNodeType.EXECUTE,
        kinds = { EDITOR_NODE_CATALOG.filter { it.type == WorkflowNodeType.EXECUTE } },
    ),
    EditorCategory(
        titleRes = R.string.dsh_wf_cat_control,
        hintRes = R.string.dsh_wf_cat_control_hint,
        accent = WorkflowNodeType.CONDITION,
        kinds = {
            EDITOR_NODE_CATALOG.filter {
                it.type == WorkflowNodeType.CONDITION ||
                    it.type == WorkflowNodeType.WAIT ||
                    it.type == WorkflowNodeType.LOGIC ||
                    it.type == WorkflowNodeType.EXTRACT
            }
        },
    ),
    EditorCategory(
        titleRes = R.string.dsh_wf_cat_alert,
        hintRes = R.string.dsh_wf_cat_alert_hint,
        accent = WorkflowNodeType.EXECUTE,
        kinds = { capKindsOf(NativeCapCatalog.Group.ALERT) },
    ),
    EditorCategory(
        titleRes = R.string.dsh_wf_cat_device,
        hintRes = R.string.dsh_wf_cat_device_hint,
        accent = WorkflowNodeType.EXECUTE,
        kinds = { capKindsOf(NativeCapCatalog.Group.DEVICE) },
    ),
    EditorCategory(
        titleRes = R.string.dsh_wf_cat_media,
        hintRes = R.string.dsh_wf_cat_media_hint,
        accent = WorkflowNodeType.EXECUTE,
        kinds = { capKindsOf(NativeCapCatalog.Group.MEDIA) },
    ),
    EditorCategory(
        titleRes = R.string.dsh_wf_cat_data,
        hintRes = R.string.dsh_wf_cat_data_hint,
        accent = WorkflowNodeType.EXECUTE,
        kinds = { capKindsOf(NativeCapCatalog.Group.DATA) },
    ),
    EditorCategory(
        titleRes = R.string.dsh_wf_cat_sense,
        hintRes = R.string.dsh_wf_cat_sense_hint,
        accent = WorkflowNodeType.EXECUTE,
        kinds = { capKindsOf(NativeCapCatalog.Group.SENSE) },
    ),
    EditorCategory(
        titleRes = R.string.dsh_wf_cat_screen,
        hintRes = R.string.dsh_wf_cat_screen_hint,
        accent = WorkflowNodeType.EXECUTE,
        kinds = { capKindsOf(NativeCapCatalog.Group.SCREEN) },
    ),
    EditorCategory(
        titleRes = R.string.dsh_wf_cat_privilege,
        hintRes = R.string.dsh_wf_cat_privilege_hint,
        accent = WorkflowNodeType.CONDITION,
        kinds = { capKindsOf(NativeCapCatalog.Group.PRIVILEGE) },
    ),
)

/** 取某个能力分组对应的编辑器节点（目录里没有的跳过，不让它出现在 UI 上）。 */
private fun capKindsOf(group: NativeCapCatalog.Group): List<EditorNodeKind> =
    NativeCapCatalog.byGroup()
        .firstOrNull { it.first == group }
        ?.second
        ?.mapNotNull { cap -> EDITOR_CAP_NODE_CATALOG.firstOrNull { it.cap == cap } }
        .orEmpty()

/**
 * 原生能力节点：一项能力一张卡片，点选即生成一个已带命令的 shell 节点。
 *
 * 命令用 [NativeCapCatalog.CapItem.command] 的骨架（不含 --reason，那是执行时补的）。
 * 这些节点与上面的「基础节点」分开列，是为了不在抽屉里把 7 个基础 + 23 个能力混成一片。
 */
private val EDITOR_CAP_NODE_CATALOG: List<EditorNodeKind> =
    NativeCapCatalog.ITEMS.map { cap ->
        EditorNodeKind(
            type = WorkflowNodeType.EXECUTE,
            labelRes = cap.titleRes,
            defaults = mapOf("action" to "shell", "cmd" to cap.command),
            cap = cap,
        )
    }

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
    // 打开时的**基准**：模型里坐标全是 0（AI 生成 / 新建流程）时，先把自动布局算进模型 ——
    // 否则「显示的位置」来自自动布局、「模型的位置」还是 0，拖动一上手就会跳。
    val baseline = remember(initial) {
        if (initial.nodes.isNotEmpty() && initial.nodes.all { it.posX == 0 && it.posY == 0 }) {
            val laid = editorLayoutFor(initial)
            initial.copy(
                nodes = initial.nodes.map { n ->
                    val p = laid.firstOrNull { it.first == n.id }
                    if (p != null) n.copy(posX = p.second.toInt(), posY = p.third.toInt()) else n
                },
            )
        } else {
            initial
        }
    }
    var draft by remember { mutableStateOf(baseline) }
    val def = draft
    var selected by remember { mutableStateOf<String?>(null) }
    var addAfter by remember { mutableStateOf<String?>(null) }
    // 拉线落在空白时的**松手窗口坐标**：菜单里选完类型，新节点就落在这里（不是默认网格位）。
    var addAfterAt by remember { mutableStateOf(Offset.Zero) }
    var sheetOpen by remember { mutableStateOf(false) }
    // 抽屉当前停在一级分类（null = 显示分类列表）。用 saveable 没必要：
    // 旋转后回到分类首页比「停在一个半开的二级页」更符合预期。
    var sheetCategory by remember { mutableStateOf<EditorCategory?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    // 抽屉拖出：幽灵卡（null = 没在拖）、根容器与画布在窗口里的位置。
    var ghost by remember { mutableStateOf<Pair<String, Offset>?>(null) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var canvasOrigin by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // 拖动卡片：起点 + **未吸附**的累加位移（每帧吸附会让小位移被吞掉 → 原地颤抖），
    // 松手时才吸附网格落盘。
    var dragFrom by remember { mutableStateOf<Triple<String, Float, Float>?>(null) }
    var dragAcc by remember { mutableStateOf(Offset.Zero) }
    var dragPreview by remember { mutableStateOf<Pair<String, Offset>?>(null) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    // 连边被拒（成环）时给一次即时反馈：编辑期就要说，不能等到保存才报。
    val context = LocalContext.current

    val patchLines: List<WorkflowPatch> = remember(def) { workflowDiff(baseline, def) }
    val errors: List<String> = remember(def) {
        WorkflowValidator.validate(def, DshNativeBridge.Cap.entries.map { it.id }.toSet()).errors
    }
    // 系统返回与「取消」同一条路：先问要不要放弃改动。
    androidx.activity.compose.BackHandler {
        if (patchLines.isEmpty()) onCancel() else confirmDiscard = true
    }

    /**
     * 在指定 dp 位置新建节点（落点 = 松手位置，已吸附 12dp 网格）。
     * afterId 非空时顺手连一条出边（从针脚拉出来、却落在空白的情况）。
     */
    fun addNodeAt(kind: EditorNodeKind, xDp: Int, yDp: Int, afterId: String? = null) {
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
            edges = if (afterId != null) def.edges + WorkflowEdge(afterId, id, "") else def.edges,
        )
    }

    /** 窗口坐标 → 画布 dp（吸附 12dp 网格）。拉线/抽屉拖出的落点都走这里。 */
    fun dropPointDp(pointerWindow: Offset): Pair<Int, Int> = Pair(
        WorkflowGeometry.snapToGridDp(
            WorkflowGeometry.pxToDp(pointerWindow.x - canvasOrigin.x, density),
        ).coerceAtLeast(0),
        WorkflowGeometry.snapToGridDp(
            WorkflowGeometry.pxToDp(pointerWindow.y - canvasOrigin.y, density),
        ).coerceAtLeast(0),
    )

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
                    val placed = editorLayoutFor(def)
                        .associate { it.first to Offset(it.second, it.third) }
                        .toMutableMap()
                        .apply { dragPreview?.let { (id, p) -> this[id] = p } }
                    WorkflowBlueprintCanvas(
                        definition = def,
                        positions = placed,
                        readOnly = false,
                        selectedNodeId = selected,
                        onNodeClick = { id -> selected = if (selected == id) null else id },
                        onNodeDragStart = { id ->
                            val node = def.nodes.firstOrNull { it.id == id }
                            if (node != null) {
                                dragFrom = Triple(id, node.posX.toFloat(), node.posY.toFloat())
                                dragAcc = Offset.Zero
                                dragPreview = id to Offset(node.posX.toFloat(), node.posY.toFloat())
                            }
                        },
                        onNodeDrag = { id, dxPx, dyPx ->
                            val from = dragFrom
                            if (from != null && from.first == id) {
                                // 累加**未吸附**位移：拖动过程中 1:1 跟手，松手时再吸附。
                                dragAcc += Offset(
                                    WorkflowGeometry.pxToDp(dxPx, density),
                                    WorkflowGeometry.pxToDp(dyPx, density),
                                )
                                dragPreview = id to Offset(
                                    (from.second + dragAcc.x).coerceIn(0f, 2000f),
                                    (from.third + dragAcc.y).coerceIn(0f, 2000f),
                                )
                            }
                        },
                        onNodeDragEnd = { id ->
                            val from = dragFrom
                            val preview = dragPreview
                            if (from != null && preview != null && from.first == id) {
                                val nx = WorkflowGeometry.snapToGridDp(preview.second.x).coerceIn(0, 2000)
                                val ny = WorkflowGeometry.snapToGridDp(preview.second.y).coerceIn(0, 2000)
                                draft = def.copy(
                                    nodes = def.nodes.map { if (it.id == id) it.copy(posX = nx, posY = ny) else it },
                                )
                            }
                            dragFrom = null
                            dragAcc = Offset.Zero
                            dragPreview = null
                        },
                        onWireDrop = { fromId, toId, pointer ->
                            if (toId != null) {
                                // 成环的边**不落盘**：环一旦进入定义，执行器的拓扑调度会死循环。
                                // 判据只有 WorkflowValidator.addEdgeIfAcyclic 一处 —— 连边入口若
                                // 以后增加，也必须走它，否则环会从新入口漏进来。
                                val next = WorkflowValidator.addEdgeIfAcyclic(def, fromId, toId)
                                if (next === def) {
                                    // 返回同一个实例 = 被拒（成环 / 自环 / 重复边）
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.dsh_workflow_cycle_rejected),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    draft = next
                                }
                            } else {
                                // 落在空白：记住松手坐标，菜单选完类型后按它建节点。
                                addAfterAt = pointer
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
                    // 胶囊左上角 = 手指位置 = 松手后节点的左上角：三者对齐，落点才不会看起来「偏右下」。
                    modifier = Modifier.offset {
                        androidx.compose.ui.unit.IntOffset(
                            (pos.x - rootOrigin.x).toInt(),
                            (pos.y - rootOrigin.y).toInt(),
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
                        // 头部：一级页显示标题，二级页显示「返回 + 分类名」。
                        //
                        // 用同一个头部而不是两个 Composable：返回键的位置必须稳定，
                        // 否则用户点了一次分类之后，关闭按钮会跳到别处。
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val cat = sheetCategory
                            if (cat != null) {
                                IconButton(onClick = { sheetCategory = null }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = stringResource(R.string.dsh_workflow_action_cancel),
                                    )
                                }
                            }
                            Text(
                                if (cat == null) stringResource(R.string.dsh_workflow_editor_add_node)
                                else stringResource(cat.titleRes),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = {
                                sheetOpen = false
                                sheetCategory = null
                            }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.dsh_workflow_action_cancel),
                                )
                            }
                        }
                        // 内容区**故意不是滚动容器**。
                        //
                        // 抽屉的核心交互是从卡片往外拖到画布上。一旦内容区可以纵向滚动，
                        // 手指按住卡片往下拖时，滚动容器会先把手势吃掉 —— 用户以为在拖节点，
                        // 实际在滚列表。这是 check-workflow 里专门有一条断言拦着的事
                        // （#16j「抽屉的列表不能是滚动容器」）。
                        //
                        // 所以「不堆在一个页面」不能靠在抽屉里加滚动解决，只能靠**分级**：
                        // 一级最多 9 个分类、二级最多 4 个节点，两级都一屏放得下，根本不需要滚。
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val cat = sheetCategory
                            if (cat == null) {
                                // ── 一级：分类网格 ──
                                // 两列排布而不是一行一项：9 个分类单列要 460dp 以上，
                                // 会顶破抽屉高度上限；两列 5 行约 290dp，一屏放得下。
                                //
                                // 这里也**不能有滚动**（原因见上面的注释），所以只能靠排布压缩。
                                for (chunk in EDITOR_CATEGORIES.filter { it.kinds().isNotEmpty() }.chunked(2)) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        for (c in chunk) {
                                            SheetNodeCard(
                                                label = stringResource(c.titleRes),
                                                subtitle = stringResource(c.hintRes),
                                                accent = workflowNodeAccent(c.accent),
                                                modifier = Modifier.weight(1f),
                                                onTap = { sheetCategory = c },
                                                onDragStart = {},
                                                onDragMove = {},
                                                onDragEnd = {},
                                            )
                                        }
                                        if (chunk.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                            } else {
                                // ── 二级：该分类下的节点，每行两张 ──
                                val kinds = cat.kinds()
                                for (chunk in kinds.chunked(2)) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        for (kind in chunk) {
                                            val kindLabel = stringResource(kind.labelRes)
                                            SheetNodeCard(
                                                label = kindLabel,
                                                subtitle = kind.cap?.argsHint.orEmpty().ifBlank { kind.type.id },
                                                accent = workflowNodeAccent(kind.type),
                                                modifier = Modifier.weight(1f),
                                                onTap = {
                                                    addNode(kind, null)
                                                    sheetOpen = false
                                                    sheetCategory = null
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
                                                        val (xDp, yDp) = dropPointDp(windowPos)
                                                        addNodeAt(kind, xDp, yDp)
                                                    }
                                                    sheetOpen = false
                                                    sheetCategory = null
                                                },
                                            )
                                        }
                                        if (chunk.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                            }
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
                        TextButton(onClick = {
                            val (x, y) = dropPointDp(addAfterAt)
                            addNodeAt(kind, x, y, after)
                            addAfter = null
                        }) {
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
