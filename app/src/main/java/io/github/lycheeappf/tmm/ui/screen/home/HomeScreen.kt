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
import androidx.compose.ui.unit.dp
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
                        title = "Standard-SMS-App",
                        description = "Damit dein Tesla die weitergeleiteten Nachrichten anzeigen kann.",
                        isGranted = state.isDefaultSmsApp,
                        actionLabel = "Setzen",
                        onAction = { roleLauncher.launch(viewModel.defaultSmsIntent()) }
                    )
                    StatusCard(
                        title = "Benachrichtigungs-Zugriff",
                        description = "Erlaubt das Lesen von Benachrichtigungen deiner Messenger.",
                        isGranted = state.hasNotificationAccess,
                        actionLabel = "Aktivieren",
                        onAction = {
                            listenerLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                    )
                    StatusCard(
                        title = "Hinweise dieser App",
                        description = "Für Status- und Fehlermeldungen.",
                        isGranted = state.hasPostNotifications,
                        actionLabel = "Erlauben",
                        onAction = {
                            postNotifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    )
                }
            }
            AnimatedVisibility(visible = allGranted, enter = mfsExpandEnter(), exit = mfsExpandExit()) {
                StatusPill(text = "Alle Berechtigungen erteilt", status = MfsStatus.Success)
            }

            // Live-Status
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(MfsSpacing.lg)) {
                    SectionHeader("Status")
                    StatusRow("Zuordnung gültig", "${state.mappingTtlHours} Stunden")
                    StatusRow("Heute weitergeleitet", "${state.sendCountToday} / ${state.sendBudget}")
                    StatusRow("Netz-Test", state.preflightResult ?: "noch nicht ausgeführt")
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
                    title = "Freigegebene Apps",
                    subtitle = "Welche Apps an dein Tesla weitergeleitet werden",
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
                    if (isReady) "Bereit" else "Einrichtung nötig",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    if (isReady)
                        "Nachrichten werden an dein Tesla weitergeleitet."
                    else
                        "Erteile die folgenden Berechtigungen, um zu starten.",
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
                Text("Grok-Assistent", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                if (apiKeyIsSet)
                    "Starte das Gespräch im Tesla: diktiere wie gewohnt, Grok antwortet und dein Tesla liest die Antwort vor."
                else
                    "Hinterlege zuerst einen xAI-API-Key in den Assistent-Einstellungen.",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(MfsSpacing.sm)) {
                PrimaryActionButton(
                    text = "Chat starten",
                    onClick = onStart,
                    enabled = canTrigger,
                    loading = triggerInFlight
                )
                TextButton(onClick = onConfigure) { Text("Einstellungen") }
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
        confirmButton = { Button(onClick = onConfirm) { Text("Verstanden, starten") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
        title = { Text("Diktate werden an Grok übertragen") },
        text = {
            Text(
                "Wenn du diese Konversation startest, sendet die App jedes Tesla-Diktat " +
                    "an die xAI Grok API. xAI bekommt damit den Inhalt deiner Diktate.\n\n" +
                    "Wir setzen \"store: false\" — xAI speichert die Konversation nicht " +
                    "langfristig — kurzfristige Logging-Praktiken können wir aber nicht " +
                    "kontrollieren.\n\nDer Gesprächsverlauf lebt nur im Speicher dieser App " +
                    "und wird nach kurzer Inaktivität verworfen."
            )
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
