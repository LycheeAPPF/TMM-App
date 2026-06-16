package io.github.lycheeappf.tmm.domain.channel

import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.data.db.PayloadJson
import org.junit.Test

/**
 * Verifiziert, dass die V2-`Llm`-Payload das alte V0-Schema (mit
 * conversationHistory, systemPrompt, modelHint, lastInteractionAt) abwärts-
 * kompatibel dekodiert. `ignoreUnknownKeys = true` in [PayloadJson.format]
 * macht die alten Felder zu No-Ops.
 */
class ChannelPayloadLlmSerializationTest {

    @Test fun `legacy json without new fields decodes with defaults`() {
        val legacy = """{"kind":"llm","providerId":"grok"}"""
        val decoded = PayloadJson.decode(legacy)
        assertThat(decoded).isInstanceOf(ChannelPayload.Llm::class.java)
        val llm = decoded as ChannelPayload.Llm
        assertThat(llm.providerId).isEqualTo("grok")
        assertThat(llm.assistantDisplayName).isEqualTo("Grok")
        assertThat(llm.conversationKey).isEqualTo("default-assistant")
    }

    @Test fun `legacy json with conversation history and prompt is gracefully ignored`() {
        val legacy = """
            {
              "kind":"llm",
              "providerId":"grok",
              "systemPrompt":"alte Prompt",
              "conversationHistory":[
                {"role":"user","content":"foo","timestamp":1},
                {"role":"assistant","content":"bar","timestamp":2}
              ],
              "modelHint":"grok-3",
              "lastInteractionAt":1234567890
            }
        """.trimIndent()
        val decoded = PayloadJson.decode(legacy)
        assertThat(decoded).isInstanceOf(ChannelPayload.Llm::class.java)
        // History und Prompt landen im Bit-Bucket
        val llm = decoded as ChannelPayload.Llm
        assertThat(llm.providerId).isEqualTo("grok")
    }

    @Test fun `round-trip new schema`() {
        val original = ChannelPayload.Llm(
            providerId = "grok",
            assistantDisplayName = "MeinGrok",
            conversationKey = "key-42"
        )
        val encoded = PayloadJson.encode(original)
        val decoded = PayloadJson.decode(encoded)
        assertThat(decoded).isEqualTo(original)
    }
}
