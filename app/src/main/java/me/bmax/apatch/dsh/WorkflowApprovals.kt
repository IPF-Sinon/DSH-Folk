package me.bmax.apatch.dsh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 创建 / 运行工作流的**审批状态机**：桥在这里阻塞，等用户在弹窗上做决定。
 *
 * ## 与 [DshElevationRequests] 的关系
 *
 * 形态是照着它写的（前台才弹、倒计时、超时即拒绝、`runBlocking` 等结论、结论是一种
 * 返回值而不是异常），但**必须是独立的一份**：提权申请是「这一次调用要不要放行」，工作流
 * 审批是「这一整套流程要不要建/要不要跑」。共用一份状态机的话，一次工作流审批会把正在排队
 * 的一次提权申请顶掉（两者都只允许一个 pending），而用户看到的是一个莫名其妙的
 * `request_pending`。
 *
 * ## 两个决定、两种后果
 *
 * - [Kind.CREATE]（黄）：只是**落盘一份定义**，不执行任何东西。批准表示「这个流程我认可」。
 * - [Kind.RUN]（红）：**立刻开跑**，且其中的能力调用按声明范围免逐条确认。所以这两个
 *   决定的文案、颜色、以及有没有「编辑」按钮都不同。
 *
 * ## 编辑期间为什么暂停倒计时
 *
 * 用户在编辑页改节点可能花上几分钟。倒计时照走的话，他正在改的东西会在半路被判超时 ——
 * 那不是安全设计，那是坑人。所以 [startEditing] 撤掉到期任务，[stopEditing] 回来后
 * **重新给一个完整的 TTL**（弹窗上的倒计时也从头开始）。
 */
internal object WorkflowApprovals {

    /** 这次问的是「建」还是「跑」。 */
    enum class Kind(val id: String) {
        CREATE("create"),
        RUN("run"),
    }

    /** 用户（或超时）给出的结论。 */
    enum class Decision(val id: String) {
        APPROVED("approved"),
        REJECTED("rejected"),
        EXPIRED("expired"),
    }

    /**
     * 一次待审批的工作流。
     *
     * [originalJson] 是 AI 提交的原文（批准后要拿它和用户的改动做 diff，所以必须原样留存）；
     * [editedJson] 是用户在编辑页改出来的那一版（没改就是 null）。
     */
    data class Request(
        val id: Long,
        val kind: Kind,
        val workflowId: String,
        val workflowName: String,
        val originalJson: String,
        /** 给弹窗看的摘要：节点数、会用到哪些能力、有没有危险命令。 */
        val summary: String,
        val declaredCaps: List<String>,
        val dangerous: Boolean,
        /** 发起理由（AI 给的 `reason`，必填）。 */
        val reason: String,
        val nodeCount: Int,
        val edgeCount: Int,
        val filedAtMs: Long,
        var expiresAtMs: Long,
        val editedJson: String? = null,
        /** 编辑器正在打开时，弹窗本体不渲染。 */
        val editing: Boolean = false,
    ) {
        val hasEdits: Boolean get() = editedJson != null
    }

    /** `awaitDecision` 的结论 + 用户改动 + 拒绝理由 + 危险命令是否被单独放行。 */
    data class Verdict(
        val decision: Decision,
        val editedJson: String?,
        val rejectReason: String,
        /** 用户在批准页是否**单独勾选**了「含危险命令，全部放行」。默认 false。 */
        val allowDangerous: Boolean = false,
    )

    /** 审批时限：弹窗上的倒计时，也是超时判定的时限。 */
    const val TTL_MS = 60_000L

    private val ids = AtomicLong(0)

    private val mutable = MutableStateFlow<Request?>(null)

    /** 待审批的那一份；同一时刻最多一个（多了用户也看不过来）。 */
    val pending = mutable.asStateFlow()

    private val waiters = ConcurrentHashMap<Long, CompletableDeferred<Verdict>>()
    private val verdicts = ConcurrentHashMap<Long, Verdict>()
    private val rejectReasons = ConcurrentHashMap<Long, String>()

    /** 批准时是否单独勾了「含危险命令，全部放行」。 */
    private val allowDangerousIds = ConcurrentHashMap<Long, Boolean>()

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var expiry: Job? = null

    /** 提交一次审批；已有待审批时返回 null（容器侧对应 409）。 */
    @Synchronized
    fun submit(
        kind: Kind,
        workflowId: String,
        workflowName: String,
        originalJson: String,
        summary: String,
        declaredCaps: List<String>,
        dangerous: Boolean,
        reason: String,
        nodeCount: Int,
        edgeCount: Int,
    ): Request? {
        if (mutable.value != null) return null
        val now = System.currentTimeMillis()
        val request = Request(
            id = ids.incrementAndGet(),
            kind = kind,
            workflowId = workflowId,
            workflowName = workflowName,
            originalJson = originalJson,
            summary = summary,
            declaredCaps = declaredCaps,
            dangerous = dangerous,
            reason = reason,
            nodeCount = nodeCount,
            edgeCount = edgeCount,
            filedAtMs = now,
            expiresAtMs = now + TTL_MS,
        )
        mutable.value = request
        waiters[request.id] = CompletableDeferred()
        armExpiry(request)
        return request
    }

    private fun armExpiry(request: Request) {
        expiry?.cancel()
        expiry = scope.launch {
            // 多睡一小会儿：让 UI 的倒计时先走到 0，用户看到的是「时间到了才自动拒绝」。
            delay(TTL_MS + 400)
            expireIfStill(request.id)
        }
    }

    /**
     * 阻塞等结论。桥的连接线程上调用。
     *
     * 超时按 [Decision.EXPIRED] 处理：即使到期协程因为进程被冻结而没跑，这里也不会永远
     * 挂着 —— 一个卡死的连接会让 AI 那次工具调用一直不返回。
     */
    fun awaitDecision(id: Long): Verdict = runBlocking {
        withTimeoutOrNull(TTL_MS + 5_000L) { waiters[id]?.await() }
    } ?: Verdict(Decision.EXPIRED, null, "")

    @Synchronized
    private fun expireIfStill(id: Long) {
        val current = mutable.value ?: return
        if (current.id != id) return
        if (current.editing) return // 编辑器开着：不判超时（stopEditing 会重新计时）
        mutable.value = null
        finish(id, Verdict(Decision.EXPIRED, current.editedJson, ""))
    }

    /** 用户按了「批准」。[allowDangerous] = 是否同时勾选了「含危险命令，全部放行」。 */
    fun approve(id: Long, allowDangerous: Boolean = false) {
        allowDangerousIds[id] = allowDangerous
        resolve(id, Decision.APPROVED)
    }

    /** 用户按了「拒绝」并可写理由。 */
    fun reject(id: Long, reason: String) {
        rejectReasons[id] = reason.trim()
        resolve(id, Decision.REJECTED)
    }

    /** 关掉弹窗等同于拒绝。 */
    fun dismiss(id: Long) = reject(id, "")

    @Synchronized
    private fun resolve(id: Long, decision: Decision) {
        val current = mutable.value ?: return
        if (current.id != id) return
        mutable.value = null
        expiry?.cancel()
        finish(
            id,
            Verdict(
                decision = decision,
                editedJson = current.editedJson,
                rejectReason = rejectReasons.remove(id).orEmpty(),
                allowDangerous = allowDangerousIds.remove(id) == true,
            ),
        )
    }

    @Synchronized
    private fun finish(id: Long, verdict: Verdict) {
        verdicts[id] = verdict
        waiters.remove(id)?.complete(verdict)
    }

    /**
     * 结论已经交出去之后，提交方（桥）还会再读一次。
     *
     * 为什么不让 [awaitDecision] 的调用方直接拿 `verdicts`：`awaitDecision` 与
     * 「读用户编辑后那一版」之间隔着一次网络回包，期间用户完全可能又点了一次（实际不会，
     * 但读一份快照比读一个会被清的 map 更稳）。[takeVerdict] 读走即删。
     */
    @Synchronized
    fun takeVerdict(id: Long): Verdict? = verdicts.remove(id)

    // ────────────────────────── 编辑页 ──────────────────────────

    /** 打开编辑器：弹窗隐藏、暂停倒计时。 */
    @Synchronized
    fun startEditing(id: Long) {
        val current = mutable.value ?: return
        if (current.id != id) return
        expiry?.cancel()
        mutable.value = current.copy(editing = true)
    }

    /**
     * 退出编辑器。[editedJson] 非空 = 用户保存了改动。
     *
     * 无论保存还是取消，都重新给一个完整 TTL：用户回到弹窗时应该还有时间做决定。
     */
    @Synchronized
    fun stopEditing(id: Long, editedJson: String?) {
        val current = mutable.value ?: return
        if (current.id != id) return
        val updated = current.copy(
            editing = false,
            editedJson = editedJson ?: current.editedJson,
            expiresAtMs = System.currentTimeMillis() + TTL_MS,
        )
        mutable.value = updated
        armExpiry(updated)
    }

    /** 这次审批还剩多少毫秒；弹窗倒计时用。 */
    fun remainingMs(request: Request, nowMs: Long = System.currentTimeMillis()): Long =
        (request.expiresAtMs - nowMs).coerceAtLeast(0L)
}
