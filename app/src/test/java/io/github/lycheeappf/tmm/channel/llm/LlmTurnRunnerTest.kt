package io.github.lycheeappf.tmm.channel.llm

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.R
import io.github.lycheeappf.tmm.channel.llm.provider.LlmProvider
import io.github.lycheeappf.tmm.channel.llm.provider.LlmProviderError
import io.github.lycheeappf.tmm.channel.llm.provider.LlmRequest
import io.github.lycheeappf.tmm.channel.llm.provider.LlmResponse
import io.github.lycheeappf.tmm.channel.llm.provider.ToolCall
import io.github.lycheeappf.tmm.channel.llm.tools.ToolInvocationResult
import io.github.lycheeappf.tmm.channel.llm.tools.ToolRegistry
import io.github.lycheeappf.tmm.core.locale.localizedString
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.data.store.AssistantPreferencesStore
import io.github.lycheeappf.tmm.platform.location.LocationFix
import io.github.lycheeappf.tmm.platform.location.LocationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.coVerify
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test

class LlmTurnRunnerTest {

    private val context: Context = mockk()
    private val store = LlmConversationStore(mockk(relaxed = true) {
        coEvery { contextTtlSeconds() } returns 120
    }) { 1_000L }
    private val provider: LlmProvider = mockk()
    private val prefs: AssistantPreferencesStore = mockk()
    private val limiter: LlmRateLimiter = mockk()
    private val formatter = LlmResponseFormatter()
    private val toolRegistry: ToolRegistry = mockk(relaxed = true)
    // Bewusst strikt: schlägt fehl, wenn der Runner den Standort trotz
    // deaktiviertem Opt-in abfragt.
    private val locationProvider: LocationProvider = mockk()
    private val logBuffer: LogBuffer = mockk(relaxed = true)

    private lateinit var runner: LlmTurnRunner

    @Before fun setup() {
        // localizedString ist eine Top-Level-Extension (LocaleExt) — auf der JVM ohne
        // Robolectric via mockkStatic stubben, statt einen echten Context zu brauchen.
        mockkStatic("io.github.lycheeappf.tmm.core.locale.LocaleExtKt")
        every { context.localizedString(R.string.llm_tool_success_fallback) } returns FALLBACK_TEXT
        coEvery { prefs.model() } returns "grok-4.3"
        coEvery { prefs.systemPrompt(any(), any(), isNull()) } returns "Sys"
        coEvery { prefs.maxTokens() } returns 256
        coEvery { prefs.temperature() } returns 0.7f
        coEvery { prefs.webSearchEnabled() } returns false
        coEvery { prefs.xSearchEnabled() } returns false
        coEvery { prefs.locationContextEnabled() } returns false
        coEvery { toolRegistry.activeSchemas() } returns emptyList()
        coEvery { limiter.checkAndAcquire(any()) } returns LlmRateLimiter.Decision.Allow
        // refund() wird bei Provider-Failure / EmptyResponse aufgerufen — mockk
        // wirft sonst MockKException ("missing answer").
        coEvery { limiter.refund(any()) } returns Unit
        runner = LlmTurnRunner(
            context, store, provider, prefs, limiter, formatter, toolRegistry, locationProvider, logBuffer
        ) { 1_000L }
    }

    @After fun tearDown() {
        unmockkStatic("io.github.lycheeappf.tmm.core.locale.LocaleExtKt")
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
        coVerify { prefs.systemPrompt(true, true, null) }
    }

    @Test fun `location clause is included when opt-in is enabled and a fresh fix exists`() = runTest {
        val fix = LocationFix(latitude = 48.1373, longitude = 11.5754, accuracyInMeters = 12f)
        coEvery { prefs.locationContextEnabled() } returns true
        every { locationProvider.lastKnownLocation() } returns fix
        coEvery { prefs.systemPrompt(false, false, fix) } returns "Sys mit Standort"
        coEvery { provider.complete(any()) } returns LlmResponse(
            content = "Antwort!", toolCalls = emptyList(), finishReason = "stop",
            usage = null, responseId = "r1"
        )

        val result = runner.run(7L, "Frage?")

        assertThat(result).isInstanceOf(LlmTurnRunner.TurnResult.Success::class.java)
        coVerify { prefs.systemPrompt(false, false, fix) }
    }

    @Test fun `location is not queried at all when the opt-in toggle is off`() = runTest {
        coEvery { provider.complete(any()) } returns LlmResponse(
            content = "Antwort!", toolCalls = emptyList(), finishReason = "stop",
            usage = null, responseId = "r1"
        )

        runner.run(7L, "Frage?")

        verify(exactly = 0) { locationProvider.lastKnownLocation() }
        coVerify { prefs.systemPrompt(false, false, null) }
    }

    @Test fun `turn proceeds without location clause when the provider has no fresh fix`() = runTest {
        coEvery { prefs.locationContextEnabled() } returns true
        every { locationProvider.lastKnownLocation() } returns null
        coEvery { provider.complete(any()) } returns LlmResponse(
            content = "Antwort!", toolCalls = emptyList(), finishReason = "stop",
            usage = null, responseId = "r1"
        )

        val result = runner.run(7L, "Frage?")

        assertThat(result).isInstanceOf(LlmTurnRunner.TurnResult.Success::class.java)
        coVerify { prefs.systemPrompt(false, false, null) }
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

    @Test fun `tool loop happy path executes the tool and feeds the result into a follow-up completion`() = runTest {
        val toolOutput = """{"status":"ok","destination":"Alexanderplatz"}"""
        val argsCaptured = slot<JsonObject>()
        coEvery { toolRegistry.invoke("tesla_navigate", capture(argsCaptured)) } returns
            ToolInvocationResult.Success(toolOutput)
        val requests = mutableListOf<LlmRequest>()
        coEvery { provider.complete(capture(requests)) } returnsMany listOf(
            LlmResponse(
                content = null,
                toolCalls = listOf(ToolCall("c1", "tesla_navigate", """{"address":"Alexanderplatz"}""")),
                finishReason = "tool_calls", usage = null, responseId = "r1"
            ),
            LlmResponse(
                content = "Ich navigiere dich zum Alexanderplatz.", toolCalls = emptyList(),
                finishReason = "stop", usage = null, responseId = "r2"
            )
        )

        val result = runner.run(7L, "Navigier mich zum Alexanderplatz")

        assertThat(result).isInstanceOf(LlmTurnRunner.TurnResult.Success::class.java)
        assertThat((result as LlmTurnRunner.TurnResult.Success).assistantText)
            .isEqualTo("Ich navigiere dich zum Alexanderplatz.")
        // Tool genau einmal mit den geparsten Argumenten aufgerufen.
        coVerify(exactly = 1) { toolRegistry.invoke("tesla_navigate", any()) }
        assertThat(argsCaptured.captured["address"]?.jsonPrimitive?.content)
            .isEqualTo("Alexanderplatz")
        // Erster Request ohne In-Flight-Items; der Follow-up trägt Call + Result
        // (Result-Output = unverändertes Tool-JSON, callId matcht den Tool-Call).
        assertThat(requests).hasSize(2)
        assertThat(requests[0].inFlightToolCalls).isEmpty()
        assertThat(requests[0].inFlightToolResults).isEmpty()
        assertThat(requests[1].inFlightToolCalls).hasSize(1)
        assertThat(requests[1].inFlightToolCalls[0].name).isEqualTo("tesla_navigate")
        assertThat(requests[1].inFlightToolResults).hasSize(1)
        assertThat(requests[1].inFlightToolResults[0].callId).isEqualTo("c1")
        assertThat(requests[1].inFlightToolResults[0].output).isEqualTo(toolOutput)
        // Normaler Erfolg: History persistiert, kein Refund.
        assertThat(store.snapshot(store.sessionFor(7L))).hasSize(2)
        coVerify(exactly = 0) { limiter.refund(any()) }
    }

    @Test fun `tool failure is sent back to the model and its spoken error becomes the turn result`() = runTest {
        coEvery { toolRegistry.invoke("tesla_navigate", any()) } returns
            ToolInvocationResult.Failure("no vehicle configured")
        val requests = mutableListOf<LlmRequest>()
        coEvery { provider.complete(capture(requests)) } returnsMany listOf(
            LlmResponse(
                content = null,
                toolCalls = listOf(ToolCall("c1", "tesla_navigate", "{}")),
                finishReason = "tool_calls", usage = null, responseId = "r1"
            ),
            LlmResponse(
                content = "Die Navigation hat leider nicht geklappt.", toolCalls = emptyList(),
                finishReason = "stop", usage = null, responseId = "r2"
            )
        )

        val result = runner.run(7L, "Navigier mich")

        // Der Tool-Fehler geht als error-JSON an das Modell zurück …
        assertThat(requests).hasSize(2)
        assertThat(requests[1].inFlightToolResults[0].output)
            .isEqualTo("""{"error":"no vehicle configured"}""")
        // … und dessen verbalisierte Fehlermeldung wird als normaler Success
        // vorgelesen — NICHT die Erfolgs-Fallback-Bestätigung.
        assertThat(result).isInstanceOf(LlmTurnRunner.TurnResult.Success::class.java)
        assertThat((result as LlmTurnRunner.TurnResult.Success).assistantText)
            .isEqualTo("Die Navigation hat leider nicht geklappt.")
        assertThat(result.assistantText).isNotEqualTo(FALLBACK_TEXT)
        assertThat(store.snapshot(store.sessionFor(7L))).hasSize(2)
    }

    @Test fun `blank reply after successful tool call falls back to a localized confirmation`() = runTest {
        coEvery { toolRegistry.invoke("tesla_navigate", any()) } returns
            ToolInvocationResult.Success("""{"status":"ok","destination":"Alexanderplatz"}""")
        coEvery { provider.complete(any()) } returnsMany listOf(
            LlmResponse(
                content = null,
                toolCalls = listOf(ToolCall("c1", "tesla_navigate", """{"address":"Alexanderplatz"}""")),
                finishReason = "tool_calls", usage = null, responseId = "r1"
            ),
            LlmResponse(content = "", toolCalls = emptyList(), finishReason = "stop", usage = null, responseId = "r2")
        )

        val result = runner.run(7L, "Navigier mich zum Alexanderplatz")

        assertThat(result).isInstanceOf(LlmTurnRunner.TurnResult.Success::class.java)
        assertThat((result as LlmTurnRunner.TurnResult.Success).assistantText).isEqualTo(FALLBACK_TEXT)
        // Fallback zählt als normaler Erfolg: History wird persistiert, kein Refund.
        assertThat(store.snapshot(store.sessionFor(7L))).hasSize(2)
        coVerify(exactly = 0) { limiter.refund(any()) }
    }

    @Test fun `blank reply after failed tool call stays on the empty-response error path`() = runTest {
        coEvery { toolRegistry.invoke("tesla_navigate", any()) } returns
            ToolInvocationResult.Failure("no vehicle configured")
        coEvery { provider.complete(any()) } returnsMany listOf(
            LlmResponse(
                content = null,
                toolCalls = listOf(ToolCall("c1", "tesla_navigate", "{}")),
                finishReason = "tool_calls", usage = null, responseId = "r1"
            ),
            LlmResponse(content = "", toolCalls = emptyList(), finishReason = "stop", usage = null, responseId = "r2")
        )

        val result = runner.run(7L, "Navigier mich")

        assertThat(result).isEqualTo(LlmTurnRunner.TurnResult.EmptyResponse)
        assertThat(store.snapshot(store.sessionFor(7L))).isEmpty()
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

    private companion object {
        const val FALLBACK_TEXT = "Erledigt — die Anfrage wurde ausgeführt."
    }
}
