package io.github.lycheeappf.tmm.listener.filter

import android.service.notification.StatusBarNotification
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Beeper-spezifische Anreicherung: extrahiert Bridge-Info (welcher Dienst:
 * WhatsApp/Signal/Telegram/iMessage etc.) aus den Notification-Extras.
 *
 * Beeper-Notification-Format ist nicht stabil dokumentiert – wir versuchen es,
 * fallen aber graceful zurück.
 */
@Singleton
class BeeperExtractor @Inject constructor() {

    fun extractBridgeHint(sbn: StatusBarNotification): String? {
        if (sbn.packageName !in BEEPER_PACKAGES) return null
        val extras = sbn.notification.extras ?: return null

        // Beeper exponiert sub-text mit dem Bridge-Namen, z.B. "WhatsApp"
        val subText = extras.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)?.toString()
        if (!subText.isNullOrBlank() && subText.length < 32) {
            return subText
        }

        // Conversation-Title kann das Format "Bridge — Chat" haben
        val convTitle = extras.getCharSequence(android.app.Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        if (convTitle != null && convTitle.contains(" — ")) {
            return convTitle.substringBefore(" — ").takeIf { it.length < 32 }
        }
        return null
    }

    companion object {
        val BEEPER_PACKAGES = setOf(
            "com.beeper.android",
            "com.beeper.inc.android"
        )
    }
}
