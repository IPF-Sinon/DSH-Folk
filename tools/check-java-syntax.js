#!/usr/bin/env node
/**
 * Java 文件里的「Kotlin 式属性访问」检查。
 *
 * 为什么需要：仓库绝大部分是 Kotlin，只有少数几个 .java（TarGzipExtractor 等）—— 于是很容易
 * 顺手把 Kotlin 的属性写法搬进 Java，而 **kt-lint 只检查 .kt**，本地完全发现不了，只能等 CI 的
 * javac 报 `cannot find symbol: variable isFile`。2026-10-25 就为此红了一轮。
 *
 * 检查项（都是 Java 里必须带括号的方法，写成 Kotlin 风格会编译失败）：
 *   File.isFile / isDirectory / isAbsolute / exists / canExecute / listFiles / length
 *   File.absolutePath / name / parentFile（这些在 Java 里是 getName()/getAbsolutePath()/getParentFile()）
 *   String.length（Java 里是 length()）
 *
 * 只报「明显错误」：跳过注释与字符串字面量，避免把文档里的示例当成代码。
 */
const fs = require("fs");
const path = require("path");

const ROOT = path.resolve(__dirname, "..");

/** 位置保留的剥离器：把注释与字符串内容替换成空格，保留换行。 */
function codeOnly(src) {
  let out = "";
  let i = 0;
  const n = src.length;
  let block = 0;
  while (i < n) {
    const c = src[i];
    const c2 = src[i + 1];
    if (block > 0) {
      if (c === "*" && c2 === "/") {
        block--;
        out += "  ";
        i += 2;
        continue;
      }
      out += c === "\n" ? "\n" : " ";
      i++;
      continue;
    }
    if (c === "/" && c2 === "/") {
      while (i < n && src[i] !== "\n") {
        out += " ";
        i++;
      }
      continue;
    }
    if (c === "/" && c2 === "*") {
      block++;
      out += "  ";
      i += 2;
      continue;
    }
    if (c === '"' || c === "'") {
      const q = c;
      out += " ";
      i++;
      while (i < n && src[i] !== q) {
        if (src[i] === "\\") {
          out += "  ";
          i += 2;
          continue;
        }
        out += src[i] === "\n" ? "\n" : " ";
        i++;
      }
      if (i < n) {
        out += " ";
        i++;
      }
      continue;
    }
    out += c;
    i++;
  }
  return out;
}

/** Kotlin 属性名 → Java 里正确的写法（供报错提示用）。 */
const PROPERTIES = {
  isFile: "isFile()",
  isDirectory: "isDirectory()",
  isAbsolute: "isAbsolute()",
  exists: "exists()",
  canExecute: "canExecute()",
  canRead: "canRead()",
  canWrite: "canWrite()",
  listFiles: "listFiles()",
  length: "length()",
  absolutePath: "getAbsolutePath()",
  parentFile: "getParentFile()",
  getName: "getName()",
};

/** 这些类型上的 `.name` 在 Java 里通常是合法的（枚举的 name()、自定义字段），只查 File 语境。 */
function findJavaFiles(dir, acc) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) findJavaFiles(p, acc);
    else if (entry.name.endsWith(".java")) acc.push(p);
  }
  return acc;
}

let bad = 0;
const files = findJavaFiles(path.join(ROOT, "app/src/main/java"), []);
for (const file of files) {
  const src = fs.readFileSync(file, "utf8");
  const code = codeOnly(src);
  const rel = path.relative(ROOT, file);

  // 只看形如 `xxx.isFile`（后面不是 `(`）的用法 —— 即访问了属性而没调用方法
  for (const [prop, correct] of Object.entries(PROPERTIES)) {
    // .length 在 Java 里对数组是合法的，单独放过（数组没有 length()）
    if (prop === "length") continue;
    const re = new RegExp("\\.[ \\t]*" + prop + "\\b(?!\\s*\\()", "g");
    let m;
    while ((m = re.exec(code)) !== null) {
      const line = code.slice(0, m.index).split("\n").length;
      console.error(
        "FAIL  " + rel + ":" + line + "  用了 Kotlin 属性写法 ." + prop + "，Java 里应为 ." + correct,
      );
      bad++;
    }
  }
}

if (bad === 0) {
  console.log("全部通过（" + files.length + " 个 Java 文件，无 Kotlin 属性写法）");
  process.exit(0);
}
console.error("共 " + bad + " 处需要修正");
process.exit(1);
