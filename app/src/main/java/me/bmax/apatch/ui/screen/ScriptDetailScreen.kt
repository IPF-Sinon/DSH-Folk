package me.bmax.apatch.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.bmax.apatch.R
import me.bmax.apatch.dsh.Userscripts

/**
 * 脚本详情底部弹层。
 *
 * 与插件详情 [me.bmax.apatch.ui.component.DshPluginDetailSheet] **同一形态、同一套排布**：
 * 插件侧一直是 `ModalBottomSheet` + 限高滚动的列 + 底部动作行，而脚本侧原来是一整页二级
 * 目的地（`@Destination ScriptDetailScreen`）—— 同一件事在两处两种形态，用户在两边之间
 * 切换时要重新认一遍。现在没有二级目的地：列表整行点开就地弹出这一层，「开关 / 更新 /
 * 移除」三个动作都收在层里，列表行只留快捷开关。
 *
 * 文件名保留 `ScriptDetailScreen.kt`（历史）；里面已经没有一个 `@Destination`。
 *
 * @param code 正文预览（[Userscripts.code] 读出来的原文），读不到给 null。
 * @param codeLoading 正文还在读（调用方在 IO 线程上取）：先显示一句占位，别让层里空着。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScriptDetailSheet(
    script: Userscripts.Script,
    code: String?,
    onDismiss: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onUpdate: () -> Unit,
    onRemove: () -> Unit,
    codeLoading: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp)
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = script.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = listOfNotNull(
                    script.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                    "run-at " + script.runAt,
                    "${script.bytes} B",
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 开关旁边给一句话：与插件详情弹层同一套手感（那边也是「启用插件 / 停用插件」），
            // 光一个 Switch 说不清拨过去会发生什么
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        if (script.enabled) R.string.dsh_userscripts_toggle_off
                        else R.string.dsh_userscripts_toggle_on
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.weight(1f))
                Switch(checked = script.enabled, onCheckedChange = onToggle)
            }

            Text(
                text = stringResource(
                    R.string.dsh_userscripts_detail_source,
                    script.source ?: stringResource(R.string.dsh_userscripts_detail_source_unknown),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (script.description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(text = script.description, style = MaterialTheme.typography.bodyMedium)
            }

            if (script.matches.isNotEmpty()) {
                Text(
                    text = script.matches.joinToString("\n"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.dsh_userscripts_detail_code),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            // 正文可能几千行：限高 + 自己滚，否则这一层会被它顶到没法看。
            // 限高加在 Box 上、不是 Text 上 —— 门禁不允许「给 Text 限高又不给省略/滚动」。
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    // 还在读（IO 上）时给一句占位；读不到才是空白 —— 两种状态不是一回事
                    text = if (codeLoading) {
                        stringResource(R.string.dsh_userscripts_code_loading)
                    } else {
                        code.orEmpty()
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(6.dp))
            // 动作行与插件详情弹层同形：更新走主按钮、移除走描边按钮，点了先关层
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onDismiss(); onUpdate() }) {
                    Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.dsh_userscripts_update))
                }
                OutlinedButton(onClick = { onDismiss(); onRemove() }) {
                    Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.dsh_userscripts_delete))
                }
            }
        }
    }
}
