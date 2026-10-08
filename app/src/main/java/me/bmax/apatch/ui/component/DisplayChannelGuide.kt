package me.bmax.apatch.ui.component

import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import me.bmax.apatch.R
import me.bmax.apatch.dsh.PrivilegedShell

/**
 * 虚拟屏现在有没有那条它需要的提权通道。
 *
 * 虚拟屏服务端是 `app_process` 起的**独立进程**，以 uid 0（root）或 2000（shell/Shizuku）运行
 * —— 建一块 TRUSTED 虚拟屏和注入输入事件要的都是系统权限，普通 App 无论怎么申请都拿不到。
 * 所以判断依据不是「有没有某个 Android 权限」，而是 [PrivilegedShell.reach] 里那条通道就绪没
 * 有（root / Shizuku / 无线 ADB）。
 *
 * 每次点击时现算（reach 会 ping Shizuku、看 su 路径，不该在每帧重组里做）。
 */
internal fun displayChannelReady(context: Context): Boolean =
    PrivilegedShell.reach(context)?.usable == true

/**
 * 虚拟屏缺提权通道时的引导弹窗。
 *
 * 在此之前的形态是：点「预览」→ 进去只看到一行「启动失败」，既不说差什么，也没有去处 ——
 * 用户要的是"我该去开哪一个开关"。这里把**还差哪一步**按 [PrivilegedShell.reach] 给出的原因
 * 说清楚，并给一个直接到权限通道页的按钮（那一页有 root / Shizuku / 无线 ADB 三条的现状与
 * 各自的引导）。
 *
 * 原因串没有对上的（例如通道就绪却仍然起不来服务端）回落到一句通用说明：那种情况该看的是
 * 预览页那行报错，而不是这个弹窗。
 *
 * 三个出口：去权限通道页、重新检测、**取消（返回上级）**。第三个是必要的 —— 弹窗是在进这一页
 * 时自己弹出来的（不是用户点了什么），只给"去配通道"和"再检测一次"的话，用户想先退出去看看
 * 别的地方就没有路，只能按系统返回键；这里给一个明确的出口。
 */
@Composable
fun DisplayChannelGuideDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onOpenChannel: () -> Unit,
    onRecheck: () -> Unit,
    onCancel: () -> Unit,
) {
    if (!visible) return
    val context = LocalContext.current
    val reason = PrivilegedShell.reach(context)?.reason
    val bodyRes = when (reason) {
        PrivilegedShell.REASON_ROOT_UNVERIFIED -> R.string.dsh_display_guide_root
        PrivilegedShell.REASON_SHIZUKU_UNAUTHORIZED -> R.string.dsh_display_guide_shizuku
        PrivilegedShell.REASON_ADB_UNPAIRED -> R.string.dsh_display_guide_adb
        else -> R.string.dsh_display_guide_no_channel
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dsh_display_guide_title)) },
        text = { Text(stringResource(bodyRes)) },
        confirmButton = {
            TextButton(onClick = onOpenChannel) {
                Text(stringResource(R.string.dsh_display_guide_open_channel))
            }
        },
        dismissButton = {
            Row {
                // 「重新检测」不是重复那句说明：用户可能刚在系统里授了 Shizuku / 配好了 ADB 再回来，
                // 那一下要能立刻重判，否则只能退出去再点一次。
                TextButton(onClick = onRecheck) {
                    Text(stringResource(R.string.dsh_display_guide_recheck))
                }
                // 「取消」= 关掉弹窗并返回上级（调用方负责 navigateUp）：
                TextButton(onClick = onCancel) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        },
    )
}
