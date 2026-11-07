package me.bmax.apatch.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import me.bmax.apatch.R
import me.bmax.apatch.dsh.WorkflowDefinition
import me.bmax.apatch.dsh.effectiveCaps

/**
 * 流程详情弹窗：**点列表里的一行就弹这个**，不再在行内就地展开。
 *
 * ## 为什么值得单独一个弹窗
 *
 * 用户点一行流程时想知道的是三件事：它叫什么、它长什么样、我能对它做什么。
 * 上一版把这三件事塞在行内展开里：行高忽大忽小、预览占掉半屏、按钮跟着滚走。
 * 弹窗把这三件事固定在同一屏，且**预览可缩放** —— 流程一长，固定尺寸的预览就是一团线。
 *
 * ## 缩放手势为什么不能用滚动容器包
 *
 * 之前 WorkflowScreen 里是 horizontalScroll + verticalScroll 套一层 graphicsLayer。
 * 那套写法在真机上是坏的：滚动容器会先吃掉双指手势，缩放要么不响应、要么和滚动打架。
 * 这里直接上 detectTransformGestures，只取 zoomChange，预览区不滚动。
 */
@Composable
internal fun WorkflowDetailDialog(
    def: WorkflowDefinition,
    lastStateText: String?,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    // 缩放只在本次弹窗内有效：关掉再开是"重新看一遍"，不是"接着上次的倍率"。
    var zoom by remember(def.id) { mutableStateOf(1f) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Text(
                    text = def.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = def.description.ifBlank { stringResource(R.string.dsh_wf_detail_desc) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.dsh_wf_detail_nodes, def.nodes.size, def.edges.size) +
                        (lastStateText?.let { " · " + it } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(10.dp))
                // 预览区：固定高度 + 双指缩放。理由见函数头注释。
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            RoundedCornerShape(12.dp),
                        )
                        .pointerInput(def.id) {
                            detectTransformGestures { _, _, zoomChange, _ ->
                                zoom = (zoom * zoomChange).coerceIn(0.4f, 1.6f)
                            }
                        },
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                // 以左上角为原点缩放：与编辑器里的视口同一套约定，
                                // 否则两边看到的"图往哪个方向长"会不一致。
                                transformOrigin = TransformOrigin(0f, 0f)
                                scaleX = zoom
                                scaleY = zoom
                            },
                    ) {
                        WorkflowGraphPreview(def)
                    }
                }

                Spacer(Modifier.height(10.dp))
                // 权限：用 effectiveCaps（手工声明 + 动作目录推出来的那份），
                // 而不是只显示手工声明 —— 用卡片搭的流程可能一项都没手工声明。
                val caps = def.effectiveCaps
                Text(
                    text = if (caps.isEmpty()) {
                        stringResource(R.string.dsh_wf_detail_nocaps)
                    } else {
                        stringResource(R.string.dsh_wf_detail_caps, caps.joinToString("、"))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDelete) {
                        Text(
                            stringResource(R.string.dsh_workflow_action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    TextButton(onClick = onEdit) { Text(stringResource(R.string.dsh_workflow_action_edit)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dsh_workflow_action_close)) }
                }
            }
        }
    }
}
