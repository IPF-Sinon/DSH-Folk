package me.bmax.apatch.dsh

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.LinkedHashMap
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.bmax.apatch.BuildConfig
import me.bmax.apatch.R
import me.bmax.apatch.ui.theme.ThemeIO
import me.bmax.apatch.ui.theme.ThemeManager
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
     * `/status` 探活的读超时。
     *
     * 它是**一次健康检查**，不是一次作业：返回慢只有一个含义 —— 不可用。所以这里用
     * 秒级而不是 [request] 默认的分钟级，界面才可能在一次转身之内给出结论。
     */
    const val STATUS_TIMEOUT_MS = 15_000


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

    /** 主题包大小上限：超过它，「包含应用主题」默认不勾（用户仍可手动勾上）。 */
    const val THEME_SIZE_LIMIT_BYTES = 5L * 1024 * 1024


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

    /**
     * 插件在不在、能不能用。DSH 没起来或插件没装都会落到 ready=false。
     *
     * 这是一次**探活**，用的是自己的短超时（见 [STATUS_TIMEOUT_MS]）：[request] 默认的
     * 300 秒是给导入导出那种真在干活的请求用的，套在探活上会让「检测中」持续五分钟 ——
     * 界面那边按钮的可点性全挂在这个结果上，那种时长等于没有反馈。
     */
    suspend fun status(ctx: Context): Status = withContext(Dispatchers.IO) {
        val r = request("GET", "/status", null, timeoutMs = STATUS_TIMEOUT_MS)
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
            "start scope=" + plan.scope + " sessions=" + plan.sessionLimit +
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
        // 外观（背景图/视频背景/字体/音乐/音效）不在 prefs 里 —— prefs 只存文件名与
        // `file://` 指向，文件本身在 filesDir。所以它单独打一个主题包进包（见
        // [DshBackupArchive.THEME]），走的是既有的主题导出通路，不另造一套。
        // 具名传 onLine：它前面还有一个带默认值的 fileName，位置传参会跳不过去。
        // 主题只在「含软件数据 + 没关掉主题开关」时导出；关掉时包里就没有 theme.zip，
        // 恢复侧 matching 地什么也不做（见 restoreThemeZip 的 ABSENT 分支）。
        val theme = if (plan.wantsTheme) exportThemeZip(ctx, stage, onLine = onLine) else null
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
                " theme=" + (theme?.length()?.toString() ?: "none") +
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
                theme = theme,
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
                    trace(ctx, "encrypt=chunked fileLen=${merged.length()}")
                    // 分块 GCM：每块独立加密，内存只占一个分块——几百 MB 的主题包也不再 OOM
                    // （旧的单段流式在 Android/Conscrypt 上会把整段密文攒到 doFinal，必爆堆）。
                    DshBackupCrypto.encryptArchiveChunkedToFile(merged, finalFile, plan.password)
                }
            } catch (e: Exception) {
                finalFile.delete()
                return@withContext failTrace(ctx, ctx.appString(R.string.dsh_bk_encrypt_failed, describe(e)))
            }
            // 加密不校验等于没做：直接拿刚写出的文件解一遍，解不回来就删掉并如实报错。
            // 大小校验：v1 单段容器是「头 + 明文」；v2 分块容器每块多 4B 长度 + 16B tag，
            // 所以分块容器只核对「解回来的大小/内容」，不核对容器本身的字节数。
            val verify = verifyEncrypted(ctx, merged, finalFile, plan.password, stage)
            val chunked = DshBackupCrypto.isChunkedContainer(finalFile)
            trace(
                ctx,
                "container bytes=" + finalFile.length() +
                    (if (chunked) " format=chunked" else " expected=" + (DshBackupCrypto.HEADER_LENGTH + merged.length())) +
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
            // 外观是软件数据里最占体积的一块（背景视频能上百 MB），所以带上具体大小 ——
            // 「包里装了什么」这句话里，用户最需要知道的就是它。
            if (stats.theme) {
                append("，").append(ctx.appString(R.string.dsh_bk_out_theme, humanSize(stats.themeBytes)))
            }
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
     * 生成外观主题包（见 [DshBackupArchive.THEME]）。
     *
     * 失败**不阻断导出**：外观拿不到不该让整份备份失败 —— 用户最需要的 DSH 配置、会话
     * 与软件设置都已经在包里了。这里失败只记一笔日志并返回 null，导出结果里会如实写出
     * 「不含外观」，而不是安静地少带一半东西。
     */
    /**
     * 打进包里的主题包元信息。
     *
     * 抽出来是为了让 [measureThemeZipBytes] 量到的字节数与真导出**完全一致** —— 元信息会写进
     * 主题包内的 theme.json，两边若各写一份，量出来的大小就永远差一点。
     */
    private fun themeMetadata(ctx: Context): ThemeManager.ThemeMetadata = ThemeManager.ThemeMetadata(
        name = ctx.appString(R.string.dsh_bk_theme_name),
        type = "phone",
        version = BuildConfig.VERSION_NAME,
        author = "DSH-Folk",
        description = ctx.appString(R.string.dsh_bk_theme_desc),
    )

    /**
     * 量一次主题包有多大（字节）。失败返回 -1。
     *
     * 云备份的「是否包括应用主题」开关要按大小自动给默认值（超过 [THEME_SIZE_LIMIT_BYTES] 默认不勾，
     * 免得同步包被字体/音乐/视频背景撑爆），所以需要一个「现在这个主题打进包有多大」的数字。
     * 走的是与导出同一条通路（同暂存、同加密、同 zip），只是输出写进计数流、不落盘 ——
     * 加密后的数据本来就压不动，落盘对结果没有影响，省掉一次几十 MB 的写入。
     */
    suspend fun measureThemeZipBytes(ctx: Context): Long =
        ThemeIO.measureThemeZip(ctx, themeMetadata(ctx))

    private suspend fun exportThemeZip(
        ctx: Context,
        stage: File,
        /** 落在这个目录下的文件名（两条通路各用各的名字，避免互相覆盖）。 */
        fileName: String = "theme.zip",
        onLine: suspend (String) -> Unit,
    ): File? {
        val out = File(stage, fileName)
        out.delete()
        return try {
            onLine(ctx.appString(R.string.dsh_bk_step_theme_export))
            // exportTheme 内部自己切到 IO，并先清空它自己的暂存目录再写。
            val ok = ThemeManager.exportTheme(ctx, Uri.fromFile(out), themeMetadata(ctx))
            val bytes = out.length()
            trace(ctx, "theme-export ok=" + ok + " bytes=" + bytes)
            if (ok && out.isFile && bytes > 0L) out else null
        } catch (e: Exception) {
            trace(ctx, "theme-export-failed " + describe(e))
            null
        }
    }

    /**
     * 把包里的外观主题包应用回本机。
     *
     * 顺序很要紧：**必须在软件数据之后**。外观参数（自定义主色、首页布局、夜间模式、
     * 导航栏图标）本身就存在 `config` 里，两边都会写这几个键 —— 主题后写，它才是最终
     * 生效的那一份，与用户在导出时看到的外观一致。
     *
     * 还要注意副作用（导入结果里会说清）：主题导入会**替换**本机的音乐与音效
     * （`MusicConfig.clearMusic` + 重新设置），并在包内语言与当前语言不同时改变界面语言
     * （会触发 Activity 重建）。
     *
     * 返回三态：[ThemeOutcome.RESTORED] / [ThemeOutcome.ABSENT]（这个包没有外观）/
     * [ThemeOutcome.FAILED]（解不开或导入失败）。三种都不影响其余部分是否成功。
     */
    private suspend fun restoreThemeZip(ctx: Context, zip: File, stage: File): ThemeOutcome {
        val entry = readEntry(zip, DshBackupArchive.THEME) ?: return ThemeOutcome.ABSENT
        val tmp = File(stage, "theme-restore.zip")
        return try {
            tmp.delete()
            tmp.writeBytes(entry)
            val ok = ThemeManager.importTheme(ctx, Uri.fromFile(tmp))
            trace(ctx, "theme-import ok=" + ok + " bytes=" + entry.size)
            if (ok) ThemeOutcome.RESTORED else ThemeOutcome.FAILED
        } catch (e: Exception) {
            trace(ctx, "theme-import-failed " + describe(e))
            ThemeOutcome.FAILED
        } finally {
            tmp.delete()
        }
    }

    /** 外观恢复的三种结果。 */
    enum class ThemeOutcome { RESTORED, ABSENT, FAILED }

    /** 外观结局的一句话。三种都要说出来：用户问的是「我的背景和字体回来了吗」。 */
    private fun themeNote(ctx: Context, outcome: ThemeOutcome): String = when (outcome) {
        ThemeOutcome.RESTORED -> ctx.appString(R.string.dsh_bk_theme_restored)
        ThemeOutcome.ABSENT -> ctx.appString(R.string.dsh_bk_theme_absent)
        ThemeOutcome.FAILED -> ctx.appString(R.string.dsh_bk_theme_failed)
    }

    /**
     * 追加「按设计没跟着过来」的那一行。
     *
     * 这一行不能省：不写它，用户会以为整份备份都回来了 —— 而权限通道、原生能力档位
     * 恰恰是换机之后最需要重新确认的东西，静默跳过比明确拒绝更危险。
     */
    private fun StringBuilder.appendExcluded(ctx: Context, data: JSONObject, excluded: Int) {
        if (excluded <= 0) return
        append("\n").append(ctx.appString(R.string.dsh_bk_excluded_note, excluded))
        val priv = DshAppData.excludedPrivilegeCount(data)
        if (priv > 0) append("；").append(ctx.appString(R.string.dsh_bk_excluded_privilege, priv))
    }

    /** 读出包里的一个条目（不存在返回 null）。 */
    private fun readEntry(zip: File, name: String): ByteArray? = runCatching {
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (!e.isDirectory && e.name == name) return@runCatching zis.readBytes()
                zis.closeEntry()
            }
            null
        }
    }.getOrNull()

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
        // v2 分块容器有每块的长度前缀 + tag 开销，容器字节数不等于「头 + 明文」，跳过这条；
        // 真正的验证是下面「解回来的大小 + sha256」——那对两种格式都成立。
        if (!DshBackupCrypto.isChunkedContainer(blob) && blob.length() != expected) {
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
    /**
     * 包内的一条冲突。
     *
     * 带 [id] 是因为逐条决策要它：插件的 `decisions.resolutions` 是
     * `{ 计划项 id: keepCurrent | useImported }`，没有 id 就只能整包选一个策略。
     */
    data class ConflictItem(
        val id: String,
        val adapter: String,
        val description: String,
        /** 计划项自带的冲突建议（插件 plan item 的 `conflict.resolution`），没有就是 review。 */
        val suggestion: String = "",
    ) {
        /** 列表里显示成一行。 */
        fun line(): String = if (adapter.isEmpty()) description else "$adapter: $description"
    }

    /**
     * 插件 `/analyze` 的摘要（向导的「分析」与「预览」两步都读它）。
     *
     * 字段名与插件 `ImportAnalysis` 一一对应，只挑界面用得上的：分区数、插件安装情况、
     * 凭据条数、路径问题数、兼容性与警告。`unsupportedSections` / `unsupportedVersions`
     * 这类更细的结构化信息不单独搬 —— 它们已经被插件写进了 `warnings` 文案里。
     */
    data class Analysis(
        val compatibility: String = "",
        val sections: Int = 0,
        val pluginsInstalled: Int = 0,
        val pluginsToInstall: Int = 0,
        val secretCount: Int = 0,
        val pathIssues: Int = 0,
        val encrypted: Boolean = false,
        val warnings: List<String> = emptyList(),
    )

    /** 计划里的一项（预览页只列出会改动的那些）。 */
    data class PlanItemLite(
        val id: String,
        val kind: String,
        val adapter: String,
        val description: String,
    )

    /**
     * 试规划之后的摘要（向导的「预览」与「决策」两步读它）。
     *
     * 计数口径与插件 Web UI 一致：`willChange` 只算真的会动东西的项
     * （Create/Update/Install/Conflict），`unchanged` 是 Skip。这样「这次会改动 N 项」
     * 与插件界面上的数字对得上，用户在两边看到的不是两个说法。
     */
    data class PlanSummary(
        val items: List<PlanItemLite> = emptyList(),
        val willChange: Int = 0,
        val unchanged: Int = 0,
        val installs: Int = 0,
        val conflicts: Int = 0,
        val needsRestart: Boolean = false,
        val missingSecrets: List<String> = emptyList(),
        val estimatedSections: Int = 0,
    )

    data class Preflight(
        /** 插件侧的已上传路径；纯软件数据包为空（那种包不进插件流程）。 */
        val zipPath: String,
        /** 本地明文包：会话与软件数据都从它读；等于用户选的文件时就是文件本身。 */
        val plainZip: File,
        /** 包内会话文件数。 */
        val sessions: Int,
        /** 冲突条目（最多 [MAX_CONFLICT_LIST] 条，供向导逐条列出并决策）。 */
        val conflicts: List<ConflictItem>,
        /** 冲突总数（列表被截断时仍然如实报数）。 */
        val conflictTotal: Int,
        /** 有没有需要插件出面的 DSH 分区（纯软件数据包为 false）。 */
        val needsDsh: Boolean,
        /** 插件 `/analyze` 的摘要（纯软件数据包为 null）。 */
        val analysis: Analysis? = null,
        /** 试规划的结果（纯软件数据包为 null；插件不给计划时为 null）。 */
        val plan: PlanSummary? = null,
        /**
         * 纯软件数据包的内容摘要（有 DSH 分区时为 null）。
         *
         * 类型就是 [DshAppData.Summary] —— 那是「这份包里的软件数据长什么样」的唯一
         * 定义；在这里再声明一个同形状的类，只会多一处需要跟它同步的地方。
         *
         * 这类包不进插件流程（一个分区都没有），所以没有分析也没有计划，但预览步不能
         * 因此变成空白：用户此刻最需要看清的恰恰是「设置带了几项、外观在不在、
         * 有多少项按设计没跟着来」。
         */
        val appData: DshAppData.Summary? = null,
        /** 这个包是不是加密的（决定「确认」步要不要提醒密码只在这次会话里）。 */
        val encrypted: Boolean = false,
        /**
         * 包里带没带外观主题包，以及它多大（-1 = 没有）。
         *
         * 与 [appData] 不同：这个字段对**两种包**都有效。混合包（软件数据 + DSH 分区）
         * 同样会带上 `dsh-folk/theme.zip`，而恢复外观会替换背景/字体/音乐/音效、还可能
         * 切换应用语言 —— 这类副作用必须在确认之前说出来，不能因为「有 DSH 分区」就不提。
         */
        val themeBytes: Long = -1L,
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
            // 纯软件数据包：没有分区要恢复，也就不存在冲突。这里给出**内容摘要**，
            // 让向导的预览步有东西可说（设置带了几项、外观在不在、排除了多少项）。
            val summary = DshAppData.summarize(plainZip)
            return@withContext PreflightResult.Ready(
                Preflight(
                    zipPath = "",
                    plainZip = plainZip,
                    sessions = sessions,
                    conflicts = emptyList(),
                    conflictTotal = 0,
                    needsDsh = false,
                    appData = summary,
                    encrypted = plainZip != zip,
                    // 摘要里的外观尺寸是流式数出来的（准）；中央目录里那个可能是 0。
                    themeBytes = if (summary.theme) summary.themeBytes else -1L,
                ),
            )
        }
        onLine(ctx.appString(R.string.dsh_bk_step_uploading, plainZip.name))
        // 大外观包拎出去再上传（见 [managerZipOf]）：dsh-config-manager 有 100MB 单条目上限
        val (managerZip, managerTmp) = managerZipOf(ctx, plainZip)
        val up = upload(managerZip)
        managerTmp?.delete()
        if (up == null) return@withContext PreflightResult.Failed(ctx.appString(R.string.dsh_bk_upload_failed))
        val upObj = runCatching { JSONObject(up) }.getOrNull()
            ?: return@withContext PreflightResult.Failed(ctx.appString(R.string.dsh_bk_upload_bad_json))
        val zipPath = upObj.optString("zipPath")
        if (zipPath.isEmpty()) {
            return@withContext PreflightResult.Failed(
                upObj.optString("error").ifEmpty { ctx.appString(R.string.dsh_bk_upload_no_path) },
            )
        }
        onLine(ctx.appString(R.string.dsh_bk_step_analyzing))
        // 带上解密密码：整包由我们解开后交给插件的是明文包，但包内 security/secrets.enc 仍用
        // 同一密码加密。0.1.64 起 /analyze 认 decryptPassword，据此把「只存在于 secrets.enc、
        // 未被 credentialsStatus 声明」的凭据 ref 也算进可恢复集（真机反馈：不带密码这些密钥
        // 导入不生效）。不加密的包 password 为空，行为不变。
        val analyze = request(
            "POST", "/analyze",
            JSONObject().put("zipPath", zipPath)
                .apply { if (password.isNotEmpty()) put("decryptPassword", password) }
                .toString(),
        ) ?: return@withContext PreflightResult.Failed(ctx.appString(R.string.dsh_bk_analyze_failed))
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
        // 试规划：默认策略下未决策的冲突项保持 kind == "Conflict"，数一下就知道要不要问。
        // pathMappings 传空：0.1.64 插件会用 manifest.sourceHome 自动生成「导出机 home → 本机
        // home」的重定基规则并作用于结构化分区，我们不必也不该重复传（会话由 App 独占归组）。
        val dryPlan = request(
            "POST", "/plan",
            JSONObject()
                .put("zipPath", zipPath)
                .apply { if (password.isNotEmpty()) put("decryptPassword", password) }
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
        val conflicts = mutableListOf<ConflictItem>()
        var total = 0
        for (i in 0 until (items?.length() ?: 0)) {
            val item = items?.optJSONObject(i) ?: continue
            if (item.optString("kind") != "Conflict") continue
            total++
            if (conflicts.size < MAX_CONFLICT_LIST) {
                conflicts += ConflictItem(
                    id = item.optString("id"),
                    adapter = item.optString("adapter"),
                    description = item.optString("description").ifEmpty { item.optString("id") },
                    suggestion = item.optJSONObject("conflict")?.optString("resolution").orEmpty(),
                )
            }
        }
        trace(ctx, "import-preflight conflicts=" + total + " listed=" + conflicts.size)
        PreflightResult.Ready(
            Preflight(
                zipPath = zipPath,
                plainZip = plainZip,
                sessions = sessions,
                conflicts = conflicts,
                conflictTotal = total,
                needsDsh = true,
                analysis = analysisOf(analyzeObj),
                plan = planSummaryOf(dryPlan),
                encrypted = plainZip != zip,
                // 混合包也会带外观主题包（导出时「含软件数据」那一档就会打一份），
                // 而恢复外观有副作用（替换背景/字体/音乐，可能换语言），确认页必须能说出来。
                themeBytes = DshBackupArchive.entrySize(plainZip, DshBackupArchive.THEME),
            ),
        )
    }

    /**
     * 把插件 `/analyze` 的响应读成 [Analysis]。
     *
     * 一个字段读不出来不影响其余：插件版本比我们新或旧时，界面该显示的部分照常显示，
     * 缺的那一项显示为 0 / 空而不是让整次预检失败。
     */
    private fun analysisOf(obj: JSONObject): Analysis {
        val summary = obj.optJSONObject("pluginSummary")
        val pathIssues = obj.optJSONArray("pathIssues")
        val warnings = obj.optJSONArray("warnings")
        return Analysis(
            compatibility = obj.optString("compatibility"),
            sections = obj.optJSONArray("sectionsInZip")?.length() ?: 0,
            pluginsInstalled = summary?.optInt("installed") ?: 0,
            pluginsToInstall = summary?.optInt("toInstall") ?: 0,
            secretCount = obj.optInt("secretCount"),
            pathIssues = pathIssues?.length() ?: 0,
            encrypted = obj.optBoolean("encrypted"),
            warnings = (0 until (warnings?.length() ?: 0))
                .mapNotNull { warnings?.optString(it)?.takeIf { s -> s.isNotEmpty() } },
        )
    }

    /**
     * 把插件 `/plan` 的响应读成 [PlanSummary]。
     *
     * 计数口径照插件 Web UI：会改动 = Create + Update + Install + Conflict；Skip 归入
     * 「保持不变」。`estimatedActions` 是插件按分区给的预估动作数，这里只取分区个数 ——
     * 界面要说的是「涉及 N 个分区」，不是再造一张逐分区的表。
     */
    private fun planSummaryOf(plan: JSONObject?): PlanSummary? {
        plan ?: return null
        val items = plan.optJSONArray("items") ?: return null
        val list = mutableListOf<PlanItemLite>()
        var willChange = 0
        var unchanged = 0
        var installs = 0
        var conflicts = 0
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val kind = item.optString("kind")
            val lite = PlanItemLite(
                id = item.optString("id"),
                kind = kind,
                adapter = item.optString("adapter"),
                description = item.optString("description").ifEmpty { item.optString("id") },
            )
            when (kind) {
                "Skip" -> unchanged++
                "Create", "Update", "Install", "Conflict" -> willChange++
            }
            if (kind == "Install") installs++
            if (kind == "Conflict") conflicts++
            // 预览只列会动的东西：把「保持不变」的几十项也铺出来，真正要看的就被淹了
            if (kind != "Skip" && list.size < MAX_PREVIEW_ITEMS) list += lite
        }
        val missing = plan.optJSONArray("missingSecrets")
        return PlanSummary(
            items = list,
            willChange = willChange,
            unchanged = unchanged,
            installs = installs,
            conflicts = conflicts,
            needsRestart = plan.optBoolean("needsRestart"),
            missingSecrets = (0 until (missing?.length() ?: 0)).mapNotNull { i ->
                missing?.optJSONObject(i)?.optString("ref")?.takeIf { it.isNotEmpty() }
            },
            estimatedSections = plan.optJSONObject("estimatedActions")?.length() ?: 0,
        )
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

    /** 预检里最多列几条冲突（向导列清单，不把上百条塞进去）。 */
    private const val MAX_CONFLICT_LIST = 8

    /** 预览页最多列几条会改动的计划项（剩下的只报数）。 */
    private const val MAX_PREVIEW_ITEMS = 30

    /** 导入时会话怎么处理 —— 就是用户在弹窗里选的那一项。 */
    enum class SessionImport {
        /** 只恢复配置，不动会话（会话计划项从交给插件的计划里剔除）。 */
        SKIP,

        /** 恢复会话：会话计划项保留，交给插件在 /execute 里写文件 + 改首帧 cwd + 归位 + 登记。 */
        RESTORE,
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
     * 这份备份包里的**凭据原文**情况 —— 它才是「凭据能不能恢复」的唯一判据。
     *
     * 链路（已核对插件源码）：插件的 /execute 拿我们传的 decryptPassword 去解
     * security/secrets.enc，把 YAML 顶层字符串项收成 Map&lt;键, 值&gt;（键就是
     * DEEPSEEK_API_KEY 这类 env 名），凡是能从这个 Map 里拿到值的凭据就不会出现在
     * 待补录清单里。
     *
     * 所以：**导出时勾了「含 vault」→ 包里是凭据原文 → 能自动恢复；没勾 → 包里是空
     * 占位（manifest 仍是 encrypted=true）→ 没有值可恢复，只能人工重填。**
     * 插件还会另报一句「凭据文件 .credentials.yaml 不在本机 vault」—— 那是它的**本机
     * 镜像**（导出时在同机留的副本），跨机必然缺，和上面的判断是两回事，容易误读成
     * 「凭据没恢复」。这里把两件事分开算清楚。
     */
    data class SecretsInfo(
        /** manifest.security.encrypted（设了密码就是 true）。 */
        val encrypted: Boolean = false,
        /** manifest.security.containsSecrets（导出时勾了「含 vault」才是 true）。 */
        val containsSecrets: Boolean = false,
        /** 包里存在 security/secrets.enc。 */
        val filePresent: Boolean = false,
        /** 用给出的密码解得开。 */
        val decrypted: Boolean = false,
        /** 解出来的凭据键（env 名）。 */
        val keys: List<String> = emptyList(),
        /** 凭据键 → 值（导入时用 secretInputs 直接交给插件）。 */
        val refs: Map<String, String> = emptyMap(),
    )

    /** 读 manifest 与 security/secrets.enc，判断这份包里有没有真的凭据原文。 */
    fun secretsInfoInZip(zip: File, password: String): SecretsInfo = runCatching {
        var encrypted = false
        var containsSecrets = false
        var blob: ByteArray? = null
        var salt = ""
        var iv = ""
        var tag = ""
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                when {
                    !e.isDirectory && e.name == DshBackupArchive.MANIFEST -> {
                        val sec = runCatching {
                            JSONObject(zis.readBytes().toString(StandardCharsets.UTF_8))
                                .optJSONObject("security")
                        }.getOrNull()
                        if (sec != null) {
                            encrypted = sec.optBoolean("encrypted", false)
                            containsSecrets = sec.optBoolean("containsSecrets", false)
                            val enc = sec.optJSONObject("encryption")
                            if (enc != null) {
                                salt = enc.optString("salt")
                                iv = enc.optString("iv")
                                tag = enc.optString("authTag")
                            }
                        }
                    }
                    !e.isDirectory && e.name == DshBackupArchive.SECRETS -> {
                        blob = runCatching { zis.readBytes() }.getOrNull()
                    }
                }
                zis.closeEntry()
            }
        }
        val bytes = blob
        if (bytes == null || !encrypted || password.isEmpty()) {
            return@runCatching SecretsInfo(encrypted, containsSecrets, bytes != null)
        }
        val yaml = DshBackupCrypto.decryptSecrets(bytes, salt, iv, tag, password)
            ?: return@runCatching SecretsInfo(encrypted, containsSecrets, true, decrypted = false)
        val refs = credentialRefs(yaml)
        SecretsInfo(encrypted, containsSecrets, true, decrypted = true, keys = refs.keys.toList(), refs = refs)
    }.getOrDefault(SecretsInfo())

    /**
     * 凭据 YAML 里的「ref → 值」。
     *
     * 为什么要 App 自己取：容器的 .credentials.yaml 把凭据放在**顶层 refs: 下面**
     * （实测形状：
     *     version: 1
     *     records:
     *       client-connection/browser-session: …
     *     refs:
     *       RJK66_API_KEY: sk-…
     *       DEEPSEEK_API_KEY: sk-…
     * ），而插件收集凭据时只看**顶层字符串项**（Object.entries + typeof v === 'string'）
     * —— refs 是个对象，于是整段被跳过。结果就是「明明勾了含 vault、包里也确实有原文，
     * 导入时照样让你重填」。
     *
     * 插件其实留了另一条通道：MissingSecret 的 applyItem 是
     *     ctx.secretInputs[ref] ?? decryptedCredentials?.get(ref) → credentials.set(ref, value)
     * 所以把 refs 里的值经 /execute 的 opts.secretInputs 喂回去，它就会真的写进去。
     *
     * **这条通道是给 0.1.59 及更旧插件用的兼容层**：插件 0.1.60 起自己会认 refs: 块
     * （src/core/credentials-file.ts），届时这里只是重复提供同样的值（secretInputs 优先级
     * 更高、值一致，无害）。别把这里当成唯一修复 —— 根因在插件侧。
     *
     * 解析：顶层单个 KEY: 值（只认 env 风格的键名）＋ 顶层 refs: 块下缩进一层的键值。
     * 其余嵌套结构不算凭据（records 里的会话秘密之类不该被当成 ref）。
     */
    private fun credentialRefs(yaml: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var block = ""
        for (raw in yaml.lineSequence()) {
            val line = raw.trimEnd()
            val body = line.trim()
            if (body.isEmpty() || body.startsWith("#") || body.startsWith("-")) continue
            val indent = line.length - line.trimStart().length
            val i = body.indexOf(':')
            if (i <= 0) continue
            val key = body.substring(0, i).trim()
            val value = body.substring(i + 1).trim().trim('"', '\'')
            if (indent == 0) {
                block = if (value.isEmpty()) key else ""
                // 顶层标量：只认 env 风格的键名（version: 1 这类别混进来）
                if (value.isNotEmpty() && ENV_KEY.matches(key)) out[key] = value
            } else if (block == REFS_BLOCK && value.isNotEmpty() && !key.contains(' ')) {
                out[key] = value
            }
        }
        return out
    }

    /** env 风格的键名（DEEPSEEK_API_KEY、RJK66_API_KEY…）。 */
    private val ENV_KEY = Regex("^[A-Z][A-Z0-9_]*$")

    /** 凭据值所在的顶层块名（实测容器的 .credentials.yaml 就用这个）。 */
    private const val REFS_BLOCK = "refs"

    /**
     * 备份包引用的**工作区目录**（容器内绝对路径）。
     *
     * 为什么需要它：插件的 workspaces 适配器写记录时要对路径做 realpath，目录不存在就
     * 直接抛 ENOENT，而它按 §34.17 把这种情况当**非致命警告**（不触发回滚）—— 结果就是
     * 「配置都导进来了，只有工作区没写进去」，随后 App 的会话归组找不到对应工作区，
     * 会话只能落单。插件自己在报错里写了修法：**先在目标创建目录**。
     *
     * 数据来源是包里的 workspaces 分区，位置由插件定死：
     * `workspaces/workspaces.json`（`SECTION_JSON_PATHS.workspaces`），形状是
     * `{version:1, workspaces:[{id, path, …}]}`；落盘那份是
     * `~/.dsh/storages/workspace.json` 的 `tables.workspaces`，两种形状都认。
     *
     * ## 不能用条目尺寸做门槛
     *
     * 这里曾经要求 `e.size in 1..2MB`，本意是「只读小文件、别把几百 MB 的会话读进内存」。
     * 但 App 自己合并包时是 `zos.putNextEntry(ZipEntry(name))`（不预设尺寸），Java 的
     * `ZipOutputStream` 于是走 data descriptor，**本地头里的 size 字段是 0** ——
     * `ZipInputStream.getSize()` 拿到的就是 0，判据恒假，**一个条目都读不到**。
     * 症状极具迷惑性：补建目录那一步整段不执行（报告里连「已补建 N 个」这一行都没有），
     * 随后插件照旧报 ENOENT，看起来像「补建逻辑写了但没生效」。
     * 现在改为按**条目名精确匹配**分区，尺寸只从中央目录取（读不到也不影响判断）。
     */
    fun workspacePathsInZip(
        zip: File,
        /**
         * 诊断回调（可选）。它的存在本身就是这次的教训：上面那段判据失效时，界面与日志里
         * **一点痕迹都没有** —— 报告里连「已补建 N 个」那行都不出现，看起来像「没写这个功能」。
         * 有它才能在 bugreport 里看到「分区在不在、读到多少字节、认出几条路径」。
         */
        onNote: ((String) -> Unit)? = null,
    ): Pair<List<String>, String> = runCatching {
        val found = mutableListOf<String>()
        var entryName = ""
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (!e.isDirectory && isWorkspacesEntry(e.name)) {
                    // 只读这一个条目，且它是小 JSON（几十 KB 量级）；真读到异常就整体放弃，
                    // 不猜路径 —— 建目录是不可逆的外部副作用。
                    val text = runCatching { zis.readBytes() }.getOrNull()
                        ?.toString(StandardCharsets.UTF_8)
                    if (text != null && looksLikeWorkspaces(text)) {
                        entryName = e.name
                        found += pathsFromWorkspacesJson(text)
                        onNote?.invoke("workspaces-entry=" + e.name + " bytes=" + text.length + " paths=" + found.size)
                    } else {
                        onNote?.invoke(
                            "workspaces-entry-unrecognized=" + e.name +
                                " bytes=" + (text?.length ?: -1),
                        )
                    }
                    break
                }
                zis.closeEntry()
            }
        }
        if (entryName.isEmpty()) onNote?.invoke("workspaces-entry-missing")
        found.distinct() to entryName
    }.getOrDefault(emptyList<String>() to "")

    /**
     * 这个条目名是不是工作区分区。
     *
     * 精确匹配插件定死的位置，外加一个宽松兜底（目录名就叫 workspaces 的 json）：
     * 插件改了布局也不会静默失效 —— 那正是这次的教训。
     */
    private fun isWorkspacesEntry(name: String): Boolean {
        if (name == WORKSPACES_ENTRY) return true
        val base = name.substringAfterLast('/')
        return name.contains("/workspaces/") && base.endsWith(".json")
    }

    /** 插件把工作区分区放在这个条目（src/schema/config.ts 的 SECTION_JSON_PATHS）。 */
    private const val WORKSPACES_ENTRY = "workspaces/workspaces.json"

    /** 这份 JSON 是不是工作区分区（section 形状或落盘形状都算）。 */
    private fun looksLikeWorkspaces(text: String): Boolean =
        text.contains("\"workspaces\"") && text.contains("\"path\"")

    /** 从工作区分区 JSON 里取出所有 path 字段。 */
    private fun pathsFromWorkspacesJson(text: String): List<String> = runCatching {
        val root = JSONObject(text)
        val arr = root.optJSONArray("workspaces")
            ?: root.optJSONObject("tables")?.optJSONArray("workspaces")
            ?: return@runCatching emptyList()
        (0 until arr.length()).mapNotNull { i ->
            (arr.opt(i) as? JSONObject)?.optString("path")?.ifEmpty { null }
        }
    }.getOrDefault(emptyList())

    /**
     * 一个容器内绝对路径对应的设备路径。
     *
     * rootfs 根就是容器根（容器里的 /root/.dsh 对应 rootfs/root/.dsh，见 DshEnv）。
     * 只接受干净的绝对路径：带 .. 段、不绝对、或规范化后逃出 rootfs 的一律拒绝 ——
     * 备份是外部输入，不能拿它当「随便往哪写」的许可。
     */
    private fun containerPathToDevice(ctx: Context, containerPath: String): File? {
        val p = containerPath.trim()
        if (!p.startsWith("/") || p == "/") return null
        if (p.split('/').any { it == ".." }) return null
        val root = DshEnv.rootfs(ctx)
        val target = File(root, p.trimStart('/'))
        val rootCanon = runCatching { root.canonicalPath }.getOrNull() ?: return null
        val targetCanon = runCatching { target.canonicalPath }.getOrNull() ?: return null
        if (targetCanon != rootCanon && !targetCanon.startsWith(rootCanon + File.separator)) return null
        return target
    }

    /** 补建目录的结果明细（给日志与界面用）。 */
    data class DirFixResult(
        val created: List<String> = emptyList(),
        val existing: List<String> = emptyList(),
        /** 非法路径（不绝对 / 含 .. / 逃出 rootfs）。 */
        val rejected: List<String> = emptyList(),
        /** 建失败的原因（"路径: 原因"）。 */
        val failed: List<String> = emptyList(),
    ) {
        val changed: Boolean get() = created.isNotEmpty()
    }

    /**
     * 导入前把包里引用、目标端却不存在的工作区目录补出来。
     *
     * 必须在插件 /import-apply **之前**跑：插件写工作区记录在前、App 会话归组在后，
     * 事后补目录已经晚了（那正是「会话归组 0/1」的现场）。
     * 幂等：已存在的只记一笔跳过；失败不改既有行为（插件那条警告照旧）。
     */
    suspend fun ensureWorkspaceDirs(ctx: Context, paths: List<String>): DirFixResult = withContext(Dispatchers.IO) {
        val created = mutableListOf<String>()
        val existing = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (path in paths.distinct()) {
            val target = containerPathToDevice(ctx, path)
            if (target == null) {
                rejected += path
                continue
            }
            when {
                target.isDirectory -> existing += path
                target.exists() -> failed += "$path: 目标已存在但不是目录，没有覆盖"
                target.mkdirs() || target.isDirectory -> created += path
                else -> failed += "$path: 创建失败（权限或只读挂载？）"
            }
        }
        DirFixResult(created, existing, rejected, failed)
    }

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
        // 插件侧快照删掉后，我们那份软件设置副本也没有意义了（留着就是孤儿目录）
        DshAppDataSnapshot.delete(ctx, snapshotId)
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
        /**
         * 需要重启才生效的**具体项**。
         *
         * 插件当前的核心结果里只有 `needsRestart` 这个布尔量（逐项清单是它的 Web UI 自己
         * 推出来的），所以这里按 `restartItems` 字段读，读到就没有 —— 那时界面只显示
         * 「需要重启服务」这一句。写成字段而不是写死一句话，是为了插件哪天补上清单时
         * 我们不用改协议。
         */
        val restartItems: List<String> = emptyList(),
        /** 插件报「缺凭据」的项：导入本身成功，但运行时用不了，需要用户补上。 */
        val missingSecrets: List<String> = emptyList(),
        /** 插件顶层 warnings（非致命，但用户该知道）。 */
        val warnings: List<String> = emptyList(),
        /** 失败项、回滚失败项、被删除墓碑挡掉的项 —— 结果页的「还需要处理」直接列这些。 */
        val unresolved: List<String> = emptyList(),
        /** 插件在 DSH 里留下的回滚快照 id（有的话结果页会写出来，用户可以据此回退）。 */
        val snapshotId: String = "",
        /** 外观主题包在这次导入里的结局（null = 这次导入没走软件数据那条路）。 */
        val theme: ThemeOutcome? = null,
        /** 按设计被跳过的提权项数量（[DshAppData.PRIVILEGE_KEYS]），结果页要如实说明。 */
        val privilegeSkipped: Int = 0,
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
     * @param resolutions 逐条冲突决策：计划项 id → `keepCurrent` / `useImported`。向导里
     *        用户对每条冲突都表了态才走到这里；空表就是「按 strategy 统一处理」。
     * @param rollbackOnError 任一项失败时是否整体回滚（默认开）。**必须显式传**：插件那边
     *        是 `=== true` 的严格判断，漏传等于关掉回滚。
     * @param excludedItems 用户在预览页取消勾选的计划项 id。**排除式**：只有明确列出的 id
     *        会被剔除，其余（含因展示上限没列出来的）照常导入。插件引擎支持只跑子集
     *        （它自己的「重试失败项」就是 `executeImportPlan(zip, {...plan, items: subset})`），
     *        所以这里直接过滤计划项，不需要插件额外配合。
     * @param password 加密备份的解锁密码
     * @param sessions 会话怎么办（[SessionImport]）：RESTORE = 保留在计划里交给插件恢复+归位，
     *        SKIP = 从计划里剔除会话项。0.1.64 起会话由插件完整处理（写文件/改 cwd/归位/登记）。
     * @param onLine 阶段进度（上传/分析/计划/执行/软件数据）。
     */
    suspend fun import(
        ctx: Context,
        zip: File,
        strategy: String = "merge",
        resolutions: Map<String, String> = emptyMap(),
        rollbackOnError: Boolean = true,
        excludedItems: Set<String> = emptySet(),
        password: String = "",
        sessions: SessionImport = SessionImport.RESTORE,
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
            val applied = DshAppData.apply(ctx, data)
            trace(ctx, "import-appdata changed=" + applied.changed + " files=" + applied.files)
            // 外观必须在软件数据**之后**落地：两边都会写那几个外观参数（自定义主色、
            // 首页布局、夜间模式、导航栏图标都在 config 里），主题后写才是最终生效的那份。
            val theme = restoreThemeZip(ctx, plainZip, tmpDir)
            // 审计要**再读一次包**，所以删除必须放在它后面（先删会让这一步对着空气空跑）
            val lines = DshAppData.mergeAudit(ctx, plainZip)
            if (plainZip != zip) plainZip.delete()
            val note = buildString {
                append(ctx.appString(R.string.dsh_bk_appdata_restored, applied.changed))
                if (lines > 0) append("，").append(ctx.appString(R.string.dsh_bk_audit_merged, lines))
                append("，").append(themeNote(ctx, theme))
                appendExcluded(ctx, data, applied.excluded)
                append("\n").append(ctx.appString(R.string.dsh_bk_appdata_takes_effect))
            }
            return@withContext ImportResult(
                ok = true,
                message = ctx.appString(R.string.dsh_bk_import_done, note),
                theme = theme,
                privilegeSkipped = DshAppData.excludedPrivilegeCount(data),
            )
        }

        var zipPath = preflight?.zipPath.orEmpty()
        var containerType = ""
        if (zipPath.isEmpty()) {
            onLine(ctx.appString(R.string.dsh_bk_step_uploading, plainZip.name))
            // 大外观包拎出去再上传（见 [managerZipOf]）：dsh-config-manager 有 100MB 单条目上限
            val (managerZip, managerTmp) = managerZipOf(ctx, plainZip)
            val up = upload(managerZip)
            managerTmp?.delete()
            if (up == null) return@withContext ImportResult(false, ctx.appString(R.string.dsh_bk_upload_failed))
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
        // decryptPassword 同预检：让插件把 secrets.enc 里的凭据算进可恢复集（见预检处注释）。
        val analyze = request(
            "POST", "/analyze",
            JSONObject().put("zipPath", zipPath)
                .apply { if (password.isNotEmpty()) put("decryptPassword", password) }
                .toString(),
        ) ?: return@withContext ImportResult(false, ctx.appString(R.string.dsh_bk_analyze_failed))
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
            // 逐条冲突决策（向导里「这条冲突保留本机还是用包里的」）。键是**计划项 id**，
            // 取值只有 keepCurrent / useImported 两种（插件 core/types.ts ItemResolution）。
            // 插件不认 resolutions 里没提到的项时按 strategy 兜底，所以没逐条表态的项
            // 不需要在这里补默认值。
            put("resolutions", JSONObject().apply { for ((id, r) in resolutions) put(id, r) })
            put("pathMappings", JSONArray())
        }
        onLine(ctx.appString(R.string.dsh_bk_step_planning, strategy))
        val plan = request(
            "POST", "/plan",
            JSONObject().put("zipPath", zipPath)
                .apply { if (password.isNotEmpty()) put("decryptPassword", password) }
                .put("decisions", decisions)
                .toString(),
        ) ?: return@withContext ImportResult(false, ctx.appString(R.string.dsh_bk_plan_failed))
        val planObj = runCatching { JSONObject(plan) }.getOrNull()
            ?: return@withContext ImportResult(false, ctx.appString(R.string.dsh_bk_plan_bad_json))
        val planErr = planObj.optString("error")
        if (planErr.isNotEmpty()) return@withContext ImportResult(false, planErr)

        // 会话恢复交给插件（0.1.64 起 sessions 进了执行清单，SessionsAdapter 会真的写会话
        // 文件、按 pathMappings 改首帧 cwd、用自带的 projectKeyOf 算目标目录并归位、收尾再用
        // 官方 attachSession 登记 —— 连 App 根本算不出的目标 projectKey 哈希它都能算）。所以
        // App 不再自己恢复/归组会话，只在**用户选择不恢复**时按 adapter == "sessions" 把会话
        // 计划项剔除（结构化判据，不猜 id 前缀）。用户在预览页取消勾选的项一并按 id 剔除。
        val dropSessions = sessions == SessionImport.SKIP
        run {
            val arr = planObj.optJSONArray("items")
            val before = arr?.length() ?: 0
            val kept = JSONArray()
            var removedSessions = 0
            var removedByUser = 0
            for (i in 0 until before) {
                val item = arr?.optJSONObject(i) ?: continue
                if (dropSessions && item.optString("adapter") == "sessions") {
                    removedSessions++
                    continue
                }
                if (item.optString("id") in excludedItems) {
                    removedByUser++
                    continue
                }
                kept.put(item)
            }
            planObj.put("items", kept)
            trace(
                ctx,
                "import-plan-filtered sessions=" + removedSessions + " byUser=" + removedByUser +
                    " kept=" + kept.length() + " of=" + before,
            )
        }

        // 软件设置的导入前快照。
        //
        // **必须在 /execute 之前采集**，而且只能在这里：插件侧的 DSH 快照按 SECTION_IDS
        // 采集，够不到 App 的 SharedPreferences 与外观资源文件，所以「恢复快照」一直只回
        // DSH 配置、软件设置停在导入后的样子（真机反馈）。这里先把当前值拍下来，等 /execute
        // 返回快照 id 后再落盘 —— 快照 id 那时才存在。
        val appDataForSnapshot = DshAppData.collect(ctx)
        // 外观同理：当前背景/字体/音乐/音效打进一个主题包（与备份包里那份同一套通路）。
        // 进度回调只落日志 —— 这时用户还在「正在恢复」那一步，插不进额外的界面文案。
        val themeForSnapshot = exportThemeZip(ctx, tmpDir, "snapshot-theme.zip") { note ->
            trace(ctx, "snapshot-theme " + note)
        }

        val opts = JSONObject().apply {
            put("confirm", true)
            // 插件侧是**严格判断**（`opts['rollbackOnError'] === true`），漏传等于「不回滚」，
            // 所以这里永远显式写。用户在向导的确认步取消勾选时才写 false。
            put("rollbackOnError", rollbackOnError)
            if (password.isNotEmpty()) put("decryptPassword", password)
        }
        // 补建缺失的工作区目录：必须赶在插件 /execute 之前。插件写工作区记录时会对路径
        // realpath，目录不存在就只留一条非致命警告（§34.17）；插件随后归位会话时也按
        // workspace.json 匹配 cwd —— 目录没建起来，会话就只能落单。
        //
        // 凭据现在**完全交给插件**：opts 里的 decryptPassword（上面）让插件自己解 secrets.enc、
        // 把包里的凭据（含只在 refs 块里、未在 credentialsStatus 声明的那些）收成计划项并回填。
        // 早先 App 还会自己解密再把值经 secretInputs 转交插件（老插件读不到 refs 块的兜底），
        // 0.1.64 起这条已冗余（executeImportPlan 里 decryptedCredentials 优先于 secretInputs），
        // 故删除，避免两处各解一遍同一个 secrets.enc。这里只保留一次只读扫描，用于结果页那句
        // 「归档里带了 N 条凭据 / 只是占位」的说明 —— 那是读包告知，不参与写入。
        val secretsInfo = secretsInfoInZip(plainZip, password)
        trace(
            ctx,
            "import-secrets encrypted=" + secretsInfo.encrypted +
                " contains=" + secretsInfo.containsSecrets +
                " file=" + secretsInfo.filePresent +
                " decrypted=" + secretsInfo.decrypted +
                " keys=" + secretsInfo.keys.size,
        )
        var notesForDirs = ""
        // 诊断直通日志：这条链路一旦静默失效（判据写错时一个路径都读不到），报告里就只剩
        // 插件那条 ENOENT 警告，完全看不出「本来可以补建却没有」。回调不是 suspend
        // （读 zip 是纯 IO，不该被挂起语义绑住），所以先收进列表再逐条落日志。
        val dirNotes = mutableListOf<String>()
        val (wantedDirs, dirsSource) = workspacePathsInZip(plainZip) { dirNotes += it }
        for (note in dirNotes) trace(ctx, note)
        if (wantedDirs.isEmpty()) {
            trace(ctx, "import-dirs-none source=" + dirsSource)
        }
        if (wantedDirs.isNotEmpty()) {
            onLine(ctx.appString(R.string.dsh_bk_step_prepare_dirs, wantedDirs.size))
            val fix = ensureWorkspaceDirs(ctx, wantedDirs)
            trace(
                ctx,
                "import-ensure-dirs source=" + dirsSource +
                    " wanted=" + wantedDirs.size +
                    " created=" + fix.created.size +
                    " existing=" + fix.existing.size +
                    " rejected=" + fix.rejected.size +
                    " failed=" + fix.failed.size,
            )
            for (p in fix.created) trace(ctx, "import-dir-created " + p)
            for (p in fix.rejected) trace(ctx, "import-dir-rejected " + p)
            for (p in fix.failed) trace(ctx, "import-dir-failed " + p)
            if (fix.created.isNotEmpty()) {
                notesForDirs = ctx.appString(R.string.dsh_bk_import_dirs_created, fix.created.size, fix.created.joinToString("、"))
            } else if (fix.failed.isNotEmpty() || fix.rejected.isNotEmpty()) {
                notesForDirs = ctx.appString(
                    R.string.dsh_bk_import_dirs_failed,
                    (fix.failed + fix.rejected).joinToString("；"),
                )
            }
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
        // 结果页的「还需要处理」直接列这几组，不再让用户从整段日志里自己挑
        val unresolved = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val missingSecrets = mutableListOf<String>()
        // 补建目录的结果放在最前面：它是解释「为什么这次会话进得了工作区」的那句话
        if (notesForDirs.isNotEmpty()) notes.append(notesForDirs).append("\n")
        // 凭据那句话同样要紧：它区分「包里没有凭据」与「插件说的本机镜像不在」
        when {
            secretsInfo.decrypted && secretsInfo.keys.isNotEmpty() -> notes
                .append(
                    ctx.appString(
                        R.string.dsh_bk_secrets_in_archive,
                        secretsInfo.keys.size,
                        secretsInfo.keys.joinToString("、"),
                    ),
                )
                .append("\n")
            secretsInfo.encrypted && secretsInfo.filePresent && !secretsInfo.containsSecrets -> notes
                .append(ctx.appString(R.string.dsh_bk_secrets_placeholder))
                .append("\n")
        }
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
                    unresolved += if (note.isNotEmpty()) "$id $note" else id
                }
                "warning" -> {
                    warned++
                    notes.append("! ").append(id)
                    if (note.isNotEmpty()) notes.append(' ').append(note)
                    notes.append('\n')
                    warnings += if (note.isNotEmpty()) "$id $note" else id
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
                if (key == "warnings") warnings += v else missingSecrets += v
            }
        }
        // 被删除墓碑挡掉的条目：状态是「成功」但东西没进来，不说用户会以为导入了
        val tombstoned = execObj.optJSONArray("skippedTombstoned")
        val tombstonedCount = tombstoned?.length() ?: 0
        for (i in 0 until tombstonedCount) {
            val t = tombstoned?.optJSONObject(i) ?: continue
            notes.append("⊘ ").append(t.optString("id"))
            val adapter = t.optString("adapter")
            if (adapter.isNotEmpty()) notes.append(" (").append(adapter).append(")")
            notes.append('\n')
            unresolved += if (adapter.isNotEmpty()) "${t.optString("id")} ($adapter)" else t.optString("id")
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
                unresolved += ctx.appString(R.string.dsh_bk_rollback_failed, f.optString("item")) +
                    f.optString("manualHint").takeIf { it.isNotEmpty() }?.let { " → $it" }.orEmpty()
            }
        }
        val needsRestart = execObj.optBoolean("needsRestart", planObj.optBoolean("needsRestart", false))
        val ok = execObj.optBoolean("ok", failed == 0) && rollback == null

        // 会话不再由 App 恢复/归组：0.1.64 起插件在 /execute 阶段完整处理会话（写文件 → 按
        // pathMappings 改首帧 cwd → 用自带 projectKeyOf 算目标目录并归位 → attachSession 登记）。
        // 用户选「恢复会话」时会话计划项已保留在交给插件的计划里，结果随其它计划项一起出现在
        // 下面的 executed 汇总里；选「不恢复」时它们已在计划阶段被剔除。这里不再有 App 侧动作。

        // 软件数据：插件不认识它，一直由我们自己带、自己放回（见 [DshAppData]）。
        // 插件整体回滚时不动它：配置都没落地，先把设置写进去只会让本机处于一个
        // 「一半是备份里的设置、一半是本机配置」的状态，比不恢复更难解释。
        var appNote = ""
        var themeOutcome: ThemeOutcome? = null
        var privilegeSkipped = 0
        val appData = if (rollback == null) DshAppData.readFromZip(plainZip) else null
        if (appData != null) {
            onLine(ctx.appString(R.string.dsh_bk_step_appdata))
            val applied = DshAppData.apply(ctx, appData)
            privilegeSkipped = DshAppData.excludedPrivilegeCount(appData)
            // 外观在软件数据之后（见 [restoreThemeZip]）：两边都写那几个外观参数，
            // 主题后落地才是与导出时一致的那一份。
            val theme = restoreThemeZip(ctx, plainZip, tmpDir)
            themeOutcome = theme
            val lines = DshAppData.mergeAudit(ctx, plainZip)
            appNote = ctx.appString(R.string.dsh_bk_appdata_restored, applied.changed) +
                if (lines > 0) "，" + ctx.appString(R.string.dsh_bk_audit_merged, lines) else ""
            appNote += "，" + themeNote(ctx, theme)
            appNote += buildString { appendExcluded(ctx, appData, applied.excluded) }
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
        }
        // 把导入前的软件设置挂到这次导入的回滚快照上（key 就是插件给的快照 id）。
        // 失败不打断导入：设置的回退点少一份，不该让整次导入失败。
        val execSnapshotId = execObj.optString("snapshotId")
        if (execSnapshotId.isNotEmpty()) {
            val saved = DshAppDataSnapshot.write(ctx, execSnapshotId, appDataForSnapshot, themeForSnapshot)
            trace(ctx, "import-appdata-snapshot id=" + execSnapshotId + " ok=" + saved)
        }
        if (themeForSnapshot != null) themeForSnapshot.delete()
        // 明文中间产物里含解出来的凭据，不留
        if (plainZip != zip) plainZip.delete()
        // restartItems：插件核心结果目前不给这份清单（只有 needsRestart 这个布尔量），
        // 所以读到什么算什么 —— 界面在它为空时只显示一句「需要重启服务」。
        val restartItems = execObj.optJSONArray("restartItems")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
        } ?: emptyList()
        ImportResult(
            ok = ok,
            message = head,
            detail = detail,
            needsRestart = needsRestart,
            restartItems = restartItems,
            missingSecrets = missingSecrets,
            warnings = warnings,
            unresolved = unresolved,
            snapshotId = execObj.optString("snapshotId"),
            theme = themeOutcome,
            privilegeSkipped = privilegeSkipped,
        )
    }

    /** 会话文件在导出 ZIP 内的目录前缀（插件 SECTION_FILE_PREFIXES.sessions）。 */
    private const val SESSION_PREFIX = "sessions/"

    /**
     * 会话目录里的**运行时状态**文件：锁、临时文件、点开头的东西。
     *
     * 它们不是会话数据，恢复时不该带走：一份别机的锁在本机没有任何意义，而 0 字节的
     * 状态文件解析不出 zstd 帧，会被误当成「不可读的会话」（真机上 6 个会话文件报错的根因）。
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

    /**
     * 交给 dsh-config-manager 前，把体积大的外观包 `dsh-folk/theme.zip` 从包里拎出去。
     *
     * 为什么必须拎：dsh-config-manager 的 zip 安全护栏有「单条目解压 ≤100MB」的硬上限
     * （`utils/zip.js` maxSingleBytes），真机上 137MB 视频壁纸主题恢复时正是撞在这条上，
     * 报「备份完整性校验失败: dsh-folk/theme.zip」。外观本来就由 App 自己解（[restoreThemeZip]
     * 走 java.util.zip，无上限），所以交给插件的那份不需要带它。剥离副本会同步把 checksums
     * 里那一行摘掉，插件的逐条校验才不会因「清单列了、包里没有」而失败。
     *
     * @return first = 要上传给插件的包（有外观时是剥离副本，否则就是 [plainZip] 本身）；
     *   second = 需要在上传后删除的临时文件（没有则为 null）。
     */
    private suspend fun managerZipOf(ctx: Context, plainZip: File): Pair<File, File?> {
        if (DshBackupArchive.entrySize(plainZip, DshBackupArchive.THEME) < 0L) return plainZip to null
        val tmpDir = File(ctx.filesDir, "backup-tmp").apply { mkdirs() }
        val stripped = File(tmpDir, "manager-" + System.nanoTime() + ".zip")
        val ok = DshBackupArchive.stripEntry(plainZip, stripped, DshBackupArchive.THEME)
        return if (ok && stripped.isFile) {
            trace(ctx, "manager-strip-theme from=" + plainZip.length() + " to=" + stripped.length())
            stripped to stripped
        } else {
            stripped.delete()
            plainZip to null
        }
    }

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
