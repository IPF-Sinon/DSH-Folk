package me.bmax.apatch.dsh

import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * 容器里的一个真 PTY 会话：把 proot/proroot + bash 挂在伪终端上，让 vim / htop / tmux 能跑。
 *
 * 为什么必须是 PTY：把 bash 的 stdout 塞进一个 Text 里没有 `TERM`、没有行编辑、没有光标
 * 定位，任何 TUI 程序要么直接报 not a tty，要么画一屏乱码。
 *
 * PTY 与终端模拟没有自己写：用 Termux 官方拆出的 terminal-emulator / terminal-view
 * （**Apache-2.0**，termux-app 整体 GPLv3 但这两个子模块不是），aar 自带
 * `jni/arm64-v8a/libtermux.so`，里面就是 openpty + fork + execvp。
 *
 * 启动参数只有一个来源：[DshRuntime.ptyArgv] / [DshRuntime.ptyEnv] —— 与 execRootfs
 * 共用同一份构造逻辑，各写一份必然漏（少个 PROOT_LOADER 就直接起不来）。
 *
 * 这个类只管会话与回调转发，不碰任何 View：UI 侧实现 [Listener] 即可，不必去实现
 * TerminalSessionClient 那十几个方法。
 */
class DshPtySession private constructor(listener: Listener) : TerminalSessionClient {

    /**
     * 当前的回调宿主。**必须可换**：会话跨页面存活，每次回到终端页都是一个新的
     * TerminalView，而复用的会话若还指着上一个 View 的回调，新 View 就永远不重绘
     * （表现为「切走再回来终端不动了」）。
     */
    @Volatile
    private var listener: Listener = listener

    /** UI 侧只关心这几件事。回调都在 PTY 读线程上来，实现方自己切主线程。 */
    interface Listener {
        /** 屏幕内容变了 → 该重绘。 */
        fun onOutput()

        /** 标题变了（PS1 与 tmux 都会改）。 */
        fun onTitle(title: String)

        /** 会话结束（exit / 进程被杀）。 */
        fun onExit(status: Int)

        /** 终端要求把选中内容放进剪贴板。 */
        fun onCopy(text: String)

        /** 终端要求粘贴。 */
        fun onPasteRequest()

        /** 响铃：手机上震一下比响一声合适。 */
        fun onBell()
    }

    @Volatile
    var session: TerminalSession? = null
        private set

    fun write(s: String?) {
        if (s.isNullOrEmpty()) return
        session?.write(s)
    }

    fun resize(cols: Int, rows: Int) {
        session?.updateSize(maxOf(4, cols), maxOf(2, rows))
    }

    fun isRunning(): Boolean = session?.isRunning == true

    /** 结束会话，别在容器里留一个孤儿 bash。 */
    fun finish() {
        runCatching { session?.finishIfRunning() }
    }

    /**
     * 原地重启用：收掉旧会话但把监听器换成空实现，避免 `onSessionFinished` 把标题
     * 刷成「会话已结束（退出码 …）」—— 新会话马上接管，用户看到的应是新标题。
     */
    fun finishQuietly() {
        listener = object : Listener {
            override fun onOutput() {}
            override fun onTitle(title: String) {}
            override fun onExit(status: Int) {}
            override fun onCopy(text: String) {}
            override fun onPasteRequest() {}
            override fun onBell() {}
        }
        runCatching { session?.finishIfRunning() }
    }

    // ==================== TerminalSessionClient ====================

    override fun onTextChanged(changedSession: TerminalSession) = listener.onOutput()

    override fun onTitleChanged(changedSession: TerminalSession) =
        listener.onTitle(changedSession.title ?: "")

    override fun onSessionFinished(finishedSession: TerminalSession) {
        // 会话已死，别让单例继续指着它：否则下次进终端页 attachOrStart 拿到的仍是
        // 这个已退出的对象（isRunning 为 false 才会重开，但引用会一直留着不放）
        if (current === this) current = null
        listener.onExit(finishedSession.exitStatus)
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) =
        listener.onCopy(text ?: "")

    override fun onPasteTextFromClipboard(session: TerminalSession?) = listener.onPasteRequest()

    override fun onBell(session: TerminalSession) = listener.onBell()

    override fun onColorsChanged(session: TerminalSession) = listener.onOutput()

    override fun onTerminalCursorStateChange(state: Boolean) {
        // 光标闪烁重绘由 TerminalView 自己安排
    }

    override fun getTerminalCursorStyle(): Int? = null   // null = 用库默认（块状光标）

    // ---- 日志：库里打得很细，统一收口到 logcat ----

    override fun logError(tag: String?, message: String?) { Log.e(TAG, "$tag: $message") }
    override fun logWarn(tag: String?, message: String?) { Log.w(TAG, "$tag: $message") }
    override fun logInfo(tag: String?, message: String?) { Log.i(TAG, "$tag: $message") }
    override fun logDebug(tag: String?, message: String?) { Log.d(TAG, "$tag: $message") }

    override fun logVerbose(tag: String?, message: String?) {
        // Verbose 是逐字节读写，开着会把 logcat 冲垮
    }

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.w(TAG, "$tag: $message", e)
    }

    override fun logStackTrace(tag: String?, e: Exception?) { Log.w(TAG, tag, e) }

    companion object {
        private const val TAG = "DSH-Folk-pty"

        /** 回滚缓冲行数：够往上翻几屏，又不至于吃太多内存。 */
        private const val TRANSCRIPT_ROWS = 2000

        /** 跨页面存活的单例会话：切走再回来历史与正在跑的程序都还在。 */
        @Volatile
        private var current: DshPtySession? = null

        /** 已有活会话就复用（并把回调重新指向新宿主），否则新起一个。 */
        fun attachOrStart(cols: Int, rows: Int, listener: Listener): DshPtySession {
            current?.takeIf { it.isRunning() }?.let {
                it.listener = listener
                return it
            }
            val ps = DshPtySession(listener)
            val argv = DshRuntime.ptyArgv()
            val env = DshRuntime.ptyEnv()
            // args 就是 argv（含 argv[0]）：Termux 的 termux.c 原样 execvp，不做加工
            val s = TerminalSession(argv[0], "/", argv, env, TRANSCRIPT_ROWS, ps)
            ps.session = s
            // initializeEmulator 才真正 fork 出子进程，尺寸必须在这之前定好
            s.initializeEmulator(maxOf(4, cols), maxOf(2, rows))
            current = ps
            return ps
        }

        fun currentSession(): DshPtySession? = current

        /** App 退出时收掉会话。 */
        fun shutdown() {
            val s = current
            current = null
            runCatching { s?.finish() }
        }

        /** 原地重启：清掉单例引用并安静收尾，宿主紧接着会用 [attachOrStart] 新起一个。 */
        fun restartQuietly() {
            val s = current
            current = null
            s?.finishQuietly()
        }

    }
}
