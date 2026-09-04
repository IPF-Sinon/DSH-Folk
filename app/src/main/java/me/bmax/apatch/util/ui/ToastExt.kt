package me.bmax.apatch.util.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import android.graphics.Color
import me.bmax.apatch.util.appString

private const val TAG = "SafeToast"

fun showToast(context: Context, message: String) {
    try {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Log.w(TAG, "System toast unavailable, using fallback: $message", e)
        showFallbackToast(context, message)
    }
}

// 下面两个重载走 appString 而不是 getString：调用方经常传 applicationContext
// （后台协程、单例、Service），而应用内语言在 API 33 以下只作用于 Activity ——
// 那种情况下 getString 会拿到系统语言，Toast 与界面语言不一致。
fun showToast(context: Context, resId: Int) {
    showToast(context, context.appString(resId))
}

fun showToast(context: Context, resId: Int, vararg formatArgs: Any) {
    showToast(context, context.appString(resId, *formatArgs))
}

fun Toast.safeShow() {
    try {
        show()
    } catch (e: SecurityException) {
        Log.w(TAG, "System toast unavailable, safeShow suppressed", e)
    }
}

private fun showFallbackToast(context: Context, message: String) {
    val handler = Handler(Looper.getMainLooper())
    handler.post {
        try {
            val windowManager =
                context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            params.y = 100

            val textView = TextView(context).apply {
                text = message
                setPadding(48, 24, 48, 24)
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.argb(200, 48, 48, 48))
                textSize = 14f
            }

            windowManager.addView(textView, params)
            handler.postDelayed({
                try {
                    windowManager.removeView(textView)
                } catch (_: Exception) {
                }
            }, 2500)
        } catch (e: Exception) {
            Log.e(TAG, "Fallback toast failed too: $message", e)
        }
    }
}