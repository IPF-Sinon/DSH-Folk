#!/usr/bin/env node
// 门禁：运行时重启换了 token 之后，WebUI 必须带新地址重载一次（否则页面一直报 bad_token）。
//
// 真机（issue #12）：切运行时 / 重启服务之后，网页上所有请求都带着**旧 token**，
// 用户看到 {"ok":false,"error":"token 不匹配","reason":"bad_token"}。
// 原因：DshWebUiActivity 只在创建时 loadUrl 一次，从不观察 DshRuntime.state 里的 webToken。
//
// 两条容易写坏的地方，这里都盯住：
//  1. 只认「记下来的 token 与当前不同」——比 URL 字符串会被 WebView 规范化坑到，反复重载；
//  2. 只订阅 token 这一项（map + distinctUntilChanged）——订阅整份 state 会让启动期间的
//     progress/message 每次变化都重组整个页面。
const fs = require('fs');
const path = require('path');

const errors = [];
const root = path.resolve(__dirname, '..');
const src = fs.readFileSync(path.join(root, 'app/src/main/java/me/bmax/apatch/ui/DshWebUiActivity.kt'), 'utf8');
const must = (c, m) => { if (!c) errors.push(m); };

must(/private var loadedWebToken: String\? = null/.test(src), '缺 loadedWebToken：无法判断要不要重载');
must(/\n\s+loadUrl\(url\)\n\s+loadedWebToken = DshRuntime\.state\.value\.webToken/.test(src),
  '首次 loadUrl 之后没有记下当前 token（那重载逻辑会把首屏也当成「变了」）');

must(/map \{ it\.webToken \}\.distinctUntilChanged\(\)/.test(src),
  '没有只订阅 token 这一项（订阅整份 state 会在启动期间反复重组整个页面）');
must(/\.collectAsState\(initial = DshRuntime\.state\.value\.webToken\)/.test(src),
  'collectAsState 没有给初值（首帧会拿到 null，白跑一轮）');

const effect = (src.match(/LaunchedEffect\(webToken\) \{[\s\S]*?\n\s+\}/) || [''])[0];
must(effect !== '', '缺 LaunchedEffect(webToken) 重载逻辑');
must(/if \(webToken\.isNullOrBlank\(\)\) return@LaunchedEffect/.test(effect),
  'token 为空时也会重载（启动早期 webToken 是 null，会白重载一次）');
must(/val previous = loadedWebToken \?: return@LaunchedEffect/.test(effect),
  '没有「还没加载过就不重载」的保护');
must(/if \(previous == webToken\) return@LaunchedEffect/.test(effect),
  '没有「token 没变就不重载」的保护（会反复重载）');
must(/wv\.loadUrl\(DshRuntime\.webUrl\(\)\)/.test(effect),
  '重载用的不是带新 token 的 webUrl()');

if (errors.length) {
  console.error('check-webui-token FAILED:');
  for (const e of errors) console.error('  ✗ ' + e);
  process.exit(1);
}
console.log('check-webui-token: 通过');
