#!/usr/bin/env node
/*
 * 检查 Kotlin 源文件的词法状态是否「闭合」。
 *
 * 为什么需要：Kotlin 的块注释**可以嵌套**（一个块注释里再出现起始标记就会再开一层）。
 * 于是在 KDoc 里随手写一个包含起始标记的文本（比如 `@deepseek-ai/` 后面跟一个星号
 * 这种作用域通配写法）就会打开一层嵌套注释，而那行 KDoc 自己的收尾标记只关掉内层 ——
 * 外层一直开着，**把它后面所有代码都吃掉**。编译器的报错会指向别处（一片
 * 「unresolved reference」），完全指不到那一行注释，排查代价极高（本项目真的踩过一次）。
 *
 * 这个检查器用一个显式状态栈逐字符遍历：代码 / 行注释 / 块注释（记录嵌套深度）/
 * 普通字符串（含 `${...}` 模板里嵌套的字符串与花括号）/ 原始字符串 / 字符字面量。
 * 结束时若栈里还留着注释或字符串帧，就说明有东西没闭合，报出起始行号。
 *
 * 用法：node tools/check-kotlin-comments.js [目录...]（默认扫 app/src/main/java）
 * 退出码 0 = 全部闭合；1 = 有问题。
 */
const fs = require('fs');
const path = require('path');

const scanRoots = process.argv.slice(2);
const roots = scanRoots.length ? scanRoots : ['app/src/main/java'];

function collectKotlin(dir, out = []) {
    let entries;
    try {
        entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
        return out;
    }
    for (const e of entries) {
        const p = path.join(dir, e.name);
        if (e.isDirectory()) collectKotlin(p, out);
        else if (e.name.endsWith('.kt') || e.name.endsWith('.kts')) out.push(p);
    }
    return out;
}

/** 逐字符扫描一个文件，返回词法问题（空数组 = 干净）。 */
function scan(file) {
    const src = fs.readFileSync(file, 'utf8');
    const problems = [];
    // 帧：code（模板表达式里也用它，brace 记未闭合的 { 数）/ line / block / string / raw
    const stack = [{ kind: 'code', brace: 0, line: 1 }];
    let i = 0;
    let line = 1;

    const top = () => stack[stack.length - 1];
    const push = (frame) => stack.push({ ...frame, line });
    const pop = () => stack.pop();

    while (i < src.length) {
        const c = src[i];
        const next = src[i + 1];
        const frame = top();

        switch (frame.kind) {
            case 'line': {
                if (c === '\n') pop();
                break;
            }
            case 'block': {
                if (c === '/' && next === '*') {
                    frame.depth++;
                    i += 2;
                    continue;
                }
                if (c === '*' && next === '/') {
                    frame.depth--;
                    if (frame.depth === 0) pop();
                    i += 2;
                    continue;
                }
                break;
            }
            case 'raw': {
                // 原始字符串：里面一切照收，直到收尾的三引号
                if (src.startsWith('"""', i)) {
                    pop();
                    i += 3;
                    continue;
                }
                // 原始字符串里也可能有模板表达式
                if (c === '$' && next === '{') {
                    push({ kind: 'code', brace: 0 });
                    i += 2;
                    continue;
                }
                break;
            }
            case 'string': {
                if (c === '\\') {
                    if (src[i + 1] === '\n') line++;
                    i += 2;
                    continue;
                }
                if (c === '\n') {
                    problems.push(`第 ${frame.line} 行：字符串在行尾未闭合`);
                    pop();
                    continue;
                }
                if (c === '"') {
                    pop();
                    i++;
                    continue;
                }
                // `${...}`：花括号里是代码，可以再嵌套字符串与花括号
                if (c === '$' && next === '{') {
                    push({ kind: 'code', brace: 0 });
                    i += 2;
                    continue;
                }
                break;
            }
            case 'code': {
                if (c === '/' && next === '/') {
                    push({ kind: 'line' });
                    i += 2;
                    continue;
                }
                if (c === '/' && next === '*') {
                    push({ kind: 'block', depth: 1 });
                    i += 2;
                    continue;
                }
                if (src.startsWith('"""', i)) {
                    push({ kind: 'raw' });
                    i += 3;
                    continue;
                }
                if (c === '"') {
                    push({ kind: 'string' });
                    i++;
                    continue;
                }
                if (c === "'") {
                    // 字符字面量：不跨行，支持转义
                    let j = i + 1;
                    let closed = false;
                    while (j < src.length && src[j] !== '\n') {
                        if (src[j] === '\\') {
                            j += 2;
                            continue;
                        }
                        if (src[j] === "'") {
                            closed = true;
                            j++;
                            break;
                        }
                        j++;
                    }
                    if (!closed) {
                        problems.push(`第 ${line} 行：字符字面量未闭合`);
                        i++;
                        continue;
                    }
                    if (src.slice(i, j).includes('\n')) line++;
                    i = j;
                    continue;
                }
                if (c === '{') {
                    frame.brace++;
                    i++;
                    continue;
                }
                if (c === '}') {
                    // 回到字符串模板时（brace 归零且不是最外层 code 帧）弹栈
                    if (frame.brace > 0) frame.brace--;
                    else if (stack.length > 1) pop();
                    i++;
                    continue;
                }
                break;
            }
        }

        if (c === '\n') line++;
        i++;
    }

    const dangling = stack.filter((f) => f.kind !== 'code');
    for (const f of dangling) {
        if (f.kind === 'block') {
            problems.push(
                `第 ${f.line} 行开始的块注释没有闭合（嵌套层数 ${f.depth}）—— ` +
                    '注释体里很可能出现了多余的块注释起始标记（Kotlin 块注释可嵌套，会吞掉后面的代码）',
            );
        } else if (f.kind === 'string') {
            problems.push(`第 ${f.line} 行开始的字符串没有闭合`);
        } else if (f.kind === 'raw') {
            problems.push(`第 ${f.line} 行开始的原始字符串（三引号）没有闭合`);
        } else if (f.kind === 'line') {
            problems.push(`第 ${f.line} 行的行注释状态没有结束（不应发生）`);
        }
    }
    return problems;
}

const files = roots.flatMap((r) => collectKotlin(r));
if (files.length === 0) {
    console.log('没有找到 Kotlin 文件（检查路径是否正确）');
    process.exit(1);
}

let bad = 0;
for (const f of files) {
    const problems = scan(f);
    if (problems.length) {
        bad++;
        console.log(`✗ ${f}`);
        for (const p of problems) console.log(`    ${p}`);
    }
}

if (bad) {
    console.log(`\n${bad} 个文件有词法问题`);
    process.exit(1);
}
console.log(`✓ ${files.length} 个 Kotlin 文件词法闭合（字符串 / 模板 / 注释均未泄漏）`);
