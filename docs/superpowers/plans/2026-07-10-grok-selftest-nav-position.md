# Grok-Selbsttest (Key + Position + Tesla + Nav e2e) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eine „Grok-Selbsttest"-Card im Assistant-Screen, die vier Stufen live durchläuft — Key-Ping, lokaler Positions-Ketten-Check, lokaler Tesla-Konto-Check, echter End-to-end-Grok-Turn mit `tesla_navigate`-Tool-Call + Fleet-API-Call — sodass Nav & Position ohne Gang zum Auto testbar sind.

**Architecture:** Der Tool-Execution-Loop wird aus `LlmTurnRunner` in einen geteilten `ToolCallExecutor` extrahiert (verhaltensidentisch). Ein neuer `GrokSelfTester` orchestriert die Stufen als `Flow<SelfTestEvent>` am Conversation-State/Rate-Limiter vorbei; reine Auswertung (Adress-Match, Koordinaten-Echo) liegt in `SelfTestEvaluation`. `AssistantViewModel` sammelt die Events in einen `SelfTestUiState`, der `refresh()` überlebt; die UI ist eine `SettingCard` mit Status-Zeilen.

**Tech Stack:** Kotlin, Hilt (constructor injection, kein Modul-Change), Compose (bestehende Komponenten `SettingCard`/`StatusPill`/`PrimaryActionButton`), kotlinx.coroutines Flow, JUnit4 + Truth + MockK + `runTest` (Robolectric nur im bestehenden `AssistantViewModelTest`).

**Spec:** `docs/superpowers/specs/2026-07-10-grok-selftest-nav-position-design.md` — bei Widersprüchen gilt die Spec.

## Global Constraints

- Windows: immer `gradlew.bat` (nie system-gradle). JDK 17.
- Coroutines: `CancellationException` IMMER rethrown, nie in Ergebnis-Typen wrappen. Dispatcher nur via injizierte Qualifier, nie `Dispatchers.*` direkt (im Test: `StandardTestDispatcher` + `Dispatchers.setMain`).
- **PII-Regel:** NIE Prompt, Antworttext, Koordinaten oder Zieladresse in `LogBuffer` — nur Stufen-Outcomes/Typnamen/Längen.
- i18n: JEDER neue UI-String in `res/values/strings_assistant.xml` (EN) UND `res/values-de/strings_assistant.xml` (DE), zeilenparallel, ohne XML-Kommentare (Datei-Konvention), Apostrophe als `\'`, Anführungszeichen als `\"`.
- `gradlew.bat :app:lint` ist durch einen bekannten Tooling-Bug (Compose-Lint + Kotlin 2.0.21, IncompatibleClassChangeError) kaputt — NICHT ausführen, i18n-Parität manuell prüfen.
- Bestehende `LlmTurnRunnerTest`-Testmethoden dürfen NICHT verändert werden (Beleg der Verhaltensäquivalenz). Einzige erlaubte Änderung dort: die Konstruktor-Zeile (+ ein Import) für den neuen `ToolCallExecutor`-Parameter.
- Commits ohne `Co-Authored-By`-Zeile (Projektkonvention).
- Kein Room-/Manifest-/DI-Modul-Change nötig; falls einer nötig erscheint: STOPP, Spec prüfen.

---

### Task 1: `ToolCallExecutor` extrahieren (Loop aus `LlmTurnRunner`)

**Files:**
- Create: `app/src/main/java/io/github/lycheeappf/tmm/channel/llm/tools/ToolCallExecutor.kt`
- Modify: `app/src/main/java/io/github/lycheeappf/tmm/channel/llm/LlmTurnRunner.kt`
- Modify (nur Konstruktion): `app/src/test/java/io/github/lycheeappf/tmm/channel/llm/LlmTurnRunnerTest.kt`
- Test: `app/src/test/java/io/github/lycheeappf/tmm/channel/llm/tools/ToolCallExecutorTest.kt`

**Interfaces:**
- Consumes: `ToolRegistry.invoke(name, JsonObject)`, `LogBuffer`, `LlmRequest.inFlightToolCalls/-Results`, `LlmResponse.toolCalls`, `ToolInvocationResult.toOutputString()` (internal, gleiches Package).
- Produces (spätere Tasks verlassen sich exakt hierauf):
  ```kotlin
  @Singleton class ToolCallExecutor @Inject constructor(toolRegistry: ToolRegistry, logBuffer: LogBuffer)
  data class ToolCallExecutor.ToolStep(val call: ToolCall, val result: ToolInvocationResult)
  data class ToolCallExecutor.ToolLoopResult(val finalResponse: LlmResponse, val steps: List<ToolStep>)
  suspend fun run(initialRequest: LlmRequest, initialResponse: LlmResponse, complete: suspend (LlmRequest) -> LlmResponse): ToolLoopResult
  ToolCallExecutor.MAX_TOOL_ITERATIONS == 3  // internal
  ```

- [ ] **Step 1: Failing Test schreiben**

Neue Datei `app/src/test/java/io/github/lycheeappf/tmm/channel/llm/tools/ToolCallExecutorTest.kt`:

```kotlin
package io.github.lycheeappf.tmm.channel.llm.tools

import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.channel.llm.provider.LlmProviderError
import io.github.lycheeappf.tmm.channel.llm.provider.LlmRequest
import io.github.lycheeappf.tmm.channel.llm.provider.LlmResponse
import io.github.lycheeappf.tmm.channel.llm.provider.ToolCall
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class ToolCallExecutorTest {

    private val toolRegistry: ToolRegistry = mockk(relaxed = true)
    private val logBuffer: LogBuffer = mockk(relaxed = true)
    private val executor = ToolCallExecutor(toolRegistry, logBuffer)

    private fun request() = LlmRequest(
        model = "grok-4.3", systemPrompt = "Sys", history = emptyList(),
        userMessage = "hi", tools = emptyList(), maxTokens = 256, temperature = 0f
    )

    private fun response(toolCalls: List<ToolCall> = emptyList(), content: String? = null) =
        LlmResponse(
            content = content, toolCalls = toolCalls,
            finishReason = if (toolCalls.isEmpty()) "stop" else "tool_calls",
            usage = null, responseId = null
        )

    @Test fun `no tool calls returns initial response untouched and never completes`() = runTest {
        val initial = response(content = "Fertig")
        val result = executor.run(request(), initial) {
            throw AssertionError("complete darf ohne Tool-Calls nicht aufgerufen werden")
        }
        assertThat(result.finalResponse).isSameInstanceAs(initial)
        assertThat(result.steps).isEmpty()
    }

    @Test fun `executes tool and feeds result into follow-up request`() = runTest {
        val toolOutput = """{"status":"ok","destination":"Alexanderplatz"}"""
        coEvery { toolRegistry.invoke("tesla_navigate", any()) } returns
            ToolInvocationResult.Success(toolOutput)
        val initial = response(
            toolCalls = listOf(ToolCall("c1", "tesla_navigate", """{"address":"Alexanderplatz"}"""))
        )
        val followUps = mutableListOf<LlmRequest>()

        val result = executor.run(request(), initial) { req ->
            followUps += req
            response(content = "Ich navigiere dich.")
        }

        assertThat(result.finalResponse.content).isEqualTo("Ich navigiere dich.")
        assertThat(result.steps).hasSize(1)
        assertThat(result.steps[0].call.name).isEqualTo("tesla_navigate")
        assertThat(result.steps[0].result).isEqualTo(ToolInvocationResult.Success(toolOutput))
        assertThat(followUps).hasSize(1)
        assertThat(followUps[0].inFlightToolCalls).hasSize(1)
        assertThat(followUps[0].inFlightToolResults).hasSize(1)
        assertThat(followUps[0].inFlightToolResults[0].callId).isEqualTo("c1")
        assertThat(followUps[0].inFlightToolResults[0].output).isEqualTo(toolOutput)
    }

    @Test fun `stops after three iterations even if the model keeps calling tools`() = runTest {
        coEvery { toolRegistry.invoke(any(), any()) } returns ToolInvocationResult.Success("{}")
        var completions = 0
        val toolResponse = response(toolCalls = listOf(ToolCall("c", "t", "{}")))

        val result = executor.run(request(), toolResponse) {
            completions++
            toolResponse
        }

        assertThat(completions).isEqualTo(3)
        assertThat(result.steps).hasSize(3)
        assertThat(result.finalResponse.toolCalls).isNotEmpty()
    }

    @Test fun `malformed arguments fall back to empty json object`() = runTest {
        val args = slot<JsonObject>()
        coEvery { toolRegistry.invoke("t", capture(args)) } returns ToolInvocationResult.Success("{}")
        val initial = response(toolCalls = listOf(ToolCall("c1", "t", "NOT-JSON")))

        executor.run(request(), initial) { response(content = "ok") }

        assertThat(args.captured).isEmpty()
    }

    @Test fun `arguments are parsed and passed to the registry`() = runTest {
        val args = slot<JsonObject>()
        coEvery { toolRegistry.invoke("tesla_navigate", capture(args)) } returns
            ToolInvocationResult.Success("{}")
        val initial = response(
            toolCalls = listOf(ToolCall("c1", "tesla_navigate", """{"address":"Alexanderplatz"}"""))
        )

        executor.run(request(), initial) { response(content = "ok") }

        assertThat(args.captured["address"]?.jsonPrimitive?.content).isEqualTo("Alexanderplatz")
    }

    @Test fun `multiple tool calls in one response are all executed and returned in order`() = runTest {
        coEvery { toolRegistry.invoke("a", any()) } returns ToolInvocationResult.Success("""{"r":1}""")
        coEvery { toolRegistry.invoke("b", any()) } returns ToolInvocationResult.Failure("nope")
        val initial = response(
            toolCalls = listOf(ToolCall("c1", "a", "{}"), ToolCall("c2", "b", "{}"))
        )
        val followUps = mutableListOf<LlmRequest>()

        val result = executor.run(request(), initial) { req ->
            followUps += req
            response(content = "done")
        }

        assertThat(result.steps.map { it.call.id }).containsExactly("c1", "c2").inOrder()
        assertThat(followUps[0].inFlightToolResults.map { it.callId })
            .containsExactly("c1", "c2").inOrder()
        // Failure wird als error-JSON zurückgesendet (toOutputString-Vertrag).
        assertThat(followUps[0].inFlightToolResults[1].output).contains("nope")
        coVerify(exactly = 1) { toolRegistry.invoke("a", any()) }
        coVerify(exactly = 1) { toolRegistry.invoke("b", any()) }
    }

    @Test fun `provider error from complete propagates unchanged`() = runTest {
        coEvery { toolRegistry.invoke(any(), any()) } returns ToolInvocationResult.Success("{}")
        val initial = response(toolCalls = listOf(ToolCall("c1", "t", "{}")))

        var thrown: Throwable? = null
        try {
            executor.run(request(), initial) { throw LlmProviderError.Server(503, null) }
        } catch (e: LlmProviderError) {
            thrown = e
        }
        assertThat(thrown).isInstanceOf(LlmProviderError.Server::class.java)
    }

    @Test fun `inflight items accumulate across iterations`() = runTest {
        coEvery { toolRegistry.invoke(any(), any()) } returns ToolInvocationResult.Success("{}")
        val followUps = mutableListOf<LlmRequest>()
        var round = 0

        executor.run(request(), response(toolCalls = listOf(ToolCall("c1", "t", "{}")))) { req ->
            followUps += req
            round++
            if (round == 1) response(toolCalls = listOf(ToolCall("c2", "t", "{}")))
            else response(content = "done")
        }

        assertThat(followUps).hasSize(2)
        assertThat(followUps[0].inFlightToolCalls.map { it.id }).containsExactly("c1")
        assertThat(followUps[1].inFlightToolCalls.map { it.id }).containsExactly("c1", "c2").inOrder()
        assertThat(followUps[1].inFlightToolResults.map { it.callId }).containsExactly("c1", "c2").inOrder()
    }
}
```

(Konstruktoren gegen `LlmProviderError.kt` verifiziert: `Server(val code: Int, val body: String?)`, `RateLimit(val retryAfterSec: Int?)`, `Auth(detail: String)`, `Network(cause: Throwable)`, `MissingKey()`, `Parse(detail: String)`, `data object NoNetwork`.)

- [ ] **Step 2: Test laufen lassen — muss ROT sein (Klasse existiert nicht)**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.channel.llm.tools.ToolCallExecutorTest"`
Expected: FAIL (Compile-Fehler: `Unresolved reference: ToolCallExecutor`)

- [ ] **Step 3: `ToolCallExecutor` implementieren**

Neue Datei `app/src/main/java/io/github/lycheeappf/tmm/channel/llm/tools/ToolCallExecutor.kt`:

```kotlin
package io.github.lycheeappf.tmm.channel.llm.tools

import io.github.lycheeappf.tmm.channel.llm.provider.LlmRequest
import io.github.lycheeappf.tmm.channel.llm.provider.LlmResponse
import io.github.lycheeappf.tmm.channel.llm.provider.ToolCall
import io.github.lycheeappf.tmm.channel.llm.provider.ToolResult
import io.github.lycheeappf.tmm.core.util.LogBuffer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Geteilter Tool-Execution-Loop (extrahiert aus [io.github.lycheeappf.tmm.channel.llm.LlmTurnRunner],
 * zusätzlich genutzt vom [io.github.lycheeappf.tmm.channel.llm.GrokSelfTester]): führt die
 * `function_calls` einer Response aus, hängt Call+Result als In-Flight-Items an den Request
 * und holt via [run]s `complete`-Callback die Folge-Response — bis eine Text-Antwort kommt
 * oder [MAX_TOOL_ITERATIONS] erreicht ist.
 *
 * Bewusst NUR der Loop: Formatter, Blank-Fallback, Rate-Limit-Refunds und History-Persist
 * bleiben beim Aufrufer. Fehler aus `complete` propagieren unverändert (kein Catch hier).
 */
@Singleton
class ToolCallExecutor @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val logBuffer: LogBuffer
) {

    /** Ein ausgeführter Tool-Call samt Ergebnis — für Auswertung durch den Aufrufer. */
    data class ToolStep(val call: ToolCall, val result: ToolInvocationResult)

    data class ToolLoopResult(val finalResponse: LlmResponse, val steps: List<ToolStep>)

    suspend fun run(
        initialRequest: LlmRequest,
        initialResponse: LlmResponse,
        complete: suspend (LlmRequest) -> LlmResponse
    ): ToolLoopResult {
        var currentReq = initialRequest
        var currentResponse = initialResponse
        val steps = mutableListOf<ToolStep>()
        var toolIterations = 0
        while (currentResponse.toolCalls.isNotEmpty() && toolIterations < MAX_TOOL_ITERATIONS) {
            val results = currentResponse.toolCalls.map { call ->
                val args = runCatching {
                    Json.parseToJsonElement(call.argumentsJson).jsonObject
                }.getOrDefault(buildJsonObject {})
                val out = toolRegistry.invoke(call.name, args)
                logBuffer.info(TAG, "Tool '${call.name}' → ${out::class.simpleName}")
                steps += ToolStep(call, out)
                ToolResult(callId = call.id, output = out.toOutputString())
            }
            currentReq = currentReq.copy(
                inFlightToolCalls = currentReq.inFlightToolCalls + currentResponse.toolCalls,
                inFlightToolResults = currentReq.inFlightToolResults + results
            )
            currentResponse = complete(currentReq)
            toolIterations++
        }
        return ToolLoopResult(finalResponse = currentResponse, steps = steps.toList())
    }

    companion object {
        private const val TAG = "ToolCallExecutor"
        internal const val MAX_TOOL_ITERATIONS = 3
    }
}
```

- [ ] **Step 4: Executor-Tests grün laufen lassen**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.channel.llm.tools.ToolCallExecutorTest"`
Expected: PASS (8 Tests)

- [ ] **Step 5: `LlmTurnRunner` auf den Executor umstellen**

In `app/src/main/java/io/github/lycheeappf/tmm/channel/llm/LlmTurnRunner.kt`:

(a) Imports: `kotlinx.serialization.json.Json`, `kotlinx.serialization.json.jsonObject`, `io.github.lycheeappf.tmm.channel.llm.provider.ToolResult` und `io.github.lycheeappf.tmm.channel.llm.tools.toOutputString` ENTFERNEN; `io.github.lycheeappf.tmm.channel.llm.tools.ToolCallExecutor` HINZUFÜGEN. (`ToolInvocationResult` bleibt — wird für `anyToolSucceeded` gebraucht.)

(b) Konstruktor: neuen Parameter direkt nach `toolRegistry` einfügen:

```kotlin
    private val toolRegistry: ToolRegistry,
    private val toolCallExecutor: ToolCallExecutor,
    private val locationProvider: LocationProvider,
```

(c) Den Block von `var currentReq = req` bis einschließlich `val response: LlmResponse = currentResponse` (aktuell Zeilen 104–157) ERSETZEN durch:

```kotlin
            val initialResponse = try {
                provider.complete(req)
            } catch (e: LlmProviderError) {
                // Nur Typ-Name loggen — Provider-Error-Messages können Body-Fragmente
                // (= ggf. Diktatinhalt) enthalten und gehören nicht in den LogBuffer.
                logBuffer.warn(TAG, "Provider error: ${e::class.simpleName}")
                // Transientes Failure → Rate-Limit-Slot refunden, sodass flakiges Netz
                // nicht das User-Limit aufbraucht.
                rateLimiter.refund(mappingId)
                return@withLock TurnResult.ProviderFailed(e)
            } catch (e: Exception) {
                // CancellationException NIE in ProviderFailed wrappen — sonst bricht
                // structured concurrency und der Lifecycle (z.B. App-Stop) wird vom
                // Channel als "Grok-Server-Fehler" an den User vorgelesen.
                if (e is kotlinx.coroutines.CancellationException) throw e
                logBuffer.error(TAG, "Unexpected provider exception: ${e::class.simpleName}")
                rateLimiter.refund(mappingId)
                return@withLock TurnResult.ProviderFailed(LlmProviderError.Network(e))
            }

            // Tool-Execution-Loop (geteilt mit dem Grok-Selbsttest, siehe ToolCallExecutor).
            val loop = try {
                toolCallExecutor.run(req, initialResponse) { provider.complete(it) }
            } catch (e: LlmProviderError) {
                logBuffer.warn(TAG, "Provider error in tool loop: ${e::class.simpleName}")
                rateLimiter.refund(mappingId)
                return@withLock TurnResult.ProviderFailed(e)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                logBuffer.error(TAG, "Unexpected exception in tool loop: ${e::class.simpleName}")
                rateLimiter.refund(mappingId)
                return@withLock TurnResult.ProviderFailed(LlmProviderError.Network(e))
            }
            val response: LlmResponse = loop.finalResponse
            val anyToolSucceeded = loop.steps.any { it.result is ToolInvocationResult.Success }
```

(d) Im `companion object` die Zeile `private const val MAX_TOOL_ITERATIONS = 3` ENTFERNEN (lebt jetzt im Executor).

(e) In `app/src/test/java/io/github/lycheeappf/tmm/channel/llm/LlmTurnRunnerTest.kt` NUR die Runner-Konstruktion + Import anpassen (Testmethoden unangetastet):

```kotlin
import io.github.lycheeappf.tmm.channel.llm.tools.ToolCallExecutor
```

```kotlin
        runner = LlmTurnRunner(
            context, store, provider, prefs, limiter, formatter, toolRegistry,
            ToolCallExecutor(toolRegistry, logBuffer), locationProvider, logBuffer
        ) { 1_000L }
```

- [ ] **Step 6: Verhaltensäquivalenz belegen — alle LLM-Tests grün**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.channel.llm.*"`
Expected: PASS, insbesondere alle 17 bestehenden `LlmTurnRunnerTest`-Tests unverändert grün.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/github/lycheeappf/tmm/channel/llm/tools/ToolCallExecutor.kt app/src/main/java/io/github/lycheeappf/tmm/channel/llm/LlmTurnRunner.kt app/src/test/java/io/github/lycheeappf/tmm/channel/llm/tools/ToolCallExecutorTest.kt app/src/test/java/io/github/lycheeappf/tmm/channel/llm/LlmTurnRunnerTest.kt
git commit -m "refactor(llm): extract tool-execution loop into shared ToolCallExecutor"
```

---

### Task 2: Selbsttest-Modelle + Auswertungs-Funktionen + geteiltes Outcome-Mapping

**Files:**
- Create: `app/src/main/java/io/github/lycheeappf/tmm/channel/llm/SelfTestModels.kt`
- Create: `app/src/main/java/io/github/lycheeappf/tmm/channel/llm/SelfTestEvaluation.kt`
- Modify: `app/src/main/java/io/github/lycheeappf/tmm/channel/llm/GrokKeyTester.kt`
- Test: `app/src/test/java/io/github/lycheeappf/tmm/channel/llm/SelfTestEvaluationTest.kt`

**Interfaces:**
- Consumes: `ToolCallExecutor.ToolStep` (Task 1), `LocationFix(latitude: Double, longitude: Double, accuracyInMeters: Float)`, `KeyTestOutcome`, `ToolInvocationResult`.
- Produces (spätere Tasks verlassen sich exakt hierauf):
  ```kotlin
  enum class SelfTestStage { KEY, POSITION, TESLA_LOCAL, E2E }
  sealed class SelfTestEvent { StageRunning(stage) | KeyResult(outcome) | PositionResult(result)
      | TeslaLocalResult(credentialsSet: Boolean, vinSelected: Boolean) | E2eDone(result) | StageSkipped(stage) }
  sealed class PositionLocalResult { Disabled | NoPermission | NoFix | Ok(fix: LocationFix, backgroundGranted: Boolean) }
  sealed class NavCheck { NotCalled | WrongAddress(sent) | CalledOk(sent) | CalledFailed(sent, error) }
  enum class PositionEcho { SKIPPED, NO_CLAUSE, MATCHED, NOT_FOUND }
  sealed class E2eResult { Completed(nav, echo, answer) | ProviderFailed(outcome: KeyTestOutcome) | ConsentMissing | Timeout }
  internal object SelfTestEvaluation {
      const val NAV_TOOL_NAME = "tesla_navigate"; const val COORD_TOLERANCE = 0.02
      fun navCheck(destination: String, steps: List<ToolCallExecutor.ToolStep>): NavCheck
      fun addressMatches(expected: String, sent: String): Boolean
      fun positionEcho(fix: LocationFix?, systemPromptBlank: Boolean, answer: String): PositionEcho
      fun extractNumbers(text: String): List<Double>
  }
  internal fun LlmProviderError.toKeyTestOutcome(): KeyTestOutcome  // top-level in GrokKeyTester.kt
  ```

- [ ] **Step 1: Failing Test schreiben**

Neue Datei `app/src/test/java/io/github/lycheeappf/tmm/channel/llm/SelfTestEvaluationTest.kt`:

```kotlin
package io.github.lycheeappf.tmm.channel.llm

import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.channel.llm.tools.ToolCallExecutor
import io.github.lycheeappf.tmm.channel.llm.tools.ToolInvocationResult
import io.github.lycheeappf.tmm.channel.llm.provider.ToolCall
import io.github.lycheeappf.tmm.platform.location.LocationFix
import org.junit.Test

class SelfTestEvaluationTest {

    private val fix = LocationFix(latitude = 52.5200, longitude = 13.4050, accuracyInMeters = 25f)

    private fun step(
        name: String = "tesla_navigate",
        argumentsJson: String = """{"address":"Alexanderplatz, Berlin"}""",
        result: ToolInvocationResult = ToolInvocationResult.Success("""{"status":"ok"}""")
    ) = ToolCallExecutor.ToolStep(ToolCall("c1", name, argumentsJson), result)

    // ---- addressMatches ------------------------------------------------------

    @Test fun `address matches exactly`() {
        assertThat(SelfTestEvaluation.addressMatches("Alexanderplatz, Berlin", "Alexanderplatz, Berlin")).isTrue()
    }

    @Test fun `address match is case- and punctuation-tolerant`() {
        assertThat(SelfTestEvaluation.addressMatches("Alexanderplatz, Berlin", "alexanderplatz berlin")).isTrue()
    }

    @Test fun `address match accepts a more specific sent address`() {
        assertThat(
            SelfTestEvaluation.addressMatches("Alexanderplatz, Berlin", "Alexanderplatz Berlin 10178 Deutschland")
        ).isTrue()
    }

    @Test fun `different address does not match`() {
        assertThat(SelfTestEvaluation.addressMatches("Alexanderplatz, Berlin", "Hauptbahnhof München")).isFalse()
    }

    @Test fun `blank sent address does not match`() {
        assertThat(SelfTestEvaluation.addressMatches("Alexanderplatz, Berlin", "  ")).isFalse()
    }

    // ---- extractNumbers ------------------------------------------------------

    @Test fun `extracts dot and comma decimals and integers`() {
        assertThat(SelfTestEvaluation.extractNumbers("52.5200° N, 13,405° O bei 25 m"))
            .containsExactly(52.52, 13.405, 25.0).inOrder()
    }

    // ---- navCheck ------------------------------------------------------------

    @Test fun `nav not called when no tesla_navigate step exists`() {
        assertThat(SelfTestEvaluation.navCheck("Alexanderplatz, Berlin", emptyList()))
            .isEqualTo(NavCheck.NotCalled)
    }

    @Test fun `nav called ok when address matches and tool succeeded`() {
        assertThat(SelfTestEvaluation.navCheck("Alexanderplatz, Berlin", listOf(step())))
            .isEqualTo(NavCheck.CalledOk("Alexanderplatz, Berlin"))
    }

    @Test fun `wrong address wins even when the tool call succeeded`() {
        val s = step(argumentsJson = """{"address":"Hauptbahnhof München"}""")
        assertThat(SelfTestEvaluation.navCheck("Alexanderplatz, Berlin", listOf(s)))
            .isEqualTo(NavCheck.WrongAddress("Hauptbahnhof München"))
    }

    @Test fun `tool failure carries the localized error through`() {
        val s = step(result = ToolInvocationResult.Failure("Kein Tesla-Fahrzeug konfiguriert"))
        assertThat(SelfTestEvaluation.navCheck("Alexanderplatz, Berlin", listOf(s)))
            .isEqualTo(NavCheck.CalledFailed("Alexanderplatz, Berlin", "Kein Tesla-Fahrzeug konfiguriert"))
    }

    // ---- positionEcho --------------------------------------------------------

    @Test fun `echo skipped without a fix`() {
        assertThat(SelfTestEvaluation.positionEcho(null, false, "52.52, 13.40"))
            .isEqualTo(PositionEcho.SKIPPED)
    }

    @Test fun `echo no_clause when the system prompt is blank despite a fix`() {
        assertThat(SelfTestEvaluation.positionEcho(fix, true, "52.52, 13.40"))
            .isEqualTo(PositionEcho.NO_CLAUSE)
    }

    @Test fun `echo matched with dot decimals`() {
        assertThat(SelfTestEvaluation.positionEcho(fix, false, "Du bist bei 52.5200° N, 13.4050° O."))
            .isEqualTo(PositionEcho.MATCHED)
    }

    @Test fun `echo matched with german comma decimals and rounding`() {
        assertThat(SelfTestEvaluation.positionEcho(fix, false, "Position: 52,52 Grad Nord, 13,41 Grad Ost"))
            .isEqualTo(PositionEcho.MATCHED)
    }

    @Test fun `echo matched for southern-western fix via abs comparison`() {
        val sw = LocationFix(latitude = -33.8688, longitude = -151.2093, accuracyInMeters = 10f)
        assertThat(SelfTestEvaluation.positionEcho(sw, false, "33.87° S, 151.21° W"))
            .isEqualTo(PositionEcho.MATCHED)
    }

    @Test fun `echo not_found when the reply has no matching pair`() {
        assertThat(SelfTestEvaluation.positionEcho(fix, false, "NO POSITION"))
            .isEqualTo(PositionEcho.NOT_FOUND)
    }
}
```

- [ ] **Step 2: Test laufen lassen — ROT**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.channel.llm.SelfTestEvaluationTest"`
Expected: FAIL (Unresolved references: `SelfTestEvaluation`, `NavCheck`, `PositionEcho`)

- [ ] **Step 3: Modelle + Auswertung implementieren**

Neue Datei `app/src/main/java/io/github/lycheeappf/tmm/channel/llm/SelfTestModels.kt`:

```kotlin
package io.github.lycheeappf.tmm.channel.llm

import io.github.lycheeappf.tmm.platform.location.LocationFix

/**
 * Modelle des Grok-Selbsttests (siehe docs/superpowers/specs/2026-07-10-…-design.md).
 * Bewusst ohne Android-/R-Referenzen — die lokalisierte UI-Fassung entsteht erst an
 * der UI-Grenze im Assistant-Screen.
 */
enum class SelfTestStage { KEY, POSITION, TESLA_LOCAL, E2E }

sealed class SelfTestEvent {
    data class StageRunning(val stage: SelfTestStage) : SelfTestEvent()
    data class KeyResult(val outcome: KeyTestOutcome) : SelfTestEvent()
    data class PositionResult(val result: PositionLocalResult) : SelfTestEvent()
    data class TeslaLocalResult(val credentialsSet: Boolean, val vinSelected: Boolean) : SelfTestEvent()
    data class E2eDone(val result: E2eResult) : SelfTestEvent()
    /** Stufen, die wegen eines früheren Abbruchs (Key ≠ VALID) nicht laufen. */
    data class StageSkipped(val stage: SelfTestStage) : SelfTestEvent()
}

/** Lokaler Positions-Ketten-Check: erstes fehlendes Glied gewinnt. */
sealed class PositionLocalResult {
    data object Disabled : PositionLocalResult()
    data object NoPermission : PositionLocalResult()
    data object NoFix : PositionLocalResult()
    /** [backgroundGranted]=false ⇒ Fix nur im Vordergrund — Warnung, kein Fail. */
    data class Ok(val fix: LocationFix, val backgroundGranted: Boolean) : PositionLocalResult()
}

sealed class NavCheck {
    data object NotCalled : NavCheck()
    /** Tool gerufen, aber Ziel weicht ab — hat Vorrang vor Ok/Failed (falsches Ziel im Auto!). */
    data class WrongAddress(val sent: String) : NavCheck()
    data class CalledOk(val sent: String) : NavCheck()
    /** [error] ist die bereits lokalisierte Meldung aus dem Tool (z. B. TeslaNavigateTool). */
    data class CalledFailed(val sent: String, val error: String) : NavCheck()
}

enum class PositionEcho {
    /** Stufe 2 lieferte keinen Fix → keine Erwartung. */
    SKIPPED,
    /** Fix da, aber System-Prompt bewusst geleert → Klausel wird nie angehängt (Produktionsverhalten). */
    NO_CLAUSE,
    MATCHED,
    NOT_FOUND
}

sealed class E2eResult {
    data class Completed(val nav: NavCheck, val echo: PositionEcho, val answer: String) : E2eResult()
    data class ProviderFailed(val outcome: KeyTestOutcome) : E2eResult()
    /** Privacy-Consent fehlt — es ist KEIN xAI-Call erfolgt. */
    data object ConsentMissing : E2eResult()
    data object Timeout : E2eResult()
}
```

Neue Datei `app/src/main/java/io/github/lycheeappf/tmm/channel/llm/SelfTestEvaluation.kt`:

```kotlin
package io.github.lycheeappf.tmm.channel.llm

import io.github.lycheeappf.tmm.channel.llm.tools.ToolCallExecutor
import io.github.lycheeappf.tmm.channel.llm.tools.ToolInvocationResult
import io.github.lycheeappf.tmm.platform.location.LocationFix
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

/**
 * Reine Auswertungs-Funktionen des Grok-Selbsttests (kein Android, kein I/O) —
 * von [GrokSelfTester] nach dem E2E-Turn aufgerufen, separat testbar.
 */
internal object SelfTestEvaluation {

    const val NAV_TOOL_NAME = "tesla_navigate"

    /** Toleranz beim Koordinaten-Vergleich — die Prompt-Klausel hat 4 Nachkommastellen, Grok rundet gern auf 2. */
    const val COORD_TOLERANCE = 0.02

    /** Obergrenze gescannter Zahlen — schützt vor pathologisch zahlenreichen Antworten. */
    private const val MAX_NUMBERS_SCANNED = 64

    fun navCheck(destination: String, steps: List<ToolCallExecutor.ToolStep>): NavCheck {
        val step = steps.firstOrNull { it.call.name == NAV_TOOL_NAME } ?: return NavCheck.NotCalled
        val sent = runCatching {
            Json.parseToJsonElement(step.call.argumentsJson)
                .jsonObject["address"]?.jsonPrimitive?.content
        }.getOrNull().orEmpty()
        // Zuerst die Adresse: ein falsches Ziel ist auch bei Fleet-Erfolg der primäre Defekt.
        if (!addressMatches(destination, sent)) return NavCheck.WrongAddress(sent)
        return when (val result = step.result) {
            is ToolInvocationResult.Success -> NavCheck.CalledOk(sent)
            is ToolInvocationResult.Failure -> NavCheck.CalledFailed(sent, result.error)
            ToolInvocationResult.NotApplicable -> NavCheck.CalledFailed(sent, "tool not applicable")
        }
    }

    /** Case-/Whitespace-/Interpunktions-tolerant; Substring-Match in beide Richtungen. */
    fun addressMatches(expected: String, sent: String): Boolean {
        val e = normalize(expected)
        val s = normalize(sent)
        if (e.isEmpty() || s.isEmpty()) return false
        return s.contains(e) || e.contains(s)
    }

    fun positionEcho(fix: LocationFix?, systemPromptBlank: Boolean, answer: String): PositionEcho {
        if (fix == null) return PositionEcho.SKIPPED
        if (systemPromptBlank) return PositionEcho.NO_CLAUSE
        val numbers = extractNumbers(answer)
        // Vorzeichen-tolerant: die Prompt-Klausel formatiert abs() + Himmelsrichtung.
        val lat = abs(fix.latitude)
        val lon = abs(fix.longitude)
        for (i in numbers.indices) {
            for (j in numbers.indices) {
                if (i == j) continue
                if (abs(numbers[i] - lat) <= COORD_TOLERANCE &&
                    abs(numbers[j] - lon) <= COORD_TOLERANCE
                ) {
                    return PositionEcho.MATCHED
                }
            }
        }
        return PositionEcho.NOT_FOUND
    }

    /** Dezimalpunkt UND -komma (Grok antwortet ggf. deutsch: „52,52"); Grad-/Richtungszeichen fallen raus. */
    fun extractNumbers(text: String): List<Double> =
        Regex("""\d+(?:[.,]\d+)?""").findAll(text)
            .take(MAX_NUMBERS_SCANNED)
            .mapNotNull { it.value.replace(',', '.').toDoubleOrNull() }
            .toList()

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
}
```

- [ ] **Step 4: Test grün laufen lassen**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.channel.llm.SelfTestEvaluationTest"`
Expected: PASS (16 Tests)

- [ ] **Step 5: `toOutcome()` → geteiltes `toKeyTestOutcome()`**

In `app/src/main/java/io/github/lycheeappf/tmm/channel/llm/GrokKeyTester.kt`: die private Member-Extension `private fun LlmProviderError.toOutcome(): KeyTestOutcome` aus der Klasse herausziehen und als `internal` Top-Level-Funktion ans Datei-Ende setzen (Body unverändert), Aufrufstelle anpassen:

```kotlin
        } catch (e: LlmProviderError) {
            return e.toKeyTestOutcome()
        }
```

```kotlin
/**
 * Mappt einen [LlmProviderError] auf das UI-Vokabular des Key-Tests. Geteilt von
 * [GrokKeyTester] und [GrokSelfTester] — eine Quelle, kein Duplikat.
 */
internal fun LlmProviderError.toKeyTestOutcome(): KeyTestOutcome = when (this) {
    is LlmProviderError.Auth -> KeyTestOutcome.AUTH_ERROR
    is LlmProviderError.MissingKey -> KeyTestOutcome.MISSING_KEY
    is LlmProviderError.NoNetwork -> KeyTestOutcome.NO_NETWORK
    is LlmProviderError.RateLimit -> KeyTestOutcome.RATE_LIMITED
    is LlmProviderError.Network ->
        if (cause is SocketTimeoutException) KeyTestOutcome.TIMEOUT else KeyTestOutcome.NO_NETWORK
    is LlmProviderError.Server -> KeyTestOutcome.SERVER_ERROR
    is LlmProviderError.Parse -> KeyTestOutcome.SERVER_ERROR
}
```

(Der Import `java.net.SocketTimeoutException` existiert in der Datei bereits.)

- [ ] **Step 6: Key-Tester-Tests weiter grün**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.channel.llm.GrokKeyTesterTest"`
Expected: PASS (11 Tests, unverändert)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/github/lycheeappf/tmm/channel/llm/SelfTestModels.kt app/src/main/java/io/github/lycheeappf/tmm/channel/llm/SelfTestEvaluation.kt app/src/main/java/io/github/lycheeappf/tmm/channel/llm/GrokKeyTester.kt app/src/test/java/io/github/lycheeappf/tmm/channel/llm/SelfTestEvaluationTest.kt
git commit -m "feat(llm): add self-test models, evaluation helpers and shared outcome mapping"
```

---

### Task 3: `GrokSelfTester` (Orchestrierung der vier Stufen)

**Files:**
- Create: `app/src/main/java/io/github/lycheeappf/tmm/channel/llm/GrokSelfTester.kt`
- Test: `app/src/test/java/io/github/lycheeappf/tmm/channel/llm/GrokSelfTesterTest.kt`

**Interfaces:**
- Consumes: `GrokKeyTester.run()`, `ToolCallExecutor` (Task 1), Modelle + `SelfTestEvaluation` + `toKeyTestOutcome()` (Task 2), `LlmProvider.complete`, `AssistantPreferencesStore.{isPrivacyConsentGiven, locationContextEnabled, model, systemPrompt}`, `PermissionGate.{hasLocationAccess, hasBackgroundLocationAccess}`, `LocationProvider.lastKnownLocation`, `TeslaAuthManager.hasCredentials()` (suspend), `TeslaTokenStore.readSelectedVin()` (suspend), `ToolRegistry.activeSchemas()`.
- Produces:
  ```kotlin
  @Singleton class GrokSelfTester @Inject constructor(/* 10 Deps, siehe unten */) {
      fun run(destination: String): Flow<SelfTestEvent>
      companion object { internal const val E2E_TIMEOUT_MS = 120_000L; internal const val E2E_MAX_TOKENS = 512
          internal fun testPrompt(destination: String): String }
  }
  ```

- [ ] **Step 1: Failing Test schreiben**

Neue Datei `app/src/test/java/io/github/lycheeappf/tmm/channel/llm/GrokSelfTesterTest.kt`:

```kotlin
package io.github.lycheeappf.tmm.channel.llm

import com.google.common.truth.Truth.assertThat
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
    }
}
```

- [ ] **Step 2: Test laufen lassen — ROT**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.channel.llm.GrokSelfTesterTest"`
Expected: FAIL (`Unresolved reference: GrokSelfTester`)

- [ ] **Step 3: `GrokSelfTester` implementieren**

Neue Datei `app/src/main/java/io/github/lycheeappf/tmm/channel/llm/GrokSelfTester.kt`:

```kotlin
package io.github.lycheeappf.tmm.channel.llm

import io.github.lycheeappf.tmm.channel.llm.provider.LlmProvider
import io.github.lycheeappf.tmm.channel.llm.provider.LlmProviderError
import io.github.lycheeappf.tmm.channel.llm.provider.LlmRequest
import io.github.lycheeappf.tmm.channel.llm.tools.ToolCallExecutor
import io.github.lycheeappf.tmm.channel.llm.tools.ToolRegistry
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.data.store.AssistantPreferencesStore
import io.github.lycheeappf.tmm.data.store.TeslaTokenStore
import io.github.lycheeappf.tmm.platform.location.LocationProvider
import io.github.lycheeappf.tmm.platform.permission.PermissionGate
import io.github.lycheeappf.tmm.platform.tesla.auth.TeslaAuthManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mehrstufiger Grok-Selbsttest (Assistant-Screen): 1. Key-Ping, 2. lokaler
 * Positions-Ketten-Check, 3. lokaler Tesla-Konto-Check, 4. echter End-to-end-Turn
 * (Grok ruft `tesla_navigate` → Fleet API; Koordinaten-Echo-Prüfung).
 *
 * Wie [GrokKeyTester]: keine eigenen R-/Context-Referenzen (Lokalisierung an der
 * UI-Grenze), bewusst am [LlmTurnRunner]/Rate-Limiter/[LlmConversationStore] vorbei —
 * kein Conversation-State, kein Rate-Limit-Verbrauch, kein SMS/Mapping. Der
 * E2E-Turn ist consent-gated (Turn-Zeit-Parität zu [LlmChannel]) und deterministisch
 * (temperature=0, ohne Web-/X-Suche, leere History, feste maxTokens).
 *
 * PII: in den [LogBuffer] gehen nur Stufen-Outcomes/Typnamen/Längen — nie Prompt,
 * Antworttext, Koordinaten oder Zieladresse.
 */
@Singleton
class GrokSelfTester @Inject constructor(
    private val keyTester: GrokKeyTester,
    private val provider: LlmProvider,
    private val prefs: AssistantPreferencesStore,
    private val toolRegistry: ToolRegistry,
    private val toolCallExecutor: ToolCallExecutor,
    private val locationProvider: LocationProvider,
    private val permissionGate: PermissionGate,
    private val teslaAuthManager: TeslaAuthManager,
    private val teslaTokenStore: TeslaTokenStore,
    private val logBuffer: LogBuffer
) {

    fun run(destination: String): Flow<SelfTestEvent> = flow {
        emit(SelfTestEvent.StageRunning(SelfTestStage.KEY))
        val key = keyTester.run()
        emit(SelfTestEvent.KeyResult(key))
        logBuffer.info(TAG, "Self-test key: $key")
        if (key != KeyTestOutcome.VALID) {
            emit(SelfTestEvent.StageSkipped(SelfTestStage.POSITION))
            emit(SelfTestEvent.StageSkipped(SelfTestStage.TESLA_LOCAL))
            emit(SelfTestEvent.StageSkipped(SelfTestStage.E2E))
            return@flow
        }

        emit(SelfTestEvent.StageRunning(SelfTestStage.POSITION))
        val position = positionStage()
        emit(SelfTestEvent.PositionResult(position))
        logBuffer.info(TAG, "Self-test position: ${position::class.simpleName}")

        emit(SelfTestEvent.StageRunning(SelfTestStage.TESLA_LOCAL))
        val credentialsSet = teslaAuthManager.hasCredentials()
        val vinSelected = teslaTokenStore.readSelectedVin() != null
        emit(SelfTestEvent.TeslaLocalResult(credentialsSet, vinSelected))
        logBuffer.info(TAG, "Self-test tesla: credentials=$credentialsSet vin=$vinSelected")

        emit(SelfTestEvent.StageRunning(SelfTestStage.E2E))
        val e2e = e2eStage(destination, position)
        emit(SelfTestEvent.E2eDone(e2e))
        logBuffer.info(
            TAG,
            "Self-test e2e: ${e2e::class.simpleName}" +
                ((e2e as? E2eResult.Completed)?.let {
                    " (nav=${it.nav::class.simpleName}, echo=${it.echo}, answer len=${it.answer.length})"
                } ?: "")
        )
    }

    /** Erstes fehlendes Glied gewinnt: Opt-in → Permission → frischer Fix. */
    private suspend fun positionStage(): PositionLocalResult = when {
        !prefs.locationContextEnabled() -> PositionLocalResult.Disabled
        !permissionGate.hasLocationAccess() -> PositionLocalResult.NoPermission
        else -> locationProvider.lastKnownLocation()?.let { fix ->
            PositionLocalResult.Ok(fix, backgroundGranted = permissionGate.hasBackgroundLocationAccess())
        } ?: PositionLocalResult.NoFix
    }

    private suspend fun e2eStage(destination: String, position: PositionLocalResult): E2eResult {
        // Consent-Gate wie der Produktions-Turn (LlmChannel prüft zur Turn-Zeit):
        // ohne Zustimmung KEIN xAI-Call — sonst würde der Test Nutzerdaten
        // (Zieladresse, ggf. Koordinaten) ohne Consent senden und einen Zustand
        // grün melden, den das Auto ablehnen würde.
        if (!prefs.isPrivacyConsentGiven()) return E2eResult.ConsentMissing

        val fix = (position as? PositionLocalResult.Ok)?.fix
        val systemPrompt = prefs.systemPrompt(webSearch = false, xSearch = false, location = fix)
        val request = LlmRequest(
            model = prefs.model(),
            systemPrompt = systemPrompt,
            history = emptyList(),
            userMessage = testPrompt(destination),
            tools = toolRegistry.activeSchemas(),
            maxTokens = E2E_MAX_TOKENS,
            temperature = 0f,
            webSearch = false,
            xSearch = false
        )
        return try {
            withTimeoutOrNull(E2E_TIMEOUT_MS) {
                val initialResponse = provider.complete(request)
                val loop = toolCallExecutor.run(request, initialResponse) { provider.complete(it) }
                val answer = loop.finalResponse.content.orEmpty()
                E2eResult.Completed(
                    nav = SelfTestEvaluation.navCheck(destination, loop.steps),
                    echo = SelfTestEvaluation.positionEcho(fix, systemPrompt.isBlank(), answer),
                    answer = answer
                )
            } ?: E2eResult.Timeout
        } catch (e: LlmProviderError) {
            E2eResult.ProviderFailed(e.toKeyTestOutcome())
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            logBuffer.error(TAG, "Self-test e2e unexpected: ${e::class.simpleName}")
            E2eResult.ProviderFailed(KeyTestOutcome.UNKNOWN)
        }
    }

    companion object {
        private const val TAG = "GrokSelfTester"

        /** Hartes Gesamt-Cap des E2E-Turns — der Fleet-Pfad kann Wake-up + 15 s Delay + Retry enthalten. */
        internal const val E2E_TIMEOUT_MS = 120_000L

        /** Fest statt User-Setting: ein zu kleines User-maxTokens würde das Koordinaten-Echo abschneiden. */
        internal const val E2E_MAX_TOKENS = 512

        /** Model-facing, bewusst englisch (wie Tool-Descriptions). */
        internal fun testPrompt(destination: String): String =
            "This is an automated integration self-test of the app. Do exactly two things: " +
                "(1) Call the tesla_navigate tool with the destination '$destination' passed exactly " +
                "as written — do not reformat it and do not search the web. " +
                "(2) After the tool call, reply with one short line: if your context contains the " +
                "user's GPS position, repeat its coordinates; otherwise write exactly NO POSITION."
    }
}
```

- [ ] **Step 4: Tests grün laufen lassen**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.channel.llm.GrokSelfTesterTest"`
Expected: PASS (14 Tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/lycheeappf/tmm/channel/llm/GrokSelfTester.kt app/src/test/java/io/github/lycheeappf/tmm/channel/llm/GrokSelfTesterTest.kt
git commit -m "feat(llm): add GrokSelfTester orchestrating key, position, tesla and e2e stages"
```

---

### Task 4: `AssistantViewModel` — Selbsttest-State, Guards, refresh()-Erhalt

**Files:**
- Modify: `app/src/main/java/io/github/lycheeappf/tmm/ui/screen/assistant/AssistantViewModel.kt`
- Test: `app/src/test/java/io/github/lycheeappf/tmm/ui/screen/assistant/AssistantViewModelTest.kt` (erweitern)

**Interfaces:**
- Consumes: `GrokSelfTester.run(destination): Flow<SelfTestEvent>` (Task 3), Modelle aus Task 2.
- Produces (Task 5 verlässt sich exakt hierauf):
  ```kotlin
  data class StageCell<T>(val value: T? = null, val skipped: Boolean = false)
  data class SelfTestUiState(
      val running: Boolean = false,
      val destination: String = SelfTestUiState.DEFAULT_DESTINATION, // "Alexanderplatz, Berlin"
      val key: StageCell<KeyTestOutcome> = StageCell(),
      val position: StageCell<PositionLocalResult> = StageCell(),
      val teslaLocal: StageCell<Pair<Boolean, Boolean>> = StageCell(), // (credentialsSet, vinSelected)
      val e2e: StageCell<E2eResult> = StageCell(),
      val currentStage: SelfTestStage? = null
  )
  AssistantUiState.selfTest: SelfTestUiState
  AssistantViewModel.runSelfTest(); AssistantViewModel.setSelfTestDestination(value: String)
  ```

- [ ] **Step 1: Failing Tests schreiben**

In `app/src/test/java/io/github/lycheeappf/tmm/ui/screen/assistant/AssistantViewModelTest.kt`:

(a) Imports ergänzen:

```kotlin
import io.github.lycheeappf.tmm.channel.llm.E2eResult
import io.github.lycheeappf.tmm.channel.llm.GrokSelfTester
import io.github.lycheeappf.tmm.channel.llm.NavCheck
import io.github.lycheeappf.tmm.channel.llm.PositionEcho
import io.github.lycheeappf.tmm.channel.llm.SelfTestEvent
import io.github.lycheeappf.tmm.channel.llm.SelfTestStage
import io.mockk.verify
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
```

(Bereits vorhandene Imports wie `io.mockk.every`/`io.mockk.coVerify` nicht doppeln; `PositionLocalResult` wird ebenfalls gebraucht: `import io.github.lycheeappf.tmm.channel.llm.PositionLocalResult`.)

(b) Mock-Feld + `viewModel()`-Helper erweitern (selfTester als NEUER Parameter nach `keyTester`):

```kotlin
    private val selfTester = mockk<GrokSelfTester> {
        every { run(any()) } returns emptyFlow()
    }
```

```kotlin
    private fun viewModel() = AssistantViewModel(
        context, prefs, apiKeyStore, keyTester, selfTester, coordinator, contactProvisioner,
        teslaContactResync, permissionGate, dispatcher
    )
```

(c) Neue Tests ans Datei-Ende:

```kotlin
    @Test
    fun `runSelfTest collects stage events into ui state and clears running at the end`() = runTest(dispatcher) {
        every { selfTester.run("Alexanderplatz, Berlin") } returns flowOf(
            SelfTestEvent.StageRunning(SelfTestStage.KEY),
            SelfTestEvent.KeyResult(KeyTestOutcome.VALID),
            SelfTestEvent.StageRunning(SelfTestStage.POSITION),
            SelfTestEvent.PositionResult(PositionLocalResult.Disabled),
            SelfTestEvent.StageRunning(SelfTestStage.TESLA_LOCAL),
            SelfTestEvent.TeslaLocalResult(credentialsSet = true, vinSelected = true),
            SelfTestEvent.StageRunning(SelfTestStage.E2E),
            SelfTestEvent.E2eDone(
                E2eResult.Completed(
                    nav = NavCheck.CalledOk("Alexanderplatz, Berlin"),
                    echo = PositionEcho.SKIPPED,
                    answer = "NO POSITION"
                )
            )
        )
        val vm = viewModel()
        advanceUntilIdle()

        vm.runSelfTest()
        advanceUntilIdle()

        val selfTest = vm.uiState.value.selfTest
        assertThat(selfTest.running).isFalse()
        assertThat(selfTest.currentStage).isNull()
        assertThat(selfTest.key.value).isEqualTo(KeyTestOutcome.VALID)
        assertThat(selfTest.position.value).isEqualTo(PositionLocalResult.Disabled)
        assertThat(selfTest.teslaLocal.value).isEqualTo(true to true)
        assertThat((selfTest.e2e.value as E2eResult.Completed).nav)
            .isEqualTo(NavCheck.CalledOk("Alexanderplatz, Berlin"))
    }

    @Test
    fun `skipped stages are marked skipped not merely empty`() = runTest(dispatcher) {
        every { selfTester.run(any()) } returns flowOf(
            SelfTestEvent.KeyResult(KeyTestOutcome.AUTH_ERROR),
            SelfTestEvent.StageSkipped(SelfTestStage.POSITION),
            SelfTestEvent.StageSkipped(SelfTestStage.TESLA_LOCAL),
            SelfTestEvent.StageSkipped(SelfTestStage.E2E)
        )
        val vm = viewModel()
        advanceUntilIdle()

        vm.runSelfTest()
        advanceUntilIdle()

        val selfTest = vm.uiState.value.selfTest
        assertThat(selfTest.position.skipped).isTrue()
        assertThat(selfTest.teslaLocal.skipped).isTrue()
        assertThat(selfTest.e2e.skipped).isTrue()
        assertThat(selfTest.position.value).isNull()
    }

    @Test
    fun `runSelfTest is a no-op while a run is already in flight`() = runTest(dispatcher) {
        every { selfTester.run(any()) } returns flow {
            emit(SelfTestEvent.StageRunning(SelfTestStage.KEY))
            awaitCancellation()
        }
        val vm = viewModel()
        advanceUntilIdle()

        vm.runSelfTest()
        advanceUntilIdle()
        vm.runSelfTest()
        advanceUntilIdle()

        verify(exactly = 1) { selfTester.run(any()) }
    }

    @Test
    fun `testApiKey is rejected while the self-test runs`() = runTest(dispatcher) {
        every { selfTester.run(any()) } returns flow {
            emit(SelfTestEvent.StageRunning(SelfTestStage.KEY))
            awaitCancellation()
        }
        val vm = viewModel()
        advanceUntilIdle()

        vm.runSelfTest()
        advanceUntilIdle()
        vm.testApiKey()
        advanceUntilIdle()

        coVerify(exactly = 0) { keyTester.run() }
    }

    @Test
    fun `refresh preserves self-test destination and results`() = runTest(dispatcher) {
        every { selfTester.run(any()) } returns flowOf(
            SelfTestEvent.KeyResult(KeyTestOutcome.VALID)
        )
        val vm = viewModel()
        advanceUntilIdle()

        vm.setSelfTestDestination("Brandenburger Tor, Berlin")
        vm.runSelfTest()
        advanceUntilIdle()
        vm.refresh()
        advanceUntilIdle()

        assertThat(vm.uiState.value.selfTest.destination).isEqualTo("Brandenburger Tor, Berlin")
        assertThat(vm.uiState.value.selfTest.key.value).isEqualTo(KeyTestOutcome.VALID)
    }
```

Hinweis: `runSelfTest` nutzt `selfTest.destination` aus dem State — der No-op-Test oben ruft mit Default-Destination; `verify(exactly = 1) { selfTester.run(any()) }` braucht `io.mockk.verify` (Import ggf. ergänzen).

- [ ] **Step 2: Tests laufen lassen — ROT (Compile-Fehler)**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.ui.screen.assistant.AssistantViewModelTest"`
Expected: FAIL (`Unresolved reference` / `No value passed for parameter`)

- [ ] **Step 3: ViewModel implementieren**

In `app/src/main/java/io/github/lycheeappf/tmm/ui/screen/assistant/AssistantViewModel.kt`:

(a) Imports ergänzen:

```kotlin
import io.github.lycheeappf.tmm.channel.llm.E2eResult
import io.github.lycheeappf.tmm.channel.llm.GrokSelfTester
import io.github.lycheeappf.tmm.channel.llm.PositionLocalResult
import io.github.lycheeappf.tmm.channel.llm.SelfTestEvent
import io.github.lycheeappf.tmm.channel.llm.SelfTestStage
```

(b) Direkt unter dem `LocationPermissionLevel`-Enum die neuen UI-State-Typen einfügen:

```kotlin
/** Ergebnis-Zelle einer Selbsttest-Stufe: „übersprungen", „noch nicht gelaufen" (value=null) und „Ergebnis da" sind unterscheidbar. */
data class StageCell<T>(val value: T? = null, val skipped: Boolean = false)

data class SelfTestUiState(
    val running: Boolean = false,
    val destination: String = DEFAULT_DESTINATION,
    val key: StageCell<KeyTestOutcome> = StageCell(),
    val position: StageCell<PositionLocalResult> = StageCell(),
    /** (credentialsSet, vinSelected) — rein lokale Tesla-Info-Stufe. */
    val teslaLocal: StageCell<Pair<Boolean, Boolean>> = StageCell(),
    val e2e: StageCell<E2eResult> = StageCell(),
    val currentStage: SelfTestStage? = null
) {
    companion object {
        const val DEFAULT_DESTINATION = "Alexanderplatz, Berlin"
    }
}
```

(c) `AssistantUiState` um das Feld erweitern (ans Ende der Parameterliste):

```kotlin
    val isSystemPromptCustomized: Boolean = false,
    val selfTest: SelfTestUiState = SelfTestUiState()
```

(d) Konstruktor: `private val selfTester: GrokSelfTester,` direkt NACH `private val keyTester: GrokKeyTester,` einfügen.

(e) `refresh()`: Der Platten-Snapshot darf den Selbsttest-State nie wipen — Resume (z. B. Rückkehr aus dem Permission-Dialog) ruft `refresh()` mitten im Lauf. Das `_uiState.update`-Lambda so ändern, dass BEIDE Zweige auf einer Basis mit übernommenem `selfTest` arbeiten:

```kotlin
            val persisting = persistJobs.values.any { it.isActive }
            _uiState.update { cur ->
                // Selbsttest-State ist rein transient (kein Platten-Backing) —
                // IMMER aus dem aktuellen State übernehmen, sonst wipet jedes
                // Resume Destination/Ergebnisse/running.
                val base = snapshot.copy(selfTest = cur.selfTest)
                if (persisting) {
                    base.copy(
                        driverName = cur.driverName,
                        systemPrompt = cur.systemPrompt,
                        welcomeMessage = cur.welcomeMessage,
                        model = cur.model,
                        contextTtlSeconds = cur.contextTtlSeconds,
                        maxTokens = cur.maxTokens,
                        temperature = cur.temperature,
                        rateLimitPerMin = cur.rateLimitPerMin,
                        rateLimitPerHour = cur.rateLimitPerHour
                    )
                } else {
                    base
                }
            }
```

(f) Methoden-Guards: in `saveApiKey()`, `clearApiKey()` und `testApiKey()` als jeweils ERSTE Zeile (bei `saveApiKey` nach der bestehenden `value.isEmpty()`-Prüfung ist auch ok, aber vor dem `launch`):

```kotlin
        if (_uiState.value.selfTest.running) return
```

(g) Neue Methoden (unter `testApiKey()` einfügen):

```kotlin
    /** Reines UI-State-Update — Destination wird bewusst nicht persistiert. */
    fun setSelfTestDestination(value: String) =
        _uiState.update { it.copy(selfTest = it.selfTest.copy(destination = value)) }

    /**
     * Startet den mehrstufigen Grok-Selbsttest ([GrokSelfTester]) und zeichnet die
     * Stufen-Events live in den State. Methoden-Guard zusätzlich zu den UI-`enabled`-
     * Flags (Buttons können durch State-Latenz doppelt feuern); Key-Test/Save/Remove
     * sind währenddessen gesperrt (Race-Guard, wie bisher Key-Test ↔ Save/Remove).
     */
    fun runSelfTest() {
        val current = _uiState.value
        if (current.selfTest.running || current.keyTestRunning || current.saving) return
        val destination = current.selfTest.destination.trim()
        if (destination.isEmpty()) return
        viewModelScope.launch(ioDispatcher) {
            _uiState.update {
                it.copy(
                    selfTest = it.selfTest.copy(
                        running = true,
                        key = StageCell(),
                        position = StageCell(),
                        teslaLocal = StageCell(),
                        e2e = StageCell(),
                        currentStage = null
                    )
                )
            }
            try {
                selfTester.run(destination).collect { event ->
                    _uiState.update { it.copy(selfTest = it.selfTest.applyEvent(event)) }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // GrokSelfTester fängt erwartbare Fehler selbst — das hier ist der
                // Belt-and-braces-Pfad für Unerwartetes im Flow/Collect.
                _uiState.update {
                    it.copy(
                        selfTest = it.selfTest.copy(
                            e2e = StageCell(E2eResult.ProviderFailed(KeyTestOutcome.UNKNOWN))
                        )
                    )
                }
            }
            _uiState.update { it.copy(selfTest = it.selfTest.copy(running = false, currentStage = null)) }
        }
    }

    private fun SelfTestUiState.applyEvent(event: SelfTestEvent): SelfTestUiState = when (event) {
        is SelfTestEvent.StageRunning -> copy(currentStage = event.stage)
        is SelfTestEvent.KeyResult -> copy(key = StageCell(event.outcome), currentStage = null)
        is SelfTestEvent.PositionResult -> copy(position = StageCell(event.result), currentStage = null)
        is SelfTestEvent.TeslaLocalResult ->
            copy(teslaLocal = StageCell(event.credentialsSet to event.vinSelected), currentStage = null)
        is SelfTestEvent.E2eDone -> copy(e2e = StageCell(event.result), currentStage = null)
        is SelfTestEvent.StageSkipped -> when (event.stage) {
            SelfTestStage.POSITION -> copy(position = StageCell(skipped = true))
            SelfTestStage.TESLA_LOCAL -> copy(teslaLocal = StageCell(skipped = true))
            SelfTestStage.E2E -> copy(e2e = StageCell(skipped = true))
            SelfTestStage.KEY -> this
        }
    }
```

- [ ] **Step 4: Alle ViewModel-Tests grün**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.ui.screen.assistant.AssistantViewModelTest"`
Expected: PASS (10 bestehende + 5 neue Tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/lycheeappf/tmm/ui/screen/assistant/AssistantViewModel.kt app/src/test/java/io/github/lycheeappf/tmm/ui/screen/assistant/AssistantViewModelTest.kt
git commit -m "feat(assistant): wire grok self-test state into AssistantViewModel"
```

---

### Task 5: Strings (EN + DE) + Selbsttest-Card im `AssistantScreen`

**Files:**
- Modify: `app/src/main/res/values/strings_assistant.xml`
- Modify: `app/src/main/res/values-de/strings_assistant.xml`
- Modify: `app/src/main/java/io/github/lycheeappf/tmm/ui/screen/assistant/AssistantScreen.kt`

**Interfaces:**
- Consumes: `SelfTestUiState`/`StageCell` + `runSelfTest`/`setSelfTestDestination` (Task 4), Modelle (Task 2), Komponenten `SettingCard`/`StatusPill(text, status)`/`PrimaryActionButton(text, onClick, enabled, loading)`/`MfsStatus`, bestehendes `keyTestResultUi(outcome)`.
- Produces: nur UI — keine weiteren Konsumenten.

- [ ] **Step 1: Strings EN einfügen**

In `app/src/main/res/values/strings_assistant.xml` direkt NACH der Zeile `<string name="assistant_keytest_error">Test failed</string>` (und vor der Leerzeile + `assistant_websearch_title`) einfügen — eine Leerzeile davor, Konvention „Gruppen durch Leerzeilen, keine Kommentare":

```xml

    <string name="assistant_selftest_title">Grok self-test</string>
    <string name="assistant_selftest_desc">Tests the whole chain without sitting in the car: API key, GPS position, Tesla account, and one real Grok turn that sends a navigation destination to your Tesla via the Fleet API. The car really receives the destination, and the run costs one xAI request.</string>
    <string name="assistant_selftest_destination_label">Test destination</string>
    <string name="assistant_selftest_start">Run self-test</string>
    <string name="assistant_selftest_running">running…</string>
    <string name="assistant_selftest_skipped">skipped</string>
    <string name="assistant_selftest_stage_key">1 · API key</string>
    <string name="assistant_selftest_stage_position">2 · GPS position</string>
    <string name="assistant_selftest_stage_tesla">3 · Tesla account</string>
    <string name="assistant_selftest_stage_nav">4 · Navigation via Grok</string>
    <string name="assistant_selftest_stage_echo">Position reached Grok</string>
    <string name="assistant_selftest_position_ok">Fresh fix available</string>
    <string name="assistant_selftest_position_foreground_only">Fix available — in the car \"Allow all the time\" is required</string>
    <string name="assistant_selftest_position_disabled">Location sharing is off</string>
    <string name="assistant_selftest_position_no_permission">Location permission missing</string>
    <string name="assistant_selftest_position_no_fix">No fresh fix (max. 15 min old)</string>
    <string name="assistant_selftest_position_detail">%1$s (±%2$d m)</string>
    <string name="assistant_selftest_tesla_ok">Account connected, vehicle selected</string>
    <string name="assistant_selftest_tesla_no_credentials">No Tesla account connected</string>
    <string name="assistant_selftest_tesla_no_vehicle">No vehicle selected</string>
    <string name="assistant_selftest_nav_ok">Destination sent to the car</string>
    <string name="assistant_selftest_nav_failed">Navigation failed</string>
    <string name="assistant_selftest_nav_wrong_address">Wrong destination sent</string>
    <string name="assistant_selftest_nav_not_called">Grok did not call the navigation tool</string>
    <string name="assistant_selftest_consent_missing">Consent missing — enable it at the top</string>
    <string name="assistant_selftest_echo_matched">Grok knows the position</string>
    <string name="assistant_selftest_echo_not_found">Position not found in the reply</string>
    <string name="assistant_selftest_echo_no_clause">System prompt is empty — the position clause is never appended</string>
    <string name="assistant_selftest_answer_label">Grok\'s reply: %1$s</string>
```

- [ ] **Step 2: Strings DE zeilenparallel einfügen**

In `app/src/main/res/values-de/strings_assistant.xml` an der IDENTISCHEN Stelle (nach `assistant_keytest_error`):

```xml

    <string name="assistant_selftest_title">Grok-Selbsttest</string>
    <string name="assistant_selftest_desc">Testet die ganze Kette, ohne im Auto zu sitzen: API-Key, GPS-Position, Tesla-Konto und ein echter Grok-Turn, der ein Navigationsziel über die Fleet API an deinen Tesla schickt. Das Auto erhält das Ziel wirklich, und der Lauf kostet eine xAI-Anfrage.</string>
    <string name="assistant_selftest_destination_label">Testziel</string>
    <string name="assistant_selftest_start">Selbsttest starten</string>
    <string name="assistant_selftest_running">läuft…</string>
    <string name="assistant_selftest_skipped">übersprungen</string>
    <string name="assistant_selftest_stage_key">1 · API-Key</string>
    <string name="assistant_selftest_stage_position">2 · GPS-Position</string>
    <string name="assistant_selftest_stage_tesla">3 · Tesla-Konto</string>
    <string name="assistant_selftest_stage_nav">4 · Navigation via Grok</string>
    <string name="assistant_selftest_stage_echo">Position kommt bei Grok an</string>
    <string name="assistant_selftest_position_ok">Frischer Fix vorhanden</string>
    <string name="assistant_selftest_position_foreground_only">Fix vorhanden — im Auto ist \"Immer erlauben\" nötig</string>
    <string name="assistant_selftest_position_disabled">Standort-Übermittlung ist aus</string>
    <string name="assistant_selftest_position_no_permission">Standort-Berechtigung fehlt</string>
    <string name="assistant_selftest_position_no_fix">Kein frischer Fix (max. 15 min alt)</string>
    <string name="assistant_selftest_position_detail">%1$s (±%2$d m)</string>
    <string name="assistant_selftest_tesla_ok">Konto verbunden, Fahrzeug gewählt</string>
    <string name="assistant_selftest_tesla_no_credentials">Kein Tesla-Konto verbunden</string>
    <string name="assistant_selftest_tesla_no_vehicle">Kein Fahrzeug gewählt</string>
    <string name="assistant_selftest_nav_ok">Ziel ans Auto gesendet</string>
    <string name="assistant_selftest_nav_failed">Navigation fehlgeschlagen</string>
    <string name="assistant_selftest_nav_wrong_address">Falsches Ziel gesendet</string>
    <string name="assistant_selftest_nav_not_called">Grok hat das Navigations-Tool nicht aufgerufen</string>
    <string name="assistant_selftest_consent_missing">Einwilligung fehlt — oben aktivieren</string>
    <string name="assistant_selftest_echo_matched">Grok kennt die Position</string>
    <string name="assistant_selftest_echo_not_found">Position nicht in der Antwort gefunden</string>
    <string name="assistant_selftest_echo_no_clause">System-Prompt ist leer — die Positions-Klausel wird nie angehängt</string>
    <string name="assistant_selftest_answer_label">Groks Antwort: %1$s</string>
```

- [ ] **Step 3: Card + Helper in `AssistantScreen.kt` einbauen**

(a) Imports ergänzen:

```kotlin
import io.github.lycheeappf.tmm.channel.llm.E2eResult
import io.github.lycheeappf.tmm.channel.llm.NavCheck
import io.github.lycheeappf.tmm.channel.llm.PositionEcho
import io.github.lycheeappf.tmm.channel.llm.PositionLocalResult
import io.github.lycheeappf.tmm.channel.llm.SelfTestStage
```

(b) Direkt NACH der schließenden `}` der API-Key-`SettingCard` (vor `// ---- Internetzugriff: Web-Suche ----`) die neue Card einfügen:

```kotlin
            // ---- Grok-Selbsttest ----
            SettingCard(
                title = stringResource(R.string.assistant_selftest_title),
                description = stringResource(R.string.assistant_selftest_desc)
            ) {
                OutlinedTextField(
                    value = state.selfTest.destination,
                    onValueChange = viewModel::setSelfTestDestination,
                    label = { Text(stringResource(R.string.assistant_selftest_destination_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                PrimaryActionButton(
                    text = stringResource(R.string.assistant_selftest_start),
                    onClick = { viewModel.runSelfTest() },
                    enabled = state.apiKeyIsSet && state.selfTest.destination.isNotBlank() &&
                        !state.saving && !state.keyTestRunning,
                    loading = state.selfTest.running
                )
                SelfTestStageRow(
                    label = stringResource(R.string.assistant_selftest_stage_key),
                    running = state.selfTest.currentStage == SelfTestStage.KEY,
                    skipped = state.selfTest.key.skipped,
                    ui = state.selfTest.key.value?.let { keyTestResultUi(it) to null }
                )
                SelfTestStageRow(
                    label = stringResource(R.string.assistant_selftest_stage_position),
                    running = state.selfTest.currentStage == SelfTestStage.POSITION,
                    skipped = state.selfTest.position.skipped,
                    ui = state.selfTest.position.value?.let { positionResultUi(it) }
                )
                SelfTestStageRow(
                    label = stringResource(R.string.assistant_selftest_stage_tesla),
                    running = state.selfTest.currentStage == SelfTestStage.TESLA_LOCAL,
                    skipped = state.selfTest.teslaLocal.skipped,
                    ui = state.selfTest.teslaLocal.value?.let { teslaLocalUi(it) }
                )
                SelfTestStageRow(
                    label = stringResource(R.string.assistant_selftest_stage_nav),
                    running = state.selfTest.currentStage == SelfTestStage.E2E,
                    skipped = state.selfTest.e2e.skipped,
                    ui = state.selfTest.e2e.value?.let { navResultUi(it) }
                )
                val completed = state.selfTest.e2e.value as? E2eResult.Completed
                if (completed != null) {
                    SelfTestStageRow(
                        label = stringResource(R.string.assistant_selftest_stage_echo),
                        running = false,
                        skipped = false,
                        ui = echoResultUi(completed.echo)
                    )
                    if (completed.answer.isNotBlank()) {
                        Text(
                            stringResource(R.string.assistant_selftest_answer_label, completed.answer),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
```

(c) Private Helper ans Datei-Ende (neben `keyTestResultUi`). Der `ui`-Parameter ist `(Pill-Text, Pill-Status) + optionaler Detailtext`:

```kotlin
/**
 * Status-Zeile einer Selbsttest-Stufe: Label + Pill (läuft… / übersprungen / Ergebnis)
 * + optionaler Detailtext. `ui` = (Pill-Label+Status) zu Detail — null solange die
 * Stufe noch kein Ergebnis hat.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelfTestStageRow(
    label: String,
    running: Boolean,
    skipped: Boolean,
    ui: Pair<Pair<String, MfsStatus>, String?>?
) {
    Column(verticalArrangement = Arrangement.spacedBy(MfsSpacing.xs)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MfsSpacing.sm),
            itemVerticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            when {
                running -> StatusPill(
                    text = stringResource(R.string.assistant_selftest_running),
                    status = MfsStatus.Info
                )
                skipped -> StatusPill(
                    text = stringResource(R.string.assistant_selftest_skipped),
                    status = MfsStatus.Neutral
                )
                ui != null -> StatusPill(text = ui.first.first, status = ui.first.second)
            }
        }
        ui?.second?.let { detail ->
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun positionResultUi(result: PositionLocalResult): Pair<Pair<String, MfsStatus>, String?> =
    when (result) {
        PositionLocalResult.Disabled ->
            (stringResource(R.string.assistant_selftest_position_disabled) to MfsStatus.Warning) to null
        PositionLocalResult.NoPermission ->
            (stringResource(R.string.assistant_selftest_position_no_permission) to MfsStatus.Warning) to null
        PositionLocalResult.NoFix ->
            (stringResource(R.string.assistant_selftest_position_no_fix) to MfsStatus.Warning) to null
        is PositionLocalResult.Ok -> {
            // Koordinaten sind Nutzdaten für die Sichtkontrolle — Locale.US wie die Prompt-Klausel.
            val coords = String.format(
                java.util.Locale.US, "%.4f, %.4f", result.fix.latitude, result.fix.longitude
            )
            val detail = stringResource(
                R.string.assistant_selftest_position_detail, coords, result.fix.accuracyInMeters.toInt()
            )
            if (result.backgroundGranted) {
                (stringResource(R.string.assistant_selftest_position_ok) to MfsStatus.Success) to detail
            } else {
                (stringResource(R.string.assistant_selftest_position_foreground_only) to MfsStatus.Warning) to detail
            }
        }
    }

@Composable
private fun teslaLocalUi(state: Pair<Boolean, Boolean>): Pair<Pair<String, MfsStatus>, String?> {
    val (credentialsSet, vinSelected) = state
    return when {
        !credentialsSet ->
            (stringResource(R.string.assistant_selftest_tesla_no_credentials) to MfsStatus.Warning) to null
        !vinSelected ->
            (stringResource(R.string.assistant_selftest_tesla_no_vehicle) to MfsStatus.Warning) to null
        else -> (stringResource(R.string.assistant_selftest_tesla_ok) to MfsStatus.Success) to null
    }
}

@Composable
private fun navResultUi(result: E2eResult): Pair<Pair<String, MfsStatus>, String?> = when (result) {
    is E2eResult.Completed -> when (val nav = result.nav) {
        is NavCheck.CalledOk ->
            (stringResource(R.string.assistant_selftest_nav_ok) to MfsStatus.Success) to nav.sent
        is NavCheck.CalledFailed ->
            (stringResource(R.string.assistant_selftest_nav_failed) to MfsStatus.Error) to nav.error
        is NavCheck.WrongAddress ->
            (stringResource(R.string.assistant_selftest_nav_wrong_address) to MfsStatus.Error) to nav.sent
        NavCheck.NotCalled ->
            (stringResource(R.string.assistant_selftest_nav_not_called) to MfsStatus.Warning) to null
    }
    E2eResult.ConsentMissing ->
        (stringResource(R.string.assistant_selftest_consent_missing) to MfsStatus.Error) to null
    E2eResult.Timeout ->
        (stringResource(R.string.assistant_keytest_timeout) to MfsStatus.Warning) to null
    is E2eResult.ProviderFailed -> keyTestResultUi(result.outcome) to null
}

@Composable
private fun echoResultUi(echo: PositionEcho): Pair<Pair<String, MfsStatus>, String?> = when (echo) {
    PositionEcho.MATCHED ->
        (stringResource(R.string.assistant_selftest_echo_matched) to MfsStatus.Success) to null
    PositionEcho.NOT_FOUND ->
        (stringResource(R.string.assistant_selftest_echo_not_found) to MfsStatus.Warning) to null
    PositionEcho.NO_CLAUSE ->
        (stringResource(R.string.assistant_selftest_echo_no_clause) to MfsStatus.Warning) to null
    PositionEcho.SKIPPED ->
        (stringResource(R.string.assistant_selftest_skipped) to MfsStatus.Neutral) to null
}
```

(d) Gegen-Sperre am BESTEHENDEN Key-Test-Button und Save/Remove: in der API-Key-Card die drei `enabled`-Ausdrücke jeweils um `&& !state.selfTest.running` erweitern:

```kotlin
                    PrimaryActionButton(
                        text = stringResource(R.string.assistant_apikey_save),
                        onClick = { viewModel.saveApiKey() },
                        enabled = state.apiKeyDraft.isNotBlank() && !state.keyTestRunning && !state.selfTest.running,
                        loading = state.saving
                    )
                    TextButton(
                        onClick = { viewModel.clearApiKey() },
                        enabled = state.apiKeyIsSet && !state.saving && !state.keyTestRunning && !state.selfTest.running
                    ) { Text(stringResource(R.string.assistant_apikey_remove)) }
```

```kotlin
                    PrimaryActionButton(
                        text = stringResource(R.string.assistant_apikey_test),
                        onClick = { viewModel.testApiKey() },
                        enabled = state.apiKeyIsSet && !state.saving && !state.selfTest.running,
                        loading = state.keyTestRunning
                    )
```

- [ ] **Step 4: Kompilieren + i18n-Parität prüfen**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL, APK unter `app/build/outputs/apk/debug/app-debug.apk`

Manuelle i18n-Parität (Lint ist kaputt, siehe Global Constraints): prüfen, dass BEIDE `strings_assistant.xml` exakt dieselben 30 neuen `assistant_selftest_*`-Keys in derselben Reihenfolge enthalten. PowerShell-Schnellcheck:

```powershell
$en = Select-String -Path "app/src/main/res/values/strings_assistant.xml" -Pattern 'name="(assistant_selftest_[a-z_]+)"' -AllMatches | ForEach-Object { $_.Matches } | ForEach-Object { $_.Groups[1].Value }
$de = Select-String -Path "app/src/main/res/values-de/strings_assistant.xml" -Pattern 'name="(assistant_selftest_[a-z_]+)"' -AllMatches | ForEach-Object { $_.Matches } | ForEach-Object { $_.Groups[1].Value }
if (Compare-Object $en $de -SyncWindow 0) { Write-Output "MISMATCH" } else { Write-Output "OK: $($en.Count) keys parallel" }
```

Expected: `OK: 30 keys parallel`

- [ ] **Step 5: Voller Testlauf**

Run: `gradlew.bat :app:test`
Expected: BUILD SUCCESSFUL, alle Unit-Tests grün (debug + release Varianten)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/res/values/strings_assistant.xml app/src/main/res/values-de/strings_assistant.xml app/src/main/java/io/github/lycheeappf/tmm/ui/screen/assistant/AssistantScreen.kt
git commit -m "feat(assistant): add grok self-test card with staged results (EN+DE)"
```

---

### Task 6: Endabnahme

**Files:** keine neuen — reine Verifikation.

- [ ] **Step 1: Kompletter Test- und Build-Durchlauf**

Run: `gradlew.bat clean :app:test :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (Bei Windows-KSP-Delete-Lock: `gradlew.bat --stop`, dann `app/build` löschen und erneut bauen — bekanntes Umgebungsproblem.)

- [ ] **Step 2: Spec-Abgleich**

Jede Spec-Anforderung gegen den Code prüfen (Checkliste): 4 Stufen + Skips ✓, Consent-Gate ohne xAI-Call ✓, `backgroundGranted`-Warnstufe ✓, `refresh()`-Erhalt ✓, `NO_CLAUSE` ✓, Dezimalkomma-Parsing ✓, `WrongAddress`-Vorrang ✓, Tesla-Info-Stufe lokal ✓, PII-Logging nur Metadaten ✓, Timeout 120 s ✓, `LlmTurnRunnerTest`-Methoden unverändert ✓, Strings EN+DE parallel ✓.

- [ ] **Step 3: Abschlussbericht an den User** (was getestet werden kann, Hinweis: echter Lauf schickt ein Navigationsziel ans Auto und kostet einen xAI-Request).
