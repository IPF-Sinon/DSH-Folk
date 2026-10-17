/**
 * dsh-folk-workflow —— 把「自动化工作流」交给容器里的 agent。
 *
 * ## 它做什么
 *
 * 两个职责，彼此独立（因为可靠性完全不同）：
 *
 * 1. **提示词段**：把 `/root/.dsh/workflow-dsl.md`（宿主 App 写的 DSL 说明）贴进系统提示词。
 *    这一步只依赖 dsh 的 `systemPrompt` 注入点，几乎不会失败。
 * 2. **工具注册**：注册 `workflow_create / workflow_run / workflow_status / workflow_cancel`。
 *    这一步**依赖 dsh 的工具 API 形状**，不同版本不一样，所以按三级降级：
 *    先 `@deepseek-ai/dsh-tools` 的 `defineTool`，再直接 `ctx.tools.register(裸 spec)`，
 *    都失败就只留提示词段（并让 agent 用 `dsh-workflow` CLI —— 工具没有，能力不丢）。
 *
 * ## 为什么工具只是 CLI 的壳
 *
 * 四个工具都不自己拼 HTTP：它们调用 `dsh-workflow`（宿主落盘的 node 脚本）。
 * 这样「agent 调工具」和「人敲命令」走的是**同一条实现** —— token、端口、错误格式、
 * 审批语义只有一份，不会出现「CLI 能建但工具建不了」这类分叉。
 *
 * ## 注册结果要落盘
 *
 * 设置页要如实告诉用户「容器里的工具到底注册上了没有」，所以把结论写进
 * `/root/.dsh/workflow-tool-status.json`：`ok` / `degraded` / `unavailable` + 原因。
 * 不写的话，用户遇到「agent 说没有这个工具」时无从判断是插件没装、还是工具 API 不兼容。
 */
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { execFile } from 'node:child_process';

export const name = 'dsh-folk-workflow';
// 只声明 systemPrompt：工具服务在旧运行时上可能不存在，而 cordis 对「声明了却拿不到的
// 服务」会让**整个 entry** 不激活 —— 那样连提示词段都没了，agent 会以为这台设备根本不支持
// 工作流。工具能力改为进 apply 后探测 ctx.tools（见下），探测不到就退化成只有 CLI。
// 必须把 tools 一起注入：cordis 对**没声明 inject 的服务**会直接抛
// 「cannot get property 'tools' without inject」，而不是返回 undefined —— 只声明 systemPrompt 时
// ctx.tools 一读就炸，结果工具永远注册不上（真机自检里那句 unavailable 的真凶）。
export const inject = ['systemPrompt', 'tools'];

const DSL_DOC = '/root/.dsh/workflow-dsl.md';
const STATUS_FILE = '/root/.dsh/workflow-tool-status.json';
const CLI_CANDIDATES = ['dsh-workflow', '/usr/local/bin/dsh-workflow'];

/**
 * 格式速查：工具描述与提示词兜底**共用同一份文本**。
 *
 * 为什么放在工具描述里（而不是只放在系统提示词的 DSL 段）：工具描述一定会随工具 schema 到达
 * 模型，而提示词段依赖「宿主把 DSL 文档写进容器 + 这个插件真的激活」两个前提。实测里 agent
 * 正是卡在「不知道格式」上 —— 那就把格式放在最不可能丢的那条通道上。
 */
const FORMAT = [
  "  FORMAT (a workflow is plain data; nothing else is accepted):",
  "    { \"schemaVersion\": 1, \"id\": \"<id>\", \"name\": \"<name>\", \"description\": \"<optional>\",",
  "      \"declaredCaps\": [\"notify\"], \"nodes\": [ ... ], \"edges\": [ ... ] }",
  "",
  "  Node = { \"id\", \"type\", \"name\", \"posX\", \"posY\", \"timeoutMs\", \"onError\", \"config\" }",
  "    id: letter first, then [A-Za-z0-9_-], <= 32 chars, unique",
  "    timeoutMs: 1000..3600000 (default 120000); onError: \"stop\" (default) | \"continue\" | \"skip\"",
  "    types and their REQUIRED config:",
  "      trigger   - exactly one, no incoming edge. config: requireDsh \"0\"|\"1\", autostartDsh \"0\"|\"1\".",
  "                  With requireDsh \"1\" it starts DSH if needed, then takes the \"true\" edge; if DSH",
  "                  cannot start it takes \"false\" (draw one) or the run fails.",
  "      execute   - config: action \"shell\" | \"dsh\" | \"notify\".",
  "                  shell: cmd (<=8192 chars, runs in the container)",
  "                  dsh:   cmd (same, but DSH is started and awaited first)",
  "                  notify: title (optional), text",
  "      condition - config: source \"exitCode\" | \"lastOutput\" | \"var:<name>\" | \"branch:<id>\" |",
  "                  \"step:<id>\", op \"contains\" | \"equals\" | \"matches\" | \"exists\" | \"empty\",",
  "                  value. Gives a \"true\" and a \"false\" edge.",
  "      logic     - config: op \"and\" | \"or\" | \"not\", sources \"id,id\". Gives true/false edges.",
  "      extract   - config: source \"lastOutput\" | \"step:<id>\", mode \"text\" | \"regex\" | \"json\",",
  "                  pattern (regex) or path (dotted JSON path), var (name it writes)",
  "      wait      - config: ms (<=86400000) or untilDsh \"running\" | \"stopped\"",
  "",
  "  Edge = { \"from\": \"<nodeId>\", \"to\": \"<nodeId>\", \"when\": \"\" | \"true\" | \"false\" }",
  "    only condition / logic, and a trigger with requireDsh \"1\", may use when, and they need BOTH",
  "    a true and a false edge. The graph must be acyclic; every node must be reachable from trigger.",
  "",
  "  Variables: ${name} inside cmd / notify text comes from the run. extract writes them; these are",
  "    pre-seeded: run_id, workflow_id, workflow_name, dsh_port, dsh_url, dsh_state.",
  "",
  "  Minimal valid example (also: dsh-workflow sample minimal):",
  "    { \"schemaVersion\": 1, \"id\": \"sample-minimal\", \"name\": \"minimal\", \"declaredCaps\": [],",
  "      \"nodes\": [",
  "        { \"id\": \"trigger\", \"type\": \"trigger\", \"name\": \"trigger\", \"config\": { \"requireDsh\": \"0\" } },",
  "        { \"id\": \"hello\", \"type\": \"execute\", \"name\": \"hello\",",
  "          \"config\": { \"action\": \"shell\", \"cmd\": \"echo hello\" } } ],",
  "      \"edges\": [ { \"from\": \"trigger\", \"to\": \"hello\", \"when\": \"\" } ] }",
  "",
  "  Cheaper starting points: run `dsh-workflow samples`, print one with `dsh-workflow sample <name>`,",
  "  create straight from it with `dsh-workflow create --file /root/.dsh/workflow-samples/<name>.json",
  "  --reason \"...\"`, or read the whole guide with `dsh-workflow guide`.",].join("\n");

/** 提示词段的兜底文案：宿主 DSL 文档读不到时也要给出**完整格式**（否则 agent 只能瞎猜）。 */
const FALLBACK_SECTION = [
  '## Workflows (dsh-folk)',
  '',
  'This device can run saved, user-approved workflows. Two ways in:',
  '  - CLI in the container: dsh-workflow list | status | create --file ... | run --workflow ...',
  '  - Tools: workflow_create / workflow_run / workflow_status / workflow_cancel (if registered)',
  'Every create/run needs a reason (the user sees it in the confirmation dialog); run is',
  'non-blocking unless you ask for wait; follow a run with dsh-workflow status.',
  '',
  FORMAT,
].join('\n');

function runCli(args, timeoutMs) {
  return new Promise(function (resolve) {
    let tried = 0;
    function attempt() {
      const bin = CLI_CANDIDATES[tried];
      execFile(bin, args, { timeout: timeoutMs, maxBuffer: 4 * 1024 * 1024 }, function (err, stdout, stderr) {
        if (err && err.code === 'ENOENT' && tried + 1 < CLI_CANDIDATES.length) {
          tried += 1;
          attempt();
          return;
        }
        const out = (stdout || '').trim();
        const errText = (stderr || '').trim();
        if (err && !out) {
          resolve('dsh-workflow failed: ' + (errText || err.message));
          return;
        }
        resolve(errText ? out + '\n' + errText : out);
      });
    }
    attempt();
  });
}

function spec(name, description, parameters, buildArgs, timeoutMs) {
  return {
    name: name,
    description: description,
    parameters: parameters,
    execute: async function (args) {
      const a = args || {};
      return await runCli(buildArgs(a), timeoutMs);
    },
  };
}

const TOOLS = [
  spec(
    'workflow_create',
    'Save an automation workflow. The user sees a YELLOW confirmation dialog with a flow-chart ' +
      'preview and may edit or reject it; nothing runs. The response carries the workflowId and, ' +
      'if the user edited the definition, a diff you must read.\n\n' + FORMAT,
    {
      type: 'object',
      properties: {
        definition: {
          type: 'object',
          description: 'The workflow definition (nodes + edges). Must match the FORMAT given in this ' +
            'tool description; an invalid definition is rejected with a list of errors, it is never ' +
            'partially executed. Copy a sample from dsh-workflow samples when unsure.',
        },
        reason: { type: 'string', description: 'Why you want to create this workflow (shown to the user).' },
        name: { type: 'string', description: 'Optional display name override.' },
      },
      required: ['definition', 'reason'],
      additionalProperties: false,
    },
    function (a) {
      return ['create', '--json', JSON.stringify(a.definition || {}), '--reason', String(a.reason || '')]
        .concat(a.name ? ['--name', String(a.name)] : []);
    },
    300000,
  ),
  spec(
    'workflow_run',
    'Run a workflow. The user sees a RED confirmation dialog with the flow chart, then an Android ' +
      'permission check. Non-blocking by default: returns a runId immediately - follow it with ' +
      'workflow_status. Pass wait=true only when the whole point is the result.\n\n' +
      'Either pass workflowId (a saved workflow) or a full definition in the FORMAT below.',
    {
      type: 'object',
      properties: {
        workflowId: { type: 'string', description: 'Id of a saved workflow.' },
        definition: {
          type: 'object',
          description: 'Or run a one-off definition instead of a saved one (same FORMAT as ' +
            'workflow_create). A definition that fails validation is rejected before anything runs.',
        },
        wait: { type: 'boolean', description: 'Block until the run finishes (default false).' },
        reason: { type: 'string', description: 'Why you want to run this (shown to the user).' },
      },
      required: ['reason'],
      additionalProperties: false,
    },
    function (a) {
      const args = ['run'];
      if (a.workflowId) args.push('--workflow', String(a.workflowId));
      else args.push('--json', JSON.stringify(a.definition || {}));
      if (a.wait) args.push('--wait');
      args.push('--reason', String(a.reason || ''));
      return args;
    },
    900000,
  ),
  spec(
    'workflow_status',
    'Where a run is now: state, every finished step (state / exitCode / duration / reason / output ' +
      'tail) and new log lines. Reuse logNextLine as the next fromLine so you never re-read the log. ' +
      'This is how you follow a run started without wait=true.',
    {
      type: 'object',
      properties: {
        runId: { type: 'string', description: 'A specific run; omit for the most recent one.' },
        workflowId: { type: 'string', description: 'Instead: recent runs of one workflow.' },
        fromLine: { type: 'number', description: 'Only return log lines after this line number.' },
        includeLogs: { type: 'boolean', description: 'Set false to skip logs entirely.' },
      },
      additionalProperties: false,
    },
    function (a) {
      const args = ['status'];
      if (a.runId) args.push('--run', String(a.runId));
      if (a.workflowId) args.push('--workflow', String(a.workflowId));
      if (a.fromLine) args.push('--from', String(a.fromLine));
      if (a.includeLogs === false) args.push('--no-logs');
      return args;
    },
    120000,
  ),
  spec(
    'workflow_cancel',
    'Cancel a running workflow. Kills the step that is executing right now (not just the schedule), ' +
      'so a long command stops instead of finishing.',
    {
      type: 'object',
      properties: {
        runId: { type: 'string', description: 'The run to cancel.' },
        reason: { type: 'string', description: 'Optional reason, recorded with the run.' },
      },
      required: ['runId'],
      additionalProperties: false,
    },
    function (a) {
      const args = ['cancel', '--run', String(a.runId || '')];
      if (a.reason) args.push('--reason', String(a.reason));
      return args;
    },
    60000,
  ),
];

/**
 * 把诊断写进状态文件（设置页「自检」读它）。
 *
 * 为什么要分 sectionState / toolsState 并带上 reason：实测里设置页显示「工具注册 unavailable」，
 * 但 agent 用 CLI 干得好好的 —— 只有一个笼统的 state 时，用户只能看到「爆红」却不知道红在哪。
 * 分开之后每一环各自诚实：提示词段成没成、工具注册成没成、失败原因是什么。
 */
function writeStatus(patch) {
  try {
    let prev = {};
    try { prev = JSON.parse(readFileSync(STATUS_FILE, 'utf8')); } catch (e) { prev = {}; }
    const next = Object.assign({}, prev, patch, { updatedAt: Date.now() });
    writeFileSync(STATUS_FILE, JSON.stringify(next, null, 2));
  } catch (e) {
    // 状态文件只是给设置页看的，写不了不该影响功能
  }
}

/** 提示词段：宿主写的 DSL 说明优先，读不到就用兜底文案。 */
function renderSection() {
  try {
    if (existsSync(DSL_DOC)) {
      const text = readFileSync(DSL_DOC, 'utf8').trim();
      if (text) return text;
    }
  } catch (e) {
    /* 落到兜底 */
  }
  return FALLBACK_SECTION;
}

export function apply(ctx) {
  // 1) 提示词段（先做：它不依赖工具 API，失败面最小）。
  //    text 传函数而不是字符串：dsh 每次拼提示词时现取，改了 DSL 文档不必重启容器。
  const section = { name: 'dsh-folk-workflow', order: 120, text: () => renderSection() };
  try {
    if (typeof ctx.effect === 'function') {
      ctx.effect(() => ctx.systemPrompt.section(section), 'dsh-folk-workflow.section()');
    } else {
      ctx.systemPrompt.section(section);
    }
    writeStatus({ sectionState: 'ok' });
  } catch (e) {
    writeStatus({
      sectionState: 'unavailable',
      sectionReason: 'systemPrompt section failed: ' + (e && e.message ? e.message : e),
    });
  }

  // 2) 工具注册，三级降级
  const register = (specObj) => {
    if (typeof defineTool === 'function') return ctx.tools.register(defineTool(specObj));
    return ctx.tools.register(specObj);
  };
  let defineTool = null;
  const outcomes = [];
  (async () => {
    try {
      const mod = await import('@deepseek-ai/dsh-tools');
      if (mod && typeof mod.defineTool === 'function') defineTool = mod.defineTool;
    } catch (e) {
      outcomes.push('dsh-tools import failed: ' + (e && e.message ? e.message : e));
    }
    // 工具服务可能比本插件晚就绪（cordis 的服务是懒注入的）：等一小会儿再试，别把
    // 「还没就绪」写成「不可用」—— 那正是设置页那个假红点。
    for (let attempt = 0; attempt < 20 && (!ctx.tools || typeof ctx.tools.register !== 'function'); attempt++) {
      await new Promise((r) => setTimeout(r, 250));
    }
    const shape = ctx.tools ? typeof ctx.tools.register : 'no ctx.tools';
    if (!ctx.tools || typeof ctx.tools.register !== 'function') {
      writeStatus({
        toolsState: 'unavailable',
        toolsReason: 'tools service never became available (register=' + shape + '); use the dsh-workflow CLI',
        outcomes,
      });
      return;
    }
    for (const t of TOOLS) {
      try {
        register(t);
      } catch (e) {
        outcomes.push(t.name + ': ' + (e && e.message ? e.message : e));
        // 一个工具注册不上就整体退回「只有 CLI」——半个工具集比没有更让人困惑
        writeStatus({
          state: 'degraded',
          toolsState: 'degraded',
          toolsReason: 'tool registration failed: ' + outcomes[outcomes.length - 1],
          reason: 'tool registration failed; use the dsh-workflow CLI',
          outcomes,
        });
        return;
      }
    }
    writeStatus({
      state: 'ok',
      toolsState: 'ok',
      toolsReason: '',
      tools: TOOLS.map((t) => t.name),
      defineTool: typeof defineTool === 'function',
      outcomes,
    });
  })().catch((e) => {
    writeStatus({
      state: 'unavailable',
      toolsState: 'unavailable',
      toolsReason: String((e && e.message) || e),
      outcomes,
    });
  });
}

export const __test = { TOOLS, FALLBACK_SECTION, DSL_DOC, STATUS_FILE };
