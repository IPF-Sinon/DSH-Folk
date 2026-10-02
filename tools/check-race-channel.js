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

// ── 测速的两条路径必须分开：自动要快，手动要全 ──
must(/fun speedTest\(\s*\n?\s*probeAll: Boolean = false,/.test(source),
  'speedTest 必须有 probeAll 开关（自动路径不为好看的表格多等十几秒，手动路径必须给全）');
must(/\.take\(2\)\.map \{ it\.source \}\.toSet\(\)/.test(source),
  '自动路径（probeAll=false）仍只给最快的两条测吞吐');
must(/for \(r in reachable\) \{[\s\S]{0,220}probeSpeed\(proxyPrefix\(r\.source\) \+ probe\)/.test(source),
  '手动路径必须对每条可达线路逐条测吞吐（不能并行：并行互相抢带宽，测出来的数全是错的）');
must(/DshSource\.speedTest\(probeAll = true\)/.test(screen),
  '弹窗的测速按钮必须走全量（probeAll = true）');
must(/onProgress\?\.invoke\(acc\)/.test(source) && /lastResults = acc/.test(source),
  '全量测速要逐条回调进度，并且中途就更新 lastResults（提前关窗也该留下结论）');
must(/dsh_race_testing_progress/.test(settings),
  '测速中要显示吞吐进度（全量十来秒，没进度像个死按钮）');


// ── 8. 精简版运行时通道：通道 × 口味 = 四个互不覆盖的滚动位置 ──
//
// 这一组守卫的不变量是「**两个正交开关**」：精简与测试必须能自由组合，不能被做成
// 三选一的互斥项；四个 tag 也各自对应一个独立 release 位置，任何两个撞在一起都会
// 让一份 rootfs 覆盖另一份（arm64 的旧名被顶掉那种事故）。
{
  const builder = read('runtime-builder/build-rootfs.sh');
  const runtimeYml = read('.github/workflows/runtime.yml');
  const stringsEn = read('app/src/main/res/values/dsh_strings.xml');
  const stringsZh = read('app/src/main/res/values-zh-rCN/dsh_strings.xml');

  for (const [constName, tag] of [
    ['RUNTIME_STABLE_BASE', 'runtime-latest'],
    ['RUNTIME_SLIM_BASE', 'runtime-slim-latest'],
    ['RUNTIME_BETA_BASE', 'runtime-beta-latest'],
    ['RUNTIME_SLIM_BETA_BASE', 'runtime-slim-beta-latest'],
  ]) {
    must(source.includes(`${constName} = RELEASE_DOWNLOAD_BASE + "${tag}/"`),
      `DshSource 缺 ${constName}（应指向 ${tag}）`);
  }

  // 正交：runtimeBase() 必须同时读两个开关。只读一个 = 把精简与测试做成了互斥。
  const baseAt = source.indexOf('private fun runtimeBase()');
  const baseFn = baseAt < 0 ? '' : source.slice(baseAt, source.indexOf('\n    }', baseAt));
  must(/acceptRuntimeSlim\(/.test(baseFn) && /acceptRuntimeBeta\(/.test(baseFn),
    'runtimeBase() 必须同时读 acceptRuntimeSlim 与 acceptRuntimeBeta（两个正交开关，不是二选一）');
  must(baseFn.includes('RUNTIME_SLIM_BETA_BASE') && baseFn.includes('RUNTIME_SLIM_BASE'),
    'runtimeBase() 必须能落到精简版的两个 base 上');

  // 两个 setter 都要清缓存：只清一个，旧通道的测速/metadata 结论会被套到新通道上
  for (const fn of ['setAcceptRuntimeBeta', 'setAcceptRuntimeSlim']) {
    const at = source.indexOf(`fun ${fn}(ctx: Context, on: Boolean)`);
    const body = at < 0 ? '' : source.slice(at, source.indexOf('\n    }', at));
    must(at >= 0, `DshSource 缺 ${fn}`);
    must(/resetCaches\(\)/.test(body), `${fn} 必须调用 resetCaches()`);
  }

  for (const c of ['CHANNEL_SLIM', 'CHANNEL_SLIM_BETA']) {
    must(runtime.includes(`const val ${c} = "`), `DshRuntime 缺 ${c} 常量`);
  }
  // channelOf 的判定顺序：必须先认 slim-beta 的全名，再退到 slim 前缀，
  // 否则 runtime-slim-beta-latest 会被前缀规则吞掉、误判成「正式精简版」
  const chanAt = runtime.indexOf('fun channelOf');
  const chanFn = chanAt < 0 ? '' : runtime.slice(chanAt, runtime.indexOf('else -> CHANNEL_ARCHIVE', chanAt));
  for (const t of ['runtime-latest', 'runtime-beta-latest', 'runtime-slim-latest', 'runtime-slim-beta-latest']) {
    must(chanFn.includes(`"${t}"`), `channelOf 必须精确识别 ${t}`);
  }
  must(chanFn.indexOf('runtime-slim-beta-latest') < chanFn.indexOf('startsWith("runtime-slim")'),
    'channelOf 必须先认 runtime-slim-beta-latest 再退到 runtime-slim 前缀（否则精简测试版被误判）');

  // UI 接线
  must(/var runtimeSlim by rememberSaveable \{ mutableStateOf\(DshSource\.acceptRuntimeSlim\(context\)\) \}/.test(screen),
    '设置页必须读取精简开关');
  must(/DshSource\.setAcceptRuntimeSlim\(context, on\)/.test(screen),
    '设置页必须把精简开关写回 DshSource');
  must(/runtimeSlim: Boolean/.test(settings) && /onRuntimeSlimChange: \(Boolean\) -> Unit/.test(settings),
    'FunctionSettings 必须接精简开关的两个参数');
  must(/LaunchedEffect\([^)]*runtimeSlim[^)]*\)/.test(settings),
    '更新检查的 LaunchedEffect 必须带上 runtimeSlim（否则拨了开关不重查）');
  must(/R\.string\.dsh_runtime_slim\b/.test(settings) && /R\.string\.dsh_runtime_channel_slim\b/.test(settings),
    '运行时卡片与版本列表必须用精简版文案');

  for (const [label, xml] of [['values', stringsEn], ['values-zh-rCN', stringsZh]]) {
    for (const k of ['dsh_runtime_slim', 'dsh_runtime_slim_summary', 'dsh_runtime_channel_slim', 'dsh_runtime_channel_slim_beta']) {
      must(xml.includes(`name="${k}"`), `${label} 缺字符串 ${k}`);
    }
  }

  // 构建脚本：四组 tag 矩阵、口味变量、裁剪安全断言、metadata 的 flavor 字段
  for (const [combo, tag] of [
    ['stable/full', 'runtime-latest'],
    ['beta/full', 'runtime-beta-latest'],
    ['stable/slim', 'runtime-slim-latest'],
    ['beta/slim', 'runtime-slim-beta-latest'],
  ]) {
    const at = builder.indexOf(`${combo})`);
    const seg = at < 0 ? '' : builder.slice(at, at + 220);
    must(at >= 0 && seg.includes(`CHANNEL_RELEASE_TAG="${tag}"`),
      `build-rootfs.sh 的 tag 矩阵缺 ${combo} → ${tag}`);
  }
  must(builder.includes('RUNTIME_FLAVOR="${RUNTIME_FLAVOR:-full}"'), 'build-rootfs.sh 缺 RUNTIME_FLAVOR');
  must(builder.includes('check-trim-safety.js'), 'slim 构建必须先跑 check-trim-safety.js（删除安全性断言）');
  must(builder.includes('FLAVOR_SUFFIX') && builder.includes('"flavor": "${RUNTIME_FLAVOR}"'),
    'metadata 必须带 flavor 字段，版本串必须带口味后缀');
  // 裁剪必须留下 libreoffice 的包本身：dsh-skill-office 要解析它的 CLI 路径，
  // dsh-office-to-pdf 要 import libreoffice-kit（两者都在用时才 resolveEngine）
  must(builder.includes('libreoffice-kit-wasm/package.json'),
    'slim 裁剪必须断言保留 libreoffice-kit-wasm/package.json（否则技能页路径解析会崩）');

  must(runtimeYml.includes('flavor:') && runtimeYml.includes('options: [ full, slim ]'),
    'runtime.yml 必须暴露 flavor 输入');
  must(runtimeYml.includes('RUNTIME_FLAVOR: ${{ inputs.flavor }}'),
    'runtime.yml 必须把 flavor 传给构建脚本');
  must(/needs\.plan\.outputs\.default_tag/.test(runtimeYml),
    'runtime.yml 的 build 与 publish 必须用同一份 tag 推导结果（各写一份会漂移）');
}

if (errors.length) {
  console.error('check-race-channel FAILED:');
  for (const e of errors) console.error('  ✗ ' + e);
  process.exit(1);
}
console.log('check-race-channel: 通过');

// ── 镜像线路清单的单一事实来源（2026-09-25 扩充到 5 条线路后补的门禁）──
// 线路清单散成三份（测速候选、下载排序、清 git 重写）是这类改动最容易踩的坑：
// 加了线路却忘了进 downloadRank，速度排序就退化成「没测过」；忘了进清键清单，
// 旧前缀的 insteadOf 会留在 .gitconfig 里继续生效。
{
  const fs2 = require('fs');
  const p2 = require('path');
  const rootDir = p2.resolve(__dirname, '..');
  const src = fs2.readFileSync(p2.join(rootDir, 'app/src/main/java/me/bmax/apatch/dsh/DshSource.kt'), 'utf8');
  const repos = fs2.readFileSync(p2.join(rootDir, 'app/src/main/java/me/bmax/apatch/dsh/DshPluginRepo.kt'), 'utf8');
  const settings2 = fs2.readFileSync(p2.join(rootDir, 'app/src/main/java/me/bmax/apatch/ui/screen/settings/FunctionSettings.kt'), 'utf8');
  const builder = fs2.readFileSync(p2.join(rootDir, 'runtime-builder/build-rootfs.sh'), 'utf8');
  const extra = [];
  const want = (c, m) => { if (!c) extra.push(m); };

  want(/private val MIRRORS: List<Pair<String, String>> = listOf\(/.test(src),
    'DshSource 必须用 MIRRORS 作为线路清单的唯一事实来源');
  want(/fun proxyPrefix\(source: String\): String =\s*\n?\s*MIRRORS\.firstOrNull/.test(src),
    'proxyPrefix 必须从 MIRRORS 反查（不能各写一份 when）');
  want(/val src = MIRRORS\.firstOrNull \{ url\.startsWith\(it\.second\) \}/.test(src),
    'downloadRank 必须从 MIRRORS 反查：新加的线路否则会被当成「未测速」排在最后');
  want(/activeMirrors\(\)\.map \{ \(src, prefix\) -> src to "\$prefix\$meta" \}/.test(src) &&
    /private fun activeMirrors\(\): List<Pair<String, String>>/.test(src),
    'speedTest 的候选必须来自 activeMirrors()（= 勾选过的线路，加线路只改 MIRRORS）');
  want(/DshSource\.allProxyPrefixes\(\)/.test(repos),
    '插件清 git 重写必须用 DshSource.allProxyPrefixes()（否则漏清新前缀）');
  want(!/GH_MIRROR_PREFIXES/.test(repos), '插件里不该再留第二份前缀清单（GH_MIRROR_PREFIXES）');
  want(/for \(id in DshSource\.allSourceIds\(\)\)/.test(settings2),
    '弹窗的镜像勾选必须按 DshSource.allSourceIds() 渲染（加线路自动出现）');
  want(/DshSource\.setEnabledMirrors\(/.test(fs2.readFileSync(p2.join(rootDir, 'app/src/main/java/me/bmax/apatch/ui/screen/settings/FunctionSettingsScreen.kt'), 'utf8')),
    '勾选必须写回 DshSource.setEnabledMirrors');
  want(!/function_download_source/.test(settings2),
    '旧的「运行时下载源」卡片必须已移除（下载源只能在竞速弹窗里设，两处设就会互相打架）');
  want(/fun enabledMirrors\(\): Set<String>/.test(src) && /private fun activeMirrors\(\)/.test(src),
    'DshSource 必须按勾选过滤候选（enabledMirrors/activeMirrors）');
  want(/SOURCE_GHPROXY_MAIN to "https:\/\/gh-proxy\.org\/"/.test(src) && /private fun migrateLegacySourceChoice/.test(src),
    '新增线路必须在 MIRRORS 里，且老固定源选择要有迁移（不许把用户的明确选择悄悄重置成全选）');
// 下载候选必须收敛到 DshSource.proxyCandidates（各消费点不许再写死两条线路）
  const dshRuntime = fs2.readFileSync(p2.join(rootDir, 'app/src/main/java/me/bmax/apatch/dsh/DshRuntime.kt'), 'utf8');
  const appUpdater2 = fs2.readFileSync(p2.join(rootDir, 'app/src/main/java/me/bmax/apatch/util/AppUpdater.kt'), 'utf8');
  const updateChecker = fs2.readFileSync(p2.join(rootDir, 'app/src/main/java/me/bmax/apatch/util/UpdateChecker.kt'), 'utf8');
  want(/fun proxyCandidates\(url: String\): List<String>/.test(src), '缺 DshSource.proxyCandidates');
  want(/DshSource\.proxyCandidates\(meta\.url\)/.test(dshRuntime),
    '运行时下载必须用 DshSource.proxyCandidates（只认 metadata.mirrors 会让新增线路永远用不上）');
  want(/DshSource\.proxyCandidates\(status\.apkUrl\)/.test(appUpdater2),
    'APK 下载必须用 DshSource.proxyCandidates');
  want(/DshSource\.proxyCandidates\(url\)/.test(updateChecker),
    'APK 校验值获取必须跟 APK 走同一批候选');

  want(/fun <T> probeAllInParallel\(/.test(src),
    '6 条线路的延迟探测必须并行（串行最坏要等 6 次超时）');

  // metadata 的 mirrors 要覆盖同一批线路（老版本 App 也靠它回退）
  const prefixes = (src.match(/SOURCE_GHPROXY_\w+ to "(https:\/\/[^"]+)"/g) || [])
    .map((l) => l.match(/"(https:\/\/[^"]+)"/)[1]);
  want(prefixes.length >= 5, `MIRRORS 线路数应 ≥5，实际 ${prefixes.length}`);
  for (const pre of prefixes) {
    want(builder.includes(pre), `runtime-builder 的 metadata mirrors 缺线路 ${pre}`);
  }

  if (extra.length) {
    console.error('check-race-channel FAILED (mirror sources):');
    for (const e of extra) console.error('  ✗ ' + e);
    process.exit(1);
  }
  console.log(`check-race-channel: 镜像线路清单一致（${prefixes.length} 条）`);
}
