package io.github.lycheeappf.tmm.data.repository

import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.contact.ContactSyncWriter
import io.github.lycheeappf.tmm.core.model.ChannelId
import io.github.lycheeappf.tmm.data.db.MappingDao
import io.github.lycheeappf.tmm.data.db.MappingEntity
import io.github.lycheeappf.tmm.data.db.PayloadJson
import io.github.lycheeappf.tmm.data.store.SettingsStore
import io.github.lycheeappf.tmm.domain.channel.ChannelPayload
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MappingRepositoryImplTest {

    private val dao = mockk<MappingDao>(relaxed = true)
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val contactSyncWriter = mockk<ContactSyncWriter>(relaxed = true)
    private val repository = MappingRepositoryImpl(dao, settings, contactSyncWriter)

    private val testPayload = ChannelPayload.Notification(
        sourcePackage = "com.beeper.android",
        notificationKey = "key-1",
        remoteInputResultKey = "input",
        conversationLabel = "Anna",
        senderDisplayName = "Anna",
        bridgeHint = "WhatsApp"
    )

    @Test
    fun `allocateOrReuse creates new mapping with numeric fakeAddress`() = runTest {
        coEvery { dao.findByConversationKey(any(), any()) } returns null
        coEvery { settings.nextMappingId() } returns 42L
        coEvery { settings.addressScheme() } returns io.github.lycheeappf.tmm.core.model.AddressScheme.Itu999

        val inserted = slot<MappingEntity>()
        coEvery { dao.insert(capture(inserted)) } just Runs

        val mapping = repository.allocateOrReuse(
            channel = ChannelId.NOTIFICATION,
            conversationKey = "com.beeper.android::anna",
            payload = testPayload,
            ttlMillis = 24L * 60 * 60 * 1000
        )

        assertThat(mapping.mappingId).isEqualTo(42L)
        assertThat(mapping.fakeAddress).isEqualTo("+9994200000042")
        assertThat(mapping.channel).isEqualTo(ChannelId.NOTIFICATION)
        assertThat(mapping.replyable).isTrue()
        assertThat(inserted.captured.fakeAddress).isEqualTo("+9994200000042")
    }

    @Test
    fun `allocateOrReuse refreshes expiry when conversation already exists`() = runTest {
        val now = System.currentTimeMillis()
        val existing = MappingEntity(
            mappingId = 7L,
            channel = ChannelId.NOTIFICATION.code,
            fakeAddress = "+9994200000007",
            conversationKey = "com.beeper.android::anna",
            payloadJson = PayloadJson.encode(testPayload),
            createdAt = now - 60_000,
            expiresAt = now - 1000,
            lastUsedAt = null,
            replyCount = 0,
            replyable = true
        )
        coEvery {
            dao.findByConversationKey(ChannelId.NOTIFICATION.code, "com.beeper.android::anna")
        } returns existing
        coEvery { dao.refreshOnReuse(any(), any(), any(), any(), any(), any()) } just Runs

        val mapping = repository.allocateOrReuse(
            channel = ChannelId.NOTIFICATION,
            conversationKey = "com.beeper.android::anna",
            payload = testPayload,
            ttlMillis = 24L * 60 * 60 * 1000
        )

        assertThat(mapping.mappingId).isEqualTo(7L)
        assertThat(mapping.fakeAddress).isEqualTo("+9994200000007")
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun `allocateOrReuse migrates display-only legacy address back to numeric`() = runTest {
        // Aus dem Display-only-Versuch: fakeAddress = "Grok". Wir migrieren
        // zurück auf "+9994210000007", weil das den Reply-Pfad ermöglicht.
        val now = System.currentTimeMillis()
        val legacy = MappingEntity(
            mappingId = 7L,
            channel = ChannelId.LLM.code,
            fakeAddress = "Grok",
            conversationKey = "default-assistant",
            payloadJson = PayloadJson.encode(ChannelPayload.Llm(assistantDisplayName = "Grok")),
            createdAt = now - 60_000,
            expiresAt = now - 1000,
            lastUsedAt = null,
            replyCount = 0,
            replyable = true
        )
        coEvery {
            dao.findByConversationKey(ChannelId.LLM.code, "default-assistant")
        } returns legacy
        coEvery { settings.addressScheme() } returns io.github.lycheeappf.tmm.core.model.AddressScheme.Itu999
        coEvery { dao.updateFakeAddress(7L, ChannelId.LLM.code, "+9994210000007") } returns 1

        val mapping = repository.allocateOrReuse(
            channel = ChannelId.LLM,
            conversationKey = "default-assistant",
            payload = ChannelPayload.Llm(assistantDisplayName = "Grok"),
            ttlMillis = 1000L
        )

        assertThat(mapping.fakeAddress).isEqualTo("+9994210000007")
        coVerify { dao.updateFakeAddress(7L, ChannelId.LLM.code, "+9994210000007") }
    }

    @Test
    fun `allocateOrReuse leaves numeric legacy address untouched on reuse`() = runTest {
        val now = System.currentTimeMillis()
        val existing = MappingEntity(
            mappingId = 7L,
            channel = ChannelId.LLM.code,
            fakeAddress = "+9994210000007",
            conversationKey = "default-assistant",
            payloadJson = PayloadJson.encode(ChannelPayload.Llm()),
            createdAt = now - 60_000,
            expiresAt = now - 1000,
            lastUsedAt = null,
            replyCount = 0,
            replyable = true
        )
        coEvery {
            dao.findByConversationKey(ChannelId.LLM.code, "default-assistant")
        } returns existing

        val mapping = repository.allocateOrReuse(
            channel = ChannelId.LLM,
            conversationKey = "default-assistant",
            payload = ChannelPayload.Llm(),
            ttlMillis = 1000L
        )

        assertThat(mapping.fakeAddress).isEqualTo("+9994210000007")
        coVerify(exactly = 0) { dao.updateFakeAddress(any(), any(), any()) }
    }

    @Test
    fun `findById returns null when DB row is missing`() = runTest {
        coEvery { dao.findById(99L, ChannelId.NOTIFICATION.code) } returns null
        assertThat(repository.findById(99L, ChannelId.NOTIFICATION)).isNull()
    }

    @Test
    fun `findByFakeAddress delegates to DAO`() = runTest {
        val entity = MappingEntity(
            mappingId = 5L,
            channel = ChannelId.LLM.code,
            fakeAddress = "+9994210000005",
            conversationKey = "default-assistant",
            payloadJson = PayloadJson.encode(ChannelPayload.Llm()),
            createdAt = 0L,
            expiresAt = Long.MAX_VALUE,
            lastUsedAt = null,
            replyCount = 0,
            replyable = true
        )
        coEvery { dao.findByFakeAddress("+9994210000005") } returns entity

        val mapping = repository.findByFakeAddress("+9994210000005")
        assertThat(mapping).isNotNull()
        assertThat(mapping!!.mappingId).isEqualTo(5L)
    }

    @Test
    fun `recordReplyAttempt delegates to DAO with current timestamp`() = runTest {
        coEvery { dao.recordReply(any(), any(), any()) } just Runs

        repository.recordReplyAttempt(7L, ChannelId.NOTIFICATION)

        coVerify { dao.recordReply(7L, ChannelId.NOTIFICATION.code, any()) }
    }

    @Test
    fun `replyable defaults to false when notification has no remoteInputResultKey`() = runTest {
        val payloadWithoutRemoteInput = testPayload.copy(remoteInputResultKey = null)
        coEvery { dao.findByConversationKey(any(), any()) } returns null
        coEvery { settings.nextMappingId() } returns 1L
        coEvery { settings.addressScheme() } returns io.github.lycheeappf.tmm.core.model.AddressScheme.Itu999
        coEvery { dao.insert(any()) } just Runs

        val mapping = repository.allocateOrReuse(
            channel = ChannelId.NOTIFICATION,
            conversationKey = "ck",
            payload = payloadWithoutRemoteInput,
            ttlMillis = 1000L
        )

        assertThat(mapping.replyable).isFalse()
    }
}
