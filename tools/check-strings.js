#!/usr/bin/env node
// 校验 values/ 与 values-zh-rCN/ 的 dsh_strings.xml。
//
// 为什么需要它：本项目没有本地 Android SDK，改字符串的错误（键缺失、占位符不一致、
// 未转义的 &）要等 CI 跑 aapt2 才暴露，一轮十几分钟。这些都是纯文本判据，本地几十
// 毫秒就能查完。
//
//  1. 键集完全一致（少一条就是某个语言下的空文案）；
//  2. 同一键的占位符集合一致（%1$s vs 缺失 → 运行时 IllegalFormatException）；
//  3. 没有未转义的裸 & 或非法 %（aapt2 直接编译失败）；
//  4. 代码里引用的 R.string.dsh_* 都存在，且带参调用的键真的有占位符。
const fs = require("fs");
const path = require("path");

const ROOT = path.resolve(__dirname, "..");
const EN = "app/src/main/res/values/dsh_strings.xml";
const ZH = "app/src/main/res/values-zh-rCN/dsh_strings.xml";

function parse(file) {
  const raw = fs.readFileSync(path.join(ROOT, file), "utf8");
  const map = new Map();
  const untranslatable = new Set();
  for (const m of raw.matchAll(/<string name="([^"]+)"([^>]*)>([\s\S]*?)<\/string>/g)) {
    map.set(m[1], m[3]);
    // translatable="false" 的串按设计只存在于默认语言，不该要求 zh 也有
    if (/translatable\s*=\s*"false"/.test(m[2])) untranslatable.add(m[1]);
  }
  return { raw, map, untranslatable };
}

const en = parse(EN);
const zh = parse(ZH);
let fail = 0;

// ── 1. 键集 ──
const enOnly = [...en.map.keys()].filter((k) => !zh.map.has(k) && !en.untranslatable.has(k));
const zhOnly = [...zh.map.keys()].filter((k) => !en.map.has(k));
console.log(`键数 en=${en.map.size}（其中 translatable=false ${en.untranslatable.size}）zh=${zh.map.size}`);
if (enOnly.length) {
  console.log(`✗ 只有 en: ${enOnly.join(", ")}`);
  fail++;
}
if (zhOnly.length) {
  console.log(`✗ 只有 zh: ${zhOnly.join(", ")}`);
  fail++;
}
if (!enOnly.length && !zhOnly.length) console.log("✓ 两个语言键集一致");

// ── 2. 占位符 ──
function placeholders(v) {
  return [...v.matchAll(/%(\d+\$)?[sdf]/g)].map((m) => m[0]).sort().join(",");
}
let phBad = 0;
for (const [k, v] of en.map) {
  if (!zh.map.has(k)) continue;
  const a = placeholders(v);
  const b = placeholders(zh.map.get(k));
  if (a !== b) {
    console.log(`✗ 占位符不一致 ${k}: en[${a}] zh[${b}]`);
    phBad++;
  }
}
if (phBad) fail++;
else console.log("✓ 占位符全部一致");

// ── 3. 转义 ──
let escBad = 0;
for (const [file, { map }] of [[EN, en], [ZH, zh]]) {
  const dir = path.basename(path.dirname(file));
  for (const [k, v] of map) {
    for (const _ of v.matchAll(/&(?!amp;|lt;|gt;|quot;|apos;|#\d+;|#x[0-9a-fA-F]+;)/g)) {
      console.log(`✗ ${dir}/${k}: 未转义的 &`);
      escBad++;
    }
    const stripped = v.replace(/%(\d+\$)?[sdf]/g, "").replace(/%%/g, "");
    if (stripped.includes("%")) {
      console.log(`✗ ${dir}/${k}: 非法的 %（要么写成合法占位符，要么转义为 %%）`);
      escBad++;
    }
  }
}
if (escBad) fail++;
else console.log("✓ 转义合法");

// ── 4. 代码引用 ──
const kotlin = [];
(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const f = path.join(d, e.name);
    if (e.isDirectory()) walk(f);
    else if (f.endsWith(".kt")) kotlin.push({ f, s: fs.readFileSync(f, "utf8") });
  }
})(path.join(ROOT, "app/src/main/java"));

let refBad = 0;
let refCount = 0;
for (const { f, s } of kotlin) {
  // android.R.string.* 不是本项目的资源
  for (const m of s.matchAll(/(?<!android\.)\bR\.string\.(dsh_[a-z0-9_]+)/g)) {
    refCount++;
    if (!en.map.has(m[1])) {
      console.log(`✗ ${path.relative(ROOT, f)}: R.string.${m[1]} 未定义`);
      refBad++;
    }
  }
}
console.log(`代码引用 dsh_* ${refCount} 个`);
if (refBad) fail++;
else console.log("✓ 代码引用的串都存在");

// SettingsRegistry 的摘要走无参 getString，带占位符会原样显示 "%1$s"
const registry = kotlin.find(({ f }) => f.endsWith("SettingsRegistry.kt"));
if (registry) {
  let regBad = 0;
  for (const m of registry.s.matchAll(/\bR\.string\.(dsh_[a-z0-9_]+)/g)) {
    const v = en.map.get(m[1]);
    if (v && placeholders(v)) {
      console.log(`✗ SettingsRegistry 引用了带占位符的 ${m[1]}（resolveAll 用无参 getString）`);
      regBad++;
    }
  }
  if (regBad) fail++;
  else console.log("✓ SettingsRegistry 引用的串都不带占位符");
}

console.log(fail === 0 ? "\n全部通过" : `\n${fail} 组检查失败`);
process.exit(fail === 0 ? 0 : 1);
