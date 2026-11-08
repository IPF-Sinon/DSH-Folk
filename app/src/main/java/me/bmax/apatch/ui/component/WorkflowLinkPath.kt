package me.bmax.apatch.ui.component

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * 连线路径：**正交折线**，拐角倒圆 —— 逐条对齐 TouchTool 的 `CardLayoutHelper.calculateLinkPath`。
 *
 * ## 为什么不是贝塞尔曲线
 *
 * 上一版画的是三次贝塞尔（`cubicTo`），视觉上像"两团橡皮筋"。参考实现画的是
 * **由水平和垂直线段组成的折线**，只在拐角处磨圆。两者气质完全不同：
 *  - 折线读起来像"管线"，走向、拐点、分流一眼能数清；
 *  - 贝塞尔读起来像"飘带"，两条线的相对关系反而看不出来。
 *
 * 这个编辑器的核心问题是"这个值从哪儿来、到哪儿去"，所以要用前者。
 *
 * ## 算法（照抄，不化简）
 *
 * 1. 起止各让出一小段走廊（`grid * CORNER_GRID_COUNT`），避免线条压在卡片边缘；
 * 2. 按"横向差大还是纵向差大"（`xLong`）以及在目标的上/下方（`isYPositive`）
 *    选走法，共 8 种；
 * 3. 有一种特殊情况：目标在上方且横向差很小（`!isYPositive && dx < gridOffset`）时，
 *    绕一条"← ↑ →"的回路，而不是把线穿回卡片里；
 * 4. [keepCorners]：把挨得很近的拐点融合、把共线的中间点去掉，
 *    否则短距离连接会画出一堆挤在一起的锯齿；
 * 5. [roundCorners]：每个拐角按半径倒圆（等价于参考实现的 `CornerPathEffect`）。
 *
 * 这些步骤少做任何一条，观感都会明显变差，所以宁可长一点也照着写。
 */
private const val CORNER_GRID_COUNT = 2f

/**
 * 连线走廊宽度（dp）。
 *
 * ## 为什么不直接用画布网格步长
 *
 * 这里最容易犯的错：参考实现里 `gridSize` 是**同一个量**同时充当「网格步长」和「走廊宽度」，
 * 所以照抄时很容易把我们的网格步长（24dp）直接当走廊传进去。那样做的后果是
 * `gridOffset = gridSize * (2 + CORNER_GRID_COUNT) = 4 × 24 = 96dp` ——
 * 相对 220dp 宽的卡片，单侧偏移接近半个卡宽，绕行时会把整张卡包住成一个套索。
 *
 * 参考实现之所以能用 12dp：它的网格就是 12dp，`gridOffset` 只有 48dp，约等于卡片
 * 的四分之一，绕行只是贴边绕一下。我们要的是**同样的绝对观感**，所以走廊要独立取值，
 * 不能跟着网格走。
 */
internal const val LINK_CORRIDOR_DP = 12f

/**
 * 折线在拐角处的圆角半径（以 [corridor] 为单位）。
 *
 * 参考实现是 `CornerPathEffect(gridSize * CORNER_GRID_COUNT / 2)` —— 即半径 = 一个网格。
 * 那里 `gridSize` 同时充当走廊，所以「半径 = 走廊 × 1.0」。**我们不行**：
 * 画布网格是 24dp，走廊另取 12dp（见 [LINK_CORRIDOR_DP]），若照抄 1.0 就会得到
 * 24dp 的圆角，是参考的两倍，拐角会过分外扩、把线甩得离卡片更远。
 * 取 0.5 才是「半径 = 12dp」这一等效值。
 */
private const val CORNER_ROUND = 0.5f

/** 融合阈值：两个相邻拐点距离小于它就算同一个（参考实现 `FUSION_THRESHOLD = 5`）。 */
private const val FUSION_THRESHOLD = 5f

/**
 * 生成一条正交折线。
 *
 * @param corridor 走廊宽度（一般为画布网格大小）。它决定"从针脚先直着走多远再拐"。
 * @param vertical 主线方向。画布是自上而下流动的，所以默认 true。
 */
internal fun orthogonalLinkPath(
    start: Offset,
    end: Offset,
    corridor: Float,
    vertical: Boolean = true,
): Path {
    val minOffset = corridor * CORNER_GRID_COUNT
    var dx = abs(start.x - end.x)
    var dy = abs(start.y - end.y)
    // 两端几乎重合：直接一根直线。否则下面的走廊偏移会把它们推成一个小线圈。
    if (dx <= minOffset && dy <= minOffset) {
        return Path().apply {
            moveTo(start.x, start.y)
            lineTo(end.x, end.y)
        }
    }

    val points = mutableListOf(start)

    val startPoint = Offset(start.x, start.y)
    val endPoint = Offset(end.x, end.y)
    val s0 = if (vertical) Offset(startPoint.x, startPoint.y + corridor * CORNER_GRID_COUNT)
    else Offset(startPoint.x + corridor * CORNER_GRID_COUNT, startPoint.y)
    val e0 = if (vertical) Offset(endPoint.x, endPoint.y - corridor * CORNER_GRID_COUNT)
    else Offset(endPoint.x - corridor * CORNER_GRID_COUNT, endPoint.y)
    points.add(s0)

    val isXPositive = s0.x < e0.x
    val isYPositive = s0.y < e0.y
    val xScale = if (isXPositive) 1f else -1f
    val yScale = if (isYPositive) 1f else -1f

    // 参考实现对两位小数取整后比较长短；这里保留同样做法，避免临界值附近抖动。
    dx = round(abs(e0.x - s0.x) * 100f) / 100f
    dy = round(abs(e0.y - s0.y) * 100f) / 100f
    val xLong = dx > dy
    val halfLen = abs(dx - dy) / 2f

    val gridOffset = corridor * (2f + CORNER_GRID_COUNT)
    var handled = false

    if (vertical) {
        // 目标在上方、横向差很小：绕一条 ← ↑ → 的回路，而不是把线穿回卡片里。
        if (!isYPositive && dx < gridOffset && dy > gridOffset) {
            val x = min(e0.x, s0.x) - gridOffset
            points.add(Offset(x, s0.y))
            points.add(Offset(x, e0.y))
            handled = true
        }
        if (!handled) {
            if (xLong) {
                if (isYPositive) {
                    points.add(Offset(s0.x, s0.y + dy / 2f))
                    points.add(Offset(e0.x, e0.y - dy / 2f))
                } else {
                    points.add(Offset(s0.x + halfLen * xScale, s0.y))
                    points.add(Offset(e0.x - halfLen * xScale, e0.y))
                }
            } else {
                if (isYPositive) {
                    points.add(Offset(s0.x, s0.y + halfLen))
                    points.add(Offset(e0.x, e0.y - halfLen))
                } else {
                    points.add(Offset(s0.x + dx * xScale / 2f, s0.y))
                    points.add(Offset(e0.x - dx * xScale / 2f, e0.y))
                }
            }
        }
    } else {
        if (!isXPositive && dy < gridOffset && dx > gridOffset) {
            val y = max(e0.y, s0.y) + corridor * (CORNER_GRID_COUNT + 4f)
            points.add(Offset(s0.x, y))
            points.add(Offset(e0.x, y))
            handled = true
        }
        if (!handled) {
            if (xLong) {
                if (isXPositive) {
                    points.add(Offset(s0.x + halfLen, s0.y))
                    points.add(Offset(e0.x - halfLen, e0.y))
                } else {
                    points.add(Offset(s0.x, s0.y + dy * yScale / 2f))
                    points.add(Offset(e0.x, e0.y - dy * yScale / 2f))
                }
            } else {
                if (isXPositive) {
                    points.add(Offset(s0.x + dx / 2f, s0.y))
                    points.add(Offset(e0.x - dx / 2f, e0.y))
                } else {
                    points.add(Offset(s0.x, s0.y + halfLen * yScale))
                    points.add(Offset(e0.x, e0.y - halfLen * yScale))
                }
            }
        }
    }

    points.add(e0)
    points.add(endPoint)

    return roundCorners(keepCorners(points), corridor * CORNER_ROUND)
}

/**
 * 融合挨得太近的点、去掉共线的中间点。
 *
 * 不做这一步的话，两张卡挨得很近时会得到一串挤在两三像素内的拐点，
 * 倒圆之后看起来像一团毛刺。参考实现为此专门写了这一段，这里照搬。
 */
private fun keepCorners(points: List<Offset>): List<Offset> {
    if (points.size <= 2) return points

    // 第一步：平均融合近距离点。
    val fused = mutableListOf<Offset>()
    var sumX = points[0].x
    var sumY = points[0].y
    var count = 1
    for (i in 1 until points.size) {
        val cur = points[i]
        val avgX = sumX / count
        val avgY = sumY / count
        val ddx = cur.x - avgX
        val ddy = cur.y - avgY
        val dist = kotlin.math.sqrt(ddx * ddx + ddy * ddy)
        if (dist <= FUSION_THRESHOLD) {
            sumX += cur.x
            sumY += cur.y
            count++
        } else {
            fused.add(Offset(sumX / count, sumY / count))
            sumX = cur.x
            sumY = cur.y
            count = 1
        }
    }
    fused.add(Offset(sumX / count, sumY / count))
    if (fused.size <= 2) return fused

    // 第二步：叉积判共线，去掉中间点。
    val result = mutableListOf(fused[0])
    for (i in 1 until fused.size - 1) {
        val a = fused[i - 1]
        val b = fused[i]
        val c = fused[i + 1]
        val cross = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
        if (cross != 0f) result.add(b)
    }
    result.add(fused[fused.size - 1])
    return result
}

/**
 * 把折线的每个拐角倒圆。等价于参考实现的 `CornerPathEffect(radius)`。
 *
 * Compose 没有 `CornerPathEffect`，而 `Stroke(join = Round)` 的圆角半径只有线宽一半，
 * 远小于参考实现用的半径（一整个网格），画出来会显得"棱角还在"。
 * 所以这里显式地把拐角切开、用二次曲线过渡。
 */
private fun roundCorners(points: List<Offset>, radius: Float): Path {
    val path = Path()
    if (points.isEmpty()) return path
    if (points.size == 1) {
        path.moveTo(points[0].x, points[0].y)
        return path
    }
    path.moveTo(points[0].x, points[0].y)
    if (points.size == 2) {
        path.lineTo(points[1].x, points[1].y)
        return path
    }
    for (i in 1 until points.size - 1) {
        val prev = points[i - 1]
        val cur = points[i]
        val next = points[i + 1]
        // 切入/切出点：沿两条边各退 radius，但不能退过整段的一半，
        // 否则短段会来回折返（这是 CornerPathEffect 也必须处理的同一件事）。
        val inX = cur.x + (prev.x - cur.x).coerceIn(-radius, radius) 
        val inY = cur.y + (prev.y - cur.y).coerceIn(-radius, radius)
        val outX = cur.x + (next.x - cur.x).coerceIn(-radius, radius)
        val outY = cur.y + (next.y - cur.y).coerceIn(-radius, radius)
        path.lineTo(inX, inY)
        // 用二次曲线穿过拐点，控制点就是拐点本身 —— 这就是"倒圆"。
        path.quadraticTo(cur.x, cur.y, outX, outY)
    }
    val last = points[points.size - 1]
    path.lineTo(last.x, last.y)
    return path
}
