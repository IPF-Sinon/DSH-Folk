package me.bmax.apatch.dsh

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.util.appString
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 把「刚恢复进来的会话」放回它该在的工作区。
 *
 * ## 为什么需要它
 *
 * App 的会话恢复只写 `~/.dsh/sessions` 文件，而 dsh 的**工作区分组只在注册表首次 bootstrap
 * 时做一次**（`@deepseek-ai/dsh-workspace` 的 `Service.init()`：`if (!state.initialized) bootstrap(headers)`）。
 * 之后再放进 sessions 树的会话永远不会被归组 —— 界面上一律显示「未分组」，而 GUI 没有
 * 「从未分组移进工作区」的入口。所以恢复完会话，看着像是「都进来了」，实际全散着。
 *
 * 而且**光把 session id 塞进 `workspace.json` 的 `sessionIds` 也没用**：成员判定是
 * `host.sessionPath(id) === record.path`，`sessionPath(id)` 由会话 header 的 `cwd` 反推。
 * 跨设备迁移时源路径（如 `/root/deepseek-harness`）与本机工作区路径不同，那条会话会被
 * 上游的 `reportFilteredCandidates()` 过滤掉，只在日志里留一句
 * `canonical cwd '<a>' differs from workspace path '<b>'`。
 *
 * 真正归组要同时满足三件事，[DshSessionGroup] 把它们交给容器里的
 * `assets/dsh-session-group.cjs` 一次做完（那边能解 zstd、也能算 realpath）：
 *   1. 文件在 `<sessions>/<projectKey(cwd)>/<encodeSegment(id)>/session.jsonl.zstd`；
 *   2. header 的 cwd（realpath 后）== 工作区记录的 path；
 *   3. id 在该工作区 `sessionIds` 里，且不在别的工作区里。
 *
 * ## 为什么必须在服务停止时做
 *
 * 注册表以内存状态为准、启动才读盘、之后整份写回：运行中改文件会被覆盖。调用方负责
 * 用 [DshRuntime.withServiceStopped] 把它包起来 —— 这里不自己启停，免得两处各自启停撞车。
 *
 * ## 为什么脚本放 assets 而不是内联
 *
 * 它要按 zstd 帧头规格切帧、要做红线校验与回滚，几百行塞进 `node -e "…"` 的引号里
 * 只会变成不可读也不可测的字符串；放 assets 后 [tools/check-session-group.js] 能拿真夹具
 * 直接把它跑起来验收。
 */
object DshSessionGroup {
    private const val ASSET = "dsh-session-group.cjs"
    private const val REPORT_PREFIX = "DSH_GROUP_REPORT "

    /** 容器内路径（rootfs 即容器根；DshEnv.dshHome 对应容器里的 /root/.dsh）。 */
    private const val SESSIONS_ROOT = "/root/.dsh/sessions"
    private const val REGISTRY = "/root/.dsh/storages/workspace.json"

    /** 助手跑一次的结果。字段与脚本输出的 JSON 一一对应。 */
    data class Report(
        val ok: Boolean = false,
        val applied: Boolean = false,
        /** 本次处理的会话数。 */
        val total: Int = 0,
        /** 成功归组的条数。 */
        val grouped: Int = 0,
        /** 改写了 header（跨设备路径映射）的条数。 */
        val rewritten: Int = 0,
        /** 按 basename 推断出目标的条数。 */
        val inferredCount: Int = 0,
        /** 未找到对应工作区的会话（含原因）。 */
        val ungrouped: List<String> = emptyList(),
        /** header 不合法、无法归组的会话（含原因）。 */
        val unreadable: List<String> = emptyList(),
        /** 被挪出 sessions 树的坏会话。 */
        val quarantined: List<String> = emptyList(),
        /** 良性跳过/拒绝写入的原因（空=没跳过）。 */
        val skipped: String = "",
        /** 注册表红线校验失败时的问题清单。 */
        val problems: List<String> = emptyList(),
        /** 写盘失败后是否已回滚。 */
        val rolledBack: Boolean = false,
        /** 备份文件路径（容器内）。 */
        val backup: String = "",
        /** 助手完全没跑起来时的说明。 */
        val failure: String = "",
    ) {
        /** 给界面看的一句话摘要。 */
        fun summary(ctx: Context): String = when {
            failure.isNotEmpty() -> ctx.appString(R.string.dsh_bk_group_failed, failure)
            skipped.isNotEmpty() && grouped == 0 && unreadable.isEmpty() ->
                ctx.appString(R.string.dsh_bk_group_skipped, skipped)
            else -> buildString {
                append(ctx.appString(R.string.dsh_bk_group_done, grouped, total))
                if (rewritten > 0) append(ctx.appString(R.string.dsh_bk_group_rewritten, rewritten))
                if (inferredCount > 0) append(ctx.appString(R.string.dsh_bk_group_inferred, inferredCount))
                if (ungrouped.isNotEmpty()) {
                    append(ctx.appString(R.string.dsh_bk_group_ungrouped, ungrouped.size))
                }
                if (unreadable.isNotEmpty()) {
                    append(ctx.appString(R.string.dsh_bk_group_unreadable, unreadable.size))
                }
                if (problems.isNotEmpty()) {
                    append(ctx.appString(R.string.dsh_bk_group_problems, problems.size))
                }
                if (rolledBack) append(ctx.appString(R.string.dsh_bk_group_rolled_back))
            }
        }

        /** 给界面看的逐条明细（每行一条，最多 [limit] 条，其余折叠成计数）。 */
        fun details(limit: Int = 6): String {
            val lines = mutableListOf<String>()
            for (s in ungrouped.take(limit)) lines += "· " + s
            if (ungrouped.size > limit) lines += "· …(+${ungrouped.size - limit})"
            for (s in unreadable.take(limit)) lines += "! " + s
            if (unreadable.size > limit) lines += "! …(+${unreadable.size - limit})"
            for (s in quarantined.take(limit)) lines += "⊘ " + s
            for (s in problems.take(limit)) lines += "✗ " + s
            if (backup.isNotEmpty()) lines += "↩ " + backup
            return lines.joinToString("\n")
        }
    }

    /**
     * 归组本次恢复的会话。
     *
     * @param relPaths 本次写进 sessions 树的会话相对路径（相对 sessions 根），来自
     *   [DshConfigBackup.restoreSessionsFromZip]；空列表直接返回，避免白跑一次容器。
     * @param maps 源设备 cwd → 本机工作区路径 的显式映射（跨设备迁移时用）
     * @param onLine 助手输出的行进界面日志（在 IO 线程回调）
     */
    suspend fun groupRestoredSessions(
        ctx: Context,
        relPaths: List<String>,
        maps: List<Pair<String, String>> = emptyList(),
        onLine: (String) -> Unit = {},
    ): Report = withContext(Dispatchers.IO) {
        if (relPaths.isEmpty()) return@withContext Report(ok = true, skipped = "no sessions restored")

        val tmpDir = DshEnv.tmpDir(ctx).apply { mkdirs() }
        val script = File(tmpDir, ASSET)
        val listFile = File(tmpDir, "dsh-restored-sessions.txt")
        try {
            val scriptText = ctx.assets.open(ASSET).bufferedReader().use { it.readText() }
            script.writeText(scriptText)
            // 容器内看到的是 /tmp/...（rootfs/tmp 就是容器的 /tmp）
            listFile.writeText(relPaths.joinToString("\n") + "\n")

            val mapArgs = maps
                .filter { it.first.isNotBlank() && it.second.isNotBlank() }
                .joinToString("") { " --map " + shellQuoted("${it.first}=${it.second}") }
            val cmd = buildString {
                append("node /tmp/").append(ASSET)
                append(" --sessions-root ").append(shellQuoted(SESSIONS_ROOT))
                append(" --registry ").append(shellQuoted(REGISTRY))
                append(" --paths-file /tmp/").append(listFile.name)
                append(mapArgs)
                append(" --apply 2>&1")
            }

            var sawReport: Report? = null
            val raw = DshRuntime.execRootfsStreaming(cmd, 300_000L) { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith(REPORT_PREFIX)) {
                    sawReport = parseReport(trimmed.removePrefix(REPORT_PREFIX))
                } else if (trimmed.isNotEmpty()) {
                    onLine(trimmed)
                }
            }
            sawReport ?: parseReport(raw.substringAfter(REPORT_PREFIX, "").substringBefore('\n').trim())
                ?: Report(failure = raw.take(300).ifBlank { ctx.appString(R.string.dsh_bk_group_no_output) })
        } catch (e: Exception) {
            Report(failure = e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { script.delete() }
            runCatching { listFile.delete() }
        }
    }

    /** 解析助手输出的报告 JSON；解析不了返回 null（调用方据此报「没输出」）。 */
    private fun parseReport(json: String): Report? {
        if (json.isBlank()) return null
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        fun strings(key: String): List<String> {
            val arr: JSONArray = o.optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                when (val v = arr.opt(i)) {
                    is JSONObject -> buildString {
                        val id = v.optString("id")
                        val cwd = v.optString("cwd")
                        val reason = v.optString("reason").ifEmpty { v.optString("action") }
                        if (id.isNotEmpty()) append(id).append("  ")
                        if (cwd.isNotEmpty()) append(cwd).append("  ")
                        append(reason)
                    }.trim()
                    is String -> v
                    else -> null
                }
            }
        }
        return Report(
            ok = o.optBoolean("ok", false),
            applied = o.optBoolean("applied", false),
            total = o.optInt("total", 0),
            grouped = o.optInt("grouped", 0),
            rewritten = o.optInt("rewritten", 0),
            inferredCount = o.optJSONArray("inferred")?.length() ?: 0,
            ungrouped = strings("ungrouped"),
            unreadable = strings("unreadable"),
            quarantined = strings("quarantined"),
            skipped = o.optString("skipped"),
            problems = strings("problems"),
            rolledBack = o.optBoolean("rolledBack", false),
            backup = o.optString("backup"),
        )
    }

    /** 单引号包一层：容器里的路径可能带空格（工作区路径是用户选的）。 */
    private fun shellQuoted(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
