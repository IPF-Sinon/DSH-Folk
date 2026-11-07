package me.bmax.apatch.ui.component

import android.widget.Toast
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
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshA11yRecovery

/**
 * 「无障碍服务被系统关掉后自动恢复」的设置卡。
 *
 * 为什么需要它：WRITE_SECURE_SETTINGS **没有运行时授权框**，只能由用户从 ADB / 终端授予。
 * 所以这张卡的全部职责是把两件事说清楚 ——
 *
 * 1. 现在有没有这条权限（没有就给出**可以直接复制**的 pm grant 命令，不在应用内偷偷跑）；
 * 2. 恢复本身是 best-effort（进程不在时无人可恢复），不能让用户以为装了它就万无一失。
 *
 * 是否真的动手不在这里判断：由 [DshA11yRecovery.wanted] 按应用内那两个开关算
 * （自启动模式是否仍是无障碍、原生桥 A11Y 档位是否不是关）。这里只负责呈现与触发。
 */
@Composable
internal fun A11yRecoveryCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(DshA11yRecovery.secureSettingsGranted(context)) }
    var busy by remember { mutableStateOf(false) }
    val command = remember(context) { DshA11yRecovery.adbGrantCommand(context) }

    // 用户刚从 ADB 授完权回来：每次回到这一页重读，而不是等进程重启。
    LifecycleResumeEffect(Unit) {
        granted = DshA11yRecovery.secureSettingsGranted(context)
        onPauseOrDispose { }
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.dsh_a11y_fix_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.dsh_a11y_fix_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    if (granted) R.string.dsh_a11y_fix_granted else R.string.dsh_a11y_fix_need_permission,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.tertiary,
            )
            if (!granted) {
                Spacer(Modifier.height(6.dp))
                // 命令原样摆出来：用户要复制去 ADB 执行，藏进弹窗就不叫"可复制"了。
                Text(
                    text = command,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!granted) {
                    OutlinedButton(onClick = {
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("pm grant", command))
                        Toast.makeText(context, R.string.dsh_a11y_fix_copied, Toast.LENGTH_SHORT).show()
                    }) {
                        Text(stringResource(R.string.dsh_a11y_fix_copy))
                    }
                    TextButton(onClick = {
                        granted = DshA11yRecovery.secureSettingsGranted(context)
                    }) {
                        Text(stringResource(R.string.dsh_a11y_fix_check))
                    }
                } else {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                // 写 secure settings 是 binder 调用，仍旧不该占主线程。
                                val r = withContext(Dispatchers.IO) {
                                    DshA11yRecovery.repair(context.applicationContext, manual = true)
                                }
                                busy = false
                                Toast.makeText(context, r.messageRes, Toast.LENGTH_SHORT).show()
                            }
                        },
                    ) {
                        Text(stringResource(R.string.dsh_a11y_fix_repair))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.dsh_a11y_fix_best_effort),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
