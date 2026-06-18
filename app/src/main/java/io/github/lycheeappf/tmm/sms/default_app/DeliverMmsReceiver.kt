package io.github.lycheeappf.tmm.sms.default_app

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import io.github.lycheeappf.tmm.MainActivity
import io.github.lycheeappf.tmm.MfsApplication
import io.github.lycheeappf.tmm.R
import io.github.lycheeappf.tmm.core.locale.localizedString

/**
 * Empfängt eingehende MMS, wenn unsere App Default-SMS-App ist.
 *
 * Wir parsen MMS-PDUs nicht selbst und können auch keinen Download via APN
 * durchführen. Weil unsere App Default ist, würde Google Messages MMS NICHT
 * mehr empfangen — der User hätte sonst keine Möglichkeit, MMS zu sehen.
 *
 * Daher: wir posten eine Warn-Notification, dass eine MMS empfangen wurde und
 * aktuell nicht angezeigt werden kann. Empfehlung: Google Messages als Default
 * setzen für MMS-Sessions.
 */
class DeliverMmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.w(TAG, "WAP_PUSH_DELIVER received — MMS-Support ist nicht implementiert")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val nm = context.getSystemService<NotificationManager>() ?: return

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(context, MfsApplication.CHANNEL_FALLBACK)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.localizedString(R.string.mms_received_title))
            .setContentText(context.localizedString(R.string.mms_received_text))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    context.localizedString(R.string.mms_received_big)
                )
            )
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

        try {
            nm.notify(NOTIF_ID, notif)
        } catch (e: SecurityException) {
            Log.e(TAG, "notify failed", e)
        }
    }

    companion object {
        private const val TAG = "DeliverMmsReceiver"
        private const val NOTIF_ID = 4001
    }
}
