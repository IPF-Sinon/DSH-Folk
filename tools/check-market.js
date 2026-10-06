#!/usr/bin/env node
/**
 * 脚本市场（GreasyFork）的门禁。
 *
 * ## 为什么要钉死这些形状
 *
 * 这个市场是**照着实测的 API 形状**写的（`curl` 摸过每一个字段、每一种外壳）。它最大的
 * 风险不是"连不上"，而是"照着想当然的 API 改一行"：
 *
 * - `greasyfork.org/…/scripts.json` 每个都是 **308** 跳转，`HttpURLConnection` 对 308
 *   的支持随版本而变 → 必须直连 `api.greasyfork.org`；
 * - 响应有两种外壳：`{"query":[…]}` 与翻过 2000 条窗口时的**裸 `[]`**；
 * - 字段名**不是** `author` / `installs` / `code_url_ssl`（这三个都是"想当然"），而是
 *   `users[0].name` / `total_installs` / `code_url`；
 * - locale 走 URL **路径**，写错整页 406。
 *
 * 另外两条是安全/可用性判据：安装地址必须是 **https + greasyfork 的域**（`code_url`
 * 来自网络响应，被换掉就等于把任意 URL 拉进来）；正文必须含 `==UserScript==`（服务端
 * 出错会回 HTML，装进去只是一段跑不起来的 JS）。
 */
const fs = require("fs");

const ROOT = __dirname + "/..";
const MARKET = fs.readFileSync(
  require("path").join(ROOT, "app/src/main/java/me/bmax/apatch/dsh/ScriptMarket.kt"),
  "utf8",
);
const SCREEN = fs.readFileSync(
  require("path").join(ROOT, "app/src/main/java/me/bmax/apatch/ui/screen/settings/UserscriptsScreen.kt"),
  "utf8",
);

let n = 0;
let bad = 0;
function ok(cond, label) {
  n++;
  console.log("  " + (cond ? "✓" : "✗") + " " + label);
  if (!cond) bad++;
}

console.log("\n── 端点与外壳 ──");
{
  ok(/const val API = "https:\/\/api\.greasyfork\.org"/.test(MARKET),
    "直连 api.greasyfork.org（greasyfork.org 的每个 .json 都是 308）");
  // 注意别写成 /greasyfork\.org\//：API 基址末尾**没有**斜杠（路径是拼上去的），
  // 要求斜杠的话这行永远绿，"把基址改回 greasyfork.org" 这个错就漏了。
  ok(!/https:\/\/greasyfork\.org[\/"]/.test(MARKET),
    "不再出现 greasyfork.org 的 API 基址（安装地址来自响应的 code_url）");
  ok(/scripts\.json\?q=/.test(MARKET) && /per_page=\$PER_PAGE/.test(MARKET),
    "搜索带 q 与 per_page（默认 100/上限 200，我们只要一页）");
  ok(/internal const val PER_PAGE = 20/.test(MARKET) && /ScriptMarket\.PER_PAGE/.test(SCREEN),
    "页大小是一个常量，界面判断「还有下一页」用的是同一个（两处写死必然写歪）");
  ok(/is JSONObject -> root\.optJSONArray\("query"\)/.test(MARKET) &&
    /is JSONArray -> root/.test(MARKET),
    "两种外壳都认：对象里的 query，以及裸数组（翻过 2000 条窗口时）");
  ok(/else -> throw MarketException\(Fail\.CONTENT\)/.test(MARKET),
    "两种都不是 → CONTENT 失败（不是静默空列表：空列表会被当成「没有结果」）");
}

console.log("\n── 字段名：实测出来的那三个 ──");
{
  ok(/optString\("code_url"\)/.test(MARKET), "安装地址读 code_url");
  ok(/optLong\("total_installs"\)/.test(MARKET), "安装量读 total_installs");
  ok(/optJSONArray\("users"\)/.test(MARKET), "作者读 users[0].name（API 没有 author 字段）");
  for (const ghost of ["author", "installs", "code_url_ssl", "slug", "updated_at"]) {
    ok(!new RegExp('opt(String|Long|JSONArray|JSONObject)\\("' + ghost + '"').test(MARKET),
      `没有读不存在的字段 ${ghost}`);
  }
  ok(/code\.isBlank\(\)/.test(MARKET) && /continue/.test(MARKET),
    "缺 code_url 的条目跳过（点了「安装」也没东西可拉）");
}

console.log("\n── locale：走路径，写错 406 ──");
{
  ok(/if \(lang !in LOCALES\) return "en"/.test(MARKET),
    "不认得的语言一律 en（写错 locale 会让整页 406，比回退成英文糟得多）");
  ok(/"zh" -> "zh-CN"/.test(MARKET) && /"pt" -> "pt-BR"/.test(MARKET),
    "zh/pt 用带地区的写法（GreasyFork 认的正是这两个）");
  ok(/configuration\.locales\[0\]\.language/.test(MARKET),
    "语言取自当前 resources 的配置（跟随应用内语言），不是 JVM 默认");
}

console.log("\n── 安装地址与正文：两条硬约束 ──");
{
  ok(/url\.protocol != "https"/.test(MARKET), "只收 https");
  ok(/host\.endsWith\("\.greasyfork\.org"\)/.test(MARKET) && /host == "greasyfork\.org"/.test(MARKET),
    "只收 greasyfork 的域（code_url 来自网络响应，别处就等于拉任意 URL）");
  ok(/body\.contains\("==UserScript=="\)/.test(MARKET),
    "正文必须含 ==UserScript==（服务端出错时回的是 HTML/JSON）");
}

console.log("\n── 别把页面按住 ──");
{
  ok(/connectTimeout = 8_000/.test(MARKET) && /readTimeout = 20_000/.test(MARKET),
    "超时短（市场是页面上的一块，不是一屏）");
  ok(/MAX_BYTES = 2 \* 1024 \* 1024/.test(MARKET) && /MarketException\(Fail\.SIZE\)/.test(MARKET),
    "响应有硬上限，超了报 SIZE（截断的 JS 比报错更难查）");
  ok(/disconnect\(\)/.test(MARKET), "读完就断开");
  ok(/BuildConfig\.VERSION_NAME/.test(MARKET), "给一个可辨识的 UA（GreasyFork 只要求 Crawl-delay: 1）");
}

console.log("\n── 接线：状态在原生侧，装完回到同一套列表 ──");
{
  ok(/import me\.bmax\.apatch\.dsh\.ScriptMarket/.test(SCREEN) &&
    /ScriptMarket\.search\(context, marketQuery\.trim\(\), next\)/.test(SCREEN),
    "界面调 search（原生 HTTP —— WebView 坏掉时这一页还能用）");
  ok(/Userscripts\.install\(context, ScriptMarket\.fetch\(context, hit\.codeUrl\)\)/.test(SCREEN),
    "装的就是 fetch 回来的正文，走既有 install（同名覆盖/落盘都由它管）");
  ok(/withContext\(Dispatchers\.IO\)/.test(SCREEN),
    "网络与落盘都在 IO 线程（首屏不该被一次搜索卡住）");
  ok(/reload\(\)/.test(SCREEN) && /scripts\.any \{ it\.title == hit\.name \}/.test(SCREEN),
    "装完刷新列表，并标出已装的（同一个名字就是同一条）");
  ok(/script/.test(MARKET) && !/WebView/.test(MARKET),
    "市场不碰 WebView（这一页的全部意义就是坏脚本时还能进来）");
}

console.log(bad === 0 ? `\n全部通过（${n} 项断言）` : `\n${bad}/${n} 项失败`);
process.exit(bad === 0 ? 0 : 1);
