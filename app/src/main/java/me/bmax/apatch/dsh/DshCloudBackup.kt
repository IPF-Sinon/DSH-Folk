package me.bmax.apatch.dsh

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 云备份插件 `dsh-folk-cloud` 的 App 侧客户端。
 *
 * ## 定位
 *
 * WebDAV 云备份从 1.9.2.5 起**整个搬进插件**：App 不再自己传 zip（原 [me.bmax.apatch.util.WebDavUtils]
 * 那条链已退役），只保留本地导出/导入。备份页的 WebDAV 设置框从此是插件 `/api/dsh-folk-cloud/config`
 * 的一个前端 —— 设置只存插件一份，App 读回来回填，**口令留空 = 保持插件里已存的那个**（插件按
 * 契约永不回传口令，只回布尔）。
 *
 * ## 与 [DshConfigBackup] 的区别
 *
 * [DshConfigBackup] 面向 dsh-config-manager（DSH 分区的导出/导入引擎），本对象面向
 * dsh-folk-cloud（WebDAV 云同步）。两者都走同一台 loopback web 服务、各自的路由前缀，
 * 守卫都是「回环 + 同源」。本应用直连 `http://127.0.0.1:<port>`、不带 Origin，恰好满足。
 *
 * 插件会反过来经 App 的文件桥（[DshFsBridge] 的 `/cloud/appdata/` 端点族）回调 App 补软件数据 ——
 * 那是另一个方向，与本客户端无关。
 */
object DshCloudBackup {
    private const val BASE = "/api/dsh-folk-cloud"

    /** 探活/读状态的短超时：卡住时不该让设置框一直转。 */
    private const val STATUS_TIMEOUT_MS = 15_000

    /** 保存/触发的超时：保存是轻量写，30s 足够；真正的同步是插件异步跑，不在这条请求里等。 */
    private const val WRITE_TIMEOUT_MS = 30_000

    /**
     * 插件当前的云备份配置状态（只读视图，绝不含口令值）。
     *
     * [reachable] = false 表示 DSH 没起来 / 插件没装 / 插件没在跑：这不是错误，
     * 备份页据此把云备份区块标灰并给一句「插件未运行」。
     */
    data class CloudStatus(
        val reachable: Boolean,
        /** 是否配过 WebDAV 地址。 */
        val configured: Boolean = false,
        val url: String = "",
        val username: String = "",
        /** 口令在插件的凭据里配没配上（永不回传口令本身）。 */
        val passwordConfigured: Boolean = false,
        /** 备份**加密口令**是否已存（与 WebDAV 口令分开一份，供自动触发也能加密）。 */
        val encryptPasswordConfigured: Boolean = false,
        val remoteDir: String = "",
        /** 用户设置的备份档位（dsh-only / dsh-vault / app-only / app-dsh / app-dsh-vault）。 */
        val tier: String = "",
        /** 实际会跑的档位（App 补包接口不可用时会被回退）。 */
        val effectiveTier: String = "",
        val tierFellBack: Boolean = false,
        val encrypt: Boolean = false,
        val includeSessions: Boolean = false,
        val intervalMinutes: Int = 0,
        val onStartup: Boolean = false,
        /** 插件是否检测到宿主 App 的软件数据接口（[DshFsBridge] 的 /cloud/appdata）。 */
        val appBridgeAvailable: Boolean = false,
        /** 插件是否检测到 dsh-config-manager（DSH 数据备份的硬依赖）。 */
        val configManagerAvailable: Boolean = false,
        val lastSyncedHash: String = "",
    )

    /** 读插件云备份状态（`GET /status`）。DSH 不可达安静回 reachable=false。 */
    suspend fun status(): CloudStatus = withContext(Dispatchers.IO) {
        val r = request("GET", "/status", null, STATUS_TIMEOUT_MS)
            ?: return@withContext CloudStatus(reachable = false)
        val o = runCatching { JSONObject(r) }.getOrNull()
            ?: return@withContext CloudStatus(reachable = false)
        val trigger = o.optJSONObject("trigger")
        CloudStatus(
            reachable = true,
            configured = o.optBoolean("configured", false),
            url = o.optString("url"),
            username = o.optString("username"),
            passwordConfigured = o.optBoolean("passwordConfigured", false),
            encryptPasswordConfigured = o.optBoolean("encryptPasswordConfigured", false),
            remoteDir = o.optString("remoteDir"),
            tier = o.optString("tier"),
            effectiveTier = o.optString("effectiveTier"),
            tierFellBack = o.optBoolean("tierFellBack", false),
            encrypt = o.optBoolean("encrypt", false),
            includeSessions = o.optBoolean("includeSessions", false),
            intervalMinutes = trigger?.optInt("intervalMinutes", 0) ?: 0,
            onStartup = trigger?.optBoolean("onStartup", false) ?: false,
            appBridgeAvailable = o.optBoolean("appBridgeAvailable", false),
            configManagerAvailable = o.optBoolean("configManagerAvailable", false),
            lastSyncedHash = o.optString("lastSyncedHash"),
        )
    }

    /** 写操作的结果：写没写成、插件够不够得着、失败原因（原样带出，插件已是中文）。 */
    data class CloudResult(
        val ok: Boolean,
        val reachable: Boolean,
        val error: String = "",
        /** 同步类操作（trigger/resolve）的结果分类；配置保存无此字段。 */
        val outcome: String = "",
        val message: String = "",
    )

    /**
     * 保存 WebDAV 配置到插件（`POST /config`）。
     *
     * @param password 留空则**不下发**该字段 —— 插件按契约保留原口令不动（不会清空）。
     *        这正是「设置只存一份、口令永不回传」下 App 能安全回填的关键：用户不重填口令，
     *        它就一直是插件里那个。
     */
    suspend fun saveConfig(
        url: String,
        username: String,
        password: String,
        remoteDir: String,
        tier: String,
        encrypt: Boolean,
        includeSessions: Boolean,
        intervalMinutes: Int,
        onStartup: Boolean,
        /** 备份加密口令；留空则**不下发** —— 插件保留已存的加密口令不动（同 [password] 语义）。 */
        encryptPassword: String = "",
    ): CloudResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("url", url.trim())
            put("username", username)
            if (password.isNotEmpty()) put("password", password)
            if (encryptPassword.isNotEmpty()) put("encryptPassword", encryptPassword)
            put("remoteDir", remoteDir)
            put("tier", tier)
            put("encrypt", encrypt)
            put("includeSessions", includeSessions)
            put("trigger", JSONObject().apply {
                put("intervalMinutes", intervalMinutes)
                put("onStartup", onStartup)
            })
        }
        val r = request("POST", "/config", body.toString(), WRITE_TIMEOUT_MS)
            ?: return@withContext CloudResult(ok = false, reachable = false)
        parseWrite(r)
    }

    /** 测试连接（`POST /test`）。[password] 留空则用插件已存的口令。 */
    suspend fun test(url: String, username: String, password: String): CloudResult =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("url", url.trim())
                put("username", username)
                put("password", password)
            }
            val r = request("POST", "/test", body.toString(), WRITE_TIMEOUT_MS)
                ?: return@withContext CloudResult(ok = false, reachable = false)
            parseWrite(r)
        }

    /**
     * 触发一轮同步（`POST /trigger`）。
     *
     * @param mode auto = 按状态机决定推或拉；push = 只上传；pull = 只从上游恢复。
     * @param password 加密档位需要；留空则插件用它已知的（目前不缓存，故加密档位手动触发要带）。
     */
    suspend fun trigger(mode: String, password: String = ""): CloudResult =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("mode", mode)
                if (password.isNotEmpty()) put("password", password)
            }
            // 触发本身是同步跑完才返回：插件那边一轮可能要传大包，给足超时
            val r = request("POST", "/trigger", body.toString(), 600_000)
                ?: return@withContext CloudResult(ok = false, reachable = false)
            parseWrite(r)
        }

    /**
     * 解析写类响应。
     *
     * `/config` `/test` 回 `{ok:true,...}` 或 `{error:"…"}`；
     * `/trigger` 回一个 SyncReport（有 `outcome`/`message`，error 类 outcome 也算失败）。
     */
    private fun parseWrite(raw: String): CloudResult {
        val o = runCatching { JSONObject(raw) }.getOrNull()
            ?: return CloudResult(ok = false, reachable = true, error = raw.take(200))
        val err = o.optString("error")
        if (err.isNotEmpty()) return CloudResult(ok = false, reachable = true, error = err)
        val outcome = o.optString("outcome")
        if (outcome.isNotEmpty()) {
            // SyncReport：outcome=error 视为失败，其余（uploaded/restored/up-to-date/…）视为成功
            return CloudResult(
                ok = outcome != "error",
                reachable = true,
                outcome = outcome,
                message = o.optString("message"),
            )
        }
        return CloudResult(
            ok = o.optBoolean("ok", true),
            reachable = true,
            message = o.optString("message"),
        )
    }

    private fun base(): String = "http://127.0.0.1:${DshRuntime.port()}$BASE"

    private fun request(method: String, path: String, body: String?, timeoutMs: Int): String? =
        runCatching {
            val conn = URL(base() + path).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 8_000
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader()?.use { it.readText() } ?: ""
        }.getOrNull()
}
