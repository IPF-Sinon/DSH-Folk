package me.bmax.apatch.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import me.bmax.apatch.BuildConfig

/**
 * 直接把「数据目录」的持久化 URI 权限授给用户勾选的文件管理器，绕开系统选择器那一步发放。
 *
 * ## 为什么需要这条通道（缺陷 ROM）
 *
 * 正常流程是文件管理器发 `ACTION_OPEN_DOCUMENT_TREE`，用户挑中 DSH-Folk 这个 root，
 * documentsui 把 tree URI 连同 `FLAG_GRANT_*` 一起 `setResult` 回去，系统在
 * `ActivityTaskManagerService.collectGrants` 里替 documentsui 发放授权，
 * 文件管理器再 `takePersistableUriPermission` 把它持久化。
 *
 * 但发放那一步会走 `UriGrantsManagerService.checkGrantUriPermissionUnlocked`（AOSP
 * android-15.0.0_r1 行 1202-1216），开头有一段：
 *
 * ```java
 * // Bail early if system is trying to hand out permissions directly …
 * final int callingAppId = UserHandle.getAppId(callingUid);
 * if ((callingAppId == SYSTEM_UID) || (callingAppId == ROOT_UID)) {
 *     if ("com.android.settings.files".equals(...) || ...) {
 *         // 豁免：settings 裁剪头像、开源许可页
 *     } else {
 *         Slog.w(TAG, "For security reasons, the system cannot issue a Uri permission grant …");
 *         return -1;   // 授权被静默丢弃
 *     }
 * }
 * ```
 *
 * 这里的 `callingUid` 是 documentsui 的 uid。多数 ROM 上它是普通应用 uid（例如 10076），
 * 不受影响；但在部分定制/模拟器镜像里 documentsui 被塞进 `android.uid.system`（uid 1000），
 * 于是这个分支命中、授权被丢掉 —— **发放时不报错**，等到文件管理器
 * `takePersistableUriPermission` 才炸：
 *
 * ```
 * java.lang.SecurityException: No persistable permission grants found for UID 10055
 *   and Uri content://top.funcun.dshfolk.documents/tree/…
 * ```
 *
 * 这是那台 ROM 的问题，应用侧改不动系统。但**应用自己**发放同一份授权是允许的：那时
 * `callingUid` 是 DSH-Folk 自己（普通 uid），不触发上面的分支；provider 又是自己的
 * （`pi.applicationInfo.uid == callingUid`，`checkHoldingPermissions…` 直接返回 true），
 * `grantUriPermissions="true"` 也满足，于是授权真正落库、
 * `UriPermission.persistableModeFlags` 置上 READ|WRITE，文件管理器随后的
 * `takePersistableUriPermission` 就能成功。
 *
 * ## 候选名单按「能力」算，不按厂商包名硬编码
 *
 * 三类并集：`ACTION_OPEN_DOCUMENT_TREE` 的处理器（能选文件夹 —— 系统文件管理器在这一类）、
 * `ACTION_VIEW` + 目录 MIME 的处理器（能打开文件夹）、以及 [MT_PACKAGES]（MT 管理器的
 * 「添加本地存储」是自己去调系统选择器，不声明上面那两个 intent-filter，按能力查不到它）。
 * 默认勾选「能选文件夹且是系统应用」的那一个（就是系统文件管理器）与 MT 管理器。
 *
 * 这三类之外的应用不给授（[grant] 会再查一遍名单）：一次误勾等于把整个私有目录交出去，
 * 名单是这条通道唯一的安全边界。
 *
 * ## 授的是哪个 URI
 *
 * 必须是文件管理器随后会 take 的**那一个**：`takePersistableUriPermission` 只做精确查找
 * （`findUriPermissionLocked(uid, GrantUri(uri, 0))` 与 `…(uri, PREFIX)`），不做路径前缀匹配。
 * MT 管理器的文档流程是「在侧栏选中你的应用」后点选择，也就是 root 文档，对应
 * [DocumentsContract.buildTreeDocumentUri] + root docId。
 *
 * 同时带上 `FLAG_GRANT_PREFIX_URI_PERMISSION`：树里每个子文档 URI
 * （`…/tree/%2F/document/<id>`）靠前缀匹配这条授权，少了它只有树根本身能访问。
 *
 * ## 安全
 *
 * 一次授权等于把应用私有目录（含容器 rootfs）的读写权交给另一个应用。只能由用户在弹窗里
 * 明确勾选并点「授权」触发。[grantedPackages] 记下授过谁，[revokeAll] 只撤这些包 —— 不用
 * 无参版本的 `revokeUriPermission`，那会连用户通过系统选择器正常授出去的那份一起撤掉。
 */
object DshDocsAccess {

    /** MT 管理器的包名（正式版 / 内测版）。 */
    val MT_PACKAGES = listOf(
        "bin.mt.plus",
        "bin.mt.plus.canary",
    )

    /** 一个可授权的文件管理器。 */
    data class Candidate(
        val packageName: String,
        val label: String,
        /** 默认勾选：系统文件管理器（能选文件夹的系统应用）与 MT 管理器。 */
        val defaultOn: Boolean,
    )

    /**
     * 已安装的、可授权的文件管理器。
     *
     * 顺序：默认勾选的排前面，然后按名称排 —— 弹窗里前几个就是最可能要选的那个。
     */
    fun candidates(ctx: Context): List<Candidate> {
        val pm = ctx.packageManager
        val found = LinkedHashMap<String, String>()
        val defaults = LinkedHashSet<String>()

        fun collect(intent: Intent, byDefault: (String) -> Boolean) {
            @Suppress("DEPRECATION")
            val handlers = runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
            for (handler in handlers) {
                val pkg = handler.activityInfo?.packageName ?: continue
                if (pkg == ctx.packageName) continue
                if (found.putIfAbsent(pkg, labelOf(pm, pkg)) == null && byDefault(pkg)) {
                    defaults += pkg
                }
            }
        }

        // 能选文件夹的（系统文件管理器在这一类），能打开文件夹的，以及按包名兜底的 MT
        collect(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)) { isSystemApp(pm, it) }
        collect(Intent(Intent.ACTION_VIEW).setType(DocumentsContract.Document.MIME_TYPE_DIR)) { false }
        for (pkg in MT_PACKAGES) {
            if (installed(pm, pkg)) {
                defaults += pkg
                found.putIfAbsent(pkg, labelOf(pm, pkg))
            }
        }

        return found.map { (pkg, label) -> Candidate(pkg, label, pkg in defaults) }
            .sortedWith(compareBy({ !it.defaultOn }, { it.label }))
    }

    /** 数据目录 root 的 tree URI —— 文件管理器会 take 的就是它。 */
    fun rootTreeUri(): Uri = DocumentsContract.buildTreeDocumentUri(authority(), ROOT_DOC_ID)

    /** 本对象记录下来的、授过权的包名（用户撤销前一直有效）。 */
    fun grantedPackages(ctx: Context): List<String> =
        prefs(ctx).getStringSet(KEY_GRANTED, emptySet()).orEmpty().sorted()

    /**
     * 把 root tree URI 的读写 + 可持久化 + 前缀权限授给 [packageName]。
     *
     * @return 成功与否；包不在候选名单里、或系统拒绝时返回 false（原因写日志）。
     */
    fun grant(ctx: Context, packageName: String): Boolean {
        if (packageName == ctx.packageName || candidates(ctx).none { it.packageName == packageName }) {
            Log.w(TAG, "refusing to grant to a package outside the file-manager candidates: $packageName")
            return false
        }
        return runCatching {
            ctx.grantUriPermission(packageName, rootTreeUri(), FLAGS)
            remember(ctx, packageName)
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
        val granted = grantedPackages(ctx)
        if (granted.isEmpty()) return false
        var any = false
        for (pkg in granted) {
            runCatching { ctx.revokeUriPermission(pkg, rootTreeUri(), FLAGS_ACCESS) }
                .onSuccess { any = true }
                .onFailure { Log.w(TAG, "revokeUriPermission($pkg) failed", it) }
        }
        forget(ctx, granted)
        return any
    }

    private fun remember(ctx: Context, pkg: String) {
        val next = (grantedPackages(ctx) + pkg).distinct().toSet()
        prefs(ctx).edit().putStringSet(KEY_GRANTED, next).apply()
    }

    private fun forget(ctx: Context, pkgs: Collection<String>) {
        val next = grantedPackages(ctx).filterNot { it in pkgs }.toSet()
        prefs(ctx).edit().putStringSet(KEY_GRANTED, next).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun labelOf(pm: PackageManager, pkg: String): String =
        runCatching {
            @Suppress("DEPRECATION")
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)

    private fun isSystemApp(pm: PackageManager, pkg: String): Boolean =
        runCatching {
            @Suppress("DEPRECATION")
            val flags = pm.getApplicationInfo(pkg, 0).flags
            (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        }.getOrDefault(false)

    private fun installed(pm: PackageManager, pkg: String): Boolean =
        runCatching {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(pkg, 0)
            true
        }.getOrDefault(false)

    private fun authority(): String = "${BuildConfig.APPLICATION_ID}.documents"

    private const val TAG = "DshDocsAccess"
    private const val PREFS = "dsh_docs_access"
    private const val KEY_GRANTED = "granted_packages"

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
