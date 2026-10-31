// 上传诊断载体（**不是修复**）。
//
// 背景：dsh 网页端把「上传失败」吞成一句通用文案 —— 上传路由即便业务失败也回 HTTP 200，
// 真实原因只在响应体的 {ok:false,error:{code,message}} 里，前端不显示也不打 console。
// 上游为此留了启动前钩子 window.__DSH_FILE_UPLOAD__ = { fetch }（dsh-client-file-upload 的 README
// 明写「存在该钩子时，服务通过页面自己提供的 Fetch 载体发送请求体」）。这里装上它，只为在
// 失败当下把 status / 体积 / 响应体前 300 字带进 App 运行日志（随 bugreport 出来）。
//
// 由 WebScripts.kt 的注册表管理（顺序 / 开关 / 时机都在那边）；改这里也要改 tools/check-web-upload.js。

(function(){
  if (window.__dshFolkFileUpload) return; window.__dshFolkFileUpload = 1;
  // 宿主或上游自己给过就不覆盖（我们也可能是那个给过的，但幂等哨兵已经挡住了）
  if (window.__DSH_FILE_UPLOAD__) return;
  if (typeof fetch !== 'function') return;

  function describe(body){
    try {
      if (body === null || body === undefined) return 'none';
      if (typeof Blob !== 'undefined' && body instanceof Blob) return 'blob size=' + body.size + ' type=' + (body.type || '-');
      if (typeof ReadableStream !== 'undefined' && body instanceof ReadableStream) return 'stream';
      if (typeof body.byteLength === 'number') return 'bytes len=' + body.byteLength;
      return typeof body;
    } catch (e) { return 'unreadable:' + (e && e.name); }
  }

  window.__DSH_FILE_UPLOAD__ = {
    fetch: function(path, init){
      var opts = Object.assign({}, init || {});
      // 同源请求本来就会带 cookie，这里显式写出来，避免以后宿主改 origin 策略时悄悄丢鉴权
      opts.credentials = 'include';
      var size = describe(opts.body);
      return fetch(path, opts).then(function(res){
        try {
          // clone 一份读响应体，不能动上游要读的那份
          res.clone().text().then(function(text){
            var bad = res.status !== 200 || /"ok"\s*:\s*false/.test(String(text));
            if (bad) {
              console.error('[DSH-Folk upload] ' + path + ' status=' + res.status + ' body=' + size + ' resp=' + String(text).slice(0, 300));
            }
          }, function(){});
        } catch (e) {}
        return res;
      }, function(err){
        console.error('[DSH-Folk upload] ' + path + ' body=' + size + ' transport-failed: ' + (err && (err.name + ': ' + err.message)));
        throw err;
      });
    }
  };
})();
