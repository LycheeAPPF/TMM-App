package io.github.lycheeappf.tmm.channel.system

import android.util.Log
import io.github.lycheeappf.tmm.core.model.ChannelId
import io.github.lycheeappf.tmm.domain.channel.ChannelMapping
import io.github.lycheeappf.tmm.domain.channel.MessagingChannel
import io.github.lycheeappf.tmm.domain.reply.ReplyResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * System-Channel für interne Tests und Diagnostics. Wird im Onboarding für den
 * Pre-Flight-Test (Carrier-Reject-Verifikation) verwendet.
 *
 * Alle Tesla-Antworten an SystemChannel-Adressen werden geloggt, aber ignoriert.
 */
@Singleton
class SystemChannel @Inject constructor() : MessagingChannel {
    override val id: ChannelId = ChannelId.SYSTEM
    override val displayName: String = "System"

    override suspend fun handleTeslaReply(
        mapping: ChannelMapping,
        replyText: String
    ): ReplyResult {
        Log.i(TAG, "SystemChannel reply received: addr=${mapping.fakeAddress} text=$replyText")
        return ReplyResult.Success
    }

    companion object {
        private const val TAG = "SystemChannel"
    }
}
