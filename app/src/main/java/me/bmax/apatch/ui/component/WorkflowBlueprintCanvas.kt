package me.bmax.apatch.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import me.bmax.apatch.dsh.WorkflowDefinition
import me.bmax.apatch.dsh.WorkflowStepState
import kotlin.math.abs
import kotlin.math.max

/** 卡片尺寸常量必须和 [WorkflowNodeCard] 一致，否则命中判定与连线端点都会偏。 */
private const val CARD_W_DP = 220f
private const val CARD_H_DP = 92f

/** 右侧这个宽度内按下并拖出 = 从输出口「拉线」，不是拖动卡片。 */
private const val PIN_ZONE_DP = 34f

/**
 * 蓝图式画布：点阵网格 + 贝塞尔连线 + 节点卡，**手势统一在这里处理**。
 *
 * 为什么手势放在画布而不是卡片里：卡片上有 clickable + 自己的拖动手势，画布上又有平移/缩放手势，
 * 三者会抢触摸（真机表现就是「卡片拖不动」）。现在只留两处：
 *  - 画布：单指按在卡片上 → 拖动这张卡片；按在空白 → 平移整张画布；缩放留给双指（只读画布不开）；
 *  - 卡片：只管「点一下」（选中/编辑）。
 * 从右侧输出口按下并拖出 → 松手时回调 [onNodeAddAfter]（弹出「接哪个节点」的菜单），对应点击助手
 * 的「从针脚拉出下一个动作」。
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
    onNodeDrag: ((String, Float, Float) -> Unit)? = null,
    onNodeAddAfter: ((String) -> Unit)? = null,
) {
    // 主题色在 Canvas 之外取：Canvas 的 lambda 是 DrawScope。
    val dotColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val edgeColor = MaterialTheme.colorScheme.outline
    val trueColor = MaterialTheme.colorScheme.tertiary
    val falseColor = MaterialTheme.colorScheme.error

    var pan by remember { mutableStateOf(Offset.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density

    // 命中判定要按「当前屏上的位置」算：positions 是 dp，画布也按 dp 偏移，比例恒为 1（暂不做缩放）。
    fun hitTest(p: Offset): Pair<String, Boolean>? {
        for (node in definition.nodes.asReversed()) {
            val pos = positions[node.id] ?: continue
            val x = pos.x * density
            val y = pos.y * density
            if (p.x >= x && p.x <= x + CARD_W_DP * density && p.y >= y && p.y <= y + CARD_H_DP * density) {
                val inPinZone = p.x >= x + (CARD_W_DP - PIN_ZONE_DP) * density
                return node.id to inPinZone
            }
        }
        return null
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f))
            // 点：选中卡片（编辑与只读都需要）；拖：见下面那条。
            .pointerInput(definition, positions) {
                detectTapGestures { p -> hitTest(p)?.let { onNodeClick?.invoke(it.first) } }
            }
            .then(
                if (readOnly) Modifier else Modifier.pointerInput(definition, positions) {
                    // 单指：卡片上 = 拖动卡片；输出口区 = 拉线（松手弹菜单）；空白 = 平移画布。
                    var dragId: String? = null
                    var pinId: String? = null
                    var total = 0f
                    detectDragGestures(
                        onDragStart = { start ->
                            total = 0f
                            val hit = hitTest(start)
                            dragId = hit?.first
                            pinId = if (hit?.second == true) hit.first else null
                        },
                        onDragEnd = {
                            if (pinId != null && total < 24f) onNodeAddAfter?.invoke(pinId!!)
                            dragId = null
                            pinId = null
                        },
                        onDragCancel = { dragId = null; pinId = null },
                    ) { change, amount ->
                        change.consume()
                        total += abs(amount.x) + abs(amount.y)
                        if (dragId != null && pinId == null) {
                            onNodeDrag?.invoke(dragId!!, amount.x, amount.y)
                        } else if (dragId == null) {
                            pan += amount
                        }
                    }
                },
            ),
    ) {
        Box(Modifier.fillMaxSize().offset { androidx.compose.ui.unit.IntOffset(pan.x.toInt(), pan.y.toInt()) }) {
            Canvas(Modifier.fillMaxSize()) { drawBlueprintDots(dotColor, pan) }
            Canvas(Modifier.fillMaxSize()) { drawBlueprintEdges(definition, positions, pan, edgeColor, trueColor, falseColor) }
            for (node in definition.nodes) {
                val pos = positions[node.id] ?: continue
                WorkflowNodeCard(
                    node = node,
                    status = stepStates[node.id],
                    selected = selectedNodeId == node.id,
                    modifier = Modifier.offset((pos.x + pan.x / density).dp, (pos.y + pan.y / density).dp),
                )
            }
        }
    }
}

/** 点阵网格（24dp 步长），随平移一起移动，看起来像一张可拖的纸。 */
private fun DrawScope.drawBlueprintDots(color: Color, pan: Offset) {
    val step = 24.dp.toPx()
    val radius = 1.dp.toPx()
    val ox = pan.x.mod(step)
    val oy = pan.y.mod(step)
    var y = oy
    while (y < size.height) {
        var x = ox
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
    pan: Offset,
    normal: Color,
    trueColor: Color,
    falseColor: Color,
) {
    val density = this.density
    for (edge in definition.edges) {
        val from = positions[edge.from] ?: continue
        val to = positions[edge.to] ?: continue
        val fromRight = (from.x * density + CARD_W_DP * density + pan.x)
        val startY = (from.y * density + 26.dp.toPx() + pan.y)
        val endX = (to.x * density + pan.x)
        val endY = (to.y * density + 26.dp.toPx() + pan.y)
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
