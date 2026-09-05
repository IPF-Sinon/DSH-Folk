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
  const dollars = (body.match(/\$/g) || []).length;
  ok(dollars === 0, `${name}: 零个 $（实际 ${dollars}）`);
  // 注释里的中文无所谓，代码里的不行
  const code = stripComments(body);
  const cjk = code.match(/[\u4e00-\u9fff]/g) || [];
  ok(cjk.length === 0,
    `${name}: 代码里零个 CJK（实际 ${cjk.length}${cjk.length ? " → " + cjk.slice(0, 10).join("") : ""}）`);
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
  [["caps"], "GET", "/native/capabilities", {}],
];

const seen = [];
const server = http.createServer((req, res) => {
  seen.push(req.method + " " + req.url);
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
      await run(process.execPath, [runner, ...argv]);
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

  // 未知命令同样不该悄悄成功
  seen.length = 0;
  let unknownOk = false;
  try {
    await run(process.execPath, [runner, "nosuchcmd"]);
  } catch (e) {
    unknownOk = String(e.stderr || "").includes("usage: dsh-native") && seen.length === 0;
  }
  ok(unknownOk, "未知命令 → 打 usage、不发请求、退出码非 0");

  // USAGE 里列出的顶层命令必须都真的被分发（写了帮助却没实现是最气人的那种 bug）
  const usageBlock = script.slice(
    script.indexOf("const USAGE = ["),
    script.indexOf("].join(") + 1
  );
  // USAGE 里每条命令行的形状是：<缩进>'<两空格><命令名> ...',
  // 所以要认的是「引号 + 恰好两个空格 + 命令名」，不是任意两空格缩进 ——
  // 后者会把脚本里的 const / if / for 全当成命令。
  const usageCmds = [...usageBlock.matchAll(/^\s*' {2}([a-z-]+)/gm)].map((m) => m[1]);
  const dispatched = new Set([...script.matchAll(/cmd === '([a-z-]+)'/g)].map((m) => m[1]));
  const missing = [...new Set(usageCmds)].filter((c) => !dispatched.has(c));
  ok(missing.length === 0, "USAGE 列出的命令都有分发" + (missing.length ? " → 缺 " + missing.join(",") : ""));

  // 反过来：分发了却没写进 USAGE 的命令等于隐藏功能
  const undocumented = [...dispatched].filter((c) => !usageCmds.includes(c));
  ok(undocumented.length === 0,
    "分发的命令都写进了 USAGE" + (undocumented.length ? " → 漏写 " + undocumented.join(",") : ""));

  // README 同样是用户会照着敲的地方
  const readme = fs.readFileSync("README.md", "utf8");
  const inReadme = new Set([...readme.matchAll(/^dsh-native ([a-z-]+)/gm)].map((m) => m[1]));
  const rdMissing = [...dispatched].filter((c) => !inReadme.has(c));
  ok(rdMissing.length === 0,
    "每条命令 README 都写了" + (rdMissing.length ? " → 漏写 " + rdMissing.join(",") : ""));
  const rdExtra = [...inReadme].filter((c) => !dispatched.has(c));
  ok(rdExtra.length === 0,
    "README 没写不存在的命令" + (rdExtra.length ? " → " + rdExtra.join(",") : ""));

  server.close();
  fs.rmSync(tmp, { recursive: true, force: true });
  console.log(fail === 0 ? "\n全部通过" : `\n${fail} 项失败`);
  process.exit(fail === 0 ? 0 : 1);
})();
