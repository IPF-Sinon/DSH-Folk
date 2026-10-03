package me.bmax.apatch.dsh

import android.content.Context
import android.util.Log
import me.bmax.apatch.R
import me.bmax.apatch.display.IDisplayService
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * 虚拟屏能力的工具面（`/native/display/…`），接进 [DshNativeBridge] 既有的能力档、
 * 严格程度与审计体系 —— 不另建一套平行门禁，否则「通道允许什么」会分裂成两份会漂移的事实。
 *
 * ## 两个显示目标
 *
 * `display=0` 是**真实屏幕**：它能截图、能注入输入，但不需要新建任何东西。正整数是
 * [session] 建出来的**虚拟屏**：一块独立于真实屏幕的显示，可以在上面启动目标 App、
 * 随便点，而不影响用户手上的操作。
 *
 * 哪个动作落在哪种屏上由调用方用 `display` 参数决定：`0` 真实屏，`<id>` 虚拟屏。
 * 不传时：截图取当前会话的虚拟屏（有的话），其余动作同理，都没有则退回 `0`。
 * 响应里始终回带 `display`，所以"我到底操作了哪块屏"是可发现的，不用猜。
 *
 * ## 为什么截图回的是路径而不是字节
 *
 * PNG 动辄两三 MB，塞进 JSON 要 base64 膨胀 33%。这里沿用「拍照」那条既有做法：写进
 * 暂存目录（容器里能按 `/tmp/dsh-native/…` 直接读），只回带路径 —— agent 用普通文件工具
 * 读它就行，和读一张本地图片没有区别。
 *
 * ## 风险
 *
 * 整个能力在 [DshNativeBridge] 里登记为 [PrivRisk.DANGEROUS]：注入输入、启动 App 都能
 * 真实改变设备状态。另按读/写分了档 —— `READ` 只放行截图与查询，点击/滑动/按键/启动 App
 * 要 `READ_WRITE`。也就是说用户可以「让我看，但别动」。
 */
object DshDisplay {

    private const val TAG = "DshDisplay"

    fun handle(
        ctx: Context,
        method: String,
        path: String,
        params: Map<String, String>,
    ): Pair<Int, String> = when {
        method == "GET" && path == "/native/display/status" -> status(ctx)
        method == "POST" && path == "/native/display/session" -> session(ctx, params)
        method == "POST" && path == "/native/display/screenshot" -> screenshot(ctx, params)
        method == "POST" && path == "/native/display/tap" -> tap(ctx, params)
        method == "POST" && path == "/native/display/swipe" -> swipe(ctx, params)
        method == "POST" && path == "/native/display/key" -> key(ctx, params)
        method == "POST" && path == "/native/display/launch" -> launch(ctx, params)
        method == "POST" && path == "/native/display/stop" -> stop(ctx)
        else -> 404 to DshNativeBridge.err(
            DshNativeBridge.str(ctx, R.string.dsh_native_err_unknown_endpoint, method, path),
            "unknown_endpoint",
        )
    }

    // ── 端点 ─────────────────────────────────────────────────────────────────

    /** 服务端在不在、当前会话是哪块屏、以及走的是哪条提权通道。 */
    private fun status(ctx: Context): Pair<Int, String> {
        val reach = PrivilegedShell.reach(ctx)
        val running = DisplayServer.isRunning()
        return 200 to JSONObject()
            .put("ok", true)
            .put("running", running)
            .put("session", DisplayServer.sessionDisplay())
            .put("channel", reach?.channel?.name?.lowercase(Locale.ROOT).orEmpty())
            .put("minSdk", android.os.Build.VERSION.SDK_INT)
            .toString()
    }

    /**
     * 建一块虚拟屏并记为当前会话。重复调用会把**新的**一块设为当前会话 ——
     * 服务端支持多块屏，这里不替调用方去重（它想建几块就建几块，`destroy` 在 stop 里统一收）。
     */
    private fun session(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val dm = ctx.resources.displayMetrics
        val w = params["width"]?.toIntOrNull()?.takeIf { it > 0 } ?: dm.widthPixels
        val h = params["height"]?.toIntOrNull()?.takeIf { it > 0 } ?: dm.heightPixels
        val dpi = params["dpi"]?.toIntOrNull()?.takeIf { it > 0 } ?: dm.densityDpi
        val bitrate = params["bitrate"]?.toIntOrNull()?.takeIf { it > 0 } ?: 0
        // 建屏与会话记账都在 DisplayServer 里（预览界面走的就是同一条路），这里只负责翻成 HTTP。
        val s = DisplayServer.startSession(ctx, w, h, dpi, bitrate)
            .getOrElse { e -> return unavailable(ctx, e.message) }
        return 200 to JSONObject()
            .put("ok", true)
            .put("display", s.displayId)
            .put("width", s.width)
            .put("height", s.height)
            .put("dpi", s.dpi)
            .toString()
    }

    /**
     * 截一张 PNG 存进暂存目录，回带容器内路径与尺寸。
     *
     * 尺寸直接从 PNG 的 IHDR 里读：截图本身不带这些字段，而 agent 要算点击坐标时
     * 需要知道画面有多大（它拿到的是一个路径，不是一张能自己看尺寸的图）。
     */
    private fun screenshot(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val svc = service(ctx) ?: return unavailable(ctx)
        val display = displayOf(params)
        return runCatching {
            val png = svc.requestScreenshot(display)
                ?: return@runCatching 500 to DshNativeBridge.err(
                    DshNativeBridge.str(ctx, R.string.dsh_native_err_display_no_frame),
                    "no_frame",
                )
            if (png.isEmpty()) {
                return@runCatching 500 to DshNativeBridge.err(
                    DshNativeBridge.str(ctx, R.string.dsh_native_err_display_no_frame),
                    "no_frame",
                )
            }
            val dir = DshNativeBridge.stageDir(ctx).apply { mkdirs() }
            val name = "display-${display}-${System.currentTimeMillis()}.png"
            File(dir, name).writeBytes(png)
            DshNativeBridge.trimStage(dir)
            val (pw, ph) = pngSize(png)
            200 to JSONObject()
                .put("ok", true)
                .put("display", display)
                .put("path", DshNativeBridge.stageGuestPath(name))
                .put("bytes", png.size)
                .put("width", pw)
                .put("height", ph)
                .toString()
        }.getOrElse { e -> failure(ctx, e, "screenshot_failed") }
    }

    private fun tap(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val svc = service(ctx) ?: return unavailable(ctx)
        val x = params["x"]?.toFloatOrNull()
            ?: return badParam(ctx, "x")
        val y = params["y"]?.toFloatOrNull()
            ?: return badParam(ctx, "y")
        return runCatching {
            svc.tap(displayOf(params), x, y)
            200 to JSONObject().put("ok", true).put("x", x.toDouble()).put("y", y.toDouble()).toString()
        }.getOrElse { e -> failure(ctx, e, "tap_failed") }
    }

    private fun swipe(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val svc = service(ctx) ?: return unavailable(ctx)
        val x1 = params["x1"]?.toFloatOrNull() ?: return badParam(ctx, "x1")
        val y1 = params["y1"]?.toFloatOrNull() ?: return badParam(ctx, "y1")
        val x2 = params["x2"]?.toFloatOrNull() ?: return badParam(ctx, "x2")
        val y2 = params["y2"]?.toFloatOrNull() ?: return badParam(ctx, "y2")
        val ms = params["duration"]?.toLongOrNull()?.coerceIn(20L, 10_000L) ?: 300L
        return runCatching {
            svc.swipe(displayOf(params), x1, y1, x2, y2, ms)
            200 to JSONObject().put("ok", true).put("durationMs", ms).toString()
        }.getOrElse { e -> failure(ctx, e, "swipe_failed") }
    }

    private fun key(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val svc = service(ctx) ?: return unavailable(ctx)
        val raw = params["key"]?.trim().orEmpty()
        if (raw.isEmpty()) return badParam(ctx, "key")
        val code = raw.toIntOrNull() ?: KEY_NAMES[raw.lowercase(Locale.ROOT)]
            ?: return 400 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_display_bad_key, raw),
                "bad_key",
            )
        return runCatching {
            svc.injectKey(displayOf(params), code)
            200 to JSONObject().put("ok", true).put("keyCode", code).toString()
        }.getOrElse { e -> failure(ctx, e, "key_failed") }
    }

    private fun launch(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val svc = service(ctx) ?: return unavailable(ctx)
        val pkg = params["package"]?.trim().orEmpty()
        if (pkg.isEmpty()) return badParam(ctx, "package")
        if (!pkg.matches(Regex("[A-Za-z0-9_.]+"))) {
            return 400 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_display_bad_package, pkg),
                "bad_package",
            )
        }
        return runCatching {
            val display = displayOf(params)
            svc.launchApp(pkg, display)
            // 记下"这块屏上现在跑的是谁"：悬浮小窗的把手要显示它的图标，
            // 用户抬眼就知道 agent 此刻在哪个 App 里操作（见 DisplayMirror）。
            DisplayServer.noteLaunchedPackage(pkg)
            200 to JSONObject().put("ok", true).put("package", pkg).put("display", display).toString()
        }.getOrElse { e -> failure(ctx, e, "launch_failed") }
    }

    private fun stop(ctx: Context): Pair<Int, String> {
        DisplayServer.stop(ctx)
        return 200 to JSONObject().put("ok", true).put("running", false).toString()
    }

    // ── 内部 ─────────────────────────────────────────────────────────────────

    /**
     * 要操作哪块屏。
     *
     * 显式传 `display` 时听它的（`0` = 真实屏幕）。不传时**优先当前会话的虚拟屏** ——
     * agent 刚建了虚拟屏却截到真实屏幕会很困惑，而且这个错很难自查。响应里始终回带
     * `display`，所以这一点也是可发现的。
     */
    private fun displayOf(params: Map<String, String>): Int =
        params["display"]?.toIntOrNull()?.takeIf { it >= 0 } ?: DisplayServer.sessionDisplay()

    private fun service(ctx: Context): IDisplayService? =
        DisplayServer.start(ctx).getOrElse { e ->
            Log.i(TAG, "虚拟屏服务端不可用：${e.message}")
            null
        }

    /**
     * 服务端起不来时回**具体原因**，而不是笼统的 500：起不来通常意味着"提权通道没就绪"，
     * agent 该做的是提示用户去看那个设置页，而不是重试。
     */
    private fun unavailable(ctx: Context, detail: String? = null): Pair<Int, String> {
        val reach = PrivilegedShell.reach(ctx)
        val reason = when {
            reach == null -> "no_channel"
            !reach.usable -> reach.reason ?: "no_channel"
            else -> "display_server_unavailable"
        }
        // 带上传入的细节（服务端起不来时它才是那句真正有用的话：推送失败第几块、解密失败、
        // 或"进程没起来"）；能力可用性给的是"该怎么补救"，两者拼起来才是完整的一句话。
        val base = DshNativeBridge.str(ctx, R.string.dsh_native_err_display_unavailable)
        val msg = if (detail.isNullOrBlank()) base else "$base（$detail）"
        return 503 to DshNativeBridge.err(msg, reason)
    }

    private fun badParam(ctx: Context, name: String): Pair<Int, String> =
        400 to DshNativeBridge.err(
            DshNativeBridge.str(ctx, R.string.dsh_native_err_display_bad_param, name),
            "bad_param",
        )

    private fun failure(ctx: Context, e: Throwable, reason: String): Pair<Int, String> {
        Log.w(TAG, "虚拟屏操作失败($reason): ${e.message}")
        return 500 to DshNativeBridge.err(
            DshNativeBridge.str(ctx, R.string.dsh_native_err_display_failed, e.message ?: ""),
            reason,
        )
    }

    /** PNG 的 IHDR：偏移 16 起是宽、高，各 4 字节大端。 */
    private fun pngSize(png: ByteArray): Pair<Int, Int> {
        if (png.size < 24) return 0 to 0
        fun be32(at: Int): Int =
            ((png[at].toInt() and 0xff) shl 24) or ((png[at + 1].toInt() and 0xff) shl 16) or
                ((png[at + 2].toInt() and 0xff) shl 8) or (png[at + 3].toInt() and 0xff)
        return be32(16) to be32(20)
    }

    /** 常用按键名 → keycode。数字 keycode 也接受（见 [key]）。 */
    private val KEY_NAMES = mapOf(
        "home" to 3,
        "back" to 4,
        "call" to 5,
        "endcall" to 6,
        "up" to 19,
        "down" to 20,
        "left" to 21,
        "right" to 22,
        "center" to 23,
        "volume_up" to 24,
        "volume_down" to 25,
        "power" to 26,
        "camera" to 27,
        "enter" to 66,
        "del" to 67,
        "menu" to 82,
        "search" to 84,
        "play" to 126,
        "pause" to 127,
        "app_switch" to 187,
        "wakeup" to 224,
        "sleep" to 223,
    )
}
