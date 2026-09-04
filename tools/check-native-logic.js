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

console.log(bad === 0 ? `\n全部通过（${n} 项断言）` : `\n${bad}/${n} 项失败`);
process.exit(bad === 0 ? 0 : 1);
