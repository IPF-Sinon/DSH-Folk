package me.bmax.apatch.dsh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import me.bmax.apatch.display.DisplayBinderContainer
import me.bmax.apatch.display.IDisplayService
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 虚拟屏服务端的**宿主侧**：把 dex jar 送到设备上、以特权身份把它拉起来、拿回 Binder，
 * 并在会话期间维持它的存活。
 *
 * ## 为什么服务端必须是一个独立进程
 *
 * 创建带 `SUPPORTS_TOUCH` 的 `TRUSTED` 虚拟屏、以及调用隐藏的
 * `InputManager.injectInputEvent`，都要 `INJECT_EVENTS` 这类系统权限。本 App 是普通应用，
 * 拿不到。所以服务端走 `app_process`：它以 uid 0（root）或 2000（shell）运行，再用 Binder
 * 把能力交回本进程。三条提权通道（root / Shizuku / 无线 ADB）都能给出这样一个设备侧 shell。
 *
 * ## Binder 交接：定向广播 + 一次性随机 token
 *
 * 服务端起好后不能像系统服务那样注册到 servicemanager（那需要额外权限），它用一条
 * **定向广播**把 IBinder 送回来。广播是跨 uid 送的，所以本进程的接收器不能声明成
 * not-exported —— 任何应用都能往它发一条携带自己 IBinder 的假广播。因此每次启动都生成
 * 一个一次性随机 token，经 argv 传给服务端、又放进广播，本进程只认 token 对上的那一条。
 * 见 [DisplayServer.EXTRA_BINDER_TOKEN] 与服务端 `Main.EXTRA_BINDER_TOKEN` 的注释。
 *
 * ## 存活
 *
 * 服务端带看门狗：没有视频 sink、又 15 秒没有客户端活动就自杀（避免留下孤儿 root 进程）。
 * 这对 agent 的用法（截图 → 思考 → 点击）太短，于是协议里加了无副作用的 `ping()`，
 * 本类在会话期间每 [PING_INTERVAL_MS] 打一次。效果是：App 一死或会话一结束，服务端在
 * 15 秒内自己回收。
 */
object DisplayServer {

    private const val TAG = "DshDisplayServer"

    const val ACTION_BINDER_READY = "me.bmax.apatch.action.DISPLAY_BINDER_READY"
    const val EXTRA_BINDER_CONTAINER = "binder_container"
    const val EXTRA_BINDER_TOKEN = "binder_token"

    /** 构建产物：由 app/build.gradle.kts 的 buildDisplayServerJar 生成并注册为 assets 源目录。 */
    private const val JAR_ASSET = "dsh-display-server.jar"
    private const val JAR_PATH = "/data/local/tmp/dsh-display-server.jar"

    /**
     * `app_process` 的入口类。改动它必须同步 gate，因为服务端 jar 里就是这个名字。
     */
    private const val ENTRY_CLASS = "me.bmax.apatch.display.Main"

    /**
     * 用于 `pkill`/`pgrep` 的进程标识。
     *
     * `[m]` 这个括号是必须的：命令自己（`sh -c "pkill -f …"`）的 cmdline 里含有同一串文字，
     * 不加括号时 pkill 会把承载自己的那个 shell 一并匹配上，于是在杀掉服务端的同时
     * 把自己的命令也杀了 —— 结果看起来像"什么都没发生"。
     */
    private const val PROC_PATTERN = "[m]e.bmax.apatch.display.Main"

    /**
     * 推送 jar 时的分块大小（base64 字符数）。
     *
     * 不一次性灌 35KB：这条命令要穿过 libsu 的管道、Shizuku 的 user service、或
     * `adb-shell.py` → adbd 三层之一，每一层对单条命令长度都有自己的脾气。分块后每条都很短，
     * 而且失败时能定位到第几块。
     */
    private const val PUSH_CHUNK = 6_000

    private const val PING_INTERVAL_MS = 10_000L

    /** 等服务端把 Binder 送回来的上限。它要 prepareMainLooper + Workarounds + 建 DisplayManager。 */
    private const val HANDSHAKE_TIMEOUT_MS = 20_000L

    private val lock = Any()

    @Volatile
    private var service: IDisplayService? = null

    @Volatile
    private var pingThread: Thread? = null

    private var receiver: BroadcastReceiver? = null

    /** 当前可用的服务端；null 表示没起来或已经死了。 */
    fun current(): IDisplayService? = service

    fun isRunning(): Boolean = service != null

    // ── 启动 ─────────────────────────────────────────────────────────────────

    /**
     * 确保服务端在跑并返回它的 Binder。已经跑着就直接返回（幂等）。
     *
     * **阻塞**：由调用它的桥线程承担（桥本来就是每请求一个线程）。调用方负责先做
     * 风险门禁 —— 本类内部对每条特权命令只做 [PrivilegedShell.denyReason] 这一层兜底。
     *
     * @return 成功时返回 [IDisplayService]；失败时返回 [Result.failure]，异常 message 是
     *         可直接展示给用户的短句（不是堆栈）。
     */
    fun start(ctx: Context): Result<IDisplayService> {
        service?.let { return Result.success(it) }
        synchronized(lock) {
            service?.let { return Result.success(it) }

            val bytes = try {
                ctx.assets.open(JAR_ASSET).use { it.readBytes() }
            } catch (e: Throwable) {
                return Result.failure(DisplayError("读不到内置的 $JAR_ASSET：${e.message}"))
            }
            if (bytes.size < 1024) {
                // 构建产物缺失时 assets 里可能是 0 字节的占位；这里明确区分"没编出来"与"推送失败"
                return Result.failure(DisplayError("内置的 $JAR_ASSET 只有 ${bytes.size} 字节，像是没被正确构建"))
            }

            pushJar(ctx, bytes)?.let { note -> return Result.failure(DisplayError(note)) }

            val freshToken = randomToken()
            val latch = java.util.concurrent.CountDownLatch(1)
            val arrived = java.util.concurrent.atomic.AtomicReference<IBinder?>(null)
            val receiverRegistered = registerReceiver(ctx, freshToken, latch, arrived)
            if (!receiverRegistered) {
                return Result.failure(DisplayError("注册 Binder 交接广播失败"))
            }

            try {
                val pkg = ctx.packageName
                val launch = launchCommand(pkg, freshToken)
                val outcome = privileged(ctx, launch, asRoot = false, timeoutMs = 15_000L)
                    ?: return Result.failure(DisplayError("特权通道不可用，无法启动虚拟屏服务端"))
                if (outcome.note != null) {
                    return Result.failure(DisplayError("启动命令没跑成：${noteText(outcome.note)}"))
                }
                // 启动是后台化的，所以这里不该有非零退出码；有就说明 sh 自己就失败了
                if (outcome.exit != 0) {
                    val why = outcome.stderr.ifBlank { outcome.stdout }.trim().take(200)
                    return Result.failure(DisplayError("启动命令退出码 ${outcome.exit}${if (why.isEmpty()) "" else "：$why"}"))
                }

                if (!latch.await(HANDSHAKE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    // 没等到广播：区分"进程根本没起来"与"起来了但没送回来"，这两件事的下一步完全不同
                    val alive = processAlive(ctx)
                    return Result.failure(
                        DisplayError(
                            if (alive) {
                                "服务端进程起来了，但 ${HANDSHAKE_TIMEOUT_MS / 1000} 秒内没有送回 Binder" +
                                    "（多半是它初始化中途挂了，日志见 $LOG_PATH）"
                            } else {
                                "服务端进程没起来。常见原因：当前提权通道拿不到 uid 0/2000，" +
                                    "或设备的 app_process 不接受这种调用方式"
                            },
                        ),
                    )
                }
                val binder = arrived.get() ?: return Result.failure(DisplayError("交接广播到了，但没有携带 Binder"))
                val svc = IDisplayService.Stub.asInterface(binder)
                    ?: return Result.failure(DisplayError("无法把交接来的 Binder 转成 IDisplayService"))
                service = svc
                startPingLoop(svc)
                return Result.success(svc)
            } finally {
                unregisterReceiver(ctx)
            }
        }
    }

    /** 服务端自己写的日志；排查"起来了但没送回来"时先看这里。 */
    const val LOG_PATH = "/data/local/tmp/dsh-display.log"

    private fun registerReceiver(
        ctx: Context,
        expectedToken: String,
        latch: java.util.concurrent.CountDownLatch,
        arrived: java.util.concurrent.atomic.AtomicReference<IBinder?>,
    ): Boolean {
        // token 通过闭包捕获，不需要额外字段
        val rx = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                val got = intent.getStringExtra(EXTRA_BINDER_TOKEN)
                if (got == null || got != expectedToken) {
                    // 伪造或过期的交接。记一笔但**不**结束等待：真的那条可能还在路上。
                    Log.w(TAG, "忽略 token 不匹配的交接广播")
                    return
                }
                // 跨进程来的 Parcelable 必须显式指定 classloader，否则系统会用框架的
                // classloader 去还原 DisplayBinderContainer，直接 ClassNotFoundException。
                intent.extras?.classLoader = DisplayBinderContainer::class.java.classLoader
                val container = parcelableContainer(intent)
                if (container == null) {
                    Log.w(TAG, "交接广播里没有 DisplayBinderContainer")
                    return
                }
                val b = container.binder
                if (b == null) {
                    Log.w(TAG, "交接广播里的 binder 是空的")
                    return
                }
                if (arrived.compareAndSet(null, b)) latch.countDown()
            }
        }
        receiver = rx
        return try {
            val filter = IntentFilter(ACTION_BINDER_READY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // 必须是 EXPORTED：发送方是 root/shell，跨 uid。安全性由 token 承担。
                ctx.registerReceiver(rx, filter, Context.RECEIVER_EXPORTED)
            } else {
                ctx.registerReceiver(rx, filter)
            }
            true
        } catch (e: Throwable) {
            Log.w(TAG, "注册交接广播失败: ${e.message}")
            receiver = null
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun parcelableContainer(intent: Intent): DisplayBinderContainer? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_BINDER_CONTAINER, DisplayBinderContainer::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_BINDER_CONTAINER) as? DisplayBinderContainer
        }
    } catch (e: Throwable) {
        Log.w(TAG, "还原 DisplayBinderContainer 失败: ${e.message}")
        null
    }

    private fun unregisterReceiver(ctx: Context) {
        receiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        receiver = null
    }

    /**
     * 启动命令。
     *
     * 两个细节都不能省：
     *
     * - **`setsid`**：无线 ADB 通道下这条命令是经 `adb shell` 跑的，会话一结束 adbd 会清掉
     *   该会话的进程组，后台化的 `app_process` 会跟着被杀（经典坑）。`setsid` 把它挪进新会话
     *   就躲开了。设备上不一定有 `setsid`（toybox 的裁剪因厂商而异），所以先探测再决定，
     *   而不是假定它在。
     * - **三个重定向**：把 stdout/stderr 接到 /dev/null、stdin 接到 /dev/null，否则
     *   `libsu` 的管道会等着这个后台子进程关闭 fd，`exec` 就一直不返回（它是阻塞调用，
     *   而整个特权执行是单飞的）。
     */
    private fun launchCommand(pkg: String, token: String): String {
        val entry = "$ENTRY_CLASS ${shq(pkg)} ${shq(token)}"
        return buildString {
            append("if command -v setsid >/dev/null 2>&1; then PRE=\"setsid \"; else PRE=\"\"; fi; ")
            append("CLASSPATH=").append(shq(JAR_PATH)).append(' ')
            append("\$PRE app_process / ").append(entry)
            append(" >/dev/null 2>&1 </dev/null &")
        }
    }

    // ── 推送 jar ─────────────────────────────────────────────────────────────

    /**
     * 把 jar 送到设备上。
     *
     * 先比对 sha256：一致就不重推（重启服务端是常事，26KB 传三遍没必要）。不一致或读不到远端
     * 哈希时，按 [PUSH_CHUNK] 分块 base64 写过去，最后 `base64 -d` 还原**并再次校验 sha256** ——
     * 分块传输最典型的失败是静默截断，而一个截断的 dex jar 会让 ART 报一句和真因无关的
     * 解析错误，所以这里必须自己验。
     *
     * @return null 表示成功；否则是一句可直接展示的失败原因
     */
    private fun pushJar(ctx: Context, bytes: ByteArray): String? {
        val want = sha256Hex(bytes)
        remoteSha256(ctx, JAR_PATH)?.let { have ->
            if (have.equals(want, ignoreCase = true)) {
                Log.i(TAG, "jar 已在设备上且哈希一致，跳过推送")
                return null
            }
        }

        val tmp = "$JAR_PATH.b64"
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val chunks = b64.chunked(PUSH_CHUNK)
        chunks.forEachIndexed { i, chunk ->
            val cmd = if (i == 0) {
                "printf '%s' ${shq(chunk)} > ${shq(tmp)}"
            } else {
                "printf '%s' ${shq(chunk)} >> ${shq(tmp)}"
            }
            val out = privileged(ctx, cmd, asRoot = false, timeoutMs = 20_000L)
                ?: return "特权通道不可用，无法推送虚拟屏服务端"
            if (out.note != null) return "推送服务端第 ${i + 1}/${chunks.size} 块失败：${noteText(out.note)}"
            if (out.exit != 0) {
                val why = out.stderr.ifBlank { out.stdout }.trim().take(200)
                return "推送服务端第 ${i + 1}/${chunks.size} 块失败：退出码 ${out.exit}${if (why.isEmpty()) "" else "（$why）"}"
            }
        }

        val decode = "base64 -d ${shq(tmp)} > ${shq(JAR_PATH)} && rm -f ${shq(tmp)}"
        val dec = privileged(ctx, decode, asRoot = false, timeoutMs = 20_000L)
            ?: return "特权通道不可用，无法还原虚拟屏服务端"
        if (dec.note != null) return "还原服务端失败：${noteText(dec.note)}"
        if (dec.exit != 0) {
            val why = dec.stderr.ifBlank { dec.stdout }.trim().take(200)
            return "还原服务端失败：退出码 ${dec.exit}（设备上可能没有 base64）${if (why.isEmpty()) "" else "：$why"}"
        }

        val have = remoteSha256(ctx, JAR_PATH)
            ?: return "推送后读不到服务端哈希，无法确认它完整"
        if (!have.equals(want, ignoreCase = true)) {
            return "服务端推送后哈希不一致（期望 ${want.take(12)}…，实际 ${have.take(12)}…），传输被截断了"
        }
        return null
    }

    /** `sha256sum` 在 toybox 里有；输出形如 `<hash>  <path>`。 */
    private fun remoteSha256(ctx: Context, path: String): String? {
        val out = privileged(ctx, "sha256sum ${shq(path)} 2>/dev/null", asRoot = false, timeoutMs = 15_000L) ?: return null
        if (out.note != null || out.exit != 0) return null
        return Regex("^([0-9a-fA-F]{64})").find(out.stdout.trim())?.groupValues?.get(1)
    }

    /** 十六进制小写。显式 `and 0xff`：`%02x` 直接喂 Byte 在 Java 里是有符号的，一个字节的
     *  差别就能让哈希比对永远不相等 —— 而这里的比对正是用来判断 jar 有没有被截断的。 */
    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    // ── 停止 ─────────────────────────────────────────────────────────────────

    /**
     * 停掉服务端。
     *
     * 其实不打这一枪它也会在 15 秒内因空闲自杀（见类注释），但"用户按了停止"应当立刻生效。
     * 命令里的 `[m]` 括号见 [PROC_PATTERN] 的注释 —— 不加它会连承载命令的 shell 一起杀掉。
     */
    fun stop(ctx: Context) {
        synchronized(lock) {
            stopPingLoop()
            service = null
            val outcome = privileged(
                ctx,
                "pkill -f ${shq(PROC_PATTERN)} 2>/dev/null; true",
                asRoot = false,
                timeoutMs = 15_000L,
            )
            if (outcome?.note != null) Log.w(TAG, "停止服务端时通道不可用：${outcome.note}")
            unregisterReceiver(ctx)
        }
    }

    private fun processAlive(ctx: Context): Boolean {
        val out = privileged(ctx, "pgrep -f ${shq(PROC_PATTERN)} 2>/dev/null", asRoot = false, timeoutMs = 15_000L)
        if (out == null || out.note != null) return false
        return out.stdout.trim().lineSequence().any { it.trim().toIntOrNull() != null }
    }

    // ── 心跳 ─────────────────────────────────────────────────────────────────

    private fun startPingLoop(svc: IDisplayService) {
        stopPingLoop()
        // 刻意不用 return@Thread：那是"传给构造函数的 lambda 的隐式标签"，名字取决于是不是
        // 被当成 Thread 这个构造函数，脆；用 break 表达"退出循环"没有歧义。
        val t = Thread({
            while (true) {
                val interrupted = try {
                    Thread.sleep(PING_INTERVAL_MS)
                    false
                } catch (e: InterruptedException) {
                    true
                }
                // 心跳只是在推进服务端的空闲计时器；服务端已被换掉/已死时直接退出去
                if (interrupted || service !== svc) break
                val alive = try {
                    svc.ping()
                    true
                } catch (e: Throwable) {
                    Log.i(TAG, "心跳失败，视为服务端已退出：${e.message}")
                    false
                }
                if (!alive) {
                    synchronized(lock) {
                        if (service === svc) service = null
                    }
                    break
                }
            }
        }, "DshDisplayPing")
        t.isDaemon = true
        pingThread = t
        t.start()
    }

    private fun stopPingLoop() {
        pingThread?.interrupt()
        pingThread = null
    }

    // ── 特权执行 ─────────────────────────────────────────────────────────────

    /**
     * 走一遍既有的特权执行约定（见 `DshNativeBridge.shellExec`）：风险判定 → 拒绝判定 →
     * 单飞 → 执行 → 释放。
     *
     * 返回 null **只**表示通道不可用（`no_channel` / `channel_lost`）—— 那种情况要处理的是
     * "去设置里刷新权限"，不是重试命令。被策略拒绝与忙都如实返回，由调用方用 [noteText]
     * 翻译成准确的话：把"被策略拒绝"说成"通道不可用"会把人引到错误的方向。
     */
    private fun privileged(
        ctx: Context,
        command: String,
        asRoot: Boolean,
        timeoutMs: Long,
    ): PrivilegedShell.ExecOutcome? {
        val risk = PrivilegedShell.riskOf(command)
        PrivilegedShell.denyReason(ctx, risk, asRoot)?.let { deny ->
            Log.w(TAG, "特权命令被策略拒绝：$deny")
            return PrivilegedShell.ExecOutcome(-1, "", "", false, "denied:$deny")
        }
        if (!PrivilegedShell.tryEnter()) {
            return PrivilegedShell.ExecOutcome(-1, "", "", false, "busy")
        }
        val outcome = try {
            PrivilegedShell.exec(ctx, command, asRoot, timeoutMs)
        } finally {
            PrivilegedShell.exit()
        }
        if (outcome.note == "no_channel" || outcome.note == "channel_lost") return null
        return outcome
    }

    /** 把 [PrivilegedShell.ExecOutcome.note] 翻成一句能直接给用户看的话。 */
    private fun noteText(note: String): String = when {
        note.startsWith("denied:") -> "被权限策略拒绝（${note.removePrefix("denied:")}）"
        note == "busy" -> "另一条特权命令正在执行，稍后再试"
        note == "timeout" -> "特权命令超时"
        else -> note
    }

    // ── 工具 ─────────────────────────────────────────────────────────────────

    private fun randomToken(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** 单引号包裹，供 `sh` 使用。命令里会嵌入 base64 与 token，必须转义。 */
    private fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** 服务端没起来时抛出的短句错误（message 直接可展示）。 */
    class DisplayError(message: String) : Exception(message)
}
