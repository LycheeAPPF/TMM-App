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
| Determinismus | Test-Turn mit `temperature=0`, festen `maxTokens=512`, `webSearch=false`/`xSearch=false`, leerer History; Modell **und System-Prompt** = die vom User konfigurierten (end-to-end; ein bewusst geleerter Prompt wird als Ursache ausgewiesen, s. u.) |
| Gating | Stufe 1 braucht nur den API-Key (wie Key-Test); der E2E-Turn zusätzlich den **Privacy-Consent** — dasselbe Turn-Zeit-Gate wie `LlmChannel.handleTeslaReply` (Consent fehlt → eigenes Outcome, kein xAI-Call). Koordinaten gehen nur bei Standort-Opt-in + Permission + frischem Fix an xAI |
| Background-Permission | `WHILE_IN_USE` reicht für den Vordergrund-Test, aber nicht für den echten Grok-Turn im Hintergrund → **Warning** („nur Vordergrund"), kein Fail |
| Tesla-Vorab-Check | Rein lokale Statuszeile (Credentials gesetzt? Fahrzeug gewählt?) vor dem bezahlten xAI-Call; **kein Abbruch** — ein fehlendes Fahrzeug ist ein legitimer E2E-Befund (`CalledFailed`) |

## Architektur-Überblick

```
AssistantScreen („Grok-Selbsttest"-Card)
  └─ AssistantViewModel.runSelfTest(destination)
       └─ GrokSelfTester.run(destination): Flow<SelfTestEvent>
            ├─ Stufe 1  KEY        GrokKeyTester.run()          (Ping, wie bisher)
            │             └─ Fail → Abbruch (Stufen 2–4 = Skipped)
            ├─ Stufe 2  POSITION   prefs.locationContextEnabled()
            │                      permissionGate.hasLocationAccess()
            │                      permissionGate.hasBackgroundLocationAccess()   (nur Warnstufe)
            │                      locationProvider.lastKnownLocation()
            │             └─ Ergebnis: Disabled | NoPermission | NoFix | Ok(fix, backgroundGranted)
            │                (kein Abbruch — Stufe 4 läuft ggf. ohne Positions-Erwartung)
            ├─ Stufe 3  TESLA      authManager.hasCredentials() + tokenStore.readSelectedVin()
            │             └─ rein lokal, reine Info-Zeile (nie Abbruch, kein Netzwerk)
            └─ Stufe 4  E2E-TURN   Gate: prefs.isPrivacyConsentGiven() → sonst ConsentMissing (kein xAI-Call)
                          LlmRequest(systemPrompt(false,false,fix?), tools=Registry,
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
- **Scharfe Abgrenzung:** Der Executor enthält NUR den Loop. `LlmResponseFormatter`, der Blank-nach-Tool-Erfolg-Fallback (`llm_tool_success_fallback`), `EmptyResponse` bei Blank ohne Tool-Erfolg, Rate-Limit-Refunds und History-Persist bleiben unverändert im `LlmTurnRunner`.
- `LlmTurnRunner` nutzt den Executor; **alle bestehenden `LlmTurnRunnerTest`s bleiben unverändert grün.**

### B) `GrokSelfTester` (neu) — `channel/llm/GrokSelfTester.kt`

Orchestriert die vier Stufen. Wie `GrokKeyTester`: `@Singleton`, keine eigenen `R`-/Context-Referenzen (Lokalisierung erst an der UI-Grenze; Android steckt nur hinter injizierbaren, in JVM-Tests via MockK fakebaren Deps wie `PermissionGate`), umgeht bewusst `LlmTurnRunner`/`LlmRateLimiter`/`LlmConversationStore` (kein Conversation-State, kein Rate-Limit-Verbrauch, kein SMS/Mapping).

- **Deps:** `GrokKeyTester`, `LlmProvider`, `AssistantPreferencesStore`, `ToolRegistry`, `ToolCallExecutor`, `LocationProvider`, `PermissionGate`, `TeslaAuthManager`, `TeslaTokenStore`, `LogBuffer`.
- **API:** `fun run(destination: String): Flow<SelfTestEvent>`. Das Event-Modell ist explizit — kein Null-Raten in der UI:
  ```kotlin
  enum class SelfTestStage { KEY, POSITION, TESLA_LOCAL, E2E }
  sealed class SelfTestEvent {
      data class StageRunning(val stage: SelfTestStage) : ...
      data class KeyResult(val outcome: KeyTestOutcome) : ...
      data class PositionResult(val result: PositionLocalResult) : ...
      data class TeslaLocalResult(val credentialsSet: Boolean, val vinSelected: Boolean) : ...
      data class E2eDone(val result: E2eResult) : ...
      /** Stufen, die wegen eines früheren Abbruchs nicht laufen (Key ≠ VALID). */
      data class StageSkipped(val stage: SelfTestStage) : ...
  }
  ```
- **Ergebnismodell (immutable, ohne Android-Typen außer `LocationFix`):**
  ```kotlin
  sealed class PositionLocalResult {
      data object Disabled : ...       // Standort-Opt-in aus
      data object NoPermission : ...   // keine Location-Permission
      data object NoFix : ...          // kein Fix jünger als 15 min
      /** backgroundGranted=false ⇒ Fix nur im Vordergrund verfügbar — Warning, kein Fail. */
      data class Ok(val fix: LocationFix, val backgroundGranted: Boolean) : ...
  }
  sealed class NavCheck {
      data object NotCalled : ...                                  // Grok hat das Tool nicht gerufen
      data class WrongAddress(val sent: String) : ...              // Tool gerufen, Ziel weicht ab
      data class CalledOk(val sent: String) : ...                  // Fleet API hat akzeptiert
      data class CalledFailed(val sent: String, val error: String) : ... // ToolInvocationResult.Failure (lokalisierte Meldung)
  }
  enum class PositionEcho {
      SKIPPED,      // Stufe 2 nicht Ok → keine Erwartung
      NO_CLAUSE,    // Fix vorhanden, aber System-Prompt bewusst geleert → Klausel wird nie angehängt (Produktionsverhalten!)
      MATCHED,
      NOT_FOUND
  }
  sealed class E2eResult {
      data class Completed(val nav: NavCheck, val echo: PositionEcho, val answer: String) : ...
      data class ProviderFailed(val outcome: KeyTestOutcome) : ... // Fehler-Mapping wie GrokKeyTester
      data object ConsentMissing : ...                             // Privacy-Consent fehlt — kein xAI-Call erfolgt
      data object Timeout : ...
  }
  ```

**Stufe 1 — Key:** delegiert an `GrokKeyTester.run()`. Ergebnis ≠ `VALID` → für POSITION/TESLA_LOCAL/E2E wird je ein `StageSkipped` emittiert, der Flow endet.

**Stufe 2 — Position lokal:** prüft in dieser Reihenfolge und meldet das **erste fehlende Glied**: `prefs.locationContextEnabled()` → `Disabled`; `permissionGate.hasLocationAccess()` → `NoPermission`; `locationProvider.lastKnownLocation()` → `NoFix` bzw. `Ok(fix, backgroundGranted = permissionGate.hasBackgroundLocationAccess())`. Hintergrund: der echte Grok-Turn läuft im Hintergrund (Tesla-Reply ohne UI) — mit nur `WHILE_IN_USE` liefert der OS-Cache dort keine Koordinaten, der Vordergrund-Selbsttest würde aber grün zeigen. Deshalb weist `backgroundGranted=false` explizit als Warnung aus: „Test ok, im Auto fehlt ‚Immer erlauben‘". Kein Netzwerk-Call. Kein Abbruch bei ≠ Ok.

**Stufe 3 — Tesla lokal (Info-Zeile):** `authManager.hasCredentials()` und `tokenStore.readSelectedVin() != null`, beides rein lokale Reads (kein Netzwerk, kein Wake-up). Niemals Abbruch — die Zeile erklärt nur ein späteres `CalledFailed` bzw. gibt vor dem bezahlten xAI-Call den Hinweis „Tesla-Konto nicht verbunden / kein Fahrzeug gewählt".

**Stufe 4 — E2E-Turn:**

- **Consent-Gate zuerst:** `prefs.isPrivacyConsentGiven()` — fehlt der Consent → `E2eResult.ConsentMissing`, **kein** xAI-Call. Begründung: der Produktions-Turn prüft Consent zur Turn-Zeit (`LlmChannel.handleTeslaReply`); ein Selbsttest ohne dieses Gate würde erstens Nutzerdaten (Zieladresse, ggf. Koordinaten) ohne Zustimmung an xAI senden und zweitens einen Zustand „grün" melden, der im Auto abgelehnt würde.

- Request: `LlmRequest(model = prefs.model(), systemPrompt = prefs.systemPrompt(webSearch=false, xSearch=false, location=fixAusStufe2), history = emptyList(), userMessage = TEST_PROMPT(destination), tools = toolRegistry.activeSchemas(), maxTokens = 512, temperature = 0f, webSearch = false, xSearch = false)`.
- `TEST_PROMPT` (model-facing, englisch, `internal` für Tests), sinngemäß:
  > "This is an automated integration self-test of the app. Do exactly two things: (1) Call the tesla_navigate tool with the destination '<destination>' passed exactly as written — do not reformat it and do not search the web. (2) After the tool call, reply with one short line: if your context contains the user's GPS position, repeat its coordinates; otherwise write exactly NO POSITION."
- Ablauf: `provider.complete(request)` → `ToolCallExecutor.run(...)`; Gesamt-Timeout **120 s** hart via `withTimeoutOrNull` (der Fleet-Pfad kann Wake-up + 15 s Delay + Retry enthalten) → sonst `E2eResult.Timeout`. `LlmProviderError` → `ProviderFailed(outcome)` mit demselben Mapping wie `GrokKeyTester`: die private `toOutcome()`-Extension wird zu einer `internal` Top-Level-Funktion in `GrokKeyTester.kt` und von beiden Testern genutzt (eine Quelle, kein Duplikat). `CancellationException` wird **immer** rethrown.
- **Auswertung NavCheck** aus `steps`: erster Step mit `call.name == "tesla_navigate"`; fehlt er → `NotCalled`. Prüf-Reihenfolge: **zuerst** das `address`-Argument — es muss das Testziel tolerant enthalten (Vergleich case-insensitiv, Whitespace normalisiert; Substring-Match in beide Richtungen genügt), sonst `WrongAddress` (hat Vorrang, auch wenn der Fleet-Call durchging — das Auto führe sonst zum falschen Ziel). Erst bei passender Adresse zählt das Ergebnis: `ToolInvocationResult.Success` → `CalledOk`, `Failure(msg)` → `CalledFailed` (msg ist bereits die lokalisierte Meldung aus `TeslaNavigateTool`, z. B. „Tesla-Konto verbinden").
- **Auswertung PositionEcho:** Stufe 2 ≠ `Ok` → `SKIPPED`. Stufe 2 `Ok`, aber der gebaute System-Prompt ist blank (User hat den Prompt bewusst geleert — `AssistantPreferencesStore.systemPrompt()` hängt dann **keine** Positions-Klausel an, exakt wie in Produktion) → `NO_CLAUSE` mit Ursachen-Hinweis in der UI. Sonst: Zahlen aus dem finalen Antworttext extrahieren, **Normalisierung explizit**: Dezimalkomma wie Dezimalpunkt akzeptieren (Grok antwortet ggf. deutsch: „52,52° N"), Grad-/Minutenzeichen und Himmelsrichtungs-Buchstaben (N/S/E/W/O) ignorieren, Vergleich vorzeichen-tolerant via `abs()` (die Prompt-Klausel formatiert `abs()` + Himmelsrichtung). Existiert ein Zahlenpaar mit `|Δ|lat|| ≤ 0.02` **und** `|Δ|lon|| ≤ 0.02` zum lokalen Fix → `MATCHED`, sonst `NOT_FOUND`.
- Der finale Antworttext geht **nur** in die UI (Sichtkontrolle), **nie** in den `LogBuffer` (PII-Regel: dort nur Stufen-Outcomes und Längen).

### C) `AssistantViewModel` — Selbsttest-State

- Neuer UI-State-Teil:
  ```kotlin
  data class StageCell<T>(val value: T?, val skipped: Boolean = false)  // value=null ∧ !skipped ⇒ noch nicht gelaufen

  data class SelfTestUiState(
      val running: Boolean = false,
      val destination: String = DEFAULT_SELFTEST_DESTINATION, // "Alexanderplatz, Berlin"
      val key: StageCell<KeyTestOutcome> = StageCell(null),
      val position: StageCell<PositionLocalResult> = StageCell(null),
      val teslaLocal: StageCell<Pair<Boolean, Boolean>> = StageCell(null), // credentialsSet, vinSelected
      val e2e: StageCell<E2eResult> = StageCell(null),
      val currentStage: SelfTestStage? = null   // für Live-Anzeige „läuft…"
  )
  ```
  eingebettet als `val selfTest: SelfTestUiState` in `AssistantUiState`. „Übersprungen", „noch nicht gelaufen" und „Ergebnis da" sind damit drei unterscheidbare Zustände.
- **`refresh()` darf den Selbsttest-State nicht wipen:** `AssistantScreen` ruft `refresh()` bei jedem Resume (`LifecycleResumeEffect`), und Resumes passieren mitten im Testlauf (z. B. Rückkehr aus dem Permission-Dialog der App-Einstellungen). Der Platten-Snapshot in `refresh()` übernimmt deshalb IMMER `selfTest` aus dem aktuellen State (`snapshot.copy(selfTest = cur.selfTest)`) — unabhängig vom `persisting`-Zweig; Destination, Ergebnisse und `running` überleben so Resume/Recompose.
- `runSelfTest()`: sammelt `GrokSelfTester.run(destination)` auf `ioDispatcher` (Cancellation rethrow, unerwarteter Fehler → `E2eResult.ProviderFailed(UNKNOWN)`), aktualisiert den State pro Event.
- `setSelfTestDestination(value)`: reines UI-State-Update (kein Persist, kein Debounce nötig).
- **Race-Guards doppelt** — nicht nur `enabled`-Flags in der UI, sondern auch Methoden-Guards im ViewModel (Buttons können durch State-Latenz doppelt feuern): `runSelfTest()` returned sofort, wenn `selfTest.running || keyTestRunning || saving`; `saveApiKey`/`clearApiKey`/`testApiKey` returnen sofort, wenn `selfTest.running`. Zusätzlich dieselben Bedingungen als `enabled` in der UI.

### D) `AssistantScreen` — neue Card

- Neue `SettingCard` **direkt unter der API-Key-Card**: Titel „Grok-Selbsttest", Beschreibung erklärt die Stufen und den Hinweis, dass ein **echtes Navigationsziel ans Auto gesendet** wird und der Test einen xAI-Request kostet.
- Inhalt: `OutlinedTextField` (Testziel, single line) → `PrimaryActionButton` „Selbsttest starten" (`enabled = apiKeyIsSet && destination.isNotBlank() && !saving && !keyTestRunning`, `loading = selfTest.running`) → darunter pro Stufe eine Zeile: Stufen-Label + `StatusPill` + Detailtext (`bodySmall`).
- Status-Mapping (UI-Grenze, analog `keyTestResultUi`):
  - Key: bestehendes `keyTestResultUi`-Mapping.
  - Position: `Ok(_, backgroundGranted=true)` → Success-Pill mit „52.5200° N, 13.4050° O (±25 m)"-Detail; `Ok(_, backgroundGranted=false)` → **Warning**-Pill, Detail „Fix ok — im Auto braucht es aber ‚Immer erlauben‘ (App-Einstellungen)"; `Disabled`/`NoPermission`/`NoFix` → Warning-Pill mit Handlungshinweis (Opt-in einschalten / Permission erteilen / auf frischen Fix warten).
  - Tesla lokal: beides ok → Success („Konto verbunden, Fahrzeug gewählt"); sonst Warning mit dem fehlenden Teil („Tesla-Konto in den Einstellungen verbinden" / „Fahrzeug wählen").
  - Nav: `CalledOk` → Success („Ziel ans Auto gesendet: <adresse>"); `CalledFailed` → Error mit der lokalisierten Tool-Fehlermeldung; `WrongAddress`/`NotCalled` → Error/Warning mit Antworttext als Detail.
  - Echo: `MATCHED` → Success; `NOT_FOUND` → Warning; `NO_CLAUSE` → Warning („System-Prompt ist leer — die Positions-Klausel wird nie angehängt"); `SKIPPED` → Neutral („übersprungen — keine lokale Position").
  - `ConsentMissing` → Error-Pill, Detail „Datenschutz-Zustimmung erteilen (Schalter oben)"; `ProviderFailed`/`Timeout` → Error-/Warning-Pill über das bestehende Key-Test-Vokabular.
  - Übersprungene Stufen (Key ≠ VALID) → Neutral-Pill „übersprungen".
- Während des Laufs zeigt die aktuelle Stufe eine „läuft…"-Pill (Live-Updates aus dem Flow).
- **Alle neuen Strings EN (`values/`) + DE (`values-de/`)**, Präfix `assistant_selftest_*`.

## Fehlerbehandlung & Sicherheit

- `CancellationException` wird auf jeder Ebene rethrown (`coRunCatching` bzw. explizit) — nie als Testergebnis maskieren.
- Timeouts: Stufe 1 nutzt das bestehende 20-s-Cap des Key-Testers; Stufe 4 hat ein hartes 120-s-Gesamt-Cap.
- `LogBuffer`: nur Metadaten (Stufe, Outcome-Typ, Antwortlänge) — **nie** Prompt, Antworttext, Koordinaten oder Zieladresse (Adresse = potenzielles PII, konsistent zur bestehenden `navigate()`-Logging-Regel).
- Koordinaten verlassen das Gerät nur Richtung xAI und nur bei aktivem Opt-in — identisches Gating wie der Produktions-Turn.
- Kein neuer Berechtigungsbedarf; keine Manifest-Änderung.

## Tests (JVM, JUnit4 + Truth + MockK + `runTest`)

1. **`ToolCallExecutorTest` (neu):** Loop-Semantik isoliert — Iterationslimit 3, Parse-Fehler → leeres Args-Objekt, Steps vollständig aufgezeichnet, Fehler aus `complete` propagieren, `inFlight*`-Akkumulation über mehrere Iterationen.
2. **`LlmTurnRunnerTest` (bestehend): unverändert grün** — Beleg für Verhaltensäquivalenz der Extraktion.
3. **`GrokSelfTesterTest` (neu):** MockK-`LlmProvider` mit Skript-Antworten (Tool-Call → Text), Fake-`LocationProvider`/`PermissionGate`/Prefs/`TeslaAuthManager`/`TeslaTokenStore`, echte `ToolRegistry` mit Fake-`AssistantTool` („tesla_navigate"). Szenarien: Key ungültig → StageSkipped für alle Folgestufen; Consent fehlt → `ConsentMissing`, Provider wird NIE aufgerufen (verify); Opt-in aus / keine Permission / kein Fix; `backgroundGranted=false` korrekt durchgereicht; Tesla-lokal-Kombinationen; Tool nicht gerufen; falsche Adresse; Fleet-Failure (Tool-Failure-Message durchgereicht); Koordinaten-Match und ‑Mismatch inkl. **Dezimalkomma** („52,52"), Himmelsrichtungen und Vorzeichen/Rundung; `NO_CLAUSE` bei geleertem System-Prompt trotz Fix; Provider-Fehler; Timeout; `webSearch=false` im Request; Prompt enthält Zieladresse.
4. **`AssistantViewModelTest` (erweitert):** `runSelfTest`-State-Übergänge (running → Stufen-Events → Endzustand), Methoden-Race-Guards (Doppel-Invoke feuert nur einmal; Key-Test während Selbsttest abgewiesen), Destination-Editing, **`refresh()` erhält `selfTest`-State** (Ergebnisse + Destination überleben ein Resume).

## Review-Nachtrag (2026-07-10, Codex/GPT-5.x-Review)

Nach externem Review eingearbeitet: Privacy-Consent-Gate für den E2E-Turn (Turn-Zeit-Parität zu `LlmChannel`); Background-Location-Unterscheidung (`WHILE_IN_USE` = Warning „nur Vordergrund"); `refresh()`-Erhalt des Selbsttest-States; explizites `SelfTestEvent`-/`StageCell`-Modell statt Null-Mehrdeutigkeit; `PositionEcho.NO_CLAUSE` für bewusst geleerten System-Prompt; Dezimalkomma-/Himmelsrichtungs-Normalisierung; lokale Tesla-Konto-Info-Stufe vor dem bezahlten xAI-Call; Executor-Abgrenzung (Formatter/Fallback bleiben im Runner) und ViewModel-Methoden-Guards explizit gemacht. Entschieden gegen: Consent-Sonderweg nur für den Selbsttest (nein — Produktions-Gate gilt), Abbruch bei fehlendem Fahrzeug (nein — legitimer E2E-Befund), festen Test-System-Prompt (nein — der konfigurierte Prompt IST Teil des Testgegenstands).

## Offene Punkte

Keine — alle Entscheidungen sind oben festgehalten.
