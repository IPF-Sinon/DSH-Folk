package me.bmax.apatch.dsh

import android.content.Context
import org.json.JSONArray

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
    private fun isUnderOrEqual(child: String, parent: String): Boolean {
        if (child == parent) return true
        return child.startsWith("$parent/")
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
}
