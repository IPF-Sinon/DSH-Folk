package me.bmax.apatch.dsh

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell
import me.bmax.apatch.util.APatchCli
import me.bmax.apatch.util.getRootShell
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 特权命令执行：把宿主已经拿到的那条通道（root / Shizuku / 无线 ADB）借给容器里的 agent 用。
 *
 * 存在的理由：特权此前只在 App 内部用（仪表盘读 `/proc`、bugreport、重启菜单），容器里的
 * agent 完全够不着 —— 提示词甚至明说「别指望 su」。而容器内唯一那条通路
 * （[AdbBridge] 注入的 `adb-shell.py`）连提示词都没提过，等于没有。
 *
 * 三条通道的区别只在「谁来执行」：
 * - **root**：libsu 的常驻 su shell，uid 0；
 * - **Shizuku**：命令送到 Shizuku 进程里的用户服务执行（[DshShizukuShell]），uid 由它决定
 *   （Sui/root 模式是 0，普通 adb 模式是 2000）；
 * - **无线 ADB**：转发给容器内的 `adb-shell.py`（宿主没有 adb 客户端，而那条脚本已经有
 *   自己的只读白名单与两个授权标记），uid 2000，`--su` 才到 0。
 *
 * 这一层只管执行与「能不能执行」，**要不要用户同意由 [PrivPolicy] 判定**，两者分开是因为
 * 严格程度是用户的偏好，而通道能力是设备的事实。
 */
internal object PrivilegedShell {
    private const val TAG = "DshPrivilegedShell"

    /** 超时默认值与上限。命令卡住时最坏情况是「一次调用白等两分钟」，不会更久。 */
    const val DEFAULT_TIMEOUT_MS = 30_000L
    const val MAX_TIMEOUT_MS = 120_000L

    /** 返回给 agent 的输出上限。超出的部分截断并注明原长度，与审计的做法一致。 */
    private const val MAX_OUTPUT_CHARS = 64_000

    /**
     * 只读命令白名单。
     *
     * 与容器内 `app/src/main/assets/adb-shell.py` 的 `READONLY_CMDS` **逐字同源** ——
     * `tools/check-native-logic.js` 会解析两边做一致性断言，改一边不改另一边会让
     * 「同一条命令走宿主和走脚本得到不同的确认要求」。
     */
    private val READONLY_CMDS = setOf(
        "getprop", "dumpsys", "logcat", "id", "ps", "df", "free", "uptime", "date",
        "whoami", "getevent", "ls", "stat", "wc", "head", "tail", "grep", "cat",
        "md5sum", "sha1sum", "printenv", "env", "pwd", "which", "true", "echo",
    )

    /** 命令名本身可写、只有列出的子命令算只读（空集 = 这条命令的任何子命令都要确认）。 */
    private val READONLY_SUB = mapOf(
        "pm" to setOf("list", "path", "dump"),
        "settings" to setOf("get", "list"),
        "cmd" to emptySet<String>(),
        "am" to emptySet<String>(),
        "wm" to emptySet<String>(),
        "input" to emptySet<String>(),
        "svc" to emptySet<String>(),
    )

    /** 整机级、改完不容易恢复的命令：宽松档也要问一声。 */
    private val DANGEROUS_CMDS = setOf("rm", "rmdir", "dd", "mkfs", "wipe", "reboot", "shutdown")

    /** 这些命令的危险藏在子命令里。 */
    private val DANGEROUS_SUB = mapOf(
        "pm" to setOf("uninstall", "disable", "disable-user", "clear", "hide", "uninstall-system-updates"),
        "settings" to setOf("put", "delete", "reset"),
        "svc" to setOf("power", "data", "wifi"),
        "am" to setOf("force-stop", "kill"),
    )

    /** shell 元字符：出现即不再相信「按命令名判只读」。 */
    private val META_CHARS = listOf(">", "<", "|", ";", "&", "$(", "`", "\n", "\r")

    /**
     * 判定命令是否「确定只读」。
     *
     * 与 `adb-shell.py` 的 `is_readonly_cmd` 同一套判据，包括两个容易被忽略的坑：
     * 元字符（`echo x > /sdcard/f`、`ls; rm -rf /` 都能骗过按名字的白名单）与
     * `find -delete`（`find` 本身只读，但这两个参数会改盘）。拿不准一律 false。
     */
    fun isReadonly(command: String): Boolean {
        val s = command.trim()
        if (s.isEmpty()) return false
        for (m in META_CHARS) if (s.contains(m)) return false
        val parts = s.split(Regex("\\s+"))
        val name = parts[0].substringAfterLast('/')
        if (name == "find") {
            return parts.drop(1).none { a ->
                a.startsWith("-delete") || a.startsWith("-exec") ||
                    a.startsWith("-fprint") || a.startsWith("-fls")
            }
        }
        READONLY_SUB[name]?.let { subs -> return parts.size > 1 && parts[1] in subs }
        return name in READONLY_CMDS
    }

    /** 一条命令的风险等级：严格程度据此决定要不要弹窗。 */
    fun riskOf(command: String): PrivRisk {
        val s = command.trim()
        if (s.isEmpty()) return PrivRisk.WRITE
        if (isReadonly(s)) return PrivRisk.READONLY
        val parts = s.split(Regex("\\s+"))
        val name = parts[0].substringAfterLast('/')
        val sub = parts.getOrNull(1).orEmpty()
        if (name in DANGEROUS_CMDS) return PrivRisk.DANGEROUS
        if (DANGEROUS_SUB[name]?.contains(sub) == true) return PrivRisk.DANGEROUS
        return PrivRisk.WRITE
    }

    /**
     * 当前的执行通道。
     *
     * @param channel 真正会执行的那条通道
     * @param uid 预期身份
     * @param canRoot 这条通道能不能到 uid 0
     * @param ready 现在就能用吗：root 验过 / Shizuku 已授权 / ADB 已配对
     * @param reason [ready] 为 false 时的原因（[REASON_ROOT_UNVERIFIED] 等）
     * @param selected 用户首选的那条；与 [channel] 不同表示发生了回退
     */
    data class Reach(
        val channel: PermissionManager.Channel,
        val uid: Int,
        val canRoot: Boolean,
        val ready: Boolean = true,
        val reason: String? = null,
        val selected: PermissionManager.Channel? = null,
    ) {
        /**
         * 现在能不能试。
         *
         * 「root 还没验证过」算能试：调用会真的跑 su，Magisk/KernelSU 这时候才弹授权框，
         * 用户点一下就成了。真正不能试的是 Shizuku 没授权、ADB 没配对 —— 那两条无论怎么
         * 调都是失败，提前拦掉既能给出可指路的 reason，也不会白花用户一次弹窗。
         */
        val usable: Boolean get() = ready || reason == REASON_ROOT_UNVERIFIED
    }

    /**
     * 探测当前通道。
     *
     * 每次调用都重探一次（[PermissionManager.refresh] 在 `allowRootPrompt=false` 下只做
     * 文件检查与 binder ping，不会弹 su 授权框）：用户刚在设置里换了通道、或者刚给 Shizuku
     * 授权，agent 下一次调用就应该看到新事实，而不是等 App 重启。
     *
     * **用户选了一条但还没就绪时也必须返回**（只是 ready=false）。这一条以前没有，结果是
     * 提示词整段消失：用户刚把通道设成 root、还没点过「刷新权限」，agent 那边看起来就是
     * 「这台设备没有特权」—— 于是它连试都不试，而设备完全能做到。
     */
    fun reach(ctx: Context): Reach? {
        val status = PermissionManager.refresh(ctx, allowRootPrompt = false)
        val selected = status.preferred?.takeIf { it != PermissionManager.Channel.NONE }
        when (status.channel) {
            PermissionManager.Channel.ROOT -> return Reach(
                channel = PermissionManager.Channel.ROOT,
                uid = 0,
                canRoot = true,
                selected = selected?.takeIf { it != PermissionManager.Channel.ROOT },
            )
            PermissionManager.Channel.SHIZUKU -> return Reach(
                channel = PermissionManager.Channel.SHIZUKU,
                uid = status.shizukuUid,
                canRoot = status.shizukuUid == 0,
                selected = selected?.takeIf { it != PermissionManager.Channel.SHIZUKU },
            )
            PermissionManager.Channel.ADB -> return Reach(
                channel = PermissionManager.Channel.ADB,
                uid = 2000,
                canRoot = AdbBridge.granted(ctx, AdbBridge.ShellGrant.ROOT),
                selected = selected?.takeIf { it != PermissionManager.Channel.ADB },
            )
            PermissionManager.Channel.NONE -> Unit
        }
        // 没有可用通道 —— 但用户选了一条、设备上也确实看得到它，那就是「选了还没就绪」
        return when (selected) {
            PermissionManager.Channel.ROOT -> if (status.suPresent) {
                Reach(PermissionManager.Channel.ROOT, 0, true, ready = false, reason = REASON_ROOT_UNVERIFIED)
            } else null
            PermissionManager.Channel.SHIZUKU -> if (status.shizukuRunning) {
                Reach(PermissionManager.Channel.SHIZUKU, 2000, false, ready = false, reason = REASON_SHIZUKU_UNAUTHORIZED)
            } else null
            PermissionManager.Channel.ADB ->
                Reach(PermissionManager.Channel.ADB, 2000, false, ready = false, reason = REASON_ADB_UNPAIRED)
            else -> null
        }
    }

    /** 「选了但还没就绪」的三种原因；词表与提示词、UI 文案共用一份。 */
    const val REASON_ROOT_UNVERIFIED = "root_unverified"
    const val REASON_SHIZUKU_UNAUTHORIZED = "shizuku_unauthorized"
    const val REASON_ADB_UNPAIRED = "adb_unpaired"

    /** 通道的稳定标识：写进事实与审计，也用于提示词。 */
    fun channelId(channel: PermissionManager.Channel): String = channel.name.lowercase()

    /**
     * 只读命令清单。
     *
     * 给 agent 看的：严格档下每次调用都要用户点一下，它猜错一次就白花一次点击。名单在这里
     * 只有一份（[isReadonly] 用的就是它），所以不会出现「提示词说只读、宿主说不是」。
     */
    fun readonlyCommands(): List<String> = buildList {
        addAll(READONLY_CMDS.sorted())
        READONLY_SUB.toSortedMap().forEach { (name, subs) ->
            subs.sorted().forEach { add("$name $it") }
        }
    }

    /**
     * 这条命令在该通道上有没有被禁止，返回 null 表示可以执行。
     *
     * 与 [PrivPolicy.needsConfirm] 分工明确：这里判「通道允许不允许」（设备与用户授权的事实），
     * 那里判「要不要问一声」（用户的偏好）。
     */
    fun denyReason(ctx: Context, risk: PrivRisk, asRoot: Boolean): String? {
        val reach = reach(ctx) ?: return "no_channel"
        // 没就绪的通道先拦：这类失败与命令本身无关，重试多少次都一样
        if (!reach.usable) return reach.reason ?: "no_channel"
        if (asRoot && !reach.canRoot) {
            return if (reach.channel == PermissionManager.Channel.ADB) "adb_root_disabled" else "root_unavailable"
        }
        if (reach.channel == PermissionManager.Channel.ADB) {
            // 走容器内脚本，所以要求 rootfs 在（脚本装在 /root/.dsh 下）
            if (!DshEnv.rootfs(ctx).isDirectory) return "runtime_missing"
            // 脚本自己也会拦，这里提前拦是为了给出一个能指路的 reason，
            // 而不是把脚本那句中文报错丢给 agent
            if (risk != PrivRisk.READONLY && !AdbBridge.granted(ctx, AdbBridge.ShellGrant.WRITE)) {
                return "adb_write_disabled"
            }
        }
        return null
    }

    /** 一次执行的结果。`note` 非 null 表示这次根本没跑成（通道没了、超时…）。 */
    data class ExecOutcome(
        val exit: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
        val note: String?,
    )

    private val busy = AtomicBoolean(false)

    /**
     * 单飞：同一时刻只允许一条特权命令。
     *
     * 两个理由：弹窗与审计会互相穿插（用户看到的是两条命令叠在一起，批准了哪条说不清），
     * 而且 libsu 的 shell 是一条管道，第二条命令会排在被卡住的那条后面。
     */
    fun tryEnter(): Boolean = busy.compareAndSet(false, true)

    fun exit() {
        busy.set(false)
    }

    /**
     * 执行一条命令。**阻塞**，与 [DshCamera] 同样由调用它的桥线程承担（桥本来就是
     * 每请求一个线程）。
     */
    fun exec(ctx: Context, command: String, asRoot: Boolean, timeoutMs: Long): ExecOutcome {
        val reach = reach(ctx) ?: return ExecOutcome(-1, "", "", false, "no_channel")
        val limit = timeoutMs.coerceIn(1_000L, MAX_TIMEOUT_MS)
        return when (reach.channel) {
            PermissionManager.Channel.ROOT -> execViaRootShell(command, limit)
            PermissionManager.Channel.SHIZUKU -> execViaShizuku(ctx, command, limit)
            PermissionManager.Channel.ADB -> execViaAdb(ctx, command, asRoot, limit)
            PermissionManager.Channel.NONE -> ExecOutcome(-1, "", "", false, "no_channel")
        }
    }

    private fun execViaRootShell(command: String, timeoutMs: Long): ExecOutcome {
        val out = ArrayList<String>()
        val err = ArrayList<String>()
        val shell = try {
            getRootShell()
        } catch (e: Throwable) {
            Log.w(TAG, "root shell 不可用: ${e.message}")
            return ExecOutcome(-1, "", "", false, "channel_lost")
        }
        // 缓存的那条 shell 可能是降级出来的 sh（createRootShellSafe 会在 su 失败时退到 sh），
        // 那种情况下「以 root 执行」是假的，必须当场说清楚而不是让命令以普通身份跑完
        if (!shell.isRoot) return ExecOutcome(-1, "", "", false, "root_lost")

        var result: Shell.Result? = null
        val worker = Thread {
            result = runCatching { shell.newJob().add(command).to(out, err).exec() }.getOrNull()
        }
        worker.isDaemon = true
        worker.start()
        worker.join(timeoutMs)
        if (worker.isAlive) {
            // libsu 的 exec() 没有超时。卡住的命令不能留在缓存 shell 里 —— 后面每一次
            // 特权调用都会排在同一条管道后面，看上去像是「特权坏了」。
            runCatching { APatchCli.refresh() }
            return ExecOutcome(-1, clip(out.joinToString("\n")), clip(err.joinToString("\n")), true, "timeout")
        }
        val r = result ?: return ExecOutcome(-1, "", "", false, "channel_lost")
        return ExecOutcome(r.code, clip(out.joinToString("\n")), clip(err.joinToString("\n")), false, null)
    }

    /**
     * Shizuku 通道：走用户服务，命令在 Shizuku 自己的进程里执行。
     *
     * 这里**不能**用 `Shizuku.newProcess` —— 它的返回类型在本项目的依赖版本里是库内部可见的
     * （@RestrictTo），应用侧根本编译不过。那是库在表达「别走这条路」，官方做法就是用户服务：
     * Shizuku 在自己的进程里实例化 [DshShizukuShellService]，于是身份由它决定。
     *
     * 失败一律 `channel_lost`：需要重来的是「去设置里刷新权限 / 重开 Shizuku」，
     * 而不是重试这条命令。
     */
    private fun execViaShizuku(ctx: Context, command: String, timeoutMs: Long): ExecOutcome {
        val json = DshShizukuShell.exec(ctx, command, timeoutMs)
            ?: return ExecOutcome(-1, "", "", false, "channel_lost")
        return ExecOutcome(
            json.optInt("exit", -1),
            clip(json.optString("stdout")),
            clip(json.optString("stderr")),
            json.optBoolean("timedOut"),
            if (json.optBoolean("failed")) "channel_lost" else null,
        )
    }

    /**
     * 无线 ADB 通道：交给容器内的 `adb-shell.py`。
     *
     * 这里**不带** `DSH_INTERNAL=1` —— 那个环境变量是给 App 自己的调用（设置页的配对、
     * 用户手点的测试）用的，会跳过脚本的写操作关卡。agent 的调用必须走脚本自己的关卡，
     * 这样「通道允许什么」在两条路上是同一份事实。
     */
    private fun execViaAdb(ctx: Context, command: String, asRoot: Boolean, timeoutMs: Long): ExecOutcome {
        val argv = buildString {
            append("python3 /root/.dsh/adb-shell.py")
            if (asRoot) append(" --su")
            append(' ').append(shellArg(command))
        }
        val raw = try {
            DshRuntime.execRootfsForOutput(argv, timeoutMs)
        } catch (e: Throwable) {
            Log.w(TAG, "adb-shell 执行失败: ${e.message}")
            return ExecOutcome(-1, "", "", false, "channel_lost")
        }
        val exit = Regex("\\[EXIT=(-?\\d+)]").findAll(raw).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
        val body = raw.replace(Regex("\\n?\\[EXIT=-?\\d+]\\s*$"), "")
        // 脚本没打印 [EXIT=…] 说明它自己就死在中途（超时被杀、解释器崩），此时 raw 里
        // 通常就是唯一有用的线索，所以整段当 stderr 交回去
        if (exit == null) return ExecOutcome(-1, "", clip(body), false, "channel_lost")
        return ExecOutcome(exit, clip(body), "", false, null)
    }

    private fun shellArg(value: String): String =
        if (value.matches(Regex("[A-Za-z0-9._:/+=,-]+"))) value
        else "'" + value.replace("'", "'\\''") + "'"

    private fun clip(text: String): String =
        if (text.length <= MAX_OUTPUT_CHARS) text
        else text.take(MAX_OUTPUT_CHARS) + "\n…(已截断，完整长度 ${text.length})"
}
