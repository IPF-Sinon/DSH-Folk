#!/usr/bin/env node
/**
 * 「无障碍别看本应用」档位的门禁（off / agent / all，默认 agent）。
 *
 * ## 为什么钉死这些形状
 *
 * 这个开关的承诺是**否定式的**（"读不到、也操作不了"），而否定式承诺最容易悄悄失效：
 *
 * - 只在 `pickRoot` 里挡、忘了 `searchRoots` → `tree` 干净了，`--target` 照样点得到自家节点；
 * - 挡了窗口、忘了 `setText` 那条**全局** `findFocus`（它不走 searchRoots）→ 焦点在自家框里时
 *   还是写进去；
 * - 挡到一半又"读不到就退回自家树" → 开关装着，行为一点没变；
 * - 三档只实现两档、或 all 档的视图级隐藏忘了在某一个窗上落（主界面/WebUI/悬浮窗）→
 *   "所有无障碍服务"这句话只对一半的窗成立。
 *
 * 另外两条是用户明确要求的：默认档必须是 agent（[A11yOwn.MODE_AGENT]），提示词必须在
 * 开着时**明说**本应用读不到（只有常驻说明不够，见 check-host-prompt）。
 */
const fs = require("fs");
const path = require("path");

const ROOT = path.join(__dirname, "..");
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), "utf8");

const OWN = read("app/src/main/java/me/bmax/apatch/dsh/A11yOwn.kt");
const A11Y = read("app/src/main/java/me/bmax/apatch/dsh/DshA11y.kt");
const ENV = read("app/src/main/java/me/bmax/apatch/dsh/DshEnv.kt");
const CAPS = read("app/src/main/java/me/bmax/apatch/ui/screen/settings/PermissionCapsScreens.kt");
const HUB = read("app/src/main/java/me/bmax/apatch/ui/screen/settings/PermissionHubScreen.kt");
const MAIN = read("app/src/main/java/me/bmax/apatch/ui/MainActivity.kt");
const WEBUI = read("app/src/main/java/me/bmax/apatch/ui/DshWebUiActivity.kt");
const MIRROR = read("app/src/main/java/me/bmax/apatch/dsh/DisplayMirror.kt");
const PROMPT = read("app/src/main/java/me/bmax/apatch/dsh/DshHostPrompt.kt");
const MJS = read("app/src/main/assets/dsh-folk-host.mjs");
const STR_EN = read("app/src/main/res/values/dsh_strings.xml");
const STR_ZH = read("app/src/main/res/values-zh-rCN/dsh_strings.xml");
const NOTES = read("docs/dev-notes.md");
const NOTES_EN = read("docs/dev-notes.en.md");

let n = 0;
let bad = 0;
function ok(cond, label) {
  n++;
  console.log("  " + (cond ? "✓" : "✗") + " " + label);
  if (!cond) bad++;
}

console.log("\n── 三档与默认 ──");
{
  ok(/internal object A11yOwn \{/.test(OWN), "A11yOwn 在（而不是把档位散在各调用点）");
  ok(/const val MODE_OFF = "off"/.test(OWN) &&
     /const val MODE_AGENT = "agent"/.test(OWN) &&
     /const val MODE_ALL = "all"/.test(OWN),
    "三个档位常量都在");
  ok(/val MODES = listOf\(MODE_OFF, MODE_AGENT, MODE_ALL\)/.test(OWN),
    "MODES 按「拦得越来越多」列出全部三档（界面按它渲染，不许再写一份 when）");
  ok(/getString\(DshEnv\.KEY_A11Y_OWN, MODE_AGENT\)/.test(OWN),
    "默认档是 agent（用户要求：默认开）");
  ok(/\?\.takeIf \{ it in MODES \}\s*\n?\s*\?: MODE_AGENT/.test(OWN),
    "prefs 里是脏值时按默认档算（旧版本/手改过的键不许变成「第四档」）");
  ok(/fun hidesAgent\(ctx: Context\): Boolean = mode\(ctx\) != MODE_OFF/.test(OWN),
    "hidesAgent = 除 off 之外都要挡通道");
  ok(/fun hidesViews\(ctx: Context\): Boolean = mode\(ctx\) == MODE_ALL/.test(OWN),
    "hidesViews 只在 all 档为真（agent 档不许动 View 层：那会连 TalkBack 一起挡）");
  ok(/const val KEY_A11Y_OWN = "a11y_hide_own"/.test(ENV),
    "prefs 键在 DshEnv 里（单一事实来源）");
}

console.log("\n── 视图级隐藏：只 all 档，且关得回去 ──");
{
  ok(/IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS/.test(OWN),
    "all 档用 noHideDescendants（整棵子树不进无障碍树）");
  ok(/IMPORTANT_FOR_ACCESSIBILITY_AUTO/.test(OWN),
    "其它档位恢复 AUTO —— 不恢复的话「关掉了却还是读不到」");
  ok(/fun applyToWindow\(window: Window\?\) \{\s*\n\s*window\?\.decorView\?\.let \{ applyToView\(it\) \}/.test(OWN),
    "applyToWindow 落在 decor 上（Activity 的整棵视图树）");
  ok(/fun applyToView\(view: View\?\)/.test(OWN),
    "applyToView 单独给出（悬浮窗不是 Activity 窗，只有一个 ComposeView）");
}

console.log("\n── 三个自家的窗都要落到 ──");
{
  ok(/override fun onResume\(\) \{[\s\S]{0,240}A11yOwn\.applyToWindow\(window\)/.test(MAIN),
    "主界面 onResume 落档位（改档位后回到这一页立刻生效）");
  ok(/override fun onResume\(\) \{[\s\S]{0,240}A11yOwn\.applyToWindow\(window\)/.test(WEBUI),
    "WebUI 页 onResume 落档位（agent 最常驱动的那一页）");
  ok(/A11yOwn\.applyToView\(cv\)[\s\S]{0,120}windowManager\.addView\(cv, p\)/.test(MIRROR),
    "悬浮小窗在 addView **之前**落档位（挂上去之后再设会漏一次）");
}

console.log("\n── 通道级：读、搜、写、报，四处都要挡 ──");
{
  ok(/private fun isOwnWindow\(svc: AccessibilityService, window: AccessibilityWindowInfo\): Boolean =\s*\n\s*window\.root\?\.packageName\?\.toString\(\) == svc\.packageName/.test(A11Y),
    "自家窗按**根上的包名**认（TYPE_APPLICATION 与自己的悬浮窗都算）");
  const pick = A11Y.slice(A11Y.indexOf("private fun pickRoot("), A11Y.indexOf("private fun isOwnOverlay"));
  ok(/val hideOwn = A11yOwn\.hidesAgent\(svc\)/.test(pick),
    "pickRoot 现查档位（不缓存：改档位立刻生效）");
  ok(/if \(active != null && !ownOverlayActive && !\(hideOwn && isOwnNode\(svc, active\)\)\)/.test(pick),
    "活动窗是自家的就不选它（挡住「活动窗优先」这条路）");
  ok(/\.filter \{ !hideOwn \|\| !isOwnWindow\(svc, it\) \}/.test(pick),
    "候选窗表也过滤自家窗（否则「别的窗」里还是自己的）");
  ok(/if \(hideOwn\) return other to ownOverlayActive/.test(pick),
    "只拦自家却不留退路：没有别的窗就报读不到（退回自家树 = 开关白装）");
  ok(/other \?: active \?: svc\.windows/.test(pick),
    "off 档保持老行为（没有别的可读窗口就退回活动窗）");
  const search = A11Y.slice(A11Y.indexOf("private fun searchRoots("), A11Y.indexOf("private fun findAll("));
  ok(/val hideOwn = A11yOwn\.hidesAgent\(svc\)/.test(search) &&
     /\.filter \{ !hideOwn \|\| !isOwnWindow\(svc, it\) \}/.test(search),
    "searchRoots 同样过滤（显式寻址 --target/--class/--text 不该点得到自家节点）");
  ok(/svc\.findFocus\(AccessibilityNodeInfo\.FOCUS_INPUT\)\s*\n\s*\?\.takeIf \{ allowed\(svc, it\) \}/.test(A11Y),
    "setText 那条**全局** findFocus 自己挡一次（它不走 searchRoots）");
  ok(/private fun allowed\(svc: AccessibilityService, node: AccessibilityNodeInfo\): Boolean =\s*\n\s*!A11yOwn\.hidesAgent\(svc\) \|\| !isOwnNode\(svc, node\)/.test(A11Y),
    "allowed() 判的是节点自己的包名");
}

console.log("\n── 判因字段：让 agent 知道「这是策略」而不是「坏了」 ──");
{
  ok(/\.put\("hideOwn", A11yOwn\.mode\(svc\)\)/.test(A11Y),
    "tree 成功时报 hideOwn（当前档位）");
  const failSeg = A11Y.slice(A11Y.indexOf("if (root == null)"), A11Y.indexOf("val counter = intArrayOf(0)"));
  ok(/\.put\("hideOwn", A11yOwn\.mode\(svc\)\)/.test(failSeg),
    "no_window 时也报 hideOwn（否则「只剩自家窗」会被读成锁屏/安全窗）");
  ok(/if \(filtered\) \{/.test(failSeg) && /relax that switch/.test(failSeg),
    "no_window 的 note 在过滤开着时说清是策略、并给出路（让用户放宽开关）");
  ok(/\.put\("own", own\)/.test(A11Y) &&
     /val own = root\?\.packageName\?\.toString\(\) == svc\.packageName/.test(A11Y),
    "窗口表每项带 own（「屏幕上只有我们自己的窗」与「什么窗都没有」要分得开）");
}

console.log("\n── 设置页：三档 + 免责声明 ──");
{
  ok(/var a11yOwn by remember \{ mutableStateOf\(A11yOwn\.mode\(context\)\) \}/.test(CAPS),
    "档位读进界面状态（进页面重读）");
  ok(/A11yOwn\.setMode\(context, mode\)/.test(CAPS), "改档位就落盘");
  ok(/A11yOwn\.applyToWindow\(\(context as\? Activity\)\?\.window\)/.test(CAPS),
    "改档位立刻作用到当前窗（否则「这一页现在算不算数」要靠猜）");
  ok(/if \(cap == DshNativeBridge\.Cap\.A11Y\) \{\s*\n\s*A11yOwnPicker\(mode = a11yOwnMode, onPick = onSetA11yOwn\)/.test(CAPS),
    "选择器只挂在无障碍那张卡片里（它管的就是这项能力读到的范围）");
  ok(/private fun A11yOwnPicker\(mode: String, onPick: \(String\) -> Unit\)/.test(CAPS) &&
     /stringResource\(a11yOwnLabelRes\(option\)\)/.test(CAPS),
    "三档直接铺开（不是弹层：免责声明是档位的限定词，藏起来等于藏了它）");
  ok(/A11yOwn\.MODE_OFF -> R\.string\.dsh_a11y_own_off[\s\S]{0,200}A11yOwn\.MODE_ALL -> R\.string\.dsh_a11y_own_all/.test(CAPS),
    "标签/说明都覆盖到 off 与 all 两档（不许只剩一个 else 兜底）");
  ok(/stringResource\(R\.string\.dsh_a11y_own_note\)/.test(CAPS),
    "卡片里带「不保证完全拦截」那行");
  ok(/Hit\("perm-a11y-own", a11yOwnTitle, a11yOwnSummary, HubTarget\.Group\(CapGroup\.SCREEN\)\)/.test(HUB),
    "权限页搜索能搜到它（并跳到它所在的那一组）");
}

console.log("\n── 字符串（两种语言都要） ──");
{
  for (const key of [
    "dsh_a11y_own_title",
    "dsh_a11y_own_summary",
    "dsh_a11y_own_off",
    "dsh_a11y_own_off_desc",
    "dsh_a11y_own_agent",
    "dsh_a11y_own_agent_desc",
    "dsh_a11y_own_all",
    "dsh_a11y_own_all_desc",
    "dsh_a11y_own_note",
  ]) {
    ok(STR_EN.includes('name="' + key + '"') && STR_ZH.includes('name="' + key + '"'),
      "两种语言都有 " + key);
  }
  ok(/不保证完全拦截/.test(STR_ZH), "中文提示明确写了「不保证完全拦截」（用户的原话）");
  ok(/not a guarantee/.test(STR_EN), "英文提示同样不承诺「已拦截」");
}

console.log("\n── 提示词：状态进事实、开着就明说 ──");
{
  ok(/\.put\("a11yHideOwn", A11yOwn\.mode\(ctx\)\)/.test(PROMPT),
    "host-facts 里带上当前档位（插件按 mtime 失效，改档位下一轮就生效）");
  ok(/private const val PLUGIN_REV = 17/.test(PROMPT),
    "插件内容版本 +1（改了 .mjs 必须抬，否则落盘的还是旧内容）");
  ok(/keep accessibility away from DSH-Folk itself/.test(MJS),
    "常驻说明提到这个开关（讲「存在」，不讲当前档位）");
  ok(/const ownMode = str\(f\.a11yHideOwn\)/.test(MJS) &&
     /usable\.includes\('a11y'\) && \(ownMode === 'agent' \|\| ownMode === 'all'\)/.test(MJS),
    "开着时才渲染「现在读不到本应用」那段（并且只在 a11y 能力勾了时）");
  // 只在这段**动态**文字里找这三个词：no_window/not_found 在常驻说明里也有，
  // 全文 grep 的话把动态段整段删掉都还是绿的（第一版就是这么写的）。
  const dyn = MJS.slice(
    MJS.indexOf("const ownMode = str(f.a11yHideOwn)"),
    MJS.indexOf("const off = Object.keys(CAP_USAGE)"),
  );
  ok(/hideOwn/.test(dyn) && /no_window/.test(dyn) && /not_found/.test(dyn),
    "那段说清会返回什么（no_window / not_found + hideOwn），agent 才不会当 bug 重试");
  ok(/every accessibility service/.test(MJS) && /best effort/.test(MJS),
    "all 档额外说明连别的服务也读不到、且只是 best effort");
}

console.log("\n── 文档 ──");
{
  ok(/别看本应用|无障碍别看/.test(NOTES) && /a11y_hide_own|A11yOwn/.test(NOTES),
    "dev-notes 记了这个档位（中文）");
  ok(/A11yOwn|a11y_hide_own/.test(NOTES_EN), "dev-notes.en 也记了（英文）");
}

console.log(
  "\n" + (bad === 0 ? "✓ 全部通过" : "✗ 有失败") + "：" + n + " 项断言，" + bad + " 项失败"
);
process.exit(bad === 0 ? 0 : 1);
