package me.bmax.apatch.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import me.bmax.apatch.util.LauncherIconUtils
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import me.bmax.apatch.BuildConfig
import me.bmax.apatch.R
import me.bmax.apatch.ui.component.ExpressiveCard
import me.bmax.apatch.ui.component.SplicedColumnGroup

/**
 * 一位赞助者。
 *
 * [avatarRes] 为空时界面会退成「首字母圆底」—— 这样新加赞助者不必先准备图片，
 * 也避免为了一个占位图在 drawable 里堆一堆无用资源。
 */
private data class Sponsor(
    val name: String,
    val note: String = "",
    val avatarRes: Int? = null,
)

/**
 * 赞助者名单（按贡献先后排列，第一位是最早的支持者）。
 *
 * 为什么写在代码里而不是读远端：赞助者数量极少、变动极慢，
 * 为它引入一次网络请求与失败态不值得；改名单时发一版即可。
 */
private val SPONSORS = listOf(
    Sponsor(
        name = "花凬琴",
        // 头像来自赞助者本人提供的那张（drawable-nodpi 下不做密度适配，原样显示）
        avatarRes = me.bmax.apatch.R.drawable.sponsor_huajueqin,
    ),
    Sponsor(
        name = "大器晚成",
        avatarRes = me.bmax.apatch.R.drawable.sponsor_daqiwancheng,
    ),
    Sponsor(
        name = "海棠错",
        avatarRes = me.bmax.apatch.R.drawable.sponsor_haitangcuo,
    ),
)

// 注意：下面这行 @Destination 必须**紧贴** AboutScreen。中间插任何声明都会让
// composedestinations 把注解当成加在别的东西上，于是 AboutScreenDestination 不再生成
// （CI 报的就是 Home.kt 里 Unresolved reference 'AboutScreenDestination'）。
@Destination<RootGraph>
@Composable
fun AboutScreen(navigator: DestinationsNavigator) {
    val uriHandler = LocalUriHandler.current
    val repoUrl = stringResource(R.string.dsh_about_repo)
    val qqGroupUrl = stringResource(R.string.dsh_qq_group_url)

    Scaffold(
        topBar = {
            TopBar(onBack = dropUnlessResumed { navigator.popBackStack() })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // 应用图标：**与主屏那个图标同一份资源**（「设置 → 常规 → 备用图标」开着就换成备用那
            // 一份），见 LauncherIconUtils.currentIconForeground()。以前这里放的是 drawable/about.png
            // —— 一张独立的旧图，图标改版后它不会跟着变，于是关于页显示的是老 logo。
            Image(
                painter = painterResource(id = LauncherIconUtils.currentIconForeground()),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.size(100.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(id = R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = stringResource(
                    id = R.string.about_app_version,
                    if (BuildConfig.VERSION_NAME.contains(BuildConfig.VERSION_CODE.toString())) "${BuildConfig.VERSION_CODE}" else "${BuildConfig.VERSION_CODE} (${BuildConfig.VERSION_NAME})"
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = stringResource(
                    id = R.string.about_powered_by,
                    stringResource(R.string.dsh_about_powered_by)
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Description card — M3E ExpressiveCard
            ExpressiveCard(
                modifier = Modifier.padding(horizontal = 16.dp),
                flat = true,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(id = R.string.about),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(id = R.string.dsh_about_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Links — M3E SplicedColumnGroup
            SplicedColumnGroup(
                modifier = Modifier.padding(horizontal = 0.dp),
            ) {
                item {
                    SplicedLinkItem(
                        icon = {
                            Icon(
                                painter = painterResource(id = R.drawable.github),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        title = stringResource(id = R.string.about_github),
                        onClick = { uriHandler.openUri(repoUrl) }
                    )
                }
                item {
                    SplicedLinkItem(
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.Group,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        title = stringResource(R.string.dsh_qq_group) + " " +
                            stringResource(R.string.dsh_qq_group_id),
                        onClick = { uriHandler.openUri(qqGroupUrl) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── 赞助者 ────────────────────────────────────────────────────────
            // 默认折叠成一行「赞助者 N 位」，点开才逐个列出。
            // 为什么不直接摊开：关于页已经有「说明 / 链接 / 致谢」三块，赞助者一多会把整页拉长；
            // 而它恰恰是「想看才会看」的信息，折叠起来更合适。
            var sponsorsExpanded by remember { mutableStateOf(false) }
            ExpressiveCard(
                modifier = Modifier.padding(horizontal = 16.dp),
                flat = true,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { sponsorsExpanded = !sponsorsExpanded }
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.dsh_sponsors, SPONSORS.size),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = if (sponsorsExpanded) {
                                Icons.Outlined.ExpandLess
                            } else {
                                Icons.Outlined.ExpandMore
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AnimatedVisibility(visible = sponsorsExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            for (s in SPONSORS) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // 头像：圆形裁切。用 ContentScale.Crop 而不是 Fit ——
                                    // 赞助者给的多是方图或半身像，Fit 会留白边显得很空。
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape),
                                    ) {
                                        if (s.avatarRes != null) {
                                            Image(
                                                painter = painterResource(id = s.avatarRes),
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        } else {
                                            // 没有头像就退成首字母圆底：比空白方块像样，
                                            // 也保证新加赞助者时不必先准备图片。
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    text = s.name.take(1),
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = s.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        if (s.note.isNotEmpty()) {
                                            Text(
                                                text = s.note,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                            Text(
                                text = stringResource(R.string.dsh_sponsors_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // 与上一张卡片（赞助者）留出与其它卡片一致的间距：
            // 少了这一行两张卡会「粘」在一起，看上去像一个区块里混了两种内容。
            Spacer(modifier = Modifier.height(16.dp))

            // 致谢：这个 App 是站在这几个项目上做出来的，明确列出来
            ExpressiveCard(
                modifier = Modifier.padding(horizontal = 16.dp),
                flat = true,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.dsh_acknowledgements),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    val credits = listOf(
                        R.string.dsh_ack_dsh,
                        R.string.dsh_ack_dsha,
                        R.string.dsh_ack_dshm,
                        R.string.dsh_ack_folkpatch,
                        R.string.dsh_ack_termux,
                    )
                    for (id in credits) {
                        Text(
                            text = "· " + stringResource(id),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SplicedLinkItem(
    icon: @Composable () -> Unit,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(modifier = Modifier.width(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(onBack: () -> Unit = {}) {
    TopAppBar(
        title = { Text(stringResource(R.string.about)) },
        navigationIcon = {
            IconButton(
                onClick = onBack
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
        },
    )
}
