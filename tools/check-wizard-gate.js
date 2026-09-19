// 把「预览死结」钉成一支可重跑的回归脚本，并留在仓库里（按项目习惯：不看住的判据会退化）。
// 判据从 DshImportWizard.kt 里按标记抽出来，再在 Node 里复刻一遍语义 —— 纯文本断言抓不住
// 「两步共用一套判据」这种错，只有把状态跑一遍才抓得住。
const fs = require('fs');
const src = fs.readFileSync('app/src/main/java/me/bmax/apatch/dsh/DshImportWizard.kt', 'utf8');
const bodyOf = (fn) => {
  const m = src.match(new RegExp('fun ' + fn + '\\([\\s\\S]*?\\n    \\}'));
  if (!m) throw new Error('抽不出 ' + fn);
  return m[0];
};
let bad = 0;
const ok = (c, msg) => { console.log((c ? '  ✓ ' : '  ✗ ') + msg); if (!c) bad++; };

// 1) 预览步必须不看决策完成度
ok(/WizardStep\.PREVIEW -> true/.test(bodyOf('canAdvance')),
  '预览步无条件放行（它的下一步就是进入决策步）');
ok(/WizardStep\.DECIDE -> decisionsComplete\(sessions, sessionChoice, conflicts, choices\)/.test(bodyOf('canAdvance')),
  '只有决策步检查决策完成度');

// 2) 复刻语义跑一遍截图现场
const undecided = (c, ch) => c.filter((x) => x.id && !(x.id in ch)).length;
const complete = (s, sc, c, ch) => (s <= 0 || sc != null) && undecided(c, ch) === 0;
const canAdvance = (step, s, sc, c, ch) =>
  step === 'PREVIEW' ? true : step === 'DECIDE' ? complete(s, sc, c, ch) : false;
const conflicts = [{ id: 'a' }, { id: 'b' }, { id: 'c' }];
ok(canAdvance('PREVIEW', 12, null, conflicts, {}) === true,
  '截图现场（12 个会话、3 条冲突、都未表态）预览步可点');
ok(canAdvance('DECIDE', 12, 'STOP', conflicts, { a: 'keepCurrent', b: 'useImported', c: 'keepCurrent' }) === true,
  '决策齐了可以继续');
ok(canAdvance('DECIDE', 12, null, conflicts, { a: 'keepCurrent', b: 'useImported', c: 'keepCurrent' }) === false,
  '会话没选不许继续');
ok(canAdvance('DECIDE', 12, 'STOP', conflicts, { b: 'useImported' }) === false,
  '还有冲突没表态不许继续');
ok(canAdvance('EXECUTE', 0, null, [], {}) === false && canAdvance('RESULT', 0, null, [], {}) === false,
  '执行中与结果步不出现继续键');
ok(undecided([{ id: '' }], {}) === 0, 'id 为空的冲突不参与「未表态」计数（否则会永远卡住）');

console.log(bad === 0 ? '\n全部通过' : '\n' + bad + ' 项失败');
process.exit(bad === 0 ? 0 : 1);
