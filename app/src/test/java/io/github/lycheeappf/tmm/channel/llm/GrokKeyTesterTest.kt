package io.github.lycheeappf.tmm.channel.llm

import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.channel.llm.provider.LlmProvider
import io.github.lycheeappf.tmm.channel.llm.provider.LlmProviderError
import io.github.lycheeappf.tmm.channel.llm.provider.LlmRequest
import io.github.lycheeappf.tmm.channel.llm.provider.LlmResponse
import io.github.lycheeappf.tmm.data.store.AssistantPreferencesStore
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class GrokKeyTesterTest {

    private val provider: LlmProvider = mockk()
    private val tester = GrokKeyTester(provider)

    private fun okResponse() = LlmResponse(
        content = "pong",
        toolCalls = emptyList(),
        finishReason = "stop",
        usage = null,
        responseId = null
    )

    @Test fun `successful ping maps to VALID`() = runTest {
        coEvery { provider.complete(any()) } returns okResponse()
        assertThat(tester.run()).isEqualTo(KeyTestOutcome.VALID)
    }

    @Test fun `auth error maps to AUTH_ERROR`() = runTest {
        coEvery { provider.complete(any()) } throws LlmProviderError.Auth("rejected")
        assertThat(tester.run()).isEqualTo(KeyTestOutcome.AUTH_ERROR)
    }

    @Test fun `missing key maps to MISSING_KEY`() = runTest {
        coEvery { provider.complete(any()) } throws LlmProviderError.MissingKey()
        assertThat(tester.run()).isEqualTo(KeyTestOutcome.MISSING_KEY)
    }

    @Test fun `no network maps to NO_NETWORK`() = runTest {
        coEvery { provider.complete(any()) } throws LlmProviderError.NoNetwork
        assertThat(tester.run()).isEqualTo(KeyTestOutcome.NO_NETWORK)
    }

    @Test fun `socket timeout maps to TIMEOUT`() = runTest {
        coEvery { provider.complete(any()) } throws LlmProviderError.Network(SocketTimeoutException("read timed out"))
        assertThat(tester.run()).isEqualTo(KeyTestOutcome.TIMEOUT)
    }

    @Test fun `generic network IO maps to NO_NETWORK`() = runTest {
        coEvery { provider.complete(any()) } throws LlmProviderError.Network(IOException("dns"))
        assertThat(tester.run()).isEqualTo(KeyTestOutcome.NO_NETWORK)
    }

    @Test fun `rate limit maps to RATE_LIMITED`() = runTest {
        coEvery { provider.complete(any()) } throws LlmProviderError.RateLimit(5)
        assertThat(tester.run()).isEqualTo(KeyTestOutcome.RATE_LIMITED)
    }

    @Test fun `server error maps to SERVER_ERROR`() = runTest {
        coEvery { provider.complete(any()) } throws LlmProviderError.Server(503, null)
        assertThat(tester.run()).isEqualTo(KeyTestOutcome.SERVER_ERROR)
    }

    @Test fun `parse error maps to SERVER_ERROR`() = runTest {
        coEvery { provider.complete(any()) } throws LlmProviderError.Parse("unreadable")
        assertThat(tester.run()).isEqualTo(KeyTestOutcome.SERVER_ERROR)
    }

    @Test fun `provider slower than timeout maps to TIMEOUT`() = runTest {
        // runTest auto-advanced die virtuelle Zeit; withTimeoutOrNull schlägt vor dem delay zu.
        coEvery { provider.complete(any()) } coAnswers {
            delay(GrokKeyTester.TIMEOUT_MS + 1)
            okResponse()
        }
        assertThat(tester.run()).isEqualTo(KeyTestOutcome.TIMEOUT)
    }

    @Test fun `ping uses fixed default model and minimal payload`() = runTest {
        val captured = slot<LlmRequest>()
        coEvery { provider.complete(capture(captured)) } returns okResponse()
        tester.run()
        val req = captured.captured
        assertThat(req.model).isEqualTo(AssistantPreferencesStore.DEFAULT_MODEL)
        assertThat(req.userMessage).isEqualTo("ping")
        assertThat(req.history).isEmpty()
        assertThat(req.tools).isEmpty()
        assertThat(req.temperature).isEqualTo(0f)
        assertThat(req.maxTokens).isEqualTo(16)
    }
}
