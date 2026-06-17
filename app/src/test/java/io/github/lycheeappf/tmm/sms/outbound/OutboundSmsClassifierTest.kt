package io.github.lycheeappf.tmm.sms.outbound

import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.core.model.ChannelId
import io.github.lycheeappf.tmm.domain.channel.ChannelMapping
import io.github.lycheeappf.tmm.domain.channel.ChannelPayload
import io.github.lycheeappf.tmm.domain.repository.MappingRepository
import kotlinx.coroutines.runBlocking
import org.junit.Test

class OutboundSmsClassifierTest {

    private val repo = FakeMappingRepository()
    private val classifier = OutboundSmsClassifier(repo)

    @Test
    fun `display-only address from new mapping routes via DB lookup`() = runBlocking {
        repo.put(
            mapping(
                mappingId = 7L,
                channel = ChannelId.LLM,
                fakeAddress = "Grok"
            )
        )
        val result = classifier.classify(row(address = "Grok"))
        assertThat(result).isEqualTo(
            OutboundSmsClassifier.Classification.TeslaReply(
                mappingId = 7L,
                channelCode = ChannelId.LLM.code
            )
        )
    }

    @Test
    fun `display address with conflict suffix routes via DB lookup`() = runBlocking {
        repo.put(
            mapping(
                mappingId = 11L,
                channel = ChannelId.NOTIFICATION,
                fakeAddress = "Anna #2"
            )
        )
        val result = classifier.classify(row(address = "Anna #2"))
        assertThat(result).isEqualTo(
            OutboundSmsClassifier.Classification.TeslaReply(
                mappingId = 11L,
                channelCode = ChannelId.NOTIFICATION.code
            )
        )
    }

    @Test
    fun `legacy fake-address falls through to FakeAddress-parse`() = runBlocking {
        // Bestehende Mappings vor der Migration haben "+9994200000042" als
        // fakeAddress. Der DB-Lookup matched die Outbox-Address direkt,
        // selbst ohne den FakeAddress.parse-Fallback.
        repo.put(
            mapping(
                mappingId = 42L,
                channel = ChannelId.NOTIFICATION,
                fakeAddress = "+9994200000042"
            )
        )
        val result = classifier.classify(row(address = "+9994200000042"))
        assertThat(result).isEqualTo(
            OutboundSmsClassifier.Classification.TeslaReply(
                mappingId = 42L,
                channelCode = ChannelId.NOTIFICATION.code
            )
        )
    }

    @Test
    fun `legacy fake-address without DB row falls back to FakeAddress-parse`() = runBlocking {
        // DB ist leer, aber das Schema matched — wird trotzdem als Tesla-Reply
        // klassifiziert (ggf. Mapping inzwischen via TTL gelöscht; Dispatcher
        // returnt dann Expired). Wichtig: Adresse darf nicht NotOurs werden,
        // sonst dispatchen wir nie und der Cleanup räumt nicht auf.
        val result = classifier.classify(row(address = "+9994210000007"))
        assertThat(result).isEqualTo(
            OutboundSmsClassifier.Classification.TeslaReply(
                mappingId = 7L,
                channelCode = ChannelId.LLM.code
            )
        )
    }

    // --- Phonetische Sprach-Aliasse („Grog"/„Grogg") → kanonische Grok-Session (id 0) ---

    @Test
    fun `alias address Grog (+88810000001) redirects to canonical Grok id 0`() = runBlocking {
        // Bewusst KEIN Mapping im Repo — der Redirect darf nicht von der DB abhängen.
        val result = classifier.classify(row(address = "+88810000001"))
        assertThat(result).isEqualTo(
            OutboundSmsClassifier.Classification.TeslaReply(
                mappingId = 0L,
                channelCode = ChannelId.LLM.code
            )
        )
    }

    @Test
    fun `alias address Grogg (+88810000002) redirects to canonical Grok id 0`() = runBlocking {
        val result = classifier.classify(row(address = "+88810000002"))
        assertThat(result).isEqualTo(
            OutboundSmsClassifier.Classification.TeslaReply(
                mappingId = 0L,
                channelCode = ChannelId.LLM.code
            )
        )
    }

    @Test
    fun `alias bracket form redirects to canonical Grok id 0`() = runBlocking {
        val result = classifier.classify(row(address = "Grogg <+88810000002>"))
        assertThat(result).isEqualTo(
            OutboundSmsClassifier.Classification.TeslaReply(
                mappingId = 0L,
                channelCode = ChannelId.LLM.code
            )
        )
    }

    @Test
    fun `alias 00-prefix form redirects to canonical Grok id 0`() = runBlocking {
        val result = classifier.classify(row(address = "0088810000001"))
        assertThat(result).isEqualTo(
            OutboundSmsClassifier.Classification.TeslaReply(
                mappingId = 0L,
                channelCode = ChannelId.LLM.code
            )
        )
    }

    @Test
    fun `non-alias LLM address routes by its own id, not redirected`() = runBlocking {
        // +88810000009 ist KEIN Alias (id 9) → über FakeAddress.parse als (LLM, 9).
        val result = classifier.classify(row(address = "+88810000009"))
        assertThat(result).isEqualTo(
            OutboundSmsClassifier.Classification.TeslaReply(
                mappingId = 9L,
                channelCode = ChannelId.LLM.code
            )
        )
    }

    @Test
    fun `notification address is not treated as alias`() = runBlocking {
        // +88800000001 ist Channel-Digit 0 (NOTIFICATION, id 1) — kein Alias.
        val result = classifier.classify(row(address = "+88800000001"))
        assertThat(result).isEqualTo(
            OutboundSmsClassifier.Classification.TeslaReply(
                mappingId = 1L,
                channelCode = ChannelId.NOTIFICATION.code
            )
        )
    }

    @Test
    fun `unknown plain address classified as not ours`() = runBlocking {
        val result = classifier.classify(row(address = "+4915123456789"))
        assertThat(result).isEqualTo(OutboundSmsClassifier.Classification.NotOurs)
    }

    @Test
    fun `empty address classified as not ours`() = runBlocking {
        val result = classifier.classify(row(address = ""))
        assertThat(result).isEqualTo(OutboundSmsClassifier.Classification.NotOurs)
    }

    private fun row(
        id: Long = 1L,
        address: String,
        body: String = "test",
        type: Int = 4,
        date: Long = 0L
    ) = OutboundSmsRow(id, address, body, type, date)

    private fun mapping(
        mappingId: Long,
        channel: ChannelId,
        fakeAddress: String
    ) = ChannelMapping(
        mappingId = mappingId,
        channel = channel,
        fakeAddress = fakeAddress,
        conversationKey = "conv_$mappingId",
        payload = ChannelPayload.System("test"),
        createdAt = 0L,
        expiresAt = Long.MAX_VALUE,
        lastUsedAt = null,
        replyCount = 0,
        replyable = true
    )

    private class FakeMappingRepository : MappingRepository {
        private val byAddress = mutableMapOf<String, ChannelMapping>()

        fun put(mapping: ChannelMapping) {
            byAddress[mapping.fakeAddress] = mapping
        }

        override suspend fun findById(mappingId: Long, channel: ChannelId): ChannelMapping? =
            byAddress.values.firstOrNull { it.mappingId == mappingId && it.channel == channel }

        override suspend fun findByConversationKey(
            channel: ChannelId,
            conversationKey: String
        ): ChannelMapping? = byAddress.values.firstOrNull {
            it.channel == channel && it.conversationKey == conversationKey
        }

        override suspend fun findByFakeAddress(fakeAddress: String): ChannelMapping? =
            byAddress[fakeAddress]

        override suspend fun allocateOrReuse(
            channel: ChannelId,
            conversationKey: String,
            payload: ChannelPayload,
            ttlMillis: Long
        ): ChannelMapping = throw NotImplementedError()

        override suspend fun ensureStaticAssistantMapping(displayName: String): ChannelMapping =
            throw NotImplementedError()

        override suspend fun sweepStaleAssistantMappings(): Int = 0

        override suspend fun recordReplyAttempt(mappingId: Long, channel: ChannelId) {}
        override suspend fun deleteExpired(now: Long) {}
        override suspend fun delete(mappingId: Long, channel: ChannelId) {}
        override suspend fun deleteAll() {}
        override suspend fun allMappings(): List<ChannelMapping> = byAddress.values.toList()
    }
}
