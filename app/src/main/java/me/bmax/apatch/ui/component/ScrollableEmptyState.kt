package me.bmax.apatch.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/**
 * 居中的空状态，**可滚动**。
 *
 * 为什么不能直接用 `Box(fillMaxSize())`：Box 不参与嵌套滚动，
 * `rememberScrollConnection.onPreScroll` 收不到事件；而悬浮底栏 3 秒自动隐藏后
 * 唯一的恢复入口就是 onPreScroll 里的 resetBottomBarAutoHide()。于是页面内容为空时
 * 底栏一藏就再也叫不回来（真机实测：插件页没有插件时底栏消失后无法下拉唤回）。
 *
 * `verticalScroll` 即便内容不足一屏也会参与嵌套滚动分发，over-scroll 手势仍产生
 * onPreScroll，底栏因此能被唤回。
 *
 * 这个坑已经出现过两次（终端页、插件页），所以抽出来共用，别再修第三遍。
 * 终端页是另一回事：TerminalView 是原生 View，自己吃掉触摸事件、根本不进
 * Compose 的嵌套滚动，那里的白名单绕过仍然必要。
 */
@Composable
fun ScrollableEmptyState(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val minHeight = LocalConfiguration.current.screenHeightDp.dp
    Box(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(
            modifier = Modifier.fillMaxWidth().heightIn(min = minHeight),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}
