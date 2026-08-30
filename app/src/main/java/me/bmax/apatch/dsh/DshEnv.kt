package me.bmax.apatch.dsh

import android.content.Context
import java.io.File

/**
 * DSH-Folk 运行时的目录与路径约定。
 *
 * DSH（DeepSeek Harness）跑在一个 Linux rootfs 里（proot/proroot 容器），rootfs
 * 与 Node/dsh 由 CI 打成 rootfs.tar.gz 放 GitHub Release，首次启动在线下载解压到
 * filesDir。可执行的 proot/proroot .so 随 APK 进 nativeLibraryDir（SELinux 允许执行）。
 */
object DshEnv {
    /** rootfs 解压根目录（Ubuntu base + node + dsh）。 */
    fun rootfs(ctx: Context): File = File(ctx.filesDir, "rootfs")

    /** 容器内 dsh 的 $DSH_HOME 对应宿主路径（rootfs/root/.dsh）。 */
    fun dshHome(ctx: Context): File = File(rootfs(ctx), "root/.dsh")

    /** proot 的 l2s 中间文件目录（无硬链接时启用），固定在 rootfs 内避免随 tmp 被清。 */
    fun l2sDir(ctx: Context): File = File(rootfs(ctx), ".l2s")

    /** 容器 TMPDIR 对应宿主路径。 */
    fun tmpDir(ctx: Context): File = File(rootfs(ctx), "tmp")

    /** 应用私有缓存下的 /dev/shm 顶替目录（proroot 用）。 */
    fun shmDir(ctx: Context): File = File(ctx.cacheDir, "shm")

    /** 下载运行时压缩包的落点。 */
    fun downloadZip(ctx: Context): File = File(ctx.filesDir, "runtime-download.tar.gz")

    /** 启动/运行日志文件。 */
    fun serverLog(ctx: Context): File = File(ctx.filesDir, "logs/dsh-web.log")

    /** APK 提取出的可执行 .so 所在目录（proot/proroot 必须从这里执行）。 */
    fun nativeLibDir(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir)

    /** rootfs 就绪标记：rootfs/root 存在且 dsh 可用（bin.js 或全局 dsh）。 */
    fun isRuntimeInstalled(ctx: Context): Boolean {
        val root = File(rootfs(ctx), "root")
        if (!root.isDirectory) return false
        // node 存在即视为可用（dsh 通过全局包或源码树，运行期再判定）
        return File(rootfs(ctx), "usr/bin").isDirectory || File(rootfs(ctx), "bin").isDirectory
    }

    const val DEFAULT_PORT = 3080
    const val PREF = "dshfolk"
    const val KEY_RUNTIME = "container_runtime"   // proot | proroot
    const val KEY_PORT = "dsh_port"
    const val KEY_RUNTIME_VERSION = "runtime_version"

    /**
     * 容器体积（字节）的缓存值。
     *
     * 必须缓存：算它要递归遍历整个 Ubuntu rootfs（十万级文件），在组合期同步调用
     * 会让每次导航回首页都卡 2 秒以上（真机日志实测 duration=2505ms + Skipped 232 frames）。
     * 只在安装完成、以及首次缺值时于 IO 线程后台重算。
     */
    const val KEY_ROOTFS_SIZE = "rootfs_size_bytes"
    const val KEY_PROROOT_FAIL = "proroot_fail_streak"
    const val KEY_AUTOSTART = "dsh_autostart"     // 开机自启（BootCompletedReceiver 读取）

    /** 权限通道首选：auto | root | shizuku | adb。 */
    const val KEY_PERM_CHANNEL = "perm_channel_pref"

    /** WebUI 打开方式：in | browser | ask。 */
    const val KEY_WEBUI_MODE = "webui_open_mode"

    /** 应用内 WebUI 悬浮球吸附的一侧：left | right。 */
    const val KEY_WEBUI_BALL_SIDE = "webui_ball_side"

    /** 应用内 WebUI 悬浮球的纵向位置，0..1 的屏高比例。 */
    const val KEY_WEBUI_BALL_Y = "webui_ball_y"
}
