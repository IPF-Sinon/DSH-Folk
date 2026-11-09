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
import me.bmax.apatch.dsh.WorkflowNodeType
import me.bmax.apatch.dsh.WorkflowPinKind
import me.bmax.apatch.dsh.WorkflowSchema
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
    /**
     * 已授予的能力 id 集合。卡片据此把自己标成"不可用"。
     *
     * 传集合而不是每张卡一个布尔：同一张卡的能力要求是从动作目录推出来的，
     * 而"哪些已授予"是全局事实 —— 把两者在画布入口合并一次，卡片就只管显示。
     */
    grantedCaps: Set<String> = emptySet(),
    /** 卡上直接改参数：传 null 时卡片参数只读（预览、详情页）。 */
    onNodeParamChange: ((nodeId: String, key: String, value: String) -> Unit)? = null,
    /** 卡上「展开」按钮：在 NONE → HALF → FULL 之间循环。 */
    onNodeToggleExpand: ((nodeId: String) -> Unit)? = null,
    /** 卡上「锁」按钮：冻结这张卡（不能拖/不能选/不能删）。 */
    onNodeToggleLock: ((nodeId: String) -> Unit)? = null,
    onNodeClick: ((String) -> Unit)? = null,
    onNodeDrag: ((id: String, dxPx: Float, dyPx: Float) -> Unit)? = null,
    onNodeDragStart: ((id: String) -> Unit)? = null,
    onNodeDragEnd: ((id: String) -> Unit)? = null,
    /** 卡上「复制」按钮（对齐参考实现 ActionCard.initCopy）。 */
    onNodeCopy: ((id: String) -> Unit)? = null,
    /** 卡上「删除」按钮（第一下武装、第二下真删，见 deleteArmedNodeId）。 */
    onNodeDelete: ((id: String) -> Unit)? = null,
    /** 卡上铅笔（打开这一步的详细配置）。 */
    onNodeEdit: ((id: String) -> Unit)? = null,
    /** 哪张卡的删除按钮正处于「已武装」（第一下点过）状态。 */
    deleteArmedNodeId: String? = null,
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
    onWireDrop: ((
        fromId: String,
        fromPin: String,
        toId: String?,
        pointerWindow: Offset,
        /** 松手压中的**具体数据入口针脚 id**（`p_x` / `cmd` / `title` …）；压在卡片空白处时为 null。 */
        targetPin: String?,
    ) -> Unit)? = null,
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
    // 针脚窗口矩形，两套键同时登记（见卡片的 onPinBounds / onPinIdBounds）：
    //  - "<节点id>:<引脚种类名>"（EXEC_IN / DATA_OUT …）—— 控制流边与按种类的调用点用；
    //  - "<节点id>:<引脚id>"（p_x / cmd / result / var）—— 数据边与数据落点用。
    // 一张卡上常有好几个同种类的数据针脚，只按种类建索引会互相覆盖，
    // 于是"线从哪颗点出去"和"落在哪个参数上"都只能靠猜。
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
            val framed = selectedNodeId
            if (framed != null) {
                // 框必须用**卡片实测的窗口矩形**换算，不能用写死的高度。
                //
                // 上一版把高度写死成 96dp：卡片还只有"名称+一个主参数"时正好相等，于是肉眼看不出问题；
                // 现在卡片带参数行、会变高，写死的框就比卡片小一截（真机截图里框只包住上半张卡）。
                val rect = bounds[framed]
                if (rect != null) {
                    Canvas(Modifier.fillMaxSize()) {
                        val pad = 8.dp.toPx()
                        val left = (rect.left - origin.x - pan.x) / (if (scale > 0f) scale else 1f)
                        val top = (rect.top - origin.y - pan.y) / (if (scale > 0f) scale else 1f)
                        val right = (rect.right - origin.x - pan.x) / (if (scale > 0f) scale else 1f)
                        val bottom = (rect.bottom - origin.y - pan.y) / (if (scale > 0f) scale else 1f)
                        drawRect(
                            color = wireColor,
                            topLeft = Offset(left - pad, top - pad),
                            size = androidx.compose.ui.geometry.Size(
                                (right - left) + pad * 2,
                                (bottom - top) + pad * 2,
                            ),
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
            // 每张卡里"已接线的参数键"：NONE 档据此决定显示哪些行。
            // 由数据边（toPin / fromPin 的 to 端）算出，卡片自己不认识边表。
            val dataLinkedKeys: Map<String, Set<String>> = definition.edges
                .filter { it.kind == WorkflowEdgeKind.DATA }
                .groupBy { it.to }
                .mapValues { (_, es) -> es.map { it.toPin }.toSet() }
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
                    onTap = { if (!node.locked) onNodeClick?.invoke(node.id) },
                    // 锁定的卡不接拖动（锁定 = 不能拖动，见 WorkflowNode.locked）。
                    onDrag = if (node.locked) null else ({ dx: Float, dy: Float -> onNodeDrag?.invoke(node.id, dx, dy) }),
                    onDragStart = if (node.locked) null else ({ onNodeDragStart?.invoke(node.id) }),
                    onDragEnd = if (node.locked) null else ({ onNodeDragEnd?.invoke(node.id) }),
                    // 卡上直接改参数：只在编辑态开放（预览/详情传 null，输入框自动 disabled）。
                    onParamChange = onNodeParamChange?.let { cb -> { key, value -> cb(node.id, key, value) } },
                    onToggleExpand = onNodeToggleExpand?.let { { it(node.id) } },
                    onToggleLock = onNodeToggleLock?.let { { it(node.id) } },
                    onCopy = onNodeCopy?.let { cb -> { cb(node.id) } },
                    onDelete = onNodeDelete?.let { cb -> { cb(node.id) } },
                    onEdit = onNodeEdit?.let { cb -> { cb(node.id) } },
                    deleteArmed = deleteArmedNodeId == node.id,
                    // 入口节点不能删：删掉它整张图没有起点，定义立刻不合法（校验器也会报）。
                    canDelete = node.type != WorkflowNodeType.TRIGGER,
                    linkedParamKeys = dataLinkedKeys[node.id].orEmpty(),
                    onBounds = { rect -> bounds = bounds + (node.id to rect) },
                    onPinBounds = { pinKind, rect -> pinRects = pinRects + ("${node.id}:$pinKind" to rect) },
                    // 同时按引脚 id 登记：数据边与落点判定要精确到"哪一颗"。
                    onPinIdBounds = { pinId, pinKind, rect ->
                        pinRects = pinRects + ("${node.id}:$pinId" to rect) + ("${node.id}:$pinKind" to rect)
                    },
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
                            // 落点先找**具体针脚**：松手压在某颗数据入口上，就把线接到那个参数。
                            //
                            // 上一版只比卡片矩形，于是所有数据线一律接到下游的第一个参数 ——
                            // 用户明明落在第二个参数上，线却跑到第一个去了。
                            // 只认**数据入口**针脚：索引里还登记着引脚种类名与出口针脚，
                            // 不筛的话，松手压到自己那颗出口点、或者压在种类名那种粗索引上，
                            // 都会被当成"连到下游某个参数"。
                            val hitNodeId = pinRects.entries.firstOrNull { (key, rect) ->
                                val owner = key.substringBefore(':')
                                val pinId = key.substringAfter(':')
                                val ownerNode = definition.nodes.firstOrNull { it.id == owner }
                                owner != from && ownerNode != null &&
                                    pinId in WorkflowSchema.dataInputKeysOf(ownerNode) &&
                                    rect.contains(pointer)
                            }?.key?.substringBefore(':')
                            val targetPin = hitNodeId?.let { owner ->
                                pinRects.entries.firstOrNull { (key, rect) ->
                                    key.substringBefore(':') == owner &&
                                        key.substringAfter(':') in WorkflowSchema.dataInputKeysOf(
                                            definition.nodes.first { it.id == owner },
                                        ) && rect.contains(pointer)
                                }?.key?.substringAfter(':')
                            }
                            val target = hitNodeId
                                ?: bounds.entries.firstOrNull { (id, rect) -> id != from && rect.contains(pointer) }?.key
                            wireDropCb.value?.invoke(from, fromPin, target, pointer, targetPin)
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
                // 拉线中的预览线也画成折线：松手之后会变成折线，预览若是曲线，
                // 松手那一下的"变形"会让人以为连错了对象。
                // 走廊取 LINK_CORRIDOR_DP（12dp），**不是**画布网格步长（24dp）。
                // 见 WorkflowLinkPath 的说明：把网格步长当走廊会让绕行半径翻倍、把卡片包住。
                val path = orthogonalLinkPath(a, b, corridor = LINK_CORRIDOR_DP.dp.toPx())
                drawPath(path, wireColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                drawCircle(wireColor, radius = 5.dp.toPx(), center = b)
            }
        }
    }
}

/**
 * 数据边的 fromPin → 起点节点的**引脚 id**（画布内部索引键的后半段）。
 *
 * 落盘的 fromPin 有三种写法，都源自编辑页的 `dataSourceFor`：
 *  - `var:<名字>`  → extract 产出的是变量，出口针脚 id 就是 `var`；
 *  - `step:<id>`   → 本步整体输出，出口针脚 id 是 `result`（每个节点都有这条出口）；
 *  - 其余（裸针脚名）→ 原样用（兼容手写/旧文件）。
 *
 * 上一版是对 `:` 切一刀取后半段，于是 `step:n3_123` 被当成一个叫 `n3_123` 的针脚 ——
 * 永远找不到，线因此退回"卡片右上角"的估算法，看起来像连错了。
 */
private fun dataPinIdOf(
    definition: WorkflowDefinition,
    fromNodeId: String,
    fromPin: String,
): String = when {
    fromPin.startsWith("var:") -> "var"
    fromPin.startsWith("step:") -> "result"
    fromPin.contains(':') -> fromPin.substringAfter(':')
    fromPin.isNotBlank() -> fromPin
    // 什么都没写：按节点类型给最合适的出口（extract 给变量，其余给结果）。
    definition.node(fromNodeId)?.type == WorkflowNodeType.EXTRACT -> "var"
    else -> "result"
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
        val isData = edge.kind == WorkflowEdgeKind.DATA
        // 线头的针脚键：控制流用引脚种类名，数据边用引脚 id（见 [dataPinIdOf]）。
        // 两者共用 "<节点id>:<引脚键>" 这一套索引，卡片在 onPinBounds / onPinIdBounds 里都登记。
        val pinKey = if (isData) {
            dataPinIdOf(definition, edge.from, edge.fromPin)
        } else {
            when (edge.whenExpr) {
                "true" -> WorkflowPinKind.EXEC_TRUE.name
                "false" -> WorkflowPinKind.EXEC_FALSE.name
                else -> WorkflowPinKind.EXEC_OUT.name
            }
        }
        // 针脚报的是**窗口**矩形，而这一层画在已被 graphicsLayer 缩放+平移过的内容层里 ——
        // 所以要先经逆变换回到世界坐标，否则一旦缩放，线头就会离针脚越来越远。
        val safeScale = if (scale > 0f) scale else 1f
        fun worldX(x: Float) = (x - origin.x - pan.x) / safeScale
        fun worldY(y: Float) = (y - origin.y - pan.y) / safeScale
        val pinned = pinRects["${edge.from}:$pinKey"]
        // 起点在卡片的哪一侧：数据**入口**在左缘，其余出口在右缘。
        //
        // 上一版一律取 pinned.right，数据线于是从参数行左缘那颗点上"往右"弹出去再绕回来，
        // 画出来像卡在卡片里。参考实现的 PinLeftView / PinRightView 正是按方向分开的。
        val startX = if (pinned != null) {
            worldX(if (isData) pinned.left else pinned.right)
        } else {
            (from.x * density) + (if (isData) 0f else CARD_W_DP * density)
        }
        val startY = if (pinned != null) {
            worldY(pinned.center.y)
        } else {
            (from.y * density) + WorkflowNodeCardAnchorY * density
        }
        // 终点：数据边落在目标**参数那颗点**上，控制流边落在卡片的执行入口上。
        // 取不到真针脚才退回卡片锚点的估算法（旧文件 / 未知键仍画得出线）。
        val toPinned = pinRects["${edge.to}:${if (isData) edge.toPin else WorkflowPinKind.EXEC_IN.name}"]
        val endX = if (toPinned != null) worldX(toPinned.center.x) else to.x * density
        val endY = if (toPinned != null) {
            worldY(toPinned.center.y)
        } else {
            (to.y * density) + WorkflowNodeCardAnchorY * density
        }
        // 折线（不是贝塞尔）：见 WorkflowLinkPath 的说明。
        val path = orthogonalLinkPath(
            start = Offset(startX, startY),
            end = Offset(endX, endY),
            corridor = LINK_CORRIDOR_DP.dp.toPx(),
        )
        // 数据边用虚线 + 细一点的线：它在执行期不决定"走哪条路"，只决定"参数从哪来"。
        // 两者画成一模一样的话，用户会以为一条数据边也是分支。
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