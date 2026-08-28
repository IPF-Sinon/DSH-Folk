package me.bmax.apatch.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.ramcosta.composedestinations.generated.destinations.GeneralSettingsScreenDestination
import com.ramcosta.composedestinations.generated.destinations.AppearanceSettingsScreenDestination
import com.ramcosta.composedestinations.generated.destinations.BehaviorSettingsScreenDestination
import com.ramcosta.composedestinations.generated.destinations.SecuritySettingsScreenDestination
import com.ramcosta.composedestinations.generated.destinations.BackupSettingsScreenDestination
import com.ramcosta.composedestinations.generated.destinations.ModuleSettingsScreenDestination
import com.ramcosta.composedestinations.generated.destinations.MultimediaSettingsScreenDestination
import com.ramcosta.composedestinations.generated.destinations.FunctionSettingsScreenDestination
import me.bmax.apatch.R
import me.bmax.apatch.ui.component.SearchAppBar
import me.bmax.apatch.util.ui.NavigationBarsSpacer

@Destination<RootGraph>
@Composable
fun SettingsSearchScreen(navigator: DestinationsNavigator) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current

    val resolvedEntries = remember {
        SettingsRegistry.resolveAll(context.resources)
    }

    val filteredResults = remember(searchQuery) {
        if (searchQuery.isBlank()) emptyList()
        else {
            val query = searchQuery.lowercase()
            resolvedEntries.filter { it.title.contains(query, ignoreCase = true) ||
                    it.summary.contains(query, ignoreCase = true) ||
                    it.categoryName.contains(query, ignoreCase = true) }
        }
    }

    Scaffold(
        topBar = {
            SearchAppBar(
                title = { Text(stringResource(R.string.settings_search_title)) },
                searchText = searchQuery,
                onSearchTextChange = { searchQuery = it },
                onClearClick = { searchQuery = "" },
                onBackClick = { navigator.popBackStack() },
                startInSearchMode = true,
            )
        },
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            if (filteredResults.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp, vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = if (searchQuery.isBlank())
                                stringResource(R.string.settings_search_empty_hint)
                            else
                                stringResource(R.string.settings_no_results),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            }

            val grouped = filteredResults.groupBy { it.entry.category }
            grouped.forEach { (category, entries) ->
                item(key = "header_${category.key}") {
                    Text(
                        text = stringResource(category.labelResId),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
                items(entries, key = { it.entry.key }) { resolved ->
                    SearchResultItem(
                        title = resolved.title,
                        summary = resolved.summary.ifBlank { null },
                        categoryName = stringResource(resolved.entry.category.labelResId),
                        onClick = {
                            val highlightKey = resolved.entry.key
                            when (category) {
                                SettingCategory.GENERAL -> navigator.navigate(GeneralSettingsScreenDestination(highlightKey))
                                SettingCategory.APPEARANCE -> navigator.navigate(AppearanceSettingsScreenDestination(highlightKey))
                                SettingCategory.BEHAVIOR -> navigator.navigate(BehaviorSettingsScreenDestination(highlightKey))
                                SettingCategory.SECURITY -> navigator.navigate(SecuritySettingsScreenDestination(highlightKey))
                                SettingCategory.BACKUP -> navigator.navigate(BackupSettingsScreenDestination(highlightKey))
                                SettingCategory.MODULE -> navigator.navigate(ModuleSettingsScreenDestination(highlightKey))
                                SettingCategory.MULTIMEDIA -> navigator.navigate(MultimediaSettingsScreenDestination(highlightKey))
                                SettingCategory.FUNCTION -> navigator.navigate(FunctionSettingsScreenDestination(highlightKey))
                            }
                        },
                    )
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
            item { NavigationBarsSpacer() }
        }
    }
}

@Composable
private fun SearchResultItem(
    title: String,
    summary: String?,
    categoryName: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = categoryName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
