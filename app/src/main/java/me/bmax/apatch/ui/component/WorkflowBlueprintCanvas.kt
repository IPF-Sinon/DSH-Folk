package me.bmax.apatch.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import me.bmax.apatch.dsh.WorkflowActionCatalog
import me.bmax.apatch.dsh.WorkflowNode
import me.bmax.apatch.dsh.WorkflowDefinition
import me.bmax.apatch.dsh.WorkflowEdgeKind
import me.bmax.apatch.dsh.WorkflowStepState
import kotlin.math.abs
import kotlin.math.max

private const val CARD_W_DP = 220f

/** 视口手势块的稳定 key：只用于让 pointerInput 不随重组重启。 */
private const val UNIT_VIEWPORT_GESTURE_KEY = "workflow-canvas-viewport"

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
    /** 正在被拖动的那张卡：它周围要画一圈虚线选中框（对齐点击助手）。 */
    draggingNodeId: String? = null,
    /**
     * 已授予的能力 id 集合。卡片据此把自己标成"不可用"。
     *
     * 传集合而不是每张卡一个布尔：同一张卡的能力要求是从动作目录推出来的，
     * 而"哪些已授予"是全局事实 —— 把两者在画布入口合并一次，卡片就只管显示。
     */
    grantedCaps: Set<String> = emptySet(),
    onNodeClick: ((String) -> Unit)? = null,
    onNodeDrag: ((id: String, dxPx: Float, dyPx: Float) -> Unit)? = null,
    onNodeDragStart: ((id: String) -> Unit)? = null,
    onNodeDragEnd: ((id: String) -> Unit)? = null,
    /**
     * 拉线松手：toId 非空 = 连到那张卡；为空 = 落在空白（调用方弹节点菜单）。
     * pointer 是松手时的**窗口坐标** —— 菜单里选了节点类型后，新节点要落在松手的位置。
     */
    /**
     * 拉线松手。
     *
     * [fromPin] 是**拉出来的那个针脚的种类名**（[WorkflowPinKind] 的 name）：从「成立」口拉出去
     * 和从「不成立」口拉出去，落盘的 whenExpr 不一样。之前这里只有 fromId，于是 condition 的
     * 两条分支画出来全变成默认边 —— 判断节点在真实执行时永远只走其中一条，而用户看到的图
     * 上有两条线。
     */
    onWireDrop: ((fromId: String, fromPin: String, toId: String?, pointerWindow: Offset) -> Unit)? = null,
    onBlankTap: (() -> Unit)? = null,
    /**
     * 视口（缩放 + 平移）。由**宿主**持有而不是画布内部：落点、吸附、新节点坐标都要用同一套
     * 逆变换（见 [WorkflowViewport]），两处各存一份就一定会算出两个不同的落点。
     */
    viewport: WorkflowViewport = WorkflowViewport(),
    onViewportChange: ((WorkflowViewport) -> Unit)? = null,
) {
    val dotColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val edgeColor = MaterialTheme.colorScheme.outline
    val trueColor = MaterialTheme.colorScheme.tertiary
    val falseColor = MaterialTheme.colorScheme.error
    val wireColor = MaterialTheme.colorScheme.primary

    val pan = viewport.pan
    val scale = viewport.scale
    val viewportCb = rememberUpdatedState(onViewportChange)
    // 手势块里的 `viewport` **必须是这个**，不能直接读参数。
    //
    // pointerInput 的 key 是常量，块只会组合一次；它捕获的是**第一次组合时**的 viewport。
    // 直接读参数的话，每一帧都拿初始 scale 去算 zoomAround，于是缩放永远停在第一帧算出来的
    // 那个比例上、来回闪（真机表现就是「卡在 89% 闪烁」）。rememberUpdatedState 让块里
    // 读到的是最新值。
    val viewportState = rememberUpdatedState(viewport)
    // 画布在窗口里的位置：连线端点要把窗口坐标换算回画布局部坐标。
    var origin by remember { mutableStateOf(Offset.Zero) }
    // 卡片窗口矩形（卡片自己报上来）：拉线落点只在这里比，坐标口径唯一。
    var bounds by remember { mutableStateOf<Map<String, Rect>>(emptyMap()) }
    // 画布的空白点击与拉线回调也要最新值（手势块的 key 不会因回调变化而重启）。
    val blankTapCb = rememberUpdatedState(onBlankTap)
    val wireDropCb = rememberUpdatedState(onWireDrop)
    var wireFrom by remember { mutableStateOf<String?>(null) }
    // 拉线起点那个针脚的种类（"EXEC_TRUE" / "EXEC_FALSE" / …），决定落盘时带哪个 when。
    var wireFromPin by remember { mutableStateOf("") }
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
                        .pointerInput(UNIT_VIEWPORT_GESTURE_KEY) {
                            // 空白处（卡片没有消费事件时才会到这里）拖 = 平移、双指 = 缩放。
                            // 与点击助手一致：范围 0.2–2.0，超出即夹住，不报错也不回弹。
                            detectTransformGestures { centroid, panChange, zoomChange, _ ->
                                // 围绕手势中心缩放，并叠上这帧的平移 —— 否则手指按住的那个点会跑。
                                viewportCb.value?.invoke(
                                    viewportState.value.zoomAround(centroid, zoomChange, panChange),
                                )
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
                    // 变换顺序 = 先按左上角缩放、再平移，于是「屏幕 = 世界 × scale + pan」。
                    // 逆变换只有一处（[WorkflowViewport.toWorldDp]），落点/吸附都由它算。
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale
                    scaleY = scale
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
                    scale = scale,
                    normal = edgeColor,
                    trueColor = trueColor,
                    falseColor = falseColor,
                    dataColor = edgeColor,
                )
            }
            // 选中/拖动的卡画一圈虚线框。
            //
            // 为什么要有它：拖动时卡片跟着手指走，但它**是不是被选中了**没有任何视觉反馈 ——
            // 松手之后才发现"我动的是另一张卡"是这类编辑器里最常见的误操作。虚线框用的是
            // 与卡片自身尺寸无关的固定 padding，所以缩放时框也跟着缩放（它画在内容层里）。
            val framed = draggingNodeId ?: selectedNodeId
            if (framed != null) {
                val p = positions[framed]
                if (p != null) {
                    Canvas(Modifier.fillMaxSize()) {
                        val pad = 8.dp.toPx()
                        val w = CARD_W_DP * density
                        val h = 96.dp.toPx()
                        drawRect(
                            color = wireColor,
                            topLeft = Offset(p.x * density - pad, p.y * density - pad),
                            size = androidx.compose.ui.geometry.Size(w + pad * 2, h + pad * 2),
                            style = Stroke(
                                width = 2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(
                                    floatArrayOf(10.dp.toPx(), 7.dp.toPx()),
                                ),
                            ),
                        )
                    }
                }
            }
            for (node in definition.nodes) {
                val pos = positions[node.id] ?: continue
                WorkflowNodeCard(
                    node = node,
                    status = stepStates[node.id],
                    // 能力与授予情况由宿主算好传下来：画布每帧重绘，不能在这里查权限。
                    requiredCaps = requiredCapsOf(node),
                    granted = requiredCapsOf(node).all { it in grantedCaps },
                    selected = selectedNodeId == node.id,
                    modifier = Modifier.offset(x = pos.x.dp, y = pos.y.dp),
                    onTap = { onNodeClick?.invoke(node.id) },
                    onDrag = { dx, dy -> onNodeDrag?.invoke(node.id, dx, dy) },
                    onDragStart = { onNodeDragStart?.invoke(node.id) },
                    onDragEnd = { onNodeDragEnd?.invoke(node.id) },
                    onBounds = { rect -> bounds = bounds + (node.id to rect) },
                    onPinBounds = { pinKind, rect -> pinRects = pinRects + ("${node.id}:$pinKind" to rect) },
                    onPinDrag = { pinKind, anchor, pointer ->
                        wireFrom = node.id
                        wireFromPin = pinKind
                        wireAnchor = anchor
                        wirePointer = pointer
                    },
                    onPinDrop = { pointer ->
                        val from = wireFrom
                        val fromPin = wireFromPin
                        wireFrom = null
                        wireFromPin = ""
                        if (from != null) {
                            val target = bounds.entries.firstOrNull { it.key != from && it.value.contains(pointer) }?.key
                            wireDropCb.value?.invoke(from, fromPin, target, pointer)
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

/**
 * 数据边引脚 → 画布内部用的针脚键。
 *
 * 数据边的 fromPin 有三种写法（`step:<id>` / `var:<name>` / 裸参数名）。
 * 卡片上报针脚时用的是**节点自己的引脚种类名**，两者必须在这里对齐一次 ——
 * 对齐错了的表现是"线从卡片右上角飞出去"，看起来像连错，实际只是键没对上。
 */
private fun dataPinKeyOf(fromPin: String): String = when {
    fromPin.contains(':') -> fromPin.substringAfter(':')
    else -> fromPin
}

/**
 * 这张卡需要的系统能力 id。
 *
 * 用 [WorkflowActionCatalog] 而不是读 config：能力是**动作定义的一部分**，
 * 写进 config 就等于让用户能改自己的能力声明，那等于没有声明。
 */
private fun requiredCapsOf(node: WorkflowNode): List<String> {
    val spec = WorkflowActionCatalog.byId(node.config("bridge")) ?: return emptyList()
    val cap = spec.cap ?: return emptyList()
    return listOf(cap.id)
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

/**
 * 三次贝塞尔连线：真分支 tertiary、假分支 error 虚线、默认流 outline 实线、
 * **数据边 primary 细虚线**。
 */
private fun DrawScope.drawBlueprintEdges(
    definition: WorkflowDefinition,
    positions: Map<String, Offset>,
    pinRects: Map<String, Rect>,
    origin: Offset,
    pan: Offset,
    scale: Float,
    normal: Color,
    trueColor: Color,
    falseColor: Color,
    dataColor: Color,
) {
    val density = this.density
    for (edge in definition.edges) {
        val from = positions[edge.from] ?: continue
        val to = positions[edge.to] ?: continue
        // 线的起点：优先用真针脚的窗口矩形（换算回内容层局部坐标），没有就退回卡片右上角。
        //
        // 数据边从**哪个针脚**出去由 fromPin 决定（例如 extract 的 "var"）；
        // 控制流边看 when。两种边的针脚键必须是同一套格式（"<节点id>:<引脚种类名>"），
        // 否则会画出"从卡片右上角飞出去"的线 —— 而那看起来像连错了。
        val pinKind = if (edge.kind == WorkflowEdgeKind.DATA) {
            dataPinKeyOf(edge.fromPin)
        } else {
            when (edge.whenExpr) {
                "true" -> "EXEC_TRUE"
                "false" -> "EXEC_FALSE"
                else -> "EXEC_OUT"
            }
        }
        val pinned = pinRects["${edge.from}:$pinKind"]
        // 针脚报的是**窗口**矩形，而这一层画在已被 graphicsLayer 缩放+平移过的内容层里 ——
        // 所以要先经逆变换回到世界坐标，否则一旦缩放，线头就会离针脚越来越远。
        val safeScale = if (scale > 0f) scale else 1f
        val fromRight = if (pinned != null) {
            (pinned.right - origin.x - pan.x) / safeScale
        } else {
            (from.x * density) + CARD_W_DP * density
        }
        val startY = if (pinned != null) {
            (pinned.center.y - origin.y - pan.y) / safeScale
        } else {
            (from.y * density) + WorkflowNodeCardAnchorY * density
        }
        val endX = to.x * density
        val endY = (to.y * density) + WorkflowNodeCardAnchorY * density
        val ctrl = max(abs(endX - fromRight) * 0.5f, 60.dp.toPx())
        val path = Path().apply {
            moveTo(fromRight, startY)
            cubicTo(fromRight + ctrl, startY, endX - ctrl, endY, endX, endY)
        }
        // 数据边用虚线 + 细一点的线：它在执行期不决定"走哪条路"，只决定"参数从哪来"。
        // 两者画成一模一样的话，用户会以为一条数据边也是分支。
        val isData = edge.kind == WorkflowEdgeKind.DATA
        val color = when {
            isData -> dataColor
            edge.whenExpr == "true" -> trueColor
            edge.whenExpr == "false" -> falseColor
            else -> normal
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = if (isData) 1.8.dp.toPx() else if (edge.whenExpr.isBlank()) 2.5.dp.toPx() else 2.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = if (isData || edge.whenExpr == "false") {
                    PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))
                } else {
                    null
                },
            ),
        )
    }
}
