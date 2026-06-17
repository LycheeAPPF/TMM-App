package io.github.lycheeappf.tmm.ui.screen.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lycheeappf.tmm.BuildConfig
import io.github.lycheeappf.tmm.ui.component.MfsCardVariant
import io.github.lycheeappf.tmm.ui.component.MfsListItem
import io.github.lycheeappf.tmm.ui.component.MfsScaffold
import io.github.lycheeappf.tmm.ui.component.MfsStatus
import io.github.lycheeappf.tmm.ui.component.PrimaryActionButton
import io.github.lycheeappf.tmm.ui.component.SectionHeader
import io.github.lycheeappf.tmm.ui.component.SettingCard
import io.github.lycheeappf.tmm.ui.component.StatusPill
import io.github.lycheeappf.tmm.ui.component.mfsExpandEnter
import io.github.lycheeappf.tmm.ui.component.mfsExpandExit
import io.github.lycheeappf.tmm.ui.component.preflightStatusUi
import io.github.lycheeappf.tmm.ui.theme.MfsSpacing

@Composable
fun SettingsScreen(
    bottomBar: @Composable () -> Unit,
    onOpenWhitelist: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenChannels: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    MfsScaffold(title = "Einstellungen", bottomBar = bottomBar) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MfsSpacing.xl, vertical = MfsSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(MfsSpacing.lg)
        ) {
            SectionHeader("Weiterleitung")

            SettingCard(
                title = "Gültigkeit der Zuordnung",
                description = "Wie lange merkt sich die App, welche Tesla-Adresse zu welchem Chat gehört?"
            ) {
                Text("${state.ttlHours} Stunden", style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = state.ttlHours.toFloat(),
                    onValueChange = { viewModel.setTtlHours(it.toInt().coerceIn(1, 168)) },
                    valueRange = 1f..168f
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("1 h", style = MaterialTheme.typography.labelSmall)
                    Text("7 Tage", style = MaterialTheme.typography.labelSmall)
                }
            }

            SettingCard(
                title = "Tageslimit",
                description = "Maximale Anzahl weitergeleiteter Nachrichten pro Tag — ein Sicherheitsnetz."
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${state.sendBudget} Nachrichten", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "heute: ${state.sendCountToday}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.sendCountToday >= state.sendBudget)
                            MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Slider(
                    value = state.sendBudget.toFloat(),
                    onValueChange = { viewModel.setSendBudget(it.toInt().coerceIn(10, 500)) },
                    valueRange = 10f..500f
                )
            }

            SectionHeader("Apps")
            SettingCard(
                title = "Freigegebene Apps",
                description = "Lege fest, welche Apps an dein Tesla weitergeleitet werden.",
                variant = MfsCardVariant.Outlined
            ) {
                MfsListItem(
                    title = "Liste öffnen",
                    trailing = { androidx.compose.material3.Icon(Icons.Outlined.ChevronRight, null) },
                    onClick = onOpenWhitelist
                )
            }

            AnimatedVisibility(
                visible = state.developerMode,
                enter = mfsExpandEnter(),
                exit = mfsExpandExit()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(MfsSpacing.lg)) {
                    DeveloperSettings(
                        state = state,
                        viewModel = viewModel,
                        onOpenDiagnostics = onOpenDiagnostics,
                        onOpenChannels = onOpenChannels
                    )
                }
            }

            VersionTapFooter(
                developerMode = state.developerMode,
                onEnableDeveloperMode = { viewModel.setDeveloperMode(true) }
            )
        }
    }
}

@Composable
private fun DeveloperSettings(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onOpenDiagnostics: () -> Unit,
    onOpenChannels: () -> Unit
) {
    SectionHeader("Entwickler")

    SettingCard(
        title = "Netz-Test (Pre-Flight)",
        description = "Sendet eine Test-SMS an die +888-Systemadresse und prüft, ob das Netz sie " +
            "kostenlos ablehnt. 'Sicher abgelehnt' = +888 ist verwendbar."
    ) {
        val (statusText, status) = preflightStatusUi(state.preflightStatus)
        StatusPill(text = statusText, status = status)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MfsSpacing.sm)
        ) {
            PrimaryActionButton(
                text = "Test ausführen",
                onClick = { viewModel.runPreflight() },
                loading = state.preflightRunning
            )
            TextButton(
                onClick = { viewModel.resetPreflight() },
                enabled = !state.preflightRunning
            ) { Text("Zurücksetzen") }
        }
    }

    SettingCard(
        title = "Tesla-Bridge-Kontakte",
        description = "Pro Gespräch wird ein unsichtbarer Kontakt angelegt, damit Tesla den Namen " +
            "statt der Nummer anzeigt. Bei Problemen: Sync neu erzwingen."
    ) {
        Text(
            when {
                state.teslaContactsHasPermission ->
                    "${state.teslaContactCount} synchronisierte Kontakte"
                !state.teslaContactsHasRead && !state.teslaContactsHasWrite ->
                    "Kontakt-Berechtigung fehlt (Lesen + Schreiben) — Tesla zeigt nur Nummern"
                !state.teslaContactsHasWrite ->
                    "Schreibrecht für Kontakte fehlt — Tesla zeigt nur Nummern"
                else ->
                    "Leserecht für Kontakte fehlt — Tesla zeigt nur Nummern"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.teslaContactsHasPermission) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.error
        )
        TextButton(
            enabled = state.teslaContactsHasPermission && !state.teslaContactsResetting,
            onClick = { viewModel.resetTeslaContacts() }
        ) {
            Text(if (state.teslaContactsResetting) "läuft…" else "Tesla-Kontakte neu synchronisieren")
        }
    }

    SettingCard(
        title = "Sprach-Aliasse (Grog / Grogg)",
        description = "Zusätzliche Kontakte, die Teslas Sprachsteuerung statt „Grok\" oft " +
            "besser versteht. Aus = nur „Grok\" als Kontakt (vermeidet das „gro\"-Auswahlmenü " +
            "im Auto). Das Umschalten erzwingt einen Tesla-Kontakt-Sync."
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Grog + Grogg aktiv", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = state.voiceAliasesEnabled,
                enabled = !state.teslaContactsResetting,
                onCheckedChange = { viewModel.setVoiceAliasesEnabled(it) }
            )
        }
    }

    SettingCard(
        title = "Diagnose-Werkzeuge",
        description = "Mappings, Reply-Verlauf, Live-Log und der Channel-Überblick.",
        variant = MfsCardVariant.Outlined
    ) {
        MfsListItem(
            title = "Diagnose",
            subtitle = "Mappings, Reply-Verlauf, Live-Log, Sender-Test",
            trailing = { androidx.compose.material3.Icon(Icons.Outlined.ChevronRight, null) },
            onClick = onOpenDiagnostics
        )
        HorizontalDivider()
        MfsListItem(
            title = "Channels",
            subtitle = "Notification (V1) + Grok-LLM (V2)",
            trailing = { androidx.compose.material3.Icon(Icons.Outlined.ChevronRight, null) },
            onClick = onOpenChannels
        )
    }

    SettingCard(
        title = "Entwicklermodus",
        description = "Blendet Diagnose, Channels und die Experten-Einstellungen ein. " +
            "Ausschalten versteckt sie wieder; das Versions-Label unten bleibt der Wieder-Einstieg."
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Entwicklermodus aktiv", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = state.developerMode,
                onCheckedChange = { viewModel.setDeveloperMode(it) }
            )
        }
    }
}

@Composable
private fun VersionTapFooter(
    developerMode: Boolean,
    onEnableDeveloperMode: () -> Unit
) {
    val context = LocalContext.current
    var tapCount by remember { mutableIntStateOf(0) }
    Text(
        text = "Version ${BuildConfig.VERSION_NAME}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (developerMode) return@clickable
                tapCount++
                if (tapCount >= 7) {
                    tapCount = 0
                    onEnableDeveloperMode()
                    android.widget.Toast
                        .makeText(context, "Entwicklermodus aktiviert", android.widget.Toast.LENGTH_SHORT)
                        .show()
                }
            }
            .padding(vertical = MfsSpacing.sm)
    )
}
