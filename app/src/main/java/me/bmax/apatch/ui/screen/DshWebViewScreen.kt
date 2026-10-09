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
 * 为什么要它：脚本市场主源失败时给的那个「镜像站」是一个静态导航页（没有脚本正文 / JSON，
 * 所以不能拿来装，只能看）。原来那一步交给系统浏览器 —— 用户被甩出应用，回来之后市场这一页
 * 的连接状态与滚动位置都没了；而这条路的全部意义就是"连着刚才那次失败往下看"。
 *
 * 刻意保持最小：一个顶栏 + 一个 WebView。没有地址栏、没有标签页、没有下载器、没有书签 ——
 * 那些是浏览器的活。页面内的跳转留在这一层（装了 [WebViewClient] 就不会再外抛给系统）；
 * 主文档加载失败时如实说一句，而不是留一屏白。
 */
@Destination<RootGraph>
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DshWebViewScreen(navigator: DestinationsNavigator, url: String) {
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }

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
            }
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
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
