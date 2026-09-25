#!/usr/bin/env node
// 门禁：竞速通道（先测速再下载）与插件页缓存不许被改回「顺序瞎试 / 每次进页面重新拉」。
//
// 需求（2026-09-25 用户定）：
// - 「插件镜像」卡片升级为竞速通道总开关，长按出勾选弹窗，可勾三条通道（插件/应用更新/运行时）；
//   没勾选的一律直连；总开关关掉则全都不生效；
// - 插件安装/更新并入竞速（git 线路按测速排序；npm 规格测速选 registry 并带回落）；
// - 测速结果复用缓存（带有效时间与兜底顺序）；
// - 插件页进入时先显示上次缓存的版本信息，再后台刷新。
const fs = require('fs');
const path = require('path');

const errors = [];
const root = path.resolve(__dirname, '..');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');
const must = (c, m) => { if (!c) errors.push(m); };

const env = read('app/src/main/java/me/bmax/apatch/dsh/DshEnv.kt');
const runtime = read('app/src/main/java/me/bmax/apatch/dsh/DshRuntime.kt');
const source = read('app/src/main/java/me/bmax/apatch/dsh/DshSource.kt');
const repo = read('app/src/main/java/me/bmax/apatch/dsh/DshPluginRepo.kt');
const appUpdater = read('app/src/main/java/me/bmax/apatch/util/AppUpdater.kt');
const dialog = read('app/src/main/java/me/bmax/apatch/ui/component/UpdateDialog.kt');
const card = read('app/src/main/java/me/bmax/apatch/ui/component/ToggleSettingCard.kt');
const settings = read('app/src/main/java/me/bmax/apatch/ui/screen/settings/FunctionSettings.kt');
const screen = read('app/src/main/java/me/bmax/apatch/ui/screen/settings/FunctionSettingsScreen.kt');
const vm = read('app/src/main/java/me/bmax/apatch/ui/viewmodel/DshPluginViewModel.kt');

// 1. prefs 键：总开关沿用旧键（老用户设置不能丢）+ 三条通道分开关
must(/const val KEY_RACE_MASTER = "plugin_gh_mirror"/.test(env),
  '总开关键必须沿用旧的 plugin_gh_mirror（改名会让老用户的设置/备份键错位）');
for (const k of ['KEY_RACE_PLUGINS', 'KEY_RACE_APP_UPDATE', 'KEY_RACE_RUNTIME']) {
  must(new RegExp(`const val ${k} = "`).test(env), `缺 ${k}`);
}

// 2. 总开关关掉时通道一律不生效
const raceFn = (runtime.match(/fun raceEnabled\(channel: String\): Boolean \{[\s\S]*?\n    \}/) || [''])[0];
must(raceFn.length > 0, '找不到 DshRuntime.raceEnabled');
must(/if \(!raceMasterEnabled\(\)\) return false/.test(raceFn),
  'raceEnabled 必须先判总开关（关掉时任何通道都不生效）');
for (const c of ['RACE_PLUGINS', 'RACE_APP_UPDATE', 'RACE_RUNTIME']) {
  must(new RegExp(`const val ${c} = "`).test(runtime), `DshRuntime 缺 ${c}`);
}

// 3. 插件通道：git 线路按测速排序，npm 规格选 registry 并回落
must(/DshSource\.rankedSources\(\)/.test(repo),
  'installGitSpec 必须用 DshSource.rankedSources()（测速排序）而不是写死的镜像顺序');
must(/if \(!DshRuntime\.raceEnabled\(DshRuntime\.RACE_PLUGINS\)\)/.test(repo),
  '插件通道必须判 raceEnabled(RACE_PLUGINS)（关掉时直连）');
must(/fun installRegistrySpec\(/.test(repo) && /--registry/.test(repo),
  'npm 规格必须走 installRegistrySpec 且带 --registry（否则 npm 插件永远只用官方源）');
must(/rankedNpmRegistries/.test(repo),
  'npm 规格必须用 DshSource.rankedNpmRegistries() 选源');

// 4. DshSource：复用窗口 + 兜底顺序
must(/SPEED_RESULT_TTL_MS/.test(source) && /resultsFresh\(\)/.test(source),
  'DshSource 必须带测速结果复用窗口（SPEED_RESULT_TTL_MS + resultsFresh）');
must(/FALLBACK_ORDER/.test(source) && /SOURCE_GITHUB/.test(source),
  'DshSource 必须有兜底顺序（测不出结果时回退，不能返回空列表）');
must(/lastResultsAt = System\.currentTimeMillis\(\)/.test(source),
  'speedTest() 必须记录产出时刻，否则复用窗口永远不成立');
must(/fun rankedNpmRegistries\(/.test(source), '缺 DshSource.rankedNpmRegistries()');

// 5. 应用更新 / 运行时通道的门控
must(/fun raceEnabled\(\): Boolean = DshRuntime\.raceEnabled\(DshRuntime\.RACE_APP_UPDATE\)/.test(appUpdater),
  'AppUpdater.raceEnabled 必须接 RACE_APP_UPDATE');
must(/if \(!AppUpdater\.raceEnabled\(\)\)/.test(dialog),
  '更新对话框必须在竞速关闭时跳过测速、直接直连下载（而不是仍去测速）');
must(/private fun runtimeSource\(ctx: Context\): String/.test(runtime) &&
     /raceEnabled\(RACE_RUNTIME\)/.test(runtime),
  '运行时通道必须经 runtimeSource() 判 RACE_RUNTIME');
must(/runtimeSource\(appContext\)/.test(runtime) && /runtimeMetaUrl\(\)/.test(runtime),
  '运行时下载与 metadata 取址都必须走 runtimeSource/runtimeMetaUrl（否则门控只覆盖一半）');

// 6. UI：长按出勾选弹窗，三条通道都在
must(/onLongClick: \(\(\) -> Unit\)\? = null/.test(card),
  'ToggleSettingCard 必须支持可选的 onLongClick（长按出配置弹窗）');
must(/onLongClick = \{ showRaceDialog = true \}/.test(settings),
  '竞速通道卡片必须把长按接到弹窗');
must(/private fun RaceChannelDialog\(/.test(settings), '缺 RaceChannelDialog');
for (const c of ['RACE_PLUGINS', 'RACE_APP_UPDATE', 'RACE_RUNTIME']) {
  must(new RegExp(`Triple\\(DshRuntime\\.${c},`).test(settings), `弹窗里缺 ${c} 这一项`);
}
must(/DshRuntime\.setRaceEnabled\(channel, on\)/.test(screen),
  '设置页必须把勾选写回 DshRuntime.setRaceEnabled');

// 7. 插件页缓存：先缓存后刷新
must(/fun cachedCatalogRows\(/.test(repo) && /fun fetchCatalogAndCache\(/.test(repo),
  '缺插件页目录缓存读写（cachedCatalogRows / fetchCatalogAndCache）');
must(/cachedCatalogRows\(ctx\)[\s\S]{0,400}fetchCatalogAndCache\(ctx\)/.test(vm),
  'DshPluginViewModel.refresh 必须先渲染缓存、再拉真数据（顺序不能反）');

if (errors.length) {
  console.error('check-race-channel FAILED:');
  for (const e of errors) console.error('  ✗ ' + e);
  process.exit(1);
}
console.log('check-race-channel: 通过');
