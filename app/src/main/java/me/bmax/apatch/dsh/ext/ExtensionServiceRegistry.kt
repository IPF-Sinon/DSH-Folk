package me.bmax.apatch.dsh.ext

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * 可插拔 SPI 总线：扩展可以用自己的实现**整块替换**宿主的某个子系统。
 *
 * 与 [ExtensionHookRegistry] 的分工：Hook 是「在宿主流程中间插一刀」，SPI 是「整个换掉」。
 * 宿主侧取值一律走 [resolve]（传默认实现），这样没扩展时行为零变化。
 *
 * 安全模式（[ExtensionSafetyGuard]）下一律返回默认实现 —— 熔断之后不能还有扩展在跑。
 */
object ExtensionServiceRegistry {

    private val providers = ConcurrentHashMap<Class<*>, Pair<String, Any>>()

    /** 注册（同类型后注册的覆盖先注册的：由启用顺序决定，行为可预期）。 */
    fun <T : Any> register(extensionId: String, type: Class<T>, implementation: T) {
        if (ExtensionSafetyGuard.isLatched()) return
        providers[type] = extensionId to implementation
    }

    /**
     * 取值：有扩展注册就返回它的实现，否则返回 [fallback]。
     *
     * 类型不匹配时也退回 fallback（扩展写错类型不该让宿主崩）。
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> resolve(type: Class<T>, fallback: () -> T): T {
        if (ExtensionSafetyGuard.isLatched()) return fallback()
        val entry = providers[type] ?: return fallback()
        return (entry.second as? T) ?: fallback()
    }

    /** 撤掉某个扩展注册的全部实现（停用 / 卸载 / onLoad 失败时调用）。 */
    fun unregisterAll(extensionId: String) {
        providers.entries.removeIf { it.value.first == extensionId }
    }

    fun clearAll() {
        providers.clear()
    }

    /** 当前是否有扩展替换了该类型（界面上可以据此显示「已被扩展接管」）。 */
    fun providerIdOf(type: Class<*>): String? = providers[type]?.first

    /** 便捷：从 Context 取（本对象无状态，参数只为调用方便）。 */
    fun safeMode(ctx: Context): Boolean = ExtensionSafetyGuard.isSafeMode(ctx)
}
