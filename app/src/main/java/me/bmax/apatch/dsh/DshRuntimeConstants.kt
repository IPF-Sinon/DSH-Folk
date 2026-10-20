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

/**
 * 运行时用到的常量与纯数据表：超时、路径片段、pnpm 配置键、预装插件名单。
 *
 * 从 DshRuntime.kt 搬出（交接规范阶段 4）。它们与那个 2600 多行的 object 没有代码关系，
 * 只是原先堆在同一个文件开头 —— 同包声明，调用点零改动。
 *
 * 先搬常量的理由：seed 那一簇经实测需要 57 个成员，含可变状态（ready / serverProcess /
 * _state）与整条进程启动路径（awaitReady / applyEnv / baseArgv …）。硬搬必须先暴露内部状态，
 * 那比不拆更糟；常量是真正可搬的部分。
 */

/**
 * 等服务就绪的上限。
 *
 * 从 180s 压到 90s：proroot 现在失败一次就回退（见 [PROROOT_FAIL_LIMIT]），
 * 而「卡住不退」的 proroot 唯一表现就是耗尽这个超时 —— 等待越久，用户从
 * 坏运行时走到可用运行时的时间就越长。node 起 dsh web 正常在 10s 内。
 */
internal const val READY_TIMEOUT_MS = 90_000L

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
internal const val TOKEN_WAIT_MS = 30_000L

/** 等 token 时的轮询间隔：这行通常在 1s 内到，密一点能让「已就绪」尽快翻过来。 */
internal const val TOKEN_POLL_MS = 200L

/**
 * proroot 连续失败到此次数即强制回退 proot 并清掉用户选择。
 *
 * 取 1（失败即回退）：proroot 的失败模式是内核相关的确定性失败，重试同一台
 * 设备不会有不同结果，让用户白等第二、三次没有意义。
 */
internal const val PROROOT_FAIL_LIMIT = 1

/** profile pnpm 设置里固定导入方式（见 [ensureProfilePnpmSettings]）。 */
internal const val PNPM_IMPORT_KEY = "packageImportMethod"

internal const val PNPM_IMPORT_LINE = "packageImportMethod: copy"

/** v1.3 写进 /root/.npmrc 的无效行，只为清理它而保留。 */
internal const val NPMRC_LEGACY_LINE = "package-import-method=copy"

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
internal val SEED_PLUGINS =
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
internal val SEED_SPECS = mapOf("dsh-folk-cloud" to "github:IPF-Sinon/dsh-folk-cloud")

internal val SEED_MIN_VERSIONS = mapOf(
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
internal val SEED_FALLBACK_TGZ = mapOf(
"dsh-folk-cloud" to
    "https://github.com/IPF-Sinon/dsh-folk-cloud/releases/download/v0.6.0/dsh-folk-cloud-0.6.0.tgz",
)

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
internal val RETIRED_SEED_PLUGINS = mapOf("dsh-file-upload" to "file-upload")

/** 启动日志里「profile 声明了 bundles 但包解析不到」的行（声明残留时出现）。 */
internal val PROFILE_BUNDLE_RE =
Regex("cannot resolve profile bundle [\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

/** 启动日志里「重复 loader entry id」的行（0.1.5 内置能力与三方插件重名时出现）。 */
internal val DUP_ENTRY_RE = Regex("duplicate loader entry id[: ]+([A-Za-z0-9_.@/-]+)", RegexOption.IGNORE_CASE)

/**
 * 「预装补修」轮次（见 [applySeedRepair]）。
 *
 * 修好一个会让预装失败的根因后 +1，让**记过账但实际没生效**的预装包再试一次。
 *   1 = 1.7.7：修 pnpm 拦构建脚本导致 dsh-file-upload 装了但没进 bundles
 *   2 = 1.9.2.7：github/git 插件安装改走 gh-proxy 镜像线路（1.9.2.6）后，重试一次
 *       之前因直连 github 失败而记账没生效的预装包（dsh-folk-cloud 首当其冲）
 */
internal const val SEED_REPAIR_REV = 2

/**
 * 同一个运行时版本下，最多允许几轮「补装没生效的预装包」。
 *
 * 给的是**瞬时故障**的余地（网络抽风、pnpm 首次拉包超时）：第一次失败后下次开机
 * 还能再试一次；两次都不行就认定这个环境下装不上，不再每次开机重跑 pnpm。
 * 换运行时版本会重置这个计数（见 [applySeedEnvRetry]）。
 */
internal const val SEED_MAX_PASSES = 2

/** 样例目录名（容器内 `/root/.dsh/<这个名字>/`）。 */
/** 写到容器里的 DSL 文档文件名（与 `dsh-workflow` / 工具插件约定）。 */
/**
 * 插件树/客户端包加载失败的日志签名。
 *
 * 命中这些就**不能**判定为 proroot 故障：它与容器运行时无关，换 proot 重试
 * 照样失败（真机实测就是这样），而 [PROROOT_FAIL_LIMIT] = 1 会一次就把用户的
 * proroot 偏好静默清掉。
 */
internal val PLUGIN_FAILURE_MARKS = listOf(
"plugin tree failed to load",
"client bundles not found",
"failed to apply loader entry modules",
)
