package io.github.lycheeappf.tmm.domain.channel

import io.github.lycheeappf.tmm.core.model.ChannelId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-Memory-Registry der aktiven Channels. Wird in [ReplyDispatcher] genutzt, um
 * eine Fake-Adresse → konkrete [MessagingChannel]-Instanz zu resolven.
 *
 * Hilt-Modul `ChannelModule` (Phase 3) registriert die konkreten Channel-Implementations.
 */
@Singleton
class ChannelRegistry @Inject constructor(
    private val channels: Set<@JvmSuppressWildcards MessagingChannel>
) {
    private val byId: Map<ChannelId, MessagingChannel> = channels.associateBy { it.id }

    fun get(id: ChannelId): MessagingChannel? = byId[id]

    fun all(): Collection<MessagingChannel> = byId.values

    fun isRegistered(id: ChannelId): Boolean = byId.containsKey(id)
}
