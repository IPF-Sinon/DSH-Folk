package me.bmax.apatch.dsh

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 虚拟屏的**悬浮小窗**。
 *
 * ## 它解决什么
 *
 * agent 在虚拟屏上操作时，用户在自己手机上**什么都看不到** —— 那些点击落在哪、画面现在
 * 什么样，只能靠用户自己想起来去设置里开预览页。而 agent 做的事常常正是要用户看着的
 * （点外卖、填表单）。所以建出一块虚拟屏之后，就把画面放进一个小窗口挂在屏幕上，用户
 * 一抬眼就看得到。
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
 * 这一点是刻意的：小窗存在的意义就是「看 agent 正在看的那块」。要是它自己建一块，
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
 * 窗口与 View 只能在主线程碰（[main]）；[DisplayServer.setVideoSink] 会走特权启动路径，
 * 是阻塞的，必须放到后台线程 —— 这条界线在本文件里每一处都守着。
 */
object DisplayMirror {

    private const val TAG = "DshDisplayMirror"

    /** 小窗宽度占屏宽的比例：太小看不清画面，太大挡住用户自己的界面。 */
    private const val WIDTH_FRACTION = 0.42f

    /** 高度上限占屏高的比例：竖屏按宽高比算出来的高度会顶到屏幕顶部。 */
    private const val MAX_HEIGHT_FRACTION = 0.6f

    /** 小窗距屏幕边缘的留白。 */
    private const val MARGIN_DP = 8

    private val main = Handler(Looper.getMainLooper())

    /** 应用级 Context。桥的后台线程会写它，主线程会读，所以是 volatile。 */
    @Volatile
    private var app: Context? = null
    private var windowManager: WindowManager? = null
    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var sink: DisplayVideoSink? = null

    /** 小窗里挂的是哪块屏。0 = 没显示。 */
    private var shownDisplayId = 0

    /** 预览页占用期间为 true：不是"不显示"，是"先让位"，别把它当成用户关掉了。 */
    @Volatile
    private var suspended = false

    /** 用户手动关掉过的那块屏（点小窗上的 ✕）。下次建屏要重新给他看，所以只在同一块屏上生效。 */
    @Volatile
    private var dismissed = 0

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
            if (root != null && shownDisplayId == s.displayId) return
            show(ctx, s)
        } catch (t: Throwable) {
            Log.w(TAG, "同步悬浮小窗失败：${t.message}", t)
        }
    }

    /** 收起小窗（不动「用户手动关过」这条记忆）。只在主线程调用。 */
    private fun hide() {
        val dec = sink
        val id = shownDisplayId
        sink = null
        shownDisplayId = 0
        val view = root
        root = null
        params = null
        if (view != null) runCatching { windowManager?.removeView(view) }
        detach(dec, id)
    }

    /**
     * 服务端停了/死了：收起小窗，并且忘掉「用户手动关过」——
     * 下一次建屏是全新的一轮，该重新给他看。
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
     * 反例是把它 post 到主线程：连调两次工具时第二次看到的 `root` 还是 null，
     * 于是会再 post 一次，屏幕上就会出现两个小窗。
     */
    private fun show(ctx: Context, s: DisplayServer.Session) {
        // 先清掉可能残留的旧窗口：换一块屏时不能留下上一次的画面
        hide()
        run {
            try {
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    ?: return@run
                windowManager = wm
                val dm = ctx.resources.displayMetrics
                val density = dm.density
                val margin = (MARGIN_DP * density).roundToInt()

                val w = (dm.widthPixels * WIDTH_FRACTION).roundToInt().coerceAtLeast(1)
                val h = heightFor(w, s.width, s.height, dm.heightPixels)

                val tv = TextureView(ctx)
                val close = TextView(ctx).apply {
                    text = "✕"
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.argb(150, 0, 0, 0))
                    textSize = 12f
                    gravity = Gravity.CENTER
                    val pad = (6 * density).roundToInt()
                    setPadding(pad, pad, pad, pad)
                }
                val box = FrameLayout(ctx).apply {
                    setBackgroundColor(Color.argb(220, 24, 24, 24))
                    addView(
                        tv,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        ),
                    )
                    val closeSize = (22 * density).roundToInt()
                    addView(
                        close,
                        FrameLayout.LayoutParams(closeSize, closeSize).apply {
                            gravity = Gravity.TOP or Gravity.END
                        },
                    )
                }

                val p = WindowManager.LayoutParams(
                    w,
                    h,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    // NOT_FOCUSABLE 是必须的：小窗一旦拿到焦点，被 agent 操作的那个应用就会
                    // 失去焦点，输入法也会打到小窗这边来。它只负责"显示"，不参与输入。
                    //
                    // HARDWARE_ACCELERATED 是给 TextureView 的：它拿不到硬件加速的 canvas 会
                    // 直接抛（"TextureView requires hardware acceleration"）。本应用在清单里
                    // 默认就开着，但这是**非 Activity 窗口**，显式写上比依赖默认值稳。
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    x = margin
                    y = margin
                }

                close.setOnClickListener {
                    // 用户明确不要看这一块了：记下来，别在每次工具调用后再把它弹回来
                    dismissed = s.displayId
                    hide()
                }

                // 拖动：落点记的是手指相对屏幕的位置，移动时按差值挪窗口，避免"按哪儿就跳哪儿"
                box.setOnTouchListener(object : View.OnTouchListener {
                    private var downX = 0f
                    private var downY = 0f
                    private var startX = 0
                    private var startY = 0

                    override fun onTouch(v: View, e: MotionEvent): Boolean {
                        when (e.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                downX = e.rawX
                                downY = e.rawY
                                startX = p.x
                                startY = p.y
                                return true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                p.x = startX + (e.rawX - downX).roundToInt()
                                p.y = startY + (e.rawY - downY).roundToInt()
                                runCatching { wm.updateViewLayout(box, p) }
                                return true
                            }
                        }
                        return false
                    }
                })

                // SurfaceTexture 要等视图真的挂上才可用，所以解码器在回调里建，而不是这里
                tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                        attachSink(ctx, s, st)
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        // 交给系统回收，同时把解码器摘掉：surface 没了还继续解会直接报错
                        detach(sink?.also { sink = null }, shownDisplayId)
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }

                wm.addView(box, p)
                root = box
                params = p
                shownDisplayId = s.displayId
            } catch (t: Throwable) {
                // 没有悬浮窗权限、厂商 ROM 拦了 OVERLAY 类型等，都会走到这里。
                // 只记日志：agent 那一次调用已经成功了，不能被这个副作用拖下水。
                Log.w(TAG, "悬浮小窗建不起来（虚拟屏本身正常）：${t.message}", t)
                hide()
            }
        }
    }

    /** 解码器 + 服务端挂载。必须在 SurfaceTexture 可用之后调用。 */
    private fun attachSink(ctx: Context, s: DisplayServer.Session, st: SurfaceTexture) {
        try {
            if (sink != null) return
            val surface = Surface(st)
            val dec = DisplayVideoSink(
                requestedWidth = s.width,
                requestedHeight = s.height,
                onError = { msg -> Log.w(TAG, "小窗解码失败：$msg") },
                onSize = { w, h -> resizeToDecoded(w, h) },
            )
            dec.attach(surface)
            sink = dec
            // 阻塞的特权路径，不能放在主线程上（这里正是主线程）
            //
            // 刻意不用 return@Thread（那是"传给构造函数的 lambda 的隐式标签"，脆，见
            // DisplayServer.startPingLoop 的同一条注释）：用 if 包住整段，提前退出就不需要标签。
            Thread({
                if (DisplayServer.isRunning()) {
                    DisplayServer.setVideoSink(ctx, s.displayId, dec.asBinder())
                        .onFailure { Log.w(TAG, "挂载视频回流失败：${it.message}") }
                } else {
                    Log.w(TAG, "服务端不在了，跳过挂载")
                }
            }, "DshDisplayMirrorAttach").start()
        } catch (t: Throwable) {
            Log.w(TAG, "小窗挂载解码器失败：${t.message}", t)
        }
    }

    /**
     * 摘掉 sink 并释放解码器。
     *
     * 只在服务端还活着时才发那一次 detach：`setVideoSink` 内部会走 `start()`，
     * 而服务端已经被停掉之后调它会把服务端**重新拉起来** —— 用户按了停止，
     * 结果因为收一个窗口又活过来，那是最糟的。
     */
    private fun detach(dec: DisplayVideoSink?, id: Int) {
        if (dec == null) return
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
            }
        }, "DshDisplayMirrorDetach").start()
    }

    /** 解出来的真实尺寸由 SPS 决定，可能和会话报的不一样（服务端会对齐到编码块）。 */
    private fun resizeToDecoded(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        main.post {
            val p = params ?: return@post
            val box = root ?: return@post
            val wm = windowManager ?: return@post
            try {
                val dm = app?.resources?.displayMetrics ?: return@post
                p.height = heightFor(p.width, w, h, dm.heightPixels)
                runCatching { wm.updateViewLayout(box, p) }
            } catch (t: Throwable) {
                Log.w(TAG, "调整小窗尺寸失败：${t.message}", t)
            }
        }
    }

    /** 按宽高比算高度，并封顶。 */
    private fun heightFor(width: Int, srcW: Int, srcH: Int, screenH: Int): Int {
        if (srcW <= 0 || srcH <= 0) return (width * 9 / 16).coerceAtLeast(1)
        val byRatio = (width.toLong() * srcH / srcW).toInt()
        val cap = (screenH * MAX_HEIGHT_FRACTION).toInt().coerceAtLeast(1)
        return min(byRatio, cap).coerceAtLeast(1)
    }
}
