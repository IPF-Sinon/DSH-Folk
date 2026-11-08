#!/usr/bin/env node
// CI 门禁：workflow 里的 gh 调用必须有仓库上下文。
//
// 2026-11-08 的事故：runtime-auto.yml 每 6 小时跑一次，**连续三次全失败**，
// 而且失败方式极其安静 —— 
//   - probe 里 `gh release view ... || echo ""` 把 `fatal: not a git repository` 吞了，
//     于是 last 永远读到 none，每轮都判定"需要构建"；
//   - dispatch 里 `gh workflow run` 直接以同一个错误退出。
// 结果：上游 0.2.2-rc.1 发了 12 天，自动构建一次都没成功，日志里只有一行
// `failed to run git: fatal: not a git repository`。
//
// 根因：**没有 checkout 的 job 里，gh 无法从 .git/config 推断要操作哪个仓库**。
// 修法是给该 step 加 `GH_REPO: ${{ github.repository }}`。
//
// 本脚本就查这一件事：任何用到 gh 的 job，必须满足其一
//   1) 该 job 里出现过 actions/checkout；或
//   2) 用到 gh 的那个 step 设了 GH_REPO。
//
// 为什么不用 "gh 命令 + GH_REPO 全局检查"：一个 workflow 里通常只有部分 job 用 gh，
// 按 job 粒度判才不会逼着所有 step 都写一遍。

const fs = require("node:fs");
const path = require("node:path");

const ROOT = path.resolve(__dirname, "..");
const WF_DIR = path.join(ROOT, ".github", "workflows");

let bad = 0;
let checked = 0;

function jobBlocks(src) {
  // 只做**行级**切分：以 "  <name>:" 且缩进 2 的行开头视为一个 job，
  // 直到下一个同缩进的非空行。不引 yaml 依赖（仓库里没有，CI 里也不想多装一个）。
  const lines = src.split("\n");
  const starts = [];
  let inJobs = false;
  for (let i = 0; i < lines.length; i++) {
    if (/^jobs:\s*$/.test(lines[i])) { inJobs = true; continue; }
    if (!inJobs) continue;
    // 顶层 key（缩进 0）出现即 jobs 段结束
    if (/^[A-Za-z_]/.test(lines[i]) && !lines[i].startsWith("jobs:")) { inJobs = false; continue; }
    if (/^  [A-Za-z_][A-Za-z0-9_-]*:\s*$/.test(lines[i])) starts.push(i);
  }
  const blocks = [];
  for (let k = 0; k < starts.length; k++) {
    const from = starts[k];
    const to = k + 1 < starts.length ? starts[k + 1] : lines.length;
    blocks.push({ name: lines[from].trim().replace(":",""), text: lines.slice(from, to).join("\n") });
  }
  return blocks;
}

const files = fs.readdirSync(WF_DIR).filter((f) => f.endsWith(".yml") || f.endsWith(".yaml"));
for (const f of files) {
  const src = fs.readFileSync(path.join(WF_DIR, f), "utf8");
  for (const job of jobBlocks(src)) {
    if (!/\bgh \b|\bgh\s+(release|workflow|api|run|pr)\b/.test(job.text)) continue;
    checked++;
    const hasCheckout = /uses:\s*actions\/checkout/.test(job.text);
    if (hasCheckout) continue;
    // 没有 checkout：每个含 gh 的 step 都必须带 GH_REPO
    const stepRe = /^\s*- name:.*$/gm;
    const idx = [];
    let m;
    while ((m = stepRe.exec(job.text)) !== null) idx.push(m.index);
    let jobBad = false;
    for (let k = 0; k < idx.length; k++) {
      const seg = job.text.slice(idx[k], k + 1 < idx.length ? idx[k + 1] : job.text.length);
      if (!/\bgh\s/.test(seg)) continue;
      if (!/GH_REPO:\s*\$\{\{\s*github\.repository\s*\}\}/.test(seg)) {
        const nm = (seg.match(/- name:\s*(.*)$/m) || [])[1] || "(unnamed step)";
        console.log(`gh: ${f} → job "${job.name}" 的 step "${nm.trim()}" 用了 gh 但既没有 checkout 也没有 GH_REPO（会报 fatal: not a git repository）`);
        jobBad = true;
      }
    }
    if (jobBad) bad++;
  }
}

if (bad > 0) {
  console.log(`\n${bad} 个 CI job 缺少仓库上下文`);
  process.exit(1);
}
console.log(`CI workflow 的 gh 调用都有仓库上下文（检查 ${checked} 个含 gh 的 job）`);
