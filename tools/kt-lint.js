#!/usr/bin/env node
/**
 * Kotlin 源文件的轻量语法体检（提交前跑，用来挡住 CI 才会发现的低级错误）。
 *
 * 为什么需要它：本项目没有本地 JDK/Android SDK，唯一的编译门是 GitHub Actions，
 * 一轮要 4~5 分钟。用一个正确的词法扫描先把「括号不配平 / 注释没闭合」挡住，
 * 比推上去等 CI 报错快得多。
 *
 * 关键点：**Kotlin 的块注释可以嵌套**（`/* /* *​/ *​/`）。所以 KDoc 里出现
 * `@deepseek-ai/*` 这种写法会开一个嵌套注释，把后面整个文件都吃进注释里 ——
 * v1.7.1 第一次 CI 就是这么失败的（报 `Unclosed comment` + 一片
 * `Unresolved reference`）。天真的 `replace(/"..."/g,"")` 式检查看不出来。
 *
 * 用法: node tools/kt-lint.js <文件…>
 * 退出码: 0 = 通过，1 = 有问题
 */
'use strict';

const fs = require('node:fs');

/**
 * 按 Kotlin 词法扫一遍，返回 { brace, paren, comment, errors }。
 *
 * 处理：行注释、可嵌套块注释、普通字符串（含转义）、三引号字符串、字符字面量。
 * 不处理字符串模板里的嵌套大括号 —— 那需要完整解析器；改为把模板表达式整段跳过。
 */
function scan(src) {
  let i = 0;
  let brace = 0;
  let paren = 0;
  let comment = 0; // 块注释嵌套深度
  let line = 1;
  const errors = [];
  const openLines = { comment: [], brace: [], paren: [] };

  const n = src.length;
  while (i < n) {
    const c = src[i];
    const c2 = src[i + 1];

    if (c === '\n') {
      line++;
      i++;
      continue;
    }

    // 块注释（可嵌套）
    if (comment > 0) {
      if (c === '/' && c2 === '*') {
        comment++;
        openLines.comment.push(line);
        i += 2;
        continue;
      }
      if (c === '*' && c2 === '/') {
        comment--;
        openLines.comment.pop();
        i += 2;
        continue;
      }
      i++;
      continue;
    }
    if (c === '/' && c2 === '*') {
      comment++;
      openLines.comment.push(line);
      i += 2;
      continue;
    }
    // 行注释
    if (c === '/' && c2 === '/') {
      while (i < n && src[i] !== '\n') i++;
      continue;
    }
    // 三引号字符串
    if (src.startsWith('"""', i)) {
      i += 3;
      while (i < n && !src.startsWith('"""', i)) {
        if (src[i] === '\n') line++;
        i++;
      }
      i += 3;
      continue;
    }
    // 普通字符串。必须单独处理 `${…}` 模板：里面是真正的 Kotlin 代码，可以有
    // 字符字面量、嵌套字符串和大括号。不理解模板的话，
    // `"  '${p.replace("'", "''")}': true"` 会被误判成「字符串里出现换行」——
    // 模板里的第一个 " 被当成字符串结束，后面的 ' 又被当成字符字面量开头。
    if (c === '"') {
      i++;
      while (i < n) {
        const d = src[i];
        if (d === '\\') { i += 2; continue; }
        if (d === '"') { i++; break; }
        if (d === '\n') {
          errors.push(`line ${line}: 字符串里出现换行（缺右引号？）`);
          break;
        }
        if (d === '$' && src[i + 1] === '{') {
          // 跳到配对的右大括号，期间跳过其中的字符串与字符字面量
          i += 2;
          let depth = 1;
          while (i < n && depth > 0) {
            const e = src[i];
            if (e === '\n') line++;
            else if (e === '{') depth++;
            else if (e === '}') depth--;
            else if (e === '"') {
              i++;
              while (i < n && src[i] !== '"') {
                if (src[i] === '\\') i++;
                i++;
              }
            } else if (e === "'") {
              i++;
              while (i < n && src[i] !== "'") {
                if (src[i] === '\\') i++;
                i++;
              }
            }
            i++;
          }
          continue;
        }
        i++;
      }
      continue;
    }
    // 字符字面量
    if (c === "'") {
      i++;
      while (i < n && src[i] !== "'") {
        if (src[i] === '\\') i++;
        i++;
      }
      i++;
      continue;
    }

    if (c === '{') {
      brace++;
      openLines.brace.push(line);
    } else if (c === '}') {
      brace--;
      openLines.brace.pop();
      if (brace < 0) errors.push(`line ${line}: 多出一个 }`);
    } else if (c === '(') {
      paren++;
      openLines.paren.push(line);
    } else if (c === ')') {
      paren--;
      openLines.paren.pop();
      if (paren < 0) errors.push(`line ${line}: 多出一个 )`);
    }
    i++;
  }

  if (comment > 0) {
    errors.push(
      `块注释未闭合（深度 ${comment}），最内层开始于 line ${openLines.comment[openLines.comment.length - 1]}。` +
        `注意 Kotlin 块注释可嵌套：KDoc 里写 "/*" 会开一个新注释`,
    );
  }
  if (brace !== 0) errors.push(`大括号不配平: ${brace > 0 ? '缺 ' + brace + ' 个 }' : '多 ' + -brace + ' 个 }'}`);
  if (paren !== 0) errors.push(`圆括号不配平: ${paren > 0 ? '缺 ' + paren + ' 个 )' : '多 ' + -paren + ' 个 )'}`);

  return { brace, paren, comment, errors };
}

function main() {
  const files = process.argv.slice(2);
  if (files.length === 0) {
    console.error('用法: node tools/kt-lint.js <文件…>');
    process.exit(1);
  }
  let bad = 0;
  for (const f of files) {
    let src;
    try {
      src = fs.readFileSync(f, 'utf8');
    } catch (e) {
      console.error(`READ_FAIL ${f}: ${e.message}`);
      bad++;
      continue;
    }
    const r = scan(src);
    if (r.errors.length === 0) {
      console.log(`OK    ${f}`);
    } else {
      bad++;
      console.error(`FAIL  ${f}`);
      for (const e of r.errors) console.error(`        ${e}`);
    }
  }
  console.log(bad === 0 ? `全部通过（${files.length} 个文件）` : `${bad} 个文件有问题`);
  process.exit(bad === 0 ? 0 : 1);
}

main();
