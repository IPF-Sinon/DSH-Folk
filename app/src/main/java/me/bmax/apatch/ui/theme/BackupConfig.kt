package me.bmax.apatch.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import me.bmax.apatch.APApplication

object BackupConfig {
    private const val PREF_KEY_BACKUP_ENABLED = "backup_enabled"
    private const val PREF_KEY_WEBDAV_URL = "webdav_url"
    private const val PREF_KEY_WEBDAV_USERNAME = "webdav_username"
    private const val PREF_KEY_WEBDAV_PASSWORD = "webdav_password"
    private const val PREF_KEY_WEBDAV_PATH = "webdav_path"

    var isBackupEnabled by mutableStateOf(false)
    var webdavUrl by mutableStateOf("")
    var webdavUsername by mutableStateOf("")
    var webdavPassword by mutableStateOf("")
    var webdavPath by mutableStateOf("/")

    init {
        load(APApplication.sharedPreferences)
    }

    private fun load(prefs: android.content.SharedPreferences) {
        isBackupEnabled = prefs.getBoolean(PREF_KEY_BACKUP_ENABLED, false)
        webdavUrl = prefs.getString(PREF_KEY_WEBDAV_URL, "") ?: ""
        webdavUsername = prefs.getString(PREF_KEY_WEBDAV_USERNAME, "") ?: ""
        webdavPassword = prefs.getString(PREF_KEY_WEBDAV_PASSWORD, "") ?: ""
        webdavPath = prefs.getString(PREF_KEY_WEBDAV_PATH, "/") ?: "/"
    }

    fun save(context: Context) {
        val prefs = APApplication.sharedPreferences
        prefs.edit().apply {
            putBoolean(PREF_KEY_BACKUP_ENABLED, isBackupEnabled)
            putString(PREF_KEY_WEBDAV_URL, webdavUrl)
            putString(PREF_KEY_WEBDAV_USERNAME, webdavUsername)
            putString(PREF_KEY_WEBDAV_PASSWORD, webdavPassword)
            putString(PREF_KEY_WEBDAV_PATH, webdavPath)
            apply()
        }
    }
}
