package io.github.lycheeappf.tmm.sms.outbound

import android.app.NotificationManager
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lycheeappf.tmm.MfsApplication
import io.github.lycheeappf.tmm.R
import io.github.lycheeappf.tmm.channel.llm.InjectedMessageLedger
import io.github.lycheeappf.tmm.core.di.IoDispatcher
import io.github.lycheeappf.tmm.core.locale.localizedString
import io.github.lycheeappf.tmm.core.model.FakeAddress
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.data.repository.ReplyHistoryRecorder
import io.github.lycheeappf.tmm.data.store.SettingsStore
import io.github.lycheeappf.tmm.domain.reply.ReplyResult
import io.github.lycheeappf.tmm.domain.routing.ReplyDispatcher
import io.github.lycheeappf.tmm.sms.send.SelfSendLedger
import io.github.lycheeappf.tmm.ui.screen.onboarding.PreFlightCoordinator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Beobachtet `content://sms` und dispatched Tesla-Replies an den ReplyDispatcher.
 *
 * Statusmodell: Rows wandern durch Telephony-Typen
 *  - MESSAGE_TYPE_OUTBOX (queued)
 *  - MESSAGE_TYPE_SENT (Carrier hat akzeptiert!)
 *  - MESSAGE_TYPE_FAILED (Carrier rejected — der gewünschte Pfad für unsere Fakes)
 *
 * Die Row-ID bleibt über alle Status-Updates **gleich**. Deshalb tracken wir
 * `Map<rowId, lastSeenType>`: bei TYPE-Wechsel von OUTBOX → SENT triggern wir die
 * Carrier-Routing-Warnung, ohne den Reply nochmal zu dispatchen.
 */
@Singleton
class OutboundSmsObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val classifier: OutboundSmsClassifier,
    private val dispatcher: ReplyDispatcher,
    private val failedRowCleaner: FailedRowCleaner,
    private val settingsStore: SettingsStore,
    private val replyHistory: ReplyHistoryRecorder,
    private val preFlightCoordinator: PreFlightCoordinator,
    private val logBuffer: LogBuffer,
    private val injectedMessageLedger: InjectedMessageLedger,
    private val selfSendLedger: SelfSendLedger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    private val registered = AtomicBoolean(false)
    private val initialized = AtomicBoolean(false)
    private val handlerThread = HandlerThread("MfsOutboundObserver").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val dispatchMutex = Mutex()

    /**
     * Tracked rowId → letzter beobachteter MESSAGE_TYPE. Status-Update-Erkennung:
     * gleicher rowId mit neuem TYPE → reprocess (kein neuer Reply, aber ggf. Warning).
     * Bounded LinkedHashMap mit access-order ist hier nicht nötig — wir sind
     * unter `dispatchMutex.withLock` serialisiert.
     */
    private val dispatchedRowStates: LinkedHashMap<Long, Int> = LinkedHashMap()

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            scope.launch { processChanges() }
        }
    }

    fun register() {
        if (registered.getAndSet(true)) return
        try {
            context.contentResolver.registerContentObserver(
                Telephony.Sms.CONTENT_URI,
                /*notifyForDescendants*/ true,
                observer
            )
            Log.i(TAG, "OutboundSmsObserver registered")
            // initLastSeenIfNeeded läuft im ersten processChanges synchron unter Mutex,
            // sodass keine Race zwischen Init und Event-Processing entstehen kann.
        } catch (e: Exception) {
            registered.set(false)
            Log.e(TAG, "register failed", e)
        }
    }

    fun unregister() {
        if (!registered.getAndSet(false)) return
        try {
            context.contentResolver.unregisterContentObserver(observer)
            Log.i(TAG, "OutboundSmsObserver unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "unregister failed", e)
        }
    }

    fun shutdown() {
        unregister()
        scope.cancel()
        handlerThread.quitSafely()
    }

    private suspend fun processChanges() = dispatchMutex.withLock {
        if (!initialized.get()) {
            initLastSeenInternal()
            initialized.set(true)
        }

        val lastSeenId = settingsStore.lastSeenOutboxId()
        val lookbackStart = (lastSeenId - LOOKBACK_WINDOW).coerceAtLeast(0L)
        val rows = queryRowsSince(lookbackStart)
        if (rows.isEmpty()) {
            // ContentObserver feuerte, aber die Query lieferte nichts — Tesla schrieb
            // einen TYPE den wir nicht beobachten, die Row wurde gepurged, oder wir
            // verloren das Change-Race.
            return@withLock
        }
        logBuffer.info(TAG, "processChanges: ${rows.size} candidate rows since id=$lookbackStart")

        var maxSeenId = lastSeenId
        try {
            for (row in rows) {
                try {
                    processRow(row)
                } catch (e: Exception) {
                    // CancellationException muss durch — sonst bricht structured concurrency.
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(TAG, "processRow failed for row=${row.id}", e)
                    logBuffer.error(TAG, "row ${row.id} (${addrForLog(row.address)}) failed: ${e.message}")
                }
                if (row.id > maxSeenId) maxSeenId = row.id
            }
            if (maxSeenId > lastSeenId) {
                settingsStore.setLastSeenOutboxId(maxSeenId)
            }
        } finally {
            // Eviction MUSS laufen, auch wenn oben etwas wirft — sonst wächst
            // dispatchedRowStates über die Zeit unbegrenzt.
            evictOldDispatchedStates()
        }
    }

    private suspend fun initLastSeenInternal() {
        if (settingsStore.lastSeenOutboxId() > 0L) return
        val maxId = queryMaxId()
        settingsStore.setLastSeenOutboxId(maxId.coerceAtLeast(0L))
        Log.i(TAG, "Initialized lastSeenOutboxId to $maxId (skipping historical SMS)")
    }

    private fun queryMaxId(): Long = try {
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID),
            null, null,
            "${Telephony.Sms._ID} DESC LIMIT 1"
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L
    } catch (e: Exception) {
        Log.w(TAG, "queryMaxId failed", e)
        0L
    }

    private suspend fun processRow(row: OutboundSmsRow) {
        // DIAGNOSE: Jede outbox-Row im LogBuffer protokollieren, damit der
        // User in Settings > Diagnostics nachvollziehen kann, was Tesla beim
        // Reply tatsächlich in den Provider schreibt (ADDRESS-Form, TYPE,
        // Body-Länge). Body selbst landet nicht im Buffer (Privacy).
        logBuffer.info(
            TAG,
            "Outbox-Row #${row.id} type=${row.type} addr='${addrForLog(row.address)}' body=${row.body.length}ch"
        )

        if (preFlightCoordinator.isReservedForPreflight(row.address)) {
            logBuffer.info(TAG, "Row ${row.id}: preflight reserved → skip")
            return
        }

        // Belt-and-Suspenders: Wenn die Outbox-Row body+address einer gerade
        // injizierten Inbox-Message gleicht, ist das vermutlich ein Echo (z.B.
        // wenn die LLM-Welcome-Message irgendwie als Outbound zurückkäme).
        // Eigene Inserts kommen normalerweise als INBOX rein und triggern den
        // Observer nicht, der defensive Check kostet aber praktisch nichts.
        if (injectedMessageLedger.shouldIgnoreOutbound(row.address, row.body)) {
            logBuffer.warn(TAG, "Echo detected for ${addrForLog(row.address)} — dropped (row ${row.id})")
            failedRowCleaner.delete(row.id)
            dispatchedRowStates[row.id] = row.type
            return
        }

        // Eigene In-App-Sends (RealSmsSender) NICHT als Tesla-Reply behandeln —
        // weder dispatchen noch löschen. rowId-Match bevorzugt, (address,body) als
        // Fallback fürs Race „Observer sieht Row, bevor markRowId lief".
        if (selfSendLedger.isSelfSend(row.id) || selfSendLedger.isSelfSend(row.address, row.body)) {
            logBuffer.info(TAG, "Row ${row.id}: self-send (eigene App) → skip")
            dispatchedRowStates[row.id] = row.type
            return
        }

        val previousType = dispatchedRowStates[row.id]

        // Identische Notification (gleiche rowId, gleicher TYPE) → schon verarbeitet
        if (previousType == row.type) return

        val cls = classifier.classify(row)
        if (cls is OutboundSmsClassifier.Classification.NotOurs) {
            // user-initiated SMS via Google Messages → nicht anfassen
            logBuffer.info(TAG, "Row ${row.id} ('${addrForLog(row.address)}') → NotOurs, kein Dispatch")
            return
        }
        cls as OutboundSmsClassifier.Classification.TeslaReply
        logBuffer.info(
            TAG,
            "Row ${row.id} → TeslaReply mapping=${cls.mappingId} channel=${cls.channelCode}"
        )

        val carrierAccepted = row.type == Telephony.Sms.MESSAGE_TYPE_SENT
        val isFirstDispatch = previousType == null

        // Status-Update: Carrier-Warning auch wenn schon dispatched
        if (carrierAccepted) {
            postCarrierRoutingWarning(row.address)
            logBuffer.error(
                TAG,
                "CARRIER ROUTED FAKE ADDRESS: ${row.address} (row=${row.id} prev=$previousType) — possible billing"
            )
        }

        if (isFirstDispatch) {
            // LLM-Channel-Diktate enthalten u.U. private Prompts — redacten.
            val isLlmAddress = cls.channelCode == io.github.lycheeappf.tmm.core.model.ChannelId.LLM.code
            val bodyForLog = if (isLlmAddress) "<llm-prompt redacted>" else row.body.take(40)
            Log.i(TAG, "Tesla reply detected: addr=${row.address} body=$bodyForLog")
            val result: ReplyResult? = try {
                dispatcher.dispatch(cls.mappingId, cls.channelCode, row.body)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Dispatch failed", e)
                logBuffer.error(TAG, "Dispatch threw for row ${row.id}: ${e::class.simpleName}")
                null
            }
            logBuffer.info(TAG, "Dispatch row=${row.id} result=${result?.let { it::class.simpleName } ?: "null"}")

            replyHistory.record(
                cls.mappingId, cls.channelCode, row.body,
                result ?: ReplyResult.ProviderError("dispatcher threw exception")
            )

            // Row löschen außer carrierAccepted (= Audit-Trail für Kosten-Beweis).
            // Auch bei Dispatch-Fehlern löschen, damit keine stale FAILED-Rows
            // dauerhaft in Google Messages stehen bleiben.
            if (!carrierAccepted) {
                failedRowCleaner.delete(row.id)
            }
        }
        // (else: schon dispatched → unten nur das Type-Tracking aktualisieren.)

        dispatchedRowStates[row.id] = row.type
    }

    private fun evictOldDispatchedStates() {
        if (dispatchedRowStates.size <= DISPATCH_CACHE_SIZE) return
        val iterator = dispatchedRowStates.entries.iterator()
        while (dispatchedRowStates.size > DISPATCH_CACHE_SIZE && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }

    private fun postCarrierRoutingWarning(address: String) {
        val nm = context.getSystemService<NotificationManager>() ?: return
        val notif = NotificationCompat.Builder(context, MfsApplication.CHANNEL_FALLBACK)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.localizedString(R.string.carrier_warning_title))
            .setContentText(context.localizedString(R.string.carrier_warning_text, address))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    context.localizedString(R.string.carrier_warning_big, address)
                )
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .build()
        try { nm.notify(NOTIF_ID_CARRIER_WARNING, notif) }
        catch (e: SecurityException) { Log.e(TAG, "Carrier-warning notify failed", e) }
    }

    private fun queryRowsSince(minId: Long): List<OutboundSmsRow> {
        return try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.TYPE,
                    Telephony.Sms.DATE
                ),
                "${Telephony.Sms.TYPE} IN (?, ?, ?) AND ${Telephony.Sms._ID} >= ?",
                arrayOf(
                    Telephony.Sms.MESSAGE_TYPE_OUTBOX.toString(),
                    Telephony.Sms.MESSAGE_TYPE_SENT.toString(),
                    Telephony.Sms.MESSAGE_TYPE_FAILED.toString(),
                    minId.toString()
                ),
                "${Telephony.Sms._ID} ASC LIMIT $QUERY_BATCH_LIMIT"
            )?.use { cursor ->
                val rows = mutableListOf<OutboundSmsRow>()
                while (cursor.moveToNext()) {
                    rows += OutboundSmsRow(
                        id = cursor.getLong(0),
                        address = cursor.getString(1).orEmpty(),
                        body = cursor.getString(2).orEmpty(),
                        type = cursor.getInt(3),
                        date = cursor.getLong(4)
                    )
                }
                rows
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Query failed", e)
            emptyList()
        }
    }

    /**
     * Redaktion für den (exportierten) LogBuffer: Fake-`+888…`-Adressen sind keine
     * PII und bleiben zur Diagnose sichtbar; echte Rufnummern werden maskiert, damit
     * sie nicht über [io.github.lycheeappf.tmm.core.util.DiagnosticsExporter] lecken.
     */
    private fun addrForLog(address: String): String =
        if (FakeAddress.isFakeAddress(address)) address else maskReal(address)

    private fun maskReal(address: String): String {
        val digits = address.filter { it.isDigit() }
        return if (digits.length >= 4) "•••${digits.takeLast(4)}" else "•••"
    }

    companion object {
        private const val TAG = "OutboundSmsObserver"
        // Lookback-Fenster damit Status-Updates derselben Row (OUTBOX → SENT/FAILED)
        // erkannt werden — die _ID bleibt gleich, also brauchen wir >= statt >
        // und einen kleinen Window für gleichzeitige Inserts.
        private const val LOOKBACK_WINDOW = 50L
        private const val DISPATCH_CACHE_SIZE = 200
        private const val QUERY_BATCH_LIMIT = 100
        private const val NOTIF_ID_CARRIER_WARNING = 2002
    }
}
