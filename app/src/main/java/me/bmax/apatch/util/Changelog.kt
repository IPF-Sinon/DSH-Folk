package me.bmax.apatch.util

import android.content.Context
import me.bmax.apatch.BuildConfig
import me.bmax.apatch.R

/**
 * 「本次更新」说明的数据与显示时机。
 *
 * ## 为什么更新内容是**本地**资源而不是 GitHub release 正文
 *
 * [me.bmax.apatch.ui.component.UpdateDialog] 显示的是 release 正文，它说的是「有一个新
 * 版本，它讲了这些」；这里要说的是「你**现在跑的**这一版改了什么」—— 用户此刻可能在
 * 飞机上、可能刚从别人那里拷来一个 APK。这必须离线可用，所以随包打进 res。
 *
 * ## 为什么版本号是 Kotlin 常量而不是字符串资源
 *
 * 它是一个版本号，不该被翻译；放进 `strings.xml` 会让 17 个语言目录各有一份可以互相
 * 矛盾的副本。放这里，[tools/check-changelog.js] 直接把它和 `build.gradle.kts` 里的
 * `getVersionName()` 对齐 —— 发版时改了版本号却忘了写更新说明，开发期就被拦住，而不是
 * 让用户看到「1.8.1 的新特性」下面列着 1.8.0 的内容。
 */
object Changelog {
    /**
     * 这份更新说明描述的版本。**改版本号时必须同时改它和 [R.array.changelog_items]。**
     *
     * 与 `BuildConfig.VERSION_NAME` 的主号比较（测试版是 `1.8.1-beta.7` 这种）：同一批
     * 测试版共用一份更新说明，按完整版本名比会让每个测试版都被判成「没有对应说明」。
     */
    const val VERSION = "1.8.1"

    /** 记「哪个版本的说明已经弹过」的 prefs 键。 */
    const val KEY_SHOWN_FOR = "changelog_shown_for"

    /** 当前版本的主号：去掉 `-beta.7` 这类预发布后缀。 */
    fun currentCoreVersion(): String = BuildConfig.VERSION_NAME.substringBefore('-')

    /** 更新条目；空行会被剔掉，方便在 XML 里留空占位。 */
    fun items(context: Context): List<String> =
        runCatching {
            context.resources.getStringArray(R.array.changelog_items)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())

    /**
     * 这一版的更新说明该不该弹。
     *
     * 四个条件都必须成立：
     * - 首启引导已经看过 —— 全新安装的人不需要「本次更新」，他要的是「这是什么应用」；
     *   两个对话框同时弹会叠在一起，而且后弹的那个把前一个的按钮盖住。
     * - [VERSION] 与当前版本主号一致：不一致说明发版时漏改了文案，宁可不显示也不能
     *   把上一版的内容配上新版本号 —— 那是一句自信的假话。
     * - 有内容可显示。
     * - 这一版还没弹过。
     */
    fun shouldShow(context: Context, welcomeShown: Boolean, shownFor: String?): Boolean =
        welcomeShown &&
            VERSION == currentCoreVersion() &&
            items(context).isNotEmpty() &&
            shownFor != VERSION
}
