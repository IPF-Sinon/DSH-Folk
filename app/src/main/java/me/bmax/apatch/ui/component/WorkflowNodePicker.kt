package me.bmax.apatch.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.bmax.apatch.R
import me.bmax.apatch.dsh.WorkflowNodeType
import me.bmax.apatch.dsh.WorkflowStore

/** 面板顶部的三段标签：预设 / 任务 / 变量（对齐参考实现 SelectActionDialog 的 GroupType）。 */
private val PICKER_TABS = listOf(
    R.string.dsh_wf_picker_tab_preset,
    R.string.dsh_wf_picker_tab_task,
    R.string.dsh_wf_picker_tab_var,
)

private const val PICKER_TAB_PRESET = 0
private const val PICKER_TAB_TASK = 1
private const val PICKER_TAB_VAR = 2

/**
 * 节点选择面板：工作流编辑器里所有「挑一个节点」的地方共用这一个组件。
 *
 * ## 为什么抽成共用组件
 * 之前有两个入口各写各的：底部「加节点」→ 抽屉；从输出口拉出落在空白 → 一个只有 7 个
 * 基础节点的 AlertDialog。两个入口的选项集不一致，用户从哪进能看到的东西就不一样。
 *
 * ## 版式照点击助手的 SelectActionDialog
 * 参考实现（mr-bogey/TouchTool，GPL-3.0）是**整页贴边贴底**的：顶部三段标签
 * （预设 / 任务 / 变量）、左侧约 80dp 的分类竖排、右侧两列可滚动作卡片、右下角搜索小 FAB。
 *
 * ## 为什么这一版必须换掉 ModalBottomSheet 的默认宽度
 * M3 的 `ModalBottomSheet` 有一个 `sheetMaxWidth` 默认值（BottomSheetDefaults.SheetMaxWidth，
 * 640dp）并且**居中**。手机竖屏时屏幕比它窄，看不出问题；一旦屏宽超过 640dp（平板、横屏、
 * 折叠屏展开），面板就缩成居中的一小块浮窗，左侧留一条背景 —— 用户连着两轮说的「飘起来了」
 * 就是这个。调 `heightIn` 的小数没有用：高度的锅是另一回事，宽度被限死时它永远是浮窗。
 * 传 `Dp.Unspecified` 才会「占满整个屏幕宽度」（官方文档该参数的原话）。
 *
 * 同理，上一版用 `height(IntrinsicSize.Min) + heightIn(max = ...)` 让面板跟着内容长，
 * 是因为 sheet 高度由内容撑；现在面板是整页的，主体直接 `weight(1f)` 吃掉剩余高度即可。
 *
 * ## 关于标题
 * 上一版顶部有一行「加节点」标题和关闭按钮。参考实现没有这行字：三段标签本身就是面板的
 * 身份，而关闭靠拖柄下滑与返回键。多一行标题只会把内容往下挤，所以这里删掉了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowNodePicker(
    groups: List<NodePickerGroup>,
    onPick: (NodePickerEntry) -> Unit,
    onDismiss: () -> Unit,
    initialSearchOpen: Boolean = false,
    /**
     * 当前流程已声明的变量。
     *
     * 变量页不能凭空造条目：它列的必须是这份流程真实拥有的变量（来自 `draft.variables`），
     * 否则用户点一下会建出一个指向不存在变量的取值节点。
     */
    variables: Map<String, String> = emptyMap(),
    /** 打开「流程内变量」编辑窗（宿主侧已有这个对话框）。null = 不提供该入口。 */
    onEditVariables: (() -> Unit)? = null,
) {
    var tab by rememberSaveable { mutableStateOf(PICKER_TAB_PRESET) }
    var groupIndex by rememberSaveable { mutableStateOf(0) }
    var searchOpen by rememberSaveable { mutableStateOf(initialSearchOpen) }
    var query by rememberSaveable { mutableStateOf("") }

    val context = LocalContext.current
    // 任务页读的是**真实落盘**的流程清单。
    //
    // 在 LaunchedEffect 里读而不是在 remember 里直接读：list() 要读目录里每个 JSON，
    // 放在组合阶段就是主线程磁盘 IO。切到任务页时读一次即可，切走不重读。
    var savedWorkflows by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(tab) {
        if (tab == PICKER_TAB_TASK) {
            savedWorkflows = runCatching {
                WorkflowStore.list(context).map { it.id to it.name }
            }.getOrDefault(emptyList())
        }
    }

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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Dp.Unspecified = 占满整个屏幕宽度。见文件头的说明：默认值 640dp 居中就是「飘」的成因。
        sheetMaxWidth = Dp.Unspecified,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            Column(Modifier.fillMaxWidth().fillMaxHeight()) {
                // ── 三段标签 ──
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for ((i, labelRes) in PICKER_TABS.withIndex()) {
                        PickerTab(
                            label = stringResource(labelRes),
                            selected = i == tab,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                tab = i
                                // 切页签清掉关键词：搜索词是按当前页的条目匹配的，留着会串页。
                                query = ""
                            },
                        )
                    }
                }

                // ── 搜索框（折叠；入口是右下角 FAB）──
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

                when (tab) {
                    PICKER_TAB_TASK -> TaskTab(
                        workflows = savedWorkflows.filter { (_, name) ->
                            query.isBlank() || name.contains(query.trim(), ignoreCase = true)
                        },
                        onPick = onPick,
                    )
                    PICKER_TAB_VAR -> VariableTab(
                        variables = variables.filterKeys { name ->
                            query.isBlank() || name.contains(query.trim(), ignoreCase = true)
                        },
                        onPick = onPick,
                        onEditVariables = onEditVariables,
                    )
                    else -> PresetTab(
                        groups = groups,
                        groupIndex = groupIndex,
                        visible = visible,
                        onSelectGroup = { i ->
                            groupIndex = i
                            query = ""
                        },
                        onPick = onPick,
                    )
                }
            }
            // 搜索浮动入口：与参考实现右下角的小 FAB 同一位置。
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

/** 一个页签：等宽胶囊，选中用 primary 实心（参考实现的 MaterialButtonToggleGroup 单选）。 */
@Composable
private fun PickerTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        )
    }
}

/**
 * 预设页：左侧分类竖排 + 右侧两列卡片。
 *
 * 两列不是随手定的：参考实现的 RecyclerView 就是 `spanCount=2` 的 StaggeredGrid。
 * 这里刻意不用 LazyVerticalGrid —— 面板主体在 Row 里拿到的是无界高度，Lazy 网格必须有
 * 确定高度才能测量，无界会直接抛 infinity maximum height。
 */
@Composable
private fun ColumnScope.PresetTab(
    groups: List<NodePickerGroup>,
    groupIndex: Int,
    visible: List<NodePickerEntry>,
    onSelectGroup: (Int) -> Unit,
    onPick: (NodePickerEntry) -> Unit,
) {
    Row(
        // weight(1f) 而不是 fillMaxHeight：Column 里的子项拿到的是**整列**的最大高度，
        // fillMaxHeight 会让主体和上面的标签行争满高，把标签挤出可视区。
        Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
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
                    onClick = { onSelectGroup(i) },
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (visible.isEmpty()) {
            PickerEmpty(stringResource(R.string.dsh_wf_picker_empty), Modifier.weight(1f))
        } else {
            TwoColumnCards(visible, onPick)
        }
    }
}

/**
 * 任务页：列出已保存的流程，点一下插入一个「跑那个流程」的 shell 节点。
 *
 * 这不是装饰：`WorkflowEngine` 的 SHELL 分支走 `DshRuntime.execRootfs(cmd)`，在容器里执行，
 * 而容器里就有 `dsh-workflow` CLI（`run --workflow <id>` 会经 `/workflow/run` 桥路由，
 * 需要非空 reason 并弹出审批）。所以这条命令是真能跑通的。
 *
 * 为什么不做成「直接运行」按钮：这里是在**编辑流程**，用户要的是「让这一步去跑另一个流程」，
 * 而不是现在就执行一次。运行入口在流程列表页，那里才有审批语义。
 */
@Composable
private fun ColumnScope.TaskTab(
    workflows: List<Pair<String, String>>,
    onPick: (NodePickerEntry) -> Unit,
) {
    if (workflows.isEmpty()) {
        PickerEmpty(stringResource(R.string.dsh_wf_picker_task_empty))
        return
    }
    val entries = workflows.map { (id, name) ->
        // reason 里的引号必须去掉：它会原样进 shell 命令，留一个双引号就把命令截断了。
        val safeName = name.replace("\"", "")
        NodePickerEntry(
            key = "wf_" + id,
            titleRes = 0,
            descRes = R.string.dsh_wf_picker_task_desc,
            icon = Icons.Filled.PlayArrow,
            accent = MaterialTheme.colorScheme.primary,
            searchText = name,
            title = name,
            payload = PickerInsert(
                type = WorkflowNodeType.EXECUTE,
                name = name,
                config = mapOf(
                    "action" to "shell",
                    "name" to name,
                    "cmd" to "dsh-workflow run --workflow " + id + " --reason \"workflow:" + safeName + "\"",
                ),
            ),
        )
    }
    Row(
        Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
    ) {
        TwoColumnCards(entries, onPick)
    }
}

/**
 * 变量页：列出**这份流程**已声明的变量，点一下插入一个取值节点。
 *
 * 为什么变量页不是一张表而是可点的卡片：编辑器里的变量页和「加节点」是同一个动作的两半 ——
 * 用户翻到变量页，多半是想让某一步把某个变量取出来用。只显示名字不能落地成一步，
 * 所以这里点一下就建一个 EXTRACT 节点，`var` 预填这个变量名。
 */
@Composable
private fun ColumnScope.VariableTab(
    variables: Map<String, String>,
    onPick: (NodePickerEntry) -> Unit,
    onEditVariables: (() -> Unit)?,
) {
    Column(
        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        if (variables.isEmpty()) {
            PickerEmpty(stringResource(R.string.dsh_wf_picker_var_empty))
        } else {
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((name, value) in variables) {
                    val entry = NodePickerEntry(
                        key = "var_" + name,
                        titleRes = 0,
                        descRes = R.string.dsh_wf_picker_var_desc,
                        icon = Icons.Filled.PlayArrow,
                        accent = MaterialTheme.colorScheme.tertiary,
                        searchText = name,
                        title = name,
                        desc = if (value.isBlank()) null else value,
                        payload = PickerInsert(
                            type = WorkflowNodeType.EXTRACT,
                            name = name,
                            config = mapOf(
                                "source" to "lastOutput",
                                "mode" to "text",
                                "var" to name,
                            ),
                        ),
                    )
                    PickerCard(entry = entry, onClick = { onPick(entry) })
                }
            }
        }
        if (onEditVariables != null) {
            Spacer(Modifier.height(8.dp))
            Surface(
                onClick = onEditVariables,
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.dsh_wf_picker_var_edit),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                )
            }
            Spacer(Modifier.height(72.dp))
        }
    }
}

/** 两列卡片栅格（各列独立滚动，奇数条目时最后一张也能完整滚进视口）。 */
@Composable
private fun RowScope.TwoColumnCards(
    entries: List<NodePickerEntry>,
    onPick: (NodePickerEntry) -> Unit,
) {
    val leftCol = entries.filterIndexed { i, _ -> i % 2 == 0 }
    val rightCol = entries.filterIndexed { i, _ -> i % 2 == 1 }
    Row(
        Modifier.weight(1f).fillMaxHeight(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (col in listOf(leftCol, rightCol)) {
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (entry in col) {
                    PickerCard(entry = entry, onClick = { onPick(entry) })
                }
                // 底部留出 FAB 安全区，不让最后一张卡被搜索按钮盖住。
                Spacer(Modifier.height(80.dp))
            }
        }
    }
}

/**
 * 空状态：一行灰字居中。
 *
 * 必须能收 modifier：在 Row 里时由调用方给 weight(1f)，否则 fillMaxWidth 会去抢整行宽度，
 * 把左边的分类栏挤变形。
 */
@Composable
private fun PickerEmpty(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().fillMaxHeight(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
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
            // 上下 8dp：左栏固定八项，每项多 8dp 就是 64dp，直接决定面板的可见高度。
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/**
 * 一张卡片：标题 + 一句说明 + 右下角淡色图标。
 *
 * 对齐参考实现的 `dialog_select_action_normal_item`：MaterialCardView 承载，
 * 图标大尺寸、alpha 约 0.1、靠右下；文字区在左（标题 BodyMedium，描述 BodySmall 半透明）。
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
                    // 任务页/变量页的标题是动态文本，没有对应字符串资源，标题就取 entry.title。
                    entry.title ?: stringResource(entry.titleRes),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    // 给右下角图标让出位置：两列栅格本来就窄，不留白会撞字。
                    modifier = Modifier.padding(end = 30.dp),
                )
                val desc = entry.desc ?: if (entry.descRes != 0) stringResource(entry.descRes) else null
                if (desc != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
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
    val icon: ImageVector,
    val accent: Color,
    /** 搜索匹配用的文本（名称 + 说明）。 */
    val searchText: String,
    /** 具体要建哪种节点 —— 由调用方解释，面板不关心。 */
    val payload: Any,
    /** 动态标题（任务页/变量页）；非空时优先于 [titleRes]。 */
    val title: String? = null,
    /** 动态说明；非空时优先于 [descRes]。 */
    val desc: String? = null,
)

/**
 * 「按给定 config 建一个节点」的载荷。
 *
 * 预设页的载荷是 `EditorNodeKind`（编辑器内部类型，面板看不到），任务页与变量页需要的却是
 * 「一个已经配好的节点」。把两者压成同一个类型会逼面板去认识编辑器内部结构，所以这里只描述
 * 结果（类型 + 名字 + config），由调用方翻译成一次 addNode。
 */
internal data class PickerInsert(
    val type: WorkflowNodeType,
    val name: String,
    val config: Map<String, String>,
)

/**
 * 一个分类：左栏的一个按钮 + 右栏这一个分类下的卡片。
 *
 * 参考实现的真实形态是「左栏分类竖排 + 右栏卡片栅格」，我们的数据正好对得上，
 * 所以不再套一层同名二级分组（那会让每个分类都塞一个和它同名的空壳）。
 */
data class NodePickerGroup(val titleRes: Int, val entries: List<NodePickerEntry>)
