package me.bmax.apatch.dsh

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import me.bmax.apatch.R
import me.bmax.apatch.util.createRootShellStrict
import me.bmax.apatch.util.shellForResult
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 开机自启：三条路径 + 「要不要同时拉起容器」。
 *
 * 为什么是三条而不是一条：Android 官方的 `BOOT_COMPLETED` 广播在国产 ROM 上**基本不可靠**
 * —— MIUI / ColorOS / EMUI 等都有自己的「自启动管理」白名单，不在名单里的应用收不到那条
 * 广播，而这个名单只能由用户在系统设置里勾。所以：
 *
 * - [Mode.RECEIVER]：标准做法，收 `BOOT_COMPLETED`。零权限、零副作用，但受上述白名单摆布。
 * - [Mode.SCRIPT]：把一个脚本放进 root 管理器的 `service.d`，开机由 root 直接拉。
 *   完全绕过白名单，代价是必须有 root。
 * - [Mode.ACCESSIBILITY]：注册一个**什么也不做**的无障碍服务。系统在开机后会主动 bind
 *   无障碍服务、并且被杀之后会重新 bind —— 这是唯一一条既不需要 root、又不受 ROM 白名单
 *   约束的路。代价是用户要在系统里开一个无障碍开关，而那个开关看起来很吓人。
 *
 * 三条都汇聚到 [trigger]，由它统一判断「当前模式是否允许这个来源」。所以留在设备上的
 * 陈旧脚本、或者用户忘了关的无障碍服务，都不会在模式切走之后偷偷拉起东西。
 */
object DshAutostart {
    private const val TAG = "DSH-Folk-Autostart"

    /** 自启动方式。id 是写进 prefs 的稳定值，不要改。 */
    enum class Mode(val id: String) {
        /** 不自启（出厂默认）。 */
        OFF("off"),

        /** 收 `BOOT_COMPLETED` 广播。 */
        RECEIVER("receiver"),

        /** root 管理器的 `service.d` 开机脚本。 */
        SCRIPT("script"),

        /** 无障碍服务被 bind 时启动。 */
        ACCESSIBILITY("a11y"),
        ;

        companion object {
            fun of(id: String?): Mode = entries.firstOrNull { it.id == id } ?: OFF
        }
    }

    /**
     * root 管理器放开机脚本的目录。
     *
     * Magisk / KernelSU / APatch 三家都用同一个路径（KernelSU 与 APatch 为了兼容 Magisk
     * 模块刻意保持一致），所以不需要按实现分支。
     */
    private const val SERVICE_D = "/data/adb/service.d"

    /** 脚本文件名。带 `dsh-folk-` 前缀，便于用户在 service.d 里认出这是谁放的。 */
    private const val SCRIPT_NAME = "dsh-folk-autostart.sh"

    /** assets 里的脚本模板。 */
    private const val SCRIPT_ASSET = "dsh-folk-autostart.sh"

    /**
     * 脚本版本。改了 [SCRIPT_ASSET] 的内容就 +1。
     *
     * 装过的机器上脚本不会自己更新，[scriptNeedsUpdate] 靠比对文件里的这行版本号来判断
     * 要不要重装 —— 否则修好一个开机时序的 bug，只有新用户能受益。
     */
    private const val SCRIPT_REV = 1

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)

    // ───────────────────────── 偏好读写 ─────────────────────────

    /**
     * 当前自启动方式。
     *
     * 顺带做一次性迁移：1.8.0 及以前只有一个布尔 [DshEnv.KEY_AUTOSTART]，开着就等价于
     * 现在的 [Mode.RECEIVER]。迁移只在新键从未写过时发生，之后用户怎么选都不会被覆盖。
     */
    fun mode(ctx: Context): Mode {
        val p = prefs(ctx)
        val raw = p.getString(DshEnv.KEY_AUTOSTART_MODE, null)
        if (raw != null) return Mode.of(raw)
        // 老键 → 新键。老键留着不删：用户可能降级回 1.8.0。
        val legacy = p.getBoolean(DshEnv.KEY_AUTOSTART, false)
        val migrated = if (legacy) Mode.RECEIVER else Mode.OFF
        p.edit().putString(DshEnv.KEY_AUTOSTART_MODE, migrated.id).apply()
        Log.i(TAG, "迁移旧的开机自启开关: $legacy -> ${migrated.id}")
        return migrated
    }

    /** 写自启动方式。老键跟着同步，方便降级。 */
    fun setMode(ctx: Context, m: Mode) {
        prefs(ctx).edit()
            .putString(DshEnv.KEY_AUTOSTART_MODE, m.id)
            .putBoolean(DshEnv.KEY_AUTOSTART, m != Mode.OFF)
            .apply()
    }

    /**
     * 自启时是否连容器一起拉起来。
     *
     * 默认 **true**（与 1.8.0 的行为一致：那时开机自启就是直接 bootstrap）。关掉之后只起
     * 前台服务：通知栏出现、进程预热、点一下就能开始，但不烧那几秒 CPU 也不占内存。
     * 对「只想开机后随手能用」的人来说这才是想要的。
     */
    fun startContainer(ctx: Context): Boolean =
        prefs(ctx).getBoolean(DshEnv.KEY_AUTOSTART_CONTAINER, true)

    fun setStartContainer(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(DshEnv.KEY_AUTOSTART_CONTAINER, on).apply()
    }

    // ───────────────────────── 统一入口 ─────────────────────────

    /**
     * 三条路径共用的启动入口。
     *
     * @param source 谁在调用。必须与当前模式一致才会真的启动 —— 这是**故意**的：用户从
     *   脚本模式切到无障碍模式时，`service.d` 里那个脚本可能还没删（或者删除失败），
     *   而无障碍开关也可能一直开着。让每一路自己检查，就不会出现「我明明只选了一种」
     *   却被拉起两次、或者关掉之后还在自启的情况。
     * @return 是否真的请求了启动。
     */
    fun trigger(ctx: Context, source: Mode): Boolean {
        val want = mode(ctx)
        if (want != source) {
            Log.i(TAG, "忽略来自 ${source.id} 的自启请求：当前模式是 ${want.id}")
            return false
        }
        if (!DshEnv.isRuntimeInstalled(ctx)) {
            // 没装运行时就啥也别做：这时 bootstrap 会去下载 120 MB，开机自动跑流量
            // 是任何人都不会想要的。
            Log.i(TAG, "自启已开启但运行时尚未安装，跳过")
            return false
        }
        return runCatching {
            HarnessService.autostart(ctx)
            Log.i(TAG, "自启（${source.id}）已请求启动前台服务，拉容器=${startContainer(ctx)}")
            true
        }.getOrElse {
            // 高版本 Android 对后台启动前台服务有诸多限制，失败只记日志：用户手动点一下
            // 仍然能用，而崩在开机路径上是最糟的结果。
            Log.e(TAG, "自启失败", it)
            false
        }
    }

    // ───────────────────────── 无障碍服务 ─────────────────────────

    /**
     * 无障碍服务当前是否被用户启用了。
     *
     * 读的是 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`（一个用 `:` 分隔的
     * `包名/类名` 列表）而不是 `AccessibilityManager.getEnabledAccessibilityServiceList`：
     * 后者只列出**当前反馈类型匹配**的服务，而我们这个服务的 feedbackType 是
     * `feedbackGeneric`，在部分 ROM 上查不到，会误报成「没启用」。
     *
     * 顺带也检查了无障碍总开关：单个服务开着、总开关关着时系统不会 bind 它。
     */
    fun a11yEnabled(ctx: Context): Boolean = runCatching {
        val component = ComponentName(ctx, DshAutostartService::class.java)
        val flat = component.flattenToString()
        // 有些 ROM 存的是短形式（包名/.类名），两种都认
        val short = component.flattenToShortString()
        val enabled = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val listed = enabled.split(':').any { it.equals(flat, true) || it.equals(short, true) }
        if (!listed) return false
        Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
    }.getOrElse { false }

    // ───────────────────────── 开机脚本 ─────────────────────────

    /** 一次操作的结果：给 UI 用的本地化提示 + 给日志用的细节。 */
    data class ScriptResult(val ok: Boolean, val messageRes: Int, val detail: String = "")

    /** 脚本在设备上的完整路径，只用于显示。 */
    fun scriptPath(): String = "$SERVICE_D/$SCRIPT_NAME"

    /**
     * 脚本是否已装好。
     *
     * 走 root shell 读，因为 `/data/adb` 对普通应用不可见（连 `File.exists()` 都是 false，
     * 不是抛异常）—— 直接用 java.io 判断会永远告诉用户「没装」。
     */
    fun scriptInstalled(ctx: Context): Boolean = runCatching {
        val r = rootExec(ctx, "test -f ${scriptPath()} && echo yes")
        r.isSuccess && r.out.any { it.trim() == "yes" }
    }.getOrElse { false }

    /**
     * 已装的脚本是不是旧版本。
     *
     * 只在确实装过时有意义；没装时返回 false（该显示的是「未安装」而不是「需更新」）。
     */
    fun scriptNeedsUpdate(ctx: Context): Boolean = runCatching {
        val r = rootExec(ctx, "grep -c 'rev $SCRIPT_REV)' ${scriptPath()} 2>/dev/null || true")
        if (!r.isSuccess) return false
        val n = r.out.firstOrNull()?.trim()?.toIntOrNull() ?: 0
        n == 0
    }.getOrElse { false }

    /**
     * 装脚本。
     *
     * 先把渲染好的脚本写进应用私有目录，再用 root shell `cat` 进目标位置 —— 不用
     * `cp`，因为从 `/data/data` 复制过去会带上 `app_data_file` 的 SELinux 上下文，
     * 而 `cat >` 创建的新文件继承目录的 `adb_data_file`，才是 root 管理器执行得动的那种。
     */
    fun installScript(ctx: Context): ScriptResult {
        val body = renderScript(ctx) ?: return ScriptResult(false, R.string.dsh_autostart_script_asset_failed)
        val staged = File(ctx.filesDir, SCRIPT_NAME)
        runCatching { staged.writeText(body, StandardCharsets.UTF_8) }
            .onFailure { return ScriptResult(false, R.string.dsh_autostart_script_stage_failed, it.message ?: "") }

        val r = runCatching {
            rootExec(
                ctx,
                "mkdir -p $SERVICE_D",
                "cat ${staged.absolutePath} > ${scriptPath()}",
                "chmod 0755 ${scriptPath()}",
                "chown 0:0 ${scriptPath()} 2>/dev/null || true",
                "test -x ${scriptPath()} && echo ok",
            )
        }.getOrElse {
            return ScriptResult(false, R.string.dsh_autostart_script_no_root, it.message ?: "")
        }
        // 顺手删掉暂存件：它是一份可执行脚本的副本，留在 filesDir 里没有用处。
        runCatching { staged.delete() }

        val ok = r.out.any { it.trim() == "ok" }
        if (!ok) {
            val why = (r.err + r.out).joinToString("; ").take(300)
            return ScriptResult(false, R.string.dsh_autostart_script_install_failed, why)
        }
        return ScriptResult(true, R.string.dsh_autostart_script_installed)
    }

    /** 删脚本。用户切走模式时也会调它 —— 脚本的存在本身就是那条路的开关。 */
    fun removeScript(ctx: Context): ScriptResult {
        val r = runCatching {
            rootExec(ctx, "rm -f ${scriptPath()}", "test -f ${scriptPath()} || echo gone")
        }.getOrElse {
            return ScriptResult(false, R.string.dsh_autostart_script_no_root, it.message ?: "")
        }
        val gone = r.out.any { it.trim() == "gone" }
        return if (gone) ScriptResult(true, R.string.dsh_autostart_script_removed)
        else ScriptResult(false, R.string.dsh_autostart_script_remove_failed, r.err.joinToString("; ").take(300))
    }

    /**
     * 把 assets 里的模板填上本次安装的实际包名 / 组件名。
     *
     * 不在构建期写死：debug 构建的 applicationId 带 `.debug` 后缀，写死会让 debug 包
     * 装出一个指向 release 包的脚本。
     */
    private fun renderScript(ctx: Context): String? = runCatching {
        ctx.assets.open(SCRIPT_ASSET).use { it.readBytes().toString(StandardCharsets.UTF_8) }
            .replace("__PKG__", ctx.packageName)
            .replace("__SERVICE__", HarnessService::class.java.name)
            .replace("__ACTION__", HarnessService.ACTION_AUTOSTART)
            .replace("__REV__", SCRIPT_REV.toString())
    }.onFailure { Log.w(TAG, "读取自启脚本模板失败: ${it.message}") }.getOrNull()

    /** 建一个 root shell 跑几条命令；拿不到 root 直接抛（调用方转成用户可读的提示）。 */
    private fun rootExec(ctx: Context, vararg cmds: String): com.topjohnwu.superuser.Shell.Result {
        if (!PermissionManager.elevationEnabled(ctx)) {
            // 用户把提权设成「未启用」时不去碰 su：那会弹一个他刚刚才拒绝过的授权框。
            throw IllegalStateException("elevation disabled")
        }
        return createRootShellStrict(reason = "autostart-script").use { shell ->
            shellForResult(shell, *cmds)
        }
    }
}
