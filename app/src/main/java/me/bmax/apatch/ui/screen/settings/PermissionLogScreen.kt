package me.bmax.apatch.ui.screen.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.util.BiometricUtils
import org.json.JSONObject
import java.io.File

private data class PermissionLogEntry(
    val time: String,
    val command: String,
    val fullCommand: String?,
    val reason: String,
    val capability: String,
    val access: String,
    val status: Int,
)

@Destination<RootGraph>
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionLogScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    var sensitiveVisible by remember { mutableStateOf(false) }
    val entries = produceState<List<PermissionLogEntry>>(initialValue = emptyList()) {
        value = withContext(Dispatchers.IO) {
            listOf(
                File(context.filesDir, "audit/native-capability.jsonl"),
                File(context.filesDir, "audit/native-capability.previous.jsonl"),
            ).filter { it.isFile }.flatMap { file ->
                file.useLines { lines ->
                    lines.mapNotNull { line ->
                        runCatching {
                            val o = JSONObject(line)
                            PermissionLogEntry(
                                time = o.optString("time"),
                                command = o.optString("command").ifBlank {
                                    "${o.optString("method")} ${o.optString("path")}".trim()
                                },
                                fullCommand = o.optString("fullCommand").takeIf { it.isNotBlank() },
                                reason = o.optString("reason"),
                                capability = o.optString("capability"),
                                access = o.optString("access"),
                                status = o.optInt("status"),
                            )
                        }.getOrNull()
                    }.toList()
                }
            }.sortedByDescending { it.time }
        }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.dsh_permission_log_title)) },
            actions = {
                if (entries.value.any { it.fullCommand != null }) {
                    IconButton(onClick = {
                        if (sensitiveVisible) {
                            sensitiveVisible = false
                        } else if (activity != null) {
                            scope.launch {
                                if (BiometricUtils.authenticate(activity)) sensitiveVisible = true
                            }
                        }
                    }) {
                        Icon(
                            if (sensitiveVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = stringResource(
                                if (sensitiveVisible) R.string.dsh_permission_log_hide_sensitive
                                else R.string.dsh_permission_log_show_sensitive
                            ),
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = { navigator.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                }
            },
        )
    }) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Spacer(Modifier.height(2.dp)) }
            if (entries.value.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.dsh_permission_log_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(entries.value) { entry ->
                val displayedCommand = if (sensitiveVisible) entry.fullCommand ?: entry.command else entry.command
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            SelectionContainer(Modifier.weight(1f)) {
                                Text(
                                    displayedCommand,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            IconButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("dsh-native", displayedCommand))
                            }) {
                                Icon(
                                    Icons.Outlined.ContentCopy,
                                    contentDescription = stringResource(R.string.dsh_permission_log_copy),
                                )
                            }
                        }
                        Text(
                            entry.time,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.dsh_permission_log_reason, entry.reason),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            stringResource(
                                R.string.dsh_permission_log_details,
                                entry.capability,
                                entry.access,
                                entry.status,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}
