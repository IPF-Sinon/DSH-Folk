package me.bmax.apatch.dsh

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.bmax.apatch.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自动化工作流的 HTTP 端点族（`/workflow/…`），挂在 [DshFsBridge] 的同一个回环 socket 上。
 *
 * ## 为什么走回环桥而不是 Binder / 文件
 *
 * 与 [DshNativeBridge] 同一套理由：容器里没有 Binder；token 是真正的边界（只有本 App 的
 * `filesDir` 里才有），而定长比较 + 逐连接回环校验已经在 [DshFsBridge] 里做好了。这里只负责
 * 「这次请求该不该做、能不能做」。
 *
 * ## 两个端点为什么是**阻塞**的
 *
 * `create` / `run` 都要等用户在弹窗上做决定（含 60s 超时）。做成「先回 202、让 agent 过会儿
 * 再来问」会把它拆成三次往返，中间还要 agent 记得回来 —— 与提权那条路当初从 403→elevate→
 * 再调 的教训完全一样。所以这里就阻塞在连接上，用户的结论就是这次请求的结论。
 *
 * ## 免逐条确认的范围
 *
 * `run` 批准后按 `declaredCaps` 落一份**随运行失效**的授权（[WorkflowGrants]）。没声明的能力
 * 照旧逐条弹窗；危险命令还要用户在批准页**单独勾选**才免问（见 [WorkflowApprovals]）。
 */
internal object WorkflowBridge {

    private const val MAX_BODY_BYTES = 256 * 1024

    /** 一次「等待运行结束」最多阻塞多久（超过就让 agent 改用 workflow_status 查）。 */
    private const val WAIT_MAX_MS = 600_000L

    @Volatile private var reconciled = false

    fun handle(
        ctx: Context,
        method: String,
        path: String,
        params: Map<String, String>,
        readBody: () -> String,
    ): Pair<Int, String> {
        // 进程重启后把上次挂着没结束的运行标成 INTERRUPTED。只做一次、且在做任何查询之前：
        // 否则 workflow_status 会永远回一个 state=running 的运行，agent 会一直等下去。
        if (!reconciled) {
            reconciled = true
            runCatching { WorkflowStore.reconcileInterrupted(ctx.applicationContext) }
        }
        if (!enabled(ctx)) return 403 to err(str(ctx, R.string.dsh_workflow_err_disabled), "disabled")
        return when {
            method == "GET" && path == "/workflow/list" -> list(ctx, params)
            method == "GET" && path == "/workflow/status" -> status(ctx, params)
            method == "POST" && path == "/workflow/create" -> create(ctx, readBody(), params)
            method == "POST" && path == "/workflow/run" -> run(ctx, readBody(), params)
            method == "POST" && path == "/workflow/cancel" -> cancel(ctx, readBody())
            else -> 404 to err(str(ctx, R.string.dsh_workflow_err_unknown_endpoint, method, path), "unknown_endpoint")
        }
    }

    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)
            .getBoolean(DshEnv.KEY_WORKFLOW_ENABLED, false)

    // ────────────────────────── 查询 ──────────────────────────

    private fun list(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val defs = WorkflowStore.list(ctx)
        val arr = JSONArray()
        for (d in defs) arr.put(WorkflowStore.summaryJson(d))
        val body = JSONObject()
            .put("ok", true)
            .put("workflows", arr)
            .put("count", defs.size)
            .put("active", JSONArray(WorkflowEngine.active.value.toList()))
        params["withRuns"]?.let { if (it == "1" || it == "true") {
            val runs = JSONArray()
            for (r in WorkflowStore.listRuns(ctx, params["workflowId"], limit = 10)) {
                runs.put(shortRun(r))
            }
            body.put("runs", runs)
        } }
        return 200 to body.toString()
    }

    private fun status(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val runId = params["runId"].orEmpty()
        if (runId.isNotBlank()) {
            val run = WorkflowEngine.status(ctx, runId)
                ?: return 404 to err(str(ctx, R.string.dsh_workflow_err_no_run, runId), "no_such_run")
            return 200 to statusJson(ctx, run, params)
        }
        val workflowId = params["workflowId"].orEmpty()
        if (workflowId.isNotBlank()) {
            val def = WorkflowStore.load(ctx, workflowId)
                ?: return 404 to err(str(ctx, R.string.dsh_workflow_err_no_workflow, workflowId), "no_such_workflow")
            val runs = WorkflowStore.listRuns(ctx, workflowId, limit = 10)
            val arr = JSONArray()
            for (r in runs) arr.put(shortRun(r))
            return 200 to JSONObject()
                .put("ok", true)
                .put("workflow", WorkflowStore.summaryJson(def))
                .put("definition", def.toJson())
                .put("runs", arr)
                .toString()
        }
        // 什么都不给：回最近一次运行（AI 最常问的就是「我刚才那次跑到哪了」）
        val latest = WorkflowStore.listRuns(ctx, limit = 1).firstOrNull()
            ?: return 200 to JSONObject().put("ok", true).put("note", "no_runs").toString()
        return 200 to statusJson(ctx, latest, params)
    }

    private fun statusJson(ctx: Context, run: WorkflowRun, params: Map<String, String>): String {
        val body = JSONObject()
            .put("ok", true)
            .put("run", run.toJson(includeDefinition = params["withDefinition"] != "0"))
            .put("running", run.running)
            .put("stepIndex", run.steps.size)
            .put("currentNodeId", run.currentNodeId ?: JSONObject.NULL)
        val steps = JSONArray()
        for (s in run.steps) {
            steps.put(
                JSONObject()
                    .put("nodeId", s.nodeId)
                    .put("name", s.name)
                    .put("type", s.type.id)
                    .put("state", s.state.id)
                    .put("exitCode", s.exitCode ?: JSONObject.NULL)
                    .put("durationMs", s.durationMs)
                    .put("reason", s.reason)
                    .put("outputTail", s.outputTail),
            )
        }
        body.put("steps", steps)
        if (params["includeLogs"] != "0" && params["includeLogs"] != "false") {
            val fromLine = params["fromLine"]?.toIntOrNull() ?: 1
            val (lines, total) = WorkflowStore.readLog(ctx, run.runId, fromLine)
            body.put("logs", JSONArray(lines))
            body.put("logFromLine", fromLine)
            body.put("logNextLine", fromLine + lines.size)
            body.put("logTotalLines", total)
        }
        return body.toString()
    }

    private fun shortRun(run: WorkflowRun): JSONObject = JSONObject()
        .put("runId", run.runId)
        .put("workflowId", run.workflowId)
        .put("state", run.state.id)
        .put("startedAtMs", run.startedAtMs)
        .put("endedAtMs", run.endedAtMs)
        .put("steps", run.steps.size)
        .put("currentNodeId", run.currentNodeId ?: JSONObject.NULL)
        .put("reason", run.cancelReason)

    // ────────────────────────── 创建 ──────────────────────────

    private fun create(ctx: Context, body: String, params: Map<String, String>): Pair<Int, String> {
        if (body.length > MAX_BODY_BYTES) {
            return 413 to err(str(ctx, R.string.dsh_workflow_err_body_too_large), "body_too_large")
        }
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to err(str(ctx, R.string.dsh_workflow_err_bad_json), "bad_json")
        val raw = json.opt("definition")?.let { if (it is JSONObject) it.toString() else it.toString() }
            ?: json.optString("definitionJson").takeIf { it.isNotBlank() }
            ?: return 400 to err(str(ctx, R.string.dsh_workflow_err_missing_definition), "missing_definition")
        val parsed = definitionFromJson(raw)
            ?: return 400 to err(str(ctx, R.string.dsh_workflow_err_bad_definition), "invalid_definition")
        val reason = json.optString("reason", params["reason"].orEmpty())
        if (reason.isBlank()) return 400 to err(str(ctx, R.string.dsh_workflow_err_reason_required), "reason_required")

        // 名字/描述可以由调用方覆盖（AI 常常把定义里的 name 写成占位）
        val def = parsed.copy(
            id = parsed.id.ifBlank { WorkflowStore.newId() },
            name = json.optString("name").ifBlank { parsed.name },
            description = json.optString("description").ifBlank { parsed.description },
        )
        return approveAndAct(ctx, WorkflowApprovals.Kind.CREATE, def, reason, null)

    }

    // ────────────────────────── 运行 ──────────────────────────

    private fun run(ctx: Context, body: String, params: Map<String, String>): Pair<Int, String> {
        if (body.length > MAX_BODY_BYTES) {
            return 413 to err(str(ctx, R.string.dsh_workflow_err_body_too_large), "body_too_large")
        }
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to err(str(ctx, R.string.dsh_workflow_err_bad_json), "bad_json")
        val reason = json.optString("reason", params["reason"].orEmpty())
        if (reason.isBlank()) return 400 to err(str(ctx, R.string.dsh_workflow_err_reason_required), "reason_required")

        val workflowId = json.optString("workflowId").ifBlank { params["workflowId"].orEmpty() }
        val inlineRaw = json.opt("definition")?.let { if (it is JSONObject) it.toString() else it.toString() }
            ?: json.optString("definitionJson").takeIf { it.isNotBlank() }
        val def = when {
            workflowId.isNotBlank() -> WorkflowStore.load(ctx, workflowId)
                ?: return 404 to err(str(ctx, R.string.dsh_workflow_err_no_workflow, workflowId), "no_such_workflow")
            inlineRaw != null -> definitionFromJson(inlineRaw)
                ?: return 400 to err(str(ctx, R.string.dsh_workflow_err_bad_definition), "invalid_definition")
            else -> return 400 to err(str(ctx, R.string.dsh_workflow_err_missing_workflow), "missing_workflow")
        }
        val wait = json.optBoolean("wait", params["wait"] == "1" || params["wait"] == "true")
        return approveAndAct(ctx, WorkflowApprovals.Kind.RUN, def, reason, wait)
    }

    /**
     * 两个端点共用的后半段：校验 → 提交审批并**阻塞**等结论 → 落盘或开跑。
     *
     * [wait] 只有 RUN 用得上：true 时一直阻塞到运行结束（或 [WAIT_MAX_MS]），把结果与日志
     * 一起回给 AI。默认 false —— 工具调用不该把一个可能跑一小时的工作流挂在连接上。
     */
    private fun approveAndAct(
        ctx: Context,
        kind: WorkflowApprovals.Kind,
        submitted: WorkflowDefinition,
        reason: String,
        wait: Boolean?,
    ): Pair<Int, String> {
        if (!DshNativeBridge.isForeground(ctx)) {
            // 弹窗只有前台才看得见。不在前台就把这条连接留着等一个永远不会出现的弹窗 = 静默超时。
            return 409 to err(str(ctx, R.string.dsh_workflow_err_foreground), "not_foreground")
        }
        val allowedCaps = DshNativeBridge.Cap.entries.map { it.id }.toSet()
        val validation = WorkflowValidator.validate(submitted, allowedCaps)
        if (!validation.ok) {
            return 400 to err(
                str(ctx, R.string.dsh_workflow_err_invalid, validation.errors.take(8).joinToString("；")),
                "invalid_definition",
            ).let { (code, body) ->
                code to JSONObject(body).put("errors", JSONArray(validation.errors)).toString()
            }
        }
        val dangerous = scanDangerous(ctx, submitted)
        val summary = summaryOf(ctx, submitted, dangerous)

        val request = WorkflowApprovals.submit(
            kind = kind,
            workflowId = submitted.id,
            workflowName = submitted.name,
            originalJson = submitted.toJson().toString(),
            summary = summary,
            declaredCaps = submitted.declaredCaps,
            dangerous = dangerous,
            reason = reason,
            nodeCount = submitted.nodes.size,
            edgeCount = submitted.edges.size,
        ) ?: return 409 to err(str(ctx, R.string.dsh_workflow_err_busy), "approval_pending")

        val verdict = WorkflowApprovals.awaitDecision(request.id)
        WorkflowApprovals.takeVerdict(request.id)
        when (verdict.decision) {
            WorkflowApprovals.Decision.EXPIRED ->
                return 403 to err(str(ctx, R.string.dsh_workflow_err_expired), "approval_expired")

            WorkflowApprovals.Decision.REJECTED ->
                return 403 to err(
                    str(
                        ctx,
                        R.string.dsh_workflow_err_rejected,
                        verdict.rejectReason.ifBlank { str(ctx, R.string.dsh_workflow_reject_no_reason) },
                    ),
                    "rejected_by_user",
                ).let { (code, body) ->
                    code to JSONObject(body).put("rejectReason", verdict.rejectReason).toString()
                }

            WorkflowApprovals.Decision.APPROVED -> Unit
        }

        // 用户改过 → 用他那一版，并重算校验与 diff。改坏了就直接回错误，不执行。
        var effective = submitted
        var diff = emptyList<WorkflowPatch>()
        if (verdict.editedJson != null) {
            val edited = definitionFromJson(verdict.editedJson)
                ?: return 400 to err(str(ctx, R.string.dsh_workflow_err_edited_bad), "invalid_definition")
            val recheck = WorkflowValidator.validate(edited, allowedCaps)
            if (!recheck.ok) {
                return 400 to err(
                    str(ctx, R.string.dsh_workflow_err_edited_invalid, recheck.errors.take(8).joinToString("；")),
                    "invalid_definition",
                ).let { (code, body) ->
                    code to JSONObject(body).put("errors", JSONArray(recheck.errors)).toString()
                }
            }
            diff = workflowDiff(submitted, edited)
            effective = edited
        }

        val dangerousNow = scanDangerous(ctx, effective)
        val saved = WorkflowStore.save(ctx, effective)
            ?: return 500 to err(str(ctx, R.string.dsh_workflow_err_save_failed), "save_failed")

        if (kind == WorkflowApprovals.Kind.CREATE) {
            return 200 to JSONObject()
                .put("ok", true)
                .put("action", "created")
                .put("workflowId", saved.id)
                .put("revision", saved.revision)
                .put("summary", WorkflowStore.summaryJson(saved))
                .put("edited", verdict.editedJson != null)
                .put("diff", JSONArray().apply { diff.forEach { put(it.toJson()) } })
                .put("diffText", JSONArray(diff.map { it.humanize() }))
                .toString()
        }

        // RUN：授权由 start() 内部在开跑之前落好（能力 = 弹窗上列出的那些；危险命令要看
        // 用户有没有单独勾选 allowDangerous）。
        val started = WorkflowEngine.start(ctx, saved, diff, allowDangerous = verdict.allowDangerous)
        if (!started.ok || started.runId == null) {
            return 500 to err(str(ctx, R.string.dsh_workflow_err_start_failed, started.reason), "start_failed")
        }

        val body = JSONObject()
            .put("ok", true)
            .put("action", "started")
            .put("workflowId", saved.id)
            .put("runId", started.runId)
            .put("edited", verdict.editedJson != null)
            .put("diff", JSONArray().apply { diff.forEach { put(it.toJson()) } })
            .put("diffText", JSONArray(diff.map { it.humanize() }))
        if (wait == true) {
            val finished = awaitRun(ctx, started.runId!!, WAIT_MAX_MS)
            if (finished != null) {
                body.put("wait", true)
                    .put("state", finished.state.id)
                    .put("run", finished.toJson(includeDefinition = false))
            } else {
                body.put("wait", true).put("state", "still_running")
            }
        }
        return 200 to body.toString()
    }

    private fun awaitRun(ctx: Context, runId: String, timeoutMs: Long): WorkflowRun? = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        var run = WorkflowEngine.status(ctx, runId)
        while (run != null && run.running && System.currentTimeMillis() < deadline) {
            delay(400)
            run = WorkflowEngine.status(ctx, runId)
        }
        run?.takeIf { it.state.terminal }
    }

    private fun cancel(ctx: Context, body: String): Pair<Int, String> {
        val json = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
        val runId = json.optString("runId")
        if (runId.isBlank()) return 400 to err(str(ctx, R.string.dsh_workflow_err_missing_run), "missing_runId")
        val ok = WorkflowEngine.cancel(ctx, runId, json.optString("reason"))
        return if (ok) {
            200 to JSONObject().put("ok", true).put("runId", runId).put("state", "canceled").toString()
        } else {
            404 to err(str(ctx, R.string.dsh_workflow_err_not_running, runId), "not_running")
        }
    }

    // ────────────────────────── 摘要与危险扫描 ──────────────────────────

    /** 扫一遍定义里的 shell 命令，看有没有命中 [PrivPolicy] 的危险清单。 */
    private fun scanDangerous(ctx: Context, def: WorkflowDefinition): Boolean =
        def.nodes.any { n ->
            n.type == WorkflowNodeType.EXECUTE &&
                n.actionType in setOf(WorkflowActionType.SHELL, WorkflowActionType.DSH) &&
                PrivPolicy.shellRisk(ctx, n.config("cmd")) == PrivRisk.DANGEROUS
        }

    /** 批准弹窗给用户看的那几行：他判断的不是「JSON 合不合法」，而是「它要做什么」。 */
    private fun summaryOf(ctx: Context, def: WorkflowDefinition, dangerous: Boolean): String = buildString {
        append(str(ctx, R.string.dsh_workflow_summary_nodes, def.nodes.size, def.edges.size))
        if (def.declaredCaps.isNotEmpty()) {
            append("\n")
            append(str(ctx, R.string.dsh_workflow_summary_caps, def.declaredCaps.joinToString("、")))
        }
        if (dangerous) {
            append("\n")
            append(str(ctx, R.string.dsh_workflow_summary_danger))
        }
        val steps = def.nodes.take(6).joinToString("\n") { n ->
            val what = when (n.type) {
                WorkflowNodeType.TRIGGER -> str(ctx, R.string.dsh_workflow_kind_trigger)
                WorkflowNodeType.CONDITION -> str(ctx, R.string.dsh_workflow_kind_condition)
                WorkflowNodeType.LOGIC -> str(ctx, R.string.dsh_workflow_kind_logic)
                WorkflowNodeType.EXTRACT -> str(ctx, R.string.dsh_workflow_kind_extract)
                WorkflowNodeType.WAIT -> str(ctx, R.string.dsh_workflow_kind_wait)
                WorkflowNodeType.EXECUTE -> when (n.actionType) {
                    WorkflowActionType.NOTIFY -> str(ctx, R.string.dsh_workflow_kind_notify)
                    WorkflowActionType.DSH -> "dsh: " + n.config("cmd").take(80)
                    else -> "$ " + n.config("cmd").take(80)
                }
            }
            if (n.name.isBlank()) what else "${n.name} — $what"
        }
        if (steps.isNotBlank()) {
            append("\n")
            append(steps)
            if (def.nodes.size > 6) append("\n…")
        }
    }

    private fun str(ctx: Context, resId: Int, vararg args: Any): String = DshNativeBridge.str(ctx, resId, *args)

    private fun err(msg: String, reason: String): String =
        JSONObject().put("ok", false).put("reason", reason).put("error", msg).toString()
}
