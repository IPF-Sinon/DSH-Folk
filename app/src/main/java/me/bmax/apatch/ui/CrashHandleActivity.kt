package me.bmax.apatch.ui

import android.content.ClipData
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.bmax.apatch.BuildConfig
import me.bmax.apatch.R
import me.bmax.apatch.dsh.ext.ExtensionEngine
import me.bmax.apatch.ui.theme.APatchTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.system.exitProcess

class CrashHandleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {

        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        super.onCreate(savedInstanceState)

        val appName = getString(R.string.app_name)
        val versionName = BuildConfig.VERSION_NAME
        val versionCode = BuildConfig.VERSION_CODE

        val deviceBrand = Build.BRAND
        val deviceModel = Build.MODEL
        val sdkLevel = Build.VERSION.SDK_INT
        val currentDateTime = Calendar.getInstance().time
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val formattedDateTime = formatter.format(currentDateTime)

        val exceptionMessage = intent.getStringExtra("exception_message")
        val threadName = intent.getStringExtra("thread")

        val message = buildString {
            append(appName).append(" version: ").append(versionName).append(" ($versionCode)")
                .append("\n\n")
            append("Brand: ").append(deviceBrand).append("\n")
            append("Model: ").append(deviceModel).append("\n")
            append("SDK Level: ").append(sdkLevel).append("\n")
            append("Time: ").append(formattedDateTime).append("\n\n")
            append("Thread: ").append(threadName).append("\n")
            append("Crash Info: \n").append(exceptionMessage)
        }

        setContent {
            APatchTheme {
                CrashHandleScreen(
                    message = message,
                    onDisableAllExtensions = { disableAllExtensionsAndRestart() },
                )
            }
        }
    }

    /**
     * 崩溃自救：禁用全部扩展并重启。
     *
     * 为什么先落标志、再重启：disableAllAndLatch 会把「禁用全部扩展」的标志写进文件系统，
     * 下次冷启动 [me.bmax.apatch.dsh.ext.ExtensionSafetyGuard.checkAndEnforce] 第一行就读到它。
     * 所以哪怕重启之后又崩在别处，扩展也仍然是关着的 —— 这条动作的效果不依赖本次进程活下去。
     *
     * 为什么先 startActivity 再 exitProcess：进程一退就没人再拉起界面了，必须趁还活着把
     * MainActivity 的启动意图交给系统；NEW_TASK + CLEAR_TASK 保证新起的是干净的一摞任务，
     * 崩溃页与残留的半死界面都不会留在返回栈里。
     */
    private fun disableAllExtensionsAndRestart() {
        ExtensionEngine.disableAllAndLatch(this, "用户在崩溃页面手动禁用全部扩展")
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        exitProcess(0)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CrashHandleScreen(
    message: String,
    /**
     * 点「禁用全部扩展并重启」时要做什么。
     *
     * 做成参数而不是在这里直接拿 Context 做：这一页刻意**不接任何扩展 UI**
     * （一个扩展注册的槽位都不取），所有动作都由 Activity 的原生代码提供，
     * 这样即便扩展把 Compose 环境搞坏，救援按钮也还是宿主的。
     */
    onDisableAllExtensions: () -> Unit = {},
) {
    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Scaffold(contentWindowInsets = WindowInsets.safeDrawing, topBar = {
        LargeTopAppBar(
            title = { Text(text = stringResource(R.string.crash_handle_title)) },
            scrollBehavior = scrollBehavior,
            windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        )
    }, floatingActionButton = {
        ExtendedFloatingActionButton(
            onClick = {
            scope.launch {
                clipboard.setClipEntry(
                    ClipEntry(ClipData.newPlainText("CrashLog", message)),
                )
            }
        }, text = { Text(text = stringResource(R.string.crash_handle_copy)) }, icon = {
            Icon(
                imageVector = Icons.Outlined.ContentCopy, contentDescription = null
            )
        }, containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 1f),
        contentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 1f),
        modifier = Modifier.windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.End)
        )
        )
    }) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(start = 16.dp, top = 12.dp, end = 16.dp),
        ) {
            // 救援按钮独立于正文的滚动区，放在最上面一行。
            // 为什么不能放进 SelectionContainer 里跟正文一起滚：崩溃栈动辄几百行，
            // 用户最需要这颗按钮的时候，它正好被滚到屏幕外看不见 —— 那等于没有。
            // 为什么是颜色 error 的纯原生 TextButton：这一页不接任何扩展 UI，
            // 扩展正是可疑对象，让扩展决定救援页长什么样，就是把逃生门交给要逃出来的那间屋子。
            TextButton(
                onClick = onDisableAllExtensions,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.dsh_ext_crash_disable_all))
            }
            Spacer(Modifier.height(4.dp))
            SelectionContainer(
                modifier = Modifier
                    // weight 而不是 fillMaxSize：正文占满按钮以下的剩余高度，自己滚自己的，
                    // 不会把 Column 撑出屏幕（撑出去的那部分连滚都滚不到）。
                    .weight(1f)
                    .fillMaxWidth()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .verticalScroll(rememberScrollState())
                    // 底部给「复制日志」那颗 FAB 留位：不留的话最后几行永远被它压住
                    .padding(bottom = 56.dp + 16.dp),
            ) {
                Text(
                    text = message, style = TextStyle(
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp
                    )
                )
            }
        }
    }
}

@Preview
@Composable
fun CrashHandleScreenPreview() {
    APatchTheme {
        CrashHandleScreen("Crash log here")
    }
}