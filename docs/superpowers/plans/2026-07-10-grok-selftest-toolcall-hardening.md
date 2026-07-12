# Grok-Selbsttest Tool-Call-Hardening — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Der E2E-Selbsttest (Stufe 4, `GrokSelfTester`) ruft das `tesla_navigate`-Tool deterministisch auf und weist Token-Budget-Truncation als eigenen Befund aus, statt sie fälschlich als „Grok hat das Tool nicht aufgerufen" zu melden.

**Architecture:** Root-Cause-Analyse (5 unabhängige Reviews, 2026-07-10) ergab zwei zusammenwirkende Ursachen: (1) `grok-4.3` ist ein Reasoning-Modell — Reasoning-Tokens zählen gegen `max_output_tokens=512`; bei Budget-Erschöpfung liefert die xAI Responses API `status:"incomplete"` mit einem reasoning-only `output` (kein `function_call`, kein `message`), was die App nicht parst und als „sauberes NotCalled" fehlinterpretiert. (2) Es wird kein `tool_choice` gesendet (Default „auto") und Tool-Description + System-Prompt konditionieren den Call auf Fahrer-Intent, den das „automated integration self-test"-Framing des Test-Prompts wörtlich nicht bedient. Fixes: `tool_choice="required"` im ersten Call (Reset im Follow-up), `E2E_MAX_TOKENS=4096`, Top-Level-`status`/`incomplete_details` parsen + neuer `E2eResult.Truncated`, Test-Prompt als Fahrer-Nav-Anfrage framen, Tool-Description-Trigger erweitern, PII-sicheres Response-Metadaten-Logging.

**Tech Stack:** Kotlin, kotlinx.serialization, Retrofit/MockWebServer, JUnit4 + Truth + MockK + coroutines-test. Modul `:app`, Branch `nightly`.

## Global Constraints

- `CancellationException` IMMER rethrown, nie wrappen (`coRunCatching`-Konvention).
- NIE Message-Bodies/Prompts/Antworttexte/Koordinaten/Zieladressen in `LogBuffer` loggen — nur Outcomes/Typnamen/Zähler/Längen (LogBuffer wird via DiagnosticsExporter exportiert).
- i18n: `res/values/strings_assistant.xml` (EN, Default-Locale) und `res/values-de/strings_assistant.xml` MÜSSEN zeilenparallel bleiben — neue Keys in beiden Dateien an derselben Position einfügen.
- Kein `Dispatchers.*` direkt — nur injizierte Qualifier (in diesem Plan nicht nötig).
- Windows: IMMER `gradlew.bat`, nie system-gradle. NIEMALS `gradlew.bat :app:lint` ausführen (bekannter Tooling-Crash, Memory `gradle-lint-crashes-tooling-bug`).
- Test-Namen sind Backtick-Sätze (`` `does x when y` ``). Tests spiegeln den main-Paketbaum unter `app/src/test/java`.
- xAI `tool_choice`: NUR die string-Form `"required"` verwenden (dokumentiert-sicher). Die Objekt-Form für benannte Funktionen ist für xAI `/v1/responses` unverifiziert. KEIN `reasoning`-Feld senden (Support für grok-4.3 unverifiziert, 400-Risiko).
- Konstante `FINISH_REASON_INCOMPLETE = "incomplete"` ist der provider-agnostische Vertrag zwischen `GrokProvider` und `GrokSelfTester` — exakt diesen Namen/Wert verwenden.
- Kommentar-Stil: gemischt Deutsch/Englisch wie die umgebenden Dateien; model-facing Strings (Prompts, Tool-Descriptions) bewusst Englisch.
- Commits ohne Co-Authored-By-Trailer (Memory `no-ai-provenance-in-commits`).

---

### Task 1: `tool_choice`-Request-Seam + Top-Level-Status-Parsing im Grok-Provider

**Files:**
- Modify: `app/src/main/java/io/github/lycheeappf/tmm/channel/llm/provider/grok/GrokDtos.kt`
- Modify: `app/src/main/java/io/github/lycheeappf/tmm/channel/llm/provider/LlmRequest.kt`
- Modify: `app/src/main/java/io/github/lycheeappf/tmm/channel/llm/provider/LlmResponse.kt`
- Modify: `app/src/main/java/io/github/lycheeappf/tmm/channel/llm/provider/grok/GrokProvider.kt`
- Test: `app/src/test/java/io/github/lycheeappf/tmm/channel/llm/provider/grok/GrokDtosTest.kt`
- Test: `app/src/test/java/io/github/lycheeappf/tmm/channel/llm/provider/grok/GrokProviderTest.kt`

**Interfaces:**
- Consumes: bestehende DTO-/Provider-Strukturen (siehe Dateien).
- Produces: `LlmRequest.toolChoice: String? = null` (neues optionales Feld, Default null → Verhalten unverändert); Top-Level-Konstante `FINISH_REASON_INCOMPLETE = "incomplete"` in `LlmResponse.kt` (Paket `io.github.lycheeappf.tmm.channel.llm.provider`); `LlmResponse.finishReason == FINISH_REASON_INCOMPLETE` wenn die xAI-Response `status:"incomplete"` trägt. Task 2 und Task 4 hängen von beidem ab.

- [ ] **Step 1: Failing Tests schreiben**

In `GrokDtosTest.kt` am Ende der Klasse ergänzen:

```kotlin
    @Test fun `request serializes tool_choice when set`() {
        val req = ResponsesRequest(
            model = "grok-4.3",
            input = listOf(ResponsesInputItem(role = "user", content = "Hi")),
            toolChoice = "required"
        )
        val text = json.encodeToString(ResponsesRequest.serializer(), req)
        assertThat(text).contains("\"tool_choice\":\"required\"")
    }

    @Test fun `request without tool_choice omits the field`() {
        val req = ResponsesRequest(
            model = "grok-4.3",
            input = listOf(ResponsesInputItem(role = "user", content = "Hi"))
        )
        val text = json.encodeToString(ResponsesRequest.serializer(), req)
        assertThat(text).doesNotContain("tool_choice")
    }

    @Test fun `response parses top-level status and incomplete_details and reasoning token usage`() {
        val raw = """
            {"id":"resp_i","status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},
             "output":[{"type":"reasoning"}],
             "usage":{"input_tokens":900,"output_tokens":512,"output_tokens_details":{"reasoning_tokens":512}}}
        """.trimIndent()
        val r = json.decodeFromString(ResponsesResponse.serializer(), raw)
        assertThat(r.status).isEqualTo("incomplete")
        assertThat(r.incompleteDetails?.reason).isEqualTo("max_output_tokens")
        assertThat(r.usage?.outputTokensDetails?.reasoningTokens).isEqualTo(512)
    }
```

In `GrokProviderTest.kt` am Ende der Klasse ergänzen (Import `io.github.lycheeappf.tmm.channel.llm.provider.FINISH_REASON_INCOMPLETE` hinzufügen; `buildJsonObject`/`put` sind bereits importiert):

```kotlin
    // ---- Truncation-Sichtbarkeit + tool_choice ----

    @Test fun `incomplete reasoning-only response maps to finishReason incomplete with no tool calls`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"resp_i","status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[{"type":"reasoning"}]}"""
            )
        )
        val response = provider.complete(sampleRequest())
        assertThat(response.finishReason).isEqualTo(FINISH_REASON_INCOMPLETE)
        assertThat(response.toolCalls).isEmpty()
        assertThat(response.content).isNull()
    }

    @Test fun `completed response keeps message-item finish reason`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"r","status":"completed","output":[{"type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"ok"}]}]}"""
            )
        )
        val response = provider.complete(sampleRequest())
        assertThat(response.finishReason).isEqualTo("completed")
    }

    @Test fun `tool_choice is sent only when tools are present`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"r","output_text":"ok"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"r","output_text":"ok"}"""))
        val schema = ToolSchema(
            name = "t", description = "d",
            parametersJson = buildJsonObject { put("type", "object") }
        )
        provider.complete(sampleRequest().copy(tools = listOf(schema), toolChoice = "required"))
        assertThat(server.takeRequest().body.readUtf8()).contains("\"tool_choice\":\"required\"")
        // Ohne Tools wäre tool_choice ein API-Fehler — Feld muss wegfallen.
        provider.complete(sampleRequest().copy(toolChoice = "required"))
        assertThat(server.takeRequest().body.readUtf8()).doesNotContain("tool_choice")
    }
```

- [ ] **Step 2: Tests laufen lassen — müssen FEHLSCHLAGEN (Compile-Error: unbekannte Parameter/Felder)**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.channel.llm.provider.grok.*"`
Expected: FAIL (unresolved reference `toolChoice`, `status`, `incompleteDetails`, `outputTokensDetails`, `FINISH_REASON_INCOMPLETE`)

- [ ] **Step 3: DTOs erweitern**

In `GrokDtos.kt`, `ResponsesRequest` ersetzen durch:

```kotlin
@Serializable
data class ResponsesRequest(
    val model: String,
    val input: List<ResponsesInputItem>,
    // xAI Responses API verwendet `max_output_tokens` (nicht `max_completion_tokens`
    // wie die Chat-Completions-API). Falsche Bezeichnung führt zu HTTP 400.
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
    val temperature: Double? = null,
    @SerialName("previous_response_id") val previousResponseId: String? = null,
    val store: Boolean = false,
    val stream: Boolean = false,
    val tools: List<ResponsesTool>? = null,
    // String-Form der Responses API: weggelassen = Server-Default "auto",
    // "required" = Modell MUSS mindestens ein Tool callen. Die Objekt-Form für
    // eine benannte Funktion ist für xAI /v1/responses unverifiziert — nicht nutzen.
    @SerialName("tool_choice") val toolChoice: String? = null,
    // Steuert optionale Response-Bestandteile. Bei aktivem server-seitigem
    // web_search/x_search setzen wir `["no_inline_citations"]`, damit Grok keine
    // `[[1]](url)`-Zitatmarker in den Text webt (die das Tesla-TTS vorlesen würde).
    val include: List<String>? = null
)
```

`ResponsesResponse` ersetzen durch (plus neue Klasse `ResponsesIncompleteDetails` direkt darunter):

```kotlin
@Serializable
data class ResponsesResponse(
    val id: String? = null,
    val model: String? = null,
    // "completed" | "in_progress" | "incomplete". Bei "incomplete" (Reasoning hat
    // `max_output_tokens` aufgebraucht) kann `output` NUR reasoning-Items enthalten —
    // kein message-, kein function_call-Item. Ohne dieses Feld ist der Fall von
    // „Modell hat nichts gesagt und nichts gecallt" nicht unterscheidbar.
    val status: String? = null,
    @SerialName("incomplete_details") val incompleteDetails: ResponsesIncompleteDetails? = null,
    val output: List<ResponsesOutputItem> = emptyList(),
    val usage: ResponsesUsage? = null,
    @SerialName("output_text") val outputText: String? = null
)

@Serializable
data class ResponsesIncompleteDetails(
    val reason: String? = null // z. B. "max_output_tokens"
)
```

`ResponsesUsage` ersetzen durch (plus neue Klasse darunter):

```kotlin
@Serializable
data class ResponsesUsage(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
    @SerialName("cached_tokens") val cachedTokens: Int? = null,
    @SerialName("output_tokens_details") val outputTokensDetails: ResponsesOutputTokensDetails? = null
)

@Serializable
data class ResponsesOutputTokensDetails(
    @SerialName("reasoning_tokens") val reasoningTokens: Int? = null
)
```

- [ ] **Step 4: `LlmRequest`/`LlmResponse` Seam**

In `LlmRequest.kt` das data class um ein Feld ergänzen (nach `xSearch`, vor `inFlightToolCalls`):

```kotlin
    /**
     * Responses-API `tool_choice` in string-Form: null = Feld weggelassen
     * (Server-Default "auto"), "required" = Modell muss mindestens ein Tool callen.
     * Vom Selbsttest für den ERSTEN Call gesetzt; der ToolCallExecutor setzt es auf
     * Folge-Requests zurück (sonst würde jede Folge-Response erneut einen Call
     * erzwingen, bis MAX_TOOL_ITERATIONS reißt). Produktion sendet null.
     */
    val toolChoice: String? = null,
```

In `LlmResponse.kt` oberhalb von `data class LlmResponse` eine Top-Level-Konstante ergänzen:

```kotlin
/**
 * Provider-agnostischer `finishReason`-Wert: die Antwort wurde vom Token-Budget
 * abgeschnitten (`max_output_tokens` — bei Reasoning-Modellen inkl. Denk-Tokens),
 * bevor Text/Tool-Call fertig emittiert waren. Vertrag zwischen Provider-Mapping
 * und Auswertern (GrokSelfTester).
 */
const val FINISH_REASON_INCOMPLETE = "incomplete"
```

- [ ] **Step 5: `GrokProvider` anpassen**

In `buildRequest` das `return ResponsesRequest(...)` ersetzen durch:

```kotlin
        return ResponsesRequest(
            model = req.model,
            input = items,
            maxOutputTokens = req.maxTokens.takeIf { it > 0 },
            temperature = req.temperature.toDouble().takeIf { it >= 0.0 },
            store = false,
            tools = tools,
            // tool_choice ohne tools wäre ein API-Fehler — nur mit Tool-Liste senden.
            toolChoice = req.toolChoice?.takeIf { tools != null },
            include = include
        )
```

In `mapResponse` die Zeile `val finish = payload.output.firstOrNull { it.type == "message" }?.status ?: "stop"` ersetzen durch:

```kotlin
        // Top-Level-Status VOR dem message-Item-Status prüfen: eine incomplete
        // Response (Reasoning hat das Token-Budget aufgebraucht) hat oft GAR KEIN
        // message-Item — der alte Fallback fabrizierte dann ein irreführendes "stop".
        val finish = if (payload.status == FINISH_REASON_INCOMPLETE) {
            FINISH_REASON_INCOMPLETE
        } else {
            payload.output.firstOrNull { it.type == "message" }?.status ?: "stop"
        }
```

Am Anfang von `mapResponse` (vor `val content = extractText(payload)`) eine PII-sichere Metadaten-Logzeile ergänzen (nur Status/Item-Typen/Token-Zähler — NIE Inhalte):

```kotlin
        logBuffer.info(
            TAG,
            "Response status=${payload.status ?: "-"} items=${payload.output.map { it.type }} " +
                "outTokens=${payload.usage?.outputTokens ?: -1} " +
                "reasoningTokens=${payload.usage?.outputTokensDetails?.reasoningTokens ?: -1}" +
                (payload.incompleteDetails?.reason?.let { " incomplete=$it" } ?: "")
        )
```

Import ergänzen: `io.github.lycheeappf.tmm.channel.llm.provider.FINISH_REASON_INCOMPLETE` (gleiche Package-Gruppe wie die bestehenden provider-Imports).

- [ ] **Step 6: Tests laufen lassen — müssen GRÜN sein (alle bestehenden + neuen)**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.channel.llm.provider.grok.*"`
Expected: PASS (alle bestehenden Tests unverändert grün — Default-Werte ändern kein Serialisierungsverhalten, `explicitNulls=false` lässt null-Felder weg)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/github/lycheeappf/tmm/channel/llm/provider app/src/test/java/io/github/lycheeappf/tmm/channel/llm/provider
git commit -m "feat(llm): add tool_choice request seam and parse top-level response status"
```

---

### Task 2: `ToolCallExecutor` setzt `tool_choice` auf Folge-Requests zurück

**Files:**
- Modify: `app/src/main/java/io/github/lycheeappf/tmm/channel/llm/tools/ToolCallExecutor.kt`
- Test: `app/src/test/java/io/github/lycheeappf/tmm/channel/llm/tools/ToolCallExecutorTest.kt`

**Interfaces:**
- Consumes: `LlmRequest.toolChoice: String?` aus Task 1.
- Produces: Garantie, dass JEDER Folge-Request des Tool-Loops `toolChoice = null` trägt (Task 4 verlässt sich darauf, dass `tool_choice="required"` nur den ersten Call erzwingt).

- [ ] **Step 1: Failing Test schreiben**

In `ToolCallExecutorTest.kt` am Ende der Klasse ergänzen:

```kotlin
    @Test fun `forced tool_choice is cleared on follow-up requests`() = runTest {
        coEvery { toolRegistry.invoke(any(), any()) } returns ToolInvocationResult.Success("{}")
        val followUps = mutableListOf<LlmRequest>()
        val initial = response(toolCalls = listOf(ToolCall("c1", "t", "{}")))

        executor.run(request().copy(toolChoice = "required"), initial) { req ->
            followUps += req
            response(content = "done")
        }

        assertThat(followUps.single().toolChoice).isNull()
    }
```

- [ ] **Step 2: Test laufen lassen — muss FEHLSCHLAGEN**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.channel.llm.tools.ToolCallExecutorTest"`
Expected: FAIL (`toolChoice` ist im Follow-up noch "required")

- [ ] **Step 3: Implementieren**

In `ToolCallExecutor.run` das `currentReq = currentReq.copy(...)` ersetzen durch:

```kotlin
            currentReq = currentReq.copy(
                inFlightToolCalls = currentReq.inFlightToolCalls + currentResponse.toolCalls,
                inFlightToolResults = currentReq.inFlightToolResults + results,
                // Ein erzwungener erster Call (tool_choice="required") darf nicht auf
                // Folge-Requests kleben — sonst MUSS das Modell nach jedem Tool-Result
                // erneut callen, bis MAX_TOOL_ITERATIONS reißt und nie Text kommt.
                toolChoice = null
            )
```

- [ ] **Step 4: Tests laufen lassen — müssen GRÜN sein**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.channel.llm.tools.ToolCallExecutorTest"`
Expected: PASS (alle, inkl. der 9 bestehenden)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/lycheeappf/tmm/channel/llm/tools/ToolCallExecutor.kt app/src/test/java/io/github/lycheeappf/tmm/channel/llm/tools/ToolCallExecutorTest.kt
git commit -m "fix(llm): clear forced tool_choice on tool-loop follow-up requests"
```

---

### Task 3: `E2eResult.Truncated` — Modell, Strings (EN+DE), UI-Zeile

**Files:**
- Modify: `app/src/main/java/io/github/lycheeappf/tmm/channel/llm/SelfTestModels.kt`
- Modify: `app/src/main/res/values/strings_assistant.xml`
- Modify: `app/src/main/res/values-de/strings_assistant.xml`
- Modify: `app/src/main/java/io/github/lycheeappf/tmm/ui/screen/assistant/AssistantScreen.kt`

**Interfaces:**
- Consumes: bestehende `E2eResult`-Hierarchie und `navResultUi`.
- Produces: `E2eResult.Truncated` (`data object`) — Task 4 emittiert diesen Wert. String-Key `assistant_selftest_nav_truncated` in beiden Locales.

Kein neuer Unit-Test: reine Daten-/UI-/Ressourcen-Erweiterung; Prüfkriterien sind Kompilierbarkeit (die exhaustive `when` in `navResultUi` erzwingt den neuen Branch) und i18n-Zeilenparität. Verifikation über `:app:compileDebugKotlin` + bestehende Suite.

- [ ] **Step 1: Modell erweitern**

In `SelfTestModels.kt`, in `sealed class E2eResult` nach dem `Timeout`-Objekt ergänzen:

```kotlin
    /**
     * Der Turn kam mit `finishReason=incomplete` OHNE Nav-Call zurück: das Token-
     * Budget wurde vom Reasoning aufgebraucht, bevor das Tool dran war — KEIN
     * Modell-„Nein" und kein Pipeline-Defekt. (Truncation NACH erfolgreichem
     * Call bleibt Completed; sichtbar über leere Antwort/Echo-Zeile.)
     */
    data object Truncated : E2eResult()
```

- [ ] **Step 2: Strings ergänzen (zeilenparallel!)**

In `app/src/main/res/values/strings_assistant.xml` NACH der Zeile mit `assistant_selftest_nav_not_called` (Zeile 63) einfügen:

```xml
    <string name="assistant_selftest_nav_truncated">Reply hit the token limit before the tool call — run the test again</string>
```

In `app/src/main/res/values-de/strings_assistant.xml` NACH der Zeile mit `assistant_selftest_nav_not_called` (Zeile 63) einfügen:

```xml
    <string name="assistant_selftest_nav_truncated">Antwort am Token-Limit abgeschnitten, bevor das Tool aufgerufen wurde — Test erneut starten</string>
```

- [ ] **Step 3: UI-Branch ergänzen**

In `AssistantScreen.kt`, in `navResultUi` zwischen dem `E2eResult.Timeout`-Branch und `is E2eResult.ProviderFailed` einfügen:

```kotlin
    E2eResult.Truncated ->
        (stringResource(R.string.assistant_selftest_nav_truncated) to MfsStatus.Warning) to null
```

- [ ] **Step 4: Kompilieren + bestehende Tests**

Run: `gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.channel.llm.*" --tests "io.github.lycheeappf.tmm.ui.screen.assistant.*"`
Expected: BUILD SUCCESSFUL, alle Tests grün (niemand produziert Truncated bisher)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/lycheeappf/tmm/channel/llm/SelfTestModels.kt app/src/main/res/values/strings_assistant.xml app/src/main/res/values-de/strings_assistant.xml app/src/main/java/io/github/lycheeappf/tmm/ui/screen/assistant/AssistantScreen.kt
git commit -m "feat(selftest): surface token-budget truncation as its own e2e outcome"
```

---

### Task 4: `GrokSelfTester`-Hardening + Tool-Description-Trigger

**Files:**
- Modify: `app/src/main/java/io/github/lycheeappf/tmm/channel/llm/GrokSelfTester.kt`
- Modify: `app/src/main/java/io/github/lycheeappf/tmm/channel/llm/tools/tesla/TeslaNavigateTool.kt`
- Test: `app/src/test/java/io/github/lycheeappf/tmm/channel/llm/GrokSelfTesterTest.kt`

**Interfaces:**
- Consumes: `LlmRequest.toolChoice` (Task 1), `FINISH_REASON_INCOMPLETE` (Task 1), Executor-Reset-Garantie (Task 2), `E2eResult.Truncated` (Task 3).
- Produces: E2E-Request mit `toolChoice = TOOL_CHOICE_REQUIRED` und `maxTokens = 4096`; `E2eResult.Truncated` wenn `finishReason == FINISH_REASON_INCOMPLETE` und kein `tesla_navigate`-Step lief.

- [ ] **Step 1: Failing Tests schreiben**

In `GrokSelfTesterTest.kt`: Import `io.github.lycheeappf.tmm.channel.llm.provider.FINISH_REASON_INCOMPLETE` ergänzen. Im Test `request is deterministic - no search, temperature zero, fixed max tokens, empty history` nach der `maxTokens`-Assertion ergänzen:

```kotlin
        assertThat(req.toolChoice).isEqualTo(GrokSelfTester.TOOL_CHOICE_REQUIRED)
```

Am Ende der Klasse zwei neue Tests:

```kotlin
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
```

Der bestehende Test `tool never called yields NotCalled` bleibt UNVERÄNDERT — er beweist, dass ein echtes Modell-„Nein" (finishReason "stop") weiterhin als NotCalled gemeldet wird.

- [ ] **Step 2: Tests laufen lassen — müssen FEHLSCHLAGEN**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.channel.llm.GrokSelfTesterTest"`
Expected: FAIL (unresolved `TOOL_CHOICE_REQUIRED`; Truncated-Test bekommt Completed statt Truncated)

- [ ] **Step 3: `GrokSelfTester` implementieren**

Import ergänzen: `io.github.lycheeappf.tmm.channel.llm.provider.FINISH_REASON_INCOMPLETE`.

In `e2eStage` die `LlmRequest(...)`-Konstruktion ersetzen durch:

```kotlin
        val request = LlmRequest(
            model = prefs.model(),
            systemPrompt = systemPrompt,
            history = emptyList(),
            userMessage = testPrompt(destination),
            tools = toolRegistry.activeSchemas(),
            maxTokens = E2E_MAX_TOKENS,
            temperature = 0f,
            webSearch = false,
            xSearch = false,
            // Erzwungener erster Tool-Call: der Selbsttest misst „funktioniert die
            // Pipeline", nicht „entscheidet sich das Modell". Der ToolCallExecutor
            // setzt das Feld auf Folge-Requests zurück.
            toolChoice = TOOL_CHOICE_REQUIRED
        )
```

Den `withTimeoutOrNull`-Block ersetzen durch:

```kotlin
            withTimeoutOrNull(E2E_TIMEOUT_MS) {
                val initialResponse = provider.complete(request)
                val loop = toolCallExecutor.run(request, initialResponse) { provider.complete(it) }
                val navCalled = loop.steps.any { it.call.name == SelfTestEvaluation.NAV_TOOL_NAME }
                if (!navCalled && loop.finalResponse.finishReason == FINISH_REASON_INCOMPLETE) {
                    // Reasoning hat max_output_tokens aufgebraucht, bevor das Tool dran
                    // war — eigener Befund statt fälschlich „nicht aufgerufen".
                    E2eResult.Truncated
                } else {
                    val answer = loop.finalResponse.content.orEmpty()
                    E2eResult.Completed(
                        nav = SelfTestEvaluation.navCheck(destination, loop.steps),
                        echo = SelfTestEvaluation.positionEcho(fix, systemPrompt.isBlank(), answer),
                        answer = answer
                    )
                }
            } ?: E2eResult.Timeout
```

Im `companion object`: `E2E_MAX_TOKENS` ersetzen und eine Konstante ergänzen; `testPrompt` ersetzen:

```kotlin
        /**
         * Fest statt User-Setting. 4096 statt 512: grok-4.x sind Reasoning-Modelle,
         * deren Denk-Tokens gegen `max_output_tokens` zählen — 512 war oft schon vom
         * Reasoning aufgebraucht, bevor der function_call emittiert war (Response
         * kam als status=incomplete ohne Call zurück).
         */
        internal const val E2E_MAX_TOKENS = 4096

        /** Responses-API string-Form: das Modell MUSS im ersten Call ein Tool rufen. */
        internal const val TOOL_CHOICE_REQUIRED = "required"

        /**
         * Model-facing, bewusst englisch (wie die Tool-Descriptions). Als Fahrer-
         * Navigationsanfrage geframt, damit die Trigger-Klauseln von Tool-Description
         * und System-Prompt („when the driver asks to navigate…") greifen, statt mit
         * einem „automated self-test"-Framing zu kollidieren (das las sich für das
         * Modell wie eine Injection und unterdrückte den Call intermittierend).
         */
        internal fun testPrompt(destination: String): String =
            "Navigate to '$destination'. To do this, call the tesla_navigate tool now, passing " +
                "the destination exactly as written above — do not reformat it and do not search the web. " +
                "Only after the tool has returned, reply with one short line: if your context contains " +
                "the user's GPS position, repeat its coordinates; otherwise write exactly NO POSITION. " +
                "(The driver started this navigation check from the app's settings screen.)"
```

- [ ] **Step 4: `TeslaNavigateTool`-Trigger erweitern**

In `TeslaNavigateTool.kt` die ersten zwei Description-Zeilen ersetzen:

```kotlin
        description = "Sends a navigation destination to the driver's Tesla vehicle via the Fleet API. " +
            "Call this when the driver asks to navigate somewhere, find a route, or go to a place, " +
            "or when you are explicitly instructed to call this tool (for example an app integration check). " +
```

(Rest der Description unverändert lassen.)

- [ ] **Step 5: Tests laufen lassen — müssen GRÜN sein**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.channel.llm.GrokSelfTesterTest" --tests "io.github.lycheeappf.tmm.channel.llm.tools.tesla.TeslaNavigateToolTest"`
Expected: PASS (14 bestehende + 2 neue; der Prompt-Test prüft `contains("tesla_navigate")` und die Destination — beides im neuen Prompt enthalten)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/lycheeappf/tmm/channel/llm/GrokSelfTester.kt app/src/main/java/io/github/lycheeappf/tmm/channel/llm/tools/tesla/TeslaNavigateTool.kt app/src/test/java/io/github/lycheeappf/tmm/channel/llm/GrokSelfTesterTest.kt
git commit -m "fix(selftest): force tool_choice, raise token budget, reframe test prompt"
```

---

### Task 5: Gesamtverifikation

**Files:** keine neuen Änderungen — Verifikation.

- [ ] **Step 1: Volle Suite + Debug-Build**

Run: `gradlew.bat :app:test :app:assembleDebug`
Expected: BUILD SUCCESSFUL, 0 failures

- [ ] **Step 2: i18n-Parität manuell prüfen** (Lint ist wegen Tooling-Bug tabu)

`values/strings_assistant.xml` und `values-de/strings_assistant.xml` müssen dieselben `assistant_selftest_*`-Keys in derselben Reihenfolge enthalten (jetzt je 30).
