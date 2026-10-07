package me.bmax.apatch.dsh

import android.content.Context

/**
 * 权限挡位：**权限桥在 DSH 内所需的最低权限**。
 *
 * 这是用户给容器里那份 agent 设的**天花板**，与「某个能力开没开」（[DshNativeBridge] 的逐项
 * 档位）和「要不要每次都问」（[PrivPolicy] 的限制模式）是三件不同的事：
 *
 * - 逐项档位回答「这个能力我用不用得上」（装一次之后长期有效）；
 * - 限制模式回答「用它之前要不要先问一句」；
 * - 挡位回答「**根本不谈**」——它比前两者都靠前，被挡下的请求不会弹窗（弹了等于把用户刚设的
 *   上限又拿回来问一遍），直接 403 并说明是哪一挡挡的。
 *
 * 四挡的语义（写死在 [fsWriteBlocked] / [nativeWriteAllowed] 里）：
 *
 * | 挡位 | 权限桥（/native） | 文件桥（/fs） |
 * | --- | --- | --- |
 * | 仅可查看 | 只放读类动作 | 只放 GET |
 * | 工作区内修改 | 只放读类动作 | 只有 /root/workspace 下的写放行 |
 * | 完全权限 | 现状（逐项档位与限制模式说了算） | 现状 |
 * | 自定义 | 同上，但由用户自己那套逐项配置说了算 | 同上 |
 *
 * 「完全权限」与「自定义」在**挡位这一层**是同一个意思（都不额外压），区别只是给用户的说法：
 * 前者是"我就是要全开"，后者是"别替我决定，我自己去逐项配"。所以两者都返回"不拦"，而不是
 * 让自定义去复制一份逐项档位表 —— 那张表已经有一处（[DshNativeBridge.accessOptions]）。
 *
 * 默认 [FULL]：与这一版之前的行为完全一致（此前没有任何挡位），所以升级不会悄悄收紧谁。
 */
object DshPermTier {
    const val READ_ONLY = "read-only"
    const val WORKSPACE_WRITE = "workspace-write"
    const val FULL = "danger-full-access"

    /**
     * 自定义：不额外压，逐项档位与限制模式说了算。
     *
     * 单独一个值（而不是让用户把 [FULL] 当自定义）是为了让 UI 能如实说"现在是你自己那套配置
     * 在决定"，也让审计里能分清"用户明确要全开"与"用户不想被统一挡位管"。
     */
    const val CUSTOM = "custom"

    /** 界面顺序：从严到宽，最后是"自己配"。 */
    val OPTIONS = listOf(READ_ONLY, WORKSPACE_WRITE, FULL, CUSTOM)

    const val DEFAULT = FULL

    /** 旧的 / 空的 / 非法的值一律回落 [DEFAULT]（与 [PermissionManager.normalize] 同一纪律）。 */
    fun normalize(value: String?): String =
        if (value != null && OPTIONS.contains(value)) value else DEFAULT

    fun tier(ctx: Context): String =
        normalize(prefs(ctx).getString(DshEnv.KEY_PERM_TIER, DEFAULT))

    fun setTier(ctx: Context, value: String) {
        prefs(ctx).edit().putString(DshEnv.KEY_PERM_TIER, normalize(value)).apply()
        // 事实里带着它：agent 得知道自己这一层天花板在哪，否则被 403 只会当成故障
        DshHostPrompt.writeFacts(ctx.applicationContext)
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)

    /** 权限桥（`/native`）的写类动作放不放行。 */
    fun nativeWriteAllowed(ctx: Context): Boolean = when (tier(ctx)) {
        READ_ONLY, WORKSPACE_WRITE -> false
        else -> true
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

    /** 被挡下时给容器侧的原因串（写进 `reason`，与 `disabled` / `no_access` 同一套词表）。 */
    fun blockedReason(ctx: Context?): String =
        if (ctx != null && tier(ctx) == WORKSPACE_WRITE) "tier_workspace" else "tier_readonly"

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
