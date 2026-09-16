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

    /** 超过它就把旧内容砍掉一半（导出/导入每一步都记账，日志会长得很快）。 */
    private const val MAX_BYTES = 512L * 1024L
    private val logFile: File by lazy { File(apApp.filesDir, LOG_FILE_NAME) }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /**
     * 记一笔。
     *
     * 导出/导入现在每一步都会写进来（大小、走的哪条加密路、校验结果、冲突数……），
     * 所以文件会长得快：超过 [MAX_BYTES] 就从**开头**砍掉一半，只保留最近的部分 ——
     * 这份日志的用途是「出了问题回看刚刚发生了什么」，旧记录没有价值，但不能让它无限长大。
     */
    suspend fun log(message: String) {
        withContext(Dispatchers.IO) {
            try {
                val timestamp = dateFormat.format(Date())
                val logEntry = "[$timestamp] $message\n"
                rotateIfTooBig()
                logFile.appendText(logEntry)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** 太大就砍掉前一半（保留最近的那一半，够回看最近的几次操作）。 */
    private fun rotateIfTooBig() {
        if (!logFile.isFile || logFile.length() <= MAX_BYTES) return
        val text = logFile.readText()
        val keep = text.takeLast((text.length / 2).coerceAtLeast(1))
        // 从换行处切开：不要留半行，否则日志读起来像坏了
        val cut = keep.indexOf('\n')
        logFile.writeText("[日志过大，已截断旧内容]\n" + if (cut >= 0) keep.substring(cut + 1) else keep)
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
