package me.bmax.apatch.ui.screen.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.dsh.ScriptMarket
import me.bmax.apatch.dsh.Userscripts
import me.bmax.apatch.dsh.WebScripts
import me.bmax.apatch.util.DshWebCompat
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
 * 所以页面分成上下两段，顺序是有意的：
 *
 * - **上：应用内置**（不可删，逐条开关在行上）。它回答「应用往页面里注入了什么」；
 *   兼容垫片的**三档（自动/始终/从不）与当前内核版本**也只在这里选 —— 功能设置页那两行
 *   收进来了，因为它们本来就是这两条内置的档位。
 * - **中：我装的脚本**（总开关 + 粘贴/选文件 + 已导入列表）。它回答「我要不要跑自己的 JS」。
 * - **下：脚本市场**（GreasyFork 搜索 + 一键安装）。它回答「还有哪些能装」；装进来的东西
 *   落进**中段**那份列表，开关与删除都还是同一套。
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
            )
        },
    ) { padding ->
        UserscriptsContent(modifier = Modifier.padding(padding))
    }
}

/**
 * 用户脚本的正文：内置（应用自带那几段）/ 我装的 / 市场。
 *
 * 单独抽出来是因为**插件首页的「用户脚本」那一组直接复用它** —— 入口从「设置 → 功能」
 * 右上角搬到插件首页之后，同一个页面在两地各留一份实现，很快就会各长各的（这正是「对齐
 * UI」要防的事）。独立页的顶栏（标题 + 返回）留在 [UserscriptsScreen] 里；插件首页用的是
 * 它自己的搜索栏。
 *
 * @param revealMarket 递增的触发计数：插件首页那个「商店」按钮在脚本这一组时，用它把市场
 *   那一段滚进视野。用计数而不是布尔，是为了连点两次也各有一次反应。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UserscriptsContent(
    modifier: Modifier = Modifier,
    revealMarket: Int = 0,
    filter: String = "",
) {
    val context = LocalContext.current
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

    // ── 脚本市场（GreasyFork）──────────────────────────────────────────────
    //
    // 状态全留在这一页：搜一页、列出来、装了就 [reload]。市场只负责「把一段 .user.js
    // 拿回来」（见 [ScriptMarket]），落盘/同名覆盖/开关都还是 [Userscripts] 那一套。
    // 一次只发一个请求：手机上的网络本来就慢，并发只会让两边都超时。
    var marketQuery by remember { mutableStateOf("") }
    var marketHits by remember { mutableStateOf<List<ScriptMarket.Hit>>(emptyList()) }
    var marketPage by remember { mutableStateOf(1) }
    var marketFull by remember { mutableStateOf(false) }
    var marketBusy by remember { mutableStateOf(false) }
    var marketSearched by remember { mutableStateOf(false) }
    var marketFail by remember { mutableStateOf<ScriptMarket.Fail?>(null) }
    var marketCode by remember { mutableStateOf(0) }
    var installing by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()

    fun runSearch(next: Int, reset: Boolean) {
        if (marketBusy) return
        marketBusy = true
        marketFail = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { ScriptMarket.search(context, marketQuery.trim(), next) }
            }
            outcome.onSuccess { hits ->
                marketHits = if (reset) hits else (marketHits + hits).distinctBy { it.id }
                marketPage = next
                marketFull = hits.size >= ScriptMarket.PER_PAGE
                marketSearched = true
            }.onFailure { e ->
                val me = e as? ScriptMarket.MarketException
                marketFail = me?.fail ?: ScriptMarket.Fail.NETWORK
                marketCode = me?.code ?: 0
                // 「更多」失败时保留已有那一页，别把用户已经看到的清掉
                if (reset) {
                    marketHits = emptyList()
                    marketSearched = false
                    marketFull = false
                }
            }
            marketBusy = false
        }
    }

    fun installHit(hit: ScriptMarket.Hit) {
        if (installing != 0L) return
        installing = hit.id
        marketFail = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { Userscripts.install(context, ScriptMarket.fetch(context, hit.codeUrl)) }
            }
            outcome.onSuccess { id ->
                if (id == null) {
                    // 落盘失败（同名覆盖、目录不可写）：按内容问题报，细节在 Userscripts 里
                    marketFail = ScriptMarket.Fail.CONTENT
                } else {
                    reload()
                    showToast(context, context.getString(R.string.dsh_userscripts_installed))
                }
            }.onFailure { e ->
                val me = e as? ScriptMarket.MarketException
                marketFail = me?.fail ?: ScriptMarket.Fail.NETWORK
                marketCode = me?.code ?: 0
            }
            installing = 0L
        }
    }

    /** 去浏览器看那一页（作者、说明、评分都在那儿）。 */
    fun openMarketPage(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    val marketFailText: String? = marketFail?.let { fail ->
        when (fail) {
            ScriptMarket.Fail.NETWORK -> context.getString(R.string.dsh_userscripts_market_fail_network)
            ScriptMarket.Fail.SERVER ->
                context.getString(R.string.dsh_userscripts_market_fail_server, marketCode)
            ScriptMarket.Fail.CONTENT -> context.getString(R.string.dsh_userscripts_market_fail_content)
            ScriptMarket.Fail.SIZE -> context.getString(R.string.dsh_userscripts_market_fail_size)
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

    val scrollState = rememberScrollState()
    // 市场那一段在列里的纵向偏移（px）：由它自己的 modifier 量出来，供 revealMarket 滚过去
    var marketOffset by remember { mutableStateOf(0) }
    LaunchedEffect(revealMarket) {
        if (revealMarket > 0 && marketOffset > 0) scrollState.animateScrollTo(marketOffset)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
    ) {
            // ── 应用内置：与「我装的脚本」走同一条注入管道，但**不受总开关约束** ──
            // 所以放在最上面：这段说明的是「页面被注入什么」，与下面「我要不要跑自己的脚本」是
            // 两件事。也是「一处看得全」的那一处 —— 以前这些散在设置与代码里。
            Text(
                stringResource(R.string.dsh_userscripts_builtin_section),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
            )
            Text(
                stringResource(R.string.dsh_userscripts_builtin_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
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
                BuiltinRow(
                    title = stringResource(entry.titleRes),
                    summary = stringResource(entry.summaryRes),
                    checked = checked,
                    autoTag = null,
                    onToggle = onToggle,
                    extra = extra,
                )
            }

            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

            // ── 总开关：只管「我装的」那些脚本 ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
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
            Text(
                stringResource(R.string.dsh_userscripts_scope),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            // ── 装：贴一段，或从文件选 ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
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

            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            // 搜索框（插件首页那个）在这一组里过滤的是「我装的」：内置那几段是随包发的，
            // 不是"搜出来"的东西，所以不参与过滤。
            val shown = if (filter.isBlank()) scripts else scripts.filter {
                it.title.contains(filter, ignoreCase = true) || it.id.contains(filter, ignoreCase = true)
            }
            if (shown.isEmpty()) {
                Text(
                    stringResource(
                        if (filter.isBlank()) R.string.dsh_userscripts_empty
                        else R.string.dsh_userscripts_empty_filtered
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                Text(
                    // 有过滤词时报「命中几个 / 共几个」：只报总数会让人以为过滤没生效
                    if (filter.isBlank()) stringResource(R.string.dsh_userscripts_count, shown.size)
                    else stringResource(R.string.dsh_userscripts_count_filtered, shown.size, scripts.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
                )
                for (s in shown) {
                    ScriptRow(
                        script = s,
                        onToggle = { want ->
                            Userscripts.setEnabled(context, s.id, want)
                            reload()
                        },
                        onDelete = { pendingDelete = s.id },
                    )
                }
            }

            // ── 脚本市场（GreasyFork）──
            // 放在最后：先把「已经注入/已经装了哪些」说清，再谈「还能装什么」。
            HorizontalDivider(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .onGloballyPositioned { marketOffset = it.positionInParent().y.toInt() },
            )
            Text(
                stringResource(R.string.dsh_userscripts_market_section),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 2.dp),
            )
            Text(
                stringResource(R.string.dsh_userscripts_market_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = marketQuery,
                    onValueChange = { marketQuery = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.dsh_userscripts_market_query_hint)) },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { runSearch(1, reset = true) },
                    enabled = marketQuery.isNotBlank() && !marketBusy,
                ) { Text(stringResource(R.string.dsh_userscripts_market_search)) }
            }
            if (marketFailText != null) {
                Text(
                    marketFailText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (marketBusy) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
            if (!marketBusy && marketSearched && marketHits.isEmpty() && marketFailText == null) {
                Text(
                    stringResource(R.string.dsh_userscripts_market_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            for (hit in marketHits) {
                MarketRow(
                    hit = hit,
                    installed = scripts.any { it.title == hit.name },
                    busy = installing != 0L || marketBusy,
                    installing = installing == hit.id,
                    onInstall = { installHit(hit) },
                    onOpen = { if (hit.pageUrl.isNotBlank()) openMarketPage(hit.pageUrl) },
                )
            }
            if (marketFull && marketHits.isNotEmpty()) {
                TextButton(
                    onClick = { runSearch(marketPage + 1, reset = false) },
                    enabled = !marketBusy,
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text(stringResource(R.string.dsh_userscripts_market_more)) }
            }
            Spacer(Modifier.height(24.dp))
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
 * 内置脚本一行：标题 + 摘要 + （有开关就给开关，没有就标「常开」）。
 *
 * [checked] 为 null 表示这条没有开关（常开）；[autoTag] 是 compat 在 auto 档时的标记，
 * 说明勾选状态是**按内核自动算出来的**，不是用户上次点的那一下。
 */
@Composable
private fun BuiltinRow(
    title: String,
    summary: String,
    checked: Boolean?,
    autoTag: String?,
    onToggle: ((Boolean) -> Unit)?,
    extra: (@Composable () -> Unit)? = null,
) {
    ScriptCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    if (autoTag != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            autoTag,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (extra != null) {
                    Spacer(Modifier.height(4.dp))
                    extra()
                }
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
    }
}

/**
 * 脚本卡片的外壳：与插件页 [me.bmax.apatch.ui.screen.DshPluginScreen] 的插件卡片**同一套几何**。
 *
 * 插件那边是 `LazyColumn`（contentPadding 左右 16dp + spacedBy(12.dp)），脚本这一页是
 * 带 verticalScroll 的 `Column`，每一行自己加 padding —— 于是以前脚本是**裸行**、插件是卡片：
 * 左右起点虽然都是 16dp，但没有卡片背景、右边收到 8dp，看起来与插件页不是一套东西。
 * 这里把宿主几何收进一个壳里：左右 16dp、上下各 6dp（合起来正好 12dp，与插件列表的间距一致），
 * 卡片形状与底色照抄插件卡片。
 */
@Composable
private fun ScriptCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f),
        ),
    ) { content() }
}

/** 一行：名字 + 版本/时机/大小 + 说明与 `@match` + 开关 + 删除。 */
@Composable
private fun ScriptRow(
    script: Userscripts.Script,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    ScriptCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(script.title, style = MaterialTheme.typography.bodyLarge)
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

/**
 * 市场一行：名字 + 版本/作者/安装量/更新日 + 说明 + 安装（或「已安装」）/ 去浏览器。
 *
 * 说明压到 3 行：GreasyFork 的 description 有的很长，任它铺开会把一页挤成一条。
 */
@Composable
private fun MarketRow(
    hit: ScriptMarket.Hit,
    installed: Boolean,
    busy: Boolean,
    installing: Boolean,
    onInstall: () -> Unit,
    onOpen: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // 与上面那两种卡片同一套外边距（左右 16dp、上下各 6dp）
            .padding(horizontal = 16.dp, vertical = 6.dp),
        // 市场是「搜出来的东西」，所以用插件商店那张卡的样式（18dp / surfaceContainer），
        // 与「已经装好的」那两张卡区分开 —— 这也是本仓既有的两种列表语法。
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    hit.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        hit.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                        hit.author.takeIf { it.isNotBlank() }
                            ?.let { stringResource(R.string.dsh_userscripts_market_by, it) },
                        hit.installs.takeIf { it > 0 }
                            ?.let { stringResource(R.string.dsh_userscripts_market_installs, formatCount(it)) },
                        hit.updated.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (hit.description.isNotBlank()) {
                    Text(
                        hit.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (installed) {
                Text(
                    stringResource(R.string.dsh_userscripts_market_installed),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else if (installing) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onInstall, enabled = !busy) {
                    Text(stringResource(R.string.dsh_userscripts_market_install))
                }
            }
            IconButton(onClick = onOpen) {
                Icon(
                    Icons.Outlined.OpenInBrowser,
                    contentDescription = stringResource(R.string.dsh_userscripts_market_page),
                )
            }
        }
    }
}

/** 安装量：四位数以上折成 `4.7k` —— 一行里塞六位数字会把它挤成两行。 */
private fun formatCount(n: Long): String {
    if (n < 1_000) return n.toString()
    val k = n / 1000.0
    return if (k < 10) String.format(Locale.US, "%.1fk", k) else String.format(Locale.US, "%.0fk", k)
}
