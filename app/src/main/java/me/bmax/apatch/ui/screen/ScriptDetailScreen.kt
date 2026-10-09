package me.bmax.apatch.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.ScriptMarketScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.dsh.Userscripts
import me.bmax.apatch.util.ui.HomeBottomSpacer
import me.bmax.apatch.util.ui.showToast

/**
 * 脚本详情页：一条脚本「是哪来的、正文长什么样、要不要换掉它」都放这一页。
 *
 * 为什么单独一页，而不是在列表行上再堆两个按钮：那一行的职责是「哪个脚本在跑、跑不跑」，
 * 一行里塞进三个控件之后每个都变得难点；而看来源、读正文、决定更新/移除，是**看一条**的活。
 * 列表行因此只留一个「更新」图标，其余交给这里。
 *
 * 「更新」用的是 [Userscripts.update]：记下来源的直接重拉并替换，没记来源的（从文件导入、
 * 或由没有来源功能的旧版本装的）去市场按名字找一遍 —— 不给一个点了没反应的按钮，也不假装
 * 能原地更新。更新真的换了版本时 id 会变（文件名含正文哈希），所以这一页会退回到列表。
 */
@Destination<RootGraph>
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptDetailScreen(navigator: DestinationsNavigator, scriptId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var script by remember { mutableStateOf(Userscripts.list(context).firstOrNull { it.id == scriptId }) }
    var code by remember { mutableStateOf(Userscripts.code(context, scriptId)) }
    var pendingRemove by remember { mutableStateOf(false) }

    /** 重读这一条。更新换了 id / 移除之后它就不在了，那就退回列表 —— 不摆一个空壳。 */
    fun reload() {
        script = Userscripts.list(context).firstOrNull { it.id == scriptId }
        code = Userscripts.code(context, scriptId)
        if (script == null) navigator.popBackStack()
    }

    fun update(title: String) {
        val ctx = context
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { Userscripts.update(ctx, scriptId) }
            when (outcome) {
                Userscripts.UpdateOutcome.NO_SOURCE -> {
                    showToast(ctx, ctx.getString(R.string.dsh_userscripts_update_check_market))
                    navigator.navigate(ScriptMarketScreenDestination(initialQuery = title))
                }
                Userscripts.UpdateOutcome.FETCH_FAILED ->
                    showToast(ctx, ctx.getString(R.string.dsh_userscripts_update_failed))
                Userscripts.UpdateOutcome.UP_TO_DATE ->
                    showToast(ctx, ctx.getString(R.string.dsh_userscripts_update_same))
                Userscripts.UpdateOutcome.UPDATED -> {
                    showToast(ctx, ctx.getString(R.string.dsh_userscripts_update_done))
                    reload()
                }
            }
        }
    }

    val current = script
    if (current == null) {
        // id 不存在（刚被移除、或链接过期）：别摆一个空壳，直接退回去
        LaunchedEffect(Unit) { navigator.popBackStack() }
        return
    }

    val sourceLabel = stringResource(R.string.dsh_userscripts_detail_source)
    val sourceValue = current.source ?: stringResource(R.string.dsh_userscripts_detail_source_unknown)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dsh_userscripts_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        // 与用户脚本页、插件列表同一套几何：LazyColumn + 左右 16dp + 12dp 行距
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(top = 8.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "head") {
                DetailCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                current.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                listOfNotNull(
                                    current.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                                    "run-at " + current.runAt,
                                    "${current.bytes} B",
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = current.enabled,
                            onCheckedChange = { want ->
                                Userscripts.setEnabled(context, current.id, want)
                                script = current.copy(enabled = want)
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "$sourceLabel：$sourceValue",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (current.description.isNotBlank()) {
                item(key = "desc") {
                    DetailCard {
                        Text(current.description, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (current.matches.isNotEmpty()) {
                item(key = "match") {
                    DetailCard {
                        Text(
                            current.matches.joinToString("\n"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }

            item(key = "actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { update(current.title) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.dsh_userscripts_update))
                    }
                    OutlinedButton(
                        onClick = { pendingRemove = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.dsh_userscripts_delete))
                    }
                }
            }

            item(key = "code") {
                DetailCard {
                    Text(
                        stringResource(R.string.dsh_userscripts_detail_code),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    // 正文可能几千行：限高 + 自己滚，否则这一页会被它顶到没法看。
                    // 限高加在 Box 上、不是 Text 上 —— 门禁不允许「给 Text 限高又不给省略」。
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            code.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { HomeBottomSpacer() }
        }
    }

    if (pendingRemove) {
        AlertDialog(
            onDismissRequest = { pendingRemove = false },
            title = { Text(stringResource(R.string.dsh_userscripts_delete)) },
            text = { Text(current.title) },
            confirmButton = {
                TextButton(onClick = {
                    Userscripts.remove(context, current.id)
                    pendingRemove = false
                    reload()
                }) { Text(stringResource(R.string.dsh_userscripts_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** 详情页的卡片壳：与用户脚本页、插件页那一套同形（20dp 圆角 + secondaryContainer 0.2）。 */
@Composable
private fun DetailCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f),
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) { content() }
    }
}
