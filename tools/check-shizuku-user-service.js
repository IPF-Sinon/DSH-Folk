// Shizuku 用户服务的契约。
//
// 为什么值得单独一个门禁：这几条错了**本地完全看不出来** —— 编译通过、debug 变体能跑、
// 权限页显示"Shizuku 一切正常"，而实际表现是 `bindUserService` 提交成功却永远等不到
// onServiceConnected，五秒后报"通道不可用"。两边说法互相矛盾，真机上极难自查。
// 实测踩过：用户服务写成了 `class X : Service()` + 清单声明。
//
// 依据是 Shizuku-API 的 README 与官方 demo：
//   - "Unlike Bound service, the service class must implement IBinder interface"
//     → 类本身必须是 AIDL 的 Stub，不是 Service；
//   - demo 的 AndroidManifest 里**没有**这个服务的 <service> 声明（没有框架服务可声明）；
//   - demo 的 AIDL 里有 `void destroy() = 16777114;`（Shizuku 服务器写死的事务号）。
//
// 用法：node tools/check-shizuku-user-service.js
const fs = require("fs");

let bad = 0;
function must(cond, msg) {
  if (!cond) {
    bad++;
    console.log("  ✗ " + msg);
  } else {
    console.log("  ✓ " + msg);
  }
}

/** 去掉注释与字符串，供"不允许出现"这类断言使用（否则自己的注释会把断言满足掉）。 */
function code(src) {
  let out = "";
  let i = 0;
  while (i < src.length) {
    const c = src[i];
    const n = src[i + 1];
    if (c === "/" && n === "/") {
      while (i < src.length && src[i] !== "\n") i++;
    } else if (c === "/" && n === "*") {
      i += 2;
      while (i < src.length && !(src[i] === "*" && src[i + 1] === "/")) i++;
      i += 2;
    } else if (c === "<" && src.startsWith("<!--", i)) {
      // XML 注释也要剥：清单里那段"故意不声明"的说明本身提到了 <service> 与类名，
      // 不剥的话，哪天有人调整措辞就会把断言误伤成"清单里声明了服务"。
      const end = src.indexOf("-->", i);
      i = end < 0 ? src.length : end + 3;
    } else if (c === '"' || c === "'") {
      const q = c;
      i++;
      while (i < src.length && src[i] !== q) {
        if (src[i] === "\\") i++;
        i++;
      }
      i++;
    } else {
      out += c;
      i++;
    }
  }
  return out;
}

/** 只剥 XML 注释，**不剥字符串** —— 清单里的类名正是属性值，剥字符串会把要查的东西一起剥掉。 */
function stripXmlComments(src) {
  let out = "";
  let i = 0;
  while (i < src.length) {
    if (src.startsWith("<!--", i)) {
      const end = src.indexOf("-->", i);
      i = end < 0 ? src.length : end + 3;
    } else {
      out += src[i];
      i++;
    }
  }
  return out;
}

const SERVICE = "app/src/main/java/me/bmax/apatch/dsh/DshShizukuShellService.kt";
const CLIENT = "app/src/main/java/me/bmax/apatch/dsh/DshShizukuShell.kt";
const AIDL = "app/src/main/aidl/me/bmax/apatch/dsh/IDshShellService.aidl";
const MANIFEST = "app/src/main/AndroidManifest.xml";
const PROGUARD = "app/proguard-rules.pro";

const svc = fs.readFileSync(SERVICE, "utf8");
const cli = fs.readFileSync(CLIENT, "utf8");
const aidl = fs.readFileSync(AIDL, "utf8");
const manifest = fs.readFileSync(MANIFEST, "utf8");
const proguard = fs.readFileSync(PROGUARD, "utf8");

console.log("── 服务类形态 ──");
// 必须自己就是 Binder。写成 Service 的话 Shizuku 反射拿到的实例不是 IBinder，
// 于是它什么也回传不了 —— 这正是那个"永远连不上"的根因。
must(/class\s+DshShizukuShellService\s*:\s*IDshShellService\.Stub\(\)/.test(svc),
  "DshShizukuShellService 必须是 IDshShellService.Stub()（Binder），这是 Shizuku 的要求");
must(!/:\s*Service\s*\(/.test(code(svc)) && !/extends\s+Service/.test(code(svc)),
  "服务类不能是 android.app.Service（用户服务不是框架绑定服务）");
must(!/override\s+fun\s+onBind/.test(code(svc)),
  "服务类不该有 onBind —— 它不是框架服务，binder 就是它自己");
must(/override\s+fun\s+exec\(/.test(svc), "必须实现 exec（应用侧唯一的调用入口）");

console.log("── destroy：Shizuku 的保留事务 ──");
// AIDL 的规则：要么所有方法都带显式 id，要么都不带。destroy 的 id 是 Shizuku 定死的、
// 省不掉，所以 exec 也必须带 —— 漏了会在 CI 上炸成 :app:compileDebugAidl FAILED：
// "You must either assign id's to all methods or to none of them."（实测踩过一次）
{
  const decls = aidl.match(/^\s*(?:String|void|int|boolean|long)\s+\w+\s*\([^)]*\)\s*(?:=\s*\d+\s*)?;/gm) || [];
  const withoutId = decls.filter((d) => !/=\s*\d+\s*;/.test(d));
  must(decls.length >= 2, `AIDL 里解析出 ${decls.length} 个方法声明（预期至少 destroy 与 exec）`);
  must(withoutId.length === 0,
    `AIDL 带了显式 id 就必须每个方法都带，这些没带：${withoutId.map((d) => d.trim()).join(" / ")}`);
}
must(/void\s+destroy\(\)\s*=\s*16777114\s*;/.test(aidl),
  "AIDL 必须声明 Shizuku 的保留方法 destroy() = 16777114");
must(!/destroy\(\)\s*=\s*16777115/.test(aidl),
  "destroy 在 aidl 里要写 16777114（16777115 是 binder 侧的事务号，写错这条事务就没人应答）");
must(/override\s+fun\s+destroy\(\)/.test(svc), "服务必须实现 destroy()");
{
  const at = svc.indexOf("override fun destroy()");
  const body = at < 0 ? "" : svc.slice(at, at + 400);
  must(/System\.exit\(0\)/.test(body),
    "destroy() 里必须自己退出：Shizuku 文档明确说用户服务的进程不会被自动杀掉");
}

console.log("── 清单 ──");
// 官方 demo 的清单里没有这一项。声明成 <service> 既不生效，又会让下一个人以为
// 它是靠框架绑定起来的，从而照着错的方向改。
must(!/<service[\s\S]{0,200}DshShizukuShellService/.test(stripXmlComments(manifest)),
  "清单里**不能**声明 DshShizukuShellService（没有框架服务可声明，Shizuku 自己反射实例化）");

console.log("── 身份与 R8 ──");
must(/\.tag\(/.test(cli),
  "UserServiceArgs 必须显式设 tag：Shizuku 用它判身份，不设就退回类名，而 release 开了 R8，类名会变");
must(/\.version\(\s*\d+\s*\)/.test(cli),
  "UserServiceArgs 必须设 version：它变了 Shizuku 会当成另一个服务（销毁旧的、再起一个新的）");
must(/\.daemon\(/.test(cli), "UserServiceArgs 应显式设 daemon（决定用户服务是否常驻）");
// release 变体 isMinifyEnabled=true，而这个类要被**另一个进程反射实例化**，
// 应用侧没有静态引用，R8 会把它删掉或改名。row 里少一条 keep 规则，release 就凭空失效。
must(/-keep\s+class\s+me\.bmax\.apatch\.dsh\.DshShizukuShellService\s*\{[\s\S]{0,120}?<init>\(\)/.test(proguard),
  "proguard 必须 keep 这个类**及其无参构造**（否则 release 里它会被 R8 删掉或改名）");
{
  const minify = fs.readFileSync("app/build.gradle.kts", "utf8");
  const releaseMinify = /release\s*\{[\s\S]*?isMinifyEnabled\s*=\s*true/.test(minify);
  must(releaseMinify, "release 变体开着 minify，所以上面那条 keep 规则不是可选项");
}

console.log("── 客户端 ──");
must(/Shizuku\.bindUserService\(/.test(cli), "客户端必须用 bindUserService（官方做法）");
must(!/Shizuku\.newProcess/.test(code(cli)),
  "不能用 Shizuku.newProcess：那条路在本项目的依赖版本里返回 @RestrictTo 的内部类型，应用侧编译不过");
must(/Shizuku\.unbindUserService\(/.test(cli), "解绑要走 unbindUserService（配合 destroy 收尾）");

if (bad > 0) {
  console.log(`\ncheck-shizuku-user-service FAILED: ${bad} 处`);
  process.exit(1);
}
console.log("\ncheck-shizuku-user-service: 通过");
