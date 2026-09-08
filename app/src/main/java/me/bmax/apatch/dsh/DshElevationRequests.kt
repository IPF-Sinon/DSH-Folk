package me.bmax.apatch.dsh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** 容器只能提交提权请求；真正修改权限必须由前台 UI 经用户确认。 */
object DshElevationRequests {
    data class Request(
        val id: Long,
        val cap: DshNativeBridge.Cap,
        val access: DshNativeBridge.Access,
        val reason: String,
    )

    private val ids = AtomicLong(0)
    private val mutable = MutableStateFlow<Request?>(null)
    val pending = mutable.asStateFlow()

    fun submit(cap: DshNativeBridge.Cap, access: DshNativeBridge.Access, reason: String): Request? {
        if (mutable.value != null) return null
        return Request(ids.incrementAndGet(), cap, access, reason).also { mutable.value = it }
    }

    fun clear(id: Long) {
        if (mutable.value?.id == id) mutable.value = null
    }
}
