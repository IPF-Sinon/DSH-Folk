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
const dshRuntime = read('app/src/main/java/me/bmax/apatch/dsh/DshRuntime.kt');

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
const busyBlock = (vm.match(/if \(installing\) \{[\s\S]*?\n        \}/) || [''])[0];
must(busyBlock !== '', '找不到 run() 里的「正在忙」分支');
must(/transientMessage = /.test(busyBlock),
  'run() 在 installing 时仍然静默 return（用户点了看不出任何变化）');
must(/progressHidden = false/.test(busyBlock),
  '忙时没有把进度窗口叫回来（若被隐藏，用户找不到它）');
must(/dsh_plugin_busy_on/.test(busyBlock) && /actionLabelRes/.test(vm),
  '忙时提示没说明「正在跑的是哪一件」（进度窗口显示上一个插件的信息会让人以为点错了）');
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

// ---- 7b. 命令拼接：值一律走 shellQuoted（不许再手写 '\$x'） ----
const repo = read('app/src/main/java/me/bmax/apatch/dsh/DshPluginRepo.kt');
// 手写单引号包插值的写法：值里带一个 ' 就会把整条命令的引号配平打乱，
// 容器里表现为 `bash -c: line 1: unexpected EOF while looking for matching \`'\``。
must(!/dshPlugin\("[^"]*'\$/.test(repo),
  "还有 dshPlugin(\"… '\$x' …\") 这种手写单引号插值 —— 值里带单引号就把命令弄坏了");
const addCalls = repo.match(/dshPlugin\(\s*\n?\s*"add[\s\S]{0,220}?onLine\)/g) || [];
must(addCalls.length >= 5, '找不到插件安装命令（写法变了？）');
for (const call of addCalls) {
  must(/shellQuoted\(/.test(call), '有安装命令没走 shellQuoted：' + call.slice(0, 60));
}
must(/\"remove \$\{shellQuoted\(pkg\)\}/.test(repo), 'remove 命令没走 shellQuoted');
must(/\"add \$\{importFlag\(\)\}\$\{shellQuoted\(containerPath\)\}/.test(repo),
  '本地 tgz 安装命令没走 shellQuoted（路径里的单引号会破坏命令）');

// ---- 7c. 停止要能真的停下来（重试链逐步检查） ----
must(/private var pluginStopFlag = false/.test(dshRuntime), 'DshRuntime 缺停止标志');
must(/fun requestPluginStop\(\) \{/.test(dshRuntime) && /killPluginOperations\(\)/.test(dshRuntime),
  'requestPluginStop 没有同时杀容器内进程');
must(/fun pluginStopRequested\(\): Boolean/.test(dshRuntime), '缺 pluginStopRequested()');
must(/fun clearPluginStop\(\)/.test(dshRuntime), '缺 clearPluginStop()');
const stopChecks = (repo.match(/pluginStopRequested\(\)/g) || []).length;
must(stopChecks >= 4,
  '重试链里的停止检查少于 4 处（npm 循环/官方源兜底/git 线路/tgz 兜底），停了还会继续试下一个源');
must(/viewModelScope\.launch\(Dispatchers\.IO\) \{ runCatching \{ DshRuntime\.requestPluginStop\(\) \} \}/.test(vm),
  'stopInstall() 没有走 requestPluginStop()');
must(/DshRuntime\.clearPluginStop\(\)/.test(vm), 'run() 开始时没有清掉上一次的停止标志');

// ---- 7d. shell 解析失败要能定位到命令 ----
must(/private fun reportShellParseFailure\(/.test(repo), '缺 shell 解析失败的诊断');
must(/reportShellParseFailure\(args, out, onLine\)/.test(repo), '诊断没有被调用');
must(/out\.contains\("unexpected EOF"\) && !out\.contains\("syntax error"\)/.test(repo)
  || /!out\.contains\("unexpected EOF"\) && !out\.contains\("syntax error"\)/.test(repo),
  '诊断的触发条件不对（只该在 shell 解析失败时打印）');

// ---- 7e. 停止要能真的打断当前那一步（pgrep 模式必须匹配真实 argv） ----
// 真实 argv 是 `node … plugin --profile web add …`，没有连续的 "plugin add" 子串；
// 早先那两条模式是死模式，一条进程都匹配不到（外部审计实测 argv 形状后确认）。
must(/pgrep -f 'plugin --profile'/.test(dshRuntime),
  "killPluginOperations 的 pgrep 模式匹配不到真实的 dsh plugin argv");
must(!/pgrep -f 'plugin add'/.test(dshRuntime) && !/pgrep -f 'plugin remove'/.test(dshRuntime),
  "还在用 'plugin add'/'plugin remove' 这两条死模式（真实 argv 里没有这个连续子串）");
must(/DSHFOLK_PLUGIN_STOP_SENTINEL/.test(dshRuntime),
  "pgrep -f 会匹配到执行它的 shell 自己，必须保留哨兵跳过");

// ---- 7f. 宽限期复位后，上一代协程不许再写共享状态 ----
must(/private var runGeneration = 0/.test(vm), '缺 runGeneration（新旧操作会抢 installLog/lastOutput）');
must(/val gen = \+\+runGeneration/.test(vm), 'run() 没有在开头换代');
must(/if \(gen != runGeneration\) return@launch/.test(vm) && /if \(gen == runGeneration\) installing = false/.test(vm),
  '旧一代的写入/收尾没有被挡住');
const watchdog = (vm.match(/delay\(STOP_GRACE_MS\)[\s\S]{0,400}?\n            \}/) || [''])[0];
must(/runGeneration\+\+/.test(watchdog), '强制复位时没有换代（旧协程仍会写进新操作的日志）');

// ---- 8. 字符串两种语言都要有 ----
for (const name of [
  'dsh_plugin_confirm_remove',
  'dsh_plugin_stop_uninstall',
  'dsh_plugin_stopping',
  'dsh_plugin_stopped_forced',
  'dsh_plugin_busy',
  'dsh_plugin_busy_on',
  'dsh_plugin_action_update',
  'dsh_plug_log_cmd_parse_failed',
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
