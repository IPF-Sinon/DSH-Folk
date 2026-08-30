package me.bmax.apatch.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.bmax.apatch.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * 应用自身的更新检查。
 *
 * FolkPatch 原来查的是它自己的服务端（folk.mysqil.com/api/version，返回裸 versionCode）。
 * DSH-Folk 没有服务端，改为直接读 GitHub 的 latest release：
 *  - tag 形如 `v1.6` / `1.6`，与 `versionName` 比较；
 *  - 版本号比较走 [me.bmax.apatch.dsh.compareVersions]（含预发布标识）；
 *  - 匿名 GitHub API 限流 60/h，所以沿用 FolkApiClient 的 TTL 缓存。
 *
 * 必须先验 tag 长得像版本号：同一个仓库里还有 `runtime-latest` 这个滚动 tag（容器
 * 运行时的发布位），仓库里没有正式版本发布时 `releases/latest` 返回的就是它 ——
 * 而 compareVersions("runtime-latest", "1.6") > 0（首段不是数字，按字典序比），
 * 于是每次启动都弹「有新版本」。
 *
 * **结果必须区分「没有更新」与「查不到」**：原来 [checkUpdate] 一律返回 Boolean，
 * 任何失败（限流 403、断网、DNS 失败）都落成 false，界面报「您已是最新版本」——
 * 用户看到的是一句肯定的错误结论，而不是「检查失败」。匿名配额只有 60/h，而插件
 * 商店那边也在打 GitHub（star / 目录），配额烧完这条路就静默失效了。
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val LATEST_PATH = "/repos/IPF-Sinon/DSH-Folk/releases/latest"
    private const val LIST_PATH = "/repos/IPF-Sinon/DSH-Folk/releases?per_page=10"
    private const val API_BASE = "https://api.github.com"
    private const val RELEASES_URL = "https://github.com/IPF-Sinon/DSH-Folk/releases"

    /**
     * 直连之外的备用入口。
     *
     * 已实测 gh-proxy 能代理 api.github.com（返回同样的 JSON），而它不共享 GitHub
     * 对本机 IP 的匿名配额 —— 直连被限流时正好接得上。
     */
    private val API_MIRRORS = listOf(
        "https://v6.gh-proxy.org/https://api.github.com",
        "https://gh-proxy.com/https://api.github.com",
    )

    /** 版本 tag：可选 v 前缀 + 至少两段数字 + 可选预发布后缀。 */
    private val VERSION_TAG = Regex("""^[vV]?\d+(\.\d+)+([-+].*)?$""")

    /** 检查结果。[failure] 只在真的查不到时非空，不能与「已是最新」混为一谈。 */
    data class Status(
        val hasUpdate: Boolean,
        val latestTag: String = "",
        val failure: String? = null,
    )

    suspend fun check(): Status = withContext(Dispatchers.IO) {
        var lastError: String? = null

        for (base in listOf(API_BASE) + API_MIRRORS) {
            // latest 拿不到版本形 tag 时退到列表：latest 只认「最近发布的那个」，
            // 若它恰好是 runtime-latest（滚动 tag），列表里仍能挑出真正的版本
            for (path in listOf(LATEST_PATH, LIST_PATH)) {
                val body = FolkApiClient.fetchJson(
                    base + path,
                    ttlMs = 30 * 60 * 1000L,
                    maxRetries = 1,
                    forceRefresh = true,
                ).getOrElse { e ->
                    lastError = e.message ?: e.javaClass.simpleName
                    Log.w(TAG, "fetch failed: $base$path — $lastError")
                    null
                } ?: continue

                val tag = runCatching { newestVersionTag(body) }.getOrNull()
                if (tag.isNullOrEmpty()) {
                    Log.i(TAG, "no version-shaped tag from $base$path")
                    continue
                }

                Log.d(TAG, "remote=$tag local=${BuildConfig.VERSION_NAME} via $base")
                // 用 DshPluginRepo 那套 semver 比较：预发布标识必须参与，否则装着
                // v1.6-rc.1 的用户看不到 v1.6 正式版的更新
                val newer = me.bmax.apatch.dsh.compareVersions(tag, BuildConfig.VERSION_NAME) > 0
                return@withContext Status(hasUpdate = newer, latestTag = tag)
            }
        }

        // 走到这里 = 每条路都没拿到可用的版本 tag
        Status(hasUpdate = false, failure = lastError ?: "no release found")
    }

    /**
     * 从 latest 对象或 releases 数组里挑出最新的**版本形** tag。
     *
     * 数组按发布时间倒序，但不能只取第一个：`runtime-latest` 也在同一个列表里。
     */
    private fun newestVersionTag(body: String): String {
        val trimmed = body.trimStart()
        if (trimmed.startsWith("[")) {
            val arr = JSONArray(trimmed)
            var best = ""
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optBoolean("draft")) continue
                val tag = o.optString("tag_name").trim()
                if (!VERSION_TAG.matches(tag)) continue
                if (best.isEmpty() || me.bmax.apatch.dsh.compareVersions(tag, best) > 0) best = tag
            }
            return best
        }
        val tag = JSONObject(trimmed).optString("tag_name").trim()
        return if (VERSION_TAG.matches(tag)) tag else ""
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
