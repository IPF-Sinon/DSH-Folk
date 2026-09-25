#!/usr/bin/env node
// 门禁：「插件页点更新，版本必须真的变」这条修复不许被改坏。
//
// 真机症状：插件页点「更新」，日志一切正常，版本却永远不变（dsh-config-manager 0.1.61→0.1.64、
// dsh-web-mobile 2.4.1→3.0.0 都点不动）。
// 实测根因（pnpm 11）：`dsh plugin … add` 只是把参数原样转给 pnpm，而 pnpm 对**已声明过**的
// registry 依赖，`add <裸包名>` 是**空操作**（打印 "Already up to date"，已装版本与 package.json
// 里的范围都不动）；显式 `@latest` 才会重新解析并改写范围，顺带越过 `^2.4.1` 这类 caret 天花板
// （major 升级才进得来）。`github:` 规格相反：裸规格本来就会重新解析到默认分支最新提交
// （实测 0.3.0 → 0.4.1），且不能接 `@版本`（要跟 ref 得写 `#ref`）。
const fs = require('fs');
const path = require('path');

const errors = [];
const root = path.resolve(__dirname, '..');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');
const must = (c, m) => { if (!c) errors.push(m); };

const repo = read('app/src/main/java/me/bmax/apatch/dsh/DshPluginRepo.kt');
const vm = read('app/src/main/java/me/bmax/apatch/ui/viewmodel/DshPluginViewModel.kt');
const screen = read('app/src/main/java/me/bmax/apatch/ui/screen/DshPluginScreen.kt');
const runtime = read('app/src/main/java/me/bmax/apatch/dsh/DshRuntime.kt');

// 1. 有显式的 latest 常量，且 install 会把它拼成 `pkg@latest`
must(/const val VERSION_LATEST = "latest"/.test(repo),
  '缺 DshPluginRepo.VERSION_LATEST —— 「更新」失去了显式取最新版的唯一手段');

// 2. git 规格绝不接 `@版本`（要跟 ref 得写 #ref），否则会拼出装不上的规格
must(/val isGit = resolved\.startsWith\("github:"\) \|\| resolved\.startsWith\("git\+"\)/.test(repo),
  'install() 必须先按 resolved 判定 isGit');
must(/val spec = if \(isGit \|\| version\.isBlank\(\)\) resolved else "\$resolved@\$version"/.test(repo),
  'install() 的 spec 组装被改：git 规格会被拼上 @版本（无效规格），或非空 version 不再拼 @');

// 3. 插件页「更新」走 update()（= 显式 @latest），不能退回裸包名的 install()
must(/fun update\(pkg: String, onDone: \(String\) -> Unit = \{\}\) =\s*\n?\s*install\(pkg, DshPluginRepo\.VERSION_LATEST, onDone\)/.test(vm),
  'ViewModel.update() 不再显式传 VERSION_LATEST —— pnpm 会把它当空操作');
must(/onUpdate = \{ viewModel\.update\(/.test(screen),
  '插件页 onUpdate 没有走 viewModel.update()（退回裸包名 install 就又是空操作）');
must(!/onUpdate = \{ viewModel\.install\(/.test(screen),
  '插件页仍有 onUpdate 直接调 install()（裸包名空操作）');

// 4. 预装包门禁的重装也必须显式 @latest，否则版本顶不上去（静默失效）
const seedFn = (runtime.match(/private suspend fun installSeedPkgWithApproval\(pkg: String\): Int \{[\s\S]*?\n    \}/) || [''])[0];
must(seedFn.length > 0, '找不到 installSeedPkgWithApproval()');
must((seedFn.match(/DshPluginRepo\.VERSION_LATEST/g) || []).length >= 2,
  'installSeedPkgWithApproval() 的两次安装（首次 + 放行构建后重试）都要传 VERSION_LATEST');

// 6. 插件页开关刷新（真机：停用/启用后开关很大概率不变、实际状态其实已变，找不出规律）。
//    根因是并发刷新被静默丢弃 + 失败把 isRefreshing 永久挂 true + 详情面板持实体快照。
must(vm.includes("refreshQueued = true"),
  'refresh() 不再把并发请求排队补跑（refreshQueued）—— 切换后的那次刷新会被静默丢弃');
must(/if \(refreshQueued\) \{[\s\S]{0,80}refreshQueued = false[\s\S]{0,40}refresh\(\)/.test(vm),
  'refresh() 的 finally 没有「有待跑请求就补跑一次」—— 保证写盘之后一定有一次读盘');
must(/refreshError by mutableStateOf/.test(vm),
  'refresh() 失败不再上报（refreshError）—— 旧代码把失败吞掉，用户只看到「刷新不管用」');
must(vm.includes("plugins = plugins.map { if (it.pkg == pkg) it.copy(disabled = disabled) else it }"),
  'setDisabled() 成功后缺乐观更新 —— 开关要立刻翻转，不等那一轮读盘');
// run()/refresh() 的 installing/isRefreshing 必须在 finally 里复位（异常不能让后续操作静默失效）
must(/\} finally \{[\s\S]{0,60}installing = false[\s\S]{0,20}\}/.test(vm),
  'run() 的 installing = false 不在 finally 里 —— 一次异常会让之后所有安装/切换静默失效');
must(/\} finally \{[\s\S]{0,60}isRefreshing = false/.test(vm),
  'refresh() 的 isRefreshing 复位不在 finally 里 —— 异常会把它永久挂 true');
// 详情面板持 id 不持实体（否则面板里的开关停在打开那一刻）
must(!/var detail by remember \{ mutableStateOf<DshPlugin\?>\(null\) \}/.test(screen),
  '插件页详情面板仍持 DshPlugin 实体快照 —— 切换后面板里的开关不会跟着变');
must(/var detailId by remember \{ mutableStateOf<String\?>\(null\) \}/.test(screen),
  '插件页详情面板没有改成持 id（detailId）');
// 开关灰着要给原因
must(screen.includes("dsh_plugin_toggle_state_unknown"),
  '开关因 entryIds 为空而灰掉时缺一句原因文案（dsh_plugin_toggle_state_unknown）');

// 5. 说明性注释要在（这条修复的原理不写在代码里，后人一定会再踩）
must(/Already up to date/.test(repo) || /空操作/.test(repo),
  'install() 上缺少「pnpm 对已声明依赖是空操作」的说明注释');

if (errors.length) {
  console.error('check-plugin-update FAILED:');
  for (const e of errors) console.error('  ✗ ' + e);
  process.exit(1);
}
console.log('check-plugin-update: 通过');
