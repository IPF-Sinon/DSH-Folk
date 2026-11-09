#!/usr/bin/env node
/**
 * 运行时版本「命名格式 + 排序」的门禁。
 *
 * ## 为什么必须钉住
 *
 * 1. **排序曾经是错的**：`listRuntimeVersions` 先按 `publishedAt`、再按字符串字典序排。
 *    在线实测（11 个 runtime release）用户看到的是
 *    `0.2.1-rc.5 → 0.2.2-alpha.2 → 0.2.2-alpha.3 → 0.2.2-rc.1 → 0.2.1-rc.4` ——
 *    最新的 `0.2.2-rc.1` 排在第 4。两个原因各自独立：
 *      - 同一批补发的 release 发布时间只差几十秒，`publishedAt` 就成了主键；
 *      - 并列时那句 `thenByDescending { it.version }` 是**字典序**，`rc.9` 比 `rc.10` 大、
 *        `0.2.1-rc.5` 比 `0.2.2-rc.1` 大。
 *    所以这里既断源码结构，也把真实的版本串喂给一份复刻出来的比较函数跑用例。
 *
 * 2. **命名改过一版**：`ubuntu` 与套件代号连写成 `ubuntunoble`，中间没有分隔，
 *    从串上看不出哪一段是发行版、哪一段是代号。改成 `ubuntu-noble`。
 *    改名的代价是「串不相等 = 有更新」这条判据会全体误判，所以必须同时断
 *    `sameVersion` 存在且被 checkRuntimeUpdate / 界面用上 —— 这条是本次改动里唯一一个
 *    「漏了就白下 150MB」的地方。
 *
 * 3. 四个滚动 tag 在 App 常量、构建脚本、CI 工作流里各写一遍，漂移了没人发现。
 */
const fs = require("fs");
const path = require("path");

const ROOT = path.join(__dirname, "..");
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), "utf8");

const RT = read("app/src/main/java/me/bmax/apatch/dsh/DshRuntime.kt");
const MODELS = read("app/src/main/java/me/bmax/apatch/dsh/DshRuntimeModels.kt");
const CONSTS = read("app/src/main/java/me/bmax/apatch/dsh/DshRuntimeConstants.kt");
const BUILDER = read("runtime-builder/build-rootfs.sh");
const WORKFLOW = read(".github/workflows/runtime.yml");
const SETTINGS = read("app/src/main/java/me/bmax/apatch/ui/screen/settings/FunctionSettings.kt");

let n = 0;
let bad = 0;
function ok(cond, label) {
  n++;
  console.log("  " + (cond ? "✓" : "✗") + " " + label);
  if (!cond) bad++;
}
// -- 复刻 Kotlin 侧的比较逻辑（一处逻辑两侧跑，断言才不会各说各话） --

/** 与 DshPluginRepo.kt 的 compareVersions 同一套规则。 */
function compareVersions(a, b) {
  const clean = (v) => v.trim().replace(/^v/, "").split("+")[0];
  const core = (v) => clean(v).split("-")[0];
  const pre = (v) => {
    const c = clean(v);
    const i = c.indexOf("-");
    return i < 0 ? "" : c.slice(i + 1);
  };
  const cmpSeg = (x, y) => {
    const nx = /^\d+$/.test(x) ? Number(x) : null;
    const ny = /^\d+$/.test(y) ? Number(y) : null;
    if (nx !== null && ny !== null) return nx === ny ? 0 : (nx < ny ? -1 : 1);
    return x < y ? -1 : (x > y ? 1 : 0);
  };
  const pa = core(a).split(".");
  const pb = core(b).split(".");
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const xa = pa[i] === undefined ? "0" : pa[i];
    const xb = pb[i] === undefined ? "0" : pb[i];
    const c = cmpSeg(xa, xb);
    if (c !== 0) return c;
  }
  const qa = pre(a);
  const qb = pre(b);
  if (qa === qb) return 0;
  if (qa === "") return 1;
  if (qb === "") return -1;
  const ra = qa.split(".");
  const rb = qb.split(".");
  for (let i = 0; i < Math.max(ra.length, rb.length); i++) {
    if (ra[i] === undefined) return -1;
    if (rb[i] === undefined) return 1;
    const c = cmpSeg(ra[i], rb[i]);
    if (c !== 0) return c;
  }
  return 0;
}

const dshOf = (v) => {
  const i = v.indexOf("-ubuntu");
  return i > 0 ? v.slice(0, i) : v;
};
const rootfsRevOf = (v) => {
  const m = /-r(\d+)/.exec(v);
  return m ? Number(m[1]) : 0;
};
const variantRank = (v) => {
  const slim = v.includes("-slim");
  const beta = v.endsWith("-beta");
  if (!slim && !beta) return 0;
  if (slim && !beta) return 1;
  if (!slim && beta) return 2;
  return 3;
};
function compareRuntimeVersion(a, b) {
  const c = compareVersions(dshOf(a), dshOf(b));
  if (c !== 0) return c;
  const ra = rootfsRevOf(a);
  const rb = rootfsRevOf(b);
  if (ra !== rb) return ra - rb;
  return variantRank(b) - variantRank(a);
}
const normalizeVersion = (v) => v.split("ubuntunoble").join("ubuntu-noble");
function sameVersion(a, b) {
  if (a === b) return true;
  if (a.length === 0 || b.length === 0) return false;
  return normalizeVersion(a) === normalizeVersion(b);
}
console.log("\n── 命名：分隔符与可解析 ──");
{
  ok(/"version": "\$\{DSH_REAL_VERSION\}-ubuntu-\$\{UBUNTU_RELEASE\}-r/.test(BUILDER),
    "版本模板是 (dsh)-ubuntu-(suite)-r(rev)：发行版与套件代号之间有分隔");
  ok(!/(\}\-ubuntu\$\{UBUNTU_RELEASE\})/.test(BUILDER),
    "旧的连写 ubuntu+noble（-> ubuntunoble）已删除");
  ok(/"ubuntuSuite": "\$\{UBUNTU_RELEASE\}"/.test(BUILDER) &&
     /"rootfsRev": \$\{ROOTFS_REV\}/.test(BUILDER),
    "metadata 直接给 ubuntuSuite / rootfsRev（新 App 不必再从串里正则抠）");
  ok(/ubuntuSuite = json\.optString\("ubuntuSuite", ""\)/.test(RT) &&
     /rootfsRev = json\.optInt\("rootfsRev", 0\)/.test(RT),
    "解析侧读这两个字段（老 metadata 读不到时退化为空/0）");
  ok(/val ubuntuSuite: String = ""/.test(MODELS) && /val rootfsRev: Int = 0/.test(MODELS),
    "DshMeta 带着这两个字段（默认值确保老 metadata 也能构造）");
}

console.log("\n── 解析：版本串拆段 ──");
{
  const parts = (v) => {
    const norm = normalizeVersion(v);
    const idx = norm.indexOf("-ubuntu-");
    const rest = idx < 0 ? "" : norm.slice(idx + 8);
    const suite = rest === "" ? "" : rest.split("-r")[0];
    return { dsh: dshOf(v), suite: suite, rev: rootfsRevOf(v) };
  };
  const p = parts("0.2.2-rc.1-ubuntu-noble-r6-slim-beta");
  ok(p.dsh === "0.2.2-rc.1", "dsh 段取到 0.2.2-rc.1（不是 0.2.2，rc 不能被吃掉）");
  ok(p.suite === "noble", "套件段取到 noble");
  ok(p.rev === 6, "修订号取到 6");
  const legacy = parts("0.1.5-rc.1-ubuntunoble-r3-beta");
  ok(legacy.dsh === "0.1.5-rc.1" && legacy.suite === "noble" && legacy.rev === 3,
    "老命名（ubuntunoble）也拆得开（库里那些存量 release 还得显示）");
  ok(parts("9.9.9-custom").suite === "" && parts("9.9.9-custom").rev === 0,
    "外部源的怪串拆不出来时空着，不瞎猜");
  ok(/fun dshOf\(version: String\)/.test(MODELS) &&
     /fun rootfsRevOf\(version: String\)/.test(MODELS) &&
     /fun parts\(version: String\)/.test(MODELS),
    "三个纯函数都在 RuntimeVersion.companion 里（可单测、界面共用）");
}

console.log("\n── 排序：语义序，不是字典序 ──");
{
  const gt = (a, b) => compareRuntimeVersion(a, b) > 0;
  ok(gt("0.2.2-rc.1-ubuntu-noble-r6-beta", "0.2.1-rc.5-ubuntu-noble-r6-beta"),
    "0.2.2-rc.1 > 0.2.1-rc.5（跨 dsh 版本）");
  ok(gt("0.2.1-rc.10-ubuntu-noble-r6", "0.2.1-rc.9-ubuntu-noble-r6"),
    "rc.10 > rc.9（字典序在这里会判反，是最容易回归的一条）");
  ok(gt("0.2.2-rc.1-ubuntu-noble-r10", "0.2.2-rc.1-ubuntu-noble-r9"),
    "r10 > r9（rootfs 修订号按数值比）");
  ok(gt("0.2.2-rc.1-ubuntu-noble-r6", "0.2.2-alpha.3-ubuntu-noble-r6"),
    "rc > alpha（同为预发布，按点分段比）");
  ok(gt("0.2.2-rc.1-ubuntu-noble-r6", "0.2.2-rc.1-ubuntu-noble-r6-beta"),
    "同一份内容：正式通道 > 测试通道");
  ok(gt("0.2.2-rc.1-ubuntu-noble-r6-slim", "0.2.2-rc.1-ubuntu-noble-r6-slim-beta"),
    "精简版同理：正式 > 测试");
  // 变体权重与 channelRank 同向：正式 > 精简 > 测试 > 精简测试。
  // 所以「精简正式版」排在「完整测试版」前面 —— 这条断言就是在钉这个方向。
  ok(gt("0.2.1-rc.3-ubuntu-noble-r6-slim", "0.2.1-rc.3-ubuntu-noble-r6-beta"),
    "精简正式版 > 完整测试版（变体权重与 channelRank 同向，不打架）");

  // 现网真实数据：这些版本串来自 DSH-Folk 的 runtime release，顺序就是用户期望看到的顺序。
  const live = [
    "0.2.2-rc.1-ubuntunoble-r6-beta",
    "0.2.2-alpha.3-ubuntunoble-r6-beta",
    "0.2.2-alpha.2-ubuntunoble-r6-beta",
    "0.2.1-rc.5-ubuntunoble-r6-beta",
    "0.2.1-rc.4-ubuntunoble-r6-beta",
    "0.2.1-rc.3-ubuntunoble-r6",
    "0.1.5-rc.1-ubuntunoble-r3-beta",
    "0.1.1-rc.2-ubuntunoble-r6-beta",
  ];
  const sortedDesc = live.slice().sort((a, b) => compareRuntimeVersion(b, a));
  ok(JSON.stringify(sortedDesc) === JSON.stringify(live),
    "语义降序 == 线上真实期望顺序（0.2.2-rc.1 最前，0.2.1-rc.5 在其后）");
  // 这 8 个串的字典序恰好与语义序一致（rc > alpha、0.2.2 > 0.2.1），所以它们
  // 证明不了「字典序会错」。真正会翻车的是两位数的预发布段：
  // 必须用完整的版本串：dsh 段被切出来之后 `rc.9` 才会按数字比。
  const tricky = ["0.2.1-rc.9-ubuntu-noble-r6", "0.2.1-rc.10-ubuntu-noble-r6"];
  const lexDesc = tricky.slice().sort().reverse();
  const semDesc = tricky.slice().sort((a, b) => compareRuntimeVersion(b, a));
  ok(lexDesc[0] === "0.2.1-rc.9-ubuntu-noble-r6" &&
     semDesc[0] === "0.2.1-rc.10-ubuntu-noble-r6",
    "字典序把 rc.9 排在 rc.10 前面，语义序才对 —— 旧实现的错法可复现");
}
console.log("\n── 接线：排序键与去重 ──");
{
  ok(/\.thenComparator \{ x, y -> RuntimeVersion\.compareRuntimeVersion\(y\.version, x\.version\) \}/.test(RT),
    "列表按 compareRuntimeVersion 降序（不是 publishedAt、也不是字符串）");
  const iCmp = RT.indexOf("compareRuntimeVersion(y.version, x.version)");
  const iPub = RT.indexOf("thenByDescending { it.publishedAt }");
  ok(iCmp > 0 && iPub > iCmp, "publishedAt 只在语义序之后做兜底，不再是主键");
  ok(!/thenByDescending \{ it\.version \}/.test(RT),
    "旧的 thenByDescending { it.version }（字典序）已删除");
  ok(/compareBy<RuntimeVersion> \{ RuntimeVersion\.channelRank\(it\.channel\) \}/.test(RT),
    "通道分组仍在（正式→精简→测试→精简测试→历史，这是产品决定）");
  ok(/val prev = found\[entry\.version\]/.test(RT) &&
     /RuntimeVersion\.channelRank\(entry\.channel\) < RuntimeVersion\.channelRank\(prev\.channel\)/.test(RT),
    "去重改成「通道权重高的那份胜出」，不再是先到先得");
}

console.log("\n── 分页与滚动通道 ──");
{
  ok(/for \(page in 1\.\.MAX_RELEASE_PAGES\)/.test(RT),
    "Releases API 翻页（一页 100 条，滚动 tag 已经在第 3 页）");
  ok(/if \(got < RELEASES_PER_PAGE\) break/.test(RT),
    "页没给满就停（不靠异常控制流程）");
  ok(/internal const val RELEASES_PER_PAGE = 100/.test(CONSTS) &&
     /internal const val MAX_RELEASE_PAGES = 3/.test(CONSTS),
    "分页参数是具名常量（300 条 >= 当下 270 多）");
  ok(/RuntimeVersion\.ROLLING_TAGS\.map/.test(RT),
    "四个滚动 tag 无条件补拉（API 里它们排在很后面，滑到精简档才是空列表）");
  ok(/val ROLLING_TAGS = listOf\("runtime-latest", "runtime-slim-latest", "runtime-beta-latest", "runtime-slim-beta-latest"\)/.test(MODELS),
    "ROLLING_TAGS 是唯一来源（App 侧不再散落字符串）");

  const tags = ["runtime-latest", "runtime-slim-latest", "runtime-beta-latest", "runtime-slim-beta-latest"];
  for (const t of tags) {
    ok(BUILDER.indexOf("CHANNEL_RELEASE_TAG=\"" + t + "\"") >= 0,
      "构建脚本的通道矩阵里有 " + t);
  }
  const missing = tags.filter((t) => WORKFLOW.indexOf(t) < 0);
  ok(missing.length === 0, "CI 工作流的 tag 推导认得这四个名字");
}

console.log("\n── 迁移：改名不得触发重装 ──");
{
  ok(/fun normalizeVersion\(version: String\): String = version\.replace\("ubuntunoble", "ubuntu-noble"\)/.test(MODELS),
    "normalizeVersion 折叠老命名");
  ok(/fun sameVersion\(a: String, b: String\): Boolean/.test(MODELS),
    "sameVersion 提供「是不是同一份」的判据");
  ok(sameVersion("0.2.1-rc.3-ubuntunoble-r6", "0.2.1-rc.3-ubuntu-noble-r6"),
    "老名与新名判为同一份（否则改名当天全体用户被推一次 150MB 重装）");
  ok(!sameVersion("0.2.1-rc.3-ubuntu-noble-r6", "0.2.1-rc.3-ubuntu-noble-r7"),
    "r 号不同仍判为不同（rootfs 内容变了必须能收到更新）");
  ok(!sameVersion("", "0.2.1-rc.3-ubuntu-noble-r6"),
    "本地没记过版本时不算「同一份」（checkRuntimeUpdate 另有分支处理）");
  ok(/if \(RuntimeVersion\.sameVersion\(meta\.version, local\)\) RuntimeCheckResult\(\)/.test(RT),
    "checkRuntimeUpdate 用 sameVersion 而不是逐字 ==");
  ok(/current = RuntimeVersion\.sameVersion\(entry\.version, currentVersion\)/.test(SETTINGS),
    "界面「当前版本」标记也走 sameVersion（迁移期不会消失）");
}

console.log(
  "\n" + (bad === 0 ? "✓ 全部通过" : "✗ 有失败") + "：" + n + " 项断言，" + bad + " 项失败",
);
process.exit(bad === 0 ? 0 : 1);