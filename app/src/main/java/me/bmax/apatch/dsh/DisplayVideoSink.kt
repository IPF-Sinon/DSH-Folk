package me.bmax.apatch.dsh

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import me.bmax.apatch.display.IDisplayVideoSink

/**
 * 视频回传的**宿主侧**：接住服务端推来的 H.264 帧，解码，直接画到 [SurfaceView] 的 Surface 上。
 *
 * ## 帧流里没有标记，只能自己认
 *
 * 服务端在 `INFO_OUTPUT_FORMAT_CHANGED` 时把 csd-0 / csd-1（SPS / PPS）**当作普通帧**
 * 逐个推过来，帧与帧之间没有任何标记区分「编解码器配置」与「媒体帧」。所以这里按 NAL 类型
 * 自己判断：一个帧里若只有 SPS(7) / PPS(8) / SEI(6) / AUD(9) 而没有任何 VCL（1–5），它就是
 * 配置帧，攒起来当 csd 用；否则是媒体帧。
 *
 * 为什么不用「头两帧一定是 SPS/PPS」这个更简单的规则：那个顺序依赖服务端在挂 sink 时补发
 * 配置（见 `Main.DisplaySession.configSps`），是一条隐含契约 —— 今天成立，但哪天服务端改了
 * 补发时机，客户端就会把一张真实画面当成配置吃掉，而且**不会报错**，只表现为画面卡一下。
 * 按 NAL 类型判断是自描述的，两边各自演进也不会互相坑。
 *
 * ## 线程
 *
 * `onVideoFrame` 是服务端进程发起的 Binder 调用，跑在 Binder 线程上，**必须立刻返回**：
 * 阻塞它等于阻塞服务端的编码线程。所以这里只做入队，真正的喂数据在 [HandlerThread] 上、
 * 由 MediaCodec 的异步回调驱动。
 */
class DisplayVideoSink(
    private val requestedWidth: Int,
    private val requestedHeight: Int,
    private val onError: (String) -> Unit,
    private val onSize: (Int, Int) -> Unit = { _, _ -> },
) : IDisplayVideoSink.Stub() {

    private companion object {
        const val TAG = "DshDisplayVideo"

        /**
         * 待解码帧的队列上限。
         *
         * 超了就**丢最旧的**：客户端解码跟不上时，攒着一堆旧帧只会让画面越拖越久，
         * 而直播场景下"最新的那一帧"才是唯一有价值的。丢帧本身是正常的，不打日志刷屏。
         */
        const val MAX_QUEUED = 4

        /** 统计行多久刷一次。用户说的"卡卡的"要能落到数字上（见 [lastStatsLine]）。 */
        const val STATS_MS = 2000L

        /** 一帧里有 VCL（真正的图像数据）时的 NAL 类型范围。 */
        const val VCL_MIN = 1
        const val VCL_MAX = 5
    }

    private val lock = Any()
    private val pending = ArrayDeque<ByteArray>()

    /**
     * 与 [pending] 一一对应的入队时刻（nanoTime）。
     *
     * 用来量"帧从进队列到喂进解码器等了多久" —— 解码跟不上时，这一段等待就是用户看到的
     * 迟滞，而且它比"丢了多少帧"更直接。两个队列必须同增同删（只有 addLast / removeFirst /
     * clear 三个地方，都在一起改）。
     */
    private val pendingStamps = ArrayDeque<Long>()
    private val freeInputs = ArrayDeque<Int>()

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var configured = false
    private var surface: Surface? = null
    private var decoder: MediaCodec? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    // ── 量化的计数 ───────────────────────────────────────────────────────────
    //
    // 全是"够用就好"的计数，不追求精确同步：写方在 Binder 线程（onVideoFrame）与解码线程
    // （onOutputBufferAvailable），读方在任意线程。@Volatile + 一个独立统计行，不共享状态机。
    @Volatile
    private var queuedFrames = 0L
    @Volatile
    private var droppedQueueFull = 0L
    @Volatile
    private var maxQueueDepth = 0
    @Volatile
    private var waitSumMs = 0L
    @Volatile
    private var waitCount = 0L
    private var statsAtMs = 0L
    private var statsQueued = 0L
    private var statsDecoded = 0L
    private var statsDropped = 0L

    /**
     * 最近一次统计行（每 [STATS_MS] 更新一次）。
     *
     * 界面直接读它显示：小窗展开时把这一行画在控制条下面，用户与我们都看到同一组数字 ——
     * "卡"就不再是一句感觉，而是"解码 12fps、队列峰值 4、平均等了 90ms"这种可判断的事实。
     */
    @Volatile
    var lastStatsLine: String = ""
        private set

    @Volatile
    private var released = false

    @Volatile
    private var decodedFrames = 0L

    /** 到目前为止真的解出过画面吗。界面用它区分「还没起来」与「真的没画面」。 */
    fun hasDecoded(): Boolean = decodedFrames > 0

    // ── 生命周期 ─────────────────────────────────────────────────────────────

    /**
     * 挂上渲染目标。可以晚于 [onVideoFrame] 调用（SurfaceView 的 surfaceCreated 总是迟一点），
     * 攒下的帧会在这里补喂。
     */
    fun attach(surface: Surface) {
        synchronized(lock) {
            if (released) return
            this.surface = surface
            // 换 Surface 必须重建解码器：configure 时就把输出目标绑定了，改不了
            releaseDecoderLocked()
            ensureDecoderLocked()
        }
        drain()
    }

    /** 释放解码器与线程。之后本对象不可再用。 */
    fun release() {
        synchronized(lock) {
            released = true
            releaseDecoderLocked()
            thread?.quitSafely()
            thread = null
            handler = null
            surface = null
            pending.clear()
            pendingStamps.clear()
            freeInputs.clear()
        }
    }

    /** 解码器必须先停再释放，且要吞掉异常：有些设备在出错后 stop 会再抛一次。 */
    private fun releaseDecoderLocked() {
        val codec = decoder ?: return
        decoder = null
        configured = false
        decodedFrames = 0
        // 统计基线跟着解码器一起对齐：否则重建后第一行会算出负数（decodedFrames 归零、
        // 基线还停在重建前）。attach() 目前每个 sink 只走一次，但别让这个坑留着。
        statsQueued = queuedFrames
        statsDecoded = decodedFrames
        statsDropped = droppedQueueFull
        runCatching { codec.stop() }.onFailure { Log.w(TAG, "stop 解码器失败: ${it.message}") }
        runCatching { codec.release() }.onFailure { Log.w(TAG, "release 解码器失败: ${it.message}") }
        synchronized(lock) { freeInputs.clear() }
    }

    // ── 服务端推来的帧 ───────────────────────────────────────────────────────

    override fun onVideoFrame(data: ByteArray?) {
        if (data == null || data.isEmpty() || released) return
        val isConfig = isConfigOnly(data)
        if (isConfig) {
            // 配置帧不进解码队列：单独一个 SPS / PPS 不是合法的「访问单元」，
            // 喂进输入队列在部分解码器上会直接报错。它该去的地方是 MediaFormat 的 csd。
            //
            // 一个例外：解码器已经配置好之后再来的配置帧只能当普通帧喂掉 —— 它通常就是
            // 关键帧内联的 SPS/PPS（服务端开了 PREPEND_HEADER_TO_SYNC_FRAMES），
            // 丢掉反而会让下一个关键帧解不出来。
            synchronized(lock) {
                if (!configured) {
                    if (sps == null) sps = data else if (pps == null) pps = data
                    ensureDecoderLocked()
                    return
                }
            }
        }
        synchronized(lock) {
            if (released) return
            while (pending.size >= MAX_QUEUED) {
                pending.removeFirst()
                pendingStamps.removeFirst()
                droppedQueueFull++
            }
            pending.addLast(data)
            pendingStamps.addLast(System.nanoTime())
            queuedFrames++
            if (pending.size > maxQueueDepth) maxQueueDepth = pending.size
        }
        drain()
        maybeLogStats()
    }

    /**
     * 每 [STATS_MS] 打一行可判断的数字，并存进 [lastStatsLine] 给界面用。
     *
     * 这一行是这次"卡顿"调查的**唯一依据**：没有它，任何优化都只是换个地方猜。
     * 三个数各指一类病因 —— 入队低＝服务端没在推；入队正常但解码低＝解码器跟不上（分辨率/码率）；
     * 丢帧多或平均等待高＝我们这边排队太久（该看队列策略）。
     */
    private fun maybeLogStats() {
        val now = System.currentTimeMillis()
        if (statsAtMs == 0L) {
            statsAtMs = now
            statsQueued = queuedFrames
            statsDecoded = decodedFrames
            statsDropped = droppedQueueFull
            return
        }
        val elapsed = now - statsAtMs
        if (elapsed < STATS_MS) return
        val secs = elapsed / 1000.0
        val dq = queuedFrames - statsQueued
        val dd = decodedFrames - statsDecoded
        val ddrop = droppedQueueFull - statsDropped
        val avgWait = if (waitCount > 0) waitSumMs / waitCount else 0
        val line = "入队 %.1ffps，解码 %.1ffps，丢帧 %d，队列峰值 %d，平均等待 %dms（累计丢 %d）"
            .format(dq / secs, dd / secs, ddrop, maxQueueDepth, avgWait, droppedQueueFull)
        lastStatsLine = line
        Log.i(TAG, "小窗视频：$line")
        statsAtMs = now
        statsQueued = queuedFrames
        statsDecoded = decodedFrames
        statsDropped = droppedQueueFull
        maxQueueDepth = 0
        waitSumMs = 0
        waitCount = 0
    }

    /**
     * 这一帧是不是纯配置（只有 SPS/PPS/SEI/AUD，没有任何图像数据）。
     *
     * 按 Annex-B 的起始码切分后看每个 NAL 的类型。没有起始码时按「整帧就是一个 NAL」处理 ——
     * 有些编码器的 csd 就是不带起始码的裸 NAL。
     */
    private fun isConfigOnly(data: ByteArray): Boolean {
        var sawNal = false
        var i = 0
        while (i + 3 < data.size) {
            val startLen = when {
                data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte() -> 3
                i + 4 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> 4
                else -> {
                    i++
                    continue
                }
            }
            val headerAt = i + startLen
            if (headerAt >= data.size) break
            val type = data[headerAt].toInt() and 0x1f
            sawNal = true
            if (type in VCL_MIN..VCL_MAX) return false
            i = headerAt + 1
        }
        // 一帧都没解析出起始码：当它是一整段裸 NAL，看首字节
        if (!sawNal) {
            val type = data[0].toInt() and 0x1f
            return type == 7 || type == 8 || type == 6 || type == 9
        }
        return true
    }

    /** 把攒到的帧喂给解码器的空闲输入缓冲。**不持锁调用 MediaCodec**，避免 Binder 线程被堵。 */
    private fun drain() {
        if (released) return
        val h = handler ?: return
        h.post { pump() }
    }

    private fun pump() {
        while (true) {
            val codec: MediaCodec
            val index: Int
            val frame: ByteArray
            synchronized(lock) {
                if (released) return
                codec = decoder ?: return
                if (!configured) return
                if (freeInputs.isEmpty() || pending.isEmpty()) return
                index = freeInputs.removeFirst()
                frame = pending.removeFirst()
                if (pendingStamps.isNotEmpty()) {
                    val waitedMs = (System.nanoTime() - pendingStamps.removeFirst()) / 1_000_000L
                    waitSumMs += waitedMs
                    waitCount++
                }
            }
            try {
                val buf = codec.getInputBuffer(index) ?: continue
                buf.clear()
                // 一帧必须整个装进输入缓冲；装不下说明缓冲比帧小，那是设备/格式层面的异常
                if (buf.capacity() < frame.size) {
                    Log.w(TAG, "输入缓冲 ${buf.capacity()} 装不下 ${frame.size} 字节的帧，丢弃")
                    codec.queueInputBuffer(index, 0, 0, 0, 0)
                    continue
                }
                buf.put(frame)
                codec.queueInputBuffer(index, 0, frame.size, System.nanoTime() / 1000, 0)
            } catch (t: Throwable) {
                // 正常释放（换 Surface / 退出预览）时 decoder 正在被释放，pump 撞上它抛异常是
                // 预期内的 —— 那种情况不该报给用户，否则每次关掉预览都会闪一条假错误。
                if (released) return
                Log.w(TAG, "喂帧给解码器失败: ${t.message}")
                onError("解码器喂数据失败：${t.message}")
                return
            }
        }
    }

    // ── 解码器 ───────────────────────────────────────────────────────────────

    /** 调用方必须已持有 [lock]。Surface 与线程都就绪、且还没配置时才真的建。 */
    private fun ensureDecoderLocked() {
        if (released || configured || decoder != null) return
        val target = surface ?: return

        val t = thread ?: HandlerThread("DshDisplayDecoder").also {
            it.start()
            thread = it
            handler = Handler(it.looper)
        }
        val h = handler ?: return

        try {
            val format = MediaFormat.createVideoFormat("video/avc", requestedWidth, requestedHeight)
            sps?.let { format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(it)) }
            pps?.let { format.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(it)) }

            val codec = MediaCodec.createDecoderByType("video/avc")
            codec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
                    synchronized(lock) {
                        if (!released) freeInputs.addLast(index)
                    }
                    pump()
                }

                override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    // 第二个参数 true = 渲染到 configure 时给的 Surface。info.size 为 0 的
                    // 情况（EOS / 纯配置输出）也要 release，否则缓冲会漏光。
                    runCatching { mc.releaseOutputBuffer(index, true) }
                        .onFailure { Log.w(TAG, "releaseOutputBuffer 失败: ${it.message}") }
                    if (info.size > 0) decodedFrames++
                }

                override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
                    // 编码器会把宽高对齐到 16，所以真实尺寸可能与请求的不同；界面上按它调比例，
                    // 否则画面会被拉伸。有 crop 时以 crop 为准。
                    val w = format.intOrNull("crop-right")?.let { it - (format.intOrNull("crop-left") ?: 0) + 1 }
                        ?: format.intOrNull(MediaFormat.KEY_WIDTH) ?: requestedWidth
                    val h = format.intOrNull("crop-bottom")?.let { it - (format.intOrNull("crop-top") ?: 0) + 1 }
                        ?: format.intOrNull(MediaFormat.KEY_HEIGHT) ?: requestedHeight
                    Log.i(TAG, "解码器输出格式 ${w}x$h")
                    onSize(w, h)
                }

                override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                    Log.w(TAG, "解码器报错: ${e.message}")
                    onError("解码器报错：${e.diagnosticInfo ?: e.message ?: "未知"}")
                }
            }, h)

            codec.configure(format, target, null, 0)
            codec.start()
            decoder = codec
            configured = true
            Log.i(TAG, "解码器已启动，csd-0=${sps?.size ?: 0}B csd-1=${pps?.size ?: 0}B")
        } catch (t2: Throwable) {
            Log.w(TAG, "创建解码器失败: ${t2.message}")
            onError("创建解码器失败：${t2.message}")
        }
    }
}

/** `MediaFormat.getInteger` 在键不存在时抛异常，而 crop 那几个键在多数设备上确实不存在。 */
internal fun MediaFormat.intOrNull(key: String): Int? =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null
