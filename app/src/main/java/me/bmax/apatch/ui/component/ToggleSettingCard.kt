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
 * @param onLongClick 长按卡片时触发（给需要二级配置的开关用，如「竞速通道」的通道勾选弹窗、
 *   运行时卡片的版本菜单）。传 null 时**不加**长按手势，卡片行为与以前完全一致。
 * @param onClick 整行点一下做**别的**事（运行时卡片：点一下 = 立即检查更新），开关自己仍是
 *   那个开关。传 null（默认）时保持老语义：点整行 = 拨开关。给了它之后整行不再是
 *   Switch 角色 —— 否则 TalkBack 会把「检查更新」念成一个开关。
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
    onClick: (() -> Unit)? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    val rowClick = onClick
    val switchHandlesIt = rowClick != null
    ExpressiveCard(flat = flat) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .run {
                    if (rowClick != null) {
                        // 整行点了做别的事：不给 role（它不是开关），长按照旧可以挂；
                        // 开关由右边那个 Switch 自己负责（见下面的 onCheckedChange）。
                        combinedClickable(
                            enabled = enabled,
                            onLongClick = onLongClick,
                            onClick = rowClick,
                        )
                    } else if (onLongClick == null) {
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
                // 整行被拿去干别的事时，开关必须自己可点 —— 否则这一页就没法开/关它了
                onCheckedChange = if (switchHandlesIt) {
                    { if (enabled) onCheckedChange(it) }
                } else {
                    null
                },
                enabled = enabled,
            )
        }
    }
}
