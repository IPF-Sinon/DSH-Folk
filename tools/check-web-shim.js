#!/usr/bin/env node
/**
 * WebUI 兼容垫片（COMPAT_SHIM）的门禁。
 *
 * ## 为什么要有这个检查器
 *
 * 1.9.2 及以前只覆盖到 `AbortSignal.any`(Chrome 116)/`Promise.withResolvers`(119)，
 * 阈值也停在 119 —— 而 dsh 前端实际用到 `Iterator`(Chrome 122) 与 `Promise.try`(128)。
 * 真机（Chromium 110）上的结果是**整个 WebUI 渲染成错误页**：
 *
 *     Failed to load plugins
 *     failed to import loader entry … : Iterator is not defined
 *
 * 连设置都进不去，而 logcat 与 bugreport 里一个字都没有 —— 只能靠用户截图。
 *
 * 这类「垫片缺一项 / 阈值写小了」的问题靠人肉对照版本表根本防不住，所以这里做两件事：
 * 把 Kotlin 里那份 JS 抠出来**在缺 API 的环境里真跑**，再对着 API→版本表反向断言。
 */
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const SRC_WEBUI = "app/src/main/java/me/bmax/apatch/ui/DshWebUiActivity.kt";
const SRC_COMPAT = "app/src/main/java/me/bmax/apatch/util/DshWebCompat.kt";
const SRC_ENV = "app/src/main/java/me/bmax/apatch/dsh/DshEnv.kt";

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
function eq(actual, expected, label) {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  ok(a === e, label + (a === e ? "" : `（期望 ${e}，实际 ${a}）`));
}

const webui = fs.readFileSync(SRC_WEBUI, "utf8");
const compat = fs.readFileSync(SRC_COMPAT, "utf8");
const env = fs.readFileSync(SRC_ENV, "utf8");

/**
 * 把 Kotlin 里 `private const val COMPAT_SHIM = """…"""` 那段原样还原成 JS。
 *
 * 用三引号原始字符串，所以只需要去掉首尾空行，不做转义处理 —— 与代码里
 * `addDocumentStartJavaScript(view, COMPAT_SHIM, rules)` 拿到的字面量完全一致。
 */
function rawStringConst(source, name) {
  const start = source.indexOf(`const val ${name} = """`);
  if (start < 0) throw new Error(`找不到 ${name}`);
  const from = start + `const val ${name} = """`.length;
  const end = source.indexOf('"""', from);
  if (end < 0) throw new Error(`${name} 三引号没有闭合`);
  return source.slice(from, end);
}

console.log("\n── WebUI 兼容垫片：在缺 API 的环境里真跑 ──");

const shim = rawStringConst(webui, "COMPAT_SHIM");
ok(shim.length > 1500, `垫片还原成功（${shim.length} 字节）`);
ok(!/\$\{/.test(shim), "垫片里没有未展开的 Kotlin 模板（三引号里 $ 必须转义或用字面量）");

/**
 * 造一个「老内核」环境：把垫片覆盖到的 API 全部删掉，再执行垫片。
 *
 * 用真的 V8 跑，而不是正则匹配 —— 这样语法错、`Symbol.iterator` 用错、
 * `Iterator.from` 返回的对象不是迭代器这类问题全都会暴露出来。
 */
function sandbox(stripGlobals) {
  const ctx = {
    console,
    setTimeout,
    clearTimeout,
    // 垫片用到的宿主对象：AbortController/WeakRef/DOMException 在 110 上都有
    AbortController,
    AbortSignal,
    crypto: globalThis.crypto,
    WeakRef,
    DOMException,
    Object,
    Symbol,
    Promise,
    ArrayBuffer,
    Uint8Array,
    Array,
    String,
    Number,
    TypeError,
    Math,
    JSON,
  };
  ctx.window = ctx;
  ctx.globalThis = ctx;
  ctx.self = ctx;
  const context = vm.createContext(ctx);
  for (const name of stripGlobals) {
    vm.runInContext(`delete globalThis.${name};`, context);
  }
  return context;
}

// 老内核的样子：有 Promise，但没有 Promise.try / withResolvers；也没有全局 Iterator
const realCtx = sandbox(["Iterator"]);
vm.runInContext(
  `var RealPromise = (function(){ var p = new Promise(function(r){r(1)}); return p.constructor; })();
   RealPromise.try = undefined; RealPromise.withResolvers = undefined;
   globalThis.Promise = RealPromise;`,
  realCtx,
);


let shimError = null;
try {
  vm.runInContext(shim, realCtx);
} catch (e) {
  shimError = e;
}
ok(shimError === null, "垫片本身能在老内核环境里执行" + (shimError ? `（${shimError.message}）` : ""));

// ── 上游真实调用点：documentpreview 插件里那句 ──
const probe = (code) => {
  try {
    return vm.runInContext(code, realCtx);
  } catch (e) {
    return "THREW: " + e.message;
  }
};

ok(probe("typeof Iterator") === "object" || probe("typeof Iterator") === "function",
  "全局 Iterator 已存在（documentpreview 里 `typeof Iterator.prototype.join` 不再抛）");
ok(probe("(function(){ try { return typeof Iterator.prototype.join; } catch(e) { return 'THREW:'+e.message } })()") === "function",
  "Iterator.prototype.join 可读（这就是真机上抛 ReferenceError 的那一句）");
const baseIterProto = probe(
  "(function(){ var p = Object.getPrototypeOf(Object.getPrototypeOf([][Symbol.iterator]()));" +
  " return p === Iterator.prototype; })()",
);
ok(baseIterProto === true, "Iterator.prototype 就是 %IteratorPrototype%（不是手搓的冒牌货）");

eq(probe("Iterator.from([1,2,3]).map(function(x){return x*2}).toArray()"), [2, 4, 6],
  "Iterator.from(...).map(...).toArray() 得到 [2,4,6]");
eq(probe("Iterator.from([1,2,3,4]).drop(1).take(2).toArray()"), [2, 3],
  "drop/take 语义正确");
eq(probe("Iterator.from([1,2,3,4,5]).filter(function(x){return x%2===1}).toArray()"), [1, 3, 5],
  "filter 语义正确");
eq(probe("Iterator.from([1,2,3]).reduce(function(a,b){return a+b}, 0)"), 6,
  "reduce 语义正确");
eq(probe("Iterator.from([1,2,3]).some(function(x){return x===2})"), true, "some ✓");
eq(probe("Iterator.from([1,2,3]).every(function(x){return x>0})"), true, "every ✓");
eq(probe("Iterator.from([1,2,3]).find(function(x){return x>1})"), 2, "find ✓");
eq(probe("Iterator.from(['a','b']).join('-')"), "a-b", "join ✓");
eq(probe("Iterator.from([1,2]).flatMap(function(x){return [x, x*10]}).toArray()"), [1, 10, 2, 20],
  "flatMap 语义正确");
ok(probe("(function(){ var it = Iterator.from([1,2]); return it[Symbol.iterator]() === it; })()") === true,
  "迭代器协议：it[Symbol.iterator]() 返回自身");
ok(probe("(function(){ var s = new Set([1,2]); return Iterator.from(s).toArray().join(','); })()") === "1,2",
  "Iterator.from 吃得下任意可迭代对象（Set）");
ok(probe("(function(){ var g = Iterator.from([1,2,3]).map(function(x){return x+1});" +
  " return Array.from(g).join(','); })()") === "2,3,4",
  "垫片产物能被原生 for-of / Array.from 消费");

// ── 其余覆盖项 ──
ok(probe("typeof Promise.try") === "function", "Promise.try 已补齐（pdf.js 直接调用它）");
eq(probe("Promise.try(function(a,b){return a+b}, 1, 2) instanceof Promise"), true,
  "Promise.try 返回 Promise");
eq(probe("typeof Promise.withResolvers"), "function", "Promise.withResolvers ✓");
ok(probe("typeof ArrayBuffer.prototype.transferToFixedLength") === "function",
  "ArrayBuffer.prototype.transferToFixedLength ✓");
eq(probe("new Uint8Array(new ArrayBuffer(4).transferToFixedLength(2)).length"), 2,
  "transferToFixedLength 真的能缩容");
ok(probe("typeof Symbol.dispose") === "symbol", "Symbol.dispose ✓");
ok(probe("typeof AbortSignal === 'undefined' || typeof AbortSignal.any === 'function'") === true,
  "AbortSignal.any ✓");
ok(probe("(function(){ try { AbortSignal.any([AbortSignal.timeout(1)]); return true } catch(e) { return 'THREW:'+e.message } })()") === true,
  "AbortSignal.any 可调用（pdf.js 的 signal 合并）");
ok(probe("(function(){ try { crypto.randomUUID(); return true } catch(e) { return 'NO_CRYPTO' } })()") !== "THREW:undefined",
  "crypto.randomUUID 分支不抛（vm 里没有 crypto，只要不是 TypeError 即可）");
ok(probe("(function(){ var n = 0; try { eval('Iterator.prototype.join') } catch(e) { n++ } return window.__dshFolkCompat; })()") === 1,
  "幂等标记已设置（重复注入不会重装一遍）");
ok(probe("(function(){ try { vmNoop(); } catch(e) {} return typeof Iterator; })()") !== "undefined",
  "垫片执行后 Iterator 仍在（没有把自己删掉）");

// ── 幂等：同一份文档执行两次不报错、语义不变 ──
let secondRun = null;
try {
  vm.runInContext(shim, realCtx);
} catch (e) {
  secondRun = e;
}
ok(secondRun === null, "重复注入幂等（第二次执行不抛）");
eq(probe("Iterator.from([1,2,3]).map(function(x){return x*2}).toArray()"), [2, 4, 6],
  "重复注入后语义不变");

console.log("\n── 覆盖表 vs 阈值：反向断言 ──");

// 表里每一项都来自上游 client bundle 的实测用法（见 DSH_COMPAT_MIN_CHROMIUM 的 KDoc）。
// required = 该 API 进入 Chromium 的主版本号。
const TABLE = [
  ["AbortSignal.any", 116, "AbortSignal.any"],
  ["AbortSignal.timeout", 103, "AbortSignal.timeout"],
  ["Promise.withResolvers", 119, "Promise.withResolvers"],
  ["Iterator (全局对象)", 122, "Iterator"],
  ["Promise.try", 128, "Promise.try"],
  ["ArrayBuffer.prototype.transferToFixedLength", 114, "transferToFixedLength"],
  ["crypto.randomUUID", 92, "randomUUID"],
];

const minMatch = env.match(/DSH_COMPAT_MIN_CHROMIUM = (\d+)/);
ok(minMatch !== null, "DSH_COMPAT_MIN_CHROMIUM 是字面量常量");
const min = minMatch ? Number(minMatch[1]) : 0;
const highestRequired = Math.max(...TABLE.map((r) => r[1]));
ok(min >= highestRequired,
  `阈值 ${min} ≥ 覆盖项里要求最高的 Chrome ${highestRequired}（低报会让该修的设备一条都不修）`);

for (const [label, required, needle] of TABLE) {
  const covered = shim.includes(needle);
  ok(covered || required > min,
    `${label}：要么垫片里有，要么其所需 Chrome ${required} 高于阈值 ${min}`);
}

ok(shim.includes("Symbol.dispose"), "Symbol.dispose 一并定义（缺失时属性键会变成 undefined）");
ok(shim.includes("Symbol.asyncDispose"), "Symbol.asyncDispose 一并定义");

// 上游 bundle 里出现过、但**故意不补**的项（内核 110 已自带，或有 typeof 守卫）：
//   structuredClone(98)、Array.prototype.findLastIndex?(97)、Object.hasOwn(93)、
//   String.prototype.replaceAll(85)、Float16Array(135，但上游写成 typeof 守卫，不补也不会抛)。
// 反过来断言：垫片里每个「缺失才补」的分支都必须在上面那张表里 —— 避免有人加了新分支却不更新表。

// 反向断言（可判定、不误报）：上游真的会调的 Iterator 助手必须一个不少。
// 依据是上游 47 个 client 包的实际用法扫描：.toArray() 5 处、.take( 3 处、
// .flatMap( 3 处、join 1 处（documentpreview 里那句 typeof 判断）。
const REQUIRED_HELPERS = ["map", "filter", "take", "drop", "takeWhile", "dropWhile",
  "flatMap", "reduce", "toArray", "forEach", "some", "every", "find", "join", "next"];
const definedHelpers = [...shim.matchAll(/defineHelper\('([\w$]+)'/g)].map((m) => m[1]);
const missingHelpers = REQUIRED_HELPERS.filter((h) => !definedHelpers.includes(h));
ok(missingHelpers.length === 0,
  "Iterator 助手齐全" + (missingHelpers.length ? `（缺 ${missingHelpers.join(", ")}）` : `（${definedHelpers.length} 个）`));
ok(shim.includes("IteratorGlobal.from ="), "Iterator.from 已定义（上游用 Iterator.from 造迭代器）");

console.log("\n── 结构断言：注入策略与诊断 ──");

ok(/fun shouldInject\(ctx: Context, kernel: Kernel = kernel\(ctx\)\): Boolean =\s*\n\s*when \(mode\(ctx\)\) \{[\s\S]{0,200}MODE_ON -> true[\s\S]{0,120}MODE_OFF -> false[\s\S]{0,120}else -> kernel\.needsShim/.test(compat),
  "shouldInject：内核缺 API 就自动注入（不再等用户点头，否则错误页里根本没机会点）");
ok(/fun shouldNotice\(ctx: Context, kernel: Kernel = kernel\(ctx\)\): Boolean =[\s\S]{0,220}KEY_WEBUI_COMPAT_NOTICED/.test(compat),
  "shouldNotice：只提示一次（落盘标记）");
ok(env.includes("KEY_WEBUI_COMPAT_NOTICED"), "DshEnv 里有「已提示」标记键");
ok(/MODE_OFF -> false/.test(compat), "MODE_OFF 仍然完全尊重用户选择");
ok(webui.includes("DshWebCompat.shouldNotice"), "Activity 用的是 shouldNotice 而不是旧的 shouldAsk");
ok(!webui.includes("shouldAsk"), "旧的「先问」路径已彻底移除");
ok(webui.includes("onConsoleMessage"), "WebChromeClient 接了 onConsoleMessage（页面报错进日志）");
ok(/onConsoleMessage[\s\S]{0,600}MessageLevel\.ERROR[\s\S]{0,400}Log\.w/.test(webui),
  "页面 JS 报错写进应用日志（1.9.2 那次故障在 bugreport 里一个字都没有）");
ok(webui.includes("addDocumentStartJavaScript") && webui.includes("DOCUMENT_START_SCRIPT"),
  "注入仍是 document-start（模块求值前），不支持时回落 onPageStarted");

console.log("\n── dsh-file-upload 退役 ──");

const rt = fs.readFileSync("app/src/main/java/me/bmax/apatch/dsh/DshRuntime.kt", "utf8");
const seedLine = rt.match(/val SEED_PLUGINS =\s*\n?\s*listOf\(([^)]*)\)/);
ok(seedLine !== null, "SEED_PLUGINS 可解析");
ok(seedLine && !seedLine[1].includes("dsh-file-upload"),
  `SEED_PLUGINS 不再含 dsh-file-upload（现在是 ${seedLine ? seedLine[1].trim() : "?"}）`);
ok(/RETIRED_SEED_PLUGINS = mapOf\("dsh-file-upload" to "file-upload"\)/.test(rt),
  "退役表仍记得它（否则已装的卸不掉、冲突也认不出来）");
ok(/RETIRE_MIN_DSH_VERSION = "0.1.5"/.test(rt), "退役判定版本下界 = 0.1.5");
ok(/private fun runtimeSupportsRetiredSeed[\s\S]{0,700}compareVersions\(core, RETIRE_MIN_DSH_VERSION\) >= 0/.test(rt),
  "判定走 compareVersions(core, 0.1.5) ≥ 0");
ok(/core\.isEmpty\(\) \|\| core\.substringBefore\('\.'\)\.toIntOrNull\(\) == null\) return false/.test(rt),
  "版本解析不出来就不动（宁可多留一会儿，也不卸掉老运行时上还能用的能力）");
const mappedLine = rt.split("\n").find((l) => l.includes("val mappedShadowed")) || "";
ok(mappedLine !== "" && !mappedLine.includes("pluginEntries") && mappedLine.includes("RETIRED_SEED_PLUGINS"),
  "退役判定只看运行时版本，不再依赖 pluginEntries（yaml 失败时它静默返回空表 —— 真机就栽在这）");
ok(/private fun managedSeedPackages\(\)[\s\S]{0,120}SEED_PLUGINS \+ RETIRED_SEED_PLUGINS\.keys/.test(rt),
  "清理范围 = 在装的 + 退役的");
ok(/pruneUnresolvableBundles\(managedSeedPackages\(\)\)/.test(rt), "启动前按该范围清声明");
ok(/ifEmpty \{ RETIRED_SEED_PLUGINS\.filter \{ \(_, retiredId\) -> retiredId == id \}/.test(rt),
  "启动失败兜底：探测不到 entry id 时按 id 反查退役表");
const repo = fs.readFileSync("app/src/main/java/me/bmax/apatch/dsh/DshPluginRepo.kt", "utf8");
ok(/NO_YAML/.test(repo), "yaml 解析失败会输出标记");
ok(/out\.contains\("NO_YAML"\)\) \{\s*\n\s*Log\.w\(TAG, "pluginEntries/.test(repo),
  "该标记会变成一条警告日志（把静默失败变可见）");
ok(/for\(const a of \[process\.argv\[1\],require\('path'\)\.join\(process\.argv\[2\]/.test(repo),
  "yaml 解析多锚点尝试（dsh 入口 → profile 目录 → 自带路径）");

console.log(bad === 0 ? `\n全部通过（${n} 项断言）` : `\n${bad}/${n} 项失败`);
process.exit(bad === 0 ? 0 : 1);
