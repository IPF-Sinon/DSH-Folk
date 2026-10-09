package me.bmax.apatch.ui.screen.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.ScriptDetailScreenDestination
import com.ramcosta.composedestinations.generated.destinations.ScriptMarketScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import me.bmax.apatch.R
import me.bmax.apatch.dsh.Userscripts
import me.bmax.apatch.dsh.WebScripts
import me.bmax.apatch.ui.component.ModuleLabel
import me.bmax.apatch.util.DshWebCompat
import me.bmax.apatch.util.ui.HomeBottomSpacer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.util.ui.showToast

/**
 * 「用户脚本」管理页：一个不需要发版的扩展口。
 *
 * 装一个 `.user.js`，它会在页面脚本之前跑（document-start），于是能改我们自己的界面 —— 与
 * 应用内置的那几段（见 [WebScripts.BUILTINS]）走的是**同一条注入管道**，区别只在于把"贴一段
 * 试试"的门槛降到不用发版。语义与边界（只服务本应用自己的页面、`@run-at` 三档、SPA 路由不
 * 重跑、同名不覆盖）都写在 [Userscripts] 的 KDoc 里。
 *
 * 这一页**故意是原生的**（不在 WebView 里）：一个坏脚本把页面弄白时，这里是唯一的退路。
 * 所以页面按「注入什么 / 我装了什么」分成两段，顺序是有意的：
 *
 * - **上：应用内置**（不可删，逐条开关在行上）。它回答「应用往页面里注入了什么」；
 *   兼容垫片的**三档（自动/始终/从不）与当前内核版本**也只在这里选 —— 功能设置页那两行
 *   收进来了，因为它们本来就是这两条内置的档位。
 * - **下：我装的脚本**（总开关 + 粘贴/选文件 + 已导入列表）。它回答「我要不要跑自己的 JS」。
 *
 * 脚本市场（GreasyFork 搜索 + 一键安装）搬去了独立页
 * [me.bmax.apatch.ui.screen.ScriptMarketScreen]：它是「去别处找东西」，与这一页「本机现在注入/
 * 装了哪些」不是一件事 —— 混在一列里既把页面拉长，又让插件首页那个「商店」按钮只能把列表
 * 滚过去。现在两处按钮都是直接开那一页，装回来的东西落进**下段**那份列表。
 *
 * 总开关只管下段：一个坏脚本把页面弄白时，内边距/无障碍/兼容垫片还得在 —— 那正是这一页
 * 能把界面救回来的前提。
 */
@Destination<RootGraph>
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserscriptsScreen(navigator: DestinationsNavigator) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dsh_userscripts_title)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                // 市场入口与插件首页那个「商店」按钮同一形态（同样的图标与无障碍名）：
                // 同一件事在两处出现时，长得一样才不用重新认一遍。
                actions = {
                    IconButton(onClick = { navigator.navigate(ScriptMarketScreenDestination) }) {
                        Icon(
                            Icons.Outlined.Storefront,
                            contentDescription = stringResource(R.string.dsh_userscripts_market_section),
                        )
                    }
                },
            )
        },
    ) { padding ->
        UserscriptsContent(
            modifier = Modifier.padding(padding),
            onOpen = { navigator.navigate(ScriptDetailScreenDestination(scriptId = it.id)) },
            // 没记来源的那条：更新只能去市场按名字找一遍（市场页支持带一个初始查询进来）
            onOpenMarket = { navigator.navigate(ScriptMarketScreenDestination(initialQuery = it)) },
        )
    }
}

/**
 * 用户脚本的正文：内置（应用自带那几段）/ 我装的 / 市场。
 *
 * 单独抽出来是因为**插件首页的「用户脚本」那一组直接复用它** —— 入口从「设置 → 功能」
 * 右上角搬到插件首页之后，同一个页面在两地各留一份实现，很快就会各长各的（这正是「对齐
 * UI」要防的事）。独立页的顶栏（标题 + 返回 + 市场入口）留在 [UserscriptsScreen] 里；
 * 插件首页用的是它自己的搜索栏与「商店」按钮。
 *
 * 列表几何与插件列表**逐项一致**：`LazyColumn` + contentPadding 左右 16dp + 12dp 行距，
 * 卡片抄插件卡片那一套（见 [ScriptCard]）。两地本来就是同一份正文，几何再分叉会一眼看出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UserscriptsContent(
    modifier: Modifier = Modifier,
    filter: String = "",
    onOpen: (Userscripts.Script) -> Unit = {},
    onOpenMarket: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var master by remember { mutableStateOf(Userscripts.masterEnabled(context)) }
    var scripts by remember { mutableStateOf(Userscripts.list(context)) }
    // 内置那两条有开关的：勾选状态取**当前生效值**（compat 在 auto 下就是按内核算出来的结果）。
    // 拨动即落成 on / off —— auto 这一档只在设置页选，这里不重复表达三态。
    // 兼容垫片的档位：auto / on / off。**只在这一页选**（功能设置里那两行已收起）。
    var compatMode by remember { mutableStateOf(DshWebCompat.mode(context)) }
    var composerOn by remember { mutableStateOf(DshWebCompat.enterNewline(context)) }
    var showPaste by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scripts = Userscripts.list(context)
    }

    /**
     * 「更新」：记了来源的直接重拉并替换（[Userscripts.update] 会把旧文件删掉、开关搬过去）；
     * 没记来源的（从文件导入、或旧版本装的）去市场按名字找一遍 —— 不给一个点了没反应的按钮，
     * 也不假装能原地更新。网络与落盘都在 IO 线程上（与市场页同一条规矩）。
     */
    fun update(script: Userscripts.Script) {
        val ctx = context
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { Userscripts.update(ctx, script.id) }
            when (outcome) {
                Userscripts.UpdateOutcome.NO_SOURCE -> {
                    showToast(ctx, ctx.getString(R.string.dsh_userscripts_update_check_market))
                    onOpenMarket(script.title)
                }
                Userscripts.UpdateOutcome.FETCH_FAILED ->
                    showToast(ctx, ctx.getString(R.string.dsh_userscripts_update_failed))
                Userscripts.UpdateOutcome.UP_TO_DATE ->
                    showToast(ctx, ctx.getString(R.string.dsh_userscripts_update_same))
                Userscripts.UpdateOutcome.UPDATED -> {
                    reload()
                    showToast(ctx, ctx.getString(R.string.dsh_userscripts_update_done))
                }
            }
        }
    }


    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        val text = Userscripts.read(context, uri)
        if (text.isNullOrBlank()) {
            showToast(context, context.getString(R.string.dsh_userscripts_read_failed))
            return@rememberLauncherForActivityResult
        }
        if (Userscripts.install(context, text) == null) {
            showToast(context, context.getString(R.string.dsh_userscripts_read_failed))
        } else {
            reload()
            showToast(context, context.getString(R.string.dsh_userscripts_installed))
        }
    }


    // 与插件页**同一套列表几何**：LazyColumn + contentPadding 左右 16dp + spacedBy 12dp，
    // 卡片只管自己的形状与底色（见 [ScriptCard]）。以前这里是带 verticalScroll 的 Column、
    // 每一行自己加 padding，于是脚本是裸行、插件是卡片 —— 同一屏里出现两套几何。
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── 应用内置：与「我装的脚本」走同一条注入管道，但**不受总开关约束** ──
        // 所以放在最上面：这段说明的是「页面被注入什么」，与下面「我要不要跑自己的脚本」是
        // 两件事。也是「一处看得全」的那一处 —— 以前这些散在设置与代码里。
        item(key = "builtin-header") {
            Column {
                Text(
                    stringResource(R.string.dsh_userscripts_builtin_section),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.dsh_userscripts_builtin_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // 内置那几段的行数与内容都来自注册表（见 WebScripts.BUILTINS），不写死 5 行；
        // 每条一个列表项，与「我装的」共用同一个卡片壳与 12dp 行距。
        for (entry in WebScripts.BUILTINS) {
            // 这条内置有没有开关、接到哪：与 WebScripts.builtinEnabled 的分支一一对应
            // （compat / composer 有；inset / a11y-labels / blob-download 常开）。
            val onToggle: ((Boolean) -> Unit)?
            val checked: Boolean?
            val extra: (@Composable () -> Unit)?
            when (entry.id) {
                "compat" -> {
                    // 三档都在 extra 里，所以这里不给 Switch：一个状态两个控件只会
                    // 让人猜「到底哪个才算数」
                    onToggle = null
                    checked = null
                    extra = {
                        CompatPicker(
                            mode = compatMode,
                            kernel = DshWebCompat.kernel(context).display,
                            onPick = { m ->
                                compatMode = m
                                DshWebCompat.setMode(context, m)
                            },
                        )
                    }
                }
                "composer" -> {
                    onToggle = { on ->
                        composerOn = on
                        DshWebCompat.setEnterNewline(context, on)
                    }
                    checked = composerOn
                    extra = null
                }
                // 其余三条没有开关：内边距是布局前提，无障碍名字/blob 下载是补页面缺陷
                // （见 WebScripts 的类 KDoc）。给它们开关只会多出「用户关了之后来报 bug」。
                else -> {
                    onToggle = null
                    checked = null
                    extra = null
                }
            }
            item(key = "builtin:" + entry.id) {
                BuiltinRow(
                    title = stringResource(entry.titleRes),
                    summary = stringResource(entry.summaryRes),
                    checked = checked,
                    onToggle = onToggle,
                    extra = extra,
                )
            }
        }


        // ── 总开关：只管「我装的」那些脚本 ──
        item(key = "master") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.dsh_userscripts_master), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.dsh_userscripts_master_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = master,
                    onCheckedChange = {
                        master = it
                        Userscripts.setMasterEnabled(context, it)
                    },
                )
            }
        }
        item(key = "scope") {
            Text(
                stringResource(R.string.dsh_userscripts_scope),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── 装：贴一段，或从文件选 ──
        item(key = "install-actions") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { showPaste = true }) {
                    Text(stringResource(R.string.dsh_userscripts_paste))
                }
                TextButton(onClick = {
                    pickFile.launch(
                        Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }
                    )
                }) {
                    Text(stringResource(R.string.dsh_userscripts_pick))
                }
            }
        }

        // 搜索框（插件首页那个）在这一组里过滤的是「我装的」：内置那几段是随包发的，
        // 不是"搜出来"的东西，所以不参与过滤。
        val shown = if (filter.isBlank()) scripts else scripts.filter {
            it.title.contains(filter, ignoreCase = true) || it.id.contains(filter, ignoreCase = true)
        }
        if (shown.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(
                        if (filter.isBlank()) R.string.dsh_userscripts_empty
                        else R.string.dsh_userscripts_empty_filtered
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            item(key = "count") {
                Text(
                    // 有过滤词时报「命中几个 / 共几个」：只报总数会让人以为过滤没生效
                    if (filter.isBlank()) stringResource(R.string.dsh_userscripts_count, shown.size)
                    else stringResource(R.string.dsh_userscripts_count_filtered, shown.size, scripts.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            items(shown, key = { it.id }) { s ->
                ScriptRow(
                    script = s,
                    onToggle = { want ->
                        Userscripts.setEnabled(context, s.id, want)
                        reload()
                    },
                    onDelete = { pendingDelete = s.id },
                    onOpen = { onOpen(s) },
                    onUpdate = { update(s) },
                )
            }
        }

        item { HomeBottomSpacer() }
    }

    if (showPaste) {
        AlertDialog(
            onDismissRequest = { showPaste = false },
            title = { Text(stringResource(R.string.dsh_userscripts_paste)) },
            text = {
                Column {
                    Text(stringResource(R.string.dsh_userscripts_paste_hint))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pasted,
                        onValueChange = { pasted = it },
                        placeholder = { Text("==UserScript==") },
                        minLines = 4,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = pasted.isNotBlank(),
                    onClick = {
                        if (Userscripts.install(context, pasted) == null) {
                            showToast(context, context.getString(R.string.dsh_userscripts_read_failed))
                        } else {
                            pasted = ""
                            showPaste = false
                            reload()
                            showToast(context, context.getString(R.string.dsh_userscripts_installed))
                        }
                    },
                ) { Text(stringResource(R.string.dsh_userscripts_install)) }
            },
            dismissButton = {
                TextButton(onClick = { showPaste = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    val deleting = pendingDelete
    if (deleting != null) {
        val title = scripts.firstOrNull { it.id == deleting }?.title ?: deleting
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.dsh_userscripts_delete)) },
            text = { Text(title) },
            confirmButton = {
                TextButton(onClick = {
                    Userscripts.remove(context, deleting)
                    pendingDelete = null
                    reload()
                }) { Text(stringResource(R.string.dsh_userscripts_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/**
 * 内置脚本一行：与插件页的内置条目（[me.bmax.apatch.ui.screen.DshPluginScreen] 的
 * BuiltInHostPluginItem）同一套长相 —— 「内置」标签 + 「不可卸载」那行版本文案 + 标题/开关，
 * 最后是摘要。这几个内置的正文随包发布（见 [WebScripts.BUILTINS]），只能开关、不能删，
 * 所以这里没有删除按钮；标签与那行文案直接复用插件页的两个字符串，不再造同义的新串。
 *
 * [checked] 为 null 表示这条没有开关（常开）。
 */
@Composable
private fun BuiltinRow(
    title: String,
    summary: String,
    checked: Boolean?,
    onToggle: ((Boolean) -> Unit)?,
    extra: (@Composable () -> Unit)? = null,
) {
    ScriptCard {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            ModuleLabel(
                text = stringResource(R.string.dsh_plugin_builtin_label),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.dsh_host_plugin_version),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (checked != null && onToggle != null) {
                    Switch(checked = checked, onCheckedChange = onToggle)
                } else if (extra == null) {
                    // 只有「真的没有控件」的行才标常开：compat 的控件在 extra 里（三档）
                    Text(
                        stringResource(R.string.dsh_userscripts_builtin_always_on),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (extra != null) {
                Spacer(Modifier.height(8.dp))
                extra()
            }
        }
    }
}

/**
 * 脚本卡片的外壳：与插件页 [me.bmax.apatch.ui.screen.DshPluginScreen] 的插件卡片**同一套几何**
 * —— `fillMaxWidth()` + 20dp 圆角 + `secondaryContainer` 0.2，抄自那张卡。
 *
 * 左右 16dp 与行间 12dp 交给列表（`LazyColumn` 的 contentPadding + spacedBy），与插件页一样：
 * 卡片自己不夹带外边距，否则两页的左右起点、行距都会各差一层。
 */
@Composable
private fun ScriptCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f),
        ),
    ) { content() }
}

/** 一行：名字 + 版本/时机/大小 + 说明与 `@match` + 开关 + 更新 + 移除。点整行进详情页。 */
@Composable
private fun ScriptRow(
    script: Userscripts.Script,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onUpdate: () -> Unit,
) {
    ScriptCard {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    script.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    listOfNotNull(
                        script.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                        "run-at " + script.runAt,
                        "${script.bytes} B",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (script.description.isNotBlank()) {
                    Text(
                        script.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (script.matches.isNotEmpty()) {
                    Text(
                        script.matches.joinToString("  "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            // 更新放在开关左边：它是"内容层面"的动作，开关是"跑不跑"的动作，先内容后开关
            IconButton(onClick = onUpdate) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.dsh_userscripts_update))
            }
            Switch(checked = script.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.dsh_userscripts_delete))
            }
        }
    }
}

/**
 * 兼容垫片的三档：自动 / 始终 / 从不 —— 连同当前内核版本。
 *
 * 从功能设置页收进来的：它本来就是**这一条内置**的档位，和旁边几条摆在一起才看得出
 * 「页面被注入了什么、为什么」。auto 的含义是「按内核判断」，所以必须把内核版本亮
 * 出来 —— 否则「自动」等于没说（用户没法知道它这次到底注没注）。
 */
@Composable
private fun CompatPicker(mode: String, kernel: String, onPick: (String) -> Unit) {
    Column {
        Text(
            stringResource(R.string.dsh_userscripts_builtin_kernel, kernel.ifEmpty { "?" }),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (m in listOf(DshWebCompat.MODE_AUTO, DshWebCompat.MODE_ON, DshWebCompat.MODE_OFF)) {
                TextButton(
                    onClick = { onPick(m) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        stringResource(modeLabel(m)),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (m == mode) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

private fun modeLabel(mode: String): Int = when (mode) {
    DshWebCompat.MODE_ON -> R.string.dsh_webui_compat_on
    DshWebCompat.MODE_OFF -> R.string.dsh_webui_compat_off
    else -> R.string.dsh_webui_compat_auto
}

