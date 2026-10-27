# 自动化工作流：开发文档与踩坑指南

这份文档写给**要在 DSH-Folk 上改工作流功能的人**（以及想让 agent 稳定产出的使用者）。
它不重复 DSL 语法 —— 那是 `dsh-workflow --help` 与 `samples` 的职责 —— 而是记录
**我们在这套东西上真实踩过的坑**，尤其是那些「代码看起来对、跑起来不对」的部分。

> 授权：本文档以 [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.zh)
> （知识共享 署名 4.0 国际）授权。转载、改编请保留出处与本说明。
> 代码本身仍是 GPL-3.0（见项目 LICENSE）。

---

## 目录

1. [它由哪些部分组成](#1-它由哪些部分组成)
2. [跨进程契约：DSL 一旦分叉就会静默出错](#2-跨进程契约dsl-一旦分叉就会静默出错)
3. [审批：阻塞语义与 60 秒 TTL](#3-审批阻塞语义与-60-秒-ttl)
4. [执行引擎：分支、跳过与超时](#4-执行引擎分支跳过与超时)
5. [DSH 未启动时的阻塞与分支](#5-dsh-未启动时的阻塞与分支)
6. [Compose 画布：手势与坐标的七次返工](#6-compose-画布手势与坐标的七次返工)
7. [Kotlin 语言细节：那些编译期不报、运行期才炸的坑](#7-kotlin-语言细节那些编译期不报运行期才炸的坑)
8. [工具注册与降级](#8-工具注册与降级)
9. [无本地编译器时如何验证](#9-无本地编译器时如何验证)
10. [给 agent 的提示词与文档怎么放](#10-给-agent-的提示词与文档怎么放)

---

## 1. 它由哪些部分组成

一条工作流从 AI 写下定义到真正跑起来，要穿过**四个进程/语言边界**：

```
容器内 agent
  ↓ 工具调用（dsh-folk-workflow.mjs，JS）
dsh-workflow CLI（node 脚本，落在 rootfs）
  ↓ HTTP（回环桥 127.0.0.1，token 鉴权）
App 侧 WorkflowBridge（Kotlin）
  ↓ 审批弹出 → WorkflowApprovals（状态机）
WorkflowEngine（Kotlin，协程）
  ↓ execRootfs（proot/proroot/chroot）
容器内实际执行
```

**踩坑的前提认知**：这四层里任何一层的「格式假设」与另一层不一致，都会表现为
**运行期静默失败**（AI 说「我创建了」但列表里没有、或流程跑了一半停住）。
所以契约必须只有一份来源。

| 层 | 文件 | 职责 |
|---|---|---|
| 工具与提示词 | `app/src/main/assets/dsh-folk-workflow.mjs` | 注册 4 个工具、把 DSL 文档贴进系统提示词、写自检状态文件 |
| CLI | `DshRuntimeScripts.WORKFLOW_CLI_SCRIPT` | 落盘/读取/校验/运行，唯一与 HTTP 说话的地方 |
| 桥 | `WorkflowBridge.kt` | HTTP 端点、审批编排、启动运行 |
| 引擎 | `WorkflowEngine.kt` | 节点调度、分支、超时、状态落盘 |
| 模型与校验 | `WorkflowModels.kt` / `WorkflowValidator.kt` | 唯一的数据结构与合法性判据 |

---

## 2. 跨进程契约：DSL 一旦分叉就会静默出错

### 坑：字段名对不上，编译器帮不了你

规范文档里曾写过 `edge.fromNodeId` / `edge.toNodeId`，而**仓库里的真实字段是 `from` / `to`**：

```kotlin
data class WorkflowEdge(val from: String, val to: String, val whenExpr: String = "")
```

CLI 是 JS、桥是 Kotlin，两边不做类型检查。照抄文档写出来的代码**要么编译不过，要么
解析出空边**（Gson 对缺失字段给默认值，于是「连线全没了」但不报错）。

**做法**：任何跨语言字段必须以 `WorkflowModels.kt` 为准，文档里的示例代码**不要直接抄**。
门禁 `check-workflow.js` 里有一条真实 CLI 的 JS harness —— 它会真的跑一遍 `dsh-workflow`
的解析，这是唯一能挡住这类分叉的东西。

### 坑：节点状态用过 7 态，别照抄旧文档的 5 态

```kotlin
internal enum class WorkflowStepState(val id: String) {
    PENDING("pending"), RUNNING("running"), OK("ok"), FAILED("failed"),
    TIMEOUT("timeout"), SKIPPED("skipped"), CANCELED("canceled"),
}
```

早期文档写的是 `SUCCEEDED`，实际是 **`OK`**。这些 `id` 会落盘（`workflow-runs/*.json`），
改名等于让历史运行读不出来。**不要为了对齐文档去改它**。

### 坑：坐标是 `Int`（dp），不是 `Float`

```kotlin
val posX: Int = 0,   // dp，12dp 网格吸附
val posY: Int = 0,
```

文档建议 `0f`。改 `Float` 会同时破坏：落盘格式、网格吸附、以及画布命中判定。
**新增字段可以，改既有字段类型不行。**

### 坑：`schemaVersion` 必须在两处同时改

`WorkflowSchema.SCHEMA_VERSION` 与 CLI 里的常量。只改一边 → 所有旧定义被拒或新定义被拒。

---

## 3. 审批：阻塞语义与 60 秒 TTL

### 创建是黄色、运行是红色，这是两条独立命令

```kotlin
val accent = if (creating) Color(0xFFB58900) else MaterialTheme.colorScheme.error
```

不要合并成一条「执行」命令：创建之后**什么都没跑**，运行可能造成破坏。
两者给用户的认知负担完全不同。

### 坑：TTL 计时器在「编辑」期间必须暂停

用户在编辑页改定义的几分钟里，如果 60 秒 TTL 继续走，他改完发现审批已过期 —— 白改。

```kotlin
const val TTL_MS = 60_000L
fun startEditing(id: Long)   // 暂停倒计时，并重新给一个完整 TTL
```

`WorkflowApprovals` 里编辑与审批是**两个状态**，不是同一个的变体。保存或取消都重新计时。

### 坑：「有改动」必须以编辑前的定义算 diff

```kotlin
diff = workflowDiff(submitted, edited)
```

`submitted` 是 AI 提交的那份，`edited` 是用户改后的。**不要把 diff 算成「和上次保存的比」** ——
那样用户会看到一堆与他这次无关的变更，然后开始怀疑批准的是什么。

diff 要跟着结果一起回给 AI（`diff` + `diffText` 两个字段），否则 AI 会继续按它原来的
（已经被用户改掉的）理解往下走。

### 坑：拒绝理由要能带回给 AI

`rejectReasons: ConcurrentHashMap<Long, String>`。理由为空也要回一个明确的「用户拒绝」，
而不是让 AI 看到超时或 500 —— 那会让它重试，然后反复弹窗。

---

## 4. 执行引擎：分支、跳过与超时

### 坑：没走到的分支必须标 SKIPPED，不能从结果里消失

跑成功后遍历未访问节点：

```kotlin
val visited = run.steps.map { it.nodeId }.toSet()
// 其余节点 → WorkflowStepState.SKIPPED, reason = "branch_not_taken"
```

**为什么**：用户看到的流程图上有 8 个节点，运行详情只有 5 步。不标跳过的话，他会以为
引擎漏跑了。标注之后「没走这条路」是一个明确的事实，而不是一个缺口。

### 坑：`onError` 与「有没有 false 分支」耦合

```kotlin
if (!up && node.onError == WorkflowOnError.STOP && !hasFalse) {
    failRun(ctx, runId, "dsh_unavailable")
}
```

`hasFalse = def.outgoing(node.id).any { it.whenExpr == "false" }`。
也就是说：**画了 false 分支就按分支走，没画才按 `onError` 决定要不要整单失败**。
先判 `onError` 会让画了补救分支的流程依然整单失败。

### 超时：默认 120 秒，不是 60

```kotlin
val timeoutMs: Long = 120_000L   // 1000..3600000
```

单步超时是**节点级**的，与审批 TTL（60s）是两回事。混为一谈会导致长命令被误杀。

---

## 5. DSH 未启动时的阻塞与分支

`trigger` 节点有两个开关，语义**不同**：

| 配置 | 行为 |
|---|---|
| `requireDsh = "0"` | 不检查、不启动，直接走 `true` 分支 |
| `requireDsh = "1"` | `awaitRunning(timeoutMs)`，结果写入 `branches[node.id]`，再按 `whenExpr` 分流 |
| `autostartDsh = "1"`（配合上一条） | 先 `HarnessService.start()` 再等 |

**关键**：`requireDsh=1` 且画了 `false` 边时，「DSH 没起来」会走 false 分支（可能是「发个通知
让用户手动启动」），**不会**让整单失败。这是设计上最容易被误读的一处 —— 它同时覆盖
「一开始就没启动」和「启动等超时」两种情况，因为出口都是 `up == false`。

---

## 6. Compose 画布：手势与坐标的七次返工

这一节是本文档最长的一节，因为这里的坑**全部是「代码看起来完全合理、真机上手就是不跟手」**。

### 6.1 永远不要让两个东西同时抢手势

第一版：卡片 `clickable` + 卡片 `pointerInput` 拖动 + 画布 `detectTransformGestures` 平移缩放。
真机表现：**卡片拖不动**。三者抢触摸，谁先消费事件取决于细节，结果不可预测。

**最终架构**（照点击助手的做法：它的卡片就是各自的 View，各自 `onTouchEvent`）：

- **卡片自己**接点 / 拖 / 从针脚拉线 —— 命中不需要手算，手指按在卡上事件就到那张卡；
- **画布**只在空白处接拖动（平移）；
- 谁都不需要在两套坐标系之间换算命中矩形。

### 6.2 `pointerInput` 的 key 用 `Unit`，回调必须 `rememberUpdatedState`

这是最容易反复踩的一条。第一版：

```kotlin
Modifier.pointerInput(node.id) { detectDragGestures { _, amount -> onDrag(amount) } }
```

`onDrag` 是**第一次组合时**捕获的闭包。key 没变 → Compose 不重启手势块 → 拖动时用的是
**最初始的坐标**。真机现象：**第一次拖动正常，第二次一上手就跳回原位**。

修法（缺一不可）：

```kotlin
val dragCb = rememberUpdatedState(onDrag)
Modifier.pointerInput(Unit) { detectDragGestures { _, amount -> dragCb.value(amount) } }
```

同理适用于坐标：卡片位置每帧都变，若拿 `positions` 当 key，手势检测器会被**反复取消重建**，
位移被重复应用 —— 手感「发飘 / 越拖越快」。

### 6.3 拖动时不要每帧吸附网格

```kotlin
// 错：每次位移都吸附，小于半格（6dp）的位移被四舍五入吃掉 → 原地颤抖
val nx = snapToGridDp(node.posX + dxDp)

// 对：拖动期间累加**未吸附**位移，松手时才吸附
dragAcc += dxDp
// 预览位置 = 起点 + dragAcc（1:1 跟手）
// onDragEnd → draft = snapToGridDp(...)
```

真机现象：**一拖就在原地颤抖**（抖而不走）。

### 6.4 坐标换算只留一处

曾有三套换算散在画布、卡片偏移、宿主写回：`dp × density`、`+ pan / density`、`dxPx / density`，
外加 `Int` 截断。结果：小位移被吃掉、位置漂移。

```kotlin
internal object WorkflowGeometry {
    const val GRID_DP = 12
    fun pxToDp(px: Float, density: Float): Float = if (density > 0f) px / density else px
    fun snapToGridDp(dp: Float): Int = (dp / GRID_DP).roundToInt() * GRID_DP
}
```

门禁里有一个**独立复算的数值契约**（`density=2.75`、手指 110px → 40dp → 吸附 36dp），
公式一改就红。

### 6.5 `pan` 只能应用一次

先在图层 `translationX/Y` 应用，又在卡片 `offset` 里加一遍 → 位置漂移一倍。
现在只在 `graphicsLayer` 上应用。

### 6.6 拉线：手势挂在**针脚粒子**上，不挂在整列

第一版把拉线手势挂在「输出引脚那一列」上，而 `onDragStart` 给的是**列的局部坐标**，
却被当成卡片坐标去加卡片原点 → 线头**偏左一大截**。

修法：手势挂在针脚自己身上（触摸目标 26dp，视觉圆点不变），线头 = 粒子中心、
手指 = 粒子原点 + 局部坐标，**都在窗口坐标里算**。

已连的线也要从**真针脚**起画（卡片上报每个针脚的 `boundsInWindow()`），
否则线一律从卡片右上角出发，看起来像「没接上」。

### 6.7 落点必须跟着松手位置走

三条拖放路径**必须共用同一处换算**：

| 路径 | 落点来源 |
|---|---|
| 抽屉拖出 | 松手时的窗口坐标 |
| 拉线落在卡片上 | 连线，不建节点 |
| 拉线落在空白 | 松手坐标 → 弹菜单 → 按该坐标建节点并连边 |

第一版在弹菜单那一刻把松手坐标丢了，菜单回来用默认网格位 —— 新卡片出现在别处。

**幽灵卡对齐**：胶囊左上角 = 手指 = 节点左上角。曾把胶囊自己往左上挪了 40/20dp，
结果看起来「偏移到右下」。

### 6.8 抽屉里不能有滚动容器

抽屉是**纵向拖出**的，而 `verticalScroll` 会先吃掉纵向拖动 → 子项的
`detectDragGestures` 根本收不到。真机现象：**抽屉里的卡片拖不出来**。

修法：7 个类型排成两列 4 行，固定高度，不滚。

> 顺带一个自证：我们写的门禁「抽屉段内不许出现滚动容器」第一次跑就抓到了
> **自己注释里**的 `verticalScroll` 字样 —— 说明断言确实在生效。

### 6.9 模型与显示必须一致

新建流程 / AI 生成的定义坐标**全是 0**，而画布显示的是自动布局算出来的位置。
拖动起点却是模型的 0 → 一上手就跳。

修法：打开编辑页时就把自动布局**写进模型**（作为 baseline），差异也以这份 baseline 为基准
（自动布局不算用户改动，取消时不该弹「放弃改动」）。

### 6.10 防成环：判据只能有一处

```kotlin
WorkflowValidator.addEdgeIfAcyclic(def, fromId, toId)  // 唯一的加边入口
```

连边入口有三条（针脚落卡片、落空白后菜单建节点、`addNode` 的 `afterId`），
只要有一条忘了判，环就会从那里漏进去 —— 而环一旦进定义，执行器拓扑调度会死循环。

注意：`addNode` 那两处直连的是**本次刚创建的节点**（还没有出边，按构造不可能成环），
所以不需要判 —— 门禁要按这个真实不变量写，而不是笼统要求「零直接拼边」。

---

## 7. Kotlin 语言细节：那些编译期不报、运行期才炸的坑

### 7.1 lambda 里的 `this` 不是外层类

```kotlin
// 错：this 指向 lambda 自身，Unresolved reference
this.setState { s -> s.copy(message = this.str(R.string.x)) }

// 对：先取到外面
val msg = this.str(R.string.x)
this.setState { s -> s.copy(message = msg) }
```

`withContext(Dispatchers.IO) { this.installArchive(...) }` 同样中招 —— `this` 是**协程作用域**。
修法：`val self = this` 再在 lambda 里用 `self`。

### 7.2 接口成员与已有 private 成员同名 → Conflicting overloads

给一个已有同签名 `private fun` 的 object 加 `override` 会冲突（Kotlin 不把 private 成员当接口实现）。
两条出路：

- 把 private 放宽为 `internal`（先确认**仓库内外部调用为 0**）；
- 或**收窄接口**，别要求那些扩展函数提供的能力（扩展函数**无法**满足接口成员）。

### 7.3 object 内的裸调用优先解析到**成员**，不是同包扩展函数

```kotlin
// object 内：假设已有 `private fun prefs()`，接口也要求 `prefs()`
override fun prefs() = prefs()   // ← 无限递归，运行期栈溢出
```

修法：显式私有别名（`hostPrefs()`）或改名（`*Impl`），别指望同包扩展函数兜住。

### 7.4 `Shell.Result.isSuccess` 是 `Boolean?`

```kotlin
if (ok == true) ...   // 不能写 if (ok)
```

### 7.5 含引号/`$` 的 shell 命令用 raw string

```kotlin
// 错：\\" 在普通字符串里变成字面量 \"，字符串提前闭合
.add("if command -v \\"\\$c\\" ...")

// 对
val probe = """
    if command -v "${'$'}c" >/dev/null 2>&1; then ...
""".trimIndent()
```

### 7.6 KDoc 里写 `/xxx/*` 会开嵌套注释

Kotlin 块注释**可嵌套**。文档里写 `` `/fs/*` `` 会让注释深度 +1，整个文件报「块注释未闭合」。
改成「fs 端点族」这类措辞。

### 7.7 跨文件可达性：搬家前先算依赖闭包

把函数搬到同包新文件时，只要碰到原来的 `private` 成员就编不过。**搬家前**先用脚本算出
它引用了哪些成员、可见性够不够；别忘了 **`const val`** 与 **`private-in-file` 类型**
（`internal fun` 以 private 类型作返回/参数会被拒）。

### 7.8 Java 文件里别写 Kotlin 属性

```java
tarball.isFile        // 错：Java 是 isFile()
```

`kt-lint` 只查 `.kt`，这类错误只能等 javac。已加 `tools/check-java-syntax.js` 兜住。

---

## 8. 工具注册与降级

### 坑：cordis 对「没声明 inject 的服务」直接抛异常

```js
// 错：只声明 systemPrompt，然后读 ctx.tools → cannot get property 'tools' without inject
export const inject = ['systemPrompt']

// 对
export const inject = ['systemPrompt', 'tools']
```

不是返回 `undefined`，是**抛异常**，而且会让整个 entry 不激活 —— 连提示词段都没了。

### 降级链要落盘，让设置页能说真话

工具注册依赖 dsh 的工具 API 形状（不同版本不一样），所以三级降级：

1. `@deepseek-ai/dsh-tools` 的 `defineTool`；
2. 直接 `ctx.tools.register(裸 spec)`；
3. 只留提示词段（工具没有，能力不丢 —— agent 可以改用 `dsh-workflow` CLI）。

结论写进 `/root/.dsh/workflow-tool-status.json`（`ok` / `degraded` / `unavailable` + 原因）。
不写的话，用户遇到「agent 说没有这个工具」时无从判断是插件没装、还是 API 不兼容。

---

## 9. 无本地编译器时如何验证

这个项目的开发环境**没有本地 Android SDK**（内存上限不足以跑 Gradle），CI 是唯一编译器。
于是所有「本地能过」的手段都只是**过滤器**，不是证明：

| 手段 | 能挡什么 | 挡不住什么 |
|---|---|---|
| `node tools/check-*.js`（35 个） | 行为丢失、契约分叉、字符串缺失 | 任何类型错误 |
| `node tools/kt-lint.js`（字符串感知） | 括号/模板/导入/Compose 卫生 | 类型、可见性、未解析引用 |
| `check-java-syntax.js` | Java 里写 Kotlin 属性 | 其余 Java 错误 |
| 结构边界 + 逐字符比对 | 搬家时多删/漏搬 | 语义等价性 |
| CI（唯一真相） | 一切编译错误 | 运行期行为 |

**因此的工作纪律**：

1. **一次只改一个内聚的簇**，每步一个提交，CI 绿再往下；
2. 搬家用「结构边界（下一个 4 空格成员之前）+ 末行形态断言」，
   **不要**写自己的括号匹配器去切 Kotlin —— 字符串模板里的引号会把状态机带偏
   （我们曾因此多删了 159 行）；
3. 关键函数搬家后**立刻核对**：残留符号为 0 + 目标函数仍在 + 行数对得上；
4. **门禁红了不许推**（把命令链写成红就 `exit 1`，别让它继续往下跑）；
5. 断言本身也会写错 —— 我们有一条断言写的正是「回退到 `baseArgv()`」这个**错误做法**，
   后来修代码时才发现。改实现时**同时**看断言。

---

## 10. 给 agent 的提示词与文档怎么放

### 格式放在**最不可能丢**的通道上

实测里 agent 卡在「不知道格式」上。三条通道按可靠性排序：

1. **工具描述内嵌 `FORMAT`** —— 一定随 schema 到达模型；
2. `/root/.dsh/workflow-dsl.md`（宿主落盘）—— 依赖「写进容器 + 插件激活」两个前提；
3. 系统提示词段 —— 同样依赖插件激活。

所以格式速查放 (1)，详细说明放 (2)，(3) 只放「有这个能力 + 怎么开始」。

### 工具描述要写清**失败语义**

不只写「返回 runId」，还要写：

- 默认不阻塞（`wait=false`）→ 返回 `runId`，用 `workflow_status` 跟进；
- `wait=true` 有上限，超时返回 `state: "still_running"` 而不是无限等；
- 定义不合法会在**执行前**被拒（列错误），不会部分执行。

### 状态查询要给 `logNextLine`

```json
{ "runId": "...", "fromLine": 42 }
```

回复里带 `logNextLine`，AI 下次用它作 `fromLine`，就不用重读整份日志（也避免上下文被日志冲爆）。

---

## 附：相关文件与门禁

| 你改什么 | 必须跑 |
|---|---|
| DSL / 校验 / 引擎 | `node tools/check-workflow.js`（含真实 CLI harness） |
| 画布 / 编辑页 | 同上（手势结构、坐标契约、防成环断言都在里面） |
| 桥 / 守卫 | `check-workflow.js` + `check-native-caps.js` |
| 字符串 | `check-strings.js` + `check-i18n.js`（中英条目数必须一致） |
| 任何 Kotlin | `node tools/kt-lint.js $(find app/src -name '*.kt')` |
| 任何 Java | `node tools/check-java-syntax.js` |

完整清单见 `.github/workflows/build.yml` 的 `Static source checks` 步骤 —— 那里列出的就是
**发版前必经的 35 道门**。
