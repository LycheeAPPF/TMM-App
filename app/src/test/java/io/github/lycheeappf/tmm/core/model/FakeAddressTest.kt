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
    fun `toE164 formats system channel address (default +888)`() {
        val addr = FakeAddress(ChannelId.SYSTEM, 1).toE164()
        assertThat(addr).isEqualTo("+88890000001")
    }

    @Test
    fun `parse round-trips notification address`() {
        val parsed = FakeAddress.parse("+9994200000042")
        assertThat(parsed).isEqualTo(FakeAddress(ChannelId.NOTIFICATION, 42))
    }

    @Test
    fun `parse round-trips LLM address`() {
        val parsed = FakeAddress.parse("+9994210000007")
        assertThat(parsed).isEqualTo(FakeAddress(ChannelId.LLM, 7))
    }

    @Test
    fun `parse handles whitespace and dashes`() {
        val parsed = FakeAddress.parse("+999 42 0 000 0042")
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
        assertThat(FakeAddress.parse("+9994250000042")).isNull()
    }

    @Test
    fun `parse returns null for wrong prefix`() {
        assertThat(FakeAddress.parse("+9994100000042")).isNull()
        assertThat(FakeAddress.parse("+8884200000042")).isNull()
    }

    @Test
    fun `parse returns null for wrong total length`() {
        assertThat(FakeAddress.parse("+999420000042")).isNull()    // 13 chars (too short)
        assertThat(FakeAddress.parse("+99942000000042")).isNull()  // 15 chars (too long)
    }

    @Test
    fun `isFakeAddress is consistent with parse`() {
        assertThat(FakeAddress.isFakeAddress("+9994200000042")).isTrue()
        assertThat(FakeAddress.isFakeAddress("+4915123456789")).isFalse()
    }

    @Test
    fun `constructor rejects mappingId out of range`() {
        assertThat(runCatching { FakeAddress(ChannelId.NOTIFICATION, -1) }.isFailure).isTrue()
        assertThat(runCatching { FakeAddress(ChannelId.NOTIFICATION, 10_000_000L) }.isFailure).isTrue()
    }

    @Test
    fun `toE164 with DE32 scheme uses +4932 prefix`() {
        val addr = FakeAddress(ChannelId.NOTIFICATION, 42).toE164(AddressScheme.De32)
        assertThat(addr).isEqualTo("+4932" + "0" + "0000042")
        assertThat(addr).hasLength(13)
    }

    @Test
    fun `parse recognizes DE32 address`() {
        val parsed = FakeAddress.parse("+4932" + "0" + "0000042")
        assertThat(parsed).isEqualTo(FakeAddress(ChannelId.NOTIFICATION, 42))
    }

    @Test
    fun `parse recognizes DE32 LLM address`() {
        val parsed = FakeAddress.parse("+4932" + "1" + "0000007")
        assertThat(parsed).isEqualTo(FakeAddress(ChannelId.LLM, 7))
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
    fun `+888 +99942 and +4932 schemes are length-disambiguated`() {
        // Distinkte totalLengths (12 / 14 / 13) ⇒ parse() wählt das richtige Schema;
        // Legacy-+999-Contacts parsen weiter, auch nachdem der Default auf +888 kippte.
        assertThat(FakeAddress.parse("+88810000007")?.channel).isEqualTo(ChannelId.LLM)      // Itu888
        assertThat(FakeAddress.parse("+9994210000007")?.channel).isEqualTo(ChannelId.LLM)    // Itu999 (legacy)
        assertThat(FakeAddress.parse("+4932" + "1" + "0000007")?.channel).isEqualTo(ChannelId.LLM) // De32
    }

    @Test
    fun `parse extracts embedded number from display-name combined format`() {
        // Bracket-Address-Format: Tesla zeigt 'Grok' als Sender, weil das im
        // Display-Teil steht, Reply-Routing klappt via dem in spitzen Klammern
        // eingebetteten +99942...-Token.
        val parsed = FakeAddress.parse("Grok <+9994210000007>")
        assertThat(parsed).isEqualTo(FakeAddress(ChannelId.LLM, 7))
    }

    @Test
    fun `parse extracts embedded DE32 number from display-name combined format`() {
        val parsed = FakeAddress.parse("Anna · WhatsApp <+4932" + "0" + "0000042>")
        assertThat(parsed).isEqualTo(FakeAddress(ChannelId.NOTIFICATION, 42))
    }

    @Test
    fun `parse handles 00-prefix combined format`() {
        // Manche MAP-Stacks geben Adressen als 00-Prefix zurück
        val parsed = FakeAddress.parse("Grok <009994210000007>")
        assertThat(parsed).isEqualTo(FakeAddress(ChannelId.LLM, 7))
    }
}
