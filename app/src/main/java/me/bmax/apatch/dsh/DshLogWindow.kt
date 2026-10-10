package me.bmax.apatch.dsh

import java.io.File

/**
 * 应用日志的时间窗口裁切（bugreport 里的「时间窗口」用它）。
 *
 * ## 为什么单独一块
 *
 * 窗口是**用户选的**（10 分钟 / 30 分钟 / 1 小时 / 12 小时 / 全部，见 `LogWindow`），而应用的
 * 日志是**多份文件**：当前那份 `dsh-web.log` 与起服务时轮转出去的 `dsh-web.prev.log`。原来的
 * 归档是各读各的 `tail(2000)` 行 —— 那是**按行数**裁，换过文件或重启过的时候，窗口的起点落在
 * 哪一份上完全看不出来，而且缓冲区里还没落盘的那几十行根本不在文件里。
 *
 * 这里做三件事：
 *
 * 1. **跨文件**：把 [files] 全部读进来，按行首时间戳**合并排序**（同一毫秒内保持读取顺序），
 *    所以窗口的边界不会被文件边界切断。
 * 2. **严格按时间比较**：起点 = `now − minutes`，终点 = **最新一条日志的时间戳**（不是"此刻"，
 *    日志停了很久时短窗口仍能给出最后一段活动，而不是一片空白）。两端都是闭区间，毫秒级。
 * 3. **不吞掉现场**：读文件前先把 [LogStore] 里还没落盘的行刷出去 —— 窗口的终点是"最新一条
 *    日志"，漏掉缓冲区里那几十行就等于漏掉最现场的那一段。
 *
 * ## 时间戳与边界（实话实说）
 *
 * - 时间戳由**写入方** [LogStore.append] 落在行首（本地时区的 `uuuu-MM-dd HH:mm:ss.SSS`），
 *   读写用的是同一个格式、同一个时区，且由 [LogStore.parseStamp] **严格**解析（畸形前缀不再被
 *   lenient 地滚成某个合法日期）；解析返回的是 epoch 毫秒，比较因此与夏令时无关。
 * - **没有时间戳的行**（这个改动上线前写下的旧日志）**直接丢弃**：它们参加不了"严格比较"，
 *   留着就等于在一个按时间排序的归档里混进一段时间未知的内容。丢了多少如实记在
 *   [Result.untimed] 里、由调用方写进报告说明。
 * - **`minutes <= 0`（「全部」）** = 不做时间窗口裁剪，但**没有时间戳的行照样丢** ——
 *   「全部」说的是时间范围，不是"什么行都收"。
 * - 用的是**写入时的系统时间**：用户改过设备时间/时区时，旧行的语义会跟着变（本地时间格式），
 *   与 logcat 那条按行首解析的路径同一个前提。
 */
internal object DshLogWindow {

    /**
     * 裁切结果。
     *
     * @param text 合并排序 + 裁切之后的正文（行之间用 `\n`）。
     * @param total 读到的日志行总数（所有文件，含被丢弃的无时间戳行）。
     * @param dropped 因超出时间窗口被丢掉的行数（`minutes <= 0` 时为 0）。
     * @param untimed 没有可解析时间戳而被丢弃的行数（见类 KDoc）。
     */
    data class Result(
        val text: String,
        val total: Int,
        val dropped: Int,
        val untimed: Int,
    )

    /**
     * 起点 = now − minutes；[minutes] <= 0（「全部」）时不做窗口裁剪，但仍然丢弃无时间戳的行。
     */
    fun read(
        files: List<File>,
        minutes: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): Result {
        val entries = ArrayList<Pair<Long, String>>()
        var total = 0
        var untimed = 0
        for (file in files) {
            // 内存里还没落盘的行先刷出去（见类 KDoc 第 3 点）
            runCatching { LogStore.named(file).flushForExit() }
            val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
            for (line in lines) {
                total++
                val ts = LogStore.parseStamp(line)
                if (ts == null) {
                    // 严格按时间：解析不出时间戳的行直接丢，只计数（见类 KDoc）
                    untimed++
                } else {
                    entries.add(ts to line)
                }
            }
        }
        // 稳定排序：同一时间戳的行保持原来的读取顺序
        val sorted = entries.sortedBy { it.first }
        if (minutes <= 0) {
            // 「全部」= 不裁时间窗口，但无时间戳的行已经在上面的循环里丢掉了
            return Result(sorted.joinToString("\n") { it.second }, total, 0, untimed)
        }
        val since = nowMillis - minutes * 60_000L
        val until = sorted.lastOrNull()?.first ?: nowMillis
        val kept = sorted.filter { it.first in since..until }
        return Result(
            text = kept.joinToString("\n") { it.second },
            total = total,
            dropped = sorted.size - kept.size,
            untimed = untimed,
        )
    }
}
