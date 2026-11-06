package me.bmax.apatch

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import me.bmax.apatch.dsh.ext.ExtensionEngine
import me.bmax.apatch.dsh.ext.ExtensionSafetyGuard
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

    /**
     * 宿主自己的根崩溃处理器（就是本对象）。
     *
     * 为什么单独留一个具名引用：根处理器是**全进程唯一**的一个槽位，扩展与宿主跑在同一进程里，
     * 一句 Thread.setDefaultUncaughtExceptionHandler(...) 就能把它换成自己的 —— 那之后崩溃页、
     * 崩溃计数、扩展熔断全都不会再跑，用户看到的只剩「打开就闪退，什么都没有」。
     * 有了这个引用，[ensureRootCrashHandler] 才能在扩展加载之后把它复原回来。
     */
    private val hostCrashHandler: Thread.UncaughtExceptionHandler = this

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

        /**
         * 启动后多久算「这次启动成功了」（用于清零扩展的连续崩溃计数）。
         *
         * 取 8 秒：首帧早就画完、启动路径上该崩的也都崩过了；再长会把「启动即崩」的场景漏掉
         * —— 那时进程已经死了，定时器根本轮不到执行。
         */
        private const val STARTUP_GRACE_MS = 8_000L

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

        // ── 扩展系统：启动的第一件事 ──────────────────────────────────────────
        // ① 先查熔断标志（只做文件 stat，一个扩展字节码都不加载）。命中就把「已启用」集合
        //    清空并记住原因 —— 这是扩展把启动路径写坏后，用户唯一还走得通的逃生通道。
        //    必须排在 bootstrap 之前：反过来的话坏扩展已经在内存里跑起来了，守卫再快也晚了。
        ExtensionSafetyGuard.checkAndEnforce(this)

        // 没有 UserManager 的进程（例如 app-zygote）拿不到 SharedPreferences，
        // 这里直接跳过初始化。扩展也一并不加载：它的状态都存在 prefs 里，而那个进程是
        // root 通道的宿主、不是用户界面进程，不该让三方代码在里面跑 onLoad。
        if (getSystemService(Context.USER_SERVICE) == null) {
            return
        }
        apApp = this
        sharedPreferences = getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

        // ② 加载「已启用且未被熔断」的扩展。bootstrap 自己吞异常：扩展系统坏掉也不能让 App 起不来。
        ExtensionEngine.bootstrap(this)
        // ③ bootstrap 会跑扩展的 onLoad，而 onLoad 里可以换掉根崩溃处理器（见 [hostCrashHandler]）。
        //    这里立刻复原一次，保证后面所有崩溃仍然回到宿主手里。
        ensureRootCrashHandler()

        // DSH 运行时的 Context 必须在任何 Composable 之前绑定：首页 composition
        // 期间就会读 runtimeId() / port()，只靠首页 LaunchedEffect 里的 attach()
        // 太晚（那时首帧已经在 measure 了），会撞未初始化的 lateinit 直接崩。
        me.bmax.apatch.dsh.DshRuntime.init(this)

        // 权限策略迁移：旧模型（全局严格程度 + 信任名单）→ 新模型（限制模式 + 两张清单）。
        // 放在这里而不是 UI 里：它必须在任何一次能力判定之前跑完，而首页 composition 只是
        // "通常更早" —— 一个后台调用不该因为用户没打开首页就按旧键做判定。
        // 迁移只跑一次，并且**一律关**（用户拍板）；需要告知的人由 KEY_PRIV_POLICY_NOTICE 记着，
        // 由首页在更新内容弹窗关掉之后弹一次说明（见 Home.kt）。
        me.bmax.apatch.dsh.PrivPolicy.migrateOnce(this)

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

        // 支持 arm64-v8a 与 x86_64（后者面向模拟器 / Android-x86 / ChromeOS）。
        // 两者都不在才退出：APK 按 ABI 拆包，装错架构的包会缺 proot 可执行 .so，
        // 与其让容器启动时报 Exec format error，不如在这里明确拒绝。
        val abis = Build.SUPPORTED_ABIS
        val archSupported = abis.any { it == "arm64-v8a" || it == "x86_64" }
        Log.d(TAG, "Device architecture check: supported=$archSupported, ABIs=${abis.joinToString(", ")}")
        if (!archSupported) {
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

        // 启动成功就清掉「扩展连续崩溃」计数：不清的话，偶发的一次崩溃会一直攒在盘上，
        // 某天正常启动时突然被判成崩溃循环，全部扩展被禁用 —— 用户完全莫名其妙。
        // 为什么不是在这里立刻清：onCreate 跑到底只说明初始化过程没抛，首帧 composition
        // 与服务拉起还在后头（扩展的 onLoad 也可能到那时才动手）。8 秒是个保守的经验值：
        // 真会在启动路径上崩的，那时早就崩了；期间真崩了由 uncaughtException 先记一次，
        // 不会被这里抹掉。
        Handler(Looper.getMainLooper()).postDelayed({
            ExtensionSafetyGuard.resetCrashCount(this)
        }, STARTUP_GRACE_MS)
    }

    /**
     * 把根崩溃处理器复原成宿主自己的。
     *
     * 扩展的 onLoad 是三方代码，换掉根处理器只需要一行；换掉之后崩溃页与崩溃计数都不会再跑，
     * 用户看到的是「闪退，连崩溃页都没有」。所以 bootstrap 之后立刻校验一次。
     */
    private fun ensureRootCrashHandler() {
        if (Thread.getDefaultUncaughtExceptionHandler() !== hostCrashHandler) {
            Log.w(TAG, "根崩溃处理器被替换过（多半来自扩展），已复原为宿主自己的")
            Thread.setDefaultUncaughtExceptionHandler(hostCrashHandler)
        }
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

    /**
     * 这次崩溃是不是扩展引起的。
     *
     * 两条判据都要，因为它们挡的是两种不同的伪装：
     *  - 栈文本：能认出「借道扩展包里的类」的崩溃（[ExtensionSafetyGuard.stackTouchesExtension]）；
     *  - 类加载器：扩展可以用 try/catch 把栈洗白，但换不掉自己的 ClassLoader —— 扩展字节码由
     *    ExtensionLoader 里的 DexClassLoader 从 files/extensions/ 下加载，加载器的描述里就带着
     *    那个路径，而宿主自己的类全部来自应用自身的 PathClassLoader。
     */
    private fun crashTouchesExtension(e: Throwable, trace: String): Boolean {
        if (ExtensionSafetyGuard.stackTouchesExtension(trace)) return true
        var cur: Throwable? = e
        var depth = 0
        // 只顺着 cause 链看有限几层：循环引用（e.cause == e）在真实崩溃里出现过，
        // 无上限的 while 会把崩溃处理器本身卡死 —— 那是最后一次能记日志的机会。
        while (cur != null && depth < 8) {
            val loader = cur.javaClass.classLoader
            if (loader != null && loader !== javaClass.classLoader) {
                val desc = runCatching { loader.toString() }.getOrDefault("")
                if (desc.contains("/extensions/")) return true
            }
            cur = cur.cause
            depth++
        }
        return false
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        val exceptionMessage = Log.getStackTraceString(e)
        val threadName = t.name
        Log.e(TAG, "Error on thread $threadName:\n $exceptionMessage")

        // 扩展的崩溃要单独记一笔：下面那套 CRASH_COUNT_KEY 只决定「这次要不要弹崩溃页」，
        // 而扩展要的是「连续崩几次就熔断」。不单独计数的话，一个把启动路径写坏的扩展会让
        // 用户永远停在「打开就闪退」，崩溃页根本来不及点 —— 唯一的自救入口就没了。
        if (crashTouchesExtension(e, exceptionMessage)) {
            val latched = ExtensionSafetyGuard.noteCrash(
                this,
                e.javaClass.simpleName + " on " + threadName,
            )
            if (latched) {
                Log.w(TAG, "扩展连续崩溃已达阈值，已停用全部扩展并写入禁用标志")
            }
        }

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
