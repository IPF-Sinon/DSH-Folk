package me.bmax.apatch.dsh

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import me.bmax.apatch.R
import me.bmax.apatch.util.PermissionUtils
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「读设备与环境状态」这一类能力：传感器、电话状态、网络。
 *
 * 三者都是**只读**。电话这一项特意不包含拨号与短信：
 * - 拨号如果真要做，正确形式是 `ACTION_DIAL`（把号码填进拨号盘、由用户按下通话键），
 *   那属于已有的 `intent` 能力，不需要 `CALL_PHONE`；
 * - 短信读写会把整个会话历史暴露给容器里的任意代码，收益远小于风险。
 */
internal object DshDeviceSense {
    private const val TAG = "DshDeviceSense"

    /** 单次读数的等待上限。传感器采样率不同，光线传感器可能几百毫秒才来一帧。 */
    private const val SENSOR_WAIT_MS = 2_500L

    // ────────────────────────── 传感器 ──────────────────────────

    /**
     * 可读的传感器类型 → 稳定 id。
     *
     * 只列**有明确含义、单位稳定**的那些。刻意排除：
     * - 原始的 magnetic field uncalibrated 之类的变体（对 agent 没有意义）；
     * - 姿态类的 rotation vector（四元数，解释成本高于价值）。
     *
     * 大多数项不需要任何权限。心率要 BODY_SENSORS，计步要 ACTIVITY_RECOGNITION ——
     * [needsPermission] 标出这两类，缺权限时它们从列表里消失而不是让整项能力不可用。
     */
    private val SENSORS = listOf(
        SensorSpec("accelerometer", Sensor.TYPE_ACCELEROMETER, "m/s^2", 3),
        SensorSpec("gyroscope", Sensor.TYPE_GYROSCOPE, "rad/s", 3),
        SensorSpec("magnetometer", Sensor.TYPE_MAGNETIC_FIELD, "uT", 3),
        SensorSpec("light", Sensor.TYPE_LIGHT, "lx", 1),
        SensorSpec("proximity", Sensor.TYPE_PROXIMITY, "cm", 1),
        SensorSpec("pressure", Sensor.TYPE_PRESSURE, "hPa", 1),
        SensorSpec("humidity", Sensor.TYPE_RELATIVE_HUMIDITY, "%", 1),
        SensorSpec("temperature", Sensor.TYPE_AMBIENT_TEMPERATURE, "degC", 1),
        SensorSpec("gravity", Sensor.TYPE_GRAVITY, "m/s^2", 3),
        SensorSpec("heart_rate", Sensor.TYPE_HEART_RATE, "bpm", 1, needsBody = true),
        SensorSpec("step_counter", Sensor.TYPE_STEP_COUNTER, "steps", 1, needsActivity = true),
    )

    private data class SensorSpec(
        val id: String,
        val type: Int,
        val unit: String,
        val values: Int,
        val needsBody: Boolean = false,
        val needsActivity: Boolean = false,
    )

    /** 这台设备上有哪些可读传感器。 */
    fun sensorList(ctx: Context): Pair<Int, String> {
        val sm = ctx.getSystemService(SensorManager::class.java)
            ?: return 500 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_no_service, "SensorManager"),
                "no_service",
            )
        val out = JSONArray()
        for (spec in SENSORS) {
            if (!permitted(ctx, spec)) continue
            val sensor = runCatching { sm.getDefaultSensor(spec.type) }.getOrNull() ?: continue
            out.put(
                JSONObject()
                    .put("id", spec.id)
                    .put("name", sensor.name ?: "")
                    .put("vendor", sensor.vendor ?: "")
                    .put("unit", spec.unit)
                    .put("values", spec.values)
                    .put("maxRange", sensor.maximumRange.toDouble()),
            )
        }
        // 缺权限而被隐藏的项要说清楚，否则「我这明明有心率」会变成一个 bug 报告
        val hidden = JSONArray()
        for (spec in SENSORS) {
            if (permitted(ctx, spec)) continue
            if (runCatching { sm.getDefaultSensor(spec.type) }.getOrNull() == null) continue
            hidden.put(spec.id)
        }
        return 200 to JSONObject()
            .put("ok", true)
            .put("sensors", out)
            .put("needPermission", hidden)
            .toString()
    }

    /**
     * 读一个传感器的当前值。
     *
     * 传感器 API 是「注册监听 → 等回调」，没有同步读法。这里注册、拿第一帧、立刻注销 ——
     * **必须**注销，留着监听会持续耗电（加速度计常驻能明显掉电）。
     *
     * 监听要注册到主线程 Looper：桥接请求跑在自己的连接线程上，那里没有 Looper。
     */
    fun sensorRead(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val id = params["id"] ?: params["sensor"]
            ?: return 400 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_missing_param, "id"),
                "missing_id",
            )
        val spec = SENSORS.firstOrNull { it.id == id }
            ?: return 400 to DshNativeBridge.err(
                DshNativeBridge.str(
                    ctx,
                    R.string.dsh_native_err_bad_sensor,
                    SENSORS.joinToString(", ") { it.id },
                ),
                "bad_sensor",
            )
        if (!permitted(ctx, spec)) {
            return 403 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_sensor_denied, spec.id),
                if (spec.needsBody) "no_body_sensors_permission" else "no_activity_permission",
            )
        }
        val sm = ctx.getSystemService(SensorManager::class.java)
            ?: return 500 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_no_service, "SensorManager"),
                "no_service",
            )
        val sensor = runCatching { sm.getDefaultSensor(spec.type) }.getOrNull()
            ?: return 404 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_sensor_absent, spec.id),
                "sensor_absent",
            )

        val latch = CountDownLatch(1)
        val values = FloatArray(spec.values)
        // 回调在主线程写、桥接线程读，所以标志位必须是原子的
        val got = AtomicBoolean(false)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (got.get()) return
                val n = minOf(spec.values, event.values.size)
                for (i in 0 until n) values[i] = event.values[i]
                got.set(true)
                latch.countDown()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        val registered = runCatching {
            sm.registerListener(
                listener,
                sensor,
                SensorManager.SENSOR_DELAY_UI,
                Handler(Looper.getMainLooper()),
            )
        }.getOrDefault(false)
        if (!registered) {
            return 500 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_sensor_read, spec.id),
                "register_failed",
            )
        }
        runCatching { latch.await(SENSOR_WAIT_MS, TimeUnit.MILLISECONDS) }
        runCatching { sm.unregisterListener(listener) }

        if (!got.get()) {
            return 409 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_sensor_timeout, spec.id),
                "read_timeout",
            )
        }
        val arr = JSONArray()
        for (v in values) arr.put(v.toDouble())
        return 200 to JSONObject()
            .put("ok", true)
            .put("id", spec.id)
            .put("unit", spec.unit)
            .put("values", arr)
            .toString()
    }

    private fun permitted(ctx: Context, spec: SensorSpec): Boolean = when {
        spec.needsBody -> PermissionUtils.hasBodySensorsPermission(ctx)
        spec.needsActivity -> PermissionUtils.hasActivityRecognitionPermission(ctx)
        else -> true
    }

    // ────────────────────────── 电话状态 ──────────────────────────

    /**
     * 运营商 / 网络制式 / SIM 状态。
     *
     * 刻意**不返回** IMEI、序列号、电话号码：Android 10 起前两者对普通应用一律不可读
     * （抛 SecurityException），而号码即使读到也属于强标识信息，交给容器里的任意代码
     * 不成比例。这里给的都是「网络环境」层面的事实。
     */
    fun phoneInfo(ctx: Context): Pair<Int, String> {
        if (!PermissionUtils.hasPhoneStatePermission(ctx)) {
            return 403 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_phone_denied),
                "no_phone_permission",
            )
        }
        val tm = ctx.getSystemService(TelephonyManager::class.java)
            ?: return 500 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_no_service, "TelephonyManager"),
                "no_service",
            )
        val json = JSONObject().put("ok", true)
        runCatching { json.put("carrier", tm.networkOperatorName ?: "") }
        runCatching { json.put("simState", simStateName(tm.simState)) }
        runCatching { json.put("countryIso", tm.networkCountryIso ?: "") }
        runCatching { json.put("networkType", networkTypeName(tm)) }
        runCatching { json.put("callState", callStateName(tm.callState)) }
        runCatching { json.put("roaming", tm.isNetworkRoaming) }
        runCatching {
            json.put(
                "phoneType",
                when (tm.phoneType) {
                    TelephonyManager.PHONE_TYPE_GSM -> "gsm"
                    TelephonyManager.PHONE_TYPE_CDMA -> "cdma"
                    TelephonyManager.PHONE_TYPE_SIP -> "sip"
                    else -> "none"
                },
            )
        }
        return 200 to json.toString()
    }

    private fun simStateName(state: Int): String = when (state) {
        TelephonyManager.SIM_STATE_READY -> "ready"
        TelephonyManager.SIM_STATE_ABSENT -> "absent"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "pin_required"
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "puk_required"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "network_locked"
        TelephonyManager.SIM_STATE_NOT_READY -> "not_ready"
        TelephonyManager.SIM_STATE_PERM_DISABLED -> "disabled"
        TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "io_error"
        else -> "unknown"
    }

    private fun callStateName(state: Int): String = when (state) {
        TelephonyManager.CALL_STATE_IDLE -> "idle"
        TelephonyManager.CALL_STATE_RINGING -> "ringing"
        TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"
        else -> "unknown"
    }

    /**
     * 网络制式名。
     *
     * 用 `getDataNetworkType()`（API 24 起有，minSdk 是 26 所以不必分支）而不是已废弃的
     * `getNetworkType()`。它在 API 30 以下要求 READ_PHONE_STATE —— 这项能力本来就有。
     *
     * NETWORK_TYPE_NR 是 API 29 才加的常量，但 Kotlin 会把它内联成整数字面量，
     * 在更低的系统上只是永不命中的一个分支，不会 NoSuchFieldError。
     */
    private fun networkTypeName(tm: TelephonyManager): String {
        val type = runCatching { tm.dataNetworkType }
            .getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)
        return when (type) {
            TelephonyManager.NETWORK_TYPE_NR -> "5g"
            TelephonyManager.NETWORK_TYPE_LTE -> "lte"
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_UMTS -> "3g"
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_GPRS -> "2g"
            else -> "unknown"
        }
    }

    // ────────────────────────── 网络 ──────────────────────────

    /**
     * 当前网络状态。
     *
     * 用 `NetworkCapabilities` 而不是废弃的 `getActiveNetworkInfo`：后者在 API 29 起
     * 只返回过时的粗略信息。带宽估值（`linkDownstreamBandwidthKbps`）是**系统的估计**，
     * 不是实测值 —— 字段名里带 estimated 就是为了让 agent 别拿它当测速结果。
     *
     * SSID 需要位置权限才拿得到真值：Android 10 起没有位置权限时
     * `getConnectionInfo().ssid` 返回 `<unknown ssid>`。那种情况下**不返回**这个字段，
     * 而不是把占位串当网络名交出去。
     */
    fun networkInfo(ctx: Context): Pair<Int, String> {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
            ?: return 500 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_no_service, "ConnectivityManager"),
                "no_service",
            )
        val json = JSONObject().put("ok", true)
        val network = runCatching { cm.activeNetwork }.getOrNull()
        val caps = network?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
        if (caps == null) {
            return 200 to json
                .put("connected", false)
                .put("transport", "none")
                .toString()
        }
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            else -> "other"
        }
        json.put("connected", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            .put("transport", transport)
            // VALIDATED 才是「真的能上网」；连上一个要门户认证的 WiFi 时它是 false
            .put("validated", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            .put("metered", !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            .put("estimatedDownKbps", caps.linkDownstreamBandwidthKbps)
            .put("estimatedUpKbps", caps.linkUpstreamBandwidthKbps)
            // VPN 起来时 activeNetwork 是那条 VPN，它的 caps 同时带底层传输 ——
            // 所以上面的 transport 报的是物理链路（wifi/cellular），这一位单独说明
            // 「流量还经过一层 VPN」。两者都要有：只看 transport 会漏掉 VPN，
            // 只看这一位又不知道底下是 WiFi 还是流量。
            .put("vpn", caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        if (transport == "wifi") {
            json.put("wifi", wifiJson(ctx))
        }
        return 200 to json.toString()
    }

    @Suppress("DEPRECATION")
    private fun wifiJson(ctx: Context): JSONObject {
        val out = JSONObject()
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java) ?: return out
        val info = runCatching { wm.connectionInfo }.getOrNull() ?: return out
        // SSID 只有拿到位置权限才是真值；否则是 "<unknown ssid>" 这种占位串。
        // 宁可不给，也不要把占位串当网络名。
        val ssid = info.ssid?.trim('"').orEmpty()
        if (PermissionUtils.hasLocationPermission(ctx) &&
            ssid.isNotEmpty() && !ssid.contains("unknown", ignoreCase = true)
        ) {
            out.put("ssid", ssid)
        } else {
            out.put("ssidHidden", true)
        }
        runCatching { out.put("rssi", info.rssi) }
        runCatching { out.put("linkSpeedMbps", info.linkSpeed) }
        runCatching {
            out.put("signalLevel", WifiManager.calculateSignalLevel(info.rssi, 5))
        }.onFailure { Log.w(TAG, "signalLevel 计算失败: ${it.message}") }
        return out
    }
}
