package me.bmax.apatch.dsh

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * 回环桥的统一守卫：**回环校验 → token 校验 → 限流记账 → 审计**。
 *
 * 为什么要有这一层：`/fs`、`/native`、`/cloud`、`/saf`、`/workflow` 五族端点共用同一段守卫，
 * 但**审计与限流此前完全缺失**。容器里的 agent 完全可能写出「死循环刷 10 万次 /list」这类请求 ——
 * 宿主进程会被拖住，而当前只有 30s 的 socket 超时兜底，事后一条日志都没有。
 *
 * ## 为什么默认只记账不拦截
 *
 * 容器里的 agent 做批量文件操作（同步一个目录树）时，每秒几十次请求是正常行为，贸然上 429 会把
 * 正常用法打断 —— 这类「安全相关行为改变」必须先在真实设备上观测一段时间再决定。所以默认：
 *
 * - 每次请求都记账（端点计数、耗时、是否超限）；
 * - 超限只记一条 warn 并累加计数，**仍返回正常响应**；
 * - 要真拦截就把 [ENFORCE] 打开。
 *
 * ## 阈值
 *
 * 600 次/分钟 ≈ 每秒 10 次。批量同步 1 万个小文件会到十倍于它 —— 也就是说正常批量操作**会**
 * 触发记录但不被拦截，这正是「先观测」的意思。
 */
internal object DshBridgeGuard {
    private const val TAG = "DshBridgeGuard"

    /** 滑动窗口长度与窗口内允许的请求数（默认 600/分钟）。 */
    const val WINDOW_MS = 60_000L
    const val MAX_PER_WINDOW = 600

    /**
     * 是否真的拦截（默认 false = 只记账）。
     *
     * 做成常量而不是设置项：在真机上观测过一个版本的流量之前，一个「会拒绝请求的开关」比缺一个
     * 开关更危险。要挂设置项时读 DshSource 那套 prefs。
     */
    const val ENFORCE = false

    /** 审计文件上限，与原生能力审计同规格（2MB 后轮转）。 */
    private const val AUDIT_MAX_BYTES = 2L * 1024 * 1024

    /**
     * 分端点阈值（每分钟）。**未列出的端点一律用 [MAX_PER_WINDOW]**。
     *
     * 为什么要有这张表：全局 600/分钟对 `fs` 那族端点的批量文件操作是合理的（同步一万个小文件正好这个
     * 量级），但对「一次点击就该只发生一次」的端点太松 —— 剪贴板读、通知、震动、toast 都是用户
     * 可感知的骚扰面，agent 死循环时 600/分钟已经足够把状态栏刷满。
     *
     * 取值口径（都不动默认拦截行为，只让「超限」这件事记得更准）：
     * - `/native/clipboard` 10/分钟：剪贴板是人工动作，正常用法一分钟几次；
     * - `/native/notify` 30/分钟：一个 agent 一分钟发 30 条通知已经该被记下来了；
     * - `/native/vibrate`、`/native/toast` 30/分钟：同上，都是即时反馈类；
     * - `/native/a11y` 维持 600：AI 驱动界面确实高频（一次 tree+click 就是两三条），不宜收紧，
     *   它的风险在别处（已由能力档位与限制模式管），不在频率。
     */
    private val thresholds = mapOf(
        "/native/clipboard" to 10,
        "/native/notify" to 30,
        "/native/vibrate" to 30,
        "/native/toast" to 30,
        "/native/a11y" to MAX_PER_WINDOW,
    )

    /** 某个端点实际生效的阈值（未列出则回退全局）。 */
    fun thresholdFor(endpoint: String): Int = thresholds[endpoint] ?: MAX_PER_WINDOW

    private val limitedTotal = AtomicLong(0)
    private val requestTotal = AtomicLong(0)
    private val byEndpoint = ConcurrentHashMap<String, AtomicLong>()

    /** 每个端点各自一个窗口：全局一个计数器的话，`fs` 那族端点的批量操作会把剪贴板的额度吃掉。 */
    private class Window {
        val start = AtomicLong(System.currentTimeMillis())
        val count = AtomicLong(0)

        fun roll() {
            val now = System.currentTimeMillis()
            val s = start.get()
            if (now - s >= WINDOW_MS) {
                if (start.compareAndSet(s, now)) count.set(0)
            }
        }
    }

    private val windows = ConcurrentHashMap<String, Window>()

    fun totalRequests(): Long = requestTotal.get()

    fun limitedRequests(): Long = limitedTotal.get()

    /** 某个端点当前窗口内已记账的请求数。 */
    fun windowRequests(endpoint: String = ""): Long {
        if (endpoint.isEmpty()) return windows.values.sumOf { it.count.get() }
        val w = windows[endpoint] ?: return 0
        w.roll()
        return w.count.get()
    }

    fun endpointCounts(): Map<String, Long> =
        byEndpoint.entries.associate { it.key to it.value.get() }

    /**
     * 记一笔请求并返回是否超限。
     *
     * 阈值**按端点取**（见 [thresholds]）；窗口也按端点各自滚动。
     */
    fun record(endpoint: String): Boolean {
        requestTotal.incrementAndGet()
        byEndpoint.computeIfAbsent(endpoint) { AtomicLong(0) }.incrementAndGet()
        val w = windows.computeIfAbsent(endpoint) { Window() }
        w.roll()
        val n = w.count.incrementAndGet()
        val limit = thresholdFor(endpoint)
        if (n > limit) {
            limitedTotal.incrementAndGet()
            // 不让日志自己变成放大器：每 100 次超限才记一条
            if (limitedTotal.get() % 100L == 1L) {
                Log.w(TAG, "回环桥请求超限：$endpoint 窗口内 $n 次（上限 $limit/分钟），累计 ${limitedTotal.get()} 次")
            }
            return true
        }
        return false
    }

    /**
     * 审计一行。只记**写操作、被拒与超限**，不记每次成功的读 —— 否则刷目录会把审计冲掉。
     *
     * 落在 `<files>/audit/bridge.jsonl`，与原生能力审计（`native-capability.jsonl`）同一目录、
     * 同一套轮转规则，bugreport 里一起收走。
     */
    fun audit(ctx: Context?, endpoint: String, status: Int, costMs: Long, limited: Boolean) {
        if (ctx == null) return
        val interesting = status >= 400 || limited ||
            endpoint.contains("/write") || endpoint.contains("/delete") ||
            endpoint.contains("/move") || endpoint.contains("/copy")
        if (!interesting) return
        runCatching {
            val dir = File(ctx.filesDir, "audit").apply { mkdirs() }
            val file = File(dir, "bridge.jsonl")
            if (file.exists() && file.length() > AUDIT_MAX_BYTES) {
                File(dir, "bridge.previous.jsonl").also { if (it.exists()) it.delete() }
                file.renameTo(File(dir, "bridge.previous.jsonl"))
            }
            val entry = JSONObject()
                .put("time", Instant.now().toString())
                .put("endpoint", endpoint)
                .put("status", status)
                .put("costMs", costMs)
                .put("limited", limited)
                // 实际生效的阈值：事后才能判断「到底超了多少」，而不是只知道超了
                .put("threshold", thresholdFor(endpoint))
            file.appendText(entry.toString() + "\n")
        }.onFailure { Log.w(TAG, "写桥审计失败: ${it.message}") }
    }
}
