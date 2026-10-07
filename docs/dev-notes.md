# 构建与发布内幕（应用 / 运行时工作流）

[← 返回 README](../README.md)

## 更新说明

升级之后第一次打开会弹一次「本次更新」，列出这一版改了什么。它和首启引导**共用同一个对话框壳**
（`PagedInfoDialog`）：两者要的东西完全一样，两套壳会立刻开始各自漂移。

内容是**本地资源**（`R.array.changelog_items`）而不是 GitHub release 正文：release 正文说的是
「有一个新版本，它讲了这些」，而这里要说的是「你现在跑的这一版改了什么」—— 用户此刻可能在飞机上，
所以必须离线可用。

两个对话框互斥，且首启引导会顺手把当前版本记成「更新说明已弹过」：刚装上的人要的是「这是什么应用」，
不是「本次更新」，而两个对话框叠在一起会互相盖住按钮。

同一串互斥分支上还挂着两条一次性提示，顺序固定：**首启引导 → 本次更新 → 权限策略变更 → 10.8 的
生日彩蛋**（`ui/screen/Home.kt`）。彩蛋排最后，是因为它最不重要 —— 前面三个都该先弹；彩蛋自己
的判据在 `util/BirthdayEgg.kt`：10 月 8 日当天首次打开弹一次，记的是**年份**（所以明年还会弹，
同一天不会弹第二次）。它不进 `changelog_items`：提前写进更新说明就不叫彩蛋了。

版本号写在三处（`build.gradle.kts` 的基准、`util/Changelog.kt` 的 `VERSION`、那份条目文案），
`tools/check-changelog.js` 把它们钉在一起。运行时另有兜底：版本不符就不弹 —— 拿上一版的内容配上
新版本号是一句自信的假话，比什么都不显示糟得多。但那个兜底意味着**新版本的用户什么都看不到**，
而没人会发现，所以真正的防线是那个检查器。

同一批源码自检里还有 `tools/check-kotlin-comments.js`：Kotlin 的块注释**可以嵌套**，
所以在 KDoc 里写一个包含块注释起始标记的文本（例如作用域通配写法）会打开一层嵌套注解，
而那行 KDoc 自己的收尾标记只关掉内层 —— **外层一直开着，把它后面所有代码都吃掉**，
编译器报出来的却是一片「unresolved reference」，定位代价极高（本项目真的踩过一次，
靠一次完整 CI 构建才发现）。这个检查器按字符遍历全部 Kotlin 文件，确认字符串、
模板与注释都正确闭合。两者都接在 `build.yml` 与 `beta.yml` 的编译之前。

## WebUI 注入的脚本管道（内置 + 用户脚本）

往应用自己的 WebView 里塞 JS 只有**一条**路：`DshWebUiActivity.installScripts` 调
`WebScripts.injections`（内置在前、用户导入的在后），每段各一次
`WebViewCompat.addDocumentStartJavaScript`（分别编译，一段语法错只毁它自己），origin 规则是
`loopbackOriginRules`；内核不支持 document-start 时回落成 `onPageStarted` 里逐段
`evaluateJavascript`。这一版之前是六条互不相干的路（四段 Kotlin 常量各带一个安装函数与
`xxxShimInstalled` 布尔量、内边距是函数拼出来的、用户脚本又一套），结果「一共注入了什么」在
任何一页都看不全。

- **内置五条**写在 `app/src/main/assets/webui-scripts/`，元数据（标题/摘要/时机/开关/顺序）只在
  `WebScripts.BUILTINS` 里 —— 时机与开关本来就依赖 pref 与 i18n，文件里再写一份 `@run-at` 只会
  两处不一致。顺序即注入顺序：兼容垫片第一（它补的 API 别的脚本与页面都要用），内边距第二
  （第一帧就要就位）。
- **开关**：`compat` → `webui_compat_shim`（auto/on/off，auto 按内核）、`composer` →
  `web_enter_newline`；其余三条常开（内边距是布局前提，无障碍名字与 blob 下载是补页面缺陷）。
  这三档**只在用户脚本页**（2026-10 起功能设置里那两行收了进来：它们本来就是这两条内置的档位，
  和旁边几条摆在一起才看得出"页面被注入了什么"），auto 档旁边显示当前内核版本；功能设置页右上角
  只留一个入口图标。
- **总开关只管导入的脚本**。内置刻意不看 `dsh_userscripts_on`：一个坏脚本把页面弄白时，内边距、
  无障碍名字与兼容垫片还得在 —— 那正是「管理页能把界面救回来」的前提。
- **参数通道只有一条**：内边距需要四个 CSS 像素值且随转屏/键盘变化，正文里留了
  `WebScripts.PARAM_MARKER`，注入前整体替换；换不到就保持正文自带的全 0（合法 JS，绝不会把占位符
  注进去）。之后的尺寸变化仍走 `insetUpdateScript` → `window.__dshFolkInsets`。
- **包装共用** `Userscripts.blob`（GM_* + 幂等哨兵 + try/catch）；`@run-at start` 是同步执行，
  所以内置拿到的仍是 document-start 语义。内置 id 带 `builtin:` 前缀，`setEnabled` 直接拒收 ——
  用户脚本改不动它们的开关。

门禁：`tools/check-web-shim.js` 现在读 assets（原来从 Kotlin 常量抠），把每段正文在 Node 的假内核/
假 DOM 里**真跑**，并对着 API→版本表反向断言；它还反查「Kotlin 里不再有垫片 JS / 只剩一个注入器与
一个回落」，并检查注册表与 assets 一一对应。`tools/check-userscripts.js` 钉注册表的顺序、时机、
开关映射、总开关范围、参数通道与管理页遍历。每条断言都反向验证过（删文件、调顺序、把内置挂到总
开关下、写错替换目标……都能验红）。

## WebView 侧的麦克风授权（网页语音输入）

对话页的语音输入是**页面里**的 `getUserMedia`，与容器里的 `mic record` / `mic start` 是两条
独立的路：前者走 WebView 的 `WebChromeClient`，后者走 `/native/mic/*`。它们可以**同时**都
"没问题"却仍然用不了，这正是那次报错的样子 —— 系统设置里 RECORD_AUDIO 是开的、页面 JS 也对，
提示却是"麦克风权限未开启"：

- AOSP 的 `WebChromeClient.onPermissionRequest` 默认实现是 `request.deny()`。宿主不重写，
  Chromium 就永远收到"拒绝"，`getUserMedia` 恒抛 `NotAllowedError` —— 上游客户端正好在这个
  错误上显示那句提示。`DshWebUiActivity` 现在重写它：这次要的资源里**已经授予**的直接
  `grant`（快路径）；网页要麦克风而系统还没给，就弹一次系统授权框并把这次 `PermissionRequest`
  **挂住**（`pendingAudioRequest`），结果回来再答 —— Chromium 会一直等 grant/deny。上一次还没
  答就再来一次时，先把旧的那次 `deny` 掉；Activity 走掉时同理，不留 WebView 对象给下一次加载。
- Chromium M117+ 的 `cr_media` 还要求宿主声明 `MODIFY_AUDIO_SETTINGS`（normal 级、安装即
  授予）。少了它 logcat 里是 `Requires MODIFY_AUDIO_SETTINGS and RECORD_AUDIO. No audio
  device will be available for recording`，页面拿到的是**没有音轨**的 stream —— 一样不报错。

两条都是"静默失败"型（不崩、不抛到用户看得懂的地方），所以由 `tools/check-web-permissions.js`
钉住：清单里两条权限都在、重写落在 `WebChromeClient` 里、已授予走快路径、未授予挂住并弹框、
结果回来答 grant/deny 只答一次、`onDestroy` 兜底。`ActivityResultContracts.RequestPermission`
必须在 `onCreate` 里注册（STARTED 之前才能收到回调），门禁连"注册落在哪个函数里"一起查。

## 容器侧的会话式录音（`mic start` / `mic stop`）

`mic/record --ms N` 回答的是"录满 N 毫秒"；按键说话要的是"现在开始、说完了停"，时长由说话的
人定。`POST /native/mic/start` 立刻回 `{id, path, maxMs}`，`POST /native/mic/stop?id=` 收尾
并回 `{path, bytes, ms, id}`。三条形状上的约束（门禁逐条钉住）：

- **一条收尾路径**：`finishMic` 同时服务 `record` 与 `stop`（stop → 空文件删除 → 前台复查 →
  `trimStage`），两者只有"时长怎么来的"不同：前者按请求、后者按实测。
- **一个状态位**：还是那个 `recording` AtomicBoolean（`compareAndSet` 抢位、启动失败还位、停会话
  放位），没有第二把锁 —— 两把锁必然让"忙不忙"分叉。
- **看门狗 + 一次迟到**：`MAX_RECORD_MS` 到点由看门狗走**同一条**收尾把文件留下，结果存进
  `micLast`；客户端慢一拍才 `stop` 同一个 id，就把那份结果再给一遍（下一次 start 清掉）。id 只认
  当前会话，别人的 id 回 `409 bad_session`，早已结束又没结果回 `409 no_session`。

## 脚本市场（GreasyFork）

「用户脚本」页底部是原生市场：搜 greasyfork.org、点一下装进"我装的"那份列表（装完仍是同一套
开关 / 删除）。它**在原生侧发 HTTP、不经过 WebView** —— 一个坏脚本把页面弄白时，这一页照样能用，
这正是这一页存在的理由。

形状是照着实测的 API 抄的，别按"想当然"改：入口是
`https://api.greasyfork.org/<locale>/scripts.json`（`greasyfork.org/…/scripts.json` 每个都是
**308**，而 `HttpURLConnection` 对 308 的支持随版本而变）；响应有两种外壳（`{"query":[…]}` 与
翻过 2000 条窗口时的**裸 `[]`**）；字段是 `code_url` / `total_installs` /
`users[0].name`（没有 `author`、`installs`、`code_url_ssl` 这三个"想当然"）；locale 走 URL
**路径**，不认得的一律 `en`。安装地址只收 **https + greasyfork 的域**，正文必须含
`==UserScript==`（服务端出错时回的是 HTML/JSON）。`tools/check-market.js` 把这些连同超时、
2MB 上限、IO 线程、装完 reload 一起钉住。

## 无障碍别看本应用（默认只拦自己的 AI）

a11y 读屏默认**连我们自己的界面一起读**（`pickRoot` 只跳自己那个 `TYPE_SYSTEM` 悬浮窗；本应用
`TYPE_APPLICATION` 的窗是刻意保留的 —— 平时 agent 正是靠它驱动这里的输入框）。可目标在别的 App
上时，我们自己的窗会冒充活动窗，把 agent 引到错误的一棵树里。于是有了这个档位（设置页：无障碍卡片；
实现是 `A11yOwn`，prefs 键 `a11y_hide_own`，只在 `DshEnv` 里定义一次）：

| 档位 | 拦什么 | 谁受影响 |
|---|---|---|
| `off` | 什么都不拦 | —— |
| `agent`（默认） | `/native/a11y/…` 的读与写跳过本应用自己的窗口 | 只有我们自己的 agent |
| `all` | 再叠一层视图级 `importantForAccessibility = noHideDescendants` | **所有**无障碍服务（含 TalkBack） |

落点（缺一个就漏一条路）：`pickRoot` / `searchRoots` 把自家窗从候选与搜索表里去掉，且
`no_window` 时**不许退回自家树**（退回 = 开关白装）；`setText` 的
`findFocus(FOCUS_INPUT)` 是**全局**查、不走 `searchRoots`，所以那里单独再挡一次；三个自家的窗
（主界面 / WebUI 页在 `onResume`、悬浮小窗在 `addView` **之前**）都要落档位。

判因：`tree` 带 `hideOwn`（当前档位），`windows[]` 每项带 `own`，`no_window` 的 note 在过滤开着时
说清"这是策略"并给出路。

为什么只敢写"不保证完全拦截"：视图级那层只是给系统的**建议**（WebView 的虚拟子树、弹窗的独立窗、
`AccessibilityNodeProvider` 都可能照旧报到）；通道级只覆盖 `/native/a11y/…` —— `a11y screenshot`
读像素、`shell` 里的 `uiautomator`、`display` 把画面拖进容器，都不是它管的。

提示词也同步：档位写进 `host-facts.json`（`a11yHideOwn`），`dsh-folk-host` 在 agent/all 档渲染一段
"现在读不到本应用、会拿到 `no_window`/`not_found` + `hideOwn`，不要重试也不要绕开"。facts 按 mtime
失效，所以用户拨一下开关，**下一轮**组装就是新的，不必重启 dsh。`tools/check-a11y-own.js` 钉住上面
每一条；`tools/check-host-prompt.js` 真跑 `render()` 验那段话。

## 插件安装的 github 写法归一（`insteadOf`）

`github:owner/name` 规格到 pnpm 手里会变成 **`git+ssh://git@github.com/…`**，而容器里既没有 ssh
私钥、也没有 known_hosts —— 直连只会得到 `Host key verification failed`。更坏的是这个错会**跨依赖**
传染：pnpm 装一个 npm 规格（如更新 `dshmarket`）时会重新解析整个 profile 的依赖树，于是预装的
`dsh-folk-cloud`（`github:` 规格）能把"装 npm 包"整件事打死。

所以 `DshPluginRepo` 的 `insteadOf` 要收**每一种写法**（https / git+https / **git+ssh** / ssh /
`git@github.com:`），并且都重写到同一个目标 `<线路前缀>https://github.com/`（不能让前缀后面跟
`git+ssh://…`，gh-proxy 不认）。两条容易踩的细节：一个目标 URL 下挂多条 `insteadOf` 必须 `--add`
（不带它后一条会顶掉前一条，只剩一种写法生效）；`pnpm add` 那条 npm 路径**也要**围着这层重写
（不然它连带解析 git 依赖时同样撞 ssh）。启动时另留一份基线（`ensureGitCaAtStartup`），让 dsh 自身
reconcile、自愈、以及 agent 在会话里跑 `dsh plugin` 这些**不走安装路径**的 git 也拿得到。
`tools/check-race-channel.js` 钉住这五条。

## 测试版通道（应用 / 运行时）


应用测试版在 **设置 → 常规 → 接受测试版更新** 打开之后，检查更新会连预发布版一起看，界面上会给它打一个
「测试版」标记。默认关闭。

容器运行时的测试版是独立通道：在**版本菜单**里把「更新通道」滑块拨到测试版，运行时检查就会改用
`runtime-beta-latest` 滚动通道；默认是正式版，测试版可能不稳定。它与应用自己的测试版开关互不影响。

## 运行时卡片：开关卡片 + 版本菜单

运行时那张卡片现在就是**开关卡片**：整行点一下 = 立即检查更新（走 `confirmAfterCheck`：
查到确实装得上才弹确认框），长按 = 版本菜单，右边那个开关只管「自动检查更新」。原来那一排
「更新 / 重装 / 导入」按钮和三个小开关都收进了菜单 —— 更新回到卡片上（点一下），重装在
**当前已装那一行**，导入在菜单**左下角**（`AlertDialog` 的 dismissButton 本来就在左下角）。
手动检查不再有独立按钮：点卡片就是它，菜单打开时那次拉取也是一次检查。

菜单顶上是两个两档滑块（`steps = 1` 的 `Slider`，拖过去自动吸附到两端）：

- **版本类型**：完整版 ↔ 精简版；
- **更新通道**：正式 ↔ 测试。

两个滑块都是 `LaunchedEffect(reloadKey, slim, beta)` 的 key —— 换档就重拉列表（列表内容由
它们筛选，不重拉显示的仍是上一次选择的结果）；滑块只在 `onValueChangeFinished` 且**真的换了档**
时才落盘 + 重拉（拖动过程中每帧都写 prefs 再发一次请求，列表会被刷成幻灯片）。

筛选规则在 `RuntimeVersion.matchesFilter`：版本类型是硬条件；通道上四个滚动 tag 按名字分边，
**历史版本两边都留**（它们的 tag 里没有通道信息，只给一边就等于让另一半人找不到降级包）。
版本类型优先信 metadata 里的 `"flavor"`（构建脚本一直在写，从前不解析）—— 历史 release 的
tag 里没有它，光看 tag 只能当完整版。

列表里当前已装那一版**即使被筛掉也钉在最上面**：点它是重装（沿用「保留数据 / 全新重装」
二选一），其它版本才是切换。要求比当前 App 更新的版本不给装，点它只去更新应用。
`ToggleSettingCard` 为此加了个可选 `onClick`：整行被拿去做别的事时它不再是 Switch 角色
（否则 TalkBack 会把「检查更新」念成开关），开关由右边那个 `Switch` 自己负责。

## 运行时卡片与工作流内幕

预装插件时 pnpm 会刷一屏 `missing peer …` 警告，这是**预期的**：`@deepseek-ai/dsh-*`、`react`
这些 peer 由 dsh 自己解析，从不装进 profile 的 `node_modules`（装进去反而会与宿主版本打架）。
判断预装成没成看每个插件末尾的「预装完成 <包名>」与 `[DSH-Folk-exit] 0`，不是看这些警告。

日志里 `dsh web: http://127.0.0.1:3080/?token=…` 那行是给 App 打开 WebUI 用的令牌，等同于这个实例
的密码（局域网访问默认关闭，所以只在本机可达）—— 贴日志求助前记得把它删掉。

容器里的 pnpm 固定 10.x，运行时构建时就把 `update-notifier=false` 写进 npmrc：pnpm 自己那句
「Update available! 10.x → 12.x」会把用户引向 `pnpm add -g pnpm`，而 12.x 正是因为没有可执行的
启动器而被撤掉的那个版本。

应用测试版由 **Build DSH-Folk beta** 工作流发布（`workflow_dispatch`，填一个目标版本号如 `1.8.1`），
tag 形如 `v1.8.1-beta.7`，标了 GitHub 的 prerelease。几个刻意的选择：

- **测试版用 release 变体 + 正式版的签名**，不是 debug 包。debug 变体的包名是
  `top.funcun.folkpatch.debug`（一个能与正式版共存的独立应用），装上它不是「升级」而是多一个
  图标；debug 签名也压根覆盖不了正式版。测试版必须能原地替换正式版，否则这条通道毫无意义。
- **versionCode 用目标正式版的号**（`1.8.1` → `10801`），不加 beta 偏移。它必须大于当前正式版
  （否则 `compareVersions` 判成不更新，用户永远收不到提示），又不能大于将来那个正式版（否则正式版
  发出来时装不回去）。AOSP 的 `PackageManagerServiceUtils.checkDowngrade` 只在 `after < before`
  时拒绝安装，相等是允许的 —— 「与目标正式版同号」正好落在两个约束的交集里。区分先后靠版本**名**
  里的 `-beta.N`，`compareVersions` 认它，且正式版 > 预发布版。
- **不用 Actions 的 artifact**。artifact 的下载地址需要认证（匿名 `GET .../artifacts/<id>/zip`
  返回 401，而列表接口 200），产物还是 zip 包、30 天后过期。要让应用能匿名下载、断点续传、按
  sha256 校验，只有 release 资产这一条路。
- 关掉开关时按**两道**判断排除测试版：`prerelease` 标记，以及 tag 里的预发布后缀。漏一道的代价是
  所有人都被推上测试通道，而那正是这个开关要防的事。
- 开着开关时**先查列表再查 `releases/latest`**。后者定义上跳过 prerelease，先问它会拿到正式版、
  判定「已是最新」直接返回，列表根本没机会被看一眼 —— 开关看起来毫无作用。

APK 只由 GitHub Actions 构建，不提供本地打包的产物。想自己出包：在 Actions 里手动触发 **Build DSH-Folk**
（`workflow_dispatch`，可选 debug / release / both）。release 需要在仓库 secrets 里配置
`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PRIVATE_PASSWORD`；
缺任何一项会**直接构建失败**而不是退回调试签名 —— 一个用 debug key 签出来的「release」装得上、看着正常，
但和正式包签名不同、之后无法覆盖升级，比构建失败危险得多。构建末尾还有一道签名自检拦住这种情况。

容器运行时由另一个工作流 **Build DSH runtime rootfs** 生成（可选 `arch=both / arm64 / amd64`），
产物发布到滚动 tag `runtime-latest`：arm64 是 `rootfs.tar.gz` + `metadata.json`，
x86_64 是 `rootfs-x86_64.tar.gz` + `metadata-x86_64.json`（arm64 沿用无后缀的旧名以兼容存量版本）。
应用按本机架构读取对应的 `metadata*.json` 决定下载什么。

这个工作流还有一个 `release_tag` 输入（留空则按通道推导）：版本列表里那两个只在历史里存在的老 tag
（`runtime-beta`、`runtime-0.1.1-rc.2`）就是用它**原地重发**的 —— 同一个 tag 换内容对已装用户不可检测，
但「列表里点进去装出来的是当年那个坏掉的运行时」显然比什么都不做更糟。重发后版本串的 r 号会变，
装过旧内容的用户因此至少能看到一次更新提示。

运行时可以在 `metadata.json` 里声明 `minAppVersion`（构建时从 `build.gradle.kts` 的基准版本自动取，
`workflow_dispatch` 也可手动覆盖）：低于该版本的应用会先被要求更新软件，而不是下载一个装不上的运行时。
已装运行时的要求会持久化，App 升级后自动放行；空字段 = 无要求，兼容旧 metadata。
