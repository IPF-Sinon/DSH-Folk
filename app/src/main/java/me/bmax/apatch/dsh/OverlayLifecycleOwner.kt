package me.bmax.apatch.dsh

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * 给**悬浮窗里的 Compose** 提供一个生命周期宿主。
 *
 * 悬浮小窗是挂在 `WindowManager` 上的一个 `ComposeView`，它不在任何 Activity 里，
 * 也就没有天然的 `LifecycleOwner` / `ViewModelStoreOwner` / `SavedStateRegistryOwner` ——
 * 而 Compose 的 `ViewTreeLifecycleOwner` 拿不到会直接抛。所以这里自己造一个：
 * 一个常驻的 `LifecycleRegistry`（创建即 CREATE→START→RESUME），随窗口的存亡手动收发事件。
 *
 * 与 Operit 的 `ServiceLifecycleOwner` 是同一个东西（见 README 致谢）；
 * 三个 owner 缺一不可，只给 LifecycleOwner 会在 `rememberSaveable` 之类的地方炸。
 */
class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val tag = "DshOverlayLifecycle"
    private val registry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedState = SavedStateRegistryController.create(this)
    private val main = Handler(Looper.getMainLooper())

    init {
        // performRestore 必须在主线程：它内部会碰 Bundle / 已恢复状态
        if (Looper.myLooper() == Looper.getMainLooper()) {
            savedState.performRestore(null)
        } else {
            main.post { savedState.performRestore(null) }
        }
    }

    override val lifecycle: Lifecycle get() = registry

    override val viewModelStore: ViewModelStore get() = store

    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    /** 收发生命周期事件；同样只在主线程执行。 */
    fun moveTo(event: Lifecycle.Event) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            registry.handleLifecycleEvent(event)
        } else {
            main.post {
                try {
                    registry.handleLifecycleEvent(event)
                } catch (t: Throwable) {
                    Log.w(tag, "生命周期事件 $event 处理失败：${t.message}", t)
                }
            }
        }
    }
}
