package io.github.lycheeappf.tmm.domain.routing

import android.util.Log
import io.github.lycheeappf.tmm.core.model.ChannelId
import io.github.lycheeappf.tmm.domain.channel.ChannelRegistry
import io.github.lycheeappf.tmm.domain.reply.ReplyResult
import io.github.lycheeappf.tmm.domain.repository.MappingRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Zentrale Routing-Logik für Tesla-Antworten. Wird vom OutboundSmsObserver
 * angesprochen, sobald eine unserer Adressen in der Outbox erscheint.
 *
 * Der Caller hat bereits via [OutboundSmsClassifier] entschieden, dass die
 * Row zu uns gehört, und liefert `mappingId` + `channelCode` direkt — der
 * Dispatcher muss die ADDRESS-Form nicht mehr parsen.
 *
 * Liest das [ChannelMapping] aus dem Repository, resolved den Channel und
 * delegiert an dessen [MessagingChannel.handleTeslaReply].
 */
@Singleton
class ReplyDispatcher @Inject constructor(
    private val registry: ChannelRegistry,
    private val mappingRepository: MappingRepository
) {

    /**
     * @return ReplyResult oder null wenn der Channel-Code unbekannt ist.
     */
    suspend fun dispatch(mappingId: Long, channelCode: Int, replyText: String): ReplyResult? {
        val channelId = ChannelId.fromCode(channelCode)
        if (channelId == null) {
            Log.w(TAG, "Unknown channel code $channelCode for mappingId $mappingId")
            return null
        }

        val mapping = mappingRepository.findById(mappingId, channelId)
        if (mapping == null) {
            Log.w(TAG, "Mapping not found for mappingId $mappingId channel $channelId")
            return ReplyResult.Expired
        }

        if (mapping.expiresAt < System.currentTimeMillis()) {
            Log.w(TAG, "Mapping ${mapping.mappingId} expired at ${mapping.expiresAt}, dropping reply")
            return ReplyResult.Expired
        }

        val channel = registry.get(channelId)
        if (channel == null) {
            Log.w(TAG, "No channel registered for $channelId")
            return ReplyResult.ProviderError("Channel not registered: $channelId")
        }

        val result = channel.handleTeslaReply(mapping, replyText)
        // Statistik aktualisieren: replyCount, lastUsedAt — auch für FollowUp,
        // damit der Diagnostics-Screen LLM-Antworten zählt.
        if (result.isSuccess) {
            try {
                mappingRepository.recordReplyAttempt(mapping.mappingId, mapping.channel)
            } catch (e: Exception) {
                // CancellationException muss durch — sonst bricht structured concurrency.
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "recordReplyAttempt failed", e)
            }
        }
        // Bei Ignored macht ein Follow-up keinen Sinn — Channel hat den Reply
        // bewusst verworfen (z.B. Blank-Diktat).
        if (result !is ReplyResult.Ignored) {
            try {
                channel.maybeInjectFollowUp(mapping, replyText, result)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Follow-up injection failed", e)
            }
        }
        return result
    }

    companion object {
        private const val TAG = "ReplyDispatcher"
    }
}
