package me.bmax.apatch.dsh

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** 为 /native/notify/list 提供系统通知快照；实际暴露仍由原生桥权限模式控制。 */
class DshNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        cache.clear()
        activeNotifications?.forEach { cache[it.key] = it }
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        cache[sbn.key] = sbn
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        cache.remove(sbn.key)
    }

    companion object {
        @Volatile private var instance: DshNotificationListener? = null
        private val cache = ConcurrentHashMap<String, StatusBarNotification>()

        fun connected(): Boolean = instance != null

        fun snapshot(limit: Int): JSONArray {
            val out = JSONArray()
            cache.values.sortedByDescending { it.postTime }.take(limit).forEach { sbn ->
                val extras = sbn.notification.extras
                out.put(
                    JSONObject()
                        .put("key", sbn.key)
                        .put("package", sbn.packageName)
                        .put("title", extras.getCharSequence("android.title")?.toString().orEmpty())
                        .put("text", extras.getCharSequence("android.text")?.toString().orEmpty())
                        .put("postTime", sbn.postTime)
                        .put("ongoing", sbn.isOngoing),
                )
            }
            return out
        }
    }
}
