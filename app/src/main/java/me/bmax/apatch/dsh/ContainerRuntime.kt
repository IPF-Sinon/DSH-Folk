package me.bmax.apatch.dsh

import android.content.Context
import me.bmax.apatch.R
import me.bmax.apatch.util.appString
import java.io.File

/**
 * 容器运行时抽象：把「怎么进 rootfs 执行命令」独立出来，让 proot 与 proroot 并存、可切换。
 *
 * 移植自 DSHA 的 ContainerRuntime（MIT, qiannianhuanxiang）。真正与运行时绑定的只有
 * baseArgv() 组装的参数和几个环境变量；rootfs 本身一个字节都不用改。
 *
 * - [Proot]   Termux 的 proot（APK 内置 libproot.so），默认、稳定。
 * - [Proroot] coderredlab/proroot，LD_PRELOAD + 二进制补丁，零 ptrace 开销，实验性。
 */
interface ContainerRuntime {
    /** 运行时标识（proot | proroot），用于 UI 显示与偏好存储。 */
    fun id(): String

    /**
     * 人类可读名称。
     *
     * 实现需要 Context 取本地化字符串 —— 这两个名字既进首页运行方式卡，也进启动日志。
     */
    fun displayName(): String

    /** 二进制是否齐备、现在就能用。 */
    fun available(): Boolean

    /** 缺什么（available() 为 false 时给出下一步）。 */
    fun unavailableReason(): String

    /**
     * 组装进入 rootfs 的命令前缀（不含最终要跑的 /bin/bash …）。
     * 调用方追加 `/bin/bash -c <cmd>` 或 `/bin/bash`。
     */
    fun baseArgv(rootfsDir: File, hardlinkSupported: Boolean): List<String>

    /** 设置进程环境（LD_LIBRARY_PATH、TMPDIR 之类）。 */
    fun applyEnv(env: MutableMap<String, String>, baseDir: File, libDir: File, tmpDir: File)

    /** 首次使用前的准备（复制依赖库等）。抛异常表示准备失败，调用方应回退。 */
    fun prepare()

    /**
     * 让运行时**自己接管**「怎么把 guest 命令送进容器」。
     *
     * proot/proroot 的 argv 是「运行时前缀 + guest 命令」，调用方直接拼即可；但 chroot 必须以
     * **真 root** 启动（`su -c …`），而 `su -c` 只接受**一个命令字符串** —— 把任意 guest 命令
     * 再塞进那层引号就是两层转义地狱（命令里可能有单引号、换行、`$`）。所以 chroot 把命令写进
     * 脚本文件交给 root 侧执行，argv 里因此不含 guest 命令。
     *
     * 传 [env] 是必要的：`su` 不保证继承调用方环境（Magisk 会自己改 PATH），chroot 把这份环境
     * 以 export 形式写进脚本。
     *
     * @return null = 用 `baseArgv() + guest`（proot/proroot 既有行为，一个字节不变）。
     */
    fun spawnArgv(guest: List<String>, env: Map<String, String>): List<String>? = null

    companion object {
        /**
         * 非存储类 bind（两个运行时共用、与黑白名单无关）。
         *
         * 共享存储（`/storage/emulated/0` → `/sdcard` 等）**不在这里**：它按用户的文件访问
         * 黑白名单动态组装，见 [DshFileAccess.storageBinds]。
         */
        val BINDS: Array<Array<String>> = arrayOf(
            arrayOf("/dev"),
            arrayOf("/dev/urandom", "/dev/random"),
            arrayOf("/proc"),
            arrayOf("/sys"),
            arrayOf("/proc/self/fd", "/dev/fd"),
        )

        /**
         * 共享存储的 bind 对（host:guest），已套用黑白名单。空目录遮蔽源在 [DshEnv.fsMaskDir]，
         * 这里顺带确保它存在。两个运行时共用同一份组装逻辑。
         */
        fun storageBinds(ctx: Context): List<Pair<String, String>> {
            // 挂载总开关关：一条存储 bind 都不给，容器彻底看不到 /sdcard（dsh-fs 侧另有拦截）
            if (!DshFileAccess.mountEnabled(ctx)) return emptyList()
            val mask = DshEnv.fsMaskDir(ctx)
            mask.mkdirs()
            return DshFileAccess.storageBinds(ctx, mask.absolutePath)
        }

        /**
         * 「在工作区中挂载手机存储」的 bind 对（host:guest），已套用黑白名单。
         *
         * 与 [storageBinds] 同看一个开关（2026-10 两个开关合并成一个）：共享存储关着就一条都不给
         * —— 否则会出现「总开关关着、工作区却能看到 /sdcard」这种自相矛盾的状态。见
         * [DshFileAccess.workspaceBinds]。
         */
        fun workspaceBinds(ctx: Context): List<Pair<String, String>> {
            if (!DshFileAccess.mountEnabled(ctx)) return emptyList()
            val mask = DshEnv.fsMaskDir(ctx)
            mask.mkdirs()
            return DshFileAccess.workspaceBinds(ctx, mask.absolutePath)
        }
    }

    /** 现有实现：Termux proot，APK 内置。 */
    class Proot(private val ctx: Context, private val nativeLibProot: File?) : ContainerRuntime {
        override fun id() = "proot"
        override fun displayName() = ctx.appString(R.string.dsh_mode_proot_name)
        override fun available() = nativeLibProot != null && nativeLibProot.exists()
        override fun unavailableReason() =
            if (available()) "" else ctx.appString(R.string.dsh_mode_proot_missing)

        override fun baseArgv(rootfsDir: File, hardlinkSupported: Boolean): List<String> {
            val argv = ArrayList<String>()
            argv.add(nativeLibProot!!.absolutePath)
            // 只有文件系统不支持硬链接时才需要 link2symlink 模拟（会破坏 dsh write 工具）
            if (!hardlinkSupported) argv.add("--link2symlink")
            argv.add("-L")
            argv.add("--kill-on-exit")
            argv.add("-0")
            argv.add("--rootfs=${rootfsDir.absolutePath}")
            argv.add("--cwd=/root")
            for (b in BINDS) {
                argv.add("-b")
                argv.add(if (b.size == 1) b[0] else "${b[0]}:${b[1]}")
            }
            // 共享存储按黑白名单动态挂：整棵树/白名单目录在前，被禁目录用空目录盖在后（覆盖前者）
            for ((host, guest) in storageBinds(ctx)) {
                argv.add("-b")
                argv.add("$host:$guest")
            }
            // 「在工作区中挂载手机存储」：额外映到 /root/workspace 下（同样套黑白名单）
            for ((host, guest) in workspaceBinds(ctx)) {
                argv.add("-b")
                argv.add("$host:$guest")
            }
            return argv
        }

        override fun applyEnv(env: MutableMap<String, String>, baseDir: File, libDir: File, tmpDir: File) {
            // 由 DshRuntime.applyProotEnv 统一处理
        }

        override fun prepare() { /* 内置库由系统提取，无额外准备 */ }
    }

    /**
     * 实验实现：proroot（coderredlab/proroot），LD_PRELOAD 路径翻译，零 ptrace。
     *
     * **只支持 arm64-v8a**：上游只发布 arm64 的 .so（README 的 Requirements 明写
     * arm64-v8a + Ubuntu arm64 rootfs），x86_64 包里没有这五个文件，
     * [available] 自然为 false，[DshRuntime.runtime] 会静默用 proot。
     */
    class Proroot(private val ctx: Context, private val dir: File) : ContainerRuntime {
        override fun id() = "proroot"
        override fun displayName() = ctx.appString(R.string.dsh_mode_proroot_name)

        override fun available(): Boolean = LIBS.all { File(dir, it).let { f -> f.isFile && f.length() > 0 } }

        override fun unavailableReason(): String {
            val missing = LIBS.filter { File(dir, it).let { f -> !f.isFile || f.length() == 0L } }
            if (missing.isEmpty()) return ""
            // 非 arm64 设备上五个文件必然全缺，报「缺 5 个文件」会把用户引到去找文件；
            // 真实原因是上游不支持这个架构，直接说清楚。
            if (!android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
                return ctx.appString(R.string.dsh_mode_proroot_arch_only)
            }
            return ctx.appString(R.string.dsh_mode_proroot_missing, missing.size, missing.first())
        }

        override fun baseArgv(rootfsDir: File, hardlinkSupported: Boolean): List<String> {
            // 写法照 proroot 作者的 andClaw 实测版本：bind 一律 host:guest 显式形式，
            // 额外把真实目录挂成 /dev/shm，--link2symlink 无条件加。
            val argv = ArrayList<String>()
            argv.add(File(dir, "libproroot.so").absolutePath)
            argv.add("-r")
            argv.add(rootfsDir.absolutePath)
            argv.add("-0")
            argv.add("-w")
            argv.add("/root")
            for (b in BINDS) {
                argv.add("-b")
                argv.add(if (b.size == 1) "${b[0]}:${b[0]}" else "${b[0]}:${b[1]}")
            }
            // 共享存储按黑白名单动态挂（与 proot 同一份组装）：被禁目录用空目录遮蔽
            for ((host, guest) in storageBinds(ctx)) {
                argv.add("-b")
                argv.add("$host:$guest")
            }
            // 「在工作区中挂载手机存储」：额外映到 /root/workspace 下（与 proot 同一份组装）
            for ((host, guest) in workspaceBinds(ctx)) {
                argv.add("-b")
                argv.add("$host:$guest")
            }
            val shm = DshEnv.shmDir(ctx)
            shm.mkdirs()
            argv.add("-b")
            argv.add("${shm.absolutePath}:/dev/shm")
            argv.add("--link2symlink")
            return argv
        }

        override fun applyEnv(env: MutableMap<String, String>, baseDir: File, libDir: File, tmpDir: File) {
            env["PROROOT_TMP_DIR"] = tmpDir.absolutePath
            env["PROROOT_LIB_PATH"] = File(dir, "libproroot-runtime.so").absolutePath
            env["PROROOT_LINKER_PATH"] = File(dir, "libproroot-linker.so").absolutePath
            env["PROROOT_STUB_LOADER"] = File(dir, "libproroot-stub-loader.so").absolutePath
        }

        override fun prepare() {
            for (n in LIBS) {
                if (!File(dir, n).isFile) throw IllegalStateException("proroot 运行时缺 $n")
            }
            DshEnv.shmDir(ctx).mkdirs()
        }

        companion object {
            /** 五个 .so 都得在同一目录，启动器靠 /proc/self/exe 的 dirname 找同伴。 */
            val LIBS = arrayOf(
                "libproroot.so",
                "libproroot-runtime.so",
                "libproroot-linker.so",
                "libproroot-stub-loader.so",
                "libproroot-bridge.so",
            )

            /** 存放目录：APK 的 jniLibs 提取目录（Android 10+ W^X：只有这里能执行）。 */
            fun defaultDir(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir)
        }
    }

    /**
     * 真 root 的 chroot 启动方式（需要 root 权限通道）。
     *
     * ## 它解决什么
     *
     * proot 是 ptrace 模拟（不要 root、兼容性最好），proroot 是 LD_PRELOAD 路径翻译。两者的
     * 共同问题是**进程仍在 zygote 的 seccomp 域里**、且拿不到 CAP_SYS_ADMIN —— 于是 DSH 的
     * 工作区沙箱（bwrap / Landlock）在容器内一律不可用。chroot 走真 root（su 域）：
     *  - 可以 `mount --bind` 组装自己的挂载视图；
     *  - seccomp 由 su 域决定（宽松得多），**bwrap 的 mount namespace 有机会跑起来**。
     *
     * ## 它不是什么（重要）
     *
     * **chroot 本身不是沙箱**：只改根目录，不拦网络 / 设备 / 内核接口，且有经典逃逸手法。
     * 它在此的价值是「提供真 root 上下文，让 bwrap 有机会工作」，而不是自己提供隔离。
     * 已实测：Landlock 在该设备**内核层**就不可用（`landlock-run --probe` 返回 125），那条路
     * chroot 也救不了。
     *
     * ## 实现要点
     *
     * - `su -c` 只吃**一个命令字符串**，所以 guest 命令不拼进 argv，而是写进脚本文件；
     * - 挂载与清理都在 root 侧脚本里，trap 保证异常退出也卸载；
     * - 环境以 export 写进脚本（`su` 会重置环境，不能指望继承）。
     */
    class Chroot(private val ctx: Context) : ContainerRuntime {
        override fun id() = "chroot"

        override fun displayName() = ctx.appString(R.string.dsh_mode_chroot_name)

        /** 需要 root 通道就绪。不等同于「沙箱可用」—— 沙箱能不能用是 DSH 侧的事。 */
        override fun available(): Boolean =
            PrivilegedShell.reach(ctx)?.channel == PermissionManager.Channel.ROOT

        override fun unavailableReason(): String = ctx.appString(R.string.dsh_mode_chroot_needs_root)

        /** 不用宿主 argv 前缀：命令经脚本送进去，见 [spawnArgv]。 */
        override fun baseArgv(rootfsDir: File, hardlinkSupported: Boolean): List<String> = emptyList()

        override fun applyEnv(env: MutableMap<String, String>, baseDir: File, libDir: File, tmpDir: File) {
            // 宿主进程不需要设什么；真正生效的环境在启动脚本里
        }

        override fun prepare() {
            val dir = workDir(ctx)
            if (!dir.isDirectory && !dir.mkdirs()) {
                throw IllegalStateException("无法创建 " + dir.absolutePath)
            }
        }

        /**
         * 生成启动 argv：`/system/bin/sh -c "<su> -c 'sh <launch.sh>'"`。
         *
         * **su 的路径必须探测，不能写死**：用户设备上 `/system/bin/su` 根本不存在
         * （`sh: su: inaccessible or not found`），因为 KernelSU / APatch 那类实现的 su 在
         * `/debug_ramdisk/su`、`/data/adb/ksu/bin/su` 等位置，且常常只有 root 域可见。
         * 这里用 [PrivilegedShell.resolveSuPath] 借一条已生效的 root shell 问出真实路径。
         *
         * guest 命令与环境写进脚本文件，argv 里不含它们 —— 命令里有单引号、换行、`$` 都
         * 不会被 `su -c` 那层引号破坏。
         */
        override fun spawnArgv(guest: List<String>, env: Map<String, String>): List<String>? {
            // 解析不到 su 就直接放弃（返回 null → 调用方回退 proot），
            // 而不是拼一条注定以 "su: not found" 失败的 argv。
            val su = PrivilegedShell.resolveSuPath(ctx) ?: return null
            prepare()
            val dir = workDir(ctx)
            val rootfs = DshEnv.rootfs(ctx)
            val launch = File(dir, "launch.sh")
            // guest argv **逐项**传下去：绝不在这里拼成一条命令字符串。
            //
            // 曾经是 `guest.joinToString(" ") { shellQuote(it) }` 再被外壳又 quote 一次 ——
            // 那是两层转义，真机报 `launch.sh[26]: no closing quote`（issue #12），
            // 而且 `bash -c` 收到的参数会错位成 `/bin/bash`。见 buildLaunchScript 的写法。
            launch.writeText(buildLaunchScript(rootfs, dir, env, guest), Charsets.UTF_8)
            launch.setExecutable(true, false)
            return listOf(
                "/system/bin/sh", "-c",
                shellQuote(su) + " -c " + shellQuote("sh " + shellQuote(launch.absolutePath)),
            )
        }

        /**
         * 拼启动脚本：建挂载视图 → chroot → 执行 guest 命令 → 退出时卸载。
         *
         * ## 为什么用 `"$@"` 传 guest argv
         *
         * 脚本是**由 shell 解析两次**的（`su -c 'sh launch.sh'`，再由 launch.sh 自己 exec），
         * 所以任何「先把 argv 拼成一条命令字符串、再整体 quote」的写法都会变成两层转义。
         * 真机上表现就是 `launch.sh[26]: no closing quote`，且 `bash -c` 拿到的参数错位
         * （第一个参数成了 `/bin/bash` 本身）—— issue #12。
         *
         * 正确做法：把 guest argv **逐项**写成脚本自己的位置参数（`set -- a b c`），
         * 每项只经过 [shellQuote] **一次**，最后用 `"${'$'}@"` 展开。这样：
         *  - 引号配对由 shell 自己保证，命令里出现单引号/换行/`$` 都不会破坏结构；
         *  - argv 边界被完整保留（`bash -c "<cmd>"` 的 `<cmd>` 仍是**一个**参数）。
         */
        private fun buildLaunchScript(
            rootfs: File,
            dir: File,
            env: Map<String, String>,
            guest: List<String>,
        ): String {
            val mnt = "\${'$'}MNT"
            val storage = storageBinds(ctx).joinToString("\n") { (host, g) ->
                "mkdir -p \"$mnt$g\" && mount --bind ${shellQuote(host)} \"$mnt$g\" 2>/dev/null"
            }
            // 环境只收不含空格/单引号的项：脚本里用单引号包住 k=v，含这些字符会破坏引号
            val envLine = env.entries
                .filter { (k, v) -> k.none { it == ' ' || it == '\'' } && v.none { it == ' ' || it == '\'' } }
                .joinToString(" ") { (k, v) -> shellQuote("$k=$v") }
            // guest argv → 脚本的位置参数，每项只 quote 一次
            val guestArgs = guest.joinToString(" ") { shellQuote(it) }
            val script = StringBuilder()
            script.append("#!/system/bin/sh\n")
            script.append("# DSH-Folk chroot 启动器（App 生成，勿手改）\n")
            script.append("RFS=").append(shellQuote(rootfs.absolutePath)).append('\n')
            script.append("MNT=").append(shellQuote(File(dir, "mnt").absolutePath)).append('\n')
            script.append("cleanup() {\n")
            script.append("  for d in \"$mnt/dev\" \"$mnt/proc\" \"$mnt/sys\"; do umount -l \"\$d\" 2>/dev/null; done\n")
            script.append("}\n")
            script.append("trap cleanup EXIT INT TERM\n")
            script.append("mkdir -p \"$mnt\" || exit 90\n")
            script.append("mount -t tmpfs tmpfs \"$mnt\" || exit 91\n")
            script.append("mkdir -p \"$mnt/dev\" \"$mnt/proc\" \"$mnt/sys\"\n")
            script.append("mount --bind /dev \"$mnt/dev\" || exit 92\n")
            script.append("mount -t proc proc \"$mnt/proc\" || exit 93\n")
            script.append("mount --bind /sys \"$mnt/sys\" 2>/dev/null\n")
            if (storage.isNotEmpty()) script.append(storage).append('\n')
            // guest argv 交给脚本位置参数；`--` 防止某项以 `-` 开头时被 set 当选项
            script.append("set -- ").append(guestArgs).append('\n')
            // 进 chroot 执行：env -i 起干净环境，再逐个喂我们这份，最后摊开 "$@"
            script.append("exec chroot \"\$RFS\" /usr/bin/env -i ").append(envLine)
            script.append(" \"\$\@\"\n")
            return script.toString()
        }

        companion object {
            /** 脚本与挂载点的工作目录（放 rootfs 之外，免得被运行时替换清掉）。 */
            fun workDir(ctx: Context): File = File(ctx.filesDir, "chroot")

            /** POSIX 单引号转义：值里的 `'` 写成 `'\''`。 */
            fun shellQuote(value: String): String =
                "'" + value.replace("'", "'\\''") + "'"
        }
    }
}
