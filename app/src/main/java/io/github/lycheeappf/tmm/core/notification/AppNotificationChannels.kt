package io.github.lycheeappf.tmm.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lycheeappf.tmm.MfsApplication
import io.github.lycheeappf.tmm.R
import io.github.lycheeappf.tmm.core.locale.localizedString
import javax.inject.Inject
import javax.inject.Singleton

/**
 * (Re-)erstellt die App-eigenen Notification-Channels mit in der aktiven App-Locale
 * aufgelösten Namen/Beschreibungen.
 *
 * Idempotent: ein erneutes `createNotificationChannel` mit gleicher ID aktualisiert
 * nur Name + Beschreibung. Das System cacht die Channel-Namen ab dem ersten Anlegen,
 * daher muss [ensure] nach einem Sprachwechsel erneut aufgerufen werden (siehe
 * `SettingsViewModel.setLanguage`), zusätzlich zum Aufruf in `MfsApplication.onCreate`.
 */
@Singleton
class AppNotificationChannels @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun ensure() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(
                    MfsApplication.CHANNEL_STATUS,
                    context.localizedString(R.string.notif_channel_status_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = context.localizedString(R.string.notif_channel_status_desc) },
                NotificationChannel(
                    MfsApplication.CHANNEL_FALLBACK,
                    context.localizedString(R.string.notif_channel_fallback_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = context.localizedString(R.string.notif_channel_fallback_desc) },
                NotificationChannel(
                    MfsApplication.CHANNEL_DIAGNOSTIC,
                    context.localizedString(R.string.notif_channel_diagnostic_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = context.localizedString(R.string.notif_channel_diagnostic_desc) }
            )
        )
    }
}
