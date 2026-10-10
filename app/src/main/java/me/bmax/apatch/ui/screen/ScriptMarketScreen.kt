package me.bmax.apatch.ui.screen

import android.app.Activity.RESULT_OK
import android.content.Intent
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
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
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
import com.ramcosta.composedestinations.generated.destinations.DshWebViewScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.dsh.ScriptMarket
import me.bmax.apatch.dsh.Userscripts
import me.bmax.apatch.ui.component.ModuleLabel
import me.bmax.apatch.ui.component.SearchAppBar
import me.bmax.apatch.ui.component.UserscriptLinkInstallDialog
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
    // 当前列的是不是「热门」：热门与搜索结果共用 hits / page / full，靠这个标志区分
    // 标题显示什么、以及「更多」该去取搜索的下一页还是热门的下一页。
    var popular by remember { mutableStateOf(false) }
    // 失败后「重试」按上一次那条路重来：true = 热门，false = 搜索。不能复用 popular ——
    // 热门失败时 popular 会被重置成 false，重试就会悄悄变成一次空查询的搜索。
    var retryPopular by remember { mutableStateOf(true) }

    // 「从链接安装」弹窗（粘贴 greasyfork 脚本页 / .user.js 地址）：输入与标红状态住在
    // 共用的 UserscriptLinkInstallDialog 里，这里只留「开没开」。
    var showLinkInstall by remember { mutableStateOf(false) }

    // 一次只发一个请求：手机上的网络本来就慢，并发只会让两边都超时。
    fun runSearch(next: Int, reset: Boolean) {
        if (busy || marketQuery.isBlank()) return
        retryPopular = false
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
                // 搜索结果一出来就不再是「热门」：标题与「更多」都按搜索走
                popular = false
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

    /**
     * 热门（精选）第一页/下一页。走 [ScriptMarket.popular]，与搜索同一个外壳。
     *
     * 失败处理与 [runSearch] 逐条一致：reset 那次才把已有结果清掉，翻页失败保留已看到的；
     * 失败文案就是主源失败的文案，旁边还给一个「去镜像站」的入口（见列表头部）。
     */
    fun runPopular(next: Int, reset: Boolean) {
        if (busy) return
        retryPopular = true
        busy = true
        fail = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { ScriptMarket.popular(context, next) }
            }
            outcome.onSuccess { found ->
                hits = if (reset) found else (hits + found).distinctBy { it.id }
                page = next
                full = found.size >= ScriptMarket.PER_PAGE
                searched = true
                popular = true
            }.onFailure { e ->
                val me = e as? ScriptMarket.MarketException
                fail = me?.fail ?: ScriptMarket.Fail.NETWORK
                code = me?.code ?: 0
                if (reset) {
                    hits = emptyList()
                    searched = false
                    full = false
                    popular = false
                }
            }
            busy = false
        }
    }

    // 带初始查询进来的，进来就搜一次（只一次：initialQuery 在本页生命周期里不再变）；
    // 没带查询（从「脚本市场」按钮直接进来）就先列一页热门 —— 空屏幕对着一个输入框
    // 是最差的首屏。
    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank()) runSearch(1, reset = true) else runPopular(1, reset = true)
    }

    /** 失败后的「重试」：按上一次那条路原样重来一次（首屏热门失败 = 重新拉热门）。 */
    fun retry() {
        if (retryPopular) runPopular(1, reset = true) else runSearch(1, reset = true)
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

    /**
     * 从链接安装（弹窗那条路）：把用户粘贴的东西先归一成可取的正文地址
     * （[ScriptMarket.normalizeInstallUrl]，认不出就不装、弹窗里标红），再走「拉正文 + 落盘 +
     * 记来源」这一条**与用户脚本页共用**的 [Userscripts.installFromUrl]。
     */
    fun installFromUrl(normalized: String) {
        if (installing != 0L) return
        // -1 是「链接安装」的哨兵值：结果卡的 id 都是正数，所以不会有卡片显示成正在装
        installing = -1L
        fail = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { Userscripts.installFromUrl(context, normalized) }
            }
            outcome.onSuccess { id ->
                if (id == null) {
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

    /** 本地安装：选一个 `.user.js`（与「用户脚本」页那条路同一套 read / install）。 */
    val pickLocal = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@rememberLauncherForActivityResult
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) { Userscripts.read(context, uri) }
            if (text.isNullOrBlank() || Userscripts.install(context, text) == null) {
                showToast(context, context.getString(R.string.dsh_userscripts_read_failed))
            } else {
                reload()
                showToast(context, context.getString(R.string.dsh_userscripts_installed))
            }
        }
    }

    /**
     * 镜像源的浏览入口：**应用内**打开（见 [DshWebViewScreen]），不再交给系统浏览器。
     *
     * 这条路的全部意义就是"接着刚才那次失败往下看" —— 甩去浏览器再回来，这一页的状态全没了。
     */
    fun openMirrorPage(url: String) {
        if (url.isBlank()) return
        navigator.navigate(DshWebViewScreenDestination(url = url))
    }

    /**
     * 去看某个脚本的页面（作者、说明、评分都在那儿）：**同一个应用内网页页**（[DshWebViewScreen]），
     * 不再交给系统浏览器。
     *
     * 用户要求两处统一：镜像站与应用内脚本页都是"看一眼就回来"的动作，交给系统浏览器就等于
     * 把用户甩出应用、回来时市场这页的搜索与滚动位置全丢。页面里的跳转也留在那一层；那里
     * 处理不了的（下载 .user.js、非网页协议）会给一句明确提示，不假装能装（见 [DshWebViewScreen]）。
     */
    fun openScriptPage(url: String) {
        if (url.isBlank()) return
        navigator.navigate(DshWebViewScreenDestination(url = url))
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

    // 「从链接安装」：与用户脚本页**共用同一个弹窗**（[UserscriptLinkInstallDialog]）——
    // 归一、标红、安装三件事只写一遍，两处入口的手感与边界因此不会漂移。
    if (showLinkInstall) {
        UserscriptLinkInstallDialog(
            onDismiss = { showLinkInstall = false },
            onInstall = { url -> installFromUrl(url) },
        )
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
                    // 「从链接安装」与插件商店同一个位置（顶栏右侧的链接图标）
                    IconButton(onClick = { showLinkInstall = true }) {
                        Icon(
                            Icons.Outlined.Link,
                            contentDescription = stringResource(R.string.dsh_userscripts_market_link_install),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            // 本地安装：与插件页那个 FAB 同形（同样的图标、文案与配色），选一个 .user.js
            FloatingActionButton(
                onClick = {
                    pickLocal.launch(
                        Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }
                    )
                },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(
                    Icons.Outlined.FolderOpen,
                    contentDescription = stringResource(R.string.dsh_local_install),
                )
            }
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
                    // 加载态与失败态都要有字，不能只留一个转圈（首屏一次请求慢/被墙时，
                    // 「只有一行灰字、底下全空」看起来就和白屏一样）——见用户报的那个 bug。
                    loadingText = stringResource(R.string.dsh_userscripts_market_loading),
                    retryText = stringResource(R.string.dsh_userscripts_market_retry),
                    emptyText = stringResource(
                        // 热门空了说「热门暂时没有」，搜索空了才是「没有结果」：两种空态不是一回事
                        if (retryPopular) R.string.dsh_userscripts_market_empty_popular
                        else R.string.dsh_userscripts_market_empty
                    ),
                    busy = busy,
                    searched = searched,
                    showEmpty = searched && hits.isEmpty() && failText == null,
                    failText = failText,
                    onRetry = { retry() },
                )
                // 主源失败时的**浏览入口**：镜像站是静态导航页（没有脚本正文/JSON），
                // 所以只能看、不能拿来装 —— 这也正是它只出现在这里的原因。看这一页在
                // **应用内**打开（见 openMirrorPage）：它是"接着刚才那次失败往下看"。
                if (failText != null) {
                    TextButton(onClick = { openMirrorPage(ScriptMarket.MIRROR_INDEX) }) {
                        Text(stringResource(R.string.dsh_userscripts_market_mirror))
                    }
                }
            }
            // 「热门」这一列是首屏默认内容（没带查询时自动拉）：给一行标题，
            // 否则用户会以为这是自己搜出来的结果
            if (popular && hits.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine, key = "market-popular") {
                    Text(
                        stringResource(R.string.dsh_userscripts_market_popular),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            items(hits, key = { it.id }) { hit ->
                MarketTile(
                    hit = hit,
                    // 判据与安装时同一处状态：同名即已装（Userscripts 落盘按标题命名）
                    installed = scripts.any { it.title == hit.name },
                    busy = busy,
                    installing = installing == hit.id,
                    onInstall = { installHit(hit) },
                    onOpen = { openScriptPage(hit.pageUrl) },
                )
            }
            if (full && hits.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine, key = "market-more") {
                    TextButton(
                        // 当前列的是热门就接着取热门的下一页，否则取搜索的下一页
                        onClick = {
                            if (popular) runPopular(page + 1, reset = false)
                            else runSearch(page + 1, reset = false)
                        },
                        enabled = !busy,
                    ) { Text(stringResource(R.string.dsh_userscripts_market_more)) }
                }
            }
            item(span = StaggeredGridItemSpan.FullLine, key = "market-bottom") { HomeBottomSpacer() }
        }
    }
}

/**
 * 结果之前的那一段：说明 + 当前状态（加载中 / 失败 / 空 / 还没搜）。
 *
 * 四态分开写，合成一条会把「还没搜」说成「没有结果」——那正是用户第一次进来时看到的画面。
 * **每一种状态都必须有可见文字**：加载中只画一个 16dp 的转圈，在首屏请求慢或被网络挡住时
 * 看起来就是"底下全空"（用户报的正是这个）；失败态除了文案还给一个重试。
 */
@Composable
private fun MarketHead(
    hint: String,
    queryHint: String,
    loadingText: String,
    retryText: String,
    emptyText: String,
    busy: Boolean,
    searched: Boolean,
    showEmpty: Boolean,
    failText: String?,
    onRetry: () -> Unit,
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
                // 失败必须可重试：镜像站那条路只能看、装不了，没有重试等于把用户丢在这儿
                TextButton(onClick = onRetry) { Text(retryText) }
            }
            busy -> {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        loadingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
 * 列表语法。两个动作都是显式的：装（或「已安装」）与在**应用内**看脚本页
 * （[DshWebViewScreen]，与镜像站同一条路）；没做成整卡点击，因为点一下就把用户带出这一页
 * 比点一个明确的图标更容易误触。
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
