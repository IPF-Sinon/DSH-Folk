package me.bmax.apatch.dsh

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import me.bmax.apatch.BuildConfig
import me.bmax.apatch.util.PermissionUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 宿主能力提示词注入：让容器里的 agent 知道自己跑在 DSH-Folk 里、能用哪些宿主命令。
 *
 * ## 为什么需要
 *
 * dsh 的 agent 默认以为自己在一台普通 Linux 上。它不知道 `/sdcard` 是手机共享存储、
 * 不知道有 `dsh-fs` / `dsh-native` 这两个命令，更不知道原生能力此刻是开还是关 ——
 * 于是要么根本想不到能发通知，要么一遍遍去调一个返回 403 的接口。
 *
 * ## 分工
 *
 * - 这个类只负责**写事实**：把「宿主是什么、哪些能力开着」序列化成
 *   `/root/.dsh/host-facts.json`（[DshEnv.hostFacts]）。
 * - 真正把文字塞进系统提示词的是容器内的 cordis 插件 `dsh-folk-host`
 *   （源码 `app/src/main/assets/dsh-folk-host.mjs`，由 [ensureInstalled] 落盘）。
 *
 * 为什么要分成两半：提示词的组装发生在 dsh 进程里，宿主进不去；而能力开关在
 * SharedPreferences 里，容器进不来。文件是两边都能碰的最简接口，而且插件按 mtime
 * 判失效 —— 用户在设置里拨一下开关，下一轮组装就是新的，不必重启 dsh。
 *
 * ## 为什么走 systemPrompt.section 而不是 AGENTS.md
 *
 * dsh 支持 `$DSH_HOME/AGENTS.md` 全局指令文件，但 web profile 恰恰把
 * agent-instructions 的全局行 disable 了（`dsh-web-app/cordis.patch.yml`），改由每个
 * agent preset 自己挂 —— 用户换个 preset 就静默失效。而 `systemPrompt.section` 注册在
 * global layer，对所有 preset 的会话都可见，且进的是 system prompt 前缀：KV cache 稳定、
 * 不占对话历史、compaction 之后不用重注。
 *
 * ## 插件怎么被加载
 *
 * 不走 `dsh plugin add`（那要 pnpm 联网装包，为一个 40 行的本地文件不值得）。
 * 直接把 .mjs 落进 rootfs，再往 `$DSH_HOME/cordis.patch.yml` 追加一行 loader entry，
 * `name` 用**容器内绝对路径**。这是 dsh 明确支持的：patch 的 home 层对每个 profile 都
 * 生效（`profile-boot` 的 `homePatches`），而 loader 的 EntryTree.import 对绝对路径
 * specifier 直接交给 Node 的 ESM 解析（相对路径才按 profile 目录解析，所以必须用绝对）。
 */
object DshHostPrompt {
    private const val TAG = "DshHostPrompt"

    /** 插件在 APK assets 里的名字。 */
    private const val ASSET_NAME = "dsh-folk-host.mjs"

    /** 插件在容器内的落点（宿主视角要拼 rootfs 前缀）。 */
    private const val PLUGIN_GUEST_PATH = "/root/.dsh/plugins/dsh-folk-host.mjs"
    private const val PLUGIN_HOST_REL = "root/.dsh/plugins/dsh-folk-host.mjs"

    /** loader entry id：写进 patch 行，也是将来要改/删时的锚点。 */
    private const val ENTRY_ID = "dsh-folk-host"

    /** home 级 patch 文件（对所有 profile 生效），宿主视角的相对路径。 */
    private const val HOME_PATCH_REL = "root/.dsh/cordis.patch.yml"

    /**
     * 插件内容版本。
     *
     * 改了 [ASSET_NAME] 的内容就 +1：落盘按「版本不同才写」判断，否则每次引导都要
     * 读一遍 assets 再全量覆盖。版本号存在 prefs 里，与 rootfs 无关 —— 重装运行时后
     * 文件没了但版本号还在，所以 [ensureInstalled] 另外检查文件是否真的存在。
     */
    private const val PLUGIN_REV = 3
    private const val KEY_PLUGIN_REV = "host_prompt_plugin_rev"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)

    /** 是否注入（默认开）。 */
    fun enabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(DshEnv.KEY_HOST_PROMPT, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit { putBoolean(DshEnv.KEY_HOST_PROMPT, on) }
        // 立刻改事实文件：不必等下一次引导，用户拨完开关下一轮对话就生效
        writeFacts(ctx)
    }

    /**
     * 落盘插件 + 挂上 patch 行。引导路径调用，幂等。
     *
     * 顺序是「先写文件再改 patch」：反过来的话，patch 已经指向一个还不存在的文件，
     * 期间启动 dsh 会让整棵插件树加载失败（`assertEntriesActivated` 对 FAILED 的 entry
     * 直接抛，不是跳过），表现是 web 服务起不来。
     */
    fun ensureInstalled(ctx: Context) {
        if (!DshEnv.isRuntimeInstalled(ctx)) return
        runCatching {
            val dst = File(DshEnv.rootfs(ctx), PLUGIN_HOST_REL)
            val revOk = prefs(ctx).getInt(KEY_PLUGIN_REV, 0) == PLUGIN_REV
            if (!revOk || !dst.isFile || dst.length() == 0L) {
                val body = ctx.assets.open(ASSET_NAME).use {
                    it.readBytes().toString(StandardCharsets.UTF_8)
                }
                dst.parentFile?.mkdirs()
                dst.writeText(body, StandardCharsets.UTF_8)
                prefs(ctx).edit { putInt(KEY_PLUGIN_REV, PLUGIN_REV) }
            }
            writeFacts(ctx)
            ensurePatchRow(ctx)
        }.onFailure { android.util.Log.w(TAG, "安装提示词插件失败: ${it.message}") }
    }

    /**
     * 往 home 级 `cordis.patch.yml` 追加插件行（已存在则不动）。
     *
     * 手写 YAML 而不是调容器里的 node/yaml：这一步在引导路径上，容器可能还没起来，
     * 而要写的内容是两行定长文本。为了不破坏用户可能已有的内容，只做「读全文 → 判断
     * 有没有我们的 id → 没有就追加」，绝不重写已有行。
     *
     * 文件不存在时创建成一个合法的顶层 YAML 数组（dsh 的 parsePatchList 要求顶层是
     * 数组，否则**整个 profile 启动失败**）。
     */
    private fun ensurePatchRow(ctx: Context) {
        val f = File(DshEnv.rootfs(ctx), HOME_PATCH_REL)
        val existing = if (f.isFile) f.readText(StandardCharsets.UTF_8) else ""
        // 判据用 id 而不是路径：将来路径变了也不会重复追加
        if (existing.contains("id: $ENTRY_ID")) return
        val row = buildString {
            if (existing.isNotEmpty() && !existing.endsWith("\n")) append('\n')
            append("# DSH-Folk 宿主能力说明（App 自动维护；删掉这两行即可停用）\n")
            append("- insert:\n")
            append("    - id: $ENTRY_ID\n")
            append("      name: $PLUGIN_GUEST_PATH\n")
        }
        f.parentFile?.mkdirs()
        // 先写 .tmp 再 rename：这个文件坏掉会让 dsh 完全起不来，不能留半截
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(existing + row, StandardCharsets.UTF_8)
        if (!tmp.renameTo(f)) {
            tmp.delete()
            android.util.Log.w(TAG, "写 cordis.patch.yml 失败（rename）")
        }
    }

    /**
     * 写宿主事实文件。
     *
     * 每次引导、以及任何相关开关变化时调用。内容全是当下可直接读到的状态，不做缓存 ——
     * 这个文件只有几百字节，而「读到的是旧值」会让 agent 拿着过期的能力清单干活。
     */
    fun writeFacts(ctx: Context) {
        runCatching {
            val caps = JSONArray()
            val nativeOn = DshNativeBridge.enabled(ctx)
            if (nativeOn) {
                for (c in DshNativeBridge.enabledCaps(ctx)) caps.put(c.id)
            }
            val json = JSONObject()
                .put("promptEnabled", enabled(ctx))
                .put("appVersion", BuildConfig.VERSION_NAME)
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("androidRelease", Build.VERSION.RELEASE ?: "")
                .put("sdkInt", Build.VERSION.SDK_INT)
                .put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "")
                // 设备语言是**事实**，不是让提示词跟着翻译的理由：段文本保持英文（与 dsh
                // 自带的各段一致），但 agent 需要知道该用什么语言回话、通知该写成什么语言。
                .put("locale", localeTag(ctx))
                // 实际在跑的那个：agent 看到 proroot 会以为 link() 一定变符号链接
                .put("containerRuntime", DshRuntime.effectiveRuntimeId())
                // 桥进程一直在，但没有「所有文件访问」时每个文件端点都回 403 ——
                // 提示词必须说清是哪一种，否则 agent 会拿着 dsh-fs 一路撞 403。
                .put("fsBridge", PermissionUtils.hasAllFilesAccess(ctx))
                .put("nativeBridge", nativeOn)
                .put("nativeCaps", caps)
                .put("notificationPermission", PermissionUtils.hasNotificationPermission(ctx))
                // 媒体是逐类授权的（Android 13 起）：只给了照片时 agent 该知道音频读不了，
                // 而不是以为设备上没有音频文件。
                .put("mediaPermissions", grantedMediaJson(ctx))
                .put("microphonePermission", PermissionUtils.hasMicrophonePermission(ctx))
                .put("cameraPermission", PermissionUtils.hasCameraPermission(ctx))
                // 位置有两档：只给了「大致」时系统把坐标模糊到公里级，agent 不该
                // 把它当街道级坐标用
                .put("preciseLocation", PermissionUtils.hasPreciseLocationPermission(ctx))
                // 这三项申请不到、只能由用户在系统页里开。agent 知道状态才能决定
                // 要不要提示用户，而不是撞一串 403
                .put("writeSettings", PermissionUtils.canWriteSystemSettings(ctx))
                .put("dndAccess", PermissionUtils.hasNotificationPolicyAccess(ctx))
                .put("canRequestInstall", PermissionUtils.canRequestPackageInstalls(ctx))
                // 提权通道给 agent 看的是「有没有」，不是具体哪条 —— 它用不上具体通道，
                // 但需要知道「别指望 su」。
                .put("elevation", elevationLabel(ctx))
                .toString()
            val f = DshEnv.hostFacts(ctx)
            f.parentFile?.mkdirs()
            f.writeText(json, StandardCharsets.UTF_8)
        }.onFailure { android.util.Log.w(TAG, "写 host-facts 失败: ${it.message}") }
    }

    /**
     * 当前生效的语言标签，如 `zh-CN` / `en-US`。
     *
     * 取的是**应用**的 locale（`resources.configuration.locales[0]`）而不是 `Locale.getDefault()`：
     * 用户可能在系统里给 DSH-Folk 单独设了语言（Android 13 的 per-app language），
     * 那时前者才是他真正看到的语言。
     */
    private fun localeTag(ctx: Context): String = runCatching {
        val locales = ctx.resources.configuration.locales
        if (locales.isEmpty) "" else locales.get(0).toLanguageTag()
    }.getOrElse { "" }

    /** 已授权的媒体类型 id 列表（`image` / `video` / `audio`）。 */
    private fun grantedMediaJson(ctx: Context): JSONArray {
        val arr = JSONArray()
        for (t in PermissionUtils.grantedMediaTypes(ctx)) arr.put(t.id)
        return arr
    }

    /** 提权状态的字符串形式：none / root / shizuku / adb。 */
    private fun elevationLabel(ctx: Context): String {
        if (!PermissionManager.elevationEnabled(ctx)) return "none"
        return when (PermissionManager.readPreference(ctx)) {
            PermissionManager.Channel.ROOT -> "root"
            PermissionManager.Channel.SHIZUKU -> "shizuku"
            PermissionManager.Channel.ADB -> "adb"
            // null = 「自动」：具体走哪条由探测决定，对 agent 而言只要知道「已启用」
            else -> "auto"
        }
    }
}
