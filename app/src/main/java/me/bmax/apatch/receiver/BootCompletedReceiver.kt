package me.bmax.apatch.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import me.bmax.apatch.dsh.DshEnv
import me.bmax.apatch.dsh.HarnessService

/**
 * 开机自启：可选地在系统启动完成后把 `dsh web` 拉起来。
 *
 * FolkPatch 原来在这里补跑 `apd manager-boot-completed`（内核补丁的 post-fs-data 兜底）。
 * DSH-Folk 没有 apd，改成读 [DshEnv.KEY_AUTOSTART]：开启且运行时已安装时启动前台服务，
 * 否则什么都不做 —— 不能无条件启动，否则用户一开机就白跑一个 120 MB 容器。
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(DshEnv.KEY_AUTOSTART, false)) return

        if (!DshEnv.isRuntimeInstalled(context)) {
            Log.i(TAG, "Autostart requested but the runtime is not installed yet")
            return
        }

        try {
            HarnessService.start(context)
            Log.i(TAG, "Autostart: HarnessService requested")
        } catch (t: Throwable) {
            // 高版本 Android 对开机后台启动前台服务有限制，失败只记日志，用户可手动启动
            Log.e(TAG, "Autostart failed", t)
        }
    }

    companion object {
        private const val TAG = "DshBootReceiver"
    }
}
