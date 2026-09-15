#!/usr/bin/env node
// 采集日志的「文件归属」检查。
//
// 起因是一次真机崩溃（beta.44 / OPPO SDK 35）：
//
//   java.io.FileNotFoundException: .../cache/bugreport/dmesg.txt: open failed: EACCES
//
// `dmesg > 文件` 由 root shell 执行，文件属主是 root；随后应用自己 writeText 去裁剪
// 那份文件就是 EACCES。崩溃还发生在 `rm -rf bugreport` 之前，root 文件留在原地 ——
// 用户再点一次还是崩，只能清应用数据才能恢复。
//
// 这类 bug 有个特点：**新增采集项时最容易再犯**（照着上一行抄一句 `xxx > file` 就行），
// 而且只在「有 root + 选了时间窗口」的真机上才暴露，本地与 CI 都跑不出来。
// 所以把它变成静态不变量：凡是 root shell 写入的文件，应用必须先用 prepareOut 建好，
// 或者写入后把属主交回应用（chown）。
//
// 用法：node tools/check-bugreport-files.js
"use strict";

const fs = require("fs");
const PATH = "app/src/main/java/me/bmax/apatch/util/LogEvent.kt";

let failed = 0;
function ok(cond, label) {
  if (cond) {
    console.log("  ✓ " + label);
  } else {
    console.log("  ✗ " + label);
    failed++;
  }
}

if (!fs.existsSync(PATH)) {
  console.log("  ✗ 找不到 " + PATH);
  process.exit(1);
}
const raw = fs.readFileSync(PATH, "utf8");
// 注释会让「出现过某个字符串」这类判据失效
const code = raw.replace(/\/\*[\s\S]*?\*\//g, "").replace(/(^|[^:])\/\/[^\n]*/g, "$1");

const abs = "\\$\\{([A-Za-z0-9_]+)\\.absolutePath\\}";

/** root shell 真正**写入**的文件（`>` 重定向、tar 输出、cp 目标、touch）。 */
function writeTargets(text) {
  const out = new Set();
  const patterns = [
    new RegExp(">\\s*" + abs, "g"), // dmesg > f、cat /proc/x > f
    new RegExp("-c?zf\\s+" + abs, "g"), // tar -czf f
    new RegExp("cp\\s+[^\"\\n]*?" + abs, "g"), // cp 源 目标
    new RegExp("touch\\s+" + abs, "g"),
  ];
  for (const re of patterns) {
    for (const m of text.matchAll(re)) out.add(m[1]);
  }
  // 同一条命令里立刻 `rm` 掉的临时文件不算（例如 tar 的 -T 清单）：
  // 应用从不需要写它们，预创建反而是多余的
  const temps = new Set();
  for (const m of text.matchAll(new RegExp("rm\\s+-[rf]+\\s+" + abs, "g"))) temps.add(m[1]);
  for (const t of temps) out.delete(t);
  return out;
}

const written = writeTargets(code);

// tar 助手把目标当参数收：`tarDir(out, ...)` 里的 out 由调用点决定，要展开成实参再判断
const helperParams = new Set();
const helperDecl = code.match(/fun\s+tarDir\(([^)]*)\)/);
if (helperDecl) {
  for (const m of helperDecl[1].matchAll(/\(?\s*([A-Za-z0-9_]+)\s*:\s*File/g)) helperParams.add(m[1]);
}
for (const p of helperParams) {
  if (!written.has(p)) continue;
  written.delete(p);
  for (const m of code.matchAll(/(?<!fun )tarDir\(\s*([A-Za-z0-9_]+)/g)) written.add(m[1]);
}

// 应用用 prepareOut 预创建的那些。
// 抽取必须锚定 forEach 再回溯：文件里还有别的 listOf（CRASH_DUMP_HINTS），
// 用「第一个 listOf 到 forEach」这种非贪婪匹配会把两段一起吞进来。
const forEachAt = code.indexOf(").forEach { prepareOut(it) }");
const listAt = forEachAt < 0 ? -1 : code.lastIndexOf("listOf(", forEachAt);
const listBody = forEachAt > 0 && listAt > 0 ? code.slice(listAt + "listOf(".length, forEachAt) : "";
const prepared = new Set();
for (const m of listBody.matchAll(/[A-Za-z0-9_]+/g)) prepared.add(m[0]);
const listMatch = listBody ? [listBody] : null;

// 写入后把属主交回应用的（tar.gz 归档那条路：应用不写它，但要能读、能分享）
const chowned = new Set();
for (const m of code.matchAll(new RegExp("chown\\s+\\$uid:\\$uid\\s+" + abs, "g"))) chowned.add(m[1]);

console.log("── 采集文件的归属 ──");
ok(listMatch !== null, "存在 prepareOut 预创建清单");
// 自校验：清单里每一项都要对应源码里的 `val xxx = File(` —— 抽错了会立刻暴露，
// 而不是让「清单没有多余项」这类断言拿着垃圾 token 去报警（这正是上一版的毛病）
const notAFile = [...prepared].filter((n) => !new RegExp("val " + n + " = File\\(").test(code));
ok(prepared.size >= 10 && notAFile.length === 0,
  "预创建清单解析正确（" + prepared.size + " 项）" + (notAFile.length ? "（不是 File 变量：" + notAFile.join(", ") + "）" : ""));
ok(written.size >= 10, "识别到 root 写入的文件（" + written.size + " 个）");

const missing = [...written].filter((n) => !prepared.has(n) && !chowned.has(n)).sort();
ok(
  missing.length === 0,
  "root 写的每个文件都先由应用建好、或写了之后 chown 回应用" +
    (missing.length ? "（缺：" + missing.join(", ") + "）" : "")
);
// 反向：清单里不该有已经不存在的东西（否则清单会越长越像摆设）
const stale = [...prepared].filter((n) => !written.has(n) && !["listOf", "it"].includes(n)).sort();
ok(stale.length === 0, "预创建清单没有多余项" + (stale.length ? "（多余：" + stale.join(", ") + "）" : ""));

console.log("── 应用侧改写必须容错 ──");
// 应用自己重写 root 写过的文件时，失败不能让整份报告崩掉（就是这次的崩溃）
ok(/runCatching \{ dmesgFile\.writeText/.test(code), "dmesg 裁剪包在 runCatching 里");
ok(/runCatching \{ logcatFile\.writeText/.test(code), "logcat 回退过滤包在 runCatching 里");
ok(
  /notes \+= "dmesg 裁剪失败/.test(code) && /notes \+= "logcat 回退过滤失败/.test(code),
  "两处失败都记进 notes"
);
ok(
  /pw\.println\("Notes: " \+ notes\.joinToString/.test(code),
  "notes 写进 basic.txt（否则「窗口没生效」查不出原因）"
);

console.log("── 历史遗留的 root 文件要能清掉 ──");
ok(
  /if \(file\.exists\(\) && !file\.canWrite\(\)\)/.test(code) && /file\.delete\(\)/.test(code),
  "prepareOut 会删掉不可写的旧文件（删除只需要目录写权限）"
);
ok(/file\.createNewFile\(\)/.test(code), "prepareOut 会建出应用属主的空文件");

console.log("── 归档内文本的脱敏 ──");
// 从 Kotlin 源码里抽出正则，直接用真机样本跑 —— 比「文件里出现过某个字符串」强得多：
// 判据写错、少一个转义、把 [] 忘了，这里都会失败。
const kotlinRegexes = [...code.matchAll(/Regex\(\s*"""([\s\S]*?)"""\s*\)/g)].map((m) => m[1]);
function toJs(raw) {
  const ignoreCase = raw.startsWith("(?i)");
  const body = ignoreCase ? raw.slice(4) : raw;
  return new RegExp(body, ignoreCase ? "gi" : "g");
}
const patterns = kotlinRegexes.map(toJs);
ok(patterns.length >= 3, "抽出 " + patterns.length + " 条脱敏正则");
const redact = (t) => patterns.reduce((acc, re) => acc.replace(re, (m, g1) => g1 + "<redacted>"), t);

// 真机 beta.46 报告里的两行（已脱敏成占位符，仍能验证判据是否命中）
const tokenLine = "dsh web: http://127.0.0.1:3080/?token=05YIXAJTevgX9AjR7pdN_sYVilHC0BvyIP22zKhS8rc";
const propsLine = "[persist.netd.stable_secret]: [6f15:16b1:9a92:5c12:3a61:7c0a:949:d931]";
ok(!/token=[A-Za-z0-9_-]{6,}/.test(redact(tokenLine)), "URL 里的 WebUI token 会被替换");
ok(!/6f15:16b1/.test(redact(propsLine)), "getprop 的 [key]: [value] 格式也会被替换（方括号不能挡住判据）");
// 不能误伤诊断字段
const mustKeep = [
  "Kernel: 6.1.90-perf+",
  "[ro.build.version.sdk]: [35]",
  "dsh web: http://127.0.0.1:3080/",
  "[persist.sys.locale]: [zh-Hans-CN]",
  "126|com.android.webview|10027",
];
const hurt = mustKeep.filter((l) => redact(l) !== l);
ok(hurt.length === 0, "正常诊断字段不被误改" + (hurt.length ? "（" + hurt.join(" | ") + "）" : ""));

// 脱敏必须用在真正会被打包的文件上，而且要在打包之前
ok(/redactInPlace\(dshLogFile, notes\)/.test(code) && /redactInPlace\(propFile, notes\)/.test(code) &&
  /redactInPlace\(cmdlineFile, notes\)/.test(code),
  "dsh.log / props / cmdline 都过脱敏");
const tarAt = code.indexOf("tar czf ${targetFile.absolutePath}");
const lastRedact = code.lastIndexOf("redactInPlace(");
ok(tarAt > 0 && lastRedact > 0 && lastRedact < tarAt, "脱敏在打包之前（顺序反了等于没脱）");

console.log("── kallsyms 的收取判据 ──");
const hintsMatch = code.match(/CRASH_DUMP_HINTS = listOf\(([\s\S]*?)\)/);
const hints = hintsMatch ? [...hintsMatch[1].matchAll(/"([a-z_]+)"/g)].map((m) => m[1]) : [];
function isCrashDump(path) {
  const p2 = String(path).trim().toLowerCase();
  if (!p2) return false;
  if (p2.startsWith("/data/tombstones") || p2.startsWith("/sys/fs/pstore")) return true;
  const name = p2.slice(p2.lastIndexOf("/") + 1);
  return hints.some((h) => name.includes(h));
}
ok(hints.length >= 5, "抽到 " + hints.length + " 个崩溃转储关键词");
ok(isCrashDump("/data/tombstones/tombstone_07") && isCrashDump("/sys/fs/pstore/dmesg-ramoops-0"),
  "tombstones / pstore 里的一切都算崩溃转储");
ok(isCrashDump("/data/system/dropbox/SYSTEM_TOMBSTONE@1.txt"), "dropbox 的 tombstone 条目算");
ok(!isCrashDump("/data/system/dropbox/SYSTEM_BOOT@1.txt"),
  "SYSTEM_BOOT 不算（每次开机都写，它会让 620 KB 的 kallsyms 每次都进归档）");
ok(!isCrashDump("/data/system/dropbox/SYSTEM_RESTART@1.txt"), "SYSTEM_RESTART 不算");
// 判据必须真的挂在 isCrashDump 上，而不是回到「目录里有文件就算」
ok(/wantKallsyms = window == LogWindow\.All \|\|/.test(code) && /dumps\.lineSequence\(\)\.any \{ isCrashDump\(it\) \}/.test(code),
  "kallsyms 判据走 isCrashDump");
ok(!/system\/dropbox \/sys\/fs\/pstore[\s\S]{0,80}head -1/.test(code),
  "没有残留「dropbox 里有任何文件就收 kallsyms」的旧判据");

console.log("── 最终归档 ──");
ok(
  /chown \$uid:\$uid \$\{targetFile\.absolutePath\}/.test(code) &&
    /chmod 0644 \$\{targetFile\.absolutePath\}/.test(code),
  "归档 tar.gz 交回应用属主（否则 FileProvider 分享不出去）"
);

console.log("");
if (failed === 0) {
  console.log("全部通过（" + written.size + " 个文件 + 8 项断言）");
  process.exit(0);
}
console.log(failed + " 项失败");
process.exit(1);
