package io.github.lycheeappf.tmm.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Non-Secret-Einstellungen für den LLM-Assistenten. API-Keys liegen in
 * [io.github.lycheeappf.tmm.core.security.ApiKeyStore], hier nur die Modell-
 * und Verhaltens-Parameter.
 *
 * Separates DataStore ("mfs_assistant") damit die Tesla-Bridge-Settings
 * (`SettingsStore`) nicht mit Assistant-spezifischen Keys verschmieren.
 */
@Singleton
class AssistantPreferencesStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val store: DataStore<Preferences> = context.assistantDataStore

    // ---- Model & Prompt -----------------------------------------------------

    suspend fun model(): String =
        store.data.first()[KEY_MODEL] ?: DEFAULT_MODEL

    suspend fun setModel(value: String) {
        store.edit { it[KEY_MODEL] = value }
    }

    /**
     * System-Prompt mit eingesetztem Fahrernamen ({driver} aufgelöst) — der
     * RUNTIME-Pfad ([LlmTurnRunner]). Der Settings-Editor nutzt [systemPromptRaw],
     * damit dort das {driver}-Token sichtbar bleibt.
     */
    suspend fun systemPrompt(): String =
        resolveDriverTemplate(systemPromptRaw(), driverName())

    /**
     * Rohes Template inkl. {driver}-Token (für den Settings-Editor). Der Default
     * greift NUR, wenn der Key noch nie gesetzt wurde (null) — ein bewusst geleertes
     * Feld bleibt leer, statt im Editor auf den langen Default zurückzuspringen.
     */
    suspend fun systemPromptRaw(): String =
        store.data.first()[KEY_SYSTEM_PROMPT] ?: DEFAULT_SYSTEM_PROMPT

    suspend fun setSystemPrompt(value: String) {
        store.edit { it[KEY_SYSTEM_PROMPT] = value }
    }

    suspend fun assistantDisplayName(): String =
        store.data.first()[KEY_ASSISTANT_NAME] ?: DEFAULT_ASSISTANT_NAME

    suspend fun setAssistantDisplayName(value: String) {
        store.edit { it[KEY_ASSISTANT_NAME] = value }
    }

    /** Optionaler Fahrername; wird für {driver} in Prompt + Welcome eingesetzt. Leer = neutrale Anrede. */
    suspend fun driverName(): String =
        store.data.first()[KEY_DRIVER_NAME] ?: DEFAULT_DRIVER_NAME

    suspend fun setDriverName(value: String) {
        // Nicht beim Tippen trimmen — sonst lässt sich kein Leerzeichen setzen.
        // resolveDriverTemplate trimmt erst beim Einsetzen (Runtime).
        store.edit { it[KEY_DRIVER_NAME] = value }
    }

    /** Welcome mit eingesetztem Fahrernamen (RUNTIME). Editor: [welcomeMessageRaw]. */
    suspend fun welcomeMessage(): String =
        resolveDriverTemplate(welcomeMessageRaw(), driverName())

    /**
     * Rohes Welcome-Template inkl. {driver}-Token (für den Settings-Editor). Default
     * nur bei nie gesetztem Key (null); ein geleertes Feld bleibt leer.
     */
    suspend fun welcomeMessageRaw(): String =
        store.data.first()[KEY_WELCOME] ?: DEFAULT_WELCOME

    suspend fun setWelcomeMessage(value: String) {
        store.edit { it[KEY_WELCOME] = value }
    }

    // ---- Generation Parameters ---------------------------------------------

    suspend fun maxTokens(): Int = store.data.first()[KEY_MAX_TOKENS] ?: DEFAULT_MAX_TOKENS
    suspend fun setMaxTokens(value: Int) { store.edit { it[KEY_MAX_TOKENS] = value } }

    suspend fun temperature(): Float = store.data.first()[KEY_TEMPERATURE] ?: DEFAULT_TEMPERATURE
    suspend fun setTemperature(value: Float) { store.edit { it[KEY_TEMPERATURE] = value } }

    // ---- Context-TTL --------------------------------------------------------

    /**
     * Wie viele Sekunden Inaktivität reichen, um die In-Memory-History zu
     * verwerfen? Defensiv niedrig, damit der LLM nicht aus Versehen private
     * Vor-Sätze in spätere Konversationen weiterträgt.
     */
    suspend fun contextTtlSeconds(): Int =
        store.data.first()[KEY_CONTEXT_TTL] ?: DEFAULT_CONTEXT_TTL_SECONDS

    suspend fun setContextTtlSeconds(value: Int) {
        store.edit { it[KEY_CONTEXT_TTL] = value }
    }

    // ---- Rate-Limits --------------------------------------------------------

    suspend fun maxRequestsPerMin(): Int =
        store.data.first()[KEY_RATE_PER_MIN] ?: DEFAULT_RATE_PER_MIN

    suspend fun setMaxRequestsPerMin(value: Int) {
        store.edit { it[KEY_RATE_PER_MIN] = value }
    }

    suspend fun maxRequestsPerHour(): Int =
        store.data.first()[KEY_RATE_PER_HOUR] ?: DEFAULT_RATE_PER_HOUR

    suspend fun setMaxRequestsPerHour(value: Int) {
        store.edit { it[KEY_RATE_PER_HOUR] = value }
    }

    // ---- Mapping-TTL (für die LLM-Konversation in der Channel-DB) -----------

    suspend fun mappingTtlHours(): Int =
        store.data.first()[KEY_MAPPING_TTL_HOURS] ?: DEFAULT_MAPPING_TTL_HOURS

    suspend fun setMappingTtlHours(value: Int) {
        store.edit { it[KEY_MAPPING_TTL_HOURS] = value }
    }

    // ---- Consent ------------------------------------------------------------

    suspend fun isPrivacyConsentGiven(): Boolean =
        store.data.first()[KEY_PRIVACY_CONSENT] ?: false

    suspend fun setPrivacyConsentGiven(value: Boolean) {
        store.edit { it[KEY_PRIVACY_CONSENT] = value }
    }

    fun privacyConsentFlow(): Flow<Boolean> =
        store.data.map { it[KEY_PRIVACY_CONSENT] ?: false }

    // ---- Sprach-Aliasse (Grog / Grogg) -------------------------------------

    /**
     * Sind die phonetischen Sprach-Alias-Kontakte „Grog"/„Grogg" aktiv? Default
     * `true` (bisheriges Verhalten). Aus = nur „Grok" als Kontakt, um Teslas
     * „gro"-Auswahlmenü zu vermeiden. Wird in
     * [io.github.lycheeappf.tmm.channel.llm.AssistantContactProvisioner] gelesen,
     * sodass jeder reconcile() (Boot/Health/Backfill) den Schalter ehrt.
     */
    suspend fun isVoiceAliasesEnabled(): Boolean =
        store.data.first()[KEY_VOICE_ALIASES_ENABLED] ?: true

    suspend fun setVoiceAliasesEnabled(value: Boolean) {
        store.edit { it[KEY_VOICE_ALIASES_ENABLED] = value }
    }

    companion object {
        const val DEFAULT_MODEL = "grok-4.3"
        const val DEFAULT_ASSISTANT_NAME = "Grok"
        const val DEFAULT_DRIVER_NAME = ""
        const val DEFAULT_WELCOME =
            "Hey {driver}, hier ist Grok. Stell mir einfach deine Frage, ich antworte kurz und freihändig."
        const val DEFAULT_MAX_TOKENS = 512
        const val DEFAULT_TEMPERATURE = 0.7f
        const val DEFAULT_CONTEXT_TTL_SECONDS = 120
        const val DEFAULT_RATE_PER_MIN = 6
        const val DEFAULT_RATE_PER_HOUR = 30
        const val DEFAULT_MAPPING_TTL_HOURS = 24

        const val DEFAULT_SYSTEM_PROMPT =
            "Du bist Grok, der Sprachassistent im Tesla von {driver}. Du wirst freihändig " +
                "während der Fahrt benutzt: {driver} diktiert eine Frage per Stimme über die " +
                "Antwort-Funktion des Autos, und deine Antwort wird vom Auto laut vorgelesen. " +
                "Es gibt keinen Bildschirm und keine Hand für deine Antwort — alles, was du " +
                "schreibst, wird nur als gesprochenes Audio gehört, während {driver} auf die " +
                "Straße schaut.\n\n" +
                "Oberste Regel ist Sicherheit. Halte die kognitive Last gering und fass dich " +
                "kurz, damit die Aufmerksamkeit auf der Straße bleibt: in der Regel zwei bis " +
                "drei knappe Sätze, höchstens rund 800 Zeichen. Sag nie, jemand solle auf den " +
                "Bildschirm schauen oder etwas antippen.\n\n" +
                "Schreib darum reinen, natürlich klingenden Fließtext zum Vorlesen. Niemals " +
                "Markdown, Sternchen, Code, Aufzählungen, nummerierte Listen, Überschriften, " +
                "Tabellen, Emojis oder Links und Webadressen — vorgelesen klingt so etwas wie " +
                "Kauderwelsch, und antippen kann man im Fahren ohnehin nichts. Formuliere " +
                "Zahlen, Einheiten, Uhrzeiten und Abkürzungen so, dass eine Vorlesestimme sie " +
                "sauber spricht, also \"circa 20 Grad\" statt einer Tilde mit Gradzeichen und " +
                "\"15 Uhr 30\" statt einer Doppelpunkt-Schreibweise, und löse Abkürzungen wie " +
                "\"zum Beispiel\" auf, wenn sie sonst seltsam klingen.\n\n" +
                "Sprich Deutsch und wechsle die Sprache nur, wenn {driver} aktiv in einer " +
                "anderen spricht. Jede Frage steht für sich, denn dein Gedächtnis reicht nur " +
                "über die letzten Sekunden des Gesprächs — antworte in sich verständlich und " +
                "verlass dich nicht auf weiter zurückliegenden Kontext.\n\n" +
                "In dieser Version steuerst du nichts im Auto und hast keine Live-Werkzeuge: " +
                "du kannst weder Klima, Navigation, Medien noch Apps bedienen und nichts in " +
                "Echtzeit nachschlagen. Wirst du danach gefragt, sag das kurz und nenne, wenn " +
                "es hilft, in einem Satz den Weg über die Bedienelemente des Teslas, ohne zu " +
                "belehren.\n\n" +
                "Weise normale Fragen nicht ab, sei ehrlich nützlich und natürlich. Etwas " +
                "trockener Grok-Witz ist willkommen, aber kurz und der Fahrt angemessen, nie " +
                "auf Kosten von Klarheit oder Tempo. Wenn du etwas nicht sicher weißt, sag " +
                "das knapp, statt zu raten."

        /**
         * Setzt das {driver}-Token ein. Bei leerem Namen werden grammatisch passende
         * neutrale Formen verwendet (Genitiv "des Fahrers", sonst "der Fahrer"),
         * damit Prompt und Welcome auch ohne Namen sauber klingen. Reihenfolge der
         * Ersetzungen ist wichtig: spezifische Phrasen vor dem generischen Token.
         */
        fun resolveDriverTemplate(template: String, driverName: String): String =
            if (driverName.isNotBlank()) {
                template.replace("{driver}", driverName.trim())
            } else {
                template
                    .replace("Hey {driver},", "Hey,")
                    .replace("von {driver}", "des Fahrers")
                    .replace("{driver}", "der Fahrer")
            }

        private val KEY_MODEL = stringPreferencesKey("model")
        private val KEY_SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        private val KEY_ASSISTANT_NAME = stringPreferencesKey("assistant_name")
        private val KEY_WELCOME = stringPreferencesKey("welcome")
        private val KEY_DRIVER_NAME = stringPreferencesKey("driver_name")
        private val KEY_MAX_TOKENS = intPreferencesKey("max_tokens")
        private val KEY_TEMPERATURE = floatPreferencesKey("temperature")
        private val KEY_CONTEXT_TTL = intPreferencesKey("context_ttl_seconds")
        private val KEY_RATE_PER_MIN = intPreferencesKey("rate_per_min")
        private val KEY_RATE_PER_HOUR = intPreferencesKey("rate_per_hour")
        private val KEY_MAPPING_TTL_HOURS = intPreferencesKey("mapping_ttl_hours")
        private val KEY_PRIVACY_CONSENT = booleanPreferencesKey("privacy_consent")
        private val KEY_VOICE_ALIASES_ENABLED = booleanPreferencesKey("voice_aliases_enabled")
    }
}

private val Context.assistantDataStore: DataStore<Preferences> by preferencesDataStore("mfs_assistant")
