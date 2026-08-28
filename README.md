<div align="center">

# DSH-Folk

**在 Android 上跑 DeepSeek Harness 的启动器**

[![License](https://img.shields.io/badge/License-GPL--3.0-blue.svg?logo=gnu)](./LICENSE)
[![Build](https://github.com/IPF-Sinon/DSH-Folk/actions/workflows/build.yml/badge.svg)](https://github.com/IPF-Sinon/DSH-Folk/actions/workflows/build.yml)

</div>

DSH-Folk 把 [DeepSeek Harness](https://www.npmjs.com/package/@deepseek-ai/dsh)（一个 Node.js 的编码 Agent CLI）装进手机：
应用下载一份 arm64 Linux 容器运行时，用 proot / proroot 在容器里启动 `dsh web`，然后你在手机上直接打开它的 Web UI。

不需要 root，也不需要 Termux。有 root / Shizuku / 无线 ADB 时会自动利用，用于放宽某些受限操作。

## 现在能做什么

| 页面 | 说明 |
| --- | --- |
| **主页** | 一键启动 / 停止 / 重启 DSH，显示运行阶段、Web UI 地址、当前运行方式与权限通道，带可复制的启动日志 |
| **终端** | 容器内的真 PTY 终端（基于 Termux 的 `terminal-view`），直接 `bash` 进容器 |
| **插件** | 管理容器里 DSH 的插件，展示 npm 周下载量、GitHub star 与 dsh-market 点赞，内置 dsh-market 插件商店，支持本地安装 |
| **设置** | 常规 / 外观 / 行为 / 功能 / 安全 / 备份 / 插件 / 多媒体，界面主题体系沿用 FolkPatch（`theme.json` 完全兼容） |

**配置备份**与 DSH 桌面端的 `dsh-config-manager` 插件使用**同一套导出格式**（走它的回环 HTTP API，不是另写一份 ZIP 打包器），
所以手机上导出的 zip 能直接在电脑上导入，反之亦然。默认导出 settings / ui / providers / plugins / mcp / prompts /
skills / agentPresets / agentInstructions / workspaces / pluginFiles / credentialsStatus / self，
不导 sessions（会话记录体积能到几百 MB）；凭据值不导出，可选整包 AES-256-GCM 加密。

## 环境要求

- Android 8.0 (API 26) 或更高
- **arm64-v8a** 设备（不支持 32 位）
- 首次启动需要联网下载运行时（约 130 MB 压缩包，解压后约 530 MB；可在设置里选镜像或自动测速）
- 存储空间建议预留 2 GB 以上

root / Shizuku / 无线 ADB 都是**可选**的。DSH-Folk 只探测并复用设备上已有的 su（Magisk / KernelSU / APatch）与已授权的 Shizuku / Sui，
自身不打任何内核补丁、不安装 su、不内置 Shizuku Server。

## 安装

从 [Releases](https://github.com/IPF-Sinon/DSH-Folk/releases) 下载 APK 安装。

APK 只由 GitHub Actions 构建，不提供本地打包的产物。想自己出包：在 Actions 里手动触发 **Build DSH-Folk**
（`workflow_dispatch`，可选 debug / release）。release 需要在仓库 secrets 里配置
`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`；没配置时会退回默认调试签名。

容器运行时由另一个工作流 **Build DSH runtime rootfs** 生成，产物（`rootfs.tar.gz` + `metadata.json`）
发布到滚动 tag `runtime-latest`，应用启动时读取其中的 `metadata.json` 决定下载什么。

## 它是怎么跑起来的

```
DSH-Folk (Android app)
  └─ proot / proroot                      ← 打包在 APK 里的可执行 .so
       └─ Ubuntu 24.04 arm64 rootfs       ← 首次启动时在线下载
            ├─ python3                     ← 无线 ADB 配对用，已预装
            └─ Node.js 24 + @deepseek-ai/dsh
                 └─ dsh web --port 3080    ← 只监听 127.0.0.1
                      └─ 手机浏览器 / 应用内打开
```

几个不得不这么做的地方：

- Android 的 `app_data_file` 带 **noexec**，只有 `nativeLibraryDir` 里的 `.so` 可执行，所以 proot / proroot 以 `.so` 形式打包进 APK。
- 部分设备的私有目录禁止 `link(2)`，应用会先探测硬链接是否可用，不可用时给 proot 加 `--link2symlink`。
- `dsh web` 只绑定回环地址；配置备份走的也是同一个回环 HTTP 接口，不对局域网开放。

## 项目结构

```
app/src/main/java/me/bmax/apatch/
  dsh/                  运行时层：下载安装、proot 启动、权限探测、无线 ADB、PTY、配置备份
  ui/screen/HomeDsh.kt  主页
  ui/screen/Dsh*.kt     终端 / 插件 / 插件商店
  ui/screen/settings/   设置各分页
runtime-builder/        容器 rootfs 构建脚本（在 CI 上跑）
.github/workflows/      build.yml（APK） + runtime.yml（rootfs）
```

内部包名保留 `me.bmax.apatch`（applicationId 是 `io.github.ipfsinon.dshfolk`）：
这样 FolkPatch 的整套主题子系统与用户已有的 `theme.json` 不需要改一行就能继续用。

## 致谢

DSH-Folk 的 UI 直接复用 FolkPatch，容器与运行时交付思路来自 DSHA / DSHM：

- [FolkPatch](https://github.com/LyraVoid/FolkPatch) —— 本项目的 UI 基础（GPL-3.0）
- [APatch](https://github.com/bmax121/APatch) —— FolkPatch 的上游
- [DSHA](https://github.com/IPF-Sinon) —— 无线 ADB 配对方案
- [DSHM](https://github.com/IPF-Sinon) —— 运行时在线交付与镜像测速方案
- [DeepSeek Harness](https://www.npmjs.com/package/@deepseek-ai/dsh) —— 被启动的本体
- [proot](https://github.com/proot-me/proot) / [Termux](https://github.com/termux/termux-app) —— 容器执行与 PTY 终端
- [Shizuku](https://github.com/RikkaApps/Shizuku) —— 免 root 特权通道
- [KernelSU](https://github.com/tiann/KernelSU) / [SukiSU-Ultra](https://github.com/SukiSU-Ultra/SukiSU-Ultra) —— 界面设计参考

## 许可证

[GNU General Public License v3.0](./LICENSE)。本项目派生自 GPL-3.0 的 FolkPatch，因此整体沿用 GPL-3.0：
分发（含二次修改）必须同样以 GPLv3 开源并提供完整源码。

## 交流

- QQ 群：[1109060326](https://qm.qq.com/q/t7HDoR5ACk)
- Issues：https://github.com/IPF-Sinon/DSH-Folk/issues
