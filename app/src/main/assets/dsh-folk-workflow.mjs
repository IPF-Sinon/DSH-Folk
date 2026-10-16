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
export const inject = ['systemPrompt'];

const DSL_DOC = '/root/.dsh/workflow-dsl.md';
const STATUS_FILE = '/root/.dsh/workflow-tool-status.json';
const CLI_CANDIDATES = ['dsh-workflow', '/usr/local/bin/dsh-workflow'];

/** 提示词段的兜底文案：宿主 DSL 文档读不到时至少告诉 agent CLI 存在且 --reason 必填。 */
const FALLBACK_SECTION = [
  '## Workflows (dsh-folk)',
  '',
  'This device can run saved, user-approved workflows: use the `dsh-workflow` CLI in the',
  'container (`dsh-workflow list | status | create --file ... | run --workflow ...`).',
  'Every create/run needs `--reason "..."` (the user sees it in the confirmation dialog), and',
  '`run` is non-blocking unless you pass `--wait`. Follow a run with `dsh-workflow status`.',
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
      'if the user edited the definition, a diff you must read.',
    {
      type: 'object',
      properties: {
        definition: {
          type: 'object',
          description: 'The workflow definition (nodes + edges). See the DSL section in the system prompt.',
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
      'permission check. Non-blocking by default: returns a runId immediately — follow it with ' +
      'workflow_status. Pass wait=true only when the whole point is the result.',
    {
      type: 'object',
      properties: {
        workflowId: { type: 'string', description: 'Id of a saved workflow.' },
        definition: { type: 'object', description: 'Or run a one-off definition instead of a saved one.' },
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
      'tail) and new log lines. Reuse logNextLine as the next fromLine so you never re-read the log.',
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

function writeStatus(status) {
  try {
    writeFileSync(STATUS_FILE, JSON.stringify(Object.assign({ updatedAt: Date.now() }, status), null, 2));
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
  } catch (e) {
    writeStatus({ state: 'unavailable', reason: 'systemPrompt section failed: ' + (e && e.message ? e.message : e) });
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
    if (!ctx.tools || typeof ctx.tools.register !== 'function') {
      writeStatus({ state: 'unavailable', reason: 'no tools.register on ctx', outcomes });
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
          reason: 'tool registration failed; use the dsh-workflow CLI',
          outcomes,
        });
        return;
      }
    }
    writeStatus({
      state: 'ok',
      tools: TOOLS.map((t) => t.name),
      defineTool: typeof defineTool === 'function',
      outcomes,
    });
  })().catch((e) => {
    writeStatus({ state: 'unavailable', reason: String((e && e.message) || e), outcomes });
  });
}

export const __test = { TOOLS, FALLBACK_SECTION, DSL_DOC, STATUS_FILE };
