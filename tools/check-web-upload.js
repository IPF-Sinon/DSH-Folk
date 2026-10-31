#!/usr/bin/env node
// 门禁：上传失败必须自带原因（issue 反馈：只显示「上传失败，点击重试」，日志里一个字都没有）。
//
// 背景（上游源码）：dsh 网页端把 Blob 通过 blob: Worker 的 XHR 投到
// POST /api/session/uploadFileBinary；该路由**业务失败也回 HTTP 200**，真实原因只在
// 响应体 {ok:false,error:{code,message}} 里，前端只渲染 file.uploadFailed。
// 上游为此留了启动前钩子 window.__DSH_FILE_UPLOAD__ = { fetch }（README 明写）。
//
// DSH-Folk 这边要做的是「让下一次失败说清原因」，不是修复上传本身：
//   1) 内置注入脚本文件装钩子并把 status/体积/响应体打进 console（[DSH-Folk upload] 前缀）；
//   2) onConsoleMessage 对这段前缀**不论级别**都落进运行日志；
//   3) 启动时体检一次附件存储（空间/属主/写入），日志带 [attach] 前缀。
const fs = require('fs');
const path = require('path');
const root = path.resolve(__dirname, '..');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');
const ok = (c, m) => { if (!c) { console.error('  ✗ ' + m); failures++; } };
let failures = 0;

const asset = read('app/src/main/assets/webui-scripts/file-upload.js');
const scripts = read('app/src/main/java/me/bmax/apatch/dsh/WebScripts.kt');
const webui = read('app/src/main/java/me/bmax/apatch/ui/DshWebUiActivity.kt');
const runtime = read('app/src/main/java/me/bmax/apatch/dsh/DshRuntime.kt');
const en = read('app/src/main/res/values/dsh_strings.xml');
const zh = read('app/src/main/res/values-zh-rCN/dsh_strings.xml');

// 1. 钩子本身
ok(/window\.__DSH_FILE_UPLOAD__\s*=/.test(asset), '注入脚本设置 window.__DSH_FILE_UPLOAD__ 钩子');
ok(/if \(window\.__DSH_FILE_UPLOAD__\) return;/.test(asset), '已有钩子就不覆盖（宿主/上游给过就尊重它）');
ok(/credentials\s*=\s*'include'/.test(asset), '转发时带上 credentials=include（同源 cookie 不能丢）');
ok(/\[DSH-Folk upload\]/.test(asset), '失败时打固定前缀，便于 App 侧识别');
ok(/clone\(\)\.text\(\)/.test(asset), '读响应体用 clone，不动上游要读的那份');
ok(/ok.*\\s\*:\\s\*false/.test(asset) || /"ok"[^\n]{0,20}false/.test(asset),
  '把 {ok:false} 也算失败（路由业务失败也回 200）');

// 2. 注册表
ok(/id = "file-upload",[\s\S]{0,220}?runAt = RunAt\.START,[\s\S]{0,90}?asset = "webui-scripts\/file-upload\.js"/.test(scripts),
  '注册表里有 file-upload 条目，且是 document-start（上游在插件启动时读钩子，晚了就无效）');

// 3. 落日志：前缀消息不论级别都记
ok(/startsWith\("\[DSH-Folk upload\]"\)/.test(webui), 'onConsoleMessage 识别上传前缀');
ok(/if \(uploadDiag \|\| m\.messageLevel\(\) == android\.webkit\.ConsoleMessage\.MessageLevel\.ERROR\)/.test(webui),
  '前缀消息不受级别限制（console.error 在某些内核会被降级）');
ok(/DshRuntime\.appendLog\("\[page\] " \+ line\)/.test(webui), '仍然写进运行日志（随 bugreport 带出）');
ok(/if \(uploadDiag\) "upload: " else "page error: "/.test(webui), '上传诊断的日志行标成 upload 而不是 page error（便于 grep）');

// 4. 启动体检
ok(/private fun attachmentDiag\(\)/.test(runtime), '有 attachmentDiag()');
ok(/\[attach\] /.test(runtime), '体检结果带 [attach] 前缀');
ok(/WRITE_OK/.test(runtime) && /df -h \/root/.test(runtime), '体检真的看空间与写入（不是只打一行结论）');
ok(/^        scope\.launch\(kotlinx\.coroutines\.Dispatchers\.IO\) \{ attachmentDiag\(\) \}$/m.test(runtime),
  'startServer 里后台调用（不拖慢启动）');
{
  // 这条是 CI 真红过一次的教训：Kotlin 普通字符串里的 $ 就是模板插值，
  // 写 "of=\"$f\"" 会变成「Unresolved reference 'f'」，而本地 kt-lint 只看词法闭合、查不出来。
  const i = runtime.indexOf("private fun attachmentDiag");
  const j = runtime.indexOf("private fun forwardOutput", i);
  const code = runtime.slice(i, j).split("\n").map((l) => l.replace(/\/\/.*$/, "")).join("\n");
  ok(i > 0 && j > i && !/\$/.test(code), 'attachmentDiag 的命令串里没有未转义的 $（否则 Kotlin 当成模板，CI 才报）');
}

// 5. 中英文案都在
for (const [name, text] of [['values/dsh_strings.xml', en], ['values-zh-rCN/dsh_strings.xml', zh]]) {
  ok(/dsh_userscripts_builtin_upload"/.test(text) && /dsh_userscripts_builtin_upload_summary"/.test(text),
    name + ' 有上传诊断的标题与摘要');
}

// 6. 附件落盘补丁（真机 ATTACHMENT_WRITE_FAILED：硬链接/目录 fsync 在 Android 上被拒）
{
  ok(/private fun patchAttachmentStore\(\)/.test(runtime), '有 patchAttachmentStore()');
  ok(/^        patchAttachmentStore\(\)$/m.test(runtime), 'startServer 里真的调用（只定义不调用=永远不生效）');
  ok(/dsh-attachment-local\/lib\/index\.js/.test(runtime), '补丁打在上游 dsh-attachment-local 上');
  ok(/"async function publishStagedObject\(root, target, staged\) \{"/.test(runtime) &&
    /"async function publishImmutableAlias\(root, source, target, sha256\) \{"/.test(runtime),
    '发布路径的两处入口都被改写（落盘简化成直接复制；漏一处就还有一条路会失败）');
  ok(/copyFile\(staged\.path, target\)/.test(runtime) && /copyFile\(source, target\)/.test(runtime),
    '两条发布路径都用 copyFile 而不是 link（proroot 的 link2symlink 会把 link 变成符号链接，删临时文件后就成了悬空链接）');
  ok(/handle\.sync\(\)\.catch/.test(runtime), '目录 fsync 失败被容忍（不再让整次上传失败）');
  ok(/if \(src\.contains\("DSH-Folk: Android"\)\) return/.test(runtime), '已打过就跳过（幂等，不重复追加）');
  ok(/out\.contains\("import \{ chmod, copyFile, link,"\)/.test(runtime),
    '上一版已打过 v1 的机器也能升级（import 里已有 copyFile 时不能判成 hunk 未命中）');
  ok(/\[DSH-Folk attach\] publish failed/.test(runtime) && /\[DSH-Folk attach\] alias failed/.test(runtime) &&
    /\[DSH-Folk attach\] stage failed/.test(runtime),
    '三个失败点都把底层 errno 打进日志（RemoteError 只带 reason，cause 会被丢掉）');
  ok(/LINK_OK/.test(runtime) && /FSYNC_DIR_OK/.test(runtime), '启动体检会实探 link 与目录 fsync（补丁覆盖不了时靠它定位）');
  ok(/存储补丁：import=/.test(runtime) && /" publish="/.test(runtime) && /" alias="/.test(runtime) && /" stage="/.test(runtime),
    '逐个 hunk 报告是否命中（运行时版本变了要一眼看出来，而不是静默不生效）');
  ok(/存储补丁不完整，未写回/.test(runtime), 'hunk 没全命中就不写回（宁可不动，也不写坏依赖）');
}

// 7. 行为：脚本必须能真跑起来（语法错的话钩子永远装不上，而 kt-lint 不查 JS）
{
  const vm = require('vm');
  const ctx = {
    console: { error: () => {} },
    fetch: async () => ({ status: 200, clone: () => ({ text: async () => '{}' }) }),
  };
  ctx.window = ctx;
  vm.createContext(ctx);
  let err = null;
  try { vm.runInContext(asset, ctx); } catch (e) { err = e; }
  ok(err === null, '注入脚本可执行' + (err ? '（' + err.message + '）' : ''));
  ok(ctx.__DSH_FILE_UPLOAD__ && typeof ctx.__DSH_FILE_UPLOAD__.fetch === 'function',
    '装上后 __DSH_FILE_UPLOAD__.fetch 可调用');
}

if (failures > 0) { console.error('check-web-upload FAILED: ' + failures + ' 项'); process.exit(1); }
console.log('check-web-upload: 通过');
