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

// 应用用 prepareOut 预创建的那些
const listMatch = code.match(/listOf\(([\s\S]*?)\)\.forEach\s*\{\s*prepareOut\(it\)\s*\}/);
const prepared = new Set();
if (listMatch) for (const m of listMatch[1].matchAll(/[A-Za-z0-9_]+/g)) prepared.add(m[0]);

// 写入后把属主交回应用的（tar.gz 归档那条路：应用不写它，但要能读、能分享）
const chowned = new Set();
for (const m of code.matchAll(new RegExp("chown\\s+\\$uid:\\$uid\\s+" + abs, "g"))) chowned.add(m[1]);

console.log("── 采集文件的归属 ──");
ok(listMatch !== null, "存在 prepareOut 预创建清单");
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
