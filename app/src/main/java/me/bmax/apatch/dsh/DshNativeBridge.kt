package me.bmax.apatch.dsh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import me.bmax.apatch.R
import me.bmax.apatch.ui.MainActivity
import me.bmax.apatch.util.PermissionUtils
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 原生能力桥：容器内的 agent 通过 `/native/` 下的全部端点 让**宿主 App 代它**做只有 Android 侧
 * 才能做的事 —— 发通知、振动、弹 toast、读写剪贴板、拉起分享/打开链接、读设备信息。
 *
 * 端点挂在 [DshFsBridge] 的同一个回环 socket 上，共用它的回环守卫与 token 校验，
 * 所以这里只负责「授权之后允不允许、能不能做成」。
 *
 * ## 为什么必须默认关闭、并且分项
 *
 * 容器里跑的不只是 dsh 本体，还有用户自己从插件商店装的第三方插件。它们共享同一个
 * token（都读 `/root/.dsh/fs-bridge.json`），因此「能调这个接口」等价于「容器内任何代码
 * 都能调」。发通知、读剪贴板、拉起分享面板都是能被滥用的能力，只能由用户显式逐项打开。
 *
 * ## 前台限制不假装成功
 *
 * Android 10 起后台读剪贴板恒返回 null、后台启动 Activity 会被静默丢弃。这两类请求在
 * 后台时直接回 409 + `reason:"not_foreground"`，而不是回一个空串或者假的 ok ——
 * agent 需要知道「现在做不到，让用户把应用切到前台」，不是以为剪贴板真的是空的。
 */
object DshNativeBridge {
    private const val TAG = "DshNativeBridge"

    /** agent 通知的独立渠道。和前台服务的 dsh_harness 分开：那条是 LOW，压根不会提醒。 */
    private const val CHANNEL_ID = "dsh_agent"

    /** agent 通知 id 的基址。1001 是 [HarnessService] 的前台通知，绝不能被覆盖。 */
    private const val NOTIFICATION_ID_BASE = 2000
    private const val MAX_NOTIFICATION_SLOT = 999

    private const val MAX_TEXT_LEN = 4096
    private const val MAX_VIBRATE_MS = 3000L

    /** 主线程操作的等待上限：桥接线程不能被 UI 卡死。 */
    private const val MAIN_WAIT_MS = 3000L

    @Volatile private var channelReady = false

    /**
     * 可分项开关的能力。
     *
     * [id] 会出现在设置项、prefs 与 `/native/capabilities` 的响应里，不要改。
     */
    enum class Cap(val id: String) {
        NOTIFY("notify"),
        TOAST("toast"),
        VIBRATE("vibrate"),
        CLIPBOARD("clipboard"),
        /** 分享面板与「打开链接/文件」共用一项：两者都是拉起外部 Activity。 */
        INTENT("intent"),
        DEVICE("device"),
    }

    // ────────────────────────── 开关 ──────────────────────────

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)

    /** 总开关。默认关。 */
    fun enabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(DshEnv.KEY_NATIVE_BRIDGE, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit { putBoolean(DshEnv.KEY_NATIVE_BRIDGE, on) }
    }

    /** 已启用的能力集合。默认空 —— 开了总开关也还要逐项勾。 */
    fun enabledCaps(ctx: Context): Set<Cap> {
        val raw = prefs(ctx).getString(DshEnv.KEY_NATIVE_CAPS, "") ?: ""
        if (raw.isEmpty()) return emptySet()
        val ids = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return Cap.entries.filter { it.id in ids }.toSet()
    }

    fun setCapEnabled(ctx: Context, cap: Cap, on: Boolean) {
        val next = enabledCaps(ctx).toMutableSet()
        if (on) next.add(cap) else next.remove(cap)
        prefs(ctx).edit {
            putString(DshEnv.KEY_NATIVE_CAPS, next.joinToString(",") { it.id })
        }
    }

    fun capEnabled(ctx: Context, cap: Cap): Boolean = cap in enabledCaps(ctx)

    /**
     * 这项能力当前是否真的能用（权限、系统能力层面），以及不能用的原因 id。
     *
     * 与「用户开没开」分开：通知项开着但系统通知权限没给时，要能说出是后者。
     */
    private fun availability(ctx: Context, cap: Cap): Pair<Boolean, String> = when (cap) {
        Cap.NOTIFY ->
            if (PermissionUtils.hasNotificationPermission(ctx)) true to ""
            else false to "no_notification_permission"
        Cap.VIBRATE ->
            if (vibrator(ctx)?.hasVibrator() == true) true to "" else false to "no_vibrator"
        else -> true to ""
    }

    // ────────────────────────── 分发 ──────────────────────────

    /**
     * 处理一条 `/native/...` 请求。
     *
     * @return `状态码 to JSON 体`；null 表示响应已自行写出（本桥不会出现这种情况）。
     */
    fun handle(
        ctx: Context,
        method: String,
        path: String,
        params: Map<String, String>,
    ): Pair<Int, String> {
        // capabilities 必须在总开关关闭时也能查：否则容器侧只能靠 403 猜是「没开」还是「没这功能」
        if (path == "/native/capabilities") {
            return if (method == "GET") capabilitiesJson(ctx) else methodNotAllowed(method, path)
        }
        if (!enabled(ctx)) {
            return 403 to err("原生能力桥未启用（设置 → 功能 → 原生能力）", "disabled")
        }

        val cap = capOf(path) ?: return 404 to err("未知端点 $method $path", "unknown_endpoint")
        if (!capEnabled(ctx, cap)) {
            return 403 to err("能力「${cap.id}」未启用", "cap_disabled")
        }
        val (ok, why) = availability(ctx, cap)
        if (!ok) return 409 to err("能力「${cap.id}」当前不可用", why)

        return when {
            method == "POST" && path == "/native/notify" -> notify(ctx, params)
            method == "DELETE" && path == "/native/notify" -> cancelNotify(ctx, params)
            method == "POST" && path == "/native/toast" -> toast(ctx, params)
            method == "POST" && path == "/native/vibrate" -> vibrate(ctx, params)
            method == "POST" && path == "/native/clipboard" -> clipboardSet(ctx, params)
            method == "GET" && path == "/native/clipboard" -> clipboardGet(ctx)
            method == "POST" && path == "/native/share" -> share(ctx, params)
            method == "POST" && path == "/native/open" -> open(ctx, params)
            method == "GET" && path == "/native/device" -> device(ctx)
            else -> methodNotAllowed(method, path)
        }
    }

    private fun capOf(path: String): Cap? = when (path) {
        "/native/notify" -> Cap.NOTIFY
        "/native/toast" -> Cap.TOAST
        "/native/vibrate" -> Cap.VIBRATE
        "/native/clipboard" -> Cap.CLIPBOARD
        "/native/share", "/native/open" -> Cap.INTENT
        "/native/device" -> Cap.DEVICE
        else -> null
    }

    private fun capabilitiesJson(ctx: Context): Pair<Int, String> {
        val on = enabled(ctx)
        val caps = JSONObject()
        for (cap in Cap.entries) {
            val (available, why) = availability(ctx, cap)
            caps.put(
                cap.id,
                JSONObject()
                    .put("enabled", on && capEnabled(ctx, cap))
                    .put("available", available)
                    .put("reason", why),
            )
        }
        return 200 to JSONObject()
            .put("ok", true)
            .put("bridgeEnabled", on)
            .put("foreground", isForeground(ctx))
            .put("caps", caps)
            .toString()
    }

    // ────────────────────────── 能力实现 ──────────────────────────

    private fun notify(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val title = text(params["title"]) ?: return 400 to err("缺 title", "missing_title")
        val body = text(params["body"]) ?: ""
        val slot = (params["id"]?.toIntOrNull() ?: 0).coerceIn(0, MAX_NOTIFICATION_SLOT)
        val ongoing = params["ongoing"] == "1"
        val id = NOTIFICATION_ID_BASE + slot

        ensureChannel(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
            ?: return 500 to err("拿不到 NotificationManager", "no_service")
        return runCatching {
            nm.notify(id, buildNotification(ctx, title, body, ongoing))
            200 to JSONObject().put("ok", true).put("id", slot).toString()
        }.getOrElse { e ->
            Log.w(TAG, "notify 失败: ${e.message}")
            500 to err("发送通知失败：${e.message}", "notify_failed")
        }
    }

    private fun cancelNotify(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val slot = (params["id"]?.toIntOrNull() ?: 0).coerceIn(0, MAX_NOTIFICATION_SLOT)
        runCatching {
            ctx.getSystemService(NotificationManager::class.java)
                ?.cancel(NOTIFICATION_ID_BASE + slot)
        }
        return 200 to JSONObject().put("ok", true).put("id", slot).toString()
    }

    private fun buildNotification(
        ctx: Context,
        title: String,
        body: String,
        ongoing: Boolean,
    ): Notification {
        val open = PendingIntent.getActivity(
            ctx,
            NOTIFICATION_ID_BASE,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(open)
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun ensureChannel(ctx: Context) {
        if (channelReady) return
        runCatching {
            val ch = NotificationChannel(
                CHANNEL_ID,
                ctx.getString(R.string.dsh_native_notif_channel_name),
                // 与前台服务那条 IMPORTANCE_LOW 分开：agent 主动发的通知本来就是要人看见的
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = ctx.getString(R.string.dsh_native_notif_channel_desc)
            }
            ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
            channelReady = true
        }
    }

    private fun toast(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val msg = text(params["text"]) ?: return 400 to err("缺 text", "missing_text")
        // Toast 必须有 Looper；桥接线程没有
        onMain { me.bmax.apatch.util.ui.showToast(ctx, msg) }
        return 200 to okJson()
    }

    private fun vibrate(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val ms = (params["ms"]?.toLongOrNull() ?: 30L).coerceIn(1L, MAX_VIBRATE_MS)
        val amplitude = (params["amplitude"]?.toIntOrNull() ?: -1).let {
            if (it < 0) VibrationEffect.DEFAULT_AMPLITUDE else it.coerceIn(1, 255)
        }
        val v = vibrator(ctx) ?: return 500 to err("拿不到 Vibrator", "no_service")
        return runCatching {
            v.vibrate(VibrationEffect.createOneShot(ms, amplitude))
            200 to JSONObject().put("ok", true).put("ms", ms).toString()
        }.getOrElse { e ->
            Log.w(TAG, "vibrate 失败: ${e.message}")
            500 to err("振动失败：${e.message}", "vibrate_failed")
        }
    }

    /**
     * 不复用 [me.bmax.apatch.util.VibrationManager.vibrate]：那条被 UI 的
     * 「触感反馈」开关挡着、且时长固定 30ms，是给按钮点击用的。
     */
    private fun vibrator(ctx: Context): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }.getOrNull()

    private fun clipboardSet(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val value = text(params["text"]) ?: return 400 to err("缺 text", "missing_text")
        val label = text(params["label"]) ?: "DSH"
        var failure: String? = null
        onMain {
            runCatching {
                clipboard(ctx)?.setPrimaryClip(ClipData.newPlainText(label, value))
                    ?: run { failure = "no_service" }
            }.onFailure { e -> failure = e.message ?: "set_failed" }
        }
        val f = failure
        return if (f == null) 200 to okJson() else 500 to err("写剪贴板失败：$f", "clipboard_failed")
    }

    private fun clipboardGet(ctx: Context): Pair<Int, String> {
        // Android 10 起后台读剪贴板恒为 null，别把它当成「剪贴板是空的」
        if (!isForeground(ctx)) {
            return 409 to err("读剪贴板需要应用处于前台，请让用户先切回 DSH-Folk", "not_foreground")
        }
        var value: String? = null
        var present = false
        onMain {
            runCatching {
                val clip = clipboard(ctx)?.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    present = true
                    value = clip.getItemAt(0).coerceToText(ctx).toString()
                }
            }.onFailure { e -> Log.w(TAG, "读剪贴板失败: ${e.message}") }
        }
        return 200 to JSONObject()
            .put("ok", true)
            .put("empty", !present)
            .put("text", value ?: "")
            .toString()
    }

    private fun clipboard(ctx: Context): ClipboardManager? =
        ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    private fun share(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val value = text(params["text"]) ?: return 400 to err("缺 text", "missing_text")
        val title = text(params["title"])
        if (!isForeground(ctx)) return backgroundActivityDenied()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, value)
            if (title != null) putExtra(Intent.EXTRA_SUBJECT, title)
        }
        return startChooser(ctx, Intent.createChooser(send, title))
    }

    private fun open(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val url = text(params["url"]) ?: return 400 to err("缺 url", "missing_url")
        // 只放 http/https：file:// 要经 FileProvider 换 content URI，而 intent:// 之类
        // 能被用来拉起任意组件，不是这个接口该提供的能力
        val lower = url.lowercase(Locale.US)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return 400 to err("只支持 http/https", "unsupported_scheme")
        }
        if (!isForeground(ctx)) return backgroundActivityDenied()
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull()
            ?: return 400 to err("url 无法解析", "bad_url")
        return startChooser(ctx, Intent(Intent.ACTION_VIEW, uri))
    }

    private fun startChooser(ctx: Context, intent: Intent): Pair<Int, String> =
        runCatching {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            200 to okJson()
        }.getOrElse { e ->
            Log.w(TAG, "startActivity 失败: ${e.message}")
            500 to err("拉起失败：${e.message}", "start_failed")
        }

    private fun backgroundActivityDenied(): Pair<Int, String> =
        409 to err("拉起界面需要应用处于前台，请让用户先切回 DSH-Folk", "not_foreground")

    private fun device(ctx: Context): Pair<Int, String> {
        val json = JSONObject()
            .put("ok", true)
            .put("brand", Build.BRAND)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("release", Build.VERSION.RELEASE)
            .put("abis", Build.SUPPORTED_ABIS.joinToString(","))
            .put("locale", Locale.getDefault().toLanguageTag())
            .put("foreground", isForeground(ctx))
        runCatching {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            if (bm != null) {
                json.put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                json.put("charging", bm.isCharging)
            }
        }
        runCatching {
            val status = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            if (status > 0) json.put("batteryTempC", status / 10.0)
        }
        return 200 to json.toString()
    }

    // ────────────────────────── 工具 ──────────────────────────

    /**
     * 进程是否有可见 Activity。
     *
     * 用 `getMyMemoryState` 而不是 ProcessLifecycleOwner：前者可以在任意线程读，而
     * 桥接请求跑在自己的连接线程上。前台服务给的是 IMPORTANCE_FOREGROUND_SERVICE(125)，
     * 只有真的有 Activity 在前面才是 IMPORTANCE_FOREGROUND(100) —— 正好是需要的区分。
     */
    private fun isForeground(ctx: Context): Boolean = runCatching {
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(false)

    /** 在主线程跑一段并等它结束（有超时，不让桥接线程被 UI 卡死）。 */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        runCatching { latch.await(MAIN_WAIT_MS, TimeUnit.MILLISECONDS) }
    }

    /** 文本参数：空串归为「没给」，并截断到 [MAX_TEXT_LEN]。 */
    private fun text(v: String?): String? {
        if (v.isNullOrEmpty()) return null
        return if (v.length > MAX_TEXT_LEN) v.substring(0, MAX_TEXT_LEN) else v
    }

    private fun okJson(): String = JSONObject().put("ok", true).toString()

    /** 错误体带机器可读的 [reason]：agent 需要据此决定是重试还是提示用户。 */
    private fun err(msg: String, reason: String): String = JSONObject()
        .put("ok", false)
        .put("error", msg)
        .put("reason", reason)
        .toString()

    private fun methodNotAllowed(method: String, path: String): Pair<Int, String> =
        404 to err("未知端点 $method $path", "unknown_endpoint")
}
