package me.bmax.apatch.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import me.bmax.apatch.dsh.DshDownloader
import me.bmax.apatch.dsh.DshSource

/**
 * 应用内更新：多渠道测速 → 下载 → 校验 → 唤起系统安装器。
 *
 * 复用而非重写：
 * - 测速直接用 [DshSource.speedTest]（延迟 + 1MB Range 吞吐 + 「估算下载 100MB
 *   耗时」评分）。它探的是运行时 release 资产，与 APK 同一个仓库、同一批 gh-proxy
 *   代理，结论可以直接迁移；
 * - 下载用 [DshDownloader]，那是从运行时下载器里抽出来的断点续传实现。实测两个代理
 *   对 release 资产都返回 206，所以续传在 APK 上同样有效。
 *
 * 安装走 `ACTION_VIEW` + FileProvider，不做静默安装（那需要 device owner 或系统签名）。
 * **校验不通过绝不安装**：装错包的代价是签名不匹配、之后无法覆盖升级。
 */
object AppUpdater {
    private const val TAG = "AppUpdater"

    /** 下载目录（cacheDir 下，file_paths.xml 的 cache-path 已覆盖）。 */
    private const val DIR = "apk-update"

    sealed interface Phase {
        data object Idle : Phase
        data object Testing : Phase
        data class Downloading(val percent: Int, val speed: String) : Phase
        data object Verifying : Phase
        data class Ready(val file: File) : Phase
        data class Failed(val reason: String) : Phase
    }

    /** 各渠道测速结果，按「估算耗时」升序，第一个即推荐。 */
    suspend fun speedTest(): List<DshSource.SpeedResult> = withContext(Dispatchers.IO) {
        DshSource.speedTest().sortedBy { it.estimatedMs }
    }

    /**
     * 下载并校验。
     *
     * @param source 渠道 id（[DshSource] 的常量）；决定 URL 前缀
     * @return 校验通过的文件，失败返回 null（原因通过 [onPhase] 报出）
     */
    suspend fun downloadAndVerify(
        ctx: Context,
        status: UpdateChecker.Status,
        source: String,
        onPhase: (Phase) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        if (status.apkUrl.isEmpty()) {
            onPhase(Phase.Failed(ctx.getString(me.bmax.apatch.R.string.update_no_asset)))
            return@withContext null
        }
        if (status.sha256.isEmpty()) {
            // 没有校验值就不装：这是刻意的硬拒绝，不是保守
            onPhase(Phase.Failed(ctx.getString(me.bmax.apatch.R.string.update_no_checksum)))
            return@withContext null
        }

        val dir = File(ctx.cacheDir, DIR).apply { mkdirs() }
        val target = File(dir, status.apkName.ifEmpty { "update.apk" })

        // 已经下好并校验通过的就别再下一遍（用户可能只是关掉对话框又打开）
        if (target.isFile && DshDownloader.sha256(target).equals(status.sha256, ignoreCase = true)) {
            onPhase(Phase.Ready(target))
            return@withContext target
        }

        val prefix = DshSource.proxyPrefix(source)
        val candidates = listOfNotNull(
            if (prefix.isNotEmpty() && status.apkUrl.startsWith("https://github.com/")) {
                prefix + status.apkUrl
            } else {
                null
            },
            status.apkUrl,
        ).distinct()

        for ((i, url) in candidates.withIndex()) {
            Log.i(TAG, "downloading [${i + 1}/${candidates.size}] $url")
            onPhase(Phase.Downloading(0, "—"))
            val ok = DshDownloader.download(
                url = url,
                target = target,
                expectedSize = status.apkSize,
                onLog = { Log.i(TAG, it) },
                onProgress = { p ->
                    onPhase(Phase.Downloading(p.percent, DshDownloader.formatSpeed(p.speedBytesPerSec)))
                },
            )
            if (!ok) continue

            onPhase(Phase.Verifying)
            val actual = DshDownloader.sha256(target)
            if (actual.equals(status.sha256, ignoreCase = true)) {
                onPhase(Phase.Ready(target))
                return@withContext target
            }
            // 校验不过说明这份文件不可信 —— 删掉，换下一个源重下，
            // 留着的话续传会从一个错误文件的末尾接着写
            Log.w(TAG, "sha256 mismatch: expected=${status.sha256} actual=$actual")
            runCatching { target.delete() }
        }

        onPhase(Phase.Failed(ctx.getString(me.bmax.apatch.R.string.update_verify_failed)))
        null
    }

    /** 有没有「安装未知来源应用」的许可。 */
    fun canInstall(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** 跳到系统的「安装未知应用」授权页。 */
    fun requestInstallPermission(ctx: Context) {
        runCatching {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(android.net.Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        }.onFailure { Log.e(TAG, "cannot open install-permission settings", it) }
    }

    /** 唤起系统安装器。 */
    fun install(ctx: Context, apk: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        true
    }.getOrElse {
        Log.e(TAG, "install intent failed", it)
        false
    }
}
