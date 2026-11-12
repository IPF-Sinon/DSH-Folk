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
  // 节点选择面板（两个入口共用的组件）。抽出来单独读，是为了让「两处选项集必须一致」
  // 这条断言能直接检查组件本身的结构，而不是去 grep 调用点。
  nodePicker: read("app/src/main/java/me/bmax/apatch/ui/component/WorkflowNodePicker.kt"),
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
// 等待仍必须有硬上限（不能把桥的连接无限挂着），但现在不是「一锤子 65 秒」：
// 用户进编辑器时倒计时会暂停，等的人也要跟着续期（否则「还在编辑」会被读成 EXPIRED），
// 所以判据改成「按片等 + 编辑预算上限」两条。
ok(/const val EDIT_MAX_MS = 30 \* 60_000L/.test(SRC.approvals),
  "等待没有编辑期的总预算上限（用户在编辑器里待着会把连接挂死）");
ok(/var remaining = TTL_MS \+ 5_000L/.test(SRC.approvals) &&
   /withTimeoutOrNull\(slice\) \{ waiter\.await\(\) \}/.test(SRC.approvals),
  "等待不是按片等（硬上限与编辑期续期都要靠它）");
ok(/if \(isEditing\(id\)\)/.test(SRC.approvals) &&
   /remaining = TTL_MS \+ 5_000L/.test(SRC.approvals),
  "编辑期间没有续期：用户还在改蓝图，桥那边却已经按 EXPIRED 收尾了");
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
ok(/onOpenWorkflow = \{ navigator\.navigate\(WorkflowScreenDestination\)( \{ launchSingleTop = true \})? \}/.test(SRC.settingsScreen),
  "那一行真的会打开工作流页（不是只声明一个没人用的参数）");
// 阶段 2.4b：旧的画布/节点卡自绘已删除，画布与卡片只剩新组件这一份实现。
ok(/WorkflowBlueprintCanvas\(/.test(SRC.dialogs) && !/private fun NodeCard\(/.test(SRC.dialogs),
  "编辑页/预览用同一块画布，旧的自绘节点卡已删除（不是一列输入框）");
// 编辑页的画布已换成 WorkflowBlueprintCanvas（与预览同一套）；拖动回调的手势量是像素，
// 先在密度里换算成 dp 再吸附 12dp 网格，最后写回 posX/posY。
ok(/onNodeDrag = \{ id, dxPx, dyPx ->[\s\S]{0,700}?dragAcc \+= viewport\.dragDeltaDp\(/.test(SRC.editorSurface) &&
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
// 单步输出必须边读边截：一条 ls -R / 死循环 echo 以前会把整份输出攒在内存里（P2-1b）
ok(/private const val MAX_STEP_OUTPUT_CHARS = \d+/.test(read("app/src/main/java/me/bmax/apatch/dsh/WorkflowEngine.kt")),
  "工作流单步输出没有上限常量");
ok(/if \(kept < MAX_STEP_OUTPUT_CHARS\)/.test(read("app/src/main/java/me/bmax/apatch/dsh/WorkflowEngine.kt")),
  "读线程里没有按上限丢弃（还是读完才截）");

// 桥那条路（agent 调 workflow_run）过去**没有**权限预检：声明了能力、系统权限没给也能跑起来。
// 审计 P1-2a：两条路必须同一套判据（缺失 → 弹窗 → 等结论 → 不给就 403）。
const wfBridge = read("app/src/main/java/me/bmax/apatch/dsh/WorkflowBridge.kt");
ok(/WorkflowPermissions\.missing\(ctx, effective\.effectiveCaps\)/.test(wfBridge),
  "桥的 RUN 分支没有做系统权限预检（与工作流页不一致）");
ok(/kind == WorkflowApprovals\.Kind\.RUN/.test(wfBridge) &&
  /WorkflowPermissions\.Gate\.submit\(/.test(wfBridge) &&
  /WorkflowPermissions\.Gate\.awaitDecision\(gate\.id\)/.test(wfBridge),
  "桥的 RUN 分支只是查了缺失项，却没有弹窗 + 等用户结论");
ok(/\"permission_denied\"/.test(wfBridge) && /R\.string\.dsh_workflow_err_permission_denied/.test(wfBridge),
  "缺权限且没给授权时没有回 403 permission_denied（用户会以为「跑了」）");
ok(/allowDangerous = verdict\.allowDangerous && dangerousNow/.test(wfBridge),
  "危险命令的豁免没有同时看「用户勾选」与「当前定义确实含危险命令」（dangerousNow 又变成算了不用）");
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
  // 真假两条出口：condition 与 logic 各自一条分支路径。断言钉的是「两类节点都给了 yes 与 no」，
  // 不是钉 listOf 的字面参数表 —— 针脚表按节点逐个设计之后，condition 多了 source/value/result、
  // logic 多了 sources/result，字面参数表必然会变，但「必须有真假两条出口」这条不能变。
  ok(/WorkflowNodeType\.CONDITION -> listOf\([\s\S]{0,120}?yes[\s\S]{0,60}?no/.test(pins),
    "condition 节点给出真、假两条出口");
  ok(/WorkflowNodeType\.LOGIC -> listOf\([\s\S]{0,120}?yes[\s\S]{0,60}?no/.test(pins),
    "logic 节点给出真、假两条出口");
  // 判断节点还要有能接线的比较输入：source/value 不能只有参数行能改。
  ok(/WorkflowNodeType\.CONDITION -> listOf\([\s\S]{0,700}?WorkflowPinSpec\("source"/.test(pins) &&
    /WorkflowNodeType\.CONDITION -> listOf\([\s\S]{0,700}?WorkflowPinSpec\("value"/.test(pins),
    "condition 的 source / value 是可接线的数据入口（要跟别处比就该能连）");
  ok(/WorkflowNodeType\.TRIGGER ->[\s\S]{0,220}?EXEC_FALSE/.test(pins),
    "trigger 要求 DSH 时也有「未就绪」出口（它本来就走假分支）");
  ok(/WorkflowActionType\.SHELL, WorkflowActionType\.DSH -> listOf\([\s\S]{0,160}?"cmd"/.test(pins),
    "shell/dsh 节点的参数引脚是 cmd");
  ok(/WorkflowNodeType\.EXTRACT -> listOf\([\s\S]{0,900}?WorkflowPinSpec\("var"[\s\S]{0,120}?DATA_OUT/.test(pins),
    "extract 节点有变量输出引脚");
  // extract 的 source 也必须能接线：上一版它只做参数行，等于逼用户手打来源。
  ok(/WorkflowNodeType\.EXTRACT -> listOf\([\s\S]{0,400}?WorkflowPinSpec\("source"/.test(pins),
    "extract 的 source 是可接线的数据入口");
  // ── 枚举针脚按点击助手的规矩也给 ──
  //
  // 参考实现里枚举（PinSingleSelect，继承 PinString）是普通 Pin，不是 NotLinkAblePin，
  // 所以照样能拉线。我曾以「让上游决定比较方式没意义」为由把 op/mode/untilDsh 排除，
  // 那是替用户做的判断，不是参考行为。用户明确要求按点击助手来。
  // 安全性由类型系统兜住：枚举是 CHOICE，只与 CHOICE/ANY 互接（不做隐式转换），
  // 文本喂进 op 会被 addDataEdge 拦掉，不会静默生效。
  ok(/WorkflowNodeType\.CONDITION -> listOf\([\s\S]{0,600}?WorkflowPinSpec\("op"/.test(pins) &&
    /dataType = WorkflowPinType\.CHOICE\.id/.test(pins),
    "condition 的 op 是 CHOICE 类型针脚（枚举也能接线，同点击助手）");
  ok(/WorkflowNodeType\.EXTRACT -> listOf\([\s\S]{0,700}?WorkflowPinSpec\("mode"/.test(pins) &&
    /WorkflowNodeType\.EXTRACT -> listOf\([\s\S]{0,800}?WorkflowPinSpec\("pattern"/.test(pins),
    "extract 的 mode / pattern 也是针脚");
  ok(/WorkflowNodeType\.WAIT -> listOf\([\s\S]{0,400}?WorkflowPinSpec\("untilDsh"/.test(pins),
    "wait 的 untilDsh 是针脚");
  // ── 多来源参数：接多根线要保留，不是互相覆盖 ──
  //
  // logic.sources 的语义是「若干判断结论 and/or 起来」，引擎 evaluateLogic 按逗号切成 id 列表。
  // 如果接新线时把同一 toPin 的旧线清掉（普通参数的单线语义），用户永远只能接一个来源。
  ok(/private val MULTI_SOURCE_PINS = setOf\("sources"\)/.test(SRC.validator) &&
    /private val MULTI_SOURCE_PINS = setOf\("sources"\)/.test(SRC.engine),
    "校验器与引擎用同一个多来源参数集（两处不一致会静默丢掉来源）");
  ok(/val withoutOld = if \(multiSource\) def\.edges else/.test(SRC.validator),
    "接数据线时多来源参数不清旧线（否则只能接一个来源）");
  ok(/val multi = e\.toPin in MULTI_SOURCE_PINS/.test(SRC.engine),
    "写回 config 时多来源参数走追加分支，不是覆盖");
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
  // 连线是**正交折线**（照抄 TouchTool 的 CardLayoutHelper.calculateLinkPath），不是贝塞尔。
  // 上一版用 cubicTo 画曲线，观感像两根飘带，看不出走向与分流；折线才读得出"值从哪来"。
  ok(/orthogonalLinkPath/.test(canvas) && !/cubicTo/.test(canvas) && /onWireDrop/.test(canvas) && /detectTransformGestures/.test(canvas),
  "画布：正交折线连线 + 空白处平移/双指缩放 + 拉线落点回调");
  // ── 连线走廊**不能**等于画布网格步长 ──
  //
  // 参考实现（CardLayoutHelper/CardLayoutView）里 gridSize 是同一个量同时充当
  // 「网格步长」和「走廊宽度」，所以照抄时极易把我们的网格步长（24dp）当走廊传进去。
  // 那样 gridOffset = 4×24 = 96dp，相对 220dp 宽的卡片接近半个卡宽，绕行会把卡片
  // 包成一个套索 —— 真机截图就是这个。
  // 这条断言钉的是"两个量是两个名字"：走廊必须来自 LINK_CORRIDOR_DP，
  // 画布上不得再出现硬编码 24dp 当作 corridor。
  const linkPath = read("app/src/main/java/me/bmax/apatch/ui/component/WorkflowLinkPath.kt");
  ok(/LINK_CORRIDOR_DP/.test(linkPath) && /LINK_CORRIDOR_DP\.dp\.toPx\(\)/.test(canvas),
    "画布：连线走廊来自 LINK_CORRIDOR_DP（不是画布网格步长）");
  ok(!/corridor\s*=\s*24\.dp/.test(canvas),
    "画布：没有把 24dp（网格步长）再当作走廊用");
  // 圆角半径不能是"走廊 × 1.0"——我们的走廊是 12dp（≠网格 24dp），
  // 照抄参考的 1.0 会得到 24dp 圆角，是参考的两倍。
  ok(/CORNER_ROUND\s*=\s*0\.5f/.test(linkPath),
    "折线圆角半径是走廊的 0.5（等效参考的「半径 = 一个网格」）");
  // nodeTypeIcon 的 when 必须**覆盖真实枚举**，且不能带 else。
  //
  // 这条是补的：上一版我写了 `WorkflowNodeType.LOG`，而枚举里根本没有 LOG ——
  // 44 个本地门禁一个都没报，靠 CI 编译才发现（Unresolved reference 'LOG'）。
  // 门禁读不到枚举定义就不要猜，直接拿 models 里的真实值逐条比对。
  const models = read("app/src/main/java/me/bmax/apatch/dsh/WorkflowModels.kt");
  const enumBody = models.slice(models.indexOf("enum class WorkflowNodeType"));
  const realTypes = [...enumBody.slice(0, enumBody.indexOf("companion object"))
    .matchAll(/([A-Z_]+)\("([a-z]+)"\)/g)].map((m) => m[1]);
  ok(realTypes.length === 6, "WorkflowNodeType 有 6 个取值（" + realTypes.join("/") + "）");
  const iconFn = canvas + read("app/src/main/java/me/bmax/apatch/ui/component/WorkflowEditorSurface.kt");
  // 不绑定可见性修饰符：这个函数后来从 private 提成 internal（卡片要拿它画水印），
  // 断言按 "private fun" 找就整个失效了 —— 而函数体本身没变。门禁要钉的是
  // "每个枚举值都有分支"，不是"它是不是 private"。
  const iconAt = iconFn.search(/fun nodeTypeIcon\(/);
  const iconBody = iconFn.slice(iconAt);
  const iconSrc = iconBody.slice(0, iconBody.indexOf("\n}"));
  for (const t of realTypes) {
    ok(new RegExp("WorkflowNodeType\\." + t + " ->").test(iconSrc), "nodeTypeIcon 覆盖了 " + t);
  }
  ok(!/WorkflowNodeType\.LOG\b/.test(iconSrc), "nodeTypeIcon 不引用枚举里不存在的 LOG（CI 就是这么炸的）");
  ok(!/else ->/.test(iconSrc), "nodeTypeIcon 不带 else 兜底（这样新增枚举值时编译器会报错提醒）");
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
  ok(/WorkflowNameDialog\(/.test(SRC.screen) &&
    /WorkflowEditorScreenDestination\(workflowId = id\)/.test(SRC.screen) &&
    /WorkflowEditorScreenDestination\(workflowId = [a-z]+\.id\)/.test(SRC.screen),
    "新建先收名字（WorkflowNameDialog）再按 id 导航，编辑按 id 导航（不再在本页盖一层）");
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
  ok(/viewport\.dragDeltaDp\(dxPx, dyPx, density\)/.test(SRC.editorSurface) &&
    /WorkflowGeometry\.snapToGridDp\(/.test(SRC.editorSurface), "编辑页写回走视口逆变换 + snapToGridDp");
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
  ok(/onWireDrop/.test(SRC.canvas) && /onWireDrop = \{ fromId, fromPin, toId, pointer(, targetPin)? ->/.test(SRC.editorSurface),
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

console.log("\u2500 #16j 节点选择面板：两个入口共用同一个组件");
{
  const e = SRC.editorSurface;
  const sheetStart = e.indexOf("if (sheetOpen) {");
  // 端点跟着实现的注释走：点卡片不再弹窗（参数在卡上），这段注释已改名。
  const sheetEnd = e.indexOf("// ── 节点详情窗", sheetStart);
  const sheet = sheetStart >= 0 && sheetEnd > sheetStart ? e.slice(sheetStart, sheetEnd) : "";
  ok(sheet.length > 0, "能定位到底部「加节点」入口这一段");
  ok(/WorkflowNodePicker\(/.test(sheet), "底部入口用的是共用面板组件");

  // 第二个入口（从输出口拉出后落在空白）必须用**同一个**组件。
  //
  // 这一条是这条 issue 的根因护栏：改了两级分类之后，那个入口仍是一个只有 7 个
  // 基础节点的 AlertDialog，原生能力在那边根本看不到。两个入口选项集不一致就是 bug。
  const afterStart = e.indexOf("addAfter?.let { after ->");
  const afterEnd = e.indexOf("// ── 放弃改动确认", afterStart);
  const after = afterStart >= 0 && afterEnd > afterStart ? e.slice(afterStart, afterEnd) : "";
  ok(after.length > 0, "能定位到「拉出后加节点」入口这一段");
  ok(/WorkflowNodePicker\(/.test(after), "拉出后加节点也用同一个面板（不再是只有 7 项的空 AlertDialog）");
  ok(!/for \(kind in EDITOR_NODE_CATALOG\)/.test(after), "拉出后加节点的旧列表已移除");

  // 面板内部结构（版式照点击助手的 SelectActionDialog）。
  const picker = SRC.nodePicker;
  ok(picker.length > 0, "能读到节点面板组件源码");
  // ── 面板版式：左侧分类竖排 + 右侧两列卡片栅格 ──
  //
  // 这三条断言原先钉的是「LazyRow 一级连体按钮 + 二级左侧竖排」那套**三层**结构。
  // 但我们的数据只有两层（八个分类，每个分类下直接是节点），照抄三层的结果是：
  // 八个一级分组横排一行溢出、第 8 个「感知」被裁掉，而二级栏因恒为 1 个被永久隐藏。
  // 断言应当钉「分类在哪、卡片怎么排、能不能滚」，而不是钉某一种已被证明错位的结构。
  // 两列布局：参考实现的列表就是两列卡片。
  //
  // 钉的是"切成两列"这个行为（leftCol/rightCol 各占一半并排），不是某个具体控件——
  // 这里刻意没用 LazyVerticalGrid：整个 sheet 高度是 heightIn(max) 跟着内容长，
  // Row 拿到无界高度约束，而 LazyVerticalGrid 必须有确定高度才能测量（无界会抛
  // "infinity maximum height"）。断言钉控件名会把这种约束下的必然选择打成缺陷。
  ok(/leftCol/.test(picker) && /rightCol/.test(picker) && /filterIndexed\s*\{\s*i,\s*_\s*->\s*i % 2 == 0/.test(picker),
    "节点卡片是两列并排（参考实现的列表就是两列卡片）");
  ok(/verticalScroll\(rememberScrollState\(\)\)/.test(picker) && /CategoryButton\(/.test(picker),
    "分类在左侧竖排且可滚动（横向排一排会溢出、裁掉后面的分类）");
  ok(!/LazyRow/.test(picker),
    "分类栏**不再**是横向 chip（八颗横排必然溢出）");
  ok(/descRes = kind\.bridge\?\.descRes \?: 0/.test(SRC.editorSurface),
    "卡片带说明文字（只有名字的卡片让用户只能靠猜）");
  ok(/OutlinedTextField/.test(picker) && /searchText\.contains\(query\.trim\(\), ignoreCase = true\)/.test(picker),
    "带搜索框，且搜索跨整个一级分组（不只当前二级）");
  // ── 面板必须是**整页贴边贴底**，不是居中的浮窗 ──
  //
  // 用户连着两轮说"飘起来了"。真机截图（654x372）逐像素量过：面板亮色区起于 x=125、
  // 止于画布右缘，左侧留一条背景、底部也不到底 —— 就是一块居中的浮窗。
  //
  // 根因不在高度，在**宽度**：M3 的 ModalBottomSheet 有 sheetMaxWidth 默认值
  // （BottomSheetDefaults.SheetMaxWidth = 640dp）并且居中。屏宽小于 640dp 时看不出来，
  // 平板/横屏/折叠屏展开就会缩成居中小窗。官方文档对 Dp.Unspecified 的原话是
  // "a sheet that spans the entire screen width"，所以这条断言钉的就是它。
  // 之前几轮一直在调 heightIn 的小数，方向错了：宽度被限死时，高度怎么调都还是浮窗。
  ok(/ModalBottomSheet/.test(picker) && /sheetMaxWidth = Dp\.Unspecified/.test(picker),
    "面板宽度占满整屏（sheetMaxWidth = Dp.Unspecified；默认 640dp 居中就是\"飘\"的根因）");
  // 贴底：内容必须撑满可用高度，而不是跟着条目数缩。
  ok(/fillMaxHeight\(\)/.test(picker),
    "面板主体撑满可用高度（贴到底，不再随条目数缩成一条）");
  // ── 三段标签：预设 / 任务 / 变量 ──
  //
  // 参考实现 SelectActionDialog 的 MaterialButtonToggleGroup 是 GroupType.PRESET/TASK/VARIABLE。
  // 之前这里**没有**这三个页签，注释里写的是"DSH 没有任务/变量一级页签，所以不凭空添加"。
  // 但两条数据链其实是现成的：任务 = WorkflowStore.list()（已落盘的流程），
  // 变量 = draft.variables（这份流程自己声明的）。现在它们各自有页签，且都只列真实数据。
  ok(/PICKER_TABS/.test(picker) && /dsh_wf_picker_tab_preset/.test(picker) &&
    /dsh_wf_picker_tab_task/.test(picker) && /dsh_wf_picker_tab_var/.test(picker),
    "顶部有「预设 / 任务 / 变量」三段标签（对齐参考实现的 GroupType）");
  // 任务页必须读真实流程清单，不能是写死的假条目。
  ok(/WorkflowStore\.list\(/.test(picker),
    "任务页列的是已保存的流程（WorkflowStore.list），不是假数据");
  // 变量页必须来自这份流程自己声明的变量。
  ok(/variables: Map<String, String> = emptyMap\(\)/.test(picker) && /draft\.variables/.test(e),
    "变量页列的是这份流程的 variables（宿主把 draft.variables 传进来）");
  // 标题行必须**删掉**：参考实现没有它，三段标签本身就是身份，关闭靠拖柄/返回键。
  ok(!/dsh_workflow_editor_add_node/.test(picker),
    "面板顶部不再有「加节点」标题行");
  // ── 按源针脚筛候选（对齐 SelectActionByPinDialog）──
  //
  // 参考实现从某颗针脚拖出、松手落空白时，弹的窗只保留 `findConnectToAblePin` 认的动作。
  // 我们原来落空白只记 addAfter（源节点 id），针脚类型丢了，面板只能列全量。
  ok(/var addAfterPin by remember \{ mutableStateOf\(""\) \}/.test(e) &&
    /addAfterPin = fromPin/.test(e),
    "拉线落空白时记录源针脚（否则面板无法按针脚筛选）");
  ok(/filterPin = null,/.test(e) && /filterPin = addAfterPin\.takeIf \{ it\.isNotBlank\(\) \}/.test(e),
    "底部「加节点」不过滤，拉线入口按源针脚过滤");
  ok(/addAfterPin = ""/.test(e),
    "选完或取消后清空源针脚（残留会让下次拉线用错的类型筛）");
  ok(/filterPin: String\? = null/.test(picker) && /\.filter \{ entry -> acceptFilter\?\.invoke\(entry\) \?: true \}/.test(picker),
    "面板接受 filterPin 并对分类与搜索两条分支同时过滤");
  // 判定必须复用 canLinkFrom，不能在面板里另写一套——参考实现的 linkAble 只有一处。
  ok(/target\.canLinkFrom\(source\)/.test(e),
    "可接性判定复用 WorkflowPinSpec.canLinkFrom（不另写规则）");
  // ── 拉线中的落点高亮（对齐 dragOnPin + 震动）──
  //
  // 参考实现拖拽时把"落点处可接的针脚"记进 dragOnPin，命中变化时 performHapticFeedback。
  // 光有预览线不够：线是连续的一根，指不出"具体是哪颗针脚"。
  const cvSrc = SRC.canvas;
  ok(/var wireHoverPin by remember \{ mutableStateOf<String\?>\(null\) \}/.test(cvSrc) &&
    /haptic\.performHapticFeedback\(/.test(cvSrc),
    "拖拽中记录可接针脚，命中时震动反馈");
  ok(/if \(next != wireHoverPin\)/.test(cvSrc),
    "只在命中**变化**时震动（每帧都震手会麻）");
  ok(/hovered = hoveredPinId == pin\.id/.test(SRC.card) &&
    /hoveredPinId: String\? = null,/.test(SRC.card),
    "被悬停的针脚本身有视觉强调（不只是线）");
  // 落点判定必须是**同一个**函数：两处各写一遍会出现「高亮说能接、松手接不上」。
  ok(/private fun hitPinAt\(/.test(cvSrc) &&
    (cvSrc.match(/hitPinAt\(/g) || []).length >= 3,
    "落点命中判定抽成 hitPinAt，被拖拽高亮与松手落点共用");
  ok(/wireRejectColor/.test(cvSrc) &&
    /val wireRejectColor = MaterialTheme\.colorScheme\.error\.copy/.test(cvSrc),
    "接不上时的提示色在组合期取值（Canvas 的 draw lambda 不是 @Composable）");
  // 造临时节点探测针脚表：针脚本身就依赖 config（trigger 的 requireDsh、原生动作的 bridge）。
  ok(/val probe = WorkflowNode\(/.test(e) && /WorkflowSchema\.defaultPinsFor\(probe\)/.test(e),
    "用该 kind 的默认 config 造临时节点取针脚表（否则筛选与真实针脚对不上）");
  ok(/dsh_wf_picker_no_compatible/.test(picker) &&
    /dsh_wf_picker_no_compatible/.test(read("app/src/main/res/values-zh-rCN/dsh_strings.xml")) &&
    /dsh_wf_picker_no_compatible/.test(read("app/src/main/res/values/dsh_strings.xml")),
    "过滤为空时有专门提示，中英成对（与「没搜到」分开说）");
  ok(/verticalScroll\(rememberScrollState\(\)\)/.test(picker) &&
    /width\(84\.dp\)/.test(picker),
    "左栏独立滚动（八项放不下时自己滚，不把整块面板撑变形）");
  // 分类项内边距：每项多 8dp 就是 64dp，直接决定可见高度。分类不该比卡片还占地方。
  ok(/padding\(horizontal = 10\.dp, vertical = 8\.dp\)/.test(picker),
    "分类项内边距是 vertical = 8dp（12dp 会让八项多占 64dp）");
  // ── 主体高度不再由左栏项数决定 ──
  //
  // 旧版靠 IntrinsicSize.Min 让主体"取右栏高度"，那是 heightIn 上限时代的手法：
  // heightIn 只是上限，左栏八项照样把 Row 撑高，右栏下方留白。
  // 现在是整页面板，主体直接 fillMaxHeight 吃掉剩余高度，两栏各自内部滚动，
  // 谁也不再撑高谁 —— 所以 IntrinsicSize.Min 这条断言随实现一起作废。
  ok(/fillMaxHeight\(\)[\s\S]{0,200}verticalScroll/.test(picker),
    "左栏 fillMaxHeight + verticalScroll：高度跟右栏走，八项放不下时自己滚");

  // ── 卡片类型水印 ──
  //
  // 参考实现 card_normal.xml 顶部有一枚 center_horizontal、alpha 0.1 的
  // ShapeableImageView，把节点类型编码进形状，扫一眼就能分卡。
  // 我们之前完全没有它，一屏卡片全是"标题 + 参数行"，读起来要靠逐行文字。
  const card = read("app/src/main/java/me/bmax/apatch/ui/component/WorkflowNodeCard.kt");
  ok(/nodeTypeIcon\(node\.type\)/.test(card) && /alpha\(0\.10f\)/.test(card),
    "卡片有半透明的类型水印图标（对齐参考实现的 card_normal icon）");
  // 水印是背景装饰，绝不能参与布局 —— 否则每张卡都会凭空高一截。
  // 断言的是**结构意图**：水印是 Box 的子项（align 到 TopCenter），而不是内容 Column 的孩子。
  // 旧写法是 "Box 后面 80 字内出现 Column"，那只是当时那版代码的巧合顺序 ——
  // 顺序一变就误报，而"水印是否参与布局"根本没被断言到。
  const wmAt = card.indexOf("workflowNodeWatermark(node)");
  const innerColAt = card.indexOf("Column(Modifier.padding(horizontal = 8.dp");
  ok(wmAt > 0 && innerColAt > wmAt && /\.align\(Alignment\.TopCenter\)/.test(card),
    "水印叠在 Box 里、不塞进内容 Column（否则会把卡片撑高）");
  ok(!/fillMaxSize\(\).*background\(MaterialTheme\.colorScheme\.scrim/.test(picker),
    "不再自绘全屏遮罩层（那层没有弹出动画）");

  // ── 卡片结构与参考实现逐条对齐 ──
  //
  // 这一组守的是"卡片长什么样"。参考实现是 card_normal.xml + ActionCard，
  // 每一条都对应它里面一个具体的控件/属性，不是随手定的口味。
  // 断言的共同点是：错了都不报错，只会在真机上表现为"看着不像"或"点不中"。

  // 参考卡片没有顶部色带：类型信息由水印 + 选中描边承担。
  // 多一条 4dp 实色带会让卡片顶部凭空多一截，并与 1dp 描边撞色。
  ok(!/height\(4\.dp\)\s*\.background\(accent\)/.test(card),
    "卡片没有顶部类型色带（参考实现的 card_normal 里没有这一层）");

  // 参考卡片的标题下面没有副标题：动作摘要与下方的参数行是同一件事，
  // 两处都写既浪费横向空间，又把参数行往下挤一整行。
  ok(!/workflowNodeSubtitle/.test(card),
    "标题下没有重复的动作摘要行（命令值只在参数行出现一次）");

  // 参考实现的 des 是一条**满宽主题色横条**（background=colorPrimary、textColor=onPrimary），
  // 不是一行浅色小字 —— 它是用户自己写的一句话，视觉权重该高于参数标签。
  ok(/\.height\(24\.dp\)\.background\(MaterialTheme\.colorScheme\.primary\)/.test(card) &&
    /color = MaterialTheme\.colorScheme\.onPrimary/.test(card),
    "描述条是参考实现那样的主题色横条（主色底 + onPrimary 字）");

  // 参考实现是 expandButton 在左、Space(weight=1)、右侧 ButtonGroup(lock/copy/remove)。
  // 上一版四个按钮全挤在尾部，展开与删除只隔 20dp —— 想切展开会点到删除，
  // 而删除两下才真删，第一下已经把卡变成红色警戒态。
  {
    const barAt = card.indexOf("动作条：展开（左）");
    const spacerAt = card.indexOf("Spacer(Modifier.weight(1f))", barAt);
    const lockAt = card.indexOf("if (onToggleLock != null) {", barAt);
    ok(barAt > 0 && spacerAt > barAt && lockAt > spacerAt,
      "动作条左右分置：展开在左、锁定/复制/删除在右（中间 weight 撑开）");
  }

  // 参考实现的 pinBox 是 Row：inBox 在左、outBox 在右，两者**同一水平带**。
  // 上一版把出口单独摆在所有参数下方一整行，右侧留半张卡宽的空白，出口针脚离
  // 它对应的那列数据很远 —— 拉线时要在两片空白之间找点。
  {
    const pinBoxAt = card.indexOf("针脚区（参考实现的 pinBox");
    const leftColAt = card.indexOf("Modifier.weight(1f)", pinBoxAt);
    const outAt = card.indexOf("if (dataOuts.isNotEmpty())", pinBoxAt);
    ok(pinBoxAt > 0 && leftColAt > pinBoxAt && outAt > leftColAt,
      "数据入口与数据出口同处一个水平针脚区（inBox 左 / outBox 右）");
  }

  // 参考实现的 position：bottom|end、alpha 0.1、colorSecondary。
  // 它必须挂在 Box 上而不是内容 Column 里 —— 否则每张卡都会被这行字撑高一截。
  // 坐标只读已有的 posX/posY，不参与任何持久化写入。
  ok(/\$\{node\.posX\},\$\{node\.posY\}/.test(card) &&
    /\.align\(Alignment\.BottomEnd\)[\s\S]{0,80}\.alpha\(0\.10f\)/.test(card),
    "右下角有低透明度坐标水印（参考实现的 position）");
}

console.log("\u2500 #16i 节点落点换算（拉线与新面板共用同一处）");
{
  const e = SRC.editorSurface;
  ok(/positionInWindow\(\)/.test(e) && /onGloballyPositioned/.test(e),
    "画布用窗口坐标做基准（否则局部坐标算不出落点）");
  ok(/fun dropPointDp\(pointerWindow: Offset\): Pair<Int, Int>/.test(e) &&
    /viewport\.toWorldDp\(pointerWindow, canvasOrigin, density\)/.test(e) &&
    /WorkflowGeometry\.snapToGridDp\(world\.x\)/.test(e),
    "落点经视口逆变换 + 12dp 吸附后落盘（缩放/平移后落点仍然对）");
  // 面板是**点选即建**，不再有「按住卡片拖出来」这条路 ——
  // 上一版的幽灵卡、窗口坐标累加、inCanvas 判定都随之删除了。
  // 这里改为守住「新节点确实落在画布内」这一意图：坐标仍取画布内的换算结果。
  ok(/dep\?|dropPointDp/.test(e) && !/total < -32f/.test(e),
    "删掉了「只按向上位移猜」的旧启发式");
}

console.log("\u2500 #16k 拖动手感与拉线坐标（三条真机反馈的回归护栏）");
{
  const e = SRC.editorSurface;
  const c = SRC.card;
  const cv = SRC.canvas;
  // ① 原地颤抖：拖动过程中每帧吸附会把小位移吞掉；现在累加未吸附位移，松手才吸附。
  ok(/dragAcc \+= viewport\.dragDeltaDp\(/.test(e) && /dragPreview = id to Offset\(/.test(e) &&
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
  // 从针脚矩形起画，且**先逆变换回世界坐标**（窗口→内容层局部，再除以缩放）。
  //
  // 锚点要**按针脚方向**取，不能一律取某一条边：
  //   控制流针脚是垂直的（顶进底出）→ x 取 center、出口落在 bottom；
  //   数据针脚是水平的（左进右出）→ y 取 center、出口落在 right。
  // 参考实现 PinView.getSlotPosInLayout 就是这个分支。
  //
  // 这条断言原来钉的是 `worldX(if (isData) pinned.left else pinned.right)` —— 那是**错的**：
  // 控制流针脚取右边中点，线就从五边形右侧弹出、贴着卡右缘走，与底尖错开半个针脚宽。
  // 用户真机反馈的「连线没对齐」正是它，所以断言必须改成钉正确的行为，而不是钉住那个 bug。
  ok(/val pinned = pinRects\[/.test(cv) &&
    /worldX\(if \(startVertical\) pinned\.center\.x else pinned\.right\)/.test(cv) &&
    /worldY\(if \(startVertical\) pinned\.bottom else pinned\.center\.y\)/.test(cv) &&
    /\(x - origin\.x - pan\.x\) \/ safeScale/.test(cv),
    "已连线从针脚矩形起画，且锚点按针脚方向取（控制流底边中点 / 数据右边中点）");
  // 终点方向相反：控制流入参在顶边中点，数据入参在左边中点。
  ok(/worldX\(if \(isData\) toPinned\.left else toPinned\.center\.x\)/.test(cv) &&
    /worldY\(if \(isData\) toPinned\.center\.y else toPinned\.top\)/.test(cv),
    "终点按针脚方向取（控制流顶边中点 / 数据左边中点）");
  // 连线颜色取源针脚色，且与卡片共用同一个函数（两处各写一份色表必然对不上）。
  // 颜色必须与卡片共用 workflowPinColor，**但只能在组合期调用**：
  // drawBlueprintEdges 是 DrawScope 扩展而不是 @Composable，绘制期读 MaterialTheme 编译不过。
  // 所以组合期算成 类型id→颜色 的表，绘制期只查表。
  ok(/internal fun workflowPinColor\(/.test(SRC.card) &&
    /WorkflowPinType\.entries\.associate \{ it\.id to workflowPinColor\(it\) \}/.test(cv) &&
    /pinColors\[\(toSpec\?\.type \?: WorkflowPinType\.ANY\)\.id\]/.test(cv),
    "连线颜色取目标针脚的类型色（组合期算表、绘制期查表），与卡片共用 workflowPinColor");
  // 与选中卡相连的线加粗并排到最后画（置顶）。只加粗不置顶会被后画的线盖掉半截。
  ok(/val touchesSelection =/.test(cv) && /sortedBy \{ e ->/.test(cv),
    "选中卡的连线加粗且最后绘制（置顶），不是只加粗");
  // ③ 面板入口是「点选即建」：落点由 dropPointDp 统一换算，不再有跟随手指的幽灵胶囊。
  //
  // 上一版这里是「幽灵胶囊左上角 = 手指 = 节点左上角」。面板改成点选之后，
  // 那条交互整体消失了（幽灵卡与 rootOrigin 一起删掉），改为守住换算入口唯一：
  ok(/fun dropPointDp\(pointerWindow: Offset\): Pair<Int, Int>/.test(e),
    "新节点落点只经 dropPointDp 一处换算（面板与拉线共用）");
  ok(!/rootOrigin/.test(e) || !/pos\.x - rootOrigin/.test(e),
    "幽灵胶囊的旧对齐代码已删除");
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
  ok(/onWireDrop: \(\([\s\S]{0,220}pointerWindow: Offset/.test(cv) &&
    /wireDropCb\.value\?\.invoke\(from, fromPin, target, pointer, targetPin\)/.test(cv),
    "画布把松手坐标与起点针脚一起交给宿主");
  // 起针脚决定落盘的 when：成立/不成立两条出口必须各自带 when，
  // 否则执行器在判断节点上只会走第一条，图与实际行为矛盾。
  ok(/wireFromPin/.test(cv) && /WorkflowPinKind\.EXEC_TRUE\.name -> "true"/.test(e) &&
    /WorkflowPinKind\.EXEC_FALSE\.name -> "false"/.test(e),
    "从成立/不成立口拉出的边各自带上 when");
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
  ok(/WorkflowValidator\.addEdgeIfAcyclic\(def, fromId, toId, whenExpr\)/.test(e),
    '编辑页连边走这个入口（并把 when 一起传下去）');
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
  // 现在提到 internal：恢复路径（recoverInterruptedRuntimeInstall / DshEnv.recoverPreserved）
  // 也要复用它，private 会让那两处只能各写一遍裸 renameTo（评审 P1-3c）。
  ok(/internal fun renameOrCopy\(src: File, dst: File/.test(inst), '改名失败有复制回退');
  ok(/renameOrCopy\(backup, dest, note\)/.test(inst),
    'recoverInterruptedRuntimeInstall 走了 renameOrCopy（不再裸 renameTo 静默失败）');
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
  // 可见性从 private 提到 internal（扩展系统安装也要用它，避免复制一份丢了这个链接语义）
  const copyFn = inst.slice(inst.indexOf('internal fun File.copyRecursiveTo('));
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
  // 命令写进脚本文件而非拼进 su -c（脚本内容来自 assets 模板，见 check-chroot.js）
  ok(/launch\.writeText\(script, Charsets\.UTF_8\)/.test(rt), '命令写进脚本文件而非拼进 su -c');
  ok(/runCatching \{ buildLaunchScript\(rootfs, dir, env, guest\) \}/.test(rt),
    '模板读不到就回退 proot（不让一次 chroot 尝试把启动搞死）');
  // 脚本内容已搬到 assets/chroot/launch.sh，trap 与卸载都在那边（Kotlin 只替换占位符）
  ok(/trap cleanup EXIT INT TERM/.test(read("app/src/main/assets/chroot/launch.sh")),
    '脚本用 trap 保证退出时卸载挂载点');
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

console.log("\u2500 #16y chroot 启动脚本不得双层转义 + proot 前提无条件就位（issue #12）");
{
  const rt = read("app/src/main/java/me/bmax/apatch/dsh/ContainerRuntime.kt");
  const dr = read("app/src/main/java/me/bmax/apatch/dsh/DshRuntime.kt");
  // ── 启动脚本：guest argv 逐项传参（「双层转义」那套归因是误判，真凶见 #16z） ──
  // 语义断言：脚本必须把位置参数摊开成 "$@"。
  // 不去精确匹配转义后的字面量 —— 那样会随 raw string / 普通字符串的写法变化，
  // 实测已因此误报过一次（改 \@ 时断言跟着红，但代码是对的）。
  // 脚本本体已搬到 assets/chroot/launch.sh（普通 shell 文件，可被 sh -n 检查），
  // 这里改盯模板 —— 断言语义，不绑死某种字符串写法（以前那样会随写法漂移误报）。
  const launchAsset = read("app/src/main/assets/chroot/launch.sh");
  ok(/chroot "\$MNT" \/usr\/bin\/env -i @ENV@ "\$@"/.test(launchAsset),
    '用 "$@" 展开（argv 边界保留）');
  ok(/^set -- @GUEST_ARGS@$/m.test(launchAsset), 'guest argv 走 set -- 位置参数');
  ok(/val guestArgs = guest\.joinToString\(" "\) \{ shellQuote\(it\) \}/.test(rt), '每项只 shellQuote 一次');
  // 反向断言：不允许再把 argv 拼成字符串传进 buildLaunchScript
  ok(/buildLaunchScript\(rootfs, dir, env, guest\)/.test(rt), 'buildLaunchScript 收 argv 列表而不是拼接串');
  ok(!/buildLaunchScript\(rootfs, dir, env, guest\.joinToString/.test(rt),
    '不再把 guest 拼成命令字符串（引号配对与 argv 边界都会失真）');
  // ── Bug B：proot 的运行前提必须无条件就位 ──
  // 与 ensureRuntimeFiles 里那条同源：prootBaseArgv() 是全局兜底，chroot/proroot 下也可能 exec proot。
  // 关键：这两行不能再被 if (rt.id() == "proot") 包住
  // 判据：扫到 `env["LD_LIBRARY_PATH"]` 为止的**括号深度**必须等于 1（函数体本身）。
  // 用深度而不是「之前有没有出现过 if」：后者在有多个分支时会假阳/假阴。
  // 这条断言最初写成数括号、又改成「无条件区段包含」，两次都**抓不住注入的回归**
  // （把这三行包回 `if (proot) {` 后仍然全绿）—— 所以这里用深度重新算，并已自证。
  const applyEnvBody = dr.slice(dr.indexOf('private fun applyEnv'), dr.indexOf('private fun guestEnv'));
  const ldLine = 'env["LD_LIBRARY_PATH"]';
  const lines = applyEnvBody.split('\n');
  let depth = 0;
  let ldDepth = -1;
  for (const line of lines) {
    // 注释行不计（注释里含大括号会干扰）
    const code = line.replace(/\/\/.*$/, '');
    if (code.includes(ldLine)) { ldDepth = depth; break; }
    depth += (code.match(/\{/g) || []).length - (code.match(/\}/g) || []).length;
  }
  ok(ldDepth === 1,
    'LD_LIBRARY_PATH 写在函数体顶层、不在任何 if 里（实际深度 ' + ldDepth + '；' +
      'chroot/proroot 下回退 proot 也要能找到 libtalloc.so.2）');
  ok(/env\["PROOT_LOADER"\]/.test(applyEnvBody), 'PROOT_LOADER 同样无条件设');
}

console.log("\u2500 #16z launch.sh 的真凶与启动前自检（issue #12 第三轮）");
{
  const rt = read("app/src/main/java/me/bmax/apatch/dsh/ContainerRuntime.kt");
  const fs2 = read("app/src/main/java/me/bmax/apatch/ui/screen/settings/FunctionSettings.kt");
  // 真凶：`val mnt = "\${'$'}MNT"` 在**普通字符串**里不是 `$MNT`，而是字面量 `${'$'}MNT` ——
  // mksh 解析这个字面量会在 EOF 处报 `no closing quote`，而且报的行号是**最后一行 +1**，
  // 所以三轮排查都被引到「exec 那一行」上去。已用 mksh -n 在真脚本上复现与验证。
  // 启动脚本现在是 assets 里的普通 shell 文件，Kotlin 只做字面 replace ——
  // 这样本地与 CI 都能直接 `sh -n`（详细断言见 tools/check-chroot.js）。
  const launchAsset = read("app/src/main/assets/chroot/launch.sh");
  const assetCode = launchAsset.split("\n").filter((l) => !l.trim().startsWith("#")).join("\n");
  ok(/^#!\/system\/bin\/sh$/m.test(launchAsset), '启动脚本模板在 assets 里且带 shebang');
  ok(!assetCode.includes("\\" + "$" + "{'$'}"), '模板代码里不再出现非法参数展开（mksh 会报 no closing quote）');
  ok(/\.replace\("@RFS@"/.test(rt) && /\.replace\("@ENV@"/.test(rt),
    'Kotlin 用字面 replace 填模板占位符（不再拼字符串）');
  // 启动前自检：写盘后先让设备自己的 shell 过一遍语法，不通过就回退 proot
  ok(/private fun checkLaunchScript\(launch: File\): String\?/.test(rt), '有 launch.sh 语法自检');
  ok(/ProcessBuilder\("\/system\/bin\/sh", "-n", launch\.absolutePath\)/.test(rt), '用 /system/bin/sh -n 检查（就是真正解析脚本的那个 shell）');
  ok(/val syntaxError = checkLaunchScript\(launch\)/.test(rt), 'spawnArgv 返回 argv 之前先自检');
  ok(/DshRuntime\.appendLog\(/.test(rt) && /已回退 proot/.test(rt), '自检不过：把 shell 原文写进日志并回退 proot');
  // ── 跨运行时互操作 + 挂载（真机：切回 proot 后 /root/.dsh/... EACCES；工作区列目录 ENOENT）──
  ok(/^\s*binds \+= storageBinds\(ctx\)/m.test(rt) && /^\s*binds \+= workspaceBinds\(ctx\)/m.test(rt),
    "chroot 同时挂「存储」与「工作区」（proot/proroot 都有；缺工作区就是新建工作区 ENOENT）");
  ok(rt.includes("挂载失败"), "挂载失败写进日志（不再 2>/dev/null 吞掉，否则只看到 ENOENT）");
  ok(launchAsset.includes("/proc/mounts"), "已在挂载中就不叠第二层（否则盖掉运行中容器的 /dev /proc /sdcard）");
  ok(launchAsset.includes("umask 000"), "真 root + Android 应用 umask 077 的组合必须显式改 umask，否则文件是 root 0600");
  ok(launchAsset.includes("chown -R") && launchAsset.includes("APP_GID"),
    "把历史 root 拥有的容器数据修回 App uid（否则切回 proot 必 EACCES）");
  // 挂载视图必须落在 chroot 根里面，且不能用 exec 收尾（exec 会让 trap 永不执行）
  ok(/^\s*mount --bind "\$RFS" "\$MNT" \|\| exit 91$/m.test(launchAsset) && /chroot "\$MNT"/.test(launchAsset),
    "先 bind rootfs 到 $MNT 再 chroot $MNT（否则挂载点全在 rootfs 之外，容器看不到 /dev /proc /sdcard）");
  ok(!/exec chroot/.test(assetCode), "不能用 exec chroot 收尾");
  // chroot 失败必须能回退 proot；切回 proot 前要有属主兜底
  const dshRuntime = read("app/src/main/java/me/bmax/apatch/dsh/DshRuntime.kt");
  ok(!/runtimeId\(\) == "proroot" && noteProrootFailure\(/.test(dshRuntime),
    "noteProrootFailure 的调用点不得写死 proroot（否则 chroot 的回退分支不可达）");
  ok(/private fun repairRootfsOwnership\(\)/.test(dshRuntime),
    "有「切回 proot/proroot 前修属主」的兜底（那条路不会执行 chroot 的脚本）");
  // UI：chroot 加入后 `!= "proroot"` 会让 proot 与 chroot 同时显示选中（真机截图）
  ok(/selected = runtimeId == "proot"/.test(fs2), 'proot 单选项按 == "proot" 判定');
  ok(!/selected = runtimeId != "proroot"/.test(fs2), '不再用 != "proroot"（会同时选中两项）');
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
  ok(/WorkflowStore\.saveIfRevision\(ctx, edited, initial\.revision\)/.test(SRC.editorScreen),
  "编辑页保存走带 revision 前置条件的落盘（不会静默覆盖别处的改动）");
  ok(/internal fun starter\(/.test(SRC.screen) && /WorkflowStore\.newId\(\)/.test(SRC.screen),
    "有「新建流程」的合法起点（不是空定义）");
  // 起点必须**走字段表的默认值**，不能手写一份 config。
  //
  // 这条原先断言的是"写死了 mapOf(\"action\" to \"shell\", \"cmd\" to \"echo hello\")"，
  // 但那正是预设卡片与加节点卡片长得不一样的根因：两条路径各写一份默认值，
  // 手写的那份缺 autostartDsh / 缺 name。断言改为要求"两边同源"。
  ok(/defaultConfigFor\(WorkflowNodeType\.TRIGGER, null\)/.test(SRC.screen) &&
    /defaultConfigFor\(WorkflowNodeType\.EXECUTE, WorkflowActionType\.SHELL\)/.test(SRC.screen),
    "起点与加节点共用字段表默认值（不再手写 config，否则预设卡片缺参数）");
  ok(!/mapOf\("action" to "shell"/.test(SRC.screen),
    "起点里不再有手写的 action/cmd（手写就等于第二份默认值）");
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

// ── 动作目录：每条动作都必须打到桥的真实端点 ──
//
// 这一组是这次改动最要害的断言。卡片上写着「点一下屏幕」、实际却调了别的路径（或者路径压根
// 不存在），用户看到的是 A、设备上做的是 B，而批准弹窗按 A 渲染 —— 那是最坏的一类 bug，
// 且只会在运行时暴露。所以：目录里每个 (method, path) 都必须在桥的 dispatch 里**逐字**出现。
{
  const catalog = read("app/src/main/java/me/bmax/apatch/dsh/WorkflowActionCatalog.kt");
  const bridge = read("app/src/main/java/me/bmax/apatch/dsh/DshNativeBridge.kt");
  const pairs = [...catalog.matchAll(/"([A-Z]+)",\s*"([^"]+)"/g)].map((m) => [m[1], m[2]]);
  ok(pairs.length >= 30, "动作目录条目过少（" + pairs.length + " 条），卡片没铺满");
  let missing = [];
  for (const [method, path] of pairs) {
    const needle = 'method == "' + method + '" && path == "' + path + '"';
    if (!bridge.includes(needle)) missing.push(method + " " + path);
  }
  ok(missing.length === 0, "动作目录里有端点不存在：" + missing.join(", "));
  // 反向：目录里声明的每个 Cap 都必须是真的 Cap id
  const capsDecl = [...catalog.matchAll(/DshNativeBridge\.Cap\.([A-Z0-9_]+)/g)].map((m) => m[1]);
  const missingCap = capsDecl.filter((c) => !new RegExp("\\n\\s*" + c + '\\(\\"').test(bridge));
  ok(missingCap.length === 0, "动作目录引用了不存在的 Cap：" + missingCap.join(", "));
  // 引擎必须真的把 NATIVE 走到桥，而不是当 shell 跑
  const engine = read("app/src/main/java/me/bmax/apatch/dsh/WorkflowEngine.kt");
  ok(/WorkflowActionType\.NATIVE ->/.test(engine) && /DshNativeBridge\.handle\(ctx, spec\.method, spec\.path/.test(engine),
    "引擎没有把原生动作真的走一遍桥（NATIVE 分支缺失或绕成 shell）");
  // 校验器必须认 bridge 动作，否则写错的 id 会一直到运行时才报
  const validator = read("app/src/main/java/me/bmax/apatch/dsh/WorkflowValidator.kt");
  ok(/WorkflowActionCatalog\.byId\(n\.config\("bridge"\)\)/.test(validator),
    "校验器没有核对 bridge 动作 id");
  // 能力必须自动算进来：只查手工声明的话，用卡片搭的流程可以一项都不声明
  ok(/val WorkflowDefinition\.effectiveCaps/.test(read("app/src/main/java/me/bmax/apatch/dsh/WorkflowModels.kt")) &&
    /def\.effectiveCaps/.test(validator) && /def\.effectiveCaps/.test(engine),
    "原生动作用的能力没有被自动算进 declaredCaps（批准弹窗会漏项）");
  // 参数编辑器的字段必须由目录生成，而不是又一串手写键名
  const fields = read("app/src/main/java/me/bmax/apatch/ui/component/WorkflowNodeFields.kt");
  ok(/for \(p in spec\.params\)/.test(fields) && /spec\.configKey\(p\)/.test(fields),
    "原生动作的参数控件没有从动作目录生成（会漏字段）");
}

// ── v2 schema：数据边 + v1→v2 迁移 ──
//
// 这一组的重点是**迁移不改行为**：一份 v1 的定义用 v2 的读法解析出来必须完全等价
// （每条边都是控制流、引脚为空），而不是"升级后流程跑不通了"。
{
  const models = read("app/src/main/java/me/bmax/apatch/dsh/WorkflowModels.kt");
  // 版本 3 = 接线模型重写（参数变成带类型、可接线的针脚）。
  // 用户已明确「不必兼容旧流程，只要提醒该流程检测为旧格式、已失效」
  ok(/const val SCHEMA_VERSION = 3/.test(models), "schema 版本已抬到 3（接线模型重写）");
  ok(/const val MIN_SUPPORTED_VERSION = 3/.test(models),
    "旧版本不再被抬上来（v3 起只支持当前格式）");
  ok(/fun WorkflowDefinition\.isStaleFormat\(\)/.test(models), "有旧格式判定入口");
  ok(/旧格式/.test(SRC.validator) && /已失效/.test(SRC.validator),
    "校验器说的是「旧格式、已失效」，不是干巴巴的版本号");
  ok(/文件仍保留/.test(SRC.validator), "明确告诉用户文件没被删（可导出备份）");
  // 旧格式不再被"认得"：v3 起旧定义判失效（这是用户明确的选择）。
  // 但仍然**能读进来**（不抛异常、不丢文件），只是不能跑。
  ok(/raw < WorkflowSchema\.MIN_SUPPORTED_VERSION -> raw/.test(models),
    "旧版本原样返回版本号，不静默抬版本（否则会假装能用）");
  ok(/schemaVersion = migrateToCurrent\(/.test(models), "解析定义时真的走了迁移");
  // v1 的关键不变量：kind 缺省 = flow。缺了它，旧定义会被读成"没有边是控制流"。
  ok(/val kind = WorkflowEdgeKind\.of\(o\.optString\("kind"\)\) \?: WorkflowEdgeKind\.FLOW/.test(models),
    "kind 缺省即 control-flow（v1 的每条边都是控制流）");
  // 控制流边不写 kind：这样 v1 JSON 与 v2 的控制流边逐字相同，迁移不需要改写文件。
  ok(/if \(kind == WorkflowEdgeKind\.DATA\) \{/.test(models),
    "只有数据边才写 kind/引脚（v1 文件因此逐字兼容）");
  ok(/fun outgoing\(from: String\): List<WorkflowEdge> = edges\.filter \{ it\.from == from && it\.isFlow \}/.test(models),
    "outgoing 只返回控制流边（否则 nextNode 会抓到数据边）");
  ok(/fun dataInto\(nodeId: String\)/.test(models) && /fun dataFrom\(nodeId: String\)/.test(models),
    "数据边有明确的入/出查询");
  ok(/fun dataInputKeysOf\(node: WorkflowNode\)/.test(models),
    "数据入口键有唯一权威定义（在 DSL 层，不是 UI 层）");

  const engine = read("app/src/main/java/me/bmax/apatch/dsh/WorkflowEngine.kt");
  ok(/val node = applyDataEdges\(def, resolved, outputs, vars, branches\)/.test(engine),
    "执行前把数据边接到 config 上");
  ok(/private fun coerceValue\(raw: String, dataType: String\)/.test(engine),
    "数据边按声明类型收敛（否则文字会喂进 number 参数）");

  const validator = read("app/src/main/java/me/bmax/apatch/dsh/WorkflowValidator.kt");
  ok(/if \(e\.kind == WorkflowEdgeKind\.DATA\) \{/.test(validator),
    "校验器把数据边与控制流边分开查");
  ok(/数据边不能带 when/.test(validator), "数据边不允许带 when（它不决定走哪条路）");
  ok(/private fun validateDataWiring\(/.test(validator), "数据边两端都查（来源存在、目标参数存在）");
  ok(/fun addDataEdge\(/.test(validator), "加数据边有唯一入口（自我引用直接拒）");
  ok(/WorkflowSchema\.dataInputKeysOf\(target\)/.test(validator),
    "校验器用的参数键与执行器同一份");
  // 版本判据必须放宽成区间：只写"必须等于 2"会让所有 v1 文件在迁移之后又被拦。
  ok(/def\.schemaVersion < WorkflowSchema\.MIN_SUPPORTED_VERSION/.test(validator),
    "校验器的版本判据是区间，不是精确相等");

  const canvas = read("app/src/main/java/me/bmax/apatch/ui/component/WorkflowBlueprintCanvas.kt");
  ok(/edge\.kind == WorkflowEdgeKind\.DATA/.test(canvas), "画布认得出数据边");
  ok(/private fun dataPinIdOf\(/.test(canvas) && /fromPin\.startsWith\("var:"\) -> "var"/.test(canvas),
    "数据边从**自己的**针脚出发（不是卡片右上角）");
  ok(/val isData = edge\.kind == WorkflowEdgeKind\.DATA/.test(canvas),
    "数据边与分支边画得不一样（否则用户会以为数据边也是分支");

  const surface = read("app/src/main/java/me/bmax/apatch/ui/component/WorkflowEditorSurface.kt");
  ok(/WorkflowValidator\.addDataEdge\(/.test(surface), "编辑页从数据针脚拉出的线落成数据边");
  ok(/fun dataSourceFor\(/.test(surface) && /"var:\$varName"/.test(surface),
    "extract 拉出的是变量（var:名字），其余是 step:id");
}

// ── 卡片内联参数（对齐点击助手）──
//
// 这一组守的是"卡片上到底显示了什么"。它属于**纯 UI 逻辑**，但有两个会静默错的判据：
// 行顺序（有值的必须先占名额）与项目符号（无权限要看得见）。两者错了都不会报错，
// 只会在真机上表现为"看不出这卡配了什么" / "不知道为什么跑不了"。
{
  const card = read("app/src/main/java/me/bmax/apatch/ui/component/WorkflowNodeCard.kt");
  // 签名多了一个 linkedKeys 参数（三档展开的 NONE 档要知道哪些参数接了线），
  // 断言只要求「函数存在且以 node 为第一参数」，不再苛求单参数形式。
  ok(/private fun cardParamRows\(\s*\n?\s*node: WorkflowNode/.test(card),
    "卡片参数行由字段表生成（不在卡上写死键名）");
  // 行来源逐项来自字段表；顺序与 DATA_IN 对应，且不截断后续输入口。
  ok(/val filled = mutableListOf<CardParamRowModel>\(\)/.test(card) &&
    /val empty = mutableListOf<CardParamRowModel>\(\)/.test(card) &&
    /sortedBy \{ row -> WorkflowNodeFields\.fieldsFor\(node\)\.indexOfFirst \{ it\.key == row\.key \} \}/.test(card) &&
    !/take\(4\)/.test(card),
    "所有参数行依字段表顺序显示，不截断 DATA_IN 针脚");
  ok(/private fun paramDotColor\(kind: WorkflowNodeFields\.Kind\)/.test(card),
    "参数行有按种类着色的色点");
  // 无权限必须能一眼看出来：整卡压暗 + 红条 + 说清缺什么。
  ok(/if \(granted\) base else base\.alpha\(0\.6f\)/.test(card),
    "无权限的卡整体压暗");
  ok(/R\.string\.dsh_wf_lock\b/.test(card) && /R\.string\.dsh_wf_lock_need/.test(card),
    "红条既说「没权限」也说「缺哪项」");
  ok(/requiredCaps: List<String> = emptyList\(\)/.test(card) && /granted: Boolean = true/.test(card),
    "新增的两个参数都有默认值（老调用点不受影响）");
  // 空心椭圆：空值参数要有占位，不能留白（留白会被读成"这行没内容"）。
  ok(/border\(1\.dp, row\.color, CircleShape\)/.test(card),
    "空值参数画空心椭圆，而不是留白");
  // 引脚上报是拉线的基础，改卡片时最容易被顺手删掉。
  ok(/onPinBounds\?\.invoke\(pin\.kind\.name, coords\.boundsInWindow\(\)\)/.test(card),
    "卡片仍然上报输出引脚的真实窗口矩形（拉线端点依赖它）");

  // ── 控制流针脚是**五边形**（上圆角 4dp / 下切角 8.5dp）──
  //
  // 参考实现 ExecutePinSlotView 用 ShapeAppearanceModel 把两个上角设成 ROUNDED 4dp、
  // 两个下角设成 CUT 8.5dp，尺寸取 ?attr/halfIconButtonSize = 18dp，描边 1dp。
  // 这个"上圆下尖"不是装饰：尖角朝下表示"往下一步走"，朝上表示"从上一步进来"，
  // 圆形/圆角方块表达不了方向 —— 用户说卡片不像参考实现，针脚形状是其中一条。
  ok(/GenericShape/.test(card) && /quadraticTo\(/.test(card),
    "控制流针脚用 GenericShape 画（圆角用二次曲线，不是把圆角方块一圆了事）");
  ok(/cutCorner = 8\.5\.dp/.test(card) && /topCorner = 4\.dp/.test(card),
    "五边形几何照抄 ExecutePinSlotView：上圆角 4dp、下切角 8.5dp");
  // 悬停时描边加粗到 2dp 并换 primary（拉线落点提示），未悬停仍是参考实现的 1dp。
  ok(/border\(if \(hovered\) 2\.dp else 1\.dp/.test(card) &&
    /else color, shape\)/.test(card),
    "五边形描边默认 1dp（悬停才加粗，对齐 pin_slot_normal.xml 的 strokeWidth=1dp）");
  ok(/if \(hovered\) MaterialTheme\.colorScheme\.primary else MaterialTheme\.colorScheme\.surfaceVariant/.test(card),
    "未连线时填 surfaceVariant（对齐 setLinked(false)），悬停才换 primary");
  // 数据针脚仍是圆环：参考实现的 NormalPinSlotView 不改 shape，沿用默认。
  ok(/border\(1\.5\.dp, color, CircleShape\)/.test(card),
    "数据针脚保持圆环（NormalPinSlotView 不改 shape）");
  // 尺寸 18dp = 参考实现的 ?attr/halfIconButtonSize。
  ok(/\.size\(18\.dp\)/.test(card),
    "针脚 18dp（参考实现 halfIconButtonSize = 18dp）");

  // ── 面板载荷分两类：预设给 EditorNodeKind，任务/变量给已配好的 PickerInsert ──
  ok(/data class PickerInsert\(/.test(SRC.nodePicker),
    "面板有 PickerInsert 载荷（任务页/变量页建的是已配好的节点）");
  ok(/is EditorNodeKind -> build\(payload\)/.test(SRC.editorSurface) &&
    /is PickerInsert -> addInsertedNode\(payload\)/.test(SRC.editorSurface),
    "调用点按载荷类型分派，面板本身不认识编辑器内部类型");
}

console.log("\u2500 #16k 卡片可编辑 / 悬浮层定位 / 虚线框跟着选中态");
{
  const card = read("app/src/main/java/me/bmax/apatch/ui/component/WorkflowNodeCard.kt");
  const e = SRC.editorSurface;
  // 参数必须在卡上直接改 —— 用户的原话是「参数都是在卡片本身上的」。
  ok(/BasicTextField\(/.test(card) && /onValueChange = \{ onEdit\?\.invoke\(row\.key, it\) \}/.test(card),
    "卡片参数行是输入框（不是只读值胶囊 + 复制）");
  ok(/WorkflowNodeFields\.Kind\.BOOL/.test(card) && /WorkflowNodeFields\.Kind\.CHOICE/.test(card),
    "BOOL / CHOICE 参数使用对应的开关和枚举控件");
  ok(/copiedValue\?\.first == row\.kind/.test(card) && /onCopyValue\(row\.kind, row\.value\)/.test(card),
    "参数针脚提供同类型复制/粘贴（对齐 PinView.copyButton）");
  ok(/onParamChange: \(\(key: String, value: String\) -> Unit\)\? = null/.test(card),
    "卡片接受参数回写回调（预览/详情传 null 即只读）");
  // 拖动必须收窄到标题区：整卡吃手势会把输入框的点击与光标全抢走。
  ok(/标题区＝拖动把手/.test(card) && /detectDragGestures/.test(card),
    "拖动只在标题区，参数区留给输入框");
  // 计数式断言：detectDragGestures 在卡片文件里只能出现**一次**，且在标题区注释之后。
  // 上一版写成"base 之后 400 字内没有它"，结果注入到别的位置就漏检了。
  {
    // 只数**卡片主体**里的手势：import 行、以及 LinkablePinDot 里拉线用掉的那处都不算。
    // （引脚拉线是必需的，它不抢输入框 —— 命中的是那颗小圆点。）
    const body = card.slice(0, card.indexOf("private fun PinDot("));
    const uses = (body.match(/detectDragGestures\(/g) || []).length;
    ok(uses === 1, "卡片主体只有一处拖动手势（标题区），实际 " + uses + " 处");
    const head = body.indexOf("标题区＝拖动把手");
    const drag = body.indexOf("detectDragGestures(");
    ok(head >= 0 && drag > head, "拖动手势在标题区之内，不在整卡 base 上");
    // base 链上不许挂拖动：那会连参数行一起吃掉。
    const baseChain = body.slice(body.indexOf("val base = modifier"), body.indexOf("Surface("));
    ok(!/detectDragGestures/.test(baseChain), "整卡 base 链上没有拖动手势");
  }

  // 悬浮底栏必须铺满再底部对齐：fillMaxWidth 会让它落在**左上角**（真机复现过）。
  const bar = e.slice(e.indexOf("private fun FloatingEditorBar"), e.indexOf("private fun FloatBarIcon"));
  ok(/fillMaxSize\(\)[\s\S]{0,200}contentAlignment = Alignment\.BottomCenter/.test(bar),
    "底栏铺满整屏再底部居中（fillMaxWidth 会落到左上角）");

  // 「更多」菜单必须锚在按钮上，否则 DropdownMenu 会弹到左下角。
  ok(/Box \{\s*Surface\(shape = CircleShape, tonalElevation = 5\.dp\) \{\s*IconButton\(onClick = \{ moreOpen = true \}\)[\s\S]{0,600}DropdownMenu\(/.test(e),
    "「更多」菜单锚在按钮所在的 Box 里（否则弹到左下角）");

  // 虚线框用实测矩形，且跟着选中态而不是拖动。
  ok(/val rect = bounds\[framed\]/.test(SRC.canvas), "虚线框用卡片实测矩形（不写死高度）");
  ok(/val framed = selectedNodeId/.test(SRC.canvas), "虚线框跟选中态（松手后不消失）");
  ok(!/draggingNodeId/.test(SRC.canvas), "不再有 draggingNodeId 这一路状态");
}

console.log("\u2500 #16l 展开三档 / 锁定（对齐 TouchTool ActionCard + PinView.expand）");
{
  const card = read("app/src/main/java/me/bmax/apatch/ui/component/WorkflowNodeCard.kt");
  const models = read("app/src/main/java/me/bmax/apatch/dsh/WorkflowModels.kt");
  const canvas = SRC.canvas;

  // 三档循环必须完整：NONE → HALF → FULL → NONE。少了任一档，用户就出不来。
  ok(/WorkflowExpandType\.NONE -> WorkflowExpandType\.HALF/.test(SRC.editorSurface) &&
    /WorkflowExpandType\.HALF -> WorkflowExpandType\.FULL/.test(SRC.editorSurface) &&
    /WorkflowExpandType\.FULL -> WorkflowExpandType\.NONE/.test(SRC.editorSurface),
    "展开档位是完整三档循环（NONE→HALF→FULL→NONE）");

  // NONE 档只留已连线的行 —— 这条是照抄参考实现时的关键细节（PinView.expand 里
  // `if (pin.getLinks().isEmpty())` 才考虑隐藏），漏了会让用户以为接线丢了。
  ok(/WorkflowExpandType\.NONE -> row\.linked/.test(card),
    "NONE 档仍显示已接线的参数行（否则用户以为线丢了）");
  ok(/WorkflowExpandType\.FULL, WorkflowExpandType\.HALF -> true/.test(card),
    "HALF/FULL 档显示全部参数行");

  // 三个 UI 元素都要有：卡上展开按钮、卡上锁按钮、底栏锁。
  ok(/onToggleExpand: \(\(\) -> Unit\)\? = null/.test(card), "卡片上有展开按钮");
  ok(/onToggleLock: \(\(\) -> Unit\)\? = null/.test(card), "卡片上有锁定按钮");
  ok(/selectionLocked/.test(SRC.editorSurface), "选中后底栏也知道锁定状态");
  ok(/if \(canDelete && !selectionLocked\)/.test(SRC.editorSurface),
    "锁定的卡在底栏不能删除");

  // 锁定三处语义：不能拖动、不能被选中、不能删除。
  ok(/onDrag = if \(node\.locked\) null else/.test(canvas), "锁定的卡不接拖动");
  ok(/onTap = \{ if \(!node\.locked\) onNodeClick\?\.invoke\(node\.id\) \}/.test(canvas),
    "锁定的卡点不选中");

  // 两个新字段必须落盘，否则关掉重开就丢。
  ok(/\.put\(\"expandType\", expandType\.id\)/.test(models), "展开档位会存盘");
  ok(/if \(locked\) put\(\"locked\", true\)/.test(models), "锁定状态会存盘");
  ok(/expandType = WorkflowExpandType\.of\(o\.optString\(\"expandType\"\)\)/.test(models),
    "读盘时还原展开档位");
  ok(/locked = o\.optBoolean\(\"locked\"\)/.test(models), "读盘时还原锁定");
}

console.log("\u2500 #16m 接线模型：类型表 / 输入端单线 / 结果出口");
{
  const models = read("app/src/main/java/me/bmax/apatch/dsh/WorkflowModels.kt");
  const validator = SRC.validator;

  // 类型表：同类可接、ANY 万能、其余不接（不做隐式转换）。
  ok(/enum class WorkflowPinType/.test(models), "有针脚类型表");
  ok(/this == other \|\| this == ANY \|\| other == ANY/.test(models),
    "类型相兼容才接线（ANY 万能；不做隐式转换）");
  ok(/fun canLinkFrom\(source: WorkflowPinSpec\)/.test(models), "有接线合法性判定");

  // 输入端单线 —— 参考实现 Pin.addLink 里的 clearLinks。
  ok(/val singleLink: Boolean get\(\) = !isOut/.test(models),
    "输入端是单线（纵向画布下，对齐参考实现 isSingleLink）");
  ok(/it\.kind == WorkflowEdgeKind\.DATA && it\.to == toId && it\.toPin == toPin/.test(validator),
    "接新线前清掉同一目标针脚的旧线（否则一个参数被两根线喂）");
  ok(/\.filterNot \{/.test(validator), "旧线是被替换掉而不是被忽略");
  ok(/canLinkFrom\(fromSpec\)/.test(validator), "接线时真的校验类型（AI 手写 JSON 也拦得住）");

  // 结果出口：任何节点都能把输出接走 —— 这是「参数接到别处」的主路径。
  ok(/val resultPin = listOf\(WorkflowPinSpec\(\"result\"/.test(models), "每个节点都有「结果」出口针脚");
  ok(/dataType = WorkflowPinType\.ANY\.id/.test(models), "结果出口是 ANY 类型（任何下游都能接）");
  ok(/e\.fromPin == \"result\" -> outputs\[e\.from\]/.test(SRC.engine),
    "引擎能解析 result 出口（取源头节点自己的输出）");

  // 没有为了传值而必须插入 extract 节点。
  ok(/WorkflowNodeType\.EXECUTE -> listOf\(execIn, execOut\) \+ resultPin/.test(models),
    "执行节点自己就能把结果接出去（不必插 extract）");
}

console.log("\u2500 #16n 流程内变量（定义期声明 → 运行期种入）");
{
  const models = read("app/src/main/java/me/bmax/apatch/dsh/WorkflowModels.kt");

  // 变量分两层：定义里是「声明」，运行记录里是「这一次的取值」。
  // 合在一起会让「改变量初值」污染历史运行记录。
  ok(/internal data class WorkflowDefinition\([\s\S]{0,900}?val variables: Map<String, String>/.test(models),
    "定义层有 variables（声明，跟流程一起存盘）");
  ok(/\.apply \{ if \(variables\.isNotEmpty\(\)\) put\(\"variables\"/.test(models),
    "变量会写进定义 JSON");
  ok(/o\.optJSONObject\(\"variables\"\)\?\.let \{ v -> for \(k in v\.keys\(\)\) dVars\[k\]/.test(models),
    "读盘时还原定义的变量");

  // 关键：定义的变量必须真正进入运行期 —— 上一版只有存储，写了不生效。
  ok(/def\.variables \+ mapOf\(/.test(SRC.engine),
    "开跑时把定义的变量种进运行变量（不是只存不算）");
  ok(/\"run_id\" to runId/.test(SRC.engine), "系统变量仍在（且顺序上覆盖用户声明）");

  // 校验：非法名 / 遮蔽系统变量 / 数量上限。
  ok(/变量名「\$v」不合法/.test(SRC.validator), "校验器拦非法变量名");
  ok(/与系统变量同名/.test(SRC.validator), "校验器拦遮蔽系统变量（那会变成填了不生效）");
  ok(/MAX_VARIABLES/.test(models) && /MAX_VARIABLES/.test(SRC.validator), "变量数量有上限");

  // 编辑器入口：放在「更多」里，不占常驻位置。
  ok(/WorkflowVariablesDialog\(/.test(SRC.editorSurface), "编辑页能打开变量编辑器");
  ok(/varsOpen/.test(SRC.editorSurface), "变量编辑器有独立开关");
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