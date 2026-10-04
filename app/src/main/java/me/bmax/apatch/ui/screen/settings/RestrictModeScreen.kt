package me.bmax.apatch.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshHostPrompt
import me.bmax.apatch.dsh.DshNativeBridge
import me.bmax.apatch.dsh.PrivPolicy
import me.bmax.apatch.ui.component.SearchAppBar

/**
 * 「限制模式」：一个开关 + 两张清单，**一处**管理「还要不要再问」。
 *
 * ## 为什么要这个页面（旧模型哪里不对）
 *
 * 旧模型是「全局严格程度 + 按能力的信任名单」。两者与档位（允不允许做）重叠，组合出来的
 * 效果解释不清：真机上先后出现过「加进不再逐条确认也照样弹」和「这个开关没用」两种反馈。
 * 更要紧的是旧模型的 per-call 闸**只覆盖 shell / 虚拟屏 / 无障碍**三个能力，短信、相机、
 * 麦克风、定位在能力启用之后再没被问过 —— 名单对它们本来就无事可做。
 *
 * 现在：
 * - **档位**（每张能力卡上的开关）决定「允不允许做」，首次调用必然弹一次；
 * - **限制模式关**（默认）= 能力启用之后不再问；**开** = 下面能力清单里的每次都问；
 * - **危险操作清单**命中就永远问一次，与开关无关；条目可删（删掉即沉默）、可增补、
 *   可恢复默认 —— 这张表替代了以前写死的"危险档"。
 *
 * 名单只在这里管理：弹窗上没有「不再逐条确认」那种快捷出口。挂在弹窗上等于第二个入口，
 * 用户就看不到自己一共免掉了哪几项 —— 上一版把开关从能力卡挪到这里，正是同一个理由。
 */
@Destination<RootGraph>
@Composable
fun RestrictModeScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var restrictMode by remember { mutableStateOf(PrivPolicy.restrictMode(context)) }
    var restricted by remember { mutableStateOf(PrivPolicy.restrictedCaps(context)) }
    var danger by remember { mutableStateOf(PrivPolicy.activeDanger(context)) }
    var disabledBuiltin by remember { mutableStateOf(PrivPolicy.disabledBuiltinDanger(context)) }
    var draft by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<Int?>(null) }

    fun refreshFacts() = DshHostPrompt.writeFacts(context.applicationContext)

    val q = query.trim().lowercase()
    // 按分类列，而不是一条平铺的大列表：用户记的是「通知」「剪贴板」，而"它在哪一类"
    // 正是他要确认的事（同 [PermissionHubScreen.searchHits] 的三层匹配）。
    val groups = remember(q) {
        CapGroup.entries.mapNotNull { group ->
            val name = context.getString(group.titleRes)
            val caps = group.caps.filter { cap ->
                q.isEmpty() ||
                    context.getString(nativeCapTitleRes(cap)).lowercase().contains(q) ||
                    context.getString(nativeCapSummaryRes(cap)).lowercase().contains(q) ||
                    name.lowercase().contains(q)
            }
            if (caps.isEmpty()) null else group to caps
        }
    }

    Scaffold(
        topBar = {
            SearchAppBar(
                title = { Text(stringResource(R.string.dsh_priv_restrict_title)) },
                searchText = query,
                onSearchTextChange = { query = it },
                onClearClick = { query = "" },
                onBackClick = { navigator.popBackStack() },
                // 与权限总页一致：进来先看开关与清单，别一进门就被键盘盖住正文
                startInSearchMode = false,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── 开关：唯一决定"要不要再问"的全局项 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            if (restrictMode) R.string.dsh_priv_restrict_on
                            else R.string.dsh_priv_restrict_off
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.dsh_priv_restrict_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = restrictMode,
                    onCheckedChange = { want ->
                        restrictMode = want
                        PrivPolicy.setRestrictMode(context.applicationContext, want)
                        // 事实要立刻刷新：agent 靠提示词里的字知道"这条调用还会不会弹窗"，
                        // 漏了这一步它会一直按旧假设行事。
                        refreshFacts()
                    },
                )
            }

            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            Text(
                text = stringResource(R.string.dsh_priv_restrict_caps_header),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp),
            )
            Text(
                text = stringResource(R.string.dsh_priv_restrict_caps_default),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (groups.isEmpty()) {
                Text(
                    text = stringResource(R.string.dsh_priv_restrict_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            for ((group, caps) in groups) {
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Text(
                    text = stringResource(group.titleRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
                )
                for (cap in caps) {
                    RestrictRow(
                        cap = cap,
                        restricted = cap.id in restricted,
                        onToggle = { want ->
                            PrivPolicy.setCapRestricted(context.applicationContext, cap, want)
                            restricted = PrivPolicy.restrictedCaps(context)
                            refreshFacts()
                        },
                    )
                }
            }
            TextButton(
                onClick = {
                    PrivPolicy.resetRestrictedCaps(context.applicationContext)
                    restricted = PrivPolicy.restrictedCaps(context)
                    refreshFacts()
                },
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text(stringResource(R.string.dsh_priv_restrict_caps_reset))
            }

            // ── 危险操作清单 ──
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            Text(
                text = stringResource(R.string.dsh_priv_danger_header),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp),
            )
            Text(
                text = stringResource(R.string.dsh_priv_danger_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(6.dp))
            for (entry in danger) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = entry,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (entry !in PrivPolicy.BUILTIN_DANGER) {
                        Text(
                            text = stringResource(R.string.dsh_priv_danger_added_tag),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = {
                        PrivPolicy.removeDangerEntry(context.applicationContext, entry)
                        danger = PrivPolicy.activeDanger(context)
                        disabledBuiltin = PrivPolicy.disabledBuiltinDanger(context)
                        // 删掉内置条目要能"恢复默认"，所以被删的集合也要重读
                        refreshFacts()
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.dsh_priv_danger_remove),
                        )
                    }
                }
            }
            // 增补：只收"命令"或"命令 子命令"两段。不收正则 —— 一个写错的正则会让某条命令
            // **静默免问**，那比"不支持正则"危险得多。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        addError = null
                    },
                    label = { Text(stringResource(R.string.dsh_priv_danger_add_label)) },
                    singleLine = true,
                    isError = addError != null,
                    // 支持文本只在这条输入不合法时出现；类型上要显式包一层 composable lambda
                    supportingText = if (addError == null) {
                        null
                    } else {
                        { Text(stringResource(addError!!)) }
                    },
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = {
                    val raw = draft.trim()
                    addError = when {
                        raw.isEmpty() -> R.string.dsh_priv_danger_invalid
                        !PrivPolicy.addDangerEntry(context.applicationContext, raw) ->
                            R.string.dsh_priv_danger_exists
                        else -> null
                    }
                    if (addError == null) {
                        draft = ""
                        danger = PrivPolicy.activeDanger(context)
                        disabledBuiltin = PrivPolicy.disabledBuiltinDanger(context)
                        refreshFacts()
                    }
                }) {
                    Text(stringResource(R.string.dsh_priv_danger_add))
                }
            }
            // 被删掉的内置条目只在"恢复默认"里回来，所以这个按钮同时说清它还原什么
            if (disabledBuiltin.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.dsh_priv_danger_removed_builtin, disabledBuiltin.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            TextButton(
                onClick = {
                    PrivPolicy.resetDangerList(context.applicationContext)
                    danger = PrivPolicy.activeDanger(context)
                    disabledBuiltin = PrivPolicy.disabledBuiltinDanger(context)
                    refreshFacts()
                },
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text(stringResource(R.string.dsh_priv_danger_reset))
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun RestrictRow(cap: DshNativeBridge.Cap, restricted: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(nativeCapTitleRes(cap)),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(nativeCapSummaryRes(cap)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = restricted, onCheckedChange = onToggle)
    }
}
