package me.bmax.apatch.dsh

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.RandomAccessFile

/**
 * 统一文本日志存取：内存环形缓冲（tail / 行数计数无需读盘）+ 批量落盘（低频 flush）。
 *
 * 原实现每次 append 都打开/关闭文件、tail 每次全量 readLines，在进程输出转发、
 * 下载进度、诊断轮询、启动日志轮询等高频路径上造成大量磁盘 I/O。本组件把高频读
 * 全部收敛到内存，磁盘只做低频批量写，显著降低 I/O 与 GC 压力。
 */
object LogStore {
    private const val MAX_MEM_LINES = 600
    private const val FLUSH_THRESHOLD = 32

    /**
     * 单个日志文件的体积上限。
     *
     * 超过就用内存里的最近 [MAX_MEM_LINES] 行整体重写 —— `dsh web` 是常驻进程，
     * 输出全量追加进同一个文件，只在启动/重装时 clearLog。跑几天的会话（或一个
     * 刷屏的插件）能把应用私有目录写满，而日志面板本来也只看尾部。
     */
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024

    private val logs = HashMap<String, NamedLog>()

    @Synchronized
    fun named(file: File): NamedLog =
        logs.getOrPut(file.absolutePath) { NamedLog(file) }

    /** 单个命名日志文件：内存环形 + 追加式落盘。所有公开方法线程安全。 */
    class NamedLog internal constructor(private val file: File) {
        private val mem = ArrayDeque<String>()
        private var memLines = 0

        /** 本进程内累计 append 行数（磁盘始终全量追加，故等于磁盘行数增长量）。 */
        private var totalAppended = 0L

        private var writer: BufferedWriter? = null
        private var pending = 0

        /** 进程内首次写入前统计的磁盘已有行数（重启后追加场景）；-1 表示未统计。 */
        private var baseDiskLines = -1L

        /** 已知的磁盘体积（含未 flush 的缓冲），用于判断是否该自截断。 */
        private var knownBytes = -1L

        @Synchronized
        fun append(line: String) {
            if (memLines < MAX_MEM_LINES) {
                mem.addLast(line)
                memLines++
            } else {
                mem.removeFirst()
                mem.addLast(line)
            }
            totalAppended++
            try {
                val w = writer()
                w.write(line)
                w.newLine()
                pending++
                if (knownBytes >= 0) knownBytes += line.toByteArray().size + 1
                if (pending >= FLUSH_THRESHOLD) {
                    w.flush()
                    pending = 0
                }
                if (knownBytes > MAX_FILE_BYTES) trimToMemory()
            } catch (_: Exception) {
                // 写盘失败不阻塞调用方（内存缓冲仍可读）
            }
        }

        /**
         * 用内存里的最近若干行整体重写文件，把体积压回可控范围。
         *
         * 直接截断文件头是不行的：writer 持有的是追加句柄，改文件长度会让后续写入
         * 落在错位的偏移上。所以关旧句柄、整体重写、下次 append 自然重开。
         */
        private fun trimToMemory() {
            val keep = mem.joinToString("\n")
            runCatching { writer?.close() }
            writer = null
            pending = 0
            val ok = runCatching {
                file.parentFile?.mkdirs()
                file.writeText(if (keep.isEmpty()) "" else keep + "\n")
                true
            }.getOrDefault(false)
            if (ok) {
                // 磁盘现在只剩内存这份，行数基线随之重置
                baseDiskLines = memLines.toLong()
                totalAppended = 0L
                knownBytes = file.length()
            } else {
                knownBytes = -1L
            }
        }

        /** 取最近 n 行（优先内存，避免全量读盘）。读内存不触发落盘（主线程安全）。 */
        @Synchronized
        fun tail(lines: Int): String {
            if (memLines > 0) {
                val n = minOf(lines, memLines)
                val list = ArrayList<String>(n)
                val it = mem.iterator()
                val skip = memLines - n
                var i = 0
                while (it.hasNext()) {
                    val l = it.next()
                    if (i >= skip) list.add(l)
                    i++
                }
                return list.joinToString("\n")
            }
            if (baseDiskLines < 0) baseDiskLines = countDiskLines()
            return readTailFromDisk(lines)
        }

        /** 当前总行数（磁盘全量计数），供诊断轮询免读盘。 */
        @Synchronized
        fun count(): Int {
            if (baseDiskLines < 0) baseDiskLines = countDiskLines()
            return (baseDiskLines + totalAppended).toInt()
        }

        /** 清空日志（内存 + 磁盘）。关闭旧 writer 避免文件截断后指针错位。 */
        @Synchronized
        fun clear() {
            flushPending()
            runCatching { writer?.close() }
            writer = null
            pending = 0
            mem.clear()
            memLines = 0
            totalAppended = 0L
            baseDiskLines = 0L
            knownBytes = 0L
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText("")
            }
        }

        /** 关闭底层句柄（应用销毁时调用）。 */
        @Synchronized
        fun close() {
            runCatching { writer?.close() }
            writer = null
            pending = 0
        }

        /** 进程输出流结束时把积压内容落盘（不关闭句柄，后续 append 可继续）。 */
        @Synchronized
        fun flushForExit() {
            flushPending()
        }

        private fun writer(): BufferedWriter {
            val w = writer
            if (w != null) return w
            file.parentFile?.mkdirs()
            if (knownBytes < 0) knownBytes = runCatching { file.length() }.getOrDefault(0L)
            return BufferedWriter(FileWriter(file, true)).also { writer = it }
        }

        private fun flushPending() {
            if (pending > 0) {
                runCatching { writer?.flush() }
                pending = 0
            }
        }

        private fun countDiskLines(): Long = runCatching {
            if (!file.exists()) return@runCatching 0L
            // 逐块数换行符：readLines() 会为每一行分配一个 String，2MB 日志会上万次分配
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                var lines = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    for (i in 0 until n) if (buf[i] == '\n'.code.toByte()) lines++
                }
                lines
            }
        }.getOrDefault(0L)

        /** RandomAccessFile 从文件尾读取最后 n 行（进程重启后内存为空时兜底）。 */
        private fun readTailFromDisk(lines: Int): String = runCatching {
            if (!file.exists()) return@runCatching ""
            val raf = RandomAccessFile(file, "r")
            try {
                val len = raf.length()
                if (len == 0L) return@runCatching ""
                val chunk = 8192L
                val start = maxOf(0L, len - chunk)
                raf.seek(start)
                val bytes = ByteArray((len - start).toInt())
                raf.readFully(bytes)
                var text = String(bytes, Charsets.UTF_8)
                // 截断处可能落在某行中间：去掉不完整首行
                if (start > 0) {
                    val nl = text.indexOf('\n')
                    if (nl >= 0) text = text.substring(nl + 1)
                }
                text.trimEnd('\n').split('\n').takeLast(lines).joinToString("\n")
            } finally {
                raf.close()
            }
        }.getOrDefault("")
    }
}
