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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshElevationRequests
import me.bmax.apatch.dsh.DshHostPrompt
import me.bmax.apatch.dsh.DshNativeBridge

/**
 * 原生能力提权申请弹窗。
 *
 * ## 为什么做成公用组件而不是写在 MainActivity 里
 *
 * WebUI 是**独立 Activity**（[me.bmax.apatch.ui.DshWebUiActivity]）。只把弹窗挂在
 * MainActivity 上时，用户正看着 WebUI，申请被压在它下面 —— 表现是「申请出去以后毫无反应」，
 * 而请求在 [DshElevationRequests.TTL_MS] 之后静默超时算拒绝，用户根本不知道自己被问过。
 * 所以每个可能在前台的 Activity 都挂一份，两个入口共用同一份文案与判定逻辑。
 *
 * 状态放在 [DshElevationRequests] 单例里（而不是某个 Activity 的 remember）：这样切页面、
 * 转屏、从主界面跳到 WebUI 都不会把一份还没答复的申请弄丢。
 */
@Composable
fun ElevationRequestDialogHost() {
    val activity = LocalActivity.current ?: return
    val request by DshElevationRequests.pending.collectAsStateWithLifecycle()
    val current = request ?: return

    // 倒计时：只负责显示，真正的超时判定在 DshElevationRequests 里（不依赖任何界面活着）
    var secondsLeft by remember(current.id) {
        mutableIntStateOf(((DshElevationRequests.remainingMs(current) + 999) / 1000).toInt())
    }
    LaunchedEffect(current.id) {
        while (secondsLeft > 0) {
            delay(250)
            secondsLeft =
                ((DshElevationRequests.remainingMs(current) + 999) / 1000).toInt()
        }
    }

    AlertDialog(
        onDismissRequest = { DshElevationRequests.clear(current.id) },
        title = { Text(stringResource(R.string.dsh_native_elevate_title)) },
        text = {
            Column {
                Text(
                    stringResource(
                        R.string.dsh_native_elevate_message,
                        current.cap.id,
                        current.access.id,
                        current.reason,
                    )
                )
                if (current.cap == DshNativeBridge.Cap.NOTIFY &&
                    current.access == DshNativeBridge.Access.CONTROL
                ) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.dsh_native_full_control_warning_message))
                }
                // agent 附上的命令：用户要判断的不是「camera=write 要不要给」，而是「它接下来
                // 到底要做什么」。所以原文照显、等宽、可选中复制，不做任何润色。
                val script = current.command ?: current.invocation
                if (script != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(
                            if (current.command != null) {
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
                // 倒计时放在正文末尾而不是按钮行里：按钮行是三个并排的动作，塞一个会跳动
                // 的秒数进去会让整行随秒数重排；这里它是一条稳定宽度的说明。
                Text(
                    text = stringResource(R.string.dsh_native_elevate_countdown, secondsLeft),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { allowOnce(activity, current) }) {
                    Text(stringResource(R.string.dsh_native_elevate_once))
                }
                TextButton(onClick = { allow(activity, current) }) {
                    Text(stringResource(R.string.dsh_native_elevate_allow))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { DshElevationRequests.clear(current.id) }) {
                Text(stringResource(R.string.dsh_native_elevate_deny))
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

/** 「允许」：级别落盘，长期有效。 */private fun allow(activity: Activity, request: DshElevationRequests.Request) {
    DshNativeBridge.setAccess(activity.applicationContext, request.cap, request.access)
    DshHostPrompt.writeFacts(activity.applicationContext)
    DshElevationRequests.resolve(request.id, DshElevationRequests.Decision.ALLOWED)
    requestOsPermission(activity, request)
}

/** 「仅本次」：只放行一次调用，用完即收回，开关不会被改动。 */
private fun allowOnce(activity: Activity, request: DshElevationRequests.Request) {
    DshNativeBridge.grantOnce(activity.applicationContext, request.cap, request.access)
    DshElevationRequests.resolve(request.id, DshElevationRequests.Decision.ONCE)
    // 系统权限还是得问：Android 那一层没有「只给一次」的通用选项，而缺了它这次调用
    // 必然失败。用户在系统弹窗上拒绝，就等于两层都没给。
    requestOsPermission(activity, request)
}

/**
 * 拉起系统层面的授权：特殊权限直接跳系统页，普通运行时权限走 requestPermissions。
 *
 * 与「允许 / 仅本次」分开：这一层是 Android 说了算，桥只能代为发起。
 */
private fun requestOsPermission(activity: Activity, request: DshElevationRequests.Request) {
    val ctx = activity.applicationContext
    val special = DshNativeBridge.specialPermissionOf(request.cap, request.access)
    if (special != null && !DshNativeBridge.specialGranted(ctx, special)) {
        runCatching { activity.startActivity(Intent(special.action)) }
        return
    }
    val permissions = DshNativeBridge.runtimePermissions(ctx, request.cap, request.access)
    if (permissions.isNotEmpty()) {
        ActivityCompat.requestPermissions(activity, permissions, PERMISSION_REQUEST_CODE)
    }
}

/** 与 MainActivity 里原有的请求码保持一致，避免同一 Activity 出现两套码。 */
private const val PERMISSION_REQUEST_CODE = 7301
