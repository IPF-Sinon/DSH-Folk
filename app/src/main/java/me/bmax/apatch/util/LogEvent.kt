package me.bmax.apatch.util

import android.content.Context
import android.os.Build
import android.system.Os
import com.topjohnwu.superuser.ShellUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import me.bmax.apatch.R

/**
 * 发送日志的时间窗口。
 *
 * 窗口作用于**所有带时间信息的采集项**：
 *
 * | 采集项 | 怎么裁 |
 * |---|---|
 * | `logcat.txt` | `logcat -d -T '<起点>'`，ROM 不认 `-T` 时回落到按行首时间戳过滤 |
 * | `dmesg.txt` | 行首是相对开机秒，按 `/proc/uptime` 换算下界后过滤 |
 * | `tombstones` / `dropbox` / `pstore` / `diag` / `oplus` / `bootlog` | `find -mmin` 出清单交给 `tar -T` |
 * | `kallsyms.txt` | 窗口内没有任何崩溃转储就不采集（它只用于符号化） |
 *
 * 其余项（`props` / `mounts` / `cpuinfo` / `packages` / `defconfig` / `ap_tree`）是**当前状态
 * 快照**，没有时间维度，无论选哪个窗口都原样收集。
 *
 * 所以「选了短窗口但归档没小多少」有三种正常原因：设备刚开机（缓冲区本来就短于窗口）、
 * 窗口内确实发生过崩溃（转储被保留）、或剩下的体积本就来自无时间维度的状态快照。
 * `basic.txt` 里的 `Collected` 与 `Uptime` 两行就是为了让收报告的人一眼分辨这三种情况。
 *
 * @param minutes 回看多少分钟；0 表示全量。
 */
enum class LogWindow(val minutes: Int, val labelRes: Int) {
    M10(10, R.string.dsh_log_window_10m),
    M30(30, R.string.dsh_log_window_30m),
    H1(60, R.string.dsh_log_window_1h),
    H12(720, R.string.dsh_log_window_12h),
    All(0, R.string.dsh_log_window_all),
}

suspend fun getBugreportFile(context: Context, window: LogWindow = LogWindow.All): File = withContext(Dispatchers.IO) {

    val bugreportDir = File(context.cacheDir, "bugreport")
    bugreportDir.mkdirs()

    val dmesgFile = File(bugreportDir, "dmesg.txt")
    val logcatFile = File(bugreportDir, "logcat.txt")
    val tombstonesFile = File(bugreportDir, "tombstones.tar.gz")
    val dropboxFile = File(bugreportDir, "dropbox.tar.gz")
    val pstoreFile = File(bugreportDir, "pstore.tar.gz")
    val diagFile = File(bugreportDir, "diag.tar.gz")
    val oplusFile = File(bugreportDir, "oplus.tar.gz")
    val bootlogFile = File(bugreportDir, "bootlog.tar.gz")
    val kallsymsFile = File(bugreportDir, "kallsyms.txt")
    val cpuinfoFile = File(bugreportDir, "cpuinfo.txt")
    val cmdlineFile = File(bugreportDir, "cmdline.txt")
    val mountsFile = File(bugreportDir, "mounts.txt")
    val fileSystemsFile = File(bugreportDir, "filesystems.txt")
    val apFileTree = File(bugreportDir, "ap_tree.txt")
    val appListFile = File(bugreportDir, "packages.txt")
    val propFile = File(bugreportDir, "props.txt")
    val packageConfigFile = File(bugreportDir, "package_config")
    val kernelConfig = File(bugreportDir, "defconfig")

    val cutoffMillis = System.currentTimeMillis() - window.minutes * 60_000L
    val cutoffTs = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(cutoffMillis)

    tryGetRootShell().use { shell ->
        // 崩溃转储目录按 mtime 收窗口内的文件：find 出相对路径清单，再交给 tar -T。
        // toybox find 的 `-mmin -N` 语义是「距今不足 N 分钟」（compare_numsign 的 '-' 分支），
        // GNU find 同义，两边都可用。只列 -type f：清单里出现目录会让 tar 递归整棵子树，
        // 把目录下的旧文件一起带进来。
        //
        // 清单落在 bugreportDir，不在被扫描目录内 —— 否则它自己刚创建、mtime 是现在，
        // 会被 find 命中而进归档。目录不存在时 `cd` 失败、`&&` 短路，清单保持空，
        // tar 产出空归档（toybox `tar_main` 里 `!TT.incl && !FLAG(T)` 才报 empty archive，
        // 给了 -T 就不报），与改动前对缺失目录的行为一致。
        //
        // extra 里的 `--exclude` 必须排在文件参数**之前**：GNU tar 的 --exclude 是位置相关的，
        // 放在 `.` 或 `-T` 之后会打印 "has no effect" 并以失败退出（toybox 的参数解析不看
        // 位置，所以旧写法只是在设备上侥幸生效）。
        fun tarDir(out: File, dir: String, extra: String = "") {
            if (window == LogWindow.All) {
                shell.newJob().add("tar -czf ${out.absolutePath} -C $dir $extra .").exec()
                return
            }
            val list = File(bugreportDir, "${out.name}.list")
            shell.newJob().add(
                "touch ${list.absolutePath}; " +
                    "cd $dir && find . -type f -mmin -${window.minutes} > ${list.absolutePath}; " +
                    "tar -czf ${out.absolutePath} -C $dir $extra -T ${list.absolutePath}; " +
                    "rm -f ${list.absolutePath}"
            ).exec()
        }

        shell.newJob().add("dmesg > ${dmesgFile.absolutePath}").exec()
        if (window == LogWindow.All) {
            shell.newJob().add("logcat -d > ${logcatFile.absolutePath}").exec()
        } else {
            shell.newJob().add("logcat -d -T '$cutoffTs' > ${logcatFile.absolutePath}").exec()
        }
        // -T 在个别 ROM 上不被接受会输出空文件：回落到全量 + 行首时间戳自行过滤。
        if (window != LogWindow.All && logcatFile.length() == 0L) {
            val full = ShellUtils.fastCmd(shell, "logcat -d")
            logcatFile.writeText(filterLogcatByTime(full, cutoffMillis))
        }
        // dmesg 行首是相对开机秒（无绝对时间），按 /proc/uptime 换算窗口下界再过滤。
        val uptimeSeconds = ShellUtils.fastCmd(shell, "cat /proc/uptime")
            .trim().substringBefore(' ').toDoubleOrNull()
        if (window != LogWindow.All && uptimeSeconds != null && dmesgFile.length() > 0) {
            val cutoffUptime = uptimeSeconds - window.minutes * 60.0
            dmesgFile.writeText(filterDmesgByUptime(dmesgFile.readText(), cutoffUptime))
        }
        tarDir(tombstonesFile, "/data/tombstones")
        tarDir(dropboxFile, "/data/system/dropbox")
        tarDir(pstoreFile, "/sys/fs/pstore")
        tarDir(diagFile, "/data/vendor/diag", "--exclude=./minidump.gz")
        tarDir(oplusFile, "/mnt/oplus/op2/media/log/boot_log/")
        tarDir(bootlogFile, "/data/adb/ap/log")

        shell.newJob().add("cat /proc/1/mountinfo > ${mountsFile.absolutePath}").exec()
        shell.newJob().add("cat /proc/filesystems > ${fileSystemsFile.absolutePath}").exec()
        // kallsyms 是这份归档里最大的一项（实测 4.3 MB，压缩后仍占归档近一半），
        // 唯一用途是给内核崩溃地址符号化。窗口内没有任何崩溃转储时就别收了。
        val wantKallsyms = window == LogWindow.All || ShellUtils.fastCmd(
            shell,
            "find /data/tombstones /data/system/dropbox /sys/fs/pstore " +
                "-type f -mmin -${window.minutes} 2>/dev/null | head -1"
        ).isNotBlank()
        if (wantKallsyms) {
            shell.newJob().add("cat /proc/kallsyms > ${kallsymsFile.absolutePath}").exec()
        }
        shell.newJob().add("cat /proc/cpuinfo > ${cpuinfoFile.absolutePath}").exec()
        shell.newJob().add("cat /proc/cmdline > ${cmdlineFile.absolutePath}").exec()
        shell.newJob().add("ls -alRZ /data/adb > ${apFileTree.absolutePath}").exec()
        shell.newJob().add("cp /data/system/packages.list ${appListFile.absolutePath}").exec()
        shell.newJob().add("getprop > ${propFile.absolutePath}").exec()
        shell.newJob().add("cp /data/adb/ap/package_config ${packageConfigFile.absolutePath}").exec()
        shell.newJob().add("zcat /proc/config.gz > ${kernelConfig.absolutePath}").exec()

        val selinux = ShellUtils.fastCmd(shell, "getenforce")

        // basic information
        val buildInfo = File(bugreportDir, "basic.txt")
        PrintWriter(FileWriter(buildInfo)).use { pw ->
            pw.println("Kernel: ${System.getProperty("os.version")}")
            pw.println("BRAND: " + Build.BRAND)
            pw.println("MODEL: " + Build.MODEL)
            pw.println("PRODUCT: " + Build.PRODUCT)
            pw.println("MANUFACTURER: " + Build.MANUFACTURER)
            pw.println("SDK: " + Build.VERSION.SDK_INT)
            pw.println("PREVIEW_SDK: " + Build.VERSION.PREVIEW_SDK_INT)
            pw.println("FINGERPRINT: " + Build.FINGERPRINT)
            pw.println("DEVICE: " + Build.DEVICE)
            pw.println("Manager: " + Version.getManagerVersion())
            pw.println("SELinux: $selinux")
            pw.println("LogWindow: " + context.getString(window.labelRes))
            // 窗口只能裁掉「有时间信息」的内容；采集时刻和开机时长写进来，
            // 收到报告的人一眼能看出「归档没变小」是因为设备刚开机、缓冲区本来就短
            pw.println("Collected: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(System.currentTimeMillis()))
            pw.println("Uptime: " + uptimeSeconds.let { if (it == null) "unknown" else "%.0fs".format(it) })

            val uname = Os.uname()
            pw.println("KernelRelease: ${uname.release}")
            pw.println("KernelVersion: ${uname.version}")
            pw.println("Mahcine: ${uname.machine}")
            pw.println("Nodename: ${uname.nodename}")
            pw.println("Sysname: ${uname.sysname}")

            pw.println("DshRuntime: ${me.bmax.apatch.dsh.DshRuntime.state.value.runtimeVersion}")
            pw.println("DshPhase: ${me.bmax.apatch.dsh.DshRuntime.state.value.phase}")
            pw.println("ContainerRuntime: ${me.bmax.apatch.dsh.DshRuntime.runtimeId()}")
            pw.println("PermissionChannel: ${me.bmax.apatch.dsh.PermissionManager.status.value.channel}")
        }

        // DSH 启动日志（替代原来的内核模块列表）
        val dshLogFile = File(bugreportDir, "dsh.log")
        dshLogFile.writeText(runCatching { me.bmax.apatch.dsh.DshRuntime.tailLog(2000) }.getOrDefault(""))

        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH_mm")
        val current = LocalDateTime.now().format(formatter)

        val targetFile = File(context.cacheDir, "DSH-Folk_bugreport_${current}.tar.gz")

        shell.newJob().add("tar czf ${targetFile.absolutePath} -C ${bugreportDir.absolutePath} .")
            .exec()
        shell.newJob().add("rm -rf ${bugreportDir.absolutePath}").exec()
        val uid = android.os.Process.myUid()
        shell.newJob().add("chown $uid:$uid ${targetFile.absolutePath}").exec()
        shell.newJob().add("chmod 0644 ${targetFile.absolutePath}").exec()

        targetFile
    }
}

/**
 * 按行首时间戳过滤 logcat 全量输出（`-T` 兜底用）。
 *
 * logcat 行首是 `MM-DD HH:MM:SS.mmm`（无年份），窗口最长只有 12 小时，所以只要把
 * 「未来太远」的行当作跨年（往回推一年）即可正确落在当前窗口。没有可解析时间戳的行
 * （头部标题等）一律保留，宁可多给不漏。
 */
private fun filterLogcatByTime(text: String, cutoffMillis: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    val now = System.currentTimeMillis()
    val year = Calendar.getInstance().get(Calendar.YEAR)
    val yearMs = 366L * 24 * 60 * 60 * 1000L
    return text.lineSequence().filter { line ->
        val ts = line.take(18)
        if (ts.length < 18) return@filter true
        val parsed = try {
            val cur = fmt.parse("$year-$ts")?.time ?: return@filter true
            // 未来超过 6 小时 = 实际是去年同一日期（跨年边界）
            if (cur - now > 6 * 60 * 60 * 1000L) cur - yearMs else cur
        } catch (e: Exception) {
            return@filter true
        }
        parsed >= cutoffMillis
    }.joinToString("\n")
}

/**
 * 按行首相对开机秒过滤 dmesg。
 *
 * dmesg 行首是 `[ 1234.567890]`，那是**开机以来的秒数**，没有绝对时间，所以窗口下界要
 * 用采集时刻的 `/proc/uptime` 换算：`uptime - 窗口秒数`。下界为负（开机时长还不够一个
 * 窗口）说明整段 dmesg 都在窗口内，原样返回。
 *
 * 没有可解析时间戳的行（多行续行、内核自己打的无前缀输出）一律保留。
 */
private fun filterDmesgByUptime(text: String, cutoffUptime: Double): String {
    if (cutoffUptime <= 0.0) return text
    return text.lineSequence().filter { line ->
        val open = line.indexOf('[')
        val close = line.indexOf(']')
        if (open != 0 || close <= 1) return@filter true
        val secs = line.substring(1, close).trim().toDoubleOrNull() ?: return@filter true
        secs >= cutoffUptime
    }.joinToString("\n")
}
