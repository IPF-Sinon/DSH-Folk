package me.bmax.apatch.dsh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 容器只能提交提权请求；真正修改权限必须由前台 UI 经用户确认。
 *
 * ## 为什么请求有寿命
 *
 * 请求在 [TTL_MS] 内没被答复就当作拒绝（[Decision.EXPIRED]）。没有这条时限的话，一个
 * 用户没注意到的弹窗会永久占住「同时只允许一个待处理请求」那个名额：后面的申请一律
 * 409，而 agent 只能一直等一个永远不会来的答复。有了时限，最坏情况退化成「这次没成」，
 * agent 还能据 [Decision.EXPIRED] 判断该不该重问。
 *
 * 时限也决定了这个弹窗必须真的能被看见（它可能出现在 WebUI 页之上），否则时限惩罚的是
 * 一个根本没看到问题的人。
 */
object DshElevationRequests {
    data class Request(
        val id: Long,
        val cap: DshNativeBridge.Cap,
        val access: DshNativeBridge.Access,
        val reason: String,
        /**
         * agent 打算在获准后执行的那条命令（可多行），原文照显给用户。
         *
         * 这是这个弹窗最有说服力的一栏：用户要判断的不是「camera=write 要不要给」，而是
         * 「它接下来到底要做什么」。为空表示 agent 没附（旧版 CLI 就是这样）。
         */
        val command: String? = null,
        /** 发起这次申请的那条命令；command 缺失时用它兜底，让弹窗永远有东西可看。 */
        val invocation: String? = null,
        val filedAtMs: Long,
        val expiresAtMs: Long,
    )

    /** 一次申请的结论。 */
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

    /** 弹窗上的倒计时长度，也是超时判定的时限。 */
    const val TTL_MS = 60_000L

    private val ids = AtomicLong(0)
    private val mutable = MutableStateFlow<Request?>(null)

    /** 待处理的申请；同一时刻最多一个。 */
    val pending = mutable.asStateFlow()

    private val lastMutable = MutableStateFlow<Outcome?>(null)

    /** 最近一次结论；本次进程还没有人申请过时为空。 */
    val last = lastMutable.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var expiry: Job? = null

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
        expiry?.cancel()
        expiry = scope.launch {
            // 多睡一小会儿：让 UI 的倒计时先走到 0，用户看到的是「时间到了才自动拒绝」，
            // 而不是「还剩 1 秒就被判了」
            delay(TTL_MS + 400)
            expireIfStill(request)
        }
        return request
    }

    @Synchronized
    private fun expireIfStill(request: Request) {
        if (mutable.value?.id != request.id) return
        mutable.value = null
        lastMutable.value =
            Outcome(request.cap, request.access, Decision.EXPIRED, System.currentTimeMillis())
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
    }

    /** 关掉弹窗等同于拒绝。 */
    fun clear(id: Long) = resolve(id, Decision.DENIED)

    /** 这次申请还剩多少毫秒；弹窗倒计时与 `/native/capabilities` 都用它。 */
    fun remainingMs(request: Request, nowMs: Long = System.currentTimeMillis()): Long =
        (request.expiresAtMs - nowMs).coerceAtLeast(0L)
}
