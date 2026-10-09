package me.bmax.apatch.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.bmax.apatch.R
import me.bmax.apatch.dsh.ScriptMarket

/**
 * 「从链接安装」弹窗：**脚本市场与用户脚本页共用同一份**。
 *
 * 这两处本来就是同一个动作（粘一个 GreasyFork 脚本页 / `.user.js` 地址 → 归一 → 拉正文 →
 * 装进同一份列表），只是入口位置不同（市场顶栏 / 用户脚本页顶栏）。各写一份的话，
 * 「认不出就标红」这类细节必然会漂移 —— 而它正是用户唯一能得到的反馈。
 *
 * 这里只做「输入 + 归一 + 标红」，真正的网络与落盘由调用方在 IO 线程上走
 * [me.bmax.apatch.dsh.Userscripts.installFromUrl]，装完各自刷新自己的列表。
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
