# DSH-Folk 扩展开发指南

> 面向第三方开发者。本文所有 API 名与行为都以本仓库源码为准，不是示意。

## 目录

1. [什么是扩展](#1-什么是扩展)
2. [最小可运行扩展](#2-最小可运行扩展)
3. [manifest.json 字段](#3-manifestjson-字段)
4. [API 能力清单](#4-api-能力清单)
5. [生命周期与异常隔离](#5-生命周期与异常隔离)
6. [编译与发布](#6-编译与发布)
7. [混淆与 R8（最容易踩死的一节）](#7-混淆与-r8最容易踩死的一节)
8. [安全模型](#8-安全模型)
9. [调试与常见失败对照](#9-调试与常见失败对照)
10. [已知边界](#10-已知边界)

---

## 1. 什么是扩展

DSH-Folk 里有三种「扩展」，跑在完全不同的地方：

| 类型 | 跑在哪 | 约束 | 能做什么 |
|---|---|---|---|
| DSH 插件 | 容器里的 Node.js | 受容器约束 | 挂 dsh 的钩子、加工具 |
| 用户脚本 | 内置 WebView | 受浏览器沙箱约束 | 改网页 DOM |
| **应用扩展** | **App 自己的进程** | **与宿主同权** | 改界面、拦宿主调用、替换子系统、读写应用目录 |

本文讲第三种。关键事实：**扩展是加载进 App 进程的 DEX**，`DexClassLoader` 的父加载器就是宿主的
classLoader —— 所以扩展能直接 `import` 宿主的所有类。这是它的力量来源，也是它的风险来源。

---

## 2. 最小可运行扩展

### 目录结构

```
<filesDir>/extensions/<id>/
    manifest.json      # 必填
    classes.dex        # 可选（纯声明式扩展不需要）
    assets/            # 可选，扩展自己的资源
```

`<filesDir>` 是应用私有目录（`/data/user/0/top.funcun.dshfolk/files`）。
`<id>` 必须匹配 `^[A-Za-z0-9._-]{1,64}$` 且不能是 `.` / `..` —— 它会被直接拼进路径。

### Hello World

`manifest.json`：

```json
{
  "id": "hello-world",
  "name": "Hello World",
  "version": "1.0.0",
  "entryClass": "com.example.hello.HelloExtension",
  "description": "在扩展页顶部加一行文字",
  "declaredCaps": ["ui.slot"],
  "minAppVersion": "2.2.1"
}
```

`HelloExtension.kt`：

```kotlin
package com.example.hello

import androidx.compose.material3.Text
import me.bmax.apatch.dsh.ext.DshExtension
import me.bmax.apatch.dsh.ext.DshExtensionHost
import me.bmax.apatch.dsh.ext.ExtensionUiRegistry

class HelloExtension : DshExtension {

    override fun onLoad(host: DshExtensionHost) {
        host.log("hello 扩展已加载")

        // 往「扩展页顶部」这个槽位插一行文字
        ExtensionUiRegistry.registerSlot(
            extensionId = host.id,
            slot = ExtensionUiRegistry.Slot.EXTENSIONS_PAGE_TOP,
            op = ExtensionUiRegistry.SlotOp.INSERT_AFTER,
        ) { modifier ->
            Text("来自 hello-world 扩展", modifier = modifier)
        }
    }

    override fun onUnload() {
        // 引擎会自动撤掉本扩展注册的全部槽位/Hook/SPI/外壳，这里一般不需要做什么。
        // 除非你自己持有线程、文件句柄之类的资源，在这里收尾。
    }
}
```

把 `classes.dex`（和 `manifest.json`）放进 `<filesDir>/extensions/hello-world/`，
到「插件 → 扩展」打开开关，**重启 App** 生效。

> 扩展没有「热加载」。启用/禁用/安装/卸载都需要重启才真正改变运行状态 —— 这是刻意的：
> 运行期替换一个已经初始化到一半的宿主，会引入难以解释的状态不一致。

---

## 3. manifest.json 字段

| 字段 | 必填 | 说明 | 校验 |
|---|---|---|---|
| `id` | 是 | 扩展标识，同时是目录名 | `^[A-Za-z0-9._-]{1,64}$`，不能是 `.`/`..` |
| `name` | 是 | 显示名 | 非空 |
| `version` | 是 | 版本号 | 非空（用于市场「可更新」比对，字符串相等比较） |
| `entryClass` | 否 | 实现 `DshExtension` 的全限定类名 | 为空 = 纯声明式扩展，不加载字节码 |
| `description` | 否 | 一句话说明 | — |
| `declaredCaps` | 否 | 声明的能力，字符串数组 | 会逐条显示在安装/启用的信任确认框里 |
| `minAppVersion` | 否 | 要求的最低宿主版本 | 低于它时**不会被加载**，市场也不会让你装 |

任何字段不合法 → `manifest.json` 解析失败 → 该扩展**不会加载**，且扩展页会显示「清单损坏」。

### 为什么 `minAppVersion` 很重要

扩展可能用到只有新版本宿主才有的 API。装在旧宿主上，调用时会在**运行期**抛
`NoSuchMethodError` —— 而这个异常出现在扩展自己的调用栈里，看起来像「宿主崩了」。
所以宿主在**两处**都会拦：加载时跳过、市场安装时直接失败。写 manifest 时请如实填。

---

## 4. API 能力清单

全部契约在 `ext-api` 模块（`me.bmax.apatch.dsh.ext`）。**这个模块只依赖 Compose 与协程**，
所以你编译扩展时只需要它，不需要整个宿主。

### 4.1 入口契约

```kotlin
interface DshExtension {
    fun onLoad(host: DshExtensionHost)
    fun onUnload() {}
}

interface DshExtensionHost {
    val id: String              // 扩展 id（来自 manifest）
    val appContext: Context     // application context，不是 Activity
    val fs: ExtensionFs         // 目录读写
    fun log(message: String)    // 写日志（logcat，tag DSH-Ext）
}
```

> 宿主实现里还有一个 `platform: DshPlatform`（36 项宿主能力门面），但**它不在 `ext-api` 里**，
> 第三方拿不到。原因见 [10. 已知边界](#10-已知边界)。

### 4.2 界面：槽位 / 整屏 / 外壳 / 色板

```kotlin
ExtensionUiRegistry.registerSlot(
    extensionId: String,
    slot: String,             // Slot 常量或自定义
    op: SlotOp,               // INSERT_BEFORE / INSERT_AFTER / REPLACE / REMOVE / WRAP
    content: SlotContent,     // @Composable (Modifier) -> Unit
)

ExtensionUiRegistry.overrideScreen(extensionId, screenId, content)   // 整屏接管
ExtensionUiRegistry.registerShell(extensionId, content)             // 接管导航外壳
ExtensionUiRegistry.interceptPalette(extensionId) { scheme -> ... }  // 改主题色板
```

**宿主内置槽位**（`ExtensionUiRegistry.Slot`）：

| 常量 | 值 | 位置 |
|---|---|---|
| `EXTENSIONS_PAGE_TOP` | `extensions.page.top` | 扩展页顶部 |
| `HOME_CARD` | `home.card` | 首页卡片区 |
| `SETTINGS_FUNCTION` | `settings.function` | 设置的功能页 |
| `WEBUI_OVERLAY` | `webui.overlay` | WebUI 之上 |

**外壳接管**（`registerShell`）的形状：

```kotlin
typealias ShellContent = @Composable (ShellHost, @Composable (Modifier) -> Unit) -> Unit

interface ShellHost {
    fun navigate(tabId: String)                    // 主页/终端/插件/设置
    fun currentTabId(): String
    fun currentTabStates(): StateFlow<String>      // 可观察的当前页（高亮用）
    fun navigateRoute(route: String)
    fun openWebUi()
    fun runtimeSummary(): RuntimeSummary
    fun runtimeStates(): StateFlow<RuntimeSummary>
    fun resetToDefaultShell()                      // 切回宿主默认外壳
}
```

**关键约定**：传给外壳的第二个参数是**宿主原有的页面树**。外壳决定「怎么切页」，
页面本身仍归宿主 —— 外壳哪怕只画了个半成品，插件页/设置页/终端页也都在。

多条外壳注册时**最后注册的那条生效**（后启用的扩展赢）。

### 4.3 拦截：19 个拦截点

```kotlin
ExtensionHookRegistry.before(extensionId, point, handler)
ExtensionHookRegistry.after(extensionId, point, handler)
ExtensionHookRegistry.around(extensionId, point, interceptor)  // 返回 false = 拦下
```

| 常量 | 时机 |
|---|---|
| `RUNTIME_BEFORE_START` | 启动运行时之前 |
| `RUNTIME_BUILD_ARGV` | 组装容器启动参数时 |
| `RUNTIME_APPLY_ENV` | 应用环境变量时 |
| `RUNTIME_PROBE_READY` | 探测服务就绪时 |
| `BRIDGE_BEFORE_DISPATCH` | 文件桥分发请求前 |
| `BRIDGE_AUDIT` | 文件桥审计 |
| `STORAGE_CHECK_PATH` | 路径访问检查 |
| `NATIVE_BEFORE_INVOKE` / `NATIVE_AFTER_INVOKE` | 原生命令调用前后 |
| `SHELL_BEFORE_EXEC` / `SHELL_AFTER_EXEC` | 命令执行前后 |
| `A11Y_DUMP_TREE` | 无障碍节点树导出时 |
| `DISPLAY_START_SESSION` | 虚拟显示会话启动时 |
| `WEBUI_CONFIGURE` | 配置 WebView 时 |
| `USERSCRIPTS_BUILD` | 构造注入脚本时 |
| `PLUGIN_UPDATE` | 插件更新时 |
| `WORKFLOW_EXECUTE_NODE` | 工作流执行节点时 |
| `PROMPT_RENDER` | 渲染系统提示词时 |
| `DIAGNOSTICS_REDACT` | 诊断文本脱敏时 |

### 4.4 替换：SPI

```kotlin
ExtensionServiceRegistry.register(extensionId, SomeInterface::class.java, impl)
ExtensionServiceRegistry.resolve(SomeInterface::class.java) { 默认实现 }
```

约定：宿主侧**一律走 `resolve`**（传默认实现），所以没扩展时行为零变化。
同类型后注册的覆盖先注册的。

### 4.5 目录读写

`ExtensionFs` 提供：`dataDir` / `filesDir` / `rootfsDir` / `cacheDir` / `ownDir` /
`externalFilesDir()` / `prefs(name)` / `read` / `readText` / `write` / `writeText` /
`mkdirs` / `delete` / `symlink` / `chmod` / `list` / `isProtected`。

**唯一硬边界**：不能碰熔断标志文件（`isProtected` 为真的路径）。
那是用户「禁用全部扩展」的逃生通道，扩展如果能删掉它，用户就失去了唯一的自救手段。

### 4.6 日志

```kotlin
ExtLog.info(msg)   // 只进 logcat（tag DSH-Ext）
ExtLog.log(msg)    // 同上，用于故障
```

**扩展日志不会写进 `dsh.log`**。那是「启动 dsh 运行时」的日志，用户会在
「设置 → 日志」里读它；扩展诊断混进去会干扰用户，也把真正的 dsh 故障淹没。
排查扩展问题请用 `logcat | grep DSH-Ext`。

---

## 5. 生命周期与异常隔离

- `onLoad(host)`：宿主加载完扩展后回调。**抛异常会被捕获并跳过该扩展**，不影响其它扩展与宿主启动。
  半途失败时引擎会自动撤掉它已注册的 SPI/Hook/UI，不留悬空回调。
- `onUnload()`：停用/移除/熔断时回调。同样不能抛出去。
- **异常隔离**：Hook handler 抛异常只记日志并跳过本次，绝不冒泡进宿主主流程；
  `around` 拦截器抛异常按「不拦」处理 —— 扩展不能靠崩溃来阻止宿主做正确的事。
- **熔断短路**：安全模式（见第 8 节）下，所有注册中心一律不生效、既有注册按「不存在」处理。

---

## 6. 编译与发布

扩展是**独立编译的 DEX**，由你自己的仓库构建。官方仓库 [dsh-folk-plugins](https://github.com/IPF-Sinon/dsh-folk-plugins)
用的是 Gradle composite build：

```kotlin
// settings.gradle.kts
includeBuild("../DSH-Folk") {
    dependencySubstitution {
        substitute(module("me.bmax.apatch:ext-api")).using(project(":ext-api"))
    }
}
include(":extensions:my-extension")
```

```kotlin
// extensions/my-extension/build.gradle.kts
dependencies {
    implementation("me.bmax.apatch:ext-api:1.0")   // 由上面的 substitute 映射到项目
}
```

构建产物：

```bash
# 1. 编出 classes.jar
./gradlew :extensions:my-extension:assembleRelease
# 2. 转成 dex
$ANDROID_HOME/build-tools/<ver>/d8 --min-api 26 --lib <android.jar> --output out/ classes.jar
# 3. 打包成 .dshx（zip：manifest.json + classes.dex）
zip my-extension-1.0.0.dshx manifest.json classes.dex
```

进市场：在 `index.json` 里加一条（`downloadUrl` / `sha256` / `sizeBytes` 必填，宿主会逐一校验）。

---

## 7. 混淆与 R8（最容易踩死的一节）

> 这一节是三**轮真机事故**换来的。宿主 release 构建开着 `isMinifyEnabled` + `-repackageclasses`，
> 而扩展是外部编译产物 —— 它的字节码里**写死了**编译时的名字。宿主一改名，扩展就在运行期链接失败。

宿主 `app/proguard-rules.pro` 必须保留以下内容（缺任何一条都会炸）：

```proguard
# 扩展 API 本身
-keep class me.bmax.apatch.dsh.ext.** { *; }
-keep interface me.bmax.apatch.dsh.ext.** { *; }

# Kotlin 标准库（扩展会调到任意 stdlib 函数）
-keep class kotlin.** { *; }

# Compose 必须整包、连成员一起保留
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.foundation.** { *; }
-keep class androidx.compose.material3.** { *; }
-keep class androidx.compose.material.** { *; }
-keep class androidx.compose.animation.** { *; }

# 协程与 lifecycle / activity
-keep class kotlinx.coroutines.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.activity.** { *; }
```

### 少一条会看到什么（真机原文）

| 报错 | 被混淆掉了什么 |
|---|---|
| `Didn't find class "<你的入口类>" on path: DexPathList[...]` | 扩展 API 本身被改名 |
| `Failed resolution of: Lkotlin/jvm/internal/Intrinsics;` | kotlin-stdlib 被改名挪包 |
| `No static method composableLambdaInstance(...) in class ...ComposableLambdaKt` | Compose 的 **internal 成员**被改名 |

### 为什么 `-keepnames` 不够

`-keepnames` 只保**类名**，不保**成员名**。Compose 编译器生成的代码调的正是 internal 成员
（`ComposableLambdaKt.composableLambdaInstance` 之类），Compose 自带的 consumer 规则不保护它们。
宿主自己的调用会被 R8 一致重写所以没事，**扩展 dex 里的调用是编译期写死的**，于是找不到。

### 为什么这些报错只在 release 出现

debug 构建不混淆，所以本地开发、静态门禁、CI 编译全都抓不到。这是扩展开发的固有难点：
**你必须用 release 包测扩展。**

---

## 8. 安全模型

扩展与宿主同权，意味着它能：

- 走宿主的**特权命令通道**（root / Shizuku）
- 读**无障碍屏幕内容**
- 访问 App 能访问的**全部文件**

### 三条铁律

1. **熔断逃生文件**。在以下任一位置新建名为 `disable_extensions` 的文件，冷启动即禁用全部扩展：
   - `<filesDir>/extensions/.disable_extensions`
   - `Android/data/top.funcun.dshfolk/files/disable_extensions`（插电脑用 MTP 就能建）
   - `/sdcard/Download/DSH-Folk/.disable_extensions`（手机文件管理器直接建，**不需要权限**）

   三种文件名写法都认：`.disable_extensions` / `disable_extensions` / `disable_extensions.txt`。

2. **连续崩溃 3 次自动熔断**：同一个扩展让进程崩 3 次，宿主自动写下上面那个标志文件。
   用户不需要知道这个机制也能自救。

3. **崩溃页有「禁用全部扩展并重启」**。该页**故意不接任何扩展 UI** —— 扩展正是可疑对象，
   不能让被救的人决定逃生门长什么样。

### 信任确认

打开扩展开关时会弹一次「这个扩展将获得完整权限」，并列出它 `declaredCaps` 里声明的能力。
所以 **`declaredCaps` 要如实写** —— 这是用户唯一能看到的告知。

---

## 9. 调试与常见失败对照

扩展日志：`adb logcat -s DSH-Ext`（或 bugreport 的 `logcat.txt` 里 grep `DSH-Ext`）。

| 现象 | 原因 | 修法 |
|---|---|---|
| 扩展页显示「清单损坏」 | `manifest.json` 缺失或字段不合法 | 检查 id 字符集、name/version 非空 |
| 列表里有、但启用后没反应 | 见下面几行 | — |
| 日志 `缺 classes.dex` | 目录里没有 `classes.dex`，但 manifest 有 `entryClass` | 补 dex，或去掉 `entryClass` 做纯声明式扩展 |
| 日志 `入口类没有实现 DshExtension` | 类型对不上（典型：你的 dex 自带了一份 `ext-api`） | 用 `compileOnly`/`implementation` 到宿主提供的 ext-api，不要打包进去 |
| 日志 `两条加载路径都失败` + `自检 xxx=缺失` | R8 改名（见第 7 节） | 补 keep 规则 |
| 日志 `要求宿主 >= X，已跳过加载` | `minAppVersion` 高于当前宿主 | 降低 `minAppVersion` 或让用户升级 |
| 日志 `安全模式，跳过全部第三方扩展` | 有熔断标志文件 | 删掉它，或在扩展页点「重新启用扩展」 |
| 外壳注册了但界面没变 | 外壳被别的扩展覆盖 / 熔断 | 看 `[外壳决策=...]` 那行日志 |

---

## 10. 已知边界

- **没有签名校验**。宿主不验证扩展来源，所以只装你信任的扩展。
- **运行期不热加载**。启用/禁用/安装/卸载都要重启。
- **`DshPlatform`（36 项宿主能力门面）不对外**。它直接暴露宿主内部类型（`DshRuntime`、
  `DshEnv`、`DshState`），放进公共 API 等于把整个 app 钉成 API，以后改不动。
  第三方扩展可用的就是本文列出的这些：界面、拦截、SPI、目录、日志。
- **外壳接管时页面树仍归宿主**。扩展只决定「怎么切页」。
- **市场分发**目前只指向官方仓库；没有 App 内的开发者侧载以外的安装入口。