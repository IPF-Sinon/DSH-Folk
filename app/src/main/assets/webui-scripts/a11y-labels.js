// 给网页里的可编辑元素补无障碍名字（把 placeholder 抄成 aria-label）。
// 由 WebScripts.kt 的注册表管理（顺序 / 开关 / 时机都在那边）；改这里也要改 tools/check-web-shim.js。

(function(){
  if (window.__dshFolkA11yLabel) return; window.__dshFolkA11yLabel = 1;
  function label(){
    var els = document.querySelectorAll('input, textarea, [contenteditable="true"], [data-composer-input]');
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      try {
        if (el.getAttribute('aria-label') || el.getAttribute('aria-labelledby') || el.getAttribute('title')) continue;
        var name = el.getAttribute('placeholder') || el.getAttribute('data-placeholder') || '';
        if (!name) continue;
        el.setAttribute('aria-label', name);
      } catch (e) {}
    }
  }
  function start(){
    label();
    try { new MutationObserver(label).observe(document.documentElement, {subtree:true, childList:true}); } catch (e) {}
  }
  if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', start); } else { start(); }
})();