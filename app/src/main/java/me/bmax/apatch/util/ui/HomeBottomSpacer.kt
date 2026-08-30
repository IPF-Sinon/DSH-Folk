package me.bmax.apatch.util.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import me.bmax.apatch.ui.navigation.LocalBottomBarVisible
import me.bmax.apatch.ui.navigation.LocalIsFloatingNavMode

/**
 * 页面底部为底栏预留的空白。
 *
 * 输入法弹起时归零：底栏此刻被键盘整块盖住，再为它留 80dp 就成了内容与键盘之间
 * 一大片空隙（真机实测终端页的扩展键条被顶到屏幕 0.56 高度处，与键盘之间空出约
 * 0.15 屏高）。IME 的 inset 由调用方的 `imePadding()` 负责，这里只管底栏那一份。
 */
@Composable
fun HomeBottomSpacer(modifier: Modifier = Modifier) {
    val isFloatingMode = LocalIsFloatingNavMode.current
    val bottomBarVisible = LocalBottomBarVisible.current.value
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val imeVisible = isImeVisible()

    val targetHeight by animateDpAsState(
        targetValue = when {
            imeVisible -> 0.dp
            isFloatingMode && bottomBarVisible -> 80.dp + navBarBottom
            isFloatingMode -> navBarBottom
            else -> 16.dp
        },
        animationSpec = tween(durationMillis = 300),
        label = "homeBottomSpacer"
    )

    Spacer(modifier.height(targetHeight))
}

/**
 * 输入法是否占据了屏幕空间。
 *
 * 读 inset 的原始像素而不是 `asPaddingValues()`：后者在键盘动画期间会被
 * consume 影响，而这里只需要「键盘是否在」这个布尔判断。
 */
@Composable
fun isImeVisible(): Boolean {
    val density = LocalDensity.current
    return WindowInsets.ime.getBottom(density) > 0
}
