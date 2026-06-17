package io.github.lycheeappf.tmm.ui.screen.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lycheeappf.tmm.contact.ContactBackfillWorker
import io.github.lycheeappf.tmm.contact.ContactSyncWriter
import io.github.lycheeappf.tmm.core.di.IoDispatcher
import io.github.lycheeappf.tmm.data.store.AssistantPreferencesStore
import io.github.lycheeappf.tmm.data.store.SettingsStore
import io.github.lycheeappf.tmm.domain.channel.AssistantIdentity
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
    val sendCountToday: Int = 0,
    val teslaContactCount: Int = 0,
    val teslaContactsHasPermission: Boolean = false,
    val teslaContactsHasRead: Boolean = false,
    val teslaContactsHasWrite: Boolean = false,
    val teslaContactsResetting: Boolean = false,
    val preflightStatus: String? = null,
    val preflightRunning: Boolean = false,
    val developerMode: Boolean = false,
    val voiceAliases: List<AliasToggle> =
        AssistantIdentity.ALIASES.map { AliasToggle(it.mappingId, it.displayName, true) }
)

/** Einzeln schaltbarer Sprach-Alias (Grog/Grogg) für die Settings-UI. */
data class AliasToggle(
    val mappingId: Long,
    val displayName: String,
    val enabled: Boolean
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: SettingsStore,
    private val assistantPrefs: AssistantPreferencesStore,
    private val contactSyncWriter: ContactSyncWriter,
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
                    sendCountToday = store.dailySendCount(),
                    preflightStatus = store.preflightResult(),
                    developerMode = store.isDeveloperMode(),
                    voiceAliases = AssistantIdentity.ALIASES.map {
                        AliasToggle(it.mappingId, it.displayName, assistantPrefs.isVoiceAliasEnabled(it.mappingId))
                    }
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
        viewModelScope.launch(ioDispatcher) { forceTeslaResync() }
    }

    /**
     * Schaltet EINEN phonetischen Sprach-Alias (Grog = 1, Grogg = 2) einzeln ein
     * bzw. aus und erzwingt einen Tesla-Kontakt-Sync, damit das Auto den Wechsel
     * beim nächsten PBAP-Pull übernimmt. Der Backfill ruft `reconcile()` →
     * `ensure()`, das je Alias das Pref liest und den Kontakt (neu) anlegt oder
     * weglässt.
     */
    fun setVoiceAliasEnabled(mappingId: Long, value: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            assistantPrefs.setVoiceAliasEnabled(mappingId, value)
            _uiState.update { s ->
                s.copy(voiceAliases = s.voiceAliases.map {
                    if (it.mappingId == mappingId) it.copy(enabled = value) else it
                })
            }
            forceTeslaResync()
        }
    }

    /**
     * Gemeinsamer Force-Resync-Pfad: alle Bridge-Contacts löschen, Account
     * entfernen (bumpt den `account_changes`-Counter → Tesla zieht neu) und neu
     * provisionieren lassen. Genutzt vom Reset-Button und vom Alias-Schalter.
     * Muss aus einem `ioDispatcher`-Scope gerufen werden.
     */
    private suspend fun forceTeslaResync() {
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

}
