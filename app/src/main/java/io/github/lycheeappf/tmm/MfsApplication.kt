package io.github.lycheeappf.tmm

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import io.github.lycheeappf.tmm.data.repository.AppPolicySeed
import io.github.lycheeappf.tmm.sms.outbound.OutboundSmsObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class MfsApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var outboundSmsObserver: OutboundSmsObserver
    @Inject lateinit var appPolicySeed: AppPolicySeed

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        createAppNotificationChannels()
        outboundSmsObserver.register()
        appScope.launch { appPolicySeed.seedIfEmpty() }
    }

    override fun onTerminate() {
        // Hinweis: onTerminate() läuft auf echten Geräten praktisch nie.
        // Wir machen trotzdem best-effort shutdown für robuste Tests / Emulator.
        outboundSmsObserver.shutdown()
        super.onTerminate()
    }

    private fun createAppNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_STATUS, getString(R.string.notif_channel_status_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = getString(R.string.notif_channel_status_desc) },
                NotificationChannel(
                    CHANNEL_FALLBACK, getString(R.string.notif_channel_fallback_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = getString(R.string.notif_channel_fallback_desc) },
                NotificationChannel(
                    CHANNEL_DIAGNOSTIC, getString(R.string.notif_channel_diagnostic_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = getString(R.string.notif_channel_diagnostic_desc) }
            )
        )
    }

    companion object {
        const val CHANNEL_STATUS = "status"
        const val CHANNEL_FALLBACK = "fallback"
        const val CHANNEL_DIAGNOSTIC = "diagnostic"
    }
}
