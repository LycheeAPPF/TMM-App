package io.github.lycheeappf.tmm.work

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.getSystemService
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.lycheeappf.tmm.MainActivity
import io.github.lycheeappf.tmm.MfsApplication
import io.github.lycheeappf.tmm.platform.permission.PermissionGate
import io.github.lycheeappf.tmm.platform.role.DefaultSmsRoleManager
import java.util.concurrent.TimeUnit

/**
 * Health-Check Worker (4h Intervall):
 * 1. Prüft, ob die App noch Default SMS App ist
 * 2. Prüft, ob NotificationListener-Access noch gewährt ist
 *
 * Bei Problem: postet eine persistent Notification mit Direct-Settings-Link.
 */
@HiltWorker
class HealthCheckWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val roleManager: DefaultSmsRoleManager,
    private val permissionGate: PermissionGate
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val nm = context.getSystemService<NotificationManager>() ?: return Result.success()

        if (!roleManager.isDefault()) {
            postIssue(nm, NOTIF_ID_ROLE,
                title = "Default SMS App nicht gesetzt",
                body = "Tap zum Setzen — sonst werden keine Nachrichten ans Tesla weitergeleitet",
                settingsAction = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            )
        } else {
            nm.cancel(NOTIF_ID_ROLE)
        }

        if (!permissionGate.hasNotificationListenerAccess()) {
            postIssue(nm, NOTIF_ID_NLS,
                title = "Notification Access fehlt",
                body = "Tap um Notification-Listener zu aktivieren",
                settingsAction = permissionGate.openNotificationListenerSettings()
            )
        } else {
            nm.cancel(NOTIF_ID_NLS)
        }

        // Contact-Permission ist nur informativ — ohne sie zeigt Tesla die
        // Fake-Nummer statt Klartextname, die Bridge funktioniert ansonsten.
        // Daher posten wir nur einen Hinweis, keinen Alarm.
        if (!permissionGate.hasContactsAccess()) {
            postIssue(nm, NOTIF_ID_CONTACTS,
                title = "Tesla zeigt nur Nummern",
                body = "Contact-Berechtigung fehlt — Tap zum Erteilen",
                settingsAction = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                }
            )
        } else {
            nm.cancel(NOTIF_ID_CONTACTS)
        }

        return Result.success()
    }

    private fun postIssue(
        nm: NotificationManager,
        notifId: Int,
        title: String,
        body: String,
        settingsAction: Intent
    ) {
        // Tap → direkt die problemspezifische Settings-Seite öffnen.
        // (Vorher: hat MainActivity geöffnet, was nicht zielführend war.)
        val launchIntent = settingsAction.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntentCompat.getActivity(
            context, notifId,
            launchIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            false
        )
        val notif = NotificationCompat.Builder(context, MfsApplication.CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        try {
            nm.notify(notifId, notif)
        } catch (e: SecurityException) {
            android.util.Log.e("HealthCheckWorker", "notify failed", e)
        }
    }

    companion object {
        const val NAME = "MfsHealthCheckWorker"
        val INTERVAL_HOURS = 4L
        val INTERVAL_MS: Long = TimeUnit.HOURS.toMillis(INTERVAL_HOURS)
        private const val NOTIF_ID_ROLE = 1001
        private const val NOTIF_ID_NLS = 1002
        private const val NOTIF_ID_CONTACTS = 1003
    }
}
