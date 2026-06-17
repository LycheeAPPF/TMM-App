package io.github.lycheeappf.tmm.channel.llm

import io.github.lycheeappf.tmm.channel.llm.provider.LlmProviderError
import io.github.lycheeappf.tmm.core.model.ChannelId
import io.github.lycheeappf.tmm.core.security.ApiKeyStore
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.core.util.SendBudget
import io.github.lycheeappf.tmm.data.store.AssistantPreferencesStore
import io.github.lycheeappf.tmm.domain.channel.ChannelMapping
import io.github.lycheeappf.tmm.domain.channel.ChannelPayload
import io.github.lycheeappf.tmm.domain.channel.MessagingChannel
import io.github.lycheeappf.tmm.domain.reply.ReplyResult
import io.github.lycheeappf.tmm.sms.provider.SmsContentProviderWriter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `MessagingChannel`-Implementation für Grok. Wird vom [ReplyDispatcher]
 * aufgerufen, wenn das Tesla einen Reply an die Grok-Adresse `+888 1 …`
 * (z.B. `+88810000000`) schickt.
 *
 *  - [handleTeslaReply] delegiert sofort an den [LlmTurnRunner], damit die
 *    komplette Turn-Logik (Mutex, TTL, Rate-Limit, Provider-Call, History-
 *    Append) in einem einzigen Pfad lebt und nicht zwischen Channel und
 *    Service auseinanderdriftet.
 *  - [maybeInjectFollowUp] schreibt die Grok-Antwort als neue Inbox-SMS in
 *    den Provider — Tesla TTS spricht sie. Fehlertexte werden gleichbehandelt;
 *    der User hört dann "Entschuldige …" über die Auto-Lautsprecher.
 */
@Singleton
class LlmChannel @Inject constructor(
    private val turnRunner: LlmTurnRunner,
    private val smsWriter: SmsContentProviderWriter,
    private val sendBudget: SendBudget,
    private val prefs: AssistantPreferencesStore,
    private val apiKeyStore: ApiKeyStore,
    private val logBuffer: LogBuffer
) : MessagingChannel {

    override val id: ChannelId = ChannelId.LLM
    override val displayName: String = "AI Assistant (Grok)"

    override suspend fun handleTeslaReply(
        mapping: ChannelMapping,
        replyText: String
    ): ReplyResult {
        if (mapping.payload !is ChannelPayload.Llm) return ReplyResult.PayloadMismatch
        if (replyText.isBlank()) {
            logBuffer.info(TAG, "Blank Tesla-reply für LLM-Channel — ignoring")
            return ReplyResult.Ignored
        }
        // Consent + API-Key zur TURN-Zeit prüfen. Der Reply-/Auto-Pfad (Tesla schickt
        // eine SMS an die Grok-Adresse) läuft NICHT über [LlmStarter], der diese
        // Checks macht — und das statische Grok-Mapping ist nicht-ablaufend. Ohne
        // diese Schranke könnte ein zurückgezogener Consent (bei noch gesetztem Key)
        // via gecachtem Tesla-Kontakt weiter einen echten xAI-Turn auslösen.
        if (!prefs.isPrivacyConsentGiven() || apiKeyStore.read().isNullOrBlank()) {
            logBuffer.warn(TAG, "Tesla-reply für LLM, aber Assistent inaktiv (Consent/Key) — Turn abgelehnt")
            return ReplyResult.ProviderError(
                "Der Assistent ist nicht aktiv. Bitte Einwilligung und API-Key in der App bestätigen."
            )
        }
        return when (val outcome = turnRunner.run(mapping.mappingId, replyText)) {
            is LlmTurnRunner.TurnResult.Success ->
                ReplyResult.FollowUp(outcome.assistantText)
            is LlmTurnRunner.TurnResult.RateLimited ->
                ReplyResult.ProviderError(outcome.message)
            is LlmTurnRunner.TurnResult.ProviderFailed ->
                ReplyResult.ProviderError(outcome.error.userFacingMessage)
            LlmTurnRunner.TurnResult.EmptyResponse ->
                ReplyResult.ProviderError("Grok hat keine Antwort gesendet.")
        }
    }

    override suspend fun maybeInjectFollowUp(
        mapping: ChannelMapping,
        replyText: String,
        result: ReplyResult
    ) {
        val payload = mapping.payload as? ChannelPayload.Llm ?: return
        val body = when (result) {
            is ReplyResult.FollowUp -> result.body
            is ReplyResult.ProviderError -> errorReply(result.message)
            ReplyResult.Expired -> "Entschuldige — die Konversation ist abgelaufen."
            // Sealed-Class-Vollständigkeit; alle anderen ReplyResult-Typen sind für LLM nicht relevant.
            ReplyResult.Ignored,
            ReplyResult.PayloadMismatch,
            ReplyResult.Success,
            ReplyResult.NoActionAvailable,
            ReplyResult.PendingIntentCanceled,
            ReplyResult.NoRemoteInput -> return
        }
        if (body.isBlank()) return

        if (!sendBudget.checkAndIncrement()) {
            logBuffer.warn(TAG, "SendBudget exceeded — LLM-Follow-up verworfen")
            return
        }
        // Idempotenter Rollback-Flag — verhindert doppelten Rollback wenn z.B.
        // sowohl `uri == null` als auch eine Exception oben zusammenkämen.
        val rolledBack = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            val uri = smsWriter.injectIncoming(
                fakeAddress = mapping.fakeAddress,
                body = body,
                displayName = payload.assistantDisplayName
            )
            if (uri == null) {
                logBuffer.warn(TAG, "Inject-Follow-up returned null für ${mapping.fakeAddress}")
                if (rolledBack.compareAndSet(false, true)) sendBudget.rollback()
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (rolledBack.compareAndSet(false, true)) sendBudget.rollback()
            throw e
        }
    }

    private fun errorReply(detail: String): String =
        "Entschuldige, ich kann das gerade nicht beantworten — $detail"

    companion object {
        private const val TAG = "LlmChannel"
    }
}
