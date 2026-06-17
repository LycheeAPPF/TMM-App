package io.github.lycheeappf.tmm.sms.provider

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lycheeappf.tmm.channel.llm.InjectedMessageLedger
import io.github.lycheeappf.tmm.contact.ContactSyncWriter
import io.github.lycheeappf.tmm.platform.role.DefaultSmsRoleManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schreibt SMS-Rows in den Android SMS Content Provider.
 *
 * Wird in zwei Pfaden genutzt:
 * 1. [injectIncoming]: vom NotificationCapture (Messenger → Tesla) und Channel.maybeInjectFollowUp (LLM)
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
    private val contactSyncWriter: ContactSyncWriter
) {

    /**
     * Schreibt eine fake inbound SMS. In `Telephony.Sms.ADDRESS` landet nur die
     * reine `fakeAddress` (+888x...); der Name kommt über den Contact-Sync-Pfad
     * (PBAP), sodass Tesla z.B. "Grok" sauber zeigt.
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

        // Die ADDRESS-Spalte trägt nur die pure Fake-Number (+888...). Damit Tesla
        // trotzdem "Grok"/"Anna" zeigt, legen wir einen RawContact an, den Tesla per
        // PBAP-Cache pullen kann. Permission-Check + Account-Setup macht der
        // ContactSyncWriter idempotent; ohne Permission gracefully no-op.
        if (!displayName.isNullOrBlank()) {
            contactSyncWriter.upsertContact(fakeAddress, displayName)
        }

        val firstAttempt = doInsert(fakeAddress, fakeAddress, body, timestamp, requireThreadId = true)
        if (firstAttempt != null) {
            // Echo-Ledger auf der Fake-Number registrieren — die Outbox-Address kann
            // bei Tesla-Reply leicht abweichen (z.B. Bracket-Form);
            // [InjectedMessageLedger.shouldIgnoreOutbound] normalisiert gleich.
            injectedMessageLedger.markInjected(fakeAddress, body)
            return firstAttempt
        }

        // Insert hat fehlgeschlagen — möglicherweise stale Thread-ID im Cache.
        // Invalidiere und retry einmal ohne gecachte Thread-ID.
        threadIdResolver.invalidate(fakeAddress)
        Log.w(TAG, "Insert failed for $fakeAddress, invalidating thread cache and retrying")
        val retried = doInsert(fakeAddress, fakeAddress, body, timestamp, requireThreadId = true)
        if (retried != null) injectedMessageLedger.markInjected(fakeAddress, body)
        return retried
    }

    private fun doInsert(
        addressForProvider: String,
        threadKey: String,
        body: String,
        timestamp: Long,
        requireThreadId: Boolean
    ): Uri? {
        // Thread-Lookup IMMER mit der pure Fake-Number — die Bracket-Form
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

    companion object {
        private const val TAG = "SmsProviderWriter"
    }
}
