package me.bmax.apatch.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import me.bmax.apatch.dsh.WorkflowDefinition
import me.bmax.apatch.dsh.WorkflowStepState
import kotlin.math.abs
import kotlin.math.max

private const val CARD_W_DP = 220f
private const val CARD_H_FALLBACK_DP = 110f
private const val PIN_ZONE_DP = 34f

private data class Hit(val id: String, val onPin: Boolean)

/**
 * 蓝图式画布：点阵 + 贝塞尔连线 + 节点卡。**所有触摸都在这里判**（与点击助手的 onTouchEvent 同一
 * 种模型：按下点落在哪决定「拖卡 / 拉线 / 平移」，没超过 touch slop 的抬起算「点」）。
 *
 * 为什么不再让卡片自己接手势：卡片 clickable + 卡片拖动 + 画布平移三者会抢触摸（真机表现就是
 * 「卡片拖不动」）；而现在 `pointerInput(Unit)` 保证手势不会因为数据每帧变化而中途重启
 * （之前用 definition/positions 当 key，拖动时检测器被反复取消重建 → 位移被重复应用 → 觉得「快」）。
 *
 * 命中用**实测卡片尺寸**（卡片创建后回传，见 [onMeasured]），再放宽 8dp —— 点击助手的命中区
 * 也刻意大于视觉尺寸；固定高度只作为还没测量时的兜底。
 */
@Composable
internal fun WorkflowBlueprintCanvas(
    definition: WorkflowDefinition,
    positions: Map<String, Offset>,
    readOnly: Boolean,
    modifier: Modifier = Modifier,
    stepStates: Map<String, WorkflowStepState> = emptyMap(),
    selectedNodeId: String? = null,
    onNodeClick: ((String) -> Unit)? = null,
    onNodeDrag: ((id: String, dxPx: Float, dyPx: Float) -> Unit)? = null,
    onNodeAddAfter: ((String) -> Unit)? = null,
    onWireConnect: ((fromId: String, toId: String) -> Unit)? = null,
) {
    val dotColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val edgeColor = MaterialTheme.colorScheme.outline
    val trueColor = MaterialTheme.colorScheme.tertiary
    val falseColor = MaterialTheme.colorScheme.error
    val wireColor = MaterialTheme.colorScheme.primary

    val density = LocalDensity.current.density
    var pan by remember { mutableStateOf(Offset.Zero) }
    var wireFrom by remember { mutableStateOf<String?>(null) }
    var wireEnd by remember { mutableStateOf<Offset?>(null) }
    val sizes = remember { mutableStateMapOf<String, IntSize>() }

    // 回调用 rememberUpdatedState 包住：pointerInput(Unit) 不重启，也不会看到旧闭包。
    val clickState = rememberUpdatedState(onNodeClick)
    val dragState = rememberUpdatedState(onNodeDrag)
    val addAfterState = rememberUpdatedState(onNodeAddAfter)
    val connectState = rememberUpdatedState(onWireConnect)

    fun hitTest(p: Offset): Hit? {
        val tolerance = 8.dp.value
        for (node in definition.nodes.asReversed()) {
            val pos = positions[node.id] ?: continue
            val w = (sizes[node.id]?.width ?: (CARD_W_DP * density).toInt()).toFloat()
            val h = (sizes[node.id]?.height ?: (CARD_H_FALLBACK_DP * density).toInt()).toFloat()
            val x = pos.x * density
            val y = pos.y * density
            val pad = tolerance * density
            if (p.x >= x - pad && p.x <= x + w + pad && p.y >= y - pad && p.y <= y + h + pad) {
                val onPin = p.x >= x + w - (PIN_ZONE_DP * density)
                return Hit(node.id, onPin)
            }
        }
        return null
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)),
    ) {
        // 内容层：平移只在这里应用一次（之前在卡片 offset 里又加了一遍 pan → 双重位移）。
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = pan.x
                    translationY = pan.y
                },
        ) {
            Canvas(Modifier.fillMaxSize()) { drawBlueprintDots(dotColor) }
            Canvas(Modifier.fillMaxSize()) {
                drawBlueprintEdges(definition, positions, edgeColor, trueColor, falseColor)
                val fromId = wireFrom
                val end = wireEnd
                if (fromId != null && end != null) {
                    val from = positions[fromId]
                    if (from != null) {
                        val sx = (from.x * density) + CARD_W_DP * density
                        val sy = (from.y * density) + 26.dp.toPx()
                        val path = Path().apply {
                            moveTo(sx, sy)
                            val ctrl = max(abs(end.x - sx) * 0.5f, 60.dp.toPx())
                            cubicTo(sx + ctrl, sy, end.x - ctrl, end.y, end.x, end.y)
                        }
                        drawPath(path, wireColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                    }
                }
            }
            for (node in definition.nodes) {
                val pos = positions[node.id] ?: continue
                WorkflowNodeCard(
                    node = node,
                    status = stepStates[node.id],
                    selected = selectedNodeId == node.id,
                    modifier = Modifier
                        .offset(x = pos.x.dp, y = pos.y.dp)
                        .onSizeChanged { sizes[node.id] = it },
                )
            }
        }

        // 手势层：一个循环决定一切。
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val hit = hitTest(down.position)
                        var dragging = false
                        var total = 0f
                        var last = down.position
                        var mode = when {
                            readOnly -> "tap"
                            hit == null -> "pan"
                            hit.onPin -> "wire"
                            else -> "drag"
                        }
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUpIgnoreConsumed()) {
                                if (!dragging) {
                                    hit?.let { clickState.value?.invoke(it.id) }
                                } else if (mode == "wire") {
                                    val target = hitTest(change.position)
                                    val fromId = hit?.id
                                    if (fromId != null) {
                                        if (target != null && target.id != fromId) {
                                            connectState.value?.invoke(fromId, target.id)
                                        } else {
                                            addAfterState.value?.invoke(fromId)
                                        }
                                    }
                                }
                                wireFrom = null
                                wireEnd = null
                                break
                            }
                            val delta = change.position - last
                            last = change.position
                            total += abs(delta.x) + abs(delta.y)
                            if (!dragging && total > viewConfiguration.touchSlop) {
                                dragging = true
                                if (mode == "wire") wireFrom = hit?.id
                            }
                            if (dragging) {
                                change.consume()
                                when (mode) {
                                    "drag" -> {
                                        val id = hit?.id
                                        if (id != null) dragState.value?.invoke(id, delta.x, delta.y)
                                    }
                                    "pan" -> pan += delta
                                    "wire" -> wireEnd = change.position
                                    else -> Unit
                                }
                            }
                        }
                    }
                },
        )
    }
}

/** 点阵网格（24dp），随平移一起移动。 */
private fun DrawScope.drawBlueprintDots(color: Color) {
    val step = 24.dp.toPx()
    val radius = 1.dp.toPx()
    var y = step / 2f
    while (y < size.height) {
        var x = step / 2f
        while (x < size.width) {
            drawCircle(color = color, radius = radius, center = Offset(x, y))
            x += step
        }
        y += step
    }
}

/** 三次贝塞尔连线：真分支 tertiary、假分支 error 虚线、默认流 outline 实线。 */
private fun DrawScope.drawBlueprintEdges(
    definition: WorkflowDefinition,
    positions: Map<String, Offset>,
    normal: Color,
    trueColor: Color,
    falseColor: Color,
) {
    val density = this.density
    for (edge in definition.edges) {
        val from = positions[edge.from] ?: continue
        val to = positions[edge.to] ?: continue
        val fromRight = (from.x * density) + CARD_W_DP * density
        val startY = (from.y * density) + 26.dp.toPx()
        val endX = to.x * density
        val endY = (to.y * density) + 26.dp.toPx()
        val ctrl = max(abs(endX - fromRight) * 0.5f, 60.dp.toPx())
        val path = Path().apply {
            moveTo(fromRight, startY)
            cubicTo(fromRight + ctrl, startY, endX - ctrl, endY, endX, endY)
        }
        val color = when (edge.whenExpr) {
            "true" -> trueColor
            "false" -> falseColor
            else -> normal
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = if (edge.whenExpr.isBlank()) 2.5.dp.toPx() else 2.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = if (edge.whenExpr == "false") {
                    PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))
                } else {
                    null
                },
            ),
        )
    }
}
