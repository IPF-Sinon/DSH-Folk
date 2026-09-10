package me.bmax.apatch.ui.screen.settings

import androidx.compose.runtime.Composable
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator

/** 安全页同时承载权限控制；生物识别项目由 SecuritySettingsContent 按设备能力决定是否显示。 */
@Destination<RootGraph>
@Composable
fun SecuritySettingsScreen(navigator: DestinationsNavigator, highlightKey: String? = null) {
    DshSettingsScreen(navigator, highlightKey, permissionOnly = true)
}
