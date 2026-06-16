package io.github.lycheeappf.tmm.sms.default_app

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import io.github.lycheeappf.tmm.MfsApplication

/**
 * Empfängt echte eingehende SMS, wenn unsere App Default-SMS-App ist.
 *
 * - Schreibt SMS via ContentResolver in `content://sms/inbox` MIT `THREAD_ID`,
 *   damit Google Messages parallel sie zur richtigen Konversation ordnet.
 * - Postet eine eigene System-Notification, weil Google Messages (als nicht-Default
 *   SMS App) seine eigenen Notifications unterdrückt — sonst verpasst der User
 *   alle SMS inkl. 2FA-Codes.
 * - Tap öffnet die SMS-Konversation in Google Messages (Fallback: generisches
 *   smsto-Intent).
 */
class DeliverSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val first = messages.first()
        val address = first.originatingAddress ?: return
        val body = messages.joinToString("") { it.messageBody.orEmpty() }
        val timestamp = first.timestampMillis
        val subId = intent.getIntExtra("subscription", -1)

        // goAsync(): SMS_DELIVER ist ein priority broadcast mit ~10 s ANR-Limit.
        // `Telephony.Threads.getOrCreateThreadId` und `contentResolver.insert`
        // sind beide Binder + Disk-IO — werden hier auf einen Worker-Thread
        // ausgelagert, sodass der Main-Thread des Receivers nicht blockiert.
        val pending = goAsync()
        Thread {
            try {
                doDeliver(context, address, body, timestamp, subId)
            } catch (e: Exception) {
                Log.e(TAG, "doDeliver crashed", e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun doDeliver(
        context: Context,
        address: String,
        body: String,
        timestamp: Long,
        subId: Int
    ) {
        val threadId = runCatching {
            Telephony.Threads.getOrCreateThreadId(context, address)
        }.getOrDefault(-1L)

        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, timestamp)
            put(Telephony.Sms.DATE_SENT, timestamp)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            if (subId >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, subId)
            if (threadId > 0) put(Telephony.Sms.THREAD_ID, threadId)
        }

        val insertedUri = try {
            context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert SMS", e)
            null
        }

        Log.d(TAG, "Inserted real SMS from $address (${body.length} chars), uri=$insertedUri")
        postIncomingSmsNotification(context, address, body)
    }

    private fun postIncomingSmsNotification(
        context: Context,
        address: String,
        body: String
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w(TAG, "POST_NOTIFICATIONS denied — kann SMS-Notification nicht posten")
                return
            }
        }

        val nm = context.getSystemService<NotificationManager>() ?: return

        val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse("smsto:$address")).apply {
            setPackage(GOOGLE_MESSAGES_PKG)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("smsto:$address")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chosenIntent =
            if (openIntent.resolveActivity(context.packageManager) != null) openIntent
            else fallbackIntent

        val pi = PendingIntent.getActivity(
            context,
            address.hashCode(),
            chosenIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(context, MfsApplication.CHANNEL_DIAGNOSTIC)
            .setSmallIcon(android.R.drawable.sym_action_email)
            .setContentTitle("SMS von $address")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

        try {
            val id = NOTIF_ID_BASE + (address.hashCode() and 0x7FFF)
            nm.notify(id, notif)
        } catch (e: SecurityException) {
            Log.e(TAG, "notify failed (POST_NOTIFICATIONS denied)", e)
        }
    }

    companion object {
        private const val TAG = "DeliverSmsReceiver"
        private const val GOOGLE_MESSAGES_PKG = "com.google.android.apps.messaging"
        private const val NOTIF_ID_BASE = 3000
    }
}
