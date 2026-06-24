package io.github.lycheeappf.tmm.ui.screen.onboarding

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lycheeappf.tmm.contact.ContactBackfillWorker
import io.github.lycheeappf.tmm.core.di.IoDispatcher
import io.github.lycheeappf.tmm.data.store.SettingsStore
import io.github.lycheeappf.tmm.platform.bluetooth.BluetoothConnectionChecker
import io.github.lycheeappf.tmm.platform.bluetooth.PairedBtDevice
import io.github.lycheeappf.tmm.platform.permission.PermissionGate
import io.github.lycheeappf.tmm.platform.role.DefaultSmsRoleManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

// Kein Anzeige-Label hier: der Onboarding-Screen rendert seine Schritt-Titel selbst
// aus lokalisierten String-Ressourcen. Dieses Enum dient nur der Schritt-Logik.
enum class OnboardingStep {
    DefaultSmsApp,
    NotificationAccess,
    PostNotifications,
    ContactsAccess,
    PreFlightTest,
    Done
}

data class OnboardingUiState(
    val currentStep: OnboardingStep = OnboardingStep.DefaultSmsApp,
    val isDefaultSmsApp: Boolean = false,
    val roleManagerHeld: Boolean = false,
    val telephonyMatches: Boolean = false,
    val currentDefaultPackage: String? = null,
    val ourPackage: String = "",
    val hasNotificationAccess: Boolean = false,
    val hasPostNotifications: Boolean = false,
    val hasContactsAccess: Boolean = false,
    val contactsSkipped: Boolean = false,
    val preflightStatus: String? = null,
    val preflightRunning: Boolean = false,
    val preflightTargetAddress: String = "",
    val riskAcknowledged: Boolean = false,
    /** Optionaler Schritt: gewähltes Tesla-Bluetooth-Gerät; null = keins. */
    val teslaBtDeviceName: String? = null,
    val hasBluetoothPermission: Boolean = false,
    val pairedDevices: List<PairedBtDevice> = emptyList()
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val roleManager: DefaultSmsRoleManager,
    private val permissionGate: PermissionGate,
    private val settingsStore: SettingsStore,
    private val preFlightTester: PreFlightTester,
    private val bluetoothConnectionChecker: BluetoothConnectionChecker,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    private data class Snapshot(
        val roleStatus: DefaultSmsRoleManager.Status,
        val hasNls: Boolean,
        val hasPostNotif: Boolean,
        val hasContacts: Boolean,
        val contactsSkipped: Boolean,
        val preflight: String?,
        val preflightAddress: String,
        val riskAcked: Boolean,
        val teslaBtName: String?,
        val hasBluetooth: Boolean
    )

    fun refresh() {
        viewModelScope.launch {
            val snap = withContext(ioDispatcher) {
                Snapshot(
                    roleStatus = roleManager.detailedStatus(),
                    hasNls = permissionGate.hasNotificationListenerAccess(),
                    hasPostNotif = permissionGate.hasPostNotifications(),
                    hasContacts = permissionGate.hasContactsAccess(),
                    contactsSkipped = settingsStore.isContactsStepSkipped(),
                    preflight = settingsStore.preflightResult(),
                    preflightAddress = preFlightTester.targetAddress(),
                    riskAcked = settingsStore.isRiskAcknowledged(),
                    teslaBtName = settingsStore.teslaBtName(),
                    hasBluetooth = permissionGate.hasBluetoothConnect()
                )
            }
            val currentStep = determineStep(
                isDefault = snap.roleStatus.isDefault,
                hasNls = snap.hasNls,
                hasPostNotif = snap.hasPostNotif,
                hasContacts = snap.hasContacts,
                contactsSkipped = snap.contactsSkipped,
                preflight = snap.preflight,
                riskAcked = snap.riskAcked
            )
            _uiState.update {
                it.copy(
                    currentStep = currentStep,
                    isDefaultSmsApp = snap.roleStatus.isDefault,
                    roleManagerHeld = snap.roleStatus.roleManagerHeld,
                    telephonyMatches = snap.roleStatus.telephonyMatches,
                    currentDefaultPackage = snap.roleStatus.currentDefaultPackage,
                    ourPackage = snap.roleStatus.ourPackage,
                    hasNotificationAccess = snap.hasNls,
                    hasPostNotifications = snap.hasPostNotif,
                    hasContactsAccess = snap.hasContacts,
                    contactsSkipped = snap.contactsSkipped,
                    preflightStatus = snap.preflight,
                    preflightTargetAddress = snap.preflightAddress,
                    riskAcknowledged = snap.riskAcked,
                    teslaBtDeviceName = snap.teslaBtName,
                    hasBluetoothPermission = snap.hasBluetooth
                )
            }
        }
    }

    /** Lädt die gekoppelten Bluetooth-Geräte für den Tesla-Auswahl-Dialog. */
    fun loadPairedDevices() {
        viewModelScope.launch {
            val devices = withContext(ioDispatcher) { bluetoothConnectionChecker.pairedDevices() }
            _uiState.update { it.copy(pairedDevices = devices) }
        }
    }

    /** Merkt sich das gewählte Tesla-Gerät → ab jetzt wird nur verbunden weitergeleitet. */
    fun selectTeslaDevice(address: String, name: String) {
        viewModelScope.launch {
            withContext(ioDispatcher) { settingsStore.setTeslaBtDevice(address, name) }
            refresh()
        }
    }

    /** Hebt die Tesla-Gerätewahl auf → Verbindungs-Gate aus, Weiterleitung rund um die Uhr. */
    fun clearTeslaDevice() {
        viewModelScope.launch {
            withContext(ioDispatcher) { settingsStore.clearTeslaBtDevice() }
            refresh()
        }
    }

    /** Permission gerade gewährt → Backfill für existierende Mappings starten. */
    fun onContactsPermissionGranted() {
        ContactBackfillWorker.enqueue(context)
        refresh()
    }

    fun skipContactsStep() {
        viewModelScope.launch {
            withContext(ioDispatcher) { settingsStore.setContactsStepSkipped(true) }
            refresh()
        }
    }

    fun acknowledgeRisk() {
        viewModelScope.launch {
            withContext(ioDispatcher) { settingsStore.setRiskAcknowledged(true) }
            refresh()
        }
    }

    fun runPreFlight() {
        viewModelScope.launch {
            _uiState.update { it.copy(preflightRunning = true) }
            withContext(ioDispatcher) { preFlightTester.run() }
            refresh()
            _uiState.update { it.copy(preflightRunning = false) }
        }
    }

    fun markOnboarded(onDone: () -> Unit) {
        viewModelScope.launch {
            withContext(ioDispatcher) { settingsStore.setOnboarded(true) }
            onDone()
        }
    }

    fun defaultSmsIntent(): Intent =
        roleManager.createRequestIntent()
            ?: Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)

    private fun determineStep(
        isDefault: Boolean,
        hasNls: Boolean,
        hasPostNotif: Boolean,
        hasContacts: Boolean,
        contactsSkipped: Boolean,
        preflight: String?,
        riskAcked: Boolean
    ): OnboardingStep = when {
        !isDefault -> OnboardingStep.DefaultSmsApp
        !hasNls -> OnboardingStep.NotificationAccess
        !hasPostNotif -> OnboardingStep.PostNotifications
        !hasContacts && !contactsSkipped -> OnboardingStep.ContactsAccess
        preflight == null || preflight == SettingsStore.PREFLIGHT_RUNNING ->
            OnboardingStep.PreFlightTest
        preflight == SettingsStore.PREFLIGHT_OK -> OnboardingStep.Done
        preflight == SettingsStore.PREFLIGHT_RISK -> if (riskAcked) OnboardingStep.Done
            else OnboardingStep.PreFlightTest
        // TIMEOUT, ERROR und alles andere: User MUSS Test nochmal laufen lassen.
        else -> OnboardingStep.PreFlightTest
    }
}
