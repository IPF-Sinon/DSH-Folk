package me.bmax.apatch.ui.component

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * 设置页的开关卡片。
 *
 * @param onLongClick 长按卡片时触发（给需要二级配置的开关用，如「竞速通道」的通道勾选弹窗）。
 *   传 null 时**不加**长按手势，卡片行为与以前完全一致。
 */
@Composable
fun ToggleSettingCard(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    flat: Boolean = false,
    icon: ImageVector? = null,
    onLongClick: (() -> Unit)? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    ExpressiveCard(flat = flat) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .run {
                    if (onLongClick == null) {
                        // 没有长按就用原来的 toggleable：语义、涟漪、Switch 角色都不变
                        toggleable(
                            value = checked,
                            onValueChange = { if (enabled) onCheckedChange(it) },
                            role = Role.Switch,
                            enabled = enabled,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(),
                        )
                    } else {
                        combinedClickable(
                            enabled = enabled,
                            role = Role.Switch,
                            onLongClick = { onLongClick() },
                            onClick = { onCheckedChange(!checked) },
                        )
                    }
                }
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(16.dp))
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 16.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                )
            }

            ExpressiveSwitch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
            )
        }
    }
}
