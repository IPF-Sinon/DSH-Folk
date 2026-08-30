package me.bmax.apatch.ui.screen.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.bmax.apatch.APApplication
import me.bmax.apatch.R
import me.bmax.apatch.ui.component.CheckboxItem
import me.bmax.apatch.ui.component.ExpressiveCard
import me.bmax.apatch.ui.component.SplicedColumnGroup
import me.bmax.apatch.ui.component.ToggleSettingCard

@Composable
fun BehaviorSettingsContent(
    flat: Boolean = false,
    highlightKey: String? = null,
) {
    val prefs = APApplication.sharedPreferences

    var currentStyle by remember { mutableStateOf(prefs.getString("home_layout_style", "dsh")) }
    DisposableEffect(Unit) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (key == "home_layout_style") {
                currentStyle = sharedPreferences.getString("home_layout_style", "dsh")
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    SplicedColumnGroup(flat = flat, highlightKey = highlightKey) {

    item(key = "behavior_web_debugging") {
    var enableWebDebugging by remember { mutableStateOf(prefs.getBoolean("enable_web_debugging", false)) }
    ToggleSettingCard(
            flat = flat,
            icon = Icons.Filled.BugReport,
        title = stringResource(id = R.string.enable_web_debugging),
        description = stringResource(id = R.string.enable_web_debugging_summary),
        checked = enableWebDebugging,
        onCheckedChange = {
            enableWebDebugging = it
            prefs.edit().putBoolean("enable_web_debugging", it).apply()
        }
    )
    }

    item(key = "behavior_info_copy") {
        var infoCopyEnabled by remember { mutableStateOf(prefs.getBoolean("enable_info_copy", true)) }
        ToggleSettingCard(
            flat = flat,
            icon = Icons.Filled.ContentCopy,
            title = stringResource(id = R.string.settings_info_copy),
            description = stringResource(id = R.string.settings_info_copy_summary),
            checked = infoCopyEnabled,
            onCheckedChange = {
                infoCopyEnabled = it
                prefs.edit().putBoolean("enable_info_copy", it).apply()
            }
        )
    }

    item(key = "behavior_hide_fingerprint") {
        var hideFingerprint by remember { mutableStateOf(prefs.getBoolean("hide_fingerprint", false)) }
        ToggleSettingCard(
            flat = flat,
            icon = Icons.Filled.Fingerprint,
            title = stringResource(id = R.string.home_hide_fingerprint),
            description = stringResource(id = R.string.home_hide_fingerprint_summary),
            checked = hideFingerprint,
            onCheckedChange = {
                hideFingerprint = it
                prefs.edit().putBoolean("hide_fingerprint", it).apply()
            }
        )
    }

    item(key = "behavior_badge_count") {
        // DSH-Folk 只有一个角标：插件页的「可更新」计数。key 沿用 badge_apm 兼容旧配置。
        var enablePluginBadge by remember { mutableStateOf(prefs.getBoolean("badge_apm", true)) }
        var expanded by remember { mutableStateOf(false) }
        val rotationState by animateFloatAsState(
            targetValue = if (expanded) 180f else 0f,
            label = "ArrowRotation"
        )
        val badgeCountTitle = stringResource(id = R.string.enable_badge_count)
        val badgeCountSummary = stringResource(id = R.string.enable_badge_count_summary)

        ExpressiveCard(
            flat = flat,
            onClick = { expanded = !expanded }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Badge,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = badgeCountTitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = badgeCountSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(rotationState)
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(start = 16.dp)) {
                CheckboxItem(
                    icon = null,
                    title = stringResource(id = R.string.dsh_badge_plugin),
                    summary = null,
                    checked = enablePluginBadge,
                    onCheckedChange = {
                        enablePluginBadge = it
                        prefs.edit().putBoolean("badge_apm", it).apply()
                    }
                )
            }
        }
    }

    }
}
