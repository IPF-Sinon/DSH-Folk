package me.bmax.apatch

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import android.util.Log
import me.bmax.apatch.util.ui.showToast
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import me.bmax.apatch.ui.CrashHandleActivity
import me.bmax.apatch.ui.theme.MusicConfig
import me.bmax.apatch.util.MusicManager
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.Locale
import kotlin.system.exitProcess

import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.util.DebugLogger
import me.zhanghai.android.appiconloader.coil.AppIconFetcher
import me.zhanghai.android.appiconloader.coil.AppIconKeyer

lateinit var apApp: APApplication

const val TAG = "APatch"

class APApplication : Application(), Thread.UncaughtExceptionHandler, ImageLoaderFactory {
    lateinit var okhttpClient: OkHttpClient

    init {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun newImageLoader(): ImageLoader {
        val iconSize = resources.getDimensionPixelSize(android.R.dimen.app_icon_size)
        return ImageLoader.Builder(this)
            .components {
                add(AppIconKeyer())
                add(AppIconFetcher.Factory(iconSize, false, this@APApplication))
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .diskCache(
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            )
            .memoryCache(
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            )
            .crossfade(true)
            .logger(if (BuildConfig.DEBUG) DebugLogger() else null)
            .build()
    }


    companion object {
        /** 供 root shell 的 PATH/BUSYBOX 使用：设备上若装了 APatch，这个目录里有 busybox。 */
        const val APATCH_FOLDER = "/data/adb/ap/"

        const val SP_NAME = "config"
        private const val SHOW_BACKUP_WARN = "show_backup_warning"
        private const val CRASH_COUNT_KEY = "fp_crash_count"
        private const val CRASH_TIMESTAMP_KEY = "fp_crash_timestamp"
        private const val CRASH_LOOP_THRESHOLD = 2
        private const val CRASH_WINDOW_MS = 30_000L
        lateinit var sharedPreferences: SharedPreferences

        /**
         * 应用级初始化是否完成（启动图关闭的信号）。
         *
         * 原来这里是 KernelPatch/AndroidPatch 的状态机（kpStateLiveData / apStateLiveData /
         * kpStateInitializedLiveData），启动图要等内核状态探测完才关。DSH-Folk 不打补丁，
         * 只需要一个「Application 初始化完成」的布尔量。
         */
        private val _initializedLiveData = MutableLiveData(false)
        val initializedLiveData: LiveData<Boolean> = _initializedLiveData

        private fun bypassHiddenApiRestrictions() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
            try {
                val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
                val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                    "getDeclaredMethod", String::class.java, Array<Any>::class.java
                )
                val vmRuntimeClass = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
                val getRuntime = getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", null) as java.lang.reflect.Method
                val setHiddenApiExemptions = getDeclaredMethod.invoke(
                    vmRuntimeClass, "setHiddenApiExemptions", arrayOf(Array<String>::class.java)
                ) as java.lang.reflect.Method
                val vmRuntime = getRuntime.invoke(null)
                setHiddenApiExemptions.invoke(vmRuntime, arrayOf("L"))
                Log.d(TAG, "Hidden API bypass applied successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bypass hidden API restrictions", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 没有 UserManager 的进程（例如 app-zygote）拿不到 SharedPreferences，
        // 这里直接跳过初始化。
        if (getSystemService(Context.USER_SERVICE) == null) {
            return
        }
        apApp = this
        sharedPreferences = getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

        // DSH 运行时的 Context 必须在任何 Composable 之前绑定：首页 composition
        // 期间就会读 runtimeId() / port()，只靠首页 LaunchedEffect 里的 attach()
        // 太晚（那时首帧已经在 measure 了），会撞未初始化的 lateinit 直接崩。
        me.bmax.apatch.dsh.DshRuntime.init(this)

        // 主题/音效/背景等配置必须在任何 Composable 读取之前同步载入
        MusicConfig.load(this)
        me.bmax.apatch.ui.theme.SoundEffectConfig.load(this)
        me.bmax.apatch.ui.theme.VibrationConfig.load(this)
        me.bmax.apatch.ui.theme.BackgroundConfig.load(this)
        me.bmax.apatch.ui.theme.FontConfig.load(this)
        me.bmax.apatch.util.ui.FloatingBarConfig.load(this)

        val processName = getProcessNameCompat()
        if (processName.endsWith(":root")) {
            return
        }
        // 背景音乐仅在主进程初始化：子进程若也初始化会创建第二个 MediaPlayer，
        // 与主进程实例重叠播放
        MusicManager.init(this)
        bypassHiddenApiRestrictions()
        Log.d(TAG, "APApplication onCreate started")

        val isArm64 = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
        Log.d(TAG, "Device architecture check: isArm64=$isArm64, supported ABIs=${Build.SUPPORTED_ABIS.joinToString(", ")}")
        if (!isArm64) {
            Log.e(TAG, "Unsupported architecture!")
            showToast(applicationContext, "Unsupported architecture!")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                exitProcess(0)
            }, 5000)
            return
        }

        // DSH-Folk 不做签名自校验：上游那份常量是 FolkPatch 官方 keystore 的指纹，
        // 本项目由使用者自行签名（CI 用仓库 secrets），校验必然失败并把自己弹去卸载。
        // 需要防篡改的用户请核对 Release 附带的 APK 校验和。

        if (!sharedPreferences.contains("app_initialized")) {
            sharedPreferences.edit()
                .putBoolean("app_initialized", true)
                .putBoolean("night_mode_enabled", true)
                .putBoolean("night_mode_follow_sys", true)
                .putBoolean("use_system_color_theme", true)
                .putString("custom_color", "indigo")
                .putString("home_layout_style", "dsh")
                .apply()
            // 首次安装部署内置仪表盘卡片壁纸
            me.bmax.apatch.ui.theme.BackgroundManager.provisionDefaultDashboardCardBg(this)
        }
        
        me.bmax.apatch.util.LauncherIconUtils.applySaved(this)

        Log.d(TAG, "Initializing OkHttpClient...")
        okhttpClient =
            OkHttpClient.Builder()
                .cache(Cache(File(cacheDir, "okhttp"), 10 * 1024 * 1024))
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .addInterceptor { block ->
                    block.proceed(
                        block.request().newBuilder()
                            .header("User-Agent", "DSH-Folk/${BuildConfig.VERSION_CODE}")
                            .header("Accept-Language", Locale.getDefault().toLanguageTag()).build()
                    )
                }.build()

        Log.d(TAG, "APApplication onCreate completed")

        sharedPreferences.edit()
            .remove(CRASH_COUNT_KEY)
            .remove(CRASH_TIMESTAMP_KEY)
            .apply()

        _initializedLiveData.postValue(true)
    }

    fun getBackupWarningState(): Boolean {
        return sharedPreferences.getBoolean(SHOW_BACKUP_WARN, true)
    }

    fun updateBackupWarningState(state: Boolean) {
        sharedPreferences.edit { putBoolean(SHOW_BACKUP_WARN, state) }
    }

    /**
     * Compatibility helper to get the current process name.
     * Application.getProcessName() is only available from API 28 (Android P).
     * On API 26-27, fall back to ActivityManager.getRunningAppProcesses().
     */
    private fun getProcessNameCompat(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName()
        }
        // Fallback for API 26-27
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return packageName
        val pid = Process.myPid()
        return am.runningAppProcesses?.find { it.pid == pid }?.processName ?: packageName
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        val exceptionMessage = Log.getStackTraceString(e)
        val threadName = t.name
        Log.e(TAG, "Error on thread $threadName:\n $exceptionMessage")

        val now = System.currentTimeMillis()
        val prefs = getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        val lastCrashTime = prefs.getLong(CRASH_TIMESTAMP_KEY, 0L)
        val crashCount = if (now - lastCrashTime < CRASH_WINDOW_MS) {
            prefs.getInt(CRASH_COUNT_KEY, 0) + 1
        } else {
            1
        }
        prefs.edit()
            .putInt(CRASH_COUNT_KEY, crashCount)
            .putLong(CRASH_TIMESTAMP_KEY, now)
            .commit()

        if (crashCount <= CRASH_LOOP_THRESHOLD) {
            val intent = Intent(this, CrashHandleActivity::class.java).apply {
                putExtra("exception_message", exceptionMessage)
                putExtra("thread", threadName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } else {
            Log.e(TAG, "Crash loop detected ($crashCount crashes in ${CRASH_WINDOW_MS}ms window). " +
                    "Skipping CrashHandleActivity to prevent infinite loop.")
        }
        exitProcess(10)
    }
}
