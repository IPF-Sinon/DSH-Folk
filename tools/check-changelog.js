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
  const expect = parts.length === 3
    ? parts[0] * 10000 + parts[1] * 100 + parts[2]
    : null;
  ok(expect !== null && expect === Number(baseCode[1]),
    `baseVersionCode() 与版本名对应（${baseName[1]} → 期望 ${expect}，实际 ${baseCode[1]}）`);
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

// ── 7. README ──
//
// 这三条是最容易「文档说 A、代码做 B」的地方：测试版用哪个变体、artifact 为什么不能用、
// 更新说明为什么是本地资源。读者按 README 去改代码时，错的文档比没有文档更贵。
console.log("\n── README ──");
{
  const rd = fs.readFileSync(path.join(ROOT, "README.md"), "utf8");
  ok(/接受测试版更新/.test(rd), "README 写了那个开关的位置");
  ok(/release 变体/.test(rd), "README 说明测试版为什么不用 debug 包");
  ok(/401/.test(rd), "README 记下 artifact 下载要认证这个事实（否则下一个人会再试一次）");
  ok(/PagedInfoDialog/.test(rd), "README 说明两个对话框共用一个壳");
  ok(/changelog_items/.test(rd), "README 指出更新说明的内容在哪");
}

console.log(fail === 0 ? "\n全部通过" : `\n${fail} 项失败`);
process.exit(fail === 0 ? 0 : 1);
