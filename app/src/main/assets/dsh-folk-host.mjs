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
  camera: [
    'dsh-native camera photo [--facing back|front] [--max N]  # no preview; copies a JPEG into /tmp',
  ],
  tts: [
    'dsh-native tts say <text> [--lang zh-CN] [--rate 0.1..3] [--pitch 0.5..2]  # read aloud, waits until done',
    'dsh-native tts file <text> [--lang L]                  # synthesise a wav into /tmp, returns its path',
    'dsh-native tts voices                                  # which languages/voices this device can actually read',
  ],
  calendar: [
    'dsh-native calendar list [--days N] [--limit N]        # upcoming events, repeats expanded',
    'dsh-native calendar add <title> --start <epochMs> [--minutes N] [--location L]',
  ],
  contacts: [
    'dsh-native contacts list [--q name-or-number] [--limit N]  # names and numbers, read only',
  ],
  location: [
    'dsh-native location [--maxAge ms] [--wait ms]          # cached fix first, GPS only if needed',
  ],
  phone: [
    'dsh-native phone                                       # carrier / network type / SIM / call state',
  ],
  sensors: [
    'dsh-native sensors list                                # which sensors this device has',
    'dsh-native sensors read <id>                           # one sample, e.g. light, accelerometer',
  ],
  network: [
    'dsh-native network                                     # transport, validated, metered, wifi signal',
  ],
  volume: [
    'dsh-native volume                                      # every stream with its max',
    'dsh-native volume set <0..100> [--stream music|ring|alarm|notification|call|system]',
    'dsh-native ringer <normal|vibrate|silent>              # needs Do Not Disturb access',
  ],
  settings: [
    'dsh-native settings                                    # brightness / timeout / auto-rotate',
    'dsh-native settings brightness <1..100> [--auto 0|1]',
    'dsh-native settings timeout <ms>',
    'dsh-native settings rotation <0|1>',
  ],
  install: [
    'dsh-native install                                     # may this device install unknown apps?',
  ],
};

/** Per-capability caveats; only worth tokens while that capability is on. */
const CAP_CAVEAT = {
  notify:
    'A notification interrupts the user. Post one when the task is genuinely done or genuinely ' +
    'needs a human, never to report progress.',
  vibrate:
    'Tablets and emulators often have no vibrator at all, in which case this reports ' +
    'available:false with reason no_vibrator — that is a property of the device, not a transient ' +
    'error, so do not retry. Vibration is silent feedback: it only reaches the user if the phone is ' +
    'on them.',
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
  camera:
    'Like mic, the camera needs the app in the foreground (a background app gets a black frame) and ' +
    'the photo lands in /tmp as a path, not as bytes. Taking a picture is physically intrusive — ' +
    'only when the user asked in this turn.',
  tts:
    'The one capability that works in the background — that is its purpose: when the phone is in a ' +
    'pocket, saying something out loud is the only way to reach the user. It uses whatever engine the ' +
    'device has, so whether it can read a given language is a property of the device, not of the text: ' +
    'check tts voices before assuming Chinese or any non-English language will be spoken correctly, ' +
    'because setting an unsupported language silently falls back to the default voice and produces ' +
    'gibberish. say waits until the utterance finishes, so do not fire two in a row expecting both to ' +
    'be heard. Speaking is audible to everyone nearby, so keep it short and do not read out private ' +
    'content unless the user asked for exactly that.',
  calendar:
    'Times are epoch MILLISECONDS in the device timezone. calendar add creates a real event the ' +
    'user will see and get reminders for, so confirm the details before writing rather than ' +
    'guessing a time. There is no delete endpoint: a wrong event has to be removed by hand.',
  contacts:
    'Read only, and it returns just names and numbers. Do not dump the whole address book into ' +
    'your reasoning — pass --q and --limit to fetch only who you actually need.',
  location:
    'A cached fix is returned when recent enough (fresh:false says so); only otherwise is the GPS ' +
    'woken, which can take tens of seconds indoors. precise:false means the user granted only ' +
    'approximate location and the coordinates are deliberately blurred to a few kilometres — do ' +
    'not present them as a street address.',
  phone:
    'Network environment only. There is no dialling, no SMS and no IMEI: Android does not hand ' +
    'those to ordinary apps, and this host will not proxy them.',
  sensors:
    'One sample per call, not a stream. Most sensors need no permission; heart rate and step count ' +
    'do, and the list response says which ones are missing a permission rather than hiding them ' +
    'silently. proximity and light settle fast; expect a 409 read_timeout on sensors this device ' +
    'only updates on change.',
  network:
    'validated:false with connected:true is the captive-portal case: associated but no real ' +
    'internet. The bandwidth numbers are the system ESTIMATE, not a measurement — never report ' +
    'them as a speed test. ssidHidden:true means location permission is missing, not that the ' +
    'network has no name.',
  volume:
    'Percentages, because the number of steps differs per stream and per device; the raw value and ' +
    'max come back in the response. Setting silent, or changing volume while Do Not Disturb is on, ' +
    'needs Do Not Disturb access and fails with 403 no_dnd_access otherwise. Nothing restores the ' +
    'previous level for the user — say what you changed.',
  settings:
    'Brightness is 1..100 (never 0 — a black screen is unrecoverable by hand). If autoBrightness ' +
    'is still true in the response, the system will overwrite your value within seconds; pass ' +
    '--auto 0 when you mean it to stick. These changes are global and permanent: report the ' +
    'before/after values the response gives you.',
  install:
    'Status only — it installs nothing. Use it before suggesting the in-app update: when ' +
    'canRequestInstall is false the download will succeed and the install will not.',
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
        'clipboard, open a share sheet or link, read device info and network state, read sensors, ' +
        'read the media library, take a photo, record audio, speak text aloud, read location, ' +
        'calendar and contacts, ' +
        'and change volume or system settings — but it is currently **off** (' +
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
    // Only the counter-intuitive ones get their own line — an ordinary 403 with a reason is
    // self-explanatory and does not need prompt real estate.
    if (usable.includes('notify') && f.notificationPermission === false) {
      lines.push('');
      lines.push(
        'Note: the system notification permission is NOT granted, so `notify` returns success while ' +
          'the user sees nothing. Ask them to grant notifications first.'
      );
    }
    if (usable.includes('location') && f.preciseLocation === false) {
      lines.push('');
      lines.push(
        'Note: only APPROXIMATE location is granted. Coordinates come back deliberately blurred to ' +
          'a few kilometres — usable for a city, not for an address. Do not ask for the precise ' +
          'permission repeatedly; many people grant this on purpose.'
      );
    }
    if (usable.includes('settings') && f.writeSettings === false) {
      lines.push('');
      lines.push(
        'Note: "Modify system settings" is NOT granted, so every settings write returns 403 ' +
          '(`reason: "no_write_settings"`). It cannot be requested from code — only the user can ' +
          'grant it on a system page. Say so once and move on.'
      );
    }
    if (usable.includes('volume') && f.dndAccess === false) {
      lines.push('');
      lines.push(
        'Note: Do Not Disturb access is NOT granted. Reading volume works; setting silent/vibrate, ' +
          'and changing volume while DND is on, return 403 (`reason: "no_dnd_access"`).'
      );
    }
    if (usable.includes('install') && f.canRequestInstall === false) {
      lines.push('');
      lines.push(
        'Note: this device does not currently allow installing unknown apps, so an in-app update ' +
          'would download fine and then fail to install.'
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
