package me.bmax.apatch.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.bmax.apatch.dsh.DshAutostart

/**
 * 开机自启的**广播**路径：收 `BOOT_COMPLETED` 后把 Harness 拉起来。
 *
 * 三条自启路径之一（另两条见 [DshAutostart]）。判断逻辑全部下沉到
 * [DshAutostart.trigger]：它会检查当前选的是不是**这一条**，所以用户切到脚本或无障碍
 * 模式之后，这个 receiver 仍然会收到广播、但不会再启动任何东西。
 *
 * FolkPatch 原来在这里补跑 `apd manager-boot-completed`（内核补丁的 post-fs-data 兜底）。
 * DSH-Folk 没有 apd，那段一并去掉了。
 *
 * 注意 `ACTION_LOCKED_BOOT_COMPLETED` **没有**注册：那条广播在用户解锁前就送到，此时
 * 应用的 CE 存储（`filesDir`、SharedPreferences）还没挂载，读到的会是空配置。
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        DshAutostart.trigger(context.applicationContext, DshAutostart.Mode.RECEIVER)
    }
}
