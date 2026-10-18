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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
                WorkflowDefinitionEditor(
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

/** 一张节点卡：左侧按类型着色的竖条 + 名称 + 类型/动作副标题。 */
@Composable
private fun NodeCard(
    node: WorkflowNode,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onDrag: ((Float, Float) -> Unit)? = null,
    /** 类型名水印：编辑页开着（卡片大、好看），预览/详情里关掉 —— 小卡片上它会和标题挤成两行字。 */
    showWatermark: Boolean = false,
) {
    val accent = nodeColor(node.type)
    val base = modifier.width(NODE_W).height(NODE_H)
    val clickable = if (onClick != null) base.clickable { onClick() } else base
    val draggable = if (onDrag != null) {
        clickable.pointerInput(node.id) {
            detectDragGestures { change, amount ->
                change.consume()
                onDrag(amount.x, amount.y)
            }
        }
    } else {
        clickable
    }
    // 规格：卡片**不按分类着色**（统一 surfaceVariant + 细描边），颜色语义挂在「针脚」上 ——
    // 这里用一个 18dp 圆点承担（未连线是空的，我们暂无连接状态，所以是实心加点描边）；
    // 卡片内容之下叠一个 alpha 0.12 的**水印**（用类型名而不是图标：效果一样，且不必再引一批图标）；
    // 选中 = 换底色 secondaryContainer（规格里唯一的选中表达）。
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(if (selected) 1.dp else 0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = if (selected) 6.dp else 3.dp,
        modifier = draggable,
    ) {
        Box {
            if (showWatermark) {
                Text(
                    node.type.id,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Box(
                    Modifier
                        .size(18.dp)
                        .background(accent, CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        node.name.ifBlank { nodeLabel(node) },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                    )
                    Text(
                        nodeSubtitle(node),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * 画布网格：方格线（规格：12dp 单元格、每 10 格加粗），颜色取主题 outline 压暗。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGrid(
    normal: androidx.compose.ui.graphics.Color,
    strong: androidx.compose.ui.graphics.Color,
) {
    // 方格线而不是点阵：点击助手的画布是 12dp 方格（源码 GRID_DP_SIZE=12），普通线 0.5px、
    // 每 10 格加粗 2px。加粗那一档让画布有尺度感，不至于看成一片细线。
    val step = CANVAS_STEP_DP.dp.toPx()
    val thin = 0.5f
    val thick = 2f
    var i = 0
    var x = 0f
    while (x < size.width) {
        drawLine(if (i % 10 == 0) strong else normal, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = if (i % 10 == 0) thick else thin)
        i++
        x += step
    }
    i = 0
    var y = 0f
    while (y < size.height) {
        drawLine(if (i % 10 == 0) strong else normal, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = if (i % 10 == 0) thick else thin)
        i++
        y += step
    }
}

/** 正交连线（横-竖-横）：比直线更容易看清「谁接谁」，也便于在中段放分支标签。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWorkflowEdges(
    placed: List<PlacedNode>,
    definition: WorkflowDefinition,
    color: androidx.compose.ui.graphics.Color,
) {
    val byId = placed.associateBy { it.id }
    for (e in definition.edges) {
        val from = byId[e.from] ?: continue
        val to = byId[e.to] ?: continue
        val x1 = (from.x + 148f).dp.toPx()
        val y1 = (from.y + 23f).dp.toPx()
        val x2 = to.x.dp.toPx()
        val y2 = (to.y + 23f).dp.toPx()
        val midX = (x1 + x2) / 2f
        // 3dp + 圆角 + 圆头圆角连接：规格里线宽是 gridSize/4，且**没有箭头也没有端点圆点**
        // —— 分支语义由源卡片底部的针脚标签承担，线上不写字。
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(x1, y1)
            lineTo(midX, y1)
            lineTo(midX, y2)
            lineTo(x2, y2)
        }
        drawPath(
            path = path,
            color = color,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 3.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
                pathEffect = androidx.compose.ui.graphics.PathEffect.cornerPathEffect(16f),
            ),
        )
    }
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

/**
 * 分支标签的位置：贴在**源卡片的底部**（规格里 true/false 就是卡片底部的针脚标题）。
 */
private data class EdgeLabel(val text: String, val x: Float, val y: Float)

private fun edgeLabels(placed: List<PlacedNode>, definition: WorkflowDefinition): List<EdgeLabel> {
    val byId = placed.associateBy { it.id }
    // 贴在**源卡片底部**（规格里 true/false 就是卡片底部的针脚标题），而不是浮在连线中段：
    // 一条线属于哪个节点、走哪个分支，一眼就能对上。
    val out = mutableListOf<EdgeLabel>()
    for (p in placed) {
        val labels = definition.outgoing(p.id).filter { it.whenExpr.isNotBlank() }
        for ((i, e) in labels.withIndex()) {
            out += EdgeLabel(e.whenExpr, p.x + i * 46f, p.y + 48f)
        }
    }
    return out
}

/** 节点副标题：类型 + 动作/关键配置，让用户一眼看出这一步做什么。 */
private fun nodeSubtitle(node: WorkflowNode): String = when (node.type) {
    WorkflowNodeType.TRIGGER -> if (node.config("requireDsh") == "1") "trigger · 需要 DSH" else "trigger"
    WorkflowNodeType.EXECUTE -> (node.actionType?.id ?: "execute") + " · " +
        node.config("cmd").ifBlank { node.config("text") }.take(18)
    WorkflowNodeType.CONDITION -> "condition · " + node.config("op") + " " + node.config("value").take(10)
    WorkflowNodeType.LOGIC -> "logic · " + node.config("op")
    WorkflowNodeType.EXTRACT -> "extract · " + node.config("var")
    WorkflowNodeType.WAIT -> "wait · " + node.config("ms").ifBlank { node.config("untilDsh") }
}

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
    var draft by remember { mutableStateOf(initial) }
    var selected by remember { mutableStateOf<String?>(null) }
    var linkFrom by remember { mutableStateOf<String?>(null) }
    var linkWhen by remember { mutableStateOf("") }
    var confirmDiscard by remember { mutableStateOf(false) }
    val def = draft
    // 差异在组合期算成不可变列表：写 state 会造成重组循环。
    val patchLines: List<WorkflowPatch> = remember(def) { workflowDiff(initial, def) }
    val errors: List<String> = remember(def) {
        WorkflowValidator.validate(def, DshNativeBridge.Cap.entries.map { it.id }.toSet()).errors
    }

    // 自己的**全屏窗口**：确认弹窗（AI 创建流程那条路）里点「编辑」时，编辑器若只是弹窗内容树里
    // 的一个 Surface，就会被那个弹窗的窗口裁成小窗（真机反馈「不要再小窗编辑」）。独立 Dialog 之后
    // 两条入口（确认弹窗 / 设置页）拿到的都是整页。
    Dialog(
        onDismissRequest = { if (patchLines.isEmpty()) onCancel() else confirmDiscard = true },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            dismissOnBackPress = true,
        ),
    ) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.dsh_workflow_editor_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            // 画布：与预览**同一套组件**（点阵 / 贝塞尔 / 缩放平移），只是可编辑。
            // 拖动的结果写回定义（posX/posY）—— 摆位不落盘，用户摆好的图下次打开就自己跳。
            // 画布吃满剩余高度：编辑页的主体就是画布，下面是工具与属性面板。
            val placed = layoutNodes(def)
            val density = LocalDensity.current
            WorkflowBlueprintCanvas(
                definition = def,
                positions = placed.associate { it.id to Offset(it.x, it.y) },
                readOnly = false,
                zoomable = true,
                selectedNodeId = selected,
                modifier = Modifier.fillMaxWidth().weight(1f),
                onNodeClick = { id ->
                    val from = linkFrom
                    if (from == null) {
                        selected = if (selected == id) null else id
                    } else if (from != id) {
                        draft = def.copy(edges = def.edges + WorkflowEdge(from, id, linkWhen))
                        linkFrom = null
                        linkWhen = ""
                    }
                },
                onNodeDrag = { id, dxPx, dyPx ->
                    // 手势给的是**像素**，位置量纲是 dp：先在密度里换算，再吸附到 12dp 网格。
                    val dxDp = with(density) { dxPx.toDp().value }
                    val dyDp = with(density) { dyPx.toDp().value }
                    val node = def.nodes.firstOrNull { it.id == id }
                    if (node != null) {
                        val nx = snapGrid(node.posX + dxDp).toInt().coerceIn(0, 600)
                        val ny = snapGrid(node.posY + dyDp).toInt().coerceIn(0, 900)
                        draft = def.copy(
                            nodes = def.nodes.map {
                                if (it.id == id) it.copy(posX = nx, posY = ny) else it
                            },
                        )
                    }
                },
            )
            if (linkFrom != null) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.dsh_workflow_editor_linking, linkFrom.orEmpty()),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { linkFrom = null }) {
                            Text(stringResource(R.string.dsh_workflow_action_cancel))
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            // 提示单独一行：原先它和下面那排「横向滚动」的按钮同处一个 Row，权重被无限宽的
            // 滚动容器抢光，于是被压成一列竖排的字（真机截图）。
            Text(
                stringResource(R.string.dsh_workflow_editor_drag_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(R.string.dsh_workflow_editor_add_node),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                // 动作目录：每类都带**合法默认 config**。默认值不只是省事 —— 用户拿到的是
                // 「把 equals 改成 contains」这种改值动作，而不是「自己敲对键名」（敲错要等到
                // 批准那一刻才被校验器拦下，那时他已经改了五分钟）。
                for (kind in NODE_CATALOG) {
                    TextButton(onClick = {
                        val id = "n" + (def.nodes.size + 1)
                        draft = def.copy(
                            nodes = def.nodes + WorkflowNode(
                                id = id,
                                type = kind.type,
                                name = kind.label(),
                                posX = 16 + (def.nodes.size % 4) * 90,
                                posY = 16 + (def.nodes.size / 4) * 44,
                                config = kind.defaults,
                            ),
                        )
                    }) {
                        Text(stringResource(kind.labelRes))
                    }
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
            if (errors.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.dsh_workflow_editor_errors),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    errors.take(5).joinToString("\n"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = {
                    // 有改动就先问清楚「要不要丢掉」：编辑页的取消键和保存键挨在一起，
                    // 一键点掉等于把用户改了几分钟的东西无声清空。
                    if (patchLines.isEmpty()) onCancel() else confirmDiscard = true
                }) { Text(stringResource(R.string.dsh_workflow_action_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSave(def) }) {
                    Text(stringResource(R.string.dsh_workflow_editor_save))
                }
            }
        }
    }

    // 放在覆盖层内部：与编辑页同一棵组合树，否则「放弃改动」的弹窗会被全屏 Surface 盖住。
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
                TextButton(onClick = {
                    confirmDiscard = false
                    onCancel()
                }) { Text(stringResource(R.string.dsh_workflow_editor_discard_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text(stringResource(R.string.dsh_workflow_editor_keep_editing))
                }
            },
        )
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
