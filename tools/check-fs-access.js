#!/usr/bin/env node
// 门禁：文件桥（dsh-fs）的黑白名单必须**逐层**生效 —— 入口过名单不代表子项过名单。
//
// 背景（外部审查 P1-1，已逐行核实）：
//  - `resolve()` 只校验**入口**路径；
//  - 递归 list（walk）一直有 pathAllowed 检查，但 **非递归 list** 只判 within，
//    于是被禁目录/文件的名字会被直接列给容器；
//  - `deleteRecursive` / `copyRecursive` 只判 within，入口合法就能把被禁子目录
//    连根复制/删除 —— 「允许的目录里放一个被禁的子目录」就是绕过名单的后门。
// 修法：统一走 policyAllows()；递归复制/删除命中策略时**拒绝**（throw DeniedPathException
// → 外层 403 no_access），而不是悄悄跳过（跳过会让调用方以为整棵都处理完了）。
const fs = require('fs');
const path = require('path');

const errors = [];
const root = path.resolve(__dirname, '..');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');
const must = (c, m) => { if (!c) errors.push(m); };

const src = read('app/src/main/java/me/bmax/apatch/dsh/DshFsBridge.kt');
const slice = (from, to) => {
  const a = src.indexOf(from);
  const b = src.indexOf(to, a + 1);
  return a < 0 || b < 0 ? '' : src.slice(a, b);
};

// ---- 1. 统一的逐层判据 ----
const helper = slice('private fun policyAllows(', '/** 这个 File 的规范路径是否仍落在根内');
must(helper !== '', '缺 policyAllows()（逐层黑白名单判据）');
must(/DshFileAccess\.pathAllowed\(ctx, relativeOf\(f\)\)/.test(helper),
  'policyAllows 没有用 relativeOf + pathAllowed 判名单');

// ---- 2. 四条路径都要用它 ----
const listBody = slice('private fun handleList(', 'private fun handleStat(');
must(/if \(!policyAllows\(f\)\) continue/.test(listBody),
  '非递归 /list 没有逐项过黑白名单（被禁目录/文件的名字会泄露）');

const walkBody = slice('private fun walk(', '/** 条目总数');
must(/if \(!policyAllows\(f\)\) continue/.test(walkBody),
  '递归 walk 丢了黑白名单遮蔽');

const delBody = slice('private fun deleteRecursive(', 'private fun copyRecursive(');
must(/if \(!policyAllows\(target\)\) throw DeniedPathException/.test(delBody),
  'deleteRecursive 没有逐层过名单，或命中时不拒绝（跳过会留下被禁子树）');

const copyBody = slice('private fun copyRecursive(', '// ────────────────────────── 工具');
must(/if \(!policyAllows\(s\) \|\| !policyAllows\(d\)\) throw DeniedPathException/.test(copyBody),
  'copyRecursive 没有逐层过名单（源与目标都要判）');
must(/if \(e is DeniedPathException\) throw e/.test(copyBody),
  'copyRecursive 的 onFailure 会把 DeniedPathException 吞成 500 copy_failed');

// ---- 3. 策略拒绝要能定位到具体路径 ----
must(/private class DeniedPathException\(val rel: String = ""\)/.test(src),
  'DeniedPathException 没带被拒路径（递归时无法定位是哪个子路径）');
must(/throw DeniedPathException\(relativeOf\(target\)\)/.test(src)
  && /throw DeniedPathException\(relativeOf\(s\)\)/.test(src),
  '递归复制/删除抛出时没有带上路径');
must(/Log\.w\(TAG, "文件桥策略拒绝: " \+ e\.rel\)/.test(src),
  '外层没有把被拒路径写进日志（只回一句「该路径被挡住」用户无从判断是哪个）');
must(/throw DeniedPathException\(rel\)/.test(src), 'resolve() 抛出时没有带路径');

// ---- 4. 防漂移：每一处「越过根就 continue」的枚举点，紧跟一条名单检查 ----
const enumSites = src.split('if (!within(f)) continue').length - 1;
const guardedSites = (src.match(
  /if \(!within\(f\)\) continue\n(\s*\/\/[^\n]*\n)*\s*if \(!policyAllows\(f\)\) continue/g,
) || []).length;
must(enumSites >= 2, '找不到枚举点（within 判据被改写？）');
must(enumSites === guardedSites,
  '有 ' + enumSites + ' 处枚举只判 within，只有 ' + guardedSites + ' 处跟了名单检查' +
    '（新加枚举点时别忘了逐层过名单）');

if (errors.length) {
  console.error('check-fs-access FAILED:');
  for (const e of errors) console.error('  ✗ ' + e);
  process.exit(1);
}
console.log('check-fs-access: 通过');
