// DSH-Folk 宿主能力提示词注入插件（cordis / dsh 插件，零依赖单文件 ESM）。
//
// 作用：让容器里的 agent 知道它跑在 DSH-Folk 这个 Android 宿主里，以及宿主额外
// 给了哪些命令（dsh-fs / dsh-native）、此刻哪些能力真的开着。
//
// 为什么走 systemPrompt.section 而不是 AGENTS.md：
//   - section 注册在 global layer，对所有 agent preset 的会话都可见；而 web profile
//     把 agent-instructions 的全局行 disable 了、改由每个 preset 自己挂，用户换 preset
//     就会静默失效。
//   - 进的是 system prompt 前缀，KV cache 稳定，不占对话历史，compaction 后不用重注。
//   - text 允许是求值函数，于是能力开关一改，下一轮组装就是新的，不必重启 dsh。
//
// 事实来源是宿主写的 /root/.dsh/host-facts.json（App 侧 DshHostPrompt.kt）。
// 读文件而不是回环查桥：section 的 text 提供器是同步的，这里发不了 HTTP。

import { readFileSync, statSync } from 'node:fs';

/** cordis 插件名。 */
export const name = 'dsh-folk-host';

/** 只依赖提示词注册表。 */
export const inject = ['systemPrompt'];

/** 宿主事实文件（App 每次状态变化都重写）。 */
const FACTS_PATH = '/root/.dsh/host-facts.json';

/** 段名与顺序：工具指导带（100–199）末尾，晚于各工具自己的说明。 */
const SECTION_NAME = 'host:dsh-folk';
const SECTION_ORDER = 165;

/** 能力 id → 面向模型的说明。与 App 侧 DshNativeBridge.Cap 的 id 一一对应。 */
const CAP_USAGE = {
  notify: [
    'dsh-native notify <标题> [正文] [--id N] [--ongoing]   # 发通知，--id 0..999 用于后续更新/撤销',
    'dsh-native notify-cancel [--id N]                      # 撤销自己发的通知',
  ],
  toast: ['dsh-native toast <文本>                                # 屏幕上一闪而过的短提示'],
  vibrate: ['dsh-native vibrate [--ms N] [--amplitude 1..255]      # 振动，最长 3000ms'],
  clipboard: [
    'dsh-native clip get                                    # 读剪贴板（仅应用在前台时）',
    'dsh-native clip set <文本> [--label L]                  # 写剪贴板',
  ],
  intent: [
    'dsh-native share <文本> [--title T]                     # 拉起系统分享面板',
    'dsh-native open <https 链接>                            # 交给系统浏览器打开',
  ],
  device: ['dsh-native device                                      # 机型 / Android 版本 / 电量 / 存储'],
};

/** 逐能力的注意事项：只在该能力开着时才值得占 token。 */
const CAP_CAVEAT = {
  notify: '通知会打断用户，只在任务真的结束或真的需要人介入时发，不要用来汇报进度。',
  clipboard: '剪贴板读取受 Android 后台限制：应用不在前台时返回 409 not_foreground，这不是错误，别重试。',
  intent: '分享与打开链接要拉起 Activity，同样只在应用前台可用，后台返回 409 not_foreground。',
};

let cached = null;
let cachedMtime = -1;

/**
 * 读宿主事实。按 mtime 判失效 —— 用户在设置里拨一下开关，下一轮组装就是新的。
 * @returns {object|null} 解析后的事实，读不到或坏了返回 null。
 */
function readFacts() {
  let mtime;
  try {
    mtime = statSync(FACTS_PATH).mtimeMs;
  } catch {
    cached = null;
    cachedMtime = -1;
    return null;
  }
  if (cached !== null && mtime === cachedMtime) return cached;
  try {
    const parsed = JSON.parse(readFileSync(FACTS_PATH, 'utf8'));
    cached = typeof parsed === 'object' && parsed !== null ? parsed : null;
  } catch {
    cached = null;
  }
  cachedMtime = mtime;
  return cached;
}

/**
 * 渲染宿主环境说明。
 * @param {object|null} f 宿主事实。
 * @returns {string} 提示词段文本；不该注入时返回空串（renderPrompt 会丢掉空段）。
 */
function render(f) {
  if (f === null || f.promptEnabled === false) return '';

  const lines = [];
  lines.push('# 宿主环境：DSH-Folk（Android）');
  lines.push('');
  lines.push(
    '你跑在 DSH-Folk 这个 Android 应用里：一个 proot 容器中的 Ubuntu，宿主是手机而不是服务器。' +
      '没有显示器、没有 systemd，容器随应用被系统杀掉而停止。'
  );

  const env = [];
  if (typeof f.appVersion === 'string' && f.appVersion !== '') env.push('DSH-Folk ' + f.appVersion);
  if (typeof f.device === 'string' && f.device !== '') env.push(f.device);
  if (typeof f.androidRelease === 'string' && f.androidRelease !== '') {
    env.push('Android ' + f.androidRelease + (typeof f.sdkInt === 'number' ? '（API ' + f.sdkInt + '）' : ''));
  }
  if (typeof f.abi === 'string' && f.abi !== '') env.push(f.abi);
  if (typeof f.containerRuntime === 'string' && f.containerRuntime !== '') env.push('容器 ' + f.containerRuntime);
  if (env.length > 0) {
    lines.push('');
    lines.push('运行环境：' + env.join(' · '));
  }

  // 共享存储：先说清「普通文件工具本来就能用」，避免 agent 以为非得走桥。
  lines.push('');
  lines.push('## 共享存储');
  lines.push('');
  lines.push(
    '手机的共享存储已经 bind 挂进容器，`/sdcard` 与 `/storage/emulated/0` 都是它，' +
      '普通的 read/write/glob/grep 和 shell 命令直接可用 —— 需要动用户文件时优先用这些。'
  );
  if (f.fsBridge === true) {
    lines.push('');
    lines.push(
      '另有 `dsh-fs` 走宿主的受控接口（路径逐段校验、不跟随符号链接出界），' +
        '在普通工具不好办的场合更省事：'
    );
    lines.push('');
    lines.push('```');
    lines.push("dsh-fs find . --glob '*.log' [--maxDepth N] [--limit N]   # 带预算的递归查找");
    lines.push('dsh-fs list [路径] [--recursive] [--maxDepth N] [--limit N]');
    lines.push('dsh-fs space [路径]                                        # 剩余空间');
    lines.push('dsh-fs read <路径> [--offset N] [--length N]               # 分段读，二进制进 stdout');
    lines.push('dsh-fs write <本地文件> [远端路径] [--append]              # 写完才替换目标，不留半截文件');
    lines.push('dsh-fs stat|mkdir|rm [-r]|mv|cp <路径…>');
    lines.push('dsh-fs health');
    lines.push('```');
    lines.push('');
    lines.push('路径都相对 `/sdcard`。写入前先 `dsh-fs space` 看一眼：手机存储满是常态。');
  }

  // 原生能力：只列真的开着的，并说明关着时该怎么办。
  lines.push('');
  lines.push('## 原生能力（dsh-native）');
  lines.push('');
  const bridgeOn = f.nativeBridge === true;
  const caps = Array.isArray(f.nativeCaps) ? f.nativeCaps.filter((c) => typeof c === 'string') : [];
  const usable = caps.filter((c) => Object.prototype.hasOwnProperty.call(CAP_USAGE, c));

  if (!bridgeOn || usable.length === 0) {
    lines.push(
      '`dsh-native` 能借宿主发通知、Toast、振动、读写剪贴板、拉起分享/链接、读设备信息，' +
        '但当前**未启用**（' +
        (bridgeOn ? '总开关已开，但没有勾选任何能力' : '总开关关闭') +
        '）。调用只会返回 403。'
    );
    lines.push('');
    lines.push(
      '需要它时，一次性告诉用户去 **设置 → 功能 → 原生能力** 打开总开关并勾选具体能力，然后继续做别的；' +
        '不要反复重试，也不要反复索要权限。'
    );
  } else {
    lines.push('以下能力**已启用**，可以直接调（失败时 stderr 有 reason，据此判断原因，不要盲目重试）：');
    lines.push('');
    lines.push('```');
    for (const cap of usable) for (const line of CAP_USAGE[cap]) lines.push(line);
    lines.push('dsh-native caps                                        # 查此刻哪些能力开着 / 可用');
    lines.push('```');
    const caveats = usable.map((c) => CAP_CAVEAT[c]).filter((x) => typeof x === 'string');
    if (caveats.length > 0) {
      lines.push('');
      for (const c of caveats) lines.push('- ' + c);
    }
    const off = Object.keys(CAP_USAGE).filter((c) => !usable.includes(c));
    if (off.length > 0) {
      lines.push('');
      lines.push(
        '未勾选（调用返回 403，需要就让用户去 设置 → 功能 → 原生能力 勾上，别重试）：' + off.join('、') + '。'
      );
    }
    if (usable.includes('notify') && f.notificationPermission === false) {
      lines.push('');
      lines.push(
        '注意：系统通知权限尚未授予，`notify` 会成功返回但用户看不到任何东西 —— 先请用户在设置里放通知权限。'
      );
    }
    if (f.foregroundOnlyHint === true) {
      lines.push('');
      lines.push('用户此刻可能没把 DSH-Folk 放在前台，需要前台的能力会返回 409 not_foreground。');
    }
  }

  // 提权状态：直接决定 shell 命令能不能成，值得单独说一句。
  if (typeof f.elevation === 'string' && f.elevation !== '') {
    lines.push('');
    lines.push('## 提权');
    lines.push('');
    if (f.elevation === 'none') {
      lines.push(
        '宿主的特权通道**未启用**（默认如此）。容器里你是 proot 假装的 root，' +
          '但对宿主 Android 系统没有任何特权：读不到 dmesg、动不了 /data、重启不了设备。' +
          '需要这些时告诉用户去 设置 → 功能 → 权限通道 选一条（root / Shizuku / 无线 ADB），不要自己反复试。'
      );
    } else {
      lines.push(
        '宿主特权通道：' +
          f.elevation +
          '。它归 App 用，容器里的命令**不会**自动带上这份特权 —— 别假设 `su` 在容器内可用。'
      );
    }
  }

  return lines.join('\n');
}

/**
 * 注册宿主环境说明段。
 * @param {import('@deepseek-ai/cordis').Context} ctx 携带 systemPrompt 服务的上下文。
 */
export function apply(ctx) {
  ctx.effect(
    () =>
      ctx.systemPrompt.section({
        name: SECTION_NAME,
        order: SECTION_ORDER,
        text: () => render(readFacts()),
      }),
    'dsh-folk-host.section()'
  );
}

// 供宿主侧的等价测试直接调用（不影响插件加载）。
export const __test = { render, CAP_USAGE, CAP_CAVEAT, FACTS_PATH, SECTION_NAME, SECTION_ORDER };
