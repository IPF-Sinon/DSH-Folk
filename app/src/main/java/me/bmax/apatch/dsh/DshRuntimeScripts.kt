package me.bmax.apatch.dsh

import android.content.Context
import me.bmax.apatch.R

/**
 * 容器侧的脚本与文档（原来内嵌在 [DshRuntime] 里，约一千二百行）。
 *
 * 为什么搬出来：DshRuntime 是**编排** —— 启动、安装、提权、端口；而这几个是**纯内容**：
 * dsh-fs / dsh-saf / dsh-native / dsh-workflow 四段 CLI，加上 DSL 说明、样例与排障清单。
 * 内容改一次不该让编排文件再长几十行，也不该让「看一个启动顺序的改动」先滚过一千行 JS。
 *
 * 只搬不改：raw string 逐字搬过来（`${D}` 这类插值由本文件私有的 `D` 兜住），DshRuntime 里
 * 保留同名引用（`private val X get() = …`），所以调用点一个都没改。
 */
internal object DshRuntimeScripts {
    /** raw string 里的 `$` 转义入口：`${D}` 在 DSL 文档/样例里拼出字面量 `${`。 */
    private const val D = "$"

    /**
     * 容器内 `dsh-fs` CLI（node 脚本，读 /root/.dsh/fs-bridge.json 后回环调用文件桥）。
     *
     * 这是 Kotlin raw string：里面**不能出现 `${'$'}`**，否则会被当成模板插值。
     */
    val FS_BRIDGE_CLI_SCRIPT = """
        #!/usr/bin/env node
        const fs = require('fs');
        const http = require('http');
        const CFG = '/root/.dsh/fs-bridge.json';
        if (!fs.existsSync(CFG)) { console.error('dsh-fs: bridge config missing: ' + CFG); process.exit(1); }
        const cfg = JSON.parse(fs.readFileSync(CFG, 'utf8'));
        const enc = encodeURIComponent;
        function req(method, path, body) {
          return new Promise(function (resolve, reject) {
            const headers = { 'X-Dsh-Fs-Token': cfg.token };
            // Content-Length 必须显式给：只 r.write(body) 的话 Node 会改用
            // Transfer-Encoding: chunked，而宿主侧要求 Content-Length，直接 400。
            if (body) headers['Content-Length'] = Buffer.byteLength(body);
            const r = http.request({
              host: '127.0.0.1', port: cfg.port, method: method, path: path,
              headers: headers
            }, function (res) {
              const chunks = [];
              res.on('data', function (c) { chunks.push(c); });
              res.on('end', function () { resolve({ status: res.statusCode, body: Buffer.concat(chunks) }); });
            });
            r.on('error', reject);
            if (body) r.write(body);
            r.end();
          });
        }
        const argv = process.argv.slice(2);
        const cmd = argv[0];
        // --key value / --flag 从位置参数里剥出来，剩下的按顺序用
        const opt = {};
        const a = [];
        for (let i = 1; i < argv.length; i++) {
          const t = argv[i];
          if (t.slice(0, 2) === '--') {
            const eq = t.indexOf('=');
            if (eq > 2) { opt[t.slice(2, eq)] = t.slice(eq + 1); }
            else if (i + 1 < argv.length && argv[i + 1].slice(0, 2) !== '--') { opt[t.slice(2)] = argv[++i]; }
            else { opt[t.slice(2)] = '1'; }
          } else { a.push(t); }
        }
        function rel(p) { return p === undefined ? '' : p; }
        function q(pairs) {
          const out = [];
          for (const k in pairs) { if (pairs[k] !== undefined) out.push(k + '=' + enc(String(pairs[k]))); }
          return out.length ? '?' + out.join('&') : '';
        }
        function say(r) {
          process.stdout.write(r.body.toString() + '\n');
          if (r.status !== 200) process.exitCode = 1;
        }
        const USAGE = [
          'usage: dsh-fs <command> [args] [options]',
          '  list [path] [--recursive] [--maxDepth N] [--limit N]',
          '  stat <path>',
          '  read <path> [--offset N] [--length N]      # binary goes to stdout',
          '  write <localFile> [remotePath] [--append]',
          '  rm <path> [-r|--recursive]',
          '  mv <src> <dst>',
          '  cp <src> <dst> [--overwrite]',
          '  mkdir <path>',
          '  find <path> --glob <pattern> [--maxDepth N] [--limit N]',
          '  space [path]',
          '  health',
          'All paths are relative to the shared-storage root (/sdcard).'
        ].join('\n');
        (async function () {
          try {
            if (cmd === 'list') {
              say(await req('GET', '/list' + q({
                path: rel(a[0]), recursive: opt.recursive, maxDepth: opt.maxDepth, limit: opt.limit
              })));
            } else if (cmd === 'stat') {
              say(await req('GET', '/stat' + q({ path: rel(a[0]) })));
            } else if (cmd === 'health') {
              say(await req('GET', '/health'));
            } else if (cmd === 'space') {
              say(await req('GET', '/space' + q({ path: rel(a[0]) })));
            } else if (cmd === 'read') {
              const r = await req('GET', '/read' + q({
                path: rel(a[0]), offset: opt.offset, length: opt.length
              }));
              if (r.status === 200) process.stdout.write(r.body);
              else { process.stderr.write(r.body.toString() + '\n'); process.exitCode = 1; }
            } else if (cmd === 'write') {
              if (!a[0]) { console.error(USAGE); process.exit(1); }
              const data = fs.readFileSync(a[0]);
              const dst = a.length > 1 ? a[1] : a[0];
              say(await req('PUT', '/write' + q({ path: dst, append: opt.append }), data));
            } else if (cmd === 'rm') {
              // -r 是 unix 习惯，不带 -- 所以落在位置参数里，得手动挑出来
              const recursive = (opt.recursive || opt.r || a.indexOf('-r') >= 0) ? '1' : undefined;
              const path = a.filter(function (x) { return x !== '-r'; })[0];
              say(await req('DELETE', '/delete' + q({ path: rel(path), recursive: recursive })));
            } else if (cmd === 'mv') {
              say(await req('POST', '/move' + q({ src: rel(a[0]), dst: rel(a[1]) })));
            } else if (cmd === 'cp') {
              say(await req('POST', '/copy' + q({
                src: rel(a[0]), dst: rel(a[1]), overwrite: opt.overwrite
              })));
            } else if (cmd === 'mkdir') {
              say(await req('POST', '/mkdir' + q({ path: rel(a[0]) })));
            } else if (cmd === 'find') {
              if (!opt.glob) { console.error(USAGE); process.exit(1); }
              say(await req('GET', '/find' + q({
                path: rel(a[0]), glob: opt.glob, maxDepth: opt.maxDepth, limit: opt.limit
              })));
            } else {
              console.error(USAGE);
              process.exitCode = 1;
            }
          } catch (e) {
            console.error('dsh-fs: ' + (e && e.message ? e.message : e));
            process.exitCode = 1;
          }
        })();
    """.trimIndent()

    /**
     * 容器内 `dsh-saf` CLI（node 脚本，读 /root/.dsh/fs-bridge.json 后回环调用 SAF 桥的 `/saf/…`）。
     *
     * 与 `dsh-fs` 共用同一个回环端口与 token（同一份 fs-bridge.json），只是 path 前缀是 `/saf/`。
     * 它针对的是**挂不上**的那一类提供器（典型 Termux）：宿主 App 用 ContentResolver /
     * DocumentsContract 代办，不走真实路径、不复制整树。写操作（write/mkdir/rm/mv/cp）在宿主侧
     * 受 SAF 能力的读写档 gate。
     *
     * 同样是 Kotlin raw string：里面**不能出现 `${'$'}`**，字符串字面量里也**不能有 CJK**。
     */
    val SAF_BRIDGE_CLI_SCRIPT = """
        #!/usr/bin/env node
        const fs = require('fs');
        const http = require('http');
        const CFG = '/root/.dsh/fs-bridge.json';
        if (!fs.existsSync(CFG)) { console.error('dsh-saf: bridge config missing: ' + CFG); process.exit(1); }
        const cfg = JSON.parse(fs.readFileSync(CFG, 'utf8'));
        const enc = encodeURIComponent;
        function req(method, path, body) {
          return new Promise(function (resolve, reject) {
            const headers = { 'X-Dsh-Fs-Token': cfg.token };
            if (body) headers['Content-Length'] = Buffer.byteLength(body);
            const r = http.request({
              host: '127.0.0.1', port: cfg.port, method: method, path: path,
              headers: headers
            }, function (res) {
              const chunks = [];
              res.on('data', function (c) { chunks.push(c); });
              res.on('end', function () { resolve({ status: res.statusCode, body: Buffer.concat(chunks) }); });
            });
            r.on('error', reject);
            if (body) r.write(body);
            r.end();
          });
        }
        const argv = process.argv.slice(2);
        const cmd = argv[0];
        const opt = {};
        const a = [];
        for (let i = 1; i < argv.length; i++) {
          const t = argv[i];
          if (t.slice(0, 2) === '--') {
            const eq = t.indexOf('=');
            if (eq > 2) { opt[t.slice(2, eq)] = t.slice(eq + 1); }
            else if (i + 1 < argv.length && argv[i + 1].slice(0, 2) !== '--') { opt[t.slice(2)] = argv[++i]; }
            else { opt[t.slice(2)] = '1'; }
          } else { a.push(t); }
        }
        function rel(p) { return p === undefined ? '' : p; }
        function q(pairs) {
          const out = [];
          for (const k in pairs) { if (pairs[k] !== undefined) out.push(k + '=' + enc(String(pairs[k]))); }
          return out.length ? '?' + out.join('&') : '';
        }
        function say(r) {
          process.stdout.write(r.body.toString() + '\n');
          if (r.status !== 200) process.exitCode = 1;
        }
        const USAGE = [
          'usage: dsh-saf <command> [args] [options]',
          '  sources                                    # list registered SAF sources',
          '  list <src> [path] [--limit N]',
          '  stat <src> <path>',
          '  read <src> <path> [--offset N] [--length N]   # binary goes to stdout',
          '  write <localFile> <src> <path> [--append]',
          '  mkdir <src> <path>',
          '  rm <src> <path>',
          '  mv <src> <from> <to>',
          '  cp <src> <from> <to>',
          'A SAF source is a tree you authorised in the app that cannot be mounted (e.g. Termux).',
          'Paths are relative to that tree root; the host resolves them segment by segment.'
        ].join('\n');
        (async function () {
          try {
            if (cmd === 'sources') {
              say(await req('GET', '/saf/sources'));
            } else if (cmd === 'list') {
              if (!a[0]) { console.error(USAGE); process.exit(1); }
              say(await req('GET', '/saf/list' + q({ src: a[0], path: rel(a[1]), limit: opt.limit })));
            } else if (cmd === 'stat') {
              if (!a[0] || a[1] === undefined) { console.error(USAGE); process.exit(1); }
              say(await req('GET', '/saf/stat' + q({ src: a[0], path: a[1] })));
            } else if (cmd === 'read') {
              if (!a[0] || a[1] === undefined) { console.error(USAGE); process.exit(1); }
              const r = await req('GET', '/saf/read' + q({
                src: a[0], path: a[1], offset: opt.offset, length: opt.length
              }));
              if (r.status === 200) process.stdout.write(r.body);
              else { process.stderr.write(r.body.toString() + '\n'); process.exitCode = 1; }
            } else if (cmd === 'write') {
              if (!a[0] || !a[1] || a[2] === undefined) { console.error(USAGE); process.exit(1); }
              const data = fs.readFileSync(a[0]);
              say(await req('PUT', '/saf/write' + q({ src: a[1], path: a[2], append: opt.append }), data));
            } else if (cmd === 'mkdir') {
              if (!a[0] || a[1] === undefined) { console.error(USAGE); process.exit(1); }
              say(await req('POST', '/saf/mkdir' + q({ src: a[0], path: a[1] })));
            } else if (cmd === 'rm') {
              if (!a[0] || a[1] === undefined) { console.error(USAGE); process.exit(1); }
              say(await req('DELETE', '/saf/delete' + q({ src: a[0], path: a[1] })));
            } else if (cmd === 'mv') {
              if (!a[0] || a[1] === undefined || a[2] === undefined) { console.error(USAGE); process.exit(1); }
              say(await req('POST', '/saf/move' + q({ src: a[0], from: a[1], to: a[2] })));
            } else if (cmd === 'cp') {
              if (!a[0] || a[1] === undefined || a[2] === undefined) { console.error(USAGE); process.exit(1); }
              say(await req('POST', '/saf/copy' + q({ src: a[0], from: a[1], to: a[2] })));
            } else {
              console.error(USAGE);
              process.exitCode = 1;
            }
          } catch (e) {
            console.error('dsh-saf: ' + (e && e.message ? e.message : e));
            process.exitCode = 1;
          }
        })();
    """.trimIndent()

    /**
     * 容器内 `dsh-native` CLI：调宿主的原生能力（通知/振动/toast/剪贴板/分享/设备信息）。
     *
     * 与 `dsh-fs` 共用同一个回环端口与 token（同一份 fs-bridge.json）。失败时把宿主返回的
     * `reason` 打到 stderr 并以非 0 退出，agent 能据此区分「没启用」「没权限」「不在前台」。
     *
     * 同样是 raw string：里面**不能出现 `${'$'}`**。
     */
    val NATIVE_CLI_SCRIPT = """
        #!/usr/bin/env node
        const fs = require('fs');
        const http = require('http');
        const CFG = '/root/.dsh/fs-bridge.json';
        if (!fs.existsSync(CFG)) { console.error('dsh-native: bridge config missing: ' + CFG); process.exit(1); }
        const cfg = JSON.parse(fs.readFileSync(CFG, 'utf8'));
        const enc = encodeURIComponent;
        function req(method, path) {
          return new Promise(function (resolve, reject) {
            const r = http.request({
              host: '127.0.0.1', port: cfg.port, method: method, path: path,
              headers: { 'X-Dsh-Fs-Token': cfg.token }
            }, function (res) {
              const chunks = [];
              res.on('data', function (c) { chunks.push(c); });
              res.on('end', function () { resolve({ status: res.statusCode, body: Buffer.concat(chunks) }); });
            });
            r.on('error', reject);
            r.end();
          });
        }
        const argv = process.argv.slice(2);
        const cmd = argv[0];
        const opt = {};
        const a = [];
        // 无值开关：不给它们「吃掉下一个 token」的机会。'shell --su getprop ro.x' 里的 --su
        // 后面跟的是要执行的命令，按「带值选项」解析会把 getprop 当成 --su 的值，命令就没了。
        const FLAGS = { su: 1, ongoing: 1, all: 1 };
        for (let i = 1; i < argv.length; i++) {
          const t = argv[i];
          // '--' 之后一律当参数：被执行的命令自己常带 --flag（'pm list packages --user 0'），
          // 不划这条线的话那些 flag 会被当成 dsh-native 的选项从命令里消失
          if (t === '--') { for (let j = i + 1; j < argv.length; j++) a.push(argv[j]); break; }
          if (t.slice(0, 2) === '--') {
            const eq = t.indexOf('=');
            if (eq > 2) { opt[t.slice(2, eq)] = t.slice(eq + 1); }
            else if (FLAGS[t.slice(2)]) { opt[t.slice(2)] = '1'; }
            else if (i + 1 < argv.length && argv[i + 1].slice(0, 2) !== '--') { opt[t.slice(2)] = argv[++i]; }
            else { opt[t.slice(2)] = '1'; }
          } else { a.push(t); }
        }
        function q(pairs) {
          if (cmd !== 'caps' && pairs.reason === undefined) pairs.reason = opt.reason;
          const out = [];
          for (const k in pairs) { if (pairs[k] !== undefined) out.push(k + '=' + enc(String(pairs[k]))); }
          return out.length ? '?' + out.join('&') : '';
        }
        function say(r) {
          const text = r.body.toString();
          if (r.status >= 200 && r.status < 300) { process.stdout.write(text + '\n'); return; }
          process.stderr.write(text + '\n');
          process.exitCode = 1;
        }
        const USAGE = [
          'usage: dsh-native <command> [args] --reason <why> [options]',
          '  Every capability call requires a concrete --reason for the audit log.',
          '  notify <title> [body] [--id N] [--ongoing]',
          '  notify-cancel [--id N]',
          '  notify-list [--limit N]',
          '  notify-dismiss <key>|--all                 # needs notification full control',
          '  notify-full-screen <title> [body]            # urgent full-screen alert',
          '  toast <text>',
          '  vibrate [--ms N] [--amplitude 1..255]',
          '  torch <on|off>                             # camera flash as a flashlight',
          '  clip get | clip set <text> [--label L]',
          '  share <text> [--title T]',
          '  open <https URL>',
          '  dial <number>                              # fills the dialer; user presses call',
          '  device',
          '  troubleshoot                              # privilege/bridge errors: what to try next',
          '  media list [--type image|video|audio] [--q name] [--limit N]',
          '  media get <id> [--type image|video|audio]   # lands in /tmp; JSON carries path',
          '  mic record [--ms N]                         # 30000 max; lands in /tmp',
          '  mic start                                   # returns an id; record without a fixed length',
          '  mic stop --id ID                            # graceful: writes the mp4 index, lands in /tmp',
          '  camera photo [--facing back|front] [--max N]  # no preview; lands in /tmp',
          '  tts say <text> [--lang zh-CN] [--rate 0.1..3] [--pitch 0.5..2]',
          '  tts file <text> [--lang L] [--rate R] [--pitch P]   # wav lands in /tmp',
          '  tts voices                                 # which languages this device can read',
          '  calendar list [--days N] [--limit N]',
          '  calendar add <title> --start <epochMs> [--minutes N] [--end <epochMs>]',
          '                [--location L] [--description D]',
          '  contacts list [--q name-or-number] [--limit N]',
          '  location [--maxAge ms] [--wait ms]         # cached fix first, then a live one',
          '  phone                                      # carrier / network type / SIM / call state',
          '  sensors list',
          '  sensors read <id>                          # one sample, e.g. light or accelerometer',
          '  network                                    # transport / validated / metered / wifi',
          '  volume                                     # read every stream',
          '  volume set <0..100> [--stream music|ring|alarm|notification|call|system]',
          '  ringer <normal|vibrate|silent>             # needs Do Not Disturb access',
          '  settings                                   # brightness / timeout / auto-rotate',
          '  settings brightness <1..100> [--auto 0|1]  # needs Modify system settings',
          '  settings timeout <ms>',
          '  settings rotation <0|1>',
          '  install                                    # may this device install unknown apps?',
          '  usage list [--days N] [--limit N]           # recent app foreground usage',
          '  sms list [--limit N]                        # recent SMS, read only',
          '  sms send <number> <text>                    # requires send access',
          '  shell [--su] [--timeout ms] [--] <command...>  # run through the privileged channel',
          '  a11y tree [--depth N] [--max N]            # read the current screen as a node tree',
          '  a11y click <text-or-id> [--class C] [--index N]',
          '  a11y tap <x> <y> [--ms N]',
          '  a11y swipe <x1> <y1> <x2> <y2> [--ms N]',
          '  a11y text <text> [--target <text-or-id>] [--class C] [--index N]   (empty text clears the field)',
          '  a11y global <back|home|recents|notifications|quick_settings|lock_screen|power_dialog>',
          '  a11y screenshot                            # capture the screen; lands in /tmp, JSON carries path',
          '  display status                             # is the virtual-screen service up, which display is the session',
          '  display session [--width N --height N --dpi N --bitrate K]   # create a virtual screen; session = its id',
          '  display shot [--display N]                 # PNG of display N (0 = the real screen) into /tmp; JSON carries path+size',
          '  display tap <x> <y> [--display N]          # tap. Coordinates come from the shot you just took',
          '  display swipe <x1> <y1> <x2> <y2> [--ms N] [--display N]',
          '  display key <home|back|enter|...|keycode> [--display N]',
          '  display launch <package> [--display N]     # start an app on the virtual screen (not on the real one)',
          '  display stop                               # shut the service down now (the app keeps it alive while it runs)',
          '      Put -- before the command if it contains its own --flags.',
          '  caps                                       # access, accessOptions, once, pending, lastElevation',
          '  elevate <cap> <read|write|read_write|control> --reason <why> [--command <cmd>]',
          '      Only if you want the level raised without running anything. Never auto-grants.',
          '      Handy when you know you will need it later; for a single call just make that call.',
          'No access yet? Do NOT file a request first: just make the capability call. It BLOCKS while',
          'the user is asked in the DSH-Folk app (Allow / Allow once / Deny; ${DshElevationRequests.TTL_MS / 1000}s to',
          'answer, silence counts as a deny), then either RUNS and returns the real result, or fails with',
          'reason denied_by_user / request_expired. If the user allowed it but Android itself has not',
          'granted the permission, you get no_android_permission after the user is told how to fix it:',
          'say which permission is missing and stop, do not retry in a loop.',
          '"Allow once" buys exactly one call of that capability, valid for ${DshNativeBridge.ONCE_TTL_MS / 1000}s.',
          'Only one request may be pending at a time (409 request_pending). A capability call only asks',
          'while the app is in the foreground; otherwise it fails with 409 not_foreground.',
          'Settings > Features > Native capabilities: enable the master switch and the item first.'
        ].join('\n');
        (async function () {
          try {
            // 先处理"没给命令"：光敲 `dsh-native` 的人要的是用法，不是关于 --reason 的抱怨。
            // 原来 --reason 的检查在最前面，于是这一句会把用法提示顶掉。
            if (!cmd) { console.error(USAGE); process.exit(1); }
            if (cmd !== 'caps' && !opt.reason) {
              console.error('dsh-native: --reason is required for every capability call');
              // 顺手把用法也打出来：忘了 --reason 的人多半也还没记住这个命令的形状
              console.error(USAGE);
              process.exit(1);
            }
            if (cmd === 'caps') {
              say(await req('GET', '/native/capabilities'));
            } else if (cmd === 'elevate') {
              if (!a[0] || !a[1]) { console.error(USAGE); process.exit(1); }
              // --command 是这条命令的全部意义所在：用户要判断的不是「camera=write 要不要给」，
              // 而是「它接下来到底要做什么」。没给就退回自己的调用行，弹窗至少有东西可看。
              const res = await req('POST', '/native/elevate' + q({
                cap: a[0], access: a[1], reason: opt.reason,
                command: opt.command, invocation: 'dsh-native ' + argv.join(' ')
              }));
              say(res);
              // 这条命令会一直等到用户在 App 里答复（允许 / 仅本次 / 拒绝 / 超时），stdout 上的
              // JSON 就是结论本身。下面两句是给读终端的人（和只会看退出码的调用方）的。
              if (res.status === 403) {
                console.error('elevate: the user did not grant it (status in the JSON: ' +
                  'denied_by_user or request_expired). Do not file another request for the same thing.');
              } else if (res.status === 409) {
                console.error('elevate: refused (a request is already pending, or the app is not in ' +
                  'the foreground). Run "dsh-native caps" and read "pending" / "foreground" instead ' +
                  'of filing another.');
              }
            } else if (cmd === 'shell') {
              // 命令可能带引号/管道，所以收成一段原文而不是拼参数：shell 的语义就是把这段
              // 东西原样交给通道，宿主不解析它（审计与弹窗里显示的也是这段原文）
              const line = a.join(' ');
              if (!line) { console.error(USAGE); process.exit(1); }
              // 命令自己带的 --flag 会被上面的解析器当成本命令的选项吃掉（'pm list packages --user 0'
              // 里的 --user）。丢参数比报错危险得多：命令照样跑，但跑的不是 agent 以为的那条。
              // 认不出来的选项一律拒绝，并告诉它怎么改。
              const KNOWN = { su: 1, timeout: 1, reason: 1 };
              const unknown = Object.keys(opt).filter(function (k) { return !KNOWN[k]; });
              if (unknown.length) {
                console.error('dsh-native: ' + unknown.map(function (k) { return '--' + k; }).join(' ') +
                  ' is not a dsh-native option — it looks like part of the command.');
                console.error('Quote the whole command as one argument, or put -- before it:');
                console.error('  dsh-native shell --reason "<why>" -- <the command, as you would type it>');
                process.exit(1);
              }
              const res = await req('POST', '/native/shell' + q({
                cmd: line, su: opt.su, timeout: opt.timeout
              }));
              say(res);
              if (res.status === 403) {
                console.error('shell: the channel refused it. Read "reason" in the JSON (no_channel,' +
                  ' adb_write_disabled, root_unavailable ...): those are states to fix in the app,' +
                  ' not errors to retry.');
              } else if (res.status === 504) {
                console.error('shell: timed out and was dropped. Retry with a larger --timeout only if' +
                  ' the command is genuinely long; do not loop.');
              }
            } else if (cmd === 'a11y') {
              const act = a[0];
              if (act === 'tree') {
                say(await req('GET', '/native/a11y/tree' + q({ depth: opt.depth, max: opt.max })));
              } else if (act === 'click' && a[1]) {
                say(await req('POST', '/native/a11y/click' + q({
                  target: a[1], class: opt.class, index: opt.index
                })));
              } else if (act === 'tap' && a[1] && a[2]) {
                say(await req('POST', '/native/a11y/tap' + q({ x: a[1], y: a[2], ms: opt.ms })));
              } else if (act === 'swipe' && a[1] && a[2] && a[3] && a[4]) {
                say(await req('POST', '/native/a11y/swipe' + q({
                  x1: a[1], y1: a[2], x2: a[3], y2: a[4], ms: opt.ms
                })));
              } else if (act === 'text' && a.length > 1) {
                // a[1] 是"要写进去的字"，--target / --class 是"往哪儿写"。class 这一路
                // 是给 WebView 里的编辑框留的：它们常常既没有 text 也没有 view id。
                //
                // 判据是 `a.length > 1` 而不是 `a[1]`：**空串是合法输入 = 清空这个输入框**。
                // 用真值判断会把 'a11y text ""' 判成"参数不全"、只回一句 usage —— 于是
                // "清空输入框"这件事只能靠找到并点上那个清除按钮，而它往往没有节点名。
                say(await req('POST', '/native/a11y/text' + q({
                  text: a[1], target: opt.target, class: opt.class, index: opt.index
                })));
              } else if (act === 'global' && a[1]) {
                say(await req('POST', '/native/a11y/global' + q({ action: a[1] })));
              } else if (act === 'screenshot') {
                say(await req('GET', '/native/a11y/screenshot' + q({})));
              } else {
                console.error(USAGE);
                process.exitCode = 1;
              }
            } else if (cmd === 'display') {
              // 坐标 0 是合法值，所以这里一律用 !== undefined 判定，不能照抄 a11y 那几行的
              // 'a[1] && a[2]' —— 那会把 'tap 0 100' 判成参数不全。
              const act = a[0];
              const num = (v) => v !== undefined && v !== '';
              if (act === 'status') {
                say(await req('GET', '/native/display/status' + q({})));
              } else if (act === 'session') {
                say(await req('POST', '/native/display/session' + q({
                  width: opt.width, height: opt.height, dpi: opt.dpi, bitrate: opt.bitrate
                })));
              } else if (act === 'shot' || act === 'screenshot') {
                say(await req('POST', '/native/display/screenshot' + q({ display: opt.display })));
              } else if (act === 'tap' && num(a[1]) && num(a[2])) {
                say(await req('POST', '/native/display/tap' + q({
                  x: a[1], y: a[2], display: opt.display
                })));
              } else if (act === 'swipe' && num(a[1]) && num(a[2]) && num(a[3]) && num(a[4])) {
                say(await req('POST', '/native/display/swipe' + q({
                  x1: a[1], y1: a[2], x2: a[3], y2: a[4], duration: opt.ms, display: opt.display
                })));
              } else if (act === 'key' && a[1]) {
                say(await req('POST', '/native/display/key' + q({ key: a[1], display: opt.display })));
              } else if (act === 'launch' && a[1]) {
                say(await req('POST', '/native/display/launch' + q({
                  package: a[1], display: opt.display
                })));
              } else if (act === 'stop') {
                say(await req('POST', '/native/display/stop' + q({})));
              } else {
                console.error(USAGE);
                process.exitCode = 1;
              }
            } else if (cmd === 'troubleshoot') {
              // 这份清单由 App 按应用内语言写进 /root/.dsh（见 nativeTroubleshootDoc），
              // 不是写死在这里：本脚本的字符串字面量不许有 CJK。
              const doc = '/root/.dsh/dsh-native-troubleshoot.txt';
              try { process.stdout.write(fs.readFileSync(doc, 'utf8')); }
              catch (e) { process.stderr.write('troubleshoot: cannot read ' + doc + '\n'); process.exitCode = 1; }
            } else if (cmd === 'device') {
              say(await req('GET', '/native/device' + q({})));
            } else if (cmd === 'notify') {
              if (!a[0]) { console.error(USAGE); process.exit(1); }
              say(await req('POST', '/native/notify' + q({
                title: a[0], body: a[1], id: opt.id, ongoing: opt.ongoing
              })));
            } else if (cmd === 'notify-cancel') {
              say(await req('DELETE', '/native/notify' + q({ id: opt.id })));
            } else if (cmd === 'notify-list') {
              say(await req('GET', '/native/notify/list' + q({ limit: opt.limit })));
            } else if (cmd === 'notify-dismiss') {
              if (!a[0] && !opt.all) { console.error(USAGE); process.exit(1); }
              say(await req('DELETE', '/native/notify/system' + q({ key: a[0], all: opt.all })));
            } else if (cmd === 'notify-full-screen') {
              if (!a[0]) { console.error(USAGE); process.exit(1); }
              say(await req('POST', '/native/notify/full-screen' + q({ title: a[0], body: a[1] })));
            } else if (cmd === 'toast') {
              if (!a[0]) { console.error(USAGE); process.exit(1); }
              say(await req('POST', '/native/toast' + q({ text: a[0] })));
            } else if (cmd === 'vibrate') {
              say(await req('POST', '/native/vibrate' + q({ ms: opt.ms, amplitude: opt.amplitude })));
            } else if (cmd === 'torch') {
              if (a[0] !== 'on' && a[0] !== 'off') { console.error(USAGE); process.exit(1); }
              say(await req('POST', '/native/torch' + q({ state: a[0] })));
            } else if (cmd === 'clip') {
              if (a[0] === 'get') {
                say(await req('GET', '/native/clipboard' + q({})));
              } else if (a[0] === 'set' && a[1]) {
                say(await req('POST', '/native/clipboard' + q({ text: a[1], label: opt.label })));
              } else {
                console.error(USAGE);
                process.exitCode = 1;
              }
            } else if (cmd === 'media') {
              if (a[0] === 'list') {
                say(await req('GET', '/native/media/list' + q({
                  type: opt.type, q: opt.q, limit: opt.limit
                })));
              } else if (a[0] === 'get' && a[1]) {
                say(await req('GET', '/native/media/read' + q({ type: opt.type, id: a[1] })));
              } else {
                console.error(USAGE);
                process.exitCode = 1;
              }
            } else if (cmd === 'mic') {
              if (a[0] === 'record') {
                say(await req('POST', '/native/mic/record' + q({ ms: opt.ms })));
              } else if (a[0] === 'start') {
                say(await req('POST', '/native/mic/start' + q({})));
              } else if (a[0] === 'stop' && opt.id) {
                say(await req('POST', '/native/mic/stop' + q({ id: opt.id })));
              } else {
                console.error(USAGE);
                process.exitCode = 1;
              }
            } else if (cmd === 'share') {
              if (!a[0]) { console.error(USAGE); process.exit(1); }
              say(await req('POST', '/native/share' + q({ text: a[0], title: opt.title })));
            } else if (cmd === 'open') {
              if (!a[0]) { console.error(USAGE); process.exit(1); }
              say(await req('POST', '/native/open' + q({ url: a[0] })));
            } else if (cmd === 'dial') {
              if (!a[0]) { console.error(USAGE); process.exit(1); }
              say(await req('POST', '/native/dial' + q({ number: a[0] })));
            } else if (cmd === 'camera') {
              if (a[0] === 'photo') {
                say(await req('POST', '/native/camera/photo' + q({
                  facing: opt.facing, max: opt.max
                })));
              } else { console.error(USAGE); process.exitCode = 1; }
            } else if (cmd === 'tts') {
              if (a[0] === 'say' && a[1]) {
                say(await req('POST', '/native/tts/speak' + q({
                  text: a[1], lang: opt.lang, rate: opt.rate, pitch: opt.pitch
                })));
              } else if (a[0] === 'file' && a[1]) {
                say(await req('POST', '/native/tts/file' + q({
                  text: a[1], lang: opt.lang, rate: opt.rate, pitch: opt.pitch
                })));
              } else if (a[0] === 'voices') {
                say(await req('GET', '/native/tts/voices' + q({})));
              } else { console.error(USAGE); process.exitCode = 1; }
            } else if (cmd === 'calendar') {
              if (a[0] === 'list') {
                say(await req('GET', '/native/calendar/list' + q({
                  days: opt.days, limit: opt.limit
                })));
              } else if (a[0] === 'add' && a[1]) {
                say(await req('POST', '/native/calendar/create' + q({
                  title: a[1], start: opt.start, end: opt.end, minutes: opt.minutes,
                  location: opt.location, description: opt.description
                })));
              } else { console.error(USAGE); process.exitCode = 1; }
            } else if (cmd === 'contacts') {
              if (a[0] === 'list') {
                say(await req('GET', '/native/contacts/list' + q({
                  q: opt.q, limit: opt.limit
                })));
              } else { console.error(USAGE); process.exitCode = 1; }
            } else if (cmd === 'location') {
              say(await req('GET', '/native/location' + q({
                maxAge: opt.maxAge, wait: opt.wait
              })));
            } else if (cmd === 'phone') {
              say(await req('GET', '/native/phone/info' + q({})));
            } else if (cmd === 'sensors') {
              if (a[0] === 'list') {
                say(await req('GET', '/native/sensors/list' + q({})));
              } else if (a[0] === 'read' && a[1]) {
                say(await req('GET', '/native/sensors/read' + q({ id: a[1] })));
              } else { console.error(USAGE); process.exitCode = 1; }
            } else if (cmd === 'network') {
              say(await req('GET', '/native/network' + q({})));
            } else if (cmd === 'volume') {
              if (!a[0]) {
                say(await req('GET', '/native/volume' + q({})));
              } else if (a[0] === 'set' && a[1] !== undefined) {
                say(await req('POST', '/native/volume' + q({
                  percent: a[1], stream: opt.stream
                })));
              } else { console.error(USAGE); process.exitCode = 1; }
            } else if (cmd === 'ringer') {
              if (!a[0]) { console.error(USAGE); process.exit(1); }
              say(await req('POST', '/native/ringer' + q({ mode: a[0] })));
            } else if (cmd === 'settings') {
              if (!a[0]) {
                say(await req('GET', '/native/settings' + q({})));
              } else if (a[0] === 'brightness') {
                say(await req('POST', '/native/settings/brightness' + q({
                  percent: a[1], auto: opt.auto
                })));
              } else if (a[0] === 'timeout' && a[1]) {
                say(await req('POST', '/native/settings/timeout' + q({ ms: a[1] })));
              } else if (a[0] === 'rotation' && a[1] !== undefined) {
                say(await req('POST', '/native/settings/rotation' + q({ on: a[1] })));
              } else { console.error(USAGE); process.exitCode = 1; }
            } else if (cmd === 'install') {
              say(await req('GET', '/native/install' + q({})));
            } else if (cmd === 'usage') {
              if (a[0] === 'list') {
                say(await req('GET', '/native/usage/list' + q({ days: opt.days, limit: opt.limit })));
              } else { console.error(USAGE); process.exitCode = 1; }
            } else if (cmd === 'sms') {
              if (a[0] === 'list') {
                say(await req('GET', '/native/sms/list' + q({ limit: opt.limit })));
              } else if (a[0] === 'send' && a[1] && a[2]) {
                say(await req('POST', '/native/sms/send' + q({ to: a[1], body: a[2] })));
              } else { console.error(USAGE); process.exitCode = 1; }
            } else {
              console.error(USAGE);
              process.exitCode = 1;
            }
          } catch (e) {
            console.error('dsh-native: ' + (e && e.message ? e.message : e));
            process.exitCode = 1;
          }
        })();
    """.trimIndent()

    /**
     * 容器里的 `dsh-workflow`：建流程 / 跑流程 / 查状态 / 取消。
     *
     * 与 `dsh-fs` / `dsh-native` / `dsh-saf` 完全同一条路（同一个回环端口与 token），所以
     * 容器里任何能跑命令的东西都能用它 —— 包括**没有**注册成工具的那些运行时（工具插件注册
     * 失败时这条路就是唯一的入口，见 assets/dsh-folk-workflow.mjs 的三级降级）。
     *
     * 字面量必须保持纯 ASCII：`check-native-cli` 门禁会拒掉带 CJK 的脚本（容器里 locale 可能
     * 是 POSIX，中文提示会变成乱码；而 agent 读英文 DSL 文档没有问题）。
     */
    val WORKFLOW_CLI_SCRIPT = """
        #!/usr/bin/env node
        const fs = require('fs');
        const http = require('http');
        const CFG = '/root/.dsh/fs-bridge.json';
        if (!fs.existsSync(CFG)) { console.error('dsh-workflow: bridge config missing: ' + CFG); process.exit(1); }
        const cfg = JSON.parse(fs.readFileSync(CFG, 'utf8'));
        const USAGE = [
          'dsh-workflow <command> [options]',
          '',
          '  guide                               print the full guide (DSL + worked example)',
          '  samples                             list sample definitions under /root/.dsh/workflow-samples',
          '  sample <name>                       print one sample definition',
          '  list                                list saved workflows',
          '  status [--run <runId>] [--workflow <id>] [--from <line>] [--no-logs]',
          '                                      where a run is now + results of finished steps',
          '  create --file <path|-> [--reason <text>] [--name <text>]',
          '  create --sample <name> [--reason <text>] [--name <text>]',
          '  create --json <json>   [--reason <text>] [--name <text>]',
          '                                      ask the user (yellow dialog) and save a workflow',
          '  run --workflow <id>    [--wait] [--reason <text>]',
          '  run --file <path|->    [--wait] [--reason <text>]',
          '  run --sample <name>    [--wait] [--reason <text>]',
          '                                      ask the user (red dialog) and run it;',
          '                                      without --wait the run continues in background',
          '  cancel --run <runId>   [--reason <text>]',
          '',
          'The user sees a flow-chart preview before answering; if they edit the definition the',
          'response carries a diff. Every option is required to name a --reason.'
        ].join('\n');
        function req(method, path, body) {
          return new Promise(function (resolve, reject) {
            const headers = { 'X-Dsh-Fs-Token': cfg.token };
            // Content-Length must be explicit (same as dsh-fs): write(body) alone goes chunked and the host answers 400.
            if (body) headers['Content-Length'] = Buffer.byteLength(body);
            const r = http.request({
              host: '127.0.0.1', port: cfg.port, method: method, path: path, headers: headers,
              // agent:false = do not reuse connections. One command makes one request; keep-alive
              // only leaves the process hanging on an open socket after the response arrives.
              agent: false
            }, function (res) {
              const chunks = [];
              res.on('data', function (c) { chunks.push(c); });
              res.on('end', function () { resolve({ status: res.statusCode, body: Buffer.concat(chunks).toString() }); });
            });
            r.on('error', reject);
            if (body) r.write(body);
            r.end();
          });
        }
        const argv = process.argv.slice(2);
        const cmd = argv[0];
        const opt = {};
        const a = [];
        for (let i = 1; i < argv.length; i++) {
          const t = argv[i];
          if (t.slice(0, 2) === '--') {
            const eq = t.indexOf('=');
            if (eq > 2) { opt[t.slice(2, eq)] = t.slice(eq + 1); }
            else if (i + 1 < argv.length && argv[i + 1].slice(0, 2) !== '--') { opt[t.slice(2)] = argv[++i]; }
            else { opt[t.slice(2)] = '1'; }
          } else { a.push(t); }
        }
        function readDef() {
          // --sample <name>: take a checked definition from the sample directory. The most common
          // first move for an agent is "copy a sample and edit it", so it should not have to
          // remember where the sample files live.
          if (opt.sample) {
            const p = '/root/.dsh/workflow-samples/' + String(opt.sample).replace(/\.json$/, '') + '.json';
            try { return fs.readFileSync(p, 'utf8'); } catch (e) {
              console.error('dsh-workflow: no such sample: ' + opt.sample + ' (see `dsh-workflow samples`)');
              process.exit(1);
            }
          }
          if (opt.json) return opt.json;
          const p = opt.file || a[0];
          if (!p) { console.error('dsh-workflow: need --file <path|-> or --json <json>'); process.exit(1); }
          return p === '-' ? fs.readFileSync(0, 'utf8') : fs.readFileSync(p, 'utf8');
        }
        function say(r) {
          process.stdout.write(r.body + '\n');
          if (r.status < 200 || r.status >= 300) {
            // When the validator rejects a definition, the most useful next step is the full format.
            // Put that entry point right next to the error instead of hoping the agent remembers to
            // look it up (measured: it does not).
            if (r.body.indexOf('invalid_definition') >= 0) {
              console.error('dsh-workflow: that definition was rejected. `dsh-workflow guide` prints the');
              console.error('dsh-workflow: full format; `dsh-workflow samples` lists ready-made ones, and');
              console.error('dsh-workflow: `--sample <name>` creates straight from one.');
            }
            process.exit(1);
          }
        }
        (async function () {
          try {
            if (!cmd) { console.error(USAGE); process.exit(1); }
            if (cmd === 'guide' || cmd === 'help' || cmd === '--help' || cmd === '-h') {
              // The guide is written into the container by the host app, so it always matches the
              // validator that will actually judge the definition. Print USAGE if it is missing.
              try {
                process.stdout.write(fs.readFileSync('/root/.dsh/workflow-dsl.md', 'utf8'));
              } catch (e) {
                console.error(USAGE);
                process.exit(1);
              }
            } else if (cmd === 'samples') {
              const dir = '/root/.dsh/workflow-samples';
              let names = [];
              try {
                names = fs.readdirSync(dir).filter(function (n) { return /\.json$/.test(n); }).sort();
              } catch (e) { names = []; }
              if (!names.length) {
                console.error('dsh-workflow: no samples in ' + dir);
                process.exit(1);
              }
              names.forEach(function (n) { console.log(dir + '/' + n); });
            } else if (cmd === 'sample') {
              const n = a[0] || opt.name;
              if (!n) { console.error('dsh-workflow: sample needs a name (see `dsh-workflow samples`)'); process.exit(1); }
              const p = '/root/.dsh/workflow-samples/' + String(n).replace(/\.json$/, '') + '.json';
              try { process.stdout.write(fs.readFileSync(p, 'utf8')); } catch (e) {
                console.error('dsh-workflow: no such sample: ' + n);
                process.exit(1);
              }
            } else if (cmd === 'list') {
              say(await req('GET', '/workflow/list', null));
            } else if (cmd === 'status') {
              const qs = [];
              if (opt.run) qs.push('runId=' + encodeURIComponent(opt.run));
              if (opt.workflow) qs.push('workflowId=' + encodeURIComponent(opt.workflow));
              if (opt.from) qs.push('fromLine=' + encodeURIComponent(opt.from));
              if (opt['no-logs']) qs.push('includeLogs=0');
              say(await req('GET', '/workflow/status' + (qs.length ? '?' + qs.join('&') : ''), null));
            } else if (cmd === 'cancel') {
              if (!opt.run) { console.error('dsh-workflow: cancel needs --run <runId>'); process.exit(1); }
              say(await req('POST', '/workflow/cancel', JSON.stringify({
                runId: opt.run, reason: opt.reason || 'canceled by agent'
              })));
            } else if (cmd === 'create' || cmd === 'run') {
              // reason is required: the approval dialog shows why it wants to create/run, otherwise it shows raw JSON.
              if (!opt.reason) {
                console.error('dsh-workflow: --reason is required (shown to the user in the dialog)');
                console.error(USAGE);
                process.exit(1);
              }
              // `run --workflow <id>` runs a SAVED workflow: this path neither needs nor may read a
              // definition. (Before, readDef() was called unconditionally, so `run --workflow wf_x`
              // died with "need --file" and the documented command was unreachable.)
              const byId = cmd === 'run' && opt.workflow && !opt.file && !opt.json && !a[0];
              const body = { reason: opt.reason };
              if (opt.name) body.name = opt.name;
              if (byId) {
                body.workflowId = opt.workflow;
              } else {
                let def;
                try { def = JSON.parse(readDef()); } catch (e) {
                  console.error('dsh-workflow: definition is not valid JSON: ' + e.message);
                  process.exit(1);
                }
                body.definition = def;
              }
              if (cmd === 'run') {
                body.wait = !!opt.wait;
                say(await req('POST', '/workflow/run', JSON.stringify(body)));
              } else {
                say(await req('POST', '/workflow/create', JSON.stringify(body)));
              }
            } else {
              console.error(USAGE);
              process.exit(1);
            }
          } catch (e) {
            console.error('dsh-workflow: ' + (e && e.message ? e.message : e));
            process.exit(1);
          }
        })();
    """.trimIndent()

    /**
     * 给容器里 AI 的工作流 DSL 说明（写到 `/root/.dsh/workflow-dsl.md`，由工具插件的提示词段读）。
     *
     * 为什么由 App 写、而不是写死在插件里：DSL 的形状由宿主实现决定（校验器认哪些键、上限是
     * 多少、哪些动作存在），把它放在 App 侧意味着**改实现时文档跟着改**，否则 agent 会照着
     * 一份过时的说明写定义、然后在校验器那里撞墙。
     *
     * 同样保持纯 ASCII：读它的是模型，不是用户；而 CJK 在容器 locale 下会让文件更难读。
     */
    val WORKFLOW_DSL_DOC = """
        # Workflows (dsh-folk)

        A workflow is **declarative data**, not a script: the host app reads the whole definition,
        shows the user a flow chart, and only runs what that chart shows. There is no expression
        language and no way to execute code from inside a definition.

        ## Read this first

        - `dsh-workflow guide` prints this document at any time.
        - `dsh-workflow samples` lists ready-made definitions (in /root/.dsh/workflow-samples).
          Copy one and change it: `dsh-workflow sample dsh-ready`, then
          `dsh-workflow create --file /root/.dsh/workflow-samples/dsh-ready.json --reason "..."`.
          Starting from a sample is much safer than writing JSON from scratch - the samples are
          checked to be valid, so you cannot miss a required field.
        - The user can also create and edit workflows by hand in the app
          (Settings > Features > Automation workflows). Names and descriptions are what they read,
          so keep them meaningful; an invalid definition cannot be run and the app shows them the
          same validation errors you would get back.

        ## A complete example (the `minimal` sample)

        ```json
        {
          "schemaVersion": 1,
          "id": "sample-minimal",
          "name": "minimal",
          "description": "smallest runnable flow: one shell command",
          "declaredCaps": [],
          "nodes": [
            { "id": "trigger", "type": "trigger", "name": "trigger", "timeoutMs": 60000,
              "onError": "stop", "config": { "requireDsh": "0" } },
            { "id": "hello", "type": "execute", "name": "hello", "timeoutMs": 120000,
              "onError": "stop", "config": { "action": "shell", "cmd": "echo hello" } }
          ],
          "edges": [ { "from": "trigger", "to": "hello", "when": "" } ]
        }
        ```

        ## Two separate steps, two separate confirmations

        - `dsh-workflow create ...` -> **yellow** dialog: "save this workflow". Nothing runs.
        - `dsh-workflow run ...`    -> **red** dialog: "run it now", plus an Android permission
          check dialog before the first step. Long runs are allowed here (a run may call the
          native bridge many times without asking again -- the one approval covers the run).

        Both dialogs show a preview. The user may **edit** the definition there; when they do, the
        response contains a `diff` (node/edge add/remove/modify) -- read it, because it is what
        actually ran. They may also reject with a reason; you get `reason: rejected_by_user`.

        Runs are **non-blocking by default**: `run` returns a `runId` immediately. Pass `--wait`
        only when the whole point is the result. Otherwise follow up with `status`.

        ## Node types

        Every node: `id` (letter first, [A-Za-z0-9_-], <=32), `type`, `name`, `posX`, `posY`
        (left/top in the editor; 0/0 lets the app lay it out), `timeoutMs` (1000..3600000,
        default 120000), `onError` = `stop` (default) | `continue` | `skip`, and `config`.

        - `trigger` -- exactly one, must have no incoming edge. config: `requireDsh` = 0|1,
          `autostartDsh` = 0|1 (default 1). With `requireDsh=1` the host starts DSH if needed and
          waits; then it takes the `true` edge. If DSH cannot come up it takes the `false` edge
          (draw one if you want a fallback flow) or fails the run.
        - `execute` -- config: `action` = `shell` | `dsh` | `notify`.
          - `shell`: `cmd` (<=8192 chars, run in the container)
          - `dsh`: same as shell, but DSH is started and waited for first
          - `notify`: `title` (optional), `text`
        - `condition` -- config: `source` = `exitCode` | `lastOutput` | `var:<name>` |
          `branch:<nodeId>` | `step:<nodeId>`, `op` = `contains` | `equals` | `matches` |
          `exists` | `empty`, `value` (not needed for exists/empty). Gives `true`/`false` edges.
        - `logic` -- config: `op` = `and` | `or` | `not`, `sources` = comma-separated ids of
          condition/logic nodes. Gives `true`/`false` edges.
        - `extract` -- pull one value out of a step's output into a run variable.
          config: `source` = `lastOutput` | `step:<nodeId>`, `mode` = `text` | `regex` | `json`,
          `pattern` (regex) or `path` (dotted JSON path), `var` (the variable name).
        - `wait` -- config: `ms` (<=86400000) or `untilDsh` = `running` | `stopped`.

        ## Edges

        `{ "from": "<id>", "to": "<id>", "when": "" | "true" | "false" }`.
        Only `condition` / `logic` (and a `trigger` with `requireDsh=1`) may use `when`, and such
        nodes must have **both** a `true` and a `false` edge. Everything else uses `"when": ""`.
        The graph must be acyclic and every node must be reachable from the trigger.

        ## Variables

        ${D}{name} inside `cmd` / `notify` text is replaced from the run's variables (`extract`
        writes them). Undefined names are left as-is on purpose -- silently substituting an empty
        string turns `rm -rf ${D}{dir}/` into `rm -rf /`. These are pre-seeded: `run_id`,
        `workflow_id`, `workflow_name`, `dsh_port`, `dsh_url`, `dsh_state`.

        ## Limits

        <=64 nodes, <=256 edges, <=8KB per command, log line <=4KB, a single run's log is kept
        per run under `filesDir/workflows/runs/<runId>.log` inside the app.

        ## Following up

        `dsh-workflow status [--run <runId>] [--from <line>]` returns the run state, every finished
        step (state / exitCode / duration / reason / output tail) and new log lines. Reuse
        `logNextLine` as the next `--from` so you never re-read the whole log. `cancel --run` kills
        the currently running step.
    """.trimIndent()

    val WORKFLOW_SAMPLES: Map<String, String> = mapOf(
        // 三份各自演示一件最容易写错的事：最小可跑 / DSH 拉起与 false 分支 / 变量与取值。
        "minimal" to """
{
  "schemaVersion": 1,
  "id": "sample-minimal",
  "name": "minimal",
  "description": "smallest runnable flow: one shell command",
  "declaredCaps": [],
  "declaresDangerous": false,
  "nodes": [
    {
      "id": "trigger",
      "type": "trigger",
      "name": "trigger",
      "posX": 16,
      "posY": 16,
      "timeoutMs": 60000,
      "onError": "stop",
      "config": {
        "requireDsh": "0"
      }
    },
    {
      "id": "hello",
      "type": "execute",
      "name": "hello",
      "posX": 16,
      "posY": 70,
      "timeoutMs": 120000,
      "onError": "stop",
      "config": {
        "action": "shell",
        "cmd": "echo hello from a workflow"
      }
    }
  ],
  "edges": [
    {
      "from": "trigger",
      "to": "hello",
      "when": ""
    }
  ]
}
""",
        "dsh-ready" to """
{
  "schemaVersion": 1,
  "id": "sample-dsh-ready",
  "name": "dsh-ready",
  "description": "start DSH if needed, then run a dsh command; false branch notifies the user",
  "declaredCaps": [
    "notify"
  ],
  "declaresDangerous": false,
  "nodes": [
    {
      "id": "trigger",
      "type": "trigger",
      "name": "trigger",
      "posX": 16,
      "posY": 16,
      "timeoutMs": 180000,
      "onError": "stop",
      "config": {
        "requireDsh": "1",
        "autostartDsh": "1"
      }
    },
    {
      "id": "dshcli",
      "type": "execute",
      "name": "dsh-cli",
      "posX": 16,
      "posY": 70,
      "timeoutMs": 120000,
      "onError": "stop",
      "config": {
        "action": "dsh",
        "cmd": "dsh --version"
      }
    },
    {
      "id": "tell",
      "type": "execute",
      "name": "notify",
      "posX": 16,
      "posY": 124,
      "timeoutMs": 30000,
      "onError": "continue",
      "config": {
        "action": "notify",
        "title": "DSH is not running",
        "text": "Start DSH in DSH-Folk, then run this workflow again."
      }
    }
  ],
  "edges": [
    {
      "from": "trigger",
      "to": "dshcli",
      "when": "true"
    },
    {
      "from": "trigger",
      "to": "tell",
      "when": "false"
    }
  ]
}
""",
        "variables" to """
{
  "schemaVersion": 1,
  "id": "sample-variables",
  "name": "variables",
  "description": "extract a value from one step and use it in the next",
  "declaredCaps": [
    "notify"
  ],
  "declaresDangerous": false,
  "nodes": [
    {
      "id": "trigger",
      "type": "trigger",
      "name": "trigger",
      "posX": 16,
      "posY": 16,
      "timeoutMs": 60000,
      "onError": "stop",
      "config": {
        "requireDsh": "0"
      }
    },
    {
      "id": "produce",
      "type": "execute",
      "name": "produce",
      "posX": 16,
      "posY": 70,
      "timeoutMs": 60000,
      "onError": "stop",
      "config": {
        "action": "shell",
        "cmd": "printf '{\\\"tag\\\":\\\"v1\\\"}'"
      }
    },
    {
      "id": "pick",
      "type": "extract",
      "name": "pick",
      "posX": 16,
      "posY": 124,
      "timeoutMs": 60000,
      "onError": "stop",
      "config": {
        "source": "step:produce",
        "mode": "json",
        "path": "tag",
        "var": "tag"
      }
    },
    {
      "id": "use",
      "type": "execute",
      "name": "use",
      "posX": 16,
      "posY": 178,
      "timeoutMs": 60000,
      "onError": "stop",
      "config": {
        "action": "shell",
        "cmd": "echo got ${D}{tag}"
      }
    },
    {
      "id": "check",
      "type": "condition",
      "name": "check",
      "posX": 16,
      "posY": 232,
      "timeoutMs": 60000,
      "onError": "stop",
      "config": {
        "source": "var:tag",
        "op": "equals",
        "value": "v1"
      }
    },
    {
      "id": "ok",
      "type": "execute",
      "name": "ok",
      "posX": 16,
      "posY": 286,
      "timeoutMs": 30000,
      "onError": "continue",
      "config": {
        "action": "notify",
        "title": "workflow ok",
        "text": "tag is ${D}{tag}"
      }
    },
    {
      "id": "bad",
      "type": "execute",
      "name": "bad",
      "posX": 200,
      "posY": 286,
      "timeoutMs": 30000,
      "onError": "continue",
      "config": {
        "action": "notify",
        "title": "workflow odd",
        "text": "tag is not ${D}{tag}"
      }
    }
  ],
  "edges": [
    {
      "from": "trigger",
      "to": "produce",
      "when": ""
    },
    {
      "from": "produce",
      "to": "pick",
      "when": ""
    },
    {
      "from": "pick",
      "to": "use",
      "when": ""
    },
    {
      "from": "use",
      "to": "check",
      "when": ""
    },
    {
      "from": "check",
      "to": "ok",
      "when": "true"
    },
    {
      "from": "check",
      "to": "bad",
      "when": "false"
    }
  ]
}
""",
    )

    const val WORKFLOW_SAMPLES_DIR = "workflow-samples"

    const val WORKFLOW_DSL_DOC_NAME = "workflow-dsl.md"

    const val TROUBLESHOOT_DOC_NAME = "dsh-native-troubleshoot.txt"

    /**
     * 容器里的权限桥疑难解答（agent 用 `dsh-native troubleshoot` 读它）。
     *
     * 为什么是 App 写进容器、而不是写死在 CLI 脚本里：那个脚本的字符串字面量不许有 CJK
     * （check-native-cli 门禁），而这份清单要跟着**应用内语言**走 —— 只有资源系统能做到。
     * 写在 [DshEnv.dshHome]（保留清单里），所以更新运行时也不会丢。
     *
     * 三个 reason 常量是从 [PrivilegedShell] 插进文案的：报错里出现的就是它们的值
     * （root_unverified / shizuku_unauthorized / adb_unpaired），照抄进清单 agent 才对得上。
     */
    fun nativeTroubleshootDoc(ctx: Context): String = buildString {
        appendLine(ctx.appString(R.string.dsh_troubleshoot_intro))
        appendLine()
        appendLine(ctx.appString(R.string.dsh_troubleshoot_no_channel))
        appendLine()
        appendLine(ctx.appString(R.string.dsh_troubleshoot_root, PrivilegedShell.REASON_ROOT_UNVERIFIED))
        appendLine(ctx.appString(R.string.dsh_troubleshoot_shizuku, PrivilegedShell.REASON_SHIZUKU_UNAUTHORIZED))
        appendLine(ctx.appString(R.string.dsh_troubleshoot_adb, PrivilegedShell.REASON_ADB_UNPAIRED))
        appendLine()
        appendLine(ctx.appString(R.string.dsh_troubleshoot_cap))
        appendLine()
        appendLine(ctx.appString(R.string.dsh_troubleshoot_perm))
        appendLine()
        appendLine(ctx.appString(R.string.dsh_troubleshoot_rom))
    }

}
