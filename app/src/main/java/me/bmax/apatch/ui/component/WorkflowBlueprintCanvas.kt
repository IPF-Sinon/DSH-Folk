package me.bmax.apatch.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import me.bmax.apatch.dsh.WorkflowDefinition
import me.bmax.apatch.dsh.WorkflowStepState
import kotlin.math.abs
import kotlin.math.max

/**
 * 蓝图式画布：点阵网格 + 贝塞尔连线 + 节点卡。
 *
 * 预览、详情与编辑共用同一份画法（[WorkflowNodeCard] 画卡，这里画背景与线），
 * 差别只在 [readOnly]：预览/详情不响应拖动与点选，只负责看。
 *
 * 为什么坐标由调用方以 `Map<String, Offset>`（dp 值）传进来：摆位规则（有落盘坐标就用、
 * 压住了就退回自动分层）已经在编辑器那边实现过一次，画布不该再有一套自己的布局 —— 两套
 * 布局是「预览里看到的图和编辑器里摆的位置不一致」的根源。
 *
 * [zoomable] 只在编辑页开：双指缩放、单指平移；预览页保持不动，避免与页面滚动抢手势。
 */
@Composable
internal fun WorkflowBlueprintCanvas(
    definition: WorkflowDefinition,
    positions: Map<String, Offset>,
    readOnly: Boolean,
    modifier: Modifier = Modifier,
    stepStates: Map<String, WorkflowStepState> = emptyMap(),
    selectedNodeId: String? = null,
    zoomable: Boolean = false,
    onNodeClick: ((String) -> Unit)? = null,
    onNodeDrag: ((String, Float, Float) -> Unit)? = null,
) {
    // 主题色必须在 Canvas 之外取：Canvas 的 lambda 是 DrawScope，不是 composable 作用域。
    val dotColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val edgeColor = MaterialTheme.colorScheme.outline
    val trueColor = MaterialTheme.colorScheme.tertiary
    val falseColor = MaterialTheme.colorScheme.error

    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    val contentW = max(positions.values.maxOfOrNull { it.x } ?: 0f, 320f) + 260f
    val contentH = max(positions.values.maxOfOrNull { it.y } ?: 0f, 220f) + 120f

    Box(
        modifier = modifier
            .fillMaxWidth()
            // 高度交给调用方：预览是固定 340dp，编辑页是 weight(1f) 吃满剩余空间。
            // 自带高度会和 weight 打架，画布要么被固定死、要么留一片空白。
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f))
            .then(
                if (zoomable) {
                    Modifier.pointerInput(Unit) {
                        detectTransformGestures { _, panChange, zoomChange, _ ->
                            scale = (scale * zoomChange).coerceIn(0.45f, 2f)
                            pan += panChange
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        Box(
            Modifier
                .width(contentW.dp)
                .height(contentH.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = pan.x
                    translationY = pan.y
                    transformOrigin = TransformOrigin(0f, 0f)
                },
        ) {
            Canvas(Modifier.fillMaxSize()) { drawBlueprintDots(dotColor) }
            Canvas(Modifier.fillMaxSize()) { drawBlueprintEdges(definition, positions, edgeColor, trueColor, falseColor) }
            for (node in definition.nodes) {
                val pos = positions[node.id] ?: continue
                WorkflowNodeCard(
                    node = node,
                    status = stepStates[node.id],
                    selected = selectedNodeId == node.id,
                    modifier = Modifier.offset(x = pos.x.dp, y = pos.y.dp),
                    onClick = if (readOnly) null else onNodeClick?.let { cb -> { cb(node.id) } },
                    onDrag = if (readOnly) {
                        null
                    } else {
                        onNodeDrag?.let { cb -> { dx: Float, dy: Float -> cb(node.id, dx, dy) } }
                    },
                )
            }
        }
    }
}

/** 点阵网格（24dp 步长）。点比线更适合小屏：不会把卡片之间的空隙填满。 */
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

/** 三次贝塞尔连线：真分支用 tertiary、假分支用 error 虚线，默认流用 outline 实线。 */
private fun DrawScope.drawBlueprintEdges(
    definition: WorkflowDefinition,
    positions: Map<String, Offset>,
    normal: Color,
    trueColor: Color,
    falseColor: Color,
) {
    for (edge in definition.edges) {
        val from = positions[edge.from] ?: continue
        val to = positions[edge.to] ?: continue
        val startY = (from.y + WorkflowNodeCardAnchorY).dp.toPx()
        val endX = to.x.dp.toPx()
        val endY = (to.y + WorkflowNodeCardAnchorY).dp.toPx()
        // 端点从卡片右缘出发：用卡片宽度换成 x
        val fromRight = (from.x + WorkflowNodeCardWidth.value).dp.toPx()
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
