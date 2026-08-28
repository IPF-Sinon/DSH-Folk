# DSH-Folk

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

**DSH-Folk** —— 基于 FolkPatch UI 代码构建的 DeepSeek Harness 移动端界面。

本项目是 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（`dsh`）的移动端客户端，其 **UI 层代码直接派生自 [FolkPatch](https://github.com/LyraVoid/FolkPatch)** —— 一款以卓越 UI/UX 著称的 Android Root 管理工具。我们将 FolkPatch 优雅的界面语言带到 AI 智能体框架中，让移动端的 AI 交互体验同样流畅而精致。

> *“把 FolkPatch 那种‘工具亦有温度’的精神，带到 Harness 的生态中。”*

## ⚠️ 重要声明

本项目是 **FolkPatch UI 代码的 GPL-3.0 衍生作品**。所有 UI 相关的源代码（布局、动画、主题引擎等）均基于 FolkPatch 的源码修改而来，因此本项目的**整体许可证必须为 GPL-3.0**。

## ✨ 特性

- **🎨 纯正 FolkPatch 界面**：直接继承其流畅动效与壁纸感知主题
- **📱 移动端原生体验**：专为 Android 设备优化的交互逻辑
- **🔌 完整 DSH 兼容**：完美支持 DeepSeek Harness “一切皆插件” 的生态
- **🎯 轻量高效**：UI 层独立，不侵入 DSH 核心逻辑

## 🚀 快速开始

### 环境要求
- Android 7.0 或更高版本
- 已安装 DeepSeek Harness（`dsh`）运行时环境

### 安装步骤

1. 从 [Releases](https://github.com/your_github_name/DSH-Folk/releases) 下载最新 APK
2. 在 Android 设备上安装 APK
3. 打开应用，自动检测并连接已安装的 `dsh` 服务
4. 开始体验 FolkPatch 风格的 AI 智能体交互

### 从源码构建

```bash
git clone https://github.com/your_github_name/DSH-Folk.git
cd DSH-Folk
# 具体的构建命令取决于你的技术栈