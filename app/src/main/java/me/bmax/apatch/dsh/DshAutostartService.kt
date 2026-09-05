package me.bmax.apatch.dsh

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍自启：一个**故意什么都不做**的无障碍服务。
 *
 * 它存在的唯一理由是 [onServiceConnected]：系统在开机后会主动 bind 已启用的无障碍服务，
 * 而且进程被杀之后还会重新 bind。这是唯一一条既不需要 root、又不受国产 ROM「自启动管理」
 * 白名单约束的启动路径 —— [Mode.RECEIVER][DshAutostart.Mode.RECEIVER] 那条广播在很多 ROM
 * 上根本不会送达。
 *
 * 这是在**借用**无障碍框架，不是在做无障碍功能，所以有意把它做到最小：
 *
 * - `accessibilityEventTypes` 设成 `typeWindowStateChanged` 而不是 `typeAllMask`。
 *   一个都不订阅在部分 ROM 上会被判成「无效服务」而不列出来，但订阅全部等于让系统把每一次
 *   界面变化都推给我们，白烧电。
 * - **没有** `canRetrieveWindowContent`。那一位才是「能读取你屏幕上的内容」的来源，
 *   而我们只需要「被 bind」这个事实，不需要任何事件内容。
 * - [onAccessibilityEvent] 是空的，[onInterrupt] 也是。
 *
 * 用户在系统里看到的授权文案由 ROM 决定，我们无法改写；能做的是在应用内把「为什么要这个」
 * 说清楚，并且让这一项默认关闭、可随时切走。
 */
class DshAutostartService : AccessibilityService() {

    /**
     * 系统 bind 成功。开机后这里是第一个能跑我们代码的地方。
     *
     * 不在 `onCreate` 里做：`onCreate` 早于 bind 完成，此时 `AccessibilityService` 的
     * 上下文还没就绪，而且被系统重新 bind（例如进程被杀后）也会重新走到这里 ——
     * 这正是我们要的「容器掉了还能回来」。
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "无障碍服务已连接，检查是否需要自启")
        DshAutostart.trigger(applicationContext, DshAutostart.Mode.ACCESSIBILITY)
    }

    /** 不处理任何事件：这个服务只借用「会被 bind」这一点。 */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    /** 同上，无事可中断。 */
    override fun onInterrupt() = Unit

    private companion object {
        const val TAG = "DSH-Folk-Autostart-A11y"
    }
}
