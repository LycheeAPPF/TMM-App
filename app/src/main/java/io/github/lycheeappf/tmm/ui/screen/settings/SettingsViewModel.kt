package io.github.lycheeappf.tmm.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lycheeappf.tmm.contact.ContactSyncWriter
import io.github.lycheeappf.tmm.contact.TeslaContactResync
import io.github.lycheeappf.tmm.core.di.IoDispatcher
import io.github.lycheeappf.tmm.core.locale.AppLocaleManager
import io.github.lycheeappf.tmm.core.notification.AppNotificationChannels
import io.github.lycheeappf.tmm.core.util.DiagnosticsExporter
import io.github.lycheeappf.tmm.core.util.coRunCatching
import io.github.lycheeappf.tmm.data.store.SettingsStore
import io.github.lycheeappf.tmm.platform.bluetooth.BluetoothConnectionChecker
import io.github.lycheeappf.tmm.platform.bluetooth.PairedBtDevice
import io.github.lycheeappf.tmm.platform.permission.PermissionGate
import io.github.lycheeappf.tmm.platform.tesla.api.VehicleInfo
import io.github.lycheeappf.tmm.platform.tesla.api.TeslaVehicleCommandClient
import io.github.lycheeappf.tmm.platform.tesla.auth.TeslaAuthManager
import io.github.lycheeappf.tmm.platform.tesla.auth.TeslaAuthState
import io.github.lycheeappf.tmm.platform.tesla.auth.TeslaOAuthConfig
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
    /** Anzeigename des gewählten Tesla-Bluetooth-Geräts; null = keins gewählt. */
    val teslaBtDeviceName: String? = null,
    /** MAC des gewählten Geräts (zum Vorauswählen im Picker). */
    val teslaBtAddress: String? = null,
    val hasBluetoothPermission: Boolean = false,
    /** Gewähltes Gerät ist nicht mehr gekoppelt → Weiterleitung stillschweigend tot. */
    val teslaDeviceMissing: Boolean = false,
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
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
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
        // Tesla-Auth-State live beobachten und in UiState spiegeln.
        viewModelScope.launch {
            teslaAuthManager.init()
            teslaAuthManager.state.collect { authState ->
                _uiState.update { it.copy(teslaAuthState = authState) }
            }
        }
        // OAuth-Callback-Code abarbeiten, wenn MainActivity ihn posted.
        viewModelScope.launch {
            teslaAuthManager.pendingCode.collect { code ->
                withContext(ioDispatcher) { teslaAuthManager.exchangeCode(code) }
                loadTeslaVehicles()
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
            val btAddress = store.teslaBtAddress()
            val hasBt = permissionGate.hasBluetoothConnect()
            // Gespeichertes Gerät nicht mehr gekoppelt? Dann gated der Check stillschweigend
            // alles weg — als Warnung surfacen. Nur prüfbar mit Permission.
            val deviceMissing = btAddress != null && hasBt &&
                bluetoothConnectionChecker.pairedDevices().none { it.address.equals(btAddress, ignoreCase = true) }
            _uiState.update {
                it.copy(
                    ttlHours = store.mappingTtlHours(),
                    sendBudget = store.sendBudgetPerDay(),
                    sendBudgetEnabled = store.isSendBudgetEnabled(),
                    sendCountToday = store.dailySendCount(),
                    teslaBtDeviceName = store.teslaBtName(),
                    teslaBtAddress = btAddress,
                    hasBluetoothPermission = hasBt,
                    teslaDeviceMissing = deviceMissing,
                    preflightStatus = store.preflightResult(),
                    developerMode = store.isDeveloperMode(),
                    languageTag = appLocaleManager.currentTag()
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

    /** Merkt sich das gewählte Tesla-Gerät → ab jetzt wird nur verbunden weitergeleitet. */
    fun selectTeslaDevice(address: String, name: String) {
        viewModelScope.launch(ioDispatcher) {
            store.setTeslaBtDevice(address, name)
            refreshSettings()
        }
    }

    /** Hebt die Tesla-Gerätewahl auf → Verbindungs-Gate aus, Weiterleitung wieder rund um die Uhr. */
    fun clearTeslaDevice() {
        viewModelScope.launch(ioDispatcher) {
            store.clearTeslaBtDevice()
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

    fun startTeslaLogin() {
        val url = teslaAuthManager.startAuth()
        viewModelScope.launch { _events.send(SettingsEvent.OpenTeslaAuthUrl(url)) }
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
            val diagnostic = if (result.isFailure) {
                withContext(ioDispatcher) {
                    coRunCatching { teslaCommandClient.regionDiagnosticInfo() }.getOrDefault("Diagnose fehlgeschlagen")
                }
            } else null
            _uiState.update {
                it.copy(
                    teslaVehicles = result.getOrDefault(emptyList()),
                    teslaVehiclesLoading = false,
                    teslaVehiclesError = result.exceptionOrNull()?.message,
                    teslaRegionDiagnostic = diagnostic
                )
            }
        }
    }

}
