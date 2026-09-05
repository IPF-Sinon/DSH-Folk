package me.bmax.apatch.ui.component

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import me.bmax.apatch.BuildConfig
import me.bmax.apatch.R
import me.bmax.apatch.util.Changelog
import me.bmax.apatch.util.ui.APDialogBlurBehindUtils

/**
 * 首次启动欢迎引导对话框 —— 4 页分布式指导。
 */
@Composable
fun WelcomeGuideDialog(
    onDismiss: () -> Unit
) {
    PagedInfoDialog(
        pages = listOf(
            InfoPage(
                icon = Icons.Filled.AutoAwesome,
                title = stringResource(R.string.welcome_title_1),
                body = stringResource(R.string.welcome_desc_1),
            ),
            InfoPage(
                icon = Icons.Filled.Palette,
                title = stringResource(R.string.welcome_title_2),
                body = stringResource(R.string.welcome_desc_2),
            ),
            InfoPage(
                icon = Icons.Filled.Tune,
                title = stringResource(R.string.welcome_title_3),
                body = stringResource(R.string.welcome_desc_3),
            ),
            InfoPage(
                icon = Icons.Filled.Settings,
                title = stringResource(R.string.welcome_title_4),
                body = stringResource(R.string.welcome_desc_4),
            ),
        ),
        confirmLabel = stringResource(R.string.welcome_got_it),
        skipLabel = stringResource(R.string.welcome_skip),
        // 首启引导是一道门：点外面或按返回键关掉它，用户就带着「这是什么应用」的疑问
        // 进主界面了，而这个对话框此后再也不会出现。
        dismissible = false,
        onDismiss = onDismiss,
    )
}

/**
 * 更新说明对话框：升级之后第一次打开时，说清这一版改了什么。
 *
 * 复用 [PagedInfoDialog] 而不是另做一个：它和首启引导要的东西完全一样（一个居中的
 * 圆形图标、标题、正文、一个确认按钮、背景模糊），差别只是页数和内容来源。两套壳会
 * 立刻开始各自漂移 —— 圆角、间距、模糊哪个改了另一个不会跟。
 *
 * ## 为什么更新内容是**本地**资源而不是 release 正文
 *
 * [me.bmax.apatch.ui.component.UpdateDialog] 显示的是 GitHub release 的正文，那是
 * 「有一个新版本，它讲了这些」；而这里要说的是「你**现在跑的**这一版改了什么」，用户
 * 此刻可能正在飞机上。这必须离线可用，所以随 APK 一起打包。
 *
 * ## 版本不一致时宁可不显示
 *
 * 显示时机在 [Changelog.shouldShow] 里：只有 [Changelog.VERSION] 与当前版本主号一致时
 * 才弹。发版时忘了改这份文案的后果是「拿上一版的更新内容配上新版本号」—— 一句自信的
 * 假话，比什么都不显示糟得多。`tools/check-changelog.js` 在开发期就拦住这种不一致。
 */
@Composable
fun ChangelogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val items = remember { Changelog.items(context) }
    PagedInfoDialog(
        pages = listOf(
            InfoPage(
                icon = Icons.Filled.NewReleases,
                title = stringResource(R.string.changelog_title, BuildConfig.VERSION_NAME),
                bullets = items,
            ),
        ),
        confirmLabel = stringResource(R.string.changelog_got_it),
        // 更新说明只有一页，没有「跳过」可言；确认按钮就是唯一出口
        skipLabel = null,
        dismissible = true,
        onDismiss = onDismiss,
    )
}

/** 一页的内容。[body] 与 [bullets] 可以只给一个，也可以都给。 */
internal data class InfoPage(
    val icon: ImageVector,
    val title: String,
    val body: String = "",
    val bullets: List<String> = emptyList(),
)

/**
 * 多页信息对话框的公用外壳：圆形图标 + 标题 + 正文/条目 + 圆点 + 底部按钮 + 背景模糊。
 *
 * 只有一页时圆点指示器和「上一步/下一步」自动消失 —— 一个页码指示器指着唯一的一页
 * 只是噪音。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PagedInfoDialog(
    pages: List<InfoPage>,
    confirmLabel: String,
    skipLabel: String?,
    dismissible: Boolean,
    onDismiss: () -> Unit,
) {
    var currentPage by remember { mutableIntStateOf(0) }
    val totalPages = pages.size
    if (totalPages == 0) return

    BasicAlertDialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        properties = DialogProperties(
            dismissOnClickOutside = dismissible,
            dismissOnBackPress = dismissible,
            usePlatformDefaultWidth = false,
        )
    ) {
        Surface(
            modifier = Modifier
                .width(340.dp)
                .wrapContentHeight(),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = AlertDialogDefaults.TonalElevation,
            color = AlertDialogDefaults.containerColor,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ── 页面内容（AnimatedContent 带动画过渡） ──
                AnimatedContent(
                    targetState = currentPage,
                    transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        val enterOffset = { w: Int -> dir * w }
                        (slideInHorizontally(tween(300)) { enterOffset(it) } + fadeIn(tween(200)))
                            .togetherWith(
                                slideOutHorizontally(tween(300)) { -enterOffset(it) } + fadeOut(tween(200))
                            )
                    },
                    label = "welcome_page"
                ) { pageIndex ->
                    val page = pages[pageIndex]
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(vertical = 16.dp)
                    ) {
                        // ── 图标 ──
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = page.icon,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        Spacer(Modifier.height(24.dp))

                        // ── 标题 ──
                        Text(
                            text = page.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        if (page.body.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))

                            // ── 描述 ──
                            Text(
                                text = page.body,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 22.sp
                            )
                        }

                        if (page.bullets.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            // 条目**左对齐**：更新说明是要逐条读的，居中的多行列表读起来
                            // 每一行的起点都在动
                            Column(
                                horizontalAlignment = Alignment.Start,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                for (line in page.bullets) {
                                    Row {
                                        Text(
                                            text = "·",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = line,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            lineHeight = 20.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // ── 圆点指示器（只有一页时没有意义） ──
                if (totalPages > 1) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        repeat(totalPages) { index ->
                            val isActive = index == currentPage
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 5.dp)
                                    .size(if (isActive) 10.dp else 8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isActive) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                    )
                            )
                        }
                    }
                }

                // ── 底部按钮行 ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (totalPages > 1) {
                        Arrangement.SpaceBetween
                    } else {
                        Arrangement.End
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (totalPages > 1) {
                        // 左侧：“上一步”（第一页隐藏）
                        if (currentPage > 0) {
                            TextButton(onClick = { currentPage-- }) {
                                Icon(
                                    Icons.Filled.ChevronLeft,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.welcome_prev))
                            }
                        } else {
                            // 占位保持布局稳定
                            Spacer(Modifier.width(80.dp))
                        }
                    }

                    // 中间：跳过
                    if (skipLabel != null) {
                        TextButton(onClick = onDismiss) {
                            Text(
                                text = skipLabel,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // 右侧：“下一步” / 确认
                    if (currentPage < totalPages - 1) {
                        TextButton(onClick = { currentPage++ }) {
                            Text(stringResource(R.string.welcome_next))
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Filled.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else {
                        Button(onClick = onDismiss) {
                            Text(confirmLabel)
                        }
                    }
                }
            }
        }
    }

    // 模糊背景效果
    val dialogWindowProvider = LocalView.current.parent as? DialogWindowProvider
    dialogWindowProvider?.let {
        APDialogBlurBehindUtils.setupWindowBlurListener(it.window)
    }
}
