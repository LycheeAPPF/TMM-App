package io.github.lycheeappf.tmm.channel.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InjectedMessageLedgerTest {

    private var nowMs = 1_000_000L
    private val ledger = InjectedMessageLedger { nowMs }

    @Test fun `mark then shouldIgnore returns true for same address and body`() {
        ledger.markInjected("+9994210000001", "Hallo Welt")
        assertThat(ledger.shouldIgnoreOutbound("+9994210000001", "Hallo Welt")).isTrue()
    }

    @Test fun `shouldIgnore is false for unknown body`() {
        ledger.markInjected("+9994210000001", "Hallo Welt")
        assertThat(ledger.shouldIgnoreOutbound("+9994210000001", "anderer Text")).isFalse()
    }

    @Test fun `shouldIgnore is false for unknown address`() {
        ledger.markInjected("+9994210000001", "Hallo Welt")
        assertThat(ledger.shouldIgnoreOutbound("+4915123456789", "Hallo Welt")).isFalse()
    }

    @Test fun `entries expire after 10 seconds`() {
        ledger.markInjected("+9994210000001", "Hallo")
        nowMs += 10_001
        assertThat(ledger.shouldIgnoreOutbound("+9994210000001", "Hallo")).isFalse()
    }

    @Test fun `entries within 10 seconds still match`() {
        ledger.markInjected("+9994210000001", "Hallo")
        nowMs += 9_500
        assertThat(ledger.shouldIgnoreOutbound("+9994210000001", "Hallo")).isTrue()
    }

    @Test fun `entry at exact 10 second boundary still matches`() {
        // evictOld nutzt `at < cutoff` (strict), Eintrag bei `at == cutoff` bleibt drin
        ledger.markInjected("+9994210000001", "Hallo")
        nowMs += 10_000
        assertThat(ledger.shouldIgnoreOutbound("+9994210000001", "Hallo")).isTrue()
    }

    @Test fun `mark with pure number matches outbound with bracket display+number`() {
        // Inject registriert die pure FakeNumber, Tesla schickt aber die
        // Bracket-ADDRESS-Form ("Grok <+999...>") zurück. Normalisierung muss
        // beide Wege strippen.
        ledger.markInjected("+9994210000007", "Welcome")
        assertThat(ledger.shouldIgnoreOutbound("Grok <+9994210000007>", "Welcome")).isTrue()
    }

    @Test fun `mark with bracket form matches outbound with pure number`() {
        ledger.markInjected("Grok <+9994210000007>", "Welcome")
        assertThat(ledger.shouldIgnoreOutbound("+9994210000007", "Welcome")).isTrue()
    }

    @Test fun `clear empties ledger`() {
        ledger.markInjected("+9994210000001", "Hallo")
        ledger.clear()
        assertThat(ledger.shouldIgnoreOutbound("+9994210000001", "Hallo")).isFalse()
    }
}
