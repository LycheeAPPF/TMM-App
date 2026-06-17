package io.github.lycheeappf.tmm.sms.send

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lycheeappf.tmm.core.di.IoDispatcher
import io.github.lycheeappf.tmm.core.model.FakeAddress
import io.github.lycheeappf.tmm.core.util.Clock
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.domain.sms.SmsSendResult
import io.github.lycheeappf.tmm.domain.sms.SmsSender
import io.github.lycheeappf.tmm.platform.role.DefaultSmsRoleManager
import io.github.lycheeappf.tmm.sms.provider.ThreadIdResolver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Versendet echte SMS und schreibt – als Standard-SMS-App – die OUTBOX-Row selbst.
 *
 * Ablauf (insert-first):
 *  1. Guards (Default-App, SEND_SMS, Telefonie, leer, **Fake-Adresse abgelehnt**).
 *  2. [SelfSendLedger.markSelfSend] VOR Insert, OUTBOX-Row schreiben, [SelfSendLedger.markRowId] NACH Insert.
 *  3. `SmsManager.send(Multipart)TextMessage` mit JE EINEM sent-`PendingIntent` pro Part.
 *  4. Ein selbst-deregistrierender Receiver setzt die Row auf SENT (alle Parts ok) bzw. FAILED.
 *
 * Der [OutboundSmsObserver] erkennt diese Rows über den [SelfSendLedger] und lässt
 * sie unangetastet (kein Dispatch/Löschen). Sende-Status wird über die Row-`TYPE`-
 * Transition sichtbar (der Thread aktualisiert sich via ContentObserver).
 */
@Singleton
class RealSmsSender @Inject constructor(
    @ApplicationContext private val context: Context,
    private val roleManager: DefaultSmsRoleManager,
    private val threadIdResolver: ThreadIdResolver,
    private val selfSendLedger: SelfSendLedger,
    private val logBuffer: LogBuffer,
    private val clock: Clock,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : SmsSender {

    private val nonce = AtomicLong(0)

    override suspend fun send(address: String, body: String): SmsSendResult = withContext(ioDispatcher) {
        val recipient = address.trim()
        when {
            !roleManager.isDefault() -> return@withContext SmsSendResult.Rejected.NOT_DEFAULT_SMS_APP
            !hasSendPermission() -> return@withContext SmsSendResult.Rejected.MISSING_SEND_PERMISSION
            !hasTelephony() -> return@withContext SmsSendResult.Rejected.NO_TELEPHONY
            recipient.isBlank() -> return@withContext SmsSendResult.Rejected.BLANK_RECIPIENT
            body.isBlank() -> return@withContext SmsSendResult.Rejected.BLANK_BODY
            // KRITISCH: niemals in den +888-Fake-Raum senden (Carrier-Kosten/Routing),
            // und eine echte Nummer darf nie mit einer Fake-Adresse verwechselt werden.
            FakeAddress.isFakeAddress(recipient) -> return@withContext SmsSendResult.Rejected.FAKE_ADDRESS_REFUSED
        }

        // Insert-first: Ledger VOR dem Insert (deckt Observer-Race), rowId danach.
        selfSendLedger.markSelfSend(recipient, body)
        val rowId = writeOutboxRow(recipient, body)
        rowId?.let { selfSendLedger.markRowId(it) }

        val sent = transmit(recipient, body, rowId)
        if (!sent) {
            rowId?.let { updateRowType(it, Telephony.Sms.MESSAGE_TYPE_FAILED) }
            return@withContext SmsSendResult.Error("SmsManager send failed")
        }
        // Nur Metadaten loggen — niemals Adresse/Body (CLAUDE.md / DiagnosticsExporter).
        logBuffer.info(TAG, "SMS enqueued (${body.length} chars)")
        SmsSendResult.Enqueued
    }

    private fun hasSendPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

    private fun hasTelephony(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    /** Schreibt die OUTBOX-Row und liefert ihre rowId (oder null bei Fehler). */
    private fun writeOutboxRow(address: String, body: String): Long? {
        val threadId = runCatching { threadIdResolver.getOrCreate(address) }.getOrDefault(-1L)
        val now = clock.now()
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, now)
            put(Telephony.Sms.DATE_SENT, now)
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
            if (threadId > 0) put(Telephony.Sms.THREAD_ID, threadId)
        }
        return try {
            context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
                ?.let { ContentUris.parseId(it) }
        } catch (e: Exception) {
            Log.e(TAG, "writeOutboxRow insert failed", e)
            null
        }
    }

    private fun updateRowType(rowId: Long, type: Int) {
        runCatching {
            val uri = ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, rowId)
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(Telephony.Sms.TYPE, type) },
                null, null
            )
        }.onFailure { Log.w(TAG, "updateRowType($rowId,$type) failed", it) }
    }

    private fun transmit(address: String, body: String, rowId: Long?): Boolean {
        return try {
            val sms = context.getSystemService(SmsManager::class.java) ?: return false
            val parts = sms.divideMessage(body)
            val action = "$ACTION_PREFIX${clock.now()}_${nonce.incrementAndGet()}"
            registerResultReceiver(action, parts.size, rowId)
            if (parts.size <= 1) {
                sms.sendTextMessage(address, null, body, makeSentIntent(action, 0), null)
            } else {
                val sentIntents = ArrayList<PendingIntent>(parts.size)
                for (i in parts.indices) sentIntents.add(makeSentIntent(action, i))
                sms.sendMultipartTextMessage(address, null, parts, sentIntents, null)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "transmit failed", e)
            false
        }
    }

    private fun makeSentIntent(action: String, partIndex: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            partIndex,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /**
     * Registriert einen selbst-deregistrierenden Receiver, der die [rowId] auf SENT
     * (alle Parts RESULT_OK) bzw. FAILED (mind. ein Part-Fehler) setzt. Ein Handler-
     * Timeout dereg. als Leak-Schutz, falls ein sent-Intent ausbleibt.
     */
    private fun registerResultReceiver(action: String, partCount: Int, rowId: Long?) {
        val remaining = AtomicInteger(partCount.coerceAtLeast(1))
        val anyFailed = AtomicBoolean(false)
        val mainHandler = Handler(Looper.getMainLooper())

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (resultCode != Activity.RESULT_OK) anyFailed.set(true)
                if (remaining.decrementAndGet() <= 0) {
                    rowId?.let {
                        updateRowType(
                            it,
                            if (anyFailed.get()) Telephony.Sms.MESSAGE_TYPE_FAILED
                            else Telephony.Sms.MESSAGE_TYPE_SENT
                        )
                    }
                    mainHandler.removeCallbacksAndMessages(null)
                    runCatching { context.unregisterReceiver(this) }
                }
            }
        }

        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        mainHandler.postDelayed(
            { runCatching { context.unregisterReceiver(receiver) } },
            RESULT_TIMEOUT_MS
        )
    }

    companion object {
        private const val TAG = "RealSmsSender"
        private const val ACTION_PREFIX = "io.github.lycheeappf.tmm.SMS_SENT_"
        private const val RESULT_TIMEOUT_MS = 120_000L
    }
}
