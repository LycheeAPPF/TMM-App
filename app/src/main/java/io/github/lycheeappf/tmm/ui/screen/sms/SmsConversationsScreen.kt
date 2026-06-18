package io.github.lycheeappf.tmm.ui.screen.sms

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lycheeappf.tmm.R
import io.github.lycheeappf.tmm.ui.component.MfsListItem
import io.github.lycheeappf.tmm.ui.component.MfsScaffold
import io.github.lycheeappf.tmm.ui.component.MfsStatus
import io.github.lycheeappf.tmm.ui.component.StatusCard
import io.github.lycheeappf.tmm.ui.component.StatusPill
import io.github.lycheeappf.tmm.ui.theme.MfsSpacing

@Composable
fun SmsConversationsScreen(
    bottomBar: @Composable () -> Unit,
    onOpenThread: (Long) -> Unit,
    onCompose: () -> Unit,
    viewModel: SmsConversationsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }

    val readSmsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.refresh() }
    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.refresh() }

    MfsScaffold(
        title = stringResource(R.string.sms_list_title),
        bottomBar = bottomBar,
        actions = {
            IconButton(onClick = onCompose) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.sms_compose_action))
            }
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = MfsSpacing.xl, vertical = MfsSpacing.md),
            verticalArrangement = Arrangement.spacedBy(MfsSpacing.md)
        ) {
            when {
                !state.isDefaultSmsApp -> StatusCard(
                    title = stringResource(R.string.sms_list_default_app_title),
                    description = stringResource(R.string.sms_list_default_app_desc),
                    isGranted = false,
                    actionLabel = stringResource(R.string.sms_list_default_app_action),
                    onAction = { viewModel.defaultSmsIntent()?.let { roleLauncher.launch(it) } }
                )

                !state.hasReadSms -> StatusCard(
                    title = stringResource(R.string.sms_list_read_permission_title),
                    description = stringResource(R.string.sms_list_read_permission_desc),
                    isGranted = false,
                    actionLabel = stringResource(R.string.sms_list_read_permission_action),
                    onAction = { readSmsLauncher.launch(android.Manifest.permission.READ_SMS) }
                )

                state.loading -> Box(
                    modifier = Modifier.fillMaxWidth().padding(top = MfsSpacing.xxl),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                state.items.isEmpty() -> Text(
                    stringResource(R.string.sms_list_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = MfsSpacing.lg)
                )

                else -> LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = MfsSpacing.listBottom)
                ) {
                    itemsIndexed(state.items, key = { _, c -> c.threadId }) { index, conv ->
                        val unread = conv.unreadCount > 0
                        MfsListItem(
                            title = conv.displayName ?: conv.address,
                            subtitle = conv.snippet,
                            trailing = {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        SmsFormat.relativeTime(conv.date),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (unread) {
                                        StatusPill(text = conv.unreadCount.toString(), status = MfsStatus.Info)
                                    }
                                }
                            },
                            onClick = { onOpenThread(conv.threadId) },
                            modifier = Modifier.animateItem()
                        )
                        if (index < state.items.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
    }
}
