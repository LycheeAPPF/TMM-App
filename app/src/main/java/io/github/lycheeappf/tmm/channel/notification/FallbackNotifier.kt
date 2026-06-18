package io.github.lycheeappf.tmm.channel.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lycheeappf.tmm.MfsApplication
import io.github.lycheeappf.tmm.R
import io.github.lycheeappf.tmm.core.locale.localizedString
import io.github.lycheeappf.tmm.domain.channel.ChannelPayload
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Postet eine eigene Notification, wenn ein Tesla-Reply nicht an die Original-App
 * zugestellt werden konnte (z.B. PendingIntent canceled). Bietet "Tap to Copy"
 * für den unzugestellten Text.
 */
@Singleton
class FallbackNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val nm: NotificationManager? = context.getSystemService()

    fun post(payload: ChannelPayload.Notification, text: String) {
        val id = generateId(payload.notificationKey)
        val copyIntent = PendingIntent.getBroadcast(
            context,
            id,
            Intent(ACTION_COPY_TO_CLIPBOARD).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_TEXT, text)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(context, MfsApplication.CHANNEL_FALLBACK)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.localizedString(R.string.notif_fallback_title, payload.conversationLabel))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(copyIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        // Try/catch — wenn POST_NOTIFICATIONS entzogen, soll der Fallback nicht
        // selbst crashen (sonst gehen Replies still verloren).
        try {
            nm?.notify(id, notif)
        } catch (e: SecurityException) {
            android.util.Log.e(
                "FallbackNotifier",
                "POST_NOTIFICATIONS denied — lost reply for ${payload.conversationLabel}: $text",
                e
            )
        } catch (e: Exception) {
            android.util.Log.e("FallbackNotifier", "notify() failed", e)
        }
    }

    private fun generateId(key: String): Int = key.hashCode() and Int.MAX_VALUE

    companion object {
        const val ACTION_COPY_TO_CLIPBOARD = "io.github.lycheeappf.tmm.COPY_TO_CLIPBOARD"
        const val EXTRA_TEXT = "text"

        /**
         * Helper für FallbackCopyReceiver (Phase 5): kopiert Text in Clipboard.
         */
        fun copyToClipboard(context: Context, text: String) {
            val cm = context.getSystemService<ClipboardManager>() ?: return
            cm.setPrimaryClip(ClipData.newPlainText("Tesla Reply", text))
        }
    }
}
