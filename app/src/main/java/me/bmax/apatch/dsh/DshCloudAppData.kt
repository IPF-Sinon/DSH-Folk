package me.bmax.apatch.dsh

import android.content.Context
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * 云备份的「软件数据补包」实现：把 [DshConfigBackup] 已有的导出/导入整包通路，包一层给
 * dsh-folk-cloud 插件经 [DshFsBridge] 的 `/cloud/appdata/` 端点族调用。
 *
 * ## 为什么由 App 出整包（而不是插件自己拼）
 *
 * 含软件数据的档位，整包组装天然是 App 的活：软件数据与外观住在 Android 私有目录，
 * 而 App 已经有一条成熟通路（插件出 DSH 明文 → 合并 App 数据与主题 → 改 manifest →
 * 重算 checksums → 加密），复用它，格式就与 App 自己「备份」出来的完全一致 ——
 * 同一份包在 App 的恢复向导里也能直接打开。插件再实现一遍只会多一处会漂移的格式。
 *
 * ## 档位映射
 *
 * 插件的 `tier` 字符串 → App 的 [BackupScope]：
 * - `app-only`      → [BackupScope.APP_ONLY]
 * - `app-dsh`       → [BackupScope.BOTH]
 * - `app-dsh-vault` → [BackupScope.BOTH_VAULT]
 *
 * 只处理**含软件数据**的三档；不含软件数据的档位（dsh-only / dsh-vault）插件自己走
 * dsh-config-manager，根本不会调到这里。
 */
internal object DshCloudAppData {

    /** 主题包大小的进程内缓存时长：面板每次打开都会问，不必每次重新打包测量。 */
    private const val THEME_SIZE_CACHE_MS = 5 * 60 * 1000L

    /** 最近一次量到的 (主题包字节数, 时刻)；null 表示还没量过。 */
    @Volatile private var themeSizeCache: Pair<Long, Long>? = null

    /** 出一份含软件数据的整包。请求体：`{tier,includeSessions,encrypt,password,outDir,includeTheme}`。 */
    fun export(ctx: Context, body: JSONObject): Pair<Int, String> {
        val tier = body.optString("tier")
        val scope = scopeForTier(tier)
            ?: return 400 to error("tier ${tier.ifEmpty { "(空)" }} 不含软件数据，不该走 App 补包接口")
        val encrypt = body.optBoolean("encrypt", false)
        val password = body.optString("password")
        if (scopeNeedsVaultPassword(scope) && !encrypt) {
            return 400 to error("含 vault 的档位必须加密")
        }
        if (encrypt && password.isEmpty()) {
            return 400 to error("加密档位必须提供口令")
        }
        // 插件指定把整包放哪（它随后会算哈希、上传）。给了就用它的目录，没给退回缓存。
        // 关键：插件给的是**容器路径**（/root/.dsh/dsh-folk-cloud），App 跑在 Android，必须映射到
        // 宿主 rootfs 真实目录才写得进（否则 File("/root/.dsh/...") 落 ENOENT——真机实测就是这个错）；
        // 而回给插件的 file 必须仍是**容器路径**，插件要在容器里 stat/读它再上传。
        val outDirContainer = body.optString("outDir")
        val destDirHost: File
        val destPathForPlugin: (String) -> String
        if (outDirContainer.isNotEmpty()) {
            destDirHost = DshEnv.containerToHost(ctx, outDirContainer)
                ?: return 400 to error("插件目录不在 rootfs 内，拒绝写入：$outDirContainer")
            destPathForPlugin = { name -> outDirContainer.trimEnd('/') + "/" + name }
        } else {
            destDirHost = File(ctx.cacheDir, "cloud-export")
            destPathForPlugin = { name -> File(destDirHost, name).absolutePath }
        }

        val plan = ExportPlan(
            scope = scope,
            // 云备份是「全量或不带」——插件那边只有 includeSessions 布尔开关，映射成 -1(全部)/0(不带)
            sessionLimit = if (body.optBoolean("includeSessions", false)) -1 else 0,
            password = if (encrypt) password else "",
            // 插件没带这个字段时按 true（老版本插件的行为就是含主题），带了就听它的。
            // 注意形参名是 includesTheme（ExportPlan 的字段名），JSON 字段才是 includeTheme。
            includesTheme = if (body.has("includeTheme")) body.optBoolean("includeTheme", true) else true,
        )
        val result = runCatching {
            runBlocking { DshConfigBackup.exportArchive(ctx, plan) }
        }.getOrElse { return 500 to error("出包异常：${it.message ?: it.javaClass.simpleName}") }

        if (!result.ok || result.file == null) {
            return 500 to error(result.message.ifEmpty { "出包失败" })
        }
        // 搬到插件指定目录（exportArchive 落在 App 自己的缓存/外部目录；插件要在它的 workDir 里算哈希）
        val src = result.file
        destDirHost.mkdirs()
        val destHost = File(destDirHost, src.name)
        val moved = runCatching {
            if (src.absolutePath != destHost.absolutePath) {
                src.copyTo(destHost, overwrite = true)
                src.delete()
            }
            destHost
        }.getOrElse { return 500 to error("落盘到插件目录失败：${it.message ?: it.javaClass.simpleName}") }

        return 200 to JSONObject()
            .put("ok", true)
            // 回容器路径（插件在容器里读），不是宿主绝对路径
            .put("file", destPathForPlugin(src.name))
            .put("size", moved.length())
            .put("tier", tier)
            .put("encrypted", result.encrypted)
            .toString()
    }

    /** 把插件下载好的整包恢复回本机。请求体：`{file,password}`。 */
    fun restore(ctx: Context, body: JSONObject): Pair<Int, String> {
        val filePath = body.optString("file")
        if (filePath.isEmpty()) return 400 to error("缺少 file")
        // 插件给的多半是容器路径（它下载到自己的 workDir，/root/.dsh/...）。先按原样试（兼容 App
        // 缓存那种宿主绝对路径），够不到再映射到宿主 rootfs 真实路径。
        val direct = File(filePath)
        val zip = if (direct.isFile) direct
            else DshEnv.containerToHost(ctx, filePath)?.takeIf { it.isFile }
                ?: return 400 to error("文件不存在：$filePath")
        val password = body.optString("password")

        val result = runCatching {
            // 走 App 既有的 headless 导入通路：整体加密的包由 App 自己解（同一 DCA1 格式），
            // 纯软件数据 / 含 DSH 分区两种包它都认，rollbackOnError 让中途失败能回滚。
            runBlocking {
                DshConfigBackup.import(
                    ctx = ctx,
                    zip = zip,
                    strategy = DshConfigBackup.STRATEGY_MERGE,
                    rollbackOnError = true,
                    password = password,
                )
            }
        }.getOrElse { return 500 to error("恢复异常：${it.message ?: it.javaClass.simpleName}") }

        return 200 to JSONObject()
            .put("ok", result.ok)
            .put("message", result.message)
            .put("needsRestart", if (result.needsRestart) 1 else 0)
            .toString()
    }

    /**
     * 主题包信息（给云备份面板的「是否包括应用主题」开关用）。
     *
     * `sizeBytes` 是**真打一遍主题包**量出来的（[DshConfigBackup.measureThemeZipBytes]，与导出同一通路、
     * 不落盘），不是估算 —— 开关的默认值、以及用户看到的「当前主题包大小」都得是那个真数。
     *
     * 代价是每次量都要走一遍加密 + 压缩（几十 MB 的字体/音乐主题在百毫秒到秒级），所以这里带
     * [THEME_SIZE_CACHE_MS] 的进程内缓存：插件的云备份面板每次打开都会问一次，不该每次重算。
     * 用户改完主题想立刻看新大小，可带 `force=true`（插件目前不传，缓存过期自然会刷新）。
     *
     * 量不出来（主题读取失败）时 `sizeBytes` 为 -1、`defaultInclude` 仍给 true —— 不能因为量不出来
     * 就把用户的主题悄悄排除在备份之外。
     */
    fun themeInfo(ctx: Context, force: Boolean): Pair<Int, String> {
        val now = System.currentTimeMillis()
        val cached = themeSizeCache
        val size = if (!force && cached != null && now - cached.second < THEME_SIZE_CACHE_MS) {
            cached.first
        } else {
            val measured = runBlocking { DshConfigBackup.measureThemeZipBytes(ctx) }
            themeSizeCache = measured to now
            measured
        }
        val limit = DshConfigBackup.THEME_SIZE_LIMIT_BYTES
        return 200 to JSONObject()
            .put("ok", true)
            .put("exists", size > 0L)
            .put("sizeBytes", size)
            .put("limitBytes", limit)
            .put("defaultInclude", size <= 0L || size <= limit)
            .toString()
    }

    /** 含软件数据的档位 → BackupScope；其余（含纯 DSH 与未知）返回 null。 */
    private fun scopeForTier(tier: String): BackupScope? = when (tier) {
        "app-only" -> BackupScope.APP_ONLY
        "app-dsh" -> BackupScope.BOTH
        "app-dsh-vault" -> BackupScope.BOTH_VAULT
        else -> null
    }

    private fun scopeNeedsVaultPassword(scope: BackupScope): Boolean =
        scope == BackupScope.BOTH_VAULT || scope == BackupScope.DSH_VAULT

    private fun error(msg: String): String = JSONObject().put("ok", false).put("error", msg).toString()
}
