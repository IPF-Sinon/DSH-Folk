package me.bmax.apatch.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 浏览器模式下的 Via 式导航栏。
 *
 * **显隐由调用方控制**（[visible]）：切换动作绑在现有的悬浮球**长按**上，所以这个组件
 * 自己不再画按钮 —— 再画一颗会出现两个入口互相打架（用户分不清哪颗算「那颗球」）。
 *
 * 内容：后退 / 前进 / 当前地址（只读展示）/ **回 DSH 首页** / 刷新 / 退出浏览器模式。
 *
 * ## 首页按钮为什么必须存在
 * 浏览器模式一旦打开，用户可能漂到任意站点。没有「回 dsh 首页」就等于把人锁在外部网页上，
 * 得靠系统返回键一层层退 —— 而站点自己也可能吃掉返回键。
 *
 * ## 地址为什么只读
 * 这里是「看清自己在哪」，不是地址输入框。真要做地址输入得配一整套输入法、补全与校验，
 * 那是另一个功能；现在把只读地址摆在这是为了让「站内导航」这件事可解释。
 */
@Composable
internal fun WebUiBrowserBar(
    visible: Boolean,
    currentUrl: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onGoHome: () -> Unit,
    onExitBrowserMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
            tonalElevation = 3.dp,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = onBack, enabled = canGoBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "后退")
                }
                IconButton(onClick = onForward, enabled = canGoForward) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, "前进")
                }
                Row(
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = currentUrl,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onGoHome) {
                    Icon(Icons.Filled.Home, "回到 DSH 首页")
                }
                IconButton(onClick = onReload) {
                    Icon(Icons.Outlined.Refresh, "刷新")
                }
                IconButton(onClick = onExitBrowserMode) {
                    Icon(Icons.Outlined.Close, "退出浏览器模式")
                }
            }
        }
    }
}
