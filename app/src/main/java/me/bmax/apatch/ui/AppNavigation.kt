package me.bmax.apatch.ui

import android.app.Activity
import android.content.Context
import android.content.Intent

/**
 * 从**不在导航图里**的页面跳进某个目的地。
 *
 * 现实来源：DSH WebUI 那一页（[DshWebUiActivity]）与虚拟屏小窗都是独立的 Activity，它们没有
 * `DestinationsNavigator`，但用户在那儿时需要能一键跳到「用户脚本」这种页面。可用的通道只有
 * Intent，所以这里把「跳哪一页」放到一个 extra 里交给 [MainActivity]，由它在导航图上落地。
 *
 * ## 为什么传的是白名单常量而不是路由字符串
 *
 * [MainActivity] 是 exported 的（文件分享 / 打开都要走它），任何应用都能带 extra 启动它。
 * 收自由字符串就等于让外部决定我们落在哪一页 —— 现在只是"显示一个页面"，但这条通道没有再
 * 加一层判断的理由。要加新目标就在这里加一个常量，并在 [MainActivity] 的 `when` 里接上。
 */
object AppNavigation {
    const val EXTRA_SCREEN = "dsh_nav_screen"

    /** 用户脚本页（`UserscriptsScreenDestination`）。插件首页那一组之外的第二条入口。 */
    const val SCREEN_USERSCRIPTS = "userscripts"

    /**
     * 打开 [MainActivity] 并落到 [screen]。
     *
     * 调用方通常已经在栈里（WebUI 页），所以**不加** `NEW_TASK`：新页面压在它上面，返回键
     * 回到原来那一页。从非 Activity 上下文调用时才补上该标志 —— 否则系统会直接抛。
     */
    fun openScreen(context: Context, screen: String) {
        val intent = Intent(context, MainActivity::class.java).putExtra(EXTRA_SCREEN, screen)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** 把 [MainActivity] 收到的 extra 收敛成白名单里的一页；不认识的值返回 null。 */
    fun screenOf(intent: Intent?): String? = when (intent?.getStringExtra(EXTRA_SCREEN)) {
        SCREEN_USERSCRIPTS -> SCREEN_USERSCRIPTS
        else -> null
    }
}
