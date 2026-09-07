package me.bmax.apatch.util

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * 权限管理工具类
 */
object PermissionUtils {
    
    /**
     * 检查是否有外部存储权限
     */
    fun hasExternalStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13及以上版本使用READ_MEDIA_IMAGES权限
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Android 12及以下版本使用READ_EXTERNAL_STORAGE权限
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * 检查是否有写入外部存储权限
     */
    fun hasWriteExternalStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10及以上版本不需要WRITE_EXTERNAL_STORAGE权限来写入应用专有目录
            true
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * 启动时申请的权限。
     *
     * 只申请**启动就用得上**的那些：前台服务通知，以及主题/背景选图要读的图片。
     * 媒体的视频/音频与麦克风不在这里 —— 它们属于原生能力桥的分项，勾选那一项时才申请，
     * 开局就弹一串权限框只会让人整片拒绝。「所有文件访问」更不在这里：它是 appop
     * 特殊权限，申请不到，且那个系统页面对首次启动的用户过于劝退。
     *
     * 三段分支必须与 AndroidManifest 里的 `maxSdkVersion` 对齐：申请一个当前 SDK 上
     * 没有声明的权限，系统直接判拒，`allGranted` 永远为 false，于是每次启动都走 onDenied。
     */
    fun getRequiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        // Android 11/12：WRITE_EXTERNAL_STORAGE 在分区存储下已无作用，manifest 里
        // 也只声明到 29，这里跟着只要 READ
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
        )
        else -> arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )
    }

    /** 前台服务通知是否能显示（Android 13 以下恒为 true）。 */
    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            granted(context, Manifest.permission.POST_NOTIFICATIONS)

    // ───────────────────── 回环桥用到的权限 ─────────────────────
    //
    // 这一组给 DshNativeBridge / DshFsBridge 判「这项能力现在真的能用吗」。
    // 与「用户开没开开关」严格分开：开关是意愿，这里是系统事实。

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * 是否拿到了整个共享存储的读写。
     *
     * Android 11 起这是一个 **appop 特殊权限**（`MANAGE_EXTERNAL_STORAGE` 的
     * protectionLevel 是 `signature|appop|preinstalled`），`requestPermissions()`
     * 申请不到，只能把用户送去 `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`
     * 那个系统页面（见「功能」设置页的共享存储那一行）。
     *
     * Android 10 及以下没有这个 API（[Environment.isExternalStorageManager] 是 API 30
     * 才有的），必须走 SDK 分支：**直接调用会在 minSdk 26 的机器上抛
     * NoSuchMethodError**，而那些系统本来靠 READ/WRITE_EXTERNAL_STORAGE 就够了。
     */
    fun hasAllFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        } else {
            granted(context, Manifest.permission.READ_EXTERNAL_STORAGE) &&
                granted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

    /** 媒体类型：与 `/native/media/list` 等端点的 `type` 参数一一对应。 */
    enum class MediaType(val id: String) {
        IMAGE("image"),
        VIDEO("video"),
        AUDIO("audio"),
    }

    /**
     * 读某一类媒体需要的权限。
     *
     * Android 13 把 READ_EXTERNAL_STORAGE 拆成了三个按类型的权限，13 以下只有一个。
     */
    fun mediaPermission(type: MediaType): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when (type) {
                MediaType.IMAGE -> Manifest.permission.READ_MEDIA_IMAGES
                MediaType.VIDEO -> Manifest.permission.READ_MEDIA_VIDEO
                MediaType.AUDIO -> Manifest.permission.READ_MEDIA_AUDIO
            }
        } else {
            @Suppress("DEPRECATION")
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun hasMediaPermission(context: Context, type: MediaType): Boolean =
        granted(context, mediaPermission(type))

    /** 至少能读一类媒体。用户可能只给了照片而没给音频，那也算这项能力可用。 */
    fun hasAnyMediaPermission(context: Context): Boolean =
        MediaType.entries.any { hasMediaPermission(context, it) }

    /** 已经能读的媒体类型；`/native/media/list` 用它告诉调用方还差什么。 */
    fun grantedMediaTypes(context: Context): List<MediaType> =
        MediaType.entries.filter { hasMediaPermission(context, it) }

    /** 全部媒体读权限（去重）：申请时一次性请。 */
    fun mediaPermissions(): Array<String> =
        MediaType.entries.map { mediaPermission(it) }.distinct().toTypedArray()

    fun hasMicrophonePermission(context: Context): Boolean =
        granted(context, Manifest.permission.RECORD_AUDIO)

    fun hasCameraPermission(context: Context): Boolean =
        granted(context, Manifest.permission.CAMERA)

    /** 日历要读也要写：`/native/calendar/create` 建事件，list 只读。 */
    fun calendarPermissions(): Array<String> = arrayOf(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )

    fun hasCalendarReadPermission(context: Context): Boolean =
        granted(context, Manifest.permission.READ_CALENDAR)

    fun hasCalendarWritePermission(context: Context): Boolean =
        granted(context, Manifest.permission.WRITE_CALENDAR)

    fun hasContactsPermission(context: Context): Boolean =
        granted(context, Manifest.permission.READ_CONTACTS)

    /**
     * 位置权限：COARSE 与 FINE 一起申请。
     *
     * Android 12 起权限弹窗给用户三个选项（精确 / 大致 / 拒绝），只申请 FINE 时选
     * 「大致」也只会授予 COARSE —— 所以两个都要申请、也都要检查。不申请
     * ACCESS_BACKGROUND_LOCATION：它必须在拿到前台位置**之后**单独发起，且会把用户
     * 送进系统设置页选「始终允许」，而这项能力本来就要求应用在前台。
     */
    fun locationPermissions(): Array<String> = arrayOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )

    /** 有任一档位置权限就算可用；精度差别由 `/native/location` 的 `precise` 字段说明。 */
    fun hasLocationPermission(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_COARSE_LOCATION) ||
            granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    /** 是否拿到了**精确**位置（Android 12 起用户可能只给了大致位置）。 */
    fun hasPreciseLocationPermission(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPhoneStatePermission(context: Context): Boolean =
        granted(context, Manifest.permission.READ_PHONE_STATE)

    /**
     * 传感器权限。
     *
     * 绝大多数传感器（加速度、陀螺、光、气压、磁场…）**不需要任何权限**，只有两类要：
     * 心率之类的人体传感器要 BODY_SENSORS，计步器/计步检测在 Android 10 起要
     * ACTIVITY_RECOGNITION。所以这项能力在没有这两个权限时依然可用（只是列不到那几个），
     * 权限提示是「想读心率/计步还缺这个」而不是「这项功能不可用」。
     */
    fun sensorPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(Manifest.permission.BODY_SENSORS, Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            arrayOf(Manifest.permission.BODY_SENSORS)
        }

    fun hasBodySensorsPermission(context: Context): Boolean =
        granted(context, Manifest.permission.BODY_SENSORS)

    fun hasActivityRecognitionPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            granted(context, Manifest.permission.ACTIVITY_RECOGNITION)

    // ─────────────── 特殊权限（申请不到，只能跳系统页） ───────────────

    /**
     * 能不能改系统设置（亮度、休眠时间、自动旋转…）。
     *
     * `WRITE_SETTINGS` 的 protectionLevel 含 `appop`，`requestPermissions()` 永远拿不到
     * —— 必须把用户送去 [android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS]。
     * 检查也不能用 checkSelfPermission：那只反映 manifest 里声明了，用户是否在那个
     * 页面上打开了开关得问 `Settings.System.canWrite()`。
     */
    fun canWriteSystemSettings(context: Context): Boolean =
        runCatching { Settings.System.canWrite(context) }.getOrDefault(false)

    /**
     * 能不能改勿扰策略。
     *
     * 影响的不只是「开勿扰」：勿扰开着时调音量、以及把响铃模式设成静音/振动，
     * 都要求这项授权，否则 `setRingerMode` 静默无效或抛 SecurityException。
     * 同样是特殊权限，入口是 [android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS]。
     */
    fun hasNotificationPolicyAccess(context: Context): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted
            ?: false
    }.getOrDefault(false)

    /**
     * 能不能请求安装其他应用。
     *
     * `REQUEST_INSTALL_PACKAGES` 在 Android 8 起也是一项用户可撤销的**特殊**权限
     * （「安装未知应用」），`canRequestPackageInstalls()` 才是真相；应用内更新正是走这条。
     */
    fun canRequestPackageInstalls(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
        } else {
            true
        }
}

/**
 * 权限请求处理器
 */
class PermissionRequestHandler(private val activity: ComponentActivity) {
    
    private var onPermissionGranted: (() -> Unit)? = null
    private var onPermissionDenied: (() -> Unit)? = null
    
    private val permissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            onPermissionGranted?.invoke()
        } else {
            onPermissionDenied?.invoke()
        }
    }
    
    /**
     * 请求权限
     */
    fun requestPermissions(
        onGranted: () -> Unit,
        onDenied: () -> Unit
    ) {
        onPermissionGranted = onGranted
        onPermissionDenied = onDenied
        
        val permissions = PermissionUtils.getRequiredPermissions()
        permissionLauncher.launch(permissions)
    }
}