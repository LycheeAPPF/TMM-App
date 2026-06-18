package io.github.lycheeappf.tmm.ui.screen.home

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lycheeappf.tmm.R
import io.github.lycheeappf.tmm.channel.llm.AssistantTriggerCoordinator
import io.github.lycheeappf.tmm.channel.llm.AssistantTriggerSource
import io.github.lycheeappf.tmm.channel.llm.LlmStarter
import io.github.lycheeappf.tmm.core.di.IoDispatcher
import io.github.lycheeappf.tmm.core.locale.localizedString
import io.github.lycheeappf.tmm.core.security.ApiKeyStore
import io.github.lycheeappf.tmm.data.store.AssistantPreferencesStore
import io.github.lycheeappf.tmm.data.store.SettingsStore
import io.github.lycheeappf.tmm.platform.permission.PermissionGate
import io.github.lycheeappf.tmm.platform.role.DefaultSmsRoleManager
import io.github.lycheeappf.tmm.work.WorkScheduler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class HomeUiState(
    val isDefaultSmsApp: Boolean = false,
    val hasNotificationAccess: Boolean = false,
    val hasPostNotifications: Boolean = false,
    val preflightResult: String? = null,
    val sendBudget: Int = 100,
    val sendCountToday: Int = 0,
    val mappingTtlHours: Int = 24,
    // ---- Assistant-Block ----
    val assistantApiKeyIsSet: Boolean = false,
    val assistantConsentGiven: Boolean = false,
    val triggerInFlight: Boolean = false,
    val pendingConsentDialog: Boolean = false,
    val triggerFeedback: String? = null,
    val developerMode: Boolean = false
) {
    val isReady: Boolean
        get() = isDefaultSmsApp && hasNotificationAccess && hasPostNotifications

    val canTriggerAssistant: Boolean
        get() = isReady && assistantApiKeyIsSet
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val roleManager: DefaultSmsRoleManager,
    private val permissionGate: PermissionGate,
    private val settingsStore: SettingsStore,
    private val workScheduler: WorkScheduler,
    private val assistantPrefs: AssistantPreferencesStore,
    private val apiKeyStore: ApiKeyStore,
    private val coordinator: AssistantTriggerCoordinator,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val snapshot = withContext(ioDispatcher) {
                RefreshSnapshot(
                    isDefaultSmsApp = roleManager.isDefault(),
                    hasNotificationAccess = permissionGate.hasNotificationListenerAccess(),
                    hasPostNotifications = permissionGate.hasPostNotifications(),
                    preflightResult = settingsStore.preflightResult(),
                    sendBudget = settingsStore.sendBudgetPerDay(),
                    sendCountToday = settingsStore.dailySendCount(),
                    mappingTtlHours = settingsStore.mappingTtlHours(),
                    assistantApiKeyIsSet = apiKeyStore.isSet(),
                    assistantConsentGiven = assistantPrefs.isPrivacyConsentGiven(),
                    developerMode = settingsStore.isDeveloperMode()
                )
            }
            // Wichtig: transient UI-State (triggerInFlight, pendingConsentDialog,
            // triggerFeedback) NICHT wegwischen — sonst flackert der Consent-Dialog
            // bei Lifecycle-RESUME aus.
            _uiState.update { current ->
                current.copy(
                    isDefaultSmsApp = snapshot.isDefaultSmsApp,
                    hasNotificationAccess = snapshot.hasNotificationAccess,
                    hasPostNotifications = snapshot.hasPostNotifications,
                    preflightResult = snapshot.preflightResult,
                    sendBudget = snapshot.sendBudget,
                    sendCountToday = snapshot.sendCountToday,
                    mappingTtlHours = snapshot.mappingTtlHours,
                    assistantApiKeyIsSet = snapshot.assistantApiKeyIsSet,
                    assistantConsentGiven = snapshot.assistantConsentGiven,
                    developerMode = snapshot.developerMode
                )
            }
            if (_uiState.value.isReady) {
                withContext(ioDispatcher) { workScheduler.scheduleAll() }
            }
        }
    }

    private data class RefreshSnapshot(
        val isDefaultSmsApp: Boolean,
        val hasNotificationAccess: Boolean,
        val hasPostNotifications: Boolean,
        val preflightResult: String?,
        val sendBudget: Int,
        val sendCountToday: Int,
        val mappingTtlHours: Int,
        val assistantApiKeyIsSet: Boolean,
        val assistantConsentGiven: Boolean,
        val developerMode: Boolean
    )

    /**
     * UI ruft das beim Tap auf den Trigger-Button. Wenn der User noch nie
     * privacy-eingewilligt hat, öffnet der ViewModel den Consent-Dialog;
     * sonst läuft der Trigger direkt durch.
     */
    fun onAssistantButtonTapped() {
        if (!_uiState.value.assistantConsentGiven) {
            _uiState.update { it.copy(pendingConsentDialog = true) }
            return
        }
        runTrigger()
    }

    fun confirmConsent() {
        viewModelScope.launch(ioDispatcher) {
            assistantPrefs.setPrivacyConsentGiven(true)
            _uiState.update {
                it.copy(assistantConsentGiven = true, pendingConsentDialog = false)
            }
            runTrigger()
        }
    }

    fun dismissConsent() = _uiState.update { it.copy(pendingConsentDialog = false) }

    fun consumeTriggerFeedback() = _uiState.update { it.copy(triggerFeedback = null) }

    private fun runTrigger() {
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(triggerInFlight = true) }
            val result = coordinator.trigger(AssistantTriggerSource.MANUAL_BUTTON)
            val feedback = when (result) {
                is LlmStarter.StartResult.Success ->
                    context.localizedString(R.string.home_trigger_feedback_success)
                LlmStarter.StartResult.NoApiKey ->
                    context.localizedString(R.string.home_trigger_feedback_no_key)
                LlmStarter.StartResult.NotDefaultSmsApp ->
                    context.localizedString(R.string.home_trigger_feedback_not_default)
                LlmStarter.StartResult.BudgetExceeded ->
                    context.localizedString(R.string.home_trigger_feedback_budget)
                LlmStarter.StartResult.InjectionFailed ->
                    context.localizedString(R.string.home_trigger_feedback_injection_failed)
                LlmStarter.StartResult.ConsentMissing ->
                    context.localizedString(R.string.home_trigger_feedback_consent_missing)
            }
            _uiState.update { it.copy(triggerInFlight = false, triggerFeedback = feedback) }
            refresh()
        }
    }

    /**
     * Returnt den optimalen Intent zum Setzen der App als Default SMS:
     *  - RoleManager-Intent (direkter Dialog) wenn API 29+ und Rolle verfügbar
     *  - sonst ACTION_MANAGE_DEFAULT_APPS_SETTINGS als Fallback
     */
    fun defaultSmsIntent(): Intent =
        roleManager.createRequestIntent()
            ?: Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
}
