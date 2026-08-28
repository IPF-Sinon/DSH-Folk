package me.bmax.apatch.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.bmax.apatch.apApp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BackupLogManager {
    private const val LOG_FILE_NAME = "backup_log.log"
    private val logFile: File by lazy { File(apApp.filesDir, LOG_FILE_NAME) }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    suspend fun log(message: String) {
        withContext(Dispatchers.IO) {
            try {
                val timestamp = dateFormat.format(Date())
                val logEntry = "[$timestamp] $message\n"
                logFile.appendText(logEntry)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun readLogs(): String {
        return withContext(Dispatchers.IO) {
            try {
                if (logFile.exists()) {
                    logFile.readText()
                } else {
                    ""
                }
            } catch (e: Exception) {
                "Error reading logs: ${e.message}"
            }
        }
    }

    suspend fun clearLogs() {
        withContext(Dispatchers.IO) {
            try {
                if (logFile.exists()) {
                    logFile.writeText("")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
