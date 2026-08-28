package me.bmax.apatch.dsh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.bmax.apatch.R
import me.bmax.apatch.ui.MainActivity

/**
 * DSH 前台服务：让 `dsh web` 常驻，通知条显示状态并提供「停止」。
 *
 * 必须是前台服务：Android 会在应用切后台后冻结/回收普通进程，而我们启动的
 * proot + node 是本进程的子进程，进程被杀 WebUI 就断。
 */
class HarnessService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        DshRuntime.attach(applicationContext)
        startForeground(NOTIFICATION_ID, buildNotification(statusText(DshRuntime.state.value.phase)))
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            DshRuntime.stopServer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (DshRuntime.state.value.phase != DshPhase.RUNNING) {
            DshRuntime.bootstrap()
        }
        notifyNow()
        // START_STICKY：被系统回收后自动重建，服务里再判断要不要重新拉起
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        DshRuntime.stopServer()
        super.onDestroy()
    }

    private fun observeState() {
        scope.launch {
            DshRuntime.state.map { it.phase }.distinctUntilChanged().collect { phase ->
                notify(statusText(phase))
            }
        }
    }

    private fun notifyNow() = notify(statusText(DshRuntime.state.value.phase))

    private fun notify(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun statusText(phase: DshPhase): String = when (phase) {
        DshPhase.RUNNING -> "DSH 运行中 · http://127.0.0.1:${DshRuntime.state.value.port}"
        DshPhase.STARTING -> "正在启动 DSH…"
        DshPhase.DOWNLOADING, DshPhase.EXTRACTING -> "正在安装运行时…"
        DshPhase.ERROR -> "服务异常，点击查看"
        DshPhase.NOT_READY -> "服务未启动"
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "DSH 服务", NotificationManager.IMPORTANCE_LOW).apply {
            description = "DeepSeek Harness 后台服务状态"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, HarnessService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DSH-Folk")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "停止", stopIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "dsh_harness"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "io.github.ipfsinon.dshfolk.action.STOP_HARNESS"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, HarnessService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, HarnessService::class.java).apply { action = ACTION_STOP })
        }
    }
}
