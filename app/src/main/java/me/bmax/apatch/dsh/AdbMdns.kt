package me.bmax.apatch.dsh

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 用系统 [NsdManager] 发现无线调试「连接端口」。
 *
 * ## 为什么必须在 App 侧做
 *
 * Android 11+ 的无线调试里，配对成功后 adbd 真正可连的那个端口，是系统**另行**通过 mDNS
 * 服务 `_adb-tls-connect._tcp` 广播的**动态**端口 —— 它既不是配对端口（`_adb-tls-pairing._tcp`），
 * 也不是开发者选项界面上显示的那个数，而且每次重开无线调试都会变。让用户手填这个端口几乎
 * 必然是错的，连 adbd 一律被拒（Connection refused）。
 *
 * 原来的做法是在 proot 容器里用 Python zeroconf 收组播，但容器收不到：Android 的组播要
 * 持有 `MulticastLock`（容器内拿不到），且容器网络受限。Shizuku 的做法是用系统
 * [NsdManager]（AdbMdns.java）—— 系统服务本身替进程持多播锁、也不需要额外权限
 * （只要 `INTERNET`，和 Shizuku 一致）。这个类照抄这条思路。
 *
 * ## 用法
 *
 * ```
 * val port = AdbMdns(ctx).discoverConnectPort(timeoutMs = 5000)  // 挂起，发现到返回端口，超时/失败 null
 * ```
 *
 * 一次性：发现到第一个合法端口就返回并停止发现。调用方（配对流程）在「用户没手填连接端口」
 * 时用它补上真实端口。低于 Android 11（R）不要调用它 —— 无线调试本身就是 11+，调用方应回退
 * 到旧的「脚本端口自愈」路径。
 */
@RequiresApi(Build.VERSION_CODES.R)
class AdbMdns(private val context: Context) {

    companion object {
        private const val TAG = "AdbMdns"

        /** 无线调试「连接端口」的 mDNS 服务类型（与「配对端口」`_adb-tls-pairing._tcp` 不同）。 */
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
    }

    /**
     * 发现一个可用的连接端口。
     *
     * 回调式的 [NsdManager] 封成一次性挂起接口：发现到并解析出合法端口（1..65535）即 `resume`
     * 该端口；[timeoutMs] 内没结果返回 null。任何异常都吞掉返回 null —— 发现失败只是回退到手填/
     * 脚本自愈，不该把异常抛给调用方。
     *
     * 取消 / 超时都会 `stopServiceDiscovery` 释放系统侧的发现会话，不留悬挂监听。
     *
     * 注意：部分 ROM 的 [NsdManager.resolveService] 同一时刻只允许一个在途请求，所以这里**串行**
     * 解析（[resolving] 这把软锁没放开前，后来的服务一律跳过），避免触发
     * `FAILURE_ALREADY_ACTIVE`。
     */
    suspend fun discoverConnectPort(timeoutMs: Long = 5000): Int? =
        withTimeoutOrNull(timeoutMs) { awaitPort() } ?: run {
            Log.i(TAG, "connect-port discovery timed out after ${timeoutMs}ms")
            null
        }

    private suspend fun awaitPort(): Int? = suspendCancellableCoroutine { cont ->
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) {
            Log.w(TAG, "NsdManager unavailable")
            if (cont.isActive) cont.resume(null)
            return@suspendCancellableCoroutine
        }

        // 发现与解析都在 NsdManager 的 binder 线程回调上触发，可能并发，用原子量兜住：
        // - finished：只允许 resume 一次、只允许 stopServiceDiscovery 一次；
        // - resolving：resolveService 串行化（部分 ROM 不允许并发在途请求）。
        val finished = AtomicBoolean(false)
        val resolving = AtomicBoolean(false)
        var listener: NsdManager.DiscoveryListener? = null

        fun stop() {
            val l = listener ?: return
            runCatching { nsd.stopServiceDiscovery(l) }
        }

        fun finish(port: Int?) {
            if (!finished.compareAndSet(false, true)) return
            stop()
            if (cont.isActive) cont.resume(port)
        }

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.w(TAG, "resolve failed: err=$errorCode name=${serviceInfo?.serviceName}")
                resolving.set(false)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                resolving.set(false)
                val port = serviceInfo.port
                if (port in 1..65535) {
                    Log.i(TAG, "resolved connect port=$port host=${serviceInfo.host?.hostAddress}")
                    finish(port)
                } else {
                    Log.w(TAG, "resolved service with out-of-range port=$port")
                }
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w(TAG, "start discovery failed: err=$errorCode")
                finish(null)
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w(TAG, "stop discovery failed: err=$errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                Log.i(TAG, "discovery started for $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                Log.i(TAG, "discovery stopped for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (finished.get()) return
                // resolveService 串行：有在途解析时跳过，等它失败/成功再轮到下一个。
                if (!resolving.compareAndSet(false, true)) return
                runCatching { nsd.resolveService(serviceInfo, resolveListener) }
                    .onFailure {
                        Log.w(TAG, "resolveService threw: ${it.message}")
                        resolving.set(false)
                    }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                // 丢失不影响：还没解析出端口的话继续等别的服务
            }
        }

        listener = discoveryListener
        cont.invokeOnCancellation {
            // 超时 / 外部取消：释放发现会话（finished 置位避免再 resume）
            finished.set(true)
            stop()
        }

        runCatching {
            nsd.discoverServices(TLS_CONNECT, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }.onFailure {
            Log.w(TAG, "discoverServices threw: ${it.message}")
            finish(null)
        }
    }
}
