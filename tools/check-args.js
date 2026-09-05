// 命名参数调用与函数声明的一致性检查。
//
// 这一类错误刚刚连着废掉两轮 CI，而它们全都是纯文本可判的：
//   1. 声明里有重名参数（改签名时插了一个已经存在的名字）
//   2. 调用点重复传同一个参数
//   3. 调用点漏传没有默认值的参数
//   4. 调用点传了声明里不存在的参数
//
// 只处理**全命名参数**的调用（Compose 里的大函数基本都是这么调的），位置参数一律跳过 ——
// 那需要真的做类型推断，超出文本分析能做的范围。
const fs = require("fs");
const path = require("path");

const ROOT = path.resolve(__dirname, "..");

/** 要检查的「声明文件 → 调用文件」对。 */
const PAIRS = [
  ["AppearanceSettingsContent", "app/src/main/java/me/bmax/apatch/ui/screen/settings/AppearanceSettings.kt",
    "app/src/main/java/me/bmax/apatch/ui/screen/settings/AppearanceSettingsScreen.kt"],
  ["BackupSettingsContent", "app/src/main/java/me/bmax/apatch/ui/screen/settings/BackupSettings.kt",
    "app/src/main/java/me/bmax/apatch/ui/screen/settings/BackupSettingsScreen.kt"],
  ["BehaviorSettingsContent", "app/src/main/java/me/bmax/apatch/ui/screen/settings/BehaviorSettings.kt",
    "app/src/main/java/me/bmax/apatch/ui/screen/settings/BehaviorSettingsScreen.kt"],
  ["FunctionSettingsContent", "app/src/main/java/me/bmax/apatch/ui/screen/settings/FunctionSettings.kt",
    "app/src/main/java/me/bmax/apatch/ui/screen/settings/FunctionSettingsScreen.kt"],
  ["GeneralSettingsContent", "app/src/main/java/me/bmax/apatch/ui/screen/settings/GeneralSettings.kt",
    "app/src/main/java/me/bmax/apatch/ui/screen/settings/GeneralSettingsScreen.kt"],
  ["ModuleSettingsContent", "app/src/main/java/me/bmax/apatch/ui/screen/settings/ModuleSettings.kt",
    "app/src/main/java/me/bmax/apatch/ui/screen/settings/ModuleSettingsScreen.kt"],
  ["MultimediaSettingsContent", "app/src/main/java/me/bmax/apatch/ui/screen/settings/MultimediaSettings.kt",
    "app/src/main/java/me/bmax/apatch/ui/screen/settings/MultimediaSettingsScreen.kt"],
  ["SecuritySettingsContent", "app/src/main/java/me/bmax/apatch/ui/screen/settings/SecuritySettings.kt",
    "app/src/main/java/me/bmax/apatch/ui/screen/settings/SecuritySettingsScreen.kt"],
  ["PluginSettingsContent", "app/src/main/java/me/bmax/apatch/ui/screen/settings/PluginSettings.kt",
    "app/src/main/java/me/bmax/apatch/ui/screen/settings/PluginSettingsScreen.kt"],
];

let fail = 0;
function ok(cond, msg) {
  console.log((cond ? "  ✓ " : "  ✗ ") + msg);
  if (!cond) fail++;
}

/** 从 `(` 开始按括号深度找到配对的 `)`，跳过字符串与注释。 */
function matchParen(s, open) {
  let depth = 0;
  for (let i = open; i < s.length; i++) {
    const c = s[i];
    if (c === '"') {
      // 跳过字符串（含三引号）
      if (s.startsWith('"""', i)) {
        const end = s.indexOf('"""', i + 3);
        i = end < 0 ? s.length : end + 2;
      } else {
        i++;
        while (i < s.length && s[i] !== '"') {
          if (s[i] === "\\") i++;
          i++;
        }
      }
      continue;
    }
    if (c === "/" && s[i + 1] === "/") {
      i = s.indexOf("\n", i);
      if (i < 0) return -1;
      continue;
    }
    if (c === "/" && s[i + 1] === "*") {
      const end = s.indexOf("*/", i);
      i = end < 0 ? s.length : end + 1;
      continue;
    }
    if (c === "(") depth++;
    else if (c === ")") {
      depth--;
      if (depth === 0) return i;
    }
  }
  return -1;
}

/** 按顶层逗号切分参数列表。 */
function splitTop(body) {
  const out = [];
  let depth = 0;
  let start = 0;
  for (let i = 0; i < body.length; i++) {
    const c = body[i];
    if (c === '"') {
      if (body.startsWith('"""', i)) {
        const e = body.indexOf('"""', i + 3);
        i = e < 0 ? body.length : e + 2;
      } else {
        i++;
        while (i < body.length && body[i] !== '"') {
          if (body[i] === "\\") i++;
          i++;
        }
      }
      continue;
    }
    if (c === "/" && body[i + 1] === "/") {
      i = body.indexOf("\n", i);
      if (i < 0) break;
      continue;
    }
    if (c === "/" && body[i + 1] === "*") {
      const e = body.indexOf("*/", i);
      i = e < 0 ? body.length : e + 1;
      continue;
    }
    // 不把 < > 当括号。它们在 Kotlin 里更多是比较运算符（`>=`、`a > b`）和函数类型的
    // 箭头，而不是泛型定界；按括号算会让深度变负，整个参数列表就切不开了。泛型里的逗号
    // 几乎总被外层的 ( ) 或 { } 罩住，所以不跟踪它们并不会漏切。
    if ("([{".includes(c)) depth++;
    else if (")]}".includes(c)) depth--;
    else if (c === "," && depth === 0) {
      out.push(body.slice(start, i));
      start = i + 1;
    }
  }
  out.push(body.slice(start));
  return out.map((x) => x.trim()).filter((x) => x.length > 0);
}

for (const [fn, declFile, callFile] of PAIRS) {
  console.log(`── ${fn} ──`);
  if (!fs.existsSync(path.join(ROOT, declFile)) || !fs.existsSync(path.join(ROOT, callFile))) {
    console.log("  – 跳过（文件不存在）");
    continue;
  }
  const decl = fs.readFileSync(path.join(ROOT, declFile), "utf8");
  const call = fs.readFileSync(path.join(ROOT, callFile), "utf8");

  // ── 声明 ──
  const declAt = decl.indexOf(`fun ${fn}(`);
  if (declAt < 0) {
    ok(false, `找不到 fun ${fn}(`);
    continue;
  }
  const dOpen = decl.indexOf("(", declAt);
  const dClose = matchParen(decl, dOpen);
  const params = splitTop(decl.slice(dOpen + 1, dClose)).map((p) => {
    // 去掉前导的 KDoc / 行注释
    const clean = p.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, "").trim();
    const m = clean.match(/^([A-Za-z_][A-Za-z0-9_]*)\s*:/);
    return { name: m ? m[1] : null, hasDefault: /(^|[^=!<>])=[^=]/.test(clean.split(":").slice(1).join(":")) };
  }).filter((p) => p.name);

  const dupDecl = params.map((p) => p.name).filter((n, i, a) => a.indexOf(n) !== i);
  ok(dupDecl.length === 0,
    `声明的 ${params.length} 个参数没有重名` + (dupDecl.length ? " → 重复 " + [...new Set(dupDecl)].join(",") : ""));

  // ── 调用 ──
  const callAt = call.indexOf(`${fn}(`);
  if (callAt < 0) {
    ok(false, `${path.basename(callFile)} 里找不到调用`);
    continue;
  }
  const cOpen = call.indexOf("(", callAt);
  const cClose = matchParen(call, cOpen);
  const args = splitTop(call.slice(cOpen + 1, cClose)).map((a) => {
    const clean = a.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, "").trim();
    const m = clean.match(/^([A-Za-z_][A-Za-z0-9_]*)\s*=(?!=)/);
    return m ? m[1] : null;
  });
  const named = args.filter(Boolean);
  ok(named.length === args.length,
    `调用点全部使用命名参数（${named.length}/${args.length}）`);

  const dupArg = named.filter((n, i, a) => a.indexOf(n) !== i);
  ok(dupArg.length === 0,
    "调用点没有重复传参" + (dupArg.length ? " → 重复 " + [...new Set(dupArg)].join(",") : ""));

  const names = new Set(params.map((p) => p.name));
  const unknown = named.filter((n) => !names.has(n));
  ok(unknown.length === 0,
    "调用点没有传声明里不存在的参数" + (unknown.length ? " → " + unknown.join(",") : ""));

  const missing = params.filter((p) => !p.hasDefault && !named.includes(p.name)).map((p) => p.name);
  ok(missing.length === 0,
    `必填参数全部传了（声明 ${params.length} 个，调用 ${named.length} 个）` +
      (missing.length ? " → 漏 " + missing.join(",") : ""));
}

console.log(fail === 0 ? "\n全部通过" : `\n${fail} 项失败`);
process.exit(fail === 0 ? 0 : 1);
