package me.bmax.apatch.dsh

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.ArrayDeque
import me.bmax.apatch.R

/**
 * 无障碍服务的**自动恢复**：系统把它关掉之后，尽量再打开一次。
 *
 * ## 为什么需要「恢复」而不是「保持」
 *
 * 两个服务（[DshAutostartService] 与 [DshA11yService]）都由系统托管：用户在系统设置里拨一次
 * 开关，Android 把组件名写进 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 并 bind；
 * 之后**任何**一方改这个值（系统清理、ROM 的省电策略、安全软件、用户误触）都会让服务
 * 悄悄消失 —— 而应用侧只看得到「服务没了」，看不到是谁关的。
 *
 * 所以这里做的事只有一件：**把用户当初的意图重新写回去**。为了避免变成"抢开关"，
 * 判定"要不要恢复"不看系统设置（系统关掉时无法区分是不是用户本意），只看应用内部的
 * 期望设置：
 *
 * - 自启服务：[DshAutostart.mode] 仍是 [DshAutostart.Mode.ACCESSIBILITY]；
 * - 读屏服务：原生桥总开关开着，且 [DshNativeBridge.Cap.A11Y] 的档位不是
 *   [DshNativeBridge.Access.OFF]。
 *
 * 用户在应用里把这两处关掉，就等于撤回授权，这里立刻停手。
 *
 * ## 写 secure settings 需要什么
 *
 * Settings.Secure.putString 要 [Manifest.permission.WRITE_SECURE_SETTINGS]。这条权限
 * 没有运行时授权框，只能用 ADB / root 一次性授予（[adbGrantCommand] 给出可复制的命令）。
 * 没授予时这里**不执行任何写入**，也不假装成功 —— 界面据此显示"命令还没执行"。
 *
 * ## 三条硬约束（都是为了防止把用户的设备搞坏）
 *
 * 1. **只增不减**：读到的列表里别人的条目原样保留，我们只补自己缺的两个组件，绝不重排、
 *    绝不清空。一次"顺手整理"就会把用户的读屏软件关掉。
 * 2. **写后读回**：写入不等于生效（有的 ROM 会拒收或立刻改回），所以每次写完重新读一遍，
 *    只有读回确认才报成功。
 * 3. **限次**：窗口期内最多试 [MAX_ATTEMPTS_PER_WINDOW] 次。若系统反复把它改回去，
 *    继续写就成了两个进程互相抢同一个开关 —— 那种情况下停手并如实报告，比无限重试好。
 *
 * ## 能力边界（必须对用户说清）
 *
 * 这是 **best-effort**：普通应用做不到常驻监控。能覆盖的时机只有 —— 应用进程活着时收到
 * secure settings 变更（[install] 的 observer）、应用回到前台（[repairQuietly] 的调用点）、
 * 以及服务断开回调。进程被系统杀掉期间，谁也不会替我们恢复。
 */
internal object DshA11yRecovery {
    private const val TAG = "DSH-Folk-A11yFix"

    /** 自动恢复的期望状态快照。两项独立：一处关掉不影响另一处。 */
    data class Wanted(val autostart: Boolean, val a11y: Boolean) {
        val any: Boolean get() = autostart || a11y
    }

    /** 一次恢复尝试的结果。[detail] 只进日志与调试信息，不直接展示给用户。 */
    data class Result(
        val ok: Boolean,
        val messageRes: Int,
        val detail: String = "",
        /** 本次真的补了组件（false 表示本来就不缺，或没权限没动手）。 */
        val changed: Boolean = false,
    )

    /** 自动恢复的限流：窗口期内最多这么多次，超了就停手。 */
    private const val MAX_ATTEMPTS_PER_WINDOW = 3
    private const val WINDOW_MS = 10 * 60 * 1000L

    /** 两次自动恢复之间的最小间隔：系统一次关闭会连发好几条变更。 */
    private const val DEBOUNCE_MS = 1_200L

    /** 自己刚写过之后忽略 observer 的时长（否则会把自己的写当成"又被关了"）。 */
    private const val SELF_WRITE_GRACE_MS = 1_500L

    private val attempts = ArrayDeque<Long>()

    @Volatile private var lastWriteAtMs = 0L

    @Volatile private var observerInstalled = false

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private val pendingRepair = Runnable { repairQuietly(lastTrigger = "observer") }

    // ───────────────────────── 前提：权限 ─────────────────────────

    /**
     * WRITE_SECURE_SETTINGS 是否已授予。
     *
     * 它是 manifest 里声明过的**签名/特权级**权限，所以真正的判据只有
     * [ContextCompat.checkSelfPermission]：声明不等于授予（不做这一步判断，
     * 界面就会一直显示"已授权"而写入静默失败）。
     */
    fun secureSettingsGranted(ctx: Context): Boolean = runCatching {
        ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.WRITE_SECURE_SETTINGS,
        ) == PackageManager.PERMISSION_GRANTED
    }.getOrElse { false }

    /**
     * 供用户复制的授权命令。
     *
     * 包名取 [Context.getPackageName] 而不是写死：debug 构建的 applicationId 带后缀，
     * 写死会让用户把权限授给了另一个包。
     */
    fun adbGrantCommand(ctx: Context): String =
        "pm grant " + ctx.packageName + " " + Manifest.permission.WRITE_SECURE_SETTINGS

    // ───────────────────────── 期望状态 ─────────────────────────

    /** 按**应用内部设置**算出这两个服务现在该不该在。 */
    fun wanted(ctx: Context): Wanted = Wanted(
        autostart = DshAutostart.mode(ctx) == DshAutostart.Mode.ACCESSIBILITY,
        a11y = DshNativeBridge.enabled(ctx) &&
            DshNativeBridge.access(ctx, DshNativeBridge.Cap.A11Y) != DshNativeBridge.Access.OFF,
    )

    private fun componentOf(ctx: Context, wanted: Wanted): List<ComponentName> = buildList {
        if (wanted.autostart) add(ComponentName(ctx, DshAutostartService::class.java))
        if (wanted.a11y) add(ComponentName(ctx, DshA11yService::class.java))
    }

    /** 当前被系统列在启用列表里的全部条目（原样，未做规范化）。 */
    private fun enabledList(ctx: Context): List<String> = runCatching {
        Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
    }.getOrDefault("")
        .split(':')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    /** 无障碍总开关（单服务开着、总开关关着时系统不 bind）。 */
    private fun masterEnabled(ctx: Context): Boolean = runCatching {
        Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
    }.getOrDefault(false)

    /**
     * 期望开启、但当前不在列表里的组件。
     *
     * 比较用 [ComponentName.flattenToString] 与 [ComponentName.flattenToShortString] 两种形式：
     * 不同 ROM 存的形式不一样，只认一种会在某些机器上永远报"没启用"。
     */
    fun missing(ctx: Context): List<ComponentName> {
        val listed = enabledList(ctx)
        return componentOf(ctx, wanted(ctx)).filter { c ->
            val flat = c.flattenToString()
            val short = c.flattenToShortString()
            listed.none { it.equals(flat, true) || it.equals(short, true) }
        }
    }

    // ───────────────────────── 执行 ─────────────────────────

    /**
     * 手动 / 自动共用的修复入口。
     *
     * @param manual 用户主动点了按钮。true 时不受限流与自我写入宽限的约束（那是用户当场
     *   要求的动作），但仍然只增不减、写后读回。
     */
    fun repair(ctx: Context, manual: Boolean = false): Result {
        val app = ctx.applicationContext
        val want = wanted(app)
        if (!want.any) {
            return Result(true, R.string.dsh_a11y_fix_not_wanted)
        }
        if (!secureSettingsGranted(app)) {
            return Result(false, R.string.dsh_a11y_fix_no_permission)
        }
        if (!manual && !allowAttempt()) {
            return Result(false, R.string.dsh_a11y_fix_give_up)
        }
        val gone = missing(app)
        val needMaster = gone.isNotEmpty() && !masterEnabled(app)
        if (gone.isEmpty() && !needMaster) {
            return Result(true, R.string.dsh_a11y_fix_already)
        }

        val current = enabledList(app)
        // 只增不减：原有条目按原顺序保留，我们的组件补在末尾。
        val merged = (current + gone.map { it.flattenToString() }).joinToString(":")
        lastWriteAtMs = System.currentTimeMillis()
        val wroteList = runCatching {
            Settings.Secure.putString(
                app.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                merged,
            )
        }.getOrElse {
            Log.w(TAG, "写入无障碍服务列表失败: ${it.message}")
            false
        }
        val wroteMaster = !needMaster || runCatching {
            Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }.getOrElse { false }
        lastWriteAtMs = System.currentTimeMillis()

        // 写后读回：写入返回 true 只代表这次调用没报错，系统可能立刻改回去。
        val stillMissing = missing(app)
        val masterOk = masterEnabled(app)
        return when {
            stillMissing.isEmpty() && masterOk ->
                Result(true, R.string.dsh_a11y_fix_ok, changed = true)
            !wroteList || !wroteMaster ->
                Result(false, R.string.dsh_a11y_fix_write_failed, "write rejected")
            else ->
                Result(
                    false,
                    R.string.dsh_a11y_fix_rejected,
                    "still missing: " + stillMissing.joinToString(",") { it.flattenToShortString() },
                )
        }
    }

    /**
     * 自动路径：不抛、不打扰用户，只在日志里留痕。
     *
     * @param lastTrigger 谁触发的（observer / resume / service-gone），只用于排障。
     */
    fun repairQuietly(lastTrigger: String) {
        val app = runCatching { me.bmax.apatch.apApp.applicationContext }.getOrNull() ?: return
        val r = runCatching { repair(app) }.getOrElse {
            Log.w(TAG, "自动恢复异常 ($lastTrigger): ${it.message}")
            return
        }
        Log.i(TAG, "自动恢复[$lastTrigger] ok=${r.ok} changed=${r.changed} ${r.detail}")
    }

    /** 限流：窗口期内超过上限就停手（见类注释第 3 条）。 */
    private fun allowAttempt(): Boolean {
        val now = System.currentTimeMillis()
        while (attempts.isNotEmpty() && now - attempts.first() > WINDOW_MS) attempts.pollFirst()
        if (attempts.size >= MAX_ATTEMPTS_PER_WINDOW) return false
        attempts.addLast(now)
        return true
    }

    // ───────────────────────── 自动触发 ─────────────────────────

    /**
     * 装上 secure settings 的 observer，使系统一改动就能被看到。
     *
     * 只在主进程装一次（[APApplication.onCreate][me.bmax.apatch.APApplication] 调用）。
     * 重复安装会让一次变更触发多次恢复，而恢复本身又会写列表 —— 那就是放大成抖动的起点。
     */
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        if (observerInstalled) return
        observerInstalled = true
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                // 自己刚写的那一条不是"又被关了"，别去响应它。
                if (System.currentTimeMillis() - lastWriteAtMs < SELF_WRITE_GRACE_MS) return
                if (!wanted(app).any) return
                handler.removeCallbacks(pendingRepair)
                handler.postDelayed(pendingRepair, DEBOUNCE_MS)
            }
        }
        runCatching {
            app.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                observer,
            )
            app.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
                false,
                observer,
            )
        }.onFailure { Log.w(TAG, "注册无障碍设置监听失败: ${it.message}") }
    }

    /**
     * 服务断开时调一次（[DshAutostartService.onUnbind] 等）。
     *
     * 不直接在同一回调里写：断开是系统正在做状态变更的中间时刻，此刻写回去有可能与它的
     * 收尾互相覆盖。推迟一个防抖窗口再判断，让系统先把这一轮做完。
     */
    fun onServiceGone(reason: String) {
        val app = runCatching { me.bmax.apatch.apApp.applicationContext }.getOrNull() ?: return
        if (!wanted(app).any) return
        handler.removeCallbacks(pendingRepair)
        handler.postDelayed({ repairQuietly(lastTrigger = reason) }, DEBOUNCE_MS)
    }

    /** 应用回到前台：补一次（覆盖"进程活着但没收到 observer"的情况）。 */
    fun onForeground() {
        if (!observerInstalled) return
        handler.removeCallbacks(pendingRepair)
        handler.postDelayed({ repairQuietly(lastTrigger = "resume") }, DEBOUNCE_MS)
    }
}
