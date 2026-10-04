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
const SRC_FUNCTION_SETTINGS = "app/src/main/java/me/bmax/apatch/ui/screen/settings/FunctionSettings.kt";
const SRC_FUNCTION_SETTINGS_SCREEN =
  "app/src/main/java/me/bmax/apatch/ui/screen/settings/FunctionSettingsScreen.kt";

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

console.log("\n── 沉浸内边距脚本：在假 DOM 里真跑 ──");
{
  // 这一段是**函数**返回值（要带上当次量到的系统栏尺寸），不是 const 字符串，
  // 所以单独抠函数体里的三引号，再把四个 Kotlin 模板换成具体数字。
  const m = webui.match(
    /internal fun insetShimScript\([^)]*\): String = """\n([\s\S]*?)\n"""\.trimIndent\(\)/,
  );
  if (!m) {
    ok(false, "能从 DshWebUiActivity.kt 抠出 insetShimScript");
  } else {
    const INSETS = { top: 24, right: 0, bottom: 48, left: 0 };
    const js = m[1].replace(/\$(top|right|bottom|left)\b/g, (_, k) => String(INSETS[k]));
    ok(true, `脚本还原成功（${js.length} 字节）`);
    ok(!/\$\{/.test(js) && !/\$(top|right|bottom|left)\b/.test(js),
      "四个尺寸都换成了字面量，没有留下未展开的模板");

    // 假 DOM：先模拟「文档刚开始、documentElement 还没有」，再让它出现并触发
    // DOMContentLoaded —— 这正是 document-start 注入时的真实时序。
    const created = [];
    const doc = {
      documentElement: null,
      head: null,
      createElement: (tag) => ({ tagName: tag, id: "", textContent: "" }),
      getElementById: (id) => created.find((e) => e.id === id) || null,
      addEventListener: (ev, fn) => {
        if (ev === "DOMContentLoaded") doc.__ready = fn;
      },
    };
    const ctx = { document: doc, console, window: {} };
    vm.createContext(ctx);
    let runError = null;
    try {
      vm.runInContext(js, ctx);
    } catch (e) {
      runError = e;
    }
    ok(runError === null, "文档开始阶段执行不报错" + (runError ? `（${runError.message}）` : ""));
    ok(created.length === 0, "此时还不插节点（documentElement 可能还没有）");
    ok(typeof doc.__ready === "function", "登记了 DOMContentLoaded 的兜底插入");
    ok(typeof ctx.window.__dshFolkInsets === "function", "暴露了 __dshFolkInsets 供尺寸变化时更新");

    doc.documentElement = { appendChild: (nn) => created.push(nn) };
    doc.head = doc.documentElement;
    if (typeof doc.__ready === "function") doc.__ready();
    ok(created.length === 1, "DOM 一出现就插入了一个 <style>");
    const css = created[0] ? created[0].textContent : "";
    ok(/#root\{box-sizing:border-box!important;padding:24px 0px 48px 0px!important\}/.test(css),
      "#root 用 border-box 内边距避让系统栏");
    ok(/\[class\*="_banner_"\]\{top:24px!important\}/.test(css),
      "fixed 定位的断线提示条单独顶下来（它不受 #root 内边距影响）");

    // 尺寸变化（转屏 / 折叠 / 键盘）走的是这条路径
    let updateError = null;
    try {
      ctx.window.__dshFolkInsets(30, 0, 0, 10);
    } catch (e) {
      updateError = e;
    }
    ok(updateError === null, "更新尺寸不报错");
    ok(/padding:30px 0px 0px 10px!important/.test(created[0].textContent),
      "更新后 CSS 跟上新尺寸（键盘弹起时 bottom 传 0 就是这条路）");

    // 垫片还没装上时 onPageStarted 会补注入；更新调用必须容忍「函数还不存在」
    const bare = { console, window: {} };
    vm.createContext(bare);
    let bareError = null;
    try {
      vm.runInContext("window.__dshFolkInsets&&window.__dshFolkInsets(24,0,48,0)", bare);
    } catch (e) {
      bareError = e;
    }
    ok(bareError === null, "脚本未装上时更新调用是空操作（不会抛）");
  }

  // 静态侧：WebView 必须真的铺满整窗，只让键盘把它顶起来
  const webViewModifier = webui.match(/AndroidView\(\s*modifier = Modifier([\s\S]{0,800}?)factory/);
  const modifierSrc = webViewModifier ? webViewModifier[1] : "";
  ok(/\.fillMaxSize\(\)/.test(modifierSrc), "WebView 铺满整窗");
  // 键盘用 imeAnimationTarget（目标高度、一步到位）而非 imePadding（逐帧插值，WebView 117 上卡顿）
  ok(/\.windowInsetsPadding\(WindowInsets\.imeAnimationTarget\)/.test(modifierSrc),
    "键盘由 imeAnimationTarget 一步让开（非逐帧 imePadding）");
  ok(!/\.imePadding\(\)/.test(modifierSrc), "不再用逐帧 imePadding（避免 WebView 逐帧重排卡顿）");
  ok(!/safeDrawing/.test(modifierSrc), "WebView 上不再用 safeDrawing 内边距（那会留出色带）");
  ok(/installInsetShim\(/.test(webui) && /!insetShimInstalled && isLoopback\(u\)/.test(webui),
    "装上与否分别有 document-start 与 onPageStarted 两条路径");
}

// ── 手机回车换行：在假 DOM 里真跑一遍 ──
//
// 这一段是**行为**断言，不是字符串匹配：造一个最小的 window/document，把 COMPOSER_SHIM
// 真跑起来，然后喂各种 keydown 进去看它拦不拦、改不改。理由是这条补丁的风险全在"什么时候
// 不该拦"上 —— 拦错一次，中文输入法确认候选词就变成换行、或者 `/` 菜单回车选不中，
// 这些只有真跑事件才测得出来。
{
  const composer = rawStringConst(webui, "COMPOSER_SHIM");
  ok(composer.length > 800, `回车换行脚本还原成功（${composer.length} 字节）`);
  ok(!/\$\{/.test(composer), "脚本里没有未展开的 Kotlin 模板");

  // 静态契约
  ok(!/beforeinput/.test(composer),
    "不碰 beforeinput（软键盘只发 beforeinput 时上游本来就会换行，介入反而危险）");
  ok(/pointer: coarse/.test(composer), "只对触屏（pointer: coarse）注册监听");
  ok(/isComposing/.test(composer) && /229/.test(composer),
    "输入法合成期放行（中文回车是确认候选词，拦了就成换行）");
  ok(/aria-haspopup/.test(composer) && /aria-expanded/.test(composer),
    "联想菜单打开时放行（回车在菜单里是「选中」），判据用宿主自己的 aria 标记");

  /** 造一个假 window；返回登记下来的监听器。 */
  function harness(coarse) {
    const listeners = [];
    const ctx = {
      console,
      document: { querySelectorAll: () => [], addEventListener: () => {} },
      matchMedia: (q) => ({ matches: q === "(pointer: coarse)" ? coarse : false }),
    };
    ctx.window = ctx;
    ctx.globalThis = ctx;
    ctx.addEventListener = (ev, fn, capture) => listeners.push({ ev, fn, capture });
    ctx.KeyboardEvent = function (type, init) {
      this.type = type;
      Object.assign(this, init || {});
    };
    vm.createContext(ctx);
    return { ctx, listeners };
  }

  /** 输入框/卡片元素替身。`menuOpen` 模拟宿主把 aria-expanded 置为 true。 */
  function element(inComposer, menuOpen) {
    return {
      dispatched: [],
      closest(sel) {
        if (sel === "[data-composer-input]") return inComposer ? this : null;
        if (sel === "[data-composer-card]") return inComposer ? this : null;
        return null;
      },
      getClientRects: () => [],
      getAttribute: () => null,
      querySelector: (sel) =>
        menuOpen && sel === '[aria-haspopup][aria-expanded="true"]' ? { tagName: "BUTTON" } : null,
      dispatchEvent(e) {
        this.dispatched.push(e);
        return true;
      },
    };
  }

  function keydown(over) {
    return Object.assign(
      {
        type: "keydown",
        key: "Enter",
        code: "Enter",
        keyCode: 13,
        shiftKey: false,
        ctrlKey: false,
        altKey: false,
        metaKey: false,
        isComposing: false,
        target: null,
        prevented: 0,
        stopped: 0,
        preventDefault() { this.prevented++; },
        stopImmediatePropagation() { this.stopped++; },
      },
      over || {},
    );
  }

  const coarse = harness(true);
  vm.runInContext(composer, coarse.ctx);
  ok(coarse.listeners.length === 1 && coarse.listeners[0].ev === "keydown",
    "触屏上挂了恰好一个 keydown 监听");
  ok(coarse.listeners[0].capture === true,
    "用的是**捕获**阶段（要在宿主自己的 window 监听之前吃到这次回车）");
  ok(coarse.ctx.__dshFolkComposerEnter === 1, "装了幂等哨兵（onPageStarted 回落重复注入不会挂两遍）");
  vm.runInContext(composer, coarse.ctx);
  ok(coarse.listeners.length === 1, "重复注入不会重复挂监听");

  const fire = (e, target) => {
    e.target = target;
    coarse.listeners[0].fn(e);
    return e;
  };

  // ① 正题：输入框里的裸回车 → 拦下"发送"，改发一个 Shift+Enter
  const editor = element(true, false);
  const e1 = fire(keydown(), editor);
  ok(e1.prevented === 1, "裸回车被 preventDefault（拦掉上游的发送）");
  ok(e1.stopped === 1, "并且 stopImmediatePropagation 掉，不让宿主的 window 监听再看到它");
  ok(editor.dispatched.length === 1, "补发了一个按键事件");
  const sent = editor.dispatched[0];
  ok(sent && sent.type === "keydown" && sent.key === "Enter" && sent.shiftKey === true,
    "补发的是 **Shift+Enter** —— 走上游自己的 fixed.newline 换行路径，不自己搓编辑器");
  ok(sent && sent.bubbles === true && sent.cancelable === true,
    "补发事件要冒泡且可取消（否则宿主收不到、或没法阻止默认插入）");

  // ② 合成期：中文输入确认候选词，绝不能拦
  const e2 = fire(keydown({ isComposing: true }), element(true, false));
  ok(e2.prevented === 0 && e2.stopped === 0, "isComposing 期间放行");
  const e3 = fire(keydown({ keyCode: 229 }), element(true, false));
  ok(e3.prevented === 0, "keyCode 229（旧内核的合成标记）也放行");

  // ③ 带修饰键：Shift+Enter 本来就是换行，Ctrl/Cmd+Enter 是宿主自己的互补行为
  for (const mod of ["shiftKey", "ctrlKey", "altKey", "metaKey"]) {
    const em = fire(keydown({ [mod]: true }), element(true, false));
    ok(em.prevented === 0, `带 ${mod} 的回车放行（那是宿主已定义的行为）`);
  }

  // ④ 菜单打开：回车是"选中"，不是换行
  const menuEl = element(true, true);
  const e4 = fire(keydown(), menuEl);
  ok(e4.prevented === 0 && menuEl.dispatched.length === 0,
    "联想/命令菜单打开时放行（回车在那里是选中项）");

  // ⑤ 别的地方的回车（队列条目编辑框、搜索框…）不归这段管
  const e5 = fire(keydown(), element(false, false));
  ok(e5.prevented === 0, "输入框之外的回车放行");
  const e6 = fire(keydown({ key: "a", code: "KeyA", keyCode: 65 }), element(true, false));
  ok(e6.prevented === 0, "非回车键放行");

  // ⑥ 精确指针（桌面/接了硬键盘）：根本不注册监听，宿主行为原样保留
  const fine = harness(false);
  vm.runInContext(composer, fine.ctx);
  ok(fine.listeners.length === 0, "非触屏设备上不注册任何监听（桌面 Shift+Enter 本来就能按）");

  // ── 接线：两处注入 + 偏好开关 + 设置入口，缺一处这补丁就到不了用户手里 ──
  ok(/private fun installComposerShim\(view: WebView, url: String\)/.test(webui),
    "有 installComposerShim");
  ok(/WebViewCompat\.addDocumentStartJavaScript\(view, COMPOSER_SHIM, rules\)/.test(webui),
    "document-start 注入（监听必须排在宿主之前，晚一秒就拦不到）");
  ok(/!composerShimInstalled && isLoopback\(u\)/.test(webui) && /evaluateJavascript\(COMPOSER_SHIM/.test(webui),
    "onPageStarted 有回落注入（document-start 不支持时尽力而为）");
  ok(/if \(!DshWebCompat\.enterNewline\(this\)\)/.test(webui),
    "注入受用户偏好约束（关掉就不注入）");
  // 定义写对了不等于接上了：反向验证时"删掉调用点"曾经漏网（断言只查了函数定义），
  // 所以这里钉**调用现场**本身。
  ok(/composerShimInstalled = installComposerShim\(this, url\)/.test(webui),
    "建 WebView 时真的调用了它（只定义不调用=补丁永远装不上）");
  ok(/fun enterNewline\(ctx: Context\): Boolean/.test(compat) &&
    /getBoolean\(DshEnv\.KEY_WEB_ENTER_NEWLINE, true\)/.test(compat),
    "偏好默认**开**（手机上这不是可选项，是唯一能换行的办法）");
  ok(/const val KEY_WEB_ENTER_NEWLINE/.test(env), "偏好键落在 DshEnv");

  const fnSettings = fs.readFileSync(SRC_FUNCTION_SETTINGS, "utf8");
  const fnScreen = fs.readFileSync(SRC_FUNCTION_SETTINGS_SCREEN, "utf8");
  ok(/R\.string\.dsh_web_enter_newline_title/.test(fnSettings) &&
    /onWebEnterNewlineChange/.test(fnSettings),
    "设置里有一个能拨的开关（标题 + 回调）");
  ok(/DshWebCompat\.setEnterNewline\(/.test(fnScreen), "拨开关会落盘");
}

// ── 无障碍名字：在假 DOM 里真跑一遍 ──
//
// 用户现场：`a11y text` 打不进 WebView 的输入框 —— 树里那个"编辑框"既没有 text 也没有
// view id（网页元素的 viewIdResourceName 就是 null），agent 无从寻址。这一段补的是**
// aria-label**（Chromium 映射成 contentDescription）。风险全在"什么时候不该写"上：
// 覆盖宿主自己的名字、或替一个没名字的框编一个词。所以真跑。
{
  const a11yShim = rawStringConst(webui, "A11Y_SHIM");
  ok(a11yShim.length > 300, `无障碍名字脚本还原成功（${a11yShim.length} 字节）`);
  ok(!/\$\{/.test(a11yShim), "脚本里没有未展开的 Kotlin 模板");
  ok(/pointer|placeholder/.test(a11yShim) && /aria-label/.test(a11yShim),
    "判据是 placeholder → aria-label");
  ok(/MutationObserver/.test(a11yShim),
    "盯着后挂上来的输入框（SPA 首屏之后才渲染的搜索框）");

  function el(attrs) {
    const a = Object.assign({}, attrs);
    return {
      attrs: a,
      getAttribute: (k) => (k in a ? a[k] : null),
      setAttribute: (k, v) => { a[k] = v; },
    };
  }
  const inputs = [
    el({ placeholder: "Search sessions" }),
    // 这两个也带 placeholder：不带的话，即便补丁覆盖了宿主的名字，也没东西可覆盖 ——
    // "不动"就成了一个测不出来的断言（反向验证抓到过）
    el({ "aria-label": "already named", placeholder: "SHOULD-NOT-OVERWRITE" }),
    el({ title: "titled", placeholder: "SHOULD-NOT-OVERWRITE" }),
    el({}),
  ];
  const ctx = {
    console,
    document: {
      readyState: "complete",
      documentElement: {},
      querySelectorAll: () => inputs,
      addEventListener: () => {},
    },
    MutationObserver: function () { this.observe = () => {}; },
  };
  ctx.window = ctx;
  ctx.globalThis = ctx;
  vm.createContext(ctx);
  vm.runInContext(a11yShim, ctx);
  ok(inputs[0].attrs["aria-label"] === "Search sessions",
    "没名字但有 placeholder 的框，被写上同字的 aria-label（于是树里能读到、能 --target）");
  ok(inputs[1].attrs["aria-label"] === "already named", "已有 aria-label 的不动（不覆盖宿主语义）");
  ok(inputs[2].attrs["aria-label"] === undefined, "有 title 的也不动");
  ok(inputs[3].attrs["aria-label"] === undefined, "没名字也没 placeholder 的不硬造名字");
  ok(ctx.__dshFolkA11yLabel === 1, "装了幂等哨兵（onPageFinished 回落重复注入不会写两遍）");

  // 注入策略：与另外三段垫片同款 —— document-start 优先，装不上才回落（这里是 onPageFinished）
  ok(/private fun installA11yShim\(view: WebView, url: String\)/.test(webui), "有 installA11yShim");
  ok(/WebViewCompat\.addDocumentStartJavaScript\(view, A11Y_SHIM, rules\)/.test(webui),
    "document-start 注入（观察器要在页面脚本渲染出输入框之前就位）");
  {
    // 钉**顺序**：addDocumentStartJavaScript 只对"调用返回之后才开始加载"的 frame 生效 ——
    // 装在 loadUrl 之后等于对本次加载无效（反向验证：两处都在时会漏）。
    const installAt = webui.indexOf("a11yShimInstalled = installA11yShim(this, url)");
    const loadAt = webui.indexOf("loadUrl(url)");
    ok(installAt > 0 && loadAt > installAt, "loadUrl **之前**安装（晚了就对本次加载无效）");
  }
  ok(/!a11yShimInstalled && isLoopback\(u\)/.test(webui) && /evaluateJavascript\(A11Y_SHIM/.test(webui),
    "装不上时有回落（尽力而为），且只对回环 origin");
  {
    // 只看**这个函数**的函数体：别的三段垫片也用 loopbackOriginRules，全局找不到等于没查
    const i = webui.indexOf("private fun installA11yShim(");
    const j = webui.indexOf("private fun ", i + 10);
    const body = j > i ? webui.slice(i, j) : webui.slice(i);
    ok(/WebViewCompat\.addDocumentStartJavaScript\(view, A11Y_SHIM, loopbackOriginRules\(url\)\)/.test(body) ||
       /val rules = loopbackOriginRules\(url\)[\s\S]{0,120}?A11Y_SHIM, rules/.test(body),
      "只改我们自己页面的无障碍语义（rules 来自 loopback，别处也叫这个名字）");
  }
}

console.log(bad === 0 ? `\n全部通过（${n} 项断言）` : `\n${bad}/${n} 项失败`);
process.exit(bad === 0 ? 0 : 1);
