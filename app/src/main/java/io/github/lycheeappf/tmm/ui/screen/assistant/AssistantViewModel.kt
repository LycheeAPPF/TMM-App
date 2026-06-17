package io.github.lycheeappf.tmm.ui.screen.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lycheeappf.tmm.channel.llm.AssistantContactProvisioner
import io.github.lycheeappf.tmm.channel.llm.AssistantTriggerCoordinator
import io.github.lycheeappf.tmm.channel.llm.AssistantTriggerSource
import io.github.lycheeappf.tmm.channel.llm.LlmStarter
import io.github.lycheeappf.tmm.contact.TeslaContactResync
import io.github.lycheeappf.tmm.core.di.IoDispatcher
import io.github.lycheeappf.tmm.core.security.ApiKeyStore
import io.github.lycheeappf.tmm.core.util.coRunCatching
import io.github.lycheeappf.tmm.data.store.AssistantPreferencesStore
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
    val saving: Boolean = false,
    val voiceAliasEnabled: Boolean = true,
    val voiceAliasName: String = AssistantPreferencesStore.DEFAULT_VOICE_ALIAS_NAME,
    val voiceAliasApplying: Boolean = false,
    val triggerInFlight: Boolean = false,
    val lastFeedback: String? = null
)

@HiltViewModel
class AssistantViewModel @Inject constructor(
    private val prefs: AssistantPreferencesStore,
    private val apiKeyStore: ApiKeyStore,
    private val coordinator: AssistantTriggerCoordinator,
    private val contactProvisioner: AssistantContactProvisioner,
    private val teslaContactResync: TeslaContactResync,
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
                    voiceAliasEnabled = prefs.voiceAliasEnabled(),
                    voiceAliasName = prefs.voiceAliasName()
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
                true to "API-Key gespeichert."
            } catch (e: IllegalArgumentException) {
                // KeystoreApiKeyStore validiert auf Newline/Whitespace — bei Verletzung
                // bekommen wir IllegalArgumentException. Sauber an die UI returnen.
                false to "API-Key abgelehnt: ${e.message ?: "ungültig"}."
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                false to "Speichern fehlgeschlagen: ${e::class.simpleName}"
            }
            // Key gesetzt → statischen Grok-Auto-Kontakt anlegen (sofern Consent da).
            if (apiKeyIsSet) coRunCatching { contactProvisioner.reconcile() }
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
            // Key weg → Grok-Auto-Kontakt entfernen.
            coRunCatching { contactProvisioner.reconcile() }
            _uiState.update {
                it.copy(
                    apiKeyIsSet = false,
                    apiKeyDraft = "",
                    lastFeedback = "API-Key entfernt."
                )
            }
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
                    lastFeedback = "Gespeichert — Tesla-Sync läuft. Ggf. Bluetooth neu verbinden."
                )
            }
        }
    }

    fun setDriverName(value: String) =
        edit("driver_name", { it.copy(driverName = value) }) { prefs.setDriverName(value) }

    fun setSystemPrompt(value: String) =
        edit("system_prompt", { it.copy(systemPrompt = value) }) { prefs.setSystemPrompt(value) }

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
        val PRESET_NAMES = listOf("Walter Grok", "xAI Grok")
    }
}
