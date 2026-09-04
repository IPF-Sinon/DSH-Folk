package me.bmax.apatch.dsh

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import me.bmax.apatch.R
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 无预览拍照（`/native/camera/photo`）。
 *
 * 单独一个文件是因为 Camera2 的「开设备 → 建会话 → 发请求 → 收图」四段异步流程谁都
 * 绕不过，塞进 [DshNativeBridge] 会把那个文件变成一锅粥。
 *
 * ## 为什么是 Camera2 而不是 `ACTION_IMAGE_CAPTURE`
 *
 * 拉起系统相机要用户自己按快门 —— 那不是「agent 拍一张」，那是「agent 请用户拍一张」。
 * Camera2 能在没有任何预览 Surface 的情况下直接出图，代价是这段样板代码。
 *
 * ## 为什么要求前台
 *
 * Android 9 起后台进程打开相机会拿到 `CameraAccessException`（ERROR_CAMERA_DISABLED）
 * 或者一路黑帧 —— 系统按前后台裁决，与权限无关。所以后台直接回
 * `409 not_foreground`，而不是交一张黑图。
 *
 * ## 为什么要热身几帧
 *
 * 相机刚开时自动曝光/白平衡还没收敛，第一帧经常是全黑或惨白的。这里发**重复**请求、
 * 丢掉前几帧只留最后一张（[WARMUP_FRAMES]）—— 这是无预览拍照唯一可靠的收敛办法，
 * 单发一张 STILL_CAPTURE 在多数机型上就是一张黑图。
 */
internal object DshCamera {
    private const val TAG = "DshCamera"

    /** 丢掉的热身帧数：等 AE/AWB 收敛。第 [WARMUP_FRAMES] 帧才是要保留的那张。 */
    private const val WARMUP_FRAMES = 5

    /** 整个流程的上限。超时一律按失败处理并释放相机，绝不让连接线程挂死。 */
    private const val TIMEOUT_MS = 12_000L

    /** 输出长边上限；再大对 agent 没有意义，只是把容器 /tmp 撑爆。 */
    private const val DEFAULT_MAX_DIM = 1920
    private const val MAX_MAX_DIM = 4096

    /** 相机是独占资源：并发请求必须排队而不是互相抢。 */
    private val capturing = AtomicBoolean(false)

    fun photo(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        if (!DshNativeBridge.isForeground(ctx)) {
            return 409 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_camera_background),
                "not_foreground",
            )
        }
        if (!capturing.compareAndSet(false, true)) {
            return 409 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_camera_busy),
                "already_capturing",
            )
        }
        try {
            return doPhoto(ctx, params)
        } finally {
            capturing.set(false)
        }
    }

    /** 设备上有没有可用的相机。能力可用性判断用。 */
    fun hasCamera(ctx: Context): Boolean = runCatching {
        val mgr = ctx.getSystemService(CameraManager::class.java) ?: return false
        mgr.cameraIdList.isNotEmpty()
    }.getOrDefault(false)

    private fun doPhoto(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val mgr = ctx.getSystemService(CameraManager::class.java)
            ?: return 500 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_no_service, "CameraManager"),
                "no_service",
            )
        val wantFront = (params["facing"] ?: "back").lowercase() == "front"
        val camId = pickCamera(mgr, wantFront)
            ?: return 404 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_camera_none),
                "no_camera",
            )
        val maxDim = (params["max"]?.toIntOrNull() ?: DEFAULT_MAX_DIM).coerceIn(320, MAX_MAX_DIM)

        val chars = runCatching { mgr.getCameraCharacteristics(camId) }.getOrNull()
            ?: return 500 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_camera_open, camId),
                "camera_failed",
            )
        val size = pickSize(chars, maxDim)
            ?: return 500 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_camera_no_size),
                "camera_failed",
            )

        val thread = HandlerThread("dsh-camera").apply { start() }
        val handler = Handler(thread.looper)
        // maxImages 要 > 1：重复请求在我们处理上一帧时可能已经把下一帧塞进队列，
        // 只给 1 会让 HAL 直接丢帧、热身永远走不完。
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 3)

        var jpeg: ByteArray? = null
        var frames = 0
        val done = CountDownLatch(1)
        var failure: String? = null

        reader.setOnImageAvailableListener({ r ->
            // 每一帧都必须 close，否则 3 张之后队列满、再也不出图
            val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                frames++
                if (frames >= WARMUP_FRAMES) {
                    val buf = img.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    jpeg = bytes
                    done.countDown()
                }
            } finally {
                runCatching { img.close() }
            }
        }, handler)

        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        try {
            val opened = CountDownLatch(1)
            runCatching {
                mgr.openCamera(camId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        device = camera
                        opened.countDown()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        runCatching { camera.close() }
                        failure = "camera_disconnected"
                        opened.countDown()
                        done.countDown()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        runCatching { camera.close() }
                        failure = "camera_error_$error"
                        opened.countDown()
                        done.countDown()
                    }
                }, handler)
            }.onFailure {
                Log.w(TAG, "openCamera 失败: ${it.message}")
                failure = "camera_failed"
            }

            if (failure == null) {
                runCatching { opened.await(TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                val dev = device
                if (dev == null) {
                    failure = failure ?: "open_timeout"
                } else {
                    @Suppress("DEPRECATION")
                    runCatching {
                        dev.createCaptureSession(
                            listOf(reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    session = s
                                    val req = dev.createCaptureRequest(
                                        CameraDevice.TEMPLATE_STILL_CAPTURE
                                    ).apply {
                                        addTarget(reader.surface)
                                        set(
                                            CaptureRequest.CONTROL_AE_MODE,
                                            CaptureRequest.CONTROL_AE_MODE_ON,
                                        )
                                        set(
                                            CaptureRequest.CONTROL_AWB_MODE,
                                            CaptureRequest.CONTROL_AWB_MODE_AUTO,
                                        )
                                        set(
                                            CaptureRequest.CONTROL_AF_MODE,
                                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                                        )
                                        // JPEG 里带上方向，否则竖拍出来是躺着的
                                        chars.get(CameraCharacteristics.SENSOR_ORIENTATION)?.let {
                                            set(CaptureRequest.JPEG_ORIENTATION, it)
                                        }
                                    }.build()
                                    runCatching { s.setRepeatingRequest(req, null, handler) }
                                        .onFailure { e ->
                                            Log.w(TAG, "setRepeatingRequest 失败: ${e.message}")
                                            failure = "capture_failed"
                                            done.countDown()
                                        }
                                }

                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    failure = "session_failed"
                                    done.countDown()
                                }
                            },
                            handler,
                        )
                    }.onFailure {
                        Log.w(TAG, "createCaptureSession 失败: ${it.message}")
                        failure = "session_failed"
                        done.countDown()
                    }
                    runCatching { done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                }
            }
        } finally {
            // 顺序有讲究：先停会话再关设备，反过来会在 logcat 里刷一串 abort。
            runCatching { session?.stopRepeating() }
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { reader.close() }
            thread.quitSafely()
        }

        val bytes = jpeg
        if (bytes == null || bytes.isEmpty()) {
            return 500 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_camera_capture),
                failure ?: "capture_timeout",
            )
        }
        // 拍完再确认一次前台：中途切走那几帧多半是黑的
        if (!DshNativeBridge.isForeground(ctx)) {
            return 409 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_camera_background),
                "not_foreground",
            )
        }

        val dir = DshNativeBridge.stageDir(ctx)
        val out = File(dir, "cam_${System.currentTimeMillis()}.jpg")
        val written = runCatching { out.writeBytes(bytes); true }.getOrDefault(false)
        if (!written) {
            runCatching { out.delete() }
            return 500 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_camera_write),
                "write_failed",
            )
        }
        DshNativeBridge.trimStage(dir)
        return 200 to org.json.JSONObject()
            .put("ok", true)
            .put("path", DshNativeBridge.stageGuestPath(out.name))
            .put("bytes", bytes.size)
            .put("width", size.width)
            .put("height", size.height)
            .put("facing", if (wantFront) "front" else "back")
            .toString()
    }

    /**
     * 选一个摄像头。
     *
     * 优先按朝向匹配；匹配不到就退回第一个 —— 平板/模拟器上经常只有一个摄像头，
     * 「没有前摄」不该让整个请求失败。
     */
    private fun pickCamera(mgr: CameraManager, wantFront: Boolean): String? = runCatching {
        val ids = mgr.cameraIdList
        if (ids.isEmpty()) return null
        val want = if (wantFront) CameraCharacteristics.LENS_FACING_FRONT
        else CameraCharacteristics.LENS_FACING_BACK
        ids.firstOrNull {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want
        } ?: ids.first()
    }.getOrNull()

    /** 不超过 [maxDim] 的最大 JPEG 尺寸；全都超了就取最小的那个。 */
    private fun pickSize(chars: CameraCharacteristics, maxDim: Int): Size? = runCatching {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val sizes = map.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        if (sizes.isEmpty()) return null
        val area = { s: Size -> s.width.toLong() * s.height }
        val fits = sizes.filter { maxOf(it.width, it.height) <= maxDim }
        // 有合规尺寸就取其中最大的（画质优先）；一个都没有说明这机器的最小 JPEG
        // 尺寸都超了上限，那就取全局最小，而不是失败。
        if (fits.isNotEmpty()) fits.maxByOrNull(area) else sizes.minByOrNull(area)
    }.getOrNull()
}
