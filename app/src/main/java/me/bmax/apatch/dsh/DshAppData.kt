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
 * ## 什么不带走
 *
 * 用户选的是「App 设置 + 原生权限记录」，明确不含任何密钥。所以三类键一律跳过：
 * - 名字像密钥的（password / token / secret / api key / credential）；
 * - `webdav_*` 整组：只恢复地址与用户名、把密码留在原机，等于给用户一个「填好了但连不上」
 *   的配置，还不如整组不动，让用户自己重填；
 * - `app_initialized`：它是「首次启动初始化已完成」的标记，恢复它会让新设备跳过初始化。
 *
 * 跳过的键会记进包里（`excluded`），导入时能如实告诉用户「有 N 项没跟着过来」，
 * 而不是让人以为全都恢复了。
 */
object DshAppData {
    /** 与 [APatchApp.SP_NAME] 同名的偏好文件名（这里写死是为了不依赖 APatchApp 的初始化时序）。 */
    const val PREFS_NAME = "config"

    private const val KEY_PREFS = "prefs"
    private const val KEY_EXCLUDED = "excluded"
    private const val KEY_AUDIT = "audit"
    private const val KEY_APP = "app"

    /** 键名里出现这些片段就当作密钥，不带走。 */
    private val SECRET_HINTS = listOf(
        "password", "passwd", "token", "secret", "apikey", "api_key",
        "private_key", "credential", "license",
    )

    /** 整组不碰的前缀。 */
    private val SKIP_PREFIXES = listOf("webdav_")

    /** 单独点名不碰的键。 */
    private val SKIP_KEYS = setOf("app_initialized")

    /** 权限记录的目录（与 [DshNativeBridge] 写的是同一个）。 */
    fun auditDir(ctx: Context): File = File(ctx.filesDir, "audit")

    /** 审计文件清单（轮转出来的 .previous 也要带走，否则最近的历史会缺一段）。 */
    fun auditFiles(ctx: Context): List<File> =
        (auditDir(ctx).listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".jsonl") }
            .sortedBy { it.name }

    /** 这个键是不是「不该进包」的。 */
    fun isExcluded(key: String): Boolean =
        key in SKIP_KEYS ||
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
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val kept = JSONObject()
        val excluded = JSONArray()
        for ((key, value) in prefs.all) {
            if (isExcluded(key)) {
                excluded.put(key)
                continue
            }
            encode(value)?.let { kept.put(key, it) }
        }
        val audit = JSONArray()
        for (f in auditFiles(ctx)) audit.put(f.name)
        return JSONObject().apply {
            put(KEY_APP, me.bmax.apatch.BuildConfig.VERSION_NAME)
            put(KEY_PREFS, kept)
            put(KEY_EXCLUDED, excluded)
            put(KEY_AUDIT, audit)
        }
    }

    /** 把包里的软件数据写回本机；返回真正改动的条目数，供界面如实报告。 */
    fun apply(ctx: Context, data: JSONObject): Int {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val incoming = data.optJSONObject(KEY_PREFS) ?: JSONObject()
        val editor = prefs.edit()
        var changed = 0
        for (key in incoming.keys()) {
            if (isExcluded(key)) continue
            val obj = incoming.optJSONObject(key) ?: continue
            if (write(editor, prefs, key, obj)) changed++
        }
        if (changed > 0) editor.apply()
        return changed
    }

    /** 从包里读出软件数据（没有就是 null）。调用方负责先解容器。 */
    fun readFromZip(zip: File): JSONObject? = runCatching {
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zis ->
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
