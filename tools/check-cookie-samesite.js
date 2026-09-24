#!/usr/bin/env node
// 门禁：外部浏览器登录修复（把 dsh 会话 cookie 从 SameSite=Strict 放宽为 Lax）不许被改坏。
//
// 背景：dsh 的浏览器会话认证在 ?token= 校验通过后用 303 重定向下发一个
// `HttpOnly; SameSite=Strict` 的会话 cookie。App 用 Intent(ACTION_VIEW) 拉起外部 Chrome
// 属于「外部发起的顶层导航」，Chrome 对 Strict cookie 在这种导航（及随后 303 跳转/刷新按钮）
// 上一律不带 → 服务端收不到 cookie → 401「authentication required」。手动地址栏回车是第一方
// 导航才带得上。修法=启动前幂等 patch rootfs 里被服务端加载的 dsh-client-connection/lib/index.js，
// 把该 cookie 放宽为 SameSite=Lax（顶层 GET 导航都会带，跨站子请求/POST 仍挡住）。
const fs = require('fs');
const path = require('path');

const errors = [];
const root = path.resolve(__dirname, '..');
const rt = fs.readFileSync(path.join(root, 'app/src/main/java/me/bmax/apatch/dsh/DshRuntime.kt'), 'utf8');

function must(cond, msg) { if (!cond) errors.push(msg); }

// 1. 补丁函数存在
must(/private fun patchBrowserCookieSameSite\(\)/.test(rt),
  '缺 patchBrowserCookieSameSite()：外部浏览器登录修复被删了');

// 2. 启动前会调用它（startServer 里，和 patchLanHost 同批）
must(/patchBrowserCookieSameSite\(\)/.test((rt.match(/fun startServer\(\)[\s\S]*?patchBrowserCookieSameSite\(\)/) || [''])[0]) ||
     /patchLanHost\(\)\s*\n[\s\S]{0,200}patchBrowserCookieSameSite\(\)/.test(rt),
  'patchBrowserCookieSameSite() 没有在启动路径里被调用');

// 3. 精确地把 Strict 换成 Lax（成对，别误伤别的字符串）
must(rt.includes('"HttpOnly; SameSite=Strict"') && rt.includes('"HttpOnly; SameSite=Lax"'),
  '补丁没有把 `HttpOnly; SameSite=Strict` 成对替换为 `HttpOnly; SameSite=Lax`');
must(/\.replace\("HttpOnly; SameSite=Strict", "HttpOnly; SameSite=Lax"\)/.test(rt),
  '缺少精确的 replace("HttpOnly; SameSite=Strict","HttpOnly; SameSite=Lax")');

// 4. 目标文件枚举命中 dsh-client-connection 的认证源码，且不深走整棵 node_modules
must(/dsh-client-connection\/lib\/index\.js/.test(rt),
  '补丁没有指向 dsh-client-connection/lib/index.js（认证逻辑所在）');
must(/private fun clientConnectionIndexFiles\(\): List<File>/.test(rt),
  '缺 clientConnectionIndexFiles()：目标文件枚举器');
must(!/walkTopDown\(\)[\s\S]{0,120}dsh-client-connection/.test(rt),
  '不要用 walkTopDown 深走整棵 node_modules 找该文件（改用有界枚举）');

// 5. 日志串齐（en + zh）
for (const f of ['app/src/main/res/values/dsh_strings.xml', 'app/src/main/res/values-zh-rCN/dsh_strings.xml']) {
  const s = fs.readFileSync(path.join(root, f), 'utf8');
  must(s.includes('name="dsh_log_cookie_samesite_patched"'),
    `${f} 缺 dsh_log_cookie_samesite_patched`);
}

if (errors.length) { console.error('check-cookie-samesite FAILED:'); for (const e of errors) console.error('  ✗ ' + e); process.exit(1); }
console.log('check-cookie-samesite: 通过');
