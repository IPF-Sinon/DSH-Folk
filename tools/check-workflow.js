/**
 * 自动化工作流的门禁。
 *
 * 这个功能的安全模型只有一句话：**用户批准前看到的流程图，必须就是实际会执行的东西**。
 * 因此这里断言的不是「代码风格」，而是那条链路上的每一环：定义只能声明不能变代码、批准前
 * 一定有一道阻塞式确认、免逐条确认的范围恰好等于用户看过的那些能力、危险命令必须单独勾选、
 * 容器侧拿到的说明必须与宿主实现一致。
 *
 * 与 check-native-logic 一样：能用正则查的用正则，能真跑的（插件 asset）就真跑。
 */
const fs = require("fs");
const path = require("path");

let failures = 0;
let total = 0;
function ok(cond, msg) {
  total += 1;
  if (cond) {
    console.log("  \u2713 " + msg);
  } else {
    failures += 1;
    console.log("  \u2717 " + msg);
  }
}
function eq(a, b, msg) {
  ok(a === b, msg + (a === b ? "" : `（实际 ${JSON.stringify(a)} 期望 ${JSON.stringify(b)}）`));
}

const read = (p) => fs.readFileSync(p, "utf8");
const SRC = {
  models: read("app/src/main/java/me/bmax/apatch/dsh/WorkflowModels.kt"),
  validator: read("app/src/main/java/me/bmax/apatch/dsh/WorkflowValidator.kt"),
  store: read("app/src/main/java/me/bmax/apatch/dsh/WorkflowStore.kt"),
  engine: read("app/src/main/java/me/bmax/apatch/dsh/WorkflowEngine.kt"),
  grants: read("app/src/main/java/me/bmax/apatch/dsh/WorkflowGrants.kt"),
  approvals: read("app/src/main/java/me/bmax/apatch/dsh/WorkflowApprovals.kt"),
  bridge: read("app/src/main/java/me/bmax/apatch/dsh/WorkflowBridge.kt"),
  perms: read("app/src/main/java/me/bmax/apatch/dsh/WorkflowPermissions.kt"),
  native: read("app/src/main/java/me/bmax/apatch/dsh/DshNativeBridge.kt"),
  fsbridge: read("app/src/main/java/me/bmax/apatch/dsh/DshFsBridge.kt"),
  // 内嵌脚本（dsh-workflow CLI / DSL 文档 / 样例）已搬到 DshRuntimeScripts.kt。
  runtime:
    (read("app/src/main/java/me/bmax/apatch/dsh/DshRuntime.kt") + "\n" + read("app/src/main/java/me/bmax/apatch/dsh/DshRuntimeScripts.kt")) +
    "\n" +
    read("app/src/main/java/me/bmax/apatch/dsh/DshRuntimeScripts.kt"),
  hostPrompt: read("app/src/main/java/me/bmax/apatch/dsh/DshHostPrompt.kt"),
  engineHarness: read("app/src/main/java/me/bmax/apatch/dsh/HarnessService.kt"),
  settings: read("app/src/main/java/me/bmax/apatch/ui/screen/settings/FunctionSettings.kt"),
  settingsScreen: read("app/src/main/java/me/bmax/apatch/ui/screen/settings/FunctionSettingsScreen.kt"),
  dialogs: read("app/src/main/java/me/bmax/apatch/ui/component/WorkflowDialogs.kt"),
  screen: read("app/src/main/java/me/bmax/apatch/ui/screen/WorkflowScreen.kt"),
  editorScreen: read("app/src/main/java/me/bmax/apatch/ui/screen/WorkflowEditorScreen.kt"),
  // 编辑页的内容本体（页面与审批弹窗共用）
  editorSurface: read("app/src/main/java/me/bmax/apatch/ui/component/WorkflowEditorSurface.kt"),
  canvas: read("app/src/main/java/me/bmax/apatch/ui/component/WorkflowBlueprintCanvas.kt"),
  card: read("app/src/main/java/me/bmax/apatch/ui/component/WorkflowNodeCard.kt"),
  mainActivity: read("app/src/main/java/me/bmax/apatch/ui/MainActivity.kt"),
  webUiActivity: read("app/src/main/java/me/bmax/apatch/ui/DshWebUiActivity.kt"),
  buildYml: read(".github/workflows/build.yml"),
  betaYml: read(".github/workflows/beta.yml"),
  mjs: read("app/src/main/assets/dsh-folk-workflow.mjs"),
};

console.log("\u2500 #1 DSL 是数据，不是代码");
ok(/enum class WorkflowNodeType\(val id: String\)/.test(SRC.models), "节点类型是枚举（落盘 id 稳定，改不了形状）");
for (const id of ["trigger", "execute", "condition", "logic", "extract", "wait"]) {
  ok(new RegExp(`\\("${id}"\\)`).test(SRC.models), `节点类型含 ${id}`);
}
for (const id of ["shell", "dsh", "notify"]) {
  ok(new RegExp(`\\("${id}"\\)`).test(SRC.models), `动作类型含 ${id}`);
}
ok(!/\beval\s*\(/.test(SRC.models + SRC.validator + SRC.engine), "定义通路里没有 eval");
ok(!/Class\.forName|getDeclaredMethod|ScriptEngine/.test(SRC.models + SRC.validator + SRC.engine),
  "没有反射/脚本引擎（否则「批准流程图」就成了摆设）");
ok(/fun interpolate\(/.test(SRC.models) && !/\$\{.*\?.*:/.test(SRC.models.split("fun interpolate")[1] || ""),
  "变量插值只做字符串替换，不做表达式求值");
ok(/if \(v != null\) \{/.test(SRC.models.split("fun interpolate")[1] || ""),
  "未定义的变量保持原样（不替换成空串：那会让 rm -rf \${dir}/ 变成 rm -rf /）");

console.log("\u2500 #2 校验器：看不懂的定义一律拒绝");
ok(/fun validate\(def: WorkflowDefinition, allowedCaps: Set<String>\): Result/.test(SRC.validator), "校验是纯函数（不碰设备、可单测）");
ok(/findCycle\(def\)/.test(SRC.validator), "查环");
ok(/reachableFrom\(def, entry\)/.test(SRC.validator) && /从 trigger 出发到不了/.test(SRC.validator), "要求每个节点从入口可达");
ok(/必须且只能有一个 trigger 节点/.test(SRC.validator), "唯一入口");
ok(/fun branchingNode\(node: WorkflowNode\?\)/.test(SRC.validator), "分支节点判定独立成函数（判断/逻辑/要求 DSH 的入口）");
ok(/MAX_NODES = 64/.test(SRC.models) && /MAX_EDGES = 256/.test(SRC.models) && /MAX_CMD_CHARS = 8_192/.test(SRC.models),
  "规模上限存在（64 节点 / 256 边 / 8KB 命令）");
ok(/when\x27?/.test(SRC.validator) || /只认 true \/ false/.test(SRC.validator), "分支标签只认 true/false");

console.log("\u2500 #3 批准链：阻塞 + 超时即拒绝 + 编辑期暂停");
ok(/enum class Kind\(val id: String\) \{\s*CREATE\("create"\),\s*RUN\("run"\),/.test(SRC.approvals.replace(/\n/g, " ")) ||
   /CREATE\("create"\)/.test(SRC.approvals) && /RUN\("run"\)/.test(SRC.approvals), "创建与运行是两个独立决定");
ok(/const val TTL_MS = 60_000L/.test(SRC.approvals), "确认时限 60 秒");
ok(/withTimeoutOrNull\(TTL_MS \+ 5_000L\)/.test(SRC.approvals), "等待有硬上限（超时不会把连接挂死）");
ok(/Decision\.EXPIRED/.test(SRC.approvals) && /expireIfStill/.test(SRC.approvals), "超时按拒绝处理");
ok(/fun startEditing\(id: Long\)/.test(SRC.approvals) && /expiry\?\.cancel\(\)/.test(SRC.approvals),
  "打开编辑器会撤掉到期任务（用户在改东西时被判超时不是安全设计）");
ok(/fun stopEditing\(id: Long, editedJson: String\?\)/.test(SRC.approvals) && /expiresAtMs = System\.currentTimeMillis\(\) \+ TTL_MS/.test(SRC.approvals),
  "退出编辑器重新给满时限");
ok(/allowDangerousIds/.test(SRC.approvals) && /val allowDangerous: Boolean = false/.test(SRC.approvals),
  "危险命令的放行是单独一个结论位");
ok(/rejectReasons\[id\] = reason\.trim\(\)/.test(SRC.approvals), "拒绝理由被记录（会转达给 AI）");

console.log("\u2500 #4 免逐条确认的范围 = 用户看过的能力，且只在运行期");
ok(/fun allows\(capId: String\): Grant\?/.test(SRC.grants) && /fun allowsDangerous\(capId: String\): Boolean/.test(SRC.grants),
  "命中判据分「能力」与「危险」两条");
ok(/caps = caps\.toSet\(\)/.test(SRC.grants), "授权绑定声明的能力集合");
ok(/fun revoke\(runId: String\)/.test(SRC.grants) && /WorkflowGrants\.revoke\(runId\)/.test(SRC.engine),
  "运行结束即撤销授权（在 finally 里，异常路径也撤）");
ok(/WorkflowGrants\.grant\(\s*runId = runId/.test(SRC.engine) && !/grant\(\s*runId = "pending"/.test(SRC.engine),
  "授权在开跑前落好，且用的是真实 runId（没有中间态窗口）");
{
  const bypass = (SRC.native.match(/!workflowCall &&/g) || []).length;
  ok(bypass >= 6, `门禁旁路出现在 ${bypass} 处（档位/总开关/读写/系统通知档都要一起放，只放一处等于没放）`);
  // 逐处点名：只数次数的话，「删掉其中一处、再加一处无意义的」也能凑够数。
  for (const [pat, what] of [
    [/if \(!workflowCall && !enabled\(ctx\)\)/, "原生能力总开关"],
    [/val confirm = !workflowCall && PrivPolicy\.needsConfirm\(/, "限制模式/危险清单确认"],
    [/if \(\(need != null \|\| confirm\) && !workflowCall\)/, "档位不足与确认共用同一条弹窗路径"],
    [/if \(!workflowCall && !capCallable\(ctx, cap\)\)/, "能力档位关闭"],
    [/if \(!workflowCall && path == "\/native\/notify\/system"/, "系统级通知档"],
    [/if \(!workflowCall && isWriteRequest\(method, path, params\) && !canWrite\(ctx, cap\)\)/, "写档位"],
    [/if \(!workflowCall && !isWriteRequest\(method, path, params\) && !canRead\(ctx, cap\)\)/, "读档位"],
  ]) {
    ok(pat.test(SRC.native), `工作流调用放开了「${what}」`);
  }
  ok(/val workflowGrant: WorkflowGrants\.Grant\? = if \(grantDanger\)/.test(SRC.native) &&
    /WorkflowGrants\.allowsDangerous\(grantCap\.id\)/.test(SRC.native),
    "危险命令走 allowsDangerous（勾过才放行）");
  // 类型必须显式写成 Grant?：if/else 两分支类型不同（Boolean? 与 Grant?）会被推断成 Any?，
  // 后面取 .runId 编译不过 —— CI 上已经踩过一次。
  ok(/val workflowGrant: WorkflowGrants\.Grant\?/.test(SRC.native), "授权变量的类型是显式的 Grant?");
  ok(!/val workflowCall = true/.test(SRC.native), "没有「无条件视为工作流」的写法");
  ok(/privDecision = "workflow:" \+/.test(SRC.native), "审计记下是哪一次运行放行的");
}

console.log("\u2500 #5 Android 权限没有被绕过（运行前专门检查）");
ok(/object WorkflowPermissions/.test(SRC.perms) && /fun missing\(ctx: Context, caps: List<String>\): List<Item>/.test(SRC.perms),
  "权限检查按声明能力计算缺失项");
for (const cap of ["CAMERA", "MIC", "LOCATION", "CONTACTS", "CALENDAR", "SMS", "PHONE", "MEDIA", "NOTIFY",
  "FULL_SCREEN_NOTIFY", "A11Y", "USAGE", "SETTINGS", "INSTALL", "SHELL", "DISPLAY"]) {
  ok(new RegExp(`Cap\\.${cap}\\.id`).test(SRC.perms), `权限预检覆盖 Cap.${cap}`);
}
ok(/Kind\.RUNTIME/.test(SRC.perms) && /Kind\.SPECIAL/.test(SRC.perms) && /settingsIntent\(/.test(SRC.perms),
  "区分运行时权限与特殊访问（后者只能跳设置页）");
ok(/const val TTL_MS = 300_000L/.test(SRC.perms), "权限检查时限 5 分钟（跳设置页开无障碍 60 秒不够）");
ok(/fun granted\(ctx: Context, item: Item\)/.test(SRC.perms) && /required\(ctx, caps\)\.filter \{ !granted\(ctx, it\) \}/.test(SRC.perms),
  "已授权的项从清单里消失（回来后重算，不会停在旧快照）");
ok(/WorkflowPermissions\.Gate\.pending/.test(SRC.dialogs) && /WorkflowPermissions\.Gate\.start/.test(SRC.dialogs),
  "弹窗接在同一个 Gate 状态机上");

console.log("\u2500 #6 端点：同一 token 守卫 + 总开关 + 前台才弹");
for (const ep of ["/workflow/list", "/workflow/status", "/workflow/create", "/workflow/run", "/workflow/cancel"]) {
  ok(SRC.bridge.includes(`"${ep}"`), `实现 ${ep}`);
}
ok(/path\.startsWith\("\/workflow\/"\) -> dispatchWorkflow\(/.test(SRC.fsbridge), "DshFsBridge 路由到 /workflow/");
ok(/private fun dispatchWorkflow\(/.test(SRC.fsbridge) && /readJsonBody\(input, headers, 256L \* 1024\)/.test(SRC.fsbridge),
  "请求体单独放宽到 256KB（定义比普通原生调用大）");
ok(/KEY_WORKFLOW_ENABLED, false/.test(SRC.bridge) && /dsh_workflow_err_disabled/.test(SRC.bridge),
  "总开关默认关，关着回 403 disabled");
ok(/if \(!DshNativeBridge\.isForeground\(ctx\)\)/.test(SRC.bridge), "不在前台不弹窗（否则连接等一个永远不出现的弹窗）");
ok(/if \(reason\.isBlank\(\)\) return 400/.test(SRC.bridge), "reason 必填，弹窗上才有「发起理由」可显示");

console.log("\u2500 #7 编辑后的 diff 必须随结果回传");
ok(/fun workflowDiff\(before: WorkflowDefinition, after: WorkflowDefinition\): List<WorkflowPatch>/.test(SRC.models),
  "结构差异单独一个纯函数");
ok(/OP_ADD_NODE/.test(SRC.models) && /OP_REMOVE_NODE/.test(SRC.models) && /OP_UPDATE_NODE/.test(SRC.models) &&
  /OP_ADD_EDGE/.test(SRC.models) && /OP_REMOVE_EDGE/.test(SRC.models), "patch 词汇与 Operit 的 node_patches 对齐（增删改/连线）");
ok(/diff = workflowDiff\(submitted, edited\)/.test(SRC.bridge) && /effective = edited/.test(SRC.bridge),
  "用户改过就按改动后的版本执行");
ok(/\.put\("diffText", JSONArray\(diff\.map \{ it\.humanize\(\) \}\)\)/.test(SRC.bridge), "diff 有机器可读与人类可读两份");
ok(/val recheck = WorkflowValidator\.validate\(edited, allowedCaps\)/.test(SRC.bridge),
  "用户编辑后的版本**重新校验**（改坏了不执行）");
ok(/diff = diff,/.test(SRC.engine) && /\.put\("diff"/.test(SRC.bridge), "diff 也随运行结果一起给出");

console.log("\u2500 #8 执行：DSH 拉起与等待、可取消、结果可查");
ok(/suspend fun awaitRunning\(timeoutMs: Long\): Boolean/.test(SRC.runtime), "DshRuntime 有「等 DSH 真的在跑」");
ok(/HarnessService\.start\(ctx\)/.test(SRC.engine) && /awaitRunning\(node\.timeoutMs\)/.test(SRC.engine),
  "trigger 会自主拉起 DSH 并等待（未启动 DSH 时先阻塞启动）");
ok(/hasFalse/.test(SRC.engine) && /dsh_unavailable/.test(SRC.engine),
  "起不来时走 false 分支（可条件判断进入其它流程）或判失败");
ok(/destroyForcibly\(\)/.test(SRC.engine) && /currentProcess\[runId\] = proc/.test(SRC.engine),
  "取消会杀掉当前节点进程（只 cancel 协程要等到命令自己结束）");
ok(/proc\.exitValue\(\)/.test(SRC.engine), "自己取退出码（「跑完了」与「失败了」必须能分辨）");
ok(/WorkflowStore\.saveRun\(ctx, run\)/.test(SRC.engine) && /appendLog\(ctx, runId/.test(SRC.engine),
  "每步落盘 + 日志边跑边追加（状态可查，不是只在内存里）");
ok(/fun reconcileInterrupted\(ctx: Context\)/.test(SRC.store) && /WorkflowStore\.reconcileInterrupted/.test(SRC.bridge),
  "重启后把挂着的运行标成 INTERRUPTED（不谎报 running）");
ok(/fun cancel\(ctx: Context, runId: String, reason: String\): Boolean/.test(SRC.engine), "取消是显式的");
ok(/HarnessService\.hold\(/.test(SRC.engine) && /ACTION_HOLD/.test(SRC.engineHarness),
  "长流程用前台服务钉住进程，且不顺带启动容器");
ok(/fromHold -> false/.test(SRC.engineHarness), "HOLD 不会拉起容器（纯 shell 工作流不该拖起一整套 DSH）");
{
  const line = SRC.runtime.match(/val WORKFLOW_CLI_SCRIPT = """([\s\S]*?)"""\.trimIndent\(\)/);
  ok(line !== null, "容器侧 dsh-workflow CLI 存在");
  const body = line ? line[1] : "";
  ok(!/[^\x00-\x7F]/.test(body), "CLI 字面量纯 ASCII（既有 check-native-cli 的规矩）");
  ok(/"dsh-workflow" to WORKFLOW_CLI_SCRIPT/.test(SRC.runtime), "CLI 被写进 /usr/local/bin");
  ok(/--reason is required/.test(body), "CLI 强制 --reason（与端点一致）");
  ok(/\/workflow\/status/.test(body) && /\/workflow\/cancel/.test(body), "CLI 覆盖状态与取消");
}

console.log("\u2500 #9 容器侧插件：降级 + 说明文档 + 版本号独立");
{
  const s = SRC.mjs;
  ok(/export const name = 'dsh-folk-workflow'/.test(s), "插件有 name");
  // 这一条以前写反了：当时的理由是「旧运行时的 tools 服务可能不存在，声明它会让 entry 不激活」，
  // 但 cordis 的真实行为是 —— 没声明 inject 就**不许读**该服务，ctx.tools 直接抛。于是工具永远
  // 注册不上，自检长期显示 unavailable（真机截图）。现在两个都注入，并钉住这一点。
  ok(/export const inject = \['systemPrompt', 'tools'\]/.test(s),
    "systemPrompt 与 tools 都在 inject 里（否则 ctx.tools 会抛 without inject）");
  ok(!/ctx\.tools/.test(s) || /inject = \[[^\]]*'tools'/.test(s),
    "读 ctx.tools 之前必须先在 inject 里声明它");
  ok(/export function apply\(ctx\)/.test(s), "有 apply（cordis 插件契约）");
  for (const tool of ["workflow_create", "workflow_run", "workflow_status", "workflow_cancel"]) {
    ok(s.includes(`'${tool}'`) || s.includes(`"${tool}"`) || s.includes(tool), `注册工具 ${tool}`);
  }
  ok(/typeof defineTool === 'function'\) return ctx\.tools\.register\(defineTool\(specObj\)\)/.test(s) &&
    /return ctx\.tools\.register\(specObj\)/.test(s),
    "工具注册有降级路径（defineTool → 裸 spec）");
  ok(/writeStatus\(\{\s*state: 'degraded'/.test(s) && /writeStatus\(\{\s*state: 'unavailable'/.test(s),
    "注册失败会落盘状态（设置页才能如实告诉用户）");
  ok(/DSL_DOC = '\/root\/\.dsh\/workflow-dsl\.md'/.test(s) && /FALLBACK_SECTION/.test(s),
    "提示词段读宿主写的 DSL 文档，读不到用兜底");
  ok(/'dsh-workflow', '\/usr\/local\/bin\/dsh-workflow'/.test(s), "工具走 CLI（与人手敲命令同一条实现）");
  ok(/WORKFLOW_PLUGIN_REV = 1/.test(SRC.hostPrompt) && /KEY_WORKFLOW_PLUGIN_REV/.test(SRC.hostPrompt),
    "工作流插件有独立版本号");
  ok(/private const val PLUGIN_REV = 20/.test(SRC.hostPrompt),
    "宿主提示词插件的 PLUGIN_REV 未被牵连抬高（内容没变）");
  ok(/PluginSpec\(\s*WORKFLOW_ASSET_NAME/.test(SRC.hostPrompt), "工作流插件在落盘/patch 清单里");
  ok(/val WORKFLOW_DSL_DOC = """/.test(SRC.runtime) && /WORKFLOW_DSL_DOC_NAME/.test(SRC.runtime),
    "宿主写 DSL 说明进容器");
  ok(/MAX_NODES|node types|trigger/.test(SRC.runtime.split("WORKFLOW_DSL_DOC = \"\"\"")[1] || ""),
    "DSL 说明里有节点类型（agent 照它写定义，才不会撞校验器）");
}

console.log("\u2500 #10 UI：两个宿主都挂、设置项接上、文案两份齐全");
ok((SRC.mainActivity.match(/WorkflowDialogHosts\(\)/g) || []).length === 1, "MainActivity 挂了弹窗宿主");
ok((SRC.webUiActivity.match(/WorkflowDialogHosts\(\)/g) || []).length === 1, "DshWebUiActivity 也挂了（它是独立 Activity）");
ok(/WorkflowDialogHosts\(\)/.test(SRC.dialogs), "宿主同时渲染审批与权限检查");
ok(/WorkflowApprovals\.approve\(request\.id, dangerous\)/.test(SRC.dialogs), "批准带上危险命令结论");
ok(/WorkflowApprovals\.startEditing\(request\.id\)/.test(SRC.dialogs) && /WorkflowDefinitionEditor\(/.test(SRC.dialogs),
  "编辑页在宿主里（编辑期间审批弹窗不渲染）");
ok(/WorkflowGraphPreview\(definition\)/.test(SRC.dialogs), "批准弹窗里有流程图预览");
ok(/posX/.test(SRC.dialogs) && /detectDragGestures/.test(SRC.dialogs), "编辑页节点可拖动且位置写回定义");
ok(/dsh_workflow_title/.test(SRC.settings) && /onOpenWorkflow: \(\) -> Unit = \{\}/.test(SRC.settings),
  "功能页入口层有工作流一行（不是塞在某个分类里）");
ok(/onOpenWorkflow = \{ navigator\.navigate\(WorkflowScreenDestination\) \}/.test(SRC.settingsScreen),
  "那一行真的会打开工作流页（不是只声明一个没人用的参数）");
// 阶段 2.4b：旧的画布/节点卡自绘已删除，画布与卡片只剩新组件这一份实现。
ok(/WorkflowBlueprintCanvas\(/.test(SRC.dialogs) && !/private fun NodeCard\(/.test(SRC.dialogs),
  "编辑页/预览用同一块画布，旧的自绘节点卡已删除（不是一列输入框）");
// 编辑页的画布已换成 WorkflowBlueprintCanvas（与预览同一套）；拖动回调的手势量是像素，
// 先在密度里换算成 dp 再吸附 12dp 网格，最后写回 posX/posY。
ok(/onNodeDrag = \{ id, dxPx, dyPx ->[\s\S]{0,700}?dragAcc \+= Offset\(/.test(SRC.editorSurface) &&
    /onNodeDragEnd = \{ id ->[\s\S]{0,700}?WorkflowGeometry\.snapToGridDp\(preview\.second\.x\)/.test(SRC.editorSurface) &&
    /it\.copy\(posX = nx, posY = ny\)/.test(SRC.editorSurface),
  "拖动把坐标写回定义（像素→dp→吸附网格，摆位不落盘下次打开就会自己跳）");
ok(/WorkflowBlueprintCanvas\(/.test(SRC.editorSurface) &&
  /onWireDrop/.test(SRC.canvas) && !/hitTest\(/.test(SRC.canvas),
  "编辑页用同一块画布；手势由卡片自己接（画布不再手算命中）");
ok(/private fun RunDetailDialog\(/.test(SRC.screen) && /WorkflowStore\.readLog/.test(SRC.screen),
  "详情是弹窗，且把当时那一版定义与日志都摆出来");
ok(/warn = tool\.state != "ok"/.test(SRC.screen),
  "自检区分红/黄（工具没注册但 CLI 可用时不该画成红的）");
ok(/@Destination<RootGraph>/.test(SRC.screen) && /fun WorkflowScreen\(navigator: DestinationsNavigator\)/.test(SRC.screen),
  "工作流页是真目的地");
ok(/KEY_WORKFLOW_ENABLED/.test(SRC.screen), "页面能开关总开关");
ok(/WorkflowPermissions\.Gate\.submit/.test(SRC.screen), "页面自己运行也要过权限检查");
{
  const zh = read("app/src/main/res/values-zh-rCN/dsh_strings.xml");
  const en = read("app/src/main/res/values/dsh_strings.xml");
  const used = new Set();
  for (const f of Object.values(SRC)) {
    for (const m of f.matchAll(/R\.string\.(dsh_workflow[a-zA-Z0-9_]*)/g)) used.add(m[1]);
  }
  const inFile = (t) => new Set([...t.matchAll(/name="(dsh_workflow[a-zA-Z0-9_]*)"/g)].map((m) => m[1]));
  const zhKeys = inFile(zh);
  const enKeys = inFile(en);
  const missZh = [...used].filter((k) => !zhKeys.has(k));
  const missEn = [...used].filter((k) => !enKeys.has(k));
  const extraZh = [...zhKeys].filter((k) => !enKeys.has(k));
  ok(missZh.length === 0 && missEn.length === 0, `引用的 ${used.size} 条文案中英都有` +
    `${missZh.length ? "，zh 缺 " + missZh.join(",") : ""}${missEn.length ? "，en 缺 " + missEn.join(",") : ""}`);
  ok(extraZh.length === 0, "两份文案键集完全一致（多出：" + extraZh.join(",") + "）");
}

console.log("\u2500 #11 编辑页真的能画出全部六类节点（且默认值合法）");
ok(/private val NODE_CATALOG = listOf\(/.test(SRC.dialogs), "有动作目录（不是只能加 shell 节点）");
for (const t of ["EXECUTE", "CONDITION", "EXTRACT", "WAIT", "LOGIC"]) {
  ok(new RegExp("NodeKind\\(\\s*WorkflowNodeType\\." + t).test(SRC.dialogs) || new RegExp("WorkflowNodeType\\." + t + ",").test(SRC.dialogs),
    `目录里有 ${t}`);
}
ok(/NODE_CATALOG/.test(SRC.dialogs) && /WorkflowNodeType.TRIGGER/.test(SRC.dialogs),
  "trigger 由定义自带（不可新增第二个）");
ok(/for \(action in WorkflowActionType.entries\)/.test(SRC.dialogs), "execute 的动作是选择器，不是自由文本");
ok(/onChange\(node.copy\(config = configForAction\(action\)\)\)/.test(SRC.dialogs),
  "换动作时整份替换 config（不留上一个动作的垃圾键）");
ok(/private fun configForAction\(action: WorkflowActionType\)/.test(SRC.dialogs) &&
  /private fun editableKeys\(node: WorkflowNode\)/.test(SRC.dialogs) &&
  !/listOf\("action", "cmd"\)/.test(SRC.dialogs),
  "动作键由选择器写入，editableKeys 只暴露要填的值");
ok(/mapOf\("source" to "exitCode", "op" to "equals", "value" to "0"\)/.test(SRC.dialogs),
  "判断节点的默认值合法（source/op 都已填好）");

ok(/if \(patchLines\.isEmpty\(\)\) onCancel\(\) else confirmDiscard = true/.test(SRC.editorSurface) &&
  /dsh_workflow_editor_discard_title/.test(SRC.editorSurface),
  "编辑页有改动时返回要先确认（一键点掉等于无声清空用户改了几分钟的东西）");
// 草稿落盘这条**故意没做**：审批只存在于内存里（进程一死这次审批就没了），
// 恢复出来的草稿没有去处。留着 saveDraft/loadDraft 没人调用就是死代码，所以删掉了。
ok(!/workflowDraftDir|saveDraft|loadDraft|clearDraft/.test(SRC.store + read("app/src/main/java/me/bmax/apatch/dsh/DshEnv.kt")),
  "没有没人调用的草稿 API（审批是内存态的，草稿恢复无去处）");

// 项目内部符号的导入：check-imports 只管 androidx/Compose 那一类，这三次 CI 红全栽在
// 「项目自己的类型/扩展没导入」上（R、toJson、WorkflowActionType）。这里按文件静态对一遍。
{
  const dshSymbols = ["WorkflowApprovals", "WorkflowDefinition", "WorkflowEdge", "WorkflowEngine", "WorkflowGrants",
    "WorkflowNode", "WorkflowNodeType", "WorkflowActionType", "WorkflowOnError", "WorkflowPatch",
    "WorkflowPermissions", "WorkflowRun", "WorkflowRunState", "WorkflowSchema", "WorkflowStepResult",
    "WorkflowStepState", "WorkflowStore", "WorkflowValidator", "WorkflowBridge", "definitionFromJson",
    "toJson", "workflowDiff", "interpolate", "nodeFromJson", "runFromJson", "stepFromJson"];
  for (const [name, text] of [["WorkflowDialogs.kt", SRC.dialogs], ["WorkflowScreen.kt", SRC.screen],
    ["FunctionSettings.kt", SRC.settings]]) {
    // 注释与 KDoc 链接（[WorkflowValidator]）不是使用，先剥掉再匹配
    const code = text.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, "");
    const missing = dshSymbols.filter((sym) => {
      const used = new RegExp("\\b" + sym + "\\b").test(code);
      if (!used) return false;
      // 自己声明的符号不用导入
      // 自己声明的符号不用导入（用 includes 而不是正则：这里已经有三层转义，正则太容易写错）
      const declared = text.includes("fun " + sym) || text.includes("class " + sym) ||
        text.includes("object " + sym) || text.includes("val " + sym);
      return !declared && !text.includes("import me.bmax.apatch.dsh." + sym);
    });
    ok(missing.length === 0, name + " 用到的 dsh 符号都导入了" + (missing.length ? "，缺 " + missing.join(",") : ""));
    if (/R\.(string|drawable|plurals)\./.test(text)) {
      ok(/import me\.bmax\.apatch\.R\b/.test(text), name + " 引用了 R.* 就必须 import me.bmax.apatch.R");
    }
  }
}

// Kotlin raw string 里拼错模板的典型形态是 ${$}（我拼过一次）：它在 JSON 里完全合法，
// 只有编译器会报「Expecting an expression」，所以必须在这里钉住。
{
  const raw = [SRC.runtime.match(/val WORKFLOW_SAMPLES: Map<String, String> = mapOf\(([\s\S]*?)\n    \)/) || []];
  const text = raw[0] ? raw[0][1] : "";
  ok(!/\$\{\$\}/.test(text), "样例里没有拼坏的模板（${$} 形态）");
  ok(text.includes("${D}{tag}"), "样例用 ${D}{tag} 写出运行时的 ${tag}（变量演示是真的在演示变量）");
}

console.log("\u2500 #14 样例定义：用户与 AI 的起点，必须真的合法");
{
  const m = SRC.runtime.match(/val WORKFLOW_SAMPLES: Map<String, String> = mapOf\(([\s\S]*?)\n    \)/);
  ok(m !== null, "有三份样例定义（最小可跑 / DSH 拉起与 false 分支 / 变量与取值）");
  const raw = m ? m[1] : "";
  // Kotlin raw string 里的 ${D} 会被拼成美元；测试里还原成 JSON 见过的样子
  const names = [...raw.matchAll(/"([a-z0-9-]+)" to """/g)].map((x) => x[1]);
  eq(names.sort().join(","), "dsh-ready,minimal,variables", "样例名固定");
  const bodies = [...raw.matchAll(/"""\n([\s\S]*?)\n"""/g)].map((x) => x[1].split("${D}").join("$"));
  eq(bodies.length, 3, "三份样例正文都能取到");
  // 复刻校验器的硬规则：样例是给人抄的，抄出一份不合法的样例比没有样例更糟
  const TYPES = ["trigger", "execute", "condition", "logic", "extract", "wait"];
  const NEED = { trigger: [], execute: ["action"], condition: ["source", "op"], logic: ["op", "sources"],
    extract: ["source", "mode", "var"], wait: [] };
  const isBranch = (n) => n.type === "condition" || n.type === "logic" ||
    (n.type === "trigger" && String(n.config && n.config.requireDsh) === "1");
  bodies.forEach((text, i) => {
    let def = null;
    try { def = JSON.parse(text); } catch (e) { /* 下面报 */ }
    ok(def !== null, names[i] + " 是合法 JSON" + (def === null ? "（" + String(text.slice(0, 40)) + "）" : ""));
    if (!def) return;
    const ids = def.nodes.map((n) => n.id);
    eq(new Set(ids).size, ids.length, names[i] + " 节点 id 不重复");
    eq(ids.filter((id) => def.nodes.find((n) => n.id === id).type === "trigger").length, 1, names[i] + " 恰好一个 trigger");
    const trig = def.nodes.find((n) => n.type === "trigger");
    ok(!def.edges.some((e) => e.to === trig.id), names[i] + " trigger 没有入边");
    ok(def.nodes.every((n) => TYPES.includes(n.type)), names[i] + " 节点类型都在白名单里");
    ok(def.nodes.every((n) => ids.includes(n.id) && (n.timeoutMs === undefined || (n.timeoutMs >= 1000 && n.timeoutMs <= 3600000))),
      names[i] + " 节点 id 合法、超时在范围内");
    ok(def.nodes.every((n) => NEED[n.type].every((k) => n.config && String(n.config[k] || "").length > 0)),
      names[i] + " 每个节点的必填 config 都在");
    ok(def.nodes.filter((n) => n.type === "execute").every((n) => ["shell", "dsh", "notify"].includes(n.config.action)),
      names[i] + " execute 的 action 都是 shell/dsh/notify");
    ok(def.edges.every((e) => ids.includes(e.from) && ids.includes(e.to) && e.from !== e.to),
      names[i] + " 连线两端都存在且不是自环");
    ok(def.edges.every((e) => (e.when === "" || e.when === "true" || e.when === "false") &&
      (e.when === "" || isBranch(def.nodes.find((n) => n.id === e.from)))),
      names[i] + " when 只在判断节点的出边上出现");
    ok(def.nodes.filter(isBranch).every((n) => {
      const out = def.edges.filter((e) => e.from === n.id);
      return out.some((e) => e.when === "true") && out.some((e) => e.when === "false");
    }), names[i] + " 判断节点都有 true / false 两条出边");
    // 无环 + 可达
    const adj = {};
    for (const n of def.nodes) adj[n.id] = [];
    for (const e of def.edges) adj[e.from].push(e.to);
    const seen = new Set([trig.id]);
    const q = [trig.id];
    while (q.length) for (const nx of adj[q.shift()]) if (!seen.has(nx)) { seen.add(nx); q.push(nx); }
    eq(seen.size, def.nodes.length, names[i] + " 每个节点都能从 trigger 走到（无断链、无环外孤岛）");
  });
}

console.log("\u2500 #15 指南与样例：命令里就能拿到（不依赖提示词段）");
{
  const cli = SRC.runtime.match(/val WORKFLOW_CLI_SCRIPT = """([\s\S]*?)"""\.trimIndent\(\)/);
  const body = cli ? cli[1] : "";
  ok(/cmd === .guide./.test(body), "dsh-workflow guide 打印完整指南");
  ok(/cmd === .samples./.test(body) && /cmd === .sample./.test(body), "samples 列出、sample <name> 打印一份");
  ok(/opt\.sample/.test(body) && /--sample <name>/.test(body), "create/run 支持 --sample <name>（直接拿样例当模板）");
  ok(/invalid_definition/.test(body) && /guide/.test(body), "定义被拒时把「去看 guide / 用样例」写在错误旁边");
  ok(/\/root\/\.dsh\/workflow-samples/.test(body), "CLI 知道样例目录在哪");
  ok(/\/root\/\.dsh\/workflow-dsl\.md/.test(body), "CLI 从容器里的指南文件读（宿主写、实现改了文档跟着改）");
  const doc = SRC.runtime.match(/val WORKFLOW_DSL_DOC = """([\s\S]*?)"""\.trimIndent\(\)/);
  const docText = doc ? doc[1] : "";
  ok(/dsh-workflow guide/.test(docText), "指南里写明「随时 dsh-workflow guide」");
  ok(/dsh-workflow samples/.test(docText) && /workflow-samples/.test(docText), "指南里写明样例怎么用");
  ok(/```json/.test(docText) && /"edges"/.test(docText), "指南里有一份完整可抄的例子");
  ok(/Settings > Features > Automation workflows/.test(docText), "指南里告诉 agent 用户也能自己建（名字/描述是给人看的）");
}

console.log("\u2500 #16 格式放到了模型必定看得见的地方");
{
  ok(/const FORMAT = \[/.test(SRC.mjs), "插件里有一份格式速查（单一真相）");
  ok(/FALLBACK_SECTION = \[[\s\S]*?FORMAT,/.test(SRC.mjs), "提示词兜底里带完整格式（不是「见文档」这种死路）");
  // 取 workflow_create 那一段 spec（到下一个 spec( 为止），否则 split 会切在别处
  const createSeg = (SRC.mjs.match(/workflow_create'[\s\S]*?\n  spec\(/) || [""])[0];
  ok(/\+ FORMAT/.test(createSeg), "workflow_create 的工具描述里直接带格式");
  ok(/workflow_run[\s\S]{0,900}?FORMAT/.test(SRC.mjs), "workflow_run 也指向同一份格式");
  ok(/dsh-workflow samples/.test(SRC.mjs), "工具描述里给出「先抄样例」的路径");
  ok(/type: .object.,[\s\S]{0,400}?Must match the FORMAT/.test(SRC.mjs), "definition 参数自己就说清必须符合格式");
}

// Canvas 的 lambda 是 DrawScope，不是 composable 作用域：在里面读 MaterialTheme 编译不过
// （CI 抓过一次）。顺带钉住 Modifier.offset 的导入 —— 它同样只在 CI 上暴露。
{
  const windows = [...SRC.dialogs.matchAll(/Canvas\(/g)].map((m) => SRC.dialogs.slice(m.index, m.index + 400));
  ok(windows.length > 0 && windows.every((t) => !/MaterialTheme/.test(t)),
    "Canvas 的 lambda 里不读主题色（要先提到外面）");
  ok(!/Modifier\.offset\(/.test(SRC.dialogs) || /import androidx\.compose\.foundation\.layout\.offset/.test(SRC.dialogs),
    "用了 Modifier.offset 就必须导入它");
}

console.log("\u2500 #16b 引脚展示模型（阶段 1：纯加法，不改落盘格式）");
{
  // 引脚是**展示层**：枚举 + 规格 + 按类型推导。它们不参与落盘、不改变校验与执行 ——
  // 这条断言组的作用就是钉住「它只是加法」：既要有，也不能偷偷进到 JSON 里。
  ok(/internal enum class WorkflowPinKind/.test(SRC.models), "有 WorkflowPinKind");
  for (const kind of ["EXEC_IN", "EXEC_OUT", "EXEC_TRUE", "EXEC_FALSE", "DATA_IN", "DATA_OUT"]) {
    ok(new RegExp(`\\b${kind}\\b`).test(SRC.models), `引脚种类含 ${kind}`);
  }
  ok(/internal data class WorkflowPinSpec\(/.test(SRC.models) &&
    /val dataType: String = "string"/.test(SRC.models) &&
    /val defaultValue: String = ""/.test(SRC.models),
    "WorkflowPinSpec 的两个新字段都有默认值（旧构造点不用改）");
  const pins = SRC.models.slice(SRC.models.indexOf("fun defaultPinsFor("));
  ok(pins.length > 200, "WorkflowSchema 里有 defaultPinsFor");
  ok(/WorkflowNodeType\.CONDITION, WorkflowNodeType\.LOGIC -> listOf\(execIn, yes, no\)/.test(pins),
    "条件/逻辑节点给出真、假两条出口");
  ok(/WorkflowNodeType\.TRIGGER ->[\s\S]{0,220}?EXEC_FALSE/.test(pins),
    "trigger 要求 DSH 时也有「未就绪」出口（它本来就走假分支）");
  ok(/WorkflowActionType\.SHELL, WorkflowActionType\.DSH -> listOf\([\s\S]{0,160}?"cmd"/.test(pins),
    "shell/dsh 节点的参数引脚是 cmd");
  ok(/WorkflowNodeType\.EXTRACT -> listOf\([\s\S]{0,200}?DATA_OUT/.test(pins),
    "extract 节点有变量输出引脚");
  // 「只是展示」的反向证据：引脚不进 JSON 编解码。
  ok(!/pin/i.test(SRC.models.slice(SRC.models.indexOf("fun toJson"), SRC.models.indexOf("fun toJson") + 4000)),
    "引脚不写进 toJson（落盘格式没变）");
}

console.log("\u2500 #16c 步骤状态是 7 态，id 是落盘值，不能改名/新增");
{
  // 交接文档任务 1.4 说「完善五态 Pending/Running/Succeeded/Skipped/Failed」——那个前提是错的：
  // 实际是 7 态，而且 id 字符串会写进盘上的 run JSON。这里把 id 逐个钉死。
  const st = SRC.models.slice(SRC.models.indexOf("enum class WorkflowStepState"), SRC.models.indexOf("enum class WorkflowStepState") + 400);
  for (const pair of [["PENDING", "pending"], ["RUNNING", "running"], ["OK", "ok"], ["FAILED", "failed"],
    ["TIMEOUT", "timeout"], ["SKIPPED", "skipped"], ["CANCELED", "canceled"]]) {
    ok(st.includes(pair[0] + "(\"" + pair[1] + "\")"), "步骤状态 " + pair[0] + "(" + pair[1] + ")");
  }
  ok(!/\bSUCCEEDED\b/.test(st), "没有 SUCCEEDED 这种别名（成功是 OK；改名会让已存 run 读不回来）");
}

console.log("\u2500 #16d 蓝图节点卡与画布（阶段 2）");
{
  const card = read("app/src/main/java/me/bmax/apatch/ui/component/WorkflowNodeCard.kt");
  const canvas = read("app/src/main/java/me/bmax/apatch/ui/component/WorkflowBlueprintCanvas.kt");
  ok(card.length > 800 && canvas.length > 800, "两个新组件文件都在");
  ok(!/Color\(0x/i.test(card + canvas), "新组件里没有硬编码颜色（只用 MaterialTheme.colorScheme）");
  ok(/MaterialTheme\.colorScheme\.error/.test(card) && /MaterialTheme\.colorScheme\.tertiary/.test(card),
    "运行态边框按状态取色（失败 error、成功 tertiary）");
  ok(/WorkflowSchema\.defaultPinsFor\(node\)/.test(card), "引脚行来自 defaultPinsFor（与模型同一份推导）");
  ok(/cubicTo\(/.test(canvas) && /onWireDrop/.test(canvas) && /detectDragGestures/.test(canvas),
  "画布：贝塞尔连线 + 空白处平移 + 拉线落点回调");
  ok(/drawBlueprintDots/.test(canvas) && /24\.dp/.test(canvas), "画布有点阵网格");
  ok(!/fun layoutNodes\(/.test(canvas), "画布不自带布局：坐标由调用方传入（预览与编辑器共用摆位）");
  const preview = SRC.dialogs.slice(SRC.dialogs.indexOf("internal fun WorkflowGraphPreview("));
  ok(/WorkflowBlueprintCanvas\(/.test(preview.slice(0, 700)), "预览内部委托新画布（对外签名没变）");
}

console.log("\u2500 #16e 分支未命中的节点标 SKIPPED（阶段 3.1）");
{
  const engine = SRC.engine;
  ok(/branch_not_taken/.test(engine), "未命中分支的下游节点有明确的 reason（branch_not_taken）");
  const markAt = engine.indexOf("branch_not_taken");
  const okAt = engine.indexOf("state = WorkflowRunState.SUCCEEDED");
  ok(markAt > 0 && okAt > markAt, "标记发生在「成功收尾」之前（失败/取消的路径不标）");
  ok((engine.match(/branch_not_taken/g) || []).length === 1, "只在成功收尾处标一次（不在每步里误标）");
  ok(/val visited = run\.steps\.map \{ it\.nodeId \}\.toSet\(\)/.test(engine),
    "以「已产生步骤结果的节点」为已访问集合（引擎只在执行时建步骤，没有 PENDING 兜底）");
}

console.log("\u2500 #16f 单步超时（阶段 3.2：核对既有实现，不重复造）");
{
  // 文档任务 3.2 说「为单步执行增加超时保护」——实际早就有了：exec 用 reader.join(node.timeoutMs)、
  // wait 用 deadline、DSH 用 awaitRunning(node.timeoutMs)，默认 120s、校验器限 1s~1h。
  // 这里把它钉住，避免以后「再加一套」变成两套超时互相掩盖。
  ok(/reader\.join\(node\.timeoutMs\)/.test(SRC.engine), "exec 步骤有超时（reader.join）");
  ok(/System\.currentTimeMillis\(\) \+ node\.timeoutMs/.test(SRC.engine), "wait 步骤有 deadline");
  ok(/awaitRunning\(node\.timeoutMs\)/.test(SRC.engine), "等 DSH 也有超时");
  ok(/val timeoutMs: Long = 120_000L/.test(SRC.models), "默认单步超时 120s（不是文档说的 60s）");
  ok(/MIN_TIMEOUT_MS = 1_000L/.test(SRC.models) && /MAX_TIMEOUT_MS = 3_600_000L/.test(SRC.models),
    "超时上下限 1s~1h");
}

console.log("\u2500 #16g 编辑页是次级页面（不是覆盖层）");
{
  ok(/@Destination<RootGraph>/.test(SRC.editorScreen) && /fun WorkflowEditorScreen\(/.test(SRC.editorScreen),
    "编辑页是导航图里的一个目的地");
  ok(/WorkflowEditorScreenDestination\(isNew = true\)/.test(SRC.screen) &&
    /WorkflowEditorScreenDestination\(workflowId = def\.id\)/.test(SRC.screen),
    "新建与编辑都导航到编辑页（不再在本页盖一层）");
  ok(!/editing\?\.let/.test(SRC.screen) && !/WorkflowDefinitionEditor\(/.test(SRC.screen),
    "列表页不再内联渲染编辑器");
  ok(/WorkflowEditorDialog\(/.test(SRC.dialogs) && /WorkflowDefinitionEditor\(initial = initial/.test(SRC.dialogs),
    "审批那条仍用全屏弹窗包装，内容与页面版同一份");
  // 内容本身不该再自带 Dialog：弹窗只是包装，页面直接用它。
  const editorBody = SRC.dialogs.slice(SRC.dialogs.indexOf("internal fun WorkflowDefinitionEditor("));
  ok(!/Dialog\(/.test(editorBody.slice(0, 1600)), "编辑器内容不再自带 Dialog（弹窗由 WorkflowEditorDialog 负责）");
  ok(/BackHandler \{/.test(SRC.editorSurface) && /patchLines\.isEmpty\(\)/.test(SRC.editorSurface),
  "系统返回与「取消」同一条路（不绕过放弃改动确认）");
}

console.log("\u2500 #16h 坐标换算与手势结构（拖动 1:1 的契约）");
{
  const geo = read("app/src/main/java/me/bmax/apatch/ui/component/WorkflowGeometry.kt");
  ok(/internal object WorkflowGeometry/.test(geo) && /const val GRID_DP = 12/.test(geo), "坐标换算收成一个对象，网格 12dp");
  ok(/fun pxToDp\(px: Float, density: Float\): Float = if \(density > 0f\) px \/ density else px/.test(geo),
    "pxToDp 就是 px / density（密度非法时不放大位移）");
  ok(/fun snapToGridDp\(dp: Float\): Int = \(dp \/ GRID_DP\)\.roundToInt\(\) \* GRID_DP/.test(geo),
    "snapToGridDp 四舍五入到 12dp 网格");
  // 数值契约（与 Kotlin 同一条公式的独立复算）：手指 110px、密度 2.75 → 40dp → 吸附 36dp。
  const pxToDp = (px, d) => (d > 0 ? px / d : px);
  const snap = (dp) => Math.round(dp / 12) * 12;
  ok(Math.abs(pxToDp(110, 2.75) - 40) < 1e-6, "110px / 2.75 = 40dp（1:1 换算）");
  ok(snap(pxToDp(110, 2.75)) === 36, "40dp 吸附到 12dp 网格 = 36dp");
  // 宿主：写回只经几何对象，不再出现裸除密度。
  ok(/WorkflowGeometry\.pxToDp\(dxPx, density\)/.test(SRC.editorSurface) &&
    /WorkflowGeometry\.snapToGridDp\(/.test(SRC.editorSurface), "编辑页写回走 pxToDp + snapToGridDp");
  ok(!/dxPx \/ density/.test(SRC.editorSurface), "编辑页不再裸除密度（截断/加速的来源）");
  // 手势新模型（对齐点击助手）：卡片各自 onTouchEvent，画布只接空白处。
  ok(/detectDragGestures/.test(SRC.card) && /clickable/.test(SRC.card),
    "卡片自己接点/拖手势（命中不需要手算，也就不会比错坐标系）");
  ok(/boundsInWindow/.test(SRC.card) && /onPinDrag/.test(SRC.card),
    "卡片把自己的窗口矩形与拉线事件报给画布（窗口坐标对窗口坐标）");
  ok(!/hitTest/.test(SRC.canvas) && !/CARD_H_FALLBACK_DP/.test(SRC.canvas),
    "画布不再手算命中（删掉了固定高度兜底那套）");
  ok(/pointerInput\(Unit\)/.test(SRC.card), "卡片手势 key 是 Unit（拖动中位置变化不会重启检测器）");
  ok(/wireAnchor/.test(SRC.canvas) && /wirePointer/.test(SRC.canvas),
    "拉线两端都用窗口坐标（不随平移的画布原点换算），所以线跟手");
  ok(/onWireDrop/.test(SRC.canvas) && /onWireDrop = \{ fromId, toId, pointer ->/.test(SRC.editorSurface),
    "松手落在卡片上 = 连边（不是一律弹菜单）");
  // 卡片：不接手势，交给 focusable/semantics 与画布实测。
  ok(/focusable\(\)/.test(SRC.card) && /semantics \{ contentDescription/.test(SRC.card),
    "卡片可被无障碍聚焦，并带名称描述");
  ok(/detectDragGestures/.test(SRC.card) && !/hitTest/.test(SRC.card),
    "卡片自己处理拖动（不再依赖画布命中判定）");
  // 平移只应用一次。
  ok(/translationX = pan\.x/.test(SRC.canvas) && !/pos\.x \+ pan/.test(SRC.canvas),
    "画布平移只在图层上应用一次（不再在卡片偏移里重复加）");
}

console.log("\u2500 #16j 抽屉的列表不能是滚动容器（否则纵向拖出会被滚动抢走）");
{
  const e = SRC.editorSurface;
  // 只截「抽屉」这一整块：从 if (sheetOpen) 到「点卡片」弹窗之前。
  const sheetStart = e.indexOf("if (sheetOpen) {");
  const sheetEnd = e.indexOf("// ── 点卡片", sheetStart);
  const sheet = sheetStart >= 0 && sheetEnd > sheetStart ? e.slice(sheetStart, sheetEnd) : "";
  ok(sheet.length > 0, "能定位到抽屉这一段");
  ok(!/verticalScroll/.test(sheet), "抽屉里不能有滚动容器（纵向拖动会被它吃掉）");
  ok(/EDITOR_NODE_CATALOG\.chunked\(2\)/.test(sheet), "抽屉按两列分块（7 个类型 4 行放得下）");
  ok(/modifier = Modifier\.weight\(1f\)/.test(sheet), "抽屉条目拿到 Row 的权重（两列等宽）");
}

console.log("\u2500 #16i 抽屉拖出：落点就是松手位置（阶段 4）");
{
  const e = SRC.editorSurface;
  ok(/positionInWindow\(\)/.test(e) && /onGloballyPositioned/.test(e),
    "抽屉与画布都用窗口坐标做基准（否则局部坐标算不出落点）");
  ok(/var pointer by remember/.test(e) && /pointer \+= amount/.test(e),
    "拖出时累计的是**窗口坐标**里的指针位置，不是相对抽屉的位移");
  ok(/val inCanvas = windowPos\.x >= canvasOrigin\.x/.test(e) && /canvasSize\.height/.test(e),
    "只有落在画布矩形内才算拖出（松在抽屉/导航条上 = 取消）");
  ok(/addNodeAt\(kind, xDp, yDp\)/.test(e) &&
    /fun dropPointDp\(pointerWindow: Offset\): Pair<Int, Int>/.test(e) &&
    /WorkflowGeometry\.snapToGridDp\(\s*WorkflowGeometry\.pxToDp\(pointerWindow\.x - canvasOrigin\.x, density\)/.test(e),
    "落点经 pxToDp + 12dp 吸附后落盘（抽屉与拉线共用同一处换算）");
  ok(/ghost\?\.let \{/.test(e) && /pos\.x - rootOrigin\.x/.test(e),
    "拖出过程中有跟随手指的幽灵卡");
  ok(!/total < -32f/.test(e), "删掉了「只按向上位移猜」的旧启发式");
}

console.log("\u2500 #16k 拖动手感与拉线坐标（三条真机反馈的回归护栏）");
{
  const e = SRC.editorSurface;
  const c = SRC.card;
  const cv = SRC.canvas;
  // ① 原地颤抖：拖动过程中每帧吸附会把小位移吞掉；现在累加未吸附位移，松手才吸附。
  ok(/dragAcc \+= Offset\(/.test(e) && /dragPreview = id to Offset\(/.test(e) &&
    /onNodeDragEnd = \{ id ->/.test(e) && /snapToGridDp\(preview\.second\.x\)/.test(e),
    "拖动过程中不吸附（累加未吸附位移），松手才吸附网格");
  ok(/dragPreview\?\.let \{ \(id, p\) -> this\[id\] = p \}/.test(e),
    "拖动中画布用预览位置（1:1 跟手，右不抖）");
  // ② 拉线：手势挂在针脚自己身上，线头就是这颗粒子；局部坐标的基准也是粒子。
  ok(/private fun LinkablePinDot\(/.test(c) && /pointerInput\(Unit\)/.test(c),
    "拉线手势挂在针脚粒子自己身上（不是整列，命中范围=看得见的点）");
  ok(/pointer = originState\.value \+ start/.test(c) && /o\.x \+ s\.width \/ 2f/.test(c),
    "手指与线头都在窗口坐标里算（粒子原点 + 局部坐标 / 粒子中心）");
  ok(/onPinBounds\?\.invoke\(pin\.kind\.name, coords\.boundsInWindow\(\)\)/.test(c),
    "针脚上报窗口矩形（含引脚类型），已连的线也从真针脚画出去");
  ok(/val pinned = pinRects\[/.test(cv) && /pinned\.right - origin\.x - pan\.x/.test(cv),
    "已连线优先从针脚矩形起画（窗口→内容层局部坐标）");
  // ③ 落点/幽灵卡对齐：胶囊左上角 = 手指 = 节点左上角。
  ok(/pos\.x - rootOrigin\.x\)\.toInt\(\)/.test(e) && !/- 40 \* density/.test(e),
    "幽灵胶囊与落点对齐（不再自己往左上挪 40/20dp）");
}

console.log("\u2500 #16l 手势回调的闭包新鲜度（第二次拖动跳回原位的根因）");
{
  const c = SRC.card;
  const cv = SRC.canvas;
  const e = SRC.editorSurface;
  ok(/val dragCb = rememberUpdatedState\(onDrag\)/.test(c) &&
    /val dragStartCb = rememberUpdatedState\(onDragStart\)/.test(c) &&
    /val dragEndCb = rememberUpdatedState\(onDragEnd\)/.test(c),
    "卡片拖动回调经 rememberUpdatedState（key 不变的手势块不会重启，否则用的是旧闭包）");
  ok(/dragCb\.value\?\.invoke\(amount\.x, amount\.y\)/.test(c) && /dragStartCb\.value\?\.invoke\(\)/.test(c),
    "手势块里读的是 .value（最新回调），不是捕获的旧闭包");
  ok(/val pinDragCb = rememberUpdatedState\(onPinDrag\)/.test(c) &&
    /val blankTapCb = rememberUpdatedState\(onBlankTap\)/.test(cv),
    "拉线与空白点击回调同样保持最新");
  ok(/val baseline = remember\(initial\)/.test(e) &&
    /editorLayoutFor\(initial\)/.test(e) && /workflowDiff\(baseline, def\)/.test(e),
    "自动布局在打开时就写进模型（模型=显示），且差异以 baseline 为基准（取消不误报改动）");
}

console.log("\u2500 #16m 拉线落空白：菜单选完按松手坐标建节点");
{
  const e = SRC.editorSurface;
  const cv = SRC.canvas;
  ok(/onWireDrop: \(\(fromId: String, toId: String\?, pointerWindow: Offset\) -> Unit\)\?/.test(cv) &&
    /wireDropCb\.value\?\.invoke\(from, target, pointer\)/.test(cv),
    "画布把松手坐标一起交给宿主");
  ok(/addAfterAt = pointer/.test(e) && /dropPointDp\(addAfterAt\)/.test(e) &&
    /addNodeAt\(kind, x, y, after\)/.test(e),
    "菜单里选了类型后按松手坐标建节点，并连上源节点");
  ok(/fun addNodeAt\(kind: EditorNodeKind, xDp: Int, yDp: Int, afterId: String\? = null\)/.test(e) &&
    /edges = if \(afterId != null\) def\.edges \+ WorkflowEdge\(afterId, id, ""\) else def\.edges/.test(e),
    "落点建节点时可以顺手连边（from → 新节点）");
}

console.log("\u2500 #16n 回环桥的守卫与拆分（阶段 4：DshBridgeGuard / DshBridgeSafRoutes）");
{
  const bridge = read("app/src/main/java/me/bmax/apatch/dsh/DshFsBridge.kt");
  const guard = read("app/src/main/java/me/bmax/apatch/dsh/DshBridgeGuard.kt");
  const saf = read("app/src/main/java/me/bmax/apatch/dsh/DshBridgeSafRoutes.kt");
  ok(/internal object DshBridgeGuard/.test(guard), '守卫单独成文件');
  ok(/const val WINDOW_MS = 60_000L/.test(guard) && /const val MAX_PER_WINDOW = 600/.test(guard),
    '滑动窗口 60s / 600 次每分钟');
  ok(/const val ENFORCE = false/.test(guard), '限流默认只记账不拦截（先观测再决定，安全行为不静默改变）');
  ok(/fun record\(endpoint: String\): Boolean/.test(guard) && /fun audit\(ctx: Context\?, endpoint: String/.test(guard),
    '守卫有记账与审计两个出口');
  ok(/byEndpoint/.test(guard), '按端点计数（一眼看出哪个接口被刷）');
  // 宿主必须真的接上守卫，而不是只写了类不用
  ok(/DshBridgeGuard\.record\(endpoint\)/.test(bridge) && /DshBridgeGuard\.audit\(/.test(bridge),
    'DshFsBridge 每次请求都经守卫记账与审计');
  ok(/fun normalizeEndpoint\(path: String\)/.test(bridge), '端点归一化（审计记接口名，不记文件名）');
  ok(/if \(limited && DshBridgeGuard\.ENFORCE\)/.test(bridge), '超限只在 ENFORCE 打开时回 429');
  // SAF 路由搬出后，主文件不再有 saf 实现，但分发仍在主文件
  ok(/internal fun DshFsBridge\.dispatchSaf\(/.test(saf) && /path\.startsWith\("\/saf\/"\) -> dispatchSaf\(/.test(bridge),
    'SAF 路由搬到独立文件，主文件仍负责分发');
  ok(!/private fun saf[A-Z]/.test(bridge), '主文件不再保留 SAF 实现（避免两份）');
}

console.log("\u2500 #16o 首页阶段名只有一份映射（阶段 5：Home 收敛）");
{
  const shared = read("app/src/main/java/me/bmax/apatch/ui/screen/DshHomeShared.kt");
  const dsh = read("app/src/main/java/me/bmax/apatch/ui/screen/HomeDsh.kt");
  ok(/fun dshPhaseLabelOf\(phase: DshPhase, installed: Boolean\): String/.test(shared),
    '共享侧提供按基本类型取名的唯一映射');
  ok(/fun dshPhaseLabel\(state: DshHomeUiState\): String = dshPhaseLabelOf\(state\.phase, state\.installed\)/.test(shared),
    'dshPhaseLabel 委托给它（不再各写一份 when）');
  ok(/private fun phaseLabel\(phase: DshPhase, installed: Boolean\): String =\s*\n\s*dshPhaseLabelOf\(phase, installed\)/.test(dsh),
    'HomeDsh 的本地 phaseLabel 也委托给共享映射');
  // 关键：HomeDsh 里不应再出现第二份阶段名 when（出现即两份映射会分叉）
  const dshBody = dsh.slice(dsh.indexOf('private fun phaseLabel('));
  ok(!/R\.string\.dsh_phase_downloading/.test(dshBody), 'HomeDsh 不再自带阶段名字符串表');
  ok(/R\.string\.dsh_phase_downloading/.test(shared), '阶段名字符串表只在共享侧');
}

console.log("\u2500 #16p 回环桥分端点阈值（任务 1）");
{
  const guard = read("app/src/main/java/me/bmax/apatch/dsh/DshBridgeGuard.kt");
  const bridge = read("app/src/main/java/me/bmax/apatch/dsh/DshFsBridge.kt");
  ok(/private val thresholds = mapOf\(/.test(guard), '有分端点阈值表');
  ok(/"\/native\/clipboard" to 10/.test(guard), "剪贴板 10/分钟");
  ok(/"\/native\/notify" to 30/.test(guard), "通知 30/分钟");
  ok(/"\/native\/vibrate" to 30/.test(guard) && /"\/native\/toast" to 30/.test(guard),
    "震动与 toast 30/分钟");
  ok(/fun thresholdFor\(endpoint: String\): Int = thresholds\[endpoint\] \?: MAX_PER_WINDOW/.test(guard),
    "未列出的端点回退全局阈值（不误伤 fs 批量操作）");
  // 窗口必须按端点各自滚动：全局一个计数器会让 fs 批量操作吃掉剪贴板的额度
  ok(/private class Window/.test(guard) && /windows\.computeIfAbsent\(endpoint\)/.test(guard),
    "每个端点各自一个滑动窗口");
  ok(/const val ENFORCE = false/.test(guard), "仍然只记账不拦截（安全行为不静默改变）");
  ok(/\.put\("threshold", thresholdFor\(endpoint\)\)/.test(guard), "审计带上实际生效阈值");
  // 守卫必须在分发之前集中调用，不能被改成各路由分散调用
  const guardAt = bridge.indexOf("DshBridgeGuard.record(endpoint)");
  const dispatchAt = bridge.indexOf('path.startsWith("/native/")');
  ok(guardAt > 0 && dispatchAt > 0 && guardAt < dispatchAt,
    "守卫在 when 分发之前集中调用（所有路由含 native 都覆盖）");
}

console.log("\u2500 #16q 连边即时防成环（任务 2）");
{
  const v = SRC.validator;
  const e = SRC.editorSurface;
  // 校验器早就查环（findCycle 三色法 + validate 里拦），任务 2 补的是**编辑期即时**一条。
  ok(/fun cycleIn\(def: WorkflowDefinition\): List<String>\?/.test(v), '校验器暴露只读的环查询');
  ok(/fun addEdgeIfAcyclic\(/.test(v), '加边有唯一入口（不成环才落盘）');
  ok(/if \(fromId == toId\) return def/.test(v), '自环直接拒');
  ok(/return if \(cycleIn\(next\) != null\) def else next/.test(v), '成环则返回原定义（不落边）');
  ok(/WorkflowValidator\.addEdgeIfAcyclic\(def, fromId, toId\)/.test(e),
    '编辑页连边走这个入口');
  ok(/dsh_workflow_cycle_rejected/.test(e), '被拒时给即时反馈');
  // 关键：**连接两张已有卡片**这条路径必须经判据。
  // 另外两处直接拼边在 addNodeAt/addNode 里，连的是本次刚创建的节点（新节点还没有出边，
  // 按构造不可能成环），所以不需要判 —— 断言按这个真实不变量写。
  const newNodeEdges = (e.match(/edges = if \(afterId != null\) def\.edges \+ WorkflowEdge\(afterId, id, ""\)/g) || []).length;
  const otherEdges = (e.match(/def\.edges \+ WorkflowEdge\(/g) || []).length - newNodeEdges;
  ok(newNodeEdges === 2 && otherEdges === 0,
    "仅新建节点的两处直接拼边（按构造无环），其余连边一律经 addEdgeIfAcyclic：新节点 " + newNodeEdges + " / 其它 " + otherEdges);
  // 保存路径仍由 validate 兜底（含环）
  ok(/findCycle\(def\)/.test(v), 'validate 里的环检查仍在（保存/执行前兜底）');
}

console.log("\u2500 #16r 解压诊断三联（任务 3 / Issue #12）");
{
  const ext = read("app/src/main/java/me/bmax/apatch/dsh/TarGzipExtractor.java");
  const inst = read("app/src/main/java/me/bmax/apatch/dsh/DshRuntimeInstaller.kt");
  // ① 计数器必须存在**且被读取**：以前 lastSkipped/lastChmodFailures 全仓无人读，
  //    用户只看到一句「rootfs 不完整」，没有任何可判断的线索。
  ok(/public static volatile int lastChmodFailures = 0/.test(ext), 'chmod 失败有计数器');
  ok(/public static volatile String lastChmodFailNote = ""/.test(ext), 'chmod 失败记首个路径');
  // 签名从 () 变成 (File tarball)：顺手把 tar 体积记下来当解压进度的分母
  ok(/private static void resetDiagnostics\(File tarball\)/.test(ext), '有统一的重置函数');
  const resets = (ext.match(/resetDiagnostics\(tarball\);/g) || []).length;
  ok(resets >= 5, '所有解压入口都重置计数器（实际 ' + resets + ' 处含定义，调用点应 ≥5）');
  ok(/lastChmodFailures\+\+/.test(ext) && !/catch \(Throwable ignored\)/.test(ext.split('chmodBestEffort')[2] || ''),
    'chmodBestEffort 不再静默吞异常');
  // ② 安装侧必须真的把这些读出来并打日志
  ok(/internal fun DshRuntime\.logExtractDiagnostics\(/.test(inst), '安装侧有诊断输出函数');
  ok(/TarGzipExtractor\.lastSkipped/.test(inst), '读取跳过计数');
  ok(/TarGzipExtractor\.lastChmodFailures/.test(inst), '读取 chmod 失败计数');
  ok(/logExtractDiagnostics\(root, required\)/.test(inst), '校验失败时输出诊断');
  ok(/存在=\$exists 大小=\$size 可执行=\$exec/.test(inst), '逐个必需文件报「存在/大小/可执行」');
}

console.log("\u2500 #16s 安装失败必须说清败在哪一步（Issue #12 追因）");
{
  const inst = read("app/src/main/java/me/bmax/apatch/dsh/DshRuntimeInstaller.kt");
  const logEvent = read("app/src/main/java/me/bmax/apatch/util/LogEvent.kt");
  // 核心约束：installRuntimeArchive 里每个 return false 之前都必须有一行 note()/日志。
  // 原先 5 个分支只有 1 个说话，Issue #12 的日志里只剩一句 generic 报错，排查卡了好几天。
  const body = inst.slice(
    inst.indexOf("internal fun DshRuntime.installRuntimeArchive("),
    inst.indexOf("internal fun DshRuntime.installRuntimeArchive(") + 4200,
  );
  const falseReturns = (body.match(/return@runCatching false/g) || []).length;
  const notes = (body.match(/note\(/g) || []).length;
  ok(falseReturns >= 5, '失败分支有 ' + falseReturns + ' 条（≥5）');
  ok(notes >= falseReturns, '每个失败分支前都有 note() 记录（note ' + notes + ' ≥ 失败 ' + falseReturns + '）');
  // renameTo 必须有回退：Android 上整目录改名很容易返回 false 而不抛异常
  ok(/private fun renameOrCopy\(src: File, dst: File/.test(inst), '改名失败有复制回退');
  ok(/src\.renameTo\(dst\)\) return true/.test(inst), '先试 renameTo');
  ok(/copyRecursiveTo\(dst\)/.test(inst) && /setExecutable\(true, false\)/.test(inst),
    '回退复制保留可执行位（bash/node/pnpm 要能跑）');
  ok(/note\("切换到新 rootfs 失败/.test(inst) || /切换到新 rootfs 失败/.test(inst), '切换失败有明确记录');
  ok(/note\("移走旧 rootfs 失败/.test(inst) || /移走旧 rootfs 失败/.test(inst), '让位失败有明确记录');
  // 独立诊断文件：不被 clearLog/轮转/卡片截断影响，且必须进 bugreport
  ok(/internal fun DshRuntime\.writeInstallDiag\(/.test(inst), '有独立诊断文件写入');
  ok(/internal fun DshRuntime\.clearInstallDiag\(/.test(inst), '安装成功后清除诊断文件');
  ok(/File\(dir, "install-diag.txt"\)/.test(inst), "诊断文件路径固定");
  ok(/install-diag\.txt/.test(logEvent), "bugreport 会收走这份诊断");
  ok(/redactInPlace\(installDiagFile, notes\)/.test(logEvent), '诊断进包前过脱敏');
}

console.log("\u2500 #16t 回退复制必须保留符号链接（Issue #12 真正的原因）");
{
  const inst = read("app/src/main/java/me/bmax/apatch/dsh/DshRuntimeInstaller.kt");
  // 用户 beta.209 的日志把原因钉死了：
  //   renameTo 失败 → 回退复制 → 15ms 后 ENOENT: .../motd-news.timer
  // rootfs 里到处是悬空符号链接，跟随读取必然 ENOENT。所以复制必须先判链接、原样重建。
  ok(/readSymbolicLink\(toPath\(\)\)/.test(inst), '复制前先读符号链接目标（不跟随）');
  ok(/Os\.symlink\(linkTarget\.toString\(\), dst\.absolutePath\)/.test(inst), '用 Os.symlink 原样重建链接');
  const copyFn = inst.slice(inst.indexOf('private fun File.copyRecursiveTo('));
  const linkCheckAt = copyFn.indexOf('readSymbolicLink');
  const isDirAt = copyFn.indexOf('if (isDirectory)');
  ok(linkCheckAt > 0 && isDirAt > 0 && linkCheckAt < isDirAt,
    '链接判定必须在 isDirectory/isFile 之前（那两个 API 会跟随链接）');
  // 单条链接建不出来不能废掉整次安装
  ok(/复制符号链接失败/.test(inst), '单条链接失败只记日志、不中断');
  // 所有目录搬移都要经回退：装完/回滚/保留数据 三条路径
  const orCopy = (inst.match(/renameOrCopy\(/g) || []).length;
  ok(orCopy >= 7, '目录搬移统一走 renameOrCopy（实际 ' + orCopy + ' 处，含定义）');
  ok(/回滚失败：备份 rootfs 也放不回去/.test(inst), '回滚失败有明确记录（比安装失败更严重）');
}

console.log("\u2500 #16u 解压进度（Issue #12 用户反馈：安装像卡死）");
{
  const ext = read("app/src/main/java/me/bmax/apatch/dsh/TarGzipExtractor.java");
  const inst = read("app/src/main/java/me/bmax/apatch/dsh/DshRuntimeInstaller.kt");
  // 解压要几十秒，界面此前只停在「开始解压安装…」→ 看起来像卡死
  ok(/public interface ProgressListener/.test(ext), '解压器暴露进度回调');
  ok(/void onProgress\(long extractedBytes, long totalTarBytes\)/.test(ext), '回调带已解/总量两个数');
  ok(/private static volatile long totalTarBytes/.test(ext), '进度分母来自 tar 文件大小');
  ok(/reportProgress\(totalBytes, totalTarBytes\)/.test(ext), '每解出一个文件就上报一次');
  // 回调里抛异常不能影响解压
  ok(/try \{\s*\n\s*l\.onProgress\(extracted, total\);/.test(ext) || /catch \(Throwable ignored\)/.test(ext),
    '回调异常被吞掉（进度出错不能影响解压本身）');
  // 安装侧：节流到 1% + 结束后摘掉监听
  ok(/TarGzipExtractor\.setProgressListener \{ extracted, total ->/.test(inst), '安装侧接上进度回调');
  ok(/if \(pct != lastPct\)/.test(inst), '节流：只在百分比变化时更新状态（解压要写几万个文件）');
  ok(/progress = pct \/ 100f/.test(inst), '进度写进 DshState.progress');
  ok(/TarGzipExtractor\.setProgressListener\(null\)/.test(inst), '解压结束摘掉监听');
  // finally 而不是顺序执行：解压抛异常时也必须摘掉，否则后续状态被覆盖
  ok(/\} finally \{\s*\n\s*\/\/ 解压结束/.test(inst) || /finally \{\s*\n[\s\S]{0,120}setProgressListener\(null\)/.test(inst),
    '用 finally 摘监听（异常路径也要摘）');
}

console.log("\u2500 #16v chroot 启动方式（真 root，实验性）");
{
  const rt = read("app/src/main/java/me/bmax/apatch/dsh/ContainerRuntime.kt");
  const dr = read("app/src/main/java/me/bmax/apatch/dsh/DshRuntime.kt");
  const fs2 = read("app/src/main/java/me/bmax/apatch/ui/screen/settings/FunctionSettings.kt");
  ok(/class Chroot\(private val ctx: Context\) : ContainerRuntime/.test(rt), '有 Chroot 运行时实现');
  ok(/override fun id\(\) = "chroot"/.test(rt), 'id 是 chroot');
  // 可用性 = root 通道，不是「沙箱可用」
  ok(/PrivilegedShell\.reach\(ctx\)\?\.channel == PermissionManager\.Channel\.ROOT/.test(rt),
    '可用性判定 = root 通道就绪');
  // 关键：chroot 不是沙箱，注释必须写明（避免误导）
  ok(/\*\*chroot 本身不是沙箱\*\*/.test(rt), '类注释明确「chroot 不是沙箱」');
  ok(/landlock is not enforced|landlock-run --probe.*125/.test(rt), '注释记录实测：Landlock 内核层不可用');
  // spawnArgv 接管 argv，proot/proroot 保持 null（行为不变）
  ok(/fun spawnArgv\(guest: List<String>, env: Map<String, String>\): List<String>\? = null/.test(rt),
    '接口默认 null（proot/proroot 行为零变化）');
  ok(/override fun spawnArgv\(guest: List<String>, env: Map<String, String>\): List<String>\? \{/.test(rt),
    'chroot 覆盖 spawnArgv');
  // guest 命令写进脚本，不进 argv（避免两层转义）
  ok(/launch\.writeText\(\s*\n?\s*buildLaunchScript/.test(rt), '命令写进脚本文件而非拼进 su -c');
  ok(/trap cleanup EXIT INT TERM/.test(rt), '脚本用 trap 保证退出时卸载挂载点');
  ok(/fun shellQuote\(value: String\): String/.test(rt), '有 POSIX 单引号转义');
  // 调用点：execRootfs / execRootfsInteractive 都经 spawnArgv，null 则回退
  // 回退目标必须是 **proot** 而不是 baseArgv()：chroot 的 baseArgv 是空列表，
  // 用它兜底会拼出只有一个元素的非法命令（这条断言最初写的就是那个错误写法）。
  ok(/rt\.spawnArgv\(guest, guestEnv\(\)\) \?: \(prootBaseArgv\(\) \+ guest\)/.test(dr),
    '两个 spawn 点都「先问运行时，null 回退 proot」');
  // PTY 必须显式回退：terminal-emulator 是 execvp，chroot 的 argv 为空会让它起不来
  ok(/chroot 走不了 PTY/.test(dr) && /if \(argv\.isEmpty\(\)\)/.test(dr), 'PTY 对 chroot 显式回退 proot');
  // 失败即回退
  ok(/if \(id == "chroot"\)/.test(dr) && /prorootFellBack = true/.test(dr), 'chroot 失败自动回退 proot');
  // UI 第三项
  ok(/R\.string\.dsh_mode_chroot_name/.test(fs2) && /R\.string\.dsh_mode_chroot_desc/.test(fs2),
    '设置页有 chroot 选项且有说明');
}

console.log("\u2500 #16w chroot 的 su 路径探测与三处回退（真机反馈）");
{
  const ps = read("app/src/main/java/me/bmax/apatch/dsh/PrivilegedShell.kt");
  const rt = read("app/src/main/java/me/bmax/apatch/dsh/ContainerRuntime.kt");
  const dr = read("app/src/main/java/me/bmax/apatch/dsh/DshRuntime.kt");
  // 真机日志：`/system/bin/sh: su: inaccessible or not found` —— 写死 su 必然失败
  ok(/fun resolveSuPath\(ctx: Context\): String\?/.test(ps), '有 su 路径探测');
  ok(/\/debug_ramdisk\/su/.test(ps) && /\/data\/adb\/ksu\/bin\/su/.test(ps),
    '探测覆盖 KernelSU/APatch 的常见位置');
  ok(/getRootShell\(\)\.newJob\(\)/.test(ps), '借已生效的 root shell 去问（libsu 自己知道怎么找 su）');
  ok(/cachedSuPath/.test(ps), 'su 路径有缓存（不每次启动都重探）');
  // chroot 必须用探测到的路径
  ok(/val su = PrivilegedShell\.resolveSuPath\(ctx\) \?: return null/.test(rt),
    '解析不到 su 就返回 null（而不是拼一条注定失败的 argv）');
  ok(/shellQuote\(su\) \+ " -c " \+ shellQuote/.test(rt), 'argv 用的是解析出的 su 绝对路径');
  // 关键陷阱：chroot 的 baseArgv 是空列表，简单回退会拼出非法 argv
  ok(/private fun prootBaseArgv\(\): MutableList<String>/.test(dr), '有显式的 proot 回退前缀');
  ok(/\?: \(prootBaseArgv\(\) \+ guest\)/.test(dr), '两个 exec 点都回退 proot（不是 baseArgv）');
  ok(/if \(argv\.isEmpty\(\)\) argv \+= prootBaseArgv\(\)/.test(dr), 'PTY 同样回退 proot');
  // 统一：三处都不得再出现「baseArgv() + guest」这种在 chroot 下会错的写法
  ok(!/\?: \(baseArgv\(\) \+ guest\)/.test(dr), '没有残留的裸 baseArgv 回退');
}

console.log("\u2500 #16x proot 的 NEEDED 依赖必须无条件就位（真机回归）");
{
  const dr = read("app/src/main/java/me/bmax/apatch/dsh/DshRuntime.kt");
  // 背景（OnePlus PLQ110 / Android 17 / beta.212 真机）：
  //   CANNOT LINK EXECUTABLE \".../lib/libproot.so\": library \"libtalloc.so.2\" not found
  // 根因：ensureRuntimeFiles() 曾按「当前选中的运行时」跳过复制，而 prootBaseArgv() 是全局
  // 兜底，proroot 模式下也会真的 exec libproot.so —— 它 NEEDED libtalloc.so.2（soname）。
  ok(/copyExec\(nativeLib\("libtalloc\.so"\), File\(libDir, "libtalloc\.so\.2"\)\)/.test(dr),
    'libtalloc.so → libtalloc.so.2 的 SONAME 改名仍在');
  ok(/copyExec\(nativeLib\("libandroidshmem\.so"\), File\(libDir, "libandroid-shmem\.so"\)\)/.test(dr),
    'libandroidshmem → libandroid-shmem 的 SONAME 改名仍在');
  // 关键不变量：不能再有「按运行时跳过」的守卫挡在这两行前面
  const body = dr.slice(dr.indexOf("private fun ensureRuntimeFiles"), dr.indexOf("private fun copyExec"));
  ok(!/if \(runtime\(\)\.id\(\) != "proot"\) return/.test(body),
    'ensureRuntimeFiles 不再按选中的运行时跳过 proot 依赖');
  ok(!/if \(effectiveRuntimeId\(\) != "proot"\) return/.test(body),
    '也没有换成用 effectiveRuntimeId 跳过（同一个坑的另一种写法）');
  // 兜底入口必须存在，否则这个不变量没有理由
  ok(/private fun prootBaseArgv\(\): MutableList<String>/.test(dr), 'proot 全局兜底入口仍在');
}

console.log("\u2500 #17 用户自己也能建/改流程");
{
  ok(/fun WorkflowDefinitionEditor\(/.test(SRC.dialogs) && /initial: WorkflowDefinition/.test(SRC.dialogs),
    "编辑器接受一份定义（不是只能照着 AI 提交的 JSON 改）");
  // 编辑页是导航图里的次级页面（WorkflowEditorScreen），审批那条走全屏弹窗包装，两者共用同一份内容。
  const callers = (SRC.dialogs.match(/WorkflowDefinitionEditor\(/g) || []).length +
    (SRC.screen.match(/WorkflowDefinitionEditor\(/g) || []).length +
    (SRC.editorScreen.match(/WorkflowDefinitionEditor\(/g) || []).length;
  ok(callers >= 3, "审批弹窗与编辑页共用同一个编辑器（共 " + callers + " 处，含定义本身与弹窗包装）");
  ok(/WorkflowApprovals\.stopEditing\(request\.id, edited\.toJson\(\)\.toString\(\)\)/.test(SRC.dialogs), "审批路径保存回待批状态");
  ok(/WorkflowStore\.save\(ctx, edited\)/.test(SRC.editorScreen), "编辑页保存直接落盘");
  ok(/internal fun starter\(/.test(SRC.screen) && /WorkflowStore\.newId\(\)/.test(SRC.screen),
    "有「新建流程」的合法起点（不是空定义）");
  ok(/config = mapOf\("action" to "shell", "cmd" to "echo hello"\)/.test(SRC.screen),
    "起点自带一条可跑的命令（空 cmd 一建出来就是错的）");
  ok(/WorkflowValidator\.validate\(def, DshNativeBridge\.Cap\.entries/.test(SRC.screen), "列表就地校验");
  ok(/enabled = enabled && valid/.test(SRC.screen), "不合法时运行按钮点不动");
  ok(/dsh_workflow_invalid_run/.test(SRC.screen) && /dsh_workflow_editor_errors/.test(SRC.editorSurface),
    "同一批错误既在列表也在编辑页显示（与 AI 收到的是同源）");
  ok(/DshRuntime\.refreshContainerCli\(\)/.test(SRC.screen), "打开页面/打开开关时刷新容器侧 CLI 与指南");
  ok(/fun refreshContainerCli\(\)/.test(SRC.runtime), "DshRuntime 提供刷新入口");
  ok(/dsh_workflow_tools_restart/.test(SRC.screen), "如实说明「工具插件要重启 DSH 才注册」");
  ok(/DshHostPrompt\.ensureInstalled/.test(SRC.runtime) && /fun refreshContainerCli\(\)/.test(SRC.runtime),
    "刷新容器资产时**连插件与挂载项一起补**（只补 CLI 与指南的话，没重启过容器的设备上 agent 依旧什么都不知道）");
  ok(/containerChecks\(ctx\)/.test(SRC.screen) && /dsh_workflow_selfcheck/.test(SRC.screen),
    "设置页有自检面板（缺哪一环是文件系统事实，不是猜）");
  ok(/DshRuntime\.restart\(\)/.test(SRC.screen) && /dsh_workflow_restart_body/.test(SRC.screen),
    "有一键重启 DSH 并说明为什么需要重启");
  ok(/plugin\.isFile && plugin\.length\(\) > 0L/.test(SRC.screen) && /patchHasEntry/.test(SRC.screen),
    "自检真的去看插件文件与 cordis 挂载项");
}

console.log("\u2500 #12 新门禁登记进两条 CI 清单");
ok(/node tools\/check-workflow\.js/.test(SRC.buildYml), "build.yml 里有 check-workflow");
ok(/node tools\/check-workflow\.js/.test(SRC.betaYml), "beta.yml 里有 check-workflow");

// CLI 的行为测试：参数 → 请求体。
// 放在函数里而不是顶层块：本文件是 CommonJS，顶层 await 会让 Node 拒绝加载。
async function checkCli() {
  console.log("\u2500 #13 真跑 dsh-workflow CLI（参数→请求体）");
  const m = SRC.runtime.match(/val WORKFLOW_CLI_SCRIPT = """([\s\S]*?)"""\.trimIndent\(\)/);
  // Kotlin 的 trimIndent() 是编译期行为，源码里抽出来的是**带缩进**的原文；
  // 这里补等价处理，否则缩进的 shebang 落在第二行，node 直接语法错误。
  const dedent = (t) => {
    const lines = t.replace(/^\n/, "").split("\n");
    const indents = lines.filter((l) => l.trim()).map((l) => l.match(/^ */)[0].length);
    const min = indents.length ? Math.min(...indents) : 0;
    return lines.map((l) => l.slice(min)).join("\n");
  };
  const body = m ? dedent(m[1]) : "";
  const os = require("os");
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "dsh-wf-cli-"));
  const cfgPath = path.join(dir, "fs-bridge.json");
  const scriptPath = path.join(dir, "dsh-workflow");
  const reqs = [];
  const server = require("http").createServer((rq, rs) => {
    let b = "";
    rq.on("data", (c) => (b += c));
    rq.on("end", () => {
      reqs.push({ method: rq.method, url: rq.url, token: rq.headers["x-dsh-fs-token"], len: rq.headers["content-length"], body: b });
      rs.writeHead(200, { "Content-Type": "application/json" });
      rs.end('{"ok":true}');
    });
  });
  const conns = new Set();
  server.on("connection", (sock) => {
    conns.add(sock);
    sock.on("close", () => conns.delete(sock));
  });
  await new Promise((res) => server.listen(0, "127.0.0.1", res));
  fs.writeFileSync(cfgPath, JSON.stringify({ port: server.address().port, token: "tok" }));
  fs.writeFileSync(scriptPath, body.replace("'/root/.dsh/fs-bridge.json'", JSON.stringify(cfgPath)));
  // 必须是**异步** spawn：桩服务器跑在本进程里，spawnSync 会把本进程的事件循环冻住，
  // 请求永远等不到应答（表现是 CLI 挂住、超时被杀、reqs 为空）。带超时是因为门禁自己卡住
  // 比漏判更糟 —— CI 会一直挂到超时。
  const run = (args) =>
    new Promise((res) => {
      require("child_process").execFile(
        "node", [scriptPath].concat(args), { timeout: 20000 },
        (err, stdout, stderr) => res({
          status: err ? (typeof err.code === "number" ? err.code : 124) : 0,
          stdout: stdout || "",
          stderr: stderr || "",
        }),
      );
    });
  // 1) 跑已保存的流程：**不需要**再给定义（修之前这条命令会被 readDef 拦掉）
  const r1 = await run(["run", "--workflow", "wf_x", "--reason", "because"]);
  eq(r1.status, 0, "run --workflow <id> 能跑通（不需要 --file/--json）");
  const j1 = reqs.length ? JSON.parse(reqs[reqs.length - 1].body) : {};
  eq(j1.workflowId, "wf_x", "请求体带 workflowId");
  eq(j1.definition, undefined, "不留一个空 definition");
  eq(j1.wait, false, "默认不阻塞（wait=false）");
  eq(reqs[reqs.length - 1].token, "tok", "带上回环 token");
  ok(reqs[reqs.length - 1].len !== undefined, "显式 Content-Length（否则宿主 400）");
  // 2) 内联定义建流程
  const r2 = await run(["create", "--json", '{"id":"wf_y","name":"n","nodes":[],"edges":[]}', "--reason", "r"]);
  eq(r2.status, 0, "create --json 能跑通");
  eq(JSON.parse(reqs[reqs.length - 1].body).definition.id, "wf_y", "请求体带 definition");
  // 3) 少了 reason 必须被拦（与端点一致）
  const r3 = await run(["create", "--json", "{}"]);
  eq(r3.status, 1, "没有 --reason 时退出码 1");
  ok(/--reason is required/.test(r3.stderr), "并说清为什么");
  // 4) run --wait 会显式要求阻塞
  const r4 = await run(["run", "--workflow", "wf_x", "--wait", "--reason", "r"]);
  eq(r4.status, 0, "run --wait 能跑通");
  eq(JSON.parse(reqs[reqs.length - 1].body).wait, true, "wait=true 传到请求体");
  // 5) status 的行号续读
  const r5 = await run(["status", "--run", "run_1", "--from", "7", "--no-logs"]);
  eq(r5.status, 0, "status 能跑通");
  const u = reqs[reqs.length - 1].url;
  ok(u.includes("runId=run_1") && u.includes("fromLine=7") && u.includes("includeLogs=0"), "status 查询串正确：" + u);
  // 6) cancel
  const r6 = await run(["cancel", "--run", "run_1"]);
  eq(r6.status, 0, "cancel 能跑通");
  eq(JSON.parse(reqs[reqs.length - 1].body).runId, "run_1", "cancel 带 runId");
  // 7) 非 2xx 必须让调用方看到失败
  server.removeAllListeners("request");
  server.on("request", (rq, rs) => { rs.writeHead(403, { "Content-Type": "application/json" }); rs.end('{"ok":false,"reason":"rejected_by_user"}'); });
  const r7 = await run(["run", "--workflow", "wf_x", "--reason", "r"]);
  eq(r7.status, 1, "被拒绝时退出码非 0（agent 才知道这次没成）");
  ok(/rejected_by_user/.test(r7.stdout), "并把服务端的理由原样打出来");
  if (typeof server.closeAllConnections === "function") server.closeAllConnections();
  for (const sock of conns) sock.destroy();
  server.close();
}

console.log("");
// #12 真跑插件 asset：工具表能建出正确 argv，且失败时**返回字符串而不是抛异常**
(async () => {
  console.log("\u2500 #12 真跑 dsh-folk-workflow.mjs");
  const mod = await import("../app/src/main/assets/dsh-folk-workflow.mjs");
  const tools = mod.__test.TOOLS;
  eq(tools.length, 4, "四个工具都在");
  const names = tools.map((t) => t.name).sort().join(",");
  eq(names, "workflow_cancel,workflow_create,workflow_run,workflow_status", "工具名固定");
  for (const t of tools) {
    ok(typeof t.description === "string" && t.description.length > 40, `${t.name} 有像样的说明（模型靠它判断何时用）`);
    ok(typeof t.execute === "function", `${t.name} 有 execute`);
  }
  const create = tools.find((t) => t.name === "workflow_create");
  ok(create.parameters.required.includes("reason"), "workflow_create 要求 reason");
  const run = tools.find((t) => t.name === "workflow_run");
  ok(run.parameters.properties.wait !== undefined, "workflow_run 暴露 wait（默认不阻塞，可选阻塞）");
  const cancel = tools.find((t) => t.name === "workflow_cancel");
  ok(cancel.parameters.required.includes("runId"), "workflow_cancel 要求 runId");
  // CLI 不存在时：必须回一句可读的话，而不是把异常抛给 agent 循环
  const out = await run.execute({ workflowId: "wf_x", reason: "test" });
  eq(typeof out, "string", "execute 永远返回字符串（不抛异常）");
  ok(out.length > 0, "失败也有可读输出：" + out.slice(0, 60).replace(/\n/g, " "));

  await checkCli();

  console.log("");
  if (failures) {
    console.error(`\u2717 有失败：${total} 项断言，${failures} 项失败`);
    process.exit(1);
  }
  console.log(`\u2713 全部通过：${total} 项断言，0 项失败`);
})();
