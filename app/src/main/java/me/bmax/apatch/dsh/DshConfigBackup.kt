package me.bmax.apatch.dsh

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.util.BackupLogManager
import me.bmax.apatch.util.appString
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

    /** 导入冲突策略（与插件 /plan 的 decisions.strategy 取值一致）。 */
    const val STRATEGY_MERGE = "merge"
    const val STRATEGY_REPLACE = "replace"
    const val STRATEGY_SKIP_EXISTING = "skipExisting"


    /**
     * 默认导出的分区。
     *
     * 与插件 defaultIncluded=true 的集合一致，另外**显式**加上 pluginFiles
     * （插件侧默认关，但手机迁移时插件自己的配置文件该跟着走）。
     *
     * sessions 不在里面：它是逐会话文件复制，体积能到几百 MB，且含敏感信息。
     * 插件侧也 defaultIncluded=false —— 想要的话用户在页面上勾
     * 「包含会话数据」，走 [sections] 显式加进去。
     */
    val DEFAULT_SECTIONS = listOf(
        "settings", "ui", "providers", "plugins", "mcp", "prompts",
        "skills", "agentPresets", "agentInstructions", "workspaces",
        "pluginFiles", "credentialsStatus", "self",
    )

    /** 要导出给插件的 `only` 列表；[includeSessions] 为真时追加 sessions 分区。 */
    fun sections(includeSessions: Boolean): List<String> =
        if (includeSessions) DEFAULT_SECTIONS + "sessions" else DEFAULT_SECTIONS

    /** 备份落地的公共子目录（在 Download 下，用户用文件管理器就能看到）。 */
    const val PUBLIC_SUBDIR = "DSH-Folk"

    /**
     * 超过这个大小才走流式加密。
     *
     * 内存版（[DshBackupCrypto.encryptArchive]）每次导出前都会被 selfTest() 验一遍，
     * 流式那对函数以前从没被验证过 —— 现场那个 49 字节的空容器就是出自它。所以：
     * 能用内存版就用内存版（几十 MB 以内都没问题），只有真正的几百 MB 大包才走流式。
     */
    /** 插件放备份的目录（容器内绝对路径）。 */
    private const val DSH_BACKUP_EXPORTS_DIR = "/root/.dsh/dsh-config-manager/exports/"

    private const val IN_MEMORY_ENCRYPT_LIMIT = 16L * 1024 * 1024

    /**
     * 手机上备份文件的落地目录（尽力而为的兜底）。
     *
     * 首选走 MediaStore 直接写公共 `Download/DSH-Folk`（API 29+，免「所有文件」权限，
     * 见 [export]）。这个 File 只在 MediaStore 写不进去时兜底：SDK<30 或已授
     * 「所有文件」权限时返回真·公共 Download，否则 [getSafeDownloadsDir] 退回应用专属
     * 外部目录。
     */
    fun backupDir(ctx: Context): File =
        File(getSafeDownloadsDir(ctx), PUBLIC_SUBDIR)

    data class Status(
        val ready: Boolean,
        val pluginVersion: String = "",
        val dshVersion: String = "",
        val error: String = "",
    )

    /** 插件在不在、能不能用。DSH 没起来或插件没装都会落到 ready=false。 */
    suspend fun status(ctx: Context): Status = withContext(Dispatchers.IO) {
        val r = request("GET", "/status", null)
        if (r == null) return@withContext Status(false, error = ctx.appString(R.string.dsh_bk_not_running))
        val o = runCatching { JSONObject(r) }.getOrNull()
            ?: return@withContext Status(false, error = ctx.appString(R.string.dsh_bk_bad_json))
        // 插件/DSH 自己给的 error 必须带出来：以前只读 ready，于是「未授权」「DSH 没起来」
        // 这类原因全被界面兜成「插件缺失」，用户被指去重装一个明明装好的插件。
        val err = o.optString("error")
        Status(
            ready = o.optBoolean("ready", false),
            pluginVersion = o.optString("pluginVersion"),
            dshVersion = o.optString("dshVersion"),
            error = err,
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
        /** 给用户看的位置（如 Download/DSH-Folk/xxx.zip）；与 [file] 可能不是同一个文件。 */
        val location: String = "",
    )

    /**
     * 导出配置并把 ZIP 落到公共 Download/DSH-Folk（写不进才退 [backupDir]）。
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
            ?: return@withContext ExportResult(false, message = ctx.appString(R.string.dsh_bk_export_req_failed))
        val o = runCatching { JSONObject(raw) }.getOrNull()
            ?: return@withContext ExportResult(
            false,
            message = ctx.appString(R.string.dsh_bk_export_bad_json, raw.take(200)),
        )
        val err = o.optString("error")
        if (err.isNotEmpty()) return@withContext ExportResult(false, message = err)
        val zipPath = o.optString("zipPath")
        if (zipPath.isEmpty()) {
            return@withContext ExportResult(false, message = ctx.appString(R.string.dsh_bk_export_no_path))
        }
        val name = zipPath.substringAfterLast('/').ifBlank { "dsh-config-backup.zip" }

        // 先落到应用专属外部目录的暂存（始终可写，多大的会话都放得下），
        // 再复制进公共 Download/DSH-Folk。不直接往公共目录写：分区存储下
        // 没有「所有文件」权限就写不进去，而 MediaStore 那条路要先有完整字节流。
        val stage = File(ctx.getExternalFilesDir(null) ?: ctx.cacheDir, "config-backup").apply { mkdirs() }
        val tmp = File(stage, name)
        val bytes = download(zipPath, tmp)
        if (bytes <= 0) {
            tmp.delete()
            return@withContext ExportResult(false, message = ctx.appString(R.string.dsh_bk_download_failed))
        }
        // 复制进公共目录；返回给用户看的位置。失败退回应用专属目录（仍可导出，只是不好找）
        val (location, publicOk) = copyToPublic(ctx, tmp, name)

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
            if (!publicOk) append("\n! ").append(ctx.appString(R.string.dsh_bk_copy_failed))
            if (containsSecrets) append("\n! ").append(ctx.appString(R.string.dsh_bk_contains_secrets))
            if (warnings != null) {
                for (i in 0 until warnings.length()) {
                    val w = warnings.optString(i)
                    if (w.isNotEmpty()) append("\n! ").append(w)
                }
            }
        }
        ExportResult(
            ok = true,
            // WebDAV 上传等需要真实字节流的场合用暂存文件（公共目录里的那份是 MediaStore 项，
            // 不一定能当普通 File 打开）。它留在应用专属目录，由系统按需回收。
            file = tmp,
            sizeBytes = bytes,
            message = buildString {
                append(ctx.appString(R.string.dsh_bk_exported, name))
                if (sections > 0) {
                    append("（").append(ctx.appString(R.string.dsh_bk_exported_sections, sections)).append("）")
                }
                if (encrypted) append("，").append(ctx.appString(R.string.dsh_bk_exported_encrypted))
                append(warnText)
            },
            sections = sections,
            encrypted = encrypted,
            location = location,
        )
    }

    /**
     * 把导出的 ZIP 复制进用户能直接找到的地方，返回展示用的位置字符串。
     *
     * API 29+ 走 MediaStore.Downloads：分区存储下不需要「所有文件」权限就能写公共
     * Download/DSH-Folk，文件会出现在系统文件管理器的「下载」里。写不进才退回
     * [backupDir]（SDK<30 或有「所有文件」权限时是真公共目录，否则是应用专属目录）。
     */
    /**
     * 新导出：插件只负责把 DSH 分区打成**明文** ZIP，选中的会话、软件数据、凭据由我们在
     * 本地补进包里，整包加密最后也由我们做（见 [DshBackupArchive] 与 [DshBackupCrypto]）。
     *
     * 为什么绕这一圈：插件不能按数量筛会话、也不认识 App 自己的数据；而它一旦被要求加密，
     * 就直接产出最终容器 —— 我们就再也没有机会往包里放东西了。反过来做（插件出明文 →
     * 本地补包 → 本地加密）产出的包与插件自己的格式完全一致，所以插件内恢复、桌面端
     * dsh-config-manager 恢复都不受影响。
     *
     * 自检先行：只要路径上会用到密码，就先用**插件产出的向量**验证我们的 scrypt/GCM 实现；
     * 不过就拒绝导出。宁可这一次没有备份，也不要给用户一个连自己都打不开的备份文件。
     */
    suspend fun exportArchive(
        ctx: Context,
        plan: ExportPlan,
        onLine: suspend (String) -> Unit = {},
    ): ExportResult = withContext(Dispatchers.IO) {
        if (!plan.valid) {
            return@withContext ExportResult(false, message = ctx.appString(R.string.dsh_bk_vault_needs_password))
        }
        if (plan.password.isNotEmpty()) {
            val bad = DshBackupCrypto.selfTest()
            if (bad != null) {
                return@withContext failTrace(ctx, ctx.appString(R.string.dsh_bk_crypto_broken, bad))
            }
        }
        val stage = File(ctx.getExternalFilesDir(null) ?: ctx.cacheDir, "config-backup").apply { mkdirs() }
        trace(
            ctx,
            "start scope=" + plan.scope + " sessions=" + plan.sessions +
                " password=" + (if (plan.password.isEmpty()) "no" else "yes") +
                " vault=" + plan.includesVault + " appdata=" + plan.includesAppData,
        )
        // 上一次留下的中间产物先清掉：用户连点两次导出时它们会和新产物同名
        val pluginPlain = File(stage, "plugin-plain.zip")
        val merged = File(stage, "merged.zip")
        pluginPlain.delete()
        merged.delete()

        // 1) 插件导出。刻意**不带密码**也不带 includeSecrets：这一步只出明文，密码由我们
        //    最后统一施加；sessions 也不向它要（会话由我们按数量挑，见 DshBackupArchive）。
        var fromPlugin: File? = null
        if (plan.includesDsh) {
            onLine(ctx.appString(R.string.dsh_bk_step_exporting))
            val body = JSONObject().apply {
                put("includeSecrets", false)
                put("only", JSONArray(DshBackupArchive.pluginSections()))
            }
            trace(ctx, "plugin-request only=" + DshBackupArchive.pluginSections().size)
            val raw = request("POST", "/export", body.toString())
                ?: return@withContext ExportResult(false, message = ctx.appString(R.string.dsh_bk_export_req_failed))
            val o = runCatching { JSONObject(raw) }.getOrNull()
                ?: return@withContext ExportResult(
                    false,
                    message = ctx.appString(R.string.dsh_bk_export_bad_json, raw.take(200)),
                )
            val err = o.optString("error")
            if (err.isNotEmpty()) return@withContext ExportResult(false, message = err)
            val zipPath = o.optString("zipPath")
            if (zipPath.isEmpty()) {
                return@withContext ExportResult(false, message = ctx.appString(R.string.dsh_bk_export_no_path))
            }
            trace(ctx, "plugin-file path=" + zipPath)
            val got = download(zipPath, pluginPlain)
            trace(ctx, "plugin-downloaded bytes=" + got + " ok=" + isUsableZip(pluginPlain))
            if (got <= 0) {
                return@withContext ExportResult(false, message = ctx.appString(R.string.dsh_bk_download_failed))
            }
            // 下载成功不等于内容可用：插件可能返回了一个 0 字节或半截的文件。
            // 这种包一路补下来会变成「看起来成功、实际解不开」的东西，必须在源头拦住。
            if (!isUsableZip(pluginPlain)) {
                return@withContext failTrace(ctx, ctx.appString(R.string.dsh_bk_plugin_zip_bad, got))
            }
            fromPlugin = pluginPlain
        }

        // 2) 本地补包
        onLine(ctx.appString(R.string.dsh_bk_step_merging))
        val appData = if (plan.includesAppData) DshAppData.collect(ctx) else null
        val audit = if (plan.includesAppData) DshAppData.auditFiles(ctx) else emptyList()
        val secrets = if (plan.password.isEmpty()) {
            null
        } else {
            // 含 vault：把凭据原文放进包里（由密码保护）；不含 vault 时写**空内容占位** ——
            // manifest 一旦声明 encrypted=true，插件的导入侧就要求能解出 secrets.enc
            // （解不出直接拒绝执行），插件自己也是这么做的。
            val yaml = if (plan.includesVault) {
                runCatching {
                    File(DshEnv.dshHome(ctx), ".credentials.yaml").readText(StandardCharsets.UTF_8)
                }.getOrDefault("")
            } else {
                ""
            }
            DshBackupCrypto.encryptSecrets(yaml, plan.password)
        }
        trace(
            ctx,
            "merge-start input=" + (fromPlugin?.length()?.toString() ?: "none") +
                " appdata=" + (appData != null) + " audit=" + audit.size +
                " secrets=" + (secrets != null),
        )
        val stats = try {
            DshBackupArchive.merge(
                ctx = ctx,
                input = fromPlugin,
                output = merged,
                plan = plan,
                appData = appData,
                auditFiles = audit,
                secrets = secrets,
                sourceDshVersion = dshVersionOrUnknown(ctx),
            )
        } catch (e: Exception) {
            return@withContext ExportResult(false, message = ctx.appString(R.string.dsh_bk_merge_failed, describe(e)))
        }
        // 补包可能「什么都没写」还不报错（例如输入是空文件 + 提前返回的写法）。
        // 49 字节的空容器就是这么来的：加密一个空文件，头 + 空密文的 tag 正好 49 字节。
        trace(
            ctx,
            "merge-done bytes=" + merged.length() + " sessions=" + stats.sessions +
                " sessionFiles=" + stats.sessionFiles + " appdata=" + stats.appData +
                " auditFiles=" + stats.auditFiles + " secrets=" + stats.secrets,
        )
        if (!isUsableZip(merged)) {
            return@withContext failTrace(ctx, ctx.appString(R.string.dsh_bk_merge_empty, merged.length()))
        }

        // 3) 整包加密（有密码时）
        val name = "dsh-config-" + stamp() + ".zip"
        val finalFile = File(stage, name)
        finalFile.delete()
        if (plan.password.isEmpty()) {
            merged.copyTo(finalFile, overwrite = true)
        } else {
            // 先验流式加解密这条路本身是好的（内存版自检覆盖不到它）
            val streamBad = DshBackupCrypto.selfTestFiles(stage)
            trace(ctx, "selftest-stream=" + (streamBad ?: "ok"))
            if (streamBad != null) {
                return@withContext failTrace(ctx, ctx.appString(R.string.dsh_bk_crypto_broken, streamBad))
            }
            try {
                // 小包走**内存版**：selfTest() 每次导出前都会验它，是这条路里唯一
                // 「每一版都验过」的加密器；流式那对函数只留给大包（几百 MB 的 vault 包
                // 不能整个读进内存）。两条路的容器格式完全一致，验证步骤对两者都适用。
                if (merged.length() <= IN_MEMORY_ENCRYPT_LIMIT) {
                    val plainBytes = merged.readBytes()
                    trace(ctx, "encrypt=memory plainRead=" + plainBytes.size + " fileLen=" + merged.length())
                    // 「文件说 N 字节、实际只读出 M 字节」这种事必须当场拦下：
                    // 否则加密出来就是一个头 + 空密文的 49 字节容器。
                    if (plainBytes.size.toLong() != merged.length()) {
                        return@withContext failTrace(
                            ctx,
                            ctx.appString(
                                R.string.dsh_bk_verify_failed,
                                "读取明文",
                                merged.length().toString() + " 字节",
                                plainBytes.size.toString() + " 字节",
                            ),
                        )
                    }
                    finalFile.writeBytes(DshBackupCrypto.encryptArchive(plainBytes, plan.password))
                } else {
                    trace(ctx, "encrypt=stream fileLen=${merged.length()}")
                    DshBackupCrypto.encryptArchiveToFile(merged, finalFile, plan.password)
                }
            } catch (e: Exception) {
                finalFile.delete()
                return@withContext failTrace(ctx, ctx.appString(R.string.dsh_bk_encrypt_failed, describe(e)))
            }
            // 加密不校验等于没做：直接拿刚写出的文件解一遍，解不回来就删掉并如实报错。
            // 大小也要对：GCM 不放大数据，容器必须正好是「头 + 明文」。
            val verify = verifyEncrypted(ctx, merged, finalFile, plan.password, stage)
            trace(
                ctx,
                "container bytes=" + finalFile.length() +
                    " expected=" + (DshBackupCrypto.HEADER_LENGTH + merged.length()) +
                    " verify=" + (verify ?: "ok"),
            )
            if (verify != null) {
                finalFile.delete()
                return@withContext failTrace(ctx, verify)
            }
        }
        merged.delete()
        pluginPlain.delete()

        // 4) 落公共 Download/DSH-Folk（写不进才退回应用专属目录）
        val (location, publicOk) = copyToPublic(ctx, finalFile, name)
        val outSummary = buildString {
            append(ctx.appString(R.string.dsh_bk_exported, name))
            append("，").append(ctx.appString(R.string.dsh_bk_out_size, humanSize(finalFile.length())))
            if (stats.sessions > 0) {
                append("，").append(ctx.appString(R.string.dsh_bk_out_sessions, stats.sessions, stats.sessionFiles))
            }
            append("，").append(ctx.appString(R.string.dsh_bk_out_appdata))
            .append(if (stats.appData) "" else "×")
            append("，").append(
                if (stats.secrets) ctx.appString(R.string.dsh_bk_out_vault) else ctx.appString(R.string.dsh_bk_out_novault)
            )
            if (!publicOk) append("\n! ").append(ctx.appString(R.string.dsh_bk_copy_failed))
        }
        trace(ctx, "copy location=" + location + " public=" + publicOk + " bytes=" + finalFile.length())
        ExportResult(
            ok = true,
            file = finalFile,
            sizeBytes = finalFile.length(),
            location = location,
            sections = if (plan.includesDsh) DshBackupArchive.pluginSections().size else 0,
            encrypted = plan.password.isNotEmpty(),
            message = outSummary,
        )
    }

    /**
     * 导出过程记一笔（进 filesDir/backup_log.log，bugreport 会带上它）。
     *
     * 「导出的包只有 49 字节」这种事，光看代码读不出来，必须知道每一步的实际大小 ——
     * 插件给了多少、补包后多少、容器多少。日志里没有这些数字时，就只能靠来回问用户。
     */
    private suspend fun trace(ctx: Context, step: String) {
        runCatching { BackupLogManager.log("export $step") }
    }

    /** 这个文件是「能打开的 zip」吗（0 字节、半截文件、非 zip 都算不行）。 */
    private fun isUsableZip(f: File): Boolean = runCatching {
        if (!f.isFile || f.length() <= 0L) return false
        java.util.zip.ZipInputStream(f.inputStream()).use { zis -> zis.nextEntry != null }
    }.getOrElse { false }

    /**
     * 加密之后当场解回来核对（大小 + 内容 sha256），不通过就返回一句给人看的原因。
     *
     * 这一步是「不把坏包交给用户」的最后一道闸：它真的读刚落盘的那个文件，而不是相信
     * 写它的那段代码 —— 之前那次 49 字节的坏包就是「写的人以为写好了」。
     */
    private fun verifyEncrypted(
        ctx: Context,
        plain: File,
        blob: File,
        password: String,
        stage: File,
    ): String? {
        val expected = DshBackupCrypto.HEADER_LENGTH.toLong() + plain.length()
        if (blob.length() != expected) {
            return ctx.appString(R.string.dsh_bk_verify_failed, "大小", "$expected", blob.length().toString())
        }
        val back = File(stage, "verify-back.zip")
        try {
            if (!DshBackupCrypto.decryptArchiveToFile(blob, back, password)) {
                return ctx.appString(R.string.dsh_bk_verify_failed, "解密", "成功", "失败")
            }
            if (back.length() != plain.length()) {
                return ctx.appString(
                    R.string.dsh_bk_verify_failed, "大小", plain.length().toString(), back.length().toString(),
                )
            }
            val a = sha256File(plain)
            val b = sha256File(back)
            if (a != b) return ctx.appString(R.string.dsh_bk_verify_failed, "内容", a.take(12), b.take(12))
            return null
        } finally {
            back.delete()
        }
    }

    /** 逐块算 sha256（大包不读进内存）。 */
    private fun sha256File(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        f.inputStream().use { ins ->
            var n = ins.read(buf)
            while (n > 0) {
                md.update(buf, 0, n)
                n = ins.read(buf)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 1536 字节 → "1.5 KB"（只为了让用户一眼看出是不是空包）。 */
    private fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }

    /**
     * 导入预检的结果：上传、分析、**试规划**都已经做过了，界面据此决定要不要问用户。
     *
     * 之所以把「试规划」也放进预检：冲突只有生成计划才看得见（`/analyze` 返回的是分区清单
     * 与兼容性，不含逐项冲突），而计划是零写入的 dry run —— 用默认策略 merge 跑一次，
     * 凡是 `kind == "Conflict"` 的条目就是「本机已有、且与备份不同」的项。于是界面能做到
     * 「检测到冲突才问」，而不是事先逼用户选一个策略。
     */
    data class Preflight(
        /** 插件侧的已上传路径；纯软件数据包为空（那种包不进插件流程）。 */
        val zipPath: String,
        /** 本地明文包：会话与软件数据都从它读；等于用户选的文件时就是文件本身。 */
        val plainZip: File,
        /** 包内会话文件数。 */
        val sessions: Int,
        /** 冲突条目（最多 [MAX_CONFLICT_LIST] 条，供弹窗列出来）。 */
        val conflicts: List<String>,
        /** 冲突总数（列表被截断时仍然如实报数）。 */
        val conflictTotal: Int,
        /** 有没有需要插件出面的 DSH 分区（纯软件数据包为 false）。 */
        val needsDsh: Boolean,
    )

    /** 预检结果：要么就绪，要么带一句能直接显示给用户的失败原因。 */
    sealed class PreflightResult {
        data class Ready(val preflight: Preflight) : PreflightResult()
        data class Failed(val message: String) : PreflightResult()
    }

    /**
     * 导入前的一次预检：解容器（需要时）→ 数会话 → 上传 → 分析 → 用默认策略试规划看冲突。
     *
     * 它把「上传」这一步的产物一并交回去，[import] 拿到 [Preflight] 后不会再传第二遍 ——
     * 一个带会话的备份可能上百兆，为了问一句话就传两次是不可接受的；解出来的明文包同理。
     */
    suspend fun preflightImport(
        ctx: Context,
        zip: File,
        password: String,
        onLine: suspend (String) -> Unit = {},
    ): PreflightResult = withContext(Dispatchers.IO) {
        val plainZip: File = if (DshBackupCrypto.isArchiveBlobFile(zip)) {
            if (password.isEmpty()) {
                return@withContext PreflightResult.Failed(ctx.appString(R.string.dsh_bk_need_password))
            }
            DshBackupCrypto.selfTest()?.let {
                return@withContext PreflightResult.Failed(ctx.appString(R.string.dsh_bk_crypto_broken, it))
            }
            val tmpDir = File(ctx.filesDir, "backup-tmp").apply { mkdirs() }
            val plain = File(tmpDir, "preflight-plain.zip")
            if (!DshBackupCrypto.decryptArchiveToFile(zip, plain, password)) {
                return@withContext PreflightResult.Failed(ctx.appString(R.string.dsh_bk_bad_password))
            }
            plain
        } else {
            zip
        }
        trace(ctx, "import-start file=" + zip.name + " container=" + (plainZip != zip))
        val sessions = countSessionsInZip(plainZip)
        trace(
            ctx,
            "import-decrypted bytes=" + plainZip.length() + " sessions=" + sessions +
                " dsh=" + hasDshSections(plainZip),
        )
        if (!hasDshSections(plainZip)) {
            // 纯软件数据包：没有分区要恢复，也就不存在冲突
            return@withContext PreflightResult.Ready(
                Preflight("", plainZip, sessions, emptyList(), 0, needsDsh = false),
            )
        }
        onLine(ctx.appString(R.string.dsh_bk_step_uploading, plainZip.name))
        val up = upload(plainZip)
            ?: return@withContext PreflightResult.Failed(ctx.appString(R.string.dsh_bk_upload_failed))
        val upObj = runCatching { JSONObject(up) }.getOrNull()
            ?: return@withContext PreflightResult.Failed(ctx.appString(R.string.dsh_bk_upload_bad_json))
        val zipPath = upObj.optString("zipPath")
        if (zipPath.isEmpty()) {
            return@withContext PreflightResult.Failed(
                upObj.optString("error").ifEmpty { ctx.appString(R.string.dsh_bk_upload_no_path) },
            )
        }
        onLine(ctx.appString(R.string.dsh_bk_step_analyzing))
        val analyze = request("POST", "/analyze", JSONObject().put("zipPath", zipPath).toString())
            ?: return@withContext PreflightResult.Failed(ctx.appString(R.string.dsh_bk_analyze_failed))
        trace(ctx, "import-uploaded zipPath=" + zipPath)
        val analyzeObj = runCatching { JSONObject(analyze) }.getOrNull()
            ?: return@withContext PreflightResult.Failed(ctx.appString(R.string.dsh_bk_analyze_bad_json))
        val analyzeErr = analyzeObj.optString("error")
        if (analyzeErr.isNotEmpty()) return@withContext PreflightResult.Failed(analyzeErr)
        if (!analyzeObj.optBoolean("valid", true)) {
            val errs = analyzeObj.optJSONArray("errors")
            val detail = buildString {
                for (i in 0 until (errs?.length() ?: 0)) {
                    val e = errs?.optString(i) ?: continue
                    if (e.isNotEmpty()) append("✗ ").append(e).append('\n')
                }
            }
            return@withContext PreflightResult.Failed(
                ctx.appString(R.string.dsh_bk_invalid_archive) + detail,
            )
        }
        if (analyzeObj.optString("compatibility") == "unsupported") {
            return@withContext PreflightResult.Failed(ctx.appString(R.string.dsh_bk_incompatible))
        }
        // 试规划：默认策略下未决策的冲突项保持 kind == "Conflict"，数一下就知道要不要问
        val dryPlan = request(
            "POST", "/plan",
            JSONObject()
                .put("zipPath", zipPath)
                .put(
                    "decisions",
                    JSONObject().apply {
                        put("strategy", "merge")
                        put("resolutions", JSONObject())
                        put("pathMappings", JSONArray())
                    },
                )
                .toString(),
        )?.let { runCatching { JSONObject(it) }.getOrNull() }
        trace(
            ctx,
            "import-analyze valid=" + analyzeObj.optBoolean("valid", true) +
                " compat=" + analyzeObj.optString("compatibility") +
                " encrypted=" + analyzeObj.optBoolean("encrypted") +
                " secrets=" + analyzeObj.optInt("secretCount"),
        )
        val items = dryPlan?.optJSONArray("items")
        val conflicts = mutableListOf<String>()
        var total = 0
        for (i in 0 until (items?.length() ?: 0)) {
            val item = items?.optJSONObject(i) ?: continue
            if (item.optString("kind") != "Conflict") continue
            total++
            if (conflicts.size < MAX_CONFLICT_LIST) {
                val section = item.optString("adapter")
                val desc = item.optString("description").ifEmpty { item.optString("id") }
                conflicts += if (section.isEmpty()) desc else section + ": " + desc
            }
        }
        trace(ctx, "import-preflight conflicts=" + total + " listed=" + conflicts.size)
        PreflightResult.Ready(Preflight(zipPath, plainZip, sessions, conflicts, total, needsDsh = true))
    }

    /**
     * 导入之前先看一眼包里有多少个会话 —— 界面拿它决定「要不要弹那个询问框」。
     *
     * 加密包得先用密码解到临时文件才能数（条目表在容器里面），所以这里要有密码；密码为空
     * 且确实是加密包时返回 0：那种情况下界面本来就要先要密码，谈不上问会话。
     * 解出来的临时明文用完即删，不留含凭据的中间产物。
     */
    suspend fun countSessionsForPrompt(ctx: Context, zip: File, password: String): Int =
        withContext(Dispatchers.IO) {
            if (!DshBackupCrypto.isArchiveBlobFile(zip)) return@withContext countSessionsInZip(zip)
            if (password.isEmpty()) return@withContext 0
            val tmp = File(File(ctx.filesDir, "backup-tmp").apply { mkdirs() }, "peek-plain.zip")
            val ok = runCatching { DshBackupCrypto.decryptArchiveToFile(zip, tmp, password) }.getOrDefault(false)
            val n = if (ok) countSessionsInZip(tmp) else 0
            tmp.delete()
            n
        }

    /**
     * 用户中途放弃导入时调用：把预检解出来的明文临时包删掉。
     *
     * 不解密时 [Preflight.plainZip] 就是用户自己选的文件，那种情况什么都不做 —— 删别人的
     * 文件是不可接受的。解出来的临时包里有 security/secrets.enc 与 App 数据，留在
     * filesDir 里没有任何理由。
     */
    fun discardPreflight(preflight: Preflight) {
        val f = preflight.plainZip
        if (f.parentFile?.name != "backup-tmp") return
        runCatching { f.delete() }
    }

    /** 预检里最多列几条冲突（弹窗里列清单，不把上百条塞进去）。 */
    private const val MAX_CONFLICT_LIST = 8

    /** 导入时会话怎么处理 —— 就是用户在弹窗里选的那一项。 */
    enum class SessionImport {
        /** 只恢复配置，不动会话。 */
        SKIP,

        /** 在 dsh 运行中直接写入（重启 dsh 后生效）。 */
        DIRECT,

        /** 先停服务再写（推荐：写完之后不会被任何工作区操作盖掉）。 */
        STOP,
    }

    /**
     * 包里有多少个会话文件（弹窗靠它决定要不要问、以及显示数量）。
     *
     * 只读 ZIP 的条目表，不碰内容 —— 一个几百 MB 的包在这里也不该被读进内存。
     */
    fun countSessionsInZip(zip: File): Int = runCatching {
        var n = 0
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (!e.isDirectory && e.name.startsWith(SESSION_PREFIX)) {
                    val rel = safeSessionRel(e.name)
                    if (rel != null && !isSessionRuntimeState(rel)) n++
                }
                zis.closeEntry()
            }
        }
        n
    }.getOrDefault(0)

    /**
     * 这个包里有没有需要插件出面的 DSH 分区。
     *
     * 读不到 manifest 时返回 true：那是「不是本生态的包/包坏了」，该由插件去报那个更准确的
     * 错，而不是被我们当成纯软件数据包吞掉、回一句「不含软件数据」。
     */
    private fun hasDshSections(zip: File): Boolean = runCatching {
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (!e.isDirectory && e.name == DshBackupArchive.MANIFEST) {
                    val sections = JSONObject(zis.readBytes().toString(StandardCharsets.UTF_8))
                        .optJSONObject("sections")
                    if (sections == null) return@runCatching true
                    for (k in sections.keys()) if (sections.optBoolean(k)) return@runCatching true
                    return@runCatching false
                }
                zis.closeEntry()
            }
            true
        }
    }.getOrDefault(true)

    /** export 文件名里的时间戳（与插件自动命名的风格一致，便于在文件管理器里排在一起）。 */
    private fun stamp(): String =
        java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())

    /**
     * 容器里的 dsh 版本：插件在跑就问它，问不到就写 unknown（只有纯软件数据包用得上，
     * 那种包的 manifest 由我们自己写，而 [status] 是 suspend 的，所以这里也得是 suspend）。
     */
    private suspend fun dshVersionOrUnknown(ctx: Context): String =
        runCatching { status(ctx).dshVersion }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "unknown"

    /** 失败统一走这里：把原因记进日志再返回，免得「用户看到了提示、日志里什么都没有」。 */
    private suspend fun failTrace(ctx: Context, message: String): ExportResult {
        runCatching { BackupLogManager.log("export failed: $message") }
        return ExportResult(false, message = message)
    }

    private fun describe(e: Throwable): String = e.javaClass.simpleName + ": " + (e.message ?: "")

    private fun copyToPublic(ctx: Context, src: File, name: String): Pair<String, Boolean> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_SUBDIR")
                // API 29/30 上 IS_PENDING=1 → 写字节 → 翻成 0 是让文件立刻可见的官方路径。
                // 不走这一步，备份要重启或重新挂载才在「下载」里出现，对用户不可见。
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val uri = runCatching {
                ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            }.getOrNull()
            if (uri != null) {
                val written = runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        src.inputStream().use { it.copyTo(out) }
                    }
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
                        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                        ctx.contentResolver.update(uri, done, null, null)
                    }
                    true
                }.getOrElse {
                    // 写失败就把这条 pending 行删掉，免得留着一条 0 字节的尸体
                    runCatching { ctx.contentResolver.delete(uri, null, null) }
                    false
                }
                if (written) return "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_SUBDIR/$name" to true
            }
        }
        // 兜底：公共目录直接可写（SDK<29 有 WRITE_EXTERNAL_STORAGE），或退回应用专属目录
        val dir = backupDir(ctx)
        if (dir.exists() || dir.mkdirs()) {
            // 复制失败时**不能**照样把路径报给用户：他拿着一个不存在的路径去文件管理器里找，
            // 只会以为备份丢了。所以这里校验「存在 + 大小一致」，不一致就说实话退回暂存文件。
            val target = File(dir, name)
            val copied = runCatching { src.copyTo(target, overwrite = true) }.isSuccess &&
                target.exists() && target.length() == src.length()
            if (!copied) return src.absolutePath to false
            // SDK<29 需要主动触发媒体扫描，文件管理器才看得到新文件
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                runCatching {
                    android.media.MediaScannerConnection.scanFile(
                        ctx, arrayOf(File(dir, name).absolutePath), null, null,
                    )
                }
            }
            return target.absolutePath to true
        }
        // 连兜底目录都建不出来：就留在暂存目录，位置照实说
        return src.absolutePath to false
    }

    /**
     * 打开备份所在目录。
     *
     * API 29+ 打开系统「下载」（备份落在 Download/DSH-Folk，MediaStore 没有按
     * RELATIVE_PATH 直接开子目录的稳定入口）；SDK<30 或有「所有文件」权限时，
     * [backupDir] 是真·公共目录，用 FileProvider 打开到具体文件夹。
     *
     * @return 是否成功发起打开意图
     */
    fun openBackupDir(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val opened = runCatching {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setData(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.isSuccess
            if (opened) return true
        }
        val dir = backupDir(ctx)
        dir.mkdirs()
        return runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", dir,
            )
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "resource/folder")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
    }

    /** 运行时 exports 目录里的一个备份文件。 */
    data class RemoteBackup(
        val name: String,
        /** 容器里的完整路径（列表没给就按插件 exports 目录推）。 */
        val path: String = "",
        val sizeBytes: Long = 0,
        val mtimeMs: Long = 0,
        val note: String = "",
    )

    /**
     * DSH 内某个备份在容器里的完整路径。
     *
     * 插件的 /download 要的是容器内路径而不是文件名，列表接口通常会带 path；
     * 万一没带就按它的 exports 约定推一个 —— 这个目录是插件自己固定用的
     * （日志里就是 /root/.dsh/dsh-config-manager/exports/xxx.zip）。
     */
    fun remoteBackupPath(b: RemoteBackup): String =
        b.path.ifEmpty { DSH_BACKUP_EXPORTS_DIR + b.name }

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
                        path = v.optString("path"),
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

    /**
     * 删除 DSH 内的一个备份文件。
     *
     * 插件只接受「纯 .zip 文件名」（自己会做防穿越），所以这里先在本地挡一道：
     * 带路径分隔符、不是 .zip 的输入根本不发请求，省得拿一个 400 回来还要翻译。
     * 成功返回空串，失败返回给用户看的原因。
     */
    suspend fun deleteRemoteBackup(ctx: Context, backup: RemoteBackup): String = withContext(Dispatchers.IO) {
        val name = backup.name
        if (!name.endsWith(".zip") || name.contains('/') || name.contains('\\')) {
            return@withContext ctx.appString(R.string.dsh_bk_remote_delete_bad_name, name)
        }
        val raw = request(
            "POST",
            "/backup-files/delete",
            JSONObject().put("name", name).toString(),
            timeoutMs = 60_000,
        ) ?: return@withContext ctx.appString(R.string.dsh_bk_remote_delete_failed)
        val o = runCatching { JSONObject(raw) }.getOrNull()
            ?: return@withContext ctx.appString(R.string.dsh_bk_remote_delete_failed)
        // 插件失败时给的是 {error}，原样显示比我翻译一遍有用
        val err = o.optString("error")
        if (err.isNotEmpty()) err else ""
    }

    /**
     * 把 DSH 内的某个备份下载到手机缓存，交给导入流程。
     *
     * 为什么不在这里直接导入：DSH 内的备份和手机本地选的文件本质相同（都是 zip，
     * 可能加密也可能没有），导入那条路（选密码 → 预检 → 会话/冲突询问 → 执行）
     * 已经齐了，复用它比自己再走一条通道可靠。
     */
    suspend fun fetchRemoteBackup(ctx: Context, backup: RemoteBackup): File? = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "config-restore").apply { mkdirs() }
        val dest = File(dir, backup.name)
        if (!dest.name.endsWith(".zip")) return@withContext null
        if (download(remoteBackupPath(backup), dest) <= 0) return@withContext null
        // 和导出一样：下载完先确认它真的是个能打开的 zip，别把半截文件当备份喂给导入
        if (!isUsableZip(dest)) {
            dest.delete()
            return@withContext null
        }
        dest
    }

    /**
     * 删除一个快照。
     *
     * 快照是导入前的回滚点，删掉就没了（插件那边置顶的快照只能这样手动删），
     * 所以 UI 上必须二次确认 —— 这里只负责发请求。
     */
    suspend fun deleteSnapshot(ctx: Context, snapshotId: String): RestoreResult = withContext(Dispatchers.IO) {
        val raw = request(
            "POST",
            "/snapshots/delete",
            JSONObject().put("snapshotId", snapshotId).toString(),
            timeoutMs = 60_000,
        ) ?: return@withContext RestoreResult(
            false,
            ctx.appString(R.string.dsh_bk_snapshot_delete_failed),
        )
        val o = runCatching { JSONObject(raw) }.getOrNull()
            ?: return@withContext RestoreResult(
                false,
                ctx.appString(R.string.dsh_bk_snapshot_delete_failed),
            )
        val err = o.optString("error")
        if (err.isNotEmpty()) {
            RestoreResult(false, err)
        } else {
            RestoreResult(true, ctx.appString(R.string.dsh_bk_snapshot_deleted, snapshotId))
        }
    }

    data class ImportResult(
        val ok: Boolean,
        val message: String,
        val detail: String = "",
        /**
         * 插件说「重启 DSH 才生效」（装/卸了插件、改了 MCP 等）。
         *
         * 以前它只被拼进一句文案里，界面上没人据此做事 —— 用户看到「需要重启」却不知道
         * 按钮在哪，回头就以为恢复没生效。现在由它驱动一个真正的「立即重启服务」。
         */
        val needsRestart: Boolean = false,
    )

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
     * @param sessions 包里带着会话时怎么办（[SessionImport]）：跳过、在 dsh 运行中直接
     *        写入，还是先停服务再写。会话记录**必须由我们自己做**，原因见 [restoreSessionsFromZip]。
     * @param onLine 阶段进度（上传/分析/计划/执行/会话/软件数据）。
     */
    suspend fun import(
        ctx: Context,
        zip: File,
        strategy: String = "merge",
        password: String = "",
        sessions: SessionImport = SessionImport.SKIP,
        /**
         * [preflightImport] 的结果。给了就用它已上传好的路径与已解好的明文包，不再传第二遍
         * —— 冲突与会话的询问都发生在预检之后，大包不能被传两次。
         */
        preflight: Preflight? = null,
        /**
         * 阶段进度（上传/分析/计划/执行/会话）：界面用它显示「在动」，而不是只转圈。
         *
         * 是 suspend 回调，因为界面要在里面切回主线程改 Compose 状态。
         */
        onLine: suspend (String) -> Unit = {},
    ): ImportResult = withContext(Dispatchers.IO) {
        // 整体加密的包由**我们**解开（见 [DshBackupCrypto]），不再请插件解：格式本来就是同一个
        // （DCA1），自己解顺带确认了「这确实是本生态的备份」，而且插件那一步只看到普通明文包
        // —— 所以插件内恢复、桌面端恢复都照旧能用。解出来的明文落在应用专属目录，导入结束就删。
        val tmpDir = File(ctx.filesDir, "backup-tmp").apply { mkdirs() }
        val plainZip: File = preflight?.plainZip ?: if (DshBackupCrypto.isArchiveBlobFile(zip)) {
            if (password.isEmpty()) {
                return@withContext ImportResult(false, ctx.appString(R.string.dsh_bk_need_password))
            }
            DshBackupCrypto.selfTest()?.let {
                return@withContext ImportResult(false, ctx.appString(R.string.dsh_bk_crypto_broken, it))
            }
            val plain = File(tmpDir, "import-plain.zip")
            if (!DshBackupCrypto.decryptArchiveToFile(zip, plain, password)) {
                return@withContext ImportResult(false, ctx.appString(R.string.dsh_bk_bad_password))
            }
            plain
        } else {
            zip
        }

        // 纯软件数据包：插件那边一个分区都没有，走完整流程只会白跑（还可能因为「没有可导入
        // 的项」报错）。直接恢复 App 数据即可 —— 这类包正是「仅软件数据」那一档导出来的。
        if (!hasDshSections(plainZip)) {
            val data = DshAppData.readFromZip(plainZip)
            if (data == null) {
                // 这条分支不再往下走，临时明文要在这里就删掉：它可能是解密出来的包，
                // 里面带着 security/secrets.enc 与 App 数据，留在 filesDir 里没有道理。
                if (plainZip != zip) plainZip.delete()
                return@withContext ImportResult(false, ctx.appString(R.string.dsh_bk_appdata_none))
            }
            onLine(ctx.appString(R.string.dsh_bk_step_appdata))
            val changed = DshAppData.apply(ctx, data)
            trace(ctx, "import-appdata changed=" + changed)
            // 审计要**再读一次包**，所以删除必须放在它后面（先删会让这一步对着空气空跑）
            val lines = DshAppData.mergeAudit(ctx, plainZip)
            if (plainZip != zip) plainZip.delete()
            val note = buildString {
                append(ctx.appString(R.string.dsh_bk_appdata_restored, changed))
                if (lines > 0) append("，").append(ctx.appString(R.string.dsh_bk_audit_merged, lines))
                append("\n").append(ctx.appString(R.string.dsh_bk_appdata_takes_effect))
            }
            return@withContext ImportResult(true, ctx.appString(R.string.dsh_bk_import_done, note))
        }

        var zipPath = preflight?.zipPath.orEmpty()
        var containerType = ""
        if (zipPath.isEmpty()) {
            onLine(ctx.appString(R.string.dsh_bk_step_uploading, plainZip.name))
            val up = upload(plainZip)
                ?: return@withContext ImportResult(false, ctx.appString(R.string.dsh_bk_upload_failed))
            val upObj = runCatching { JSONObject(up) }.getOrNull()
                ?: return@withContext ImportResult(false, ctx.appString(R.string.dsh_bk_upload_bad_json))
            zipPath = upObj.optString("zipPath")
            containerType = upObj.optString("containerType")
            if (zipPath.isEmpty()) {
                return@withContext ImportResult(false, upObj.optString("error").ifEmpty { ctx.appString(R.string.dsh_bk_upload_no_path) })
            }
        }

        // 走到这里还被告知是加密包，说明它的 magic 不是 DCA1（例如别人改过字节）——不猜，
        // 直接告诉用户解不开，而不是把一个半懂的文件递给插件。
        if (containerType == "encrypted") {
            return@withContext ImportResult(false, ctx.appString(R.string.dsh_bk_unlock_failed))
        }

        onLine(ctx.appString(R.string.dsh_bk_step_analyzing))
        val analyze = request("POST", "/analyze", JSONObject().put("zipPath", zipPath).toString())
            ?: return@withContext ImportResult(false, ctx.appString(R.string.dsh_bk_analyze_failed))
        val analyzeObj = runCatching { JSONObject(analyze) }.getOrNull()
            ?: return@withContext ImportResult(false, ctx.appString(R.string.dsh_bk_analyze_bad_json))
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
            return@withContext ImportResult(false, ctx.appString(R.string.dsh_bk_invalid_archive), detail)
        }
        if (analyzeObj.optString("compatibility") == "unsupported") {
            return@withContext ImportResult(false, ctx.appString(R.string.dsh_bk_incompatible))
        }

        val decisions = JSONObject().apply {
            put("strategy", strategy)
            put("resolutions", JSONObject())
            put("pathMappings", JSONArray())
        }
        onLine(ctx.appString(R.string.dsh_bk_step_planning, strategy))
        val plan = request(
            "POST", "/plan",
            JSONObject().put("zipPath", zipPath).put("decisions", decisions).toString(),
        ) ?: return@withContext ImportResult(false, ctx.appString(R.string.dsh_bk_plan_failed))
        val planObj = runCatching { JSONObject(plan) }.getOrNull()
            ?: return@withContext ImportResult(false, ctx.appString(R.string.dsh_bk_plan_bad_json))
        val planErr = planObj.optString("error")
        if (planErr.isNotEmpty()) return@withContext ImportResult(false, planErr)

        val opts = JSONObject().apply {
            put("confirm", true)
            put("rollbackOnError", true)
            if (password.isNotEmpty()) put("decryptPassword", password)
        }
        onLine(ctx.appString(R.string.dsh_bk_step_executing))
        val exec = request(
            "POST", "/execute",
            JSONObject().put("zipPath", zipPath).put("plan", planObj).put("opts", opts).toString(),
            timeoutMs = 900_000,
        ) ?: return@withContext ImportResult(false, ctx.appString(R.string.dsh_bk_exec_failed))
        val execObj = runCatching { JSONObject(exec) }.getOrNull()
            ?: return@withContext ImportResult(false, ctx.appString(R.string.dsh_bk_exec_bad_json))
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
        trace(
            ctx,
            "import-execute ok=" + execObj.optBoolean("ok", true) +
                " rollback=" + (execObj.optJSONObject("rollback") != null) +
                " needsRestart=" + execObj.optBoolean("needsRestart"),
        )
        val rollback = execObj.optJSONObject("rollback")
        if (rollback != null) {
            notes.append("↩ ").append(
                ctx.appString(
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
                    .append(ctx.appString(R.string.dsh_bk_rollback_failed, f.optString("item")))
                f.optString("reason").takeIf { it.isNotEmpty() }?.let { notes.append("：").append(it) }
                f.optString("manualHint").takeIf { it.isNotEmpty() }?.let { notes.append(" → ").append(it) }
                notes.append('\n')
            }
        }
        val needsRestart = execObj.optBoolean("needsRestart", planObj.optBoolean("needsRestart", false))
        val ok = execObj.optBoolean("ok", failed == 0) && rollback == null

        // 会话记录必须在插件跑完之后再补：插件失败会整体回滚，先写会话就会留下
        // 一堆没有对应配置的孤立会话。回滚发生时干脆不写。
        var sessionNote = ""
        if (sessions != SessionImport.SKIP && rollback == null) {
            onLine(ctx.appString(R.string.dsh_bk_step_sessions))
            val r = restoreSessionsFromZip(ctx, plainZip)
            sessionNote = when {
                r.restored > 0 -> ctx.appString(R.string.dsh_bk_sessions_restored, r.restored, r.skipped) +
                    "\n" + ctx.appString(R.string.dsh_bk_sessions_foreign_workspace)
                r.skipped > 0 -> ctx.appString(R.string.dsh_bk_sessions_all_present, r.skipped)
                else -> ctx.appString(R.string.dsh_bk_sessions_none)
            }
            if (r.failed > 0) {
                sessionNote += "\n" + ctx.appString(R.string.dsh_bk_sessions_failed, r.failed)
            }
            // 会话文件只是「放进去了」；dsh 的分组只在注册表首次 bootstrap 时做一次，
            // 之后进来的会话一律显示「未分组」且 GUI 没有归组入口 —— 所以这里补上归组。
            // 运行中改注册表也能生效（启动才读盘），但「改完之后、重启之前」任何一次
            // workspace 域写都会把整份内存状态盖回盘，改动静默丢失 —— 停着改把这条归零。
            if (r.paths.isNotEmpty()) {
                onLine(ctx.appString(R.string.dsh_bk_group_stage, r.paths.size))
                val report = runCatching {
                    // 「停机恢复」与「直接恢复」在这里分岔：两者都能生效（注册表启动才读盘），
                    // 区别只在于直接恢复时，用户接下来若在 WebUI 里动工作区，这次归组可能被
                    // dsh 的整份内存写回盖掉。所以推荐停机，但把选择权交给用户。
                    if (sessions == SessionImport.STOP) {
                        DshRuntime.withServiceStopped {
                            DshSessionGroup.groupRestoredSessions(ctx, r.paths, onLine = onLine)
                        }
                    } else {
                        DshSessionGroup.groupRestoredSessions(ctx, r.paths, onLine = onLine)
                    }
                }.getOrNull()
                val group = report ?: DshSessionGroup.Report(
                    failure = ctx.appString(R.string.dsh_bk_group_service_failed),
                )
                sessionNote += "\n" + group.summary(ctx)
                val detail = group.details()
                if (detail.isNotEmpty()) sessionNote += "\n" + detail
            }
        }

        // 软件数据：插件不认识它，一直由我们自己带、自己放回（见 [DshAppData]）。
        // 插件整体回滚时不动它：配置都没落地，先把设置写进去只会让本机处于一个
        // 「一半是备份里的设置、一半是本机配置」的状态，比不恢复更难解释。
        var appNote = ""
        val appData = if (rollback == null) DshAppData.readFromZip(plainZip) else null
        if (appData != null) {
            onLine(ctx.appString(R.string.dsh_bk_step_appdata))
            val changed = DshAppData.apply(ctx, appData)
            val lines = DshAppData.mergeAudit(ctx, plainZip)
            appNote = ctx.appString(R.string.dsh_bk_appdata_restored, changed) +
                if (lines > 0) "，" + ctx.appString(R.string.dsh_bk_audit_merged, lines) else ""
            // 设置是写进 SharedPreferences 的，界面里那些已经读进内存的状态不会自己刷新
            appNote += "\n" + ctx.appString(R.string.dsh_bk_appdata_takes_effect)
        } else if (rollback != null) {
            appNote = ctx.appString(R.string.dsh_bk_appdata_skipped_rollback)
        }

        val head = buildString {
            // 「导入完成：」这种以空格/冒号收尾的前缀不能单独做一条资源：
            // AAPT2 会把 XML 文本值的首尾空白 trim 掉（英文那条 "Import finished: "
            // 打进 APK 后变成 "Import finished:"，和后面的计数黏在一起）。
            // 用带 %1$s 的完整格式串，把计数当参数塞进去。
            append(
                ctx.appString(
                    if (ok) R.string.dsh_bk_import_done else R.string.dsh_bk_import_incomplete,
                    ctx.appString(R.string.dsh_bk_items, total),
                )
            )
            if (failed > 0) append(ctx.appString(R.string.dsh_bk_items_failed, failed))
            if (warned > 0) append(ctx.appString(R.string.dsh_bk_items_warned, warned))
            if (skipped > 0) append(ctx.appString(R.string.dsh_bk_items_skipped, skipped))
            if (tombstonedCount > 0) {
                append(ctx.appString(R.string.dsh_bk_items_tombstoned, tombstonedCount))
            }
            if (needsRestart) append(ctx.appString(R.string.dsh_bk_needs_restart))
            execObj.optString("snapshotId").takeIf { it.isNotEmpty() }
                ?.let { append(ctx.appString(R.string.dsh_bk_snapshot, it)) }
        }
        val detail = buildString {
            append(notes)
            if (appNote.isNotEmpty()) append("◧ ").append(appNote).append('\n')
            if (sessionNote.isNotEmpty()) append("↺ ").append(sessionNote)
        }
        // 明文中间产物里含解出来的凭据，不留
        if (plainZip != zip) plainZip.delete()
        ImportResult(ok, head, detail, needsRestart)
    }

    /** [restoreSessionsFromZip] 的结果计数。 */
    data class SessionRestore(
        val restored: Int,
        val skipped: Int,
        val failed: Int,
        /**
         * 本次真正落盘的文件（相对 sessions 根）。
         *
         * 归组助手只处理「这次恢复进来的」会话，所以必须把清单传给它 —— 让它去扫全树的话，
         * 用户本来就故意留在「未分组」里的会话也会被它动。
         */
        val paths: List<String> = emptyList(),
    )

    /**
     * 把备份包里的会话记录直接写进 `~/.dsh/sessions`。
     *
     * ## 为什么不交给插件
     *
     * dsh-config-manager 的导入执行阶段按一张固定的 `APPLY_ORDER` 遍历 adapter，
     * 而那张表**只有 12 个分区**（settings/ui/providers/prompts/skills/agentPresets/
     * agentInstructions/workspaces/pluginFiles/mcp/plugins/credentialsStatus）——
     * `sessions` 不在其中。它的 analyze 会正确解析出 ZIP 里 `sessions/` 的文件、
     * plan 也会把这些项算进去，但 execute 永远不会 apply 它们：**静默丢弃，连
     * warning 都没有**。所以「导出勾了会话，导入却找不到历史聊天」不是参数问题，
     * 传什么都救不回来。
     *
     * 而这件事我们自己做得到：会话就是纯文件
     * （`~/.dsh/sessions/<projectKey>/<sessionId>/session.jsonl.zstd`），
     * 而 `~/.dsh` 就在应用私有目录里（[DshEnv.dshHome]），直接落盘即可。
     *
     * ## 冲突与安全
     *
     * - 已存在的目标文件**跳过不覆盖**：会话日志是只追加的不可变流，同 id 即同会话，
     *   覆盖只会丢掉本机更新的那部分。这也让重复导入天然幂等。
     * - 逐段校验 ZIP 内路径（拒绝空段、`.`、`..`、绝对路径、反斜杠），再用
     *   canonicalPath 二次确认落点仍在目标目录内 —— ZIP 是不可信输入，
     *   `../` 条目能写到应用私有目录的任何地方。
     * - 单个文件失败只计数，不中断整轮。
     */
    suspend fun restoreSessionsFromZip(ctx: Context, zip: File): SessionRestore =
        withContext(Dispatchers.IO) {
            val base = File(DshEnv.dshHome(ctx), "sessions")
            if (!base.isDirectory && !base.mkdirs()) {
                return@withContext SessionRestore(0, 0, 1)
            }
            val baseCanon = runCatching { base.canonicalPath }.getOrNull()
                ?: return@withContext SessionRestore(0, 0, 1)
            var restored = 0
            var skipped = 0
            var failed = 0
            val written = mutableListOf<String>()
            runCatching {
                java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        val rel = safeSessionRel(entry.name)
                        if (rel == null) {
                            // 不是会话条目（别的分区/目录项）不算跳过；真正被拒的路径才计数
                            if (entry.name.startsWith(SESSION_PREFIX) && !entry.isDirectory) skipped++
                            zis.closeEntry()
                            continue
                        }
                        // 会话目录里的 session.lock 是**运行时状态**（「这个会话正在被写」的
                        // 标记），不是会话数据：跨机恢复一份别人的锁没有意义，而且后面的归组
                        // 步骤会把它当成一个会话去解析 —— 0 字节解不出 zstd 帧，于是真机上出现
                        // 「15 个会话文件里 6 个不可读」，还把 6 个锁文件挪出了 sessions 树。
                        if (isSessionRuntimeState(rel)) {
                            zis.closeEntry()
                            continue
                        }
                        val dest = File(base, rel)
                        val destCanon = runCatching { dest.canonicalPath }.getOrNull()
                        if (destCanon == null || !destCanon.startsWith(baseCanon + File.separator)) {
                            skipped++
                            zis.closeEntry()
                            continue
                        }
                        if (dest.exists()) {
                            skipped++
                            zis.closeEntry()
                            continue
                        }
                        val wrote = runCatching {
                            dest.parentFile?.mkdirs()
                            dest.outputStream().use { out -> zis.copyTo(out) }
                            true
                        }.getOrDefault(false)
                        if (wrote) {
                            restored++
                            written += rel
                        } else {
                            failed++
                        }
                        zis.closeEntry()
                    }
                }
            }.onFailure { failed++ }
            SessionRestore(restored, skipped, failed, written)
        }

    /** 会话文件在导出 ZIP 内的目录前缀（插件 SECTION_FILE_PREFIXES.sessions）。 */
    private const val SESSION_PREFIX = "sessions/"

    /**
     * 会话目录里的**运行时状态**文件：锁、临时文件、点开头的东西。
     *
     * 它们不是会话数据，恢复时不该带走：一份别机的锁在本机没有任何意义，而它会被
     * [DshSessionGroup] 当成会话去解析 —— 0 字节解析不出 zstd 帧，报「不可读」并把它
     * 挪出 sessions 树（真机上 6 个会话文件报错的根因）。
     */
    private fun isSessionRuntimeState(rel: String): Boolean {
        val name = rel.substringAfterLast('/')
        return name.startsWith(".") || name.endsWith(".lock") || name.endsWith(".tmp")
    }

    /**
     * ZIP 条目名 → 相对会话目录的安全路径；不是会话文件或路径不可信时返回 null。
     *
     * 逐段拒绝而不是只查开头的 `../`：`sessions/x/../../y` 开头完全正常，
     * 拼接后照样能逃出目标目录。
     */
    private fun safeSessionRel(entryName: String): String? {
        if (!entryName.startsWith(SESSION_PREFIX)) return null
        val rel = entryName.removePrefix(SESSION_PREFIX)
        if (rel.isEmpty() || rel.endsWith("/")) return null
        if (rel.startsWith("/") || rel.contains('\\')) return null
        // 盘符（C:/…）在 Android 上无意义，但它是 ZIP 里常见的越界写法
        if (rel.length >= 2 && rel[1] == ':') return null
        val parts = rel.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return parts.joinToString("/")
    }

    /** 把 SAF 选中的文件先落到应用可控目录，再上传（ContentResolver 的 Uri 不能直接给 HTTP）。 */
    fun stage(ctx: Context, input: InputStream, name: String): File? = runCatching {
        val dir = File(ctx.cacheDir, "config-import").apply { mkdirs() }
        val f = File(dir, name.ifBlank { "backup.zip" })
        f.outputStream().use { input.copyTo(it) }
        f
    }.getOrNull()

    // ───────────────────────────── 快照 ─────────────────────────────

    /** 插件快照目录里的一个快照（GET /snapshots 的 snapshots[] 元素）。 */
    data class Snapshot(
        val id: String,
        /** 创建时间（毫秒）；插件没给或解析不了时为 0，只影响排序显示。 */
        val createdAtMs: Long = 0L,
        val note: String = "",
        val sizeBytes: Long = 0L,
    )

    /**
     * 列出插件保留的快照。
     *
     * 快照目录永不被自动清理（插件源码注释明写「它是恢复的最后依靠」），所以这张表就是
     * 用户真正能回退到的历史状态 —— 而在此之前，它只能去插件的网页界面里看。
     */
    suspend fun listSnapshots(): List<Snapshot> = withContext(Dispatchers.IO) {
        val raw = request("GET", "/snapshots", null, timeoutMs = 60_000) ?: return@withContext emptyList()
        val arr = runCatching { JSONObject(raw).optJSONArray("snapshots") }.getOrNull()
            ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").ifEmpty { return@mapNotNull null }
            Snapshot(
                id = id,
                // 插件侧 createdAt 是 ISO 字符串；也认可能出现的 createdAtMs 数字
                createdAtMs = o.optLong("createdAtMs", 0L).takeIf { it > 0 }
                    ?: parseIsoMillis(o.optString("createdAt")),
                note = o.optString("note"),
                sizeBytes = o.optLong("sizeBytes", 0L),
            )
        }
    }

    /** 解析 ISO-8601 时间戳；解析不了返回 0（只影响显示排序，不影响功能）。 */
    private fun parseIsoMillis(text: String): Long {
        if (text.isBlank()) return 0L
        return runCatching { java.time.Instant.parse(text).toEpochMilli() }.getOrElse {
            runCatching { java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli() }
                .getOrDefault(0L)
        }
    }

    data class RestorePreview(
        val ok: Boolean,
        /** 计划里的动作数。 */
        val actions: Int = 0,
        /** 插件 plan 里的摘要（没有就由界面自己拼计数）。 */
        val summary: String = "",
        val message: String = "",
    )

    /**
     * 预览恢复到某个快照会做什么（POST /restore，dryRun=true）。
     *
     * **零写入**：插件只返回动作计划。恢复会覆盖设置文件、并卸载快照里没有的插件
     * （报告的 removedPlugins），所以必须在动手前把计划摆给用户看，而不是事后惊讶。
     */
    suspend fun previewSnapshot(snapshotId: String): RestorePreview = withContext(Dispatchers.IO) {
        val raw = request(
            "POST", "/restore",
            JSONObject().put("snapshotId", snapshotId).put("dryRun", true).toString(),
            timeoutMs = 120_000,
        ) ?: return@withContext RestorePreview(false, message = "no response")
        val o = runCatching { JSONObject(raw) }.getOrNull()
            ?: return@withContext RestorePreview(false, message = raw.take(200))
        val err = o.optString("error")
        if (err.isNotEmpty()) return@withContext RestorePreview(false, message = err)
        val plan = o.optJSONObject("plan")
        val actions = plan?.optJSONArray("actions")?.length()
            ?: plan?.optJSONArray("steps")?.length() ?: 0
        RestorePreview(true, actions, plan?.optString("summary").orEmpty())
    }

    data class RestoreResult(val ok: Boolean, val message: String, val detail: String = "")

    /**
     * 恢复到一个快照（POST /restore，dryRun=false）。
     *
     * 插件侧语义（已核对源码）：恢复前把当前文件复制到 <snapshot>/pre-restore/ 作双保险；
     * 报告是诚实的 restored / removedPlugins / manualHints / failed / skipped；同一时刻只允许
     * 一个 restore 在跑（冲突返回 409），这里把 409 转成一句能看懂的话。
     */
    suspend fun restoreSnapshot(ctx: Context, snapshotId: String): RestoreResult =
        withContext(Dispatchers.IO) {
            val raw = request(
                "POST", "/restore",
                JSONObject().put("snapshotId", snapshotId).put("dryRun", false).toString(),
                timeoutMs = 600_000,
            ) ?: return@withContext RestoreResult(false, ctx.appString(R.string.dsh_bk_snapshot_failed))
            val o = runCatching { JSONObject(raw) }.getOrNull()
                ?: return@withContext RestoreResult(
                    false, ctx.appString(R.string.dsh_bk_bad_json), raw.take(200),
                )
            val err = o.optString("error")
            if (err.isNotEmpty()) {
                val busy = err.contains("conflict", ignoreCase = true) ||
                    err.contains("running", ignoreCase = true)
                return@withContext RestoreResult(
                    false,
                    if (busy) ctx.appString(R.string.dsh_bk_snapshot_busy) else err,
                )
            }
            val restored = o.optJSONArray("restored")?.length() ?: 0
            val removed = o.optJSONArray("removedPlugins") ?: JSONArray()
            val failed = o.optJSONArray("failed") ?: JSONArray()
            val skipped = o.optJSONArray("skipped")?.length() ?: 0
            val hints = o.optJSONArray("manualHints") ?: JSONArray()
            val notes = StringBuilder()
            for (i in 0 until removed.length()) {
                val v = removed.optString(i)
                if (v.isNotEmpty()) notes.append("− ").append(v).append('\n')
            }
            for (i in 0 until failed.length()) {
                val item = failed.optJSONObject(i) ?: continue
                notes.append("✗ ").append(item.optString("item").ifEmpty { item.optString("path") })
                item.optString("reason").takeIf { it.isNotEmpty() }?.let { notes.append("：").append(it) }
                notes.append('\n')
            }
            for (i in 0 until hints.length()) {
                val v = hints.optString(i)
                if (v.isNotEmpty()) notes.append("→ ").append(v).append('\n')
            }
            val head = buildString {
                append(ctx.appString(R.string.dsh_bk_snapshot_done, restored, snapshotId))
                if (removed.length() > 0) {
                    append(ctx.appString(R.string.dsh_bk_snapshot_removed, removed.length()))
                }
                if (failed.length() > 0) {
                    append(ctx.appString(R.string.dsh_bk_items_failed, failed.length()))
                }
                if (skipped > 0) append(ctx.appString(R.string.dsh_bk_items_skipped, skipped))
            }
            RestoreResult(failed.length() == 0, head, notes.toString())
        }

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
