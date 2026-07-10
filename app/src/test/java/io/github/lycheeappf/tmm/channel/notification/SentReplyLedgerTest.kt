package io.github.lycheeappf.tmm.channel.notification

import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.core.util.Clock
import org.junit.Test

class SentReplyLedgerTest {

    private var now = 0L
    private val ledger = SentReplyLedger(Clock { now })

    @Test
    fun `recorded reply is recognized within ttl`() {
        ledger.record("com.whatsapp", "meine Antwort")
        now += 5_000
        assertThat(ledger.isRecentReply("com.whatsapp", "meine Antwort")).isTrue()
    }

    @Test
    fun `entry expires after ttl`() {
        ledger.record("com.whatsapp", "meine Antwort")
        now += 15_001
        assertThat(ledger.isRecentReply("com.whatsapp", "meine Antwort")).isFalse()
    }

    @Test
    fun `different body or package does not match`() {
        ledger.record("com.whatsapp", "meine Antwort")
        assertThat(ledger.isRecentReply("com.whatsapp", "andere Nachricht")).isFalse()
        assertThat(ledger.isRecentReply("org.telegram.messenger", "meine Antwort")).isFalse()
    }
}
