package me.bmax.apatch.ui.component

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.bmax.apatch.R

/**
 * 节点选择面板：**工作流编辑器里所有「挑一个节点」的地方共用这一个组件**。
 *
 * ## 为什么抽成共用组件
 * 之前有两个入口各写各的：
 *  - 底部「加节点」→ 抽屉（本轮先改成了分类网格）；
 *  - 从输出口拉出落在空白 → 一个 AlertDialog，只有 7 个基础节点，连原生能力都没有。
 * 两个入口的选项集不一致，用户从哪进能看到的东西就不一样 —— 这是 bug，不是风格差异。
 *
 * ## 版式照点击助手的 SelectActionDialog
 * 参考实现（mr-bogey/TouchTool，GPL-3.0）的结构：
 *  - BottomSheet，高度约 75% 屏幕，可滚动列表；
 *  - 顶部一排**一级分组 chip**（预设 / 任务 / 变量）；
 *  - 下面一排**二级分组 chip**（该组下的分类）；
 *  - 主体是列表，每项带色点 + 名称 + 一句说明；
 *  - 右上角有**搜索**，可折叠。
 *
 * ## 关键差异：这里**不靠拖出**，所以列表可以滚
 * 上一版我把「从卡片往外拖到画布」当成核心交互，于是被「抽屉内容区不能滚动」
 * 那条约束绑死，只能用分类网格摊平，结果节点越藏越深。
 *
 * 点击助手是**点选即建**。照它之后列表可以滚动，节点按分类一层层铺开也不会溢出，
 * 这才是「不堆在一个页面」的正确解法：分组 + 可滚动列表。
 *
 * @Composable 参数见下。onPick 由调用方决定「新建在画布中央」还是「接在某节点之后」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowNodePicker(
    groups: List<NodePickerGroup>,
    onPick: (NodePickerEntry) -> Unit,
    onDismiss: () -> Unit,
    initialSearchOpen: Boolean = false,
) {
    var groupIndex by rememberSaveable { mutableStateOf(0) }
    var subIndex by rememberSaveable { mutableStateOf(0) }
    var searchOpen by rememberSaveable { mutableStateOf(initialSearchOpen) }
    var query by rememberSaveable { mutableStateOf("") }

    val group = groups.getOrNull(groupIndex)
    val sub = group?.subs?.getOrNull(subIndex)
    // 搜索优先于分类：一旦输入关键词，就在**整个一级分组**里找，而不是只在当前二级分类里。
    val visible = if (query.isBlank()) {
        sub?.entries.orEmpty()
    } else {
        group?.subs.orEmpty().flatMap { it.entries }.filter { e ->
            e.searchText.contains(query.trim(), ignoreCase = true)
        }
    }

    // 底部弹出层用 ModalBottomSheet：动画由系统提供（上滑入场、下滑退场），
    // 与参考实现（BottomSheetDialog + BottomSheetBehavior）是同一套行为。
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // 高度上限 75%（对齐参考实现 decorView*0.75），但**不强制撑到 75%**。
        //
        // 上一版写的是 fillMaxHeight(0.75f)，那是"必须占满 75% 高度"的意思：
        // 一个分类下只有 3 个节点时，面板照样拉到 0.75 屏，下面留一大片空白。
        // 用户的原话是"应该只弹出成半屏，这样就不会出现下面空的情况了"——
        // 要的是**跟着内容长**，而不是给内容一个固定的大外框。
        //
        // 改成 heightIn(max = ...)：内容少就矮、内容多才长到上限，上限之内可滚动。
        modifier = Modifier.heightIn(
            max = LocalConfiguration.current.screenHeightDp.dp * 0.75f,
        ),
    ) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
            // ── 标题行：标题 + 搜索开关 + 关闭 ──
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.dsh_workflow_editor_add_node),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    searchOpen = !searchOpen
                    if (!searchOpen) query = ""
                }) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = stringResource(R.string.dsh_wf_picker_search),
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.dsh_workflow_action_cancel),
                    )
                }
            }

            // ── 搜索框（折叠，点放大镜才展开）──
            //
            // 参考实现在这里是 TextInputLayout + startIconDrawable(搜索) + clear_text，
            // 视觉上就是一个「左放大镜、右清除」的输入框，这里用等价的 leading/trailing icon。
            if (searchOpen) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    placeholder = { Text(stringResource(R.string.dsh_wf_picker_search_hint)) },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, stringResource(R.string.dsh_wf_picker_clear))
                            }
                        }
                    },
                )
            }

            // ── 一级分类：一排连体按钮 ──
            //
            // 用 Connected 风格的按钮组（参考实现是 MaterialButtonToggleGroup.Connected）：
            // 相邻按钮共用边框、只有一个「被选中」的那颗是实心。上一版是一排独立圆角胶囊,
            // 选中态只是换个底色，看上去像六个互不相干的标签。
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                items(groups.size) { i ->
                    GroupButton(
                        label = stringResource(groups[i].titleRes),
                        selected = i == groupIndex,
                        first = i == 0,
                        last = i == groups.size - 1,
                        onClick = {
                            groupIndex = i
                            subIndex = 0
                        },
                    )
                }
            }

            // ── 主体：左边二级分类竖排 + 右边节点列表 ──
            //
            // 这是参考实现的布局：subGroup 是 width=80dp 的竖向按钮组，右侧 RecyclerView 占满。
            // 上一版把二级分类做成**又一行横向 chip**，于是当一级分类下只有一个二级分类时,
            // 就出现「执行」下孤零零重复一个「执行」的怪状 —— 截图里那个就是它。
            val subs = group?.subs.orEmpty()
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                if (subs.size > 1) {
                    Column(
                        Modifier
                            .width(84.dp)
                            .padding(end = 8.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        for ((i, sg) in subs.withIndex()) {
                            SubGroupButton(
                                label = stringResource(sg.titleRes),
                                selected = i == subIndex,
                                first = i == 0,
                                last = i == subs.size - 1,
                                onClick = { subIndex = i },
                            )
                        }
                    }
                }

                if (visible.isEmpty()) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.dsh_wf_picker_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(visible, key = { it.key }) { entry ->
                            PickerRow(entry = entry, onClick = { onPick(entry) })
                        }
                    }
                }
            }
        }
    }
}
/**
 * 一级分类的一个按钮。**连体**：相邻按钮共享边框，只有选中的那颗是实心。
 *
 * 对齐参考实现的 `MaterialButtonToggleGroup.Connected` —— `selectionRequired` +
 * `singleSelection`，视觉上是一整条被切开的按钮条，而不是几个独立的胶囊标签。
 *
 * 「连体」的做法：自己不带左右圆角，只有整条的头尾补圆角。上一版每颗都是独立胶囊，
 * 六个标签看上去像六件不相干的事，而它们其实是一个维度的选项。
 */
@Composable
private fun GroupButton(
    label: String,
    selected: Boolean,
    first: Boolean,
    last: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(
            topStart = if (first) 20.dp else 0.dp,
            bottomStart = if (first) 20.dp else 0.dp,
            topEnd = if (last) 20.dp else 0.dp,
            bottomEnd = if (last) 20.dp else 0.dp,
        ),
        color = bg,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
        )
    }
}

/**
 * 二级分类：**左侧竖排**按钮。
 *
 * 这是参考实现的形态（subGroup 是 width=80dp 的竖向 MaterialButtonToggleGroup）。
 * 上一版把它做成又一行横向 chip，于是「执行」下只有一个二级分类「执行」时，
 * 屏幕上就出现一个孤零零重复的「执行」—— 那正是真机截图里的怪状。
 *
 * 现在由调用方决定：二级分类**只有一个时不画这一列**（`subs.size > 1` 才显示），
 * 所以那种重复再也不会出现。
 */
@Composable
private fun SubGroupButton(
    label: String,
    selected: Boolean,
    first: Boolean,
    last: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(
            topStart = if (first) 14.dp else 0.dp,
            topEnd = if (first) 14.dp else 0.dp,
            bottomStart = if (last) 14.dp else 0.dp,
            bottomEnd = if (last) 14.dp else 0.dp,
        ),
        color = bg,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
        )
    }
}

/**
 * 列表里的一行：卡片 + 左侧标题/说明 + 右侧大幅淡色图标。
 *
 * 对齐参考实现的 `dialog_select_action_normal_item`：那个布局是 MaterialCardView，
 * `icon` 用大尺寸、`alpha=0.1`、靠右；文字区在左（标题 + 描述、描述 alpha 0.75）。
 *
 * 上一版只有「一个小色点 + 一行字」：色点几乎不传达信息（用户看不出蓝点是什么意思），
 * 也没有说明文字，于是列表变成一堆光秃秃的名字，只能靠猜。
 */
@Composable
private fun PickerRow(entry: NodePickerEntry, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Icon(
                imageVector = entry.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 10.dp)
                    .size(46.dp),
            )
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    stringResource(entry.titleRes),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 44.dp),
                )
                if (entry.descRes != 0) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        stringResource(entry.descRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = 44.dp),
                    )
                }
            }
        }
    }
}

/** 面板里的一个可选节点。 */
data class NodePickerEntry(
    /** 稳定 key：列表用。 */
    val key: String,
    val titleRes: Int,
    /** 说明；0 = 不显示。 */
    val descRes: Int,
    /** 列表右侧的大图标（参考实现每项都有图标）。 */
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val accent: Color,
    /** 搜索匹配用的文本（名称 + 说明）。 */
    val searchText: String,
    /** 具体要建哪种节点 —— 由调用方解释，面板不关心。 */
    val payload: Any,
)

/** 二级分组：一个分类下的若干节点。 */
data class NodePickerSubGroup(val titleRes: Int, val entries: List<NodePickerEntry>)

/** 一级分组。 */
data class NodePickerGroup(val titleRes: Int, val subs: List<NodePickerSubGroup>)
