package me.bmax.apatch.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.bmax.apatch.BuildConfig
import me.bmax.apatch.dsh.DshSource
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
        /** APK 资产的下载地址；空表示这次没找到可直装的包，UI 应退回浏览器。 */
        val apkUrl: String = "",
        val apkName: String = "",
        val apkSize: Long = 0,
        /** 期望的 sha256（来自同名 .sha256 资产）；空表示无法校验。 */
        val sha256: String = "",
        /** release 正文，给对话框显示更新内容。 */
        val notes: String = "",
        val failure: String? = null,
    ) {
        /** 能不能走应用内更新：要有包，也要有校验值 —— 不校验就装是不可接受的。 */
        val canInstallInApp: Boolean get() = apkUrl.isNotEmpty() && sha256.isNotEmpty()
    }

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

                val release = runCatching { newestVersionRelease(body) }.getOrNull()
                val tag = release?.optString("tag_name")?.trim().orEmpty()
                if (tag.isEmpty()) {
                    Log.i(TAG, "no version-shaped tag from $base$path")
                    continue
                }

                Log.d(TAG, "remote=$tag local=${BuildConfig.VERSION_NAME} via $base")
                // 用 DshPluginRepo 那套 semver 比较：预发布标识必须参与，否则装着
                // v1.6-rc.1 的用户看不到 v1.6 正式版的更新
                val newer = me.bmax.apatch.dsh.compareVersions(tag, BuildConfig.VERSION_NAME) > 0
                if (!newer) return@withContext Status(hasUpdate = false, latestTag = tag)

                val asset = pickApkAsset(release!!)
                return@withContext Status(
                    hasUpdate = true,
                    latestTag = tag,
                    apkUrl = asset?.url.orEmpty(),
                    apkName = asset?.name.orEmpty(),
                    apkSize = asset?.size ?: 0L,
                    sha256 = asset?.let { fetchSha256(it.shaUrl) }.orEmpty(),
                    notes = release.optString("body").trim(),
                )
            }
        }

        // 走到这里 = 每条路都没拿到可用的版本 tag
        Status(hasUpdate = false, failure = lastError ?: "no release found")
    }

    private data class ApkAsset(
        val name: String,
        val url: String,
        val size: Long,
        /** 同名 + `.sha256` 的资产地址；空 = 这个 release 没带校验文件。 */
        val shaUrl: String,
    )

    /**
     * 从 release 的 assets 里挑 APK，并配对它的 `.sha256`。
     *
     * 严格按「同名 + .sha256」配对，不去猜别的命名：配错了校验值就等于没校验，
     * 而校验失败会阻止安装 —— 宁可退回浏览器下载。
     */
    private fun pickApkAsset(release: JSONObject): ApkAsset? {
        val assets = release.optJSONArray("assets") ?: return null
        var apk: JSONObject? = null
        val byName = HashMap<String, JSONObject>()
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            byName[name] = a
            if (apk == null && name.endsWith(".apk", ignoreCase = true)) apk = a
        }
        val a = apk ?: return null
        val name = a.optString("name")
        return ApkAsset(
            name = name,
            url = a.optString("browser_download_url"),
            size = a.optLong("size", 0L),
            shaUrl = byName["$name.sha256"]?.optString("browser_download_url").orEmpty(),
        )
    }

    /**
     * 拉 `.sha256` 文件并取出十六进制摘要。
     *
     * 内容形如 `<hex>  <filename>`（sha256sum 的输出），所以取第一段。
     * 不是 64 位十六进制就返回空 —— 宁可让 UI 退回浏览器，也不拿一个可疑的
     * 期望值去比对（那等于没校验）。
     *
     * 直连 `github.com/releases/download/…` 会 302 到
     * `release-assets.githubusercontent.com`（Azure blob），国内直连经常超时；
     * 而 APK 下载那边（[AppUpdater]）走的是 gh-proxy 镜像。校验值必须跟着走
     * 同一批镜像，否则「包下得来、校验值拿不到」照样被 [Status.canInstallInApp]
     * 判成不能应用内更新。每个候选只试一次（串行、命中即返回），控制总超时。
     */
    private suspend fun fetchSha256(url: String): String {
        if (url.isEmpty()) return ""
        val candidates = buildList {
            add(url)
            if (url.startsWith("https://github.com/")) {
                add(DshSource.proxyPrefix(DshSource.SOURCE_GHPROXY_CF) + url)
                add(DshSource.proxyPrefix(DshSource.SOURCE_GHPROXY_AXISNOW) + url)
            }
        }.distinct()
        for (c in candidates) {
            val body = FolkApiClient.fetchJson(c, ttlMs = 30 * 60 * 1000L, maxRetries = 0)
                .getOrNull().orEmpty()
            val hex = body.trim().substringBefore(' ').trim()
            if (hex.length == 64 && hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                return hex.lowercase()
            }
        }
        return ""
    }

    /**
     * 从 latest 对象或 releases 数组里挑出最新的**版本形** release。
     *
     * 数组按发布时间倒序，但不能只取第一个：`runtime-latest` 也在同一个列表里。
     */
    private fun newestVersionRelease(body: String): JSONObject? {
        val trimmed = body.trimStart()
        if (trimmed.startsWith("[")) {
            val arr = JSONArray(trimmed)
            var best: JSONObject? = null
            var bestTag = ""
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optBoolean("draft")) continue
                val tag = o.optString("tag_name").trim()
                if (!VERSION_TAG.matches(tag)) continue
                if (bestTag.isEmpty() || me.bmax.apatch.dsh.compareVersions(tag, bestTag) > 0) {
                    best = o
                    bestTag = tag
                }
            }
            return best
        }
        val o = JSONObject(trimmed)
        return if (VERSION_TAG.matches(o.optString("tag_name").trim())) o else null
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
