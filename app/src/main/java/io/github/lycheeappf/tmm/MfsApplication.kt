package io.github.lycheeappf.tmm

import android.app.Application
import android.app.LocaleManager
import android.os.LocaleList
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import io.github.lycheeappf.tmm.channel.llm.AssistantContactProvisioner
import io.github.lycheeappf.tmm.core.notification.AppNotificationChannels
import io.github.lycheeappf.tmm.core.util.coRunCatching
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
    @Inject lateinit var contactProvisioner: AssistantContactProvisioner
    @Inject lateinit var notificationChannels: AppNotificationChannels

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        seedDefaultLanguageOnce()
        notificationChannels.ensure()
        outboundSmsObserver.register()
        // Statischen Grok-Auto-Kontakt bei jedem Prozessstart abgleichen — so kann das
        // Auto Grok auch ohne geöffnete App ansprechen (NLS-Bind, Boot, eingehende SMS
        // beleben den Prozess). Idempotent, im Hintergrund; ohne Consent/Key ein No-op.
        appScope.launch { coRunCatching { contactProvisioner.reconcile() } }
    }

    /**
     * Sprachwechsel (per-app locale, API 33+) ist eine Configuration-Änderung und feuert
     * hier für BEIDE Wege: den In-App-Umschalter UND den OS-Picker
     * (Einstellungen ▸ Apps ▸ TMM ▸ Sprache). Das System cacht Channel-Namen ab dem
     * ersten Anlegen, daher hier neu auflösen — sonst blieben sie bis zum nächsten
     * Kaltstart in der alten Sprache (der In-App-Umschalter ruft [AppNotificationChannels.ensure]
     * zusätzlich direkt auf; [ensure] ist idempotent).
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        notificationChannels.ensure()
    }

    override fun onTerminate() {
        // Hinweis: onTerminate() läuft auf echten Geräten praktisch nie.
        // Wir machen trotzdem best-effort shutdown für robuste Tests / Emulator.
        outboundSmsObserver.shutdown()
        super.onTerminate()
    }

    /**
     * Einmaliger Sprach-Seed: die App war bisher ausschließlich deutsch. Bei der
     * Erstinstallation setzen wir daher "de" als App-Locale, damit Bestands- und
     * Neunutzer ihr gewohntes Deutsch behalten (Default-Resources sind Englisch).
     *
     * Läuft VOR der ersten Activity und VOR [createAppNotificationChannels] →
     * kein Recreate-Flash, korrekte (deutsche) Channel-Namen beim ersten Start.
     * Das synchrone SharedPreferences-Flag vermeidet ein `runBlocking` auf dem
     * Cold-Start-Pfad. Danach wird das Locale nie wieder automatisch verändert —
     * die spätere Toggle-/OS-Wahl des Users bleibt unangetastet.
     */
    private fun seedDefaultLanguageOnce() {
        val prefs = getSharedPreferences(PREFS_LOCALE, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_LANGUAGE_SEEDED, false)) return
        getSystemService(LocaleManager::class.java)?.applicationLocales =
            LocaleList.forLanguageTags("de")
        prefs.edit().putBoolean(KEY_LANGUAGE_SEEDED, true).apply()
    }

    companion object {
        const val CHANNEL_STATUS = "status"
        const val CHANNEL_FALLBACK = "fallback"
        const val CHANNEL_DIAGNOSTIC = "diagnostic"

        private const val PREFS_LOCALE = "mfs_locale"
        private const val KEY_LANGUAGE_SEEDED = "language_seeded"
    }
}
