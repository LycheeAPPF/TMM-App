package io.github.lycheeappf.tmm.data.db

import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.domain.channel.ChannelPayload
import org.junit.Test

class PayloadJsonTest {

    @Test
    fun `notification payload round-trips`() {
        val original = ChannelPayload.Notification(
            sourcePackage = "com.beeper.android",
            notificationKey = "0|com.beeper.android|42|null|10042",
            remoteInputResultKey = "input_text",
            conversationLabel = "WhatsApp – Anna",
            senderDisplayName = "Anna",
            bridgeHint = "WhatsApp"
        )
        val encoded = PayloadJson.encode(original)
        val decoded = PayloadJson.decode(encoded)
        assertThat(decoded).isEqualTo(original)
    }

    /**
     * V2 schlankerer Llm-Payload: keine History mehr (in-memory in
     * [io.github.lycheeappf.tmm.channel.llm.LlmConversationStore]), nur
     * Channel-Metadaten.
     */
    @Test
    fun `llm payload round-trips with V2 schema`() {
        val original = ChannelPayload.Llm(
            providerId = "grok",
            assistantDisplayName = "Grok",
            conversationKey = "default-assistant"
        )
        val encoded = PayloadJson.encode(original)
        val decoded = PayloadJson.decode(encoded)
        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `system payload round-trips`() {
        val original = ChannelPayload.System(reason = "preflight-test")
        val decoded = PayloadJson.decode(PayloadJson.encode(original))
        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `polymorphic round-trip preserves subtype`() {
        val payload: ChannelPayload = ChannelPayload.Notification(
            sourcePackage = "x",
            notificationKey = "y",
            remoteInputResultKey = null,
            conversationLabel = "lbl",
            senderDisplayName = "snd"
        )
        val decoded = PayloadJson.decode(PayloadJson.encode(payload))
        assertThat(decoded).isInstanceOf(ChannelPayload.Notification::class.java)
    }
}
