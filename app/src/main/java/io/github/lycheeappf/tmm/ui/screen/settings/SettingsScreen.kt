package io.github.lycheeappf.tmm.ui.screen.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lycheeappf.tmm.BuildConfig
import io.github.lycheeappf.tmm.R
import io.github.lycheeappf.tmm.ui.component.MfsCardVariant
import io.github.lycheeappf.tmm.ui.component.MfsListItem
import io.github.lycheeappf.tmm.ui.component.MfsScaffold
import io.github.lycheeappf.tmm.ui.component.MfsStatus
import io.github.lycheeappf.tmm.ui.component.PrimaryActionButton
import io.github.lycheeappf.tmm.ui.component.SectionHeader
import io.github.lycheeappf.tmm.ui.component.SettingCard
import io.github.lycheeappf.tmm.ui.component.StatusPill
import io.github.lycheeappf.tmm.ui.component.TeslaConnectionCard
import io.github.lycheeappf.tmm.ui.component.TeslaDevicePickerDialog
import io.github.lycheeappf.tmm.ui.component.TeslaVehicleLinkDialog
import io.github.lycheeappf.tmm.ui.component.maskVin
import io.github.lycheeappf.tmm.ui.component.mfsExpandEnter
import io.github.lycheeappf.tmm.ui.component.mfsExpandExit
import io.github.lycheeappf.tmm.ui.component.preflightStatusUi
import io.github.lycheeappf.tmm.platform.tesla.api.VehicleInfo
import io.github.lycheeappf.tmm.platform.tesla.auth.TeslaAuthState
import io.github.lycheeappf.tmm.platform.tesla.auth.TeslaOAuthConfig
import io.github.lycheeappf.tmm.ui.screen.diagnostics.DiagnosticsShare
import io.github.lycheeappf.tmm.ui.theme.MfsSpacing

@Composable
fun SettingsScreen(
    bottomBar: @Composable () -> Unit,
    onOpenWhitelist: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenChannels: () -> Unit,
    onRestartSetup: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    val context = LocalContext.current
    val activity = context as? android.app.Activity
    var showBudgetWarn by remember { mutableStateOf(false) }
    var showDevicePicker by remember { mutableStateOf(false) }
    var btPermanentlyDenied by remember { mutableStateOf(false) }
    val btPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Nach einer Ablehnung: dauerhaft verweigert, wenn keine Rationale mehr
        // gezeigt werden darf → CTA auf „App-Einstellungen öffnen" umschalten.
        if (!granted && activity != null) {
            btPermanentlyDenied = !androidx.core.app.ActivityCompat
                .shouldShowRequestPermissionRationale(activity, android.Manifest.permission.BLUETOOTH_CONNECT)
        }
        viewModel.refresh()
    }
    val openAppSettings: () -> Unit = {
        activity?.startActivity(
            android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", context.packageName, null)
            )
        )
    }

    if (showBudgetWarn) {
        BudgetDisableDialog(
            onConfirm = {
                showBudgetWarn = false
                viewModel.setBudgetEnabled(false)
            },
            onCancel = { showBudgetWarn = false }
        )
    }
    if (showDevicePicker) {
        TeslaDevicePickerDialog(
            devices = state.pairedDevices,
            selectedAddresses = state.teslaDevices.map { it.device.address }.toSet(),
            loading = state.pairedDevicesLoading,
            onConfirm = { selected ->
                showDevicePicker = false
                viewModel.setTeslaDevices(selected)
            },
            onCancel = { showDevicePicker = false }
        )
    }

    val chooserTitle = stringResource(R.string.diagnostics_share_chooser_title)
    val exportFailed = stringResource(R.string.diagnostics_share_failed)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.Share ->
                    context.startActivity(DiagnosticsShare.chooser(context, event.file, chooserTitle))
                SettingsEvent.ExportFailed ->
                    android.widget.Toast.makeText(context, exportFailed, android.widget.Toast.LENGTH_SHORT).show()
                is SettingsEvent.OpenTeslaAuthUrl ->
                    androidx.browser.customtabs.CustomTabsIntent.Builder().build()
                        .launchUrl(context, android.net.Uri.parse(event.url))
                is SettingsEvent.Feedback ->
                    android.widget.Toast.makeText(context, event.message, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    MfsScaffold(title = stringResource(R.string.settings_title), bottomBar = bottomBar) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MfsSpacing.xl, vertical = MfsSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(MfsSpacing.lg)
        ) {
            SectionHeader(stringResource(R.string.settings_section_forwarding))

            SettingCard(
                title = stringResource(R.string.settings_ttl_title),
                description = stringResource(R.string.settings_ttl_desc)
            ) {
                Text(
                    pluralStringResource(R.plurals.settings_ttl_hours, state.ttlHours, state.ttlHours),
                    style = MaterialTheme.typography.bodyLarge
                )
                Slider(
                    value = state.ttlHours.toFloat(),
                    onValueChange = { viewModel.setTtlHours(it.toInt().coerceIn(1, 168)) },
                    valueRange = 1f..168f
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.settings_ttl_slider_min), style = MaterialTheme.typography.labelSmall)
                    Text(stringResource(R.string.settings_ttl_slider_max), style = MaterialTheme.typography.labelSmall)
                }
            }

            SettingCard(
                title = stringResource(R.string.settings_budget_title),
                description = stringResource(R.string.settings_budget_desc)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.settings_budget_enabled_label),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Switch(
                        checked = state.sendBudgetEnabled,
                        onCheckedChange = { enabled ->
                            // Einschalten sofort; Ausschalten erst nach Warn-Bestätigung.
                            if (enabled) viewModel.setBudgetEnabled(true) else showBudgetWarn = true
                        }
                    )
                }
                if (state.sendBudgetEnabled) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            pluralStringResource(R.plurals.settings_budget_messages, state.sendBudget, state.sendBudget),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            stringResource(R.string.settings_budget_today, state.sendCountToday),
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
                } else {
                    Text(
                        stringResource(R.string.settings_budget_disabled_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            TeslaConnectionCard(
                devices = state.teslaDevices,
                hasPermission = state.hasBluetoothPermission,
                permanentlyDenied = btPermanentlyDenied,
                onGrantPermission = { btPermLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT) },
                onOpenAppSettings = openAppSettings,
                onSelectDevices = {
                    viewModel.loadPairedDevices()
                    showDevicePicker = true
                },
                onRemoveDevice = { viewModel.removeTeslaDevice(it) }
            )

            SectionHeader(stringResource(R.string.tesla_api_section))
            TeslaCredentialsCard(state = state, viewModel = viewModel)
            TeslaFleetApiCard(state = state, viewModel = viewModel)

            SectionHeader(stringResource(R.string.settings_section_apps))
            SettingCard(
                title = stringResource(R.string.settings_whitelist_title),
                description = stringResource(R.string.settings_whitelist_desc),
                variant = MfsCardVariant.Outlined
            ) {
                MfsListItem(
                    title = stringResource(R.string.settings_whitelist_open),
                    trailing = { androidx.compose.material3.Icon(Icons.Outlined.ChevronRight, null) },
                    onClick = onOpenWhitelist
                )
            }

            SectionHeader(stringResource(R.string.settings_section_language))
            LanguageSettingCard(
                currentTag = state.languageTag,
                onSelect = { viewModel.setLanguage(it) }
            )

            SectionHeader(stringResource(R.string.settings_section_support))
            SettingCard(
                title = stringResource(R.string.settings_send_diagnostics_title),
                description = stringResource(R.string.settings_send_diagnostics_desc)
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.settings_send_diagnostics_button),
                    onClick = { viewModel.shareDiagnostics() },
                    loading = state.sendingDiagnostics
                )
                Text(
                    stringResource(R.string.settings_send_diagnostics_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        onOpenChannels = onOpenChannels,
                        onRestartSetup = onRestartSetup
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

/**
 * Eingabe der nutzer-eigenen Tesla-App-Credentials (developer.tesla.com) —
 * gleiches Muster wie die xAI-Key-Karte im Assistant-Screen: Status-Pill,
 * maskierte Eingabefelder mit Sichtbarkeits-Toggle, Speichern/Entfernen.
 * Zusätzlich die exakte Redirect-URI (kopierbar), die der Nutzer in seiner
 * Tesla-App-Registrierung hinterlegen muss.
 */
@Composable
private fun TeslaCredentialsCard(state: SettingsUiState, viewModel: SettingsViewModel) {
    val clipboard = LocalClipboardManager.current
    SettingCard(
        title = stringResource(R.string.tesla_credentials_title),
        description = stringResource(R.string.tesla_credentials_desc)
    ) {
        StatusPill(
            text = if (state.teslaCredentialsSet) stringResource(R.string.tesla_credentials_status_set)
            else stringResource(R.string.tesla_credentials_status_none),
            status = if (state.teslaCredentialsSet) MfsStatus.Success else MfsStatus.Neutral
        )
        MaskedCredentialField(
            value = state.teslaClientIdDraft,
            onValueChange = viewModel::setTeslaClientIdDraft,
            label = stringResource(R.string.tesla_credentials_client_id_label)
        )
        MaskedCredentialField(
            value = state.teslaClientSecretDraft,
            onValueChange = viewModel::setTeslaClientSecretDraft,
            label = stringResource(R.string.tesla_credentials_client_secret_label)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(MfsSpacing.sm)) {
            PrimaryActionButton(
                text = stringResource(R.string.tesla_credentials_save),
                onClick = { viewModel.saveTeslaCredentials() },
                enabled = state.teslaClientIdDraft.isNotBlank() &&
                    state.teslaClientSecretDraft.isNotBlank(),
                loading = state.teslaCredentialsSaving
            )
            TextButton(
                onClick = { viewModel.clearTeslaCredentials() },
                enabled = state.teslaCredentialsSet && !state.teslaCredentialsSaving
            ) { Text(stringResource(R.string.tesla_credentials_remove)) }
        }
        if (state.teslaCredentialsSet) {
            Text(
                stringResource(R.string.tesla_credentials_remove_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            stringResource(R.string.tesla_credentials_redirect_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MfsSpacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                TeslaOAuthConfig.REDIRECT_URI,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                clipboard.setText(AnnotatedString(TeslaOAuthConfig.REDIRECT_URI))
            }) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(R.string.tesla_credentials_copy_redirect)
                )
            }
        }
    }
}

/** Maskiertes Eingabefeld mit Sichtbarkeits-Toggle — Spiegel des xAI-Key-Felds. */
@Composable
private fun MaskedCredentialField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = if (visible) VisualTransformation.None
        else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Outlined.VisibilityOff
                    else Icons.Outlined.Visibility,
                    contentDescription = if (visible) stringResource(R.string.tesla_credentials_hide)
                    else stringResource(R.string.tesla_credentials_show)
                )
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun TeslaFleetApiCard(state: SettingsUiState, viewModel: SettingsViewModel) {
    val authState = state.teslaAuthState
    SettingCard(
        title = stringResource(R.string.tesla_api_card_title),
        description = stringResource(R.string.tesla_api_card_desc)
    ) {
        when (authState) {
            is TeslaAuthState.MissingCredentials -> {
                // Kein Connect ohne Credentials — der Hinweis verweist auf die
                // Credentials-Karte direkt darüber ([TeslaCredentialsCard]).
                Text(
                    stringResource(R.string.tesla_api_missing_credentials),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            is TeslaAuthState.NotAuthenticated -> {
                PrimaryActionButton(
                    text = stringResource(R.string.tesla_api_connect),
                    onClick = { viewModel.startTeslaLogin() }
                )
            }
            is TeslaAuthState.Loading -> {
                Text(
                    stringResource(R.string.tesla_api_status_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            is TeslaAuthState.Authenticated -> {
                StatusPill(
                    text = stringResource(R.string.tesla_api_status_connected),
                    status = MfsStatus.Success
                )
                val vin = authState.selectedVin
                Text(
                    // VIN maskiert (…letzte 4) — volle VIN gehört nicht auf den Screen.
                    if (vin != null) stringResource(R.string.tesla_api_vehicle_selected, maskVin(vin))
                    else stringResource(R.string.tesla_api_no_vehicle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Multi-Tesla: Fahrzeug ↔ Bluetooth-Gerät verknüpfen, damit Grok das Auto
                // navigiert, in dem man sitzt. Nur sinnvoll, wenn Geräte gewählt sind.
                val teslaDevices = state.teslaDevices.map { it.device }
                var linkTarget by remember { mutableStateOf<VehicleInfo?>(null) }
                linkTarget?.let { vehicle ->
                    TeslaVehicleLinkDialog(
                        vehicle = vehicle,
                        devices = teslaDevices,
                        onLink = { address ->
                            linkTarget = null
                            viewModel.linkTeslaVehicle(vehicle, address)
                        },
                        onCancel = { linkTarget = null }
                    )
                }
                if (state.teslaVehicles.isNotEmpty()) {
                    if (teslaDevices.isNotEmpty()) {
                        Text(
                            stringResource(R.string.tesla_api_link_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(modifier = Modifier.selectableGroup()) {
                        state.teslaVehicles.forEach { vehicle ->
                            val selected = vehicle.vin == vin
                            val linkedDevice = teslaDevices.firstOrNull { it.vin == vehicle.vin }
                            val masked = maskVin(vehicle.vin)
                            MfsListItem(
                                title = vehicle.displayName.ifBlank { masked },
                                subtitle = if (linkedDevice != null) {
                                    "$masked · ${stringResource(R.string.tesla_api_linked_to, linkedDevice.name)}"
                                } else masked,
                                // Radio = Standard-Fahrzeug (Fallback des Resolvers); die ganze Zeile
                                // ist selectable, damit a11y „ausgewählt" ansagt.
                                modifier = Modifier.selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = { viewModel.selectTeslaVehicle(vehicle.vin, vehicle.id) }
                                ),
                                leading = { RadioButton(selected = selected, onClick = null) },
                                trailing = if (teslaDevices.isNotEmpty()) ({
                                    IconButton(onClick = { linkTarget = vehicle }) {
                                        Icon(
                                            imageVector = Icons.Outlined.Link,
                                            contentDescription = stringResource(R.string.tesla_api_link_device),
                                            tint = if (linkedDevice != null) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }) else null
                            )
                        }
                    }
                } else {
                    state.teslaVehiclesError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    // Region-Diagnose ist eine Dev-Oberfläche — nur im Developer-
                    // Mode zeigen (das ViewModel erhebt sie auch nur dann).
                    if (state.developerMode) {
                        state.teslaRegionDiagnostic?.let { diag ->
                            Text(
                                text = diag,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    PrimaryActionButton(
                        text = stringResource(R.string.tesla_api_load_vehicles),
                        loading = state.teslaVehiclesLoading,
                        onClick = { viewModel.loadTeslaVehicles() }
                    )
                }
                androidx.compose.material3.TextButton(onClick = { viewModel.logoutTesla() }) {
                    Text(stringResource(R.string.tesla_api_disconnect))
                }
            }
            is TeslaAuthState.Error -> {
                // Lokalisierte Fehlermeldung + optionales technisches Detail (HTTP/OAuth-Body).
                val base = stringResource(authState.messageRes)
                val message = authState.detail?.let { "$base ($it)" } ?: base
                Text(
                    stringResource(R.string.tesla_api_error_prefix, message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                PrimaryActionButton(
                    text = stringResource(R.string.tesla_api_connect),
                    onClick = { viewModel.startTeslaLogin() }
                )
            }
        }
    }
}

@Composable
private fun LanguageSettingCard(
    currentTag: String,
    onSelect: (String) -> Unit
) {
    // "" = Systemsprache folgen, sonst "de"/"en". OS-Picker kann auch Regions-Tags
    // ("de-DE") liefern — daher per Sprach-Präfix auf die drei Optionen normalisieren.
    val selected = when {
        currentTag.isBlank() -> ""
        currentTag.startsWith("de") -> "de"
        currentTag.startsWith("en") -> "en"
        else -> ""
    }
    SettingCard(
        title = stringResource(R.string.settings_language_title),
        description = stringResource(R.string.settings_language_desc)
    ) {
        Column(modifier = Modifier.selectableGroup()) {
            LanguageOption(stringResource(R.string.settings_language_system), selected == "") { onSelect("") }
            LanguageOption(stringResource(R.string.settings_language_german), selected == "de") { onSelect("de") }
            LanguageOption(stringResource(R.string.settings_language_english), selected == "en") { onSelect("en") }
        }
    }
}

@Composable
private fun LanguageOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = MfsSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MfsSpacing.sm)
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Warn-Dialog vor dem Abschalten des Tageslimits (zwei konkrete Risiken). */
@Composable
private fun BudgetDisableDialog(
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null) },
        title = { Text(stringResource(R.string.settings_budget_dialog_title)) },
        text = { Text(stringResource(R.string.settings_budget_dialog_text)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.settings_budget_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.settings_budget_dialog_cancel))
            }
        }
    )
}

@Composable
private fun DeveloperSettings(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onOpenDiagnostics: () -> Unit,
    onOpenChannels: () -> Unit,
    onRestartSetup: () -> Unit
) {
    SectionHeader(stringResource(R.string.settings_section_developer))

    SettingCard(
        title = stringResource(R.string.settings_preflight_title),
        description = stringResource(R.string.settings_preflight_desc)
    ) {
        val (statusText, status) = preflightStatusUi(state.preflightStatus)
        StatusPill(text = statusText, status = status)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MfsSpacing.sm)
        ) {
            PrimaryActionButton(
                text = stringResource(R.string.settings_preflight_run),
                onClick = { viewModel.runPreflight() },
                loading = state.preflightRunning
            )
            TextButton(
                onClick = { viewModel.resetPreflight() },
                enabled = !state.preflightRunning
            ) { Text(stringResource(R.string.settings_preflight_reset)) }
        }
    }

    SettingCard(
        title = stringResource(R.string.settings_contacts_title),
        description = stringResource(R.string.settings_contacts_desc)
    ) {
        Text(
            when {
                state.teslaContactsHasPermission ->
                    pluralStringResource(
                        R.plurals.settings_contacts_count,
                        state.teslaContactCount,
                        state.teslaContactCount
                    )
                !state.teslaContactsHasRead && !state.teslaContactsHasWrite ->
                    stringResource(R.string.settings_contacts_perm_missing)
                !state.teslaContactsHasWrite ->
                    stringResource(R.string.settings_contacts_write_missing)
                else ->
                    stringResource(R.string.settings_contacts_read_missing)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.teslaContactsHasPermission) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.error
        )
        TextButton(
            enabled = state.teslaContactsHasPermission && !state.teslaContactsResetting,
            onClick = { viewModel.resetTeslaContacts() }
        ) {
            Text(
                if (state.teslaContactsResetting) stringResource(R.string.settings_contacts_resyncing)
                else stringResource(R.string.settings_contacts_resync)
            )
        }
    }

    SettingCard(
        title = stringResource(R.string.settings_diagnostics_title),
        description = stringResource(R.string.settings_diagnostics_desc),
        variant = MfsCardVariant.Outlined
    ) {
        MfsListItem(
            title = stringResource(R.string.settings_diagnostics_item_title),
            subtitle = stringResource(R.string.settings_diagnostics_item_subtitle),
            trailing = { androidx.compose.material3.Icon(Icons.Outlined.ChevronRight, null) },
            onClick = onOpenDiagnostics
        )
        HorizontalDivider()
        MfsListItem(
            title = stringResource(R.string.settings_channels_item_title),
            subtitle = stringResource(R.string.settings_channels_item_subtitle),
            trailing = { androidx.compose.material3.Icon(Icons.Outlined.ChevronRight, null) },
            onClick = onOpenChannels
        )
    }

    SettingCard(
        title = stringResource(R.string.settings_restart_setup_title),
        description = stringResource(R.string.settings_restart_setup_desc)
    ) {
        TextButton(onClick = onRestartSetup) {
            Text(stringResource(R.string.settings_restart_setup_button))
        }
    }

    SettingCard(
        title = stringResource(R.string.settings_devmode_title),
        description = stringResource(R.string.settings_devmode_desc)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.settings_devmode_active), style = MaterialTheme.typography.bodyLarge)
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
    val devModeEnabledToast = stringResource(R.string.settings_devmode_enabled_toast)
    var tapCount by remember { mutableIntStateOf(0) }
    Text(
        text = stringResource(R.string.settings_version_label, BuildConfig.VERSION_NAME),
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
                        .makeText(context, devModeEnabledToast, android.widget.Toast.LENGTH_SHORT)
                        .show()
                }
            }
            .padding(vertical = MfsSpacing.sm)
    )
}
