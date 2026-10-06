package me.bmax.apatch.dsh

import android.content.Context
import android.view.View
import android.view.Window
import androidx.core.content.edit

/**
 * 「无障碍别看本应用的内容」的档位（设置页：无障碍卡片里的三档选择）。
 *
 * ## 为什么要有
 *
 * a11y 读屏默认会把**我们自己的界面**也算进去（`DshA11y.pickRoot` 只跳自己那个
 * `TYPE_SYSTEM` 悬浮窗，本应用 `TYPE_APPLICATION` 的窗是刻意保留的 —— 用户常常正想让
 * agent 驱动这页的输入框）。但反过来也成立：目标在别的 App 上时，我们自己的窗会冒充
 * 活动窗、把 agent 引到错误的一棵树里。这个开关给用户一个"别看我们自己"的档位。
 *
 * ## 三档（默认 [MODE_AGENT]）
 *
 * - [MODE_OFF]：什么都不拦 —— agent 读得到、也能操作本应用自己的界面。
 * - [MODE_AGENT]：**只拦我们自己的 a11y 通道**（`/native/a11y/…`）：读 / 操作都跳过
 *   本应用自己的窗口；View 层不动，所以别的无障碍服务（TalkBack 等）完全不受影响。
 * - [MODE_ALL]：再叠一层**视图级**隐藏（`importantForAccessibility = noHideDescendants`），
 *   对**所有**无障碍服务生效。
 *
 * ## 为什么两档都只能说"尽量"
 *
 * - 视图级那层是给系统的**建议**：WebView 的虚拟子树、本应用弹窗的独立窗（decor 是另一棵
 *   树）、`AccessibilityNodeProvider` 都可能照旧报到；
 * - 通道级只覆盖 `/native/a11y/…`：`/native/a11y/screenshot`（读像素）、`shell` 里的
 *   `uiautomator`、`display`（把画面拖进容器）都不是它管的；
 * - 所以设置页的提示写的是「**不保证**完全拦截」，而不是"已拦截"。
 *
 * 落点：App 自己的每个窗（[MainActivity] / `DshWebUiActivity` / `DisplayMirror` 的悬浮窗）
 * 在展示前调 [applyToWindow] / [applyToView]；`DshA11y` 每读一次现查 [hidesAgent]，
 * 所以**改档位立刻生效**（不需要重启，也不需要重开页面）。
 */
internal object A11yOwn {

    /** 不拦：agent 照常读/操作本应用自己的界面。 */
    const val MODE_OFF = "off"

    /** 只拦我们自己的 a11y 通道（默认）。 */
    const val MODE_AGENT = "agent"

    /** 通道 + 视图级隐藏（对所有无障碍服务生效）。 */
    const val MODE_ALL = "all"

    /** 全部档位，按「拦得越来越多」排序 —— 界面按这个顺序渲染。 */
    val MODES = listOf(MODE_OFF, MODE_AGENT, MODE_ALL)

    /** 当前档位；prefs 里是脏值（旧版本/手改）时按默认 [MODE_AGENT] 算。 */
    fun mode(ctx: Context): String =
        prefs(ctx).getString(DshEnv.KEY_A11Y_OWN, MODE_AGENT)
            ?.takeIf { it in MODES }
            ?: MODE_AGENT

    fun setMode(ctx: Context, mode: String) {
        prefs(ctx).edit { putString(DshEnv.KEY_A11Y_OWN, mode) }
    }

    /** agent 的读/写要不要跳过本应用自己的窗口（[MODE_OFF] 之外都要）。 */
    fun hidesAgent(ctx: Context): Boolean = mode(ctx) != MODE_OFF

    /** 要不要连 View 层一起隐藏（只有 [MODE_ALL]）。 */
    fun hidesViews(ctx: Context): Boolean = mode(ctx) == MODE_ALL

    /**
     * 把一个窗口按当前档位设好（在窗展示前调；[MODE_ALL] 之外恢复默认）。
     *
     * `AUTO` 而不是"什么都不做"：用户从 all 调回 agent/off 时，上一轮设下的
     * `noHideDescendants` 留在 decor 上不会自己消失 —— 那会让"关掉了却还是读不到"。
     */
    fun applyToWindow(window: Window?) {
        window?.decorView?.let { applyToView(it) }
    }

    /** 同上，直接给一个 View（悬浮窗不是 Activity，只有一个 ComposeView）。 */
    fun applyToView(view: View?) {
        val v = view ?: return
        v.importantForAccessibility = if (hidesViews(v.context)) {
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        } else {
            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)
}
