package me.bmax.apatch.ui.component

import androidx.compose.ui.geometry.Offset
import kotlin.math.roundToInt

/**
 * 编辑页的坐标换算只有这一处。
 *
 * 为什么单列一个对象：之前「dp/px/网格」三种换算散在画布、卡片偏移、宿主写回三处，还混着
 * `Int` 截断（拖动感觉「有点快」、位置回弹都来自这里）。现在只有两条纯函数，门禁里有数值自测
 * （density=2.75、手指 110px → 40dp → 吸附 36dp），改公式会立刻被抓住。
 */
internal object WorkflowGeometry {
    /** 网格步长：与点击助手的 12dp 网格一致，也是落盘坐标的吸附粒度。 */
    const val GRID_DP = 12

    /** 手指位移（像素）→ dp。密度非法时按 1 处理，宁可慢一点也不能放大位移。 */
    fun pxToDp(px: Float, density: Float): Float = if (density > 0f) px / density else px

    /** 吸附到 12dp 网格，返回整数 dp（落盘坐标是 Int）。 */
    fun snapToGridDp(dp: Float): Int = (dp / GRID_DP).roundToInt() * GRID_DP
}

/**
 * 蓝图视口：世界坐标（落盘的那套 dp）与屏幕之间的唯一变换。
 *
 * ## 变换定义
 *
 * 屏幕像素 = 世界 dp × density × scale + pan
 *
 * 内容层就是按这个顺序画的（`graphicsLayer` 先 `transformOrigin=(0,0)` 缩放、再平移），
 * 所以 [toWorldDp] 是它的**逆变换**，两者必须永远成对修改。
 *
 * ## 为什么必须集中
 *
 * 缩放一旦存在，「手指在哪」与「节点该落在哪」就不再有同一个数值：漏掉逆变换的地方会表现为
 * 「放大后拖动变快」「缩小后新节点跑到视野外」「拉线接不上针脚」—— 这些 bug 的共同点都是
 * 某个调用点自己算了一遍坐标。所以落点、拖动增量、针脚换算全部走这一个对象。
 *
 * ## 视口不落盘
 *
 * 只持久化节点坐标：视口是「这一次编辑看到哪儿」，跨设备恢复一个旧视口只会让人对着一片空白。
 * 打开时由宿主 fit 到内容（见 [fit]）。
 */
internal data class WorkflowViewport(
    val pan: Offset = Offset.Zero,
    val scale: Float = 1f,
) {
    /** 窗口像素 → 世界 dp（落盘与摆位用的那一套）。 */
    fun toWorldDp(windowPx: Offset, originPx: Offset, density: Float): Offset {
        val safeDensity = if (density > 0f) density else 1f
        val safeScale = if (scale > 0f) scale else 1f
        return Offset(
            (windowPx.x - originPx.x - pan.x) / safeScale / safeDensity,
            (windowPx.y - originPx.y - pan.y) / safeScale / safeDensity,
        )
    }

    /**
     * 拖动位移（**内容层局部像素**）→ 世界 dp 增量。
     *
     * 这里**不除缩放**，原因是 Compose 的命中测试会先过 `graphicsLayer`：内容层带着
     * scale/translation 时，子节点收到的指针坐标与拖动增量**已经**是反变换后的局部值
     * （缩到 2 倍时点 (200,200) 得到的是 (100,100)）。再除一次 scale，缩放后拖动就会
     * 只有一半跟手。真正需要逆变换的是**窗口坐标**（拉线落点、针脚矩形），见 [toWorldDp]。
     */
    fun dragDeltaDp(dxPx: Float, dyPx: Float, density: Float): Offset {
        val safeDensity = if (density > 0f) density else 1f
        return Offset(dxPx / safeDensity, dyPx / safeDensity)
    }

    /**
     * 以 [centroid]（内容层局部像素）为中心缩放后的新 pan。
     *
     * 只改 scale 会把画面朝左上角「抽」，手指按住的那个点在缩放过程中会跑掉。围绕手势中心
     * 缩放的判据是：那个点缩放前后都贴在手指下面 —— 解出来就是下面这个式子。
     */
    fun zoomAround(centroid: Offset, zoomChange: Float, panChange: Offset): WorkflowViewport {
        val nextScale = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
        val ratio = if (scale > 0f) nextScale / scale else 1f
        return WorkflowViewport(
            pan = (pan - centroid) * ratio + centroid + panChange,
            scale = nextScale,
        )
    }

    companion object {
        /** 缩放范围与点击助手的 CardLayoutView 一致（0.2–2.0）。 */
        const val MIN_SCALE = 0.2f
        const val MAX_SCALE = 2.0f

        /** 一颗节点都没有时的兜底：不缩放、不偏移。 */
        fun fitTo(nodes: Collection<Pair<Float, Float>>, viewportPx: Offset, density: Float): WorkflowViewport {
            if (nodes.isEmpty() || viewportPx.x <= 0f || viewportPx.y <= 0f) return WorkflowViewport()
            val safeDensity = if (density > 0f) density else 1f
            val minX = nodes.minOf { it.first }
            val minY = nodes.minOf { it.second }
            val maxX = nodes.maxOf { it.first } + WorkflowNodeCardWidth.value
            val maxY = nodes.maxOf { it.second } + 120f
            val contentW = (maxX - minX) * safeDensity
            val contentH = (maxY - minY) * safeDensity
            if (contentW <= 0f || contentH <= 0f) return WorkflowViewport()
            val scale = minOf(viewportPx.x / contentW, viewportPx.y / contentH, 1f)
                .coerceIn(MIN_SCALE, MAX_SCALE)
            // 居中：内容宽高按 scale 折算后，两侧余量各一半。
            val panX = (viewportPx.x - contentW * scale) / 2f - minX * safeDensity * scale
            val panY = (viewportPx.y - contentH * scale) / 2f - minY * safeDensity * scale
            return WorkflowViewport(pan = Offset(panX, panY), scale = scale)
        }
    }
}
