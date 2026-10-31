#!/usr/bin/env node
// 门禁：Agent Teams（spawn_teammate）子会话文件「readdir 有、open ENOENT」的修复不许被改坏。
//
// 根因：dsh-session-persistence-jsonl 的 publishCurrentExclusive 用 link() 发布会话文件；
// proroot 的 --link2symlink 把 link 模拟成「锚点 + 符号链接组」，锚点被清理后目标变悬空。
// 修法：该处改用同目录 rename 发布（目标若是悬空链接则先删）。
const fs = require('fs');
const path = require('path');
const root = path.resolve(__dirname, '..');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');
const ok = (c, m) => { if (!c) { console.error('  ✗ ' + m); failures++; } };
let failures = 0;

const runtime = read('app/src/main/java/me/bmax/apatch/dsh/DshRuntime.kt');

ok(/private fun patchSessionPersistence\(\)/.test(runtime), '有 patchSessionPersistence()');
ok(/^        patchSessionPersistence\(\)$/m.test(runtime), 'startServer 里真的调用（只定义不调用=永不生效）');
ok(/dsh-session-persistence-jsonl\/lib\/index\.js/.test(runtime), '补丁打在会话持久化包上');
ok(/realpath, rename, rm, stat, truncate/.test(runtime),
  'import 里补上 rename（否则 rename 未定义）');
ok(/await internals\.fs\.link\(staged, currentPath\);/.test(runtime), 'v1 定位的原文仍在（改前的锚点）');
ok(/await rename\(staged, currentPath\);/.test(runtime), '改为 rename 发布');
ok(/existing\.isSymbolicLink\(\)/.test(runtime) && /await internals\.fs\.rm\(currentPath\);/.test(runtime),
  '目标若是悬空符号链接，先删掉再 rename（否则旧悬空项会一直挡住）');
ok(/before != 1/.test(runtime) && /持久化补丁不完整，未写回/.test(runtime),
  '片段不是恰好 1 次就不写回');
ok(/private fun sessionStoreDeepDiag\(\)/.test(runtime), '有 sessionStoreDeepDiag() 体检（两层：sessions/<cwd>/<id>/file）');
ok(/^        sessionStoreDeepDiag\(\)$/m.test(runtime), 'startServer 里调用体检');
ok(/danglingRemoved/.test(runtime) && /locks=/.test(runtime),
  '体检统计文件/锁/符号链接/悬空项（可判定是链接问题还是根本没有文件）');
ok(/private fun patchAgentTeamDiag\(\)/.test(runtime) && /^        patchAgentTeamDiag\(\)$/m.test(runtime),
  '有并调用 Agent Teams 诊断补丁');
ok(/\[DSH-Folk team\] spawn failed/.test(runtime), '失败时把 AggregateError 的成员完整打进日志');
ok(/\[session\] /.test(runtime), '体检与补丁都用 [session] 前缀落日志');

if (failures > 0) { console.error('check-session-store FAILED: ' + failures + ' 项'); process.exit(1); }
console.log('check-session-store: 通过');
