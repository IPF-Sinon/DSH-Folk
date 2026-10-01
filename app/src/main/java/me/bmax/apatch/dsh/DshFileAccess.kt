package me.bmax.apatch.dsh

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/**
 * 手机文件访问的黑白名单策略，直接作用在**容器 bind 挂载**这一层。
 *
 * ## 为什么在挂载层做
 *
 * `/storage/emulated/0` 是**无条件** bind 进容器的（[ContainerRuntime] 的存储绑定），一旦 App 拿到
 * 「所有文件访问」，容器里任何进程（dsh 本体、插件、终端）都能直接读写整棵 `/sdcard`。仅在
 * `DshFsBridge`（受控 HTTP 接口）上拦是**假隔离**——那只是众多入口里的一个，绕过它经 bind 挂载
 * 照样能读相册。真正生效的唯一办法是改挂载本身：
 *
 * - **黑名单**：给每个被禁目录叠一条 bind，用一个空目录（[DshEnv.fsMaskDir]）盖在它上面，容器里
 *   看到的就是个空文件夹（proot/proroot 后加的更具体 guest 路径覆盖前面的整棵树绑定）。
 * - **白名单**（非空时启用）：不再 bind 整棵 `/storage/emulated/0`，改成只 bind 勾选的子目录，
 *   其余一律不映进容器。
 *
 * 挂载在容器**启动那一刻**定死，改名单必须**重启容器**才生效——UI 改完要提示用户重启。
 *
 * ## 规则（用户 2026-09-26 定）
 *
 * - 白名单、黑名单**各自独立**，各自选了目录即视为启用。
 * - 默认黑名单 = [DEFAULT_DENY]（相册类：DCIM / Pictures / Movies / Android/media）。
 *   偏好里**缺失** = 用默认；**显式空数组** = 用户清空了、谁都不禁。
 * - 同一名单内若加了某目录的**上级**，则上级覆盖其下级条目（去重规整，见 [normalize]）。
 * - 两名单同时启用时**黑名单优先**：先按白名单圈定范围，再从中扣掉黑名单命中的部分。
 */
object DshFileAccess {

    /** 默认黑名单：相册类目录（相对 /sdcard）。 */
    val DEFAULT_DENY: List<String> = listOf("DCIM", "Pictures", "Movies", "Android/media")

    /** 宿主共享存储根。 */
    private const val HOST_ROOT = "/storage/emulated/0"

    /** 容器内看到共享存储的两个别名（历史上一直双挂）。 */
    private val GUEST_ALIASES = listOf("/sdcard", "/storage/emulated/0")

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)

    private fun readArray(raw: String?): List<String>? {
        if (raw == null) return null
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.optString(it) }
        }.getOrNull()
    }

    private fun writeArray(list: List<String>): String {
        val a = JSONArray()
        for (s in list) a.put(s)
        return a.toString()
    }

    /**
     * 规整一份名单：去空白、去首尾斜杠、统一分隔符、去重，并让**上级覆盖下级**
     * （若某条目落在另一条目之下，则丢弃这个更深的条目——上级已经覆盖它了）。
     */
    fun normalize(input: List<String>): List<String> {
        val cleaned = input
            .map { it.trim().replace('\\', '/').trim('/') }
            .filter { it.isNotEmpty() }
            .distinct()
        // 保留「不被别的条目覆盖」的那些：a 被 b 覆盖 ⇔ a == b 的子路径（段边界）
        val kept = ArrayList<String>()
        for (a in cleaned) {
            val coveredByOther = cleaned.any { b -> b != a && isUnderOrEqual(a, b) }
            if (!coveredByOther) kept.add(a)
        }
        // 去掉「互为同名」的重复（isUnderOrEqual 对相等为真，上面的 b != a 已排除自身）
        return kept.distinct()
    }

    /** [child] 是否等于 [parent] 或落在其下（按目录段边界，不误伤 Pictures2 这种同前缀兄弟）。 */
    internal fun isUnderOrEqual(child: String, parent: String): Boolean {
        if (child == parent) return true
        return child.startsWith("$parent/")
    }

    /** 共享存储挂载总开关（默认开）。关＝不挂载 + dsh-fs 也拒绝。 */
    fun mountEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(DshEnv.KEY_STORAGE_MOUNT, true)

    fun setMountEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(DshEnv.KEY_STORAGE_MOUNT, on).apply()
    }

    /**
     * 某个相对 /sdcard 的路径在当前黑白名单下是否放行——供 [DshFsBridge] 用，使桥的可见范围
     * 与挂载遮罩**语义一致**（这是把「假隔离」补成真隔离的关键：桥不再绕过名单）。
     *
     * 判据（与 [storageBinds] 对齐）：
     * - 命中黑名单（等于或落在某被禁目录之下）→ 拒。
     * - 无白名单 → 其余全放行。
     * - 有白名单：根（空串）放行（供列根，逐项再判）；否则要么落在某白名单目录内/相等、
     *   要么是某白名单目录的祖先（可下钻）才放行。
     */
    fun pathAllowed(ctx: Context, relative: String): Boolean {
        val rel = relative.trim().replace('\\', '/').trim('/')
        val deny = denyDirs(ctx)
        if (deny.any { isUnderOrEqual(rel, it) }) return false
        val allow = allowDirs(ctx)
        if (allow.isEmpty()) return true
        if (rel.isEmpty()) return true
        return allow.any { a -> isUnderOrEqual(rel, a) || isUnderOrEqual(a, rel) }
    }

    /** 当前白名单（已规整）。空 = 未设白名单。 */
    fun allowDirs(ctx: Context): List<String> =
        normalize(readArray(prefs(ctx).getString(DshEnv.KEY_FS_ALLOW_DIRS, null)) ?: emptyList())

    /** 当前黑名单（已规整）。偏好缺失时用 [DEFAULT_DENY]；显式空数组则为空。 */
    fun denyDirs(ctx: Context): List<String> {
        val stored = readArray(prefs(ctx).getString(DshEnv.KEY_FS_DENY_DIRS, null))
        return normalize(stored ?: DEFAULT_DENY)
    }

    fun setAllowDirs(ctx: Context, list: List<String>) {
        prefs(ctx).edit().putString(DshEnv.KEY_FS_ALLOW_DIRS, writeArray(normalize(list))).apply()
    }

    fun setDenyDirs(ctx: Context, list: List<String>) {
        // 写显式数组（哪怕是空）——空数组语义是「用户清空了黑名单」，不能回落到默认
        prefs(ctx).edit().putString(DshEnv.KEY_FS_DENY_DIRS, writeArray(normalize(list))).apply()
    }

    /** 黑名单偏好是否还没被用户动过（用来在 UI 上区分「默认」与「用户清空」）。 */
    fun denyIsDefault(ctx: Context): Boolean =
        prefs(ctx).getString(DshEnv.KEY_FS_DENY_DIRS, null) == null

    /**
     * 组装共享存储的 bind 列表（host, guest），已把黑白名单落进去。顺序即应用顺序：
     * 整棵树/白名单目录在前，遮蔽（空目录盖被禁目录）在后——后者覆盖前者。
     *
     * @param maskPath 空目录的宿主绝对路径（[DshEnv.fsMaskDir]）
     */
    fun storageBinds(ctx: Context, maskPath: String): List<Pair<String, String>> {
        val allow = allowDirs(ctx)
        val deny = denyDirs(ctx)
        val out = ArrayList<Pair<String, String>>()

        if (allow.isEmpty()) {
            // 无白名单：整棵树都映进来，再逐个遮蔽被禁目录
            for (alias in GUEST_ALIASES) {
                out.add(HOST_ROOT to alias)
                for (d in deny) out.add(maskPath to "$alias/$d")
            }
        } else {
            // 有白名单：只映勾选目录（黑名单优先——被黑名单覆盖的白名单目录整个不映）
            for (a in allow) {
                if (deny.any { isUnderOrEqual(a, it) }) continue // a 落在某个被禁目录之下/相等 → 不映
                for (alias in GUEST_ALIASES) {
                    out.add("$HOST_ROOT/$a" to "$alias/$a")
                    // 黑名单若落在这个白名单目录之内，仍要在其内部遮蔽
                    for (d in deny) if (isUnderOrEqual(d, a) && d != a) out.add(maskPath to "$alias/$d")
                }
            }
        }
        return out
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  工作区挂载：把手机存储按自定义映射额外 bind 到 /root/workspace 下
    // ──────────────────────────────────────────────────────────────────────────

    /** 一条工作区挂载映射：把 /sdcard/[src] 挂到 /root/workspace/[dest]。src 空串 = 整棵 /sdcard。 */
    data class WsMount(val src: String, val dest: String)

    /** 默认映射：整棵 /sdcard → /root/workspace/sdcard。 */
    val DEFAULT_WS_MOUNTS: List<WsMount> = listOf(WsMount("", "sdcard"))

    /** 「在工作区中挂载手机存储」子开关（默认关）。 */
    fun wsMountEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(DshEnv.KEY_WS_MOUNT, false)

    fun setWsMountEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(DshEnv.KEY_WS_MOUNT, on).apply()
        DshHostPrompt.writeFacts(ctx.applicationContext)
    }

    /**
     * 规整 dest（工作区下的相对子路径）：转 `/`、去首尾 `/`、丢弃 `.`/`..` 段（禁止越界），
     * 结果为空则回落 `sdcard`。
     */
    internal fun normalizeDest(input: String): String {
        val segs = input.trim().replace('\\', '/').split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
        val joined = segs.joinToString("/")
        return if (joined.isEmpty()) "sdcard" else joined
    }

    /**
     * 当前工作区挂载映射（已规整）。缺失 / 空数组 → [DEFAULT_WS_MOUNTS]。
     * src 按 [normalize] 规整（同黑白名单条目语义），dest 按 [normalizeDest] 规整；
     * 按 dest 去重（同一目的只保留第一条，避免两条映射抢同一挂载点）。
     */
    fun workspaceMounts(ctx: Context): List<WsMount> {
        val raw = prefs(ctx).getString(DshEnv.KEY_WS_MOUNTS, null)
        val parsed: List<WsMount>? = if (raw == null) null else runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val src = normalize(listOf(o.optString("src", ""))).firstOrNull() ?: ""
                val dest = normalizeDest(o.optString("dest", ""))
                WsMount(src, dest)
            }
        }.getOrNull()
        val list = parsed?.takeIf { it.isNotEmpty() } ?: DEFAULT_WS_MOUNTS
        val seen = HashSet<String>()
        return list.filter { seen.add(it.dest) }
    }

    fun setWorkspaceMounts(ctx: Context, list: List<WsMount>) {
        val a = JSONArray()
        val seen = HashSet<String>()
        for (m in list) {
            val src = normalize(listOf(m.src)).firstOrNull() ?: ""
            val dest = normalizeDest(m.dest)
            if (!seen.add(dest)) continue
            a.put(JSONObject().put("src", src).put("dest", dest))
        }
        prefs(ctx).edit().putString(DshEnv.KEY_WS_MOUNTS, a.toString()).apply()
        DshHostPrompt.writeFacts(ctx.applicationContext)
    }

    /**
     * 组装工作区挂载的 bind 列表（host, guest），已套用黑白名单。子开关关 → 空表。
     *
     * 每条映射 `{src, dest}`（guestBase = `/root/workspace/<dest>`）：
     * - src 命中黑名单（等于或落在某被禁目录之下）→ 整条跳过；
     * - **无白名单**：把整个 `/storage/emulated/0[/src]` 映到 guestBase，再把落在 src 内部的
     *   被禁子目录用空目录（[maskPath]）盖住（base 在前、mask 在后覆盖）；
     * - **有白名单**（黑名单优先）：只映「落在 src 内」的白名单目录到 guestBase 下对应位置，
     *   其余一律不进工作区——不因走了工作区这条路就绕过白名单（避免「假隔离」）。
     */
    fun workspaceBinds(ctx: Context, maskPath: String): List<Pair<String, String>> {
        if (!wsMountEnabled(ctx)) return emptyList()
        val allow = allowDirs(ctx)
        val deny = denyDirs(ctx)
        val out = ArrayList<Pair<String, String>>()
        for (m in workspaceMounts(ctx)) {
            val src = m.src
            val guestBase = "${DshEnv.WORKSPACE_GUEST}/${m.dest}"
            // src 本身被黑名单覆盖 → 整条不挂
            if (deny.any { isUnderOrEqual(src, it) }) continue
            if (allow.isEmpty()) {
                // 无白名单：整个 src 映进来，再遮蔽落在 src 内部的被禁子目录
                out.add(hostUnder(src) to guestBase)
                for (d in deny) if (contains(src, d) && d != src) {
                    out.add(maskPath to "$guestBase/${relUnder(d, src)}")
                }
            } else {
                // 有白名单（黑名单优先）：只映「落在 src 内」的白名单目录
                for (a in allow) {
                    if (!contains(src, a)) continue                // a 不在这条映射范围内
                    if (deny.any { isUnderOrEqual(a, it) }) continue // a 被黑名单盖掉
                    val relA = relUnder(a, src)
                    val guest = if (relA.isEmpty()) guestBase else "$guestBase/$relA"
                    out.add(hostUnder(a) to guest)
                    // a 内部的被禁子目录仍要遮蔽
                    for (d in deny) if (isUnderOrEqual(d, a) && d != a) {
                        out.add(maskPath to "$guest/${relUnder(d, a)}")
                    }
                }
            }
        }
        return out
    }

    /** [base] 是否包含 [child]（base 空串 = /sdcard 根，包含一切）。 */
    private fun contains(base: String, child: String): Boolean =
        base.isEmpty() || isUnderOrEqual(child, base)

    /** 宿主共享存储下某相对路径的绝对路径（空串 = 整棵 [HOST_ROOT]）。 */
    private fun hostUnder(rel: String): String =
        if (rel.isEmpty()) HOST_ROOT else "$HOST_ROOT/$rel"

    /** [child] 相对 [parent] 的路径（child 落在 parent 内/相等；parent 空 = 相对 /sdcard 根）。 */
    private fun relUnder(child: String, parent: String): String =
        when {
            parent.isEmpty() -> child
            child == parent -> ""
            else -> child.substring(parent.length + 1)
        }

    // ──────────────────────────────────────────────────────────────────────────
    //  共享存储的文件系统能力探测
    // ──────────────────────────────────────────────────────────────────────────

    private const val TAG = "DshFileAccess"

    /** [storageLinkSupported] 的缓存（null = 还没探过）。 */
    @Volatile
    private var storageLinkOk: Boolean? = null

    /**
     * 共享存储（[HOST_ROOT]）是否支持**真硬链接**。
     *
     * 为什么需要单独探一次：[DshRuntime.hardlinkSupported] 只探 **rootfs** 所在文件系统
     * （ext4 → true），于是 proot 不加 `--link2symlink`；但链接能力是**逐挂载点**的。
     * dsh 的 write 工具用 `link(临时文件, 目标)` 发布，而共享存储（sdcardfs/FUSE）不支持
     * 硬链接 —— 一旦把手机存储挂进工作区（[workspaceBinds]），容器内就出现了指向「不支持
     * 硬链接的文件系统」的可写路径，write 工具在那里直接报 `EINVAL: invalid argument, link`。
     *
     * 这是**探测**不是**开关**：proot 的 `--link2symlink` 是全局的，按挂载点开不了，而且它会
     * 把所有 `link()` 改写成符号链接（反而制造悬空链接、破坏 pnpm，见
     * [DshRuntime.linkBecomesSymlink]）。所以这里只把事实告诉用户（UI 显著警告），不改挂载。
     *
     * 无「所有文件访问」权限也**能**探：探针放在 App 专属外部目录（同一 emulated 卷）。只有
     * 连那里都拿不到（外部存储未挂载等）才退回 [HOST_ROOT]；此时探针可能因权限失败而返回
     * false，UI 的警告与权限提示并存，语义仍成立。
     */
    fun storageLinkSupported(ctx: Context): Boolean {
        storageLinkOk?.let { return it }
        synchronized(this) {
            storageLinkOk?.let { return it }
            // 探针放在 App 专属外部目录（同一 emulated 卷、无需「所有文件访问」权限），
            // 避免因根目录不可写而**误报**「不支持」；拿不到时退回共享存储根。
            // 链接能力是**按文件系统**的，探哪儿结论都一样。
            val dir = ctx.getExternalFilesDir(null) ?: File(HOST_ROOT)
            val src = File(dir, ".dshfolk-sdlinkprobe")
            val dst = File(dir, ".dshfolk-sdlinkprobe.hl")
            var ok = false
            var detail = ""
            try {
                src.delete(); dst.delete()
                Files.write(src.toPath(), byteArrayOf('o'.code.toByte(), 'k'.code.toByte()))
                Files.createLink(dst.toPath(), src.toPath())
                ok = dst.isFile && dst.length() == 2L
                if (!ok) detail = "link() 成功但目标不可读"
            } catch (e: Throwable) {
                ok = false
                detail = "${e.javaClass.simpleName}: ${e.message}"
            } finally {
                runCatching { src.delete() }
                runCatching { dst.delete() }
            }
            Log.i(TAG, "共享存储硬链接=$ok${if (detail.isEmpty()) "" else "（$detail）"}")
            storageLinkOk = ok
            return ok
        }
    }

    /** 清掉 [storageLinkSupported] 的缓存，供 UI「重新检测」用。 */
    fun resetStorageLinkProbe() {
        synchronized(this) { storageLinkOk = null }
    }
}
