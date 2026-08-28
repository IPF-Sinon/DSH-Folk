package me.bmax.apatch.ui.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

@Composable
fun SegmentedControl(
    items: List<String>,
    selectedIndex: Int,
    useFixedWidth: Boolean = false,
    itemWidth: Dp = 120.dp,
    cornerRadius: Int = 50,
    color: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    onItemSelection: (selectedItemIndex: Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(color, shape = RoundedCornerShape(cornerRadius))
            .border(1.dp, color.copy(alpha = 0.1f), RoundedCornerShape(cornerRadius))
            .clip(RoundedCornerShape(cornerRadius)),
        horizontalArrangement = Arrangement.Center
    ) {
        items.forEachIndexed { index, item ->
            val isSelected = selectedIndex == index
            
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                    )
                    .clickable {
                        onItemSelection(index)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = item,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else contentColor,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
