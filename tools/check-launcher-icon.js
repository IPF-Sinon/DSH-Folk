// 关于页的图标必须**就是**主屏那个图标。
//
// 这条例子的现实来源：关于页原来放的是 `drawable/about.png` —— 一张独立的 1.17 MB 旧 logo，
// 图标改版（换成带虎鲸与文字的圆角徽章）时它不会跟着变，于是"软件里"和"软件图标"长期不是
// 同一个设计，而且要用户截图对比才看得出来。判据不是"关于页有一张图"，而是"关于页引用的
// 资源与 adaptive-icon 的 foreground 是同一份"：这样只要主屏图标是对的，关于页就不可能错。
const fs = require("fs");
let bad = 0;
let n = 0;
const ok = (c, msg) => { n++; console.log((c ? "  ✓ " : "  ✗ ") + msg); if (!c) bad++; };
const read = (p) => fs.readFileSync(p, "utf8");
const exists = (p) => fs.existsSync(p);

const UTILS = "app/src/main/java/me/bmax/apatch/util/LauncherIconUtils.kt";
const ABOUT = "app/src/main/java/me/bmax/apatch/ui/screen/AboutScreen.kt";
const GENERAL = "app/src/main/java/me/bmax/apatch/ui/screen/settings/GeneralSettings.kt";
const MANIFEST = "app/src/main/AndroidManifest.xml";
const RES = "app/src/main/res/";

const utils = read(UTILS);
const about = read(ABOUT);
const general = read(GENERAL);
const manifest = read(MANIFEST);

console.log("\n── 关于页引用的资源 = 启动器图标的 foreground ──");
const mainAdaptive = read(RES + "mipmap-anydpi-v26/ic_launcher.xml");
const altAdaptive = read(RES + "mipmap-anydpi-v26/ic_launcher_alt.xml");
const fgOf = (xml) => (xml.match(/<foreground android:drawable="@mipmap\/([a-z0-9_]+)"/) || [])[1] || "";
const mainFg = fgOf(mainAdaptive);
const altFg = fgOf(altAdaptive);
ok(mainFg === "ic_launcher_foreground", `主图标的前景是 ic_launcher_foreground（实际 ${mainFg || "?"}）`);
ok(altFg === "ic_launcher_alt_foreground", `备用图标的前景是 ic_launcher_alt_foreground（实际 ${altFg || "?"}）`);

// currentIconForeground() 里必须正好写着这两个资源名，且由 usesAltIcon() 分派
const fn = (utils.match(/fun currentIconForeground\(\): Int =\n([\s\S]{0,200}?)\n\n/) || [])[1] || "";
ok(/R\.mipmap\.ic_launcher_foreground/.test(fn) && /R\.mipmap\.ic_launcher_alt_foreground/.test(fn),
  "currentIconForeground() 用的正是这两份前景位图");
ok(/if \(usesAltIcon\(\)\)\s*R\.mipmap\.ic_launcher_alt_foreground\s*else\s*R\.mipmap\.ic_launcher_foreground/.test(fn),
  "备用图标开关决定选哪一份（开着 → alt）");
ok(/@DrawableRes\s*\n\s*fun currentIconForeground/.test(utils),
  "标注 @DrawableRes（传错资源类型时编译期就报）");

console.log("\n── 关于页不再有自己的一张图 ──");
ok(/painterResource\(id = LauncherIconUtils\.currentIconForeground\(\)\)/.test(about),
  "关于页的 painter 走 LauncherIconUtils.currentIconForeground()");
ok(!/R\.drawable\.about/.test(about) && !exists(RES + "drawable/about.png"),
  "旧的一次性素材 drawable/about.png 已删、也不再被引用");
// 直接引 mipmap/ic_launcher 会在 API 26+ 拿到 adaptive-icon XML，painterResource 画不了它
ok(!/R\.mipmap\.ic_launcher(_alt)?\b/.test(about),
  "不直接引 R.mipmap.ic_launcher[_alt]（API 26+ 那是 adaptive-icon XML，painterResource 会抛）");
ok(!/about_icon_background/.test(read(RES + "values/colors.xml")) &&
   !/about_icon_background/.test(about),
  "只剩它用的 about_icon_background 颜色也清掉了");
ok(/contentDescription = stringResource\(R\.string\.app_name\)/.test(about),
  "图标仍有 contentDescription（读屏要能念出这是什么）");

console.log("\n── 开关只有一份字面量 ──");
const lits = [...utils.matchAll(/"use_alt_icon"/g)].length + [...general.matchAll(/"use_alt_icon"/g)].length +
  [...about.matchAll(/"use_alt_icon"/g)].length;
ok(/const val KEY_USE_ALT_ICON = "use_alt_icon"/.test(utils) && lits === 1,
  `"use_alt_icon" 只在常量里出现一次（实际 ${lits} 处）—— 两处各写一份就是"改了一个另一个不跟"`);
ok(/prefs\.getBoolean\(KEY_USE_ALT_ICON, false\)/.test(utils),
  "usesAltIcon / updateLauncherState 都读这个常量");
ok(/prefs\.getBoolean\(LauncherIconUtils\.KEY_USE_ALT_ICON, false\)/.test(general) &&
   /putBoolean\(LauncherIconUtils\.KEY_USE_ALT_ICON, it\)/.test(general),
  "设置页的读写也用同一个常量");

console.log("\n── 资源本身齐全 ──");
for (const d of ["mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"]) {
  ok(exists(`${RES}mipmap-${d}/ic_launcher_foreground.png`) && exists(`${RES}mipmap-${d}/ic_launcher_alt_foreground.png`),
    `mipmap-${d} 两份前景位图都在（缺一档会退回别的密度、显示模糊）`);
}

console.log("\n── 清单里的别名用同一对图标 ──");
{
  const aliases = [...manifest.matchAll(/<activity-alias[\s\S]*?<\/activity-alias>/g)].map((m) => m[0]);
  const byName = {};
  for (const a of aliases) {
    const name = (a.match(/android:name="\.ui\.([A-Za-z]+)"/) || [])[1];
    const icon = (a.match(/android:icon="@mipmap\/([a-z0-9_]+)"/) || [])[1];
    if (name) byName[name] = icon;
    }
  ok(byName.MainActivityDefault === "ic_launcher" && byName.MainActivityAlias === "ic_launcher_alt",
    `默认别名用主图标、Alias 别名用备用图标（实际 ${byName.MainActivityDefault} / ${byName.MainActivityAlias}）`);
  ok(byName.MainActivityAliasSu === "ic_launcher" && byName.MainActivityAliasAltSu === "ic_launcher_alt",
    "短名那两个别名同样按 主/备用 这一对分（关于页跟着的就是这一对）");
}

console.log("\n── 文档 ──");
{
  const notes = read("docs/dev-notes.md");
  const notesEn = read("docs/dev-notes.en.md");
  ok(/check-launcher-icon\.js/.test(notes) && /currentIconForeground/.test(notes) && /use_alt_icon/.test(notes),
    "dev-notes 记了「关于页 = 启动器 foreground」和那个键（中文）");
  ok(/check-launcher-icon\.js/.test(notesEn) && /currentIconForeground/.test(notesEn),
    "dev-notes.en 也记了（英文）");
}

console.log("\n" + (bad === 0 ? "✓ 全部通过" : "✗ 有失败") + "：" + n + " 项断言，" + bad + " 项失败");
process.exit(bad === 0 ? 0 : 1);
