#!/usr/bin/env node
/**
 * 检查 rootfs 里一组入口程序的动态库依赖是否闭合。
 *
 * 为什么需要这个：build-rootfs.sh 是用 `dpkg-deb -x` 纯解包装东西的（不能跑
 * maintainer script —— runner 是 x86_64，包是 arm64），所以 dpkg 的依赖关系
 * **完全没人替我们解**。包列表是手写的，写漏一个传递依赖，构建期一切正常，
 * 到手机上才在 exec 的那一刻报 `cannot find libxxx.so.N`。
 *
 * v1.5 就这么翻过车：git 本体只链 libpcre2/libz/libc，装完看着好用；而
 * `git-remote-https`（pnpm 走 https 克隆真正 exec 的那个）链 libcurl-gnutls，
 * 它自己又要 8 个库 —— 一个都没打包。结果目录里 51% 的 `github:` 插件全装不上。
 * 当时的自检只验了「文件存在 + 是 aarch64 ELF」，这两条都通过了。
 *
 * 所以这里做的是真闭包：从入口出发递归解析 ELF 的 DT_NEEDED，凡是在 rootfs
 * 里找不到提供者的 SONAME 就报错并指出谁需要它。
 *
 * 纯 Node，无外部依赖（CI 上 readelf 未必有，且它读的是 host 的 ELF 工具链，
 * 自己解析 aarch64 ELF 反而更省事）。
 *
 * 用法: node check-elf-closure.js <rootfs 目录> [入口 glob 前缀…]
 * 退出码: 0 = 闭合，1 = 有缺失，2 = 用法/环境错误
 */
'use strict';

const fs = require('node:fs');
const path = require('node:path');

/** 解析 ELF64 的 .dynamic，取 DT_NEEDED 列表与 DT_SONAME。 */
function readElf(file) {
  let b;
  try {
    b = fs.readFileSync(file);
  } catch {
    return null;
  }
  // 4 字节 magic + 至少一个完整 ELF64 头
  if (b.length < 64 || b.readUInt32BE(0) !== 0x7f454c46) return null;
  if (b[4] !== 2) return null; // EI_CLASS: 只处理 ELF64（本项目只有 aarch64）
  const le = b[5] === 1;
  const u16 = (o) => (le ? b.readUInt16LE(o) : b.readUInt16BE(o));
  const u32 = (o) => (le ? b.readUInt32LE(o) : b.readUInt32BE(o));
  const u64 = (o) => Number(le ? b.readBigUInt64LE(o) : b.readBigUInt64BE(o));

  const machine = u16(0x12); // EM_AARCH64 = 183
  const shoff = u64(0x28);
  const shentsize = u16(0x3a);
  const shnum = u16(0x3c);
  if (shoff <= 0 || shnum === 0 || shoff + shnum * shentsize > b.length) {
    return { machine, needed: [], soname: null };
  }

  const secs = [];
  for (let i = 0; i < shnum; i++) {
    const o = shoff + i * shentsize;
    secs.push({
      type: u32(o + 4),
      off: u64(o + 0x18),
      size: u64(o + 0x20),
      link: u32(o + 0x28),
    });
  }

  // SHT_DYNAMIC = 6；它的 sh_link 指向对应的 .dynstr
  const dyn = secs.find((s) => s.type === 6);
  if (!dyn) return { machine, needed: [], soname: null };
  const dynstr = secs[dyn.link];
  if (!dynstr) return { machine, needed: [], soname: null };

  const str = (off) => {
    const start = dynstr.off + off;
    if (start >= b.length) return '';
    let end = start;
    while (end < b.length && b[end] !== 0) end++;
    return b.toString('utf8', start, end);
  };

  const needed = [];
  let soname = null;
  for (let o = dyn.off; o + 16 <= dyn.off + dyn.size && o + 16 <= b.length; o += 16) {
    const tag = u64(o);
    const val = u64(o + 8);
    if (tag === 0) break; // DT_NULL
    if (tag === 1) needed.push(str(val)); // DT_NEEDED
    if (tag === 14) soname = str(val); // DT_SONAME
  }
  return { machine, needed, soname };
}

/** 递归收集目录下所有 ELF 文件（相对 root 的路径）。 */
function collectElf(root, rel, out) {
  const abs = path.join(root, rel);
  let ents;
  try {
    ents = fs.readdirSync(abs, { withFileTypes: true });
  } catch {
    return out;
  }
  for (const e of ents) {
    const r = path.join(rel, e.name);
    if (e.isSymbolicLink()) continue; // 软链接指向的实体本身会被扫到
    if (e.isDirectory()) {
      collectElf(root, r, out);
      continue;
    }
    if (!e.isFile()) continue;
    if (readElf(path.join(root, r))) out.push(r);
  }
  return out;
}

function main() {
  const root = process.argv[2];
  if (!root || !fs.existsSync(root)) {
    console.error('用法: node check-elf-closure.js <rootfs 目录> [额外入口目录…]');
    process.exit(2);
  }

  // 库搜索路径。加 sasl2 / perl 的私有目录：它们是 dlopen 出来的插件目录，
  // 不在标准搜索路径上，但里面的 .so 同样会拉依赖。
  const libDirs = [
    'usr/lib/aarch64-linux-gnu',
    'lib/aarch64-linux-gnu',
    'usr/lib',
    'lib',
    'usr/local/lib',
    'usr/lib/git-core',
    'usr/lib/aarch64-linux-gnu/sasl2',
    'usr/lib/aarch64-linux-gnu/perl',
    'usr/lib/python3.12/lib-dynload',
  ];

  // SONAME → 路径。文件名也一并登记：少数库没写 DT_SONAME，
  // 而依赖方是按文件名（libdb-5.3.so 这种）引用的。
  const provide = new Map();
  for (const d of libDirs) {
    for (const rel of collectElf(root, d, [])) {
      const info = readElf(path.join(root, rel));
      if (!info) continue;
      if (info.soname && !provide.has(info.soname)) provide.set(info.soname, rel);
      const base = path.basename(rel);
      if (!provide.has(base)) provide.set(base, rel);
    }
  }
  // 软链接形式的 SONAME（libfoo.so.1 → libfoo.so.1.2.3）也要能查到
  for (const d of libDirs) {
    let ents;
    try {
      ents = fs.readdirSync(path.join(root, d), { withFileTypes: true });
    } catch {
      continue;
    }
    for (const e of ents) {
      if (!e.isSymbolicLink()) continue;
      if (provide.has(e.name)) continue;
      const target = path.join(root, d, e.name);
      if (readElf(target)) provide.set(e.name, path.join(d, e.name));
    }
  }

  // 入口：命令行给的目录 + 默认那几处
  const entryDirs = process.argv.slice(3);
  const entryFiles = ['usr/bin/git', 'usr/bin/perl', 'usr/bin/python3.12'];
  const entries = [];
  for (const d of entryDirs.length ? entryDirs : ['usr/lib/git-core', 'usr/lib/aarch64-linux-gnu/perl']) {
    collectElf(root, d, entries);
  }
  for (const f of entryFiles) {
    if (readElf(path.join(root, f))) entries.push(f);
  }

  if (entries.length === 0) {
    console.error('!! 没找到任何入口 ELF，检查 rootfs 路径与入口目录');
    process.exit(2);
  }

  // ld.so 由内核/加载器提供，不在包里；proot 自己也会注入
  const ignore = new Set([
    'ld-linux-aarch64.so.1',
    'linux-vdso.so.1',
    'ld-linux-x86-64.so.2',
  ]);

  const seen = new Set();
  const missing = new Map(); // SONAME → 第一个需要它的东西
  const queue = [];
  const wrongArch = [];
  for (const e of entries) {
    const info = readElf(path.join(root, e));
    if (info.machine !== 183) wrongArch.push(`${e} (e_machine=${info.machine})`);
    for (const n of info.needed) queue.push([n, e]);
  }

  while (queue.length) {
    const [so, from] = queue.shift();
    if (seen.has(so) || ignore.has(so)) continue;
    seen.add(so);
    const p = provide.get(so);
    if (!p) {
      if (!missing.has(so)) missing.set(so, from);
      continue;
    }
    const info = readElf(path.join(root, p));
    if (info) for (const n of info.needed) queue.push([n, so]);
  }

  console.log(`    入口 ELF ${entries.length} 个，解析 SONAME ${seen.size} 个`);
  if (wrongArch.length) {
    console.error('!! 以下入口不是 aarch64:');
    for (const w of wrongArch) console.error(`     ${w}`);
    process.exit(1);
  }
  if (missing.size === 0) {
    console.log('    动态库依赖闭合 ✓');
    process.exit(0);
  }
  console.error(`!! 缺少 ${missing.size} 个动态库（会在手机上 exec 时才报 cannot find）:`);
  for (const [so, from] of missing) {
    console.error(`     ${so.padEnd(28)} ← ${from} 需要`);
  }
  console.error('   把提供这些 SONAME 的包加进 build-rootfs.sh 的包列表');
  process.exit(1);
}

main();
