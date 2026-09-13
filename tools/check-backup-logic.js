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
ok(/onRestart = \{[\s\S]{0,200}DshRuntime\.restart\(\)/.test(screen),
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

console.log("─ 3. 冲突策略：三档可选、默认仍保守、选择落盘");
ok(/const val STRATEGY_MERGE = "merge"/.test(backup) &&
  /const val STRATEGY_REPLACE = "replace"/.test(backup) &&
  /const val STRATEGY_SKIP_EXISTING = "skipExisting"/.test(backup),
  "三个策略常量与插件 /plan 的 decisions.strategy 取值一致");
ok(/strategy: String = "merge"/.test(backup), "默认策略仍是插件侧的保守默认 merge");
ok(/strategy = importStrategy/.test(screen), "界面把用户选的策略传进 import()");
ok(/Triple\(DshConfigBackup\.STRATEGY_MERGE/.test(content) &&
  /Triple\(DshConfigBackup\.STRATEGY_REPLACE/.test(content) &&
  /DshConfigBackup\.STRATEGY_SKIP_EXISTING/.test(content),
  "界面上三档都在（不是只传参不给选）");
ok(/data class StrategyOption\(val id: String, val label: Int, val desc: Int\)/.test(content),
  "每档都带说明文案（用户要知道 merge 与 replace 的差别）");
ok(/importStrategy = prefs\.getString\(PREF_KEY_IMPORT_STRATEGY/.test(config) &&
  /putString\(PREF_KEY_IMPORT_STRATEGY, importStrategy\)/.test(config),
  "策略选择会持久化（下次进页面还是上次那档）");
ok(/importStrategy = it[\s\S]{0,120}BackupConfig\.save\(context\)/.test(screen),
  "改策略即落盘");

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
ok(/onCloudRestore = \{ entry ->[\s\S]{0,2000}DshConfigBackup\.import\(/.test(screen),
  "云端下载后复用同一条导入管道（策略/密码/会话选项一致），不另起一套");

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
