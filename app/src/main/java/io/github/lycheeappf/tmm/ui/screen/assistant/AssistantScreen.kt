package io.github.lycheeappf.tmm.ui.screen.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lycheeappf.tmm.ui.component.MfsScaffold
import io.github.lycheeappf.tmm.ui.component.MfsStatus
import io.github.lycheeappf.tmm.ui.component.PrimaryActionButton
import io.github.lycheeappf.tmm.ui.component.SettingCard
import io.github.lycheeappf.tmm.ui.component.StatusPill
import io.github.lycheeappf.tmm.ui.theme.MfsSpacing

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssistantScreen(
    bottomBar: @Composable () -> Unit,
    viewModel: AssistantViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var keyVisible by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    LaunchedEffect(state.lastFeedback) {
        state.lastFeedback?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeFeedback()
        }
    }

    MfsScaffold(
        title = "Grok-Assistent",
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
            Text(
                "Deine Diktate aus dem Tesla gehen an Grok (xAI). Die Antwort liest dein " +
                    "Tesla vor. Der Gesprächskontext für Grok bleibt nur kurz im Speicher. " +
                    "Deine Diktate werden zusätzlich lokal auf dem Gerät protokolliert " +
                    "(für Diagnose und erneute Zustellung) und verlassen es nur Richtung xAI.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ---- Privacy-Einwilligung ----
            SettingCard(
                title = "Einwilligung",
                description = "Vor dem ersten Start zwingend — bestätigt, dass Diktate an xAI gehen."
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MfsSpacing.sm)
                ) {
                    Switch(
                        checked = state.privacyConsent,
                        onCheckedChange = { viewModel.setPrivacyConsent(it) }
                    )
                    Text(
                        if (state.privacyConsent) "Eingewilligt — Start freigegeben."
                        else "Noch nicht eingewilligt — Start blockiert.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // ---- API-Key ----
            SettingCard(
                title = "xAI-API-Key",
                description = "Wird verschlüsselt auf dem Gerät gespeichert und nicht ins Backup übernommen."
            ) {
                StatusPill(
                    text = if (state.apiKeyIsSet) "Key aktiv" else "Kein Key",
                    status = if (state.apiKeyIsSet) MfsStatus.Success else MfsStatus.Neutral
                )
                OutlinedTextField(
                    value = state.apiKeyDraft,
                    onValueChange = viewModel::setApiKeyDraft,
                    label = { Text("Neuen Key eintragen") },
                    visualTransformation = if (keyVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                imageVector = if (keyVisible) Icons.Outlined.VisibilityOff
                                else Icons.Outlined.Visibility,
                                contentDescription = if (keyVisible) "Key verbergen" else "Key anzeigen"
                            )
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(MfsSpacing.sm)) {
                    PrimaryActionButton(
                        text = "Speichern",
                        onClick = { viewModel.saveApiKey() },
                        enabled = state.apiKeyDraft.isNotBlank(),
                        loading = state.saving
                    )
                    TextButton(
                        onClick = { viewModel.clearApiKey() },
                        enabled = state.apiKeyIsSet && !state.saving
                    ) { Text("Entfernen") }
                }
            }

            // ---- Modell + Texte ----
            SettingCard(
                title = "Modell",
                description = "Standard: grok-4.3. Nur ändern, wenn xAI ein anderes Modell empfiehlt."
            ) {
                OutlinedTextField(
                    value = state.model,
                    onValueChange = viewModel::setModel,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SettingCard(
                title = "Dein Name (optional)",
                description = "Grok spricht dich damit an. Wird für {driver} in Begrüßung und " +
                    "Verhalten eingesetzt. Leer lassen für eine neutrale Anrede."
            ) {
                OutlinedTextField(
                    value = state.driverName,
                    onValueChange = viewModel::setDriverName,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SettingCard(
                title = "Name im Tesla (Sprachsteuerung)",
                description = "So heißt Grok als Kontakt im Auto. Teslas Sprachsteuerung erkennt " +
                    "einen VOR- + NACHNAMEN am zuverlässigsten. Auswählen bzw. Anwenden " +
                    "synchronisiert den Namen direkt zum Tesla."
            ) {
                val isCustom = state.assistantName !in AssistantViewModel.PRESET_NAMES
                var customMode by remember(state.assistantName) { mutableStateOf(isCustom) }
                var customDraft by remember(state.assistantName) {
                    mutableStateOf(if (isCustom) state.assistantName else "")
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MfsSpacing.sm)) {
                    AssistantViewModel.PRESET_NAMES.forEach { preset ->
                        FilterChip(
                            selected = !customMode && state.assistantName == preset,
                            enabled = !state.assistantNameApplying,
                            onClick = {
                                customMode = false
                                viewModel.applyAssistantName(preset)
                            },
                            label = { Text(preset) }
                        )
                    }
                    FilterChip(
                        selected = customMode,
                        enabled = !state.assistantNameApplying,
                        onClick = { customMode = true },
                        label = { Text("Eigener Name") }
                    )
                }
                if (customMode) {
                    OutlinedTextField(
                        value = customDraft,
                        onValueChange = { customDraft = it },
                        singleLine = true,
                        label = { Text("z.B. Viktor Grok") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { viewModel.applyAssistantName(customDraft) },
                        enabled = customDraft.isNotBlank() && !state.assistantNameApplying
                    ) {
                        Text(if (state.assistantNameApplying) "läuft…" else "Anwenden & zum Tesla syncen")
                    }
                }
            }

            SettingCard(
                title = "Begrüßung",
                description = "Erster Satz, den dein Tesla beim Start vorliest. {driver} wird durch deinen Namen ersetzt."
            ) {
                OutlinedTextField(
                    value = state.welcomeMessage,
                    onValueChange = viewModel::setWelcome,
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SettingCard(
                title = "Verhalten von Grok",
                description = "Leitet Grok an, wie es antworten soll. {driver} wird durch deinen Namen ersetzt. Kurz halten — geht in jede Anfrage ein."
            ) {
                OutlinedTextField(
                    value = state.systemPrompt,
                    onValueChange = viewModel::setSystemPrompt,
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ---- Parameter ----
            SliderCard(
                title = "Verlauf verwerfen nach",
                description = "Nach so vielen Sekunden ohne Eingabe wird der Gesprächsverlauf gelöscht.",
                value = state.contextTtlSeconds.toFloat(),
                valueLabel = "${state.contextTtlSeconds} Sekunden",
                range = 30f..600f,
                onChange = { viewModel.setContextTtl(it.toInt()) }
            )
            SliderCard(
                title = "Maximale Antwortlänge",
                description = "Obergrenze pro Antwort. 512 entspricht etwa 3–5 vorlesbaren Sätzen.",
                value = state.maxTokens.toFloat(),
                valueLabel = "${state.maxTokens} Tokens",
                range = 64f..2048f,
                onChange = { viewModel.setMaxTokens(it.toInt()) }
            )
            SliderCard(
                title = "Kreativität",
                description = "0 = streng sachlich, 1.5 = sehr kreativ. Standard 0.7.",
                value = state.temperature,
                valueLabel = "%.2f".format(state.temperature),
                range = 0f..1.5f,
                onChange = viewModel::setTemperature
            )
            SliderCard(
                title = "Anfragen-Limit pro Minute",
                description = "Maximale Grok-Anfragen je Gespräch innerhalb von 60 Sekunden.",
                value = state.rateLimitPerMin.toFloat(),
                valueLabel = "${state.rateLimitPerMin}",
                range = 1f..60f,
                onChange = { viewModel.setRateLimitPerMin(it.toInt()) }
            )
            SliderCard(
                title = "Anfragen-Limit pro Stunde",
                description = "Maximale Grok-Anfragen je Gespräch innerhalb von 60 Minuten.",
                value = state.rateLimitPerHour.toFloat(),
                valueLabel = "${state.rateLimitPerHour}",
                range = 1f..600f,
                onChange = { viewModel.setRateLimitPerHour(it.toInt()) }
            )

            // ---- Start ----
            SettingCard(
                title = "Gespräch jetzt starten",
                description = "Spielt die Begrüßung ins Tesla — praktisch zum Testen vor der Fahrt."
            ) {
                PrimaryActionButton(
                    text = "Chat starten",
                    onClick = { viewModel.triggerAssistant() },
                    enabled = state.privacyConsent && state.apiKeyIsSet,
                    loading = state.triggerInFlight
                )
            }
        }
    }
}

@Composable
private fun SliderCard(
    title: String,
    description: String,
    value: Float,
    valueLabel: String,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    SettingCard(title = title, description = description) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            StatusPill(text = valueLabel, status = MfsStatus.Info)
        }
        androidx.compose.material3.Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range
        )
    }
}
