#!/usr/bin/env node
// 门禁：运行时安装/恢复链上的四处「静默出错」不许回潮。
//
// 背景（外部审查 P1-3 三项 + P2-2 + P0-3 附带项，均已核对成立）：
//  1. 解压进度：分子是「解压后累计字节」，分母却是「压缩包大小」——两者差约 3 倍，
//     进度条在 1/3 处就满格并卡死。现在分母取 gzip 尾部 ISIZE（解压后字节数）。
//  2. PAX 扩展头只读了 path=，没读 linkpath= —— 长符号链接/硬链接的目标被截断在 100 字节。
//  3. 恢复路径用裸 renameTo：跨挂载点失败时静默返回 false，用户数据丢失。
//  4. 备份规则全被注释掉（且 XML 括号悬空）→ 系统云备份会去扫 rootfs 与含 token 的 prefs。
//  5. noteProrootFailure 里插件失败判据排在 chroot 分支之后 → 插件报错会把 chroot 判死。
const fs = require('fs');
const path = require('path');

const errors = [];
const root = path.resolve(__dirname, '..');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');
const must = (c, m) => { if (!c) errors.push(m); };

const extractor = read('app/src/main/java/me/bmax/apatch/dsh/TarGzipExtractor.java');
const installer = read('app/src/main/java/me/bmax/apatch/dsh/DshRuntimeInstaller.kt');
const env = read('app/src/main/java/me/bmax/apatch/dsh/DshEnv.kt');
const runtime = read('app/src/main/java/me/bmax/apatch/dsh/DshRuntime.kt');
const backupRules = read('app/src/main/res/xml/backup_rules.xml');
const extractionRules = read('app/src/main/res/xml/data_extraction_rules.xml');

// ---- 1. 解压进度分母 = 解压后字节（gzip ISIZE） ----
must(/private static long readGzipPlainSize\(File tarball\)/.test(extractor),
  '缺 readGzipPlainSize()：分母退回「压缩包大小」就会在 1/3 处满格');
must(/new RandomAccessFile\(tarball, "r"\)/.test(extractor),
  '没有从 gzip 尾部读 ISIZE（只读 4 字节，别用「再解压一遍数总长」）');
must(/raf\.seek\(tarball\.length\(\) - 4\)/.test(extractor), 'ISIZE 的偏移写错了（应读最后 4 字节）');
must(/long plain = readGzipPlainSize\(tarball\);/.test(extractor)
  && /totalTarBytes = plain > 0 \? plain : compressed;/.test(extractor),
  'resetDiagnostics 没有优先用解压后字节作分母');
// 反向：不能再出现「直接拿 tarball.length() 当分母」的老写法
must(!/totalTarBytes = tarball != null && tarball\.isFile\(\) \? tarball\.length\(\) : 0L;/.test(extractor),
  '分母又被写回压缩包大小了');

// ---- 2. PAX 必须解析 linkpath= ----
must(/private static String parsePaxKey\(byte\[\] data, String key\)/.test(extractor),
  '缺 parsePaxKey()：path= 与 linkpath= 应共用一套解析');
must(/parsePaxKey\(longData, "linkpath"\)/.test(extractor),
  'PAX 记录没有读 linkpath=（长符号链接目标会被截断成坏链接）');
must(/pendingLinkname = paxLink/.test(extractor),
  'linkpath 读到了却没交给 pendingLinkname（等于没解析）');

// ---- 3. 恢复路径不得再出现裸 renameTo ----
must(/internal fun renameOrCopy\(src: File, dst: File, note: \(String\) -> Unit\): Boolean/.test(installer),
  'renameOrCopy 不是 internal：其它恢复路径没法复用，只能各写一遍裸 renameTo');
const installerCalls = (installer.match(/renameOrCopy\(/g) || []).length;
must(installerCalls >= 6, 'renameOrCopy 的调用点少于 6 处（恢复路径又漏了）');
const envBare = /(?<!\/\/.*)\bsrc\.renameTo\(dst\)/.test(env);
must(!envBare, 'DshEnv.recoverPreserved 又用裸 renameTo（跨挂载点会静默丢数据）');
must(/renameOrCopy\(src, dst\)/.test(env), 'DshEnv.recoverPreserved 没有走 renameOrCopy');
const backupRenames = (installer.match(/backup\.renameTo\(dest\)/g) || []).length;
must(backupRenames === 0, 'recoverInterruptedRuntimeInstall 里又有裸 backup.renameTo(dest)');

// ---- 4. 备份规则必须真的排除（且 XML 注释括号平衡） ----
for (const [name, xml] of [['backup_rules.xml', backupRules], ['data_extraction_rules.xml', extractionRules]]) {
  const opens = (xml.match(/<!--/g) || []).length;
  const closes = (xml.match(/-->/g) || []).length;
  must(opens === closes, name + ' 的注释括号不平衡（' + opens + ' 开 / ' + closes + ' 闭）—— 上一版就是这么坏的');
  must(!/^\s*<include\b/m.test(xml), name + ' 里还有 <include>（白名单式备份容易漏掉新增目录）');
}
// path="." 的那几条（file/sharedpref/database/external 整域排除）单独算：
// 它们对应的是「domain" path="."」而不是 「domain" path="sharedpref"」。
for (const need of ['rootfs', 'chroot', 'extensions', 'logs', 'audit']) {
  must(new RegExp('<exclude domain="file" path="' + need + '"').test(backupRules),
    'backup_rules.xml 没有排除 ' + need);
  must(new RegExp('<exclude domain="file" path="' + need + '"').test(extractionRules),
    'data_extraction_rules.xml 没有排除 ' + need);
}
must(/<exclude domain="sharedpref" path="\." \/>/.test(backupRules),
  'SharedPreferences 没有整体排除：里面有 fs_bridge_token');

// ---- 5. 插件失败判据必须先于 chroot 分支 ----
const body = (runtime.match(/fun noteProrootFailure\(why: String\): Boolean \{[\s\S]*?\n    \}/) || [''])[0];
must(body !== '', '找不到 noteProrootFailure()');
const pluginIdx = body.indexOf('lastLogSuggestsPluginFailure()');
const chrootIdx = body.indexOf('if (id == "chroot")');
must(pluginIdx > 0 && chrootIdx > 0, 'noteProrootFailure 里缺插件判据或 chroot 分支');
must(pluginIdx < chrootIdx,
  '插件失败判据排在 chroot 分支之后：插件报错会把 chroot 判死并回退 proot（评审 P0-3 附带项）');
must(/if \(id != "proroot" && id != "chroot"\) return false/.test(body),
  '入口判断应同时接受 proroot 与 chroot');

if (errors.length) {
  console.error('check-install-recovery FAILED:');
  for (const e of errors) console.error('  ✗ ' + e);
  process.exit(1);
}
console.log('check-install-recovery: 通过');
