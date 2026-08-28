package me.bmax.apatch.ui.component.chart

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path

internal data class ChartPoint(val x: Float, val y: Float)

internal fun normalizePoints(dataPoints: List<Float>): List<ChartPoint> {
    if (dataPoints.isEmpty()) return emptyList()
    val minY = dataPoints.minOrNull() ?: 0f
    val maxY = dataPoints.maxOrNull() ?: 0f
    val yRange = (maxY - minY).coerceAtLeast(1f)
    return dataPoints.mapIndexed { index, value ->
        val x = if (dataPoints.size <= 1) 0f else index.toFloat() / (dataPoints.size - 1)
        val y = (value - minY) / yRange
        ChartPoint(x, y)
    }
}

internal fun interpolatePoints(
    prev: List<ChartPoint>,
    current: List<ChartPoint>,
    progress: Float
): List<ChartPoint> {
    if (current.isEmpty()) return emptyList()
    if (prev.isEmpty()) return current
    return current.mapIndexed { index, cur ->
        if (index < prev.size) {
            ChartPoint(
                x = prev[index].x + (cur.x - prev[index].x) * progress,
                y = prev[index].y + (cur.y - prev[index].y) * progress
            )
        } else {
            cur
        }
    }
}

internal fun buildBezierPath(
    points: List<ChartPoint>,
    size: Size,
    heightFraction: Float = 1f
): Path {
    if (points.isEmpty()) return Path()
    val chartHeight = size.height * heightFraction
    val path = Path()
    path.moveTo(
        points[0].x * size.width,
        chartHeight - points[0].y * chartHeight
    )
    for (i in 1 until points.size - 1) {
        val current = points[i]
        val next = points[i + 1]
        val midX = (current.x + next.x) / 2f
        val midY = (current.y + next.y) / 2f
        path.quadraticTo(
            current.x * size.width,
            chartHeight - current.y * chartHeight,
            midX * size.width,
            chartHeight - midY * chartHeight
        )
    }
    path.lineTo(
        points.last().x * size.width,
        chartHeight - points.last().y * chartHeight
    )
    return path
}

internal fun buildFillPath(linePath: Path, size: Size): Path {
    val fillPath = Path()
    fillPath.addPath(linePath)
    fillPath.lineTo(size.width, size.height)
    fillPath.lineTo(0f, size.height)
    fillPath.close()
    return fillPath
}
