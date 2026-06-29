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
import io.github.lycheeappf.tmm.core.locale.LocaleProvider
import io.github.lycheeappf.tmm.platform.location.LocationFix
import kotlin.math.abs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Locale
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
    @ApplicationContext context: Context,
    private val localeProvider: LocaleProvider
) {
    private val store: DataStore<Preferences> = context.assistantDataStore

    /** Aktive App-Locale — entscheidet, welcher Default-Prompt/-Welcome gewählt wird. */
    private fun currentLocale(): Locale = localeProvider.current()

    private fun isEnglish(): Boolean =
        currentLocale().language == Locale.ENGLISH.language

    private fun defaultSystemPrompt(): String =
        if (isEnglish()) DEFAULT_SYSTEM_PROMPT_EN else DEFAULT_SYSTEM_PROMPT

    private fun defaultWelcome(): String =
        if (isEnglish()) DEFAULT_WELCOME_EN else DEFAULT_WELCOME

    /** Gilt ein gespeicherter Wert als unveränderter Seed-Default (DE oder EN)? */
    private fun isSeedDefault(value: String, deDefault: String, enDefault: String): Boolean =
        value == deDefault || value == enDefault

    /**
     * Lokalisierte Live-Suche-Klausel, die [systemPrompt] an den Prompt hängt. Kein
     * UI-String (geht an xAI), daher Konstanten in diesem File. Ohne Suche bleibt der
     * bisherige „kann nichts in Echtzeit nachschlagen"-Hinweis; mit Suche die Erlaubnis,
     * live nachzuschlagen — stets mit der Auflage, keine URLs/Marker/Markdown vorzulesen.
     */
    private fun searchCapabilityClause(webSearch: Boolean, xSearch: Boolean): String {
        if (!webSearch && !xSearch) {
            return if (isEnglish()) NO_SEARCH_CLAUSE_EN else NO_SEARCH_CLAUSE
        }
        return if (isEnglish()) {
            val source = when {
                webSearch && xSearch -> "the web and on X"
                webSearch -> "the web"
                else -> "X"
            }
            "You can look up current information live on $source — use it when fresh facts help, " +
                "and weave the facts into plain spoken prose, without ever reading out URLs, " +
                "source markers or markdown."
        } else {
            val source = when {
                webSearch && xSearch -> "im Web und auf X"
                webSearch -> "im Web"
                else -> "auf X"
            }
            "Du kannst aktuelle Informationen live $source nachschlagen — nutze das, wenn frische " +
                "Fakten helfen, und webe die Fakten in normalen gesprochenen Fließtext ein, ohne " +
                "je URLs, Quellenmarker oder Markdown vorzulesen."
        }
    }

    // Coordinates use Locale.US (dot decimal separator) — this goes to the AI, not the UI.
    // Cardinal directions: N/S are identical in DE and EN; longitude uses "O" (Ost) in DE, "E" in EN.
    private fun locationClause(fix: LocationFix): String {
        val latAbs = String.format(java.util.Locale.US, "%.4f", abs(fix.latitude))
        val lonAbs = String.format(java.util.Locale.US, "%.4f", abs(fix.longitude))
        val latDir = if (fix.latitude >= 0) "N" else "S"
        val accuracyInMeters = fix.accuracyInMeters.toInt()
        return if (isEnglish()) {
            val lonDir = if (fix.longitude >= 0) "E" else "W"
            "The user's current GPS position is approximately $latAbs° $latDir, " +
                "$lonAbs° $lonDir (accuracy: about $accuracyInMeters m). " +
                "Use this when answering location-based questions."
        } else {
            val lonDir = if (fix.longitude >= 0) "O" else "W"
            "Die aktuelle GPS-Position des Nutzers ist ungefähr $latAbs° $latDir, " +
                "$lonAbs° $lonDir (Genauigkeit: ca. $accuracyInMeters m). " +
                "Nutze das bei standortbezogenen Fragen."
        }
    }

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
     *
     * [webSearch]/[xSearch] entscheiden, welche Live-Suche-Klausel angehängt wird:
     * ohne Suche der „kann nichts in Echtzeit nachschlagen"-Hinweis, mit Suche die
     * Erlaubnis, live im Web/auf X nachzuschlagen (ohne URLs/Marker vorzulesen). Der
     * Aufrufer liest die Flags EINMAL und reicht sie hier UND in den Request, damit
     * Prompt und gesendete Tools nicht auseinanderlaufen.
     */
    suspend fun systemPrompt(
        webSearch: Boolean,
        xSearch: Boolean,
        location: LocationFix? = null
    ): String {
        val base = resolveDriverTemplate(systemPromptRaw(), driverName(), currentLocale())
        // Ein bewusst geleerter Prompt bleibt leer (kein „\n\n"-Vorspann, keine Klausel).
        if (base.isBlank()) return base
        val sb = StringBuilder(base)
        sb.append("\n\n").append(searchCapabilityClause(webSearch, xSearch))
        if (location != null) sb.append("\n\n").append(locationClause(location))
        return sb.toString()
    }

    /**
     * Rohes Template inkl. {driver}-Token (für den Settings-Editor). Der lokalisierte
     * Default greift, wenn der Key noch nie gesetzt wurde (null) ODER wenn ein
     * unverändert gespeicherter Default (DE oder EN) vorliegt — so flippt ein nie
     * angepasster Prompt beim Sprachwechsel mit. Ein bewusst geleertes oder echt
     * angepasstes Feld bleibt unangetastet.
     */
    suspend fun systemPromptRaw(): String {
        val stored = store.data.first()[KEY_SYSTEM_PROMPT]
        return if (stored == null ||
            isSeedDefault(stored, DEFAULT_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT_EN) ||
            isSeedDefault(stored, LEGACY2_DEFAULT_SYSTEM_PROMPT, LEGACY2_DEFAULT_SYSTEM_PROMPT_EN) ||
            isSeedDefault(stored, LEGACY_DEFAULT_SYSTEM_PROMPT, LEGACY_DEFAULT_SYSTEM_PROMPT_EN)
        ) defaultSystemPrompt() else stored
    }

    suspend fun setSystemPrompt(value: String) {
        store.edit { it[KEY_SYSTEM_PROMPT] = value }
    }

    /** Antwort-Name des kanonischen Grok-Kontakts. Fest „Grok" (nicht über die UI editierbar). */
    suspend fun assistantDisplayName(): String =
        store.data.first()[KEY_ASSISTANT_NAME] ?: DEFAULT_ASSISTANT_NAME

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
        resolveDriverTemplate(welcomeMessageRaw(), driverName(), currentLocale())

    /**
     * Rohes Welcome-Template inkl. {driver}-Token (für den Settings-Editor). Wie
     * [systemPromptRaw]: lokalisierter Default bei null oder unverändertem Default,
     * sonst der gespeicherte Wert (auch leer).
     */
    suspend fun welcomeMessageRaw(): String {
        val stored = store.data.first()[KEY_WELCOME]
        return if (stored == null || isSeedDefault(stored, DEFAULT_WELCOME, DEFAULT_WELCOME_EN))
            defaultWelcome() else stored
    }

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

    // ---- Internetzugriff (server-seitige Suche) ----------------------------

    /**
     * Darf Grok live im Web suchen (xAIs server-seitiges `web_search`-Tool)? Opt-in,
     * Default `false`. Eine Suche schickt die diktierte Frage an xAIs Such-Subsystem
     * (gleiche xAI-Vertrauensgrenze wie der normale Turn) und kostet zusätzlich.
     */
    suspend fun webSearchEnabled(): Boolean =
        store.data.first()[KEY_WEB_SEARCH_ENABLED] ?: false

    suspend fun setWebSearchEnabled(value: Boolean) {
        store.edit { it[KEY_WEB_SEARCH_ENABLED] = value }
    }

    fun webSearchEnabledFlow(): Flow<Boolean> =
        store.data.map { it[KEY_WEB_SEARCH_ENABLED] ?: false }

    /** Darf Grok live auf X/Twitter suchen (`x_search`-Tool)? Opt-in, Default `false`. */
    suspend fun xSearchEnabled(): Boolean =
        store.data.first()[KEY_X_SEARCH_ENABLED] ?: false

    suspend fun setXSearchEnabled(value: Boolean) {
        store.edit { it[KEY_X_SEARCH_ENABLED] = value }
    }

    fun xSearchEnabledFlow(): Flow<Boolean> =
        store.data.map { it[KEY_X_SEARCH_ENABLED] ?: false }

    // ---- Sprach-Ansprech-Kontakt (zusätzlicher Alias) ----------------------

    /**
     * Ist der zusätzliche Sprach-Ansprech-Kontakt ([AssistantIdentity.VOICE_ALIAS_FAKE_ADDRESS],
     * +88810000001) aktiv? Default `true`. Trägt [voiceAliasName] und lenkt Diktate auf die
     * kanonische Grok-Session um; „Aus" entfernt den Zusatzkontakt.
     */
    suspend fun voiceAliasEnabled(): Boolean =
        store.data.first()[KEY_VOICE_ALIAS_ENABLED] ?: true

    suspend fun setVoiceAliasEnabled(value: Boolean) {
        store.edit { it[KEY_VOICE_ALIAS_ENABLED] = value }
    }

    /** Anzeigename des Sprach-Ansprech-Kontakts (Default „xAI Grok" — neutral). */
    suspend fun voiceAliasName(): String =
        store.data.first()[KEY_VOICE_ALIAS_NAME] ?: DEFAULT_VOICE_ALIAS_NAME

    suspend fun setVoiceAliasName(value: String) {
        store.edit { it[KEY_VOICE_ALIAS_NAME] = value }
    }

    companion object {
        const val DEFAULT_MODEL = "grok-4.3"
        const val DEFAULT_ASSISTANT_NAME = "Grok"
        const val DEFAULT_VOICE_ALIAS_NAME = "xAI Grok"
        const val DEFAULT_DRIVER_NAME = ""
        const val DEFAULT_WELCOME =
            "Hey {driver}, hier ist Grok. Stell mir einfach deine Frage, ich antworte kurz und freihändig."
        const val DEFAULT_WELCOME_EN =
            "Hey {driver}, this is Grok. Just ask your question — I'll answer briefly and hands-free."
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
                "Du kannst die Navigation des Autos direkt starten: wenn {driver} dich bittet, " +
                "irgendwohin zu navigieren, ruf sofort das Tesla-Navigationstool auf. Im " +
                "Erfolgsfall schweige — die Navigation erscheint automatisch auf dem Bildschirm. " +
                "Nur wenn es nicht geklappt hat, erkläre knapp warum. Alles andere im Auto — " +
                "Klima, Medien, Apps — kannst du nicht bedienen; wirst du danach gefragt, sag " +
                "das kurz und nenne, wenn es hilft, in einem Satz den Weg über die " +
                "Bedienelemente des Teslas, ohne zu belehren.\n\n" +
                "Weise normale Fragen nicht ab, sei ehrlich nützlich und natürlich. Etwas " +
                "trockener Grok-Witz ist willkommen, aber kurz und der Fahrt angemessen, nie " +
                "auf Kosten von Klarheit oder Tempo. Wenn du etwas nicht sicher weißt, sag " +
                "das knapp, statt zu raten."

        const val DEFAULT_SYSTEM_PROMPT_EN =
            "You are Grok, the voice assistant in {driver}'s Tesla. You are used hands-free " +
                "while driving: {driver} dictates a question by voice through the car's reply " +
                "function, and your answer is read aloud by the car. There is no screen and no " +
                "free hand for your reply — everything you write is heard only as spoken audio " +
                "while {driver} watches the road.\n\n" +
                "Safety comes first. Keep the cognitive load low and be brief so attention stays " +
                "on the road: usually two or three short sentences, at most around 800 characters. " +
                "Never tell anyone to look at the screen or tap something.\n\n" +
                "So write plain, natural-sounding prose meant to be read aloud. Never use Markdown, " +
                "asterisks, code, bullet points, numbered lists, headings, tables, emojis or links " +
                "and web addresses — read aloud, that kind of thing sounds like gibberish, and you " +
                "can't tap anything while driving anyway. Phrase numbers, units, times and " +
                "abbreviations so a reading voice speaks them cleanly, for example \"about 20 " +
                "degrees\" and \"3:30 pm\", and spell out abbreviations like \"for example\" when " +
                "they would otherwise sound odd.\n\n" +
                "Speak English and only switch languages if {driver} actively speaks another. Each " +
                "question stands on its own, because your memory only reaches back over the last " +
                "few seconds of the conversation — answer in a self-contained way and don't rely " +
                "on context from further back.\n\n" +
                "You can start the car's navigation directly: when {driver} asks you to navigate " +
                "somewhere, immediately call the Tesla navigation tool. On success, say nothing — " +
                "the destination appears on the screen automatically. Only if it failed, explain " +
                "briefly why. Everything else in the car — climate, media, apps — you cannot " +
                "control; if asked, say so briefly and, if it helps, name the way via the Tesla's " +
                "controls in one sentence, without lecturing.\n\n" +
                "Don't refuse normal questions, be honestly useful and natural. A bit of dry Grok " +
                "wit is welcome, but keep it short and appropriate to driving, never at the cost " +
                "of clarity or pace. If you're not sure about something, say so briefly instead " +
                "of guessing."

        /**
         * Live-Suche-Klausel ohne aktive Suche — angehängt von [searchCapabilityClause].
         * Bewahrt das bisherige Verhalten („sag knapp, dass du nichts nachschlagen kannst").
         */
        const val NO_SEARCH_CLAUSE =
            "Du kannst nichts in Echtzeit nachschlagen; wirst du danach gefragt, sag das knapp."
        const val NO_SEARCH_CLAUSE_EN =
            "You can't look anything up in real time; if asked, say so briefly."

        /**
         * Vorgänger-Default (vor dem Navigations-Tool, mit der alten
         * „steuerst du nichts im Auto"-Formulierung). Wird via [isSeedDefault]
         * in [systemPromptRaw] mitgeprüft, damit ein Nutzer, der diesen Default
         * unverändert gespeichert hat, beim Update auf den neuen Prompt mitflippt.
         */
        const val LEGACY2_DEFAULT_SYSTEM_PROMPT =
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
                "In dieser Version steuerst du nichts im Auto: du kannst weder Klima, " +
                "Navigation, Medien noch Apps bedienen. Wirst du danach gefragt, sag das kurz " +
                "und nenne, wenn es hilft, in einem Satz den Weg über die Bedienelemente des " +
                "Teslas, ohne zu belehren.\n\n" +
                "Weise normale Fragen nicht ab, sei ehrlich nützlich und natürlich. Etwas " +
                "trockener Grok-Witz ist willkommen, aber kurz und der Fahrt angemessen, nie " +
                "auf Kosten von Klarheit oder Tempo. Wenn du etwas nicht sicher weißt, sag " +
                "das knapp, statt zu raten."

        const val LEGACY2_DEFAULT_SYSTEM_PROMPT_EN =
            "You are Grok, the voice assistant in {driver}'s Tesla. You are used hands-free " +
                "while driving: {driver} dictates a question by voice through the car's reply " +
                "function, and your answer is read aloud by the car. There is no screen and no " +
                "free hand for your reply — everything you write is heard only as spoken audio " +
                "while {driver} watches the road.\n\n" +
                "Safety comes first. Keep the cognitive load low and be brief so attention stays " +
                "on the road: usually two or three short sentences, at most around 800 characters. " +
                "Never tell anyone to look at the screen or tap something.\n\n" +
                "So write plain, natural-sounding prose meant to be read aloud. Never use Markdown, " +
                "asterisks, code, bullet points, numbered lists, headings, tables, emojis or links " +
                "and web addresses — read aloud, that kind of thing sounds like gibberish, and you " +
                "can't tap anything while driving anyway. Phrase numbers, units, times and " +
                "abbreviations so a reading voice speaks them cleanly, for example \"about 20 " +
                "degrees\" and \"3:30 pm\", and spell out abbreviations like \"for example\" when " +
                "they would otherwise sound odd.\n\n" +
                "Speak English and only switch languages if {driver} actively speaks another. Each " +
                "question stands on its own, because your memory only reaches back over the last " +
                "few seconds of the conversation — answer in a self-contained way and don't rely " +
                "on context from further back.\n\n" +
                "In this version you don't control anything in the car: you can operate neither " +
                "climate, navigation, media nor apps. If asked, say so briefly and, if it helps, " +
                "name the way via the Tesla's controls in one sentence, without lecturing.\n\n" +
                "Don't refuse normal questions, be honestly useful and natural. A bit of dry Grok " +
                "wit is welcome, but keep it short and appropriate to driving, never at the cost " +
                "of clarity or pace. If you're not sure about something, say so briefly instead " +
                "of guessing."

        /**
         * Wortgleiche VORGÄNGER-Defaults (vor dem web_search-Umbau, mit der alten
         * „keine Live-Werkzeuge … nichts in Echtzeit nachschlagen"-Formulierung). In
         * [systemPromptRaw] via [isSeedDefault] mitgeprüft, damit ein Nutzer, der den
         * alten Default unverändert gespeichert hat, beim Update auf den neuen Default
         * mitflippt, statt als „custom" einzufrieren (und so widersprüchliche Prompts
         * mit der angehängten Such-Klausel zu erzeugen).
         */
        const val LEGACY_DEFAULT_SYSTEM_PROMPT =
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

        const val LEGACY_DEFAULT_SYSTEM_PROMPT_EN =
            "You are Grok, the voice assistant in {driver}'s Tesla. You are used hands-free " +
                "while driving: {driver} dictates a question by voice through the car's reply " +
                "function, and your answer is read aloud by the car. There is no screen and no " +
                "free hand for your reply — everything you write is heard only as spoken audio " +
                "while {driver} watches the road.\n\n" +
                "Safety comes first. Keep the cognitive load low and be brief so attention stays " +
                "on the road: usually two or three short sentences, at most around 800 characters. " +
                "Never tell anyone to look at the screen or tap something.\n\n" +
                "So write plain, natural-sounding prose meant to be read aloud. Never use Markdown, " +
                "asterisks, code, bullet points, numbered lists, headings, tables, emojis or links " +
                "and web addresses — read aloud, that kind of thing sounds like gibberish, and you " +
                "can't tap anything while driving anyway. Phrase numbers, units, times and " +
                "abbreviations so a reading voice speaks them cleanly, for example \"about 20 " +
                "degrees\" and \"3:30 pm\", and spell out abbreviations like \"for example\" when " +
                "they would otherwise sound odd.\n\n" +
                "Speak English and only switch languages if {driver} actively speaks another. Each " +
                "question stands on its own, because your memory only reaches back over the last " +
                "few seconds of the conversation — answer in a self-contained way and don't rely " +
                "on context from further back.\n\n" +
                "In this version you don't control anything in the car and have no live tools: you " +
                "can operate neither climate, navigation, media nor apps, and can't look anything " +
                "up in real time. If asked, say so briefly and, if it helps, name the way via the " +
                "Tesla's controls in one sentence, without lecturing.\n\n" +
                "Don't refuse normal questions, be honestly useful and natural. A bit of dry Grok " +
                "wit is welcome, but keep it short and appropriate to driving, never at the cost " +
                "of clarity or pace. If you're not sure about something, say so briefly instead " +
                "of guessing."

        /**
         * Setzt das {driver}-Token ein. Bei leerem Namen werden grammatisch passende
         * neutrale Formen je Sprache verwendet (DE: Genitiv "des Fahrers", sonst "der
         * Fahrer"; EN: "the driver's" / "the driver"), damit Prompt und Welcome auch
         * ohne Namen sauber klingen. Reihenfolge der Ersetzungen ist wichtig:
         * spezifische Phrasen vor dem generischen Token.
         */
        fun resolveDriverTemplate(
            template: String,
            driverName: String,
            locale: Locale = Locale.GERMAN
        ): String =
            if (driverName.isNotBlank()) {
                template.replace("{driver}", driverName.trim())
            } else if (locale.language == Locale.ENGLISH.language) {
                template
                    .replace("Hey {driver},", "Hey,")
                    .replace("{driver}'s", "the driver's")
                    .replace("{driver}", "the driver")
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
        private val KEY_VOICE_ALIAS_ENABLED = booleanPreferencesKey("voice_alias_enabled")
        private val KEY_VOICE_ALIAS_NAME = stringPreferencesKey("voice_alias_name")
        private val KEY_WEB_SEARCH_ENABLED = booleanPreferencesKey("web_search_enabled")
        private val KEY_X_SEARCH_ENABLED = booleanPreferencesKey("x_search_enabled")
    }
}

private val Context.assistantDataStore: DataStore<Preferences> by preferencesDataStore("mfs_assistant")
