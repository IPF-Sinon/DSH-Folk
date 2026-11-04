#!/usr/bin/env node
// 门禁：chroot 启动脚本的「挂载视图 / 清理 / 占位符替换 / 失败回退」不许被改坏。
//
// 背景（issue #12 + 外部审查 P0-2/P0-3，均已核对为真）：
//  1. 原实现在 filesDir/chroot/mnt 上挂 /dev、/proc、/sys 与存储/工作区，最后却
//     `exec chroot "$RFS"` —— 挂载点全在 rootfs 之外，容器里根本看不到
//     （真机表现：新建工作区后列目录 ENOENT）；
//  2. `exec chroot` 会把脚本进程替换掉，`trap cleanup EXIT` 永远不执行：
//     挂载泄漏，而且 chroot 写出的 root 属主文件不会修回 App uid
//     —— 切回 proot 直接 EACCES（`/root/.dsh/profiles/web/cordis.patch.yml`）；
//  3. 脚本内容放在 Kotlin 字符串里逐行 append，引号/`$` 极易写错；issue #12 的
//     `launch.sh[N]: no closing quote` 就是这么来的，而且 shell 报的行号是
//     「最后一行 +1」，把排查带偏三轮。
//
// 现在的形态：脚本模板是 assets 里的普通 shell 文件（本地/CI 可 `sh -n`），
// Kotlin 只做字面 replace。这个门禁同时盯模板与替换代码。
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const errors = [];
const root = path.resolve(__dirname, '..');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');
const must = (c, m) => { if (!c) errors.push(m); };

const ASSET_REL = 'app/src/main/assets/chroot/launch.sh';
const assetPath = path.join(root, ASSET_REL);
must(fs.existsSync(assetPath), '缺 ' + ASSET_REL + '（chroot 启动脚本模板）');
const template = fs.existsSync(assetPath) ? fs.readFileSync(assetPath, 'utf8') : '';
const runtime = read('app/src/main/java/me/bmax/apatch/dsh/ContainerRuntime.kt');
const dsh = read('app/src/main/java/me/bmax/apatch/dsh/DshRuntime.kt');

// ---- 1. 模板：语法 + 结构 ----
if (template) {
  // 注释行要剔除后再做「不许出现 XXX」这类判断：抬头注释里正是在解释
  // 「为什么不能再写 exec chroot / chroot "$RFS"」，按整份文件查会误报。
  const code = template.split('\n')
    .filter((l) => !l.trim().startsWith('#'))
    .join('\n');
  const syn = spawnSync('sh', ['-n', assetPath], { encoding: 'utf8' });
  must(syn.status === 0,
    '模板 shell 语法不过（sh -n 退出 ' + syn.status + '）：' + (syn.stderr || '').trim().slice(0, 200));

  must(/^#!\/system\/bin\/sh/m.test(template), '模板缺 #!/system/bin/sh');
  must(/chroot "\$MNT" \/usr\/bin\/env -i @ENV@ "\$@"/.test(template),
    '模板没有 chroot 到 $MNT（挂载点必须落在 chroot 的根里面）');
  must(!/exec chroot/.test(code),
    '模板又用了 `exec chroot`：exec 会让 trap 永远不执行（挂载泄漏 + 属主不修回）');
  must(!/chroot "\$RFS"/.test(code), '模板还在 chroot "$RFS"（挂载点会全落在 rootfs 之外）');
  must(/^\s*mount --bind "\$RFS" "\$MNT" \|\| exit 91$/m.test(template),
    '模板缺 mount --bind "$RFS" "$MNT"（整行匹配，别只匹配到前缀）');
  must(/CREATED_MNT=0/.test(template) && /if \[ "\$CREATED_MNT" = 1 \]; then/.test(template),
    '模板缺 CREATED_MNT 判据：子命令退出时会把正在跑的容器的挂载拆掉');
  must(/-d "\$MNT\/usr\/local\/lib\/node_modules\/@deepseek-ai\/dsh"/.test(template),
    '模板缺「残留挂载是否还指向当前 rootfs」的判据（运行时更新后会跑在旧 rootfs 上）');
  must(/restore_ownership\(\) \{/.test(template), '模板缺 restore_ownership()');
  must(/chown -R "\$APP_UID:\$APP_GID"/.test(template), 'restore_ownership 没有 chown 回 App uid');
  must(/find "\$RFS\/root" ! -user "\$APP_UID"/.test(template),
    'restore_ownership 缺 find 短路判据（每次启动都无条件递归 chown 太贵）');
  // cleanup 里必须先卸载、再修属主；进入前也要修一次
  const cleanupBody = (template.match(/cleanup\(\) \{[\s\S]*?\n\}/) || [''])[0];
  must(/umount_all/.test(cleanupBody), 'cleanup() 没有卸载');
  must(/restore_ownership/.test(cleanupBody), 'cleanup() 没有把属主修回 App uid');
  must(template.split('restore_ownership').length - 1 >= 3,
    'restore_ownership 至少要被调用两次（进入前 + cleanup 里），加上定义共 3 处');
  must(/trap cleanup EXIT INT TERM/.test(template), '模板缺 trap cleanup EXIT INT TERM');
  must(/umask 000/.test(template), '模板缺 umask 000（真 root 建出的目录会挡住 App uid）');
  must(/set -- @GUEST_ARGS@/.test(template), '模板没有把 guest argv 交给位置参数');
  must(!/\$\{'\$'\}/.test(code),
    '模板里出现了非法参数展开写法（issue #12 那一类，shell 会报到 EOF）');


  // ---- 2b. 替换后的脚本也要能过 sh -n ----
  // 光验模板不够：真正执行的是替换后的脚本，引号问题恰恰出在替换值上。
  // 这里用可信但真实形状的值替换一遍（含一个带空格的宿主路径，专门试引号包没包住）。
  const qv = (s) => "'" + String(s) + "'";
  const fakeRoot = "/data/user/0/top.funcun.dshfolk/files";
  const simSub = {
    "@RFS@": qv(fakeRoot + "/rootfs"),
    "@MNT@": qv(fakeRoot + "/chroot/mnt"),
    "@APP_UID@": "10234",
    "@APP_GID@": "10234",
    "@UNMOUNT_TARGETS@": qv("$MNT/workspace") + " " + qv("$MNT/sdcard"),
    "@MOUNT_LINES@": "mkdir -p \"$MNT/sdcard\" && mount --bind " + qv("/storage/emulated/0") +
      " \"$MNT/sdcard\" || echo " + qv("[DSH-Folk] chroot 挂载失败: /storage/emulated/0 -> /sdcard"),
    "@GUEST_ARGS@": [qv("/bin/bash"), qv("-lc"), qv("echo hi; dsh web")].join(" "),
    "@ENV@": qv("DSH_HOME=/root/.dsh") + " " + qv("PATH=/usr/bin:/bin"),
  };
  let sim = template;
  for (const [k, v] of Object.entries(simSub)) sim = sim.split(k).join(v);
  const simCode = sim.split("\n").filter((l) => !l.trim().startsWith("#")).join("\n");
  const leftover = (simCode.match(/@[A-Z_]+@/g) || []).join(",");
  must(leftover === "", "替换后仍残留占位符：" + leftover);
  const simPath = path.join(require("os").tmpdir(), "dsh-folk-chroot-sim.sh");
  fs.writeFileSync(simPath, sim);
  const syn2 = spawnSync("sh", ["-n", simPath], { encoding: "utf8" });
  must(syn2.status === 0,
    "替换后的脚本语法不过（sh -n 退出 " + syn2.status + "）：" + (syn2.stderr || "").trim().slice(0, 200));
  // ---- 2. 占位符 ↔ Kotlin replace 一一对应 ----
  const placeholders = [...new Set(template.match(/@[A-Z_]+@/g) || [])].sort();
  must(placeholders.length >= 8, '模板里的占位符少于 8 个：' + placeholders.join(','));
  for (const ph of placeholders) {
    must(runtime.includes('.replace("' + ph + '"'),
      'Kotlin 没有替换 ' + ph + '（漏一个就会把占位符原样写进脚本）');
  }
}

// ---- 3. Kotlin 侧 ----
must(/const val LAUNCH_ASSET = "chroot\/launch\.sh"/.test(runtime), '缺 LAUNCH_ASSET 常量');
must(/ctx\.assets\.open\(LAUNCH_ASSET\)/.test(runtime), '没有从 assets 读模板');
must(/import java\.nio\.charset\.StandardCharsets/.test(runtime), '缺 StandardCharsets 导入');
must(/runCatching \{ buildLaunchScript\(rootfs, dir, env, guest\) \}/.test(runtime)
  && /生成 launch.sh 失败（已回退 proot）/.test(runtime),
  '生成脚本失败时没有回退 proot（一次 chroot 尝试能把启动搞死）');
must(/return template\s*\n\s*\.replace\("@RFS@"/.test(runtime),
  'buildLaunchScript 不是「读模板 + 逐个 replace」形态（别退回 Kotlin 拼字符串）');

// ---- 4. chroot 失败必须能回退 proot ----
const gatedCallSites = (dsh.match(/runtimeId\(\) == "proroot" && noteProrootFailure\(/g) || []).length;
must(gatedCallSites === 0,
  '还有 ' + gatedCallSites + ' 处调用点写死 `runtimeId() == "proroot" &&` —— chroot 的回退分支不可达');
const plainCallSites = (dsh.match(/if \(noteProrootFailure\(/g) || []).length;
must(plainCallSites >= 3, 'noteProrootFailure 的调用点少于 3 处（启动异常/进程退出/超时）');
must(/if \(id == "chroot"\)/.test(dsh), 'noteProrootFailure 里缺 chroot 分支');

// ---- 5. 属主修复要覆盖「切回 proot」那条路 ----
must(/private fun repairRootfsOwnership\(\)/.test(dsh), '缺 repairRootfsOwnership()');
must(/^        repairRootfsOwnership\(\)$/m.test(dsh), 'startServer 里没有调用 repairRootfsOwnership()');
must(/repairRootfsOwnership\(\)\s*\n\s*\n\s*val port = port\(\)/.test(dsh),
  'repairRootfsOwnership() 必须在起容器之前调用');
must(/PrivilegedShell\.tryEnter\(\)/.test(dsh) && /PrivilegedShell\.exec\(appContext, script, asRoot = true/.test(dsh),
  '属主修复没有走特权单飞通道（tryEnter/exec）');
must(/ContainerRuntime\.Chroot\(appContext\)\.available\(\)/.test(dsh),
  '属主修复没有先判断 root 通道是否就绪');

if (errors.length) {
  console.error('check-chroot FAILED:');
  for (const e of errors) console.error('  ✗ ' + e);
  process.exit(1);
}
console.log('check-chroot: 通过');
