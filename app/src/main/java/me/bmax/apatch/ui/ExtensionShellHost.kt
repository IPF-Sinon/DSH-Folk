package me.bmax.apatch.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.bmax.apatch.dsh.DshRuntime
import me.bmax.apatch.dsh.ext.ExtensionEngine
import me.bmax.apatch.dsh.ext.ExtensionRegistry
import me.bmax.apatch.dsh.ext.ExtensionShellTabs
import me.bmax.apatch.dsh.ext.ExtensionUiRegistry
import me.bmax.apatch.dsh.ext.RuntimeSummary
import me.bmax.apatch.dsh.ext.ShellHost
import me.bmax.apatch.ui.screen.BottomBarDestination

/**
 * 宿主侧的 [ShellHost] 实现：把外壳扩展需要的导航动作接到真实的 navController 上。
 *
 * 三条设计约束：
 * 1. **导航必须与底栏同一套参数**（popUpTo + saveState + launchSingleTop + restoreState）。
 *    另起一套的话，外壳里切页会清掉返回栈，底栏那边切页又不会 —— 同一台机器两种返回行为，
 *    出了 bug 根本说不清是哪个壳的问题。
 * 2. **状态用 StateFlow 推**：外壳要跟着当前页高亮，而 currentRoute 的变化在 Compose 里是
 *    重组事件，扩展拿不到；所以这里把 currentRoute 收集成流再交给它。
 * 3. **resetToDefaultShell 必须真的能「回去」**：外壳把导航整个接管了，一旦它画坏，
 *    用户没有任何入口。这里做两件事 —— 停用该扩展 + 立刻撤销它的界面注册，然后回调宿主重组。
 */
@Composable
internal fun rememberDshShellHost(
    navController: NavHostController,
    homeRoute: String,
    currentRoute: String?,
    /** 让宿主外壳分支重组一次（撤销外壳之后必须重新读一次情况）。 */
    onShellReleased: () -> Unit,
): ShellHost {
    val context = LocalContext.current

    val tabFlow = remember { MutableStateFlow(ExtensionShellTabs.HOME) }
    val runtimeFlow = remember { MutableStateFlow(RuntimeSummary("", "", false, 0)) }

    // 当前页 → 外壳可用的 tab id（不在四页里时归到 HOME，避免外壳高亮成「无」）
    LaunchedEffect(currentRoute) {
        val dest = BottomBarDestination.entries.firstOrNull { it.direction.route == currentRoute }
        tabFlow.value = dest?.let { tabIdOf(it) } ?: ExtensionShellTabs.HOME
    }
    // 运行时状态一直跟着推：外壳顶栏那个胶囊要实时（启动中/端口/版本都会变）
    LaunchedEffect(Unit) {
        DshRuntime.state.collect { st ->
            runtimeFlow.value = RuntimeSummary(
                version = st.runtimeVersion ?: "",
                // DshState 里没有「当前运行时 id」（proot / proroot / chroot）这个字段，
                // 它属于运行时的启动参数而不是状态快照。外壳那颗胶囊因此只显示版本与端口，
                // 不硬凑一个假的 mode —— 显示错的信息比不显示更糟。
                mode = "",
                running = st.phase == me.bmax.apatch.dsh.DshPhase.RUNNING,
                port = st.port,
            )
        }
    }

    return remember(navController, homeRoute, onShellReleased) {
        object : ShellHost {
            override fun navigate(tabId: String) {
                val dest = BottomBarDestination.entries.firstOrNull { tabIdOf(it) == tabId } ?: return
                navController.navigate(dest.direction.route) {
                    // 与 [me.bmax.apatch.ui.screen.BottomBar] 完全一致：不同的话返回栈行为会分叉
                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }

            override fun currentTabId(): String = tabFlow.value

            override fun currentTabStates(): StateFlow<String> = tabFlow

            override fun navigateRoute(route: String) {
                runCatching { navController.navigate(route) }
            }

            override fun openWebUi() {
                runCatching {
                    context.startActivity(
                        Intent(context, DshWebUiActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }

            override fun runtimeSummary(): RuntimeSummary = runtimeFlow.value

            override fun runtimeStates(): StateFlow<RuntimeSummary> = runtimeFlow

            override fun resetToDefaultShell() {
                val owner = ExtensionUiRegistry.shellOverride()?.extensionId ?: return
                // 先卸（撤掉它注册的 Hook/SPI/UI/外壳），再禁用（下次启动不再加载）。
                // 顺序不能反：只禁用不卸，内存里的外壳这次仍然占着整屏。
                ExtensionEngine.unload(context.applicationContext, owner)
                ExtensionRegistry.setEnabled(context.applicationContext, owner, false)
                onShellReleased()
            }
        }
    }
}

/** BottomBarDestination → 外壳 tab id。 */
private fun tabIdOf(dest: BottomBarDestination): String = when (dest) {
    BottomBarDestination.Home -> ExtensionShellTabs.HOME
    BottomBarDestination.Terminal -> ExtensionShellTabs.TERMINAL
    BottomBarDestination.Plugin -> ExtensionShellTabs.PLUGINS
    BottomBarDestination.Settings -> ExtensionShellTabs.SETTINGS
}
