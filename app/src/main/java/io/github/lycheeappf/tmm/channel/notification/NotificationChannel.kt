package io.github.lycheeappf.tmm.channel.notification

import io.github.lycheeappf.tmm.core.model.ChannelId
import io.github.lycheeappf.tmm.domain.channel.ChannelMapping
import io.github.lycheeappf.tmm.domain.channel.ChannelPayload
import io.github.lycheeappf.tmm.domain.channel.MessagingChannel
import io.github.lycheeappf.tmm.domain.reply.ReplyResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Channel für Tesla-Antworten, die an die Original-Notification (WhatsApp, Telegram,
 * Signal, ...) zurückgehen sollen. Triggert die RemoteInput-Action der Notification.
 *
 * Wird in Phase 3 mit [NotificationReplyExecutor] verkabelt.
 */
@Singleton
class NotificationChannel @Inject constructor(
    private val executor: NotificationReplyExecutor
) : MessagingChannel {

    override val id: ChannelId = ChannelId.NOTIFICATION
    override val displayName: String = "Notification Reply"

    override suspend fun handleTeslaReply(
        mapping: ChannelMapping,
        replyText: String
    ): ReplyResult {
        val payload = mapping.payload as? ChannelPayload.Notification
            ?: return ReplyResult.PayloadMismatch
        return executor.reply(payload, mapping.mappingId, replyText)
    }
}
