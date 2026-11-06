package me.bmax.apatch.dsh.ext

import android.content.Context
import me.bmax.apatch.dsh.DshRuntime
import java.io.File

/** 已安装扩展的一条记录。 */
data class ExtensionRecord(
    val manifest: ExtensionManifest,
    val enabled: Boolean,
    /** 目录是否存在（目录被外部删掉时仍会出现在列表里，标成损坏让用户能清理）。 */
    val installed: Boolean,
) {
    val id: String get() = manifest.id
}

/**
 * 扩展的安装 / 启停 / 移除，以及「哪些是启用的」这一份持久化状态。
 *
 * 状态放在独立的 prefs（dsh_ext_state）里，且备份规则已把 sharedpref 整体排除 ——
 * 换设备恢复不该把「某个三方扩展是启用的」也带过去（那台机器上可能根本没这个 dex）。
 */
object ExtensionRegistry {

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("dsh_ext_state", Context.MODE_PRIVATE)

    private const val KEY_ENABLED = "enabled_ids"

    /** 已启用的 id 集合（每次都复制一份：SharedPreferences 返回的是内部引用，改它会污染缓存）。 */
    fun enabledIds(ctx: Context): Set<String> =
        runCatching { prefs(ctx).getStringSet(KEY_ENABLED, emptySet())?.toSet() ?: emptySet() }
            .getOrDefault(emptySet())

    fun isEnabled(ctx: Context, id: String): Boolean = enabledIds(ctx).contains(id)

    fun setEnabled(ctx: Context, id: String, enabled: Boolean) {
        runCatching {
            val next = enabledIds(ctx).toMutableSet()
            if (enabled) next.add(id) else next.remove(id)
            prefs(ctx).edit().putStringSet(KEY_ENABLED, next).apply()
        }
    }

    /** 清空「已启用」集合（安全模式熔断时用）。 */
    fun clearEnabled(ctx: Context) {
        runCatching { prefs(ctx).edit().putStringSet(KEY_ENABLED, emptySet()).apply() }
    }

    /** 扫磁盘列出全部已安装扩展（按名字排序；目录缺失的标成 installed=false）。 */
    fun listInstalled(ctx: Context): List<ExtensionRecord> {
        val root = ExtensionLayout.root(ctx)
        val dirs = runCatching { root.listFiles() }.getOrNull() ?: return emptyList()
        val enabled = enabledIds(ctx)
        val out = ArrayList<ExtensionRecord>()
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val mf = ExtensionManifest.read(dir)
            if (mf == null) {
                // manifest 坏掉 / 缺失：列表里仍要出现，否则用户永远删不掉它（目录一直占着）
                val fallback = ExtensionManifest(
                    id = dir.name,
                    name = dir.name + "（清单损坏）",
                    version = "?",
                    entryClass = null,
                )
                out.add(ExtensionRecord(fallback, enabled = false, installed = true))
                continue
            }
            out.add(ExtensionRecord(mf, enabled = enabled.contains(mf.id), installed = true))
        }
        return out.sortedBy { it.manifest.name.lowercase() }
    }

    /**
     * 从已解压的目录安装（调用方负责先解压到临时目录）。
     *
     * @return 安装好的 manifest；失败返回 null。
     */
    fun installFromDir(ctx: Context, source: File): ExtensionManifest? {
        val mf = ExtensionManifest.read(source) ?: return null
        val dest = ExtensionLayout.dirOf(ctx, mf.id)
        val ok = runCatching {
            if (dest.exists()) dest.deleteRecursively()
            dest.parentFile?.mkdirs()
            source.copyRecursiveTo(dest)
            true
        }.getOrDefault(false)
        if (!ok) return null
        // 装完默认**启用**：用户点安装就是想用；真要关，扩展页一个开关的事
        setEnabled(ctx, mf.id, true)
        DshRuntime.appendLog("[ext] 已安装 " + mf.name + " v" + mf.version + "（" + mf.id + "）")
        return mf
    }

    /** 移除扩展：删目录 + 清启用位。不抛异常（界面点「移除」不该崩）。 */
    fun remove(ctx: Context, id: String): Boolean {
        if (!ExtensionLayout.isValidId(id)) return false
        setEnabled(ctx, id, false)
        val dir = ExtensionLayout.dirOf(ctx, id)
        val ok = runCatching { if (dir.exists()) dir.deleteRecursively() else true }.getOrDefault(false)
        if (ok) DshRuntime.appendLog("[ext] 已移除扩展：" + id)
        return ok
    }
}
