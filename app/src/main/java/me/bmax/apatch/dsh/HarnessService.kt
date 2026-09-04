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
import me.bmax.apatch.util.appString
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

    /**
     * 通知条文案。走资源：通知会一直挂在状态栏，是最显眼的一处 UI。
     *
     * 必须用 [appString] 而不是 Service 自己的 `getString` —— Service 的 Context 与
     * Application 一样不受应用内语言影响（API 33 以下 setApplicationLocales 只改
     * Activity 的 Configuration）。界面切成英文后通知栏还是中文，就是这里。
     */
    private fun statusText(phase: DshPhase): String = when (phase) {
        DshPhase.RUNNING -> appString(
            R.string.dsh_notif_running,
            "http://127.0.0.1:${DshRuntime.state.value.port}",
        )
        DshPhase.STARTING -> appString(R.string.dsh_notif_starting)
        DshPhase.DOWNLOADING, DshPhase.EXTRACTING -> appString(R.string.dsh_notif_installing)
        DshPhase.ERROR -> appString(R.string.dsh_notif_error)
        DshPhase.NOT_READY -> appString(R.string.dsh_notif_stopped)
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            appString(R.string.dsh_notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = appString(R.string.dsh_notif_channel_desc)
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
            .setContentTitle(appString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, appString(R.string.dsh_notif_stop_action), stopIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "dsh_harness"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "top.funcun.dshfolk.action.STOP_HARNESS"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, HarnessService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, HarnessService::class.java).apply { action = ACTION_STOP })
        }
    }
}
