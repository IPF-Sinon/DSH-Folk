// 系统栏内边距：把安全区交给页面自己避让（#root 内边距 + 断线提示条的 top）。
// 由 WebScripts.kt 的注册表管理（顺序 / 开关 / 时机都在那边）；改这里也要改 tools/check-web-shim.js。
// 唯一需要原生参数的内置脚本：正文里那个 /* ... */ 表达式是占位符，注入前整体替换成真实 {t,r,b,l}；
// 替换不到（占位符被改掉）就保持全 0 —— 那本身是合法 JS，绝不会把语法错注进页面。

(function(){
  var state = /*__DSH_PARAMS__*/{ t: 0, r: 0, b: 0, l: 0 };
  function css(){
    return '#root{box-sizing:border-box!important;padding:' +
      state.t + 'px ' + state.r + 'px ' + state.b + 'px ' + state.l + 'px!important}' +
      // 「连接已断开，正在重连」那条提示固定在 top:0，是页面里唯一够得着状态栏的东西。
      // 用类名子串选（上游的类名带构建哈希，选不中时这条规则自动失效，不会误伤别处）：
      // 另外两个同样含 _banner_ 的类（markdown 代码块标题栏）都是静态定位，top 对它们
      // 是空操作，所以这条只可能命中那条提示条。
      '[class*="_banner_"]{top:' + state.t + 'px!important}';
  }
  function render(){
    if (!document.documentElement) return false;
    var el = document.getElementById('__dsh_folk_insets__');
    if (!el) {
      el = document.createElement('style');
      el.id = '__dsh_folk_insets__';
      (document.head || document.documentElement).appendChild(el);
    }
    el.textContent = css();
    return true;
  }
  window.__dshFolkInsets = function(t, r, b, l){
    state.t = t; state.r = r; state.b = b; state.l = l;
    render();
  };
  if (!render()) document.addEventListener('DOMContentLoaded', render);
})();