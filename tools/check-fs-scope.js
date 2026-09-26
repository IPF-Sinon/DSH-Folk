#!/usr/bin/env node
/* 文件访问黑白名单（#3）+ 运行时替换前「建议先备份」（#1）+ 软件更新测速逐条补（#2）门禁。 */
const fs = require("fs");
let n = 0, bad = 0;
function ok(cond, msg) {
  n++;
  if (cond) { console.log("  \u2713 " + msg); }
  else { bad++; console.log("  \u2717 " + msg); }
}
function read(p) { return fs.readFileSync(p, "utf8"); }

const fa = read("app/src/main/java/me/bmax/apatch/dsh/DshFileAccess.kt");
const cr = read("app/src/main/java/me/bmax/apatch/dsh/ContainerRuntime.kt");
const env = read("app/src/main/java/me/bmax/apatch/dsh/DshEnv.kt");
const faScreen = read("app/src/main/java/me/bmax/apatch/ui/screen/settings/FileAccessScreen.kt");
const fnScreen = read("app/src/main/java/me/bmax/apatch/ui/screen/settings/FunctionSettingsScreen.kt");
const fn = read("app/src/main/java/me/bmax/apatch/ui/screen/settings/FunctionSettings.kt");
const updDialog = read("app/src/main/java/me/bmax/apatch/ui/component/UpdateDialog.kt");
const appUpdater = read("app/src/main/java/me/bmax/apatch/util/AppUpdater.kt");
const dshSource = read("app/src/main/java/me/bmax/apatch/dsh/DshSource.kt");

console.log("\u2500 #3 文件访问黑白名单：默认相册黑名单 + 挂载层生效");
ok(/val DEFAULT_DENY[^\n]*"DCIM"[\s\S]{0,60}"Pictures"[\s\S]{0,60}"Movies"[\s\S]{0,80}"Android\/media"/.test(fa),
  "默认黑名单 = DCIM / Pictures / Movies / Android/media");
ok(/fun allowDirs\(/.test(fa) && /fun denyDirs\(/.test(fa) &&
  /fun setAllowDirs\(/.test(fa) && /fun setDenyDirs\(/.test(fa),
  "白/黑名单各有读写入口");
// 黑名单：偏好缺失=默认；显式空数组=用户清空（不能回落默认）
ok(/return normalize\(stored \?: DEFAULT_DENY\)/.test(fa),
  "黑名单缺失时用默认、显式（含空数组）时用存的值");
// 规整：上级覆盖下级（段边界，不误伤同前缀兄弟）
ok(/fun normalize\(/.test(fa) && /isUnderOrEqual\(a, b\)/.test(fa),
  "normalize 让上级目录覆盖其下级条目");
ok(/child\.startsWith\("\$parent\/"\)/.test(fa),
  "覆盖判定按目录段边界（parent + / ），不误伤同前缀兄弟目录");
// 白名单非空 = 只放行；黑名单优先
ok(/if \(allow\.isEmpty\(\)\)/.test(fa),
  "白名单空 = 放行整棵树（再按黑名单遮蔽）；非空 = 只映白名单目录");
ok(/if \(deny\.any \{ isUnderOrEqual\(a, it\) \}\) continue/.test(fa),
  "黑名单优先：被黑名单覆盖的白名单目录整个不映");
// 挂载层：两个别名都要处理，用空目录遮蔽
ok(/GUEST_ALIASES = listOf\("\/sdcard", "\/storage\/emulated\/0"\)/.test(fa),
  "两个容器别名（/sdcard 与 /storage/emulated/0）都套名单");
ok(/fun storageBinds\(/.test(fa) && /maskPath to "\$alias\/\$d"/.test(fa),
  "被禁目录用空目录（maskPath）盖在容器路径上");

console.log("\u2500 #3 ContainerRuntime：存储绑定改为动态、两个运行时都用");
ok(!/arrayOf\("\/storage\/emulated\/0"/.test(cr),
  "BINDS 里不再写死共享存储（改由 DshFileAccess 动态组装）");
ok(/fun storageBinds\(ctx: Context\)/.test(cr) && /DshFileAccess\.storageBinds\(ctx/.test(cr),
  "ContainerRuntime.storageBinds 代理到 DshFileAccess");
ok((cr.match(/for \(\(host, guest\) in storageBinds\(ctx\)\)/g) || []).length === 2,
  "proot 与 proroot 两处 baseArgv 都追加了动态存储绑定");
ok(/fun fsMaskDir\(ctx: Context\): File/.test(env) &&
  /const val KEY_FS_ALLOW_DIRS/.test(env) && /const val KEY_FS_DENY_DIRS/.test(env),
  "DshEnv 有名单偏好键与空遮蔽目录");

console.log("\u2500 #3 UI：黑白名单页 + 改动需重启提示");
ok(/fun FileAccessScreen\(/.test(faScreen), "有文件访问范围子页");
ok(/DshFileAccess\.DEFAULT_DENY/.test(faScreen) && /dsh_fs_reset_deny_default/.test(faScreen),
  "支持一键恢复默认黑名单");
ok(/DshRuntime\.restart\(\)/.test(faScreen) && /dsh_fs_restart_needed/.test(faScreen),
  "改动后提示需重启并给「重启 DSH」");
ok(/onOpenFileAccess/.test(fn) && /FileAccessScreenDestination/.test(fnScreen),
  "权限页有入口跳到文件访问范围子页");

console.log("\u2500 #1 运行时替换前「建议先备份」，复用备份页导出组件");
ok(/var pendingRuntimeOp by remember/.test(fnScreen),
  "重装/切版本/导入前先挂起为 pendingRuntimeOp（不直接开跑）");
// 三处运行时替换都经 pendingRuntimeOp
ok((fnScreen.match(/pendingRuntimeOp = \{/g) || []).length >= 3,
  "重装 / 切版本 / 导入三处都走「建议备份」拦截");
ok(/fun RuntimeBackupAdviceDialog\(/.test(fnScreen) &&
  /BackupExportOptionsDialog\(/.test(fnScreen),
  "建议备份提示里复用 BackupExportOptionsDialog（不另写一套导出 UI）");
ok(/DshConfigBackup\.exportArchive\(/.test(fnScreen),
  "导出走与备份页同一条通路 exportArchive");

console.log("\u2500 #2 软件更新测速：逐条补，最快测完的先出，且不阻塞选择/下载");
ok(/suspend fun speedTest\(\s*onProgress/.test(appUpdater) &&
  /DshSource\.speedTest\(probeAll = onProgress != null, onProgress = onProgress\)/.test(appUpdater),
  "AppUpdater.speedTest 支持逐条回报（probeAll + onProgress）");
ok(/AppUpdater\.speedTest \{ partial ->/.test(updDialog) &&
  /sortedBy \{ rankKey\(it\) \}/.test(updDialog),
  "更新弹窗按完成度排序、逐条刷新结果");
ok(/private fun rankKey\(/.test(updDialog) && /r\.speedKBps > 0\.0 -> r\.estimatedMs/.test(updDialog),
  "排序键让「已测出吞吐」的按估算耗时最快在前");
ok(/onProgress\?\.invoke\(acc\)/.test(dshSource),
  "DshSource.speedTest 逐条回调仍在（组件依赖它）");
// 关键：测速期间不锁死选择/下载——busy 只含下载/校验，测速用独立 testing 标志
ok(/var testing by remember \{ mutableStateOf\(false\) \}/.test(updDialog) &&
  /var speedJob by remember/.test(updDialog),
  "测速用独立的 testing/speedJob 状态（不并进 busy）");
ok(/val busy = phase is AppUpdater\.Phase\.Downloading \|\|\s*\n\s*phase is AppUpdater\.Phase\.Verifying/.test(updDialog),
  "busy 只含下载/校验，不含测速（测速不锁选择与下载）");
ok(/speedJob\?\.cancel\(\)\s*\n\s*testing = false/.test(updDialog),
  "点「开始下载」会取消剩余测速、用当前选中线路直接下");

console.log(bad === 0 ? `\n全部通过（${n} 项断言）` : `\n${bad}/${n} 项失败`);
process.exit(bad === 0 ? 0 : 1);
