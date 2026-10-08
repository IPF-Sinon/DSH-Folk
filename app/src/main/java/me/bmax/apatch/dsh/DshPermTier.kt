package me.bmax.apatch.dsh

import android.content.Context

/**
 * 权限挡位：**这道 DSH 权限能不能用桥**。
 *
 * 它只回答这一件事，**不管别的**：不改变「某个能力开没开」（[DshNativeBridge] 的逐项档位），
 * 也不改变「用它之前要不要先问一句」（[PrivPolicy] 的限制模式）。被挡下的请求不会弹窗 ——
 * 挡位说的不是"要不要允许这一次"，而是"这一级根本不用桥"，弹窗只会把问题问错人。
 *
 * 档位对的是**桥自己的权限梯子**（[DshNativeBridge.Access]：read / write / read_write /
 * control，见 [DshNativeBridge.neededAccess] 与 [DshNativeBridge.levelCovers]），所以
 * 「完全权限」恰好到**读写**为止 —— 需要「控制」那一级的动作（例如系统通知开关那一个端点）
 * 仍不受理，要「自定义」才放行。「自定义」在梯子最高一级（等于把决定权交回逐项配置），
 * 所以它也是默认：此前没有任何挡位，默认必须等于旧行为，否则升级会把用户已经配好并授权过的
 * CONTROL 能力悄悄挡掉。
 *
 * | 挡位 | 权限桥（/native） | 文件桥（/fs） |
 * | --- | --- | --- |
 * | 仅可查看 | 只受理读 | 只受理读 |
 * | 工作区内修改 | 只受理读（/native 的写不是"工作区内的修改"，那是文件桥的事） | 只有 /root/workspace 下的写受理 |
 * | 完全权限 | 受理读与写，**不含** CONTROL | 读与写都受理 |
 * | 自定义（默认） | 不设上限：逐项档位（含 CONTROL）说了算 | 同上 |
 */
object DshPermTier {
    const val READ_ONLY = "read-only"
    const val WORKSPACE_WRITE = "workspace-write"
    const val FULL = "danger-full-access"

    /**
     * 自定义：桥档位梯子最高一级（CONTROL 之上），不设上限，逐项配置说了算。
     *
     * 单独一个值（而不是让用户把 [FULL] 当自定义）是为了让 UI 能如实说"现在是你自己那套配置
     * 在决定"，也让审计里能分清"用户明确要读写"与"用户不想被统一挡位管"。
     */
    const val CUSTOM = "custom"

    /** 界面顺序：从严到宽，最后是"自己配"。 */
    val OPTIONS = listOf(READ_ONLY, WORKSPACE_WRITE, FULL, CUSTOM)

    /**
     * 默认「自定义」。
     *
     * 此前没有任何挡位 = 逐项配置说了算（其中系统通知开关那个端点需要 CONTROL 那一档）。
     * 默认若取「完全权限」，升级会把用户已经配好并授权过的系统通知开关挡住 —— 默认值必须等于
     * 旧行为，这条在本仓是不成文的硬规矩（见 [normalize] 的注释）。
     */
    const val DEFAULT = CUSTOM

    /** 旧的 / 空的 / 非法的值一律回落 [DEFAULT]：认不出的值不能被解释成「更严」。 */
    fun normalize(value: String?): String =
        if (value != null && OPTIONS.contains(value)) value else DEFAULT

    fun tier(ctx: Context): String =
        normalize(prefs(ctx).getString(DshEnv.KEY_PERM_TIER, DEFAULT))

    fun setTier(ctx: Context, value: String) {
        prefs(ctx).edit().putString(DshEnv.KEY_PERM_TIER, normalize(value)).apply()
        // 事实里带着它：agent 得知道自己这一挡在哪，否则被 403 只会当成故障
        DshHostPrompt.writeFacts(ctx.applicationContext)
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)

    /**
     * 权限桥（`/native`）这一挡放行到桥档位的哪一级；`null` = 不设上限（自定义）。
     *
     * 比较交给桥自己做（[DshNativeBridge.levelCovers]），因为档位包含关系只有它那张表知道：
     * 按 [DshNativeBridge.accessOptions] 的下标比大小是错的（read 并不包含 write）。这里只
     * 给出"这一挡相当于哪一级"。
     */
    fun nativeCap(ctx: Context): DshNativeBridge.Access? = when (tier(ctx)) {
        // 工作区内修改在权限桥这一侧等于"只读"：/native 的写（点屏、发通知、改剪贴板…）都不是
        // "工作区内的修改"，那是文件桥的事。要放行它们得选「完全权限」。
        READ_ONLY, WORKSPACE_WRITE -> DshNativeBridge.Access.READ
        FULL -> DshNativeBridge.Access.READ_WRITE
        else -> null
    }

    /**
     * 文件桥（`/fs`）这次写请求要不要挡。
     *
     * [params] 里所有路径参数（`path` / `src` / `dst`）都必须落在工作区内 —— move/copy 有两个
     * 路径，只查其中一个等于留了个后门（从工作区外搬进来、或把工作区里的东西搬出去）。
     *
     * `ctx` 为空时（桥还没拿到 application context）不在这里挡：那时处理函数自己会以
     * `native_uninit` 失败，挡位不该替它背这个锅。
     */
    fun fsWriteBlocked(ctx: Context?, method: String, params: Map<String, String>): Boolean {
        if (ctx == null || !WRITE_METHODS.contains(method)) return false
        return when (tier(ctx)) {
            READ_ONLY -> true
            WORKSPACE_WRITE -> pathParams(params).any { !isUnderWorkspace(it) }
            else -> false
        }
    }

    /**
     * 被挡下时给容器侧的原因串（写进 `reason`，与 `disabled` / `no_access` 同一套词表）。
     *
     * [needControl] = 这条请求要的是 CONTROL 那一级（只有「完全权限」会因为这一条挡下），
     * 单独给一个原因：否则 agent 会以为是"挡位太低"，往上改一挡仍然被挡，白试一轮。
     */
    fun blockedReason(ctx: Context?, needControl: Boolean = false): String = when {
        needControl -> "tier_control"
        ctx != null && tier(ctx) == WORKSPACE_WRITE -> "tier_workspace"
        else -> "tier_readonly"
    }

    /**
     * 容器看到的路径在不在工作区里。
     *
     * 先按目录段消掉 `.` / `..` 再比前缀：`/root/workspace/../../etc` 直接做字符串前缀是"看着
     * 在工作区里"的，而它其实落在 /etc。往上的 `..` 越出根就判否。
     */
    internal fun isUnderWorkspace(guest: String): Boolean {
        val segs = ArrayList<String>()
        for (raw in guest.replace('\\', '/').split('/')) {
            when (val seg = raw.trim()) {
                "", "." -> {}
                ".." -> if (segs.isEmpty()) return false else segs.removeAt(segs.size - 1)
                else -> segs.add(seg)
            }
        }
        val p = "/" + segs.joinToString("/")
        return p == DshEnv.WORKSPACE_GUEST || p.startsWith("${DshEnv.WORKSPACE_GUEST}/")
    }

    private fun pathParams(params: Map<String, String>): List<String> =
        listOfNotNull(params["path"], params["src"], params["dst"]).filter { it.isNotBlank() }

    /** 文件桥里算"写"的方法。GET 一律不算。 */
    internal val WRITE_METHODS = setOf("PUT", "POST", "DELETE")
}
