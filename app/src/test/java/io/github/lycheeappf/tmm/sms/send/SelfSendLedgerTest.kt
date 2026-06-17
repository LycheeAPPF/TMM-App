package io.github.lycheeappf.tmm.sms.send

import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.core.util.Clock
import org.junit.Test
import java.util.concurrent.TimeUnit

class SelfSendLedgerTest {

    private var nowMs = 1_000L
    private val clock = Clock { nowMs }
    private val ledger = SelfSendLedger(clock)

    @Test
    fun `address and body match within TTL`() {
        ledger.markSelfSend("+4915123456789", "Hallo")
        assertThat(ledger.isSelfSend("+4915123456789", "Hallo")).isTrue()
    }

    @Test
    fun `different body does not match`() {
        ledger.markSelfSend("+4915123456789", "Hallo")
        assertThat(ledger.isSelfSend("+4915123456789", "Tschüss")).isFalse()
    }

    @Test
    fun `different address does not match`() {
        ledger.markSelfSend("+4915123456789", "Hallo")
        assertThat(ledger.isSelfSend("+4915000000000", "Hallo")).isFalse()
    }

    @Test
    fun `rowId match`() {
        ledger.markRowId(4242L)
        assertThat(ledger.isSelfSend(4242L)).isTrue()
        assertThat(ledger.isSelfSend(9999L)).isFalse()
    }

    @Test
    fun `entries expire after TTL`() {
        ledger.markSelfSend("+4915123456789", "Hallo")
        ledger.markRowId(4242L)
        nowMs += TimeUnit.SECONDS.toMillis(31)
        assertThat(ledger.isSelfSend("+4915123456789", "Hallo")).isFalse()
        assertThat(ledger.isSelfSend(4242L)).isFalse()
    }

    @Test
    fun `address match tolerates bracket form and 00 prefix`() {
        // markSelfSend mit reiner Nummer; Observer sieht ggf. Bracket-/00-Form.
        ledger.markSelfSend("+4915123456789", "Hallo")
        assertThat(ledger.isSelfSend("Anna <+4915123456789>", "Hallo")).isTrue()
        assertThat(ledger.isSelfSend("004915123456789", "Hallo")).isTrue()
    }

    @Test
    fun `entry just before TTL boundary still matches`() {
        ledger.markSelfSend("+4915123456789", "Hallo")
        nowMs += TimeUnit.SECONDS.toMillis(29)
        assertThat(ledger.isSelfSend("+4915123456789", "Hallo")).isTrue()
    }
}
