package me.bmax.apatch.ui.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import kotlin.math.cos
import kotlin.math.sin
import me.bmax.apatch.R

/** 主按钮（右下角那颗 ＋/✕）的直径。 */
private val ADD_FAB_SIZE = 56.dp

/** 环绕的小按钮直径，以及它们圆心到主按钮圆心的距离。 */
private val ADD_MINI_SIZE = 40.dp
private val ADD_RADIUS = 72.dp

/**
 * 「＋ 环绕菜单」里的一颗：图标、无障碍名（资源 id），以及点下去干什么。
 *
 * 存资源 id 而不是现成的字符串，是为了让调用方不必是 `@Composable` —— 入口清单因此可以写成
 * 普通的 `listOf(...)`，不必再抽一层。
 */
internal class AddMenuEntry(
    val icon: ImageVector,
    val labelRes: Int,
    val onClick: () -> Unit,
)

/**
 * 右下角的「＋」：点开后绕着它朝屏幕里侧铺开 [entries]，＋ 自己变成 ✕（再点收回）。
 *
 * 为什么做成一个共用组件：现在有三处在用（用户脚本页、插件首页的脚本组、插件首页的插件组）。
 * 三份各写一遍的话，落点、动效、以及「展开时读屏该念什么」很快就会各长各的。
 *
 * 动画与悬浮球那套同形：progress 0 = 全部叠在主按钮圆心，1 = 各自到位；位移、透明度、缩放
 * 都由它插值出来，所以是「从 ＋ 里长出去」而不是原地淡入。**不过冲** —— 这一格的边正好压在
 * 落点上，弹簧一回弹就会被 Scaffold 的边裁掉。
 */
@Composable
internal fun AddMenuFab(entries: List<AddMenuEntry>) {
    var expanded by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "addMenu",
    )

    // 主按钮落在这一格的右下角（FAB 槽本身就是右下角对齐），副按钮以它的圆心为圆心铺开。
    // 边长 = 半径 + 两颗的半径之和：正好等于最远那颗的外沿 —— 一格装得下，也不多占地方。
    val box = ADD_RADIUS + ADD_MINI_SIZE / 2 + ADD_FAB_SIZE / 2
    val cx = box - ADD_FAB_SIZE / 2
    val cy = box - ADD_FAB_SIZE / 2
    val fromX = cx - ADD_MINI_SIZE / 2
    val fromY = cy - ADD_MINI_SIZE / 2

    // 屏幕右下角这个位置只有左上那 90° 有地方：从正左（180°）扫到正上（270°）。
    // 两颗就是两端，三颗均分（180 / 225 / 270，与只有用户脚本页时那版一致）；一颗放中间。
    fun slot(index: Int): Pair<Dp, Dp> {
        val f = if (entries.size <= 1) 0.5f else index.toFloat() / (entries.size - 1)
        val rad = Math.toRadians(180.0 + 90.0 * f)
        return cx + ADD_RADIUS * cos(rad).toFloat() - ADD_MINI_SIZE / 2 to
            cy + ADD_RADIUS * sin(rad).toFloat() - ADD_MINI_SIZE / 2
    }

    Box(Modifier.size(box)) {
        entries.forEachIndexed { i, entry ->
            val at = slot(i)
            AddMenuButton(
                progress = progress,
                fromX = fromX,
                fromY = fromY,
                toX = at.first,
                toY = at.second,
                onClick = { expanded = false; entry.onClick() },
            ) {
                Icon(entry.icon, contentDescription = stringResource(entry.labelRes))
            }
        }
        FloatingActionButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.align(Alignment.BottomEnd).size(ADD_FAB_SIZE),
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(
                if (expanded) Icons.Filled.Close else Icons.Filled.Add,
                // 展开时这一颗的语义是「取消」：图标与读屏名一起换，否则读屏还会念「添加」，
                // 而按下去其实是收回菜单。
                contentDescription = stringResource(
                    if (expanded) R.string.cancel else R.string.dsh_add_menu
                ),
            )
        }
    }
}

/**
 * 环绕 ＋ 的一颗小按钮：起止点由 [AddMenuFab] 算好传进来，[progress] 与主按钮共用 —— 所以
 * 几颗是一起长出去、一起收回来的。
 */
@Composable
private fun AddMenuButton(
    progress: Float,
    fromX: Dp,
    fromY: Dp,
    toX: Dp,
    toY: Dp,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    // 进度为 0 时干脆不画：否则几颗透明按钮叠在主按钮上，会把「点 ＋」这一下抢走。
    if (progress <= 0.01f) return
    val scale = 0.4f + 0.6f * progress
    FloatingActionButton(
        onClick = onClick,
        modifier = Modifier
            .offset(x = lerp(fromX, toX, progress), y = lerp(fromY, toY, progress))
            .size(ADD_MINI_SIZE)
            .graphicsLayer {
                alpha = progress
                scaleX = scale
                scaleY = scale
            },
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        content()
    }
}
