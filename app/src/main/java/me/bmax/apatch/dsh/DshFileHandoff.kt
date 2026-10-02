package me.bmax.apatch.dsh

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 「分享/以…打开 → 交给 DSH 处理」这条路的后端：列工作区、把分享的文件复制进选中的
 * 工作区目录，并算出该文件在容器里看到的路径（用来拼提示词）。
 *
 * 为什么能纯读盘列工作区（不走 dsh 的 WebSocket 控制流）：dsh 的工作区注册表是
 * storage-json 的 **single-layout** 单元，落在 `$DSH_HOME/storages/workspace.json`
 * （`dsh-base` 的 patch 把 storage-json 的 root 设成 `dshHomePath('storages')`，
 * 域名 `workspace` 无显式 layout → storage-json 默认 single → 整个单元一个 JSON 文件）。
 * 文档结构：`{ unit:{name,version}, global:{workspaceIds,…}, tables:{ workspaces:{ <id>:{path,title,sessionIds,…} } } }`。
 * 内存态是权威的，每次工作区写入都会原子重写回这个文件，所以**只读**它是安全且准确的。
 *
 * 会话标题是从每个会话 jsonl 日志 fold 出来的 projection、随会话格式版本漂移，所以这里
 * **不**原生列会话标题；按方案 A，会话树交给 Web UI，App 只到「选工作区（含会话数）」。
 */
object DshFileHandoff {

    /** 宿主共享存储根（与 [DshFileAccess] 对齐）。 */
    private const val HOST_ROOT = "/storage/emulated/0"

    /** 容器内共享存储的两个别名。 */
    private val GUEST_ALIASES = listOf("/sdcard", "/storage/emulated/0")

    /** 列表里的一个工作区条目。[title] 可能为空（合成的默认工作区），由 UI 回落展示。 */
    data class Workspace(
        val id: String,
        val title: String,
        /** 容器内的规范路径（会话 cwd），如 `/root/workspace`。 */
        val guestPath: String,
        val sessionCount: Int,
    )

    /**
     * 读 `$DSH_HOME/storages/workspace.json` 列出工作区，按注册表的显示顺序
     * （`global.workspaceIds`）排列，表里有而顺序表里没有的追加在后。
     *
     * 永远保证默认工作区 [DshEnv.WORKSPACE_GUEST] 在列：新装/没会话时注册表可能为空，
     * 而默认工作区始终是合法的「开始新对话」落点（dsh 启动即 cd 到那里）。
     */
    fun listWorkspaces(ctx: Context): List<Workspace> {
        val file = File(DshEnv.dshHome(ctx), "storages/workspace.json")
        val byId = LinkedHashMap<String, Workspace>()
        var order: List<String> = emptyList()
        runCatching {
            val root = JSONObject(file.readText())
            val workspaces = root.optJSONObject("tables")?.optJSONObject("workspaces") ?: JSONObject()
            val orderArr = root.optJSONObject("global")?.optJSONArray("workspaceIds")
            order = if (orderArr == null) emptyList() else (0 until orderArr.length()).map { orderArr.optString(it) }
            val keys = workspaces.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val rec = workspaces.optJSONObject(id) ?: continue
                val path = rec.optString("path").trim()
                if (path.isEmpty()) continue
                byId[id] = Workspace(
                    id = id,
                    title = rec.optString("title").trim(),
                    guestPath = normalizeGuest(path),
                    sessionCount = rec.optJSONArray("sessionIds")?.length() ?: 0,
                )
            }
        }

        val ordered = ArrayList<Workspace>()
        for (id in order) byId[id]?.let { ordered.add(it) }
        for ((id, ws) in byId) if (order.none { it == id }) ordered.add(ws)

        val default = normalizeGuest(DshEnv.WORKSPACE_GUEST)
        if (ordered.none { it.guestPath == default }) {
            ordered.add(0, Workspace(id = "", title = "", guestPath = default, sessionCount = 0))
        }
        return ordered
    }

    /**
     * 把文件内容流复制进某工作区目录。返回**容器内**的文件路径（拼提示词用）。
     * 重名不覆盖：追加 ` (n)`，不动用户已有的文件。
     */
    fun copyInto(ctx: Context, input: java.io.InputStream, workspaceGuest: String, fileName: String): Result<String> = runCatching {
        val guestBase = normalizeGuest(workspaceGuest)
        val hostDir = guestToHost(ctx, guestBase)
        if (!hostDir.exists() && !hostDir.mkdirs() && !hostDir.exists()) {
            throw java.io.IOException("cannot create workspace dir: ${hostDir.absolutePath}")
        }
        val target = dedupe(hostDir, sanitizeName(fileName))
        target.outputStream().use { out -> input.copyTo(out) }
        if (guestBase == "/") "/${target.name}" else "$guestBase/${target.name}"
    }

    /**
     * 容器内路径 → 宿主可写路径。
     * 1) 落在某条**工作区挂载** `/root/workspace/<dest>` 下 → 映到挂载源 `HOST_ROOT/<src>/…`
     *    （运行时该挂载点被 bind 遮住，必须写真正的源，否则写进去容器看不到）；
     * 2) 落在 `/sdcard` 或 `/storage/emulated/0` 下 → `HOST_ROOT/…`；
     * 3) 其余（rootfs 内，如 `/root/workspace` 本身）→ `<rootfs>/<path>`（App 私有、可写）。
     */
    internal fun guestToHost(ctx: Context, guest: String): File {
        val g = normalizeGuest(guest)
        if (DshFileAccess.wsMountEnabled(ctx)) {
            for (m in DshFileAccess.workspaceMounts(ctx)) {
                val base = normalizeGuest("${DshEnv.WORKSPACE_GUEST}/${m.dest}")
                if (g == base || g.startsWith("$base/")) {
                    val rest = g.removePrefix(base).trimStart('/')
                    val hostBase = if (m.src.isEmpty()) HOST_ROOT else "$HOST_ROOT/${m.src}"
                    return File(if (rest.isEmpty()) hostBase else "$hostBase/$rest")
                }
            }
        }
        for (alias in GUEST_ALIASES) {
            if (g == alias || g.startsWith("$alias/")) {
                val rest = g.removePrefix(alias).trimStart('/')
                return File(if (rest.isEmpty()) HOST_ROOT else "$HOST_ROOT/$rest")
            }
        }
        return File(DshEnv.rootfs(ctx), g.trimStart('/'))
    }

    /** 规范成前导 `/`、无尾随 `/` 的绝对路径（根保留为 `/`）。 */
    internal fun normalizeGuest(path: String): String {
        val trimmed = path.trim().replace('\\', '/').trim('/')
        return if (trimmed.isEmpty()) "/" else "/$trimmed"
    }

    /** 文件名去路径分隔符/控制字符，空则回落。 */
    internal fun sanitizeName(name: String): String {
        val base = name.trim().substringAfterLast('/').substringAfterLast('\\')
            .filter { it != '\u0000' && it >= ' ' }
            .trim()
        return base.ifBlank { "shared-file" }
    }

    /** 若 [dir] 下已有同名文件，在扩展名前追加 ` (n)`。 */
    internal fun dedupe(dir: File, name: String): File {
        val first = File(dir, name)
        if (!first.exists()) return first
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = File(dir, "$stem ($n)$ext")
            if (!candidate.exists()) return candidate
            n++
        }
    }
}
