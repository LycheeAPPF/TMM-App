package io.github.lycheeappf.tmm.data.repository

import io.github.lycheeappf.tmm.data.db.ReplyHistoryDao
import io.github.lycheeappf.tmm.data.db.ReplyHistoryEntity
import io.github.lycheeappf.tmm.domain.reply.ReplyResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistiert Reply-Versuche zur späteren Sichtung im Diagnostics-Screen.
 */
@Singleton
class ReplyHistoryRecorder @Inject constructor(
    private val dao: ReplyHistoryDao
) {
    suspend fun record(
        mappingId: Long,
        channelCode: Int,
        text: String,
        result: ReplyResult
    ) {
        val (resultLabel, errorDetail) = when (result) {
            is ReplyResult.Success -> "SUCCESS" to null
            is ReplyResult.FollowUp -> "LLM_REPLY" to null
            is ReplyResult.Ignored -> "IGNORED" to null
            is ReplyResult.PayloadMismatch -> "PAYLOAD_MISMATCH" to null
            is ReplyResult.NoActionAvailable -> "NO_ACTION" to null
            is ReplyResult.PendingIntentCanceled -> "PI_CANCELED" to null
            is ReplyResult.NoRemoteInput -> "NO_REMOTE_INPUT" to null
            is ReplyResult.ProviderError -> "PROVIDER_ERROR" to result.message
            is ReplyResult.Expired -> "EXPIRED" to null
        }
        // Tesla-Diktate können in der Praxis ~50 Zeichen sein, aber 10-KB-Diktate
        // sind technisch möglich. Wir kappen auf MAX_TEXT, sonst wächst die DB
        // bei langer Nutzung unkontrolliert (TTL-Cleanup gibt es für ReplyHistory
        // nicht — nur per Hand löschen).
        val clipped = if (text.length > MAX_TEXT) text.take(MAX_TEXT - 1) + "…" else text
        dao.insert(
            ReplyHistoryEntity(
                mappingId = mappingId,
                channel = channelCode,
                text = clipped,
                attemptedAt = System.currentTimeMillis(),
                result = resultLabel,
                errorDetail = errorDetail?.take(MAX_TEXT)
            )
        )
    }

    companion object {
        // 500 chars reicht für jedes realistische Tesla-Diktat (TTS gibt eh nur
        // ~1 min vor) und hält die Row klein.
        private const val MAX_TEXT = 500
    }
}
