#!/usr/bin/env node
/**
 * 会话归组助手（app/src/main/assets/dsh-session-group.cjs）的门禁。
 *
 * ## 为什么是「真跑」而不是断言字符串
 *
 * 这个助手要动两样一旦写坏就让 dsh **起不来**的东西：会话文件（`.jsonl.zstd` 是多帧容器，
 * header 必须单独成帧）与 `storages/workspace.json`（红线违反即 fail loud）。
 * 所以检查器搭一套真实夹具，把助手当子进程跑起来，再用**独立的**帧解析器验收：
 *
 *   - 一个 cwd 与本机工作区路径完全一致的会话 → 归组
 *   - 一个来自别的设备（cwd 是 /root/deepseek-harness）的会话 → 靠 --map 改写 header + 挪目录后归组
 *   - 一个「单帧里塞了 header + 批次」的坏会话 → 判 unreadable 并挪出 sessions 树
 *     （dsh 会以 first frame is not exactly one header line 拒绝它，而那个错误发生在
 *      workspace 插件 init 的 list() 里 —— 一个坏文件能把整个 dsh 启动拖垮）
 *   - 注册表本来就违反红线（两个工作区同一个 path）→ 一个字都不许写
 *
 * 独立验收的意思：检查器自己按 zstd 帧头规格切帧、自己解每一帧，不调用助手里的函数。
 */
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const zlib = require("node:zlib");
const { execFileSync } = require("node:child_process");

const HELPER = "app/src/main/assets/dsh-session-group.cjs";
const SRC_DB = "app/src/main/java/me/bmax/apatch/dsh/DshConfigBackup.kt";
const SRC_RT = "app/src/main/java/me/bmax/apatch/dsh/DshRuntime.kt";

let n = 0;
let bad = 0;
function ok(cond, label) {
  n++;
  if (cond) {
    console.log("  ✓ " + label);
  } else {
    bad++;
    console.log("  ✗ " + label);
  }
}

/* ─────────────────── 独立的 zstd 帧工具（检查器自己的） ─────────────────── */

const MAGIC = 0xfd2fb528;

function frameLength(buf, at = 0) {
  if (buf.length - at < 6) return -1;
  if (buf.readUInt32LE(at) !== MAGIC) return -1;
  const fhd = buf[at + 4];
  const fcsFlag = fhd >> 6;
  const single = (fhd >> 5) & 1;
  const checksum = (fhd >> 2) & 1;
  const dictFlag = fhd & 3;
  let off = at + 5;
  if (!single) off += 1;
  off += dictFlag === 0 ? 0 : dictFlag === 1 ? 1 : dictFlag === 2 ? 2 : 4;
  off += fcsFlag === 0 ? (single ? 1 : 0) : fcsFlag === 1 ? 2 : fcsFlag === 2 ? 4 : 8;
  for (;;) {
    if (off + 3 > buf.length) return -1;
    const b0 = buf[off];
    const last = b0 & 1;
    const type = (b0 >> 1) & 3;
    const size = (b0 >> 3) | (buf[off + 1] << 5) | (buf[off + 2] << 13);
    off += 3;
    if (type === 3) return -1;
    off += type === 1 ? 1 : size;
    if (off > buf.length) return -1;
    if (last) break;
  }
  if (checksum) off += 4;
  return off <= buf.length ? off - at : -1;
}

/** 把一个多帧文件切成 [{text}]，逐帧解压。 */
function splitFrames(buf) {
  const out = [];
  let at = 0;
  while (at < buf.length) {
    const len = frameLength(buf, at);
    if (len <= 0) return null;
    try {
      out.push(zlib.zstdDecompressSync(buf.subarray(at, at + len)).toString("utf8"));
    } catch {
      return null;
    }
    at += len;
  }
  return out;
}

function frame(text) {
  return zlib.zstdCompressSync(Buffer.from(text, "utf8"), {
    params: { [zlib.constants.ZSTD_c_checksumFlag]: 1 },
  });
}

/* ───────────────────────────── 夹具 ───────────────────────────── */

const root = fs.mkdtempSync(path.join(os.tmpdir(), "dsh-group-"));
const real = (p) => {
  const abs = path.join(root, p);
  fs.mkdirSync(abs, { recursive: true });
  return fs.realpathSync(abs);
};

const projA = real("proj-a");
const harness = real("harness");
const sessionsRoot = real("sessions");
const storages = real("storages");
const registry = path.join(storages, "workspace.json");
const pathsFile = path.join(root, "restored.txt");

const hdr = (id, cwd, extra = {}) => JSON.stringify({ id, cwd, delegationDepth: 0, ...extra }) + "\n";

function writeSession(rel, headerLine, batches) {
  const parts = [frame(headerLine)];
  for (const b of batches) parts.push(frame(b));
  const abs = path.join(sessionsRoot, rel);
  fs.mkdirSync(path.dirname(abs), { recursive: true });
  fs.writeFileSync(abs, Buffer.concat(parts));
  return abs;
}

// 既有会话：proj-a 下已归组的 s-a；harness 下已有一条（用于让助手认出该工作区的 projectKey 目录）
const existingA = writeSession("pkA/seg-a/session.jsonl.zstd", hdr("s-a", projA), ["batch-a\n"]);
const existingH = writeSession("pkH/seg-x/session.jsonl.zstd", hdr("s-h0", harness), ["batch-h0\n"]);

// 本次「恢复」进来的三个：一个本机路径、一个外机路径、一个坏文件
const restoredExact = writeSession("pkA/seg-b/session.jsonl.zstd", hdr("s-b", projA), ["batch-b1\n", "batch-b2\n"]);
const restoredForeignRel = "pkOld/seg-h/session.jsonl.zstd";
const restoredForeign = writeSession(restoredForeignRel, hdr("s-h", "/root/deepseek-harness"), ["batch-h1\n"]);
// 坏文件：header 与批次塞在**同一个帧**里（整文件压单帧的典型误写）
{
  const abs = path.join(sessionsRoot, "pkBad/seg-bad/session.jsonl.zstd");
  fs.mkdirSync(path.dirname(abs), { recursive: true });
  fs.writeFileSync(abs, frame(hdr("s-bad", projA) + "batch-bad\n"));
}

fs.writeFileSync(
  pathsFile,
  ["pkA/seg-b/session.jsonl.zstd", restoredForeignRel, "pkBad/seg-bad/session.jsonl.zstd"].join("\n") + "\n",
);

const registryDoc = {
  // 故意用带外层信封、带未知键的形状：助手必须原样保留它不认识的键
  version: 2,
  unknownTopLevel: { keepMe: true, nested: [1, 2, 3] },
  state: {
    initialized: true,
    workspaceIds: ["ws-a", "ws-h"],
    archivedSessionIds: ["archived-keep"],
  },
  workspaces: {
    "ws-a": {
      path: projA,
      title: "proj-a",
      sessionIds: ["s-a"],
      createdAt: "2026-09-01T00:00:00.000Z",
      updatedAt: "2026-09-01T00:00:00.000Z",
    },
    "ws-h": {
      path: harness,
      title: "harness",
      sessionIds: ["s-h0"],
      createdAt: "2026-09-02T00:00:00.000Z",
      updatedAt: "2026-09-02T00:00:00.000Z",
    },
  },
};
fs.writeFileSync(registry, JSON.stringify(registryDoc, null, 2) + "\n");

/* ───────────────────────────── 跑助手 ───────────────────────────── */

function runHelper(extraArgs) {
  const args = [
    HELPER,
    "--sessions-root", sessionsRoot,
    "--registry", registry,
    "--paths-file", pathsFile,
    ...extraArgs,
  ];
  let stdout = "";
  let code = 0;
  try {
    stdout = execFileSync(process.execPath, args, { encoding: "utf8" });
  } catch (e) {
    stdout = (e.stdout || "") + (e.stderr || "");
    code = e.status === undefined ? -1 : e.status;
  }
  const marker = stdout.split("\n").find((l) => l.startsWith("DSH_GROUP_REPORT "));
  return { code, report: marker ? JSON.parse(marker.slice("DSH_GROUP_REPORT ".length)) : null, raw: stdout };
}

console.log("─ 1. 预览（不加 --apply）不得写盘");
{
  const before = fs.readFileSync(registry, "utf8");
  const { code, report } = runHelper(["--map", `/root/deepseek-harness=${harness}`]);
  ok(report !== null && report.ok === true, "预览返回 ok 报告");
  ok(report.applied === false, "预览 applied=false");
  ok(code === 0, "预览退出码 0");
  ok(fs.readFileSync(registry, "utf8") === before, "预览没有改注册表一个字节");
  ok(report.rewritten === 0 && report.grouped === 0, "预览不做实际归属变更");
  ok(
    report.items.some((i) => i.action === "rewrite+group") && report.items.some((i) => i.action === "group"),
    "预览给出了 group 与 rewrite+group 两类计划",
  );
}

console.log("─ 2. 应用：改写 header、挪目录、归组、隔离坏文件");
let applied = null;
{
  const { code, report } = runHelper(["--map", `/root/deepseek-harness=${harness}`, "--apply"]);
  applied = report;
  ok(report !== null, "拿到了报告");
  ok(code === 0, "退出码 0（成功）");
  ok(report.applied === true && report.ok === true, "报告 applied/ok 均为 true");
  ok(report.total === 3, "处理 3 条待归组会话（实际 " + (report && report.total) + "）");
  ok(report.grouped === 2, "归组 2 条（实际 " + (report && report.grouped) + "）");
  ok(report.rewritten === 1, "改写 header 1 条（实际 " + (report && report.rewritten) + "）");
  ok(report.unreadable.length === 1, "识别出 1 个坏会话");
  ok(
    report.unreadable[0] && /not exactly one header line/.test(report.unreadable[0].reason),
    "坏会话的原因是「第 1 帧不止一行」（与 dsh 的报错同义）",
  );
  ok(report.quarantined.length === 1, "坏会话已被挪出 sessions 树");
  ok(report.problems.length === 0, "没有残留问题");
}

console.log("─ 3. 注册表：归属、顺序、未知键、归档集合");
{
  const doc = JSON.parse(fs.readFileSync(registry, "utf8"));
  const ids = doc.workspaces["ws-a"].sessionIds;
  ok(ids.includes("s-a") && ids.includes("s-b"), "ws-a 里既有原来的 s-a 也有新归组的 s-b");
  ok(doc.workspaces["ws-h"].sessionIds.includes("s-h"), "外机会话被归到 ws-h（靠 --map 映射）");
  ok(
    !doc.workspaces["ws-a"].sessionIds.includes("s-h") && !doc.workspaces["ws-h"].sessionIds.includes("s-b"),
    "没有会话同时属于两个工作区",
  );
  ok(
    doc.state.workspaceIds.join(",") === "ws-a,ws-h",
    "workspaceIds 顺序没被动（新建才需要 prepend，本次没有新建）",
  );
  ok(
    JSON.stringify(doc.state.archivedSessionIds) === JSON.stringify(["archived-keep"]),
    "archivedSessionIds 原样保留",
  );
  ok(
    doc.unknownTopLevel && doc.unknownTopLevel.keepMe === true && doc.unknownTopLevel.nested.length === 3,
    "不认识的键原样保留（外层信封由 dsh 决定，助手不假设）",
  );
  ok(doc.version === 2, "version 字段保留");
  ok(doc.workspaces["ws-a"].updatedAt !== registryDoc.workspaces["ws-a"].updatedAt, "归属变了就刷新 updatedAt");
  const backups = fs.readdirSync(storages).filter((f) => f.startsWith("workspace.json.bak-"));
  ok(backups.length === 1, "写盘前留了 1 个备份（" + backups.join(",") + "）");
}

console.log("─ 4. 会话文件：多帧结构必须保持，header 帧单独成帧");
{
  const moved = path.join(sessionsRoot, "pkH/seg-h/session.jsonl.zstd");
  ok(fs.existsSync(moved), "外机会话被挪到目标工作区的 projectKey 目录下（pkH/seg-h/…）");
  ok(!fs.existsSync(restoredForeign), "原位置的文件已删除");

  const frames = splitFrames(fs.readFileSync(moved));
  ok(frames !== null, "重写后的文件仍能被逐帧解出来（帧结构合法）");
  ok(frames && frames.length === 2, "帧数与原文件一致（header 帧 + 批次帧）");
  const headerText = frames ? frames[0] : "";
  ok(headerText.endsWith("\n") && headerText.indexOf("\n") === headerText.length - 1, "第 1 帧恰好一行且以换行结尾");
  const header = frames ? JSON.parse(headerText.trim()) : {};
  ok(header.id === "s-h", "header 的 id 保持不变");
  ok(header.cwd === harness, "header 的 cwd 已改写为目标工作区路径");
  ok(header.delegationDepth === 0, "header 的其它字段原样保留");
  ok(frames && frames[1] === "batch-h1\n", "第 2 帧（批次）字节级原样保留");

  const exact = splitFrames(fs.readFileSync(restoredExact));
  ok(exact && exact.length === 3, "本机路径那条会话没有被重写（3 帧原样）");

  const invalidRoot = path.join(root, "sessions-invalid");
  const movedBad = fs.existsSync(invalidRoot)
    ? fs.readdirSync(invalidRoot).flatMap((stamp) =>
        fs.readdirSync(path.join(invalidRoot, stamp), { withFileTypes: true })
          .filter((d) => d.isDirectory())
          .map((d) => path.join(invalidRoot, stamp, d.name, "seg-bad/session.jsonl.zstd")),
      )
    : [];
  ok(movedBad.some((p) => fs.existsSync(p)), "坏会话被挪到 sessions-invalid/<时间戳>/ 下");
  ok(!fs.existsSync(path.join(sessionsRoot, "pkBad/seg-bad/session.jsonl.zstd")), "坏会话已离开 sessions 树");
}

console.log("─ 5. 注册表本来就坏时：一个字都不许写");
{
  const badRoot = real("bad-case");
  const badSessions = real("bad-case/sessions");
  const badStorages = real("bad-case/storages");
  const badRegistry = path.join(badStorages, "workspace.json");
  const p1 = real("bad-case/p1");
  fs.writeFileSync(
    badRegistry,
    JSON.stringify(
      {
        state: { initialized: true, workspaceIds: ["x", "y"], archivedSessionIds: [] },
        workspaces: {
          x: { path: p1, title: "x", sessionIds: [], createdAt: "t", updatedAt: "t" },
          y: { path: p1, title: "y", sessionIds: [], createdAt: "t", updatedAt: "t" },
        },
      },
      null,
      2,
    ) + "\n",
  );
  fs.mkdirSync(path.join(badSessions, "pk/seg"), { recursive: true });
  fs.writeFileSync(
    path.join(badSessions, "pk/seg/session.jsonl.zstd"),
    Buffer.concat([frame(hdr("s-x", p1)), frame("b\n")]),
  );
  const listFile = path.join(badRoot, "list.txt");
  fs.writeFileSync(listFile, "pk/seg/session.jsonl.zstd\n");
  const before = fs.readFileSync(badRegistry, "utf8");
  let report = null;
  try {
    execFileSync(
      process.execPath,
      [HELPER, "--sessions-root", badSessions, "--registry", badRegistry, "--paths-file", listFile, "--apply"],
      { encoding: "utf8" },
    );
  } catch (e) {
    const out = (e.stdout || "").toString();
    const marker = out.split("\n").find((l) => l.startsWith("DSH_GROUP_REPORT "));
    report = marker ? JSON.parse(marker.slice("DSH_GROUP_REPORT ".length)) : null;
  }
  ok(report !== null && report.ok === false, "坏注册表 → 报告 ok=false");
  ok(report && report.skipped.length > 0, "并给出跳过原因：" + (report ? report.skipped : ""));
  ok(fs.readFileSync(badRegistry, "utf8") === before, "坏注册表一个字节都没被改");
  ok(report && report.applied === false, "applied=false");
}

console.log("─ 6. 未 bootstrap 的注册表：交给 dsh 自己归组，不插手");
{
  const r2 = real("uninit");
  const s2 = real("uninit/sessions");
  const st2 = real("uninit/storages");
  const reg2 = path.join(st2, "workspace.json");
  fs.writeFileSync(reg2, JSON.stringify({ state: { initialized: false, workspaceIds: [], archivedSessionIds: [] }, workspaces: {} }) + "\n");
  const before = fs.readFileSync(reg2, "utf8");
  const { report } = runHelper0(s2, reg2, null, []);
  ok(report && report.ok === true && /not initialized/.test(report.skipped), "报「未初始化，跳过」");
  ok(fs.readFileSync(reg2, "utf8") === before, "注册表未被改动");

  function runHelper0(sessionsRootArg, registryArg, pathsArg, extra) {
    const args = [HELPER, "--sessions-root", sessionsRootArg, "--registry", registryArg, ...extra];
    if (pathsArg) args.push("--paths-file", pathsArg);
    let stdout = "";
    try {
      stdout = execFileSync(process.execPath, args, { encoding: "utf8" });
    } catch (e) {
      stdout = (e.stdout || "") + (e.stderr || "");
    }
    const marker = stdout.split("\n").find((l) => l.startsWith("DSH_GROUP_REPORT "));
    return { report: marker ? JSON.parse(marker.slice("DSH_GROUP_REPORT ".length)) : null };
  }
}

console.log("─ 7. 助手自身约定");
{
  const src = fs.readFileSync(HELPER, "utf8");
  ok(/DSH_GROUP_REPORT /.test(src), "报告有固定前缀，App 可解析");
  ok(/ZSTD_c_checksumFlag/.test(src), "压缩帧开了 checksum（与 dsh 写 header 帧一致）");
  ok(
    /first frame is not exactly one header line/.test(src),
    "坏文件的判据与 dsh 的报错同义",
  );
  ok(/sessions-invalid/.test(src), "坏文件隔离目录名固定");
  ok(/workspace\.json\.bak-/.test(src), "备份文件名固定");
  ok(
    !/zstdCompressSync\(\s*fs\.readFileSync/.test(src),
    "没有「整文件压一帧」这种写法（那正是踩过的坑）",
  );
  ok(/validateRegistry/.test(src) && /rolledBack/.test(src), "有红线自校验与回滚路径");
}

console.log("─ 8. App 侧接线（session 恢复后必须走停机 → 归组 → 起服务）");
{
  const backup = fs.readFileSync(SRC_DB, "utf8");
  const runtime = fs.readFileSync(SRC_RT, "utf8");
  const group = fs.readFileSync("app/src/main/java/me/bmax/apatch/dsh/DshSessionGroup.kt", "utf8");
  ok(/dsh-session-group\.cjs/.test(group), "DshSessionGroup 引用 assets 里的助手");
  ok(/DSH_GROUP_REPORT /.test(group), "App 按固定前缀解析助手报告");
  ok(/SESSIONS_ROOT = "\/root\/.dsh\/sessions"/.test(group) && /REGISTRY = "\/root\/.dsh\/storages\/workspace.json"/.test(group),
    "容器内路径与会话/注册表位置一致");
  ok(/withServiceStopped/.test(backup), "DshConfigBackup 用「停服务时执行」包住归组");
  ok(/DshSessionGroup\.groupRestoredSessions\(ctx, r\.paths/.test(backup), "只归组本次恢复的那些会话（r.paths）");
  ok(
    /data class SessionRestore\([\s\S]{0,500}val paths: List<String>/.test(backup),
    "restoreSessionsFromZip 交出写盘清单",
  );
  ok(/withServiceStopped/.test(runtime), "DshRuntime 提供「停服务时执行」的原语");
  ok(
    /stopServer\(\)[\s\S]{0,400}startAndAwait\(\)/.test(runtime),
    "该原语内部先停后起（异常也必须恢复服务）",
  );
  ok(/restoreSessionsFromZip/.test(backup), "会话落盘函数仍在（归组在它之后）");

  // 归组回调必须是 suspend：DshConfigBackup.import 的 onLine 是 suspend 的，
  // 少写一个 suspend 就是一次编译失败（beta run 34764596409 就是这么挂的）
  ok(
    (group.match(/onLine: suspend \(String\) -> Unit/g) || []).length === 3,
    "两个公开入口 + 私有 run 的 onLine 都是 suspend 回调",
  );
  ok(/pending \+= trimmed/.test(group), "助手输出先缓冲再发出（execRootfsStreaming 的回调不是挂起上下文）");

  // 旧版本导入进来的会话：文件已存在 → 再导入会被跳过，必须有一个主动整理入口
  const content = fs.readFileSync("app/src/main/java/me/bmax/apatch/ui/screen/settings/BackupSettings.kt", "utf8");
  const screen = fs.readFileSync("app/src/main/java/me/bmax/apatch/ui/screen/settings/BackupSettingsScreen.kt", "utf8");
  ok(/DshSessionGroup\.tidyAllSessions/.test(screen), "界面调用 DshSessionGroup.tidyAllSessions（全树整理入口）");
  ok(/relPaths: List<String>\?/.test(group) && /if \(relPaths != null\) append\(" --paths-file/.test(group),
    "不给 --paths-file 即扫全树（助手侧据此决定范围）");
  ok(/dsh_bk_tidy_sessions/.test(content) && /onTidySessions/.test(content), "备份页有「整理未分组会话」按钮");
  ok(/onTidySessions = \{[\s\S]{0,1200}withServiceStopped/.test(screen), "整理动作在服务停止时执行");
  ok(/DshSessionGroup\.tidyAllSessions\(context\)/.test(screen), "界面调的是 tidyAllSessions（全树）");

  // 助手侧：没给 --paths-file 时必须扫全树
  const helper = fs.readFileSync(HELPER, "utf8");
  ok(/relPaths = \[\];[\s\S]{0,300}walk\(sessionsRoot/.test(helper), "助手在没有清单时扫全树");
}

fs.rmSync(root, { recursive: true, force: true });
console.log(bad === 0 ? `\n全部通过（${n} 项断言）` : `\n${bad}/${n} 项失败`);
process.exit(bad === 0 ? 0 : 1);
