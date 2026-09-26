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

## 测试版通道（应用 / 运行时）


应用测试版在 **设置 → 常规 → 接受测试版更新** 打开之后，检查更新会连预发布版一起看，界面上会给它打一个
「测试版」标记。默认关闭。

容器运行时的测试版是独立通道：在 **设置 → 功能 → 运行时 → 接受测试版运行时更新** 打开后，运行时检查会改用
`runtime-beta-latest` 滚动通道；默认关闭，测试版可能不稳定。它与上面的应用测试版开关互不影响。
更省事的办法是长按运行时卡上的 **更新** 直接列出所有已发布的运行时版本（正式通道、测试通道、历史版本），
点一行就切过去 —— 升到测试版、退回某个具体版本都走同一个入口，不必先改通道开关再等检查。

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
