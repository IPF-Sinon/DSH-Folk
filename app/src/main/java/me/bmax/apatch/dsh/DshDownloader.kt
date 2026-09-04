package me.bmax.apatch.dsh

import android.util.Log
import me.bmax.apatch.R
import me.bmax.apatch.util.appString
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 带断点续传的单文件下载器。
 *
 * 从 [DshRuntime.downloadFile] 原样抽出来的：运行时 rootfs（150 MB）和应用自身的
 * APK 更新包面对的是同一件事 —— 手机网络上断一次很常见，而 GitHub Release 与两个
 * gh-proxy 都支持 Range（实测返回 206 + content-range）。抽出来是为了让 APK 更新
 * 复用这套续传，而不是再写一个半成品下载器。
 *
 * 进度/日志通过回调交出去，所以这里不依赖任何 UI 或 DshRuntime 的状态。
 */
object DshDownloader {
    private const val TAG = "DshDownloader"

    /**
     * 取本地化字符串。
     *
     * 本类刻意不依赖 UI 与 DshRuntime 的状态，也就没有 Context 形参，所以走全局
     * apApp；用 appString 而不是 getString，因为应用内语言在 API 33 以下不会作用到
     * Application 的 Context（那时它解析的是系统语言）。
     */
    private fun str(resId: Int, vararg args: Any): String =
        runCatching { me.bmax.apatch.apApp.appString(resId, *args) }.getOrDefault("")

    /** 太小的残片不值得续传（可能是上次刚建好文件就断了），直接重下。 */
    private const val MIN_RESUME_BYTES = 1L * 1024 * 1024

    /** 下载进度。[contentLength] 为 0 表示服务端没给长度，百分比不可用。 */
    data class Progress(
        val bytesDone: Long,
        val contentLength: Long,
        val speedBytesPerSec: Long,
    ) {
        val fraction: Float
            get() = if (contentLength > 0) {
                (bytesDone.toDouble() / contentLength).coerceIn(0.0, 1.0).toFloat()
            } else {
                0f
            }

        val percent: Int get() = (fraction * 100).toInt()
    }

    /**
     * 下载 [url] 到 [target]，可续传。
     *
     * @param expectedSize 已知的最终大小（0 = 未知，退回 Content-Length）
     * @param onLog 面向用户的一行日志（续传、异常、不完整等）
     * @param onProgress 每约 512 KB 或收尾时回调一次
     */
    fun download(
        url: String,
        target: File,
        expectedSize: Long = 0L,
        onLog: (String) -> Unit = {},
        onProgress: (Progress) -> Unit = {},
    ): Boolean {
        var conn: HttpURLConnection? = null
        var input: InputStream? = null
        var out: OutputStream? = null
        var ok = false
        return try {
            val have = if (target.isFile && target.length() > MIN_RESUME_BYTES) target.length() else 0L
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            if (conn.responseCode !in 200..299) return false
            // 206 才是真的续传；服务端忽略 Range 返回 200 时必须从头写
            val resumed = have > 0 && conn.responseCode == 206
            if (have > 0 && !resumed) onLog("> " + str(R.string.dsh_log_resume_unsupported))
            if (resumed) onLog("> " + str(R.string.dsh_log_resume, have / 1024 / 1024))
            val contentLength = if (expectedSize > 0) expectedSize
                else conn.contentLengthLong.let { if (it > 0 && resumed) it + have else it }
            target.parentFile?.mkdirs()
            // stream 是非空局部量：循环里写它，out 只留给 finally 关句柄。
            // （out 是 OutputStream? 且循环内会被置空，直接用它写会丢智能转换）
            val stream = BufferedOutputStream(FileOutputStream(target, resumed))
            out = stream
            input = conn.inputStream
            val buf = ByteArray(64 * 1024)
            var total = if (resumed) have else 0L
            var lastUpdate = total
            var speedBps = 0L
            var lastSpeedAt = System.currentTimeMillis()
            var lastSpeedTotal = total
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                stream.write(buf, 0, n)
                total += n
                val now = System.currentTimeMillis()
                if (now - lastSpeedAt >= 500) {
                    val dt = (now - lastSpeedAt) / 1000.0
                    if (dt > 0.0) speedBps = ((total - lastSpeedTotal) / dt).toLong()
                    lastSpeedAt = now
                    lastSpeedTotal = total
                }
                if (total - lastUpdate > 512 * 1024 || (contentLength > 0 && total >= contentLength)) {
                    lastUpdate = total
                    onProgress(Progress(total, contentLength, speedBps))
                }
                // 超出预期大小：文件已经错了，删掉再报失败 —— 留着的话下次续传会
                // 从一个比目标还长的偏移接着请求，服务端只会回 416。
                if (contentLength > 0 && total > contentLength) {
                    onLog("! " + str(R.string.dsh_log_download_oversize, total, contentLength))
                    // 先关流再删：否则 finally 里的 close 会把缓冲区刷回一个刚被删掉的路径
                    runCatching { stream.close() }
                    out = null
                    runCatching { target.delete() }
                    return false
                }
            }
            if (contentLength > 0 && total != contentLength) {
                onLog("! " + str(R.string.dsh_log_download_incomplete, contentLength, total))
                return false
            }
            ok = true
            true
        } catch (e: Exception) {
            onLog(
                "! " + str(
                    R.string.dsh_log_download_error,
                    "${e.javaClass.simpleName}: ${e.message}",
                )
            )
            Log.w(TAG, "download failed: $url", e)
            false
        } finally {
            runCatching { input?.close() }
            runCatching { out?.close() }
            runCatching { conn?.disconnect() }
            // 失败时保留已下载部分供下次续传；只有明显不可续（大小超出预期）时才删，
            // 那种情况由上面的 return false 之前就地处理。
            if (!ok && target.length() < MIN_RESUME_BYTES) runCatching { target.delete() }
        }
    }

    /** 文件的 SHA-256 小写十六进制；读不动返回空串。 */
    fun sha256(file: File): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrElse { "" }

    /** 人类可读的速度。 */
    fun formatSpeed(bytesPerSec: Long): String = when {
        bytesPerSec <= 0 -> "—"
        bytesPerSec < 1024 -> "$bytesPerSec B/s"
        bytesPerSec < 1024 * 1024 -> "${bytesPerSec / 1024} KB/s"
        else -> String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024))
    }
}
