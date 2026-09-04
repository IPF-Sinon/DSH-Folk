package me.bmax.apatch.util

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale

/**
 * 在**非 Activity** 的 Context 上按「应用内语言」取字符串。
 *
 * 为什么需要这一层：`AppCompatDelegate.setApplicationLocales()` 在 API 33 以下**只**
 * 改 Activity 的 Configuration —— AppCompatDelegateImpl.attachBaseContext2 的注释写得
 * 很直白：「Don't modify the configuration of the Application context」。于是
 * Application / Service 这类 Context 上的 `getString()` 拿到的是**系统语言**，不是
 * 用户在应用里选的语言。表现出来就是：界面已经是英文，通知栏、启动日志、前台服务
 * 文案却还是系统的中文（或反过来）。API 33+ 由 LocaleManager 落到整个进程，没这问题，
 * 但 minSdk 是 26，不能只照顾新系统。
 *
 * 另一半原因是本项目的风味语言（`values-mgl` / `values-zh-rAT` 等）只能通过应用内
 * 语言选择进入 —— 它们**永远**不是系统语言。不走这一层，那几套皮在通知与日志里
 * 一行都不会出现。
 *
 * 用法：凡是在 Application / Service / 单例里取面向用户的字符串，都用
 * [localized] 或 [Context.appString]，不要直接 `ctx.getString()`。Composable 里的
 * `stringResource()` 不受影响（那是 Activity 的 Context）。
 */
object LocaleCtx {

    /** 上次解析出的语言标签，用来判断缓存还新鲜不新鲜。 */
    @Volatile
    private var cachedTags: String? = null

    /**
     * 缓存的包装 Context。
     *
     * **只缓存 application context**：[showToast] 之类的调用方会传进 Activity，
     * 而把 Activity 派生出的 Context 存进 object 的字段就等于泄漏那个 Activity
     * （连带它的整棵 View 树）。Activity 的调用按次现建，反正它们都是低频路径。
     */
    @Volatile
    private var cachedCtx: Context? = null

    /**
     * 把 [base] 包成一份「按应用内语言」解析资源的 Context。
     *
     * 没有设置应用内语言（跟随系统）时原样返回 [base]：那种情况下系统语言就是对的，
     * 多包一层只会多一份 Resources。
     */
    fun localized(base: Context): Context {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return base
        val tags = locales.toLanguageTags()
        val cacheable = base === base.applicationContext

        // 缓存命中要同时满足「语言没变」——语言一变就必须重建，否则切了语言之后
        // 通知与日志会继续用旧语言，而这正是本类要修的毛病。
        if (cacheable) cachedCtx?.let { if (cachedTags == tags) return it }

        val built = runCatching {
            val config = Configuration(base.resources.configuration)
            // setLocales 而不是 setLocale：保留整条回退链（pt-BR → pt → en），
            // 只塞第一个会让缺翻译的键跳过中间语言直接落到 values/。
            val unwrapped = locales.unwrap()
            if (unwrapped is LocaleList) {
                config.setLocales(unwrapped)
            } else {
                locales[0]?.let { config.setLocale(it) }
            }
            base.createConfigurationContext(config)
        }.getOrNull() ?: return base

        if (cacheable) {
            cachedTags = tags
            cachedCtx = built
        }
        return built
    }

    /** 当前生效的应用内语言标签；跟随系统时返回空串。诊断用（写进 bugreport）。 */
    fun appLocaleTags(): String = AppCompatDelegate.getApplicationLocales().toLanguageTags()

    /**
     * 当前生效的语言，用于 [android.icu.text.ListFormatter] 这类要显式 Locale 的 API。
     *
     * 跟随系统时取系统默认值。
     */
    fun currentLocale(): Locale =
        AppCompatDelegate.getApplicationLocales()[0] ?: Locale.getDefault()
}

/** [LocaleCtx.localized] 的便捷形式：按应用内语言取一条字符串。 */
fun Context.appString(resId: Int, vararg args: Any): String =
    LocaleCtx.localized(this).getString(resId, *args)
