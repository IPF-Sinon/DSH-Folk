package me.bmax.apatch.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.TextDecrease
import androidx.compose.material.icons.outlined.TextIncrease
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.util.concurrent.atomic.AtomicBoolean
import me.bmax.apatch.APApplication
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshEnv
import me.bmax.apatch.dsh.DshPtySession
import me.bmax.apatch.ui.theme.BackgroundConfig
import me.bmax.apatch.util.ui.HomeBottomSpacer
import me.bmax.apatch.util.ui.isImeVisible
import me.bmax.apatch.util.ui.showToast

private const val KEY_FONT_SP = "dsh_term_font_sp"

/**
 * 终端固定深色配色（不跟随应用主题）。
 *
 * 必须显式给背景的理由：TerminalRenderer 里只有
 * `if (backColor != palette[COLOR_INDEX_BACKGROUND])` 时才画矩形
 * （源码注释原文 "Only draw non-default background"）—— 也就是说
 * **默认背景压根不绘制**，露出来的是底层 Compose 窗口背景。
 * 而 Termux 默认前景是白色，浅色主题下就成了白底白字；
 * 开了自定义背景图时更是图片直接透到字符后面。
 *
 * 终端的惯例就是深底亮字，所以不做浅色变体，也顺带解决了主题背景干扰。
 */
private const val TERM_BG = 0xFF101014.toInt()
private const val TERM_FG = 0xFFE6E6E6.toInt()
private const val TERM_CURSOR = 0xFF7DD3FC.toInt()

private const val FONT_MIN_SP = 8
private const val FONT_MAX_SP = 24
private const val FONT_DEF_SP = 13

/**
 * 扩展键：`显示 to 要发的序列`；序列为 null 表示状态键（Ctrl / Alt）。
 * 手机软键盘没有 Esc / Ctrl / 方向键，缺了这排 TUI 基本没法用。
 */
private val EXTRA_KEYS: List<Pair<String, String?>> = listOf(
    "ESC" to "\u001b",
    "TAB" to "\t",
    "CTRL" to null,
    "ALT" to null,
    "↑" to "\u001b[A",
    "↓" to "\u001b[B",
    "←" to "\u001b[D",
    "→" to "\u001b[C",
    "^C" to "\u0003",
    "^D" to "\u0004",
    "^Z" to "\u001a",
    "|" to "|",
    "~" to "~",
    "/" to "/",
    "-" to "-",
)

/**
 * DSH 终端页（底栏「终端」）。
 *
 * 一个真 PTY 终端，直接进 rootfs 容器的 `bash -l`，所以 `dsh`、`node`、`npm`、`vim`、
 * `htop` 都能正常用。会话跨页面存活（[DshPtySession] 持单例），切走再回来历史仍在。
 *
 * Compose 侧用 [AndroidView] 承载 Termux 的 TerminalView —— 终端渲染没有 Compose 实现，
 * 自己写一个终端模拟器不现实。
 */
/**
 * 把固定配色写进 emulator 的调色盘。
 *
 * TerminalColors.mCurrentColors 是 public 数组，索引 256/257/258 分别是
 * 前景/背景/光标（TextStyle.COLOR_INDEX_*）。emulator 要到
 * initializeEmulator 之后才存在，所以这一步必须在 attachSession 之后做。
 */
private fun applyTermColors(session: TerminalSession?) {
    val colors = session?.emulator?.mColors ?: return
    colors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND] = TERM_FG
    colors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND] = TERM_BG
    colors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] = TERM_CURSOR
}

@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun DshTerminalScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val prefs = remember { APApplication.sharedPreferences }
    var fontSp by remember {
        mutableIntStateOf(prefs.getInt(KEY_FONT_SP, FONT_DEF_SP).coerceIn(FONT_MIN_SP, FONT_MAX_SP))
    }
    var title by remember { mutableStateOf("Ubuntu · PTY") }
    var ctrlDown by remember { mutableStateOf(false) }
    var altDown by remember { mutableStateOf(false) }
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }
    // 原地重启时 +1，用 key 强制重建 AndroidView 以便 attachOrStart 起一个新 PTY
    var sessionGen by remember { mutableIntStateOf(0) }
    // 重启那一刻键盘是否开着；决定新会话要不要把输入法重新叫起来
    var restoreImeOnRestart by remember { mutableStateOf(false) }

    val installed = remember { DshEnv.isRuntimeInstalled(context) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val imeVisible = isImeVisible()

    /**
     * 重建视图后把焦点抢回终端。
     *
     * 不做这一步的话，旧 TerminalView 被销毁时它持有的焦点会落到下一个可聚焦节点 ——
     * 工具栏第一个 IconButton（缩小字号），输入法也跟着跑到那里去。
     */
    LaunchedEffect(sessionGen) {
        if (sessionGen == 0) return@LaunchedEffect
        val view = terminalView ?: return@LaunchedEffect
        view.requestFocus()
        if (restoreImeOnRestart) {
            val im = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            im?.showSoftInput(view, 0)
        }
    }

    fun setFont(sp: Int) {
        val v = sp.coerceIn(FONT_MIN_SP, FONT_MAX_SP)
        fontSp = v
        prefs.edit().putInt(KEY_FONT_SP, v).apply()
        terminalView?.setTextSize(
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, v.toFloat(), context.resources.displayMetrics
            ).toInt()
        )
    }

    fun send(seq: String) {
        val s = DshPtySession.currentSession()
        if (s == null || !s.isRunning()) {
            showToast(context, R.string.dsh_term_session_ended)
            return
        }
        s.write(seq)
        // 修饰键一次性：发完就灭，与物理键盘手感一致
        if (ctrlDown || altDown) {
            ctrlDown = false
            altDown = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (installed) title else stringResource(R.string.dsh_phase_not_ready),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                actions = {
                    IconButton(onClick = { setFont(fontSp - 1) }) {
                        Icon(
                            Icons.Outlined.TextDecrease,
                            contentDescription = stringResource(R.string.dsh_term_font_smaller),
                        )
                    }
                    IconButton(onClick = { setFont(fontSp + 1) }) {
                        Icon(
                            Icons.Outlined.TextIncrease,
                            contentDescription = stringResource(R.string.dsh_term_font_larger),
                        )
                    }
                    IconButton(onClick = {
                        // 原地重启：收旧会话 + 重建视图，别把用户弹回上一页。
                        // 键盘状态先记下来，重建后由 LaunchedEffect(sessionGen) 复原。
                        restoreImeOnRestart = imeVisible
                        DshPtySession.restartQuietly()
                        sessionGen++
                        showToast(context, R.string.dsh_term_restarted)
                    }) {
                        Icon(
                            Icons.Outlined.RestartAlt,
                            contentDescription = stringResource(R.string.dsh_term_restart),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        if (!installed) {
            Box(
                Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.dsh_term_not_ready),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        Column(Modifier.fillMaxSize().padding(innerPadding).imePadding()) {
            // 左右留白 + 圆角 + 半透明黑：
            //
            // View 背景设成透明而不是 TERM_BG，透出来的才是这个 Surface 的半透明色。
            // 字符本身不受影响 —— TerminalRenderer 只在 backColor 与调色盘的
            // COLOR_INDEX_BACKGROUND 不同时才画背景矩形（见 [applyTermColors] 的说明），
            // 默认背景压根不绘制，所以半透明只作用在「空白」区域。
            //
            // 不透明度由外观设置里的滑块决定，默认 0.88。
            Surface(
                color = Color(TERM_BG).copy(alpha = BackgroundConfig.terminalBgAlpha),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            ) {
                key(sessionGen) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            TerminalView(ctx, null).also { view ->
                                terminalView = view
                                view.isFocusable = true
                                view.isFocusableInTouchMode = true
                                view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            view.setTextSize(
                                TypedValue.applyDimension(
                                    TypedValue.COMPLEX_UNIT_SP, fontSp.toFloat(),
                                    ctx.resources.displayMetrics
                                ).toInt()
                            )
                            val redrawPending = AtomicBoolean(false)
                            val listener = object : DshPtySession.Listener {
                                // PTY 每几十字节回调一次，无节流 post 会灌满主线程队列
                                // （表现为「终端越用越卡」），所以合并成一次重绘
                                override fun onOutput() {
                                    if (!redrawPending.compareAndSet(false, true)) return
                                    main.post {
                                        redrawPending.set(false)
                                        view.onScreenUpdated()
                                    }
                                }

                                override fun onTitle(t: String) {
                                    if (t.isNotBlank()) main.post { title = t.trim() }
                                }

                                override fun onExit(status: Int) {
                                    main.post { title = context.getString(R.string.dsh_term_exited, status) }
                                }

                                override fun onCopy(text: String) {
                                    main.post {
                                        runCatching {
                                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                                                as ClipboardManager
                                            cm.setPrimaryClip(ClipData.newPlainText("term", text))
                                        }
                                    }
                                }

                                override fun onPasteRequest() {
                                    main.post {
                                        runCatching {
                                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                                                as ClipboardManager
                                            val clip = cm.primaryClip ?: return@runCatching
                                            if (clip.itemCount == 0) return@runCatching
                                            val cs = clip.getItemAt(0).coerceToText(ctx)
                                            if (!cs.isNullOrEmpty()) send(cs.toString())
                                        }
                                    }
                                }

                                override fun onBell() {
                                    // 手机上「响一声」多半是骚扰，这里不做处理
                                }
                            }
                            view.setTerminalViewClient(
                                DshTerminalViewClient(
                                    fontSp = { fontSp },
                                    bumpFont = { setFont(fontSp + it) },
                                    showKeyboard = {
                                        view.requestFocus()
                                        val im = ctx.getSystemService(Context.INPUT_METHOD_SERVICE)
                                            as? InputMethodManager
                                        im?.showSoftInput(view, 0)
                                    },
                                    readCtrl = { ctrlDown },
                                    readAlt = { altDown },
                                    onScreenUpdated = { view.onScreenUpdated() },
                                )
                            )
                            runCatching {
                                // 初始 80x24 只是占位：attachSession 后 TerminalView 会按实测
                                // 字宽重算行列并通知 PTY，否则 TUI 边框会错位
                                val ps = DshPtySession.attachOrStart(80, 24, listener)
                                view.attachSession(ps.session)
                                applyTermColors(ps.session)
                                title = ps.session?.title?.takeIf { it.isNotBlank() } ?: "Ubuntu · PTY"
                            }.onFailure {
                                title = context.getString(
                                    R.string.dsh_term_start_failed,
                                    it.message ?: it.javaClass.simpleName,
                                )
                            }
                        }
                    },
                )
                }
            }

            // 扩展键条。键盘弹起时它就该紧贴键盘上沿 —— 底部预留由
            // HomeBottomSpacer 在 IME 可见时自行归零，这里只保留自身的呼吸间距。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for ((label, seq) in EXTRA_KEYS) {
                    val active = (label == "CTRL" && ctrlDown) || (label == "ALT" && altDown)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (active) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = {
                            when (label) {
                                "CTRL" -> ctrlDown = !ctrlDown
                                "ALT" -> altDown = !altDown
                                else -> seq?.let { send(it) }
                            }
                        },
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                        )
                    }
                }
            }

            // 底栏在终端页常显（不参与自动隐藏），不让位的话扩展键条会被盖住
            HomeBottomSpacer()
        }
    }
}

/**
 * TerminalViewClient 的最小实现。
 *
 * 库要求的方法很多，但绝大多数返回默认值即可 —— 真正需要接的是缩放调字号、点击唤起键盘，
 * 以及把扩展键条上的 Ctrl/Alt 状态告诉库（它每次按键都会来问）。
 */
private class DshTerminalViewClient(
    private val fontSp: () -> Int,
    private val bumpFont: (Int) -> Unit,
    private val showKeyboard: () -> Unit,
    private val readCtrl: () -> Boolean,
    private val readAlt: () -> Boolean,
    private val onScreenUpdated: () -> Unit,
) : TerminalViewClient {

    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) bumpFont(if (scale > 1f) 1 else -1)
        return fontSp().toFloat()
    }

    override fun onSingleTapUp(e: MotionEvent?) = showKeyboard()

    /** 返回键就该是返回键，映射成 Esc 会让人退不出去。 */
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    /** 逐字符提交，对中文输入法友好（否则候选词会把整行吞掉）。 */
    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false

    /** 返回 false 才会走库自带的文本选择。 */
    override fun onLongPress(e: MotionEvent?): Boolean = false

    override fun readControlKey(): Boolean = readCtrl()

    override fun readAltKey(): Boolean = readAlt()

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDownFromKeyboard: Boolean, session: TerminalSession?): Boolean = false

    override fun onEmulatorSet() = onScreenUpdated()

    override fun logError(tag: String?, message: String?) { android.util.Log.e(TAG, "$tag: $message") }
    override fun logWarn(tag: String?, message: String?) { android.util.Log.w(TAG, "$tag: $message") }
    override fun logInfo(tag: String?, message: String?) { android.util.Log.i(TAG, "$tag: $message") }
    override fun logDebug(tag: String?, message: String?) { android.util.Log.d(TAG, "$tag: $message") }
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        android.util.Log.w(TAG, "$tag: $message", e)
    }
    override fun logStackTrace(tag: String?, e: Exception?) { android.util.Log.w(TAG, tag, e) }

    private companion object {
        const val TAG = "DSH-Folk-ptyview"
    }
}
