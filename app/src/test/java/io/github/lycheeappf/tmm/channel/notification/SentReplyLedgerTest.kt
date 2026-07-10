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

    @Test
    fun `hash-colliding different body is not treated as recent reply`() {
        // "Aa" und "BB" haben denselben Java String.hashCode() (2112). Ein
        // Hash-Keying würde hier eine LEGITIME eingehende Nachricht droppen —
        // gleiche Begründung wie beim lastBodies-Dedup in NotificationCapture.
        assertThat("Aa".hashCode()).isEqualTo("BB".hashCode())

        ledger.record("com.whatsapp", "Aa")
        assertThat(ledger.isRecentReply("com.whatsapp", "BB")).isFalse()
    }

    @Test
    fun `oldest entry is evicted once max size is exceeded`() {
        ledger.record("com.whatsapp", "reply-0")
        repeat(50) { i -> ledger.record("com.whatsapp", "reply-${i + 1}") }

        assertThat(ledger.isRecentReply("com.whatsapp", "reply-0")).isFalse()
        assertThat(ledger.isRecentReply("com.whatsapp", "reply-50")).isTrue()
    }
}
