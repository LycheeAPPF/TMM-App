package io.github.lycheeappf.tmm.ui.screen.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lycheeappf.tmm.contact.ContactBackfillWorker
import io.github.lycheeappf.tmm.contact.ContactSyncWriter
import io.github.lycheeappf.tmm.core.di.IoDispatcher
import io.github.lycheeappf.tmm.data.store.SettingsStore
import io.github.lycheeappf.tmm.domain.repository.MappingRepository
import io.github.lycheeappf.tmm.sms.provider.SmsContentProviderWriter
import io.github.lycheeappf.tmm.sms.provider.ThreadIdResolver
import io.github.lycheeappf.tmm.ui.screen.onboarding.PreFlightTester
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SettingsUiState(
    val ttlHours: Int = SettingsStore.DEFAULT_TTL_HOURS,
    val sendBudget: Int = SettingsStore.DEFAULT_SEND_BUDGET,
    val numberSchema: String = SettingsStore.SCHEMA_ITU_888,
    val displayMode: String = SettingsStore.DEFAULT_DISPLAY_MODE,
    val sendCountToday: Int = 0,
    val teslaContactCount: Int = 0,
    val teslaContactsHasPermission: Boolean = false,
    val teslaContactsHasRead: Boolean = false,
    val teslaContactsHasWrite: Boolean = false,
    val teslaContactsResetting: Boolean = false,
    val preflightStatus: String? = null,
    val preflightRunning: Boolean = false,
    val developerMode: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: SettingsStore,
    private val mappingRepository: MappingRepository,
    private val threadIdResolver: ThreadIdResolver,
    private val contactSyncWriter: ContactSyncWriter,
    private val smsWriter: SmsContentProviderWriter,
    private val preFlightTester: PreFlightTester,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init { refresh() }

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
            _uiState.update {
                it.copy(
                    ttlHours = store.mappingTtlHours(),
                    sendBudget = store.sendBudgetPerDay(),
                    numberSchema = store.numberSchema(),
                    displayMode = store.displayMode(),
                    sendCountToday = store.dailySendCount(),
                    preflightStatus = store.preflightResult(),
                    developerMode = store.isDeveloperMode()
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
     * Force-Reset der Tesla-Bridge-Contacts. Löscht alle existierenden
     * RawContacts, entfernt den Account, lässt ihn vom nächsten Backfill neu
     * anlegen. Erzwingt damit einen PBAP-Sync-Version-Counter-Increment, sodass
     * Tesla beim nächsten Connect die Contacts frisch holt.
     */
    fun resetTeslaContacts() {
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(teslaContactsResetting = true) }
            io.github.lycheeappf.tmm.core.util.coRunCatching {
                contactSyncWriter.deleteAllContacts()
                contactSyncWriter.removeAccount()
                contactSyncWriter.ensureAccountAndVisibility()
            }
            ContactBackfillWorker.enqueue(context)
            _uiState.update { it.copy(teslaContactsResetting = false) }
            refresh()
        }
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

    /**
     * Schema-Wechsel ist destruktiv für aktive Konversationen, weil alte
     * Mappings noch die alte `fakeAddress` (im alten Schema-Prefix) führen.
     * Wir invalidieren daher:
     *  - alle Mappings (DB),
     *  - den Thread-ID-Cache,
     *  - den Preflight-Status (alter Test validiert nur das alte Schema),
     *  - die Risk-Acknowledgement (User muss neu validieren).
     */
    fun setNumberSchema(value: String) {
        viewModelScope.launch(ioDispatcher) {
            val previous = store.numberSchema()
            if (previous == value) return@launch
            store.setNumberSchema(value)
            store.setPreflightResult("")
            store.setRiskAcknowledged(false)
            mappingRepository.deleteAll()
            threadIdResolver.clearAll()
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
     * Onboardings (z.B. nach Schema-Wechsel auf +888) wiederholbar ist.
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

    fun setDisplayMode(value: String) {
        viewModelScope.launch(ioDispatcher) {
            val previous = store.displayMode()
            if (previous == value) return@launch
            store.setDisplayMode(value)
            // Bei Wechsel zu NUMERIC: bestehende Mappings einmal nachsynchronisieren
            // damit Tesla via PBAP die Display-Namen findet. ContactBackfillWorker
            // ist idempotent — wenn Permission fehlt oder Contacts schon da, no-op.
            if (value == SettingsStore.DISPLAY_NUMERIC) {
                ContactBackfillWorker.enqueue(context)
                // Bestehende SMS-Rows tragen noch die alte ADDRESS (z.B. HYBRID
                // "Grok <+999...>"). Tesla zeigt diese gecachten Rows weiter, bis
                // sie verschwinden. Fake-Rows löschen (Grok-Messages sind ephemer)
                // + Thread-Cache leeren, damit der nächste Inject einen sauberen
                // NUMERIC-Thread bildet und Tesla den Namen via Contact-Sync auflöst.
                purgeFakeSmsRows()
            }
            refreshSettings()
        }
    }

    /**
     * Entfernt alle SMS-Rows unserer aktiven Fake-Adressen (egal welche ADDRESS-
     * Form sie tragen) und leert den Thread-Cache. Spiegelt das Verhalten von
     * [setNumberSchema], aber ohne die Mappings selbst zu löschen.
     */
    private suspend fun purgeFakeSmsRows() {
        io.github.lycheeappf.tmm.core.util.coRunCatching {
            mappingRepository.allMappings().forEach { mapping ->
                smsWriter.deleteByFakeNumber(mapping.fakeAddress)
            }
            threadIdResolver.clearAll()
        }
    }
}
