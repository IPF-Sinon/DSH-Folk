package me.bmax.apatch.ui.screen

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DisplayServer
import me.bmax.apatch.dsh.DisplayVideoSink
import kotlin.math.hypot

/**
 * 虚拟屏预览：一边看画面，一边在画面上点/滑。
 *
 * ## 和 agent 工具面是同一条路
 *
 * 这里用的是 [DisplayServer] 那一套（推 jar → 特权拉起 → Binder 交接 → `setVideoSink`），
 * 与 `/native/display/…` 是同一个服务端、同一份会话状态。所以这个界面既是给人看的，也是一个
 * 排障口：agent 那边"截得到图却点不动"时，打开这里就能看出是画面没来、还是输入没进去。
 *
 * ## 手势怎么变成注入
 *
 * 预览是**缩放过**的：SurfaceView 的尺寸不等于虚拟屏的像素尺寸。`IDisplayService` 的坐标是
 * 显示像素空间，所以这里必须按比例换算 —— 直接把手势的 view 坐标发过去，在缩放比不是 1 的
 * 设备上会"点哪儿都不准"，而且不报错，极难自查。
 *
 * 按下到抬起的位移小算单击、否则算滑动：和用户的心智一致，不必让他先选模式。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun DisplayPreviewScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current

    var state by remember { mutableStateOf(context.getString(R.string.dsh_display_preview_starting)) }
    var displayId by remember { mutableStateOf(-1) }
    var displayW by remember { mutableStateOf(0) }
    var displayH by remember { mutableStateOf(0) }
    var launchedText by remember { mutableStateOf("") }

    // SurfaceView 的 Surface 与"会话是否就绪"是两条独立的到达路径（谁先谁后不确定），
    // 所以两边各自记录，齐了就挂上。解码器必须等 Surface 真的在才能 configure ——
    // configure 那一刻输出目标就绑死了，之后再给 Surface 是无效的。
    var surface by remember { mutableStateOf<Surface?>(null) }
    var viewW by remember { mutableStateOf(0) }
    var viewH by remember { mutableStateOf(0) }
    var sink by remember { mutableStateOf<DisplayVideoSink?>(null) }

    // 起服务端 + 建虚拟屏。两步都是阻塞的特权操作，放 IO 上，别卡住首帧。
    LaunchedEffect(Unit) {
        val dm = context.resources.displayMetrics
        val result = withContext(Dispatchers.IO) {
            DisplayServer.startSession(context, dm.widthPixels, dm.heightPixels, dm.densityDpi)
        }
        result.onSuccess { s ->
            displayId = s.displayId
            displayW = s.width
            displayH = s.height
            // 解码器按**会话的真实尺寸**建：createVideoFormat(0,0) 会 configure 失败，
            // 而这里拿到的宽高是服务端对齐之后真正会编出来的那个尺寸附近的值，
            // 真实值由 SPS 决定，onOutputFormatChanged 会回报。
            sink = DisplayVideoSink(
                requestedWidth = s.width,
                requestedHeight = s.height,
                onError = { state = it },
                onSize = { w, h ->
                    displayW = w
                    displayH = h
                },
            )
            state = context.getString(R.string.dsh_display_preview_session, s.displayId, s.width, s.height)
        }.onFailure { e ->
            state = e.message ?: context.getString(R.string.dsh_display_preview_start_failed)
        }
    }

    // Surface 与解码器都就绪 → 挂上并开始收帧
    LaunchedEffect(surface, sink, displayId) {
        val s = surface ?: return@LaunchedEffect
        val decoder = sink ?: return@LaunchedEffect
        if (displayId < 0) return@LaunchedEffect
        decoder.attach(s)
        val id = displayId
        val r = withContext(Dispatchers.IO) { DisplayServer.setVideoSink(context, id, decoder.asBinder()) }
        r.onSuccess { state = context.getString(R.string.dsh_display_preview_connected, id) }
            .onFailure { e -> state = e.message ?: context.getString(R.string.dsh_display_preview_start_failed) }
    }

    // 离开时摘掉 sink：不摘的话服务端会一直往一个没人看的解码器推帧
    DisposableEffect(Unit) {
        onDispose {
            val id = displayId
            val decoder = sink
            Thread {
                if (id >= 0) runCatching { DisplayServer.setVideoSink(context, id, null) }
                decoder?.release()
            }.start()
        }
    }

    val toDisplayX: (Float) -> Float = { x -> if (viewW > 0) x * displayW / viewW else x }
    val toDisplayY: (Float) -> Float = { y -> if (viewH > 0) y * displayH / viewH else y }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dsh_display_preview_title)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            Text(state, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(displayId, viewW, viewH) {
                            // 一个手势循环同时判定单击与滑动。用两个 pointerInput 会互相吃事件
                            // （tap 检测器先消费 down），分成两段反而两个都不准。
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val startedAt = System.currentTimeMillis()
                                val from = down.position
                                val to = waitForUpOrCancellation()?.position ?: from
                                val id = displayId
                                if (id < 0 || viewW == 0) return@awaitEachGesture
                                val moved = hypot((to.x - from.x).toDouble(), (to.y - from.y).toDouble())
                                val duration = (System.currentTimeMillis() - startedAt).coerceIn(20L, 10_000L)
                                val x1 = toDisplayX(from.x)
                                val y1 = toDisplayY(from.y)
                                val x2 = toDisplayX(to.x)
                                val y2 = toDisplayY(to.y)
                                Thread {
                                    runCatching {
                                        val svc = DisplayServer.current() ?: return@runCatching
                                        if (moved < TAP_SLOP_PX) svc.tap(id, x1, y1) else svc.swipe(id, x1, y1, x2, y2, duration)
                                    }
                                }.start()
                            }
                        },
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    surface = holder.surface
                                }

                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                                    viewW = w
                                    viewH = h
                                }

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    surface = null
                                    val id = displayId
                                    val decoder = sink
                                    // Surface 没了就别再收帧了；留着 sink 只会让服务端白推
                                    Thread {
                                        if (id >= 0) runCatching { DisplayServer.setVideoSink(context, id, null) }
                                        decoder?.release()
                                    }.start()
                                }
                            })
                        }
                    },
                )
                if (displayId < 0) CircularProgressIndicator()
            }

            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.dsh_display_preview_hint) +
                    if (displayW > 0) {
                        stringResource(R.string.dsh_display_preview_size, displayW, displayH)
                    } else {
                        ""
                    },
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { sendKey(displayId, KEY_BACK) }) {
                    Text(stringResource(R.string.dsh_display_preview_key_back))
                }
                TextButton(onClick = { sendKey(displayId, KEY_HOME) }) {
                    Text(stringResource(R.string.dsh_display_preview_key_home))
                }
                TextButton(onClick = { sendKey(displayId, KEY_RECENTS) }) {
                    Text(stringResource(R.string.dsh_display_preview_key_recents))
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = launchedText,
                    onValueChange = { launchedText = it },
                    label = { Text(stringResource(R.string.dsh_display_preview_pkg_label)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = displayId >= 0 && launchedText.isNotBlank(),
                    onClick = {
                        val pkg = launchedText.trim()
                        val id = displayId
                        Thread { runCatching { DisplayServer.current()?.launchApp(pkg, id) } }.start()
                    },
                ) { Text(stringResource(R.string.dsh_display_preview_launch)) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 超过这个位移就算滑动而不是单击（与 Compose 的 touch slop 同一个量级）。 */
private const val TAP_SLOP_PX = 24.0

private const val KEY_BACK = 4
private const val KEY_HOME = 3
private const val KEY_RECENTS = 187

/** 按键注入。阻塞的 Binder 调用，放后台线程。 */
private fun sendKey(displayId: Int, keyCode: Int) {
    if (displayId < 0) return
    Thread { runCatching { DisplayServer.current()?.injectKey(displayId, keyCode) } }.start()
}
