package me.bmax.apatch.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
 * 「不再逐条确认」名单：**一处**管理所有"免掉后续弹窗"的能力。
 *
 * ## 为什么从能力卡上挪到这里
 *
 * 这份名单与档位是两个维度（档位是全局的，名单只针对一项能力），所以原先挂在每张能力卡上。
 * 代价是：**根本不是名单**。用户看不到"我一共免掉了哪几项"，只能一张卡一张卡翻；想加一项
 * 也得先找到那张卡。而这份名单的全部意义正是"我免掉了什么、随时能收回"。
 *
 * 现在按**分类**列全部能力，带搜索：能一眼看出哪些已加入（开关是开的），也能按名字/摘要/
 * 分类名找到任意一项再开。撤掉一项就恢复逐条确认。
 *
 * 与严格程度无关：严格程度管"所有能力还要不要问"，这份名单只管"这一项免不免"，两者取
 * **更宽**的那个（见 [PrivPolicy.needsConfirm]）。危险操作（卸载/重启/清数据）永远会问 ——
 * 这一条不在这份名单的管辖范围内（见 [PrivPolicy.isTrusted] 的说明）。
 */
@Destination<RootGraph>
@Composable
fun TrustedCapsScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var trusted by remember { mutableStateOf(PrivPolicy.trusted(context)) }

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
                title = { Text(stringResource(R.string.dsh_priv_trust_title)) },
                searchText = query,
                onSearchTextChange = { query = it },
                onClearClick = { query = "" },
                onBackClick = { navigator.popBackStack() },
                // 与权限总页一致：进来先看名单，别一进门就被键盘盖住正文
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
            Text(
                text = stringResource(R.string.dsh_priv_trust_hub_summary, trusted.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (groups.isEmpty()) {
                Text(
                    text = stringResource(R.string.dsh_priv_trust_empty),
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
                    TrustRow(
                        cap = cap,
                        trusted = cap.id in trusted,
                        onToggle = { want ->
                            PrivPolicy.setTrusted(context.applicationContext, cap, want)
                            trusted = PrivPolicy.trusted(context)
                            // 事实要立刻刷新：agent 靠提示词里的字知道「这条调用还会不会弹窗」，
                            // 漏了这一步它会一直按旧假设行事。
                            DshHostPrompt.writeFacts(context.applicationContext)
                        },
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun TrustRow(cap: DshNativeBridge.Cap, trusted: Boolean, onToggle: (Boolean) -> Unit) {
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
        Switch(checked = trusted, onCheckedChange = onToggle)
    }
}
