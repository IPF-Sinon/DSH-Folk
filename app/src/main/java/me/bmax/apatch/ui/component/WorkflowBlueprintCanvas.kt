package me.bmax.apatch.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import me.bmax.apatch.dsh.WorkflowDefinition
import me.bmax.apatch.dsh.WorkflowStepState
import kotlin.math.abs
import kotlin.math.max

private const val CARD_W_DP = 220f

/**
 * 蓝图式画布：点阵 + 贝塞尔连线 + 节点卡。
 *
 * 手势分工（对齐点击助手：View 各自 onTouchEvent，容器只管空白处平移）：
 *  - **卡片自己**接点/拖/拉线（见 [WorkflowNodeCard]）—— 不需要手算命中，也就不会再出现
 *    「只有屏幕左上角能点到」这种坐标系比错的 bug；
 *  - 画布只接「按在空白处」的拖动 → 平移；空白处点一下 → [onBlankTap]（取消选中）；
 *  - 拉线的落点判定只在**窗口坐标**里比（卡片报上来的窗口矩形 vs 手指窗口坐标），
 *    连线也画在不随平移的覆盖层上，所以线永远跟手。
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
    onNodeDragStart: ((id: String) -> Unit)? = null,
    onNodeDragEnd: ((id: String) -> Unit)? = null,
    /** 拉线松手：toId 非空 = 连到那张卡；为空 = 落在空白（调用方弹节点菜单）。 */
    onWireDrop: ((fromId: String, toId: String?) -> Unit)? = null,
    onBlankTap: (() -> Unit)? = null,
) {
    val dotColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val edgeColor = MaterialTheme.colorScheme.outline
    val trueColor = MaterialTheme.colorScheme.tertiary
    val falseColor = MaterialTheme.colorScheme.error
    val wireColor = MaterialTheme.colorScheme.primary

    var pan by remember { mutableStateOf(Offset.Zero) }
    // 画布在窗口里的位置：连线端点要把窗口坐标换算回画布局部坐标。
    var origin by remember { mutableStateOf(Offset.Zero) }
    // 卡片窗口矩形（卡片自己报上来）：拉线落点只在这里比，坐标口径唯一。
    var bounds by remember { mutableStateOf<Map<String, Rect>>(emptyMap()) }
    // 画布的空白点击与拉线回调也要最新值（手势块的 key 不会因回调变化而重启）。
    val blankTapCb = rememberUpdatedState(onBlankTap)
    val wireDropCb = rememberUpdatedState(onWireDrop)
    var wireFrom by remember { mutableStateOf<String?>(null) }
    var wireAnchor by remember { mutableStateOf(Offset.Zero) }
    var wirePointer by remember { mutableStateOf(Offset.Zero) }
    // 针脚窗口矩形，键 "节点id:引脚类型名"：已连的线也从真针脚画出去，而不是卡片右上角。
    var pinRects by remember { mutableStateOf<Map<String, Rect>>(emptyMap()) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f))
            .onGloballyPositioned { origin = it.positionInWindow() }
            .then(
                if (readOnly) {
                    // 只读（预览）：只有点空白取消选中，没有平移。
                    Modifier.pointerInput(Unit) {
                        detectTapGestures { blankTapCb.value?.invoke() }
                    }
                } else {
                    Modifier
                        .pointerInput(Unit) {
                            // 空白处（卡片没有消费事件时才会到这里）拖动 = 平移画布。
                            detectDragGestures { change, amount ->
                                change.consume()
                                pan += amount
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures { blankTapCb.value?.invoke() }
                        }
                },
            ),
    ) {
        // 内容层：平移只在这里应用一次。
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
                drawBlueprintEdges(
                    definition = definition,
                    positions = positions,
                    pinRects = pinRects,
                    origin = origin,
                    pan = pan,
                    normal = edgeColor,
                    trueColor = trueColor,
                    falseColor = falseColor,
                )
            }
            for (node in definition.nodes) {
                val pos = positions[node.id] ?: continue
                WorkflowNodeCard(
                    node = node,
                    status = stepStates[node.id],
                    selected = selectedNodeId == node.id,
                    modifier = Modifier.offset(x = pos.x.dp, y = pos.y.dp),
                    onTap = { onNodeClick?.invoke(node.id) },
                    onDrag = { dx, dy -> onNodeDrag?.invoke(node.id, dx, dy) },
                    onDragStart = { onNodeDragStart?.invoke(node.id) },
                    onDragEnd = { onNodeDragEnd?.invoke(node.id) },
                    onBounds = { rect -> bounds = bounds + (node.id to rect) },
                    onPinBounds = { pinKind, rect -> pinRects = pinRects + ("${node.id}:$pinKind" to rect) },
                    onPinDrag = { anchor, pointer ->
                        wireFrom = node.id
                        wireAnchor = anchor
                        wirePointer = pointer
                    },
                    onPinDrop = { pointer ->
                        val from = wireFrom
                        wireFrom = null
                        if (from != null) {
                            val target = bounds.entries.firstOrNull { it.key != from && it.value.contains(pointer) }?.key
                            wireDropCb.value?.invoke(from, target)
                        }
                    },
                )
            }
        }

        // 拉线覆盖层：**不随平移**，两端都是「窗口坐标 - 画布原点」，所以始终贴着手指。
        if (wireFrom != null) {
            Canvas(Modifier.fillMaxSize()) {
                val a = wireAnchor - origin
                val b = wirePointer - origin
                val ctrl = max(abs(b.x - a.x) * 0.5f, 60.dp.toPx())
                val path = Path().apply {
                    moveTo(a.x, a.y)
                    cubicTo(a.x + ctrl, a.y, b.x - ctrl, b.y, b.x, b.y)
                }
                drawPath(path, wireColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                drawCircle(wireColor, radius = 5.dp.toPx(), center = b)
            }
        }
    }
}

/** 点阵网格（24dp）。 */
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
    pinRects: Map<String, Rect>,
    origin: Offset,
    pan: Offset,
    normal: Color,
    trueColor: Color,
    falseColor: Color,
) {
    val density = this.density
    for (edge in definition.edges) {
        val from = positions[edge.from] ?: continue
        val to = positions[edge.to] ?: continue
        // 线的起点：优先用真针脚的窗口矩形（换算回内容层局部坐标），没有就退回卡片右上角。
        val pinKind = when (edge.whenExpr) {
            "true" -> "EXEC_TRUE"
            "false" -> "EXEC_FALSE"
            else -> "EXEC_OUT"
        }
        val pinned = pinRects["${edge.from}:$pinKind"]
        val fromRight = if (pinned != null) pinned.right - origin.x - pan.x else (from.x * density) + CARD_W_DP * density
        val startY = if (pinned != null) pinned.center.y - origin.y - pan.y else (from.y * density) + WorkflowNodeCardAnchorY * density
        val endX = to.x * density
        val endY = (to.y * density) + WorkflowNodeCardAnchorY * density
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
