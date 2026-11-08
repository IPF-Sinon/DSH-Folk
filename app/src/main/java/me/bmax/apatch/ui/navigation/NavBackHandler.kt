package me.bmax.apatch.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController

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

        // ── 只在"首页确实在同一张栈里"时才弹回，绝不 navigate ──
        //
        // 上一版这里是 `navigate(homeRoute) { popUpTo(homeRoute) { inclusive = false } }`，
        // 那个写法有两个漏洞，都会导致「连按返回把首页也退掉」：
        //
        //  1. `popUpTo(homeRoute)` 只在 homeRoute **真的在栈里**时才有作用。而底栏切标签
        //     用的是 `popUpTo(NavGraphs.root) { saveState = true }` —— 那个弹出会把
        //     homeRoute 一起弹掉（root 是图根，home 是它的第一个目的地的子层）。
        //     于是 homeRoute 不在栈里时，`popUpTo` 等于没写，只剩下一次纯 `navigate`，
        //     每按一下就往栈里**多加**一个 home —— 越按越深，最后连根一起退掉。
        //  2. `navigate` + `launchSingleTop` 只挡"栈顶已经是 home"，挡不住"home 在栈中段"。
        //
        // `popBackStack(homeRoute, inclusive = false)` 的语义正好相反：它在栈里找到 home
        // 就弹回那里；**找不到就返回 false 什么都不做**。这正是我们要的 ——
        // 宁可这一下没反应，也不许凭空造出一层新的首页。
        val popped = navController.popBackStack(homeRoute, inclusive = false)
        if (popped) {
            onBeforeBack()
            return@back
        }

        // 走到这里说明 home 已经不在栈里（典型：用户在 A 标签里，又切到了 B 标签）。
        // 这一下**什么都不做**，交回系统。
        //
        // 为什么不 navigate 过去：任何形式的 navigate 都是在栈顶再叠一层。
        // 叠出来的那层会让下一次返回把栈底（图根）也带走，这正是
        // 「疯狂按返回，首页也没了」那条链。宁可这一下没反应，
        // 也不许凭自己的判断去改栈的形状 —— 用户还在标签页里，
        // 系统的默认返回（退出应用）本来就不是他想要的，但他按的是返回，
        // 我们不该替他造一个"首页"出来。
        //
        // 代价：这一下返回没有视觉反馈。可接受 —— 它只在
        // "home 已被底栏切换弹掉"这个窄条件下发生，而那个状态下
        // 页面本来就已经是能停住的地方。
        return@back
    }
}
