package me.bmax.apatch.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import kotlinx.coroutines.launch
import me.bmax.apatch.R
import me.bmax.apatch.dsh.ext.ExtensionMarket
import me.bmax.apatch.dsh.ext.MarketEntry

/**
 * 扩展市场弹层。
 *
 * 为什么做成弹层而不是一个独立目的地：这一页的上下文是「我装了哪些扩展」，
 * 市场的用途只有一个 —— 往里再装一个。跳到另一屏再跳回来会把「刚装完的那个在哪」丢掉，
 * 而弹层收起后列表本来就会重读。
 *
 * 安装成功的判定**不看网络返回**，只看 ExtensionRegistry 里有没有它：
 * 网络层说成功但写盘失败是真实存在的情况（目录权限、空间），那时用户看到的必须是真相。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionMarketSheet(
    onDismiss: () -> Unit,
    /** 安装成功后回调（由调用方重读列表）。 */
    onInstalled: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var loading by remember { mutableStateOf(true) }
    var entries by remember { mutableStateOf<List<MarketEntry>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var installingId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    val installed by remember { mutableStateOf(ExtensionMarket.installedVersions(context)) }

    LaunchedEffect(Unit) {
        loading = true
        ExtensionMarket.fetchIndex().fold(
            onSuccess = { entries = it; error = "" },
            onFailure = { error = it.message ?: it.javaClass.simpleName },
        )
        loading = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                text = stringResource(R.string.dsh_ext_market),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (message.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(12.dp))

            when {
                loading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                }
                error.isNotEmpty() -> {
                    Text(
                        text = stringResource(R.string.dsh_ext_market_failed, error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                )
                }
                entries.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.dsh_ext_market_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().height(420.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(entries, key = { it.id }) { entry ->
                            MarketRow(
                                entry = entry,
                                installedVersion = installed[entry.id],
                                installing = installingId == entry.id,
                                onInstall = {
                                    installingId = entry.id
                                    scope.launch {
                                        ExtensionMarket.install(context, entry).fold(
                                            onSuccess = { mf ->
                                                message = context.getString(
                                                    R.string.dsh_ext_market_done,
                                                    mf.name,
                                                )
                                                onInstalled()
                                            },
                                            onFailure = { e ->
                                                message = e.message ?: e.javaClass.simpleName
                                            },
                                        )
                                        installingId = null
                                    }
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 市场里的一行：名称 + 版本/作者 + 说明 + 能力 + 安装按钮。 */
@Composable
private fun MarketRow(
    entry: MarketEntry,
    installedVersion: String?,
    installing: Boolean,
    onInstall: () -> Unit,
) {
    // 已装的版本与目录里的不同才算「可更新」：只按 id 判断会把「装了旧版」也显示成已安装，
    // 用户就再也不会去点它。
    val upToDate = installedVersion != null && installedVersion == entry.version
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f),
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "v" + entry.version +
                            (if (entry.author.isNotEmpty()) " · " + entry.author else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    installing -> CircularProgressIndicator(Modifier.size(20.dp))
                    upToDate -> Text(
                        text = stringResource(R.string.dsh_ext_market_installed),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> TextButton(onClick = onInstall) {
                        Text(
                            stringResource(
                                if (installedVersion == null) {
                                    R.string.dsh_ext_market_install
                                } else {
                                    R.string.dsh_ext_market_update
                                },
                            ),
                        )
                    }
                }
            }
            if (entry.description.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.declaredCaps.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        R.string.dsh_ext_caps,
                        entry.declaredCaps.take(3).joinToString(", "),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
