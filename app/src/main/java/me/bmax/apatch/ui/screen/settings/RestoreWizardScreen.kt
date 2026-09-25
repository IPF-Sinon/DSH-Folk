package me.bmax.apatch.ui.screen.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshBackupCrypto
import me.bmax.apatch.dsh.DshConfigBackup
import me.bmax.apatch.dsh.DshImportWizard
import me.bmax.apatch.dsh.DshRuntime
import me.bmax.apatch.dsh.WizardStep
import me.bmax.apatch.util.BackupLogManager
import me.bmax.apatch.util.ui.LocalSnackbarHost

/**
 * 向导带进来的临时文件可能落在哪些目录（退出时按它判断「这份副本是不是我们自己的」）。
 *
 * 只删自己造的文件：`config-import` 是 [DshConfigBackup.stage] 的落点，`config-restore`
 * 是云端 / DSH 内下载的落点，`backup-tmp` 是预检解密出来的明文包。用户从系统文件选择器
 * 给的原始文件在别的目录里，绝不在这里删 —— 那是他自己的文件。
 *
 * 注意 Kotlin 的顶层 `private` 是**文件私有**：这些常量跟着向导页走，不能留在备份页。
 */
private val WIZARD_TEMP_DIRS = setOf("config-import", "config-restore", "backup-tmp")

/** 外观在这次恢复里的结局（存成字符串，为了能进 rememberSaveable）。 */
private const val THEME_RESTORED = "restored"
private const val THEME_ABSENT = "absent"
private const val THEME_FAILED = "failed"
private const val THEME_NONE = "none"

/**
 * 决策步该不该出现：包里有会话、或有冲突，才需要问用户。
 *
 * 判据本身在 [DshImportWizard.decideNeeded]（与 `canAdvance`、`decisionsComplete` 同一处），
 * 这里只负责把 nullable 的预检结果翻译成它要的参数 —— 两处各写一份判据迟早会漂移。
 */
private fun needsDecide(preflight: DshConfigBackup.Preflight?): Boolean =
    preflight != null && DshImportWizard.decideNeeded(preflight.sessions, preflight.conflicts)

/**
 * 恢复向导的**独立页面**。
 *
 * ## 为什么它必须是一个路由（而不是备份页里的一段内容）
 *
 * 起初它写成 `BackupSettingsScreen` 内部的一个 `if (wizardStep != null)` 分支：进来时页面上
 * 直接换内容、左上角返回箭头由我手动实现成「退一步」。看起来没问题，但**系统返回手势**
 * 根本不经过那段代码 —— 导航栈里这一层仍然是「备份页」，于是手势返回把整个备份页弹掉，
 * 用户落到了设置页（真机反馈）。同一个「返回」有两套语义，而且都说得通，这正是它容易
 * 错的地方。
 *
 * 对比参照是权限页右上角进的那个权限记录页：它是独立 `@Destination`，返回交给导航栈处理，
 * 因此手势与箭头行为天然一致。这里改成同一套：
 * - 它是自己的路由，`navigator.navigate(RestoreWizardScreenDestination(...))` 进来；
 * - 返回（手势与箭头**共用**同一条判据）在向导内部退一步，退到头才真正离开这一页；
 * - 离开这一页时统一清理临时产物（预检解出来的明文包、暂存副本）。
 *
 * ## 预检产物为什么留在这里
 *
 * 上传/解密的结果（[DshConfigBackup.Preflight]）只在这次会话里有意义：文件是暂存的、
 * 容器内的路径只在本次上传有效。它含一个 `File`，Bundle 存不下，所以用 `remember`
 * （配置变更后重跑一次预检，代价是一次上传），而不用 `rememberSaveable` 去拼路径。
 */
@Destination<RootGraph>
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreWizardScreen(
    navigator: DestinationsNavigator,
    /**
     * 已经落到暂存目录的备份包（云端下载 / DSH 内备份取下来的那份）。
     *
     * 从备份页选本地文件进来的话是 null —— 那一步由这一页自己的「选择文件」完成。
     */
    stagedPath: String? = null,
    /** 暂存包是不是加密容器（决定密码是不是必填），只在对 [stagedPath] 有值时才有意义。 */
    stagedEncrypted: Boolean = false,
) {
    val snackBarHost = LocalSnackbarHost.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var step by rememberSaveable { mutableStateOf(WizardStep.SELECT) }
    var path by rememberSaveable { mutableStateOf(stagedPath) }
    var encrypted by rememberSaveable { mutableStateOf(stagedEncrypted) }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    /** 会话处理方式（[DshConfigBackup.SessionImport] 的 name）。默认恢复（用户 2026-09-26）。 */
    var session by rememberSaveable { mutableStateOf<String?>(DshConfigBackup.SessionImport.RESTORE.name) }
    var strategy by rememberSaveable { mutableStateOf(DshConfigBackup.STRATEGY_MERGE) }
    var rollback by rememberSaveable { mutableStateOf(true) }
    /** 逐条冲突决策：计划项 id → keepCurrent / useImported（值就是插件协议值）。 */
    var choices by rememberSaveable { mutableStateOf<Map<String, String>>(emptyMap()) }
    /**
     * 预览页里被用户**取消勾选**的计划项 id。
     *
     * 记「排除」而不是「选中」：计划项可能比预览列出来的多（`MAX_PREVIEW_ITEMS` 会截断），
     * 按选中集合提交等于让截断替用户决定「这些不导入」—— 他没做过的决定不该被执行。
     */
    var excluded by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    var running by rememberSaveable { mutableStateOf(false) }
    var analyzeError by rememberSaveable { mutableStateOf<String?>(null) }
    var result by rememberSaveable { mutableStateOf<WizardResultUi?>(null) }
    var lines by remember { mutableStateOf(listOf<String>()) }
    var preflight by remember { mutableStateOf<DshConfigBackup.Preflight?>(null) }
    /** 当前选的会话处理方式（存的是 name，取的时候容错）。 */
    fun sessionChoice(): DshConfigBackup.SessionImport? =
        session?.let { runCatching { DshConfigBackup.SessionImport.valueOf(it) }.getOrNull() }

    /**
     * 离开这一页时的清理：把带进来的临时产物收干净。
     *
     * 三类：预检解出来的明文包（[DshConfigBackup.discardPreflight]）、用户自己选的暂存副本
     * （`cacheDir/config-import`）、云端或 DSH 内下载的副本（`cacheDir/config-restore`）。
     * 它们可能含解出来的凭据，也可能是上百兆的包 —— 而且**系统返回手势也必须走到这里**，
     * 这正是把向导做成独立路由的另一个好处：离开 = 这个 composable 被销毁，清理挂得住。
     */
    fun cleanUp() {
        preflight?.let { DshConfigBackup.discardPreflight(it) }
        val staged = path?.let { File(it) }
        if (staged != null && staged.parentFile?.name.orEmpty() in WIZARD_TEMP_DIRS) staged.delete()
    }

    // 这一页被销毁就清理（无论是因为点「完成」「取消」还是手势返回）
    DisposableEffect(Unit) {
        onDispose { cleanUp() }
    }

    fun exit() {
        // 先 pop 再清理：pop 会触发上面的 onDispose，重复调用是幂等的（文件已删则 delete() 返回 false）
        navigator.popBackStack()
    }

    /** 向导的「分析」步：预检一次（解容器 → 数会话 → 上传 → 分析 → 试规划）。 */
    fun analyze() {
        val zip = path?.let { File(it) } ?: return
        step = WizardStep.ANALYZE
        analyzeError = null
        running = true
        lines = emptyList()
        scope.launch(Dispatchers.IO) {
            val r = DshConfigBackup.preflightImport(
                context, zip, password,
                onLine = { line -> withContext(Dispatchers.Main) { lines = lines + line } },
            )
            withContext(Dispatchers.Main) {
                running = false
                when (r) {
                    is DshConfigBackup.PreflightResult.Failed -> {
                        // 失败停在「分析」这一步：界面上给「换一个文件」与「再试一次」，
                        // 而不是把人退回首页重来（包可能只是 DSH 还没起来）。
                        analyzeError = r.message
                        lines = lines + r.message
                    }
                    is DshConfigBackup.PreflightResult.Ready -> {
                        val p = r.preflight
                        // 加密包已经解开，那份暂存的密文副本就没用了（明文包由退出时清理）
                        val staged = path?.let { File(it) }
                        if (staged != null && staged != p.plainZip &&
                            staged.parentFile?.name.orEmpty() in WIZARD_TEMP_DIRS
                        ) {
                            staged.delete()
                        }
                        preflight = p
                        session = null
                        choices = emptyMap()
                        excluded = emptySet()
                        step = WizardStep.PREVIEW
                    }
                }
            }
        }
    }

    /** 向导的「上一步」：只往回走一步，已经做过的决策都留着。 */
    fun goBackAStep() {
        step = when (step) {
            WizardStep.ANALYZE -> WizardStep.SELECT
            WizardStep.PREVIEW -> WizardStep.SELECT
            WizardStep.DECIDE -> WizardStep.PREVIEW
            WizardStep.CONFIRM -> if (needsDecide(preflight)) WizardStep.DECIDE else WizardStep.PREVIEW
            else -> step
        }
    }

    /** 向导的「下一步」：预览 → （需要决策时）决策 → 确认。 */
    fun goForwardAStep() {
        step = when (step) {
            WizardStep.PREVIEW -> if (needsDecide(preflight)) WizardStep.DECIDE else WizardStep.CONFIRM
            WizardStep.DECIDE -> WizardStep.CONFIRM
            else -> step
        }
    }

    /**
     * 「返回」的**唯一**判据：返回箭头与系统返回手势都走它。
     *
     * 两套入口各写一份是这次踩的坑（箭头退一步、手势把整页弹掉）。规则：
     * - 执行中不退：插件已经开始写盘，退出只会留下一个半写入的状态，而界面上的「退出」
     *   做不到真正中止。
     * - 退到头（选择步）才真的离开这一页；离开时由 [DisposableEffect] 统一清理。
     * - 结果步返回 = 离开（那时没有「上一步」可回，回去只会看到已经作废的确认页）。
     */
    fun handleBack() {
        when (step) {
            WizardStep.EXECUTE -> Unit
            WizardStep.SELECT, WizardStep.RESULT -> exit()
            else -> goBackAStep()
        }
    }

    BackHandler(enabled = true) { handleBack() }

    /**
     * 「开始恢复」：真正写盘。
     *
     * 与旧流程的区别全在这三个参数上：`resolutions` 来自用户对每条冲突的表态、
     * `rollbackOnError` 来自确认步的选择、`sessions` 来自决策步。旧流程里前两个是写死的
     * （逐条决策恒为空、回滚恒开），也就是「问了也白问」。
     */
    fun runImport() {
        val p = preflight ?: return
        step = WizardStep.EXECUTE
        running = true
        result = null
        lines = emptyList()
        scope.launch(Dispatchers.IO) {
            val r = DshConfigBackup.import(
                context, p.plainZip,
                strategy = strategy,
                resolutions = DshImportWizard.resolutions(p.conflicts, choices),
                rollbackOnError = rollback,
                // 用户在预览页取消勾选的项在这里被剔除（排除式：没显示出来的条目不受影响）
                excludedItems = excluded,
                password = password,
                sessions = sessionChoice() ?: DshConfigBackup.SessionImport.SKIP,
                preflight = p,
                // 阶段进度直接进向导：不然用户只看到一个转圈，不知道在干什么
                onLine = { line -> withContext(Dispatchers.Main) { lines = lines + line } },
            )
            p.plainZip.delete()
            val text = if (r.detail.isBlank()) r.message else r.message + "\n" + r.detail
            BackupLogManager.log(
                "import strategy=" + strategy + " sessions=" + (session ?: "unset") +
                    " rollback=" + rollback + " resolutions=" + choices.size +
                    " ok=" + r.ok + " restart=" + r.needsRestart,
            )
            withContext(Dispatchers.Main) {
                running = false
                lines = lines + text
                result = WizardResultUi(
                    ok = r.ok,
                    text = text,
                    // 「需要重启」只在成功时说：失败了却没重启，用户会以为重启能救回来
                    needsRestart = r.ok && r.needsRestart,
                    restartItems = r.restartItems,
                    missingSecrets = r.missingSecrets,
                    warnings = r.warnings,
                    unresolved = r.unresolved,
                    snapshotId = r.snapshotId,
                    theme = when (r.theme) {
                        DshConfigBackup.ThemeOutcome.RESTORED -> THEME_RESTORED
                        DshConfigBackup.ThemeOutcome.ABSENT -> THEME_ABSENT
                        DshConfigBackup.ThemeOutcome.FAILED -> THEME_FAILED
                        null -> THEME_NONE
                    },
                    privilegeSkipped = r.privilegeSkipped,
                )
                step = WizardStep.RESULT
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        // 用户什么都没选（系统选择器里按了返回）：留在「选择」步，不当作失败
        if (uri == null) return@rememberLauncherForActivityResult
        analyzeError = null
        scope.launch(Dispatchers.IO) {
            val staged = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    DshConfigBackup.stage(context, input, "import-${System.currentTimeMillis()}.zip")
                }
            }.getOrNull()
            if (staged == null) {
                val text = context.getString(R.string.dsh_plugin_local_read_failed)
                withContext(Dispatchers.Main) {
                    analyzeError = text
                }
                return@launch
            }
            // 加密与否只用来决定「密码是不是必填」：留空按「没加密」处理，
            // DCA1 容器留空会在预检阶段解析失败并说明原因，不会把密文当成包导进去。
            val isEncrypted = withContext(Dispatchers.IO) { DshBackupCrypto.isArchiveBlobFile(staged) }
            withContext(Dispatchers.Main) {
                path = staged.absolutePath
                encrypted = isEncrypted
                password = ""
            }
        }
    }

    // 「下一步」此刻可不可用。判据在 DshImportWizard.canAdvance（不在界面里现拼）：
    // 预览步永远可以继续（它的下一步就是进入决策步，决策还没开始做），只有决策步才要求
    // 「会话已选 + 列出来的冲突都已表态」。beta.64 的教训：把决策完成度套在预览步上，
    // 「下一步」会永远灰着 —— 用户进不了决策页，也就永远做不完决策。
    val canAdvance = DshImportWizard.canAdvance(
        step = step,
        sessions = preflight?.sessions ?: 0,
        sessionChoice = sessionChoice(),
        conflicts = preflight?.conflicts.orEmpty(),
        choices = choices,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.dsh_bk_wiz_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    // 与系统返回手势**共用** handleBack()：两套入口各写一份正是之前的坑
                    IconButton(onClick = { handleBack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackBarHost) },
    ) { paddingValues ->
        BackupImportWizard(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxWidth(),
            step = step,
            fileName = path?.let { File(it).name }.orEmpty(),
            encrypted = encrypted,
            password = password,
            showPassword = showPassword,
            preflight = preflight,
            analyzeError = analyzeError,
            sessionChoice = sessionChoice(),
            strategy = strategy,
            choices = choices,
            excludedItems = excluded,
            rollback = rollback,
            lines = lines,
            running = running,
            result = result,
            canAdvance = canAdvance,
            onPasswordChange = { password = it },
            onToggleShowPassword = { showPassword = !showPassword },
            onPickFile = { filePicker.launch("*/*") },
            onAnalyze = { analyze() },
            onNext = { goForwardAStep() },
            onSessionChoice = { session = it.name },
            onStrategyChange = { strategy = it },
            onChoice = { id, c -> choices = choices + (id to c) },
            onToggleItem = { id ->
                excluded = if (id in excluded) excluded - id else excluded + id
            },
            onSelectAllItems = { all ->
                // 全选 = 清空排除集；全不选 = 排除所有**列出来的**可取消项
                excluded = if (all) {
                    emptySet()
                } else {
                    preflight?.plan?.items?.filter { it.kind != "Conflict" }?.map { it.id }?.toSet().orEmpty()
                }
            },
            // 「全部保留本机 / 全部用包里的」：一次表态所有**列出来**的冲突；
            // 没列出来的那些由全局策略处理（见 DshImportWizard.tally）。
            onChooseAll = { c ->
                choices = preflight?.conflicts?.associate { it.id to c } ?: choices
            },
            onRollbackChange = { rollback = it },
            onBack = { goBackAStep() },
            onCancel = { exit() },
            onStartRun = { runImport() },
            onRestart = {
                // BackupLogManager.log 是 suspend，这里不是挂起上下文，得自己开一个
                scope.launch { BackupLogManager.log("restart DSH after backup/restore") }
                DshRuntime.restart()
            },
            onCopy = { clipboard.setText(AnnotatedString(it)) },
            onDone = { exit() },
        )
    }
}
