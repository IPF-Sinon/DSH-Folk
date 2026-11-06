#!/usr/bin/env node
// 门禁：扩展系统（App Extensions）的**安全铁律**与失败隔离不许被改坏。
//
// 这套东西跑在 App 进程内、与宿主同权（root 通道 / 无障碍 / 全盘文件），所以它的价值不在
// 「功能多」，而在「出问题时用户还有路可走」。三条不变量：
//   1. 启动第一件事检测熔断标志文件；命中就一个扩展都不加载；
//   2. 崩溃页永远不接扩展 UI，且带「禁用全部扩展并重启」；
//   3. 任何一个扩展抛异常/加载失败，只能影响它自己（宿主与其它扩展照常）。
// 另外还有一条技术硬约束：Android 14+ 动态加载的 dex 必须只读，否则 SecurityException。
const fs = require('fs');
const path = require('path');

const errors = [];
const root = path.resolve(__dirname, '..');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');
const exists = (p) => fs.existsSync(path.join(root, p));
const must = (c, m) => { if (!c) errors.push(m); };

const EXT = 'app/src/main/java/me/bmax/apatch/dsh/ext/';
for (const f of [
  'ExtensionApi.kt', 'ExtensionSafetyGuard.kt', 'ExtensionManifest.kt', 'ExtensionRegistry.kt',
  'ExtensionLoader.kt', 'ExtensionFs.kt', 'ExtensionServiceRegistry.kt', 'ExtensionHookRegistry.kt',
  'ExtensionUiRegistry.kt', 'ExtensionBootPipeline.kt', 'ExtensionEngine.kt', 'DshPlatform.kt',
]) {
  must(exists(EXT + f), '缺 ' + f + '（扩展系统内核不完整）');
}

const guard = read(EXT + 'ExtensionSafetyGuard.kt');
const loader = read(EXT + 'ExtensionLoader.kt');
const registry = read(EXT + 'ExtensionRegistry.kt');
const manifest = read(EXT + 'ExtensionManifest.kt');
const engine = read(EXT + 'ExtensionEngine.kt');
const extFs = read(EXT + 'ExtensionFs.kt');
const svc = read(EXT + 'ExtensionServiceRegistry.kt');
const hook = read(EXT + 'ExtensionHookRegistry.kt');

// ---- 1. 熔断文件：三条路径都得认，且三种文件名写法都认 ----
must(/val KILL_SWITCH_NAMES = listOf\([\s\S]{0,200}"\.disable_extensions"[\s\S]{0,200}"disable_extensions"[\s\S]{0,200}"disable_extensions\.txt"/.test(guard),
  'KILL_SWITCH_NAMES 要同时认带点 / 不带点 / .txt 三种写法（不同文件管理器的习惯不一样）');
must(/fun killSwitchCandidates\(ctx: Context\): List<File>/.test(guard), '缺 killSwitchCandidates()');
must(/ExtensionLayout\.root\(ctx\)/.test(guard), '没有把私有扩展目录纳入熔断路径');
must(/getExternalFilesDir\(null\)/.test(guard),
  '没有纳入 getExternalFilesDir()：那是「插电脑用 MTP 就能建」的唯一免权限路径');
must(/DIRECTORY_DOWNLOADS/.test(guard), '没有纳入公共下载目录 /sdcard/Download/DSH-Folk');
must(/fun checkAndEnforce\(ctx: Context\)/.test(guard), '缺 checkAndEnforce()：启动时没有任何地方检测熔断');
must(/fun isSafeMode\(ctx: Context\?\): Boolean/.test(guard), '缺 isSafeMode()');
must(/fun latch\(ctx: Context, why: String, writeMarker: Boolean\)/.test(guard), '缺 latch()');
must(/fun isProtectedPath\(ctx: Context, target: File\): Boolean/.test(guard), '缺 isProtectedPath()');
// canonicalPath：否则扩展用符号链接指到标志文件再删，逃生通道就失效
must(/canonicalPath/.test(guard), 'isProtectedPath 必须用 canonicalPath 判定（只比字符串会被符号链接绕过）');

// ---- 2. 崩溃阈值与自动熔断 ----
must(/const val CRASH_THRESHOLD = \d+/.test(guard), '缺崩溃阈值常量');
must(/fun noteCrash\(ctx: Context, detail: String\): Boolean/.test(guard), '缺 noteCrash()：连续崩溃无法自动熔断');
must(/writeMarker = true/.test(guard), '自动熔断没有落标志文件：下次冷启动又会崩在同一个扩展上');
must(/fun stackTouchesExtension\(trace: String\?\): Boolean/.test(guard), '缺 stackTouchesExtension()：分不清是谁崩的');

// ---- 3. Android 14+ 只读 dex（硬约束） ----
must(/setReadOnly\(\)/.test(loader), 'ExtensionLoader 没有 setReadOnly()：Android 14+ 会抛 SecurityException（Writable dex file）');
const readOnlyIdx = loader.indexOf('setReadOnly()');
const dexLoaderIdx = loader.indexOf('DexClassLoader(');
must(readOnlyIdx > 0 && dexLoaderIdx > readOnlyIdx,
  'setReadOnly() 必须在 DexClassLoader 之前调用（顺序反了等于没设）');
must(/ctx\.classLoader,/.test(loader),
  'DexClassLoader 的父加载器必须是宿主 classLoader，否则扩展拿不到宿主任何类');

// ---- 4. 失败隔离：安全模式短路 + 异常不冒泡 ----
must(/if \(ExtensionSafetyGuard\.isLatched\(\)\) return fallback\(\)/.test(svc),
  'SPI 在安全模式下没有退回默认实现');
must(/if \(ExtensionSafetyGuard\.isLatched\(\)\) return true/.test(hook),
  'Hook 的 around 在安全模式下没有直接放行');
must(/if \(ExtensionSafetyGuard\.isLatched\(\)\) return emptyList\(\)/.test(read(EXT + 'ExtensionUiRegistry.kt'))
  || /if \(ExtensionSafetyGuard\.isLatched\(\)\) emptyList\(\)/.test(read(EXT + 'ExtensionUiRegistry.kt')),
  'UI 槽位在安全模式下没有返回空（熔断后界面必须回到原生样子）');
must(/runCatching \{ handler\.run\(payload\) \}/.test(hook), 'Hook 调用没有 runCatching：扩展异常会冒泡进宿主主流程');
must(/runCatching \{[\s\S]{0,80}?ext\.onLoad\(host\)/.test(loader),
  'onLoad 没有 runCatching：一个坏扩展能让宿主起不来');
must(/unregisterAll\(mf\.id\)/.test(loader),
  'onLoad 失败后没有撤掉已注册的 SPI/Hook/UI —— 会留下指向半初始化扩展的悬空回调');

// ---- 5. 编排器 ----
must(/fun bootstrap\(ctx: Context\)/.test(engine), '缺 ExtensionEngine.bootstrap()');
must(/if \(ExtensionSafetyGuard\.isSafeMode\(ctx\)\)/.test(engine), 'bootstrap 没有先看安全模式');
must(/ExtensionSafetyGuard\.isSafeMode\(ctx\)\) \{[\s\S]{0,200}return/.test(engine),
  '安全模式下 bootstrap 没有提前返回（熔断了还在加载扩展）');
must(/fun unloadAll\(ctx: Context\)/.test(engine) && /fun disableAllAndLatch\(ctx: Context, why: String\)/.test(engine),
  '缺 unloadAll / disableAllAndLatch（崩溃页与安全模式都要用）');
for (const reg of ['ExtensionServiceRegistry.unregisterAll', 'ExtensionHookRegistry.unregisterAll',
  'ExtensionUiRegistry.unregisterAll', 'ExtensionBootPipeline.unregisterAll']) {
  must(new RegExp(reg.replace(/\./g, '\\.')).test(engine),
    'unload 没有撤掉 ' + reg + '（会留下悬空回调）');
}

// ---- 6. 目录 API 不得碰熔断标志 ----
must(/ExtensionSafetyGuard\.isProtectedPath\(ctx, file\)/.test(extFs),
  'ExtensionFs 没有拦截保护路径：扩展可以把「禁用全部扩展」的标志删掉');
must(/fun symlink\(/.test(extFs), 'ExtensionFs 缺 symlink（容器相关扩展的基本需求）');

// ---- 7. manifest 校验 ----
must(/fun isValidId\(id: String\): Boolean/.test(read(EXT + 'ExtensionApi.kt')),
  '缺 isValidId()：id 会被直接拼进目录名，必须防 ../ 穿越');
must(/if \(!ExtensionLayout\.isValidId\(id\)\) return null/.test(manifest),
  'manifest 解析没有校验 id 合法性');

// ---- 8. 宿主接线 ----
const app = read('app/src/main/java/me/bmax/apatch/APatchApp.kt');
must(/ExtensionSafetyGuard\.checkAndEnforce\(this\)/.test(app), 'APatchApp 没有在启动时检测熔断');
const checkIdx = app.indexOf('ExtensionSafetyGuard.checkAndEnforce(this)');
const bootIdx = app.indexOf('ExtensionEngine.bootstrap(this)');
must(checkIdx > 0 && bootIdx > checkIdx, '必须「先检测熔断、再加载扩展」，顺序反了等于没有熔断');
must(/getDefaultUncaughtExceptionHandler\(\)/.test(app), '没有校验/复原根崩溃处理器（扩展可以劫持崩溃流）');
must(/ExtensionSafetyGuard\.noteCrash\(/.test(app), '崩溃时没有计入扩展崩溃（连续崩溃不会自动熔断）');

const crash = read('app/src/main/java/me/bmax/apatch/ui/CrashHandleActivity.kt');
must(/ExtensionEngine\.disableAllAndLatch\(/.test(crash), '崩溃页没有「禁用全部扩展」的救援入口');
must(!/ExtensionUiRegistry/.test(crash), '崩溃页不许接扩展 UI（会被扩展接管，用户就点不到救援按钮了）');

const screen = read('app/src/main/java/me/bmax/apatch/ui/screen/DshPluginScreen.kt');
must(/ExtensionRegistry\.listInstalled\(/.test(screen), '扩展页没有列真实扩展（还是占位文案）');
must(/ExtensionSafetyGuard\.isSafeMode\(/.test(screen), '扩展页没有安全模式横幅');
must(/ExtensionEngine\.unload\(/.test(screen), '关闭扩展时没有卸载（会留下仍在跑的扩展）');
// 打开开关 = 授予完整权限：必须有信任确认，否则一次误触就等于授权
must(/dsh_ext_trust_title/.test(screen) && /dsh_ext_trust_accept/.test(screen),
  '启用扩展前没有「以完整权限运行」的信任确认');
must(/dsh_ext_trust_body/.test(screen), '信任确认没有列出扩展声明的能力/说明');

// ---- 9. 文案两种语言都要有 ----
const zh = read('app/src/main/res/values-zh-rCN/dsh_strings.xml');
const en = read('app/src/main/res/values/dsh_strings.xml');
for (const key of ['dsh_ext_title', 'dsh_ext_empty', 'dsh_ext_safe_mode', 'dsh_ext_crash_disable_all']) {
  must(new RegExp('name="' + key + '"').test(zh), '中文缺字符串 ' + key);
  must(new RegExp('name="' + key + '"').test(en), '英文缺字符串 ' + key);
}

if (errors.length) {
  console.error('check-extensions FAILED:');
  for (const e of errors) console.error('  ✗ ' + e);
  process.exit(1);
}
console.log('check-extensions: 通过');
