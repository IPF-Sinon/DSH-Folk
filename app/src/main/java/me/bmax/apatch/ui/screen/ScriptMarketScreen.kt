package me.bmax.apatch.ui.screen

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.dsh.ScriptMarket
import me.bmax.apatch.dsh.Userscripts
import me.bmax.apatch.ui.component.ModuleLabel
import me.bmax.apatch.ui.component.SearchAppBar
import me.bmax.apatch.util.ui.HomeBottomSpacer
import me.bmax.apatch.util.ui.showToast

/**
 * 脚本市场（GreasyFork 搜索 + 一键安装）。
 *
 * 从用户脚本页里搬出来的一页：市场是「去别处找东西」，与那一页「本机现在注入/装了哪些」
 * 不是一件事 —— 混在一列里既把页面拉长，又让插件首页那个「商店」按钮只能**把列表滚过去**
 * （`revealMarket` 那套计数触发）。现在两边都是直接开这一页。
 *
 * 外壳与列表照 [DshPluginStoreScreen] 的写法：`SearchAppBar`（搜索在这条栏上）+ 瀑布流 +
 * `surfaceContainer` 的 18dp 卡片 —— 两个「商店」长得一样，用户不用重新认一遍。
 *
 * 数据源沿用 [ScriptMarket]；落盘/同名覆盖/开关都还是 [Userscripts] 那一套，所以这里只负责
 * 「搜一页、列出来、装进去」，装完把已装那份列表重读一次用来标「已安装」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun ScriptMarketScreen(navigator: DestinationsNavigator, initialQuery: String = "") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 已装的脚本：用来把结果标成「已安装」。装完一趟要重读，所以是状态而不是查一次。
    var scripts by remember { mutableStateOf(Userscripts.list(context)) }

    fun reload() {
        scripts = Userscripts.list(context)
    }

    // 从脚本页那条「没记来源」的更新进来时带一个初始查询：直接帮用户搜一遍同名脚本
    var marketQuery by remember { mutableStateOf(initialQuery) }
    var hits by remember { mutableStateOf<List<ScriptMarket.Hit>>(emptyList()) }
    var page by remember { mutableStateOf(1) }
    var full by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var fail by remember { mutableStateOf<ScriptMarket.Fail?>(null) }
    var code by remember { mutableStateOf(0) }
    var installing by remember { mutableStateOf(0L) }

    // 一次只发一个请求：手机上的网络本来就慢，并发只会让两边都超时。
    fun runSearch(next: Int, reset: Boolean) {
        if (busy || marketQuery.isBlank()) return
        busy = true
        fail = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { ScriptMarket.search(context, marketQuery.trim(), next) }
            }
            outcome.onSuccess { found ->
                hits = if (reset) found else (hits + found).distinctBy { it.id }
                page = next
                full = found.size >= ScriptMarket.PER_PAGE
                searched = true
            }.onFailure { e ->
                val me = e as? ScriptMarket.MarketException
                fail = me?.fail ?: ScriptMarket.Fail.NETWORK
                code = me?.code ?: 0
                // 「更多」失败时保留已有那一页，别把用户已经看到的清掉
                if (reset) {
                    hits = emptyList()
                    searched = false
                    full = false
                }
            }
            busy = false
        }
    }

    // 带初始查询进来的，进来就搜一次（只一次：initialQuery 在本页生命周期里不再变）
    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank()) runSearch(1, reset = true)
    }

    fun installHit(hit: ScriptMarket.Hit) {
        if (installing != 0L) return
        installing = hit.id
        fail = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val id = Userscripts.install(context, ScriptMarket.fetch(context, hit.codeUrl))
                    // 记下来源：脚本页那条「更新」才知道去哪拉新的（不往用户正文里塞字段）
                    if (id != null) Userscripts.rememberSource(context, id, hit.codeUrl)
                    id
                }
            }
            outcome.onSuccess { id ->
                if (id == null) {
                    // 落盘失败（同名覆盖、目录不可写）：按内容问题报，细节在 Userscripts 里
                    fail = ScriptMarket.Fail.CONTENT
                } else {
                    reload()
                    showToast(context, context.getString(R.string.dsh_userscripts_installed))
                }
            }.onFailure { e ->
                val me = e as? ScriptMarket.MarketException
                fail = me?.fail ?: ScriptMarket.Fail.NETWORK
                code = me?.code ?: 0
            }
            installing = 0L
        }
    }

    /** 去浏览器看那一页（作者、说明、评分都在那儿）。 */
    fun openMarketPage(url: String) {
        if (url.isBlank()) return
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    val failText: String? = fail?.let { f ->
        when (f) {
            ScriptMarket.Fail.NETWORK -> context.getString(R.string.dsh_userscripts_market_fail_network)
            ScriptMarket.Fail.SERVER ->
                context.getString(R.string.dsh_userscripts_market_fail_server, code)
            ScriptMarket.Fail.CONTENT -> context.getString(R.string.dsh_userscripts_market_fail_content)
            ScriptMarket.Fail.SIZE -> context.getString(R.string.dsh_userscripts_market_fail_size)
        }
    }

    Scaffold(
        topBar = {
            SearchAppBar(
                title = { Text(stringResource(R.string.dsh_userscripts_market_section)) },
                searchText = marketQuery,
                onSearchTextChange = { marketQuery = it },
                // 清空只清关键词，已搜到的结果留着：用户多半是想直接点进去看
                onClearClick = { marketQuery = "" },
                onBackClick = { navigator.popBackStack() },
                // 键盘上的「搜索」与右上角那个放大镜走同一个入口（都是重新搜第一页）
                onConfirm = { runSearch(1, reset = true) },
                dropdownContent = {
                    IconButton(
                        onClick = { runSearch(1, reset = true) },
                        enabled = marketQuery.isNotBlank() && !busy,
                    ) {
                        Icon(
                            Icons.Outlined.Search,
                            contentDescription = stringResource(R.string.dsh_userscripts_market_search),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Adaptive(minSize = 160.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalItemSpacing = 12.dp,
        ) {
            item(span = StaggeredGridItemSpan.FullLine, key = "market-head") {
                MarketHead(
                    hint = stringResource(R.string.dsh_userscripts_market_hint),
                    queryHint = stringResource(R.string.dsh_userscripts_market_query_hint),
                    emptyText = stringResource(R.string.dsh_userscripts_market_empty),
                    busy = busy,
                    searched = searched,
                    showEmpty = searched && hits.isEmpty() && failText == null,
                    failText = failText,
                )
            }
            items(hits, key = { it.id }) { hit ->
                MarketTile(
                    hit = hit,
                    // 判据与安装时同一处状态：同名即已装（Userscripts 落盘按标题命名）
                    installed = scripts.any { it.title == hit.name },
                    busy = busy,
                    installing = installing == hit.id,
                    onInstall = { installHit(hit) },
                    onOpen = { openMarketPage(hit.pageUrl) },
                )
            }
            if (full && hits.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine, key = "market-more") {
                    TextButton(
                        onClick = { runSearch(page + 1, reset = false) },
                        enabled = !busy,
                    ) { Text(stringResource(R.string.dsh_userscripts_market_more)) }
                }
            }
            item(span = StaggeredGridItemSpan.FullLine, key = "market-bottom") { HomeBottomSpacer() }
        }
    }
}

/**
 * 结果之前的那一段：说明 + 当前状态（还没搜 / 正在搜 / 搜失败 / 没结果）。
 *
 * 四态分开写，合成一条会把「还没搜」说成「没有结果」——那正是用户第一次进来时看到的画面。
 */
@Composable
private fun MarketHead(
    hint: String,
    queryHint: String,
    emptyText: String,
    busy: Boolean,
    searched: Boolean,
    showEmpty: Boolean,
    failText: String?,
) {
    Column {
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            failText != null -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    failText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            busy -> {
                Spacer(Modifier.height(8.dp))
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            showEmpty -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            !searched -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    queryHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 市场一张卡：名字 + 作者，安装量/版本/更新日的标签行，说明压到 4 行，右下角两个动作。
 *
 * 卡片样式照 [DshPluginStoreScreen] 的商店瓦片（18dp / `surfaceContainer` / 内边距 14dp），
 * 与「已经装好的」那两张卡（20dp / `secondaryContainer` 0.2）区分开 —— 这是本仓既有的两种
 * 列表语法。两个动作都是显式的：装（或「已安装」）与去浏览器看脚本页；没做成整卡点击，
 * 因为点一下就把用户带出应用比点一个明确的图标更容易误触。
 */
@Composable
private fun MarketTile(
    hit: ScriptMarket.Hit,
    installed: Boolean,
    busy: Boolean,
    installing: Boolean,
    onInstall: () -> Unit,
    onOpen: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = hit.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (hit.author.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.dsh_userscripts_market_by, hit.author),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onOpen, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Outlined.OpenInBrowser,
                        contentDescription = stringResource(R.string.dsh_userscripts_market_page),
                    )
                }
                if (installed) {
                    FilledTonalIconButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = stringResource(R.string.dsh_userscripts_market_installed),
                        )
                    }
                } else if (installing) {
                    FilledTonalIconButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.size(40.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                } else {
                    FilledTonalIconButton(
                        onClick = onInstall,
                        enabled = !busy,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = stringResource(R.string.dsh_userscripts_market_install),
                        )
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hit.installs > 0) {
                    ModuleLabel(
                        text = stringResource(
                            R.string.dsh_userscripts_market_installs,
                            formatCount(hit.installs),
                        ),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                if (hit.version.isNotBlank()) {
                    ModuleLabel(
                        text = "v${hit.version}",
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (hit.updated.isNotBlank()) {
                Text(
                    text = hit.updated,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (hit.description.isNotBlank()) {
                Text(
                    text = hit.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
