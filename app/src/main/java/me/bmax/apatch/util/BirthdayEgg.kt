package me.bmax.apatch.util

import android.content.Context
import java.time.LocalDate
import me.bmax.apatch.APApplication

/**
 * 10 月 8 日的彩蛋：那天第一次打开应用时弹一次「今天是开发者生日」。
 *
 * ## 「第一次」按当天算，不是按装机算
 *
 * 判据挂在**日期**上：10.8 当天首次打开弹一次，当天再打开不弹；记的是**年份**，所以明年
 * 10.8 会再弹一次。反过来的写法（"装完之后的第一次启动"）只有恰好在 10.8 装机的人看得到，
 * 这个彩蛋等于不存在 —— 而它本来就是为了给老用户一个一年一次的小惊喜。
 *
 * ## 为什么判据要拆成纯函数
 *
 * [isBirthday] / [shouldShow] 不碰任何 UI，[shouldShow] 的「今天」也能传进来：日期判断里
 * 混进 prefs 读写最容易出「同一天弹两次」或者「弹过就再也不弹」这种**只有等到明年 10.8
 * 才发现**的错。拆开之后 [tools/check-changelog.js] 能把月份/日期/年份比较这三条逐条钉住。
 */
object BirthdayEgg {
    /** 开发者生日：10 月 8 日（写死在这里，别散到界面里）。 */
    const val MONTH = 10
    const val DAY = 8

    /** 存「哪一年已经弹过」。存年份而不是布尔：布尔会让彩蛋一辈子只弹一次。 */
    const val KEY_SHOWN_YEAR = "birthday_egg_shown_year"

    /** 今天是不是那个日子。 */
    fun isBirthday(today: LocalDate): Boolean =
        today.monthValue == MONTH && today.dayOfMonth == DAY

    /**
     * 现在该不该弹：今天正是 10.8，而且**今年**还没弹过。
     *
     * @param today 只给测试/门禁用；正常调用走 [LocalDate.now]（设备本地时区 —— 用户看到的
     *   日历日期就是他手机上的日期，用 UTC 会在某些时区差一天）。
     */
    fun shouldShow(ctx: Context, today: LocalDate = LocalDate.now()): Boolean =
        isBirthday(today) && prefs().getInt(KEY_SHOWN_YEAR, 0) != today.year

    /** 弹过就记下年份（同一天再打开不会再弹，明年 10.8 还会弹）。 */
    fun markShown(today: LocalDate = LocalDate.now()) {
        prefs().edit().putInt(KEY_SHOWN_YEAR, today.year).apply()
    }

    private fun prefs() = APApplication.sharedPreferences
}
