// 拦 blob:/data: 下载：WebView 对这两种 scheme 不会触发 DownloadListener。
// 由 WebScripts.kt 的注册表管理（顺序 / 开关 / 时机都在那边）；改这里也要改 tools/check-web-shim.js。

(function(){
  if (window.__dshFolkBlobShim) return; window.__dshFolkBlobShim = 1;
  function grab(href, name){
    fetch(href).then(function(r){return r.blob()}).then(function(b){
      var fr = new FileReader();
      fr.onloadend = function(){
        var s = String(fr.result || '');
        var i = s.indexOf(',');
        if (i >= 0) DshFolkDownload.save(s.slice(i+1), name || 'download');
      };
      fr.readAsDataURL(b);
    }).catch(function(e){ console.warn('dsh-folk blob download failed', e); });
  }
  document.addEventListener('click', function(ev){
    var a = ev.target && ev.target.closest ? ev.target.closest('a[download]') : null;
    if (!a) return;
    var href = a.getAttribute('href') || '';
    if (href.indexOf('blob:') !== 0 && href.indexOf('data:') !== 0) return;
    ev.preventDefault();
    grab(href, a.getAttribute('download'));
  }, true);
})();