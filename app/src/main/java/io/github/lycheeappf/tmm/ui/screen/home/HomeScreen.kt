package io.github.lycheeappf.tmm.ui.screen.home

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lycheeappf.tmm.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lycheeappf.tmm.ui.component.MfsListItem
import io.github.lycheeappf.tmm.ui.component.MfsScaffold
import io.github.lycheeappf.tmm.ui.component.MfsStatus
import io.github.lycheeappf.tmm.ui.component.PrimaryActionButton
import io.github.lycheeappf.tmm.ui.component.SectionHeader
import io.github.lycheeappf.tmm.ui.component.StatusCard
import io.github.lycheeappf.tmm.ui.component.StatusPill
import io.github.lycheeappf.tmm.ui.component.accentColor
import io.github.lycheeappf.tmm.ui.component.containerColors
import io.github.lycheeappf.tmm.ui.component.MfsMotion
import io.github.lycheeappf.tmm.ui.component.mfsExpandEnter
import io.github.lycheeappf.tmm.ui.component.mfsExpandExit
import io.github.lycheeappf.tmm.ui.theme.MfsSpacing

@Composable
fun HomeScreen(
    bottomBar: @Composable () -> Unit,
    onOpenWhitelist: () -> Unit,
    onOpenAssistant: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.refresh() }
    val postNotifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.refresh() }
    val listenerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.refresh() }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.triggerFeedback) {
        state.triggerFeedback?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeTriggerFeedback()
        }
    }

    if (state.pendingConsentDialog) {
        AssistantConsentDialog(
            onConfirm = viewModel::confirmConsent,
            onDismiss = viewModel::dismissConsent
        )
    }

    MfsScaffold(
        title = "Tesla Messages Manager",
        bottomBar = bottomBar,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MfsSpacing.xl, vertical = MfsSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(MfsSpacing.lg)
        ) {
            HeroStatus(isReady = state.isReady)

            // Berechtigungen: einzeln solange offen, kompakt zusammengefasst sobald
            // alles erteilt ist (Dashboard statt Dauer-Checkliste).
            val allGranted = state.isDefaultSmsApp && state.hasNotificationAccess && state.hasPostNotifications
            AnimatedVisibility(visible = !allGranted, enter = mfsExpandEnter(), exit = mfsExpandExit()) {
                Column(verticalArrangement = Arrangement.spacedBy(MfsSpacing.lg)) {
                    StatusCard(
                        title = stringResource(R.string.home_perm_sms_title),
                        description = stringResource(R.string.home_perm_sms_desc),
                        isGranted = state.isDefaultSmsApp,
                        actionLabel = stringResource(R.string.home_perm_sms_action),
                        onAction = { roleLauncher.launch(viewModel.defaultSmsIntent()) }
                    )
                    StatusCard(
                        title = stringResource(R.string.home_perm_listener_title),
                        description = stringResource(R.string.home_perm_listener_desc),
                        isGranted = state.hasNotificationAccess,
                        actionLabel = stringResource(R.string.home_perm_listener_action),
                        onAction = {
                            listenerLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                    )
                    StatusCard(
                        title = stringResource(R.string.home_perm_post_title),
                        description = stringResource(R.string.home_perm_post_desc),
                        isGranted = state.hasPostNotifications,
                        actionLabel = stringResource(R.string.home_perm_post_action),
                        onAction = {
                            postNotifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    )
                }
            }
            AnimatedVisibility(visible = allGranted, enter = mfsExpandEnter(), exit = mfsExpandExit()) {
                StatusPill(text = stringResource(R.string.home_perm_all_granted), status = MfsStatus.Success)
            }

            // Live-Status
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(MfsSpacing.lg)) {
                    SectionHeader(stringResource(R.string.home_status_title))
                    StatusRow(
                        stringResource(R.string.home_status_mapping_label),
                        pluralStringResource(
                            R.plurals.home_status_mapping_hours,
                            state.mappingTtlHours,
                            state.mappingTtlHours
                        )
                    )
                    StatusRow(
                        stringResource(R.string.home_status_forwarded_label),
                        stringResource(
                            R.string.home_status_forwarded_value,
                            state.sendCountToday,
                            state.sendBudget
                        )
                    )
                    StatusRow(
                        stringResource(R.string.home_status_preflight_label),
                        state.preflightResult ?: stringResource(R.string.home_status_preflight_pending)
                    )
                }
            }

            AssistantTriggerCard(
                apiKeyIsSet = state.assistantApiKeyIsSet,
                triggerInFlight = state.triggerInFlight,
                canTrigger = state.canTriggerAssistant,
                onStart = viewModel::onAssistantButtonTapped,
                onConfigure = onOpenAssistant
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                MfsListItem(
                    title = stringResource(R.string.home_whitelist_title),
                    subtitle = stringResource(R.string.home_whitelist_subtitle),
                    trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
                    onClick = onOpenWhitelist
                )
            }
        }
    }
}

@Composable
private fun HeroStatus(isReady: Boolean) {
    val container by animateColorAsState(
        targetValue = if (isReady) MfsStatus.Success.containerColors().first
        else MaterialTheme.colorScheme.errorContainer,
        animationSpec = MfsMotion.effects(),
        label = "heroContainer"
    )
    val onContainer = if (isReady) MfsStatus.Success.containerColors().second
    else MaterialTheme.colorScheme.onErrorContainer
    val accent = if (isReady) MfsStatus.Success.accentColor() else MaterialTheme.colorScheme.error
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = onContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(MfsSpacing.xl),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MfsSpacing.lg)
        ) {
            AnimatedContent(targetState = isReady, label = "heroIcon") { ready ->
                Icon(
                    imageVector = if (ready) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(40.dp)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(MfsSpacing.xs)) {
                Text(
                    if (isReady) stringResource(R.string.home_hero_ready_title)
                    else stringResource(R.string.home_hero_setup_title),
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    if (isReady)
                        stringResource(R.string.home_hero_ready_body)
                    else
                        stringResource(R.string.home_hero_setup_body),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun AssistantTriggerCard(
    apiKeyIsSet: Boolean,
    triggerInFlight: Boolean,
    canTrigger: Boolean,
    onStart: () -> Unit,
    onConfigure: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(MfsSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(MfsSpacing.sm)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MfsSpacing.sm)
            ) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                Text(stringResource(R.string.home_assistant_title), style = MaterialTheme.typography.titleMedium)
            }
            Text(
                if (apiKeyIsSet)
                    stringResource(R.string.home_assistant_body_ready)
                else
                    stringResource(R.string.home_assistant_body_no_key),
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(MfsSpacing.sm)) {
                PrimaryActionButton(
                    text = stringResource(R.string.home_assistant_action_start),
                    onClick = onStart,
                    enabled = canTrigger,
                    loading = triggerInFlight
                )
                TextButton(onClick = onConfigure) { Text(stringResource(R.string.home_assistant_action_settings)) }
            }
        }
    }
}

@Composable
private fun AssistantConsentDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { Button(onClick = onConfirm) { Text(stringResource(R.string.home_consent_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_consent_dismiss)) } },
        title = { Text(stringResource(R.string.home_consent_title)) },
        text = {
            Text(stringResource(R.string.home_consent_body))
        }
    )
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = MfsSpacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
