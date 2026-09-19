package me.bmax.apatch.dsh

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 「软件数据」——App 自己的设置与原生权限记录。
 *
 * ## 为什么不交给插件
 *
 * 插件（`dsh-config-manager`）工作在 `~/.dsh` 里，它**不认识** App 的 `SharedPreferences`
 * 与 `filesDir/audit`。所以这一半只能由 App 自己收集、自己放回包里（[DshBackupArchive]
 * 把它们写成 `dsh-folk/app-data.json` 与 `dsh-folk/audit/` 下的 jsonl 文件）。
 *
 * ## 为什么是「一组文件」而不是一个
 *
 * 设置并不都住在 `config` 里：DSH 自己的运行时/行为开关（「服务就绪后自动打开页面」
 * 「启动应用后自启动服务」「局域网/端口」「运行方式」「兼容模式」「宿主提示词」…）
 * 全在 [DshEnv.PREF]（`dshfolk`）那份文件里。只带 `config` 的话，用户在备份页看到的
 * 是「软件数据已恢复」，实际却少了整整一类设置 —— 而且这种缺失**没有任何提示**，因为
 * 那些键从来没进过包。外观类（背景/字体/音乐/音效）走的是主题包（见 [DshConfigBackup]），
 * 不在这个文件里，原因见那份代码里的说明（它们带资源文件，prefs 搬不动）。
 *
 * ## 什么不带走
 *
 * 三类键一律跳过，理由各不相同：
 *
 * 1. **像密钥的**（password / token / secret / api key / credential）：这一档说的是
 *    「App 设置 + 权限记录」，明确不含任何密钥（`fs_bridge_token` 也在这里被挡下）。
 * 2. **`webdav_*` 整组**：只恢复地址与用户名、把密码留在原机，等于给用户一个「填好了但
 *    连不上」的配置，还不如整组不动，让用户自己重填。
 * 3. **设备/运行时状态 + 提权项**（见 [FILE_SKIP_KEYS]）：前者记的是「**这台机器** +
 *    **这个运行时版本**下发生过什么」，照搬到另一台机器就是伪造事实；后者属于「换机后
 *    该由用户重新点一次」的授权，不该由一份备份替他决定。
 *
 * 跳过的键会按文件记进包里（`excluded`），导入时能如实告诉用户「有 N 项没跟着过来」，
 * 而不是让人以为全都恢复了。
 */
object DshAppData {
    /** 与 [APatchApp.SP_NAME] 同名的偏好文件名（这里写死是为了不依赖 APatchApp 的初始化时序）。 */
    const val PREFS_NAME = "config"

    /** 打进包的偏好文件。顺序即界面上的报告顺序。 */
    val PREFS_FILES = listOf(PREFS_NAME, DshEnv.PREF)

    /** 提权相关的那几个键：不随备份走，且要在导入结果里单独点名。 */
    val PRIVILEGE_KEYS = setOf(
        DshEnv.KEY_PERM_CHANNEL,
        DshEnv.KEY_PRIV_STRICTNESS,
        DshEnv.KEY_NATIVE_BRIDGE,
        DshEnv.KEY_NATIVE_CAPS,
    )

    private const val KEY_PREFS = "prefs"
    private const val KEY_EXCLUDED = "excluded"
    private const val KEY_AUDIT = "audit"
    private const val KEY_APP = "app"
    private const val KEY_SCHEMA = "schema"

    /**
     * 包结构版本。
     *
     * 1 = `prefs` 是「键 → 值」（只有一个文件）；2 = 「文件名 → 键 → 值」。
     * 写出去的一律是 2，读进来两种都认（见 [prefsGroups]）—— 用户手上的旧备份必须继续能导。
     */
    private const val SCHEMA_V2 = 2

    /** 键名里出现这些片段就当作密钥，不带走。 */
    private val SECRET_HINTS = listOf(
        "password", "passwd", "token", "secret", "apikey", "api_key",
        "private_key", "credential", "license",
    )

    /** 整组不碰的前缀。 */
    private val SKIP_PREFIXES = listOf("webdav_")

    /**
     * 按文件点名不碰的键。
     *
     * `dshfolk` 那一串分三组：
     * - **预装账本**（`seed_*`）：记的是「这台机器、这个运行时版本下哪些预装插件已处理」。
     *   照搬会让新机跳过或重跑预装修复 —— 1.9.1 那次升级死循环就是这套账本的判据出错。
     * - **本机运行时状态/缓存**：`runtime_version` / `runtime_min_app_version` 是**别的
     *   机器**上那份运行时的版本与最低 App 要求，搬过来会让更新检查与兼容判定拿着错的事实
     *   做决定；`proroot_fail_streak` 是连续失败计数（新机「才失败 1 次」被写成「失败 3 次」
     *   会直接触发回退到 proot）；`rootfs_size_bytes` 是那份 rootfs 的体积缓存。
     * - **提权项**：权限通道（root/Shizuku/ADB）、严格程度、原生能力桥开关与各能力档位。
     *   换台机器恢复一份备份，不该在用户没点过的情况下把特权通道或 SMS/SHELL 这类能力
     *   重新打开（宿主侧另有系统权限约束，但那是第二道闸，不是这一道的替代）。
     */
    private val FILE_SKIP_KEYS: Map<String, Set<String>> = mapOf(
        PREFS_NAME to setOf("app_initialized"),
        DshEnv.PREF to setOf(
            // 预装账本。这里点名的是**键名**：`seed_plugins_done` 这个旧键在老用户升级
            // 上来的 prefs 里仍然可能出现，所以必须继续排除它 —— 引用 @Deprecated 的常量
            // 正是为了这一点，警告在这个位置是噪音。
            @Suppress("DEPRECATION") DshEnv.KEY_SEED_PLUGINS_DONE,
            DshEnv.KEY_SEEDED_PLUGINS,
            DshEnv.KEY_SEED_REPAIR_REV,
            DshEnv.KEY_SEED_RUNTIME,
            DshEnv.KEY_SEED_PASSES,
            DshEnv.KEY_SEED_SHADOWED,
            DshEnv.KEY_SEED_SHADOWED_RUNTIME,
            // 本机运行时状态 / 缓存
            DshEnv.KEY_RUNTIME_VERSION,
            DshEnv.KEY_RUNTIME_MIN_APP,
            DshEnv.KEY_PROROOT_FAIL,
            DshEnv.KEY_ROOTFS_SIZE,
            // 提权项
            DshEnv.KEY_PERM_CHANNEL,
            DshEnv.KEY_PRIV_STRICTNESS,
            DshEnv.KEY_NATIVE_BRIDGE,
            DshEnv.KEY_NATIVE_CAPS,
        ),
    )

    /** 权限记录的目录（与 [DshNativeBridge] 写的是同一个）。 */
    fun auditDir(ctx: Context): File = File(ctx.filesDir, "audit")

    /** 审计文件清单（轮转出来的 .previous 也要带走，否则最近的历史会缺一段）。 */
    fun auditFiles(ctx: Context): List<File> =
        (auditDir(ctx).listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".jsonl") }
            .sortedBy { it.name }

    /** 这个文件里的这个键是不是「不该进包」的。 */
    fun isExcluded(file: String, key: String): Boolean =
        key in FILE_SKIP_KEYS[file].orEmpty() ||
            SKIP_PREFIXES.any { key.startsWith(it) } ||
            SECRET_HINTS.any { key.lowercase().contains(it) }

    /**
     * 收集 App 设置。
     *
     * 值带类型标记（`{"t":"s","v":...}`）而不是直接塞进 JSON：`SharedPreferences` 里
     * int/long/float/boolean/StringSet 混在一起，直接序列化会把 `1`（int）变成 `1.0`（double），
     * 写回去时类型就错了 —— 那种错在真机上表现为「开关状态看起来对、行为不对」，很难查。
     */
    fun collect(ctx: Context): JSONObject {
        val prefsByFile = JSONObject()
        val excludedByFile = JSONObject()
        for (file in PREFS_FILES) {
            val prefs = ctx.getSharedPreferences(file, Context.MODE_PRIVATE)
            val kept = JSONObject()
            val excluded = JSONArray()
            for ((key, value) in prefs.all) {
                if (isExcluded(file, key)) {
                    excluded.put(key)
                    continue
                }
                encode(value)?.let { kept.put(key, it) }
            }
            prefsByFile.put(file, kept)
            excludedByFile.put(file, excluded)
        }
        val audit = JSONArray()
        for (f in auditFiles(ctx)) audit.put(f.name)
        return JSONObject().apply {
            put(KEY_APP, me.bmax.apatch.BuildConfig.VERSION_NAME)
            put(KEY_SCHEMA, SCHEMA_V2)
            put(KEY_PREFS, prefsByFile)
            put(KEY_EXCLUDED, excludedByFile)
            put(KEY_AUDIT, audit)
        }
    }

    /** [apply] 的结果：改了多少条、动了哪几个文件、按设计跳过了多少条。 */
    data class Applied(
        /** 真正写入且值有变化的条目数（与现值相同的键不计）。 */
        val changed: Int = 0,
        /** 这次真的被写过的偏好文件（不含「包里没有」与「值没变」的）。 */
        val files: List<String> = emptyList(),
        /** 包里被排除规则挡下的键数：密钥类 + 设备/运行时状态 + 提权项。 */
        val excluded: Int = 0,
    )

    /** 把包里的软件数据写回本机。 */
    fun apply(ctx: Context, data: JSONObject): Applied {
        val groups = prefsGroups(data)
        val editorFiles = mutableListOf<String>()
        var changed = 0
        for (file in groups.keys()) {
            // 只认自己写过的文件：包是不可信输入，不该凭它去创建任意 prefs 文件。
            if (file !in PREFS_FILES) continue
            val incoming = groups.optJSONObject(file) ?: continue
            val prefs = ctx.getSharedPreferences(file, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            var n = 0
            for (key in incoming.keys()) {
                if (isExcluded(file, key)) continue
                val obj = incoming.optJSONObject(key) ?: continue
                if (write(editor, prefs, key, obj)) n++
            }
            if (n > 0) {
                editor.apply()
                changed += n
                editorFiles += file
            }
        }
        return Applied(changed, editorFiles, excludedCount(data))
    }

    /** 一个包里的软件数据概况（向导的「预览」步用）。 */
    data class Summary(
        /** 包里有几个偏好文件（1 = 旧结构，只有 config）。 */
        val prefsFiles: Int = 0,
        /** 一共多少条设置。 */
        val keys: Int = 0,
        /** 审计文件数。 */
        val auditFiles: Int = 0,
        /** 有没有外观主题包。 */
        val theme: Boolean = false,
        /** 外观主题包大小（字节）。 */
        val themeBytes: Long = 0,
        /** 按设计被排除的键数。 */
        val excluded: Int = 0,
        /** 其中提权项的数量。 */
        val privilegeSkipped: Int = 0,
        /** 包结构版本（1 = 旧包，只有 config；2 = 按文件分组）。 */
        val schema: Int = 0,
    )

    /**
     * 只看一眼包里有什么，不落地任何东西。
     *
     * 一次遍历同时取三样（app-data.json 的字节、theme.zip 的大小、审计文件清单），
     * 而不是扫三遍包：预览步在用户点「下一步」时同步发生，包可能上百兆。
     */
    fun summarize(zip: File): Summary {
        var dataBytes: ByteArray? = null
        var themeBytes = 0L
        var audit = 0
        runCatching {
            java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    when {
                        e.isDirectory -> Unit
                        e.name == DshBackupArchive.APP_DATA -> dataBytes = zis.readBytes()
                        // 不能信 e.size：本地头里的尺寸常常是 0（写完才补 data descriptor）。
                        // 流式数一遍只占 64 KB 缓冲，不会把上百兆的背景视频读进内存。
                        e.name == DshBackupArchive.THEME -> themeBytes = streamLength(zis)
                        e.name.startsWith(DshBackupArchive.APP_DIR + "audit/") -> audit++
                    }
                    zis.closeEntry()
                }
            }
        }
        val data = dataBytes?.let { runCatching { JSONObject(it.toString(Charsets.UTF_8)) }.getOrNull() }
        if (data == null) return Summary(auditFiles = audit, theme = themeBytes > 0L, themeBytes = themeBytes)
        val groups = prefsGroups(data)
        var keys = 0
        for (file in groups.keys()) keys += groups.optJSONObject(file)?.length() ?: 0
        return Summary(
            prefsFiles = groups.length(),
            keys = keys,
            auditFiles = audit,
            theme = themeBytes > 0L,
            themeBytes = themeBytes,
            excluded = excludedCount(data),
            privilegeSkipped = excludedPrivilegeCount(data),
            schema = data.optInt("schema", 1),
        )
    }

    /**
     * 取「文件 → 键 → 值」分组。
     *
     * 兼容 schema 1：那时的包把 `prefs` 直接存成「键 → 值」，而那时只有 `config` 一个文件，
     * 所以整块按 [PREFS_NAME] 处理即可。旧包在用户手里，这条路不能断。
     */
    private fun prefsGroups(data: JSONObject): JSONObject {
        val raw = data.optJSONObject(KEY_PREFS) ?: return JSONObject()
        return if (isFlatV1(raw)) JSONObject().put(PREFS_NAME, raw) else raw
    }

    /** v1 判据：看第一层某个值是不是带 `t` 的类型标记对象。 */
    private fun isFlatV1(prefs: JSONObject): Boolean {
        val keys = prefs.keys()
        while (keys.hasNext()) {
            val v = prefs.optJSONObject(keys.next()) ?: continue
            if (v.has("t")) return true
        }
        return false
    }

    /** 包里被排除规则挡下的键数（v1 的 `excluded` 是数组，v2 是按文件分组的对象）。 */
    fun excludedCount(data: JSONObject): Int {
        data.optJSONArray(KEY_EXCLUDED)?.let { return it.length() }
        val byFile = data.optJSONObject(KEY_EXCLUDED) ?: return 0
        var n = 0
        for (file in byFile.keys()) n += byFile.optJSONArray(file)?.length() ?: 0
        return n
    }

    /**
     * 包里被有意跳过的**提权项**数量（[PRIVILEGE_KEYS] 里的那些）。
     *
     * 单独数出来是为了让导入结果能说清一件事：这几项不是「漏了」，是按设计没跟着走。
     */
    fun excludedPrivilegeCount(data: JSONObject): Int {
        var n = 0
        data.optJSONArray(KEY_EXCLUDED)?.let { arr ->
            for (i in 0 until arr.length()) if (arr.optString(i) in PRIVILEGE_KEYS) n++
            return n
        }
        val byFile = data.optJSONObject(KEY_EXCLUDED) ?: return 0
        for (file in byFile.keys()) {
            val arr = byFile.optJSONArray(file) ?: continue
            for (i in 0 until arr.length()) if (arr.optString(i) in PRIVILEGE_KEYS) n++
        }
        return n
    }

    /** 把一个 zip 条目的字节数流式数出来（不占内存）。 */
    private fun streamLength(zis: java.util.zip.ZipInputStream): Long {
        var n = 0L
        val buf = ByteArray(1 shl 16)
        while (true) {
            val r = zis.read(buf)
            if (r <= 0) break
            n += r
        }
        return n
    }

    /** 从包里读出软件数据（没有就是 null）。调用方负责先解容器。 */
    fun readFromZip(zip: File): JSONObject? = runCatching {        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (!e.isDirectory && e.name == DshBackupArchive.APP_DATA) {
                    return@runCatching JSONObject(zis.readBytes().toString(Charsets.UTF_8))
                }
                zis.closeEntry()
            }
            null
        }
    }.getOrNull()

    /**
     * 把包里的审计日志并回本机。
     *
     * 用「按行去重后追加」而不是覆盖：审计是一份时间序列，本机已有的记录不能因为恢复一份
     * 旧包就消失；而同一行在两个文件里同时出现只可能是同一份包被导入了两次。整文件上限
     * 8 MB，超了就不动（审计文件是诊断材料，不值得为它冒内存风险）。
     */
    fun mergeAudit(ctx: Context, zip: File): Int {
        val dir = auditDir(ctx).apply { mkdirs() }
        var appended = 0
        runCatching {
            java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    val name = e.name
                    if (!e.isDirectory && name.startsWith(DshBackupArchive.APP_DIR + "audit/")) {
                        val fileName = name.substringAfterLast('/')
                        if (fileName.isNotEmpty() && fileName.endsWith(".jsonl")) {
                            val incoming = zis.readBytes().toString(Charsets.UTF_8)
                            if (incoming.length <= 8 shl 20) {
                                val dest = File(dir, fileName)
                                val have = if (dest.isFile) {
                                    dest.readText(Charsets.UTF_8).lineSequence().toHashSet()
                                } else {
                                    emptySet()
                                }
                                val add = incoming.lineSequence().filter { it.isNotBlank() && it !in have }.toList()
                                if (add.isNotEmpty()) {
                                    dest.appendText(add.joinToString("\n", postfix = "\n"))
                                    appended += add.size
                                }
                            }
                        }
                    }
                    zis.closeEntry()
                }
            }
        }
        return appended
    }

    /** 单个值的类型化编码；不认识的值返回 null（跳过而不是猜）。 */
    private fun encode(value: Any?): JSONObject? = when (value) {
        is String -> JSONObject().put("t", "s").put("v", value)
        is Int -> JSONObject().put("t", "i").put("v", value)
        is Long -> JSONObject().put("t", "l").put("v", value)
        is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
        is Boolean -> JSONObject().put("t", "b").put("v", value)
        is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(value.map { it.toString() }))
        else -> null
    }

    /** 写回一个值；与现值相同则不动（这样 reported 的「改动数」才是真的改动数）。 */
    private fun write(
        editor: SharedPreferences.Editor,
        prefs: SharedPreferences,
        key: String,
        obj: JSONObject,
    ): Boolean {
        val type = obj.optString("t")
        val current = prefs.all[key]
        return when (type) {
            "s" -> {
                val v = obj.optString("v")
                if (current == v) false else { editor.putString(key, v); true }
            }
            "i" -> {
                val v = obj.optInt("v")
                if (current == v) false else { editor.putInt(key, v); true }
            }
            "l" -> {
                val v = obj.optLong("v")
                if (current == v) false else { editor.putLong(key, v); true }
            }
            "f" -> {
                val v = obj.optDouble("v").toFloat()
                if (current == v) false else { editor.putFloat(key, v); true }
            }
            "b" -> {
                val v = obj.optBoolean("v")
                if (current == v) false else { editor.putBoolean(key, v); true }
            }
            "set" -> {
                val arr = obj.optJSONArray("v") ?: JSONArray()
                val v = (0 until arr.length()).map { arr.optString(it) }.toSet()
                if (current is Set<*> && current == v) false else {
                    editor.putStringSet(key, v)
                    true
                }
            }
            else -> false
        }
    }
}
