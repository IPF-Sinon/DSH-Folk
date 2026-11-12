# 工作流编辑器 vs 点击助手（TouchTool）实现差异对照

> 本文只做**索引与概述**，用于定位改动范围和最终审查。具体改动前必须回到代码核实。
> 参考实现：`.tmp/touchtool`（GPL-3.0，只读）。当前实现：`work/app/src/main/java/me/bmax/apatch/`。

## 0. 一句话结论

点击助手的核心不是"动作多"，而是**每个动作都是自描述的数据源**：每张卡自带专属图标、
专属描述、以及一份可查询的针脚表。编辑器的选卡弹窗因此能按"我要接的那个针脚"反向筛选
候选动作。我们的动作目录只有 31 条、图标按**节点类型**给（同类卡图标必然相同）、
拉线落空白时不携带针脚类型 —— 于是弹窗给的是全量列表，跟点击助手的体验差在这里。

## 1. 按针脚自动筛选候选（用户指出的核心差异）

### 参考实现

`SelectActionByPinDialog.getGroupData()`（`ui/blueprint/selecter/select_action/SelectActionByPinDialog.java`）：

```java
for (ActionType actionType : ActionMap.getTypes(actionGroupType)) {
    ActionInfo actionInfo = ActionInfo.getActionInfo(actionType);
    Action action = actionInfo.getAction();
    Pin pin = action.findConnectToAblePin(touchedPin);  // 关键
    if (pin == null) continue;                            // 接不上就整条剔除
    types.add(actionType);
}
```

触发点：`CardLayoutView.showActionSelector()`（第 798 行附近）。松手落在**空白处**时，
把当前拖动的针脚（拖出时为 out、拖入时反转 `!pin.isOut()`）传给弹窗，弹窗据此筛选。

`findConnectToAblePin`（`bean/action/Action.java`）：
```java
public Pin findConnectToAblePin(Pin pin) {
    return pins.stream().filter(p -> p.linkAble() && p.linkAble(pin)).findFirst().orElse(null);
}
```

### 当前实现

- `WorkflowBlueprintCanvas.kt` 的 `onWireDrop` 回调签名已有 `targetPin`，但**只用于压中卡片时的参数落点**。
- `WorkflowEditorSurface.kt:846` 落空白时只记 `addAfter = fromId`，**没有记录 fromPin**。
- `WorkflowNodePicker` 的 `groups` 是全量 `editorPickerGroups()`，没有按针脚过滤。

### 差距

| 项 | 参考 | 当前 |
|---|---|---|
| 落空白时携带针脚类型 | 是 | **否** |
| 拉线弹窗按针脚筛选 | 是 | **否**（全量） |
| 针脚可接性判定 | `Pin.linkAble(pin)` + `PinBase.linkToAble/linkFromAble` | `WorkflowPinSpec.canLinkFrom` + `WorkflowPinType.accepts`（已有，可复用） |

**结论**：判定逻辑我们已经有了（`canLinkFrom`），缺的是"把源针脚传进面板并过滤"这条线。

## 2. 卡片图标与描述

### 参考实现

`ActionInfo`（`bean/action/ActionInfo.java`）为**每一个动作**逐个指定：
```java
new ActionInfo(ActionType.MANUAL_START, ManualStartAction.class,
    R.drawable.icon_waving_hand,          // 专属图标
    R.string.manual_start_action,          // 标题
    R.string.manual_start_action_desc,     // 专属描述
    ...)
```
184 个动作 = 184 条独立图标 + 独立描述。卡片水印用 `ShapeableImageView` + `alpha 0.1`
（`dialog_select_action_normal_item.xml`）。

### 当前实现

- `workflowActionIcon(actionId)`（`WorkflowNodeCard.kt:735`）已覆盖 31 个动作的分组图标，
  但**多个动作共用同一个图标**（如 `a11y_click`/`a11y_tap`/`display_tap` 都是 `TouchApp`）。
- `nodeTypeIcon(type)` 按**节点类型**给图标：所有 EXECUTE 卡都同图标。
- `PickerCard` 用 `entry.icon`（来自 `nodeTypeIcon(kind.type)`）→ 同类卡片图标全一样。
- 描述来自 `kind.bridge?.descRes`，只有动作目录条目有；基础节点（shell/条件/等待/取值/逻辑）**无描述**。

### 差距

| 项 | 参考 | 当前 |
|---|---|---|
| 图标粒度 | 每动作一个 | 每组一个（抽屉里按类型） |
| 基础节点描述 | 都有 | **无** |
| 水印位置 | 卡片内 overlay | 已有（`alpha 0.10f` TopCenter） |

## 3. 拉线高亮 / 候选反馈

### 参考实现

- 拖拽中：`dragOnPin` 记录"落点处可接的针脚"，命中即 `performHapticFeedback(LONG_PRESS)`（震动反馈）。
- 绘制：`dispatchDraw` 里拖拽中的线用 `colorPrimaryInverse`；两端针脚不同类时做 `LinearGradient`。
- 松手命中：`pin.linkAble(touchedPin.getPin())` 通过才 `mutualAddLink`。

### 当前实现

- 无"落点处可接针脚"的高亮，也无震动反馈。
- 拖拽预览线用固定 `wireColor`，不反映目标针脚类型，也不提示"这里能接/不能接"。
- 已连线高亮：已按上一轮改为取目标针脚类型色 + 选中卡加粗置顶（**已对齐**）。

## 4. 动作数量

| | 数量 |
|---|---|
| 点击助手 `ActionType` | **184** |
| 我们 `WorkflowActionCatalog.ACTIONS` | **31** |

点击助手的 184 个按 `ActionMap.ActionGroupType` 分组（开始/逻辑/通用/点击/应用/系统/数值/文本/条件/控件/图片/区域/位置/列表/字典）。
我们缺的主要是纯计算与数据结构类：
- 数值运算：`NUMBER_ADD/SUB/MUL/DIV/MOD/ABS/RANDOM/TO_INT`、比较 `NUMBER_EQUAL/LESS/GREATER`、`MATH_EXPRESSION`
- 文本处理：`STRING_APPEND/SUBSTRING/REGEX/SPLIT/EQUAL/REPLACE/ENCODE/DECODE`、`PARSE_JSON`、`TO_JSON`、`GET_OCR_TEXT`
- 布尔运算：`BOOLEAN_OR/AND/NOT`
- 列表：`LIST_MAKE/SIZE/CONTAIN/ADD/REMOVE/APPEND/CLEAR/GET/SET/INDEX_OF/SUBLIST/FOREACH/SORT/REVERSE`
- 字典：`MAP_MAKE/SIZE/CONTAIN_KEY/REMOVE/APPEND/CLEAR/GET/SET/KEYS/VALUES/FOREACH`
- 图片：`GET_IMAGE/CROP/RESIZE/SAVE/FIND_IMAGE/IS_IMAGE_EXIST/TOUCH_IMAGE/CREATE_QRCODE/PARSE_QRCODE/GET_COLOR`
- 区域/坐标：`AREA_*`、`POINT_*`
- 控件：`FIND_NODE/GET_NODE_INFO/EDITTEXT_INPUT/PICK_NODE` 等

**注意**：我们每个动作都要有对应的 `DshNativeBridge` 端点才能真跑。
纯计算类（数值/文本/列表/字典）**不需要设备能力**，可以直接在 `WorkflowEngine` 里算，
这是最容易补齐、也最不依赖系统权限的一批。

## 5. 其它已核实差异

| 项 | 参考 | 当前 | 状态 |
|---|---|---|---|
| 控制流针脚形状 | 五边形（上圆下切） | 已实现 | 已对齐 |
| 针脚方向 | 控制流垂直、数据水平 | 已实现 | 已对齐 |
| 连线锚点 | 按方向取边中点 | 已实现 | 已对齐 |
| 已连线配色 | 取针脚色 | 已实现 | 已对齐 |
| 面板版式 | 整页贴边三段标签 | 已实现 | 已对齐 |
| 枚举针脚 | 可接线 | 已实现 | 已对齐 |
| 多来源 | `PinAdd` 动态针脚 | 逗号列表追加 | **实现方式不同，表达能力等价** |

## 6. 本轮已实现（beta.270 起）

| 项 | 做法 | 断言 |
|---|---|---|
| 拉线弹窗按针脚筛候选 | `addAfterPin` 记录源针脚 → 面板 `filterPin` → 复用 `canLinkFrom` 过滤 | check-workflow 632 项 |
| 落点高亮 + 震动 | `wireHoverPin` 实时判定；命中针脚放大到 22dp 换 primary；命中变化才震 | 637 项 |
| 基础节点描述 | `EditorNodeKind.descRes`，七张基础卡各一句 | 638 项 |
| 图标去重 | 31 个动作互不重复的图标（防回退断言） | 640 项 |
| 数据线绕大弯 | `vertical = !isData`（数据走水平走法） | 642 项 |

### 实现时踩到的坑（记录以免重犯）

1. **`Canvas` 的 draw lambda 不是 `@Composable`** —— 在里面读 `MaterialTheme` 编译不过。
   颜色必须在组合期算好再传入。已踩过两次（`workflowPinColor`、`wireRejectColor`）。
2. **public 组件不能暴露 internal 类型当类型参数** —— `acceptsPin: ((WorkflowPinKind) -> Boolean)?`
   报 `exposes its internal parameter type argument`。改传字符串名，合法性由宿主保证。
3. **子组件不能摸父组件局部状态** —— `PresetTab` 里用 `query` 报 Unresolved reference，改为传参。
4. **顶层函数调不到 Composable 内的局部函数** —— `editorPickerGroups` 是顶层 private，调不到
   `WorkflowEditorSurface` 内部的 `withActionDefaults`，必须自己拼 config。
5. **Material 图标按名解析** —— 写一个本工程没用过的图标名 = Unresolved reference，本地无编译器
   只能靠 CI 炸。补图标时优先取 `grep -rhoE 'Icons\.(Filled|Outlined)\.[A-Za-z]+'` 现得的名字。

## 7. 后续工作索引

- `docs/workflow-pin-filtering.md` — 按针脚筛选候选面板的实现要点
- `docs/workflow-action-catalog.md` — 动作目录扩充清单与端点对照（**P5 未做**）

### 仍待做

- **P5 动作目录扩充**：31 → 更多。批次 A（纯计算，无端点）需要新增 `WorkflowActionType.COMPUTE`
  并在引擎里本地求值；批次 B/C 见 `workflow-action-catalog.md`。
- **列表/字典序列化格式**：`Map<String,String>` 只能存字符串，需定 JSON 数组/对象字符串，
  并评估 `WorkflowPinType` 是否加 `LIST`/`MAP`（会动 `accepts` 的互通规则，影响面大）。
- **运行时验证**：以上外观类改动（高亮、图标、连线走法）只过了编译，未经真机确认。
