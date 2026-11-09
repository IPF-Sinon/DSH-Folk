package me.bmax.apatch.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import com.ramcosta.composedestinations.generated.NavGraphs

/**
 * 底栏标签页的返回键语义：**返回首页，首页交回系统**。
 *
 * ## 为什么单独抽出来
 *
 * 宿主有三种外壳（浮动底栏 / 侧栏 / 普通底栏），原来三处各写一遍一模一样的
 * `BackHandler(enabled = currentRoute in bottomBarRoutes && currentRoute != homeRoute)`。
 * 三份复制品都带着同一个缺陷，改一处漏两处，所以合成一个。
 *
 * ## 修的缺陷：连按返回会把首页也退掉
 *
 * `currentRoute` 来自 `currentBackStackEntryAsState()`，它**由导航状态驱动**，
 * 不是按下的瞬间就更新。所以「疯狂连按返回」时会发生：
 *
 * 1. 第 1 次按：当前在某个标签页 → 执行 `navigate(home)`，导航开始；
 * 2. 导航还没落地，第 2、3 次按已经到：此时 [currentRoute] **仍是旧值**，
 *    `enabled` 依然为 true → 又执行一遍 `navigate(home)`；
 * 3. 每一遍都带 `popUpTo(NavGraphs.root)`，反复把所有页面连同首页一起弹掉。
 *
 * 表现就是"连按返回，最后首页也没了，直接退出应用"。
 *
 * ## 做法
 *
 * - **时间防抖**：两次有效返回之间至少隔 [MIN_INTERVAL_MS]。这个窗口不是随便取的：
 *   它要短于人手连按的最小间隔（约 120ms 以上），又要长于一次导航落地的时间。
 *   80ms 太短挡不住连按，150ms 会让人觉得"按了没反应"，取 100ms。
 * - **栈底保护**：真正执行前再查一次 `previousBackStackEntry`。栈里只剩首页时
 *   直接返回 false，把这一下交回系统的默认处理（也就是退出应用），
 *   而不是继续 `navigate` 造出一个悬空的根。
 *
 * 注意 `enabled` 已经包含「不在首页」的判断，所以首页按返回仍是系统默认行为。
 */
private const val MIN_BACK_INTERVAL_MS = 100L

@Composable
fun rememberTabBackHandler(
    navController: NavHostController,
    currentRoute: String?,
    homeRoute: String,
    // 用 Set 而不是 List：调用方的 `bottomBarRoutes` 本来就是 Set<String>，
    // 这里若坚持 List 就要求调用处多做一次 toList() 转换（每次重组都新建一个 List）。
    // 而且这个参数只用于 `in` 判断，Set 的语义正是"成员资格"。
    bottomBarRoutes: Set<String>,
    onBeforeBack: () -> Unit = {},
) {
    // 上一次"真的执行了返回"的时刻。用 remember 而不是 CompositionLocal：
    // 它只服务于这一个 BackHandler 实例，跨页面共享没有意义。
    var lastBackAt by remember { mutableLongStateOf(0L) }

    BackHandler(enabled = currentRoute != null && currentRoute in bottomBarRoutes && currentRoute != homeRoute) back@{
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastBackAt < MIN_BACK_INTERVAL_MS) {
            // 连按的后续几下直接吞掉：导航已经在路上，再发一次就是重复弹栈。
            return@back
        }
        lastBackAt = now

        // ── 回到首页：能弹就弹，弹不了就"清到图根再放首页" ──
        //
        // 第一选择是 `popBackStack(homeRoute, inclusive = false)`：在栈里找到首页就弹回
        // 那里，**找不到返回 false 且不动栈**。
        //
        // 但"找不到"是常态：底栏切标签用的是 `popUpTo(NavGraphs.root) { saveState = true }`，
        // 那一下会把首页一起弹进 saved state（root 是图根，首页是它的第一个目的地）。
        // 于是用户在「终端」页按返回时，首页其实**不在**栈里。
        //
        // 旧版在这里什么都不做，把这一下交回系统 —— 而系统默认返回就是 finish()，
        // 于是"点几下标签再按返回"直接退回桌面。用户的原话正是"快速点击还是能退掉首页"。
        //
        // 现在补上第二条路：先把栈清到图根（一次干净的收尾，不累加），再
        // `launchSingleTop + restoreState` 把首页放回去。结果是栈形 `[首页]`，
        // 与"本来就在首页按返回"完全同一个状态；而不是每按一次就往栈顶叠一层。
        val popped = navController.popBackStack(homeRoute, inclusive = false)
        if (!popped) {
            navController.navigate(homeRoute) {
                popUpTo(NavGraphs.root.route) { inclusive = false }
                launchSingleTop = true
                restoreState = true
            }
        }
        // 底栏复位之类的收尾放在成功/回填之后：两条路都真的动了导航，都该复位。
        onBeforeBack()
        return@back
    }
}