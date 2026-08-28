package me.bmax.apatch.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import me.bmax.apatch.BuildConfig
import me.bmax.apatch.R
import me.bmax.apatch.ui.component.ExpressiveCard
import me.bmax.apatch.ui.component.SplicedColumnGroup

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

            // App icon with elevated shadow
            Surface(
                modifier = Modifier
                    .size(100.dp)
                    .shadow(8.dp, CircleShape),
                color = colorResource(id = R.color.about_icon_background),
                shape = CircleShape
            ) {
                Image(
                    painter = painterResource(id = R.drawable.about),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier.scale(0.7f)
                )
            }

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
