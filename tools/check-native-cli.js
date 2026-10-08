// 把两段容器 CLI 从 Kotlin 源里抠出来验证：
//   1. node --check 语法检查（这段脚本进容器后由 Node 直接跑，语法错等于功能全废）
//   2. 零个 `$`（Kotlin 原始字符串里 $ 会被当模板插值，编译期就炸）
//   3. **字符串字面量**里零个 CJK（这段不经资源系统，写中文等于把语言写死）
//      —— 注释里的中文是允许的，所以比较前先剥掉注释
// 然后用一个假的桥服务端跑一遍每条命令，验证它们真的打到正确的 method + path + 参数。
const fs = require("fs");
const path = require("path");
const http = require("http");
const { execFile } = require("child_process");
const { promisify } = require("util");
const os = require("os");

const run = promisify(execFile);
const SRC = "app/src/main/java/me/bmax/apatch/dsh/DshRuntime.kt";
const kt = fs.readFileSync(SRC, "utf8");

function extract(name) {
  const start = kt.indexOf("private val " + name + " = \"\"\"");
  if (start < 0) throw new Error(name + " 找不到");
  const from = kt.indexOf("\n", start) + 1;
  const end = kt.indexOf("\"\"\".trimIndent()", from);
  if (end < 0) throw new Error(name + " 结尾找不到");
  const raw = kt.slice(from, end);
  const lines = raw.split("\n");
  const indents = lines.filter((l) => l.trim()).map((l) => l.match(/^ */)[0].length);
  const min = Math.min(...indents);
  return lines.map((l) => l.slice(min)).join("\n").replace(/\s+$/, "");
}

/** 去掉 // 行注释与 /* 块注释，只留会被 Node 当代码跑的部分。 */
function stripComments(src) {
  return src
    .split("\n")
    .map((l) => l.replace(/\/\/.*$/, ""))
    .join("\n")
    .replace(/\/\*[\s\S]*?\*\//g, "");
}

let fail = 0;
function ok(cond, msg) {
  console.log((cond ? "  ✓ " : "  ✗ ") + msg);
  if (!cond) fail++;
}

const script = extract("NATIVE_CLI_SCRIPT");
const fsScript = extract("FS_BRIDGE_CLI_SCRIPT");

console.log("── 不变量 ──");
for (const [name, body] of [["dsh-native", script], ["dsh-fs", fsScript]]) {
  // Kotlin 的 `${...}` 是**有意的**插值（会渲染成数字，最终脚本里没有 $），先摘掉再数；
  // 剩下的 $ 才是会被误当成模板插值的那种，必须为零。
  const dollars = (body.replace(/\$\{[^}]*\}/g, "").match(/\$/g) || []).length;
  ok(dollars === 0, `${name}: 零个裸 $（实际 ${dollars}，已排除 Kotlin 插值）`);
  // 注释里的中文无所谓，代码里的不行
  const code = stripComments(body);
  const cjk = code.match(/[\u4e00-\u9fff]/g) || [];
  ok(cjk.length === 0,
    `${name}: 代码里零个 CJK（实际 ${cjk.length}${cjk.length ? " → " + cjk.slice(0, 10).join("") : ""}）`);
}

// ── 每条能力调用都必须带 reason ──
//
// host 对 /native/** 一律强制 reason（只有 /native/capabilities 与 /native/elevate 在 reason
// 检查之前就被接走），而 reason 唯一的来源就是 `+ q({...})`。**漏套 q() 的那条命令在真机上
// 100% 返回 reason_required**，看起来像"这个功能没实现"。
//
// 这件事真发生过，而且是一次漏 12 条：`display status`、`display stop`、`a11y screenshot`、
// `device`、`clip get`、`tts voices`、`phone`、`sensors list`、`network`、`volume`、
// `settings`、`install`。之所以长期没人发现，是因为下面那个假服务端从不检查 reason，
// 于是「命令没带 reason」与「命令不带 reason 也能通过」在门禁里长得一模一样。
{
  const reqLines = script.split("\n")
    .map((l, i) => ({ l, n: i + 1 }))
    .filter(({ l }) => /\breq\('/.test(l));
  ok(reqLines.length > 30, `解析到 ${reqLines.length} 处 req( 调用（解析失效会让本条形同虚设）`);
  const noReason = reqLines.filter(({ l }) => !/q\(/.test(l) && !/'\/native\/capabilities'/.test(l));
  ok(noReason.length === 0,
    "每条能力调用都带了 q()（即 reason）" +
    (noReason.length ? " → 漏 " + noReason.map(({ n, l }) => n + ":" + l.trim().slice(0, 46)).join(" / ") : ""));
  // 反向：豁免只能是 capabilities —— 否则这条断言会被一句 "反正不用 reason" 慢慢掏空
  const exempt = reqLines.filter(({ l }) => !/q\(/.test(l));
  ok(exempt.every(({ l }) => /'\/native\/capabilities'/.test(l)),
    "不带 q() 的只有 /native/capabilities 这一个豁免端点");
}

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "dshcli-"));
for (const [name, body] of [["dsh-native", script], ["dsh-fs", fsScript]]) {
  const f = path.join(tmp, name + ".js");
  fs.writeFileSync(f, body);
  try {
    require("child_process").execFileSync(process.execPath, ["--check", f], { stdio: "pipe" });
    ok(true, `${name}: node --check 通过`);
  } catch (e) {
    ok(false, `${name}: node --check 失败 → ` + String(e.stderr || e).slice(0, 300));
  }
}

const EXPECT = [
  // 新增命令
  [["camera", "photo", "--facing", "front", "--max", "1280"], "POST", "/native/camera/photo", { facing: "front", max: "1280" }],
  [["tts", "say", "读一句", "--lang", "zh-CN", "--rate", "1.2"], "POST", "/native/tts/speak", { text: "读一句", lang: "zh-CN", rate: "1.2" }],
  [["tts", "file", "存成文件"], "POST", "/native/tts/file", { text: "存成文件" }],
  [["tts", "voices"], "GET", "/native/tts/voices", {}],
  [["calendar", "list", "--days", "3"], "GET", "/native/calendar/list", { days: "3" }],
  [["calendar", "add", "站会", "--start", "1737000000000", "--minutes", "30"], "POST", "/native/calendar/create", { title: "站会", start: "1737000000000", minutes: "30" }],
  [["contacts", "list", "--q", "张"], "GET", "/native/contacts/list", { q: "张" }],
  [["location", "--wait", "5000"], "GET", "/native/location", { wait: "5000" }],
  [["phone"], "GET", "/native/phone/info", {}],
  [["sensors", "list"], "GET", "/native/sensors/list", {}],
  [["sensors", "read", "light"], "GET", "/native/sensors/read", { id: "light" }],
  [["network"], "GET", "/native/network", {}],
  [["volume"], "GET", "/native/volume", {}],
  [["volume", "set", "40", "--stream", "alarm"], "POST", "/native/volume", { percent: "40", stream: "alarm" }],
  [["ringer", "vibrate"], "POST", "/native/ringer", { mode: "vibrate" }],
  [["settings"], "GET", "/native/settings", {}],
  [["settings", "brightness", "60", "--auto", "0"], "POST", "/native/settings/brightness", { percent: "60", auto: "0" }],
  [["settings", "timeout", "60000"], "POST", "/native/settings/timeout", { ms: "60000" }],
  [["settings", "rotation", "1"], "POST", "/native/settings/rotation", { on: "1" }],
  [["install"], "GET", "/native/install", {}],
  // 回归：老命令不能被改坏
  [["toast", "hi"], "POST", "/native/toast", { text: "hi" }],
  [["notify", "T", "B", "--id", "7"], "POST", "/native/notify", { title: "T", body: "B", id: "7" }],
  [["vibrate", "--ms", "500"], "POST", "/native/vibrate", { ms: "500" }],
  [["clip", "set", "x", "--label", "L"], "POST", "/native/clipboard", { text: "x", label: "L" }],
  [["clip", "get"], "GET", "/native/clipboard", {}],
  [["share", "hello", "--title", "t"], "POST", "/native/share", { text: "hello", title: "t" }],
  [["open", "https://example.com"], "POST", "/native/open", { url: "https://example.com" }],
  [["device"], "GET", "/native/device", {}],
  [["media", "list", "--type", "audio", "--limit", "5"], "GET", "/native/media/list", { type: "audio", limit: "5" }],
  [["media", "get", "42", "--type", "video"], "GET", "/native/media/read", { type: "video", id: "42" }],
  [["mic", "record", "--ms", "3000"], "POST", "/native/mic/record", { ms: "3000" }],
  [["mic", "start"], "POST", "/native/mic/start", {}],
  [["mic", "stop", "--id", "abc123"], "POST", "/native/mic/stop", { id: "abc123" }],
  [["caps"], "GET", "/native/capabilities", {}],
  // a11y 与 display：权限最大、也最容易漏套 q() 的两组，之前一条用例都没有
  // （`display status` / `display stop` 就是这么漏掉的）。
  [["a11y", "tree", "--depth", "3", "--max", "40"], "GET", "/native/a11y/tree", { depth: "3", max: "40" }],
  [["a11y", "click", "OK", "--class", "android.widget.Button", "--index", "1"], "POST", "/native/a11y/click", { target: "OK", class: "android.widget.Button", index: "1" }],
  [["a11y", "tap", "10", "20", "--ms", "80"], "POST", "/native/a11y/tap", { x: "10", y: "20", ms: "80" }],
  [["a11y", "swipe", "1", "2", "3", "4", "--ms", "200"], "POST", "/native/a11y/swipe", { x1: "1", y1: "2", x2: "3", y2: "4", ms: "200" }],
  [["a11y", "text", "hi", "--target", "Note"], "POST", "/native/a11y/text", { text: "hi", target: "Note" }],
  // WebView 的编辑框既没有 text 也没有 view id，class 是唯一抓手（见 DshA11y.setText）
  [["a11y", "text", "hi", "--class", "EditText"], "POST", "/native/a11y/text", { text: "hi", class: "EditText" }],
  // 页面上两个输入框时"写第二个"：--index 要和 click 一样透到底（只声明不传 = 又只能写焦点那个）
  [["a11y", "text", "hi", "--class", "EditText", "--index", "1"], "POST", "/native/a11y/text", { text: "hi", class: "EditText", index: "1" }],
  // 空串是**合法输入 = 清空这个框**：判据用 a[1] 真值会让 'a11y text ""' 回一句 usage，
  // 于是清空只能靠找到并点中那个清除按钮（它往往连节点名都没有）
  [["a11y", "text", ""], "POST", "/native/a11y/text", { text: "" }],
  [["a11y", "global", "back"], "POST", "/native/a11y/global", { action: "back" }],
  [["a11y", "screenshot"], "GET", "/native/a11y/screenshot", {}],
  [["display", "status"], "GET", "/native/display/status", {}],
  [["display", "session", "--width", "1080", "--height", "1920", "--dpi", "420", "--bitrate", "4000000"], "POST", "/native/display/session", { width: "1080", height: "1920", dpi: "420", bitrate: "4000000" }],
  [["display", "shot", "--display", "13"], "POST", "/native/display/screenshot", { display: "13" }],
  // 坐标 0 是合法值，用 0 才能压住 'a[1] && a[2]' 那类判空写法（display tap 0 0）
  [["display", "tap", "0", "0", "--display", "13"], "POST", "/native/display/tap", { x: "0", y: "0", display: "13" }],
  [["display", "swipe", "1", "2", "3", "4", "--ms", "150", "--display", "13"], "POST", "/native/display/swipe", { x1: "1", y1: "2", x2: "3", y2: "4", duration: "150", display: "13" }],
  [["display", "key", "home", "--display", "13"], "POST", "/native/display/key", { key: "home", display: "13" }],
  [["display", "launch", "com.miui.calculator", "--display", "13"], "POST", "/native/display/launch", { package: "com.miui.calculator", display: "13" }],
  [["display", "stop"], "POST", "/native/display/stop", {}],
];

// 每条能力调用都替它补上 --reason，好让假服务端能像真 host 一样强制它。
// 不给 `caps` 补：那一条是 host 唯一免 reason 的端点。
const REASON = "gate-reason";
const withReason = (argv) => (argv[0] === "caps" ? argv : [...argv, "--reason", REASON]);

const seen = [];
const server = http.createServer((req, res) => {
  seen.push(req.method + " " + req.url);
  const u = new URL("http://x" + req.url);
  // 与真 host 同一条契约：/native/capabilities 之外，缺 reason 一律 400 reason_required
  // （DshNativeBridge 在路由分发前就拦了）。不在这里强制的话，脚本漏套 q() 的命令会
  // 静默"通过"，这正是 12 条命令同时失效却没人发现的原因。
  if (u.pathname !== "/native/capabilities" && !u.searchParams.get("reason")) {
    res.writeHead(400, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ ok: false, code: "reason_required" }));
    return;
  }
  res.writeHead(200, { "Content-Type": "application/json" });
  res.end(JSON.stringify({ ok: true }));
});

(async () => {
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  const port = server.address().port;
  const cfgDir = path.join(tmp, "root", ".dsh");
  fs.mkdirSync(cfgDir, { recursive: true });
  const cfg = path.join(cfgDir, "fs-bridge.json");
  fs.writeFileSync(cfg, JSON.stringify({ port, token: "T" }));
  // 脚本里 CFG 是容器内绝对路径，测试时改指到 tmp
  const runner = path.join(tmp, "run.js");
  fs.writeFileSync(runner, script.replace("'/root/.dsh/fs-bridge.json'", JSON.stringify(cfg)));

  console.log("\n── 每条命令打到的端点 ──");
  for (const [argv, method, urlPath, params] of EXPECT) {
    seen.length = 0;
    try {
      await run(process.execPath, [runner, ...withReason(argv)]);
    } catch (e) {
      ok(false, argv.join(" ") + " → 退出码非 0: " + String(e.stderr || "").slice(0, 160));
      continue;
    }
    if (seen.length !== 1) {
      ok(false, argv.join(" ") + ` → 期望 1 次请求，实际 ${seen.length}`);
      continue;
    }
    const [gotMethod, gotUrl] = seen[0].split(" ");
    const qs = new URL("http://x" + gotUrl).searchParams;
    const pathOk = gotMethod === method && gotUrl.split("?")[0] === urlPath;
    const bad = [];
    // reason 必须真的落在 query 里：漏套 q() 的命令会在这里当场现形
    if (argv[0] !== "caps" && qs.get("reason") !== REASON) bad.push("reason 丢失（漏套 q()）");
    for (const [k, v] of Object.entries(params)) {
      if (qs.get(k) !== v) bad.push(`${k}=${qs.get(k)}≠${v}`);
    }
    ok(pathOk && bad.length === 0,
      `${argv.join(" ").padEnd(46)} → ${gotMethod} ${gotUrl.split("?")[0]}` +
      (pathOk && bad.length === 0 ? "" : `  【${!pathOk ? "路径不符: " + seen[0] : bad.join(",")}】`));
  }

  // 无参数：打 usage、不发请求、非零退出
  seen.length = 0;
  let usageOk = false;
  try {
    await run(process.execPath, [runner]);
  } catch (e) {
    usageOk = String(e.stderr || "").includes("usage: dsh-native") && seen.length === 0;
  }
  ok(usageOk, "无参数 → 打 usage、不发请求、退出码非 0");

  // 未知命令同样不该悄悄成功。**带上 --reason**：不带的话命中的是上一条「缺 reason」的分支
  // （它也打 usage），这条断言就分不清"未知命令被拒"与"根本没走到分发"。
  seen.length = 0;
  let unknownOk = false;
  try {
    await run(process.execPath, [runner, "nosuchcmd", "--reason", REASON]);
  } catch (e) {
    unknownOk = String(e.stderr || "").includes("usage: dsh-native") && seen.length === 0;
  }
  ok(unknownOk, "未知命令（带 reason）→ 打 usage、不发请求、退出码非 0");

  // USAGE 里列出的顶层命令必须都真的被分发（写了帮助却没实现是最气人的那种 bug）
  const usageBlock = script.slice(
    script.indexOf("const USAGE = ["),
    script.indexOf("].join(") + 1
  );
  // USAGE 里每条命令行的形状是：<缩进>'<两空格><命令名> ...',
  // 所以要认的是「引号 + 恰好两个空格 + 命令名」，不是任意两空格缩进 ——
  // 后者会把脚本里的 const / if / for 全当成命令。
  // 命令名里可以有数字（a11y、tts 之外还有 notify-full-screen 这种连字符组合），
  // 所以是 [a-z][a-z0-9-]* 而不是 [a-z-]+：后者会把 `a11y` 截成 `a`，于是断言拿着一个
  // 根本不存在的命令去比对，报出"缺 a"这种没人看得懂的失败。
  const usageCmds = [...usageBlock.matchAll(/^\s*' {2}([a-z][a-z0-9-]*)/gm)].map((m) => m[1]);
  const dispatched = new Set([...script.matchAll(/cmd === '([a-z][a-z0-9-]*)'/g)].map((m) => m[1]));
  const missing = [...new Set(usageCmds)].filter((c) => !dispatched.has(c));
  ok(missing.length === 0, "USAGE 列出的命令都有分发" + (missing.length ? " → 缺 " + missing.join(",") : ""));

  // 反过来：分发了却没写进 USAGE 的命令等于隐藏功能
  const undocumented = [...dispatched].filter((c) => !usageCmds.includes(c));
  ok(undocumented.length === 0,
    "分发的命令都写进了 USAGE" + (undocumented.length ? " → 漏写 " + undocumented.join(",") : ""));

  // 两份 README 都是用户会照着敲的地方（README.md 链中文那份，README.en.md 链英文那份）。
  // 只查一份的话另一份会静默落后 —— display 这一整块就曾经两份都没写。
  for (const doc of ["docs/host-bridges.md", "docs/host-bridges.en.md"]) {
    const readme = fs.readFileSync(doc, "utf8");
    const inReadme = new Set([...readme.matchAll(/^dsh-native ([a-z][a-z0-9-]*)/gm)].map((m) => m[1]));
    const rdMissing = [...dispatched].filter((c) => !inReadme.has(c));
    ok(rdMissing.length === 0,
      `${doc}: 每条命令都写了` + (rdMissing.length ? " → 漏写 " + rdMissing.join(",") : ""));
    const rdExtra = [...inReadme].filter((c) => !dispatched.has(c));
    ok(rdExtra.length === 0,
      `${doc}: 没写不存在的命令` + (rdExtra.length ? " → " + rdExtra.join(",") : ""));
  }

  // ── 门禁自己也不能是孤儿 ──
  //
  // 这个文件本身曾经就是孤儿：躺在 tools/ 里，两个工作流谁都没调它，于是它悄悄腐烂到
  // 二十多条断言常年失败，而「12 条 dsh-native 命令漏了 reason」这种真问题一条都没拦住。
  // 不被执行的门禁比没有门禁更糟 —— 它让人以为有人在守。
  //
  // 局限：如果哪天有人把**本文件**从两个工作流里摘掉，这条断言也没机会跑了。它挡的是
  // 「新加门禁忘了接线」这类更常见的疏漏。
  {
    const wfs = [".github/workflows/build.yml", ".github/workflows/beta.yml"];
    const all = fs.readdirSync("tools").filter((f) => /^check-.*\.js$/.test(f));
    ok(all.length > 20, `tools/ 下有 ${all.length} 个 check 脚本（解析失效会让本条形同虚设）`);
    for (const wf of wfs) {
      const text = fs.readFileSync(wf, "utf8");
      const missing = all.filter((f) => !text.includes(`tools/${f}`));
      ok(missing.length === 0,
        `${wf} 调用了每个 check 脚本` + (missing.length ? " → 孤儿: " + missing.join(",") : ""));
    }
  }

  // ── 权限桥疑难解答：agent 的一条命令 ──
  //
  // 报错里会出现 no_channel / root_unverified / shizuku_unauthorized / adb_unpaired 这类状态词，
  // agent 需要一份「照做就行」的清单。清单由 App 按**应用内语言**写进容器（CLI 的字符串字面量
  // 不许有 CJK，所以不能写死在脚本里），这里钉「命令—文件—三个 reason—提示词—资源」五处对齐：
  // 三个 reason 常量必须都被插进文案，否则以后加了第四种状态就等于悄悄漏了一段。
  {
    ok(/cmd === 'troubleshoot'/.test(script) && /readFileSync\(doc, 'utf8'\)/.test(script),
      "dsh-native troubleshoot 读容器里的清单文件，而不是把内容写死在脚本里");
    ok(/const doc = '\/root\/\.dsh\/dsh-native-troubleshoot\.txt'/.test(script),
      "清单路径固定在容器内 /root/.dsh/dsh-native-troubleshoot.txt");
    ok(/TROUBLESHOOT_DOC_NAME = "dsh-native-troubleshoot\.txt"/.test(kt) &&
      /File\(DshEnv\.dshHome\(appContext\), TROUBLESHOOT_DOC_NAME\)/.test(kt) &&
      /doc\.writeText\(text, StandardCharsets\.UTF_8\)/.test(kt),
      "App 侧把清单写进 rootfs/root/.dsh（DshEnv.dshHome），与 CLI 的路径一致");
    ok(/private fun nativeTroubleshootDoc\(\): String = buildString/.test(kt) &&
      /appContext\.appString\(R\.string\.dsh_troubleshoot_/.test(kt),
      "清单用资源串组装（跟着应用内语言走，不写死一种语言）");
    for (const reason of ["REASON_ROOT_UNVERIFIED", "REASON_SHIZUKU_UNAUTHORIZED", "REASON_ADB_UNPAIRED"]) {
      ok(kt.includes("PrivilegedShell." + reason + "))"),
        reason + " 的值被插进清单（agent 才能把报错词对到那一段）");
    }
    const prompt = fs.readFileSync("app/src/main/assets/dsh-folk-host.mjs", "utf8");
    ok(/dsh-native troubleshoot/.test(prompt) && /which screen to open, in what order/.test(prompt),
      "提示词在「这些状态词怎么修」的地方指到这条命令");
    for (const f of ["app/src/main/res/values/dsh_strings.xml", "app/src/main/res/values-zh-rCN/dsh_strings.xml"]) {
      const res = fs.readFileSync(f, "utf8");
      ok(["intro", "no_channel", "root", "shizuku", "adb", "cap", "perm", "rom"]
        .every((k) => res.includes('name="dsh_troubleshoot_' + k + '"')),
        f + "：八个段落都在");
    }
  }


  server.close();
  fs.rmSync(tmp, { recursive: true, force: true });
  console.log(fail === 0 ? "\n全部通过" : `\n${fail} 项失败`);
  process.exit(fail === 0 ? 0 : 1);
})();
