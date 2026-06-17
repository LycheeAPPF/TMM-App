package io.github.lycheeappf.tmm.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FakeAddressTest {

    @Test
    fun `toE164 formats notification channel address (default +888)`() {
        val addr = FakeAddress(ChannelId.NOTIFICATION, 42).toE164()
        assertThat(addr).isEqualTo("+88800000042")
        assertThat(addr).hasLength(12)
    }

    @Test
    fun `toE164 formats LLM channel address (default +888)`() {
        val addr = FakeAddress(ChannelId.LLM, 7).toE164()
        assertThat(addr).isEqualTo("+88810000007")
    }

    @Test
    fun `reserved Grok address +88810000000 round-trips to (LLM, 0)`() {
        val reserved = FakeAddress(ChannelId.LLM, 0L)
        assertThat(reserved.toE164()).isEqualTo("+88810000000")
        assertThat(FakeAddress.parse("+88810000000")).isEqualTo(reserved)
        // Single source of truth für den statischen Grok-Auto-Kontakt.
        assertThat(io.github.lycheeappf.tmm.domain.channel.AssistantIdentity.STATIC_FAKE_ADDRESS)
            .isEqualTo("+88810000000")
        // Zusätzlicher Sprach-Ansprech-Kontakt (Id 1).
        assertThat(io.github.lycheeappf.tmm.domain.channel.AssistantIdentity.VOICE_ALIAS_FAKE_ADDRESS)
            .isEqualTo("+88810000001")
        // Reserviert: Grok-Id (0) + Sprach-Alias-Id (1).
        assertThat(io.github.lycheeappf.tmm.domain.channel.AssistantIdentity.RESERVED_MAPPING_IDS)
            .containsExactly(0L, 1L)
    }

    @Test
    fun `toE164 formats system channel address (default +888)`() {
        val addr = FakeAddress(ChannelId.SYSTEM, 1).toE164()
        assertThat(addr).isEqualTo("+88890000001")
    }

    @Test
    fun `parse round-trips +888 default-scheme address`() {
        assertThat(FakeAddress.parse("+88800000042"))
            .isEqualTo(FakeAddress(ChannelId.NOTIFICATION, 42))
        assertThat(FakeAddress.parse("+88810000007"))
            .isEqualTo(FakeAddress(ChannelId.LLM, 7))
        assertThat(FakeAddress.parse("+88890000001"))
            .isEqualTo(FakeAddress(ChannelId.SYSTEM, 1))
    }

    @Test
    fun `parse handles whitespace and dashes`() {
        val parsed = FakeAddress.parse("+888 0 000 0042")
        assertThat(parsed).isEqualTo(FakeAddress(ChannelId.NOTIFICATION, 42))
    }

    @Test
    fun `parse returns null for non-fake address`() {
        assertThat(FakeAddress.parse("+4915123456789")).isNull()
        assertThat(FakeAddress.parse("+12025550100")).isNull()
        assertThat(FakeAddress.parse("123")).isNull()
        assertThat(FakeAddress.parse("")).isNull()
    }

    @Test
    fun `parse returns null for unknown channel code`() {
        // Channel code 5 ist nicht in [ChannelId] registriert
        assertThat(FakeAddress.parse("+88850000042")).isNull()
    }

    @Test
    fun `parse returns null for wrong prefix`() {
        assertThat(FakeAddress.parse("+88700000042")).isNull()  // +887 statt +888
        assertThat(FakeAddress.parse("+4915123456789")).isNull()
    }

    @Test
    fun `parse returns null for wrong total length`() {
        assertThat(FakeAddress.parse("+8880000042")).isNull()    // 11 chars (too short)
        assertThat(FakeAddress.parse("+888000000042")).isNull()  // 13 chars (too long)
    }

    @Test
    fun `isFakeAddress is consistent with parse`() {
        assertThat(FakeAddress.isFakeAddress("+88800000042")).isTrue()
        assertThat(FakeAddress.isFakeAddress("+4915123456789")).isFalse()
    }

    @Test
    fun `constructor rejects mappingId out of range`() {
        assertThat(runCatching { FakeAddress(ChannelId.NOTIFICATION, -1) }.isFailure).isTrue()
        assertThat(runCatching { FakeAddress(ChannelId.NOTIFICATION, 10_000_000L) }.isFailure).isTrue()
    }

    @Test
    fun `parse extracts embedded number from display-name combined format`() {
        // Bracket-Address-Format: Tesla zeigt 'Grok' als Sender, weil das im
        // Display-Teil steht, Reply-Routing klappt via dem in spitzen Klammern
        // eingebetteten +888...-Token.
        val parsed = FakeAddress.parse("Grok <+88810000007>")
        assertThat(parsed).isEqualTo(FakeAddress(ChannelId.LLM, 7))
    }

    @Test
    fun `parse handles 00-prefix combined format`() {
        // Manche MAP-Stacks geben Adressen als 00-Prefix zurück
        val parsed = FakeAddress.parse("Grok <0088810000007>")
        assertThat(parsed).isEqualTo(FakeAddress(ChannelId.LLM, 7))
    }
}
