package me.bmax.apatch.dsh

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import me.bmax.apatch.R
import me.bmax.apatch.util.PermissionUtils
import org.json.JSONObject

/**
 * 「改手机设置」这一类能力：系统设置（亮度 / 休眠 / 自动旋转）与音量。
 *
 * ## 为什么这一类要单独说明
 *
 * 它们改的是**全局状态**，而且改完不会自动恢复 —— agent 把亮度调到 1% 之后不会有人
 * 替用户调回来。所以：
 *
 * - 每个写操作都返回改动前后的值，agent 能自己说清「我把什么从多少改成了多少」；
 * - 不提供「一键静音全部」「关掉所有传感器」这类批量动作，只给单项；
 * - 亮度、休眠、旋转这三项走 `WRITE_SETTINGS`，那是 **appop 特殊权限**，
 *   `requestPermissions()` 拿不到，只能跳系统页（见设置页里那一行）。
 *
 * ## 两个容易踩的坑
 *
 * 1. 自动亮度开着时写 `SCREEN_BRIGHTNESS` **没有可见效果** —— 系统下一次感光就覆盖回去。
 *    所以 [brightnessSet] 会把当前模式一并报告，并接受 `auto=0` 显式关掉自动亮度。
 * 2. 勿扰（DND）开着时改音量、或者把响铃模式设成静音，都需要
 *    `ACCESS_NOTIFICATION_POLICY` 授权，否则 `setStreamVolume` 静默无效、
 *    `setRingerMode` 抛 SecurityException。这里先检查再动手，并把原因如实回报。
 */
internal object DshSystemCtl {
    private const val TAG = "DshSystemCtl"

    /**
     * 系统亮度的取值上限。
     *
     * Android 没有公开 API 能读出这个上限（`PowerManager` 里那几个
     * `config_screenBrightnessSettingMaximum` 是内部资源），实践中一律是 255。
     * 所以接口收的是 **0..100 的百分比**，由这里换算 —— 让 agent 去猜某台设备的
     * 原始量程是不现实的。
     */
    private const val BRIGHTNESS_MAX = 255

    /** 休眠时间的合理区间：15 秒到 30 分钟。系统设置界面给的档位也在这个范围内。 */
    private const val TIMEOUT_MIN_MS = 15_000
    private const val TIMEOUT_MAX_MS = 30 * 60 * 1000

    // ────────────────────────── 系统设置 ──────────────────────────

    /** 当前的系统设置状态；写之前 agent 通常要先读一次。 */
    fun settingsGet(ctx: Context): Pair<Int, String> {
        val brightness = readSetting(ctx, Settings.System.SCREEN_BRIGHTNESS, -1)
        val mode = readSetting(ctx, Settings.System.SCREEN_BRIGHTNESS_MODE, -1)
        return 200 to JSONObject()
            .put("ok", true)
            .put("canWrite", PermissionUtils.canWriteSystemSettings(ctx))
            .put("brightness", brightness)
            .put("brightnessPercent", if (brightness < 0) -1 else brightness * 100 / BRIGHTNESS_MAX)
            .put("brightnessMax", BRIGHTNESS_MAX)
            .put("autoBrightness", mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            .put("screenOffTimeoutMs", readSetting(ctx, Settings.System.SCREEN_OFF_TIMEOUT, -1))
            .put("autoRotate", readSetting(ctx, Settings.System.ACCELEROMETER_ROTATION, 0) == 1)
            .toString()
    }

    /**
     * 设亮度（0..100 百分比）。
     *
     * `auto` 参数：`0` 关掉自动亮度再写（否则这次写入会被下一次感光覆盖），`1` 只开自动
     * 亮度、不改具体值。不传就保持现状，但响应里会带 `autoBrightness` 提醒。
     */
    fun brightnessSet(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val denied = requireWriteSettings(ctx)
        if (denied != null) return denied

        val auto = params["auto"]
        if (auto != null) {
            val on = auto == "1"
            val ok = writeSetting(
                ctx,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (on) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            if (!ok) return writeFailed(ctx, "SCREEN_BRIGHTNESS_MODE")
            // 只切模式，不给具体值
            if (params["percent"] == null) return settingsGet(ctx)
        }

        val percent = params["percent"]?.toIntOrNull()
            ?: return 400 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_missing_param, "percent"),
                "missing_percent",
            )
        val before = readSetting(ctx, Settings.System.SCREEN_BRIGHTNESS, -1)
        // 不允许 0：全黑屏幕会让用户以为设备死了，也没法自己调回来
        val value = (percent.coerceIn(1, 100) * BRIGHTNESS_MAX / 100).coerceIn(1, BRIGHTNESS_MAX)
        if (!writeSetting(ctx, Settings.System.SCREEN_BRIGHTNESS, value)) {
            return writeFailed(ctx, "SCREEN_BRIGHTNESS")
        }
        val modeNow = readSetting(ctx, Settings.System.SCREEN_BRIGHTNESS_MODE, -1)
        return 200 to JSONObject()
            .put("ok", true)
            .put("before", before)
            .put("after", value)
            .put("percent", value * 100 / BRIGHTNESS_MAX)
            // 自动亮度还开着的话，这次写入几秒内就会被系统覆盖 —— 必须让 agent 知道
            .put("autoBrightness", modeNow == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            .toString()
    }

    /** 设熄屏时间（毫秒）。 */
    fun timeoutSet(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val denied = requireWriteSettings(ctx)
        if (denied != null) return denied
        val ms = params["ms"]?.toIntOrNull()
            ?: return 400 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_missing_param, "ms"),
                "missing_ms",
            )
        val before = readSetting(ctx, Settings.System.SCREEN_OFF_TIMEOUT, -1)
        val value = ms.coerceIn(TIMEOUT_MIN_MS, TIMEOUT_MAX_MS)
        if (!writeSetting(ctx, Settings.System.SCREEN_OFF_TIMEOUT, value)) {
            return writeFailed(ctx, "SCREEN_OFF_TIMEOUT")
        }
        return 200 to JSONObject()
            .put("ok", true).put("before", before).put("after", value).toString()
    }

    /** 开关自动旋转。 */
    fun rotationSet(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val denied = requireWriteSettings(ctx)
        if (denied != null) return denied
        val on = params["on"]
            ?: return 400 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_missing_param, "on"),
                "missing_on",
            )
        val before = readSetting(ctx, Settings.System.ACCELEROMETER_ROTATION, 0)
        val value = if (on == "1") 1 else 0
        if (!writeSetting(ctx, Settings.System.ACCELEROMETER_ROTATION, value)) {
            return writeFailed(ctx, "ACCELEROMETER_ROTATION")
        }
        return 200 to JSONObject()
            .put("ok", true).put("before", before == 1).put("after", value == 1).toString()
    }

    private fun requireWriteSettings(ctx: Context): Pair<Int, String>? =
        if (PermissionUtils.canWriteSystemSettings(ctx)) {
            null
        } else {
            403 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_write_settings_denied),
                "no_write_settings",
            )
        }

    private fun writeFailed(ctx: Context, key: String): Pair<Int, String> =
        500 to DshNativeBridge.err(
            DshNativeBridge.str(ctx, R.string.dsh_native_err_setting_write, key),
            "write_failed",
        )

    private fun readSetting(ctx: Context, key: String, fallback: Int): Int = runCatching {
        Settings.System.getInt(ctx.contentResolver, key, fallback)
    }.getOrDefault(fallback)

    private fun writeSetting(ctx: Context, key: String, value: Int): Boolean = runCatching {
        Settings.System.putInt(ctx.contentResolver, key, value)
    }.getOrElse {
        Log.w(TAG, "写 $key 失败: ${it.message}")
        false
    }

    // ────────────────────────── 音量 ──────────────────────────

    /** 可调的音量流：id 是稳定协议名，不要改。 */
    private val STREAMS = mapOf(
        "music" to AudioManager.STREAM_MUSIC,
        "ring" to AudioManager.STREAM_RING,
        "alarm" to AudioManager.STREAM_ALARM,
        "notification" to AudioManager.STREAM_NOTIFICATION,
        "call" to AudioManager.STREAM_VOICE_CALL,
        "system" to AudioManager.STREAM_SYSTEM,
    )

    /** 读全部音量流 + 响铃模式。 */
    fun volumeGet(ctx: Context): Pair<Int, String> {
        val am = ctx.getSystemService(AudioManager::class.java)
            ?: return 500 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_no_service, "AudioManager"),
                "no_service",
            )
        val streams = JSONObject()
        for ((id, stream) in STREAMS) {
            val max = runCatching { am.getStreamMaxVolume(stream) }.getOrDefault(0)
            val cur = runCatching { am.getStreamVolume(stream) }.getOrDefault(0)
            streams.put(
                id,
                JSONObject()
                    .put("current", cur)
                    .put("max", max)
                    .put("percent", if (max > 0) cur * 100 / max else -1),
            )
        }
        return 200 to JSONObject()
            .put("ok", true)
            .put("ringerMode", ringerName(runCatching { am.ringerMode }.getOrDefault(-1)))
            .put("dndAccess", PermissionUtils.hasNotificationPolicyAccess(ctx))
            .put("streams", streams)
            .toString()
    }

    /**
     * 设某一路音量（0..100 百分比）。
     *
     * 百分比而不是原始档位：不同机型 / 不同流的最大档位差别很大（媒体常见 15，通话 5），
     * 让 agent 先查一次 max 再算是多余的往返。原始值仍在响应里给出。
     */
    fun volumeSet(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val am = ctx.getSystemService(AudioManager::class.java)
            ?: return 500 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_no_service, "AudioManager"),
                "no_service",
            )
        val id = params["stream"] ?: "music"
        val stream = STREAMS[id]
            ?: return 400 to DshNativeBridge.err(
                DshNativeBridge.str(
                    ctx,
                    R.string.dsh_native_err_bad_stream,
                    STREAMS.keys.joinToString(", "),
                ),
                "bad_stream",
            )
        val percent = params["percent"]?.toIntOrNull()
            ?: return 400 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_missing_param, "percent"),
                "missing_percent",
            )
        // 勿扰开着时改音量需要策略授权，否则 setStreamVolume 静默无效 ——
        // 「看起来成功了但没变」比直接报错糟得多
        if (dndActive(ctx) && !PermissionUtils.hasNotificationPolicyAccess(ctx)) {
            return 403 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_dnd_denied),
                "no_dnd_access",
            )
        }
        val max = runCatching { am.getStreamMaxVolume(stream) }.getOrDefault(0)
        if (max <= 0) {
            return 500 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_volume_unavailable, id),
                "stream_unavailable",
            )
        }
        val before = runCatching { am.getStreamVolume(stream) }.getOrDefault(0)
        // 四舍五入而不是截断：50% 在 max=15 上应该是 8 而不是 7
        val target = ((percent.coerceIn(0, 100) * max) + 50) / 100
        val ok = runCatching {
            am.setStreamVolume(stream, target.coerceIn(0, max), 0)
            true
        }.getOrElse {
            Log.w(TAG, "设音量失败: ${it.message}")
            false
        }
        if (!ok) {
            return 500 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_volume_set),
                "set_failed",
            )
        }
        val after = runCatching { am.getStreamVolume(stream) }.getOrDefault(target)
        return 200 to JSONObject()
            .put("ok", true)
            .put("stream", id)
            .put("before", before)
            .put("after", after)
            .put("max", max)
            .put("percent", after * 100 / max)
            .toString()
    }

    /**
     * 设响铃模式（normal / vibrate / silent）。
     *
     * 进入 vibrate 或 silent 在 Android 6 起需要勿扰策略授权 —— 没有的话
     * `setRingerMode` 抛 SecurityException。回 normal 不需要。
     */
    fun ringerSet(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val am = ctx.getSystemService(AudioManager::class.java)
            ?: return 500 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_no_service, "AudioManager"),
                "no_service",
            )
        val want = when (params["mode"]) {
            "normal" -> AudioManager.RINGER_MODE_NORMAL
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            "silent" -> AudioManager.RINGER_MODE_SILENT
            else -> return 400 to DshNativeBridge.err(
                DshNativeBridge.str(
                    ctx,
                    R.string.dsh_native_err_bad_ringer,
                    "normal, vibrate, silent",
                ),
                "bad_mode",
            )
        }
        if (want != AudioManager.RINGER_MODE_NORMAL &&
            !PermissionUtils.hasNotificationPolicyAccess(ctx)
        ) {
            return 403 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_dnd_denied),
                "no_dnd_access",
            )
        }
        val before = runCatching { am.ringerMode }.getOrDefault(-1)
        val ok = runCatching { am.ringerMode = want; true }.getOrElse {
            Log.w(TAG, "设响铃模式失败: ${it.message}")
            false
        }
        if (!ok) {
            return 500 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_ringer_set),
                "set_failed",
            )
        }
        return 200 to JSONObject()
            .put("ok", true)
            .put("before", ringerName(before))
            .put("after", ringerName(runCatching { am.ringerMode }.getOrDefault(want)))
            .toString()
    }

    /** 勿扰此刻是否在生效（非 ALL 即为开着某种过滤）。 */
    private fun dndActive(ctx: Context): Boolean = runCatching {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return false
        nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }.getOrDefault(false)

    private fun ringerName(mode: Int): String = when (mode) {
        AudioManager.RINGER_MODE_NORMAL -> "normal"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        AudioManager.RINGER_MODE_SILENT -> "silent"
        else -> "unknown"
    }

    // ────────────────────────── 安装权限 ──────────────────────────

    /**
     * 「允许安装未知应用」的状态。
     *
     * 只报告状态、不代替用户安装：安装动作本身仍然由系统安装器弹窗确认（应用内更新走
     * 的就是这条），而这项授权在 Android 8 起是用户可随时撤销的特殊权限。
     * 之所以给 agent 看，是因为它决定了「下载好的 APK 能不能装上」——
     * agent 不该在这项关着的时候还建议用户走应用内更新。
     */
    fun installStatus(ctx: Context): Pair<Int, String> = 200 to JSONObject()
        .put("ok", true)
        .put("canRequestInstall", PermissionUtils.canRequestPackageInstalls(ctx))
        .put("sdkInt", Build.VERSION.SDK_INT)
        .toString()

    // ────────────────────────── 一次性汇总 ──────────────────────────

    /**
     * 把所有「特殊权限」的状态一次给全。
     *
     * 这些权限申请不到、只能跳系统页，所以 agent 最该做的是**先查再决定要不要提示用户**，
     * 而不是撞一串 403 才发现。
     */
    fun specialPermissions(ctx: Context): JSONObject = JSONObject()
        .put("writeSettings", PermissionUtils.canWriteSystemSettings(ctx))
        .put("notificationPolicy", PermissionUtils.hasNotificationPolicyAccess(ctx))
        .put("requestInstall", PermissionUtils.canRequestPackageInstalls(ctx))
        .put("allFilesAccess", PermissionUtils.hasAllFilesAccess(ctx))
}
