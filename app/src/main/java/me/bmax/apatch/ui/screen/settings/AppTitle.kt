package me.bmax.apatch.ui.screen.settings

import androidx.annotation.StringRes
import me.bmax.apatch.R

/**
 * 「应用名称」设置（`app_title`）的可选值与映射。
 *
 * 这个键有三处读它：常规页的当前值、首页顶栏标题、以及选择对话框。以前三处各写一份
 * `when`，14 个选项意味着同一张表抄了三遍 —— 加一个选项要改三处，漏一处就出现
 * 「设置里选了、标题上没变」。
 *
 * 选项本身也收窄成三个（DSH-Folk / 自定义 / DeepSeek Harness）：其余那些是 FolkPatch
 * 时代的内核补丁项目名，DSH-Folk 身上没有任何对应物。存量用户选的旧值由 [normalize]
 * 统一折成默认项，不会出现「当前值不在列表里」的空勾选状态。
 */
object AppTitle {
    /** 默认标题：资源串里是 `DSH-Folk`。 */
    const val DEFAULT = "dsh"

    /** 用户自定义名称，实际文本存在 `custom_app_title`。 */
    const val CUSTOM = "custom"

    const val DEEPSEEK_HARNESS = "deepseek_harness"

    /** 合法键集合。不在其中的（旧版本留下的 fpatch、kernelpatch…）一律按 [DEFAULT] 处理。 */
    val KEYS = setOf(DEFAULT, CUSTOM, DEEPSEEK_HARNESS)

    /** 把存下来的键折成合法值。 */
    fun normalize(stored: String?): String = if (stored != null && stored in KEYS) stored else DEFAULT

    /**
     * 标题资源串。
     *
     * [CUSTOM] 也返回资源串（用于无法读取自定义文本处的内容描述），界面显示自定义名称时
     * 用 `custom_app_title` 而不是这个串。第三项复用 `dsh_app_title`（值就是
     * `DeepSeek Harness`，translatable=false）：同一个名字没必要存两份。
     */
    @StringRes
    fun labelRes(key: String): Int = when (normalize(key)) {
        CUSTOM -> R.string.app_title_custom
        DEEPSEEK_HARNESS -> R.string.dsh_app_title
        else -> R.string.app_title_dsh
    }
}
