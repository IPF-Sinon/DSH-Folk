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

// 5. 中英文案都在
for (const [name, text] of [['values/dsh_strings.xml', en], ['values-zh-rCN/dsh_strings.xml', zh]]) {
  ok(/dsh_userscripts_builtin_upload"/.test(text) && /dsh_userscripts_builtin_upload_summary"/.test(text),
    name + ' 有上传诊断的标题与摘要');
}

if (failures > 0) { console.error('check-web-upload FAILED: ' + failures + ' 项'); process.exit(1); }
console.log('check-web-upload: 通过');
