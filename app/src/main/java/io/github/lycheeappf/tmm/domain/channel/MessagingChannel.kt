package io.github.lycheeappf.tmm.domain.channel

import io.github.lycheeappf.tmm.core.model.ChannelId
import io.github.lycheeappf.tmm.domain.reply.ReplyResult

/**
 * Abstrakter Channel zwischen einer Tesla-MAP-Konversation und einem Reply-Ziel.
 *
 * V1: NotificationChannel routet zu RemoteInput der Original-App.
 * V2 (geplant): LlmChannel routet zum LLM-Provider und injiziert die Antwort zurück.
 */
interface MessagingChannel {
    val id: ChannelId
    val displayName: String

    /**
     * Verarbeitet eine Antwort, die das Tesla via MAP an die [mapping]-Adresse
     * geschickt hat. Implementations sind selbst dafür verantwortlich, ggf.
     * fehlerhafte Zustellung an den User zu kommunizieren (Fallback-Notification).
     */
    suspend fun handleTeslaReply(mapping: ChannelMapping, replyText: String): ReplyResult

    /**
     * Optionale "outbound → inbound" Loop: erlaubt einem Channel, nach erfolgreicher
     * Verarbeitung eine eigene Nachricht als neue Tesla-sichtbare SMS zu injizieren
     * (z.B. LlmChannel injiziert die LLM-Antwort, damit Tesla sie vorliest).
     *
     * V1-NotificationChannel macht das nicht – die Messaging-App bestätigt selbst.
     */
    suspend fun maybeInjectFollowUp(
        mapping: ChannelMapping,
        replyText: String,
        result: ReplyResult
    ) {
        // Default: no-op
    }
}
