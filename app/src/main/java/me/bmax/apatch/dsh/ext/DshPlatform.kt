// 下面这些属性直接把宿主的 internal 单例（PrivilegedShell / DshA11y / WebScripts / WorkflowEngine …）
// 挂出来给扩展用。改的只是「谁看得见」这件事的外包装，没有放宽单例本身的可见性 —— 扩展与宿主同在
// app 模块，internal 本来就可访问。
// Kotlin 会因此报 EXPOSED_PROPERTY_TYPE（公开 API 暴露了 internal 类型）。这条诊断在这里是**误伤**：
// 暴露是刻意的，正是这个门面的全部意义；而逐个手写转发方法会把「零签名抄写风险」这个前提推翻
// （本地没有编译器，抄错的签名只有 CI 才会发现）。所以按文件抑制，而不是把宿主的单例改成 public
// —— 那会把 internal 当 API 承诺出去，以后谁都改不动。
@file:Suppress("EXPOSED_PROPERTY_TYPE")

package me.bmax.apatch.dsh.ext

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import me.bmax.apatch.dsh.AdbBridge
import me.bmax.apatch.dsh.DisplayServer
import me.bmax.apatch.dsh.DshA11y
import me.bmax.apatch.dsh.DshAutostart
import me.bmax.apatch.dsh.DshBridgeGuard
import me.bmax.apatch.dsh.DshCamera
import me.bmax.apatch.dsh.DshCloudBackup
import me.bmax.apatch.dsh.DshConfigBackup
import me.bmax.apatch.dsh.DshDeepClean
import me.bmax.apatch.dsh.DshDeviceSense
import me.bmax.apatch.dsh.DshEnv
import me.bmax.apatch.dsh.DshFileAccess
import me.bmax.apatch.dsh.DshFsBridge
import me.bmax.apatch.dsh.DshHostPrompt
import me.bmax.apatch.dsh.DshNativeBridge
import me.bmax.apatch.dsh.DshPersonalData
import me.bmax.apatch.dsh.DshPluginRepo
import me.bmax.apatch.dsh.DshRuntime
import me.bmax.apatch.dsh.DshState
import me.bmax.apatch.dsh.DshTts
import me.bmax.apatch.dsh.LogStore
import me.bmax.apatch.dsh.PrivilegedShell
import me.bmax.apatch.dsh.ScriptMarket
import me.bmax.apatch.dsh.Userscripts
import me.bmax.apatch.dsh.WebScripts
import me.bmax.apatch.dsh.WorkflowApprovals
import me.bmax.apatch.dsh.WorkflowEngine
import me.bmax.apatch.ui.theme.ThemeManager
import me.bmax.apatch.util.DshWebCompat
import me.bmax.apatch.util.HardwareMonitor
import me.bmax.apatch.util.MusicManager
import me.bmax.apatch.util.SoundEffectManager
import me.bmax.apatch.util.UpdateChecker
import me.bmax.apatch.util.VibrationManager
import java.io.File

/**
 * 给扩展用的「全功能门面」：把宿主已有的各个 object 单例原样摆出来。
 *
 * ## 三者的分工（本文件只负责第一件事）
 *  - **调用**（本文件，[DshPlatform]）：扩展**用**宿主的能力 —— 跑命令、读状态、放音乐、改主题。
 *  - **替换**（[ExtensionServiceRegistry]）：扩展**顶掉**宿主某个子系统，宿主改用扩展的实现。
 *  - **拦截**（[ExtensionHookRegistry]）：扩展在宿主方法前后插一脚，通常还继续调用宿主原实现。
 *
 * ## 为什么是「直接暴露宿主 object」而不是一层层转发方法
 * 一是覆盖度：转发写法每加一个宿主能力就要回来补一行，扩展永远落后于宿主；
 * 直接给出 object，宿主今天新增的方法扩展明天就能调。
 * 二是正确性：手抄签名（参数顺序、默认值、可空性）是纯人工活，抄错只会在 CI 编译时才炸，
 * 而这个项目本地没有编译器。能拿到对象就不转发 —— 这条比「门面更好看」重要得多。
 *
 * ## 只在三种情况下才写显式方法
 * 宿主入口**需要 Context**（扩展不该自己持有 Context）、**需要拼参数**（日志前缀）、
 * 或者返回的东西必须加工过（流式输出的回调）。除此之外一律不转发。
 *
 * ## 边界
 * 扩展与宿主同权（见 [DshExtension] 的说明），这里的门面不打算伪造沙箱。唯一的硬约束是
 * 不能碰熔断标志文件，那由 [ExtensionFs] / [ExtensionSafetyGuard] 守住，与本文件无关。
 */
class DshPlatform internal constructor(
    private val ctx: Context,
    private val extensionId: String,
) {
    // ────────── 运行时与通道 ──────────

    /** Linux 容器的安装、启动、停止与状态机（首页那张卡片背后的东西）。 */
    val runtime get() = DshRuntime

    /** 文件系统桥：宿主的 SAF / 共享目录与容器内路径之间的转换。 */
    val bridge get() = DshFsBridge

    /** 原生桥：JNI 那层能力（chroot 辅助、底层工具）的 Kotlin 入口。 */
    val native get() = DshNativeBridge

    /** 特权 shell：以 root 身份执行一条命令的底层通道。 */
    val shell get() = PrivilegedShell

    /** ADB 通道：通过 adb 而非 root 跑命令（Shizuku 场景）。 */
    val adb get() = AdbBridge

    /** 网络兼容层：给内嵌 WebView / 用户脚本用的请求改写与 UA 处理。 */
    val webCompat get() = DshWebCompat

    // ────────── 无障碍与显示 ──────────

    /** 无障碍：读屏节点树、点击、滑动、监听窗口变化。 */
    val a11y get() = DshA11y

    /** 显示服务：独立的虚拟显示，可在上面跑界面并串流出来。 */
    val display get() = DisplayServer

    // ────────── 脚本 ──────────

    /** 站点脚本：注入内嵌 WebView 的 JS，按域名匹配。 */
    val webScripts get() = WebScripts

    /** 用户脚本：管理用户安装的 *.user.js（启停、列表、匹配）。 */
    val userscripts get() = Userscripts

    /** 脚本市场：远程脚本源的索引与安装。 */
    val scriptMarket get() = ScriptMarket

    // ────────── 插件与工作流 ──────────

    /** DSH 插件仓库：容器内 Node 插件的安装、启停与元数据。 */
    val plugins get() = DshPluginRepo

    /** 工作流引擎：跑一条编排好的步骤链（每步可以是 shell / 脚本 / 审批）。 */
    val workflow get() = WorkflowEngine

    /** 工作流审批：需要用户点头才能继续的关卡排队处。 */
    val workflowApprovals get() = WorkflowApprovals

    // ────────── 数据与备份 ──────────

    /** 配置备份：把宿主设置导出/导入为归档（含加解密）。 */
    val backup get() = DshConfigBackup

    /** 云备份：把备份推到远端并拉回。 */
    val cloud get() = DshCloudBackup

    /** 日志仓库：宿主日志的持久化与查询（比 [log] 多了检索能力）。 */
    val logs get() = LogStore

    /** 文件访问层：跨应用的 SAF 授权与文件交接。 */
    val fileAccess get() = DshFileAccess

    /** 个人数据：联系人、短信等需要权限的数据的受限入口。 */
    val personalData get() = DshPersonalData

    // ────────── 设备能力 ──────────

    /** 相机：拍照 / 录制的宿主封装（不必自己拉起 CameraX）。 */
    val camera get() = DshCamera

    /** 语音合成。 */
    val tts get() = DshTts

    /** 设备感知：电池、温度、网络、屏幕等状态的一次性读取。 */
    val deviceSense get() = DshDeviceSense

    /** 硬件监控：CPU / 内存 / 温度的持续采样。 */
    val hardware get() = HardwareMonitor

    /** 清理：深清缓存与残留目录。 */
    val deepClean get() = DshDeepClean

    /** 自启动：开机/重启后拉起宿主运行时。 */
    val autostart get() = DshAutostart

    /** 环境常量与路径（端口、rootfs 位置、版本号等）。 */
    val env get() = DshEnv

    // ────────── 界面与提醒 ──────────

    /** 主题：全局配色与明暗模式。改它会立刻影响宿主界面。 */
    val theme get() = ThemeManager

    /** 音乐播放：扩展可以放一段背景音，而不必自己管理 MediaPlayer 生命周期。 */
    val music get() = MusicManager

    /** 音效：点击 / 提示音这类短音效。 */
    val sound get() = SoundEffectManager

    /** 振动：按预设模式震动，自动遵守系统「勿扰 / 静音」设置。 */
    val vibration get() = VibrationManager

    /** 宿主弹窗：在宿主界面之上提问 / 确认，扩展不用自己拼 AlertDialog。 */
    val hostPrompt get() = DshHostPrompt

    /** 桥接守卫：统一校验跨进程 / 跨应用调过来的是谁，扩展要走桥就先过它。 */
    val bridgeGuard get() = DshBridgeGuard

    /** 更新检查：宿主 App 自身有没有新版本。 */
    val updater get() = UpdateChecker

    // ────────── 需要 Context 或需要封装的入口 ──────────

    /** 数据目录（/data/user/0/包名）。扩展自己的东西写这里，别写外部存储。 */
    val dataDir: File get() = ctx.dataDir

    /** 容器 rootfs（files/rootfs），往里面写就是在改容器的根文件系统。 */
    val rootfsDir: File get() = DshEnv.rootfs(ctx)

    /**
     * 跑一条 shell 命令，拿到进程对象自己读输出。
     *
     * 需要这个重载的场景是长驻进程（交互式会话）；一次性命令用 [execCapture] 更省事，
     * 否则忘了收尾会留下僵尸进程。
     */
    fun exec(bashCommand: String): Process = DshRuntime.execRootfs(bashCommand)

    /**
     * 跑一条命令并把输出整段收回来（超时截断，返回已读到的部分）。
     *
     * 默认 30 秒而不是宿主内部的 60 秒：扩展做的是「顺手跑一下」的事，
     * 卡满一分钟会拖住调用它的界面；真需要更久就显式传更大的值。
     */
    fun execCapture(bashCommand: String, timeoutMs: Long = 30_000L): String =
        DshRuntime.execRootfsForOutput(bashCommand, timeoutMs)

    /**
     * 同 [execCapture]，但每读到一整行就回调一次。
     *
     * 回调在**读线程**上执行，不是主线程 —— 里面不要直接改 Compose 状态，
     * 需要更新界面请自己切回主线程。
     */
    fun execStreaming(
        bashCommand: String,
        timeoutMs: Long = 30_000L,
        onLine: (String) -> Unit,
    ): String = DshRuntime.execRootfsStreaming(bashCommand, timeoutMs, onLine)

    /** 写进宿主日志，前缀带扩展 id，报障时一眼看出是谁干的。 */
    fun log(message: String) {
        DshRuntime.appendLog("[ext:" + extensionId + "] " + message)
    }

    /**
     * 运行时的可观察状态（安装进度、端口、PID、错误原因）。
     *
     * 返回的是流的实时引用而不是快照：扩展要跟随时收集它，只看一眼就取 value 字段。
     */
    fun state(): StateFlow<DshState> = DshRuntime.state
}
