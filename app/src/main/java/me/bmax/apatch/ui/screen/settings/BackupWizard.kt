package me.bmax.apatch.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import me.bmax.apatch.R
import me.bmax.apatch.dsh.ConflictChoice
import me.bmax.apatch.dsh.DshConfigBackup
import me.bmax.apatch.dsh.DshImportWizard
import me.bmax.apatch.dsh.WizardStage
import me.bmax.apatch.dsh.WizardStep
import me.bmax.apatch.ui.component.SectionHeader

/**
 * 恢复向导的结果步要显示的东西。
 *
 * 全部是可保存的纯数据（而不是持有 [DshConfigBackup.ImportResult]）：外观主题包里带着
 * 应用语言，恢复它会让 Activity 重建 —— 结果必须能从 `rememberSaveable` 里原样恢复，
 * 否则用户看到的是一句「恢复完成」后面什么都没有。
 */
data class WizardResultUi(
    val ok: Boolean = true,
    /** 完整文本（message + detail），复制按钮直接给这个。 */
    val text: String = "",
    val needsRestart: Boolean = false,
    val restartItems: List<String> = emptyList(),
    val missingSecrets: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val unresolved: List<String> = emptyList(),
    val snapshotId: String = "",
    /** 外观的结局（"restored" / "absent" / "failed" / "none"）。 */
    val theme: String = "none",
    /** 按设计跳过的提权项数量。 */
    val privilegeSkipped: Int = 0,
) : java.io.Serializable

/**
 * 恢复向导。
 *
 * ## 为什么是「接管整页」而不是再开一个弹窗
 *
 * 决策步要列出每条冲突、每条都有两个选项，还要复述会话怎么处理 —— 塞进 `AlertDialog`
 * 的结果就是今天这个样子：一个框里三行选项，用户点完才知道发生了什么（旧流程甚至是
 * 「点哪一行就等于开始导入」，没有回头路）。整页空间还让「上一步」变得可能。
 *
 * ## 与插件向导的关系
 *
 * 阶段名、顺序、摘要口径都对着 dsh-config-manager 的导入向导（`import-stepper.ts` 的
 * 六个阶段、`ImportWizardView` 的预览计数）。区别只有两处，都是**故意**的：
 * 不做路径映射（App 已自动补建目录），不做凭据补录（包里没原文就补不出来）。
 */
@Composable
internal fun BackupImportWizard(
    step: WizardStep,
    fileName: String,
    encrypted: Boolean,
    password: String,
    showPassword: Boolean,
    preflight: DshConfigBackup.Preflight?,
    analyzeError: String?,
    sessionChoice: DshConfigBackup.SessionImport?,
    strategy: String,
    choices: Map<String, String>,
    /** 预览页里被取消勾选的计划项 id（排除式，见 BackupSettingsScreen 的说明）。 */
    excludedItems: Set<String>,
    rollback: Boolean,
    lines: List<String>,
    running: Boolean,
    result: WizardResultUi?,
    canAdvance: Boolean,
    onPasswordChange: (String) -> Unit,
    onToggleShowPassword: () -> Unit,
    onPickFile: () -> Unit,
    onAnalyze: () -> Unit,
    onNext: () -> Unit,
    onSessionChoice: (DshConfigBackup.SessionImport) -> Unit,
    onStrategyChange: (String) -> Unit,
    onChoice: (String, String) -> Unit,
    onChooseAll: (String) -> Unit,
    onToggleItem: (String) -> Unit,
    onSelectAllItems: (Boolean) -> Unit,
    onRollbackChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    onStartRun: () -> Unit,
    onRestart: () -> Unit,
    onCopy: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .verticalScroll(scroll),
    ) {
        Spacer(Modifier.height(8.dp))
        WizardStepper(step)
        Spacer(Modifier.height(14.dp))

        when (step) {
            WizardStep.SELECT -> WizardSelectStep(
                fileName = fileName,
                encrypted = encrypted,
                password = password,
                showPassword = showPassword,
                onPasswordChange = onPasswordChange,
                onToggleShowPassword = onToggleShowPassword,
                onPickFile = onPickFile,
                onAnalyze = onAnalyze,
            )

            WizardStep.ANALYZE -> WizardAnalyzeStep(
                fileName = fileName,
                lines = lines,
                running = running,
                error = analyzeError,
                onPickFile = onPickFile,
                onRetry = onAnalyze,
            )

            WizardStep.PREVIEW -> WizardPreviewStep(
                preflight = preflight,
                excluded = excludedItems,
                onToggleItem = onToggleItem,
                onSelectAll = onSelectAllItems,
            )

            WizardStep.DECIDE -> WizardDecideStep(
                preflight = preflight,
                sessionChoice = sessionChoice,
                strategy = strategy,
                choices = choices,
                onSessionChoice = onSessionChoice,
                onStrategyChange = onStrategyChange,
                onChoice = onChoice,
                onChooseAll = onChooseAll,
            )

            WizardStep.CONFIRM -> WizardConfirmStep(
                preflight = preflight,
                sessionChoice = sessionChoice,
                strategy = strategy,
                choices = choices,
                rollback = rollback,
                onRollbackChange = onRollbackChange,
            )

            WizardStep.EXECUTE -> WizardExecuteStep(lines = lines, running = running)

            WizardStep.RESULT -> WizardResultStep(
                result = result,
                onCopy = onCopy,
                onRestart = onRestart,
            )
        }

        // 「继续」只在需要用户决策的两步出现：它的可用性取决于这一步的决策是否完整，
        // 摆在决策内容旁边（而不是底部按钮行）才看得出「为什么点不动」。
        // 「预览永远可以继续、只有决策步才卡」这条判据在 DshImportWizard.canAdvance 里，
        // 界面只负责把 canAdvance 传进来（beta.64 的死结就是两处判据混用造成的）。
        if (step == WizardStep.PREVIEW || step == WizardStep.DECIDE) {
            WizardAdvanceButton(
                step = step,
                nextIsDecide = DshImportWizard.decideNeeded(
                    sessions = preflight?.sessions ?: 0,
                    conflicts = preflight?.conflicts.orEmpty(),
                ),
                enabled = canAdvance,
                onClick = onNext,
            )
        }

        Spacer(Modifier.height(16.dp))
        WizardButtons(
            step = step,
            running = running,
            onBack = onBack,
            onCancel = onCancel,
            onStartRun = onStartRun,
            onDone = onDone,
        )
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 六个阶段的进度条。
 *
 * 用六段方条 + 「当前阶段名（N/6）」，而不是六个文字标签排一行：中文标签在窄屏上一定会
 * 被挤成两行或截断（这一页的「预览与决策」就有五个字），六段色块在任何宽度下都读得出来。
 */
@Composable
private fun WizardStepper(step: WizardStep) {
    val current = DshImportWizard.stageOf(step)
    val index = WizardStage.entries.indexOf(current)
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (stage in WizardStage.entries) {
                val done = WizardStage.entries.indexOf(stage) <= index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (done) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(wizardStageTitle(current)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(
                    R.string.dsh_bk_wiz_stage_of,
                    index + 1,
                    DshImportWizard.STAGE_COUNT,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 阶段名。六个阶段都在 [WizardStage] 里，这里只做「枚举 → 资源」的映射。 */
private fun wizardStageTitle(stage: WizardStage): Int = when (stage) {
    WizardStage.SELECT -> R.string.dsh_bk_wiz_stage_select
    WizardStage.ANALYZE -> R.string.dsh_bk_wiz_stage_analyze
    WizardStage.DECIDE -> R.string.dsh_bk_wiz_stage_decide
    WizardStage.CONFIRM -> R.string.dsh_bk_wiz_stage_confirm
    WizardStage.EXECUTE -> R.string.dsh_bk_wiz_stage_execute
    WizardStage.DONE -> R.string.dsh_bk_wiz_stage_done
}

@Composable
private fun WizardAnalyzeStep(
    fileName: String,
    lines: List<String>,
    running: Boolean,
    error: String?,
    onPickFile: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        if (running) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
        }
        Text(
            text = stringResource(R.string.dsh_bk_wiz_analyzing, fileName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        LogBox(lines)
        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(10.dp))
            // 失败要能原路退回：换一个文件，或对同一个文件再试一次（插件可能只是没起来）
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickFile) {
                    Text(stringResource(R.string.dsh_bk_wiz_pick_again))
                }
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.dsh_backup_plugin_retry))
                }
            }
        }
    }
}

@Composable
private fun WizardSelectStep(
    fileName: String,
    encrypted: Boolean,
    password: String,
    showPassword: Boolean,
    onPasswordChange: (String) -> Unit,
    onToggleShowPassword: () -> Unit,
    onPickFile: () -> Unit,
    onAnalyze: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.dsh_bk_wiz_select_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        // 没选文件时，**主按钮就是「选择文件」**（而不是一个次级 OutlinedButton 加一个
        // 灰着的「开始分析」）：用户从设置页点进来，第一眼要能看出「这一步要我做的是选文件」。
        if (fileName.isEmpty()) {
            Button(onClick = onPickFile) {
                Text(stringResource(R.string.dsh_bk_wiz_pick_file))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.dsh_bk_wiz_no_file),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // 选好之后，文件名要能一眼看到（用户刚从系统选择器回来，需要确认选对了）
            SectionHeader(stringResource(R.string.dsh_bk_wiz_selected_file))
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onPickFile) {
                    Text(stringResource(R.string.dsh_bk_wiz_pick_again))
                }
            }
            Spacer(Modifier.height(12.dp))
            // 加密包必须填密码：留空会被预检当成「没加密」直接报解析失败，
            // 那句话（「不是本生态的备份」）会把人指到完全错误的方向去。
            Text(
                text = stringResource(
                    if (encrypted) R.string.dsh_bk_import_pw_hint_encrypted
                    else R.string.dsh_bk_import_pw_hint_plain,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (encrypted) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text(stringResource(R.string.dsh_bk_pw_title)) },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = onToggleShowPassword) {
                        Text(
                            text = stringResource(
                                if (showPassword) R.string.dsh_pw_hide else R.string.dsh_pw_show,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onAnalyze,
                enabled = !encrypted || password.isNotEmpty(),
            ) {
                Text(stringResource(R.string.dsh_bk_wiz_start_analyze))
            }
        }
    }
}

@Composable
private fun WizardPreviewStep(
    preflight: DshConfigBackup.Preflight?,
    excluded: Set<String>,
    onToggleItem: (String) -> Unit,
    onSelectAll: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        val analysis = preflight?.analysis
        val plan = preflight?.plan
        val appData = preflight?.appData
        if (analysis != null) {
            CompatibilityBand(analysis.compatibility)
            Spacer(Modifier.height(12.dp))
            SectionHeader(stringResource(R.string.dsh_bk_wiz_preview_scope))
            Spacer(Modifier.height(6.dp))
            StatRow(
                stringResource(R.string.dsh_bk_wiz_sections),
                analysis.sections.toString(),
            )
            StatRow(
                stringResource(R.string.dsh_bk_wiz_plugins),
                stringResource(
                    R.string.dsh_bk_wiz_plugins_value,
                    analysis.pluginsInstalled,
                    analysis.pluginsToInstall,
                ),
            )
            if (analysis.secretCount > 0) {
                StatRow(
                    stringResource(R.string.dsh_bk_wiz_secrets),
                    analysis.secretCount.toString(),
                )
            }
            if (analysis.pathIssues > 0) {
                StatRow(
                    stringResource(R.string.dsh_bk_wiz_path_issues),
                    stringResource(R.string.dsh_bk_wiz_path_issues_value, analysis.pathIssues),
                )
            }
        }
        if (plan != null) {
            Spacer(Modifier.height(14.dp))
            SectionHeader(stringResource(R.string.dsh_bk_wiz_preview_plan))
            Spacer(Modifier.height(6.dp))
            StatRow(
                stringResource(R.string.dsh_bk_wiz_will_change),
                plan.willChange.toString(),
            )
            StatRow(stringResource(R.string.dsh_bk_wiz_unchanged), plan.unchanged.toString())
            if (plan.installs > 0) {
                StatRow(stringResource(R.string.dsh_bk_wiz_installs), plan.installs.toString())
            }
            if (plan.conflicts > 0) {
                StatRow(stringResource(R.string.dsh_bk_wiz_conflicts), plan.conflicts.toString())
            }
            if (plan.needsRestart) {
                StatRow(
                    stringResource(R.string.dsh_bk_wiz_needs_restart),
                    stringResource(R.string.dsh_bk_wiz_yes),
                )
            }
            if (plan.missingSecrets.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        R.string.dsh_bk_wiz_missing_secrets,
                        plan.missingSecrets.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // ── 逐条勾选（默认全选）──
            //
            // 状态记的是「被取消的那些 id」而不是「被选中的那些」：计划项可能比上面这个
            // 列表多（[DshConfigBackup] 的 MAX_PREVIEW_ITEMS 截断），按「选中集合」提交
            // 会让没显示出来的条目**静默不导入** —— 用户没做过的决定不该由截断替他做。
            if (plan.items.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.dsh_bk_wiz_pick_items),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onSelectAll(true) }) {
                        Text(stringResource(R.string.dsh_bk_wiz_select_all))
                    }
                    TextButton(onClick = { onSelectAll(false) }) {
                        Text(stringResource(R.string.dsh_bk_wiz_select_none))
                    }
                }
                Text(
                    text = stringResource(R.string.dsh_bk_wiz_pick_items_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                for (item in plan.items) {
                    PlanItemRow(
                        item = item,
                        // 冲突项不在这里取消：它不是「要不要导入」，而是「哪一边说了算」，
                        // 下一步会逐条问。在这里提供一个勾选框等于给了两种互相矛盾的表达。
                        selectable = item.kind != "Conflict",
                        checked = item.id !in excluded,
                        onToggle = { onToggleItem(item.id) },
                    )
                }
                val skipped = plan.items.count { it.id in excluded }
                if (skipped > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.dsh_bk_wiz_items_skipped, skipped),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        if (appData != null) {
            Spacer(Modifier.height(14.dp))
            SectionHeader(stringResource(R.string.dsh_bk_wiz_preview_appdata))
            Spacer(Modifier.height(6.dp))
            StatRow(
                stringResource(R.string.dsh_bk_wiz_prefs_files),
                stringResource(R.string.dsh_bk_wiz_prefs_files_value, appData.prefsFiles, appData.keys),
            )
            if (appData.schema >= 2 && appData.prefsFiles > 1) {
                Text(
                    text = stringResource(R.string.dsh_bk_wiz_prefs_runtime_included),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (appData.schema > 0 && appData.schema < 2) {
                // 旧包只带 config：不说清楚的话，用户会以为「运行时设置也回来了」
                Text(
                    text = stringResource(R.string.dsh_bk_wiz_prefs_old_schema),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (appData.auditFiles > 0) {
                StatRow(
                    stringResource(R.string.dsh_bk_wiz_audit),
                    appData.auditFiles.toString(),
                )
            }
            if (appData.excluded > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.dsh_bk_excluded_note, appData.excluded) +
                        if (appData.privilegeSkipped > 0) {
                            "；" + stringResource(
                                R.string.dsh_bk_excluded_privilege,
                                appData.privilegeSkipped,
                            )
                        } else {
                            ""
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // 外观单独一段，且**两种包都看它**：混合包（软件数据 + DSH 分区）同样带主题包，
        // 而恢复外观会替换背景/字体/音乐/音效、还可能切换应用语言 —— 这些副作用必须在
        // 确认之前说清楚，不能因为「有 DSH 分区」就跳过不提。
        val themeBytes = preflight?.themeBytes ?: -1L
        if (themeBytes >= 0L) {
            Spacer(Modifier.height(14.dp))
            SectionHeader(stringResource(R.string.dsh_bk_wiz_appearance))
            Spacer(Modifier.height(6.dp))
            StatRow(
                stringResource(R.string.dsh_bk_wiz_theme),
                if (themeBytes > 0L) {
                    stringResource(R.string.dsh_bk_wiz_theme_value, themeBytes / 1024)
                } else {
                    stringResource(R.string.dsh_bk_wiz_theme_included)
                },
            )
            Text(
                text = stringResource(R.string.dsh_bk_wiz_theme_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val warnings = analysis?.warnings.orEmpty()
        if (warnings.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            SectionHeader(stringResource(R.string.dsh_bk_wiz_warnings))
            Spacer(Modifier.height(6.dp))
            for (w in warnings) {
                Text(
                    text = "! " + w,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * 预览里的一条计划项（可勾选）。
 *
 * 勾选框用 [Checkbox] 而不是整行可点：这一行里还有描述文字，整行可点会让「想看清文字」
 * 的人误触成取消。冲突项的勾选框禁用并给出原因（它由下一步逐条决定，不是丢不丢）。
 */
@Composable
private fun PlanItemRow(
    item: DshConfigBackup.PlanItemLite,
    selectable: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { if (selectable) onToggle() },
            enabled = selectable,
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = item.description,
                style = MaterialTheme.typography.bodySmall,
                color = if (selectable && !checked) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            )
            if (!selectable) {
                Text(
                    text = stringResource(R.string.dsh_bk_wiz_item_conflict_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 兼容性一带（与插件预览页同一套档位名）。 */
@Composable
private fun CompatibilityBand(compatibility: String) {
    val (textRes, color) = when (compatibility) {
        "excellent" -> R.string.dsh_bk_wiz_compat_excellent to MaterialTheme.colorScheme.primary
        "good" -> R.string.dsh_bk_wiz_compat_good to MaterialTheme.colorScheme.primary
        "partial" -> R.string.dsh_bk_wiz_compat_partial to MaterialTheme.colorScheme.error
        else -> R.string.dsh_bk_wiz_compat_unknown to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (compatibility == "partial") Icons.Filled.Warning
                else Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(textRes),
                style = MaterialTheme.typography.bodyMedium,
                color = color,
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun WizardDecideStep(
    preflight: DshConfigBackup.Preflight?,
    sessionChoice: DshConfigBackup.SessionImport?,
    strategy: String,
    choices: Map<String, String>,
    onSessionChoice: (DshConfigBackup.SessionImport) -> Unit,
    onStrategyChange: (String) -> Unit,
    onChoice: (String, String) -> Unit,
    onChooseAll: (String) -> Unit,
) {
    val sessions = preflight?.sessions ?: 0
    val conflicts = preflight?.conflicts.orEmpty()
    Column(Modifier.fillMaxWidth()) {
        if (sessions > 0) {
            SectionHeader(stringResource(R.string.dsh_bk_sessions_ask_title))
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.dsh_bk_sessions_ask_message, sessions),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            // 推荐项（停机恢复）放最上面：三种写法都能生效，区别只在「接下来若在 WebUI 里
            // 动工作区，这次归组会不会被 dsh 的内存写回盖掉」。所以默认不选，但推荐摆第一。
            SessionChoiceRow(
                title = stringResource(R.string.dsh_bk_sessions_ask_stop),
                note = stringResource(R.string.dsh_bk_sessions_ask_stop_note),
                recommended = true,
                selected = sessionChoice == DshConfigBackup.SessionImport.STOP,
                onClick = { onSessionChoice(DshConfigBackup.SessionImport.STOP) },
            )
            Spacer(Modifier.height(8.dp))
            SessionChoiceRow(
                title = stringResource(R.string.dsh_bk_sessions_ask_direct),
                note = stringResource(R.string.dsh_bk_sessions_ask_direct_note),
                selected = sessionChoice == DshConfigBackup.SessionImport.DIRECT,
                onClick = { onSessionChoice(DshConfigBackup.SessionImport.DIRECT) },
            )
            Spacer(Modifier.height(8.dp))
            SessionChoiceRow(
                title = stringResource(R.string.dsh_bk_sessions_ask_skip),
                note = stringResource(R.string.dsh_bk_sessions_ask_skip_note),
                selected = sessionChoice == DshConfigBackup.SessionImport.SKIP,
                onClick = { onSessionChoice(DshConfigBackup.SessionImport.SKIP) },
            )
        }

        if (conflicts.isNotEmpty()) {
            if (sessions > 0) Spacer(Modifier.height(16.dp))
            SectionHeader(
                stringResource(R.string.dsh_bk_conflict_title, preflight?.conflictTotal ?: 0),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.dsh_bk_conflict_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onChooseAll(ConflictChoice.KEEP_CURRENT.wire) }) {
                    Text(stringResource(R.string.dsh_bk_wiz_choose_all_keep))
                }
                OutlinedButton(onClick = { onChooseAll(ConflictChoice.USE_IMPORTED.wire) }) {
                    Text(stringResource(R.string.dsh_bk_wiz_choose_all_use))
                }
            }
            Spacer(Modifier.height(10.dp))
            for (c in conflicts) {
                ConflictRow(
                    line = c.line(),
                    chosen = choices[c.id],
                    onChoice = { onChoice(c.id, it) },
                )
                Spacer(Modifier.height(8.dp))
            }
            val undecided = DshImportWizard.undecided(conflicts, choices)
            if (undecided > 0) {
                Text(
                    text = stringResource(R.string.dsh_bk_wiz_undecided, undecided),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // 列不完的那些由全局策略兜底：不写这一句，用户会以为没列出来的项没被处理
            val hidden = (preflight?.conflictTotal ?: 0) - conflicts.size
            if (hidden > 0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.dsh_bk_wiz_strategy_for_rest, hidden),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                StrategyRow(strategy, onStrategyChange)
            }
        }
    }
}

/** 一条冲突 + 两个选项。 */
@Composable
private fun ConflictRow(
    line: String,
    chosen: String?,
    onChoice: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(10.dp),
    ) {
        Text(text = line, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip(
                text = stringResource(R.string.dsh_bk_wiz_keep_current),
                selected = chosen == ConflictChoice.KEEP_CURRENT.wire,
                onClick = { onChoice(ConflictChoice.KEEP_CURRENT.wire) },
            )
            ChoiceChip(
                text = stringResource(R.string.dsh_bk_wiz_use_imported),
                selected = chosen == ConflictChoice.USE_IMPORTED.wire,
                onClick = { onChoice(ConflictChoice.USE_IMPORTED.wire) },
            )
        }
    }
}

@Composable
private fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** 没列出来的冲突用哪个全局策略。 */
@Composable
private fun StrategyRow(strategy: String, onStrategyChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SessionChoiceRow(
            title = stringResource(R.string.dsh_bk_strategy_merge),
            note = stringResource(R.string.dsh_bk_strategy_merge_desc),
            recommended = true,
            selected = strategy == DshConfigBackup.STRATEGY_MERGE,
            onClick = { onStrategyChange(DshConfigBackup.STRATEGY_MERGE) },
        )
        Spacer(Modifier.height(8.dp))
        SessionChoiceRow(
            title = stringResource(R.string.dsh_bk_strategy_replace),
            note = stringResource(R.string.dsh_bk_strategy_replace_desc),
            selected = strategy == DshConfigBackup.STRATEGY_REPLACE,
            onClick = { onStrategyChange(DshConfigBackup.STRATEGY_REPLACE) },
        )
        Spacer(Modifier.height(8.dp))
        SessionChoiceRow(
            title = stringResource(R.string.dsh_bk_strategy_skip),
            note = stringResource(R.string.dsh_bk_strategy_skip_desc),
            selected = strategy == DshConfigBackup.STRATEGY_SKIP_EXISTING,
            onClick = { onStrategyChange(DshConfigBackup.STRATEGY_SKIP_EXISTING) },
        )
    }
}

@Composable
private fun WizardConfirmStep(
    preflight: DshConfigBackup.Preflight?,
    sessionChoice: DshConfigBackup.SessionImport?,
    strategy: String,
    choices: Map<String, String>,
    rollback: Boolean,
    onRollbackChange: (Boolean) -> Unit,
) {
    val plan = preflight?.plan
    val conflicts = preflight?.conflicts.orEmpty()
    val tally = DshImportWizard.tally(conflicts, choices, preflight?.conflictTotal ?: 0)
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.dsh_bk_wiz_confirm_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        if (plan != null) {
            StatRow(stringResource(R.string.dsh_bk_wiz_will_change), plan.willChange.toString())
        }
        if (preflight != null && preflight.sessions > 0) {
            StatRow(
                stringResource(R.string.dsh_bk_wiz_sessions_value),
                stringResource(
                    when (sessionChoice ?: DshConfigBackup.SessionImport.SKIP) {
                        DshConfigBackup.SessionImport.STOP -> R.string.dsh_bk_wiz_sessions_stop
                        DshConfigBackup.SessionImport.DIRECT -> R.string.dsh_bk_wiz_sessions_direct
                        DshConfigBackup.SessionImport.SKIP -> R.string.dsh_bk_wiz_sessions_skip
                    },
                ),
            )
        }
        if (preflight != null && preflight.conflictTotal > 0) {
            StatRow(
                stringResource(R.string.dsh_bk_wiz_conflicts),
                stringResource(
                    R.string.dsh_bk_wiz_conflict_tally,
                    tally.keepCurrent,
                    tally.useImported,
                    tally.byStrategy,
                ),
            )
            StatRow(
                stringResource(R.string.dsh_bk_wiz_conflict_strategy),
                stringResource(
                    when (strategy) {
                        DshConfigBackup.STRATEGY_REPLACE -> R.string.dsh_bk_strategy_replace
                        DshConfigBackup.STRATEGY_SKIP_EXISTING -> R.string.dsh_bk_strategy_skip
                        else -> R.string.dsh_bk_strategy_merge
                    },
                ),
            )
        }
        val themeIncluded = preflight != null && preflight.themeBytes >= 0L
        val restartNeeded = plan?.needsRestart == true
        if (themeIncluded || restartNeeded) {
            Spacer(Modifier.height(10.dp))
            if (restartNeeded) {
                Text(
                    text = stringResource(R.string.dsh_bk_wiz_needs_restart_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (themeIncluded) {
                Text(
                    text = stringResource(R.string.dsh_bk_wiz_theme_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        HorizontalDivider()
        Spacer(Modifier.height(10.dp))
        SessionChoiceRow(
            title = stringResource(R.string.dsh_bk_wiz_rollback_on),
            note = stringResource(R.string.dsh_bk_wiz_rollback_on_note),
            recommended = true,
            selected = rollback,
            onClick = { onRollbackChange(true) },
        )
        Spacer(Modifier.height(8.dp))
        SessionChoiceRow(
            title = stringResource(R.string.dsh_bk_wiz_rollback_off),
            note = stringResource(R.string.dsh_bk_wiz_rollback_off_note),
            selected = !rollback,
            onClick = { onRollbackChange(false) },
        )
    }
}

@Composable
private fun WizardExecuteStep(lines: List<String>, running: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        if (running) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
        }
        Text(
            text = stringResource(R.string.dsh_bk_wiz_execute_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        LogBox(lines)
    }
}

@Composable
private fun WizardResultStep(
    result: WizardResultUi?,
    onCopy: (String) -> Unit,
    onRestart: () -> Unit,
) {
    val r = result ?: return
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (r.ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (r.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(
                    if (r.ok) R.string.dsh_bk_wiz_result_ok else R.string.dsh_bk_wiz_result_partial,
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(12.dp))
        // 「下一步」清单：需重启 / 缺凭据 / 没处理的项，各自成组。
        // 这些以前只藏在整段日志里，用户看到「导入完成」就以为没事了。
        if (r.needsRestart) {
            ResultGroup(
                title = stringResource(R.string.dsh_bk_wiz_next_restart),
                items = r.restartItems.ifEmpty {
                    listOf(stringResource(R.string.dsh_bk_wiz_next_restart_generic))
                },
                action = stringResource(R.string.dsh_plugin_restart_now) to onRestart,
            )
        }
        if (r.theme == "restored") {
            ResultGroup(
                title = stringResource(R.string.dsh_bk_wiz_next_theme),
                items = listOf(stringResource(R.string.dsh_bk_wiz_next_theme_note)),
            )
        }
        if (r.privilegeSkipped > 0) {
            ResultGroup(
                title = stringResource(R.string.dsh_bk_wiz_next_privilege),
                items = listOf(
                    stringResource(R.string.dsh_bk_excluded_privilege, r.privilegeSkipped),
                ),
            )
        }
        if (r.missingSecrets.isNotEmpty()) {
            ResultGroup(
                title = stringResource(R.string.dsh_bk_wiz_next_secrets),
                items = r.missingSecrets,
            )
        }
        if (r.unresolved.isNotEmpty()) {
            ResultGroup(
                title = stringResource(R.string.dsh_bk_wiz_next_unresolved),
                items = r.unresolved,
            )
        }
        if (r.warnings.isNotEmpty()) {
            ResultGroup(
                title = stringResource(R.string.dsh_bk_wiz_warnings),
                items = r.warnings,
            )
        }
        if (r.snapshotId.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.dsh_bk_snapshot, r.snapshotId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(14.dp))
        SectionHeader(stringResource(R.string.dsh_bk_wiz_result_log))
        Spacer(Modifier.height(6.dp))
        LogBox(r.text.lines())
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onCopy(r.text) }) {
                Text(stringResource(R.string.dsh_copy_log))
            }
            if (r.needsRestart) {
                Button(onClick = onRestart) {
                    Text(stringResource(R.string.dsh_plugin_restart_now))
                }
            }
        }
    }
}

@Composable
private fun ResultGroup(
    title: String,
    items: List<String>,
    action: Pair<String, () -> Unit>? = null,
) {
    Spacer(Modifier.height(12.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(4.dp))
    for (i in items) {
        Text(
            text = "• " + i,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 2.dp),
        )
    }
    if (action != null) {
        Spacer(Modifier.height(6.dp))
        Button(onClick = action.second) { Text(action.first) }
    }
}

/**
 * 一行可选项（会话怎么处理、冲突没列完时用哪个策略、回滚策略）。
 *
 * ## 高亮只表示「已选中」
 *
 * 这里曾经把「推荐」也画成高亮底色（primaryContainer），于是**没被选中的推荐项**看起来
 * 和选中项一样 —— 真机上用户以为「停机恢复」已经是默认选项，而实际上一条都没选，
 * 于是「下一步」点不动，还以为按钮坏了（beta.65 反馈）。
 * 现在底色**只由 [selected] 决定**，[recommended] 退化成一枚「推荐」小标记：
 * 「默认」与「推荐」是两件事，绝不能共用同一种视觉。
 */
@Composable
internal fun SessionChoiceRow(
    title: String,
    note: String,
    recommended: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    // 选中 = primaryContainer 底 + 主色文字；未选中 = surfaceVariant 底 + 常规文字。
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val titleColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface
    val noteColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
    else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = titleColor,
                )
                if (recommended) {
                    Spacer(Modifier.width(6.dp))
                    // 只是「这个选项更稳妥」的信息，不是「已经替你选了」
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.dsh_bk_wiz_recommended),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = noteColor,
            )
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
/** 只读的日志框（执行中与结果步共用）。 */
@Composable
private fun LogBox(lines: List<String>) {
    val scroll = rememberScrollState()
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = 320.dp),
    ) {
        Text(
            text = lines.joinToString("\n").ifEmpty {
                stringResource(R.string.dsh_plugin_waiting_output)
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .padding(12.dp)
                .verticalScroll(scroll),
        )
    }
}

/**
 * 底部按钮。
 *
 * 「恢复中」不给取消：插件那边的 `/execute` 已经开跑，中途离开只会留下半写入的状态，
 * 而界面上的取消按钮做不到真正的中止（插件有它自己的跳过通道，但那是逐个计划项的）。
 * 所以这一步只显示进度，不去假装能撤。
 */
@Composable
private fun WizardButtons(
    step: WizardStep,
    running: Boolean,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    onStartRun: () -> Unit,
    onDone: () -> Unit,
) {
    when (step) {
        WizardStep.EXECUTE -> Unit
        WizardStep.RESULT -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onDone) { Text(stringResource(R.string.close)) }
        }
        else -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel, enabled = !running) {
                Text(stringResource(android.R.string.cancel))
            }
            if (step != WizardStep.SELECT) {
                TextButton(onClick = onBack, enabled = !running) {
                    Text(stringResource(R.string.dsh_bk_wiz_back))
                }
            }
            Spacer(Modifier.weight(1f))
            if (step == WizardStep.CONFIRM) {
                Button(onClick = onStartRun, enabled = !running) {
                    Text(stringResource(R.string.dsh_bk_wiz_restore_now))
                }
            }
        }
    }
}

/**
 * 向导自己的「继续」按钮。
 *
 * 放在内容里而不是底部按钮行：它的可用性取决于当前这一步的决策（会话选没选、冲突是否
 * 都已表态），摆在决策内容旁边比摆在屏幕底部更容易让人看出「为什么点不动」。
 *
 * [nextIsDecide] 只影响文案：预览步在没有会话也没有冲突时会直接跳到确认，
 * 那时候还写「下一步：处理冲突」就是在指一件不存在的事。
 */
@Composable
internal fun WizardAdvanceButton(
    step: WizardStep,
    nextIsDecide: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val label = when (step) {
        WizardStep.PREVIEW -> if (nextIsDecide) R.string.dsh_bk_wiz_to_decide else R.string.dsh_bk_wiz_to_confirm
        WizardStep.DECIDE -> R.string.dsh_bk_wiz_to_confirm
        else -> R.string.dsh_bk_wiz_next
    }
    Spacer(Modifier.height(14.dp))
    Button(onClick = onClick, enabled = enabled) {
        Text(stringResource(label))
    }
}
