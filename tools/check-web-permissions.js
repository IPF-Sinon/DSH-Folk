#!/usr/bin/env node
/**
 * WebView 里 `getUserMedia` 的宿主侧前提（网页语音输入）的门禁。
 *
 * ## 为什么要查
 *
 * 用户报「对话页点语音输入 → 麦克风权限未开启，请在浏览器和系统设置中允许访问」，
 * 而系统设置里 RECORD_AUDIO 明明是允许的。原因是宿主从没重写
 * `WebChromeClient.onPermissionRequest`：AOSP 的默认实现是 `request.deny()`，
 * 于是页面里 `getUserMedia` 恒抛 `NotAllowedError`（上游客户端插件正是在
 * NotAllowedError 上显示那句提示）。这是**只看页面、只看系统权限都看不出来**的一层：
 * JS 侧完全正确，系统侧完全正确，缺的是中间那次点头。
 *
 * 同时 Chromium M117+ 还要求宿主声明 `MODIFY_AUDIO_SETTINGS`：少了它，logcat 里
 * `cr_media` 会打 "Requires MODIFY_AUDIO_SETTINGS and RECORD_AUDIO. No audio device
 * will be available for recording"。
 *
 * 两条都是**静默失败**型：不崩、不报错、只有一个不像原因的提示，所以钉成静态检查。
 */
const fs = require("fs");

const SRC_WEBUI = "app/src/main/java/me/bmax/apatch/ui/DshWebUiActivity.kt";
const MANIFEST = "app/src/main/AndroidManifest.xml";

const webui = fs.readFileSync(SRC_WEBUI, "utf8");
const manifest = fs.readFileSync(MANIFEST, "utf8");

let n = 0;
let bad = 0;
function ok(cond, label) {
  n++;
  console.log("  " + (cond ? "✓" : "✗") + " " + label);
  if (!cond) bad++;
}

console.log("\n── 清单：录音 + 音频会话 ──");
{
  const rec = [...manifest.matchAll(/android\.permission\.RECORD_AUDIO/g)].length;
  const mod = [...manifest.matchAll(/android\.permission\.MODIFY_AUDIO_SETTINGS/g)].length;
  ok(rec === 1, `RECORD_AUDIO 声明一次（实际 ${rec}）`);
  ok(mod === 1, `MODIFY_AUDIO_SETTINGS 声明一次（实际 ${mod}）`);
  // 声明了权限却忘了放宽隐式硬件要求的检查在 check-native-caps.js 里；这里只管这一条
  ok(/<uses-permission android:name="android\.permission\.MODIFY_AUDIO_SETTINGS" \/>/.test(manifest),
    "MODIFY_AUDIO_SETTINGS 是一条正常形式的 uses-permission");
}

console.log("\n── 授权回调必须落在 WebChromeClient 里 ──");
{
  // 只看**这个对象**里的重写：写在别处（比如某个工具类）等于没接上
  const at = webui.indexOf("webChromeClient = object");
  const until = webui.indexOf("setDownloadListener", at);
  const client = at >= 0 && until > at ? webui.slice(at, until) : "";
  ok(client.length > 0, "定位到 WebChromeClient 对象体（切片标记没失效）");
  ok(/override fun onPermissionRequest\(/.test(client),
    "重写了 onPermissionRequest（不重写 = AOSP 默认 deny = getUserMedia 恒失败）");
  ok(/PermissionRequest\.RESOURCE_AUDIO_CAPTURE/.test(client),
    "认的是 RESOURCE_AUDIO_CAPTURE（网页要的正是麦克风）");
  ok(/req\.resources\.contains\(/.test(client),
    "按**这次请求**要的资源判断，不是无条件 grant");
}

console.log("\n── 已经授予：直接给（快路径） ──");
{
  ok(/private fun grantableMedia\(req: PermissionRequest\): Array<String>/.test(webui),
    "有 grantableMedia：算出「现在真正授权得了的资源」（类型是 android.webkit.PermissionRequest，不是 WebChromeClient 的嵌套类）");
  ok(/ContextCompat\.checkSelfPermission\(this, Manifest\.permission\.RECORD_AUDIO\)/.test(webui),
    "麦克风按 RECORD_AUDIO 的实际授权状态判定（不靠缓存标志）");
  ok(/RESOURCE_VIDEO_CAPTURE[\s\S]{0,120}Manifest\.permission\.CAMERA/.test(webui),
    "摄像头同样按 CAMERA 的实际授权状态判定");
  ok(/else -> false/.test(webui), "其余资源（DRM / MIDI）保持默认：不替用户点头");
  ok(/req\.grant\(granted\)/.test(webui), "授权得了就 grant");
}

console.log("\n── 还没授予：弹系统框 + 挂住这次请求 ──");
{
  ok(/pendingAudioRequest = req/.test(webui),
    "挂住这次 PermissionRequest（丢了它就是默认拒绝 —— 用户报的那条路径）");
  ok(/micPermission\.launch\(Manifest\.permission\.RECORD_AUDIO\)/.test(webui),
    "现场弹系统授权框（网页那一步不该只让用户去设置里猜）");
  ok(/super\.onPermissionRequest\(req\)/.test(webui),
    "不需要麦克风的请求交回 WebView 默认处理");
  ok(/pendingAudioRequest\?\.deny\(\)[\s\S]{0,160}pendingAudioRequest = req/.test(webui),
    "上一个还没答就放掉（否则那个页面被永久卡住）");
  // 注册时机：registerForActivityResult 必须在 STARTED 之前（onCreate），否则回调收不到
  const onCreate = webui.indexOf("override fun onCreate(");
  const onDestroy = webui.indexOf("override fun onDestroy(");
  const reg = webui.indexOf("micPermission = registerForActivityResult(");
  ok(onCreate > 0 && onDestroy > onCreate && reg > onCreate && reg < onDestroy,
    "launcher 在 onCreate 里注册（不是第一次点击时才注册）");
  ok(/ActivityResultContracts\.RequestPermission\(\)/.test(webui),
    "用的是运行时权限契约（RECORD_AUDIO 是危险权限）");
}

console.log("\n── 结果回来：答 grant 或 deny，答一次 ──");
{
  const at = webui.indexOf("micPermission = registerForActivityResult(");
  const body = at >= 0 ? webui.slice(at, at + 1400) : "";
  ok(body.length > 0, "定位到授权结果回调");
  ok(/pendingAudioRequest = null/.test(body), "先清空挂起的那次请求");
  ok(/req\.grant\(resources\)/.test(body), "允许 → grant（只 grant 真正授权得了的资源）");
  ok(/req\.deny\(\)/.test(body), "拒绝 → deny（拒绝也要答，否则页面一直等）");
  ok(/if \(granted\) grantableMedia\(req\) else emptyArray\(\)/.test(body),
    "拒绝时不 grant 任何一项");
  // onPermissionRequest 里也答一次（快路径），onDestroy 再兜一次
  ok(/pendingAudioRequest\?\.deny\(\)/.test(webui.slice(webui.indexOf("override fun onDestroy("))),
    "onDestroy 放掉没答完的那次请求（不留 WebView 对象给下一次加载）");
}

console.log(bad === 0 ? `\n全部通过（${n} 项断言）` : `\n${bad}/${n} 项失败`);
process.exit(bad === 0 ? 0 : 1);
