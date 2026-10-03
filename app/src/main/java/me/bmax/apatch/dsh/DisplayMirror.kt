package me.bmax.apatch.dsh

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.outlined.Minimize
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 虚拟屏的**悬浮小窗**。
 *
 * ## 它解决什么
 *
 * agent 在虚拟屏上操作时，用户在自己手机上**什么都看不到** —— 那些点击落在哪、画面现在
 * 什么样，只能靠用户自己想起来去设置里开预览页。而 agent 做的事常常正是要用户看着的
 * （点外卖、填表单）。所以建出一块虚拟屏之后，就把画面挂到屏幕上。
 *
 * ## 形态：平时是边缘的一个把手，点开才展开
 *
 * 这块照 [Operit](https://github.com/AAswordman/Operit) 的 `VirtualDisplayOverlay` 学
 * （见 README 致谢）。一开始我把整块画面一直摊在屏幕上，结果是"挡着我自己的界面"，
 * 一点不像它 —— 差别不在配色，在**形态**：
 *
 * - **默认折叠**成屏幕边缘的把手（比屏幕外多藏 12dp，露出约 36×48dp），里面显示
 *   **agent 正在操作的那个 App 的图标**。抬眼就知道它在哪儿干活，又不挡事。
 * - **点一下**才展开成小窗（视频区占屏宽 40%）；拖动可挪，挪到哪儿算哪儿。
 * - 展开后**点一下才出现控制按钮**（折叠 / 全屏 / 关闭），3 秒后自己隐去 ——
 *   静止时画面上没有任何按钮，那是"自然"的另一半。
 * - 折叠 / 展开 / 全屏都是 300ms 的位移动画，不是硬跳。
 * - 整块界面用 **Compose** 画（`ComposeView` + [OverlayLifecycleOwner]），
 *   于是圆角、Material 图标、动画都是现成的。
 *
 * ## 与预览页的分工
 *
 * 预览页是**用户主动打开**的全屏调试口（也是排障口：画面没来还是输入没进去，一看便知）；
 * 小窗是**agent 一动就自己出现**的旁观口。两者看的是同一块屏、同一条会话。
 * 但服务端一块屏只有**一个** sink，两边同时挂会互相顶掉，所以预览页打开期间本类让位
 * （[suspendForPreview]），关掉再要回来（[resumeAfterPreview]）。
 *
 * ## 它绝不新建虚拟屏
 *
 * 这点是刻意的：小窗存在的意义就是「看 agent 正在看的那块」。要是它自己建一块，
 * 用户看到的就会是一块空白屏，而 agent 在另一块上点 —— 两边的画面永远不是同一个。
 * 所以这里只挂 [DisplayServer.currentSession] 报出来的那块，没有会话就什么都不做。
 *
 * ## 失败一律吞掉
 *
 * 它是 agent 工具调用路上的**副作用**：小窗建不出来（没有悬浮窗权限、厂商 ROM 拦了
 * TYPE_APPLICATION_OVERLAY、解码器起不来）绝不能反过来让那一次 `display session` 失败。
 * 所有入口都包了 try/catch，出错只记日志。
 *
 * ## 线程
 *
 * 窗口 / View / Compose 状态只在主线程碰（[main]）；[DisplayServer.setVideoSink] 会走
 * 特权启动路径，是阻塞的，必须放到后台线程 —— 这条界线在本文件里每一处都守着。
 */
object DisplayMirror {

    private const val TAG = "DshDisplayMirror"

    /** 展开态视频区占屏宽的比例（与 Operit 同一手感：0.4）。 */
    private const val WIDTH_FRACTION = 0.40f

    /** 折叠把手：露出的宽度 / 藏到屏幕外的宽度 / 高度（dp）。 */
    private const val HANDLE_VISIBLE_DP = 40
    private const val HANDLE_OFFSCREEN_DP = 12
    private const val HANDLE_HEIGHT_DP = 48

    /** 把手里那个 App 图标的大小。 */
    private const val HANDLE_ICON_DP = 30

    /** 展开态小窗的圆角 / 折叠把手的圆角。 */
    private const val EXPANDED_RADIUS_DP = 16
    private const val HANDLE_RADIUS_DP = 14

    /** 首次出现的提示气泡：宽度（可见部分）与停留时长。 */
    private const val HINT_WIDTH_DP = 208
    private const val HINT_MS = 4200L

    /** 把手的入场淡入、以及 agent 每用一次屏时的脉冲时长。 */
    private const val APPEAR_MS = 220
    private const val PULSE_MS = 300

    /** 把手的常态底色与"正在干活"的高亮色。 */
    private val HANDLE_BASE = Color.Gray.copy(alpha = 0.85f)
    private val HANDLE_HOT = Color(0xFF2F6FB0).copy(alpha = 0.95f)

    /** 全屏常驻胶囊里一格按钮与图标的大小。 */
    private const val PILL_BUTTON_DP = 34
    private const val PILL_ICON_DP = 18

    /**
     * 常驻胶囊离屏幕顶端的距离。
     *
     * 不能用 12dp 那种"好看"的小边距：窗口是 `FLAG_LAYOUT_NO_LIMITS`（画面伸到状态栏底下），
     * 而状态栏是更上层的系统窗口，压在它那一条里的触摸到不了覆盖层 —— 而这里是全屏唯一的出路。
     * 32dp 足够越过常见状态栏（含 MIUI 的加高条），也让胶囊不与被镜像 App 的顶栏重叠。
     */
    private const val PILL_TOP_DP = 32

    /**
     * 「算点击还是滑动」的位移阈值（px）。与预览页同一个值（`DisplayPreviewScreen.TAP_SLOP_PX`）：
     * 两处对「手指抖一下算不算滑动」的判断必须一致，否则同一个动作在两个界面里结果不同。
     */
    private const val TAP_SLOP_PX = 24.0

    /**
     * 触摸转发用的**单线程** executor。
     *
     * 单线程是为了保序：`tap`/`swipe` 都是 binder 调用（阻塞），并发跑就可能 UP 抢在 MOVE 前到，
     * 远端会看到一个乱序的手势。它同时把主线程让出来（手势回调跑在主线程）。
     */
    private val touchExecutor: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "DshDisplayMirrorTouch")
        }
    }

    /** 折叠 / 展开 / 全屏的动画时长。 */
    private const val SNAP_MS = 300L

    /** 控制按钮出现后多久自己隐去。 */
    private const val CONTROLS_MS = 3000L

    private val main = Handler(Looper.getMainLooper())

    /** 应用级 Context。桥的后台线程会写它，主线程会读，所以是 volatile。 */
    @Volatile
    private var app: Context? = null

    private var wm: WindowManager? = null
    private var view: ComposeView? = null
    private var owner: OverlayLifecycleOwner? = null
    private var params: WindowManager.LayoutParams? = null
    private var sink: DisplayVideoSink? = null
    private var animator: ValueAnimator? = null

    /** 小窗里挂的是哪块屏。0 = 没显示。 */
    private var shownDisplayId = 0

    /** 第一帧是否已经记过日志（真机黑屏时用来区分"没帧"与"没合成"）。 */
    private var firstFrameLogged = false

    /** 展开前的几何：从贴边折叠还原时用。 */
    private var restX = 0
    private var restY = 0
    private var restW = 0
    private var restH = 0

    // ── Compose 状态（都由主线程写）──────────────────────────────────────────
    private var snapped by mutableStateOf(true)
    private var snappedRight by mutableStateOf(true)
    private var fullscreen by mutableStateOf(false)
    private var controls by mutableStateOf(false)

    /** agent 正在操作的那个 App；折叠把手里显示它的图标。 */
    private var appPackage by mutableStateOf<String?>(null)

    /** 虚拟屏的真实分辨率，用来算小窗高度（保持画面不变形）。 */
    private var videoWidth by mutableStateOf(1080)
    private var videoHeight by mutableStateOf(1920)

    /** 预览页占用期间为 true：不是"不显示"，是"先让位"，别把它当成用户关掉了。 */
    @Volatile
    private var suspended = false

    /** 用户手动关掉过的那块屏（点小窗上的 ✕）。下次建屏要重新给他看，所以只在同一块屏上生效。 */
    @Volatile
    private var dismissed = 0

    /**
     * 每"亮一下"把手就 +1。
     *
     * agent 每用一次虚拟屏都会碰它 —— 只出把手、又不出声的话，用户根本不知道它在干活
     * （这正是当初"agent 干了什么用户完全不知道"的老问题）。Compose 侧监听它做呼吸高亮。
     */
    private var pulseToken by mutableStateOf(0)

    /** 首次出现的提示气泡是否正显示着（一次性，见 [KEY_DISPLAY_HINT_SHOWN]）。 */
    private var hint by mutableStateOf(false)

    /** 是否正在问"要不要终止这块虚拟屏"（✕ 不再是"藏起来"，见 [terminateCurrent]）。 */
    private var confirmTerminate by mutableStateOf(false)

    // ── 开关与权限 ───────────────────────────────────────────────────────────

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)

    /** 默认开：见 [DshEnv.KEY_DISPLAY_FLOAT]。 */
    fun enabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(DshEnv.KEY_DISPLAY_FLOAT, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(DshEnv.KEY_DISPLAY_FLOAT, on).apply()
    }

    /**
     * 悬浮窗是**特殊权限**：`canDrawOverlays` 为 false 时 addView 会直接抛，
     * 所以每次显示之前都要问一次（用户可能刚在系统页里关掉）。
     */
    fun canOverlay(ctx: Context): Boolean = runCatching { Settings.canDrawOverlays(ctx) }
        .getOrDefault(false)

    // ── 对外动作 ─────────────────────────────────────────────────────────────

    /**
     * 按当前状态对齐一次：该显示就显示，不该显示就收起。
     *
     * 调用点：agent 建出一块虚拟屏之后，所以它读的是 [DisplayServer.currentSession]，
     * 而不是由调用方传进来的 id —— 少一个「两边各记一份、然后对不上」的机会。
     *
     * 调用方是桥的后台线程，而下面所有 View/窗口状态都只在主线程碰，所以整体交给主线程。
     */
    fun sync(ctx: Context) {
        try {
            app = ctx.applicationContext
            main.post { syncOnMain() }
        } catch (t: Throwable) {
            // 它是 agent 调用链上的副作用，绝不能因为"窗口这一步"让那次工具调用失败
            Log.w(TAG, "同步悬浮小窗失败：${t.message}", t)
        }
    }

    /**
     * agent 用了虚拟屏（任何一次 display 调用成功）。
     *
     * 为什么不能只在 [DisplayServer.startSession] 里同步一次：agent 完全可以显式带着
     * `--display N` 直接用**已有的**那块屏（[DshDisplay.displayOf]），那条路不建会话，
     * 于是窗口从来不出现 —— 用户看到的就是「agent 在用虚拟屏，可是什么都没弹出来」。
     */
    fun onAgentUse(ctx: Context) = noteUse(ctx, newRound = false)

    /**
     * 新一轮开始（建/复用会话，或开始驱动某个 App）。
     *
     * 与 [onAgentUse] 的区别只有一个：它会忘掉"这一轮我不想看"（[dismissed]）。
     * 屏是会被复用的，若把 ✕ 记成"这块屏永远不看"，那么用户关过一次之后，
     * 后面每一轮 agent 操作他都再也看不到画面。
     */
    fun beginRound(ctx: Context) = noteUse(ctx, newRound = true)

    private fun noteUse(ctx: Context, newRound: Boolean) {
        try {
            app = ctx.applicationContext
            main.post {
                try {
                    if (newRound) dismissed = 0
                    pulseToken++
                    syncOnMain()
                } catch (t: Throwable) {
                    Log.w(TAG, "同步悬浮小窗失败：${t.message}", t)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "同步悬浮小窗失败：${t.message}", t)
        }
    }

    private fun syncOnMain() {
        val ctx = app ?: return
        try {
            if (!enabled(ctx) || !canOverlay(ctx)) {
                hide()
                return
            }
            // 预览页正占着那个 sink：不是"不显示"，是"先让位"，等它关掉再要回来
            if (suspended) return
            val s = DisplayServer.currentSession() ?: return hide()
            if (dismissed == s.displayId) return
            if (view != null && shownDisplayId == s.displayId) return
            show(ctx, s)
        } catch (t: Throwable) {
            Log.w(TAG, "同步悬浮小窗失败：${t.message}", t)
        }
    }

    /** 收起小窗（不动「用户手动关过」这条记忆）。只在主线程调用。 */
    private fun hide() {
        animator?.cancel()
        animator = null
        val dec = sink
        val id = shownDisplayId
        sink = null
        shownDisplayId = 0
        val v = view
        view = null
        params = null
        hint = false
        confirmTerminate = false
        if (v != null) runCatching { wm?.removeView(v) }
        // 生命周期跟着窗口一起收：Compose 在无人持有之后别再收到事件
        owner?.moveTo(androidx.lifecycle.Lifecycle.Event.ON_DESTROY)
        owner = null
        detach(dec, id)
    }

    /**
     * 屏没了：收起小窗，并且忘掉「用户手动关过」——
     * 无论是服务端整个停了、还是用户只终止了某一块屏（小窗上的 ✕），
     * 下一块屏都是全新的一轮，该重新给他看。
     *
     * 这个方法从 [DisplayServer.stop] 的后台线程被调，状态存储本身是线程安全的，
     * 动窗口的那部分交给主线程。
     */
    fun onServerGone() {
        dismissed = 0
        try {
            main.post { hide() }
        } catch (t: Throwable) {
            Log.w(TAG, "收起悬浮小窗失败：${t.message}", t)
        }
    }

    /** 预览页要占用 sink：先让位。 */
    fun suspendForPreview() {
        suspended = true
        try {
            main.post { hide() }
        } catch (t: Throwable) {
            Log.w(TAG, "让位给预览页失败：${t.message}", t)
        }
    }

    /** 预览页关掉了：会话还在的话把小窗要回来。 */
    fun resumeAfterPreview(ctx: Context) {
        suspended = false
        sync(ctx)
    }

    // ── 显示 ────────────────────────────────────────────────────────────────

    /**
     * 建窗。**只在主线程调用，而且同步建完** ——
     * 反例是把它 post 到主线程：连调两次工具时第二次看到的 `view` 还是 null，
     * 于是会再 post 一次，屏幕上就会出现两个小窗。
     *
     * 一开始是**折叠**状态：先给用户一个不挡事的把手，他要看再点开。
     */
    private fun show(ctx: Context, s: DisplayServer.Session) {
        hide()
        val windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (windowManager == null) {
            Log.w(TAG, "拿不到 WindowManager，悬浮小窗跳过")
            return
        }
        wm = windowManager
        try {
            appPackage = DisplayServer.currentAppPackage()
            videoWidth = s.width.coerceAtLeast(1)
            videoHeight = s.height.coerceAtLeast(1)
            firstFrameLogged = false
            snapped = true
            snappedRight = true
            fullscreen = false
            controls = false
            hint = false
            confirmTerminate = false

            val dm = ctx.resources.displayMetrics
            restW = (dm.widthPixels * WIDTH_FRACTION).roundToInt().coerceAtLeast(1)
            restH = heightFor(restW, videoWidth, videoHeight, dm.heightPixels)
            restX = ((dm.widthPixels - restW) / 2f).roundToInt().coerceAtLeast(0)
            restY = ((dm.heightPixels - restH) / 2f).roundToInt().coerceAtLeast(0)

            // Compose 需要三个 owner，缺一个就会在 rememberSaveable 之类的地方抛
            val lo = OverlayLifecycleOwner().apply {
                moveTo(androidx.lifecycle.Lifecycle.Event.ON_CREATE)
                moveTo(androidx.lifecycle.Lifecycle.Event.ON_START)
                moveTo(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
            }
            owner = lo

            val cv = ComposeView(ctx).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setViewTreeLifecycleOwner(lo)
                setViewTreeViewModelStoreOwner(lo)
                setViewTreeSavedStateRegistryOwner(lo)
                setContent {
                    // 刻意**不用** APatchTheme：它会无条件调 SystemBarStyle → `context as
                    // ComponentActivity`，而悬浮窗的 context 不是 Activity，用了必崩。
                    // 这块 chrome 盖在画面上，固定用深色配色反而更稳、对比度也够。
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        MirrorContent(s.displayId)
                    }
                }
            }

            val p = WindowManager.LayoutParams().apply {
                width = handleWidthPx(ctx)
                height = handleHeightPx(ctx)
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                // NOT_FOCUSABLE：小窗一旦拿到焦点，被 agent 操作的应用就会失去焦点、
                // 输入法也会打到小窗这边来。它只负责显示，不参与输入。
                // LAYOUT_NO_LIMITS：折叠时要把窗口的一部分放到屏幕外，没有它会被夹回屏内。
                // HARDWARE_ACCELERATED：这是**非 Activity 窗口**，Compose 要在里面画，
                // 显式声明比依赖默认值稳。
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                format = PixelFormat.TRANSLUCENT
                gravity = Gravity.TOP or Gravity.START
                x = snappedX(ctx)
                y = ((dm.heightPixels - handleHeightPx(ctx)) / 3f).roundToInt()
                    .coerceAtLeast(0)
            }
            params = p
            windowManager.addView(cv, p)
            view = cv
            shownDisplayId = s.displayId

            // 首次出现给一次提示气泡：只出把手、又不说话，用户根本不知道这一小块是什么。
            // 只提示一次（记住看过），之后靠脉冲高亮提示"agent 又开始干活了"。
            if (!prefs(ctx).getBoolean(DshEnv.KEY_DISPLAY_HINT_SHOWN, false)) {
                prefs(ctx).edit().putBoolean(DshEnv.KEY_DISPLAY_HINT_SHOWN, true).apply()
                hint = true
                animateTo(hintWidthPx(ctx), handleHeightPx(ctx), hintX(ctx), p.y)
                main.postDelayed(
                    {
                        // 用户已经自己展开/切全屏了就别再抢回来（那两处都会把 hint 清掉）
                        if (hint) {
                            hint = false
                            collapse()
                        }
                    },
                    HINT_MS,
                )
            }
        } catch (t: Throwable) {
            // 没有悬浮窗权限、厂商 ROM 拦了 OVERLAY 类型等，都会走到这里。
            // 只记日志：agent 那一次调用已经成功了，不能被这个副作用拖下水。
            Log.w(TAG, "悬浮小窗建不起来（虚拟屏本身正常）：${t.message}", t)
            hide()
        }
    }

    // ── 几何与动画 ───────────────────────────────────────────────────────────

    private fun densityPx(ctx: Context, dp: Int): Int =
        (dp * ctx.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)

    private fun visiblePx(ctx: Context) = densityPx(ctx, HANDLE_VISIBLE_DP)
    private fun handleWidthPx(ctx: Context) = densityPx(ctx, HANDLE_VISIBLE_DP + HANDLE_OFFSCREEN_DP)
    private fun handleHeightPx(ctx: Context) = densityPx(ctx, HANDLE_HEIGHT_DP)

    /** 提示气泡那一版的窗口宽度：可见部分是 [HINT_WIDTH_DP]，另有屏外的那 [HANDLE_OFFSCREEN_DP]。 */
    private fun hintWidthPx(ctx: Context) = densityPx(ctx, HINT_WIDTH_DP + HANDLE_OFFSCREEN_DP)

    /** 气泡期间的 x：可见边缘仍贴在屏幕上，多出来的宽度向屏内长。 */
    private fun hintX(ctx: Context): Int {
        val screenW = ctx.resources.displayMetrics.widthPixels
        return if (snappedRight) screenW - densityPx(ctx, HINT_WIDTH_DP)
        else -densityPx(ctx, HANDLE_OFFSCREEN_DP)
    }

    private fun snappedX(ctx: Context): Int {
        val screenW = ctx.resources.displayMetrics.widthPixels
        return if (snappedRight) screenW - visiblePx(ctx) else -densityPx(ctx, HANDLE_OFFSCREEN_DP)
    }

    /** 折叠：贴到最近的那条竖边，并把窗口缩成把手大小。 */
    private fun collapse() {
        val ctx = app ?: return
        val p = params ?: return
        val dm = ctx.resources.displayMetrics
        snappedRight = p.x + p.width / 2 >= dm.widthPixels / 2
        snapped = true
        fullscreen = false
        controls = false
        val targetH = handleHeightPx(ctx)
        val targetY = p.y.coerceIn(0, (dm.heightPixels - targetH).coerceAtLeast(0))
        animateTo(handleWidthPx(ctx), targetH, snappedX(ctx), targetY)
    }

    /** 展开：还原到折叠前的位置（第一次展开就是屏幕中间）。 */
    private fun expand() {
        val ctx = app ?: return
        val dm = ctx.resources.displayMetrics
        // 用户自己展开了：提示气泡与确认层都让位（气泡的收尾任务会看到 hint 已是 false 而跳过）
        hint = false
        confirmTerminate = false
        snapped = false
        fullscreen = false
        controls = true
        val targetX = restX.coerceIn(0, (dm.widthPixels - restW).coerceAtLeast(0))
        val targetY = restY.coerceIn(0, (dm.heightPixels - restH).coerceAtLeast(0))
        animateTo(restW, restH, targetX, targetY)
    }

    private fun toggleFullscreen() {
        val ctx = app ?: return
        val dm = ctx.resources.displayMetrics
        // 用户自己动手了：提示气泡与确认层都让位
        hint = false
        confirmTerminate = false
        fullscreen = !fullscreen
        // 切进/切出全屏都把控制条亮出来：全屏时它是**唯一的出路**（窗口不可聚焦，
        // 收不到返回键；点画面出控制条是用户唯一能按到"退出全屏/缩小/关闭"的地方），
        // 不能让他进去以后先看到一块没有任何按钮的黑屏。3 秒后由自动隐藏收走。
        controls = true
        if (fullscreen) {
            animateTo(dm.widthPixels, dm.heightPixels, 0, 0)
        } else {
            val targetX = restX.coerceIn(0, (dm.widthPixels - restW).coerceAtLeast(0))
            val targetY = restY.coerceIn(0, (dm.heightPixels - restH).coerceAtLeast(0))
            animateTo(restW, restH, targetX, targetY)
        }
    }

    /**
     * 位移 + 改尺寸的动画。
     *
     * 一次性把四个值一起插值：只动位置、尺寸硬跳的话，折叠/展开那一下会"闪"一下，
     * 而这一下正是用户判断"这东西做得糙不糙"的地方。
     */
    private fun animateTo(tw: Int, th: Int, tx: Int, ty: Int) {
        val windowManager = wm ?: return
        val v = view ?: return
        val p = params ?: return
        val sx = p.x
        val sy = p.y
        val sw = p.width
        val sh = p.height
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SNAP_MS
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                p.x = (sx + (tx - sx) * f).roundToInt()
                p.y = (sy + (ty - sy) * f).roundToInt()
                p.width = (sw + (tw - sw) * f).roundToInt().coerceAtLeast(1)
                p.height = (sh + (th - sh) * f).roundToInt().coerceAtLeast(1)
                runCatching { windowManager.updateViewLayout(v, p) }
            }
            start()
        }
    }

    /** 拖动。折叠态只允许上下挪（保持贴边）。 */
    private fun moveBy(dx: Float, dy: Float) {
        val ctx = app ?: return
        val windowManager = wm ?: return
        val v = view ?: return
        val p = params ?: return
        if (fullscreen) return
        val dm = ctx.resources.displayMetrics
        if (snapped) {
            p.y = (p.y + dy.roundToInt()).coerceIn(0, (dm.heightPixels - p.height).coerceAtLeast(0))
            p.x = snappedX(ctx)
        } else {
            p.x = (p.x + dx.roundToInt()).coerceIn(0, (dm.widthPixels - p.width).coerceAtLeast(0))
            p.y = (p.y + dy.roundToInt()).coerceIn(0, (dm.heightPixels - p.height).coerceAtLeast(0))
            restX = p.x
            restY = p.y
        }
        runCatching { windowManager.updateViewLayout(v, p) }
    }

    /** 用户按了 ✕：记下"这一块他不想看"，同屏不再弹回来。 */
    private fun dismiss() {
        confirmTerminate = true
        controls = false
    }

    /** 取消终止：回到刚才的样子，什么都不动。 */
    private fun cancelTerminate() {
        confirmTerminate = false
    }

    /**
     * 确认终止：真的把那块虚拟屏销毁掉。
     *
     * 窗口先收，`destroyDisplay` 交给后台线程（binder 调用不占主线程）。
     * 服务端进程**不动** —— 那是 [DisplayServer.stop]（全部终止）的事，这里只收这一块，
     * agent 可能还开着别的屏，或者马上要建新的。
     */
    private fun terminateCurrent() {
        val ctx = app ?: return
        val id = shownDisplayId
        confirmTerminate = false
        // 这轮"不想看"的记忆也一起清掉：屏都没了，"不看这块屏"就没有意义了
        dismissed = 0
        hide()
        if (id <= 0) return
        Thread({
            // 刻意不用 return@Thread（隐式标签脆，见 DisplayServer.startPingLoop 的同一条注释）
            val r = DisplayServer.stopSession(ctx, id)
            if (r.isFailure) Log.w(TAG, "终止虚拟屏失败：" + r.exceptionOrNull()?.message)
        }, "DshDisplayMirrorTerminate").start()
    }

    /**
     * 把一次全屏手势转发进虚拟屏（**只在全屏**）。
     *
     * 坐标换算与判定沿用预览页那一套：先把窗口像素按比例换算到虚拟屏坐标
     * （`x * 虚拟屏宽 / 视频区宽`），位移小于 [TAP_SLOP_PX] 算点击，否则按滑动发出去。
     *
     * 必须离开主线程：`svc.tap` / `svc.swipe` 都是 binder 调用，而这里正处在手势回调里
     * （主线程）；[touchExecutor] 是单线程的，保证 DOWN/MOVE/UP 的先后不乱。
     */
    private fun forwardTouch(
        displayId: Int,
        tap: Boolean,
        from: Offset,
        to: Offset,
        boxW: Int,
        boxH: Int,
        durationMs: Long,
    ) {
        if (displayId <= 0 || boxW <= 0 || boxH <= 0) return
        val x1 = from.x * videoWidth / boxW
        val y1 = from.y * videoHeight / boxH
        val x2 = to.x * videoWidth / boxW
        val y2 = to.y * videoHeight / boxH
        runCatching {
            touchExecutor.execute {
                // 刻意不用 return@Runnable：那是"传给构造函数的 lambda 的隐式标签"，脆
                // （同 DisplayServer.startPingLoop 的注释）。用 if 包住即可。
                try {
                    val svc = DisplayServer.current()
                    if (svc != null) {
                        if (tap) {
                            svc.tap(displayId, x1, y1)
                        } else {
                            svc.swipe(displayId, x1, y1, x2, y2, durationMs)
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "转发触摸失败：${t.message}", t)
                }
            }
        }.onFailure { Log.w(TAG, "触摸转发任务提交失败：${it.message}") }
    }

    // ── Compose 界面 ─────────────────────────────────────────────────────────

    @Composable
    private fun MirrorContent(displayId: Int) {
        val ctx = LocalContext.current
        val isSnapped = snapped
        val isFullscreen = fullscreen
        var surface by remember { mutableStateOf<Surface?>(null) }
        // 视频区在窗口内的像素尺寸：转发触摸时要把窗口坐标换算成虚拟屏坐标
        var boxW by remember { mutableStateOf(0) }
        var boxH by remember { mutableStateOf(0) }

        // Surface 就绪 → 建解码头并挂到服务端；换 Surface 会重建解码器
        LaunchedEffect(surface, displayId) {
            val sf = surface ?: return@LaunchedEffect
            attachSink(ctx, displayId, sf)
        }
        // 离开组合时一定要摘：不摘服务端会一直往一个没人看的解码器推帧
        DisposableEffect(displayId) {
            onDispose { detachSink() }
        }
        // 控制按钮出现后自己隐去 —— 静止画面上不该常驻按钮
        LaunchedEffect(controls) {
            if (controls) {
                delay(CONTROLS_MS)
                controls = false
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { boxW = it.width; boxH = it.height }
                .pointerInput(isSnapped, isFullscreen) {
                    when {
                        // 折叠态：拖动 = 沿屏幕边缘挪
                        isSnapped -> detectDragGestures { change, amount ->
                            change.consume()
                            moveBy(amount.x, amount.y)
                        }
                        // 展开态：拖动 = 挪窗口
                        !isFullscreen -> detectDragGestures(
                            onDragStart = { controls = false },
                            onDrag = { change, amount ->
                                change.consume()
                                moveBy(amount.x, amount.y)
                            },
                        )
                        // **全屏态：把触摸转发进虚拟屏** —— 全屏就是"我要亲手点它"的场景。
                        //
                        // 手势只在这里转发（小窗态仍只旁观），与 Operit 同一取舍：小窗点一下
                        // 是唤控制条，全屏点一下是点虚拟屏。转发跑在单线程 executor 上（binder
                        // 调用会阻塞），按预览页那套映射与判定（同为 24px 的 tap 阈值）。
                        else -> awaitEachGesture {
                            val down = awaitFirstDown()
                            val from = down.position
                            val startedAt = System.currentTimeMillis()
                            val to = waitForUpOrCancellation()?.position ?: from
                            if (displayId <= 0 || boxW <= 0 || boxH <= 0) return@awaitEachGesture
                            val moved = hypot((to.x - from.x).toDouble(), (to.y - from.y).toDouble())
                            val duration = (System.currentTimeMillis() - startedAt).coerceIn(20L, 10_000L)
                            forwardTouch(
                                displayId = displayId,
                                tap = moved < TAP_SLOP_PX,
                                from = from,
                                to = to,
                                boxW = boxW,
                                boxH = boxH,
                                durationMs = duration,
                            )
                        }
                    }
                }
                .then(
                    when {
                        isSnapped -> Modifier.pointerInput(isSnapped) {
                            detectTapGestures { expand() }
                        }
                        // 展开态：点一下切换控制条。
                        //
                        // 全屏态**不再需要**这条：那里单指触摸全被转发给虚拟屏，控制条改成
                        // 右上角**常驻**胶囊（见 FullscreenPill 与下方注释）。
                        !isFullscreen -> Modifier.pointerInput(isSnapped, isFullscreen) {
                            detectTapGestures { controls = !controls }
                        }
                        else -> Modifier
                    }
                ),
        ) {
            if (isSnapped) {
                MirrorHandle()
            } else {
                // 圆角必须靠 TextureView：SurfaceView 的画面是**独立图层**（SurfaceFlinger 合成），
                // 父级的裁剪对它无效 —— 用 SurfaceView 时四角永远是直角（用户反馈"边角太锐利"）。
                // TextureView 画在普通视图树里，裁剪/圆角/透明度都认。
                // 全屏时不裁（贴屏幕边缘，圆角反而会切掉画面）。
                val shape = if (isFullscreen) RectangleShape else RoundedCornerShape(EXPANDED_RADIUS_DP.dp)
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(shape),
                    factory = { c ->
                        TextureView(c).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                    surface = Surface(st)
                                }

                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit

                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                                    // 第一帧到达只记一次：真机上"黑屏"要能区分"没帧"与"没合成"。
                                    // 这个回调只给 st，拿不到宽高 —— 用虚拟屏自己的分辨率记。
                                    if (!firstFrameLogged) {
                                        firstFrameLogged = true
                                        Log.i(TAG, "小窗收到第一帧（虚拟屏 ${videoWidth}x${videoHeight}）")
                                    }
                                }

                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                    // Surface 没了还继续解会直接报错
                                    surface = null
                                    // 返回 false = 这个 SurfaceTexture 由我们自己释放。**必须**这样：
                                    // 返回 true 会让上层当场释放它，而解码器是异步停用的，可能还在往
                                    // 这个已释放的目标写帧。真释放放在 detach 线程、解码器停用之后。
                                    detachSink(st)
                                    return false
                                }
                            }
                        }
                    },
                )
                if (confirmTerminate) {
                    // 终止确认层：✕ 的后果不小（agent 下一步会失败），所以要问一次。
                    // 刻意**不用** Dialog —— 这是个 FLAG_NOT_FOCUSABLE 的悬浮窗，
                    // 真 Dialog 需要 Activity token 与焦点，在这里建不出来；同一窗口里画一层最稳。
                    TerminateConfirm()
                } else if (isFullscreen) {
                    // 全屏：**常驻**右上角小胶囊。
                    //
                    // 为什么不能像展开态那样"点一下唤出"：全屏的单指触摸全被转发进虚拟屏了，
                    // 覆盖层收不到 —— Operit 正是栽在这里（它靠"进全屏时亮 3 秒"，错过就摸不到
                    // 按钮）。常驻一小块换来"永远出得去"，比省下那 3 个 34dp 的按钮划算。
                    //
                    // 它自己的表面（含按钮之间/周围的黑底）会消费掉触摸，只有可见范围之外的
                    // 透明外边距留给虚拟屏；否则按胶囊会顺带点到虚拟屏（见 FullscreenPill）。
                    FullscreenPill()
                } else if (controls) {
                    MirrorControls()
                }
            }
        }
    }

    /**
     * 终止确认层（小窗上的 ✕ 按下去之后）。
     *
     * 说什么很重要：用户按 ✕ 想要的多半是"别挡着我"，而这件事现在做的是**销毁那块屏**，
     * agent 的下一步会因此失败。所以文案把后果讲明白，并给出"只是先收起来"的替代路径
     * ——左下角那颗"折叠"图标（[MirrorControls] 里的 Minimize）才是"先别挡我"。
     */
    @Composable
    private fun BoxScope.TerminateConfirm() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f)),
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF23262B).copy(alpha = 0.96f))
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.dsh_display_float_terminate_title),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = stringResource(R.string.dsh_display_float_terminate_summary),
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.size(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { cancelTerminate() }) {
                    Text(stringResource(R.string.dsh_display_float_terminate_cancel), color = Color.White)
                }
                TextButton(onClick = { terminateCurrent() }) {
                    Text(
                        text = stringResource(R.string.dsh_display_float_terminate_ok),
                        color = Color(0xFFFF8A80),
                    )
                }
            }
        }
    }

    /**
     * 全屏态常驻的右上角胶囊：缩小到边缘 / 退出全屏 / 关闭。
     *
     * 它是全屏态**唯一**的出路（窗口 `FLAG_NOT_FOCUSABLE`，收不到返回键；单指触摸又都转发给了
     * 虚拟屏），所以不参与自动隐藏、也不参与触摸转发 —— 按钮在 Compose 层，点按会被自己消费掉，
     * 转发手势（`awaitFirstDown`）默认只认未被消费的 down。
     */
    @Composable
    private fun BoxScope.FullscreenPill() {
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                // 顶到状态栏**下面**：窗口是 FLAG_LAYOUT_NO_LIMITS（画面会伸到状态栏底下），
                // 而状态栏是更上层的系统窗口 —— 落在它那一条里的触摸到不了我们这儿。偏巧
                // 这里是全屏态唯一的出路，被压住一角就可能按不动。
                .padding(top = PILL_TOP_DP.dp, end = 12.dp)
                // 吃掉落在**胶囊可见表面**上的触摸（含 4/2dp 内边距）。
                //
                // 只靠按钮消费不够：按钮之外还有一圈可见的黑底，落在那里会**穿到虚拟屏**上，
                // 用户会看到"明明按的是胶囊，虚拟屏里也被点了一下"。
                //
                // 位置刻意夹在 `.padding(top/end)` 与 `.clip(…)` 之间：消费范围 = 可见的
                // 胶囊本体，**不含**那 12dp 透明外边距 —— 外边距一来看不见，二来那正是
                // 虚拟屏里 App 顶栏常放菜单的位置，吞掉它反而按不到 App 的按钮。
                //
                // 它在按钮的**外层**：Compose 的 Main 阶段是"后代先于祖先"，按钮先拿到
                // 自己的 down 并消费，所以这里抢不走按钮的点按（只在按钮没消费时才生效）。
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown().also { it.consume() }
                        waitForUpOrCancellation()
                    }
                }
                .clip(RoundedCornerShape(999.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillButton(
                icon = Icons.Outlined.Minimize,
                a11y = R.string.dsh_display_float_a11y_minimize,
                onClick = { collapse() },
            )
            PillButton(
                icon = Icons.Filled.FullscreenExit,
                a11y = R.string.dsh_display_float_a11y_exit_fullscreen,
                onClick = { toggleFullscreen() },
            )
            PillButton(
                icon = Icons.Filled.Close,
                a11y = R.string.dsh_display_float_a11y_close,
                onClick = { dismiss() },
            )
        }
    }

    /** 胶囊里的一格。 */
    @Composable
    private fun PillButton(icon: ImageVector, a11y: Int, onClick: () -> Unit) {
        IconButton(onClick = onClick, modifier = Modifier.size(PILL_BUTTON_DP.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = stringResource(a11y),
                tint = Color.White,
                modifier = Modifier.size(PILL_ICON_DP.dp),
            )
        }
    }

    /**
     * 折叠态的把手：屏幕边缘的一小块，里面是 **agent 正在操作的那个 App 的图标** +
     * 一个指向屏幕外的箭头（点它就知道是往哪边展开）。
     */
    @Composable
    private fun MirrorHandle() {
        val ctx = LocalContext.current
        val right = snappedRight
        val density = LocalDensity.current
        val iconPx = with(density) { HANDLE_ICON_DP.dp.roundToPx() }
        val pkg = appPackage
        val icon: ImageBitmap? by produceState<ImageBitmap?>(null, pkg, iconPx) {
            value = if (pkg.isNullOrBlank()) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    runCatching {
                        ctx.packageManager.getApplicationIcon(pkg).toBitmap(iconPx, iconPx).asImageBitmap()
                    }.getOrNull()
                }
            }
        }
        // 入场淡入
        val appear = remember { Animatable(0f) }
        LaunchedEffect(Unit) { appear.animateTo(1f, tween(APPEAR_MS)) }
        // 脉冲：agent 每用一次虚拟屏（pulseToken 变一次）把手就亮一下。
        // 这是"只出把手"这个选择的必要补偿 —— 不出声又不亮，用户就不知道它在干活。
        val glow = remember { Animatable(0f) }
        LaunchedEffect(pulseToken) {
            if (pulseToken > 0) {
                repeat(2) {
                    glow.animateTo(1f, tween(PULSE_MS))
                    glow.animateTo(0f, tween(PULSE_MS))
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 窗口是瞬时 addView 出来的，硬闪一下看不出"它刚出现"；
                // 淡入 + 描边 + 脉冲（agent 每用一次屏就亮一下）一起让这一小块"显眼"。
                .graphicsLayer { alpha = appear.value }
                .clip(RoundedCornerShape(HANDLE_RADIUS_DP.dp))
                .background(lerp(HANDLE_BASE, HANDLE_HOT, glow.value))
                .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(HANDLE_RADIUS_DP.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                // 藏到屏幕外的那一段留白：内容要留在看得见的那一侧。
                // fillMaxSize + 端对齐：有提示气泡时，文字留在靠里侧、图标与箭头贴着可见边缘。
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = if (right) 0.dp else HANDLE_OFFSCREEN_DP.dp,
                        end = if (right) HANDLE_OFFSCREEN_DP.dp else 0.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (right) Arrangement.End else Arrangement.Start,
            ) {
                if (hint) {
                    Text(
                        text = stringResource(R.string.dsh_display_float_hint),
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (icon != null) {
                    Image(
                        bitmap = icon!!,
                        contentDescription = null,
                        modifier = Modifier
                            .size(HANDLE_ICON_DP.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                }
                Icon(
                    imageVector = if (right) Icons.Filled.ChevronLeft else Icons.Filled.ChevronRight,
                    contentDescription = stringResource(R.string.dsh_display_float_a11y_expand),
                    tint = Color.White,
                )
            }
        }
    }

    /**
     * 控制条：点画面才出现，3 秒后自己隐去。
     *
     * 保持三个图标（用户明确说带文字的胶囊没必要，"原来就挺好的"）：
     * 折叠回边缘把手 / 切换全屏 / 关闭这一块 —— 三个动作都是**出路**。
     *
     * 真正的坑不在这里的样式，而在**唤出**：全屏态一度没有"点画面出控制条"这一支，
     * 进了全屏就再也出不来（见 [MirrorContent] 里的注释）。
     */
    @Composable
    private fun BoxScope.MirrorControls() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f)),
        ) { }
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = { collapse() }) {
                Icon(
                    imageVector = Icons.Outlined.Minimize,
                    contentDescription = stringResource(R.string.dsh_display_float_a11y_minimize),
                    tint = Color.White,
                )
            }
            IconButton(onClick = { toggleFullscreen() }) {
                Icon(
                    imageVector = if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    contentDescription = stringResource(
                        if (fullscreen) R.string.dsh_display_float_a11y_exit_fullscreen
                        else R.string.dsh_display_float_a11y_fullscreen
                    ),
                    tint = Color.White,
                )
            }
            IconButton(onClick = { dismiss() }) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.dsh_display_float_a11y_close),
                    tint = Color.White,
                )
            }
            // 视频链路的一行数字（入队/解码 fps、丢帧、队列峰值、平均等待）。
            // 用户报"卡"的时候，这一行就是判据：解码低＝分辨率/码率压不住；丢帧多＝我们这边排队。
            // 只在控制条可见时出现（控制条本来就会自动隐去），平时不占画面。
            //
            // sink.lastStatsLine 是普通 volatile，不是 Compose 状态，所以要自己轮询 ——
            // 控制条在的这几秒里每 500ms 取一次，数字是活的而不是"打开那一刻的快照"。
            var statsLine by remember { mutableStateOf("") }
            LaunchedEffect(Unit) {
                while (true) {
                    statsLine = sink?.lastStatsLine.orEmpty()
                    delay(500)
                }
            }
            if (statsLine.isNotEmpty()) {
                Text(
                    text = statsLine,
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }

    // ── 解码头 ──────────────────────────────────────────────────────────────

    /** 解码器 + 服务端挂载。必须在 Surface 可用之后调用（主线程）。 */
    private fun attachSink(ctx: Context, displayId: Int, surface: Surface) {
        try {
            if (sink != null) return
            val dec = DisplayVideoSink(
                requestedWidth = videoWidth,
                requestedHeight = videoHeight,
                onError = { msg -> Log.w(TAG, "小窗解码失败：$msg") },
            )
            dec.attach(surface)
            sink = dec
            // 阻塞的特权路径，不能放在主线程上（这里正是主线程）
            //
            // 刻意不用 return@Thread（那是"传给构造函数的 lambda 的隐式标签"，脆，见
            // DisplayServer.startPingLoop 的同一条注释）：用 if 包住整段，提前退出不需要标签。
            Thread({
                if (DisplayServer.isRunning()) {
                    DisplayServer.setVideoSink(ctx, displayId, dec.asBinder())
                        .onFailure { Log.w(TAG, "挂载视频回流失败：${it.message}") }
                } else {
                    Log.w(TAG, "服务端不在了，跳过挂载")
                }
            }, "DshDisplayMirrorAttach").start()
        } catch (t: Throwable) {
            Log.w(TAG, "小窗挂载解码器失败：${t.message}", t)
        }
    }

    /** 摘掉当前 sink 并释放解码器（主线程）。[st] 非空时在解码器停用后再放掉它。 */
    private fun detachSink(st: SurfaceTexture? = null) {
        val dec = sink
        if (dec == null) {
            runCatching { st?.release() }
            return
        }
        sink = null
        detach(dec, shownDisplayId, st)
    }

    /**
     * 摘掉 sink 并释放解码器。
     *
     * 只在服务端还活着时才发那一次 detach：`setVideoSink` 内部会走 `start()`，
     * 而服务端已经被停掉之后调它会把服务端**重新拉起来** —— 用户按了停止，
     * 结果因为收一个窗口又活过来，那是最糟的。
     */
    private fun detach(dec: DisplayVideoSink?, id: Int, st: SurfaceTexture? = null) {
        if (dec == null) {
            runCatching { st?.release() }
            return
        }
        val ctx = app
        Thread({
            try {
                if (id > 0 && ctx != null && DisplayServer.isRunning()) {
                    runCatching { DisplayServer.setVideoSink(ctx, id, null) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "摘除视频回流失败：${t.message}", t)
            } finally {
                runCatching { dec.release() }
                // 顺序不能反：SurfaceTexture 必须等解码器停用之后才 release，
                // 否则 MediaCodec 可能还在往一个已经释放的渲染目标写帧。
                // TextureView 的销毁回调返回 false，就是把这次 release 交到这里来做。
                if (st != null) runCatching { st.release() }
            }
        }, "DshDisplayMirrorDetach").start()
    }

    /** 按宽高比算高度，并封顶。 */
    private fun heightFor(width: Int, srcW: Int, srcH: Int, screenH: Int): Int {
        if (srcW <= 0 || srcH <= 0) return (width * 9 / 16).coerceAtLeast(1)
        val byRatio = (width.toLong() * srcH / srcW).toInt()
        val cap = (screenH * 0.6f).toInt().coerceAtLeast(1)
        return min(byRatio, cap).coerceAtLeast(1)
    }
}
