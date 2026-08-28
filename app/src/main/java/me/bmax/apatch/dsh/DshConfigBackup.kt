package me.bmax.apatch.dsh

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * DSH 配置备份 / 迁移。
 *
 * 格式**不是**自己发明的：直接走容器里 `dsh-config-manager` 插件的 loopback HTTP API，
 * 产出与桌面端完全一致的导出 ZIP（`manifest.json` + `config/` + `ai/providers.json` +
 * `plugins/plugins.json` + `mcp/servers.json` + `custom/` + `workspaces/` +
 * `integrity/checksums.json`），所以手机导出的备份能直接在电脑上导入，反之亦然。
 *
 * 为什么用 HTTP 而不是自己读文件：
 * - 设置值要经过 `settings.describe({redactSecrets:true})` 剥离凭据，这是插件里的逻辑，
 *   照抄一份必然与上游漂移；
 * - 导入涉及冲突分析 / 计划 / 回滚快照，重写一遍等于把插件在 Kotlin 里实现第二次。
 *
 * 安全：插件的路由有 loopback 守卫（remoteAddress 必须是 127.0.0.1 且 Host 必须是回环）。
 * 本应用直连 `http://127.0.0.1:<port>`，同机同回环，不带 Origin，恰好满足；这也意味着
 * **不需要**把端口暴露到局域网。凭据默认不导出（includeSecrets=false）。
 */
object DshConfigBackup {
    private const val BASE = "/api/dsh-config-manager"

    /** 默认导出的分区：与插件「推荐分区」一致，去掉体积巨大的 sessions。 */
    val DEFAULT_SECTIONS = listOf(
        "settings", "ui", "providers", "plugins", "mcp", "prompts",
        "skills", "agentPresets", "agentInstructions", "workspaces",
        "pluginFiles", "credentialsStatus", "self",
    )

    /** 手机上备份文件的落地目录（对用户可见，便于用文件管理器拷走）。 */
    fun backupDir(): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "DSH-Folk/ConfigBackups",
        )

    data class Status(
        val ready: Boolean,
        val pluginVersion: String = "",
        val dshVersion: String = "",
        val error: String = "",
    )

    /** 插件在不在、能不能用。DSH 没起来或插件没装都会落到 ready=false。 */
    suspend fun status(): Status = withContext(Dispatchers.IO) {
        val r = request("GET", "/status", null)
        if (r == null) return@withContext Status(false, error = "DSH 未运行或 dsh-config-manager 未安装")
        val o = runCatching { JSONObject(r) }.getOrNull()
            ?: return@withContext Status(false, error = "返回内容无法解析")
        Status(
            ready = o.optBoolean("ready", false),
            pluginVersion = o.optString("pluginVersion"),
            dshVersion = o.optString("dshVersion"),
        )
    }

    data class ExportResult(
        val ok: Boolean,
        val file: File? = null,
        val sizeBytes: Long = 0,
        val message: String = "",
    )

    /**
     * 导出配置并把 ZIP 拉到 [backupDir]。
     *
     * @param sections 要导出的分区；空则用 [DEFAULT_SECTIONS]
     * @param password 非空则整包 AES-256-GCM 加密（只在内存里传给插件，本地不留）
     */
    suspend fun export(
        sections: List<String> = emptyList(),
        password: String = "",
    ): ExportResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("includeSecrets", false)
            put("only", JSONArray(sections.ifEmpty { DEFAULT_SECTIONS }))
            if (password.isNotEmpty()) put("password", password)
        }
        val raw = request("POST", "/export", body.toString())
            ?: return@withContext ExportResult(false, message = "导出请求失败：DSH 未运行或插件未安装")
        val o = runCatching { JSONObject(raw) }.getOrNull()
            ?: return@withContext ExportResult(false, message = "导出返回无法解析：${raw.take(200)}")
        val err = o.optString("error")
        if (err.isNotEmpty()) return@withContext ExportResult(false, message = err)
        val zipPath = o.optString("zipPath")
        if (zipPath.isEmpty()) return@withContext ExportResult(false, message = "导出未返回文件路径")

        val dir = backupDir()
        if (!dir.exists() && !dir.mkdirs()) {
            return@withContext ExportResult(false, message = "无法创建目录 ${dir.absolutePath}")
        }
        val dest = File(dir, zipPath.substringAfterLast('/'))
        val bytes = download(zipPath, dest)
        if (bytes <= 0) return@withContext ExportResult(false, message = "下载导出文件失败")
        ExportResult(true, dest, bytes, "已导出 ${dest.name}")
    }

    /** 备份文件列表（插件侧 exports 目录，不含手机本地已拷出的副本）。 */
    suspend fun listRemoteBackups(): List<String> = withContext(Dispatchers.IO) {
        val raw = request("GET", "/backup-files", null) ?: return@withContext emptyList()
        val arr = runCatching { JSONObject(raw).optJSONArray("files") }.getOrNull()
            ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            when (val v = arr.opt(i)) {
                is JSONObject -> v.optString("name").ifEmpty { null }
                is String -> v
                else -> null
            }
        }
    }

    data class ImportResult(val ok: Boolean, val message: String, val detail: String = "")

    /**
     * 导入一个导出 ZIP：upload → analyze → plan → execute。
     *
     * @param strategy 冲突策略：merge（保守，冲突保留）/ replace / skipExisting
     * @param password 加密备份的解锁密码
     */
    suspend fun import(
        zip: File,
        strategy: String = "merge",
        password: String = "",
    ): ImportResult = withContext(Dispatchers.IO) {
        val up = upload(zip) ?: return@withContext ImportResult(false, "上传失败：DSH 未运行或插件未安装")
        val upObj = runCatching { JSONObject(up) }.getOrNull()
            ?: return@withContext ImportResult(false, "上传返回无法解析")
        var zipPath = upObj.optString("zipPath")
        if (zipPath.isEmpty()) {
            return@withContext ImportResult(false, upObj.optString("error").ifEmpty { "上传未返回路径" })
        }

        // 整体加密备份必须先解锁成明文 ZIP，否则 analyze 读不出 manifest
        if (upObj.optString("containerType") == "encrypted") {
            if (password.isEmpty()) return@withContext ImportResult(false, "这是加密备份，请填写备份密码")
            val dec = request(
                "POST", "/decrypt-archive",
                JSONObject().put("zipPath", zipPath).put("password", password).toString(),
            ) ?: return@withContext ImportResult(false, "解锁请求失败")
            val decObj = runCatching { JSONObject(dec) }.getOrNull()
                ?: return@withContext ImportResult(false, "解锁返回无法解析")
            val newPath = decObj.optString("zipPath")
            if (newPath.isEmpty()) {
                return@withContext ImportResult(false, decObj.optString("error").ifEmpty { "密码错误或备份已损坏" })
            }
            zipPath = newPath
        }

        val analyze = request("POST", "/analyze", JSONObject().put("zipPath", zipPath).toString())
            ?: return@withContext ImportResult(false, "分析请求失败")
        val analyzeObj = runCatching { JSONObject(analyze) }.getOrNull()
            ?: return@withContext ImportResult(false, "分析返回无法解析")
        val analyzeErr = analyzeObj.optString("error")
        if (analyzeErr.isNotEmpty()) return@withContext ImportResult(false, analyzeErr)

        val decisions = JSONObject().apply {
            put("strategy", strategy)
            put("resolutions", JSONObject())
            put("pathMappings", JSONArray())
        }
        val plan = request(
            "POST", "/plan",
            JSONObject().put("zipPath", zipPath).put("decisions", decisions).toString(),
        ) ?: return@withContext ImportResult(false, "生成计划失败")
        val planObj = runCatching { JSONObject(plan) }.getOrNull()
            ?: return@withContext ImportResult(false, "计划返回无法解析")
        val planErr = planObj.optString("error")
        if (planErr.isNotEmpty()) return@withContext ImportResult(false, planErr)

        val opts = JSONObject().apply {
            put("confirm", true)
            put("rollbackOnError", true)
            if (password.isNotEmpty()) put("decryptPassword", password)
        }
        val exec = request(
            "POST", "/execute",
            JSONObject().put("zipPath", zipPath).put("plan", planObj).put("opts", opts).toString(),
            timeoutMs = 900_000,
        ) ?: return@withContext ImportResult(false, "执行导入失败")
        val execObj = runCatching { JSONObject(exec) }.getOrNull()
            ?: return@withContext ImportResult(false, "导入返回无法解析")
        val execErr = execObj.optString("error")
        if (execErr.isNotEmpty()) return@withContext ImportResult(false, execErr)

        val items = execObj.optJSONArray("items")
        val total = items?.length() ?: 0
        var failed = 0
        val notes = StringBuilder()
        for (i in 0 until total) {
            val it = items?.optJSONObject(i) ?: continue
            val st = it.optString("status")
            if (st == "failed") {
                failed++
                notes.append("✗ ").append(it.optString("itemId")).append(' ')
                    .append(it.optString("message")).append('\n')
            }
        }
        val needsRestart = execObj.optBoolean("needsRestart", planObj.optBoolean("needsRestart", false))
        val head = buildString {
            append("导入完成：$total 项")
            if (failed > 0) append("，$failed 项失败")
            if (needsRestart) append("；需要重启 DSH 生效")
        }
        ImportResult(failed == 0, head, notes.toString())
    }

    /** 把 SAF 选中的文件先落到应用可控目录，再上传（ContentResolver 的 Uri 不能直接给 HTTP）。 */
    fun stage(ctx: Context, input: InputStream, name: String): File? = runCatching {
        val dir = File(ctx.cacheDir, "config-import").apply { mkdirs() }
        val f = File(dir, name.ifBlank { "backup.zip" })
        f.outputStream().use { input.copyTo(it) }
        f
    }.getOrNull()

    // ───────────────────────────── HTTP ─────────────────────────────

    private fun base(): String = "http://127.0.0.1:${DshRuntime.port()}$BASE"

    private fun request(
        method: String,
        path: String,
        body: String?,
        timeoutMs: Int = 300_000,
    ): String? = runCatching {
        val conn = URL(base() + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 8_000
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("Accept", "application/json")
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        }
        // 4xx/5xx 的响应体带 {"error": …}，比状态码有用，所以两路都读
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        stream?.bufferedReader()?.use { it.readText() } ?: ""
    }.getOrNull()

    private fun upload(zip: File): String? = runCatching {
        val name = java.net.URLEncoder.encode(zip.name, "UTF-8")
        val conn = URL("${base()}/upload?name=$name").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 8_000
        conn.readTimeout = 300_000
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(zip.length())
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.outputStream.use { out -> zip.inputStream().use { it.copyTo(out) } }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        stream?.bufferedReader()?.use { it.readText() } ?: ""
    }.getOrNull()

    private fun download(remotePath: String, dest: File): Long = runCatching {
        val q = java.net.URLEncoder.encode(remotePath, "UTF-8")
        val conn = URL("${base()}/download?path=$q").openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 300_000
        if (conn.responseCode !in 200..299) return@runCatching 0L
        dest.outputStream().use { out -> conn.inputStream.use { it.copyTo(out) } }
        dest.length()
    }.getOrDefault(0L)
}
