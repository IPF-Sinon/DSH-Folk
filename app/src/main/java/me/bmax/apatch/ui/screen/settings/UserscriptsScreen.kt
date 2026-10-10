package me.bmax.apatch.ui.screen.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import com.ramcosta.composedestinations.generated.destinations.ScriptMarketScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshEnv
import me.bmax.apatch.dsh.Userscripts
import me.bmax.apatch.dsh.WebScripts
import me.bmax.apatch.ui.component.ModuleLabel
import me.bmax.apatch.ui.component.UserscriptLinkInstallDialog
import me.bmax.apatch.ui.screen.ScriptDetailSheet
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
 * - **下：我装的脚本**（已导入列表）。它回答「我要不要跑自己的 JS」。
 *
 * 安装入口与脚本市场**同一套形态**（本地 `.user.js` 文件 / 从链接安装）：正文由两处宿主共用
 * （独立页 + 插件首页的「用户脚本」分组），所以安装逻辑也只能有一份 —— [UserscriptsContent]
 * 持有选择器与弹窗，宿主（这里的 Scaffold 与插件首页的 Scaffold）只通过
 * [UserscriptInstallRequest] 发「本地安装 / 从链接安装」两个请求。
 *
 * 用户明确要求**不要**正文顶部那张「安装脚本」卡片，改成**右下角 FAB**（照插件页那个
 * `Icons.Outlined.FolderOpen` 圆形按钮）。所以入口是「两处宿主各一个 FAB + 顶栏一个链接图标」，
 * 不再有卡片。上一轮把入口做成正文第一个列表项时，独立页与插件首页确实都看得到；但用户要的是
 * FAB 形态 —— 这一轮按 FAB 重做，两处都由宿主提供，正文里不再有安装项。
 *
 * 脚本市场（GreasyFork 搜索 + 一键安装）搬去了独立页
 * [me.bmax.apatch.ui.screen.ScriptMarketScreen]：它是「去别处找东西」，与这一页「本机现在注入/
 * 装了哪些」不是一件事 —— 混在一列里既把页面拉长，又让插件首页那个「商店」按钮只能把列表
 * 滚过去。现在两处按钮都是直接开那一页，装回来的东西落进**下段**那份列表。
 */
@Destination<RootGraph>
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserscriptsScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val installRequest = rememberUserscriptInstallRequest()
    var hideBuiltins by remember { mutableStateOf(DshEnv.userscriptsHideBuiltins(context)) }

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
                    // 与插件首页那个「商店」同一行（都在右上角 actions 里）。状态持久化，默认显示。
                    IconButton(onClick = {
                        val next = !hideBuiltins
                        hideBuiltins = next
                        DshEnv.setUserscriptsHideBuiltins(context, next)
                    }) {
                        Icon(
                            Icons.Outlined.VisibilityOff,
                            contentDescription = stringResource(
                                if (hideBuiltins) R.string.dsh_userscripts_show_builtin
                                else R.string.dsh_userscripts_hide_builtin
                            ),
                        )
                    }
                    // 「从链接安装」：与市场同一个位置（顶栏右侧的链接图标）。安装卡撤掉之后，
                    // 链接安装就剩这一个入口 —— 独立页与插件首页都必须有。
                    IconButton(onClick = { installRequest.requestLink() }) {
                        Icon(
                            Icons.Outlined.Link,
                            contentDescription = stringResource(R.string.dsh_userscripts_market_link_install),
                        )
                    }
                    IconButton(onClick = { navigator.navigate(ScriptMarketScreenDestination(initialQuery = "")) }) {
                        Icon(
                            Icons.Outlined.Storefront,
                            contentDescription = stringResource(R.string.dsh_userscripts_market_section),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            // 本地安装：与插件页那个 FAB 同形（图标 / 圆形 / 配色），选一个 `.user.js`。
            // 这里只发请求，真正的选择器与安装仍只有 [UserscriptsContent] 里那一份。
            FloatingActionButton(
                onClick = { installRequest.requestLocal() },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(
                    Icons.Outlined.FolderOpen,
                    contentDescription = stringResource(R.string.dsh_userscripts_install_file),
                )
            }
        },
    ) { padding ->
        UserscriptsContent(
            modifier = Modifier.padding(padding),
            hideBuiltins = hideBuiltins,
            installRequest = installRequest,
            // 没记来源的那条：更新只能去市场按名字找一遍（市场页支持带一个初始查询进来）
            onOpenMarket = { navigator.navigate(ScriptMarketScreenDestination(initialQuery = it)) },
        )
    }
}

/**
 * 安装入口的「请求」：宿主的右下角 FAB 与顶栏链接图标只置一个待办标志，真正的选择器（本地
 * `.user.js`）与弹窗（从链接安装）仍只有 [UserscriptsContent] 里那一份实现。
 *
 * 为什么不让两处宿主各自持有一个 `rememberLauncherForActivityResult`：那样安装逻辑会变成
 * 三份（两个宿主 + 正文），这正是"对齐 UI"要防的事。为什么用「置位 + 消费」而不是自增计数：
 * 正文会在切组时离开再回到组合（插件首页的「插件 / 用户脚本」两组），自增计数会让**上一次**
 * 请求在重新进入组合时被重放一次；置位标志由正文消费后即清，重放不会发生。
 */
internal class UserscriptInstallRequest {
    /** 有待处理的「本地 .user.js」请求。 */
    var local by mutableStateOf(false)
        private set

    /** 有待处理的「从链接安装」请求。 */
    var link by mutableStateOf(false)
        private set

    fun requestLocal() {
        local = true
    }

    fun requestLink() {
        link = true
    }

    /** 正文已经拉起本地选择器，清掉这次请求（见类 KDoc 的防重放说明）。 */
    fun consumeLocal() {
        local = false
    }

    /** 正文已经打开链接安装弹窗，清掉这次请求。 */
    fun consumeLink() {
        link = false
    }
}

/** 供宿主持有：与 [UserscriptsContent] 的生命周期同域（`remember`）。 */
@Composable
internal fun rememberUserscriptInstallRequest(): UserscriptInstallRequest =
    remember { UserscriptInstallRequest() }

/**
 * 用户脚本的正文：内置（应用自带那几段）/ 我装的。
 *
 * 单独抽出来是因为**插件首页的「用户脚本」那一组直接复用它** —— 入口从「设置 → 功能」
 * 右上角搬到插件首页之后，同一个页面在两地各留一份实现，很快就会各长各的（这正是「对齐
 * UI」要防的事）。独立页的顶栏（标题 + 返回 + 市场 + 链接）留在 [UserscriptsScreen] 里；
 * 插件首页用的是它自己的搜索栏与按钮。
 *
 * **安装入口不在这份正文里**：用户要求撤掉正文顶部那张「安装脚本」卡片，改成宿主 Scaffold
 * 的**右下角 FAB**（本地 `.user.js`）与**顶栏链接图标**（从链接安装）——两处宿主都要有。
 * 正文只接宿主的 [UserscriptInstallRequest]，真正干活的选择器与弹窗在这一份实现里
 * （本函数里的 `pickLocal` 与 [UserscriptLinkInstallDialog]），所以三处不会各写一套安装逻辑。
 *
 * 列表几何与插件列表**逐项一致**：`LazyColumn` + contentPadding 左右 16dp + 12dp 行距，
 * 卡片抄插件卡片那一套（见 [ScriptCard]）。
 *
 * 详情不再是二级目的地：整行点开就地弹 [ScriptDetailSheet]（与插件详情弹层同一形态），
 * 动作（开关 / 更新 / 移除）都收在层里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UserscriptsContent(
    modifier: Modifier = Modifier,
    filter: String = "",
    onOpenMarket: (String) -> Unit = {},
    /** 是否隐藏「应用内置」那几段（顶栏那个开关控制，默认显示）。 */
    hideBuiltins: Boolean = false,
    /** 宿主的安装请求（FAB = 本地 / 顶栏链接图标 = 从链接）；null = 这份正文不接请求。 */
    installRequest: UserscriptInstallRequest? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scripts by remember { mutableStateOf(Userscripts.list(context)) }
    // 内置那两条有开关的：勾选状态取**当前生效值**（compat 在 auto 下就是按内核算出来的结果）。
    // 拨动即落成 on / off —— auto 这一档只在设置页选，这里不重复表达三态。
    // 兼容垫片的档位：auto / on / off。**只在这一页选**（功能设置里那两行已收起）。
    var compatMode by remember { mutableStateOf(DshWebCompat.mode(context)) }
    var composerOn by remember { mutableStateOf(DshWebCompat.enterNewline(context)) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    // 详情弹层：只存 id，实体每次从 scripts 取 —— 装/卸/开关之后列表会刷新，
    // 存快照的话弹层里的开关会停在打开那一刻的状态（与插件详情弹层同一纪律）。
    var detailId by remember { mutableStateOf<String?>(null) }
    // 「从链接安装」弹窗的开关；弹窗本体与市场页共用（[UserscriptLinkInstallDialog]）。
    var showLinkInstall by remember { mutableStateOf(false) }

    fun reload() {
        scripts = Userscripts.list(context)
    }

    /** 本地安装：选一个 `.user.js`（与市场那个 FAB 走同一套 read / install）。 */
    val pickLocal = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@rememberLauncherForActivityResult
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

    // 宿主 Scaffold 的 FAB / 顶栏链接图标只发「请求」：正文在这里把它翻译成选择器与弹窗，
    // 安装逻辑因此仍然只有这一份（独立页与插件首页共用）。请求用置位标志 + 消费，
    // 切组回来时不会把上一次请求重放一次（见 [UserscriptInstallRequest] 的 KDoc）。
    if (installRequest != null) {
        LaunchedEffect(installRequest.local) {
            if (installRequest.local) {
                installRequest.consumeLocal()
                pickLocal.launch(
                    Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                )
            }
        }
        LaunchedEffect(installRequest.link) {
            if (installRequest.link) {
                installRequest.consumeLink()
                showLinkInstall = true
            }
        }
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

    // 与插件页**同一套列表几何**：LazyColumn + contentPadding 左右 16dp + spacedBy 12dp，
    // 卡片只管自己的形状与底色（见 [ScriptCard]）。以前这里是带 verticalScroll 的 Column、
    // 每一行自己加 padding，于是脚本是裸行、插件是卡片 —— 同一屏里出现两套几何。
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 安装入口不在这里：用户要求撤掉正文顶部那张「安装脚本」卡，改成宿主 Scaffold 的
        // 右下角 FAB 与顶栏链接图标（两处宿主 —— 独立页与插件首页那一组 —— 都有）。
        // 正文只接宿主的安装请求，选择器与弹窗仍是这一份实现（见上面的 installRequest 分支）。

        // ── 应用内置：与「我装的脚本」走同一条注入管道，但**不受逐条用户脚本开关约束** ──
        // 所以放在最上面：这段说明的是「页面被注入什么」，与下面「我要不要跑自己的脚本」是
        // 两件事。也是「一处看得全」的那一处 —— 以前这些散在设置与代码里。
        // 内置那几段的行数与内容都来自注册表（见 WebScripts.BUILTINS），不写死 5 行；
        // 每条一个列表项，与「我装的」共用同一个卡片壳与 12dp 行距。
        for (entry in if (hideBuiltins) emptyList() else WebScripts.BUILTINS) {
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


        // ── 「我装的」那一段的说明 ──
        // 这一页原来有一张「启用我装的用户脚本」总开关卡：按用户要求撤掉了；后来它搬进设置，
        // 用户又说不要 —— 最后连**逻辑一起删掉**了（Userscripts.masterEnabled /
        // DshEnv.KEY_USERSCRIPTS_ON 都不在了），注入只由下面每条的逐条开关决定。
        // 旧设备上残留的 pref 值被忽略（等于永远视为开），也没有界面能再碰它。
        item(key = "scope") {
            Text(
                stringResource(R.string.dsh_userscripts_scope),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            items(shown, key = { it.id }) { s ->
                ScriptRow(
                    script = s,
                    onToggle = { want ->
                        Userscripts.setEnabled(context, s.id, want)
                        reload()
                    },
                    onDelete = { pendingDelete = s.id },
                    onOpen = { detailId = s.id },
                    onUpdate = { update(s) },
                )
            }
        }

        item { HomeBottomSpacer() }
    }

    // 详情弹层：与插件详情同一形态（底部弹层），正文预览按需读一次。
    detailId?.let { id ->
        scripts.firstOrNull { it.id == id }?.let { s ->
            // 正文可能几千行：读盘不能挂在主线程上（重组一次读一次）。用 produceState 在 IO
            // 线程读，读完换进来；加载中先给占位 —— 与列表那条路同一条规矩。
            val preview by produceState(CodePreview(loading = true, text = ""), s.id) {
                value = CodePreview(
                    loading = false,
                    text = withContext(Dispatchers.IO) { Userscripts.code(context, s.id) },
                )
            }
            ScriptDetailSheet(
                script = s,
                code = preview.text,
                codeLoading = preview.loading,
                onDismiss = { detailId = null },
                onToggle = { want ->
                    Userscripts.setEnabled(context, s.id, want)
                    reload()
                },
                // 更新可能换 id（正文哈希进文件名）：先关层，结果按既有 toast 报
                onUpdate = {
                    detailId = null
                    update(s)
                },
                onRemove = {
                    detailId = null
                    pendingDelete = s.id
                },
            )
        }
    }

    // 「从链接安装」：与脚本市场**共用同一个弹窗**（[UserscriptLinkInstallDialog]），
    // 归一、标红、安装三件事只写一遍，两处入口的手感与边界因此不会漂移。
    if (showLinkInstall) {
        UserscriptLinkInstallDialog(
            onDismiss = { showLinkInstall = false },
            onInstall = { url ->
                scope.launch {
                    val id = withContext(Dispatchers.IO) { Userscripts.installFromUrl(context, url) }
                    if (id == null) {
                        showToast(context, context.getString(R.string.dsh_userscripts_read_failed))
                    } else {
                        reload()
                        showToast(context, context.getString(R.string.dsh_userscripts_installed))
                    }
                }
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
 * 详情弹层的正文预览状态。
 *
 * 不能只用一个 `String?`：那样「还没读出来」与「读不到」都是 null，弹层里只能显示空白，
 * 用户会以为脚本正文是空的。加载中给占位、读不到保持空白 —— 两种状态在层里是两句话。
 */
private data class CodePreview(val loading: Boolean, val text: String?)

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

/**
 * 一行：名字 + 版本/时机/大小 + 说明与 `@match` + 开关 + 更新 + 移除。
 *
 * 手感照插件页的 [me.bmax.apatch.ui.screen.DshPluginScreen] 插件卡片（那张卡的 KDoc 见
 * DshPluginItem）：**单击进详情、长按行内展开说明**，动作按钮收在卡片底部一行 ——
 * 原来把「更新 / 移除」两个图标挤在开关两边，一行里四个控件每个都难点，
 * 而且插件卡片那边根本不是这个形状。说明按插件页那样截断（收起 3 行 / 展开 12 行），
 * 长按才展开，否则长说明会把列表顶得很长。
 */
@Composable
private fun ScriptRow(
    script: Userscripts.Script,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onUpdate: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ScriptCard {
        // 整卡手势挂在内容列上（插件卡片挂在外壳上，但这里内容列铺满整卡、手势在 padding 之外，
        // 触摸范围完全一样）；用 combinedClickable 才有长按 —— 单击进详情，长按行内展开说明。
        Column(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = { expanded = !expanded },
                )
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                }
                Switch(checked = script.enabled, onCheckedChange = onToggle)
            }
            if (script.description.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    script.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) 12 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (script.matches.isNotEmpty()) {
                Text(
                    script.matches.joinToString("  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.height(8.dp))
            // 动作收在底部右侧，与插件卡片那一行同形（图标 16dp + 6dp 间距 + 文案）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onUpdate) {
                    Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.dsh_userscripts_update))
                }
                TextButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, null, Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.dsh_userscripts_delete))
                }
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
