package io.github.lycheeappf.tmm.ui.screen.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lycheeappf.tmm.R
import io.github.lycheeappf.tmm.contact.ContactSyncWriter
import io.github.lycheeappf.tmm.contact.TeslaContactResync
import io.github.lycheeappf.tmm.core.di.IoDispatcher
import io.github.lycheeappf.tmm.core.locale.AppLocaleManager
import io.github.lycheeappf.tmm.core.locale.localizedString
import io.github.lycheeappf.tmm.core.notification.AppNotificationChannels
import io.github.lycheeappf.tmm.core.util.DiagnosticsExporter
import io.github.lycheeappf.tmm.core.util.coRunCatching
import io.github.lycheeappf.tmm.data.store.SettingsStore
import io.github.lycheeappf.tmm.domain.tesla.TeslaDevice
import io.github.lycheeappf.tmm.platform.bluetooth.BluetoothConnectionChecker
import io.github.lycheeappf.tmm.platform.bluetooth.PairedBtDevice
import io.github.lycheeappf.tmm.platform.bluetooth.TeslaDeviceStatus
import io.github.lycheeappf.tmm.platform.permission.PermissionGate
import io.github.lycheeappf.tmm.platform.tesla.api.TeslaCommandError
import io.github.lycheeappf.tmm.platform.tesla.api.VehicleInfo
import io.github.lycheeappf.tmm.platform.tesla.api.TeslaVehicleCommandClient
import io.github.lycheeappf.tmm.platform.tesla.api.userMessage
import io.github.lycheeappf.tmm.platform.tesla.auth.TeslaAuthManager
import io.github.lycheeappf.tmm.platform.tesla.auth.TeslaAuthState
import io.github.lycheeappf.tmm.ui.screen.onboarding.PreFlightTester
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SettingsUiState(
    val ttlHours: Int = SettingsStore.DEFAULT_TTL_HOURS,
    val sendBudget: Int = SettingsStore.DEFAULT_SEND_BUDGET,
    val sendBudgetEnabled: Boolean = true,
    val sendCountToday: Int = 0,
    /**
     * Gewählte Tesla-Bluetooth-Geräte mit Zeilen-Zustand (nicht mehr gekoppelt /
     * verbunden / verknüpftes Fahrzeug); leer = keins gewählt → Gate aus.
     */
    val teslaDevices: List<TeslaDeviceStatus> = emptyList(),
    val hasBluetoothPermission: Boolean = false,
    /** Gekoppelte Geräte für den Auswahl-Dialog (on-demand geladen). */
    val pairedDevices: List<PairedBtDevice> = emptyList(),
    val pairedDevicesLoading: Boolean = false,
    val teslaContactCount: Int = 0,
    val teslaContactsHasPermission: Boolean = false,
    val teslaContactsHasRead: Boolean = false,
    val teslaContactsHasWrite: Boolean = false,
    val teslaContactsResetting: Boolean = false,
    val preflightStatus: String? = null,
    val preflightRunning: Boolean = false,
    val developerMode: Boolean = false,
    /** Aktive App-Sprache: "" = Systemsprache folgen, sonst BCP-47-Tag ("de"/"en"). */
    val languageTag: String = "",
    /** Läuft, während der „Diagnose senden"-Export geschrieben wird. */
    val sendingDiagnostics: Boolean = false,
    /** Tesla Fleet API Auth-Status. */
    val teslaAuthState: TeslaAuthState = TeslaAuthState.Loading,
    /** Nutzer-eigene Tesla-App-Credentials (developer.tesla.com) hinterlegt? */
    val teslaCredentialsSet: Boolean = false,
    /** Eingabe-Entwürfe für die Tesla-Credentials — nie persistiert, nur bis „Speichern". */
    val teslaClientIdDraft: String = "",
    val teslaClientSecretDraft: String = "",
    val teslaCredentialsSaving: Boolean = false,
    /** Fahrzeuge des eingeloggten Tesla-Accounts (geladen nach Login). */
    val teslaVehicles: List<VehicleInfo> = emptyList(),
    val teslaVehiclesLoading: Boolean = false,
    /** Fehlermeldung vom letzten Fahrzeugladen — null = kein Fehler. */
    val teslaVehiclesError: String? = null,
    /** Rohausgabe der Region-Diagnose — nur bei Fehler gefüllt. */
    val teslaRegionDiagnostic: String? = null
)

sealed class SettingsEvent {
    data class Share(val file: java.io.File) : SettingsEvent()
    data object ExportFailed : SettingsEvent()
    data class OpenTeslaAuthUrl(val url: String) : SettingsEvent()

    /** Kurzes Text-Feedback (Toast) — bereits lokalisiert im ViewModel aufgelöst. */
    data class Feedback(val message: String) : SettingsEvent()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: SettingsStore,
    private val contactSyncWriter: ContactSyncWriter,
    private val teslaContactResync: TeslaContactResync,
    private val preFlightTester: PreFlightTester,
    private val appLocaleManager: AppLocaleManager,
    private val notificationChannels: AppNotificationChannels,
    private val diagnosticsExporter: DiagnosticsExporter,
    private val permissionGate: PermissionGate,
    private val bluetoothConnectionChecker: BluetoothConnectionChecker,
    private val teslaAuthManager: TeslaAuthManager,
    private val teslaCommandClient: TeslaVehicleCommandClient,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** One-Shot-Events (Share-Sheet öffnen / Fehler-Toast / Tesla-Auth-URL) an die UI. */
    private val _events = Channel<SettingsEvent>(Channel.BUFFERED)
    val events: Flow<SettingsEvent> = _events.receiveAsFlow()

    init {
        refresh()
        // Tesla-Auth-State live beobachten und in UiState spiegeln. Der OAuth-
        // Callback-Exchange läuft application-scoped im TeslaAuthManager (auch
        // ohne lebende UI) — hier wird nur beobachtet; beim Übergang zu
        // Authenticated werden die Fahrzeuge für den Auswahl-Dialog geladen.
        viewModelScope.launch {
            teslaAuthManager.init()
            var previous: TeslaAuthState? = null
            teslaAuthManager.state.collect { authState ->
                _uiState.update { it.copy(teslaAuthState = authState) }
                if (authState is TeslaAuthState.Authenticated && previous != null &&
                    previous !is TeslaAuthState.Authenticated
                ) {
                    loadTeslaVehicles()
                }
                previous = authState
            }
        }
    }

    /**
     * „Diagnose senden": schreibt den redigierten Export (IO) und emittiert ein
     * [DiagnosticsEvent], das die UI in ein Android-Share-Sheet übersetzt. Bewusst
     * ohne Developer-Mode erreichbar — der einfachste Weg für Tester (ein Tap, eine Datei).
     */
    fun shareDiagnostics() {
        viewModelScope.launch {
            _uiState.update { it.copy(sendingDiagnostics = true) }
            val file = withContext(ioDispatcher) {
                coRunCatching { diagnosticsExporter.exportToCache() }.getOrNull()
            }
            _uiState.update { it.copy(sendingDiagnostics = false) }
            _events.send(
                if (file != null) SettingsEvent.Share(file) else SettingsEvent.ExportFailed
            )
        }
    }

    /**
     * Voller Refresh (Settings + Contact-State). Nur für init/Resume und nach dem
     * Tesla-Contacts-Reset aufrufen — NICHT aus den Settern. [refreshContactState]
     * macht eine blockierende ContentResolver-Query (contactCount), und der
     * Kontakt-Zustand ändert sich bei einem Slider-/Toggle-Change nicht.
     */
    fun refresh() {
        refreshSettings()
        refreshContactState()
    }

    /** Billige DataStore-Reads — wird nach jedem Setter aufgerufen. */
    private fun refreshSettings() {
        viewModelScope.launch(ioDispatcher) {
            val hasBt = permissionGate.hasBluetoothConnect()
            // Zeilen-Zustände (nicht mehr gekoppelt / verbunden) kommen aus dem Checker —
            // ein entkoppeltes Gerät würde sonst stillschweigend alles weggaten.
            val teslaDevices = bluetoothConnectionChecker.teslaDeviceStatuses()
            _uiState.update {
                it.copy(
                    ttlHours = store.mappingTtlHours(),
                    sendBudget = store.sendBudgetPerDay(),
                    sendBudgetEnabled = store.isSendBudgetEnabled(),
                    sendCountToday = store.dailySendCount(),
                    teslaDevices = teslaDevices,
                    hasBluetoothPermission = hasBt,
                    preflightStatus = store.preflightResult(),
                    developerMode = store.isDeveloperMode(),
                    languageTag = appLocaleManager.currentTag(),
                    teslaCredentialsSet = teslaAuthManager.hasCredentials()
                )
            }
        }
    }

    /** Contact-Permissions + Tesla-Bridge-Contact-Count via ContentResolver (teuer, IPC). */
    private fun refreshContactState() {
        viewModelScope.launch(ioDispatcher) {
            val hasRead = contactSyncWriter.hasReadContacts()
            val hasWrite = contactSyncWriter.hasWriteContacts()
            val hasContacts = hasRead && hasWrite
            val count = if (hasContacts) contactSyncWriter.contactCount() else 0
            _uiState.update {
                it.copy(
                    teslaContactCount = count,
                    teslaContactsHasPermission = hasContacts,
                    teslaContactsHasRead = hasRead,
                    teslaContactsHasWrite = hasWrite
                )
            }
        }
    }

    fun setDeveloperMode(value: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            store.setDeveloperMode(value)
            refreshSettings()
        }
    }

    /**
     * Setzt die App-Sprache ("" = Systemsprache). Läuft synchron auf dem Main-Thread:
     * [AppLocaleManager.setLanguageTag] triggert (API 33+) automatisch das Activity-
     * Recreate. Danach werden die Notification-Channels mit den neuen lokalisierten
     * Namen neu angelegt — das System cacht die Channel-Namen sonst in der alten Sprache.
     */
    fun setLanguage(tag: String) {
        appLocaleManager.setLanguageTag(tag)
        notificationChannels.ensure()
    }

    /**
     * Force-Reset der Tesla-Bridge-Contacts. Löscht alle existierenden
     * RawContacts, entfernt den Account, lässt ihn vom nächsten Backfill neu
     * anlegen. Erzwingt damit einen PBAP-Sync-Version-Counter-Increment, sodass
     * Tesla beim nächsten Connect die Contacts frisch holt.
     */
    fun resetTeslaContacts() {
        viewModelScope.launch(ioDispatcher) { forceTeslaResync() }
    }

    /**
     * Gemeinsamer Force-Resync-Pfad: alle Bridge-Contacts löschen, Account
     * entfernen (bumpt den `account_changes`-Counter → Tesla zieht neu) und neu
     * provisionieren lassen. Delegiert an [TeslaContactResync]; hier nur das
     * UI-Flag + Refresh. Muss aus einem `ioDispatcher`-Scope gerufen werden.
     */
    private suspend fun forceTeslaResync() {
        _uiState.update { it.copy(teslaContactsResetting = true) }
        teslaContactResync.force()
        _uiState.update { it.copy(teslaContactsResetting = false) }
        refresh()
    }

    fun setTtlHours(value: Int) {
        viewModelScope.launch(ioDispatcher) {
            store.setMappingTtlHours(value)
            refreshSettings()
        }
    }

    fun setSendBudget(value: Int) {
        viewModelScope.launch(ioDispatcher) {
            store.setSendBudgetPerDay(value)
            refreshSettings()
        }
    }

    /** Schaltet das Tageslimit ([SendBudget]) ganz ab/an. */
    fun setBudgetEnabled(value: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            store.setSendBudgetEnabled(value)
            refreshSettings()
        }
    }

    /**
     * Lädt die gekoppelten Bluetooth-Geräte für den Tesla-Auswahl-Dialog. Braucht
     * BLUETOOTH_CONNECT — ohne Permission bleibt die Liste leer.
     */
    fun loadPairedDevices() {
        // Sofort (synchron, Main) auf „lädt" setzen, damit der Picker beim Öffnen nicht
        // kurz fälschlich „keine Geräte" zeigt, bevor der IO-Load zurückkommt.
        _uiState.update { it.copy(pairedDevicesLoading = true, pairedDevices = emptyList()) }
        viewModelScope.launch(ioDispatcher) {
            val devices = bluetoothConnectionChecker.pairedDevices()
            _uiState.update { it.copy(pairedDevices = devices, pairedDevicesLoading = false) }
        }
    }

    /**
     * Ersetzt die Tesla-Geräteauswahl (Mehrfach-Picker) → ab jetzt wird nur
     * weitergeleitet, während eines dieser Geräte verbunden ist. Fahrzeug-Links
     * behaltener Geräte bleiben erhalten (Merge passiert atomar im Store).
     */
    fun setTeslaDevices(selected: List<PairedBtDevice>) {
        viewModelScope.launch(ioDispatcher) {
            store.setTeslaDevices(selected.map { TeslaDevice(address = it.address, name = it.name) })
            refreshSettings()
        }
    }

    /** Entfernt ein Gerät aus der Auswahl; ohne Geräte ist das Gate aus (rund um die Uhr). */
    fun removeTeslaDevice(address: String) {
        viewModelScope.launch(ioDispatcher) {
            store.removeTeslaDevice(address)
            refreshSettings()
        }
    }

    /**
     * Ordnet ein Fleet-Fahrzeug einem Tesla-Gerät zu (`address == null` = entkoppeln)
     * → Grok navigiert das Auto, mit dem das Handy gerade verbunden ist.
     */
    fun linkTeslaVehicle(vehicle: VehicleInfo, address: String?) {
        viewModelScope.launch(ioDispatcher) {
            store.linkVehicleToDevice(vehicle.vin, vehicle.id, address)
            refreshSettings()
        }
    }

    fun resetPreflight() {
        viewModelScope.launch(ioDispatcher) {
            store.setPreflightResult("")
            store.setRiskAcknowledged(false)
            refreshSettings()
        }
    }

    /**
     * Führt den Carrier-Pre-Flight JETZT aus (sendet eine Test-SMS an die +888-
     * Systemadresse und prüft, ob der Carrier sie kostenlos ablehnt). Spiegelt
     * [OnboardingViewModel.runPreFlight], damit der Test auch außerhalb des
     * Onboardings wiederholbar ist.
     */
    fun runPreflight() {
        viewModelScope.launch {
            _uiState.update { it.copy(preflightRunning = true) }
            withContext(ioDispatcher) {
                io.github.lycheeappf.tmm.core.util.coRunCatching { preFlightTester.run() }
            }
            refreshSettings()
            _uiState.update { it.copy(preflightRunning = false) }
        }
    }

    // ---- Tesla Fleet API ----------------------------------------------------

    fun setTeslaClientIdDraft(value: String) =
        _uiState.update { it.copy(teslaClientIdDraft = value) }

    fun setTeslaClientSecretDraft(value: String) =
        _uiState.update { it.copy(teslaClientSecretDraft = value) }

    /**
     * Persistiert die nutzer-eigenen Tesla-App-Credentials (verschlüsselt, via
     * [TeslaAuthManager.setCredentials] — re-initialisiert auch den Auth-State,
     * sodass der Connect-Button erscheint). Spiegel des xAI-Key-Speicherns:
     * Validierungsfehler des Stores (leer/CR-LF) landen als Feedback-Toast.
     */
    fun saveTeslaCredentials() {
        val clientId = _uiState.value.teslaClientIdDraft.trim()
        val clientSecret = _uiState.value.teslaClientSecretDraft.trim()
        if (clientId.isEmpty() || clientSecret.isEmpty()) return
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(teslaCredentialsSaving = true) }
            val feedback = try {
                teslaAuthManager.setCredentials(clientId, clientSecret)
                _uiState.update {
                    it.copy(
                        teslaClientIdDraft = "",
                        teslaClientSecretDraft = "",
                        teslaCredentialsSet = true
                    )
                }
                context.localizedString(R.string.tesla_credentials_feedback_saved)
            } catch (_: IllegalArgumentException) {
                // KeystoreTeslaCredentialsStore validiert auf leer/Zeilenumbrüche.
                context.localizedString(R.string.tesla_credentials_feedback_invalid)
            }
            _uiState.update { it.copy(teslaCredentialsSaving = false) }
            _events.send(SettingsEvent.Feedback(feedback))
        }
    }

    /**
     * Entfernt Credentials UND alle davon abhängigen Artefakte (Tokens, Region)
     * über [TeslaAuthManager.clearCredentials] → Auth-State wird
     * [TeslaAuthState.MissingCredentials], alle Fleet-Features sind gegated.
     */
    fun clearTeslaCredentials() {
        viewModelScope.launch(ioDispatcher) {
            teslaAuthManager.clearCredentials()
            _uiState.update {
                it.copy(
                    teslaCredentialsSet = false,
                    teslaVehicles = emptyList(),
                    teslaVehiclesError = null,
                    teslaRegionDiagnostic = null
                )
            }
            _events.send(
                SettingsEvent.Feedback(
                    context.localizedString(R.string.tesla_credentials_feedback_removed)
                )
            )
        }
    }

    fun startTeslaLogin() {
        viewModelScope.launch {
            // null = keine Credentials hinterlegt; der Manager hat den State
            // bereits auf MissingCredentials gesetzt → UI zeigt den Hinweis.
            val url = teslaAuthManager.startAuth() ?: return@launch
            _events.send(SettingsEvent.OpenTeslaAuthUrl(url))
        }
    }

    fun selectTeslaVehicle(vin: String, id: Long) {
        viewModelScope.launch(ioDispatcher) { teslaAuthManager.selectVehicle(vin, id) }
    }

    fun logoutTesla() {
        viewModelScope.launch(ioDispatcher) { teslaAuthManager.logout() }
        _uiState.update { it.copy(teslaVehicles = emptyList()) }
    }

    fun loadTeslaVehicles() {
        viewModelScope.launch {
            _uiState.update { it.copy(teslaVehiclesLoading = true, teslaVehiclesError = null, teslaRegionDiagnostic = null) }
            val result = withContext(ioDispatcher) {
                coRunCatching { teslaCommandClient.listVehicles() }
            }
            // Region-Diagnose ist eine Dev-Oberfläche (wie Channels/Diagnostics):
            // nur im Developer-Mode überhaupt erheben — normale Nutzer sehen die
            // lokalisierte Fehlermeldung, keine rohen Endpoint-Probes.
            val diagnostic = if (result.isFailure && _uiState.value.developerMode) {
                withContext(ioDispatcher) {
                    // null = kein Token / Diagnose selbst gescheitert → lokalisierter Hinweis.
                    coRunCatching { teslaCommandClient.regionDiagnosticInfo() }.getOrNull()
                        ?: context.localizedString(R.string.tesla_api_diagnostic_failed)
                }
            } else null
            _uiState.update { state ->
                state.copy(
                    teslaVehicles = result.getOrDefault(emptyList()),
                    teslaVehiclesLoading = false,
                    teslaVehiclesError = result.exceptionOrNull()?.let { e ->
                        // Typisierte Fleet-Fehler lokalisiert anzeigen (EN+DE).
                        if (e is TeslaCommandError) e.userMessage(context) else e.message
                    },
                    teslaRegionDiagnostic = diagnostic
                )
            }
        }
    }

}
