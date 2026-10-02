#!/usr/bin/env node
/*
 * 「分享/以…打开 → 用途选择 → 交给 DSH 处理」这条路（方案 A）的门禁。
 *
 * 为什么单独开一个 check：这条路把一件易碎的事钉在 App 侧——**纯读** dsh 的
 * `$DSH_HOME/storages/workspace.json`（storage-json 的 single-layout 单元）来列工作区，
 * 并把容器内路径映射回宿主可写路径。两件事都没有编译期信号，且随 dsh 存储布局/挂载
 * 配置而变。这里用两招补盲区：
 *   1. 静态断言接线齐全（manifest 收任意文件、MainActivity 三条分流、落地页行为）；
 *   2. 复刻 DshFileHandoff 的几个纯函数（workspace.json 解析、guest→host 映射、
 *      文件名规整），真跑样例证明算法。
 */
const fs = require("fs");
const path = require("path");

const ROOT = path.resolve(__dirname, "..");
let n = 0;
let bad = 0;
function ok(cond, msg) {
  n++;
  if (cond) console.log("  \u2713 " + msg);
  else {
    bad++;
    console.log("  \u2717 " + msg);
  }
}
function eq(actual, expected, msg) {
  const same = JSON.stringify(actual) === JSON.stringify(expected);
  n++;
  if (same) console.log("  \u2713 " + msg);
  else {
    bad++;
    console.log("  \u2717 " + msg + ` \u2192 \u5b9e\u9645 ${JSON.stringify(actual)}\uff0c\u671f\u671b ${JSON.stringify(expected)}`);
  }
}
/** 剥掉注释再断言「不该出现 / 必须出现 X」，避免命中解释用的 KDoc。 */
function code(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/[^\n]*/g, "");
}
const read = (p) => fs.readFileSync(path.join(ROOT, p), "utf8");

// ─────────────────────────────────────────────────────────────────────────────
// 1) 接线：manifest 收任意文件
// ─────────────────────────────────────────────────────────────────────────────
console.log("1) Manifest / 分流接线");
const manifest = read("app/src/main/AndroidManifest.xml");
const alias = manifest.slice(
  manifest.indexOf("MainActivityFileHandler"),
  manifest.indexOf("</activity-alias>", manifest.indexOf("MainActivityFileHandler"))
);
const sendFilter = alias.slice(alias.indexOf("action.SEND"), alias.indexOf("SEND_MULTIPLE"));
const viewFilter = alias.slice(alias.indexOf("action.VIEW"), alias.indexOf("</intent-filter>"));
ok(/mimeType="\*\/\*"/.test(sendFilter), "SEND 过滤器收任意 MIME（*/*）");
ok(/mimeType="\*\/\*"/.test(viewFilter) && /scheme="content"/.test(viewFilter), "VIEW 过滤器收任意 MIME 的 content");

// ─────────────────────────────────────────────────────────────────────────────
// 2) MainActivity：分享入口交给用途选择弹窗，恢复备份/导入主题分流回宿主
// ─────────────────────────────────────────────────────────────────────────────
const main = code(read("app/src/main/java/me/bmax/apatch/ui/MainActivity.kt"));
ok(/DshFileHandoffDialog\(/.test(main), "分享入口渲染用途选择弹窗 DshFileHandoffDialog");
ok(/onTheme\s*=/.test(main) && /ThemeManager\.readThemeMetadata/.test(main), "导入主题分流 → ThemeManager.readThemeMetadata");
ok(/onBackup\s*=/.test(main) && /DshConfigBackup\.stage/.test(main) && /RestoreWizardScreenDestination/.test(main), "恢复备份分流 → 暂存后进恢复向导");
ok(!/fileName\.endsWith\("\.fpt"/.test(main), "已移除「只认 .fpt 自动导主题」的写死分支");
ok(!/DshFileHandoffScreenDestination/.test(main), "不再跳独立落地页（已改成弹窗）");
// bug1 根因：开了「服务就绪自动打开页面」时，有外部文件待处理会被自动开页面甩走。
// 修法：用 externalFilePending 压制——但**必须只活到弹窗关闭**。曾用 rememberSaveable
// 固化，导致分享过一次后自动开页面永久失效（用户实测：功能变成一次性的）。
ok(/externalFilePending\s*=\s*remember\s*\{\s*mutableStateOf\(installUri != null\)\s*\}/.test(main),
  "externalFilePending 用普通 remember（installUri 未清空前同步取初值，不与服务就绪抢跑）");
ok(!/externalFilePending\s*=\s*rememberSaveable/.test(main),
  "externalFilePending 不得用 rememberSaveable（否则会把自动开页面永久压死）");
ok(/if \(externalFilePending\.value\) return@collect/.test(main),
  "服务就绪自动开页面的 LaunchedEffect 在有外部文件待处理时短路（修 bug1 真正根因）");
ok(/externalFilePending\.value = true/.test(main) && /externalFilePending\.value = false/.test(main),
  "解析到外部文件时置位、弹窗关闭时解除（自动开页面随之恢复）");
// 「服务就绪后自动打开页面」的 once-guard 必须随服务离开 RUNNING 清零，
// 否则它只覆盖本进程的第一次就绪：停止再启动第二次就不自动开页面了。
ok(/if \(phase != DshPhase\.RUNNING\) \{[\s\S]{0,200}webUiAutoOpened\.value = false/.test(main),
  "服务离开 RUNNING 时重新武装 webUiAutoOpened（不再一次就完）");

// ─────────────────────────────────────────────────────────────────────────────
// 3) 弹窗行为：三选项 + 复制成功 toast + 提示词进剪贴板 + Web UI 只在用户点击时才开
// ─────────────────────────────────────────────────────────────────────────────
console.log("3) 用途选择弹窗行为");
const dlg = code(read("app/src/main/java/me/bmax/apatch/ui/component/DshFileHandoffDialog.kt"));
ok(/Dialog\(onDismissRequest/.test(dlg), "是弹窗（Dialog），不是整页");
ok(/dsh_share_use_dsh/.test(dlg) && /dsh_share_use_backup/.test(dlg) && /dsh_share_use_theme/.test(dlg), "三个用途选项都在");
ok(/DshFileHandoff\.copyInto/.test(dlg), "选工作区后复制文件进工作区");
ok(/showToast\([^)]*R\.string\.dsh_handoff_copied/.test(dlg), "复制成功后弹 toast（dsh_handoff_copied）");
ok(/clipboard\.setText/.test(dlg) && /R\.string\.dsh_handoff_prompt/.test(dlg), "提示词写入剪贴板（dsh_handoff_prompt）");
// 复制成功进 DONE 步，而不是直接拉起 Web UI；openWeb 只挂在按钮 onClick 上。
const onSuccess = dlg.slice(dlg.indexOf("onSuccess"), dlg.indexOf("onFailure"));
ok(/step = "DONE"/.test(onSuccess) && !/openWeb\(\)/.test(onSuccess), "复制成功进完成步，不自动拉起 Web UI（修 bug1：不再把 App 挤到后台）");
ok(/onClick = \{ openWeb\(\)/.test(dlg), "Web UI 改为用户在完成步点按钮才打开");
ok(/requestedStart/.test(dlg) && /DshPhase\.ERROR/.test(dlg), "ERROR 态不反复 bootstrap（requestedStart 守卫）");

// ─────────────────────────────────────────────────────────────────────────────
// 4) DshFileHandoff 源码里关键判据在场
// ─────────────────────────────────────────────────────────────────────────────
console.log("4) DshFileHandoff 关键判据");
const ho = code(read("app/src/main/java/me/bmax/apatch/dsh/DshFileHandoff.kt"));
ok(/storages\/workspace\.json/.test(ho), "读 $DSH_HOME/storages/workspace.json");
ok(/"tables"[\s\S]*?"workspaces"/.test(ho), "解析 tables.workspaces");
ok(/"global"[\s\S]*?"workspaceIds"/.test(ho), "用 global.workspaceIds 排序");
ok(/wsMountEnabled/.test(ho) && /GUEST_ALIASES/.test(ho) && /DshEnv\.rootfs/.test(ho), "guest→host 三分支（工作区挂载 / 共享存储别名 / rootfs 内）都在");
ok(/ordered\.add\(0,/.test(ho), "默认工作区缺失时前插，保证永远有可选项");

// ─────────────────────────────────────────────────────────────────────────────
// 5) 纯函数复刻 + 跑样例（证明算法，而不只是「字符串在场」）
// ─────────────────────────────────────────────────────────────────────────────
console.log("5) 算法复刻验证");
const HOST_ROOT = "/storage/emulated/0";
const WS_GUEST = "/root/workspace";
const ROOTFS = "/data/app/rootfs";

const normalizeGuest = (p) => {
  const t = p.trim().replace(/\\/g, "/").replace(/^\/+|\/+$/g, "");
  return t === "" ? "/" : "/" + t;
};
eq(normalizeGuest("/root/workspace/"), "/root/workspace", "normalizeGuest 去尾斜杠");
eq(normalizeGuest(""), "/", "normalizeGuest 空 → 根");
eq(normalizeGuest("a//b"), "/a//b", "normalizeGuest 前置斜杠（中间不塌缩，与 Kotlin 对齐）");

const sanitizeName = (name) => {
  const base = name.trim().split("/").pop().split("\\").pop()
    .split("").filter((c) => c !== "\u0000" && c >= " ").join("").trim();
  return base === "" ? "shared-file" : base;
};
eq(sanitizeName("a/b/c.txt"), "c.txt", "sanitizeName 去路径");
eq(sanitizeName("   "), "shared-file", "sanitizeName 空 → 回落");

// guest→host（复刻三分支）
function guestToHost(guest, wsEnabled, mounts) {
  const g = normalizeGuest(guest);
  if (wsEnabled) {
    for (const m of mounts) {
      const base = normalizeGuest(`${WS_GUEST}/${m.dest}`);
      if (g === base || g.startsWith(base + "/")) {
        const rest = g.slice(base.length).replace(/^\/+/, "");
        const hostBase = m.src === "" ? HOST_ROOT : `${HOST_ROOT}/${m.src}`;
        return rest === "" ? hostBase : `${hostBase}/${rest}`;
      }
    }
  }
  for (const alias of ["/sdcard", "/storage/emulated/0"]) {
    if (g === alias || g.startsWith(alias + "/")) {
      const rest = g.slice(alias.length).replace(/^\/+/, "");
      return rest === "" ? HOST_ROOT : `${HOST_ROOT}/${rest}`;
    }
  }
  return `${ROOTFS}/${g.replace(/^\/+/, "")}`;
}
const mounts = [{ src: "", dest: "sdcard" }];
eq(guestToHost("/root/workspace", true, mounts), `${ROOTFS}/root/workspace`, "默认工作区 → rootfs 内（不被 sdcard 挂载吞掉）");
eq(guestToHost("/root/workspace/sdcard", true, mounts), HOST_ROOT, "工作区下 sdcard 挂载点 → 挂载源 HOST_ROOT");
eq(guestToHost("/root/workspace/sdcard/x", true, mounts), `${HOST_ROOT}/x`, "挂载点子路径 → 源下对应子路径");
eq(guestToHost("/sdcard/Documents", false, mounts), `${HOST_ROOT}/Documents`, "/sdcard 别名 → HOST_ROOT");
eq(guestToHost("/root/.dsh", true, mounts), `${ROOTFS}/root/.dsh`, "其它 rootfs 内路径 → rootfs");
eq(guestToHost("/root/workspace/notes", true, [{ src: "Documents", dest: "docs" }]),
  `${ROOTFS}/root/workspace/notes`, "挂载 dest 不匹配时仍落 rootfs（不误吞）");

// workspace.json 解析（single-unit 形状）
function listWorkspaces(doc) {
  const ws = (doc.tables && doc.tables.workspaces) || {};
  const order = (doc.global && doc.global.workspaceIds) || [];
  const byId = {};
  for (const id of Object.keys(ws)) {
    const r = ws[id];
    if (!r || !r.path) continue;
    byId[id] = { id, title: (r.title || "").trim(), guestPath: normalizeGuest(r.path), sessionCount: (r.sessionIds || []).length };
  }
  const out = [];
  for (const id of order) if (byId[id]) out.push(byId[id]);
  for (const id of Object.keys(byId)) if (!order.includes(id)) out.push(byId[id]);
  const def = normalizeGuest(WS_GUEST);
  if (!out.some((w) => w.guestPath === def)) out.unshift({ id: "", title: "", guestPath: def, sessionCount: 0 });
  return out;
}
const sample = {
  unit: { name: "workspace", version: 2 },
  global: { workspaceIds: ["w2", "w1"] },
  tables: {
    workspaces: {
      w1: { path: "/root/workspace", title: "默认", sessionIds: ["s1", "s2"] },
      w2: { path: "/sdcard/Documents", title: "文档", sessionIds: ["s3"] },
    },
  },
};
const list = listWorkspaces(sample);
eq(list.map((w) => w.guestPath), ["/sdcard/Documents", "/root/workspace"], "按 workspaceIds 顺序排列");
eq(list.map((w) => w.sessionCount), [1, 2], "会话数来自 sessionIds 长度");
const empty = listWorkspaces({ tables: { workspaces: {} }, global: { workspaceIds: [] } });
eq(empty.length, 1, "空注册表 → 仍给出默认工作区");
eq(empty[0].guestPath, "/root/workspace", "合成的默认工作区指向 /root/workspace");
const noDefault = listWorkspaces({
  tables: { workspaces: { a: { path: "/sdcard/x", title: "X", sessionIds: [] } } },
  global: { workspaceIds: ["a"] },
});
eq(noDefault[0].guestPath, "/root/workspace", "注册表没有默认工作区时前插它");

console.log(`\n${bad === 0 ? "\u2713 \u5168\u90e8\u901a\u8fc7" : "\u2717 \u6709\u5931\u8d25"}\uff1a${n} \u9879\u65ad\u8a00\uff0c${bad} \u9879\u5931\u8d25`);
process.exit(bad === 0 ? 0 : 1);
