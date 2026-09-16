#!/usr/bin/env node
/**
 * 备份加密与补包链路的门禁。
 *
 * ## 为什么必须有一个这样的检查器
 *
 * 新的导出流程是「插件出明文 → 我们在本地补包 → 我们在本地加密」，而加密格式必须与
 * 插件（`dsh-config-manager`）**逐字节一致**，否则产出的备份在插件里、在桌面端都打不开
 * —— 而这件事在 App 侧完全看不出来：包生成了、大小正常、密码也对，只是谁都解不开。
 *
 * 本机没有 Android SDK，Kotlin 跑不了，所以这里做两件事：
 *
 *  1. **用 Node 的独立实现去验嵌进 Kotlin 的那两条自检向量**（scrypt 向量 + 插件产出的
 *     DCA1 容器）。向量是「跨实现」的：只要 Kotlin 侧或这里的任何一方被改坏，就有一边
 *     对不上。Node 的 crypto 是标准库，CI 里一定有，不依赖容器里的插件。
 *  2. **结构性断言**：GCM 的 tag 位置、header 布局、无 AAD、checksums 必须重算、
 *     encrypted=true 必须有 secrets.enc、旧的开关与「请插件解密」的路径必须真的消失。
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const root = path.resolve(__dirname, '..');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');

const cryptoKt = read('app/src/main/java/me/bmax/apatch/dsh/DshBackupCrypto.kt');
const archiveKt = read('app/src/main/java/me/bmax/apatch/dsh/DshBackupArchive.kt');
const backupKt = read('app/src/main/java/me/bmax/apatch/dsh/DshConfigBackup.kt');
const appDataKt = read('app/src/main/java/me/bmax/apatch/dsh/DshAppData.kt');

let n = 0;
let bad = 0;

/** 把 Kotlin 源码里的字符串字面量还原成真实字符（\n、\" 等），否则「读到的常量」永远带反斜杠。 */
function unescapeKotlin(literal) {
  return literal.replace(/\\n/g, '\n').replace(/\\"/g, '"').replace(/\\\\/g, '\\');
}
function ok(cond, msg) {
  n++;
  if (!cond) {
    bad++;
    console.log('✗ ' + msg);
  }
}
function section(title) {
  console.log('─ ' + title);
}

/* ---------------------------------------------------------------- 1. 常量 */
section('1. 容器常量必须与插件一致');
ok(/const val ARCHIVE_MAGIC = "DCA1"/.test(cryptoKt), 'DCA1 magic');
ok(/const val SECRETS_MAGIC = "DSC1"/.test(cryptoKt), 'DSC1 magic（secrets.enc 与容器同构，只有 magic 不同）');
ok(/const val VERSION = 1\b/.test(cryptoKt), '版本号 1');
ok(/const val SCRYPT_N = 16384/.test(cryptoKt), 'scrypt N');
ok(/const val SCRYPT_R = 8/.test(cryptoKt), 'scrypt r');
ok(/const val SCRYPT_P = 1/.test(cryptoKt), 'scrypt p');
ok(/const val KEY_LENGTH = 32/.test(cryptoKt), 'scrypt keyLength');
ok(/const val SALT_LENGTH = 16/.test(cryptoKt), 'salt 16 字节');
ok(/const val IV_LENGTH = 12/.test(cryptoKt), 'iv 12 字节');
ok(/const val TAG_LENGTH = 16/.test(cryptoKt), 'tag 16 字节');
ok(
  /const val HEADER_LENGTH = 4 \+ 1 \+ SALT_LENGTH \+ IV_LENGTH \+ TAG_LENGTH/.test(cryptoKt),
  'header = magic(4)+version(1)+salt(16)+iv(12)+tag(16) = 49',
);
ok(/AES\/GCM\/NoPadding/.test(cryptoKt), '用 AES/GCM/NoPadding');
ok(!/\.setAAD\(|\.updateAAD\(/.test(cryptoKt), '不设 AAD（插件的 Node createCipheriv 也没设，设了就对不上）');
ok(/GCMParameterSpec\(TAG_BITS, iv\)/.test(cryptoKt), 'GCM 参数按 tag 位数 + iv 构造');

/* ------------------------------------------- 2. 自检向量（Node 独立实现复算） */
section('2. 嵌入 Kotlin 的自检向量必须在独立实现下成立');

const pwMatch = cryptoKt.match(/SELFTEST_PASSWORD = "([^"]+)"/);
const keyMatch = cryptoKt.match(/SELFTEST_KEY_HEX =\s*\n?\s*"([0-9a-f]{64})"/);
const blobMatch = cryptoKt.match(/SELFTEST_BLOB_B64 =\s*\n?\s*"([A-Za-z0-9+/=]+)"/);
const plainMatch = cryptoKt.match(/SELFTEST_PLAINTEXT = "([^"]*)"/);
ok(!!pwMatch, '自检密码常量存在');
ok(!!keyMatch, 'scrypt 向量常量存在');
ok(!!blobMatch, 'DCA1 向量常量存在');
ok(!!plainMatch, 'DCA1 向量明文常量存在');

if (pwMatch && keyMatch && blobMatch && plainMatch) {
  const password = pwMatch[1];
  const salt = Buffer.from(Array.from({ length: 16 }, (_, i) => i));
  const derived = crypto.scryptSync(password, salt, 32, { N: 16384, r: 8, p: 1 });
  ok(
    derived.toString('hex') === keyMatch[1],
    `scrypt 向量可复算（期望 ${keyMatch[1].slice(0, 16)}…，实算 ${derived.toString('hex').slice(0, 16)}…）`,
  );

  // DCA1：magic(4)+version(1)+salt(16)+iv(12)+tag(16)+密文
  const blob = Buffer.from(blobMatch[1], 'base64');
  const magic = blob.subarray(0, 4).toString('ascii');
  ok(magic === 'DCA1', `向量是 DCA1 容器（读到 "${magic}"）`);
  ok(blob[4] === 1, '向量版本号 = 1');
  const vSalt = blob.subarray(5, 21);
  const vIv = blob.subarray(21, 33);
  const vTag = blob.subarray(33, 49);
  const vBody = blob.subarray(49);
  let plain = null;
  try {
    const key = crypto.scryptSync(password, vSalt, 32, { N: 16384, r: 8, p: 1 });
    const d = crypto.createDecipheriv('aes-256-gcm', key, vIv);
    d.setAuthTag(vTag);
    plain = Buffer.concat([d.update(vBody), d.final()]).toString('utf8');
  } catch (e) {
    plain = 'ERR:' + e.message;
  }
  ok(
    plain === unescapeKotlin(plainMatch[1]),
    `DCA1 向量用 Node 独立实现能解出期望明文（实得 ${JSON.stringify(String(plain).slice(0, 40))}）`,
  );
  // 顺带把「tag 不在密文尾部」这件事钉住：按密文尾部取 16 字节当 tag 必须解不开
  let tailAsTag = false;
  try {
    const key = crypto.scryptSync(password, vSalt, 32, { N: 16384, r: 8, p: 1 });
    const d = crypto.createDecipheriv('aes-256-gcm', key, vIv);
    d.setAuthTag(vBody.subarray(vBody.length - 16));
    Buffer.concat([d.update(vBody.subarray(0, vBody.length - 16)), d.final()]);
    tailAsTag = true;
  } catch {
    tailAsTag = false;
  }
  ok(!tailAsTag, '认证 tag 确实在 header 里而不是密文尾部（否则 GCM 两种取法都会通过）');
}

/* ------------------------------------------------- 3. 容器读写的结构性断言 */
section('3. 容器读写：tag 位置、往返、错误处理');
ok(
  /sealedBytes\.size - TAG_LENGTH/.test(cryptoKt) && /arraycopy\(sealedBytes, body, out, TAG_OFFSET, TAG_LENGTH\)/.test(cryptoKt),
  'seal：把 Java 输出尾部的 tag 切出来写进 header',
);
ok(
  /arraycopy\(tag, 0, sealedBytes, body, TAG_LENGTH\)/.test(cryptoKt),
  'decryptBlock：把 header 里的 tag 拼回密文尾部再交给 Cipher（Java 只认「密文||tag」）',
);
ok(/fun selfTest\(\): String\?/.test(cryptoKt), '有自检入口');
ok(/synchronized\(selfTestLock\)/.test(cryptoKt) && /selfTestFinished/.test(cryptoKt), '自检结果被缓存（16MB×4 不必每次导出重跑）');
ok(
  /AEADBadTagException|catch \(e: Exception\)/.test(cryptoKt) && /return null/.test(cryptoKt),
  '密码错/被篡改走「返回 null」而不是抛异常',
);
ok(/fun encryptArchiveToFile\(/.test(cryptoKt) && /fun decryptArchiveToFile\(/.test(cryptoKt), '有流式文件接口');
ok(!/readBytes\(\)\s*\)\s*use/.test(cryptoKt), '加密路径不把整包读进内存');
ok(/RandomAccessFile/.test(cryptoKt), '流式加密用随机写回填 header（tag 只有加密完才知道）');

/* --------------------------------------------------------- 4. 补包链路 */
section('4. 补包：checksums 必须重算、secrets.enc 必须与 encrypted 同进退');
ok(/private val SESSION_FILE_RE = Regex/.test(archiveKt), '会话文件名用正则判据（新旧格式都认）');
ok(/fun pluginSections\(\): List<String> = DshConfigBackup\.DEFAULT_SECTIONS/.test(archiveKt), '向插件要的分区里不含 sessions');
ok(/sections\.put\("sessions", sessionDirs\.isNotEmpty\(\)\)/.test(archiveKt), 'manifest.sections.sessions 跟着实际内容写');
ok(
  /put\("encrypted", plan\.password\.isNotEmpty\(\)\)/.test(archiveKt) ||
    /put\("encrypted",\s*plan\.password\.isNotEmpty\(\)\)/.test(archiveKt),
  'manifest.security.encrypted 跟着密码写',
);
ok(/put\("containsSecrets", plan\.includesVault && plan\.password\.isNotEmpty\(\)\)/.test(archiveKt), 'containsSecrets 只在含 vault 时为真');
ok(/sums\[name\] = hex\(sha\.digest\(\)\)/.test(archiveKt), '搬运插件条目时逐个算 sha256');
ok(/name == CHECKSUMS -> Unit/.test(archiveKt), 'checksums 表本身不参与校验（插件也是这么生成的）');
ok(
  /writeBytes\(zos, CHECKSUMS, table\.toString\(2\)\.toByteArray/.test(archiveKt),
  'checksums 表最后写、且覆盖前面写过的全部条目',
);
ok(/if \(secrets != null\)/.test(archiveKt) && /writeBytes\(zos, SECRETS, secrets\.blob\)/.test(archiveKt), '有密码时写出 security/secrets.enc');
ok(/require\(plan\.valid\)/.test(archiveKt), '含 vault 无密码时 merge 直接拒绝');

/* --------------------------------------------------------- 5. 导出管线 */
section('5. 导出管线：插件只出明文，密码只由我们施加');
ok(/suspend fun exportArchive\(/.test(backupKt), '有 exportArchive');
ok(
  /val bad = DshBackupCrypto\.selfTest\(\)/.test(backupKt) && /if \(bad != null\)/.test(backupKt),
  '导出前先跑加密自检，不过就拒绝导出',
);
const exportBody = backupKt.slice(backupKt.indexOf('suspend fun exportArchive('));
ok(/put\("includeSecrets", false\)/.test(exportBody), '导出请求仍然不带凭据');
ok(
  !/put\("password", plan\.password\)/.test(exportBody),
  '不再把密码交给插件（否则它直接产出最终容器，我们就没法补包了）',
);
ok(/JSONArray\(DshBackupArchive\.pluginSections\(\)\)/.test(exportBody), 'only 用 pluginSections()');
ok(/DshBackupArchive\.merge\(/.test(exportBody), '导出走本地补包');
ok(/DshBackupCrypto\.encryptArchiveToFile\(merged, finalFile, plan\.password\)/.test(exportBody), '有密码时由 App 做容器加密');
ok(!/DshConfigBackup\.sections\(/.test(exportBody), '旧的分区拼装入口不再被导出使用');

/* --------------------------------------------------------- 6. 导入管线 */
section('6. 导入管线：容器自己解、会话三模式、软件数据自己放回');
ok(/DshBackupCrypto\.isArchiveBlobFile\(zip\)/.test(backupKt), '先按 magic 判断是不是加密容器');
ok(/DshBackupCrypto\.decryptArchiveToFile\(zip, plain, password\)/.test(backupKt), '由 App 侧解密');
ok(!/decrypt-archive/.test(backupKt), '不再调用插件的 /decrypt-archive');
ok(/enum class SessionImport/.test(backupKt), '会话三模式存在');
ok(/if \(sessions == SessionImport\.STOP\)/.test(backupKt), '停机恢复与直接恢复在归组处分岔');
ok(
  /DshRuntime\.withServiceStopped \{\s*\n\s*DshSessionGroup\.groupRestoredSessions/.test(backupKt),
  '停机模式仍然把归组包在 withServiceStopped 里',
);
ok(/DshAppData\.apply\(ctx, data\)/.test(backupKt) && /DshAppData\.mergeAudit\(ctx, plainZip\)/.test(backupKt), '导入时恢复软件数据与审计记录');
ok(/if \(!hasDshSections\(plainZip\)\)/.test(backupKt), '纯软件数据包短路（不去打扰插件）');
ok(/if \(plainZip != zip\) plainZip\.delete\(\)/.test(backupKt), '解出来的明文中间产物用完即删（里面有凭据）');
ok(/suspend fun countSessionsForPrompt\(/.test(backupKt), '有导入前的会话探测入口');
ok(/fun countSessionsInZip\(/.test(backupKt), '有本地会话计数');

/* ---------------------- 6b. 「跳过会话」只跳过会话，别的照常导入 ---------------------- */

/**
 * 取「从某个标记起的那个 {…} 块」的字符区间（大括号配对）。
 *
 * 用它才能断言「某段代码在不在这个守卫里面」—— 纯文本包含判断做不到这件事，而
 * 「跳过会话」一旦被写成在外面 return，用户在设备上看到的就是「选了跳过，整包都没导入」。
 */
function braceSpan(src, marker) {
  const i = src.indexOf(marker);
  if (i < 0) return null;
  const open = src.indexOf('{', i);
  if (open < 0) return null;
  let depth = 0;
  for (let k = open; k < src.length; k++) {
    if (src[k] === '{') depth++;
    else if (src[k] === '}') {
      depth--;
      if (depth === 0) return [i, k];
    }
  }
  return null;
}

const sessionsSpan = braceSpan(backupKt, 'if (sessions != SessionImport.SKIP && rollback == null)');
ok(sessionsSpan !== null, '会话写入仍然只由 sessions 模式守卫');
if (sessionsSpan) {
  const appDataAt = backupKt.indexOf('DshAppData.readFromZip(plainZip)', sessionsSpan[1]);
  ok(appDataAt > sessionsSpan[1], '软件数据恢复在会话守卫**之外**（选跳过时它照样执行）');
  const execAt = backupKt.indexOf('"/execute"');
  ok(execAt >= 0 && execAt < sessionsSpan[0], '插件的 analyze/plan/execute 在会话写入之前、且不受它守卫（跳过会话不影响配置导入）');
}
ok(
  /sessions != SessionImport\.SKIP && rollback == null/.test(backupKt),
  '跳过 = 不写会话，而不是不导入',
);

// 界面侧：只有探测到会话才弹框；三个选项（含跳过）都走同一条导入
const screenSrc = read('app/src/main/java/me/bmax/apatch/ui/screen/settings/BackupSettingsScreen.kt');
ok(/val count = DshConfigBackup\.countSessionsForPrompt\(/.test(screenSrc), '导入前先探测会话数量');
const gate = braceSpan(screenSrc, 'if (count > 0)');
ok(gate !== null, '弹框由「探测到会话」把关');
if (gate) {
  const flagAt = screenSrc.indexOf('pendingImportPrompt = true', gate[0]);
  ok(flagAt > gate[0] && flagAt < gate[1], '只有 count > 0 才置起弹框标记');
}
const elseAt = screenSrc.indexOf('} else {', gate ? gate[1] : 0);
ok(elseAt > 0, 'count == 0 有明确分支');
const pickSpan = braceSpan(screenSrc, 'val pick: (DshConfigBackup.SessionImport) -> Unit');
ok(pickSpan !== null, '三个选项共用一个 pick 回调');
if (pickSpan && gate) {
  const runAt = screenSrc.indexOf('runImport(', pickSpan[0]);
  ok(runAt > pickSpan[0] && runAt < pickSpan[1], '选「跳过会话数据」也调用 runImport（不是取消导入）');
  const cancelAt = screenSrc.indexOf('val cancelPick', pickSpan[1]);
  ok(cancelAt > pickSpan[1], '只有 cancelPick（点外面/取消）才放弃并删暂存文件');
}

/* --------------------------------------------------------- 7. 软件数据边界 */
section('7. 软件数据：设置带走，密钥留下');
ok(/SKIP_PREFIXES = listOf\("webdav_"\)/.test(appDataKt), 'webdav_* 整组不带（只带地址不带密码等于给用户一个连不上的配置）');
ok(/"password", "passwd", "token", "secret"/.test(appDataKt), '密钥类键名被过滤');
ok(/SKIP_KEYS = setOf\("app_initialized"\)/.test(appDataKt), '不带 app_initialized（否则新设备会跳过首次初始化）');
ok(/put\("t", "s"\)/.test(appDataKt) && /put\("t", "i"\)/.test(appDataKt), 'prefs 值带类型标记（否则 int 会被写成 double）');
ok(/SecureRandom|DshAppData/.test(appDataKt), '模块自带完整实现');

/* --------------------------------------------------------- 8. 旧开关必须消失 */
section('8. 旧的「导出/恢复对话数据」开关必须真的没了');
const uiFiles = [
  'app/src/main/java/me/bmax/apatch/ui/screen/settings/BackupSettings.kt',
  'app/src/main/java/me/bmax/apatch/ui/screen/settings/BackupSettingsScreen.kt',
];
for (const f of uiFiles) {
  const src = read(f);
  ok(!/dshIncludeSessions|dshImportSessions/.test(src), `${path.basename(f)} 里不再有旧开关状态`);
  ok(!/includeSessions\s*=/.test(src), `${path.basename(f)} 里不再传 includeSessions 参数`);
}
ok(/SessionImport\./.test(read(uiFiles[1])), '导入界面接上了三模式');
ok(
  /ExportPlan\(/.test(uiFiles.map(read).join('\n')),
  '导出界面接上了 ExportPlan（内容层构造、屏幕层执行都算）',
);
ok(
  /DshConfigBackup\.exportArchive\(/.test(read(uiFiles[1])),
  '导出按钮真的调到 exportArchive（而不是旧的那个 export）',
);
ok(
  !/DshConfigBackup\.export\(/.test(uiFiles.map(read).join('\n')),
  '旧 export( 已经没有任何界面调用',
);
ok(
  /DshBackupCrypto\.isArchiveBlobFile|countSessionsForPrompt/.test(read(uiFiles[1])),
  '导入前先探测（加密包要密码才数得出会话）',
);

console.log(bad === 0 ? `\n全部通过（${n} 项断言）` : `\n${bad}/${n} 项失败`);
process.exit(bad === 0 ? 0 : 1);
