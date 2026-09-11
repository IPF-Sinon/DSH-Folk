package me.bmax.apatch.ui.component

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshElevationRequests
import me.bmax.apatch.dsh.DshHostPrompt
import me.bmax.apatch.dsh.DshNativeBridge

/**
 * 原生能力申请弹窗（两段）。
 *
 * ## 第一段：要不要给这个权限
 *
 * 容器里的 agent 直接调能力，桥发现档位不够就**停住这次调用**问用户。用户点「允许 / 仅本次」
 * 之后，这次调用就地执行、把真实结果还给 agent —— agent 不需要「申请 → 再调一次」，用户也只看
 * 到一轮问答。倒计时走完按拒绝处理，同样是**这次调用**的结论。
 *
 * 弹窗正文里原文照显这次要执行的命令（多行、等宽、可复制）：用户要判断的不是
 * 「camera=write 要不要给」，而是「它接下来到底要做什么」。
 *
 * ## 第二段：Android 层还没给
 *
 * 用户点了允许，但 Android 自己的权限（相机、麦克风、通知、修改系统设置…）可能是关着的，
 * 这时执行必然失败。第二段单独弹，因为它要用户做的事完全不同：可能得跳系统设置页，回来再复查。
 * 应用回到前台会自动重新检测；用户点「我知道了」或者这一段的倒计时走完，这次调用就以
 * 「Android 层没授权」结束。
 *
 * ## 为什么做成公用组件而不是写在 MainActivity 里
 *
 * WebUI 是**独立 Activity**（[me.bmax.apatch.ui.DshWebUiActivity]）。只把弹窗挂在 MainActivity
 * 上时，用户正看着 WebUI，申请被压在它下面 —— 表现是「申请出去以后毫无反应」，而请求会静默
 * 超时，用户根本不知道自己被问过。所以每个可能在前台的 Activity 都挂一份，两个入口共用同一份
 * 文案与判定逻辑。
 *
 * 状态放在 [DshElevationRequests] 单例里（而不是某个 Activity 的 remember）：这样切页面、转屏、
 * 从主界面跳到 WebUI 都不会把一份还没答复的申请弄丢。
 */
@Composable
fun ElevationRequestDialogHost() {
    val activity = LocalActivity.current ?: return
    val ask by DshElevationRequests.pending.collectAsStateWithLifecycle()
    val os by DshElevationRequests.pendingOs.collectAsStateWithLifecycle()
    val request = ask
    val osRequest = os
    when {
        // 两段不会同时存在：第二段只在第一段有了结论之后才可能被登记
        osRequest != null -> OsPermissionDialog(activity, osRequest)
        request != null -> ElevationDialog(activity, request)
    }
}

/** 第一段：批准与否。 */
@Composable
private fun ElevationDialog(activity: Activity, request: DshElevationRequests.Request) {
    // 倒计时：只负责显示，真正的超时判定在 DshElevationRequests 里（不依赖任何界面活着）
    var secondsLeft by remember(request.id) {
        mutableIntStateOf(((DshElevationRequests.remainingMs(request) + 999) / 1000).toInt())
    }
    LaunchedEffect(request.id) {
        while (secondsLeft > 0) {
            delay(250)
            secondsLeft = ((DshElevationRequests.remainingMs(request) + 999) / 1000).toInt()
        }
    }

    AlertDialog(
        onDismissRequest = { DshElevationRequests.clear(request.id) },
        title = { Text(stringResource(R.string.dsh_native_elevate_title)) },
        text = {
            Column {
                Text(
                    stringResource(
                        R.string.dsh_native_elevate_message,
                        request.cap.id,
                        request.access.id,
                        request.reason,
                    )
                )
                if (request.cap == DshNativeBridge.Cap.NOTIFY &&
                    request.access == DshNativeBridge.Access.CONTROL
                ) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.dsh_native_full_control_warning_message))
                }
                // 附带的命令：原文照显、等宽、可选中复制，不做任何润色。
                val script = request.command ?: request.invocation
                if (script != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(
                            if (request.command != null) {
                                R.string.dsh_native_elevate_command_title
                            } else {
                                R.string.dsh_native_elevate_invocation_title
                            }
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    CommandBlock(script)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.dsh_native_elevate_once_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                // 倒计时放在正文末尾而不是按钮行里：按钮行是几个并排的动作，塞一个会跳动的
                // 秒数进去会让整行随秒数重排；这里它是一条稳定宽度的说明。
                Text(
                    text = stringResource(R.string.dsh_native_elevate_countdown, secondsLeft),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { allowOnce(activity, request) }) {
                    Text(stringResource(R.string.dsh_native_elevate_once))
                }
                TextButton(onClick = { allow(activity, request) }) {
                    Text(stringResource(R.string.dsh_native_elevate_allow))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { DshElevationRequests.clear(request.id) }) {
                Text(stringResource(R.string.dsh_native_elevate_deny))
            }
        },
    )
}

/**
 * 第二段：Android 层缺权限。
 *
 * 「重新检测」之外还有一个自动检测：用户多半是去系统设置页开的，回来后应用一进前台就复查一次
 * —— 手动按钮只是给「我已经开了但没检测到」的情况留的出口。
 */
@Composable
private fun OsPermissionDialog(activity: Activity, os: DshElevationRequests.OsRequest) {
    val context = activity.applicationContext
    var stillMissing by remember(os.id) { mutableStateOf(false) }
    var secondsLeft by remember(os.id) {
        mutableIntStateOf(((DshElevationRequests.remainingOsMs(os) + 999) / 1000).toInt())
    }
    LaunchedEffect(os.id) {
        while (secondsLeft > 0) {
            delay(250)
            secondsLeft = ((DshElevationRequests.remainingOsMs(os) + 999) / 1000).toInt()
        }
    }

    // 回到前台就复查：从系统设置页切回来正是这条路径
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (DshNativeBridge.permissionSatisfied(context, os.cap, os.access)) {
            DshElevationRequests.resolveOs(os.id, granted = true)
        }
    }

    AlertDialog(
        // 关掉弹窗等于「知道了」：这次调用以「Android 层没授权」结束，而不是继续挂着
        onDismissRequest = { DshElevationRequests.resolveOs(os.id, granted = false) },
        title = { Text(stringResource(R.string.dsh_native_os_title)) },
        text = {
            Column {
                Text(stringResource(R.string.dsh_native_os_message, os.missing.joinToString(", ")))
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.dsh_native_os_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (stillMissing) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.dsh_native_os_still_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.dsh_native_os_countdown, secondsLeft),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                os.settingsAction?.let { action ->
                    TextButton(onClick = { runCatching { activity.startActivity(Intent(action)) } }) {
                        Text(stringResource(R.string.dsh_native_os_open_settings))
                    }
                }
                TextButton(
                    onClick = {
                        if (DshNativeBridge.permissionSatisfied(context, os.cap, os.access)) {
                            DshElevationRequests.resolveOs(os.id, granted = true)
                        } else {
                            stillMissing = true
                        }
                    },
                ) {
                    Text(stringResource(R.string.dsh_native_os_recheck))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { DshElevationRequests.resolveOs(os.id, granted = false) }) {
                Text(stringResource(R.string.dsh_native_os_ack))
            }
        },
    )
}

/**
 * 命令块：等宽字体、可选中复制、超长可滚动。
 *
 * 垫一层 Surface 而不是直接排一行 Text —— 多行命令混在正文里时，用户分不清哪些字是要被执行的
 * 东西。横向也允许滚动：一条长命令被硬折成一堆碎片，比横向滚动更难读。
 */
@Composable
private fun CommandBlock(command: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        SelectionContainer {
            Text(
                text = command,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

/** 「允许」：级别落盘，长期有效。 */
private fun allow(activity: Activity, request: DshElevationRequests.Request) {
    DshNativeBridge.setAccess(activity.applicationContext, request.cap, request.access)
    DshHostPrompt.writeFacts(activity.applicationContext)
    DshElevationRequests.resolve(request.id, DshElevationRequests.Decision.ALLOWED)
    requestRuntimePermissions(activity, request)
}

/** 「仅本次」：只放行一次调用，用完即收回，开关不会被改动。 */
private fun allowOnce(activity: Activity, request: DshElevationRequests.Request) {
    DshNativeBridge.grantOnce(activity.applicationContext, request.cap, request.access)
    DshElevationRequests.resolve(request.id, DshElevationRequests.Decision.ONCE)
    // 系统权限还是得问：Android 那一层没有「只给一次」的通用选项，而缺了它这次调用
    // 必然失败。用户在系统弹窗上拒绝，就等于两层都没给。
    requestRuntimePermissions(activity, request)
}

/**
 * 拉起 Android 的运行时权限弹窗。
 *
 * 只管运行时权限：特殊权限（修改系统设置、勿扰访问权…）不能靠 requestPermissions 要到，得跳
 * 系统页 —— 那件事留给第二段弹窗上的「去系统设置」按钮，因为在这里直接跳走会让用户莫名其妙
 * 地离开应用。
 */
private fun requestRuntimePermissions(activity: Activity, request: DshElevationRequests.Request) {
    val permissions =
        DshNativeBridge.runtimePermissions(activity.applicationContext, request.cap, request.access)
    if (permissions.isNotEmpty()) {
        ActivityCompat.requestPermissions(activity, permissions, PERMISSION_REQUEST_CODE)
    }
}

/** 与 MainActivity 里原有的请求码保持一致，避免同一 Activity 出现两套码。 */
private const val PERMISSION_REQUEST_CODE = 7301
