// 粗粒度的「引用能不能解析」检查。
//
// 本机没有 Java / Android SDK，编译只发生在 CI，而 CI 不跑这些校验器 —— 所以最贵的
// 错误是那种「一眼看不出、要等 CI 十分钟才炸」的。经验上有三类：
//   1. 用了没 import 的类
//   2. 调了对象上不存在的成员（改名之后最常见）
//   3. 调了别的文件里 private 的成员
// 这三类都能靠文本分析抓到大半。
const fs = require("fs");
const path = require("path");

const ROOT = "app/src/main/java/me/bmax/apatch";
const TARGETS = [
  "dsh/DshCamera.kt",
  "dsh/DshPersonalData.kt",
  "dsh/DshSystemCtl.kt",
  "dsh/DshDeviceSense.kt",
  "dsh/DshNativeBridge.kt",
  "util/PermissionUtils.kt",
];

let fail = 0;
function ok(cond, msg) {
  console.log((cond ? "  ✓ " : "  ✗ ") + msg);
  if (!cond) fail++;
}

/** 收集一个文件里声明的顶层成员（object/enum 里的 fun/val/const/enum class）。 */
function members(src) {
  const out = new Set();
  for (const m of src.matchAll(/^\s*(?:internal |private |public )?(?:const )?(?:val|fun)\s+([A-Za-z_][A-Za-z0-9_]*)/gm)) {
    out.add(m[1]);
  }
  for (const m of src.matchAll(/^\s*(?:internal |private )?enum class\s+([A-Za-z_][A-Za-z0-9_]*)/gm)) {
    out.add(m[1]);
  }
  return out;
}
/** 私有成员（其他文件不能用）。 */
function privates(src) {
  const out = new Set();
  for (const m of src.matchAll(/^\s*private (?:const )?(?:val|fun)\s+([A-Za-z_][A-Za-z0-9_]*)/gm)) {
    out.add(m[1]);
  }
  return out;
}

// ── 1. 跨文件成员调用 ──
console.log("── 跨文件调用能否解析 ──");
const OBJECTS = {
  DshNativeBridge: "dsh/DshNativeBridge.kt",
  DshCamera: "dsh/DshCamera.kt",
  DshPersonalData: "dsh/DshPersonalData.kt",
  DshSystemCtl: "dsh/DshSystemCtl.kt",
  DshDeviceSense: "dsh/DshDeviceSense.kt",
  PermissionUtils: "util/PermissionUtils.kt",
};
const cache = {};
for (const [obj, rel] of Object.entries(OBJECTS)) {
  const src = fs.readFileSync(path.join(ROOT, rel), "utf8");
  cache[obj] = { src, members: members(src), privates: privates(src) };
}

// 扫全树，找 Obj.member 形式的调用
const allKt = [];
(function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p);
    else if (e.name.endsWith(".kt")) allKt.push(p);
  }
})(ROOT);

const problems = [];
for (const file of allKt) {
  const src = fs.readFileSync(file, "utf8");
  // 剥注释，免得 KDoc 里的 [DshNativeBridge.err] 被当调用
  const code = src.replace(/\/\/.*$/gm, "").replace(/\/\*[\s\S]*?\*\//g, "");
  for (const obj of Object.keys(OBJECTS)) {
    const self = file.endsWith(OBJECTS[obj]);
    for (const m of code.matchAll(new RegExp("\\b" + obj + "\\.([a-z][A-Za-z0-9_]*)", "g"))) {
      const name = m[1];
      const info = cache[obj];
      if (!info.members.has(name)) {
        problems.push(`${path.basename(file)}: ${obj}.${name} 不存在`);
      } else if (!self && info.privates.has(name)) {
        problems.push(`${path.basename(file)}: ${obj}.${name} 是 private，跨文件用不了`);
      }
    }
  }
}
const uniq = [...new Set(problems)];
ok(uniq.length === 0, `所有 Obj.member 调用都能解析（扫了 ${allKt.length} 个文件）`);
for (const p of uniq) console.log("      " + p);

// ── 2. 用到但没 import 的类 ──
console.log("\n── import 完整性 ──");
// 只检查我新写/大改的文件，且只认「明确来自 android./java. 的类型」
const KNOWN = {
  Sensor: "android.hardware.Sensor",
  SensorEvent: "android.hardware.SensorEvent",
  SensorEventListener: "android.hardware.SensorEventListener",
  SensorManager: "android.hardware.SensorManager",
  ConnectivityManager: "android.net.ConnectivityManager",
  NetworkCapabilities: "android.net.NetworkCapabilities",
  WifiManager: "android.net.wifi.WifiManager",
  TelephonyManager: "android.telephony.TelephonyManager",
  AudioManager: "android.media.AudioManager",
  NotificationManager: "android.app.NotificationManager",
  LocationManager: "android.location.LocationManager",
  LocationListener: "android.location.LocationListener",
  Location: "android.location.Location",
  CalendarContract: "android.provider.CalendarContract",
  ContactsContract: "android.provider.ContactsContract",
  ContentValues: "android.content.ContentValues",
  ContentUris: "android.content.ContentUris",
  CameraManager: "android.hardware.camera2.CameraManager",
  CameraDevice: "android.hardware.camera2.CameraDevice",
  CameraCharacteristics: "android.hardware.camera2.CameraCharacteristics",
  CameraCaptureSession: "android.hardware.camera2.CameraCaptureSession",
  CaptureRequest: "android.hardware.camera2.CaptureRequest",
  ImageReader: "android.media.ImageReader",
  ImageFormat: "android.graphics.ImageFormat",
  HandlerThread: "android.os.HandlerThread",
  Handler: "android.os.Handler",
  Looper: "android.os.Looper",
  Bundle: "android.os.Bundle",
  Build: "android.os.Build",
  Settings: "android.provider.Settings",
  Size: "android.util.Size",
  Log: "android.util.Log",
  CountDownLatch: "java.util.concurrent.CountDownLatch",
  TimeUnit: "java.util.concurrent.TimeUnit",
  AtomicBoolean: "java.util.concurrent.atomic.AtomicBoolean",
  AtomicReference: "java.util.concurrent.atomic.AtomicReference",
  TimeZone: "java.util.TimeZone",
  JSONObject: "org.json.JSONObject",
  JSONArray: "org.json.JSONArray",
  PackageManager: "android.content.pm.PackageManager",
  ContextCompat: "androidx.core.content.ContextCompat",
  File: "java.io.File",
  PermissionUtils: "me.bmax.apatch.util.PermissionUtils",
};
for (const rel of TARGETS) {
  const file = path.join(ROOT, rel);
  const src = fs.readFileSync(file, "utf8");
  const code = src.replace(/\/\/.*$/gm, "").replace(/\/\*[\s\S]*?\*\//g, "");
  const imports = new Set([...src.matchAll(/^import (.+)$/gm)].map((m) => m[1].trim()));
  const samePkg = rel.startsWith("dsh/");
  const miss = [];
  for (const [cls, fqn] of Object.entries(KNOWN)) {
    // 用到了这个简单名（后面跟 . 或 ( 或 < 或 ? 或 空格）
    if (!new RegExp("\\b" + cls + "\\s*[.(<?,)\\s]").test(code)) continue;
    if (imports.has(fqn)) continue;
    // 全限定写法也算
    if (code.includes(fqn)) continue;
    // 同包不需要 import
    if (samePkg && fqn.startsWith("me.bmax.apatch.dsh.")) continue;
    // 文件自己声明的类型当然不用 import 自己
    if (new RegExp("^(?:internal |private )?object " + cls + "\\b", "m").test(code)) continue;
    miss.push(cls + " → " + fqn);
  }
  ok(miss.length === 0, path.basename(rel) + (miss.length ? " 缺 import: " + miss.join("; ") : " import 齐"));
}

// ── 3. 未使用的 import（编译警告，但也是真噪音） ──
console.log("\n── 未使用的 import ──");
for (const rel of TARGETS) {
  const file = path.join(ROOT, rel);
  const src = fs.readFileSync(file, "utf8");
  const body = src.slice(src.lastIndexOf("\nimport ") + 1);
  const unused = [];
  for (const m of src.matchAll(/^import (?:.*\.)?([A-Za-z_][A-Za-z0-9_]*)$/gm)) {
    const simple = m[1];
    // 去掉 import 行本身再找用处
    const rest = src.replace(new RegExp("^import .*\\." + simple + "$", "m"), "");
    if (!new RegExp("\\b" + simple + "\\b").test(rest.replace(/^import .*$/gm, ""))) {
      unused.push(simple);
    }
  }
  ok(unused.length === 0, path.basename(rel) + (unused.length ? " 有未使用 import: " + unused.join(", ") : " 无冗余 import"));
}

console.log(fail === 0 ? "\n全部通过" : `\n${fail} 项失败`);
process.exit(fail === 0 ? 0 : 1);
