package me.bmax.apatch.dsh

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.util.Log
import me.bmax.apatch.R
import me.bmax.apatch.util.PermissionUtils
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 「读手机上的个人数据」这一类能力：日历、通讯录、位置。
 *
 * 与 [DshNativeBridge] 分开只是为了文件大小；授权、开关与前台判断仍然由那边统一裁决，
 * 这里只负责「已经允许之后怎么做成」。
 *
 * ## 共同的取舍
 *
 * **只读为默认，写入要有明确理由。** 日历给了 `create`（agent 帮你记一件事是常见需求，
 * 而且事件是可见、可删的）；通讯录**没有**写接口 —— 改联系人不可见、后果扩散到别人身上。
 *
 * **不返回超出请求的数据。** 列表都有 limit，通讯录不返回 photo/note 之类的额外字段。
 * agent 的上下文里塞进整本通讯录既无用也危险。
 */
internal object DshPersonalData {
    private const val TAG = "DshPersonalData"

    private const val DEFAULT_LIMIT = 50
    private const val MAX_LIMIT = 500

    /** 日历默认往后看的天数。 */
    private const val DEFAULT_DAYS = 7
    private const val MAX_DAYS = 366

    /** 位置的默认可接受年龄：超过这个岁数的缓存点位视为过期，要主动请求一次。 */
    private const val DEFAULT_MAX_AGE_MS = 5 * 60 * 1000L

    private fun limitOf(raw: String?): Int =
        (raw?.toIntOrNull() ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

    // ────────────────────────── 日历 ──────────────────────────

    /**
     * 列出时间窗口内的事件。
     *
     * 用 [CalendarContract.Instances] 而不是 `Events`：重复事件在 `Events` 里只有一行
     * 规则，真正「这周有哪几次」要靠 Instances 展开。少了这一层，每周例会只会出现一次。
     */
    fun calendarList(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        if (!PermissionUtils.hasCalendarReadPermission(ctx)) {
            return 403 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_calendar_denied),
                "no_calendar_permission",
            )
        }
        val days = (params["days"]?.toIntOrNull() ?: DEFAULT_DAYS).coerceIn(1, MAX_DAYS)
        val limit = limitOf(params["limit"])
        val now = System.currentTimeMillis()
        val end = now + days * 24L * 60 * 60 * 1000

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(now.toString())
            .appendPath(end.toString())
            .build()
        val cols = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )

        val out = JSONArray()
        val result = runCatching {
            ctx.contentResolver.query(
                uri, cols, null, null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext() && out.length() < limit) {
                    out.put(
                        JSONObject()
                            .put("id", c.getLong(0))
                            .put("title", c.getString(1) ?: "")
                            .put("start", c.getLong(2))
                            .put("end", c.getLong(3))
                            .put("allDay", c.getInt(4) == 1)
                            .put("location", c.getString(5) ?: "")
                            .put("calendar", c.getString(6) ?: ""),
                    )
                }
            }
        }
        result.onFailure { e ->
            Log.w(TAG, "日历查询失败: ${e.message}")
            return 500 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_calendar_query, e.message ?: ""),
                "query_failed",
            )
        }
        return 200 to JSONObject()
            .put("ok", true)
            .put("days", days)
            .put("now", now)
            .put("events", out)
            .toString()
    }

    /**
     * 新建一个事件。
     *
     * 必须挑一个日历账户插入：`CALENDAR_ID` 是必填项。选「第一个可写的本地/同步日历」，
     * 而不是硬编码 1 —— 那个 id 在很多机型上不存在或不可写。
     */
    fun calendarCreate(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        if (!PermissionUtils.hasCalendarWritePermission(ctx)) {
            return 403 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_calendar_write_denied),
                "no_calendar_permission",
            )
        }
        val title = DshNativeBridge.text(params["title"])
            ?: return 400 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_missing_param, "title"),
                "missing_title",
            )
        val start = params["start"]?.toLongOrNull()
            ?: return 400 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_missing_param, "start"),
                "missing_start",
            )
        // 默认 1 小时：不给 end 的请求远比给错 end 的常见
        val minutes = (params["minutes"]?.toLongOrNull() ?: 60L).coerceIn(1L, 24L * 60)
        val end = params["end"]?.toLongOrNull() ?: (start + minutes * 60_000L)
        if (end <= start) {
            return 400 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_calendar_bad_range),
                "bad_range",
            )
        }

        val calId = writableCalendarId(ctx)
            ?: return 404 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_calendar_none),
                "no_calendar",
            )

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            DshNativeBridge.text(params["description"])?.let {
                put(CalendarContract.Events.DESCRIPTION, it)
            }
            DshNativeBridge.text(params["location"])?.let {
                put(CalendarContract.Events.EVENT_LOCATION, it)
            }
        }
        val uri = runCatching {
            ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        }.getOrElse { e ->
            Log.w(TAG, "日历写入失败: ${e.message}")
            null
        }
            ?: return 500 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_calendar_insert),
                "insert_failed",
            )
        return 200 to JSONObject()
            .put("ok", true)
            .put("id", ContentUris.parseId(uri))
            .put("calendar", calId)
            .put("start", start)
            .put("end", end)
            .toString()
    }

    /** 第一个可写的日历 id。`VISIBLE` 一起筛掉那些同步用的隐藏日历。 */
    private fun writableCalendarId(ctx: Context): Long? = runCatching {
        ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND " +
                "${CalendarContract.Calendars.VISIBLE} = 1",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            "${CalendarContract.Calendars.IS_PRIMARY} DESC",
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }.getOrNull()

    // ────────────────────────── 通讯录 ──────────────────────────

    /**
     * 列联系人（只读）。
     *
     * 查 `Data` 表按 `Phone` 的 mimetype 过滤，而不是查 `Contacts` 再逐个回查号码：
     * 后者是 N+1 次查询，几百个联系人就明显卡。
     *
     * 只返回名字与号码。邮箱、生日、备注、头像都不返回 —— 需要哪一项再单独加端点，
     * 不做「顺手全给」。
     */
    fun contactsList(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        if (!PermissionUtils.hasContactsPermission(ctx)) {
            return 403 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_contacts_denied),
                "no_contacts_permission",
            )
        }
        val q = DshNativeBridge.text(params["q"])
        val limit = limitOf(params["limit"])

        // 按名字或号码模糊匹配。号码里的分隔符（空格、横线）让 LIKE 常常匹配不上，
        // 所以额外拿 NORMALIZED_NUMBER 兜一手。
        val where = StringBuilder()
        val args = ArrayList<String>()
        if (q != null) {
            where.append("(")
                .append(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
                .append(" LIKE ? OR ")
                .append(ContactsContract.CommonDataKinds.Phone.NUMBER)
                .append(" LIKE ? OR ")
                .append(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER)
                .append(" LIKE ?)")
            val like = "%$q%"
            args.add(like)
            args.add(like)
            args.add(like)
        }

        val cols = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
        )
        val out = JSONArray()
        val result = runCatching {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                cols,
                where.ifEmpty { null }?.toString(),
                if (args.isEmpty()) null else args.toTypedArray(),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} ASC",
            )?.use { c ->
                while (c.moveToNext() && out.length() < limit) {
                    out.put(
                        JSONObject()
                            .put("id", c.getLong(0))
                            .put("name", c.getString(1) ?: "")
                            .put("number", c.getString(2) ?: "")
                            .put("type", phoneTypeId(c.getInt(3))),
                    )
                }
            }
        }
        result.onFailure { e ->
            Log.w(TAG, "通讯录查询失败: ${e.message}")
            return 500 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_contacts_query, e.message ?: ""),
                "query_failed",
            )
        }
        return 200 to JSONObject()
            .put("ok", true)
            .put("contacts", out)
            .toString()
    }

    /**
     * 号码类型 → 稳定 id。
     *
     * 不返回本地化标签：这是给 agent 判断用的，翻译会变、判断不该跟着变
     * （与两个桥「`error` 给人看、`reason` 给程序看」同一条原则）。
     */
    private fun phoneTypeId(type: Int): String = when (type) {
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "home"
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "mobile"
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "work"
        ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "main"
        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME -> "fax_home"
        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK -> "fax_work"
        ContactsContract.CommonDataKinds.Phone.TYPE_PAGER -> "pager"
        else -> "other"
    }

    // ────────────────────────── 位置 ──────────────────────────

    /**
     * 取当前位置。
     *
     * 先看各 provider 的 `getLastKnownLocation`：够新就直接用。这不是偷懒 —— 主动定位
     * 要开 GNSS、耗电且可能几十秒无果，而「刚才那个点」对绝大多数问题已经够用。
     * 缓存太旧才发一次单次请求（[LocationManager.requestSingleUpdate] 的现代替代
     * `getCurrentLocation` 是 API 30，minSdk 26 上仍需回退）。
     *
     * 精度分档要如实报告：Android 12 起用户可以只给「大致位置」，此时系统返回的点位
     * 被刻意模糊到约 2 公里。`precise` 字段说明这一点，免得 agent 拿它当街道级坐标用。
     */
    fun location(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        if (!PermissionUtils.hasLocationPermission(ctx)) {
            return 403 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_location_denied),
                "no_location_permission",
            )
        }
        val lm = ctx.getSystemService(LocationManager::class.java)
            ?: return 500 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_no_service, "LocationManager"),
                "no_service",
            )
        val maxAge = (params["maxAge"]?.toLongOrNull() ?: DEFAULT_MAX_AGE_MS)
            .coerceIn(0L, 24L * 60 * 60 * 1000)
        val waitMs = (params["wait"]?.toLongOrNull() ?: 8_000L).coerceIn(0L, 30_000L)

        val cached = bestCached(lm, maxAge)
        if (cached != null) return 200 to locationJson(ctx, cached, fresh = false)

        // 定位服务整个被关掉时，主动请求也不会有结果 —— 先说清是这种情况
        val anyEnabled = runCatching {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
        if (!anyEnabled) {
            return 409 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_location_disabled),
                "location_disabled",
            )
        }
        if (waitMs == 0L) {
            return 409 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_location_stale),
                "no_fresh_fix",
            )
        }

        val fix = requestFix(lm, waitMs)
            ?: return 409 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_location_timeout, waitMs / 1000),
                "fix_timeout",
            )
        return 200 to locationJson(ctx, fix, fresh = true)
    }

    /** 各 provider 里最新且未过期的那个点位。 */
    private fun bestCached(lm: LocationManager, maxAgeMs: Long): Location? {
        val now = System.currentTimeMillis()
        var best: Location? = null
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val loc = runCatching {
                @Suppress("MissingPermission")
                lm.getLastKnownLocation(p)
            }.getOrNull() ?: continue
            if (now - loc.time > maxAgeMs) continue
            if (best == null || loc.time > best.time) best = loc
        }
        return best
    }

    private fun locationJson(ctx: Context, loc: Location, fresh: Boolean): String = JSONObject()
        .put("ok", true)
        .put("lat", loc.latitude)
        .put("lon", loc.longitude)
        // -1 表示「这台设备没给精度」，而不是「精度是 0 米」
        .put("accuracy", if (loc.hasAccuracy()) loc.accuracy.toDouble() else -1.0)
        .put("altitude", if (loc.hasAltitude()) loc.altitude else 0.0)
        .put("hasAltitude", loc.hasAltitude())
        .put("time", loc.time)
        .put("provider", loc.provider ?: "")
        .put("fresh", fresh)
        // 用户可能只授予了「大致位置」，那时坐标被系统模糊到公里级
        .put("precise", PermissionUtils.hasPreciseLocationPermission(ctx))
        .toString()

    /**
     * 发一次单次定位请求并等结果。
     *
     * 不用 API 30 的 `getCurrentLocation`（minSdk 是 26），也不用已废弃的
     * `requestSingleUpdate` —— 后者在部分 ROM 上不回调。这里自己注册监听、拿到第一个
     * 点位就立刻注销：**必须**注销，否则 GNSS 会一直开着耗电。
     *
     * 监听要注册到主线程的 Looper：桥接请求跑在自己的连接线程上，那个线程没有 Looper，
     * `requestLocationUpdates` 会直接抛。
     */
    private fun requestFix(lm: LocationManager, waitMs: Long): Location? {
        val latch = CountDownLatch(1)
        // AtomicReference 而不是 @Volatile 局部变量：后者在 Kotlin 里不合法
        // （@Volatile 只能标注属性），而这个值确实跨线程 —— 回调在主线程写，
        // 桥接线程读。
        val result = AtomicReference<Location?>(null)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (result.compareAndSet(null, location)) latch.countDown()
            }

            // 这三个在 API 29 以下是抽象的，必须实现（API 30 起有了默认实现）
            @Deprecated("API 29 以下必须实现")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit
        }

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return null

        val registered = AtomicBoolean(false)
        DshNativeBridge.onMain {
            for (p in providers) {
                runCatching {
                    lm.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper())
                    registered.set(true)
                }.onFailure { Log.w(TAG, "requestLocationUpdates($p) 失败: ${it.message}") }
            }
        }
        if (!registered.get()) return null
        runCatching { latch.await(waitMs, TimeUnit.MILLISECONDS) }
        // 必须注销：留着监听会让 GNSS 一直开着耗电
        DshNativeBridge.onMain { runCatching { lm.removeUpdates(listener) } }
        return result.get()
    }
}
