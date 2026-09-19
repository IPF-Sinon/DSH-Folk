// 「用了但没 import」检查（保守版）。
//
// 为什么不做「从全仓学符号再逐文件比对」：试过，那版在 199 个文件上报了 347 处，
// 全是误报 —— 纯文本无法区分「类型用法」与「字符串/形参/注释里的同名词」（Update、Process、
// Settings、Home…）。一把动辄几百条噪音的门禁等于没有门禁，还会训练人忽略它。
//
// 所以只查**明确知道全限定名**的一组符号（Compose 与常被漏掉的那几个）。清单会滞后，
// 但每一条都是真判据；新漏的符号由 CI 的编译错误兜住（build.yml/beta.yml 已把编译错误
// 以 ::error:: 注解报出来，不再需要翻日志）。
const fs = require('fs');
const FILES = process.argv.slice(2);
const KNOWN = {
  // material3
  Checkbox: 'androidx.compose.material3.Checkbox',
  LinearProgressIndicator: 'androidx.compose.material3.LinearProgressIndicator',
  CircularProgressIndicator: 'androidx.compose.material3.CircularProgressIndicator',
  OutlinedTextField: 'androidx.compose.material3.OutlinedTextField',
  IconButton: 'androidx.compose.material3.IconButton',
  HorizontalDivider: 'androidx.compose.material3.HorizontalDivider',
  Surface: 'androidx.compose.material3.Surface',
  TextButton: 'androidx.compose.material3.TextButton',
  OutlinedButton: 'androidx.compose.material3.OutlinedButton',
  AlertDialog: 'androidx.compose.material3.AlertDialog',
  // foundation / animation
  AnimatedVisibility: 'androidx.compose.animation.AnimatedVisibility',
  RoundedCornerShape: 'androidx.compose.foundation.shape.RoundedCornerShape',
  LazyColumn: 'androidx.compose.foundation.lazy.LazyColumn',
  // ui
  Alignment: 'androidx.compose.ui.Alignment',
  PasswordVisualTransformation: 'androidx.compose.ui.text.input.PasswordVisualTransformation',
  VisualTransformation: 'androidx.compose.ui.text.input.VisualTransformation',
  FontFamily: 'androidx.compose.ui.text.font.FontFamily',
  FontWeight: 'androidx.compose.ui.text.font.FontWeight',
  // 本项目里经常忘了 import 的对象
  DshAppDataSnapshot: 'me.bmax.apatch.dsh.DshAppDataSnapshot',
  DshImportWizard: 'me.bmax.apatch.dsh.DshImportWizard',
  DshBackupArchive: 'me.bmax.apatch.dsh.DshBackupArchive',
  ThemeManager: 'me.bmax.apatch.ui.theme.ThemeManager',
};
let bad = 0;
for (const f of FILES) {
  const src = fs.readFileSync(f, 'utf8');
  const code = src
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split('\n').map((l) => l.replace(/(^|\s)\/\/.*$/, '')).join('\n');
  for (const [sym, fqcn] of Object.entries(KNOWN)) {
    // 后面跟 ( { [ 或 **.** 都算用到：DshAppDataSnapshot.has(...) / Alignment.CenterVertically
    // 都是「用了这个对象」，而最初的写法只认括号，于是漏报（反向验证时抓到的）。
    if (!new RegExp('(?<![\\w.])' + sym + '\\s*(\\(|\\{|\\[|\\.|$)').test(code)) continue;
    const pkg = fqcn.split('.').slice(0, -1).join('.');
    const exact = new RegExp('^import\\s+' + fqcn.replace(/\./g, '\\.') + '\\s*$', 'm').test(code);
    const wild = new RegExp('^import\\s+' + pkg.replace(/\./g, '\\.') + '\\.\\*\\s*$', 'm').test(code);
    const samePkg = new RegExp('^package\\s+' + pkg.replace(/\./g, '\\.') + '\\s*$', 'm').test(code);
    const local = new RegExp('\\b(fun|class|object|interface|val|var)\\s+' + sym + '\\b').test(code);
    if (!exact && !wild && !samePkg && !local) {
      console.log('缺 import: ' + f.split('/').pop() + ' 用了 ' + sym + '，需 import ' + fqcn);
      bad++;
    }
  }
}
console.log(bad === 0 ? '导入齐全（' + FILES.length + ' 个文件）' : bad + ' 处缺 import');
process.exit(bad === 0 ? 0 : 1);
