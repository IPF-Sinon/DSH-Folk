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
const wizardScreenSrc = read('app/src/main/java/me/bmax/apatch/ui/screen/settings/RestoreWizardScreen.kt');
const wizardSrc = read('app/src/main/java/me/bmax/apatch/ui/screen/settings/BackupWizard.kt');
ok(/DshConfigBackup\.preflightImport\(/.test(wizardScreenSrc), '导入前先跑预检（会话数与冲突都由它给出）');
// 会话与冲突都在向导的「决策」步里问，而且**只有真的有**才问：
// 没有会话就不画那一段、没有冲突就不画那一段，两样都没有时预览直接进确认。
const decideSpan = braceSpan(wizardSrc, 'private fun WizardDecideStep(');
ok(decideSpan !== null, '向导有决策步（会话与冲突都在这里问）');
if (decideSpan) {
  const body = wizardSrc.slice(decideSpan[0], decideSpan[1]);
  ok(/if \(sessions > 0\) \{/.test(body), '只有包里有会话才画「会话怎么处理」那一段');
  ok(/if \(conflicts\.isNotEmpty\(\)\) \{/.test(body), '只有检测到冲突才画冲突逐条决策那一段');
  // 「跳过会话数据」必须仍然导入：决策步只是收集答案，真正的开跑在 wizardRun。
  ok(/onSessionChoice\(DshConfigBackup\.SessionImport\.SKIP\)/.test(body),
    '「跳过会话数据」是一个正常的选项，不是取消导入');
  for (const mode of ['STOP', 'DIRECT', 'SKIP']) {
    ok(new RegExp('onSessionChoice\\(DshConfigBackup\\.SessionImport\\.' + mode + '\\)').test(body),
      '三个会话选项共用一个回调：' + mode);
  }
}
ok(/sessions = sessionChoice\(\) \?: DshConfigBackup\.SessionImport\.SKIP/.test(wizardScreenSrc),
  '会话答案在真正导入时才消费（跳过 = 不写会话，其余照常导入）');
ok(/if \(needsDecide\(preflight\)\) WizardStep\.DECIDE else WizardStep\.CONFIRM/.test(wizardScreenSrc),
  '两样都没有就直接进确认（不给用户多余的一问）');
ok(/onCancel = \{ exit\(\) \}/.test(wizardScreenSrc) && /fun exit\(\)/.test(wizardScreenSrc) &&
  /onDispose \{ cleanUp\(\) \}/.test(wizardScreenSrc),
  '取消（或手势返回离开这一页）才放弃并清掉预检产物');
const runSpan = braceSpan(wizardScreenSrc, 'fun runImport()');
ok(runSpan !== null, 'runImport 是唯一的开跑入口');
if (runSpan) {
  ok(/DshConfigBackup\.import\(\s*context, p\.plainZip,/.test(wizardScreenSrc.slice(runSpan[0], runSpan[1])),
    '开跑用的是预检留下的那份明文包（不再上传/解密第二遍）');
}

/* --------------------------- 6c. 软件数据真的能恢复回去（走一遍两条入库路径） --------------------------- */

/**
 * 软件数据是这条链路上唯一「插件不管」的部分：写进去、读回来全靠我们自己的常量对齐。
 * 两边一旦用了不同的路径或不同的键，症状是**静默的** —— 导入说成功，设置一个都没回来。
 * 所以这里把四个环节逐一对上：写侧写在哪、读侧找什么、两条入库路径都覆盖、临时包何时删。
 */
const appOnlySpan = braceSpan(backupKt, 'if (!hasDshSections(plainZip)) {\n            val data');
ok(appOnlySpan !== null, '纯软件数据包有独立分支');
if (appOnlySpan) {
  const readAt = backupKt.indexOf('DshAppData.readFromZip(plainZip)', appOnlySpan[0]);
  const applyAt = backupKt.indexOf('DshAppData.apply(ctx, data)', appOnlySpan[0]);
  const auditAt = backupKt.indexOf('DshAppData.mergeAudit(ctx, plainZip)', appOnlySpan[0]);
  const delAt = backupKt.indexOf('plainZip.delete()', appOnlySpan[0]);
  ok(readAt > 0 && applyAt > readAt, '纯软件数据包：先读出来再写回设置');
  ok(auditAt > applyAt, '审计日志恢复在设置之后');
  ok(backupKt.indexOf('plainZip.delete()', auditAt) > auditAt, '临时明文在**审计读完之后**才删（先删会让审计对着空气空跑）');
}
const normalAt = backupKt.indexOf('val appData = if (rollback == null) DshAppData.readFromZip(plainZip) else null');
ok(normalAt > 0, '正常路径也恢复软件数据（同一份 JSON，同一个键）');
ok(backupKt.indexOf('if (rollback == null) DshAppData.readFromZip') >= 0, '插件整体回滚时不恢复软件数据（配置没落地，设置先落地只会前后不一致）');
ok(/dsh_bk_appdata_skipped_rollback/.test(backupKt), '回滚跳过软件数据这件事会如实告诉用户');
ok(/dsh_bk_appdata_takes_effect/.test(backupKt), '恢复设置后提示「重启应用才全部生效」（prefs 是热写的，界面里已读进内存的状态不会自己刷新）');
ok(
  backupKt.indexOf('val appData = if (rollback == null)') < backupKt.indexOf('val head = buildString {'),
  '软件数据恢复发生在拼结果之前（否则用户看不到它到底恢复了什么）',
);

// 写侧与读侧必须用同一批常量，不能一边写 "dsh-folk/app-data.json"、一边找别的名字
ok(/const val APP_DATA = "dsh-folk\/app-data.json"/.test(archiveKt), '包里 App 数据的路径是常量');
ok(/name == DshBackupArchive\.APP_DATA/.test(appDataKt), '读侧用同一个常量找它（不是重写的字面量）');
ok(/DshBackupArchive\.APP_DIR \+ "audit\/"/.test(appDataKt), '审计文件读侧用同一个目录前缀');
ok(/sums\[APP_DIR \+ "audit\/" \+ f\.name\]/.test(archiveKt), '审计文件写侧也在同一前缀下');

/* --------------------- 6d. 导出必须自校验：坏包绝不当成功交出去 --------------------- */

// 现场：用户拿到一个 49 字节的「备份」= 容器头长度（4+1+16+12+16）+ 零长密文，
// 恢复自然失败。而内存版自检一直是过的 —— 因为导出走的是流式那两个函数，
// 它们从没被自检覆盖过。下面几条把「这次是怎么发现的」钉成断言。
const selfTestFilesSpan = braceSpan(cryptoKt, 'fun selfTestFiles(');
ok(selfTestFilesSpan !== null, '流式加解密有独立自检（selfTestFiles）');
if (selfTestFilesSpan) {
  const body = cryptoKt.slice(selfTestFilesSpan[0], selfTestFilesSpan[1]);
  ok(/encryptArchiveToFile\(plain, blob, SELFTEST_PASSWORD\)/.test(body),
    '流式自检真的调用加密落盘（不是又测一遍内存版）');
  ok(/decryptArchiveToFile\(blob, back, SELFTEST_PASSWORD\)/.test(body), '流式自检把刚写出的文件解回来');
  ok(/blob\.length\(\) != expected/.test(body) && /HEADER_LENGTH\.toLong\(\) \+ payload\.size/.test(body),
    '流式自检核对「容器 = 头 + 明文」（49 字节的坏包就是这样被发现的）');
  ok(/sha256File\(plain\)/.test(body) && /sha256File\(back\)/.test(body), '流式自检比对内容 sha256');
  ok(/payload\.size/.test(body) && /300_000/.test(body), '自检数据量跨过 64KB 缓冲区（小额数据测不出流式问题）');
}
ok(/fun selfTestFiles\(dir: File\): String\?/.test(cryptoKt), 'selfTestFiles 返回可显示的原因（null = 通过）');

ok(/private fun isUsableZip\(f: File\): Boolean/.test(backupKt), '有「能打开的 zip」判定');
ok(/if \(!isUsableZip\(pluginPlain\)\)/.test(backupKt) && /dsh_bk_plugin_zip_bad/.test(backupKt),
  '插件下载回来的包先验一遍（0 字节/半截文件当场拦住）');
ok(/if \(!isUsableZip\(merged\)\)/.test(backupKt) && /dsh_bk_merge_empty/.test(backupKt),
  '补包结果也验（空结果不许往下走）');
ok(/val streamBad = DshBackupCrypto\.selfTestFiles\(stage\)/.test(backupKt),
  '有密码时导出前先跑一次流式自检');

const verifyAt = backupKt.indexOf('val verify = verifyEncrypted(ctx, merged, finalFile, plan.password, stage)');
const deleteAfterVerify = backupKt.indexOf('finalFile.delete()', verifyAt);
ok(verifyAt > 0 && deleteAfterVerify > verifyAt, '加密之后当场解回来核对，不通过就删掉文件并报错');
ok(/private fun verifyEncrypted\(/.test(backupKt) && /back\.length\(\) != plain\.length\(\)/.test(backupKt),
  '核对包含「大小 + 内容」两项');
ok(/dsh_bk_verify_failed/.test(backupKt), '核对失败会如实说明期望值与实际值');
ok(/dsh_bk_out_size/.test(backupKt) && /humanSize\(finalFile\.length\(\)\)/.test(backupKt),
  '导出结果里报大小（用户一眼能看出是不是空包）');

// 尺寸恒等式本身也验一遍：49 = 4 + 1 + 16 + 12 + 16
ok(4 + 1 + 16 + 12 + 16 === 49, '容器头正好 49 字节（与现场那个坏包大小一致）');

// 真机结论：日志在 merge-done 之后就断了（没有 encrypt= 那一行），卡在流式自检上；
// 而自检失败说明流式这条路本身是坏的 —— 它正是产出 49 字节空容器的元凶。
// 两个函数原来都用 RandomAccessFile（先占位、写完 seek 回去回填头部），
// 在那台设备的这个目录里不可靠：密文整段丢失、只剩头部。现在全部改成顺序读写。
ok(!/RandomAccessFile\(/.test(cryptoKt), '加解密两个流式函数都不再调用 RandomAccessFile（只留注释说明历史）');
const decSpan = braceSpan(cryptoKt, 'fun decryptArchiveToFile(blob: File, output: File, password: String): Boolean');
ok(decSpan !== null, '流式解密函数在');
if (decSpan) {
  const body = cryptoKt.slice(decSpan[0], decSpan[1]);
  ok(/BufferedInputStream\(FileInputStream\(blob\)/.test(body), '解密用顺序读文件头');
  ok(/val held = TAG_LENGTH\.toLong\(\)\.coerceAtMost\(body\)\.toInt\(\)/.test(body),
    '扣住密文最后 16 字节（GCM 只认密文||tag）');
  ok(/cipher\.doFinal\(tail \+ tag\)/.test(body), 'doFinal(tail + tag)');
  ok(/remaining -= n\.toLong\(\)/.test(body), '按剩余长度流式读取，不整包进内存');
}
ok(/failTrace\(ctx, ctx\.appString\(R\.string\.dsh_bk_crypto_broken, bad\)\)/.test(backupKt) &&
  /failTrace\(ctx, ctx\.appString\(R\.string\.dsh_bk_crypto_broken, streamBad\)\)/.test(backupKt),
  '自检不过也要记账 —— 这次就是这条分支没记账，日志正好断在那里、原因看不到');
ok(/selftest-stream=" \+ \(streamBad \?: "ok"\)/.test(backupKt), '流式自检结果本身也进日志');

// 真机最终结论（beta.57 的日志）：
//   selftest-stream=写出的容器大小不对：300049 字节（头 49 + 明文 300000），实际 49
// 文件读满了（written=300000）却只写出 49 字节 —— Android 的 Conscrypt 会把 AES/GCM
// 的数据攒到 doFinal() 才吐出来，循环里 update() 一直返回 null。而旧代码把 doFinal()
// 的返回值**整段当成 tag**（只取前 16 字节），密文全丢 —— 这就是 49 字节的来历。
// 内存版 seal() 一直没问题，正是因为它用 doFinal(全部明文) 一次性拿「密文||tag」。
const encFn = braceSpan(cryptoKt, 'fun encryptArchiveToFile(plain: File, output: File, password: String) {');
const decFn = braceSpan(cryptoKt, 'fun decryptArchiveToFile(blob: File, output: File, password: String): Boolean');
const encBody = encFn ? cryptoKt.slice(encFn[0], encFn[1]) : '';
ok(/val rest = cipher\.doFinal\(\)/.test(encBody) &&
  /val restBody = rest\.size - TAG_LENGTH/.test(encBody) &&
  /if \(restBody > 0\) out\.write\(rest, 0, restBody\)/.test(encBody) &&
  /tag = rest\.copyOfRange\(restBody, rest\.size\)/.test(encBody),
  'doFinal() 的返回值按「剩余密文 + 16 字节 tag」拆开（Conscrypt 会攒到这一步才吐）');
ok(/if \(rest\.size < TAG_LENGTH\)/.test(encBody), 'doFinal 返回不足 16 字节时明确报错，不当成 tag 用');
ok(/Conscrypt/.test(encBody), '代码里写明原因（否则下一个人还会踩）');
ok(/if \(chunk != null && chunk\.isNotEmpty\(\)\) out\.write\(chunk\)/.test(encBody) &&
  /if \(last\.isNotEmpty\(\)\) out\.write\(last\)/.test(decFn ? cryptoKt.slice(decFn[0], decFn[1]) : ''),
  '两条路都兼容：update 增量吐（JVM）与攒到 doFinal（Conscrypt）都写得出正确容器');

// 现场证据：DSH 侧日志里插件的明文导出是好的（导出完成 sizeBytes=5108 encrypted=false），
// 所以空的只能是应用侧。于是把「小包走那条每次导出都验过的内存版」和
// 「流式版必须自己证明读到了多少字节」都钉住。
ok(/if \(merged\.length\(\) <= IN_MEMORY_ENCRYPT_LIMIT\)/.test(backupKt) &&
  /val plainBytes = merged\.readBytes\(\)/.test(backupKt) &&
  /finalFile\.writeBytes\(DshBackupCrypto\.encryptArchive\(plainBytes, plan\.password\)\)/.test(backupKt),
  '小包走内存版加密器（selfTest 每版都验它），只有大包才走流式');
ok(/if \(plainBytes\.size\.toLong\(\) != merged\.length\(\)\)/.test(backupKt) &&
  /读取明文/.test(backupKt),
  '内存路先核对「文件长度 vs 实际读出的字节数」—— 只读出 0 字节时当场报错，而不是封出一个空容器');
ok(/private const val IN_MEMORY_ENCRYPT_LIMIT = 16L \* 1024 \* 1024/.test(backupKt),
  '内存加密上限写死在常量里（16MB，vault 大包不会被读爆）');

const encSpan = braceSpan(cryptoKt, 'fun encryptArchiveToFile(plain: File, output: File, password: String) {');
ok(encSpan !== null, '流式加密函数在');
if (encSpan) {
  const body = cryptoKt.slice(encSpan[0], encSpan[1]);
  ok(/if \(written != plain\.length\(\)\)/.test(body) && /throw IllegalStateException\("只读到/.test(body),
    '流式加密读到的字节数必须等于文件长度，否则抛错（空读会报出确切数字，不再静默产出 49 字节）');
  ok(!/RandomAccessFile\(/.test(body), '不再用 RandomAccessFile 回填头部（改成两趟：先流密文拿 tag，再拼头 + 密文）');
  ok(/\.body"/.test(body) && /body\.delete\(\)/.test(body), '临时密文体用完即删');
  ok(/out\.write\(header\)[\s\S]{0,200}copyTo\(out\)/.test(body), '容器 = 头 + 密文，两次普通写，没有回头改');
}

/* --------------------------------------------------------- 7. 软件数据边界 */
section('7. 软件数据：设置带走，密钥留下');
ok(/SKIP_PREFIXES = listOf\("webdav_"\)/.test(appDataKt), 'webdav_* 整组不带（只带地址不带密码等于给用户一个连不上的配置）');
ok(/"password", "passwd", "token", "secret"/.test(appDataKt), '密钥类键名被过滤');
ok(/PREFS_NAME to setOf\("app_initialized"\)/.test(appDataKt),
  '不带 app_initialized（否则新设备会跳过首次初始化）');
// dshfolk 里的机器/运行时状态与提权项同样不带：它们记的是「这台机器发生过什么」，
// 搬到另一台机器就是伪造事实；提权项则该由用户自己重新点一次。
ok(/DshEnv\.KEY_SEEDED_PLUGINS,/.test(appDataKt) && /DshEnv\.KEY_RUNTIME_VERSION,/.test(appDataKt) &&
  /DshEnv\.KEY_PROROOT_FAIL,/.test(appDataKt),
  'dshfolk 的预装账本与本机运行时状态被点名排除');
ok(/DshEnv\.KEY_PERM_CHANNEL,/.test(appDataKt) && /DshEnv\.KEY_NATIVE_CAPS,/.test(appDataKt),
  '提权类（权限通道、原生能力档位）不随备份走');
ok(/val PREFS_FILES = listOf\(PREFS_NAME, DshEnv\.PREF\)/.test(appDataKt),
  'config 与 dshfolk 两个设置文件都进包');
ok(/put\("t", "s"\)/.test(appDataKt) && /put\("t", "i"\)/.test(appDataKt), 'prefs 值带类型标记（否则 int 会被写成 double）');
ok(/SecureRandom|DshAppData/.test(appDataKt), '模块自带完整实现');

/* --------------------------------------------------------- 8. 旧开关必须消失 */
section('8. 旧的「导出/恢复对话数据」开关必须真的没了');
const uiFiles = [
  'app/src/main/java/me/bmax/apatch/ui/screen/settings/BackupSettings.kt',
  'app/src/main/java/me/bmax/apatch/ui/screen/settings/BackupSettingsScreen.kt',
];
for (const f of uiFiles) {
  let src = read(f);
  ok(!/dshIncludeSessions|dshImportSessions/.test(src), `${path.basename(f)} 里不再有旧开关状态`);
  // 1.9.2.5：云备份弹窗把插件配置里的 includeSessions 原样回写给插件（DshCloudBackup.saveConfig
  // 的具名参数）——那是**插件档位**的开关，不是旧的「导出对话数据」布尔。先把 saveConfig 调用块
  // 剔掉再断言，避免把这个合法透传误判成旧开关复活。
  src = src.replace(/DshCloudBackup\.saveConfig\([\s\S]*?\n\s*\)/g, '');
  ok(!/includeSessions\s*=/.test(src), `${path.basename(f)} 里不再传旧的 includeSessions 导出开关`);
}
ok(/SessionImport\./.test(wizardScreenSrc), '导入界面接上了三模式');
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
  /DshConfigBackup\.preflightImport\(/.test(wizardScreenSrc),
  '界面走预检（加密包由预检用密码解开后才数得出会话）',
);

console.log(bad === 0 ? `\n全部通过（${n} 项断言）` : `\n${bad}/${n} 项失败`);
process.exit(bad === 0 ? 0 : 1);
