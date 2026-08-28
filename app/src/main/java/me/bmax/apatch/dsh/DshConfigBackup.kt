package me.bmax.apatch.dsh

import android.content.Context
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.util.getSafeDownloadsDir
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

    /**
     * 默认导出的分区。
     *
     * 与插件 defaultIncluded=true 的集合一致，另外**显式**加上 pluginFiles
     * （插件侧默认关，但手机迁移时插件自己的配置文件该跟着走）；
     * 只有 sessions 不导 —— 会话记录体积能到几百 MB。
     */
    val DEFAULT_SECTIONS = listOf(
        "settings", "ui", "providers", "plugins", "mcp", "prompts",
        "skills", "agentPresets", "agentInstructions", "workspaces",
        "pluginFiles", "credentialsStatus", "self",
    )

    /**
     * 手机上备份文件的落地目录。
     *
     * 尽量放公共 Download（用户能用文件管理器拷走），但分区存储下没有「所有文件」
     * 权限时那里写不进去，[getSafeDownloadsDir] 会退回应用专属外部目录。
     */
    fun backupDir(ctx: Context): File =
        File(getSafeDownloadsDir(ctx), "DSH-Folk/ConfigBackups")

    data class Status(
        val ready: Boolean,
        val pluginVersion: String = "",
        val dshVersion: String = "",
        val error: String = "",
    )

    /** 插件在不在、能不能用。DSH 没起来或插件没装都会落到 ready=false。 */
    suspend fun status(ctx: Context): Status = withContext(Dispatchers.IO) {
        val r = request("GET", "/status", null)
        if (r == null) return@withContext Status(false, error = ctx.getString(R.string.dsh_bk_not_running))
        val o = runCatching { JSONObject(r) }.getOrNull()
            ?: return@withContext Status(false, error = ctx.getString(R.string.dsh_bk_bad_json))
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
        /** 实际导出的分区数（插件 report.included.size）。 */
        val sections: Int = 0,
        /** 整包是否加密（提供了密码即为 true）。 */
        val encrypted: Boolean = false,
    )

    /**
     * 导出配置并把 ZIP 拉到 [backupDir]。
     *
     * @param sections 要导出的分区；空则用 [DEFAULT_SECTIONS]
     * @param password 非空则整包 AES-256-GCM 加密（只在内存里传给插件，本地不留）
     */
    suspend fun export(
        ctx: Context,
        sections: List<String> = emptyList(),
        password: String = "",
    ): ExportResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("includeSecrets", false)
            put("only", JSONArray(sections.ifEmpty { DEFAULT_SECTIONS }))
            if (password.isNotEmpty()) put("password", password)
        }
        val raw = request("POST", "/export", body.toString())
            ?: return@withContext ExportResult(false, message = ctx.getString(R.string.dsh_bk_export_req_failed))
        val o = runCatching { JSONObject(raw) }.getOrNull()
            ?: return@withContext ExportResult(
            false,
            message = ctx.getString(R.string.dsh_bk_export_bad_json, raw.take(200)),
        )
        val err = o.optString("error")
        if (err.isNotEmpty()) return@withContext ExportResult(false, message = err)
        val zipPath = o.optString("zipPath")
        if (zipPath.isEmpty()) {
            return@withContext ExportResult(false, message = ctx.getString(R.string.dsh_bk_export_no_path))
        }

        val dir = backupDir(ctx)
        if (!dir.exists() && !dir.mkdirs()) {
            return@withContext ExportResult(
                false,
                message = ctx.getString(R.string.dsh_bk_mkdir_failed, dir.absolutePath),
            )
        }
        val dest = File(dir, zipPath.substringAfterLast('/'))
        val bytes = download(zipPath, dest)
        if (bytes <= 0) return@withContext ExportResult(false, message = ctx.getString(R.string.dsh_bk_download_failed))

        // report / manifest 里有真正落盘的分区与加密状态，比我们请求的 only 更权威
        val report = o.optJSONObject("report")
        val sections = report?.optJSONArray("included")?.length() ?: 0
        val security = report?.optJSONObject("security")
        val encrypted = security?.optBoolean("encrypted", false) ?: password.isNotEmpty()
        // 我们始终传 includeSecrets=false，所以 containsSecrets 为真是异常信号：
        // 包里带了真凭据，不能当普通文件随手转发。宁可多一句提示。
        val containsSecrets = security?.optBoolean("containsSecrets", false) ?: false
        val warnings = report?.optJSONArray("warnings")
        val warnText = buildString {
            if (containsSecrets) append("\n! ").append(ctx.getString(R.string.dsh_bk_contains_secrets))
            if (warnings != null) {
                for (i in 0 until warnings.length()) {
                    val w = warnings.optString(i)
                    if (w.isNotEmpty()) append("\n! ").append(w)
                }
            }
        }
        ExportResult(
            ok = true,
            file = dest,
            sizeBytes = bytes,
            message = buildString {
                append(ctx.getString(R.string.dsh_bk_exported, dest.name))
                if (sections > 0) {
                    append("（").append(ctx.getString(R.string.dsh_bk_exported_sections, sections)).append("）")
                }
                if (encrypted) append("，").append(ctx.getString(R.string.dsh_bk_exported_encrypted))
                append(warnText)
            },
            sections = sections,
            encrypted = encrypted,
        )
    }

    /** 运行时 exports 目录里的一个备份文件。 */
    data class RemoteBackup(
        val name: String,
        val sizeBytes: Long = 0,
        val mtimeMs: Long = 0,
        val note: String = "",
    )

    /**
     * 备份文件列表（插件侧 exports 目录，不含手机本地已拷出的副本）。
     *
     * 插件返回 BackupFileMeta：name / path / sizeBytes / mtimeMs / source / note，
     * 已按 mtime 倒序。这些备份在容器里，用文件管理器看不到，所以要能在 App 里列出来。
     */
    suspend fun listRemoteBackups(): List<RemoteBackup> = withContext(Dispatchers.IO) {
        val raw = request("GET", "/backup-files", null, timeoutMs = 30_000)
            ?: return@withContext emptyList()
        val arr = runCatching { JSONObject(raw).optJSONArray("files") }.getOrNull()
            ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            when (val v = arr.opt(i)) {
                is JSONObject -> v.optString("name").ifEmpty { null }?.let { n ->
                    RemoteBackup(
                        name = n,
                        sizeBytes = v.optLong("sizeBytes", 0L),
                        mtimeMs = v.optLong("mtimeMs", 0L),
                        note = v.optString("note"),
                    )
                }
                is String -> RemoteBackup(v)
                else -> null
            }
        }
    }

    data class ImportResult(val ok: Boolean, val message: String, val detail: String = "")

    /**
     * 插件 ImportResult 的字段名（src/core/types.ts ImportResult）：
     * ok / executed[]{itemId,status,message,skippedByUser} / needsRestart /
     * missingSecrets[] / warnings[] / rollback{full,restored,failed[]} / snapshotId /
     * skippedTombstoned[]{kind,id,adapter}。
     * 注意**不是** items —— 按 items 解析会永远得到「导入完成：0 项」。
     */
    private const val KEY_EXECUTED = "executed"

    /**
     * 导入一个导出 ZIP：upload → analyze → plan → execute。
     *
     * @param strategy 冲突策略：merge（保守，冲突保留）/ replace / skipExisting
     * @param password 加密备份的解锁密码
     */
    suspend fun import(
        ctx: Context,
        zip: File,
        strategy: String = "merge",
        password: String = "",
    ): ImportResult = withContext(Dispatchers.IO) {
        val up = upload(zip) ?: return@withContext ImportResult(false, ctx.getString(R.string.dsh_bk_upload_failed))
        val upObj = runCatching { JSONObject(up) }.getOrNull()
            ?: return@withContext ImportResult(false, ctx.getString(R.string.dsh_bk_upload_bad_json))
        var zipPath = upObj.optString("zipPath")
        if (zipPath.isEmpty()) {
            return@withContext ImportResult(false, upObj.optString("error").ifEmpty { ctx.getString(R.string.dsh_bk_upload_no_path) })
        }

        // 整体加密备份必须先解锁成明文 ZIP，否则 analyze 读不出 manifest
        if (upObj.optString("containerType") == "encrypted") {
            if (password.isEmpty()) return@withContext ImportResult(false, ctx.getString(R.string.dsh_bk_need_password))
            val dec = request(
                "POST", "/decrypt-archive",
                JSONObject().put("zipPath", zipPath).put("password", password).toString(),
            ) ?: return@withContext ImportResult(false, ctx.getString(R.string.dsh_bk_unlock_failed))
            val decObj = runCatching { JSONObject(dec) }.getOrNull()
                ?: return@withContext ImportResult(false, ctx.getString(R.string.dsh_bk_unlock_bad_json))
            val newPath = decObj.optString("zipPath")
            if (newPath.isEmpty()) {
                return@withContext ImportResult(false, decObj.optString("error").ifEmpty { ctx.getString(R.string.dsh_bk_bad_password) })
            }
            zipPath = newPath
        }

        val analyze = request("POST", "/analyze", JSONObject().put("zipPath", zipPath).toString())
            ?: return@withContext ImportResult(false, ctx.getString(R.string.dsh_bk_analyze_failed))
        val analyzeObj = runCatching { JSONObject(analyze) }.getOrNull()
            ?: return@withContext ImportResult(false, ctx.getString(R.string.dsh_bk_analyze_bad_json))
        val analyzeErr = analyzeObj.optString("error")
        if (analyzeErr.isNotEmpty()) return@withContext ImportResult(false, analyzeErr)
        // ImportAnalysis.valid / compatibility 才是「这个包能不能导」的判断依据。
        // 只看顶层 error 是不够的：分析本身成功（HTTP 200、无 error）但 valid=false
        // 时原来照样往下走 plan/execute，等于拿一个已知不合法的包去写配置。
        if (!analyzeObj.optBoolean("valid", true)) {
            val errs = analyzeObj.optJSONArray("errors")
            val detail = buildString {
                for (i in 0 until (errs?.length() ?: 0)) {
                    val e = errs?.optString(i) ?: continue
                    if (e.isNotEmpty()) append("✗ ").append(e).append('\n')
                }
            }
            return@withContext ImportResult(false, ctx.getString(R.string.dsh_bk_invalid_archive), detail)
        }
        if (analyzeObj.optString("compatibility") == "unsupported") {
            return@withContext ImportResult(false, ctx.getString(R.string.dsh_bk_incompatible))
        }

        val decisions = JSONObject().apply {
            put("strategy", strategy)
            put("resolutions", JSONObject())
            put("pathMappings", JSONArray())
        }
        val plan = request(
            "POST", "/plan",
            JSONObject().put("zipPath", zipPath).put("decisions", decisions).toString(),
        ) ?: return@withContext ImportResult(false, ctx.getString(R.string.dsh_bk_plan_failed))
        val planObj = runCatching { JSONObject(plan) }.getOrNull()
            ?: return@withContext ImportResult(false, ctx.getString(R.string.dsh_bk_plan_bad_json))
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
        ) ?: return@withContext ImportResult(false, ctx.getString(R.string.dsh_bk_exec_failed))
        val execObj = runCatching { JSONObject(exec) }.getOrNull()
            ?: return@withContext ImportResult(false, ctx.getString(R.string.dsh_bk_exec_bad_json))
        val execErr = execObj.optString("error")
        if (execErr.isNotEmpty()) return@withContext ImportResult(false, execErr)

        val executed = execObj.optJSONArray(KEY_EXECUTED)
        val total = executed?.length() ?: 0
        var failed = 0
        var skipped = 0
        var warned = 0
        val notes = StringBuilder()
        for (i in 0 until total) {
            val item = executed?.optJSONObject(i) ?: continue
            val id = item.optString("itemId")
            val note = item.optString("message")
            when (item.optString("status")) {
                "failed" -> {
                    failed++
                    notes.append("✗ ").append(id)
                    if (note.isNotEmpty()) notes.append(' ').append(note)
                    notes.append('\n')
                }
                "warning" -> {
                    warned++
                    notes.append("! ").append(id)
                    if (note.isNotEmpty()) notes.append(' ').append(note)
                    notes.append('\n')
                }
                "skipped" -> skipped++
            }
        }
        // 插件顶层 warnings / missingSecrets 与逐项结果同样重要：
        // 缺凭据的项会「成功」但运行时用不了，不列出来用户根本不知道要补什么
        for (key in listOf("warnings", "missingSecrets")) {
            val arr = execObj.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val v = arr.optString(i)
                if (v.isEmpty()) continue
                notes.append(if (key == "warnings") "! " else "? ").append(v)
                notes.append('\n')
            }
        }
        // 被删除墓碑挡掉的条目：状态是「成功」但东西没进来，不说用户会以为导入了
        val tombstoned = execObj.optJSONArray("skippedTombstoned")
        val tombstonedCount = tombstoned?.length() ?: 0
        for (i in 0 until tombstonedCount) {
            val t = tombstoned?.optJSONObject(i) ?: continue
            notes.append("⊘ ").append(t.optString("id"))
            t.optString("adapter").takeIf { it.isNotEmpty() }?.let { notes.append(" (").append(it).append(")") }
            notes.append('\n')
        }
        // 回滚发生说明这次导入整体没落地，必须显式说出来
        val rollback = execObj.optJSONObject("rollback")
        if (rollback != null) {
            notes.append("↩ ").append(
                ctx.getString(
                    if (rollback.optBoolean("full")) R.string.dsh_bk_rolled_back_full
                    else R.string.dsh_bk_rolled_back_partial
                )
            )
            notes.append('\n')
            // rollback.failed[]{item,reason,manualHint}：回滚都失败了的项处于半写入状态，
            // 只说「已部分回滚」等于让用户自己去猜哪儿坏了。manualHint 是插件给的补救指引。
            val rbFailed = rollback.optJSONArray("failed")
            for (i in 0 until (rbFailed?.length() ?: 0)) {
                val f = rbFailed?.optJSONObject(i) ?: continue
                notes.append("✗ ")
                    .append(ctx.getString(R.string.dsh_bk_rollback_failed, f.optString("item")))
                f.optString("reason").takeIf { it.isNotEmpty() }?.let { notes.append("：").append(it) }
                f.optString("manualHint").takeIf { it.isNotEmpty() }?.let { notes.append(" → ").append(it) }
                notes.append('\n')
            }
        }
        val needsRestart = execObj.optBoolean("needsRestart", planObj.optBoolean("needsRestart", false))
        val ok = execObj.optBoolean("ok", failed == 0) && rollback == null
        val head = buildString {
            append(ctx.getString(if (ok) R.string.dsh_bk_import_done else R.string.dsh_bk_import_incomplete))
            append(ctx.getString(R.string.dsh_bk_items, total))
            if (failed > 0) append(ctx.getString(R.string.dsh_bk_items_failed, failed))
            if (warned > 0) append(ctx.getString(R.string.dsh_bk_items_warned, warned))
            if (skipped > 0) append(ctx.getString(R.string.dsh_bk_items_skipped, skipped))
            if (tombstonedCount > 0) {
                append(ctx.getString(R.string.dsh_bk_items_tombstoned, tombstonedCount))
            }
            if (needsRestart) append(ctx.getString(R.string.dsh_bk_needs_restart))
            execObj.optString("snapshotId").takeIf { it.isNotEmpty() }
                ?.let { append(ctx.getString(R.string.dsh_bk_snapshot, it)) }
        }
        ImportResult(ok, head, notes.toString())
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
