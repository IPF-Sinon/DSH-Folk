package me.bmax.apatch.ui.component

import androidx.compose.foundation.background
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
@Composable
fun WorkflowNodePicker(
    groups: List<NodePickerGroup>,
    onPick: (NodePickerEntry) -> Unit,
    onDismiss: () -> Unit,
    /**
     * 进面板就直接展开搜索。顶栏的放大镜点完是"我要找某个节点"，再让他在面板里
     * 点一次放大镜才出现输入框，是白白多一次点击。
     */
    initialSearchOpen: Boolean = false,
) {
    // 一级 / 二级当前选中项。用 saveable 记住：转屏后不该跳回第一个分类。
    var groupIndex by rememberSaveable { mutableStateOf(0) }
    var subIndex by rememberSaveable { mutableStateOf(0) }
    var searchOpen by rememberSaveable { mutableStateOf(initialSearchOpen) }
    var query by rememberSaveable { mutableStateOf("") }

    val group = groups.getOrNull(groupIndex)
    val sub = group?.subs?.getOrNull(subIndex)
    // 搜索优先于分类：一旦输入关键词，就在**整个一级分组**里找，而不是只在当前二级分类里。
    //
    // 理由：用户输入关键词时已经放弃「按分类找」这条路了，还把他限制在当前分类里
    // 等于让他先切回去再搜。
    val visible = if (query.isBlank()) {
        sub?.entries.orEmpty()
    } else {
        group?.subs.orEmpty().flatMap { it.entries }.filter { e ->
            e.searchText.contains(query.trim(), ignoreCase = true)
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // 限高：面板占大半个屏幕，但上方始终留着画布 ——
                // 用户能看见自己从哪里拉出的这条线，也就知道自己加到了哪里。
                .heightIn(max = 560.dp),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
                // ── 标题行 + 搜索开关 ──
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
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

                if (searchOpen) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.dsh_wf_picker_search_hint)) },
                    )
                }

                // ── 一级分组 chips ──
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for ((i, g) in groups.withIndex()) {
                        PickerChip(
                            label = stringResource(g.titleRes),
                            selected = i == groupIndex,
                            onClick = {
                                groupIndex = i
                                subIndex = 0
                            },
                        )
                    }
                }

                // ── 二级分组 chips ──
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for ((i, s) in group?.subs.orEmpty().withIndex()) {
                        PickerChip(
                            label = stringResource(s.titleRes),
                            selected = i == subIndex,
                            onClick = { subIndex = i },
                            small = true,
                        )
                    }
                }

                // ── 节点列表（可滚动）──
                //
                // 这里能滚，是因为本面板不依赖拖出：点一下即建节点。
                // 一旦恢复成「按住卡片拖到画布」，滚动必然把手势吃掉。
                if (visible.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.dsh_wf_picker_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 380.dp),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
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

/** 一级 / 二级分组 chip。选中时用主色底，未选中时淡底。 */
@Composable
private fun PickerChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    small: Boolean = false,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
    ) {
        Text(
            label,
            style = if (small) {
                MaterialTheme.typography.labelSmall
            } else {
                MaterialTheme.typography.labelLarge
            },
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** 列表里的一行：色点 + 名称 + 说明。 */
@Composable
private fun PickerRow(entry: NodePickerEntry, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(entry.accent, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(entry.titleRes),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                if (entry.descRes != 0) {
                    Text(
                        stringResource(entry.descRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
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