package me.bmax.apatch.ui.component

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.delay
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshNativeBridge
import me.bmax.apatch.dsh.WorkflowActionType
import me.bmax.apatch.dsh.WorkflowApprovals
import me.bmax.apatch.dsh.WorkflowDefinition
import me.bmax.apatch.dsh.WorkflowEdge
import me.bmax.apatch.dsh.WorkflowNode
import me.bmax.apatch.dsh.WorkflowNodeType
import me.bmax.apatch.dsh.WorkflowPatch
import me.bmax.apatch.dsh.WorkflowPermissions
import me.bmax.apatch.dsh.WorkflowValidator
import me.bmax.apatch.dsh.definitionFromJson
import me.bmax.apatch.dsh.toJson
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
            val initial = remember(request.id) { definitionFromJson(request.originalJson) }
            if (initial != null) {
                WorkflowEditorDialog(
                    initial = initial,
                    title = stringResource(R.string.dsh_workflow_editor_title),
                    onSave = { edited ->
                        WorkflowApprovals.stopEditing(request.id, edited.toJson().toString())
                    },
                    onCancel = { WorkflowApprovals.stopEditing(request.id, null) },
                )
            } else {
                // 解析失败（提交前校验过，理论上不会）：直接退出编辑，桥那边按原版走。
                LaunchedEffect(request.id) { WorkflowApprovals.stopEditing(request.id, null) }
            }
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

/** 画布上的固定节点尺寸：Canvas 里的连线坐标与卡片本身必须用同一套数字，否则线会飘。 */
private val NODE_W = 148.dp
private val NODE_H = 46.dp
private const val CANVAS_STEP_DP = 18

/**
 * 流程画布：点阵背景 + 节点卡片 + 正交连线（带 true/false 标签）。
 *
 * 视觉参考的是「蓝图式」编辑器的通行做法（节点是可拖的卡片、连线表达顺序与分支），
 * 而不是某一家的截图 —— 点击助手的文档只给出了这个概念，没有公开配色/尺寸规格。
 * 预览与编辑页共用同一套 [NODE_W]/[NODE_H] 与连线画法，所以「看到的图」和「摆的位置」一致。
 */
@Composable
internal fun WorkflowGraphPreview(definition: WorkflowDefinition) {
    // 预览 = 同一块画布、只读模式。摆位仍由 layoutNodes 提供（有落盘坐标就用、压住了退回
    // 自动分层），于是「预览里看到的」与「编辑器里摆的」是同一份坐标 —— 以前两边各推导一遍，
    // 漂移过一次。
    val placed = remember(definition) { layoutNodes(definition) }
    WorkflowBlueprintCanvas(
        definition = definition,
        positions = placed.associate { it.id to Offset(it.x, it.y) },
        readOnly = true,
        modifier = Modifier.fillMaxWidth().height(340.dp),
    )
}

/** 节点在画布上的坐标：有落盘坐标就用它，全为 0（AI 生成的定义）时按拓扑分层自动摆。 */
private data class PlacedNode(val id: String, val x: Float, val y: Float)

private fun layoutNodes(definition: WorkflowDefinition): List<PlacedNode> {
    val anyPlaced = definition.nodes.any { it.posX != 0 || it.posY != 0 }
    if (anyPlaced) {
        val stored = definition.nodes.map { PlacedNode(it.id, it.posX.toFloat(), it.posY.toFloat()) }
        // 落盘坐标可能互相压住（早期版本没写回坐标、或 AI 生成的定义给了同一组数），
        // 真机上就是「节点叠在一起、标签串行」。压住就退回自动分层，别把糊掉的图端给用户。
        if (!overlaps(stored)) return stored
    }
    val layers = graphLayers(definition)
    val out = mutableListOf<PlacedNode>()
    for ((depth, nodes) in layers.withIndex()) {
        for ((i, n) in nodes.withIndex()) {
            // 行距 82（> 卡片 46 + 底部针脚标签 ~20）：标签不再压到下一行的卡片上
            out += PlacedNode(n.id, snapGrid(16f + i * 168f), 16f + depth * 82f)
        }
    }
    return out
}

/** 两个节点的矩形是否相交（含底部标签约 20dp 的余量）。 */
private fun overlaps(placed: List<PlacedNode>): Boolean {
    for (i in placed.indices) {
        for (j in i + 1 until placed.size) {
            val a = placed[i]
            val b = placed[j]
            if (kotlin.math.abs(a.x - b.x) < 148f && kotlin.math.abs(a.y - b.y) < 66f) return true
        }
    }
    return false
}

/** 位置吸附到 12dp 网格（规格里卡片的坐标量纲就是格号）。 */
private fun snapGrid(v: Float): Float = kotlin.math.round(v / 12f) * 12f

/** 节点副标题：类型 + 动作/关键配置，让用户一眼看出这一步做什么。 */
/**
 * 定义编辑器（全屏层）：改节点、改参数、挪位置、加删连线。
 *
 * **审批流程与设置页共用这一份**：AI 提交后用户点「编辑」看到的界面，就是他平时自己建流程
 * 的界面。一旦分成两份实现，迟早会出现「AI 能改的字段用户改不了」（或者反过来）。
 *
 * 位置是**画布上的真实坐标**（拖完写回 `posX/posY`），与预览一样落进定义里 —— 蓝图编辑器
 * 的摆位如果不存，用户摆好的图下次打开就自己跳了。
 *
 * 校验结果就地显示：不合法时用户在这里就能看到和 AI 收到的那份同源的错误，而不是保存之后
 * 在运行时撞一个 invalid_definition。
 */
@Composable
internal fun WorkflowDefinitionEditor(
    initial: WorkflowDefinition,
    title: String,
    onSave: (WorkflowDefinition) -> Unit,
    onCancel: () -> Unit,
) {
    // 编辑页的全部实现在 WorkflowEditorSurface（页面与审批弹窗共用同一份内容）。
    // 这个函数只是审批路径的入口名，保持签名不变。
    WorkflowEditorSurface(initial = initial, title = title, onSave = onSave, onCancel = onCancel)
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
        if (node.type == WorkflowNodeType.EXECUTE) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                for (action in WorkflowActionType.entries) {
                    val selected = node.actionType == action
                    TextButton(onClick = {
                        // 换动作时**换掉整份 config**：把 notify 的 title/text 留给 shell 只会变成垃圾键，
                        // 而垃圾键会在批准时被校验器点名（用户看不出为什么要改）。
                        onChange(node.copy(config = configForAction(action)))
                    }) {
                        Text(
                            stringResource(actionLabelRes(action)),
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
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

/** 动作目录的一项：新增节点时用它的类型 + 一份**合法**默认 config。 */
private data class NodeKind(val type: WorkflowNodeType, val labelRes: Int, val defaults: Map<String, String>) {
    fun label(): String = type.id
}

/** 能新增的节点类型（覆盖全部六类；缺哪一类都意味着有一类流程用户画不出来）。 */
private val NODE_CATALOG = listOf(
    NodeKind(WorkflowNodeType.EXECUTE, R.string.dsh_workflow_kind_shell, configForAction(WorkflowActionType.SHELL)),
    NodeKind(WorkflowNodeType.EXECUTE, R.string.dsh_workflow_kind_dsh, configForAction(WorkflowActionType.DSH)),
    NodeKind(WorkflowNodeType.EXECUTE, R.string.dsh_workflow_kind_notify, configForAction(WorkflowActionType.NOTIFY)),
    NodeKind(
        WorkflowNodeType.CONDITION, R.string.dsh_workflow_kind_condition,
        mapOf("source" to "exitCode", "op" to "equals", "value" to "0"),
    ),
    NodeKind(WorkflowNodeType.EXTRACT, R.string.dsh_workflow_kind_extract, mapOf("source" to "lastOutput", "mode" to "text", "var" to "value")),
    NodeKind(WorkflowNodeType.WAIT, R.string.dsh_workflow_kind_wait, mapOf("ms" to "1000")),
    NodeKind(WorkflowNodeType.LOGIC, R.string.dsh_workflow_kind_logic, mapOf("op" to "and", "sources" to "")),
)

/** 某个动作的默认 config（切入这个动作时整份替换，避免留下另一个动作的垃圾键）。 */
private fun configForAction(action: WorkflowActionType): Map<String, String> = when (action) {
    WorkflowActionType.SHELL -> mapOf("action" to action.id, "cmd" to "")
    WorkflowActionType.DSH -> mapOf("action" to action.id, "cmd" to "")
    WorkflowActionType.NOTIFY -> mapOf("action" to action.id, "title" to "", "text" to "")
}

private fun actionLabelRes(action: WorkflowActionType): Int = when (action) {
    WorkflowActionType.SHELL -> R.string.dsh_workflow_kind_shell
    WorkflowActionType.DSH -> R.string.dsh_workflow_kind_dsh
    WorkflowActionType.NOTIFY -> R.string.dsh_workflow_kind_notify
}

/** 这个类型允许编辑的 config 键（与 [WorkflowValidator] 认的键一致）。 */
private fun editableKeys(node: WorkflowNode): List<String> = when (node.type) {
    WorkflowNodeType.TRIGGER -> listOf("requireDsh", "autostartDsh")
    // action 由上面的选择器负责，这里只列「值」：把键暴露给自由文本输入等于让用户手打键名。
    WorkflowNodeType.EXECUTE -> when (node.actionType) {
        WorkflowActionType.NOTIFY -> listOf("title", "text")
        else -> listOf("cmd")
    }
    WorkflowNodeType.CONDITION -> listOf("source", "op", "value")
    WorkflowNodeType.LOGIC -> listOf("op", "sources")
    WorkflowNodeType.EXTRACT -> listOf("source", "mode", "pattern", "path", "var")
    WorkflowNodeType.WAIT -> listOf("ms", "untilDsh")
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

/**
 * 审批路径（AI 创建/修改流程时点「编辑」）用的弹窗包装 —— 内容与页面版是同一份。
 *
 * 为什么这条还留着弹窗：审批可以出现在任何前台页面，而编辑器页面是导航图里的一个目的地；
 * 从一个全局弹窗跳到导航页再带回结果，会把「编辑到一半取消/超时」的状态散在两处。所以设置页
 * 那条走真正的页面（WorkflowEditorScreen），审批这条继续用全屏弹窗。
 */
@Composable
internal fun WorkflowEditorDialog(
    initial: WorkflowDefinition,
    title: String,
    onSave: (WorkflowDefinition) -> Unit,
    onCancel: () -> Unit,
) {
    Dialog(
        onDismissRequest = { /* 由内容里的取消按钮与 BackHandler 处理，避免绕过「放弃改动」确认 */ },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            dismissOnBackPress = false,
        ),
    ) {
        WorkflowDefinitionEditor(initial = initial, title = title, onSave = onSave, onCancel = onCancel)
    }
}
