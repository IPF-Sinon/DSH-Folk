package me.bmax.apatch.dsh

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 导出范围。**枚举顺序就是滑块的档位顺序**，界面上不要另外排一遍。
 *
 * 为什么要有「仅软件数据」这一档：重装 App 之后用户最想拿回来的其实是自己的设置
 * （外观、启动开关、能力等级）而不一定是整个 DSH 环境；而插件不认识 App 自己的数据，
 * 这一档完全由我们在本地组装，一个字节都不经过插件。
 */
enum class BackupScope {
    APP_ONLY,
    DSH_ONLY,
    /**
     * 仅 DSH 数据 + 凭据原文。
     *
     * 云备份插件的 `dsh-vault` 档位对应这一档：换机时想把整套 DSH 环境（含凭据）搬走、
     * 但**不带** App 自己的设置与外观。以前只有 [BOTH_VAULT] 能带 vault，逼着「只要 DSH」
     * 的用户连 App 数据一起打包 —— 这一档补上那个缺口。含 vault，故与 [BOTH_VAULT] 一样强制加密。
     */
    DSH_VAULT,
    BOTH,
    BOTH_VAULT,
}

/**
 * 会话数量。**枚举顺序就是滑块的档位顺序**（默认第一档：不导出）。
 *
 * [limit] 为 -1 表示全部。会话是备份里最大的一块（真机上 6 个会话就 300 KB 起），
 * 而「只要最近几个」是最常见的诉求，所以它值得一个滑块而不是开关。
 */
enum class SessionPick(val limit: Int) {
    NONE(0),
    P5(5),
    P20(20),
    P50(50),
    ALL(-1),
}

/**
 * 一次导出用户选了什么。
 *
 * [includesVault] 与 [password] 的关系是硬约束而不是提示：凭据原文（`~/.dsh/.credentials.yaml`）
 * 一旦进包就必须能被密码保护，否则等于把密钥明文写进一个准备到处转发的文件里。
 * 插件自己也守着同一条不变量（绝不明文写凭据），这里在导出前再挡一次。
 */
data class ExportPlan(
    val scope: BackupScope = BackupScope.BOTH,
    val sessions: SessionPick = SessionPick.NONE,
    val password: String = "",
) {
    val includesDsh: Boolean get() = scope != BackupScope.APP_ONLY
    // 只有「仅 DSH」两档不含 App 数据；DSH_VAULT 同样是纯 DSH，故也排除
    val includesAppData: Boolean get() = scope != BackupScope.DSH_ONLY && scope != BackupScope.DSH_VAULT
    val includesVault: Boolean get() = scope == BackupScope.BOTH_VAULT || scope == BackupScope.DSH_VAULT

    /** 含 vault 却没设密码 = 非法组合，导出前必须挡住。 */
    val valid: Boolean get() = !includesVault || password.isNotEmpty()
}

/** [DshBackupArchive.merge] 的统计，用于给用户一句「包里到底装了什么」。 */
data class MergeStats(
    val sessions: Int = 0,
    val sessionFiles: Int = 0,
    val appData: Boolean = false,
    val auditFiles: Int = 0,
    val secrets: Boolean = false,
    /** 包里带了外观主题包（[DshBackupArchive.THEME]）。 */
    val theme: Boolean = false,
    /** 主题包大小，用于结果里告诉用户「外观占了多少」。 */
    val themeBytes: Long = 0,
)

/**
 * 备份包的本地组装。
 *
 * ## 为什么要在本地补包
 *
 * 插件（`dsh-config-manager`）只认 DSH 自己的分区：它会按 `sections` 把 `~/.dsh` 下的东西
 * 打包成明文 ZIP，但**不能按数量筛会话**，也**不认识 App 自己的数据**；而整包加密是它在
 * 导出那一刻做的外层容器 —— 一旦加密，我们就再也改不动那个包了。
 *
 * 所以流程反过来：让插件只导出**明文**的 DSH 分区（且明确排除 sessions），我们在本地把
 * 选中的会话、App 数据、以及含 vault 时的 `security/secrets.enc` 补进去，最后**由我们**
 * 做容器加密（[DshBackupCrypto]）。这样导出的包与插件自己产出的包在格式上完全一致，
 * 插件内恢复、桌面端 dsh-config-manager 恢复都照旧能用。
 *
 * ## 必须重算 checksums
 *
 * 插件的导入侧会**逐条 SHA-256 校验** `integrity/checksums.json`，任何不符或缺失都直接
 * 拒绝整包（analyzer 抛错）。所以补包之后必须按同样的规则重算这张表：覆盖**除
 * `manifest.json` 与表自身之外**的全部条目（插件导出侧就是这么生成的），条目上限 10000。
 *
 * ## manifest 的三处改动
 *
 * - `sections.sessions`：我们补了会话就置 true，没补就 false（导入侧是按它决定要不要
 *   找会话文件的，写错了会出现「声明有但没有」而被拒）。
 * - `security.encrypted` / `containsSecrets` / `encryption`：设了密码就为 true 并写入
 *   KDF 参数与 salt/iv/tag；**只要 encrypted=true，包里就必须有 `security/secrets.enc`**
 *   —— 导入侧在这条上是硬要求（`decryptedCredentials === undefined` 直接报错），
 *   所以不含 vault 时也要写一个**空内容**的占位（插件自己也是这么做的）。
 */
object DshBackupArchive {
    const val MANIFEST = "manifest.json"
    const val CHECKSUMS = "integrity/checksums.json"
    const val SECRETS = "security/secrets.enc"

    /** App 自己的数据在包里的目录前缀。用独立前缀是为了不与插件的分区命名撞车。 */
    const val APP_DIR = "dsh-folk/"
    const val APP_DATA = "dsh-folk/app-data.json"

    /**
     * 外观主题包在包里的落点（[ThemeIO.exportTheme] 的产物，原样搬进来）。
     *
     * 为什么外观要单独装一个 zip 而不是塞进 app-data.json：背景图、背景视频、字体、
     * 音乐、音效都是 `filesDir` 下的**资源文件**，prefs 里存的只是文件名和一个
     * `file://` 指向；只搬 prefs 会得到一台「设置说要显示某张图、而那张图不存在」的机器。
     * 而「当前外观 + 它的文件」打成 zip 这件事，[ThemeIO] 已经做了（还负责导入时把 URI
     * 重写成新路径、破 Coil 缓存），所以这里复用它，不另造一套。
     */
    const val THEME = "dsh-folk/theme.zip"

    const val SESSION_PREFIX = "sessions/"

    /** 插件产出的 manifest 里出现的全部分区 id（写新 manifest 时要给全，缺一个就是 undefined）。 */
    private val ALL_SECTIONS = listOf(
        "settings", "ui", "providers", "plugins", "mcp", "prompts",
        "skills", "agentPresets", "agentInstructions", "workspaces",
        "pluginFiles", "credentialsStatus", "sessions", "self", "secrets",
    )

    /**
     * 会话日志文件名：`session.jsonl.zstd`（旧）与 `session.v3.jsonl.zstd`（新格式）都算。
     *
     * 与容器侧归组助手用同一条判据（`dsh-session-group.cjs` 的 `SESSION_FILE_RE`）——
     * 写死一个名字会让新格式的会话在「最近 N 个」里被整批漏掉，而它们恰恰是设备上升级
     * dsh 之后才有的那些。
     */
    private val SESSION_FILE_RE = Regex("""^session(\.[A-Za-z0-9]+)*\.jsonl(\.zstd)?$""")

    /** 这个文件名是不是会话日志（不是的话就是 session.lock 之类的运行时文件）。 */
    fun isSessionFile(name: String): Boolean = SESSION_FILE_RE.matches(name)

    /** 向插件请求的 DSH 分区：**永远不含 sessions**（会话由我们在本地按数量补）。 */
    fun pluginSections(): List<String> = DshConfigBackup.DEFAULT_SECTIONS

    /**
     * 取某个条目的长度（不存在返回 -1）。
     *
     * 走 `ZipFile`（只读中央目录），**不读条目内容**：预检时一个带会话的包可能上百兆，
     * 为了问一句「外观在不在」把整包再流一遍没有道理。
     *
     * 长度可能是 0（先写本地头、写完才补 data descriptor 的条目，头里没有尺寸）——
     * 只关心「有没有」时不必当真。
     */
    fun entrySize(zip: File, name: String): Long = runCatching {
        java.util.zip.ZipFile(zip).use { zf -> zf.getEntry(name)?.size ?: -1L }
    }.getOrDefault(-1L)

    /**
     * 会话根目录（容器里的 `~/.dsh/sessions` 在设备上的落点）。
     *
     * 与 [DshConfigBackup.restoreSessionsFromZip] 用的是同一个根：App 直接读写这份树，
     * 不经过插件的 HTTP 接口 —— 插件只负责「DSH 配置分区」那一半。
     */
    fun sessionsRoot(ctx: Context): File = File(DshEnv.dshHome(ctx), "sessions")

    /** 一个会话目录下的日志文件（可能同时有新旧两份）。 */
    private fun payloads(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray()).filter { it.isFile && isSessionFile(it.name) }

    /**
     * 按「最近 [pick] 个会话」挑出会话目录。
     *
     * 单位是**会话目录**而不是文件：一个会话可能有 `session.jsonl.zstd` 与
     * `session.v3.jsonl.zstd` 两份（dsh 升格式时留下的），只挑其中一份会让恢复后的会话
     * 缺一半历史。所以选中一个目录就把它的日志全部带走。
     */
    fun pickSessions(ctx: Context, pick: SessionPick): List<File> {
        if (pick.limit == 0) return emptyList()
        val root = sessionsRoot(ctx)
        val dirs = root.listFiles()?.filter { it.isDirectory }?.flatMap { project ->
            project.listFiles()?.filter { it.isDirectory }?.toList() ?: emptyList()
        } ?: return emptyList()
        val withTime = dirs.mapNotNull { dir ->
            val files = payloads(dir)
            if (files.isEmpty()) null else dir to files.maxOf { it.lastModified() }
        }.sortedByDescending { it.second }
        return if (pick.limit < 0) withTime.map { it.first } else withTime.take(pick.limit).map { it.first }
    }

    /** 会话树里一共有多少个会话（界面上显示「最近 N 个 / 共 M 个」用）。 */
    fun sessionCount(ctx: Context): Int = pickSessions(ctx, SessionPick.ALL).size

    /**
     * 组装备份包。
     *
     * @param input 插件导出的明文 ZIP；[ExportPlan.includesDsh] 为 false 时传 null（纯 App 数据包）
     * @param output 结果落点（会被覆盖）
     * @param appData [DshAppData.collect] 的结果（不含 vault 时为 null，不写进包）
     * @param auditFiles 要带走的审计文件（与 [appData] 同进退）
     * @param theme 外观主题包（[ThemeIO.exportTheme] 的产物，见 [THEME]）；没有就传 null
     * @param secrets 含 vault 或设了密码时由 [DshBackupCrypto.encryptSecrets] 产出；
     *   不含 vault 但设了密码时是**空内容占位**（导入侧的硬要求）
     * @param sourceDshVersion 写进 manifest.source.dshVersion（新建包时才用得上）
     */
    fun merge(
        ctx: Context,
        input: File?,
        output: File,
        plan: ExportPlan,
        appData: JSONObject?,
        auditFiles: List<File>,
        theme: File? = null,
        secrets: DshBackupCrypto.Secrets?,
        sourceDshVersion: String,
        onNote: (String) -> Unit = {},
    ): MergeStats {
        require(plan.valid) { "含 vault 的备份必须设置密码" }
        val sessionDirs = if (plan.includesDsh) pickSessions(ctx, plan.sessions) else emptyList()
        val sessionFiles = sessionDirs.flatMap { payloads(it) }
        var stats = MergeStats(sessions = sessionDirs.size, sessionFiles = sessionFiles.size)

        // 除 manifest 与 checksums 表之外，每个条目都要算 sha256（插件导入侧逐条校验）
        val sums = LinkedHashMap<String, String>()
        val out = ZipOutputStream(BufferedOutputStream(FileOutputStream(output), 1 shl 16))
        out.use { zos ->
            // 1) 原样搬运插件的条目（sessions/ 一律丢弃：我们只写选中的那些）
            var manifest = JSONObject()
            if (input != null && input.isFile) {
                ZipInputStream(BufferedInputStream(FileInputStream(input), 1 shl 16)).use { zis ->
                    var e: ZipEntry? = zis.nextEntry
                    while (e != null) {
                        val name = e.name
                        if (!e.isDirectory) {
                            when {
                                name == MANIFEST -> manifest = runCatching {
                                    JSONObject(zis.readBytes().toString(Charsets.UTF_8))
                                }.getOrElse { JSONObject() }
                                name == CHECKSUMS -> Unit // 稍后按新内容重算
                                name.startsWith(SESSION_PREFIX) -> Unit // 会话由我们自己挑
                                else -> {
                                    val sha = MessageDigest.getInstance("SHA-256")
                                    zos.putNextEntry(ZipEntry(name))
                                    val buf = ByteArray(1 shl 16)
                                    var n = zis.read(buf)
                                    while (n > 0) {
                                        sha.update(buf, 0, n)
                                        zos.write(buf, 0, n)
                                        n = zis.read(buf)
                                    }
                                    zos.closeEntry()
                                    sums[name] = hex(sha.digest())
                                }
                            }
                        }
                        zis.closeEntry()
                        e = zis.nextEntry
                    }
                }
            } else {
                manifest = freshManifest(sourceDshVersion)
                onNote("no-plugin-input")
            }

            // 2) 选中的会话（按 <projectKey>/<sessionId>/<file> 逐字节搬运）
            val rootPath = sessionsRoot(ctx).absolutePath
            for (f in sessionFiles) {
                val rel = f.absolutePath.removePrefix(rootPath).trimStart('/')
                if (rel.isEmpty() || rel.startsWith("..")) continue
                sums[SESSION_PREFIX + rel] = copyInto(zos, f, SESSION_PREFIX + rel)
            }

            // 3) App 自己的数据（prefs + 审计日志）
            if (plan.includesAppData && appData != null) {
                sums[APP_DATA] = writeBytes(zos, APP_DATA, appData.toString(2).toByteArray(Charsets.UTF_8))
                stats = stats.copy(appData = true)
                for (f in auditFiles) {
                    if (!f.isFile || f.length() == 0L) continue
                    sums[APP_DIR + "audit/" + f.name] = copyInto(zos, f, APP_DIR + "audit/" + f.name)
                }
                stats = stats.copy(auditFiles = auditFiles.count { it.isFile && it.length() > 0L })
            }

            // 3b) 外观主题包。与软件数据同进退（都属于「App 自己的东西」那一档），但走的是
            //     资源文件那条路 —— 理由见 [THEME] 的说明。空文件不写：一个 0 字节的
            //     theme.zip 会让导入侧以为「有外观」却解不出任何东西。
            if (plan.includesAppData && theme != null && theme.isFile && theme.length() > 0L) {
                sums[THEME] = copyInto(zos, theme, THEME)
                stats = stats.copy(theme = true, themeBytes = theme.length())
            }

            // 4) secrets.enc（设了密码就必须有：导入侧在 encrypted=true 时会去找它解）
            if (secrets != null) {
                sums[SECRETS] = writeBytes(zos, SECRETS, secrets.blob)
                stats = stats.copy(secrets = true)
            }

            // 5) manifest：分区声明 + 加密状态
            manifest.put("schemaVersion", 1)
            manifest.put("exportedAt", isoNow())
            val sections = manifest.optJSONObject("sections") ?: JSONObject()
            for (id in ALL_SECTIONS) if (!sections.has(id)) sections.put(id, false)
            sections.put("sessions", sessionDirs.isNotEmpty())
            manifest.put("sections", sections)
            val security = JSONObject().apply {
                put("containsSecrets", plan.includesVault && plan.password.isNotEmpty())
                put("encrypted", plan.password.isNotEmpty())
                if (secrets != null) {
                    put(
                        "encryption",
                        JSONObject().apply {
                            put("algorithm", "aes-256-gcm")
                            put("kdf", "scrypt")
                            put(
                                "kdfParams",
                                JSONObject().apply {
                                    put("N", DshBackupCrypto.SCRYPT_N)
                                    put("r", DshBackupCrypto.SCRYPT_R)
                                    put("p", DshBackupCrypto.SCRYPT_P)
                                    put("keyLength", DshBackupCrypto.KEY_LENGTH)
                                },
                            )
                            put("salt", secrets.saltB64)
                            put("iv", secrets.ivB64)
                            put("authTag", secrets.authTagB64)
                            put("version", DshBackupCrypto.VERSION)
                        },
                    )
                } else {
                    put("encryption", JSONObject.NULL)
                }
            }
            manifest.put("security", security)
            writeBytes(zos, MANIFEST, manifest.toString(2).toByteArray(Charsets.UTF_8))

            // 6) checksums 表最后写：它必须覆盖前面写过的**全部**条目
            val table = JSONObject()
            for ((k, v) in sums) table.put(k, v)
            writeBytes(zos, CHECKSUMS, table.toString(2).toByteArray(Charsets.UTF_8))
        }
        return stats
    }

    /** 纯 App 数据包的 manifest：分区全 false，只为让插件的解析器也不至于拒收。 */
    private fun freshManifest(dshVersion: String): JSONObject = JSONObject().apply {
        put("schemaVersion", 1)
        put(
            "exporter",
            JSONObject().apply {
                put("name", "DSH-Folk")
                put("version", me.bmax.apatch.BuildConfig.VERSION_NAME)
            },
        )
        put(
            "source",
            JSONObject().apply {
                put("dshVersion", dshVersion)
                put("platform", "android")
                put("arch", android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            },
        )
        put("exportedAt", isoNow())
        put("sections", JSONObject())
        put("security", JSONObject().apply { put("containsSecrets", false); put("encrypted", false); put("encryption", JSONObject.NULL) })
    }

    /** 写一段字节为一个条目，返回它的 sha256。 */
    private fun writeBytes(zos: ZipOutputStream, name: String, bytes: ByteArray): String {
        zos.putNextEntry(ZipEntry(name))
        zos.write(bytes)
        zos.closeEntry()
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    /** 把一个文件流式写成一个条目，返回它的 sha256（不把整个文件读进内存）。 */
    private fun copyInto(zos: ZipOutputStream, src: File, name: String): String {
        val sha = MessageDigest.getInstance("SHA-256")
        zos.putNextEntry(ZipEntry(name))
        BufferedInputStream(FileInputStream(src), 1 shl 16).use { ins ->
            val buf = ByteArray(1 shl 16)
            var n = ins.read(buf)
            while (n > 0) {
                sha.update(buf, 0, n)
                zos.write(buf, 0, n)
                n = ins.read(buf)
            }
        }
        zos.closeEntry()
        return hex(sha.digest())
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0x0f])
        }
        return sb.toString()
    }

    private fun isoNow(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())
}
