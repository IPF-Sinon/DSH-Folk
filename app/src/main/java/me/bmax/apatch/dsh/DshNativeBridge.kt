package me.bmax.apatch.dsh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import me.bmax.apatch.R
import me.bmax.apatch.util.appString
import me.bmax.apatch.ui.MainActivity
import me.bmax.apatch.util.PermissionUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
        /** 媒体库（相册/视频/音频）的读取。 */
        MEDIA("media"),
        /** 麦克风录音。 */
        MIC("mic"),
    }

    /**
     * 这项能力需要的**运行时**权限（可以直接 `requestPermissions` 的那种）。
     *
     * 空数组表示不需要任何运行时权限。注意「所有文件访问」不在这里 —— 它是 appop
     * 特殊权限，申请不到，只能跳系统设置页（见「功能」设置页的共享存储那一行）。
     */
    fun runtimePermissions(cap: Cap): Array<String> = when (cap) {
        Cap.NOTIFY ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyArray()
            }
        Cap.MEDIA -> PermissionUtils.mediaPermissions()
        Cap.MIC -> arrayOf(android.Manifest.permission.RECORD_AUDIO)
        else -> emptyArray()
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
        // 只要有一类媒体可读就算可用：用户可能只给了照片。具体缺哪一类由
        // /native/media/list 的 granted 字段说明，不在这里一刀切成不可用。
        Cap.MEDIA ->
            if (PermissionUtils.hasAnyMediaPermission(ctx)) true to ""
            else false to "no_media_permission"
        Cap.MIC ->
            if (!PermissionUtils.hasMicrophonePermission(ctx)) false to "no_audio_permission"
            else if (!hasMicrophone(ctx)) false to "no_microphone"
            else true to ""
        else -> true to ""
    }

    private fun hasMicrophone(ctx: Context): Boolean = runCatching {
        ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_MICROPHONE)
    }.getOrDefault(true)

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
            return if (method == "GET") capabilitiesJson(ctx) else methodNotAllowed(ctx, method, path)
        }
        if (!enabled(ctx)) {
            return 403 to err(str(ctx, R.string.dsh_native_err_disabled), "disabled")
        }

        val cap = capOf(path)
            ?: return 404 to err(
                str(ctx, R.string.dsh_native_err_unknown_endpoint, method, path),
                "unknown_endpoint",
            )
        if (!capEnabled(ctx, cap)) {
            return 403 to err(
                str(ctx, R.string.dsh_native_err_cap_disabled, capName(ctx, cap)),
                "cap_disabled",
            )
        }
        val (ok, why) = availability(ctx, cap)
        if (!ok) {
            return 409 to err(
                str(ctx, R.string.dsh_native_err_cap_unavailable, capName(ctx, cap)),
                why,
            )
        }

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
            method == "GET" && path == "/native/media/list" -> mediaList(ctx, params)
            method == "GET" && path == "/native/media/read" -> mediaRead(ctx, params)
            method == "POST" && path == "/native/mic/record" -> micRecord(ctx, params)
            else -> methodNotAllowed(ctx, method, path)
        }
    }

    /**
     * 能力的**本地化**名字，用在给用户看的报错里。
     *
     * 不直接用 [Cap.id]：那是协议 id（`clipboard`），报错里该出现的是「剪贴板」。
     * 名字表放 UI 层（`nativeCapTitleRes`）会让 dsh 包依赖 ui 包，所以这里单独映射。
     */
    private fun capName(ctx: Context, cap: Cap): String = str(
        ctx,
        when (cap) {
            Cap.NOTIFY -> R.string.dsh_native_cap_notify
            Cap.TOAST -> R.string.dsh_native_cap_toast
            Cap.VIBRATE -> R.string.dsh_native_cap_vibrate
            Cap.CLIPBOARD -> R.string.dsh_native_cap_clipboard
            Cap.INTENT -> R.string.dsh_native_cap_intent
            Cap.DEVICE -> R.string.dsh_native_cap_device
            Cap.MEDIA -> R.string.dsh_native_cap_media
            Cap.MIC -> R.string.dsh_native_cap_mic
        },
    )

    private fun capOf(path: String): Cap? = when (path) {
        "/native/notify" -> Cap.NOTIFY
        "/native/toast" -> Cap.TOAST
        "/native/vibrate" -> Cap.VIBRATE
        "/native/clipboard" -> Cap.CLIPBOARD
        "/native/share", "/native/open" -> Cap.INTENT
        "/native/device" -> Cap.DEVICE
        "/native/media/list", "/native/media/read" -> Cap.MEDIA
        "/native/mic/record" -> Cap.MIC
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
        val title = text(params["title"]) ?: return 400 to err(str(ctx, R.string.dsh_native_err_missing_param, "title"), "missing_title")
        val body = text(params["body"]) ?: ""
        val slot = (params["id"]?.toIntOrNull() ?: 0).coerceIn(0, MAX_NOTIFICATION_SLOT)
        val ongoing = params["ongoing"] == "1"
        val id = NOTIFICATION_ID_BASE + slot

        ensureChannel(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
            ?: return 500 to err(str(ctx, R.string.dsh_native_err_no_service, "NotificationManager"), "no_service")
        return runCatching {
            nm.notify(id, buildNotification(ctx, title, body, ongoing))
            200 to JSONObject().put("ok", true).put("id", slot).toString()
        }.getOrElse { e ->
            Log.w(TAG, "notify 失败: ${e.message}")
            500 to err(str(ctx, R.string.dsh_native_err_notify_failed, e.message ?: ""), "notify_failed")
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
                ctx.appString(R.string.dsh_native_notif_channel_name),
                // 与前台服务那条 IMPORTANCE_LOW 分开：agent 主动发的通知本来就是要人看见的
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = ctx.appString(R.string.dsh_native_notif_channel_desc)
            }
            ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
            channelReady = true
        }
    }

    private fun toast(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val msg = text(params["text"])
            ?: return 400 to err(str(ctx, R.string.dsh_native_err_missing_param, "text"), "missing_text")
        // Toast 必须有 Looper；桥接线程没有
        onMain { me.bmax.apatch.util.ui.showToast(ctx, msg) }
        return 200 to okJson()
    }

    private fun vibrate(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val ms = (params["ms"]?.toLongOrNull() ?: 30L).coerceIn(1L, MAX_VIBRATE_MS)
        val amplitude = (params["amplitude"]?.toIntOrNull() ?: -1).let {
            if (it < 0) VibrationEffect.DEFAULT_AMPLITUDE else it.coerceIn(1, 255)
        }
        val v = vibrator(ctx)
            ?: return 500 to err(str(ctx, R.string.dsh_native_err_no_service, "Vibrator"), "no_service")
        return runCatching {
            v.vibrate(VibrationEffect.createOneShot(ms, amplitude))
            200 to JSONObject().put("ok", true).put("ms", ms).toString()
        }.getOrElse { e ->
            Log.w(TAG, "vibrate 失败: ${e.message}")
            500 to err(str(ctx, R.string.dsh_native_err_vibrate_failed, e.message ?: ""), "vibrate_failed")
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
        val value = text(params["text"])
            ?: return 400 to err(str(ctx, R.string.dsh_native_err_missing_param, "text"), "missing_text")
        val label = text(params["label"]) ?: "DSH"
        var failure: String? = null
        onMain {
            runCatching {
                clipboard(ctx)?.setPrimaryClip(ClipData.newPlainText(label, value))
                    ?: run { failure = "no_service" }
            }.onFailure { e -> failure = e.message ?: "set_failed" }
        }
        val f = failure
        return if (f == null) 200 to okJson()
        else 500 to err(str(ctx, R.string.dsh_native_err_clipboard_write, f), "clipboard_failed")
    }

    private fun clipboardGet(ctx: Context): Pair<Int, String> {
        // Android 10 起后台读剪贴板恒为 null，别把它当成「剪贴板是空的」
        if (!isForeground(ctx)) {
            return 409 to err(str(ctx, R.string.dsh_native_err_clipboard_background), "not_foreground")
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
        val value = text(params["text"])
            ?: return 400 to err(str(ctx, R.string.dsh_native_err_missing_param, "text"), "missing_text")
        val title = text(params["title"])
        if (!isForeground(ctx)) return backgroundActivityDenied(ctx)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, value)
            if (title != null) putExtra(Intent.EXTRA_SUBJECT, title)
        }
        return startChooser(ctx, Intent.createChooser(send, title))
    }

    private fun open(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val url = text(params["url"])
            ?: return 400 to err(str(ctx, R.string.dsh_native_err_missing_param, "url"), "missing_url")
        // 只放 http/https：file:// 要经 FileProvider 换 content URI，而 intent:// 之类
        // 能被用来拉起任意组件，不是这个接口该提供的能力
        val lower = url.lowercase(Locale.US)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return 400 to err(str(ctx, R.string.dsh_native_err_scheme), "unsupported_scheme")
        }
        if (!isForeground(ctx)) return backgroundActivityDenied(ctx)
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull()
            ?: return 400 to err(str(ctx, R.string.dsh_native_err_bad_url), "bad_url")
        return startChooser(ctx, Intent(Intent.ACTION_VIEW, uri))
    }

    private fun startChooser(ctx: Context, intent: Intent): Pair<Int, String> =
        runCatching {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            200 to okJson()
        }.getOrElse { e ->
            Log.w(TAG, "startActivity 失败: ${e.message}")
            500 to err(str(ctx, R.string.dsh_native_err_start_failed, e.message ?: ""), "start_failed")
        }

    private fun backgroundActivityDenied(ctx: Context): Pair<Int, String> =
        409 to err(str(ctx, R.string.dsh_native_err_activity_background), "not_foreground")

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

    // ────────────────────────── 媒体与麦克风 ──────────────────────────

    /**
     * 为什么媒体与录音的结果**落到容器里**，而不是流式回给调用方。
     *
     * 这两个端点和其它端点的形状不同：它们的产物是二进制，而 [handle] 的返回是
     * `状态码 to JSON`。要流式回传就得把 socket 一路传进来，与 [DshFsBridge] 的
     * 响应生命周期纠缠在一起（那条路上已经踩过一次：写了头就再也改不回 JSON 错误）。
     *
     * 换个思路：容器的 rootfs 本来就是本 App 的私有目录，写它**不需要任何存储权限**。
     * 于是把字节落到 `rootfs/tmp/...`，把**容器内**路径回给调用方，agent 直接用普通
     * 文件工具读 —— 少一条二进制通道，也少一次 base64 膨胀。
     */
    private const val STAGE_DIR_NAME = "dsh-native"

    /** 暂存区里保留的文件数上限：超出就删最旧的，别让 rootfs 被无声吃满。 */
    private const val STAGE_KEEP = 32

    private const val DEFAULT_MEDIA_LIMIT = 50
    private const val MAX_MEDIA_LIMIT = 500

    /** 单次取媒体的字节上限：容器里读它还要再占一份，64MB 已经很宽松。 */
    private const val MAX_MEDIA_BYTES = 64L * 1024 * 1024

    private const val DEFAULT_RECORD_MS = 5_000L

    /**
     * 录音时长上限。
     *
     * 30 秒不是协议限制（`soTimeout` 只管阻塞读，写响应前handler 慢多久都不会被它掐断），
     * 而是三件事的交集：请求占着一条连接线程直到录完；用户必须一直把应用留在前台，
     * 否则后半段全是静音；`agent` 想要更长的录音应该分多段。真需要更长再谈。
     */
    private const val MAX_RECORD_MS = 30_000L

    /**
     * 录音互斥。
     *
     * `MediaRecorder` 抢的是全局音频输入：第二个请求会在 `start()` 抛
     * `IllegalStateException`，而两个请求都已经建好文件 —— 报错的那个留下一个 0 字节
     * 残骸，成功的那个时长莫名变短。用一个标志直接回 409，比事后清理干净。
     */
    private val recording = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun stageDir(ctx: Context): File =
        File(DshEnv.tmpDir(ctx), STAGE_DIR_NAME).apply { mkdirs() }

    /** 暂存目录对应的**容器内**路径（rootfs/tmp → /tmp）。 */
    private fun stageGuestPath(name: String): String = "/tmp/$STAGE_DIR_NAME/$name"

    /** 只保留最近 [STAGE_KEEP] 个文件。 */
    private fun trimStage(dir: File) {
        runCatching {
            val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }
                ?: return
            val excess = files.size - STAGE_KEEP
            if (excess > 0) files.take(excess).forEach { it.delete() }
        }
    }

    /** 文件名里只留安全字符：这个名字会拼进容器路径。 */
    private fun safeName(raw: String, fallbackExt: String): String {
        val cleaned = raw.map { c ->
            if (c.isLetterOrDigit() || c == '.' || c == '-' || c == '_') c else '_'
        }.joinToString("").trim('.', '_')
        val name = cleaned.ifEmpty { "media" }
        return if (name.contains('.')) name.take(96) else "${name.take(90)}.$fallbackExt"
    }

    private fun mediaTypeOf(raw: String?): PermissionUtils.MediaType? =
        PermissionUtils.MediaType.entries.firstOrNull { it.id == (raw ?: "image") }

    private fun mediaCollection(type: PermissionUtils.MediaType) = when (type) {
        PermissionUtils.MediaType.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        PermissionUtils.MediaType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        PermissionUtils.MediaType.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

    /**
     * 列媒体库。
     *
     * 走 MediaStore 而不是直接遍历 `/sdcard/DCIM`：后者需要「所有文件访问」，而
     * `READ_MEDIA_*` 只授权前者。返回里带 `granted` —— 用户可能只给了照片，
     * agent 得能看出「音频不是没有，是没授权」。
     */
    private fun mediaList(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val type = mediaTypeOf(params["type"])
            ?: return 400 to err(str(ctx, R.string.dsh_native_err_bad_media_type), "bad_type")
        if (!PermissionUtils.hasMediaPermission(ctx, type)) {
            return 403 to err(
                str(ctx, R.string.dsh_native_err_media_type_denied, type.id),
                "no_media_permission",
            )
        }
        val limit = (params["limit"]?.toIntOrNull() ?: DEFAULT_MEDIA_LIMIT)
            .coerceIn(1, MAX_MEDIA_LIMIT)
        val nameLike = text(params["q"])

        val cols = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cols += MediaStore.MediaColumns.RELATIVE_PATH
        }

        val where = if (nameLike != null) "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?" else null
        val args = if (nameLike != null) arrayOf("%$nameLike%") else null
        val order = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        val entries = JSONArray()
        val queryResult = runCatching {
            ctx.contentResolver.query(
                mediaCollection(type), cols.toTypedArray(), where, args, order,
            )?.use { c ->
                val idIdx = c.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameIdx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dateIdx = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val mimeIdx = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val relIdx = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                while (c.moveToNext() && entries.length() < limit) {
                    val o = JSONObject()
                        .put("id", if (idIdx >= 0) c.getLong(idIdx) else -1L)
                        .put("name", if (nameIdx >= 0) c.getString(nameIdx) ?: "" else "")
                        .put("size", if (sizeIdx >= 0) c.getLong(sizeIdx) else -1L)
                        // MediaStore 的 DATE_MODIFIED 是**秒**，不是毫秒
                        .put("mtime", if (dateIdx >= 0) c.getLong(dateIdx) * 1000L else 0L)
                        .put("mime", if (mimeIdx >= 0) c.getString(mimeIdx) ?: "" else "")
                    if (relIdx >= 0) o.put("dir", c.getString(relIdx) ?: "")
                    entries.put(o)
                }
            }
        }
        queryResult.onFailure { e ->
            Log.w(TAG, "media 查询失败: ${e.message}")
            return 500 to err(
                str(ctx, R.string.dsh_native_err_media_query, e.message ?: ""),
                "query_failed",
            )
        }

        val granted = JSONArray()
        for (t in PermissionUtils.grantedMediaTypes(ctx)) granted.put(t.id)
        return 200 to JSONObject()
            .put("ok", true)
            .put("type", type.id)
            .put("granted", granted)
            .put("entries", entries)
            .toString()
    }

    /**
     * 取一条媒体的字节，落到容器暂存区。
     *
     * 用 `openInputStream(content://…)` 而不是拼 `_DATA` 路径：分区存储下后者可能
     * 根本读不到，而 ContentResolver 走的正是 `READ_MEDIA_*` 授权的那条路。
     */
    private fun mediaRead(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val type = mediaTypeOf(params["type"])
            ?: return 400 to err(str(ctx, R.string.dsh_native_err_bad_media_type), "bad_type")
        if (!PermissionUtils.hasMediaPermission(ctx, type)) {
            return 403 to err(
                str(ctx, R.string.dsh_native_err_media_type_denied, type.id),
                "no_media_permission",
            )
        }
        val id = params["id"]?.toLongOrNull()
            ?: return 400 to err(str(ctx, R.string.dsh_native_err_missing_id), "missing_id")
        val uri = ContentUris.withAppendedId(mediaCollection(type), id)

        var displayName = "media"
        var declaredSize = -1L
        runCatching {
            ctx.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    displayName = c.getString(0) ?: displayName
                    declaredSize = c.getLong(1)
                }
            }
        }
        // SIZE 已经超限就不必开流了；SIZE 缺失（-1）时靠下面边写边数兜底
        if (declaredSize > MAX_MEDIA_BYTES) {
            return 400 to err(
                str(ctx, R.string.dsh_native_err_media_too_big, MAX_MEDIA_BYTES / (1024 * 1024)),
                "too_large",
            )
        }

        val fallbackExt = when (type) {
            PermissionUtils.MediaType.IMAGE -> "jpg"
            PermissionUtils.MediaType.VIDEO -> "mp4"
            PermissionUtils.MediaType.AUDIO -> "m4a"
        }
        val dir = stageDir(ctx)
        val out = File(dir, "${id}_${safeName(displayName, fallbackExt)}")
        var copied = 0L
        // MediaStore 的 SIZE 可能缺失或不准（查询失败时是 -1），所以边写边数；
        // 超限与读失败要分开报，不然「文件太大」会显示成「宿主读不了」。
        var overflow = false
        val ok = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(out).use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        copied += n
                        if (copied > MAX_MEDIA_BYTES) {
                            overflow = true
                            return@runCatching false
                        }
                        o.write(buf, 0, n)
                    }
                }
                true
            } ?: false
        }.getOrElse { e ->
            Log.w(TAG, "media 读取失败: ${e.message}")
            false
        }
        if (!ok) {
            out.delete()
            return if (overflow) {
                400 to err(
                    str(ctx, R.string.dsh_native_err_media_too_big, MAX_MEDIA_BYTES / (1024 * 1024)),
                    "too_large",
                )
            } else {
                500 to err(str(ctx, R.string.dsh_native_err_media_read), "read_failed")
            }
        }
        trimStage(dir)
        return 200 to JSONObject()
            .put("ok", true)
            .put("path", stageGuestPath(out.name))
            .put("bytes", copied)
            .put("name", displayName)
            .toString()
    }

    /**
     * 录一段音，落到容器暂存区。
     *
     * 前台限制不是我们加的：Android 9 起没有前台界面/前台服务类型的进程录音**只会拿到
     * 静音**，不报错。我们的前台服务是 specialUse 类型，它不授予麦克风 —— 所以后台请求
     * 直接回 409，而不是交一段几百 KB 的静音出去。
     */
    private fun micRecord(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        if (!isForeground(ctx)) return backgroundMicDenied(ctx)
        if (!recording.compareAndSet(false, true)) {
            return 409 to err(str(ctx, R.string.dsh_native_err_mic_busy), "already_recording")
        }
        try {
            return doRecord(ctx, params)
        } finally {
            recording.set(false)
        }
    }

    private fun doRecord(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val ms = (params["ms"]?.toLongOrNull() ?: DEFAULT_RECORD_MS).coerceIn(500L, MAX_RECORD_MS)
        val dir = stageDir(ctx)
        val out = File(dir, "rec_${System.currentTimeMillis()}.m4a")

        @Suppress("DEPRECATION")
        val recorder = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(ctx)
            else MediaRecorder()
        }.getOrElse { e ->
            return 500 to err(
                str(ctx, R.string.dsh_native_err_mic_init, e.message ?: ""),
                "recorder_failed",
            )
        }

        val started = runCatching {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(64_000)
            recorder.setAudioSamplingRate(44_100)
            recorder.setOutputFile(out.absolutePath)
            recorder.prepare()
            recorder.start()
            true
        }.getOrElse { e ->
            Log.w(TAG, "录音启动失败: ${e.message}")
            runCatching { recorder.release() }
            out.delete()
            false
        }
        if (!started) {
            return 500 to err(str(ctx, R.string.dsh_native_err_mic_start), "record_failed")
        }

        runCatching { Thread.sleep(ms) }
        // stop() 在「一帧都没录到」时会抛，此时产物是个坏文件，必须删掉再报错
        val stopped = runCatching { recorder.stop(); true }.getOrElse { e ->
            Log.w(TAG, "录音停止失败: ${e.message}")
            false
        }
        runCatching { recorder.release() }
        if (!stopped || !out.isFile || out.length() == 0L) {
            out.delete()
            return 500 to err(str(ctx, R.string.dsh_native_err_mic_empty), "record_empty")
        }
        // 录完再确认一次前台：中途切走的那段是静音，交出去等于交半份假数据
        if (!isForeground(ctx)) {
            out.delete()
            return backgroundMicDenied(ctx)
        }
        trimStage(dir)
        return 200 to JSONObject()
            .put("ok", true)
            .put("path", stageGuestPath(out.name))
            .put("bytes", out.length())
            .put("ms", ms)
            .toString()
    }

    private fun backgroundMicDenied(ctx: Context): Pair<Int, String> =
        409 to err(str(ctx, R.string.dsh_native_err_mic_background), "not_foreground")

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

    /**
     * 取一条本地化文案。
     *
     * 这些串会经 `dsh-native` 的 stderr 出现在**用户**眼前（agent 也读它，但 agent 认的是
     * [err] 里的 `reason`，不是这段人话），所以要跟随应用语言。走 `appString` 而不是
     * `ctx.getString`：这里的 Context 是 Application 的，而应用内语言在 API 33 以下
     * 只作用于 Activity。
     *
     * 取不到就退回资源名：桥不能因为一次资源查找失败而回 500，那会把「参数写错了」
     * 变成「宿主坏了」。
     */
    private fun str(ctx: Context, resId: Int, vararg args: Any): String = runCatching {
        ctx.appString(resId, *args)
    }.getOrElse { ctx.resources.getResourceEntryName(resId) ?: "error" }

    /** 错误体带机器可读的 [reason]：agent 需要据此决定是重试还是提示用户。 */
    private fun err(msg: String, reason: String): String = JSONObject()
        .put("ok", false)
        .put("error", msg)
        .put("reason", reason)
        .toString()

    private fun methodNotAllowed(ctx: Context, method: String, path: String): Pair<Int, String> =
        404 to err(
            str(ctx, R.string.dsh_native_err_unknown_endpoint, method, path),
            "unknown_endpoint",
        )
}
