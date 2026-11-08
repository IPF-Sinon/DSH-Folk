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
// 默认扫**所有源码模块**，而不只是 app/src/main/java。
//
// 为什么：`ExtensionHookRegistry` 这类符号声明在 ext-api 模块里，只扫 app 的话
// 「用了它但没 import」永远看不见 —— 我接 Hook 点时就因此漏了两处（Userscripts、
// DshWebUiActivity），门禁还报了「齐全」。
//
// 显式传参时仍以参数为准（CI 与历史调用都是这么用的）。
const SRC_DIRS = [
  'app/src/main/java',
  'ext-api/src/main/java',
];
function walkKt(dir, acc) {
  let entries;
  try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return acc; }
  for (const e of entries) {
    const p = dir + '/' + e.name;
    if (e.isDirectory()) walkKt(p, acc);
    else if (e.name.endsWith('.kt')) acc.push(p);
  }
  return acc;
}
const FILES = process.argv.length > 2
  ? process.argv.slice(2)
  : SRC_DIRS.reduce((acc, d) => walkKt(d, acc), []);
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
// ── R 引用检查 ───────────────────────────────────────────────────────────
//
// 为什么单列这一条：`R.string.xxx` 用了却没 import `me.bmax.apatch.R` 时，Kotlin 只会报
// 「Unresolved reference 'R'」—— 而且是在**编译期**才报。本地只有词法门禁（查括号配平），
// 抓不到它，于是每个新文件都可能重犯一次。这一轮已经踩过两次（ExtensionApi、NativeCapCatalog）。
//
// 判据要排除三种「不需要 import」的情形：
//   1. 文件本来就在 me.bmax.apatch 包下（同包，R 可见）；
//   2. 已经 import 了 me.bmax.apatch.R；
//   3. 用的是全限定名 me.bmax.apatch.R.xxx。
{
  let rBad = 0;
  for (const f of FILES) {
    const src = fs.readFileSync(f, 'utf8');
    // 去掉注释再判：注释里提到 R.string.foo 是常见的说明写法，不该算错
    const code = src
      .split('\n')
      .map((l) => l.replace(/\/\/.*$/, ''))
      .join('\n')
      .replace(/\/\*[\s\S]*?\*\//g, '');
    const pkgMatch = src.match(/^package\s+([\w.]+)/m);
    const pkg = pkgMatch ? pkgMatch[1] : '';
    const inRootPkg = pkg === 'me.bmax.apatch';
    const imported = /^import\s+me\.bmax\.apatch\.R$/m.test(src);
    const qualified = /me\.bmax\.apatch\.R\./.test(code);
    const bareUse = /(?<![\w.])R\.(string|drawable|id|raw|plurals|array|color|xml|anim|style|layout)\b/.test(code);
    if (bareUse && !inRootPkg && !imported && !qualified) {
      console.log('导入: ' + f.split('/').pop() + ' 用了 R.xxx 但没 import me.bmax.apatch.R（编译期才会报 Unresolved reference）');
      rBad++;
    }
  }
  bad += rBad;
}

// ── Compose 修饰符/扩展函数的最小表（补 KNOWN 之外的盲区）──────────────
//
// 这一条的由来：2026-11-08 我连续两次在 CI 上因 `widthIn` 没有 import 而炸，
// 而本地 43 条门禁全绿 —— `check-imports.js` 当时只查一张手写的 KNOWN 表，
// layout 那一大票修饰符（widthIn / heightIn / size / padding / offset …）一个都不在表里。
// 它们全是**扩展函数**，忘 import 就是 Unresolved reference，只在编译期才看得见。
//
// 判据保守：只查「确定来自 layout/ui 包」且**短名唯一**的那些。
// weight 之类有多个来源的（RowScope/ColumnScope 扩展）不列入，宁可不查也不能误报。
const MODIFIER_SYMBOLS = {
  // androidx.compose.foundation.layout
  widthIn: 'androidx.compose.foundation.layout.widthIn',
  heightIn: 'androidx.compose.foundation.layout.heightIn',
  fillMaxWidth: 'androidx.compose.foundation.layout.fillMaxWidth',
  fillMaxSize: 'androidx.compose.foundation.layout.fillMaxSize',
  fillMaxHeight: 'androidx.compose.foundation.layout.fillMaxHeight',
  wrapContentWidth: 'androidx.compose.foundation.layout.wrapContentWidth',
  wrapContentHeight: 'androidx.compose.foundation.layout.wrapContentHeight',
  wrapContentSize: 'androidx.compose.foundation.layout.wrapContentSize',
  defaultMinSize: 'androidx.compose.foundation.layout.defaultMinSize',
  windowInsetsPadding: 'androidx.compose.foundation.layout.windowInsetsPadding',
  // 注意：alpha / clip / shadow **不能**进这张表。它们同时是 Color.copy(alpha=) 的具名参数、
  // 以及很多函数自己的参数名，短名不唯一 —— 加进来实测 46 处全是误报。
};
let mBad = 0;
for (const f of FILES) {
  const src = fs.readFileSync(f, 'utf8');
  const code = src
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split('\n').map((l) => l.replace(/(^|\s)\/\/.*$/, '')).join('\n');
  const wildPkgs = new Set();
  for (const m of code.matchAll(/^import\s+([\w.]+)\.\*\s*$/gm)) wildPkgs.add(m[1]);
  for (const sym of Object.keys(MODIFIER_SYMBOLS)) {
    const fqcn = MODIFIER_SYMBOLS[sym];
    const useRe = new RegExp('(^|[^\\w])' + sym + '\\s*[(=]', 'm');
    if (!useRe.test(code)) continue;
    if (code.includes('import ' + fqcn)) continue;
    const pkg = fqcn.substring(0, fqcn.lastIndexOf('.'));
    if (wildPkgs.has(pkg)) continue;
    console.log('导入: ' + f.split('/').pop() + ' 用了 ' + sym + ' 但没 import ' + fqcn + '（只在编译期报）');
    mBad++;
  }
}
bad += mBad;
// ── 跨包引用检查（项目内符号）─────────────────────────────────────────────
//
// 前面那份 KNOWN 表是「已知全限定名的外部符号」。这一条补的是**项目内自己声明的**符号：
// 用了 NativeCapCatalog.xxx 却没 import 它，Kotlin 同样只在编译期报 Unresolved reference。
// 这一轮我已经因为这类漏 import 踩了三次（ExtensionApi、NativeCapCatalog、WorkflowEditorSurface）。
//
// ## 判据（宁缺毋滥，必须零误报）
// 一个符号算「可见」当且仅当满足其一：
//   1. 同包（声明所在包 == 当前文件包）；
//   2. 有普通 import（import a.b.C → 短名 C）；
//   3. 有通配 import 且覆盖了声明所在包（import a.b.* 使 a.b 下所有符号可见）；
//   4. 代码里用了全限定名。
// 都不满足才算漏。**只认行首无缩进的顶层声明** —— 嵌套类外部文件本来就看不见它，
// 把它算进来会造成误报（早期版本把 ScriptMarket 内部的 Hit 当成了顶层）。
//
// 为什么不做「全仓符号表逐文件比对」：试过，那种写法在 199 个文件上报 347 处，几乎全是误报
// （同名参数、字符串、注释）。这一版先构建精确的可见性集合再判，实测 4.6 万次组合零误报。
{
  // 1. 收集所有文件的顶层声明 → 它出现在哪些包
  const declPkgs = new Map();
  const declRe = /^(?:(?:public|open|abstract|sealed|data|value|annotation|enum|internal)\s+)*(?:class|object|interface)\s+([A-Za-z_][A-Za-z0-9_]*)/gm;
  const filePkg = new Map();
  for (const f of FILES) {
    let src;
    try { src = fs.readFileSync(f, 'utf8'); } catch { continue; }
    const pm = src.match(/^package\s+([\w.]+)/m);
    const pkg = pm ? pm[1] : '';
    filePkg.set(f, pkg);
    for (const m of src.matchAll(declRe)) {
      if (!declPkgs.has(m[1])) declPkgs.set(m[1], new Set());
      declPkgs.get(m[1]).add(pkg);
    }
  }

  // 2. 逐文件判可见性
  let xBad = 0;
  for (const f of FILES) {
    const src = fs.readFileSync(f, 'utf8');
    const code = src
      .split('\n').map((l) => l.replace(/\/\/.*$/, '')).join('\n')
      .replace(/\/\*[\s\S]*?\*\//g, '');
    const pkg = filePkg.get(f) || '';
    const imports = [...src.matchAll(/^import\s+([\w.*]+)/gm)].map((m) => m[1]);
    const plain = new Set(imports.filter((i) => !i.endsWith('*')).map((i) => i.split('.').pop()));
    const wild = imports.filter((i) => i.endsWith('.*')).map((i) => i.slice(0, -2));
    // 只检查文件里真的出现过的大写开头标识符：全表两两比对太慢且没必要
    const tokens = new Set([...code.matchAll(/(?<![\w.])([A-Z][A-Za-z0-9_]*)/g)].map((m) => m[1]));
    for (const name of tokens) {
      const pkgs = declPkgs.get(name);
      if (!pkgs) continue;                       // 不是项目内符号（交给 KNOWN 或编译器）
      if (pkgs.has(pkg)) continue;               // 同包
      if (plain.has(name)) continue;             // 已 import
      if (wild.some((w) => pkgs.has(w))) continue; // 通配 import 覆盖
      if (code.includes('me.') && new RegExp('(?<![\\w.])me\\.[\\w.]*' + name).test(code)) continue;
      // 真用到才报（出现在类型位置、构造、或对象成员访问）
      const used = new RegExp('(?<![\\w.])' + name + '\\.[A-Za-z]').test(code) ||
        new RegExp('(?<![\\w.])' + name + '\\s*[(<]').test(code);
      if (used) {
        console.log('导入: ' + f.split('/').pop() + ' 用了项目内符号 ' + name +
          '（声明于 ' + [...pkgs].join('/') + '）却没 import（编译期才会报 Unresolved reference）');
        xBad++;
      }
    }
  }
  bad += xBad;
}

console.log(bad === 0 ? '导入/可见性齐全（' + FILES.length + ' 个文件）' : bad + ' 处问题');
process.exit(bad === 0 ? 0 : 1);