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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshNativeBridge
import me.bmax.apatch.dsh.WorkflowActionCatalog
import me.bmax.apatch.dsh.WorkflowActionType
import me.bmax.apatch.dsh.effectiveCaps
import me.bmax.apatch.dsh.WorkflowDefinition
import me.bmax.apatch.dsh.WorkflowEdge
import me.bmax.apatch.dsh.WorkflowNode
import me.bmax.apatch.dsh.WorkflowNodeType
import me.bmax.apatch.dsh.WorkflowPatch
import me.bmax.apatch.dsh.WorkflowPinKind
import me.bmax.apatch.dsh.WorkflowSchema
import me.bmax.apatch.dsh.WorkflowValidator
import me.bmax.apatch.dsh.workflowDiff

/** 自动保存的可见状态。 */
private enum class SaveState { IDLE, SAVING, SAVED, FAILED }

/** 自动保存的防抖窗口：连续输入合并成一次写盘。 */
private const val AUTOSAVE_DEBOUNCE_MS = 400L

/** 目录里的默认 config：与参数编辑器的初值同源（见 [defaultConfigFor]）。 */
private fun editorConfigForAction(action: WorkflowActionType): Map<String, String> =
    defaultConfigFor(WorkflowNodeType.EXECUTE, action)

private data class EditorNodeKind(
    val type: WorkflowNodeType,
    val labelRes: Int,
    val defaults: Map<String, String>,
    /**
     * 这个节点来自「动作目录」的哪一条（非空 = 建出来就是一个 NATIVE 节点）。
     *
     * 与 [cap] 并存是过渡期的结果：文案目录那批卡片建的是 shell 骨架，动作目录这批建的是
     * 真正的桥调用。两套只在**选择面板**里合并展示，落盘的节点完全不同（前者 config 里有
     * cmd、后者有 bridge + p_*），所以不能压成一个字段。
     */
    val bridge: WorkflowActionCatalog.Action? = null,
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
 * 节点目录：基础节点一组，原生能力由 [WorkflowActionCatalog] 提供。
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
/** 取某一级分组（按标题资源）对应的动作节点。 */
private fun actionKindsOf(groupTitleRes: Int): List<EditorNodeKind> =
    WorkflowActionCatalog.byGroup()
        .firstOrNull { it.first == groupTitleRes }
        ?.second
        ?.map { editorKindOf(it) }
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

    fun actionSub(groupTitleRes: Int): NodePickerSubGroup =
        NodePickerSubGroup(
            titleRes = groupTitleRes,
            entries = actionKindsOf(groupTitleRes).map { kind ->
                // key 用**动作 id**而不是下标：同一个面板里两类节点混排，
                // 用 labels 或下标都会在两组有同名项时撞键，撞键的后果是选中项串位。
                entry("act_" + (kind.bridge?.id ?: kind.labelRes.toString()), kind)
            },
        )

    return listOf(
        NodePickerGroup(R.string.dsh_wf_cat_exec, listOf(
            NodePickerSubGroup(R.string.dsh_wf_cat_exec, exec.mapIndexed { i, k -> entry("exec_" + i, k) }),
        )),
        NodePickerGroup(R.string.dsh_wf_cat_control, listOf(
            NodePickerSubGroup(R.string.dsh_wf_cat_control, control.mapIndexed { i, k -> entry("ctl_" + i, k) }),
        )),
        NodePickerGroup(R.string.dsh_wf_grp_alert, listOf(actionSub(R.string.dsh_wf_grp_alert))),
        NodePickerGroup(R.string.dsh_wf_grp_device, listOf(actionSub(R.string.dsh_wf_grp_device))),
        NodePickerGroup(R.string.dsh_wf_grp_media, listOf(actionSub(R.string.dsh_wf_grp_media))),
        NodePickerGroup(R.string.dsh_wf_grp_data, listOf(actionSub(R.string.dsh_wf_grp_data))),
        NodePickerGroup(R.string.dsh_wf_grp_sense, listOf(actionSub(R.string.dsh_wf_grp_sense))),
        NodePickerGroup(R.string.dsh_wf_grp_screen, listOf(actionSub(R.string.dsh_wf_grp_screen))),
        // 特权（shell / SAF）不走动作目录：它们是"写一条命令"，不是"调一个端点"。
        // 执行分组里已有 shell 节点，这里不再重复列一次。
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
 * 参数默认值取自 [WorkflowActionCatalog.Action.params]。
 * 这些节点与上面的「基础节点」分开列，是为了不在抽屉里把 7 个基础 + 23 个能力混成一片。
 */
/**
 * 动作目录里的一行 → 一个可建的节点。
 *
 * 建出来就是 **NATIVE** 节点：config 里 `bridge` 指向动作 id，参数取目录声明的默认值。
 * 不建 shell 骨架 —— 骨架要靠用户在命令里手写路径与参数，写错了只有运行时才报，
 * 而那些路径本来就有结构化端点。
 */
private fun editorKindOf(action: WorkflowActionCatalog.Action): EditorNodeKind = EditorNodeKind(
    type = WorkflowNodeType.EXECUTE,
    labelRes = action.titleRes,
    defaults = mapOf("action" to "native", "bridge" to action.id) +
        action.params.associate { action.configKey(it) to it.default },
    bridge = action,
)

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
    val scope = rememberCoroutineScope()
    var saveState by remember { mutableStateOf(SaveState.IDLE) }
    var savedRevision by remember { mutableIntStateOf(initial.revision) }
    var pending by remember { mutableStateOf(false) }
    // 最近一次真正落盘的定义：它的 revision 是下一轮保存的前置条件。
    var savedDef by remember { mutableStateOf(initial) }
    fun persistNow(current: WorkflowDefinition) {
        val saver: (WorkflowDefinition) -> WorkflowDefinition? = autosave ?: return
        pending = false
        saveState = SaveState.SAVING
        scope.launch {
            // 写盘是 IO；放在 IO 派发器上，输入框打字不会被文件系统卡住。
            val saved: WorkflowDefinition? = withContext(Dispatchers.IO) {
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
    // 待确认删除的节点 id。删卡不能"点一下就没了"：它还会带走连着这个节点的所有边，
    // 而自动保存已经在后台写盘 —— 误删是不可撤销的。
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var addAfter by remember { mutableStateOf<String?>(null) }
    // 拉线落在空白时的**松手窗口坐标**：菜单里选完类型，新节点就落在这里（不是默认网格位）。
    var addAfterAt by remember { mutableStateOf(Offset.Zero) }
    var sheetOpen by remember { mutableStateOf(false) }
    // 顶栏「搜索」把节点列表切成搜索态、「更多」弹菜单。
    var searchOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var previewOpen by remember { mutableStateOf(false) }
    // 悬浮底栏的删除按钮：第一次点只变红，第二次才真删。
    var deleteArmed by remember { mutableStateOf(false) }
    /** 节点详情窗（连线管理 / 删这一步）。**不由点卡片触发**，只从顶栏「更多」进。 */
    var nodeSheetOpen by remember { mutableStateOf(false) }
    // 选中态一变，就撤销"变红"状态 —— 否则换一张卡时按钮会莫名其妙已经是红的。
    LaunchedEffect(selected) { deleteArmed = false }
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
    LaunchedEffect(def) {
        if (autosave == null || def == savedDef) return@LaunchedEffect
        pending = true
        saveState = SaveState.SAVING
        delay(AUTOSAVE_DEBOUNCE_MS)
        persistNow(def)
    }

    // 离开页面/切到后台时立刻 flush：防抖窗口里点返回，用户看到的"已保存"不能是假的。
    //
    // 用 LifecycleStartEffect 的 onStopOrDispose：它同时覆盖「切到后台」与「这一页被销毁」，
    // 比只在返回按钮上做一次保险得多（用户也可能直接锁屏或从最近任务划走）。
    LifecycleStartEffect(Unit) {
        onStopOrDispose {
            if (autosave != null && def != savedDef) persistNow(def)
        }
    }

    // 系统返回与「取消」同一条路：先问要不要放弃改动。
    androidx.activity.compose.BackHandler {
        if (patchLines.isEmpty()) onCancel() else confirmDiscard = true
    }

    /**
     * 新节点 id。
     *
     * 不能再用 `"n" + (nodes.size + 1)`：删掉中间某个节点之后，size 会回落，下一个新节点就
     * 会撞上已经存在的 id —— 撞 id 的后果是两条连线指向同一个节点，而画布只画得出一张卡。
     * 这里在「序号」与「时间戳」之外再排一次重，保证任何时候都不会重复。
     */
    fun newEditorNodeId(current: WorkflowDefinition): String {
        var seq = current.nodes.size + 1
        while (true) {
            val candidate = "n" + seq + "_" + (System.currentTimeMillis() % 1000)
            if (current.nodes.none { it.id == candidate }) return candidate
            seq++
        }
    }

    /**
     * 从某个节点拉出数据边时的**来源标识**。
     *
     * extract 节点产出的是变量（`var:<名字>`），其余节点产出的是本步输出（`step:<id>`）。
     * 写错这两者不会报错，只是永远取到空串 —— 所以只在这一处决定。
     */
    fun dataSourceFor(current: WorkflowDefinition, fromId: String): String {
        val src = current.node(fromId) ?: return "step:$fromId"
        val varName = src.config("var")
        return if (src.type == WorkflowNodeType.EXTRACT && varName.isNotBlank()) {
            "var:$varName"
        } else {
            "step:$fromId"
        }
    }

    /**
     * 数据边默认接到下游的**第一个**数据参数上。
     *
     * 拉完线之后可以在参数编辑器里改具体接到哪个键；直接在这里弹选择框会把"拉线"
     * 变成两步，而绝大多数情况下第一个键就是想要的那个（shell 的 cmd、notify 的 title、
     * 原生动作刚建出来的那个参数）。
     */
    fun defaultTargetPin(current: WorkflowDefinition, toId: String): String {
        val target = current.node(toId) ?: return ""
        return WorkflowSchema.dataInputKeysOf(target).firstOrNull().orEmpty()
    }

    /** 数据边的值类型。目前一律按字符串传，收敛交给执行器（它按声明类型处理）。 */
    fun dataTypeFor(fromId: String): String = "string"

    /**
     * 动作的默认节点名（当前语言的动作标题）。取不到就退回动作 id。
     *
     * 必须写在 [withActionDefaults] **之前**：Kotlin 的局部函数只认声明点之前的名字，
     * 反过来写是 Unresolved reference（不是警告，是编译错误）。
     */
    fun defaultNodeName(action: WorkflowActionCatalog.Action?): String =
        action?.let { runCatching { appStringRaw(it.titleRes) }.getOrDefault(it.id) } ?: ""

    /**
     * 动作卡建出来的节点：补上 `name`（卡片标题）与目录给的默认参数。
     *
     * 名字必须落进 `config["name"]` 而不是只写 node.name —— 参数编辑器改的是 config，
     * 两处各存一份的话，改完名字卡片上还是旧的。
     */
    fun withActionDefaults(kind: EditorNodeKind, action: WorkflowActionCatalog.Action): Map<String, String> =
        kind.defaults + ("name" to defaultNodeName(action))

    /**
     * 在指定 dp 位置新建节点（落点 = 松手位置，已吸附 12dp 网格）。
     * afterId 非空时顺手连一条出边（从针脚拉出来、却落在空白的情况）。
     */
    fun addNodeAt(kind: EditorNodeKind, xDp: Int, yDp: Int, afterId: String? = null) {
        val id = newEditorNodeId(def)
        val settled = if (kind.bridge != null) withActionDefaults(kind, kind.bridge) else kind.defaults
        draft = def.copy(
            nodes = def.nodes + WorkflowNode(
                id = id,
                type = kind.type,
                name = defaultNodeName(kind.bridge),
                posX = xDp,
                posY = yDp,
                config = settled,
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
    /**
     * 落在**当前视野中心**新建（对齐点击助手：点一下节点列表，卡就出现在你正在看的地方）。
     *
     * 上一版用的是"按序号排格子"：节点一多就会排到视野外，用户点完看不见任何变化，
     * 以为没加上。改成视野中心之后，落点永远在眼前。
     */
    fun addNode(kind: EditorNodeKind, afterId: String?) {
        // 视野中心 = 画布中心经视口逆变换回到世界坐标。
        val centerWindow = Offset(
            canvasOrigin.x + canvasSize.width / 2f,
            canvasOrigin.y + canvasSize.height / 2f,
        )
        val center = viewport.toWorldDp(centerWindow, canvasOrigin, density)
        val x = WorkflowGeometry.snapToGridDp(center.x - WorkflowNodeCardWidth.value / 2f)
            .coerceAtLeast(0)
        val y = WorkflowGeometry.snapToGridDp(center.y - 40f).coerceAtLeast(0)
        val id = newEditorNodeId(def)
        val settled = if (kind.bridge != null) withActionDefaults(kind, kind.bridge) else kind.defaults
        val node = WorkflowNode(
            id = id,
            type = kind.type,
            name = defaultNodeName(kind.bridge),
            posX = x,
            posY = y,
            config = settled,
        )
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
                // ── 顶栏：左「流程名」、右「搜索 / 保存 / 更多」 ──
                //
                // 对齐点击助手：左上角显示的是**流程名**（原来是一句固定的「编辑流程」），
                // 因为用户同时开着好几个流程时，唯一能区分"我在改哪一个"的信息就是名字。
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = def.name.ifBlank { title },
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    // 保存状态用一个小圆点 + 文字，放在按钮左边；失败时是红点。
                    if (autosave != null) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(
                                    color = when (saveState) {
                                        SaveState.FAILED -> MaterialTheme.colorScheme.error
                                        SaveState.SAVING -> MaterialTheme.colorScheme.tertiary
                                        else -> MaterialTheme.colorScheme.primary
                                    },
                                    shape = CircleShape,
                                ),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Surface(shape = CircleShape, tonalElevation = 5.dp) {
                        IconButton(onClick = { searchOpen = !searchOpen }) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = stringResource(R.string.dsh_wf_top_search),
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    // 保存按钮：图标 + 文字，但只保留图标以避免在窄屏上把顶栏挤爆；
                    // 手动保存 = 立即落盘（不等防抖）然后退出。
                    Button(
                        onClick = {
                            persistNow(def)
                            onSave(def)
                        },
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.dsh_workflow_editor_save))
                    }
                    Spacer(Modifier.width(6.dp))
                    // 「更多」菜单必须**锚在这颗按钮上**。
                    //
                    // 上一版把 DropdownMenu 直接放在外层 Box 里：DropdownMenu 的锚点由
                    // 它的父布局决定，外层 Box 是整屏，于是菜单弹到了左下角（真机截图就是这样）。
                    // 放进包住按钮的 Box 里，它才会贴着按钮往下展开。
                    Box {
                        Surface(shape = CircleShape, tonalElevation = 5.dp) {
                            IconButton(onClick = { moreOpen = true }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = stringResource(R.string.dsh_wf_top_more),
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = moreOpen,
                            onDismissRequest = { moreOpen = false },
                        ) {
                            // 放的是"不常用但偶尔必须"的项：预览、自动布局、放弃改动。
                            // 常用操作（加节点、保存、复制、删除）在别处，不塞进来。
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.dsh_wf_top_preview)) },
                                onClick = { moreOpen = false; previewOpen = true },
                            )
                            // 选中某张卡时才有意义：配置在卡上直接改，这里只管连线与删除。
                            if (selected != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.dsh_wf_top_node_detail)) },
                                    onClick = { moreOpen = false; nodeSheetOpen = true },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.dsh_workflow_editor_auto_layout)) },
                                onClick = {
                                    moreOpen = false
                                    draft = def.copy(nodes = def.nodes.map { it.copy(posX = 0, posY = 0) })
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.dsh_workflow_editor_discard_title)) },
                                onClick = { moreOpen = false; confirmDiscard = true },
                            )
                        }
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
                        // 框跟着**选中态**，不是跟着拖动：松手后仍要看见"当前选中的是哪张"，
                        // 直到点别处（onBlankTap 清 selected）或选中另一张。
                        // 已授予的能力：目前按"定义里声明的 + 目录推出来的"整体放行。
                        // 真正逐项判定在运行前的 WorkflowPermissions 里做，这里只负责显示
                        // "这张卡的权限有没有到位"，所以取的是同一份 effectiveCaps 的子集关系。
                        grantedCaps = def.effectiveCaps.toSet(),
                        // 卡上直接改参数：改完立刻写回 draft（走既有的自动保存）。
                        onNodeParamChange = { nodeId, key, value ->
                            draft = draft.copy(
                                nodes = draft.nodes.map {
                                    if (it.id == nodeId) it.withConfig(key, value) else it
                                },
                            )
                        },
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
                        onWireDrop = { fromId, fromPin, toId, pointer ->
                            if (toId != null) {
                                // 成环的边**不落盘**：环一旦进入定义，执行器的拓扑调度会死循环。
                                // 判据只有 WorkflowValidator.addEdgeIfAcyclic 一处 —— 连边入口若
                                // 以后增加，也必须走它，否则环会从新入口漏进来。
                                // 从**数据针脚**拉出来的线是数据边，不参与控制流；从执行针脚
                                // 拉出来的才带 when（成立 / 不成立各自落成 true / false ——
                                // 两条都落成默认边的话，执行器在判断节点上只会选第一条）。
                                val isDataPin = fromPin == WorkflowPinKind.DATA_OUT.name
                                val next = if (isDataPin) {
                                    WorkflowValidator.addDataEdge(
                                        def = def,
                                        fromId = fromId,
                                        fromPin = dataSourceFor(def, fromId),
                                        toId = toId,
                                        toPin = defaultTargetPin(def, toId),
                                        dataType = dataTypeFor(fromId),
                                    )
                                } else {
                                    val whenExpr = when (fromPin) {
                                        WorkflowPinKind.EXEC_TRUE.name -> "true"
                                        WorkflowPinKind.EXEC_FALSE.name -> "false"
                                        else -> ""
                                    }
                                    WorkflowValidator.addEdgeIfAcyclic(def, fromId, toId, whenExpr)
                                }
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
                    LaunchedEffect(canvasSize, def.nodes.size) {
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

            }

            // ── 定义不合法时，底栏上方浮一条红条 ──
            //
            // 为什么不塞进底栏胶囊：胶囊里全是图标，塞一段中文会把胶囊撑成一整行（上一版
            // 就是这么被挤成一列竖排、把底栏撑到满屏高的）。单独一条浮在上面，既能读全，
            // 又不影响底栏的紧凑形态。运行状态因此可以一眼看见，不必点开任何东西。
            if (errors.isNotEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = 84.dp, start = 16.dp, end = 16.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        tonalElevation = 6.dp,
                    ) {
                        Text(
                            text = stringResource(R.string.dsh_workflow_editor_errors) + " " + errors.size,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            // ── 悬浮底栏（居中、浮在画布之上，不占布局高度）──
            //
            // 对齐点击助手：底部是一个**浮起来**的胶囊，而不是占满一行的工具条。
            // 占一行会把画布高度吃掉一截，而画布正是这一页最需要大的部分。
            // 它在这里、而不是在 Column 里面 —— 只有放在 Column **外面**才叫"浮在画布上"，
            // 放进去就变成又一行布局，画布照样变矮。
            //
            // 两种形态随选中态切换：
            //  - 没选中：回中 / ＋ 唤出节点列表 / 整理（自动布局）
            //  - 选中一张卡：复制 / 删除。删除要点第二下才真删 —— 第一下把它变红。
            //    为什么不用确认弹窗：底栏是高频操作，弹窗会打断节奏；而"变红"既拦住了误触，
            //    也保住了节奏。真正不可逆的删卡仍然有确认（点卡片弹窗里那条路）。
            FloatingEditorBar(
                hasSelection = selected != null,
                canDelete = def.node(selected.orEmpty())?.type != WorkflowNodeType.TRIGGER,
                deleteArmed = deleteArmed,
                onRecenter = {
                    viewport = WorkflowViewport.fitTo(
                        editorLayoutFor(def).map { it.second to it.third },
                        Offset(canvasSize.width.toFloat(), canvasSize.height.toFloat()),
                        density,
                    )
                },
                onAdd = { sheetOpen = true; searchOpen = false },
                onReset = { draft = def.copy(nodes = def.nodes.map { it.copy(posX = 0, posY = 0) }) },
                onCopy = {
                    val src = selected?.let { def.node(it) }
                    if (src != null) {
                        val newId = newEditorNodeId(def)
                        // 副本**不自动连边**：点「复制」想要的是"再加一张同类型的卡"，
                        // 而不是"让原来那条链分叉"。接在哪由用户自己拉线决定。
                        draft = def.copy(
                            nodes = def.nodes + src.copy(
                                id = newId,
                                posX = WorkflowGeometry.snapToGridDp(src.posX.toFloat() + 32f),
                                posY = WorkflowGeometry.snapToGridDp(src.posY.toFloat() + 32f),
                            ),
                        )
                        selected = newId
                    }
                },
                onDelete = {
                    if (deleteArmed) {
                        val delId = selected
                        if (delId != null) {
                            draft = def.copy(
                                nodes = def.nodes.filterNot { it.id == delId },
                                edges = def.edges.filterNot { e -> e.from == delId || e.to == delId },
                            )
                            selected = null
                        }
                        deleteArmed = false
                    } else {
                        deleteArmed = true
                    }
                },
            )

            // ── 预览（只读 + 可缩放，不占编辑手势）──
            if (previewOpen) {
                Dialog(onDismissRequest = { previewOpen = false }) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        tonalElevation = 6.dp,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                text = def.name.ifBlank { title },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            var pzoom by remember { mutableStateOf(1f) }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(260.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                    .pointerInput(Unit) {
                                        // 只用来缩放：预览里没有拖动卡的需求，双指就够了。
                                        detectTransformGestures { _, _, zoomChange, _ ->
                                            pzoom = (pzoom * zoomChange).coerceIn(0.4f, 1.6f)
                                        }
                                    },
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            transformOrigin = TransformOrigin(0f, 0f)
                                            scaleX = pzoom
                                            scaleY = pzoom
                                        },
                                ) {
                                    WorkflowGraphPreview(def)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { previewOpen = false }) {
                                    Text(stringResource(R.string.dsh_workflow_action_close))
                                }
                            }
                        }
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
                    // 顶栏那个放大镜直接把面板切到搜索态：用户点搜索就是"我要找某个节点"，
                    // 不必再进面板里点一次放大镜。
                    initialSearchOpen = searchOpen,
                    onDismiss = { sheetOpen = false },
                )
            }
        }
    }

    // ── 节点详情窗：**不再由点卡片触发** ──
    //
    // 用户的原话：「怎么还是这种弹窗式配置？参数都是在卡片本身上的」。
    // 现在参数确实在卡上（见 WorkflowNodeCard 的可编辑参数行），点卡片只负责**选中**。
    // 这个窗还留着的唯一理由是它承载「连线管理」与「删这一步」—— 卡上放不下，
    // 而这两件事又不能没有入口，于是收进顶栏「更多」里（nodeSheetOpen），
    // 从"每次点卡都弹"降级成"偶尔要用才点开"。
    if (nodeSheetOpen) selected?.let { sid ->
        val sel = def.node(sid)
        if (sel != null) {
            AlertDialog(
                onDismissRequest = { nodeSheetOpen = false },
                title = { Text(sel.name.ifBlank { sel.id }) },
                text = {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        NodeParamEditor(
                            node = sel,
                            onChange = { updated ->
                                // name 是节点标题的唯一来源：字段表里改 name 也要同步 node.name，
                                // 否则卡片/日志/批准弹窗显示的还是旧标题（两处存同一件事，必然走样）。
                                val synced = if (updated.config("name") != sel.config("name")) {
                                    updated.copy(name = updated.config("name"))
                                } else {
                                    updated
                                }
                                draft = def.copy(nodes = def.nodes.map { if (it.id == synced.id) synced else it })
                            },
                            // 入口节点不给删除按钮：删掉它整张图没有起点，定义立刻不合法。
                            onDelete = if (sel.type == WorkflowNodeType.TRIGGER) null else {
                                { pendingDelete = sel.id }
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
                    TextButton(onClick = { nodeSheetOpen = false }) {
                        Text(stringResource(R.string.dsh_workflow_editor_save))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { nodeSheetOpen = false }) {
                        Text(stringResource(R.string.dsh_workflow_action_cancel))
                    }
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

    // ── 删卡确认 ──
    //
    // 点删除只登记意图，真正的改动在用户确认后才发生。确认文案里带上"会一并删掉的连线数"，
    // 因为那部分损失用户看不见（卡一没，线也没了）。
    pendingDelete?.let { delId ->
        val gone = def.node(delId)
        val related = def.edges.count { it.from == delId || it.to == delId }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.dsh_wf_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.dsh_wf_delete_body,
                        gone?.name?.ifBlank { delId } ?: delId,
                        related,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    draft = def.copy(
                        nodes = def.nodes.filterNot { it.id == delId },
                        edges = def.edges.filterNot { e -> e.from == delId || e.to == delId },
                    )
                    pendingDelete = null
                    selected = null
                }) { Text(stringResource(R.string.dsh_wf_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
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

/**
 * 节点参数编辑（点卡片弹出的窗里用）。
 *
 * 控件全部由 [WorkflowNodeFields.fieldsFor] 生成 —— 界面不认识具体键名，所以校验器要求的
 * 每个键在这里必然有对应控件（枚举给 chip、布尔给开关、数值只收数字）。
 *
 * 允许删卡，但**入口（trigger）不能删**：删掉它整张图就没有起点，定义立刻不合法，
 * 而用户此时已经看不到任何解释。所以这里直接不给那个按钮。
 */
@Composable
private fun NodeParamEditor(
    node: WorkflowNode,
    onChange: (WorkflowNode) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = node.type.id + (node.actionType?.let { " · " + it.id } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (onDelete != null) {
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.dsh_wf_param_delete))
                }
            }
        }
        // 动作类型：只对 execute 节点有意义；切换时整份替换 config（见 defaultConfigFor 的注释）。
        if (node.type == WorkflowNodeType.EXECUTE) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (action in WorkflowActionType.entries) {
                    if (node.actionType == action) {
                        Button(onClick = {}, shape = RoundedCornerShape(50)) { Text(action.id) }
                    } else {
                        OutlinedButton(
                            onClick = {
                                onChange(
                                    node.copy(config = defaultConfigFor(node.type, action)),
                                )
                            },
                            shape = RoundedCornerShape(50),
                        ) { Text(action.id) }
                    }
                }
            }
        }
        for (field in WorkflowNodeFields.fieldsFor(node)) {
            NodeFieldRow(node = node, field = field, onChange = onChange)
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


/**
 * 编辑器底部的**悬浮胶囊**（对齐点击助手：浮在画布上，不占一行布局）。
 *
 * 为什么浮起来而不占布局：画布高度是这一页最稀缺的资源。占一行工具条会让"看得见的图"变矮
 * 一截，而底栏在全屏编辑里真正被点到的频率并不高。悬浮 + 半透明底，既保证可达，又不抢地方。
 *
 * 两种形态由 [hasSelection] 切：没选卡 = 视图操作（回中/加节点/整理）；选了卡 = 卡片操作
 * （复制/删除）。不把两种混在同一条里，是因为"我想加节点"和"我想动这张卡"是两种不同意图，
 * 混在一起时按钮的位置会随选中态跳动，肌肉记忆建立不起来。
 *
 * [deleteArmed] 是删除的二次确认：第一下变红（[onDelete] 被调用但调用方只置位），
 * 第二下才真删。这样拦住了误触，又不必弹窗打断节奏。
 */
@Composable
private fun FloatingEditorBar(
    hasSelection: Boolean,
    canDelete: Boolean,
    deleteArmed: Boolean,
    onRecenter: () -> Unit,
    onAdd: () -> Unit,
    onReset: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    // 必须是 fillMaxSize + BottomCenter。
    //
    // 上一版写的是 fillMaxWidth（高度包内容）：外层 Box(fillMaxSize) 里，一个高度只包内容的
    // 子节点默认落在**左上角**，BottomCenter 也就只在那一小条里生效 —— 真机上底栏跑到顶上去了。
    Box(
        Modifier
            .fillMaxSize()
            // 底栏自身不接手势：否则它会把整个画布的拖动/缩放全吃掉（fillMaxSize 的命中区是整屏）。
            .padding(bottom = 12.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            tonalElevation = 8.dp,
            shadowElevation = 6.dp,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasSelection) {
                    FloatBarIcon(Icons.Filled.ContentCopy, stringResource(R.string.dsh_wf_float_copy), onClick = onCopy)
                    // 入口卡不给删除按钮：删掉它整张图没有起点，定义立刻不合法。
                    if (canDelete) {
                        FloatBarIcon(
                            icon = if (deleteArmed) Icons.Filled.Warning else Icons.Filled.Delete,
                            label = if (deleteArmed) {
                                stringResource(R.string.dsh_wf_float_delete_confirm)
                            } else {
                                stringResource(R.string.dsh_wf_float_delete)
                            },
                            tint = if (deleteArmed) MaterialTheme.colorScheme.error else null,
                            onClick = onDelete,
                        )
                    }
                } else {
                    FloatBarIcon(Icons.Filled.Visibility, stringResource(R.string.dsh_workflow_zoom_reset), onClick = onRecenter)
                    // 中间那颗是实心主色圆：它是这一栏的主操作（唤出节点列表）。
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 2.dp).size(48.dp),
                    ) {
                        IconButton(onClick = onAdd) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = stringResource(R.string.dsh_wf_float_add),
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                    FloatBarIcon(Icons.Filled.Refresh, stringResource(R.string.dsh_workflow_editor_auto_layout), onClick = onReset)
                }
            }
        }
    }
}

/** 悬浮栏里的一个方形图标按钮。[tint] 为空时用 onSurfaceVariant。 */
@Composable
private fun FloatBarIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = label, tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
