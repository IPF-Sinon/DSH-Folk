#!/usr/bin/env node
/**
 * 运行时卡片（开关卡片 + 版本菜单）的门禁。
 *
 * ## 为什么钉死这些形状
 *
 * 这一版把卡片从「一排按钮 + 三个小开关」改成「开关卡片 + 长按版本菜单」，用户明确
 * 定的形状有三条是**功能性**的，不是外观：
 *
 * 1. **点一下卡片 = 立即检查更新**（开关仍然只是那个开关）。它靠 `confirmAfterCheck`
 *    那条路：先 bump revision 去查，查到可装的才弹确认框。改成直接 `updateConfirming = true`
 *    就会在「其实已经是最新」时也弹一个框；把整行交回开关就会丢掉手动检查。
 * 2. **两个滑块改了必须重拉列表**（`LaunchedEffect(reloadKey, slim, beta)`）——列表内容
 *    由它们筛选，不重拉就会显示上一次选择的结果；反过来，拖动过程中每帧都落盘 + 发请求
 *    会把列表刷成幻灯片，所以只在**换档**时回调（`onValueChangeFinished`）。
 * 3. **当前已装那一版被筛掉时必须钉在最上面**，而且点它是**重装**（保留数据/全新重装
 *    二选一），其它版本才是切换 —— 否则「滑到别的组合」之后用户就够不着重装了。
 *
 * 另外几条是「少一处就会静默」的：flavor 必须来自 metadata（历史 release 的 tag 里没有
 * 它，只能靠 `"flavor"` 字段），导入按钮在左下角（`dismissButton` 的位置），以及
 * 卡片上不能再留第二个 beta/slim 开关（同一状态两处入口必然漂移）。
 */
const fs = require("fs");
const path = require("path");

const ROOT = path.join(__dirname, "..");
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), "utf8");

const SETTINGS = read("app/src/main/java/me/bmax/apatch/ui/screen/settings/FunctionSettings.kt");
const TOGGLE = read("app/src/main/java/me/bmax/apatch/ui/component/ToggleSettingCard.kt");
const RUNTIME = read("app/src/main/java/me/bmax/apatch/dsh/DshRuntime.kt");
const SCREEN = read("app/src/main/java/me/bmax/apatch/ui/screen/settings/FunctionSettingsScreen.kt");
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

/** 切一段：从 [from] 到 [to]（`to` 找不到就切到文件尾）。 */
function slice(from, to) {
  const a = SETTINGS.indexOf(from);
  if (a < 0) return "";
  const b = to ? SETTINGS.indexOf(to, a) : -1;
  return SETTINGS.slice(a, b < 0 ? SETTINGS.length : b);
}

const card = slice('item(key = "function_runtime"', 'item(key = "function_repair_plugins"');
const dialog = slice("private fun RuntimeVersionDialog(", "private fun RuntimeFlavorSlider(");
const row = slice("private fun RuntimeVersionRow(", "private fun RuntimeRowActionLabel(");
const twoStop = slice("private fun RuntimeTwoStopSlider(", "private fun RuntimeSliderEndLabel(");

console.log("\n── 卡片：一张开关卡片，点=检查、长按=菜单 ──");
{
  ok(card.length > 0, "找得到 function_runtime 卡片块");
  ok((card.match(/ToggleSettingCard\(/g) || []).length === 1,
    "卡片里只有**一个**开关（beta/slim 已搬进菜单；同状态两处入口必然漂移）");
  const toggle = card.slice(card.indexOf("ToggleSettingCard("));
  ok(/checked = runtimeAutoCheck/.test(toggle) &&
     /onCheckedChange = onRuntimeAutoCheckChange/.test(toggle),
    "这个开关就是「自动检查更新」（读写都是它）");
  ok(/description = stringResource\(R\.string\.dsh_runtime_auto_check_summary\)/.test(toggle),
    "开关的说明讲的是自动检查本身（不是交互提示）");
  // 点一下 = 检查：走 confirmAfterCheck 那条路（查完真有才弹确认框）
  ok(/confirmAfterCheck = true[\s\S]{0,80}onCheckRuntimeUpdateRequested\(\)/.test(toggle),
    "点一下卡片 = 立即检查（先 bump revision，查到可装的才弹确认框）");
  ok(/result\?\.version != null && result\.minAppVersion\.isEmpty\(\) ->\s*\n\s*updateConfirming = true/.test(toggle),
    "已知有可装更新时点一下直接弹确认框（不再白等一次网络）");
  ok(/onLongClick = \{ versionListOpen = true \}/.test(toggle),
    "长按卡片打开版本菜单");
  ok(!/onLongClick = \{ if \(runtimeInstalled\)/.test(card),
    "长按不要求已装运行时（没装过的人也要能挑一版装上）");
  ok(!/onRuntimeBetaChange|onRuntimeSlimChange/.test(card.slice(0, card.indexOf("RuntimeVersionDialog("))),
    "卡片本体不再直接摆 beta/slim 开关（它们只在菜单的滑块里）");
  ok(!/R\.string\.dsh_runtime_update_action|R\.string\.dsh_runtime_reinstall\)|R\.string\.dsh_runtime_import\)/.test(
    card.slice(0, card.indexOf("RuntimeVersionDialog("))),
    "卡片上不再有 更新/重装/导入 按钮（分别落到 点卡片 / 当前行 / 菜单左下角）");
  // 条件与文案都要在：只查文案的话把 if (checking) 改成 if (false) 照样绿
  ok(/if \(checking\) \{/.test(card) && /R\.string\.dsh_runtime_checking/.test(card),
    "检查进行中要有可见反馈（原来是按钮里的转圈，按钮没了就得自己给）");
}

console.log("\n── 菜单：两个两档滑块，改了就重拉 ──");
{
  ok(/slim: Boolean,\s*\n\s*beta: Boolean,/.test(dialog) &&
     /onSlimChange: \(Boolean\) -> Unit,/.test(dialog) &&
     /onBetaChange: \(Boolean\) -> Unit,/.test(dialog),
    "菜单接着两个滑块的状态与回调");
  ok(/LaunchedEffect\(reloadKey, slim, beta\)/.test(dialog),
    "两个滑块都在重拉的 key 里（改了不重拉 = 列表还是上一次选择的结果）");
  ok(/RuntimeFlavorSlider\(slim = slim, onSelect = onSlimChange\)/.test(dialog) &&
     /RuntimeChannelSlider\(beta = beta, onSelect = onBetaChange\)/.test(dialog),
    "两个滑块都接上了（一个版本类型、一个更新通道）");
  ok(/if \(slim\) RuntimeSliderNote\(text = stringResource\(R\.string\.dsh_runtime_slim_summary\)\)/.test(dialog) &&
     /if \(beta\) RuntimeSliderNote\(text = stringResource\(R\.string\.dsh_runtime_beta_summary\)\)/.test(dialog),
    "选了非默认那一端才解释它是什么（精简版砍了什么 / 测试版可能不稳定）");
  ok(/private fun RuntimeFlavorSlider\(slim: Boolean, onSelect: \(Boolean\) -> Unit\)/.test(SETTINGS) &&
     /leftLabel = stringResource\(R\.string\.dsh_runtime_flavor_full\)/.test(SETTINGS) &&
     /rightLabel = stringResource\(R\.string\.dsh_runtime_flavor_slim\)/.test(SETTINGS),
    "版本类型滑块两端是 完整版 / 精简版");
  ok(/private fun RuntimeChannelSlider\(beta: Boolean, onSelect: \(Boolean\) -> Unit\)/.test(SETTINGS) &&
     /leftLabel = stringResource\(R\.string\.dsh_runtime_channel_stable_short\)/.test(SETTINGS) &&
     /rightLabel = stringResource\(R\.string\.dsh_runtime_channel_beta_short\)/.test(SETTINGS),
    "更新通道滑块两端是 正式 / 测试");
  ok(/Slider\(/.test(twoStop) && /steps = 1/.test(twoStop) && /valueRange = 0f\.\.1f/.test(twoStop),
    "两档滑块就是 steps = 1 的 Slider（拖过去自动吸附到两端）");
  ok(/onValueChangeFinished = \{ if \(dragging != rightSelected\) onSelect\(dragging\) \}/.test(twoStop),
    "只在真的换档时回调（拖动过程每帧落盘 + 重拉 = 列表变幻灯片）");
  ok(/LaunchedEffect\(rightSelected\) \{ dragging = rightSelected \}/.test(twoStop),
    "外部状态变了滑块跟着走（否则滑块显示的档位和实际选择不符）");
  // 接线：状态来自设置页那份 prefs（与自动检查用的是同一对值）
  ok(/slim = runtimeSlim,/.test(card) && /beta = runtimeBeta,/.test(card) &&
     /onSlimChange = onRuntimeSlimChange,/.test(card) && /onBetaChange = onRuntimeBetaChange,/.test(card),
    "菜单的两个滑块读写的就是设置页那份状态（不是第二份）");
}

console.log("\n── 列表：按滑块筛选 + 当前版本钉在最上面 ──");
{
  ok(/list\.filter \{ RuntimeVersion\.matchesFilter\(it, slim, beta\) \}/.test(dialog),
    "列表按两个滑块筛选（在领域层算，不散在界面里）");
  ok(/if \(currentVersion\.isNotEmpty\(\) && !currentVisible\) \{/.test(dialog) &&
     /item\(key = "current-pinned"\)/.test(dialog),
    "当前已装被筛掉时钉在最上面（否则滑到别的组合就够不着重装了）");
  ok(/R\.string\.dsh_runtime_menu_filter_note/.test(dialog), "菜单里说清列表跟着滑块走");
  ok(/R\.string\.dsh_runtime_versions_warning/.test(dialog),
    "保留「老版本可能缺修复」的提醒（降级不是无代价的）");
  const filter = RUNTIME.slice(RUNTIME.indexOf("fun matchesFilter("), RUNTIME.indexOf("fun channelRank("));
  ok(/if \(flavorOf\(entry\) != want\) return false/.test(filter),
    "版本类型是硬条件（滑块说的就是它）");
  ok(/CHANNEL_STABLE, CHANNEL_SLIM -> !beta/.test(filter) &&
     /CHANNEL_BETA, CHANNEL_SLIM_BETA -> beta/.test(filter) &&
     /else -> true/.test(filter),
    "通道按四个滚动 tag 分边，历史版本两边都给（tag 里没有通道信息，只给一边等于让人找不到降级包）");
  const flavor = RUNTIME.slice(RUNTIME.indexOf("fun flavorOf("), RUNTIME.indexOf("fun matchesFilter("));
  ok(/entry\.flavor\.isNotEmpty\(\) -> entry\.flavor/.test(flavor) &&
     /entry\.tag\.contains\("slim"\) -> FLAVOR_SLIM/.test(flavor),
    "flavor 优先信 metadata，老 metadata 才退回按 tag 猜");
}

console.log("\n── flavor 一路从 metadata 到模型 ──");
{
  ok((RUNTIME.match(/val flavor: String = ""/g) || []).length === 2,
    "DshMeta 与 RuntimeVersion 各有一个 flavor 字段（少一个就断在中间某一层）");
  ok(/flavor = json\.optString\("flavor", ""\)/.test(RUNTIME),
    "metadata 解析读 \"flavor\"（构建脚本一直在写这一项）");
  ok((RUNTIME.match(/flavor = meta\.flavor,/g) || []).length === 2,
    "两处 RuntimeVersion 构造（API 列表 + 通道兜底）都带上 flavor");
  ok(/flavor = flavorOf\(this\),/.test(RUNTIME),
    "toMeta() 带上算好的 flavor（否则切版本又退回按 tag 猜）");
}

console.log("\n── 行动作：当前=重装、其它=切换；左下角=导入 ──");
{
  ok(/current -> onReinstall\(\)/.test(row) && /else -> onInstall\(\)/.test(row) &&
     /tooOld -> onGoUpdateApp\(\)/.test(row),
    "点当前版本 = 重装，点其它 = 切换，要求更高 App 版本的 = 去更新应用");
  ok(/if \(current\) R\.string\.dsh_runtime_reinstall else R\.string\.dsh_runtime_row_switch/.test(row),
    "行右侧直接写清点下去会做什么（重装 / 切换）");
  ok(/private fun RuntimeCurrentVersionRow\(version: String, onReinstall: \(\) -> Unit\)/.test(SETTINGS) &&
     /combinedClickable\(onClick = onReinstall\)/.test(SETTINGS),
    "钉子行只有一个动作：重装");
  ok(/onReinstallCurrent = \{\s*\n\s*versionListOpen = false\s*\n\s*reinstallChoice = true/.test(card),
    "重装走回卡片原来那个「保留数据 / 全新重装」二选一（不清空也得问一句）");
  ok(/dismissButton = \{\s*\n\s*TextButton\(onClick = onImport\)/.test(dialog) &&
     /R\.string\.dsh_runtime_import/.test(dialog),
    "左下角（dismissButton）是导入");
  ok(/confirmButton = \{\s*\n\s*TextButton\(onClick = onDismiss\)/.test(dialog) &&
     /R\.string\.dsh_runtime_menu_close/.test(dialog),
    "右下角是关闭");
}

console.log("\n── 开关卡片盖章的语义（整行≠开关） ──");
{
  ok(/onClick: \(\(\) -> Unit\)\? = null,/.test(TOGGLE),
    "ToggleSettingCard 支持「整行点一下做别的事」");
  ok(/if \(rowClick != null\) \{[\s\S]{0,220}combinedClickable\(/.test(TOGGLE) &&
     !/if \(rowClick != null\) \{[\s\S]{0,220}role = Role\.Switch/.test(TOGGLE),
    "给了 onClick 的整行不再是 Switch 角色（否则 TalkBack 把「检查更新」念成开关）");
  ok(/onCheckedChange = if \(switchHandlesIt\)/.test(TOGGLE),
    "整行被拿走时开关自己可点（否则这一项再也开不了）");
  ok(/toggleable\(/.test(TOGGLE) && /role = Role\.Switch/.test(TOGGLE),
    "不给 onClick 的老行为原样保留（其它开关卡片不受影响）");
}

console.log("\n── 字符串（两份语言）与接线 ──");
{
  for (const k of [
    "dsh_runtime_menu_flavor",
    "dsh_runtime_menu_channel",
    "dsh_runtime_flavor_full",
    "dsh_runtime_flavor_slim",
    "dsh_runtime_channel_stable_short",
    "dsh_runtime_channel_beta_short",
    "dsh_runtime_menu_filter_note",
    "dsh_runtime_menu_empty",
    "dsh_runtime_menu_close",
    "dsh_runtime_row_switch",
    "dsh_runtime_checking",
  ]) {
    ok(STR_EN.includes('name="' + k + '"') && STR_ZH.includes('name="' + k + '"'),
      "两种语言都有 " + k);
  }
  // 被取代的旧文案不许留在资源里（留着下一个人会以为它在用）
  for (const k of [
    "dsh_runtime_slim",
    "dsh_runtime_beta",
    "dsh_runtime_long_press_hint",
    "dsh_runtime_update_action",
  ]) {
    ok(!STR_EN.includes('name="' + k + '"') && !STR_ZH.includes('name="' + k + '"'),
      "旧文案已删干净：" + k);
  }
  ok(/点一下卡片立即检查，长按选择版本/.test(
    (STR_ZH.match(/name="dsh_runtime_summary">([^<]*)</) || [])[1] || ""),
    "中文卡片副标题写清交互（点=检查、长按=版本）");
  ok(/tap the card to check right now, long press to pick a version/.test(
    (STR_EN.match(/name="dsh_runtime_summary">([^<]*)</) || [])[1] || ""),
    "英文卡片副标题写清交互");
  ok(/点一下卡片立即检查/.test(
    (STR_ZH.match(/name="dsh_runtime_management_summary">([^<]*)</) || [])[1] || ""),
    "设置搜索里的摘要也跟着改（搜到的人看到的必须是新交互）");
  ok(/runtimeAutoCheck by rememberSaveable \{ mutableStateOf\(DshRuntime\.autoCheckEnabled\(context\)\) \}/.test(SCREEN) &&
     /DshRuntime\.setAutoCheckEnabled\(context, on\)/.test(SCREEN),
    "自动检查的开关照旧读写 DshRuntime 的 prefs");
  ok(/onLongClick = \{ versionListOpen = true \}/.test(card) &&
     !/onCheckRuntimeUpdateRequested = \{ runtimeCheckRevision\+\+ \}/.test(card.slice(card.indexOf("RuntimeVersionDialog("))),
    "菜单打开就先拉一次列表（那次拉取就是「检查」，不必再单独 bump revision）");
}

console.log("\n── 文档 ──");
{
  ok(/开关卡片/.test(NOTES) && /matchesFilter|版本类型/.test(NOTES),
    "dev-notes 记了新形状（中文）");
  ok(/switch card|slider/i.test(NOTES_EN) && /flavor/i.test(NOTES_EN),
    "dev-notes.en 也记了（英文）");
}

console.log("\n── README（用户看到的说明） ──");
{
  // README 是最容易滞后的一份：卡片改完、dev-notes 写对了，README 里还留着「一个按钮三种用法」。
  const rdZh = read("README.md");
  const rdEn = read("README.en.md");
  ok(/点一下卡片[\s\S]{0,40}立即检查更新/.test(rdZh) && /长按卡片[\s\S]{0,40}版本菜单/.test(rdZh),
    "中文 README 说清了 点卡片=检查 / 长按=菜单");
  ok(/Tap the card[\s\S]{0,120}check for updates right now/i.test(rdEn) &&
     /Long-press the card[\s\S]{0,80}version menu/i.test(rdEn),
    "英文 README 同（Tap the card / Long-press the card）");
  ok(/完整版 \/ 精简版、正式 \/ 测试/.test(rdZh) && /full vs slim, and stable vs beta/.test(rdEn),
    "两个滑块在两个 README 里都写了");
  ok(/固定在最上面/.test(rdZh) && /pinned on top/.test(rdEn),
    "「当前已装那一版钉在最上面」两个 README 都写了");
  ok(!/一个按钮三种用法/.test(rdZh) && !/one button, three uses/i.test(rdEn),
    "旧说法（一个按钮三种用法）已从两个 README 清掉");
}

console.log(
  "\n" + (bad === 0 ? "✓ 全部通过" : "✗ 有失败") + "：" + n + " 项断言，" + bad + " 项失败",
);
process.exit(bad === 0 ? 0 : 1);
