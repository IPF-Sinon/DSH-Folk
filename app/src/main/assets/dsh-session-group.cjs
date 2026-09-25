#!/usr/bin/env node
/**
 * dsh-session-group —— 把「刚被恢复进来的会话」放回它该在的工作区。
 *
 * ## 为什么需要它
 *
 * dsh 的工作区分组**只在注册表首次 bootstrap 时做一次**
 * （`@deepseek-ai/dsh-workspace` 的 `Service.init()`：`if (!state.initialized) bootstrap(headers)`）。
 * 之后放进 sessions 树的会话永远不会被归组，一直显示「未分组」，而 GUI 里没有
 * 从未分组移进工作区的入口。
 *
 * 更麻烦的是：**光把 session id 塞进 `workspace.json` 的 `sessionIds` 也没用**。
 * 成员判定是 `host.sessionPath(id) === record.path`，而 `sessionPath(id)` 由会话 header
 * 里的 `cwd` 反推；不相等时那条会话会被 `reportFilteredCandidates()` 过滤掉，只在日志里
 * 留一句 `canonical cwd '<a>' differs from workspace path '<b>'`。
 *
 * 所以真正归组要同时满足三件事：
 *   1. 文件在 `<sessions-root>/<projectKey(cwd)>/<encodeSegment(id)>/session.jsonl.zstd`；
 *   2. header 的 cwd（realpath 规范化后）等于工作区记录的 path；
 *   3. id 出现在该工作区记录的 `sessionIds` 里，且不在别的工作区里。
 *
 * ## 会话文件格式（踩过的坑）
 *
 * `.jsonl.zstd` 是**多帧拼接容器**，不是「整文件压一下」：
 *   - 第 1 帧：只含 SessionHeader 一行（解压后恰好一行、`\n` 结尾，加载时硬校验）
 *   - 第 2 帧起：每个持久化批次一帧
 * 所以改写 header **不能**整文件重压，只能替换第 1 帧、把后面的字节原样接回去。
 * 本脚本按 zstd 帧头规格算出第 1 帧的字节长度（不必解压），替换后再用两种方式自证：
 * 单独解第 1 帧必须恰好是那一行；整体解压结果与替换前逐字节相同。任一自证不过就放弃改写。
 *
 * ## 保守性（每一条都是「宁可不动」）
 *
 * - 注册表读不出来 / 自校验不过 → 一个字都不写；
 * - 找不到目标工作区的 projectKey 目录 → 不猜，报 ungrouped；
 * - 改写自证不过 → 不猜，报 ungrouped；
 * - 写盘前备份，写盘后重新读回来自校验，任一条红线不过 → 回滚并报错（退出码 2）；
 * - 注册表里未知的键、其它会话、`archivedSessionIds`、`pendingMutation` 一律原样保留。
 *
 * ## 用法
 *
 *   node dsh-session-group.cjs --sessions-root <dir> --registry <workspace.json>
 *        [--paths-file <file>]        # 本次恢复的会话相对路径（每行一个）；省略=扫全树
 *        [--map <cwd>=<targetPath>]...  # 源设备 cwd → 本机工作区路径（精确匹配单条 cwd）
 *        [--rebase <oldHome>=<newHome>]...  # 基础路径前缀重定基（段边界匹配，可多条）
 *        [--apply]                    # 不加则只出计划，不写盘
 *
 * 退出码：0 成功（含「无需要归组的」）；1 参数/环境错误；2 应用后自校验失败并已回滚。
 * 报告以 `DSH_GROUP_REPORT <json>` 单行输出。
 */
"use strict";

const fs = require("node:fs");
const path = require("node:path");
const zlib = require("node:zlib");

const REPORT_PREFIX = "DSH_GROUP_REPORT ";
/**
 * 会话日志文件名。
 *
 * 不能写死一个名字：dsh 的新格式是 \`session.v3.jsonl.zstd\`（旧的是 \`session.jsonl.zstd\`），
 * 只认旧名字会让新格式的会话在「整树扫描」里**完全看不见**，而搬迁时写死目标名还会把
 * 新格式的文件改成旧名字（内容没变、名字变了，读它的 dsh 就不再确认它是什么）。
 * 锁文件 \`session.lock\` 不匹配：它不是会话数据。
 */
const SESSION_FILE_RE = /^session(\.[A-Za-z0-9]+)*\.jsonl(\.zstd)?$/;
const SESSION_FILE = "session.jsonl.zstd";
const isSessionFile = (name) => SESSION_FILE_RE.test(name);
const ZSTD_MAGIC = 0xfd2fb528;

/* ────────────────────────────── 参数 ────────────────────────────── */

function parseArgs(argv) {
  const out = { maps: [], rebases: [], apply: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    switch (a) {
      case "--sessions-root": out.sessionsRoot = argv[++i]; break;
      case "--registry": out.registry = argv[++i]; break;
      case "--paths-file": out.pathsFile = argv[++i]; break;
      case "--map": out.maps.push(argv[++i]); break;
      case "--rebase": out.rebases.push(argv[++i]); break;
      case "--apply": out.apply = true; break;
      default:
        if (a.startsWith("--")) throw new Error(`unknown option ${a}`);
    }
  }
  if (!out.sessionsRoot) throw new Error("--sessions-root is required");
  if (!out.registry) throw new Error("--registry is required");
  return out;
}

/* ─────────────────────────── 会话文件 ─────────────────────────── */

/** 解出会话文件第 1 帧那一行（SessionHeader）；格式不对返回 null。 */
async function readHeaderLine(file) {
  let acc = "";
  const rs = fs.createReadStream(file, { highWaterMark: 1 << 16 });
  const zs = zlib.createZstdDecompress();
  rs.pipe(zs);
  try {
    for await (const chunk of zs) {
      acc += chunk.toString("utf8");
      const nl = acc.indexOf("\n");
      if (nl >= 0) return acc.slice(0, nl);
      if (acc.length > 1 << 22) return null;
    }
  } catch {
    return null;
  } finally {
    zs.destroy();
    rs.destroy();
  }
  // 走到这里说明整文件解完都没有换行 —— 正是「不是恰好一行」那种坏文件
  return null;
}

/**
 * 严格校验一个会话文件：第 1 帧解出来必须**恰好**是一行 header（以 \n 结尾、后面没有别的字节）。
 *
 * 为什么不能用 readHeaderLine 代替：它读到第一个换行就返回，而「单帧里塞了 header + 批次」
 * 这种坏文件照样能读出 header —— dsh 的加载器则会以
 * `first frame is not exactly one header line` 拒绝，而这个错误发生在 workspace 插件 init 的
 * list() 里，会把整个 dsh 启动拖垮。要提前拦住它，就必须把第 1 帧**完整**解出来看。
 */
function inspectSessionFile(file) {
  let buf;
  try {
    buf = fs.readFileSync(file);
  } catch (e) {
    return { header: null, reason: "cannot read file: " + e.message };
  }
  const len = firstFrameLength(buf);
  if (len <= 0) return { header: null, reason: "cannot parse the first zstd frame" };
  let text;
  try {
    text = zlib.zstdDecompressSync(buf.subarray(0, len)).toString("utf8");
  } catch {
    return { header: null, reason: "the first frame does not decompress" };
  }
  if (!text.endsWith("\n")) return { header: null, reason: "the first frame does not end with a newline" };
  if (text.indexOf("\n") !== text.length - 1) {
    return { header: null, reason: "first frame is not exactly one header line" };
  }
  let header;
  try {
    header = JSON.parse(text.slice(0, -1));
  } catch {
    return { header: null, reason: "the header line is not JSON" };
  }
  if (
    header === null || typeof header !== "object" ||
    typeof header.id !== "string" || typeof header.cwd !== "string"
  ) {
    return { header: null, reason: "the header line lacks id/cwd" };
  }
  return { header, reason: "" };
}

/**
 * 第 1 个 zstd 帧的字节长度（按帧头规格算，不解压）。算不出来返回 -1。
 */
function firstFrameLength(buf) {
  if (buf.length < 6) return -1;
  if (buf.readUInt32LE(0) !== ZSTD_MAGIC) return -1;
  const fhd = buf[4];
  const fcsFlag = fhd >> 6;
  const singleSegment = (fhd >> 5) & 1;
  const checksum = (fhd >> 2) & 1;
  const dictFlag = fhd & 3;
  let off = 5;
  if (!singleSegment) off += 1;
  off += dictFlag === 0 ? 0 : dictFlag === 1 ? 1 : dictFlag === 2 ? 2 : 4;
  off += fcsFlag === 0 ? (singleSegment ? 1 : 0) : fcsFlag === 1 ? 2 : fcsFlag === 2 ? 4 : 8;
  if (off > buf.length) return -1;
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
  return off <= buf.length ? off : -1;
}

/** 单帧压一段文本（开 checksum，与 dsh 写 header 帧一致）。 */
function compressFrame(text) {
  return zlib.zstdCompressSync(Buffer.from(text, "utf8"), {
    params: { [zlib.constants.ZSTD_c_checksumFlag]: 1 },
  });
}

/**
 * 把会话文件 header 的 cwd 换成 newCwd；返回新 Buffer，任何自证不过返回 null。
 */
function rewriteHeader(file, newCwd) {
  const original = fs.readFileSync(file);
  const frameLen = firstFrameLength(original);
  if (frameLen <= 0) return null;
  const rest = original.subarray(frameLen);

  let header;
  try {
    header = JSON.parse(zlib.zstdDecompressSync(original.subarray(0, frameLen)).toString("utf8"));
  } catch {
    return null;
  }
  if (header === null || typeof header !== "object" || Array.isArray(header)) return null;
  header.cwd = newCwd;
  const line = JSON.stringify(header) + "\n";
  const rebuilt = Buffer.concat([compressFrame(line), rest]);

  // 自证 1：第 1 帧单解出来必须恰好是那一行
  const newFrameLen = firstFrameLength(rebuilt);
  if (newFrameLen <= 0) return null;
  try {
    if (zlib.zstdDecompressSync(rebuilt.subarray(0, newFrameLen)).toString("utf8") !== line) return null;
  } catch {
    return null;
  }
  // 自证 2：第 1 帧之后的**字节**必须与原文完全一致，并且那一段自己的第 1 帧
  // 还要能正常解出来 —— 帧长算错时 remainder 会错位，这一步就会失败。
  // 注意：不能用整个文件 zstdDecompressSync 来比，Node 的 zstd 只解第一个帧
  // （不像 gzip 会拼接），那样比出来的是同一行、等于没验。
  if (!rebuilt.subarray(newFrameLen).equals(original.subarray(frameLen))) return null;
  const restFrameLen = firstFrameLength(original.subarray(frameLen));
  if (original.length > frameLen) {
    if (restFrameLen <= 0) return null;
    try {
      zlib.zstdDecompressSync(original.subarray(frameLen, frameLen + restFrameLen));
    } catch {
      return null;
    }
  }
  return rebuilt;
}

/* ──────────────────────────── 注册表 ──────────────────────────── */

/**
 * 注册表的外层信封由 dsh 的 storage domain 决定，本脚本不假设它：
 * 在文档里**就地**找出「全局态」与「工作区表」两个对象，其余键原样保留。
 */
function findRegistryParts(doc) {
  let table = null;
  let state = null;
  const isRecord = (v) =>
    v !== null && typeof v === "object" && !Array.isArray(v) &&
    typeof v.path === "string" && Array.isArray(v.sessionIds);
  const walk = (node) => {
    if (node === null || typeof node !== "object" || Array.isArray(node)) return;
    if (
      state === null &&
      Array.isArray(node.workspaceIds) &&
      typeof node.initialized === "boolean"
    ) {
      state = node;
    }
    for (const [k, v] of Object.entries(node)) {
      if (v === null || typeof v !== "object" || Array.isArray(v)) continue;
      const vals = Object.values(v);
      // 表容器：值全是记录。空对象只有在键名就是 workspaces 时才认（否则会误认别的空对象）
      if (table === null && (vals.length > 0 ? vals.every(isRecord) : k === "workspaces")) {
        table = v;
        continue;
      }
      walk(v);
    }
  };
  walk(doc);
  return { table, state };
}

function canonical(p) {
  try {
    return fs.realpathSync(p);
  } catch {
    return null;
  }
}

/** 归组不变量 + 上游 validateStoredState 的等价校验；返回问题清单（空=通过）。 */
function validateRegistry(parts) {
  const { table, state } = parts;
  const problems = [];
  if (!state) problems.push("registry has no global state (workspaceIds/initialized)");
  if (!table) problems.push("registry has no workspaces table");
  if (problems.length) return problems;
  const order = new Set();
  for (const id of state.workspaceIds) {
    if (order.has(id)) problems.push(`registry order repeats workspace '${id}'`);
    order.add(id);
    if (!(id in table)) problems.push(`registry order references missing workspace '${id}'`);
  }
  for (const id of Object.keys(table)) {
    if (!order.has(id)) problems.push(`workspace '${id}' is missing from registry order`);
  }
  const paths = new Map();
  const owners = new Map();
  for (const [id, rec] of Object.entries(table)) {
    if (paths.has(rec.path)) {
      problems.push(`two workspaces share path '${rec.path}' (${paths.get(rec.path)} / ${id})`);
    }
    paths.set(rec.path, id);
    for (const sid of rec.sessionIds) {
      if (owners.has(sid)) problems.push(`session '${sid}' is in two workspaces`);
      owners.set(sid, id);
    }
  }
  return problems;
}

/* ──────────────────────────── 主流程 ──────────────────────────── */

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const sessionsRoot = path.resolve(args.sessionsRoot);
  const report = {
    ok: false,
    applied: false,
    sessionsRoot,
    /** 处理过的日志文件数。 */
    total: 0,
    /** 同上（显式命名以便读出「会话数 vs 文件数」的区别）。 */
    files: 0,
    grouped: 0,
    /** 归组成功的**会话**数（同一会话的新旧两个文件只算一条）。 */
    groupedSessions: 0,
    /** 同一会话的第二个及以后的日志文件数。 */
    duplicateFiles: 0,
    /** 调用方递进来的路径里不是会话日志的那些（session.lock 等），原样留在原处。 */
    ignoredNonSession: [],
    moved: 0,
    rewritten: 0,
    inferred: [],
    ungrouped: [],
    unreadable: [],
    quarantined: [],
    items: [],
    problems: [],
    skipped: "",
  };

  if (!fs.existsSync(sessionsRoot)) {
    report.skipped = `sessions root not found: ${sessionsRoot}`;
    report.ok = true; // 良性跳过：没东西可归组，不是失败
    return report;
  }
  if (typeof zlib.zstdDecompressSync !== "function" || typeof zlib.createZstdDecompress !== "function") {
    report.skipped = "容器内 Node 的 zlib 不支持 zstd（运行时过旧），跳过归组";
    report.ok = true; // 良性跳过
    return report;
  }

  /* 注册表 */
  const registryExisted = fs.existsSync(args.registry);
  let doc;
  if (registryExisted) {
    try {
      doc = JSON.parse(fs.readFileSync(args.registry, "utf8"));
    } catch (e) {
      report.skipped = `registry is not readable JSON: ${e.message}`;
      report.refused = true;
      return report;
    }
  } else {
    doc = { initialized: true, workspaceIds: [], archivedSessionIds: [] };
  }
  const parts = findRegistryParts(doc);
  // 还没 bootstrap 过：dsh 自己会按 cwd 归组，我们插手反而多余。
  // 必须在严格校验之前判 —— 未初始化的注册表本来就可能没有工作区表。
  if (parts.state && parts.state.initialized === false) {
    report.skipped = "registry is not initialized yet (dsh will bootstrap and group by itself)";
    report.ok = true; // 良性跳过：dsh 自己会归组
    return report;
  }
  const before = validateRegistry(parts);
  if (before.length) {
    report.problems = before;
    report.skipped = "registry failed self-validation before any change";
    report.refused = true;
    return report;
  }
  const { table, state } = parts;
  /**
   * 调用方递进来的路径里不是会话日志的那些（session.lock 等）。
   * 原样留在原处，既不解析也不隔离 —— 真机上 6 个锁文件曾被当成坏会话搬走。
   */
  const ignoredNonSession = [];
  // 同一个数组引用，后面 push 的内容会出现在报告里
  report.ignoredNonSession = ignoredNonSession;

  /* 待处理清单 */
  let relPaths;
  if (args.pathsFile) {
    // 调用方给的是「本次恢复进来的文件」，可能混进 session.lock 这类运行时文件。
    // 以前它们会被当成坏会话：报「不可读」并**挪出 sessions 树**（真机上 6 个锁文件
    // 就是这么被搬走的）。这里先按文件名过滤，非会话文件原样留在原处。
    relPaths = fs
      .readFileSync(args.pathsFile, "utf8")
      .split("\n")
      .map((s) => s.trim())
      .filter(Boolean)
      .filter((rel) => {
        if (isSessionFile(path.basename(rel))) return true;
        ignoredNonSession.push(rel);
        return false;
      });
  } else {
    relPaths = [];
    const walk = (dir, rel) => {
      for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
        const r = rel ? `${rel}/${e.name}` : e.name;
        if (e.isDirectory()) walk(path.join(dir, e.name), r);
        else if (isSessionFile(e.name)) relPaths.push(r);
      }
    };
    walk(sessionsRoot, "");
  }

  /* 索引：把所有会话的 header 读出来（只为定位 projectKey 目录与已有归属） */
  const index = [];
  const walkAll = (dir, rel) => {
    let entries = [];
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of entries) {
      const r = rel ? `${rel}/${e.name}` : e.name;
      if (e.isDirectory()) walkAll(path.join(dir, e.name), r);
      else if (isSessionFile(e.name)) index.push({ rel: r, file: path.join(dir, e.name) });
    }
  };
  walkAll(sessionsRoot, "");

  const maps = new Map();
  for (const m of args.maps) {
    const eq = m.lastIndexOf("=");
    if (eq > 0) maps.set(m.slice(0, eq), m.slice(eq + 1));
  }

  // 基础路径前缀重定基（与 dsh-config-manager 0.1.64 的 rebaseMapping 同一口径）：
  // 导出机 DSH home ≠ 本机时，把落在导出机基础路径之下的会话 cwd 换成「本机基础路径 + 同一
  // 后缀」。插件已对结构化分区（workspaces.path 等）做了同样的重定基，会话首帧 cwd 也必须跟着
  // 改，否则归组时 cwd（源机）与 workspace.path（已重定基）对不上，会话全落单。
  // 只在段边界匹配（/opt/.dsh 不误伤 /opt/.dsh-extra），两侧都去尾部分隔符。
  const rebases = [];
  for (const r of args.rebases) {
    const eq = r.lastIndexOf("=");
    if (eq <= 0) continue;
    const strip = (v) => v.replace(/\\/g, "/").replace(/\/+$/, "");
    const from = strip(r.slice(0, eq));
    const to = strip(r.slice(eq + 1));
    if (from && to && from !== to) rebases.push({ from, to });
  }
  // cwd 命中某条 rebase 前缀 → 返回重定基后的路径；都不命中返回原值。
  const rebaseCwd = (cwd) => {
    if (typeof cwd !== "string" || cwd === "") return cwd;
    const norm = cwd.replace(/\\/g, "/");
    for (const { from, to } of rebases) {
      if (norm === from) return to;
      if (norm.startsWith(from + "/")) return to + norm.slice(from.length);
    }
    return cwd;
  };

  const pathToId = new Map();
  const baselineIds = new Map(); // basename -> [workspaceId]
  for (const [id, rec] of Object.entries(table)) {
    pathToId.set(rec.path, id);
    const b = path.basename(rec.path);
    baselineIds.set(b, (baselineIds.get(b) ?? []).concat([id]));
  }

  /** 目标工作区的 projectKey 目录：取任何一条 cwd 规范化后等于该 path 的会话的目录名。 */
  const projectKeyCache = new Map();
  const projectKeyFor = (wsPath) => {
    if (projectKeyCache.has(wsPath)) return projectKeyCache.get(wsPath);
    let found = null;
    for (const entry of index) {
      if (path.dirname(entry.rel).split("/").length < 2) continue;
      const h = headerCache.get(entry.file);
      if (!h) continue;
      if (canonical(h.cwd) === wsPath) {
        found = entry.rel.split("/")[0];
        break;
      }
    }
    projectKeyCache.set(wsPath, found);
    return found;
  };

  const headerCache = new Map();
  const headerOf = async (entry) => {
    if (headerCache.has(entry.file)) return headerCache.get(entry.file);
    const line = await readHeaderLine(entry.file);
    let parsed = null;
    if (line !== null) {
      try {
        const h = JSON.parse(line);
        if (h && typeof h === "object" && typeof h.id === "string" && typeof h.cwd === "string") parsed = h;
      } catch {
        parsed = null;
      }
    }
    headerCache.set(entry.file, parsed);
    return parsed;
  };

  /* 但要先建索引再能查 projectKey —— 先把全部 header 读一遍 */
  for (const entry of index) await headerOf(entry);

  /* 逐条处理 */
  const seenIds = new Set();
  for (const rel of relPaths) {
    const file = path.join(sessionsRoot, rel);
    report.total++;
    report.files++;
    if (!fs.existsSync(file)) {
      report.ungrouped.push({ path: rel, reason: "file missing" });
      continue;
    }
    // 待归组的会话走严格校验：单帧两行这类坏文件必须在这里拦下（dsh 会因此起不来）
    const inspected = inspectSessionFile(file);
    if (inspected.header === null) {
      report.unreadable.push({ path: rel, reason: inspected.reason });
      continue;
    }
    const header = inspected.header;
    headerCache.set(file, header);
    const sid = header.id;
    // 一个会话可能有新旧两个日志文件（header 里的 cwd 两边都有，迁移时必须一起改写），
    // 但摘要里要报的是**会话数**：以前按文件数报出「9/15 条」，读起来像有 9 个会话。
    const firstOfSession = !seenIds.has(sid);
    if (firstOfSession) seenIds.add(sid);
    else report.duplicateFiles++;
    const cwd = header.cwd;

    let targetId = null;
    let targetPath = null;
    let how = "exact";
    const canonicalCwd = canonical(cwd);
    if (canonicalCwd !== null && pathToId.has(canonicalCwd)) {
      targetId = pathToId.get(canonicalCwd);
      targetPath = canonicalCwd;
    } else {
      const mapped = maps.get(cwd);
      if (mapped !== undefined) {
        const c = canonical(mapped);
        if (c !== null && pathToId.has(c)) {
          targetId = pathToId.get(c);
          targetPath = c;
          how = "mapped";
        }
      }
      // 基础路径前缀重定基：源机 cwd 落在导出机 DSH home 之下时，换成本机 home + 同后缀，
      // 再与本机工作区路径比一遍（插件已对 workspace.path 做过同样重定基，两边这才对得上）。
      if (targetId === null && rebases.length) {
        const rebased = rebaseCwd(cwd);
        if (rebased !== cwd) {
          const c = canonical(rebased);
          if (c !== null && pathToId.has(c)) {
            targetId = pathToId.get(c);
            targetPath = c;
            how = "rebased";
          } else if (pathToId.has(rebased)) {
            targetId = pathToId.get(rebased);
            targetPath = rebased;
            how = "rebased";
          }
        }
      }
      if (targetId === null) {
        const ids = baselineIds.get(path.basename(cwd)) ?? [];
        if (ids.length === 1) {
          targetId = ids[0];
          targetPath = table[ids[0]].path;
          how = "inferred";
        }
      }
    }
    if (targetId === null) {
      report.ungrouped.push({ path: rel, id: sid, cwd, reason: "没有工作区路径与这个会话的 cwd 对应" });
      continue;
    }

    const needsRewrite = canonicalCwd !== targetPath;
    let destRel = rel;
    if (needsRewrite) {
      if (!args.apply) {
        report.items.push({ id: sid, action: "rewrite+group", workspace: targetId, how, from: cwd, to: targetPath });
        continue;
      }
      const rebuilt = rewriteHeader(file, targetPath);
      if (rebuilt === null) {
        report.ungrouped.push({ path: rel, id: sid, cwd, reason: "无法安全改写 header 帧" });
        continue;
      }
      const pk = projectKeyFor(targetPath);
      if (pk === null) {
        report.ungrouped.push({ path: rel, id: sid, cwd, reason: "找不到目标工作区的 projectKey 目录（不敢猜）" });
        continue;
      }
      const seg = path.basename(path.dirname(rel)); // encodeSegment(id) 只依赖 id，跨设备一致
      // 保留原文件名：把 session.v3.jsonl.zstd 写成 session.jsonl.zstd 等于偷偷换了格式名
      destRel = `${pk}/${seg}/${path.basename(rel)}`;
      const dest = path.join(sessionsRoot, destRel);
      fs.mkdirSync(path.dirname(dest), { recursive: true });
      fs.writeFileSync(dest, rebuilt);
      if (path.resolve(dest) !== path.resolve(file)) fs.rmSync(file, { force: true });
      headerCache.set(dest, { ...header, cwd: targetPath });
      report.rewritten++;
    }

    if (!args.apply) {
      report.items.push({ id: sid, action: needsRewrite ? "rewrite+group" : "group", workspace: targetId, how });
      continue;
    }

    if (how === "inferred") {
      report.inferred.push({ id: sid, from: cwd, to: targetPath, workspace: targetId });
    }
    // 一个会话只能属于一个工作区：从别处摘掉（等价 move）
    for (const [wid, rec] of Object.entries(table)) {
      if (wid !== targetId && rec.sessionIds.includes(sid)) {
        rec.sessionIds = rec.sessionIds.filter((x) => x !== sid);
        report.moved++;
      }
    }
    if (!table[targetId].sessionIds.includes(sid)) table[targetId].sessionIds.push(sid);
    table[targetId].updatedAt = new Date().toISOString();
    report.grouped++;
    if (firstOfSession) report.groupedSessions++;
    report.items.push({
      id: sid,
      action: needsRewrite ? "rewrite+group" : "group",
      workspace: targetId,
      how,
      path: destRel,
    });
  }

  /* 预览：不写盘 */
  if (!args.apply) {
    report.ok = true;
    return report;
  }

  /* 落盘 + 自校验 + 回滚 */
  const backupPath = path.join(path.dirname(args.registry), `workspace.json.bak-${Date.now()}`);
  try {
    const problemsAfterMutation = validateRegistry(findRegistryParts(doc));
    if (problemsAfterMutation.length) {
      report.problems = problemsAfterMutation;
      throw new Error("mutation would break registry invariants");
    }
    if (registryExisted) fs.copyFileSync(args.registry, backupPath);
    fs.mkdirSync(path.dirname(args.registry), { recursive: true });
    fs.writeFileSync(args.registry, JSON.stringify(doc, null, 2) + "\n");
    const reread = JSON.parse(fs.readFileSync(args.registry, "utf8"));
    const problems = validateRegistry(findRegistryParts(reread));
    if (problems.length) {
      report.problems = problems;
      throw new Error("written registry failed re-validation");
    }
    report.backup = registryExisted ? backupPath : "";
    report.applied = true;
    report.ok = true;
  } catch (e) {
    try {
      if (registryExisted) fs.copyFileSync(backupPath, args.registry);
      else fs.rmSync(args.registry, { force: true });
      report.rolledBack = true;
    } catch (rollbackErr) {
      report.problems.push(`rollback failed: ${rollbackErr.message}`);
    }
    report.skipped = e.message;
    return report;
  }

  /* 坏文件挪出 sessions 树：一个坏文件会拖垮整个 dsh 启动（init 里会 list() 全部 header） */
  if (report.unreadable.length) {
    const stamp = new Date().toISOString().replace(/[:.]/g, "-");
    const qroot = path.join(sessionsRoot, "..", "sessions-invalid", stamp);
    for (const item of report.unreadable) {
      const src = path.join(sessionsRoot, item.path);
      if (!fs.existsSync(src)) continue;
      try {
        const dest = path.join(qroot, item.path);
        fs.mkdirSync(path.dirname(dest), { recursive: true });
        fs.renameSync(src, dest);
        report.quarantined.push({ from: item.path, to: path.relative(sessionsRoot, dest) });
      } catch (e) {
        report.problems.push(`cannot quarantine ${item.path}: ${e.message}`);
      }
    }
  }
  return report;
}

main()
  .then((report) => {
    process.stdout.write(REPORT_PREFIX + JSON.stringify(report) + "\n");
    // 0=成功或良性跳过；2=应用后自校验失败并已回滚；1=拒绝写入/崩溃
    process.exit(report.ok ? 0 : report.rolledBack ? 2 : 1);
  })
  .catch((e) => {
    process.stdout.write(
      REPORT_PREFIX + JSON.stringify({ ok: false, skipped: `helper crashed: ${e.message}` }) + "\n",
    );
    process.exit(1);
  });
