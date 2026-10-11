@file:OptIn(ExperimentalMaterial3Api::class)

package me.bmax.apatch.ui.screen.settings.general

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.APApplication
import me.bmax.apatch.R
import me.bmax.apatch.ui.screen.settings.AppTitle
import me.bmax.apatch.util.*
import me.bmax.apatch.util.ui.APDialogBlurBehindUtils
import me.bmax.apatch.util.ui.showToast
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DpiChooseDialog(showDialog: MutableState<Boolean>) {
    val context = LocalContext.current
    val activity = context as? Activity

    val savedDpi = DPIUtils.currentDpi
    var tempDpi by remember { mutableIntStateOf(if (savedDpi == DPIUtils.DEFAULT_DPI) DPIUtils.systemDpi else savedDpi) }
    var isSystemDefault by remember { mutableStateOf(savedDpi == DPIUtils.DEFAULT_DPI) }

    BasicAlertDialog(
        onDismissRequest = { showDialog.value = false },
        properties = DialogProperties(
            decorFitsSystemWindows = true,
            usePlatformDefaultWidth = false,
        )
    ) {
        Surface(
            modifier = Modifier
                .width(340.dp)
                .wrapContentHeight(),
            shape = RoundedCornerShape(30.dp),
            tonalElevation = AlertDialogDefaults.TonalElevation,
            color = AlertDialogDefaults.containerColor,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(id = R.string.settings_app_dpi),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )

                Spacer(Modifier.height(16.dp))

                // System default toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isSystemDefault) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable {
                            isSystemDefault = !isSystemDefault
                            if (isSystemDefault) {
                                tempDpi = DPIUtils.systemDpi
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(id = R.string.system_default),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isSystemDefault)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (isSystemDefault) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                AnimatedVisibility(visible = !isSystemDefault) {
                    Column {
                        Spacer(Modifier.height(16.dp))

                        // Slider
                        val sliderValue by animateFloatAsState(
                            targetValue = tempDpi.toFloat(),
                            label = "DpiSlider",
                        )
                        Slider(
                            value = sliderValue,
                            onValueChange = { newValue ->
                                tempDpi = newValue.toInt()
                            },
                            valueRange = DPIUtils.DPI_MIN.toFloat()..DPIUtils.DPI_MAX.toFloat(),
                            steps = 10,
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                            ),
                        )

                        // Current value display
                        Text(
                            text = "${DPIUtils.getDpiFriendlyName(tempDpi)} ($tempDpi DPI)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )

                        // Preset pills
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            DPIUtils.presets.forEach { preset ->
                                val isSelected = tempDpi == preset.value
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .clickable { tempDpi = preset.value }
                                        .padding(vertical = 8.dp, horizontal = 4.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = preset.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isSelected)
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Apply button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { showDialog.value = false }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val finalDpi = if (isSystemDefault) DPIUtils.DEFAULT_DPI else tempDpi
                            showDialog.value = false
                            DPIUtils.setDpi(context, finalDpi)
                            activity?.recreate()
                        },
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.dpi_apply_settings))
                    }
                }
            }

            val dialogWindowProvider = LocalView.current.parent as DialogWindowProvider
            APDialogBlurBehindUtils.setupWindowBlurListener(dialogWindowProvider.window)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTitleChooseDialog(showDialog: MutableState<Boolean>, onTitleChanged: (String) -> Unit = {}) {
    val prefs = APApplication.sharedPreferences
    // 旧版本存下的键（apatch、kernelpatch、superpatch…）现在没有对应选项了。不认的键一律按
    // 默认项显示，否则用户会看到一个一项都没勾的列表，既不知道自己现在是什么，也无从改回。
    val currentTitle = AppTitle.normalize(prefs.getString("app_title", AppTitle.DEFAULT))
    val titles = listOf(
        AppTitle.DEFAULT to stringResource(R.string.app_title_dsh),
        AppTitle.CUSTOM to stringResource(R.string.app_title_custom),
        AppTitle.DEEPSEEK_HARNESS to stringResource(R.string.dsh_app_title),
    )

    BasicAlertDialog(
        onDismissRequest = { showDialog.value = false }, properties = DialogProperties(
            decorFitsSystemWindows = true,
            usePlatformDefaultWidth = false,
        )
    ) {
        Surface(
            modifier = Modifier
                .width(310.dp)
                .wrapContentHeight(),
            shape = RoundedCornerShape(30.dp),
            tonalElevation = AlertDialogDefaults.TonalElevation,
            color = AlertDialogDefaults.containerColor,
        ) {
            LazyColumn {
                items(titles.size, key = { it }) { index ->
                    val (key, displayName) = titles[index]
                    ListItem(
                        headlineContent = { Text(text = displayName) },
                        modifier = Modifier.clickable {
                            showDialog.value = false
                            prefs.edit { putString("app_title", key) }
                            onTitleChanged(key)
                        },
                        trailingContent = {
                            if (currentTitle == key) {
                                Icon(Icons.Filled.Check, contentDescription = null)
                            }
                        }
                    )
                }
            }

            val dialogWindowProvider = LocalView.current.parent as DialogWindowProvider
            APDialogBlurBehindUtils.setupWindowBlurListener(dialogWindowProvider.window)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomAppTitleDialog(showDialog: MutableState<Boolean>, snackBarHost: SnackbarHostState, onTitleChanged: (String) -> Unit = {}) {
    val prefs = APApplication.sharedPreferences
    var customTitle by remember {
        mutableStateOf(prefs.getString("custom_app_title", "DSH-Folk") ?: "DSH-Folk")
    }

    BasicAlertDialog(
        onDismissRequest = { showDialog.value = false }, properties = DialogProperties(
            decorFitsSystemWindows = true,
            usePlatformDefaultWidth = false,
        )
    ) {
        Surface(
            modifier = Modifier
                .width(310.dp)
                .wrapContentHeight()
                .padding(24.dp),
            shape = RoundedCornerShape(30.dp),
            tonalElevation = AlertDialogDefaults.TonalElevation,
            color = AlertDialogDefaults.containerColor,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.custom_app_title_dialog_title),
                    style = MaterialTheme.typography.titleMedium
                )
                OutlinedTextField(
                    value = customTitle,
                    onValueChange = { customTitle = it },
                    placeholder = { Text(stringResource(R.string.custom_app_title_dialog_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { showDialog.value = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(onClick = {
                        val trimmed = customTitle.trim()
                        if (trimmed.isEmpty()) {
                            showDialog.value = false
                            return@TextButton
                        }
                        prefs.edit { putString("custom_app_title", trimmed) }
                        onTitleChanged(trimmed)
                        showDialog.value = false
                    }) {
                        Text(stringResource(R.string.custom_app_title_dialog_confirm))
                    }
                }
            }

            val dialogWindowProvider = LocalView.current.parent as DialogWindowProvider
            APDialogBlurBehindUtils.setupWindowBlurListener(dialogWindowProvider.window)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopAppNameChooseDialog(showDialog: MutableState<Boolean>, onNameChanged: (String) -> Unit = {}) {
    val prefs = APApplication.sharedPreferences
    val context = LocalContext.current
    val currentName = remember { prefs.getString("desktop_app_name", "DSH-Folk") }
    BasicAlertDialog(
        onDismissRequest = { showDialog.value = false }, properties = DialogProperties(
            decorFitsSystemWindows = true,
            usePlatformDefaultWidth = false,
        )
    ) {
        Surface(
            modifier = Modifier
                .width(310.dp)
                .wrapContentHeight(),
            shape = RoundedCornerShape(30.dp),
            tonalElevation = AlertDialogDefaults.TonalElevation,
            color = AlertDialogDefaults.containerColor,
        ) {
            LazyColumn {
                item {
                    ListItem(
                        headlineContent = { Text(text = "DSH-Folk") },
                        modifier = Modifier.clickable {
                            showDialog.value = false
                            prefs.edit {
                                putString("desktop_app_name", "DSH-Folk")
                            }
                            onNameChanged("DSH-Folk")
                            LauncherIconUtils.applySaved(context)
                        },
                        trailingContent = {
                            if (currentName == "DSH-Folk" || currentName == null) {
                                Icon(Icons.Filled.Check, contentDescription = null)
                            }
                        }
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(text = "DSH") },
                        modifier = Modifier.clickable {
                            showDialog.value = false
                            prefs.edit {
                                putString("desktop_app_name", "DSH")
                            }
                            onNameChanged("DSH")
                            LauncherIconUtils.applySaved(context)
                        },
                        trailingContent = {
                            if (currentName == "DSH") {
                                Icon(Icons.Filled.Check, contentDescription = null)
                            }
                        }
                    )
                }
            }

            val dialogWindowProvider = LocalView.current.parent as DialogWindowProvider
            APDialogBlurBehindUtils.setupWindowBlurListener(dialogWindowProvider.window)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolkXAnimationTypeDialog(showDialog: MutableState<Boolean>, onTypeChanged: (String) -> Unit = {}) {
    val prefs = APApplication.sharedPreferences

    BasicAlertDialog(
        onDismissRequest = { showDialog.value = false }, properties = DialogProperties(
            decorFitsSystemWindows = true,
            usePlatformDefaultWidth = false,
        )
    ) {
        Surface(
            modifier = Modifier
                .width(310.dp)
                .wrapContentHeight(),
            shape = RoundedCornerShape(30.dp),
            tonalElevation = AlertDialogDefaults.TonalElevation,
            color = AlertDialogDefaults.containerColor,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.settings_folkx_animation_type),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                val currentType = remember { prefs.getString("folkx_animation_type", "linear") }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AlertDialogDefaults.containerColor,
                    tonalElevation = 2.dp
                ) {
                    Column {
                        listOf("linear", "spatial", "fade", "vertical", "diagonal").forEach { type ->
                            val labelId = when (type) {
                                "linear" -> R.string.settings_folkx_animation_linear
                                "spatial" -> R.string.settings_folkx_animation_spatial
                                "fade" -> R.string.settings_folkx_animation_fade
                                "vertical" -> R.string.settings_folkx_animation_vertical
                                "diagonal" -> R.string.settings_folkx_animation_diagonal
                                else -> R.string.settings_folkx_animation_linear
                            }
                            ListItem(
                                headlineContent = { Text(stringResource(labelId)) },
                                leadingContent = {
                                    RadioButton(
                                        selected = currentType == type,
                                        onClick = null
                                    )
                                },
                                modifier = Modifier.clickable {
                                    prefs.edit().putString("folkx_animation_type", type).apply()
                                    onTypeChanged(type)
                                    showDialog.value = false
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { showDialog.value = false }) {
                        Text(stringResource(id = android.R.string.cancel))
                    }
                }
            }
            val dialogWindowProvider = LocalView.current.parent as DialogWindowProvider
            APDialogBlurBehindUtils.setupWindowBlurListener(dialogWindowProvider.window)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanStorageDialog(showDialog: MutableState<Boolean>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 清理过程原来是「点确定→关弹窗→默默删→最后 toast」：删得慢时像死按钮，用户不知道在动。
    // 现在就地显示进度：running 时把按钮换成进度条 + 当前步骤，删完再 toast 并关窗。
    // 进度条是 determinate（四步，一步一格）——清理步骤是可数的，比转圈更能说明「做到哪了」。
    var cleaning by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("") }
    var doneCount by remember { mutableIntStateOf(0) }
    val totalSteps = 4

    BasicAlertDialog(
        // 清理期间不许点外面关掉：任务还在删盘，弹窗一消失用户就以为没反应了
        onDismissRequest = { if (!cleaning) showDialog.value = false }, properties = DialogProperties(
            decorFitsSystemWindows = true,
            usePlatformDefaultWidth = false,
        )
    ) {
        Surface(
            modifier = Modifier
                .width(310.dp)
                .wrapContentHeight(),
            shape = RoundedCornerShape(30.dp),
            tonalElevation = AlertDialogDefaults.TonalElevation,
            color = AlertDialogDefaults.containerColor,
        ) {
            Column(modifier = Modifier.padding(PaddingValues(all = 24.dp))) {
                Text(
                    text = stringResource(id = R.string.settings_clean_storage),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                if (cleaning) {
                    // 进度：当前步骤名 + 第几步/共几步，下面一条 determinate 进度条
                    Text(
                        text = stringResource(id = R.string.settings_clean_storage_progress, step, doneCount, totalSteps),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    LinearProgressIndicator(
                        progress = { doneCount.toFloat() / totalSteps },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        text = stringResource(id = R.string.settings_clean_storage_confirm),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showDialog.value = false }) {
                            Text(stringResource(id = android.R.string.cancel))
                        }

                        Button(onClick = {
                            cleaning = true
                            doneCount = 0
                            scope.launch {
                                // 删除容易因残留/占位文件导致下载或应用失败的目录，随后重建空目录。
                                // 一步一报：每删完一类就推进一格，大目录删起来慢时用户看得见在动。
                                val steps = listOf(
                                    context.getString(R.string.settings_clean_step_themes) to { ->
                                        rebuildDir(File(context.filesDir, "themes"))
                                    },
                                    context.getString(R.string.settings_clean_step_music) to { ->
                                        rebuildDir(File(context.filesDir, "music"))
                                    },
                                    context.getString(R.string.settings_clean_step_effects) to { ->
                                        rebuildDir(File(context.filesDir, "sound_effects"))
                                    },
                                    context.getString(R.string.settings_clean_step_cache) to { ->
                                        context.cacheDir?.listFiles()?.forEach { it.deleteRecursively() }
                                    },
                                )
                                var ok = true
                                for ((label, action) in steps) {
                                    step = label
                                    val stepOk = withContext(Dispatchers.IO) {
                                        runCatching { action() }.isSuccess
                                    }
                                    if (!stepOk) ok = false
                                    doneCount += 1
                                }
                                cleaning = false
                                showDialog.value = false
                                showToast(context, if (ok) R.string.settings_clean_storage_done else R.string.failure)
                            }
                        }) {
                            Text(stringResource(id = android.R.string.ok))
                        }
                    }
                }
            }
            val dialogWindowProvider = LocalView.current.parent as DialogWindowProvider
            APDialogBlurBehindUtils.setupWindowBlurListener(dialogWindowProvider.window)
        }
    }
}

/** 删掉一个目录再重建空目录（清理时反复用到的那一步）。 */
private fun rebuildDir(dir: File) {
    if (dir.exists()) dir.deleteRecursively()
    dir.mkdirs()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolkXAnimationSpeedDialog(showDialog: MutableState<Boolean>, onSpeedChanged: (Float) -> Unit = {}) {
    val prefs = APApplication.sharedPreferences

    BasicAlertDialog(
        onDismissRequest = { showDialog.value = false }, properties = DialogProperties(
            decorFitsSystemWindows = true,
            usePlatformDefaultWidth = false,
        )
    ) {
        Surface(
            modifier = Modifier
                .width(310.dp)
                .wrapContentHeight(),
            shape = RoundedCornerShape(30.dp),
            tonalElevation = AlertDialogDefaults.TonalElevation,
            color = AlertDialogDefaults.containerColor,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.settings_folkx_animation_speed),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                val currentSpeed = remember { prefs.getFloat("folkx_animation_speed", 1.0f) }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AlertDialogDefaults.containerColor,
                    tonalElevation = 2.dp
                ) {
                    Column {
                        val speeds = listOf(
                            0.5f to "0.5x",
                            0.75f to "0.75x",
                            1.0f to "1.0x",
                            1.25f to "1.25x",
                            1.5f to "1.5x",
                            2.0f to "2.0x"
                        )

                        speeds.forEach { (speed, label) ->
                            ListItem(
                                headlineContent = { Text(label) },
                                leadingContent = {
                                    RadioButton(
                                        selected = currentSpeed == speed,
                                        onClick = null
                                    )
                                },
                                modifier = Modifier.clickable {
                                    prefs.edit().putFloat("folkx_animation_speed", speed).apply()
                                    onSpeedChanged(speed)
                                    showDialog.value = false
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { showDialog.value = false }) {
                        Text(stringResource(id = android.R.string.cancel))
                    }
                }
            }
            val dialogWindowProvider = LocalView.current.parent as DialogWindowProvider
            APDialogBlurBehindUtils.setupWindowBlurListener(dialogWindowProvider.window)
        }
    }
}
