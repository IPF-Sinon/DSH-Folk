package me.bmax.apatch.dsh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「批准即范围授权」：一次工作流运行期间，声明过的那几项能力**不再逐条弹窗**。
 *
 * ## 为什么需要它
 *
 * 工作流的用途就是长流程（几十步、其中若干步要通知/剪贴板/特权 shell）。如果每一步都弹一次
 * 确认框，用户要对着同一个他刚才已经批准过的计划点几十次 —— 那不是安全，那是逼人闭眼点
 * 「允许」，反而把确认变成了形式。
 *
 * ## 边界（这几条是这套东西敢存在的前提）
 *
 * 1. **只在运行期间有效**：运行结束/被取消/失败，立刻 [revoke]，授权随之消失。
 * 2. **只覆盖声明过的能力**：用户批准时，弹窗逐项列出了这些能力（来自定义的
 *    `declaredCaps`）。没声明的调用照旧走原来的逐条确认。
 * 3. **危险命令默认仍然逐条问**：只有用户在批准页**单独勾选**了「含危险命令」，
 *    [allowsDangerous] 才为真。`rm -rf /data` 这种命令不该被一次泛泛的批准带走。
 * 4. **一切照旧记审计**：桥那边会把 `privDecision` 记成 `workflow:<runId>` —— 事后翻审计
 *    能回答「这条 root 命令是用户为哪个工作流、在哪一次运行里放行的」。
 *
 * 同一时刻允许多个运行各自持有授权（运行之间互不影响）：命中判据因此是「**任意**一个活动
 * 授权覆盖了这次调用」。这是有意的宽松 —— 两个并行工作流本就都是用户批准的。
 */
internal object WorkflowGrants {

    data class Grant(
        val runId: String,
        val workflowId: String,
        val workflowName: String,
        val caps: Set<String>,
        val dangerous: Boolean,
        val grantedAtMs: Long,
    )

    private val mutable = MutableStateFlow<Map<String, Grant>>(emptyMap())

    /** 活动授权（runId → Grant）。界面据此显示「本次运行已授权 N 项」。 */
    val active = mutable.asStateFlow()

    @Synchronized
    fun grant(runId: String, workflowId: String, workflowName: String, caps: Collection<String>, dangerous: Boolean) {
        mutable.value = mutable.value + (runId to Grant(
            runId = runId,
            workflowId = workflowId,
            workflowName = workflowName,
            caps = caps.toSet(),
            dangerous = dangerous,
            grantedAtMs = System.currentTimeMillis(),
        ))
    }

    @Synchronized
    fun revoke(runId: String) {
        if (!mutable.value.containsKey(runId)) return
        mutable.value = mutable.value - runId
    }

    /** 有没有任何活动授权覆盖这项能力；返回命中的那个（用于审计与提示）。 */
    fun allows(capId: String): Grant? =
        mutable.value.values.firstOrNull { capId in it.caps }

    /** 危险命令是否也在授权范围内（用户在批准页单独勾过）。 */
    fun allowsDangerous(capId: String): Boolean =
        mutable.value.values.any { capId in it.caps && it.dangerous }

    fun activeCaps(): Set<String> = mutable.value.values.flatMap { it.caps }.toSet()
}
