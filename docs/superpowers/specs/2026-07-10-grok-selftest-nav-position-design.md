# Design: Grok-Selbsttest in der App (Key + Position + Navigation end-to-end)

- **Status:** Entwurf zur Review
- **Datum:** 2026-07-10
- **Kontext:** Ob der Grok-Assistent Navigationsbefehle versteht (`tesla_navigate`-Tool-Call → Fleet API) und ob die GPS-Position im System-Prompt ankommt, lässt sich heute nur testen, indem man **im Auto sitzt** und diktiert. Der bestehende „Grok-Test" in der App (`GrokKeyTester`) prüft nur den xAI-Key per Mini-Ping — ohne Tools, ohne Standort. Dieses Feature erweitert den Test zu einem mehrstufigen **Selbsttest**, der die Kette Key → Grok → Tool-Call → Fleet API sowie die Positions-Pipeline vollständig aus der App heraus verifiziert.

## Ziel

Ein Button in der App, der ohne Gang zum Auto beantwortet:

1. Ist der xAI-Key gültig? (wie bisher)
2. Kommt eine **frische GPS-Position** zustande (Opt-in → Permission → Fix < 15 min), und welches Glied fehlt ggf.?
3. Versteht Grok einen Navigationsbefehl **end-to-end**: ruft es `tesla_navigate` mit der richtigen Adresse auf, akzeptiert die Fleet API den Befehl (inkl. Wake-up-Pfad), und kennt Grok die Position aus seinem Kontext?

Jede Stufe bekommt eine eigene Status-Zeile mit Detailtext — schlägt etwas fehl, sieht man **welches Glied**.

## Nicht-Ziele

- **Kein** Test des SMS-/MAP-/Bluetooth-Pfads (Vorlesen im Auto, Diktat-Rückweg) — der Selbsttest deckt Key→Grok→Tool→Fleet ab; der allerletzte Meter (Car-UI) bleibt Autotest.
- **Kein** Dry-Run-Modus: der Fleet-Call ist echt, das Auto erhält wirklich ein Navigationsziel (bewusste Entscheidung des Maintainers, „voll end-to-end"). Die Card-Beschreibung weist darauf hin.
- **Kein** Persistieren des Testziels (vorbefülltes, editierbares Feld genügt).
- **Keine** Verhaltensänderung am Produktions-Turn (`LlmTurnRunner`): die Tool-Loop-Extraktion ist eine reine Refaktorierung, bestehende Tests müssen unverändert grün bleiben.
- **Kein** `navigation_gps_request`-Test (das Grok-Tool nutzt nur die Text-Variante `navigation_request`).
- **Kein** Entfernen des bestehenden „Key testen"-Buttons — er bleibt als schneller Billig-Check erhalten.

## Getroffene Entscheidungen (Zusammenfassung)

| Frage | Entscheidung |
|---|---|
| Nav-Test-Tiefe | Voll end-to-end: echter Grok-Turn, Grok ruft selbst `tesla_navigate`, echter Fleet-API-Call ans Auto |
| Positions-Test | Beides: lokaler Ketten-Check (Opt-in/Permission/Fix) **und** end-to-end (Grok nennt Koordinaten aus dem Kontext) |
| UI-Form | Eigene „Grok-Selbsttest"-Card im Assistant-Screen, ein Start-Button, Status-Zeile pro Stufe; Key-Test-Button bleibt |
| Implementierung | Eigener `GrokSelfTester` neben `GrokKeyTester` (am Conversation-State vorbei), Tool-Loop aus `LlmTurnRunner` in gemeinsamen Executor extrahiert |
| Testziel | Editierbares Feld, vorbefüllt mit „Alexanderplatz, Berlin", nicht persistiert |
| Determinismus | Test-Turn mit `temperature=0`, festen `maxTokens=512`, `webSearch=false`/`xSearch=false`, leerer History; Modell = das vom User konfigurierte |
| Gating | Nur API-Key nötig (wie Key-Test); Koordinaten gehen nur bei aktivem Standort-Opt-in + Permission + frischem Fix an xAI |

## Architektur-Überblick

```
AssistantScreen („Grok-Selbsttest"-Card)
  └─ AssistantViewModel.runSelfTest(destination)
       └─ GrokSelfTester.run(destination): Flow<SelfTestEvent>
            ├─ Stufe 1  KEY        GrokKeyTester.run()          (Ping, wie bisher)
            │             └─ Fail → Abbruch (Stufen 2+3 = Skipped)
            ├─ Stufe 2  POSITION   prefs.locationContextEnabled()
            │                      permissionGate.hasLocationAccess()
            │                      locationProvider.lastKnownLocation()
            │             └─ Ergebnis: Disabled | NoPermission | NoFix | Ok(fix)
            │                (kein Abbruch — Stufe 3 läuft ggf. ohne Positions-Erwartung)
            └─ Stufe 3  E2E-TURN   LlmRequest(systemPrompt(false,false,fix?), tools=Registry,
                                   deterministischer Test-Prompt, history=[])
                          provider.complete() ──► ToolCallExecutor (gemeinsam mit LlmTurnRunner)
                                                    └─ TeslaNavigateTool.invoke()
                                                         └─ Fleet API navigation_request
                          Auswertung: NavCheck + PositionEcho + Antworttext
```

## Komponenten

### A) `ToolCallExecutor` (Extraktion) — `channel/llm/tools/ToolCallExecutor.kt`

Der bisherige Tool-Execution-Loop aus `LlmTurnRunner.run` (Zeilen „Tool-Execution-Loop" bis `val response`), unverändert in der Semantik:

- **API:**
  ```kotlin
  data class ToolStep(val call: ToolCall, val result: ToolInvocationResult)
  data class ToolLoopResult(val finalResponse: LlmResponse, val steps: List<ToolStep>)

  suspend fun run(
      initialRequest: LlmRequest,
      initialResponse: LlmResponse,
      complete: suspend (LlmRequest) -> LlmResponse
  ): ToolLoopResult
  ```
- Verhalten identisch zu heute: solange `toolCalls` nicht leer und < `MAX_TOOL_ITERATIONS` (3): Argumente parsen (Parse-Fehler → leeres JSON-Objekt), `toolRegistry.invoke`, `ToolResult` an `inFlightToolCalls`/`inFlightToolResults` anhängen, Folge-Request via `complete`.
- `LlmProviderError`/Exceptions aus `complete` propagieren **unverändert nach oben** (kein Catch im Executor). `LlmTurnRunner` fängt sie wie bisher — heute führt ein Fehler in *jeder* Iteration zum sofortigen Return mit Refund, also ist Catch-um-den-Loop verhaltensäquivalent zu Catch-pro-Call.
- Das bestehende `logBuffer.info("Tool '<name>' → <ResultTyp>")` wandert mit in den Executor (gilt damit für Runner **und** Selbsttest).
- `anyToolSucceeded` leitet der Aufrufer aus `steps` ab (`any { it.result is Success }`).
- `LlmTurnRunner` nutzt den Executor; **alle bestehenden `LlmTurnRunnerTest`s bleiben unverändert grün.**

### B) `GrokSelfTester` (neu) — `channel/llm/GrokSelfTester.kt`

Orchestriert die drei Stufen. Wie `GrokKeyTester`: `@Singleton`, Android-frei (keine `R`-/Context-Referenz — Lokalisierung erst an der UI-Grenze), umgeht bewusst `LlmTurnRunner`/`LlmRateLimiter`/`LlmConversationStore` (kein Conversation-State, kein Rate-Limit-Verbrauch, kein SMS/Mapping).

- **Deps:** `GrokKeyTester`, `LlmProvider`, `AssistantPreferencesStore`, `ToolRegistry`, `ToolCallExecutor`, `LocationProvider`, `PermissionGate`, `LogBuffer`.
- **API:** `fun run(destination: String): Flow<SelfTestEvent>` — emittiert pro Stufe erst `StageRunning`, dann das Stufen-Ergebnis; die UI kann live mitzeichnen.
- **Ergebnismodell (immutable, Android-frei):**
  ```kotlin
  sealed class PositionLocalResult {
      data object Disabled : ...       // Standort-Opt-in aus
      data object NoPermission : ...   // keine Location-Permission
      data object NoFix : ...          // kein Fix jünger als 15 min
      data class Ok(val fix: LocationFix) : ...
  }
  sealed class NavCheck {
      data object NotCalled : ...                                  // Grok hat das Tool nicht gerufen
      data class WrongAddress(val sent: String) : ...              // Tool gerufen, Ziel weicht ab
      data class CalledOk(val sent: String) : ...                  // Fleet API hat akzeptiert
      data class CalledFailed(val sent: String, val error: String) : ... // ToolInvocationResult.Failure (lokalisierte Meldung)
  }
  enum class PositionEcho { SKIPPED, MATCHED, NOT_FOUND }          // SKIPPED wenn Stufe 2 nicht Ok
  sealed class E2eResult {
      data class Completed(val nav: NavCheck, val echo: PositionEcho, val answer: String) : ...
      data class ProviderFailed(val outcome: KeyTestOutcome) : ... // Fehler-Mapping wie GrokKeyTester
      data object Timeout : ...
  }
  ```

**Stufe 1 — Key:** delegiert an `GrokKeyTester.run()`. Ergebnis ≠ `VALID` → `SelfTestEvent` mit `aborted=true`, Stufen 2+3 werden nicht ausgeführt.

**Stufe 2 — Position lokal:** prüft in dieser Reihenfolge und meldet das **erste fehlende Glied**: `prefs.locationContextEnabled()` → `Disabled`; `permissionGate.hasLocationAccess()` → `NoPermission`; `locationProvider.lastKnownLocation()` → `NoFix` bzw. `Ok(fix)`. Kein Netzwerk-Call. Kein Abbruch bei ≠ Ok.

**Stufe 3 — E2E-Turn:**

- Request: `LlmRequest(model = prefs.model(), systemPrompt = prefs.systemPrompt(webSearch=false, xSearch=false, location=fixAusStufe2), history = emptyList(), userMessage = TEST_PROMPT(destination), tools = toolRegistry.activeSchemas(), maxTokens = 512, temperature = 0f, webSearch = false, xSearch = false)`.
- `TEST_PROMPT` (model-facing, englisch, `internal` für Tests), sinngemäß:
  > "This is an automated integration self-test of the app. Do exactly two things: (1) Call the tesla_navigate tool with the destination '<destination>' passed exactly as written — do not reformat it and do not search the web. (2) After the tool call, reply with one short line: if your context contains the user's GPS position, repeat its coordinates; otherwise write exactly NO POSITION."
- Ablauf: `provider.complete(request)` → `ToolCallExecutor.run(...)`; Gesamt-Timeout **120 s** hart via `withTimeoutOrNull` (der Fleet-Pfad kann Wake-up + 15 s Delay + Retry enthalten) → sonst `E2eResult.Timeout`. `LlmProviderError` → `ProviderFailed(outcome)` mit demselben Mapping wie `GrokKeyTester`: die private `toOutcome()`-Extension wird zu einer `internal` Top-Level-Funktion in `GrokKeyTester.kt` und von beiden Testern genutzt (eine Quelle, kein Duplikat). `CancellationException` wird **immer** rethrown.
- **Auswertung NavCheck** aus `steps`: erster Step mit `call.name == "tesla_navigate"`; fehlt er → `NotCalled`. Prüf-Reihenfolge: **zuerst** das `address`-Argument — es muss das Testziel tolerant enthalten (Vergleich case-insensitiv, Whitespace normalisiert; Substring-Match in beide Richtungen genügt), sonst `WrongAddress` (hat Vorrang, auch wenn der Fleet-Call durchging — das Auto führe sonst zum falschen Ziel). Erst bei passender Adresse zählt das Ergebnis: `ToolInvocationResult.Success` → `CalledOk`, `Failure(msg)` → `CalledFailed` (msg ist bereits die lokalisierte Meldung aus `TeslaNavigateTool`, z. B. „Tesla-Konto verbinden").
- **Auswertung PositionEcho:** nur wenn Stufe 2 `Ok(fix)`: alle Dezimalzahlen aus dem finalen Antworttext extrahieren; existiert ein Zahlenpaar mit `|Δlat| ≤ 0.02` **und** `|Δlon| ≤ 0.02` zum lokalen Fix → `MATCHED`, sonst `NOT_FOUND`. (Vorzeichen-tolerant prüfen: die Prompt-Klausel formatiert `abs()` + Himmelsrichtung.) Stufe 2 ≠ Ok → `SKIPPED`.
- Der finale Antworttext geht **nur** in die UI (Sichtkontrolle), **nie** in den `LogBuffer` (PII-Regel: dort nur Stufen-Outcomes und Längen).

### C) `AssistantViewModel` — Selbsttest-State

- Neuer UI-State-Teil:
  ```kotlin
  data class SelfTestUiState(
      val running: Boolean = false,
      val destination: String = DEFAULT_SELFTEST_DESTINATION, // "Alexanderplatz, Berlin"
      val key: KeyTestOutcome? = null,
      val position: PositionLocalResult? = null,
      val e2e: E2eResult? = null,
      val currentStage: SelfTestStage? = null   // für Live-Anzeige „läuft…"
  )
  ```
  eingebettet als `val selfTest: SelfTestUiState` in `AssistantUiState`.
- `runSelfTest()`: sammelt `GrokSelfTester.run(destination)` auf `ioDispatcher` via `coRunCatching`-Semantik (Cancellation rethrow, unerwarteter Fehler → `E2eResult.ProviderFailed(UNKNOWN)`), aktualisiert den State pro Event.
- `setSelfTestDestination(value)`: reines UI-State-Update (kein Persist, kein Debounce nötig).
- **Race-Guards** (gegenseitiges Sperren, wie heute Key-Test ↔ Save/Remove): `saveApiKey`/`clearApiKey`/`testApiKey` disabled solange `selfTest.running` — und `runSelfTest` disabled solange `keyTestRunning || saving`.

### D) `AssistantScreen` — neue Card

- Neue `SettingCard` **direkt unter der API-Key-Card**: Titel „Grok-Selbsttest", Beschreibung erklärt die drei Stufen und den Hinweis, dass ein **echtes Navigationsziel ans Auto gesendet** wird und der Test einen xAI-Request kostet.
- Inhalt: `OutlinedTextField` (Testziel, single line) → `PrimaryActionButton` „Selbsttest starten" (`enabled = apiKeyIsSet && destination.isNotBlank() && !saving && !keyTestRunning`, `loading = selfTest.running`) → darunter pro Stufe eine Zeile: Stufen-Label + `StatusPill` + Detailtext (`bodySmall`).
- Status-Mapping (UI-Grenze, analog `keyTestResultUi`):
  - Key: bestehendes `keyTestResultUi`-Mapping.
  - Position: `Ok` → Success-Pill mit „52.5200° N, 13.4050° O (±25 m)"-Detail; `Disabled`/`NoPermission`/`NoFix` → Warning-Pill mit Handlungshinweis (Opt-in einschalten / Permission erteilen / auf frischen Fix warten).
  - Nav: `CalledOk` → Success („Ziel ans Auto gesendet: <adresse>"); `CalledFailed` → Error mit der lokalisierten Tool-Fehlermeldung; `WrongAddress`/`NotCalled` → Error/Warning mit Antworttext als Detail.
  - Echo: `MATCHED` → Success; `NOT_FOUND` → Warning; `SKIPPED` → Neutral („übersprungen — keine lokale Position").
  - `ProviderFailed`/`Timeout` → Error-/Warning-Pill über das bestehende Key-Test-Vokabular.
- Während des Laufs zeigt die aktuelle Stufe eine „läuft…"-Pill (Live-Updates aus dem Flow).
- **Alle neuen Strings EN (`values/`) + DE (`values-de/`)**, Präfix `assistant_selftest_*`.

## Fehlerbehandlung & Sicherheit

- `CancellationException` wird auf jeder Ebene rethrown (`coRunCatching` bzw. explizit) — nie als Testergebnis maskieren.
- Timeouts: Stufe 1 nutzt das bestehende 20-s-Cap des Key-Testers; Stufe 3 hat ein hartes 120-s-Gesamt-Cap.
- `LogBuffer`: nur Metadaten (Stufe, Outcome-Typ, Antwortlänge) — **nie** Prompt, Antworttext, Koordinaten oder Zieladresse (Adresse = potenzielles PII, konsistent zur bestehenden `navigate()`-Logging-Regel).
- Koordinaten verlassen das Gerät nur Richtung xAI und nur bei aktivem Opt-in — identisches Gating wie der Produktions-Turn.
- Kein neuer Berechtigungsbedarf; keine Manifest-Änderung.

## Tests (JVM, JUnit4 + Truth + MockK + `runTest`)

1. **`ToolCallExecutorTest` (neu):** Loop-Semantik isoliert — Iterationslimit 3, Parse-Fehler → leeres Args-Objekt, Steps vollständig aufgezeichnet, Fehler aus `complete` propagieren, `inFlight*`-Akkumulation über mehrere Iterationen.
2. **`LlmTurnRunnerTest` (bestehend): unverändert grün** — Beleg für Verhaltensäquivalenz der Extraktion.
3. **`GrokSelfTesterTest` (neu):** MockK-`LlmProvider` mit Skript-Antworten (Tool-Call → Text), Fake-`LocationProvider`/`PermissionGate`/Prefs, echte `ToolRegistry` mit Fake-`AssistantTool` („tesla_navigate"). Szenarien: Key ungültig → Abbruch; Opt-in aus / keine Permission / kein Fix; Tool nicht gerufen; falsche Adresse; Fleet-Failure (Tool-Failure-Message durchgereicht); Koordinaten-Match und ‑Mismatch (inkl. Vorzeichen/Rundung); Provider-Fehler; Timeout; `webSearch=false` im Request; Prompt enthält Zieladresse.
4. **`AssistantViewModelTest` (erweitert):** `runSelfTest`-State-Übergänge (running → Stufen-Events → Endzustand), Race-Guard-Flags, Destination-Editing.

## Offene Punkte

Keine — alle Entscheidungen sind oben festgehalten.
