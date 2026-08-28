package me.bmax.apatch.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException

object WebDavUtils {
    private val client = OkHttpClient()

    suspend fun testConnection(url: String, user: String, pass: String): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val credential = Credentials.basic(user, pass)
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", credential)
                    .method("PROPFIND", null) // WebDAV check
                    .header("Depth", "0")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful || response.code == 207) { // 207 Multi-Status is typical for PROPFIND
                         Result.success(true)
                    } else {
                         Result.failure(IOException("HTTP ${response.code}"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun uploadFile(baseUrl: String, user: String, pass: String, file: File, subDir: String, remoteFileName: String? = null): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                var cleanBaseUrl = if (baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl
            
                
                val parts = subDir.split("/").filter { it.isNotEmpty() }
                var currentPath = ""
                for (part in parts) {
                    currentPath = if (currentPath.isEmpty()) part else "$currentPath/$part"
                    createDir(cleanBaseUrl, user, pass, currentPath)
                }
                
                val fileName = remoteFileName ?: file.name
                val fullUrl = "$cleanBaseUrl/$currentPath/$fileName"
                BackupLogManager.log("Uploading to: $fullUrl")
                
                val credential = Credentials.basic(user, pass)
                val request = Request.Builder()
                    .url(fullUrl)
                    .header("Authorization", credential)
                    .put(file.asRequestBody("application/octet-stream".toMediaTypeOrNull()))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful || response.code == 201 || response.code == 204) {
                        BackupLogManager.log("Upload success: $fileName")
                        Result.success(true)
                    } else {
                        val msg = "Upload failed: ${response.code} - ${response.message}"
                        BackupLogManager.log(msg)
                        Result.failure(IOException(msg))
                    }
                }
            } catch (e: Exception) {
                BackupLogManager.log("Upload exception: ${e.message}")
                Result.failure(e)
            }
        }
    }
    
    private fun createDir(baseUrl: String, user: String, pass: String, path: String) {
        val fullUrl = "$baseUrl/$path"
        val credential = Credentials.basic(user, pass)
        
        // Check if exists
        val checkRequest = Request.Builder()
            .url(fullUrl)
            .header("Authorization", credential)
            .method("HEAD", null)
            .build()
            
        try {
            client.newCall(checkRequest).execute().use { response ->
                if (response.code == 404) {
                    // Create it
                    val mkcolRequest = Request.Builder()
                        .url(fullUrl)
                        .header("Authorization", credential)
                        .method("MKCOL", null)
                        .build()
                    client.newCall(mkcolRequest).execute().close()
                }
            }
        } catch (e: Exception) {
            // Ignore errors in directory creation, maybe it exists or we can't create it
        }
    }
}
