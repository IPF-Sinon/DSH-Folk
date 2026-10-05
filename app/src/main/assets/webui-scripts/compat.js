// 旧内核 JS 兼容垫片：补齐 dsh 前端用到、而老内核没有的语言/平台 API。
// 由 WebScripts.kt 的注册表管理（顺序 / 开关 / 时机都在那边）；改这里也要改 tools/check-web-shim.js。

(function(){
  if (window.__dshFolkCompat) return; window.__dshFolkCompat = 1;
  // AbortSignal.any(signals)：任一 abort 即 abort，并带上原 reason。
  //
  // 用 WeakRef 持有返回的 controller：真实实现里「派生 signal」被源 signal 弱引用，
  // 没人用了就能回收。这里的调用点之一是
  //   AbortSignal.any([token.abort.signal, callerSignal])
  // 而 token.abort.signal 活得和整个挂载一样久 —— 若强引用，每次 RPC 都会在它上面
  // 留下一个永不摘除的闭包，一次长会话累积成千上万个。WeakRef 是 Chrome 84 起有的。
  if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.any !== 'function') {
    AbortSignal.any = function(signals){
      var list = [];
      var raw = signals || [];
      for (var i = 0; i < raw.length; i++) { if (raw[i]) list.push(raw[i]); }
      var ctrl = new AbortController();
      for (var n = 0; n < list.length; n++) {
        // 已经 abort 的输入要立刻反映，不能等事件
        if (list[n].aborted) { ctrl.abort(list[n].reason); return ctrl.signal; }
      }
      var weak = typeof WeakRef === 'function' ? new WeakRef(ctrl) : null;
      var onAbort = function(ev){
        var target = weak ? weak.deref() : ctrl;
        for (var j = 0; j < list.length; j++) {
          if (list[j].removeEventListener) list[j].removeEventListener('abort', onAbort);
        }
        // 派生 signal 已被回收 —— 没人再关心这次 abort，顺手把监听摘掉就行
        if (!target) return;
        var src = ev && ev.target ? ev.target : null;
        if (src) target.abort(src.reason); else target.abort();
      };
      for (var k = 0; k < list.length; k++) {
        if (list[k].addEventListener) list[k].addEventListener('abort', onAbort);
      }
      return ctrl.signal;
    };
  }
  // AbortSignal.timeout(ms)：110 已有，仅极旧内核兜底
  if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.timeout !== 'function') {
    AbortSignal.timeout = function(ms){
      var ctrl = new AbortController();
      setTimeout(function(){
        var err;
        try { err = new DOMException('signal timed out', 'TimeoutError'); }
        catch (e) { err = new Error('signal timed out'); }
        ctrl.abort(err);
      }, ms);
      return ctrl.signal;
    };
  }
  // Promise.withResolvers()：把 resolve/reject 掏到外面
  if (typeof Promise !== 'undefined' && typeof Promise.withResolvers !== 'function') {
    Promise.withResolvers = function(){
      var res, rej;
      var p = new Promise(function(a, b){ res = a; rej = b; });
      return { promise: p, resolve: res, reject: rej };
    };
  }
  // Iterator：ES2025 的迭代器助手。dsh 前端（documentpreview 插件）里有一句
  //   if (typeof Iterator.prototype.join !== 'function') Iterator.prototype.join = …
  // 它先求值 Iterator.prototype —— 内核没有这个全局时 typeof 保护不住，直接
  // ReferenceError，整个插件 import 失败、页面变成 "Failed to load plugins"。
  // 所以**全局对象本身**必须存在，助手也一并给全（别的地方可能真调它们）。
  // 原型取 %IteratorPrototype%（所有内置迭代器的共同原型），比手搓一个更像真货。
  if (typeof Iterator === 'undefined') {
    var IteratorPrototype = Object.getPrototypeOf(Object.getPrototypeOf([][Symbol.iterator]()));
    var defineHelper = function(name, fn){
      if (typeof IteratorPrototype[name] !== 'function') {
        Object.defineProperty(IteratorPrototype, name, {
          value: fn, writable: true, configurable: true
        });
      }
    };
    var wrap = function(iter){ return Object.assign(Object.create(IteratorPrototype), { __it: iter }); };
    Object.defineProperty(IteratorPrototype, '__it', {
      value: null, writable: true, configurable: true
    });
    // 迭代器协议本体：Symbol.iterator 返回自己（这才是「迭代器」的定义）
    if (typeof IteratorPrototype[Symbol.iterator] !== 'function') {
      Object.defineProperty(IteratorPrototype, Symbol.iterator, {
        value: function(){ return this; }, writable: true, configurable: true
      });
    }
    defineHelper('next', function(){
      return this.__it ? this.__it.next() : { done: true, value: undefined };
    });
    defineHelper('map', function(fn){
      var it = this; return wrap({ next: function(){
        var r = it.next(); return r.done ? r : { done: false, value: fn(r.value) };
      } });
    });
    defineHelper('filter', function(fn){
      var it = this; return wrap({ next: function(){
        for (;;) { var r = it.next(); if (r.done) return r; if (fn(r.value)) return r; }
      } });
    });
    defineHelper('take', function(n){
      var it = this, left = n; return wrap({ next: function(){
        if (left <= 0) return { done: true, value: undefined };
        left--; return it.next();
      } });
    });
    defineHelper('drop', function(n){
      var it = this, left = n; return wrap({ next: function(){
        while (left > 0) { left--; var r = it.next(); if (r.done) return r; }
        return it.next();
      } });
    });
    defineHelper('takeWhile', function(fn){
      var it = this; return wrap({ next: function(){
        var r = it.next(); if (r.done || !fn(r.value)) return { done: true, value: undefined };
        return r;
      } });
    });
    defineHelper('dropWhile', function(fn){
      var it = this, dropping = true; return wrap({ next: function(){
        for (;;) {
          var r = it.next(); if (r.done) return r;
          if (dropping && fn(r.value)) continue;
          dropping = false; return r;
        }
      } });
    });
    defineHelper('flatMap', function(fn){
      var it = this, inner = null; return wrap({ next: function(){
        for (;;) {
          if (inner) {
            var r = inner.next(); if (!r.done) return r; inner = null;
          }
          var o = it.next(); if (o.done) return o;
          var src = fn(o.value);
          if (!src) { inner = null; continue; }
          // 既接受可迭代对象（数组、Set、另一个垫片迭代器），也接受裸迭代器
          inner = typeof src[Symbol.iterator] === 'function' ? src[Symbol.iterator]() : src;
          if (!inner || typeof inner.next !== 'function') inner = null;
        }
      } });
    });
    defineHelper('reduce', function(fn, init){
      var it = this, acc = init, seen = arguments.length > 1;
      for (;;) {
        var r = it.next();
        if (r.done) {
          if (!seen) throw new TypeError('Reduce of empty iterator with no initial value');
          return acc;
        }
        if (!seen) { acc = r.value; seen = true; } else { acc = fn(acc, r.value); }
      }
    });
    defineHelper('toArray', function(){
      var out = [], r = this.next(); while (!r.done) { out.push(r.value); r = this.next(); } return out;
    });
    defineHelper('forEach', function(fn){
      var r = this.next(); while (!r.done) { fn(r.value); r = this.next(); }
    });
    defineHelper('some', function(fn){
      var r = this.next(); while (!r.done) { if (fn(r.value)) return true; r = this.next(); } return false;
    });
    defineHelper('every', function(fn){
      var r = this.next(); while (!r.done) { if (!fn(r.value)) return false; r = this.next(); } return true;
    });
    defineHelper('find', function(fn){
      var r = this.next(); while (!r.done) { if (fn(r.value)) return r.value; r = this.next(); }
    });
    defineHelper('join', function(sep){
      var parts = [], r = this.next();
      while (!r.done) { parts.push(String(r.value)); r = this.next(); }
      return parts.join(sep === undefined ? ',' : sep);
    });
    var IteratorGlobal = { prototype: IteratorPrototype };
    // Iterator.from(iterable | iterator)：数组、Set、字符串、生成器都吃得下
    IteratorGlobal.from = function(source){
      var it = source && typeof source[Symbol.iterator] === 'function'
        ? source[Symbol.iterator]() : source;
      if (!it || typeof it.next !== 'function') throw new TypeError('Iterator.from: not iterable');
      return Object.assign(Object.create(IteratorPrototype), { __it: it });
    };
    // 标准里 @@iterator 指回构造器，前端做 instanceof / 鸭子判断时可能碰到
    IteratorGlobal[Symbol.iterator] = function(){ return IteratorGlobal; };
    try { globalThis.Iterator = IteratorGlobal; } catch (e) { window.Iterator = IteratorGlobal; }
  }
  // Promise.try(fn, …args)：同步异常也要变成 rejected promise（pdf.js 在用）
  if (typeof Promise !== 'undefined' && typeof Promise.try !== 'function') {
    Promise.try = function(fn){
      var args = Array.prototype.slice.call(arguments, 1);
      return new Promise(function(resolve){ resolve(fn.apply(undefined, args)); });
    };
  }
  // Symbol.dispose / asyncDispose：缺了只会让属性键变成 undefined（不抛），
  // 但显式定义更像真货，且某些库会做 'dispose' in Symbol 之类的判断
  if (typeof Symbol === 'function') {
    if (!Symbol.dispose) {
      try { Object.defineProperty(Symbol, 'dispose', { value: Symbol('Symbol.dispose') }); } catch (e) {}
    }
    if (!Symbol.asyncDispose) {
      try { Object.defineProperty(Symbol, 'asyncDispose', { value: Symbol('Symbol.asyncDispose') }); } catch (e) {}
    }
  }
  // ArrayBuffer.prototype.transfer / transferToFixedLength：pdf.js 编译系统字体时用。
  // 真实现会 detach 原 buffer；这里用 slice 复制近似 —— 调用点只取返回值，
  // 代价是多一份内存，换来老内核上不炸（Chrome 114 起才有）。
  if (typeof ArrayBuffer !== 'undefined' && ArrayBuffer.prototype) {
    if (typeof ArrayBuffer.prototype.transfer !== 'function') {
      ArrayBuffer.prototype.transfer = function(newLength){
        var len = newLength === undefined ? this.byteLength : newLength;
        var out = new ArrayBuffer(len);
        new Uint8Array(out).set(new Uint8Array(this, 0, Math.min(len, this.byteLength)));
        return out;
      };
    }
    if (typeof ArrayBuffer.prototype.transferToFixedLength !== 'function') {
      ArrayBuffer.prototype.transferToFixedLength = function(newLength){
        var len = newLength === undefined ? this.byteLength : newLength;
        var out = new ArrayBuffer(len);
        new Uint8Array(out).set(new Uint8Array(this, 0, Math.min(len, this.byteLength)));
        return out;
      };
    }
  }
  // crypto.randomUUID()：它**只在安全上下文提供**。http://127.0.0.1 算安全，
  // 但开了「局域网访问」后页面是 http://<手机IP>:<端口>，不算 —— 于是
  // 前端里直接调它的地方（会话消息 id、附件草稿）会炸。
  // getRandomValues 在非安全源照常可用，按 RFC 4122 拼一个 v4 出来即可。
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID !== 'function'
      && typeof crypto.getRandomValues === 'function') {
    crypto.randomUUID = function(){
      var b = crypto.getRandomValues(new Uint8Array(16));
      b[6] = (b[6] & 0x0f) | 0x40;   // version 4
      b[8] = (b[8] & 0x3f) | 0x80;   // variant 10xx
      var h = [];
      for (var i = 0; i < 16; i++) h.push((b[i] + 0x100).toString(16).slice(1));
      return h[0]+h[1]+h[2]+h[3] + '-' + h[4]+h[5] + '-' + h[6]+h[7]
        + '-' + h[8]+h[9] + '-' + h[10]+h[11]+h[12]+h[13]+h[14]+h[15];
    };
  }
})();