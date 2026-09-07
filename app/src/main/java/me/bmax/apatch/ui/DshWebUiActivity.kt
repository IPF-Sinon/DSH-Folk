package me.bmax.apatch.ui

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.File
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshEnv
import me.bmax.apatch.dsh.DshRuntime
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
    onPick: (enable: Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { /* 划掉 = 这次先不决定，下次打开再问 */ },
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
            TextButton(onClick = { onPick(true) }) {
                Text(stringResource(R.string.dsh_webui_compat_enable))
            }
        },
        dismissButton = {
            TextButton(onClick = { onPick(false) }) {
                Text(stringResource(R.string.dsh_webui_compat_skip))
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
 * `AbortSignal.any is not a function`。[COMPAT_SHIM] 在文档开始前补齐这两个 API，
 * 见 [installCompatShim]。
 */
class DshWebUiActivity : AppCompatActivity() {

    private var webView: WebView? = null
    private var canGoBack = false

    /** document-start 垫片是否已装上；没装上才需要在 onPageStarted 里补注入。 */
    private var compatShimInstalled = false

    /** 待回填给 `<input type="file">` 的回调；同一时刻只可能有一个选择器。 */
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooser: ActivityResultLauncher<Intent>

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent?.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }
            ?: "http://127.0.0.1:${DshRuntime.port()}/"

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

                // 首次遇到旧内核时问一次要不要装兼容垫片。
                // 在这里问而不是在设置页：只有真正打开 WebUI 才知道内核是哪个，
                // 而且此刻用户正要用它，说明「不装会打不开工作区」最有说服力。
                val kernel = remember { DshWebCompat.kernel(this@DshWebUiActivity) }
                var askCompat by remember {
                    mutableStateOf(DshWebCompat.shouldAsk(this@DshWebUiActivity, kernel))
                }
                if (askCompat) {
                    DshCompatShimDialog(
                        kernel = kernel,
                        onPick = { enable ->
                            DshWebCompat.setMode(
                                this@DshWebUiActivity,
                                if (enable) DshWebCompat.MODE_ON else DshWebCompat.MODE_OFF,
                            )
                            askCompat = false
                            if (enable) {
                                // 页面已经在加载了，而 addDocumentStartJavaScript 只对
                                // 「调用返回之后才开始加载」的 frame 生效 —— 所以这里补装一次
                                // 再 reload，让垫片真的落在 document-start 上
                                webView?.let { view ->
                                    compatShimInstalled = installCompatShim(view, url)
                                    view.reload()
                                }
                            }
                        },
                    )
                }

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
                                // blob: 下载的桥。只在 loadUrl 的回环地址上注入
                                // （onPageStarted 里按 origin 校验），别的来源拿不到它
                                addJavascriptInterface(BlobBridge(), BLOB_BRIDGE)
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
                                        if (isLoopback(u)) view?.evaluateJavascript(BLOB_SHIM, null)
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
                                        // 同样受开关约束 —— compatShimInstalled 为 false 有两种
                                        // 原因（不该注入 / 想注入但装不上），所以这里要再问一次
                                        if (!compatShimInstalled && isLoopback(u) &&
                                            DshWebCompat.shouldInject(this@DshWebUiActivity)
                                        ) {
                                            view?.evaluateJavascript(COMPAT_SHIM, null)
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
                                // 兼容垫片必须在 loadUrl 之前装：addDocumentStartJavaScript
                                // 只对「调用返回之后才开始加载」的 frame 生效
                                compatShimInstalled = installCompatShim(this, url)
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
     * 只从回环页面注入（[BLOB_SHIM] 由 onPageFinished 在校验 origin 后执行）。
     * 即便如此也不信任入参：文件名只取 basename 并过滤路径分隔符，写入目录写死。
     */
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
     * 在**文档开始前**给旧内核补上 dsh 前端用到的新 JS API，返回是否装上了。
     *
     * 必须是 document-start：`AbortSignal.any` 在模块顶层就会被引用路径碰到，
     * 等到 `onPageFinished` 再补已经晚了（那时异常早就抛完了）。
     * [WebViewCompat.addDocumentStartJavaScript] 就是干这个的，能力位由
     * [WebViewFeature.DOCUMENT_START_SCRIPT] 决定；不支持时回落到 `onPageStarted`
     * 里 `evaluateJavascript`（尽力而为，比什么都不做好）。
     *
     * 只对回环 origin 生效：origin 规则里端口**必须写出来**，不写会被当成 80/443，
     * 所以规则从实际要加载的 URL 和 [DshRuntime.port] 现算，不能写死。
     *
     * 是否注入由 [DshWebCompat] 决定（默认只在旧内核上注入，且先问过用户）。
     */
    private fun installCompatShim(view: WebView, url: String): Boolean {
        if (!DshWebCompat.shouldInject(this)) {
            Log.i(TAG, "compat shim disabled for this WebView")
            return false
        }
        val supported = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        }.getOrDefault(false)
        if (!supported) {
            Log.i(TAG, "document-start script unsupported, falling back to onPageStarted")
            return false
        }
        val rules = loopbackOriginRules(url)
        return runCatching {
            WebViewCompat.addDocumentStartJavaScript(view, COMPAT_SHIM, rules)
            true
        }.getOrElse {
            // 规则非法（IllegalArgumentException）或内核临时不支持都在这里兜住
            Log.w(TAG, "addDocumentStartJavaScript failed for $rules", it)
            false
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
        // 页面走了但选择器回调还挂着时也要放掉，否则 WebView 内部一直等
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "dsh_webui_url"
        private const val TAG = "DshWebUi"

        /** 下载落地的公共子目录（用户找得到）。 */
        private const val PUBLIC_SUBDIR = "DSH-Folk"

        /** JS 侧看到的桥名。 */
        private const val BLOB_BRIDGE = "DshFolkDownload"

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

        /**
         * 旧 WebView 兼容垫片：把 dsh 前端用到、但内核太老没有的 JS API 补齐。
         *
         * 现象是「打开工作区报 `AbortSignal.any is not a function`」。设备上的
         * WebView 是 Chromium 110（OPPO PJJ110，SDK 35 却带着 110.0.5481.154），
         * 而 dsh 前端用到：
         *
         * | API | 需要 | 用在哪 |
         * |---|---|---|
         * | `AbortSignal.any` | Chrome 116 | 每个带 signal 的 RPC（`ctx.sessions.search`、`ctx.workspaces.listDirectory`…）、`postJson` 超时合并 |
         * | `Promise.withResolvers` | Chrome 119 | cordis 的 `ctx.timeout()` / `ctx.interval()` |
         *
         * 都是纯语言/平台 API，能在主线程用几行 JS 等价实现。前端全部代码里没有
         * `new Worker` / `SharedWorker` / service worker，所以主文档一份就够。
         *
         * 幂等：重复注入（同页多 frame、SPA、刷新）只装一次。
         * 只补缺的，新内核上什么都不动。
         */
        private const val COMPAT_SHIM = """
(function(){
  if (window.__dshFolkCompat) return; window.__dshFolkCompat = 1;
  // AbortSignal.any(signals)：任一 abort 即 abort，并带上原 reason。
  //
  // 用 WeakRef 持有返回的 controller：真实实现里「派生 signal」被源 signal 弱引用，
  // 没人用了就能回收。这里的调用点之一是
  //   AbortSignal.any([token.abort.signal, callerSignal])
  // 而 token.abort.signal 活得和整个挂载一样久 —— 若强引用，每次 RPC 都会在它上面
  // 留下一个永不摘除的闭包，一次长会话累积成千上万个。WeakRef 是 Chrome 84 起有的。
  if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.any !== 'function') {
    AbortSignal.any = function(signals){
      var list = [];
      var raw = signals || [];
      for (var i = 0; i < raw.length; i++) { if (raw[i]) list.push(raw[i]); }
      var ctrl = new AbortController();
      for (var n = 0; n < list.length; n++) {
        // 已经 abort 的输入要立刻反映，不能等事件
        if (list[n].aborted) { ctrl.abort(list[n].reason); return ctrl.signal; }
      }
      var weak = typeof WeakRef === 'function' ? new WeakRef(ctrl) : null;
      var onAbort = function(ev){
        var target = weak ? weak.deref() : ctrl;
        for (var j = 0; j < list.length; j++) {
          if (list[j].removeEventListener) list[j].removeEventListener('abort', onAbort);
        }
        // 派生 signal 已被回收 —— 没人再关心这次 abort，顺手把监听摘掉就行
        if (!target) return;
        var src = ev && ev.target ? ev.target : null;
        if (src) target.abort(src.reason); else target.abort();
      };
      for (var k = 0; k < list.length; k++) {
        if (list[k].addEventListener) list[k].addEventListener('abort', onAbort);
      }
      return ctrl.signal;
    };
  }
  // AbortSignal.timeout(ms)：110 已有，仅极旧内核兜底
  if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.timeout !== 'function') {
    AbortSignal.timeout = function(ms){
      var ctrl = new AbortController();
      setTimeout(function(){
        var err;
        try { err = new DOMException('signal timed out', 'TimeoutError'); }
        catch (e) { err = new Error('signal timed out'); }
        ctrl.abort(err);
      }, ms);
      return ctrl.signal;
    };
  }
  // Promise.withResolvers()：把 resolve/reject 掏到外面
  if (typeof Promise !== 'undefined' && typeof Promise.withResolvers !== 'function') {
    Promise.withResolvers = function(){
      var res, rej;
      var p = new Promise(function(a, b){ res = a; rej = b; });
      return { promise: p, resolve: res, reject: rej };
    };
  }
  // crypto.randomUUID()：它**只在安全上下文提供**。http://127.0.0.1 算安全，
  // 但开了「局域网访问」后页面是 http://<手机IP>:<端口>，不算 —— 于是
  // 前端里直接调它的地方（会话消息 id、附件草稿）会炸。
  // getRandomValues 在非安全源照常可用，按 RFC 4122 拼一个 v4 出来即可。
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID !== 'function'
      && typeof crypto.getRandomValues === 'function') {
    crypto.randomUUID = function(){
      var b = crypto.getRandomValues(new Uint8Array(16));
      b[6] = (b[6] & 0x0f) | 0x40;   // version 4
      b[8] = (b[8] & 0x3f) | 0x80;   // variant 10xx
      var h = [];
      for (var i = 0; i < 16; i++) h.push((b[i] + 0x100).toString(16).slice(1));
      return h[0]+h[1]+h[2]+h[3] + '-' + h[4]+h[5] + '-' + h[6]+h[7]
        + '-' + h[8]+h[9] + '-' + h[10]+h[11]+h[12]+h[13]+h[14]+h[15];
    };
  }
})();
"""

        /**
         * 拦 blob:/data: 下载。
         *
         * WebView 对这两种 scheme 不会触发 DownloadListener（没有网络请求可拦），
         * 所以在页面里挂一个 `click` 捕获监听：看到带 download 属性、且 href 是
         * blob:/data: 的锚点就自己读成 base64 交给原生，并阻止默认行为。
         * 同时兜住 `URL.createObjectURL` + 程序化 click 的写法（那也是一个真锚点）。
         *
         * 幂等：重复注入（刷新、SPA 路由）只装一次监听。
         */
        private const val BLOB_SHIM = """
(function(){
  if (window.__dshFolkBlobShim) return; window.__dshFolkBlobShim = 1;
  function grab(href, name){
    fetch(href).then(function(r){return r.blob()}).then(function(b){
      var fr = new FileReader();
      fr.onloadend = function(){
        var s = String(fr.result || '');
        var i = s.indexOf(',');
        if (i >= 0) DshFolkDownload.save(s.slice(i+1), name || 'download');
      };
      fr.readAsDataURL(b);
    }).catch(function(e){ console.warn('dsh-folk blob download failed', e); });
  }
  document.addEventListener('click', function(ev){
    var a = ev.target && ev.target.closest ? ev.target.closest('a[download]') : null;
    if (!a) return;
    var href = a.getAttribute('href') || '';
    if (href.indexOf('blob:') !== 0 && href.indexOf('data:') !== 0) return;
    ev.preventDefault();
    grab(href, a.getAttribute('download'));
  }, true);
})();
"""
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
