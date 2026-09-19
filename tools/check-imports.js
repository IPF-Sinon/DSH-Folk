// 扫「用了但没 import」的已知符号。必须认通配导入（本项目若干文件用 `material3.*`）。
const fs = require('fs');
const FILES = process.argv.slice(2);
const KNOWN = {
  Checkbox: 'androidx.compose.material3',
  LinearProgressIndicator: 'androidx.compose.material3',
  OutlinedTextField: 'androidx.compose.material3',
  IconButton: 'androidx.compose.material3',
  HorizontalDivider: 'androidx.compose.material3',
  Surface: 'androidx.compose.material3',
  TextButton: 'androidx.compose.material3',
  OutlinedButton: 'androidx.compose.material3',
  AlertDialog: 'androidx.compose.material3',
  Button: 'androidx.compose.material3',
  Text: 'androidx.compose.material3',
  Icon: 'androidx.compose.material3',
  MaterialTheme: 'androidx.compose.material3',
  Switch: 'androidx.compose.material3',
  Spacer: 'androidx.compose.foundation.layout',
  Column: 'androidx.compose.foundation.layout',
  Row: 'androidx.compose.foundation.layout',
  Box: 'androidx.compose.foundation.layout',
  AnimatedVisibility: 'androidx.compose.animation',
  RoundedCornerShape: 'androidx.compose.foundation.shape',
  FontFamily: 'androidx.compose.ui.text.font',
  FontWeight: 'androidx.compose.ui.text.font',
  LazyColumn: 'androidx.compose.foundation.lazy',
  PasswordVisualTransformation: 'androidx.compose.ui.text.input',
  VisualTransformation: 'androidx.compose.ui.text.input',
  stringResource: 'androidx.compose.ui.res',
  remember: 'androidx.compose.runtime',
  mutableStateOf: 'androidx.compose.runtime',
  LaunchedEffect: 'androidx.compose.runtime',
  getValue: 'androidx.compose.runtime',
  setValue: 'androidx.compose.runtime',
};
let bad = 0;
for (const f of FILES) {
  const src = fs.readFileSync(f, 'utf8');
  const code = src
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split('\n')
    .map((l) => l.replace(/(^|\s)\/\/.*$/, ''))
    .join('\n');
  for (const [sym, pkg] of Object.entries(KNOWN)) {
    if (!new RegExp('(?<![\\w.])' + sym + '\\s*(\\(|\\{|$)').test(code)) continue;
    const exact = new RegExp('^import\\s+' + pkg.replace(/\./g, '\\.') + '\\.' + sym + '\\s*$', 'm').test(code);
    const wild = new RegExp('^import\\s+' + pkg.replace(/\./g, '\\.') + '\\.\\*\\s*$', 'm').test(code);
    const samePkg = new RegExp('^package\\s+' + pkg.replace(/\./g, '\\.') + '\\s*$', 'm').test(code);
    const local = new RegExp('\\b(fun|class|object|val)\\s+' + sym + '\\b').test(code);
    if (!exact && !wild && !samePkg && !local) {
      console.log('缺 import: ' + f.split('/').pop() + ' 用了 ' + sym + '，需 import ' + pkg + '.' + sym);
      bad++;
    }
  }
}
console.log(bad === 0 ? '导入齐全（' + FILES.length + ' 个文件）' : bad + ' 处缺 import');
process.exit(bad === 0 ? 0 : 1);
