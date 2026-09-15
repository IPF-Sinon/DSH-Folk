#!/usr/bin/env node
/**
 * 拦住「限高又不给滚动」的 Text —— 静默裁字。
 *
 * ## 为什么要有这条
 *
 * 更新提示框的 release 正文写成：
 *
 *     Text(
 *         text = status.notes,
 *         modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp),
 *     )
 *
 * Compose 的 Text 拿到**有界高度**又没有 `overflow`/`maxLines` 时，多出来的行是直接裁掉
 * 的：不报错、不省略号、也没有滚动条。用户看到的就是「更新内容显示不全」，而开发者在
 * 预览里只看到短文案，什么问题都没有。外层容器就算能滚也救不了 —— 被裁的是内层自己的
 * 高度。
 *
 * 正确的两种写法：要么给中段 `.verticalScroll(rememberScrollState())`（`ElevationRequestDialog`
 * 里的命令预览就是这么写的），要么根本别限高，让外层滚动容器去管。
 *
 * 用法：`node tools/check-text-clipping.js`
 */
"use strict";
const fs = require("fs");
const path = require("path");

const ROOT = "app/src/main/java";
let bad = 0;
let total = 0;
const ok = (cond, label) => {
  if (!cond) bad++;
  console.log("  " + (cond ? "✓" : "✗") + " " + label);
};

/** 从 `Text(` 起做括号配对，取出这次调用的完整参数文本。 */
function callSlice(src, openParenAt) {
  let depth = 0;
  for (let i = openParenAt; i < src.length; i++) {
    const c = src[i];
    if (c === "(") depth++;
    else if (c === ")") {
      depth--;
      if (depth === 0) return src.slice(openParenAt + 1, i);
    } else if (c === '"') {
      // 跳过字符串字面量，避免其中的括号破坏配对
      i++;
      while (i < src.length && src[i] !== '"') {
        if (src[i] === "\\") i++;
        i++;
      }
    }
  }
  return src.slice(openParenAt + 1);
}

function walk(dir, out) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith(".kt")) out.push(p);
  }
}

/** 「有界高度」的两种写法：heightIn(max = …) 与 height(…dp)。 */
const BOUNDED = /\.heightIn\(\s*max\s*=|\.height\(\s*\d/;

const files = [];
walk(ROOT, files);

console.log("─ 限高的 Text 必须给出路（滚动或显式省略）");
const offenders = [];
for (const file of files) {
  const src = fs.readFileSync(file, "utf8");
  const re = /(^|[^A-Za-z0-9_.])Text\s*\(/g;
  let m;
  while ((m = re.exec(src))) {
    const openParenAt = m.index + m[0].length - 1;
    const args = callSlice(src, openParenAt);
    if (!BOUNDED.test(args)) continue;
    total++;
    const scrollable = /verticalScroll\s*\(/.test(args);
    // 显式省略号是有意的截断，注释里写清理由即可（`clip-ok` 是给这类情况留的出口）
    const explicit = /TextOverflow\.(Ellipsis|Visible)/.test(args);
    const line = src.slice(0, m.index).split("\n").length;
    const optOut = /clip-ok/.test(args) || /clip-ok/.test(src.slice(Math.max(0, m.index - 400), m.index));
    if (!scrollable && !explicit && !optOut) {
      offenders.push(`${file}:${line}`);
    }
  }
}
ok(total > 0, `扫到 ${total} 处限高的 Text（说明这条规则确实在检查真实代码）`);
ok(
  offenders.length === 0,
  offenders.length === 0
    ? "没有「限高又不给滚动/省略号」的 Text"
    : `这些 Text 限了高却没有滚动或省略号，多出来的行会被静默裁掉（加 verticalScroll，或去掉 heightIn）：\n      ` +
        offenders.join("\n      "),
);

// 反向自检：这条规则必须在真出过问题的地方保持有效 —— 更新提示框的正文不能再被限高
{
  const upd = fs.readFileSync("app/src/main/java/me/bmax/apatch/ui/component/UpdateDialog.kt", "utf8");
  ok(
    !/text\s*=\s*status\.notes[\s\S]{0,400}?heightIn\(\s*max/.test(upd),
    "更新提示框的 release 正文没有再被限高（用户看到的「更新内容显示不全」就是它）",
  );
}

console.log("");
console.log(bad === 0 ? `全部通过（${files.length} 个文件，${total} 处限高文本）` : `${bad} 项失败`);
process.exit(bad === 0 ? 0 : 1);
