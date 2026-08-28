package me.bmax.apatch.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.bmax.apatch.BuildConfig
import org.json.JSONObject

/**
 * 应用自身的更新检查。
 *
 * FolkPatch 原来查的是它自己的服务端（folk.mysqil.com/api/version，返回裸 versionCode）。
 * DSH-Folk 没有服务端，改为直接读 GitHub 的 latest release：
 *  - tag 形如 `v0.1.0` / `0.1.0`，与 `versionName` 比较；
 *  - 版本号按点分段做数值比较，避免 "0.10.0" < "0.9.0" 这类字符串比较错误；
 *  - 匿名 GitHub API 限流 60/h，所以沿用 FolkApiClient 的 TTL 缓存。
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val LATEST_API_URL =
        "https://api.github.com/repos/IPF-Sinon/DSH-Folk/releases/latest"
    private const val RELEASES_URL = "https://github.com/IPF-Sinon/DSH-Folk/releases"

    suspend fun checkUpdate(): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = FolkApiClient.fetchJson(
                LATEST_API_URL,
                ttlMs = 30 * 60 * 1000L,
                maxRetries = 1,
                forceRefresh = true,
            ).getOrNull() ?: return@withContext false

            val tag = JSONObject(body).optString("tag_name").trim()
            if (tag.isEmpty()) {
                Log.w(TAG, "latest release has no tag_name")
                return@withContext false
            }

            Log.d(TAG, "remote=$tag local=${BuildConfig.VERSION_NAME}")
            // 用 DshPluginRepo 那套 semver 比较：预发布标识必须参与，否则装着
            // v0.1.0-rc.1 的用户看不到 v0.1.0 正式版的更新
            me.bmax.apatch.dsh.compareVersions(tag, BuildConfig.VERSION_NAME) > 0
        } catch (e: Exception) {
            Log.e(TAG, "Check update failed", e)
            false
        }
    }

    fun openUpdateUrl(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open releases page", e)
        }
    }
}
