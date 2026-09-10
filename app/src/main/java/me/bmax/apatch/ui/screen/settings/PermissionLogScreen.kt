package me.bmax.apatch.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import org.json.JSONObject
import java.io.File

private data class PermissionLogEntry(val time: String, val command: String, val reason: String, val status: Int)

@Destination<RootGraph>
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionLogScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val entries = produceState<List<PermissionLogEntry>>(initialValue = emptyList()) {
        value = withContext(Dispatchers.IO) {
            listOf(
                File(context.filesDir, "audit/native-capability.jsonl"),
                File(context.filesDir, "audit/native-capability.previous.jsonl"),
            ).filter { it.isFile }.flatMap { file ->
                file.useLines { lines -> lines.mapNotNull { line ->
                    runCatching {
                        val o = JSONObject(line)
                        PermissionLogEntry(
                            o.optString("time"),
                            "${o.optString("method")} ${o.optString("path")}",
                            o.optString("reason"),
                            o.optInt("status"),
                        )
                    }.getOrNull()
                }.toList() }
            }.sortedByDescending { it.time }
        }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.dsh_permission_log_title)) },
            navigationIcon = { IconButton(onClick = { navigator.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null) } },
        )
    }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Spacer(Modifier.height(2.dp)) }
            if (entries.value.isEmpty()) item { Text(stringResource(R.string.dsh_permission_log_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(entries.value) { entry ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(entry.command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        Text(entry.time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.dsh_permission_log_reason, entry.reason), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.dsh_permission_log_status, entry.status), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}
