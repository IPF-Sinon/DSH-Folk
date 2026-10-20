package me.bmax.apatch.dsh

import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * [DshRuntime] 对外移交的那一层「宿主口子」。
 *
 * 为什么需要它：安装/校验/解压这一簇函数天然要动单例的状态（`_state`、prefs、日志、服务器
 * 生命周期），而 Kotlin 里同包**看不见** private 成员。交接规范提议的「同包扩展函数法」要把这些
 * private 全部放宽成 `internal` —— 那等于把单例内部面摊平给整个包，只是把 3000 行的文件拆成
 * 几个文件，封装反而更差。这里改成显式接口：需要什么就声明什么（当前 20 个成员），
 * `DshRuntime` 实现一次，搬家后的文件只认接口。
 *
 * 与 [DshRuntimeLog] 的关系：那个只管日志（Env/Scripts 用），这个覆盖安装簇要用的全部口子。
 * 两者都保留 —— 只打日志的辅助文件不该拿到状态机的写入口。
 */
internal interface DshRuntimeHost {
    /** 运行态（只读订阅）。写入口是 [setState]。 */
    val stateFlow: StateFlow<DshState>

    /** 用一次函数式更新改状态（与 `_state.update {}` 同义）。 */
    fun setState(update: (DshState) -> DshState)

    /** 共享偏好（端口、已装版本、自动检查开关等都在这）。 */
    fun prefs(): android.content.SharedPreferences

    fun logInfo(resId: Int, vararg args: Any)
    fun logWarn(resId: Int, vararg args: Any)

    /** 取一条本地化文案（沿用 DshRuntime 里的同一份实现）。 */
    fun str(resId: Int, vararg args: Any): String

    /** 把当前阶段标为失败并记日志。 */
    fun fail(message: String)

    /** 触发 web 服务启动（安装完成后要把它拉起来）。 */
    fun startServer()

    /** 走一遍完整的启动流程（bootstrap 之后的自愈也走它）。 */
    fun bootstrap()

    /** 安装完成后刷新 rootfs 体积（界面上的占用数字）。 */
    fun refreshRootfsSize()

    fun availableSpace(dir: File): Long
    fun verifySha256(file: File, expected: String): Boolean
    fun downloadWithFallback(meta: DshMeta, target: File): Boolean
    fun fetchMeta(): DshMeta?
    fun fetchNewestMeta(): DshMeta?

    /** 速度竞速开关（按通道，见 DshRuntime.RACE_RUNTIME）。 */
    fun raceEnabled(channel: String): Boolean

    /** 清空运行日志（安装前会清一次）。 */
    fun clearLog()

    /** 数据源名（界面文案用）。 */
    fun sourceName(source: String): String

    /** 当前 App 是否满足运行时要求的最低版本。 */
    fun appSatisfies(minAppVersion: String): Boolean

    /** 多镜像竞速下载单个文件（安装路径与种子路径共用）。 */
    fun downloadFile(url: String, target: File, sizeBytes: Long): Boolean

    /** 读一个 ELF 的机器码（校验 rootfs 里的 node 是不是对的架构）。 */
    fun elfMachine(f: File): Int?

    /** 当前容器运行时实现。 */
    fun runtime(): me.bmax.apatch.dsh.ContainerRuntime

    /** 运行时数据源（安装前要按它选镜像）。 */
    fun runtimeSource(ctx: android.content.Context): String

    /** 架构闸门用的两个机器码常量。 */
    val elfMachineAarch64: Int
    val elfMachineX86_64: Int
}

/**
 * [DshRuntimeHost] 的适配器：转发到 [DshRuntime] 已存在的公开成员。
 *
 * 为什么不把接口实现在 DshRuntime 上：object 里已有同签名的 private 成员（prefs / logInfo / str…），
 * 再写一遍 override 会构成 **Conflicting overloads**（Kotlin 不把 private 成员当作接口实现）；
 * 而把这些成员逐一改名又会动到公开 API（startServer / raceEnabled / clearLog / runtime 等有 9 处
 * 外部调用点）。放进独立对象后两边各自解析、互不冲突，DshRuntime 一个字符都不用改。
 */
internal object DshRuntimeHostAdapter : DshRuntimeHost {
    override val stateFlow: StateFlow<DshState> get() = DshRuntime.stateFlow
    override fun setState(update: (DshState) -> DshState) = DshRuntime.setState(update)
    override fun prefs(): android.content.SharedPreferences = DshRuntime.prefs()
    override fun logInfo(resId: Int, vararg args: Any) = DshRuntime.logInfo(resId, *args)
    override fun logWarn(resId: Int, vararg args: Any) = DshRuntime.logWarn(resId, *args)
    override fun str(resId: Int, vararg args: Any): String = DshRuntime.str(resId, *args)
    override fun fail(message: String) = DshRuntime.fail(message)
    override fun startServer() = DshRuntime.startServer()
    override fun bootstrap() = DshRuntime.bootstrap()
    override fun refreshRootfsSize() = DshRuntime.refreshRootfsSize()
    override fun availableSpace(dir: File): Long = DshRuntime.availableSpace(dir)
    override fun verifySha256(file: File, expected: String): Boolean = DshRuntime.verifySha256(file, expected)
    override fun downloadWithFallback(meta: DshMeta, target: File): Boolean = DshRuntime.downloadWithFallback(meta, target)
    override fun fetchMeta(): DshMeta? = DshRuntime.fetchMeta()
    override fun fetchNewestMeta(): DshMeta? = DshRuntime.fetchNewestMeta()
    override fun raceEnabled(channel: String): Boolean = DshRuntime.raceEnabled(channel)
    override fun clearLog() = DshRuntime.clearLog()
    override fun sourceName(source: String): String = DshRuntime.sourceName(source)
    override fun appSatisfies(minAppVersion: String): Boolean = DshRuntime.appSatisfies(minAppVersion)
    override fun downloadFile(url: String, target: File, sizeBytes: Long): Boolean =
        DshRuntime.downloadFile(url, target, sizeBytes)
    override fun elfMachine(f: File): Int? = DshRuntime.elfMachine(f)
    override fun runtime(): ContainerRuntime = DshRuntime.runtime()
    override fun runtimeSource(ctx: Context): String = DshRuntime.runtimeSource(ctx)
    override val elfMachineAarch64: Int get() = ELF_MACHINE_AARCH64
    override val elfMachineX86_64: Int get() = ELF_MACHINE_X86_64
}
