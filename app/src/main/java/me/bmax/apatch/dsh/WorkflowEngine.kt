package me.bmax.apatch.dsh

import android.content.Context
import me.bmax.apatch.dsh.ext.ExtensionHookRegistry
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

    /**
     * 单步输出保留的字符上限（只保留超出部分的行数，不保留内容）。
     *
     * 与 DshShizukuShellService.MAX_CHARS 同一个理由：命令输出以前整份攒在内存里，
     * 一条刷几百万行的命令足以把 App 顶爆（外部审计 P2-1b）。
     */
    private const val MAX_STEP_OUTPUT_CHARS = 100_000

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
            caps = def.effectiveCaps,
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
            val resolved = def.node(current)
            if (resolved == null) {
                failRun(ctx, runId, "dangling_node:$current")
                return
            }
            run = run.copy(currentNodeId = resolved.id)
            markStep(resolved.id, WorkflowStepState.RUNNING, null, "", "")
            // **数据边在这里落地**：把上游的值写进本节点的 config，再交给下面的执行逻辑。
            //
            // 为什么在"执行前"而不是在解析定义时替换：上游的值只有跑到那一步才知道，
            // 而 config 是**每步现算**的（下面的 varsSnapshot 也是同一个道理）。这样
            // 下游节点拿到的就是普通 config，不需要在任何执行分支里再插一次"取数据边"的逻辑。
            //
            // 注意：数据边只改 config，id/type/name/onError/timeoutMs 全部不变，所以这里
            // 直接把 node 指到带数据的副本上，下面的 setContent 不用逐处替换（漏一处的
            // 后果是某个分支读到旧 config，而那种 bug 只在"连了数据边"时才出现）。
            val node = applyDataEdges(def, resolved, outputs, vars, branches)
            val varsSnapshot = vars.toMap()
            val startedAtMs = System.currentTimeMillis()
            log("[${node.id}] ${node.type.id}${if (node.name.isNotBlank()) " (${node.name})" else ""} 开始")

            // 扩展拦截点：**每个节点真正执行之前**。
            //
            // around 返回 false = 扩展替这个节点做了决定，宿主**跳过**该节点的默认实现。
            // 这是工作流里的通用口：扩展可以插入自己的节点类型语义，而不必改宿主代码。
            //
            // 注意：跳过之后，扩展要自己保证 branches[node.id] 与 markStep 状态正确，
            // 否则后续分支路由会拿到未定义的布尔值。这个责任由扩展承担（API 文档里写明）。
            val extHandled = if (ExtensionHookRegistry.has(ExtensionHookRegistry.Point.WORKFLOW_EXECUTE_NODE)) {
                val payload = mutableMapOf<String, Any?>("node" to node, "vars" to varsSnapshot)
                val proceed = ExtensionHookRegistry.runAround(ExtensionHookRegistry.Point.WORKFLOW_EXECUTE_NODE, payload)
                if (!proceed) {
                    branches[node.id] = branches[node.id] ?: true
                    markStep(node.id, WorkflowStepState.OK, null, node.type.id, "扩展处理")
                    current = WorkflowValidator.nextNode(def, node.id, branches[node.id])
                }
                !proceed
            } else {
                false
            }
            if (extHandled) continue

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
                    // 原生动作的 id（仅 NATIVE 用）。取一次，分支里两处都要用。
                    val exId = node.config("bridge")
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

                        WorkflowActionType.NATIVE -> {
                            // 走宿主桥的真实端点（不是拼一条 shell 去"模拟"）：参数由动作目录
                            // 声明，Cap 由目录绑定 —— 批准弹窗列出的能力与实际调用的是同一份。
                            val spec = WorkflowActionCatalog.byId(exId)
                            if (spec == null) {
                                state = WorkflowStepState.FAILED
                                reason = "bad_bridge_action"
                            } else {
                                val payload = buildMap<String, String> {
                                    for (p in spec.params) {
                                        val v = interpolate(node.config(spec.configKey(p)), varsSnapshot)
                                        if (v.isNotBlank()) put(p.key, v)
                                    }
                                    put("reason", "workflow:${def.name} ($runId)")
                                }
                                val res = DshNativeBridge.handle(ctx, spec.method, spec.path, payload)
                                output = res.second
                                exitCode = if (res.first in 200..299) 0 else res.first
                                if (res.first in 200..299) {
                                    state = WorkflowStepState.OK
                                } else {
                                    state = WorkflowStepState.FAILED
                                    reason = "http_${res.first}"
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
                                    // 边读边截：一条刷几百万行的命令（ls -R、死循环 echo）以前会把
                                    // 整份输出攒在内存里（外部审计 P2-1b）。超限的行只计数。
                                    var kept = 0
                                    var dropped = 0
                                    val proc = DshRuntime.execRootfs(cmd)
                                    currentProcess[runId] = proc
                                    val reader = Thread {
                                        runCatching {
                                            proc.inputStream.bufferedReader().use { r ->
                                                r.lineSequence().forEach { line ->
                                                    synchronized(sb) {
                                                        if (kept < MAX_STEP_OUTPUT_CHARS) {
                                                            sb.appendLine(line)
                                                            kept += line.length + 1
                                                        } else {
                                                            dropped++
                                                        }
                                                    }
                                                    log("[${node.id}] $line")
                                                }
                                            }
                                        }
                                    }
                                    reader.isDaemon = true
                                    reader.start()
                                    reader.join(node.timeoutMs)
                                    output = synchronized(sb) {
                                        if (dropped == 0) sb.toString()
                                        else sb.toString() + "…(已截断，另有 $dropped 行未保留)"
                                    }
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

        // 分支未命中的下游节点：不执行，但不该「从结果里消失」—— 标 SKIPPED 并写明原因。
        // 以前这些节点在 steps 里根本没有：画布上看着像漏了一块，报告里也读不出「这条分支没走」
        // 与「根本没跑」的区别。只在**成功走完**时判 —— 失败/取消时未执行的节点属于「没来得及跑」，
        // 拿 SKIPPED 描述它们会说假话。
        val visited = run.steps.map { it.nodeId }.toSet()
        for (candidate in def.nodes) {
            if (candidate.id !in visited) {
                markStep(candidate.id, WorkflowStepState.SKIPPED, null, "", "branch_not_taken")
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
    /**
     * 开跑时的变量初值 = **流程内声明的变量** + 每次运行现算的系统变量。
     *
     * 为什么要把 def.variables 真正种进来：上一版它只被存盘和回读，
     * 从来没有进入运行期的 vars —— 也就是说，用户/agent 在定义里写了
     * "默认值"，运行时候却取不到（${name} 原样留在命令里）。这类"写了不生效"
     * 的问题最难查，因为它看起来完全配好了。
     *
     * 顺序：先声明的，后者覆盖前者 —— 系统变量（run_id / dsh_port 之类）必须赢，
     * 否则用户声明一个 dsh_port 就能把真实端口遮住。
     */
    private fun seedVariables(def: WorkflowDefinition, runId: String): Map<String, String> =
        def.variables + mapOf(
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
            source == "result" -> outputs[node.id].orEmpty()
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

    /**
     * 把数据边接到本节点的 config 上。
     *
     * 判据：节点收到几条数据边，就有几个参数**由上游决定**（覆盖用户在界面上填的值）。
     * 覆盖而不是"上游为空就保留原值"—— 上游跑出来是空串本身就是一个结果，
     * 悄悄退回手填值会让"我明明把 A 的输出接过去了，怎么没生效"变成谜。
     *
     * 起点的三种写法（与 [WorkflowValidator] 认的一致）：
     *  - `step:<id>` 或直接 `<id>`：取那个节点本次运行的完整输出；
     *  - `var:<name>`：取运行变量（`extract` 写进去的那种）。
     */
    private fun applyDataEdges(
        def: WorkflowDefinition,
        node: WorkflowNode,
        outputs: Map<String, String>,
        vars: Map<String, String>,
        branches: Map<String, Boolean>,
    ): WorkflowNode {
        val incoming = def.dataInto(node.id)
        if (incoming.isEmpty()) return node
        val patch = mutableMapOf<String, String>()
        for (e in incoming) {
            val raw = when {
                e.fromPin.startsWith("var:") -> vars[e.fromPin.removePrefix("var:")].orEmpty()
                e.fromPin.startsWith("branch:") -> branches[e.fromPin.removePrefix("branch:")]?.toString().orEmpty()
                e.fromPin.startsWith("step:") -> outputs[e.fromPin.removePrefix("step:")].orEmpty()
                // 「结果」出口：源头节点自己的整体输出。这是"把这一步拿到的值接到别处"
                // 的主路径 —— 每个节点都有 result 针脚，所以任何一步的输出都能被接走，
                // 不用为了传一个值专门插一个 extract 节点。
                e.fromPin == "result" -> outputs[e.from].orEmpty()
                else -> outputs[e.fromPin].orEmpty()
            }
            // 按声明的类型收敛一下：number 只留数字形态，bool 归一成 1/0。
            // 不收敛的话，"上游输出了 3 行文字"喂进 number 参数，下游拿到的是那段文字，
            // 而它会被 validator 判合法（validator 只查静态定义里写的东西）。
            val value = coerceValue(raw, e.dataType)
            // ── 多来源参数：追加，不是覆盖 ──
            //
            // `logic.sources` 的语义是"把若干判断节点的结论 and/or 起来"（见 evaluateLogic：
            // 它把 sources 按逗号切开当节点 id 列表用）。所以接到它上面的每一根线都要**保留**，
            // 直接 patch[toPin] = value 会让最后接的那根把前面全部顶掉 —— 用户接三根线，
            // 实际只有一个来源参与运算，而且看不出为什么。
            //
            // 参考实现用 PinAdd 动态增删针脚来表达"可多个"；这里用一个入口 + 逗号列表，
            // 表达能力等价，且不必给针脚表引入落盘状态。
            val multi = e.toPin in MULTI_SOURCE_PINS
            patch[e.toPin] = if (multi) {
                // 存的是**来源节点 id**（evaluateLogic 拿它查 branches），不是上游的输出值。
                val srcId = e.from
                val kept = (patch[e.toPin] ?: node.config(e.toPin))
                    .split(',').map { it.trim() }.filter { it.isNotEmpty() && it != srcId }
                (kept + srcId).joinToString(",")
            } else {
                value
            }
        }
        return node.copy(config = node.config + patch)
    }

    /** 数据边的类型收敛。认不出的类型一律当字符串原样传。 */
    private fun coerceValue(raw: String, dataType: String): String = when (dataType) {
        "number" -> raw.trim().let { t ->
            t.toLongOrNull()?.toString()
                ?: Regex("-?\\d+(\\.\\d+)?").find(t)?.value
                ?: ""
        }
        "bool" -> when (raw.trim().lowercase()) {
            "1", "true", "yes", "on" -> "1"
            "0", "false", "no", "off" -> "0"
            else -> ""
        }
        else -> raw
    }

    /**
     * 语义是「多个来源串起来」的参数键。接到这些键上的数据边要**追加**而不是覆盖。
     *
     * 目前只有 `logic.sources`：它按逗号切成节点 id 列表（见 [evaluateLogic]）。
     * 其余参数都是单值，保持覆盖语义 —— 那是"这个参数的值由上游决定"的正确含义。
     */
    private val MULTI_SOURCE_PINS = setOf("sources")

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
