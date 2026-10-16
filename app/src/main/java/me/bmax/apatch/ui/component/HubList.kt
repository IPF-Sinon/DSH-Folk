package me.bmax.apatch.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 「入口页」那套行：一张卡片里若干行，每行 = 图标 + 标题 + 副标题 + 右箭头。
 *
 * 权限管理的 hub（`PermissionHubScreen`）与功能页的分类入口共用这一份 —— 两页都是
 * 「先选一类、再进去看内容」，长得不一样用户就得重新认一遍。
 */
@Composable
internal fun HubCard(content: @Composable () -> Unit) {
    // 两侧留 16dp：与 [SplicedColumnGroup] 同一套边距。不加的话卡片会顶到屏幕两边，
    // 而同一页上面的说明文字又是缩进的 —— 看起来就像卡片「连到边框上了」（用户报的就是这个）。
    // 放在这里而不是各调用点：两个入口页共用这一份，边距也只能有一处。
    ExpressiveCard(modifier = Modifier.padding(horizontal = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { content() }
    }
}

/** 入口页上的一行：整行可点，右侧箭头明示「点进去还有一层」。 */
@Composable
internal fun HubRow(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 搜索结果的一行：只有标题 + 副标题。
 *
 * 不带图标与右箭头是有意的：它表示「某一类里的某一项」，不是入口页上的那个分类本身 ——
 * 长得跟 [HubRow] 一样的话，用户会以为自己搜出来的是另一个分类。
 */
@Composable
internal fun HubHitRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
