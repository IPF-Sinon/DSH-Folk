package me.bmax.apatch.dsh

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 工作流执行引擎：宿主 App 侧的顺序执行器。
 *
 * ## 为什么执行在 App 侧而不是容器里
 *
 * 工作流要做的事天然跨两侧：拉起并等待 DSH（宿主的进程管理）、走回环桥调原生能力（宿主的
 * Binder 与权限）、在容器里跑命令（容器）。只有 App 侧同时够得着这三样；把引擎放进容器，
 * 「DSH 没起来时先拉起它」这一条就无解了 —— 那等于让进程去启动它自己的父进程。
 *
 * ## 顺序执行
 *
 * 一次运行里同一时刻只有一个节点在跑（校验器保证定义无环，所以「多个节点同时跑」只会让
 * 状态查询与日志变复杂）。多个**工作流**之间可以并行，各自持有自己的运行变量与能力授权。
 *
 * ## 状态可查，而不是只在内存里
 *
 * 每推进一步都落盘，日志边走边追加 —— `workflow_status` 因此能回答「现在在第几步、前面各步
 * 的结果是什么」。进程被杀之后这次运行会被 [WorkflowStore.reconcileInterrupted] 标成
 * [WorkflowRunState.INTERRUPTED]，不会永远停在 running 上骗人。
 *
 * ## 退出码为什么要自己起进程读
 *
 * 容器侧已有的 [DshRuntime.execRootfsStreaming] 只回输出、不回退出码，而工作流必须区分
 * 「命令跑完了」和「命令失败了」。所以这里用 [DshRuntime.execRootfs] 自己起进程、自己读流、
 * 自己取 `exitValue()`；代价是多二十行，换来的是 `exit_1` 这种可判断的结果。
 */
internal object WorkflowEngine {

    data class StartResult(val ok: Boolean, val runId: String?, val reason: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    private val mutableActive = MutableStateFlow<Set<String>>(emptySet())

    /** 正在跑的 runId（界面与状态查询用）。 */
    val active: StateFlow<Set<String>> = mutableActive.asStateFlow()

    /** 每次状态推进 +1：界面拿它当刷新信号，不必轮询磁盘。 */
    private val mutableTick = MutableStateFlow(0)
    val tick: StateFlow<Int> = mutableTick.asStateFlow()

    /** 每个运行当前正在执行的进程（用户取消时要 `destroyForcibly` 掉它）。 */
    private val currentProcess = ConcurrentHashMap<String, Process>()

    fun isActive(runId: String): Boolean = jobs[runId]?.isActive == true

    /**
     * 开始一次运行。审批与能力授权由 [WorkflowBridge] 完成 —— 这里只负责跑。
     *
     * [diff] 是用户在批准前对定义做过的改动，随运行结果一起回给 AI。
     */
    fun start(
        ctx: Context,
        def: WorkflowDefinition,
        diff: List<WorkflowPatch> = emptyList(),
        allowDangerous: Boolean = false,
    ): StartResult {
        DshRuntime.attach(ctx.applicationContext)
        val runId = WorkflowStore.newRunId()
        // 授权必须在**开跑之前**落好：命令一旦开始跑，它内部对回环桥的调用就要命中这份授权，
        // 否则长流程的每一步还会逐条弹窗 —— 而免掉那些重复弹窗正是这次批准的意义。
        WorkflowGrants.grant(
            runId = runId,
            workflowId = def.id,
            workflowName = def.name,
            caps = def.declaredCaps,
            dangerous = allowDangerous,
        )
        val run = WorkflowRun(
            runId = runId,
            workflowId = def.id,
            workflowName = def.name,
            state = WorkflowRunState.QUEUED,
            definition = def,
            startedAtMs = System.currentTimeMillis(),
            diff = diff,
            variables = seedVariables(def, runId),
        )
        WorkflowStore.saveRun(ctx, run)
        val job = scope.launch {
            // 前台服务把进程钉住：工作流的语义是「跑完为止」，不能因为用户切走就被回收。
            // 用 HOLD 而不是 START —— 只占住进程，不顺带把容器拉起来（纯 shell 的工作流
            // 不该因为自己存在而启动一整套 DSH）。
            runCatching { HarnessService.hold(ctx.applicationContext) }
            try {
                execute(ctx.applicationContext, run)
            } catch (e: CancellationException) {
                finishCanceled(ctx.applicationContext, runId, e.message ?: "canceled")
                throw e
            } catch (e: Exception) {
                failRun(ctx.applicationContext, runId, e.message ?: e.javaClass.simpleName)
            } finally {
                jobs.remove(runId)
                currentProcess.remove(runId)
                mutableActive.value = mutableActive.value - runId
                WorkflowGrants.revoke(runId)
                mutableTick.value++
            }
        }
        jobs[runId] = job
        mutableActive.value = mutableActive.value + runId
        return StartResult(true, runId, "")
    }

    /**
     * 取消一次运行。
     *
     * 先破坏性杀掉**当前节点**的进程，再取消协程。只 cancel 协程是不够的：命令的执行在
     * 一个阻塞的读线程上，取消要等到下一个挂起点，而那个挂点在命令自己结束之后 ——
     * 用户点了取消却要等一小时，等于没取消。
     */
    fun cancel(ctx: Context, runId: String, reason: String): Boolean {
        val job = jobs[runId] ?: return false
        currentProcess.remove(runId)?.let { p -> runCatching { p.destroyForcibly() } }
        runCatching {
            WorkflowStore.loadRun(ctx, runId)?.takeIf { it.running }?.let { run ->
                WorkflowStore.saveRun(
                    ctx,
                    run.copy(
                        state = WorkflowRunState.CANCELED,
                        endedAtMs = System.currentTimeMillis(),
                        cancelReason = reason.ifBlank { "canceled" },
                    ),
                )
            }
        }
        job.cancel(CancellationException(reason.ifBlank { "canceled" }))
        mutableTick.value++
        return true
    }

    fun status(ctx: Context, runId: String): WorkflowRun? = WorkflowStore.loadRun(ctx, runId)

    fun runs(ctx: Context, workflowId: String? = null, limit: Int = 30): List<WorkflowRun> =
        WorkflowStore.listRuns(ctx, workflowId, limit)

    // ────────────────────────── 执行 ──────────────────────────

    private suspend fun execute(ctx: Context, initial: WorkflowRun) {
        val runId = initial.runId
        val def = initial.definition ?: return
        var run = initial.copy(state = WorkflowRunState.RUNNING)
        val vars = run.variables.toMutableMap()

        /** 每个节点的完整输出（内存里，给 `extract` 用）；落盘的只有尾部。 */
        val outputs = mutableMapOf<String, String>()

        /** 判断类节点的布尔结论（`condition` / `logic` 读它）。 */
        val branches = mutableMapOf<String, Boolean>()

        fun persist() {
            run = run.copy(variables = vars.toMap())
            WorkflowStore.saveRun(ctx, run)
            mutableTick.value++
        }

        fun log(line: String) {
            WorkflowStore.appendLog(ctx, runId, line)
        }

        fun markStep(nodeId: String, state: WorkflowStepState, exitCode: Int?, output: String, reason: String) {
            val node = def.node(nodeId) ?: return
            val now = System.currentTimeMillis()
            val existing = run.steps.firstOrNull { it.nodeId == nodeId }
            val step = WorkflowStepResult(
                nodeId = nodeId,
                type = node.type,
                name = node.name.ifBlank { node.type.id },
                state = state,
                exitCode = exitCode,
                startedAtMs = existing?.startedAtMs?.takeIf { it > 0 } ?: now,
                endedAtMs = now,
                outputTail = output.takeLast(WorkflowSchema.OUTPUT_TAIL_CHARS),
                reason = reason,
            )
            run = run.copy(steps = run.steps.filterNot { it.nodeId == nodeId } + step)
            persist()
        }

        val entry = def.trigger
        if (entry == null) {
            failRun(ctx, runId, "no_trigger")
            return
        }

        var current: String? = entry.id
        var guard = 0
        while (current != null) {
            if (!currentCoroutineContext().isActive) {
                finishCanceled(ctx, runId, "canceled")
                return
            }
            if (++guard > WorkflowSchema.MAX_NODES * 4) {
                failRun(ctx, runId, "too_many_steps")
                return
            }
            val node = def.node(current)
            if (node == null) {
                failRun(ctx, runId, "dangling_node:$current")
                return
            }
            run = run.copy(currentNodeId = node.id)
            markStep(node.id, WorkflowStepState.RUNNING, null, "", "")
            val varsSnapshot = vars.toMap()
            val startedAtMs = System.currentTimeMillis()
            log("[${node.id}] ${node.type.id}${if (node.name.isNotBlank()) " (${node.name})" else ""} 开始")

            when (node.type) {
                WorkflowNodeType.TRIGGER -> {
                    // 「未启动 DSH 时先阻塞启动 DSH」：requireDsh=1 时先拉起再等；等不到就走
                    // false 分支（可以在那边画「发通知让用户手动启动」之类的补救流程）。
                    val requireDsh = flag(node.config("requireDsh"), false)
                    if (!requireDsh) {
                        branches[node.id] = true
                        markStep(node.id, WorkflowStepState.OK, null, "trigger", "")
                        current = WorkflowValidator.nextNode(def, node.id, null)
                        continue
                    }
                    run = run.copy(state = WorkflowRunState.WAITING_DSH)
                    persist()
                    if (DshRuntime.state.value.phase != DshPhase.RUNNING && flag(node.config("autostartDsh"), true)) {
                        log("[${node.id}] DSH 未运行，先启动并等待（最多 ${node.timeoutMs / 1000}s）")
                        runCatching { HarnessService.start(ctx) }
                    }
                    val up = DshRuntime.awaitRunning(node.timeoutMs)
                    run = run.copy(state = WorkflowRunState.RUNNING)
                    branches[node.id] = up
                    val hasBranch = def.outgoing(node.id).any { it.whenExpr.isNotBlank() }
                    val hasFalse = def.outgoing(node.id).any { it.whenExpr == "false" }
                    if (!up && (node.onError == WorkflowOnError.STOP && !hasFalse)) {
                        markStep(node.id, WorkflowStepState.FAILED, null, "dsh=unavailable", "dsh_unavailable")
                        failRun(ctx, runId, "dsh_unavailable")
                        return
                    }
                    markStep(
                        node.id,
                        if (up) WorkflowStepState.OK else WorkflowStepState.FAILED,
                        null,
                        if (up) "dsh=running" else "dsh=unavailable",
                        if (up) "" else "dsh_unavailable",
                    )
                    current = WorkflowValidator.nextNode(def, node.id, if (hasBranch) up else null)
                    continue
                }

                WorkflowNodeType.EXECUTE -> {
                    val action = node.actionType
                    var output = ""
                    var exitCode: Int? = null
                    var state = WorkflowStepState.OK
                    var reason = ""

                    when (action) {
                        WorkflowActionType.NOTIFY, null -> {
                            // null = action 没写或写错；校验器已经拦过，这里按失败处理而不是
                            // 静默当成 shell 跑掉（不认识的动作绝不能被"顺手"执行）。
                            if (action == null) {
                                state = WorkflowStepState.FAILED
                                reason = "bad_action"
                            } else {
                                val title = interpolate(node.config("title"), varsSnapshot).ifBlank { def.name }
                                val body = interpolate(node.config("text"), varsSnapshot)
                                val res = DshNativeBridge.handle(
                                    ctx,
                                    "POST",
                                    "/native/notify",
                                    mapOf(
                                        "title" to title,
                                        "body" to body,
                                        "reason" to "workflow:${def.name} ($runId)",
                                    ),
                                )
                                output = res.second
                                if (res.first in 200..299) {
                                    exitCode = 0
                                } else {
                                    state = WorkflowStepState.FAILED
                                    reason = "notify_failed"
                                }
                            }
                        }

                        WorkflowActionType.DSH, WorkflowActionType.SHELL -> {
                            if (action == WorkflowActionType.DSH && !DshRuntime.awaitRunning(node.timeoutMs)) {
                                state = WorkflowStepState.FAILED
                                reason = "dsh_unavailable"
                            } else {
                                val cmd = interpolate(node.config("cmd"), varsSnapshot)
                                if (cmd.isBlank()) {
                                    state = WorkflowStepState.FAILED
                                    reason = "empty_cmd"
                                } else {
                                    log("[${node.id}] $ $cmd")
                                    val sb = StringBuilder()
                                    val proc = DshRuntime.execRootfs(cmd)
                                    currentProcess[runId] = proc
                                    val reader = Thread {
                                        runCatching {
                                            proc.inputStream.bufferedReader().use { r ->
                                                r.lineSequence().forEach { line ->
                                                    synchronized(sb) { sb.appendLine(line) }
                                                    log("[${node.id}] $line")
                                                }
                                            }
                                        }
                                    }
                                    reader.isDaemon = true
                                    reader.start()
                                    reader.join(node.timeoutMs)
                                    output = synchronized(sb) { sb.toString() }
                                    if (reader.isAlive) {
                                        runCatching { proc.destroyForcibly() }
                                        reader.join(1_000)
                                        state = WorkflowStepState.TIMEOUT
                                        reason = "timeout"
                                    } else {
                                        val code = runCatching { proc.exitValue() }.getOrDefault(-1)
                                        exitCode = code
                                        if (code == 0) {
                                            state = WorkflowStepState.OK
                                        } else {
                                            state = WorkflowStepState.FAILED
                                            reason = "exit_$code"
                                        }
                                    }
                                    currentProcess.remove(runId)
                                }
                            }
                        }
                    }

                    outputs[node.id] = output
                    log(
                        "[${node.id}] 结束 state=${state.id}" +
                            (exitCode?.let { " exit=$it" } ?: "") +
                            " (${System.currentTimeMillis() - startedAtMs}ms)"
                    )
                    var failed = state == WorkflowStepState.FAILED || state == WorkflowStepState.TIMEOUT
                    if (failed && node.onError == WorkflowOnError.STOP) {
                        markStep(node.id, state, exitCode, output, reason)
                        failRun(ctx, runId, "step_failed:${node.id}")
                        return
                    }
                    if (failed && node.onError == WorkflowOnError.SKIP) {
                        markStep(node.id, WorkflowStepState.SKIPPED, exitCode, output, "skipped")
                        failed = false
                    } else {
                        markStep(node.id, state, exitCode, output, reason)
                    }
                    current = WorkflowValidator.nextNode(def, node.id, null)
                    continue
                }

                WorkflowNodeType.CONDITION -> {
                    val result = evaluateCondition(node, run, outputs, varsSnapshot, branches)
                    branches[node.id] = result
                    markStep(node.id, WorkflowStepState.OK, null, "condition=$result", "")
                    current = WorkflowValidator.nextNode(def, node.id, result)
                    continue
                }

                WorkflowNodeType.LOGIC -> {
                    val result = evaluateLogic(node, branches)
                    branches[node.id] = result
                    markStep(node.id, WorkflowStepState.OK, null, "logic=$result", "")
                    current = WorkflowValidator.nextNode(def, node.id, result)
                    continue
                }

                WorkflowNodeType.EXTRACT -> {
                    val value = extractValue(node, outputs)
                    val name = node.config("var")
                    if (value == null) {
                        markStep(node.id, WorkflowStepState.FAILED, null, "", "extract_failed")
                        if (node.onError == WorkflowOnError.STOP) {
                            failRun(ctx, runId, "extract_failed:${node.id}")
                            return
                        }
                    } else {
                        vars[name] = value
                        markStep(node.id, WorkflowStepState.OK, null, "$name=$value", "")
                    }
                    current = WorkflowValidator.nextNode(def, node.id, null)
                    continue
                }

                WorkflowNodeType.WAIT -> {
                    val until = node.config("untilDsh")
                    var ok = true
                    var reason = ""
                    if (until == "running" || until == "stopped") {
                        val deadline = System.currentTimeMillis() + node.timeoutMs
                        ok = false
                        while (System.currentTimeMillis() < deadline) {
                            if (!currentCoroutineContext().isActive) {
                                finishCanceled(ctx, runId, "canceled")
                                return
                            }
                            val running = DshRuntime.state.value.phase == DshPhase.RUNNING
                            if ((until == "running" && running) || (until == "stopped" && !running)) {
                                ok = true
                                break
                            }
                            delay(250)
                        }
                        if (!ok) reason = "dsh_state_timeout"
                    } else {
                        val ms = node.config("ms").toLongOrNull() ?: 0L
                        val deadline = System.currentTimeMillis() + ms
                        while (System.currentTimeMillis() < deadline) {
                            if (!currentCoroutineContext().isActive) {
                                finishCanceled(ctx, runId, "canceled")
                                return
                            }
                            delay(minOf(250L, (deadline - System.currentTimeMillis()).coerceAtLeast(1L)))
                        }
                    }
                    markStep(
                        node.id,
                        if (ok) WorkflowStepState.OK else WorkflowStepState.TIMEOUT,
                        null,
                        if (until.isBlank()) "waited" else "dsh $until",
                        reason,
                    )
                    if (!ok && node.onError == WorkflowOnError.STOP) {
                        failRun(ctx, runId, "wait_timeout:${node.id}")
                        return
                    }
                    current = WorkflowValidator.nextNode(def, node.id, null)
                    continue
                }
            }
        }

        run = run.copy(
            state = WorkflowRunState.SUCCEEDED,
            endedAtMs = System.currentTimeMillis(),
            currentNodeId = null,
        )
        persist()
        log("[run] succeeded")
    }

    /** 运行期注入的变量：工作流的命令里可以直接用，不必自己拼。 */
    private fun seedVariables(def: WorkflowDefinition, runId: String): Map<String, String> = mapOf(
        "run_id" to runId,
        "workflow_id" to def.id,
        "workflow_name" to def.name,
        "dsh_port" to DshRuntime.state.value.port.toString(),
        "dsh_url" to "http://127.0.0.1:${DshRuntime.state.value.port}",
        "dsh_state" to DshRuntime.state.value.phase.name.lowercase(),
    )

    private fun flag(raw: String, default: Boolean): Boolean = when (raw.trim().lowercase()) {
        "" -> default
        "1", "true", "yes", "on" -> true
        else -> false
    }

    /** `condition`：把「某一步的输出 / 退出码 / 变量 / 判断结论」按 op 变成 true/false。 */
    private fun evaluateCondition(
        node: WorkflowNode,
        run: WorkflowRun,
        outputs: Map<String, String>,
        vars: Map<String, String>,
        branches: Map<String, Boolean>,
    ): Boolean {
        val source = node.config("source")
        val op = node.config("op")
        val expect = node.config("value")
        val actual: String = when {
            source == "exitCode" ->
                run.steps.lastOrNull { it.exitCode != null }?.exitCode?.toString().orEmpty()
            source == "lastOutput" -> outputs.values.lastOrNull().orEmpty()
            source.startsWith("var:") -> vars[source.removePrefix("var:")].orEmpty()
            source.startsWith("branch:") -> branches[source.removePrefix("branch:")]?.toString().orEmpty()
            source.startsWith("step:") -> outputs[source.removePrefix("step:")].orEmpty()
            else -> ""
        }
        return when (op) {
            "exists" -> actual.isNotEmpty()
            "empty" -> actual.isEmpty()
            "equals" -> actual.trim() == expect
            "contains" -> actual.contains(expect)
            "matches" -> runCatching { Regex(expect).containsMatchIn(actual) }.getOrDefault(false)
            else -> false
        }
    }

    /** `logic`：把若干判断节点的结论 and/or/not 起来。 */
    private fun evaluateLogic(node: WorkflowNode, branches: Map<String, Boolean>): Boolean {
        val values = node.config("sources").split(',').map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { branches[it] ?: false }
        return when (node.config("op")) {
            "and" -> values.isNotEmpty() && values.all { it }
            "or" -> values.any { it }
            "not" -> values.size == 1 && !values.first()
            else -> false
        }
    }

    /** `extract`：从某一步的输出里取一个值。取不到返回 null（节点失败）。 */
    private fun extractValue(node: WorkflowNode, outputs: Map<String, String>): String? {
        val source = node.config("source")
        val raw = when {
            source == "lastOutput" -> outputs.values.lastOrNull()
            source.startsWith("step:") -> outputs[source.removePrefix("step:")]
            else -> null
        } ?: return null
        return when (node.config("mode")) {
            "text" -> raw.trim().take(WorkflowSchema.MAX_VALUE_CHARS)
            "regex" -> runCatching {
                Regex(node.config("pattern")).find(raw)?.groupValues?.lastOrNull()
            }.getOrNull()?.take(WorkflowSchema.MAX_VALUE_CHARS)
            "json" -> runCatching {
                var cur: Any = JSONObject(raw)
                for (p in node.config("path").split('.').filter { it.isNotEmpty() }) {
                    cur = when (cur) {
                        is JSONObject -> cur.opt(p) ?: return@runCatching null
                        else -> return@runCatching null
                    }
                }
                cur.toString()
            }.getOrNull()?.take(WorkflowSchema.MAX_VALUE_CHARS)
            else -> null
        }
    }

    private fun failRun(ctx: Context, runId: String, reason: String) {
        val run = WorkflowStore.loadRun(ctx, runId) ?: return
        if (run.state.terminal) return
        WorkflowStore.appendLog(ctx, runId, "[run] failed: $reason")
        WorkflowStore.saveRun(
            ctx,
            run.copy(
                state = WorkflowRunState.FAILED,
                endedAtMs = System.currentTimeMillis(),
                cancelReason = reason,
            ),
        )
        mutableTick.value++
    }

    private fun finishCanceled(ctx: Context, runId: String, reason: String) {
        val run = WorkflowStore.loadRun(ctx, runId) ?: return
        if (run.state.terminal) return
        WorkflowStore.appendLog(ctx, runId, "[run] canceled: $reason")
        WorkflowStore.saveRun(
            ctx,
            run.copy(
                state = WorkflowRunState.CANCELED,
                endedAtMs = System.currentTimeMillis(),
                cancelReason = reason,
            ),
        )
        mutableTick.value++
    }
}
