package me.bmax.apatch.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import me.bmax.apatch.dsh.WorkflowExpandType
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import me.bmax.apatch.R
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
 *  - 参数内联展开：逐行取自 WorkflowNodeFields 的字段表（色点 + 字段名 + 只读值），卡片因此不再
 *    自己维护一份「主参数」的 when —— 表单认哪些键、卡片就显示哪些键，两份定义不会慢慢分叉；
 *  - 缺能力（granted = false）时整卡压暗，并追加一条 errorContainer 提示条写明缺哪些能力；
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
    onPinDrag: ((pinKind: String, anchorWindow: Offset, pointerWindow: Offset) -> Unit)? = null,
    /** 拉线松手（窗口坐标）；连到哪由画布判定。 */
    onPinDrop: ((pointerWindow: Offset) -> Unit)? = null,
    /**
     * 这张卡需要的系统能力 id（与 WorkflowPermissions 是同一套 id）。
     *
     * 默认空列表：预览、详情、画布这些老调用点不传就是「不需要任何能力」，行为不变。
     */
    requiredCaps: List<String> = emptyList(),
    /**
     * 上面这些能力此刻是否都已授权。默认 true：老调用点不传即视为可用；
     * 只有编辑器在确认缺失时才显式传 false，卡片才会压暗并显示缺能力的提示条。
     */
    granted: Boolean = true,
    /**
     * 卡上直接改参数。
     *
     * 用户的原话是"参数都在卡片本身上的" —— 参考的点击助手就是卡上直接改，
     * 不点开任何弹窗。所以参数行做成输入框，改完立刻回给宿主落盘。
     */
    onParamChange: ((key: String, value: String) -> Unit)? = null,
    /** 切换展开档位（卡上按钮）。null = 不显示该按钮（预览、详情）。 */
    onToggleExpand: (() -> Unit)? = null,
    /** 切换锁定（卡上按钮）。锁定 = 不能拖动/选中/删除，见 [WorkflowNode.locked]。 */
    onToggleLock: (() -> Unit)? = null,
    /** 这张卡里哪些参数已经接上了线（决定 NONE 档显示什么）。 */
    linkedParamKeys: Set<String> = emptySet(),
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
    // 手势块（pointerInput(node.id) / pointerInput(Unit)）的 key 不会因为回调变化而重启，
    // 所以回调必须经 rememberUpdatedState 读取最新值 —— 否则第二次拖动拿到的是第一次组合时的
    // 闭包，会读到最初的坐标（真机表现：第二次拖动一下跳回原位）。
    val tapCb = rememberUpdatedState(onTap)
    val dragCb = rememberUpdatedState(onDrag)
    val dragStartCb = rememberUpdatedState(onDragStart)
    val dragEndCb = rememberUpdatedState(onDragEnd)
    val pinDragCb = rememberUpdatedState(onPinDrag)
    val pinDropCb = rememberUpdatedState(onPinDrop)

    // 卡片尾部动作条：锁定 / 展开 / 复制 / 删除。
    //
    // 参考实现的这四个按钮都长在卡片上（ActionCard.initLock/initExpand/initCopy/initDelete），
    // 不是浮在画布上。长在卡上的好处是「操作对象」与「操作入口」在一起 ——
    // 用户不必先选中、再抬头去底栏找按钮。
    val base = modifier
        .width(WorkflowNodeCardWidth)
        .onGloballyPositioned { coords ->
            windowOrigin = coords.positionInWindow()
            cardSize = coords.size
            onBounds?.invoke(coords.boundsInWindow())
        }
        .focusable()
        .semantics { contentDescription = node.name.ifBlank { workflowNodeLabel(node) } }
        // 整卡不再接拖动手势：参数行是可编辑输入框，整卡吃手势会把输入框的点击与光标
        // 全抢走（真机表现：点输入框没反应、想拖光标反而把卡拖走了）。
        // 拖动收窄到**标题区**（见下面的 header），参数区留给输入框。
        .then(if (onTap != null) Modifier.clickable { tapCb.value?.invoke() } else Modifier)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (thick) 2.dp else 1.dp, borderColor),
        tonalElevation = if (selected) 6.dp else 2.dp,
        // 无权限的卡整体压暗：这是"现在用不了"的第一眼信号，比任何徽标都直接。
        modifier = if (granted) base else base.alpha(0.6f),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(4.dp).background(accent))
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                // ── 标题区＝拖动把手 ──
                //
                // 只有这里接拖动：它没有输入控件，不会和别的操作打架。
                // 这也是点击助手的做法 —— 卡上能编辑的地方不抢拖动。
                Column(
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (onDrag != null) {
                                Modifier.pointerInput(node.id) {
                                    detectDragGestures(
                                        onDragStart = { dragStartCb.value?.invoke() },
                                        onDragEnd = { dragEndCb.value?.invoke() },
                                        onDragCancel = { dragEndCb.value?.invoke() },
                                    ) { change, amount ->
                                        change.consume()
                                        dragCb.value?.invoke(amount.x, amount.y)
                                    }
                                }
                            } else {
                                Modifier
                            }
                        ),
                ) {
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
                }
                // ── 参数内联展开 ──
                //
                // 对齐点击助手：参数**直接摆在卡片上**，一行一个，不用点开任何弹窗就能读出
                // "这一步到底怎么配的"。上一版只显示一个"主参数"，其余全藏在弹窗里，
                // 于是一屏卡片看下来只能看出节点类型，看不出它们各在做什么。
                //
                // 参数行是**可编辑**的（见 CardParamRow）：拖动只挂在标题区，
                // 所以卡上直接改参数不会和拖动抢手势。
                // 三档展开：过滤在**这里**做，而不是让每个 CardParamRow 自己判断 ——
                // 一处过滤，可见性规则只有一份。
                val allRows = cardParamRows(node, linkedParamKeys)
                val rows = allRows.filter { candidate -> rowVisible(candidate, node) }
                if (rows.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    for (row in rows) CardParamRow(row, onEdit = onParamChange)
                }
                if (!granted) {
                    Spacer(Modifier.height(6.dp))
                    // 缺能力不是「参数为空」，而是一种状态：用 error 的容器色一条说清缺什么，
                    // 用户不用点开卡片才知道这张卡为什么跑不了。
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.errorContainer,
                                RoundedCornerShape(6.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.dsh_wf_lock),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                        // 能力清单为空时只说「没权限」，不要输出一句「需要：」吊在那里。
                        if (requiredCaps.isNotEmpty()) {
                            Text(
                                text = stringResource(
                                    R.string.dsh_wf_lock_need,
                                    requiredCaps.joinToString("、"),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                // ── 卡片尾部动作条 ──
                //
                // 四个按钮都长在卡片上（对齐参考实现的 ActionCard.initLock /
                // initExpand / initCopy / initDelete）。锁定时整条底色变化，是"这张卡被冻住了"
                // 的第一眼信号；展开按钮的图标随档位变化，三档各一个形状。
                if (onToggleLock != null || onToggleExpand != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onToggleExpand != null) {
                            CardActionIcon(
                               icon = when (node.expandType) {
                                    WorkflowExpandType.NONE -> Icons.Filled.VisibilityOff
                                    WorkflowExpandType.HALF -> Icons.Filled.Visibility
                                    WorkflowExpandType.FULL -> Icons.Filled.RemoveRedEye
                                },
                                desc = stringResource(R.string.dsh_wf_card_expand),
                                active = node.expandType == WorkflowExpandType.FULL,
                                onClick = onToggleExpand,
                            )
                        }
                        if (onToggleLock != null) {
                            CardActionIcon(
                                icon = if (node.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                                desc = stringResource(R.string.dsh_wf_card_lock),
                                active = node.locked,
                                onClick = onToggleLock,
                            )
                        }
                    }
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
    onPinDrag: ((pinKind: String, anchorWindow: Offset, pointerWindow: Offset) -> Unit)? = null,
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
    onPinDrag: ((pinKind: String, anchorWindow: Offset, pointerWindow: Offset) -> Unit)?,
    onPinDrop: ((pointerWindow: Offset) -> Unit)?,
) {
    var dotOrigin by remember { mutableStateOf(Offset.Zero) }
    var dotSize by remember { mutableStateOf(IntSize.Zero) }
    val originState = rememberUpdatedState(dotOrigin)
    val sizeState = rememberUpdatedState(dotSize)
    val pinDragCb = rememberUpdatedState(onPinDrag)
    val pinDropCb = rememberUpdatedState(onPinDrop)
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
                        pinDragCb.value?.invoke(pin.kind.name, anchorWindow(), pointer)
                    },
                    onDragEnd = { pinDropCb.value?.invoke(pointer) },
                    onDragCancel = { pinDropCb.value?.invoke(pointer) },
                ) { change, amount ->
                    change.consume()
                    pointer += amount
                    pinDragCb.value?.invoke(pin.kind.name, anchorWindow(), pointer)
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
            WorkflowActionType.NATIVE -> run {
            val spec = me.bmax.apatch.dsh.WorkflowActionCatalog.byId(node.config("bridge"))
            spec?.let { me.bmax.apatch.apApp.resources.getString(it.titleRes) } ?: node.config("bridge")
        }
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


/**
 * 卡片上要展示的一行参数。
 *
 * 取值规则：逐行取自字段表（[WorkflowNodeFields.fieldsFor]），空值也占一行（画空心椭圆）；
 * 一个可显示字段都没有时，才退回该类型的"主参数"（cmd / text / 条件表达式 …）。
 *
 * 为什么按字段表来而不是写死键名：写死的话，动作目录里新加的参数在卡上永远看不见
 * （它只在弹窗里出现），而用户根本没理由点开弹窗。
 */
private data class CardParamRowModel(
    val key: String,
    val label: String,
    val value: String,
    val color: Color,
    /** 这个参数是否已接上线（NONE 档据此决定是否显示）。 */
    val linked: Boolean = false,
)

@Composable
private fun cardParamRows(
    node: WorkflowNode,
    /** 已接线的参数键集合（由宿主从边表算出）。见 [rowVisible] 的 NONE 档规则。 */
    linkedKeys: Set<String> = emptySet(),
): List<CardParamRowModel> {
    // 先收集全部候选，再分「有值」与「空着」两批 —— 顺序不能边收边截断。
    //
    // 为什么必须先分两批：a11y_swipe 这类动作有 5 个参数，只有 durationMs 有默认值，
    // 另外 4 个坐标是空的。边收边截断的话，前 4 个空行正好占满名额，**唯一有值的那个
    // 反而被挤出去** —— 卡片上全是空心椭圆，用户看不出这一步到底配了什么。
    //
    // 空字段仍然保留（值的位置画空心椭圆，表示"这个参数在、但还没填"），
    // 只是在名额不够时优先让位给有值的字段。
    val filled = mutableListOf<CardParamRowModel>()
    val empty = mutableListOf<CardParamRowModel>()
    for (field in WorkflowNodeFields.fieldsFor(node)) {
        // 元数据不算参数：name/description/onError/timeoutMs 是节点自身的属性，
        // 摆到卡上只会把真正要看的参数挤下去。
        if (field.key in setOf("name", "description", "onError", "timeoutMs")) continue
        if (!field.visible(node)) continue
        val raw = node.config(field.key)
        val row = CardParamRowModel(
            key = field.key,
            label = stringResource(field.labelRes),
            value = raw,
            color = paramDotColor(field.kind),
            linked = field.key in linkedKeys,
        )
        if (raw.isBlank()) empty += row else filled += row
    }
    // 卡就这么宽，超过四行之后参数已经读不过来了；剩下的留给点开弹窗看。
    // 有值的排前面，空着的补在后面。
    val out = (filled + empty).take(4).toMutableList()
    if (out.isEmpty()) {
        val primary = when (node.type) {
            WorkflowNodeType.EXECUTE -> node.config("cmd").ifBlank { node.config("text") }
            WorkflowNodeType.CONDITION -> (node.config("op") + " " + node.config("value")).trim()
            WorkflowNodeType.EXTRACT -> node.config("var")
            WorkflowNodeType.WAIT -> node.config("ms")
            else -> ""
        }
        if (primary.isNotBlank()) {
            out += CardParamRowModel(
                key = "primary",
                label = "",
                value = primary,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
    return out
}

/**
 * 字段种类的色点。一律取主题角色色 —— 硬编码在这里会让深色主题下几乎看不见。
 *
 * Kind 目前没有独立的图片种类：非结构化的长文本（含图片路径）都归 MULTILINE，
 * 所以「图片类用 outline」这条落在 MULTILINE 上，让它与普通 TEXT 一眼可分。
 */
@Composable
private fun paramDotColor(kind: WorkflowNodeFields.Kind): Color = when (kind) {
    WorkflowNodeFields.Kind.TEXT -> MaterialTheme.colorScheme.primary
    WorkflowNodeFields.Kind.MULTILINE -> MaterialTheme.colorScheme.outline
    WorkflowNodeFields.Kind.NUMBER -> MaterialTheme.colorScheme.tertiary
    WorkflowNodeFields.Kind.BOOL -> MaterialTheme.colorScheme.secondary
    // 「error 的柔和版」取 errorContainer：与 error 同族、但为底色而生，深色主题下也读得出。
    WorkflowNodeFields.Kind.CHOICE -> MaterialTheme.colorScheme.errorContainer
}

/**
 * 一行参数：色点 + 标签 + **可编辑的值**。
 *
 * 从"只读值胶囊 + 复制按钮"改成真正的输入框 —— 用户的原话是"参数都在卡片本身上的"。
 * 复制按钮随之取消：能直接改的时候，复制一段只读字符串没有意义。
 */
@Composable
private fun CardParamRow(
    row: CardParamRowModel,
    onEdit: ((key: String, value: String) -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 空值画空心椭圆、有值画实心：一眼看出"这个参数还空着"。
        Box(
            Modifier
                .size(8.dp)
                .then(
                    if (row.value.isBlank()) {
                        Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    } else {
                        Modifier.background(row.color, CircleShape)
                    }
                ),
        )
        if (row.label.isNotBlank()) {
            Spacer(Modifier.width(5.dp))
            Text(
                text = row.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 84.dp),
            )
        }
        Spacer(Modifier.width(5.dp))
        BasicTextField(
            value = row.value,
            onValueChange = { onEdit?.invoke(row.key, it) },
            textStyle = MaterialTheme.typography.labelSmall.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            singleLine = true,
            enabled = onEdit != null,
            modifier = Modifier
                .weight(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}


/**
 * 参数行是否可见 —— 三档展开规则，逐条对齐 `PinView.expand`。
 *
 * ```
 * FULL  全部显示
 * HALF  显示非 hide 的（这里没有 hide 字段，等价于全部）
 * NONE  只显示"已连线"的行
 * ```
 *
 * **已连线的行在任何档位都可见**，这一条是照抄参考实现时必须保留的：
 * 它显式写了 `if (pin.getLinks().isEmpty())` 才考虑隐藏，否则用户切到 NONE
 * 会以为接线丢了。
 */
private fun rowVisible(row: CardParamRowModel, node: WorkflowNode): Boolean = when (node.expandType) {
    WorkflowExpandType.FULL, WorkflowExpandType.HALF -> true
    // NONE：只留已连线的参数行（接线是用户显式建立的关系，隐藏它等于骗人）。
    WorkflowExpandType.NONE -> row.linked
}

/** 卡片尾部的小图标按钮。激活态（已锁 / 全展开）用主色，其余用描边色。 */
@Composable
private fun CardActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Icon(
        imageVector = icon,
        contentDescription = desc,
        tint = if (active) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            // 卡片只有 220dp 宽，IconButton 会把每个图标撑到 48dp（minimumInteractiveComponentSize），
            // 四个按钮就占满一整行。这里命中区就是图标本身，换行高可控。
            .size(20.dp)
            .clickable(onClick = onClick),
    )
}
