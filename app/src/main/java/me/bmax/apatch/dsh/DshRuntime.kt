package me.bmax.apatch.dsh

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.util.LocaleCtx
import me.bmax.apatch.util.appString
import org.json.JSONObject

/** 运行时阶段。 */
enum class DshPhase { NOT_READY, DOWNLOADING, EXTRACTING, STARTING, RUNNING, ERROR }

/** 端口冲突时用户的选择。 */
enum class PortConflictAction { AUTO, MANUAL, FORCE }

/** 运行时状态快照（首页卡片与启动日志面板消费）。 */
data class DshState(
    val phase: DshPhase = DshPhase.NOT_READY,
    val progress: Float = 0f,
    val speedBytesPerSec: Long = 0L,
    val message: String = "",
    val port: Int = DshEnv.DEFAULT_PORT,
    val pid: Long? = null,
    val runtimeVersion: String? = null,
    val installed: Boolean = false,
    /**
     * 容器体积（字节），0 表示还没算过。
     *
     * 走缓存 + 后台重算而不是现算：见 [DshEnv.KEY_ROOTFS_SIZE]。
     */
    val rootfsSizeBytes: Long = 0L,
    /**
     * 启动前探测到端口被外部进程占用、正等用户决定。
     *
     * 置位时首页弹「端口被占用」对话框，用户在 [DshRuntime.resolvePortConflict]
     * 里选择换端口 / 手动指定 / 强制启动。
     */
    val portConflict: Boolean = false,
) {
    val webUrl: String get() = "http://127.0.0.1:$port/"
}

/** 运行时下载元数据（由 CI 生成的 metadata.json 提供）。 */
data class DshMeta(
    val version: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val mirrors: List<String> = emptyList(),
    val arch: String = "",
    val dsh: String = "",
    val nodeVersion: String = "",
    val builtAt: String = "",
)

/**
 * DSH 运行时管理：在线下载 rootfs → 解压 → 用 proot/proroot 进容器起 `dsh web`。
 *
 * 组合了两个上游的做法：
 * - 容器执行层（proot argv / 环境变量 / 硬链接探测 / proroot 三振出局回退）来自 DSHA；
 * - 在线交付层（metadata.json + 多镜像测速竞速 + sha256 + 进度）来自 DSHM。
 *
 * 关键约束（Android 平台限制，不是选择）：
 * - 可执行的 proot/proroot 只能从 `nativeLibraryDir` 运行（app 私有目录是 SELinux noexec）；
 * - rootfs 必须落在 filesDir（可写），容器内的一切执行由 proot 代理。
 */
object DshRuntime {
    private const val TAG = "DSH-Folk"
    /**
     * 等服务就绪的上限。
     *
     * 从 180s 压到 90s：proroot 现在失败一次就回退（见 [PROROOT_FAIL_LIMIT]），
     * 而「卡住不退」的 proroot 唯一表现就是耗尽这个超时 —— 等待越久，用户从
     * 坏运行时走到可用运行时的时间就越长。node 起 dsh web 正常在 10s 内。
     */
    private const val READY_TIMEOUT_MS = 90_000L
    /**
     * proroot 连续失败到此次数即强制回退 proot 并清掉用户选择。
     *
     * 取 1（失败即回退）：proroot 的失败模式是内核相关的确定性失败，重试同一台
     * 设备不会有不同结果，让用户白等第二、三次没有意义。
     */
    private const val PROROOT_FAIL_LIMIT = 1

    /** ELF `e_machine`：183 = AArch64，62 = x86-64（见 [rootfsArchMismatch]）。 */
    private const val ELF_MACHINE_AARCH64 = 183
    private const val ELF_MACHINE_X86_64 = 62

    /** profile pnpm 设置里固定导入方式（见 [ensureProfilePnpmSettings]）。 */
    private const val PNPM_IMPORT_KEY = "packageImportMethod"
    private const val PNPM_IMPORT_LINE = "packageImportMethod: copy"

    /** 放行依赖构建脚本的顶层键（见 [allowProfileBuilds]）。 */
    private const val PNPM_ALLOW_BUILDS_KEY = "allowBuilds"

    /** v1.3 写进 /root/.npmrc 的无效行，只为清理它而保留。 */
    private const val NPMRC_LEGACY_LINE = "package-import-method=copy"

    /** web profile 相对 [DshEnv.dshHome] 的路径（guest 侧是 /root/.dsh/profiles/web）。 */
    private const val PROFILE_WEB_REL = "profiles/web"

    /**
     * 首启预装的插件（npm 包名，已人工验证可装）。
     *
     * 手机上没有这几个体验差很多：dsh-web-mobile 做移动端适配，dshmarket 提供
     * WebUI 内的插件市场，dsh-config-manager 则是**本应用配置备份的依赖** ——
     * 设置里的导出/导入走的正是它的回环 HTTP API（见 [DshConfigBackup]），
     * 没装的话那一页直接不可用。dsh-file-upload 补上手机端最缺的一环：
     * 拖拽/回形针上传、文档转 Markdown、图片 OCR、语音输入 —— 手机上没有
     * 命令行贴文件这条路，全靠它把本地文件送进会话。
     *
     * 往这个清单里加包是安全的：[seedPlugins] 按包名逐个记账，老用户下次冷启动
     * 会补装增量（不会因为「已完成」标记而永远跳过）。
     */
    val SEED_PLUGINS =
        listOf("dsh-web-mobile", "dshmarket", "dsh-config-manager", "dsh-file-upload")

    /**
     * 「预装补修」轮次（见 [applySeedRepair]）。
     *
     * 修好一个会让预装失败的根因后 +1，让**记过账但实际没生效**的预装包再试一次。
     *   1 = 1.7.7：修 pnpm 拦构建脚本导致 dsh-file-upload 装了但没进 bundles
     */
    private const val SEED_REPAIR_REV = 1

    /** 预装插件集合（供插件列表/商店渲染「预装」标签）。 */
    fun isSeedPlugin(pkg: String): Boolean = pkg in SEED_PLUGINS

    /**
     * 容器内 `dsh-fs` CLI（node 脚本，读 /root/.dsh/fs-bridge.json 后回环调用文件桥）。
     *
     * 这是 Kotlin raw string：里面**不能出现 `${'$'}`**，否则会被当成模板插值。
     */
    private val FS_BRIDGE_CLI_SCRIPT = """
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
     * 容器内 `dsh-native` CLI：调宿主的原生能力（通知/振动/toast/剪贴板/分享/设备信息）。
     *
     * 与 `dsh-fs` 共用同一个回环端口与 token（同一份 fs-bridge.json）。失败时把宿主返回的
     * `reason` 打到 stderr 并以非 0 退出，agent 能据此区分「没启用」「没权限」「不在前台」。
     *
     * 同样是 raw string：里面**不能出现 `${'$'}`**。
     */
    private val NATIVE_CLI_SCRIPT = """
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
        for (let i = 1; i < argv.length; i++) {
          const t = argv[i];
          if (t.slice(0, 2) === '--') {
            const eq = t.indexOf('=');
            if (eq > 2) { opt[t.slice(2, eq)] = t.slice(eq + 1); }
            else if (i + 1 < argv.length && argv[i + 1].slice(0, 2) !== '--') { opt[t.slice(2)] = argv[++i]; }
            else { opt[t.slice(2)] = '1'; }
          } else { a.push(t); }
        }
        function q(pairs) {
          const out = [];
          for (const k in pairs) { if (pairs[k] !== undefined) out.push(k + '=' + enc(String(pairs[k]))); }
          return out.length ? '?' + out.join('&') : '';
        }
        function say(r) {
          const text = r.body.toString();
          if (r.status === 200) { process.stdout.write(text + '\n'); return; }
          process.stderr.write(text + '\n');
          process.exitCode = 1;
        }
        const USAGE = [
          'usage: dsh-native <command> [args] [options]',
          '  notify <title> [body] [--id N] [--ongoing]',
          '  notify-cancel [--id N]',
          '  toast <text>',
          '  vibrate [--ms N] [--amplitude 1..255]',
          '  clip get | clip set <text> [--label L]',
          '  share <text> [--title T]',
          '  open <https URL>',
          '  device',
          '  media list [--type image|video|audio] [--q name] [--limit N]',
          '  media get <id> [--type image|video|audio]   # lands in /tmp; JSON carries path',
          '  mic record [--ms N]                         # 30000 max; lands in /tmp',
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
          '  caps',
          'Settings > Features > Native capabilities: enable the master switch and the item first.'
        ].join('\n');
        (async function () {
          try {
            if (cmd === 'caps') {
              say(await req('GET', '/native/capabilities'));
            } else if (cmd === 'device') {
              say(await req('GET', '/native/device'));
            } else if (cmd === 'notify') {
              if (!a[0]) { console.error(USAGE); process.exit(1); }
              say(await req('POST', '/native/notify' + q({
                title: a[0], body: a[1], id: opt.id, ongoing: opt.ongoing
              })));
            } else if (cmd === 'notify-cancel') {
              say(await req('DELETE', '/native/notify' + q({ id: opt.id })));
            } else if (cmd === 'toast') {
              if (!a[0]) { console.error(USAGE); process.exit(1); }
              say(await req('POST', '/native/toast' + q({ text: a[0] })));
            } else if (cmd === 'vibrate') {
              say(await req('POST', '/native/vibrate' + q({ ms: opt.ms, amplitude: opt.amplitude })));
            } else if (cmd === 'clip') {
              if (a[0] === 'get') {
                say(await req('GET', '/native/clipboard'));
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
                say(await req('GET', '/native/tts/voices'));
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
              say(await req('GET', '/native/phone/info'));
            } else if (cmd === 'sensors') {
              if (a[0] === 'list') {
                say(await req('GET', '/native/sensors/list'));
              } else if (a[0] === 'read' && a[1]) {
                say(await req('GET', '/native/sensors/read' + q({ id: a[1] })));
              } else { console.error(USAGE); process.exitCode = 1; }
            } else if (cmd === 'network') {
              say(await req('GET', '/native/network'));
            } else if (cmd === 'volume') {
              if (!a[0]) {
                say(await req('GET', '/native/volume'));
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
                say(await req('GET', '/native/settings'));
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
              say(await req('GET', '/native/install'));
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
     * 插件树/客户端包加载失败的日志签名。
     *
     * 命中这些就**不能**判定为 proroot 故障：它与容器运行时无关，换 proot 重试
     * 照样失败（真机实测就是这样），而 [PROROOT_FAIL_LIMIT] = 1 会一次就把用户的
     * proroot 偏好静默清掉。
     */
    private val PLUGIN_FAILURE_MARKS = listOf(
        "plugin tree failed to load",
        "client bundles not found",
        "failed to apply loader entry modules",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(DshState())
    val state: StateFlow<DshState> = _state.asStateFlow()

    private lateinit var appContext: Context
    private var serverProcess: Process? = null
    private var startedAt: Long = 0L

    @Volatile private var hardlinkOk: Boolean? = null

    /**
     * 硬链接探测失败的原因（异常类名 + message），成功时为空。
     *
     * 必须留下来：原来只走 Log.i，而 logcat 在 bugreport 里只有最近几分钟，
     * App 早已启动完，那一行永远抓不到 —— 于是「为什么这台设备不支持硬链接」
     * 每次都只能靠猜。现在它会随 [startServer] 一起写进 dsh.log。
     */
    @Volatile private var hardlinkDetail: String = ""

    /**
     * 只绑定 Context，不做任何 IO。**必须在任何 Composable 读取本对象之前调用**
     * （APApplication.onCreate 里）。
     *
     * 原来只有 attach() 一个入口，而它是在首页的 LaunchedEffect 里调的 —— 那时
     * 首次 composition 已经跑完了，composition 期间读 runtimeId() / port() 会撞上
     * 未初始化的 lateinit 直接崩掉（真机实测：进首页几秒后
     * UninitializedPropertyAccessException）。
     */
    fun init(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
            // 一次性把旧版本落在 rootfs 里的 /root/.dsh 搬到独立数据目录（见
            // DshEnv.migrateDshHome）。必须在任何 dshHome 访问之前做，否则数据被
            // 空目录顶掉。rename 是原子的，放这里不会卡启动。
            DshEnv.migrateDshHome(appContext)
            // 进程重启后旧日志不该残留：清一次，让启动日志按「本次运行」呈现。
            // startServer() 里还会再清一次，这里主要覆盖「只开 App 不启动服务」的情况。
            clearLog()
        }
    }

    /** Context 是否已绑定。未绑定时所有读接口给默认值而不是抛异常。 */
    private val ready: Boolean get() = ::appContext.isInitialized

    fun attach(context: Context) {
        init(context)
        val installed = DshEnv.isRuntimeInstalled(appContext)
        val cachedSize = prefs().getLong(DshEnv.KEY_ROOTFS_SIZE, 0L)
        _state.update {
            it.copy(
                installed = installed,
                port = port(),
                runtimeVersion = prefs().getString(DshEnv.KEY_RUNTIME_VERSION, null),
                rootfsSizeBytes = if (installed) cachedSize else 0L,
                phase = if (installed) it.phase else DshPhase.NOT_READY,
            )
        }
        // 装好了但还没量过（升级上来的旧安装）：后台补一次，别让界面一直显示「—」
        if (installed && cachedSize <= 0L) refreshRootfsSize()
    }

    /**
     * 后台重算容器体积并落盘缓存。
     *
     * 只允许从这里进入 [dirSize]：它要遍历十万级文件，在组合期同步调用会让
     * 每次导航回首页都卡两秒以上（真机 MIUIScout 实测 duration=2505ms）。
     */
    fun refreshRootfsSize() {
        if (!ready) return
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { dirSize(DshEnv.rootfs(appContext)) }
            prefs().edit().putLong(DshEnv.KEY_ROOTFS_SIZE, bytes).apply()
            _state.update { it.copy(rootfsSizeBytes = bytes) }
        }
    }

    private fun prefs() = appContext.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)

    fun port(): Int {
        if (!ready) return DshEnv.DEFAULT_PORT
        return prefs().getInt(DshEnv.KEY_PORT, DshEnv.DEFAULT_PORT).let {
            if (it in 1..65535) it else DshEnv.DEFAULT_PORT
        }
    }

    fun setPort(p: Int) {
        if (!ready || p !in 1..65535) return
        prefs().edit().putInt(DshEnv.KEY_PORT, p).apply()
        _state.update { it.copy(port = p) }
    }

    // ────────────────────────── 端口占用检测 ──────────────────────────

    /** 127.0.0.1:port 是否已被某个进程监听（TCP connect 探测）。 */
    fun isPortInUse(port: Int): Boolean = runCatching {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1", port), 800)
            true
        }
    }.getOrDefault(false)

    /**
     * 从 [from] 起向后扫第一个空闲端口。
     *
     * 供「端口被占用 → 换一个」用；扫到 65535 还没有就回退默认端口（仍可能占用，
     * 但比拿一个越界端口强）。
     */
    fun findFreePort(from: Int = port() + 1): Int {
        var p = from.coerceIn(1, 65535)
        while (p <= 65535) {
            if (!isPortInUse(p)) return p
            p++
        }
        return DshEnv.DEFAULT_PORT
    }

    /**
     * 用户对「端口被占用」作出的决定。
     *
     * [PortConflictAction.AUTO]：换一个空闲端口；[PortConflictAction.MANUAL]：用
     * [newPort]（越界或仍被占用则保留冲突标记，交由 UI 提示）；[PortConflictAction.FORCE]：
     * 不改端口、强行继续启动。
     *
     * `setPort` 只是同步 prefs 写 + state 更新，改完端口再调 [bootstrap] 重跑；
     * FORCE 则跳过端口探测、直接 [startAndAwait]。
     */
    fun resolvePortConflict(action: PortConflictAction, newPort: Int? = null) {
        if (!ready) return
        when (action) {
            PortConflictAction.AUTO -> {
                setPort(findFreePort())
                _state.update { it.copy(portConflict = false) }
                bootstrap()
            }
            PortConflictAction.MANUAL -> {
                val p = newPort ?: return
                if (p !in 1..65535 || isPortInUse(p)) return // 保留 portConflict，UI 继续提示
                setPort(p)
                _state.update { it.copy(portConflict = false) }
                bootstrap()
            }
            PortConflictAction.FORCE -> {
                // 强行启动：跳过端口探测直接拉起（用户明知端口被占仍要用）
                _state.update { it.copy(portConflict = false) }
                scope.launch {
                    bootMutex.withLock { startAndAwait() }
                }
            }
        }
    }

    // ────────────────────────── 局域网访问 ──────────────────────────

    /** 局域网访问开关（默认关）。 */
    fun lanEnabled(): Boolean =
        ready && prefs().getBoolean(DshEnv.KEY_LAN, false)

    fun setLanEnabled(enabled: Boolean) {
        if (!ready) return
        prefs().edit().putBoolean(DshEnv.KEY_LAN, enabled).apply()
    }

    /** 本机局域网 IPv4（site-local），取不到返回 null。 */
    fun lanIp(): String? = runCatching {
        val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
        while (ifaces.hasMoreElements()) {
            val nif = ifaces.nextElement()
            if (!nif.isUp || nif.isLoopback) continue
            val addrs = nif.inetAddresses
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement()
                if (a is java.net.Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) {
                    return@runCatching a.hostAddress
                }
            }
        }
        null
    }.getOrNull()

    // ────────────────────────── 容器运行时选择 ──────────────────────────

    /**
     * 当前选择的运行时 id。
     *
     * 默认 proroot：它不走 ptrace，容器内进程开销明显低于 proot。代价是在部分
     * 内核上会卡在 seccomp/ptrace 上起不来 —— 这条路由 [noteProrootFailure]
     * 兜底，失败一次就自动切回 proot，所以默认选快的那个是合理的。
     *
     * 注意这是**用户的选择**，不一定是实际在跑的那个：proroot 的五个 .so 只有
     * arm64-v8a 有，x86_64 设备上这里返回 proroot 而 [runtime] 给的是 proot。
     * 凡是描述或依赖「实际行为」的地方用 [effectiveRuntimeId]。
     */
    fun runtimeId(): String =
        if (!ready) "proroot" else prefs().getString(DshEnv.KEY_RUNTIME, "proroot") ?: "proroot"

    /**
     * 实际会用来起容器的运行时 id。
     *
     * 与 [runtimeId] 的差别只在「选了 proroot 但它在本机不可用」这一种情况，而这一种
     * 在 x86_64 设备上是**常态**（proroot 上游只出 arm64）。分不清两者会同时产生
     * 三个可观察的错误，报障包里都出现过：
     *  - 启动日志第 1 行说 proot（走 runtime()），第 3 行说「proroot 无条件启用 l2s」；
     *  - basic.txt 记 `ContainerRuntime: proroot`，与同一个包里的 dsh.log 自相矛盾；
     *  - [linkBecomesSymlink] 误判为 true，pnpm 被无谓地降级成 `--package-import-method
     *    copy` —— 明明 proot 在硬链接可用时根本不加 `--link2symlink`。
     */
    fun effectiveRuntimeId(): String = if (!ready) "proroot" else runtime().id()

    fun setRuntimeId(id: String) {
        if (!ready) return
        prefs().edit().putString(DshEnv.KEY_RUNTIME, id).putInt(DshEnv.KEY_PROROOT_FAIL, 0).apply()
    }

    /** 解析出实际可用的运行时；选中的不可用时静默回退 proot。 */
    fun runtime(): ContainerRuntime {
        val proot = ContainerRuntime.Proot(appContext, nativeLib("libproot.so"))
        if (runtimeId() != "proroot") return proot
        val proroot = ContainerRuntime.Proroot(appContext, ContainerRuntime.Proroot.defaultDir(appContext))
        return if (proroot.available()) proroot else proot
    }

    /**
     * 刚刚发生过 proroot → proot 的自动回退，等着用 proot 重试一次。
     *
     * 存在的理由：[PROROOT_FAIL_LIMIT] 是 1，失败即回退。如果只写一行
     * 「已切回 proot，请重新启动」，用户首次开应用就会撞上一次失败并被
     * 要求手动重试——而此时我们已经知道该用 proot 了。
     */
    @Volatile
    private var prorootFellBack = false

    /**
     * 记一次 proroot 启动失败；达上限强制切回 proot。返回是否触发了回退。
     *
     * 插件层面的失败不算：见 [PLUGIN_FAILURE_MARKS]。
     */
    fun noteProrootFailure(why: String): Boolean {
        if (runtimeId() != "proroot") return false
        if (lastLogSuggestsPluginFailure()) {
            logWarn(R.string.dsh_log_plugin_stage_failure)
            return false
        }
        val n = prefs().getInt(DshEnv.KEY_PROROOT_FAIL, 0) + 1
        return if (n >= PROROOT_FAIL_LIMIT) {
            prefs().edit().putString(DshEnv.KEY_RUNTIME, "proot").putInt(DshEnv.KEY_PROROOT_FAIL, 0).apply()
            // 两条独立的完整句子，而不是把「连续 N 次」拼进一句 —— 片段拼接在
            // 别的语言里语序就散了（这也是为什么没有 dsh_log_proroot_failed_prefix）
            if (n > 1) logWarn(R.string.dsh_log_proroot_failed_times, n, why)
            else logWarn(R.string.dsh_log_proroot_failed, why)
            prorootFellBack = true
            true
        } else {
            prefs().edit().putInt(DshEnv.KEY_PROROOT_FAIL, n).apply()
            false
        }
    }

    /**
     * 启动日志尾部是否指向插件树加载失败。
     *
     * 保守匹配：宁可漏判（退回旧的误降级行为）也不误判（把真正的 proroot 故障
     * 当成插件问题、让用户一直卡在坏运行时上）。
     */
    private fun lastLogSuggestsPluginFailure(): Boolean {
        if (!ready) return false
        val tail = runCatching {
            LogStore.named(DshEnv.serverLog(appContext)).tail(200)
        }.getOrDefault("")
        if (tail.isEmpty()) return false
        return PLUGIN_FAILURE_MARKS.any { tail.contains(it, ignoreCase = true) }
    }

    /** 插件树加载失败的可修复提示；无此迹象时返回 null。 */
    fun pluginTreeFailureHint(): String? =
        if (lastLogSuggestsPluginFailure()) str(R.string.dsh_err_plugin_tree_failed) else null

    private fun nativeLib(name: String): File = File(DshEnv.nativeLibDir(appContext), name)

    /**
     * rootfs 所在文件系统是否支持真实硬链接。
     *
     * 支持时 proot 不加 `--link2symlink`：该扩展把 `link()` 目标改写成指向临时中间文件的
     * 符号链接，而 dsh 的 write 工具正是用 `link(临时文件, 目标)` 发布后立刻删临时目录 ——
     * 于是新建文件 100% 变悬空链接（write 报成功但读不出来）。
     */
    fun hardlinkSupported(): Boolean {
        hardlinkOk?.let { return it }
        synchronized(this) {
            hardlinkOk?.let { return it }
            val dir = DshEnv.rootfs(appContext).takeIf { it.isDirectory } ?: appContext.filesDir
            val src = File(dir, ".dshfolk-linkprobe")
            val dst = File(dir, ".dshfolk-linkprobe.hl")
            var ok = false
            var detail = ""
            try {
                dir.mkdirs()
                src.delete(); dst.delete()
                Files.write(src.toPath(), byteArrayOf('o'.code.toByte(), 'k'.code.toByte()))
                Files.createLink(dst.toPath(), src.toPath())
                ok = dst.isFile && dst.length() == 2L
                if (!ok) detail = str(R.string.dsh_log_hardlink_unreadable)
            } catch (e: Throwable) {
                ok = false
                detail = "${e.javaClass.simpleName}: ${e.message}"
            } finally {
                runCatching { src.delete() }
                runCatching { dst.delete() }
            }
            android.util.Log.i(TAG, "硬链接支持=$ok${if (detail.isEmpty()) "" else "（$detail）"}")
            hardlinkOk = ok
            hardlinkDetail = detail
            return ok
        }
    }

    /** 硬链接探测失败的原因；成功时为空。供启动日志记录用。 */
    fun hardlinkDetail(): String = hardlinkDetail

    /**
     * guest 里的 `link()` 是否会被改写成符号链接。
     *
     * 不能只看 [hardlinkSupported]：proroot **无条件**加 `--link2symlink`
     * （见 ContainerRuntime.Proroot.baseArgv），所以哪怕宿主文件系统支持真硬链接，
     * 容器内拿到的仍然是符号链接。pnpm 正是用 `link()` 从内容存储装包，一旦被改写，
     * `require.resolve` 的 realpath 就会跳进 CAS、把包目录结构解析没了。
     */
    fun linkBecomesSymlink(): Boolean = !hardlinkSupported() || effectiveRuntimeId() == "proroot"

    /** 复制 proot 的 NEEDED 依赖到可写 lib 目录（proroot 不需要）。 */
    private fun ensureRuntimeFiles() {
        val libDir = File(appContext.filesDir, "lib").apply { mkdirs() }
        DshEnv.tmpDir(appContext).mkdirs()
        // 每条 exec 路径都要保证 DNS 在：冷启动直接进插件页 / 终端页时不会走 bootstrap，
        // 少了这一步容器里 pnpm、apt 全是 EAI_AGAIN。已存在则原样保留（用户可能改过）。
        ensureContainerDns()
        ensureProfilePnpmSettings()
        ensureContainerGroups()
        if (runtime().id() != "proot") return
        // jniLibs 里叫 libtalloc.so / libandroidshmem.so，proot 按 SONAME 找
        copyExec(nativeLib("libtalloc.so"), File(libDir, "libtalloc.so.2"))
        copyExec(nativeLib("libandroidshmem.so"), File(libDir, "libandroid-shmem.so"))
    }

    private fun copyExec(src: File, dst: File) {
        if (!src.isFile) return
        if (dst.isFile && dst.length() == src.length()) return
        runCatching {
            src.inputStream().use { i -> FileOutputStream(dst).use { o -> i.copyTo(o) } }
            dst.setExecutable(true, false)
        }
    }

    // ────────────────────────── 进容器执行 ──────────────────────────

    private fun baseArgv(): MutableList<String> =
        runtime().baseArgv(DshEnv.rootfs(appContext), hardlinkSupported()).toMutableList()

    /** 容器内与宿主无关的 guest 环境 + 运行时专用环境。 */
    private fun applyEnv(pb: ProcessBuilder) {
        val rt = runtime()
        val libDir = File(appContext.filesDir, "lib")
        val env = pb.environment()
        if (rt.id() == "proot") {
            env["PROOT_TMP_DIR"] = DshEnv.tmpDir(appContext).absolutePath
            env["PROOT_LOADER"] = nativeLib("libprootloader.so").absolutePath
            env["PROOT_LOADER_32"] = nativeLib("libprootloader32.so").absolutePath
            env["LD_LIBRARY_PATH"] = "${libDir.absolutePath}:${DshEnv.nativeLibDir(appContext).absolutePath}"
            // 无硬链接时把 l2s 中间文件集中到 rootfs 内（默认就近存放会随 tmp 被清掉）
            if (!hardlinkSupported()) {
                val l2s = DshEnv.l2sDir(appContext).apply { mkdirs() }
                env["PROOT_L2S_DIR"] = l2s.absolutePath
            }
        }
        runCatching { rt.applyEnv(env, appContext.filesDir, libDir, DshEnv.tmpDir(appContext)) }
        // guest 侧环境：PATH 必须覆盖，否则继承 Android 的 /system/bin 找不到 bash 工具链
        env["HOME"] = "/root"
        env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        env["TMPDIR"] = "/tmp"
        env["LANG"] = "C.UTF-8"
        env["DEBIAN_FRONTEND"] = "noninteractive"
        env["TERM"] = "xterm-256color"
    }

    /** 在 rootfs 内执行一条 bash 命令（stderr 合并进 stdout）。 */
    fun execRootfs(bashCommand: String): Process {
        ensureRuntimeFiles()
        val argv = baseArgv()
        argv += listOf("/bin/bash", "-c", bashCommand)
        val pb = ProcessBuilder(argv).redirectErrorStream(true)
        pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        applyEnv(pb)
        return pb.start()
    }

    /** 启动交互式 bash（供终端页；cd/export 状态保持）。 */
    fun execRootfsInteractive(): Process {
        ensureRuntimeFiles()
        val argv = baseArgv()
        argv += "/bin/bash"
        val pb = ProcessBuilder(argv).redirectErrorStream(true)
        applyEnv(pb)
        pb.environment()["DSH_INTERACTIVE"] = "1"
        return pb.start()
    }

    /**
     * PTY 会话的 argv（终端页用）。默认 `bash -l`，也可指定 guest 命令。
     *
     * 与 [execRootfs] 共用 [baseArgv]：各写一份的话，PTY 里的 shell 会跑在和普通命令
     * 不一样的环境里，少一个 PROOT_LOADER 就直接起不来。
     */
    fun ptyArgv(vararg guestCmd: String): Array<String> {
        ensureRuntimeFiles()
        val argv = baseArgv()
        if (guestCmd.isEmpty()) {
            argv += "/bin/bash"
            argv += "-l"
        } else {
            argv += guestCmd
        }
        return argv.toTypedArray()
    }

    /**
     * PTY 会话的环境变量（`KEY=VALUE` 形式）。
     *
     * 借一个临时 ProcessBuilder 收集，而不是重抄一份：环境构造分散在 [applyEnv] 与
     * [ContainerRuntime.applyEnv]，还随 proot/proroot 分叉，手抄必漏。
     */
    fun ptyEnv(): Array<String> {
        val probe = ProcessBuilder("/system/bin/true")
        applyEnv(probe)
        return probe.environment()
            .filter { it.key != null && it.value != null }
            .map { "${it.key}=${it.value}" }
            .toTypedArray()
    }

    /**
     * 同步执行并读回输出（带超时，默认 60s）。
     *
     * 读流必须放到单独线程：readText() 会一直阻塞到 EOF，先读再 waitFor(timeout) 的写法
     * 里超时是彻底无效的 —— 子进程挂住不退出（等 stdin、卡在网络、pnpm 死锁）就会把调用
     * 线程永久钉死，容器进程也泄漏。这里改成读线程 + join(超时) + destroyForcibly。
     *
     * 超时返回已读到的部分而不是空串：pnpm / pip 那种半途卡死的情况，前面几百行输出
     * 往往正好说明卡在哪。
     */
    fun execRootfsForOutput(bashCommand: String, timeoutMs: Long = 60_000L): String =
        execRootfsStreaming(bashCommand, timeoutMs) {}

    /**
     * 同上，但每读到一整行就回调一次。
     *
     * 存在的理由：[execRootfsForOutput] 把输出攒到结束才一次返回，而
     * `dsh plugin add` 背后的 pnpm 可能跑几分钟——这段时间里界面拿不到
     * 任何进展，用户无法判断是在装还是卡死了。
     *
     * onLine 在读线程上调用（不是主线程），回调里不要直接碰 Compose 状态。
     */
    fun execRootfsStreaming(
        bashCommand: String,
        timeoutMs: Long = 60_000L,
        onLine: (String) -> Unit,
    ): String = runCatching {
        val p = execRootfs(bashCommand)
        val sb = StringBuilder()
        val reader = Thread {
            runCatching {
                p.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(8192)
                    // 手写行切分而不用 readLine()：pnpm 的进度行以 \r 结尾且不带 \n，
                    // readLine() 会一直等到下一个 \n 才吐——进度就又成了攒一批才出。
                    val line = StringBuilder()
                    fun flush() {
                        if (line.isEmpty()) return
                        val text = line.toString()
                        line.setLength(0)
                        runCatching { onLine(text) }
                    }
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(sb) { sb.appendRange(buf, 0, n) }
                        for (i in 0 until n) {
                            val ch = buf[i]
                            if (ch == '\n' || ch == '\r') flush() else line.append(ch)
                        }
                    }
                    flush()
                }
            }
        }
        reader.isDaemon = true
        reader.start()
        reader.join(timeoutMs)
        if (reader.isAlive) {
            // 超时：先杀进程让读流拿到 EOF，再给读线程一点时间收尾
            p.destroyForcibly()
            reader.join(1_000)
            logWarn(R.string.dsh_log_cmd_timeout, timeoutMs / 1000, bashCommand.take(120))
        } else if (!p.waitFor(2, TimeUnit.SECONDS)) {
            // 流已关但进程还在（少见：子孙进程继承了 stdout 又提前关掉）
            p.destroyForcibly()
        }
        synchronized(sb) { sb.toString() }
    }.getOrElse { "" }

    // ────────────────────────── 引导（下载 + 解压 + 启动）──────────────────────────

    /** 引导/重启/重装共用的串行锁：三者都会动 rootfs 与服务进程。 */
    private val bootMutex = Mutex()

    /**
     * 一键引导：未装则下载安装，然后拉起 web 服务。
     *
     * [startServer] 的守卫现在只看进程，不再兼当「防重复下载」；
     * 而 HarnessService 在 DOWNLOADING / EXTRACTING 阶段依然会放行 bootstrap，
     * 连点两次启动就会开两条 135MB 下载。用互斥锁 + 阶段判定拦住。
     */
    fun bootstrap() {
        scope.launch {
            if (busy()) return@launch
            bootMutex.withLock {
                if (!DshEnv.isRuntimeInstalled(appContext)) {
                    downloadAndInstall()
                    if (_state.value.phase == DshPhase.ERROR) return@withLock
                } else if (rootfsArchMismatch()) {
                    // 已装的 rootfs 架构不对，重下一份正确的。
                    // 1.7.6 在带 arm 转译层的 x86_64 设备上会装成 arm64 rootfs（见
                    // DshSource.runtimeArch 的注释），那份 rootfs 一执行就 SIGILL，
                    // 而 isRuntimeInstalled 只看文件在不在、会一直认为「已安装」——
                    // 不在这里自愈，用户就只能手动重装运行时才能脱困。
                    logWarn(R.string.dsh_log_arch_mismatch_redownload)
                    downloadAndInstall()
                    if (_state.value.phase == DshPhase.ERROR) return@withLock
                }
                setupResolvConf()
                seedPlugins()
                ensureFsBridgeCli()
                if (checkPortConflict()) return@withLock
                startAndAwait()
            }
        }
    }

    /**
     * 已解压的 rootfs 是不是别的架构。
     *
     * 判据取 rootfs 里 `usr/local/bin/node` 的 ELF `e_machine` —— 它就是 proot 起来后
     * 第一个真正要 exec 的目标。读不到（文件缺失、异常）返回 false：宁可放行也不要因为
     * 一次读失败就把用户的 150MB 运行时删了重下。
     */
    private fun rootfsArchMismatch(): Boolean = runCatching {
        val node = File(DshEnv.rootfs(appContext), "usr/local/bin/node")
        val machine = elfMachine(node) ?: return@runCatching false
        val want = when (DshSource.runtimeArch()) {
            "arm64-v8a" -> ELF_MACHINE_AARCH64
            else -> ELF_MACHINE_X86_64
        }
        if (machine == want) return@runCatching false
        logWarn(R.string.dsh_log_node_machine, machine, want)
        true
    }.getOrDefault(false)

    /** 读 ELF 头的 `e_machine`（偏移 0x12，2 字节）。不是 ELF 或读不到返回 null。 */
    private fun elfMachine(f: File): Int? = runCatching {
        if (!f.isFile) return@runCatching null
        FileInputStream(f).use { s ->
            val head = ByteArray(20)
            if (s.read(head) < 20) return@runCatching null
            if (head[0] != 0x7f.toByte() || head[1] != 'E'.code.toByte() ||
                head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()
            ) {
                return@runCatching null
            }
            val lo = head[18].toInt() and 0xff
            val hi = head[19].toInt() and 0xff
            if (head[5] == 1.toByte()) lo or (hi shl 8) else hi or (lo shl 8)
        }
    }.getOrNull()

    /**
     * 预装内置插件（首启，以及从旧版本升级后补装新增的那几个）。
     *
     * 放在服务启动**之前**：首启本来就要下 150MB + 解压，再加一轮 pnpm 是等比例的；
     * 而服务只启动一次、插件已经生效，不会出现「就绪了又要重启」的突兀体验。
     *
     * **按包名逐个记账**而不是记一个「已完成」布尔量：1.6 把清单从 2 个加到 3 个，
     * 如果沿用布尔量，1.5 老用户的标记已经是 true，新增的 dsh-config-manager 就
     * 永远轮不到装 —— 而它恰好是配置备份功能的依赖。记名字才能让老用户补上增量。
     *
     * 装过就不再重试（无论成功失败）：失败不该在每次冷启动重来，用户可以去商店手动装。
     * 但已经真装上的会先被跳过 —— 用 [DshPluginRepo.bundles] 对一遍，
     * 手动装过的同样算数，不会重复跑一次 pnpm。
     *
     * 这里**不做**安装后验证：插件是我们自己挑的、已人工验证过，在首启路径上再跑
     * 一次 `dsh web --port 0` 只会把首启拖长几分钟。
     */
    private suspend fun seedPlugins() {
        if (!DshEnv.isRuntimeInstalled(appContext)) return

        val p = prefs()
        val attempted = p.getString(DshEnv.KEY_SEEDED_PLUGINS, null)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toMutableSet()
            // 首次迁移：1.5 及更早只有布尔量，true 就意味着当时那两个已经试过了
            ?: if (@Suppress("DEPRECATION") p.getBoolean(DshEnv.KEY_SEED_PLUGINS_DONE, false)) {
                mutableSetOf("dsh-web-mobile", "dshmarket")
            } else {
                mutableSetOf()
            }

        // 已经装上的（含用户手动装的）。放在补修之前取：补修要靠它判断「记过账但没生效」。
        val installed = runCatching { DshPluginRepo.bundles() }.getOrElse { emptyList() }.toSet()

        // 修好根因后补修历史失败（见 KEY_SEED_REPAIR_REV）
        applySeedRepair(p, attempted, installed)

        val todo = SEED_PLUGINS.filter { it !in attempted }
        if (todo.isEmpty()) return

        val missing = todo.filter { it !in installed }
        if (missing.isEmpty()) {
            persistSeeded(attempted + todo)
            return
        }

        _state.update {
            it.copy(phase = DshPhase.EXTRACTING, progress = 0f, message = str(R.string.dsh_plugin_seeding))
        }
        for (pkg in missing) {
            logInfo(R.string.dsh_log_seeding, pkg)
            var out = runCatching {
                DshPluginRepo.install(pkg, onLine = { line -> appendLog(line) })
            }.getOrElse { str(R.string.dsh_log_seed_exception, it.message ?: it.javaClass.simpleName) }
            var code = exitCodeOf(out)

            // pnpm 拦下依赖的构建脚本时 **不是**「装不上」，而是「等人点头」：
            // 它以退出码 1 结束，于是 dsh 不 reconcile bundles，插件躺在 node_modules 里
            // 却进不了 profile 的 bundles —— 界面上就是「预装了但未生效」（1.7.6 的
            // dsh-file-upload 正是这样：它的传递依赖 sharp / tesseract.js 带 install 脚本）。
            //
            // 交互式 `pnpm approve-builds` 在容器里跑不了，而预装发生在启动路径上、
            // 根本没有人可问，所以这里自动放行**这一次预装自己拉进来的**构建脚本并重试。
            // 放行范围仅限 pnpm 点名的那几个包，不是全局开关。
            if (code != 0) {
                val pending = DshPluginRepo.pendingBuildApproval(out)
                if (pending.isNotEmpty()) {
                    logInfo(R.string.dsh_log_seed_builds_blocked, joinForLog(pending))
                    out = runCatching {
                        DshPluginRepo.install(
                            pkg,
                            onLine = { line -> appendLog(line) },
                            allowBuilds = pending,
                        )
                    }.getOrElse { str(R.string.dsh_log_seed_retry_exception, it.message ?: it.javaClass.simpleName) }
                    code = exitCodeOf(out)
                }
            }
            if (code == 0) logInfo(R.string.dsh_log_seed_done, pkg)
            else logWarn(R.string.dsh_log_seed_failed, pkg)
        }
        persistSeeded(attempted + todo)
        // 预装会大幅改变 node_modules 体积，顺手重算一次缓存
        refreshRootfsSize()
        _state.update { it.copy(phase = DshPhase.NOT_READY, progress = 1f) }
    }

    /** 从 dsh plugin 的输出里取真实退出码；没有标记行（超时/容器没起来）返回 null。 */
    private fun exitCodeOf(out: String): Int? = out.lineSequence()
        .lastOrNull { it.startsWith(DshPluginRepo.EXIT_MARKER) }
        ?.removePrefix(DshPluginRepo.EXIT_MARKER)?.trim()?.toIntOrNull()

    /** 记下「已尝试预装」的包名集合。 */
    private fun persistSeeded(names: Collection<String>) {
        prefs().edit()
            .putString(DshEnv.KEY_SEEDED_PLUGINS, names.distinct().joinToString(","))
            .apply()
    }

    /**
     * 补修历史预装失败：把**记过账但实际没进 bundles**的包从账本里摘掉，让它们再试一次。
     *
     * 为什么需要：[persistSeeded] 无论成败都记账（失败不该每次冷启动重试），于是修好
     * 根因也救不回已经失败的那次。1.7.6 的 dsh-file-upload 正卡在这里 —— pnpm 拦下
     * 传递依赖的构建脚本导致它没进 bundles，而包名已被记账，之后永远不再尝试。
     *
     * 只摘「不在 bundles 里」的：已生效的包不会被重跑。轮次号只前进一次，所以补修
     * 最多发生一轮，不会变成每次启动都重试失败项。
     */
    private fun applySeedRepair(
        p: android.content.SharedPreferences,
        attempted: MutableSet<String>,
        installed: Set<String>,
    ) {
        if (p.getInt(DshEnv.KEY_SEED_REPAIR_REV, 0) >= SEED_REPAIR_REV) return
        val retry = attempted.filter { it in SEED_PLUGINS && it !in installed }
        if (retry.isNotEmpty()) {
            attempted.removeAll(retry.toSet())
            persistSeeded(attempted)
            logInfo(R.string.dsh_log_seed_repair, joinForLog(retry))
        }
        p.edit().putInt(DshEnv.KEY_SEED_REPAIR_REV, SEED_REPAIR_REV).apply()
    }

    /**
     * 启动并等待就绪；如果这一轮触发了 proroot → proot 回退，就用 proot
     * 再试一次（只重试一次：proot 也起不来就是真错了，再试无意义）。
     */
    private suspend fun startAndAwait() {
        prorootFellBack = false
        startServer()
        awaitReady()
        if (!prorootFellBack) return
        prorootFellBack = false
        logInfo(R.string.dsh_log_retry_with_proot)
        stopServer()
        delay(500)
        startServer()
        awaitReady()
    }

    /** 下载/解压/启动中：不接受新的引导请求。 */
    private fun busy(): Boolean = _state.value.phase.let {
        it == DshPhase.DOWNLOADING || it == DshPhase.EXTRACTING || it == DshPhase.STARTING
    }

    /**
     * 启动前探测端口占用。
     *
     * 本服务自己的进程在跑（`serverProcess?.isAlive`）不算冲突 —— 那是正常监听，
     * `startServer` 自会识别并跳过。只在「我们没在跑、端口却连得上」时置 [DshState.portConflict]
     * 并中止本轮启动，等 [resolvePortConflict] 决定。
     *
     * @return true 表示已置冲突标记、应当中止本轮启动
     */
    private fun checkPortConflict(): Boolean {
        if (serverProcess?.isAlive == true) return false
        if (!isPortInUse(port())) return false
        _state.update { it.copy(portConflict = true) }
        logWarn(R.string.dsh_log_port_busy, port())
        return true
    }

    // ────────────────────────── 文件桥 ──────────────────────────

    /** 取（或首先生成）文件桥 token。 */
    private fun ensureFsToken(): String {
        val p = prefs()
        p.getString(DshEnv.KEY_FS_TOKEN, null)?.takeIf { it.isNotEmpty() }?.let { return it }
        val t = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            .joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
        p.edit().putString(DshEnv.KEY_FS_TOKEN, t).apply()
        return t
    }

    /** 把端口 + token 写进容器内配置文件，供 `dsh-fs` 读。 */
    private fun writeFsBridgeConfig(port: Int, token: String) {
        runCatching {
            DshEnv.fsBridgeConfig(appContext).parentFile?.mkdirs()
            DshEnv.fsBridgeConfig(appContext).writeText(
                "{\"port\":$port,\"token\":\"$token\"}",
                StandardCharsets.UTF_8,
            )
        }
    }

    /**
     * 把 `dsh-fs` / `dsh-native` CLI 写进容器（rootfs 就在 App 私有目录，直接落盘，
     * 不必 execRootfs heredoc）。
     *
     * 只在引导路径（bootstrap / reinstall）调用一次；CLI 内容不变时重复写无害。
     * 两个脚本都读同一份 fs-bridge.json，所以不需要各自的配置或 ROOTFS_REV 变更。
     */
    private fun ensureFsBridgeCli() {
        if (!DshEnv.isRuntimeInstalled(appContext)) return
        val bin = File(DshEnv.rootfs(appContext), "usr/local/bin")
        for ((name, script) in listOf(
            "dsh-fs" to FS_BRIDGE_CLI_SCRIPT,
            "dsh-native" to NATIVE_CLI_SCRIPT,
        )) {
            runCatching {
                val f = File(bin, name)
                f.parentFile?.mkdirs()
                f.writeText(script, StandardCharsets.UTF_8)
                f.setExecutable(true, false)
            }.onFailure { android.util.Log.w(TAG, "写 $name 失败: ${it.message}") }
        }
    }

    /** 启动回环桥（选空闲端口 + 写配置 + 监听）。文件端点与原生端点共用它。 */
    private fun startFsBridge() {
        runCatching {
            val fsPort = findFreePort(DshFsBridge.PORT_BASE)
            val fsToken = ensureFsToken()
            writeFsBridgeConfig(fsPort, fsToken)
            DshFsBridge.start(fsPort, fsToken, appContext)
            logInfo(R.string.dsh_log_bridge_up)
        }.onFailure { logWarn(R.string.dsh_log_bridge_failed, it.message ?: it.javaClass.simpleName) }
    }

    /** 强制重启 web 服务。 */
    fun restart() {
        scope.launch {
            bootMutex.withLock {
                stopServer()
                delay(500)
                if (checkPortConflict()) return@withLock
                startAndAwait()
            }
        }
    }

    /** 重新下载并安装运行时（覆盖 rootfs）。 */
    fun reinstallRuntime() {
        scope.launch {
            bootMutex.withLock {
                stopServer()
                // 重装只换 rootfs（运行时层）；dsh 数据在独立 dsh-home 里不会丢。这里仍
                // 重置预装记账：让内置插件再走一遍 install，确保它们还在（用户删掉的会补回）。
                prefs().edit()
                    .remove(DshEnv.KEY_SEEDED_PLUGINS)
                    .remove(@Suppress("DEPRECATION") DshEnv.KEY_SEED_PLUGINS_DONE)
                    .apply()
                downloadAndInstall()
                if (_state.value.phase != DshPhase.ERROR) {
                    setupResolvConf()
                    seedPlugins()
                    ensureFsBridgeCli()
                    if (checkPortConflict()) return@withLock
                    startAndAwait()
                }
            }
        }
    }

    private suspend fun downloadAndInstall() {
        clearLog()
        _state.update {
            it.copy(
                phase = DshPhase.DOWNLOADING,
                progress = 0f,
                speedBytesPerSec = 0L,
                message = str(R.string.dsh_msg_fetching_meta),
            )
        }
        if (DshSource.setting(appContext) == DshSource.SOURCE_AUTO) {
            logInfo(R.string.dsh_log_speedtest_start)
            val results = DshSource.speedTest()
            for (r in results.sortedBy { it.estimatedMs }) {
                val speed = if (r.speedKBps > 0.0) String.format("%.1f MB/s", r.speedKBps / 1024.0)
                else str(R.string.dsh_log_speedtest_untested)
                logInfo(R.string.dsh_log_speedtest_row, sourceName(r.source), r.latencyMs, speed)
            }
            logInfo(R.string.dsh_log_source_chosen, sourceName(DshSource.pickBest(results, appContext)))
        }
        logInfo(R.string.dsh_log_fetching_meta)
        val meta = fetchMeta()
        if (meta == null) {
            fail(str(R.string.dsh_err_meta_failed))
            return
        }
        // 架构必须先对上：自定义源可以指向任何 metadata.json，下错架构的 rootfs 要到
        // 启动 node 时才报 "Exec format error"，白下 130 MB 还看不懂错在哪。
        //
        // 判据是 [DshSource.runtimeArch]（= APK 里 proot 的真实架构），**不是**
        // `Build.SUPPORTED_ABIS.contains(...)`：带 arm 转译层的 x86_64 设备两个 ABI 都报，
        // 用 contains 判会把 arm64 的 rootfs 放行，而 proot 是 x86_64 原生二进制、
        // 不走转译层，执行 arm64 的 ld.so 直接 SIGILL（1.7.6 真机上就是这么炸的）。
        val want = DshSource.runtimeArch()
        if (meta.arch.isNotEmpty() && meta.arch != want) {
            fail(str(R.string.dsh_err_arch_mismatch, meta.arch, want))
            return
        }
        // 空间检查放在下载之前：rootfs 解压后约为压缩包的 3 倍，加上压缩包自身
        // 需要约 4 倍余量。等下载完再查等于白下 100 多 MB。
        if (meta.sizeBytes > 0) {
            val free = availableSpace(appContext.filesDir)
            val need = meta.sizeBytes * 4
            if (free in 1..need) {
                fail(str(R.string.dsh_err_no_space, free / 1024 / 1024, need / 1024 / 1024))
                return
            }
        }
        val tarball = DshEnv.downloadZip(appContext)
        logInfo(R.string.dsh_log_download_start, meta.version, meta.nodeVersion, meta.dsh)
        if (!downloadWithFallback(meta, tarball)) {
            fail(str(R.string.dsh_err_download_failed))
            return
        }
        logInfo(R.string.dsh_log_download_done, tarball.length() / 1024 / 1024)
        if (!verifySha256(tarball, meta.sha256)) {
            // 校验失败说明这份文件本身是坏的（续传时拼错、镜像给了旧包）——
            // 必须删掉，否则下次续传会一直基于这份坏数据往后接，永远校验不过。
            runCatching { tarball.delete() }
            fail(str(R.string.dsh_err_sha_mismatch))
            return
        }
        // 二次确认：metadata 里的 sizeBytes 可能与实际有偏差，用真实体积再算一次
        val free = availableSpace(appContext.filesDir)
        val need = (tarball.length() * 3.0).toLong()
        if (free in 1..need) {
            fail(str(R.string.dsh_err_no_space, free / 1024 / 1024, need / 1024 / 1024))
            return
        }
        logInfo(R.string.dsh_log_sha_ok)
        _state.update {
            it.copy(phase = DshPhase.EXTRACTING, progress = 0f, message = str(R.string.dsh_msg_installing))
        }
        val ok = withContext(Dispatchers.IO) { extractRootfs(tarball) }
        tarball.delete()
        if (!ok) {
            fail(str(R.string.dsh_err_extract_failed))
            return
        }
        logInfo(R.string.dsh_log_install_done)
        prefs().edit().putString(DshEnv.KEY_RUNTIME_VERSION, meta.version).apply()
        // phase 必须回 NOT_READY，不能直接置 STARTING：[bootstrap] 下一步就调
        // [startServer]，而它的防重入守卫会把 STARTING 当成「已经在启动了」直接
        // return——首次安装后服务永远起不来就是这个自我拦截造成的。
        _state.update {
            it.copy(
                phase = DshPhase.NOT_READY,
                installed = true,
                runtimeVersion = meta.version,
                progress = 1f,
                speedBytesPerSec = 0L,
                message = str(R.string.dsh_msg_runtime_ready),
            )
        }
        // 刚解压完，体积是全新的：后台量一次并落缓存，界面随 state 自动更新
        refreshRootfsSize()
    }

    private fun fetchMeta(): DshMeta? = runCatching {
        val conn = URL(DshSource.effectiveMetaUrl(appContext)).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.instanceFollowRedirects = true
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        DshMeta(
            version = json.optString("version", "unknown"),
            url = json.getString("url"),
            sha256 = json.optString("sha256", ""),
            sizeBytes = json.optLong("sizeBytes", 0L),
            mirrors = json.optJSONArray("mirrors")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                ?: emptyList(),
            arch = json.optString("arch", ""),
            dsh = json.optString("dsh", ""),
            nodeVersion = json.optString("nodeVersion", ""),
            builtAt = json.optString("builtAt", ""),
        )
    }.getOrNull()

    /**
     * 有没有更新的运行时可用。
     *
     * 判据是版本串**不相等**，不是语义比较：`0.1.1-rc.2-ubuntunoble-r2` 里混了
     * dsh 版本、ubuntu 代号和 rootfs 修订号，semver 比较对它没有意义；而任何一段
     * 变了都值得重装。r2 就是这么来的 —— 内容修了但 dsh 版本没动。
     *
     * @return 远端版本串（有更新时），null = 已是最新或查不到
     */
    suspend fun checkRuntimeUpdate(): String? = withContext(Dispatchers.IO) {
        if (!DshEnv.isRuntimeInstalled(appContext)) return@withContext null
        val meta = fetchMeta() ?: return@withContext null
        val local = prefs().getString(DshEnv.KEY_RUNTIME_VERSION, null).orEmpty()
        // 本地版本未知（早期版本装的，没记过）时不谎报有更新：重装要重下 150MB，
        // 不能靠猜就让用户付这个代价
        if (local.isEmpty()) return@withContext null
        if (meta.version == local) null else meta.version
    }

    /**
     * 逐个下载源尝试，第一个成功即返回。
     *
     * metadata 的 mirrors 本身就是加了代理前缀的 URL，再给它们叠一次前缀只会产生
     * 重复项 —— 去重前实测 6 个候选里有 3 个是重的，等于同一个失败的 URL 连试两次。
     */
    private fun downloadWithFallback(meta: DshMeta, target: File): Boolean {
        val prefix = DshSource.proxyPrefix(DshSource.resolve(appContext))
        val raw = listOf(meta.url) + meta.mirrors
        val candidates = (
            if (prefix.isEmpty()) raw
            else raw.map { if (it.startsWith("https://github.com/")) prefix + it else it } + raw
            ).distinct()
        for ((i, url) in candidates.withIndex()) {
            logInfo(R.string.dsh_log_source_try, i + 1, candidates.size, url)
            _state.update {
                it.copy(message = str(R.string.dsh_msg_downloading, meta.version), speedBytesPerSec = 0L)
            }
            if (downloadFile(url, target, meta.sizeBytes)) {
                logInfo(R.string.dsh_log_source_ok, i + 1)
                return true
            }
            logWarn(R.string.dsh_log_source_fail, i + 1)
        }
        return false
    }

    /**
     * 下载单个文件，支持断点续传。
     *
     * 续传逻辑本身住在 [DshDownloader]（APK 更新也用同一套）；这里只把进度接到
     * 运行时的状态与启动日志上。
     */
    private fun downloadFile(url: String, target: File, sizeBytes: Long): Boolean {
        var lastLoggedBucket = -1
        return DshDownloader.download(
            url = url,
            target = target,
            expectedSize = sizeBytes,
            onLog = { appendLog(it) },
            onProgress = { p ->
                if (p.contentLength <= 0) return@download
                val pctInt = p.percent
                // 日志每 5% 一行就够了，否则 400 行上限很快被下载进度占满
                if (pctInt / 5 > lastLoggedBucket) {
                    lastLoggedBucket = pctInt / 5
                    logInfo(R.string.dsh_log_downloading, pctInt, formatSpeed(p.speedBytesPerSec))
                }
                _state.update {
                    it.copy(
                        progress = p.fraction,
                        speedBytesPerSec = p.speedBytesPerSec,
                        message = str(R.string.dsh_msg_downloading_pct, pctInt, formatSpeed(p.speedBytesPerSec)),
                    )
                }
            },
        )
    }

    /** 解压 rootfs.tar.gz 到 filesDir/rootfs（整体替换）。 */
    private fun extractRootfs(tarball: File): Boolean = runCatching {
        val dest = DshEnv.rootfs(appContext)
        if (dest.exists()) dest.deleteRecursively()
        dest.mkdirs()
        TarGzipExtractor.extractRootfs(tarball, dest)
        // 关键文件自检：解压不完整（断流 / 空间耗尽）时越早发现越好，
        // 否则要等到启动 dsh web 才报一句看不懂的错。
        // File.exists() 跟随符号链接，所以 python3 -> python3.12 这类条目也一并验证了。
        val missing = listOf(
            "usr/bin/bash" to "bash",
            "usr/local/bin/node" to "node",
            "usr/local/lib/node_modules/@deepseek-ai/dsh/package.json" to "dsh",
        ).filterNot { File(dest, it.first).exists() }
        if (missing.isNotEmpty()) {
            logWarn(R.string.dsh_log_missing_after_extract, joinForLog(missing.map { it.second }))
            return@runCatching false
        }
        // 下面两个缺了不致命（插件装不了 / 无线 ADB 配不了，但 DSH 本身能跑），只记一行
        if (!File(dest, "usr/bin/python3").exists()) logWarn(R.string.dsh_log_missing_python)
        if (!File(dest, "usr/local/bin/pnpm").exists()) logWarn(R.string.dsh_log_missing_pnpm)
        true
    }.getOrElse {
        logWarn(R.string.dsh_log_extract_failed, "${it.javaClass.simpleName}: ${it.message}")
        false
    }

    /** 写容器 DNS（国内解析优先，谷歌/CF 兜底）。 */
    /**
     * 写入容器的 DNS 与 hosts。
     *
     * Android 不暴露 /etc/resolv.conf（DNS 走 netd 的 binder 接口），容器里的 glibc
     * 只会读文件，所以必须自己写一份，否则容器内所有域名解析都失败 —— 表现是
     * npm/pnpm/apt 全部 EAI_AGAIN。base rootfs 里的 resolv.conf 与 hosts 都是 0 字节。
     *
     * localhost 也得手动补：dsh web 绑 127.0.0.1，容器内插件回连 http://localhost:3080
     * 时没有这一行就解析不出来。
     */
    private fun setupResolvConf() {
        runCatching {
            // 安装/重装后强制重写：换网络环境后旧的 nameserver 可能已不可达
            File(DshEnv.rootfs(appContext), "etc/resolv.conf").delete()
        }
        ensureContainerDns()
    }

    /** 缺失或空文件才写（不覆盖用户自己改过的内容）。 */
    private fun ensureContainerDns() {
        runCatching {
            val rootfs = DshEnv.rootfs(appContext)
            if (!File(rootfs, "etc").isDirectory) return@runCatching
            val rc = File(rootfs, "etc/resolv.conf")
            if (rc.length() == 0L) {
                rc.writeText(
                    "nameserver 223.5.5.5\nnameserver 119.29.29.29\n" +
                        "nameserver 8.8.8.8\nnameserver 1.1.1.1\n",
                    StandardCharsets.UTF_8,
                )
            }
            val hosts = File(rootfs, "etc/hosts")
            if (hosts.length() == 0L) {
                hosts.writeText(
                    "127.0.0.1\tlocalhost\n::1\tlocalhost ip6-localhost ip6-loopback\n",
                    StandardCharsets.UTF_8,
                )
            }
        }
    }

    /** Android 的 uid/gid 分段偏移（AID_USER_OFFSET）。 */
    private const val AID_USER_OFFSET = 100000

    /**
     * 会被分配给应用进程的知名 AID → 名字。
     *
     * 取自 AOSP `android_filesystem_config.h`。只收「真的可能出现在应用补充组里」的那些：
     * 权限派生（INTERNET→inet、蓝牙、各种 sdcard/media）、以及所有应用共有的 everybody。
     * 表外的 gid 由 [androidGroupName] 兜底成 `aid_<gid>`，绝不留下无名 gid。
     */
    private val ANDROID_AIDS = mapOf(
        1007 to "log",
        1015 to "sdcard_rw",
        1023 to "media_rw",
        1028 to "sdcard_r",
        1033 to "sdcard_pics",
        1034 to "sdcard_av",
        1035 to "sdcard_all",
        1078 to "ext_data_rw",
        1079 to "ext_obb_rw",
        2000 to "shell",
        3001 to "net_bt_admin",
        3002 to "net_bt",
        3003 to "inet",
        3004 to "net_raw",
        3005 to "net_admin",
        3006 to "net_bw_stats",
        3007 to "net_bw_acct",
        9997 to "everybody",
        9998 to "misc",
        9999 to "nobody",
    )

    /**
     * 按 bionic 的规则把一个 Android gid 翻译成组名。
     *
     * 复刻 `bionic/libc/bionic/grp_pwd.cpp` 的 `getgrgid_internal` +
     * `print_app_name_from_gid`：先查知名 AID 表，再按分段推名字。分段与顺序都必须
     * 与上游一致 —— 共享 gid（50000+）要在缓存 gid 之前判，否则 50054 会被算成
     * `u0_a20054_cache` 之类的胡话。
     *
     * 一处刻意的简化：上游对 2900–2999 / 5000–5999 的 OEM 段会给出 `oem_<id>`，
     * 这里一律落到 `aid_<gid>`。这个段不会出现在应用进程的补充组里（它是给
     * OEM 自己的原生服务用的），而给不出名字才是问题，名字风格不是。
     */
    private fun androidGroupName(gid: Int): String {
        val userId = gid / AID_USER_OFFSET
        val appId = gid % AID_USER_OFFSET
        return when {
            appId >= 90000 -> "u${userId}_i${appId - 90000}"
            userId == 0 && appId in 50000..59999 -> "all_a${appId - 50000}"
            appId in 40000..49999 -> "u${userId}_a${appId - 40000}_ext_cache"
            appId in 30000..39999 -> "u${userId}_a${appId - 30000}_ext"
            appId in 20000..29999 -> "u${userId}_a${appId - 20000}_cache"
            appId >= 10000 -> "u${userId}_a${appId - 10000}"
            else -> {
                val known = ANDROID_AIDS[appId]
                when {
                    known == null -> "aid_$gid"
                    userId == 0 -> known
                    else -> "u${userId}_$known"
                }
            }
        }
    }

    /** 本进程的补充组（读 /proc/self/status 的 Groups 行）。 */
    private fun supplementaryGids(): List<Int> = runCatching {
        File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("Groups:") }
            ?.removePrefix("Groups:")
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.mapNotNull { it.toIntOrNull() }
            ?: emptyList()
    }.getOrDefault(emptyList())

    /**
     * 把本进程的 Android 补充组补进容器的 `/etc/group`。
     *
     * 为什么要做：proot 的 `-0` 只伪造 uid/gid 0，**不拦 getgroups()**，所以容器里看到的
     * 补充组是宿主应用进程真实的那几个 Android gid（3003=inet、9997=everybody、
     * 20054=本应用的 cache gid、50054=本应用的 shared gid）。容器 glibc 在 /etc/group
     * 里查不到它们，于是每次调用 `groups` 都往 stderr 刷一串
     * `groups: cannot find name for group ID 3003`。
     *
     * 而 Ubuntu 的 `/etc/bash.bashrc` 里有一段 sudo 提示，无条件跑 `case " $(groups) " in`
     * （见 bash 包的 debian/etc.bash.bashrc）—— 于是每开一次终端就先糊四行警告。
     * 用户看到的就是这个。
     *
     * 为什么在运行期做而不是打进 rootfs：cache/shared gid 由本应用的 uid 派生
     * （appid + 20000 / 50000），每次安装都可能不同，构建期不可能知道。也因此不必动
     * [ROOTFS_REV] —— 这个函数在每条 exec 路径上都会跑一遍，存量用户下次进终端就好了。
     *
     * 顺带写一个 `/root/.hushlogin`：那段 sudo 提示对本容器毫无意义（rootfs 里没装
     * sudo，而且永远是 root），跳过它就少一次 fork+exec。两道措施互不依赖 —— 补组是
     * 为了让用户真的敲 `groups`、`id -Gn` 时也不报错，hushlogin 只挡开场那一次。
     *
     * 只追加缺的行，不动已有内容：用户自己加过的组要保住。
     */
    private fun ensureContainerGroups() {
        runCatching {
            val rootfs = DshEnv.rootfs(appContext)
            val f = File(rootfs, "etc/group")
            if (!File(rootfs, "etc").isDirectory) return@runCatching
            // base rootfs 里这个文件必然存在且非空；真要是空的就别写，
            // 只补几个 Android gid 而丢掉 root/sudo 等基础组会更糟
            if (f.length() == 0L) return@runCatching

            val existing = f.readLines()
            val haveGid = existing.mapNotNull {
                it.split(':').getOrNull(2)?.trim()?.toIntOrNull()
            }.toHashSet()
            val haveName = existing.mapNotNull {
                it.split(':').firstOrNull()?.trim()?.takeIf { n -> n.isNotEmpty() }
            }.toHashSet()

            val add = StringBuilder()
            for (gid in supplementaryGids().distinct().sorted()) {
                if (gid in haveGid) continue
                val name = androidGroupName(gid)
                // 名字撞了就退回 aid_<gid>：/etc/group 里重名会让 getgrnam 取到错的那条
                val unique = if (name in haveName) "aid_$gid" else name
                if (unique in haveName) continue
                haveName += unique
                haveGid += gid
                add.append("$unique:x:$gid:\n")
            }
            if (add.isEmpty()) return@runCatching

            // 补尾部换行：base 文件末尾没有 \n 时直接追加会把两条粘成一行
            val needsNewline = f.readText(StandardCharsets.UTF_8).let {
                it.isNotEmpty() && !it.endsWith("\n")
            }
            java.io.FileOutputStream(f, true).bufferedWriter(StandardCharsets.UTF_8).use { w ->
                if (needsNewline) w.write("\n")
                w.write(add.toString())
            }
            logInfo(R.string.dsh_log_groups_added, joinForLog(add.toString().trim().lines()))
        }.onFailure { android.util.Log.w(TAG, "补 /etc/group 失败: ${it.message}") }
    }

    /**
     * 让容器内的 pnpm 用「复制」而不是「硬链接」从内容存储装包。
     *
     * 为什么必须这么做：pnpm 用 `link()` 把 CAS 里的文件装进 node_modules，而
     * proot/proroot 的 `--link2symlink` 会把它改写成符号链接（见 [linkBecomesSymlink]）。
     * Node 的 `require.resolve` 默认 realpath，于是
     * `node_modules/<pkg>/package.json` 解析成 `<store>/files/<xx>/<hash>`，
     * dsh 再按 `join(dirname(pkgPath), "./lib/client.cjs")` 拼客户端包路径，
     * 得到 `<store>/files/<xx>/lib/client.cjs` —— CAS 里只有扁平的哈希文件，
     * 没有 lib/ 目录，必然 ENOENT。真机表现是任何带 `exports["./client"]` 的插件
     * 装完就让 `dsh web` 起不来（MissingClientBundleError）。
     *
     * 选 copy 而不是设法让硬链接可用：真机探测报的是
     * `AccessDeniedException`（系统不允许在 App 私有目录建硬链接），不在 App 能改的
     * 范围内。copy 绕开整个链接语义，代价是 CAS 去重失效、rootfs 变大。
     *
     * **写 pnpm-workspace.yaml 而不是 .npmrc**：pnpm 11 起这类 pnpm 专有设置已从
     * `.npmrc` 迁到 `pnpm-workspace.yaml`。实测 `pnpm config get package-import-method`
     * 对 .npmrc 里的同名项返回 undefined，而 workspace 里的 `packageImportMethod`
     * 返回 copy（.npmrc 本身没失效 —— 同文件里的 registry= 照样生效）。v1.3 写
     * .npmrc 那一版因此完全没生效。
     *
     * **只在文件已存在时追加**：dsh 的 `initProfile` 见到文件存在就不写自己的模板
     * （dsh-app-boot 里 `if (!existsSync(workspacePath))`），抢先创建会把
     * `nodeLinker: hoisted` 与 `autoInstallPeers: false` 弄丢。profile 尚未初始化时
     * 靠安装命令上的 `--package-import-method copy` 兜住那一次。
     *
     * 用户显式写过这一项时**不覆盖** —— 显式配置优先于我们的推断。
     */
    private fun ensureProfilePnpmSettings() {
        runCatching { removeLegacyNpmrcImportLine() }
        if (!linkBecomesSymlink()) return
        runCatching {
            val ws = File(DshEnv.dshHome(appContext), "$PROFILE_WEB_REL/pnpm-workspace.yaml")
            // 不存在就不建：见 KDoc，抢在 dsh initProfile 之前会弄丢它的模板
            if (!ws.isFile) return@runCatching
            val old = ws.readText(StandardCharsets.UTF_8)
            if (old.lineSequence().any { it.trimStart().startsWith(PNPM_IMPORT_KEY) }) return@runCatching
            val sep = if (old.isEmpty() || old.endsWith("\n")) "" else "\n"
            // 追加顶层键而不是解析重写整个 YAML：这文件里还有 minimumReleaseAgeExclude、
            // allowBuilds 等结构化内容，字符串追加最不容易弄坏别人的配置
            ws.writeText(old + sep + PNPM_IMPORT_LINE + "\n", StandardCharsets.UTF_8)
            android.util.Log.i(TAG, "已写入 profile pnpm 设置: $PNPM_IMPORT_LINE")
        }
    }

    /**
     * 清掉 v1.3 写进 `/root/.npmrc` 的那行 —— 它已知无效（pnpm 11 不读），
     * 留着只会在排查时误导。只删精确匹配的那一行，用户自己写的其它行一律不碰；
     * 文件因此变空就把文件删掉。
     */
    private fun removeLegacyNpmrcImportLine() {
        val rc = File(DshEnv.rootfs(appContext), "root/.npmrc")
        if (!rc.isFile) return
        val old = rc.readText(StandardCharsets.UTF_8)
        if (!old.contains(NPMRC_LEGACY_LINE)) return
        val kept = old.lineSequence().filter { it.trim() != NPMRC_LEGACY_LINE }.toList()
        if (kept.all { it.isBlank() }) {
            rc.delete()
        } else {
            rc.writeText(kept.joinToString("\n").trimEnd() + "\n", StandardCharsets.UTF_8)
        }
        android.util.Log.i(TAG, "已清理无效的 npmrc 行: $NPMRC_LEGACY_LINE")
    }

    /**
     * pnpm 实际读到的导入方式，供启动日志用。
     *
     * 读文件而不是回答「我们写过没有」：v1.3 的教训正是日志宣称了一件没生效的事。
     */
    private fun pnpmImportMethodLine(): String {
        val ws = File(DshEnv.dshHome(appContext), "$PROFILE_WEB_REL/pnpm-workspace.yaml")
        if (!ws.isFile) return str(R.string.dsh_log_pnpm_unconfigured)
        val line = runCatching {
            ws.readLines().firstOrNull { it.trimStart().startsWith(PNPM_IMPORT_KEY) }
        }.getOrNull()
            ?: return str(R.string.dsh_log_pnpm_default)
        val value = line.substringAfter(':', "").trim().ifEmpty { "?" }
        return str(R.string.dsh_log_pnpm_from_profile, value)
    }

    /**
     * 把包名写进 profile `pnpm-workspace.yaml` 的 `allowBuilds`，放行它们的构建脚本。
     *
     * pnpm 11 默认不执行依赖的 install/postinstall/prepare 脚本，且**直接失败**
     * （ERR_PNPM_IGNORED_BUILDS，退出码 1），官方出路是交互式 `pnpm approve-builds` ——
     * 在容器里没有 TTY，跑不了。所以由这里代写配置。
     *
     * 格式是**映射**而不是列表，已实测：
     * ```yaml
     * allowBuilds:
     *   esbuild: true
     * ```
     * 写成 `- esbuild` 会被 pnpm 改写成 `'0': esbuild`，等于没放行。
     *
     * 还有一个坑：pnpm 失败时会**自己**往文件里塞
     * `esbuild: set this to true or false` 这样的占位行。只看「键在不在」会把它
     * 当成已放行而跳过，于是重试仍然失败（本地实测踩到过）。所以这里按**值**判断，
     * 只认 `true`，占位行原地改写。
     *
     * 做的是有针对性的合并，不是整文件重写：文件里还有 packageImportMethod、
     * minimumReleaseAgeExclude 等别人的配置，解析后重新序列化会丢注释与顺序。
     *
     * @return 这次真正放行的包名（本来就是 true 的不算）
     */
    fun allowProfileBuilds(packages: List<String>, onLine: (String) -> Unit = {}): List<String> {
        if (packages.isEmpty()) return emptyList()
        val ws = File(DshEnv.dshHome(appContext), "$PROFILE_WEB_REL/pnpm-workspace.yaml")
        if (!ws.isFile) {
            onLine("[DSH-Folk] 找不到 $PROFILE_WEB_REL/pnpm-workspace.yaml，无法放行构建脚本")
            return emptyList()
        }
        return runCatching {
            val lines = ws.readText(StandardCharsets.UTF_8).lines().toMutableList()
            val blockAt = lines.indexOfFirst { it.trimEnd() == "$PNPM_ALLOW_BUILDS_KEY:" }

            // 块内已有的键：值为 true 才算放行，其余（pnpm 的占位串）记下行号待改写
            val allowed = mutableSetOf<String>()
            val placeholders = mutableMapOf<String, Int>()
            if (blockAt >= 0) {
                for (i in (blockAt + 1) until lines.size) {
                    val raw = lines[i]
                    if (raw.isBlank()) continue
                    // 回到顶层缩进即块结束
                    if (!raw.startsWith(" ") && !raw.startsWith("\t")) break
                    val body = raw.trim()
                    val colon = body.indexOf(':')
                    if (colon <= 0) continue
                    val key = body.substring(0, colon).trim().trim('\'', '"')
                    val value = body.substring(colon + 1).trim()
                    if (value == "true") allowed += key else placeholders[key] = i
                }
            }

            val want = packages.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val todo = want.filter { it !in allowed }
            if (todo.isEmpty()) return@runCatching emptyList()

            // 包名带 @ 或斜杠时必须加引号，否则 YAML 解析会走偏
            fun entry(p: String) = "  '${p.replace("'", "''")}': true"
            val insert = mutableListOf<String>()
            for (p in todo) {
                val at = placeholders[p]
                if (at != null) lines[at] = entry(p) else insert += entry(p)
            }
            if (insert.isNotEmpty()) {
                if (blockAt >= 0) {
                    lines.addAll(blockAt + 1, insert)
                } else {
                    if (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
                    lines += "$PNPM_ALLOW_BUILDS_KEY:"
                    lines += insert
                }
            }
            ws.writeText(lines.joinToString("\n").trimEnd() + "\n", StandardCharsets.UTF_8)
            android.util.Log.i(TAG, "已放行构建脚本: ${todo.joinToString(", ")}")
            onLine("[DSH-Folk] " + str(R.string.dsh_log_builds_allowed, joinForLog(todo)))
            todo
        }.getOrElse {
            android.util.Log.e(TAG, "写 allowBuilds 失败", it)
            onLine(
                "[DSH-Folk] " + str(
                    R.string.dsh_log_builds_allow_failed,
                    it.message ?: it.javaClass.simpleName,
                )
            )
            emptyList()
        }
    }

    // ────────────────────────── web 服务 ──────────────────────────

    /**
     * 启动 `dsh web`。
     *
     * `--expose-internals` 必带：dsh 的 profile-boot 会无条件创建 cordis-plugin-hmr，
     * 那个插件第一行就检查 loader.internal，缺这个标志会把整个启动带崩；
     * 而 NODE_OPTIONS 传不了它（node 明确拒绝），只能作为命令行参数。
     *
     * 默认端口 3080 不显式传 --port，避免 commander 的 'argument missing'。
     * 服务默认只绑 127.0.0.1；开启「局域网访问」后追加 --host 0.0.0.0。
     * 鉴权由 dsh 自己的登录页负责。
     */
    fun startServer() {
        // 守卫只看真实进程，不看 phase。看 phase 会把上一步（例如安装完成）
        // 自己写下的 STARTING 误当成「已有人在启动」。
        if (serverProcess?.isAlive == true) return
        if (!DshEnv.isRuntimeInstalled(appContext)) {
            _state.update { it.copy(phase = DshPhase.NOT_READY, message = str(R.string.dsh_msg_not_installed)) }
            return
        }
        DshEnv.dshHome(appContext).mkdirs()
        DshEnv.tmpDir(appContext).mkdirs()
        DshEnv.serverLog(appContext).parentFile?.mkdirs()
        // 每次起服务都过一遍：既刷新宿主事实（用户可能在别处改了能力开关或系统通知
        // 权限），也自愈「patch 行在、插件文件没了」——那种状态下 dsh 会因为一个
        // entry 加载失败而整棵树起不来，而 restart() 不走 bootstrap，否则没人修。
        DshHostPrompt.ensureInstalled(appContext)
        clearLog()

        val port = port()
        val lan = lanEnabled()
        val opts = buildString {
            if (port != DshEnv.DEFAULT_PORT) append(" --port $port")
            if (lan) append(" --host 0.0.0.0")
        }
        val cmd = buildString {
            append("export DSH_HOME=/root/.dsh && ")
            // 不让 dsh 拉系统浏览器：我们用 Intent 打开 WebUI
            append("export BROWSER=true && ")
            append("mkdir -p /root/workspace 2>/dev/null; ")
            append("cd /root/workspace && ")
            append("if command -v dsh >/dev/null 2>&1 && test -f \"\$(command -v dsh)\"; then ")
            // dsh 是 wrapper，先 readlink 出真正的 bin.js 再交给 node（才能带 --expose-internals）
            append("DSH_REAL=\$(readlink -f \"\$(command -v dsh)\" 2>/dev/null || command -v dsh); ")
            append("exec node --expose-internals \"\$DSH_REAL\" web" + opts + " 2>&1; ")
            // 这行在容器里由 shell 输出，会被 forwardOutput 原样转进日志。文案取自
            // 资源（跟随应用语言），单引号要转义成 shell 能接受的形式。
            append("else echo '[DSH-Folk] " + shellSingleQuoted(str(R.string.dsh_log_dsh_missing_in_container)) + "'; exit 1; fi")
        }

        logInfo(R.string.dsh_log_runtime_mode, runtime().displayName())
        // 架构写进日志：x86_64 + arm 转译设备上「装了哪个包 / 用了哪份 rootfs」是首要排障线索
        logInfo(
            R.string.dsh_log_arch,
            DshSource.runtimeArch(),
            android.os.Build.SUPPORTED_ABIS.joinToString("/"),
        )
        logInfo(R.string.dsh_log_hardlink, hardlinkLogLine())
        logInfo(R.string.dsh_log_pnpm_import, pnpmImportMethodLine())
        logInfo(R.string.dsh_log_starting_web, port)
        if (lan) {
            val ip = lanIp()
            if (ip != null) {
                logInfo(R.string.dsh_log_lan_on, ip, port)
            } else {
                logInfo(R.string.dsh_log_lan_on_no_ip)
            }
        } else {
            logInfo(R.string.dsh_log_lan_off)
        }

        serverProcess = try {
            execRootfs(cmd)
        } catch (e: Exception) {
            // 不能把上一轮已死的进程留在字段里：[awaitReady] 会把它读成
            // 「进程已退出」，盖掉真正的启动失败原因。
            serverProcess = null
            val detail = str(R.string.dsh_err_start_failed, e.message ?: e.javaClass.simpleName)
            appendLog("! $detail")
            if (runtimeId() == "proroot" && noteProrootFailure(detail)) {
                logInfo(R.string.dsh_log_switched_to_proot)
            }
            _state.update { it.copy(phase = DshPhase.ERROR, message = detail) }
            return
        }
        forwardOutput(serverProcess)
        startFsBridge()
        startedAt = System.currentTimeMillis()
        _state.update { it.copy(phase = DshPhase.STARTING, port = port, message = str(R.string.dsh_msg_starting)) }
    }

    /** 逐行转发容器输出到日志（node 重定向到文件是块缓冲，不逐行读日志面板会空白）。 */
    private fun forwardOutput(proc: Process?) {
        if (proc == null) return
        val log = LogStore.named(DshEnv.serverLog(appContext))
        scope.launch {
            val reader = proc.inputStream.bufferedReader()
            try {
                for (line in reader.lineSequence()) log.append(line)
            } catch (_: Exception) {
                // 进程被销毁时读流中断，属预期
            } finally {
                log.flushForExit()
                runCatching { reader.close() }
            }
        }
    }

    /**
     * 轮询直到服务真能响应 HTTP 或超时。
     *
     * 只探 TCP 端口是不够的：端口被别的进程占着（上一次没退干净、用户自己在容器里
     * 跑了东西）也会 connect 成功，于是首页显示「已就绪」，点开却是别人的页面或直接
     * 连不上。这里在端口通之后再要一次 HTTP 响应 —— 任何状态码都算（dsh 未登录时
     * 返回登录页，403 也证明是它在服务）。
     */
    private suspend fun awaitReady() {
        // 没有进程就没什么可等。不能依赖下面那句
        // `serverProcess?.isAlive == false` —— serverProcess 为 null 时它是 false
        // （null == false），于是「压根没启动」会一声不响地等到超时。
        // [startServer] 已经报错的情况下直接返回，否则这里的超时/无进程
        // 文案会盖掉那个更具体的原因。
        if (_state.value.phase == DshPhase.ERROR) return
        if (serverProcess == null) {
            val detail = str(R.string.dsh_err_no_process)
            appendLog("! $detail")
            _state.update { it.copy(phase = DshPhase.ERROR, message = detail) }
            return
        }
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (isPortInUse(port()) && httpResponds(port())) {
                _state.update {
                    it.copy(
                        phase = DshPhase.RUNNING,
                        progress = 1f,
                        message = str(R.string.dsh_msg_service_ready, it.webUrl),
                    )
                }
                logInfo(R.string.dsh_log_ready, port())
                prefs().edit().putInt(DshEnv.KEY_PROROOT_FAIL, 0).apply()
                return
            }
            if (serverProcess?.isAlive == false) {
                val base = str(R.string.dsh_err_process_exited)
                appendLog("! $base")
                if (runtimeId() == "proroot" && noteProrootFailure(base)) {
                    logInfo(R.string.dsh_log_switched_to_proot)
                }
                // 插件树失败时给出可执行的下一步，而不是只说「进程已退出」
                val detail = pluginTreeFailureHint() ?: base
                _state.update { it.copy(phase = DshPhase.ERROR, message = detail) }
                return
            }
            delay(1_000)
        }
        // 超时同样算 proroot 一次失败：进程没退但服务始终起不来（proroot 在部分内核上
        // 卡在 seccomp/ptrace 上就是这种表现），只记「进程退出」那一路会让用户永远
        // 卡在坏运行时上，自动回退 proot 的兜底形同不存在。
        val detail = str(R.string.dsh_err_start_timeout)
        appendLog("! $detail")
        if (runtimeId() == "proroot" && noteProrootFailure(detail)) {
            logInfo(R.string.dsh_log_switched_to_proot)
        }
        _state.update { it.copy(phase = DshPhase.ERROR, message = detail) }
    }

    /**
     * 回环 HTTP 是否有响应。
     *
     * 任何 HTTP 状态码都算就绪 —— dsh 未登录时返回登录页（200）或 403，都说明服务在跑。
     * 只有连不上 / 不是 HTTP / 超时才算没起来。
     */
    private fun httpResponds(port: Int): Boolean = runCatching {
        val conn = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 1_500
            conn.readTimeout = 2_500
            conn.instanceFollowRedirects = false
            conn.responseCode > 0
        } finally {
            runCatching { conn.disconnect() }
        }
    }.getOrDefault(false)

    /** 停止 web 服务：先杀容器内的 dsh web，再销毁 proot 进程。 */
    fun stopServer() {
        runCatching {
            // proot 不隔离 PID，容器内 /proc 看到宿主全部进程 —— 只杀 bin.js web / dsh web，
            // 绝不裸 pkill -f node（会误杀 agent 或用户自己的 node 进程）。
            //
            // 必须排除自己：这条清理命令的 cmdline 里就含 'bin.js web' 字面量，pgrep -f 会
            // 把执行它的 bash、以及 $() 命令替换 fork 出的子 shell 一起匹配上（子 shell 与
            // 父进程共享 cmdline，所以比对 PID 挡不住它）。做法是在命令里埋一个哨兵字符串，
            // 再按 /proc/<pid>/cmdline 把带哨兵的进程全部跳过 —— 否则 kill -KILL 会先把这条
            // 清理命令自己杀掉，真正的 dsh web 反而留着。
            execRootfs(
                ": DSHFOLK_STOP_SENTINEL; " +
                "_kill() { for _p in " +
                "\$(pgrep -f 'bin.js web' 2>/dev/null; pgrep -f 'dsh web' 2>/dev/null); do " +
                "grep -qa DSHFOLK_STOP_SENTINEL \"/proc/\$_p/cmdline\" 2>/dev/null && continue; " +
                "kill \"\$1\" \"\$_p\" 2>/dev/null; done; }; " +
                "_kill -TERM; sleep 1; _kill -KILL"
            ).waitFor(8, TimeUnit.SECONDS)
        }
        runCatching { serverProcess?.destroyForcibly() }
        serverProcess = null
        startedAt = 0L
        DshFsBridge.stop()
        _state.update { it.copy(phase = DshPhase.NOT_READY, pid = null, message = str(R.string.dsh_msg_stopped)) }
    }

    fun uptimeMillis(): Long = if (startedAt == 0L) 0L else System.currentTimeMillis() - startedAt

    // ────────────────────────── 日志 ──────────────────────────

    fun tailLog(lines: Int = 200): String =
        if (::appContext.isInitialized) LogStore.named(DshEnv.serverLog(appContext)).tail(lines) else ""

    fun clearLog() {
        if (::appContext.isInitialized) LogStore.named(DshEnv.serverLog(appContext)).clear()
    }

    fun appendLog(line: String) {
        if (::appContext.isInitialized) LogStore.named(DshEnv.serverLog(appContext)).append(line)
    }

    /**
     * 硬链接状态的日志行，**带上探测失败的原因**。
     *
     * 只写结论是不够的：App 私有目录本该支持硬链接，报「不支持」是反常的，
     * 而 bugreport 里的 logcat 只覆盖最近几分钟、抓不到启动时那条 Log.i。
     * 把原因写进 dsh.log 才能在下一份 bugreport 里直接看到 —— 真机上就是靠这条
     * 才拿到 `AccessDeniedException`（系统不允许在 App 私有目录建硬链接）。
     *
     * 这里**不再**声称 pnpm 的导入方式：那是 [pnpmImportMethodLine] 的事，它读
     * 真实配置。v1.3 在这行里写「pnpm 已切为 copy 导入」，而那一版的配置压根没生效。
     */
    private fun hardlinkLogLine(): String {
        val ok = hardlinkSupported()
        val detail = hardlinkDetail()
        return buildString {
            append(str(if (ok) R.string.dsh_log_hardlink_yes else R.string.dsh_log_hardlink_no))
            if (detail.isNotEmpty()) append("（$detail）")
            append(" · ")
            append(
                when {
                    // proroot 无条件加 --link2symlink，探测结果在它下面不代表 guest 的实际能力。
                    // 用 effectiveRuntimeId：x86_64 上「选了 proroot」但跑的是 proot，
                    // 说成「proroot 无条件启用 l2s」与同一份日志第一行自相矛盾。
                    effectiveRuntimeId() == "proroot" -> str(R.string.dsh_log_hardlink_l2s_forced)
                    !ok -> str(R.string.dsh_log_hardlink_l2s_on)
                    else -> str(R.string.dsh_log_hardlink_l2s_off)
                }
            )
        }
    }

    private fun fail(message: String) {
        appendLog("! $message")
        _state.update { it.copy(phase = DshPhase.ERROR, message = message, speedBytesPerSec = 0L) }
    }

    /**
     * 取本地化字符串。
     *
     * 用 [me.bmax.apatch.util.appString] 而不是 `appContext.getString`：应用内语言在
     * API 33 以下只改 Activity 的 Configuration，Application 的 Context 仍然解析成
     * **系统语言**。少了这一层，界面切成英文之后首页状态和启动日志依旧是中文
     * （风味语言那几套皮更是一行都不会出现，它们永远不是系统语言）。
     */
    private fun str(resId: Int, vararg args: Any): String =
        if (::appContext.isInitialized) appContext.appString(resId, *args) else ""

    /**
     * 写一行「进展」日志（`>` 前缀）。
     *
     * 与 [appendLog] 分开的意义：调用方只提供资源 id 与参数，不再拼中文字面量 ——
     * 日志同时进首页日志卡和 bugreport 的 dsh.log，两处都该跟随应用语言。
     */
    private fun logInfo(resId: Int, vararg args: Any) = appendLog("> " + str(resId, *args))

    /** 写一行「异常」日志（`!` 前缀）。 */
    private fun logWarn(resId: Int, vararg args: Any) = appendLog("! " + str(resId, *args))

    /**
     * 把若干项连成一句里的列表。
     *
     * 原来一律用「、」硬拼，那是中文的顿号 —— 英文界面下会看到 `sharp、tesseract.js`。
     * ICU 的 ListFormatter 按语言给出正确的连接词与标点（en: "a, b, and c"，
     * zh: "a、b和c"，es: "a, b y c"），API 24 就有，不需要自己维护一张表。
     */
    private fun joinForLog(items: Collection<String>): String {
        val clean = items.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return ""
        return runCatching {
            android.icu.text.ListFormatter.getInstance(LocaleCtx.currentLocale()).format(clean)
        }.getOrElse { clean.joinToString(", ") }
    }

    /**
     * 下载源的本地化名字。
     *
     * 走 [DshSource.labelRes] —— 与设置页、更新对话框同一份映射。日志此前用的是
     * DshSource 里一份硬编码中文的 displayName()，现在那个函数已经不存在了。
     */
    private fun sourceName(source: String): String = str(DshSource.labelRes(source))

    /**
     * 让一段文本能安全地放进 shell 的单引号里。
     *
     * 这段文本现在来自资源，翻译里出现 `'` 完全正常（英文 "don't"、法语 "l'app"），
     * 而一个裸单引号会提前闭合字符串、把后面的内容变成 shell 代码。`'\''` 是 POSIX 里
     * 唯一可靠的写法：闭合、插入转义的引号、再打开。
     */
    private fun shellSingleQuoted(s: String): String = s.replace("'", "'\\''")

    // ────────────────────────── 工具 ──────────────────────────

    fun formatSpeed(bytesPerSec: Long): String = when {
        bytesPerSec <= 0 -> "…"
        bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / 1024.0 / 1024.0)
        bytesPerSec >= 1024 -> String.format("%.0f KB/s", bytesPerSec / 1024.0)
        else -> "$bytesPerSec B/s"
    }

    private fun verifySha256(file: File, expected: String): Boolean {
        // metadata 里没给 sha256 时不拦（早期 runtime 发布没有这一项）
        if (expected.isBlank()) return true
        return DshDownloader.sha256(file).equals(expected, ignoreCase = true)
    }

    private fun availableSpace(dir: File): Long = runCatching {
        dir.mkdirs()
        StatFs(dir.absolutePath).availableBytes
    }.getOrDefault(-1L)

    /**
     * 运行时占用统计（设置页存储信息用）。
     *
     * **别在组合期调用**：它递归遍历整个 rootfs。首页要显示体积请读
     * `state.rootfsSizeBytes`（缓存值），需要刷新走 [refreshRootfsSize]。
     */
    fun rootfsSizeBytes(): Long = if (!ready) 0L else dirSize(DshEnv.rootfs(appContext))

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.listFiles()?.sumOf { dirSize(it) } ?: 0L
    }
}
