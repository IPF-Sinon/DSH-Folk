#!/usr/bin/env node
/**
 * 备份 / 恢复的门禁。
 *
 * ## 为什么要有这个检查器
 *
 * 备份恢复这条链路跨了三个进程：App（Kotlin）→ 插件（容器内的 dsh-config-manager，
 * 走回环 HTTP）→ 系统（MediaStore / 文件系统）。跑不了单测的地方，恰恰是最容易悄悄
 * 退化、而且退化后**用户看不出来**的地方：
 *
 *  - 插件说 needsRestart（装/卸了插件、改了 MCP），App 只把这句话拼进文案里，
 *    界面上没有任何地方据此做事 —— 用户看到「需要重启」却找不到按钮，回头以为恢复没生效；
 *  - 导入的冲突策略写死 merge，界面没得选 —— 「恢复备份」实际是「把缺的补上」，
 *    与用户心里那句「回到当时的状态」不是一回事；
 *  - 快照恢复会**卸载**快照里没有的插件，如果界面不给预览（dryRun）就直接执行，
 *    用户的新插件会在一次「回退」里无声消失；
 *  - 复制失败却照报公共目录路径 —— 用户拿着不存在的路径去找备份，只会以为备份丢了。
 *
 * 这些都是「结构缺失」而不是「算错数」，所以断言的是**接线是否存在**：端点、字段、
 * 默认值、确认步骤、以及失败时不许虚报。
 *
 * 诚实边界：PROPFIND 的 XML 解析是 Kotlin + XmlPullParser，Node 里跑不了。这里只断言
 * 它的过滤/排序/解码规则与命名空间容忍度来自源码原文，真实解析正确性靠设备实测
 * （列目录 → 下载 → 导入一次）。
 */
const fs = require("fs");

const SRC_BACKUP = "app/src/main/java/me/bmax/apatch/dsh/DshConfigBackup.kt";
const SRC_SCREEN = "app/src/main/java/me/bmax/apatch/ui/screen/settings/BackupSettingsScreen.kt";
const SRC_CONTENT = "app/src/main/java/me/bmax/apatch/ui/screen/settings/BackupSettings.kt";
const SRC_CONFIG = "app/src/main/java/me/bmax/apatch/ui/theme/BackupConfig.kt";
// 1.9.2.5：WebDAV 云备份整个搬进 dsh-folk-cloud 插件；App 侧只剩它的客户端与补包桥。
const SRC_CLOUD = "app/src/main/java/me/bmax/apatch/dsh/DshCloudBackup.kt";
const SRC_CLOUD_APPDATA = "app/src/main/java/me/bmax/apatch/dsh/DshCloudAppData.kt";
const SRC_FSBRIDGE = "app/src/main/java/me/bmax/apatch/dsh/DshFsBridge.kt";
const SRC_RUNTIME = "app/src/main/java/me/bmax/apatch/dsh/DshRuntime.kt";
const SRC_WIZARD = "app/src/main/java/me/bmax/apatch/ui/screen/settings/BackupWizard.kt";
const SRC_WIZARD_SCREEN = "app/src/main/java/me/bmax/apatch/ui/screen/settings/RestoreWizardScreen.kt";
const SRC_WIZARD_MODEL = "app/src/main/java/me/bmax/apatch/dsh/DshImportWizard.kt";
const SRC_APPDATA = "app/src/main/java/me/bmax/apatch/dsh/DshAppData.kt";
const SRC_ARCHIVE = "app/src/main/java/me/bmax/apatch/dsh/DshBackupArchive.kt";
const SRC_APPDATA_SNAPSHOT = "app/src/main/java/me/bmax/apatch/dsh/DshAppDataSnapshot.kt";

let n = 0;
let bad = 0;
/**
 * 取出某个标记所在那一段代码（从标记到它开的大括号闭掉为止）。
 * 「这段逻辑有没有被那个条件包住」光用全文包含判断会误判 —— 别处也常有同名代码，
 * 必须真的按括号配对切出区间来问。
 */
function braceSpan(src, marker) {
  const at = src.indexOf(marker);
  if (at < 0) return null;
  const open = src.indexOf("{", at);
  if (open < 0) return null;
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    if (src[i] === "{") depth++;
    else if (src[i] === "}") {
      depth--;
      if (depth === 0) return [at, i];
    }
  }
  return null;
}

/**
 * 剥掉注释，只留会被编译的代码。
 *
 * 「不该出现 X」这类断言必须扫它：判据的「为什么」常常要写在 KDoc 里（比如这次的
 * `e.size in 1..2MB` —— 不写出来，下一个人还会踩），而注释里的字符串不是违规。
 */
function code(src) {
  return src.replace(/\/\/[^\n]*/g, "").replace(/\/\*[\s\S]*?\*\//g, "");
}

function ok(cond, label) {
  n++;
  if (cond) {
    console.log("  ✓ " + label);
  } else {
    bad++;
    console.log("  ✗ " + label);
  }
}

const backup = fs.readFileSync(SRC_BACKUP, "utf8");
const screen = fs.readFileSync(SRC_SCREEN, "utf8");
// 恢复向导已经独立成页（RestoreWizardScreen.kt）：与「恢复流程」有关的断言都看它。
const wizardScreen = fs.readFileSync(SRC_WIZARD_SCREEN, "utf8");
const content = fs.readFileSync(SRC_CONTENT, "utf8");
const config = fs.readFileSync(SRC_CONFIG, "utf8");
const cloud = fs.readFileSync(SRC_CLOUD, "utf8");
const cloudAppData = fs.readFileSync(SRC_CLOUD_APPDATA, "utf8");
const fsBridge = fs.readFileSync(SRC_FSBRIDGE, "utf8");
const runtime = fs.readFileSync(SRC_RUNTIME, "utf8");
const wizard = fs.readFileSync(SRC_WIZARD, "utf8");
const wizardModel = fs.readFileSync(SRC_WIZARD_MODEL, "utf8");
const appData = fs.readFileSync(SRC_APPDATA, "utf8");
const archive = fs.readFileSync(SRC_ARCHIVE, "utf8");

console.log("─ 1. 导入后的收尾：needsRestart 必须驱动一个真的动作");
ok(/val needsRestart: Boolean = false/.test(backup),
  "ImportResult 带 needsRestart（不再只是文案）");
ok(/needsRestart = needsRestart,/.test(backup),
  "构造时把插件的 needsRestart 透出来");
ok(/restartItems = restartItems,/.test(backup) && /missingSecrets = missingSecrets,/.test(backup) &&
  /unresolved = unresolved,/.test(backup) && /snapshotId = execObj\.optString\("snapshotId"\)/.test(backup),
  "结果里的「下一步」字段也是结构化的（需重启项/缺凭据项/没处理的项/快照 id），而不是只在文案里");
ok(/needsRestart = r\.ok && r\.needsRestart,/.test(wizardScreen),
  "界面只在导入成功且插件要求时才提示重启（失败却提示重启会让人以为重启能救回来）");
ok(/onRestart = \{[\s\S]{0,300}DshRuntime\.restart\(\)/.test(screen),
  "进度对话框的「重启服务」真的调 DshRuntime.restart()");
ok(/needsRestart = runNeedsRestart/.test(screen),
  "该对话框按 runNeedsRestart 决定是否给出重启按钮");
ok(/BackupLogManager\.log\(\s*"import strategy="/.test(wizardScreen) &&
  /resolutions=" \+ choices\.size/.test(wizardScreen),
  "导入结果（策略/会话/回滚/逐条决策数/成败/是否需重启）落一条日志 —— 出问题时有据可查");

console.log("─ 2. 阶段进度：分钟级操作不能只转圈");
for (const key of [
  "dsh_bk_step_uploading",
  "dsh_bk_step_analyzing",
  "dsh_bk_step_planning",
  "dsh_bk_step_executing",
  "dsh_bk_step_sessions",
]) {
  ok(backup.includes("R.string." + key), "导入进度含 " + key);
}
ok(/onLine: suspend \(String\) -> Unit = \{\}/.test(backup),
  "onLine 是 suspend 回调（界面要在里面切主线程改状态）");
ok(/onLine = \{ line -> withContext\(Dispatchers\.Main\) \{ lines = lines \+ line \} \}/.test(wizardScreen),
  "界面把进度行接进向导（预检与执行两处都接）");

console.log("─ 3. 冲突策略：检测到冲突才问，三档都在，选完传进 import()");
ok(/const val STRATEGY_MERGE = "merge"/.test(backup) &&
  /const val STRATEGY_REPLACE = "replace"/.test(backup) &&
  /const val STRATEGY_SKIP_EXISTING = "skipExisting"/.test(backup),
  "三个策略常量与插件 /plan 的 decisions.strategy 取值一致");
ok(/strategy: String = "merge"/.test(backup), "默认策略仍是插件侧的保守默认 merge");
// 用户要求：不要在导入前就让他选策略，而是检测到冲突再问。所以断言的是
// 「问的条件」与「答案流向」，而不是界面上有没有一个事先选好的控件。
ok(/suspend fun preflightImport\(/.test(backup), "导入前先跑预检（上传/分析/试规划一次做完）");
ok(/optString\("kind"\) != "Conflict"/.test(backup), "预检按计划项的 kind == Conflict 数冲突");
ok(/conflictTotal/.test(backup) && /conflicts\.size < MAX_CONFLICT_LIST/.test(backup),
  "冲突数量与清单都交回给界面（清单有上限，数量如实）");
// 决策步只在**有东西要问**的时候出现：没有会话也没有冲突就直接从预览进确认。
// needsDecide 现在定义在向导页文件里，界面与「下一步」共用它
ok(/private fun needsDecide\(preflight: DshConfigBackup\.Preflight\?\): Boolean/.test(wizardScreen) &&
  /if \(needsDecide\(preflight\)\) WizardStep\.DECIDE else WizardStep\.CONFIRM/.test(wizardScreen),
  "只有真的检测到会话或冲突才进决策步（不给用户多余的一问）");
// 逐条冲突决策：键是计划项 id，取值是插件协议的 keepCurrent/useImported
ok(/data class ConflictItem\(/.test(backup) && /id = item\.optString\("id"\)/.test(backup),
  "冲突条目带计划项 id —— 逐条决策的键就是它，没有 id 只能整包选一个策略");
ok(/"keepCurrent"/.test(wizardModel) && /"useImported"/.test(wizardModel),
  "两种处置用插件协议值（拼错了插件会当成 review，用户选「用包里的」却什么都没发生）");
ok(/fun decisionsComplete\(/.test(wizardModel) &&
  /conflicts\.count \{ it\.id\.isNotEmpty\(\) && it\.id !in choices \}/.test(wizardModel),
  "冲突未逐条表态就不放行（判据在状态机里，不靠界面自己数）");
ok(/sessions <= 0 \|\| sessionChoice != null/.test(wizardModel),
  "包里有会话时必须明确选一种处理方式（不给默认值：默认写入等于替用户决定动他的聊天记录）");
ok(/DshImportWizard\.canAdvance\(/.test(wizardScreen) && /canAdvance = canAdvance/.test(wizardScreen),
  "界面用状态机的判据决定「下一步」能不能点");
// beta.64 真机死结：预览步的「下一步」被决策完成度卡住 —— 而预览的下一步正是进入决策步，
// 那时一条冲突都还没表态，于是按钮永远灰着、用户永远进不了决策页。判据必须分步：
// 预览永远放行，只有决策步才检查完成度。
ok(/WizardStep\.PREVIEW -> true/.test(wizardModel),
  "预览步永远可以继续（它的下一步就是进入决策步，此时决策还没开始做）");
ok(/WizardStep\.DECIDE -> decisionsComplete\(sessions, sessionChoice, conflicts, choices\)/.test(wizardModel),
  "只有决策步才要求「会话已选 + 列出的冲突都已表态」");
ok(/DshImportWizard\.canAdvance\(\s*step = step,/.test(wizardScreen),
  "界面把当前步骤一起传进去（少传步骤 = 又变回「一套判据套两步」）");
ok(/fun decideNeeded\(sessions: Int, conflicts: List<DshConfigBackup\.ConflictItem>\): Boolean/.test(wizardModel) &&
  /DshImportWizard\.decideNeeded\(preflight\.sessions, preflight\.conflicts\)/.test(wizardScreen) &&
  /DshImportWizard\.decideNeeded\(\s*sessions = preflight\?\.sessions \?: 0,/.test(wizard),
  "「要不要进决策步」只有一处判据（模型里定义，向导页与按钮文案都调它）");
ok(/nextIsDecide = DshImportWizard\.decideNeeded\(/.test(wizard),
  "预览步的按钮文案跟着实际去向走（没有会话也没有冲突时不说「处理冲突」）");
// 决策真的流进插件：/plan 的 decisions.resolutions 不再是空对象
ok(/put\("resolutions", JSONObject\(\)\.apply \{ for \(\(id, r\) in resolutions\) put\(id, r\) \}\)/.test(backup),
  "逐条冲突决策真的写进 decisions.resolutions（以前恒为空对象 —— 问了也白问）");
ok(/resolutions: Map<String, String> = emptyMap\(\)/.test(backup) &&
  /resolutions = DshImportWizard\.resolutions\(p\.conflicts, choices\)/.test(wizardScreen),
  "决策从界面一路传到 /plan");
ok(/sessions = sessionChoice\(\) \?: DshConfigBackup\.SessionImport\.SKIP/.test(wizardScreen),
  "会话答案也带进最终那次导入");
// 回滚开关：插件侧是 === true 的严格判断，漏传等于关掉
ok(/rollbackOnError: Boolean = true/.test(backup) &&
  /put\("rollbackOnError", rollbackOnError\)/.test(backup) &&
  /rollbackOnError = rollback/.test(wizardScreen),
  "回滚开关是可传参数、永远显式写、且由确认步决定（漏传等于关掉回滚）");
for (const [strategy, key] of [
  ["STRATEGY_MERGE", "dsh_bk_strategy_merge"],
  ["STRATEGY_REPLACE", "dsh_bk_strategy_replace"],
  ["STRATEGY_SKIP_EXISTING", "dsh_bk_strategy_skip"],
]) {
  ok(new RegExp("onStrategyChange\\(DshConfigBackup\\." + strategy + "\\)").test(wizard),
    "向导的决策步里有 " + strategy + " 这一档");
  ok(wizard.includes("R.string." + key), "它带说明文案 " + key);
}
// 列不完的冲突由全局策略兜底 —— 界面上必须说出来，否则用户以为没列出来的没被处理
ok(/dsh_bk_wiz_strategy_for_rest/.test(wizard) && /byStrategy/.test(wizardModel),
  "没列出来的冲突明确交回全局策略，并把条数说出来");
ok(!/IMPORT_STRATEGIES/.test(content), "事先选策略的控件已经从这一页移除（改成导入时问）");
ok(/preflight = p,/.test(wizardScreen) && /preflight: Preflight\? = null/.test(backup),
  "答完之后用预检产物继续导入（不再上传/解密第二遍）");
ok(/discardPreflight/.test(wizardScreen) && /fun discardPreflight\(/.test(backup),
  "用户取消时把预检解出来的临时明文删掉");

console.log("─ 4. 快照回退：先预览（零写入）→ 确认 → 才执行");
ok(/suspend fun listSnapshots\(\)/.test(backup) && /"GET", "\/snapshots"/.test(backup),
  "列出快照走 GET /snapshots");
ok(/optsLong|optLong\("createdAtMs"/.test(backup) && /parseIsoMillis/.test(backup),
  "快照时间两种写法都认（ISO 字符串 / 毫秒数），解析不了不编造");
ok(/suspend fun previewSnapshot\(snapshotId: String\)/.test(backup) &&
  /put\("dryRun", true\)/.test(backup),
  "预览用 dryRun=true（插件侧零写入）");
ok(/suspend fun restoreSnapshot\(ctx: Context, snapshotId: String\)/.test(backup) &&
  /put\("dryRun", false\)/.test(backup),
  "真正执行才传 dryRun=false");
for (const field of ["restored", "removedPlugins", "failed", "skipped", "manualHints"]) {
  ok(backup.includes('"' + field + '"'), "报告解析字段 " + field + "（插件 ImportResult 的诚实报告）");
}
ok(/val busy = err\.contains\("conflict", ignoreCase = true\)/.test(backup),
  "409 冲突（已有恢复在跑）转成一句能看懂的话，而不是抛原始错误");
ok(/pendingActions = -1/.test(screen) && /if \(pendingActions >= 0\)/.test(screen),
  "预览没回来之前不弹确认框（避免 0 项动作的假确认）");
// 用括号配对切出这个回调本身再断言：原来按「400 字符以内」判定，一次缩进调整
// 就会把它挤出去，于是检查器报的是格式问题、不是行为问题。
const snapRestoreSpan = braceSpan(screen, "onSnapshotRestore = { snap ->");
ok(snapRestoreSpan !== null, "快照行接了「恢复」回调");
if (snapRestoreSpan) {
  ok(/previewSnapshot\(snap\.id\)/.test(screen.slice(snapRestoreSpan[0], snapRestoreSpan[1])),
    "点「恢复」先走预览，不是直接执行");
}
ok(/R\.string\.dsh_bk_snapshot_confirm_body,\s*snap\.id,\s*pendingActions/.test(screen),
  "确认框把快照 id 与动作数摆出来（含「会卸载快照里没有的插件」这句）");
const confirmBody = fs.readFileSync("app/src/main/res/values-zh-rCN/dsh_strings.xml", "utf8")
  .match(/<string name="dsh_bk_snapshot_confirm_body">([\s\S]*?)<\/string>/);
ok(confirmBody !== null && /卸载/.test(confirmBody[1]) && /pre-restore/.test(confirmBody[1]),
  "确认文案讲明会卸载插件、以及插件侧的 pre-restore 双保险");

console.log("─ 5. 云备份整个搬进 dsh-folk-cloud 插件，App 侧只剩它的前端");
// WebDavUtils 已删：App 不再自己传/列/取。这条断言钉住「没人再引用它」。
ok(!fs.existsSync("app/src/main/java/me/bmax/apatch/util/WebDavUtils.kt"),
  "WebDavUtils.kt 已删除（App 不再自建 WebDAV 客户端）");
ok(!/WebDavUtils/.test(screen) && !/WebDavUtils/.test(content),
  "备份页不再引用 WebDavUtils");
// BackupConfig 不再存 webdav 配置：只剩本地导入的冲突策略
// 只看真正的字段/键，不看文档注释里对 webdav 的提及
ok(!/var webdav/.test(config) && !/PREF_KEY_WEBDAV/.test(config) &&
  !/var isBackupEnabled/.test(config) && /importStrategy/.test(config),
  "BackupConfig 不再存 webdav 配置与 isBackupEnabled（只留 importStrategy）");
// App 侧云备份客户端：读状态 / 存配置 / 触发，都打到 dsh-folk-cloud 的 /api 前缀
ok(/BASE = "\/api\/dsh-folk-cloud"/.test(cloud),
  "DshCloudBackup 打到 /api/dsh-folk-cloud");
ok(/"GET", "\/status"/.test(cloud) && /"POST", "\/config"/.test(cloud) && /"POST", "\/trigger"/.test(cloud),
  "客户端覆盖 status / config / trigger 三个端点");
// 口令留空 = 不下发：插件保留原口令（与旧 beta.71 同一条铁律，只是换了目标插件）
ok(/if \(password\.isNotEmpty\(\)\) put\("password", password\)/.test(cloud),
  "口令留空则不下发（插件保留原口令）");
ok(/passwordConfigured/.test(cloud) && !/optString\("password"\)/.test(cloud),
  "口令永不回传：只读 passwordConfigured 布尔");
// 界面：云备份块只在插件可达时出现，且走 DshCloudBackup 触发（不再有 WebDavUtils 上传/下载）
ok(/cloud != null && cloud\.reachable/.test(content),
  "云备份卡片仅在检测到插件（reachable）时显示");
ok(/DshCloudBackup\.trigger\("auto"/.test(screen) && /DshCloudBackup\.trigger\("pull"/.test(screen),
  "「立即同步/从上游恢复」走插件 trigger（auto/pull）");
ok(/DshCloudBackup\.saveConfig\(/.test(content) && /DshCloudBackup\.test\(/.test(content),
  "配置弹窗保存/测试都走插件");
// 本地选文件导入仍走独立向导页（这条与云备份无关，保留）
ok(/onDshImport = \{[\s\S]{0,200}navigator\.navigate\(\s*\n?\s*RestoreWizardScreenDestination\(stagedPath = null\)/.test(screen),
  "点「导入备份」导航到独立的向导页");
const anDef = (wizardScreen.match(/fun analyze\(\)/g) || []).length;
const runDef = (wizardScreen.match(/fun runImport\(\)/g) || []).length;
ok(anDef === 1 && runDef === 1,
  "向导仍是单一预检/开跑入口（analyze " + anDef + " / runImport " + runDef + "）");

console.log("─ 5b. 导出/导入：页面上只有动作，内容都在弹窗里问");
const contentSrc2 = content; // 顶部已经读过这一份
ok(/onClick = \{ showExportDialog = true \}/.test(contentSrc2),
  "页面上的「导出」只是打开弹窗，不再直接开跑");
const exportDialog = braceSpan(contentSrc2, 'if (showExportDialog) {');
ok(exportDialog !== null, "有「导出什么」弹窗");
if (exportDialog) {
  const dialogBody = contentSrc2.slice(exportDialog[0], exportDialog[1]);
  ok(/ExportPlan\(/.test(dialogBody), "导出计划（范围/会话/密码）在弹窗里组装");
  ok(/dsh_bk_scope_title/.test(dialogBody) && /dsh_bk_sessions_title/.test(dialogBody),
    "范围与会话两档都在弹窗里（页面上没有）");
  ok(/dsh_bk_pw_title/.test(dialogBody) && /dsh_bk_pw_random/.test(dialogBody),
    "密码框与随机生成也在弹窗里");
  ok(/enabled = exportPlan\.valid/.test(dialogBody), "含 vault 却没密码时确认键禁用");
  ok(/verticalScroll/.test(dialogBody), "弹窗内容可滚动（小屏不会被截断）");
}
// 弹窗必须组合在两个滑块对话框之前，否则滑块会叠在它下面点不到
const exportAt = contentSrc2.indexOf('if (showExportDialog)');
const scopeAt = contentSrc2.indexOf('// ── 数据范围滑块');
ok(exportAt > 0 && scopeAt > exportAt, "导出弹窗组合在滑块对话框之前（滑块才叠得上去）");
// 页面上的导出说明搬进弹窗了，页面上不再有它
ok(
  !/^\s+Text\(\s*$[\s\S]{0,200}dsh_backup_export_summary/m.test(contentSrc2.split('if (showExportDialog)')[0]),
  "页面上的导出说明已挪进弹窗（页面上不再重复一大段）",
);

console.log("─ 5c. 导入密码：留空即按「没加密」解析（现在是向导的第一步）");
const selectStep = braceSpan(wizard, "private fun WizardSelectStep(");
ok(selectStep !== null, "向导第一步就是「选文件 + 填密码」");
if (selectStep) {
  const body = wizard.slice(selectStep[0], selectStep[1]);
  ok(/encrypted/.test(body), "按 magic 结果给出「这是加密包 / 不是加密包」两种提示");
  ok(/dsh_bk_import_pw_hint_plain/.test(body) && /dsh_bk_import_pw_hint_encrypted/.test(body),
    "两种提示文案都在");
  ok(/enabled = !encrypted \|\| password\.isNotEmpty\(\)/.test(body),
    "明文包空密码就能继续（留空 = 不解密直接解析），加密包必须先填 —— 留空会让预检把密文当包解，"
      + "报出来的却是「不是本生态的备份」，把人指到完全错误的方向");
  ok(/onAnalyze/.test(body), "填好之后进预检，而不是立刻写盘");
  ok(/dsh_pw_show|dsh_pw_hide/.test(body), "这个密码框也有显示/隐藏");
}
// 退出向导时必须把「我们自己造的」临时副本收拾掉，且只删自己造的
ok(/WIZARD_TEMP_DIRS = setOf\("config-import", "config-restore", "backup-tmp"\)/.test(wizardScreen),
  "只删自己造的暂存目录（config-import / config-restore / backup-tmp）");
// 「退出时清理」现在是挂在这一页被销毁上的：系统返回手势也会走到它 ——
// 这正是把向导做成独立路由换来的东西（页内分支时手势返回把整页弹掉，谁都清不到）。
ok(/fun cleanUp\(\)/.test(wizardScreen) && /staged\.delete\(\)/.test(wizardScreen) &&
  /DshConfigBackup\.discardPreflight\(it\)/.test(wizardScreen),
  "退出向导时删掉暂存副本与预检解出来的明文包");
ok(/DisposableEffect\(Unit\) \{\s*\n\s*onDispose \{ cleanUp\(\) \}/.test(wizardScreen),
  "清理挂在页面销毁上（手势返回也会走到，不再依赖「记得点取消」）");

console.log("─ 5d. 插件状态：原因不许被吞，安装按钮只在确认缺失时才画");
ok(/val err = o\.optString\("error"\)/.test(backup) && /error = err,/.test(backup),
  "status() 把插件自己的 error 带出来（以前只读 ready，原因全丢）");
ok(/status\.error\.ifEmpty \{ pluginMissing \}/.test(screen),
  "导出前的检查显示插件给的真实原因，而不是一律说「DSH 没起来」");
ok(/DshPluginRepo\.listInstalled\(\)\.any \{ it\.pkg == DSH_CONFIG_MANAGER_PKG \}/.test(screen),
  "「装没装」由应用侧直接查容器插件目录（不需要 DSH 在跑）");
ok(/pluginAbsent = st\?\.ready != true && !installed/.test(screen),
  "只有「没就绪（含检测超时）**且** 确实没装」才算缺失");
ok(/if \(pluginAbsent\) \{[\s\S]{0,200}onGoInstallPlugin/.test(content),
  "「去安装插件」只在确认缺失时出现");
ok(/onRecheckPlugin/.test(content) && /onRecheckPlugin = \{ pluginProbe\+\+ \}/.test(screen) &&
  /LaunchedEffect\(pluginProbe\)/.test(screen),
  "其它情况给的是「重新检测」而不是「去安装」");
ok(/getOrDefault\(true\)/.test(screen),
  "查不到插件清单时当作「装了」—— 宁可少给一个按钮，也不要指错路");

console.log("─ 5e. 排查通道：每一步都记账、日志一键复制、报告里带上日志");
// 「导出的包只有 49 字节」这种事，靠读代码读不出来，必须知道每一步的实际大小：
// 插件给了多少、补包后多少、容器多少、校验过没过。所以这些数字必须落进日志。
for (const step of [
  "plugin-request", "plugin-file", "plugin-downloaded",
  "merge-start", "merge-done", "encrypt=memory", "container bytes=", "copy location=",
  "import-start", "import-decrypted", "import-uploaded", "import-analyze", "import-preflight", "import-execute",
]) {
  ok(backup.includes('"' + step), "导出/导入日志里有 " + step);
}
ok(/private suspend fun trace\(ctx: Context, step: String\)/.test(backup) &&
  /BackupLogManager\.log\("export \$step"\)/.test(backup),
  "trace 走 BackupLogManager（写进 backup_log.log）");
ok(/private suspend fun failTrace\(ctx: Context, message: String\): ExportResult/.test(backup) &&
  /export failed: \$message/.test(backup),
  "失败也记一笔 —— 用户看到的提示与日志里的一致，不会「用户看到了、日志里什么都没有」");
ok(/merge-done bytes=" \+ merged\.length\(\)/.test(backup) && /stats\.sessionFiles/.test(backup),
  "补包结果连同 stats 一起记（会话数/文件数/软件数据/secrets）");
ok(/expected=" \+ \(DshBackupCrypto\.HEADER_LENGTH \+ merged\.length\(\)\)/.test(backup),
  "容器日志里同时记「期望大小」（头 + 明文），49 字节的现场一眼可见");

// 日志一键复制：用户要的就是点一下把日志拿走
ok(/dsh_bk_log_copy/.test(content) && /clipboard\.setText\(AnnotatedString\(logs\)\)/.test(content),
  "日志对话框有「复制全部日志」按钮，复制的是整份日志");
ok(/dsh_bk_log_copied/.test(content) && /Toast/.test(content), "复制后给一句反馈");
// 排查期一度在备份页单独放了个「备份日志」按钮；问题定位之后按用户要求撤掉：
// 日志查看仍在 WebDAV 对话框里（BackupLogDialog），并随 bugreport 一起打包。
ok(!/onOpenBackupLog/.test(content) && !/showBackupLog/.test(screen) &&
  /BackupLogDialog\(/.test(content),
  "备份页不再单独放日志按钮，日志查看仍在原来的位置（WebDAV 对话框）");

// 日志会随 bugreport 一起走，而且不能无限长大
const logEvent = fs.readFileSync('app/src/main/java/me/bmax/apatch/util/LogEvent.kt', 'utf8');
ok(/backup-log\.txt/.test(logEvent) && /backup_log\.log/.test(logEvent),
  "bugreport 里带上 backup-log.txt（应用自己的备份日志）");
ok(/takeLast\(400\)/.test(logEvent), "只取最后 400 行，报告不会被日志撑爆");
ok(/redactInPlace\(backupLogFile/.test(logEvent) || /backupLogFile/.test(logEvent),
  "备份日志也过脱敏流程");
const logMgr = fs.readFileSync('app/src/main/java/me/bmax/apatch/util/BackupLogManager.kt', 'utf8');
ok(/MAX_BYTES = 512L \* 1024L/.test(logMgr) && /rotateIfTooBig/.test(logMgr),
  "日志超过 512KB 就截断旧内容（每一步都记账，不轮转会无限长大）");

console.log("─ 5f. DSH 内备份分区：能恢复、能删除；快照也能删");
// 「只是列出来」没有用：用户点「列出」的下一步一定是「拿它恢复」或者「不要了」。
ok(/fun deleteRemoteBackup\(ctx: Context, backup: RemoteBackup\): String/.test(backup) &&
  /"\/backup-files\/delete"/.test(backup) && /put\("name", name\)/.test(backup),
  "删备份走插件 /backup-files/delete，请求体是 {name}");
// 插件只接受纯 .zip 文件名（自己防穿越），本地先挡一道，省得拿 400 回来还要翻译
ok(/!name\.endsWith\("\.zip"\) \|\| name\.contains\('\/'\).*contains\('\\\\'\)/.test(backup) &&
  /dsh_bk_remote_delete_bad_name/.test(backup),
  "删除前先校验文件名（不是纯 .zip 就不发请求）");
ok(/fun deleteSnapshot\(ctx: Context, snapshotId: String\): RestoreResult/.test(backup) &&
  /"\/snapshots\/delete"/.test(backup) && /put\("snapshotId", snapshotId\)/.test(backup),
  "删快照走 /snapshots/delete，请求体是 {snapshotId}");
ok(/fun fetchRemoteBackup\(ctx: Context, backup: RemoteBackup\): File\?/.test(backup) &&
  /if \(!isUsableZip\(dest\)\)/.test(backup) &&
  /fun remoteBackupPath\(b: RemoteBackup\): String/.test(backup),
  "从 DSH 取备份：下载后仍要过「是不是能打开的 zip」，路径没给就按插件 exports 约定推");
// 恢复复用导入那条路（密码 → 预检 → 会话/冲突 → 执行），不另开通道
ok(/RestoreWizardScreenDestination\([\s\S]{0,200}stagedPath = fetched\.absolutePath/.test(screen) &&
  /DshBackupCrypto\.isArchiveBlobFile\(fetched\)/.test(screen),
  "从 DSH 恢复 = 取到本地后交给同一条向导（不再自己实现一遍解密/预检）");
ok(/dshBackups: List<DshConfigBackup\.RemoteBackup>/.test(content) &&
  /onDshBackupRestore: \(DshConfigBackup\.RemoteBackup\) -> Unit/.test(content) &&
  /onDshBackupDelete: \(DshConfigBackup\.RemoteBackup\) -> Unit/.test(content),
  "备份列表传的是对象（不是拼好的字符串），每行才给得出动作");
ok(/onSnapshotDelete: \(DshConfigBackup\.Snapshot\) -> Unit/.test(content) &&
  /onSnapshotDelete = \{ snap -> pendingSnapshotDelete = snap \}/.test(screen),
  "快照行也接上删除");
// 三个写操作都要先问一句
for (const d of ['pendingRemoteRestore', 'pendingRemoteDelete', 'pendingSnapshotDelete']) {
  ok(new RegExp(d + '\\?\.let').test(screen), d + " 有确认对话框");
}
ok(/dsh_bk_snapshot_delete_confirm_body/.test(screen) &&
  /回滚点/.test(fs.readFileSync('app/src/main/res/values-zh-rCN/dsh_strings.xml', 'utf8')),
  "删快照的确认文案说清「删掉的是回滚点」");
ok(/dsh_bk_remote_restore_confirm_body/.test(screen) && /dsh_bk_remote_delete_confirm_body/.test(screen),
  "恢复/删除备份各有一句确认文案");

console.log("─ 5g. 导入前补建缺失的工作区目录（否则会话进不了工作区）");
// 现场：插件的 workspaces 适配器对路径 realpath，目录不存在就只留一条非致命警告
// （§34.17），于是「配置都导进来了、只有工作区没写进去」，App 的会话归组随后
// 找不到对应工作区 → 「会话归组：0/1 条进入工作区」。插件自己在报错里写了修法：
// 先在目标创建目录 —— 但必须赶在插件 /execute 之前。
ok(/suspend fun ensureWorkspaceDirs\(ctx: Context, paths: List<String>\): DirFixResult/.test(backup),
  "有 ensureWorkspaceDirs（补建缺失目录）");
ok(/fun workspacePathsInZip\(\s*zip: File,/.test(backup) && /onNote: \(\(String\) -> Unit\)\? = null,/.test(backup),
  "有 workspacePathsInZip（从包里读工作区路径），并带一条诊断回调");
const dirCall = backup.indexOf("ensureWorkspaceDirs(ctx, wantedDirs)");
const execStep = backup.indexOf("dsh_bk_step_executing");
ok(dirCall > 0 && execStep > 0 && dirCall < execStep,
  "补目录必须排在插件 /execute 之前（事后补已经晚了）");
// 路径安全：外部输入不能变成「随便往哪写」的许可
ok(/if \(!p\.startsWith\("\/"\) \|\| p == "\/"\) return null/.test(backup), "只接受容器内绝对路径");
ok(/if \(p\.split\('\/'\)\.any \{ it == "\.\." \}\) return null/.test(backup), "含 .. 段的路径直接拒绝");
ok(/!targetCanon\.startsWith\(rootCanon \+ File\.separator\)/.test(backup), "规范化后必须仍在 rootfs 内（软链逃逸也拦）");
ok(/target\.isDirectory -> existing \+= path/.test(backup), "已存在目录只记一笔跳过（幂等）");
ok(/target\.exists\(\) -> failed \+= ".+不是目录，没有覆盖/.test(backup), "目标已存在文件时不覆盖");
ok(/else -> failed \+= ".+创建失败/.test(backup), "创建失败照实记，不假装成功");
// 这里原本断言的是「用 e.size 卡在 2MB 以内」——那条断言在 beta.65 上是通过的，
// 而功能整段没执行（见 5m）：App 合并包时 zip 条目走 data descriptor，getSize() 是 -1/0。
// 现在断言的是「按条目名精确取那一个分区」，尺寸不再参与判定。
ok(/isWorkspacesEntry\(e\.name\)/.test(backup) && /private const val WORKSPACES_ENTRY/.test(backup),
  "只读工作区分区那一个条目（按名字取，不把整包读进内存）");
ok(/optJSONArray\("workspaces"\)/.test(backup) && /optJSONObject\("tables"\)/.test(backup),
  "分区形状与落盘形状（tables.workspaces）都认");
ok(/dsh_bk_import_dirs_created/.test(backup) && /dsh_bk_import_dirs_failed/.test(backup) &&
  /dsh_bk_step_prepare_dirs/.test(backup),
  "补建过程与结果都有话给用户（步骤行 + 成功行 + 失败行）");
ok(/trace\(\s*ctx,\s*"import-ensure-dirs/.test(backup) && /import-dir-created/.test(backup),
  "补建结果进日志（bugreport 里看得到创建了哪些目录）");

console.log("─ 5h. 凭据能不能恢复：看包里有没有原文，而不是看插件那句话");
// 插件 /execute 拿我们传的 decryptPassword 解 security/secrets.enc，把 YAML 顶层项收成
// Map<键, 值>（键就是 DEEPSEEK_API_KEY 这类 env 名）；能拿到值的凭据就不会进待补录清单。
// 而插件另报的「凭据文件 .credentials.yaml 不在本机 vault」说的是它的**本机镜像**
// （导出时在同机留的副本），跨机必然缺 —— 跟「包里有没有凭据」是两件事，很容易被读成
// 「凭据没恢复」。
ok(/fun secretsInfoInZip\(zip: File, password: String\): SecretsInfo/.test(backup),
  "有 secretsInfoInZip：读 manifest + secrets.enc 判断包里有没有真凭据");
ok(/containsSecrets = sec\.optBoolean\("containsSecrets", false\)/.test(backup) &&
  /DshBackupCrypto\.decryptSecrets\(bytes, salt, iv, tag, password\)/.test(backup),
  "同时看 manifest 的 containsSecrets 与实际能否解开");
ok(/private fun credentialRefs\(yaml: String\): Map<String, String>/.test(backup),
  "凭据从 YAML 里按 ref: 值 取出（records 里的会话秘密之类不当凭据）");
ok(/dsh_bk_secrets_in_archive/.test(backup) && /dsh_bk_secrets_placeholder/.test(backup),
  "两种情况各有明确说法：含原文 / 只有空占位");
ok(/import-secrets encrypted=/.test(backup) && /keys=\" \+ secretsInfo\.keys\.size/.test(backup),
  "凭据情况进日志（bugreport 里可判）");
const zh = fs.readFileSync('app/src/main/res/values-zh-rCN/dsh_strings.xml', 'utf8');
ok(/导出时没勾「含 vault」/.test(zh) && /这是导出时的选择，不是导入出了错/.test(zh),
  "空占位那条解释清「不可恢复的原因在导出侧」，不让人以为导入坏了");

console.log("─ 5i. 凭据值必须真的交回插件（refs 块在 YAML 里是嵌套的）");
// 实测容器的 .credentials.yaml 形状：
//   version: 1
//   records:
//     client-connection/browser-session: …
//   refs:
//     RJK66_API_KEY: sk-…
//     DEEPSEEK_API_KEY: sk-…
// 而插件收集凭据时只看**顶层字符串项**（Object.entries + typeof v === 'string'），
// refs 是对象 → 整段跳过 → 「明明勾了含 vault、包里也有原文，导入时照样让你重填」。
// 插件留的另一条通道：MissingSecret.applyItem 是
//   ctx.secretInputs[ref] ?? decryptedCredentials?.get(ref) → credentials.set(ref, value)
// 所以 App 必须把 refs 里的值经 opts.secretInputs 喂回去。
ok(/private fun credentialRefs\(yaml: String\): Map<String, String>/.test(backup),
  "有 credentialRefs：从 YAML 里取出 ref → 值");
ok(/block == REFS_BLOCK/.test(backup) && /private const val REFS_BLOCK = "refs"/.test(backup),
  "认顶层 refs: 块（实测容器就是这么放的）");
ok(/ENV_KEY\.matches\(key\)/.test(backup) && /\^\[A-Z\]\[A-Z0-9_\]\*\$/.test(backup),
  "顶层标量只认 env 风格键名（version: 1 这类不混进来）");
ok(/opts\.put\("secretInputs", inputs\)/.test(backup) &&
  /for \(\(k, v\) in secretsInfo\.refs\) inputs\.put\(k, v\)/.test(backup),
  "经 /execute 的 opts.secretInputs 交给插件（正是它 applyItem 认的那条路）");
ok(/import-secrets-handoff refs=/.test(backup), "交接动作进日志");
ok(/val refs: Map<String, String> = emptyMap\(\)/.test(backup) &&
  /keys = refs\.keys\.toList\(\)/.test(backup),
  "SecretsInfo 同时带出键与值");

console.log("─ 5j. 插件没就绪时按钮不许可点（null 是「还不知道」，不是「允许」）");
// 原来的判据是 `pluginReady != false`：检测中（null）会被放行，用户一进页面就能点导出，
// 而那一刻插件可能根本没起来。现在一律按 == true 判定。
ok(!/pluginReady != false/.test(content),
  "不再把「还在检测」当成可点");
// 云备份区块改由 dsh-folk-cloud 的 cloudStatus.reachable 把关（另一个插件），不再数 pluginReady；
// 依赖 dsh-config-manager 的入口（会话整理/导出导入/快照/DSH 内备份）仍按 == true 判定。
const gates = (content.match(/pluginReady == true/g) || []).length;
ok(gates >= 4, "依赖 dsh-config-manager 的入口按 == true 判定（找到 " + gates + " 处：会话整理/导出导入/快照/DSH 内备份）");
ok(/const val STATUS_TIMEOUT_MS = 15_000/.test(backup) &&
  /request\("GET", "\/status", null, timeoutMs = STATUS_TIMEOUT_MS\)/.test(backup),
  "探活用自己的 15 秒超时（request 默认 300 秒是给导入导出那种真在干活的请求用的）");
ok(/withTimeoutOrNull\(STATUS_PROBE_TIMEOUT_MS\)/.test(screen),
  "界面侧再包一层超时兜底：按钮一定会走到一个确定状态，而不是永远停在「检测中」");
ok(/dsh_backup_plugin_timeout/.test(screen),
  "超时有一条能直接显示的原因（不是空白，也不是「插件缺失」这种误导）");
ok(/enabled = !dshBusy/.test(content) && /onInstallRescueCli/.test(content),
  "不依赖插件的入口（打开备份目录、救急 CLI、WebDAV 开关）不受这个 gating 约束");

console.log("─ 5k. 软件数据范围：config + dshfolk，设备/运行时状态与提权项不带");
ok(/val PREFS_FILES = listOf\(PREFS_NAME, DshEnv\.PREF\)/.test(appData),
  "带两个设置文件：config 与 dshfolk（「服务就绪后自动打开页面」那批设置就在后者里）");
ok(/KEY_AUTO_OPEN_WEBUI = "auto_open_webui_when_ready"/.test(fs.readFileSync("app/src/main/java/me/bmax/apatch/dsh/DshEnv.kt", "utf8")) &&
  /const val PREF = "dshfolk"/.test(fs.readFileSync("app/src/main/java/me/bmax/apatch/dsh/DshEnv.kt", "utf8")),
  "那条设置确实住在 dshfolk 里（只带 config 的话，恢复后它不会回来）");
for (const key of [
  "KEY_SEED_PLUGINS_DONE", "KEY_SEEDED_PLUGINS", "KEY_SEED_REPAIR_REV", "KEY_SEED_RUNTIME",
  "KEY_SEED_PASSES", "KEY_SEED_SHADOWED", "KEY_SEED_SHADOWED_RUNTIME",
  "KEY_RUNTIME_VERSION", "KEY_RUNTIME_MIN_APP", "KEY_PROROOT_FAIL", "KEY_ROOTFS_SIZE",
  "KEY_PERM_CHANNEL", "KEY_PRIV_STRICTNESS", "KEY_NATIVE_BRIDGE", "KEY_NATIVE_CAPS",
]) {
  ok(new RegExp("DshEnv\\." + key + ",").test(appData), "排除表点名 " + key);
}
ok(/val PRIVILEGE_KEYS = setOf\(/.test(appData) && /fun excludedPrivilegeCount\(/.test(appData),
  "提权项单独数出来：结果里要说明「这几项不是漏了，是按设计没跟着走」");
ok(/schema/.test(appData) && /SCHEMA_V2 = 2/.test(appData),
  "包结构带版本号（v2 起 prefs 按文件分组）");
ok(/private fun isFlatV1\(/.test(appData) && /if \(v\.has\("t"\)\) return true/.test(appData),
  "读侧能认出 v1 的扁平结构（旧包必须继续能导）");
ok(/excludedCount\(data\)/.test(appData) && /dsh_bk_excluded_note/.test(backup),
  "按设计跳过的项数会如实告诉用户");

console.log("─ 5l. 外观走主题包：写进包、按顺序恢复、失败不误导");
ok(/const val THEME = "dsh-folk\/theme\.zip"/.test(archive),
  "外观主题包落在 dsh-folk/theme.zip（与 app-data.json 同一前缀）");
ok(/copyInto\(zos, theme, THEME\)/.test(archive) && /themeBytes = theme\.length\(\)/.test(archive),
  "补包时把主题包写进包并记下大小");
ok(/ThemeManager\.exportTheme\(/.test(backup) && /ThemeManager\.ThemeMetadata\(/.test(backup),
  "导出用的就是既有的主题导出通路（外观带资源文件，prefs 搬不动）");
ok(/private suspend fun exportThemeZip\(/.test(backup) &&
  /trace\(ctx, "theme-export-failed " \+ describe\(e\)\)/.test(backup),
  "外观打不出来不阻断导出，只记一笔并如实报「不含外观」");
ok(/private suspend fun restoreThemeZip\(/.test(backup) && /ThemeManager\.importTheme\(ctx, Uri\.fromFile\(tmp\)\)/.test(backup),
  "恢复走既有的主题导入通路（它会把 file:// URI 重写成新路径，并刷 UI）");
ok(/enum class ThemeOutcome \{ RESTORED, ABSENT, FAILED \}/.test(backup) &&
  /ThemeOutcome\.ABSENT -> ctx\.appString\(R\.string\.dsh_bk_theme_absent\)/.test(backup),
  "三种结局（恢复/包里有但这次没走软件数据/失败）分开说，旧包不会被当成失败");
// 顺序：软件数据在前、主题在后（两边都写外观参数，主题后落地才是最终生效的那份）
const themeOrderApply = backup.indexOf("DshAppData.apply(ctx, data)");
const themeOrderRestore = backup.indexOf("restoreThemeZip(ctx, plainZip");
const themeOrderApply2 = backup.indexOf("DshAppData.apply(ctx, appData)");
const themeOrderRestore2 = backup.indexOf("restoreThemeZip(ctx, plainZip", themeOrderApply2);
ok(themeOrderRestore > themeOrderApply && themeOrderRestore2 > themeOrderApply2,
  "主题在软件数据之后落地（自定义主色/首页布局/夜间模式在 config 里，两边都会写）");
ok(/hasDshSections\(plainZip\)/.test(backup) && /DshAppData\.summarize\(plainZip\)/.test(backup),
  "纯软件数据包也有内容摘要（预览步不能是空白）");
// 外观不是只有「纯软件数据包」才有：混合包（软件数据 + DSH 分区）同样带主题包，
// 而恢复外观有副作用（替换背景/字体/音乐、可能换语言）。所以「有没有外观」必须
// 对两种包都算出来，不能只看纯软件数据包的那份摘要。
ok(/val themeBytes: Long = -1L/.test(backup) &&
  /themeBytes = if \(summary\.theme\) summary\.themeBytes else -1L/.test(backup) &&
  /themeBytes = DshBackupArchive\.entrySize\(plainZip, DshBackupArchive\.THEME\)/.test(backup),
  "预检在两条分支里都标出「包里有没有外观主题包」（纯软件数据包用流式数出来的尺寸，混合包走中央目录）");
ok(/fun entrySize\(zip: File, name: String\): Long/.test(archive) &&
  /java\.util\.zip\.ZipFile\(zip\)/.test(archive),
  "只看中央目录读条目长度：不为问一句「外观在不在」把上百兆的包再流一遍");
ok(/val themeIncluded = preflight != null && preflight\.themeBytes >= 0L/.test(wizard),
  "预览与确认都按这个字段判断外观，而不是只在纯软件数据包那一支里显示");

console.log("─ 5m. 工作区路径提取：按条目名，不靠（不可靠的）条目尺寸");
// 真机现场（beta.65）：报告里连「已补建 N 个缺失目录」那行都没有，插件照旧报
// realpath ENOENT。根因是提取器用 `e.size in 1..2MB` 当门槛，而 App 自己合并包时
// 是 zos.putNextEntry(ZipEntry(name))（不预设尺寸）→ Java 走 data descriptor →
// ZipInputStream.getSize() 拿到 -1/0，判据恒假，**一个条目都读不到**。
// 所以判据必须是「条目名」这种稳定事实，尺寸只能从中央目录取、且不能当门槛。
// 只看代码、不看注释：这条判据的「为什么」本身就要在 KDoc 里写出旧写法，
// 否则下一个读代码的人还会踩同一个坑（注释里的字符串不该被当成违规）。
ok(!/e\.size in 1\.\./.test(code(backup)),
  "不再用条目尺寸当过滤门槛（data descriptor 条目上它是 -1/0，会让整段逻辑静默不执行）");
ok(/private const val WORKSPACES_ENTRY = "workspaces\/workspaces\.json"/.test(backup),
  "按插件定死的位置精确匹配 workspaces 分区（SECTION_JSON_PATHS.workspaces）");
ok(/private fun isWorkspacesEntry\(name: String\): Boolean/.test(backup) &&
  /name\.contains\("\/workspaces\/"\) && base\.endsWith\("\.json"\)/.test(backup),
  "另有宽松兜底：目录名含 workspaces 的 json 也认（插件改布局不会再次静默失效）");
ok(/if \(entryName\.isEmpty\(\)\) onNote\?\.invoke\("workspaces-entry-missing"\)/.test(backup),
  "找不到分区时留一条诊断（这次的教训：静默失效比报错更难查）");
ok(/\(wantedDirs, dirsSource\) = workspacePathsInZip\(plainZip\) \{ dirNotes \+= it \}/.test(backup) &&
  /for \(note in dirNotes\) trace\(ctx, note\)/.test(backup),
  "提取过程的诊断进备份日志（bugreport 里能看到分区在不在、读到多少字节）");
ok(/if \(wantedDirs\.isEmpty\(\)\) \{\s*\n\s*trace\(ctx, "import-dirs-none source="/.test(backup),
  "一个路径都没读到也记一笔（这正是本次真机的现场）");

console.log("─ 5n. 向导的交互细节（真机反馈 beta.65）");
// 1) 「高亮」只能表示已选中。曾经把「推荐」也画成 primaryContainer 底，于是没选中的
//    推荐项看起来跟选中一样 —— 用户以为「停机恢复」已是默认，其实一条都没选，
//    于是「下一步」点不动（他会以为按钮坏了）。
ok(/val bg = if \(selected\) MaterialTheme\.colorScheme\.primaryContainer/.test(wizard),
  "选项底色只由 selected 决定（「推荐」不再是高亮）");
ok(!/if \(recommended\) MaterialTheme\.colorScheme\.primaryContainer/.test(wizard),
  "不存在「推荐就用高亮底」这条老写法");
ok(/dsh_bk_wiz_recommended/.test(wizard) && /secondaryContainer/.test(wizard),
  "「推荐」退化成一枚小标记（信息仍在，但不再冒充选中）");
// 2) 进向导不要生硬：设置页点「导入备份」先落到向导的选择步，
//    由那一步的按钮去拉系统选择器（而不是当场弹系统框再甩用户一个陌生整页）。
ok(!/onDshImport = \{[\s\S]{0,600}importPicker\.launch/.test(screen),
  "点「导入备份」不再当场拉起系统选择器");
ok(/onDshImport = \{[\s\S]{0,200}navigator\.navigate\(\s*\n?\s*RestoreWizardScreenDestination\(stagedPath = null\)/.test(screen),
  "而是导航到一个真正的向导页（返回交给导航栈，手势与箭头一致）");
// 这次的结构性教训：向导曾是备份页里的一个分支，于是「返回」有两套语义 ——
// 箭头退一步（手写的）、手势把整页弹掉（导航栈的）。现在它是一个路由。
ok(/@Destination<RootGraph>/.test(wizardScreen) && /fun RestoreWizardScreen\(/.test(wizardScreen),
  "恢复向导是独立目的地（与权限记录页同一模式）");
ok(/BackHandler\(enabled = true\) \{ handleBack\(\) \}/.test(wizardScreen) &&
  /IconButton\(onClick = \{ handleBack\(\) \}\)/.test(wizardScreen),
  "返回箭头与系统返回手势共用同一个 handleBack（两套入口各写一份正是之前的坑）");
ok(/WizardStep\.SELECT, WizardStep\.RESULT -> exit\(\)/.test(wizardScreen) &&
  /WizardStep\.EXECUTE -> Unit/.test(wizardScreen),
  "退到头才离开这一页；执行中不退（插件已在写盘，退出只留半写入状态）");
ok(/if \(fileName\.isEmpty\(\)\) \{\s*\n\s*Button\(onClick = onPickFile\)/.test(wizard),
  "没选文件时主按钮就是「选择文件」（第一眼就知道这一步要干什么）");
ok(/dsh_bk_wiz_selected_file/.test(wizard),
  "选完文件后把文件名与「换一个文件」摆在明面上（用户刚从系统选择器回来）");

console.log("─ 5o. 预览页逐条勾选（真机反馈：每个条目应该能单独选）");
// 状态记「被取消的」而不是「被选中的」：计划项可能比预览列出的多（MAX_PREVIEW_ITEMS
// 截断），按选中集合提交等于让截断替用户决定「这些不导入」。
ok(/var excluded by rememberSaveable \{ mutableStateOf<Set<String>>\(emptySet\(\)\) \}/.test(wizardScreen),
  "取消勾选的项记成「排除集」（而不是选中集），没显示出来的条目不会被静默丢掉");
ok(/excludedItems = excluded/.test(wizardScreen) && /excludedItems: Set<String> = emptySet\(\)/.test(backup),
  "排除集一路传到 import()");
ok(/if \(excludedItems\.isNotEmpty\(\)\) \{/.test(backup) &&
  /planObj\.put\("items", kept\)/.test(backup) &&
  /trace\(ctx, "import-plan-filtered removed="/.test(backup),
  "执行前按排除集过滤 plan.items，并把「去掉了几条」记进日志");
// 冲突项不提供勾选框：它不是「要不要导入」，而是「哪一边说了算」，下一步会逐条问。
// 两处表达同一件事会互相矛盾。
ok(/selectable = item\.kind != "Conflict"/.test(wizard) &&
  /if \(selectable\) onToggle\(\)/.test(wizard) &&
  /dsh_bk_wiz_item_conflict_note/.test(wizard),
  "冲突项的勾选框禁用并说明原因（由下一步逐条决定）");
ok(/onSelectAllItems = \{ all ->/.test(wizardScreen) &&
  /\.filter \{ it\.kind != "Conflict" \}/.test(wizardScreen),
  "「全不选」不会把冲突项也算进去");
ok(/Checkbox\(/.test(wizard) && /dsh_bk_wiz_pick_items/.test(wizard) &&
  /dsh_bk_wiz_select_all/.test(wizard) && /dsh_bk_wiz_select_none/.test(wizard),
  "预览页有勾选框与全选/全不选");

console.log("─ 5p. 快照带回软件设置（真机反馈：快照里没有软件设置项，回不回去）");
// 插件快照按 SECTION_IDS 采集，够不到 App 的 SharedPreferences 与外观资源文件，
// 所以「恢复快照」必须由 App 自己补一份软件设置的副本。
const appSnap = fs.readFileSync(SRC_APPDATA_SNAPSHOT, "utf8");
ok(/object DshAppDataSnapshot/.test(appSnap), "有 App 侧的软件设置快照存储");
ok(/DshAppData\.collect\(ctx\)/.test(backup) &&
  /val appDataForSnapshot = DshAppData\.collect\(ctx\)/.test(backup),
  "导入前把当前软件设置拍一份（必须在 /execute 之前：要的是导入前的值）");
ok(/val execSnapshotId = execObj\.optString\("snapshotId"\)/.test(backup) &&
  /DshAppDataSnapshot\.write\(ctx, execSnapshotId/.test(backup),
  "拿到插件返回的快照 id 后挂到那个 id 下（id 只有 /execute 之后才存在）");
ok(/import-appdata-snapshot id=/.test(backup), "这一步进日志");
ok(/fun restore\(ctx: Context, snapshotId: String\): Outcome/.test(appSnap) &&
  /DshAppData\.apply\(ctx, json\)/.test(appSnap),
  "恢复时复用同一套 DshAppData.apply（不另造一份格式）");
ok(/isValidId\(id: String\): Boolean/.test(appSnap) && /it\.isDigit\(\)/.test(appSnap),
  "快照 id 必须先校验再当目录名（id 来自插件，直接拼路径能写到别处去）");
ok(/MAX_KEPT = 10/.test(appSnap) && /drop\(MAX_KEPT\)/.test(appSnap),
  "限制保留份数（这是顺手留的回退点，不是备份，不该无限长大）");
ok(/DshAppDataSnapshot\.delete\(ctx, snapshotId\)/.test(backup),
  "删除快照时连带清掉软件设置副本（不留孤儿）");
// 界面：默认勾选 + 只有真的存过才给这个开关（否则它是个骗人的开关）
ok(/var snapshotWithAppData by rememberSaveable \{ mutableStateOf\(true\) \}/.test(screen),
  "「同时回退软件设置」默认勾选（点「恢复快照」就是要回到那时候）");
// 断行为而不是断某一行的写法：这次为了把「读文件」移出主线程，把它拆成了两行，
// 断言就误报了 —— 门禁咬住无关的排版会让人去改断言而不是改代码。
ok(/val hasAppData = DshAppDataSnapshot\.has\(context, snap\.id\)/.test(screen) &&
  /snapshotHasAppData = hasAppData/.test(screen) &&
  /if \(snapshotHasAppData\) \{/.test(screen),
  "只有存过设置副本才显示这个选项，否则说明「这份快照没有软件设置」");
ok(/DshAppDataSnapshot\.Outcome\.RESTORED/.test(screen) &&
  /dsh_bk_snapshot_appdata_restored/.test(screen),
  "回退结果如实告诉用户（成功/失败/这份快照没有）");

console.log("─ 6. 失败不许虚报");
ok(/private fun copyToPublic\(ctx: Context, src: File, name: String\): Pair<String, Boolean>/.test(backup),
  "copyToPublic 返回 (位置, 是否真的落进公共目录)");
ok(/target\.exists\(\) && target\.length\(\) == src\.length\(\)/.test(backup),
  "复制后校验存在且大小一致");
ok(/if \(!copied\) return src\.absolutePath to false/.test(backup),
  "复制失败退回暂存文件真实路径");
ok(/if \(!publicOk\) append\("\\n! "\)\.append\(ctx\.appString\(R\.string\.dsh_bk_copy_failed\)\)/.test(backup),
  "并把这件事写进给用户看的结果里");
ok(/val copied = runCatching \{ src\.copyTo\(target, overwrite = true\) \}\.isSuccess &&/.test(backup),
  "不再丢弃 copyTo 的结果（原来 runCatching 的返回值没人看）");

console.log("─ 7. 既有能力不许被这次改动碰坏");
ok(/safeSessionRel\(entry\.name\)/.test(backup) && /canonicalPath/.test(backup),
  "会话恢复的 ZIP 路径穿越校验还在");
ok(/SESSION_PREFIX = "sessions\/"/.test(backup), "会话条目前缀判定还在");
ok(/put\("includeSecrets", false\)/.test(backup), "导出仍然不带凭据");
ok(/if \(Build\.VERSION\.SDK_INT <= Build\.VERSION_CODES\.R\)/.test(backup),
  "MediaStore 的 IS_PENDING 处理还在（否则备份在「下载」里不可见）");

console.log("─ 5q. 含软件数据的档位：App 经 fs-bridge 补包给插件");
// 软件数据（prefs + 外观）住在 Android 私有目录，插件够不到；App 经文件桥 /cloud/appdata/*
// 出/收整包。复用 DshConfigBackup 既有的导出/导入通路，不另造格式。
ok(/path\.startsWith\("\/cloud\/"\) -> dispatchCloud/.test(fsBridge),
  "文件桥分发 /cloud/ 到 dispatchCloud");
ok(/"\/cloud\/appdata\/status"/.test(fsBridge) &&
  /"\/cloud\/appdata\/export"/.test(fsBridge) &&
  /"\/cloud\/appdata\/restore"/.test(fsBridge),
  "补包端点族 status / export / restore 齐全");
// 补包与 /fs、/native 共用同一 token + 回环守卫（复用同一 handle 前置检查）
ok(/tokenMatches\(headers\[HEADER_TOKEN\.lowercase\(\)\]\)/.test(fsBridge),
  "补包端点同样受 token + 回环守卫（与 /fs /native 同一道检查）");
// 导出：档位字符串 → BackupScope；含软件数据的三档才走 App
ok(/"app-only" -> BackupScope\.APP_ONLY/.test(cloudAppData) &&
  /"app-dsh" -> BackupScope\.BOTH/.test(cloudAppData) &&
  /"app-dsh-vault" -> BackupScope\.BOTH_VAULT/.test(cloudAppData),
  "档位→BackupScope 映射覆盖含软件数据的三档");
ok(/DshConfigBackup\.exportArchive\(ctx, plan\)/.test(cloudAppData) &&
  /DshConfigBackup\.import\(/.test(cloudAppData),
  "出包/收包复用 DshConfigBackup 既有通路（不另造格式）");
// vault 档强制加密
ok(/scopeNeedsVaultPassword\(scope\) && !encrypt/.test(cloudAppData),
  "含 vault 的档位必须加密（否则拒绝出包）");

console.log("─ 5r. 新档位与预装");
// BackupScope 新增 DSH_VAULT：纯 DSH + 凭据（插件回退用）
ok(/DSH_VAULT/.test(archive) &&
  /this != BackupScope\.DSH_ONLY && scope != BackupScope\.DSH_VAULT/.test(archive) === false,
  "BackupScope 新增 DSH_VAULT 档");
ok(/BackupScope\.BOTH_VAULT \|\| scope == BackupScope\.DSH_VAULT/.test(archive),
  "DSH_VAULT 也算含 vault（强制加密）");
// 预装清单加入 dsh-folk-cloud，且用 github 规格安装（不发 npm）
ok(/"dsh-folk-cloud"/.test(runtime) &&
  /SEED_SPECS = mapOf\("dsh-folk-cloud" to "github:IPF-Sinon\/dsh-folk-cloud"\)/.test(runtime),
  "SEED_PLUGINS 含 dsh-folk-cloud，用 github 规格安装");
ok(/DshPluginRepo\.install\(seedSpec\(pkg\)/.test(runtime),
  "预装用 seedSpec(pkg) 取安装规格（账本仍按包名记）");

console.log(bad === 0 ? `\n全部通过（${n} 项断言）` : `\n${bad}/${n} 项失败`);
process.exit(bad === 0 ? 0 : 1);
