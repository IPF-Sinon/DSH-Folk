package me.bmax.apatch.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.app.AppOpsManager
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.util.Rational
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.File
import kotlin.math.roundToInt
import me.bmax.apatch.R
import me.bmax.apatch.dsh.A11yOwn
import me.bmax.apatch.dsh.DshEnv
import me.bmax.apatch.dsh.DshRuntime
import me.bmax.apatch.dsh.WebScripts
import me.bmax.apatch.ui.component.ElevationRequestDialogHost
import me.bmax.apatch.ui.theme.APatchTheme
import me.bmax.apatch.util.DshWebCompat
import me.bmax.apatch.util.ui.showToast

/**
 * WebUI 打开方式。
 *
 * 用 Activity 而不是 composedestinations 的页面：首页六套布局共用的
 * `DshHomeUiState.openWeb()` 是普通方法，拿不到 navigator；做成 Activity 后
 * 只要 startActivity，不用把 navigator 穿过六套布局。FolkPatch 原来的
 * WebUIActivity 也是这个形制。
 */
object DshWebUi {
    const val MODE_IN_APP = "in"
    const val MODE_BROWSER = "browser"
    const val MODE_ASK = "ask"

    fun mode(ctx: Context): String =
        ctx.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)
            .getString(DshEnv.KEY_WEBUI_MODE, MODE_IN_APP) ?: MODE_IN_APP

    fun setMode(ctx: Context, mode: String) {
        ctx.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)
            .edit().putString(DshEnv.KEY_WEBUI_MODE, mode).apply()
    }

    /** 在应用内打开。 */
    fun openInApp(ctx: Context, url: String) {
        runCatching {
            ctx.startActivity(
                Intent(ctx, DshWebUiActivity::class.java)
                    .putExtra(DshWebUiActivity.EXTRA_URL, url)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 交给系统浏览器；没有可用浏览器时提示。 */
    fun openExternal(ctx: Context, url: String) {
        val ok = runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (!ok) showToast(ctx, ctx.getString(R.string.dsh_no_browser))
    }
}

/** 悬浮球直径。 */
private val BALL_SIZE = 44.dp

/** 球体与屏幕边缘的内缩。 */
private val BALL_INSET = 8.dp

/**
 * 首次在旧内核上打开 WebUI 时的说明框。
 *
 * 明确告诉用户「要往页面里注入一小段 JS」以及不注入的后果，两个按钮都会把选择固化下来，
 * 之后不再打扰。划掉不存任何选择，下次再问。
 */
@Composable
private fun DshCompatShimDialog(
    kernel: DshWebCompat.Kernel,
    onDismiss: () -> Unit,
    onDisable: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dsh_webui_compat_title)) },
        text = {
            Text(
                text = stringResource(
                    R.string.dsh_webui_compat_body,
                    kernel.display.ifEmpty { "?" },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dsh_webui_compat_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDisable) {
                Text(stringResource(R.string.dsh_webui_compat_disable))
            }
        },
    )
}

/**
 * 内置 WebUI 容器。
 *
 * 只加载本机 `http://127.0.0.1:<port>`：明文由 network_security_config 允许，
 * 无需额外权限。不开 allowFileAccess —— 这个 WebView 除了本地回环没有别的用途，
 * 放开文件访问只会给页面多一条读 app 私有目录的路。
 *
 * **没有顶栏**：dsh 的 web 界面自己就是一个完整应用，再压一条 64dp 的 TopAppBar
 * （叠加状态栏内缩后更高）纯粹是在挤内容。返回/刷新/外部打开改由一颗贴边的悬浮球
 * 提供，位置可拖、松手吸附到左或右壁并记住。
 *
 * 注意 dsh 自己的 web 界面**有登录页**，所以这里同样需要登录，这是 dsh 的行为。
 *
 * ## 为什么必须自己接文件选择与下载
 *
 * WebView 不是浏览器，它**默认什么都不做**：
 * - `<input type="file">` 被点击时，WebView 调 `WebChromeClient.onShowFileChooser`，
 *   基类返回 false，于是没有任何反应 —— 页面侧连 `change` 事件都收不到。这就是
 *   「插件提供的文件上传按钮点了没反应，浏览器里就好」的全部原因，跟插件无关。
 * - 下载同理：`<a download>` / `Content-Disposition: attachment` 触发的是
 *   `WebView.setDownloadListener`，不设就直接丢弃。dsh 自己的会话日志导出
 *   （dsh-session-log-export）用的正是 `anchor.download = …; anchor.click()`。
 * - `blob:` URL 更特殊：它连 DownloadListener 都不会走（那是浏览器进程内的对象，
 *   没有网络请求），所以额外注入一小段 JS 把 blob 读成 base64 交回原生。
 *
 * ## 旧 WebView 内核要补 JS API
 *
 * WebView 是可独立升级的组件，系统版本高**不代表**内核新：有真机报过 Android 15
 * 上装着 Chromium 110 的 WebView。dsh 前端用到 `AbortSignal.any`（Chrome 116）与
 * `Promise.withResolvers`（Chrome 119），在这种设备上打开工作区就是
 * `AbortSignal.any is not a function`。兼容垫片在文档开始前补齐这些 API，见
 * [WebScripts.BUILTINS] 与 `assets/webui-scripts/compat.js`。
 *
 * ## 系统栏是沉浸的（网页画到小白条与状态栏后面）
 *
 * WebView 铺满整窗，系统栏后面是**网页自己的背景**；页面本体由内边距脚本（
 * `assets/webui-scripts/inset.js`，注入时带上四个方向的原生像素值）加的 `#root`
 * 内边距让开这两片区域。targetSdk 35 起系统强制 edge-to-edge，Android 侧给
 * WebView 留内边距的老做法只会得到两条主题底色带（手势条上下各一条，正是用户报的
 * 「底部留白」）。键盘例外：那一段由 `windowInsetsPadding(imeAnimationTarget)` 一步让开，见 onCreate 里的注释。
 */
class DshWebUiActivity : AppCompatActivity() {

    private var webView: WebView? = null
    private var canGoBack = false

    /**
     * 这一批注入脚本是否已按 document-start 装上。
     *
     * 只有**一个**布尔量：内置（见 [WebScripts.BUILTINS]）与用户导入的脚本走的是同一条管道、
     * 同一个注入点，失败原因也只有一个（内核不支持 DOCUMENT_START_SCRIPT，或注册抛异常）——
     * 以前五段各记一个，是因为它们各有各的开关；开关现在在 [WebScripts] 的注册表里，
     * 这里只需要知道「注册这一步成没成」。
     */
    private var scriptsInstalled = false

    /**
     * 最近一次算出的系统栏内边距（CSS 像素 = dp）。
     *
     * 存成字段而不是只在组合期用局部量：`onPageStarted` 的补注入发生在**别的时刻**
     * （每次导航、刷新），拿组合期捕获的旧值会让页面在转屏后按旧尺寸避让。
     */
    private var cssInsetTop = 0
    private var cssInsetRight = 0
    private var cssInsetBottom = 0
    private var cssInsetLeft = 0

    /** 待回填给 `<input type="file">` 的回调；同一时刻只可能有一个选择器。 */
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooser: ActivityResultLauncher<Intent>

    /**
     * 网页要麦克风、而系统还没授予时，Chromium 那一次 [PermissionRequest] 挂在这里。
     *
     * 必须挂住：回调要等**系统授权框**的结果（见 `onCreate` 里注册的 `micPermission`），
     * Chromium 会一直等我们答 grant/deny。丢掉它就是默认拒绝 —— 页面里 `getUserMedia`
     * 恒定抛 `NotAllowedError`，而系统设置里明明是允许的（用户报的正是这个）。
     */
    private var pendingAudioRequest: PermissionRequest? = null
    private lateinit var micPermission: ActivityResultLauncher<String>

    /** 网页要的这批资源里，**现在**真正授权得了的那些（麦克风 / 摄像头）。 */
    private fun grantableMedia(req: PermissionRequest): Array<String> =
        req.resources.filter { res ->
            when (res) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                // DRM / MIDI 之类维持 WebView 的默认处理：我们不替用户点头
                else -> false
            }
        }.toTypedArray()

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent?.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }
            ?: DshRuntime.webUrl()

        // 必须在 onCreate 里注册（Activity 还没 STARTED），不能等到点击时才注册
        fileChooser = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val cb = fileChooserCallback
            fileChooserCallback = null
            // 取消也必须回调（传 null），否则 WebView 认为选择器还开着，
            // 那个 <input> 之后再点就永远没反应了
            cb?.onReceiveValue(parseChooserResult(result.resultCode, result.data))
        }

        // 网页要麦克风、系统还没授予时弹的那一次授权框。同样必须在 onCreate 里注册
        // （Activity 还没 STARTED），否则回调收不到、那次 PermissionRequest 会被永久挂住。
        //
        // 为什么必须答：Chromium 等我们调 grant/deny，而我们不答的默认就是**拒绝** ——
        // 页面里 `getUserMedia` 于是恒定抛 `NotAllowedError`，提示「麦克风权限未开启，
        // 请在浏览器和系统设置中允许访问」，而系统设置里明明是允许的。用户报的正是这个。
        micPermission = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            val req = pendingAudioRequest
            pendingAudioRequest = null
            if (req == null) return@registerForActivityResult
            // 授权框允许了、且这次请求本来就要麦克风，就把麦克风（含一并要到的摄像头）
            // 交给 Chromium；否则明确拒绝 —— 拒绝也要答，否则页面一直等。
            val resources = if (granted) grantableMedia(req) else emptyArray()
            if (resources.isNotEmpty()) {
                Log.i(TAG, "web media permission granted: " + resources.joinToString(","))
                req.grant(resources)
            } else {
                req.deny()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 页面内还能后退就先退页面，否则才退出 Activity
                if (canGoBack) {
                    webView?.goBack()
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })

        setContent {
            // allowCustomBackground = false：WebUI 是别人的页面，
            // 背后垫一张自定义壁纸只会让内容看不清
            APatchTheme(allowCustomBackground = false) {
                var progress by remember { mutableIntStateOf(0) }

                // 提权申请弹窗：用户此刻正看着 WebUI，只挂在 MainActivity 上的话
                // 这份申请他永远看不到（60 秒后静默超时算拒绝）
                ElevationRequestDialogHost()

                // 旧内核上**自动**注入兼容垫片，然后只说明一次。
                //
                // 为什么不再是「先问」：缺 Iterator 这类全局时整个 WebUI 会渲染成
                // "Failed to load plugins"，用户连设置页都进不去，问了也答不上来
                // （1.9.2 真机实测）。所以先保证能用，再把「已启用兼容模式 / 可关闭」
                // 明确告诉用户 —— off 仍然是用户的决定权，落盘后永不注入。
                val kernel = remember { DshWebCompat.kernel(this@DshWebUiActivity) }
                var showCompatNotice by remember {
                    mutableStateOf(DshWebCompat.shouldNotice(this@DshWebUiActivity, kernel))
                }
                if (showCompatNotice) {
                    DshCompatShimDialog(
                        kernel = kernel,
                        onDismiss = {
                            DshWebCompat.markNoticed(this@DshWebUiActivity)
                            showCompatNotice = false
                        },
                        onDisable = {
                            DshWebCompat.setMode(this@DshWebUiActivity, DshWebCompat.MODE_OFF)
                            DshWebCompat.markNoticed(this@DshWebUiActivity)
                            showCompatNotice = false
                            // 关掉之后要重新加载一次，垫片才真的不在这份文档里
                            webView?.reload()
                        },
                    )
                }

                // 系统栏尺寸交给页面自己避让（见 assets/webui-scripts/inset.js）。
                //
                // 为什么不让 Android 侧给 WebView 加内边距：那样系统栏后面只能垫一层
                // 主题底色，网页看着像被裁掉了一截（手势条上下各一条色带）。改成 WebView
                // 铺满整窗后，状态栏与小白条后面就是网页自己的背景 —— 页面本体用
                // `#root` 的 padding 让开这两个区域，可交互内容一样不会被盖住。
                val density = LocalDensity.current
                // 左右的 inset 与书写方向有关（RTL 下 start/end 会翻），所以四个取值里
                // 横向那两个要连 layoutDirection 一起传
                val layoutDirection = LocalLayoutDirection.current
                val insetTopPx = WindowInsets.statusBars.getTop(density)
                val insetBottomPx = WindowInsets.navigationBars.getBottom(density)
                // 横屏时三键导航会在侧边、挖孔也在侧边，两边取更大的那个
                val insetLeftPx = maxOf(
                    WindowInsets.navigationBars.getLeft(density, layoutDirection),
                    WindowInsets.displayCutout.getLeft(density, layoutDirection),
                )
                val insetRightPx = maxOf(
                    WindowInsets.navigationBars.getRight(density, layoutDirection),
                    WindowInsets.displayCutout.getRight(density, layoutDirection),
                )
                // CSS 像素就是 dp，WebView 的视口按 dp 计
                fun toCss(px: Int): Int = (px / density.density).roundToInt()
                val cssTop = toCss(insetTopPx)
                val cssLeft = toCss(insetLeftPx)
                val cssRight = toCss(insetRightPx)
                // 键盘弹起时 WebView 会被抬到键盘上方（键盘本身盖住了手势条），再让页面留一条
                // 就给键盘上方多垫一层空白。判据用 imeAnimationTarget（键盘的**目标**高度，动画一
                // 开始就到位）而非 ime（逐帧插值），与下面 windowInsetsPadding 的抬升保持同步。
                val cssBottom = if (WindowInsets.imeAnimationTarget.getBottom(density) > 0) 0 else toCss(insetBottomPx)
                SideEffect {
                    cssInsetTop = cssTop
                    cssInsetRight = cssRight
                    cssInsetBottom = cssBottom
                    cssInsetLeft = cssLeft
                }
                // 尺寸变了（转屏、折叠、键盘）就同步给页面。首次组合时 WebView 还没建，
                // 那一次没关系：factory 会把这几个值直接写进 document-start 脚本。
                LaunchedEffect(cssTop, cssRight, cssBottom, cssLeft) {
                    webView?.evaluateJavascript(
                        insetUpdateScript(cssTop, cssRight, cssBottom, cssLeft),
                        null,
                    )
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            // 只有键盘要让开：左右上下的系统栏由页面用内边距避让。
                            // 用 imeAnimationTarget 而非 imePadding：后者跟着输入法弹出动画**逐帧**
                            // 改 WebView 高度，WebView 117 每帧重排又慢又卡，输入框「慢半拍才跟上」。
                            // 目标高度让 WebView 只重排一次、一步抬到键盘上方（这是壳侧能做到的最优；
                            // 逐帧跟随需 WebView M139 的 visual-viewport IME 支持，本机内核给不了）。
                            .windowInsetsPadding(WindowInsets.imeAnimationTarget),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                // 本地回环页面用不到文件访问，关掉少一条攻击面
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                // blob: 下载的桥。只在 loadUrl 的回环地址上注入
                                // （onPageStarted 里按 origin 校验），别的来源拿不到它
                                addJavascriptInterface(BlobBridge(), BLOB_BRIDGE)
                                // 用户脚本的 GM_notification 落到原生 Toast
                                addJavascriptInterface(UserscriptBridge(), USERSCRIPT_BRIDGE)
                                webViewClient = object : WebViewClient() {
                                    /**
                                     * 只让回环页面留在这个 WebView 里，其余交给系统浏览器。
                                     *
                                     * 不只是体验问题：[BlobBridge] 是通过
                                     * `addJavascriptInterface` 挂上的，一旦 WebView 被导航到
                                     * 外部站点，那个站点就能直接调它往磁盘写文件。把外链踢出去
                                     * 是让这个桥永远只面向本机 dsh 的前提。
                                     */
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                    ): Boolean {
                                        val target = request?.url ?: return false
                                        val scheme = target.scheme?.lowercase()
                                        if (scheme != "http" && scheme != "https") {
                                            // mailto: / intent: 之类交给系统，别在 WebView 里报错
                                            return runCatching {
                                                startActivity(
                                                    Intent(Intent.ACTION_VIEW, target)
                                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                )
                                            }.isSuccess
                                        }
                                        if (isLoopback(target.toString())) return false
                                        DshWebUi.openExternal(this@DshWebUiActivity, target.toString())
                                        return true
                                    }

                                    override fun doUpdateVisitedHistory(
                                        view: WebView?,
                                        u: String?,
                                        isReload: Boolean,
                                    ) {
                                        canGoBack = view?.canGoBack() == true
                                        super.doUpdateVisitedHistory(view, u, isReload)
                                    }

                                    override fun onPageFinished(view: WebView?, u: String?) {
                                        progress = 100
                                        super.onPageFinished(view, u)
                                    }

                                    override fun onPageStarted(
                                        view: WebView?,
                                        u: String?,
                                        favicon: android.graphics.Bitmap?,
                                    ) {
                                        progress = 1
                                        // document-start 装不上时的回落：这里注入虽然已经晚于
                                        // 文档开头，但仍早于绝大多数模块求值，能救回一部分场景。
                                        // 每段脚本自己的 document-start 语义就退化成 onPageStarted。
                                        //
                                        // 只判「注册那一步成没成」：该不该注入已经在
                                        // [WebScripts.injections] 里按各自的开关筛过了，
                                        // 这里再问一遍开关就会出现「关掉兼容模式 → 注册返回 false
                                        // → 回落里又把它注进去」这种自相矛盾的路径。
                                        if (!scriptsInstalled && isLoopback(u)) {
                                            injectScriptsNow(view, u)
                                        }
                                        super.onPageStarted(view, u, favicon)
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: WebResourceError?,
                                    ) {
                                        // 只报主文档失败：子资源失败（favicon 之类）不该打扰用户
                                        if (request?.isForMainFrame == true) {
                                            showToast(
                                                this@DshWebUiActivity,
                                                getString(R.string.dsh_webui_load_failed),
                                            )
                                        }
                                        super.onReceivedError(view, request, error)
                                    }
                                }
                                webChromeClient = object : android.webkit.WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, p: Int) {
                                        progress = p
                                    }

                                    /**
                                     * 把页面里的 JS 报错带进 App 日志。
                                     *
                                     * 这条是补课：上游客户端 bundle 抛
                                     * "Failed to load plugins … Iterator is not defined"
                                     * 时，页面自己画了个错误页，而 logcat 与上报里**一个字都没有**
                                     * —— 排查时只能靠用户截图。现在页面报错会落到
                                     * [me.bmax.apatch.util.LogStore]，随 bugreport 一起带出来。
                                     */
                                    override fun onConsoleMessage(
                                        msg: android.webkit.ConsoleMessage?,
                                    ): Boolean {
                                        val m = msg ?: return false
                                        if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                                            val line =
                                                "page error: " + m.message() + " @" +
                                                    m.sourceId() + ":" + m.lineNumber()
                                            Log.w(TAG, line)
                                            // 也落进 dsh 日志（随 bugreport 带出来）。logcat 只覆盖最近
                                            // 几分钟、还要看采集时机，而页面报错往往发生在启动那一刻：
                                            // 真机上「Failed to load plugins」那次就是既没进 logcat 也没进
                                            // 上报，只能靠用户截图。
                                            me.bmax.apatch.dsh.DshRuntime.appendLog("[page] " + line)
                                        }
                                        return false
                                    }

                                    /**
                                     * 网页要麦克风/摄像头时的授权回调。
                                     *
                                     * **不重写它，`getUserMedia` 就是恒定失败**：AOSP 的默认实现
                                     * 是 `request.deny()`，而对话页的语音输入正是在 `getUserMedia`
                                     * 抛 `NotAllowedError` 时显示「麦克风权限未开启，请在浏览器和
                                     * 系统设置中允许访问」—— 系统里允许了也没用，因为 WebView 这
                                     * 一层从没被点过头。
                                     *
                                     * 顺序：已经授予的直接 grant（快速路径）；网页要麦克风而系统
                                     * 还没给，就弹系统授权框并**挂住**这次请求，等结果回来再答
                                     * （Chromium 会一直等 grant/deny，见 [micPermission]）。
                                     */
                                    override fun onPermissionRequest(
                                        request: PermissionRequest?,
                                    ) {
                                        val req = request ?: return
                                        val granted = grantableMedia(req)
                                        if (granted.isNotEmpty()) {
                                            Log.i(
                                                TAG,
                                                "web media permission granted: " +
                                                    granted.joinToString(","),
                                            )
                                            req.grant(granted)
                                            return
                                        }
                                        val wantsAudio = req.resources.contains(
                                            PermissionRequest.RESOURCE_AUDIO_CAPTURE,
                                        )
                                        if (wantsAudio) {
                                            // 上一个还没答就放掉，否则那个页面会被永久卡住
                                            // （同 onShowFileChooser 的写法）
                                            pendingAudioRequest?.deny()
                                            pendingAudioRequest = req
                                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                            return
                                        }
                                        // 其余资源维持 WebView 的默认处理（拒绝）
                                        super.onPermissionRequest(req)
                                    }

                                    /**
                                     * `<input type="file">` 的落点。返回 false 会让页面
                                     * 彻底收不到文件 —— 这正是之前上传按钮没反应的原因。
                                     */
                                    override fun onShowFileChooser(
                                        view: WebView?,
                                        callback: ValueCallback<Array<Uri>>?,
                                        params: android.webkit.WebChromeClient.FileChooserParams?,
                                    ): Boolean {
                                        // 上一个选择器还没结束就先放掉它，否则那个 input 会被永久卡住
                                        fileChooserCallback?.onReceiveValue(null)
                                        fileChooserCallback = callback
                                        val intent = runCatching {
                                            params?.createIntent()
                                        }.getOrNull() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                            addCategory(Intent.CATEGORY_OPENABLE)
                                            type = "*/*"
                                        }
                                        return try {
                                            fileChooser.launch(intent)
                                            true
                                        } catch (e: ActivityNotFoundException) {
                                            Log.w(TAG, "no file picker activity", e)
                                            fileChooserCallback = null
                                            callback?.onReceiveValue(null)
                                            showToast(
                                                this@DshWebUiActivity,
                                                getString(R.string.dsh_webui_no_file_picker),
                                            )
                                            false
                                        }
                                    }
                                }
                                // http(s) 下载（Content-Disposition / <a download> 指向真实 URL）
                                setDownloadListener { dl, userAgent, disposition, mime, _ ->
                                    startHttpDownload(dl, userAgent, disposition, mime)
                                }
                                // 必须在 loadUrl 之前装：addDocumentStartJavaScript 只对
                                // 「调用返回之后才开始加载」的 frame 生效。内置那几段与用户
                                // 导入的脚本走的是同一条管道（每段一次注册，互不牵连），
                                // 顺序与开关都在 [WebScripts] 里。
                                scriptsInstalled = installScripts(this, url)
                                webView = this
                                loadUrl(url)
                            }
                        },
                    )

                    // 加载进度：窗口顶端一条细线，不占布局高度
                    if (progress in 1..99) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter),
                        )
                    }

                    // 画中画时连外壳一起藏起来：悬浮球属于「页面外壳」，在那么小的窗口里只会挡住内容
                    if (!inPip.value) {
                        WebUiFloatingBall(
                            onBack = { onBackPressedDispatcher.onBackPressed() },
                            onClose = { finish() },
                            onReload = { webView?.reload() },
                            // 交给外部浏览器时**现取**当前地址，而不是用本页进来时那个 [url]：
                            // dsh 每次重启都会生成新 token（旧地址的 token 随之失效），而本页
                            // 可以一直开着 —— 用进来时那份就等于把一个过期 token 递给浏览器。
                            onOpenExternal = {
                                DshWebUi.openExternal(this@DshWebUiActivity, DshRuntime.webUrl())
                            },
                            // 进不去（设备不支持 / 应用级开关被关）就先给引导，不硬撞
                            onEnterPip = { if (!enterPip()) showPipGuide.value = true },
                        )
                    }

                    if (showPipGuide.value) {
                        PipGuideDialog(
                            switchOff = pipSupported() && !pipAllowed(),
                            onDismiss = { showPipGuide.value = false },
                            onOpenSettings = {
                                showPipGuide.value = false
                                openAppDetails()
                            },
                        )
                    }
                }
            }
        }
    }

    /** 是否正在画中画：那种小窗里不画悬浮球。由 [onPictureInPictureModeChanged] 更新。 */
    private val inPip = mutableStateOf(false)

    /** 「开不了画中画」的引导弹窗是否可见。 */
    private val showPipGuide = mutableStateOf(false)

    /**
     * 进画中画。返回 false = 现在进不去，调用方去弹引导。
     *
     * 两种「进不去」要分开说：设备没有这个能力（[pipSupported]）与系统把本应用的画中画关了
     * （[pipAllowed]，应用信息页里的那个开关）。引导文案据此二选一，所以这里也分开判。
     */
    private fun enterPip(): Boolean {
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16))
        // 12+ 的无缝缩放：小窗与大窗之间的过渡不会闪一下
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setSeamlessResizeEnabled(true)
        // 以系统给的答复为准，不拿 AppOps 预判：个别 ROM 把它报成 MODE_IGNORED 却实际允许，
        // 预判会把本来能进的用户直接挡进引导里。[pipSupported] / [pipAllowed] 只用来决定
        // 引导怎么说（是不支持，还是本应用的开关被关了）。
        return runCatching { enterPictureInPictureMode(builder.build()) }.getOrDefault(false)
    }

    private fun pipSupported(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /**
     * 应用级画中画开关（AppOps 的 picture_in_picture 项）。
     *
     * MODE_DEFAULT 是「没被单独关掉」，按允许算：只有显式 MODE_IGNORED 才算被关。
     * 29 起用 unsafeCheckOpNoThrow（不打权限日志），26-28 只能走已废弃的 checkOpNoThrow。
     */
    private fun pipAllowed(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return true
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_DEFAULT
    }

    /** 引导里的「去设置」：应用信息页，用户在里面能找到「画中画」那一项。 */
    private fun openAppDetails() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                )
            )
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }

    /** 选择结果 → WebView 要的 Uri 数组。取消或无数据一律 null。 */
    private fun parseChooserResult(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != RESULT_OK || data == null) return null
        data.clipData?.let { clip ->
            // 多选走 clipData
            val list = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            if (list.isNotEmpty()) return list.toTypedArray()
        }
        return data.data?.let { arrayOf(it) }
    }

    /**
     * 交给系统 DownloadManager 落到公共 Download/DSH-Folk。
     *
     * 用 DownloadManager 而不是自己拉流：它有通知栏进度、断点、失败重试，
     * 而且写公共目录不需要存储权限（自带 MediaStore 登记）。
     * 回环地址没有 Cookie 也无妨，dsh 的鉴权走的是同源会话；带上 Cookie 只是兜底。
     */
    private fun startHttpDownload(
        downloadUrl: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        // DownloadManager 只认 http/https；blob:/data: 由 JS 那条路处理
        if (!downloadUrl.startsWith("http://") && !downloadUrl.startsWith("https://")) {
            Log.i(TAG, "download scheme not handled here: ${downloadUrl.take(24)}")
            return
        }
        val name = runCatching {
            URLUtil.guessFileName(downloadUrl, contentDisposition, mimeType)
        }.getOrNull() ?: "download"
        val ok = runCatching {
            val req = DownloadManager.Request(Uri.parse(downloadUrl))
                .setMimeType(mimeType)
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "$PUBLIC_SUBDIR/$name",
                )
            if (!userAgent.isNullOrEmpty()) req.addRequestHeader("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(downloadUrl)?.takeIf { it.isNotEmpty() }
                ?.let { req.addRequestHeader("Cookie", it) }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            true
        }.getOrElse {
            Log.e(TAG, "enqueue download failed", it)
            false
        }
        showToast(
            this,
            if (ok) getString(R.string.dsh_webui_downloading, name)
            else getString(R.string.dsh_webui_download_failed),
        )
    }

    /**
     * blob:/data: 下载的原生落点：JS 把内容读成 base64 递过来，这里写文件。
     *
     * 只从回环页面注入（blob 下载那段脚本由注入管道按回环 origin 规则装，见
     * [WebScripts.BUILTINS]）。
     * 即便如此也不信任入参：文件名只取 basename 并过滤路径分隔符，写入目录写死。
     */
    /**
     * 用户脚本 `GM_notification` 的原生落点：一个 Toast。
     *
     * 与 [BlobBridge] 同一套信任前提 —— 只在回环页面上挂（外部链接会被踢去系统浏览器），
     * 所以拿到调用的只可能是我们自己的页面。JS 线程调用，转回 UI 线程；长度截断，
     * 免得一个脚本刷屏。调用方可以什么都不传（脚本常写 GM_notification("done")）。
     */
    private inner class UserscriptBridge {
        @JavascriptInterface
        fun notify(title: String?, text: String?) {
            val body = listOfNotNull(title?.trim()?.takeIf { it.isNotEmpty() }, text?.trim()?.takeIf { it.isNotEmpty() })
                .joinToString(": ")
                .take(200)
                .ifEmpty { return }
            runOnUiThread { showToast(this@DshWebUiActivity, body) }
        }
    }

    private inner class BlobBridge {
        @JavascriptInterface
        fun save(base64: String, fileName: String) {
            val safe = fileName.substringAfterLast('/').substringAfterLast('\\')
                .filter { it.isLetterOrDigit() || it in "._- ()[]" }
                .take(120)
                .ifEmpty { "download" }
            val ok = runCatching {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    PUBLIC_SUBDIR,
                )
                // 公共 Download 写不进去（分区存储、无「所有文件」权限）时退到应用外部目录，
                // 那里始终可写，用户仍能通过「打开目录」拿到文件
                val target = if (dir.isDirectory || dir.mkdirs()) {
                    File(dir, safe)
                } else {
                    val fb = File(
                        getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir,
                        PUBLIC_SUBDIR,
                    )
                    fb.mkdirs()
                    File(fb, safe)
                }
                target.writeBytes(bytes)
                // 让文件在系统「下载」/文件管理器里可见（公共目录才需要）
                runCatching {
                    android.media.MediaScannerConnection.scanFile(
                        this@DshWebUiActivity, arrayOf(target.absolutePath), null, null,
                    )
                }
                target.absolutePath
            }.getOrElse {
                Log.e(TAG, "blob save failed", it)
                null
            }
            runOnUiThread {
                showToast(
                    this@DshWebUiActivity,
                    if (ok != null) getString(R.string.dsh_webui_downloaded, safe)
                    else getString(R.string.dsh_webui_download_failed),
                )
            }
        }
    }

    /** 注入的 JS 只处理 WebView 天生不管的 blob:/data:，http(s) 仍走 DownloadListener。 */
    private fun isLoopback(u: String?): Boolean {
        val host = runCatching { Uri.parse(u ?: return false).host }.getOrNull() ?: return false
        return host == "127.0.0.1" || host == "localhost" || host == "::1"
    }

    /**
     * 在**文档开始前**把所有该注入的脚本装上，返回「注册这一步成没成」。
     *
     * 该注入哪几段由 [WebScripts.injections] 决定：内置（compat / inset / composer /
     * a11y-labels / blob-download，各自的开关在注册表里）+ 用户导入的脚本（总开关 + 逐条
     * + @match）。这里只做两件原生的事：
     *
     * - 用 [loopbackOriginRules] 把范围钉死在回环地址（别的站点不该被我们动）；
     * - **一段一次** [WebViewCompat.addDocumentStartJavaScript]：WebView 分别编译，
     *   于是一段语法错只毁它自己（拼成一大段的话，一处语法错会让整段静默不执行）。
     *
     * 必须在 loadUrl 之前调用：addDocumentStartJavaScript 只对「调用返回之后才开始加载」
     * 的 frame 生效。
     */
    private fun installScripts(view: WebView, url: String): Boolean {
        val scripts = WebScripts.injections(this, url, currentInsets())
        if (scripts.isEmpty()) return true // 没什么要注入的：不用在 onPageStarted 里反复补
        val supported = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        }.getOrDefault(false)
        if (!supported) {
            Log.i(TAG, "document-start script unsupported, scripts fall back to onPageStarted")
            return false
        }
        val rules = loopbackOriginRules(url)
        return runCatching {
            scripts.forEach { WebViewCompat.addDocumentStartJavaScript(view, it, rules) }
            Log.i(TAG, "scripts injected at document-start: " + scripts.size)
            true
        }.getOrElse {
            Log.w(TAG, "addDocumentStartJavaScript failed for scripts", it)
            false
        }
    }

    /** document-start 装不上时的回落：逐段 evaluate（时机退化成 onPageStarted）。 */
    private fun injectScriptsNow(view: WebView?, url: String?) {
        // 显式判空：智能转换只对「null 检查后的不可变参数」生效，不依赖 stdlib 的契约注解
        if (view == null || url == null || url.isBlank()) return
        WebScripts.injections(this, url, currentInsets()).forEach {
            view.evaluateJavascript(it, null)
        }
    }

    /** 当前系统栏内边距（CSS 像素）。组合期算好存在字段里，注入时现取。 */
    private fun currentInsets() = WebScripts.Insets(
        top = cssInsetTop,
        right = cssInsetRight,
        bottom = cssInsetBottom,
        left = cssInsetLeft,
    )

    override fun onResume() {
        super.onResume()
        // 「别看本应用」：all 档时把整页（含 WebView 的虚拟子树）从无障碍树里隐掉。
        // 每页重进都重设一次，用户在设置里改了档位不必重启 App。
        A11yOwn.applyToWindow(window)
    }

    override fun onDestroy() {
        // 不销毁的话 WebView 会连着 Activity 一起泄漏
        runCatching {
            webView?.let {
                it.stopLoading()
                it.destroy()
            }
        }
        webView = null
        // 页面走了但选择器回调还挂着时也要放掉，否则 WebView 内部一直等
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        // 麦克风那次请求同理：还没答就答「拒绝」，别把一个 WebView 对象留给下一次加载
        pendingAudioRequest?.deny()
        pendingAudioRequest = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "dsh_webui_url"
        private const val TAG = "DshWebUi"

        /** 下载落地的公共子目录（用户找得到）。 */
        private const val PUBLIC_SUBDIR = "DSH-Folk"

        /** JS 侧看到的桥名。 */
        private const val BLOB_BRIDGE = "DshFolkDownload"

        /** 用户脚本的 GM_notification 桥名（注入体的 JS 里按这个名字找）。 */
        private const val USERSCRIPT_BRIDGE = "DshFolkNotify"

        /**
         * document-start 脚本允许的 origin 规则。
         *
         * 格式 `SCHEME "://" HOSTNAME_PATTERN [":" PORT]`，**端口不写就默认 80/443**，
         * 所以三个回环写法都要带上真实端口，否则规则匹配不到、脚本静默不注入。
         * IPv6 字面量要方括号。
         */
        internal fun loopbackOriginRules(url: String): Set<String> {
            val parsed = runCatching { Uri.parse(url) }.getOrNull()
            val port = parsed?.port?.takeIf { it in 1..65535 } ?: DshRuntime.port()
            return setOf(
                "http://127.0.0.1:$port",
                "http://localhost:$port",
                "http://[::1]:$port",
            )
        }



        /** 尺寸变化时通知页面；脚本还没装上时是空操作（那时由 onPageStarted 补注入）。 */
        internal fun insetUpdateScript(top: Int, right: Int, bottom: Int, left: Int): String =
            "window.__dshFolkInsets&&window.__dshFolkInsets($top,$right,$bottom,$left)"



    }
}

/**
 * 贴边的半透明悬浮球，点开展出返回 / 刷新 / 外部打开 / 关闭。
 *
 * 位置持久化成「哪一侧 + 纵向比例」而不是绝对像素：换了屏幕方向或分屏尺寸后，
 * 绝对坐标会把球留在屏幕外，比例不会。
 */
@Composable
private fun WebUiFloatingBall(
    onBack: () -> Unit,
    onClose: () -> Unit,
    onReload: () -> Unit,
    onOpenExternal: () -> Unit,
    onEnterPip: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)
    }
    val density = LocalDensity.current
    val safe = WindowInsets.safeDrawing.asPaddingValues()

    var onRight by remember {
        mutableStateOf(prefs.getString(DshEnv.KEY_WEBUI_BALL_SIDE, "right") != "left")
    }
    // 纵向位置按可用高度的比例存；0.5 = 竖直居中
    var yRatio by remember {
        mutableFloatStateOf(prefs.getFloat(DshEnv.KEY_WEBUI_BALL_Y, 0.45f).coerceIn(0f, 1f))
    }
    var expanded by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    // 拖动期间用未吸附的实时 x（dp），松手后回到贴边值
    var dragX by remember { mutableStateOf<Dp?>(null) }

    Box(Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val maxW = maxWidth
            val maxH = maxHeight
            val top = safe.calculateTopPadding()
            val bottom = safe.calculateBottomPadding()
            // 球心可落的纵向区间：不压状态栏、不压手势条
            val yMin = top + BALL_INSET
            val yMax = (maxH - bottom - BALL_INSET - BALL_SIZE).coerceAtLeast(yMin)
            val restX = if (onRight) maxW - BALL_SIZE - BALL_INSET else BALL_INSET

            val x by animateDpAsState(
                targetValue = dragX ?: restX,
                animationSpec = spring(),
                label = "ballX",
            )
            val y = yMin + (yMax - yMin) * yRatio

            Column(
                modifier = Modifier.offset(x = x, y = y),
                horizontalAlignment = if (onRight) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(
                        alpha = if (dragging || expanded) 0.92f else 0.55f
                    ),
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .size(BALL_SIZE)
                        .pointerInput(maxW, maxH, yMin, yMax) {
                            detectDragGestures(
                                onDragStart = {
                                    dragging = true
                                    dragX = restX
                                },
                                onDragEnd = {
                                    dragging = false
                                    // 松手按左右中线吸附，并把结果记下来
                                    val centre = (dragX ?: restX) + BALL_SIZE / 2
                                    onRight = centre > maxW / 2
                                    dragX = null
                                    prefs.edit()
                                        .putString(
                                            DshEnv.KEY_WEBUI_BALL_SIDE,
                                            if (onRight) "right" else "left",
                                        )
                                        .putFloat(DshEnv.KEY_WEBUI_BALL_Y, yRatio)
                                        .apply()
                                },
                                onDragCancel = {
                                    dragging = false
                                    dragX = null
                                },
                            ) { _, delta: Offset ->
                                with(density) {
                                    dragX = ((dragX ?: restX) + delta.x.toDp())
                                        .coerceIn(0.dp, (maxW - BALL_SIZE).coerceAtLeast(0.dp))
                                    val span = (yMax - yMin).coerceAtLeast(1.dp)
                                    yRatio = (yRatio + (delta.y.toDp() / span)).coerceIn(0f, 1f)
                                }
                            }
                        },
                ) {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            Icons.Outlined.DragIndicator,
                            contentDescription = stringResource(R.string.dsh_webui_ball),
                        )
                    }
                }

                AnimatedVisibility(
                    visible = expanded && !dragging,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(onClick = { expanded = false; onBack() }) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                            }
                            IconButton(onClick = { expanded = false; onReload() }) {
                                Icon(
                                    Icons.Outlined.Refresh,
                                    contentDescription = stringResource(R.string.dsh_webui_reload),
                                )
                            }
                            IconButton(onClick = { expanded = false; onEnterPip() }) {
                                Icon(
                                    Icons.Outlined.PictureInPictureAlt,
                                    contentDescription = stringResource(R.string.dsh_pip_button),
                                )
                            }
                            IconButton(onClick = { expanded = false; onOpenExternal() }) {
                                Icon(
                                    Icons.Outlined.OpenInBrowser,
                                    contentDescription = stringResource(R.string.dsh_webui_open_external),
                                )
                            }
                            IconButton(onClick = onClose) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = stringResource(R.string.dsh_webui_close),
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
 * 「开不了画中画」的引导。
 *
 * 分两种：设备没有画中画能力（只能说明情况），与系统把本应用的画中画关掉了（给一条去设置的
 * 路 —— 那个开关在应用信息页里，没有可直接打开的公开入口，所以不猜 ROM 的跳转）。
 */
@Composable
private fun PipGuideDialog(
    switchOff: Boolean,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dsh_pip_guide_title)) },
        text = {
            Text(
                stringResource(
                    if (switchOff) R.string.dsh_pip_guide_text_supported
                    else R.string.dsh_pip_guide_text_unsupported
                )
            )
        },
        confirmButton = {
            if (switchOff) {
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.dsh_pip_guide_settings))
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
            }
        },
        dismissButton = if (switchOff) {
            { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
        } else {
            null
        },
    )
}
