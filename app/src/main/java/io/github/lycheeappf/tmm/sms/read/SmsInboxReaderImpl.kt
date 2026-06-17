package io.github.lycheeappf.tmm.sms.read

import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.Telephony
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lycheeappf.tmm.core.di.IoDispatcher
import io.github.lycheeappf.tmm.core.model.FakeAddress
import io.github.lycheeappf.tmm.platform.role.DefaultSmsRoleManager
import io.github.lycheeappf.tmm.domain.sms.SmsConversation
import io.github.lycheeappf.tmm.domain.sms.SmsDirection
import io.github.lycheeappf.tmm.domain.sms.SmsInboxReader
import io.github.lycheeappf.tmm.domain.sms.SmsMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Liest echte SMS aus `content://sms`. Gruppierung Kotlin-seitig (kein
 * undokumentierter `GROUP BY`-sortOrder-Hack), Fakes via [FakeAddress.isFakeAddress]
 * ausgeschlossen, Namen via [ContactNameResolver]. Provider-Idiom analog
 * `SmsContentProviderWriter`/`OutboundSmsObserver`.
 */
@Singleton
class SmsInboxReaderImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactNameResolver: ContactNameResolver,
    private val roleManager: DefaultSmsRoleManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : SmsInboxReader {

    override suspend fun loadConversations(limit: Int): List<SmsConversation> =
        withContext(ioDispatcher) {
            val rows = queryRows(selection = null, args = null, scanLimit = CONVERSATION_SCAN_LIMIT)
            // isFake-Verdikt cachen — Adressen wiederholen sich über viele Rows.
            val fakeVerdict = HashMap<String, Boolean>()
            val isFake: (String) -> Boolean =
                { addr -> fakeVerdict.getOrPut(addr) { FakeAddress.isFakeAddress(addr) } }
            val grouped = groupConversations(rows, isFake, limit)
            // Namen anreichern (PhoneLookup, gecacht; ohne READ_CONTACTS → null).
            grouped.map { conv ->
                conv.copy(displayName = contactNameResolver.resolve(conv.address))
            }
        }

    override suspend fun loadThread(threadId: Long, limit: Int): List<SmsMessage> =
        withContext(ioDispatcher) {
            // Neueste `limit` Nachrichten holen (DATE DESC LIMIT), dann auf
            // aufsteigend drehen (älteste zuerst für die Thread-Ansicht).
            val rows = queryRows(
                selection = "${Telephony.Sms.THREAD_ID} = ?",
                args = arrayOf(threadId.toString()),
                scanLimit = limit
            )
            rows.asReversed()
                .filterNot { FakeAddress.isFakeAddress(it.address) } // defensiv
                .map { it.toMessage() }
        }

    override suspend fun contactName(address: String): String? =
        withContext(ioDispatcher) { contactNameResolver.resolve(address) }

    override suspend fun markThreadRead(threadId: Long) {
        withContext(ioDispatcher) {
            if (threadId < 0) return@withContext
            // Nur als Default-App darf der Provider geschrieben werden.
            if (!roleManager.isDefault()) return@withContext
            runCatching {
                val values = ContentValues().apply {
                    put(Telephony.Sms.READ, 1)
                    put(Telephony.Sms.SEEN, 1)
                }
                // Scoped auf thread_id + ungelesene eingehende Rows: trifft nie
                // einen Fake-Thread (eigene thread_ids) und schreibt 0 Rows, wenn
                // bereits alles gelesen ist (kein Observer-Loop).
                context.contentResolver.update(
                    Telephony.Sms.CONTENT_URI,
                    values,
                    "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0 AND ${Telephony.Sms.TYPE} = ?",
                    arrayOf(threadId.toString(), Telephony.Sms.MESSAGE_TYPE_INBOX.toString())
                )
            }.onFailure { Log.w(TAG, "markThreadRead failed", it) }
        }
    }

    override fun changes(): Flow<Unit> = callbackFlow {
        val thread = HandlerThread("MfsSmsReader").apply { start() }
        val handler = Handler(thread.looper)
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                trySend(Unit)
            }
        }
        try {
            context.contentResolver.registerContentObserver(
                Telephony.Sms.CONTENT_URI, /*notifyForDescendants*/ true, observer
            )
        } catch (e: Exception) {
            Log.w(TAG, "registerContentObserver failed", e)
        }
        awaitClose {
            runCatching { context.contentResolver.unregisterContentObserver(observer) }
            thread.quitSafely()
        }
    }

    /**
     * Roh-Query gegen `content://sms`, sortiert DATE DESC, begrenzt auf [scanLimit].
     * Gibt bei fehlender Permission/Fehler eine leere Liste zurück (das VM
     * flaggt Permission/Default-App separat).
     */
    private fun queryRows(selection: String?, args: Array<String>?, scanLimit: Int): List<RawSmsRow> {
        return try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                PROJECTION,
                selection,
                args,
                "${Telephony.Sms.DATE} DESC LIMIT $scanLimit"
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                val threadIdx = c.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
                val addrIdx = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIdx = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIdx = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val readIdx = c.getColumnIndexOrThrow(Telephony.Sms.READ)
                val typeIdx = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                val list = ArrayList<RawSmsRow>(c.count)
                while (c.moveToNext()) {
                    list += RawSmsRow(
                        id = c.getLong(idIdx),
                        threadId = c.getLong(threadIdx),
                        address = c.getString(addrIdx).orEmpty(),
                        body = c.getString(bodyIdx).orEmpty(),
                        date = c.getLong(dateIdx),
                        read = c.getInt(readIdx) != 0,
                        type = c.getInt(typeIdx)
                    )
                }
                list
            } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "queryRows failed (fehlende READ_SMS-Permission?)", e)
            emptyList()
        }
    }

    /** Rohzeile aus dem Provider, vor Domain-Mapping (intern für Pure-Tests). */
    internal data class RawSmsRow(
        val id: Long,
        val threadId: Long,
        val address: String,
        val body: String,
        val date: Long,
        val read: Boolean,
        val type: Int
    )

    private fun RawSmsRow.toMessage() = SmsMessage(
        id = id,
        threadId = threadId,
        address = address,
        body = body,
        date = date,
        direction = toDirection(type),
        read = read
    )

    companion object {
        private const val TAG = "SmsInboxReader"

        /** Obergrenze beim Scannen für die Konversationsliste (Paginierung/Kostenbremse). */
        private const val CONVERSATION_SCAN_LIMIT = 2000

        private val PROJECTION = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.READ,
            Telephony.Sms.TYPE
        )

        /** Telephony-`TYPE` → [SmsDirection]. Pure (Konstanten sind inline-int). */
        internal fun toDirection(type: Int): SmsDirection = when (type) {
            Telephony.Sms.MESSAGE_TYPE_INBOX -> SmsDirection.INBOX
            Telephony.Sms.MESSAGE_TYPE_SENT -> SmsDirection.SENT
            Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED -> SmsDirection.OUTBOX
            Telephony.Sms.MESSAGE_TYPE_FAILED -> SmsDirection.FAILED
            else -> SmsDirection.OTHER
        }

        /**
         * Gruppiert nach DATE DESC sortierte Rohzeilen zu Konversationen
         * (eine pro `thread_id`). Erste Zeile pro Thread = neueste = Snippet/Datum/
         * Adresse; Fakes ([isFake]) und leere Adressen werden ausgeschlossen.
         * `displayName` bleibt null (Anreicherung erfolgt separat). Pure für Tests.
         */
        internal fun groupConversations(
            rows: List<RawSmsRow>,
            isFake: (String) -> Boolean,
            limit: Int
        ): List<SmsConversation> {
            val acc = LinkedHashMap<Long, Acc>()
            for (row in rows) {
                if (row.address.isBlank()) continue
                if (isFake(row.address)) continue
                val a = acc.getOrPut(row.threadId) {
                    Acc(
                        address = row.address,
                        snippet = row.body,
                        date = row.date
                    )
                }
                a.messageCount++
                if (row.type == Telephony.Sms.MESSAGE_TYPE_INBOX && !row.read) a.unreadCount++
            }
            return acc.entries
                .map { (threadId, a) ->
                    SmsConversation(
                        threadId = threadId,
                        address = a.address,
                        displayName = null,
                        snippet = a.snippet,
                        date = a.date,
                        unreadCount = a.unreadCount,
                        messageCount = a.messageCount
                    )
                }
                .sortedByDescending { it.date }
                .take(limit)
        }

        private class Acc(
            val address: String,
            val snippet: String,
            val date: Long,
            var unreadCount: Int = 0,
            var messageCount: Int = 0
        )
    }
}
