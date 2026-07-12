package io.github.lycheeappf.tmm.channel.llm

import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.channel.llm.provider.FINISH_REASON_INCOMPLETE
import io.github.lycheeappf.tmm.channel.llm.provider.LlmProvider
import io.github.lycheeappf.tmm.channel.llm.provider.LlmProviderError
import io.github.lycheeappf.tmm.channel.llm.provider.LlmRequest
import io.github.lycheeappf.tmm.channel.llm.provider.LlmResponse
import io.github.lycheeappf.tmm.channel.llm.provider.ToolCall
import io.github.lycheeappf.tmm.channel.llm.tools.ToolCallExecutor
import io.github.lycheeappf.tmm.channel.llm.tools.ToolInvocationResult
import io.github.lycheeappf.tmm.channel.llm.tools.ToolRegistry
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.data.store.AssistantPreferencesStore
import io.github.lycheeappf.tmm.data.store.TeslaTokenStore
import io.github.lycheeappf.tmm.platform.location.LocationFix
import io.github.lycheeappf.tmm.platform.location.LocationProvider
import io.github.lycheeappf.tmm.platform.permission.PermissionGate
import io.github.lycheeappf.tmm.platform.tesla.auth.TeslaAuthManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class GrokSelfTesterTest {

    private val keyTester: GrokKeyTester = mockk()
    private val provider: LlmProvider = mockk()
    private val prefs: AssistantPreferencesStore = mockk()
    private val toolRegistry: ToolRegistry = mockk(relaxed = true)
    private val locationProvider: LocationProvider = mockk()
    private val permissionGate: PermissionGate = mockk()
    private val teslaAuthManager: TeslaAuthManager = mockk()
    private val teslaTokenStore: TeslaTokenStore = mockk()
    private val logBuffer: LogBuffer = mockk(relaxed = true)

    private val tester = GrokSelfTester(
        keyTester, provider, prefs, toolRegistry,
        ToolCallExecutor(toolRegistry, logBuffer),
        locationProvider, permissionGate, teslaAuthManager, teslaTokenStore, logBuffer
    )

    private val fix = LocationFix(52.5200, 13.4050, 25f)

    @Before fun setup() {
        coEvery { keyTester.run() } returns KeyTestOutcome.VALID
        coEvery { prefs.isPrivacyConsentGiven() } returns true
        coEvery { prefs.locationContextEnabled() } returns true
        coEvery { prefs.model() } returns "grok-4.3"
        coEvery { prefs.systemPrompt(false, false, any()) } returns "Sys mit Position"
        every { permissionGate.hasLocationAccess() } returns true
        every { permissionGate.hasBackgroundLocationAccess() } returns true
        every { locationProvider.lastKnownLocation() } returns fix
        coEvery { teslaAuthManager.hasCredentials() } returns true
        coEvery { teslaTokenStore.readSelectedVin() } returns "5YJ3E1EA7KF000000"
        coEvery { toolRegistry.activeSchemas() } returns emptyList()
    }

    private fun navToolResponse() = LlmResponse(
        content = null,
        toolCalls = listOf(ToolCall("c1", "tesla_navigate", """{"address":"Alexanderplatz, Berlin"}""")),
        finishReason = "tool_calls", usage = null, responseId = "r1"
    )

    private fun textResponse(text: String) = LlmResponse(
        content = text, toolCalls = emptyList(), finishReason = "stop", usage = null, responseId = "r2"
    )

    private suspend fun runToList(destination: String = "Alexanderplatz, Berlin") =
        tester.run(destination).toList()

    private fun List<SelfTestEvent>.e2e(): E2eResult =
        filterIsInstance<SelfTestEvent.E2eDone>().single().result

    @Test fun `invalid key aborts and skips all later stages`() = runTest {
        coEvery { keyTester.run() } returns KeyTestOutcome.AUTH_ERROR

        val events = runToList()

        assertThat(events.filterIsInstance<SelfTestEvent.KeyResult>().single().outcome)
            .isEqualTo(KeyTestOutcome.AUTH_ERROR)
        assertThat(events.filterIsInstance<SelfTestEvent.StageSkipped>().map { it.stage })
            .containsExactly(SelfTestStage.POSITION, SelfTestStage.TESLA_LOCAL, SelfTestStage.E2E)
            .inOrder()
        coVerify(exactly = 0) { provider.complete(any()) }
    }

    @Test fun `missing consent yields ConsentMissing without any provider call`() = runTest {
        coEvery { prefs.isPrivacyConsentGiven() } returns false

        val events = runToList()

        assertThat(events.e2e()).isEqualTo(E2eResult.ConsentMissing)
        coVerify(exactly = 0) { provider.complete(any()) }
    }

    @Test fun `position stage reports first missing link - opt-in off`() = runTest {
        coEvery { prefs.locationContextEnabled() } returns false
        coEvery { provider.complete(any()) } returns textResponse("NO POSITION")

        val events = runToList()

        assertThat(events.filterIsInstance<SelfTestEvent.PositionResult>().single().result)
            .isEqualTo(PositionLocalResult.Disabled)
        // Kein Fix → Turn läuft ohne Location.
        coVerify { prefs.systemPrompt(false, false, null) }
    }

    @Test fun `position stage reports missing permission before touching the provider`() = runTest {
        every { permissionGate.hasLocationAccess() } returns false
        coEvery { provider.complete(any()) } returns textResponse("NO POSITION")

        val events = runToList()

        assertThat(events.filterIsInstance<SelfTestEvent.PositionResult>().single().result)
            .isEqualTo(PositionLocalResult.NoPermission)
    }

    @Test fun `position stage reports NoFix when the os cache is empty`() = runTest {
        every { locationProvider.lastKnownLocation() } returns null
        coEvery { provider.complete(any()) } returns textResponse("NO POSITION")

        val events = runToList()

        assertThat(events.filterIsInstance<SelfTestEvent.PositionResult>().single().result)
            .isEqualTo(PositionLocalResult.NoFix)
    }

    @Test fun `foreground-only permission is flagged on the Ok result`() = runTest {
        every { permissionGate.hasBackgroundLocationAccess() } returns false
        coEvery { provider.complete(any()) } returns textResponse("52.52, 13.41")

        val events = runToList()

        assertThat(events.filterIsInstance<SelfTestEvent.PositionResult>().single().result)
            .isEqualTo(PositionLocalResult.Ok(fix, backgroundGranted = false))
    }

    @Test fun `tesla local stage reports credentials and vin`() = runTest {
        coEvery { teslaTokenStore.readSelectedVin() } returns null
        coEvery { provider.complete(any()) } returns textResponse("52.52, 13.41")

        val events = runToList()

        val tesla = events.filterIsInstance<SelfTestEvent.TeslaLocalResult>().single()
        assertThat(tesla.credentialsSet).isTrue()
        assertThat(tesla.vinSelected).isFalse()
    }

    @Test fun `happy path - nav tool called, fleet ok, coordinates echoed`() = runTest {
        coEvery { toolRegistry.invoke("tesla_navigate", any()) } returns
            ToolInvocationResult.Success("""{"status":"ok","destination":"Alexanderplatz, Berlin"}""")
        coEvery { provider.complete(any()) } returnsMany listOf(
            navToolResponse(), textResponse("Navigation läuft. Deine Position: 52,52° N, 13,41° O.")
        )

        val result = runToList().e2e()

        assertThat(result).isInstanceOf(E2eResult.Completed::class.java)
        val completed = result as E2eResult.Completed
        assertThat(completed.nav).isEqualTo(NavCheck.CalledOk("Alexanderplatz, Berlin"))
        assertThat(completed.echo).isEqualTo(PositionEcho.MATCHED)
        assertThat(completed.answer).contains("Navigation läuft")
    }

    @Test fun `fleet failure surfaces the localized tool error`() = runTest {
        coEvery { toolRegistry.invoke("tesla_navigate", any()) } returns
            ToolInvocationResult.Failure("Kein Tesla-Fahrzeug konfiguriert")
        coEvery { provider.complete(any()) } returnsMany listOf(
            navToolResponse(), textResponse("Da ging was schief. NO POSITION")
        )

        val result = runToList().e2e() as E2eResult.Completed

        assertThat(result.nav)
            .isEqualTo(NavCheck.CalledFailed("Alexanderplatz, Berlin", "Kein Tesla-Fahrzeug konfiguriert"))
    }

    @Test fun `tool never called yields NotCalled`() = runTest {
        coEvery { provider.complete(any()) } returns textResponse("Ich kann nicht navigieren. NO POSITION")

        val result = runToList().e2e() as E2eResult.Completed

        assertThat(result.nav).isEqualTo(NavCheck.NotCalled)
    }

    @Test fun `blank system prompt with fix yields NO_CLAUSE`() = runTest {
        coEvery { prefs.systemPrompt(false, false, any()) } returns ""
        coEvery { provider.complete(any()) } returns textResponse("NO POSITION")

        val result = runToList().e2e() as E2eResult.Completed

        assertThat(result.echo).isEqualTo(PositionEcho.NO_CLAUSE)
    }

    @Test fun `provider error maps via shared key-test outcome`() = runTest {
        coEvery { provider.complete(any()) } throws LlmProviderError.RateLimit(retryAfterSec = null)

        val result = runToList().e2e()

        assertThat(result).isEqualTo(E2eResult.ProviderFailed(KeyTestOutcome.RATE_LIMITED))
    }

    @Test fun `slow provider maps to Timeout`() = runTest {
        coEvery { provider.complete(any()) } coAnswers {
            delay(GrokSelfTester.E2E_TIMEOUT_MS + 1)
            textResponse("zu spät")
        }

        val result = runToList().e2e()

        assertThat(result).isEqualTo(E2eResult.Timeout)
    }

    @Test fun `request is deterministic - no search, temperature zero, fixed max tokens, empty history`() = runTest {
        val captured = slot<LlmRequest>()
        coEvery { provider.complete(capture(captured)) } returns textResponse("52.52, 13.41")

        runToList()

        val req = captured.captured
        assertThat(req.webSearch).isFalse()
        assertThat(req.xSearch).isFalse()
        assertThat(req.temperature).isEqualTo(0f)
        assertThat(req.maxTokens).isEqualTo(GrokSelfTester.E2E_MAX_TOKENS)
        assertThat(req.history).isEmpty()
        assertThat(req.model).isEqualTo("grok-4.3")
        assertThat(req.userMessage).contains("Alexanderplatz, Berlin")
        assertThat(req.userMessage).contains("tesla_navigate")
        assertThat(req.toolChoice).isEqualTo(GrokSelfTester.TOOL_CHOICE_REQUIRED)
    }

    @Test fun `incomplete response without tool call maps to Truncated`() = runTest {
        // Reasoning hat das Budget aufgebraucht: kein Text, kein Call, finishReason incomplete.
        coEvery { provider.complete(any()) } returns LlmResponse(
            content = null, toolCalls = emptyList(),
            finishReason = FINISH_REASON_INCOMPLETE, usage = null, responseId = "r3"
        )

        val result = runToList().e2e()

        assertThat(result).isEqualTo(E2eResult.Truncated)
    }

    @Test fun `incomplete follow-up after successful tool call still reports Completed`() = runTest {
        coEvery { toolRegistry.invoke("tesla_navigate", any()) } returns
            ToolInvocationResult.Success("""{"status":"ok","destination":"Alexanderplatz, Berlin"}""")
        coEvery { provider.complete(any()) } returnsMany listOf(
            navToolResponse(),
            LlmResponse(
                content = null, toolCalls = emptyList(),
                finishReason = FINISH_REASON_INCOMPLETE, usage = null, responseId = "r4"
            )
        )

        val result = runToList().e2e() as E2eResult.Completed

        assertThat(result.nav).isEqualTo(NavCheck.CalledOk("Alexanderplatz, Berlin"))
        assertThat(result.answer).isEmpty()
    }
}
