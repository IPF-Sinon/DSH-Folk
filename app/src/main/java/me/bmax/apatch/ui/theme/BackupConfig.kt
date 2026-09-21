package me.bmax.apatch.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import me.bmax.apatch.APApplication
import me.bmax.apatch.dsh.DshConfigBackup

/**
 * 本地备份/导入的偏好。
 *
 * ## 云备份配置从 1.9.2.5 起不再存在这里
 *
 * WebDAV 云备份整个搬进了 `dsh-folk-cloud` 插件：地址/用户名/口令/远端目录/档位/触发方式
 * 只存插件一份（口令走 DSH 凭据），App 通过 [me.bmax.apatch.dsh.DshCloudBackup] 读写。
 * 所以这里原来的 `webdav_*` 四个字段与 `isBackupEnabled` 开关都删了 —— App 不再自己传 zip，
 * 也就没有本机 webdav 偏好可存。历史遗留的 `webdav_*` prefs 键留在盘上无害（不再读），
 * 迁移时也被 [me.bmax.apatch.dsh.DshAppData] 的 `SKIP_PREFIXES` 一并跳过。
 *
 * 本对象现在只剩「本地导入」的一个偏好：冲突策略。
 */
object BackupConfig {
    private const val PREF_KEY_IMPORT_STRATEGY = "import_strategy"

    /**
     * 导入时的冲突策略：merge / replace / skipExisting。
     *
     * 默认 merge（插件的保守默认）。以前这里写死 merge、界面上没得选 —— 于是「恢复备份」实际是
     * 「把备份里缺的补上」，与用户心里那句「回到备份当时的状态」不是一回事。
     */
    var importStrategy by mutableStateOf(DshConfigBackup.STRATEGY_MERGE)

    init {
        load(APApplication.sharedPreferences)
    }

    private fun load(prefs: android.content.SharedPreferences) {
        importStrategy = prefs.getString(PREF_KEY_IMPORT_STRATEGY, DshConfigBackup.STRATEGY_MERGE)
            ?: DshConfigBackup.STRATEGY_MERGE
    }

    fun save(context: Context) {
        val prefs = APApplication.sharedPreferences
        prefs.edit().apply {
            putString(PREF_KEY_IMPORT_STRATEGY, importStrategy)
            apply()
        }
    }
}
