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
 * 发送日志的时间窗口。裁剪只作用于 logcat（其余命令要么需要 root、要么本就无时间戳）。
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
        shell.newJob().add("tar -czf ${tombstonesFile.absolutePath} -C /data/tombstones .").exec()
        shell.newJob().add("tar -czf ${dropboxFile.absolutePath} -C /data/system/dropbox .").exec()
        shell.newJob().add("tar -czf ${pstoreFile.absolutePath} -C /sys/fs/pstore .").exec()
        shell.newJob().add("tar -czf ${diagFile.absolutePath} -C /data/vendor/diag . --exclude=./minidump.gz").exec()
        shell.newJob().add("tar -czf ${oplusFile.absolutePath} -C /mnt/oplus/op2/media/log/boot_log/ .").exec()
        shell.newJob().add("tar -czf ${bootlogFile.absolutePath} -C /data/adb/ap/log .").exec()

        shell.newJob().add("cat /proc/1/mountinfo > ${mountsFile.absolutePath}").exec()
        shell.newJob().add("cat /proc/filesystems > ${fileSystemsFile.absolutePath}").exec()
        shell.newJob().add("cat /proc/kallsyms > ${kallsymsFile.absolutePath}").exec()
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
