package me.bmax.apatch.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.bmax.apatch.dsh.WorkflowActionType
import me.bmax.apatch.dsh.WorkflowNode
import me.bmax.apatch.dsh.WorkflowNodeType
import me.bmax.apatch.dsh.WorkflowPinKind
import me.bmax.apatch.dsh.WorkflowPinSpec
import me.bmax.apatch.dsh.WorkflowSchema
import me.bmax.apatch.dsh.WorkflowStepState

/** 节点卡的固定宽度：连线端点的算法与卡片实宽必须是同一个数，否则线会飘。 */
internal val WorkflowNodeCardWidth = 220.dp

/** 卡片头部（标题那一行）的中心高度：连线从这一行接出去。 */
internal val WorkflowNodeCardAnchorY = 26f

/**
 * 一个节点的卡片（预览、详情、画布共用）。
 *
 * 视觉规范（对齐点击助手的卡片 + Operit 的语义色带）：
 *  - 顶部一条按**节点类型**着色的色带 —— 不是整卡着色：整卡着色会让画布变成调色板，反而读不出层级；
 *  - 外边框按**运行状态**变色（运行中 / 成功 / 失败 / 跳过），「哪一步在跑」不用打开详情；
 *  - 颜色一律取 MaterialTheme 的角色色。硬编码十六进制在这份代码里踩过：画布上直接写 0x22 黑，
 *    深色主题下网格几乎看不见；
 *  - 引脚行左入右出、标签单行省略 —— 针脚文字与卡片边界挤压，正是真机截图上「糊在一起」的直接原因。
 */
@Composable
internal fun WorkflowNodeCard(
    node: WorkflowNode,
    modifier: Modifier = Modifier,
    status: WorkflowStepState? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    onDrag: ((Float, Float) -> Unit)? = null,
) {
    val accent = workflowNodeAccent(node.type)
    val borderColor = when (status) {
        WorkflowStepState.RUNNING -> MaterialTheme.colorScheme.primary
        WorkflowStepState.OK -> MaterialTheme.colorScheme.tertiary
        WorkflowStepState.FAILED, WorkflowStepState.TIMEOUT -> MaterialTheme.colorScheme.error
        WorkflowStepState.SKIPPED, WorkflowStepState.CANCELED -> MaterialTheme.colorScheme.outline
        else -> if (selected) accent else MaterialTheme.colorScheme.outlineVariant
    }
    val thick = status != null || selected
    val pins = WorkflowSchema.defaultPinsFor(node)
    val inputs = pins.filter { it.kind == WorkflowPinKind.EXEC_IN || it.kind == WorkflowPinKind.DATA_IN }
    val outputs = pins.filter {
        it.kind == WorkflowPinKind.EXEC_OUT || it.kind == WorkflowPinKind.EXEC_TRUE ||
            it.kind == WorkflowPinKind.EXEC_FALSE || it.kind == WorkflowPinKind.DATA_OUT
    }

    val base = modifier.width(WorkflowNodeCardWidth)
    val clickable = if (onClick != null) base.clickable { onClick() } else base
    val draggable = if (onDrag != null) {
        clickable.pointerInput(node.id) {
            detectDragGestures { change, amount ->
                change.consume()
                onDrag(amount.x, amount.y)
            }
        }
    } else {
        clickable
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (thick) 2.dp else 1.dp, borderColor),
        tonalElevation = if (selected) 6.dp else 2.dp,
        modifier = draggable,
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(4.dp).background(accent))
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    text = node.name.ifBlank { workflowNodeLabel(node) },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = workflowNodeSubtitle(node),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (inputs.isNotEmpty() || outputs.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            for (pin in inputs) WorkflowPinRow(pin, alignEnd = false)
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            horizontalAlignment = Alignment.End,
                        ) {
                            for (pin in outputs) WorkflowPinRow(pin, alignEnd = true)
                        }
                    }
                }
            }
        }
    }
}

/** 一个引脚：小圆点 + 标签（执行引脚没有标签，只画点）。 */
@Composable
private fun WorkflowPinRow(pin: WorkflowPinSpec, alignEnd: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (alignEnd) {
            PinLabel(pin, alignEnd)
            Spacer(Modifier.width(4.dp))
            PinDot(pin)
        } else {
            PinDot(pin)
            Spacer(Modifier.width(4.dp))
            PinLabel(pin, alignEnd)
        }
    }
}

@Composable
private fun PinDot(pin: WorkflowPinSpec) {
    val exec = pin.kind != WorkflowPinKind.DATA_IN && pin.kind != WorkflowPinKind.DATA_OUT
    Box(
        Modifier
            .size(if (exec) 7.dp else 9.dp)
            .background(
                color = if (exec) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                shape = CircleShape,
            ),
    )
}

@Composable
private fun PinLabel(pin: WorkflowPinSpec, alignEnd: Boolean) {
    if (pin.label.isBlank()) return
    Text(
        text = pin.label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
    )
}

/** 按节点类型给一条语义色带。取主题角色色，亮/暗都成立。 */
@Composable
private fun workflowNodeAccent(type: WorkflowNodeType): Color = when (type) {
    WorkflowNodeType.TRIGGER -> MaterialTheme.colorScheme.tertiary
    WorkflowNodeType.EXECUTE -> MaterialTheme.colorScheme.primary
    WorkflowNodeType.CONDITION, WorkflowNodeType.LOGIC -> MaterialTheme.colorScheme.secondary
    WorkflowNodeType.EXTRACT -> MaterialTheme.colorScheme.primaryContainer
    WorkflowNodeType.WAIT -> MaterialTheme.colorScheme.secondaryContainer
}

private fun workflowNodeLabel(node: WorkflowNode): String = when (node.type) {
    WorkflowNodeType.TRIGGER -> "触发"
    WorkflowNodeType.EXECUTE -> node.actionType?.id ?: "execute"
    WorkflowNodeType.CONDITION -> "条件"
    WorkflowNodeType.LOGIC -> "逻辑"
    WorkflowNodeType.EXTRACT -> "取值"
    WorkflowNodeType.WAIT -> "等待"
}

private fun workflowNodeSubtitle(node: WorkflowNode): String = when (node.type) {
    WorkflowNodeType.EXECUTE -> {
        val what = when (node.actionType) {
            WorkflowActionType.NOTIFY -> node.config("text")
            else -> node.config("cmd")
        }
        (node.actionType?.id ?: "execute") + if (what.isBlank()) "" else " · " + what.take(24)
    }
    WorkflowNodeType.CONDITION -> "条件 · " + node.config("op") + " " + node.config("value").take(12)
    WorkflowNodeType.EXTRACT -> "取值 · " + node.config("var")
    WorkflowNodeType.WAIT -> "等待 · " + node.config("ms").ifBlank { node.config("untilDsh") }
    WorkflowNodeType.TRIGGER -> if (node.config("requireDsh") == "1") "触发 · 需要 DSH" else "触发"
    WorkflowNodeType.LOGIC -> "逻辑 · " + node.config("op")
}
