package me.bmax.apatch.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import me.bmax.apatch.dsh.WorkflowActionType
import me.bmax.apatch.dsh.WorkflowNode
import me.bmax.apatch.dsh.WorkflowNodeType
import me.bmax.apatch.dsh.WorkflowPinKind
import me.bmax.apatch.dsh.WorkflowPinSpec
import me.bmax.apatch.dsh.WorkflowSchema
import me.bmax.apatch.dsh.WorkflowStepState

/** 节点卡的固定宽度：连线端点的算法与卡片实宽必须是同一个数，否则线会飘。 */
internal val WorkflowNodeCardWidth = 220.dp

/** 卡片头部（标题那一行）的中心高度：连线从这一行接出去。 */
internal val WorkflowNodeCardAnchorY = 26f

/**
 * 一个节点的卡片（预览、详情、画布共用）。
 *
 * 视觉规范（对齐点击助手的卡片 + Operit 的语义色带）：
 *  - 顶部一条按**节点类型**着色的色带 —— 不是整卡着色：整卡着色会让画布变成调色板，反而读不出层级；
 *  - 外边框按**运行状态**变色（运行中 / 成功 / 失败 / 跳过），「哪一步在跑」不用打开详情；
 *  - 颜色一律取 MaterialTheme 的角色色。硬编码十六进制在这份代码里踩过：画布上直接写 0x22 黑，
 *    深色主题下网格几乎看不见；
 *  - 引脚行左入右出、标签单行省略 —— 针脚文字与卡片边界挤压，正是真机截图上「糊在一起」的直接原因。
 */
@Composable
internal fun WorkflowNodeCard(
    node: WorkflowNode,
    modifier: Modifier = Modifier,
    status: WorkflowStepState? = null,
    selected: Boolean = false,
    /** 点一下（选中/编辑）。 */
    onTap: (() -> Unit)? = null,
    /** 拖动卡片：参数是**本卡片坐标系**里的像素位移（自己接手势就不会算错命中）。 */
    onDrag: ((dxPx: Float, dyPx: Float) -> Unit)? = null,
    /** 开始拖这张卡（宿主用它记录起点、累加未吸附的位移）。 */
    onDragStart: (() -> Unit)? = null,
    /** 拖动结束（宿主在这里吸附网格并落盘）。 */
    onDragEnd: (() -> Unit)? = null,
    /** 上报某个输出引脚在窗口里的矩形（拉线与已连的线都从这根针脚画出去）。 */
    onPinBounds: ((pinKind: String, rect: androidx.compose.ui.geometry.Rect) -> Unit)? = null,
    /** 把自己的窗口矩形报给画布：拉线落点判定只在窗口坐标里比。 */
    onBounds: ((androidx.compose.ui.geometry.Rect) -> Unit)? = null,
    /** 从右侧输出口拉线：anchor 是线头（窗口坐标），pointer 是手指（窗口坐标）。 */
    onPinDrag: ((anchorWindow: Offset, pointerWindow: Offset) -> Unit)? = null,
    /** 拉线松手（窗口坐标）；连到哪由画布判定。 */
    onPinDrop: ((pointerWindow: Offset) -> Unit)? = null,
) {
    val accent = workflowNodeAccent(node.type)
    val borderColor = when (status) {
        WorkflowStepState.RUNNING -> MaterialTheme.colorScheme.primary
        WorkflowStepState.OK -> MaterialTheme.colorScheme.tertiary
        WorkflowStepState.FAILED, WorkflowStepState.TIMEOUT -> MaterialTheme.colorScheme.error
        WorkflowStepState.SKIPPED, WorkflowStepState.CANCELED -> MaterialTheme.colorScheme.outline
        else -> if (selected) accent else MaterialTheme.colorScheme.outlineVariant
    }
    val thick = status != null || selected
    val pins = WorkflowSchema.defaultPinsFor(node)
    val inputs = pins.filter { it.kind == WorkflowPinKind.EXEC_IN || it.kind == WorkflowPinKind.DATA_IN }
    val outputs = pins.filter {
        it.kind == WorkflowPinKind.EXEC_OUT || it.kind == WorkflowPinKind.EXEC_TRUE ||
            it.kind == WorkflowPinKind.EXEC_FALSE || it.kind == WorkflowPinKind.DATA_OUT
    }

    // 卡片**自己**接手势（点击助手的 View 也是各自 onTouchEvent）：
    // 命中不需要算 —— 手指按在这张卡上，事件就到这张卡，不存在「坐标系比错」的可能。
    //  - 点：选中/编辑；拖：移动这张卡；右侧输出口：拉线（见下面的 outputs 区）；
    //  - 顺手把自己的窗口矩形报给画布，供拉线落点判定（窗口坐标对窗口坐标）。
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    var windowOrigin by remember { mutableStateOf(Offset.Zero) }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    val originState = rememberUpdatedState(windowOrigin)
    val sizeState = rememberUpdatedState(cardSize)

    val base = modifier
        .width(WorkflowNodeCardWidth)
        .onGloballyPositioned { coords ->
            windowOrigin = coords.positionInWindow()
            cardSize = coords.size
            onBounds?.invoke(coords.boundsInWindow())
        }
        .focusable()
        .semantics { contentDescription = node.name.ifBlank { workflowNodeLabel(node) } }
        .then(if (onTap != null) Modifier.clickable { onTap() } else Modifier)
        .then(
            if (onDrag != null) {
                Modifier.pointerInput(node.id) {
                    detectDragGestures(
                        onDragStart = { onDragStart?.invoke() },
                        onDragEnd = { onDragEnd?.invoke() },
                        onDragCancel = { onDragEnd?.invoke() },
                    ) { change, amount ->
                        change.consume()
                        onDrag(amount.x, amount.y)
                    }
                }
            } else {
                Modifier
            }
        )

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (thick) 2.dp else 1.dp, borderColor),
        tonalElevation = if (selected) 6.dp else 2.dp,
        modifier = base,
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(4.dp).background(accent))
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    text = node.name.ifBlank { workflowNodeLabel(node) },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = workflowNodeSubtitle(node),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 关键参数直接写在卡片上：卡片上一眼要能读出「这一步做什么」，而不是只有类型名。
                val primary = when (node.type) {
                    WorkflowNodeType.EXECUTE -> node.config("cmd").ifBlank { node.config("text") }
                    WorkflowNodeType.CONDITION -> (node.config("op") + " " + node.config("value")).trim()
                    WorkflowNodeType.EXTRACT -> node.config("var")
                    WorkflowNodeType.WAIT -> node.config("ms")
                    else -> ""
                }
                if (primary.isNotBlank()) {
                    Text(
                        text = primary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
                if (inputs.isNotEmpty() || outputs.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            for (pin in inputs) WorkflowPinRow(pin, alignEnd = false)
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            horizontalAlignment = Alignment.End,
                        ) {
                            for (pin in outputs) {
                                WorkflowPinRow(
                                    pin = pin,
                                    alignEnd = true,
                                    onPinBounds = onPinBounds,
                                    onPinDrag = onPinDrag,
                                    onPinDrop = onPinDrop,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 一个引脚：小圆点 + 标签（执行引脚没有标签，只画点）。 */
@Composable
private fun WorkflowPinRow(
    pin: WorkflowPinSpec,
    alignEnd: Boolean,
    onPinBounds: ((pinKind: String, rect: androidx.compose.ui.geometry.Rect) -> Unit)? = null,
    onPinDrag: ((anchorWindow: Offset, pointerWindow: Offset) -> Unit)? = null,
    onPinDrop: ((pointerWindow: Offset) -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (alignEnd) {
            PinLabel(pin, Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(4.dp))
            if (onPinDrag == null && onPinDrop == null) {
                PinDot(pin)
            } else {
                LinkablePinDot(pin, onPinBounds, onPinDrag, onPinDrop)
            }
        } else {
            PinDot(pin)
            Spacer(Modifier.width(4.dp))
            // weight 是 RowScope 扩展：必须在 Row 的 lambda 里取，不能带进子 composable
            PinLabel(pin, Modifier.weight(1f, fill = false))
        }
    }
}

@Composable
private fun PinDot(pin: WorkflowPinSpec) {
    val exec = pin.kind != WorkflowPinKind.DATA_IN && pin.kind != WorkflowPinKind.DATA_OUT
    Box(
        Modifier
            .size(if (exec) 7.dp else 9.dp)
            .background(
                color = if (exec) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                shape = CircleShape,
            ),
    )
}

/**
 * 可拉线的针脚：**手势挂在这颗粒子上**（不是整列）—— 命中范围就是用户看得见的那颗点。
 *
 * 线头 = 这颗粒子的窗口中心；手指位置 = 粒子窗口原点 + 本粒子局部坐标，两个都在窗口坐标里，
 * 所以拉出来就贴着手指。之前把手势挂在整列上，局部坐标是「列」的而不是卡片的，线头因此偏左。
 */
@Composable
private fun LinkablePinDot(
    pin: WorkflowPinSpec,
    onPinBounds: ((pinKind: String, rect: androidx.compose.ui.geometry.Rect) -> Unit)?,
    onPinDrag: ((anchorWindow: Offset, pointerWindow: Offset) -> Unit)?,
    onPinDrop: ((pointerWindow: Offset) -> Unit)?,
) {
    var dotOrigin by remember { mutableStateOf(Offset.Zero) }
    var dotSize by remember { mutableStateOf(IntSize.Zero) }
    val originState = rememberUpdatedState(dotOrigin)
    val sizeState = rememberUpdatedState(dotSize)
    Box(
        modifier = Modifier
            // 触摸目标比视觉圆点大一圈：手指头比 7dp 的点大得多（点击助手的命中区也放大）。
            .size(26.dp)
            .onGloballyPositioned { coords ->
                dotOrigin = coords.positionInWindow()
                dotSize = coords.size
                onPinBounds?.invoke(pin.kind.name, coords.boundsInWindow())
            }
            .pointerInput(Unit) {
                var pointer = Offset.Zero
                fun anchorWindow(): Offset {
                    val o = originState.value
                    val s = sizeState.value
                    return Offset(o.x + s.width / 2f, o.y + s.height / 2f)
                }
                detectDragGestures(
                    onDragStart = { start ->
                        pointer = originState.value + start
                        onPinDrag?.invoke(anchorWindow(), pointer)
                    },
                    onDragEnd = { onPinDrop?.invoke(pointer) },
                    onDragCancel = { onPinDrop?.invoke(pointer) },
                ) { change, amount ->
                    change.consume()
                    pointer += amount
                    onPinDrag?.invoke(anchorWindow(), pointer)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        PinDot(pin)
    }
}

@Composable
private fun PinLabel(pin: WorkflowPinSpec, modifier: Modifier = Modifier) {
    if (pin.label.isBlank()) return
    Text(
        text = pin.label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** 按节点类型给一条语义色带。取主题角色色，亮/暗都成立。 */
@Composable
internal fun workflowNodeAccent(type: WorkflowNodeType): Color = when (type) {
    WorkflowNodeType.TRIGGER -> MaterialTheme.colorScheme.tertiary
    WorkflowNodeType.EXECUTE -> MaterialTheme.colorScheme.primary
    WorkflowNodeType.CONDITION, WorkflowNodeType.LOGIC -> MaterialTheme.colorScheme.secondary
    WorkflowNodeType.EXTRACT -> MaterialTheme.colorScheme.primaryContainer
    WorkflowNodeType.WAIT -> MaterialTheme.colorScheme.secondaryContainer
}

private fun workflowNodeLabel(node: WorkflowNode): String = when (node.type) {
    WorkflowNodeType.TRIGGER -> "触发"
    WorkflowNodeType.EXECUTE -> node.actionType?.id ?: "execute"
    WorkflowNodeType.CONDITION -> "条件"
    WorkflowNodeType.LOGIC -> "逻辑"
    WorkflowNodeType.EXTRACT -> "取值"
    WorkflowNodeType.WAIT -> "等待"
}

private fun workflowNodeSubtitle(node: WorkflowNode): String = when (node.type) {
    WorkflowNodeType.EXECUTE -> {
        val what = when (node.actionType) {
            WorkflowActionType.NOTIFY -> node.config("text")
            else -> node.config("cmd")
        }
        (node.actionType?.id ?: "execute") + if (what.isBlank()) "" else " · " + what.take(24)
    }
    WorkflowNodeType.CONDITION -> "条件 · " + node.config("op") + " " + node.config("value").take(12)
    WorkflowNodeType.EXTRACT -> "取值 · " + node.config("var")
    WorkflowNodeType.WAIT -> "等待 · " + node.config("ms").ifBlank { node.config("untilDsh") }
    WorkflowNodeType.TRIGGER -> if (node.config("requireDsh") == "1") "触发 · 需要 DSH" else "触发"
    WorkflowNodeType.LOGIC -> "逻辑 · " + node.config("op")
}
