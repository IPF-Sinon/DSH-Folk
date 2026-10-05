package me.bmax.apatch.dsh

import android.content.Context
import android.util.Log
import me.bmax.apatch.R
import me.bmax.apatch.util.DshWebCompat
import me.bmax.apatch.util.appString

/**
 * WebUI 注入脚本的**注册表**：应用内置的那几段（assets）与用户导入的那一份（filesDir）
 * 走**同一条管道** —— 同一个包装、同一个注入点、同一套 origin 规则（见 `DshWebUiActivity`）。
 *
 * ## 为什么要有这一层
 *
 * 这一版之前，往自己页面里塞 JS 有六条互不相干的路：四段 `private const val` 垫片各有一个
 * 安装函数、一个 `xxxShimInstalled` 布尔量、一个回落分支；内边距那段是函数拼出来的；用户脚本
 * 又一套。结果是「一共往页面里注入了什么」在任何一页都看不全，而它们的顺序/时机/开关语义
 * 还都不一样（无障碍的回落落在 onPageFinished，其余落在 onPageStarted）。
 *
 * 现在：**一段 JS = 一个脚本条目**。内置的正文在 `app/src/main/assets/webui-scripts/`，
 * 元数据（标题、摘要、时机、开关）只在这张表里 —— 时机与开关本来就依赖 pref 与 i18n，
 * 文件里再写一份 `@run-at` 只会两处不一致。
 *
 * ## 谁有开关
 *
 * - `compat` → [DshWebCompat.shouldInject]（`webui_compat_shim`，auto/on/off，auto 按内核判断）
 * - `composer` → [DshWebCompat.enterNewline]（`web_enter_newline`）
 * - 其余三条**常开**：内边距是页面布局的前提（不注入就会顶到屏幕边缘、底部输入框被手势条
 *   压住），无障碍名字与 blob 下载是补页面本身的缺陷 —— 给它们一个开关只会多出「用户关了
 *   之后来报 bug」这一种状态。
 *
 * 这三条常开的也都**不受用户脚本总开关约束**：总开关是「我装的脚本先别跑」的一个退路，
 * 而不是「把应用的界面补丁一起关掉」。用户脚本把页面弄白时，管理页要能救回来的前提正是
 * 内边距/无障碍/兼容垫片还在。
 *
 * ## 参数通道只有一条
 *
 * 内边距需要四个 CSS 像素值，而且随转屏/键盘变化。它是唯一一个**注入前需要原生参数**的
 * 条目：正文里写着占位符 [PARAM_MARKER]，注入前整体替换成真实值。替换不到时保持占位符
 * 自带的全 0 —— 那本身是合法 JS（只是内边距为 0），绝不会把一个语法错注进页面。
 * 之后的尺寸变化走 `DshWebUiActivity.insetUpdateScript`（推 `window.__dshFolkInsets`）。
 */
internal object WebScripts {

    /** 注入时机，与用户脚本的 `@run-at` 同义。`END` 由 runner 的包装在 DOMContentLoaded 上执行。 */
    enum class RunAt(val id: String) {
        START("start"),
        END("end"),
    }

    /**
     * 一条注入脚本。
     *
     * [asset] 是相对 `assets/` 的路径；[needsParams] 表示正文里有 [PARAM_MARKER]，
     * 注入前要用原生值替换（见 [payload]）。
     */
    data class Entry(
        val id: String,
        val titleRes: Int,
        val summaryRes: Int,
        val runAt: RunAt,
        val asset: String,
        val needsParams: Boolean = false,
    )

    /** 内边距的四个方向（CSS 像素 = dp）。 */
    data class Insets(val top: Int, val right: Int, val bottom: Int, val left: Int) {
        /** 占位符替换用的 JS 字面量。键名与正文里的 `state.t/r/b/l` 对应。 */
        internal fun json(): String = """{"t":$top,"r":$right,"b":$bottom,"l":$left}"""
    }

    /**
     * 内置脚本，**顺序即注入顺序**。
     *
     * `compat` 排第一：它补的是语言/平台 API，别的脚本（以及页面自己）都可能用到；内边距
     * 排第二：它决定第一帧就位，晚一点就是「先顶到屏幕边缘再缩回来」的一跳。
     */
    val BUILTINS: List<Entry> = listOf(
        /**
         * 旧内核 JS 兼容垫片（`assets/webui-scripts/compat.js`）。
         *
         * 现象是「打开工作区报 `AbortSignal.any is not a function`」，或者整页
         * **Failed to load plugins — … Iterator is not defined**（缺 `Iterator` 这个全局时，
         * 连设置都进不去）。补哪些 API、各需要哪个 Chrome 版本、出现在哪里，那张表在
         * [DshEnv.DSH_COMPAT_MIN_CHROMIUM] 的 KDoc 里；`tools/check-web-shim.js` 对着同一张表
         * **反向断言**（漏补 / 阈值过期都会被门禁拦下），并在 Node 的假旧内核里真跑一遍这段脚本。
         *
         * 必须是 document-start：`AbortSignal.any` 在模块顶层就会被引用路径碰到，等到
         * onPageFinished 再补已经晚了。脚本本身是「只补缺的」写法，新内核上什么都不动。
         */
        Entry(
            id = "compat",
            titleRes = R.string.dsh_userscripts_builtin_compat,
            summaryRes = R.string.dsh_userscripts_builtin_compat_summary,
            runAt = RunAt.START,
            asset = "webui-scripts/compat.js",
        ),
        /**
         * 系统栏内边距（`assets/webui-scripts/inset.js`）。
         *
         * 为什么必须由页面自己避让：Android 侧给 WebView 加内边距时，系统栏后面只能垫一层
         * 主题底色 —— 网页看着像被裁掉一截，手势条上下各一条色带。改成给上游外壳的 `#root`
         * 加 border-box 内边距：界面整体缩进安全区，而 `body` 的背景照旧铺满整窗，这才是
         * 「沉浸」要的效果。上游 `position:fixed` 的弹层类元素都不受影响，唯独「连接已断开」
         * 那条 `top:0` 的提示条会被状态栏压住，脚本里单独补了 `top`（用类名子串选，
         * 上游换哈希类名时这条规则自动失效，不会误伤别处）。
         *
         * 唯一需要原生参数的一段：文档开始那一刻 `document.documentElement` 可能还没有，
         * 首帧值必须**内插进脚本文本**（见 [PARAM_MARKER]），否则第一帧是「网页顶到屏幕边缘、
         * 然后突然缩回来」的一跳；之后的转屏/键盘变化走 `DshWebUiActivity.insetUpdateScript`。
         */
        Entry(
            id = "inset",
            titleRes = R.string.dsh_userscripts_builtin_inset,
            summaryRes = R.string.dsh_userscripts_builtin_inset_summary,
            runAt = RunAt.START,
            asset = "webui-scripts/inset.js",
            needsParams = true,
        ),
        /**
         * 手机回车换行（`assets/webui-scripts/composer.js`）。
         *
         * 上游把 Enter/Shift+Enter 注册成**只读**快捷键（发送 / 换行），而手机软键盘没有
         * Shift —— 不补这一下，手机上就写不出多行消息。脚本只对触屏生效
         * （`matchMedia('(pointer: coarse)')`），拦住裸回车后改发一个 Shift+Enter，走上游
         * 自己的换行路径；输入法合成中（`isComposing` / keyCode 229）与菜单打开时不介入。
         *
         * 必须是 document-start：监听要**排在宿主自己的 window 监听之前**，否则拦不到那次回车。
         */
        Entry(
            id = "composer",
            titleRes = R.string.dsh_userscripts_builtin_composer,
            summaryRes = R.string.dsh_userscripts_builtin_composer_summary,
            runAt = RunAt.START,
            asset = "webui-scripts/composer.js",
        ),
        /**
         * 网页输入框的无障碍名字（`assets/webui-scripts/a11y-labels.js`）。
         *
         * WebView 里的网页元素在无障碍树里通常既没有 text 也没有 view id
         * （`viewIdResourceName` 对网页元素是 null），agent 的 `a11y text --target` 因此
         * 无从下手。脚本把页面上**本来就显示给用户**的 placeholder 抄成 `aria-label`，
         * 三条自我约束：只在元素没有任何无障碍名字时才写、没有 placeholder 就不硬造、
         * 用 MutationObserver 盯着后挂上来的输入框（SPA 首屏之后才渲染搜索框）。
         */
        Entry(
            id = "a11y-labels",
            titleRes = R.string.dsh_userscripts_builtin_a11y,
            summaryRes = R.string.dsh_userscripts_builtin_a11y_summary,
            runAt = RunAt.START,
            asset = "webui-scripts/a11y-labels.js",
        ),
        /**
         * blob:/data: 下载落地（`assets/webui-scripts/blob-download.js`）。
         *
         * WebView 对这两种 scheme 不会触发 DownloadListener（没有网络请求可拦），所以脚本在
         * 页面里挂一个捕获 click 监听：看到带 download、href 是 blob:/data: 的锚点，就读成
         * base64 交给原生桥（`DshFolkDownload`，见 `DshWebUiActivity`）。`URL.createObjectURL`
         * + 程序化 click 的写法也因此被兜住（那也是一个真锚点）。
         *
         * 时机是 [RunAt.END]：它不参与首屏渲染，等 DOM 就绪再挂监听即可。
         */
        Entry(
            id = "blob-download",
            titleRes = R.string.dsh_userscripts_builtin_blob,
            summaryRes = R.string.dsh_userscripts_builtin_blob_summary,
            runAt = RunAt.END,
            asset = "webui-scripts/blob-download.js",
        ),
    )

    /**
     * 内置条目的 id 命名空间。
     *
     * 用户脚本按「标题 + 正文哈希」生成 id，理论上可能与内置重名；带上这个前缀就永远不会
     * 撞上，而且 [Userscripts.setEnabled] 会拒收这个前缀 —— 用户脚本不可能关掉内置那几段。
     */
    const val BUILTIN_PREFIX = "builtin:"

    /**
     * 内边距的参数占位符，**整段表达式**：替换失败时它自己就是合法的全 0 兜底。
     * 只出现一次，由 tools/check-userscripts.js 对着 assets 反向断言。
     */
    internal const val PARAM_MARKER = "/*__DSH_PARAMS__*/{ t: 0, r: 0, b: 0, l: 0 }"

    /** 这条内置这次要不要注入。三条常开的理由见类 KDoc。 */
    fun builtinEnabled(ctx: Context, id: String): Boolean = when (id) {
        "compat" -> DshWebCompat.shouldInject(ctx)
        "composer" -> DshWebCompat.enterNewline(ctx)
        else -> true
    }

    /**
     * 读出正文并按需替换参数；读不到（asset 缺失）返回 null 并记一笔。
     *
     * 门禁保证 CI 里 5 个文件都在，这里的 runCatching 只是不让一个打包事故变成崩溃。
     */
    fun payload(ctx: Context, entry: Entry, insets: Insets?): String? {
        val text = runCatching {
            ctx.assets.open(entry.asset).bufferedReader().use { it.readText() }
        }.getOrElse {
            Log.w(TAG, "内置脚本读取失败: ${entry.asset}", it)
            return null
        }
        if (!entry.needsParams) return text
        val value = insets?.json() ?: return text
        if (!text.contains(PARAM_MARKER)) {
            // 占位符被改掉了：保持正文原样（全 0）比注进一个语法错好，但必须留痕。
            Log.w(TAG, "内置脚本 ${entry.id} 里找不到参数占位符，内边距按 0 处理")
            return text
        }
        return text.replace(PARAM_MARKER, value)
    }

    /**
     * 这次页面加载要注入的**每一段** JS：内置（按 [BUILTINS] 顺序、过各自开关）+ 用户导入的
     * （`master + enabled + @match`，见 [Userscripts.injections]）。
     *
     * 顺序是有意的：内置在前。用户脚本是「改我们自己的界面」的那一档，让它在外层，
     * 于是它观察到的页面已经带着应用的补丁 —— 与它今天看到的一致。
     */
    fun injections(ctx: Context, url: String, insets: Insets?): List<String> {
        val out = ArrayList<String>()
        for (entry in BUILTINS) {
            if (!builtinEnabled(ctx, entry.id)) continue
            val js = payload(ctx, entry, insets) ?: continue
            out.add(
                Userscripts.blob(
                    id = BUILTIN_PREFIX + entry.id,
                    code = js,
                    meta = Userscripts.Meta(
                        // 标题进控制台告警（`[userscript] <title>`）：给本地化文案，因为读日志的
                        // 是用户。用 appString 而不是 getString —— 这里拿到的是 Activity，
                        // 但静态门禁（tools/check-i18n.js）分不清这个区别，而 appString 在任何
                        // Context 上都对（API 33 以下 getString 会退到系统语言）。
                        title = runCatching { ctx.appString(entry.titleRes) }
                            .getOrDefault(entry.id),
                        runAt = entry.runAt.id,
                    ),
                ),
            )
        }
        out.addAll(Userscripts.injections(ctx, url))
        return out
    }

    private const val TAG = "WebScripts"
}
