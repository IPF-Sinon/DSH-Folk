package me.bmax.apatch.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import me.bmax.apatch.APApplication
import me.bmax.apatch.R

object LauncherIconUtils {
    private const val MAIN_ACTIVITY = ".ui.MainActivityDefault"
    private const val ALIAS_ACTIVITY = ".ui.MainActivityAlias"
    private const val ALIAS_ACTIVITY_SU = ".ui.MainActivityAliasSu"
    private const val ALIAS_ACTIVITY_ALT_SU = ".ui.MainActivityAliasAltSu"

    /**
     * 「设置 → 常规 → 备用图标」的 prefs 键。
     *
     * 这个开关的取值在两处要被读到：这里（决定启用哪个 launcher 别名）和关于页（决定显示哪个
     * 图标）。字面量只能有一份 —— 分散写的话改了一个另一个不会跟着变，而症状正是「关于页的图标
     * 和主屏上的不是同一个设计」这种要用户截图来对比才发现的偏差。
     */
    const val KEY_USE_ALT_ICON = "use_alt_icon"

    /** 当前用的是不是备用图标。关于页跟着它走。 */
    fun usesAltIcon(): Boolean = APApplication.sharedPreferences.getBoolean(KEY_USE_ALT_ICON, false)

    /**
     * 当前生效的启动器图标**前景**位图 —— 就是 `mipmap-anydpi-v26/ic_launcher*.xml` 里 `<foreground>`
     * 引的那一份。
     *
     * 关于页必须用它，而不是直接 `R.mipmap.ic_launcher`：后者在 API 26+ 解析到的是 adaptive-icon
     * XML，Compose 的 `painterResource` 画不了它（既不是位图也不是 vector，会抛 unsupported type）。
     * 前景位图自带那圈浅色圆角底，单独显示就是用户在主屏上看到的那个图标。
     */
    @DrawableRes
    fun currentIconForeground(): Int =
        if (usesAltIcon()) R.mipmap.ic_launcher_alt_foreground else R.mipmap.ic_launcher_foreground

    fun updateLauncherState(context: Context) {
        val prefs = APApplication.sharedPreferences
        val useAlt = prefs.getBoolean(KEY_USE_ALT_ICON, false)
        val appName = prefs.getString("desktop_app_name", "DSH-Folk")
        // 短名别名（AliasSu / AliasAltSu）的 label 是 @string/app_name_short
        val isSu = appName == "DSH"

        val pm = context.packageManager
        val basePackage = APApplication::class.java.`package`?.name ?: "me.bmax.apatch"
        
        val mainComponent = ComponentName(context.packageName, basePackage + MAIN_ACTIVITY)
        val aliasComponent = ComponentName(context.packageName, basePackage + ALIAS_ACTIVITY)
        val aliasSuComponent = ComponentName(context.packageName, basePackage + ALIAS_ACTIVITY_SU)
        val aliasAltSuComponent = ComponentName(context.packageName, basePackage + ALIAS_ACTIVITY_ALT_SU)


        val targetComponent = when {
            useAlt && isSu -> aliasAltSuComponent
            useAlt && !isSu -> aliasComponent
            !useAlt && isSu -> aliasSuComponent
            else -> mainComponent
        }

        val allComponents = listOf(mainComponent, aliasComponent, aliasSuComponent, aliasAltSuComponent)

        try {
            // Enable target
            pm.setComponentEnabledSetting(
                targetComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )

            // Disable others
            allComponents.filter { it != targetComponent }.forEach {
                pm.setComponentEnabledSetting(
                    it,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Deprecated but kept for compatibility if needed, redirects to updateLauncherState
    fun toggleLauncherIcon(context: Context, useAlt: Boolean) {
        updateLauncherState(context)
    }

    fun applySaved(context: Context) {
        updateLauncherState(context)
    }
}
