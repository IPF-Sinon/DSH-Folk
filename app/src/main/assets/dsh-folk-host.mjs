// DSH-Folk host-capability prompt plugin (cordis / dsh plugin, single zero-dependency ESM file).
//
// Purpose: tell the agent inside the container that it runs on an Android host, which extra
// commands that host provides (dsh-fs / dsh-native), and which capabilities are actually on
// right now.
//
// Why systemPrompt.section instead of AGENTS.md:
//   - A section registered in the global layer is visible to every agent preset, while the web
//     profile disables the global agent-instructions row and mounts it per preset instead — so
//     an AGENTS.md rule silently disappears when the user switches preset.
//   - It lands in the system-prompt prefix: stable KV cache, no conversation-history cost, and
//     nothing to re-inject after compaction.
//   - `text` may be a provider function, so flipping a switch changes the very next assemble
//     without restarting dsh.
//
// Why this text is English while the app UI is localized: every section dsh itself registers
// (harness:identity, tool:read, tool:bash …) is English. A mixed-script system prompt nudges
// the model's output language, which is not ours to decide — the user's language is passed as
// a FACT below (`locale`) so the model can honour it deliberately.
//
// Facts come from /root/.dsh/host-facts.json, written by the app (DshHostPrompt.kt).
// Reading a file rather than querying the bridge: a section text provider is synchronous, so
// no HTTP can happen here.

import { readFileSync, statSync } from 'node:fs';

/** cordis plugin name. */
export const name = 'dsh-folk-host';

/** Only the prompt registry is needed. */
export const inject = ['systemPrompt'];

/** Host fact file; the app rewrites it on every relevant state change. */
const FACTS_PATH = '/root/.dsh/host-facts.json';

/** Section name and order: end of the tool-guidance band (100–199), after each tool's own text. */
const SECTION_NAME = 'host:dsh-folk';
const SECTION_ORDER = 165;

/** Capability id -> model-facing usage. Ids match DshNativeBridge.Cap on the app side. */
const CAP_USAGE = {
  notify: [
    'dsh-native notify <title> [body] [--id N] [--ongoing]  # post a notification; --id 0..999 to update/cancel later',
    'dsh-native notify-cancel [--id N]                      # cancel one you posted',
  ],
  toast: ['dsh-native toast <text>                                # brief on-screen message'],
  vibrate: ['dsh-native vibrate [--ms N] [--amplitude 1..255]      # vibrate, 3000ms max'],
  clipboard: [
    'dsh-native clip get                                    # read the clipboard (foreground only)',
    'dsh-native clip set <text> [--label L]                 # write the clipboard',
  ],
  intent: [
    'dsh-native share <text> [--title T]                    # bring up the system share sheet',
    'dsh-native open <https URL>                            # hand a link to the system browser',
  ],
  device: ['dsh-native device                                      # model / Android version / battery'],
  media: [
    'dsh-native media list [--type image|video|audio] [--q name] [--limit N]',
    'dsh-native media get <id> [--type image|video|audio]   # copies the file into /tmp, returns its path',
  ],
  mic: ['dsh-native mic record [--ms N]                         # record up to 30000ms into /tmp'],
};

/** Per-capability caveats; only worth tokens while that capability is on. */
const CAP_CAVEAT = {
  notify:
    'A notification interrupts the user. Post one when the task is genuinely done or genuinely ' +
    'needs a human, never to report progress.',
  clipboard:
    'Clipboard reads are subject to Android background limits: 409 not_foreground when the app is ' +
    'not in the foreground. That is a state, not an error — do not retry.',
  intent:
    'Sharing and opening links start an Activity, so they too need the app in the foreground; ' +
    'the background answer is 409 not_foreground.',
  media:
    'media get does NOT stream bytes back: it copies the file into the container and returns a path ' +
    'under /tmp, which you then read with ordinary file tools. Media permission is per type on ' +
    'Android 13+, so `granted` in the list response tells you which types you may actually read — ' +
    'an absent type means "not permitted", not "no such files".',
  mic:
    'Recording needs the app in the foreground: in the background Android hands out SILENCE rather ' +
    'than an error, so the host refuses with 409 not_foreground instead of returning a silent file. ' +
    'Recording is a physically intrusive act — only do it when the user asked for it in this turn.',
};

let cached = null;
let cachedMtime = -1;

/**
 * Read the host facts, invalidating on mtime — flipping a switch in settings takes effect on the
 * very next assemble.
 * @returns {object|null} parsed facts, or null when unreadable/malformed.
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

/** Non-empty string, or null. */
function str(v) {
  return typeof v === 'string' && v !== '' ? v : null;
}

/**
 * Render the host-environment section.
 * @param {object|null} f host facts.
 * @returns {string} section text; empty string when it must not be injected (renderPrompt drops
 *   empty sections, so that is a zero-cost off switch).
 */
function render(f) {
  if (f === null || f.promptEnabled === false) return '';

  const lines = [];
  lines.push('# Host environment: DSH-Folk (Android)');
  lines.push('');
  lines.push(
    'You are running inside DSH-Folk, an Android app: Ubuntu in a proot container on a phone, not a ' +
      'server. There is no display and no systemd, and the container stops when Android kills the app.'
  );

  const env = [];
  const version = str(f.appVersion);
  const device = str(f.device);
  const release = str(f.androidRelease);
  const abi = str(f.abi);
  const runtime = str(f.containerRuntime);
  if (version !== null) env.push('DSH-Folk ' + version);
  if (device !== null) env.push(device);
  if (release !== null) {
    env.push('Android ' + release + (typeof f.sdkInt === 'number' ? ' (API ' + f.sdkInt + ')' : ''));
  }
  if (abi !== null) env.push(abi);
  if (runtime !== null) env.push('container ' + runtime);
  if (env.length > 0) {
    lines.push('');
    lines.push('Environment: ' + env.join(' · '));
  }

  // The user's language is a fact, not a reason to translate this section.
  const locale = str(f.locale);
  if (locale !== null) {
    lines.push('');
    lines.push(
      "The device language is " +
        locale +
        '. Reply in the language the user writes in, and localize anything you put on their screen ' +
        '(notification and toast text goes to a phone set to ' +
        locale +
        ').'
    );
  }

  // Shared storage: say up front that ordinary file tools already work, so the agent does not
  // assume the bridge is mandatory.
  lines.push('');
  lines.push('## Shared storage');
  lines.push('');
  lines.push(
    "The phone's shared storage is bind-mounted into the container — `/sdcard` and " +
      '`/storage/emulated/0` are both it, and ordinary read/write/glob/grep and shell commands work ' +
      'on it directly. Prefer those when you need to touch user files.'
  );
  if (f.fsBridge === true) {
    lines.push('');
    lines.push(
      'A `dsh-fs` command also goes through the host with a narrower, audited surface (every path ' +
        'segment validated, symlinks cannot escape the root). It is easier for a few things:'
    );
    lines.push('');
    lines.push('```');
    lines.push("dsh-fs find . --glob '*.log' [--maxDepth N] [--limit N]   # budgeted recursive search");
    lines.push('dsh-fs list [path] [--recursive] [--maxDepth N] [--limit N]');
    lines.push('dsh-fs space [path]                                        # free space');
    lines.push('dsh-fs read <path> [--offset N] [--length N]               # paged read, binary to stdout');
    lines.push('dsh-fs write <localFile> [remotePath] [--append]           # replaces the target only once complete');
    lines.push('dsh-fs stat|mkdir|rm [-r]|mv|cp <path…>');
    lines.push('dsh-fs health');
    lines.push('```');
    lines.push('');
    lines.push('All paths are relative to `/sdcard`. Check `dsh-fs space` before writing: full phones are normal.');
  } else {
    lines.push('');
    lines.push(
      'The `dsh-fs` command exists but every file endpoint currently answers 403 ' +
        '(`reason: "no_storage"`): Android requires "All files access", which has not been granted. ' +
        'The bind mount above may still be readable, so try ordinary file tools first. If they also ' +
        'fail, tell the user once to grant it in **Settings › Features › Shared storage** and move on ' +
        '— do not retry dsh-fs in a loop.'
    );
  }

  // Native capabilities: list only what is genuinely on, and say what to do when it is not.
  lines.push('');
  lines.push('## Native capabilities (dsh-native)');
  lines.push('');
  const bridgeOn = f.nativeBridge === true;
  const caps = Array.isArray(f.nativeCaps) ? f.nativeCaps.filter((c) => typeof c === 'string') : [];
  const usable = caps.filter((c) => Object.prototype.hasOwnProperty.call(CAP_USAGE, c));

  if (!bridgeOn || usable.length === 0) {
    lines.push(
      '`dsh-native` can borrow the host to post notifications, show a toast, vibrate, use the ' +
        'clipboard, open a share sheet or link, read device info, read the media library and record ' +
        'audio — but it is currently **off** (' +
        (bridgeOn ? 'the master switch is on, but no capability is ticked' : 'the master switch is off') +
        '), so every call returns 403.'
    );
    lines.push('');
    lines.push(
      'If you need it, tell the user ONCE to open **Settings › Features › Native capabilities**, turn ' +
        'on the master switch and tick the specific capability, then carry on with something else. Do ' +
        'not retry, and do not keep asking.'
    );
  } else {
    lines.push(
      'These capabilities are **on** and can be called directly. On failure stderr carries a JSON ' +
        '`reason` — read it and act on it rather than retrying blindly:'
    );
    lines.push('');
    lines.push('```');
    for (const cap of usable) for (const line of CAP_USAGE[cap]) lines.push(line);
    lines.push('dsh-native caps                                        # which capabilities are on / available now');
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
        'Not ticked (calls return 403; if you need one, ask the user to tick it in Settings › Features ' +
          '› Native capabilities and do not retry): ' +
          off.join(', ') +
          '.'
      );
    }

    // Ticked but the OS permission is missing: the call fails, or worse, silently does nothing.
    if (usable.includes('notify') && f.notificationPermission === false) {
      lines.push('');
      lines.push(
        'Note: the system notification permission is NOT granted, so `notify` returns success while ' +
          'the user sees nothing. Ask them to grant notifications first.'
      );
    }
    if (usable.includes('media')) {
      const granted = Array.isArray(f.mediaPermissions)
        ? f.mediaPermissions.filter((t) => typeof t === 'string')
        : [];
      lines.push('');
      if (granted.length === 0) {
        lines.push(
          'Note: no media read permission is granted, so every `media` call returns 403 ' +
            '(`reason: "no_media_permission"`). Ask the user to grant it, once.'
        );
      } else if (granted.length < 3) {
        lines.push(
          'Media permission is granted for ' +
            granted.join(', ') +
            ' only; the other types answer 403 (`reason: "no_media_permission"`). That means ' +
            '"not permitted", not "nothing found".'
        );
      }
    }
    if (usable.includes('mic') && f.microphonePermission === false) {
      lines.push('');
      lines.push(
        'Note: the microphone permission is NOT granted, so `mic record` returns 409 ' +
          '(`reason: "no_audio_permission"`). Ask the user to grant it, once.'
      );
    }
  }

  // Elevation decides whether shell commands can succeed at all, so it earns its own line.
  const elevation = str(f.elevation);
  if (elevation !== null) {
    lines.push('');
    lines.push('## Elevation');
    lines.push('');
    if (elevation === 'none') {
      lines.push(
        "The host's privileged channel is **off** (the default). Inside the container you are a root " +
          'that proot is faking; you have no privilege over Android itself — no dmesg, no /data, no ' +
          'reboot. When you need that, tell the user to pick a channel in Settings › Features › ' +
          'Permission channel (root / Shizuku / wireless ADB) instead of trying repeatedly.'
      );
    } else {
      lines.push(
        "The host's privileged channel is " +
          elevation +
          '. It belongs to the app: commands you run in the container do NOT inherit it, so do not ' +
          'assume `su` works in here.'
      );
    }
  }

  return lines.join('\n');
}

/**
 * Register the host-environment section.
 * @param {import('@deepseek-ai/cordis').Context} ctx context carrying the systemPrompt service.
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

// Exposed for the host-side equivalence tests (does not affect plugin loading).
export const __test = { render, CAP_USAGE, CAP_CAVEAT, FACTS_PATH, SECTION_NAME, SECTION_ORDER };
