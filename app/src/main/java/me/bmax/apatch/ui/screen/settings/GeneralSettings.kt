package me.bmax.apatch.ui.screen.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import me.bmax.apatch.util.ui.showToast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ramcosta.composedestinations.generated.destinations.LanguagePickerScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import me.bmax.apatch.APApplication
import me.bmax.apatch.BuildConfig
import me.bmax.apatch.R
import me.bmax.apatch.ui.component.ExpressiveCard
import me.bmax.apatch.ui.component.SplicedColumnGroup
import me.bmax.apatch.ui.component.ToggleSettingCard
import me.bmax.apatch.ui.component.UpdateDialog
import me.bmax.apatch.ui.component.rememberLoadingDialog
import me.bmax.apatch.util.*
import me.bmax.apatch.ui.screen.settings.general.*

@Composable
fun GeneralSettingsContent(
    snackBarHost: SnackbarHostState,
    flat: Boolean = false,
    highlightKey: String? = null,
    navigator: DestinationsNavigator,
) {
    val context = LocalContext.current
    val prefs = APApplication.sharedPreferences
    val scope = rememberCoroutineScope()
    val loadingDialog = rememberLoadingDialog()

    val languageTitle = stringResource(id = R.string.settings_app_language)
    val languageValue = remember {
        val locale = AppCompatDelegate.getApplicationLocales()[0]
        if (locale == null) {
            context.getString(R.string.system_default)
        } else {
            val languageTag = locale.toLanguageTag()
            val languages = context.resources.getStringArray(R.array.languages)
            val languagesValues = context.resources.getStringArray(R.array.languages_values)

            // Prefer an exact match, then a bare language-code match
            // (e.g. "id" for "id-ID"), otherwise fall back to the raw tag.
            var index = languagesValues.indexOf(languageTag)
            if (index < 0) {
                index = languagesValues.indexOf(languageTag.substringBefore('-'))
            }
            if (index >= 0) languages[index] else languageTag
        }
    }

    val updateTitle = stringResource(id = R.string.settings_check_update)

    val autoUpdateTitle = stringResource(id = R.string.settings_auto_update_check)
    val autoUpdateSummary = stringResource(id = R.string.settings_auto_update_check_summary)

    val launcherIconTitle = stringResource(id = R.string.settings_alt_icon)
    val launcherIconSummary = stringResource(id = R.string.alt_icon_summary)

    val appTitleTitle = stringResource(id = R.string.settings_app_title)
    var currentAppTitle by remember { mutableStateOf(prefs.getString("app_title", "dsh") ?: "dsh") }
    val appTitleLabel = when (currentAppTitle) {
        "custom" -> remember { prefs.getString("custom_app_title", "DSH-Folk") } ?: stringResource(R.string.app_title_custom)
        "fpatch" -> stringResource(R.string.app_title_fpatch)
        "apatch_folk" -> stringResource(R.string.app_title_apatch_folk)
        "apatchx" -> stringResource(R.string.app_title_apatchx)
        "apatch" -> stringResource(R.string.app_title_apatch)
        "kernelpatch" -> stringResource(R.string.app_title_kernelpatch)
        "kernelsu" -> stringResource(R.string.app_title_kernelsu)
        "supersu" -> stringResource(R.string.app_title_supersu)
        "folksu" -> stringResource(R.string.app_title_fpatch)
        "superuser" -> stringResource(R.string.app_title_superuser)
        "superpatch" -> stringResource(R.string.app_title_superpatch)
        "magicpatch" -> stringResource(R.string.app_title_magicpatch)
        "folkpatch" -> stringResource(R.string.app_title_folkpatch)
        else -> stringResource(R.string.app_title_dsh)
    }

    val customAppTitleTitle = stringResource(id = R.string.settings_custom_app_title)
    var currentCustomAppTitle by remember { mutableStateOf(prefs.getString("custom_app_title", "DSH-Folk") ?: "DSH-Folk") }

    val desktopAppNameTitle = stringResource(id = R.string.desktop_app_name)
    var currentDesktopAppName by remember { mutableStateOf(prefs.getString("desktop_app_name", "DSH-Folk") ?: "DSH-Folk") }

    val dpiTitle = stringResource(id = R.string.settings_app_dpi)
    val currentDpiVal = DPIUtils.currentDpi
    val dpiValue = if (currentDpiVal == DPIUtils.DEFAULT_DPI) stringResource(id = R.string.system_default) else "${DPIUtils.getDpiFriendlyName(currentDpiVal)} ($currentDpiVal DPI)"

    val logTitle = stringResource(id = R.string.send_log)

    val cleanStorageTitle = stringResource(id = R.string.settings_clean_storage)
    val cleanStorageSummary = stringResource(id = R.string.settings_clean_storage_summary)

    val folkXEngineTitle = stringResource(id = R.string.settings_folkx_engine_title)
    val folkXEngineSummary = stringResource(id = R.string.settings_folkx_engine_summary)

    val predictiveBackTitle = stringResource(id = R.string.settings_predictive_back)
    val predictiveBackSummary = stringResource(id = R.string.settings_predictive_back_summary)

    val showUpdateDialog = remember { mutableStateOf(false) }
    // 检查结果带到对话框：应用内更新要用它的 apkUrl / sha256 / notes
    val updateStatus = remember { mutableStateOf<UpdateChecker.Status?>(null) }
    val showCleanStorageDialog = remember { mutableStateOf(false) }
    val showAppTitleDialog = remember { mutableStateOf(false) }
    val showCustomAppTitleDialog = remember { mutableStateOf(false) }
    val showDesktopAppNameDialog = remember { mutableStateOf(false) }
    val showDpiDialog = remember { mutableStateOf(false) }
    val showFolkXAnimationTypeDialog = remember { mutableStateOf(false) }
    val showFolkXAnimationSpeedDialog = remember { mutableStateOf(false) }
    val showLogTrimDialog = remember { mutableStateOf(false) }
    val showLogWindowDialog = remember { mutableStateOf(false) }
    var logWindowIndex by remember { mutableStateOf(0f) }

    val useAltIcon = remember { mutableStateOf(prefs.getBoolean("use_alt_icon", false)) }
    var autoUpdateCheck by remember { mutableStateOf(prefs.getBoolean("auto_update_check", true)) }
    var folkXEngineEnabled by remember { mutableStateOf(prefs.getBoolean("folkx_engine_enabled", true)) }
    var currentType by remember { mutableStateOf(prefs.getString("folkx_animation_type", "linear") ?: "linear") }
    var currentSpeed by remember { mutableStateOf(prefs.getFloat("folkx_animation_speed", 1.0f)) }
    var predictiveBackEnabled by remember { mutableStateOf(prefs.getBoolean("predictive_back_enabled", true)) }

    // 采集并分享日志（两级对话框确认时间窗口后调用）。
    val collectAndShare: (LogWindow) -> Unit = { window ->
        scope.launch {
            val bugreport = loadingDialog.withLoading {
                withContext(Dispatchers.IO) {
                    getBugreportFile(context, window)
                }
            }
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                bugreport
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uri)
                type = "application/gzip"
                clipData = android.content.ClipData.newRawUri(null, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(
                    shareIntent,
                    context.getString(R.string.send_log)
                )
            )
        }
    }

    SplicedColumnGroup(flat = flat, highlightKey = highlightKey) {

        item(key = "general_language") {
            ExpressiveCard(flat = flat, onClick = { navigator.navigate(LanguagePickerScreenDestination) }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Filled.Translate, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            text = languageTitle,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = languageValue,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item(key = "general_check_update") {
            ExpressiveCard(flat = flat, onClick = {
                scope.launch {
                    loadingDialog.show()
                    val status = UpdateChecker.check()
                    loadingDialog.hide()
                    when {
                        status.hasUpdate -> {
                            // 把结果带给对话框：它据此决定能不能走应用内更新
                            updateStatus.value = status
                            showUpdateDialog.value = true
                        }
                        // 查不到不能报「已是最新」：那是一句肯定的错误结论。
                        // 匿名 GitHub API 只有 60/h，插件商店那边也在用，烧完就查不动了
                        status.failure != null -> showToast(context, R.string.update_check_failed)
                        else -> showToast(context, R.string.update_latest)
                    }
                }
            }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Filled.Update, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = updateTitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        item(key = "general_auto_update") {
            ToggleSettingCard(
            flat = flat,
            icon = Icons.Filled.Autorenew,
            title = autoUpdateTitle,
            description = autoUpdateSummary,
            checked = autoUpdateCheck,
            onCheckedChange = {
                autoUpdateCheck = it
                prefs.edit { putBoolean("auto_update_check", it) }
            }
        )
        }

        item(key = "general_folkx_engine") {
            ToggleSettingCard(
            flat = flat,
            icon = Icons.Filled.AutoAwesome,
            title = folkXEngineTitle,
            description = folkXEngineSummary,
            checked = folkXEngineEnabled,
            onCheckedChange = {
                folkXEngineEnabled = it
                prefs.edit().putBoolean("folkx_engine_enabled", it).apply()
            }
        )
        }

        item(key = "general_folkx_animation_type", visible = folkXEngineEnabled) {
            val animationTypeLabel = when (currentType) {
                "linear" -> R.string.settings_folkx_animation_linear
                "spatial" -> R.string.settings_folkx_animation_spatial
                "fade" -> R.string.settings_folkx_animation_fade
                "vertical" -> R.string.settings_folkx_animation_vertical
                "diagonal" -> R.string.settings_folkx_animation_diagonal
                else -> R.string.settings_folkx_animation_linear
            }

            ExpressiveCard(flat = flat, onClick = { showFolkXAnimationTypeDialog.value = true }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Filled.Animation, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.settings_folkx_animation_type),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(animationTypeLabel),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item(key = "general_folkx_animation_speed", visible = folkXEngineEnabled) {
            ExpressiveCard(flat = flat, onClick = { showFolkXAnimationSpeedDialog.value = true }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Filled.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.settings_folkx_animation_speed),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${currentSpeed}x",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item(key = "general_predictive_back", visible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ToggleSettingCard(
            flat = flat,
            icon = Icons.Filled.ArrowBack,
                title = predictiveBackTitle,
                description = predictiveBackSummary,
                checked = predictiveBackEnabled,
                onCheckedChange = {
                    predictiveBackEnabled = it
                    prefs.edit { putBoolean("predictive_back_enabled", it) }
                    (context as? Activity)?.recreate()
                }
            )
        }

        item(key = "general_alt_icon") {
            ToggleSettingCard(
            flat = flat,
            icon = Icons.Filled.Android,
            title = launcherIconTitle,
            description = launcherIconSummary,
            checked = useAltIcon.value,
            onCheckedChange = {
                prefs.edit { putBoolean("use_alt_icon", it) }
                LauncherIconUtils.updateLauncherState(context)
                useAltIcon.value = it
            }
        )
        }

        item(key = "general_app_title") {
            ExpressiveCard(flat = flat, onClick = { showAppTitleDialog.value = true }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Filled.Label, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            text = appTitleTitle,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = appTitleLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item(key = "general_custom_app_title", visible = currentAppTitle == "custom") {
            ExpressiveCard(flat = flat, onClick = { showCustomAppTitleDialog.value = true }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Filled.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            text = customAppTitleTitle,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = currentCustomAppTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item(key = "general_desktop_app_name") {
            ExpressiveCard(flat = flat, onClick = { showDesktopAppNameDialog.value = true }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Filled.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            text = desktopAppNameTitle,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = currentDesktopAppName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item(key = "general_dpi") {
            ExpressiveCard(flat = flat, onClick = { showDpiDialog.value = true }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Filled.FormatSize, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            text = dpiTitle,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = dpiValue,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item(key = "general_send_log") {
            ExpressiveCard(flat = flat, onClick = { showLogTrimDialog.value = true }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Filled.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = logTitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        item(key = "general_open_data_dir") {
            ExpressiveCard(flat = flat, onClick = {
                val authority = "${BuildConfig.APPLICATION_ID}.documents"
                val tree = DocumentsContract.buildTreeDocumentUri(authority, "")
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .putExtra(DocumentsContract.EXTRA_INITIAL_URI, tree)
                    .addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                    )
                val opened = runCatching { context.startActivity(intent) }.isSuccess
                if (!opened) showToast(context, R.string.dsh_docs_open_failed)
            }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Filled.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.dsh_docs_open_title),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.dsh_docs_open_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item(key = "general_clean_storage") {
            ExpressiveCard(flat = flat, onClick = { showCleanStorageDialog.value = true }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Filled.CleaningServices, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            text = cleanStorageTitle,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = cleanStorageSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showUpdateDialog.value) {
        UpdateDialog(
            onDismiss = { showUpdateDialog.value = false },
            onUpdate = {
                showUpdateDialog.value = false
                UpdateChecker.openUpdateUrl(context)
            },
            status = updateStatus.value,
        )
    }

    if (showCleanStorageDialog.value) {
        CleanStorageDialog(showCleanStorageDialog)
    }

    if (showAppTitleDialog.value) {
        AppTitleChooseDialog(showAppTitleDialog) { newTitle ->
            currentAppTitle = newTitle
        }
    }

    if (showCustomAppTitleDialog.value) {
        CustomAppTitleDialog(showCustomAppTitleDialog, snackBarHost) { newTitle ->
            currentCustomAppTitle = newTitle
        }
    }

    if (showDesktopAppNameDialog.value) {
        DesktopAppNameChooseDialog(showDesktopAppNameDialog) { newName ->
            currentDesktopAppName = newName
        }
    }

    if (showDpiDialog.value) {
        DpiChooseDialog(showDpiDialog)
    }

    if (showFolkXAnimationTypeDialog.value) {
        FolkXAnimationTypeDialog(showFolkXAnimationTypeDialog) { newType ->
            currentType = newType
        }
    }

    if (showFolkXAnimationSpeedDialog.value) {
        FolkXAnimationSpeedDialog(showFolkXAnimationSpeedDialog) { newSpeed ->
            currentSpeed = newSpeed
        }
    }

    if (showLogTrimDialog.value) {
        AlertDialog(
            onDismissRequest = { showLogTrimDialog.value = false },
            title = { Text(stringResource(R.string.dsh_log_trim_title)) },
            text = { Text(stringResource(R.string.dsh_log_trim_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showLogTrimDialog.value = false
                    showLogWindowDialog.value = true
                }) {
                    Text(stringResource(R.string.dsh_log_trim_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showLogTrimDialog.value = false
                    collectAndShare(LogWindow.All)
                }) {
                    Text(stringResource(R.string.dsh_log_trim_all))
                }
            },
        )
    }

    if (showLogWindowDialog.value) {
        val levels = listOf(LogWindow.M10, LogWindow.M30, LogWindow.H1, LogWindow.H12, LogWindow.All)
        val currentLevel = levels[logWindowIndex.toInt().coerceIn(0, levels.size - 1)]
        AlertDialog(
            onDismissRequest = { showLogWindowDialog.value = false },
            title = { Text(stringResource(R.string.dsh_log_window_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(currentLevel.labelRes),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = logWindowIndex,
                        onValueChange = { logWindowIndex = it },
                        valueRange = 0f..4f,
                        steps = 3,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showLogWindowDialog.value = false
                    val lv = levels[logWindowIndex.toInt().coerceIn(0, levels.size - 1)]
                    collectAndShare(lv)
                }) {
                    Text(stringResource(R.string.send_log))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogWindowDialog.value = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

}
