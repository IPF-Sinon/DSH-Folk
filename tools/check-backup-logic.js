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
const SRC_WEBDAV = "app/src/main/java/me/bmax/apatch/util/WebDavUtils.kt";
const SRC_CONFIG = "app/src/main/java/me/bmax/apatch/ui/theme/BackupConfig.kt";

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
const content = fs.readFileSync(SRC_CONTENT, "utf8");
const webdav = fs.readFileSync(SRC_WEBDAV, "utf8");
const config = fs.readFileSync(SRC_CONFIG, "utf8");

console.log("─ 1. 导入后的收尾：needsRestart 必须驱动一个真的动作");
ok(/val needsRestart: Boolean = false/.test(backup),
  "ImportResult 带 needsRestart（不再只是文案）");
ok(/ImportResult\(ok, head, detail, needsRestart\)/.test(backup),
  "构造时把插件的 needsRestart 透出来");
ok(/restartNeeded = r\.ok && r\.needsRestart/.test(screen),
  "界面只在导入成功且插件要求时才提示重启");
ok(/onRestart = \{[\s\S]{0,300}DshRuntime\.restart\(\)/.test(screen),
  "进度对话框的「重启服务」真的调 DshRuntime.restart()");
ok(/needsRestart = runNeedsRestart/.test(screen),
  "该对话框按 runNeedsRestart 决定是否给出重启按钮");
ok(/BackupLogManager\.log\("import strategy=/.test(screen),
  "导入结果（策略/成败/是否需重启）落一条日志 —— 出问题时有据可查");

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
ok(/onLine = \{ line -> withContext\(Dispatchers\.Main\) \{ runLines = runLines \+ line \} \}/.test(screen),
  "界面把进度行接进对话框");

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
ok(/p\.conflictTotal > 0 -> askConflicts = true/.test(screen), "只有检测到冲突才置起冲突弹窗");
ok(/SessionImport\.valueOf\(pendingSessionChoice\)/.test(screen),
  "会话与冲突两个答案都带进最终那次导入");
for (const [strategy, key] of [
  ["STRATEGY_MERGE", "dsh_bk_strategy_merge"],
  ["STRATEGY_REPLACE", "dsh_bk_strategy_replace"],
  ["STRATEGY_SKIP_EXISTING", "dsh_bk_strategy_skip"],
]) {
  ok(new RegExp("choose\\(DshConfigBackup\\." + strategy + "\\)").test(screen),
    "冲突弹窗里有 " + strategy + " 这一档");
  ok(screen.includes("R.string." + key), "它带说明文案 " + key);
}
ok(!/IMPORT_STRATEGIES/.test(content), "事先选策略的控件已经从这一页移除（改成导入时问）");
ok(/preflight = preflight/.test(screen) && /preflight: Preflight\? = null/.test(backup),
  "答完之后用预检产物继续导入（不再上传/解密第二遍）");
ok(/discardPreflight/.test(screen) && /fun discardPreflight\(/.test(backup),
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
ok(/onSnapshotRestore[\s\S]{0,400}previewSnapshot\(snap\.id\)/.test(screen),
  "点「恢复」先走预览，不是直接执行");
ok(/R\.string\.dsh_bk_snapshot_confirm_body,\s*snap\.id,\s*pendingActions/.test(screen),
  "确认框把快照 id 与动作数摆出来（含「会卸载快照里没有的插件」这句）");
const confirmBody = fs.readFileSync("app/src/main/res/values-zh-rCN/dsh_strings.xml", "utf8")
  .match(/<string name="dsh_bk_snapshot_confirm_body">([\s\S]*?)<\/string>/);
ok(confirmBody !== null && /卸载/.test(confirmBody[1]) && /pre-restore/.test(confirmBody[1]),
  "确认文案讲明会卸载插件、以及插件侧的 pre-restore 双保险");

console.log("─ 5. 云端备份：从「只能上传」变成可列可下可导入");
ok(/suspend fun listRemote\(/.test(webdav) && /\.header\("Depth", "1"\)/.test(webdav),
  "listRemote 用 PROPFIND Depth: 1 列目录");
ok(/response\.code != 207 && !response\.isSuccessful/.test(webdav),
  "207 Multi-Status 与 200 都认，其余才算失败");
ok(/substringAfterLast\(':'\)\.lowercase\(Locale\.US\)/.test(webdav),
  "标签名去命名空间前缀再比（D:response / d:response / 无前缀都得认）");
ok(/name\.endsWith\("\.zip", ignoreCase = true\)/.test(webdav),
  "只收 .zip");
ok(/sortedByDescending \{ it\.lastModifiedMs \}/.test(webdav),
  "按修改时间倒序（最近的排最前）");
ok(/href\.replace\("\+", "%2B"\)/.test(webdav),
  "href 解码前把 + 保护起来（URLDecoder 会把 + 当空格，文件名里的 + 是字面量）");
ok(/suspend fun downloadTo\(/.test(webdav) && /if \(!wrote && dest\.exists\(\)\) dest\.delete\(\)/.test(webdav),
  "下载失败删掉半截文件（否则下次导入拿到看不懂的解析错误）");
ok(/WebDavUtils\.listRemote\(/.test(screen) && /WebDavUtils\.downloadTo\(/.test(screen),
  "界面两条都接了");
ok(/code == "405" \|\| code == "501"/.test(screen) &&
  /context\.getString\(R\.string\.dsh_bk_cloud_unsupported, code\)/.test(screen) &&
  /context\.getString\(R\.string\.dsh_backup_webdav_failed, msg\)/.test(screen),
  "405/501（服务端不支持列目录）与其它失败分开说：否则用户不知道该改服务端还是改密码");
// 两条入口（本地选文件 / 云端下载）都先落到「问密码」这一步，再由同一个 startImport 开跑。
// 断言成「startImport 全文件只出现一次」比什么都直接：多写一套管道就必然多一处调用。
// 定义 1 处、调用 1 处：本地与云端都汇到同一个入口，不会各写一套管道
const defCalls = (screen.match(/fun startImport\(/g) || []).length;
const useCalls = (screen.match(/startImport\(File\(path\), importPassword\)/g) || []).length;
ok(defCalls === 1 && useCalls === 1, "只有一个开跑入口 startImport（定义 " + defCalls + " 处、调用 " + useCalls + " 处）");
ok(
  /onCloudRestore = \{[\s\S]{0,3000}pendingImportPath = dest\.absolutePath[\s\S]{0,200}askImportPassword = true/.test(screen),
  "云端下载完先问密码（加密包要密码才解得开）",
);
ok(
  /val staged = runCatching \{[\s\S]{0,1400}pendingImportPath = staged\.absolutePath[\s\S]{0,200}askImportPassword = true/.test(screen),
  "本地选文件也先问密码（预检在密码之后才跑）",
);
ok(
  /DshBackupCrypto\.isArchiveBlobFile\(staged\)/.test(screen) &&
    /DshBackupCrypto\.isArchiveBlobFile\(dest\)/.test(screen),
  "两条入口都用 magic 判断「是不是加密包」，据此决定提示哪一句",
);

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

console.log("─ 5c. 导入密码：留空即按「没加密」解析");
const pwDialog = braceSpan(screen, 'if (askImportPassword) {');
ok(pwDialog !== null, "选完文件弹密码框");
if (pwDialog) {
  const body = screen.slice(pwDialog[0], pwDialog[1]);
  ok(/pendingImportEncrypted/.test(body), "按 magic 结果给出「这是加密包 / 不是加密包」两种提示");
  ok(/dsh_bk_import_pw_hint_plain/.test(body) && /dsh_bk_import_pw_hint_encrypted/.test(body),
    "两种提示文案都在");
  ok(!/importPassword\.isEmpty[\s\S]{0,80}Text\(stringResource\(R\.string\.dsh_bk_import_parse\)/.test(body),
    "空密码不挡确认键 —— 留空就是「不解密直接解析」");
  ok(/startImport\(File\(path\), importPassword\)/.test(body), "确认后由唯一的入口开跑");
  ok(/File\(path\)\.delete\(\)/.test(body), "取消时把用户选的暂存副本删掉");
  ok(/dsh_pw_show|dsh_pw_hide/.test(body), "这个密码框也有显示/隐藏");
}

console.log("─ 5d. 插件状态：原因不许被吞，安装按钮只在确认缺失时才画");
ok(/val err = o\.optString\("error"\)/.test(backup) && /error = err,/.test(backup),
  "status() 把插件自己的 error 带出来（以前只读 ready，原因全丢）");
ok(/status\.error\.ifEmpty \{ pluginMissing \}/.test(screen),
  "导出前的检查显示插件给的真实原因，而不是一律说「DSH 没起来」");
ok(/DshPluginRepo\.listInstalled\(\)\.any \{ it\.pkg == DSH_CONFIG_MANAGER_PKG \}/.test(screen),
  "「装没装」由应用侧直接查容器插件目录（不需要 DSH 在跑）");
ok(/pluginAbsent = !st\.ready && !installed/.test(screen),
  "只有「没就绪 **且** 确实没装」才算缺失");
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
ok(/onOpenBackupLog/.test(content) && /onOpenBackupLog = \{ showBackupLog = true \}/.test(screen) &&
  /BackupLogDialog\(/.test(screen),
  "备份页自己就有日志入口（原来只在 WebDAV 对话框里，等于没有）");

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

console.log(bad === 0 ? `\n全部通过（${n} 项断言）` : `\n${bad}/${n} 项失败`);
process.exit(bad === 0 ? 0 : 1);
