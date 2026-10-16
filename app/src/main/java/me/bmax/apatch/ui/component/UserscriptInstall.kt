package me.bmax.apatch.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.dsh.ScriptMarket
import me.bmax.apatch.dsh.Userscripts

/**
 * 「从链接安装」弹窗：**脚本市场与用户脚本页共用同一份**。
 *
 * 这两处本来就是同一个动作（粘一个 GreasyFork 脚本页 / `.user.js` 地址 → 归一 → 拉正文 →
 * 装进同一份列表），只是入口位置不同（市场顶栏 / 用户脚本页顶栏）。各写一份的话，
 * 「认不出就标红」这类细节必然会漂移 —— 而它正是用户唯一能得到的反馈。
 *
 * 这里只做「输入 + 归一 + 标红」，真正的网络与落盘由调用方在 IO 线程上走
 * [me.bmax.apatch.dsh.Userscripts.prepareFromUrl] + [me.bmax.apatch.dsh.Userscripts.commit]。
 *
 * @param onInstall 归一之后的正文地址（[ScriptMarket.normalizeInstallUrl] 认得的那种）。
 */
@Composable
internal fun UserscriptLinkInstallDialog(
    onDismiss: () -> Unit,
    onInstall: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dsh_userscripts_market_link_install)) },
        text = {
            Column {
                Text(stringResource(R.string.dsh_userscripts_market_link_desc))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; invalid = false },
                    singleLine = true,
                    isError = invalid,
                    placeholder = { Text("https://greasyfork.org/scripts/…") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (invalid) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.dsh_userscripts_market_link_invalid),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val url = ScriptMarket.normalizeInstallUrl(input)
                if (url == null) {
                    invalid = true
                } else {
                    onDismiss()
                    onInstall(url)
                }
            }) { Text(stringResource(R.string.dsh_userscripts_market_install)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * 「待确认的安装」：三个入口（从链接、从文件、市场点安装）都先把要装的东西读出来放到这里，
 * 由宿主渲染 [UserscriptInstallConfirmHost] —— 用户点头之后才落盘。
 *
 * 用「置位 + 宿主渲染」而不是每个入口各弹一个框：三个入口的确认框必须是同一份（同一条
 * 「先查同名、比版本、给 diff」的逻辑），各写一份必然漂移。
 */
internal class UserscriptPendingInstallState {
    var pending by mutableStateOf<Userscripts.Pending?>(null)
        private set

    fun offer(value: Userscripts.Pending?) {
        pending = value
    }
}

@Composable
internal fun rememberUserscriptPendingInstall(): UserscriptPendingInstallState =
    remember { UserscriptPendingInstallState() }

/**
 * 确认框的宿主。确认之后走 [Userscripts.commit]：装新的、把同名旧的那条删掉、开关状态搬过去。
 *
 * 不这么做的话，同一个脚本换个版本就会在列表里留两条、页面里注入两遍 —— 文件名里带着正文
 * 哈希（[Userscripts.idOf]），版本一变 id 就变。
 */
@Composable
internal fun UserscriptInstallConfirmHost(
    state: UserscriptPendingInstallState,
    onInstalled: (String) -> Unit,
    onFailed: () -> Unit,
) {
    val pending = state.pending ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    UserscriptInstallConfirmDialog(
        pending = pending,
        onDismiss = { state.offer(null) },
        onConfirm = {
            state.offer(null)
            scope.launch {
                val id = withContext(Dispatchers.IO) { Userscripts.commit(context, pending) }
                if (id != null) onInstalled(id) else onFailed()
            }
        },
    )
}

/**
 * 安装 / 升级 / 降级 / 重装的确认框：落盘之前先把**要装的是什么**摆清楚。
 *
 * 用户要的就是这一眼：同名已装时给出「已装 vX → 这次 vY」与逐行差异（脚本升级唯一的信任来源），
 * 没有同名时就一句「确认安装」。差异只列有变化的行 —— 脚本几百行，上下文全列出来等于让人自己找。
 */
@Composable
internal fun UserscriptInstallConfirmDialog(
    pending: Userscripts.Pending,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val noVersion = stringResource(R.string.dsh_userscripts_confirm_no_version)
    val version = pending.version.ifBlank { noVersion }
    val existing = pending.existing

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    when (pending.change) {
                        Userscripts.Change.INSTALL -> R.string.dsh_userscripts_confirm_install
                        Userscripts.Change.UPGRADE -> R.string.dsh_userscripts_confirm_upgrade
                        Userscripts.Change.DOWNGRADE -> R.string.dsh_userscripts_confirm_downgrade
                        Userscripts.Change.SAME -> R.string.dsh_userscripts_confirm_reinstall
                    }
                )
            )
        },
        text = {
            Column {
                Text(pending.title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                if (existing == null) {
                    Text(
                        stringResource(R.string.dsh_userscripts_confirm_version, version),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        stringResource(
                            R.string.dsh_userscripts_confirm_replace,
                            existing.version.ifBlank { noVersion },
                            version,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (pending.change == Userscripts.Change.DOWNGRADE) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.dsh_userscripts_confirm_downgrade_warn),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.dsh_userscripts_confirm_diff),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (pending.diff.none { it.kind != Userscripts.DiffLine.Kind.SAME }) {
                        Text(
                            stringResource(R.string.dsh_userscripts_confirm_nodiff),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            for (line in pending.diff) {
                                if (line.kind == Userscripts.DiffLine.Kind.SAME) continue
                                val add = line.kind == Userscripts.DiffLine.Kind.ADD
                                Text(
                                    text = (if (add) "+ " else "- ") + line.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (add) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(
                        when (pending.change) {
                            Userscripts.Change.INSTALL -> R.string.dsh_userscripts_market_install
                            Userscripts.Change.UPGRADE -> R.string.dsh_userscripts_update
                            Userscripts.Change.DOWNGRADE -> R.string.dsh_userscripts_confirm_go_downgrade
                            Userscripts.Change.SAME -> R.string.dsh_userscripts_confirm_go_reinstall
                        }
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
