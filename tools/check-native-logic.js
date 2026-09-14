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
console.log("── 特权严格程度 ──");
{
  const priv = fs.readFileSync("app/src/main/java/me/bmax/apatch/dsh/PrivPolicy.kt", "utf8");
  const risk = (s, r) => {
    if (s === "strict") return true;
    if (s === "normal") return r !== "readonly";
    return r === "dangerous";
  };
  // 复刻体：必须与 Kotlin 里的分支一一对应
  const needs = (strictness, r) =>
    strictness === "strict" ? true : strictness === "normal" ? r !== "readonly" : r === "dangerous";
  eq([needs("strict", "readonly"), needs("strict", "write"), needs("strict", "dangerous")], [true, true, true],
    "严格档：读、写、危险都要用户同意（含只读，这正是它区别于「一般」的地方）");
  eq([needs("normal", "readonly"), needs("normal", "write"), needs("normal", "dangerous")], [false, true, true],
    "一般档：只读免确认，写与危险要确认");
  eq([needs("loose", "readonly"), needs("loose", "write"), needs("loose", "dangerous")], [false, false, true],
    "宽松档：档位内免确认，只有危险命令要确认");
  eq([risk("strict", "readonly"), risk("normal", "write"), risk("loose", "dangerous")], [true, true, true],
    "复刻体与参考实现一致");
  ok(/PrivStrictness\.STRICT -> true/.test(priv), "严格档在所有等级上都返回 true（没有给只读开后门）");
  ok(/PrivStrictness\.NORMAL -> risk != PrivRisk\.READONLY/.test(priv), "一般档只给只读免确认");
  ok(/PrivStrictness\.LOOSE -> risk == PrivRisk\.DANGEROUS/.test(priv), "宽松档只拦危险命令");
  ok(/fun allowsPersistentGrant\(strictness: PrivStrictness\): Boolean =\s*strictness != PrivStrictness\.STRICT/.test(priv),
    "严格档不给「允许（长期）」");
  ok(/entries\.firstOrNull \{ it\.id == raw \} \?: DEFAULT/.test(priv) && /val DEFAULT = STRICT/.test(priv),
    "认不出来的严格程度落回最保守的一档");
  // 默认值必须是 strict：prefs 里没有这一项时读出来就该是 strict
  ok(/KEY_PRIV_STRICTNESS = "priv_strictness"/.test(SRC.rt === undefined ? "" : fs.readFileSync("app/src/main/java/me/bmax/apatch/dsh/DshEnv.kt", "utf8")),
    "严格程度有独立的 prefs 键（默认 strict 由 PrivStrictness.DEFAULT 兜底）");
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

  const bridge = SRC.bridge;
  ok(/"\/native\/shell" -> Cap\.SHELL/.test(bridge), "端点映射到 Cap.SHELL");
  ok(/PrivilegedShell\.riskOf\(params\["cmd"\]\.orEmpty\(\)\) != PrivRisk\.READONLY/.test(bridge),
    "读写判定看命令本身（否则「读」档位连 getprop 都用不了）");
  ok(/val confirm = risk != null && PrivPolicy\.needsConfirm\(strictness, risk\)/.test(bridge),
    "严格程度接在闸门上");
  ok(/if \(need != null \|\| confirm\)/.test(bridge), "「档位不足」与「按严格程度要确认」走同一条弹窗路径");
  ok(/Kind\.CALL/.test(bridge) && /Kind\.LEVEL/.test(bridge), "两种弹窗分开了（长期授权只对 LEVEL 有意义）");
  ok(/.put\("decision", decision\)/.test(bridge), "审计记录了「用户点过头还是自动放行」");
  ok(/.put\("strictness", PrivPolicy\.of\(ctx\)\.id\)/.test(bridge), "审计记录了当时的严格程度");
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

  const risk = (a) => (a === "tree" ? "readonly" : a === "text" || a === "global" ? "dangerous" : "write");
  eq(["tree", "click", "tap", "swipe", "text", "global"].map(risk),
    ["readonly", "write", "write", "write", "dangerous", "dangerous"],
    "看屏幕是只读；点滑是写；打字与系统动作是危险");
  ok(/fun riskOf\(action: String\): PrivRisk/.test(a11y), "无障碍也有自己的风险分级");
  ok(/MAX_NODES/.test(a11y) && /MAX_DEPTH/.test(a11y), "读屏有节点数与深度上限（否则一次调用能读回几十万字符）");
  ok(/no_a11y_service/.test(a11y), "服务没开时返回可指路的 reason");
  ok(/no_window/.test(a11y), "安全窗口（锁屏/密码框）拿不到节点时单独一个 reason");
  ok(/isClickable/.test(a11y) && /performAction\(AccessibilityNodeInfo\.ACTION_CLICK\)/.test(a11y),
    "点击会往上找可点祖先（文案节点自己往往不可点）");
  ok(/ACTION_SET_TEXT/.test(a11y), "输入走 ACTION_SET_TEXT，不是模拟按键");
  ok(/dispatchGesture/.test(a11y) && /await\(/.test(a11y), "手势等回调再返回（不等只是「排队成功」）");

  // 两个无障碍服务必须是两份不同的配置：自启那个不能有读屏权限
  ok(/canRetrieveWindowContent="true"/.test(xml), "能力服务打开了读屏");
  const stripXml = (x) => x.replace(/<!--[\s\S]*?-->/g, "");
  ok(!/canRetrieveWindowContent/.test(stripXml(auto)),
    "自启服务仍然没有读屏权限（注释里提到它是为了说明为什么没有）");
  ok(/DshA11yService/.test(manifest) && /DshAutostartService/.test(manifest), "两个服务都在清单里");
  eq((stripXml(manifest).match(/BIND_ACCESSIBILITY_SERVICE/g) || []).length, 2, "两个无障碍服务各自都有权限门");
  ok(/canPerformGestures="true"/.test(xml), "能力服务允许手势");
  ok(/instance === this/.test(svc), "服务断开时只清掉自己的引用（避免误清新实例）");
}

console.log(bad === 0 ? `\n全部通过（${n} 项断言）` : `\n${bad}/${n} 项失败`);
process.exit(bad === 0 ? 0 : 1);
