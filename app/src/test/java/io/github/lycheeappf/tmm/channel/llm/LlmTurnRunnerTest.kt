package io.github.lycheeappf.tmm.channel.llm

import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.channel.llm.provider.LlmProvider
import io.github.lycheeappf.tmm.channel.llm.provider.LlmProviderError
import io.github.lycheeappf.tmm.channel.llm.provider.LlmRequest
import io.github.lycheeappf.tmm.channel.llm.provider.LlmResponse
import io.github.lycheeappf.tmm.channel.llm.tools.ToolRegistry
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.data.store.AssistantPreferencesStore
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class LlmTurnRunnerTest {

    private val store = LlmConversationStore(mockk(relaxed = true) {
        coEvery { contextTtlSeconds() } returns 120
    }) { 1_000L }
    private val provider: LlmProvider = mockk()
    private val prefs: AssistantPreferencesStore = mockk()
    private val limiter: LlmRateLimiter = mockk()
    private val formatter = LlmResponseFormatter()
    private val toolRegistry: ToolRegistry = mockk(relaxed = true)
    private val logBuffer: LogBuffer = mockk(relaxed = true)

    private lateinit var runner: LlmTurnRunner

    @Before fun setup() {
        coEvery { prefs.model() } returns "grok-4.3"
        coEvery { prefs.systemPrompt(any(), any()) } returns "Sys"
        coEvery { prefs.maxTokens() } returns 256
        coEvery { prefs.temperature() } returns 0.7f
        coEvery { prefs.webSearchEnabled() } returns false
        coEvery { prefs.xSearchEnabled() } returns false
        coEvery { toolRegistry.activeSchemas() } returns emptyList()
        coEvery { limiter.checkAndAcquire(any()) } returns LlmRateLimiter.Decision.Allow
        // refund() wird bei Provider-Failure / EmptyResponse aufgerufen — mockk
        // wirft sonst MockKException ("missing answer").
        coEvery { limiter.refund(any()) } returns Unit
        runner = LlmTurnRunner(store, provider, prefs, limiter, formatter, toolRegistry, logBuffer) { 1_000L }
    }

    @Test fun `success appends user and assistant turns`() = runTest {
        val captured = slot<LlmRequest>()
        coEvery { provider.complete(capture(captured)) } returns LlmResponse(
            content = "Antwort!", toolCalls = emptyList(), finishReason = "stop",
            usage = null, responseId = "r1"
        )

        val result = runner.run(7L, "Frage?")
        assertThat(result).isInstanceOf(LlmTurnRunner.TurnResult.Success::class.java)
        assertThat((result as LlmTurnRunner.TurnResult.Success).assistantText).isEqualTo("Antwort!")
        assertThat(store.snapshot(store.sessionFor(7L))).hasSize(2)
        assertThat(store.snapshot(store.sessionFor(7L))[0].role).isEqualTo("user")
        assertThat(store.snapshot(store.sessionFor(7L))[1].role).isEqualTo("assistant")
        assertThat(captured.captured.userMessage).isEqualTo("Frage?")
        assertThat(captured.captured.history).isEmpty()
    }

    @Test fun `search flags snapshot propagates into the request`() = runTest {
        coEvery { prefs.webSearchEnabled() } returns true
        coEvery { prefs.xSearchEnabled() } returns true
        val captured = slot<LlmRequest>()
        coEvery { provider.complete(capture(captured)) } returns LlmResponse(
            content = "Antwort!", toolCalls = emptyList(), finishReason = "stop",
            usage = null, responseId = "r1"
        )

        runner.run(7L, "Frage?")
        assertThat(captured.captured.webSearch).isTrue()
        assertThat(captured.captured.xSearch).isTrue()
        coVerify { prefs.systemPrompt(true, true) }
    }

    @Test fun `provider failure keeps history clean`() = runTest {
        coEvery { provider.complete(any()) } throws LlmProviderError.Auth("403")

        val result = runner.run(7L, "Frage?")
        assertThat(result).isInstanceOf(LlmTurnRunner.TurnResult.ProviderFailed::class.java)
        assertThat(store.snapshot(store.sessionFor(7L))).isEmpty()
    }

    @Test fun `empty response keeps history clean`() = runTest {
        coEvery { provider.complete(any()) } returns LlmResponse(
            content = "   ", toolCalls = emptyList(), finishReason = "stop",
            usage = null, responseId = "r2"
        )

        val result = runner.run(7L, "Frage?")
        assertThat(result).isEqualTo(LlmTurnRunner.TurnResult.EmptyResponse)
        assertThat(store.snapshot(store.sessionFor(7L))).isEmpty()
    }

    @Test fun `rate-limit short-circuits before provider call`() = runTest {
        coEvery { limiter.checkAndAcquire(7L) } returns
            LlmRateLimiter.Decision.Reject(LlmRateLimiter.Reason.PER_MINUTE)

        val result = runner.run(7L, "Frage?")
        assertThat(result).isInstanceOf(LlmTurnRunner.TurnResult.RateLimited::class.java)
        coVerify(exactly = 0) { provider.complete(any()) }
    }

    @Test fun `unknown exception is wrapped as Network ProviderError`() = runTest {
        coEvery { provider.complete(any()) } throws RuntimeException("boom")

        val result = runner.run(7L, "Frage?")
        assertThat(result).isInstanceOf(LlmTurnRunner.TurnResult.ProviderFailed::class.java)
        assertThat((result as LlmTurnRunner.TurnResult.ProviderFailed).error)
            .isInstanceOf(LlmProviderError.Network::class.java)
    }

    @Test fun `cancellation exception is rethrown not wrapped`() = runTest {
        coEvery { provider.complete(any()) } throws kotlinx.coroutines.CancellationException("scope dying")

        val ex = runCatching { runner.run(7L, "Frage?") }.exceptionOrNull()
        assertThat(ex).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
    }

    @Test fun `rate-limit refund is invoked on provider failure`() = runTest {
        coEvery { provider.complete(any()) } throws LlmProviderError.Auth("403")

        runner.run(7L, "Frage?")
        coVerify { limiter.refund(7L) }
    }

    @Test fun `rate-limit refund is invoked on empty response`() = runTest {
        coEvery { provider.complete(any()) } returns LlmResponse(
            content = "", toolCalls = emptyList(), finishReason = "stop", usage = null, responseId = null
        )

        runner.run(7L, "Frage?")
        coVerify { limiter.refund(7L) }
    }

    @Test fun `second turn includes previous turns in history`() = runTest {
        coEvery { provider.complete(any()) } returnsMany listOf(
            LlmResponse("Antwort 1", emptyList(), "stop", null, "r1"),
            LlmResponse("Antwort 2", emptyList(), "stop", null, "r2")
        )
        runner.run(7L, "Frage 1")
        val captured = slot<LlmRequest>()
        coEvery { provider.complete(capture(captured)) } returns LlmResponse(
            "Antwort 2", emptyList(), "stop", null, "r2"
        )
        runner.run(7L, "Frage 2")
        assertThat(captured.captured.history).hasSize(2)
        assertThat(captured.captured.history[0].content).isEqualTo("Frage 1")
        assertThat(captured.captured.history[1].content).isEqualTo("Antwort 1")
        assertThat(captured.captured.userMessage).isEqualTo("Frage 2")
    }
}
