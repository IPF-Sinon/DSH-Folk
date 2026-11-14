package me.bmax.apatch.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdsClick
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Segment
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LabelOff
import androidx.compose.material.icons.filled.Input
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import me.bmax.apatch.R
import me.bmax.apatch.dsh.WorkflowExpandType
import me.bmax.apatch.dsh.WorkflowNode
import me.bmax.apatch.dsh.WorkflowNodeType
import me.bmax.apatch.dsh.WorkflowPinKind
import me.bmax.apatch.dsh.WorkflowPinSpec
import me.bmax.apatch.dsh.WorkflowPinType
import me.bmax.apatch.dsh.WorkflowSchema
import me.bmax.apatch.dsh.WorkflowStepState
/** 卡片宽度：画布、自动布局、落点换算共用这一个值。 */
internal val WorkflowNodeCardWidth = 280.dp

/** 与参考 PinView.copyValue 同语义：已复制的数据针脚值，可粘贴到其他卡片的同类型针脚。 */
private val copiedCardPinValue = mutableStateOf<Pair<WorkflowNodeFields.Kind, String>?>(null)

/** 连线端点从卡片左边进、右边出时的纵向锚点（dp）。 */
internal val WorkflowNodeCardAnchorY = 26f

/**
 * 一个节点的卡片（预览、详情、画布共用）。
 *
 * 版面按**参考实现的卡片**（card_normal.xml + ActionCard）逐块对齐；宽度由共享卡片几何值确定：
 *
 * ```
 *  ┌─────────────────────────────┐
 *  │            执行口            │  顶：控制流入口（EXEC_IN），居中
 *  │  标题                ✎      │  标题行右侧是编辑按钮
 *  │  描述（没填就不占位）        │
 *  │  ○ 参数1   [         ]      │  数据入口 = 左缘那颗点，与参数同一行
 *  │  ○ 参数2   [         ]      │
 *  │            结果 ●           │  数据出口贴在右下
 *  │  [缺能力提示条]             │
 *  │  👁  🔒  ⧉  🗑              │  动作条
 *  │          出口口             │  底：控制流出口，居中
 *  └─────────────────────────────┘
 * ```
 *
 * 上一版的错误是**把引脚排成了"左边一列、右边一列"两块**：数据入口与其参数行分家，
 * 于是同一张卡上出现两颗点（一颗参数色点、一颗引脚点）指着同一个东西 —— 用户看到的是
 * "参数和针脚对不上"。参考实现里 ValuePin 本身就是一个"点 + 标签 + 输入框"的行，
 * 一行一个针脚；这里照样：**数据入口点就是参数行的左缘**，不再另画一颗。
 *
 * 颜色一律取 MaterialTheme 的角色色 —— 硬编码十六进制在这份代码里踩过：深色主题下网格
 * 几乎看不见。
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
    /** 上报某个引脚在窗口里的矩形（按**引脚种类**，第三方调用点只需这一种）。 */
    onPinBounds: ((pinKind: String, rect: androidx.compose.ui.geometry.Rect) -> Unit)? = null,
    /**
     * 同上，但按**引脚 id** 报（`result` / `var` / `p_x` 各自一份）。
     *
     * 为什么需要两份：一张卡上有多个同种类的数据引脚（判空的 `result` 与 extract 的 `var`、
     * 原生动作用的四五个 `p_*`），只按种类建索引会互相覆盖 —— 于是"线从哪颗点出去"
     * 与"落在哪个参数上"都只能靠猜。种类索引留给老调用点，id 索引给画布精确画线用。
     */
    onPinIdBounds: ((pinId: String, pinKind: String, rect: androidx.compose.ui.geometry.Rect) -> Unit)? = null,
    /** 把自己的窗口矩形报给画布：拉线落点判定只在窗口坐标里比。 */
    onBounds: ((androidx.compose.ui.geometry.Rect) -> Unit)? = null,
    /** 从某个引脚拉线：anchor 是线头（窗口坐标），pointer 是手指（窗口坐标）。 */
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
    /** 复制这张卡（卡上按钮，对齐参考实现 ActionCard.initCopy）。 */
    onCopy: (() -> Unit)? = null,
    /** 删除这张卡（卡上按钮，对齐参考实现 ActionCard.initDelete，两下才真删）。 */
    onDelete: (() -> Unit)? = null,
    /** 打开这张卡的详细配置（标题行右侧的铅笔，对齐参考实现 initEditDesc）。 */
    onEdit: (() -> Unit)? = null,
    /** 删除按钮是否已处于「已武装」状态（第一下把它变红）。 */
    deleteArmed: Boolean = false,
    /** 这张卡能不能删（入口节点不能删：删了整张图没有起点）。 */
    canDelete: Boolean = true,
    /** 这张卡里哪些参数已经接上了线（决定 NONE 档显示什么）。 */
    linkedParamKeys: Set<String> = emptySet(),
    /**
     * 本卡**已接线的针脚 id**（出/入、控制流/数据都算）。
     *
     * 参考实现是 slotView.setLinked(pin.isLinked())：连上就把针脚填成实色。
     * 我们之前只有数据入口的信息（[linkedParamKeys]，且只喂给参数行），
     * 出口与控制流针脚永远空心 —— 满屏连线，针脚却一个个都是空的。
     */
    linkedPinIds: Set<String> = emptySet(),
    /**
     * 拖线时**指针下这颗针脚**的 id（`p_x` / `cmd` / `result` …）；null = 没有针脚被悬停。
     *
     * 只影响外观：被悬停且可接的针脚会放大并加描边。
     *
     * 为什么需要它：拉线过程中，用户唯一的疑问是「松手会落在哪」。
     * 参考实现用 dragOnPin + 震动回答这个问题；光有预览线不够 ——
     * 线是连续的一根，指不出"具体是哪一颗针脚"。
     */
    hoveredPinId: String? = null,
) {
    val accent = workflowNodeAccent(node.type)
    // 未选中也必须有**看得见的**描边，不能是 outlineVariant 那种近乎背景色的灰。
    //
    // 参考实现 ActionCard 构造里就 setStrokeColor(colorPrimaryVariant) + setStrokeWidth(1)，
    // 卡片永远有一条高亮边。这条边的实际用途是：两张卡叠在一起时能立刻分出是两张 ——
    // 连线本来就被卡片压着，若边缘也糊在一起，整块会看成一张卡。
    //
    // 之前用 outlineVariant，与 surfaceVariant 卡片底几乎同色，叠卡就分不清了（用户截图）。
    val borderColor = when (status) {
        WorkflowStepState.RUNNING -> MaterialTheme.colorScheme.primary
        WorkflowStepState.OK -> MaterialTheme.colorScheme.tertiary
        WorkflowStepState.FAILED, WorkflowStepState.TIMEOUT -> MaterialTheme.colorScheme.error
        WorkflowStepState.SKIPPED, WorkflowStepState.CANCELED -> MaterialTheme.colorScheme.outline
        else -> if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    }
    // 引脚表与执行器/校验器同一份推导（WorkflowSchema.defaultPinsFor），不在卡上另写一份。
    val pins = WorkflowSchema.defaultPinsFor(node)
    val execIn = pins.firstOrNull { it.kind == WorkflowPinKind.EXEC_IN }
    val execOuts = pins.filter {
        it.kind == WorkflowPinKind.EXEC_OUT || it.kind == WorkflowPinKind.EXEC_TRUE ||
            it.kind == WorkflowPinKind.EXEC_FALSE
    }
    val dataIns = pins.filter { it.kind == WorkflowPinKind.DATA_IN }
    val dataOuts = pins.filter { it.kind == WorkflowPinKind.DATA_OUT }
    val rows = cardParamRows(node, linkedParamKeys).filter { rowVisible(it, node) }
    val copiedPinValue by copiedCardPinValue
    // 卡片**自己**接手势（点击助手的 View 也是各自 onTouchEvent）：
    // 命中不需要算 —— 手指按在这张卡上，事件就到这张卡，不存在「坐标系比错」的可能。
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

    val base = modifier
        .width(WorkflowNodeCardWidth)
        .onGloballyPositioned { coords ->
            windowOrigin = coords.positionInWindow()
            cardSize = coords.size
            onBounds?.invoke(coords.boundsInWindow())
        }
        .focusable()
        .semantics { contentDescription = node.name.ifBlank { workflowNodeLabel(node) } }
        // 整卡接拖动，输入框在上层会先消费：Compose 的指针事件从最内层子节点向外冒泡，
        // 参数行的 BasicTextField 一旦命中就吃掉事件，卡片收不到 —— 所以不会抢输入框。
        //
        // 之前只让标题区能拖，结果卡片其余部分滑动会冒泡到画布变成平移
        // （用户报的"在卡片上滑动没移动卡片反而移动画布"）。
        // 参考实现里卡片整体可拖（CardLayoutView 是 ViewGroup，卡片自己消费触摸），照做。
        // 整卡手势：先按拖动判定，没拖动才算点击（单击选中）。
        //
        // 用 pointerInput 而不是 clickable + 上层拖动：clickable 会先把按下事件吃掉，
        // 导致卡片永远拖不动。这里自己判：位移超过阈值算拖动，否则算点击。
        .pointerInput(node.id) {
            if (onDrag == null && onTap == null) return@pointerInput
            // 两种手势二选一，用同一个指针输入：
            //   - 按下后移超过 touchSlop = 拖动卡片（并**消费**事件，不再冒泡到画布）；
            //   - 按下即抬起 = 点击选中。
            // 不能用 clickable + 拖动两层：clickable 会先吃掉按下事件，卡片就永远拖不动。
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val slop = viewConfiguration.touchSlop
                var pastSlop = false
                val pointer = down.id
                var lastPosition = down.position
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == pointer } ?: break
                    if (!change.pressed) {
                        // 抬起：没越过阈值就是点击。
                        if (!pastSlop) tapCb.value?.invoke()
                        change.consume()
                        break
                    }
                    // 位移 = 本帧位置 - 上帧位置。用 position 直接相减，
                    // 不依赖 positionChange() 那个扩展（它的可见性/重载在各版本间有差异，
                    // 已在 CI 上报过 'positionChange' of type 'Boolean' cannot be invoked）。
                    val delta = change.position - lastPosition
                    lastPosition = change.position
                    if (!pastSlop) {
                        // 判阈值用相对**按下点**的累计位移：单帧位移可能很小（慢速拖动），
                        // 用单帧判会导致很慢的拖动永远不触发。
                        val travelled = change.position - down.position
                        // hypot 收 Double，显式转回去，别让类型推断在 CI 上炸。
                        val dist = kotlin.math.hypot(travelled.x.toDouble(), travelled.y.toDouble()).toFloat()
                        if (dist > slop) {
                            pastSlop = true
                            dragStartCb.value?.invoke()
                        }
                    }
                    if (pastSlop) {
                        change.consume()
                        dragCb.value?.invoke(delta.x, delta.y)
                    }
                }
                if (pastSlop) dragEndCb.value?.invoke()
            }
        }

    Surface(
        shape = RoundedCornerShape(12.dp),
        // ActionCard 未选中用 colorSurfaceVariant，选中改 colorSecondaryContainer；均跟随主题。
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 8.dp,
        // 无权限的卡整体压暗：这是"现在用不了"的第一眼信号，比任何徽标都直接。
        modifier = if (granted) base else base.alpha(0.6f),
    ) {
        Box {
            // 水印：**这一张卡是什么动作**的图标，置顶居中、压在内容之下。
            //
            // 参考实现里它是 card_normal.xml 的 `icon`：layout_gravity=center_horizontal、
            // alpha=0.1。装在 Box 的第一层而不是塞进 Column：它不参与布局高度，
            // 否则卡片会被这枚图标撑高，而它本来只是个背景装饰。
            //
            // 上一版用的是**节点类型**图标，于是五张 execute 卡水印全一样 —— 用户扫一眼分不出
            // 「点击」和「截图」。现在按动作 id 取图标（[workflowActionIcon]）。
            Icon(
                imageVector = workflowNodeWatermark(node),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp)
                    .size(48.dp)
                    .alpha(0.10f),
            )
            // 坐标水印：参考实现 card_normal.xml 的 `position`（bottom|end、alpha 0.1、
            // BodyLarge、colorSecondary）。挂在同一个 Box 的第二层，**不进内容 Column** ——
            // 它不占高度，也不能把卡撑宽。只读节点已有的 posX/posY，不改任何数据。
            Text(
                text = "${node.posX},${node.posY}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .alpha(0.10f),
            )
            Column {
                // 参考实现 card_normal.xml 没有顶部色带：类型信息已经由**水印**和**选中描边**
                // 两处承担，再加一条 4dp 实色带只会让卡片顶部凭空多一截，且与描边撞色。
                // ── 顶部：控制流入口（贴住卡片上边）──
                //
                // 参考实现 pin_top.xml 的子元素顺序是：**pinSlotBox 在最上**，标题在下。
                // 也就是说针脚槽位是卡片内容的第一层，紧贴上边；"执行"字样在它下方。
                //
                // 我们之前正好相反：标签在上、针脚在下，而且两者都在 6dp 内边距里 ——
                // 于是针脚离上边差了一整个标签的高度，线接到针脚时还要在卡内穿行一段。
                // 用户说的"上接口应该贴上边"就是这个。
                if (execIn != null) {
                    // 针脚向上**跨出卡片上边**：offset 半个命中框。
                    //
                    // 参考实现里针脚槽位是卡片的第一层、紧贴上边，线一离开针脚就立刻
                    // 进入卡片下方（连线层在卡片之下），所以看不出被截断。
                    //
                    // 我们的命中框是 22dp、视觉 18dp，居中后上边还留 2dp —— 再加卡片 1dp 描边，
                    // 线会在卡内先露出两三 dp 才被压住，观感是"接口没贴住边"。
                    // 上移 2dp 让五边形的上边缘与卡片描边齐平。
                    // 横向**靠左**，不居中。
                    //
                    // 参考实现 card_normal.xml 的 topBox 是 orientation=horizontal、
                    // 只有 paddingHorizontal、**没有 gravity=center** —— 子元素从左依次排开。
                    // 所以"执行"针脚在卡片左侧，不是正中间（用户对比截图指出）。
                    Column(
                        // paddingHorizontal = 8dp 对齐参考实现的 ?attr/defaultPadding，
                        // 也就是与卡片内其余内容（标题、参数行）同一左基线。
                        Modifier.fillMaxWidth().offset(y = (-2).dp).padding(horizontal = 8.dp),
                        horizontalAlignment = Alignment.Start,
                    ) {
                        LinkablePinDot(
                            execIn, onPinBounds, onPinIdBounds, onPinDrag, onPinDrop,
                            hovered = hoveredPinId == execIn.id,
                            linked = execIn.id in linkedPinIds,
                        )
                        Text(
                            execIn.label.ifBlank { "执行" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    // ── 标题区 ──
                    //
                    // 拖动已上移到**整卡**（见 base 的 pointerInput）：参考实现里卡片整体
                    // 可拖，只在标题区接拖动会让卡面其余部分的滑动冒泡到画布变成平移。
                    // 这里不再挂拖动，否则同一次手势会被两处消费。
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = node.name.ifBlank { workflowNodeLabel(node) },
                                // 参考实现标题是 TextAppearance.Material3.BodyLarge ——
                                // 卡上唯一该用 bodyLarge 的就是标题，参数一律 labelSmall。
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            // 标题行右侧的铅笔：参考实现 initEditDesc 的位置。
                            // 卡片上只改**参数**，改名/描述/连线/删除这些重操作仍收在详情窗里，
                            // 铅笔就是那扇门 —— 否则用户找不到"把这步改名"的入口。
                            if (onEdit != null) {
                                // 参考实现这枚是 ?attr/iconButton（整块 40dp 命中区）。卡只有
                                // 参考实现的 iconButtonSize = 36dp，图标 24dp。
                                Box(
                                    Modifier.size(36.dp).clickable(onClick = onEdit),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Edit,
                                        contentDescription = stringResource(R.string.dsh_wf_top_node_detail),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }
                        }
                        // 参考实现的标题下面**没有**副标题：动作摘要那条（shell · echo hello）
                        // 和下方的参数行说的是同一件事，两处都写等于把唯一宝贵的横向空间
                        // 花在重复信息上，还把参数行往下挤了一整行。
                    }
                    // ── 描述条（参考实现的 `des`）──
                    //
                    // 参考实现把它做成满宽的一条**主题色横条**（des：background=colorPrimary、
                    // gravity=center、textColor=onPrimary），而不是一行浅色小字 —— 它是用户
                    // 自己写的一句话，视觉权重应当高于参数标签。
                    // 空描述整条不出现：空横条会把参数行挤下去，而参数才是要读的内容。
                    val desc = node.config("description")
                    if (desc.isNotBlank()) {
                        Box(
                            Modifier.fillMaxWidth().height(24.dp).background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            )
                        }
                    }
                    // ── 动作条：展开（左） + 锁定/复制/删除（右）──
                    //
                    // 参考实现 card_normal.xml 的这一行是：expandButton 在左，中间一个
                    // Space(weight=1)，右侧 MaterialButtonGroup 装 lock/copy/remove。
                    // 上一版四个按钮全挤在尾部，于是「展开」和「删除」只隔 20dp —— 想切展开
                    // 却点到删除，而删除是两下才真删，第一下就已经把卡变成红色警戒态。
                    if (onToggleExpand != null || onToggleLock != null || onCopy != null || onDelete != null) {
                        Spacer(Modifier.height(2.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 左：展开三档（参考实现 initExpand）
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
                            Spacer(Modifier.weight(1f))
                            // 右：锁定 / 复制 / 删除（参考实现 initLock / initCopy / initDelete）
                            if (onToggleLock != null) {
                                CardActionIcon(
                                    icon = if (node.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                                    desc = stringResource(R.string.dsh_wf_card_lock),
                                    active = node.locked,
                                    onClick = onToggleLock,
                                )
                            }
                            if (onCopy != null) {
                                CardActionIcon(
                                    icon = Icons.Filled.ContentCopy,
                                    desc = stringResource(R.string.dsh_wf_float_copy),
                                    active = false,
                                    onClick = onCopy,
                                )
                            }
                            if (onDelete != null && canDelete) {
                                CardActionIcon(
                                    icon = if (deleteArmed) Icons.Filled.Warning else Icons.Filled.Delete,
                                    desc = if (deleteArmed) {
                                        stringResource(R.string.dsh_wf_float_delete_confirm)
                                    } else {
                                        stringResource(R.string.dsh_wf_float_delete)
                                    },
                                    active = deleteArmed,
                                    tint = if (deleteArmed) MaterialTheme.colorScheme.error else null,
                                    onClick = onDelete,
                                )
                            }
                        }
                    }
                    // ── 针脚区（参考实现的 pinBox：inBox 左、Space、outBox 右）──
                    //
                    // 参数行**就是**数据入口那一行（ValuePin = 点 + 标签 + 输入框），
                    // 所以左列直接放参数行；右列只放数据出口的标签与针脚。
                    //
                    // 上一版把出口单独摆在所有参数下方一整行，于是卡片右侧有半张卡宽的空白，
                    // 而出口针脚离它对应的那一列数据很远 —— 拉线时要在两片空白之间找点。
                    // 三档展开的过滤在**这里**做，而不是让每个 CardParamRow 自己判断 ——
                    // 一处过滤，可见性规则只有一份。
                    if (rows.isNotEmpty() || dataOuts.isNotEmpty()) {
                        Spacer(Modifier.height(3.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            // 左列参数行自带 DATA_IN 针脚、标签、编辑控件和 copy，避免重复点。
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                for (row in rows) {
                                    CardParamRow(
                                        row = row,
                                        pin = dataIns.firstOrNull { it.id == row.key },
                                        copiedValue = copiedPinValue,
                                        onCopyValue = { kind, value -> copiedCardPinValue.value = value?.let { kind to it } },
                                        onEdit = onParamChange,
                                        onPinBounds = onPinBounds,
                                        onPinIdBounds = onPinIdBounds,
                                        onPinDrag = onPinDrag,
                                        onPinDrop = onPinDrop,
                                        hoveredPinId = hoveredPinId,
                                        linkedPinIds = linkedPinIds,
                                    )
                                }
                            }
                            if (dataOuts.isNotEmpty()) {
                                Spacer(Modifier.width(4.dp))
                                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    for (pin in dataOuts) {
                                        Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                                            PinLabel(pin)
                                            LinkablePinDot(
                                                pin, onPinBounds, onPinIdBounds, onPinDrag, onPinDrop,
                                                hovered = hoveredPinId == pin.id,
                                                linked = pin.id in linkedPinIds,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (!granted) {
                        Spacer(Modifier.height(4.dp))
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
                }
                // ── 底部：控制流出口（贴住卡片下边）──
                //
                // 参考实现 pin_bottom.xml：pinSlotBox 是**最后一个**子元素、gravity=bottom，
                // 即针脚槽位紧贴卡片下边，标签在它上方。
                //
                // 之前这段在 6dp 内边距里，针脚离下边差 6dp，线接出去时底部对不齐
                // （用户说的"下出口也没有对齐底部"）。现在移到内边距之外，并下移 2dp
                // 让五边形的下尖与卡片描边齐平。
                if (execOuts.isNotEmpty()) {
                    // 同样靠左：参考实现 bottomBox 也是 orientation=horizontal + 仅 padding，
                    // 从左侧依次摆。多条出口（执行/未达成）因此是左起排开，不是居中。
                    Row(
                        Modifier.fillMaxWidth().offset(y = 2.dp).padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (pin in execOuts) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    pin.label.ifBlank { "执行" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                                LinkablePinDot(
                                    pin, onPinBounds, onPinIdBounds, onPinDrag, onPinDrop,
                                    hovered = hoveredPinId == pin.id,
                                    linked = pin.id in linkedPinIds,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 针脚：控制流用**五边形**，数据用**圆环**。
 *
 * 五边形的几何照抄参考实现 `ExecutePinSlotView`：上边两角圆角 4dp、下边两角切角 8.5dp、描边 1dp，
 * 尺寸 `?attr/halfIconButtonSize` = 18dp。所以它不是"圆角方块"——上圆下尖这个朝向本身就是方向：
 * 尖角朝下＝往下一步走，尖角在上＝从上一步进来。圆形表达不了这层含义，这也是之前卡片
 * 看着不像参考实现的直接原因。
 *
 * 未连线时填 `surfaceVariant`、描边仍用针脚色（参考实现 setLinked(false) 的行为），
 * 于是"空针脚"是一枚空心模子而不是一个实心点：连线状态在颜色上一眼可辨。
 * 数据针脚按**类型**着色（[workflowPinColor]），于是"这口吃数字还是吃文本"不用读标签。
 */
@Composable
private fun PinDot(
    pin: WorkflowPinSpec,
    hovered: Boolean = false,
    /**
     * 这颗针脚是否已接线。参考实现：slotView.setLinked(pin.isLinked())，
     * 连上就 `setCardBackgroundColor(getPinColor())` —— **填充成它自己的颜色**，不是空心。
     */
    linked: Boolean = false,
) {
    val exec = pin.kind != WorkflowPinKind.DATA_IN && pin.kind != WorkflowPinKind.DATA_OUT
    val color = if (exec) MaterialTheme.colorScheme.outline else workflowPinColor(WorkflowPinType.of(pin.dataType))
    if (!exec) {
        // 数据针脚：已连线填实色，未连线只描边。
        //
        // 参考实现里这是同一句 setLinked：linked ? pinColor : colorSurfaceVariant。
        // 我们之前**没有**这个状态，于是满屏连线而针脚全是空心圈 ——
        // 用户看线连过去了，针脚却像没连上，这是最直接的"接口没有反应"。
        val fill = when {
            hovered -> MaterialTheme.colorScheme.primary
            linked -> color
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
        Box(
            Modifier
                .size(if (hovered) 22.dp else 18.dp)
                .background(fill, CircleShape)
                .border(if (hovered) 2.dp else 1.5.dp, if (hovered) MaterialTheme.colorScheme.primary else color, CircleShape),
        )
        return
    }
    // 圆角 4dp / 切角 8.5dp：8.5 不是整数，dp 换算成 px 后带小数，这里统一走 dp→px。
    val topCorner = 4.dp
    val cutCorner = 8.5.dp
    val density = androidx.compose.ui.platform.LocalDensity.current
    val shape = remember(density) {
        val r = with(density) { topCorner.toPx() }
        val c = with(density) { cutCorner.toPx() }
        GenericShape { size, _ ->
            // 用实际尺寸而不是常量：针脚被放进不同的父容器时也能保持同样的比例。
            val sw = size.width
            val sh = size.height
            reset()
            // 左上圆角 → 上边 → 右上圆角
            moveTo(r, 0f)
            lineTo(sw - r, 0f)
            quadraticTo(sw, 0f, sw, r)
            // 右边竖直下来，到右下切角起点
            lineTo(sw, sh - c)
            // 右下切角：一条直线到 (sw - c, sh)，这就是"尖"
            lineTo(sw - c, sh)
            // 底边到左下切角
            lineTo(c, sh)
            // 左下切角上来
            lineTo(0f, sh - c)
            // 左边回到左上圆角起点
            lineTo(0f, r)
            quadraticTo(0f, 0f, r, 0f)
            close()
        }
    }
    Box(
        Modifier
            // 命中时放大一圈并换成 primary 描边：拉线过程中用户唯一要知道的就是「松手落哪」，
            // 而预览线是连续的一根，指不出具体是哪颗针脚 —— 只有针脚自己变才能回答。
            .size(if (hovered) 22.dp else 18.dp)
            .background(
                when {
                    hovered -> MaterialTheme.colorScheme.primary
                    // 控制流针脚用 outline 填实：配对 pin_slot_normal.xml 的 stroke 颜色，
                    // 连线走到哪、哪颗亮起来，是"这一步接上了"最便宜的信号。
                    linked -> color
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                shape,
            )
            .border(if (hovered) 2.dp else 1.dp, if (hovered) MaterialTheme.colorScheme.primary else color, shape),
    )
}

/**
 * 一颗可拉线的针脚：**手势挂在这颗粒子上**（不是整列）—— 命中范围就是用户看得见的那颗点。
 *
 * 线头 = 这颗粒子的窗口中心；手指位置 = 粒子窗口原点 + 本粒子局部坐标，两个都在窗口坐标里，
 * 所以拉出来就贴着手指。之前把手势挂在整列上，局部坐标是「列」的而不是卡片的，线头因此偏左。
 */
@Composable
private fun LinkablePinDot(
    pin: WorkflowPinSpec,
    onPinBounds: ((pinKind: String, rect: androidx.compose.ui.geometry.Rect) -> Unit)?,
    onPinIdBounds: ((pinId: String, pinKind: String, rect: androidx.compose.ui.geometry.Rect) -> Unit)?,
    onPinDrag: ((pinKind: String, anchorWindow: Offset, pointerWindow: Offset) -> Unit)?,
    onPinDrop: ((pointerWindow: Offset) -> Unit)?,
    /** 这颗针脚此刻是否被拉线悬停（命中可接）。只影响外观。 */
    hovered: Boolean = false,
    /** 这颗针脚是否已接线（决定填充实色还是空心）。 */
    linked: Boolean = false,
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
            .size(22.dp)
            .onGloballyPositioned { coords ->
                dotOrigin = coords.positionInWindow()
                dotSize = coords.size
                onPinBounds?.invoke(pin.kind.name, coords.boundsInWindow())
                onPinIdBounds?.invoke(pin.id, pin.kind.name, coords.boundsInWindow())
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
        PinDot(pin, hovered = hovered, linked = linked)
    }
}

/**
 * 针脚类型 → 颜色。取主题角色色，**不写十六进制**：深色主题下硬编码色几乎看不见。
 *
 * 与 [paramDotColor] 同一套语义：文本=primary、数字=tertiary、布尔=secondary、枚举=errorContainer，
 * 于是"这颗点和那一行是同一个参数"在颜色上也对得上。
 *
 * 改成 internal 顶层函数，是因为**画布画连线也要用它**。
 * 参考实现的连线颜色直接取针脚色（CardLayoutView.dispatchDraw），于是"线是什么色"与
 * "它插在哪个针脚上"是同一条信息。在这里另写一份色表就会出现线与针脚对不上的情况，
 * 而那种偏差一眼就能看出来、又极难查 —— 所以两边必须共用同一个函数。
 */
@Composable
internal fun workflowPinColor(type: WorkflowPinType): Color = when (type) {
    WorkflowPinType.TEXT -> MaterialTheme.colorScheme.primary
    WorkflowPinType.NUMBER -> MaterialTheme.colorScheme.tertiary
    WorkflowPinType.BOOL -> MaterialTheme.colorScheme.secondary
    WorkflowPinType.CHOICE -> MaterialTheme.colorScheme.errorContainer
    // ANY 是"什么都能给/什么都能收"，用描边色：它不是一种类型，只是一个通配。
    WorkflowPinType.ANY -> MaterialTheme.colorScheme.outline
}

@Composable
private fun PinLabel(pin: WorkflowPinSpec) {
    if (pin.label.isBlank()) return
    Text(
        text = pin.label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 48.dp),
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


/**
 * 卡片水印：**这一步做什么**的图标。
 *
 * 参考实现把它印在卡片顶部居中（card_normal.xml 的 icon，alpha 0.1）。上一版用的是
 * 节点类型图标，五张 execute 卡水印一模一样 —— 扫一眼分不出「点击」和「截图」，
 * 而"一屏里全是 execute"才是工作流的常态。原生动作按动作 id 取图标，其余退回节点类型图标。
 */
private fun workflowNodeWatermark(node: WorkflowNode): androidx.compose.ui.graphics.vector.ImageVector =
    workflowActionIcon(node.config("bridge")) ?: nodeTypeIcon(node.type)

/**
 * 原生动作 → 图标。只覆盖确实需要区分的动作；没列到的返回 null，由调用方退回类型图标。
 *
 * 图标必须**已在本工程里用过**：Material 图标是逐项解析的，写一个没用过的名字就是
 * Unresolved reference，而本地没有 Kotlin 编译器，只能在 CI 上炸。
 */
internal fun workflowActionIcon(actionId: String): androidx.compose.ui.graphics.vector.ImageVector? =
    when (actionId) {
        // 每个动作**一个专属图标**。参考实现为 184 个动作逐个指定 drawable；
        // 之前这里只覆盖 31 个、且有 6 组共用同一个（点/坐标/按键全是 TouchApp），
        // 于是选择面板里一排卡片水印一模一样，扫一眼分不出来。
        //
        // 图标名全部取自本工程**已经编译过**的名字（grep 'Icons\\.Filled\\.' 现得的集合）。
        // Material 图标按名解析，写一个没引入的名字就是 Unresolved reference，
        // 而本地没有 Kotlin 编译器 —— 只能靠"只用见过的名字"来保证不炸 CI。
        "a11y_tree" -> Icons.Filled.AccountTree
        "a11y_click" -> Icons.Filled.TouchApp
        "a11y_tap" -> Icons.Filled.AdsClick
        "a11y_swipe" -> Icons.Filled.Swipe
        "a11y_text" -> Icons.Filled.TextFields
        "a11y_global" -> Icons.Filled.Language
        "a11y_shot" -> Icons.Filled.PhotoCamera
        "display_shot" -> Icons.Filled.ScreenShare
        "display_tap" -> Icons.Filled.Fingerprint
        "display_swipe" -> Icons.Filled.Layers
        "display_key" -> Icons.Filled.Keyboard
        "display_stop" -> Icons.Filled.Stop
        "toast" -> Icons.Filled.Campaign
        "vibrate" -> Icons.Filled.Vibration
        "notify_full" -> Icons.Filled.NewReleases
        "clip_set" -> Icons.Filled.ContentCopy
        "torch" -> Icons.Filled.Contrast
        "volume" -> Icons.Filled.SurroundSound
        "ringer" -> Icons.Filled.Tune
        "brightness" -> Icons.Filled.Opacity
        "rotation" -> Icons.Filled.Autorenew
        "camera" -> Icons.Filled.CameraAlt
        "tts" -> Icons.Filled.RecordVoiceOver
        "media_list" -> Icons.Filled.MusicNote
        "sensors_read" -> Icons.Filled.Sensors
        "network" -> Icons.Filled.Wifi
        "usage" -> Icons.Filled.Dashboard
        "calendar_list" -> Icons.Filled.Alarm
        "contacts_list" -> Icons.Filled.Person
        "location" -> Icons.Filled.Public
        "sms_list" -> Icons.Filled.Label
        "num_add" -> Icons.Filled.Add
        "num_sub" -> Icons.Filled.Minimize
        "num_mul" -> Icons.Filled.Close
        "num_div" -> Icons.Filled.Block
        "num_mod" -> Icons.Filled.Grain
        "num_abs" -> Icons.Filled.Sort
        "num_round" -> Icons.Filled.Refresh
        "num_max" -> Icons.Filled.ExpandMore
        "num_min" -> Icons.Filled.ExpandLess
        "num_random" -> Icons.Filled.AutoAwesome
        "num_eq" -> Icons.Filled.Check
        "num_lt" -> Icons.Filled.ChevronLeft
        "num_gt" -> Icons.Filled.ChevronRight
        "str_append" -> Icons.Filled.Link
        "str_substring" -> Icons.Filled.Segment
        "str_replace" -> Icons.Filled.Cached
        "str_split" -> Icons.Filled.CallSplit
        "str_regex" -> Icons.Filled.Search
        "str_trim" -> Icons.Filled.CleaningServices
        "str_len" -> Icons.Filled.FormatSize
        "str_upper" -> Icons.Filled.TextIncrease
        "str_lower" -> Icons.Filled.TextDecrease
        "str_equal" -> Icons.Filled.CheckCircle
        "str_contains" -> Icons.Filled.FilterList
        "str_encode" -> Icons.Filled.Translate
        "str_decode" -> Icons.Filled.Api
        "bool_and" -> Icons.Filled.ToggleOn
        "bool_not" -> Icons.Filled.LabelOff
        "json_get" -> Icons.Filled.DataObject
        "json_keys" -> Icons.Filled.ViewList
        "list_make" -> Icons.Filled.GridView
        "list_size" -> Icons.Filled.Article
        "bool_or" -> Icons.Filled.Share
        "list_get" -> Icons.Filled.Input
        "list_join" -> Icons.Filled.ReceiptLong
        "list_add" -> Icons.Filled.Update
        "list_contains" -> Icons.Filled.Security
        // 没列到的（未知动作 / 空 id / 基础节点）返回 null，调用方退回节点类型图标。
        // 这个 else 不能省：when 用在表达式位置喂给可空返回类型，
        // 少了它编译器会报 must be exhaustive —— 已在 CI 上炸过一次。
        else -> null
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
    val kind: WorkflowNodeFields.Kind,
    val choices: List<String> = emptyList(),
    /** 这个参数是否已接上线（NONE 档据此决定是否显示）。 */
    val linked: Boolean = false,
)

@Composable
private fun cardParamRows(
    node: WorkflowNode,
    /** 已接线的参数键集合（由宿主从边表算出）。见 [rowVisible] 的 NONE 档规则。 */
    linkedKeys: Set<String> = emptySet(),
): List<CardParamRowModel> {
    // 空字段保留为可编辑占位；填写状态不改变显示顺序，DATA_IN 与字段表一一对应。
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
            kind = field.kind,
            choices = field.choices,
            linked = field.key in linkedKeys,
        )
        if (raw.isBlank()) empty += row else filled += row
    }
    // DATA_IN 次序跟 fieldsFor 一致：保留原始字段顺序和全部行，不能因空值而重排或截断。
    val out = (filled + empty).sortedBy { row -> WorkflowNodeFields.fieldsFor(node).indexOfFirst { it.key == row.key } }.toMutableList()
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
                kind = WorkflowNodeFields.Kind.TEXT,
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
 * 一行参数：**左缘的数据入口针脚** + 标签 + 可编辑的值。
 *
 * 上一版把针脚排成独立的"引脚区"，于是同一张卡上出现两颗点（参数色点 + 引脚点）指着同
 * 一个东西，且引脚区与参数行各排各的、纵向对不上。参考实现里 ValuePin 本身就是"点 + 标签
 * + 输入框"的一行，所以这里把入口针脚**并进这一行**：
 *
 *  - [pin] 非空（这一行真的是一条数据入口）→ 行左缘就是那颗可拉线的针脚，颜色按类型；
 *  - [pin] 为空（例如兜底的主参数行）→ 画一颗小的种类色点，只作装饰，不可拉线。
 *
 * 空值画空心椭圆：留白会被读成"这行没内容"，而实际含义是"这个参数在、但还没填"。
 */
@Composable
private fun CardParamRow(
    row: CardParamRowModel,
    pin: WorkflowPinSpec?,
    copiedValue: Pair<WorkflowNodeFields.Kind, String>?,
    onCopyValue: (WorkflowNodeFields.Kind, String?) -> Unit,
    onEdit: ((key: String, value: String) -> Unit)?,
    onPinBounds: ((pinKind: String, rect: androidx.compose.ui.geometry.Rect) -> Unit)?,
    onPinIdBounds: ((pinId: String, pinKind: String, rect: androidx.compose.ui.geometry.Rect) -> Unit)?,
    onPinDrag: ((pinKind: String, anchorWindow: Offset, pointerWindow: Offset) -> Unit)?,
    onPinDrop: ((pointerWindow: Offset) -> Unit)?,
    /** 拉线时被悬停的针脚 id（见 [WorkflowNodeCard] 的 hoveredPinId）。 */
    hoveredPinId: String? = null,
    /** 已接线的针脚 id（见 [WorkflowNodeCard] 的 linkedPinIds）。 */
    linkedPinIds: Set<String> = emptySet(),
) {
    var choiceOpen by remember(row.key) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pin != null) {
            LinkablePinDot(
                pin, onPinBounds, onPinIdBounds, onPinDrag, onPinDrop,
                hovered = hoveredPinId == pin.id,
                linked = pin.id in linkedPinIds,
            )
        }
        else Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(12.dp).border(1.dp, row.color, CircleShape))
        }
        if (pin != null) {
            PinLabel(pin)
            Spacer(Modifier.width(4.dp))
        } else if (row.label.isNotBlank()) {
            Text(row.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 66.dp))
            Spacer(Modifier.width(4.dp))
        }
        when (row.kind) {
            WorkflowNodeFields.Kind.BOOL -> {
                androidx.compose.material3.Switch(
                    checked = row.value == "1" || row.value.equals("true", ignoreCase = true),
                    onCheckedChange = { onEdit?.invoke(row.key, if (it) "1" else "0") },
                    enabled = onEdit != null,
                    modifier = Modifier.width(48.dp).height(32.dp),
                )
            }
            WorkflowNodeFields.Kind.CHOICE -> {
                Box {
                    Surface(
                        onClick = { if (onEdit != null) choiceOpen = true },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.widthIn(min = 56.dp, max = 92.dp),
                    ) {
                        Text(row.value.ifBlank { "选择" } + "  ▾", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp))
                    }
                    DropdownMenu(expanded = choiceOpen, onDismissRequest = { choiceOpen = false }) {
                        for (choice in row.choices) DropdownMenuItem(text = { Text(choice) }, onClick = { onEdit?.invoke(row.key, choice); choiceOpen = false })
                    }
                }
            }
            WorkflowNodeFields.Kind.TEXT, WorkflowNodeFields.Kind.NUMBER, WorkflowNodeFields.Kind.MULTILINE -> {
                BasicTextField(
                    value = row.value,
                    onValueChange = { onEdit?.invoke(row.key, it) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                    singleLine = true,
                    enabled = onEdit != null,
                    modifier = Modifier.weight(1f).heightIn(min = 32.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }
        }
        if (!row.linked) {
            val paste = copiedValue?.first == row.kind && onEdit != null
            Box(
                Modifier.size(36.dp).clickable(enabled = paste || row.value.isNotEmpty()) {
                    if (paste) {
                        onEdit?.invoke(row.key, copiedValue!!.second)
                        onCopyValue(row.kind, null)
                    } else {
                        onCopyValue(row.kind, row.value)
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.dsh_wf_float_copy),
                    tint = if (paste) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            Spacer(Modifier.width(36.dp))
        }
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

/** 卡片动作条上的小图标按钮。激活态（已锁 / 全展开 / 删除已武装）用主色或 error。 */
@Composable
private fun CardActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    active: Boolean,
    onClick: () -> Unit,
    tint: Color? = null,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = tint ?: if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}

