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
 * [DshRuntime] 的数据模型：运行态、元信息与版本。
 *
 * 从 DshRuntime.kt 里搬出来（交接规范阶段 4）：它们与那个 3000 多行的 object 没有代码关系，
 * 只是原先都堆在同一个文件里 —— 同包移动，所有 import 与调用点零改动。
 */

enum class DshPhase { NOT_READY, DOWNLOADING, EXTRACTING, STARTING, RUNNING, ERROR }

/** 端口冲突时用户的选择。 */
enum class PortConflictAction { AUTO, MANUAL, FORCE }

/** 运行时状态快照（首页卡片与启动日志面板消费）。 */
data class DshState(
    val phase: DshPhase = DshPhase.NOT_READY,
    val progress: Float = 0f,
    val speedBytesPerSec: Long = 0L,
    val message: String = "",
    val port: Int = DshEnv.DEFAULT_PORT,
    val pid: Long? = null,
    val runtimeVersion: String? = null,
    /**
     * 已装运行时要求的最低 App 版本，当前 App 不满足（见 [DshRuntime.appSatisfies]）。
     *
     * 置位时首页与设置页的「启动 / 更新 / 重装」入口都要变成「请先更新应用」，
     * 而不是让用户撞一串 node 堆栈。App 升级后 [DshRuntime.attach] 重算，自动清除。
     */
    val appUpdateRequired: Boolean = false,
    /** [appUpdateRequired] 为 true 时，这份运行时要求的最低 App 版本（给人看）。 */
    val requiredAppVersion: String? = null,
    val installed: Boolean = false,
    /**
     * 容器体积（字节），0 表示还没算过。
     *
     * 走缓存 + 后台重算而不是现算：见 [DshEnv.KEY_ROOTFS_SIZE]。
     */
    val rootfsSizeBytes: Long = 0L,
    /**
     * 启动前探测到端口被外部进程占用、正等用户决定。
     *
     * 置位时首页弹「端口被占用」对话框，用户在 [DshRuntime.resolvePortConflict]
     * 里选择换端口 / 手动指定 / 强制启动。
     */
    val portConflict: Boolean = false,
    /** dsh web 的认证 token；有值时 [webUrl] 带 `?token=...`。 */
    val webToken: String? = null,
) {
    val webUrl: String get() {
        val base = "http://127.0.0.1:$port/"
        return if (webToken.isNullOrBlank()) base else base + "?token=$webToken"
    }
}

/** 运行时下载元数据（由 CI 生成的 metadata.json 提供）。 */
data class DshMeta(
    val version: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val mirrors: List<String> = emptyList(),
    val arch: String = "",
    val dsh: String = "",
    val nodeVersion: String = "",
    val builtAt: String = "",
    /** 这份运行时要求的最低 DSH-Folk App 版本；空 = 无要求（旧 metadata 兼容）。 */
    val minAppVersion: String = "",
    /**
     * `full` / `slim`（构建脚本写在 metadata 里的 `flavor`）。
     *
     * 空 = 老 metadata 没这一项，那时只能按 tag 猜（见 [RuntimeVersion.flavorOf]）。
     */
    val flavor: String = "",
)

/**
 * 运行时更新检查的结果。
 *
 * [version] 非空 = 远端有不同版本；[minAppVersion] 非空 = 那份新运行时还要求一个
 * 比当前 App 更高的版本 —— 用户得**先更新应用**，而不是直接点「更新运行时」。
 *
 * [failure] 专门区分「查不到」与「已是最新」：断网、GitHub 限流、本地没记过版本号
 * 都会走到这里，界面若一律报「已是最新」就是在给用户一个肯定的错误结论。
 */
data class RuntimeCheckResult(
    val version: String? = null,
    val minAppVersion: String = "",
    val failure: Boolean = false,
)

/**
 * 版本列表里的一项：某个 runtime release 的 metadata（本机架构那一份）。
 *
 * 存在的理由：滚动通道 tag 只有 `runtime-latest` / `runtime-beta-latest` /
 * `runtime-slim-latest` / `runtime-slim-beta-latest` 四个，光靠它们既看不到历史版本，
 * 也没法降级。长按「更新」时列出仓库里**所有**运行时 release，让用户自己挑版本
 * （升级、降级、切通道、切口味）。
 */
data class RuntimeVersion(
    val version: String,
    val tag: String,
    val channel: String = RuntimeVersion.CHANNEL_ARCHIVE,
    val dsh: String = "",
    val nodeVersion: String = "",
    val builtAt: String = "",
    val minAppVersion: String = "",
    val sha256: String = "",
    val sizeBytes: Long = 0L,
    val arch: String = "",
    val url: String = "",
    val mirrors: List<String> = emptyList(),
    /** release 的发布时间（ISO 串，字典序即时间序）；通道兜底项为空。 */
    val publishedAt: String = "",
    /** `full` / `slim`；空 = 这份 metadata 没写，筛选时退回按 tag 认（[flavorOf]）。 */
    val flavor: String = "",
) {
    /** 交给安装流程时用的等价 metadata。 */
    fun toMeta(): DshMeta = DshMeta(
        version = version,
        url = url,
        sha256 = sha256,
        sizeBytes = sizeBytes,
        mirrors = mirrors,
        arch = arch,
        dsh = dsh,
        nodeVersion = nodeVersion,
        builtAt = builtAt,
        minAppVersion = minAppVersion,
        flavor = flavorOf(this),
    )

    companion object {
        const val CHANNEL_STABLE = "stable"
        const val CHANNEL_BETA = "beta"
        /** 精简版（不含文档预览/转换）。与正式/测试是**两个正交维度**，所以各有一个通道值。 */
        const val CHANNEL_SLIM = "slim"
        const val CHANNEL_SLIM_BETA = "slim-beta"
        const val CHANNEL_ARCHIVE = "archive"

        /** metadata 里的 `flavor` 取值（构建脚本写的就这两个）。 */
        const val FLAVOR_FULL = "full"
        const val FLAVOR_SLIM = "slim"

        /**
         * 这份运行时的版本类型：优先信 metadata 的 `flavor`，没有（老 metadata）就按 tag 猜。
         *
         * 按 tag 猜只对四个滚动 tag 成立 —— `runtime-slim-*` 必然是精简版；历史 release
         * （`runtime-0.1.1-rc.2` 这种）的 tag 里根本没有 flavor，只能当完整版。所以
         * **能解析 metadata 就别用 tag 猜**：列表筛选靠这个字段。
         */
        fun flavorOf(entry: RuntimeVersion): String = when {
            entry.flavor.isNotEmpty() -> entry.flavor
            entry.tag.contains("slim") -> FLAVOR_SLIM
            else -> FLAVOR_FULL
        }

        /**
         * 版本菜单那两个滑块筛的到底是哪些行。
         *
         * - **版本类型**（精简/完整）：不匹配就整行不显示（这是硬条件，滑块说的就是它）；
         * - **更新通道**（测试/正式）：四个滚动通道按名字分边；**历史版本两边都留** ——
         *   它们的 tag 里没有通道信息，只给一边就等于让另一半人找不到降级包（列表里那行
         *   标着「历史版本」，不会和正式版混淆）。
         */
        fun matchesFilter(entry: RuntimeVersion, slim: Boolean, beta: Boolean): Boolean {
            val want = if (slim) FLAVOR_SLIM else FLAVOR_FULL
            if (flavorOf(entry) != want) return false
            // 测试通道 = 在正式通道的基础上**也**接受测试版。以前这里把测试版当「替代」：选了测试
            // 通道就只认测试版，于是正式通道更新到 0.2.1-rc.2 之后，选着测试的人既看不到它、也
            // 检测不到它（真机反馈）。正式通道（beta=false）仍只认正式版，不把人推到测试版。
            return when (entry.channel) {
                CHANNEL_STABLE, CHANNEL_SLIM -> true
                CHANNEL_BETA, CHANNEL_SLIM_BETA -> beta
                else -> true
            }
        }

        /** 列表排序权重：正式 → 精简 → 测试 → 精简测试 → 历史版本。 */
        fun channelRank(channel: String): Int = when (channel) {
            CHANNEL_STABLE -> 0
            CHANNEL_SLIM -> 1
            CHANNEL_BETA -> 2
            CHANNEL_SLIM_BETA -> 3
            else -> 4
        }

        /**
         * tag → 通道。
         *
         * 四个滚动 tag 各有确定的名字，先逐个精确匹配；后面几条前缀规则给「名字不那么
         * 规整」的瘦身 tag 兜底。**顺序上必须先认带 beta 的**，否则
         * `runtime-slim-beta-latest` 会被 `runtime-slim` 前缀吞掉、误判成正式精简版。
         */
        fun channelOf(tag: String): String = when {
            tag == "runtime-latest" -> CHANNEL_STABLE
            tag == "runtime-beta-latest" -> CHANNEL_BETA
            tag == "runtime-slim-latest" -> CHANNEL_SLIM
            tag == "runtime-slim-beta-latest" -> CHANNEL_SLIM_BETA
            tag.startsWith("runtime-slim") && tag.contains("beta") -> CHANNEL_SLIM_BETA
            tag.startsWith("runtime-slim") -> CHANNEL_SLIM
            tag.startsWith("runtime-beta") -> CHANNEL_BETA
            else -> CHANNEL_ARCHIVE
        }
    }
}

/**
 * DSH 运行时管理：在线下载 rootfs → 解压 → 用 proot/proroot 进容器起 `dsh web`。
 *
 * 组合了两个上游的做法：
 * - 容器执行层（proot argv / 环境变量 / 硬链接探测 / proroot 三振出局回退）来自 DSHA；
 * - 在线交付层（metadata.json + 多镜像测速竞速 + sha256 + 进度）来自 DSHM。
 *
 * 关键约束（Android 平台限制，不是选择）：
 * - 可执行的 proot/proroot 只能从 `nativeLibraryDir` 运行（app 私有目录是 SELinux noexec）；
 * - rootfs 必须落在 filesDir（可写），容器内的一切执行由 proot 代理。
 */