package me.bmax.apatch.dsh.ext

import android.content.Context
import java.io.File

/**
 * 宿主级扩展的入口契约。
 *
 * 与另外两种「扩展」的区别：
 *  - DSH 插件：跑在容器里的 Node.js，受容器约束；
 *  - 用户脚本：注入内置 WebView，受浏览器沙箱约束；
 *  - **扩展（本文件）**：加载进 App 进程，与宿主同权。
 *
 * 这个接口放在 :ext-api 模块里，所以第三方仓库可以编译出自己的实现；
 * 宿主用 DexClassLoader 加载它（父加载器是宿主 classLoader，因此运行期与宿主同权）。
 */
interface DshExtension {
    /** 宿主加载完这个扩展后回调。抛异常会被捕获并跳过该扩展，不影响其它扩展与宿主启动。 */
    fun onLoad(host: DshExtensionHost)

    /** 停用 / 移除 / 安全模式熔断时回调（可选）。同样不能抛出去影响调用方。 */
    fun onUnload() {}
}

/**
 * 交给扩展的宿主句柄。
 *
 * 做成**接口**而不是数据类：宿主侧要往里加东西（比如只有 App 内扩展才拿得到的强类型门面），
 * 第三方只需要按接口编程；接口里只放「任何扩展都用得上、且不牵出宿主内部类型」的能力。
 */
interface DshExtensionHost {
    /** 扩展 id（目录名，来自 manifest）。 */
    val id: String

    /** 宿主 application context（不是 Activity）。 */
    val appContext: Context

    /** 目录读写（见 [ExtensionFs]）。 */
    val fs: ExtensionFs

    /** 写进宿主日志（前缀 [ext:扩展id]），便于报障时定位是哪个扩展干的。 */
    fun log(message: String)
}

/**
 * 扩展在磁盘上的形态：filesDir/extensions/扩展id/。
 *
 * 目录里：manifest.json（必填）、classes.dex（可选）、assets/（可选）。
 * 不做 zip：安装时解压到这个目录即可，也方便用户在文件管理器里核对。
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

    /** 校验 id 是否可安全用作目录名（防 .. / 分隔符穿越）。 */
    fun isValidId(id: String): Boolean =
        id.isNotEmpty() && id.length <= 64 &&
            id.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' } &&
            id != "." && id != ".."
}

/**
 * 扩展侧的日志出口。
 *
 * 为什么是一个可注入的 sink 而不是直接写宿主日志：:ext-api 不依赖宿主，而日志最终要落到
 * 宿主的 dsh.log 里（报障时最有用）。宿主在启动时把 sink 指向自己的 LogStore。
 */
object ExtLog {
    /** 宿主导入的实现：写进 dsh.log（**只给真正的故障用**）。 */
    @Volatile
    var sink: (String) -> Unit = {}

    /**
     * 宿主导入的第二条出口：只进 logcat，不写 dsh.log。
     *
     * 为什么不全都写 dsh.log：dsh.log 是用户在「设置 → 日志」里看的启动日志，
     * 塞满「准备加载 xx」「外壳决策」这种例行诊断会把真故障淹没掉。而这些信息在
     * 排查问题时又必须有 —— 所以走 logcat，bugreport 里照样拿得到。
     */
    @Volatile
    var infoSink: (String) -> Unit = {}

    /** 例行诊断：只进 logcat。 */
    fun info(message: String) {
        runCatching { infoSink(message) }
    }

    /** 故障：进 dsh.log（也进 logcat）。 */
    fun log(message: String) {
        runCatching { sink(message) }
    }
}

/**
 * 扩展系统的运行门（由宿主设置，扩展 API 只读）。
 *
 * 存在的理由：注册中心与启动管线都要在**安全模式熔断之后立刻短路**，但「是否熔断」是宿主的
 * 概念（要读文件系统、要读 prefs），:ext-api 里不能实现。所以宿主把结论塞进来，这里只读。
 */
object ExtensionGate {
    /** 熔断位：为真时注册中心一律不生效、既有注册一律按「不存在」处理。 */
    @Volatile
    var latched: Boolean = false

    fun isLatched(): Boolean = latched
}
