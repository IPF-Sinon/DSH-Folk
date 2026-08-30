package me.bmax.apatch.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshEnv
import me.bmax.apatch.dsh.DshRuntime
import me.bmax.apatch.ui.theme.APatchTheme
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
 */
class DshWebUiActivity : AppCompatActivity() {

    private var webView: WebView? = null
    private var canGoBack = false

    @SuppressLint("SetJavaScriptEnabled")
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent?.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }
            ?: "http://127.0.0.1:${DshRuntime.port()}/"

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

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    // 只有 WebView 需要避开状态栏与手势条；容器本身铺满整窗，
                    // 悬浮球才能贴到真正的屏幕边缘
                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(WindowInsets.safeDrawing.asPaddingValues()),
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
                                webViewClient = object : WebViewClient() {
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
                                }
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

                    WebUiFloatingBall(
                        onBack = { onBackPressedDispatcher.onBackPressed() },
                        onClose = { finish() },
                        onReload = { webView?.reload() },
                        onOpenExternal = { DshWebUi.openExternal(this@DshWebUiActivity, url) },
                    )
                }
            }
        }
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
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "dsh_webui_url"
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
