package me.bmax.apatch.dsh

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 工作流开跑前的 **Android 层权限检查**。
 *
 * ## 为什么是「检查 Android 权限」，而不是再走一遍应用内的门禁
 *
 * 用户对工作流的要求是「一次批准、后面别再拦」，同时「开跑前要确保系统真的给了权限」。这两句
 * 合起来的含义是：**应用自己的档位/限制模式/危险清单不该管工作流**（那由批准那一次负责），
 * 但**系统权限一样都没被绕过** —— 缺相机权限时工作流里的拍照步骤照样失败，而失败的原因写在
 * 几十步之后的日志里，用户根本不知道该去哪修。
 *
 * 所以这里把「这个工作流要用的能力」翻译成「Android 上真正需要的东西」，在开跑前一次性检查、
 * 一次性引导授权。检查项分两类，因为**解决方式完全不同**：
 *
 * - [Kind.RUNTIME]：运行时权限，能直接弹系统对话框（相机、麦克风、通知、通讯录…）；
 * - [Kind.SPECIAL]：特殊访问，只能跳到系统设置页（无障碍服务、用量访问、修改系统设置、
 *   全屏通知、安装未知应用、以及本应用的提权通道）。
 *
 * 检查是**幂等**的：用户从设置页回来，弹窗重新算一遍（[Gate.pending] 的同一份请求上重查），
 * 所以「我给了权限但弹窗还说缺」这种情况不会出现。
 */
internal object WorkflowPermissions {

    /** 检查项的类型 —— 决定了「去授权」是按哪个按钮。 */
    enum class Kind { RUNTIME, SPECIAL }

    /**
     * 一项检查。
     *
     * [permissions] 是运行时权限（[Kind.RUNTIME] 用）；[settingsAction] 是特殊访问要跳的
     * 设置页 action（[Kind.SPECIAL] 用）。[dataUri] 给需要带包名的设置页（例如
     * 「安装未知应用」）。
     */
    data class Item(
        val id: String,
        val kind: Kind,
        val labelRes: Int,
        val permissions: List<String> = emptyList(),
        val settingsAction: String? = null,
        val dataUri: String? = null,
    ) {
        companion object {
            const val ID_NOTIFY = "notify"
            const val ID_FULL_SCREEN = "full_screen_notify"
            const val ID_A11Y = "a11y"
            const val ID_USAGE = "usage"
            const val ID_SETTINGS = "settings"
            const val ID_INSTALL = "install"
            const val ID_SHELL = "shell"

            // 运行时权限那一组：一项一个 id，弹窗按它去重与显示。（跨版本同一件事的多个
            // Android 权限名共用一个 id —— 例如 Android 13 前后「读媒体库」是两组不同的权限名，
            // 用户眼里都是同一件事。）
            const val ID_CAMERA_ALIAS = "camera"
            const val ID_MIC_ALIAS = "mic"
            const val ID_CONTACTS_ALIAS = "contacts"
            const val ID_CALENDAR_ALIAS = "calendar"
            const val ID_SMS_ALIAS = "sms"
            const val ID_LOCATION_ALIAS = "location"
            const val ID_PHONE_ALIAS = "phone"
            const val ID_MEDIA_ALIAS = "media"
        }
    }

    /** 这个工作流声明要用的能力 → 需要检查的 Android 项。 */
    fun required(ctx: Context, caps: List<String>): List<Item> {
        val out = LinkedHashMap<String, Item>()
        fun add(item: Item) { out.putIfAbsent(item.id, item) }
        val capIds = caps.toSet()
        // 声明了任何一项会发通知的能力，就要通知权限
        if (capIds.any { it == DshNativeBridge.Cap.NOTIFY.id || it == DshNativeBridge.Cap.FULL_SCREEN_NOTIFY.id }) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(runtime(Item.ID_NOTIFY, me.bmax.apatch.R.string.dsh_workflow_perm_notify, Manifest.permission.POST_NOTIFICATIONS))
            }
        }
        if (capIds.contains(DshNativeBridge.Cap.FULL_SCREEN_NOTIFY.id) && Build.VERSION.SDK_INT >= 34) {
            add(special(Item.ID_FULL_SCREEN, me.bmax.apatch.R.string.dsh_workflow_perm_full_screen, Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT))
        }
        if (capIds.contains(DshNativeBridge.Cap.CAMERA.id)) {
            add(runtime(Item.ID_CAMERA_ALIAS, me.bmax.apatch.R.string.dsh_workflow_perm_camera, Manifest.permission.CAMERA))
        }
        if (capIds.contains(DshNativeBridge.Cap.MIC.id)) {
            add(runtime(Item.ID_MIC_ALIAS, me.bmax.apatch.R.string.dsh_workflow_perm_mic, Manifest.permission.RECORD_AUDIO))
        }
        if (capIds.contains(DshNativeBridge.Cap.CONTACTS.id)) {
            add(runtime(Item.ID_CONTACTS_ALIAS, me.bmax.apatch.R.string.dsh_workflow_perm_contacts, Manifest.permission.READ_CONTACTS))
        }
        if (capIds.contains(DshNativeBridge.Cap.CALENDAR.id)) {
            add(runtime(Item.ID_CALENDAR_ALIAS, me.bmax.apatch.R.string.dsh_workflow_perm_calendar, Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
        }
        if (capIds.contains(DshNativeBridge.Cap.SMS.id)) {
            add(runtime(Item.ID_SMS_ALIAS, me.bmax.apatch.R.string.dsh_workflow_perm_sms, Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS))
        }
        if (capIds.contains(DshNativeBridge.Cap.LOCATION.id)) {
            add(runtime(Item.ID_LOCATION_ALIAS, me.bmax.apatch.R.string.dsh_workflow_perm_location, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        if (capIds.contains(DshNativeBridge.Cap.PHONE.id)) {
            add(runtime(Item.ID_PHONE_ALIAS, me.bmax.apatch.R.string.dsh_workflow_perm_phone, Manifest.permission.READ_PHONE_STATE))
        }
        if (capIds.contains(DshNativeBridge.Cap.MEDIA.id)) {
            val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            add(runtime(Item.ID_MEDIA_ALIAS, me.bmax.apatch.R.string.dsh_workflow_perm_media, *perms.toTypedArray()))
        }
        if (capIds.contains(DshNativeBridge.Cap.A11Y.id)) {
            add(special(Item.ID_A11Y, me.bmax.apatch.R.string.dsh_workflow_perm_a11y, Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        if (capIds.contains(DshNativeBridge.Cap.USAGE.id)) {
            add(special(Item.ID_USAGE, me.bmax.apatch.R.string.dsh_workflow_perm_usage, Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        if (capIds.contains(DshNativeBridge.Cap.SETTINGS.id)) {
            add(special(Item.ID_SETTINGS, me.bmax.apatch.R.string.dsh_workflow_perm_settings, Settings.ACTION_MANAGE_WRITE_SETTINGS))
        }
        if (capIds.contains(DshNativeBridge.Cap.INSTALL.id)) {
            add(special(Item.ID_INSTALL, me.bmax.apatch.R.string.dsh_workflow_perm_install, Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
        }
        if (capIds.contains(DshNativeBridge.Cap.SHELL.id) || capIds.contains(DshNativeBridge.Cap.DISPLAY.id)) {
            // 提权通道不是 Android 权限，而是「设备上到底有没有一条能用的通道」。它同样
            // 只能跳页面（设置 → 安全 → 权限通道），所以归在 SPECIAL。
            add(special(Item.ID_SHELL, me.bmax.apatch.R.string.dsh_workflow_perm_shell, null))
        }
        return out.values.toList()
    }

    /** 当前**还缺**的项（已给的不会再出现在弹窗里）。 */
    fun missing(ctx: Context, caps: List<String>): List<Item> =
        required(ctx, caps).filter { !granted(ctx, it) }

    fun granted(ctx: Context, item: Item): Boolean = when (item.kind) {
        Kind.RUNTIME -> item.permissions.all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }
        Kind.SPECIAL -> when (item.id) {
            Item.ID_A11Y -> a11yEnabled(ctx)
            Item.ID_USAGE -> usageAccessGranted(ctx)
            Item.ID_SETTINGS -> Settings.System.canWrite(ctx)
            Item.ID_INSTALL -> ctx.packageManager.canRequestPackageInstalls()
            Item.ID_FULL_SCREEN -> fullScreenIntentAllowed(ctx)
            Item.ID_SHELL -> PrivilegedShell.reach(ctx) != null
            else -> false
        }
    }

    /** 特殊访问的设置页 Intent（带上包名，直接落在本应用那一项）。 */
    fun settingsIntent(ctx: Context, item: Item): Intent? {
        if (item.id == Item.ID_SHELL) {
            // 提权通道没有系统设置页可跳：那是我方设置项。
            return null
        }
        val action = item.settingsAction ?: return null
        return Intent(action).apply {
            if (item.id == Item.ID_SETTINGS || item.id == Item.ID_INSTALL || item.id == Item.ID_FULL_SCREEN) {
                data = Uri.fromParts("package", ctx.packageName, null)
            }
        }
    }

    private fun runtime(id: String, labelRes: Int, vararg permissions: String) =
        Item(id, Kind.RUNTIME, labelRes, permissions.toList())

    private fun special(id: String, labelRes: Int, action: String?) =
        Item(id, Kind.SPECIAL, labelRes, settingsAction = action)

    private fun a11yEnabled(ctx: Context): Boolean = runCatching {
        val expected = "${ctx.packageName}/${DshA11yService::class.java.name}"
        val enabled = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }.getOrDefault(false)

    private fun usageAccessGranted(ctx: Context): Boolean = runCatching {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    private fun fullScreenIntentAllowed(ctx: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 34) return@runCatching true
        ctx.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()
    }.getOrDefault(true)

    // ────────────────────────── 检查弹窗的状态机 ──────────────────────────

    /**
     * 「运行前权限检查」的那一次等待。
     *
     * 与审批弹窗同一套形态（阻塞、可超时、结论是返回值），但有两点不同，都是有意的：
     *
     * 1. **时限长得多**（5 分钟）：用户要跳到系统设置页去开无障碍/用量访问，60 秒根本不够。
     *    「超时」在这里只是防止连接永远挂着，不是催促用户。
     * 2. **不缓存结论**：用户可能开着弹窗去设置页再回来，每次回前台都重查一遍缺哪些 ——
     *    所以他回来后看到的清单是新的，而不是「你刚才缺的三个」（那两个已经给好了）。
     */
    object Gate {
        enum class Decision(val id: String) {
            /** 用户点了「开始运行」。 */
            START("start"),

            /** 用户取消（或超时）——这次运行不开始。 */
            CANCEL("cancel"),
        }

        /** 一次检查。缺哪些项由界面现算（[missing]），这里只存「要检查哪些能力」。 */
        data class Request(
            val id: Long,
            val workflowName: String,
            val caps: List<String>,
            val reason: String,
            val filedAtMs: Long,
        )

        const val TTL_MS = 300_000L

        private val ids = AtomicLong(0)
        private val mutable = MutableStateFlow<Request?>(null)
        val pending = mutable.asStateFlow()

        private val waiters = ConcurrentHashMap<Long, CompletableDeferred<Decision>>()
        private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        private var expiry: Job? = null

        @Synchronized
        fun submit(workflowName: String, caps: List<String>, reason: String): Request? {
            if (mutable.value != null) return null
            val request = Request(
                id = ids.incrementAndGet(),
                workflowName = workflowName,
                caps = caps,
                reason = reason,
                filedAtMs = System.currentTimeMillis(),
            )
            mutable.value = request
            waiters[request.id] = CompletableDeferred()
            expiry?.cancel()
            expiry = scope.launch {
                delay(TTL_MS)
                expireIfStill(request.id)
            }
            return request
        }

        fun awaitDecision(id: Long): Decision = runBlocking {
            withTimeoutOrNull(TTL_MS + 5_000L) { waiters[id]?.await() }
        } ?: Decision.CANCEL

        @Synchronized
        fun start(id: Long) = resolve(id, Decision.START)

        @Synchronized
        fun cancel(id: Long) = resolve(id, Decision.CANCEL)

        @Synchronized
        private fun expireIfStill(id: Long) {
            val current = mutable.value ?: return
            if (current.id != id) return
            resolve(id, Decision.CANCEL)
        }

        @Synchronized
        private fun resolve(id: Long, decision: Decision) {
            val current = mutable.value ?: return
            if (current.id != id) return
            mutable.value = null
            expiry?.cancel()
            waiters.remove(id)?.complete(decision)
        }
    }
}
