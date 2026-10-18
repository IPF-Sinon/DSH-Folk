package me.bmax.apatch.ui.component

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
