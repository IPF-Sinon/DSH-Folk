#!/usr/bin/env node
// 校验「面向用户的字符串必须跟随应用内语言」这条不变量。
//
// 为什么需要它：AppCompatDelegate.setApplicationLocales() 在 API 33 以下**只**改
// Activity 的 Configuration（AppCompatDelegateImpl.attachBaseContext2 的注释明确写着
// 不要动 Application 的 configuration）。于是 Application / Service / 单例上的
// getString() 返回的是**系统语言**，不是用户在应用里选的语言。minSdk 是 26，这条路径
// 覆盖大多数在用的设备。
//
// 这类 bug 没有编译期信号，肉眼审查也很容易漏（`ctx.getString` 看起来完全正常），
// 只有把设备语言与应用内语言设成不同才能复现。所以钉成静态检查。
//
// 规则：非 Activity 的 Context 上取面向用户的字符串，一律走 Context.appString()。
const fs = require("fs");
const path = require("path");

const ROOT = path.resolve(__dirname, "..");
const SRC = path.join(ROOT, "app/src/main/java/me/bmax/apatch");

// Activity / Composable 的 Context 本来就带应用内语言，getString 是对的。
// 这里列的是**例外**：确实持有 Activity Context、或本身就是 Activity 的文件。
const ACTIVITY_SCOPED = [
  "ui/MainActivity.kt",
  "ui/CrashHandleActivity.kt",
  "ui/DshWebUiActivity.kt",
  "util/BiometricUtils.kt", // 形参就叫 activity
  "util/LocaleCtx.kt", // 这一层自己实现 appString
];

// Composable 用 stringResource()，那条路径不经 Context.getString。
const COMPOSE_DIRS = ["ui/screen/", "ui/component/", "ui/theme/"];

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const f = path.join(dir, e.name);
    if (e.isDirectory()) walk(f, out);
    else if (f.endsWith(".kt")) out.push(f);
  }
  return out;
}

let fail = 0;
let checked = 0;
const hits = [];

for (const file of walk(SRC)) {
  const rel = path.relative(SRC, file).split(path.sep).join("/");
  if (ACTIVITY_SCOPED.includes(rel)) continue;
  if (COMPOSE_DIRS.some((d) => rel.startsWith(d))) continue;

  const src = fs.readFileSync(file, "utf8");
  // 去掉注释，避免 KDoc 里解释这条规则的文字被当成违规
  const code = src
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .split("\n")
    .map((l) => l.replace(/(^|\s)\/\/.*$/, ""))
    .join("\n");

  checked++;
  for (const m of code.matchAll(/(\w+(?:\.\w+)*)\.getString\(\s*R\.string\.(\w+)/g)) {
    hits.push(`${rel}: ${m[1]}.getString(R.string.${m[2]})`);
  }
  // Service / Application 里的裸 getString(R.string.x)（隐式 this）
  for (const m of code.matchAll(/(?<![.\w])getString\(\s*R\.string\.(\w+)/g)) {
    hits.push(`${rel}: getString(R.string.${m[1]})（隐式 this）`);
  }
}

console.log(`检查了 ${checked} 个非 Activity / 非 Compose 的 Kotlin 文件`);
if (hits.length) {
  console.log("✗ 下列位置应改用 Context.appString()，否则 API 33 以下拿到系统语言：");
  for (const h of hits) console.log(`    ${h}`);
  fail++;
} else {
  console.log("✓ 没有绕过应用内语言的 getString");
}

// appString 这一层本身的不变量
const localeCtx = fs.readFileSync(path.join(SRC, "util/LocaleCtx.kt"), "utf8");
const invariants = [
  ["config.setLocales(", "必须 setLocales 而不是 setLocale：保留整条回退链（pt-BR → pt → en）"],
  ["createConfigurationContext", "必须真的换一份 Resources"],
  ["if (locales.isEmpty) return base", "跟随系统时不该多包一层"],
  ["cachedTags == tags", "缓存必须按语言失效，否则切语言后旧语言会残留"],
  ["base === base.applicationContext", "只能缓存 application context，否则泄漏 Activity"],
];
let invBad = 0;
for (const [needle, why] of invariants) {
  if (!localeCtx.includes(needle)) {
    console.log(`✗ LocaleCtx: ${why}`);
    invBad++;
  }
}
if (invBad) fail++;
else console.log("✓ LocaleCtx 的实现约束都在");

console.log(fail === 0 ? "\n全部通过" : `\n${fail} 组检查失败`);
process.exit(fail === 0 ? 0 : 1);
