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

    /** 出一份含软件数据的整包。请求体：`{tier,includeSessions,encrypt,password,outDir}`。 */
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
        val outDir = body.optString("outDir").ifEmpty { File(ctx.cacheDir, "cloud-export").absolutePath }

        val plan = ExportPlan(
            scope = scope,
            sessions = if (body.optBoolean("includeSessions", false)) SessionPick.ALL else SessionPick.NONE,
            password = if (encrypt) password else "",
        )
        val result = runCatching {
            runBlocking { DshConfigBackup.exportArchive(ctx, plan) }
        }.getOrElse { return 500 to error("出包异常：${it.message ?: it.javaClass.simpleName}") }

        if (!result.ok || result.file == null) {
            return 500 to error(result.message.ifEmpty { "出包失败" })
        }
        // 搬到插件指定目录（exportArchive 落在 App 自己的缓存/外部目录；插件要在它的 workDir 里算哈希）
        val src = result.file
        val destDir = File(outDir).apply { mkdirs() }
        val dest = File(destDir, src.name)
        val moved = runCatching {
            if (src.absolutePath != dest.absolutePath) {
                src.copyTo(dest, overwrite = true)
                src.delete()
            }
            dest
        }.getOrElse { return 500 to error("落盘到插件目录失败：${it.message ?: it.javaClass.simpleName}") }

        return 200 to JSONObject()
            .put("ok", true)
            .put("file", moved.absolutePath)
            .put("size", moved.length())
            .put("tier", tier)
            .put("encrypted", result.encrypted)
            .toString()
    }

    /** 把插件下载好的整包恢复回本机。请求体：`{file,password}`。 */
    fun restore(ctx: Context, body: JSONObject): Pair<Int, String> {
        val filePath = body.optString("file")
        if (filePath.isEmpty()) return 400 to error("缺少 file")
        val zip = File(filePath)
        if (!zip.isFile) return 400 to error("文件不存在：$filePath")
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
