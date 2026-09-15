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
 * ## 为什么调用方要停着服务做（可以不停，但没必要冒这个险）
 *
 * 注册表是 storage-json：启动读一次盘，之后内存权威，平时不重读盘；任意一次 workspace
 * 域写会把整份内存状态原样写回。所以运行中改文件其实**能生效**（只要改完到重启之间没有
 * workspace 写），只是万一中间有那么一次写，改动会被静默盖掉、重启后一切照旧。
 * 停止服务把这一条风险归零，代价是几秒停机 —— 导入流程之后用户还会继续动工作区，划算。
 * 调用方负责用 [DshRuntime.withServiceStopped] 包起来 —— 这里不自己启停，免得两处撞车。
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
        /** 本次处理的**日志文件**数（同一会话的新旧两个文件各算一个）。 */
        val total: Int = 0,
        /** 成功归组的**日志文件**数。 */
        val grouped: Int = 0,
        /**
         * 本次处理的**会话**数。
         *
         * 与 [total] 分开是有原因的：dsh 的会话目录里可能有新旧两个日志文件
         * （`session.jsonl.zstd` 与 `session.v3.jsonl.zstd`），按文件数报出来的
         * 「9/15 条」读起来像有 9 个会话进了工作区，而实际只有 6 个。
         */
        val sessions: Int = 0,
        /** 成功归组的**会话**数。 */
        val groupedSessions: Int = 0,
        /** 日志文件总数（含同一会话的新旧副本）。 */
        val files: Int = 0,
        /** 清单里被忽略的非会话文件数（session.lock 这类运行时文件）。 */
        val ignoredNonSession: Int = 0,
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
                // 报会话数而不是文件数（助手会把两者都给出来；老版本助手没有这两个字段时退回文件数）
                val sessionCount = if (sessions > 0) sessions else total
                val groupedCount = if (sessions > 0) groupedSessions else grouped
                append(ctx.appString(R.string.dsh_bk_group_done, groupedCount, sessionCount))
                if (files > sessionCount) append(ctx.appString(R.string.dsh_bk_group_files, files))
                if (ignoredNonSession > 0) append(ctx.appString(R.string.dsh_bk_group_ignored, ignoredNonSession))
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
            // 恢复清单里混进的运行时文件（session.lock）不是错误，只是不会被处理；
            // 静默忽略过一次，结果用户看到的是一串没有文件名的报错 —— 说清楚。
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
        onLine: suspend (String) -> Unit = {},
    ): Report = withContext(Dispatchers.IO) {
        if (relPaths.isEmpty()) return@withContext Report(ok = true, skipped = "no sessions restored")
        run(ctx, relPaths, maps, onLine)
    }

    /**
     * 把树上**所有**还没归属的会话归回工作区。
     *
     * 为什么要单独开一个入口：归组原先只发生在「导入会话」那一刻，而
     * [DshConfigBackup.restoreSessionsFromZip] 遇到已存在的文件是**跳过**的。
     * 于是旧版本导入过的那批会话（正是「全是未分组」的那批）再导入多少次都不会被
     * 重新处理 —— 用户需要一个能主动整理的动作，而不是删掉文件重导。
     *
     * 注意这是全树扫描：用户故意留在「未分组」里的会话也会被按 cwd 归回它所属的工作区。
     * 想看清单可以先不写盘（助手支持预览），这里直接执行并在结果里逐条列出。
     */
    suspend fun tidyAllSessions(
        ctx: Context,
        maps: List<Pair<String, String>> = emptyList(),
        onLine: suspend (String) -> Unit = {},
    ): Report = withContext(Dispatchers.IO) { run(ctx, null, maps, onLine) }

    /** 跑一次助手；[relPaths] 为 null 表示扫全树。 */
    private suspend fun run(
        ctx: Context,
        relPaths: List<String>?,
        maps: List<Pair<String, String>>,
        onLine: suspend (String) -> Unit,
    ): Report = withContext(Dispatchers.IO) {
        val tmpDir = DshEnv.tmpDir(ctx).apply { mkdirs() }
        val script = File(tmpDir, ASSET)
        val listFile = File(tmpDir, "dsh-restored-sessions.txt")
        try {
            val scriptText = ctx.assets.open(ASSET).bufferedReader().use { it.readText() }
            script.writeText(scriptText)
            // 容器内看到的是 /tmp/...（rootfs/tmp 就是容器的 /tmp）
            if (relPaths != null) listFile.writeText(relPaths.joinToString("\n") + "\n")

            val mapArgs = maps
                .filter { it.first.isNotBlank() && it.second.isNotBlank() }
                .joinToString("") { " --map " + shellQuoted("${it.first}=${it.second}") }
            val cmd = buildString {
                append("node /tmp/").append(ASSET)
                append(" --sessions-root ").append(shellQuoted(SESSIONS_ROOT))
                append(" --registry ").append(shellQuoted(REGISTRY))
                // 不给 --paths-file 就是扫全树（助手侧据此决定处理范围）
                if (relPaths != null) append(" --paths-file /tmp/").append(listFile.name)
                append(mapArgs)
                append(" --apply 2>&1")
            }

            // execRootfsStreaming 的回调不是挂起上下文，所以先缓冲、拿到结果后再发出去。
            // 助手本身是秒级的（不像 pnpm 装包要几分钟），这点延迟换的是回调签名干净。
            val pending = mutableListOf<String>()
            var sawReport: Report? = null
            val raw = DshRuntime.execRootfsStreaming(cmd, 300_000L) { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith(REPORT_PREFIX)) {
                    sawReport = parseReport(trimmed.removePrefix(REPORT_PREFIX))
                } else if (trimmed.isNotEmpty()) {
                    pending += trimmed
                }
            }
            pending.forEach { onLine(it) }
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
                        // 路径必须打出来：真机上出现过 6 条「cannot parse the first zstd frame」
                        // 却不知道说的是哪个文件，隔离项更是只显示一个空的 ⊘。
                        val path = v.optString("path").ifEmpty { v.optString("from") }
                        val to = v.optString("to")
                        val id = v.optString("id")
                        val cwd = v.optString("cwd")
                        val reason = v.optString("reason").ifEmpty { v.optString("action") }
                        if (path.isNotEmpty()) {
                            append(path)
                            if (to.isNotEmpty()) append(" → ").append(to)
                            append("  ")
                        }
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
            sessions = o.optInt("sessions", 0),
            groupedSessions = o.optInt("groupedSessions", 0),
            files = o.optInt("files", 0),
            ignoredNonSession = o.optJSONArray("ignoredNonSession")?.length() ?: 0,
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
