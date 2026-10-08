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
/** 剥 Kotlin 注释：`must not appear` 类断言必须扫剥过的文本（文档里会提到被禁的写法）。 */
function code(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/[^\n]*/g, "");
}

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

console.log("\u2500 #3b 工作区挂载：把手机存储额外映到 /root/workspace 下");
ok(/const val WORKSPACE_GUEST = "\/root\/workspace"/.test(env),
  "DshEnv 有 WORKSPACE_GUEST 常量（工作区挂载目的根）");
ok(/const val KEY_WS_MOUNTS\b/.test(env),
  "DshEnv 有工作区映射列表键");
// 2026-10：原来的「在工作区中挂载手机存储」子开关与「共享存储」合并成一个 —— 两处开关管
// 同一件事，分开就会出现「总开关关着、工作区却能看到」这种自相矛盾的状态。所以下面钉的是
// **子开关必须已经不存在**，而且判据只认总开关。
ok(!/KEY_WS_MOUNT\b/.test(env) && !/fun wsMountEnabled\(/.test(fa) && !/fun setWsMountEnabled\(/.test(fa),
  "子开关键与它的读写入口都已撤掉（不留一个没人用的键）");
ok(/const val KEY_STORAGE_MOUNT/.test(env) && /fun mountEnabled\(ctx: Context\): Boolean/.test(fa) &&
  /getBoolean\(DshEnv\.KEY_STORAGE_MOUNT, true\)/.test(fa),
  "共享存储总开关仍在，且默认开");
ok(/fun workspaceMounts\(/.test(fa) && /fun setWorkspaceMounts\(/.test(fa),
  "工作区映射有读写入口");
ok(/DEFAULT_WS_MOUNTS[\s\S]{0,60}WsMount\("", "sdcard"\)/.test(fa),
  "默认映射 = 整棵 /sdcard → /root/workspace/sdcard");
ok(/fun normalizeDest\(/.test(fa) && /it != "\.\." /.test(fa),
  "dest 规整禁止 .. 越界");
ok(/fun workspaceBinds\(/.test(fa) &&
  /if \(!mountEnabled\(ctx\)\) return emptyList\(\)/.test(fa),
  "workspaceBinds 只看共享存储总开关（合并之后它不再有自己的闸）");
// 只钉「存在」是不够的：这个文件里有两处一模一样的闸（共享存储 / 工作区），改掉其中一处
// 另一处还在，全文匹配照样绿。所以按函数切开，各自钉住自己的那一条。
const crFn = (name) => (cr.match(new RegExp("fun " + name + "\\(ctx: Context\\)[\\s\\S]*?\\n        \\}")) || [])[0] || "";
ok(/if \(!DshFileAccess\.mountEnabled\(ctx\)\) return emptyList\(\)/.test(crFn("storageBinds")) &&
  /if \(!DshFileAccess\.mountEnabled\(ctx\)\) return emptyList\(\)/.test(crFn("workspaceBinds")),
  "容器的两条挂载路（共享存储 / 工作区）都跟同一个总开关");
ok(/if \(deny\.any \{ isUnderOrEqual\(src, it\) \}\) continue/.test(fa),
  "工作区映射：src 命中黑名单整条跳过");
ok(/maskPath to "\$guestBase\/\$\{relUnder\(d, src\)\}"/.test(fa) &&
  /maskPath to "\$guest\/\$\{relUnder\(d, a\)\}"/.test(fa),
  "工作区映射：被禁子目录仍用空目录遮蔽（无/有白名单两条路径都遮）");
ok(/fun contains\(base: String, child: String\)/.test(fa) && /base\.isEmpty\(\) \|\| isUnderOrEqual\(child, base\)/.test(fa),
  "contains 把空 src 视为 /sdcard 根（包含一切）——空 src 也能正确遮罩/圈定白名单");
ok(/if \(deny\.any \{ isUnderOrEqual\(a, it\) \}\) continue/.test(fa),
  "工作区映射有白名单时黑名单优先：被黑名单盖掉的白名单目录不映");
ok(/val guestBase = "\$\{DshEnv\.WORKSPACE_GUEST\}\/\$\{m\.dest\}"/.test(fa),
  "工作区映射的 guest 路径落在 WORKSPACE_GUEST 下");

console.log("\u2500 #3c 工作区挂载：共享存储不支持硬链接 → 探测并显著提示（不改挂载行为）");
// 为什么单探一次：DshRuntime.hardlinkSupported 只探 rootfs（ext4→true），于是 proot 不加
// --link2symlink；但链接能力是逐挂载点的。dsh write 工具用 link() 发布，共享存储
// （sdcardfs/FUSE）上直接 EINVAL —— 挂进工作区后这个坑才暴露出来。
ok(/fun storageLinkSupported\(ctx: Context\)/.test(fa),
  "DshFileAccess 有共享存储硬链接探测 storageLinkSupported");
ok(/getExternalFilesDir\(null\) \?: File\(HOST_ROOT\)/.test(fa) && /Files\.createLink\(/.test(fa),
  "探针打在共享存储所在文件系统上（优先 App 专属外部目录、退回 HOST_ROOT）+ createLink");
ok(/catch \(e: Throwable\) \{\s*\n\s*ok = false/.test(fa),
  "探测异常（含无权限）一律判为不支持");
ok(/storageLinkOk\?\.let \{ return it \}/.test(fa) && /@Volatile/.test(fa),
  "探测结果有缓存，避免每次重组都探盘");
ok(/fun resetStorageLinkProbe\(\)/.test(fa) && /storageLinkOk = null/.test(fa),
  "有 resetStorageLinkProbe 供 UI 重新检测");
// 明确不改挂载行为：proot 的 --link2symlink 是全局开关，按挂载点开不了，且会破坏 pnpm
ok(!/link2symlink/.test(code(fa)),
  "DshFileAccess 不碰 --link2symlink（只探测/提示，不改挂载）");
ok(/if \(!hardlinkSupported\) argv\.add\("--link2symlink"\)/.test(cr),
  "proot 的 --link2symlink 仍只由 rootfs 硬链接能力决定（本轮未改）");
ok(/if \(!storageLinkOk\)/.test(faScreen),
  "UI 在探测到不支持时显示紧凑警告");
ok(/dsh_ws_mount_warn_title/.test(faScreen),
  "紧凑警告只留一行标题（详细说明走宿主提示词，不占屏幕）");
ok(!/dsh_ws_mount_warn_body/.test(faScreen),
  "人类 UI 不再渲染大块警告正文（不再占一大块屏幕）");
ok(/DshFileAccess\.resetStorageLinkProbe\(\)/.test(faScreen) &&
  /storageLinkOk = DshFileAccess\.storageLinkSupported\(context\)/.test(faScreen),
  "「重新检测」清缓存后重探");
ok(/dsh_ws_mount_source/.test(faScreen) && /dsh_ws_mount_destination/.test(faScreen),
  "映射显示拆成「源/目标」两行，路径不再在窄屏中间被折行截断");

const hostPrompt = read("app/src/main/java/me/bmax/apatch/dsh/DshHostPrompt.kt");
const hostMjs = read("app/src/main/assets/dsh-folk-host.mjs");
// 内容哈希：原来是「只钉 PLUGIN_REV 这个数字」，等于什么都没保证 —— 改了 .mjs 却忘了抬版本时
// 它照样绿，而老设备的 ensureInstalled 认为「版本没变」，落盘的还是旧插件。现在钉内容：
// 改了 .mjs 就必须抬 PLUGIN_REV 并同步这里的哈希（重算：sha256sum app/src/main/assets/dsh-folk-host.mjs）。
const mjsSha = require("crypto").createHash("sha256").update(hostMjs, "utf8").digest("hex");
const MJS_SHA = "90ac8608a7e8cd5354878811a5f553b08da2d9ac400b22de09e9c62eaf86c4cb";
console.log("\u2500 #3d 宿主提示词：把工作区挂载与硬链接限制注入 AI（不落在大块 UI 里）");
ok(/workspaceStorageMounted/.test(hostPrompt) && /workspaceStorageMappings/.test(hostPrompt) &&
  /storageHardlinkSupported/.test(hostPrompt),
  "host-facts 里写入工作区挂载状态/映射表/共享存储硬链接探测结果");
ok(/if \(f\.workspaceStorageMounted === true\)/.test(hostMjs) &&
  /storageHardlinkSupported === false/.test(hostMjs),
  "宿主提示词仅在工作区挂载开且不支持硬链接时渲染工作区挂载段");
ok(/### Phone storage inside the workspace/.test(hostMjs) && /EINVAL/.test(hostMjs),
  "宿主提示词明说 write 工具会撞 EINVAL、推荐 edit/shell 重定向");
ok(/PLUGIN_REV = 17/.test(hostPrompt) && mjsSha === MJS_SHA,
  "改了 .mjs 就必须同时抬 PLUGIN_REV 并更新这里的内容哈希（抬版本是 ensureInstalled 重新落盘的唯一依据）");

console.log("\u2500 #3 ContainerRuntime：存储绑定改为动态、两个运行时都用");
ok(!/arrayOf\("\/storage\/emulated\/0"/.test(cr),
  "BINDS 里不再写死共享存储（改由 DshFileAccess 动态组装）");
ok(/fun storageBinds\(ctx: Context\)/.test(cr) && /DshFileAccess\.storageBinds\(ctx/.test(cr),
  "ContainerRuntime.storageBinds 代理到 DshFileAccess");
ok((cr.match(/for \(\(host, guest\) in storageBinds\(ctx\)\)/g) || []).length === 2,
  "proot 与 proroot 两处 baseArgv 都追加了动态存储绑定");
ok((cr.match(/for \(\(host, guest\) in workspaceBinds\(ctx\)\)/g) || []).length === 2,
  "proot 与 proroot 两处 baseArgv 都追加了工作区挂载绑定");
ok(/fun workspaceBinds\(ctx: Context\)/.test(cr) && /DshFileAccess\.workspaceBinds\(ctx/.test(cr),
  "ContainerRuntime.workspaceBinds 代理到 DshFileAccess");
ok(/fun fsMaskDir\(ctx: Context\): File/.test(env) &&
  /const val KEY_FS_ALLOW_DIRS/.test(env) && /const val KEY_FS_DENY_DIRS/.test(env),
  "DshEnv 有名单偏好键与空遮蔽目录");

console.log("\u2500 #3 UI：黑白名单页 + 改动需重启提示");
ok(/fun FileAccessScreen\(/.test(faScreen), "有文件访问范围子页");
ok(/DshFileAccess\.DEFAULT_DENY/.test(faScreen) && /dsh_fs_reset_deny_default/.test(faScreen),
  "支持一键恢复默认黑名单");
ok(/DshRuntime\.restart\(\)/.test(faScreen) && /dsh_fs_restart_needed/.test(faScreen),
  "改动后提示需重启并给「重启 DSH」");
ok(/dsh_ws_mount_header/.test(faScreen) && /DshFileAccess\.setWorkspaceMounts\(/.test(faScreen),
  "文件访问页仍保留「挂载进工作区」段与映射列表编辑");
ok(!/Switch\(checked = wsMount/.test(faScreen) && /dsh_ws_mount_follows_on/.test(faScreen) &&
  /dsh_ws_mount_follows_off/.test(faScreen),
  "那一段不再有自己的 Switch，而是按总开关显示「现在生不生效」");
ok(/wsMounts\.toList\(\) != initialWsMounts/.test(faScreen) && !/initialWsMount\b/.test(faScreen),
  "映射列表改动仍纳入 dirty（触发需重启横幅），且已无 wsMount 快照");
// 合并之后「共享存储」这一个开关的文案必须把工作区那一半也说进去：用户看到的开关只有一个，
// 而它现在同时决定容器挂载与工作区映射，文案不说清就变成「我明明没开工作区，它却看得到」。
{
  const zh = read("app/src/main/res/values-zh-rCN/dsh_strings.xml");
  const en = read("app/src/main/res/values/dsh_strings.xml");
  // 精确到属性名：少一个字母的后缀（follows_off_x）也是"包含"，那样的断言拦不住改名
  ok(/name="dsh_ws_mount_follows_on">/.test(zh) && /name="dsh_ws_mount_follows_off">/.test(zh) &&
    /name="dsh_ws_mount_follows_on">/.test(en) && /name="dsh_ws_mount_follows_off">/.test(en),
    "两行状态文案中英各有（页面按总开关显示「现在生不生效」）");
  ok(/name="dsh_storage_mount_hint">[^<]*工作区映射/.test(zh) &&
    /name="dsh_storage_mount_hint">[^<]*workspace mappings/.test(en) &&
    /name="dsh_ws_mount_desc">[^<]*共享存储/.test(zh) &&
    /name="dsh_ws_mount_desc">[^<]*Shared storage/.test(en),
    "共享存储那张卡的说明与工作区那段的说明都点明了「是同一个开关」（中英各一份）");
}
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

// ── 共享存储那条说明必须整段在 !storageLinkOk 分支里 ──
//
// 用户报过：设备上硬链接其实可用（上面那行"重新检测"也因此不显示），"无硬链接 / 无 exec 位"
// 那段说明却照样挂着 —— 与事实不符，也与宿主提示词那一侧（storageHardlinkSupported === false
// 才渲染）不一致。判据用**括号配对**取那块分支，再要求 note 只出现在这块里。
function fsScopeBranchBody(src, header) {
  const at = src.indexOf(header);
  if (at < 0) return "";
  const open = src.indexOf("{", at);
  if (open < 0) return "";
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    if (src[i] === "{") depth++;
    else if (src[i] === "}") { depth--; if (depth === 0) return src.slice(open, i + 1); }
  }
  return "";
}
{
  const linkWarn = fsScopeBranchBody(faScreen, "if (!storageLinkOk) {");
  const noteAll = (faScreen.match(/R\.string\.dsh_ws_mount_note/g) || []).length;
  const noteInWarn = (linkWarn.match(/R\.string\.dsh_ws_mount_note/g) || []).length;
  ok(linkWarn.length > 0 && noteInWarn === 1 && noteAll === noteInWarn,
    "「共享存储不支持硬链接」那段说明整段在 !storageLinkOk 分支里（硬链接可用时不该出现）");
  ok(/R\.string\.dsh_ws_mount_recheck/.test(linkWarn) && /resetStorageLinkProbe\(\)/.test(linkWarn),
    "「重新检测」也在同一分支里（有硬链接时不需要它）");
}

// ── 数据目录：一个入口两条路（原来是两个卡片） ──
//
// 用户问「怎么还有在文件管理器中打开数据目录和直接授权给 MT 管理器？不是合并成一个了吗」——
// 原来确实是两张卡：一张系统选择器、一张 MT 直授。对用户这是同一件事（让第三方应用能访问
// 数据目录），第二张还只在装了 MT 管理器时出现，看起来像另一个功能。现在合成一张卡。
{
  const fn = read("app/src/main/java/me/bmax/apatch/ui/screen/settings/FunctionSettings.kt");
  const reg = read("app/src/main/java/me/bmax/apatch/ui/screen/settings/SettingsRegistry.kt");
  const manifest = read("app/src/main/AndroidManifest.xml");
  const zhS = read("app/src/main/res/values-zh-rCN/dsh_strings.xml");
  const enS = read("app/src/main/res/values/dsh_strings.xml");

  const itemBlock = fsScopeBranchBody(fn, 'item(key = "function_docs_access"');
  ok(itemBlock.length > 0 && /ACTION_OPEN_DOCUMENT_TREE/.test(itemBlock) &&
    /showGrantDocsDialog\.value = true/.test(itemBlock),
    "合并后的那一个入口里两条路都在：系统选择器 + 直接授权（MT 那条只在装了 MT 时显示）");
  ok(!/function_open_data_dir/.test(fn) && !/function_open_data_dir/.test(reg) &&
    !/function_grant_docs_mt/.test(fn) && !/function_grant_docs_mt/.test(reg),
    "原来那两个 item key 已经不存在（否则就是两处入口各说各话）");
  ok((reg.match(/SettingEntry\(\s*"function_docs_access"/g) || []).length === 1 &&
    /R\.string\.dsh_docs_access_title/.test(reg),
    "设置搜索只剩一条，标题就是「允许第三方应用访问数据目录」");
  ok(/dsh_docs_access_title/.test(zhS) && /dsh_docs_access_title/.test(enS) &&
    /dsh_docs_access_summary/.test(zhS) && /dsh_docs_access_summary/.test(enS) &&
    /dsh_docs_open_action/.test(zhS) && /dsh_docs_open_action/.test(enS) &&
    /dsh_docs_grant_action/.test(zhS) && /dsh_docs_grant_action/.test(enS),
    "新标题、说明与两个按钮的文案中英各一份");
  ok(!/dsh_docs_open_title/.test(zhS) && !/dsh_docs_open_summary/.test(zhS) &&
    !/dsh_docs_grant_summary/.test(zhS) && !/dsh_docs_open_title/.test(enS) &&
    !/dsh_docs_open_summary/.test(enS) && !/dsh_docs_grant_summary/.test(enS),
    "旧那两张卡的文案已删（留着就是死资源，也不该再有第二个说法）");
  ok(/android:name="\.util\.DshDocumentsProvider"/.test(manifest) && /\.documents"/.test(manifest),
    "文档提供器仍在（同 DSHA 的方案：一个窄 provider 给文件管理器浏览数据目录）");
}

console.log(bad === 0 ? `\n全部通过（${n} 项断言）` : `\n${bad}/${n} 项失败`);
process.exit(bad === 0 ? 0 : 1);
