package me.bmax.apatch.dsh.ext

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** 一段槽位内容：给宿主一个 Modifier，画什么由扩展决定。 */
typealias SlotContent = @Composable (Modifier) -> Unit

/**
 * 宿主运行时状态的「扁平快照」。
 *
 * 为什么不直接给宿主自己的 DshState：那是 :app 的内部类型，暴露出去等于把整个 app 变成公开
 * API（以后谁都改不动）。外壳真正需要的只有这几项，做成稳定的数据类。
 */
data class RuntimeSummary(
    /** 运行时版本，例如 0.2.1-rc.2-ubuntunoble-r6。 */
    val version: String,
    /** 当前容器运行时 id，例如 proot / proroot / chroot。 */
    val mode: String,
    /** 服务是否在跑。 */
    val running: Boolean,
    /** 监听端口（未启动时为 0）。 */
    val port: Int,
)

/**
 * 交给「外壳扩展」的宿主导航句柄。
 *
 * 外壳接管了整屏之后，**必须**还能把用户送回宿主原有的页面 —— 否则替换外壳就等于把 App 关进
 * 一个空房间。所以宿主把这些动作包成接口交给外壳，而不是让外壳自己去摸 navController。
 */
interface ShellHost {
    /** 四个主页面 id，见 [ExtensionShellTabs]。 */
    fun navigate(tabId: String)

    /** 当前所在的主页面 id（外壳用来高亮）。 */
    fun currentTabId(): String

    /** 跳到任意目的地 route（设置子页等），外壳一般用不到。 */
    fun navigateRoute(route: String)

    /** 打开容器里的 WebUI（dsh 本体）。 */
    fun openWebUi()

    /** 运行时状态快照，供外壳显示「当前版本/运行方式」。 */
    fun runtimeSummary(): RuntimeSummary

    /** 运行时可观察状态（外壳想实时显示时收集它）。 */
    fun runtimeStates(): StateFlow<RuntimeSummary>
}

/** 主页面 id 常量（与宿主底栏四页一一对应）。 */
object ExtensionShellTabs {
    const val HOME = "home"
    const val TERMINAL = "terminal"
    const val PLUGINS = "plugins"
    const val SETTINGS = "settings"

    /** 全部主页面，顺序即宿主底栏顺序。 */
    val ALL = listOf(HOME, TERMINAL, PLUGINS, SETTINGS)
}

/**
 * 外壳内容：拿到宿主句柄与「原有页面树」的渲染入口。
 *
 * 第二个参数是宿主把同一棵导航树交出来的方式 —— 外壳负责画自己的顶栏/抽屉/底栏，
 * 但页面本体仍然是宿主原来的那套（插件页、设置页…一个都不能少）。
 */
typealias ShellContent = @Composable (ShellHost, @Composable (Modifier) -> Unit) -> Unit

/**
 * 界面接管总线。
 *
 * 三级能力（从保守到激进）：
 * 1. **具名槽位**：宿主在固定位置留插槽，扩展往里插内容（INSERT_BEFORE / REPLACE / …）；
 * 2. **整屏重绘**：接管某个界面的整屏；
 * 3. **外壳接管**：接管 App 的导航外壳（顶栏/抽屉/底栏），页面树仍由宿主提供；
 * 4. **主题色板拦截**：拿到宿主的 ColorScheme 再返回改过的一份。
 *
 * 熔断后一律返回空 / 原样 —— 界面必须回到宿主原生样子，否则用户连求救按钮都点不到。
 */
object ExtensionUiRegistry {

    enum class SlotOp { INSERT_BEFORE, INSERT_AFTER, REPLACE, REMOVE, WRAP }

    /** 一条已注册的槽位内容。 */
    data class SlotEntry(val extensionId: String, val op: SlotOp, val content: SlotContent)

    /** 一次整屏接管（**带 extensionId**：否则卸载时撤不掉，整屏会一直被它占着）。 */
    data class ScreenOverride(val extensionId: String, val content: SlotContent)

    /** 一次外壳接管。 */
    data class ShellOverride(val extensionId: String, val content: ShellContent)

    private val slots = ConcurrentHashMap<String, CopyOnWriteArrayList<SlotEntry>>()
    private val overrides = ConcurrentHashMap<String, CopyOnWriteArrayList<ScreenOverride>>()
    private val shells = CopyOnWriteArrayList<ShellOverride>()
    private val palette = CopyOnWriteArrayList<Pair<String, (ColorScheme) -> ColorScheme>>()

    /** 宿主内置槽位名（新增一个就是加一行常量，扩展侧用字符串引用）。 */
    object Slot {
        const val EXTENSIONS_PAGE_TOP = "extensions.page.top"
        const val HOME_CARD = "home.card"
        const val SETTINGS_FUNCTION = "settings.function"
        const val WEBUI_OVERLAY = "webui.overlay"
    }

    fun registerSlot(extensionId: String, slot: String, op: SlotOp, content: SlotContent) {
        if (ExtensionGate.isLatched()) return
        slots.getOrPut(slot) { CopyOnWriteArrayList() }.add(SlotEntry(extensionId, op, content))
    }

    /** 接管整屏（screenId 由宿主定义）。 */
    fun overrideScreen(extensionId: String, screenId: String, content: SlotContent) {
        if (ExtensionGate.isLatched()) return
        overrides.getOrPut(screenId) { CopyOnWriteArrayList() }.add(ScreenOverride(extensionId, content))
    }

    /**
     * 接管 App 的导航外壳。
     *
     * 多条注册时取**最后注册**的那条（后启用的扩展赢），只有一个外壳在生效 —— 两个外壳叠在
     * 一起既画不出来也没法解释。
     */
    fun registerShell(extensionId: String, content: ShellContent) {
        if (ExtensionGate.isLatched()) return
        shells.add(ShellOverride(extensionId, content))
    }

    /** 拦截主题色板。 */
    fun interceptPalette(extensionId: String, transform: (ColorScheme) -> ColorScheme) {
        if (ExtensionGate.isLatched()) return
        palette.add(extensionId to transform)
    }

    /** 取某个槽位的全部内容（按注册顺序）。熔断返回空。 */
    fun slotEntries(slot: String): List<SlotEntry> =
        if (ExtensionGate.isLatched()) emptyList() else (slots[slot]?.toList() ?: emptyList())

    /** 该界面是否被接管（返回第一个接管者）。 */
    fun screenOverride(screenId: String): ScreenOverride? =
        if (ExtensionGate.isLatched()) null else overrides[screenId]?.firstOrNull()

    /** 当前生效的外壳（没有则 null = 用宿主默认外壳）。 */
    fun shellOverride(): ShellOverride? =
        if (ExtensionGate.isLatched()) null else shells.lastOrNull()

    /** 依次过一遍色板拦截器。 */
    fun applyPalette(base: ColorScheme): ColorScheme {
        if (ExtensionGate.isLatched()) return base
        var out = base
        for ((id, transform) in palette) {
            out = runCatching { transform(out) }.getOrElse {
                ExtLog.log("[ext:" + id + "] 色板拦截抛异常（已跳过）")
                out
            }
        }
        return out
    }

    /** 撤掉某个扩展注册的全部界面内容（槽位 / 整屏 / 外壳 / 色板）。 */
    fun unregisterAll(extensionId: String) {
        for (list in slots.values) list.removeIf { it.extensionId == extensionId }
        for (list in overrides.values) list.removeIf { it.extensionId == extensionId }
        shells.removeIf { it.extensionId == extensionId }
        palette.removeIf { it.first == extensionId }
    }

    fun clearAll() {
        slots.clear(); overrides.clear(); shells.clear(); palette.clear()
    }
}
