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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.lycheeappf.tmm.R
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
        title = stringResource(R.string.assistant_title),
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
                stringResource(R.string.assistant_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ---- Privacy-Einwilligung ----
            SettingCard(
                title = stringResource(R.string.assistant_consent_title),
                description = stringResource(R.string.assistant_consent_desc)
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
                        if (state.privacyConsent) stringResource(R.string.assistant_consent_granted)
                        else stringResource(R.string.assistant_consent_not_granted),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // ---- API-Key ----
            SettingCard(
                title = stringResource(R.string.assistant_apikey_title),
                description = stringResource(R.string.assistant_apikey_desc)
            ) {
                StatusPill(
                    text = if (state.apiKeyIsSet) stringResource(R.string.assistant_apikey_active)
                    else stringResource(R.string.assistant_apikey_none),
                    status = if (state.apiKeyIsSet) MfsStatus.Success else MfsStatus.Neutral
                )
                OutlinedTextField(
                    value = state.apiKeyDraft,
                    onValueChange = viewModel::setApiKeyDraft,
                    label = { Text(stringResource(R.string.assistant_apikey_field_label)) },
                    visualTransformation = if (keyVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                imageVector = if (keyVisible) Icons.Outlined.VisibilityOff
                                else Icons.Outlined.Visibility,
                                contentDescription = if (keyVisible) stringResource(R.string.assistant_apikey_hide)
                                else stringResource(R.string.assistant_apikey_show)
                            )
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(MfsSpacing.sm)) {
                    PrimaryActionButton(
                        text = stringResource(R.string.assistant_apikey_save),
                        onClick = { viewModel.saveApiKey() },
                        enabled = state.apiKeyDraft.isNotBlank(),
                        loading = state.saving
                    )
                    TextButton(
                        onClick = { viewModel.clearApiKey() },
                        enabled = state.apiKeyIsSet && !state.saving
                    ) { Text(stringResource(R.string.assistant_apikey_remove)) }
                }
            }

            // ---- Modell + Texte ----
            SettingCard(
                title = stringResource(R.string.assistant_model_title),
                description = stringResource(R.string.assistant_model_desc)
            ) {
                OutlinedTextField(
                    value = state.model,
                    onValueChange = viewModel::setModel,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SettingCard(
                title = stringResource(R.string.assistant_drivername_title),
                description = stringResource(R.string.assistant_drivername_desc)
            ) {
                OutlinedTextField(
                    value = state.driverName,
                    onValueChange = viewModel::setDriverName,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SettingCard(
                title = stringResource(R.string.assistant_voicealias_title),
                description = stringResource(R.string.assistant_voicealias_desc)
            ) {
                val isCustom = state.voiceAliasEnabled &&
                    state.voiceAliasName !in AssistantViewModel.PRESET_NAMES
                var customMode by remember(state.voiceAliasName, state.voiceAliasEnabled) {
                    mutableStateOf(isCustom)
                }
                var customDraft by remember(state.voiceAliasName) {
                    mutableStateOf(if (isCustom) state.voiceAliasName else "")
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MfsSpacing.sm)) {
                    FilterChip(
                        selected = !state.voiceAliasEnabled,
                        enabled = !state.voiceAliasApplying,
                        onClick = {
                            customMode = false
                            viewModel.applyVoiceAlias(false, state.voiceAliasName)
                        },
                        label = { Text(stringResource(R.string.assistant_voicealias_off)) }
                    )
                    AssistantViewModel.PRESET_NAMES.forEach { preset ->
                        FilterChip(
                            selected = state.voiceAliasEnabled && !customMode &&
                                state.voiceAliasName == preset,
                            enabled = !state.voiceAliasApplying,
                            onClick = {
                                customMode = false
                                viewModel.applyVoiceAlias(true, preset)
                            },
                            label = { Text(preset) }
                        )
                    }
                    FilterChip(
                        selected = customMode,
                        enabled = !state.voiceAliasApplying,
                        onClick = { customMode = true },
                        label = { Text(stringResource(R.string.assistant_voicealias_custom)) }
                    )
                }
                if (customMode) {
                    OutlinedTextField(
                        value = customDraft,
                        onValueChange = { customDraft = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.assistant_voicealias_custom_hint)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { viewModel.applyVoiceAlias(true, customDraft) },
                        enabled = customDraft.isNotBlank() && !state.voiceAliasApplying
                    ) {
                        Text(
                            if (state.voiceAliasApplying) stringResource(R.string.assistant_voicealias_applying)
                            else stringResource(R.string.assistant_voicealias_apply)
                        )
                    }
                }
            }

            SettingCard(
                title = stringResource(R.string.assistant_welcome_title),
                description = stringResource(R.string.assistant_welcome_desc)
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
                title = stringResource(R.string.assistant_behavior_title),
                description = stringResource(R.string.assistant_behavior_desc)
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
                title = stringResource(R.string.assistant_ttl_title),
                description = stringResource(R.string.assistant_ttl_desc),
                value = state.contextTtlSeconds.toFloat(),
                valueLabel = pluralStringResource(
                    R.plurals.assistant_ttl_value,
                    state.contextTtlSeconds,
                    state.contextTtlSeconds
                ),
                range = 30f..600f,
                onChange = { viewModel.setContextTtl(it.toInt()) }
            )
            SliderCard(
                title = stringResource(R.string.assistant_maxtokens_title),
                description = stringResource(R.string.assistant_maxtokens_desc),
                value = state.maxTokens.toFloat(),
                valueLabel = pluralStringResource(
                    R.plurals.assistant_maxtokens_value,
                    state.maxTokens,
                    state.maxTokens
                ),
                range = 64f..2048f,
                onChange = { viewModel.setMaxTokens(it.toInt()) }
            )
            SliderCard(
                title = stringResource(R.string.assistant_temperature_title),
                description = stringResource(R.string.assistant_temperature_desc),
                value = state.temperature,
                valueLabel = "%.2f".format(state.temperature),
                range = 0f..1.5f,
                onChange = viewModel::setTemperature
            )
            SliderCard(
                title = stringResource(R.string.assistant_rate_min_title),
                description = stringResource(R.string.assistant_rate_min_desc),
                value = state.rateLimitPerMin.toFloat(),
                valueLabel = "${state.rateLimitPerMin}",
                range = 1f..60f,
                onChange = { viewModel.setRateLimitPerMin(it.toInt()) }
            )
            SliderCard(
                title = stringResource(R.string.assistant_rate_hour_title),
                description = stringResource(R.string.assistant_rate_hour_desc),
                value = state.rateLimitPerHour.toFloat(),
                valueLabel = "${state.rateLimitPerHour}",
                range = 1f..600f,
                onChange = { viewModel.setRateLimitPerHour(it.toInt()) }
            )

            // ---- Start ----
            SettingCard(
                title = stringResource(R.string.assistant_start_title),
                description = stringResource(R.string.assistant_start_desc)
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.assistant_start_action),
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
