package me.bmax.apatch.dsh

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 「SAF 来源」注册表：把一棵**已持久化授权**的 SAF 树映射成一个有名字、人类可读的来源 id。
 *
 * ## 它补的是哪一类
 *
 * 系统文件选择器选到的某些第三方提供器（典型 Termux），内容在别的应用私有目录里，容器
 * （proot）**挂载不了真实路径**（权限墙），但我们对那棵树**有持久化 SAF 授权**
 * （[android.content.ContentResolver.takePersistableUriPermission] 已在选择器回调里做）。
 * MT 管理器能读 Termux 正是因为它全程走 SAF、不需要真实路径。
 *
 * 挂得上的（共享存储里、App 读得到真实路径的）继续走 bind 挂载（见 [DshFileAccess]）；
 * **挂不上的那一类**改为登记成 SAF 来源，容器里的 `dsh-saf` 命令据此经宿主 App 用
 * ContentResolver / DocumentsContract 代办简单文件操作（list/stat/read/write/mkdir/rm/mv/cp）。
 * 不复制整树、不需要 root、不需要挂载。
 *
 * ## 为什么要注册表、而不是直接传 URI 进容器
 *
 * 容器里是 agent 和用户装的第三方插件，让它们直接拿 content:// URI 等于把「哪棵树可访问」
 * 的决定权交给容器。注册表让这件事反过来：**只有用户在选择器里选过、且登记为来源的那棵树**
 * 才可经 `dsh-saf` 访问；容器只知道来源名，真正的 URI 与授权判定留在 App 侧
 * （[DshFsBridge] 的 `/saf/…` 端点，受 token + 回环守卫，且写操作受 [DshNativeBridge.Cap.SAF] gate）。
 *
 * ## 持久化
 *
 * 名字→tree URI 的映射存在同一份 prefs（[DshEnv.KEY_SAF_SOURCES]）。SAF 授权本身由
 * `takePersistableUriPermission` 持久化，所以进程重启后仍可用。
 *
 * 册子**只随用户显式动作增删**（授权登记 / 移除），不做「持久化授权还在吗」的反查剔除 ——
 * 见 [allSources] 的说明：反查会误伤刚登记、系统那份列表还没来得及反映的来源。真授权丢了的话，
 * `dsh-saf` 调用时会拿到明确的权限错误，比来源凭空消失清楚。
 */
object DshSafSources {
    private const val TAG = "DshSafSources"

    /** 来源名的最大长度：够描述一棵树，又不至于长到 agent 难输入。 */
    private const val MAX_NAME_LEN = 40

    /** 一棵已登记的 SAF 树。[enabled] 为 false 时仍记在册、但 `dsh-saf` 看不到它。 */
    data class Source(val name: String, val uri: Uri, val enabled: Boolean = true) {
        /** 这棵树的 tree document id（SAF 逐段解析的下行起点）。 */
        val treeDocId: String?
            get() = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()

        /** 提供器 authority（诊断用）。 */
        val authority: String? get() = uri.authority
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)

    /**
     * 当前**已启用**的来源列表 —— `dsh-saf` 与可用性判定都走这个。
     * 未启用（用户在设置里取消了勾选）的来源不列出：登记≠放行，启用才放行。
     */
    fun sources(ctx: Context): List<Source> = allSources(ctx).filter { it.enabled }

    /**
     * 全部来源（含未启用）—— 设置页的勾选列表用这个，才能把「已登记但没启用」的也显示出来让用户勾。
     *
     * **不做「持久化授权还在吗」的反查剔除**：以前这里写成「拿 persistedUriPermissions 反查，
     * 查不到就从册子里删掉」，真机上「申请授权」选完目录后列表依旧是空的 —— 刚
     * `takePersistableUriPermission` 完，系统那份列表未必立刻反映出来（不同 ROM / 提供器也不一定
     * 都返回持久化位），于是刚登记的来源被当成失效、当场从 prefs 抹掉，用户看到的就是「授权后
     * 无变化」。登记是用户的显式动作，不该被一次读不准的反查悄悄撤销。真授权丢了的话，`dsh-saf`
     * 调用时会拿到明确的权限错误，比让来源凭空消失清楚得多。要撤，用户在列表里点「移除」。
     */
    fun allSources(ctx: Context): List<Source> = readStored(ctx)

    /** 按名字取一个**已启用**的来源；没有或未启用返回 null。 */
    fun find(ctx: Context, name: String): Source? =
        sources(ctx).firstOrNull { it.name == name }

    /**
     * 登记一棵树为 SAF 来源，返回最终采用的来源名。**新来源默认启用**（用户授权就是为了用它）。
     *
     * 名字由树的显示名规范化而来（Termux → `termux`）；与已有来源重名时加数字后缀。
     * 同一棵 URI 已经登记过就原样返回它既有的名字（不重复登记、不改名、不动它的启用状态）。
     */
    fun register(ctx: Context, uri: Uri): String {
        val stored = readStored(ctx).toMutableList()
        stored.firstOrNull { it.uri == uri }?.let { return it.name }
        val base = baseName(ctx, uri)
        val used = stored.map { it.name }.toSet()
        val name = uniqueName(base, used)
        stored.add(Source(name, uri, enabled = true))
        writeStored(ctx, stored)
        return name
    }

    /** 启用/停用一个来源（停用后 `dsh-saf` 立刻看不到它，但授权与登记都保留）。 */
    fun setEnabled(ctx: Context, name: String, enabled: Boolean) {
        val stored = readStored(ctx)
        if (stored.none { it.name == name }) return
        writeStored(ctx, stored.map { if (it.name == name) it.copy(enabled = enabled) else it })
    }

    /**
     * 撤销一个来源：从注册表移除，并尽量释放对那棵树的持久化授权。
     *
     * 释放授权是「尽量」——别的功能（例如挂载层对同一棵树的使用）可能也 take 过同一个 URI，
     * 释放失败不影响「从注册表移除」这件事本身（移除后 `dsh-saf` 立刻就访问不到它了）。
     */
    fun remove(ctx: Context, name: String) {
        val stored = readStored(ctx)
        val target = stored.firstOrNull { it.name == name } ?: return
        writeStored(ctx, stored.filter { it.name != name })
        runCatching {
            ctx.contentResolver.releasePersistableUriPermission(
                target.uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure { Log.w(TAG, "释放 SAF 授权失败: ${it.message}") }
    }

    // ────────────────────────── 内部 ──────────────────────────

    private fun readStored(ctx: Context): List<Source> {
        val raw = prefs(ctx).getString(DshEnv.KEY_SAF_SOURCES, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val uriStr = o.optString("uri").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                // 老记录没有 enabled 字段：缺省按启用（它们是在「默认启用」之前登记的，当时就是可用的）
                Source(name, Uri.parse(uriStr), o.optBoolean("enabled", true))
            }
        }.getOrDefault(emptyList())
    }

    private fun writeStored(ctx: Context, list: List<Source>) {
        val a = JSONArray()
        for (s in list) {
            a.put(
                JSONObject()
                    .put("name", s.name)
                    .put("uri", s.uri.toString())
                    .put("enabled", s.enabled),
            )
        }
        prefs(ctx).edit().putString(DshEnv.KEY_SAF_SOURCES, a.toString()).apply()
    }

    /** 树的显示名 → 规范化来源名的基底；取不到显示名就用 authority 的末段。 */
    private fun baseName(ctx: Context, uri: Uri): String {
        val display = runCatching {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
            ctx.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        val fromName = display?.let { sanitize(it) }
        if (!fromName.isNullOrEmpty()) return fromName
        // 退回提供器 authority 的末段（com.termux.documents → termux）
        val authSeg = uri.authority?.substringBefore('.')?.let { sanitize(it) }
        return if (!authSeg.isNullOrEmpty()) authSeg else "source"
    }

    /** 规范化成一个安全的来源名：小写、非字母数字折成 `-`、去首尾 `-`、限长。 */
    private fun sanitize(raw: String): String {
        val lowered = raw.lowercase(Locale.ROOT)
        val sb = StringBuilder()
        for (c in lowered) {
            if (c in 'a'..'z' || c in '0'..'9') sb.append(c)
            else if (sb.isNotEmpty() && sb.last() != '-') sb.append('-')
        }
        return sb.toString().trim('-').take(MAX_NAME_LEN).trim('-')
    }

    /** 与 [used] 里的名字去重：先试基底，占用了就 `-2` / `-3`… 往后加。 */
    private fun uniqueName(base: String, used: Set<String>): String {
        val b = base.ifEmpty { "source" }
        if (b !in used) return b
        var i = 2
        while ("$b-$i" in used) i++
        return "$b-$i"
    }
}
