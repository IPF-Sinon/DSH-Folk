#!/usr/bin/env node
/**
 * 会话式录音（`POST /native/mic/start` → `POST /native/mic/stop?id=`）的门禁。
 *
 * ## 为什么多了这一对端点
 *
 * 原来只有 `mic/record --ms N`：录满 N 毫秒才回。按键说话要先开始、说完了停，时长由
 * **说话的人**定；按时长切段会在段间留一截静默，等它自然结束又太迟钝。补的这对端点
 * 走 `MediaRecorder.stop()` 收尾（把 MP4 的 moov 写下去），所以提前停不会得到坏文件。
 *
 * 形状容易写歪的地方，因此逐条钉住：
 *
 * - **一条收尾路径**：start/stop 与 record 必须共用同一个 `finishMic`（前台复查、
 *   空文件删除、落盘、trimStage 只写一遍）；两份实现必然分叉。
 * - **一个状态位**：还是那个 `recording` AtomicBoolean —— 第二把锁 = 「busy 判定」
 *   分叉。
 * - **看门狗**：`MAX_RECORD_MS` 到了自己停，结果留给「stop 来晚一步」的客户端；
 *   但结果只留到下一次 start。
 * - **id ownership**：只有**当前**会话的 id 能停它；别人的 id（或一个早就结束的）
 *   不该停掉正在录的那一次。
 */
const fs = require("fs");

const ROOT = __dirname + "/..";
const read = (rel) => fs.readFileSync(require("path").join(ROOT, rel), "utf8");
const bridge = read("app/src/main/java/me/bmax/apatch/dsh/DshNativeBridge.kt");
const cli = read("app/src/main/java/me/bmax/apatch/dsh/DshRuntime.kt");
const host = read("app/src/main/assets/dsh-folk-host.mjs");
const cliGate = read("tools/check-native-cli.js");
const zhStrings = read("app/src/main/res/values-zh-rCN/dsh_strings.xml");
const enStrings = read("app/src/main/res/values/dsh_strings.xml");

let n = 0;
let bad = 0;
function ok(cond, label) {
  n++;
  console.log("  " + (cond ? "✓" : "✗") + " " + label);
  if (!cond) bad++;
}
/** 取一个顶层 private fun 的正文（到下一个同级 `private fun` / `internal fun` / 类注释为止）。 */
function body(name) {
  const at = bridge.indexOf("private fun " + name + "(");
  if (at < 0) return "";
  const rest = bridge.slice(at + 1);
  const next = rest.search(/\n    (private|internal) (fun|class|val|var|const) /);
  return next < 0 ? rest : rest.slice(0, next);
}

console.log("\n── 路由 / 能力 / 写档位（check-native-caps 会再做一次双向断言）──");
{
  ok(/method == "POST" && path == "\/native\/mic\/start" -> micStart\(ctx, params\)/.test(bridge),
    "路由：POST /native/mic/start");
  ok(/method == "POST" && path == "\/native\/mic\/stop" -> micStop\(ctx, params\)/.test(bridge),
    "路由：POST /native/mic/stop");
  ok(/"\/native\/mic\/record", "\/native\/mic\/start", "\/native\/mic\/stop" -> Cap\.MIC/.test(bridge),
    "三条都归 Cap.MIC（一次授权覆盖整段会话）");
  ok(/path == "\/native\/mic\/start" \|\| path == "\/native\/mic\/stop" -> true/.test(bridge),
    "start/stop 也算**写**请求（要 reason、要授权，与 record 同级）");
}

console.log("\n── 一条收尾路径、一个状态位 ──");
{
  ok((bridge.match(/MediaRecorder\(ctx\)|MediaRecorder\(\)/g) || []).length === 2,
    "MediaRecorder 只在 startMic 里构造（同一处 SDK 分支）");
  const calls = bridge.match(/finishMic\(/g) || [];
  ok(calls.length === 3, `finishMic 只有一处定义 + 两个调用（record 一条、会话一条）；实际 ${calls.length}`);
  ok(/return finishMic\(ctx, recorder, out, ms, id = null\)/.test(body("doRecord")),
    "record 与 stop 共用同一条收尾（前台复查/删除/trim 只有一份）");
  ok(/finishMic\([\s\S]{0,240}session\.startedAt/.test(body("stopMicSessionLocked")),
    "会话的 ms 是**实测**时长（startedAt → now）");
  ok((bridge.match(/AtomicBoolean\(false\)/g) || []).length === 1,
    "只有一个录音状态位（第二把锁会让 busy 判定分叉）");
  ok(/recording\.compareAndSet\(false, true\)/.test(body("micStart")),
    "start 用 compareAndSet 抢位（并发两次 start 只有一次成功）");
  ok(/if \(recorder == null\) \{[\s\S]{0,120}?recording\.set\(false\)/.test(body("micStart")),
    "启动失败要把位还回去（否则之后再也 start 不了）");
  ok(/recording\.set\(false\)/.test(body("stopMicSessionLocked")),
    "停会话就放位");
}

console.log("\n── 立刻返回 + 看门狗 ──");
{
  const start = body("micStart");
  ok(/\.put\("id", session\.id\)/.test(start) && /\.put\("maxMs", MAX_RECORD_MS\)/.test(start),
    "start 回 id 与 maxMs（客户端要拿 id 才能 stop）");
  ok(!/Thread\.sleep\(ms\)/.test(start), "start 不等录音时长（这正是它存在的理由）");
  ok(/Thread \{[\s\S]{0,200}?Thread\.sleep\(MAX_RECORD_MS\)/.test(start),
    "看门狗在 MAX_RECORD_MS 后自己收尾");
  ok(/stopMicSessionLocked\(ctx, current\)/.test(start) && /current\.id == session\.id/.test(start),
    "看门狗也走同一条收尾，且只停**自己**那一次（不会误停后开的一次）");
  ok(/micLast = null/.test(start), "新一次 start 清掉上一次留下的结果（只留一轮）");
}

console.log("\n── stop 的三种回答 ──");
{
  const stop = body("micStop");
  ok(/text\(params\["id"\]\)/.test(stop) && /dsh_native_err_missing_param, "id"/.test(stop),
    "缺 id → 400（参数名就是 CLI 的 --id）");
  ok(/return if \(session\.id == id\) stopMicSessionLocked\(ctx, session\)/.test(stop),
    "id 对得上 → 优雅停止");
  ok(/bad_session/.test(stop), "id 对不上（另有会话在录）→ 409 bad_session");
  ok(/micLast\?\.takeIf \{ it\.first == id \}/.test(stop) && /late\.second\.toString\(\)/.test(stop),
    "来晚一步（看门狗已收尾）→ 把**那一次**的结果再给一遍（录到的不该白录）");
  ok(/else 409 to err\(str\(ctx, R\.string\.dsh_native_err_mic_no_session\), "no_session"\)/.test(stop),
    "既没在录、也没有这个 id 的结果 → 409 no_session");
}

console.log("\n── CLI 与提示词 ──");
{
  ok(/a\[0\] === 'start'/.test(cli) && /req\('POST', '\/native\/mic\/start'/.test(cli),
    "CLI: mic start → POST /native/mic/start");
  ok(/a\[0\] === 'stop' && opt\.id/.test(cli) &&
    /req\('POST', '\/native\/mic\/stop' \+ q\(\{ id: opt\.id \}\)\)/.test(cli),
    "CLI: mic stop --id → POST /native/mic/stop?id=");
  ok(/'  mic start/.test(cli) && /'  mic stop --id ID/.test(cli), "USAGE 里两条都有");
  ok(/\[\["mic", "start"\], "POST", "\/native\/mic\/start", \{\}\]/.test(cliGate) &&
    /\[\["mic", "stop", "--id", "abc123"\], "POST", "\/native\/mic\/stop", \{ id: "abc123" \}\]/.test(cliGate),
    "check-native-cli 的端点表里也有（漏了等于没人验证过）");
  ok(/dsh-native mic start/.test(host) && /dsh-native mic stop --id/.test(host),
    "host 提示词里写了（agent 不知道就等于没做）");
  ok(/mic \(record\|start\|stop\)/.test(host), "host 提示词的 write 判定把两条算进写命令");
}

console.log("\n── 文案与文档 ──");
{
  ok(/name="dsh_native_err_mic_no_session"/.test(enStrings) &&
    /name="dsh_native_err_mic_no_session"/.test(zhStrings),
    "「没有会话」的中英文案都在");
  for (const doc of ["docs/host-bridges.md", "docs/host-bridges.en.md"]) {
    const t = read(doc);
    ok(/dsh-native mic start/.test(t) && /mic stop --id ID/.test(t),
      `${doc} 写了 start/stop 的用法`);
  }
}

console.log(bad === 0 ? `\n全部通过（${n} 项断言）` : `\n${bad}/${n} 项失败`);
process.exit(bad === 0 ? 0 : 1);
