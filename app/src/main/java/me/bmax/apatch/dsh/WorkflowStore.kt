package me.bmax.apatch.dsh

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * 工作流定义、运行记录与日志的落盘。
 *
 * ## 为什么全部走「临时文件 + rename」
 *
 * 定义的每一次保存、运行的每一次状态推进都会覆盖同一个文件。直接 `writeText` 在中途被杀
 * （Android 上这是常态）会留下半截 JSON —— 而读取端看到的是「工作流损坏」。rename 在同一
 * 文件系统内是原子的：读到的东西要么是旧的完整版，要么是新的完整版。
 *
 * ## 为什么运行日志单独一个文件
 *
 * 日志是**追加**的、量最大，而运行状态是**整份重写**的小对象。把日志塞进 JSON 会让每次
 * 追加都重写整份状态（长流程里这是每秒一次的全量写）。分开之后，`workflow_status`
 * 支持按行号续读（`fromLine`），不会因为日志变大而变慢。
 */
internal object WorkflowStore {
    private const val RUN_LOG_MAX_BYTES = 2L * 1024 * 1024

    /** 定义文件名即 id；只允许校验器认过的 id 形状，顺带挡住路径穿越。 */
    private val ID_SAFE = Regex("^[A-Za-z][A-Za-z0-9_-]{0,31}$")

    fun newId(): String = "wf_" + UUID.randomUUID().toString().replace("-", "").take(12)

    fun newRunId(): String = "run_" + UUID.randomUUID().toString().replace("-", "").take(12)

    private fun defDir(ctx: Context): File = DshEnv.workflowDir(ctx).apply { mkdirs() }

    private fun runDir(ctx: Context): File = DshEnv.workflowRunDir(ctx).apply { mkdirs() }

    private fun draftDir(ctx: Context): File = DshEnv.workflowDraftDir(ctx).apply { mkdirs() }

    private fun defFile(ctx: Context, id: String): File? =
        if (ID_SAFE.matches(id)) File(defDir(ctx), "$id.json") else null

    // ────────────────────────── 定义 ──────────────────────────

    fun list(ctx: Context): List<WorkflowDefinition> =
        defDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { f -> runCatching { definitionFromJson(f.readText()) }.getOrNull() }
            ?.sortedByDescending { it.updatedAtMs }
            ?: emptyList()

    fun load(ctx: Context, id: String): WorkflowDefinition? =
        defFile(ctx, id)?.takeIf { it.isFile }
            ?.let { f -> runCatching { definitionFromJson(f.readText()) }.getOrNull() }

    /** 保存定义（原子）。返回落盘后的定义（revision 已 +1）。 */
    fun save(ctx: Context, def: WorkflowDefinition): WorkflowDefinition? {
        val file = defFile(ctx, def.id) ?: return null
        val now = System.currentTimeMillis()
        val existing = load(ctx, def.id)
        val saved = def.copy(
            revision = (existing?.revision ?: 0) + 1,
            createdAtMs = existing?.createdAtMs ?: now,
            updatedAtMs = now,
        )
        return if (atomicWrite(file, saved.toJson().toString())) saved else null
    }

    fun delete(ctx: Context, id: String): Boolean = defFile(ctx, id)?.delete() ?: false

    // ────────────────────────── 草稿（编辑中、还没批准）──────────────────────────

    fun saveDraft(ctx: Context, approvalId: Long, def: WorkflowDefinition): Boolean =
        atomicWrite(File(draftDir(ctx), "draft_$approvalId.json"), def.toJson().toString())

    fun loadDraft(ctx: Context, approvalId: Long): WorkflowDefinition? {
        val f = File(draftDir(ctx), "draft_$approvalId.json")
        return if (f.isFile) runCatching { definitionFromJson(f.readText()) }.getOrNull() else null
    }

    fun clearDraft(ctx: Context, approvalId: Long) {
        runCatching { File(draftDir(ctx), "draft_$approvalId.json").delete() }
    }

    // ────────────────────────── 运行 ──────────────────────────

    fun saveRun(ctx: Context, run: WorkflowRun) {
        atomicWrite(File(runDir(ctx), "${run.runId}.json"), run.toJson().toString())
    }

    fun loadRun(ctx: Context, runId: String): WorkflowRun? {
        if (!ID_SAFE.matches(runId)) return null
        val f = File(runDir(ctx), "$runId.json")
        return if (f.isFile) runFromJson(f.readText()) else null
    }

    fun listRuns(ctx: Context, workflowId: String? = null, limit: Int = 30): List<WorkflowRun> =
        runDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { f -> runCatching { runFromJson(f.readText()) }.getOrNull() }
            ?.filter { workflowId == null || it.workflowId == workflowId }
            ?.sortedByDescending { it.startedAtMs }
            ?.take(limit)
            ?: emptyList()

    /**
     * 进程重启后，把「上次还挂着的运行」标成 [WorkflowRunState.INTERRUPTED]。
     *
     * 必须做，而且必须**只做一次**：不标的话，`workflow_status` 会永远回一个 `running` 的
     * 运行，AI 就会一直等一个永远不会结束的东西。
     */
    fun reconcileInterrupted(ctx: Context) {
        for (run in listRuns(ctx, limit = 200)) {
            if (!run.running) continue
            saveRun(
                ctx,
                run.copy(state = WorkflowRunState.INTERRUPTED, endedAtMs = System.currentTimeMillis()),
            )
        }
    }

    // ────────────────────────── 日志 ──────────────────────────

    fun appendLog(ctx: Context, runId: String, line: String) {
        if (!ID_SAFE.matches(runId)) return
        val f = File(runDir(ctx), "$runId.log")
        if (f.isFile && f.length() > RUN_LOG_MAX_BYTES) {
            // 超上限就轮转一次（保留上一段）：长流程不能把磁盘写满，但也不能把开头整段丢掉
            // ——出问题时要看的多半正是最开始那几行。
            runCatching {
                File(runDir(ctx), "$runId.prev.log").also { it.delete() }
                f.renameTo(File(runDir(ctx), "$runId.prev.log"))
            }
        }
        runCatching { f.appendText(line.take(WorkflowSchema.MAX_LOG_LINE_CHARS) + "\n", StandardCharsets.UTF_8) }
    }

    /**
     * 读日志。[fromLine] 是**行号**（1 起）：`workflow_status` 只回上一次之后新增的部分，
     * 长流程不必每次把几万行重新回一遍（那会直接吃掉模型的上下文）。
     */
    fun readLog(ctx: Context, runId: String, fromLine: Int = 1, maxLines: Int = 400): Pair<List<String>, Int> {
        if (!ID_SAFE.matches(runId)) return emptyList<String>() to 0
        val lines = mutableListOf<String>()
        for (name in listOf("$runId.prev.log", "$runId.log")) {
            val f = File(runDir(ctx), name)
            if (f.isFile) lines += runCatching { f.readLines() }.getOrDefault(emptyList())
        }
        val start = (fromLine - 1).coerceAtLeast(0)
        if (start >= lines.size) return emptyList<String>() to lines.size
        val slice = lines.subList(start, minOf(lines.size, start + maxLines))
        return slice.toList() to lines.size
    }

    // ────────────────────────── 工具 ──────────────────────────

    /** 原子写：先写 `.tmp` 再 rename（rename 在同一文件系统内是原子的）。 */
    private fun atomicWrite(target: File, text: String): Boolean = runCatching {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text, StandardCharsets.UTF_8)
        if (target.exists()) target.delete()
        tmp.renameTo(target)
    }.getOrDefault(false)

    /** 定义摘要（批准弹窗与列表共用；不含节点细节）。 */
    fun summaryJson(def: WorkflowDefinition): JSONObject = JSONObject()
        .put("id", def.id)
        .put("name", def.name)
        .put("description", def.description)
        .put("revision", def.revision)
        .put("nodeCount", def.nodes.size)
        .put("edgeCount", def.edges.size)
        .put("declaredCaps", JSONArray(def.declaredCaps))
        .put("declaresDangerous", def.declaresDangerous)
        .put("updatedAtMs", def.updatedAtMs)
}
