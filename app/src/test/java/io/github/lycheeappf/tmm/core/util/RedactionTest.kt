package io.github.lycheeappf.tmm.core.util

import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.data.db.PayloadJson
import io.github.lycheeappf.tmm.domain.channel.ChannelPayload
import org.junit.Test

class RedactionTest {

    @Test fun `maskName hides content but keeps length`() {
        assertThat(Redaction.maskName("Anna")).isEqualTo("•••(len=4)")
    }

    @Test fun `maskName keeps empty empty`() {
        assertThat(Redaction.maskName("")).isEqualTo("")
    }

    @Test fun `hashKey is stable and prefixed`() {
        val a = Redaction.hashKey("jid-12345")
        val b = Redaction.hashKey("jid-12345")
        assertThat(a).isEqualTo(b)
        assertThat(a).startsWith("sha1:")
        assertThat(a).doesNotContain("jid-12345")
    }

    @Test fun `redactConversationKey keeps schema prefix and hashes the id`() {
        val redacted = Redaction.redactConversationKey("com.whatsapp::id::49170someJid")
        assertThat(redacted).startsWith("com.whatsapp::id::sha1:")
        assertThat(redacted).doesNotContain("49170someJid")
    }

    @Test fun `redactConversationKey hashes unknown format wholesale`() {
        val redacted = Redaction.redactConversationKey("weird-key")
        assertThat(redacted).startsWith("sha1:")
        assertThat(redacted).doesNotContain("weird-key")
    }

    @Test fun `redactPayloadJson masks notification names`() {
        val json = PayloadJson.encode(
            ChannelPayload.Notification(
                sourcePackage = "com.whatsapp",
                notificationKey = "0|com.whatsapp|1|null|1",
                remoteInputResultKey = "k",
                conversationLabel = "Anna",
                senderDisplayName = "Bob"
            )
        )
        val redacted = Redaction.redactPayloadJson(json)
        assertThat(redacted).doesNotContain("Anna")
        assertThat(redacted).doesNotContain("Bob")
        assertThat(redacted).contains("com.whatsapp") // Paket + Key bleiben
        assertThat(redacted).contains("•••(len=4)")   // "Anna"
        assertThat(redacted).contains("•••(len=3)")   // "Bob"
    }

    @Test fun `redactPayloadJson keeps llm assistant name`() {
        val json = PayloadJson.encode(ChannelPayload.Llm(assistantDisplayName = "Grok"))
        val redacted = Redaction.redactPayloadJson(json)
        assertThat(redacted).contains("Grok")
    }
}
