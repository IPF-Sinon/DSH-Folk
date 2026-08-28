package me.bmax.apatch.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream

object SafeUriResolver {

    private const val TAG = "SafeUriResolver"

    @Throws(FileNotFoundException::class)
    fun openInputStream(context: Context, uri: Uri): InputStream {
        try {
            val stream = context.contentResolver.openInputStream(uri)
            if (stream != null) return stream
        } catch (_: SecurityException) {
            Log.w(TAG, "SecurityException for $uri, trying fallback strategies")
        } catch (_: Exception) {
        }

        val filePath = extractFilePath(uri)
        if (filePath != null) {
            try {
                val file = File(filePath)
                if (file.exists()) {
                    Log.i(TAG, "Fallback: direct file access for $filePath")
                    return FileInputStream(file)
                }
            } catch (_: Exception) {
            }

            try {
                val stream = copyViaRoot(context, filePath)
                if (stream != null) {
                    Log.i(TAG, "Fallback: root shell copy for $filePath")
                    return stream
                }
            } catch (_: Exception) {
            }
        }

        if (uri.scheme == "file") {
            try {
                val path = uri.path
                if (path != null) {
                    val file = File(path)
                    if (file.exists()) return FileInputStream(file)
                }
            } catch (_: Exception) {
            }
        }

        throw FileNotFoundException("Cannot open input stream for URI: $uri")
    }

    fun extractFilePath(uri: Uri): String? {
        val path = uri.path ?: return null
        if (uri.scheme == "file") return path
        if (uri.scheme != "content") return null

        if (path.startsWith("/storage/") || path.startsWith("/sdcard/") ||
            path.startsWith("/data/") || path.startsWith("/system/")
        ) {
            return path
        }

        for (prefix in listOf(
            "storage/emulated/",
            "sdcard/",
            "data/",
            "mnt/"
        )) {
            val idx = path.indexOf(prefix)
            if (idx >= 0) {
                return "/" + path.substring(idx)
            }
        }

        return null
    }

    private fun copyViaRoot(context: Context, sourcePath: String): InputStream? {
        val cacheDir = context.cacheDir
        val tempFile = File(cacheDir, "safe_uri_${System.currentTimeMillis()}")
        try {
            val result = Shell.cmd("cp \"$sourcePath\" \"${tempFile.absolutePath}\"").exec()
            if (!result.isSuccess) {
                Shell.cmd("cat \"$sourcePath\" > \"${tempFile.absolutePath}\"").exec()
            }
            if (tempFile.exists() && tempFile.length() > 0) {
                tempFile.deleteOnExit()
                return tempFile.inputStream()
            }
            tempFile.delete()
        } catch (_: Exception) {
            tempFile.delete()
        }
        return null
    }
}
