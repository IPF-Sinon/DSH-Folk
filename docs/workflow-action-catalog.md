# 动作目录扩充清单（对齐点击助手 184 个动作）

> 索引文档。**每条都要回代码核实**，尤其端点是否存在。

## 现状

| | 数量 |
|---|---|
| 点击助手 `ActionType` | 184 |
| 我们 `WorkflowActionCatalog.ACTIONS` | 31 |

## 关键结构约束（决定怎么扩）

`WorkflowActionCatalog.Action` 强制要求：
```kotlin
data class Action(
    val id: String, val groupTitleRes: Int, val titleRes: Int, val descRes: Int,
    val cap: DshNativeBridge.Cap,      // 必须绑一个系统能力
    val method: String, val path: String,  // 必须在 DshNativeBridge dispatch 里真实存在
    val params: List<Param> = emptyList(),
)
```
并且门禁 `check-workflow-actions.js` 会逐条核对端点。

**结论**：纯计算类动作（数值/文本/列表/字典）**不能**直接塞进这个目录 —— 它们没有端点、
也不需要系统能力。硬塞会逼我们编一个假端点，那比没有更糟。

## 扩容方案（分三批，风险递增）

### 批次 A：纯计算（无端点、无权限）— **已实现**

做法：`WorkflowActionType.COMPUTE` + 新文件 `dsh/WorkflowCompute.kt`，引擎本地求值，不走 bridge。

落地要点（照这些改，别重犯）：

| 位置 | 改什么 |
|---|---|
| `WorkflowModels.kt` 的枚举 | 加 `COMPUTE("compute")` |
| `WorkflowModels.dataInputKeysOf` | 给计算动作**它自己的**参数键（不是 `else` 落的 `cmd`） |
| `WorkflowModels.defaultPinsFor` | 按参数生成 DATA_IN 针脚，键不带 `p_` 前缀 |
| `WorkflowEngine` 的动作分发 | 新分支，`WorkflowCompute.evaluate(...)`，**不调** `DshNativeBridge.handle` |
| `WorkflowValidator` | 校验 `bridge` 在册、NUMBER 参数可解析（不强制非空） |
| `WorkflowNodeFields.fieldsFor` | 字段来自计算定义，界面不认识键名 |
| `WorkflowNodeFields.defaultConfigFor` | 显式给 `COMPUTE` 分支，否则落 `else` 变成 shell |
| `WorkflowDialogs` 三处 when | `configForAction` / `actionLabelRes` / `editableKeys` |
| `WorkflowBridge` 摘要 | 给名字，否则落到 `else` 显示成空的 `$ ` |
| `WorkflowEditorSurface` | `editorKindOfCompute` + `actionKindsOf` 合并两册 + 四个分组 |
| `workflowActionIcon` | 每个新动作**独立**图标（有防重复断言） |
| 字符串 | 中英成对，`check-strings.js` 会拦 |
| import | `tools/check-imports.js` 会抓漏（本轮抓到两处） |

已实现 37 个：数值 13（四则/取余/绝对值/取整/较大/较小/随机/相等/小于/大于）、
文本 12（拼接/截取/替换/拆分/正则/去空白/长度/大小写/相等/包含/URL 编解码）、
布尔 3、JSON 2、列表 6。

**刻意没做**：`num_pow`（`kotlin.math.pow` 不在默认导入里，且需要额外 import 才能用）。

| 组 | 动作 | 引擎实现 |
|---|---|---|
| 数值 | add / sub / mul / div / mod / abs / round / toInt | 四则 + `BigDecimal` 防浮点误差 |
| 数值比较 | equal / less / greater | 输出 bool |
| 随机 | random | `Random.nextInt` |
| 文本 | append / substring / replace / split / indexOf | `String` 方法 |
| 文本匹配 | regex / equal / contains | `Regex` |
| 文本转换 | toNumber / fromObject / encode / decode | `URLEncoder` / Base64 |
| JSON | parseJson / toJson | `JSONObject`（已有依赖） |
| 布尔 | or / and / not | 布尔运算 |
| 列表 | make / size / contain / add / remove / append / clear / get / set / indexOf / sublist / sort / reverse | 以 JSON 数组字符串为序列化形式 |
| 字典 | make / size / containKey / remove / append / clear / get / set / keys / values | 以 JSON 对象字符串为序列化形式 |

**列表/字典的序列化格式要先定**：现有 `vars` / `outputs` 都是 `Map<String,String>`，
所以列表只能是 JSON 数组字符串。这会影响 `WorkflowPinType` 是否要加 `LIST` / `MAP` 类型。

### 批次 B：需要已有能力、只差端点包装

这些能力我们已经在 `DshNativeBridge` 里有，只是没做成动作：

| 动作 | 复用现有端点 |
|---|---|
| findNode / isNodeExist / getNodeInfo | `/native/a11y/tree` 的结果本地筛选 |
| getOcrText / findOcrText | 若已有 OCR 端点；**需核实**（`WorkflowActionCatalog` 里无 OCR） |
| 通知相关（sendNotification 已有 notify_full） | 已覆盖 |

### 批次 C：需要新能力（不建议本轮做）

- 图片：`GET_IMAGE/FIND_IMAGE/TOUCH_IMAGE/CREATE_QRCODE/PARSE_QRCODE/GET_COLOR`
- 区域/坐标：`AREA_*`、`POINT_*`（多数可在本地算，但需要坐标模型）
- 任务互调：`EXECUTE_TASK/STOP_TASK/SWITCH_TASK`（需设计递归与权限）

## 每加一个动作的检查清单

1. `WorkflowActionCatalog` 加条目（若是 bridge 类）或 `WorkflowEngine` 加分支（若是 COMPUTE）。
2. `WorkflowNodeFields.fieldsFor` 能自动显示参数（走目录参数，不用改）。
3. `WorkflowSchema.dataInputKeysOf` 返回对应键。
4. `WorkflowSchema.defaultPinsFor` 生成针脚（NATIVE 走目录参数，COMPUTE 需单独分支）。
5. `workflowActionIcon` 给**专属**图标（不要与别的动作共用，这正是"图标单一"的成因）。
6. `values/` 与 `values-zh-rCN/` 成对加 title/desc 字符串。
7. 跑 `node tools/check-*.js`（尤其 `check-workflow-actions.js`、`check-strings.js`）。

## 图标与描述（用户指出的"图标单一、卡片没描述"）

参考实现 `ActionInfo` 为 184 个动作**逐个**指定 `R.drawable.icon_xxx` 与 `R.string.xxx_desc`。

当前我们的两个问题：
1. `workflowActionIcon` 存在**多动作共用一图标**（`a11y_click`/`a11y_tap`/`display_tap` 都是 `TouchApp`）。
2. 基础节点（shell / 条件 / 等待 / 取值 / 逻辑）**完全没有 descRes**，卡片上没有说明文字。

修法：
- 基础节点补 `descRes`（`EditorNodeKind` 加一个字段，或给每种 type 一个固定描述串）。
- 为 31 个动作逐个指定**不重复**的 `ImageVector`；可用的必须已在工程中出现过
  （Material 图标按名解析，写没引入的名字是编译错误，本地没编译器只能靠 CI）。
