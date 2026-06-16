package io.github.lycheeappf.tmm.sms.provider

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lycheeappf.tmm.channel.llm.InjectedMessageLedger
import io.github.lycheeappf.tmm.contact.ContactSyncWriter
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.data.store.SettingsStore
import io.github.lycheeappf.tmm.platform.role.DefaultSmsRoleManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schreibt SMS-Rows in den Android SMS Content Provider.
 *
 * Wird in zwei Pfaden genutzt:
 * 1. [injectIncoming]: vom NotificationCapture (Beeper → Tesla) und Channel.maybeInjectFollowUp (LLM)
 * 2. Aufruf via DeliverSmsReceiver für echte eingehende SMS (DeliverSmsReceiver
 *    macht das direkt, ohne diese Klasse zu nutzen)
 *
 * Funktioniert nur, wenn die App Default SMS App ist.
 */
@Singleton
class SmsContentProviderWriter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val threadIdResolver: ThreadIdResolver,
    private val roleManager: DefaultSmsRoleManager,
    private val injectedMessageLedger: InjectedMessageLedger,
    private val settingsStore: SettingsStore,
    private val contactSyncWriter: ContactSyncWriter,
    private val logBuffer: LogBuffer
) {

    /**
     * Schreibt eine fake inbound SMS. Die `fakeAddress` (numerisch, +9994x...)
     * wird in einen **Hybrid-Form** "Display <Number>" eingewickelt, falls
     * `displayName` gesetzt ist. Resultat ist ein RFC-822-Mailbox-ähnliches
     * `Telephony.Sms.ADDRESS`-Feld wie `"Grok <+9994210000007>"`.
     *
     * Hintergrund: Display-only-ADDRESS (`"Grok"`) bricht den Reply-Path —
     * Tesla/AOSP-MAP scheint die TEL-Form zu validieren oder zu strippen, sodass
     * keine deterministisch erkennbare Outbox-Row für unsere App-Adressen
     * entsteht. Mit der Hybrid-Form bleibt die `+9994x...`-Substring drin und
     * [FakeAddress.parse] kann sie aus dem Tesla-Outbox-Roundtrip wieder
     * herauspopeln, selbst wenn Tesla die ganze Address durchreicht oder
     * extrahiert.
     *
     * Trade-off: Tesla MCU2 zeigt aktuell die *ganze* Hybrid-Form als Sender,
     * also `"Grok <+9994210000007>"` statt nur `"Grok"`. Das ist hässlicher
     * als das vorherige Display-only-Experiment, aber sicherer für den
     * Reply-Pfad. Saubereres Display bleibt offen — wir loggen jetzt die
     * Outbox-Rows in den LogBuffer, um datengetrieben einen anderen Trick zu
     * finden (Unicode-Trennzeichen, vCard-Manipulation, etc.).
     *
     * @return Uri der eingefügten Row oder null bei Fehler. Null wird auch
     *   returnt, wenn die App nicht Default SMS App ist — der Insert würde
     *   sonst silent fail.
     */
    suspend fun injectIncoming(
        fakeAddress: String,
        body: String,
        timestamp: Long = System.currentTimeMillis(),
        displayName: String? = null
    ): Uri? {
        if (!roleManager.isDefault()) {
            Log.w(TAG, "injectIncoming aborted — app is not default SMS app")
            return null
        }

        val modeRead = runCatching { settingsStore.displayMode() }
        modeRead.exceptionOrNull()?.let { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Fallback auf den DEFAULT (= NUMERIC), NICHT hart auf HYBRID. Sonst
            // degradiert ein transienter DataStore-Fehler den User stumm aus dem
            // Clean-Name-Modus und Tesla zeigt wieder "Grok <+999...>". Sichtbar
            // im Diagnose-Screen (LogBuffer), nicht nur in Logcat.
            Log.w(TAG, "displayMode read failed, falling back to default", e)
            logBuffer.warn(
                TAG,
                "displayMode read failed (${e::class.simpleName}: ${e.message}) " +
                    "→ fallback ${SettingsStore.DEFAULT_DISPLAY_MODE}"
            )
        }
        val mode = displayModeOrFallback(modeRead)

        // Im NUMERIC-Modus ist Display nur "+999..." in der ADDRESS-Spalte —
        // damit Tesla trotzdem "Grok" oder "Anna" zeigt, brauchen wir einen
        // Contact-Eintrag, den Tesla per PBAP-Cache pullen kann. Im Hybrid/
        // Padding-Modus brauchen wir ContactSync nicht (Name ist schon in der
        // ADDRESS verpackt). Permission-Check + Account-Setup macht der
        // ContactSyncWriter idempotent; ohne Permission gracefully no-op.
        if (mode == SettingsStore.DISPLAY_NUMERIC && !displayName.isNullOrBlank()) {
            contactSyncWriter.upsertContact(fakeAddress, displayName)
        }

        val addressForInsert = composeDisplayAddress(displayName, fakeAddress, mode)
        val firstAttempt = doInsert(addressForInsert, fakeAddress, body, timestamp, requireThreadId = true)
        if (firstAttempt != null) {
            // Echo-Ledger auf der PURE FakeNumber registrieren — die Outbox-Address
            // kann bei Tesla-Reply leicht abweichen (z.B. nur "+999..." statt
            // "Grok <+999...>"). [InjectedMessageLedger.shouldIgnoreOutbound]
            // normalisiert dieselbe Weise.
            injectedMessageLedger.markInjected(fakeAddress, body)
            return firstAttempt
        }

        // Insert hat fehlgeschlagen — möglicherweise stale Thread-ID im Cache.
        // Invalidiere und retry einmal ohne gecachte Thread-ID.
        threadIdResolver.invalidate(fakeAddress)
        Log.w(TAG, "Insert failed for $fakeAddress, invalidating thread cache and retrying")
        val retried = doInsert(addressForInsert, fakeAddress, body, timestamp, requireThreadId = true)
        if (retried != null) injectedMessageLedger.markInjected(fakeAddress, body)
        return retried
    }

    private fun composeDisplayAddress(
        displayName: String?,
        fakeAddress: String,
        mode: String
    ): String = composeDisplayAddressPure(displayName, fakeAddress, mode)

    private fun doInsert(
        addressForProvider: String,
        threadKey: String,
        body: String,
        timestamp: Long,
        requireThreadId: Boolean
    ): Uri? {
        // Thread-Lookup IMMER mit der pure Fake-Number — der Hybrid-String
        // würde von `Telephony.Threads.getOrCreateThreadId` ggf. mit
        // IllegalArgumentException quittiert (manche AOSP-Branches normalisieren
        // hart). Threads sind nur an die Number gebunden, der Display-Teil ist
        // pro Insert in der ADDRESS-Spalte.
        val threadId = if (requireThreadId) {
            try {
                threadIdResolver.getOrCreate(threadKey)
            } catch (e: Exception) {
                Log.w(TAG, "ThreadIdResolver failed for $threadKey", e)
                -1L
            }
        } else -1L

        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, addressForProvider)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, timestamp)
            put(Telephony.Sms.DATE_SENT, timestamp)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            if (threadId > 0) put(Telephony.Sms.THREAD_ID, threadId)
        }
        return try {
            context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
        } catch (e: Exception) {
            Log.e(TAG, "doInsert failed for $addressForProvider (threadId=$threadId)", e)
            null
        }
    }

    /**
     * Löscht eine SMS-Row anhand der vollen URI (typisch: aus Outbox nach Tesla-Reply).
     */
    fun deleteRow(uri: Uri): Int = try {
        context.contentResolver.delete(uri, null, null)
    } catch (e: Exception) {
        Log.w(TAG, "deleteRow failed: $uri", e)
        0
    }

    /**
     * Löscht alle SMS-Rows mit der angegebenen Address (Fake-Adresse-Cleanup
     * nach TTL-Expiry oder Tesla-Disconnect).
     */
    fun deleteByAddress(address: String): Int = try {
        context.contentResolver.delete(
            Telephony.Sms.CONTENT_URI,
            "${Telephony.Sms.ADDRESS} = ?",
            arrayOf(address)
        )
    } catch (e: Exception) {
        Log.w(TAG, "deleteByAddress failed: $address", e)
        0
    }

    /**
     * Löscht alle SMS-Rows, deren ADDRESS die Fake-Number ENTHÄLT — egal ob pure
     * (`"+9994..."`) oder eingebettet in eine HYBRID/PADDING-Form
     * (`"Grok <+9994...>"`). Nötig beim Wechsel des Display-Modus: ein reiner
     * Exact-Match (`deleteByAddress`) würde die alten Hybrid-Rows NICHT treffen,
     * und Tesla zeigt die gecachte `"Grok <+999...>"`-ADDRESS sonst weiter an.
     *
     * Fake-Numbers (`+9994x...`) sind eindeutige Substrings ohne LIKE-Sonderzeichen,
     * daher ist der `%number%`-Match sicher gegen Kollisionen mit echten SMS.
     */
    fun deleteByFakeNumber(fakeNumber: String): Int = try {
        context.contentResolver.delete(
            Telephony.Sms.CONTENT_URI,
            "${Telephony.Sms.ADDRESS} LIKE ?",
            arrayOf("%$fakeNumber%")
        )
    } catch (e: Exception) {
        Log.w(TAG, "deleteByFakeNumber failed: $fakeNumber", e)
        0
    }

    companion object {
        private const val TAG = "SmsProviderWriter"
        internal const val MAX_DISPLAY_CHARS = 40
        internal const val PADDING_SPACES = 40

        /**
         * Liefert den gelesenen Display-Modus, oder bei Lese-Fehler den DEFAULT
         * ([SettingsStore.DEFAULT_DISPLAY_MODE] = NUMERIC). Bewusst NICHT HYBRID:
         * ein transienter DataStore-Fehler darf den User nicht stumm aus dem
         * Clean-Name-Modus werfen. Pure-Funktion für Unit-Tests.
         */
        internal fun displayModeOrFallback(read: Result<String>): String =
            read.getOrElse { SettingsStore.DEFAULT_DISPLAY_MODE }

        /**
         * Reine String→String-Logik für die ADDRESS-Form je nach
         * [SettingsStore.displayMode]. Ausgelagert für Unit-Tests.
         *
         *   - `DISPLAY_HYBRID` (default, sicher): `"Name <+999...>"` — Number
         *     sichtbar, aber Tesla hat einen RFC-822-ähnlichen Bracket-Anker für
         *     Reply-Extract via AOSP `PhoneNumberUtils.extractNetworkPortion`.
         *   - `DISPLAY_PADDING` (experimentell): `"Name" + 40 spaces + "<+999...>"`
         *     — Hoffnung dass Tesla MCU2 die Display-Spalte nach ~20 Chars
         *     truncated und nur "Name" anzeigt. Reply identisch zu Hybrid.
         *   - `DISPLAY_NUMERIC` (für Contact-Sync-Pfad): nur die Number. Display
         *     zeigt "+999..." es sei denn Tesla pulled unseren RawContact über
         *     PBAP-Cache → dann zeigt es den Contact-DisplayName.
         *
         * Sanitization am displayName: entfernt `<` `>` `,` `;` `"` `\` und
         * Control-Chars, kollabiert Whitespace, capt auf 40 Zeichen.
         */
        internal fun composeDisplayAddressPure(
            displayName: String?,
            fakeAddress: String,
            mode: String
        ): String {
            if (displayName.isNullOrBlank() || mode == SettingsStore.DISPLAY_NUMERIC) {
                return fakeAddress
            }
            val sanitized = displayName
                .replace(Regex("[<>,;\"\\\\\\r\\n\\t]"), " ")
                .replace(Regex(" +"), " ")
                .trim()
                .take(MAX_DISPLAY_CHARS)
            if (sanitized.isBlank()) return fakeAddress
            return when (mode) {
                SettingsStore.DISPLAY_PADDING ->
                    "$sanitized" + " ".repeat(PADDING_SPACES) + "<$fakeAddress>"
                else -> "$sanitized <$fakeAddress>"  // DISPLAY_HYBRID (default)
            }
        }
    }
}
