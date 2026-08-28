package me.bmax.apatch.ui.component.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

data class StoragePartition(
    val label: String,
    val usedPercent: Float,
    val color: Color
)

@Composable
fun StorageColumnChart(
    partitions: List<StoragePartition>,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = colors.surface,
                shape = RoundedCornerShape(16.dp)
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Storage Partitions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )

            partitions.forEach { partition ->
                PartitionBar(partition)
            }
        }
    }
}

@Composable
private fun PartitionBar(
    partition: StoragePartition,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = partition.label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.width(64.dp)
        )

        Spacer(Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .height(20.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(colors.surfaceContainerHighest)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(partition.usedPercent.coerceIn(0f, 100f) / 100f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(partition.color)
            )
        }

        Spacer(Modifier.width(8.dp))

        Text(
            text = "${partition.usedPercent.roundToInt()}%",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
            modifier = Modifier.width(40.dp)
        )
    }
}
