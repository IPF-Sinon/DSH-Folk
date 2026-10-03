// 门禁：Compose 的 Row / Column / LazyRow / LazyColumn 不许漏掉 content lambda。
//
// 为什么单独写这一条：`Row(modifier = ...)` 这种写法**编译不过**
// （content 是最后一个参数、没有默认值 → "No value passed for parameter 'content'"），
// 而它看上去完全正常。本机没有 Android SDK/JDK，这类错误只能等 CI 编译（约 3 分钟一轮）。
//
// **`Box` 刻意不在名单里**：foundation-layout 给 Box 专门留了一个"无内容"重载
//
//     /** A box with no content that can participate in layout, drawing, pointer input ... */
//     @Composable public fun Box(modifier: Modifier)
//
// 所以 `Box(modifier = ...)` 是合法的（本仓库另有 8 处这么用，CI 一直编得过）。
// Row / Column 没有对应重载：它们的 `content` 是唯一且无默认值的必填参数。
// 第一版把 Box 也纳进来，于是报了 8 条误报 —— 一条动辄误报的门禁等于没有门禁。
//
// 判据只认**确定**的形状，不做类型推断：
//   - 调用行以 `Row(` / `Column(` / `LazyRow(` / `LazyColumn(` 结尾（即参数从下一行开始）；
//   - 从该行起按括号配平找到配对的 `)`；
//   - 那个 `)` 之后的第一个非空白字符**必须是 `{`**（trailing lambda）。
//
// 已知不判的情形（宁可漏报也不误报）：单行调用（同行就有 `)`），
// 以及参数里带 `content =` / `children =` 具名值的写法。
const fs = require("fs");

const FILES = process.argv.slice(2);
const CONTAINERS = ["Row", "Column", "LazyRow", "LazyColumn"];

let bad = 0;
let scanned = 0;

/** 去掉注释与字符串字面量，避免把注释里的 `(`/`)` 算进配平。
 *
 * **必须保留换行**：跳过的片段里有多少个换行就补回多少个 —— 否则后面算出来的行号
 * 会比真实位置偏前（本门禁第一版就是这么错的，报出来的位置全对不上，白白怀疑了 8 处）。
 */
function strip(src) {
  let out = "";
  let i = 0;
  const keepNewlines = (s) => "\n".repeat((s.match(/\n/g) || []).length);
  while (i < src.length) {
    const two = src.slice(i, i + 2);
    if (two === "//") {
      const nl = src.indexOf("\n", i);
      i = nl < 0 ? src.length : nl;
      continue;
    }
    if (two === "/*") {
      const end = src.indexOf("*/", i + 2);
      const stop = end < 0 ? src.length : end + 2;
      out += keepNewlines(src.slice(i, stop));
      i = stop;
      continue;
    }
    const ch = src[i];
    if (ch === '"' || ch === "'") {
      // 跳过字符串（含三引号）；模板串里的 ${...} 不深究，只求括号配平不被字符串里的括号干扰
      let stop;
      if (src.startsWith('"""', i)) {
        const end = src.indexOf('"""', i + 3);
        stop = end < 0 ? src.length : end + 3;
      } else {
        let j = i + 1;
        while (j < src.length) {
          if (src[j] === "\\") { j += 2; continue; }
          if (src[j] === ch) { j++; break; }
          j++;
        }
        stop = j;
      }
      out += keepNewlines(src.slice(i, stop));
      i = stop;
      continue;
    }
    out += ch;
    i++;
  }
  return out;
}

for (const f of FILES) {
  const raw = fs.readFileSync(f, "utf8");
  const src = strip(raw);
  // 原始行号映射：strip 不改行数（换行原样保留）
  scanned++;
  for (const name of CONTAINERS) {
    const re = new RegExp("(^|[^\\w.])" + name + "\\(\\s*\\n", "g");
    let m;
    while ((m = re.exec(src))) {
      // 从 `(` 开始配平
      const open = src.indexOf("(", m.index);
      let depth = 0;
      let i = open;
      for (; i < src.length; i++) {
        const c = src[i];
        if (c === "(") depth++;
        else if (c === ")") {
          depth--;
          if (depth === 0) break;
        }
      }
      if (depth !== 0 || i >= src.length) continue; // 配平失败：交给编译期
      const closeLine = src.slice(0, i).split("\n").length;
      const lineText = src.split("\n")[closeLine - 1] ?? "";
      // 具名 content / children：合法，跳过
      const block = src.slice(open, i);
      if (/(^|[\s(])content\s*=|children\s*=/.test(block)) continue;
      const after = src.slice(i + 1).match(/^\s*([^\s])/);
      const next = after ? after[1] : "";
      if (next !== "{") {
        console.log(`✗ ${f}:${closeLine} ${name}(...) 没有 content lambda（Kotlin: No value passed for parameter 'content'）`);
        console.log(`     ${lineText.trim().slice(0, 80)}`);
        bad++;
      }
    }
  }
}

if (bad === 0) {
  console.log(`✓ Compose 容器的 content lambda 齐全（${scanned} 个文件）`);
} else {
  console.log(`\n${bad} 处缺 content lambda`);
  process.exit(1);
}
