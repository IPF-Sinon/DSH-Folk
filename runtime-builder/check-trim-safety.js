#!/usr/bin/env node
/**
 * 精简版运行时的**删除安全性**断言。
 *
 * 背景：slim 口味会删掉 node_modules 里的类型声明（*.d.ts / *.d.mts / *.d.cts）、
 * sourcemap（*.map）、文档（*.md）与 test/docs/example 目录。判据是「删掉之后运行时
 * 还能不能起来」，而唯一的权威判据是：
 *
 *   **有没有任何 Node 运行时会解析到的入口，指向这些将被删掉的文件。**
 *
 * 只写一次分析结论、然后永远相信它是不行的：dsh 换个版本就可能把某个包的入口指到
 * 别的扩展名上，那时删除会静默地把运行时搞坏 —— 用户拿到一份起不来的 rootfs。
 * 所以把判据固化成脚本，每次 slim 构建都跑一遍。
 *
 * 条件集合的处理是这里的要点：
 *  - Node 真正会用的条件只有 `node` / `import` / `require` / `default` /
 *    `node-addons`，再加裸字符串形式的 exports、以及 `main` / `bin`。只有这些
 *    指向待删文件才算不安全。
 *  - `types` / `typings` 以及键名里带 types 的子条件（`types.require` 这种）是给
 *    TS 编译器看的，跳过。
 *  - `source` / `@zod/source` 这类自定义条件（zod、eventsource、standard-schema
 *    等包用它指向 src/*.ts）只有显式 `--conditions=source` 才会被解析，本运行时任何
 *    地方都不带这个开关，所以同样跳过 —— 但它会被计数并打印，让这个边界可见，
 *    而不是被悄悄忽略。
 *
 * 用法: node check-trim-safety.js <node_modules 目录> [--quiet]
 * 退出码: 0 = 可以安全删除，1 = 有入口指向待删文件，2 = 用法错误
 */
'use strict';

const fs = require('node:fs');
const path = require('node:path');

/** Node 真正会解析的 exports 条件。`module-sync` 是 Node 22+ 的 require(esm) 条件。 */
const NODE_CONDITIONS = new Set(['node', 'import', 'require', 'default', 'node-addons', 'module-sync']);

/** 会被 slim 删掉的扩展名（以路径结尾判断）。 */
const DOOMED_EXT = /\.(ts|mts|cts|map|md)$/;

/** 会被 slim 删掉的目录名。 */
const DOOMED_DIR = /(^|\/)(test|tests|__tests__|example|examples|docs)\//;

/**
 * 递归 exports。`conds` 是**条件栈**，子路径键（`"."` / `"./client"`）不进栈 ——
 * 它们是路径分组，不是条件；把两者混起来会把每个包都误判成「自定义条件」。
 */
function walkExports(node, conds, out) {
  if (typeof node === 'string') {
    if (conds.some((c) => c === 'types' || c.endsWith('types'))) {
      out.types++;
      return;
    }
    if (conds.some((c) => !NODE_CONDITIONS.has(c))) {
      out.custom.push([conds.join('.') || '(custom)', node]);
      return;
    }
    out.entries.push([conds.join('.') || '.', node]);
    return;
  }
  // exports 允许是**数组**（fallback 数组：Node 按顺序取第一个能用的）。数组元素是
  // 同类目标、不是条件 —— 当成对象会把下标 "0"/"1" 变成假条件。
  if (Array.isArray(node)) {
    for (const v of node) walkExports(v, conds, out);
    return;
  }
  if (!node || typeof node !== 'object') return;
  const keys = Object.keys(node);
  const isSubpathMap = keys.length > 0 && keys.every((k) => k.startsWith('.'));
  for (const k of keys) walkExports(node[k], isSubpathMap ? conds : [...conds, k], out);
}

/** 收集一个包的「Node 运行时会解析的入口」。 */
function runtimeEntriesOf(pkg) {
  const out = { entries: [], types: 0, custom: [] };
  for (const key of ['main', 'bin']) {
    const v = pkg[key];
    if (typeof v === 'string') out.entries.push([key, v]);
    else if (v && typeof v === 'object') {
      for (const [k, p] of Object.entries(v)) if (typeof p === 'string') out.entries.push([`${key}:${k}`, p]);
    }
  }
  if (pkg.exports !== undefined) walkExports(pkg.exports, [], out);
  return out;
}

function walkPackages(root, onPackage) {
  const stack = [[root, 0]];
  while (stack.length) {
    const [dir, depth] = stack.pop();
    if (depth > 8) continue;
    let ents;
    try {
      ents = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const e of ents) {
      const p = path.join(dir, e.name);
      if (e.isDirectory()) {
        if (e.name === '.git') continue;
        stack.push([p, depth + 1]);
      } else if (e.name === 'package.json') {
        let pkg;
        try {
          pkg = JSON.parse(fs.readFileSync(p, 'utf8'));
        } catch {
          continue;
        }
        if (pkg && pkg.name) onPackage(pkg);
      }
    }
  }
}

function main() {
  const positional = process.argv.slice(2).filter((a) => !a.startsWith('--'));
  const quiet = process.argv.includes('--quiet');
  const root = positional[0];
  if (!root || !fs.existsSync(root)) {
    console.error('用法: node check-trim-safety.js <node_modules 目录> [--quiet]');
    process.exit(2);
  }

  const offenders = [];
  let packages = 0;
  let entries = 0;
  let types = 0;
  let custom = 0;

  walkPackages(root, (pkg) => {
    packages++;
    const r = runtimeEntriesOf(pkg);
    types += r.types;
    custom += r.custom.length;
    for (const [label, target] of r.entries) {
      entries++;
      const rel = String(target).replace(/^\.\//, '');
      if (DOOMED_EXT.test(rel) || DOOMED_DIR.test(rel)) {
        offenders.push(`${pkg.name} [${label}] → ${rel}`);
      }
    }
  });

  if (offenders.length) {
    console.error(`!! 有 ${offenders.length} 个 Node 运行时入口指向 slim 将要删除的文件：`);
    for (const o of offenders.slice(0, 40)) console.error(`     ${o}`);
    console.error('   精简会把运行时弄坏：要么调整删除范围，要么先看懂这些包的新入口。');
    process.exit(1);
  }

  if (!quiet) {
    console.log(
      `    删除安全性 ✓ 扫描 ${packages} 个包 / ${entries} 个运行时入口，` +
        `无一指向 .d.ts/.map/.md 或 test/docs/example` +
        `（跳过类型入口 ${types} 个、自定义条件入口 ${custom} 个）`,
    );
  }
  process.exit(0);
}

main();
