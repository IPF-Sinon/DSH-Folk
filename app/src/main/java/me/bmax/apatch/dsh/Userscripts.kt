package me.bmax.apatch.dsh

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File

/**
 * 用户脚本：油猴 `.user.js` 的一个**够用子集**，只作用于本应用自己的那个页面。
 *
 * ## 为什么只服务自己那一页
 *
 * WebView 只加载本机 dsh web（`127.0.0.1:<port>`，见 `DshWebUiActivity`），所以油猴里最有
 * 分量的那部分 —— 跨站 `@match`、`GM_xmlhttpRequest` 绕 CORS、地址栏导航 —— 现在都用不上。
 * 真正有用的是另一半：**在页面脚本之前跑一段自己的 JS**，用来改我们自己的界面。应用内置的
 * 那几段（兼容/内边距/回车/无障碍名字/blob 下载，见 [WebScripts]）现在也正是这份脚本管道里的
 * 前五项 —— 同一处注入、同一套 origin 规则、同一个包装；这一档的区别只在于把"贴一段试试"
 * 的门槛降到**不用发版**。
 *
 * ## 与"插件 client bundle"的关系
 *
 * dsh 插件本来就能带客户端包（`./lib/client.cjs`），那是"装成插件"的正规路（有版本、可更新、
 * 可卸载）；用户脚本是**不打包**的那一档。两者粒度不同，不是替代。
 *
 * ## 边界（先说清楚，省得当成 bug）
 *
 * - 元数据块按油猴的写法必须用 `//` 行注释：块注释里认不出来。
 * - `@run-at` 支持 start / end / idle；**SPA 路由变化不会重跑**（脚本要盯自己用
 *   MutationObserver）。
 * - 一个脚本语法错只毁**它自己**：每个脚本是**独立的一段注入**（见 [injections]），
 *   WebView 分别编译，互不牵连；报错会经 `onConsoleMessage` 进应用日志。
 * - 备份目前带走 `audit/` 与 prefs，**不带** `filesDir/userscripts/`（已记在案，未做）。
 */
internal object Userscripts {

    /** 脚本目录：`filesDir/userscripts/<id>.user.js`。与 `audit/` 同一个惯例。 */
    private const val DIR = "userscripts"

    /** 一段元数据；认不出来的一律留空，由调用方兜底（绝不因为元数据缺失而拒绝安装）。 */
    data class Meta(
        val title: String = "",
        val version: String = "",
        val description: String = "",
        /** start / end / idle（油猴的 document-start / -end / -idle）。 */
        val runAt: String = "end",
        val matches: List<String> = emptyList(),
        /** `@exclude`：命中就不跑（空 = 不排除）。 */
        val excludes: List<String> = emptyList(),
    )

    /** 管理页要显示的一条（文件即"已安装"，[enabled] 来自 prefs）。 */
    data class Script(
        val id: String,
        val title: String,
        val version: String,
        val runAt: String,
        val matches: List<String>,
        val description: String,
        val enabled: Boolean,
        val bytes: Long,
        /**
         * 装它时记下来的来源 URL（市场条目取正文那个地址）；null = 没记（从文件导入、或由
         * 没有来源功能的旧版本装的）。有了它，[update] 才能真的去拉一份新的。
         */
        val source: String? = null,
    )

    fun dir(ctx: Context): File = File(ctx.filesDir, DIR)

    /**
     * 来源 URL 存在 prefs 里的键前缀：`userscript_source_<id>` → url。
     *
     * 为什么不写进脚本正文（比如加一行 `// @source`）：正文是用户的东西，往里塞字段等于改
     * 用户文件；而 id 本身含正文哈希（见 [idOf]），正文一改 id 就变，所以来源必须挂在 prefs
     * 上，并且**更新时跟着 id 一起搬**（见 [update]）。
     */
    private const val KEY_SOURCE_PREFIX = "userscript_source_"

    fun sourceOf(ctx: Context, id: String): String? =
        prefs(ctx).getString(KEY_SOURCE_PREFIX + id, null)?.takeIf { it.isNotBlank() }

    private fun setSource(ctx: Context, id: String, url: String?) {
        val e = prefs(ctx).edit()
        if (url.isNullOrBlank()) e.remove(KEY_SOURCE_PREFIX + id) else e.putString(KEY_SOURCE_PREFIX + id, url)
        e.apply()
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)

    /** 总开关；页面被脚本搞坏时，管理页（原生）把它关掉即可恢复。 */
    fun masterEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(DshEnv.KEY_USERSCRIPTS_ON, true)

    fun setMasterEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(DshEnv.KEY_USERSCRIPTS_ON, on).apply()
    }

    private fun enabledIds(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(DshEnv.KEY_USERSCRIPTS_ENABLED, emptySet()) ?: emptySet()

    fun setEnabled(ctx: Context, id: String, on: Boolean) {
        // 内置条目的 id 带保留前缀：用户脚本不可能把它关掉（那是「界面补丁别跟着消失」的前提，
        // 见 WebScripts 的类 KDoc）。管理页也不会给出这种 id，这里是兜底。
        if (id.startsWith(WebScripts.BUILTIN_PREFIX)) return
        val next = HashSet(enabledIds(ctx))
        if (on) next.add(id) else next.remove(id)
        prefs(ctx).edit().putStringSet(DshEnv.KEY_USERSCRIPTS_ENABLED, next).apply()
    }

    /** 装了哪些脚本。顺序**稳定**（按 id 排序）：注入顺序因此可复现，排查时不必猜。 */
    fun list(ctx: Context): List<Script> {
        val on = enabledIds(ctx)
        return (dir(ctx).listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".user.js") }
            .sortedBy { it.name }
            .map { f ->
                val text = runCatching { f.readText() }.getOrDefault("")
                val meta = parse(text)
                val id = f.name.removeSuffix(".user.js")
                Script(
                    id = id,
                    title = meta.title.ifBlank { id },
                    version = meta.version,
                    runAt = meta.runAt,
                    matches = meta.matches,
                    description = meta.description,
                    enabled = id in on,
                    bytes = f.length(),
                    source = sourceOf(ctx, id),
                )
            }
    }

    /**
     * 装一个脚本；返回 id（失败返回 null）。
     *
     * 幂等：同一个标题 + 同一份正文 = 同一个 id = 同一个文件（重装即覆盖）。标题故意进
     * 文件名（还有一小段正文哈希），这样在文件管理器里也认得出是谁。
     *
     * [source] 只在「从市场装」时给：它是 [update] 唯一的依据，也让管理页知道这条能更新。
     */
    fun install(ctx: Context, text: String, fallbackTitle: String? = null, source: String? = null): String? {
        if (text.isBlank()) return null
        val meta = parse(text)
        val title = meta.title.ifBlank { fallbackTitle?.trim().orEmpty().ifBlank { "userscript" } }
        val id = idOf(title, text)
        val wrote = runCatching {
            val d = dir(ctx)
            if (!d.isDirectory && !d.mkdirs()) return null
            File(d, "$id.user.js").writeText(text)
            true
        }.getOrDefault(false)
        if (!wrote) return null
        setEnabled(ctx, id, true)
        // 只有"知道来源"时才记：从文件导入的没有来源，不能把上一次的来源留在同名 id 上。
        if (!source.isNullOrBlank()) setSource(ctx, id, source)
        return id
    }

    fun remove(ctx: Context, id: String) {
        runCatching { File(dir(ctx), "$id.user.js").delete() }
        setEnabled(ctx, id, false)
        setSource(ctx, id, null)
    }

    /**
     * 装完之后补记一条来源。
     *
     * 市场页是「先装、后记」：这样那条 install 调用保持原样（门禁按它认"走的是同一个安装
     * 入口"），而来源照样落在 prefs 上。正文是用户的东西，不往里面塞 `@source` 字段。
     */
    fun rememberSource(ctx: Context, id: String, url: String) = setSource(ctx, id, url)

    /** 某个脚本的正文（详情页要预览它）；读不到返回 null。 */
    fun code(ctx: Context, id: String): String? =
        runCatching { File(dir(ctx), "$id.user.js").readText() }.getOrNull()

    /** 一次「更新」的结果：界面照这个翻文案，不猜。 */
    enum class UpdateOutcome { NO_SOURCE, FETCH_FAILED, UP_TO_DATE, UPDATED }

    /**
     * 用记下来的来源重新拉一次正文，替换本机上那一份。
     *
     * 为什么要把旧的那份删掉：文件名是「标题 + **正文哈希**」（见 [idOf]），正文一改 id 就变
     * —— 只把新的装进去会留下两份、页面里注入两遍。所以这里把旧的删掉，并把开关状态搬过去。
     * 正文一字未改时直接回 [UpdateOutcome.UP_TO_DATE]，不动任何文件。
     *
     * 阻塞（HTTP + 落盘）：调用方必须在 IO 线程上调，与市场页同一条规矩。
     */
    fun update(ctx: Context, id: String): UpdateOutcome {
        val url = sourceOf(ctx, id) ?: return UpdateOutcome.NO_SOURCE
        val old = code(ctx, id) ?: return UpdateOutcome.FETCH_FAILED
        val text = runCatching { ScriptMarket.fetch(ctx, url) }.getOrNull()
            ?: return UpdateOutcome.FETCH_FAILED
        if (text == old) return UpdateOutcome.UP_TO_DATE
        val wasOn = id in enabledIds(ctx)
        val newId = install(ctx, text, source = url) ?: return UpdateOutcome.FETCH_FAILED
        // 新 id 已经记好了来源；删旧的只会清掉旧 id 那个键，搬不走新的。
        if (newId != id) remove(ctx, id)
        setEnabled(ctx, newId, wasOn)
        return UpdateOutcome.UPDATED
    }

    /** 从 `content://`（文件选择器）读一段正文；读不到返回 null。 */
    fun read(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
    }.getOrNull()

    /**
     * 这次页面加载要注入的**每一段** JS（已按 [Meta.runAt] 自行安排时机）。
     *
     * 一段一个脚本，而不是拼成一大段：WebView 分别编译，于是**一个脚本语法错不会拖垮别的**。
     * 空列表 = 没什么要注入的（总开关关着、没装、或都被 `@match` 挡掉）。
     */
    fun injections(ctx: Context, url: String): List<String> {
        if (!masterEnabled(ctx)) return emptyList()
        // list() 已经把"在启用集合里"算成了每条的 enabled
        return list(ctx)
            .filter { it.enabled }
            .mapNotNull { s ->
                val text = runCatching { File(dir(ctx), "${s.id}.user.js").readText() }.getOrNull()
                    ?: return@mapNotNull null
                val meta = parse(text)
                if (!applies(url, meta)) return@mapNotNull null
                blob(s.id, text, meta)
            }
    }

    /** 标题 + 正文哈希 → 文件名安全、可读、稳定。 */
    internal fun idOf(title: String, text: String): String {
        val slug = title.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .split('-').filter { it.isNotEmpty() }.joinToString("-")
            .take(40)
        val hash = Integer.toHexString(text.hashCode())
        return if (slug.isEmpty()) "userscript-$hash" else "$slug-$hash"
    }

    /**
     * 解析 `==UserScript==` 元数据块。
     *
     * 只认 `// @key value` 这一种写法（油猴也认块注释，但那种写法罕见，而且要正确处理
     * "注释里套注释"的边界；**这一批先不做**，已写在文件头的"边界"里）。
     */
    internal fun parse(text: String): Meta {
        val out = LinkedHashMap<String, MutableList<String>>()
        var inside = false
        for (raw in text.replace("\r\n", "\n").split('\n')) {
            val line = raw.trim()
            if (line.contains("==UserScript==")) {
                inside = true
                continue
            }
            if (!inside) continue
            if (line.contains("==/UserScript==")) break
            if (!line.startsWith("//")) continue
            val body = line.removePrefix("//").trim()
            if (!body.startsWith("@")) continue
            val rest = body.substring(1)
            val key = rest.substringBefore(' ').trim().lowercase()
            if (key.isEmpty()) continue
            val value = if (rest.contains(' ')) rest.substringAfter(' ').trim() else ""
            out.getOrPut(key) { mutableListOf() }.add(value)
        }
        val first = { k: String -> out[k]?.firstOrNull().orEmpty() }
        // @match 与 @include 语义不同（前者按 scheme://host/path，后者是子串/通配），但都用
        // * 通配；这里合并处理，并去掉 @exclude 命中的。
        val include = (out["match"].orEmpty() + out["include"].orEmpty())
            .filter { it.isNotBlank() }
        val exclude = out["exclude"].orEmpty().filter { it.isNotBlank() }
        val runAt = when (first("run-at").lowercase()) {
            "document-start", "start" -> "start"
            "document-idle", "idle" -> "idle"
            else -> "end"
        }
        return Meta(
            title = first("name").ifBlank { first("title") },
            version = first("version"),
            description = first("description"),
            runAt = runAt,
            matches = include,
            excludes = exclude,
        )
    }

    /** 空 = 全放行（没写 @match/@include 的脚本按油猴的惯例到处跑；我们这儿"到处"就一个站）。 */
    internal fun matchesAny(url: String, patterns: List<String>): Boolean {
        if (patterns.isEmpty()) return false
        // 查询串与 fragment 不参与匹配（@match 的语义）：**两侧**都去掉 ——
        // 只去 URL 一侧的话，模式里写了 `?x=1` 就永远命中不了。
        val clean = url.substringBefore('#').substringBefore('?')
        return patterns.any {
            runCatching {
                Regex(globToRegex(it.substringBefore('#').substringBefore('?'))).matches(clean)
            }.getOrDefault(false)
        }
    }

    /** 跑不跑：没写 @match/@include 就当"到处"（我们这儿"到处"只有一个站 ✓）。 */
    internal fun applies(url: String, meta: Meta): Boolean =
        (meta.matches.isEmpty() || matchesAny(url, meta.matches)) &&
            !matchesAny(url, meta.excludes)

    /** 油猴的 `*` 通配 → 正则（并把其余元字符转义）。 */
    internal fun globToRegex(glob: String): String {
        val sb = StringBuilder("^")
        for (c in glob) {
            when {
                c == '*' -> sb.append(".*")
                c.isLetterOrDigit() || c == '/' || c == ':' || c == '.' || c == '-' || c == '_' -> {
                    if (c == '.') sb.append("\\.") else sb.append(c)
                }
                else -> sb.append('\\').append(c)
            }
        }
        return sb.append('$').toString()
    }

    /**
     * 一个脚本 = 一段自洽的注入体（GM_* 预置 + IIFE + 幂等哨兵 + 出错只影响自己）。
     *
     * internal 而不是 private：应用内置的那几段（见 [WebScripts]）走**同一个包装**，
     * 于是「一段一个脚本、互不牵连」这条性质对内置同样成立，也只有一处实现。
     */
    internal fun blob(id: String, code: String, meta: Meta): String = """
(function(){
  var NS = ${js(id)};
  var NAME = ${js(meta.title.ifBlank { id })};
  var RUN = window.__dshFolkRunner || (window.__dshFolkRunner = {});
  if (RUN[NS]) return; RUN[NS] = 1;
  function warn(e){ try { console.warn("[userscript] " + NAME, e); } catch (_) {} }
  var S = window.__dshFolkGM || (window.__dshFolkGM = {});
  function K(k){ return "dshFolk.gm." + NS + "." + k; }
  function GM_getValue(k, d){
    try { var v = window.localStorage.getItem(K(k)); return (v === null) ? d : JSON.parse(v); }
    catch (e) { return d; }
  }
  function GM_setValue(k, v){ try { window.localStorage.setItem(K(k), JSON.stringify(v)); } catch (e) {} }
  function GM_deleteValue(k){ try { window.localStorage.removeItem(K(k)); } catch (e) {} }
  function GM_addStyle(css){
    var s = document.createElement("style"); s.textContent = String(css);
    (document.head || document.documentElement).appendChild(s); return s;
  }
  function GM_log(){
    try { console.log.apply(console, ["[userscript] " + NAME].concat(Array.prototype.slice.call(arguments))); }
    catch (e) {}
  }
  function GM_notification(a, b){
    var text = (a && typeof a === "object") ? String(a.text || "") : String(a);
    var title = (a && typeof a === "object") ? String(a.title || "") : String(b || "");
    try {
      if (window.DshFolkNotify && window.DshFolkNotify.notify) window.DshFolkNotify.notify(title, text);
      else console.log("[userscript] " + (title ? title + ": " : "") + text);
    } catch (e) { warn(e); }
  }
  var GM_info = { script: { name: NAME, version: ${js(meta.version)} } };
  function boot(){
    (function(GM, GM_getValue, GM_setValue, GM_deleteValue, GM_addStyle, GM_log,
              GM_notification, GM_info, unsafeWindow){
${code}
    }).call(window, {
      GM_getValue: GM_getValue, GM_setValue: GM_setValue, GM_deleteValue: GM_deleteValue,
      GM_addStyle: GM_addStyle, GM_log: GM_log, GM_notification: GM_notification,
      GM_info: GM_info
    }, GM_getValue, GM_setValue, GM_deleteValue, GM_addStyle, GM_log, GM_notification,
       GM_info, window);
  }
  function safely(){ try { boot(); } catch (e) { warn(e); } }
  var AT = ${js(meta.runAt)};
  if (AT === "start") safely();
  else if (AT === "idle") (window.requestIdleCallback || function (f) { setTimeout(f, 1); })(safely);
  else if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", safely);
  else safely();
})();
""".trimIndent()

    /** Kotlin 值 → JS 字符串字面量（`JSONObject.quote` 与 JS 的字符串转义同源）。 */
    private fun js(s: String): String = JSONObject.quote(s)
}
