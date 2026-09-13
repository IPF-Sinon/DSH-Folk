package me.bmax.apatch.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import me.bmax.apatch.APApplication
import me.bmax.apatch.dsh.DshConfigBackup

object BackupConfig {
    private const val PREF_KEY_BACKUP_ENABLED = "backup_enabled"
    private const val PREF_KEY_WEBDAV_URL = "webdav_url"
    private const val PREF_KEY_WEBDAV_USERNAME = "webdav_username"
    private const val PREF_KEY_WEBDAV_PASSWORD = "webdav_password"
    private const val PREF_KEY_WEBDAV_PATH = "webdav_path"
    private const val PREF_KEY_IMPORT_STRATEGY = "import_strategy"

    var isBackupEnabled by mutableStateOf(false)
    var webdavUrl by mutableStateOf("")
    var webdavUsername by mutableStateOf("")
    var webdavPassword by mutableStateOf("")
    var webdavPath by mutableStateOf("/")

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
        isBackupEnabled = prefs.getBoolean(PREF_KEY_BACKUP_ENABLED, false)
        webdavUrl = prefs.getString(PREF_KEY_WEBDAV_URL, "") ?: ""
        webdavUsername = prefs.getString(PREF_KEY_WEBDAV_USERNAME, "") ?: ""
        webdavPassword = prefs.getString(PREF_KEY_WEBDAV_PASSWORD, "") ?: ""
        webdavPath = prefs.getString(PREF_KEY_WEBDAV_PATH, "/") ?: "/"
        importStrategy = prefs.getString(PREF_KEY_IMPORT_STRATEGY, DshConfigBackup.STRATEGY_MERGE)
            ?: DshConfigBackup.STRATEGY_MERGE
    }

    fun save(context: Context) {
        val prefs = APApplication.sharedPreferences
        prefs.edit().apply {
            putBoolean(PREF_KEY_BACKUP_ENABLED, isBackupEnabled)
            putString(PREF_KEY_WEBDAV_URL, webdavUrl)
            putString(PREF_KEY_WEBDAV_USERNAME, webdavUsername)
            putString(PREF_KEY_WEBDAV_PASSWORD, webdavPassword)
            putString(PREF_KEY_WEBDAV_PATH, webdavPath)
            putString(PREF_KEY_IMPORT_STRATEGY, importStrategy)
            apply()
        }
    }
}
