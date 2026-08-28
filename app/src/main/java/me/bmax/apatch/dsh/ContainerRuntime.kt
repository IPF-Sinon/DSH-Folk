package me.bmax.apatch.dsh

import android.content.Context
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

    /** 人类可读名称。 */
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

    companion object {
        /** 两个运行时共用的 bind 列表。 */
        val BINDS: Array<Array<String>> = arrayOf(
            arrayOf("/dev"),
            arrayOf("/dev/urandom", "/dev/random"),
            arrayOf("/proc"),
            arrayOf("/sys"),
            arrayOf("/proc/self/fd", "/dev/fd"),
            arrayOf("/storage/emulated/0", "/sdcard"),
            arrayOf("/storage/emulated/0", "/storage/emulated/0"),
        )
    }

    /** 现有实现：Termux proot，APK 内置。 */
    class Proot(private val ctx: Context, private val nativeLibProot: File?) : ContainerRuntime {
        override fun id() = "proot"
        override fun displayName() = "proot（内置，稳定）"
        override fun available() = nativeLibProot != null && nativeLibProot.exists()
        override fun unavailableReason() =
            if (available()) "" else "APK 内的 libproot.so 缺失（安装包可能损坏，建议重装）"

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
            return argv
        }

        override fun applyEnv(env: MutableMap<String, String>, baseDir: File, libDir: File, tmpDir: File) {
            // 由 DshRuntime.applyProotEnv 统一处理
        }

        override fun prepare() { /* 内置库由系统提取，无额外准备 */ }
    }

    /** 实验实现：proroot（coderredlab/proroot），LD_PRELOAD 路径翻译，零 ptrace。 */
    class Proroot(private val ctx: Context, private val dir: File) : ContainerRuntime {
        override fun id() = "proroot"
        override fun displayName() = "proroot（实验，零 ptrace 开销）"

        override fun available(): Boolean = LIBS.all { File(dir, it).let { f -> f.isFile && f.length() > 0 } }

        override fun unavailableReason(): String {
            val missing = LIBS.filter { File(dir, it).let { f -> !f.isFile || f.length() == 0L } }
            if (missing.isEmpty()) return ""
            return "缺 ${missing.size} 个运行时文件（${missing.first()} 等）—— 这些随 APK 分发，缺失说明安装包不完整，建议重装"
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
}
