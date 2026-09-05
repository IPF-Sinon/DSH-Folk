// 自启动三条路径的一致性检查。
//
// 这些不变量编译器抓不到，而每一条漏掉的后果都只在真机重启之后才显形：
//   1. 三条路径都必须汇聚到 DshAutostart.trigger（谁绕过它，切走模式后就还会自启）
//   2. 每条路径调 trigger 时传的 source 必须是它自己那一个 Mode
//   3. 每个 Mode 都有标题 + 说明资源串，界面上四个单选项一个不少
//   4. root 脚本里的组件名/action 占位符与 Kotlin 侧真实值对得上
//   5. 脚本模板是合法 sh、不含 CJK（它跑在 root 的 sh 里，locale 不确定）
//   6. 无障碍服务的配置没有申请读取屏幕内容
//   7. manifest 里三处注册齐全且属性正确
const fs = require("fs");

const A = "app/src/main/java/me/bmax/apatch/dsh/DshAutostart.kt";
const SVC = "app/src/main/java/me/bmax/apatch/dsh/DshAutostartService.kt";
const RCV = "app/src/main/java/me/bmax/apatch/receiver/BootCompletedReceiver.kt";
const HS = "app/src/main/java/me/bmax/apatch/dsh/HarnessService.kt";
const SH = "app/src/main/assets/dsh-folk-autostart.sh";
const A11Y = "app/src/main/res/xml/dsh_autostart_a11y.xml";
const MANIFEST = "app/src/main/AndroidManifest.xml";
const STRINGS = "app/src/main/res/values/dsh_strings.xml";

const src = Object.fromEntries(
  Object.entries({ a: A, svc: SVC, rcv: RCV, hs: HS, sh: SH, a11y: A11Y, manifest: MANIFEST, strings: STRINGS })
    .map(([k, p]) => [k, fs.readFileSync(p, "utf8")])
);
/** 剥 Kotlin 注释：「不该出现 X」必须扫这个，否则会命中解释性 KDoc。 */
const code = (s) => s.replace(/\/\/.*$/gm, "").replace(/\/\*[\s\S]*?\*\//g, "");
/** 剥 XML 注释：同理 —— 注释里写「刻意没有 canRetrieveWindowContent」不该被当成有。 */
const xml = (s) => s.replace(/<!--[\s\S]*?-->/g, "");

let fail = 0;
function ok(cond, msg) {
  console.log((cond ? "  ✓ " : "  ✗ ") + msg);
  if (!cond) fail++;
}

// ── Mode 列表 ──
const modeBlock = src.a.slice(src.a.indexOf("enum class Mode("), src.a.indexOf("companion object", src.a.indexOf("enum class Mode(")));
const modes = [...modeBlock.matchAll(/^\s{8}([A-Z_]+)\("([a-z0-9]+)"\)/gm)].map((m) => ({ name: m[1], id: m[2] }));
console.log(`── Mode 共 ${modes.length} 项：${modes.map((m) => m.id).join(", ")} ──\n`);
ok(modes.length === 4, "解析到 4 种自启动方式");

// ── 1 & 2. 每条路径都走 trigger，且 source 正确 ──
console.log("── 三条路径 ──");
const PATHS = [
  ["广播", src.rcv, "Mode.RECEIVER"],
  ["无障碍", src.svc, "Mode.ACCESSIBILITY"],
];
for (const [label, body, want] of PATHS) {
  const calls = [...code(body).matchAll(/DshAutostart\.trigger\([^)]*DshAutostart\.(Mode\.[A-Z_]+)/g)].map((m) => m[1]);
  ok(calls.length === 1, `${label}路径恰好调一次 trigger（实际 ${calls.length}）`);
  ok(calls[0] === want, `${label}路径传的是 ${want}` + (calls[0] && calls[0] !== want ? ` → 实际 ${calls[0]}` : ""));
  // 不能绕过 trigger 直接起服务
  ok(!code(body).includes("HarnessService.start(") && !code(body).includes("HarnessService.autostart("),
    `${label}路径不绕过 trigger 直接起服务`);
}
// 脚本路径靠 am 打同一个 action，检查它没有别的隐式入口
ok(code(src.sh).includes("__ACTION__") || code(src.sh).includes("$ACT"), "脚本通过 action 启动而不是裸组件名");

// trigger 自己必须校验来源与运行时
{
  const t = src.a.slice(src.a.indexOf("fun trigger("), src.a.indexOf("\n    }", src.a.indexOf("fun trigger(")));
  ok(t.includes("want != source"), "trigger 校验来源与当前模式一致");
  ok(t.includes("isRuntimeInstalled"), "trigger 在运行时未安装时不启动（否则开机自动下载 120 MB）");
  ok(t.includes("runCatching") || t.includes("try"), "trigger 不会把异常抛到开机路径上");
}

// ── 3. 资源串 ──
console.log("\n── 资源串 ──");
const missStr = [];
for (const m of modes) {
  const base = `dsh_autostart_mode_${m.id}`;
  for (const key of [base, base + "_desc"]) {
    if (!src.strings.includes(`name="${key}"`)) missStr.push(key);
  }
}
ok(missStr.length === 0, `每种方式都有标题与说明（${modes.length * 2} 条）` + (missStr.length ? " → 缺 " + missStr.join(",") : ""));
// 界面上四个单选项一个不少
const ui = fs.readFileSync("app/src/main/java/me/bmax/apatch/ui/screen/settings/FunctionSettings.kt", "utf8");
const uiBlock = ui.slice(ui.indexOf('item(key = "function_autostart")'), ui.indexOf('item(key = "function_port")'));
// 只认 RuntimeOption 里的 onSelect —— 光是「在这段里被提到」不够：
// when (autostartMode) 分支也提到每个 Mode，删掉单选项照样能过。
const selectable = [...uiBlock.matchAll(/onAutostartModeChange\(DshAutostart\.Mode\.([A-Z_]+)\)/g)]
  .map((x) => x[1]);
const inUi = modes.filter((m) => selectable.includes(m.name));
ok(inUi.length === modes.length,
  "界面上每种方式都有单选项" + (inUi.length !== modes.length
    ? " → 缺 " + modes.filter((m) => !inUi.includes(m)).map((m) => m.id).join(",") : ""));
ok(uiBlock.includes("dsh_autostart_container"), "界面上有「同时拉起容器」开关");

// ── 4. 脚本占位符 ↔ Kotlin ──
console.log("\n── 脚本占位符 ──");
const render = src.a.slice(src.a.indexOf("private fun renderScript("), src.a.indexOf("\n    }", src.a.indexOf("private fun renderScript(")));
const placeholders = [...src.sh.matchAll(/__([A-Z]+)__/g)].map((m) => m[0]);
const replaced = [...render.matchAll(/replace\("(__[A-Z]+__)"/g)].map((m) => m[1]);
const unreplaced = [...new Set(placeholders)].filter((p) => !replaced.includes(p));
ok(unreplaced.length === 0,
  `脚本里的 ${new Set(placeholders).size} 个占位符都会被替换` + (unreplaced.length ? " → 漏 " + unreplaced.join(",") : ""));
const stray = replaced.filter((p) => !placeholders.includes(p));
ok(stray.length === 0, "没有替换脚本里不存在的占位符" + (stray.length ? " → " + stray.join(",") : ""));
// action 常量确实在 HarnessService 里，且脚本用的是它
ok(src.hs.includes("const val ACTION_AUTOSTART"), "HarnessService 定义了 ACTION_AUTOSTART");
ok(render.includes("HarnessService.ACTION_AUTOSTART"), "脚本填的是那个常量而不是硬编码字符串");
ok(render.includes("ctx.packageName"), "包名取运行时值（debug 包的 applicationId 带后缀）");
ok(render.includes("HarnessService::class.java.name"), "组件名取运行时值");

// ── 5. 脚本本身 ──
console.log("\n── 脚本内容 ──");
ok(src.sh.startsWith("#!/system/bin/sh"), "脚本有 Android 上正确的 shebang");
const shCjk = code(src.sh).match(/[\u4e00-\u9fff]/g) || [];
ok(shCjk.length === 0, `脚本代码里没有 CJK（实际 ${shCjk.length}）`);
ok(src.sh.includes("sys.boot_completed"), "脚本等 boot_completed（service.d 在 late_start 就跑了）");
ok(/exit 0/.test(src.sh), "脚本总是以 0 退出（非零会被某些管理器当成模块故障）");
ok(!/while\s+true/.test(src.sh) && !/while\s+:/.test(src.sh), "没有无限循环（会挂住一个 root 进程）");
ok(src.sh.includes("start-foreground-service"), "用 am start-foreground-service 而不是 start-service");
// 重试次数有上限
ok(/-gt 150|-lt 10/.test(src.sh), "等待与重试都有次数上限");

// ── 6. 无障碍配置最小化 ──
console.log("\n── 无障碍服务 ──");
const a11yXml = xml(src.a11y);
ok(!a11yXml.includes("canRetrieveWindowContent"),
  "配置里没有 canRetrieveWindowContent（那才是「读取屏幕内容」）");
ok(!a11yXml.includes("canPerformGestures"), "没有申请执行手势");
ok(!a11yXml.includes("canRequestFilterKeyEvents"), "没有申请过滤按键");
ok(a11yXml.includes("accessibilityEventTypes"), "声明了事件类型（一个都不订阅会被某些 ROM 判为无效）");
ok(!a11yXml.includes("typeAllMask"), "没有订阅全部事件（那等于让系统把每次界面变化都推过来）");
{
  const svcCode = code(src.svc);
  const onEvent = svcCode.slice(svcCode.indexOf("onAccessibilityEvent"), svcCode.indexOf("onAccessibilityEvent") + 120);
  ok(onEvent.includes("= Unit") || onEvent.includes("{}"), "事件回调是空实现");
  ok(svcCode.includes("onServiceConnected"), "在 onServiceConnected 里触发（那才是被 bind 的时刻）");
  ok(!svcCode.includes("rootInActiveWindow") && !svcCode.includes("findAccessibilityNodeInfo"),
    "服务不读取任何窗口内容");
}

// ── 7. manifest ──
console.log("\n── AndroidManifest ──");
const m = xml(src.manifest);
{
  const svcDecl = m.slice(m.indexOf(".dsh.DshAutostartService"), m.indexOf("</service>", m.indexOf(".dsh.DshAutostartService")));
  ok(svcDecl.includes('android:exported="true"'), "无障碍服务 exported=true（bind 它的是 system_server）");
  ok(svcDecl.includes("BIND_ACCESSIBILITY_SERVICE"), "无障碍服务用 BIND_ACCESSIBILITY_SERVICE 把门");
  ok(svcDecl.includes("android.accessibilityservice.AccessibilityService"), "声明了 intent-filter action");
  ok(svcDecl.includes("@xml/dsh_autostart_a11y"), "指向了配置文件");
}
ok(m.includes("RECEIVE_BOOT_COMPLETED"), "声明了 RECEIVE_BOOT_COMPLETED");
ok(m.includes("BootCompletedReceiver"), "注册了开机广播接收器");
ok(!m.includes("LOCKED_BOOT_COMPLETED"),
  "没有注册 LOCKED_BOOT_COMPLETED（解锁前 CE 存储没挂载，读到的是空配置）");
// 前台服务类型必须在 BOOT_COMPLETED 白名单里
ok(m.includes('android:foregroundServiceType="specialUse"'),
  "前台服务类型是 specialUse —— Android 15 的 BOOT_COMPLETED 白名单包含它");

// ── 8. 旧键迁移 ──
console.log("\n── 与 1.8.0 的兼容 ──");
{
  const modeFn = src.a.slice(src.a.indexOf("fun mode(ctx: Context)"), src.a.indexOf("\n    }", src.a.indexOf("fun mode(ctx: Context)")));
  ok(modeFn.includes("KEY_AUTOSTART, false)"), "读不到新键时回落到旧的布尔开关");
  ok(modeFn.includes("Mode.RECEIVER"), "旧的「开机自启=开」迁移成广播方式");
  const setFn = src.a.slice(src.a.indexOf("fun setMode("), src.a.indexOf("\n    }", src.a.indexOf("fun setMode(")));
  ok(setFn.includes("KEY_AUTOSTART,"), "写新键时同步旧键（方便降级）");
}

// ── 9. README ──
// 文档少写一条方式，等于那条路径不存在：没人会去翻源码找自启动怎么配。
console.log("\n── README ──");
{
  const rd = fs.readFileSync("README.md", "utf8");
  const from = rd.indexOf("## 开机自启");
  const sec = from < 0 ? "" : rd.slice(from, rd.indexOf("\n## ", from + 4));
  ok(sec.length > 0, "README 有「开机自启」一节");
  const LABELS = { receiver: "开机广播", script: "开机脚本", a11y: "无障碍服务" };
  const missDoc = modes.filter((x) => x.id !== "off" && !sec.includes(LABELS[x.id] || x.id));
  ok(missDoc.length === 0,
    "三条路径 README 都写了" + (missDoc.length ? " → 漏 " + missDoc.map((x) => x.id).join(",") : ""));
  ok(sec.includes("受限设置"), "README 说明了旁加载应用会被受限设置挡住");
  ok(sec.includes("同时拉起容器"), "README 说明了「是否同时拉起容器」");
}

console.log(fail === 0 ? "\n全部通过" : `\n${fail} 项失败`);
process.exit(fail === 0 ? 0 : 1);
