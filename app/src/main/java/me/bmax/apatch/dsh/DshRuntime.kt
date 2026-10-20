package me.bmax.apatch.dsh

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.bmax.apatch.R
import me.bmax.apatch.util.LocaleCtx
import me.bmax.apatch.util.UpdateChecker
import me.bmax.apatch.util.appString
import org.json.JSONArray
import org.json.JSONObject

/** 运行时阶段。 */
object DshRuntime : DshRuntimeHost {
    private const val TAG = "DSH-Folk"
    /**
     * 等服务就绪的上限。
     *
     * 从 180s 压到 90s：proroot 现在失败一次就回退（见 [PROROOT_FAIL_LIMIT]），
     * 而「卡住不退」的 proroot 唯一表现就是耗尽这个超时 —— 等待越久，用户从
     * 坏运行时走到可用运行时的时间就越长。node 起 dsh web 正常在 10s 内。
     */
    private const val READY_TIMEOUT_MS = 90_000L

    /**
     * 端口已响应后，再等认证 token 多久才按「没有 token」继续。
     *
     * web 服务器**激活即监听**（dsh-host-webserver：「Activation listens immediately」），
     * 而带 token 的那行 URL 是**插件树加载完**才打印的（dsh-web-app 的 announceReady 等
     * `loader.await()`），两者之间有一段时间差；这段时间里发出的地址没有 token，打开就是
     * 认证墙。等这个差值是修掉「外部浏览器打开时链接没有 token」的关键。
     *
     * 取 30s：手机上插件树加载通常在这个量级以内。等不到也不卡死 —— 超时就按无 token
     * 继续（那说明这份运行时的输出格式变了，日志里会写明）。
     */
    private const val TOKEN_WAIT_MS = 30_000L

    /** 等 token 时的轮询间隔：这行通常在 1s 内到，密一点能让「已就绪」尽快翻过来。 */
    private const val TOKEN_POLL_MS = 200L
    /**
     * proroot 连续失败到此次数即强制回退 proot 并清掉用户选择。
     *
     * 取 1（失败即回退）：proroot 的失败模式是内核相关的确定性失败，重试同一台
     * 设备不会有不同结果，让用户白等第二、三次没有意义。
     */
    private const val PROROOT_FAIL_LIMIT = 1

    /**
     * 从 dsh 打印的 `dsh web: http://…?token=…` 里提取认证 token。
     *
     * 字符集必须是 **base64url**：上游 dsh-client-connection 的 `processLaunchToken` 用的是
     * `encodeBase64Url(randomBytes(32))`，43 个字符里 `-` 与 `_` 都可能出现。这条原来写的是
     * `\?token=([A-Za-z0-9+/=-]+)`（少了 `_`），用 20000 个真 token 量过：**46.8% 会被从
     * `_` 处截断**（URL 带着一个错的 token 去撞认证墙），另有 **1.6% 因首字符就是 `_` 而整条
     * 匹配不上**，URL 里连 token 参数都没有 —— 正是「外部浏览器打开时链接没有 token」。
     *
     * 收尾不靠字符类：局域网开着时 dsh 会在同一行再打一个 ` (LAN: …)`，所以按分隔符截断。
     */
    private val DSH_WEB_TOKEN_RE = Regex("[?&]token=([A-Za-z0-9_%+/.=~-]+)")

    /** ELF `e_machine`：183 = AArch64，62 = x86-64（见 [rootfsArchMismatch]）。 */

    /** profile pnpm 设置里固定导入方式（见 [ensureProfilePnpmSettings]）。 */
    private const val PNPM_IMPORT_KEY = "packageImportMethod"
    private const val PNPM_IMPORT_LINE = "packageImportMethod: copy"

    /** v1.3 写进 /root/.npmrc 的无效行，只为清理它而保留。 */
    private const val NPMRC_LEGACY_LINE = "package-import-method=copy"

    /** web profile 在 rootfs 内的相对路径（guest 侧是 /root/.dsh/profiles/web）。 */
    private const val PROFILE_GUEST_REL = DshRuntimeEnv.PROFILE_GUEST_REL

    /** 权限桥疑难解答在容器里的文件名（与 CLI 里那个路径必须一致）。 */
    // ── 容器侧脚本与文档 ──────────────────────────────────────────────────────
    // 内容在同包 [DshRuntimeScripts]（约一千二百行 raw string）。这里只留引用：调用点零改动，
    // 要改脚本、DSL 说明或样例，去那个文件。
    private val FS_BRIDGE_CLI_SCRIPT: String get() = DshRuntimeScripts.FS_BRIDGE_CLI_SCRIPT
    private val SAF_BRIDGE_CLI_SCRIPT: String get() = DshRuntimeScripts.SAF_BRIDGE_CLI_SCRIPT
    private val NATIVE_CLI_SCRIPT: String get() = DshRuntimeScripts.NATIVE_CLI_SCRIPT
    private val WORKFLOW_CLI_SCRIPT: String get() = DshRuntimeScripts.WORKFLOW_CLI_SCRIPT
    private val WORKFLOW_DSL_DOC: String get() = DshRuntimeScripts.WORKFLOW_DSL_DOC
    private val WORKFLOW_SAMPLES: Map<String, String> get() = DshRuntimeScripts.WORKFLOW_SAMPLES
    private val WORKFLOW_SAMPLES_DIR: String get() = DshRuntimeScripts.WORKFLOW_SAMPLES_DIR
    private val WORKFLOW_DSL_DOC_NAME: String get() = DshRuntimeScripts.WORKFLOW_DSL_DOC_NAME
    private val TROUBLESHOOT_DOC_NAME: String get() = DshRuntimeScripts.TROUBLESHOOT_DOC_NAME
    private fun nativeTroubleshootDoc(): String = DshRuntimeScripts.nativeTroubleshootDoc(appContext)

    /**
     * 首启预装的插件（npm 包名，已人工验证可装）。
     *
     * 手机上没有这几个体验差很多：dsh-web-mobile 做移动端适配，dshmarket 提供
     * WebUI 内的插件市场，dsh-config-manager 则是**本应用配置备份的依赖** ——
     * 设置里的导出/导入走的正是它的回环 HTTP API（见 [DshConfigBackup]），
     * 没装的话那一页直接不可用。dsh-file-upload 补上手机端最缺的一环：
     * 拖拽/回形针上传、文档转 Markdown、图片 OCR、语音输入 —— 手机上没有
     * 命令行贴文件这条路，全靠它把本地文件送进会话。
     *
     * 往这个清单里加包是安全的：[seedPlugins] 按包名逐个记账，老用户下次冷启动
     * 会补装增量（不会因为「已完成」标记而永远跳过）。
     */
    val SEED_PLUGINS =
        listOf("dsh-web-mobile", "dshmarket", "dsh-config-manager", "dsh-folk-cloud")

    /**
     * 预装包名 → 安装 spec 的覆盖表（缺省 spec 就是包名本身）。
     *
     * `dsh-folk-cloud` **不发布到 npm**（免得和别的包撞名，也免去维护发布口令），
     * 靠 GitHub 仓库直接装：`github:owner/name` 规格由 [DshPluginRepo.install] 里的
     * `resolveSpec` 解析成 pnpm 能吃的形态。但账本（[SEED_PLUGINS]、`installed`、
     * `attempted`、补修）全部按**包名**记 —— spec 只在真正调 install 的那一刻替换，
     * 这样「装了没有」的判断不会因为 spec 形态而错位。
     *
     * 它依赖 dsh-config-manager，但只经回环 HTTP 调用（从不 import），因此其 package.json
     * 把 dsh-config-manager 记为**可选 peer**：pnpm 不会在它底下再嵌一份，避免
     * `config-manager` 这个 loader id 被声明两次而整棵插件树起不来。两者都在本清单里、
     * 各自独立安装到 profile 顶层，互为并列。
     */
    private val SEED_SPECS = mapOf("dsh-folk-cloud" to "github:IPF-Sinon/dsh-folk-cloud")

    /**
     * 预装插件的「最低要求版本」名单（见 [applySeedVersionUpgrade]）。
     *
     * DSH 启动时，凡当前已装版本**低于**这里要求的预装包，就用 `github:` 规格重装一次以拉到
     * 满足要求的新版本；已达标的不动。要求版本必须与插件仓 `package.json` 的版本对齐：插件发了
     * 需要 App 一并到位的改动，就抬插件版本、再把这里的要求版本同步上来。
     */
    // 0.5.0：云备份面板加了「包含应用主题」开关（按主题包大小自动默认），需要 App 侧的
    // /cloud/appdata/theme 端点与导出的 includeTheme 参数配合；老插件不会传该参数，
    // App 侧按 true 兜底，所以只是「拿不到新功能」，不会出错。
    //
    // dsh-config-manager 0.1.64（用户 2026-09-25 指定）：会话跨机恢复与凭据回填那一批修复
    // （上游 issue #45）。对我们尤其关键的三条：
    //   ① sessions 进了分区注册表（applyOrder 14），导入执行阶段会**真的写会话文件**并做归位/
    //      登记 —— 所以 App 侧必须把会话计划项从交给它的计划里剔除，由 App 独占会话
    //      （见 DshConfigBackup：以前插件不执行 sessions，我们独占是为了绕开静默丢弃；现在
    //      是**有意独占**，不剔除就会两边都写）；
    //   ② /plan 与 /analyze 认 decryptPassword，不传就会把「只存在于 secrets.enc、未被
    //      credentialsStatus 声明」的凭据从计划里漏掉（真机反馈「导入密钥没生效」）；
    //   ③ 会用 manifest 的 sourceHome 自动生成跨机基础路径重定基规则，App 侧据此同时给
    //      /plan 的 pathMappings 传映射（见 DshBackupArchive）。
    //
    // 种子最低版本 = 预装升级流程的「门槛」，低于它就在启动时被拉到这个版本。
    //
    // 纪律：**只能抬到同时兼容 0.1.x 与 0.2.x 的版本**。因为 App 同时挂着 stable(0.1.7)
    // 与 beta(0.2.0) 两个运行时，抬到一个只认 0.2.0 的版本，会把 stable 用户升级到
    // 反被 dsh 兼容闸门整包跳过的包 —— 那就从「云备份不可用」变成「插件市场不可用」。
    //
    // dshmarket 1.66.8：peerDeps 是 `^0.1.0-rc.7 || ^0.1.1-rc.2 || ^0.1.2-alpha.2 || ^0.2.0-rc.1`
    //   —— 0.1.x 与 0.2.x 都在范围内，是双兼容的，所以从 1.65.1 抬到它（0.2.0 上 1.65.1 会被
    //   跳过；真机已由用户手动更新验证过 1.66.8 在 0.2.0 正常）。
    // dsh-web-mobile 3.0.3：**上游最新就是它**，且范围明确 `<0.2.0`，没有双兼容版本可抬，
    //   故保持不动。实测它在 0.2.0 上并未被跳过（它不是 profile bundle，不吃那道闸门）且 UI 正常。
    // dsh-config-manager 0.1.64：范围本来就是 `>=0.1.0-rc.6 <0.3.0-0`，天然双兼容。
    private val SEED_MIN_VERSIONS = mapOf(
        "dsh-folk-cloud" to "0.6.0",
        "dsh-config-manager" to "0.1.64",
        "dshmarket" to "1.66.8",
        "dsh-web-mobile" to "3.0.3",
    )

    /**
     * 预装包 → 正式 release tgz 直链的兜底表（钉死版本）。
     *
     * 正常路径是 [SEED_SPECS] 的 `github:` 规格（跟最新代码，经 gh-proxy 镜像装）；只有
     * git 全线路都失败时，才回落到这里的 tgz 直链——纯 HTTP 下载、完全绕开 git，由
     * [DshPluginRepo.install] 在镜像线路里当作最后一条候选。tgz 装的是这个固定版本，
     * 装上后用户可在插件商店自行更新到更新的版本。
     */
    private val SEED_FALLBACK_TGZ = mapOf(
        "dsh-folk-cloud" to
            "https://github.com/IPF-Sinon/dsh-folk-cloud/releases/download/v0.6.0/dsh-folk-cloud-0.6.0.tgz",
    )

    /** 取某个预装包的安装 spec（默认即包名）。 */
    fun seedSpec(pkg: String): String = SEED_SPECS[pkg] ?: pkg

    /** 取某个预装包的 tgz 兜底直链（没有则 null）。 */
    fun seedFallbackTgz(pkg: String): String? = SEED_FALLBACK_TGZ[pkg]

    /**
     * **退役**的预装包 → 它当年会插入的 entry id。
     *
     * 退役 = 上游已经自带同名能力，预装它不但多余，还会让整棵插件树报
     * `duplicate loader entry id` 而根本起不来。dsh 0.1.5 起 `dsh-web-app` 自带
     * `file-upload`（`@deepseek-ai/dsh-client-file-upload`，见其 cordis.patch.yml 的
     * `- id: file-upload`），所以三方 `dsh-file-upload` 退役。
     *
     * 用它做三件事，**全部不依赖运行时探测**：
     * 1. [seedPlugins] 在启动前把已装的退役包卸掉；
     * 2. 账本 / 补修 / 换运行时重试一律忽略退役包（它们已不在 [SEED_PLUGINS] 里）；
     * 3. [repairDuplicateLoaderEntry] 的候选集合 —— 早先这里靠 `pluginEntries()` 读
     *    实际 entry id 再反查包名，可那条路在 yaml 解析失败时会**静默返回空表**，
     *    于是冲突检测整个失效、退役包被反复预装（真机复现）。
     *
     * 判定阈值：只有运行时的 dsh ≥ [RETIRE_MIN_DSH_VERSION] 才自动卸载 —— 老运行时
     * 没有内置能力，卸掉会让用户失去文件上传；版本读不出来时也不动。
     *
     * 离线核对过：`@deepseek-ai/dsh-web-app@0.1.5-rc.1` 的 cordis.patch.yml 声明了 94 条
     * entry id，与预装包声明的 `dsh-web-mobile` / `dsh-market` / `config-manager` /
     * `file-upload` 相比**只撞这一条**。核对方式（不需要设备）：
     * `npm view @deepseek-ai/dsh-web-app@<版本> dist.tarball` 取包，读它 package.json 里
     * `dsh.bundle.patch` 指向的文件的 `- id:` 行，再和预装包的同类声明取交集。
     */
    private val RETIRED_SEED_PLUGINS = mapOf("dsh-file-upload" to "file-upload")

    /** 退役判定用的 dsh 版本下界：从这个版本起上游自带 `file-upload`。 */
    private const val RETIRE_MIN_DSH_VERSION = "0.1.5"

    /** 启动日志里「profile 声明了 bundles 但包解析不到」的行（声明残留时出现）。 */
    private val PROFILE_BUNDLE_RE =
        Regex("cannot resolve profile bundle [\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

    /** 启动日志里「重复 loader entry id」的行（0.1.5 内置能力与三方插件重名时出现）。 */
    private val DUP_ENTRY_RE = Regex("duplicate loader entry id[: ]+([A-Za-z0-9_.@/-]+)", RegexOption.IGNORE_CASE)

    /**
     * 当前 App 的 semver 核心段（去掉 `-beta.N` 这类预发布后缀）。
     *
     * 最低版本比较只看核心段：`1.8.3-beta.20` 与 `1.8.3` 在功能上同代，按完整
     * semver 比会把所有测试版用户误拦在 `minAppVersion = 1.8.3` 之外。
     */
    private fun appCoreVersion(): String = me.bmax.apatch.BuildConfig.VERSION_NAME.substringBefore('-')

    /**
     * App 是否满足运行时的最低版本要求；空要求恒为满足（旧 metadata）。
     *
     * 两种门槛分开处理，因为它们的意图不同：
     *
     * - 要求**不带**预发布后缀（`1.8.4`）时只比核心段：`1.8.4-beta.32` 与 `1.8.4` 功能同代，
     *   门槛是按「这一代 App 有没有那些适配」写的，按完整 semver 比会把所有测试版用户挡在
     *   门外 —— 包括运行时测试通道自己（它的要求正是 `1.8.4`）。
     * - 要求**带**预发布后缀（`1.8.4-beta.32`）时按完整版本比。这种写法是在说「至少到这个
     *   测试版」，而只比核心段会让它永远成立：正式版 App 的预发布段被丢掉后比较恒为「满足」，
     *   于是这条要求等于没写。
     */
    override fun appSatisfies(minAppVersion: String): Boolean {
        if (minAppVersion.isBlank()) return true
        val exact = minAppVersion.substringBefore('+').contains('-')
        val installed = if (exact) me.bmax.apatch.BuildConfig.VERSION_NAME else appCoreVersion()
        return compareVersions(installed, minAppVersion) >= 0
    }

    /**
     * 「预装补修」轮次（见 [applySeedRepair]）。
     *
     * 修好一个会让预装失败的根因后 +1，让**记过账但实际没生效**的预装包再试一次。
     *   1 = 1.7.7：修 pnpm 拦构建脚本导致 dsh-file-upload 装了但没进 bundles
     *   2 = 1.9.2.7：github/git 插件安装改走 gh-proxy 镜像线路（1.9.2.6）后，重试一次
     *       之前因直连 github 失败而记账没生效的预装包（dsh-folk-cloud 首当其冲）
     */
    private const val SEED_REPAIR_REV = 2

    /**
     * 同一个运行时版本下，最多允许几轮「补装没生效的预装包」。
     *
     * 给的是**瞬时故障**的余地（网络抽风、pnpm 首次拉包超时）：第一次失败后下次开机
     * 还能再试一次；两次都不行就认定这个环境下装不上，不再每次开机重跑 pnpm。
     * 换运行时版本会重置这个计数（见 [applySeedEnvRetry]）。
     */
    private const val SEED_MAX_PASSES = 2

    /** 预装插件集合（供插件列表/商店渲染「预装」标签）。 */
    fun isSeedPlugin(pkg: String): Boolean = pkg in SEED_PLUGINS

    /** raw string 里没法直接写美元加大括号（Kotlin 会当模板求值），用这个常量拼。 */
    private const val D = "$"

    /** 样例目录名（容器内 `/root/.dsh/<这个名字>/`）。 */
    /** 写到容器里的 DSL 文档文件名（与 `dsh-workflow` / 工具插件约定）。 */
    /**
     * 插件树/客户端包加载失败的日志签名。
     *
     * 命中这些就**不能**判定为 proroot 故障：它与容器运行时无关，换 proot 重试
     * 照样失败（真机实测就是这样），而 [PROROOT_FAIL_LIMIT] = 1 会一次就把用户的
     * proroot 偏好静默清掉。
     */
    private val PLUGIN_FAILURE_MARKS = listOf(
        "plugin tree failed to load",
        "client bundles not found",
        "failed to apply loader entry modules",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(DshState())
    val state: StateFlow<DshState> = _state.asStateFlow()

    /** [DshRuntimeHost] 需要的只读入口（与 [state] 同一个流）。 */
    override val stateFlow: StateFlow<DshState> get() = _state

    /** [DshRuntimeHost] 需要的写入口（与内部 `_state.update` 同义）。 */
    override fun setState(update: (DshState) -> DshState) { _state.update(update) }

    override val elfMachineAarch64: Int get() = ELF_MACHINE_AARCH64
    override val elfMachineX86_64: Int get() = ELF_MACHINE_X86_64

    // ────────── DshRuntimeHost：安装簇等辅助文件只认这个接口 ──────────
    // 接口由本 object 直接实现：冲突的 10 个私有成员已放宽为 internal（外部调用为 0），
    // 6 个公开成员加 override（名字与可见性不变，7 处外部调用点照旧）。
    override val stateFlow: StateFlow<DshState> get() = _state
    override fun setState(update: (DshState) -> DshState) { _state.update(update) }

    /** 宿主 Context（安装簇的扩展函数也要用；原先 private，外部无调用）。 */
    internal lateinit var appContext: Context
    private var serverProcess: Process? = null
    private var startedAt: Long = 0L

    @Volatile private var hardlinkOk: Boolean? = null

    /**
     * 硬链接探测失败的原因（异常类名 + message），成功时为空。
     *
     * 必须留下来：原来只走 Log.i，而 logcat 在 bugreport 里只有最近几分钟，
     * App 早已启动完，那一行永远抓不到 —— 于是「为什么这台设备不支持硬链接」
     * 每次都只能靠猜。现在它会随 [startServer] 一起写进 dsh.log。
     */
    @Volatile private var hardlinkDetail: String = ""

    /**
     * 只绑定 Context，不做任何 IO。**必须在任何 Composable 读取本对象之前调用**
     * （APApplication.onCreate 里）。
     *
     * 原来只有 attach() 一个入口，而它是在首页的 LaunchedEffect 里调的 —— 那时
     * 首次 composition 已经跑完了，composition 期间读 runtimeId() / port() 会撞上
     * 未初始化的 lateinit 直接崩掉（真机实测：进首页几秒后
     * UninitializedPropertyAccessException）。
     */
    fun init(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
            // 上一次运行时替换如果被强杀打断，先恢复可启动的 rootfs，再认领暂存数据。
            recoverInterruptedRuntimeInstall()
            // 进程重启后旧日志不该残留：清一次，让启动日志按「本次运行」呈现。
            // startServer() 里还会再清一次，这里主要覆盖「只开 App 不启动服务」的情况。
            clearLog()
        }
    }

    /** Context 是否已绑定。未绑定时所有读接口给默认值而不是抛异常。 */
    private val ready: Boolean get() = ::appContext.isInitialized

    /**
     * 已绑定的 application context（给同在 dsh 包里的兄弟对象用，如 [DshPluginRepo] 要拿它读
     * 竞速通道的测速缓存）。未绑定返回 null —— 调用方自己决定怎么处理，不要在这里抛。
     */
    fun appContextOrNull(): Context? = if (ready) appContext else null

    fun attach(context: Context) {
        init(context)
        val installed = DshEnv.isRuntimeInstalled(appContext)
        val cachedSize = prefs().getLong(DshEnv.KEY_ROOTFS_SIZE, 0L)
        // 已装运行时的最低 App 版本要求是**持久化**的：App 升级/降级后（prefs 保留）
        // 或离线时都要能判断「这份运行时还能不能用」，不能依赖现查 metadata。
        val minApp = prefs().getString(DshEnv.KEY_RUNTIME_MIN_APP, null).orEmpty()
        _state.update {
            it.copy(
                installed = installed,
                port = port(),
                runtimeVersion = prefs().getString(DshEnv.KEY_RUNTIME_VERSION, null),
                appUpdateRequired = installed && !appSatisfies(minApp),
                requiredAppVersion = minApp.ifEmpty { null },
                rootfsSizeBytes = if (installed) cachedSize else 0L,
                phase = if (installed) it.phase else DshPhase.NOT_READY,
            )
        }
        // 装好了但还没量过（升级上来的旧安装）：后台补一次，别让界面一直显示「—」
        if (installed && cachedSize <= 0L) refreshRootfsSize()
    }

    /**
     * 后台重算容器体积并落盘缓存。
     *
     * 只允许从这里进入 [dirSize]：它要遍历十万级文件，在组合期同步调用会让
     * 每次导航回首页都卡两秒以上（真机 MIUIScout 实测 duration=2505ms）。
     */
    override fun refreshRootfsSize() {
        if (!ready) return
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { dirSize(DshEnv.rootfs(appContext)) }
            prefs().edit().putLong(DshEnv.KEY_ROOTFS_SIZE, bytes).apply()
            _state.update { it.copy(rootfsSizeBytes = bytes) }
        }
    }

    override fun prefs() = appContext.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)

    fun port(): Int {
        if (!ready) return DshEnv.DEFAULT_PORT
        return prefs().getInt(DshEnv.KEY_PORT, DshEnv.DEFAULT_PORT).let {
            if (it in 1..65535) it else DshEnv.DEFAULT_PORT
        }
    }

    /** 当前 WebUI 地址（带认证 token，若已从 dsh 输出里捕获到）。 */
    fun webUrl(): String = _state.value.webUrl

    fun setPort(p: Int) {
        if (!ready || p !in 1..65535) return
        prefs().edit().putInt(DshEnv.KEY_PORT, p).apply()
        _state.update { it.copy(port = p) }
    }

    // ────────────────────────── 端口占用检测 ──────────────────────────

    /** 127.0.0.1:port 是否已被某个进程监听（TCP connect 探测）。 */
    fun isPortInUse(port: Int): Boolean = runCatching {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1", port), 800)
            true
        }
    }.getOrDefault(false)

    /**
     * 从 [from] 起向后扫第一个空闲端口。
     *
     * 供「端口被占用 → 换一个」用；扫到 65535 还没有就回退默认端口（仍可能占用，
     * 但比拿一个越界端口强）。
     */
    fun findFreePort(from: Int = port() + 1): Int {
        var p = from.coerceIn(1, 65535)
        while (p <= 65535) {
            if (!isPortInUse(p)) return p
            p++
        }
        return DshEnv.DEFAULT_PORT
    }

    /**
     * 用户对「端口被占用」作出的决定。
     *
     * [PortConflictAction.AUTO]：换一个空闲端口；[PortConflictAction.MANUAL]：用
     * [newPort]（越界或仍被占用则保留冲突标记，交由 UI 提示）；[PortConflictAction.FORCE]：
     * 不改端口、强行继续启动。
     *
     * `setPort` 只是同步 prefs 写 + state 更新，改完端口再调 [bootstrap] 重跑；
     * FORCE 则跳过端口探测、直接 [startAndAwait]。
     */
    fun resolvePortConflict(action: PortConflictAction, newPort: Int? = null) {
        if (!ready) return
        when (action) {
            PortConflictAction.AUTO -> {
                setPort(findFreePort())
                _state.update { it.copy(portConflict = false) }
                bootstrap()
            }
            PortConflictAction.MANUAL -> {
                val p = newPort ?: return
                if (p !in 1..65535 || isPortInUse(p)) return // 保留 portConflict，UI 继续提示
                setPort(p)
                _state.update { it.copy(portConflict = false) }
                bootstrap()
            }
            PortConflictAction.FORCE -> {
                // 强行启动：跳过端口探测直接拉起（用户明知端口被占仍要用）
                _state.update { it.copy(portConflict = false) }
                scope.launch {
                    bootMutex.withLock { startAndAwait() }
                }
            }
        }
    }

    // ────────────────────────── 局域网访问 ──────────────────────────

    /** 局域网访问开关（默认关）。 */
    fun lanEnabled(): Boolean =
        ready && prefs().getBoolean(DshEnv.KEY_LAN, false)

    fun setLanEnabled(enabled: Boolean) {
        if (!ready) return
        prefs().edit().putBoolean(DshEnv.KEY_LAN, enabled).apply()
    }

    // ────────────────────────── 竞速通道（镜像/测速） ──────────────────────────

    /** 竞速通道的三个通道 id（见 [raceEnabled]）。 */
    const val RACE_PLUGINS = "plugins"
    const val RACE_APP_UPDATE = "app_update"
    const val RACE_RUNTIME = "runtime"

    /** 竞速通道**总开关**（默认开）。关掉时 [raceEnabled] 一律返回 false。 */
    fun raceMasterEnabled(): Boolean =
        !ready || prefs().getBoolean(DshEnv.KEY_RACE_MASTER, true)

    fun setRaceMasterEnabled(enabled: Boolean) {
        if (!ready) return
        prefs().edit().putBoolean(DshEnv.KEY_RACE_MASTER, enabled).apply()
    }

    /**
     * 某个通道是否启用竞速（总开关 + 该通道分开关，两者都开才生效）。
     *
     * 分开关默认开：老用户此前的行为就是「能加速就加速」，升级后不该悄悄变慢。
     */
    override fun raceEnabled(channel: String): Boolean {
        if (!raceMasterEnabled()) return false
        val key = when (channel) {
            RACE_PLUGINS -> DshEnv.KEY_RACE_PLUGINS
            RACE_APP_UPDATE -> DshEnv.KEY_RACE_APP_UPDATE
            RACE_RUNTIME -> DshEnv.KEY_RACE_RUNTIME
            else -> return false
        }
        return !ready || prefs().getBoolean(key, true)
    }

    fun setRaceEnabled(channel: String, enabled: Boolean) {
        if (!ready) return
        val key = when (channel) {
            RACE_PLUGINS -> DshEnv.KEY_RACE_PLUGINS
            RACE_APP_UPDATE -> DshEnv.KEY_RACE_APP_UPDATE
            RACE_RUNTIME -> DshEnv.KEY_RACE_RUNTIME
            else -> return
        }
        prefs().edit().putBoolean(key, enabled).apply()
    }

    // ────────────────────────── 应用启动行为 ──────────────────────────

    /**
     * 打开 App 时是否自动启动服务（默认关）。
     *
     * 与开机自启（[DshAutostart]）分开：那是设备开机，这是用户点开应用。两者可以各自
     * 独立成立 —— 有人只在手动打开时必须自动跑起来，不想让它在后台常驻到开机。
     */
    fun autoStartOnLaunch(): Boolean =
        ready && prefs().getBoolean(DshEnv.KEY_AUTO_START_ON_LAUNCH, false)

    fun setAutoStartOnLaunch(enabled: Boolean) {
        if (!ready) return
        prefs().edit().putBoolean(DshEnv.KEY_AUTO_START_ON_LAUNCH, enabled).apply()
    }

    /** 服务就绪后是否自动打开 DSH 页面（默认关）。 */
    fun autoOpenWebUi(): Boolean =
        ready && prefs().getBoolean(DshEnv.KEY_AUTO_OPEN_WEBUI, false)

    fun setAutoOpenWebUi(enabled: Boolean) {
        if (!ready) return
        prefs().edit().putBoolean(DshEnv.KEY_AUTO_OPEN_WEBUI, enabled).apply()
    }

    /** 本机局域网 IPv4（site-local），取不到返回 null。 */
    fun lanIp(): String? = runCatching {
        val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
        while (ifaces.hasMoreElements()) {
            val nif = ifaces.nextElement()
            if (!nif.isUp || nif.isLoopback) continue
            val addrs = nif.inetAddresses
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement()
                if (a is java.net.Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) {
                    return@runCatching a.hostAddress
                }
            }
        }
        null
    }.getOrNull()

    // ────────────────────────── 容器运行时选择 ──────────────────────────

    /**
     * 当前选择的运行时 id。
     *
     * 默认 proroot：它不走 ptrace，容器内进程开销明显低于 proot。代价是在部分
     * 内核上会卡在 seccomp/ptrace 上起不来 —— 这条路由 [noteProrootFailure]
     * 兜底，失败一次就自动切回 proot，所以默认选快的那个是合理的。
     *
     * 注意这是**用户的选择**，不一定是实际在跑的那个：proroot 的五个 .so 只有
     * arm64-v8a 有，x86_64 设备上这里返回 proroot 而 [runtime] 给的是 proot。
     * 凡是描述或依赖「实际行为」的地方用 [effectiveRuntimeId]。
     */
    fun runtimeId(): String =
        if (!ready) "proroot" else prefs().getString(DshEnv.KEY_RUNTIME, "proroot") ?: "proroot"

    /**
     * 实际会用来起容器的运行时 id。
     *
     * 与 [runtimeId] 的差别只在「选了 proroot 但它在本机不可用」这一种情况，而这一种
     * 在 x86_64 设备上是**常态**（proroot 上游只出 arm64）。分不清两者会同时产生
     * 三个可观察的错误，报障包里都出现过：
     *  - 启动日志第 1 行说 proot（走 runtime()），第 3 行说「proroot 无条件启用 l2s」；
     *  - basic.txt 记 `ContainerRuntime: proroot`，与同一个包里的 dsh.log 自相矛盾；
     *  - [linkBecomesSymlink] 误判为 true，pnpm 被无谓地降级成 `--package-import-method
     *    copy` —— 明明 proot 在硬链接可用时根本不加 `--link2symlink`。
     */
    fun effectiveRuntimeId(): String = if (!ready) "proroot" else runtime().id()

    fun setRuntimeId(id: String) {
        if (!ready) return
        prefs().edit().putString(DshEnv.KEY_RUNTIME, id).putInt(DshEnv.KEY_PROROOT_FAIL, 0).apply()
    }

    /** 解析出实际可用的运行时；选中的不可用时静默回退 proot。 */
    override fun runtime(): ContainerRuntime {
        val proot = ContainerRuntime.Proot(appContext, nativeLib("libproot.so"))
        if (runtimeId() != "proroot") return proot
        val proroot = ContainerRuntime.Proroot(appContext, ContainerRuntime.Proroot.defaultDir(appContext))
        return if (proroot.available()) proroot else proot
    }

    /**
     * 刚刚发生过 proroot → proot 的自动回退，等着用 proot 重试一次。
     *
     * 存在的理由：[PROROOT_FAIL_LIMIT] 是 1，失败即回退。如果只写一行
     * 「已切回 proot，请重新启动」，用户首次开应用就会撞上一次失败并被
     * 要求手动重试——而此时我们已经知道该用 proot 了。
     */
    @Volatile
    private var prorootFellBack = false

    /**
     * 记一次 proroot 启动失败；达上限强制切回 proot。返回是否触发了回退。
     *
     * 插件层面的失败不算：见 [PLUGIN_FAILURE_MARKS]。
     */
    fun noteProrootFailure(why: String): Boolean {
        if (runtimeId() != "proroot") return false
        if (lastLogSuggestsPluginFailure()) {
            logWarn(R.string.dsh_log_plugin_stage_failure)
            return false
        }
        val n = prefs().getInt(DshEnv.KEY_PROROOT_FAIL, 0) + 1
        return if (n >= PROROOT_FAIL_LIMIT) {
            prefs().edit().putString(DshEnv.KEY_RUNTIME, "proot").putInt(DshEnv.KEY_PROROOT_FAIL, 0).apply()
            // 两条独立的完整句子，而不是把「连续 N 次」拼进一句 —— 片段拼接在
            // 别的语言里语序就散了（这也是为什么没有 dsh_log_proroot_failed_prefix）
            if (n > 1) logWarn(R.string.dsh_log_proroot_failed_times, n, why)
            else logWarn(R.string.dsh_log_proroot_failed, why)
            prorootFellBack = true
            true
        } else {
            prefs().edit().putInt(DshEnv.KEY_PROROOT_FAIL, n).apply()
            false
        }
    }

    /**
     * 启动日志尾部是否指向插件树加载失败。
     *
     * 保守匹配：宁可漏判（退回旧的误降级行为）也不误判（把真正的 proroot 故障
     * 当成插件问题、让用户一直卡在坏运行时上）。
     */
    private fun lastLogSuggestsPluginFailure(): Boolean {
        if (!ready) return false
        val tail = runCatching {
            LogStore.named(DshEnv.serverLog(appContext)).tail(200)
        }.getOrDefault("")
        if (tail.isEmpty()) return false
        return PLUGIN_FAILURE_MARKS.any { tail.contains(it, ignoreCase = true) }
    }

    /** 插件树加载失败的可修复提示；无此迹象时返回 null。 */
    fun pluginTreeFailureHint(): String? =
        if (lastLogSuggestsPluginFailure()) str(R.string.dsh_err_plugin_tree_failed) else null

    private fun nativeLib(name: String): File = File(DshEnv.nativeLibDir(appContext), name)

    /**
     * rootfs 所在文件系统是否支持真实硬链接。
     *
     * 支持时 proot 不加 `--link2symlink`：该扩展把 `link()` 目标改写成指向临时中间文件的
     * 符号链接，而 dsh 的 write 工具正是用 `link(临时文件, 目标)` 发布后立刻删临时目录 ——
     * 于是新建文件 100% 变悬空链接（write 报成功但读不出来）。
     */
    fun hardlinkSupported(): Boolean {
        hardlinkOk?.let { return it }
        synchronized(this) {
            hardlinkOk?.let { return it }
            val dir = DshEnv.rootfs(appContext).takeIf { it.isDirectory } ?: appContext.filesDir
            val src = File(dir, ".dshfolk-linkprobe")
            val dst = File(dir, ".dshfolk-linkprobe.hl")
            var ok = false
            var detail = ""
            try {
                dir.mkdirs()
                src.delete(); dst.delete()
                Files.write(src.toPath(), byteArrayOf('o'.code.toByte(), 'k'.code.toByte()))
                Files.createLink(dst.toPath(), src.toPath())
                ok = dst.isFile && dst.length() == 2L
                if (!ok) detail = str(R.string.dsh_log_hardlink_unreadable)
            } catch (e: Throwable) {
                ok = false
                detail = "${e.javaClass.simpleName}: ${e.message}"
            } finally {
                runCatching { src.delete() }
                runCatching { dst.delete() }
            }
            android.util.Log.i(TAG, "硬链接支持=$ok${if (detail.isEmpty()) "" else "（$detail）"}")
            hardlinkOk = ok
            hardlinkDetail = detail
            return ok
        }
    }

    /** 硬链接探测失败的原因；成功时为空。供启动日志记录用。 */
    fun hardlinkDetail(): String = hardlinkDetail

    /**
     * guest 里的 `link()` 是否会被改写成符号链接。
     *
     * 不能只看 [hardlinkSupported]：proroot **无条件**加 `--link2symlink`
     * （见 ContainerRuntime.Proroot.baseArgv），所以哪怕宿主文件系统支持真硬链接，
     * 容器内拿到的仍然是符号链接。pnpm 正是用 `link()` 从内容存储装包，一旦被改写，
     * `require.resolve` 的 realpath 就会跳进 CAS、把包目录结构解析没了。
     */
    fun linkBecomesSymlink(): Boolean = !hardlinkSupported() || effectiveRuntimeId() == "proroot"

    /** 复制 proot 的 NEEDED 依赖到可写 lib 目录（proroot 不需要）。 */
    private fun ensureRuntimeFiles() {
        val libDir = File(appContext.filesDir, "lib").apply { mkdirs() }
        DshEnv.tmpDir(appContext).mkdirs()
        // 每条 exec 路径都要保证 DNS 在：冷启动直接进插件页 / 终端页时不会走 bootstrap，
        // 少了这一步容器里 pnpm、apt 全是 EAI_AGAIN。已存在则原样保留（用户可能改过）。
        ensureContainerDns()
        ensureProfilePnpmSettings()
        ensureContainerGroups()
        if (runtime().id() != "proot") return
        // jniLibs 里叫 libtalloc.so / libandroidshmem.so，proot 按 SONAME 找
        copyExec(nativeLib("libtalloc.so"), File(libDir, "libtalloc.so.2"))
        copyExec(nativeLib("libandroidshmem.so"), File(libDir, "libandroid-shmem.so"))
    }

    private fun copyExec(src: File, dst: File) {
        if (!src.isFile) return
        if (dst.isFile && dst.length() == src.length()) return
        runCatching {
            src.inputStream().use { i -> FileOutputStream(dst).use { o -> i.copyTo(o) } }
            dst.setExecutable(true, false)
        }
    }

    // ────────────────────────── 进容器执行 ──────────────────────────

    private fun baseArgv(): MutableList<String> =
        runtime().baseArgv(DshEnv.rootfs(appContext), hardlinkSupported()).toMutableList()

    /** 容器内与宿主无关的 guest 环境 + 运行时专用环境。 */
    private fun applyEnv(pb: ProcessBuilder) {
        val rt = runtime()
        val libDir = File(appContext.filesDir, "lib")
        val env = pb.environment()
        if (rt.id() == "proot") {
            env["PROOT_TMP_DIR"] = DshEnv.tmpDir(appContext).absolutePath
            env["PROOT_LOADER"] = nativeLib("libprootloader.so").absolutePath
            env["PROOT_LOADER_32"] = nativeLib("libprootloader32.so").absolutePath
            env["LD_LIBRARY_PATH"] = "${libDir.absolutePath}:${DshEnv.nativeLibDir(appContext).absolutePath}"
            // 无硬链接时把 l2s 中间文件集中到 rootfs 内（默认就近存放会随 tmp 被清掉）
            if (!hardlinkSupported()) {
                val l2s = DshEnv.l2sDir(appContext).apply { mkdirs() }
                env["PROOT_L2S_DIR"] = l2s.absolutePath
            }
        }
        runCatching { rt.applyEnv(env, appContext.filesDir, libDir, DshEnv.tmpDir(appContext)) }
        // 别让 dsh 的原生插件加载器把 .node 二进制「materialize」一遍。
        //
        // node-addon-native-custom-loader 的 materializedNativeBinaryPath() 是这么干的：
        //   读源文件 → 写一份 temp → link(temp, destination) → 删掉 temp
        // 在无硬链接设备上容器里的 link() 被 --link2symlink 改写，destination 于是成了符号链接
        // （真身被挪进 rootfs/.l2s），temp 一删，那份链接就指向了加载不了的东西。真机实测
        // （dsh 0.1.7-rc.2 + r5 运行时，proroot）：
        //   /tmp/node-addon-native-custom-loader-0/native-cache/.../linux-arm64-gnu-napi-v9.node
        //     -> /.l2s/.l2s..linux-arm64-gnu-napi-v9.node.<pid>.<hex>.tmp0001
        // 内层报错是 "Invalid or unexpected token"（Node 把那个路径当 JS 解析了），外层表现为
        // `No usable native binding found for node-addon-require-builtin-linux-arm64-gnu`，
        // dsh 启动即失败。proot 因为路径翻译恰好还能解析同一个链接，所以只在 proroot 下暴露。
        //
        // 为什么以前不犯：materialize 只在缓存里**没有**这条记录时才做（命中且哈希一致就直接用）。
        // 运行时更新会换掉整个 rootfs，而 rootfs/tmp 不在 DshEnv.PRESERVED_PATHS 里 —— 缓存一清空，
        // 每次启动都要重来一遍这个链接把戏，于是稳定失败。
        //
        // 关掉缓存后加载器直接从包路径 require（真机实测 NARB_DISABLE_NATIVE_CACHE=1 可加载），
        // 既没有 link/unlink、也没有那层链接间接；代价只是少一次到短路径的物化复制 ——
        // 在 Linux 上本来也没有 Windows 那种路径长度/杀软扫描的理由。
        env["NARB_DISABLE_NATIVE_CACHE"] = "1"
        // guest 侧环境：PATH 必须覆盖，否则继承 Android 的 /system/bin 找不到 bash 工具链
        env["HOME"] = "/root"
        env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        env["TMPDIR"] = "/tmp"
        env["LANG"] = "C.UTF-8"
        env["DEBIAN_FRONTEND"] = "noninteractive"
        env["TERM"] = "xterm-256color"
    }

    /** 在 rootfs 内执行一条 bash 命令（stderr 合并进 stdout）。 */
    fun execRootfs(bashCommand: String): Process {
        ensureRuntimeFiles()
        val argv = baseArgv()
        argv += listOf("/bin/bash", "-c", bashCommand)
        val pb = ProcessBuilder(argv).redirectErrorStream(true)
        pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        applyEnv(pb)
        return pb.start()
    }

    /** 启动交互式 bash（供终端页；cd/export 状态保持）。 */
    fun execRootfsInteractive(): Process {
        ensureRuntimeFiles()
        val argv = baseArgv()
        argv += "/bin/bash"
        val pb = ProcessBuilder(argv).redirectErrorStream(true)
        applyEnv(pb)
        pb.environment()["DSH_INTERACTIVE"] = "1"
        return pb.start()
    }

    /**
     * PTY 会话的 argv（终端页用）。默认 `bash -l`，也可指定 guest 命令。
     *
     * 与 [execRootfs] 共用 [baseArgv]：各写一份的话，PTY 里的 shell 会跑在和普通命令
     * 不一样的环境里，少一个 PROOT_LOADER 就直接起不来。
     */
    fun ptyArgv(vararg guestCmd: String): Array<String> {
        ensureRuntimeFiles()
        val argv = baseArgv()
        if (guestCmd.isEmpty()) {
            argv += "/bin/bash"
            argv += "-l"
        } else {
            argv += guestCmd
        }
        return argv.toTypedArray()
    }

    /**
     * PTY 会话的环境变量（`KEY=VALUE` 形式）。
     *
     * 借一个临时 ProcessBuilder 收集，而不是重抄一份：环境构造分散在 [applyEnv] 与
     * [ContainerRuntime.applyEnv]，还随 proot/proroot 分叉，手抄必漏。
     */
    fun ptyEnv(): Array<String> {
        val probe = ProcessBuilder("/system/bin/true")
        applyEnv(probe)
        return probe.environment()
            .filter { it.key != null && it.value != null }
            .map { "${it.key}=${it.value}" }
            .toTypedArray()
    }

    /**
     * 同步执行并读回输出（带超时，默认 60s）。
     *
     * 读流必须放到单独线程：readText() 会一直阻塞到 EOF，先读再 waitFor(timeout) 的写法
     * 里超时是彻底无效的 —— 子进程挂住不退出（等 stdin、卡在网络、pnpm 死锁）就会把调用
     * 线程永久钉死，容器进程也泄漏。这里改成读线程 + join(超时) + destroyForcibly。
     *
     * 超时返回已读到的部分而不是空串：pnpm / pip 那种半途卡死的情况，前面几百行输出
     * 往往正好说明卡在哪。
     */
    fun execRootfsForOutput(bashCommand: String, timeoutMs: Long = 60_000L): String =
        execRootfsStreaming(bashCommand, timeoutMs) {}

    /**
     * 同上，但每读到一整行就回调一次。
     *
     * 存在的理由：[execRootfsForOutput] 把输出攒到结束才一次返回，而
     * `dsh plugin add` 背后的 pnpm 可能跑几分钟——这段时间里界面拿不到
     * 任何进展，用户无法判断是在装还是卡死了。
     *
     * onLine 在读线程上调用（不是主线程），回调里不要直接碰 Compose 状态。
     */
    fun execRootfsStreaming(
        bashCommand: String,
        timeoutMs: Long = 60_000L,
        onLine: (String) -> Unit,
    ): String = runCatching {
        val p = execRootfs(bashCommand)
        val sb = StringBuilder()
        val reader = Thread {
            runCatching {
                p.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(8192)
                    // 手写行切分而不用 readLine()：pnpm 的进度行以 \r 结尾且不带 \n，
                    // readLine() 会一直等到下一个 \n 才吐——进度就又成了攒一批才出。
                    val line = StringBuilder()
                    fun flush() {
                        if (line.isEmpty()) return
                        val text = line.toString()
                        line.setLength(0)
                        runCatching { onLine(text) }
                    }
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(sb) { sb.appendRange(buf, 0, n) }
                        for (i in 0 until n) {
                            val ch = buf[i]
                            if (ch == '\n' || ch == '\r') flush() else line.append(ch)
                        }
                    }
                    flush()
                }
            }
        }
        reader.isDaemon = true
        reader.start()
        reader.join(timeoutMs)
        if (reader.isAlive) {
            // 超时：先杀进程让读流拿到 EOF，再给读线程一点时间收尾
            p.destroyForcibly()
            reader.join(1_000)
            logWarn(R.string.dsh_log_cmd_timeout, timeoutMs / 1000, bashCommand.take(120))
        } else if (!p.waitFor(2, TimeUnit.SECONDS)) {
            // 流已关但进程还在（少见：子孙进程继承了 stdout 又提前关掉）
            p.destroyForcibly()
        }
        synchronized(sb) { sb.toString() }
    }.getOrElse { "" }

    // ────────────────────────── 引导（下载 + 解压 + 启动）──────────────────────────

    /** 引导/重启/重装共用的串行锁：三者都会动 rootfs 与服务进程。 */
    private val bootMutex = Mutex()

    /**
     * 一键引导：未装则下载安装，然后拉起 web 服务。
     *
     * [startServer] 的守卫现在只看进程，不再兼当「防重复下载」；
     * 而 HarnessService 在 DOWNLOADING / EXTRACTING 阶段依然会放行 bootstrap，
     * 连点两次启动就会开两条 135MB 下载。用互斥锁 + 阶段判定拦住。
     */
    override fun bootstrap() {
        scope.launch {
            if (busy()) return@launch
            bootMutex.withLock {
                if (!DshEnv.isRuntimeInstalled(appContext)) {
                    downloadAndInstall()
                    if (_state.value.phase == DshPhase.ERROR) return@withLock
                } else if (rootfsArchMismatch()) {
                    // 已装的 rootfs 架构不对，重下一份正确的。
                    // 1.7.6 在带 arm 转译层的 x86_64 设备上会装成 arm64 rootfs（见
                    // DshSource.runtimeArch 的注释），那份 rootfs 一执行就 SIGILL，
                    // 而 isRuntimeInstalled 只看文件在不在、会一直认为「已安装」——
                    // 不在这里自愈，用户就只能手动重装运行时才能脱困。
                    logWarn(R.string.dsh_log_arch_mismatch_redownload)
                    downloadAndInstall()
                    if (_state.value.phase == DshPhase.ERROR) return@withLock
                }
                setupResolvConf()
                seedPlugins()
                ensureFsBridgeCli()
                if (checkPortConflict()) return@withLock
                startAndAwait()
            }
        }
    }

    /**
     * 已解压的 rootfs 是不是别的架构。
     *
     * 判据取 rootfs 里 `usr/local/bin/node` 的 ELF `e_machine` —— 它就是 proot 起来后
     * 第一个真正要 exec 的目标。读不到（文件缺失、异常）返回 false：宁可放行也不要因为
     * 一次读失败就把用户的 150MB 运行时删了重下。
     */
    private fun rootfsArchMismatch(): Boolean = runCatching {
        val node = File(DshEnv.rootfs(appContext), "usr/local/bin/node")
        val machine = elfMachine(node) ?: return@runCatching false
        val want = when (DshSource.runtimeArch()) {
            "arm64-v8a" -> ELF_MACHINE_AARCH64
            else -> ELF_MACHINE_X86_64
        }
        if (machine == want) return@runCatching false
        logWarn(R.string.dsh_log_node_machine, machine, want)
        true
    }.getOrDefault(false)

    /**
     * 预装内置插件（首启，以及从旧版本升级后补装新增的那几个）。
     *
     * 放在服务启动**之前**：首启本来就要下 150MB + 解压，再加一轮 pnpm 是等比例的；
     * 而服务只启动一次、插件已经生效，不会出现「就绪了又要重启」的突兀体验。
     *
     * **按包名逐个记账**而不是记一个「已完成」布尔量：1.6 把清单从 2 个加到 3 个，
     * 如果沿用布尔量，1.5 老用户的标记已经是 true，新增的 dsh-config-manager 就
     * 永远轮不到装 —— 而它恰好是配置备份功能的依赖。记名字才能让老用户补上增量。
     *
     * 装过就不再重试（无论成功失败）：失败不该在每次冷启动重来，用户可以去商店手动装。
     * 但已经真装上的会先被跳过 —— 用 [DshPluginRepo.bundles] 对一遍，
     * 手动装过的同样算数，不会重复跑一次 pnpm。
     *
     * 这里**不做**安装后验证：插件是我们自己挑的、已人工验证过，在首启路径上再跑
     * 一次 `dsh web --port 0` 只会把首启拖长几分钟。
     */
    private suspend fun seedPlugins() {
        if (!DshEnv.isRuntimeInstalled(appContext)) return

        // 预装/补装全靠 `dsh plugin` → pnpm。运行时没带 pnpm 时（旧 rootfs）只报一次
        // 并停止：四个预装包逐个 exit 127 只是刷屏，用户得不到任何可行动的信息。
        // 恢复路径就是更新运行时 —— 不含 pnpm 的旧 rootfs 已从两个通道重新发布。
        if (!pnpmInstalled()) {
            logWarn(R.string.dsh_log_missing_pnpm)
            return
        }

        // profile 的 bundles 里留着「解析不到的包」会让 dsh 直接拒绝启动
        // （见 DshPluginRepo.pruneUnresolvableBundles 的说明）。启动前先清掉，
        // 别让用户先看一轮「服务进程已退出」再自动切回 proot。
        val prunedStale = runCatching {
            DshPluginRepo.pruneUnresolvableBundles(managedSeedPackages())
        }.getOrElse { emptySet() }
        if (prunedStale.isNotEmpty()) {
            logInfo(R.string.dsh_log_bundle_pruned, joinForLog(prunedStale))
        }

        val p = prefs()
        val attempted = p.getString(DshEnv.KEY_SEEDED_PLUGINS, null)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toMutableSet()
            // 首次迁移：1.5 及更早只有布尔量，true 就意味着当时那两个已经试过了
            ?: if (@Suppress("DEPRECATION") p.getBoolean(DshEnv.KEY_SEED_PLUGINS_DONE, false)) {
                mutableSetOf("dsh-web-mobile", "dshmarket")
            } else {
                mutableSetOf()
            }

        // 已经装上的（含用户手动装的）。放在补修之前取：补修要靠它判断「记过账但没生效」。
        // 判据是「声明在 profile 的 bundles 里**且** node_modules 里真的有包」——
        // 只看声明会把「换运行时后 profile 重建、包却没了」的坏安装当成好的。
        val bundleState = runCatching { DshPluginRepo.bundleState() }
            .getOrElse { DshPluginRepo.BundleState(emptyList(), emptySet()) }
        val installed = bundleState.declared.filter { it in bundleState.present }.toSet()

        // ── 「上游已内置」必须与「试过了」分开记账 ──
        //
        // 判据是**实际读到的 entry id**：同一条 id 只要由**别的**包（通常是上游核心包，
        // dsh 0.1.5 的 dsh-web-app 就自带 file-upload）声明了，这个预装包就不能再装 ——
        // 装了会让整棵插件树报 duplicate loader entry id 而根本起不来。
        //
        // 关键是**不能写进 attempted**。写进去等于告诉补修逻辑「我们试过它、但它不在
        // bundles 里」，于是被摘账重装，形成：跳过 → 重装 → 启动失败 → 自动卸载 →
        // 下次启动又重装 的死循环（1.9.0 真机实测）。所以这里单独一份 shadowed，
        // 并且三处都要认它：补修、换运行时重试、以及本轮要装哪些。
        // 退役包：上游已自带同名能力，一律不装；已装的在下面摘掉。
        // 判据是**运行时版本**（dsh ≥ RETIRE_MIN_DSH_VERSION 才有那份内置能力），
        // 不再去读上游的 entry id —— 那条路 yaml 解析失败时会静默返回空表，
        // 于是「该退役的没退役」，用户看到预装包还在（真机复现）。
        val runtimeNow = p.getString(DshEnv.KEY_RUNTIME_VERSION, "").orEmpty()
        val retiredActive = runtimeSupportsRetiredSeed(runtimeNow)
        val mappedShadowed = if (retiredActive) RETIRED_SEED_PLUGINS.keys.toSet() else emptySet()
        // 落盘的补充项：只由「启动失败 → 自动卸载」那条路径才能知道、映射表没覆盖的冲突。
        // 不落盘的话每次冷启动都会重装一次再失败一次。换运行时即作废（内置与否是运行时的属性）。
        val recordedShadowed = if (p.getString(DshEnv.KEY_SEED_SHADOWED_RUNTIME, null) == runtimeNow) {
            p.getString(DshEnv.KEY_SEED_SHADOWED, null)
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
                .orEmpty()
        } else {
            emptySet()
        }
        val shadowed = mappedShadowed + recordedShadowed
        // 刚被清掉的声明如果本来就属于「上游已内置」那一类，落盘记住它：
        // 实时判定已经会跳过它，这里只是让判定结果跨启动稳定。
        rememberShadowed(prunedStale.filter { it in mappedShadowed })

        // 已经装着的冲突包**在启动之前**就摘掉：否则这次启动照样先以 duplicate entry id
        // 失败一次，再走「失败后自愈」重试 —— 用户白等一轮，日志里多一段 node 堆栈。
        // 用 declared 而不是 installed：包已从 node_modules 消失、声明却还留着时，
        // 也要走一次卸载把声明摘干净（dsh 解析不到那条声明就拒绝启动）。
        val shadowedInstalled = shadowed.filter { it in installed || it in bundleState.declared }
        for (pkg in shadowedInstalled) {
            logWarn(R.string.dsh_log_seed_shadow_uninstall, pkg, RETIRED_SEED_PLUGINS[pkg] ?: pkg)
            runCatching {
                DshPluginRepo.uninstall(pkg, onLine = { line -> appendLog(line) })
            }.onFailure { appendLog(it.message ?: it.javaClass.simpleName) }
        }
        val shadowedSkipped = shadowed - shadowedInstalled.toSet()
        if (shadowedSkipped.isNotEmpty()) {
            logInfo(R.string.dsh_log_seed_skip_builtin, joinForLog(shadowedSkipped))
        }
        // 历史遗留：这些包以前被记成「试过了」。摘掉它们 —— 万一以后换回不含该能力的
        // 运行时，实时判定不再命中，它们就该重新预装。
        if (attempted.removeAll(shadowed.toSet())) persistSeeded(attempted)

        // 修好根因后补修历史失败（见 KEY_SEED_REPAIR_REV）
        applySeedRepair(p, attempted, installed, shadowed)
        // 换运行时（或本键还不存在）时再给一次机会：见 KEY_SEED_RUNTIME 的说明
        applySeedEnvRetry(p, attempted, installed, shadowed)

        val todo = SEED_PLUGINS.filter { it !in attempted && it !in shadowed }
        val missing = todo.filter { it !in installed }
        if (todo.isNotEmpty() && missing.isEmpty()) {
            persistSeeded(attempted + todo)
        } else if (missing.isNotEmpty()) {
            _state.update {
                it.copy(phase = DshPhase.EXTRACTING, progress = 0f, message = str(R.string.dsh_plugin_seeding))
            }
            for (pkg in missing) {
                logInfo(R.string.dsh_log_seeding, pkg)
                val code = installSeedPkgWithApproval(pkg)
                if (code == 0) {
                    logInfo(R.string.dsh_log_seed_done, pkg)
                } else {
                    logWarn(R.string.dsh_log_seed_failed, pkg)
                }
            }
            persistSeeded(attempted + todo)
            // 预装会大幅改变 node_modules 体积，顺手重算一次缓存
            refreshRootfsSize()
            _state.update { it.copy(phase = DshPhase.NOT_READY, progress = 1f) }
        }

        // 按版本号门控刷新预装插件：只有当前已装版本**低于** SEED_MIN_VERSIONS 要求时才重装
        // （github: 拉最新 main）。用户要求「只有需要的时候才更新，对比版本号、低于才更新」。
        applySeedVersionUpgrade(shadowed)

        // 装后兜底（每次预装路径都跑，包括 todo 为空时 —— 运行时升级把上游内置能力
        // 带进来后，历史预装包也可能变成冲突源）：按实际读到的 entry id 找重复，
        // 卸载其中的预装包，保住插件树能启动。核心包与用户自装包绝不自动动。
        runCatching {
            val after = DshPluginRepo.pluginEntries(includeCore = true)
            val byId = after.entries
                .flatMap { (pkg, ids) -> ids.map { id -> id to pkg } }
                .groupBy({ it.first }, { it.second })
            for ((id, pkgs) in byId) {
                if (pkgs.size < 2) continue
                val conflicts = pkgs.distinct().filter { it in SEED_PLUGINS }
                if (conflicts.isEmpty()) continue
                for (pkg in conflicts) {
                    logWarn(R.string.dsh_log_dup_entry_removed, pkg, id)
                    DshPluginRepo.uninstall(pkg, onLine = { line -> appendLog(line) })
                }
            }
        }
    }

    /** 从 dsh plugin 的输出里取真实退出码；没有标记行（超时/容器没起来）返回 null。 */
    private fun exitCodeOf(out: String): Int? = out.lineSequence()
        .lastOrNull { it.startsWith(DshPluginRepo.EXIT_MARKER) }
        ?.removePrefix(DshPluginRepo.EXIT_MARKER)?.trim()?.toIntOrNull()

    /**
     * 记下「上游已内置、别再预装」的包（跟随运行时版本，换运行时即作废）。
     *
     * 只有启动失败后的兜底修复才用得上它 —— 映射表能判定的冲突在启动前就算出来了，
     * 不需要落盘。这条路径的存在是为了让**没进映射表**的冲突也不至于变成每轮启动一次的
     * 「卸载 → 重装 → 再失败」。
     */
    private fun rememberShadowed(names: Collection<String>) {
        if (names.isEmpty()) return
        val p = prefs()
        val runtime = p.getString(DshEnv.KEY_RUNTIME_VERSION, "").orEmpty()
        val known = if (p.getString(DshEnv.KEY_SEED_SHADOWED_RUNTIME, null) == runtime) {
            p.getString(DshEnv.KEY_SEED_SHADOWED, null)
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
                .orEmpty()
        } else {
            emptySet()
        }
        p.edit()
            .putString(DshEnv.KEY_SEED_SHADOWED, (known + names).distinct().joinToString(","))
            .putString(DshEnv.KEY_SEED_SHADOWED_RUNTIME, runtime)
            .apply()
    }

    /** 记下「已尝试预装」的包名集合。 */
    private fun persistSeeded(names: Collection<String>) {
        prefs().edit()
            .putString(DshEnv.KEY_SEEDED_PLUGINS, names.distinct().joinToString(","))
            .apply()
    }

    /**
     * 装一个预装包，并自动处理 pnpm「拦下构建脚本、等人点头」的情况（预装路径没人可问）。
     *
     * pnpm 拦下依赖的构建脚本时 **不是**「装不上」，而是以退出码 1 结束、dsh 不 reconcile
     * bundles，插件躺在 node_modules 里却进不了 profile（界面上就是「预装了但未生效」，
     * 1.7.6 的 dsh-file-upload 因传递依赖 sharp/tesseract.js 带 install 脚本正是如此）。
     * 交互式 `pnpm approve-builds` 在容器里跑不了，所以这里自动放行**本次预装自己拉进来的**
     * 那几个包的构建脚本并重试（范围仅限 pnpm 点名的，不是全局开关）。返回最终退出码。
     */
    private suspend fun installSeedPkgWithApproval(pkg: String): Int {
        // 显式 `@latest`：预装包门禁（[applySeedVersionUpgrade]）就是靠这次重装把版本顶上去的，
        // 而 pnpm 对已声明过的 registry 依赖，`add <裸包名>` 是空操作 → 门禁会静默失效。
        // git 规格（dsh-folk-cloud）由 DshPluginRepo.install 忽略版本、按裸规格重新解析最新提交。
        var out = runCatching {
            DshPluginRepo.install(
                seedSpec(pkg),
                DshPluginRepo.VERSION_LATEST,
                onLine = { line -> appendLog(line) },
                fallbackTgz = seedFallbackTgz(pkg),
            )
        }.getOrElse { str(R.string.dsh_log_seed_exception, it.message ?: it.javaClass.simpleName) }
        var code = exitCodeOf(out)
        if (code != 0) {
            val pending = DshPluginRepo.pendingBuildApproval(out)
            if (pending.isNotEmpty()) {
                logInfo(R.string.dsh_log_seed_builds_blocked, joinForLog(pending))
                out = runCatching {
                    DshPluginRepo.install(
                        seedSpec(pkg),
                        DshPluginRepo.VERSION_LATEST,
                        onLine = { line -> appendLog(line) },
                        allowBuilds = pending,
                        fallbackTgz = seedFallbackTgz(pkg),
                    )
                }.getOrElse { str(R.string.dsh_log_seed_retry_exception, it.message ?: it.javaClass.simpleName) }
                code = exitCodeOf(out)
            }
        }
        // 只补一句可行动的话，不在这里修 rootfs：pnpm 由运行时自带。旧运行时那个无 shebang
        // 的 pnpm shim 会让 dsh 的 spawnSync 直接 ENOENT，报的就是这一行 —— 出路是更新运行时。
        if (code != 0 && out.contains(DshPluginRepo.NO_PNPM)) logWarn(R.string.dsh_log_missing_pnpm)
        // exitCodeOf 解析不出退出码时返回 null —— 按失败处理（-1），调用方 `code == 0` 才是成功
        return code ?: -1
    }

    /**
     * 按版本号门控刷新预装插件：把**当前已装**版本低于 [SEED_MIN_VERSIONS] 要求的预装插件用
     * `github:` 规格重装一次（重新解析 main 最新提交），跟上必需的修复。
     *
     * 与「每次 App 升级都重装」相比，这里只在**真的过时**时才动手（用户要求：搞个名单，对比
     * 插件版本号、低于才更新）。判据是容器里 `node_modules/<pkg>/package.json` 的实际版本，
     * 所以插件仓每次发有意义的改动都要抬 package.json 版本，App 这边再把要求版本填进名单。
     *
     * 只碰当前已装、且不在 shadowed（上游已内置、不该装）里的包：用户卸掉的不会被复活，缺失
     * 首装仍由 missing 循环负责。重装失败不影响已装旧版本（install 失败不卸载），只落一行日志；
     * 仍低于要求时下次启动会再试（这正是「需要时才更新」的期望行为）。
     */
    private suspend fun applySeedVersionUpgrade(shadowed: Set<String>) {
        val wanted = SEED_MIN_VERSIONS.filterKeys { it !in shadowed }
        if (wanted.isEmpty()) return
        val byPkg = runCatching { DshPluginRepo.listInstalled() }.getOrNull()
            ?.associateBy { it.pkg } ?: return
        // 已装、有可比版本、且低于要求 → 需要重装。没装的不在这里复活（缺失首装走 missing 循环）。
        val outdated = wanted.filter { (pkg, min) ->
            val cur = byPkg[pkg]?.installedVersion.orEmpty()
            cur.isNotEmpty() && compareVersions(cur, min) < 0
        }
        if (outdated.isEmpty()) return
        _state.update {
            it.copy(phase = DshPhase.EXTRACTING, progress = 0f, message = str(R.string.dsh_plugin_seeding))
        }
        for ((pkg, min) in outdated) {
            logInfo(R.string.dsh_log_seed_version_upgrade, pkg, byPkg[pkg]?.installedVersion.orEmpty(), min)
            val code = installSeedPkgWithApproval(pkg)
            if (code == 0) logInfo(R.string.dsh_log_seed_done, pkg) else logWarn(R.string.dsh_log_seed_failed, pkg)
        }
        refreshRootfsSize()
        _state.update { it.copy(phase = DshPhase.NOT_READY, progress = 1f) }
    }

    /**
     * 补修历史预装失败：把**记过账但实际没进 bundles**的包从账本里摘掉，让它们再试一次。
     *
     * 为什么需要：[persistSeeded] 无论成败都记账（失败不该每次冷启动重试），于是修好
     * 根因也救不回已经失败的那次。1.7.6 的 dsh-file-upload 正卡在这里 —— pnpm 拦下
     * 传递依赖的构建脚本导致它没进 bundles，而包名已被记账，之后永远不再尝试。
     *
     * 只摘「不在 bundles 里」的：已生效的包不会被重跑。轮次号只前进一次，所以补修
     * 最多发生一轮，不会变成每次启动都重试失败项。
     */
    private fun applySeedRepair(
        p: android.content.SharedPreferences,
        attempted: MutableSet<String>,
        installed: Set<String>,
        shadowed: Set<String>,
    ) {
        if (p.getInt(DshEnv.KEY_SEED_REPAIR_REV, 0) >= SEED_REPAIR_REV) return
        // shadowed 的包是「上游已内置、不该装」，不是「试过但没生效」：摘它的账只会让它
        // 被重装一次再撞 duplicate entry id（1.9.0 的死循环就是这么来的）。
        val retry = attempted.filter { it in SEED_PLUGINS && it !in installed && it !in shadowed }
        if (retry.isNotEmpty()) {
            attempted.removeAll(retry.toSet())
            persistSeeded(attempted)
            logInfo(R.string.dsh_log_seed_repair, joinForLog(retry))
        }
        p.edit().putInt(DshEnv.KEY_SEED_REPAIR_REV, SEED_REPAIR_REV).apply()
    }

    /**
     * 换运行时后重新给预装一次机会：把「记过账但没生效」的预装包从账本里摘掉。
     *
     * [applySeedRepair] 靠人工抬轮次号，只能救一次；用完后再遇到同一类问题
     * —— 预装失败 → 账本照样记账 → 之后永远不再尝试 —— 就是死胡同：
     * 用户的预装插件一直缺着，界面上不会有任何提示，日志里连一行都不会有
     * （账本提前返回，预装流程整个被跳过）。这里改成跟着**运行时版本**自动复位：
     * 换运行时等于换了环境，旧失败的原因（网络、pnpm 拦构建脚本、dsh 版本不兼容、
     * profile 被重建）多半已经不成立。
     *
     * 配额 [SEED_MAX_PASSES] 防止「根因没修好 → 每次开机都重跑一遍 pnpm」：
     * 只有真的重试过才计数，用尽后要等运行时版本变化才重新获得机会。
     */
    private fun applySeedEnvRetry(
        p: android.content.SharedPreferences,
        attempted: MutableSet<String>,
        installed: Set<String>,
        shadowed: Set<String>,
    ) {
        val runtimeNow = p.getString(DshEnv.KEY_RUNTIME_VERSION, "").orEmpty()
        val lastRuntime = p.getString(DshEnv.KEY_SEED_RUNTIME, null)
        val passes = p.getInt(DshEnv.KEY_SEED_PASSES, 0)
        // 版本没变且配额已用尽：不再重试（否则每次冷启动都要多等一轮 pnpm）
        if (lastRuntime == runtimeNow && passes >= SEED_MAX_PASSES) return
        // 同 applySeedRepair：上游已内置的不算「没生效」，不重试
        val retry = attempted.filter { it in SEED_PLUGINS && it !in installed && it !in shadowed }
        if (retry.isEmpty()) {
            // 没有要补的：只记下「这个版本已经检查过」，不消耗配额
            if (lastRuntime != runtimeNow) {
                p.edit().putString(DshEnv.KEY_SEED_RUNTIME, runtimeNow).apply()
            }
            return
        }
        attempted.removeAll(retry.toSet())
        persistSeeded(attempted)
        logInfo(R.string.dsh_log_seed_retry_missing, joinForLog(retry))
        p.edit()
            .putString(DshEnv.KEY_SEED_RUNTIME, runtimeNow)
            .putInt(DshEnv.KEY_SEED_PASSES, if (lastRuntime == runtimeNow) passes + 1 else 1)
            .apply()
    }

    /**
     * 启动并等待就绪；插件树冲突可自动修复时先卸载冲突包再重试一次，然后才是
     * proroot → proot 回退（只重试一次：proot 也起不来就是真错了，再试无意义）。
     */
    private suspend fun startAndAwait() {
        prorootFellBack = false
        startServer()
        awaitReady()
        // 上游内置的 entry id 与预装三方包重名（0.1.5 的 file-upload）会让整棵插件树
        // 起不来。自动卸载冲突的预装包后重试一次 —— 不修的话用户只能看到一行
        // duplicate loader entry id 的 node 堆栈，完全不知道下一步该干什么。
        // 两个自愈各查各的：入口 id 撞车与「声明残留」是两种不同的坏状态，
        // 同时存在时要一次都修掉，否则用户要重启两轮。
        var repaired = false
        if (repairDuplicateLoaderEntry()) repaired = true
        if (repairUnresolvableBundles()) repaired = true
        if (_state.value.phase == DshPhase.ERROR && repaired) {
            logInfo(R.string.dsh_log_dup_entry_retry)
            stopServer()
            delay(500)
            startServer()
            awaitReady()
        }
        if (!prorootFellBack) return
        prorootFellBack = false
        logInfo(R.string.dsh_log_retry_with_proot)
        stopServer()
        delay(500)
        startServer()
        awaitReady()
    }

    /** 预装流程管得着的包：在装的 + 退役的（退役包也要能卸、能清声明）。 */
    private fun managedSeedPackages(): Set<String> =
        (SEED_PLUGINS + RETIRED_SEED_PLUGINS.keys).toSet()

    /**
     * 这个运行时是否已经自带退役包提供的能力。
     *
     * 运行时版本形如 `0.1.5-rc.1-ubuntunoble-r4`，前缀就是它捆绑的 dsh 版本。
     * 解析不出来一律当「不支持」：宁可让退役包多留一会儿，也不要在老运行时上
     * 把用户还能用的能力卸掉。
     */
    private fun runtimeSupportsRetiredSeed(runtimeVersion: String): Boolean {
        val core = runtimeVersion.trim().removePrefix("v").substringBefore('-')
        if (core.isEmpty() || core.substringBefore('.').toIntOrNull() == null) return false
        return compareVersions(core, RETIRE_MIN_DSH_VERSION) >= 0
    }

    /**
     * 尝试自愈「profile 声明了 bundles，但包已经不在」导致的服务起不来。
     *
     * 这种残留 `dsh plugin` 自己修不了（reconcile 要求这个包**当时**还在 dependencies 里），
     * 所以由 App 摘掉声明 —— 与 duplicate entry id 一样，用户从日志里看不出下一步该干什么，
     * 只会看到「服务进程已退出」。只动预装清单里的包；如果是用户自装的插件缺了包，
     * 就只说清是哪个、让他去插件页重装，绝不擅自改用户的东西。
     */
    private suspend fun repairUnresolvableBundles(): Boolean {
        val tail = runCatching {
            LogStore.named(DshEnv.serverLog(appContext)).tail(400)
        }.getOrDefault("")
        val names = PROFILE_BUNDLE_RE.findAll(tail)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (names.isEmpty()) return false
        val seedNames = names.filter { it in SEED_PLUGINS }.toSet()
        if (seedNames.isEmpty()) {
            logWarn(R.string.dsh_log_bundle_unresolvable_user, joinForLog(names))
            return false
        }
        val pruned = runCatching { DshPluginRepo.pruneUnresolvableBundles(seedNames) }
            .getOrElse { emptySet() }
        if (pruned.isEmpty()) return false
        logWarn(R.string.dsh_log_bundle_repair, joinForLog(pruned))
        return true
    }

    /**
     * 尝试自愈「上游内置的 entry id 与预装三方包重名」导致的插件树加载失败。
     *
     * 判据是启动日志里的 `duplicate loader entry id: X`。只卸载**预装清单里**声明了
     * 该 id 的包（核心 `@deepseek-ai/` 作用域的包与用户自装的包绝不自动动 —— 冲突时保留
     * 上游内置，因为旧 App 没有它对应的适配逻辑）。返回是否执行了卸载，调用方据此
     * 决定是否重试一次启动。已修复过的不重复修（同一轮启动只重试一次）。
     */
    private suspend fun repairDuplicateLoaderEntry(): Boolean {
        val tail = runCatching {
            LogStore.named(DshEnv.serverLog(appContext)).tail(400)
        }.getOrDefault("")
        val id = DUP_ENTRY_RE.find(tail)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            ?: return false
        val entries = runCatching { DshPluginRepo.pluginEntries() }.getOrElse { emptyMap() }
        val managed = managedSeedPackages()
        val culprits = entries.entries
            .filter { (_, ids) -> id in ids }
            .map { it.key }
            .filter { it in managed }
            // 探测不到 entry id 时的兜底：这条 id 属于某个退役包，就直接认它 ——
            // 上游已内置该能力，卸掉退役包是唯一正确的处置。
            .ifEmpty { RETIRED_SEED_PLUGINS.filter { (_, retiredId) -> retiredId == id }.keys.toList() }
        if (culprits.isEmpty()) return false
        for (pkg in culprits) {
            logWarn(R.string.dsh_log_dup_entry_repair, pkg, id)
            DshPluginRepo.uninstall(pkg, onLine = { line -> appendLog(line) })
        }
        // 必须落盘：映射表（[RETIRED_SEED_PLUGINS]）没覆盖的冲突只有这里才知道，不记下来的话
        // 下次冷启动会把它们当「缺的预装包」重新装上，再失败一次 —— 也就是刚才那个循环。
        rememberShadowed(culprits)
        return true
    }

    /** 下载/解压/启动中：不接受新的引导请求。 */
    private fun busy(): Boolean = _state.value.phase.let {
        it == DshPhase.DOWNLOADING || it == DshPhase.EXTRACTING || it == DshPhase.STARTING
    }

    /**
     * 启动前探测端口占用。
     *
     * 本服务自己的进程在跑（`serverProcess?.isAlive`）不算冲突 —— 那是正常监听，
     * `startServer` 自会识别并跳过。只在「我们没在跑、端口却连得上」时置 [DshState.portConflict]
     * 并中止本轮启动，等 [resolvePortConflict] 决定。
     *
     * @return true 表示已置冲突标记、应当中止本轮启动
     */
    private fun checkPortConflict(): Boolean {
        if (serverProcess?.isAlive == true) return false
        if (!isPortInUse(port())) return false
        _state.update { it.copy(portConflict = true) }
        logWarn(R.string.dsh_log_port_busy, port())
        return true
    }

    // ────────────────────────── 文件桥 ──────────────────────────

    /** 取（或首先生成）文件桥 token。 */
    private fun ensureFsToken(): String {
        val p = prefs()
        p.getString(DshEnv.KEY_FS_TOKEN, null)?.takeIf { it.isNotEmpty() }?.let { return it }
        val t = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            .joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
        p.edit().putString(DshEnv.KEY_FS_TOKEN, t).apply()
        return t
    }

    /** 把端口 + token 写进容器内配置文件，供 `dsh-fs` 读。 */
    private fun writeFsBridgeConfig(port: Int, token: String) {
        runCatching {
            DshEnv.fsBridgeConfig(appContext).parentFile?.mkdirs()
            DshEnv.fsBridgeConfig(appContext).writeText(
                "{\"port\":$port,\"token\":\"$token\"}",
                StandardCharsets.UTF_8,
            )
        }
    }

    /**
     * 运行时里有没有 pnpm。
     *
     * **只判定，不修 rootfs。** pnpm 是运行时的组成部分：runtime-builder 固定
     * `pnpm@10.34.5`、按 `package.json.bin` 重建 `usr/local/bin` 下的链接，并在打包前
     * 用 `node bin/pnpm.cjs --version` 自检。App 侧再打补丁只会把「运行时该负责的事」
     * 藏起来 —— 真机日志里表现就是每次进容器都刷一行「已修复此运行时的 pnpm」，
     * 而用户根本不知道自己的运行时其实是坏的。
     *
     * 判据只看 `usr/local/bin/pnpm` 在不在：执行位与入口正确性由运行时构建期自检保证，
     * 容器内 `dsh plugin` 前还有一次 `command -v pnpm` 兜底（见 DshPluginRepo）。
     */
    private fun pnpmInstalled(): Boolean =
        File(DshEnv.rootfs(appContext), "usr/local/bin/pnpm").exists()

    /**
     * 把 `dsh-fs` / `dsh-native` CLI 写进容器（rootfs 就在 App 私有目录，直接落盘，
     * 不必 execRootfs heredoc）。
     *
     * **每次启动都会调用**（见 [startServer] 里的调用点），所以必须按内容比对再写。
     *
     * 以前它只在引导路径（bootstrap / reinstall）调用，那留下了一个很安静的缺口：用户升级
     * App 但沿用同一套 rootfs 时，容器里的 CLI 永远是上一次引导时写下的那份。加一个新子命令
     * 后主机提示词会立刻开始向 agent 宣传它，而容器里根本没有 —— agent 拿到的是
     * "command not found"，翻遍设置页也找不到原因。按内容比对再写，成本只是读两个小文件。
     *
     * 两个脚本都读同一份 fs-bridge.json，所以不需要各自的配置或 ROOTFS_REV 变更。
     * （`dsh-saf` 是第三个，同样读那一份、同一 token、同一 port，只是打到 `/saf/…`。）
     */
    private fun ensureFsBridgeCli() {
        if (!DshEnv.isRuntimeInstalled(appContext)) return
        val bin = File(DshEnv.rootfs(appContext), "usr/local/bin")
        for ((name, script) in listOf(
            "dsh-fs" to FS_BRIDGE_CLI_SCRIPT,
            "dsh-native" to NATIVE_CLI_SCRIPT,
            "dsh-saf" to SAF_BRIDGE_CLI_SCRIPT,
            "dsh-workflow" to WORKFLOW_CLI_SCRIPT,
        )) {
            runCatching {
                DshRuntimeFiles.writeScript(File(bin, name), script)
            }.onFailure { android.util.Log.w(TAG, "写 $name 失败: ${it.message}") }
        }
        // 权限桥疑难解答：agent 报错时用 `dsh-native troubleshoot` 读它。和上面的脚本一样
        // 按内容比对再写 —— 换应用语言后这份清单必须跟着变，否则 agent 拿到的是上一门语言的步骤。
        runCatching {
            DshRuntimeFiles.writeIfChanged(
                File(DshEnv.dshHome(appContext), TROUBLESHOOT_DOC_NAME),
                nativeTroubleshootDoc(),
            )
        }.onFailure { android.util.Log.w(TAG, "写 $TROUBLESHOOT_DOC_NAME 失败: ${it.message}") }
        // 工作流 DSL 说明：容器里的 `dsh-workflow` 与工具插件的提示词段都读它，写在这儿是为了
        // 「实现改了、文档跟着改」——否则 agent 会照着一份过时说明写定义，然后在校验器那里撞墙。
        // 样例定义：agent 抄一份改，比从零写更不容易漏字段；设置页也拿它当新建模板。
        runCatching {
            val dir = File(DshEnv.dshHome(appContext), WORKFLOW_SAMPLES_DIR)
            dir.mkdirs()
            for ((sampleName, sampleText) in WORKFLOW_SAMPLES) {
                DshRuntimeFiles.writeIfChanged(File(dir, sampleName + ".json"), sampleText)
            }
        }.onFailure { android.util.Log.w(TAG, "写工作流样例失败: ${it.message}") }
        runCatching {
            DshRuntimeFiles.writeIfChanged(
                File(DshEnv.dshHome(appContext), WORKFLOW_DSL_DOC_NAME),
                WORKFLOW_DSL_DOC,
            )
        }.onFailure { android.util.Log.w(TAG, "写 $WORKFLOW_DSL_DOC_NAME 失败: ${it.message}") }
    }

    /**
     * 把容器侧的 CLI / 工作流指南 / 样例定义按当前版本重写一遍。
     *
     * 为什么需要单独一个入口：这些文件平时只在**容器启动**时对一遍（[ensureFsBridgeCli]），
     * 而用户完全可能「升了 App、容器一直没重启」—— 那时的容器里没有 dsh-workflow、没有指南、
     * 没有样例，agent 就会卡在「不知道该怎么用、格式是什么」上（实测就是这样）。设置页打开
     * 或打开工作流开关时调一次，把这段窗口补上；工具插件要重启 DSH 才生效，这一条如实写在
     * 设置页上。
     */
    fun refreshContainerCli() {
        runCatching { ensureFsBridgeCli() }
            .onFailure { android.util.Log.w(TAG, "刷新容器侧 CLI 失败: ${it.message}") }
        // 插件与 cordis 挂载项也要一起补：agent 看到工作流的**唯一**通道就是这两个插件
        // （工具描述 + 提示词段）。只补 CLI 与指南的话，升级后没重启过容器的设备上，agent
        // 依然什么都不知道 —— 这正是实测里「折腾半天不知道怎么用」的成因。
        runCatching { DshHostPrompt.ensureInstalled(appContext) }
            .onFailure { android.util.Log.w(TAG, "刷新容器侧插件失败: ${it.message}") }
    }

    /** 启动回环桥（选空闲端口 + 写配置 + 监听）。文件端点与原生端点共用它。 */
    private fun startFsBridge() {
        runCatching {
            val fsPort = findFreePort(DshFsBridge.PORT_BASE)
            val fsToken = ensureFsToken()
            writeFsBridgeConfig(fsPort, fsToken)
            DshFsBridge.start(fsPort, fsToken, appContext)
            logInfo(R.string.dsh_log_bridge_up)
        }.onFailure { logWarn(R.string.dsh_log_bridge_failed, it.message ?: it.javaClass.simpleName) }
    }

    /** 强制重启 web 服务。 */
    fun restart() {
        scope.launch {
            bootMutex.withLock {
                stopServer()
                delay(500)
                if (checkPortConflict()) return@withLock
                startAndAwait()
            }
        }
    }

    /**
     * 在**服务已停止**的状态下跑一段活，然后无论成败都把服务起回来。
     *
     * 为什么要停（准确一点说：**更稳，而不是唯一可行**）：工作区注册表是 storage-json ——
     * 启动时读一次盘进内存，此后内存是权威，平时不重读盘；而任意一次 workspace 域写
     * （建/删工作区、调顺序、归档、挂接会话）会把**整份内存状态**原子覆盖回盘。
     *
     * 由此可以推出：运行中改文件是**可行的**，只要改完到重启之间没有发生过任何 workspace
     * 域写 —— 重启后照样会被加载（真机上验证过）。风险不是写坏文件，而是**静默丢失**：
     * 中间若有那么一次 workspace 写，旧内存状态会把改动整份盖掉，等于白改。
     * 停止服务只是把这个风险归零。而这一步之后用户往往还会继续在 GUI 里动工作区
     * （导入完还要重启才生效），所以宁可多停几秒也不要「改完看着成功、重启后一切照旧」。
     *
     * 与 [restart] 同样走 bootMutex：两条路径并发会把容器搅成两份 dsh 在抢端口。
     * [block] 抛异常也必须恢复服务：用户点的是一次「导入」，不该因为归组失败就得到
     * 一个停着的服务。
     */
    suspend fun <T> withServiceStopped(block: suspend () -> T): T? = bootMutex.withLock {
        stopServer()
        try {
            block()
        } finally {
            // 端口被占说明容器里已经有一份在跑：保持现状，别在别人的端口上再起一份
            if (!checkPortConflict()) startAndAwait()
        }
    }

    /** 重新下载并安装运行时。 */
    /**
     * 「更新」：按**通道候选**取最新的一份（测试通道 = 正式 + 测试里更新者）。
     *
     * 与 [reinstallRuntime] 分开是必须的：两者以前共用同一条路（UI 上「更新」确认框调的就是
     * 重装那个回调），于是「测试通道检测到正式版更新」在按下更新后仍然去下当前通道那份 ——
     * 真机日志里就是这样：检测到新版，下载 URL 还是 runtime-beta-latest。
     */
    fun updateRuntime(preserveData: Boolean = true) {
        scope.launch {
            bootMutex.withLock {
                stopServer()
                clearSeedLedger()
                downloadAndInstall(preserveData, newest = true)
                if (_state.value.phase != DshPhase.ERROR) {
                    setupResolvConf()
                    seedPlugins()
                    ensureFsBridgeCli()
                    if (checkPortConflict()) return@withLock
                    startAndAwait()
                }
            }
        }
    }

    fun reinstallRuntime(preserveData: Boolean = true) {
        scope.launch {
            bootMutex.withLock {
                stopServer()
                // 重装等于换了一套全新 rootfs，容器里的插件确实没了，该重新预装
                clearSeedLedger()
                // 重装 = 装回已装那一份，不跟着通道走（否则「重装」会变成静默升级）
                downloadAndInstall(preserveData, newest = false)
                if (_state.value.phase != DshPhase.ERROR) {
                    setupResolvConf()
                    seedPlugins()
                    ensureFsBridgeCli()
                    if (checkPortConflict()) return@withLock
                    startAndAwait()
                }
            }
        }
    }

    /**
     * 安装版本列表里选中的那一份运行时（升级 / 降级 / 换通道都走这里）。
     *
     * 与 [reinstallRuntime] 的唯一区别是 metadata 从哪来：这里用用户选中的那个
     * release，而不是当前通道的最新版 —— 否则「切到 0.1.1-rc.2」会变成「又装了一遍
     * 最新版」。其余流程完全一致：先停服务，装完重新预装插件并拉起服务。
     */
    fun switchRuntimeVersion(entry: RuntimeVersion, preserveData: Boolean = true) {
        scope.launch {
            bootMutex.withLock {
                clearLog()
                logInfo(R.string.dsh_log_switch_runtime, entry.version, entry.tag)
                stopServer()
                clearSeedLedger()
                _state.update {
                    it.copy(
                        phase = DshPhase.DOWNLOADING,
                        progress = 0f,
                        speedBytesPerSec = 0L,
                        message = str(R.string.dsh_msg_downloading, entry.version),
                    )
                }
                installMeta(entry.toMeta(), preserveData)
                if (_state.value.phase != DshPhase.ERROR) {
                    setupResolvConf()
                    seedPlugins()
                    ensureFsBridgeCli()
                    if (checkPortConflict()) return@withLock
                    startAndAwait()
                }
            }
        }
    }

    /**
     * 换 rootfs = 容器里的预装插件没了：清掉预装记账，让下一轮 [seedPlugins] 重新来一遍。
     */
    private fun clearSeedLedger() {
        prefs().edit()
            .remove(DshEnv.KEY_SEEDED_PLUGINS)
            .remove(@Suppress("DEPRECATION") DshEnv.KEY_SEED_PLUGINS_DONE)
            .apply()
    }

    /**
     * App 启动后是否自动检查运行时更新（设置里「自动检查更新」那个独立开关）。
     *
     * 默认**开**：不看版本的存量用户只有靠启动提示才知道自己卡在一份坏的 rootfs 上。
     */
    fun autoCheckEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)
            .getBoolean(DshEnv.KEY_RUNTIME_AUTO_CHECK, true)

    fun setAutoCheckEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(DshEnv.KEY_RUNTIME_AUTO_CHECK, on)
            .apply()
    }

    fun importRuntime(tarball: File, preserveData: Boolean = true) {
        scope.launch {
            bootMutex.withLock {
                stopServer()
                clearLog()
                _state.update { it.copy(phase = DshPhase.EXTRACTING, message = str(R.string.dsh_msg_installing)) }
                val ok = withContext(Dispatchers.IO) { installRuntimeArchive(tarball, preserveData) }
                tarball.delete()
                if (!ok) {
                    fail(str(R.string.dsh_err_extract_failed))
                    return@withLock
                }
                prefs().edit()
                    .remove(DshEnv.KEY_SEEDED_PLUGINS)
                    .remove(@Suppress("DEPRECATION") DshEnv.KEY_SEED_PLUGINS_DONE)
                    .putString(DshEnv.KEY_RUNTIME_VERSION, "imported")
                    .apply()
                _state.update {
                    it.copy(
                        phase = DshPhase.NOT_READY,
                        installed = true,
                        runtimeVersion = "imported",
                        progress = 1f,
                        message = str(R.string.dsh_msg_runtime_ready),
                    )
                }
                refreshRootfsSize()
                setupResolvConf()
                seedPlugins()
                ensureFsBridgeCli()
                if (!checkPortConflict()) startAndAwait()
            }
        }
    }

    /**
     * 运行时通道当前生效的下载源。
     *
     * 竞速**关掉**（总开关或「运行时」分开关）时不再测速：用户若在「下载渠道」里明确指定过某个源，
     * 就按他指定的走（那是手选、不是竞速）；只有 `auto`（= 交给竞速决定）才退化为直连 GitHub。
     */
    override fun runtimeSource(ctx: Context): String {
        val setting = DshSource.setting(ctx)
        if (!raceEnabled(RACE_RUNTIME)) {
            return if (setting == DshSource.SOURCE_AUTO) DshSource.SOURCE_GITHUB else setting
        }
        return DshSource.resolve(ctx)
    }

    /** 当前该拉的 metadata.json 地址（自定义源仍走它自己的 URL）。 */
    internal fun runtimeMetaUrl(): String {
        val src = runtimeSource(appContext)
        if (src == DshSource.SOURCE_CUSTOM) return DshSource.effectiveMetaUrl(appContext)
        return DshSource.proxyPrefix(src) + DshSource.metaUrl()
    }

    /**
     * 拉一份 metadata.json 并解析。失败（断网 / 404 / JSON 坏了）返回 null。
     *
     * 取文本走 [fetchJsonText]（直连优先，其次各代理前缀）：版本列表里每个 release 的
     * metadata 都是固定的 GitHub 下载地址，用不上测速排序那一套。
     */
    internal fun fetchMetaFrom(url: String): DshMeta? {
        val text = fetchJsonText(url) ?: return null
        return runCatching {
            val json = JSONObject(text)
            DshMeta(
                version = json.optString("version", "unknown"),
                url = json.getString("url"),
                sha256 = json.optString("sha256", ""),
                sizeBytes = json.optLong("sizeBytes", 0L),
                mirrors = json.optJSONArray("mirrors")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                    ?: emptyList(),
                arch = json.optString("arch", ""),
                dsh = json.optString("dsh", ""),
                nodeVersion = json.optString("nodeVersion", ""),
                builtAt = json.optString("builtAt", ""),
                minAppVersion = json.optString("minAppVersion", ""),
                // 构建脚本一直就写着这一项（runtime-builder/build-rootfs.sh 的 "flavor"），
                // 只是以前不解析 —— 版本列表要按「精简/完整」筛选就得靠它：历史 release 的
                // tag 里没有 flavor，光看 tag 分不出来。
                flavor = json.optString("flavor", ""),
            )
        }.getOrNull()
    }

    /** 某个 release tag 在**本机架构**下的 metadata。 */
    internal fun fetchMetaOfTag(tag: String, suffix: String): DshMeta? =
        fetchMetaFrom(DshSource.releaseBase(tag) + "metadata$suffix.json")

    /**
     * 拉一段文本：直连不行就套代理前缀再试。
     *
     * 这几个 metadata 只有几十 KB，代理前缀是白名单里的两个 gh-proxy 域名 ——
     * 与下载 rootfs 用的是同一批入口，国内网络下直连 GitHub 经常直接超时。
     */
    private fun fetchJsonText(url: String): String? {
        val candidates = listOf(
            url,
            DshSource.proxyPrefix(DshSource.SOURCE_GHPROXY_CF) + url,
            DshSource.proxyPrefix(DshSource.SOURCE_GHPROXY_AXISNOW) + url,
        ).distinct()
        for (candidate in candidates) {
            val text = runCatching {
                val conn = URL(candidate).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "DSH-Folk")
                if (conn.responseCode !in 200..299) {
                    conn.disconnect()
                    return@runCatching null
                }
                conn.inputStream.bufferedReader().use { it.readText() }
            }.getOrNull()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    /**
     * 仓库里所有 runtime release 的 (tag, 发布时间)，按发布时间倒序。
     *
     * 用 API 而不是通道地址：只有 API 能一次列出历史版本。拿不到就返回空表，由
     * [listRuntimeVersions] 退回两个通道。
     */
    private suspend fun fetchRuntimeReleases(): List<Pair<String, String>> {
        val body = UpdateChecker.fetchApiJson("/repos/IPF-Sinon/DSH-Folk/releases?per_page=100")
            ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        runCatching {
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optBoolean("draft")) continue
                val tag = o.optString("tag_name").trim()
                // 仓库里还有 App 自己的 release（v1.8.4…）：只认运行时那批 tag
                if (!tag.startsWith("runtime")) continue
                out.add(tag to o.optString("published_at").trim())
            }
        }.onFailure { logWarn(R.string.dsh_log_version_list_failed) }
        return out.sortedByDescending { it.second }
    }

    /**
     * 有没有更新的运行时可用。
     *
     * 判据是版本串**不相等**，不是语义比较：`0.1.1-rc.2-ubuntunoble-r2` 里混了
     * dsh 版本、ubuntu 代号和 rootfs 修订号，semver 比较对它没有意义；而任何一段
     * 变了都值得重装。r2 就是这么来的 —— 内容修了但 dsh 版本没动。
     *
     * @return 远端有不同版本时带 [RuntimeCheckResult.version]；若那份新运行时还要求
     * 更高的 App 版本，[RuntimeCheckResult.minAppVersion] 一并带上（UI 据此改提示
     * 「先更新应用」而不是给「更新运行时」按钮）。查不到 / 已最新 = 空结果。
     */
    suspend fun checkRuntimeUpdate(): RuntimeCheckResult = withContext(Dispatchers.IO) {
        if (!DshEnv.isRuntimeInstalled(appContext)) return@withContext RuntimeCheckResult()
        val meta = fetchNewestMeta() ?: return@withContext RuntimeCheckResult(failure = true)
        val local = prefs().getString(DshEnv.KEY_RUNTIME_VERSION, null).orEmpty()
        // 本地版本未知（早期版本装的，没记过）时不谎报有更新：重装要重下 150MB，
        // 不能靠猜就让用户付这个代价；也没法说「已是最新」——那同样是猜的
        if (local.isEmpty()) return@withContext RuntimeCheckResult(failure = true)
        if (meta.version == local) RuntimeCheckResult()
        else RuntimeCheckResult(
            version = meta.version,
            minAppVersion = if (appSatisfies(meta.minAppVersion)) "" else meta.minAppVersion,
        )
    }

    /**
     * 列出仓库里**所有**可用的运行时版本（长按「更新」时用）。
     *
     * 光靠通道 tag 拿不到历史版本 —— 滚动通道永远只有那四个位置
     * （`runtime-latest` / `runtime-slim-latest` / `runtime-beta-latest` /
     * `runtime-slim-beta-latest`），它们的内容会被就地覆盖，所以「回到上一版」
     * 只能靠带版本号的历史 release（`runtime-0.1.1-rc.2` 这种）。
     *
     * 数据来源是 Releases API（列 tag + 发布时间）加上每个 release 的 metadata
     * （版本、dsh、node、体积、sha256、最低 App 版本都在里面）。API 走
     * [UpdateChecker.fetchApiJson]，复用那里已经验证过的一批入口：直连被限流时
     * gh-proxy 能代理 api.github.com。API 整个不可达时退回两个通道各一份，至少让
     * 用户在正式版与测试版之间切换。
     *
     * 结果按「正式通道 → 测试通道 → 历史版本」排，同组内新的在前。
     */
    suspend fun listRuntimeVersions(): List<RuntimeVersion> = withContext(Dispatchers.IO) {
        val want = DshSource.runtimeArch()
        val suffix = if (want == "arm64-v8a") "" else "-x86_64"
        // version 去重：正式通道与测试通道可能装着同一个版本串，先到的那份通道权重更高
        val found = LinkedHashMap<String, RuntimeVersion>()

        val releases = fetchRuntimeReleases()
        if (releases.isNotEmpty()) {
            logInfo(R.string.dsh_log_version_list_api, releases.size)
            // 并发拉各 release 的 metadata：串行的话 6 个版本在慢网络上要等十几秒
            val metas = coroutineScope {
                releases.map { (tag, published) ->
                    async { fetchMetaOfTag(tag, suffix)?.let { Triple(tag, published, it) } }
                }.mapNotNull { it.await() }
            }
            for ((tag, published, meta) in metas) {
                if (meta.arch.isNotEmpty() && meta.arch != want) continue
                found.putIfAbsent(
                    meta.version,
                    RuntimeVersion(
                        version = meta.version,
                        tag = tag,
                        channel = RuntimeVersion.channelOf(tag),
                        dsh = meta.dsh,
                        nodeVersion = meta.nodeVersion,
                        builtAt = meta.builtAt,
                        minAppVersion = meta.minAppVersion,
                        sha256 = meta.sha256,
                        sizeBytes = meta.sizeBytes,
                        arch = meta.arch,
                        url = meta.url,
                        mirrors = meta.mirrors,
                        publishedAt = published,
                        flavor = meta.flavor,
                    ),
                )
            }
        }

        // 通道兜底：API 不可达 / 限流烧完时，至少给人看当前四个通道
        if (found.isEmpty()) {
            logWarn(R.string.dsh_log_version_list_fallback)
            for (tag in listOf(
                "runtime-latest",
                "runtime-slim-latest",
                "runtime-beta-latest",
                "runtime-slim-beta-latest",
            )) {
                val meta = fetchMetaFrom(DshSource.releaseBase(tag) + "metadata$suffix.json") ?: continue
                found[meta.version] = RuntimeVersion(
                    version = meta.version,
                    tag = tag,
                    channel = RuntimeVersion.channelOf(tag),
                    dsh = meta.dsh,
                    nodeVersion = meta.nodeVersion,
                    builtAt = meta.builtAt,
                    minAppVersion = meta.minAppVersion,
                    sha256 = meta.sha256,
                    sizeBytes = meta.sizeBytes,
                    arch = meta.arch,
                    url = meta.url,
                    mirrors = meta.mirrors,
                    flavor = meta.flavor,
                )
            }
        }

        found.values.sortedWith(
            compareBy<RuntimeVersion> { RuntimeVersion.channelRank(it.channel) }
                .thenByDescending { it.publishedAt }
                .thenByDescending { it.version }
        )
    }

    /** 写容器 DNS（国内解析优先，谷歌/CF 兜底）。 */
    /**
     * 写入容器的 DNS 与 hosts。
     *
     * Android 不暴露 /etc/resolv.conf（DNS 走 netd 的 binder 接口），容器里的 glibc
     * 只会读文件，所以必须自己写一份，否则容器内所有域名解析都失败 —— 表现是
     * npm/pnpm/apt 全部 EAI_AGAIN。base rootfs 里的 resolv.conf 与 hosts 都是 0 字节。
     *
     * localhost 也得手动补：dsh web 绑 127.0.0.1，容器内插件回连 http://localhost:3080
     * 时没有这一行就解析不出来。
     */
    // 实现搬到同包 DshRuntimeEnv（只接 ctx 的纯落地件）；这里保留同名私有壳，调用点零改动。
    private fun setupResolvConf() = DshRuntimeEnv.setupResolvConf(appContext)

    /** 缺失或空文件才写（不覆盖用户自己改过的内容）。 */
    private fun ensureContainerDns() = DshRuntimeEnv.ensureDns(appContext)

    /** 落地件的日志桥：按接口把日志口子交出去，辅助文件不需要认识整个单例。 */
    private val logBridge = object : DshRuntimeLog {
        override fun info(resId: Int, vararg args: Any) = logInfo(resId, *args)
        override fun warn(resId: Int, vararg args: Any) = logWarn(resId, *args)
    }

    // 实现在 DshRuntimeEnv（只接 ctx + 依赖闭包）。调用点零改动。
    private fun ensureContainerGroups() = DshRuntimeEnv.ensureContainerGroups(appContext, logBridge)

    /** 公开入口保持原签名（DshPluginRepo 会调）。 */
    fun allowProfileBuilds(packages: List<String>, onLine: (String) -> Unit = {}): List<String> =
        DshRuntimeEnv.allowProfileBuilds(appContext, packages, onLine)

    /**
     * 让容器内的 pnpm 用「复制」而不是「硬链接」从内容存储装包。
     *
     * 为什么必须这么做：pnpm 用 `link()` 把 CAS 里的文件装进 node_modules，而
     * proot/proroot 的 `--link2symlink` 会把它改写成符号链接（见 [linkBecomesSymlink]）。
     * Node 的 `require.resolve` 默认 realpath，于是
     * `node_modules/<pkg>/package.json` 解析成 `<store>/files/<xx>/<hash>`，
     * dsh 再按 `join(dirname(pkgPath), "./lib/client.cjs")` 拼客户端包路径，
     * 得到 `<store>/files/<xx>/lib/client.cjs` —— CAS 里只有扁平的哈希文件，
     * 没有 lib/ 目录，必然 ENOENT。真机表现是任何带 `exports["./client"]` 的插件
     * 装完就让 `dsh web` 起不来（MissingClientBundleError）。
     *
     * 选 copy 而不是设法让硬链接可用：真机探测报的是
     * `AccessDeniedException`（系统不允许在 App 私有目录建硬链接），不在 App 能改的
     * 范围内。copy 绕开整个链接语义，代价是 CAS 去重失效、rootfs 变大。
     *
     * **写 pnpm-workspace.yaml 而不是 .npmrc**：pnpm 11 起这类 pnpm 专有设置已从
     * `.npmrc` 迁到 `pnpm-workspace.yaml`。实测 `pnpm config get package-import-method`
     * 对 .npmrc 里的同名项返回 undefined，而 workspace 里的 `packageImportMethod`
     * 返回 copy（.npmrc 本身没失效 —— 同文件里的 registry= 照样生效）。v1.3 写
     * .npmrc 那一版因此完全没生效。
     *
     * **只在文件已存在时追加**：dsh 的 `initProfile` 见到文件存在就不写自己的模板
     * （dsh-app-boot 里 `if (!existsSync(workspacePath))`），抢先创建会把
     * `nodeLinker: hoisted` 与 `autoInstallPeers: false` 弄丢。profile 尚未初始化时
     * 靠安装命令上的 `--package-import-method copy` 兜住那一次。
     *
     * 用户显式写过这一项时**不覆盖** —— 显式配置优先于我们的推断。
     */
    private fun ensureProfilePnpmSettings() {
        runCatching { removeLegacyNpmrcImportLine() }
        if (!linkBecomesSymlink()) return
        runCatching {
            val ws = File(DshEnv.rootfs(appContext), "$PROFILE_GUEST_REL/pnpm-workspace.yaml")
            // 不存在就不建：见 KDoc，抢在 dsh initProfile 之前会弄丢它的模板
            if (!ws.isFile) return@runCatching
            val old = ws.readText(StandardCharsets.UTF_8)
            if (old.lineSequence().any { it.trimStart().startsWith(PNPM_IMPORT_KEY) }) return@runCatching
            val sep = if (old.isEmpty() || old.endsWith("\n")) "" else "\n"
            // 追加顶层键而不是解析重写整个 YAML：这文件里还有 minimumReleaseAgeExclude、
            // allowBuilds 等结构化内容，字符串追加最不容易弄坏别人的配置
            ws.writeText(old + sep + PNPM_IMPORT_LINE + "\n", StandardCharsets.UTF_8)
            android.util.Log.i(TAG, "已写入 profile pnpm 设置: $PNPM_IMPORT_LINE")
        }
    }

    /**
     * 清掉 v1.3 写进 `/root/.npmrc` 的那行 —— 它已知无效（pnpm 11 不读），
     * 留着只会在排查时误导。只删精确匹配的那一行，用户自己写的其它行一律不碰；
     * 文件因此变空就把文件删掉。
     */
    private fun removeLegacyNpmrcImportLine() {
        val rc = File(DshEnv.rootfs(appContext), "root/.npmrc")
        if (!rc.isFile) return
        val old = rc.readText(StandardCharsets.UTF_8)
        if (!old.contains(NPMRC_LEGACY_LINE)) return
        val kept = old.lineSequence().filter { it.trim() != NPMRC_LEGACY_LINE }.toList()
        if (kept.all { it.isBlank() }) {
            rc.delete()
        } else {
            rc.writeText(kept.joinToString("\n").trimEnd() + "\n", StandardCharsets.UTF_8)
        }
        android.util.Log.i(TAG, "已清理无效的 npmrc 行: $NPMRC_LEGACY_LINE")
    }

    /**
     * pnpm 实际读到的导入方式，供启动日志用。
     *
     * 读文件而不是回答「我们写过没有」：v1.3 的教训正是日志宣称了一件没生效的事。
     */
    private fun pnpmImportMethodLine(): String {
        val ws = File(DshEnv.rootfs(appContext), "$PROFILE_GUEST_REL/pnpm-workspace.yaml")
        if (!ws.isFile) return str(R.string.dsh_log_pnpm_unconfigured)
        val line = runCatching {
            ws.readLines().firstOrNull { it.trimStart().startsWith(PNPM_IMPORT_KEY) }
        }.getOrNull()
            ?: return str(R.string.dsh_log_pnpm_default)
        val value = line.substringAfter(':', "").trim().ifEmpty { "?" }
        return str(R.string.dsh_log_pnpm_from_profile, value)
    }

    // ────────────────────────── web 服务 ──────────────────────────

    /**
     * 等到 `dsh web` 真的在跑（或超时）。
     *
     * 自动化工作流的 `trigger` 节点用它：DSH 没起来时先拉起再继续，起不来就走 `false` 分支
     * （用户要的「未启动 DSH 时先阻塞启动 DSH，也可条件判断进入其它流程」）。
     *
     * 判据只看 phase == RUNNING：那是 [startServer] 与端口探测共同判定的结果，比「进程还活着」
     * 更接近事实 —— 进程活着但端口连不上的时候，工作流后面的 `dsh` 命令必然失败。
     */
    suspend fun awaitRunning(timeoutMs: Long): Boolean {
        if (!ready) return false
        if (_state.value.phase == DshPhase.RUNNING) return true
        return withTimeoutOrNull(timeoutMs) {
            state.first { it.phase == DshPhase.RUNNING }
            true
        } ?: false
    }

    /**
     * 启动 `dsh web`。
     *
     * `--expose-internals` 必带：dsh 的 profile-boot 会无条件创建 cordis-plugin-hmr，
     * 那个插件第一行就检查 loader.internal，缺这个标志会把整个启动带崩；
     * 而 NODE_OPTIONS 传不了它（node 明确拒绝），只能作为命令行参数。
     *
     * 默认端口 3080 不显式传 --port，避免 commander 的 'argument missing'。
     * 服务默认只绑 127.0.0.1；开启「局域网访问」后追加 --host 0.0.0.0。
     * 鉴权由 dsh 自己的登录页负责。
     */
    override fun startServer() {
        // 守卫只看真实进程，不看 phase。看 phase 会把上一步（例如安装完成）
        // 自己写下的 STARTING 误当成「已有人在启动」。
        if (serverProcess?.isAlive == true) return
        if (!DshEnv.isRuntimeInstalled(appContext)) {
            _state.update { it.copy(phase = DshPhase.NOT_READY, message = str(R.string.dsh_msg_not_installed)) }
            return
        }
        // 最低 App 版本闸门：运行时要求比当前 App 更高的版本时，直接拦下并指路去
        // 更新应用，而不是 exec 一个注定起不来的 dsh —— 0.1.5 起上游内置的 entry id
        // 会与旧 App 预装的三方插件冲突，而旧 App 既没有预装跳过、也没有自愈逻辑。
        val minApp = _state.value.requiredAppVersion
        if (_state.value.appUpdateRequired && minApp != null) {
            val detail = str(R.string.dsh_runtime_min_app_required, minApp)
            appendLog("! $detail")
            _state.update { it.copy(phase = DshPhase.ERROR, message = detail) }
            return
        }
        DshEnv.dshHome(appContext).mkdirs()
        DshEnv.tmpDir(appContext).mkdirs()
        DshEnv.serverLog(appContext).parentFile?.mkdirs()
        // 每次起服务都过一遍：既刷新宿主事实（用户可能在别处改了能力开关或系统通知
        // 权限），也自愈「patch 行在、插件文件没了」——那种状态下 dsh 会因为一个
        // entry 加载失败而整棵树起不来，而 restart() 不走 bootstrap，否则没人修。
        DshHostPrompt.ensureInstalled(appContext)
        clearLog()
        // dsh 每次启动都会生成新 token，不能沿用上一个进程的认证地址。
        _state.update { it.copy(webToken = null) }

        if (lanEnabled()) patchLanHost()
        // 让外部浏览器（App 用 Intent 拉起的 Chrome）也能一次登进 dsh web：把会话 cookie 的
        // SameSite=Strict 放宽为 Lax（见 [patchBrowserCookieSameSite]）。每次启动前幂等重打。
        patchBrowserCookieSameSite()
        // git 的 CA 配置写在 /root/.gitconfig，更新运行时会把它清掉（PEM 在 /root/.dsh 下还在），
        // 之后 dsh 自身 reconcile / 启动自愈跑 `dsh plugin` 去 https 克隆 github: 插件就会撞
        // 「无 CA」（CAfile: none）。启动前无条件重设，见 DshPluginRepo.ensureGitCaAtStartup。
        DshPluginRepo.ensureGitCaAtStartup()

        val port = port()
        val lan = lanEnabled()
        val opts = buildString {
            if (port != DshEnv.DEFAULT_PORT) append(" --port $port")
            if (lan) append(" --host 0.0.0.0")
            // 不让 dsh 自己拉系统浏览器（我们用 Intent / WebView 打开 WebUI）。**这才是真开关**：
            // dsh web 用 npm 的 `open` 包（Linux 上走 xdg-open），根本不读 BROWSER 环境变量，
            // 所以下面那句 `export BROWSER=true` 从来没拦住过它 —— 只是容器里没有 xdg-open，
            // 尝试后静默失败，看着像被拦住了。`--no-open` 从 0.1.7 起就有（已核对 0.1.7-rc.2 与
            // 0.2.0-rc.2 的 dsh-web-app 选项表逐字一致），两个运行时都能无条件带。
            append(" --no-open")
        }
        val cmd = buildString {
            append("export DSH_HOME=/root/.dsh && ")
            // 兼容性保留：拦住可能读 BROWSER 的更老工具链（对 dsh web 本身无效，见上）。
            append("export BROWSER=true && ")
            append("mkdir -p ${DshEnv.WORKSPACE_GUEST} 2>/dev/null; ")
            append("cd ${DshEnv.WORKSPACE_GUEST} && ")
            append("if command -v dsh >/dev/null 2>&1 && test -f \"\$(command -v dsh)\"; then ")
            // dsh 是 wrapper，先 readlink 出真正的 bin.js 再交给 node（才能带 --expose-internals）
            append("DSH_REAL=\$(readlink -f \"\$(command -v dsh)\" 2>/dev/null || command -v dsh); ")
            append("exec node --expose-internals \"\$DSH_REAL\" web" + opts + " 2>&1; ")
            // 这行在容器里由 shell 输出，会被 forwardOutput 原样转进日志。文案取自
            // 资源（跟随应用语言），单引号要转义成 shell 能接受的形式。
            append("else echo '[DSH-Folk] " + shellSingleQuoted(str(R.string.dsh_log_dsh_missing_in_container)) + "'; exit 1; fi")
        }

        logInfo(R.string.dsh_log_runtime_mode, runtime().displayName())
        // 设备信息（裁剪版）：只留厂商 + 型号 + 系统版本。完整字段在报障包的 basic.txt 里，
        // 但那份得解压、翻文件才看得到；而这份日志是随手复制/导出转发的（设置 → 常规 →
        // 发送日志，或首页那张启动日志卡），开头就得能答出「这是哪台设备」。
        logInfo(
            R.string.dsh_log_device,
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · " +
                "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
        )
        // 架构写进日志：x86_64 + arm 转译设备上「装了哪个包 / 用了哪份 rootfs」是首要排障线索
        logInfo(
            R.string.dsh_log_arch,
            DshSource.runtimeArch(),
            android.os.Build.SUPPORTED_ABIS.joinToString("/"),
        )
        logInfo(R.string.dsh_log_hardlink, hardlinkLogLine())
        logInfo(R.string.dsh_log_pnpm_import, pnpmImportMethodLine())
        logInfo(R.string.dsh_log_starting_web, port)
        if (lan) {
            val ip = lanIp()
            if (ip != null) {
                logInfo(R.string.dsh_log_lan_on, ip, port)
            } else {
                logInfo(R.string.dsh_log_lan_on_no_ip)
            }
        } else {
            logInfo(R.string.dsh_log_lan_off)
        }

        serverProcess = try {
            execRootfs(cmd)
        } catch (e: Exception) {
            // 不能把上一轮已死的进程留在字段里：[awaitReady] 会把它读成
            // 「进程已退出」，盖掉真正的启动失败原因。
            serverProcess = null
            val detail = str(R.string.dsh_err_start_failed, e.message ?: e.javaClass.simpleName)
            appendLog("! $detail")
            if (runtimeId() == "proroot" && noteProrootFailure(detail)) {
                logInfo(R.string.dsh_log_switched_to_proot)
            }
            _state.update { it.copy(phase = DshPhase.ERROR, message = detail) }
            return
        }
        forwardOutput(serverProcess)
        // 每次启动都对一遍 CLI 内容：升级 App 而沿用旧 rootfs 时，容器里的 dsh-fs / dsh-native
        // 必须跟着更新，否则新加的子命令只存在于提示词里（见 ensureFsBridgeCli 的注释）。
        ensureFsBridgeCli()
        startFsBridge()
        startedAt = System.currentTimeMillis()
        _state.update { it.copy(phase = DshPhase.STARTING, port = port, message = str(R.string.dsh_msg_starting)) }
    }

    /** 逐行转发容器输出到日志（node 重定向到文件是块缓冲，不逐行读日志面板会空白）。 */
    private fun forwardOutput(proc: Process?) {
        if (proc == null) return
        val log = LogStore.named(DshEnv.serverLog(appContext))
        scope.launch {
            val reader = proc.inputStream.bufferedReader()
            try {
                for (line in reader.lineSequence()) {
                    log.append(line)
                    // dsh 打印带 token 的 URL 后，把 token 捞出来写进 state，
                    // 这样 webUrl 就能带 ?token=...，WebView 才不会撞认证墙。
                    if (_state.value.webToken == null) {
                        val m = DSH_WEB_TOKEN_RE.find(line)
                        if (m != null) {
                            _state.update { s -> s.copy(webToken = m.groupValues[1]) }
                        }
                    }
                }
            } catch (_: Exception) {
                // 进程被销毁时读流中断，属预期
            } finally {
                log.flushForExit()
                runCatching { reader.close() }
            }
        }
    }

    /**
     * 从代码层强行开启 `--host 0.0.0.0`：注释掉 dsh-web-app 的安全检查。
     *
     * 当前 dsh-web-app 会硬编码拒绝 `0.0.0.0`，App 打开「局域网访问」后因此以
     * usage error 退出。每次启动前幂等 patch 掉那一行，让 dsh 接受该地址。
     */
    private fun patchLanHost() {
        val target = File(
            DshEnv.rootfs(appContext),
            "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-app/lib/startup.js",
        )
        if (!target.isFile) return
        val src = target.readText(StandardCharsets.UTF_8)
        val marker = "/* patched by DSH-Folk: lan force-enable */"
        if (src.contains(marker)) return
        val patched = src.replace(
            oldValue = """if (options.host === "0.0.0.0") program.error("error: --host 0.0.0.0 is intentionally not supported yet for safety: it would expose remote code execution to the network; use 127.0.0.1 instead");""",
            newValue = marker,
        )
        if (patched != src) {
            target.writeText(patched, StandardCharsets.UTF_8)
            logInfo(R.string.dsh_log_lan_patched)
        }
    }

    /**
     * 让外部浏览器也能一次登进 dsh web：把会话 cookie 的 `SameSite=Strict` 放宽为 `Lax`。
     *
     * dsh 的浏览器会话认证（dsh-client-connection 的 `authorizeIndex`）在 `?token=` 校验通过后，
     * 用一个 303 重定向到 `/` 并下发会话 cookie，属性是 `HttpOnly; SameSite=Strict`。而 App 用
     * `Intent(ACTION_VIEW)` 拉起外部 Chrome，属于「外部/非第一方发起的顶层导航」——Chrome 对
     * `SameSite=Strict` 的 cookie 在这种导航（以及随后的 303 跳转、刷新按钮）上一律**不带**，
     * 于是服务端收不到 cookie → 回 401「authentication required; reopen the URL…」。用户手动在
     * 地址栏回车是第一方导航，`Strict` 才会带上，所以「手打能进、Intent/刷新不行」。
     *
     * 放宽成 `Lax`：顶层 GET 导航都会带 cookie（正好覆盖上面三种失败场景），跨站子请求/POST
     * 仍被挡住 —— 对一个只绑 `127.0.0.1` 的本地服务是安全且恰当的取舍。只改这一个属性，不碰
     * token/cookie 的签名逻辑，桌面端与内置 WebUI 都不受影响。
     *
     * 与 [patchLanHost] 同一路子：每次启动前幂等改 rootfs 里那份被服务端加载的源码；升级运行时
     * 会带来未打补丁的新副本，所以不能只打一次。
     */
    private fun patchBrowserCookieSameSite() {
        var patched = 0
        for (t in clientConnectionIndexFiles()) {
            val src = runCatching { t.readText(StandardCharsets.UTF_8) }.getOrNull() ?: continue
            if (!src.contains("HttpOnly; SameSite=Strict")) continue
            val out = src.replace("HttpOnly; SameSite=Strict", "HttpOnly; SameSite=Lax")
            if (out != src && runCatching { t.writeText(out, StandardCharsets.UTF_8) }.isSuccess) patched++
        }
        if (patched > 0) logInfo(R.string.dsh_log_cookie_samesite_patched, patched)
    }

    /**
     * rootfs 里所有 `@deepseek-ai/dsh-client-connection/lib/index.js`（认证逻辑所在）：
     * dsh 全局安装目录下顶层 hoist 的一份，外加 `@deepseek-ai` 下各包各自 nested 的一份。
     * 有界枚举，不深走整棵 node_modules。
     */
    private fun clientConnectionIndexFiles(): List<File> {
        val out = ArrayList<File>()
        val scope = File(
            DshEnv.rootfs(appContext),
            "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai",
        )
        File(scope, "dsh-client-connection/lib/index.js").let { if (it.isFile) out.add(it) }
        scope.listFiles()?.forEach { pkg ->
            File(pkg, "node_modules/@deepseek-ai/dsh-client-connection/lib/index.js")
                .let { if (it.isFile) out.add(it) }
        }
        return out
    }

    /**
     * 轮询直到服务真能响应 HTTP 或超时。
     *
     * 只探 TCP 端口是不够的：端口被别的进程占着（上一次没退干净、用户自己在容器里
     * 跑了东西）也会 connect 成功，于是首页显示「已就绪」，点开却是别人的页面或直接
     * 连不上。这里在端口通之后再要一次 HTTP 响应 —— 任何状态码都算（dsh 未登录时
     * 返回登录页，403 也证明是它在服务）。
     */
    private suspend fun awaitReady() {
        // 没有进程就没什么可等。不能依赖下面那句
        // `serverProcess?.isAlive == false` —— serverProcess 为 null 时它是 false
        // （null == false），于是「压根没启动」会一声不响地等到超时。
        // [startServer] 已经报错的情况下直接返回，否则这里的超时/无进程
        // 文案会盖掉那个更具体的原因。
        if (_state.value.phase == DshPhase.ERROR) return
        if (serverProcess == null) {
            val detail = str(R.string.dsh_err_no_process)
            appendLog("! $detail")
            _state.update { it.copy(phase = DshPhase.ERROR, message = detail) }
            return
        }
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (isPortInUse(port()) && httpResponds(port())) {
                awaitWebToken()
                _state.update {
                    it.copy(
                        phase = DshPhase.RUNNING,
                        progress = 1f,
                        message = str(R.string.dsh_msg_service_ready, it.webUrl),
                    )
                }
                logInfo(R.string.dsh_log_ready, port())
                prefs().edit().putInt(DshEnv.KEY_PROROOT_FAIL, 0).apply()
                return
            }
            if (serverProcess?.isAlive == false) {
                val base = str(R.string.dsh_err_process_exited)
                appendLog("! $base")
                if (runtimeId() == "proroot" && noteProrootFailure(base)) {
                    logInfo(R.string.dsh_log_switched_to_proot)
                }
                // 插件树失败时给出可执行的下一步，而不是只说「进程已退出」
                val detail = pluginTreeFailureHint() ?: base
                _state.update { it.copy(phase = DshPhase.ERROR, message = detail) }
                return
            }
            delay(1_000)
        }
        // 超时同样算 proroot 一次失败：进程没退但服务始终起不来（proroot 在部分内核上
        // 卡在 seccomp/ptrace 上就是这种表现），只记「进程退出」那一路会让用户永远
        // 卡在坏运行时上，自动回退 proot 的兜底形同不存在。
        val detail = str(R.string.dsh_err_start_timeout)
        appendLog("! $detail")
        if (runtimeId() == "proroot" && noteProrootFailure(detail)) {
            logInfo(R.string.dsh_log_switched_to_proot)
        }
        _state.update { it.copy(phase = DshPhase.ERROR, message = detail) }
    }

    /**
     * 等 dsh 打印出带 token 的地址（见 [TOKEN_WAIT_MS]）。
     *
     * 不能把「端口响应」当成「可以打开」：web 服务器**激活即监听**，而 token 那行是插件树
     * 加载完才打印的，两者之间的 `webUrl` 是不带 token 的裸地址 —— 用它开 WebUI（内置页或
     * 外部浏览器）都只会撞认证墙。等到了再宣布就绪，所有打开路径拿到的就是带 token 的地址。
     *
     * 超时不是错误：这份运行时若哪天不再打印 token（[DSH_WEB_TOKEN_RE] 没命中），也得让
     * 服务照常可用，所以按无 token 继续，只留一行日志说明 —— 否则表现成「启动卡住」，
     * 比认证墙更难查。
     */
    private suspend fun awaitWebToken() {
        if (_state.value.webToken != null) return
        val deadline = System.currentTimeMillis() + TOKEN_WAIT_MS
        var announced = false
        while (_state.value.webToken == null &&
            System.currentTimeMillis() < deadline &&
            serverProcess?.isAlive != false
        ) {
            if (!announced) {
                logInfo(R.string.dsh_log_token_waiting)
                announced = true
            }
            delay(TOKEN_POLL_MS)
        }
        val token = _state.value.webToken
        if (token == null) {
            appendLog("! " + str(R.string.dsh_log_token_timeout, TOKEN_WAIT_MS / 1000))
        } else {
            // 只记长度，不记 token 本身：长度够用来判断有没有被截断（base64url(32 字节) = 43）
            logInfo(R.string.dsh_log_token_captured, token.length)
        }
    }

    /**
     * 回环 HTTP 是否有响应。
     *
     * 任何 HTTP 状态码都算就绪 —— dsh 未登录时返回登录页（200）或 403，都说明服务在跑。
     * 只有连不上 / 不是 HTTP / 超时才算没起来。
     */
    private fun httpResponds(port: Int): Boolean = runCatching {
        val conn = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 1_500
            conn.readTimeout = 2_500
            conn.instanceFollowRedirects = false
            conn.responseCode > 0
        } finally {
            runCatching { conn.disconnect() }
        }
    }.getOrDefault(false)

    /** 停止 web 服务：先杀容器内的 dsh web，再销毁 proot 进程。 */
    fun stopServer() {
        runCatching {
            // proot 不隔离 PID，容器内 /proc 看到宿主全部进程 —— 只杀 bin.js web / dsh web，
            // 绝不裸 pkill -f node（会误杀 agent 或用户自己的 node 进程）。
            //
            // 必须排除自己：这条清理命令的 cmdline 里就含 'bin.js web' 字面量，pgrep -f 会
            // 把执行它的 bash、以及 $() 命令替换 fork 出的子 shell 一起匹配上（子 shell 与
            // 父进程共享 cmdline，所以比对 PID 挡不住它）。做法是在命令里埋一个哨兵字符串，
            // 再按 /proc/<pid>/cmdline 把带哨兵的进程全部跳过 —— 否则 kill -KILL 会先把这条
            // 清理命令自己杀掉，真正的 dsh web 反而留着。
            execRootfs(
                ": DSHFOLK_STOP_SENTINEL; " +
                "_kill() { for _p in " +
                "\$(pgrep -f 'bin.js web' 2>/dev/null; pgrep -f 'dsh web' 2>/dev/null); do " +
                "grep -qa DSHFOLK_STOP_SENTINEL \"/proc/\$_p/cmdline\" 2>/dev/null && continue; " +
                "kill \"\$1\" \"\$_p\" 2>/dev/null; done; }; " +
                "_kill -TERM; sleep 1; _kill -KILL"
            ).waitFor(8, TimeUnit.SECONDS)
        }
        runCatching { serverProcess?.destroyForcibly() }
        serverProcess = null
        startedAt = 0L
        DshFsBridge.stop()
        _state.update { it.copy(phase = DshPhase.NOT_READY, pid = null, message = str(R.string.dsh_msg_stopped)) }
    }

    fun uptimeMillis(): Long = if (startedAt == 0L) 0L else System.currentTimeMillis() - startedAt

    // ────────────────────────── 日志 ──────────────────────────

    fun tailLog(lines: Int = 200): String =
        if (::appContext.isInitialized) LogStore.named(DshEnv.serverLog(appContext)).tail(lines) else ""

    /** **上一次运行**的日志（见 [DshEnv.serverLogPrev]）：重启之后再采集也能回溯到之前的错误。 */
    fun tailPrevLog(lines: Int = 2000): String =
        if (::appContext.isInitialized) LogStore.named(DshEnv.serverLogPrev(appContext)).tail(lines) else ""

    /** 应用自己**全部**日志文件（当前那份 + 轮转出去的上一份 + 以后新加的）。 */
    fun appLogFiles(): List<java.io.File> =
        if (::appContext.isInitialized) DshEnv.logFiles(appContext) else emptyList()

    /**
     * 全部日志文件**合并排序后按时间窗口裁切**（见 [DshLogWindow]）。
     *
     * 与 [tailLog] 的区别是"按时间"而不是"按行数"、且跨文件：bugreport 的窗口选 10 分钟时，
     * 只有这一次运行的最后 10 分钟该进归档 —— 上一份里窗口内的那几行也要带上。
     */
    internal fun windowedLog(minutes: Int): DshLogWindow.Result = DshLogWindow.read(appLogFiles(), minutes)

    /**
     * 清空本次运行的日志 —— 但**先把上一份轮转成 [DshEnv.serverLogPrev]**。
     *
     * 这个函数在**每次起服务时**都会被调到，所以「重启之后再采集 bugreport」看到的永远只有
     * 本次运行的内容：真机上导入会话报错发生在 12:xx，14:24 的报告里 dsh.log 只有 1 KB
     * （就是那次启动之后的新内容），只能靠猜。留一份上一次运行的日志，问题才有可能回溯。
     */
    override fun clearLog() {
        if (!::appContext.isInitialized) return
        val log = DshEnv.serverLog(appContext)
        if (log.isFile && log.length() > 0L) {
            runCatching { log.copyTo(DshEnv.serverLogPrev(appContext), overwrite = true) }
        }
        LogStore.named(log).clear()
    }

    /**
     * 清空应用自己的**全部**日志文件（深度清理那条路用它）。
     *
     * 与 [clearLog] 的关系：clearLog 是**起服务时**用的，它把当前那份轮转进
     * [DshEnv.serverLogPrev] 再清空当前那份 —— 那是为了 bugreport 能回溯上一次运行，代价是
     * 磁盘上仍然留着全部内容（上一份），**不回收空间**。深度清理要的是真的清掉，所以这里：
     *
     * 1. 当前那份先走 [clearLog]（复用它同一条安全通道：flush 积压 → 关句柄 → 截断）；
     * 2. 轮转出去的那一份，以及 [DshEnv.logFiles] 里以后新增的任何 `*.log`，逐个走
     *    [LogStore.NamedLog.clear]。
     *
     * **绝不直接删文件**：这些文件被 [LogStore] 持有写句柄，`File.delete()` 之后后续输出会写进
     * 已被删除的 inode（内存里还在、盘上没了，而且新行永远不再落盘）。`NamedLog.clear` 先
     * flush、再关句柄、然后原地截断并把行数/体积基线归零，之后 append 自然重开 —— 正在被写的
     * 日志因此是"清空后继续写"，而不是"删掉再续写"。
     */
    fun clearAppLogs() {
        if (!::appContext.isInitialized) return
        clearLog()
        for (f in appLogFiles()) LogStore.named(f).clear()
    }

    fun appendLog(line: String) {
        if (::appContext.isInitialized) LogStore.named(DshEnv.serverLog(appContext)).append(line)
    }

    /**
     * 硬链接状态的日志行，**带上探测失败的原因**。
     *
     * 只写结论是不够的：App 私有目录本该支持硬链接，报「不支持」是反常的，
     * 而 bugreport 里的 logcat 只覆盖最近几分钟、抓不到启动时那条 Log.i。
     * 把原因写进 dsh.log 才能在下一份 bugreport 里直接看到 —— 真机上就是靠这条
     * 才拿到 `AccessDeniedException`（系统不允许在 App 私有目录建硬链接）。
     *
     * 这里**不再**声称 pnpm 的导入方式：那是 [pnpmImportMethodLine] 的事，它读
     * 真实配置。v1.3 在这行里写「pnpm 已切为 copy 导入」，而那一版的配置压根没生效。
     */
    private fun hardlinkLogLine(): String {
        val ok = hardlinkSupported()
        val detail = hardlinkDetail()
        return buildString {
            append(str(if (ok) R.string.dsh_log_hardlink_yes else R.string.dsh_log_hardlink_no))
            if (detail.isNotEmpty()) append("（$detail）")
            append(" · ")
            append(
                when {
                    // proroot 无条件加 --link2symlink，探测结果在它下面不代表 guest 的实际能力。
                    // 用 effectiveRuntimeId：x86_64 上「选了 proroot」但跑的是 proot，
                    // 说成「proroot 无条件启用 l2s」与同一份日志第一行自相矛盾。
                    effectiveRuntimeId() == "proroot" -> str(R.string.dsh_log_hardlink_l2s_forced)
                    !ok -> str(R.string.dsh_log_hardlink_l2s_on)
                    else -> str(R.string.dsh_log_hardlink_l2s_off)
                }
            )
        }
    }

    override fun fail(message: String) {
        appendLog("! $message")
        _state.update { it.copy(phase = DshPhase.ERROR, message = message, speedBytesPerSec = 0L) }
    }

    /**
     * 取本地化字符串。
     *
     * 用 [me.bmax.apatch.util.appString] 而不是 `appContext.getString`：应用内语言在
     * API 33 以下只改 Activity 的 Configuration，Application 的 Context 仍然解析成
     * **系统语言**。少了这一层，界面切成英文之后首页状态和启动日志依旧是中文
     * （风味语言那几套皮更是一行都不会出现，它们永远不是系统语言）。
     */
    override fun str(resId: Int, vararg args: Any): String =
        if (::appContext.isInitialized) appContext.appString(resId, *args) else ""

    /**
     * 写一行「进展」日志（`>` 前缀）。
     *
     * 与 [appendLog] 分开的意义：调用方只提供资源 id 与参数，不再拼中文字面量 ——
     * 日志同时进首页日志卡和 bugreport 的 dsh.log，两处都该跟随应用语言。
     */
    override fun logInfo(resId: Int, vararg args: Any) = appendLog("> " + str(resId, *args))

    /** 写一行「异常」日志（`!` 前缀）。 */
    override fun logWarn(resId: Int, vararg args: Any) = appendLog("! " + str(resId, *args))

    internal fun joinForLog(items: Collection<String>): String = DshRuntimeEnv.joinForLog(items)

    /**
     * 下载源的本地化名字。
     *
     * 走 [DshSource.labelRes] —— 与设置页、更新对话框同一份映射。日志此前用的是
     * DshSource 里一份硬编码中文的 displayName()，现在那个函数已经不存在了。
     */
    override fun sourceName(source: String): String = str(DshSource.labelRes(source))

    /**
     * 让一段文本能安全地放进 shell 的单引号里。
     *
     * 这段文本现在来自资源，翻译里出现 `'` 完全正常（英文 "don't"、法语 "l'app"），
     * 而一个裸单引号会提前闭合字符串、把后面的内容变成 shell 代码。`'\''` 是 POSIX 里
     * 唯一可靠的写法：闭合、插入转义的引号、再打开。
     */
    private fun shellSingleQuoted(s: String): String = s.replace("'", "'\\''")

    // ────────────────────────── 工具 ──────────────────────────

    fun formatSpeed(bytesPerSec: Long): String = when {
        bytesPerSec <= 0 -> "…"
        bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / 1024.0 / 1024.0)
        bytesPerSec >= 1024 -> String.format("%.0f KB/s", bytesPerSec / 1024.0)
        else -> "$bytesPerSec B/s"
    }

    override fun verifySha256(file: File, expected: String): Boolean {
        // metadata 里没给 sha256 时不拦（早期 runtime 发布没有这一项）
        if (expected.isBlank()) return true
        return DshDownloader.sha256(file).equals(expected, ignoreCase = true)
    }

    override fun availableSpace(dir: File): Long = runCatching {
        dir.mkdirs()
        StatFs(dir.absolutePath).availableBytes
    }.getOrDefault(-1L)

    /**
     * 运行时占用统计（设置页存储信息用）。
     *
     * **别在组合期调用**：它递归遍历整个 rootfs。首页要显示体积请读
     * `state.rootfsSizeBytes`（缓存值），需要刷新走 [refreshRootfsSize]。
     */
    fun rootfsSizeBytes(): Long = if (!ready) 0L else dirSize(DshEnv.rootfs(appContext))

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.listFiles()?.sumOf { dirSize(it) } ?: 0L
    }
}
