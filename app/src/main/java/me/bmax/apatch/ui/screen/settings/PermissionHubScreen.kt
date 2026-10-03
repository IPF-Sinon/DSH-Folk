package me.bmax.apatch.ui.screen.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.NativeCapsCaptureScreenDestination
import com.ramcosta.composedestinations.generated.destinations.NativeCapsControlScreenDestination
import com.ramcosta.composedestinations.generated.destinations.NativeCapsInteractScreenDestination
import com.ramcosta.composedestinations.generated.destinations.NativeCapsPersonalScreenDestination
import com.ramcosta.composedestinations.generated.destinations.NativeCapsScreenAccessScreenDestination
import com.ramcosta.composedestinations.generated.destinations.NativeCapsSenseScreenDestination
import com.ramcosta.composedestinations.generated.destinations.PrivilegedChannelScreenDestination
import com.ramcosta.composedestinations.generated.destinations.TrustedCapsScreenDestination
import com.ramcosta.composedestinations.generated.destinations.WirelessAdbScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshHostPrompt
import me.bmax.apatch.dsh.DshNativeBridge
import me.bmax.apatch.ui.component.ExpressiveCard
import me.bmax.apatch.ui.component.ExpressiveSwitch
import me.bmax.apatch.ui.component.SearchAppBar
import me.bmax.apatch.dsh.PermissionManager
import me.bmax.apatch.dsh.PrivPolicy

/**
 * 权限管理：把「通道」「配对」和每一项原生能力收进一个带搜索的分类入口。
 *
 * ## 为什么要有这一页
 *
 * 这些卡片原先挤在「安全」页里一条长列表上。原生能力越接越多（每一项还各有 2–5 个权限档位），
 * 那条列表已经长到"想找某一项只能一路滚"。这里按**来源**分类：通道是"以什么身份执行"，
 * 配对是"怎么拿到那个身份"，原生能力是"拿到身份后能动什么" —— 三者互相独立，混在一条列表里
 * 才显得杂乱。
 *
 * ## 搜索
 *
 * 搜的是**条目本身**，不是分类名：能力有几十项，用户想找的是"通知""剪贴板"这种具体的东西，
 * 而不是"个人数据"这个分类。命中后直接跳到它所在的那一页，不再让人先想"它属于哪一类"。
 */
@Destination<RootGraph>
@Composable
fun PermissionHubScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val perm by PermissionManager.status.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    val channelLabel = perm.label(context)
    // 总开关与档位都可能在别处被改（系统设置、功能页），回到本页时重读一遍
    var bridgeEnabled by remember { mutableStateOf(DshNativeBridge.enabled(context)) }
    var activeCaps by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        bridgeEnabled = DshNativeBridge.enabled(context)
        activeCaps = DshNativeBridge.accessMap(context).values.count { it != DshNativeBridge.Access.OFF }
        onPauseOrDispose { }
    }
    val hits = remember(query) { searchHits(query, context) }

    Scaffold(
        topBar = {
            SearchAppBar(
                title = { Text(stringResource(R.string.dsh_perm_hub_title)) },
                searchText = query,
                onSearchTextChange = { query = it },
                onClearClick = { query = "" },
                onBackClick = { navigator.popBackStack() },
                // 不要改成 true：进搜索态会主动 requestFocus 并拉起键盘，而键盘会盖住这一页
                // 真正的正文 —— 分类列表。用户进这一页十次里有九次是来点分类的，一进来就被
                // 键盘挡住列表，"少点一下放大镜"省下的那一步远不抵这一下。
                // 放大镜按钮本来就在（SearchBar 的 actions），点它才进搜索态。
                startInSearchMode = false,
            )
        },
    ) { padding ->
        if (query.isBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.dsh_perm_hub_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )

                HubCard {
                    HubRow(
                        icon = Icons.Filled.Security,
                        title = stringResource(R.string.dsh_perm_cat_privileged),
                        summary = stringResource(R.string.dsh_perm_cat_privileged_summary, channelLabel),
                        onClick = { navigator.navigate(PrivilegedChannelScreenDestination) },
                    )
                    HubRow(
                        icon = Icons.Filled.Wifi,
                        title = stringResource(R.string.dsh_perm_cat_wireless_adb),
                        // 这一行常显（没 root 也没 Shizuku 的用户，这是唯一一条能自己配出来的
                        // 通道，藏起来就没法发现了），所以副标题必须说清它现在到底能不能用。
                        summary = stringResource(
                            if (perm.adbPaired) R.string.dsh_adb_paired
                            else R.string.dsh_perm_state_adb_unconfigured
                        ) + " · " + stringResource(R.string.dsh_adb_summary),
                        onClick = { navigator.navigate(WirelessAdbScreenDestination) },
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.dsh_perm_hub_cat_native),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                if (!bridgeEnabled) {
                    // 总开关关着就必须在这一层说出来。原先把能力列表整块收在开关里面，用户一眼
                    // 就知道"现在什么都不通、但档位还留着"；列表搬到次级页之后，这个信号如果
                    // 只留在页内，安全页上就只剩一个入口，读不出任何状态。
                    Text(
                        text = if (activeCaps > 0) {
                            stringResource(R.string.dsh_native_off_hint_kept, activeCaps)
                        } else {
                            stringResource(R.string.dsh_native_off_hint)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                HubCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.dsh_native_enable),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(R.string.dsh_native_enable_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        ExpressiveSwitch(
                            checked = bridgeEnabled,
                            onCheckedChange = { on ->
                                bridgeEnabled = on
                                DshNativeBridge.setEnabled(context.applicationContext, on)
                                // 提示词里写着「哪些能力开着」，开关一变就得让容器侧看到新事实
                                DshHostPrompt.writeFacts(context.applicationContext)
                            },
                        )
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    for (group in CapGroup.entries) {
                        HubRow(
                            icon = groupIcon(group),
                            title = stringResource(group.titleRes),
                            summary = stringResource(R.string.dsh_perm_caps_count, group.caps.size),
                            onClick = { navigateToGroup(navigator, group) },
                        )
                    }
                    // 放在**最下面**：它不是一个能力分类，而是"哪些能力已经不用再问了" ——
                    // 一份跨全部能力的名单。挤在分类之间会让人以为它也是其中一类。
                    HubRow(
                        icon = Icons.Filled.VerifiedUser,
                        title = stringResource(R.string.dsh_priv_trust_title),
                        summary = stringResource(
                            R.string.dsh_priv_trust_hub_summary,
                            PrivPolicy.trusted(context).size,
                        ),
                        onClick = { navigator.navigate(TrustedCapsScreenDestination) },
                    )
                }
                if (bridgeEnabled) {
                    // 只在桥开着时给这条提示：关着桥还教人怎么调 CLI 是自相矛盾的
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.dsh_native_cli_hint),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                if (hits.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.dsh_perm_hub_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(hits, key = { it.key }) { hit ->
                    HitRow(hit) { navigateTo(navigator, hit.target) }
                }
            }
        }
    }
}

/** 搜索结果的一条。[target] 决定点下去去哪 —— 不直接存 `Direction`，免得依赖库里那个接口的具体形状。 */
private data class Hit(
    val key: String,
    val title: String,
    val subtitle: String,
    val target: HubTarget,
)

private sealed interface HubTarget {
    data class Group(val group: CapGroup) : HubTarget
    data object Channel : HubTarget
    data object WirelessAdb : HubTarget
    data object Trusted : HubTarget
}

private fun navigateToGroup(navigator: DestinationsNavigator, group: CapGroup) {
    when (group) {
        CapGroup.INTERACT -> navigator.navigate(NativeCapsInteractScreenDestination)
        CapGroup.SENSE -> navigator.navigate(NativeCapsSenseScreenDestination)
        CapGroup.PERSONAL -> navigator.navigate(NativeCapsPersonalScreenDestination)
        CapGroup.CAPTURE -> navigator.navigate(NativeCapsCaptureScreenDestination)
        CapGroup.SCREEN -> navigator.navigate(NativeCapsScreenAccessScreenDestination)
        CapGroup.CONTROL -> navigator.navigate(NativeCapsControlScreenDestination)
    }
}

/**
 * 每个分组一个语义图标。
 *
 * 六个分组共用同一个图标会让这份列表读起来像六个「同一件事」—— 分组的意义就在标题和
 * 图标上，两者都雷同等于没分。
 */
private fun groupIcon(group: CapGroup): ImageVector = when (group) {
    CapGroup.INTERACT -> Icons.Filled.TouchApp
    CapGroup.SENSE -> Icons.Filled.Sensors
    CapGroup.PERSONAL -> Icons.Filled.Person
    CapGroup.CAPTURE -> Icons.Filled.PhotoCamera
    CapGroup.SCREEN -> Icons.Filled.ScreenShare
    CapGroup.CONTROL -> Icons.Filled.AdminPanelSettings
}

private fun navigateTo(navigator: DestinationsNavigator, target: HubTarget) {
    when (target) {
        is HubTarget.Group -> navigateToGroup(navigator, target.group)
        HubTarget.Channel -> navigator.navigate(PrivilegedChannelScreenDestination)
        HubTarget.WirelessAdb -> navigator.navigate(WirelessAdbScreenDestination)
        HubTarget.Trusted -> navigator.navigate(TrustedCapsScreenDestination)
    }
}

/**
 * 跨分类搜索。
 *
 * 匹配范围**故意覆盖三层标题**：能力名、能力摘要、以及它所属的分组名。只匹配能力名的话，
 * 用户输入"个人数据"（分组名）会一无所获 —— 而那正是他在设置页上看到过的字。
 */
private fun searchHits(query: String, context: Context): List<Hit> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    val hits = ArrayList<Hit>()

    val channelTitle = context.getString(R.string.dsh_perm_cat_privileged)
    if (channelTitle.lowercase().contains(q)) {
        hits += Hit("perm-channel", channelTitle, context.getString(R.string.dsh_perm_summary), HubTarget.Channel)
    }
    val adbTitle = context.getString(R.string.dsh_perm_cat_wireless_adb)
    if (adbTitle.lowercase().contains(q)) {
        hits += Hit("perm-adb", adbTitle, context.getString(R.string.dsh_adb_summary), HubTarget.WirelessAdb)
    }

    val trustTitle = context.getString(R.string.dsh_priv_trust_title)
    if (trustTitle.lowercase().contains(q)) {
        hits += Hit(
            "perm-trust",
            trustTitle,
            context.getString(R.string.dsh_priv_trust_hub_summary, PrivPolicy.trusted(context).size),
            HubTarget.Trusted,
        )
    }

    for (group in CapGroup.entries) {
        val groupName = context.getString(group.titleRes)
        for (cap in group.caps) {
            val title = context.getString(nativeCapTitleRes(cap))
            val summary = context.getString(nativeCapSummaryRes(cap))
            if (title.lowercase().contains(q) || summary.lowercase().contains(q) || groupName.lowercase().contains(q)) {
                hits += Hit("cap-${cap.id}", title, groupName, HubTarget.Group(group))
            }
        }
    }
    return hits
}

@Composable
private fun HubCard(content: @Composable () -> Unit) {
    ExpressiveCard {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { content() }
    }
}

@Composable
private fun HitRow(hit: Hit, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(hit.title, style = MaterialTheme.typography.bodyLarge)
        Text(
            hit.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HubRow(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

