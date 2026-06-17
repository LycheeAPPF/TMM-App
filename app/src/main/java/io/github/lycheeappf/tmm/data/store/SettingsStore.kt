package io.github.lycheeappf.tmm.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lycheeappf.tmm.core.model.AddressScheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("mfs_settings")

@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val store = context.dataStore

    // ---------- Counters ----------

    /**
     * GLOBALER Mapping-ID-Counter. Wird über ALLE Channels hinweg geteilt, damit
     * der DB-PK (`mappingId`) eindeutig bleibt. (Vor V2: pro-Channel-Counter,
     * was zu PK-Konflikten zwischen NotificationChannel.id=1 und LlmChannel.id=1
     * geführt hätte.)
     */
    suspend fun nextMappingId(): Long {
        val key = longPreferencesKey(KEY_NEXT_MAPPING_ID)
        var assigned = 0L
        store.edit { prefs ->
            // Migration: falls noch alte pro-Channel-Counter existieren,
            // nimm den höchsten als Startwert
            val legacyMax = (0..9).maxOfOrNull { code ->
                prefs[longPreferencesKey("next_mapping_id_$code")] ?: 0L
            } ?: 0L
            val cur = (prefs[key] ?: 1L).coerceAtLeast(legacyMax)
            assigned = cur
            prefs[key] = (cur + 1).coerceAtMost(MAX_MAPPING_ID - 1)
        }
        return assigned
    }

    suspend fun lastSeenOutboxId(): Long =
        store.data.first()[longPreferencesKey(KEY_LAST_OUTBOX_ID)] ?: 0L

    suspend fun setLastSeenOutboxId(id: Long) {
        store.edit { it[longPreferencesKey(KEY_LAST_OUTBOX_ID)] = id }
    }

    // ---------- Send-Budget ----------

    suspend fun incrementDailySendCount(): Int {
        val today = LocalDate.now().toEpochDay()
        val key = intPreferencesKey("send_count_$today")
        var newCount = 0
        store.edit { prefs ->
            newCount = (prefs[key] ?: 0) + 1
            prefs[key] = newCount
        }
        return newCount
    }

    suspend fun decrementDailySendCount(): Int {
        val today = LocalDate.now().toEpochDay()
        val key = intPreferencesKey("send_count_$today")
        var newCount = 0
        store.edit { prefs ->
            newCount = ((prefs[key] ?: 0) - 1).coerceAtLeast(0)
            prefs[key] = newCount
        }
        return newCount
    }

    suspend fun dailySendCount(): Int {
        val today = LocalDate.now().toEpochDay()
        return store.data.first()[intPreferencesKey("send_count_$today")] ?: 0
    }

    suspend fun sendBudgetPerDay(): Int =
        store.data.first()[intPreferencesKey(KEY_SEND_BUDGET)] ?: DEFAULT_SEND_BUDGET

    suspend fun setSendBudgetPerDay(value: Int) {
        store.edit { it[intPreferencesKey(KEY_SEND_BUDGET)] = value }
    }

    // ---------- TTL ----------

    suspend fun mappingTtlHours(): Int =
        store.data.first()[intPreferencesKey(KEY_TTL_HOURS)] ?: DEFAULT_TTL_HOURS

    suspend fun setMappingTtlHours(hours: Int) {
        store.edit { it[intPreferencesKey(KEY_TTL_HOURS)] = hours }
    }

    fun mappingTtlHoursFlow(): Flow<Int> =
        store.data.map { it[intPreferencesKey(KEY_TTL_HOURS)] ?: DEFAULT_TTL_HOURS }

    // ---------- Pre-Flight ----------

    suspend fun setPreflightResult(result: String) {
        store.edit { it[stringPreferencesKey(KEY_PREFLIGHT_RESULT)] = result }
    }

    suspend fun preflightResult(): String? =
        store.data.first()[stringPreferencesKey(KEY_PREFLIGHT_RESULT)]?.takeIf { it.isNotBlank() }

    fun preflightResultFlow(): Flow<String?> =
        store.data.map { it[stringPreferencesKey(KEY_PREFLIGHT_RESULT)]?.takeIf { v -> v.isNotBlank() } }

    // ---------- Onboarding ----------

    suspend fun isOnboarded(): Boolean =
        store.data.first()[androidx.datastore.preferences.core.booleanPreferencesKey(KEY_ONBOARDED)] ?: false

    suspend fun setOnboarded(value: Boolean) {
        store.edit { it[androidx.datastore.preferences.core.booleanPreferencesKey(KEY_ONBOARDED)] = value }
    }

    // ---------- Risk-Acknowledgement ----------

    suspend fun isRiskAcknowledged(): Boolean =
        store.data.first()[androidx.datastore.preferences.core.booleanPreferencesKey(KEY_RISK_ACK)] ?: false

    suspend fun setRiskAcknowledged(value: Boolean) {
        store.edit { it[androidx.datastore.preferences.core.booleanPreferencesKey(KEY_RISK_ACK)] = value }
    }

    // ---------- Contacts-Step Skip-Flag ----------

    suspend fun isContactsStepSkipped(): Boolean =
        store.data.first()[androidx.datastore.preferences.core.booleanPreferencesKey(KEY_CONTACTS_SKIPPED)] ?: false

    suspend fun setContactsStepSkipped(value: Boolean) {
        store.edit { it[androidx.datastore.preferences.core.booleanPreferencesKey(KEY_CONTACTS_SKIPPED)] = value }
    }

    // ---------- Developer-Mode ----------

    /**
     * Gibt die Diagnose-/Experten-Oberfläche frei (Diagnose-Screen, Pre-Flight-
     * Retest, Experten-Settings). Default false — der normale User sieht eine
     * schlanke App. Aktivierbar über 7× Tipp auf das Versions-Label in Settings.
     */
    suspend fun isDeveloperMode(): Boolean =
        store.data.first()[androidx.datastore.preferences.core.booleanPreferencesKey(KEY_DEVELOPER_MODE)] ?: false

    suspend fun setDeveloperMode(value: Boolean) {
        store.edit { it[androidx.datastore.preferences.core.booleanPreferencesKey(KEY_DEVELOPER_MODE)] = value }
    }

    fun developerModeFlow(): Flow<Boolean> =
        store.data.map { it[androidx.datastore.preferences.core.booleanPreferencesKey(KEY_DEVELOPER_MODE)] ?: false }

    // ---------- Address-Scheme ----------

    /**
     * Aktives Fake-Adress-Schema. Fix auf [AddressScheme.Itu888] — +888 ist das
     * einzige aktive Schema (von Android auflösbar, vom Carrier kostenlos abgelehnt).
     * Die abgelösten Schemata (+99942, +4932) leben nur noch als Parse-Fallback in
     * [AddressScheme.entries] für Alt-Mappings.
     */
    suspend fun addressScheme(): AddressScheme = AddressScheme.Itu888

    // ---------- Display-Mode (Tesla-Sender-Anzeige) ----------

    /**
     * Steuert wie `Telephony.Sms.ADDRESS` für eingehende Fake-SMS gebaut wird.
     * Tesla MCU2 zeigt die ADDRESS-Spalte verbatim im Sender-Display.
     *  - [DISPLAY_NUMERIC] (Default): nur `"+9994x..."` — kein Name in der ADDRESS;
     *    der Name kommt über den Contact-Sync-Pfad (PBAP).
     *  - Jeder andere (Legacy-)Wert: `"Name <+9994x...>"`-Bracket-Form als Fallback.
     */
    suspend fun displayMode(): String =
        store.data.first()[stringPreferencesKey(KEY_DISPLAY_MODE)] ?: DEFAULT_DISPLAY_MODE

    fun sendBudgetFlow(): Flow<Int> =
        store.data.map { it[intPreferencesKey(KEY_SEND_BUDGET)] ?: DEFAULT_SEND_BUDGET }

    companion object {
        const val DEFAULT_SEND_BUDGET = 100
        const val DEFAULT_TTL_HOURS = 24
        const val MAX_MAPPING_ID = 10_000_000L

        // Display-Modus für Sms.ADDRESS — siehe [displayMode] für Beschreibung
        const val DISPLAY_NUMERIC = "numeric"      // "+999..." + ContactSync → Tesla zeigt "Grok" sauber

        // Default = NUMERIC: pure Nummer in ADDRESS + ContactSync legt einen
        // unsichtbaren RawContact an, sodass AOSP-MAP den Namen ins Listing
        // schreibt. Sauberes Tesla-Display, Phonebook bleibt clean. Bei
        // fehlender WRITE_CONTACTS-Permission zeigt Tesla die Rohnummer (Signal
        // an den User, Permission zu erteilen).
        const val DEFAULT_DISPLAY_MODE = DISPLAY_NUMERIC

        const val PREFLIGHT_OK = "ok_failed_in_carrier"
        const val PREFLIGHT_RISK = "warning_sent_via_carrier"
        const val PREFLIGHT_TIMEOUT = "timeout_unknown"
        const val PREFLIGHT_RUNNING = "running"
        const val PREFLIGHT_ERROR = "error"

        private const val KEY_NEXT_MAPPING_ID = "next_mapping_id_global"
        private const val KEY_LAST_OUTBOX_ID = "last_outbox_id"
        private const val KEY_SEND_BUDGET = "send_budget_per_day"
        private const val KEY_TTL_HOURS = "mapping_ttl_hours"
        private const val KEY_PREFLIGHT_RESULT = "preflight_result"
        private const val KEY_ONBOARDED = "is_onboarded"
        private const val KEY_RISK_ACK = "risk_acknowledged"
        private const val KEY_CONTACTS_SKIPPED = "contacts_step_skipped"
        private const val KEY_DISPLAY_MODE = "tesla_display_mode"
        private const val KEY_DEVELOPER_MODE = "developer_mode"
    }
}
