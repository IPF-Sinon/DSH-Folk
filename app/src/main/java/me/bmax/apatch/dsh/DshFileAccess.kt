package me.bmax.apatch.dsh

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import me.bmax.apatch.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/**
 * 手机文件访问的黑白名单策略，直接作用在**容器 bind 挂载**这一层。
 *
 * ## 为什么在挂载层做
 *
 * `/storage/emulated/0` 是**无条件** bind 进容器的（[ContainerRuntime] 的存储绑定），一旦 App 拿到
 * 「所有文件访问」，容器里任何进程（dsh 本体、插件、终端）都能直接读写整棵 `/sdcard`。仅在
 * `DshFsBridge`（受控 HTTP 接口）上拦是**假隔离**——那只是众多入口里的一个，绕过它经 bind 挂载
 * 照样能读相册。真正生效的唯一办法是改挂载本身：
 *
 * - **黑名单**：给每个被禁目录叠一条 bind，用一个空目录（[DshEnv.fsMaskDir]）盖在它上面，容器里
 *   看到的就是个空文件夹（proot/proroot 后加的更具体 guest 路径覆盖前面的整棵树绑定）。
 * - **白名单**（非空时启用）：不再 bind 整棵 `/storage/emulated/0`，改成只 bind 勾选的子目录，
 *   其余一律不映进容器。
 *
 * 挂载在容器**启动那一刻**定死，改名单必须**重启容器**才生效——UI 改完要提示用户重启。
 *
 * ## 规则（用户 2026-09-26 定）
 *
 * - 白名单、黑名单**各自独立**，各自选了目录即视为启用。
 * - 默认黑名单 = [DEFAULT_DENY]（相册类：DCIM / Pictures / Movies / Android/media）。
 *   偏好里**缺失** = 用默认；**显式空数组** = 用户清空了、谁都不禁。
 * - 同一名单内若加了某目录的**上级**，则上级覆盖其下级条目（去重规整，见 [normalize]）。
 * - 两名单同时启用时**黑名单优先**：先按白名单圈定范围，再从中扣掉黑名单命中的部分。
 */
object DshFileAccess {

    /** 默认黑名单：相册类目录（相对 /sdcard）。 */
    val DEFAULT_DENY: List<String> = listOf("DCIM", "Pictures", "Movies", "Android/media")

    /** 宿主共享存储根。 */
    private const val HOST_ROOT = "/storage/emulated/0"

    /**
     * SAF 的 externalstorage 提供器 authority。
     *
     * 只有它给出的 `<卷>:<路径>` document id 能确定**宿主文件系统路径**：主卷的 `primary:` →
     * [HOST_ROOT]，其余卷 → `/storage/<卷>/<路径>`（Android 把第二卷挂在那里，卷名就是冒号前
     * 那一段 —— 见 [hostPathFromTreeUri]）。别的 authority（云盘、相册）即便 document id 长得
     * 一样，也不因为"形状像"就被接受：自家提供器走 [hostPathFromPickerUri] 的第一条分支，
     * 第三方提供器只有在 id 形状本身就是宿主路径证据（`raw:` / 绝对路径）或子项名交叉验证
     * 通过时才接受（见 [hostPathFromPickerUri]）。
     */
    private const val EXT_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** 容器内看到共享存储的两个别名（历史上一直双挂）。 */
    private val GUEST_ALIASES = listOf("/sdcard", "/storage/emulated/0")

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)

    private fun readArray(raw: String?): List<String>? {
        if (raw == null) return null
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.optString(it) }
        }.getOrNull()
    }

    private fun writeArray(list: List<String>): String {
        val a = JSONArray()
        for (s in list) a.put(s)
        return a.toString()
    }

    /**
     * 规整一份名单：去空白、去首尾斜杠、统一分隔符、去重，并让**上级覆盖下级**
     * （若某条目落在另一条目之下，则丢弃这个更深的条目——上级已经覆盖它了）。
     */
    fun normalize(input: List<String>): List<String> {
        val cleaned = input
            .map { it.trim().replace('\\', '/').trim('/') }
            .filter { it.isNotEmpty() }
            .distinct()
        // 保留「不被别的条目覆盖」的那些：a 被 b 覆盖 ⇔ a == b 的子路径（段边界）
        val kept = ArrayList<String>()
        for (a in cleaned) {
            val coveredByOther = cleaned.any { b -> b != a && isUnderOrEqual(a, b) }
            if (!coveredByOther) kept.add(a)
        }
        // 去掉「互为同名」的重复（isUnderOrEqual 对相等为真，上面的 b != a 已排除自身）
        return kept.distinct()
    }

    /** [child] 是否等于 [parent] 或落在其下（按目录段边界，不误伤 Pictures2 这种同前缀兄弟）。 */
    internal fun isUnderOrEqual(child: String, parent: String): Boolean {
        if (child == parent) return true
        return child.startsWith("$parent/")
    }

    /**
     * SAF 目录（`ACTION_OPEN_DOCUMENT_TREE` 选出来的 tree URI）→ **相对 /sdcard** 的相对路径。
     *
     * 名单与工作区映射（主卷那一半）存的都是「相对 /sdcard」的路径（挂载层 bind 的也是宿主
     * /sdcard 下的真实目录），所以把目录选择器从页内那个 `java.io.File` 浏览器换成系统文件
     * 选择器之后，落盘前必须做这一步换算 —— 不然 SAF 的 document id 会被当成路径存进去，
     * 重启容器时 bind 一个不存在的目录。
     *
     * - `content://com.android.externalstorage.documents/tree/primary%3ADownload%2Ffoo`
     *   → `Download/foo`；根（`primary:`）→ `""`。
     * - **第二卷**（SD 卡 / U 盘）不在这里：它不在 /sdcard 这棵树上，走 [hostPathFromTreeUri]。
     * - 别的卷 / 别的提供器（云盘、相册、某个文件管理器自己的提供器）没有确定的宿主文件系统
     *   路径，映射不进去 → null。
     *
     * 判据有两条，缺一不可：
     *
     * 1. **authority 必须是 `com.android.externalstorage.documents`**。只按 document id 形状认
     *    是不够的：云盘/相册提供器也可能回一个 `primary:Pictures` 形状的 id，而那个目录其实是
     *    虚拟的 —— 一旦 /sdcard/Pictures 恰好存在，就会把用户选的"云盘里的 Pictures"当成
     *    /sdcard/Pictures 挂进容器。
     * 2. 映射出来的宿主目录必须**真的存在**。这条同时挡住"提供器给的路径已不存在"与
     *    "bind 一个根本不存在的目录"。
     */
    fun relativeFromTreeUri(uri: Uri?): String? {
        if (uri == null) return null
        if (!EXT_STORAGE_AUTHORITY.equals(uri.authority, ignoreCase = true)) return null
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        // 必须形如 `卷:相对路径`。没有冒号 = 不是共享存储那种 id（`substringBefore` 会把
        // 整串当卷名，于是"primary"这种无冒号的 id 会被当成根 → 落一条错的名单）。
        val sep = docId.indexOf(':')
        if (sep <= 0) return null
        val volume = docId.substring(0, sep)
        if (!volume.equals("primary", ignoreCase = true)) return null
        val rel = docId.substring(sep + 1).replace('\\', '/').trim('/')
        val segs = rel.split('/').filter { it.isNotEmpty() && it != "." }
        // `..` 越界段一律拒（与 normalizeDest 同一条纪律）
        if (segs.any { it == ".." }) return null
        val mapped = segs.joinToString("/")
        val host = if (mapped.isEmpty()) File(HOST_ROOT) else File(HOST_ROOT, mapped)
        return if (host.isDirectory) mapped else null
    }

    /**
     * SAF 目录 → **真实宿主绝对路径**（第二卷：SD 卡 / U 盘）。
     *
     * document id 是 `<卷>:<路径>`，而 Android 把非 `primary` 的卷挂在 `/storage/<卷>`，卷名
     * 就是冒号前那一段 —— 这是 externalstorage 提供器自己的约定。所以
     * `content://com.android.externalstorage.documents/tree/0123-4567%3ADownload`
     * → `/storage/0123-4567/Download`。
     *
     * 这是**唯一**能挂真实第二卷的路：容器里 `/sdcard` 映的始终是主卷（`/storage/emulated/0`），
     * SD 卡不在那棵树里，所以调用方拿到的是宿主绝对路径而不是"相对 /sdcard"的字符串。
     *
     * 拒绝（返回 null）的情形：
     * - 不是 externalstorage 的 tree（别的 authority 的卷名推不出宿主目录）；
     * - `primary:`（主卷走 [relativeFromTreeUri]，它的宿主根是 /sdcard 而不是 /storage/primary）；
     * - 卷名含 `/`、`.` 之类不该进路径的字符，或路径里有 `..`/`.`/空段；
     * - 宿主目录当前不存在（卡没插 / 没挂载 / 没权限看到 → 不 bind 一个不存在的目录；界面会
     *   如实提示，而不是悄悄落一条错路径）。
     */
    fun hostPathFromTreeUri(uri: Uri?): String? {
        if (uri == null) return null
        if (!EXT_STORAGE_AUTHORITY.equals(uri.authority, ignoreCase = true)) return null
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val sep = docId.indexOf(':')
        if (sep <= 0) return null
        val volume = docId.substring(0, sep)
        if (volume.equals("primary", ignoreCase = true)) return null
        // 卷名要拼进路径：只收字母/数字/-/_（不认就拒，绝不把一个能越界的名字拼进去）
        if (volume.isEmpty() || !volume.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
        val rel = docId.substring(sep + 1).replace('\\', '/').trim('/')
        val segs = rel.split('/').filter { it.isNotEmpty() && it != "." }
        if (segs.any { it == ".." }) return null
        val mapped = segs.joinToString("/")
        val host = if (mapped.isEmpty()) File("/storage/$volume") else File("/storage/$volume", mapped)
        return if (host.isDirectory) host.absolutePath else null
    }

    /** 共享存储挂载总开关（默认开）。关＝不挂载 + dsh-fs 也拒绝。 */
    fun mountEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(DshEnv.KEY_STORAGE_MOUNT, true)

    fun setMountEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(DshEnv.KEY_STORAGE_MOUNT, on).apply()
    }

    /**
     * 这个 tree URI 是不是系统 externalstorage 提供器给的。
     *
     * 界面用它把两种"换不出宿主路径"分开：[relativeFromTreeUri] / [hostPathFromTreeUri] 都返回
     * null 时，externalstorage 的目录是"卷/目录此刻不成立"（比如卡没插、目录已不存在），别的
     * authority 才是"这个提供器根本没有本地路径"（云盘 / 相册）。
     */
    internal fun isExternalStorageTree(uri: Uri?): Boolean =
        EXT_STORAGE_AUTHORITY.equals(uri?.authority, ignoreCase = true)

    // ──────────────────────────────────────────────────────────────────────────
    //  其余提供器的 tree URI：自家文档提供器 / raw: 等真实路径形状 / 交叉验证
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 自家文档提供器的 authority：manifest 里 `DshDocumentsProvider` 的
     * `${applicationId}.documents`（与 util 里 DshDocsAccess 用的是同一个算法）。
     */
    private fun ownDocsAuthority(): String = "${BuildConfig.APPLICATION_ID}.documents"

    /**
     * 系统选择器给的 tree URI → 真实宿主绝对路径，覆盖 [relativeFromTreeUri] /
     * [hostPathFromTreeUri] 管不到的那些提供器。三条互斥的路，每条都以"这是宿主文件系统里
     * 真实存在的目录"为**接受前提**：
     *
     * 1. **自家文档提供器**（[ownDocsAuthority]）。`DshDocumentsProvider.getDocIdForFile` 把
     *    id 编成 `ROOT_DOC_ID + <相对 dataDir 的路径>`（根是 `"/"`），所以
     *    `/files/rootfs/root/.dsh` → `context.dataDir/files/rootfs/root/.dsh`。
     *    映射后仍须落在 canonical `dataDir` 内 —— 与提供器自己的 `resolveLexical` 同一条边界。
     * 2. **document id 形状本身就是宿主路径证据**（见 [shapeHostDir]）：`raw:` 前缀、
     *    `/` 开头的绝对形状、`storage/` / `sdcard/` 开头的相对形状。
     * 3. **形状不足为凭**（例如第三方提供器回一个 `primary:Download`）：由 [candidateHostDir]
     *    推出候选，再拿 SAF 那棵树列出的子项名与候选目录 `listFiles()` 的名字交叉验证
     *    （见 [crossCheckedHostDir]）。验证不过就拒 —— 绝不挂一个可能错的目录。
     *
     * 云盘 / 相册这类虚拟文档三条都过不了，调用方据此给「没有本地路径」的提示。
     */
    fun hostPathFromPickerUri(ctx: Context, uri: Uri?): String? {
        if (uri == null) return null
        val authority = uri.authority ?: return null
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null
        if (ownDocsAuthority().equals(authority, ignoreCase = true)) {
            return ownHostDir(ctx, docId)?.path
        }
        shapeHostDir(docId)?.let { return it.path }
        return crossCheckedHostDir(ctx, uri, docId)?.path
    }

    /**
     * 路径段：转 `/`、去首尾 `/`、丢弃空段与 `.`；出现 `..` 就整条拒（与 [normalizeDest]
     * 同一条纪律）。返回拼好的相对路径 —— **空串是合法结果**（表示"就是这个根"），
     * null 才表示形状不合法。
     */
    private fun safeSegments(rel: String): String? {
        val segs = rel.replace('\\', '/').trim('/').split('/')
            .filter { it.isNotEmpty() && it != "." }
        if (segs.any { it == ".." }) return null
        return segs.joinToString("/")
    }

    /**
     * 自家提供器的文档 id → dataDir 下的真实目录。
     *
     * id 是绝对形状（`/` 或 `/files/rootfs/…`），去掉前导 `/` 直接拼到 canonical `dataDir`
     * 下；canonicalize 之后仍须落在 `dataDir` 内（软链也逃不出去），且必须存在、是目录。
     * 提供器自己的 `resolveLexical` 用的就是这条边界 —— 这里不额外放宽也不额外收紧。
     */
    private fun ownHostDir(ctx: Context, docId: String): File? {
        val base = runCatching { ctx.dataDir.canonicalFile }.getOrNull() ?: return null
        val rel = safeSegments(docId) ?: return null
        val target = if (rel.isEmpty()) base else File(base, rel)
        return allowedHostDir(target, listOf(base))
    }

    /**
     * document id 的形状**本身就是**"这条 id 指向真实宿主路径"的证据时，给出宿主目录。
     * 只认这几种线上真实存在的约定，别的一律 null（交给 [crossCheckedHostDir] 去验证，
     * 不在这里猜）：
     *
     * - `raw:/storage/emulated/0/Download`（`com.android.providers.downloads.documents`
     *   的 Downloads root 用的就是这种 id）；
     * - `/storage/…`、`/sdcard/…`（id 直接就是绝对路径）；
     * - `storage/…`、`sdcard/…`（少一个前导 `/` 的同一种形状）。
     */
    private fun shapeHostDir(docId: String): File? {
        val clean = docId.replace('\\', '/')
        val path = when {
            clean.startsWith("raw:") -> clean.removePrefix("raw:")
            clean.startsWith("/") -> clean
            clean.startsWith("storage/") || clean.startsWith("sdcard/") -> "/$clean"
            else -> return null
        }
        // safeSegments 返回空串表示"根"——绝对路径的根（`/`）不是可挂的目录，拒。
        val rel = safeSegments(path) ?: return null
        if (rel.isEmpty()) return null
        return allowedHostDir(File("/$rel"), hostRoots())
    }

    /**
     * 形状不足为凭时的**候选**目录 —— 只按 `<卷>:<相对路径>` 这一种约定推（`primary:` →
     * [HOST_ROOT]，其余卷 → `/storage/<卷>`）。它**不是**接受依据，必须再经
     * [crossCheckedHostDir] 的子项名比对。
     */
    private fun candidateHostDir(docId: String): File? {
        val clean = docId.replace('\\', '/')
        val sep = clean.indexOf(':')
        if (sep <= 0) return null
        val volume = clean.substring(0, sep)
        if (!volume.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
        val rel = safeSegments(clean.substring(sep + 1)) ?: return null
        val base = if (volume.equals("primary", ignoreCase = true)) HOST_ROOT else "/storage/$volume"
        return if (rel.isEmpty()) File(base) else File(base, rel)
    }

    /**
     * 交叉验证：把 SAF 那棵树列出的子项名字，跟候选宿主目录 `listFiles()` 的名字比对。
     *
     * 判据（保守 —— 「比对不通过就拒绝」）：
     * - SAF 列不出子项、或一个都没有 → 不接受（空目录没有可核对的证据）；
     * - SAF 的**每个**子项名都要能在候选目录里找到同名条目（子集关系）；
     * - 提供器若报了这棵树自己的显示名，还要与候选目录名相同（忽略大小写）—— 只多一道
     *   更严的门，不放松任何一条。
     *
     * 残留风险：一个虚拟目录的内容恰好与某个宿主目录同名同子项时会被误认（形状证据那条路
     * 不存在这个问题）。所以候选必须先在 [hostRoots] 白名单里真实存在，才会走到这里。
     */
    private fun crossCheckedHostDir(ctx: Context, uri: Uri, docId: String): File? {
        val candidate = candidateHostDir(docId) ?: return null
        val host = allowedHostDir(candidate, hostRoots()) ?: return null
        safDisplayName(ctx, uri, docId)?.let { name ->
            if (!name.equals(host.name, ignoreCase = true)) return null
        }
        val safNames = safChildNames(ctx, uri, docId)
        if (safNames.isEmpty()) return null
        val hostNames = runCatching { host.listFiles()?.map { it.name }?.toSet() }.getOrNull()
            ?: return null
        return if (safNames.all { it in hostNames }) host else null
    }

    /**
     * 可以挂给容器的**共享存储**宿主根（真实路径白名单）。绝对形状的 id 与交叉验证的候选都
     * 必须落在其中之一：
     *
     * - [HOST_ROOT]（`/storage/emulated/0`，主卷）；
     * - `/storage`（SD 卡 / U 盘卷挂在这里；`/sdcard` 是软链，[allowedHostDir] 先
     *   canonicalize 再比，于是归到 [HOST_ROOT] 那一支）。
     *
     * 自家提供器的 sandbox 根（canonical `dataDir`）**不在这里** —— 它只在 [ownHostDir] 里
     * 单独传：一个第三方 id 不该把本应用的私有目录（shared_prefs、databases 等）挂进容器。
     */
    private fun hostRoots(): List<File> = listOf(File(HOST_ROOT), File("/storage"))

    /**
     * 候选目录 → 通过校验的真实目录：
     * - canonicalize（`/sdcard`、`/storage/self/primary` 这类软链归一到真实路径，也堵住
     *   软链逃逸）；
     * - 必须落在 [roots] 之一内（相等或在其下，按目录段边界）；
     * - 必须存在且是目录。
     *
     * 任一不满足返回 null —— 调用方据此拒绝，而不是 bind 一个可能错的目录。
     */
    private fun allowedHostDir(candidate: File, roots: List<File>): File? {
        val canon = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        val inside = roots.any { root ->
            val r = runCatching { root.canonicalFile }.getOrNull() ?: return@any false
            canon.path == r.path || canon.path.startsWith(r.path + File.separator)
        }
        if (!inside) return null
        return if (canon.isDirectory) canon else null
    }

    /** SAF 那棵树自己的显示名（列不出来 / 提供器没报 → null）。 */
    private fun safDisplayName(ctx: Context, uri: Uri, docId: String): String? {
        val docUri = runCatching { DocumentsContract.buildDocumentUriUsingTree(uri, docId) }
            .getOrNull() ?: return null
        return runCatching {
            ctx.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null }
        }.getOrNull()
    }

    /** SAF 那棵树的直接子项显示名（列不出来 → 空集，交叉验证会因此拒绝）。 */
    private fun safChildNames(ctx: Context, uri: Uri, docId: String): Set<String> {
        val childUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(uri, docId)
        }.getOrNull() ?: return emptySet()
        val names = HashSet<String>()
        runCatching {
            ctx.contentResolver.query(
                childUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    c.getString(0)?.takeIf { it.isNotBlank() }?.let { names.add(it) }
                }
            }
        }
        return names
    }

    /**
     * 某个相对 /sdcard 的路径在当前黑白名单下是否放行——供 [DshFsBridge] 用，使桥的可见范围
     * 与挂载遮罩**语义一致**（这是把「假隔离」补成真隔离的关键：桥不再绕过名单）。
     *
     * 判据（与 [storageBinds] 对齐）：
     * - 命中黑名单（等于或落在某被禁目录之下）→ 拒。
     * - 无白名单 → 其余全放行。
     * - 有白名单：根（空串）放行（供列根，逐项再判）；否则要么落在某白名单目录内/相等、
     *   要么是某白名单目录的祖先（可下钻）才放行。
     */
    fun pathAllowed(ctx: Context, relative: String): Boolean {
        val rel = relative.trim().replace('\\', '/').trim('/')
        val deny = denyDirs(ctx)
        if (deny.any { isUnderOrEqual(rel, it) }) return false
        val allow = allowDirs(ctx)
        if (allow.isEmpty()) return true
        if (rel.isEmpty()) return true
        return allow.any { a -> isUnderOrEqual(rel, a) || isUnderOrEqual(a, rel) }
    }

    /** 当前白名单（已规整）。空 = 未设白名单。 */
    fun allowDirs(ctx: Context): List<String> =
        normalize(readArray(prefs(ctx).getString(DshEnv.KEY_FS_ALLOW_DIRS, null)) ?: emptyList())

    /** 当前黑名单（已规整）。偏好缺失时用 [DEFAULT_DENY]；显式空数组则为空。 */
    fun denyDirs(ctx: Context): List<String> {
        val stored = readArray(prefs(ctx).getString(DshEnv.KEY_FS_DENY_DIRS, null))
        return normalize(stored ?: DEFAULT_DENY)
    }

    fun setAllowDirs(ctx: Context, list: List<String>) {
        prefs(ctx).edit().putString(DshEnv.KEY_FS_ALLOW_DIRS, writeArray(normalize(list))).apply()
    }

    fun setDenyDirs(ctx: Context, list: List<String>) {
        // 写显式数组（哪怕是空）——空数组语义是「用户清空了黑名单」，不能回落到默认
        prefs(ctx).edit().putString(DshEnv.KEY_FS_DENY_DIRS, writeArray(normalize(list))).apply()
    }

    /** 黑名单偏好是否还没被用户动过（用来在 UI 上区分「默认」与「用户清空」）。 */
    fun denyIsDefault(ctx: Context): Boolean =
        prefs(ctx).getString(DshEnv.KEY_FS_DENY_DIRS, null) == null

    /**
     * 组装共享存储的 bind 列表（host, guest），已把黑白名单落进去。顺序即应用顺序：
     * 整棵树/白名单目录在前，遮蔽（空目录盖被禁目录）在后——后者覆盖前者。
     *
     * @param maskPath 空目录的宿主绝对路径（[DshEnv.fsMaskDir]）
     */
    fun storageBinds(ctx: Context, maskPath: String): List<Pair<String, String>> {
        val allow = allowDirs(ctx)
        val deny = denyDirs(ctx)
        val out = ArrayList<Pair<String, String>>()

        if (allow.isEmpty()) {
            // 无白名单：整棵树都映进来，再逐个遮蔽被禁目录
            for (alias in GUEST_ALIASES) {
                out.add(HOST_ROOT to alias)
                for (d in deny) out.add(maskPath to "$alias/$d")
            }
        } else {
            // 有白名单：只映勾选目录（黑名单优先——被黑名单覆盖的白名单目录整个不映）
            for (a in allow) {
                if (deny.any { isUnderOrEqual(a, it) }) continue // a 落在某个被禁目录之下/相等 → 不映
                for (alias in GUEST_ALIASES) {
                    out.add("$HOST_ROOT/$a" to "$alias/$a")
                    // 黑名单若落在这个白名单目录之内，仍要在其内部遮蔽
                    for (d in deny) if (isUnderOrEqual(d, a) && d != a) out.add(maskPath to "$alias/$d")
                }
            }
        }
        return out
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  工作区挂载：把手机存储按自定义映射额外 bind 到 /root/workspace 下
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 一条工作区挂载映射：把宿主目录挂到 `/root/workspace/[dest]`。
     *
     * [src] 有两种形态（[isHostPath] 判）：
     * - **不以 `/` 开头** = 相对 `/sdcard` 的路径（空串 = 整棵 /sdcard）。历史形状，黑白名单与
     *   遮蔽都按同一套相对路径比较，行为一字未改；旧数据全是这一种。
     * - **以 `/` 开头** = 真实宿主绝对路径（第二卷：SD 卡 / U 盘，见 [hostPathFromTreeUri]）。
     *   容器里 `/sdcard` 映的始终是主卷，SD 卡不在那棵树里，所以"相对 /sdcard"对它没有意义 ——
     *   落盘/挂载用的就是这个绝对路径本身；黑白名单（相对 /sdcard 的条目）也不套在它上面。
     */
    data class WsMount(val src: String, val dest: String)

    /** 默认映射：整棵 /sdcard → /root/workspace/sdcard。 */
    val DEFAULT_WS_MOUNTS: List<WsMount> = listOf(WsMount("", "sdcard"))

    /** 这条映射源是不是**真实宿主绝对路径**（第二卷）——不以 `/` 开头的一律按相对 /sdcard 读。 */
    internal fun isHostPath(src: String): Boolean = src.startsWith("/")

    /**
     * 映射源 → 宿主绝对目录。绝对路径（第二卷）原样用；相对路径按 [HOST_ROOT] 推出（空 = 整棵）。
     *
     * bind 组装（[workspaceBinds]）与容器→宿主反查（`DshFileHandoff.guestToHost`）都走这一个
     * 换算 —— 两处各写一份必然有一天对不上（SD 卡那条路就是这么冒出来的）。
     */
    internal fun wsHostDir(src: String): String =
        when {
            isHostPath(src) -> src
            src.isEmpty() -> HOST_ROOT
            else -> "$HOST_ROOT/$src"
        }

    /** 映射源在界面上的显示形态：绝对路径照原样，相对路径写成 `/sdcard[/…]`。 */
    internal fun wsSourceLabel(src: String): String =
        when {
            isHostPath(src) -> src
            src.isEmpty() -> "/sdcard"
            else -> "/sdcard/$src"
        }

    /**
     * 宿主绝对路径若本来就落在**主卷**（[HOST_ROOT] / `/sdcard`）里，折回"相对 /sdcard"的
     * 形态；不在主卷里（第二卷 SD/U 盘、自家 dataDir）返回 null。
     *
     * 为什么要折：[workspaceBinds] 对"绝对宿主路径"那一支**不套黑白名单**（名单条目是相对
     * /sdcard 的，第二卷不在那棵树上，套不上）。第三方提供器回一个
     * `raw:/storage/emulated/0/DCIM` 时，若原样按绝对路径存下去，就等于用一条绝对路径绕过
     * 了黑名单 —— 折回相对形态后，它和用户在页内浏览器里选的同一个目录**行为完全一致**。
     *
     * canonicalize 后再比：`/sdcard`、`/storage/self/primary` 这些软链归一到同一个根。
     * 含空段 / `.` / `..` 的路径返回 null（调用方按原绝对路径继续走既有校验）。
     */
    internal fun relativeUnderHostRoot(raw: String): String? {
        val p = raw.trim().replace('\\', '/')
        if (!p.startsWith("/")) return null
        val root = runCatching { File(HOST_ROOT).canonicalPath }.getOrNull() ?: return null
        val canon = runCatching { File(p).canonicalPath }.getOrNull() ?: return null
        if (canon == root) return ""
        if (!canon.startsWith("$root/")) return null
        val rel = canon.removePrefix("$root/")
        if (rel.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        return rel
    }

    /**
     * 规整一条映射源（读偏好与写偏好都过它）。
     *
     * 绝对路径形态先折回主卷相对形态（见 [relativeUnderHostRoot]），折不动（第二卷 / 自家
     * 文档提供器）再按 [sanitizeHostPath] 校验，**形状不对返回 null —— 这条映射丢掉**，
     * 不把一条越界/畸形的记录 bind 进容器。（整个清单都为空时，[workspaceMounts] 仍按既有
     * 语义回落到 [DEFAULT_WS_MOUNTS] 的"整棵 /sdcard"——那是"没配过映射"的默认值。）
     * 相对形态沿用 [normalize]（同黑白名单条目语义），但额外拒 `..`。
     */
    internal fun normalizeSrc(ctx: Context, raw: String): String? {
        val p = raw.trim().replace('\\', '/')
        if (isHostPath(p)) {
            // 主卷内的绝对路径折回相对形态：绝对形态在 workspaceBinds 里不套黑白名单，
            // 折回去才不会比"用户在页内浏览器里选同一个目录"更宽。
            relativeUnderHostRoot(p)?.let { return normalize(listOf(it)).firstOrNull() ?: "" }
            return sanitizeHostPath(ctx, p)
        }
        // 相对形态也拒 `..`（与 [normalizeDest] 同一条纪律）：[normalize] 只做去空白/去重，
        // 不认越界段 —— 留着它，`HOST_ROOT + "/../…"` 就能跑到 /sdcard 之外
        if (p.split('/').any { it == ".." }) return null
        return normalize(listOf(p)).firstOrNull() ?: ""
    }

    /**
     * 宿主绝对路径的校验/规整，收两种形状，别的绝对路径一律拒：
     * - **第二卷（SD 卡 / U 盘）**：`/storage/<卷>/…`；段里不许有空段、`.`、`..`，卷名只收
     *   字母/数字/`-`/`_`。
     * - **自家文档提供器**映射出来的路径（见 [hostPathFromPickerUri] 第 1 条）：canonical
     *   `dataDir` 本身或它下面的路径 —— 与提供器自己的边界一致。第三方 id 拿不到这一支。
     *
     * 这里**不查目录是否存在**：卡被拔掉/没挂载时它本来就不在，那不该把用户存好的映射抹掉 ——
     * 挂载那一刻再按 `isDirectory` 跳过（见 [workspaceBinds]）。
     */
    internal fun sanitizeHostPath(ctx: Context, raw: String): String? {
        val p = raw.trim().replace('\\', '/')
        if (p.startsWith("/storage/")) {
            val segs = p.trim('/').split('/')
            if (segs.size < 2) return null
            if (segs.any { it.isEmpty() || it == "." || it == ".." }) return null
            val volume = segs[1]
            if (volume.isEmpty() || !volume.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
            return "/" + segs.joinToString("/")
        }
        val base = runCatching { ctx.dataDir.canonicalFile.path }.getOrNull() ?: return null
        if (p == base) return base
        if (!p.startsWith("$base/")) return null
        if (p.trim('/').split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        return p.trimEnd('/')
    }

    /**
     * 规整 dest（工作区下的相对子路径）：转 `/`、去首尾 `/`、丢弃 `.`/`..` 段（禁止越界），
     * 结果为空则回落 `sdcard`。
     */
    internal fun normalizeDest(input: String): String {
        val segs = input.trim().replace('\\', '/').split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
        val joined = segs.joinToString("/")
        return if (joined.isEmpty()) "sdcard" else joined
    }

    /**
     * 当前工作区挂载映射（已规整）。缺失 / 空数组 → [DEFAULT_WS_MOUNTS]。
     *
     * src 按 [normalizeSrc] 规整（相对 /sdcard，或第二卷的真实宿主绝对路径 —— 见 [WsMount]），
     * dest 按 [normalizeDest] 规整；按 dest 去重（同一目的只保留第一条，避免两条映射抢同一
     * 挂载点）。旧数据（src 全是相对路径、没有 `/` 开头）走的分支与以前逐字相同。
     */
    fun workspaceMounts(ctx: Context): List<WsMount> {
        val raw = prefs(ctx).getString(DshEnv.KEY_WS_MOUNTS, null)
        val parsed: List<WsMount>? = if (raw == null) null else runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                // 形状不对的 src（越界的绝对路径等）整条丢掉，不回落到"整棵 /sdcard"
                val src = normalizeSrc(ctx, o.optString("src", "")) ?: return@mapNotNull null
                val dest = normalizeDest(o.optString("dest", ""))
                WsMount(src, dest)
            }
        }.getOrNull()
        val list = parsed?.takeIf { it.isNotEmpty() } ?: DEFAULT_WS_MOUNTS
        val seen = HashSet<String>()
        return list.filter { seen.add(it.dest) }
    }

    fun setWorkspaceMounts(ctx: Context, list: List<WsMount>) {
        val a = JSONArray()
        val seen = HashSet<String>()
        for (m in list) {
            val src = normalizeSrc(ctx, m.src) ?: continue
            val dest = normalizeDest(m.dest)
            if (!seen.add(dest)) continue
            a.put(JSONObject().put("src", src).put("dest", dest))
        }
        prefs(ctx).edit().putString(DshEnv.KEY_WS_MOUNTS, a.toString()).apply()
        DshHostPrompt.writeFacts(ctx.applicationContext)
    }

    /**
     * 组装工作区挂载的 bind 列表（host, guest），已套用黑白名单。子开关关 → 空表。
     *
     * 每条映射 `{src, dest}`（guestBase = `/root/workspace/<dest>`）：
     * - **src 是真实宿主绝对路径**（第二卷 SD/U 盘）：用户点的是这一个具体目录，目录此刻存在
     *   就直接挂在 guestBase（卡不在就跳过）。黑白名单存的是"相对 /sdcard"的条目，套不到另一棵
     *   树上，所以这条分支不做名单比较。
     * - src 是相对 /sdcard 的路径：命中黑名单（等于或落在某被禁目录之下）→ 整条跳过；
     *   **无白名单**：把整个 `/storage/emulated/0[/src]` 映到 guestBase，再把落在 src 内部的
     *   被禁子目录用空目录（[maskPath]）盖住（base 在前、mask 在后覆盖）；
     *   **有白名单**（黑名单优先）：只映「落在 src 内」的白名单目录到 guestBase 下对应位置，
     *   其余一律不进工作区——不因走了工作区这条路就绕过白名单（避免「假隔离」）。
     */
    fun workspaceBinds(ctx: Context, maskPath: String): List<Pair<String, String>> {
        // 2026-10：这一块与「共享存储」合并成一个开关，所以只看总开关 —— 两个开关说的是
        // 同一件事（容器能不能看到手机文件）的两半，分开就一定会出现「总开关关着、工作区
        // 却能看到」这种自相矛盾的状态。
        if (!mountEnabled(ctx)) return emptyList()
        val allow = allowDirs(ctx)
        val deny = denyDirs(ctx)
        val out = ArrayList<Pair<String, String>>()
        for (m in workspaceMounts(ctx)) {
            val src = m.src
            val guestBase = "${DshEnv.WORKSPACE_GUEST}/${m.dest}"
            // 第二卷（SD 卡 / U 盘）：源就是这条真实宿主路径，名单不适用（另一棵树）
            if (isHostPath(src)) {
                if (File(src).isDirectory) out.add(src to guestBase)
                continue
            }
            // src 本身被黑名单覆盖 → 整条不挂
            if (deny.any { isUnderOrEqual(src, it) }) continue
            if (allow.isEmpty()) {
                // 无白名单：整个 src 映进来，再遮蔽落在 src 内部的被禁子目录
                out.add(wsHostDir(src) to guestBase)
                for (d in deny) if (contains(src, d) && d != src) {
                    out.add(maskPath to "$guestBase/${relUnder(d, src)}")
                }
            } else {
                // 有白名单（黑名单优先）：只映「落在 src 内」的白名单目录
                for (a in allow) {
                    if (!contains(src, a)) continue                // a 不在这条映射范围内
                    if (deny.any { isUnderOrEqual(a, it) }) continue // a 被黑名单盖掉
                    val relA = relUnder(a, src)
                    val guest = if (relA.isEmpty()) guestBase else "$guestBase/$relA"
                    out.add(wsHostDir(a) to guest)
                    // a 内部的被禁子目录仍要遮蔽
                    for (d in deny) if (isUnderOrEqual(d, a) && d != a) {
                        out.add(maskPath to "$guest/${relUnder(d, a)}")
                    }
                }
            }
        }
        return out
    }

    /** [base] 是否包含 [child]（base 空串 = /sdcard 根，包含一切）。 */
    private fun contains(base: String, child: String): Boolean =
        base.isEmpty() || isUnderOrEqual(child, base)

    /** [child] 相对 [parent] 的路径（child 落在 parent 内/相等；parent 空 = 相对 /sdcard 根）。 */
    private fun relUnder(child: String, parent: String): String =
        when {
            parent.isEmpty() -> child
            child == parent -> ""
            else -> child.substring(parent.length + 1)
        }

    // ──────────────────────────────────────────────────────────────────────────
    //  共享存储的文件系统能力探测
    // ──────────────────────────────────────────────────────────────────────────

    private const val TAG = "DshFileAccess"

    /** [storageLinkSupported] 的缓存（null = 还没探过）。 */
    @Volatile
    private var storageLinkOk: Boolean? = null

    /**
     * 共享存储（[HOST_ROOT]）是否支持**真硬链接**。
     *
     * 为什么需要单独探一次：[DshRuntime.hardlinkSupported] 只探 **rootfs** 所在文件系统
     * （ext4 → true），于是 proot 不加 `--link2symlink`；但链接能力是**逐挂载点**的。
     * dsh 的 write 工具用 `link(临时文件, 目标)` 发布，而共享存储（sdcardfs/FUSE）不支持
     * 硬链接 —— 一旦把手机存储挂进工作区（[workspaceBinds]），容器内就出现了指向「不支持
     * 硬链接的文件系统」的可写路径，write 工具在那里直接报 `EINVAL: invalid argument, link`。
     *
     * 这是**探测**不是**开关**：proot 的 `--link2symlink` 是全局的，按挂载点开不了，而且它会
     * 把所有 `link()` 改写成符号链接（反而制造悬空链接、破坏 pnpm，见
     * [DshRuntime.linkBecomesSymlink]）。所以这里只把事实告诉用户（UI 显著警告），不改挂载。
     *
     * 无「所有文件访问」权限也**能**探：探针放在 App 专属外部目录（同一 emulated 卷）。只有
     * 连那里都拿不到（外部存储未挂载等）才退回 [HOST_ROOT]；此时探针可能因权限失败而返回
     * false，UI 的警告与权限提示并存，语义仍成立。
     */
    fun storageLinkSupported(ctx: Context): Boolean {
        storageLinkOk?.let { return it }
        synchronized(this) {
            storageLinkOk?.let { return it }
            // 探针放在 App 专属外部目录（同一 emulated 卷、无需「所有文件访问」权限），
            // 避免因根目录不可写而**误报**「不支持」；拿不到时退回共享存储根。
            // 链接能力是**按文件系统**的，探哪儿结论都一样。
            val dir = ctx.getExternalFilesDir(null) ?: File(HOST_ROOT)
            val src = File(dir, ".dshfolk-sdlinkprobe")
            val dst = File(dir, ".dshfolk-sdlinkprobe.hl")
            var ok = false
            var detail = ""
            try {
                src.delete(); dst.delete()
                Files.write(src.toPath(), byteArrayOf('o'.code.toByte(), 'k'.code.toByte()))
                Files.createLink(dst.toPath(), src.toPath())
                ok = dst.isFile && dst.length() == 2L
                if (!ok) detail = "link() 成功但目标不可读"
            } catch (e: Throwable) {
                ok = false
                detail = "${e.javaClass.simpleName}: ${e.message}"
            } finally {
                runCatching { src.delete() }
                runCatching { dst.delete() }
            }
            Log.i(TAG, "共享存储硬链接=$ok${if (detail.isEmpty()) "" else "（$detail）"}")
            storageLinkOk = ok
            return ok
        }
    }

    /** 清掉 [storageLinkSupported] 的缓存，供 UI「重新检测」用。 */
    fun resetStorageLinkProbe() {
        synchronized(this) { storageLinkOk = null }
    }
}
