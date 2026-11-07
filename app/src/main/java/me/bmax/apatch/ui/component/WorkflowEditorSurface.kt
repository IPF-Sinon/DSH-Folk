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
import androidx.compose.ui.graphics.Color
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

/** 自动保存的可见状态。 */
private enum class SaveState { IDLE, SAVING, SAVED, FAILED }

/** 自动保存的防抖窗口：连续输入合并成一次写盘。 */
private const val AUTOSAVE_DEBOUNCE_MS = 400L

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
 * 节点目录：基础节点单独一组，原生能力由 [NativeCapCatalog] 提供。
 *
 * ## 为什么基础节点与原生能力分开存
 * 基础节点是「控制流/执行」的固定几项，原生能力是「具体能做什么」的二十多项。
 * 混在一个列表里既看不出层次，也无法让能力项自带命令骨架。
 *
 * ## 分类按「用户想干什么」切，不按代码里的枚举切
 * 基础节点在代码里是 EXECUTE / CONDITION / WAIT …，但用户找的是「让它跑一条命令」
 * 还是「让它等一下」。所以「执行」与「控制流」分开，前者是"做什么"，
 * 后者是"什么时候做、做几次"。
 */
/** 取某个能力分组对应的编辑器节点（目录里没有的跳过，不让它出现在 UI 上）。 */
private fun capKindsOf(group: NativeCapCatalog.Group): List<EditorNodeKind> =
    NativeCapCatalog.byGroup()
        .firstOrNull { it.first == group }
        ?.second
        ?.mapNotNull { cap -> EDITOR_CAP_NODE_CATALOG.firstOrNull { it.cap == cap } }
        .orEmpty()

/**
 * 把编辑器节点目录翻译成 [WorkflowNodePicker] 的分组数据。
 *
 * 一级分组 = 用户想干什么（执行 / 控制流 / 提醒 / 设备 / …）；
 * 二级分组 = 该组内部再细分的类别。
 *
 * 为什么二级对基础节点也给一个分类：面板版式是「一级 chip + 二级 chip + 列表」，
 * 若某个一级分组只有一个二级分组，用户会看到一排只有一个按钮的空壳 —— 观感很差。
 */
// 这里**刻意不用 @Composable**，也不用 workflowNodeAccent。
//
// 教训：数据构建一旦依赖主题色，排队等着的是连锁的 @Composable 传染 ——
// 外层函数要标、内层局部函数要标、包它的 remember 又不算 Composable 上下文。
// 三处报错修完还会因为新加的局部函数再次红。
//
// 色点只是列表里 8dp 的辅助标记，不需要跟随主题语义色。
// 用固定色即可：构建纯数据、可在任何上下文调用，也不会再被 remember 包坏。
private fun editorPickerGroups(): List<NodePickerGroup> {
    fun entry(key: String, kind: EditorNodeKind): NodePickerEntry = NodePickerEntry(
        key = key,
        titleRes = kind.labelRes,
        descRes = 0,
        accent = editorPickerAccent(kind.type),
        // 搜索文本用资源名而不是 stringResource：构建分组不该再读一次当前语言，
        // 面板会用它自己的 locale 匹配。这里给的是稳定的、可搜的英文/中文键名。
        searchText = appStringRaw(kind.labelRes),
        payload = kind,
    )

    val exec = EDITOR_NODE_CATALOG.filter { it.type == WorkflowNodeType.EXECUTE }
    val control = EDITOR_NODE_CATALOG.filter {
        it.type == WorkflowNodeType.CONDITION ||
            it.type == WorkflowNodeType.WAIT ||
            it.type == WorkflowNodeType.LOGIC ||
            it.type == WorkflowNodeType.EXTRACT
    }

    fun capSub(group: NativeCapCatalog.Group, titleRes: Int): NodePickerSubGroup =
        NodePickerSubGroup(
            titleRes = titleRes,
            entries = capKindsOf(group).map { kind ->
                entry("cap_" + (kind.cap?.cap?.id ?: kind.labelRes.toString()), kind)
            },
        )

    return listOf(
        NodePickerGroup(R.string.dsh_wf_cat_exec, listOf(
            NodePickerSubGroup(R.string.dsh_wf_cat_exec, exec.mapIndexed { i, k -> entry("exec_" + i, k) }),
        )),
        NodePickerGroup(R.string.dsh_wf_cat_control, listOf(
            NodePickerSubGroup(R.string.dsh_wf_cat_control, control.mapIndexed { i, k -> entry("ctl_" + i, k) }),
        )),
        NodePickerGroup(R.string.dsh_wf_cat_alert, listOf(capSub(NativeCapCatalog.Group.ALERT, R.string.dsh_wf_cat_alert))),
        NodePickerGroup(R.string.dsh_wf_cat_device, listOf(capSub(NativeCapCatalog.Group.DEVICE, R.string.dsh_wf_cat_device))),
        NodePickerGroup(R.string.dsh_wf_cat_media, listOf(capSub(NativeCapCatalog.Group.MEDIA, R.string.dsh_wf_cat_media))),
        NodePickerGroup(R.string.dsh_wf_cat_data, listOf(capSub(NativeCapCatalog.Group.DATA, R.string.dsh_wf_cat_data))),
        NodePickerGroup(R.string.dsh_wf_cat_sense, listOf(capSub(NativeCapCatalog.Group.SENSE, R.string.dsh_wf_cat_sense))),
        NodePickerGroup(R.string.dsh_wf_cat_screen, listOf(capSub(NativeCapCatalog.Group.SCREEN, R.string.dsh_wf_cat_screen))),
        NodePickerGroup(R.string.dsh_wf_cat_privilege, listOf(capSub(NativeCapCatalog.Group.PRIVILEGE, R.string.dsh_wf_cat_privilege))),
    )
}

/**
 * 列表色点用的固定色。
 *
 * 与 workflowNodeAccent 的区别：那个读主题角色色（@Composable）；
 * 这个是一组常量，供数据构建用。两者视觉接近，但这里必须能脱离 Composable 上下文调用。
 */
private fun editorPickerAccent(type: WorkflowNodeType): Color = when (type) {
    WorkflowNodeType.TRIGGER -> Color(0xFF7E57C2)
    WorkflowNodeType.EXECUTE -> Color(0xFF3F7DE0)
    WorkflowNodeType.CONDITION, WorkflowNodeType.LOGIC -> Color(0xFF2E9E6B)
    WorkflowNodeType.EXTRACT -> Color(0xFF5C6BC0)
    WorkflowNodeType.WAIT -> Color(0xFFE08A2E)
}
/** 取字符串资源在当前语言的文本（搜索用）。 */
private fun appStringRaw(resId: Int): String = me.bmax.apatch.apApp.resources.getString(resId)

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
    /** 自动保存（仅普通用户编辑页传入；审批弹窗传 null = 纯临时草稿）。 */
    autosave: ((WorkflowDefinition) -> WorkflowDefinition?)? = null,
    /** 新建时已收好的名字，进页面即写回流程名（为空表示沿用 initial）。 */
    newName: String = "",
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
    var draft by remember { mutableStateOf(if (newName.isBlank()) baseline else baseline.copy(name = newName)) }
    val def = draft
    // ── 自动保存（只在普通编辑页；autosave==null 时全是空操作）──
    //
    // 语义：每次模型变化排队一次落盘；连续输入用 400ms 防抖合并，离开页面/切后台立即 flush。
    // 落盘走 saveIfRevision（前置 revision），冲突时不覆盖 —— 保存状态原样摆在顶栏上，
    // 让用户自己看见"存了没有"，而不是关掉页面后才发现改动丢了。
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var saveState by remember { mutableStateOf(SaveState.IDLE) }
    var savedRevision by remember { mutableIntStateOf(initial.revision) }
    var pending by remember { mutableStateOf(false) }
    // 最近一次真正落盘的定义：它的 revision 是下一轮保存的前置条件。
    var savedDef by remember { mutableStateOf(initial) }
    fun persistNow(current: WorkflowDefinition) {
        val saver = autosave ?: return
        pending = false
        saveState = SaveState.SAVING
        scope.launch {
            // 写盘是 IO；放在 IO 派发器上，输入框打字不会被文件系统卡住。
            val saved = withContext(kotlinx.coroutines.Dispatchers.IO) {
                saver(current.copy(revision = savedRevision))
            }
            if (saved != null) {
                savedDef = saved
                savedRevision = saved.revision
                saveState = SaveState.SAVED
            } else {
                saveState = SaveState.FAILED
            }
        }
    }
    var selected by remember { mutableStateOf<String?>(null) }
    var addAfter by remember { mutableStateOf<String?>(null) }
    // 拉线落在空白时的**松手窗口坐标**：菜单里选完类型，新节点就落在这里（不是默认网格位）。
    var addAfterAt by remember { mutableStateOf(Offset.Zero) }
    var sheetOpen by remember { mutableStateOf(false) }
    // 抽屉当前停在一级分类（null = 显示分类列表）。用 saveable 没必要：
    // 旋转后回到分类首页比「停在一个半开的二级页」更符合预期。
    var confirmDiscard by remember { mutableStateOf(false) }
    // 抽屉拖出：幽灵卡（null = 没在拖）、根容器与画布在窗口里的位置。
    var ghost by remember { mutableStateOf<Pair<String, Offset>?>(null) }
    var canvasOrigin by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // 视口（缩放+平移）：**只存在于本次编辑会话**，不落盘 —— 跨设备恢复旧视口没有意义。
    // 落点/拖动全部经它的逆变换，见 [WorkflowViewport]。
    var viewport by remember { mutableStateOf(WorkflowViewport()) }
    var viewportInit by remember { mutableStateOf(false) }
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
    // 防抖落盘：draft 每变一次就重置计时器，只有停下来 400ms 才真写。
    // 连续打字会一路重置 —— 逐字符写盘既费 IO，也会让 saveIfRevision 撞自己的 revision。
    androidx.compose.runtime.LaunchedEffect(def) {
        if (autosave == null || def == savedDef) return@LaunchedEffect
        pending = true
        saveState = SaveState.SAVING
        kotlinx.coroutines.delay(AUTOSAVE_DEBOUNCE_MS)
        persistNow(def)
    }

    // 离开页面/切到后台时立刻 flush：防抖窗口里点返回，用户看到的"已保存"不能是假的。
    androidx.lifecycle.compose.LifecyclePauseEffect(Unit) {
        if (autosave != null && def != savedDef) persistNow(def)
        onPauseOrDispose { }
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

    /**
     * 窗口坐标 → 世界 dp（吸附 12dp 网格）。拉线/新节点的落点都走这里。
     *
     * 必须经 [WorkflowViewport.toWorldDp]：只减 canvasOrigin 的那一版在缩小/放大之后会把
     * 节点放到完全不相干的位置（那是"缩小时新节点跑到视野外"的直接成因）。
     */
    fun dropPointDp(pointerWindow: Offset): Pair<Int, Int> {
        val world = viewport.toWorldDp(pointerWindow, canvasOrigin, density)
        return Pair(
            WorkflowGeometry.snapToGridDp(world.x).coerceAtLeast(0),
            WorkflowGeometry.snapToGridDp(world.y).coerceAtLeast(0),
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
        modifier = Modifier.fillMaxSize(),
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
                    // 保存状态：自动保存时必须让用户看见"存了没有"，否则返回时心里没底。
                    if (autosave != null) {
                        Text(
                            text = stringResource(
                                when (saveState) {
                                    SaveState.IDLE -> R.string.dsh_workflow_save_saved
                                    SaveState.SAVING -> R.string.dsh_workflow_save_saving
                                    SaveState.SAVED -> R.string.dsh_workflow_save_saved
                                    SaveState.FAILED -> R.string.dsh_workflow_save_failed
                                },
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (saveState == SaveState.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Button(onClick = {
                        // 手动保存 = 立刻落盘（不等防抖）然后退出。
                        persistNow(def)
                        onSave(def)
                    }, shape = RoundedCornerShape(50)) {
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
                                // 位移要经视口换算：缩放后手指走 100px，节点只能走 100/(scale·density) dp。
                                dragAcc += viewport.dragDeltaDp(dxPx, dyPx, density)
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
                        viewport = viewport,
                        onViewportChange = { viewport = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                    // 首次打开（以及画布尺寸终于确定时）把视口 fit 到内容：视口不落盘，
                    // 所以每次都从"整张图都看得见"开始，而不会对着空白角落。
                    androidx.compose.runtime.LaunchedEffect(canvasSize, def.nodes.size) {
                        if (!viewportInit && canvasSize != IntSize.Zero && def.nodes.isNotEmpty()) {
                            val pts = editorLayoutFor(def).map { it.second to it.third }
                            viewport = WorkflowViewport.fitTo(
                                pts,
                                Offset(canvasSize.width.toFloat(), canvasSize.height.toFloat()),
                                density,
                            )
                            viewportInit = true
                        }
                    }
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
                    // 缩放控件：双指也能缩，但工具条给一条明确的路（与运行详情那套 +/- 一致）。
                    TextButton(onClick = {
                        viewport = viewport.copy(
                            scale = (viewport.scale - 0.2f).coerceAtLeast(WorkflowViewport.MIN_SCALE),
                        )
                    }) { Text("−") }
                    Text(
                        text = (viewport.scale * 100).toInt().toString() + "%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        viewport = viewport.copy(
                            scale = (viewport.scale + 0.2f).coerceAtMost(WorkflowViewport.MAX_SCALE),
                        )
                    }) { Text("+") }
                    TextButton(onClick = {
                        viewport = WorkflowViewport.fitTo(
                            editorLayoutFor(def).map { it.second to it.third },
                            Offset(canvasSize.width.toFloat(), canvasSize.height.toFloat()),
                            density,
                        )
                    }) { Text(stringResource(R.string.dsh_workflow_zoom_reset)) }
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


            // ── 节点抽屉：往外拖到画布 = 在那个位置新建；点一下 = 画布中央新建 ──
            // ── 节点选择面板（底部「加节点」入口）──
            //
            // 与「从输出口拉出」那个入口**共用同一个组件**：两处选项集必须一致。
            // 之前那边是一个只有 7 个基础节点的 AlertDialog，原生能力根本看不到。
            if (sheetOpen) {
                // 不能包在 remember 里：remember 的 lambda 不是 Composable 上下文，
                // 而 editorPickerGroups 要读主题色（workflowNodeAccent 是 @Composable）。
                // 每次重组重建这点数据可忽略 —— 它是十来个小对象，不做 IO。
                val groups = editorPickerGroups()
                WorkflowNodePicker(
                    groups = groups,
                    onPick = { entry ->
                        (entry.payload as? EditorNodeKind)?.let { kind -> addNode(kind, null) }
                        sheetOpen = false
                    },
                    onDismiss = { sheetOpen = false },
                )
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
    // ── 从输出口拉出后落在空白：选一个节点，建出来并连上前一个 ──
    //
    // 同一个面板，只是 onPick 的落点不同：这里要接在 after 之后。
    addAfter?.let { after ->
        val groups = editorPickerGroups()
        WorkflowNodePicker(
            groups = groups,
            onPick = { entry ->
                (entry.payload as? EditorNodeKind)?.let { kind ->
                    val (x, y) = dropPointDp(addAfterAt)
                    addNodeAt(kind, x, y, after)
                }
                addAfter = null
            },
            onDismiss = { addAfter = null },
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
