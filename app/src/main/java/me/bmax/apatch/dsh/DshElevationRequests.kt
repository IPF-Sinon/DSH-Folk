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
 * 原生能力申请的**两段式**状态机，桥在这里阻塞等用户。
 *
 * ## 为什么要阻塞
 *
 * 早先的做法是「403 → agent 另外发一条 elevate → 用户批准 → agent 再调一次」。这中间有一个
 * 谁都补不上的缝：批准只说明「权限级别提上去了」，第二次调用仍可能因为别的条件失败；而 agent
 * 拿到的只是两次互不相关的响应，用户则要面对两轮问答。
 *
 * 现在反过来：**能力调用本身就是申请**。桥在闸门处发现档位不够就停下来问用户，用户答「允许 /
 * 仅本次」就地把这次调用执行掉、返回真实结果；答「拒绝 / 超时」就把这次调用作为失败返回。agent
 * 只会看到「这条命令成功了」或者「这条命令因为用户拒绝而没跑」，不存在「申请成功但调用失败」。
 *
 * ## 第二段：Android 层的权限
 *
 * 用户在 App 里点了「允许」，但 Android 自己的权限可能还没给（相机、麦克风、通知、修改系统设置
 * …）。这一段单独存在，因为它的解决办法完全不同：要跳系统设置页、要等用户回到 App 再复查。
 * 用户点了「我知道了」或者这一段超时，就把「Android 层没授权」作为结果返回。
 */
object DshElevationRequests {
    data class Request(
        val id: Long,
        val cap: DshNativeBridge.Cap,
        val access: DshNativeBridge.Access,
        val reason: String,
        /**
         * 这次申请对应的命令（可多行），原文照显给用户。
         *
         * 与调用一起来的申请由桥用审计同款的方式重建出来（就是它接下来真会执行的那条），
         * 显式的 `dsh-native elevate` 则用它自己带来的 `--command`。
         */
        val command: String? = null,
        /** 发起这次申请的那条命令；command 缺失时用它兜底，让弹窗永远有东西可看。 */
        val invocation: String? = null,
        val filedAtMs: Long,
        val expiresAtMs: Long,
    )

    /** 用户（或超时）对一次申请给出的结论。 */
    enum class Decision(val id: String) {
        /** 用户点了「允许」：级别落盘，长期有效。 */
        ALLOWED("allowed"),

        /** 用户点了「仅本次」：只放行一次调用，用完即收回。 */
        ONCE("once"),

        /** 用户点了「拒绝」，或直接关掉了弹窗。 */
        DENIED("denied"),

        /** 超时未答复，按拒绝处理。 */
        EXPIRED("expired"),
    }

    /** 最近一次结论。容器侧靠它回答「我上一次申请怎么样了」。 */
    data class Outcome(
        val cap: DshNativeBridge.Cap,
        val access: DshNativeBridge.Access,
        val decision: Decision,
        val atMs: Long,
    )

    /** 第二段：App 层已同意，但 Android 层还缺权限。 */
    data class OsRequest(
        val id: Long,
        val cap: DshNativeBridge.Cap,
        val access: DshNativeBridge.Access,
        /** 缺什么，人类可读（弹窗正文直接用）。 */
        val missing: List<String>,
        /** 需要跳系统设置页时给的动作；不需要则为 null。 */
        val settingsAction: String?,
        val expiresAtMs: Long,
    )

    /** 第二段的结论。 */
    enum class OsOutcome {
        /** 复查通过，可以执行了。 */
        GRANTED,

        /** 用户点了「我知道了」：Android 层确实没给。 */
        ACKNOWLEDGED,

        /** 用户一直没处理（多半是去了系统页忘了回来）。 */
        EXPIRED,
    }

    /** 第一段的时限：弹窗上的倒计时，也是超时判定的时限。 */
    const val TTL_MS = 60_000L

    /**
     * 第二段的时限。
     *
     * 比第一段宽松得多：这一段的用户动作是「跳到系统设置页 → 找到那一项 → 打开 → 切回应用」，
     * 一分钟经常不够。
     */
    const val OS_TTL_MS = 300_000L

    private val ids = AtomicLong(0)

    private val mutable = MutableStateFlow<Request?>(null)

    /** 待处理的第一段申请；同一时刻最多一个。 */
    val pending = mutable.asStateFlow()

    private val osMutable = MutableStateFlow<OsRequest?>(null)

    /** 待处理的第二段（Android 权限缺失）；同一时刻最多一个。 */
    val pendingOs = osMutable.asStateFlow()

    private val lastMutable = MutableStateFlow<Outcome?>(null)

    /** 最近一次结论；本次进程还没有人申请过时为空。 */
    val last = lastMutable.asStateFlow()

    private val decisionWaiters = ConcurrentHashMap<Long, CompletableDeferred<Decision>>()
    private val osWaiters = ConcurrentHashMap<Long, CompletableDeferred<OsOutcome>>()

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var expiry: Job? = null
    private var osExpiry: Job? = null

    /** 提交一次申请；已有待处理申请时返回 null（容器侧对应 409）。 */
    @Synchronized
    fun submit(
        cap: DshNativeBridge.Cap,
        access: DshNativeBridge.Access,
        reason: String,
        command: String? = null,
        invocation: String? = null,
    ): Request? {
        if (mutable.value != null) return null
        val now = System.currentTimeMillis()
        val request = Request(
            ids.incrementAndGet(),
            cap,
            access,
            reason,
            command?.takeIf { it.isNotBlank() },
            invocation?.takeIf { it.isNotBlank() },
            now,
            now + TTL_MS,
        )
        mutable.value = request
        decisionWaiters[request.id] = CompletableDeferred()
        expiry?.cancel()
        expiry = scope.launch {
            // 多睡一小会儿：让 UI 的倒计时先走到 0，用户看到的是「时间到了才自动拒绝」，
            // 而不是「还剩 1 秒就被判了」
            delay(TTL_MS + 400)
            expireIfStill(request)
        }
        return request
    }

    /**
     * 阻塞等到第一段的结论。HTTP 连接线程上调用。
     *
     * 超时按 [Decision.EXPIRED] 处理：即使 TTL 协程因为进程被冻结而没能跑，这里也不会
     * 永远挂着 —— 一个卡死的连接会让 agent 的那次工具调用一直不返回。
     */
    fun awaitDecision(id: Long): Decision =
        runBlocking {
            withTimeoutOrNull(TTL_MS + 5_000L) { decisionWaiters[id]?.await() }
        } ?: Decision.EXPIRED

    @Synchronized
    private fun expireIfStill(request: Request) {
        if (mutable.value?.id != request.id) return
        mutable.value = null
        lastMutable.value =
            Outcome(request.cap, request.access, Decision.EXPIRED, System.currentTimeMillis())
        decisionWaiters.remove(request.id)?.complete(Decision.EXPIRED)
    }

    /** 用户按了某个按钮。 */
    @Synchronized
    fun resolve(id: Long, decision: Decision) {
        val current = mutable.value ?: return
        if (current.id != id) return
        mutable.value = null
        expiry?.cancel()
        lastMutable.value =
            Outcome(current.cap, current.access, decision, System.currentTimeMillis())
        decisionWaiters.remove(id)?.complete(decision)
    }

    /** 关掉弹窗等同于拒绝。 */
    fun clear(id: Long) = resolve(id, Decision.DENIED)

    /** 这次申请还剩多少毫秒；弹窗倒计时与 `/native/capabilities` 都用它。 */
    fun remainingMs(request: Request, nowMs: Long = System.currentTimeMillis()): Long =
        (request.expiresAtMs - nowMs).coerceAtLeast(0L)

    // ────────────────────────── 第二段：Android 权限 ──────────────────────────

    /** 登记第二段；已有待处理时返回 null（调用方直接返回缺少权限的结果即可）。 */
    @Synchronized
    fun askOs(
        cap: DshNativeBridge.Cap,
        access: DshNativeBridge.Access,
        missing: List<String>,
        settingsAction: String?,
    ): OsRequest? {
        if (osMutable.value != null) return null
        val now = System.currentTimeMillis()
        val request = OsRequest(
            ids.incrementAndGet(),
            cap,
            access,
            missing,
            settingsAction,
            now + OS_TTL_MS,
        )
        osMutable.value = request
        osWaiters[request.id] = CompletableDeferred()
        osExpiry?.cancel()
        osExpiry = scope.launch {
            delay(OS_TTL_MS + 400)
            expireOsIfStill(request)
        }
        return request
    }

    /** 阻塞等到第二段的结论。 */
    fun awaitOs(id: Long): OsOutcome =
        runBlocking {
            withTimeoutOrNull(OS_TTL_MS + 5_000L) { osWaiters[id]?.await() }
        } ?: OsOutcome.EXPIRED

    @Synchronized
    private fun expireOsIfStill(request: OsRequest) {
        if (osMutable.value?.id != request.id) return
        osMutable.value = null
        osWaiters.remove(request.id)?.complete(OsOutcome.EXPIRED)
    }

    /** UI 侧：复查通过（granted=true）或用户点了「我知道了」（granted=false）。 */
    @Synchronized
    fun resolveOs(id: Long, granted: Boolean) {
        val current = osMutable.value ?: return
        if (current.id != id) return
        osMutable.value = null
        osExpiry?.cancel()
        osWaiters.remove(id)?.complete(if (granted) OsOutcome.GRANTED else OsOutcome.ACKNOWLEDGED)
    }

    /** 这段还剩多少毫秒，供弹窗显示。 */
    fun remainingOsMs(request: OsRequest, nowMs: Long = System.currentTimeMillis()): Long =
        (request.expiresAtMs - nowMs).coerceAtLeast(0L)
}
