package me.bmax.apatch.dsh.ext

import android.content.Context
import me.bmax.apatch.dsh.DshRuntime
import java.io.File

/**
 * 宿主级扩展的入口契约。
 *
 * 与另外两种「扩展」的区别（这也是三页并列的原因）：
 *  - **DSH 插件**：跑在 Linux 容器的 Node.js 里，受容器约束；
 *  - **用户脚本**：注入内置 WebView，受同源策略与浏览器沙箱约束；
 *  - **扩展（本文件）**：跑在 App 进程内，与宿主**同权**——能碰 root 通道、无障碍、全部文件。
 *    所以它必须有过得去的安全边界（见 [ExtensionSafetyGuard]）与明确的用户授权（见安装确认框）。
 */
interface DshExtension {
    /** 宿主加载完这个扩展后回调。抛异常会被捕获并跳过该扩展，不影响其它扩展与宿主启动。 */
    fun onLoad(host: DshExtensionHost)

    /** 停用/移除/安全模式熔断时回调（可选）。同样不能抛出去影响调用方。 */
    fun onUnload() {}
}

/**
 * 交给扩展的宿主句柄。
 *
 * 只暴露「已经过安全检查」的入口，不把 [Context] 直接塞给扩展：扩展确实能通过
 * [dataDir] 等访问自己的目录，但**不能**直接拿 Activity/Service 去绕开宿主的状态机。
 */
class DshExtensionHost internal constructor(
    /** 扩展 id（目录名，来自 manifest）。 */
    val id: String,
    /** 宿主 application context（不是 Activity）。 */
    val appContext: Context,
) {
    /** 全功能门面（见 [DshPlatform]）。 */
    val platform: DshPlatform by lazy { DshPlatform(appContext, id) }

    /** 目录读写（见 [ExtensionFs]）。 */
    val fs: ExtensionFs by lazy { ExtensionFs(appContext, id) }

    /** 界面接管（槽位 / 整屏 / 主题）。 */
    val ui: ExtensionUiRegistry get() = ExtensionUiRegistry

    /** 方法级拦截。 */
    val hooks: ExtensionHookRegistry get() = ExtensionHookRegistry

    /** 整块替换宿主子系统。 */
    val services: ExtensionServiceRegistry get() = ExtensionServiceRegistry

    /** 写进宿主日志（前缀 `[ext:<id>]`），便于报障时定位是哪个扩展干的。 */
    fun log(message: String) {
        DshRuntime.appendLog("[ext:$id] " + message)
    }
}

/**
 * 扩展在磁盘上的形态：`<dataDir>/files/extensions/<id>/`。
 *
 * ```
 * <id>/
 *   manifest.json   必填：id / name / version / entryClass
 *   classes.dex     可选：原生扩展的字节码（加载前会被设为只读，见 ExtensionLoader）
 *   assets/         可选：扩展自己的静态资源
 * ```
 *
 * 不是 zip：安装时解压到这个目录即可（也方便用户在文件管理器里核对）。
 */
object ExtensionLayout {
    /** 扩展根目录（宿主私有）。 */
    fun root(ctx: Context): File = File(ctx.filesDir, "extensions")

    /** 某个扩展的目录。 */
    fun dirOf(ctx: Context, id: String): File = File(root(ctx), id)

    /** 扩展的 dex（可能不存在 —— 纯声明式扩展不需要代码）。 */
    fun dexOf(ctx: Context, id: String): File = File(dirOf(ctx, id), "classes.dex")

    /** 扩展的 manifest。 */
    fun manifestOf(ctx: Context, id: String): File = File(dirOf(ctx, id), "manifest.json")

    /** 扩展自己的资源目录。 */
    fun assetsOf(ctx: Context, id: String): File = File(dirOf(ctx, id), "assets")

    /** 校验 id 是否可安全用作目录名（防 `..` / 分隔符穿越）。 */
    fun isValidId(id: String): Boolean =
        id.isNotEmpty() && id.length <= 64 &&
            id.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' } &&
            id != "." && id != ".."
}
