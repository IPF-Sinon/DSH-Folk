<div align="center">

# DSH-Folk

**在 Android 上跑 DeepSeek Harness 的启动器**

[![License](https://img.shields.io/badge/License-GPL--3.0-blue.svg?logo=gnu)](./LICENSE)
[![Build](https://github.com/IPF-Sinon/DSH-Folk/actions/workflows/build.yml/badge.svg)](https://github.com/IPF-Sinon/DSH-Folk/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/IPF-Sinon/DSH-Folk?logo=github)](https://github.com/IPF-Sinon/DSH-Folk/releases/latest)

</div>

DSH-Folk 把 [DeepSeek Harness](https://www.npmjs.com/package/@deepseek-ai/dsh)（一个 Node.js 的编码 Agent CLI）装进手机：
应用下载一份 Linux 容器运行时（arm64 或 x86_64，按设备架构），用 proot / proroot 在容器里启动 `dsh web`，然后你在手机上直接打开它的 Web UI。

不需要 root，也不需要 Termux。有 root / Shizuku / 无线 ADB 时会自动利用，用于放宽某些受限操作。

## 预览

<div align="center">

| 主页 | 终端 |
| :---: | :---: |
| <img src="docs/screenshots/home.jpg" width="260" alt="主页：启动 / 停止、运行方式、权限通道与启动日志"> | <img src="docs/screenshots/terminal.jpg" width="260" alt="终端：容器内的真 PTY，带 ESC / TAB / CTRL / 方向键扩展键"> |
| **插件** | **设置** |
| <img src="docs/screenshots/plugins.jpg" width="260" alt="插件：已安装列表，显示下载量、star 与可更新状态"> | <img src="docs/screenshots/settings.jpg" width="260" alt="设置：常规 / 外观 / 行为 / 功能 / 安全 / 备份 / 插件 / 多媒体"> |

</div>

## 现在能做什么

| 页面 | 说明 |
| --- | --- |
| **主页** | 一键启动 / 停止 / 重启 DSH，显示运行阶段、Web UI 地址、当前运行方式与权限通道，带可复制的启动日志 |
| **终端** | 容器内的真 PTY 终端（基于 Termux 的 `terminal-view`），直接 `bash` 进容器 |
| **插件** | 管理容器里 DSH 的插件，展示 npm 周下载量、GitHub star 与 dsh-market 点赞；内置插件商店（下载完整目录后本地搜索，2600+ 条），支持本地 .tgz 安装。装完会用临时端口验证一次插件树能否加载，不通过自动卸载 |
| **设置** | 常规 / 外观 / 行为 / 功能 / 安全 / 备份 / 插件 / 多媒体，界面主题体系沿用 FolkPatch（`theme.json` 完全兼容） |

主题商店的入口在 **设置 → 外观** 页右上角；主题存档（`.fpt`）的导出与导入在商店页顶栏。

**配置备份**与 DSH 桌面端的 `dsh-config-manager` 插件使用**同一套导出格式**（走它的回环 HTTP API，不是另写一份 ZIP 打包器），
所以手机上导出的 zip 能直接在电脑上导入，反之亦然。默认导出 settings / ui / providers / plugins / mcp / prompts /
skills / agentPresets / agentInstructions / workspaces / pluginFiles / credentialsStatus / self，
不导 sessions（会话记录体积能到几百 MB）；凭据值不导出，可选整包 AES-256-GCM 加密。

## 环境要求

- Android 8.0 (API 26) 或更高
- **arm64-v8a** 或 **x86_64** 设备（不支持 32 位）
- 首次启动需要联网下载运行时（约 150 MB 压缩包，解压后约 600 MB；可在设置里选镜像或自动测速）
- 存储空间建议预留 2 GB 以上

首次启动下载完运行时后会自动预装四个插件：`dsh-web-mobile`（移动端适配）、`dshmarket`（WebUI 内的插件市场）、
`dsh-config-manager`（**配置备份功能的依赖**，设置里的导出/导入走它的回环 API）、
`dsh-file-upload`（拖拽上传 / 文档转 Markdown / 图片 OCR / 语音输入）。
这一步会多花几分钟；失败不影响启动，之后可以在插件商店里手动装。
预装清单按包名逐个记账，所以从旧版本升级上来会自动补装新增的那几个。

应用自身的更新可以在 设置 → 常规 → 检查更新 里完成：它会对三条下载渠道（GitHub 直连 / 两个 gh-proxy）
测延迟与吞吐，下载支持断点续传，装之前必须通过 release 附带的 `.sha256` 校验 —— 校验不过一律不装。

root / Shizuku / 无线 ADB 都是**可选**的，并且**默认不启用**。DSH-Folk 只探测并复用设备上已有的 su（Magisk / KernelSU / APatch）与已授权的 Shizuku / Sui，
自身不打任何内核补丁、不安装 su、不内置 Shizuku Server。

「特权」默认是**未启用**：容器本身不需要 root（proot/proroot 从来不需要），只有硬件监控里几项 `/proc` 读取、
bugreport 里的 dmesg/tombstones 段、以及首页的重启菜单需要它。要用就去 **设置 → 功能 → 权限通道 → 首选通道**
选一条（或选「自动」按 root > Shizuku > 无线 ADB 挑）。从旧版本升级上来的用户如果此前授权过 root，会自动迁移到「自动」。

配对成功后容器里多出一个 `adb-shell` 命令（以 shell / uid 2000 身份在设备上执行）。默认只放行只读命令
（`getprop` / `dumpsys` / `ls` / `cat` 之类）；写操作和 `--su` 提权要在 **设置 → 功能** 里分别打开开关，
没打开时命令会被拒绝并提示开关位置。

## 安装

到 [Releases](https://github.com/IPF-Sinon/DSH-Folk/releases/latest) 下载**对应架构**的 APK，同目录的 `.sha256` 可用于校验：

- `DSH-Folk-<版本>-arm64-v8a.apk` —— 绝大多数手机、平板
- `DSH-Folk-<版本>-x86_64.apk` —— Android 模拟器、Android-x86、ChromeOS

两个包功能相同，区别只在打包的原生二进制与下载的容器 rootfs。装错架构会在启动时提示
「Unsupported architecture」并退出。不确定的话：手机选 arm64-v8a。

也可以到 [Actions](https://github.com/IPF-Sinon/DSH-Folk/actions/workflows/build.yml) 取开发构建：
选一次成功的运行，下载 `dsh-folk-debug-*` 或 `dsh-folk-release-*` 工件。

APK 只由 GitHub Actions 构建，不提供本地打包的产物。想自己出包：在 Actions 里手动触发 **Build DSH-Folk**
（`workflow_dispatch`，可选 debug / release / both）。release 需要在仓库 secrets 里配置
`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PRIVATE_PASSWORD`；
缺任何一项会**直接构建失败**而不是退回调试签名 —— 一个用 debug key 签出来的「release」装得上、看着正常，
但和正式包签名不同、之后无法覆盖升级，比构建失败危险得多。构建末尾还有一道签名自检拦住这种情况。

容器运行时由另一个工作流 **Build DSH runtime rootfs** 生成（可选 `arch=both / arm64 / amd64`），
产物发布到滚动 tag `runtime-latest`：arm64 是 `rootfs.tar.gz` + `metadata.json`，
x86_64 是 `rootfs-x86_64.tar.gz` + `metadata-x86_64.json`（arm64 沿用无后缀的旧名以兼容存量版本）。
应用按本机架构读取对应的 `metadata*.json` 决定下载什么。

## 容器里能调宿主的什么

容器内除了 dsh 本体，还有两个由 App 落盘的命令，都走同一个只绑 `127.0.0.1` 的回环桥（带随机 token，
其它 App 读不到本应用私有目录，也就拿不到 token）：

`dsh-fs` —— 受控访问共享存储（根目录固定 `/sdcard`，路径逐段校验 + canonical 二次确认，防符号链接逃逸）：

```
dsh-fs list [路径] [--recursive] [--maxDepth N] [--limit N]
dsh-fs stat <路径>
dsh-fs read <路径> [--offset N] [--length N]     # 二进制写到 stdout
dsh-fs write <本地文件> [远端路径] [--append]
dsh-fs rm <路径> [-r]
dsh-fs mv <源> <目标>
dsh-fs cp <源> <目标> [--overwrite]
dsh-fs mkdir <路径>
dsh-fs find <路径> --glob '*.log' [--maxDepth N] [--limit N]
dsh-fs space [路径]
dsh-fs health
```

Android 规定读写整个共享存储要「所有文件访问」，而这一项**只能**在系统设置页里授予（它的
protectionLevel 是 `signature|appop`，应用申请不到）。没授予时上面每条命令都回
`403 no_storage`，`dsh-fs health` 会如实报 `storageGranted: false`；去
**设置 → 功能 → 原生能力 → 共享存储** 点一下就能跳到那个系统页面。
顺带说明：`/storage/emulated/0` 本来就 bind mount 进了容器，普通 `read`/`write`/`glob` 常常够用，
这个桥的价值是**窄而可审计**的那条路径，不是访问本身。

`dsh-native` —— 借 App 之手调原生能力。**默认整体关闭**，要在 **设置 → 功能 → 原生能力** 里打开总开关，
再逐项勾选想给的能力（通知 / Toast / 振动 / 剪贴板 / 分享与打开链接 / 设备信息 / 媒体库 / 麦克风）：

```
dsh-native notify <标题> [正文] [--id N] [--ongoing]
dsh-native notify-cancel [--id N]
dsh-native toast <文本>
dsh-native vibrate [--ms N] [--amplitude 1..255]
dsh-native clip get | clip set <文本>
dsh-native share <文本> [--title T]
dsh-native open <https 链接>
dsh-native media list [--type image|video|audio] [--q 名字] [--limit N]
dsh-native media get <id> [--type image|video|audio]
dsh-native mic record [--ms N]
dsh-native device
dsh-native caps            # 查当前哪些能力开着、能不能用
```

勾上一项就会立刻弹它缺的系统权限（通知 / 媒体 / 麦克风）；被永久拒绝之后不再弹空窗，而是直接
跳到系统设置页 —— 那种情况下 `launch` 会立即回调、界面毫无反应，用户只会以为按钮坏了。

`media get` 与 `mic record` **不回二进制**：字节落进容器的 `/tmp`，回一个容器内路径，agent 用普通文件
工具读。容器 rootfs 是本应用私有目录，写它不需要任何存储权限，也少一次 base64 膨胀。
媒体权限在 Android 13 起按类型拆开，所以 `media list` 的响应里带 `granted` —— 只给了照片时
agent 该知道音频是**没授权**，而不是「设备上没有音频」。录音固定要求前台：Android 9 起后台录音
只会拿到**静音而不是报错**，与其交一段静音出去，不如直接 `409 not_foreground`。

分项而不是一个总开关，是因为容器里同时跑着用户自己装的第三方插件，它们共享同一个 token —— 「能调这个接口」
等价于「容器内任何代码都能调」。读剪贴板、拉起分享/链接、录音都受 Android 的后台限制约束，应用不在前台时
会返回 `409 not_foreground` 而不是假装成功。

两个桥的报错都是**双份**的：`error` 是跟随应用语言的人话（给用户看），`reason` 是稳定的机器码（给 agent 判断）。
用户把手机切成英文不会改变程序行为。

agent 默认**不知道**这些东西存在（dsh 上游没有 Android 宿主的概念）。App 会往容器里装一个单文件
cordis 插件，往 dsh 的系统提示词里加一段说明：宿主是什么机型/系统、`/sdcard` 已经挂进来了、有
`dsh-fs` / `dsh-native` 这两个命令、此刻**哪些**能力真的开着、缺哪些系统权限、设备语言是什么、以及提权是不是关的。
勾掉哪一项，下一轮对话里那一项就从提示词里消失，agent 不会再去调一个注定 403 的接口。
那段本身是英文的（与 dsh 自带的各段一致，避免给模型的输出语言添偏置），设备语言只作为一条**事实**告诉它。
不想让它知道就在 **设置 → 功能 → 让 agent 知道这些能力** 关掉（关掉不卸插件，只是那一段渲染成空）。

## 它是怎么跑起来的

```
DSH-Folk (Android app)                      ← 按 ABI 拆包：arm64-v8a / x86_64
  └─ proot / proroot                        ← 打包在 APK 里的可执行 .so
       └─ Ubuntu 24.04 rootfs               ← 首次启动时在线下载（arm64 或 x86_64）
            ├─ python3                       ← 无线 ADB 配对用，已预装
            ├─ git                            ← git 源插件用，已预装
            └─ Node.js 24 + @deepseek-ai/dsh
                 └─ dsh web --port 3080      ← 默认只监听 127.0.0.1
                      └─ 手机浏览器 / 应用内打开
```

几个不得不这么做的地方：

- Android 的 `app_data_file` 带 **noexec**，只有 `nativeLibraryDir` 里的 `.so` 可执行，所以 proot / proroot 以 `.so` 形式打包进 APK。
- proroot 只有 arm64 版本（[上游](https://github.com/coderredlab/proroot) 只发布 arm64-v8a），所以 x86_64 设备上运行方式固定为 proot，
  设置里那一项会禁选并说明原因。
- 部分设备的私有目录禁止 `link(2)`（真机实测报 `AccessDeniedException`），应用会先探测硬链接是否可用，不可用时给 proot 加 `--link2symlink`；
  proroot 则无条件启用它。而 pnpm 正是用 `link()` 从内容存储装包 —— 链接一旦被改写成符号链接，
  Node 的 `require.resolve` 做 realpath 就会解析进内容存储的扁平哈希目录，插件声明的 `./lib/client.cjs` 再也拼不出来
  （表现是装完插件 `dsh web` 报 `MissingClientBundleError`）。所以这种环境下会给 profile 的 `pnpm-workspace.yaml`
  写上 `packageImportMethod: copy`，让 pnpm 复制真实文件。代价是内容存储的去重失效，容器体积会大一些。
- 插件目录里超过一半的条目是 `github:owner/name` 安装规格，pnpm 解析它要 `git ls-remote`，所以 git 也预装进了 rootfs。
  注意 rootfs 是用 `dpkg-deb -x` 纯解包装出来的（不跑 maintainer script —— 它们要在目标架构上执行），
  **dpkg 的依赖关系没人替我们解** —— 包列表写漏一个传递依赖，构建期一切正常，到设备上 exec 那一刻才报
  `cannot find libxxx.so.N`。所以构建末尾有一步 `check-elf-closure.js`：从 git-core / perl 扩展 / python3
  出发递归解析 ELF 的 `DT_NEEDED`，任何 SONAME 找不到提供者就让构建失败（并断言入口是目标架构）。
- `dsh web` 默认只绑定回环地址；配置备份走的也是同一个回环 HTTP 接口。局域网访问是设置里一个默认关闭的开关。

## 项目结构

```
app/src/main/java/me/bmax/apatch/
  dsh/                  运行时层：下载安装、proot 启动、权限探测、无线 ADB、PTY、配置备份
  ui/screen/HomeDsh.kt  主页
  ui/screen/Dsh*.kt     终端 / 插件 / 插件商店
  ui/screen/settings/   设置各分页
runtime-builder/        容器 rootfs 构建脚本（在 CI 上跑）+ 动态库闭包检查
.github/workflows/      build.yml（APK） + runtime.yml（rootfs）
```

内部包名保留 `me.bmax.apatch`（applicationId 是 `top.funcun.dshfolk`）：
这样 FolkPatch 的整套主题子系统与用户已有的 `theme.json` 不需要改一行就能继续用。

## 致谢

DSH-Folk 的 UI 直接复用 FolkPatch，容器与运行时交付思路来自 DSHA / DSHM：

- [FolkPatch](https://github.com/LyraVoid/FolkPatch) —— 本项目的 UI 基础（GPL-3.0）
- [APatch](https://github.com/bmax121/APatch) —— FolkPatch 的上游
- [DSHA](https://github.com/IPF-Sinon) —— 无线 ADB 配对方案、容器运行逻辑参考
- [DSHM](https://github.com/IPF-Sinon) —— 运行时在线交付与镜像测速方案
- [DeepSeek Harness](https://www.npmjs.com/package/@deepseek-ai/dsh) —— 被启动的本体
- [proot](https://github.com/proot-me/proot) / [proroot](https://github.com/coderredlab/proroot) / [Termux](https://github.com/termux/termux-app) —— 容器执行与 PTY 终端
- [Shizuku](https://github.com/RikkaApps/Shizuku) —— 免 root 特权通道
- [KernelSU](https://github.com/tiann/KernelSU) / [SukiSU-Ultra](https://github.com/SukiSU-Ultra/SukiSU-Ultra) —— 界面设计参考

## 许可证

[GNU General Public License v3.0](./LICENSE)。本项目派生自 GPL-3.0 的 FolkPatch，因此整体沿用 GPL-3.0：
分发（含二次修改）必须同样以 GPLv3 开源并提供完整源码。

## 友情链接

LINUX DO 开源社区 | [linux.do](https://linux.do) 

## 交流

- QQ 群：[1109060326](https://qm.qq.com/q/t7HDoR5ACk)
- Issues：https://github.com/IPF-Sinon/DSH-Folk/issues
