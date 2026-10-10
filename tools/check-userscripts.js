#!/usr/bin/env node
/**
 * 用户脚本（油猴 .user.js 的够用子集）的门禁。
 *
 * ## 为什么要有这个检查器
 *
 * 用户脚本的失败**全在"跑起来之后"**：元数据里 `@run-at` 读错 → 脚本在 DOM 还没有时跑、
 * 报 TypeError；`@exclude` 语义写成"随便命中一个就跑"→ 该排除的页面照跑；GM 存储命名空间
 * 用了**标题**而不是**文件 id** → 两个同名脚本互相覆盖对方的值；一段脚本抛错把整段注入吞掉
 * → 用户只看到"我这脚本没生效"，控制台里一个字都没有。
 *
 * 这些用正则匹配源码一个都防不住，所以这里分三层：
 * 1. 解析（元数据块）与 `@match`/`@exclude` —— 对着**表**逐格断言；
 * 2. 注入体（`Userscripts.blob`）—— 从 Kotlin 里抠出来，在**假 DOM 里真跑**：
 *    `start/end/idle` 三档时机、幂等哨兵、GM_* 读写与通知、抛错隔离；
 * 3. 管线 —— 一段一个脚本地 document-start 注入、只给回环、装在 loadUrl 之前、回落路径、
 *    JS→原生的 Toast 桥、管理页的三个动作。
 *
 * ## 与 Kotlin 的关系
 *
 * 第 1 层是**复刻**（Kotlin 是纯函数，JS 这边照抄一份来对表），所以每条复刻旁边都钉一句
 * 源码锚点：Kotlin 改了而这里没跟上时，锚点先报。
 */
const fs = require("fs");
const vm = require("vm");

const SRC_US = "app/src/main/java/me/bmax/apatch/dsh/Userscripts.kt";
const SRC_WEBUI = "app/src/main/java/me/bmax/apatch/ui/DshWebUiActivity.kt";
const SRC_ENV = "app/src/main/java/me/bmax/apatch/dsh/DshEnv.kt";
const SRC_MODULE = "app/src/main/java/me/bmax/apatch/ui/screen/settings/ModuleSettings.kt";
const SRC_MODULE_SCREEN = "app/src/main/java/me/bmax/apatch/ui/screen/settings/ModuleSettingsScreen.kt";
const SRC_SCREEN = "app/src/main/java/me/bmax/apatch/ui/screen/settings/UserscriptsScreen.kt";

let n = 0;
let bad = 0;
function ok(cond, label) {
  n++;
  if (cond) console.log("  ✓ " + label);
  else {
    bad++;
    console.log("  ✗ " + label);
  }
}
function eq(actual, expected, label) {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  ok(a === e, label + (a === e ? "" : `（期望 ${e}，实际 ${a}）`));
}

const us = fs.readFileSync(SRC_US, "utf8");
const webui = fs.readFileSync(SRC_WEBUI, "utf8");
const env = fs.readFileSync(SRC_ENV, "utf8");
const moduleSrc = fs.readFileSync(SRC_MODULE, "utf8");
const fnScreen = fs.readFileSync(
  "app/src/main/java/me/bmax/apatch/ui/screen/settings/FunctionSettingsScreen.kt",
  "utf8",
);
const moduleScreen = fs.readFileSync(SRC_MODULE_SCREEN, "utf8");
const screen = fs.readFileSync(SRC_SCREEN, "utf8");

console.log("\n── 元数据解析：对表 ──");

// 复刻 `Userscripts.parse`。Kotlin 那份的每一句都在下面被锚点钉着。
function parseMeta(text) {
  const out = {};
  const push = (k, v) => {
    (out[k] = out[k] || []).push(v);
  };
  let inside = false;
  for (const raw of text.replace(/\r\n/g, "\n").split("\n")) {
    const line = raw.trim();
    if (line.includes("==UserScript==")) {
      inside = true;
      continue;
    }
    if (!inside) continue;
    if (line.includes("==/UserScript==")) break;
    if (!line.startsWith("//")) continue;
    const body = line.replace(/^\/\//, "").trim();
    if (!body.startsWith("@")) continue;
    const rest = body.slice(1);
    const key = rest.split(" ")[0].trim().toLowerCase();
    if (!key) continue;
    const value = rest.includes(" ") ? rest.slice(rest.indexOf(" ") + 1).trim() : "";
    push(key, value);
  }
  const first = (k) => (out[k] && out[k][0]) || "";
  const matches = [...(out.match || []), ...(out.include || [])].filter((v) => v !== "");
  const excludes = (out.exclude || []).filter((v) => v !== "");
  const runAtMap = {
    "document-start": "start",
    start: "start",
    "document-idle": "idle",
    idle: "idle",
  };
  return {
    title: first("name") || first("title"),
    version: first("version"),
    description: first("description"),
    runAt: runAtMap[first("run-at").toLowerCase()] || "end",
    matches,
    excludes,
  };
}

ok(/line\.contains\("==UserScript=="\)/.test(us) && /startsWith\("\/\/"\)/.test(us),
  "锚点：解析只认 // 行注释的元数据块");
ok(/"document-start", "start" -> "start"/.test(us) && /else -> "end"/.test(us),
  "锚点：@run-at 三档归一，缺省 end（清单之外的写法不算 start）");
ok(/matches = include,\s*\n\s*excludes = exclude,/.test(us),
  "锚点：@match/@include 归一为 matches，@exclude 单独一路");

{
  const real = [
    "// ==UserScript==",
    "// @name         Wide rail",
    "// @namespace    dsh-folk",
    "// @version      1.2.0",
    "// @description  widen the left rail",
    "// @match        *://127.0.0.1:*/*",
    "// @match        http://localhost:*/*",
    "// @include      /s/*",
    "// @exclude      *://127.0.0.1:*/settings*",
    "// @run-at       document-idle",
    "// @grant        GM_setValue",
    "// ==/UserScript==",
    "",
    '(function(){ document.title = "x"; })();',
  ].join("\n");
  const m = parseMeta(real);
  eq(m.title, "Wide rail", "@name");
  eq(m.version, "1.2.0", "@version");
  eq(m.description, "widen the left rail", "@description");
  eq(m.runAt, "idle", "@run-at document-idle → idle");
  eq(m.matches, ["*://127.0.0.1:*/*", "http://localhost:*/*", "/s/*"],
    "@match ×2 + @include 合并进 matches（顺序保持）");
  eq(m.excludes, ["*://127.0.0.1:*/settings*"], "@exclude 单独一路");
}
{
  const crlf = "// ==UserScript==\r\n// @name  A\r\n// @run-at document-start\r\n// ==/UserScript==\r\n";
  const m = parseMeta(crlf);
  eq([m.title, m.runAt], ["A", "start"], "CRLF 头（从 Windows 粘贴过来的）照样解析");
}
{
  const m = parseMeta("(function(){})();");
  eq([m.title, m.version, m.runAt, m.matches.length], ["", "", "end", 0],
    "没有元数据块：不报错、不回退成 start，交给调用方兜底标题");
}
{
  const m = parseMeta("/* ==UserScript==\n   @name Block\n   ==/UserScript== */\n");
  eq(m.title, "", "块注释里的元数据认不出来（已在 KDoc 的「边界」里写明，不是 bug）");
}
{
  const m = parseMeta("// ==UserScript==\n// @Name Case\n// @RUN-AT DOCUMENT-START\n// ==/UserScript==\n");
  eq([m.title, m.runAt], ["Case", "start"], "键名大小写不敏感（@Name / @RUN-AT）");
}

console.log("\n── @match / @exclude：对表 ──");

function globToRegex(glob) {
  let sb = "^";
  for (const c of glob) {
    if (c === "*") sb += ".*";
    else if (/[A-Za-z0-9/:._-]/.test(c)) sb += c === "." ? "\\." : c;
    else sb += "\\" + c;
  }
  return sb + "$";
}
function matchesAny(url, patterns) {
  if (patterns.length === 0) return false;
  const clean = url.split("#")[0].split("?")[0];
  return patterns.some((p) => {
    try {
      return new RegExp(globToRegex(p.split("#")[0].split("?")[0])).test(clean);
    } catch (e) {
      return false;
    }
  });
}
function applies(url, meta) {
  return (meta.matches.length === 0 || matchesAny(url, meta.matches)) &&
    !matchesAny(url, meta.excludes);
}

ok(/if \(patterns\.isEmpty\(\)\) return false/.test(us),
  "锚点：matchesAny 对空模式返回 false（「没写模式」由 applies 决定放行，不是 matchesAny 自作主张）");
ok(/\(meta\.matches\.isEmpty\(\) \|\| matchesAny\(url, meta\.matches\)\) &&[\s\S]{0,80}!matchesAny\(url, meta\.excludes\)/.test(us),
  "锚点：applies = （没写模式 或 命中）且 未被 @exclude 命中（排除优先）");
// 用户要求把总开关连逻辑一起删（2026-10）：注入只由逐条开关决定，引擎与 DshEnv 里都不该
  // 再出现任何总开关键/读取函数。
  ok(!/KEY_USERSCRIPTS_ON/.test(us) && !/masterEnabled/.test(us),
    "总开关逻辑已删除（用户脚本注入不再受任何总开关影响）");
ok(/url\.substringBefore\('#'\)\.substringBefore\('\?'\)/.test(us) &&
  /it\.substringBefore\('#'\)\.substringBefore\('\?'\)/.test(us),
  "锚点：匹配前 URL 与模式**两侧**都去掉 #fragment 与 ?query（@match 不管查询串）");

const HERE = "http://127.0.0.1:8080/s/1";
eq(globToRegex("*://127.0.0.1:*/*"), "^.*://127\\.0\\.0\\.1:.*/.*$",
  "通配转正则（. 被转义，* 变 .*）");

eq(matchesAny(HERE, []), false, "空模式 → false（「没写」由 applies 放行）");
for (const [patterns, want, label] of [
  [["*://127.0.0.1:*/*"], true, "*://127.0.0.1:*/* 命中自己的页面"],
  [["http://localhost:8080/*"], false, "host 写错（localhost）→ 不命中"],
  [["http://127.0.0.1:8080/s/*"], true, "带路径前缀的模式命中"],
  [["http://127.0.0.1:8080/other/*"], false, "路径不符 → 不命中"],
  [["*://example.com/*"], false, "别的站点 → 不命中"],
  [["*://*/*"], true, "最宽的 *://*/* 命中"],
  [["http://127.0.0.1:8080/s/1"], true, "精确 URL 命中"],
  [["http://127.0.0.1:8080/s/1?x=1"], true, "模式带查询串也命中（两侧都去掉查询）"],
  [["http://127.0.0.1:8080/s.1"], false, "模式里的 . 只匹配字面点，不当「任意字符」"],
]) {
  eq(matchesAny(HERE, patterns), want, label);
}

eq(matchesAny("http://127.0.0.1:8080/s/(1)", ["http://127.0.0.1:8080/s/(1)"]), true,
  "带括号的 URL 精确命中（括号是字面量，不是分组）");
eq(applies(HERE, { matches: [], excludes: [] }), true, "没写 @match → 放行（我们这儿「到处」只有一个站）");
eq(applies(HERE, { matches: ["*://*/*"], excludes: [] }), true, "命中的 @match → 跑");
eq(applies(HERE, { matches: ["*://example.com/*"], excludes: [] }), false, "不命中的 @match → 不跑");
eq(applies(HERE, { matches: ["*://*/*"], excludes: ["*://127.0.0.1:*/*"] }), false,
  "@exclude 命中 → 即便 @match 命中也**不跑**（排除优先）");
eq(applies(HERE, { matches: [], excludes: ["*://example.com/*"] }), true,
  "没写 @match、@exclude 又没命中 → 跑");
eq(applies(HERE, { matches: [], excludes: ["http://127.0.0.1:8080/*"] }), false,
  "没写 @match、@exclude 命中 → 不跑");

console.log("\n── 注入体：在假 DOM 里真跑 ──");

/**
 * 从 Kotlin 里还原 `Userscripts.blob`：它是**函数**返回值（要插入标题/版本/时机/脚本文本），
 * 所以抠出三引号后把 5 个 Kotlin 模板换成具体值。
 *
 * 注意签名是 `internal fun` —— 内置那几段（WebScripts）也走这个包装，所以它不再是 private。
 */
const blobMatch = us.match(/internal fun blob\([\s\S]*?= """\n([\s\S]*?)\n"""\.trimIndent\(\)/);
ok(blobMatch !== null, "能从 Userscripts.kt 抠出 blob");
const blobTemplate = blobMatch ? blobMatch[1] : "";

/** 造一个假 window/document；返回登记下来的调用。 */
function harness({ runAt = "start", code = "", readyState = "loading" } = {}) {
  const warns = [];
  const logs = [];
  const styles = [];
  const notified = [];
  const store = new Map();
  const listeners = {};
  const idle = [];
  const timeouts = [];
  const doc = {
    readyState,
    documentElement: { appendChild: (el) => styles.push(el) },
    head: { appendChild: (el) => styles.push(el) },
    createElement: (tag) => ({ tagName: tag, textContent: "" }),
    addEventListener: (ev, fn) => {
      listeners[ev] = fn;
    },
  };
  const ctx = {
    document: doc,
    console: {
      log: (...a) => logs.push(a.join(" ")),
      warn: (...a) => warns.push(a.join(" ")),
    },
    localStorage: {
      getItem: (k) => (store.has(k) ? store.get(k) : null),
      setItem: (k, v) => store.set(k, v),
      removeItem: (k) => store.delete(k),
    },
    requestIdleCallback: (f) => {
      idle.push(f);
      return idle.length;
    },
    setTimeout: (f) => {
      timeouts.push(f);
      return timeouts.length;
    },
  };
  ctx.window = ctx;
  ctx.DshFolkNotify = { notify: (title, text) => notified.push([title, text]) };
  // 假页面：GM / unsafeWindow / DshFolkNotify / 事件
  const js = blobTemplate
    .replace(/\$\{js\(meta\.title\.ifBlank \{ id \}\)\}/, JSON.stringify("Demo title"))
    .replace(/\$\{js\(id\)\}/, JSON.stringify("demo-title-1a2b3c4d"))
    .replace(/\$\{js\(meta\.version\)\}/, JSON.stringify("1.0"))
    .replace(/\$\{js\(meta\.runAt\)\}/, JSON.stringify(runAt))
    .replace(/\$\{code\}/, code);
  return { js, ctx, warns, logs, styles, notified, store, listeners, idle, timeouts, meta: { runAt } };
}

function run(js, ctx) {
  vm.createContext(ctx);
  vm.runInContext(js, ctx);
}

{
  const h = harness({
    runAt: "start",
    code: [
      "unsafeWindow.__ran = (unsafeWindow.__ran || 0) + 1;",
      'GM_addStyle("#a{color:red}");',
      'GM_setValue("n", 7);',
      'GM_log("hello", 42);',
      'GM_notification("done", "Demo");',
    ].join("\n"),
  });
  ok(h.js.length > 500 && !/\$\{/.test(h.js), `注入体还原成功（${h.js.length} 字节，无未展开模板）`);
  try {
    new vm.Script(h.js);
    ok(true, "注入体语法有效");
  } catch (e) {
    ok(false, "注入体语法有效（" + e.message + "）");
  }
  run(h.js, h.ctx);
  ok(h.ctx.__ran === 1, "document-start：脚本当场跑（unsafeWindow === window）");
  eq(h.styles.length, 1, "GM_addStyle 插了 1 个 style");
  eq(h.styles[0].textContent, "#a{color:red}", "style 内容原样");
  eq(h.store.get("dshFolk.gm.demo-title-1a2b3c4d.n"), "7", "GM_setValue 落进 localStorage（按**文件 id** 分命名空间）");
  eq(h.notified, [["Demo", "done"]], "GM_notification 走原生桥（title, text）");
  ok(h.warns.length === 0, "跑完没有警告");
  eq(h.logs.length, 1, "GM_log 只写一条（不是两条）");

  // 幂等哨兵：document-start 装上了、onPageStarted 又回落一次 —— 不该跑两遍
  delete h.ctx.__dshFolkRunner;
  run(h.js, h.ctx);
  ok(h.ctx.__ran === 1, "重复注入只跑一次（回落路径不会让脚本跑两遍）");
}

{
  // GM_getValue / deleteValue：预置一份"上次存下的值"
  const h = harness({
    runAt: "start",
    code: [
      "window.__got = GM_getValue(\"n\", -1);",
      "GM_deleteValue(\"n\");",
      "window.__after = GM_getValue(\"n\", -1);",
      "window.__missing = GM_getValue(\"nope\", \"d\");",
    ].join("\n"),
  });
  h.store.set("dshFolk.gm.demo-title-1a2b3c4d.n", "7");
  run(h.js, h.ctx);
  eq(h.ctx.__got, 7, "GM_getValue 读回（JSON 解码）");
  eq(h.ctx.__after, -1, "GM_deleteValue 之后回到默认值");
  eq(h.ctx.__missing, "d", "没有这个键 → 用默认值");
}

{
  // @run-at 的三档时机
  const code = "window.__ran = (window.__ran || 0) + 1;";
  const loading = harness({ runAt: "end", code, readyState: "loading" });
  run(loading.js, loading.ctx);
  ok(loading.ctx.__ran === undefined, "document-end + 文档还在加载：先不跑");
  ok(typeof loading.listeners.DOMContentLoaded === "function", "document-end：登记在 DOMContentLoaded 上");
  loading.listeners.DOMContentLoaded();
  ok(loading.ctx.__ran === 1, "DOMContentLoaded 一到就跑");

  const ready = harness({ runAt: "end", code, readyState: "complete" });
  run(ready.js, ready.ctx);
  ok(ready.ctx.__ran === 1, "document-end + 文档已就绪：立刻跑（不白等一个不会再来的事件）");

  const idle = harness({ runAt: "idle", code });
  run(idle.js, idle.ctx);
  ok(idle.ctx.__ran === undefined, "document-idle：交给 requestIdleCallback");
  eq(idle.idle.length, 1, "登记了 1 次 idle 回调");
  idle.idle[0]();
  ok(idle.ctx.__ran === 1, "idle 回调一到就跑");
}

{
  const h = harness({
    runAt: "start",
    code: 'window.__before = 1; throw new Error("boom"); window.__after = 2;',
  });
  run(h.js, h.ctx);
  ok(h.ctx.__before === 1, "抛错前的语句已生效");
  ok(h.ctx.__after === undefined, "抛错后的语句没跑（异常真的抛出去了）");
  eq(h.warns.length, 1, "异常只报一次（console.warn）");
  ok(h.warns[0].includes("Demo title"), "警告里带**标题**，不是文件 id（人读的是它）");
  ok(h.ctx.__dshFolkRunner["demo-title-1a2b3c4d"] === 1, "哨兵仍按文件 id 记录");
}

{
  // 标题相同、文件 id 不同：同一个页面（同一份 localStorage）里各存一份
  const a = harness({ runAt: "start", code: 'GM_setValue("k", "A");' });
  run(a.js, a.ctx);
  const b = harness({ runAt: "start", code: 'GM_setValue("k", "B");' });
  b.js = b.js.replace(/demo-title-ffffffff|demo-title-1a2b3c4d/g, "demo-title-ffffffff");
  run(b.js, a.ctx);
  eq(a.store.get("dshFolk.gm.demo-title-1a2b3c4d.k"), "\"A\"", "脚本 A 的值还在原处");
  eq(a.store.get("dshFolk.gm.demo-title-ffffffff.k"), "\"B\"", "同名的 B 存在**自己 id** 的命名空间里");
  ok(a.ctx.__dshFolkRunner["demo-title-ffffffff"] === 1, "哨兵也按 id 分开（同名不会互相顶掉）");
}

{
  // 没有原生桥时 GM_notification 不该炸
  const h = harness({ runAt: "start", code: 'GM_notification("no bridge");' });
  delete h.ctx.DshFolkNotify;
  run(h.js, h.ctx);
  ok(h.warns.length === 0, "没有原生桥：退化成 console.log，不报错");
  ok(h.logs.some((l) => l.includes("no bridge")), "退化路径里仍能看到正文");
}

console.log("\n── 管线：注入 / 回落 / 桥 / 管理页 ──");

ok(/fun injections\(ctx: Context, url: String\): List<String>/.test(us), "injections 返回**多段**（不是一大段）");
// 注入闸现在只看逐条开关（总开关那一段判断已随逻辑一起删除）。
  ok(!/masterEnabled/.test(us) && /KEY_USERSCRIPTS_ENABLED/.test(us),
    "注入只由逐条开关决定（总开关逻辑已删）");
ok(/\.filter \{ it\.enabled \}/.test(us) && /if \(!applies\(url, meta\)\) return@mapNotNull null/.test(us),
  "逐条过滤：启用的 + 匹配这次 URL 的");
ok(/fun read\(ctx: Context, uri: Uri\): String\?/.test(us), "从 content:// 读文本（文件选择器那条路）");
ok(/if \(on\) next\.add\(id\) else next\.remove\(id\)/.test(us), "启用 = 名单里加/去一个 id");
ok(/File\(d, "\$id\.user\.js"\)\.writeText\(text\)/.test(us) && /setEnabled\(ctx, id, true\)/.test(us),
  "装 = 落一个文件 + 默认启用");
ok(/File\(dir\(ctx\), "\$id\.user\.js"\)\.delete\(\)/.test(us) && /setEnabled\(ctx, id, false\)/.test(us),
  "删 = 删文件 + 从启用名单里去掉");

// ── 注入管道：内置（WebScripts.kt 的注册表）与导入的脚本走同一条路 ──
const webScripts = fs.readFileSync("app/src/main/java/me/bmax/apatch/dsh/WebScripts.kt", "utf8");

ok(/private fun installScripts\(view: WebView, url: String\): Boolean/.test(webui) &&
  /scripts\.forEach \{ WebViewCompat\.addDocumentStartJavaScript\(view, it, rules\) \}/.test(webui),
  "**一段一个脚本**地注册 document-start（一段语法错只毁它自己）");
ok(/private fun injectScriptsNow\(view: WebView\?, url: String\?\)/.test(webui) &&
  /WebScripts\.injections\(this, url, currentInsets\(\)\)\.forEach/.test(webui),
  "document-start 装不上时逐段回落");
ok(/scriptsInstalled = installScripts\(this, url\)/.test(webui), "安装点存在（内置 + 导入共用一个）");
{
  const a = webui.indexOf("scriptsInstalled = installScripts(this, url)");
  const b = webui.indexOf("loadUrl(url)");
  ok(a > 0 && b > a, "装在 loadUrl **之前**（document-start 注册只对之后开始的加载生效）");
}
ok(/!scriptsInstalled && isLoopback\(u\)/.test(webui) && /injectScriptsNow\(view, u\)/.test(webui),
  "onPageStarted 的回落只在回环页面上做");
ok(/val rules = loopbackOriginRules\(url\)[\s\S]{0,200}addDocumentStartJavaScript/.test(webui),
  "origin 规则：内置与用户脚本都只改我们自己的页面");

// ── 内置清单：顺序 / 时机 / 开关映射 / 不受总开关 / 参数通道 ──
{
  const order = [...webScripts.matchAll(/id = "([\w-]+)",[\s\S]{0,200}?asset = "webui-scripts\/([\w.-]+)"/g)]
    .map((m) => m[2]);
  ok(order.length === 5, `注册表里 5 条内置（${order.join(" → ")}）`);
  ok(order[0] === "compat.js", "compat 排第一（它补的 API 别的脚本与页面都要用）");
  ok(order[1] === "inset.js", "inset 排第二（第一帧就要就位，晚一点就是一跳）");

  const runAts = [...webScripts.matchAll(/runAt = RunAt\.(\w+)/g)].map((m) => m[1]);
  ok(runAts.length === 5 && runAts[4] === "END" && runAts.filter((r) => r === "END").length === 1,
    `只有 blob 用 RunAt.END（其余 ${runAts.filter((r) => r === "START").length} 条必须 document-start）`);

  ok(/if \(!builtinEnabled\(ctx, entry\.id\)\) continue/.test(webScripts),
    "内置逐条过开关（不是一律注入）");
  ok(/"compat" -> DshWebCompat\.shouldInject\(ctx\)/.test(webScripts) &&
    /"composer" -> DshWebCompat\.enterNewline\(ctx\)/.test(webScripts),
    "有开关的那两条映射到既有 pref（与设置页同一处状态）");
  ok(/else -> true/.test(webScripts), "其余三条常开（内边距是布局前提，a11y/blob 是补页面缺陷）");

  // 总开关只该关「导入的」那一档：内置绝不能挂在 masterEnabled 上 ——
  // 一个坏脚本把页面弄白时，内边距/无障碍/兼容垫片还得在。
  const beforeImported = webScripts.slice(
    0, webScripts.indexOf("out.addAll(Userscripts.injections(ctx, url))"));
  ok(beforeImported.length > 0 && !/masterEnabled/.test(beforeImported),
    "内置那一段不看用户脚本总开关（总开关是「我装的先别跑」）");
  ok(/out\.addAll\(Userscripts\.injections\(ctx, url\)\)/.test(webScripts),
    "导入的脚本追加在内置之后（用户脚本看到的是已经打过补丁的页面）");

  ok(/const val BUILTIN_PREFIX = "builtin:"/.test(webScripts) &&
    /if \(id\.startsWith\(WebScripts\.BUILTIN_PREFIX\)\) return/.test(us),
    "内置 id 带保留前缀，用户脚本改不动它的开关（setEnabled 直接拒收）");
  ok(/internal fun blob\(/.test(us), "内置与导入共用同一个包装（一处实现）");

  const marker = webScripts.match(/PARAM_MARKER = "([^"]+)"/);
  ok(marker !== null && webScripts.includes("text.replace(PARAM_MARKER, value)"),
    "内边距参数：注入前整体替换占位符");
  ok(/if \(!entry\.needsParams\) return text/.test(webScripts) &&
    /if \(!text\.contains\(PARAM_MARKER\)\)/.test(webScripts),
    "只有声明了 needsParams 的条目走替换；换不到就保持正文（全 0，合法 JS）");
  ok((webScripts.match(/needsParams = true/g) || []).length === 1,
    "只有内边距声明需要原生参数（参数通道只有一条）");

  // 管理页：遍历注册表列内置（不写死 5 行），有开关的接同一条 pref，其余显示常开
  ok(/for \(entry in (if \(hideBuiltins\) emptyList\(\) else )?WebScripts\.BUILTINS\)/.test(screen),
    "管理页遍历注册表列内置（不写死 5 行；可以按「隐藏内置」开关条件取值）");
  ok(/DshWebCompat\.setMode\(\s*context,/.test(screen) && /DshWebCompat\.setEnterNewline\(context, on\)/.test(screen),
    "内置那两条开关落到既有 pref（与设置页同一处状态）");
  ok(/R\.string\.dsh_userscripts_builtin_always_on/.test(screen), "没有开关的显示「常开」");
  ok(/stringResource\(entry\.titleRes\)/.test(screen) && /stringResource\(entry\.summaryRes\)/.test(screen),
    "标题/摘要走字符串资源（内置条目的文案中英都有）");
}
ok(/addJavascriptInterface\(UserscriptBridge\(\), USERSCRIPT_BRIDGE\)/.test(webui), "GM_notification 的桥挂上了");
ok(/private const val USERSCRIPT_BRIDGE = "DshFolkNotify"/.test(webui) &&
  /private inner class UserscriptBridge[\s\S]{0,400}@JavascriptInterface[\s\S]{0,120}fun notify\(/.test(webui),
  "桥类 + 方法（JS 线程进来，转回 UI 线程）");
ok(/runOnUiThread \{ showToast\(this@DshWebUiActivity, body\) \}/.test(webui) &&
  /\.take\(200\)/.test(webui),
  "落到 Toast，且截断长度（一个脚本刷不了屏）");

ok(/const val KEY_USERSCRIPTS_ENABLED = "dsh_userscripts_enabled"/.test(env) &&
    !/KEY_USERSCRIPTS_ON/.test(env),
    "只剩「逐个启用」这一个 prefs 键（总开关键已从 DshEnv 删除）");
ok(/internal fun idOf\(title: String, text: String\): String/.test(us) &&
  /Integer\.toHexString\(text\.hashCode\(\)\)/.test(us),
  "文件名 = 标题 slug + 正文哈希（标题进名字，重装同文即覆盖）");
// 2026-10 起入口在**插件首页**（底栏「插件」那一页）的第二组：用户脚本与 DSH 插件是同一件事
// 的两半（都往容器/页面里塞东西），分成两处入口等于要用户先记住"它在哪一页"。
const pluginHome = fs.readFileSync(
  "app/src/main/java/me/bmax/apatch/ui/screen/DshPluginScreen.kt",
  "utf8",
);
ok(!/UserscriptsScreenDestination/.test(fnScreen),
  "功能页右上角那个入口已撤（入口搬去插件首页，不留第二处）");
ok(/internal fun UserscriptsContent\(/.test(screen) && /UserscriptsContent\(/.test(pluginHome),
  "插件首页与独立页共用同一份正文（UserscriptsContent），不是各写一份");
// 只看 ModuleGroupRow 那一段：这两句 label 在文件别处也出现（标题栏也按组换），
// 全文匹配抓不住"把 chip 的标签换成别的资源"这种改动。
const groupRow = (pluginHome.match(/private fun ModuleGroupRow\([\s\S]*?\n\}/) || [])[0] || "";
ok(/private const val GROUP_PLUGINS = "plugins"/.test(pluginHome) &&
  /private const val GROUP_SCRIPTS = "scripts"/.test(pluginHome) &&
  /FilterChip\(/.test(groupRow) &&
  /R\.string\.dsh_plugins/.test(groupRow) && /R\.string\.dsh_userscripts_title/.test(groupRow),
  "插件首页有「DSH 插件 / 用户脚本」两组的切换（FilterChip，与商店分类行同一套视觉）");
ok(/if \(group == GROUP_SCRIPTS\) ScriptMarketScreenDestination\(initialQuery = ""\)[\s\S]{0,40}else DshPluginStoreScreenDestination/.test(pluginHome),
  "商店按钮按当前显示的那一组分流：插件 → 插件商店；脚本 → 脚本市场（两边都是开一页）");
const marketScreen = fs.readFileSync("app/src/main/java/me/bmax/apatch/ui/screen/ScriptMarketScreen.kt", "utf8");
ok(/@Destination<RootGraph>/.test(marketScreen) && /ScriptMarket\./.test(marketScreen) &&
  !/revealMarket/.test(screen) && !/scrollState\.animateScrollTo\(/.test(screen) &&
  !/marketQuery/.test(screen),
  "市场是独立一页（脚本页里既没有页内市场，也没有「把它滚进视野」那套机制）");
ok(/filter = scriptFilter/.test(pluginHome) && /val shown = if \(filter\.isBlank\(\)\)/.test(screen),
  "一个搜索栏管两组：脚本那组用它过滤「我装的」");
// 悬浮球里那个入口按用户要求撤掉了 —— 连同它专用的 AppNavigation 一跳。留着反向断言：
// 同一个功能两处表达的旧形态不能再回来（入口只该是插件首页那一组 + 设置搜索）。
const webuiAct = fs.readFileSync("app/src/main/java/me/bmax/apatch/ui/DshWebUiActivity.kt", "utf8");
const mainAct = fs.readFileSync("app/src/main/java/me/bmax/apatch/ui/MainActivity.kt", "utf8");
ok(!/onOpenScripts/.test(webuiAct) && !/Icons\.Outlined\.Extension/.test(webuiAct),
  "WebUI 悬浮球里的用户脚本入口已删（要去那一页走插件首页或设置搜索）");
ok(!/AppNavigation/.test(webuiAct) && !/AppNavigation/.test(mainAct) &&
  !fs.existsSync("app/src/main/java/me/bmax/apatch/ui/AppNavigation.kt"),
  "连带撤掉 AppNavigation 那一跳：它只为悬浮球而写，没有第二个调用方");
// 用户报「用户脚本的卡片没对齐插件页」：脚本页那一列是带 verticalScroll 的 Column（每行自己加
// padding），插件页是 LazyColumn（contentPadding 左右 16dp + spacedBy 12dp）。所以几何要一对：
// 卡片壳外边距左右 16dp、上下各 6dp（合起来 12dp），形状与底色照抄插件卡片。
const cardAt = screen.indexOf("private fun ScriptCard(");
// 取到下一个 @Composable 为止 = 这个函数的体（不带上后面的 BuiltinRow）
// 注意要带换行的 "@Composable"：参数里的 content: @Composable () -> Unit 也在同一行附近，
// 不带边界会把函数体截断成一行，断言就自己把自己坑了。
const cardEnd = cardAt > 0 ? screen.indexOf("\n@Composable", cardAt + 10) : -1;
const cardBody = cardAt > 0 && cardEnd > cardAt ? screen.slice(cardAt, cardEnd) : "";
ok(cardBody.length > 0 && !/padding\(/.test(cardBody) &&
  /shape = RoundedCornerShape\(20\.dp\)/.test(cardBody) &&
  /secondaryContainer\.copy\(alpha = 0\.2f\)/.test(cardBody),
  "脚本卡片壳与插件卡片同一套几何（边距交给列表 / 20dp 圆角 / secondaryContainer 0.2）");
// 列表度量要跟插件页那一侧一致：LazyColumn + contentPadding 左右 16dp + spacedBy 12dp。
ok(/LazyColumn\(/.test(screen) &&
  /contentPadding = PaddingValues\([\s\S]{0,160}start = 16\.dp[\s\S]{0,120}end = 16\.dp/.test(screen) &&
  /spacedBy\(12\.dp\)/.test(screen),
  "列表容器与插件页同一套（LazyColumn，contentPadding 左右 16dp + 间距 12dp）");
// 不可卸载的内置脚本复用插件页那套标签与措辞（不另造同义串）。
const builtinAt = screen.indexOf("private fun BuiltinRow(");
const builtinEnd = builtinAt > 0 ? screen.indexOf("\n@Composable", builtinAt + 10) : -1;
const builtinRow = builtinAt > 0 && builtinEnd > builtinAt ? screen.slice(builtinAt, builtinEnd) : "";
ok(builtinRow.length > 0 && /R\.string\.dsh_plugin_builtin_label/.test(builtinRow) &&
  /R\.string\.dsh_host_plugin_version/.test(builtinRow) && !/autoTag/.test(screen),
  "不可卸载的内置脚本用插件页那套「内置」标签 + 「DSH-Folk 内置 · 不可卸载」措辞");
// 用户报「脚本页缺少更新和移除按钮」+ 要一个脚本详情页。三件事的实质：
// 更新必须知道来源（Script 原来没有来源字段，id 含正文 hash，重新 install 会留下两份），
// 所以来源存 prefs、更新拉新→装新→删旧→搬开关；没有来源的（文件导入/旧版本装的）不假造原地更新。
const userscripts = fs.readFileSync("app/src/main/java/me/bmax/apatch/dsh/Userscripts.kt", "utf8");
const detail = fs.readFileSync("app/src/main/java/me/bmax/apatch/ui/screen/ScriptDetailScreen.kt", "utf8");
ok(/val source: String\? = null/.test(userscripts) &&
  /fun rememberSource\(/.test(userscripts) &&
  /fun update\(ctx: Context, id: String\): UpdateOutcome/.test(userscripts) &&
  /NO_SOURCE/.test(userscripts) && /UP_TO_DATE/.test(userscripts),
  "来源存进 prefs、更新按 outcome 分流（没来源 → NO_SOURCE，正文没变 → UP_TO_DATE）");
ok(/Icons\.Outlined\.Refresh\b/.test(screen) && /onUpdate/.test(screen) &&
  /onOpen/.test(screen) && /combinedClickable/.test(screen),
  "列表行有「更新」+ 整行进详情（列表仍保留快捷开关）");
// 设计已换：脚本详情**不再是二级目的地**，改成与插件详情（ui/component/DshPluginDetail.kt 的
// DshPluginDetailSheet）同形态的底部弹层。所以旧断言里的「@Destination + scriptId 参数」这条
// 路整个不成立 —— 现在钉三件事：那个 internal 函数在、用的是 ModalBottomSheet、文件里没有
// @Destination（去掉二级目的地这条旧路），以及两个动作的字符串仍在。
// 判据扫剥过注释的正文：KDoc 里正拿 `@Destination ScriptDetailScreen` 当「以前是什么」的反例。
const detailCode = detail.replace(/\/\*[\s\S]*?\*\//g, "").replace(/(^|[^:])\/\/[^\n]*/g, "$1");
ok(/internal fun ScriptDetailSheet\(/.test(detailCode) && /ModalBottomSheet\(/.test(detailCode) &&
  !/@Destination/.test(detailCode) &&
  /R\.string\.dsh_userscripts_update/.test(detailCode) &&
  /R\.string\.dsh_userscripts_remove|R\.string\.dsh_userscripts_delete/.test(detailCode),
  "详情是底部弹层（internal fun ScriptDetailSheet + ModalBottomSheet，不再是二级目的地），层内有更新与移除两个动作");
ok(/heightIn\(max = 320\.dp\)/.test(detail) && /verticalScroll/.test(detail),
  "正文预览限高的同时给了滚动出路（限高不给路会被 check-text-clipping 抓）");
ok((screen.match(/ScriptCard \{/g) || []).length >= 2,
  "内置那几段与「我装的」都用同一个壳（同一页不能一半卡片一半裸行）");
ok(!/Modifier\.fillMaxWidth\(\)\.padding\(start = 16\.dp, end = 16\.dp, top = 8\.dp, bottom = 8\.dp\)/.test(screen) &&
  !/Modifier\.fillMaxWidth\(\)\.padding\(start = 16\.dp, end = 8\.dp, top = 8\.dp, bottom = 8\.dp\)/.test(screen),
  "旧的裸行 padding 形态已不存在（否则就是两套几何并存）");
ok(!/module_userscripts/.test(moduleSrc) && !/onOpenUserscripts/.test(moduleScreen),
  "插件页那张卡已摘掉（没有两处入口各说各话）");

ok(/if \(script\.runAt\.isNotBlank\(\)|runAt = meta\.runAt/.test(us), "管理页拿得到 run-at");
ok(/fun list\(ctx: Context\): List<Script>/.test(us) && /\.sortedBy \{ it\.name \}/.test(us),
  "list 的顺序稳定（按文件名），注入顺序可复现");
ok(/var pendingDelete by remember \{ mutableStateOf<String\?>\(null\) \}/.test(screen) &&
  /AlertDialog\(/.test(screen) && /Userscripts\.remove\(context, deleting\)/.test(screen),
  "删除有确认（脚本是用户的文本，误删没有撤销）");
// 总开关的 UI 从用户脚本页搬到了设置（用户要求删掉脚本页那张卡），所以写调用现在在
  // FunctionSettingsScreen.kt；用户脚本页仍然必须把逐条开关接到引擎，设置页必须同时读与写总开关。
  {
    // 总开关连逻辑一起删掉之后：只剩「脚本页逐条开关接引擎」这一件事要钉。
  {
    const lib = require("fs").readFileSync(
      "app/src/main/java/me/bmax/apatch/dsh/Userscripts.kt", "utf8");
    ok(/Userscripts\.setEnabled\(context, s\.id, want\)/.test(screen) &&
      !/masterEnabled/.test(lib) && !/KEY_USERSCRIPTS_ON/.test(lib),
      "逐条开关（脚本页）接引擎；总开关逻辑已从引擎删除");
  }
  }
// 设计已换：卡片底部「粘贴脚本 / 从文件选」两个按钮撤掉（粘贴那条路整条没了），改成与脚本
// 市场同一套 —— 本地 `.user.js` 走 install(context, text)，链接走新的 installFromUrl(...)。
ok(/Userscripts\.install\(context, text\)/.test(screen) &&
  /Userscripts\.installFromUrl\(/.test(screen) &&
  !/Userscripts\.install\(context, pasted\)/.test(screen),
  "本地文件走 Userscripts.install(text)、链接走 installFromUrl；「粘贴脚本」那条路已撤掉");
ok(/Intent\.ACTION_GET_CONTENT/.test(screen) &&
  /Userscripts\.read\(context, uri\)/.test(screen),
  "选文件：ACTION_GET_CONTENT + read(content://)");
// 任务 1 的删除项：卡片底部那两个按钮（粘贴 / 从文件选）连同它们的计数串整条撤掉，页面里
// 不许再引用这几个资源名 —— 留着引用就是「按钮删了、文案还挂在别处」的两套说法。
ok(!/dsh_userscripts_paste/.test(screen) && !/dsh_userscripts_pick/.test(screen) &&
  !/dsh_userscripts_count\b/.test(screen) && !/dsh_userscripts_count_filtered/.test(screen),
  "页面不再引用 dsh_userscripts_paste / _pick / _count / _count_filtered（粘贴与计数那条路已撤）");


// ── 悬浮菜单的画中画（把页面缩成悬浮小窗） ──
//
// 用户要的是「像视频画中画那样把页面缩成小窗」，没有权限时先引导。这里钉五件事：
// 清单开了能力、菜单里有按钮、进得去才进（进不去弹引导）、引导能跳到设置、小窗里不画悬浮球。
{
  const manifest = fs.readFileSync("app/src/main/AndroidManifest.xml", "utf8");
  const activity = fs.readFileSync(SRC_WEBUI, "utf8");
  const zhS = fs.readFileSync("app/src/main/res/values-zh-rCN/dsh_strings.xml", "utf8");
  const enS = fs.readFileSync("app/src/main/res/values/dsh_strings.xml", "utf8");
  const block = manifest.slice(
    manifest.indexOf(".ui.DshWebUiActivity"),
    manifest.indexOf("MTDataFilesWakeUpActivity")
  );
  ok(/android:supportsPictureInPicture="true"/.test(block) &&
    /android:resizeableActivity="true"/.test(block),
    "WebUI 的 Activity 声明了画中画（supportsPictureInPicture + resizeableActivity）");
  ok(/Icons\.Outlined\.PictureInPictureAlt/.test(activity) &&
    /contentDescription = stringResource\(R\.string\.dsh_pip_button\)/.test(activity),
    "悬浮菜单里有画中画按钮（带无障碍名）");
  ok(/private fun enterPip\(\): Boolean/.test(activity) &&
    /return runCatching \{ enterPictureInPictureMode\(pipParams\(autoEnter = true\)\) \}\.getOrDefault\(false\)/.test(activity) &&
    /switchOff = pipSupported\(\) && !pipAllowed\(\)/.test(activity) &&
    /hasSystemFeature\(PackageManager\.FEATURE_PICTURE_IN_PICTURE\)/.test(activity) &&
    /OPSTR_PICTURE_IN_PICTURE/.test(activity) && /unsafeCheckOpNoThrow/.test(activity) &&
    /checkOpNoThrow/.test(activity),
    "以系统的实际答复为准（不预判），并区分「能力不支持」与「本应用开关被关」来写引导");
  ok(/onEnterPip = \{ if \(!enterPip\(\)\) showPipGuide\.value = true \}/.test(activity) &&
    /if \(showPipGuide\.value\) \{\s*\n\s*PipGuideDialog\(/.test(activity),
    "进不去就弹引导，不硬撞");
  // 产品要求变了（用户报「缩成小窗后再点画中画按钮不放大，反而提示被占用」）：画中画里必须
  // **保留**悬浮球，否则小窗里根本没有那个按钮可用；按钮本身改成切换（在 PiP 里点 = 回全屏），
  // 而且这条切换路径不能弹引导（它 return true，不落进 showPipGuide 那条分支）。
  ok(!/if \(!inPip\.value\) \{\s*\n\s*WebUiFloatingBall\(/.test(activity) &&
    /FLAG_ACTIVITY_REORDER_TO_FRONT/.test(activity) &&
    /isInPictureInPictureMode/.test(activity),
    "画中画里保留悬浮球，且画中画按钮是切换（再点回全屏，不弹引导）");
  // 用户报「离开应用后画中画不显示」。根因（代码可证）：suppressAutoPip 一旦被某次失败的
  // 外部跳转立起却没人撤，就会一直压住 onUserLeaveHint；而且 31+ 只靠 setAutoEnterEnabled，
  // ROM 不认时完全没有兜底。现在：所有版本都从 onUserLeaveHint 兜底，且旗子是一次性消费的。
  {
    const at = activity.indexOf("override fun onUserLeaveHint()");
    const hint = at > 0 ? activity.slice(at, at + 700) : "";
    ok(hint.length > 0 && /!suppressAutoPip/.test(hint) && /suppressAutoPip = false/.test(hint) &&
      !/Build\.VERSION\.SDK_INT < Build\.VERSION_CODES\.S/.test(hint),
      "onUserLeaveHint 在所有版本兜底，且 suppressAutoPip 是一次性消费（不会卡住导致永远不自动进）");
  }
  ok(/setPictureInPictureParams[\s\S]{0,240}Log\.w\(/.test(activity),
    "onResume 那次 setPictureInPictureParams 的异常要写日志，不再静默吞掉");
  // 主开关（用户要求把画中画开关拆成主/副）：主开关默认开；副开关的读值被主开关与住，
  // 所以主开关一关，onResume 的参数与 onUserLeaveHint 两条路都停；按钮也不再画。
  ok(/fun webuiPipMain\(/.test(env) && /KEY_WEBUI_PIP = "webui_pip"/.test(env) &&
    /getBoolean\(KEY_WEBUI_PIP, true\)/.test(env),
    "画中画主开关存在且默认开");
  ok(/fun webuiPipAuto\(ctx: Context\): Boolean/.test(env) && /webuiPipMain\(ctx\)\s*&&/.test(env),
    "副开关（离开时缩成小窗）被主开关与住：主开关一关，自动进入就停");
  ok(/showPip/.test(activity) && /if \(showPip\) \{/.test(activity),
    "主开关关掉时悬浮菜单里不出现画中画按钮");
  ok(/Settings\.ACTION_APPLICATION_DETAILS_SETTINGS/.test(activity),
    "引导里的「去设置」真的能打开应用信息页");
  // 自动进入画中画（用户要的是「离开应用后小窗还在」）：31+ 交给系统（setAutoEnterEnabled +
  // setPictureInPictureParams），31 以下用 onUserLeaveHint 兜底；两条路都必须是**静默**的
  // —— 自动进入时弹引导是错的（用户只是按了 home）。
  ok(/private fun pipParams\(autoEnter: Boolean\): PictureInPictureParams/.test(activity) &&
    /builder\.setAutoEnterEnabled\(autoEnter\)/.test(activity) &&
    /Build\.VERSION\.SDK_INT >= Build\.VERSION_CODES\.S/.test(activity),
    "自动进入走 pipParams(autoEnter)：31+ 才 setAutoEnterEnabled（12 起才有这个 API）");
  ok(/setPictureInPictureParams\(pipParams\(autoEnter = DshEnv\.webuiPipAuto\(this\)\)\)/.test(activity),
    "onResume 里按用户开关把「自动进入」写进参数（这个参数有粘性：关掉时也必须写一次 false）");
  ok(/override fun onUserLeaveHint\(\)/.test(activity) &&
    /enterPip\(\)/.test(activity) &&
    !/onUserLeaveHint\(\)[\s\S]{0,400}showPipGuide\.value = true/.test(activity),
    "30 以下的兜底是 onUserLeaveHint，且自动进入不弹引导（引导只属于手动按钮那条路）");
  ok(/if \(!inPip\.value\)/.test(activity) || /isInPictureInPictureMode/.test(activity),
    "已经在画中画时不再触发一次进入");
  ok(/private var suppressAutoPip/.test(activity) &&
    (activity.match(/suppressAutoPip = true/g) || []).length >= 3 &&
    /!suppressAutoPip|&& suppressAutoPip|\|\| suppressAutoPip/.test(activity) &&
    /suppressAutoPip = false/.test(activity),
    "主动跳外部页面（邮件/外链/选文件）时立旗压住自动画中画，回来再撤");
  // 「离开时缩成小窗」开关（默认开）。默认值必须钉住：改成 false 就是静默回退成「不再自动缩」。
  ok(/fun webuiPipAuto\(ctx: Context\): Boolean/.test(env) &&
    /KEY_WEBUI_PIP_AUTO = "webui_pip_auto"/.test(env) &&
    /getBoolean\(KEY_WEBUI_PIP_AUTO, true\)/.test(env),
    "开关存在且默认开（getBoolean 的默认值是 true）");
  ok(/onUserLeaveHint\(\)[\s\S]{0,300}DshEnv\.webuiPipAuto\(this\)/.test(activity),
    "12 以下那条自动路径也读这个开关，关掉后静默不动");
  const funcSettings = fs.readFileSync("app/src/main/java/me/bmax/apatch/ui/screen/settings/FunctionSettings.kt", "utf8");
  const funcScreen = fs.readFileSync("app/src/main/java/me/bmax/apatch/ui/screen/settings/FunctionSettingsScreen.kt", "utf8");
  const registry = fs.readFileSync("app/src/main/java/me/bmax/apatch/ui/screen/settings/SettingsRegistry.kt", "utf8");
  ok(/R\.string\.dsh_webui_pip_auto\b/.test(funcSettings) && /checked = webuiPipAuto/.test(funcSettings) &&
    /putBoolean\(DshEnv\.KEY_WEBUI_PIP_AUTO, on\)/.test(funcScreen) &&
    /SettingEntry\("function_webui_pip_auto"/.test(registry),
    "开关在设置里可改（写 pref）且能被设置搜索搜到（注册表条目）");
  // 这个开关只管「自动」：手动按钮那条路（enterPip 的函数体）不能读它，否则等于把手动也关了。
  const enterPipAt = activity.indexOf("private fun enterPip(");
  const enterPipEnd = enterPipAt > 0 ? activity.indexOf("\n    }", enterPipAt) : -1;
  const enterPipBody = enterPipAt > 0 && enterPipEnd > enterPipAt ? activity.slice(enterPipAt, enterPipEnd) : "";
  ok(enterPipBody.length > 0 && !/webuiPipAuto/.test(enterPipBody) && /onEnterPip/.test(activity),
    "开关只管「自动」：手动点按钮那条路不看这个开关");
  for (const k of ["dsh_pip_button", "dsh_pip_guide_title", "dsh_pip_guide_text_supported",
                   "dsh_pip_guide_text_unsupported", "dsh_pip_guide_settings"]) {
    ok(zhS.includes('name="' + k + '"') && enS.includes('name="' + k + '"'),
      "文案 " + k + " 中英各一份");
  }
}

console.log(bad === 0 ? `\n全部通过（${n} 项断言）` : `\n${bad}/${n} 项失败`);
process.exit(bad === 0 ? 0 : 1);
