package me.bmax.apatch.ui.component

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
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
 *  - BottomSheet 近全屏展开，顶部保留标题；
 *  - 左侧约 80dp 的纵向可滚分类栏，右侧两列可滚动作卡片；
 *  - 卡片是圆角主题色 Material 卡，标题 BodyMedium、说明 BodySmall 半透明，
 *    右下角叠一枚低透明度大图标；
 *  - 右下角小型搜索 FAB 展开折叠搜索框。
 *
 * DSH 目前只有「分类 → 节点」两层数据；参考实现中的任务/变量一级页签在这里不存在，
 * 因此不凭空添加空页签或假数据。
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
    var searchOpen by rememberSaveable { mutableStateOf(initialSearchOpen) }
    var query by rememberSaveable { mutableStateOf("") }

    val group = groups.getOrNull(groupIndex)
    // 搜索优先于分类：输入关键词就在**全部分类**里找，而不是只在当前分类里。
    val visible = if (query.isBlank()) {
        group?.entries.orEmpty()
    } else {
        groups.flatMap { it.entries }.filter { e ->
            e.searchText.contains(query.trim(), ignoreCase = true)
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 参考 dialog_select_action 是 match_parent；用内容下限避免小分类缩成浅片，
    // 用屏高上限避免长分类越过标题/系统栏。两端都按当前屏高计算。
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val bodyMinHeight = (screenHeight * 0.82f - 64.dp).coerceAtLeast(200.dp)
    val bodyMaxHeight = (screenHeight * 0.88f - 64.dp).coerceAtLeast(bodyMinHeight)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.heightIn(max = screenHeight * 0.94f),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            Column(Modifier.fillMaxWidth()) {
                // ── 标题行 ──
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.dsh_workflow_editor_add_node),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.dsh_workflow_action_cancel),
                        )
                    }
                }

                // ── 搜索框（折叠；入口移到右下角 FAB）──
                if (searchOpen) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
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

                // 任务/变量一级 tab 不在 DSH 的节点模型里，因此只保留真实存在的分类；
                // 它们按参考 dialog_select_action 的 80dp 左侧竖排组显示。
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .heightIn(max = bodyMaxHeight)
                        .heightIn(min = bodyMinHeight)
                        .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    Column(
                        Modifier
                            .width(84.dp)
                            .fillMaxHeight()
                            .padding(end = 4.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        for ((i, g) in groups.withIndex()) {
                            CategoryButton(
                                label = stringResource(g.titleRes),
                                selected = i == groupIndex,
                                first = i == 0,
                                last = i == groups.size - 1,
                                // 分类切换保持原有语义：选分类后清除跨类搜索词。
                                onClick = {
                                    groupIndex = i
                                    query = ""
                                },
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))

                    if (visible.isEmpty()) {
                        Box(
                            Modifier.weight(1f).fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.dsh_wf_picker_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        // 参考 RecyclerView 的两列 StaggeredGrid：各列独立滚动，奇数条目时
                        // 最后一张仍能完整滚到视口内。底部留出 FAB 安全区，不遮挡可点卡片。
                        val leftCol = visible.filterIndexed { i, _ -> i % 2 == 0 }
                        val rightCol = visible.filterIndexed { i, _ -> i % 2 == 1 }
                        Row(
                            Modifier.weight(1f).fillMaxHeight(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(
                                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                for (entry in leftCol) {
                                    PickerCard(entry = entry, onClick = { onPick(entry) })
                                }
                                Spacer(Modifier.height(80.dp))
                            }
                            Column(
                                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                for (entry in rightCol) {
                                    PickerCard(entry = entry, onClick = { onPick(entry) })
                                }
                                Spacer(Modifier.height(80.dp))
                            }
                        }
                    }
                }
            }
            // 搜索浮动入口：与参考实现右下角的小 FAB 同一位置；打开后转成关闭图标，
            // 继续保留原有「打开/收起搜索并清空关键词」行为。
            SmallFloatingActionButton(
                onClick = {
                    searchOpen = !searchOpen
                    if (!searchOpen) query = ""
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp),
            ) {
                Icon(
                    imageVector = if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = if (searchOpen) {
                        stringResource(R.string.dsh_workflow_action_cancel)
                    } else {
                        stringResource(R.string.dsh_wf_picker_search)
                    },
                )
            }
        }
    }
}

/**
 * 左栏的一个分类按钮。
 *
 * 形态对齐参考实现（点击助手）的 `subGroup` —— 那一列 `width=80dp` 的竖向按钮组。
 * 未选中是透明底 + 次级文字色，选中是 secondaryContainer 实心；头尾各补一次圆角，
 * 中间相邻处不补，看上去是一条被切开的竖条而不是一堆独立胶囊。
 */
@Composable
private fun CategoryButton(
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
            // 上下 8dp（原 12dp）：左栏有固定八项，每项多 8dp 就是 64dp，
            // 直接决定面板的可见高度 —— 分类项不该比卡片还占地方。
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/**
 * 一张卡片：标题 + 一句说明 + 右下角淡色图标。
 *
 * 对齐参考实现的 `dialog_select_action_normal_item`：MaterialCardView 承载，
 * 图标大尺寸、alpha≈0.1、靠右下；文字区在左（标题 BodyMedium，描述 BodySmall 半透明）。
 *
 * 上一版是「单列宽行 + 右侧一个大三角」：
 *  - 大三角是"从这里拖出去"时代的残留，现在是点选即建，它不表示任何操作；
 *  - 单列宽行让每张卡只有一个名字，没有说明，用户只能靠猜这个节点干什么。
 *
 * 现在两列栅格里每张卡都自带说明，扫一眼就知道建出来的是什么。
 */
@Composable
private fun PickerCard(entry: NodePickerEntry, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        // materialCardViewFilledStyle 的色面跟随当前 MaterialScheme，兼顾明暗主题。
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Icon(
                imageVector = entry.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 8.dp)
                    .size(48.dp),
            )
            Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                Text(
                    stringResource(entry.titleRes),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    // 给右下角图标让出位置：两列栅格本来就窄，不留白会撞字
                    modifier = Modifier.padding(end = 30.dp),
                )
                if (entry.descRes != 0) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        stringResource(entry.descRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        // 图标 48dp 叠在右下；说明也留同一侧安全边距，窄卡里才不会压字。
                        modifier = Modifier.padding(end = 30.dp),
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

/**
 * 一个分类：左栏的一个按钮 + 右栏这一个分类下的卡片。
 *
 * ## 为什么砍掉原来的三层
 *
 * 上一版照参考实现（点击助手）抄了**三层**：一级 chip / 二级 chip / 列表。
 * 但我们的数据只有两层——八个分类，每个分类下直接就是节点。于是每个分类都塞了一个
 * 和它**同名**的二级分组，然后：
 *  - 一级 chip 变成八颗横向排一行，第 8 颗「感知」被裁掉（真机截图就是这个）；
 *  - 二级 chip 因为恒为 1 个，被 `subs.size > 1` 永久隐藏，永远不出现。
 * 照抄结构却不看数据的层数，就会得到这种「两层数据套三层壳」的错位。
 *
 * 参考实现的真实形态其实是「左栏分类竖排 + 右栏卡片栅格」，我们的数据正好对得上。
 */
data class NodePickerGroup(val titleRes: Int, val entries: List<NodePickerEntry>)
