package me.bmax.apatch.dsh

import android.content.Context

/**
 * 特权严格程度：决定**什么时候必须让用户点一下「允许」**。
 *
 * 与「档位」（[DshNativeBridge.Access]）是两件事：档位决定「这类事允不允许做」，
 * 严格程度决定「做的时候要不要每次都问一声」。默认 [STRICT] —— 特权是本应用能拿到的
 * 最高权限，默认应当最保守。
 */
enum class PrivStrictness(val id: String) {
    /** 每一次调用都要用户当场同意（弹窗只给「允许本次 / 拒绝」）。 */
    STRICT("strict"),

    /** 只读命令免确认，会改设备状态的要问一声。 */
    NORMAL("normal"),

    /** 档位内免确认，只有危险命令要问。 */
    LOOSE("loose"),
    ;

    companion object {
        val DEFAULT = STRICT

        /** 认不出来的值一律落到最保守的一档（prefs 被改坏、旧版本残留都算）。 */
        fun of(raw: String?): PrivStrictness = entries.firstOrNull { it.id == raw } ?: DEFAULT
    }
}

/**
 * 一条特权操作的风险等级。
 *
 * 三档而不是两档，是因为「只是看看」和「改完回不去」对用户的代价完全不同：
 * 前者免确认不会出事，后者哪怕在宽松档也值得问一声。
 */
internal enum class PrivRisk {
    /** 只读：无论怎么给参数都不改设备状态。 */
    READONLY,

    /** 会改状态，但影响范围清楚、容易恢复。 */
    WRITE,

    /** 改完不容易恢复，或者影响整机（卸载、重启、清数据）。 */
    DANGEROUS,
}

/**
 * 特权调用的策略判定。
 *
 * **唯一实现**：宿主侧的门禁（[DshNativeBridge]）与容器侧的提示词都引用这里，
 * `tools/check-native-logic.js` 再复刻一份逐档对拍 —— 这种「三行 when 但错了就是
 * 每次调用都静默放行」的判定，靠读代码是看不出来的。
 */
internal object PrivPolicy {
    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)

    fun of(ctx: Context): PrivStrictness =
        PrivStrictness.of(prefs(ctx).getString(DshEnv.KEY_PRIV_STRICTNESS, null))

    fun set(ctx: Context, strictness: PrivStrictness) {
        prefs(ctx).edit().putString(DshEnv.KEY_PRIV_STRICTNESS, strictness.id).apply()
    }

    /**
     * 这个能力是不是在「不再逐条确认」名单里。
     *
     * 名单按 [DshNativeBridge.Cap.id] 存；读出来先拷一份 —— `getStringSet` 返回的是 prefs
     * 内部的对象，直接改它会写坏（而且改不动）存储。
     */
    fun trusted(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(DshEnv.KEY_PRIV_TRUSTED_CAPS, emptySet())?.toSet() ?: emptySet()

    fun isTrusted(ctx: Context, cap: DshNativeBridge.Cap): Boolean = trusted(ctx).contains(cap.id)

    fun setTrusted(ctx: Context, cap: DshNativeBridge.Cap, on: Boolean) {
        val next = trusted(ctx).toMutableSet()
        if (on) next.add(cap.id) else next.remove(cap.id)
        prefs(ctx).edit().putStringSet(DshEnv.KEY_PRIV_TRUSTED_CAPS, next).apply()
    }

    /**
     * 这次调用要不要用户当场同意。
     *
     * 严格档对**所有**等级都返回 true，包括只读 —— 用户选严格就是选了「每次都问」，
     * 这里不能自作主张给只读开绿灯（那正是「宽松/一般」两档的用处）。
     *
     * @param trusted 这个能力是否被单独信任（见 [setTrusted]）。两个例外，缺一不可：
     *  - **危险操作永远要问**（[PrivRisk.DANGEROUS]）：信任不是万能钥匙，卸载/重启/清数据
     *    这类改完回不去的动作，不管什么档、不管信不信任，都要用户当场点头；
     *  - 其余等级上，信任 = 「这个能力别再逐条问」，这正是它存在的意义 —— 用户不必为了
     *    免掉虚拟屏的每条点击，把整机降到「宽松」。
     */
    fun needsConfirm(
        strictness: PrivStrictness,
        risk: PrivRisk,
        trusted: Boolean = false,
    ): Boolean {
        if (risk == PrivRisk.DANGEROUS) return true
        if (trusted) return false
        return when (strictness) {
            PrivStrictness.STRICT -> true
            PrivStrictness.NORMAL -> risk != PrivRisk.READONLY
            PrivStrictness.LOOSE -> false
        }
    }

    /** 严格档下弹窗不给「允许（长期）」：那与「每次都要同意」直接冲突。 */
    fun allowsPersistentGrant(strictness: PrivStrictness): Boolean =
        strictness != PrivStrictness.STRICT
}
