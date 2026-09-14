package me.bmax.apatch.dsh

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * 给原生能力桥用的无障碍服务：**读屏幕 + 操作界面**。
 *
 * ## 为什么新开一个服务，而不是复用自启那个
 *
 * `DshAutostartService` 存在的唯一理由是「被系统 bind」，它的配置里刻意**没有**
 * `canRetrieveWindowContent` —— 那一位才是「能读你屏幕上的内容」的来源（见
 * `res/xml/dsh_autostart_a11y.xml` 的注释）。给那个服务加上这一位，等于让当初只为了
 * 「开机自启」而打开无障碍的用户，在毫无察觉的情况下把读屏权限也交了出去。两件事的
 * 代价差着量级，所以分成两个开关、两个服务，用户自己决定要不要开这一个。
 *
 * ## 这个服务本身做什么
 *
 * 什么都不做 —— 事件回调是空的。真正的实现全在 [DshA11y] 里：它通过这个实例调用
 * AccessibilityService 的 API（`rootInActiveWindow`、`dispatchGesture`、
 * `performGlobalAction`）。服务在这里只是一个「系统愿意把屏幕内容交给谁」的凭据。
 */
class DshA11yService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var instance: DshA11yService? = null

        /**
         * 服务实例（未开启时为 null）。
         *
         * 做法与 [DshNotificationListener.instance] 一致：静态引用由系统在连接/断开时维护，
         * 应用侧只读。**不要**缓存到别处长活对象里 —— 用户在系统设置里一关，这个引用就该失效。
         */
        fun service(): DshA11yService? = instance
    }
}
