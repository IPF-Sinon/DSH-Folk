package me.bmax.apatch.dsh.ext

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** 一段槽位内容：给宿主一个 Modifier，画什么由扩展决定。 */
typealias SlotContent = @Composable (Modifier) -> Unit

/**
 * 界面接管总线。
 *
 * 三级能力（从保守到激进）：
 * 1. **具名槽位**：宿主在若干固定位置留了插槽（扩展页顶部、首页卡片区等），扩展往里插内容，
 *    可选 INSERT_BEFORE / INSERT_AFTER / REPLACE / REMOVE / WRAP；
 * 2. **整屏重绘**：扩展接管某个界面的整屏（风险最高，只对白名单界面开放）；
 * 3. **主题色板拦截**：拿到宿主的 ColorScheme 再返回一份改过的（改完立刻全 App 生效）。
 *
 * 安全模式下一律返回空 / 原样 —— 熔断之后界面必须回到宿主原生样子，否则用户连求救按钮都点不到。
 */
object ExtensionUiRegistry {

    /** 槽位内的操作类型。 */
    enum class SlotOp { INSERT_BEFORE, INSERT_AFTER, REPLACE, REMOVE, WRAP }

    /** 一条已注册的槽位内容。 */
    data class SlotEntry(val extensionId: String, val op: SlotOp, val content: SlotContent)

    private val slots = ConcurrentHashMap<String, CopyOnWriteArrayList<SlotEntry>>()
    private val overrides = ConcurrentHashMap<String, CopyOnWriteArrayList<SlotContent>>()
    private val palette = CopyOnWriteArrayList<Pair<String, (androidx.compose.material3.ColorScheme) -> androidx.compose.material3.ColorScheme>>()

    /** 宿主内置槽位名（新增加一个就是加一行常量，扩展侧用字符串引用）。 */
    object Slot {
        const val EXTENSIONS_PAGE_TOP = "extensions.page.top"
        const val HOME_CARD = "home.card"
        const val SETTINGS_FUNCTION = "settings.function"
        const val WEBUI_OVERLAY = "webui.overlay"
    }

    fun registerSlot(extensionId: String, slot: String, op: SlotOp, content: SlotContent) {
        if (ExtensionSafetyGuard.isLatched()) return
        slots.getOrPut(slot) { CopyOnWriteArrayList() }.add(SlotEntry(extensionId, op, content))
    }

    /** 接管整屏（screenId 由宿主定义，白名单见调用点）。 */
    fun overrideScreen(extensionId: String, screenId: String, content: SlotContent) {
        if (ExtensionSafetyGuard.isLatched()) return
        overrides.getOrPut(screenId) { CopyOnWriteArrayList() }.add(content)
    }

    /** 拦截主题色板。 */
    fun interceptPalette(extensionId: String, transform: (androidx.compose.material3.ColorScheme) -> androidx.compose.material3.ColorScheme) {
        if (ExtensionSafetyGuard.isLatched()) return
        palette.add(extensionId to transform)
    }

    /** 取某个槽位的全部内容（按注册顺序）。安全模式返回空。 */
    fun slotEntries(slot: String): List<SlotEntry> =
        if (ExtensionSafetyGuard.isLatched()) emptyList() else (slots[slot]?.toList() ?: emptyList())

    /** 该界面是否被扩展接管（返回第一个接管者）。 */
    fun screenOverride(screenId: String): SlotContent? =
        if (ExtensionSafetyGuard.isLatched()) null else overrides[screenId]?.firstOrNull()

    /** 依次过一遍色板拦截器。 */
    fun applyPalette(base: androidx.compose.material3.ColorScheme): androidx.compose.material3.ColorScheme {
        if (ExtensionSafetyGuard.isLatched()) return base
        var out = base
        for ((id, transform) in palette) {
            out = runCatching { transform(out) }.getOrElse {
                me.bmax.apatch.dsh.DshRuntime.appendLog("[ext:" + id + "] 色板拦截抛异常（已跳过）")
                out
            }
        }
        return out
    }

    fun unregisterAll(extensionId: String) {
        for (list in slots.values) list.removeIf { it.extensionId == extensionId }
        for (list in overrides.values) {
            // overrides 存的是纯 lambda，没有 id：由调用方在卸载时整组清（见 ExtensionEngine）
            runCatching { if (list.isEmpty()) list.clear() }
        }
        palette.removeIf { it.first == extensionId }
    }

    fun clearAll() {
        slots.clear(); overrides.clear(); palette.clear()
    }
}
