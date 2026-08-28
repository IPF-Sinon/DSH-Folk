package me.bmax.apatch.ui.screen

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.graphics.vector.ImageVector
import com.ramcosta.composedestinations.generated.destinations.DshPluginScreenDestination
import com.ramcosta.composedestinations.generated.destinations.DshTerminalScreenDestination
import com.ramcosta.composedestinations.generated.destinations.HomeScreenDestination
import com.ramcosta.composedestinations.generated.destinations.SettingScreenDestination
import com.ramcosta.composedestinations.spec.DirectionDestinationSpec
import me.bmax.apatch.R

/**
 * DSH-Folk 底栏：四页——主页 / 终端 / 插件 / 设置。
 *
 * - Terminal 是容器内的真 PTY 终端（DshTerminalScreenDestination），占原「超级用户」页位置。
 * - Plugin  复用 FolkPatch 模块页的视觉语言，内容换成 DSH 插件（DshPluginScreenDestination）。
 * - 原 KModule(KPM) 已删除；kPatch/aPatch 门控已去除（DSH-Folk 不打内核补丁）。
 *
 * [iconKey] 是自定义底栏图标的持久化键，**故意沿用 FolkPatch 的旧名**：
 * theme.json 的 navIcons 段以这些名字存图（Terminal 占原「超级用户」页的位置，
 * Plugin 占原 APM 模块页的位置），换成枚举名会让用户已有的 theme.json 失效。
 */
enum class BottomBarDestination(
    val direction: DirectionDestinationSpec,
    @param:StringRes val label: Int,
    val iconSelected: ImageVector,
    val iconNotSelected: ImageVector,
    val iconKey: String,
) {
    Home(
        HomeScreenDestination,
        R.string.home,
        Icons.Filled.Home,
        Icons.Outlined.Home,
        "Home",
    ),
    Terminal(
        DshTerminalScreenDestination,
        R.string.dsh_terminal,
        Icons.Filled.Terminal,
        Icons.Outlined.Terminal,
        "SuperUser",
    ),
    Plugin(
        DshPluginScreenDestination,
        R.string.dsh_plugins,
        Icons.Filled.Extension,
        Icons.Outlined.Extension,
        "AModule",
    ),
    Settings(
        SettingScreenDestination,
        R.string.settings,
        Icons.Filled.Settings,
        Icons.Outlined.Settings,
        "Settings",
    )
}
