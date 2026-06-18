package io.github.lycheeappf.tmm.sms.default_app

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import io.github.lycheeappf.tmm.MfsApplication
import io.github.lycheeappf.tmm.R
import io.github.lycheeappf.tmm.core.locale.localizedString

/**
 * Required für Default-SMS-App-Role: respondiert auf RESPOND_VIA_MESSAGE
 * (z.B. Quick-Reply vom Telefon-UI beim abgewiesenen Anruf).
 *
 * Wir senden SMS nicht selbst (das ist Aufgabe von Google Messages parallel),
 * postet aber eine Notification, dass der Quick-Reply nicht zugestellt wurde,
 * damit der User die SMS manuell senden kann.
 */
class HeadlessSmsSendService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val data = intent?.data
        Log.i(TAG, "RESPOND_VIA_MESSAGE intent received: $data")

        val recipient = extractRecipient(data)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent?.getStringExtra("sms_body")
            ?: intent?.getStringExtra(Intent.EXTRA_SUBJECT)

        postQuickReplyFailedNotification(recipient, text)
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun extractRecipient(data: Uri?): String? {
        if (data == null) return null
        return when (data.scheme?.lowercase()) {
            "sms", "smsto", "mms", "mmsto" -> data.schemeSpecificPart
            else -> null
        }
    }

    private fun postQuickReplyFailedNotification(recipient: String?, text: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w(TAG, "POST_NOTIFICATIONS denied — Quick-Reply silent dropped")
                return
            }
        }

        val nm = getSystemService<NotificationManager>() ?: return

        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            data = if (recipient != null) Uri.parse("smsto:$recipient") else Uri.parse("smsto:")
            setPackage(GOOGLE_MESSAGES_PKG)
            if (text != null) putExtra("sms_body", text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            this,
            recipient?.hashCode() ?: 0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(this, MfsApplication.CHANNEL_FALLBACK)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(localizedString(R.string.quickreply_failed_title))
            .setContentText(
                recipient?.let { localizedString(R.string.quickreply_failed_text_to, it) }
                    ?: localizedString(R.string.quickreply_failed_text_generic)
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    localizedString(R.string.quickreply_failed_big, text ?: "")
                )
            )
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            nm.notify(NOTIF_ID, notif)
        } catch (e: SecurityException) {
            Log.e(TAG, "notify failed", e)
        }
    }

    companion object {
        private const val TAG = "HeadlessSmsSendService"
        private const val GOOGLE_MESSAGES_PKG = "com.google.android.apps.messaging"
        private const val NOTIF_ID = 4002
    }
}
