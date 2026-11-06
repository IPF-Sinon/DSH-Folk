package me.bmax.apatch.dsh.ext

import org.json.JSONObject
import java.io.File

/**
 * 扩展的 manifest.json 模型。
 *
 * 有意做成「最小必填」：只要 id / name / version 就能装一个**纯声明式**扩展（只做主题、
 * 只注册一个槽位、只挂一个 Hook）；需要跑代码时再补 entryClass + classes.dex。
 */
data class ExtensionManifest(
    val id: String,
    val name: String,
    val version: String,
    /** 实现 [DshExtension] 的类名（全限定）；为空 = 纯声明式扩展，不加载任何字节码。 */
    val entryClass: String?,
    val description: String = "",
    /** 声明的宿主能力（安装确认框里逐条列给用户看，也是将来做细粒度授权的依据）。 */
    val declaredCaps: List<String> = emptyList(),
    /** 最低宿主版本（BuildConfig.VERSION_NAME 语义）；为空不校验。 */
    val minAppVersion: String? = null,
) {
    /** 是否需要加载字节码。 */
    val hasCode: Boolean get() = !entryClass.isNullOrBlank()

    companion object {
        /**
         * 解析 manifest。**任何字段不合法都返回 null**（宁可装不上，也不要装上一个
         * id 里带 ../ 的扩展）。
         */
        fun parse(json: String): ExtensionManifest? = runCatching {
            val o = JSONObject(json)
            val id = o.optString("id", "").trim()
            val name = o.optString("name", "").trim()
            val version = o.optString("version", "").trim()
            if (!ExtensionLayout.isValidId(id)) return null
            if (name.isEmpty() || version.isEmpty()) return null
            val caps = o.optJSONArray("declaredCaps")
            val capList = ArrayList<String>()
            if (caps != null) {
                for (i in 0 until caps.length()) {
                    val c = caps.optString(i, "").trim()
                    if (c.isNotEmpty()) capList.add(c)
                }
            }
            ExtensionManifest(
                id = id,
                name = name,
                version = version,
                entryClass = o.optString("entryClass", "").trim().ifEmpty { null },
                description = o.optString("description", "").trim(),
                declaredCaps = capList,
                minAppVersion = o.optString("minAppVersion", "").trim().ifEmpty { null },
            )
        }.getOrNull()

        /** 从目录读 manifest。 */
        fun read(dir: File): ExtensionManifest? {
            val f = File(dir, "manifest.json")
            if (!f.isFile) return null
            val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: return null
            return parse(text)
        }
    }
}
