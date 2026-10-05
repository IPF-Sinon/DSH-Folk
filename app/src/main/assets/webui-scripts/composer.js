// 手机回车换行：把 WebUI 输入框里的裸回车从「发送」改成「换行」。
// 由 WebScripts.kt 的注册表管理（顺序 / 开关 / 时机都在那边）；改这里也要改 tools/check-web-shim.js。

(function(){
  if (window.__dshFolkComposerEnter) return; window.__dshFolkComposerEnter = 1;
  var coarse = false;
  try { coarse = typeof matchMedia === 'function' && matchMedia('(pointer: coarse)').matches; } catch (e) {}
  if (!coarse) return;

  var INPUT = '[data-composer-input]';
  var CARD = '[data-composer-card]';

  // 菜单是否打开：优先信宿主自己的 aria 标记（触发按钮就在输入卡片里），
  // 再兜一层浮层可见性（菜单本体走 portal 渲染在卡片外）。
  function menuOpen(el){
    try {
      var card = (el && typeof el.closest === 'function' && el.closest(CARD)) || null;
      if (card !== null && card.querySelector('[aria-haspopup][aria-expanded="true"]') !== null) return true;
      var list = document.querySelectorAll('[role="menu"],[role="listbox"]');
      for (var i = 0; i < list.length; i++) {
        var m = list[i];
        if (m.getAttribute('aria-hidden') === 'true') continue;
        if (m.getClientRects && m.getClientRects().length > 0) return true;
      }
    } catch (e) {}
    return false;
  }

  function onKeyDown(e){
    try {
      if (e.key !== 'Enter' && e.code !== 'Enter' && e.keyCode !== 13) return;
      if (e.shiftKey || e.ctrlKey || e.altKey || e.metaKey) return;
      // 合成期：回车属于输入法（确认候选词），绝不动
      if (e.isComposing || e.keyCode === 229) return;
      var t = e.target;
      if (!t || typeof t.closest !== 'function' || t.closest(INPUT) === null) return;
      if (menuOpen(t)) return;
      // 拦住上游的"发送"，改发一个 Shift+Enter 走它自己的换行路径。
      // stopImmediatePropagation 之所以有效：本监听在 document-start 注册，
      // 排在宿主那些 window 监听之前。
      e.preventDefault();
      e.stopImmediatePropagation();
      t.dispatchEvent(new KeyboardEvent('keydown', {
        key: 'Enter', code: 'Enter', shiftKey: true,
        bubbles: true, cancelable: true, composed: true
      }));
    } catch (err) {}
  }

  window.addEventListener('keydown', onKeyDown, true);
})();