package io.github.lycheeappf.tmm.listener.filter

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extrahiert Sender, Body und einen stabilen Conversation-Key aus einer Notification.
 *
 * Reihenfolge der Strategien:
 * 1. MessagingStyle (NotificationCompat.MessagingStyle) — modern, robust
 * 2. extras.EXTRA_TITLE + EXTRA_TEXT — Fallback für ältere Notifications
 * 3. extras.EXTRA_BIG_TEXT — weitere Fallbacks
 *
 * Conversation-Key-Strategie (mit Fallback-Kette):
 * 1. Notification.shortcutId (Android 11+ Conversation-Shortcuts) — am stabilsten
 * 2. Notification.locusId (Android 11+) — auch stabil
 * 3. MessagingStyle.user/last-person.key — stable wenn von App vergeben
 * 4. bridgeHint + senderName + conversationTitle — Beeper-spezifischer Fallback
 * 5. packageName + label-hash — letzter Fallback
 *
 * **WICHTIG:** Verschiedene Chats in derselben App MÜSSEN verschiedene Keys
 * produzieren. Display-Text allein ist NICHT ausreichend, weil Display-Namen
 * über Bridges hinweg kollidieren können (z.B. "Anna" in WhatsApp und Signal
 * via Beeper). Daher fließt bridgeHint und Person-Key explizit ein.
 */
@Singleton
class MessagingStyleExtractor @Inject constructor(
    private val beeperExtractor: BeeperExtractor
) {

    fun extract(sbn: StatusBarNotification): ExtractedMessage? {
        val bridgeHint = beeperExtractor.extractBridgeHint(sbn)
        return extractFromNotification(
            packageName = sbn.packageName,
            n = sbn.notification,
            bridgeHint = bridgeHint,
            shortcutId = sbn.notification?.shortcutId,
            locusId = sbn.notification?.locusId?.id
        )
    }

    fun extractFromNotification(
        packageName: String,
        n: Notification?,
        bridgeHint: String? = null,
        shortcutId: String? = null,
        locusId: String? = null
    ): ExtractedMessage? {
        n ?: return null
        val extras = n.extras ?: Bundle.EMPTY

        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style != null && style.messages.isNotEmpty()) {
            val last = style.messages.last()
            val senderName = last.person?.name?.toString()
                ?: style.user.name?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: "Unknown"
            val body = last.text?.toString() ?: ""
            val conversationLabel = style.conversationTitle?.toString() ?: senderName
            // Gruppen-Detection: primär MessagingStyle.isGroupConversation, fallback
            // auf vorhandenen conversationTitle. Heuristik "senderName != conversationLabel"
            // ist zu fragil (Codex-Review).
            val isGroup = style.isGroupConversation ||
                !style.conversationTitle.isNullOrBlank()
            return ExtractedMessage(
                senderName = senderName,
                body = body,
                conversationLabel = conversationLabel,
                isGroup = isGroup,
                conversationKey = buildConversationKey(
                    packageName = packageName,
                    shortcutId = shortcutId,
                    locusId = locusId,
                    bridgeHint = bridgeHint,
                    personKey = last.person?.key ?: style.user.key,
                    conversationTitle = style.conversationTitle?.toString(),
                    senderName = senderName
                )
            )
        }

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: return null

        if (title.isEmpty() && text.isEmpty()) return null

        return ExtractedMessage(
            senderName = title.ifEmpty { "Unknown" },
            body = text,
            conversationLabel = title.ifEmpty { "Conversation" },
            isGroup = false,
            conversationKey = buildConversationKey(
                packageName = packageName,
                shortcutId = shortcutId,
                locusId = locusId,
                bridgeHint = bridgeHint,
                personKey = null,
                conversationTitle = title.ifEmpty { null },
                senderName = title.ifEmpty { text.take(32) }
            )
        )
    }

    /**
     * Baut einen stabilen Conversation-Key. Bevorzugt structured IDs der App
     * (shortcutId, locusId, Person.key); falls die fehlen, nutzt es eine
     * deterministische Mischung aus bridge + sender + title.
     */
    private fun buildConversationKey(
        packageName: String,
        shortcutId: String?,
        locusId: String?,
        bridgeHint: String?,
        personKey: String?,
        conversationTitle: String?,
        senderName: String
    ): String {
        val structured = shortcutId
            ?: locusId
            ?: personKey
        if (!structured.isNullOrBlank()) {
            // bridgeHint mitschleifen: Beeper bringt mehrere Messenger unter
            // demselben Package zusammen, und shortcutId/locusId aus zwei
            // verschiedenen Bridges (z.B. WhatsApp+Signal) können den selben
            // String tragen. Ohne Bridge-Diskriminator landen beide auf demselben
            // ChannelMapping und damit derselben fakeAddress.
            val bridgeSegment = bridgeHint?.lowercase()?.trim().orEmpty()
            return "$packageName::id::$bridgeSegment::${structured.take(96)}"
        }
        // Fallback: kombiniert bridgeHint + conversationTitle. senderName fließt
        // NUR ein wenn conversationTitle fehlt — sonst würde jeder Sprecher in
        // einer Gruppen-Chat-Notification eine eigene fakeAddress bekommen
        // (= Gruppe splittet in N Threads).
        val labelInput = if (conversationTitle.isNullOrBlank()) {
            listOf(bridgeHint.orEmpty(), senderName)
                .joinToString("|") { it.lowercase().trim() }
        } else {
            listOf(bridgeHint.orEmpty(), conversationTitle)
                .joinToString("|") { it.lowercase().trim() }
        }
        val labelHash = sha1(labelInput).take(16)
        return "$packageName::lbl::$labelHash"
    }

    private fun sha1(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

data class ExtractedMessage(
    val senderName: String,
    val body: String,
    val conversationLabel: String,
    val isGroup: Boolean,
    val conversationKey: String
)
