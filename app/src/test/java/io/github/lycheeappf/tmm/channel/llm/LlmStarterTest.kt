package io.github.lycheeappf.tmm.channel.llm

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.core.model.ChannelId
import io.github.lycheeappf.tmm.core.security.ApiKeyStore
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.core.util.SendBudget
import io.github.lycheeappf.tmm.data.store.AssistantPreferencesStore
import io.github.lycheeappf.tmm.domain.channel.ChannelMapping
import io.github.lycheeappf.tmm.domain.channel.ChannelPayload
import io.github.lycheeappf.tmm.domain.repository.MappingRepository
import io.github.lycheeappf.tmm.platform.role.DefaultSmsRoleManager
import io.github.lycheeappf.tmm.sms.provider.SmsContentProviderWriter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * Hilfs-Uri-Mock: `Uri.parse` in unit tests gibt `null` zurück
 * (`isReturnDefaultValues = true` in [TestOptions]). Wir nutzen daher einen
 * `mockk<Uri>()` als Marker für "Insert erfolgreich".
 */
private val FAKE_OK_URI: Uri = mockk(relaxed = true)

class LlmStarterTest {

    private val mappingRepo: MappingRepository = mockk()
    private val smsWriter: SmsContentProviderWriter = mockk()
    private val prefs: AssistantPreferencesStore = mockk()
    private val sendBudget: SendBudget = mockk()
    private val store: LlmConversationStore = mockk(relaxed = true)
    private val apiKeyStore: ApiKeyStore = mockk()
    private val roleManager: DefaultSmsRoleManager = mockk()
    private val rateLimiter: LlmRateLimiter = mockk(relaxed = true)
    private val logBuffer: LogBuffer = mockk(relaxed = true)

    private lateinit var starter: LlmStarter

    @Before fun setup() {
        starter = LlmStarter(
            mappingRepo, smsWriter, prefs, sendBudget,
            store, apiKeyStore, roleManager, rateLimiter, logBuffer
        )
        coEvery { prefs.isPrivacyConsentGiven() } returns true
        every { roleManager.isDefault() } returns true
        coEvery { apiKeyStore.read() } returns "valid-key"
        coEvery { sendBudget.checkAndIncrement() } returns true
        coEvery { sendBudget.rollback() } returns Unit
        coEvery { prefs.assistantDisplayName() } returns "Grok"
        coEvery { prefs.welcomeMessage() } returns "Hi"
        coEvery { prefs.mappingTtlHours() } returns 24
        coEvery {
            mappingRepo.allocateOrReuse(any(), any(), any(), any())
        } returns ChannelMapping(
            mappingId = 1L,
            channel = ChannelId.LLM,
            fakeAddress = "+88810000001",
            conversationKey = "default-assistant",
            payload = ChannelPayload.Llm(),
            createdAt = 100L,
            expiresAt = 200L,
            lastUsedAt = null,
            replyCount = 0,
            replyable = true
        )
    }

    @Test fun `consent missing short-circuits with ConsentMissing`() = runTest {
        coEvery { prefs.isPrivacyConsentGiven() } returns false

        val result = starter.start(AssistantTriggerSource.MANUAL_BUTTON)
        assertThat(result).isEqualTo(LlmStarter.StartResult.ConsentMissing)
        coVerify(exactly = 0) { sendBudget.checkAndIncrement() }
        coVerify(exactly = 0) { smsWriter.injectIncoming(any(), any(), any(), any()) }
    }

    @Test fun `not default sms app short-circuits`() = runTest {
        every { roleManager.isDefault() } returns false

        val result = starter.start(AssistantTriggerSource.MANUAL_BUTTON)
        assertThat(result).isEqualTo(LlmStarter.StartResult.NotDefaultSmsApp)
    }

    @Test fun `no api key short-circuits`() = runTest {
        coEvery { apiKeyStore.read() } returns null

        val result = starter.start(AssistantTriggerSource.MANUAL_BUTTON)
        assertThat(result).isEqualTo(LlmStarter.StartResult.NoApiKey)
        coVerify(exactly = 0) { sendBudget.checkAndIncrement() }
    }

    @Test fun `budget exceeded short-circuits before allocation`() = runTest {
        coEvery { sendBudget.checkAndIncrement() } returns false

        val result = starter.start(AssistantTriggerSource.MANUAL_BUTTON)
        assertThat(result).isEqualTo(LlmStarter.StartResult.BudgetExceeded)
        coVerify(exactly = 0) {
            mappingRepo.allocateOrReuse(any(), any(), any(), any())
        }
    }

    @Test fun `successful start returns Success with allocated address`() = runTest {
        coEvery {
            smsWriter.injectIncoming(any(), any(), any(), any())
        } returns FAKE_OK_URI

        val result = starter.start(AssistantTriggerSource.MANUAL_BUTTON)
        assertThat(result).isInstanceOf(LlmStarter.StartResult.Success::class.java)
        assertThat((result as LlmStarter.StartResult.Success).fakeAddress)
            .isEqualTo("+88810000001")
        coVerify { store.resetUnderLock(1L) }
        coVerify { rateLimiter.reset(1L) }
        coVerify {
            smsWriter.injectIncoming(
                fakeAddress = "+88810000001",
                body = "Hi",
                timestamp = any(),
                displayName = "Grok"
            )
        }
    }

    @Test fun `inject returning null rolls back budget`() = runTest {
        coEvery {
            smsWriter.injectIncoming(any(), any(), any(), any())
        } returns null

        val result = starter.start(AssistantTriggerSource.MANUAL_BUTTON)
        assertThat(result).isEqualTo(LlmStarter.StartResult.InjectionFailed)
        coVerify { sendBudget.rollback() }
    }

    @Test fun `inject throwing rolls back budget`() = runTest {
        coEvery {
            smsWriter.injectIncoming(any(), any(), any(), any())
        } throws RuntimeException("boom")

        val result = starter.start(AssistantTriggerSource.MANUAL_BUTTON)
        assertThat(result).isEqualTo(LlmStarter.StartResult.InjectionFailed)
        coVerify { sendBudget.rollback() }
    }

    @Test fun `welcome from prefs is injected verbatim`() = runTest {
        // welcomeMessage() löst {driver} auf UND fällt intern auf den Default zurück;
        // LlmStarter reicht das Ergebnis nur durch (kein eigener Fallback mehr).
        coEvery { prefs.welcomeMessage() } returns "Hallo, hier ist Grok."
        coEvery {
            smsWriter.injectIncoming(any(), any(), any(), any())
        } returns FAKE_OK_URI

        starter.start(AssistantTriggerSource.MANUAL_BUTTON)
        coVerify {
            smsWriter.injectIncoming(
                fakeAddress = any(),
                body = "Hallo, hier ist Grok.",
                timestamp = any(),
                displayName = any()
            )
        }
    }
}
