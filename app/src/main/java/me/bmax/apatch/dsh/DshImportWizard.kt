package me.bmax.apatch.dsh

/**
 * 恢复向导的状态机与决策逻辑。
 *
 * 单独放在 `dsh` 包、**不依赖任何 Android / Compose 类型**，是为了让这套判据能被静态检查
 * 钉住：向导出错的形态都很安静（该问的没问、逐条决策没生效、未决就放行），编译器和肉眼
 * 都拦不住，只有把「什么情况下能继续」写成一个可被检查的函数才行。
 *
 * 流程与容器里 dsh-config-manager 的导入向导对齐（它的 `import-stepper.ts` 把整个过程
 * 收敛成 6 个用户可见阶段）：
 *
 * ```
 * 选择 ── 分析 ── 预览与决策 ── 确认 ── 恢复中 ── 完成
 * SELECT  ANALYZE   DECIDE       CONFIRM  EXECUTE   DONE
 * ```
 *
 * 我们这边多出一个内部步骤 [WizardStep.PREVIEW]（计划摘要）—— 它落在插件的「决策」阶段里，
 * 不额外占用一个阶段名：对用户来说「看一遍将发生什么，再决定冲突怎么办」是同一件事。
 *
 * **不做**的两步（插件的向导里有，这里故意没有）：
 * - 路径映射：App 侧已经在导入前把缺失的工作区目录补建好（[DshConfigBackup] 的
 *   `ensureWorkspaceDirs`），把一条已经自动处理好的事情再问一遍，只会让人以为还要动手。
 * - 凭据补录：包里有凭据原文时我们直接交给插件（`secretInputs`），没带原文时补录框也变不出
 *   密码来 —— 那不是「补录」，是让用户重新想一遍他已经忘了的东西。
 */
enum class WizardStep {
    /** 选包 + 填密码。 */
    SELECT,

    /** 预检进行中（解容器 → 数会话 → 上传 → 分析 → 试规划）。 */
    ANALYZE,

    /** 计划摘要：这次会改动什么、涉及哪些分区、要不要重启。 */
    PREVIEW,

    /** 决策：包里有会话时怎么处理、每条冲突保留哪一边。 */
    DECIDE,

    /** 确认：回滚策略 + 一句「现在开始写盘」。 */
    CONFIRM,

    /** 执行中。 */
    EXECUTE,

    /** 结果与「下一步」。 */
    RESULT,
}

/** 用户可见的阶段（与插件导入向导的 stepper 一一对应）。 */
enum class WizardStage { SELECT, ANALYZE, DECIDE, CONFIRM, EXECUTE, DONE }

/**
 * 一条冲突的两种处置。**取值就是插件的协议值**（`ItemResolution`：
 * `keepCurrent` / `useImported`），不要在别处再拼一次字符串 —— 拼错了插件会当成无效决策，
 * 表现为「用户选了用包里的，实际保留的还是本机」。
 */
enum class ConflictChoice(val wire: String) {
    /** 保留本机现有内容（合并语义，不会覆盖）。 */
    KEEP_CURRENT("keepCurrent"),

    /** 用备份里的内容覆盖本机。 */
    USE_IMPORTED("useImported"),
}

object DshImportWizard {

    /** 内部步骤 → 用户可见阶段。 */
    fun stageOf(step: WizardStep): WizardStage = when (step) {
        WizardStep.SELECT -> WizardStage.SELECT
        WizardStep.ANALYZE -> WizardStage.ANALYZE
        // 预览与决策是同一个用户阶段的上下两屏
        WizardStep.PREVIEW, WizardStep.DECIDE -> WizardStage.DECIDE
        WizardStep.CONFIRM -> WizardStage.CONFIRM
        WizardStep.EXECUTE -> WizardStage.EXECUTE
        WizardStep.RESULT -> WizardStage.DONE
    }

    /** 阶段总数（画 stepper 用）。 */
    val STAGE_COUNT: Int = WizardStage.entries.size

    /**
     * 还有几条冲突没有表态。
     *
     * 只数**列出来的**那些：包里的冲突可能比 `MAX_CONFLICT_LIST` 多，没列出来的项由
     * 全局策略兜底（见 [resolutions]）。
     */
    fun undecided(
        conflicts: List<DshConfigBackup.ConflictItem>,
        choices: Map<String, String>,
    ): Int = conflicts.count { it.id.isNotEmpty() && it.id !in choices }

    /**
     * 决策步该不该出现：包里有会话、或有冲突，才需要问用户。
     *
     * 两样都没有时直接从预览跳到确认 —— 这正是旧流程的取舍（没有冲突就不弹策略框），
     * 区别只是现在会说清楚「没什么要问你的，看一眼就开跑」。
     */
    fun decideNeeded(sessions: Int, conflicts: List<DshConfigBackup.ConflictItem>): Boolean =
        sessions > 0 || conflicts.isNotEmpty()

    /**
     * 「下一步」现在能不能点。
     *
     * **预览步要看的是「决策做完了没有」吗？不是。** 预览的下一步正是**进入**决策步，
     * 那一刻冲突一条都还没表态 —— 拿决策完成度去卡它，按钮就永远点不动：用户进不了
     * 决策页，也就永远做不完决策（beta.64 上真机就是这个死结）。
     * 所以只有决策步才检查完成度。
     */
    fun canAdvance(
        step: WizardStep,
        sessions: Int,
        sessionChoice: DshConfigBackup.SessionImport?,
        conflicts: List<DshConfigBackup.ConflictItem>,
        choices: Map<String, String>,
    ): Boolean = when (step) {
        WizardStep.PREVIEW -> true
        WizardStep.DECIDE -> decisionsComplete(sessions, sessionChoice, conflicts, choices)
        else -> false
    }

    /**
     * 「决策」这一步能不能继续。
     *
     * 两条都要满足：
     * - 包里有会话（`sessions > 0`）时必须明确选一种处理方式。**不给默认值**：默认「写入」
     *   等于替用户决定动他的聊天记录，默认「跳过」又会让人以为备份里的会话丢了 —— 这两件
     *   事都该由用户自己点一下。
     * - 列出来的每条冲突都要表态（两个批量按钮让这件事只要两下）。
     */
    fun decisionsComplete(
        sessions: Int,
        sessionChoice: DshConfigBackup.SessionImport?,
        conflicts: List<DshConfigBackup.ConflictItem>,
        choices: Map<String, String>,
    ): Boolean = (sessions <= 0 || sessionChoice != null) && undecided(conflicts, choices) == 0

    /**
     * 逐条决策 → 插件的 `decisions.resolutions`。
     *
     * 只发**列出来的**那些 id：我们没让用户看过的项不该被他没做过的决定影响，那些留给
     * 全局策略。非法取值（不是 [ConflictChoice] 的两个之一）直接不发 —— 插件对不认识的
     * 取值会当成 `review`，那会让这条项在它的界面上变成「待人工」，而不是照我们的意思办。
     */
    fun resolutions(
        conflicts: List<DshConfigBackup.ConflictItem>,
        choices: Map<String, String>,
    ): Map<String, String> {
        val allowed = ConflictChoice.entries.map { it.wire }.toSet()
        val out = LinkedHashMap<String, String>()
        for (c in conflicts) {
            if (c.id.isEmpty()) continue
            val v = choices[c.id] ?: continue
            if (v in allowed) out[c.id] = v
        }
        return out
    }

    /** 决策的汇总口径（确认步要复述一遍「接下来会发生什么」）。 */
    data class Tally(val keepCurrent: Int, val useImported: Int, val byStrategy: Int)

    fun tally(
        conflicts: List<DshConfigBackup.ConflictItem>,
        choices: Map<String, String>,
        conflictTotal: Int,
    ): Tally {
        var keep = 0
        var use = 0
        for (c in conflicts) {
            when (choices[c.id]) {
                ConflictChoice.KEEP_CURRENT.wire -> keep++
                ConflictChoice.USE_IMPORTED.wire -> use++
            }
        }
        // 没列出来的那些由全局策略处理
        val listed = conflicts.size
        return Tally(keep, use, (conflictTotal - listed).coerceAtLeast(0))
    }
}
