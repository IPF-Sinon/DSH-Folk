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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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

/**
 * 内置 WebUI 容器。
 *
 * 只加载本机 `http://127.0.0.1:<port>`：明文由 network_security_config 允许，
 * 无需额外权限。不开 allowFileAccess —— 这个 WebView 除了本地回环没有别的用途，
 * 放开文件访问只会给页面多一条读 app 私有目录的路。
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
                var title by remember { mutableStateOf(url) }

                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(WindowInsets.safeDrawing.asPaddingValues())
                ) {
                    TopAppBar(
                        title = {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { finish() }) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                            }
                        },
                        actions = {
                            IconButton(onClick = { webView?.reload() }) {
                                Icon(
                                    Icons.Outlined.Refresh,
                                    contentDescription = stringResource(R.string.dsh_webui_reload),
                                )
                            }
                            IconButton(onClick = { DshWebUi.openExternal(this@DshWebUiActivity, url) }) {
                                Icon(
                                    Icons.Outlined.OpenInBrowser,
                                    contentDescription = stringResource(R.string.dsh_webui_open_external),
                                )
                            }
                        },
                    )
                    if (progress in 1..99) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Box(Modifier.fillMaxSize()) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
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
                                            view?.title?.takeIf { it.isNotBlank() }?.let { title = it }
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
                                            // 只报主文档失败：子资源失败（favicon 之类）不该覆盖标题
                                            if (request?.isForMainFrame == true) {
                                                title = getString(R.string.dsh_webui_load_failed)
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
                    }
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
