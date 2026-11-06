package me.bmax.apatch.dsh.ext

import android.content.Context

/**
 * [DshExtensionHost] 的宿主实现。
 *
 * 比接口多一样东西：[platform]（36 项宿主能力门面）。它**故意不放进 :ext-api** —— 那些门面
 * 直接暴露宿主内部类型，放进公共 API 等于把整个 app 钉成 API。
 * 所以：市场里的扩展只用接口；将来若要做「一等公民扩展」，再由宿主侧把它们编进 APK 并拿这个句柄。
 */
internal class ExtensionHostImpl(
    override val id: String,
    override val appContext: Context,
) : DshExtensionHost {

    override val fs: ExtensionFs by lazy { AppExtensionFs(appContext, id) }

    /** 只有 App 内扩展拿得到；第三方扩展编译时看不到这个属性。 */
    val platform: DshPlatform by lazy { DshPlatform(appContext, id) }

    override fun log(message: String) {
        ExtLog.log("[" + id + "] " + message)
    }
}
