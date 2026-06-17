package io.github.lycheeappf.tmm.sms.read

import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.core.model.FakeAddress
import io.github.lycheeappf.tmm.domain.sms.SmsDirection
import org.junit.Test

/**
 * Pure-Tests der Reader-Hilfsfunktionen (kein Robolectric/ContentResolver nötig).
 * Telephony-TYPE-Konstanten sind Compile-Time-Ints → in plain JUnit nutzbar.
 */
class SmsInboxReaderImplTest {

    // Telephony.Sms.MESSAGE_TYPE_* Werte
    private val inbox = 1
    private val sent = 2
    private val draft = 3
    private val outbox = 4
    private val failed = 5
    private val queued = 6

    private fun row(
        id: Long,
        threadId: Long,
        address: String,
        body: String,
        date: Long,
        read: Boolean,
        type: Int
    ) = SmsInboxReaderImpl.RawSmsRow(id, threadId, address, body, date, read, type)

    @Test
    fun `toDirection maps Telephony types`() {
        assertThat(SmsInboxReaderImpl.toDirection(inbox)).isEqualTo(SmsDirection.INBOX)
        assertThat(SmsInboxReaderImpl.toDirection(sent)).isEqualTo(SmsDirection.SENT)
        assertThat(SmsInboxReaderImpl.toDirection(outbox)).isEqualTo(SmsDirection.OUTBOX)
        assertThat(SmsInboxReaderImpl.toDirection(queued)).isEqualTo(SmsDirection.OUTBOX)
        assertThat(SmsInboxReaderImpl.toDirection(failed)).isEqualTo(SmsDirection.FAILED)
        assertThat(SmsInboxReaderImpl.toDirection(draft)).isEqualTo(SmsDirection.OTHER)
        assertThat(SmsInboxReaderImpl.toDirection(99)).isEqualTo(SmsDirection.OTHER)
    }

    @Test
    fun `groupConversations groups by thread, newest snippet, counts unread`() {
        // DATE DESC sortiert (so liefert es der Reader-Query).
        val rows = listOf(
            row(5, 1, "+49111", "newest t1", 500, read = false, type = inbox),
            row(4, 2, "+49222", "newest t2", 400, read = true, type = sent),
            row(3, 1, "+49111", "older t1", 300, read = true, type = inbox)
        )
        val convs = SmsInboxReaderImpl.groupConversations(rows, isFake = { false }, limit = 10)

        assertThat(convs).hasSize(2)
        val t1 = convs.first { it.threadId == 1L }
        assertThat(t1.address).isEqualTo("+49111")
        assertThat(t1.snippet).isEqualTo("newest t1")
        assertThat(t1.date).isEqualTo(500)
        assertThat(t1.unreadCount).isEqualTo(1)
        assertThat(t1.messageCount).isEqualTo(2)
        // Nach Datum absteigend → Thread 1 (500) vor Thread 2 (400).
        assertThat(convs.map { it.threadId }).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `groupConversations excludes fake +888 threads`() {
        val rows = listOf(
            row(2, 9, "+88810000005", "fake", 200, read = false, type = inbox),
            row(1, 1, "+49111", "real", 100, read = false, type = inbox)
        )
        val convs = SmsInboxReaderImpl.groupConversations(
            rows, isFake = { FakeAddress.isFakeAddress(it) }, limit = 10
        )
        assertThat(convs.map { it.address }).containsExactly("+49111")
    }

    @Test
    fun `groupConversations skips blank addresses`() {
        val rows = listOf(
            row(2, 9, "", "no-addr", 200, read = false, type = inbox),
            row(1, 1, "+49111", "real", 100, read = false, type = inbox)
        )
        val convs = SmsInboxReaderImpl.groupConversations(rows, isFake = { false }, limit = 10)
        assertThat(convs).hasSize(1)
        assertThat(convs.first().address).isEqualTo("+49111")
    }

    @Test
    fun `groupConversations counts only unread incoming`() {
        val rows = listOf(
            row(3, 1, "+49111", "in unread", 300, read = false, type = inbox),
            row(2, 1, "+49111", "sent unread", 200, read = false, type = sent),   // ausgehend → kein unread
            row(1, 1, "+49111", "in read", 100, read = true, type = inbox)
        )
        val convs = SmsInboxReaderImpl.groupConversations(rows, isFake = { false }, limit = 10)
        assertThat(convs.single().unreadCount).isEqualTo(1)
        assertThat(convs.single().messageCount).isEqualTo(3)
    }

    @Test
    fun `groupConversations respects limit`() {
        val rows = (1..5).map { i ->
            row(i.toLong(), i.toLong(), "+4900$i", "msg$i", (600 - i).toLong(), read = true, type = inbox)
        }
        val convs = SmsInboxReaderImpl.groupConversations(rows, isFake = { false }, limit = 3)
        assertThat(convs).hasSize(3)
        // Höchstes Datum zuerst (i=1 → date 599).
        assertThat(convs.first().threadId).isEqualTo(1L)
    }
}
