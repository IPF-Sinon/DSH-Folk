#!/usr/bin/env node
// 校验「更新说明」与「测试版通道」这两件事的内部一致性。
//
// 它们各自都有一个安静失效的方式：
//
//  1. 更新说明的版本号写在三个地方（build.gradle.kts 的基准、util/Changelog.kt 的
//     VERSION、以及那份条目文案）。发版时改了版本却忘了改文案，用户看到的是「1.8.1
//     更新了什么」配上 1.8.0 的内容 —— 一句自信的假话。运行时有兜底（版本不符就不弹），
//     但那意味着**新版本的用户什么都看不到**，而没人会发现。
//
//  2. 测试版通道依赖三处配合：工作流发的是 prerelease、tag 长得像版本号、App 侧按
//     prerelease 标记与 tag 后缀排除。任一处漏掉的后果都是「开关看起来没用」或者更糟 ——
//     「关着开关的人也被推上测试版」。
//
// 这些全都没有编译期信号，也不会让任何测试变红。
const fs = require("fs");
const path = require("path");

const ROOT = path.resolve(__dirname, "..");
const read = (p) => fs.readFileSync(path.join(ROOT, p), "utf8");

let fail = 0;

/**
 * 取一个顶层函数的**函数体**。
 *
 * 不用「从函数名往后取 N 个字符」：文件里下一个函数的内容会滑进这个窗口，让「这个
 * 函数里必须出现 X」的断言在 X 被删掉之后照样通过（第一版就是这样，反向验证抓到了）。
 * 这里按花括号深度找真正的结束位置。
 */
function fnBody(src, header) {
  const at = src.indexOf(header);
  if (at < 0) return null;
  const open = src.indexOf("{", at);
  // 表达式体的函数（`fun f() = a && b`）没有 `{`，或者它的 `{` 属于后面某个函数 ——
  // 两种情况都退到 memberBody 的「切到下一个同级声明」策略。
  const nextDecl = nextMemberAt(src, at);
  if (open < 0 || (nextDecl > 0 && open > nextDecl)) return memberBody(src, at);
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    if (src[i] === "{") depth++;
    else if (src[i] === "}") {
      depth--;
      if (depth === 0) return src.slice(at, i + 1);
    }
  }
  return null;
}

/** 下一个同级成员声明的位置（-1 表示没有）。 */
function nextMemberAt(src, from) {
  const re = /\n(?:\s{0,4})(?:@|fun |val |var |const |private |internal |object |class )/g;
  re.lastIndex = from + 1;
  const m = re.exec(src);
  return m ? m.index : -1;
}

/** 从 [from] 切到下一个同级成员声明，用于表达式体的函数。 */
function memberBody(src, from) {
  const end = nextMemberAt(src, from);
  return src.slice(from, end < 0 ? src.length : end);
}
function ok(cond, msg) {
  console.log((cond ? "  ✓ " : "  ✗ ") + msg);
  if (!cond) fail++;
}

/** 剥 Kotlin 注释：`must not appear` 类断言必须扫剥过的文本。 */
function code(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/[^\n]*/g, "");
}

const gradle = read("build.gradle.kts");
const changelogKt = code(read("app/src/main/java/me/bmax/apatch/util/Changelog.kt"));
const stringsEn = read("app/src/main/res/values/strings.xml");
const stringsZh = read("app/src/main/res/values-zh-rCN/strings.xml");
const checker = code(read("app/src/main/java/me/bmax/apatch/util/UpdateChecker.kt"));
/** 剥 YAML 注释：注释里解释某个 flag 为什么重要，不能算作那个 flag 存在。 */
function yml(src) {
  return src
    .split("\n")
    .filter((l) => !/^\s*#/.test(l))
    .join("\n");
}

const betaYml = yml(read(".github/workflows/beta.yml"));
const home = code(read("app/src/main/java/me/bmax/apatch/ui/screen/Home.kt"));
const dialog = code(read("app/src/main/java/me/bmax/apatch/ui/component/WelcomeGuide.kt"));

// ── 1. 版本号三处一致 ──
console.log("── 版本号 ──");
const baseName = gradle.match(/fun baseVersionName\(\): String = "([^"]+)"/);
const baseCode = gradle.match(/fun baseVersionCode\(\): Int = (\d+)/);
ok(baseName !== null, "build.gradle.kts 有 baseVersionName()" + (baseName ? ` = ${baseName[1]}` : ""));
ok(baseCode !== null, "build.gradle.kts 有 baseVersionCode()" + (baseCode ? ` = ${baseCode[1]}` : ""));

// `.kts` 的脚本体被编译成一个类的主体，那里**不允许** const val —— 写了就是
// 「Const 'val' is only allowed on top level…」，而这只有 Kotlin 编译器会说，
// 也就是要等 CI 十几分钟。基准版本一律用函数：另一个候选（普通 val）有更安静的坑，
// 见那段 KDoc。
ok(!/^\s*(private )?const val/m.test(gradle),
  "build.gradle.kts 里没有 const val（脚本体不允许，只有 Kotlin 编译器会告诉你）");

// 基准版本必须是**没有副作用、没有初始化顺序**的函数：`managerVersionCode by
// extra(getVersionCode())` 在脚本很靠前的位置就执行，一个声明在后面的 val 此刻还是
// 默认值，构建会静默拿到错误的版本号。
ok(/fun baseVersionName\(\)/.test(gradle) && /fun baseVersionCode\(\)/.test(gradle),
  "基准版本是函数而不是属性（脚本里的 val 有初始化顺序，会静默取到 0）");

const clVersion = changelogKt.match(/VERSION = "([^"]+)"/);
ok(clVersion !== null, "Changelog.VERSION 存在" + (clVersion ? ` = ${clVersion[1]}` : ""));
if (baseName && clVersion) {
  ok(baseName[1] === clVersion[1],
    `Changelog.VERSION 与 baseVersionName() 一致（${clVersion[1]} vs ${baseName[1]}）`);
}

// versionCode 必须与版本名对应：1.8.0 → 10800。beta.yml 里那段推导用的是同一规则，
// 两边脱钩会让测试版的 versionCode 落在正式版的错误一侧。
if (baseName && baseCode) {
  const parts = baseName[1].split(".").map((x) => parseInt(x, 10));
  // 四段式（补丁版）= 前三位算出的号 + 第四段：1.8.2.1 → 10803、1.9.2.1 → 10903、1.9.2.2 → 10904。
  // 必须**大于**它修补的那个正式版，否则 App 内更新检查认不出、系统也可能拒绝覆盖
  // （相同号允许覆盖，但「比它大」才是我们想要的语义）。
  const floor = parts.length === 3
    ? parts[0] * 10000 + parts[1] * 100 + parts[2]
    : parts.length === 4
      ? parts[0] * 10000 + parts[1] * 100 + parts[2] + parts[3]
      : null;
  // 公式值是**下限**：正式版可以显式抬高 versionCode，以便盖过此前测试版线里已用掉的号
  // （测试版 1.9.2.34-beta.101 的 vc 已是 10936，公式给正式版 1.9.5 只有 10905 会被系统当降级、
  // 装了 beta 的用户无法覆盖更新）。所以只要求 ≥ 公式下限、且不小于本项目已发布过的最高号。
  // 注意：手动抬高后，后续走 beta.yml 公式推导的测试版号可能低于它，需要人工确认单调递增
  // （beta.yml 会拿 baseVersionCode() 当下限取 max，所以这个数也是测试版号的输入）。
  //
  // **每发一个正式版就把它抬到那版的 baseVersionCode()** —— 它记的是已经发出去的最高号，
  // 不是"当前版本号"。抬晚了（或忘了抬）就会出现「新版本的号低于用户已装的号」：系统按降级
  // 拒绝安装、App 的 compareVersions 也不会提示，整个更新通道静默失效。2.0.0 → 20000。
  const PUBLISHED_FLOOR = 20000; // 已发布过的最高 baseVersionCode（正式版 2.0.0；抬到 20000 前是 1.9.5 的 10950）
  ok(floor !== null && Number(baseCode[1]) >= floor && Number(baseCode[1]) >= PUBLISHED_FLOOR,
    `baseVersionCode() 不低于公式下限且盖过已发布最高号（${baseName[1]} → 下限 ${floor} / 已发 ${PUBLISHED_FLOOR}，实际 ${baseCode[1]}）`);
}

// CI 覆盖必须存在：没有它，测试版工作流传的 -P 会被静默忽略，
// 发出去的每个 beta 都自称正式版号，而 App 判成「不更新」
ok(/dshVersionOverride\("dshVersionName"\)/.test(gradle), "版本名可被 -PdshVersionName 覆盖");
ok(/dshVersionOverride\("dshVersionCode"\)/.test(gradle), "版本号可被 -PdshVersionCode 覆盖");
ok(/providers\.gradleProperty/.test(gradle),
  "覆盖走 providers.gradleProperty（findProperty 会让配置缓存失效）");

// ── 2. 更新说明的内容 ──
console.log("\n── 更新说明 ──");
for (const [label, xml] of [["values", stringsEn], ["values-zh-rCN", stringsZh]]) {
  const arr = xml.match(/<string-array name="changelog_items">([\s\S]*?)<\/string-array>/);
  ok(arr !== null, `${label} 里有 changelog_items`);
  if (arr) {
    const items = [...arr[1].matchAll(/<item>([\s\S]*?)<\/item>/g)].map((m) => m[1].trim());
    ok(items.length > 0, `${label} 的更新条目非空（${items.length} 条）`);
    ok(items.every((x) => x.length > 0), `${label} 没有空条目`);
  }
  ok(/name="changelog_title"/.test(xml), `${label} 里有 changelog_title`);
  ok(/name="changelog_got_it"/.test(xml), `${label} 里有 changelog_got_it`);
}
// 两个语言的条目数必须一样：少一条不会报错，只是那一条对某个语言的用户消失了
{
  const n = (xml) => {
    const a = xml.match(/<string-array name="changelog_items">([\s\S]*?)<\/string-array>/);
    return a ? [...a[1].matchAll(/<item>/g)].length : -1;
  };
  ok(n(stringsEn) === n(stringsZh),
    `两个语言的更新条目数一致（${n(stringsEn)} / ${n(stringsZh)}）`);
}

// ── 3. 显示时机 ──
console.log("\n── 显示时机 ──");
{
  const body = fnBody(changelogKt, "fun shouldShow(") || "";
  ok(body.length > 0, "Changelog.shouldShow 存在");
  // 查的是**条件本身**，不是形参名：形参删不掉，条件才是会被删的那个
  ok(/welcomeShown &&/.test(body),
    "shouldShow 把「首启引导已看过」作为前提（新装的人不该看到「本次更新」）");
  ok(/VERSION == currentCoreVersion\(\) &&/.test(body),
    "版本不符时不显示（宁可不说，也不能把旧内容配新版本号）");
  ok(/shownFor != VERSION/.test(body), "同一版本只弹一次");
}
ok(/substringBefore\('-'\)/.test(changelogKt),
  "比较用主版本号（测试版是 1.8.1-beta.7，同批共用一份说明）");
ok(/showWelcomeGuide/.test(home) && /else if \(showChangelog\)/.test(home),
  "首启引导与更新说明互斥（两个对话框叠在一起会互相盖住按钮）");
ok(new RegExp("putString\\(Changelog\\.KEY_SHOWN_FOR").test(home),
  "关掉之后记下已弹过的版本");
// 引导那条路径也要记：不然刚装完的人关掉引导，下一秒又看到「本次更新」
{
  const welcomeBlock = home.slice(
    home.indexOf("if (showWelcomeGuide) {"),
    home.indexOf("} else if (showChangelog) {")
  );
  ok(/KEY_SHOWN_FOR/.test(welcomeBlock),
    "看完首启引导也记成「更新说明已弹过」（否则关掉引导立刻又弹一个）");
}

// ── 3b. 10.8 的生日彩蛋 ──
//
// 这个彩蛋的判据**一年只跑得到一次**：写错了（记成布尔 → 一辈子只弹一次；或按 UTC 判 →
// 某些时区差一天）要等到明年 10.8 才有人发现。所以这里把月份、日期、年份比较、prefs 键、
// 以及它在互斥分支里的位置全部钉住。
console.log("\n── 生日彩蛋（10.8） ──");
{
  const egg = read("app/src/main/java/me/bmax/apatch/util/BirthdayEgg.kt");
  const home2 = read("app/src/main/java/me/bmax/apatch/ui/screen/Home.kt");
  const dshEn = read("app/src/main/res/values/dsh_strings.xml");
  const dshZh = read("app/src/main/res/values-zh-rCN/dsh_strings.xml");
  ok(/const val MONTH = 10/.test(egg) && /const val DAY = 8/.test(egg),
    "生日就写在 BirthdayEgg 里：10 月 8 日（不散到界面里）");
  ok(/today\.monthValue == MONTH && today\.dayOfMonth == DAY/.test(egg),
    "isBirthday 判的正是这两位");
  ok(/fun shouldShow\(ctx: Context, today: LocalDate = LocalDate\.now\(\)\)/.test(egg),
    "shouldShow 的「今天」可以注入（否则这条判据根本没法验）");
  ok(/isBirthday\(today\) && prefs\(\)\.getInt\(KEY_SHOWN_YEAR, 0\) != today\.year/.test(egg),
    "该弹 = 今天是 10.8 **且今年还没弹过**");
  ok(/putInt\(KEY_SHOWN_YEAR, today\.year\)/.test(egg),
    "弹过记的是年份（记成布尔 → 这辈子只弹一次）");
  ok(/KEY_SHOWN_YEAR = "birthday_egg_shown_year"/.test(egg) &&
    (egg.match(/birthday_egg_shown_year/g) || []).length === 1,
    "prefs 键只有一处字面量（第二处写歪了就是同一个彩蛋弹两次或永不弹）");
  ok(/import java\.time\.LocalDate/.test(egg) && /LocalDate\.now\(\)/.test(egg),
    "用设备本地日期（按 UTC 判会在某些时区差一天）");
  const minSdk = gradle.match(/androidMinSdkVersion by extra\((\d+)\)/);
  ok(minSdk !== null && Number(minSdk[1]) >= 26,
    `java.time 要 API 26（minSdk 现在 ${minSdk ? minSdk[1] : "?"}）—— 低于它会在老机器上 NoClassDefFoundError`);
  // 互斥分支的顺序：彩蛋最不重要，排最后
  const birthdayAt = home2.indexOf("else if (showBirthday)");
  const policyAt = home2.indexOf("else if (showPolicyNotice)");
  const changeAt2 = home2.indexOf("else if (showChangelog)");
  ok(birthdayAt > 0 && policyAt > 0 && changeAt2 > 0 && policyAt > changeAt2 && birthdayAt > policyAt,
    "彩蛋排在整串互斥分支的**最后**（引导 → 更新说明 → 策略变更 → 彩蛋）");
  {
    const block = home2.slice(birthdayAt, home2.indexOf("ProvideDshHomeState"));
    // 两条关闭路径都走同一个 dismiss：标记与关闭只有一处，少接一处就会同一天反复弹
    ok(/val dismissBirthday: \(\) -> Unit = \{[\s\S]{0,120}BirthdayEgg\.markShown\(\)/.test(home2) &&
      (block.match(/dismissBirthday\(\)/g) || []).length >= 2,
      "点按钮和点外面都走同一个 dismiss（且那个 dismiss 里真的记了「已弹过」）");
  }
  ok(/mutableStateOf\(BirthdayEgg\.shouldShow\(homeContext\)\)/.test(home2),
    "进首页判一次就够（日期在一次 composition 里不会变）");
  for (const [label, xml] of [["values", dshEn], ["values-zh-rCN", dshZh]]) {
    for (const k of ["dsh_birthday_title", "dsh_birthday_text", "dsh_birthday_ok"]) {
      ok(xml.includes(`name="${k}"`), `${label} 有 ${k}`);
    }
  }
  // 彩蛋不进更新说明 —— 提前写出来就不叫彩蛋了
  ok(!/生日|birthday/i.test(stringsEn) && !/生日|birthday/i.test(stringsZh),
    "changelog_items 里不提彩蛋（更新说明是给人看的清单，彩蛋是惊喜）");
  const rd = read("docs/dev-notes.md");
  const rdEn = read("docs/dev-notes.en.md");
  ok(/BirthdayEgg/.test(rd) && /10 月 8 日/.test(rd), "dev-notes 记了彩蛋与其判据（中文）");
  ok(/BirthdayEgg/.test(rdEn) && /October 8/.test(rdEn), "dev-notes.en 也记了（英文）");
}

// ── 4. 复用的是同一个壳 ──
console.log("\n── 对话框复用 ──");
ok(/fun PagedInfoDialog\(/.test(dialog), "有公用的 PagedInfoDialog");
for (const name of ["WelcomeGuideDialog", "ChangelogDialog"]) {
  const body = fnBody(dialog, `fun ${name}(`);
  ok(body !== null, `${name} 存在`);
  if (body) {
    ok(/PagedInfoDialog\(/.test(body), `${name} 走的是 PagedInfoDialog（不是另做一套壳）`);
  }
}
// 首启引导必须**不可**随手关掉：它是一道门，关掉就再也不出现
{
  const body = fnBody(dialog, "fun WelcomeGuideDialog(") || "";
  ok(/dismissible = false/.test(body), "首启引导不可点外面/返回键关掉");
}

// ── 5. 测试版通道 ──
console.log("\n── 测试版通道 ──");
ok(/KEY_ACCEPT_BETA = "([a-z_]+)"/.test(checker), "UpdateChecker 里有 KEY_ACCEPT_BETA 常量");
{
  // 键必须只有一处字面量：开关写一个键、检查读另一个键，界面看起来完全正常
  const key = checker.match(/KEY_ACCEPT_BETA = "([a-z_]+)"/);
  if (key) {
    const settings = code(read("app/src/main/java/me/bmax/apatch/ui/screen/settings/GeneralSettings.kt"));
    const main = code(read("app/src/main/java/me/bmax/apatch/ui/MainActivity.kt"));
    for (const [label, src] of [["GeneralSettings", settings], ["MainActivity", main]]) {
      ok(src.includes("KEY_ACCEPT_BETA") && !src.includes(`"${key[1]}"`),
        `${label} 用常量而不是重写字面量 "${key[1]}"`);
    }
  }
}
ok(/suspend fun check\(acceptBeta: Boolean = false\)/.test(checker),
  "check() 的 acceptBeta 默认 false（这条通道必须由用户明确打开）");
ok(/if \(acceptBeta\) listOf\(LIST_PATH, LATEST_PATH\)/.test(checker),
  "开着测试版时先查列表：releases/latest 定义上跳过 prerelease，先问它会让开关失效");
ok(/private fun isBeta\(/.test(checker), "有 isBeta 判据");
{
  const at = checker.indexOf("private fun isBeta(");
  const body = checker.slice(at, at + 400);
  ok(/optBoolean\("prerelease"\)/.test(body) && /substringAfter\('-'/.test(body),
    "isBeta 两道判断都在（prerelease 标记 + tag 的预发布后缀）");
}
ok(/if \(!acceptBeta && isBeta\(/.test(checker), "不接受测试版时把它排除掉");
ok((checker.match(/if \(!acceptBeta && isBeta\(/g) || []).length >= 2,
  "列表与 latest 两条路径都过滤（只过滤一条 = 另一条把测试版推给所有人）");
ok(/val isPrerelease: Boolean = false/.test(checker),
  "Status 带 isPrerelease，界面才能把测试版标出来");
{
  const ud = code(read("app/src/main/java/me/bmax/apatch/ui/component/UpdateDialog.kt"));
  ok(/isPrerelease/.test(ud), "更新对话框读 isPrerelease");
  ok(/update_beta_badge/.test(ud) && /update_beta_warning/.test(ud),
    "对话框上有测试版标记与提醒（两种提示长得一样，风险却不同）");
}
// 列表条数：每个 beta 一个 tag，10 条很快全是测试版
{
  const per = checker.match(/releases\?per_page=(\d+)/);
  ok(per !== null && Number(per[1]) >= 30,
    `列表取够条数（当前 ${per ? per[1] : "?"}，每个 beta 占一条，太少会让正式版通道找不到正式版）`);
}

// ── 6. beta 工作流 ──
console.log("\n── beta 工作流 ──");
ok(/--prerelease/.test(betaYml), "发的是 prerelease（这是 App 侧过滤的依据）");
ok(/assembleRelease/.test(betaYml),
  "用 release 变体：debug 变体是独立包名 + debug 签名，装上去不是升级而是多一个图标");
ok(/-PdshVersionName=/.test(betaYml) && /-PdshVersionCode=/.test(betaYml),
  "把版本传给 Gradle");
ok(/beta\.\$\{GITHUB_RUN_NUMBER\}|beta\.\$\{\{ github\.run_number \}\}/.test(betaYml),
  "版本名带 -beta.N（compareVersions 靠它排先后，且正式版 > 预发布版）");
ok(/KEYSTORE_BASE64/.test(betaYml) && /::error::missing release signing secrets/.test(betaYml),
  "缺签名材料就硬失败（用 debug key 签出的包用户装不上，报错只说「应用未安装」）");
ok(/CN=Android Debug/.test(betaYml), "签名自检：debug key 必须被拦住");
{
  const out = betaYml.match(/OUT="([^"]*)"/);
  ok(out !== null && out[1].includes("${abi}"),
    "产物名带 ABI（pickApkAsset 按文件名挑架构，没有 ABI 会被当成旧的单包 release）" +
      (out ? ` → ${out[1]}` : ""));
}
ok(/sha256sum/.test(betaYml),
  "带 .sha256（canInstallInApp 要求校验值，没有它只能退回浏览器）");
// tag 必须过得了 App 侧的正则
{
  const re = /^[vV]?\d+(\.\d+)+([-+].*)?$/;
  const sample = "v1.8.1-beta.7";
  ok(/tag=v\$NAME/.test(betaYml) && re.test(sample),
    `tag 形如 ${sample}，能过 UpdateChecker 的 VERSION_TAG（滚动 tag 会被直接忽略）`);
}

// ── 7. 工作流注入：自由文本输入只能走 env ──
//
// 这一条是**真炸过**的：`notes` 直接写进 run: 之后，GitHub 在跑脚本之前就把它展开进脚本
// 文本，于是说明里的反引号被当命令替换执行（日志里能看到 "git+ssh://…: No such file or
// directory" 与 "dsh: command not found"），展开后的 body 还超过 GitHub 的 125000 字符
// 上限，`gh release create` 回 422 —— 包已经构建、签名、验完，却发不出去。
//
// 规则：`workflow_dispatch` 里 type 缺省（GitHub 默认就是 string）或 `type: string` 的输入
// 是**自由文本**，只允许出现在 env: 映射里，绝不允许出现在 run: 正文里。
function workflowInputs(src) {
  const lines = src.split("\n");
  const at = lines.findIndex((l) => /^\s*workflow_dispatch:\s*$/.test(l));
  if (at < 0) return [];
  const inAt = lines.findIndex((l, i) => i > at && /^\s*inputs:\s*$/.test(l));
  if (inAt < 0) return [];
  const base = lines[inAt].match(/^(\s*)/)[1].length;
  const out = [];
  for (let i = inAt + 1; i < lines.length; i++) {
    const l = lines[i];
    if (l.trim() === "") continue;
    const ind = l.match(/^(\s*)/)[1].length;
    if (ind <= base) break;
    const m = l.match(/^\s*([A-Za-z_][\w-]*):\s*$/);
    if (!m || ind !== base + 2) continue;
    let block = "";
    for (let j = i + 1; j < lines.length; j++) {
      const l2 = lines[j];
      if (l2.trim() === "") continue;
      if (l2.match(/^(\s*)/)[1].length <= ind) break;
      block += l2 + "\n";
    }
    const ty = block.match(/^\s*type:\s*(\w+)\s*$/m);
    out.push({ name: m[1], type: ty ? ty[1] : "string" });
  }
  return out;
}

/** `run:` 块的正文行号（`run: |` 缩进之下、缩进回到同级之前的那些行）。 */
function runBodyLines(src) {
  const lines = src.split("\n");
  const inBody = new Set();
  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(/^(\s*)run:\s*[|>]-?\s*$/);
    if (!m) continue;
    const indent = m[1].length;
    for (let j = i + 1; j < lines.length; j++) {
      const l = lines[j];
      if (l.trim() === "") continue;
      if (l.match(/^(\s*)/)[1].length <= indent) break;
      inBody.add(j);
    }
  }
  return inBody;
}

console.log("\n── 工作流注入（自由文本输入必须走 env） ──");
{
  const WF = [".github/workflows/beta.yml", ".github/workflows/runtime.yml", ".github/workflows/build.yml"];
  /** 当前哪些输入是自由文本：写出来是为了「新增一个 string 输入」时逼人回来审这条规则。 */
  const EXPECT = {
    ".github/workflows/beta.yml": ["notes", "target_version"],
    ".github/workflows/runtime.yml": ["dsh_version", "min_app_version", "node_version", "release_tag"],
    ".github/workflows/build.yml": [],
  };
  for (const wf of WF) {
    const raw = read(wf);
    const free = workflowInputs(raw).filter((i) => i.type === "string").map((i) => i.name).sort();
    ok(free.join(",") === EXPECT[wf].join(","),
      `${wf} 的自由文本输入清单没变（${free.join(", ") || "无"}）—— 变了就得重看下面两条`);
    const lines = raw.split("\n");
    const inBody = runBodyLines(raw);
    const inRun = [...inBody].map((i) => lines[i]).join("\n");
    for (const n of EXPECT[wf]) {
      ok(!new RegExp("\\$\\{\\{\\s*inputs\\." + n + "\\s*\\}\\}").test(inRun),
        `${wf}: inputs.${n} 不出现在 run: 正文里（展开进脚本 = 允许注入命令）`);
      // 交给 YAML 层（env:/with: 的映射值）就行：那里是原样传递，不过 shell。
      // 不要求"值恰好是 ${{ inputs.x }}"——runtime.yml 的 release_tag 是带默认值的表达式。
      const handed = lines.some((l, i) =>
        !inBody.has(i) &&
        new RegExp("^\\s*[A-Za-z_][\\w.-]*:.*\\$\\{\\{\\s*inputs\\." + n + "\\b").test(l));
      ok(handed, `${wf}: inputs.${n} 通过 YAML 映射（env:/with:）传进去，而不是拼进脚本`);
    }
  }
}

// ── 8. README ──
//
// 这三条是最容易「文档说 A、代码做 B」的地方：测试版用哪个变体、artifact 为什么不能用、
// 更新说明为什么是本地资源。读者按 README 去改代码时，错的文档比没有文档更贵。
console.log("\n── README ──");
{
  const rd = fs.readFileSync(path.join(ROOT, "docs/dev-notes.md"), "utf8");
  ok(/接受测试版更新/.test(rd), "README 写了那个开关的位置");
  ok(/release 变体/.test(rd), "README 说明测试版为什么不用 debug 包");
  ok(/401/.test(rd), "README 记下 artifact 下载要认证这个事实（否则下一个人会再试一次）");
  ok(/PagedInfoDialog/.test(rd), "README 说明两个对话框共用一个壳");
  ok(/changelog_items/.test(rd), "README 指出更新说明的内容在哪");
}

console.log(fail === 0 ? "\n全部通过" : `\n${fail} 项失败`);
process.exit(fail === 0 ? 0 : 1);
