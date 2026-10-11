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
 * `takePersistableUriPermission` 持久化，所以进程重启后仍可用；[sources] 读出来时会与
 * 当前 [android.content.ContentResolver.getPersistedUriPermissions] 交叉核对，授权已被系统/
 * 用户收回的来源不再列出（也从映射里清掉），避免 agent 调一个早就失效的来源。
 */
object DshSafSources {
    private const val TAG = "DshSafSources"

    /** 来源名的最大长度：够描述一棵树，又不至于长到 agent 难输入。 */
    private const val MAX_NAME_LEN = 40

    /** 一棵已登记的 SAF 树。 */
    data class Source(val name: String, val uri: Uri) {
        /** 这棵树的 tree document id（SAF 逐段解析的下行起点）。 */
        val treeDocId: String?
            get() = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()

        /** 提供器 authority（诊断用）。 */
        val authority: String? get() = uri.authority
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)

    /**
     * 当前仍然有效的来源列表。
     *
     * 「有效」= 既在 prefs 的映射里、系统也仍然持有对那棵树的持久化授权。授权被收回的条目
     * 会被顺手从 prefs 里清掉（懒清理）——不另开后台任务，读列表这件事本来就不频繁。
     */
    fun sources(ctx: Context): List<Source> {
        val stored = readStored(ctx)
        if (stored.isEmpty()) return emptyList()
        val persisted = persistedTreeUris(ctx)
        val kept = stored.filter { persisted.contains(it.uri.toString()) }
        if (kept.size != stored.size) writeStored(ctx, kept)
        return kept
    }

    /** 按名字取一个仍有效的来源；没有或已失效返回 null。 */
    fun find(ctx: Context, name: String): Source? =
        sources(ctx).firstOrNull { it.name == name }

    /**
     * 登记一棵树为 SAF 来源，返回最终采用的来源名。
     *
     * 名字由树的显示名规范化而来（Termux → `termux`）；与已有来源重名时加数字后缀。
     * 同一棵 URI 已经登记过就原样返回它既有的名字（不重复登记、不改名）。
     */
    fun register(ctx: Context, uri: Uri): String {
        val stored = readStored(ctx).toMutableList()
        stored.firstOrNull { it.uri == uri }?.let { return it.name }
        val base = baseName(ctx, uri)
        val used = stored.map { it.name }.toSet()
        val name = uniqueName(base, used)
        stored.add(Source(name, uri))
        writeStored(ctx, stored)
        return name
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
                Source(name, Uri.parse(uriStr))
            }
        }.getOrDefault(emptyList())
    }

    private fun writeStored(ctx: Context, list: List<Source>) {
        val a = JSONArray()
        for (s in list) {
            a.put(JSONObject().put("name", s.name).put("uri", s.uri.toString()))
        }
        prefs(ctx).edit().putString(DshEnv.KEY_SAF_SOURCES, a.toString()).apply()
    }

    /** 当前持有持久化授权的 tree URI 字符串集合。 */
    private fun persistedTreeUris(ctx: Context): Set<String> = runCatching {
        ctx.contentResolver.persistedUriPermissions
            .map { it.uri.toString() }
            .toSet()
    }.getOrDefault(emptySet())

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
