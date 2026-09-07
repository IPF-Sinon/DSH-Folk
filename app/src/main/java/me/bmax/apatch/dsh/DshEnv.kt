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

    /** 局域网访问开关（默认关；开则 dsh web 绑 0.0.0.0）。 */
    const val KEY_LAN = "dsh_lan"

    /** 文件桥回环 token（随机生成，写进容器内配置文件供 dsh-fs 使用）。 */
    const val KEY_FS_TOKEN = "fs_bridge_token"

    /** 容器内文件桥配置（JSON：port + token），由 App 写、dsh-fs 读。 */
    fun fsBridgeConfig(ctx: Context): File = File(dshHome(ctx), "fs-bridge.json")

    /**
     * 容器体积（字节）的缓存值。
     *
     * 必须缓存：算它要递归遍历整个 Ubuntu rootfs（十万级文件），在组合期同步调用
     * 会让每次导航回首页都卡 2 秒以上（真机日志实测 duration=2505ms + Skipped 232 frames）。
     * 只在安装完成、以及首次缺值时于 IO 线程后台重算。
     */
    const val KEY_ROOTFS_SIZE = "rootfs_size_bytes"
    const val KEY_PROROOT_FAIL = "proroot_fail_streak"

    /**
     * 开机自启的**旧**布尔开关（1.8.0 及以前）。
     *
     * 现在的权威值是 [KEY_AUTOSTART_MODE]。这一项由 [DshAutostart.setMode] 跟着同步写，
     * 只为让降级回旧版本的用户不至于突然失去自启 —— 新代码不要读它。
     */
    const val KEY_AUTOSTART = "dsh_autostart"

    /** 自启动方式：off | receiver | script | a11y（见 [DshAutostart.Mode]）。 */
    const val KEY_AUTOSTART_MODE = "dsh_autostart_mode"

    /** 自启时是否连容器一起拉起（默认 true，与 1.8.0 的行为一致）。 */
    const val KEY_AUTOSTART_CONTAINER = "dsh_autostart_container"

    /**
     * 权限通道首选：off | auto | root | shizuku | adb。
     *
     * **默认 off**（未启用）。见 [PermissionManager.readPreference] 与迁移逻辑。
     */
    const val KEY_PERM_CHANNEL = "perm_channel_pref"

    /**
     * 原生能力桥总开关（默认关）。
     *
     * 关闭时 `/native/` 下的全部端点 一律 403。容器里跑的是 dsh 和用户自己装的第三方插件，
     * 让它们随手弹通知、读剪贴板、拉起分享面板是实打实的能力扩张，必须显式同意。
     */
    const val KEY_NATIVE_BRIDGE = "native_bridge_enabled"

    /**
     * 已启用的原生能力（英文逗号分隔的能力 id，见 [DshNativeBridge.Cap]）。
     *
     * 总开关之外再分项：想让 agent 发通知的人不一定想让它读剪贴板。
     */
    const val KEY_NATIVE_CAPS = "native_bridge_caps"

    /** WebUI 打开方式：in | browser | ask。 */
    const val KEY_WEBUI_MODE = "webui_open_mode"

    /** 应用内 WebUI 悬浮球吸附的一侧：left | right。 */
    const val KEY_WEBUI_BALL_SIDE = "webui_ball_side"

    /** 应用内 WebUI 悬浮球的纵向位置，0..1 的屏高比例。 */
    const val KEY_WEBUI_BALL_Y = "webui_ball_y"

    /**
     * 旧内核 JS 兼容垫片：auto | on | off。
     *
     * auto（默认）= 还没决定，此时不注入；只有在检测到 WebView 内核 ≤
     * [DSH_COMPAT_MIN_CHROMIUM] 时弹一次说明，用户的选择固化成 on/off。
     */
    const val KEY_WEBUI_COMPAT = "webui_compat_shim"

    /**
     * 需要垫片的 Chromium 主版本上界（含）。
     *
     * 垫片补的是 `AbortSignal.any`（Chrome 116）、`Promise.withResolvers`（119）与
     * 非安全上下文下缺失的 `crypto.randomUUID`。119 及以下都可能缺，120 起齐全。
     */
    const val DSH_COMPAT_MIN_CHROMIUM = 119

    /**
     * 首启预装插件是否已经跑过。
     *
     * 无论成功失败都置位：失败不该在每次冷启动重试（用户可以自己去商店装），
     * 否则每次开应用都要多等一轮 pnpm。
     *
     * 只保留给旧版本迁移用：布尔量记不住「装过哪些」，1.6 把预装清单从 2 个加到 3 个
     * 之后，1.5 老用户的这个标记已经是 true，新增那个就永远轮不到装。
     * 现在的判据是 [KEY_SEEDED_PLUGINS]。
     */
    @Deprecated("用 KEY_SEEDED_PLUGINS，它记得住装过哪些")
    const val KEY_SEED_PLUGINS_DONE = "seed_plugins_done"

    /**
     * 已经尝试预装过的包名（英文逗号分隔）。
     *
     * 记名字而不是记布尔：预装清单以后还会增删，只有逐个记名才能让老用户在升级后
     * 补上新增的那个，同时不重复跑已经装过的。
     */
    const val KEY_SEEDED_PLUGINS = "seeded_plugins"

    /**
     * 已应用的「预装补修」轮次（见 [DshRuntime.SEED_REPAIR_REV]）。
     *
     * [KEY_SEEDED_PLUGINS] 记的是「试过」，无论成败都置位 —— 这在当时是对的（失败不该
     * 每次冷启动重试），但代价是**修好了根因也救不回已经失败的那次**。1.7.6 的
     * dsh-file-upload 就卡在这里：pnpm 拦下构建脚本导致它没进 bundles，而包名已被记账，
     * 下次启动不会再试。
     *
     * 这个轮次号让「修好根因」能顺带补修历史：轮次变大时，把**记过账但实际没生效**的
     * 预装包从账本里摘掉，让它们再试一次。只补真正没生效的，不会重跑已生效的。
     */
    const val KEY_SEED_REPAIR_REV = "seed_repair_rev"

    /** 安装插件后是否用 `dsh web --port 0` 验证一次能否启动（默认开）。 */
    const val KEY_VERIFY_AFTER_INSTALL = "verify_after_install"

    /**
     * 是否往 dsh 的系统提示词里注入宿主能力说明（默认开）。
     *
     * 关掉不卸插件，只是让 [DshHostPrompt] 写的事实文件里 `promptEnabled` 变 false，
     * 插件那一段随即渲染成空串 —— dsh 的 renderPrompt 会丢掉空段，等于零开销。
     * 卸插件要动 profile 的 bundles，重装一次就得再走 pnpm，不值得为一个开关做。
     */
    const val KEY_HOST_PROMPT = "host_prompt_enabled"

    /** 宿主事实文件（JSON），由 App 写、dsh-folk-host 插件读。 */
    fun hostFacts(ctx: Context): File = File(dshHome(ctx), "host-facts.json")
}
