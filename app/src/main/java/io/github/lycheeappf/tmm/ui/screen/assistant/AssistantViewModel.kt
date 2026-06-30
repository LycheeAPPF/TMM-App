package io.github.lycheeappf.tmm.ui.screen.assistant

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lycheeappf.tmm.R
import io.github.lycheeappf.tmm.channel.llm.AssistantContactProvisioner
import io.github.lycheeappf.tmm.channel.llm.AssistantTriggerCoordinator
import io.github.lycheeappf.tmm.channel.llm.AssistantTriggerSource
import io.github.lycheeappf.tmm.channel.llm.GrokKeyTester
import io.github.lycheeappf.tmm.channel.llm.KeyTestOutcome
import io.github.lycheeappf.tmm.channel.llm.LlmStarter
import io.github.lycheeappf.tmm.contact.TeslaContactResync
import io.github.lycheeappf.tmm.core.di.IoDispatcher
import io.github.lycheeappf.tmm.core.locale.localizedString
import io.github.lycheeappf.tmm.core.security.ApiKeyStore
import io.github.lycheeappf.tmm.core.util.coRunCatching
import io.github.lycheeappf.tmm.data.store.AssistantPreferencesStore
import io.github.lycheeappf.tmm.platform.permission.PermissionGate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val webSearchEnabled: Boolean = false,
    val xSearchEnabled: Boolean = false,
    val saving: Boolean = false,
    val voiceAliasEnabled: Boolean = true,
    val voiceAliasName: String = AssistantPreferencesStore.DEFAULT_VOICE_ALIAS_NAME,
    val voiceAliasApplying: Boolean = false,
    val triggerInFlight: Boolean = false,
    val keyTestRunning: Boolean = false,
    val keyTestResult: KeyTestOutcome? = null,
    val hasLocationPermission: Boolean = false,
    val lastFeedback: String? = null,
    val isSystemPromptCustomized: Boolean = false
)

@HiltViewModel
class AssistantViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AssistantPreferencesStore,
    private val apiKeyStore: ApiKeyStore,
    private val keyTester: GrokKeyTester,
    private val coordinator: AssistantTriggerCoordinator,
    private val contactProvisioner: AssistantContactProvisioner,
    private val teslaContactResync: TeslaContactResync,
    private val permissionGate: PermissionGate,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    // Pro Editor-Feld ein laufender (debounced) Persist-Job. Solange einer aktiv
    // ist, gilt das Feld als "in Bearbeitung" und wird von refresh() nicht von der
    // Platte überschrieben.
    private val persistJobs = mutableMapOf<String, Job>()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch(ioDispatcher) {
            val snapshot = withContext(ioDispatcher) {
                AssistantUiState(
                    apiKeyIsSet = apiKeyStore.isSet(),
                    apiKeyDraft = "",
                    driverName = prefs.driverName(),
                    systemPrompt = prefs.systemPromptRaw(),
                    welcomeMessage = prefs.welcomeMessageRaw(),
                    model = prefs.model(),
                    contextTtlSeconds = prefs.contextTtlSeconds(),
                    maxTokens = prefs.maxTokens(),
                    temperature = prefs.temperature(),
                    rateLimitPerMin = prefs.maxRequestsPerMin(),
                    rateLimitPerHour = prefs.maxRequestsPerHour(),
                    privacyConsent = prefs.isPrivacyConsentGiven(),
                    webSearchEnabled = prefs.webSearchEnabled(),
                    xSearchEnabled = prefs.xSearchEnabled(),
                    voiceAliasEnabled = prefs.voiceAliasEnabled(),
                    voiceAliasName = prefs.voiceAliasName(),
                    hasLocationPermission = permissionGate.hasLocationAccess(),
                    isSystemPromptCustomized = prefs.isSystemPromptCustomized()
                )
            }
            // Tippt der User gerade (ein Persist-Job läuft noch), die editierbaren
            // Felder NICHT von der Platte überschreiben — sonst verwirft ein Resume
            // die noch nicht gespeicherten Zeichen. Extern bestimmte Felder
            // (API-Key gesetzt?, Consent) trotzdem übernehmen.
            val persisting = persistJobs.values.any { it.isActive }
            _uiState.update { cur ->
                if (persisting) {
                    snapshot.copy(
                        driverName = cur.driverName,
                        systemPrompt = cur.systemPrompt,
                        welcomeMessage = cur.welcomeMessage,
                        model = cur.model,
                        contextTtlSeconds = cur.contextTtlSeconds,
                        maxTokens = cur.maxTokens,
                        temperature = cur.temperature,
                        rateLimitPerMin = cur.rateLimitPerMin,
                        rateLimitPerHour = cur.rateLimitPerHour
                    )
                } else {
                    snapshot
                }
            }
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
                true to context.localizedString(R.string.assistant_feedback_key_saved)
            } catch (e: IllegalArgumentException) {
                // KeystoreApiKeyStore validiert auf Newline/Whitespace — bei Verletzung
                // bekommen wir IllegalArgumentException. Sauber an die UI returnen.
                val reason = e.message
                    ?: context.localizedString(R.string.assistant_feedback_key_rejected_reason_invalid)
                false to context.localizedString(R.string.assistant_feedback_key_rejected, reason)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                false to context.localizedString(
                    R.string.assistant_feedback_save_failed,
                    e::class.simpleName ?: ""
                )
            }
            // Key gesetzt → statischen Grok-Auto-Kontakt anlegen (sofern Consent da).
            if (apiKeyIsSet) coRunCatching { contactProvisioner.reconcile() }
            _uiState.update {
                it.copy(
                    saving = false,
                    apiKeyDraft = "",
                    apiKeyIsSet = it.apiKeyIsSet || apiKeyIsSet,
                    // Key geändert → altes Test-Ergebnis ist stale.
                    keyTestResult = null,
                    lastFeedback = feedback
                )
            }
        }
    }

    fun clearApiKey() {
        viewModelScope.launch(ioDispatcher) {
            apiKeyStore.clear()
            // Key weg → Grok-Auto-Kontakt entfernen.
            coRunCatching { contactProvisioner.reconcile() }
            _uiState.update {
                it.copy(
                    apiKeyIsSet = false,
                    apiKeyDraft = "",
                    keyTestResult = null,
                    lastFeedback = context.localizedString(R.string.assistant_feedback_key_removed)
                )
            }
        }
    }

    /**
     * Prüft den gespeicherten xAI-Key rein lokal (kein Tesla/Bluetooth) via
     * [GrokKeyTester]: minimaler „ping" gegen die xAI-API, Ergebnis als
     * [KeyTestOutcome] in den State. `coRunCatching` re-throwt `CancellationException`;
     * jeder andere unerwartete Fehler wird zu [KeyTestOutcome.UNKNOWN]. Save/Remove
     * sind in der UI gesperrt, solange dieser Test läuft (Race-Guard) — der Key kann
     * sich also während eines laufenden Tests nicht ändern.
     */
    fun testApiKey() {
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(keyTestRunning = true, keyTestResult = null) }
            val outcome = coRunCatching { keyTester.run() }
                .getOrDefault(KeyTestOutcome.UNKNOWN)
            _uiState.update { it.copy(keyTestRunning = false, keyTestResult = outcome) }
        }
    }

    /**
     * Schaltet den zusätzlichen Sprach-Ansprech-Kontakt (+88810000001) ein/aus bzw.
     * setzt seinen Namen SOFORT (kein Debounce — Preset-Tap/„Aus"/„Anwenden" sind
     * diskrete Aktionen) und erzwingt einen Tesla-Kontakt-Sync, damit das Auto den
     * Kontakt beim nächsten PBAP-Pull neu zieht bzw. entfernt. Erst persistieren,
     * DANN resyncen: der Backfill ruft `reconcile()` → `ensure()`, das die Prefs
     * liest. Der ANTWORT-Name bleibt unberührt „Grok".
     */
    fun applyVoiceAlias(enabled: Boolean, name: String) {
        val trimmed = name.trim()
        if (enabled && trimmed.isEmpty()) return
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(voiceAliasApplying = true) }
            prefs.setVoiceAliasEnabled(enabled)
            if (enabled) prefs.setVoiceAliasName(trimmed)
            teslaContactResync.force()
            _uiState.update {
                it.copy(
                    voiceAliasEnabled = enabled,
                    voiceAliasName = if (enabled) trimmed else it.voiceAliasName,
                    voiceAliasApplying = false,
                    lastFeedback = context.localizedString(R.string.assistant_feedback_voicealias_saved)
                )
            }
        }
    }

    fun setDriverName(value: String) =
        edit("driver_name", { it.copy(driverName = value) }) { prefs.setDriverName(value) }

    fun setSystemPrompt(value: String) =
        edit("system_prompt", { it.copy(systemPrompt = value, isSystemPromptCustomized = true) }) {
            prefs.setSystemPrompt(value)
        }

    fun resetSystemPromptToDefault() {
        viewModelScope.launch(ioDispatcher) {
            prefs.resetSystemPromptToDefault()
            val defaultPrompt = prefs.systemPromptRaw()
            _uiState.update { it.copy(systemPrompt = defaultPrompt, isSystemPromptCustomized = false) }
        }
    }

    fun setWelcome(value: String) =
        edit("welcome", { it.copy(welcomeMessage = value) }) { prefs.setWelcomeMessage(value) }

    fun setModel(value: String) =
        edit("model", { it.copy(model = value) }) { prefs.setModel(value) }

    fun setContextTtl(value: Int) {
        val clamped = value.coerceIn(30, 600)
        edit("context_ttl", { it.copy(contextTtlSeconds = clamped) }) { prefs.setContextTtlSeconds(clamped) }
    }

    fun setMaxTokens(value: Int) {
        val clamped = value.coerceIn(64, 2048)
        edit("max_tokens", { it.copy(maxTokens = clamped) }) { prefs.setMaxTokens(clamped) }
    }

    fun setTemperature(value: Float) {
        val clamped = value.coerceIn(0f, 1.5f)
        edit("temperature", { it.copy(temperature = clamped) }) { prefs.setTemperature(clamped) }
    }

    fun setRateLimitPerMin(value: Int) {
        val clamped = value.coerceIn(1, 60)
        edit("rate_per_min", { it.copy(rateLimitPerMin = clamped) }) { prefs.setMaxRequestsPerMin(clamped) }
    }

    fun setRateLimitPerHour(value: Int) {
        val clamped = value.coerceIn(1, 600)
        edit("rate_per_hour", { it.copy(rateLimitPerHour = clamped) }) { prefs.setMaxRequestsPerHour(clamped) }
    }

    fun setPrivacyConsent(granted: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            prefs.setPrivacyConsentGiven(granted)
            // Consent-Wechsel → Grok-Auto-Kontakt anlegen (an) bzw. entfernen (aus).
            coRunCatching { contactProvisioner.reconcile() }
            _uiState.update { it.copy(privacyConsent = granted) }
        }
    }

    fun setWebSearchEnabled(enabled: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            prefs.setWebSearchEnabled(enabled)
            _uiState.update { it.copy(webSearchEnabled = enabled) }
        }
    }

    fun setXSearchEnabled(enabled: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            prefs.setXSearchEnabled(enabled)
            _uiState.update { it.copy(xSearchEnabled = enabled) }
        }
    }

    fun triggerAssistant() {
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(triggerInFlight = true) }
            val result = coordinator.trigger(AssistantTriggerSource.MANUAL_BUTTON)
            val msg = when (result) {
                is LlmStarter.StartResult.Success ->
                    context.localizedString(
                        R.string.assistant_feedback_start_success,
                        result.fakeAddress
                    )
                LlmStarter.StartResult.NoApiKey ->
                    context.localizedString(R.string.assistant_feedback_start_no_key)
                LlmStarter.StartResult.NotDefaultSmsApp ->
                    context.localizedString(R.string.assistant_feedback_start_not_default_sms)
                LlmStarter.StartResult.BudgetExceeded ->
                    context.localizedString(R.string.assistant_feedback_start_budget)
                LlmStarter.StartResult.InjectionFailed ->
                    context.localizedString(R.string.assistant_feedback_start_injection_failed)
                LlmStarter.StartResult.ConsentMissing ->
                    context.localizedString(R.string.assistant_feedback_start_consent_missing)
            }
            _uiState.update { it.copy(triggerInFlight = false, lastFeedback = msg) }
        }
    }

    fun consumeFeedback() = _uiState.update { it.copy(lastFeedback = null) }

    /**
     * Aktualisiert ein Editor-Feld SOFORT im UI-State, damit das controlled
     * OutlinedTextField das getippte Zeichen ohne Verzögerung anzeigt, und
     * persistiert den Wert erst debounced. Es wird bewusst NICHT pro Tastendruck
     * der komplette State neu von der Platte gelesen (das frühere refresh()) — genau
     * das ließ Zeichen "wegbuggen" und Felder leer zurückspringen.
     */
    private fun edit(
        key: String,
        applyToState: (AssistantUiState) -> AssistantUiState,
        persist: suspend () -> Unit
    ) {
        _uiState.update(applyToState)
        persistJobs[key]?.cancel()
        persistJobs[key] = viewModelScope.launch(ioDispatcher) {
            delay(PERSIST_DEBOUNCE_MS)
            persist()
        }
    }

    companion object {
        private const val PERSIST_DEBOUNCE_MS = 350L

        /**
         * Vorgefertigte Namen für den Sprach-Ansprech-Kontakt. Zweiteilige Namen
         * (Vor- + Nachname) werden von Teslas Sprachsteuerung zuverlässiger
         * adressiert; alles andere geht über das freie Custom-Feld. „Grok" ist hier
         * bewusst NICHT dabei — das ist der feste Antwort-Name.
         */
        val PRESET_NAMES = listOf("xAI Grok", "Elon Musk")
    }
}
