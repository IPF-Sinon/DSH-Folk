// 原生能力桥的一致性检查。
//
// 这些不变量编译器一个都抓不到，而漏掉任何一条的后果都是「界面上不存在但 prefs 里能打开」
// 或者「文档里有、实际 404」这类只有真机才发现的错：
//
//   1. 每个 Cap 都出现在某个 CapGroup 里（否则界面上凭空消失，旧 prefs 仍能启用它）
//   2. 每个 Cap 恰好出现在**一个**分组里（重复会渲染两个开关，改一个另一个不动）
//   3. capOf 覆盖 handle 里路由的每一个端点（漏了就永远 404 unknown_endpoint）
//   4. 反过来，capOf 声明的每个端点都真的被路由（写了却没实现）
//   5. capName / nativeCapTitleRes / nativeCapSummaryRes / capPermissionHintRes 全覆盖
//   6. 每个 Cap 都有对应的 dsh_native_cap_<id> / _desc 资源串
//   7. 需要运行时权限的能力，其权限都在 AndroidManifest 里声明过
//   8. 走特殊权限的能力不同时要求运行时权限（两条路互斥，混了就会走错分支）
const fs = require("fs");

const BRIDGE = "app/src/main/java/me/bmax/apatch/dsh/DshNativeBridge.kt";
const UI = "app/src/main/java/me/bmax/apatch/ui/screen/settings/FunctionSettings.kt";
const MANIFEST = "app/src/main/AndroidManifest.xml";
const STRINGS = "app/src/main/res/values/dsh_strings.xml";
const PROMPT = "app/src/main/assets/dsh-folk-host.mjs";

const bridge = fs.readFileSync(BRIDGE, "utf8");
const ui = fs.readFileSync(UI, "utf8");
const manifest = fs.readFileSync(MANIFEST, "utf8");
const strings = fs.readFileSync(STRINGS, "utf8");
const prompt = fs.readFileSync(PROMPT, "utf8");

let fail = 0;
function ok(cond, msg) {
  console.log((cond ? "  ✓ " : "  ✗ ") + msg);
  if (!cond) fail++;
}

/**
 * 取一个 `when (x) {` 的主体，并判断它有没有**顶层** else 分支。
 *
 * 深度感知是必须的：Compose 代码里分支体内部常有 `if/else` 和嵌套 `when { … else -> }`，
 * 用一个 /else ->/ 正则去扫会把内层的 else 当成外层的兜底，于是「漏一个分支」这种
 * 回归就检测不出来了（第一版就是这样）。
 */
function whenBody(src, at, header) {
  const open = src.indexOf("{", at + header.length);
  if (open < 0) return null;
  let depth = 0;
  let close = -1;
  const topLevelElse = [];
  for (let i = open; i < src.length; i++) {
    const c = src[i];
    if (c === "{") depth++;
    else if (c === "}") {
      depth--;
      if (depth === 0) {
        close = i;
        break;
      }
    } else if (depth === 1 && src.startsWith("else", i) && /^else\s*->/.test(src.slice(i))) {
      // 深度 1 = 直接属于这个 when 的分支位置
      topLevelElse.push(i);
    }
  }
  if (close < 0) return null;
  return { body: src.slice(open, close), hasElse: topLevelElse.length > 0 };
}
/** 取 `enum class Cap(...) { ... }` 里的 NAME("id") 对。 */
function parseCaps() {
  const start = bridge.indexOf("enum class Cap(val id: String) {");
  const end = bridge.indexOf("\n    }", start);
  const body = bridge.slice(start, end);
  return [...body.matchAll(/^\s{8}([A-Z_]+)\("([a-z_]+)"\)/gm)].map((m) => ({ name: m[1], id: m[2] }));
}

const caps = parseCaps();

/**
 * 需要权限（运行时的或特殊的）的能力名。
 *
 * 从 bridge 自己的两张表里推，不手写清单：手写的迟早跟代码脱节，而这个判据的用处
 * 恰恰是「加了新能力却忘了告诉 agent 它会 403」。
 */
const permGated = (() => {
  const rt = bridge.slice(bridge.indexOf("fun runtimePermissions(cap: Cap)"), bridge.indexOf("fun specialPermissionOf(cap: Cap)"));
  const sp = bridge.slice(bridge.indexOf("fun specialPermissionOf(cap: Cap)"), bridge.indexOf("enum class Special("));
  const names = new Set();
  for (const b of [rt, sp]) {
    for (const m of b.matchAll(/Cap\.([A-Z_]+)\s*->/g)) names.add(m[1]);
  }
  // 「设备上没这个硬件/引擎」和「缺权限」对 agent 是同一件事：调用会失败，而它需要
  // 知道那不是暂时性错误、不该重试。所以 availability 里任何能返回 false 的能力也算。
  const av = bridge.slice(bridge.indexOf("private fun availability(ctx: Context, cap: Cap)"), bridge.indexOf("private fun hasMicrophone"));
  for (const seg of av.split(/\n {8}(?=Cap\.)/)) {
    const m = seg.match(/^Cap\.([A-Z_,\s.]+?)\s*->/);
    if (!m) continue;
    if (!/false to "/.test(seg)) continue;
    for (const one of m[1].split(",")) names.add(one.trim().replace("Cap.", ""));
  }
  return [...names];
})();
console.log(`── Cap 共 ${caps.length} 项：${caps.map((c) => c.id).join(", ")} ──\n`);
ok(caps.length > 0, "解析到 Cap 列表");

// ── 1 & 2. 分组覆盖 ──
console.log("── 界面分组 ──");
const groupBlock = ui.slice(
  ui.indexOf("internal enum class CapGroup("),
  ui.indexOf("\n}", ui.indexOf("internal enum class CapGroup("))
);
const grouped = [...groupBlock.matchAll(/DshNativeBridge\.Cap\.([A-Z_]+)/g)].map((m) => m[1]);
const missingGroup = caps.filter((c) => !grouped.includes(c.name));
ok(missingGroup.length === 0,
  "每个 Cap 都在某个分组里" + (missingGroup.length ? " → 缺 " + missingGroup.map((c) => c.id).join(",") : ""));
const dupGroup = grouped.filter((n, i) => grouped.indexOf(n) !== i);
ok(dupGroup.length === 0,
  "没有 Cap 出现在两个分组里" + (dupGroup.length ? " → 重复 " + [...new Set(dupGroup)].join(",") : ""));
const strayGroup = grouped.filter((n) => !caps.some((c) => c.name === n));
ok(strayGroup.length === 0,
  "分组里没有不存在的 Cap" + (strayGroup.length ? " → " + strayGroup.join(",") : ""));

// ── 3 & 4. 路由 ↔ capOf ──
console.log("\n── 端点 ──");
const handleStart = bridge.indexOf("return when {\n            method ==");
const handleEnd = bridge.indexOf("else -> methodNotAllowed(ctx, method, path)", handleStart);
const handleBody = bridge.slice(handleStart, handleEnd);
const routed = [...new Set([...handleBody.matchAll(/path == "(\/native\/[a-z\/]+)"/g)].map((m) => m[1]))];

const capOfStart = bridge.indexOf("private fun capOf(path: String): Cap? = when (path) {");
const capOfEnd = bridge.indexOf("else -> null", capOfStart);
const capOfBody = bridge.slice(capOfStart, capOfEnd);
const declared = [...new Set([...capOfBody.matchAll(/"(\/native\/[a-z\/]+)"/g)].map((m) => m[1]))];

const unmapped = routed.filter((p) => !declared.includes(p));
ok(unmapped.length === 0,
  `路由的 ${routed.length} 个端点都在 capOf 里` + (unmapped.length ? " → 缺 " + unmapped.join(",") : ""));
const unrouted = declared.filter((p) => !routed.includes(p));
ok(unrouted.length === 0,
  `capOf 声明的 ${declared.length} 个端点都被路由` + (unrouted.length ? " → 缺 " + unrouted.join(",") : ""));

// capabilities 端点在总开关关闭时也走单独分支，不该出现在这两张表里
ok(!routed.includes("/native/capabilities") && !declared.includes("/native/capabilities"),
  "/native/capabilities 走独立分支（总开关关闭时也可查）");

// capOf 必须覆盖每个 Cap（一个能力没有任何端点 = 一个永远没用的开关）
const capsInCapOf = [...new Set([...capOfBody.matchAll(/Cap\.([A-Z_]+)/g)].map((m) => m[1]))];
const capsNoEndpoint = caps.filter((c) => !capsInCapOf.includes(c.name));
ok(capsNoEndpoint.length === 0,
  "每个 Cap 都至少有一个端点" + (capsNoEndpoint.length ? " → 缺 " + capsNoEndpoint.map((c) => c.id).join(",") : ""));

// ── 5. 四张映射表全覆盖 ──
console.log("\n── 映射表 ──");
const TABLES = [
  ["capName（桥内报错用）", bridge, "private fun capName(ctx: Context, cap: Cap): String = str("],
  ["nativeCapTitleRes", ui, "internal fun nativeCapTitleRes("],
  ["nativeCapSummaryRes", ui, "internal fun nativeCapSummaryRes("],
];
for (const [label, src, anchor] of TABLES) {
  const from = src.indexOf(anchor);
  if (from < 0) {
    ok(false, label + " 找不到");
    continue;
  }
  const to = src.indexOf("\n}", from) > 0 ? src.indexOf("\n}", from) : src.length;
  const body = src.slice(from, to);
  const covered = [...new Set([...body.matchAll(/Cap\.([A-Z_]+)/g)].map((m) => m[1]))];
  const miss = caps.filter((c) => !covered.includes(c.name));
  ok(miss.length === 0, label + " 覆盖每个 Cap" + (miss.length ? " → 缺 " + miss.map((c) => c.id).join(",") : ""));
}
// capPermissionHintRes 有 else 兜底，所以只要求「需要权限的那些」显式列出
{
  const from = ui.indexOf("internal fun capPermissionHintRes(");
  const body = ui.slice(from, ui.indexOf("\n}", from));
  const covered = [...new Set([...body.matchAll(/Cap\.([A-Z_]+)/g)].map((m) => m[1]))];
  // 需要权限 = runtimePermissions 非空，或有特殊权限
  const rtStart = bridge.indexOf("fun runtimePermissions(cap: Cap): Array<String> = when (cap) {");
  const rtBody = bridge.slice(rtStart, bridge.indexOf("else -> emptyArray()", rtStart));
  const needsRuntime = [...new Set([...rtBody.matchAll(/Cap\.([A-Z_]+) ->/g)].map((m) => m[1]))];
  const spStart = bridge.indexOf("fun specialPermissionOf(cap: Cap): Special? = when (cap) {");
  const spBody = bridge.slice(spStart, bridge.indexOf("else -> null", spStart));
  const needsSpecial = [...new Set([...spBody.matchAll(/Cap\.([A-Z_]+) ->/g)].map((m) => m[1]))];
  const needsAny = [...new Set([...needsRuntime, ...needsSpecial])];
  const miss = needsAny.filter((n) => !covered.includes(n));
  ok(miss.length === 0,
    `capPermissionHintRes 覆盖 ${needsAny.length} 项需要权限的能力` + (miss.length ? " → 缺 " + miss.join(",") : ""));

  // ── 8. 两条路互斥 ──
  const both = needsRuntime.filter((n) => needsSpecial.includes(n));
  ok(both.length === 0,
    "没有能力同时要求运行时权限与特殊权限" + (both.length ? " → " + both.join(",") : ""));
}

// ── 5b. 所有对 Cap 的穷尽 when 都必须覆盖每一项 ──
//
// 上面那张 TABLES 是手写的锚点清单，而手写清单会漏 —— 加 TTS 那次就漏了 capName，
// 一路放行到 CI 才被 Kotlin 的穷尽性检查拦住。这里改为自己去找：凡是 `when (cap)` /
// `when (c)` 形式且**没有** else 分支的，都是编译期要求穷尽的地方。
console.log("\n── 穷尽 when ──");
{
  const FILES = [
    ["DshNativeBridge.kt", bridge],
    ["FunctionSettings.kt", ui],
  ];
  let found = 0;
  for (const [name, src] of FILES) {
    for (const m of src.matchAll(/when \((?:cap|c)\) \{/g)) {
      const w = whenBody(src, m.index, m[0].slice(0, -1));
      if (!w) continue;
      // 有**顶层** else 兜底的不要求穷尽（capPermissionHintRes 就是这种，单独判过）。
      // 内层 if/else 与嵌套 when 的 else 不算 —— 见 whenBody 的注释。
      if (w.hasElse) continue;
      const body = w.body;
      found++;
      const line = src.slice(0, m.index).split("\n").length;
      const covered = [...new Set([...body.matchAll(/Cap\.([A-Z_]+)/g)].map((x) => x[1]))];
      const miss = caps.filter((c) => !covered.includes(c.name));
      ok(miss.length === 0,
        `${name}:${line} 的 when (cap) 覆盖全部 ${caps.length} 项` +
          (miss.length ? " → 缺 " + miss.map((c) => c.id).join(",") : ""));
    }
  }
  ok(found > 0, `找到 ${found} 处需要穷尽的 when (cap)`);
}

// ── 6. 资源串 ──
console.log("\n── 资源串 ──");
const missStr = [];
for (const c of caps) {
  for (const suffix of ["", "_desc"]) {
    const key = `dsh_native_cap_${c.id}${suffix}`;
    if (!strings.includes(`name="${key}"`)) missStr.push(key);
  }
}
ok(missStr.length === 0,
  `每个 Cap 都有标题与说明串（${caps.length * 2} 条）` + (missStr.length ? " → 缺 " + missStr.join(",") : ""));

// ── 7. manifest 声明 ──
console.log("\n── AndroidManifest ──");
const rtStart = bridge.indexOf("fun runtimePermissions(cap: Cap): Array<String> = when (cap) {");
const rtBody = bridge.slice(rtStart, bridge.indexOf("else -> emptyArray()", rtStart));
// 直接写出的 android.Manifest.permission.X
const direct = [...new Set([...rtBody.matchAll(/android\.Manifest\.permission\.([A-Z_]+)/g)].map((m) => m[1]))];
// PermissionUtils 里的成组权限
const pu = fs.readFileSync("app/src/main/java/me/bmax/apatch/util/PermissionUtils.kt", "utf8");
const grouped2 = [...new Set([...pu.matchAll(/Manifest\.permission\.([A-Z_]+)/g)].map((m) => m[1]))];
const allPerms = [...new Set([...direct, ...grouped2])];
const notDeclared = allPerms.filter((p) => !manifest.includes("android.permission." + p));
ok(notDeclared.length === 0,
  `代码用到的 ${allPerms.length} 个权限都在 manifest 里` + (notDeclared.length ? " → 缺 " + notDeclared.join(",") : ""));

// manifest 本身得是良构 XML —— aapt2 会拒绝，但那要等 CI 十分钟
const mNoComment = manifest.replace(/<!--[\s\S]*?-->/g, "").replace(/<\?[\s\S]*?\?>/g, "");
{
  const st = [];
  let broken = null;
  for (const t of mNoComment.matchAll(/<(\/?)([a-zA-Z][\w:.-]*)(\s[^>]*?)?(\/?)>/g)) {
    const [, slash, tag, , self] = t;
    if (slash) {
      const top = st.pop();
      if (top !== tag) {
        broken = `</${tag}> 配到 <${top}>`;
        break;
      }
    } else if (!self) {
      st.push(tag);
    }
  }
  if (st.length > 0 && broken === null) broken = "未闭合: " + st.join(",");
  ok(broken === null, "manifest 是良构 XML" + (broken ? " → " + broken : ""));
  // XML 注释里不允许出现连续两个横线，这个错 aapt2 也会拒
  const badComment = [...manifest.matchAll(/<!--([\s\S]*?)-->/g)].find((c) => c[1].includes("--"));
  ok(!badComment, "注释里没有非法的双横线");
}

// 申请某些权限会**隐式**把对应硬件设为 required=true，没那个硬件的设备就被判不兼容。
// 本应用的核心功能（容器里跑 Node）不依赖任何一件，所以每一条都必须显式放宽。
const IMPLIED = [
  ["CAMERA", ["android.hardware.camera", "android.hardware.camera.autofocus"]],
  ["RECORD_AUDIO", ["android.hardware.microphone"]],
  ["ACCESS_FINE_LOCATION", ["android.hardware.location.gps"]],
  ["ACCESS_COARSE_LOCATION", ["android.hardware.location.network"]],
  ["ACCESS_WIFI_STATE", ["android.hardware.wifi"]],
  ["READ_PHONE_STATE", ["android.hardware.telephony"]],
];
const RELAXED = /android:required="false"/;
const notRelaxed = [];
for (const [perm, features] of IMPLIED) {
  if (!manifest.includes("android.permission." + perm)) continue;
  for (const f of features) {
    const line = [...manifest.matchAll(/<uses-feature[^>]*>/g)]
      .map((m) => m[0])
      .find((l) => l.includes('"' + f + '"'));
    if (!line || !RELAXED.test(line)) notRelaxed.push(perm + " → " + f);
  }
}
ok(notRelaxed.length === 0,
  "隐式硬件要求都放宽为 required=false" + (notRelaxed.length ? " → 缺 " + notRelaxed.join("; ") : ""));
const hardFeature = [...manifest.matchAll(/<uses-feature[^>]*>/g)]
  .map((m) => m[0])
  .filter((l) => !RELAXED.test(l));
ok(hardFeature.length === 0,
  `全部 ${[...manifest.matchAll(/<uses-feature[^>]*>/g)].length} 条 uses-feature 都不是必需` +
    (hardFeature.length ? " → " + hardFeature.length + " 条是必需" : ""));


// ── 提示词插件 ──
//
// 每项能力都必须在注入 dsh 的提示词里有用法与注意事项。漏掉一项的后果很隐蔽：
// 那项能力开着、端点也通，但 agent 压根不知道它存在 —— 与没做等价。
console.log("\n── 提示词插件 ──");
{
  function block(name) {
    const at = prompt.indexOf(`const ${name} = {`);
    if (at < 0) return null;
    // 到下一个顶层 `};` 为止
    const end = prompt.indexOf("\n};", at);
    return end < 0 ? null : prompt.slice(at, end);
  }
  const usage = block("CAP_USAGE");
  const caveat = block("CAP_CAVEAT");
  ok(usage !== null && caveat !== null, "能找到 CAP_USAGE 与 CAP_CAVEAT 两张表");
  if (usage && caveat) {
    // 只认顶层的 `  id:` 行（两空格缩进），否则 usage 字符串里的冒号也会被算进去
    const keys = (b) => [...b.matchAll(/^ {2}([a-zA-Z]+):/gm)].map((m) => m[1]);
    const uk = keys(usage);
    const ck = keys(caveat);
    const missU = caps.filter((c) => !uk.includes(c.id)).map((c) => c.id);
    ok(missU.length === 0, `CAP_USAGE 覆盖每个 Cap（${caps.length} 项）` + (missU.length ? " → 缺 " + missU.join(",") : ""));
    // 注意事项**不**要求每项都有：toast / device 就是「调了就成、没有陷阱」
    // 的那种，硬凑一条注意事项只会稀释真正重要的几条。但**需要权限**的能力必须有 ——
    // 「它可能失败，那时该怎么办」正是 agent 必须被告知的事 —— 缺权限与缺硬件同理。
    const needsPerm = caps.filter((c) => permGated.includes(c.name)).map((c) => c.id);
    const missC = needsPerm.filter((id) => !ck.includes(id));
    ok(missC.length === 0,
      `会失败的 ${needsPerm.length} 项能力都有注意事项（缺权限或缺硬件）` + (missC.length ? " → 缺 " + missC.join(",") : ""));
    const strayU = uk.filter((k) => !caps.some((c) => c.id === k));
    ok(strayU.length === 0, "CAP_USAGE 里没有已不存在的能力" + (strayU.length ? " → " + strayU.join(",") : ""));
    const strayC = ck.filter((k) => !caps.some((c) => c.id === k));
    ok(strayC.length === 0, "CAP_CAVEAT 里没有已不存在的能力" + (strayC.length ? " → " + strayC.join(",") : ""));
  }
  // 插件内容改了就必须 +1，否则装过的机器上不会被覆盖（落盘按版本号判断）
  const kt = fs.readFileSync("app/src/main/java/me/bmax/apatch/dsh/DshHostPrompt.kt", "utf8");
  const rev = kt.match(/PLUGIN_REV = (\d+)/);
  ok(rev !== null, "DshHostPrompt 里有 PLUGIN_REV" + (rev ? ` = ${rev[1]}` : ""));
}

console.log(fail === 0 ? "\n全部通过" : `\n${fail} 项失败`);
process.exit(fail === 0 ? 0 : 1);
