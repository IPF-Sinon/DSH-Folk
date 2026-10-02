#!/usr/bin/env node
// 门禁：虚拟屏服务端（displayserver/ → dsh-display-server.jar）。
//
// 这个子系统有几个**安静失效**的方式，全都不会让编译变红：
//
//  1. jar 里装的是普通 .class 而不是 classes.dex。app_process 用 CLASSPATH 加载，
//     ART 只认 dex —— 装错了命令能起来、然后 ClassNotFoundException，而这一路
//     要到真机上才暴露。
//  2. 又把资源塞进 jar。移植源（Operit 的 tools/shower）就是把整个 app 模块打成了 jar，
//     里面 408KB 的 resources.arsc、一堆 res 图片和四个 ABI 的 .so 对 app_process
//     全是死重量 —— 那是它那个用不到的 Compose 界面带进来的。这个门禁守住"只装
//     classes.dex"，否则体积会不知不觉从 200KB 涨回 1MB。
//  3. 服务端源码里混进 androidx / kotlin 依赖。那会把整套库拖进 dex，而且它跑在
//     app_process 里，没有 App 的 classloader 帮忙。
//  4. 服务端开了网络监听。以 root 跑一个本地监听 socket，等于把"注入输入"的能力
//     开放给机器上任意 App。原实现里有个从未实现的 WebSocket 残留（DEFAULT_PORT 与
//     三个 socket import），已经删掉，这里防止它长回来。
//  5. jar 被提交进 git。那样改了源码却忘了重编，会静默发出一个旧服务端。
//
// 用法：
//   node tools/check-display-server.js                 静态检查（CI 的编译前一步）
//   node tools/check-display-server.js --jar <path>    额外校验真实产物（编译后一步）

'use strict';
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const errors = [];
const must = (c, m) => { if (!c) errors.push(m); };
const root = path.resolve(__dirname, '..');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');
const exists = (p) => fs.existsSync(path.join(root, p));
/** 剥掉注释再断言「不该出现 / 必须出现 X」，避免命中解释用的注释（与其它门禁同一约定）。 */
const code = (src) => src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');

const SERVER_DIR = 'displayserver/src/main/java/me/bmax/apatch/display';
const APP_PROTO_DIR = 'app/src/main/java/me/bmax/apatch/display';

// ── 1. 服务端源码清单 ──
// 只保留 app_process 真正需要的类：没有 MainActivity、没有 ui/theme（那是 Compose）。
const EXPECTED_SERVER = [
  'AndroidVersions.java',
  'DisplayCapture.java',
  'InputController.java',
  'Main.java',
  'package-info.java',
  'device/DisplayInfo.java',
  'device/Size.java',
  'shell/FakeContext.java',
  'shell/Workarounds.java',
  'wrappers/ActivityManager.java',
  'wrappers/DisplayManager.java',
  'wrappers/ServiceManager.java',
  'wrappers/SurfaceControl.java',
  'wrappers/WindowManager.java',
];
const SKINNY_TARGET = 20 * 1024; // 20KB：服务端总源码量应与"13 个类"相称
for (const f of EXPECTED_SERVER) {
  must(exists(`${SERVER_DIR}/${f}`), `displayserver 缺 ${f}`);
}
const actualServer = [];
(function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p);
    else if (e.name.endsWith('.java')) actualServer.push(path.relative(path.join(root, SERVER_DIR), p).split(path.sep).join('/'));
  }
})(path.join(root, SERVER_DIR));
for (const f of actualServer) {
  must(EXPECTED_SERVER.includes(f), `displayserver 多出一个未预期的类 ${f}（移植源的 Compose 界面不该进来）`);
}

// ── 2. 两端共享的 Binder 协议类必须在 app 源码树里 ──
// 它们在 App 侧与服务端侧都要存在（Binder 的 DESCRIPTOR 必须逐字一致），
// 但又不能把 13 个服务端专属类也编进 App 的 dex。
const SHARED = ['IDisplayService.java', 'IDisplayVideoSink.java', 'DisplayBinderContainer.java'];
for (const f of SHARED) {
  must(exists(`${APP_PROTO_DIR}/${f}`), `app 侧缺协议类 ${f}`);
  must(!exists(`${SERVER_DIR}/${f}`), `${f} 应只存在于 app 源码树（服务端构建时引用它），不该在 displayserver 里`);
}

// ── 3. 包名 / 标识：不许有移植源的残留 ──
{
  const all = [];
  (function walk(dir) {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      const p = path.join(dir, e.name);
      if (e.isDirectory()) walk(p);
      else if (e.name.endsWith('.java')) all.push(p);
    }
  })(path.join(root, SERVER_DIR));
  for (const p of all) {
    const src = fs.readFileSync(p, 'utf8');
    const rel = path.relative(root, p);
    // 只在代码里查残留：package-info.java 的署名段落**应该**提到原来的包名，
    // 那是出处说明，不是没改干净。
    const srcCode = code(src);
    must(!/com\.ai\.assistance\.shower/.test(srcCode), `${rel} 还有移植源的包名 com.ai.assistance.shower`);
    must(!/IShowerService|IShowerVideoSink|ShowerBinderContainer/.test(srcCode), `${rel} 还有移植源的类名（Shower*）`);
    must(!/SHOWER_BINDER_READY/.test(srcCode), `${rel} 还有移植源的广播常量名`);
    const pkg = (src.match(/^package\s+([\w.]+);/m) || [])[1];
    must(!!pkg && pkg.startsWith('me.bmax.apatch.display'), `${rel} 的 package 不是 me.bmax.apatch.display*（实际 ${pkg}）`);
  }
  // 广播 action 必须是本项目自己的
  const main = code(read(`${SERVER_DIR}/Main.java`));
  must(/ACTION_BINDER_READY\s*=\s*"me\.bmax\.apatch\.action\.DISPLAY_BINDER_READY"/.test(main),
    'Main.java 的广播 action 必须是 me.bmax.apatch.action.DISPLAY_BINDER_READY');
  must(/public\s+class\s+Main\b/.test(main), 'Main.java 必须声明 public class Main（app_process 的入口类）');
  must(/static\s+void\s+main\s*\(\s*String\s*\.\.\.\s*\w+/.test(main) || /static\s+void\s+main\s*\(\s*String\s*\[\s*\]/.test(main),
    'Main.main 必须接参数（启动命令要把宿主包名传进去，否则 binder 回传没有投递目标）');

  // 视频流的自足性。移植源的帧流有个会让画面**永久黑屏且不报错**的坑：编解码器配置
  // （csd-0/csd-1 = SPS/PPS）只在 INFO_OUTPUT_FORMAT_CHANGED 时发一次，而宿主界面是用户
  // 点开预览才挂 sink 的 —— 那时配置早发过了。修法是两条保险，两条都得在。
  must(/KEY_PREPEND_HEADER_TO_SYNC_FRAMES/.test(main),
    '编码器必须设 KEY_PREPEND_HEADER_TO_SYNC_FRAMES，让关键帧自带 SPS/PPS（否则帧流不自足）');
  must(/configSps\s*=\s*toBytes\(csd0\)/.test(main) && /configPps\s*=\s*toBytes\(csd1\)/.test(main),
    'trySendConfig 必须把 SPS/PPS **存下来**（只声明字段不算：那还是没东西可补发）');
  must(/freshSink/.test(main),
    'setVideoSink 必须把补发所需的状态先搬出锁，再在锁外做 Binder 调用');
  must(/void setVideoSink\(IBinder sink\) \{[\s\S]{0,160}?freshSink = null[\s\S]{0,160}?synchronized \(lock\)/.test(main),
    'setVideoSink 必须在进入 synchronized 之前声明 freshSink/sps/pps —— 持锁调用客户端的 ' +
    'onVideoFrame 会把编码线程堵住（画面卡住），客户端回调进来还会死锁');
  must(/requestSyncFrame\(\);/.test(main) && /PARAMETER_KEY_REQUEST_SYNC_FRAME/.test(main),
    '挂上 sink 后要**调用**一次请求关键帧，否则最多要等 1 秒才出画面（只定义不调用等于没有）');
}

// ── 4. 不许引入 androidx / kotlin，也不许开网络监听 ──
{
  const bad = [];
  (function walk(dir) {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      const p = path.join(dir, e.name);
      if (e.isDirectory()) walk(p);
      else if (e.name.endsWith('.java')) bad.push(p);
    }
  })(path.join(root, SERVER_DIR));
  for (const p of bad) {
    const src = code(fs.readFileSync(p, 'utf8'));
    const rel = path.relative(root, p);
    must(!/^\s*import\s+androidx\./m.test(src), `${rel} 引入了 androidx（服务端跑在 app_process 里，没有 App 的 classloader）`);
    must(!/^\s*import\s+kotlin\./m.test(src), `${rel} 引入了 kotlin`);
    must(!/ServerSocket|HttpServer|\.bind\(|\.accept\(\)/.test(src),
      `${rel} 出现了网络监听/连接。以 root 开本地 socket = 把注入输入的能力开放给任意 App；服务端只应通过 Binder 提供服务`);
    must(!/DEFAULT_PORT/.test(src), `${rel} 还留着移植源那个从未实现的 WebSocket 端口常量`);
  }
}

// ── 5. Gradle 接线 ──
{
  const g = read('app/build.gradle.kts');
  must(/tasks\.register<BuildDisplayServerJar>\(\s*"buildDisplayServerJar"\s*\)/.test(g),
    'app/build.gradle.kts 缺 buildDisplayServerJar 任务');
  must(/abstract\s+class\s+BuildDisplayServerJar\s*:\s*DefaultTask\(\)/.test(g), '缺 BuildDisplayServerJar 任务类');
  must(/execOps\.exec/.test(g), '构建必须经注入的 ExecOperations（Gradle 9 已移除 project.exec）');
  must(/javaToolchains\.launcherFor\s*\{\s*languageVersion\.set\(JavaLanguageVersion\.of\(21\)\)/.test(g),
    'javac 必须优先走 Java 21 工具链（用守护进程的 JDK 会产出 d8 不认识的 class 版本）');
  must(/javacPath\.set\(resolvedJavacPath\)/.test(g), '任务的 javacPath 必须绑定到解析出来的 javac');
  // 平台目录名有三种形态：android-34 / android-34-ext10（扩展级，不是给普通编译用的）/
  // android-37.1（新的小版本号形态）。第一版只按 android-<compileSdk> 找，在 CI 上永远找不到
  // —— 实测 runner 上 android-37 这个目录根本不存在，只有 37.0/37.1/37.2；更糟的是退回逻辑
  // 把 "37.1".toIntOrNull() 解析成 null 直接丢掉，最终错误地退到 android-36。
  //
  // 断言必须盯**调用点**而不只是函数存在：函数留着、调用点改回旧写法，正是最可能发生的那种
  // "看起来没删干净"的回归。
  must(/Regex\("\^android-\(/.test(g) && /platformVersion\(d\.name\)\?\.let \{ \(major, minor\)/.test(g),
    '平台目录名必须经 platformVersion() 解析后再用（只认 android-<主版本> 会漏掉 android-37.1）');
  must(/filter \{ it\.first == wantSdk \}\.maxByOrNull \{ it\.second \}/.test(g),
    '平台选择必须先按主版本精确匹配、再退回最高版本');
  must(/if \(picked\.first != wantSdk\) \{/.test(g) && /没有 android-\$wantSdk 平台，退回/.test(g),
    '退回时必须打日志说明用的是哪个平台，否则"用了更旧的 android.jar"这件事完全不可见');
  // 平台也可能是**还没装**（AGP 是懒加载的：直到编译任务才去装，而本任务挂在资源合并上、
  // 跑在那之前）。所以任务必须"有就用、没有就退回"，不能硬要求精确路径存在。
  must(/check\(picked != null\)/.test(g), '平台一个都找不到时必须给出可执行的报错，而不是让 android.jar 路径空着');
  must(/Ensure compile SDK platform/.test(read('.github/actions/setup-build-env/action.yml')),
    'setup-build-env 必须显式确保 compileSdk 平台（否则只能靠任务兜底，走进不确定的那条路）');
  must(/d8\.absolutePath,\s*"--min-api",\s*minSdkVersion\.get\(\)\.toString\(\)/.test(g),
    'd8 的 --min-api 必须绑定到 app 的 minSdk（写死会与 app 漂移）');
  must(/ZipEntry\("classes\.dex"\)/.test(g), 'jar 里必须且只能装 classes.dex');
  must(!/putNextEntry\(ZipEntry\("(?!classes\.dex")/.test(g), 'jar 里混进了 classes.dex 以外的条目');
  must(/assets\.srcDir\(displayServerJarDir/.test(g), '生成目录必须注册为 assets 源目录，否则 jar 不进 APK');
  must(/tasks\.matching\s*\{\s*it\.name\.matches\(Regex\("merge\.\*Assets"\)\)\s*\}\s*\.configureEach\s*\{\s*dependsOn\(buildDisplayServerJar\)/.test(g),
    'assets 合并任务必须 dependsOn(buildDisplayServerJar)');
}

// ── 6. jar 不许提交进 git ──
{
  const assets = path.join(root, 'app/src/main/assets');
  const jars = fs.existsSync(assets) ? fs.readdirSync(assets).filter((f) => f.endsWith('.jar')) : [];
  must(jars.length === 0,
    `app/src/main/assets 里不应有提交进 git 的 jar（${jars.join(', ')}）：构建产物必须由 buildDisplayServerJar 生成，否则源码改了而 jar 没重编会静默发出旧服务端`);
}

// ── 7. 署名（LGPLv3 与 GPLv3 兼容，但必须留出处）──
{
  const rd = read('README.md');
  must(/AAswordman\/Operit/.test(rd), 'README 致谢里必须写明移植自 Operit');
  must(/LGPL/.test(rd), 'README 必须写明 Operit 是 LGPL-3.0（许可兼容性依赖这个事实）');
  must(/Genymobile\/scrcpy/.test(rd), 'README 必须写明手法源自 scrcpy');
  must(/displayserver/.test(rd), 'README 必须说明 displayserver/ 是移植产物');
  const pi = read(`${SERVER_DIR}/package-info.java`);
  must(/Operit/.test(pi) && /LGPL/.test(pi), 'package-info.java 必须带上来源与许可说明');
}

// ── 8. Binder 协议：手写 AIDL 的三处必须齐全且一致 ──
//
// 这个协议是手写的（不是 aidl 生成的），加一个方法要同时改三个地方：接口声明、
// TRANSACTION_* 常量、Stub.onTransact 的 case、Proxy 的实现。漏掉 onTransact 那一处的
// 后果最阴：调用方拿到的是"事务没人接"，而不是编译错误 —— 比如漏掉 ping，
// 表现就是服务端在 agent 思考时莫名其妙自己退出。
{
  const proto = read(`${APP_PROTO_DIR}/IDisplayService.java`);
  const pCode = code(proto);
  const ifaceAt = pCode.indexOf('public interface IDisplayService');
  const stubAt = pCode.indexOf('abstract class Stub');
  const proxyAt = pCode.indexOf('private static final class Proxy');
  must(ifaceAt >= 0 && stubAt > ifaceAt && proxyAt > stubAt, 'IDisplayService.java 结构不完整（接口/Stub/Proxy 三段）');
  if (ifaceAt >= 0 && stubAt > ifaceAt && proxyAt > stubAt) {
    const iface = pCode.slice(ifaceAt, stubAt);
    const proxy = pCode.slice(proxyAt);
    const methods = [...iface.matchAll(/^\s*(?:void|int|boolean|String|byte\[\])\s+(\w+)\s*\(/gm)].map((m) => m[1]);
    must(methods.length >= 13, `IDisplayService 只解析出 ${methods.length} 个方法，解析或文件有问题`);
    must(methods.includes('ping'), 'IDisplayService 必须保留 ping（服务端空闲看门狗靠它续命）');
    for (const m of methods) {
      must(new RegExp(`TRANSACTION_${m}\\s*=`).test(pCode), `IDisplayService 缺少 TRANSACTION_${m} 常量`);
      must(new RegExp(`case\\s+TRANSACTION_${m}\\s*:`).test(pCode), `IDisplayService 的 onTransact 缺少 ${m} 的分支`);
      must(new RegExp(`\\b${m}\\s*\\(`).test(proxy), `IDisplayService 的 Proxy 缺少 ${m} 的实现`);
    }
    // 事务码不能重复：复制粘贴时最容易撞，撞了就是"调 A 跑到 B"
    const codes = [...pCode.matchAll(/TRANSACTION_\w+\s*=\s*IBinder\.FIRST_CALL_TRANSACTION(?:\s*\+\s*(\d+))?/g)]
      .map((m) => (m[1] === undefined ? 0 : Number(m[1])));
    must(new Set(codes).size === codes.length, `TRANSACTION 码有重复：${codes.join(', ')}`);
  }
  const sink = code(read(`${APP_PROTO_DIR}/IDisplayVideoSink.java`));
  must(/onVideoFrame\s*\(\s*byte\[\]/.test(sink), 'IDisplayVideoSink.onVideoFrame(byte[]) 签名被改了（服务端按这个签名回传帧）');
  for (const f of ['IDisplayService', 'IDisplayVideoSink']) {
    const src = code(read(`${APP_PROTO_DIR}/${f}.java`));
    const d = src.match(/DESCRIPTOR\s*=\s*"([^"]+)"/);
    must(!!d && d[1] === `me.bmax.apatch.display.${f}`,
      `${f} 的 DESCRIPTOR 必须是 me.bmax.apatch.display.${f}，两端不一致会导致 transact 全部失败`);
  }
}

// ── 9. App 侧 [DisplayServer.kt] 与服务端的约定必须逐字一致 ──
//
// 这些是跨进程的字符串契约，不一致时不会编译失败：广播收不到、或收得到但 token 对不上，
// 表现都是"启动超时"，而真因藏在名字里。
{
  const kt = code(read('app/src/main/java/me/bmax/apatch/dsh/DisplayServer.kt'));
  const java = code(read(`${SERVER_DIR}/Main.java`));
  const actions = [
    ['ACTION_BINDER_READY', '交接广播的 action'],
    ['EXTRA_BINDER_CONTAINER', 'binder 容器 extra 的键'],
    ['EXTRA_BINDER_TOKEN', 'token extra 的键'],
  ];
  for (const [name, what] of actions) {
    const inKt = kt.match(new RegExp(`${name}\\s*=\\s*"([^"]+)"`));
    const inJava = java.match(new RegExp(`${name}\\s*=\\s*"([^"]+)"`));
    must(!!inKt && !!inJava && inKt[1] === inJava[1],
      `${what}在 App 侧与服务端不一致：App=${inKt ? inKt[1] : '(缺)'}，服务端=${inJava ? inJava[1] : '(缺)'}`);
  }
  must(/me\.bmax\.apatch\.display\.Main/.test(kt) && /public\s+class\s+Main\b/.test(java),
    'App 侧启动的入口类必须与服务端里的 Main 类同名');
  must(/dsh-display-server\.jar/.test(kt) && /ZipEntry\("classes\.dex"\)/.test(read('app/build.gradle.kts')),
    'App 侧读的 jar 名必须与构建产物的名字一致');
  // 服务端侧的对应实现
  must(/public\s+void\s+ping\s*\(/.test(java), '服务端的 Stub 必须实现 ping（否则事务没人接，看门狗照旧杀进程）');
  must(/putExtra\(EXTRA_BINDER_TOKEN/.test(java), '服务端必须把 token 放进交接广播');
  must(/args\.length\s*>\s*1/.test(java), '服务端必须从 argv[1] 读 token');
  // App 侧的几个必须项，每一个都对应一类只在真机上才暴露的失败
  must(/R\.string|RECEIVER_EXPORTED/.test(kt) && /RECEIVER_EXPORTED/.test(kt),
    'App 侧在 API 33+ 必须用 RECEIVER_EXPORTED 注册：发送方是 root/shell，not-exported 收不到');
  must(/classLoader\s*=\s*DisplayBinderContainer::class\.java\.classLoader/.test(kt),
    'App 侧必须给 Intent 显式设置 classloader，否则跨进程还原 DisplayBinderContainer 会 ClassNotFoundException');
  must(/"\[m\]/.test(kt), 'App 侧 pkill/pgrep 的模式必须带 [m] 括号，否则会连承载命令的 shell 一起杀掉');
  must(/setsid/.test(kt), '启动命令必须处理 setsid（ADB 通道下 adbd 会清掉会话的进程组，后台子进程会被带走）');
  must(/sha256/.test(kt), 'App 侧必须校验推送后的哈希（分块传输最典型的失败是静默截断）');
}

// ── 10. 括号配平（一个便宜的语法代理）──
//
// 没有本地 Android SDK 时 Java/Kotlin 一行都编不了，而手工插入方法最典型的失误就是
// 吃掉一个收尾大括号 —— 那会在 CI 上变成一条与真因完全无关的报错。这里跳过字符串与
// 注释后数括号，成本几乎为零。
{
  const balance = (src) => {
    let d = 0;
    let i = 0;
    let inStr = null;
    while (i < src.length) {
      const c = src[i];
      const n = src[i + 1];
      if (inStr) {
        if (c === '\\') { i += 2; continue; }
        if (c === inStr) inStr = null;
        i++;
        continue;
      }
      if (c === '"' || c === "'") { inStr = c; i++; continue; }
      if (c === '/' && n === '/') { while (i < src.length && src[i] !== '\n') i++; continue; }
      if (c === '/' && n === '*') { i += 2; while (i < src.length && !(src[i] === '*' && src[i + 1] === '/')) i++; i += 2; continue; }
      if (c === '{') d++;
      else if (c === '}') { d--; if (d < 0) return -1; }
      i++;
    }
    return d;
  };
  const files = [
    `${APP_PROTO_DIR}/IDisplayService.java`,
    `${APP_PROTO_DIR}/IDisplayVideoSink.java`,
    `${APP_PROTO_DIR}/DisplayBinderContainer.java`,
    'app/src/main/java/me/bmax/apatch/dsh/DisplayServer.kt',
    `${SERVER_DIR}/Main.java`,
  ];
  for (const f of files) {
    const b = balance(read(f));
    must(b === 0, `${f} 的花括号不配平（差 ${b}）—— 大概率是插入方法时吃掉了收尾括号`);
  }
}

// ── 11. 工具面接线（/native/display/* → Cap.DISPLAY）──
//
// 路由要在四个地方各登记一次：capOf（决定用哪项能力）、主分派（真正处理）、
// isWriteRequest（读档位能不能碰）、以及 handler 自己的 when。少一处的后果都不报编译错：
// 少 capOf 就是 unknown_endpoint，少分派就是 404，少 isWriteRequest 就是读档位能动手。
{
  const bridge = code(read('app/src/main/java/me/bmax/apatch/dsh/DshNativeBridge.kt'));
  const disp = code(read('app/src/main/java/me/bmax/apatch/dsh/DshDisplay.kt'));

  must(/DISPLAY\("display"\)/.test(bridge), 'Cap 枚举里必须有 DISPLAY("display")');
  must(/Cap\.SHELL,\s*Cap\.DISPLAY\s*->\s*PrivilegedShell\.reach/.test(bridge),
    'DISPLAY 的可用性必须与 SHELL 一样看提权通道就绪情况（否则报不出精确原因）');
  must(/Cap\.A11Y,\s*Cap\.DISPLAY\s*->\s*true/.test(bridge), 'supportsWrite 必须把 DISPLAY 算作可写');
  must(/Cap\.DISPLAY\s*->\s*PrivRisk\.DANGEROUS/.test(bridge),
    'DISPLAY 的风险档必须是 DANGEROUS（注入输入与启动 App 都能真实改变设备状态）');
  must(/-> display\(ctx, method, path, params\)/.test(bridge),
    '主分派必须把 display 端点转到 display(...) 转发口');

  // 两边登记的路由必须完全一致：handler 里有而 capOf 里没有 → 能力判定为 null、永远
  // unknown_endpoint；capOf 里有而 handler 里没有 → 稳定 404。
  const routesIn = (src, re) => new Set([...src.matchAll(re)].map((m) => m[1]));
  const handled = routesIn(disp, /"(\/native\/display\/\w+)"/g);
  const mapped = routesIn(bridge, /"(\/native\/display\/\w+)"/g);
  // 每条路由都要在主分派表里**显式**出现，不能用 path.startsWith 一把兜住：
  // check-native-caps 门禁是「capOf 声明的端点都必须在分派表里显式出现」，前缀兜底会让它
  // 看不见这些路由，于是新加端点时漏接线也无人拦。
  for (const r of mapped) {
    must(new RegExp(`path == "${r}"`).test(bridge), `${r} 必须在主分派表里显式出现，而不是靠前缀兜住`);
  }
  must(handled.size >= 8, `DshDisplay 只分派了 ${handled.size} 条路由，预期至少 8 条`);
  for (const r of handled) must(mapped.has(r), `${r} 在 handler 里处理了，但 capOf 没登记它（能力判定会是 null）`);
  for (const r of mapped) must(handled.has(r), `${r} 在 capOf 里登记了，但 handler 不处理它（会稳定 404）`);

  // 读档位只该放行「看」：截图与查询
  must(/path == "\/native\/display\/status" \|\| path == "\/native\/display\/screenshot" -> false/.test(bridge),
    'isWriteRequest 必须把 display 的 status/screenshot 判为读');
  must(/path\.startsWith\("\/native\/display\/"\) -> true/.test(bridge),
    'isWriteRequest 必须把 display 其余端点判为写（否则读档位也能点击/启动 App）');

  // 截图回路径而不是字节：PNG 走 base64 塞进 JSON 会膨胀 33%，而且 agent 本来就有文件工具
  must(/stageGuestPath/.test(disp), '截图必须落到暂存目录并回带容器内路径，而不是把 PNG 塞进 JSON');
  must(/pngSize|be32/.test(disp), '截图响应要带画面尺寸，agent 算点击坐标时要用');
}

// ── 12. 容器侧 CLI + 主机提示词 ──
//
// 提示词里写的 `dsh-native display …` 是**对 agent 的许诺**。许诺了而 CLI 不认，agent 拿到的是
// command not found，而它会去翻能力开关 —— 那条路永远查不出原因。所以这两份东西必须对齐。
//
// 另一半是送达：dsh-native 是 App 自己写进容器的。如果只在引导路径写，升级 App 而沿用旧 rootfs
// 的用户永远拿不到新子命令。所以既要有"每次启动对一遍"的调用点，也要按内容比对再写。
{
  const rt = code(read('app/src/main/java/me/bmax/apatch/dsh/DshRuntime.kt'));
  const host = read('app/src/main/assets/dsh-folk-host.mjs');

  must(/cmd === 'display'/.test(rt), 'dsh-native CLI 必须有 display 子命令分支');
  // CAP_USAGE 里许诺的子命令，CLI 必须都认
  const usageBlock = (() => {
    const at = host.indexOf('display: [');
    if (at < 0) return '';
    const end = host.indexOf('\n  ],', at);
    return end < 0 ? '' : host.slice(at, end);
  })();
  must(usageBlock.length > 0, '主机提示词的 CAP_USAGE 里必须有 display 一节');
  const promised = [...usageBlock.matchAll(/'dsh-native display (\w+)/g)].map((m) => m[1]);
  must(promised.length >= 7, `CAP_USAGE 的 display 一节只解析出 ${promised.length} 条用法`);
  for (const sub of new Set(promised)) {
    const alt = sub === 'shot' ? /act === 'shot' \|\| act === 'screenshot'/ : new RegExp(`act === '${sub}'`);
    must(alt.test(rt), `提示词许诺了 'dsh-native display ${sub}'，但 CLI 没有这个分支`);
  }
  // CAP_CAVEAT 必须自己有一节。断言要精确到这个块里，不能只查全文有没有 "display:" ——
  // CAP_USAGE 里的 `display: [` 会把那种松检查满足掉，于是删掉注意事项也照样通过。
  const caveatBlock = (() => {
    const at = host.indexOf('const CAP_CAVEAT');
    if (at < 0) return '';
    const end = host.indexOf('\n};', at);
    return end < 0 ? '' : host.slice(at, end);
  })();
  must(/^ {2}display:/m.test(caveatBlock),
    'CAP_CAVEAT 必须有 display 一节（它需要提权通道、会 15 秒自退，agent 得知道这是状态不是错误）');

  // 送达：必须有一个"非引导路径"的调用点，而且要按内容比对
  const callSites = [...rt.matchAll(/ensureFsBridgeCli\(\)/g)].length;
  must(callSites >= 2, `ensureFsBridgeCli() 只有 ${callSites} 处调用（定义之外一处都没有？）`);
  must(/f\.readText\(\)\s*\}\s*\.getOrNull\(\)\s*==\s*script/.test(rt),
    'ensureFsBridgeCli 必须按内容比对再写（每次启动都会调用它，无脑重写会白磨盘）');
  must(/forwardOutput\(serverProcess\)[\s\S]{0,400}?ensureFsBridgeCli\(\)/.test(rt),
    'ensureFsBridgeCli 必须挂在每次启动的路径上，否则升级 App 而沿用旧 rootfs 的用户拿不到新 CLI');
}

// ── 13. 内嵌 CLI 的 JS 语法 ──
//
// `dsh-native` 是一段**内嵌在 Kotlin 里的 JavaScript**（DshRuntime.NATIVE_CLI_SCRIPT），
// 我的 display 子命令就在里面。Kotlin 编译器只会把它当成一个字符串常量，里面写错语法它一句
// 都不会说 —— 直到容器里 agent 真的去调，才发现整个 CLI 是坏的。所以这里把它抽出来交给 node
// 做一次语法检查（与手动 `node --check` 同一件事）。
{
  const rt = read('app/src/main/java/me/bmax/apatch/dsh/DshRuntime.kt');
  const marker = 'private val NATIVE_CLI_SCRIPT = """';
  const at = rt.indexOf(marker);
  must(at >= 0, '找不到 NATIVE_CLI_SCRIPT（内嵌 CLI 的抽取标记失效了）');
  if (at >= 0) {
    const start = at + marker.length;
    const end = rt.indexOf('"""', start);
    must(end > start, 'NATIVE_CLI_SCRIPT 的 raw string 没有闭合');
    if (end > start) {
      // raw string 的首行有缩进、并且以 shebang 开头；两者都要去掉再交给 node
      const js = rt.slice(start, end)
        .split('\n')
        .filter((l, i) => !(i === 0 && l.trim() === '') && !l.trim().startsWith('#!'))
        .join('\n');
      const tmp = path.join(require('os').tmpdir(), `dsh-native-cli-${process.pid}.js`);
      fs.writeFileSync(tmp, js);
      const r = require('child_process').spawnSync(process.execPath, ['--check', tmp], { encoding: 'utf8' });
      must(r.status === 0,
        `内嵌的 dsh-native CLI 有 JS 语法错误（Kotlin 编译查不出来）：${(r.stderr || '').split('\n').slice(0, 4).join(' / ')}`);
      try { fs.unlinkSync(tmp); } catch (_) { /* 临时文件清不掉不影响结论 */ }
    }
  }
  // 主机提示词也是 JS，同样只在自己运行时才暴露
  const hostCheck = require('child_process').spawnSync(process.execPath, ['--check', path.join(root, 'app/src/main/assets/dsh-folk-host.mjs')], { encoding: 'utf8' });
  must(hostCheck.status === 0, `主机提示词 dsh-folk-host.mjs 有 JS 语法错误：${(hostCheck.stderr || '').split('\n').slice(0, 3).join(' / ')}`);
}

// ── 14. 产物校验（编译之后跑）──




function zipEntries(buf) {
  let eocd = -1;
  for (let i = buf.length - 22; i >= 0 && i > buf.length - 65558; i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('不是合法的 zip/jar');
  const n = buf.readUInt16LE(eocd + 10);
  let off = buf.readUInt32LE(eocd + 16);
  const out = [];
  for (let k = 0; k < n; k++) {
    if (buf.readUInt32LE(off) !== 0x02014b50) throw new Error('中央目录损坏');
    const method = buf.readUInt16LE(off + 10);
    const compSize = buf.readUInt32LE(off + 20);
    const uncompSize = buf.readUInt32LE(off + 24);
    const nameLen = buf.readUInt16LE(off + 28);
    const extraLen = buf.readUInt16LE(off + 30);
    const commentLen = buf.readUInt16LE(off + 32);
    out.push({ name: buf.toString('utf8', off + 46, off + 46 + nameLen), method, compSize, uncompSize });
    off += 46 + nameLen + extraLen + commentLen;
  }
  return out;
}

const jarArgAt = process.argv.indexOf('--jar');
if (jarArgAt >= 0) {
  const jarPath = process.argv[jarArgAt + 1];
  if (!jarPath || !fs.existsSync(jarPath)) {
    errors.push(`--jar 指定的文件不存在：${jarPath}`);
  } else {
    const buf = fs.readFileSync(jarPath);
    let entries;
    try {
      entries = zipEntries(buf);
    } catch (e) {
      entries = null;
      errors.push(`jar 解析失败：${e.message}`);
    }
    if (entries) {
      const names = entries.map((e) => e.name);
      must(names.length === 1 && names[0] === 'classes.dex',
        `jar 里应当只有 classes.dex 一个条目，实际：[${names.join(', ')}]`);
      for (const e of entries) {
        if (e.name !== 'classes.dex') continue;
        // 实测基线：这 13 个服务端类编出来是 **58KB**（移植源那个 jar 里是 1112KB —— 因为它
        // 把整个 app 模块连 Compose 一起打了进去）。上下界都留足余量，只拦两种极端：
        // "javac 全失败、dex 是空壳" 与 "把一大坨库拖了进来"。第一版把下界写成 64KB，
        // 结果把自己 58KB 的正常产物拦下来了（CI 上门禁自己失败）。
        must(e.uncompSize > 24 * 1024, `classes.dex 只有 ${e.uncompSize} 字节，不像编进了服务端（javac 是不是全失败了？）`);
        must(e.uncompSize < 512 * 1024,
          `classes.dex 有 ${(e.uncompSize / 1024).toFixed(0)}KB —— 移植基线是 58KB，这个体量像是把 androidx/Compose 拖进来了`);
      }
      // dex 头部自检：magic "dex\n" + version
      const dexAt = (() => {
        // 用本地文件头定位数据区
        let eocd = -1;
        for (let i = buf.length - 22; i >= 0; i--) { if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; } }
        let off = buf.readUInt32LE(eocd + 16);
        const n = buf.readUInt16LE(eocd + 10);
        for (let k = 0; k < n; k++) {
          const method = buf.readUInt16LE(off + 10);
          const cs = buf.readUInt32LE(off + 20);
          const nl = buf.readUInt16LE(off + 28);
          const el = buf.readUInt16LE(off + 30);
          const cl = buf.readUInt16LE(off + 32);
          const lo = buf.readUInt32LE(off + 42);
          const nm = buf.toString('utf8', off + 46, off + 46 + nl);
          if (nm === 'classes.dex') {
            const lnl = buf.readUInt16LE(lo + 26);
            const lel = buf.readUInt16LE(lo + 28);
            const raw = buf.subarray(lo + 30 + lnl + lel, lo + 30 + lnl + lel + cs);
            return method === 0 ? raw : zlib.inflateRawSync(raw);
          }
          off += 46 + nl + el + cl;
        }
        return null;
      })();
      must(dexAt !== null, '在 jar 里定位不到 classes.dex 的数据区');
      if (dexAt) {
        must(dexAt.subarray(0, 4).toString('latin1') === 'dex\n',
          `classes.dex 的魔数不对（${dexAt.subarray(0, 4).toString('latin1')}）—— 装的可能是普通 .class`);
      }
    }
  }
}

if (errors.length) {
  console.error('check-display-server FAILED:');
  for (const e of errors) console.error('  ✗ ' + e);
  process.exit(1);
}
console.log(`check-display-server: 通过（${actualServer.length} 个服务端类${jarArgAt >= 0 ? ' + 产物校验' : ''}）`);
