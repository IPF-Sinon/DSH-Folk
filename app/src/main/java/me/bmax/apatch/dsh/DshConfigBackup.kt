package me.bmax.apatch.dsh

import android.content.Context
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            ?: return@withContext ExportResult(false, message = "导出请求失败：DSH 未运行或插件未安装")
        val o = runCatching { JSONObject(raw) }.getOrNull()
            ?: return@withContext ExportResult(false, message = "导出返回无法解析：${raw.take(200)}")
        val err = o.optString("error")
        if (err.isNotEmpty()) return@withContext ExportResult(false, message = err)
        val zipPath = o.optString("zipPath")
        if (zipPath.isEmpty()) return@withContext ExportResult(false, message = "导出未返回文件路径")

        val dir = backupDir(ctx)
        if (!dir.exists() && !dir.mkdirs()) {
            return@withContext ExportResult(false, message = "无法创建目录 ${dir.absolutePath}")
        }
        val dest = File(dir, zipPath.substringAfterLast('/'))
        val bytes = download(zipPath, dest)
        if (bytes <= 0) return@withContext ExportResult(false, message = "下载导出文件失败")

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
            if (containsSecrets) append("\n! 备份中含有凭据明文，请勿分享此文件")
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
                append("已导出 ").append(dest.name)
                if (sections > 0) append("（").append(sections).append(" 个分区）")
                if (encrypted) append("，已加密")
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
            notes.append(if (rollback.optBoolean("full")) "↩ 已完整回滚" else "↩ 已部分回滚")
            notes.append('\n')
            // rollback.failed[]{item,reason,manualHint}：回滚都失败了的项处于半写入状态，
            // 只说「已部分回滚」等于让用户自己去猜哪儿坏了。manualHint 是插件给的补救指引。
            val rbFailed = rollback.optJSONArray("failed")
            for (i in 0 until (rbFailed?.length() ?: 0)) {
                val f = rbFailed?.optJSONObject(i) ?: continue
                notes.append("✗ 回滚失败 ").append(f.optString("item"))
                f.optString("reason").takeIf { it.isNotEmpty() }?.let { notes.append("：").append(it) }
                f.optString("manualHint").takeIf { it.isNotEmpty() }?.let { notes.append(" → ").append(it) }
                notes.append('\n')
            }
        }
        val needsRestart = execObj.optBoolean("needsRestart", planObj.optBoolean("needsRestart", false))
        val ok = execObj.optBoolean("ok", failed == 0) && rollback == null
        val head = buildString {
            append(if (ok) "导入完成：" else "导入未完成：")
            append("$total 项")
            if (failed > 0) append("，$failed 项失败")
            if (warned > 0) append("，$warned 项告警")
            if (skipped > 0) append("，$skipped 项跳过")
            if (tombstonedCount > 0) append("，$tombstonedCount 项被删除记录挡下")
            if (needsRestart) append("；需要重启 DSH 生效")
            execObj.optString("snapshotId").takeIf { it.isNotEmpty() }
                ?.let { append("；回滚快照 $it") }
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
