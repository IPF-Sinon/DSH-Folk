# 按针脚筛选候选面板（对齐点击助手）

> 索引文档。改动前请回代码核实。

## 目标

拉线松手落在**空白处**时，弹出的节点列表只包含"有针脚能接上当前源针脚"的节点 ——
与点击助手 `SelectActionByPinDialog` 一致。

## 参考实现路径

1. `CardLayoutView.showActionSelector()`：松手空白 → `new SelectActionByPinDialog(ctx, task, pin, cb)`，
   `pin` 是拖动中的针脚；拖出时用原 pin，拖入时反转 `new Pin(value, 0, !pin.isOut())`。
2. `SelectActionByPinDialog.getGroupData()`：对每个候选 Action 构造实例 →
   `action.findConnectToAblePin(touchedPin)` → 为 null 则 `continue`（整条剔除）。
3. `Action.findConnectToAblePin(pin)`：`pins.stream().filter(p -> p.linkAble() && p.linkAble(pin)).findFirst()`。
4. `Pin.linkAble(other)`：`out == other.isOut()` 直接 false（必须一进一出）；
   同 owner 不许；再交给 `PinBase.linkToAble/linkFromAble` 判类型。

## 当前实现缺口

| 环节 | 现状 |
|---|---|
| 画布上报 `fromPin` | **已有**：`onWireDrop(fromId, fromPin, toId, pointerWindow, targetPin)` |
| 落空白时记录 | `WorkflowEditorSurface.kt:846` 只记 `addAfter = fromId`，**fromPin 丢失** |
| 面板过滤 | `editorPickerGroups()` 全量，无过滤 |
| 可接性判定 | **已有** `WorkflowPinSpec.canLinkFrom(source)` + `WorkflowPinType.accepts` |

所以最小改动是：把 `fromPin` 存进新状态，面板接收一个 `filterPin`，用已有判定过滤。
**不需要**新建判定逻辑。

## 设计

```kotlin
// WorkflowEditorSurface.kt
var addAfterPin by remember { mutableStateOf("") }   // 新增：落空白时的源针脚

// 落空白处：
addAfterAt = pointer
addAfter = fromId
addAfterPin = fromPin          // ← 新增这一行

// 面板调用：
WorkflowNodePicker(
    groups = editorPickerGroups(),
    filterPin = addAfterPin.takeIf { it.isNotBlank() },
    ...
)
```

面板侧（`WorkflowNodePicker.kt`）：
```kotlin
fun WorkflowNodePicker(
    ...,
    /** 非空 = 只显示"有针脚能接上这个源针脚"的节点。空 = 不过滤（底部「加节点」入口）。 */
    filterPin: String? = null,
)
```

过滤要落到 `NodePickerEntry`：需要给每个 entry 带上它的针脚表，或用 lambda 判定：
```kotlin
data class NodePickerEntry(
    ...,
    /** 这个节点能否接上给定的源针脚。null = 恒可接。 */
    val acceptsPin: ((sourceKind: WorkflowPinKind, sourceType: WorkflowPinType) -> Boolean)? = null,
)
```

### 必须注意的三个坑

1. **数据针脚 vs 控制流针脚方向相反**。从 `EXEC_OUT` 拖出时只能接 `EXEC_IN`；
   从 `DATA_OUT` 拖出时只能接 `DATA_IN`。`canLinkFrom` 已实现这个判断，但它是
   `WorkflowPinSpec` 的方法，而面板只有 `EditorNodeKind` —— 需要先用
   `WorkflowSchema.defaultPinsFor(假节点)` 造出针脚表才能调用。
2. **`requireDsh` 的 trigger 分叉**：某些节点的针脚表依赖 config（如 trigger 的 requireDsh、
   原生动作的 bridge 参数）。构造假节点时要用该 kind 的默认 config。
3. **过滤后可能为空**（例如从一个 `DATA_OUT` 拖出，但没有节点有对应类型的数据入口）。
   参考实现此时弹窗是空的；我们应该显示"没有能接上这颗针脚的节点"，而不是静默不弹。

## 验收

- 底部「加节点」入口：**不过滤**（`filterPin = null`），列表不变。
- 从执行出口拖到空白：只出现有 `EXEC_IN` 的节点。
- 从数据出口拖到空白：只出现有同类型数据入口的节点（`ANY` 都能接）。
- 过滤为空时有明确提示。
- `tools/check-workflow.js` 增加断言：`addAfterPin` 被记录、面板收到 `filterPin`。
