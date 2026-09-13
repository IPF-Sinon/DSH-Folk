package me.bmax.apatch.util

import android.content.Context
import android.content.pm.PackageInfo
import android.util.Log
import androidx.webkit.WebViewCompat
import me.bmax.apatch.dsh.DshEnv

/**
 * 旧 WebView 内核的 JS 兼容垫片开关。
 *
 * ## 为什么需要开关
 *
 * WebView 是可独立升级的组件，系统版本高**不代表**内核新：实测有 Android 15（SDK 35）
 * 设备装着 Chromium 110 的 WebView。dsh 前端用到的几个 API 都比 110 晚：
 *
 * | API | 需要 | 用在哪 |
 * |---|---|---|
 * | `AbortSignal.any` | Chrome 116 | 每个带 signal 的 RPC、`postJson` 超时合并 |
 * | `Promise.withResolvers` | Chrome 119 | cordis 的 `ctx.timeout()` / `ctx.interval()` |
 * | `crypto.randomUUID` | 任意版本，但**只在安全上下文提供** | 会话消息 id、附件草稿 |
 *
 * 但在内核够新的设备上注入是纯粹的多余动作：垫片本身是"只补缺的"写法，装上去不会改变
 * 行为，可它仍然会在每个文档开始前跑一遍脚本。所以默认按内核版本决定，只有真的旧才注入，
 * 而且**先问用户**——注入 JS 到页面里这件事应该由用户点头。
 *
 * ## 三种状态
 *
 * - [MODE_AUTO]（默认）：**按内核自动**。内核缺 API（[Kernel.needsShim]）时直接注入，
 *   随后只说明一次（见 [shouldNotice]）；内核够新则什么都不做。
 * - [MODE_ON]：始终注入。
 * - [MODE_OFF]：从不注入。
 *
 * 换句话说：新内核默认什么都不做，旧内核问一次，问过之后就照用户说的办。
 */
object DshWebCompat {

    const val MODE_AUTO = "auto"
    const val MODE_ON = "on"
    const val MODE_OFF = "off"

    /** 内核版本判定结果。 */
    data class Kernel(
        /** WebView 包名，读不到时为空。 */
        val packageName: String,
        /** 完整版本名，例如 `110.0.5481.154`；读不到时为空。 */
        val versionName: String,
        /** Chromium 主版本号；解析不出来时为 null。 */
        val majorVersion: Int?,
    ) {
        /**
         * 这个内核是否缺 dsh 前端要用的 API。
         *
         * 版本号读不出来时返回 false：宁可默认不注入，也不要在正常设备上凭猜测注入。
         * 真有问题的用户可以在设置里手动打开。
         */
        val needsShim: Boolean
            get() = majorVersion != null && majorVersion <= DshEnv.DSH_COMPAT_MIN_CHROMIUM

        /** 给界面显示的版本文本；读不到返回空串。 */
        val display: String get() = versionName
    }

    /** 读当前 WebView 内核信息。不会触发 WebView 加载。 */
    fun kernel(ctx: Context): Kernel {
        val info: PackageInfo? = runCatching { WebViewCompat.getCurrentWebViewPackage(ctx) }
            .getOrElse {
                Log.w(TAG, "getCurrentWebViewPackage failed", it)
                null
            }
        val versionName = info?.versionName.orEmpty()
        // 版本名形如 "110.0.5481.154"，主版本号是第一段
        val major = versionName.substringBefore('.').toIntOrNull()
        return Kernel(info?.packageName.orEmpty(), versionName, major)
    }

    /** 用户设定的模式。 */
    fun mode(ctx: Context): String =
        prefs(ctx).getString(DshEnv.KEY_WEBUI_COMPAT, MODE_AUTO) ?: MODE_AUTO

    fun setMode(ctx: Context, mode: String) {
        prefs(ctx).edit().putString(DshEnv.KEY_WEBUI_COMPAT, mode).apply()
    }

    /**
     * 这次打开 WebUI 要不要注入垫片。
     *
     * - [MODE_ON]：注入。
     * - [MODE_AUTO]（默认）：**内核缺 API 就注入**（[Kernel.needsShim]）。
     *   不再先问：缺 `Iterator` 这类全局时整页会变成 "Failed to load plugins"，
     *   用户连设置都进不去，问了也没机会答（1.9.2 真机实测）。
     * - [MODE_OFF]：从不注入 —— 这是用户的最终决定权，提示框里的「关闭兼容模式」
     *   就是把它落成 off。
     */
    fun shouldInject(ctx: Context, kernel: Kernel = kernel(ctx)): Boolean =
        when (mode(ctx)) {
            MODE_ON -> true
            MODE_OFF -> false
            else -> kernel.needsShim
        }

    /**
     * 要不要弹那次**说明**（不是询问）对话框。
     *
     * 只在「自动注入过、且还没说明过」时为真；说明一次就落盘
     * （[DshEnv.KEY_WEBUI_COMPAT_NOTICED]），同一台设备不再打扰。
     * 内核够新时不需要垫片，也就没有说明。
     */
    fun shouldNotice(ctx: Context, kernel: Kernel = kernel(ctx)): Boolean =
        shouldInject(ctx, kernel) &&
            !prefs(ctx).getBoolean(DshEnv.KEY_WEBUI_COMPAT_NOTICED, false)

    /** 记下「兼容模式说明已弹过」。 */
    fun markNoticed(ctx: Context) {
        prefs(ctx).edit().putBoolean(DshEnv.KEY_WEBUI_COMPAT_NOTICED, true).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)

    private const val TAG = "DshWebCompat"
}
