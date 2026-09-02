package me.bmax.apatch.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import me.bmax.apatch.BuildConfig

/**
 * 直接把「数据目录」的持久化 URI 权限授给指定文件管理器，绕开系统选择器。
 *
 * ## 为什么需要绕
 *
 * 正常流程是文件管理器发 `ACTION_OPEN_DOCUMENT_TREE`，用户挑中 DSH-Folk 这个 root，
 * documentsui 把 tree URI 连同 `FLAG_GRANT_*` 一起 `setResult` 回去，系统在
 * `ActivityTaskManagerService.collectGrants` 里替 documentsui 发放授权，
 * 文件管理器再 `takePersistableUriPermission` 把它持久化。
 *
 * 但发放那一步会走 `UriGrantsManagerService.checkGrantUriPermissionUnlocked`，
 * 开头有一段：
 *
 * ```java
 * final int callingAppId = UserHandle.getAppId(callingUid);
 * if ((callingAppId == SYSTEM_UID) || (callingAppId == ROOT_UID)) {
 *     if ("com.android.settings.files".equals(...) || ...) {
 *         // 豁免名单：settings 裁剪头像、开源许可页
 *     } else {
 *         Slog.w(TAG, "For security reasons, the system cannot issue a Uri permission grant …");
 *         return -1;   // 授权被静默丢弃
 *     }
 * }
 * ```
 *
 * 这里的 `callingUid` 是 documentsui 的 uid。绝大多数 ROM 上它是普通应用 uid
 * （例如 10076），不受影响；但在某些定制/模拟器镜像里 documentsui 被塞进了
 * `android.uid.system`（uid 1000，与 settings、packageinstaller 共享），
 * 于是这个分支命中、授权被丢掉 —— **发放时不报错**，等到文件管理器
 * `takePersistableUriPermission` 才炸：
 *
 * ```
 * java.lang.SecurityException: No persistable permission grants found for UID 10055
 *   and Uri content://top.funcun.dshfolk.documents/tree/…
 * ```
 *
 * 这是那台 ROM 的问题，应用侧改不动系统。但**应用自己**发放同一份授权是允许的：
 * 那时 `callingUid` 是 DSH-Folk 自己（普通 uid），不触发上面的分支；provider 又是
 * 自己的（`pi.applicationInfo.uid == callingUid`，`checkHoldingPermissions…` 直接
 * 返回 true），`grantUriPermissions="true"` 也满足，于是授权真正落库、
 * `UriPermission.persistableModeFlags` 置上 READ|WRITE，文件管理器随后的
 * `takePersistableUriPermission` 就能成功。
 *
 * ## 授的是哪个 URI
 *
 * 必须是文件管理器随后会 take 的**那一个**：`takePersistableUriPermission` 只做
 * 精确查找（`findUriPermissionLocked(uid, GrantUri(uri, 0))` 与 `…(uri, PREFIX)`），
 * 不做路径前缀匹配。MT 管理器的文档流程是「在侧栏选中你的应用」后点选择，
 * 也就是 root 文档，对应 [DocumentsContract.buildTreeDocumentUri] + root docId。
 *
 * 同时带上 `FLAG_GRANT_PREFIX_URI_PERMISSION`：树里每个子文档 URI
 * （`…/tree/%2F/document/<id>`）靠前缀匹配这条授权，少了它只有树根本身能访问。
 *
 * ## 安全
 *
 * 这等于把应用私有目录的读写权交给另一个应用，**只能由用户明确点击触发**，
 * 且目标包名必须在 [MT_PACKAGES] 里、并且真的装着。调用方负责先向用户说明。
 */
object DshDocsAccess {

    /** MT 管理器的包名（正式版 / 内测版）。 */
    val MT_PACKAGES = listOf(
        "bin.mt.plus",
        "bin.mt.plus.canary",
    )

    /** 一个候选文件管理器。 */
    data class Candidate(val packageName: String, val label: String)

    /** 已安装的、可授权的文件管理器。 */
    fun installedCandidates(ctx: Context): List<Candidate> {
        val pm = ctx.packageManager
        return MT_PACKAGES.mapNotNull { pkg ->
            runCatching {
                @Suppress("DEPRECATION")
                val info = pm.getApplicationInfo(pkg, 0)
                Candidate(pkg, pm.getApplicationLabel(info).toString())
            }.getOrNull()
        }
    }

    /** 数据目录 root 的 tree URI —— 文件管理器会 take 的就是它。 */
    fun rootTreeUri(): Uri = DocumentsContract.buildTreeDocumentUri(authority(), ROOT_DOC_ID)

    /**
     * 把 root tree URI 的读写 + 可持久化 + 前缀权限授给 [packageName]。
     *
     * @return 成功与否；失败原因写进日志（几乎只可能是包不存在）。
     */
    fun grant(ctx: Context, packageName: String): Boolean {
        if (packageName !in MT_PACKAGES) {
            Log.w(TAG, "refusing to grant to unlisted package: $packageName")
            return false
        }
        return runCatching {
            ctx.grantUriPermission(packageName, rootTreeUri(), FLAGS)
            true
        }.getOrElse {
            Log.w(TAG, "grantUriPermission to $packageName failed", it)
            false
        }
    }

    /**
     * 撤销之前授给这些文件管理器的权限。
     *
     * 用**带包名**的重载（minSdk 26 就有）：无参版本会撤掉这个 URI 上**所有**来源的
     * 授权，包括用户通过系统选择器正常授出去的那份，属于连坐。
     *
     * @return 是否至少撤掉一个目标。
     */
    fun revokeAll(ctx: Context): Boolean {
        var any = false
        for (pkg in MT_PACKAGES) {
            runCatching { ctx.revokeUriPermission(pkg, rootTreeUri(), FLAGS_ACCESS) }
                .onSuccess { any = true }
                .onFailure { Log.w(TAG, "revokeUriPermission($pkg) failed", it) }
        }
        return any
    }

    private fun authority(): String = "${BuildConfig.APPLICATION_ID}.documents"

    private const val TAG = "DshDocsAccess"

    /**
     * root 文档的 documentId，必须与 `DshDocumentsProvider.ROOT_DOC_ID` 一致。
     *
     * 不能是空串：空路径段会被 `Uri.getPathSegments()` 丢掉，
     * `DocumentsProvider` 内建的 `UriMatcher` 随之全部错位。
     */
    private const val ROOT_DOC_ID = "/"

    private const val FLAGS_ACCESS =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    private const val FLAGS = FLAGS_ACCESS or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
}
