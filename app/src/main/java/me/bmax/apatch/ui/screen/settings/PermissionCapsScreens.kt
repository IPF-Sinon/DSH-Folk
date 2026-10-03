package me.bmax.apatch.ui.screen.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshEnv
import me.bmax.apatch.dsh.DshHostPrompt
import me.bmax.apatch.dsh.DshNativeBridge
import me.bmax.apatch.util.PermissionUtils

/**
 * 原生能力的四个分组页。
 *
 * 「功能」页把十几项能力摊在一张大卡片里，越往下滚越长；这里按 [CapGroup] 拆成四个
 * 目的地，一个分组一屏，列表项从「一行四个按钮」换成「一张卡片 + 一个范围胶囊」，
 * 点开卡片才选档位。分组本身仍是**纯视觉**的：CapGroup 只决定这一屏显示哪些能力，
 * 档位的存储、协议 id、闸门语义与「功能」页完全共用一套（[DshNativeBridge]）。
 *
 * 四个目的地都不带参数：分组由各自的入口函数写死，导航参数越少，返回栈与深链越好推理。
 */
@Destination<RootGraph>
@Composable
fun NativeCapsInteractScreen(navigator: DestinationsNavigator) {
    NativeCapsPage(navigator, CapGroup.INTERACT)
}

@Destination<RootGraph>
@Composable
fun NativeCapsSenseScreen(navigator: DestinationsNavigator) {
    NativeCapsPage(navigator, CapGroup.SENSE)
}

@Destination<RootGraph>
@Composable
fun NativeCapsPersonalScreen(navigator: DestinationsNavigator) {
    NativeCapsPage(navigator, CapGroup.PERSONAL)
}

@Destination<RootGraph>
@Composable
fun NativeCapsControlScreen(navigator: DestinationsNavigator) {
    NativeCapsPage(navigator, CapGroup.CONTROL)
}

/**
 * 「这项能力的权限框已经弹过一次」的记账 key 前缀。
 *
 * 必须与「功能」页用同一个前缀：两处入口面对的是同一批能力，各记各的会让
 * 「从未申请过」与「已被永久拒绝」这两种都返回 false 的情形在两页之间判断不一致 ——
 * 一边第一次就跳系统设置页，另一边永久拒绝后还在反复空弹。
 */
private const val NATIVE_CAPS_ASKED_PERM_PREFIX = "asked_perm_"

private fun nativeCapsAskedPermission(prefs: SharedPreferences, cap: DshNativeBridge.Cap): Boolean =
    prefs.getBoolean(NATIVE_CAPS_ASKED_PERM_PREFIX + cap.id, false)

private fun nativeCapsMarkAsked(prefs: SharedPreferences, cap: DshNativeBridge.Cap) {
    prefs.edit { putBoolean(NATIVE_CAPS_ASKED_PERM_PREFIX + cap.id, true) }
}

/**
 * 权限范围 → 展示文案。
 *
 * 逐字沿用「功能」页那段 `when (mode)`：OFF / CONTROL 各有固定串，其余三档按能力细分
 * （通知与短信的「只发 / 只看 / 看并发」）。这里不另造词 —— 同一档位在两页显示不同说法，
 * 用户会以为自己点错了东西。
 */
private fun nativeCapsAccessLabelRes(
    cap: DshNativeBridge.Cap,
    access: DshNativeBridge.Access,
): Int = when (access) {
    DshNativeBridge.Access.OFF -> R.string.dsh_native_access_off
    DshNativeBridge.Access.CONTROL -> R.string.dsh_native_access_control
    else -> accessLabelRes(cap, access)
}

/** 档位 → 弹层里的副文案：说明这一档到底放行了什么。 */
private fun nativeCapsAccessDescRes(access: DshNativeBridge.Access): Int = when (access) {
    DshNativeBridge.Access.OFF -> R.string.dsh_perm_access_off_desc
    DshNativeBridge.Access.READ -> R.string.dsh_perm_access_read_desc
    DshNativeBridge.Access.WRITE -> R.string.dsh_perm_access_write_desc
    DshNativeBridge.Access.READ_WRITE -> R.string.dsh_perm_access_rw_desc
    DshNativeBridge.Access.CONTROL -> R.string.dsh_perm_access_control_desc
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NativeCapsPage(navigator: DestinationsNavigator, group: CapGroup) {
    val context = LocalContext.current
    val dshPrefs = remember(context) {
        context.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)
    }

    // 总开关与分项都可能在系统设置 / 本页之外被改，所以初值只是兜底，回到本页要重读
    var nativeBridgeEnabled by remember { mutableStateOf(DshNativeBridge.enabled(context)) }
    var nativeAccess by remember { mutableStateOf(DshNativeBridge.accessMap(context)) }
    // 权限随时可能在系统设置里被撤销，而开关还是亮的 —— 不能只在首次组合时读一次
    var capsWithPermission by remember {
        mutableStateOf(DshNativeBridge.capsWithPermission(context))
    }
    // 「只给了大致位置」不是缺权限，是一种要单独说明的状态
    var coarseLocationOnly by remember {
        mutableStateOf(
            PermissionUtils.hasLocationPermission(context) &&
                !PermissionUtils.hasPreciseLocationPermission(context)
        )
    }
    // 正在选档位的那项能力；null = 弹层关闭
    var accessSheetCap by remember { mutableStateOf<DshNativeBridge.Cap?>(null) }
    // 「完全控制通知」不是普通档位：确认之前先不落盘
    var pendingFullControl by remember { mutableStateOf<DshNativeBridge.Cap?>(null) }
    // 运行时权限框关掉之后还要接着跳的特殊权限页
    var specialAfterRuntime by remember { mutableStateOf<DshNativeBridge.Special?>(null) }

    // 跳某项特殊权限的系统设置页。带包名的 Intent 在少数 ROM 上打不开，逐级后退，
    // 两级都失败时退到应用信息页 —— 总比按下去什么都不发生好。
    val openSpecialSettings: (DshNativeBridge.Special) -> Unit = { special ->
        val withPackage = if (special.perAppUri) {
            Intent(special.action)
                .setData(Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            null
        }
        val ok = withPackage != null &&
            runCatching { context.startActivity(withPackage) }.isSuccess
        if (!ok) {
            val plain = runCatching {
                context.startActivity(
                    Intent(special.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.isSuccess
            if (!plain) {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
    }

    // 一个 launcher 应付所有运行时权限：contract 收的是权限数组，回调里重读状态就够。
    // 条件注册 launcher 会崩，所以不为每类能力各建一个。
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        capsWithPermission = DshNativeBridge.capsWithPermission(context)
        coarseLocationOnly = PermissionUtils.hasLocationPermission(context) &&
            !PermissionUtils.hasPreciseLocationPermission(context)
        // 权限变了，提示词里的能力清单也得跟着变
        DshHostPrompt.writeFacts(context.applicationContext)
        specialAfterRuntime?.let { openSpecialSettings(it) }
        specialAfterRuntime = null
    }

    // 为某项能力补权限。能 requestPermissions 的直接申请；特殊权限只能跳系统页；
    // 两样都缺时（全屏通知）先申请运行时的，再在回调里跳特殊页。
    val requestCapPermission: (DshNativeBridge.Cap) -> Unit = { cap ->
        val currentAccess = DshNativeBridge.access(context, cap)
        val specialMissing = DshNativeBridge.specialPermissionOf(cap, currentAccess)
            ?.takeIf { !DshNativeBridge.specialGranted(context, it) }
        val needed = DshNativeBridge.runtimePermissions(context, cap, currentAccess).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            specialMissing?.let { openSpecialSettings(it) }
        } else {
            val activity = context as? Activity
            // shouldShowRequestPermissionRationale 在「从未申请过」和「被永久拒绝」两种
            // 情况下都返回 false，光看它区分不了，所以要靠记账。
            val canAsk = activity == null || !nativeCapsAskedPermission(dshPrefs, cap) ||
                needed.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
            if (canAsk) {
                nativeCapsMarkAsked(dshPrefs, cap)
                specialAfterRuntime = specialMissing
                permissionLauncher.launch(needed.toTypedArray())
            } else if (specialMissing != null) {
                openSpecialSettings(specialMissing)
            } else if (cap == DshNativeBridge.Cap.NOTIFY) {
                // 通知有专门的开关页，比通用的应用信息页少两跳
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            } else {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
    }

    // 写入一项档位。与「功能」页走同一个 API，并同步刷新提示词事实 ——
    // 容器侧是按事实判断「这项能力现在能不能用」的，漏了这一步 agent 会一直拿到旧答案。
    val applyAccess: (DshNativeBridge.Cap, DshNativeBridge.Access) -> Unit = { cap, access ->
        if (cap == DshNativeBridge.Cap.NOTIFY && access == DshNativeBridge.Access.CONTROL) {
            pendingFullControl = cap
        } else {
            DshNativeBridge.setAccess(context.applicationContext, cap, access)
            nativeAccess = DshNativeBridge.accessMap(context.applicationContext)
            DshHostPrompt.writeFacts(context.applicationContext)
            if (access != DshNativeBridge.Access.OFF) requestCapPermission(cap)
        }
    }

    // 权限与总开关都可能在系统设置里被改，回到本页时重读一遍
    LifecycleResumeEffect(Unit) {
        capsWithPermission = DshNativeBridge.capsWithPermission(context)
        coarseLocationOnly = PermissionUtils.hasLocationPermission(context) &&
            !PermissionUtils.hasPreciseLocationPermission(context)
        nativeBridgeEnabled = DshNativeBridge.enabled(context)
        nativeAccess = DshNativeBridge.accessMap(context)
        DshHostPrompt.writeFacts(context.applicationContext)
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(group.titleRes),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navigator.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.padding(paddingValues).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.dsh_native_section),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            item {
                Text(
                    text = stringResource(R.string.dsh_perm_caps_count, group.caps.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!nativeBridgeEnabled) {
                // 关掉总开关不等于清空档位：这里要说清「值还在」，否则用户会以为白配了
                item {
                    val activeCount = DshNativeBridge.Cap.entries.count {
                        val a = nativeAccess[it]
                        a != null && a != DshNativeBridge.Access.OFF
                    }
                    Text(
                        text = if (activeCount > 0) {
                            stringResource(R.string.dsh_native_off_hint_kept, activeCount)
                        } else {
                            stringResource(R.string.dsh_native_off_hint)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            items(group.caps) { cap ->
                val access = nativeAccess[cap] ?: DshNativeBridge.Access.OFF
                // 总开关关着时档位不生效，所以「已打开但缺权限」的提示也不该出现
                val on = nativeBridgeEnabled && access != DshNativeBridge.Access.OFF
                NativeCapCard(
                    cap = cap,
                    access = access,
                    on = on,
                    permissionMissing = cap !in capsWithPermission,
                    coarseLocationOnly = coarseLocationOnly,
                    onClick = { accessSheetCap = cap },
                    onRequestPermission = { requestCapPermission(cap) },
                )
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    if (pendingFullControl != null) {
        AlertDialog(
            onDismissRequest = { pendingFullControl = null },
            title = { Text(stringResource(R.string.dsh_native_full_control_warning_title)) },
            text = { Text(stringResource(R.string.dsh_native_full_control_warning_message)) },
            confirmButton = {
                TextButton(onClick = {
                    val cap = pendingFullControl ?: return@TextButton
                    DshNativeBridge.setAccess(
                        context.applicationContext,
                        cap,
                        DshNativeBridge.Access.CONTROL,
                    )
                    nativeAccess = DshNativeBridge.accessMap(context.applicationContext)
                    DshHostPrompt.writeFacts(context.applicationContext)
                    pendingFullControl = null
                    requestCapPermission(cap)
                }) { Text(stringResource(R.string.dsh_native_full_control_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingFullControl = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    accessSheetCap?.let { cap ->
        ModalBottomSheet(
            onDismissRequest = { accessSheetCap = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 32.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.dsh_perm_access_sheet_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(nativeCapTitleRes(cap)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                // 档位来自 accessOptions：只读能力不会出现「读写」，不要在这里自己排列组合
                val options = DshNativeBridge.accessOptions(cap)
                for (mode in options) {
                    val selected = nativeAccess[cap] == mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected,
                                enabled = nativeBridgeEnabled,
                                onClick = {
                                    accessSheetCap = null
                                    applyAccess(cap, mode)
                                },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = null,
                            enabled = nativeBridgeEnabled,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(nativeCapsAccessLabelRes(cap, mode)),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (nativeBridgeEnabled) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                },
                            )
                            Text(
                                text = stringResource(nativeCapsAccessDescRes(mode)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 一项能力一张卡片：标题 + 说明在左，当前范围胶囊在右；缺权限的提示贴在卡片内部。
 *
 * 整张卡片可点 —— 原来一行四个 OutlinedButton 既是视觉噪音，也让「当前是哪一档」
 * 要靠按钮颜色去猜；现在范围是一个明确的标签，点卡片才进入选择。
 */
@Composable
private fun NativeCapCard(
    cap: DshNativeBridge.Cap,
    access: DshNativeBridge.Access,
    on: Boolean,
    permissionMissing: Boolean,
    coarseLocationOnly: Boolean,
    onClick: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(nativeCapTitleRes(cap)),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(nativeCapSummaryRes(cap)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                NativeAccessBadge(cap = cap, access = access)
            }
            if (on && permissionMissing) {
                if (cap == DshNativeBridge.Cap.SHELL) {
                    // 特权命令缺的不是 Android 权限 —— 没有任何框可弹，也没有系统页可跳，
                    // 所以是一行说明而不是一个按下去什么都不发生的按钮。
                    Text(
                        text = stringResource(capPermissionHintRes(cap)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    TextButton(onClick = onRequestPermission) {
                        Text(stringResource(capPermissionHintRes(cap)))
                    }
                }
            } else if (on && cap == DshNativeBridge.Cap.LOCATION && coarseLocationOnly) {
                // 大致位置不是缺权限，而是一种需要说明的状态：点了也只会再弹一次同样的框
                Text(
                    text = stringResource(R.string.dsh_native_precise_location),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 当前范围的彩色胶囊。颜色只区分「关 / 普通档 / 完全控制」三档，文字才是精确含义。 */
@Composable
private fun NativeAccessBadge(cap: DshNativeBridge.Cap, access: DshNativeBridge.Access) {
    val container = when (access) {
        DshNativeBridge.Access.OFF -> MaterialTheme.colorScheme.surfaceVariant
        DshNativeBridge.Access.CONTROL -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val content = when (access) {
        DshNativeBridge.Access.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
        DshNativeBridge.Access.CONTROL -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50)) {
        Text(
            text = stringResource(nativeCapsAccessLabelRes(cap, access)),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
