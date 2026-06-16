package io.github.lycheeappf.tmm.ui.screen.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lycheeappf.tmm.channel.llm.AssistantTriggerCoordinator
import io.github.lycheeappf.tmm.channel.llm.AssistantTriggerSource
import io.github.lycheeappf.tmm.channel.llm.LlmStarter
import io.github.lycheeappf.tmm.core.di.IoDispatcher
import io.github.lycheeappf.tmm.core.security.ApiKeyStore
import io.github.lycheeappf.tmm.data.store.AssistantPreferencesStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class AssistantUiState(
    val apiKeyIsSet: Boolean = false,
    val apiKeyDraft: String = "",
    val assistantName: String = AssistantPreferencesStore.DEFAULT_ASSISTANT_NAME,
    val driverName: String = AssistantPreferencesStore.DEFAULT_DRIVER_NAME,
    val systemPrompt: String = AssistantPreferencesStore.DEFAULT_SYSTEM_PROMPT,
    val welcomeMessage: String = AssistantPreferencesStore.DEFAULT_WELCOME,
    val model: String = AssistantPreferencesStore.DEFAULT_MODEL,
    val contextTtlSeconds: Int = AssistantPreferencesStore.DEFAULT_CONTEXT_TTL_SECONDS,
    val maxTokens: Int = AssistantPreferencesStore.DEFAULT_MAX_TOKENS,
    val temperature: Float = AssistantPreferencesStore.DEFAULT_TEMPERATURE,
    val rateLimitPerMin: Int = AssistantPreferencesStore.DEFAULT_RATE_PER_MIN,
    val rateLimitPerHour: Int = AssistantPreferencesStore.DEFAULT_RATE_PER_HOUR,
    val privacyConsent: Boolean = false,
    val saving: Boolean = false,
    val triggerInFlight: Boolean = false,
    val lastFeedback: String? = null
)

@HiltViewModel
class AssistantViewModel @Inject constructor(
    private val prefs: AssistantPreferencesStore,
    private val apiKeyStore: ApiKeyStore,
    private val coordinator: AssistantTriggerCoordinator,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch(ioDispatcher) {
            val snapshot = withContext(ioDispatcher) {
                AssistantUiState(
                    apiKeyIsSet = apiKeyStore.isSet(),
                    apiKeyDraft = "",
                    assistantName = prefs.assistantDisplayName(),
                    driverName = prefs.driverName(),
                    systemPrompt = prefs.systemPromptRaw(),
                    welcomeMessage = prefs.welcomeMessageRaw(),
                    model = prefs.model(),
                    contextTtlSeconds = prefs.contextTtlSeconds(),
                    maxTokens = prefs.maxTokens(),
                    temperature = prefs.temperature(),
                    rateLimitPerMin = prefs.maxRequestsPerMin(),
                    rateLimitPerHour = prefs.maxRequestsPerHour(),
                    privacyConsent = prefs.isPrivacyConsentGiven()
                )
            }
            _uiState.value = snapshot
        }
    }

    fun setApiKeyDraft(value: String) = _uiState.update { it.copy(apiKeyDraft = value) }

    fun saveApiKey() {
        val value = _uiState.value.apiKeyDraft.trim()
        if (value.isEmpty()) return
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(saving = true) }
            val (apiKeyIsSet, feedback) = try {
                apiKeyStore.write(value)
                true to "API-Key gespeichert."
            } catch (e: IllegalArgumentException) {
                // KeystoreApiKeyStore validiert auf Newline/Whitespace — bei Verletzung
                // bekommen wir IllegalArgumentException. Sauber an die UI returnen.
                false to "API-Key abgelehnt: ${e.message ?: "ungültig"}."
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                false to "Speichern fehlgeschlagen: ${e::class.simpleName}"
            }
            _uiState.update {
                it.copy(
                    saving = false,
                    apiKeyDraft = "",
                    apiKeyIsSet = it.apiKeyIsSet || apiKeyIsSet,
                    lastFeedback = feedback
                )
            }
        }
    }

    fun clearApiKey() {
        viewModelScope.launch(ioDispatcher) {
            apiKeyStore.clear()
            _uiState.update {
                it.copy(
                    apiKeyIsSet = false,
                    apiKeyDraft = "",
                    lastFeedback = "API-Key entfernt."
                )
            }
        }
    }

    fun setAssistantName(value: String) = update(value) { prefs.setAssistantDisplayName(it) }
    fun setDriverName(value: String) = update(value) { prefs.setDriverName(it) }
    fun setSystemPrompt(value: String) = update(value) { prefs.setSystemPrompt(it) }
    fun setWelcome(value: String) = update(value) { prefs.setWelcomeMessage(it) }
    fun setModel(value: String) = update(value) { prefs.setModel(it) }

    fun setContextTtl(value: Int) = updateInt(value, 30, 600) { prefs.setContextTtlSeconds(it) }
    fun setMaxTokens(value: Int) = updateInt(value, 64, 2048) { prefs.setMaxTokens(it) }
    fun setTemperature(value: Float) = updateFloat(value, 0f, 1.5f) { prefs.setTemperature(it) }
    fun setRateLimitPerMin(value: Int) = updateInt(value, 1, 60) { prefs.setMaxRequestsPerMin(it) }
    fun setRateLimitPerHour(value: Int) = updateInt(value, 1, 600) { prefs.setMaxRequestsPerHour(it) }

    fun setPrivacyConsent(granted: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            prefs.setPrivacyConsentGiven(granted)
            _uiState.update { it.copy(privacyConsent = granted) }
        }
    }

    fun triggerAssistant() {
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(triggerInFlight = true) }
            val result = coordinator.trigger(AssistantTriggerSource.MANUAL_BUTTON)
            val msg = when (result) {
                is LlmStarter.StartResult.Success ->
                    "AI-Konversation gestartet (${result.fakeAddress})."
                LlmStarter.StartResult.NoApiKey -> "Bitte zuerst API-Key eintragen."
                LlmStarter.StartResult.NotDefaultSmsApp ->
                    "App ist nicht Default-SMS — Setup im Home prüfen."
                LlmStarter.StartResult.BudgetExceeded -> "Tages-Send-Budget erreicht."
                LlmStarter.StartResult.InjectionFailed -> "Welcome-Inject fehlgeschlagen."
                LlmStarter.StartResult.ConsentMissing ->
                    "Bitte zuerst den Privacy-Hinweis bestätigen."
            }
            _uiState.update { it.copy(triggerInFlight = false, lastFeedback = msg) }
        }
    }

    fun consumeFeedback() = _uiState.update { it.copy(lastFeedback = null) }

    private fun update(value: String, block: suspend (String) -> Unit) {
        viewModelScope.launch(ioDispatcher) {
            block(value)
            refresh()
        }
    }

    private fun updateInt(value: Int, min: Int, max: Int, block: suspend (Int) -> Unit) {
        val clamped = value.coerceIn(min, max)
        viewModelScope.launch(ioDispatcher) {
            block(clamped)
            refresh()
        }
    }

    private fun updateFloat(value: Float, min: Float, max: Float, block: suspend (Float) -> Unit) {
        val clamped = value.coerceIn(min, max)
        viewModelScope.launch(ioDispatcher) {
            block(clamped)
            refresh()
        }
    }
}
