package io.github.lycheeappf.tmm.channel.llm

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.R
import io.github.lycheeappf.tmm.channel.llm.provider.LlmProviderError
import io.github.lycheeappf.tmm.core.model.ChannelId
import io.github.lycheeappf.tmm.core.security.ApiKeyStore
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.core.util.SendBudget
import io.github.lycheeappf.tmm.data.store.AssistantPreferencesStore
import io.github.lycheeappf.tmm.domain.channel.ChannelMapping
import io.github.lycheeappf.tmm.domain.channel.ChannelPayload
import io.github.lycheeappf.tmm.domain.reply.ReplyResult
import io.github.lycheeappf.tmm.platform.bluetooth.BluetoothConnectionChecker
import io.github.lycheeappf.tmm.sms.provider.SmsContentProviderWriter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val FAKE_OK_URI: Uri = mockk(relaxed = true)

// Robolectric: LlmChannel löst seine TTS-Texte über einen echten Context auf
// (lokalisierte String-Ressourcen), daher braucht der Test einen App-Context.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LlmChannelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val turnRunner: LlmTurnRunner = mockk()
    private val smsWriter: SmsContentProviderWriter = mockk()
    private val sendBudget: SendBudget = mockk()
    private val prefs: AssistantPreferencesStore = mockk()
    private val apiKeyStore: ApiKeyStore = mockk()
    private val logBuffer: LogBuffer = mockk(relaxed = true)
    private val btChecker: BluetoothConnectionChecker = mockk()
    private lateinit var channel: LlmChannel

    private val llmMapping = ChannelMapping(
        mappingId = 7L,
        channel = ChannelId.LLM,
        fakeAddress = "+88810000007",
        conversationKey = "default-assistant",
        payload = ChannelPayload.Llm(
            providerId = "grok",
            assistantDisplayName = "Grok",
            conversationKey = "default-assistant"
        ),
        createdAt = 100L,
        expiresAt = Long.MAX_VALUE,
        lastUsedAt = null,
        replyCount = 0,
        replyable = true
    )

    @Before fun setup() {
        channel = LlmChannel(turnRunner, smsWriter, sendBudget, prefs, apiKeyStore, logBuffer, btChecker, context)
        coEvery { sendBudget.checkAndIncrement() } returns true
        coEvery { sendBudget.rollback() } returns Unit
        // Default: Assistent aktiv (Consent gegeben + Key gesetzt) + Tesla verbunden.
        coEvery { prefs.isPrivacyConsentGiven() } returns true
        coEvery { apiKeyStore.read() } returns "xai-key"
        coEvery { btChecker.isTeslaConnected() } returns true
    }

    @Test fun `handleTeslaReply with blank text returns Ignored without provider call`() = runTest {
        val result = channel.handleTeslaReply(llmMapping, "   ")
        assertThat(result).isEqualTo(ReplyResult.Ignored)
        coVerify(exactly = 0) { turnRunner.run(any(), any()) }
    }

    @Test fun `handleTeslaReply payload mismatch when not Llm`() = runTest {
        val sysMapping = llmMapping.copy(payload = ChannelPayload.System("test"))
        val result = channel.handleTeslaReply(sysMapping, "hi")
        assertThat(result).isEqualTo(ReplyResult.PayloadMismatch)
    }

    @Test fun `handleTeslaReply success maps to FollowUp`() = runTest {
        coEvery { turnRunner.run(7L, "Frage") } returns
            LlmTurnRunner.TurnResult.Success("Antwort", usage = null)
        val result = channel.handleTeslaReply(llmMapping, "Frage")
        assertThat(result).isInstanceOf(ReplyResult.FollowUp::class.java)
        assertThat((result as ReplyResult.FollowUp).body).isEqualTo("Antwort")
    }

    @Test fun `handleTeslaReply provider failure maps to ProviderError`() = runTest {
        coEvery { turnRunner.run(any(), any()) } returns
            LlmTurnRunner.TurnResult.ProviderFailed(LlmProviderError.Auth("403"))
        val result = channel.handleTeslaReply(llmMapping, "Frage")
        assertThat(result).isInstanceOf(ReplyResult.ProviderError::class.java)
    }

    @Test fun `handleTeslaReply refuses turn when consent withdrawn`() = runTest {
        coEvery { prefs.isPrivacyConsentGiven() } returns false
        val result = channel.handleTeslaReply(llmMapping, "Frage")
        assertThat(result).isInstanceOf(ReplyResult.ProviderError::class.java)
        coVerify(exactly = 0) { turnRunner.run(any(), any()) }
    }

    @Test fun `handleTeslaReply refuses turn when api key missing`() = runTest {
        coEvery { apiKeyStore.read() } returns null
        val result = channel.handleTeslaReply(llmMapping, "Frage")
        assertThat(result).isInstanceOf(ReplyResult.ProviderError::class.java)
        coVerify(exactly = 0) { turnRunner.run(any(), any()) }
    }

    @Test fun `handleTeslaReply skips turn when Tesla not connected`() = runTest {
        coEvery { btChecker.isTeslaConnected() } returns false
        val result = channel.handleTeslaReply(llmMapping, "Frage")
        // Ignored → ReplyDispatcher überspringt das Follow-up-Inject; kein xAI-Call.
        assertThat(result).isEqualTo(ReplyResult.Ignored)
        coVerify(exactly = 0) { turnRunner.run(any(), any()) }
    }

    @Test fun `maybeInjectFollowUp injects FollowUp body via SmsWriter`() = runTest {
        coEvery {
            smsWriter.injectIncoming(any(), any(), any(), any())
        } returns FAKE_OK_URI

        channel.maybeInjectFollowUp(
            llmMapping, "user-text", ReplyResult.FollowUp("Antwort von Grok")
        )

        coVerify {
            smsWriter.injectIncoming(
                fakeAddress = "+88810000007",
                body = "Antwort von Grok",
                timestamp = any(),
                displayName = "Grok"
            )
        }
    }

    @Test fun `maybeInjectFollowUp with Ignored does nothing`() = runTest {
        channel.maybeInjectFollowUp(llmMapping, "user-text", ReplyResult.Ignored)
        coVerify(exactly = 0) { smsWriter.injectIncoming(any(), any(), any(), any()) }
    }

    @Test fun `maybeInjectFollowUp returns budget on inject failure`() = runTest {
        coEvery {
            smsWriter.injectIncoming(any(), any(), any(), any())
        } returns null

        channel.maybeInjectFollowUp(
            llmMapping, "user", ReplyResult.FollowUp("text")
        )
        coVerify { sendBudget.checkAndIncrement() }
        coVerify { sendBudget.rollback() }
    }

    @Test fun `maybeInjectFollowUp skips when budget exceeded`() = runTest {
        coEvery { sendBudget.checkAndIncrement() } returns false
        channel.maybeInjectFollowUp(
            llmMapping, "user", ReplyResult.FollowUp("text")
        )
        coVerify(exactly = 0) { smsWriter.injectIncoming(any(), any(), any(), any()) }
    }

    @Test fun `maybeInjectFollowUp injects friendly error for ProviderError`() = runTest {
        coEvery {
            smsWriter.injectIncoming(any(), any(), any(), any())
        } returns FAKE_OK_URI

        val detail = "kein Internetzugriff."
        channel.maybeInjectFollowUp(
            llmMapping, "user", ReplyResult.ProviderError(detail)
        )
        // Locale-agnostisch: der Wrapper-Text wird über dieselbe Ressource aufgelöst.
        val expected = context.getString(R.string.llm_error_wrapper, detail)
        coVerify {
            smsWriter.injectIncoming(
                fakeAddress = "+88810000007",
                body = expected,
                timestamp = any(),
                displayName = "Grok"
            )
        }
    }
}
