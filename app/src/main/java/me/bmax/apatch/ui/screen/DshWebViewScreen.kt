package me.bmax.apatch.ui.screen

import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import me.bmax.apatch.R

/**
 * 应用内网页浏览入口（只做浏览，不是又一个浏览器）。
 *
 * 为什么要它：脚本市场里的两类页面都走它 —— 主源失败时给的那个「镜像站」（静态导航页，没有
 * 脚本正文 / JSON，所以不能拿来装）与脚本页（作者、说明、评分）。原来那两步交给系统浏览器 ——
 * 用户被甩出应用，回来之后市场这一页的连接状态与滚动位置都没了；现在两处统一，看一眼就回来。
 *
 * 刻意保持最小：一个顶栏 + 一个 WebView。没有地址栏、没有标签页、没有下载器、没有书签 ——
 * 那些是浏览器的活。三条行为是明确的：
 *
 * - **页面内跳转留在这里**：http/https 继续在本层加载（装了 [WebViewClient] 就不会再外抛给
 *   系统），非网页协议（market://、tg://、mailto:…）本层加载不了 → 给一句明确提示；
 * - **下载不假装能装**：GreasyFork 的 `.user.js` 是下载链接，这里没有下载器，也不该把
 *   "点了没反应"留给用户 → [WebView.setDownloadListener] 同样给那句提示（要装脚本回市场）；
 * - 主文档加载失败时如实说一句，而不是留一屏白。
 */
@Destination<RootGraph>
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DshWebViewScreen(navigator: DestinationsNavigator, url: String) {
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    // 页面里有这一层处理不了的东西（下载 / 非网页协议）时，显示一句说得通的提示
    var notice by remember { mutableStateOf(false) }

    // WebView 是 Android View：remember 一份、退出时 destroy，避免每次进来都留一个内核实例。
    val webView = remember {
        WebView(context).apply {
            // 只开 JS：镜像站是普通网页，关掉它多半是空白页
            settings.javaScriptEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    // 只有主文档失败才算「打不开」：一张图、一个统计脚本失败不该误报
                    if (request.isForMainFrame) failed = true
                }

                /**
                 * 页面内跳转留在这一层：http/https 照常加载；其余协议 WebView 加载不了，
                 * 如实提示（不甩给系统、也不留一屏白）。
                 */
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val scheme = request.url?.scheme?.lowercase()
                    if (scheme == "http" || scheme == "https") return false
                    notice = true
                    return true
                }
            }
            // 下载（.user.js / 附件）：没有下载器，也不该假装能装 —— 明确告诉用户去哪装
            setDownloadListener { _, _, _, _, _ -> notice = true }
            loadUrl(url)
        }
    }
    DisposableEffect(webView) {
        onDispose {
            runCatching {
                webView.stopLoading()
                webView.destroy()
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.dsh_webview_title)) },
            navigationIcon = {
                IconButton(onClick = { navigator.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                }
            },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (failed) {
                Text(
                    text = stringResource(R.string.dsh_webview_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (notice) {
                Text(
                    text = stringResource(R.string.dsh_webview_cannot_handle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
