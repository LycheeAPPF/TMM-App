package io.github.lycheeappf.tmm.domain.channel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Channel-spezifische Daten, die mit einem [ChannelMapping] persistiert werden.
 * Wird via kotlinx.serialization polymorph zu JSON serialisiert in der Room-Datenbank.
 *
 * V1 nutzt [Notification] und [System]. [Llm] ist als Datenmodell für V2 reserviert
 * (kein V1-Code dahinter).
 */
@Serializable
sealed class ChannelPayload {

    /** Channel-agnostisches Replyable-Flag (Mapping kann reply triggern).
     *  Nicht serialisiert: berechnete Property, kein backing field. */
    abstract val isReplyable: Boolean

    @Serializable
    @SerialName("notification")
    data class Notification(
        val sourcePackage: String,
        val notificationKey: String,
        val remoteInputResultKey: String?,
        val conversationLabel: String,
        val senderDisplayName: String,
        val bridgeHint: String? = null
    ) : ChannelPayload() {
        override val isReplyable: Boolean get() = remoteInputResultKey != null
    }

    /**
     * LLM-Channel Mapping-Payload. Bewusst schlank — Conversation-History und
     * Modell-/Prompt-Settings sind ausgelagert:
     *  - History lebt In-Memory in [io.github.lycheeappf.tmm.channel.llm.LlmConversationStore]
     *    (Single Source of Truth, kein Persistenz-Drift).
     *  - Modell, System-Prompt, Welcome, Temperature, TTL etc. liegen in
     *    [io.github.lycheeappf.tmm.data.store.AssistantPreferencesStore].
     *
     * Migration: alte Payloads mit `systemPrompt`, `conversationHistory`,
     * `modelHint`, `lastInteractionAt` werden vom JSON-Decoder dank
     * `ignoreUnknownKeys = true` (siehe `PayloadJson`) gracefully ignoriert.
     */
    @Serializable
    @SerialName("llm")
    data class Llm(
        val providerId: String = "grok",
        val assistantDisplayName: String = "Grok",
        val conversationKey: String = "default-assistant"
    ) : ChannelPayload() {
        // LLM-Channel ist immer replyable (User-Diktat triggert Provider-Call)
        override val isReplyable: Boolean get() = true
    }

    @Serializable
    @SerialName("system")
    data class System(val reason: String) : ChannelPayload() {
        override val isReplyable: Boolean get() = false
    }
}
