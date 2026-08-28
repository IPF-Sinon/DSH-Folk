package me.bmax.apatch.ui.component.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private object ChartPalette {
    val KPM = Color(0xFF6366F1)
    val APM = Color(0xFF3DDC84)
    val SU  = Color(0xFFFF9F43)
}

data class PieSliceData(
    val label: String,
    val value: Float,
    val color: Color
)

@Composable
fun ModulePieChart(
    data: List<PieSliceData>,
    modifier: Modifier = Modifier,
    centerLabel: String? = null,
    donutMode: Boolean = true
) {
    val colors = MaterialTheme.colorScheme

    Box(modifier = modifier.size(140.dp)) {
        val total = data.filter { it.value > 0f }.sumOf { it.value.toDouble() }.toFloat()
        val activeSlices = data.filter { it.value > 0f }
        val sliceGap = if (activeSlices.size > 1) 2f else 0f
        val totalGap = sliceGap * activeSlices.size
        val availableAngle = 360f - totalGap

        Canvas(modifier = Modifier.size(140.dp).align(Alignment.Center)) {
            val canvasSize = size.minDimension
            val strokeWidth = if (donutMode) 20.dp.toPx() else 30.dp.toPx()
            val radius = (canvasSize - strokeWidth) / 2
            val center = Offset(canvasSize / 2, canvasSize / 2)

            var startAngle = -90f

            data.forEach { slice ->
                if (slice.value <= 0f) return@forEach

                val sweepAngle = if (total > 0f) {
                    (slice.value / total) * availableAngle
                } else {
                    360f
                }

                drawArc(
                    color = slice.color,
                    startAngle = startAngle,
                    sweepAngle = sweepAngle,
                    useCenter = !donutMode,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = if (donutMode) Stroke(width = strokeWidth, cap = StrokeCap.Butt) else Fill
                )

                startAngle += sweepAngle + sliceGap
            }
        }

        if (donutMode && centerLabel != null) {
            Text(
                text = centerLabel,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface
                ),
                modifier = Modifier.align(Alignment.Center),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun rememberPieSliceDataFromCounts(
    kernelModules: Int = 0,
    apmModules: Int = 0,
    superusers: Int = 0,
    kpmLabel: String = "KPM",
    apmLabel: String = "APM",
    suLabel: String = "SU"
): List<PieSliceData> {
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)

    return remember(kernelModules, apmModules, superusers, kpmLabel, apmLabel, suLabel) {
        listOf(
            PieSliceData(
                label = kpmLabel,
                value = kernelModules.toFloat(),
                color = if (kernelModules > 0) ChartPalette.KPM else emptyColor
            ),
            PieSliceData(
                label = apmLabel,
                value = apmModules.toFloat(),
                color = if (apmModules > 0) ChartPalette.APM else emptyColor
            ),
            PieSliceData(
                label = suLabel,
                value = superusers.toFloat(),
                color = if (superusers > 0) ChartPalette.SU else emptyColor
            )
        )
    }
}
