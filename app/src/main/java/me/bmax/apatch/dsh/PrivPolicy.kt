package me.bmax.apatch.dsh

import android.content.Context

/**
 * 一条特权操作的风险等级。
 *
 * 三档而不是两档，是因为「只是看看」和「改完回不去」对用户的代价完全不同：
 * 前者免确认不会出事，后者哪怕在限制模式关着也值得问一声。
 *
 * 等级本身不再直接决定「问不问」（那是两张清单的事，见 [PrivPolicy]），它用来：
 * 识别只读命令（[PrivilegedShell.isReadonly] 的对照）、给审计与返回值一个稳定的语义
 * （`risk: dangerous` 这条记录不随策略改动而变），以及做「危险操作清单」的内置内容。
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
 * 特权调用的策略判定。**唯一实现**：宿主侧的门禁（[DshNativeBridge]）与容器侧的提示词都
 * 引用这里，`tools/check-native-logic.js` 再复刻一份逐格对拍 —— 这种「三行 when 但错了就是
 * 每次调用都静默放行」的判定，靠读代码是看不出来的。
 *
 * ## 模型：档位 + 限制模式 + 两张清单
 *
 * - **档位**（[DshNativeBridge.Access]，按能力）决定「允不允许做」。首次调用某能力必然弹一次
 *   （默认是空集，所以这是唯一绕不过去的边界），弹窗的「允许（长期）」改的就是它。
 * - **[restrictMode] 关**（默认）：能力启用之后不再逐条问。
 * - **[restrictMode] 开**：[restrictedCaps] 里的能力**每次都问**，不在清单里的直接通过。
 * - **[dangerHit] 命中**：永远问，与开关无关 —— 条目本身可删（删掉即沉默），也可增补。
 *
 * ## 为什么删掉了「严格程度」三档与「信任名单」
 *
 * 它们与档位重叠，组合出来的效果解释不清：真机上先后出现过「加进不再逐条确认也照样弹」
 * 与「这个开关没用」两种反馈。更要紧的是旧模型里 per-call 那道闸**只覆盖三个能力**
 * （[DshNativeBridge] 的 `when (cap)` 对其余能力返回 null）—— 短信、相机、麦克风、定位
 * 在能力启用之后再没被问过。新的能力清单对所有能力一视同仁：把 [Cap.CAMERA] 之类放进清单
 * 才是**新增**的一道闸，而不是把旧行为换个名字。
 *
 * ## 迁移
 *
 * 旧键（`priv_strictness` / `priv_trusted_caps`）只在 [migrateOnce] 里被读一次：**一律关**，
 * 只有「确实有旧 prefs」这一个事实用来决定要不要弹一次「策略已改变」的说明（见
 * [policyNoticePending]）。
 */
internal object PrivPolicy {
    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)

    // ───────────────── 限制模式 ─────────────────

    /** 限制模式开关。默认**关**：能力启用之后直接通过。 */
    fun restrictMode(ctx: Context): Boolean =
        prefs(ctx).getBoolean(DshEnv.KEY_PRIV_RESTRICT_MODE, false)

    fun setRestrictMode(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(DshEnv.KEY_PRIV_RESTRICT_MODE, on).apply()
    }

    /** 能力清单的默认内容：打开限制模式后这五项每次都要申请。 */
    val DEFAULT_RESTRICTED: Set<String> = linkedSetOf(
        DshNativeBridge.Cap.SHELL.id,
        DshNativeBridge.Cap.DISPLAY.id,
        DshNativeBridge.Cap.CAMERA.id,
        DshNativeBridge.Cap.MIC.id,
        DshNativeBridge.Cap.SMS.id,
    )

    /**
     * 能力清单。
     *
     * 键不存在时给 [DEFAULT_RESTRICTED]（而不是空集）：开关关着时清单不生效，但用户一拨开关
     * 就该立刻是"这五项要申请"，否则「默认内容」等于没写。读出来先拷一份 —— `getStringSet`
     * 返回的是 prefs 内部对象，直接改它会写坏存储。
     */
    fun restrictedCaps(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(DshEnv.KEY_PRIV_RESTRICT_CAPS, null)?.toSet() ?: DEFAULT_RESTRICTED

    fun isRestricted(ctx: Context, cap: DshNativeBridge.Cap): Boolean =
        restrictedCaps(ctx).contains(cap.id)

    fun setCapRestricted(ctx: Context, cap: DshNativeBridge.Cap, on: Boolean) {
        val next = restrictedCaps(ctx).toMutableSet()
        if (on) next.add(cap.id) else next.remove(cap.id)
        prefs(ctx).edit().putStringSet(DshEnv.KEY_PRIV_RESTRICT_CAPS, next).apply()
    }

    /** 恢复默认：删掉键，于是 [restrictedCaps] 又回到 [DEFAULT_RESTRICTED]。 */
    fun resetRestrictedCaps(ctx: Context) {
        prefs(ctx).edit().remove(DshEnv.KEY_PRIV_RESTRICT_CAPS).apply()
    }

    // ───────────────── 危险操作清单 ─────────────────

    /** 内置条目（内容与 [PrivilegedShell] 的内置表同一份，避免两处各写一份迟早不一致）。 */
    val BUILTIN_DANGER: List<String> = PrivilegedShell.builtinDangerEntries()

    /** 用户自己加的条目（按行存："rm"、"pm uninstall"）。 */
    fun addedDanger(ctx: Context): List<String> =
        prefs(ctx).getString(DshEnv.KEY_PRIV_DANGER_ADDED, "")
            .orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

    /** 内置条目里被用户删掉的那些（存 id 而不是"剩下的"：内置表以后加了新条目也能自动生效）。 */
    fun disabledBuiltinDanger(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(DshEnv.KEY_PRIV_DANGER_DISABLED, emptySet())?.toSet() ?: emptySet()

    /** 当前生效的清单：内置（减去被删的）+ 用户增补。 */
    fun activeDanger(ctx: Context): List<String> {
        val off = disabledBuiltinDanger(ctx)
        return (BUILTIN_DANGER.filter { it !in off } + addedDanger(ctx)).distinct()
    }

    /**
     * 加一条。只接受"命令"或"命令 子命令"两段 —— 不收正则、不收 shell 元字符：这份清单是
     * 给用户在手机上敲的，一个写错的正则会让某条命令**静默免问**，比不支持正则危险得多。
     */
    fun addDangerEntry(ctx: Context, raw: String): Boolean {
        val entry = normalizeDangerEntry(raw) ?: return false
        if (entry in activeDanger(ctx)) return false
        // 删过同名内置条目又手工加回来：不算"新增"，而是把那条恢复生效
        if (entry in BUILTIN_DANGER && entry in disabledBuiltinDanger(ctx)) {
            val next = disabledBuiltinDanger(ctx).toMutableSet()
            next.remove(entry)
            prefs(ctx).edit().putStringSet(DshEnv.KEY_PRIV_DANGER_DISABLED, next).apply()
            return true
        }
        val next = addedDanger(ctx) + entry
        prefs(ctx).edit().putString(DshEnv.KEY_PRIV_DANGER_ADDED, next.joinToString("\n")).apply()
        return true
    }

    /** 删一条：内置的记进"被删"集合（可恢复默认），用户加的从增补里移除。 */
    fun removeDangerEntry(ctx: Context, entry: String) {
        val e = normalizeDangerEntry(entry) ?: return
        if (e in BUILTIN_DANGER) {
            val next = disabledBuiltinDanger(ctx).toMutableSet()
            next.add(e)
            prefs(ctx).edit().putStringSet(DshEnv.KEY_PRIV_DANGER_DISABLED, next).apply()
            return
        }
        val next = addedDanger(ctx).filter { it != e }
        prefs(ctx).edit().putString(DshEnv.KEY_PRIV_DANGER_ADDED, next.joinToString("\n")).apply()
    }

    fun resetDangerList(ctx: Context) {
        prefs(ctx).edit()
            .remove(DshEnv.KEY_PRIV_DANGER_ADDED)
            .remove(DshEnv.KEY_PRIV_DANGER_DISABLED)
            .apply()
    }

    /**
     * 这条命令命中了清单吗。
     *
     * 判据与 [PrivilegedShell.riskOf] 的内置表同形：取首个 token 的 basename 与第二个 token，
     * 条目只写命令名 = 该命令的任何用法都算。
     */
    fun dangerHit(ctx: Context, command: String): Boolean {
        val entries = activeDanger(ctx)
        if (entries.isEmpty()) return false
        val parts = command.trim().split(Regex("\\s+"))
        val name = parts.getOrNull(0).orEmpty().substringAfterLast('/')
        if (name.isEmpty()) return false
        val sub = parts.getOrNull(1).orEmpty()
        return entries.any { entryMatches(it, name, sub) }
    }

    /**
     * shell 的风险等级：内置只读判据打底，再叠上清单。
     *
     * 用户在清单里**删掉**一条内置危险命令时，它降级成 [PrivRisk.WRITE]（于是开关关着就不问）
     * —— 这正是"可增删"的意义。反过来，清单里加进 `settings put` 之类的自定义条目会升成
     * [PrivRisk.DANGEROUS]，审计里的 `risk` 与提示词讲的是同一件事。
     */
    fun shellRisk(ctx: Context, command: String): PrivRisk {
        val builtin = PrivilegedShell.riskOf(command)
        if (builtin == PrivRisk.READONLY) return PrivRisk.READONLY
        if (dangerHit(ctx, command)) return PrivRisk.DANGEROUS
        return PrivRisk.WRITE
    }

    // ───────────────── 判定 ─────────────────

    /**
     * 这次调用要不要用户当场同意。
     *
     * 两个来源，缺一不可：
     *  - [danger]：命中危险操作清单 —— **与开关无关**（清单可删条目，那就是"不再问它"的出口）；
     *  - [restrictMode] 开着且这个能力在清单里。
     */
    fun needsConfirm(
        restrictMode: Boolean,
        capRestricted: Boolean,
        danger: Boolean,
    ): Boolean = danger || (restrictMode && capRestricted)

    // ───────────────── 迁移与一次性提示 ─────────────────

    /**
     * 把旧模型（严格程度 + 信任名单）迁到新模型。**只跑一次**，在应用启动时调。
     *
     * 结果按用户拍板**一律关**：不保留任何"迁移后还照样问"的行为，代价由一次显式提示补偿
     * （[policyNoticePending]）。旧键读完即删 —— 留着就会有人读到它，而那一套语义已经不存在。
     */
    fun migrateOnce(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean(DshEnv.KEY_PRIV_MIGRATED, false)) return
        val hadOld = p.contains(DshEnv.KEY_PRIV_STRICTNESS) || p.contains(DshEnv.KEY_PRIV_TRUSTED_CAPS)
        p.edit()
            .putBoolean(DshEnv.KEY_PRIV_MIGRATED, true)
            // 只有"确实用过旧模型"的人需要被告知策略变了：新装的人没有参照物
            .putBoolean(DshEnv.KEY_PRIV_POLICY_NOTICE, hadOld)
            .putBoolean(DshEnv.KEY_PRIV_RESTRICT_MODE, false)
            .remove(DshEnv.KEY_PRIV_STRICTNESS)
            .remove(DshEnv.KEY_PRIV_TRUSTED_CAPS)
            .apply()
    }

    /** 待弹的「策略已改变」提示（迁移时置位，弹过一次即清）。 */
    fun policyNoticePending(ctx: Context): Boolean =
        prefs(ctx).getBoolean(DshEnv.KEY_PRIV_POLICY_NOTICE, false)

    fun consumePolicyNotice(ctx: Context) {
        prefs(ctx).edit().putBoolean(DshEnv.KEY_PRIV_POLICY_NOTICE, false).apply()
    }

    // ───────────────── 内部 ─────────────────

    /**
     * 条目规范化：首尾空白去掉、连续空白压成一个空格，只允许"命令"或"命令 子命令"。
     *
     * 命令名允许 `/system/bin/pm` 这种带路径的写法（与 [PrivilegedShell] 的 basename 判据对齐），
     * 但两段都只能是普通字符 —— shell 元字符混进来会让条目匹配到不该匹配的东西。
     */
    private fun normalizeDangerEntry(raw: String): String? {
        val parts = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 2) return null
        val ok = Regex("^[A-Za-z0-9_./-]+$")
        if (parts.any { !ok.matches(it) }) return null
        return parts.joinToString(" ")
    }

    private fun entryMatches(entry: String, name: String, sub: String): Boolean {
        val parts = entry.split(Regex("\\s+"))
        val en = parts.getOrNull(0).orEmpty().substringAfterLast('/')
        if (en != name) return false
        val es = parts.getOrNull(1).orEmpty()
        // 只写命令名 = 该命令的任何用法都算（内置的 rm、reboot 就是这一类）
        return es.isEmpty() || es == sub
    }
}
