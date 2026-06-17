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
 * Gate-Logik der Tesla-Kontakte: der kanonische Grok-Antwort-Kontakt (+88810000000,
 * fest „Grok") existiert genau dann, wenn der Assistent einsatzbereit ist (Consent +
 * API-Key). Der zusätzliche Sprach-Ansprech-Kontakt (+88810000001) trägt einen
 * konfigurierbaren Namen und folgt dem `voiceAliasEnabled`-Schalter.
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

    private fun ready() {
        coEvery { prefs.isPrivacyConsentGiven() } returns true
        coEvery { apiKeyStore.read() } returns "xai-key"
        coEvery { prefs.assistantDisplayName() } returns "Grok"
        coEvery { mappingRepository.ensureStaticAssistantMapping("Grok") } returns staticMapping
    }

    @Test
    fun `reconcile provisions Grok plus enabled voice alias`() = runTest {
        ready()
        coEvery { prefs.voiceAliasEnabled() } returns true
        coEvery { prefs.voiceAliasName() } returns "Elon Musk"

        provisioner.reconcile()

        // Antwort-Kontakt heißt immer „Grok".
        coVerify { contactSyncWriter.upsertContact("+88810000000", "Grok") }
        // Sprach-Ansprech-Kontakt mit konfiguriertem Namen.
        coVerify { contactSyncWriter.upsertContact("+88810000001", "Elon Musk") }
        coVerify(exactly = 0) { contactSyncWriter.deleteContact(any()) }
    }

    @Test
    fun `reconcile uses the configured voice alias name`() = runTest {
        ready()
        coEvery { prefs.voiceAliasEnabled() } returns true
        coEvery { prefs.voiceAliasName() } returns "xAI Grok"

        provisioner.reconcile()

        coVerify { contactSyncWriter.upsertContact("+88810000001", "xAI Grok") }
    }

    @Test
    fun `reconcile removes the voice alias when disabled, keeps Grok`() = runTest {
        ready()
        coEvery { prefs.voiceAliasEnabled() } returns false

        provisioner.reconcile()

        coVerify { contactSyncWriter.upsertContact("+88810000000", "Grok") }
        coVerify { contactSyncWriter.deleteContact("+88810000001") }
        coVerify(exactly = 0) { contactSyncWriter.upsertContact("+88810000001", any()) }
        coVerify(exactly = 0) { contactSyncWriter.deleteContact("+88810000000") }
    }

    @Test
    fun `reconcile removes both contacts when api key missing`() = runTest {
        coEvery { prefs.isPrivacyConsentGiven() } returns true
        coEvery { apiKeyStore.read() } returns null

        provisioner.reconcile()

        coVerify { contactSyncWriter.deleteContact("+88810000000") }
        coVerify { contactSyncWriter.deleteContact("+88810000001") }
        coVerify(exactly = 0) { mappingRepository.ensureStaticAssistantMapping(any()) }
        coVerify(exactly = 0) { contactSyncWriter.upsertContact(any(), any()) }
    }

    @Test
    fun `reconcile removes both contacts when consent withdrawn`() = runTest {
        coEvery { prefs.isPrivacyConsentGiven() } returns false
        coEvery { apiKeyStore.read() } returns "xai-key"

        provisioner.reconcile()

        coVerify { contactSyncWriter.deleteContact("+88810000000") }
        coVerify { contactSyncWriter.deleteContact("+88810000001") }
        coVerify(exactly = 0) { mappingRepository.ensureStaticAssistantMapping(any()) }
    }
}
