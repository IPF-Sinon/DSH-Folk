#!/usr/bin/env node
/*
 * 宿主能力提示词（dsh-folk-host 插件）门禁。
 *
 * 为什么单独开一个 check，而不是继续往 check-fs-scope.js 里塞正则：
 * 2026-10 出过一次真实事故 —— 用户机器上整段宿主提示词（连安卓原生能力桥）**全部消失**。
 * 根因不在插件代码，而在 patch 文件的**形状**：
 *
 *   - dsh 的 `applyEntryPatches` 语义是「带 insert 的条目 = 新增插件；不带 insert 的条目
 *     = patch 一个已存在的 id，找不到就 warn 后跳过」。
 *   - `dsh-config-manager` 做 config import 时把 `$DSH_HOME/cordis.patch.yml` 整体重写成
 *     扁平条目表，抹掉了我们的 `- insert:` 外层。
 *   - 于是那行退化成「patch 一个不存在的 id」→ 被 dsh 跳过 → 插件从未加载。
 *   - 而当时 `ensurePatchRow` 的判据是 `existing.contains("id: dsh-folk-host")`，压平后的
 *     行照样含这个子串 → 每次启动都判「已存在」→ 永远修不回来。
 *
 * 当时 18 个门禁全是「读源码跑正则」，**没有一个真的执行过插件或 patch 语义**，所以谁都没
 * 拦住它。这个 check 用两件事补上这个盲区：
 *   1. 用一份忠实复刻的 `applyEntryPatches` 真跑语义，证明「压平 = 插件不注册」而
 *      「insert 形状 = 注册」；
 *   2. 真的 import 容器里那份 .mjs 并调用它的 render()，证明段落非空。
 */
const fs = require("fs");

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
    console.log(
      "  \u2717 " + msg + ` → 实际 ${JSON.stringify(actual)}，期望 ${JSON.stringify(expected)}`
    );
  }
}
/** 剥掉注释再断言「不该出现 X」，否则会命中解释这件事的 KDoc。 */
function code(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/[^\n]*/g, "");
}

// ─────────────────────────────────────────────────────────────────────────────
// 1) dsh 的 patch 语义（忠实复刻 @deepseek-ai/dsh-app-boot 的 applyEntryPatches）
//    两个版本（0.1.7-rc.2 / 0.2.0-rc.2）这段逻辑逐字一致，已离线核对。
// ─────────────────────────────────────────────────────────────────────────────
function applyEntryPatches(data, patches, warn) {
  if (!patches || !patches.length) return [...data];
  data = JSON.parse(JSON.stringify(data));
  const entryMap = new Map();
  const buildMap = (entries) => {
    for (const entry of entries) {
      if (entry.id) entryMap.set(entry.id, entry);
      if (entry.group && Array.isArray(entry.config)) buildMap(entry.config);
    }
  };
  buildMap(data);
  for (const patch of patches) {
    const { id, insert, name, ...overrides } = patch;
    if (insert) {
      if (id) {
        const target = entryMap.get(id);
        if (!target) {
          warn(`patch insert: entry ${id} not found`);
          continue;
        }
        if (!target.group) {
          warn(`patch insert: entry ${id} is not a group`);
          continue;
        }
        if (!Array.isArray(target.config)) target.config = [];
        target.config.push(...insert);
      } else data.push(...insert);
      buildMap(insert);
      continue;
    }
    if (!id) {
      warn("patch: id is required for non-insert patches");
      continue;
    }
    const target = entryMap.get(id);
    if (!target) {
      warn(`patch: entry ${id} not found`);
      continue;
    }
    if (name && name !== target.name) {
      warn(`patch: name mismatch for ${id}`);
      continue;
    }
    Object.assign(target, overrides);
  }
  return data;
}

const ENTRY_ID = "dsh-folk-host";
const PLUGIN_GUEST_PATH = "/root/.dsh/plugins/dsh-folk-host.mjs";

console.log("\u2500 #1 patch 语义：为什么 `- insert:` 外层不能少");
{
  const base = [{ id: "harness", name: "some-bundle" }];

  const warnings = [];
  const inserted = applyEntryPatches(
    base,
    [{ insert: [{ id: ENTRY_ID, name: PLUGIN_GUEST_PATH }] }],
    (w) => warnings.push(w)
  );
  ok(
    inserted.some((e) => e.id === ENTRY_ID),
    "带 insert 的条目 → 插件被真正加入条目表（正确形状可用）"
  );
  eq(warnings, [], "带 insert 时 dsh 不报警告");

  const warnings2 = [];
  const flattened = applyEntryPatches(
    base,
    [{ id: ENTRY_ID, name: PLUGIN_GUEST_PATH }],
    (w) => warnings2.push(w)
  );
  ok(
    !flattened.some((e) => e.id === ENTRY_ID),
    "缺 insert 的条目 → 插件**不会**被加入（这正是线上那次的失效方式）"
  );
  eq(
    warnings2,
    [`patch: entry ${ENTRY_ID} not found`],
    "缺 insert 时 dsh 报 “entry not found” 并跳过（日志里能看到这句）"
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// 2) ensurePatchRow 的自愈算法（与 Kotlin 同构；Kotlin 那边的关键步骤在第 3 节做静态断言）
// ─────────────────────────────────────────────────────────────────────────────
const OUR_COMMENT_PREFIX = "# DSH-Folk 宿主能力说明";

function repairPatchRow(existing) {
  const idLine = `- id: ${ENTRY_ID}`;
  const lines = existing.split("\n");
  const at = lines.findIndex((l) => l.trim() === idLine);
  // 有缩进 = 在 insert 之下，形状正确，原样返回
  if (at >= 0 && lines[at].length !== lines[at].trimStart().length) return existing;

  const kept = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (line.startsWith(OUR_COMMENT_PREFIX)) {
      i++;
      continue;
    }
    if (line.trim() === idLine) {
      const base = line.length - line.trimStart().length;
      i++;
      while (
        i < lines.length &&
        lines[i].trim() !== "" &&
        lines[i].length - lines[i].trimStart().length > base
      ) {
        i++;
      }
      continue;
    }
    kept.push(line);
    i++;
  }
  while (kept.length > 0 && kept[kept.length - 1].trim() === "") kept.pop();
  const head = kept.join("\n");
  let body = head ? head + "\n" : "";
  body += OUR_COMMENT_PREFIX + "（App 自动维护；删掉这几行即可停用）\n";
  body += "- insert:\n";
  body += `    - id: ${ENTRY_ID}\n`;
  body += `      name: ${PLUGIN_GUEST_PATH}\n`;
  return body;
}

console.log("\u2500 #2 ensurePatchRow 自愈：被 dsh-config-manager 压平后能修回来");
{
  // 用户机器上真实的那份文件（原样抄下来，含 config-manager 留下的注释）
  const flattened = [
    "# rewritten by dsh-config-manager import (original comments not preserved)",
    "- id: dsh-folk-host",
    "  name: /root/.dsh/plugins/dsh-folk-host.mjs",
    "- id: ui-skin-dragon-heir",
    "  disabled: true",
    "- id: ui-skin-miku",
    "  disabled: true",
    "",
  ].join("\n");

  const fixed = repairPatchRow(flattened);
  ok(/- insert:\n {4}- id: dsh-folk-host\n {6}name: \/root\/\.dsh\/plugins\/dsh-folk-host\.mjs\n$/.test(fixed),
    "自愈后写回正确的 insert 形状");
  ok(!/^- id: dsh-folk-host$/m.test(fixed),
    "压平的顶格条目已被摘掉（否则 dsh 仍会跳过）");
  ok(/# rewritten by dsh-config-manager import/.test(fixed),
    "别人的注释与条目原样保留（不重写整个文件）");
  ok(/- id: ui-skin-dragon-heir/.test(fixed) && /- id: ui-skin-miku/.test(fixed),
    "其它 patch 条目（皮肤 disabled）一个不丢");

  // 幂等：修好之后再跑一次不该变化
  eq(repairPatchRow(fixed), fixed, "已修好的文件再跑一次不产生变化（幂等）");

  // 本来就没有我们的条目 → 只追加
  const other = ["- id: ui-skin-miku", "  disabled: true", ""].join("\n");
  const appended = repairPatchRow(other);
  ok(/- insert:/.test(appended) && /- id: ui-skin-miku/.test(appended),
    "完全没有我们的条目时：追加 insert 块且不动已有行");

  // 空文件 → 直接得到合法顶层数组（以注释开头、紧跟 insert 条目）
  const fromEmpty = repairPatchRow("");
  ok(/^- insert:$/m.test(fromEmpty) && !/^- id: /.test(fromEmpty),
    "空文件也能生成合法的顶层 YAML 数组（只有注释 + insert 条目）");
}

// ─────────────────────────────────────────────────────────────────────────────
// 3) Kotlin 侧的关键步骤（防止 JS 复刻与实现漂移）
// ─────────────────────────────────────────────────────────────────────────────
const hostPrompt = fs.readFileSync("app/src/main/java/me/bmax/apatch/dsh/DshHostPrompt.kt", "utf8");
const hpCode = code(hostPrompt);

console.log("\u2500 #3 DshHostPrompt.kt：判据改成缩进，且不再早退");
ok(/OUR_COMMENT_PREFIX/.test(hpCode), "有 OUR_COMMENT_PREFIX 常量（自愈时清旧注释）");
ok(/lines\[at\]\.length != lines\[at\]\.trimStart\(\)\.length/.test(hpCode),
  "用「id 行有没有缩进」判断是否在 insert 之下");
ok(!/existing\.contains\("id: \$ENTRY_ID"\)/.test(hpCode),
  "旧的 `contains(\"id: $ENTRY_ID\")` 早退判据已移除（它对压平行照样成立，永远修不回来）");
ok(/if \(line\.trim\(\) == idLine\)/.test(hpCode) &&
  /lines\[i\]\.length - lines\[i\]\.trimStart\(\)\.length > base/.test(hpCode),
  "会把压平的整条（含 name 等更深缩进行）摘掉");
ok(/append\("- insert:\\n"\)/.test(hpCode) &&
  /append\(" {4}- id: \$ENTRY_ID\\n"\)/.test(hpCode) &&
  /append\(" {6}name: \$PLUGIN_GUEST_PATH\\n"\)/.test(hpCode),
  "写回的正是 insert + 4/6 空格缩进的三行");
ok(/if \(body == existing\) return/.test(hpCode), "内容没变就不写盘（幂等、少一次 rename）");

// ─────────────────────────────────────────────────────────────────────────────
// 4) 真跑插件：段落必须非空（facts 读不到时整段会消失，这次事故的表象）
// ─────────────────────────────────────────────────────────────────────────────
(async () => {
  console.log("\u2500 #4 真跑 dsh-folk-host 的 render()");
  const mod = await import("../app/src/main/assets/dsh-folk-host.mjs");
  const render = mod.__test.render;

  eq(render(null), "", "facts 读不到时整段为空（这正是「连原生能力桥都没了」的成因）");
  eq(render({ promptEnabled: false }), "", "promptEnabled=false 时整段为空（零成本关开关）");

  const facts = {
    promptEnabled: true,
    appVersion: "1.0.0",
    device: "Test",
    androidRelease: "14",
    sdkInt: 34,
    abi: "arm64-v8a",
    containerRuntime: "proot",
    locale: "zh-CN",
    storageMounted: true,
    fsBridge: true,
    storageDenied: [],
    storageAllowed: [],
    nativeBridge: true,
    nativeCaps: { toast: "read_write" },
    nativeOnce: {},
  };
  const text = render(facts);
  ok(text.length > 0, "正常 facts 下段落非空（长度 " + text.length + "）");
  ok(/Host environment: DSH-Folk/.test(text), "含宿主身份段");
  ok(/Native capabilities/.test(text), "含安卓原生能力桥段（这次事故里连带消失的那部分）");

  const withWs = render({
    ...facts,
    workspaceStorageMounted: true,
    workspaceStorageMappings: [{ src: "Documents", dest: "sdcard" }],
    storageHardlinkSupported: false,
  });
  ok(/Phone storage inside the workspace/.test(withWs) && /EINVAL/.test(withWs),
    "工作区挂载开 + 不支持硬链接时，渲染出「write 会 EINVAL、改用 edit/shell」的说明");
  ok(render({ ...facts, workspaceStorageMounted: false }).length === text.length,
    "工作区挂载关时不渲染该段（与旧 facts 输出一致，向后兼容）");

  console.log(
    `\n${bad === 0 ? "\u2713 全部通过" : "\u2717 有失败"}：${n} 项断言，${bad} 项失败`
  );
  process.exit(bad === 0 ? 0 : 1);
})();
