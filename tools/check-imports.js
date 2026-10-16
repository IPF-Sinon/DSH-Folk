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
  // 值可以是一个全限定名，也可以是一组候选：同一个短名在不同包里都合法时，
  // 命中任意一个就算满足。Surface 就是这种 —— 界面里是 Compose 的，
  // 而解码头/悬浮窗那边用的是 android.view.Surface。
  Surface: ['androidx.compose.material3.Surface', 'android.view.Surface'],
  TextButton: 'androidx.compose.material3.TextButton',
  OutlinedButton: 'androidx.compose.material3.OutlinedButton',
  AlertDialog: 'androidx.compose.material3.AlertDialog',
  // foundation / animation
  AnimatedVisibility: 'androidx.compose.animation.AnimatedVisibility',
  // 动画这一组是「用了却忘了 import」的高发区：spring() / Spring.* / animateXxxAsState 都是
  // 顶层函数或对象，编译期不报、只在 CI 才炸（2026-10-15 的一轮 beta 构建就是这么废的：
  // 一个文件用了 spring() 却只 import 了 Spring 对象）。
  spring: 'androidx.compose.animation.core.spring',
  Spring: 'androidx.compose.animation.core.Spring',
  tween: 'androidx.compose.animation.core.tween',
  animateFloatAsState: 'androidx.compose.animation.core.animateFloatAsState',
  animateDpAsState: 'androidx.compose.animation.core.animateDpAsState',
  // animateColorAsState 有两个合法来源：core 里的那个，以及 animation 包里更老的那个
  // （HomeDsh / HomeV4 用的就是后者），所以这里给候选而不是单个全限定名。
  animateColorAsState: [
    'androidx.compose.animation.core.animateColorAsState',
    'androidx.compose.animation.animateColorAsState',
  ],
  RoundedCornerShape: 'androidx.compose.foundation.shape.RoundedCornerShape',
  LazyColumn: 'androidx.compose.foundation.lazy.LazyColumn',
  // runtime / 组合处（「用了却忘了 import」的第二高发区，原来这张表里一个都没有）：
  // 它们全是**顶层函数或属性**，忘了 import 就是 Unresolved reference，本地门禁原来
  // 完全看不见，只能等 CI 报 —— 2026-10-15 的 spring()、以及后面几次都是这一类。
  remember: 'androidx.compose.runtime.remember',
  rememberSaveable: 'androidx.compose.runtime.saveable.rememberSaveable',
  rememberCoroutineScope: 'androidx.compose.runtime.rememberCoroutineScope',
  rememberUpdatedState: 'androidx.compose.runtime.rememberUpdatedState',
  LaunchedEffect: 'androidx.compose.runtime.LaunchedEffect',
  DisposableEffect: 'androidx.compose.runtime.DisposableEffect',
  derivedStateOf: 'androidx.compose.runtime.derivedStateOf',
  produceState: 'androidx.compose.runtime.produceState',
  collectAsState: 'androidx.compose.runtime.collectAsState',
  collectAsStateWithLifecycle: 'androidx.lifecycle.compose.collectAsStateWithLifecycle',
  LocalContext: 'androidx.compose.ui.platform.LocalContext',
  LocalConfiguration: 'androidx.compose.ui.platform.LocalConfiguration',
  LocalDensity: 'androidx.compose.ui.platform.LocalDensity',
  LocalLifecycleOwner: 'androidx.lifecycle.compose.LocalLifecycleOwner',
  stringResource: 'androidx.compose.ui.res.stringResource',
  rememberScrollState: 'androidx.compose.foundation.rememberScrollState',
  verticalScroll: 'androidx.compose.foundation.verticalScroll',
  horizontalScroll: 'androidx.compose.foundation.horizontalScroll',
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
  for (const [sym, target] of Object.entries(KNOWN)) {
    // 后面跟 ( { [ 或 **.** 都算用到：DshAppDataSnapshot.has(...) / Alignment.CenterVertically
    // 都是「用了这个对象」，而最初的写法只认括号，于是漏报（反向验证时抓到的）。
    if (!new RegExp('(?<![\\w.])' + sym + '\\s*(\\(|\\{|\\[|\\.|$)').test(code)) continue;
    const cands = Array.isArray(target) ? target : [target];
    const satisfied = cands.some((fqcn) => {
      const pkg = fqcn.split('.').slice(0, -1).join('.');
      const exact = new RegExp('^import\\s+' + fqcn.replace(/\./g, '\\.') + '\\s*$', 'm').test(code);
      const wild = new RegExp('^import\\s+' + pkg.replace(/\./g, '\\.') + '\\.\\*\\s*$', 'm').test(code);
      const samePkg = new RegExp('^package\\s+' + pkg.replace(/\./g, '\\.') + '\\s*$', 'm').test(code);
      const local = new RegExp('\\b(fun|class|object|interface|val|var)\\s+' + sym + '\\b').test(code);
      return exact || wild || samePkg || local;
    });
    if (!satisfied) {
      console.log('缺 import: ' + f.split('/').pop() + ' 用了 ' + sym + '，需 import ' + cands.join(' 或 '));
      bad++;
    }
  }
}
// 图标：Icons.<风格>.<名字> 全是扩展属性，必须逐个 import。material-icons 里的图标上千个，
// 枚举进 KNOWN 表不现实 —— 但它们的规则极整齐，可以通用地查：用到什么风格，就要求那个包的
// import（Icons.Default 是 Icons.Filled 的老别名，同一个包）。
//
// 这一条是补课：2026-10-15 那次功能页的分类枚举里用了 Icons.Filled.Folder 却没 import，
// 本地 33 条门禁全绿、CI 才报 Unresolved reference（同一个提交里还漏了 spring()）。
const ICON_STYLE = {
  Default: 'filled', Filled: 'filled', Outlined: 'outlined',
  Rounded: 'rounded', Sharp: 'sharp', TwoTone: 'twotone',
};
for (const f of FILES) {
  const src = fs.readFileSync(f, 'utf8');
  const code = src
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split('\n').map((l) => l.replace(/(^|\s)\/\/.*$/, '')).join('\n');
  // 本仓没有自己声明过 Icons 扩展；真出现了就整文件跳过，免得误报
  if (/\bval\s+Icons\b/.test(code)) continue;
  const need = new Set();
  for (const m of code.matchAll(/Icons\.AutoMirrored\.(Default|Filled|Outlined|Rounded|Sharp|TwoTone)\.([A-Za-z0-9_]+)/g)) {
    need.add('automirrored.' + ICON_STYLE[m[1]] + '.' + m[2]);
  }
  for (const m of code.matchAll(/Icons\.(Default|Filled|Outlined|Rounded|Sharp|TwoTone)\.([A-Za-z0-9_]+)/g)) {
    need.add(ICON_STYLE[m[1]] + '.' + m[2]);
  }
  for (const key of need) {
    const parts = key.split('.');
    const name = parts.pop();
    const fqcn = 'androidx.compose.material.icons.' + key;
    const pkg = 'androidx.compose.material.icons.' + parts.join('.');
    const exact = new RegExp('^import\\s+' + fqcn.replace(/\./g, '\\.') + '\\s*$', 'm').test(code);
    const wild = new RegExp('^import\\s+' + pkg.replace(/\./g, '\\.') + '\\.\\*\\s*$', 'm').test(code);
    if (!exact && !wild) {
      console.log('缺 import: ' + f.split('/').pop() + ' 用了 Icons.…' + name + '，需 import ' + fqcn);
      bad++;
    }
  }
}
// 「public 函数暴露 internal 类型」也是编译错误，而且同样只在 CI 才炸（2026-10-15：给功能页
// 加了 internal enum 的分类参数，FunctionSettingsContent 是 public，CI 报
// 'public' function exposes its 'internal' parameter type）。文本可判，就补上：
//   1. 先收集全仓的 internal 类型名；
//   2. 再看每个文件里**顶层 public 函数**（行首就是 fun，带注解也算）的参数类型有没有用它。
// 只查顶层：internal 类的成员函数用 internal 类型是合法的，而成员函数在这个仓里都是缩进的。
const INTERNAL_TYPES = new Set();
for (const f of FILES) {
  const src = fs.readFileSync(f, 'utf8');
  for (const m of src.matchAll(
    /\binternal\s+(?:enum\s+class|sealed\s+class|data\s+class|value\s+class|annotation\s+class|class|interface|object)\s+([A-Z][A-Za-z0-9_]*)/g,
  )) {
    INTERNAL_TYPES.add(m[1]);
  }
}
if (INTERNAL_TYPES.size > 0) {
  for (const f of FILES) {
    const src = fs.readFileSync(f, 'utf8');
    const code = src
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .split('\n').map((l) => l.replace(/(^|\s)\/\/.*$/, '')).join('\n');
    for (const m of code.matchAll(/^(?:@[A-Za-z0-9_.]+(?:\([^)]*\))?\s*\n)*fun\s+([A-Za-z0-9_]+)\s*\(/gm)) {
      const open = code.indexOf('(', m.index);
      let depth = 0, close = -1;
      for (let i = open; i < code.length; i++) {
        if (code[i] === '(') depth++;
        else if (code[i] === ')') { depth--; if (depth === 0) { close = i; break; } }
      }
      if (close < 0) continue;
      const params = code.slice(open + 1, close);
      const hit = [...INTERNAL_TYPES].filter((t) => new RegExp('[:<(,\\s]' + t + '\\b').test(params));
      if (hit.length > 0) {
        console.log('可见性: ' + f.split('/').pop() + ' 的 public 函数 ' + m[1] +
          ' 暴露了 internal 类型 ' + hit.join('、') + '（Kotlin 会报 public function exposes its internal parameter type）');
        bad++;
      }
    }
  }
}
// 上面那条只查「顶层 public 函数」。漏掉的另一半：**顶层 public 类/对象的成员与属性**也是
// public API —— 2026-10-16 就栽在这里：UserscriptInstall.kt 里一个 public class 的属性和
// 成员函数用了 `internal object Userscripts` 的嵌套类型（Userscripts.Pending），本地 33 条
// 门禁全绿，CI 报两行：
//   'public' function exposes its 'internal' parameter type containing declaration 'Userscripts'.
//   'public' property exposes its 'internal' type containing declaration 'Userscripts'.
//
// 只看**类体的最外层成员**（嵌套声明里的成员缩进更深、且嵌套 internal 类用 internal 类型
// 是合法的，所以整段跳过），并且成员自己标了 private/internal/protected 的也算合规。
{
  const strip = (src) => src
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split('\n').map((l) => l.replace(/(^|\s)\/\/.*$/, '')).join('\n');
  for (const f of FILES) {
    const code = strip(fs.readFileSync(f, 'utf8'));
    for (const m of code.matchAll(
      /^(?:(?:public|open|abstract|sealed|data|value|annotation|enum)\s+)*(?:class|object|interface)\s+([A-Za-z0-9_]+)[^\n]*\{/gm,
    )) {
      if (/^\s*(internal|private)\b/.test(m[0])) continue; // 自身不是 public API
      const open = code.indexOf('{', m.index);
      let depth = 0;
      let close = -1;
      for (let i = open; i < code.length; i++) {
        if (code[i] === '{') depth++;
        else if (code[i] === '}') { depth--; if (depth === 0) { close = i; break; } }
      }
      if (close < 0) continue;
      let inner = 0;
      for (const raw of code.slice(open + 1, close).split('\n')) {
        const t = raw.trim();
        const at = inner; // 这一行开始处的相对深度
        inner += (raw.match(/\{/g) || []).length - (raw.match(/\}/g) || []).length;
        if (at !== 0) continue; // 只认类体最外层的成员
        if (/^(private|internal|protected)\b/.test(t)) continue;
        if (!/^(fun|val|var|constructor)\b/.test(t)) continue;
        const hit = [...INTERNAL_TYPES].filter((tt) =>
          new RegExp('(?<![\\w.])' + tt + '(\\.[A-Za-z0-9_]+)?\\b').test(t));
        if (hit.length > 0) {
          console.log('可见性: ' + f.split('/').pop() + ' 的 ' + m[1] + ' 里的成员「' +
            t.slice(0, 60) + '」用了 internal 类型 ' + hit.join('、') +
            '（public 成员不能暴露 internal 类型）');
          bad++;
        }
      }
    }
  }
}
console.log(bad === 0 ? '导入/可见性齐全（' + FILES.length + ' 个文件）' : bad + ' 处问题');
process.exit(bad === 0 ? 0 : 1);