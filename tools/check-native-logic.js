// 新增能力的行为验证：把每个实现里可测的纯逻辑抠出来，用 Node 复刻一遍。
//
// 测不到的是 Android 框架调用本身（没有设备也没有 SDK），能测的是决策逻辑 ——
// 而错误几乎都在决策上：单位换算、边界钳制、权限分流、降级顺序。
const fs = require("fs");

let n = 0;
let bad = 0;
function eq(actual, expected, msg) {
  n++;
  const okk = JSON.stringify(actual) === JSON.stringify(expected);
  console.log((okk ? "  ✓ " : "  ✗ ") + msg + (okk ? "" : ` → 实际 ${JSON.stringify(actual)}，期望 ${JSON.stringify(expected)}`));
  if (!okk) bad++;
}
function ok(cond, msg) {
  n++;
  console.log((cond ? "  ✓ " : "  ✗ ") + msg);
  if (!cond) bad++;
}

/** 剥掉注释，只留会被编译的代码：「不该出现 X」必须扫这个，否则会命中解释性 KDoc。 */
function code(s) {
  return s.replace(/\/\/.*$/gm, "").replace(/\/\*[\s\S]*?\*\//g, "");
}

const SRC = {
  sys: fs.readFileSync("app/src/main/java/me/bmax/apatch/dsh/DshSystemCtl.kt", "utf8"),
  cam: fs.readFileSync("app/src/main/java/me/bmax/apatch/dsh/DshCamera.kt", "utf8"),
  pd: fs.readFileSync("app/src/main/java/me/bmax/apatch/dsh/DshPersonalData.kt", "utf8"),
  ds: fs.readFileSync("app/src/main/java/me/bmax/apatch/dsh/DshDeviceSense.kt", "utf8"),
  bridge: fs.readFileSync("app/src/main/java/me/bmax/apatch/dsh/DshNativeBridge.kt", "utf8"),
  rt: fs.readFileSync("app/src/main/java/me/bmax/apatch/dsh/DshRuntime.kt", "utf8"),
};
const CODE = Object.fromEntries(Object.entries(SRC).map(([k, v]) => [k, code(v)]));

// ───────────────── 亮度换算 ─────────────────
console.log("── 亮度：百分比 ↔ 0..255 ──");
const BRIGHTNESS_MAX = 255;
// 复刻 brightnessSet：(percent.coerceIn(1,100) * MAX / 100).coerceIn(1, MAX)
const bright = (p) => Math.min(Math.max(Math.floor((Math.min(Math.max(p, 1), 100) * BRIGHTNESS_MAX) / 100), 1), BRIGHTNESS_MAX);
eq(bright(100), 255, "100% → 255");
eq(bright(50), 127, "50% → 127");
eq(bright(1), 2, "1% → 2");
eq(bright(0), 2, "0% 被钳到 1% → 2（不允许全黑）");
eq(bright(-50), 2, "负值同样钳到 1%");
eq(bright(999), 255, "超过 100% 钳到 255");
ok(SRC.sys.includes("coerceIn(1, 100)"), "源码里确实钳到 1..100 而不是 0..100");
ok(SRC.sys.includes("不允许 0：全黑屏幕"), "源码写明了为什么不允许 0");

// ───────────────── 音量换算 ─────────────────
console.log("\n── 音量：百分比 → 档位（四舍五入）──");
// 复刻：((percent.coerceIn(0,100) * max) + 50) / 100
const vol = (p, max) => Math.floor((Math.min(Math.max(p, 0), 100) * max + 50) / 100);
eq(vol(50, 15), 8, "媒体 max=15，50% → 8（截断会得 7）");
eq(vol(100, 15), 15, "100% → 满档");
eq(vol(0, 15), 0, "0% → 0（音量允许静音，亮度不允许）");
eq(vol(50, 5), 3, "通话 max=5，50% → 3");
eq(vol(33, 15), 5, "33% of 15 → 5");
eq(vol(1, 15), 0, "1% of 15 → 0（四舍五入的正确结果；音量允许静音，响应里会如实回报 after=0）");
eq(vol(4, 15), 1, "4% of 15 → 1（最小的非零档）");
ok(SRC.sys.includes("+ 50) / 100"), "源码用的是四舍五入而非截断");
// 反过来：响应里的 percent 是从实际档位算的
ok(SRC.sys.includes('.put("percent", after * 100 / max)'), "响应的 percent 用**实际**档位反算");

// ───────────────── 熄屏时间钳制 ─────────────────
console.log("\n── 熄屏时间 ──");
const TMIN = 15000, TMAX = 30 * 60 * 1000;
const timeout = (ms) => Math.min(Math.max(ms, TMIN), TMAX);
eq(timeout(1000), 15000, "1 秒 → 钳到 15 秒");
eq(timeout(60000), 60000, "1 分钟原样");
eq(timeout(99999999), 1800000, "超长 → 钳到 30 分钟");
ok(SRC.sys.includes("TIMEOUT_MIN_MS = 15_000"), "下限 15 秒");

// ───────────────── 相机尺寸选择 ─────────────────
console.log("\n── 相机尺寸 ──");
const pickSize = (sizes, maxDim) => {
  if (!sizes.length) return null;
  const area = (s) => s.w * s.h;
  const fits = sizes.filter((s) => Math.max(s.w, s.h) <= maxDim);
  return fits.length
    ? fits.reduce((a, b) => (area(b) > area(a) ? b : a))
    : sizes.reduce((a, b) => (area(b) < area(a) ? b : a));
};
const SIZES = [{ w: 4000, h: 3000 }, { w: 1920, h: 1080 }, { w: 1280, h: 720 }, { w: 640, h: 480 }];
eq(pickSize(SIZES, 1920), { w: 1920, h: 1080 }, "上限 1920 → 取合规里最大的");
eq(pickSize(SIZES, 4096), { w: 4000, h: 3000 }, "上限够大 → 取原生最大");
eq(pickSize(SIZES, 320), { w: 640, h: 480 }, "全都超限 → 退回最小（而不是失败）");
eq(pickSize([], 1920), null, "没有尺寸 → null");
ok(SRC.cam.includes("WARMUP_FRAMES = 5"), "丢 5 帧热身（AE/AWB 收敛）");
ok(SRC.cam.includes("setRepeatingRequest"), "用重复请求而不是单发（单发多为黑图）");
ok(SRC.cam.includes("JPEG_ORIENTATION"), "写入 EXIF 方向");
ok(SRC.cam.includes("maxImages") || SRC.cam.includes("ImageFormat.JPEG, 3"), "ImageReader 队列 > 1");
ok(/img\.close\(\)/.test(SRC.cam), "每帧都 close（否则队列满就再也不出图）");
ok(SRC.cam.includes("compareAndSet(false, true)"), "并发拍照互斥");
// 前台判断必须在拍照前后各一次
eq((SRC.cam.match(/isForeground\(ctx\)/g) || []).length, 2, "前台检查恰好两次（拍前 + 拍后）");

// ───────────────── 日历时间窗口 ─────────────────
console.log("\n── 日历 ──");
const days = (d) => Math.min(Math.max(d, 1), 366);
eq(days(0), 1, "0 天 → 1");
eq(days(7), 7, "默认 7 天");
eq(days(9999), 366, "超过一年 → 366");
ok(SRC.pd.includes("CalendarContract.Instances"), "用 Instances 展开重复事件（Events 只有规则）");
ok(SRC.pd.includes("CAL_ACCESS_CONTRIBUTOR"), "只往可写日历插入");
ok(SRC.pd.includes("IS_PRIMARY} DESC"), "优先主日历");
ok(SRC.pd.includes("EVENT_TIMEZONE"), "写入时区（缺了会被当 UTC）");
// end 默认 = start + minutes
const endOf = (start, minutes) => start + Math.min(Math.max(minutes, 1), 1440) * 60000;
eq(endOf(1000, 60), 1000 + 3600000, "默认 60 分钟");
eq(endOf(1000, 99999), 1000 + 1440 * 60000, "超过一天 → 钳到 1440 分钟");
ok(SRC.pd.includes("if (end <= start)"), "拒绝 end <= start");

// ───────────────── 通讯录 ─────────────────
console.log("\n── 通讯录 ──");
ok(SRC.pd.includes("CommonDataKinds.Phone.CONTENT_URI"), "查 Phone 表而不是 Contacts + N 次回查");
ok(SRC.pd.includes("NORMALIZED_NUMBER"), "号码搜索兼顾规范化形式（带分隔符时 LIKE 匹配不上）");
ok(!SRC.pd.includes("ContactsContract.CommonDataKinds.Photo"), "不返回头像");
ok(!/insert\([^)]*ContactsContract/.test(SRC.pd), "通讯录没有写入路径");
// 号码类型映射是稳定 id 而不是本地化标签
ok(SRC.pd.includes('-> "mobile"') && !SRC.pd.includes("R.string.dsh_native_contact_type"), "号码类型返回稳定 id 而非译文");

// ───────────────── 位置降级顺序 ─────────────────
console.log("\n── 位置 ──");
const order = ["bestCached", "isProviderEnabled", "requestFix"];
let last = -1;
let ordered = true;
for (const fnName of order) {
  const at = SRC.pd.indexOf(fnName, SRC.pd.indexOf("fun location("));
  if (at < last) ordered = false;
  last = at;
}
ok(ordered, "顺序是：先查缓存 → 再看定位是否开着 → 最后才主动定位");
ok(SRC.pd.includes("location_disabled"), "定位服务关掉时给出专门的 reason");
ok(SRC.pd.includes("removeUpdates"), "拿到点位后注销监听（不注销会一直耗电）");
ok(SRC.pd.includes('"precise"'), "响应里说明精度档位");
ok(SRC.pd.includes("Looper.getMainLooper()"), "监听注册到主线程 Looper（连接线程没有 Looper）");
ok(!SRC.pd.includes("ACCESS_BACKGROUND_LOCATION"), "不申请后台位置");
const maxAge = (v) => Math.min(Math.max(v, 0), 86400000);
eq(maxAge(-1), 0, "maxAge 负值 → 0");
eq(maxAge(300000), 300000, "默认 5 分钟");

// ───────────────── 传感器 ─────────────────
console.log("\n── 传感器 ──");
ok(SRC.ds.includes("unregisterListener"), "读完注销（常驻加速度计明显耗电）");
ok(SRC.ds.includes("needsBody = true"), "心率标为需要 BODY_SENSORS");
ok(SRC.ds.includes("needsActivity = true"), "计步标为需要 ACTIVITY_RECOGNITION");
ok(SRC.ds.includes('"needPermission"'), "列表里说明哪些项因缺权限被隐藏");
ok(SRC.bridge.includes("Cap.SENSORS -> true to \"\""), "传感器这项能力不因缺权限而不可用");
ok(SRC.ds.includes("AtomicBoolean"), "跨线程标志位是原子的");
// 每个传感器都有单位
const specs = [...SRC.ds.matchAll(/SensorSpec\("([a-z_]+)", Sensor\.TYPE_[A-Z_]+, "([^"]*)", (\d)/g)];
ok(specs.length >= 11, `解析到 ${specs.length} 个传感器定义`);
ok(specs.every((m) => m[2].length > 0), "每个传感器都标了单位");
ok(specs.every((m) => ["1", "3"].includes(m[3])), "值的个数只有 1 或 3");
const three = specs.filter((m) => m[3] === "3").map((m) => m[1]);
eq(three.sort(), ["accelerometer", "gravity", "gyroscope", "magnetometer"], "三轴的正好是这四个");

// ───────────────── 网络 ─────────────────
console.log("\n── 网络 ──");
ok(SRC.ds.includes("NET_CAPABILITY_VALIDATED"), "区分「连上了」和「真能上网」");
ok(SRC.ds.includes("estimatedDownKbps"), "带宽字段名里带 estimated");
ok(SRC.ds.includes("ssidHidden"), "没有位置权限时不给假的 SSID");
ok(SRC.ds.includes("NET_CAPABILITY_NOT_METERED"), "报告是否按流量计费");
ok(!CODE.ds.includes("getActiveNetworkInfo"), "代码里不用废弃的 getActiveNetworkInfo");
ok(SRC.ds.includes("TRANSPORT_VPN"), "VPN 单独一位（transport 报的是物理链路）");

// ───────────────── 电话 ─────────────────
console.log("\n── 电话 ──");
for (const forbidden of ["getImei", "getDeviceId", "getLine1Number", "getSimSerialNumber", "READ_SMS", "CALL_PHONE"]) {
  ok(!CODE.ds.includes(forbidden), `代码里不碰 ${forbidden}`);
}
ok(SRC.ds.includes("dataNetworkType"), "用 getDataNetworkType 而不是废弃的 getNetworkType");
ok(SRC.ds.includes("hasTelephony") || SRC.bridge.includes("hasTelephony"), "平板/模拟器上判为不可用");

// ───────────────── 特殊权限分流 ─────────────────
console.log("\n── 特殊权限 ──");
ok(SRC.bridge.includes("ACTION_MANAGE_WRITE_SETTINGS"), "改系统设置有专门的系统页 Action");
ok(SRC.bridge.includes("ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS"), "勿扰访问有专门的系统页 Action");
ok(SRC.bridge.includes("ACTION_MANAGE_UNKNOWN_APP_SOURCES"), "安装未知应用有专门的系统页 Action");
// 勿扰那个页面不接受包名
const dndLine = SRC.bridge.slice(SRC.bridge.indexOf("NOTIFICATION_POLICY("), SRC.bridge.indexOf("NOTIFICATION_POLICY(") + 200);
ok(dndLine.includes("false"), "勿扰页面的 perAppUri 是 false（它不接受 package: uri）");
ok(SRC.sys.includes("canWrite"), "用 Settings.System.canWrite 而不是 checkSelfPermission");
// 这个查询在 PermissionUtils 里（桥只调它），别在 DshSystemCtl 里找
ok(fs.readFileSync("app/src/main/java/me/bmax/apatch/util/PermissionUtils.kt", "utf8")
  .includes("isNotificationPolicyAccessGranted"), "勿扰用 isNotificationPolicyAccessGranted 判断");

// ───────────────── 系统设置写入的诚实性 ─────────────────
console.log("\n── 写操作报告前后值 ──");
for (const fn of ["brightnessSet", "timeoutSet", "rotationSet", "volumeSet", "ringerSet"]) {
  const at = SRC.sys.indexOf("fun " + fn + "(");
  const body = SRC.sys.slice(at, SRC.sys.indexOf("\n    }", at));
  ok(body.includes('"before"') && (body.includes('"after"') || body.includes("settingsGet")),
    `${fn} 返回改动前后的值`);
}
ok(SRC.sys.includes('"autoBrightness", modeNow'), "改亮度时报告自动亮度是否还开着（否则写入会被覆盖）");
ok(SRC.sys.includes("dndActive(ctx) && !PermissionUtils.hasNotificationPolicyAccess"),
  "勿扰开着且没授权时拒绝改音量（否则静默无效）");

// ───────────────── 预装插件的「上游已内置」不能变成死循环 ─────────────────
//
// 1.9.0 真机升级到 dsh 0.1.5 后的现象（用户日志原文）：先「检测到重复的插件入口 id
// file-upload（上游已内置），卸载预装插件 dsh-file-upload 后重试启动」，下一次冷启动
// 又「补装预装插件：容器里缺 dsh-file-upload，重新安装」，如此往复。
//
// 根因是**两类事实混在一个账本里**：SEED_ENTRY_IDS 判定「上游已内置」后把包名写进
// attempted，而 applySeedEnvRetry 把 attempted 里「不在 bundles 里」的一律当成「记过账
// 却没生效」，于是摘账重装。这里把两侧的决策都复刻一遍，断言闭环不成立。
console.log("\n── 预装账本：上游已内置 ≠ 试过 ──");
const SEED_PLUGINS = ["dsh-web-mobile", "dshmarket", "dsh-config-manager", "dsh-file-upload"];
const SEED_ENTRY_IDS = { "dsh-file-upload": "file-upload" };
const SEED_MAX_PASSES = 3;

/**
 * 复刻 seedPlugins → 启动 → repairDuplicateLoaderEntry 的完整序列。
 *
 * 状态：bundles = profile 里真的装着的包（= 老逻辑里的 installed）、attempted = 账本、
 * shadowed = 落盘的「上游已内置」记录（带运行时版本）。
 * builtinFrom = 从第几次启动开始，上游核心包声明 file-upload（99 = 一直不声明）。
 * fixed = false 复刻 1.9.0 的老逻辑（把「上游已内置」写进账本），true 是修好后的。
 */
function simulate({ preseeded, builtinFrom, fixed, starts }) {
  let attempted = new Set(preseeded ? SEED_PLUGINS : []);
  let bundles = new Set(preseeded ? SEED_PLUGINS : []);
  let storedShadowed = new Set();
  let storedShadowedRuntime = null;
  let pnpmInstalls = 0;
  let dupFailures = 0;
  for (let i = 1; i <= starts; i++) {
    const runtime = i >= builtinFrom ? "0.1.5-r4" : "0.1.4-r4";
    const entries = new Map([["@deepseek-ai/dsh-web-app", runtime === "0.1.5-r4" ? ["file-upload"] : []]]);
    for (const pkg of bundles) entries.set(pkg, SEED_ENTRY_IDS[pkg] ? [SEED_ENTRY_IDS[pkg]] : []);

    const mappedShadowed = new Set(Object.entries(SEED_ENTRY_IDS)
      .filter(([pkg, id]) => [...entries].some(([owner, ids]) => owner !== pkg && ids.includes(id)))
      .map(([pkg]) => pkg));
    const recordedShadowed = storedShadowedRuntime === runtime ? storedShadowed : new Set();
    const shadowed = fixed ? new Set([...mappedShadowed, ...recordedShadowed]) : new Set();
    if (!fixed) for (const pkg of mappedShadowed) attempted.add(pkg); // 老逻辑就错在这一行

    for (const pkg of [...shadowed]) if (bundles.has(pkg)) { bundles.delete(pkg); attempted.delete(pkg); }

    const repairRetry = [...attempted].filter((pkg) => SEED_PLUGINS.includes(pkg) && !bundles.has(pkg) && !shadowed.has(pkg));
    for (const pkg of repairRetry) attempted.delete(pkg);
    const envRetry = [...attempted].filter((pkg) => SEED_PLUGINS.includes(pkg) && !bundles.has(pkg) && !shadowed.has(pkg));
    for (const pkg of envRetry) attempted.delete(pkg);

    const todo = SEED_PLUGINS.filter((pkg) => !attempted.has(pkg) && !shadowed.has(pkg));
    for (const pkg of todo.filter((pkg) => !bundles.has(pkg))) { bundles.add(pkg); attempted.add(pkg); pnpmInstalls++; }

    const conflict = [...bundles].some((pkg) => {
      const id = SEED_ENTRY_IDS[pkg];
      return id !== undefined && [...entries].some(([owner, ids]) => owner !== pkg && ids.includes(id));
    });
    if (conflict) {
      dupFailures++;
      for (const pkg of [...bundles]) if (SEED_ENTRY_IDS[pkg]) bundles.delete(pkg);
      if (fixed) { storedShadowed = new Set([...storedShadowed, "dsh-file-upload"]); storedShadowedRuntime = runtime; }
    }
  }
  return { attempted, bundles, pnpmInstalls, dupFailures };
}

// 用户的真实序列：四个预装包都在老运行时（0.1.2/r3）时装好了，然后升级到 0.1.5-r4
const buggy = simulate({ preseeded: true, builtinFrom: 2, fixed: false, starts: 5 });
ok(buggy.pnpmInstalls >= 3, `老逻辑在升级后反复重装（4 次冷启动装了 ${buggy.pnpmInstalls} 次，重复 id 失败 ${buggy.dupFailures} 次）`);
const healed = simulate({ preseeded: true, builtinFrom: 2, fixed: true, starts: 5 });
eq(healed.pnpmInstalls, 0, "修好后：升级运行时之后一次都不再重装");
eq(healed.dupFailures, 0, "修好后：启动前就把冲突包摘掉，不再有 duplicate entry id 那一轮失败");
ok(!healed.bundles.has("dsh-file-upload"), "修好后：冲突的预装包不在 bundles 里");
const sameRuntime = simulate({ preseeded: true, builtinFrom: 99, fixed: true, starts: 3 });
eq(sameRuntime.pnpmInstalls, 0, "运行时没变、上游也没内置：照样不重装（不能白跑 pnpm）");
const fresh = simulate({ preseeded: false, builtinFrom: 1, fixed: true, starts: 2 });
eq(fresh.pnpmInstalls, 3, "全新安装在新运行时上：只装另外三个，file-upload 交给上游");
const freshOld = simulate({ preseeded: false, builtinFrom: 99, fixed: true, starts: 2 });
eq(freshOld.pnpmInstalls, 4, "全新安装在没有内置的运行时上：四个都装");

// 结构断言：这类「决策分散在多处」的 bug 靠模拟只能盖住已想到的组合
const seedBody = SRC.rt.slice(SRC.rt.indexOf("private suspend fun seedPlugins"), SRC.rt.indexOf("private fun applySeedRepair"));
ok(!/attempted\.addAll\(\s*shadowed|attempted\.addAll\(\s*mappedShadowed/.test(seedBody),
  "【上游已内置】不能写进 attempted（写进去就会被补修逻辑当成失败项重装）");
ok(seedBody.includes("attempted.removeAll(shadowed.toSet())"), "反而要把历史遗留的记账摘掉（换回旧运行时才会重新预装）");
ok(/applySeedRepair\(p, attempted, installed, shadowed\)/.test(seedBody), "补修要认 shadowed");
ok(/applySeedEnvRetry\(p, attempted, installed, shadowed\)/.test(seedBody), "换运行时重试要认 shadowed");
for (const fn of ["applySeedRepair", "applySeedEnvRetry"]) {
  const at = SRC.rt.indexOf("private fun " + fn + "(");
  const body = SRC.rt.slice(at, SRC.rt.indexOf("\n    }", at));
  ok(body.includes("it !in shadowed"), `${fn} 的待重试集合排除 shadowed`);
}
const dupRepair = SRC.rt.slice(SRC.rt.indexOf("private suspend fun repairDuplicateLoaderEntry"), SRC.rt.indexOf("private suspend fun repairDuplicateLoaderEntry") + 2000);
ok(dupRepair.includes("rememberShadowed(culprits)"),
  "启动失败后的兜底修复要把卸掉的包落盘（映射表没覆盖的冲突靠它避免下轮重装）");
ok(/\.putString\(DshEnv\.KEY_SEED_SHADOWED_RUNTIME, runtime\)/.test(SRC.rt), "shadowed 记录跟随运行时版本（换回旧运行时即作废）");

// ───────────────── profile 声明残留：拼接出来的 JS 必须真能跑 ─────────────────
//
// 1.9.0 升级运行时后连着踩了两个坑：先「duplicate loader entry id」反复卸载重装，
// 再「cannot resolve profile bundle」（bundles 里留着已删掉的包，dsh 直接拒绝启动）。
// 后者的自愈要在 App 侧改 profile 的 package.json，脚本是 Kotlin 字符串拼出来的 ——
// 而**拼接本身也会错**：行注释和后面的语句粘在同一行时，`//` 会把整段逻辑注释掉，
// 脚本静默无输出、看起来「什么都没发生」。所以这里把脚本抠出来，在临时目录里真跑。
console.log("\n── profile 声明自愈：拼接的 JS 真跑一遍 ──");
const os = require("os");
const path = require("path");
const cp = require("child_process");
const repo = fs.readFileSync("app/src/main/java/me/bmax/apatch/dsh/DshPluginRepo.kt", "utf8");
/** 把 Kotlin 源码里一段「A + B + C」的字符串字面量按 Kotlin 规则反转义后拼回原文。 */
function kotlinJs(from, to) {
  const seg = repo.slice(repo.indexOf(from), repo.indexOf(to));
  let out = "";
  for (let i = 0; i < seg.length; i++) {
    if (seg[i] !== '"') continue;
    let j = i + 1;
    while (j < seg.length && seg[j] !== '"') {
      if (seg[j] === "\\") {
        const c = seg[j + 1];
        out += c === "n" ? "\n" : c === '"' ? '"' : c === "$" ? "$" : c;
        j += 2;
      } else { out += seg[j]; j++; }
    }
    i = j;
  }
  return out;
}
const pruneJs = kotlinJs("suspend fun pruneUnresolvableBundles", "val args =");
ok(pruneJs.length > 500, `脚本还原成功（${pruneJs.length} 字节）`);
ok(!/\/\/[^\n]*console\.log/.test(pruneJs), "没有语句被行注释吞掉（拼接时注释后面必须有真换行）");
ok(!/require\.resolve\([^)]*package\.json/.test(pruneJs),
  "判据用 resolve.paths + existsSync，而不是 require.resolve(pkg+'/package.json')（后者要求包导出 package.json，会误摘健康包）");

const dir = fs.mkdtempSync(path.join(os.tmpdir(), "dsh-profile-"));
try {
  fs.mkdirSync(path.join(dir, "node_modules"), { recursive: true });
  for (const p of ["dshmarket", "dsh-config-manager"]) {
    fs.mkdirSync(path.join(dir, "node_modules", p), { recursive: true });
    fs.writeFileSync(path.join(dir, "node_modules", p, "package.json"), JSON.stringify({ name: p, version: "1.0.0" }));
  }
  const manifest = {
    name: "web",
    dsh: { profile: { bundles: ["dsh-file-upload", "dshmarket", "dsh-config-manager"] } },
    dependencies: { "dsh-file-upload": "^0.4.3", dshmarket: "^1.0.0", "dsh-config-manager": "^0.1.0" },
  };
  const write = () => fs.writeFileSync(path.join(dir, "package.json"), JSON.stringify(manifest, void 0, 2) + "\n");
  write();
  const anchor = path.join(dir, "install", "package.json");
  const run = () => cp.execFileSync(process.execPath, ["-e", pruneJs, anchor, dir, "dsh-file-upload", "dshmarket", "dsh-config-manager"], { encoding: "utf8" }).trim();
  const out = run();
  eq(out, "dsh-file-upload", "只摘掉解析不到的那个，健康包不动");
  const after = JSON.parse(fs.readFileSync(path.join(dir, "package.json"), "utf8"));
  eq(after.dsh.profile.bundles, ["dshmarket", "dsh-config-manager"], "bundles 摘掉坏声明（留着它 dsh 就起不来）");
  eq(Object.keys(after.dependencies), ["dshmarket", "dsh-config-manager"], "dependencies 也摘（否则下次 pnpm install 会装回来）");
  eq(run(), "", "幂等：再跑一次无事发生");
  ok(fs.readFileSync(path.join(dir, "package.json"), "utf8").endsWith("}\n"), "写回格式与 dsh 的 writeProfileManifest 一致（2 空格 + 末尾换行）");
} finally {
  fs.rmSync(dir, { recursive: true, force: true });
}

// 结构断言：这条自愈必须同时挂在「启动前」与「启动失败后」两条路径上
const seedForPrune = SRC.rt.slice(SRC.rt.indexOf("private suspend fun seedPlugins"), SRC.rt.indexOf("private fun applySeedRepair"));
ok(seedForPrune.includes("pruneUnresolvableBundles(managedSeedPackages())"),
  "启动前先清理声明残留（不让用户先看一轮「服务进程已退出」）");
ok(/private fun managedSeedPackages\(\)[\s\S]{0,120}SEED_PLUGINS \+ RETIRED_SEED_PLUGINS\.keys/.test(SRC.rt),
  "清理范围 = 在装的 + 退役的（退役包声明残留同样会让 dsh 拒绝启动）");
const startBody = SRC.rt.slice(SRC.rt.indexOf("private suspend fun startAndAwait"), SRC.rt.indexOf("private suspend fun repairUnresolvableBundles"));
ok(startBody.includes("repairUnresolvableBundles()"), "启动失败后也会尝试清理并重试一次");
ok(/if \(repairDuplicateLoaderEntry\(\)\) repaired = true/.test(startBody) && /if \(repairUnresolvableBundles\(\)\) repaired = true/.test(startBody),
  "两种自愈各查各的：同时存在时一轮修完");
ok(SRC.rt.includes('Regex("cannot resolve profile bundle'),
  "失败判据取 dsh 自己的那句 cannot resolve profile bundle");
ok(SRC.rt.includes("dsh_log_bundle_unresolvable_user"),
  "不是预装清单里的包就只提示、不擅自改用户的东西");

// ── WebUI 认证 token：解析与就绪时机 ───────────────────────────────────────────
//
// 「外部浏览器打开的链接没有 token 参数」查下来是两个叠在一起的问题，两个都不会
// 报错、只会表现成认证墙，所以在这里钉死：
//
//   1. token 是 base64url（上游 processLaunchToken = encodeBase64Url(randomBytes(32))），
//      原来的字符类少了 `_` —— 用真 token 量过：46.8% 被截断、1.6% 整条匹配不上。
//   2. web 服务器「激活即监听」，带 token 的 URL 却在插件树加载完才打印；
//      在那之前宣布「已就绪」并把地址交出去，拿到的就是不带 token 的裸地址。
{
  const m = SRC.rt.match(/DSH_WEB_TOKEN_RE = Regex\("([^"]+)"\)/);
  ok(m !== null, "能抠出 App 里的 token 正则");
  if (m) {
    const re = new RegExp(m[1]);
    // 上游 processLaunchToken 的实际字符集：base64url 字母表
    const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    const token = (seed) => {
      // 确定性抽样：覆盖「含 _」「以 _ 开头」「含 -」三种形状
      let s = "";
      for (let i = 0; i < 43; i++) s += alphabet[(seed * 7 + i * 13) % 64];
      return s;
    };
    let sample = token(3);
    if (!sample.includes("_")) sample = "_" + sample.slice(1);
    const line = (t) => `dsh web: http://127.0.0.1:3080/?token=${t} (LAN: http://192.168.1.5:3080/?token=${t})`;

    eq(re.exec(line(sample))?.[1], sample, `含 \`_\` 的 token 完整取出（${sample}）`);
    const leading = "_" + token(5).slice(1);
    eq(re.exec(line(leading))?.[1], leading, "首字符就是 `_` 的 token 也能取出（原来的字符类会整条匹配不上）");
    const dashed = token(9).replace(/[A-Za-z0-9]/g, "-");
    eq(re.exec(line(dashed))?.[1], dashed, "全 `-` 的 token 也完整");
    eq(re.exec(line("abc") + "junk")?.[1], "abc", "不把后面的内容吞进 token");
    eq(re.exec("dsh web: http://127.0.0.1:3080/")?.[1], undefined, "没有 token 的行不误匹配");

    // 老正则必须在新用例上失败 —— 否则这条检查等于没测到真正的原因
    const old = new RegExp("\\?token=([A-Za-z0-9+/=-]+)");
    ok(old.exec(line(sample))?.[1] !== sample, "老字符类（缺 `_`）在这些 token 上确实会截断");
    ok(old.exec(line(leading)) === null, "老字符类在 `_` 开头的 token 上确实整条匹配不上");
  }

  // 时机：宣布就绪之前必须先等 token
  const readyBody = SRC.rt.slice(
    SRC.rt.indexOf("private suspend fun awaitReady"),
    SRC.rt.indexOf("private suspend fun awaitWebToken"),
  );
  ok(readyBody.includes("awaitWebToken()"), "就绪判定里会等 token");
  ok(
    readyBody.indexOf("awaitWebToken()") < readyBody.indexOf("phase = DshPhase.RUNNING"),
    "等 token 在 `phase = RUNNING` 之前（先后写反等于没等）",
  );
  const waitBody = SRC.rt.slice(
    SRC.rt.indexOf("private suspend fun awaitWebToken"),
    SRC.rt.indexOf("private fun httpResponds"),
  );
  ok(/TOKEN_WAIT_MS/.test(waitBody) && /private const val TOKEN_WAIT_MS = \d+_?\d*L/.test(SRC.rt),
    "等待有上限：拿不到 token 也不能把启动卡死");
  ok(waitBody.includes("serverProcess?.isAlive != false"), "进程死了就不等了（超时日志会盖掉真正的失败原因）");
  ok(/logInfo\(R\.string\.dsh_log_token_captured, token\.length\)/.test(waitBody),
    "日志只记 token 长度，不记 token 本身");
}


// ───────────────── 特权：严格程度 × 风险等级 ─────────────────
//
// 「三行 when」的判定最容易被当成不用测的东西，而它错了就是**每次特权调用都静默放行**。
// 这里复刻 PrivPolicy.needsConfirm 并逐格对拍，同时检查它没有被写成两处。
console.log("── 限制模式与两张清单 ──");
{
  const privSrc = fs.readFileSync("app/src/main/java/me/bmax/apatch/dsh/PrivPolicy.kt", "utf8");
  const privCode = code(privSrc);
  const shellSrc = fs.readFileSync("app/src/main/java/me/bmax/apatch/dsh/PrivilegedShell.kt", "utf8");
  // 复刻体：判定只有两个来源。这一行比旧的三档 when 短得多，但错了就是"每次调用都静默放行"
  // 或者"永远弹窗"，所以照旧逐格对拍。
  const needs = (restrictMode, capRestricted, danger) => danger || (restrictMode && capRestricted);
  eq([
    needs(false, false, false), needs(false, true, false), needs(true, false, false),
    needs(true, true, false), needs(false, false, true), needs(true, false, true),
  ], [false, false, false, true, true, true],
    "判定只有两个来源：危险命中永远问；限制模式开着**且**能力在清单里才问");
  ok(/fun needsConfirm\(\s*\n?\s*restrictMode: Boolean,\s*\n?\s*capRestricted: Boolean,\s*\n?\s*danger: Boolean,?\s*\n?\s*\): Boolean = danger \|\| \(restrictMode && capRestricted\)/.test(privCode),
    "Kotlin 侧就是这一行（参数名逐个写出来：靠位置传参的判定最容易被改错方向）");

  // 默认值：限制模式关（新装与老用户一致）；能力清单默认五项
  ok(/getBoolean\(DshEnv\.KEY_PRIV_RESTRICT_MODE, false\)/.test(privCode),
    "限制模式默认关（钉在读的那一处：迁移里也写了同一个键，钉错地方会让「默认改开」从门禁底下溜过去）");
  for (const cap of ["SHELL", "DISPLAY", "CAMERA", "MIC", "SMS"]) {
    ok(new RegExp("DshNativeBridge\\.Cap\\." + cap + "\\.id").test(privSrc),
      "能力清单默认含 " + cap + "（旧模型里 CAMERA/MIC/SMS 启用后再没被问过，放进来才是新增的闸）");
  }
  ok(/getStringSet\(DshEnv\.KEY_PRIV_RESTRICT_CAPS, null\)\?\.toSet\(\) \?: DEFAULT_RESTRICTED/.test(privCode),
    "清单读出来要拷一份，且键不存在时给默认值（给空集等于默认内容白写）");
  ok(/fun resetRestrictedCaps/.test(privCode) && /remove\(DshEnv\.KEY_PRIV_RESTRICT_CAPS\)/.test(privCode),
    "能力清单可恢复默认（删键即回默认值）");

  // 危险清单：内置来源唯一、增删分开记、可恢复
  ok(/val BUILTIN_DANGER: List<String> = PrivilegedShell\.builtinDangerEntries\(\)/.test(privCode),
    "内置危险条目只从 PrivilegedShell 算出来（另抄一份迟早不一致）");
  ok(/fun builtinDangerEntries\(\): List<String> =\s*\n?\s*\(DANGEROUS_CMDS \+ DANGEROUS_SUB\.flatMap/.test(code(shellSrc)),
    "内置表就是那两张表（DANGEROUS_CMDS + DANGEROUS_SUB）");
  ok(/fun addedDanger\(ctx: Context\): List<String> =[\s\S]{0,200}?DshEnv\.KEY_PRIV_DANGER_ADDED/.test(privCode) &&
    /fun disabledBuiltinDanger\(ctx: Context\): Set<String> =[\s\S]{0,120}?DshEnv\.KEY_PRIV_DANGER_DISABLED/.test(privCode),
    "增补与「被删的内置」各读各的键（写反了会让恢复默认去删错集合）");
  ok(/fun resetDangerList/.test(privCode), "危险清单可恢复默认");
  ok(/private fun normalizeDangerEntry/.test(privCode) && /\^\[A-Za-z0-9_\.\/-\]\+\$/.test(privCode),
    "自定义条目只收普通字符（一个写错的正则会让某条命令静默免问，比不支持正则危险得多）");
  ok(/fun dangerHit\(ctx: Context, command: String\): Boolean/.test(privCode) &&
    /substringAfterLast\(/.test(privCode),
    "命中判据取命令名 basename（与内置表的判据同形）");
  ok(/if \(dangerHit\(ctx, command\)\) return PrivRisk\.DANGEROUS/.test(privCode) &&
    /\n        return PrivRisk\.WRITE/.test(privCode),
    "删掉内置条目后降级成 WRITE（「可增删」的语义靠这一步落地）");

  // 迁移：一律关 + 只对老用户提示一次 + 旧键读完即删
  ok(/fun migrateOnce\(ctx: Context\)/.test(privCode) &&
    /p\.contains\(DshEnv\.KEY_PRIV_STRICTNESS\) \|\| p\.contains\(DshEnv\.KEY_PRIV_TRUSTED_CAPS\)/.test(privCode),
    "迁移只认「有没有旧键」这一个事实（不用旧值决定行为）");
  ok(/putBoolean\(DshEnv\.KEY_PRIV_RESTRICT_MODE, false\)/.test(privCode) &&
    /remove\(DshEnv\.KEY_PRIV_STRICTNESS\)/.test(privCode) &&
    /remove\(DshEnv\.KEY_PRIV_TRUSTED_CAPS\)/.test(privCode),
    "迁移一律关，且旧键读完即删（留着就会有人读它，而那套语义已经不存在）");
  ok(/putBoolean\(DshEnv\.KEY_PRIV_POLICY_NOTICE, hadOld\)/.test(privCode) &&
    /fun policyNoticePending/.test(privCode) && /fun consumePolicyNotice/.test(privCode),
    "只有老用户收到一次性策略说明，弹过即清");
  ok(!/PrivStrictness/.test(privCode) && !/isTrusted|setTrusted/.test(privCode),
    "PrivPolicy 里没有严格程度/信任名单残留（留着就是两套策略并存）");
}
// ───────────────── 特权：只读命令判定必须与容器内脚本一致 ─────────────────
//
// 宿主（Kotlin）与容器内 adb-shell.py 各有一份只读白名单。两边漂移的后果是「同一条命令
// 走宿主不需要确认、走脚本要确认」——用户看到的行为取决于代码路径，这最难查。
console.log("\n── 只读白名单：Kotlin ↔ adb-shell.py ──");
{
  const shell = fs.readFileSync("app/src/main/java/me/bmax/apatch/dsh/PrivilegedShell.kt", "utf8");
  const py = fs.readFileSync("app/src/main/assets/adb-shell.py", "utf8");

  const kotlinCmds = (() => {
    const at = shell.indexOf("private val READONLY_CMDS = setOf(");
    const body = shell.slice(at, shell.indexOf(")", shell.indexOf('"echo",')));
    return [...body.matchAll(/"([a-z0-9]+)"/g)].map((m) => m[1]).sort();
  })();
  const pyCmds = (() => {
    const at = py.indexOf("READONLY_CMDS = frozenset((");
    const body = py.slice(at, py.indexOf("))", at));
    return [...body.matchAll(/'([a-z0-9]+)'/g)].map((m) => m[1]).sort();
  })();
  ok(kotlinCmds.length > 20 && pyCmds.length > 20, `两边都解析到了白名单（${kotlinCmds.length} / ${pyCmds.length}）`);
  eq(kotlinCmds.join(","), pyCmds.join(","), "只读命令白名单逐字一致");

  const kotlinSub = (() => {
    const at = shell.indexOf("private val READONLY_SUB = mapOf(");
    const body = shell.slice(at, shell.indexOf("private val DANGEROUS_CMDS"));
    return [...body.matchAll(/"([a-z]+)" to setOf\(([^)]*)\)/g)]
      .map((m) => m[1] + ":" + [...m[2].matchAll(/"([a-z]+)"/g)].map((x) => x[1]).sort().join("|"))
      .sort();
  })();
  const pySub = (() => {
    const at = py.indexOf("READONLY_SUB = {");
    const body = py.slice(at, py.indexOf("}", at));
    return [...body.matchAll(/'([a-z]+)': frozenset\(\(([^)]*)\)\)/g)]
      .map((m) => m[1] + ":" + [...m[2].matchAll(/'([a-z]+)'/g)].map((x) => x[1]).sort().join("|"))
      .sort();
  })();
  eq(kotlinSub.join(","), pySub.join(","), "只读子命令白名单逐字一致（空集也算一项）");

  // 判据本身：元字符与 find -delete 这两个坑必须两边都堵上
  const isReadonly = (cmd) => {
    const t = cmd.trim();
    if (!t) return false;
    for (const m of [">", "<", "|", ";", "&", "$(", "`", "\n", "\r"]) if (t.includes(m)) return false;
    const parts = t.split(/\s+/);
    const name = parts[0].split("/").pop();
    if (name === "find") return !parts.slice(1).some((a) => ["-delete", "-exec", "-fprint", "-fls"].some((f) => a.startsWith(f)));
    const sub = pySub.find((x) => x.startsWith(name + ":"));
    if (sub) return parts.length > 1 && sub.slice(name.length + 1).split("|").includes(parts[1]);
    return pyCmds.includes(name);
  };
  const cases = [
    ["getprop ro.build.version.sdk", true],
    ["dumpsys window", true],
    ["ls -la /sdcard", true],
    ["echo hi > /sdcard/f", false],
    ["ls; rm -rf /sdcard", false],
    ["cat /data/x | grep y", false],
    ["find /sdcard -name x", true],
    ["find /sdcard -name x -delete", false],
    ["pm list packages", true],
    ["pm uninstall com.x", false],
    ["settings get global x", true],
    ["settings put global x 1", false],
    ["input tap 1 2", false],
    ["am start -n a/b", false],
    ["", false],
  ];
  eq(cases.map((c) => isReadonly(c[0])), cases.map((c) => c[1]), "只读判定在 15 个用例上与期望一致（元字符与 find 两个坑都堵住）");
  ok(/for \(m in META_CHARS\) if \(s\.contains\(m\)\) return false/.test(shell), "Kotlin 侧确实先查元字符");
  ok(/a\.startsWith\("-delete"\)/.test(shell), "Kotlin 侧也有 find -delete 的特判");
}

// ───────────────── 特权：风险分级 ─────────────────
console.log("\n── 特权风险分级 ──");
{
  const shell = fs.readFileSync("app/src/main/java/me/bmax/apatch/dsh/PrivilegedShell.kt", "utf8");
  const dangerous = (() => {
    const at = shell.indexOf("private val DANGEROUS_CMDS = setOf(");
    return [...shell.slice(at, shell.indexOf(")", at)).matchAll(/"([a-z]+)"/g)].map((m) => m[1]);
  })();
  for (const cmd of ["rm", "dd", "mkfs", "reboot"]) ok(dangerous.includes(cmd), `${cmd} 是危险命令`);
  const subAt = shell.indexOf("private val DANGEROUS_SUB = mapOf(");
  const subBody = shell.slice(subAt, shell.indexOf("private val META_CHARS"));
  for (const pair of [["pm", "uninstall"], ["settings", "put"], ["svc", "power"], ["am", "force-stop"]]) {
    ok(new RegExp(`"${pair[0]}" to setOf\\([^)]*"${pair[1]}"`).test(subBody), `${pair[0]} ${pair[1]} 是危险子命令`);
  }
  // 只取这一张表本身：往后再切会把 isReadonly 那些引用只读表的代码也算进来
  const dangerSlice = code(
    shell.slice(
      shell.indexOf("private val DANGEROUS_CMDS"),
      shell.indexOf("private val DANGEROUS_SUB")
    )
  );
  const readonlyOnly = ["getprop", "dumpsys", "cat", "ls", "ps", "logcat"];
  ok(readonlyOnly.every((c) => !dangerSlice.includes('"' + c + '"')),
    "危险表没有混进只读命令（两张表各管一件事）");
}

// ───────────────── 特权：通道约束与审计 ─────────────────
console.log("\n── 特权通道约束 ──");
{
  const shell = fs.readFileSync("app/src/main/java/me/bmax/apatch/dsh/PrivilegedShell.kt", "utf8");
  ok(/ABD_WRITE_DISABLED|adb_write_disabled/.test(shell), "ADB 通道的写开关会被拦下");
  ok(/adb_root_disabled/.test(shell), "ADB 通道的 root 开关会被拦下（reason 能指路到设置页）");
  ok(/runtime_missing/.test(shell), "ADB 通道要容器内脚本，rootfs 不在时会说清楚");
  ok(/DSH_INTERNAL/.test(code(shell)) === false,
    "宿主调用**不带** DSH_INTERNAL=1：agent 的调用必须过脚本自己的写关卡");
  ok(/tryEnter\(\)/.test(shell) && /compareAndSet\(false, true\)/.test(shell), "特权命令单飞");

  // 用户把通道设成 root 但还没验证过时，必须**照旧告知 agent**（只是 ready=false）。
  // 这一条踩过坑：以前只按「解析出的通道」判断，于是提示词整段消失，agent 以为这台
  // 设备没有提权途径，连试都不试 —— 而用户明明刚开过。
  // 只切「没有可用通道」这一段的尾巴，避免常量出现在别处就让断言通过
  const reachTail = shell.slice(shell.indexOf("PermissionManager.Channel.NONE -> Unit"));
  ok(/Channel\.ROOT -> if \(status\.suPresent\) \{[\s\S]{0,220}reason = REASON_ROOT_UNVERIFIED/.test(reachTail),
    "没解析出通道时，选了 root 且 su 在就必须回一个未就绪的 Reach（否则提示词整段消失）");
  ok(/val usable: Boolean get\(\) = ready \|\| reason == REASON_ROOT_UNVERIFIED/.test(shell),
    "root 未验证仍然可试（调用时才会弹 su 授权框），Shizuku/ADB 未就绪则提前拦");
  ok(/if \(!reach\.usable\) return reach\.reason/.test(shell),
    "denyReason 给出精确原因，而不是一律 no_channel");
  ok(/fun readonlyCommands\(\)/.test(shell) && /READONLY_CMDS\.sorted\(\)/.test(shell),
    "只读命令清单对外可见（与 isReadonly 同一份数据，不会两处漂移）");

  // root 的「已验证」以前只有点「刷新权限」才会写，于是老用户升级后必须先手动点一次、
  // 再被系统弹一次 su 授权框 —— 而他几个月前就授权过了。现在应用自己验一次。
  const perm = fs.readFileSync("app/src/main/java/me/bmax/apatch/dsh/PermissionManager.kt", "utf8");
  const autoVerify = perm.slice(perm.indexOf("fun autoVerifyRoot"), perm.indexOf("private fun verifyRoot"));
  ok(autoVerify.length > 0, "存在 autoVerifyRoot");
  ok(/if \(preferred != null && preferred != Channel\.ROOT\) return/.test(autoVerify),
    "只在选了 root 或「自动」时才自动验证（明确选 Shizuku/ADB 的人不该被弹 su 框）");
  ok(/if \(!detectSu\(\)\) return/.test(autoVerify) && /KEY_ROOT_VERIFIED, false\)\) return/.test(autoVerify),
    "没有 su、或已经验过就不重复验");
  ok(/KEY_ROOT_DENIED_AT/.test(autoVerify) && /ROOT_RETRY_MS/.test(autoVerify),
    "被拒过就一天内不再自动试（否则每次开 App 都弹一次）");
  ok(/@Volatile private var rootAutoTried = false/.test(perm) && /if \(rootAutoTried\) return/.test(autoVerify),
    "每个进程最多自动验证一次（调用方在 LaunchedEffect 里会反复进来）");
  ok(/putLong\(KEY_ROOT_DENIED_AT, if \(ok\) 0L else System\.currentTimeMillis\(\)\)/.test(autoVerify),
    "验证成功后清掉拒绝时间戳，失败才记时间");

  // 两个首页入口都要「先验证、再探测、后写事实」，顺序反了事实就是旧的
  for (const f of ["DshHomeShared.kt", "HomeDsh.kt"]) {
    const src = fs.readFileSync("app/src/main/java/me/bmax/apatch/ui/screen/" + f, "utf8");
    ok(/autoVerifyRoot[\s\S]{0,400}PermissionManager\.refresh[\s\S]{0,200}DshHostPrompt\.writeFacts/.test(src),
      "首页入口 " + f + "：先验证 root、再探测、再写事实（顺序即正确性）");
  }

  // 提示词要按原因给不同指引：把「早就有 root 的用户」支使去设置页是错的
  const prompt2 = fs.readFileSync("app/src/main/assets/dsh-folk-host.mjs", "utf8");
  ok(/reason === .root_unverified./.test(prompt2) && /the app verifies it by itself/.test(prompt2),
    "root 未验证时告诉 agent「应用会自己验、直接调用」而不是让它去指使");
  ok(/shizuku_unauthorized[\s\S]{0,200}grant this app permission in Shizuku/.test(prompt2),
    "Shizuku 未授权时给出用户该做的事");
  ok(/justTryTail/.test(prompt2) && /askTail/.test(prompt2),
    "收尾语也按原因分开（root 那条不需要用户先做什么）");

  // 事实与 caps 必须带上「就绪与否」，否则 agent 会把「还没验证」当成「有特权」。
  const factsJson = SRC.bridge.slice(
    SRC.bridge.indexOf("internal fun elevationJson"),
    SRC.bridge.indexOf("internal fun elevationJson") + 900
  );
  ok(/\.put\("ready"/.test(factsJson) && /\.put\("reason"/.test(factsJson),
    "elevation 事实带 ready/reason");
  ok(/\.put\("fellBackFrom"/.test(factsJson),
    "回退到别的通道时如实写出来（否则 agent 会以为用户选的就是这条）");
  ok(/shellReadonly/.test(SRC.bridge) && /JSONArray\(PrivilegedShell\.readonlyCommands\(\)\)/.test(SRC.bridge),
    "caps 里给出只读命令清单（严格档下猜错一次就白花用户一次点击）");

  // 提示词：未就绪要渲染成「先让用户补一步」，而不是整段消失
  const prompt = fs.readFileSync("app/src/main/assets/dsh-folk-host.mjs", "utf8");
  ok(/const ready = elevation\.ready !== false/.test(prompt), "提示词区分就绪与未就绪");
  ok(/SELECTED a privileged channel/.test(prompt) && /reason: "/.test(prompt),
    "未就绪时说明「已选择但还差一步」并给出原因");
  ok(/root_unverified \/ shizuku_unauthorized \/ adb_unpaired/.test(prompt),
    "三个未就绪原因进了失败词表");
  ok(/fellBackFrom/.test(prompt), "提示词会说明通道是回退来的");
  ok(/dsh-native caps\` lists the exact read-only/.test(prompt), "提示 agent 去 caps 查只读清单");

  // 事实文件是「agent 知不知道有特权」的唯一来源：改通道 / 刷新权限 / Shizuku 刚授权
  // 三条路径都得重写它。少了任何一条，用户会看到「我开了 root 它也不知道」。
  //
  // 这三段逻辑原先内联在「功能」页的调用点里（FunctionSettingsScreen.kt 给
  // FunctionSettingsContent 传的那几个 lambda）。权限相关的 UI 搬进「权限管理 → 特权通道」
  // 之后，它们整体搬到了 PrivilegedChannelScreen —— 断言跟着搬，意图一个字不改。
  // 每条都要求「锚点必须找到」：切片为空时若只查 includes，会静默通过。
  const channel = fs.readFileSync("app/src/main/java/me/bmax/apatch/ui/screen/settings/PermissionChannelScreens.kt", "utf8");
  const sliceBetween = (src, from, to) => {
    const a = src.indexOf(from);
    const b = src.indexOf(to);
    return a >= 0 && b > a ? src.slice(a, b) : "";
  };
  const prefBlock = sliceBetween(channel,
    "val onPermPrefChange: (String) -> Unit = { name ->",
    "val onRestrictModeChange: (Boolean) -> Unit = { on ->",
  );
  ok(prefBlock.length > 0 && prefBlock.includes("DshHostPrompt.writeFacts"),
    "切换权限通道后重写事实（否则容器里还停在旧值）");
  const refreshBlock = sliceBetween(channel,
    "val onRefreshPerm: () -> Unit = {",
    "val onRequestShizuku: () -> Unit = {");
  ok(refreshBlock.length > 0 && refreshBlock.includes("allowRootPrompt = true") &&
    refreshBlock.includes("DshHostPrompt.writeFacts"),
    "点「刷新权限」验过 root 后重写事实");
  const listenerRefresh = sliceBetween(channel, "val refresh = {", "val onResult =");
  ok(listenerRefresh.length > 0 && listenerRefresh.includes("PermissionManager.refresh(app)") &&
    listenerRefresh.includes("DshHostPrompt.writeFacts(app)"),
    "Shizuku 授权回调也会重写事实");
  // Shizuku 侧只能走用户服务：newProcess 的返回类型是库内部可见的，直接调编译不过
  ok(/DshShizukuShell\.exec\(/.test(shell), "Shizuku 通道走用户服务");
  ok(!/Shizuku\.newProcess/.test(code(shell)), "没有直接调被限制的 Shizuku.newProcess");
  const shizuku = fs.readFileSync("app/src/main/java/me/bmax/apatch/dsh/DshShizukuShell.kt", "utf8");
  ok(/Shizuku\.bindUserService\(/.test(shizuku) && /Shizuku\.unbindUserService\(/.test(shizuku),
    "用户服务有绑定也有解绑");
  // 这个版本的 bindUserService 返回 void：成功只看有没有抛，以及连接回调会不会来
  ok(/runCatching \{\s*\n\s*Shizuku\.bindUserService\(/.test(shizuku) && !/bindUserService\([^)]*\) == 0/.test(shizuku),
    "绑定按「有没有抛 + 等连接回调」判断，而不是比较返回值");
  const shizukuSvc = fs.readFileSync("app/src/main/java/me/bmax/apatch/dsh/DshShizukuShellService.kt", "utf8");
  ok(/MAX_CHARS/.test(shizukuSvc) && /clip\(/.test(shizukuSvc),
    "用户服务侧自己截断输出（binder 事务 1MB 上限）");
  ok(/destroyForcibly\(\)/.test(shizukuSvc), "超时由服务侧执行（binder 调用是同步的，应用侧放弃等待不会让命令停下）");
  ok(/aidl/.test(fs.readdirSync("app/src/main").join(",")) , "AIDL 目录存在（user service 的接口就在这里）");

  const bridge = SRC.bridge;
  ok(/"\/native\/shell" -> Cap\.SHELL/.test(bridge), "端点映射到 Cap.SHELL");
  ok(/PrivilegedShell\.riskOf\(params\["cmd"\]\.orEmpty\(\)\) != PrivRisk\.READONLY/.test(bridge),
    "读写判定看命令本身（否则「读」档位连 getprop 都用不了）");
  ok(/val confirm = PrivPolicy\.needsConfirm\(\s*\n?\s*restrictMode = PrivPolicy\.restrictMode\(ctx\),\s*\n?\s*capRestricted = PrivPolicy\.isRestricted\(ctx, cap\),\s*\n?\s*danger = danger,/.test(bridge),
    "限制模式接在闸门上（参数名逐个写出来，别靠位置）");
  ok(/val danger = cap == Cap\.SHELL &&\s*\n?\s*PrivPolicy\.shellRisk\(ctx, params\["cmd"\]\.orEmpty\(\)\) == PrivRisk\.DANGEROUS/.test(bridge),
    "危险判定来自清单，且只对 shell 命令（其余能力没有「危险动作」这个概念，它在不在清单里才是判据）");
  ok(/privDecision = if \(PrivPolicy\.restrictMode\(ctx\)\) "cap_not_listed" else "restrict_off"/.test(bridge),
    "审计能分辨「开关关着」与「这条能力不在清单里」");
  ok(!/Decision\.ALLOW_TRUST/.test(bridge), "桥里不再有第三个结论（弹窗也没有第三个按钮）");
  ok(!/Cap\.A11Y -> DshA11y\.riskOf/.test(bridge) && !/Cap\.DISPLAY -> if \(isWriteRequest/.test(bridge),
    "桥里不再按能力各写一套风险分级（那正是「有些能力从没被问过」的成因）");

  // ── 虚拟屏按端点分级：读的归读、动的归动 ──
  //
  // 以前一律 DANGEROUS，后果是"不管用户选哪一档严格程度，每条点击都要弹窗" —— 用虚拟屏
  // 这件事在实践中根本走不下去。分级必须与 isWriteRequest 是同一条线，所以逐端点对拍。
  // 虚拟屏的读/写分档仍由 isWriteRequest 决定（档位那一条线没变），但它不再决定"问不问"：
  // 现在由「限制模式 + 能力清单」决定，DISPLAY 默认在清单里。这里改钉 isWriteRequest 本身。
  ok(/private fun isWriteRequest\(method: String, path: String, params: Map<String, String>\): Boolean/.test(bridge),
    "读写分档仍是一条线（档位按它判，见 /native/shell 那条断言）");
  const dispRead = ["/native/display/status", "/native/display/screenshot"];
  const dispWrite = [
    "/native/display/session", "/native/display/screenshot2", "/native/display/tap",
    "/native/display/swipe", "/native/display/key", "/native/display/launch", "/native/display/stop",
  ];
  // 复刻 isWriteRequest 里虚拟屏那三行，确认与上面的风险分级逐端点一致
  const isWrite = (path) => {
    if (path === "/native/display/status" || path === "/native/display/screenshot") return false;
    if (path.startsWith("/native/display/")) return true;
    return null;
  };
  eq(dispRead.map(isWrite), [false, false], "查询与截图算只读（用户可以「让我看，但别动」）");
  eq(dispWrite.map(isWrite), [true, true, true, true, true, true, true],
    "建会话/点击/滑动/按键/启动/停止都算写");
  // 两边必须给出同一个答案，否则会出现"档位按读放行、严格程度按写弹窗"这种自相矛盾
  for (const path of dispRead.concat(dispWrite)) {
    const byWrite = isWrite(path);
    const byRisk = byWrite ? "write" : "readonly";
    const expect = dispRead.includes(path) ? "readonly" : "write";
    eq(byRisk === expect, true, "风险分级与 isWriteRequest 在 " + path + " 上一致");
  }
  ok(/if \(need != null \|\| confirm\)/.test(bridge), "「档位不足」与「按严格程度要确认」走同一条弹窗路径");
  ok(/Kind\.CALL/.test(bridge) && /Kind\.LEVEL/.test(bridge), "两种弹窗分开了（长期授权只对 LEVEL 有意义）");
  ok(/.put\("decision", decision\)/.test(bridge), "审计记录了「用户点过头还是自动放行」");
  ok(/\.put\("restrictMode", PrivPolicy\.restrictMode\(ctx\)\)/.test(bridge), "审计记录了当时的限制模式");
  ok(/PrivilegedShell\.denyReason\(ctx, risk, asRoot\)/.test(bridge), "执行前先过通道约束");
  ok(/spendOnce\(ctx, cap, method, path, params\)/.test(bridge), "「仅本次」配额按同样的读写判据消耗");
}

// ───────────────── 无障碍：动作风险与服务开关 ─────────────────
console.log("\n── 无障碍 ──");
{
  const a11y = fs.readFileSync("app/src/main/java/me/bmax/apatch/dsh/DshA11y.kt", "utf8");
  const svc = fs.readFileSync("app/src/main/java/me/bmax/apatch/dsh/DshA11yService.kt", "utf8");
  const xml = fs.readFileSync("app/src/main/res/xml/dsh_a11y.xml", "utf8");
  const auto = fs.readFileSync("app/src/main/res/xml/dsh_autostart_a11y.xml", "utf8");
  const manifest = fs.readFileSync("app/src/main/AndroidManifest.xml", "utf8");

  // 打字与系统动作原先算"危险"，后果与虚拟屏当初"一律危险"一样：加进「不再逐条确认」也
  // 照样每次弹窗（用户现场反馈"这个开关没用"）。判据与读写分级同源 —— 它们改屏幕状态，
  // 但没有一件改完回不去；危险档留给卸载/重启/清数据那类。
  const risk = (a) => (a === "tree" || a === "screenshot" ? "readonly" : "write");
  eq(["tree", "click", "tap", "swipe", "text", "global", "screenshot"].map(risk),
    ["readonly", "write", "write", "write", "write", "write", "readonly"],
    "看屏幕是只读；点、滑、打字、系统动作都是写（同一判据：改屏幕状态但都回得去）");
  ok(!/fun riskOf\(action: String\)/.test(a11y),
    "无障碍不再自己按动作分级：它在不在能力清单里才是判据（那张表已随旧模型一起删掉）");
  ok(/MAX_NODES/.test(a11y) && /MAX_DEPTH/.test(a11y), "读屏有节点数与深度上限（否则一次调用能读回几十万字符）");
  ok(/no_a11y_service/.test(a11y), "服务没开时返回可指路的 reason");
  ok(/no_window/.test(a11y), "安全窗口（锁屏/密码框）拿不到节点时单独一个 reason");
  ok(/isClickable/.test(a11y) && /performAction\(AccessibilityNodeInfo\.ACTION_CLICK\)/.test(a11y),
    "点击会往上找可点祖先（文案节点自己往往不可点）");
  ok(/ACTION_SET_TEXT/.test(a11y), "输入走 ACTION_SET_TEXT，不是模拟按键");
  ok(/dispatchGesture/.test(a11y) && /await\(/.test(a11y), "手势等回调再返回（不等只是「排队成功」）");
  // ── 往输入框写字：WebView 那一格（用户现场：class=EditText、text/id/desc 全空） ──
  // 用户报告"a11y text 打不进 WebView"。三条定位方式必须都在，且失败原因要能分开：
  // 定位失败 = not_found，焦点失败 = no_input_focus（以前两者都叫 no_input_focus）。
  ok(/findAll\(svc, target\.orEmpty\(\), className, searched\)/.test(a11y),
    "setText 的定位把 class 传进 findAll，并收下搜过的窗（只给 class 时 target 传空串：contains(\"\") 命中全部）");
  ok(/DshA11y\.setText\(\s*\n?\s*value,[\s\S]{0,160}?text\(params\["class"\]\),[\s\S]{0,80}?params\["index"\]/.test(CODE.bridge),
    "桥接把 class 与 index 透给 setText（钉调用点：只声明参数、不传等于没接）");
  ok(/\/native\/a11y\/text" -> listOf\(option\("target"\), option\("class"\), option\("index"\)\)/.test(CODE.bridge),
    "CLI 选项表里 text 有 --target/--class/--index（否则 agent 根本敲不出来）");
  {
    // 三种定位 + 两种失败原因，逐条钉住结构（顺序即语义：先 target、再 class、最后焦点）
    const i = a11y.indexOf("fun setText(");
    const seg = a11y.slice(i, a11y.indexOf("private fun writeInto", i));
    const byFocus = seg.indexOf("target.isNullOrBlank() && className.isNullOrBlank()");
    const find = seg.indexOf("svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)");
    const noFocus = seg.indexOf('fail("no_input_focus")');
    const loc = seg.indexOf("val matches = findAll(svc, target.orEmpty(), className, searched)");
    const notFound = seg.indexOf('fail("not_found")');
    ok(byFocus > 0 && find > byFocus && noFocus > find,
      "两个定位参数都没给 → 走 FOCUS_INPUT → 失败才是 no_input_focus");
    ok(loc > noFocus && notFound > loc,
      "给了定位 → findAll → 没命中是 not_found（不是 no_input_focus）");
  }
  {
    // "共用"要数**调用次数**，不是查函数在不在：两条定位分支各调一次，少一处就是又分叉了
    const i = a11y.indexOf("fun setText(");
    const seg = a11y.slice(i, a11y.indexOf("private fun writeInto", i));
    const calls = (seg.match(/writeInto\(/g) || []).length;
    ok(/private fun writeInto\(node: AccessibilityNodeInfo, text: String\)/.test(a11y) && calls === 2,
      "两条定位分支都调 writeInto（「共用」要数调用次数：改一处漏两处才是真风险）");
  }
  // ── 判因字段：树与失败都要能说清"问的是哪个窗口/哪个节点" ──
  ok(/if \(node.isFocused\) obj.put\("focused", true\)/.test(a11y),
    "树里报 focused（用户「节点没报 focused」的结论，根因是我们从没问过）");
  ok(/\.put\("input", inputDiag\(svc\)\)/.test(a11y) && /fun inputDiag\(svc: AccessibilityService\)/.test(a11y),
    "树里报 input（FOCUS_INPUT 到底解析到谁；解析不到 found:false）");
  ok(/\.put\("own", skippedOwn\)/.test(a11y),
    "树里报 own（活动窗口本来是我们自己的悬浮窗，读的是别的窗）");
  ok(/private fun pickRoot\(svc: AccessibilityService\): Pair<AccessibilityNodeInfo\?, Boolean>/.test(a11y) &&
    /pickRoot\(svc\)\.first\?\.findFocus/.test(a11y) &&
    /pickRoot\(svc\)/.test(a11y.slice(a11y.indexOf("fun snapshot"), a11y.indexOf("fun snapshot") + 900)),
    "树仍从 pickRoot 读（那是「主窗」）；显式寻址改用 searchRoots（多窗）—— 两条路各自说清搜哪儿");
  ok(/window.type == AccessibilityWindowInfo.TYPE_SYSTEM &&[\s\S]{0,80}?== svc.packageName/.test(a11y),
    "只跳过\"自家 + 系统窗口\"那一类：本应用自己的界面（TYPE_APPLICATION）仍是可驱动的目标");
  ok(/private fun windowDiag\(svc: AccessibilityService, out: JSONObject\): JSONObject/.test(a11y) &&
    /windowDiag\(svc, fail\("no_input_focus"\)\)/.test(a11y) &&
    /windowDiag\(\s*\n?\s*svc,\s*\n?\s*fail\("not_found"\)/.test(a11y),
    "两种失败都带上 window/windows[]（否则两种成因在返回值里一模一样）");
  {
    // 诊断**内容**也要钉：只说"带 window/windows"不够 —— 把 windows[] 摘掉、或
    // 不写 active/focused，两种成因照样分不开（反向验证漏过这两条）。
    const wi = a11y.indexOf("private fun windowDiag(");
    const end = a11y.indexOf("private fun fail(", wi);
    const seg = end > wi ? a11y.slice(wi, end) : a11y.slice(wi);
    ok(/\.put\("window", active\?\.packageName/.test(seg) && /\.put\("windows", windowArray\(svc\)\)/.test(seg),
      "诊断：活动窗口的包名 + **真的挂上去**的全部窗口列表（算了不挂 = 和没算一样）");
    const wa = a11y.slice(a11y.indexOf("private fun windowArray("), wi);
    ok(/\.put\("package", root\?\.packageName/.test(wa) &&
       /\.put\("system", w\.type == AccessibilityWindowInfo\.TYPE_SYSTEM\)/.test(wa) &&
       /\.put\("active", w\.isActive\)/.test(wa) &&
       /\.put\("focused", w\.isFocused\)/.test(wa),
      "每个窗口都报 package/system/active/focused（这四位才分得出谁在前、谁是自己）");
  }
  {
    const i = a11y.indexOf("private fun pickRoot(");
    const seg = a11y.slice(i, a11y.indexOf("private fun isOwnOverlay", i));
    ok(/if \(active != null && !ownOverlayActive\) return active to false/.test(seg),
      "活动窗口不是自家悬浮窗时，照旧用它（自排除不许把正常路径也改道）");
    ok(/other \?: active \?: svc\.windows/.test(seg) ||
       /other \?: active/.test(seg),
      "没有别的可读窗口就退回活动窗口（读自己的树好过读不到）");
  }

  // ── 显式寻址要搜**多个窗**：写入走输入焦点窗、树读活动窗，两者可以不是同一个 ──
  {
    const i = a11y.indexOf("private fun searchRoots(");
    const seg = a11y.slice(i, a11y.indexOf("private fun findAll(", i));
    ok(i > 0 && /svc\.windows/.test(seg) && /!isOwnOverlay\(svc, it\)/.test(seg),
      "searchRoots 遍历可读窗，并排除自家悬浮窗（自排除不许在寻址这条路上失效）");
    ok(seg.indexOf("svc.rootInActiveWindow?.windowId") < seg.indexOf("FOCUS_INPUT") &&
      seg.indexOf("FOCUS_INPUT") < seg.indexOf("FOCUS_ACCESSIBILITY"),
      "优先级：活动窗 → 输入焦点窗 → 读屏焦点窗（顺序固定，index 才可复现）");
    ok(/\.sortedBy/.test(seg) && /prio\.indexOf\(w\.id\)/.test(seg),
      "按优先级排序（不是碰运气取第一个）");
  }
  {
    const i = a11y.indexOf("private fun findAll(");
    const seg = a11y.slice(i, a11y.indexOf("private fun searchRoots", i) > 0 ? a11y.length : i);
    ok(/for \(root in searchRoots\(svc\)\)/.test(a11y.slice(i, i + 1200)),
      "findAll 搜的是 searchRoots 的全部窗（不是只搜活动窗 —— 那正是现场 --target 查不到自己刚写的字的原因）");
    ok(/searchedWindows\?\.add\(root\.windowId\)/.test(a11y.slice(i, i + 1200)),
      "记下实际搜过的窗 id（not_found 时这是分辨「没匹配上」与「没搜到那个窗」的唯一证据）");
  }
  {
    const i = a11y.indexOf("fun setText(");
    const seg = a11y.slice(i, a11y.indexOf("private fun writeInto", i));
    ok(/fun setText\(text: String, target: String\?, className: String\?, index: Int = 0\)/.test(a11y),
      "setText 收下 index（页面上两个输入框时「我要第二个」才表达得出来）");
    ok(/matches\.getOrNull\(index\)/.test(seg) && /index <= 0/.test(seg),
      "默认（index<=0）仍优先可编辑的那个；显式给了下标就按点名来");
    ok(/\.put\("matches", matches\.size\)/.test(seg) && /\.put\("searchedWindows", JSONArray\(searched\)\)/.test(seg),
      "not_found 报出命中数与搜过的窗（没有它就只能靠猜）");
    ok(/\.put\("matches", 0\)/.test(a11y) && /val searched = ArrayList<Int>\(\)[\s\S]{0,200}?findAll\(svc, target, className, searched\)/.test(a11y),
      "click 的 not_found 也报同一份证据（显式寻址是同一条路，证据也该同源）");
  }
  {
    const i = a11y.indexOf("private fun windowArray(");
    const seg = a11y.slice(i, a11y.indexOf("private fun windowDiag(", i));
    ok(/for \(w in svc\.windows\)/.test(seg) && /rootAvailable/.test(seg),
      "windowArray 是唯一一份窗口表实现（成功与失败共用，两处不会各说各话）");
    const loops = (a11y.match(/for \(w in svc\.windows\)/g) || []).length;
    eq(loops, 1, "全文件只有一处遍历窗口表（复制出第二份 = 迟早两处不一致）");
    ok(/\.put\("windows", windowArray\(svc\)\)/.test(a11y) &&
      (a11y.match(/\.put\("windows", windowArray\(svc\)\)/g) || []).length >= 2,
      "tree 成功时也带窗口表，失败时也带（同一个函数）");
  }

  // ── 空串 = 清空：判据不能是真值，清空也不能拿"粘贴空剪贴板"冒充成功 ──
  ok(/act === 'text' && a\.length > 1/.test(CODE.rt),
    "CLI 用 a.length > 1 判 text 的位置参数（a[1] 真值会把空串判成参数不全 → 只回 usage）");
  ok(!/act === 'text' && a\[1\]/.test(CODE.rt), "没有残留 a[1] 真值判据");
  ok(/val value = params\["text"\]\s*\n?\s*\?: return 400/.test(CODE.bridge) ||
     /params\["text"\] ?: return 400/.test(CODE.bridge),
    "桥只把「没给 text」当错误（空串要放行，否则清空这条命令到不了无障碍）");
  {
    const i = a11y.indexOf("private fun writeInto(");
    const seg = a11y.slice(i, a11y.indexOf("private fun pasteInto(", i));
    ok(/if \(text\.isEmpty\(\)\) return fail\("set_text_rejected"\)/.test(seg),
      "空串清不掉就如实回 set_text_rejected，不走粘贴（空剪贴板在多数宿主是无操作，performAction 照样回 true）");
    // 光比 pasteInto 的位置不够：「聚焦 → 判空 → 回 set_text_rejected」也满足"在粘贴之前"，
    // 而聚焦那一步只服务于粘贴退路。空串根本不该进退路。
    ok(seg.indexOf("text.isEmpty()") < seg.indexOf("ACTION_FOCUS") &&
      seg.indexOf("text.isEmpty()") < seg.indexOf("pasteInto(node, text)"),
      "空串判据在退路**之前**（连为粘贴服务的聚焦都不走；放后面等于没拦）");
  }

  // ── 焦点回退：三层，缺一层就是真机上「明明有输入框却说没有焦点」 ──
  {
    const i = a11y.indexOf("fun setText(");
    const seg = a11y.slice(i, a11y.indexOf("private fun writeInto", i));
    const s1 = seg.indexOf("svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)");
    const s2 = seg.indexOf("pickRoot(svc).first?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)");
    const s3 = seg.indexOf("firstEditable(pickRoot(svc).first)");
    ok(s1 > 0 && s2 > s1 && s3 > s2,
      "焦点三层回退：系统输入焦点窗 → 选中那棵树里再问一次 → 该树第一个可编辑节点（AOSP 会因跨 display 让第一层作废）");
    const fe = a11y.slice(a11y.indexOf("private fun firstEditable("));
    ok(/private fun firstEditable\(root: AccessibilityNodeInfo\?\)/.test(a11y) &&
      /node\.isEditable && node\.isVisibleToUser/.test(fe),
      "firstEditable 只认「可见 + 可编辑」（不可见的编辑框不是光标所在的那个）");
  }

  // ── 写入退路：SET_TEXT 被拒时「聚焦 + 剪贴板 + PASTE」，且如实标出走了哪一路 ──
  {
    const i = a11y.indexOf("private fun writeInto(");
    const j = a11y.indexOf("private fun firstEditable(", i);
    const seg = a11y.slice(i, j);
    const focus = seg.indexOf("performAction(AccessibilityNodeInfo.ACTION_FOCUS)");
    ok(/ACTION_SET_TEXT/.test(seg) && /put\("by", "set_text"\)/.test(seg),
      "先 ACTION_SET_TEXT，成功时 by=set_text（uiautomator 的 setText 也是这一路）");
    ok(focus > 0 && focus < seg.indexOf("pasteInto(node, text)"),
      "被拒时**先聚焦再粘贴**（很多框只在有输入焦点时才处理 PASTE）");
    ok(/put\("by", "paste"\)/.test(seg) && /fail\("set_text_rejected"\)/.test(seg),
      "粘贴成功 by=paste；两路都不行才是 set_text_rejected");
    const paste = a11y.slice(a11y.indexOf("private fun pasteInto("), j);
    ok(/ACTION_PASTE/.test(paste) && /setPrimaryClip\(ClipData\.newPlainText/.test(paste),
      "退路真的走 ACTION_PASTE（写剪贴板 + PASTE）");
    ok(/setPrimaryClip\(previous\)/.test(paste),
      "粘完把用户的剪贴板还原（agent 写一次字不该把它顶掉）");
  }

  // ── 诊断无损化：两种焦点分开 + 回答者所在窗 + 「根没拿到」与「包名为空」分开 ──
  ok(/\.put\("a11y", focusDiag\(svc, AccessibilityNodeInfo\.FOCUS_ACCESSIBILITY\)\)/.test(a11y) &&
    /fun inputDiag\(svc: AccessibilityService\): JSONObject =\s*\n\s*focusDiag\(svc, AccessibilityNodeInfo\.FOCUS_INPUT\)/.test(a11y),
    "input=FOCUS_INPUT 与 a11y=FOCUS_ACCESSIBILITY 分开报（两者常常不在同一个窗）");
  ok(/private fun focusDiag\(svc: AccessibilityService, type: Int\)/.test(a11y) &&
    /svc\.findFocus\(type\)/.test(a11y) && /\.put\("window", n\.windowId\)/.test(a11y),
    "focusDiag 按 type 各问各的，并报出回答者所在的窗 id（可与窗口表对照）");
  ok(/\.put\("rootWindow", root\.windowId\)/.test(a11y) &&
    /\.put\("rootChildren", root\.childCount\)/.test(a11y),
    "树报根身份：从哪个窗读的、根有几个孩子（「怎么才 37 个节点」全靠这两个字段分辨）");
  {
    const wi = a11y.indexOf("private fun windowDiag(");
    const end = a11y.indexOf("private fun fail(", wi);
    const seg = end > wi ? a11y.slice(wi, end) : a11y.slice(wi);
    ok(/val active = svc\.rootInActiveWindow/.test(seg) && /\.put\("windowReadable", active != null\)/.test(seg),
      "windowReadable：把「根没拿到」与「包名为空」分开（现场报告正是被有损诊断带偏的）");
    const wa = a11y.slice(a11y.indexOf("private fun windowArray("), wi);
    ok(/\.put\("id", w\.id\)/.test(wa) && /\.put\("type", w\.type\)/.test(wa) &&
      /\.put\("rootAvailable", root != null\)/.test(wa),
      "每个窗口报 id/type/rootAvailable（焦点字段里的 window 才有的对照）");
  }

  // ── bounds 设防：退化矩形显式报错，不静默点一条线 ──
  ok(/if \(rect\.right <= rect\.left \|\| rect\.bottom <= rect\.top\)/.test(a11y) &&
    /fail\("invalid_bounds"\)/.test(a11y) &&
    /\.put\("bounds", JSONArray\(listOf\(rect\.left, rect\.top, rect\.right, rect\.bottom\)\)\)/.test(a11y),
    "退化矩形（零宽 / 越界）显式报 invalid_bounds + 那个矩形（折叠动画里最常见）");

  // 两个无障碍服务必须是两份不同的配置：自启那个不能有读屏权限
  ok(/canRetrieveWindowContent="true"/.test(xml), "能力服务打开了读屏");
  const stripXml = (x) => x.replace(/<!--[\s\S]*?-->/g, "");
  ok(!/canRetrieveWindowContent/.test(stripXml(auto)),
    "自启服务仍然没有读屏权限（注释里提到它是为了说明为什么没有）");
  ok(/DshA11yService/.test(manifest) && /DshAutostartService/.test(manifest), "两个服务都在清单里");
  eq((stripXml(manifest).match(/BIND_ACCESSIBILITY_SERVICE/g) || []).length, 2, "两个无障碍服务各自都有权限门");
  ok(/canPerformGestures="true"/.test(xml), "能力服务允许手势");
  // ── a11y 截屏：一个只在 bind 时读、且是硬门槛的能力位 ──
  //
  // 用户报「无障碍权限的截屏是不是有问题」。根因：res/xml/dsh_a11y.xml 少了
  // android:canTakeScreenshot="true"。官方 javadoc（AccessibilityService.takeScreenshot）
  // 写明必须声明；AOSP 服务端（AbstractAccessibilityServiceConnection.takeScreenshot →
  // canTakeScreenshotLocked 失败）**直接抛 SecurityException**。它只在 bind 时从 XML 读
  // 一次（setServiceInfo 只同步 updateDynamicallyConfigurableProperties，capabilities 不在
  // 其中），所以运行时补不上、老连接也可能还带着旧能力位。
  //
  // 断言必须查**剥掉注释的** XML：注释里正好解释了这一位，只查原文会被自己的说明蒙过去。
  ok(/android:canTakeScreenshot="true"/.test(stripXml(xml)),
    "能力服务声明了 canTakeScreenshot（takeScreenshot 的硬门槛，缺了它系统在服务端抛 SecurityException）");
  ok(!/canTakeScreenshot/.test(stripXml(auto)),
    "自启服务不需要截屏能力（它每一位都取最小值，与能力服务是两份授权）");
  // 钉**调用点**：canTakeScreenshot 与 no_screenshot_capability 这两个名字在下面的
  // SecurityException 分支里都有，只查名字的话，把预检整个删掉照样绿（反向验证抓到过）。
  ok(/if \(!canTakeScreenshot\(svc\)\) return fail\("no_screenshot_capability"\)/.test(a11y),
    "客户端先自查能力位，缺了就回一个指名道姓的 reason（否则只剩笼统的 capture_failed，看起来像设备不支持）");
  ok(/AccessibilityServiceInfo\.CAPABILITY_CAN_TAKE_SCREENSHOT/.test(a11y),
    "自查的正是 canTakeScreenshot 那一位（查错位等于没查）");
  ok(/it is SecurityException/.test(a11y),
    "系统的 SecurityException 也归到 no_screenshot_capability（绑定期读一次这个事实要能传到 agent）");
  ok(/ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "capture_failed_no_access"/.test(a11y) &&
    /ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "capture_failed_too_soon"/.test(a11y) &&
    /ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "capture_failed_bad_display"/.test(a11y),
    "错误码映射成能行动的原因（no_access=去开开关、too_soon=等一下、bad_display=换一块屏）");
  {
    const m = a11y.match(/SCREENSHOT_MIN_GAP_MS = (\d+)L/);
    ok(m !== null && Number(m[1]) >= 333,
      "重试间隔不小于系统下限 333ms（ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS）—— " +
        "比它短的重试必然再撞同一个错");
  }
  ok(/reason == "capture_failed_too_soon" && attempt == 0/.test(a11y),
    "只对 too_soon（暂时性）重试，且**限一次**（不是无限重试）");
  ok(/instance === this/.test(svc), "服务断开时只清掉自己的引用（避免误清新实例）");
}

console.log("─ 预装/安装日志降噪");
{
  const repo = fs.readFileSync('app/src/main/java/me/bmax/apatch/dsh/DshPluginRepo.kt', 'utf8');
  const filter = fs.readFileSync('app/src/main/java/me/bmax/apatch/dsh/DshPluginLogFilter.kt', 'utf8');
  // 用户现场：预装一个插件就铺一屏 pnpm peer WARN（missing peer cordis/react/…）
  // 与 Progress 刷屏，还有我们自己的 [DSH-Folk-exit] 0。那些 peer 本来就该是缺的
  // （由 dsh 运行时提供），不该按错误量级展示。
  // 2026-09-25 用户反馈：Progress 行**别全丢**——它虽然刷屏，但也是装插件时唯一的实时进度，
  // 全丢就变成「点了安装，界面半天不动」。于是改成节流显示（用户明确要求排除含 missing 的行）。
  ok(/Issues with peer dependencies found/.test(filter) && /inPeerBlock = true/.test(filter),
    "过滤 pnpm 的 peer 依赖 WARN 块");
  ok(/trimmed\.startsWith\("Progress:"\) -> \{/.test(filter) &&
    /!trimmed\.contains\("missing", ignoreCase = true\) && acceptProgress\(trimmed\)\) emit\(line\)/.test(filter),
    "Progress 行不再整类丢掉：节流后显示（含 missing 的行仍不显示）");
  ok(/private fun acceptProgress\(trimmed: String\): Boolean/.test(filter) &&
    /if \(trimmed == lastProgress\) return false/.test(filter),
    "进度行必须去重（同样的计数不重复打）");
  ok(/val done = trimmed\.endsWith\("done"\)[\s\S]{0,120}if \(!done && now - lastProgressAt < PROGRESS_MIN_GAP_MS\) return false/.test(filter) &&
    /const val PROGRESS_MIN_GAP_MS = \d+L/.test(filter),
    "进度行必须节流（最快约 1 秒一行），但带 done 的收尾行一定放行");
  // 节流逻辑在 Node 里复刻一遍，确认「打太快会丢、done 一定过、重复不打」这三条真的成立
  {
    const GAP = 800;
    const seen = (lines, stepMs) => {
      let last = "", at = 0, out = [];
      // 起点用一个大数：真实实现里 lastProgressAt 初值 0，而 now 是 epoch 毫秒，
      // 所以第一条进度行必然放行（否则「点了安装先静默 800ms」）。
      let t = 1_000_000;
      for (const raw of lines) {
        t += stepMs;
        const trimmed = raw.trim();
        const done = trimmed.endsWith("done");
        if (trimmed === last) continue;
        if (!done && t - at < GAP) continue;
        last = trimmed;
        at = t;
        out.push(trimmed);
      }
      return out;
    };
    const fast = seen([
      "Progress: resolved 1, reused 0, downloaded 0, added 0",
      "Progress: resolved 20, reused 0, downloaded 3, added 0",
      "Progress: resolved 44, reused 0, downloaded 9, added 20, done",
    ], 100);
    ok(fast.length === 2 && fast[fast.length - 1].endsWith("done"),
      `打得再快也只在够间隔时放行，且 done 行一定过（实际 ${fast.length} 行：${JSON.stringify(fast)}）`);
    const same = seen([
      "Progress: resolved 44, reused 0, downloaded 0, added 0",
      "Progress: resolved 44, reused 0, downloaded 0, added 0",
      "Progress: resolved 44, reused 0, downloaded 0, added 0",
    ], 5000);
    ok(same.length === 1, `完全相同的进度行只显示一次（实际 ${same.length} 行）`);
  }
  ok(/startsWith\(DshPluginRepo\.EXIT_MARKER\)/.test(filter) && /exitCode = trimmed\.removePrefix/.test(filter),
    "退出标记不再直接显示，改成记下退出码");
  ok(/startsWith\("dependencies:"\)/.test(filter) && /startsWith\("Done in"\)/.test(filter),
    "从 dependencies: / Done in 提取摘要");
  // 这条最关键：没有兜底丢弃 —— 任何没被明确处理的行都必须原样交出去
  ok(/else -> emit\(line\)/.test(filter),
    "未识别的行一律原样显示（错误与堆栈绝不静默）");
  // 折叠必须能被打断：块后面跟着的 dsh 自己的报错行不能一起被吃掉
  ok(/trimmed\.startsWith\("dsh:"\)/.test(filter) && /isBlockEnd/.test(filter),
    "peer 块遇到 dsh: 段首即结束，后面的真实报错不会被一起折叠");
  // 返回值必须是原始输出：repairIfLinkageBroken 等要靠它解析
  ok(/val filter = DshPluginLogFilter\(\).*val out = DshRuntime\.execRootfsStreaming\(/s.test(repo) &&
    /\{ line -> filter\.accept\(line, onLine\) \}/.test(repo) &&
    /return out\.ifBlank/.test(repo),
    "只有界面那一路过滤，返回值仍是原始输出");
  ok(/reportFiltered\(filter\.finish\(\), onLine\)/.test(repo) &&
    /dsh_plug_log_summary/.test(repo) && /dsh_plug_log_peer_note/.test(repo) &&
    /dsh_plug_log_exit_code/.test(repo),
    "收尾给摘要 + peer 说明 + 非零退出码那句人话");
  const zh = fs.readFileSync('app/src/main/res/values-zh-rCN/dsh_strings.xml', 'utf8');
  ok(/dsh_plug_log_peer_note/.test(zh) && /由 DSH 运行时提供/.test(zh),
    "peer 说明解释了「为什么缺是正常的」");
}

// ───────────────── 权限页：原生能力桥关掉时必须看得出来 ─────────────────
//
// 这一块以前与总开关无关：关掉桥之后分项、共享存储、CLI 提示照样摊在页面上，每项按钮只是变灰。
// 用户看到的是一堵「关着的开关墙」，读不出「现在什么都不通」，也读不出「档位还留着」。
// 原先是靠**结构性**断言守的：那一整块必须在 `if (nativeBridgeEnabled)` 里面。
//
// 现在能力列表搬进了次级页（权限管理 → 分类），那个结构没有了，但**意图要换个地方守住**：
// 状态必须出现在用户看得见的那一层，而且总开关本身必须仍然够得着 —— 整块搬走最容易把它一起搬丢。
console.log("─ 原生能力桥：关掉时的状态必须出现在看得见的那一层");
const settingsSrc = fs.readFileSync("app/src/main/java/me/bmax/apatch/ui/screen/settings/FunctionSettings.kt", "utf8");
const screenSrc = fs.readFileSync("app/src/main/java/me/bmax/apatch/ui/screen/settings/FunctionSettingsScreen.kt", "utf8");
const hubSrc = fs.readFileSync("app/src/main/java/me/bmax/apatch/ui/screen/settings/PermissionHubScreen.kt", "utf8");
const capsSrc = fs.readFileSync("app/src/main/java/me/bmax/apatch/ui/screen/settings/PermissionCapsScreens.kt", "utf8");
{
  ok(!/for \(group in CapGroup\.entries\)/.test(settingsSrc),
    "能力档位不再内联在安全页上（搬进了权限管理的次级页）");
  ok(/item\(key = "function_permission"[\s\S]{0,400}onOpenPermissionHub/.test(settingsSrc),
    "安全页上只剩一个「权限管理」入口");
  ok(/onOpenPermissionHub = \{ navigator\.navigate\(PermissionHubScreenDestination\) \}/.test(screenSrc),
    "入口真的接到权限管理页（否则用户根本到不了）");

  // 总开关曾经和列表画在同一张卡片里，整块搬走时最容易把它一起搬丢 —— 而它丢了，
  // 用户就再也没有办法开关原生能力（参数还在、也还在传，所以编译不会报错）。
  ok(/DshNativeBridge\.setEnabled\(/.test(hubSrc) && /R\.string\.dsh_native_enable\b/.test(hubSrc),
    "原生能力总开关搬到了权限管理页（只搬列表会把开关留在够不着的地方）");

  // 状态要在可见层
  ok(/DshNativeBridge\.enabled\(context\)/.test(hubSrc), "权限管理页读总开关状态");
  ok(/DshNativeBridge\.Access\.OFF/.test(hubSrc), "并且数出「档位不是关」的能力项数");
  ok(/R\.string\.dsh_native_off_hint_kept, activeCaps/.test(hubSrc) &&
    /R\.string\.dsh_native_off_hint\b/.test(hubSrc),
    "两种收尾说明都在权限管理页上：有档位残留时报数，没有时只说「都不通」");

  // 进到分类页里也仍要知道桥是关着的
  ok(/if \(!nativeBridgeEnabled\)/.test(capsSrc), "分类页里也保留了「桥关着」的说明");
  ok(/R\.string\.dsh_native_off_hint_kept, activeCount/.test(capsSrc), "分类页里同样报残留条数");

  // CLI 提示跟着能力列表走，而且只在桥开着时出现
  ok(/dsh_native_cli_hint/.test(hubSrc), "CLI 提示跟着能力列表搬走了");
  ok(!/dsh_native_cli_hint/.test(settingsSrc), "安全页上不再残留 CLI 提示");
  ok(/if \(bridgeEnabled\) \{[\s\S]{0,300}?dsh_native_cli_hint/.test(hubSrc),
    "CLI 提示只在桥开着时出现（关着桥还教人怎么调 CLI，是自相矛盾的）");

  // 搜索框不许自动进入搜索态。
  //
  // startInSearchMode = true 会让 SearchBar 主动 requestFocus 并拉起键盘（SearchBar 里那个
  // LaunchedEffect），而键盘正好盖住这一页的正文 —— 分类列表。用户十次里有九次是来点分类的，
  // 一进来就被键盘挡住，"少点一下放大镜"省下的那一步远不抵这一下。
  // 放大镜按钮本来就在 SearchBar 的 actions 里，点它才进搜索态（见它的 onFocusChanged）。
  ok(!/startInSearchMode\s*=\s*true/.test(hubSrc),
    "权限管理页不要自动进入搜索态（键盘会盖住分类列表）");
  ok(/startInSearchMode\s*=\s*false/.test(hubSrc),
    "并且显式写成 false —— 删掉这一行也能过，但下次有人想「顺手改回 true」时就少了一句挡着的注释");
}
const nativeZh = fs.readFileSync("app/src/main/res/values-zh-rCN/dsh_strings.xml", "utf8");
const nativeEn = fs.readFileSync("app/src/main/res/values/dsh_strings.xml", "utf8");
ok(/dsh_native_off_hint_kept">[^<]*%1\$d/.test(nativeZh) && /dsh_native_off_hint_kept">[^<]*%1\$d/.test(nativeEn),
  "「档位保留」那句带条数占位符（中英一致由 check-strings 盯）");
ok(/保留/.test(nativeZh),
  "说明里明确写了档位是保留而不是清空（否则用户会以为关掉就把配置丢了）");

// ── 原生插件加载器：不许让它在无硬链接设备上走 materialize（link+unlink）──
// 真机事故（dsh 0.1.7-rc.2 + r5 运行时 + proroot）：loader 把 .node 物化到 /tmp 缓存时用
// link() 建目标、随后删掉源文件；容器里 link() 被 --link2symlink 改写，于是缓存里留下一个
// 加载不了的符号链接（-> /.l2s/...tmp0001），dsh 启动即
// "No usable native binding found for node-addon-require-builtin-linux-arm64-gnu"。
// 这个开关必须放在 applyEnv（所有 exec 路径共用）里，而不是只加在 startServer 那一处。
{
  const runtime = fs.readFileSync("app/src/main/java/me/bmax/apatch/dsh/DshRuntime.kt", "utf8");
  const envFn = (runtime.match(/private fun applyEnv\(pb: ProcessBuilder\) \{[\s\S]*?\n    \}/) || [""])[0];
  ok(envFn.length > 0, "找到 applyEnv（容器环境构造的唯一入口）");
  ok(/env\["NARB_DISABLE_NATIVE_CACHE"\] = "1"/.test(envFn),
    "applyEnv 里设了 NARB_DISABLE_NATIVE_CACHE=1（否则 loader 会走 link+unlink 物化，无硬链接设备上必挂）");
  ok(/applyEnv\(pb\)/.test(runtime) || /applyEnv\(probe\)/.test(runtime),
    "applyEnv 确实被各 exec 路径调用");
  // 只设一次、别在别处重复设（重复意味着有人以为要按运行时分支）
  // 只看**赋值**次数（注释里提到这个名字是正常的，别把文档算进去）
  const assigns = (runtime.match(/env\["NARB_DISABLE_NATIVE_CACHE"\] = "1"/g) || []).length;
  ok(assigns === 1, `NARB_DISABLE_NATIVE_CACHE 只赋值一次（实际 ${assigns} 次）：按运行时分支设两处只会漏掉一条 exec 路径`);
}

// ── 入口可达性：搬走一块 UI 时，最容易连「进去的按钮」一起搬丢 ──
// 真机回归：虚拟屏预览的入口原先挂在原生能力卡片的 DISPLAY 那一项下面。把能力列表整块搬进
// 分组页时，参数还留在 FunctionSettingsContent 的签名里、调用方也还在传值 —— 编译不报错，
// 但页面上已经没有任何地方渲染它。用户在设置里翻遍也找不到预览页，只会以为虚拟屏没做。
{
  const settings = fs.readFileSync("app/src/main/java/me/bmax/apatch/ui/screen/settings/FunctionSettings.kt", "utf8");
  const screen = fs.readFileSync("app/src/main/java/me/bmax/apatch/ui/screen/settings/FunctionSettingsScreen.kt", "utf8");
  const caps = fs.readFileSync("app/src/main/java/me/bmax/apatch/ui/screen/settings/PermissionCapsScreens.kt", "utf8");

  ok(/DisplayPreviewScreenDestination/.test(caps) && /dsh_display_preview_open/.test(caps),
    "虚拟屏预览入口渲染在分组页上（否则预览页没有任何入口，功能等于没做）");
  ok(/if \(cap == DshNativeBridge\.Cap\.DISPLAY\)/.test(caps),
    "预览入口只挂在虚拟屏那一项上，不是每张卡片都给一个");
  ok(!/onOpenDisplayPreview/.test(settings),
    "FunctionSettingsContent 不再留着 onOpenDisplayPreview（留着就是「看着还在通」的死管道）");
  ok(!/onOpenDisplayPreview/.test(screen),
    "调用方也不再为这个已搬走的参数传值");
}

// ── 通用规则：内容页签名里的每个参数都必须在页内真的被用到 ──
// 只出现一次（就是它自己那行声明）＝ 死管道。本仓已经连续中过三次：原生能力总开关、
// 共享存储、虚拟屏预览 —— 每次都是「功能搬去了次级页，接口留在原地」，而因为参数还在、
// 调用方还在传，编译与静态检查全都不报错。
{
  const settings = fs.readFileSync("app/src/main/java/me/bmax/apatch/ui/screen/settings/FunctionSettings.kt", "utf8");
  const sig = settings.match(/fun FunctionSettingsContent\(([\s\S]*?)\n\) \{/);
  ok(sig !== null, "解析到 FunctionSettingsContent 的签名");
  if (sig) {
    const names = [...sig[1].matchAll(/^ {4}([a-zA-Z][a-zA-Z0-9]*):/gm)].map((m) => m[1]);
    ok(names.length > 50, `参数表解析出 ${names.length} 个参数（解析失败会让下面这条形同虚设）`);
    const dead = names.filter(
      (nm) => (settings.match(new RegExp(`\\b${nm}\\b`, "g")) || []).length <= 1,
    );
    ok(dead.length === 0,
      `FunctionSettingsContent 没有死参数（实际 ${dead.length} 个：${dead.join(" / ")}）`);
  }
}

// ── 通用规则：FunctionSettings.kt 里不许留死私有函数 ──
// 同一个重构还留下了两份「活的那份在别的文件里」的死拷贝：`yesNo` 与 `permOptionSummary`。
// 它们编译得过、门禁也不报，直到有人恰好看见两处同名实现才发现。
// 私有顶层函数只可能在本文件被调用，所以「名字在本文件只出现一次」＝只有声明没有调用。
// （`internal` 不在此列：nativeCapTitleRes 等是跨文件共用的入口，本文件内只出现一次正常。）
// 局限：函数只在 KDoc 里被 `[name]` 提及时会算 2 次 —— 漏报可接受，误报不可接受。
{
  const settings = fs.readFileSync("app/src/main/java/me/bmax/apatch/ui/screen/settings/FunctionSettings.kt", "utf8");
  const privates = [...settings.matchAll(/^private fun ([A-Za-z0-9_]+)/gm)].map((m) => m[1]);
  ok(privates.length > 5, `解析出 ${privates.length} 个私有顶层函数（解析失败会让下面这条形同虚设）`);
  const dead = privates.filter(
    (nm) => (settings.match(new RegExp(`\\b${nm}\\b`, "g")) || []).length <= 1,
  );
  ok(dead.length === 0,
    `FunctionSettings.kt 没有死私有函数（实际 ${dead.length} 个：${dead.join(" / ")}）`);
}

// ── 特权通道：状态要分清「差在哪一步」，未就绪的通道必须有出口 ──
// 原先每条选项只有「可用 /（当前不可用）」两态，选中一条不可用的通道之后只留一句
// 「首选通道当前不可用，已回退为 X」。用户读不出差的是哪一步（没授权？本机没检测到？
// 根本没配置？），也没有任何地方能点进去把它修好 —— 三种完全不同的处境被糊成一句
// 「不可用」，下一步动作只能靠猜。
{
  const ch = fs.readFileSync("app/src/main/java/me/bmax/apatch/ui/screen/settings/PermissionChannelScreens.kt", "utf8");
  const readyStart = ch.indexOf("private fun readinessOf(");
  const readyBlock = readyStart < 0 ? "" : ch.slice(readyStart, readyStart + 1200);
  ok(readyBlock.length > 0, "找到通道就绪判定 readinessOf");
  for (const fact of ["suPresent", "rootVerified", "shizukuRunning", "shizukuGranted", "adbPaired"]) {
    ok(readyBlock.includes(fact), `就绪判定真的读了 ${fact}`);
  }
  const states = ["ROOT_UNVERIFIED", "ROOT_ABSENT", "SHIZUKU_UNGRANTED", "SHIZUKU_ABSENT", "ADB_UNCONFIGURED"];
  const missingStates = states.filter((s) => !ch.includes(s));
  ok(missingStates.length === 0,
    `五种未就绪状态都有名字（缺 ${missingStates.join(",") || "无"}）`);
  for (const key of [
    "dsh_perm_state_root_unverified",
    "dsh_perm_state_root_absent",
    "dsh_perm_state_shizuku_ungranted",
    "dsh_perm_state_shizuku_absent",
    "dsh_perm_state_adb_unconfigured",
  ]) {
    ok(ch.includes(key), `未就绪状态有专属文案 ${key}（而不是一句笼统的「不可用」）`);
  }
  ok(/readinessOf\(name, perm\)[\s\S]{0,150}guideReadiness = it/.test(ch),
    "选中一条未就绪的通道会打开引导（而不是只留一句「已回退」）");
  ok(/dsh_perm_fell_back_go/.test(ch),
    "「还没配置好」那一行也留了同一个出口（通道可能在选中之后才失效）");
  // 锚点必须是**调用**，不能只是类型名：本文件顶部就 import 了
  // WirelessAdbScreenDestination，只匹配名字的话，把跳转删掉这条照样绿（反向验证栽过）。
  ok(/navigate\(WirelessAdbScreenDestination\)/.test(ch), "ADB 未配置的引导能到配对页");
  ok(/FunctionSettingsScreenDestination\("function_runtime"\)/.test(ch),
    "运行时没装时引导去装运行时（配对脚本跑在容器里，装了才谈得上配对）");
  ok(/dsh_chguide_shizuku_request/.test(ch) && /onRequestShizuku/.test(ch),
    "Shizuku 在跑但未授权时，引导直接申请授权");
  ok(/dsh_chguide_root_verify/.test(ch), "检测到 root 未验证时，引导去验证");
  ok(/allowRootPrompt = true/.test(ch),
    "「去验证 / 重新探测」允许真跑 su（默认不弹授权框，见 PermissionManager.refresh）");
}

// ── 「不再逐条确认」名单（P2）──
//
// 这一段的教训：第一版只查"关键字都在不在"，16 条变异里漏了 8 条 —— 把
// `if (risk == DANGEROUS) return true` 与 `if (trusted) return false` 换个顺序，两个字符串都还在，
// 语义却变成"信任可以越过危险操作"。所以下面凡是"顺序有意义"的地方一律比**位置**，
// 凡是"按钮真的接上了"的地方一律查**调用**而不是类型名。
console.log("── 限制模式页与弹窗 ──");
{
  const page = code(
    fs.readFileSync("app/src/main/java/me/bmax/apatch/ui/screen/settings/RestrictModeScreen.kt", "utf8"),
  );
  ok(/PrivPolicy\.setRestrictMode\(context\.applicationContext, want\)/.test(page), "清单页有总开关");
  ok(/PrivPolicy\.setCapRestricted\(context\.applicationContext, cap, want\)/.test(page),
    "清单页能改能力清单（撤销入口必须找得到）");
  ok(/PrivPolicy\.addDangerEntry/.test(page) && /PrivPolicy\.removeDangerEntry/.test(page) &&
    /PrivPolicy\.resetDangerList/.test(page),
    "危险清单可增、可删、可恢复默认（用户要的就是这三件事）");
  ok(/DshHostPrompt\.writeFacts\(context\.applicationContext\)/.test(page),
    "改完名单要刷新宿主事实（漏了这一步 agent 会一直按旧假设行事）");
  ok(/CapGroup\.entries\.mapNotNull \{ group ->/.test(page),
    "按**分类**遍历全部能力（只列一类 = 加一项还得先想起来它属于哪一类）");
  ok(/Switch\(checked = restricted, onCheckedChange = onToggle\)/.test(page),
    "每一项真的有一个开关，且接到 onToggle");
  {
    // 搜索谓词整段钉住：三层匹配 + 一个都不许少（只查 contains(q) 在不在文件里，
    // 把 filter 整个删掉照样绿）。
    const at = page.indexOf("val caps = group.caps.filter { cap ->");
    const seg = at < 0 ? "" : page.slice(at, page.indexOf("}", at));
    const needles = (seg.match(/contains\(q\)/g) || []).length;
    ok(at >= 0 && /q\.isEmpty\(\) \|\|/.test(seg) && needles === 3,
      "搜索覆盖三层（能力名 / 摘要 / 分类名），且真的过了一遍 filter");
  }
  {
    const hub = code(
      fs.readFileSync("app/src/main/java/me/bmax/apatch/ui/screen/settings/PermissionHubScreen.kt", "utf8"),
    );
    // 位置必须排在分类循环**之后**（它是一个开关 + 两张跨全部能力的清单，不是一个能力分类）。
    // 定位靠这一行自己的特征（图标），不靠字符串：那个串在 searchHits 里也有一份。
    const loopAt = hub.indexOf("for (group in CapGroup.entries) {");
    const iconAt = hub.indexOf("icon = Icons.Filled.VerifiedUser");
    const navAt = hub.indexOf("onClick = { navigator.navigate(RestrictModeScreenDestination) }");
    ok(loopAt >= 0 && iconAt > loopAt,
      "入口在权限总页的分类列表**最下面**（循环之后，而不是混在分类之间）");
    ok(navAt > iconAt && navAt - iconAt < 900,
      "那一行的按钮真的接上了限制模式页（导入还在、调用换成别的页 = 死管道）");
    ok(/restrictTitle\.lowercase\(\)\.contains\(q\)/.test(hub) && /HubTarget\.Restrict/.test(hub),
      "总页的搜索也能搜到它（搜「限制」却搜不到这一页 = 用户找不着）");
    ok(/PrivPolicy\.restrictMode\(context\)/.test(hub) && /PrivPolicy\.restrictedCaps\(context\)\.size/.test(hub),
      "总页那一行显示的是真实状态（开关 + 清单条目数），不是写死的文案");
  }
  const capsCode = code(
    fs.readFileSync("app/src/main/java/me/bmax/apatch/ui/screen/settings/PermissionCapsScreens.kt", "utf8"),
  );
  ok(!/dsh_priv_trust_title/.test(capsCode) && !/onToggleTrust/.test(capsCode),
    "能力卡上不再放同一颗开关（一处管理，避免两个入口各说各话）");
  {
    const ch = code(
      fs.readFileSync("app/src/main/java/me/bmax/apatch/ui/screen/settings/PermissionChannelScreens.kt", "utf8"),
    );
    ok(!/PrivStrictness/.test(ch) && /dsh_priv_restrict_title/.test(ch) &&
      /PrivPolicy\.setRestrictMode\(context\.applicationContext, on\)/.test(ch),
      "通道页那段三档选择换成限制模式开关（旧三档在界面上不留残骸）");
    ok(/navigator\.navigate\(RestrictModeScreenDestination\)/.test(ch),
      "通道页也有进清单页的入口（否则用户只知道有开关、不知道清单在哪）");
  }
  {
    const dlg = code(
      fs.readFileSync("app/src/main/java/me/bmax/apatch/ui/component/ElevationRequestDialog.kt", "utf8"),
    );
    ok(!/allowTrusted/.test(dlg) && !/dsh_native_elevate_trust/.test(dlg),
      "弹窗没有第三个按钮（名单只在一处管理，挂在弹窗上等于第二个入口）");
    ok(/dsh_native_elevate_where_hint/.test(dlg),
      "弹窗说清「想免掉去哪儿」（只删按钮不说去向 = 用户只能在设置里乱翻）");
    ok(/val persistent = !confirmOnly/.test(dlg),
      "「允许（长期）」只在档位不够时给（它动的是档位，不是「还问不问」）");
  }
  {
    const home = fs.readFileSync("app/src/main/java/me/bmax/apatch/ui/screen/Home.kt", "utf8");
    const noticeAt = home.indexOf("else if (showPolicyNotice)");
    const changeAt = home.indexOf("else if (showChangelog)");
    ok(noticeAt > 0 && changeAt > 0 && noticeAt > changeAt,
      "策略变更说明排在更新内容**之后**（同一串互斥分支，不会两个弹窗叠在一起）");
    ok(/PrivPolicy\.policyNoticePending/.test(home) && /PrivPolicy\.consumePolicyNotice/.test(home),
      "提示读真实标志位、弹过即清（不依赖「这版更新说明还没看过」）");
    const app = fs.readFileSync("app/src/main/java/me/bmax/apatch/APatchApp.kt", "utf8");
    ok(/PrivPolicy\.migrateOnce\(this\)/.test(app),
      "迁移在应用启动时跑（不靠用户打开首页 —— 后台调用不该按旧键判定）");
  }
  {
    const facts = fs.readFileSync("app/src/main/java/me/bmax/apatch/dsh/DshHostPrompt.kt", "utf8");
    ok(/\.put\("restrictMode", PrivPolicy\.restrictMode\(ctx\)\)/.test(facts) &&
      /\.put\(\s*\n?\s*"restrictedCaps"/.test(facts),
      "事实文件带上限制模式与能力清单");
    const prompt = fs.readFileSync("app/src/main/assets/dsh-folk-host.mjs", "utf8");
    ok(/f\.restrictMode/.test(prompt) && /f\.restrictedCaps/.test(prompt),
      "提示词读了这两项事实");
    ok(!/no-more-asking list/.test(prompt) && !/privStrictness/.test(prompt),
      "提示词里没有旧概念残留（否则 agent 会按已不存在的规则行事）");
  }
}
console.log(bad === 0 ? `\n全部通过（${n} 项断言）` : `\n${bad}/${n} 项失败`);
process.exit(bad === 0 ? 0 : 1);
