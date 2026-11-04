#!/usr/bin/env node
// 门禁：插件页的「移除二次确认」「停止文案跟随动作」「忙时不再静默丢弃点击」不许被改坏。
//
// 真机反馈（issue #12）：
//  1. 卸载点一下就开跑，没有任何确认 —— 用户要求第一下只变红，第二下才卸；
//  2. 进度窗口的停止按钮固定写「停止更新」，卸载时也对不上；
//  3. 「停止没用，接着点别的插件也没反应」：run() 在 installing 时直接 return（静默），
//     而 stopInstall() 只置标志 + 异步杀进程，杀不掉就把 installing 永久留在 true。
const fs = require('fs');
const path = require('path');

const errors = [];
const root = path.resolve(__dirname, '..');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');
const must = (c, m) => { if (!c) errors.push(m); };

const vm = read('app/src/main/java/me/bmax/apatch/ui/viewmodel/DshPluginViewModel.kt');
const screen = read('app/src/main/java/me/bmax/apatch/ui/screen/DshPluginScreen.kt');
const detail = read('app/src/main/java/me/bmax/apatch/ui/component/DshPluginDetail.kt');
const store = read('app/src/main/java/me/bmax/apatch/ui/screen/DshPluginStoreScreen.kt');
const zh = read('app/src/main/res/values-zh-rCN/dsh_strings.xml');
const en = read('app/src/main/res/values/dsh_strings.xml');

// ---- 1. 动作种类：停止按钮要跟着动作说 ----
must(/enum class PluginActionKind \{ Install, Update, Uninstall, Toggle, Local, Repair \}/.test(vm),
  '缺 PluginActionKind（停止按钮无法区分卸载/更新）');
must(/actionKind: PluginActionKind = PluginActionKind\.Install/.test(vm),
  'run() 缺 actionKind 参数（默认 Install 才不破坏其它调用点）');
must(/actionKind = PluginActionKind\.Uninstall/.test(vm), 'uninstall() 没有把动作标成 Uninstall');
must(/actionKind = PluginActionKind\.Update/.test(vm), 'update() 没有把动作标成 Update');
must(/actionKind = PluginActionKind\.Toggle/.test(vm), 'setDisabled() 没有把动作标成 Toggle');
must(/installActionKind = actionKind/.test(vm), 'run() 没有把本次动作写进 installActionKind');

// ---- 2. 对话框：卸载时是「停止卸载」，点了立刻有反馈 ----
must(/actionKind: PluginActionKind = PluginActionKind\.Install,/.test(detail),
  '进度对话框缺 actionKind 参数');
must(/if \(actionKind == PluginActionKind\.Uninstall\) \{/.test(detail),
  '停止按钮没有按 actionKind 分支（只写了文案不算，判据必须真的用上）');
must(/R\.string\.dsh_plugin_stop_uninstall/.test(detail), '停止按钮没有卸载专用文案');
must(/R\.string\.dsh_plugin_stop_update/.test(detail), '停止按钮丢了更新文案');
must(/stopping: Boolean = false/.test(detail), '对话框缺 stopping 状态');
must(/running && stopping -> stringResource\(R\.string\.dsh_plugin_stopping\)/.test(detail),
  '点了停止之后标题没有换成「正在停止…」（用户会以为按钮没反应）');
must(/onStop, enabled = !stopping/.test(detail), '停止按钮在停止中应禁用，避免连点');
must(/stopping = viewModel\.installStopped && viewModel\.installing/.test(screen),
  '插件页没有把「已点停止但还在跑」传给对话框');
must(/actionKind = viewModel\.installActionKind/.test(screen), '插件页没有把动作种类传给对话框');

// ---- 3. 忙时不再静默丢弃点击 ----
must(/transientMessage = apApp\.appString\(R\.string\.dsh_plugin_busy\)/.test(vm),
  'run() 在 installing 时仍然静默 return（用户点了看不出任何变化）');
must(/progressHidden = false\s*\n\s*transientMessage = apApp\.appString\(R\.string\.dsh_plugin_busy\)/.test(vm),
  '忙时没有把进度窗口叫回来（若被隐藏，用户找不到它）');
must(/var transientMessage by mutableStateOf<String\?>\(null\)/.test(vm), '缺 transientMessage 状态');
must(/fun consumeTransientMessage\(\)/.test(vm), '缺 consumeTransientMessage()（消息会反复弹）');
must(/viewModel\.consumeTransientMessage\(\)/.test(screen) && /viewModel\.consumeTransientMessage\(\)/.test(store),
  '插件页/商店页没有消费一次性提示');

// ---- 4. 状态不再卡死 ----
must(/const val STOP_GRACE_MS = 20_000L/.test(vm), '缺 STOP_GRACE_MS（停止后没有兜底）');
must(/delay\(STOP_GRACE_MS\)/.test(vm), 'stopInstall() 没有等待宽限期');
must(/if \(installing && installStopped\) \{[\s\S]{0,200}installing = false/.test(vm),
  '过了宽限期没有强制复位 installing（界面会被一直锁死）');
must(/if \(!failed\) installStopped = false/.test(vm),
  '成功完成时没有清掉 installStopped（会显示成「已停止」）');

// ---- 5. 卡片上的「移除」：两段式 ----
must(/uninstallArmed: Boolean/.test(screen), 'DshPluginItem 缺 uninstallArmed 参数');
must(/onUninstallClick: \(\) -> Unit/.test(screen), 'DshPluginItem 缺 onUninstallClick 参数（第一/第二下无法区分）');
must(/R\.string\.dsh_plugin_confirm_remove/.test(screen), '确认态没有「确认移除」文案');
must(/ButtonDefaults\.textButtonColors\(/.test(screen)
  && /contentColor = if \(uninstallArmed\)/.test(screen)
  && /MaterialTheme\.colorScheme\.error/.test(screen),
  '确认态没有变红（图标+文字一起靠 contentColor）');
must(/var pendingUninstall by remember \{ mutableStateOf<String\?>\(null\) \}/.test(screen),
  '插件页缺 pendingUninstall 状态');
must(/if \(pendingUninstall == key\) \{[\s\S]{0,160}viewModel\.uninstall\(plugin\.pkg\)/.test(screen),
  '第二下没有真的卸载');
must(/pendingUninstall = key/.test(screen), '第一下没有进入确认态');
must(/uninstallArmed = pendingUninstall == key/.test(screen),
  '卡片的确认态没有绑到 pendingUninstall（绑死了就永远红或永远不红）');
// 卸载绝不能绕过确认态直接调
must(!/onUninstall = \{ viewModel\.uninstall\(plugin\.pkg\) \}/.test(screen),
  '列表里还有绕过二次确认的直接卸载调用');

// ---- 6. 「点别处」与自动复位 ----
must(/private const val ARMED_REVERT_MS = 8_000L/.test(screen), '缺 8 秒自动复位常量');
must(/delay\(ARMED_REVERT_MS\)/.test(screen), '确认态没有 8 秒自动复位');
must(/detectTapGestures \{ if \(pendingUninstall != null\) pendingUninstall = null \}/.test(screen),
  '点空白处不会复位确认态');
must(/if \(listState\.isScrollInProgress\) pendingUninstall = null/.test(screen), '滚动不会复位确认态');
must(/LaunchedEffect\(viewModel\.search, list\) \{ pendingUninstall = null \}/.test(screen),
  '搜索/列表刷新后不会复位确认态（会指向已消失的项）');

// ---- 7. 详情面板里的卸载同样要确认 ----
must(/var uninstallArmed by remember \{ mutableStateOf\(false\) \}/.test(detail),
  '详情面板的卸载没有二次确认');
must(/delay\(8_000\)/.test(detail), '详情面板的确认态没有自动复位');
must(/ButtonDefaults\.outlinedButtonColors\(/.test(detail), '详情面板确认态没有变红');

// ---- 8. 字符串两种语言都要有 ----
for (const name of [
  'dsh_plugin_confirm_remove',
  'dsh_plugin_stop_uninstall',
  'dsh_plugin_stopping',
  'dsh_plugin_stopped_forced',
  'dsh_plugin_busy',
]) {
  must(new RegExp('name="' + name + '"').test(zh), '中文缺字符串 ' + name);
  must(new RegExp('name="' + name + '"').test(en), '英文缺字符串 ' + name);
}

if (errors.length) {
  console.error('check-plugin-actions FAILED:');
  for (const e of errors) console.error('  ✗ ' + e);
  process.exit(1);
}
console.log('check-plugin-actions: 通过');
