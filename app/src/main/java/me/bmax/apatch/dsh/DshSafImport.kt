package me.bmax.apatch.dsh

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「用 SAF 把内容读出来，复制进工作区」——**与挂载完全无关的一条独立的路**。
 *
 * ## 为什么必须有这条路
 *
 * 有一类第三方文档提供器（Termux 是最典型的一家）的 document id **就是宿主真实路径**
 * （`/data/data/com.termux/files/home`），我们认得出、也能推出候选路径 —— 但那个目录是
 * **别的应用的私有数据**（0700），本应用与容器（同一 UID）**读不到**（真机诊断里
 * `candidateState: exists=false dir=false readable=false`）。而 SAF 那棵树反而**列得出内容**：
 * 因为用户给过我们那棵树的 URI 授权，提供器愿意把内容交给我们。
 *
 * 于是对这类提供器「bind 真实路径」**永远不可能成功**：能不能挂取决于**文件系统权限**，
 * 而内容能不能读取决于**SAF 授权**，两者不是一回事。唯一通用的出路是把内容**经由 SAF
 * 读出来、落到容器看得见的地方**，也就是这里做的事（用户 2026-10 定的方向）。
 *
 * ## 落到哪里，凭什么一定是容器看得见的
 *
 * 容器是 proot/proroot，`guest /` = 宿主 `DshEnv.rootfs(ctx)`
 * （`ContainerRuntime.Proot.baseArgv` 的 `--rootfs=` / `Proroot` 的 `-r`，由
 * `DshRuntime` 传入 `DshEnv.rootfs`），而 [ContainerRuntime.BINDS] 里没有任何一条 bind 盖住
 * `/root`。所以容器里的 `/root/workspace/<名字>` 就是宿主
 * `<rootfs>/root/workspace/<名字>` —— 这里用 [DshEnv.containerToHost] 按**容器路径**换算，
 * 不自己拼字符串（同一处换算被 `DshFileHandoff.guestToHost` 的第 3 条分支用着：凡是不落在
 * 工作区挂载点、也不落在共享存储别名下的容器路径，都落回 rootfs 内）。
 *
 * **例外**：工作区挂载（`/root/workspace/<dest>`）会把某个 dest **遮住**（bind）。若我们挑的
 * 名字正好等于某条映射的**单段** dest，写进去的文件在容器里会被那条 bind 盖掉、根本看不见。
 * 所以取名时把那些 dest 也当成"已被占用"（见 [uniqueName]）。
 *
 * ## 纪律（用户 2026-10 定）
 *
 * - **只读 SAF，不碰挂载**：不改黑白名单、不改工作区映射（`WsMount`）、不改
 *   `relativeFromTreeUri` / `hostPathFromTreeUri`、不碰挂载判定本身；
 * - **一次性复制，不是实时同步**：复制之后手机那边的改动不会跟过来，结果里要明说；
 * - **不静默覆盖**：目标目录已存在（或与挂载 dest 撞名）就换名字（`名字 (2)`）；
 * - **有上限**：条目数 / 累计字节 / 目录深度 / 剩余空间四条，撞上就停在半路并把原因报回去
 *   —— 用户误选一个巨大目录不该把手机填满；
 * - **单个文件失败不影响其余**：按"权限 / IO / 其它"计数并留下**首条**原因，继续往下走；
 * - **真的能取消**：复制循环里逐块 [currentCoroutineContext].ensureActive()，`Job.cancel()`
 *   立刻在下一个块边界停下，流由 `use` 关闭。
 * - **不随页面生死**：任务与状态都挂在本对象（`SupervisorJob` + `Dispatchers.IO`）上，界面只
 *   [state] 观察它。转屏、离开设置页再回来，进度与摘要都还在（[start] / [cancel] / [dismiss]）。
 */
object DshSafImport {

    private const val TAG = "DshSafImport"

    /**
     * 最多处理多少个条目（文件 + 目录）。5000 已经远超"拿来读的目录"的正常规模（Termux 的
     * home 通常是几百项，含 node_modules 的工程目录动辄几万项 —— 那种就该由用户在容器里
     * `git clone`/`pnpm install`，而不是从手机复制手机上的那份）。
     */
    const val MAX_ENTRIES = 5000

    /** 累计复制字节上限（1 GiB）。 */
    const val MAX_BYTES = 1L shl 30

    /** 目录递归最大深度（层）。 */
    const val MAX_DEPTH = 32

    /** 目标所在分区剩余空间低于这个值（256 MiB）就停 —— 别把手机的可用空间吃光。 */
    const val MIN_FREE_AFTER = 256L shl 20

    /** 流式复制的缓冲区（不整份读进内存：单文件可能几百 MB）。 */
    private const val BUF_SIZE = 64 * 1024

    /** 目标目录名的长度上限（文件系统限制 + 免得界面被一行超长名字顶满）。 */
    private const val MAX_NAME = 64

    /** 树没有显示名（或清洗后为空）时的回落名字。 */
    private const val FALLBACK_NAME = "imported"

    /** 撞上上限 / 空间不足时的停止原因（[Result.stop]；[Stop.None] = 正常跑完）。 */
    enum class Stop { None, Entries, Bytes, LowSpace }

    /**
     * 进度快照。[destName] / [guestPath] 在一次复制里不变（结果摘要要用，所以放在这里 ——
     * 取消时调用方只能靠最后一份快照还原"复制到哪儿了"）。
     */
    data class Progress(
        val destName: String,
        val guestPath: String,
        val copied: Int,
        val skipped: Int,
        val failed: Int,
        val current: String,
        val firstError: String?,
    )

    /**
     * 一次复制的结局。
     *
     * - [cancelled]：用户按了取消（计数是**停下时**已经完成的部分，不是失败）；
     * - [error]：整体没跑起来（拿不到 tree id、定位不到工作区、建目录失败）—— 这时各计数为 0；
     * - [stop]：撞上上限停在半路；[depthHit]：有更深的子目录没复制（不影响别的分支）；
     * - [firstError]：第一条单文件/单目录失败的原因（路径 + 异常），后面还有失败也不覆盖它。
     */
    data class Result(
        val destName: String,
        val guestPath: String,
        val copied: Int,
        val skipped: Int,
        val failed: Int,
        val firstError: String?,
        val stop: Stop,
        val depthHit: Boolean,
        val cancelled: Boolean,
        val error: String?,
    )

    /** 一个待展开的目录：SAF 侧的 document id + 宿主侧的目标目录 + 深度 + 相对路径（进度显示）。 */
    private class DirNode(val docId: String, val dir: File, val depth: Int, val rel: String)

    /** SAF 的一条子项：document id / 显示名 / 是不是目录（按 `COLUMN_MIME_TYPE`）。 */
    private class ChildDoc(val docId: String, val name: String, val dir: Boolean)

    /**
     * 复制任务的状态。**它挂在本对象上、不挂页面** —— 所以转屏、离开设置页再回来，
     * 进度与最终摘要都还在（挂在页面作用域上的话，转个屏就把正在跑的复制丢掉一半、
     * 连结果都没有）。
     */
    sealed class State {
        /** 没有任务（也从没跑过，或用户把上一次的摘要关掉了）。 */
        object Idle : State()

        /** 正在复制。[Progress] 与 [copyTree] 报出来的那份是同一种快照。 */
        data class Running(val progress: Progress) : State()

        /** 跑完了（含取消与整体失败）—— 结果留在 [State.Done] 里，用户关掉才回 [Idle]。 */
        data class Done(val result: Result) : State()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<State>(State.Idle)

    /** 界面观察这个（`collectAsStateWithLifecycle`）；离开页面只是停止观察，任务照跑。 */
    val state: StateFlow<State> = _state.asStateFlow()

    /** 跨线程读写：`start` 会写它，`cancel`/`dismiss`/界面只读。 */
    @Volatile
    private var job: Job? = null

    /** 最后一份进度快照，取消时用它还原"复制到哪儿了"。 */
    @Volatile
    private var last: Progress? = null

    /**
     * 起一次复制（**不阻塞调用方**：立刻返回，进度走 [state]）。
     *
     * 已经有任务在跑时直接忽略（重复点按钮不会起第二份）；跑完后要再跑一次得先 [dismiss]，
     * 或者直接再调 [start] —— [State.Done] 不是"锁"。
     */
    fun start(ctx: Context, treeUri: Uri) {
        val appCtx = ctx.applicationContext
        synchronized(this) {
            if (job?.isActive == true) return
            last = null
            _state.value = State.Running(
                Progress(
                    destName = "",
                    guestPath = "",
                    copied = 0,
                    skipped = 0,
                    failed = 0,
                    current = "",
                    firstError = null,
                )
            )
            job = scope.launch {
                try {
                    val result = copyTree(appCtx, treeUri) { p ->
                        last = p
                        _state.value = State.Running(p)
                    }
                    _state.value = State.Done(result)
                } catch (e: CancellationException) {
                    // 取消不是失败：[cancelled] 用最后一份快照还原已复制的那部分。
                    // 这里不再调任何挂起函数，所以即使协程已被取消也照样能落状态。
                    _state.value = State.Done(cancelled(last))
                    throw e
                } catch (e: Exception) {
                    _state.value = State.Done(failed("${e.javaClass.simpleName}: ${e.message ?: ""}"))
                }
            }
        }
    }

    /** 取消当前任务（没有在跑就什么也不做）。取消后 [state] 会变成 [State.Done]（`cancelled=true`）。 */
    fun cancel() {
        job?.cancel()
    }

    /**
     * 关掉结果摘要（回到 [State.Idle]）。**正在跑时不许关** —— 那会让界面上的进度凭空消失，
     * 而任务还在写盘。
     */
    fun dismiss() {
        if (job?.isActive == true) return
        _state.value = State.Idle
    }

    /**
     * 把 [treeUri] 那棵树的内容**一次性**复制到 `/root/workspace/<名字>`（名字见 [uniqueName]）。
     *
     * 全程在 [Dispatchers.IO] 上跑（**不占 UI 线程**：哪怕调用方从 Main 调进来也不会卡界面）。
     * [onProgress] 在 IO 线程上被调用（**不是挂起函数**：调用方只该落状态，别在这儿做别的事）。
     *
     * 取消（`Job.cancel()`）会以 [CancellationException] 抛出：这是**取消语义**，不是失败，
     * 调用方应捕获它并用最后一份 [Progress] 还原结果（见 [DshSafImport.cancelled]）。
     */
    suspend fun copyTree(
        ctx: Context,
        treeUri: Uri,
        onProgress: (Progress) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        if (treeDocId.isNullOrBlank()) return@withContext failed("no tree document id in $treeUri")

        val root = workspaceRoot(ctx)
            ?: return@withContext failed("cannot resolve ${DshEnv.WORKSPACE_GUEST} to a host path")
        if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) {
            return@withContext failed("cannot create ${root.absolutePath}")
        }

        val treeName = runCatching { treeDisplayName(ctx, treeUri, treeDocId) }.getOrNull()
        val name = uniqueName(root, sanitizeDirName(treeName), mountedDestNames(ctx))
        val dest = File(root, name)
        if (!dest.isDirectory && !dest.mkdirs() && !dest.isDirectory) {
            return@withContext failed("cannot create ${dest.absolutePath}")
        }
        val guestPath = "${DshEnv.WORKSPACE_GUEST}/$name"
        Log.i(TAG, "import start: $guestPath (host ${dest.absolutePath}), provider=${treeUri.authority}")

        var copied = 0
        var skipped = 0
        var failedCount = 0
        var firstError: String? = null
        var stop = Stop.None
        var depthHit = false
        var bytes = 0L
        var entries = 0

        val queue = ArrayDeque<DirNode>()
        queue.addLast(DirNode(treeDocId, dest, 0, ""))
        var stopNow = false

        while (queue.isNotEmpty() && !stopNow) {
            currentCoroutineContext().ensureActive()
            val node = queue.removeFirst()
            val children = try {
                listChildren(ctx, treeUri, node.docId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 这一层列不出来（提供器不支持 / 授权没了 / IO 错）：记一笔，别的目录继续
                failedCount++
                if (firstError == null) firstError = describe("list", node.rel, e)
                continue
            }
            for (child in children) {
                currentCoroutineContext().ensureActive()
                entries++
                if (entries > MAX_ENTRIES) {
                    stop = Stop.Entries
                    stopNow = true
                    break
                }
                if (bytes >= MAX_BYTES) {
                    stop = Stop.Bytes
                    stopNow = true
                    break
                }
                // 剩余空间底线：**开始之前不拦**（手机上只剩几百 MB 时，复制几个小文件是正当
                // 需求，不该被一条"少于 256 MB 就拒绝"的规则挡掉）；一旦开始往外写，就守着它。
                if (entries > 1 && freeBytes(dest) in 0 until MIN_FREE_AFTER) {
                    stop = Stop.LowSpace
                    stopNow = true
                    break
                }
                val childName = sanitizeEntryName(child.name)
                if (childName == null) {
                    // 名字清洗后什么都不剩（纯控制字符之类）：跳过并计数，不猜一个名字出来
                    skipped++
                    continue
                }
                val rel = if (node.rel.isEmpty()) childName else "${node.rel}/$childName"
                if (child.dir) {
                    if (node.depth + 1 > MAX_DEPTH) {
                        // 太深的子目录不展开：记下来（结果里明说），其它分支不受影响
                        depthHit = true
                        skipped++
                    } else {
                        val sub = DshFileHandoff.dedupe(node.dir, childName)
                        if (sub.isDirectory || sub.mkdirs() || sub.isDirectory) {
                            queue.addLast(DirNode(child.docId, sub, node.depth + 1, rel))
                        } else {
                            failedCount++
                            if (firstError == null) firstError = "mkdir: $rel"
                        }
                    }
                } else {
                    val target = DshFileHandoff.dedupe(node.dir, childName)
                    try {
                        bytes += copyOne(ctx, treeUri, child.docId, target)
                        copied++
                    } catch (e: CancellationException) {
                        // 取消时可能留下半份文件：删掉再抛（不让工作区里出现看着完整、其实截断的文件）
                        runCatching { target.delete() }
                        throw e
                    } catch (e: Exception) {
                        runCatching { target.delete() }
                        failedCount++
                        if (firstError == null) firstError = describe("copy", rel, e)
                    }
                }
                // 每个条目报一次：进度里的"当前"就是刚处理完的那一条（大文件复制期间它一直在
                // 屏幕上，用户能看出卡在哪个文件上，而不是"卡住了"）
                onProgress(
                    Progress(
                        destName = name,
                        guestPath = guestPath,
                        copied = copied,
                        skipped = skipped,
                        failed = failedCount,
                        current = rel,
                        firstError = firstError,
                    )
                )
            }
        }

        val result = Result(
            destName = name,
            guestPath = guestPath,
            copied = copied,
            skipped = skipped,
            failed = failedCount,
            firstError = firstError,
            stop = stop,
            depthHit = depthHit,
            cancelled = false,
            error = null,
        )
        Log.i(
            TAG,
            "import done: $guestPath copied=$copied skipped=$skipped failed=$failed" +
                " stop=$stop depthHit=$depthHit bytes=$bytes" +
                (firstError?.let { " firstError=$it" } ?: ""),
        )
        result
    }

    /**
     * 取消（用户按了取消，或界面被回收）时的结局：计数取**最后一份进度快照**（取消不是失败，
     * 已经复制成功的部分就是要如实告诉用户的那部分）；一份都没有（还没开始就读到取消）时
     * 给一个空结局。
     */
    fun cancelled(last: Progress?): Result = Result(
        destName = last?.destName.orEmpty(),
        guestPath = last?.guestPath.orEmpty(),
        copied = last?.copied ?: 0,
        skipped = last?.skipped ?: 0,
        failed = last?.failed ?: 0,
        firstError = last?.firstError,
        stop = Stop.None,
        depthHit = false,
        cancelled = true,
        error = null,
    )

    /** 整体跑不起来（拿不到 tree id / 定位不到工作区 / 建目录失败）时的结局。 */
    fun failed(message: String): Result = Result(
        destName = "",
        guestPath = "",
        copied = 0,
        skipped = 0,
        failed = 0,
        firstError = null,
        stop = Stop.None,
        depthHit = false,
        cancelled = false,
        error = message,
    )

    // ──────────────────────────────────────────────────────────────────────────
    //  路径 / 名字
    // ──────────────────────────────────────────────────────────────────────────

    /** 容器 `/root/workspace` 对应的宿主目录（容器真的看得见它，见类注释）。 */
    private fun workspaceRoot(ctx: Context): File? =
        DshEnv.containerToHost(ctx, DshEnv.WORKSPACE_GUEST)

    /**
     * 工作区映射里那些**单段** dest —— 它们的 bind 正好落在 `/root/workspace/<dest>`，
     * 我们若用同名目录，写进去的东西在容器里会被 bind 盖住（看不见）。多段 dest（`a/b`）只
     * 遮住 `a/b` 那一层，`/root/workspace/a` 本身还是普通目录，不用避开。
     */
    private fun mountedDestNames(ctx: Context): Set<String> {
        val out = HashSet<String>()
        for (m in DshFileAccess.workspaceMounts(ctx)) {
            if (!m.dest.contains('/')) out.add(m.dest)
        }
        return out
    }

    /**
     * 目标目录名：默认用树的显示名（[sanitizeDirName] 清洗），与已有目录 / 被 bind 遮住的
     * dest 撞名就换成 `名字 (2)`、`名字 (3)`…（**不覆盖**、也不问：用户点的是"复制进来"，
     * 多一份带序号的新目录是最不容易出事的那种处理，序号本身在结果里显示给用户）。
     */
    private fun uniqueName(dir: File, base: String, taken: Set<String>): String {
        if (base !in taken && !File(dir, base).exists()) return base
        var n = 2
        while (n < 1000) {
            val cand = "$base ($n)"
            if (cand !in taken && !File(dir, cand).exists()) return cand
            n++
        }
        return "$base (${System.currentTimeMillis()})"
    }

    /**
     * 目标目录名清洗：**默认用树的显示名**，但它是提供器给的任意字符串 —— 去掉路径分隔符
     * （换成 `_`，免得一个名字变成两级目录）、控制字符、`..`、首尾空白与点，并限长。
     * 清洗后为空（显示名本身就是空的 / 全是控制字符）→ [FALLBACK_NAME]。
     */
    fun sanitizeDirName(raw: String?): String =
        cleanSegment(raw.orEmpty()).ifEmpty { FALLBACK_NAME }

    /** 子项名字清洗：同样的规矩，但清洗后为空就**返回 null**（调用方跳过并计数，不替它编名字）。 */
    private fun sanitizeEntryName(raw: String): String? = cleanSegment(raw).ifEmpty { null }

    /**
     * 一个"路径段"的清洗（目标目录名与子项名共用这一条）：
     * `/`、`\` → `_`；丢弃控制字符（含 DEL）；去掉 `..`；去首尾空白与点；限长 [MAX_NAME]。
     *
     * 限长在**码点边界**上截断（`substring` 是按 UTF-16 的，正好切在一个代理对中间会留下
     * 半个字符），截断后再去一次尾部的点（截断可能又露出一个点结尾的名字）。
     */
    private fun cleanSegment(raw: String): String {
        val replaced = raw.replace('/', '_').replace('\\', '_')
            .filter { it.code >= 0x20 && it.code != 0x7f }
            .replace("..", "")
        return cap(replaced.trim().trim('.').trim()).trim().trim('.')
    }

    /** 按码点边界截到 [MAX_NAME] 个 UTF-16 单元以内。 */
    private fun cap(s: String): String {
        if (s.length <= MAX_NAME) return s
        var end = MAX_NAME
        if (end > 0 && s[end - 1] in '\uD800'..'\uDBFF') end--
        return s.substring(0, end)
    }

    /** 树自己的显示名（没这一列 / 权限没了 / 查询失败 → null）。 */
    private fun treeDisplayName(ctx: Context, treeUri: Uri, treeDocId: String): String? {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
        return ctx.contentResolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  SAF 读写
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 一个目录的直接子项。
     *
     * **先把游标读完再递归**（返回的是 List）：同一个 ContentResolver 上边迭代游标边发新的
     * 子查询，提供器实现各有各的脾气 —— 读成列表再展开就没有这个问题。
     * 游标建不出来（提供器不支持子项查询 / 授权没了）→ 抛 [IOException]，由调用方按"这一层失败"
     * 记一笔（与"空目录"区分开）。
     */
    private fun listChildren(ctx: Context, treeUri: Uri, parentDocId: String): List<ChildDoc> {
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val out = ArrayList<ChildDoc>()
        val cursor = ctx.contentResolver.query(
            childUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        ) ?: throw IOException("child query returned null for doc $parentDocId")
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                if (id.isNullOrBlank()) continue
                // 没有显示名时用 document id 的最后一段兜底（比丢掉这一条好：内容还是用户的）
                val name = c.getString(1)?.takeIf { it.isNotBlank() }
                    ?: id.substringAfterLast('/').substringAfterLast(':')
                val dir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                out.add(ChildDoc(id, name, dir))
            }
        }
        return out
    }

    /**
     * 单个文档 → 目标文件，**流式**复制（[copyStream] 一块一块走，绝不整份读进内存）。
     * 返回写入的字节数。拿不到输入流 → [IOException]（调用方按单文件失败计数）。
     */
    private suspend fun copyOne(ctx: Context, treeUri: Uri, docId: String, target: File): Long {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val input: InputStream = ctx.contentResolver.openInputStream(docUri)
            ?: throw IOException("openInputStream returned null for doc $docId")
        var written = 0L
        input.use { src ->
            FileOutputStream(target).use { dst ->
                written = copyStream(src, dst)
            }
        }
        return written
    }

    /**
     * 流式复制 + **逐块检查取消**：`Job.cancel()` 在下一个 64 KiB 块边界就停下（不是"跑完这个
     * 文件才管"）；流由 `use` 关闭（[CancellationException] 会照常从 `use` 里穿出去）。
     */
    private suspend fun copyStream(src: InputStream, dst: OutputStream): Long {
        val buf = ByteArray(BUF_SIZE)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = src.read(buf)
            if (n < 0) break
            dst.write(buf, 0, n)
            total += n
        }
        return total
    }

    /** 目标分区可用字节；量不出来（StatFs 失败）返回 -1 = "不知道"，不据此拦复制。 */
    private fun freeBytes(dir: File): Long =
        runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(-1L)

    /** 失败原因一行（路径 + 异常类型 + 消息）：结果里"首条失败"就是它。 */
    private fun describe(what: String, rel: String, e: Exception): String =
        "$what ${rel.ifEmpty { "." }}: ${e.javaClass.simpleName}: ${e.message ?: ""}"
}
