package io.github.lycheeappf.tmm.domain.channel

import io.github.lycheeappf.tmm.core.model.ChannelId

/**
 * Verbindung zwischen einer Tesla-sichtbaren Fake-Adresse und ihrem Channel.
 *
 * `payload` enthält Channel-spezifische Daten (Notification-Key, LLM-Provider etc.).
 */
data class ChannelMapping(
    val mappingId: Long,
    val channel: ChannelId,
    val fakeAddress: String,
    val conversationKey: String,
    val payload: ChannelPayload,
    val createdAt: Long,
    val expiresAt: Long,
    val lastUsedAt: Long?,
    val replyCount: Int,
    val replyable: Boolean
)
