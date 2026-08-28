package me.bmax.apatch.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ExpressiveCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    flat: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (LocalInsideSplicedGroup.current) {
        // Inside SplicedColumnGroup: skip card wrapper, parent provides container
        if (onClick != null) {
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(),
                        onClick = onClick,
                    ),
            ) {
                content()
            }
        } else {
            Box(modifier = modifier.fillMaxWidth()) {
                content()
            }
        }
        return
    }

    // Standalone: use Card/ElevatedCard with rounded corners
    val shape = RoundedCornerShape(32.dp)
    val colors = if (flat) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    } else {
        CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    }

    if (flat) {
        if (onClick != null) {
            Card(
                modifier = modifier.fillMaxWidth(),
                colors = colors,
                onClick = onClick,
                shape = shape,
                content = { content() },
            )
        } else {
            Card(
                modifier = modifier.fillMaxWidth(),
                colors = colors,
                shape = shape,
                content = { content() },
            )
        }
    } else {
        if (onClick != null) {
            ElevatedCard(
                modifier = modifier.fillMaxWidth(),
                colors = colors,
                onClick = onClick,
                shape = shape,
                content = { content() },
            )
        } else {
            ElevatedCard(
                modifier = modifier.fillMaxWidth(),
                colors = colors,
                shape = shape,
                content = { content() },
            )
        }
    }
}
