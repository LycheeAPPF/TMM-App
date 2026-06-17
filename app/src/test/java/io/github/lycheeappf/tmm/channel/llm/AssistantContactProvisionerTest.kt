package io.github.lycheeappf.tmm.channel.llm

import io.github.lycheeappf.tmm.contact.ContactSyncWriter
import io.github.lycheeappf.tmm.core.model.ChannelId
import io.github.lycheeappf.tmm.core.security.ApiKeyStore
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.data.store.AssistantPreferencesStore
import io.github.lycheeappf.tmm.domain.channel.ChannelMapping
import io.github.lycheeappf.tmm.domain.channel.ChannelPayload
import io.github.lycheeappf.tmm.domain.repository.MappingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Gate-Logik des statischen Grok-Auto-Kontakts: er existiert genau dann, wenn der
 * Assistent einsatzbereit ist (Datenschutz-Einwilligung gegeben UND API-Key gesetzt).
 */
class AssistantContactProvisionerTest {

    private val mappingRepository = mockk<MappingRepository>(relaxed = true)
    private val contactSyncWriter = mockk<ContactSyncWriter>(relaxed = true)
    private val prefs = mockk<AssistantPreferencesStore>(relaxed = true)
    private val apiKeyStore = mockk<ApiKeyStore>(relaxed = true)
    private val logBuffer = mockk<LogBuffer>(relaxed = true)
    private val provisioner = AssistantContactProvisioner(
        mappingRepository, contactSyncWriter, prefs, apiKeyStore, logBuffer
    )

    private val staticMapping = ChannelMapping(
        mappingId = 0L,
        channel = ChannelId.LLM,
        fakeAddress = "+88810000000",
        conversationKey = "default-assistant",
        payload = ChannelPayload.Llm(),
        createdAt = 0L,
        expiresAt = Long.MAX_VALUE,
        lastUsedAt = null,
        replyCount = 0,
        replyable = true
    )

    @Test
    fun `reconcile provisions contact when consent given and api key set`() = runTest {
        coEvery { prefs.isPrivacyConsentGiven() } returns true
        coEvery { apiKeyStore.read() } returns "xai-key"
        coEvery { prefs.assistantDisplayName() } returns "Grok"
        coEvery { mappingRepository.ensureStaticAssistantMapping("Grok") } returns staticMapping

        provisioner.reconcile()

        coVerify { mappingRepository.ensureStaticAssistantMapping("Grok") }
        coVerify { contactSyncWriter.upsertContact("+88810000000", "Grok") }
        // Beide phonetischen Sprach-Aliasse werden mitgeführt.
        coVerify { contactSyncWriter.upsertContact("+88810000001", "Grog") }
        coVerify { contactSyncWriter.upsertContact("+88810000002", "Grogg") }
        coVerify(exactly = 0) { contactSyncWriter.deleteContact(any()) }
    }

    @Test
    fun `reconcile removes contact when api key missing`() = runTest {
        coEvery { prefs.isPrivacyConsentGiven() } returns true
        coEvery { apiKeyStore.read() } returns null

        provisioner.reconcile()

        coVerify { contactSyncWriter.deleteContact("+88810000000") }
        coVerify { contactSyncWriter.deleteContact("+88810000001") }
        coVerify { contactSyncWriter.deleteContact("+88810000002") }
        coVerify(exactly = 0) { mappingRepository.ensureStaticAssistantMapping(any()) }
        coVerify(exactly = 0) { contactSyncWriter.upsertContact(any(), any()) }
    }

    @Test
    fun `reconcile removes contact when consent withdrawn`() = runTest {
        coEvery { prefs.isPrivacyConsentGiven() } returns false
        coEvery { apiKeyStore.read() } returns "xai-key"

        provisioner.reconcile()

        coVerify { contactSyncWriter.deleteContact("+88810000000") }
        coVerify { contactSyncWriter.deleteContact("+88810000001") }
        coVerify { contactSyncWriter.deleteContact("+88810000002") }
        coVerify(exactly = 0) { mappingRepository.ensureStaticAssistantMapping(any()) }
    }
}
