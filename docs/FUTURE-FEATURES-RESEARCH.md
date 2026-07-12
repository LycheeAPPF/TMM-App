# Future-Features — Umsetzungsrecherche

## Einleitung

Dieses Dokument beschreibt für drei geplante Features, **wie** sie im bestehenden TMM-Code umgesetzt werden — gegründet auf den verifizierten Recherche-Findings und dem realen Codebase-Stand (Stand: nightly, 0.5.0). Es richtet sich an den Solo-Maintainer und nennt für jedes Feature den empfohlenen Ansatz, Alternativen, konkrete Schritte, die Integration in die vorhandenen Seams (`AssistantTool`/`ToolRegistry`, `GrokProvider`, `LlmTurnRunner`, `KeystoreApiKeyStore`, `HttpModule`), die genauen APIs samt Auth sowie Risiken — inklusive der durch die Verifikation **korrigierten** Annahmen.

Ein Befund zieht sich durch alle drei Features und steht als gemeinsame Voraussetzung am Ende dieses Dokuments: **Function-Calling ist im Code nur am Draht verkabelt, nicht end-to-end.** Das betrifft Spotify und Navi direkt, Internetzugriff dagegen nicht.

### Prioritäts- / Aufwands-Übersicht

| Feature | Empfohlener Ansatz | Aufwand | Haupt-Risiko |
|---|---|---|---|
| Internetzugriff für Grok | Natives server-seitiges `web_search`-Tool auf `/v1/responses` über ein dediziertes Flag (NICHT über den `AssistantTool`-Seam) | Klein–mittel (~1,5–2,5 Tage) | TTS-Hygiene (Zitate/URLs im vorgelesenen Text); unbestätigtes `store=false` + `web_search` muss live geprüft werden |
| Spotify-Steuerung | Grok-Function-Calling mit einem `control_spotify`-`AssistantTool` über die Spotify Web API (Auth-Code + PKCE) — erfordert zuerst den fehlenden Tool-Loop | Groß (~7–11 Tage) | Premium- + aktive-Connect-Device-Pflicht; `is_restricted` der Tesla-Headunit; Verteilung via BYO-Client-ID pro Nutzer (Dev-Mode-Cap ist pro App, daher kein Blocker) |
| Fahrziel ins Tesla-Navi | `navigate_to`-`AssistantTool` über Tesla Fleet API `navigation_request` (ungesigntes REST), opt-in BYO-Credentials; Default-Fallback = Maps-Link als SMS — erfordert zuerst den fehlenden Tool-Loop | Groß (~6–10 Tage) | Partner-Registrierung + eigene Domain + gehosteter Public-Key; Pay-per-use (Cloud-Bindung) bricht die No-Cloud-Haltung; OAuth-Secret darf nicht ins APK |

---

## Internetzugriff für Grok

### Empfohlener Ansatz

Aktiviere xAIs **natives server-seitiges `web_search`-Tool** (optional zusätzlich `x_search`) auf dem bestehenden `POST https://api.x.ai/v1/responses`-Call, hinter einem Nutzer-Toggle. Konkret: ein dediziertes boolesches Preference-Flag (`webSearchEnabled`), das `GrokProvider.buildRequest` liest und das einen Tool-Eintrag `{ "type": "web_search" }` an das vorhandene `tools`-Array anhängt. Der xAI-Server fährt die gesamte Such-/Reasoning-Schleife selbst und liefert **eine fertige, mit Quellen belegte Antwort in einem einzigen Response** zurück.

**Wichtig: NICHT über den `AssistantTool`/`ToolRegistry`/`@IntoSet`-Seam routen.** Dieser Seam emittiert über `ToolSchema.toDto()` (GrokProvider.kt:148-153) ausschließlich `type:"function"`-Tools (clientseitig), die die Ausführung pausieren — und würden damit den fehlenden Orchestrierungs-Loop *und* die Aufgabe von `store=false` erzwingen. Server-seitige Tools tun das nicht.

### Begründung

Dies ist die kleinste Änderung, die jede bestehende Randbedingung erfüllt:

- **Kein Loop nötig.** `LlmTurnRunner.run` ruft `provider.complete(req)` exakt einmal (LlmTurnRunner.kt:81) und liest danach nur `response.content` (Zeile 100). Ein server-seitiges Tool liefert die finale Antwort als normales `output_text` — der Runner bleibt **unverändert**.
- **`store=false` bleibt gültig.** Es ist in `GrokProvider.buildRequest` hartkodiert (GrokProvider.kt:74). Nur clientseitige Function-Tools würden eine Continuation via `previous_response_id`/`use_encrypted_content` erzwingen.
- **Kein zweites Secret, kein zweiter Datenempfänger.** Das Diktat erreicht nur xAI — dieselbe Partei, die durch Consent + Keystore-Key bereits abgedeckt ist. Ein externer Such-API-Ansatz (Tavily/Brave/Exa) wäre strikt schlechter: zweiter Empfänger, zweites Secret, zwei Netz-Hops über flakiges In-Car-Mobilfunknetz.
- **Antwort kommt durch den bestehenden Pfad.** `LlmResponseFormatter` (Markdown-Strip, ~800-Zeichen-Cap) verarbeitet `output_text` bereits.

### Alternativen

| Ansatz | Pro | Contra |
|---|---|---|
| Natives `web_search` über dediziertes Flag (**empfohlen**) | Kleinste Änderung; kein Loop; kein zweites Secret; beste Privacy; eine Round-Trip | Provider-gebunden (xAI); ~$5/1k erfolgreiche Tool-Aufrufe |
| Eigenes `internet_search`-`AssistantTool` über externe Such-API (Tavily/Brave/Exa) | Provider-unabhängig; arbeitet später mit Nicht-xAI-Providern | Erfordert den fehlenden V3-Loop; zweites Secret; zweiter Datenempfänger (Privacy-Regression); doppelte Latenz; **nur** sinnvoll für Fähigkeiten, die `web_search` nicht hat |
| Legacy „Live Search" via `search_parameters` | (historisch) einziger Pfad mit `news`/`rss`-Quelltypen | **⚠️ Korrektur:** zurückgezogen (~2026-01-12), liefert HTTP **410 Gone** — Non-Starter, nicht implementieren |
| Zusätzlich `x_search` default-an | Echtzeit-X/Twitter-Grounding im selben Call | Mehr Kosten + mehr verrauschte Quellen vorgelesen; besser als separates opt-in, default AUS |

### Konkrete Umsetzungsschritte

1. **Zuerst live verifizieren** (vor dem Coden): über den seit 0.5.0 vorhandenen lokalen xAI-Key-Test einen `POST /v1/responses` mit `model grok-4.3`, `store=false` und `tools:[{type:"web_search"}]` absetzen für eine Frage, die aktuelle Infos braucht. Prüfen: (a) HTTP 200 (also `store=false` mit `web_search` akzeptiert), (b) Antwort als `output_text`, (c) wo Zitate landen (Top-Level-`citations`-Array vs. inline `annotations`), (d) exaktes Nesting von `allowed_domains`/`excluded_domains`. Reale Response-Form festhalten.
2. `GrokDtos.kt`: `ResponsesServerTool(type, allowedDomains?, excludedDomains?)` mit `@SerialName`-Mapping hinzufügen; `ResponsesRequest.tools` so umstellen, dass Function-Tools *und* Server-Tools in einem Array koexistieren (Empfehlung: `val tools: List<JsonElement>? = null`, über `@AssistantJson` enkodiert). `citations: List<String>?` an `ResponsesResponse` + `annotations` an `ResponsesContentBlock` ergänzen (gemäß Schritt 1).
3. `LlmRequest.kt`: `val webSearch: Boolean = false` (optional `val xSearch: Boolean = false`) ergänzen; `tools: List<ToolSchema>` bleibt (in V2 leer); KDoc aktualisieren.
4. `GrokProvider.buildRequest`: Tools-Liste = vorhandene Function-Tools (`req.tools.map { it.toDto() }`) **plus**, wenn `req.webSearch`, ein `ResponsesServerTool(type="web_search")` (und `x_search` bei `req.xSearch`). `null` lassen, wenn kombiniert leer. `store=false` belassen. Tool-Inhalte nicht loggen.
5. `AssistantPreferencesStore.kt`: `booleanPreferencesKey("web_search_enabled")` + `webSearchEnabled()/setWebSearchEnabled()` (+ `webSearchFlow()` für die UI). Default: opt-in **false** empfohlen.
6. `LlmTurnRunner.run`: im `LlmRequest`-Builder (Zeile 71-79) `webSearch = prefs.webSearchEnabled()` setzen. Keine Loop-Änderung. Optional nur den `server_side_tool_usage`-COUNT loggen (nie Queries/URLs).
7. Settings-UI: Toggle „Internetzugriff erlauben / Allow internet access" über `SettingsViewModel.setWebSearchEnabled`, `SettingCard`/`StatusPill`-Tokens (kein Roh-Hex), `collectAsStateWithLifecycle`, IO in `withContext(ioDispatcher)`. EN + DE Strings in `res/values/` und `res/values-de/`.
8. Consent-/Docs-Text: Privacy-Consent erweitern (mit Internetzugriff geht die diktierte Frage an xAIs Such-Subsystem — gleiche xAI-Vertrauensgrenze). `CHANGELOG.md` + `docs/` (NICHT README) + `channel/llm/README.md` (web_search von V3-Roadmap auf „shipped" verschieben; clientseitige Function-Tools bleiben V3).
9. Tests: `GrokProviderTest` erweitern — mit `webSearch=true` emittiert `buildRequest` einen `tools`-Eintrag `type:"web_search"` (und `store` weiterhin `false`); eine `output_text`-Antwort mit `citations`-Array mappt auf `LlmResponse.content` **ohne** URLs in den Content zu spleißen. `AssistantPreferencesStore`-Robolectric-Test für Default/Persistenz des Flags.
10. On-Device-Check im Tesla: mit Toggle AN eine Aktuell-Info-Frage an den Grok-Kontakt diktieren; gesprochene Antwort gegroundet, ohne vorgelesene URLs/Markdown, innerhalb des ~800-Zeichen-Caps (keine Mitten-im-Satz-Abschneidung durch Zitatmarker).
11. `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.channel.llm.*"` und `:app:assembleDebug`.

### Codebase-Integration

- **`AssistantTool`/`@IntoSet` in `LlmModule.kt`:** bewusst **NICHT** angefasst. Web-Suche ist ein Flag, keine Tool-Implementierung.
- **`GrokProvider`:** Request-Seite (`buildRequest`) ändern (Server-Tool anhängen); `mapResponse`/`extractText` optional erweitern (Zitate parsen, aber nie in den Body spleißen).
- **Bridge-Injection:** unverändert (`maybeInjectFollowUp` → `SmsContentProviderWriter.injectIncoming`).
- **KeyStore:** unverändert — der einzige xAI-Key wird wiederverwendet.
- **OkHttp/DI:** unverändert (`@AssistantJson`/`@AssistantHttpClient`/`@AssistantRetrofit`, `RedactingAuthInterceptor`, 60s readTimeout). `network_security_config.xml` erlaubt `api.x.ai` bereits.
- **Neue Dateien:** keine zwingend nötig (alles feld-/flag-level). Optional kleines `WebSearchTools.kt` für das `ResponsesServerTool`-DTO.

### APIs & Auth

- **Endpoint:** `POST https://api.x.ai/v1/responses` (unverändert; `GrokConfig.BASE_URL="https://api.x.ai/v1/"`, `ENDPOINT_RESPONSES="responses"`).
- **Auth:** bestehender xAI-Bearer-Key, pro Call in `GrokProvider.complete` über `apiKeyStore.read()` gelesen und als `@Header("Authorization")="Bearer $key"` (`GrokApi.kt`) gesendet. Kein neuer Scope, kein neuer Account, kein zweites Secret.
- **Aktivierung:** Request-Body `"tools": [ { "type": "web_search" } ]` (optional `{ "type": "x_search" }`). Optionale `web_search`-Config: `allowed_domains` (max 5) / `excluded_domains` (max 5, gegenseitig exklusiv), `enable_image_understanding`, `enable_image_search` — **exaktes Nesting (direkt am Tool-Objekt vs. `filters`-Sub-Objekt) ist in den Quellen widersprüchlich und muss live geprüft werden** (Schritt 1). `x_search`-Config: `allowed_x_handles`/`excluded_x_handles` (max ~20, exklusiv), `from_date`/`to_date` (ISO8601).
- **Response:** finale Antwort als `output_text` plus Top-Level-`citations`-Array und inline `annotations` (`type: url_citation`: `url`, `title`, `start_index`, `end_index`); In-Text-Markdown `[[N]](url)`. `usage.server_side_tool_usage` meldet Zähler; `usage.cost_in_usd_ticks` bündelt Token+Tool-Kosten.
- **Modell:** muss suchfähig sein. `DEFAULT_MODEL "grok-4.3"` qualifiziert; `grok-4.1-fast` ist günstiger/latenzärmer (reine Settings-Änderung).
- **Kosten:** ~$5 pro 1.000 **erfolgreiche** Tool-Aufrufe (web_search und x_search je); ein Diktat kann mehrere interne Suchen auslösen.

### Einschränkungen & Risiken

- **⚠️ Korrektur:** Die ursprüngliche Annahme „native Live Search via `search_parameters`" ist **falsch für eine heutige Umsetzung**. `search_parameters` wurde zurückgezogen (~2026-01-12) und liefert **HTTP 410 Gone** („Live search is deprecated. Please switch to the Agent Tools API"). Nur die nativen `web_search`/`x_search`-Agent-Tools auf `/v1/responses` funktionieren noch.
- **⚠️ Korrektur:** Die Tool-Draht-Form ist inkompatibel mit dem bestehenden Seam. `ResponsesTool`/`ToolSchema.toDto()` setzt `type="function"` hart und verlangt `name`/`description`/`parameters`. Ein Server-Tool ist `{type:"web_search"}` **ohne** `name`/`parameters` — es kann durch den `AssistantTool`-Seam nicht ausgedrückt werden; neues DTO + `buildRequest`-Änderung sind Pflicht.
- **⚠️ Unbestätigt:** Dass `store=false` mit `web_search` akzeptiert wird, ist eine hochzuverlässige Inferenz, aber **nicht durch einen expliziten Doku-Satz belegt** — in Schritt 1 live bestätigen.
- **Suchfähiges Modell nötig:** ein vom Nutzer auf ein Nicht-Such-Modell überschriebenes `model` sucht ggf. stillschweigend nicht. Warnung/Validierung erwägen.
- **Kosten pro Diktat unbeschränkt:** ein Turn kann mehrere interne Suchen triggern; TMM hat `SendBudget`, aber kein LLM-Spend-Budget. Toggle (default aus) + ggf. `server_side_tool_usage`-Count (nur Count) für Diagnostics.
- **Latenz:** der server-seitige Such-Loop kostet echte Sekunden vor dem Vorlesen. `readTimeout` 60s (HttpModule) — wahrscheinlich ausreichend, bei Multi-Search-Turns prüfen. `grok-4.1-fast` senkt Latenz/Kosten.
- **TTS-Hygiene:** Zitate kommen als inline `[[N]](url)` + `citations`-Array. `LlmResponseFormatter` strippt Markdown und cappt ~800 Zeichen. Prüfen, dass keine URLs in den gesprochenen Body lecken und Zitatmarker keine Mitten-im-Satz-Abschneidung verursachen. `extractText` muss `output_text`-only bleiben.
- **Privacy/Logging (CLAUDE.md):** nie Diktat/Query/Suchergebnisse/URLs in `LogBuffer` (via `DiagnosticsExporter` exportiert). `RedactingAuthInterceptor` (method+path+status only) beibehalten. Nur Counts/Längen loggen.
- **i18n-Parität:** Toggle + Consent-Strings in `res/values` (EN) und `res/values-de` (DE); `gradlew lint` crasht (Projekt-Memory) → Parität manuell prüfen.

### Quellen

- https://docs.x.ai/developers/tools/web-search
- https://docs.x.ai/developers/tools/x-search
- https://docs.x.ai/docs/guides/tools/overview
- https://docs.x.ai/developers/tools/advanced-usage
- https://docs.x.ai/developers/tools/citations
- https://docs.x.ai/developers/pricing
- https://docs.x.ai/developers/tools/tool-usage-details
- https://docs.x.ai/developers/release-notes
- https://github.com/langchain-ai/langchain/issues/33961

---

## Spotify-Steuerung

### Empfohlener Ansatz

Grok-Function-Calling mit einem `control_spotify`-`AssistantTool` über die **Spotify Web API** (`/v1/me/player/*` + `/v1/search`), Auth via **Authorization Code + PKCE** (kein Client-Secret). Zwei Teile:

**Teil A — den fehlenden Tool-Execution-Loop in `LlmTurnRunner` bauen** (der eigentliche Blocker, wiederverwendbar für alle künftigen Tools). Nach `provider.complete(req)`: ist `response.toolCalls` nicht-leer, jeden `ToolCall.argumentsJson` zu `JsonObject` parsen, `toolRegistry.invoke(name, args)` rufen, jedes `ToolInvocationResult` als `function_call_output`-`ResponsesInputItem` (`type="function_call_output"`, `call_id=toolCall.id`, `output=result-JSON`) sammeln, den Assistant-`function_call`-Turn + die Tool-Outputs an den Request-Input anhängen und einen **zweiten** `provider.complete` für die natürlichsprachliche Zusammenfassung absetzen. Cap bei `MAX_TOOL_ROUNDS` (z.B. 2). **Wichtig:** Die Tool-Aktion ist bereits passiert — schlägt der Folge-Turn fehl, bleibt das Musik-Kommando erfolgreich; auf eine generische lokalisierte Bestätigung degradieren statt `EmptyResponse`. Alle Guards (Mutex, TTL, Rate-Limiter, `CancellationException`-Rethrow, kein-Body-Logging) erhalten; Rate-Slot **nicht** refunden, sobald ein Tool erfolgreich lief.

**Teil B — `control_spotify`-Tool + Spotify-Stack.** Ein Tool mit Enum-Parameter `action {play, pause, next, previous, transfer_to_car, set_volume}` plus optional `query`/`volume_percent` (ein Tool statt sechs: kleineres Schema, weniger Tokens, eine Stelle für die Device-Resolution-/Fallback-Leiter). Dazu `SpotifyPlayerClient` (Retrofit), `SpotifyAuthClient` (Token-Exchange/Refresh), `SpotifyTokenStore` (Refresh-Token verschlüsselt nach exaktem `KeystoreApiKeyStore`-Muster in neuer DataStore `mfs_spotify_secure`), `SpotifyDeviceResolver` (Tesla wählen, Transfer-first, Premium/No-Device-Fallback). Einmalige Consent-UI „Spotify verbinden" via PKCE-Browser-Flow (Custom Tabs), HTTPS-Redirect als Android App Link.

### Begründung

Der `AssistantTool`/`ToolRegistry`/`@IntoSet`-Seam ist exakt für dieses Feature gebaut, und `GrokProvider` sendet Tool-Schemata bereits (GrokProvider.kt:75) und parst `function_call` in `LlmResponse.toolCalls` (GrokProvider.kt:81-89); `ResponsesInputItem` trägt bereits `type/call_id/output`. Das einzige fehlende Stück ist der Orchestrierungs-Loop. Ihn einmal zu bauen schaltet jedes künftige Tool frei; Spotify ist das natürliche erste konkrete Tool. Die Verifikation bestätigt: Player + Search + Transfer überleben beide Deprecation-Wellen (Nov-2024, Feb-2026); Auth-Code+PKCE ist der korrekte Mobile-Flow; Premium + verfügbares Connect-Device sind harte Laufzeit-Gates. Das Anvisieren des Tesla als Connect-Device per `device_id` ist cloud-vermittelt (orthogonal zum Bluetooth-MAP-Bridge) und passt sauber.

### Alternativen

| Ansatz | Pro | Contra |
|---|---|---|
| Grok-Function-Calling + `control_spotify` Web-API-Tool (**empfohlen**) | Nutzt den vorhandenen Seam; LLM handhabt Freitext-Songtitel; baut den wiederverwendbaren Loop | Erfordert den fehlenden Loop; zweiter Round-Trip + 1–3 Spotify-Calls; Premium/Device-Gates |
| Deterministischer Intent-Parser (Regex/Keyword) statt LLM | Kein Loop; geringere Latenz; keine Token-Kosten; testbar ohne Provider | Brüchige NLU für DE+EN-Phrasen + Freitext-Songtitel; dupliziert Routing; lässt den vorhandenen Seam veröden |
| Spotify Android SDK App Remote (steuert die Phone-App) | Phone-App ist das Device → umgeht „no active device"; Basis-Transport free | Steuert die **Phone**-Spotify, nicht das Auto; spezifischer Track-URI braucht Premium; schwere native Dependency; nicht das Feature-Ziel |
| Sechs Einzel-Tools (`spotify_play` …) | Triviale Einzelschemata; eindeutige Tool-Wahl | 6× Schema-Tokens pro Turn; 6 `@IntoSet`-Bindings; Fallback-Leiter dupliziert |

### Konkrete Umsetzungsschritte

1. **Spotify-App im Developer-Dashboard registrieren:** Client-ID holen, Android-API wählen, **beide** Package-IDs (`io.github.lycheeappf.tmm` und `io.github.lycheeappf.tmm.debug`) mit ihren Debug+Release-SHA1 (via `keytool -list -v`), HTTPS-Redirect (App Link, kein Custom-Scheme) eintragen. **Empfohlenes Modell = BYO-Client-ID:** jeder Nutzer legt seine eigene App an und trägt die Client-ID in TMM ein → der 5-Nutzer-Dev-Mode-Cap (pro App) ist damit irrelevant. Jeder Nutzer braucht Premium (Feb-2026). Eine geteilte Client-ID wäre auf ≤5 Nutzer begrenzt.
2. **Tool-Loop in `LlmTurnRunner.run` bauen** (noch ohne Spotify-Code): `response.toolCalls` lesen, `argumentsJson` zu `JsonObject` parsen, `toolRegistry.invoke`, `function_call_output`-Items bauen, gebundenes Folge-`complete` (`MAX_TOOL_ROUNDS=2`), finalen Text formatieren. Mutex/TTL/Rate-Limit/Refund/Cancellation erhalten; **nicht** refunden nach erfolgreichem Side-Effect; nur Tool-Name+Status loggen. `LlmRequest` erweitern (oder `GrokProvider.completeWithToolResults`), um Assistant-`tool_calls` + Tool-Outputs in den nächsten Input zu tragen.
3. **Loop unit-testen** (MockWebServer): Fake-Provider gibt erst Tool-Call, dann Text-Turn; Fake-`AssistantTool`. Asserten: `toolRegistry.invoke` aufgerufen, zweiter Request enthält `function_call_output` mit richtiger `call_id`, `TurnResult.Success` trägt finalen Text. Plus: Tool-only + scheiternder Folge-Turn → nutzbare Bestätigung statt `EmptyResponse`.
4. **`SpotifyTokenStore` + `KeystoreSpotifyTokenStore`** (Klon von `KeystoreApiKeyStore`: neuer Alias, neue DataStore `mfs_spotify_secure`, AES-256-GCM, **nicht** auth-gated → läuft auf Lockscreen). In `SpotifySecurityModule` binden; Backup-/Data-Extraction-Ausschluss ergänzen.
5. **`SpotifyAuthClient`** (`accounts.spotify.com` Token-Exchange + Refresh, `x-www-form-urlencoded`, kein Secret) + `AccessTokenProvider` (In-Memory-Access-Token mit Expiry, proaktiver Refresh + on-401, neues Refresh-Token persistieren). `SpotifyHttpModule` mit `@Spotify*`-Qualifiern + Redacting-Interceptor + Retrofit-BaseURLs.
6. **`SpotifyApi`** (Retrofit: devices, transfer, play, pause, next, previous, volume, search — **korrekte Verben PUT/POST pro Endpoint**) + `SpotifyDtos`. **`SpotifyDeviceResolver`:** `GET /devices` frisch je Turn, Tesla nach `type`/`name` wählen, Transfer-to-car (`play:true`) bevorzugen, `device_id` an jedem Control-Call, `403 PREMIUM_REQUIRED`/`404 NO_ACTIVE_DEVICE`/Restricted-Reject auf typisierte Outcomes mappen.
7. **`SpotifyTool : AssistantTool`** implementieren. `schema` name `"control_spotify"`, `parametersJson` Enum-`action` + optional `query`/`volume_percent`. `invoke()` auf `@IoDispatcher`: nicht verbunden → `Success {status:not_connected}`; `play`+`query` → Search dann play/transfer; jedes Outcome → `Success`-JSON, das die LLM verbalisieren kann (status + human-readable reason). `429 Retry-After` ehren. `coRunCatching` (Cancellation rethrow). Nie `query`/Tracknamen loggen.
8. **`@Binds @IntoSet`** für `SpotifyTool` in `LlmModule.kt` (eine Zeile). Verifizieren, dass `ToolRegistry.activeSchemas()` es zurückgibt und `GrokProvider` es sendet.
9. **Consent-UI:** `SpotifyConnectScreen` + ViewModel, PKCE-Verifier/Challenge, Authorize-URL via Custom Tabs, App-Link-Redirect, Code-Exchange, Refresh-Token speichern, Connected-State. Manifest App-Link-`intent-filter` (`autoVerify`) + `assetlinks.json` hosten. Settings-Card „Spotify verbinden/trennen" + EN/DE-Strings.
10. **System-Prompt-Addendum** (lokalisiert) zur Tool-Existenz; User-Prompt-Override-Verhalten erhalten.
11. **On-Device im echten Tesla:** Auto erscheint in `/devices` nur bei geöffneter Spotify-App; empirisch klären, ob die Headunit `is_restricted` meldet und welchen Status Restricted-Control liefert; play-by-search/pause/next/previous/volume/Premium-fehlt testen; Bestätigung via MAP-TTS prüfen.
12. **Docs:** `channel/llm/README.md` (Tool-Tabelle + Flow), CHANGELOG, Version in `libs.versions.toml`/`build.gradle.kts`. i18n-Parität manuell (lint crasht). `gradlew.bat :app:testDebugUnitTest` für llm + spotify.

### Codebase-Integration

- **`AssistantTool`/`@IntoSet` in `LlmModule.kt`:** `@Binds @IntoSet abstract fun bindSpotifyTool(impl: SpotifyTool): AssistantTool` — eine Zeile; `@Multibinds Set<AssistantTool>` greift es auf, `ToolRegistry.activeSchemas()` liefert es, `GrokProvider.buildRequest` sendet es (keine Provider-Änderung für die Request-Seite).
- **`GrokProvider`:** Request- und Parse-Seite fertig. Für den Folge-Turn `buildRequest` (oder eine neue Provider-Methode) erweitern, um den Assistant-`function_call`-Echo + `function_call_output`-Item (`ResponsesInputItem` trägt `type/call_id/output` bereits) zu serialisieren — ggf. `name`/`arguments`-Feld am Input-Item für den Echo ergänzen, falls die API es verlangt.
- **`LlmTurnRunner.kt`:** **die zentrale Änderung** — Tool-Loop zwischen `provider.complete` und Formatter (siehe Schritt 2), alles unter `session.mutex.withLock`.
- **Bridge-Injection:** unverändert — `LlmChannel.handleTeslaReply` → `turnRunner.run` → `TurnResult.Success` → `ReplyResult.FollowUp` → `maybeInjectFollowUp` → `SmsContentProviderWriter.injectIncoming` → Fake-INBOX → MAP-TTS. Die Tool-Bestätigung wird Groks finaler Text.
- **KeyStore:** `KeystoreApiKeyStore`-Muster wiederverwenden, **nicht** den xAI-Key-Slot überladen. Neuer `SpotifyTokenStore` + Impl in neuem Modul.
- **OkHttp/DI:** `@SpotifyJson/@SpotifyHttpClient/@SpotifyRetrofit` + `SpotifyHttpModule` (Spiegel von `HttpModule`, das xAI-only bleibt). `network_security_config.xml` ist bereits HTTPS-only + System-Trust-Anchors + kein Pinning — erlaubt `api.spotify.com`/`accounts.spotify.com` bereits, keine Änderung.
- **Neue Dateien (ca.):** `channel/llm/tools/SpotifyTool.kt`; `channel/spotify/{SpotifyPlayerClient, SpotifyAuthClient, SpotifyApi, SpotifyDtos, SpotifyConfig, SpotifyDeviceResolver}.kt`; `core/security/{SpotifyTokenStore, KeystoreSpotifyTokenStore}.kt`; `core/di/{SpotifyHttpModule, SpotifySecurityModule}.kt`; `ui/spotify/{SpotifyConnectScreen, ViewModel}` + PKCE-Helper; `res/values` + `values-de` Strings; `assetlinks.json`.
- **Geänderte Dateien:** `LlmTurnRunner.kt`, `LlmModule.kt`, `LlmRequest.kt`/`GrokProvider.kt`, `AndroidManifest.xml` (App-Link-`intent-filter`), Settings-UI, Backup-/Data-Extraction-Regeln, `channel/llm/README.md`/CHANGELOG.

### APIs & Auth

**Auth (Authorization Code + PKCE, kein Client-Secret):**
- Authorize: `GET https://accounts.spotify.com/authorize?client_id=...&response_type=code&redirect_uri=<https app-link>&code_challenge_method=S256&code_challenge=<base64url(SHA256(verifier))>&scope=user-read-playback-state%20user-modify-playback-state&state=<random>`
- Token-Exchange: `POST https://accounts.spotify.com/api/token` (`x-www-form-urlencoded`) `grant_type=authorization_code&code=...&redirect_uri=...&client_id=...&code_verifier=...` → `{access_token, token_type=Bearer, expires_in=3600, refresh_token, scope}`
- Refresh: `POST .../api/token` `grant_type=refresh_token&refresh_token=...&client_id=...` (kein Basic-Header). `refresh_token` kann neu ausgegeben werden — altes behalten, wenn nicht; `invalid_grant` ⇒ Re-Authorize.
- **Scopes:** `user-read-playback-state` (Devices + State) + `user-modify-playback-state` (alle Controls). Search braucht keinen Scope.

**Playback-Endpoints** (Base `https://api.spotify.com/v1`; Bearer; **alle Control-Endpoints brauchen Premium** → `403 PREMIUM_REQUIRED` bei Free; `429` trägt `Retry-After` Sekunden):
- `GET /me/player/devices` → `devices[]{id, is_active, is_restricted, name, type, volume_percent, supports_volume}`; je Turn neu holen, IDs nicht stabil.
- `PUT /me/player` Body `{"device_ids":["<teslaId>"],"play":true}` — **genau EINE ID** (mehrere = 400); akzeptiert auch bei `is_restricted=true`; 204. Primäres „Auto aktivieren/starten"-Primitiv.
- `PUT /me/player/play?device_id=<id>` Body `{context_uri|uris:[...],offset,position_ms}`; leerer Body resumt (Verhalten medium-confidence — testen); 204.
- `PUT /me/player/pause?device_id=<id>` (**PUT**); `POST /me/player/next?device_id=<id>` (**POST**); `POST /me/player/previous?device_id=<id>` (**POST**). Falsches Verb = 405. Bei `is_restricted` ggf. abgelehnt → Transfer-Fallback.
- `PUT /me/player/volume?volume_percent=0..100&device_id=<id>` (`volume_percent` ist QUERY-Param; nur bei `supports_volume=true`).
- `GET /v1/search?q=<text>&type=track&limit=1` → `tracks.items[0].uri` (kein Scope). Dev-Mode-Such-Limit auf max 10 gecappt (Feb-2026).

**Token-Handling:** `refresh_token` verschlüsselt at rest (AES-256-GCM, `KeystoreApiKeyStore`-Muster, neue DataStore `mfs_spotify_secure`); `access_token` nur In-Memory mit Expiry; nie geloggt. `RedactingAuthInterceptor` (geklont) loggt nur method+path+status — nie Bearer/refresh_token/Query/Tracknamen.

### Einschränkungen & Risiken

- **HARTES GATE (bestätigt):** jeder play/pause/next/previous/transfer/volume-Call braucht **Spotify Premium**; Free → `403 PREMIUM_REQUIRED`. Lokalisierten „Premium erforderlich"-Fallback sprechen. Zusätzlich muss der Dev-Mode-App-**Owner** Premium haben (Feb-2026).
- **HARTES GATE — ⚠️ Korrektur:** Ein bereits **aktives** Device ist **nicht strikt erforderlich**. Übergibt man eine gültige `device_id` aus `/devices`, kann ein idle Device aktiviert werden — Transfer-first ist *eine* Abhilfe, nicht zwingend. Ohne jegliches verfügbares Device → `404 NO_ACTIVE_DEVICE`. Der Tesla erscheint nur in `/devices`, wenn seine Spotify-App offen ist; eine ganz ausgeschaltete Headunit lässt sich per Web-API nicht wecken.
- **Restricted-Device-Risiko:** Headunits sind oft `is_restricted=true` → direkte play/pause/next werden abgelehnt, **nur** Transfer akzeptiert. Ob der konkrete Tesla `is_restricted` meldet und welchen Status er liefert, ist **offene On-Device-Frage**.
- **⚠️ Korrektur — Dev-Mode-Cap gilt PRO APP, nicht pro Feature:** Dev-Mode ist auf **5** allowlisted Nutzer pro registrierter App gecappt (nicht 25); Extended Quota ist seit Mai-2025 org-only (~250k MAUs). Daraus folgt **nicht**, dass öffentliche Verteilung unmöglich ist: das **empfohlene Verteilungsmodell ist BYO-Client-ID** — jeder Nutzer legt seine **eigene** Spotify-App im Dashboard an und trägt deren Client-ID in TMM ein. Da jede App nur ihren einen Owner als Nutzer hat, wird der 5er-Cap nie erreicht; es braucht **kein** Extended-Quota-Review und **keine geteilte** Client-ID im APK. Dasselbe BYO-Muster wie bei Tesla (siehe Navi). Kosten: nur eine öffentliche Client-ID (kein Secret, PKCE). Vorbehalte: (a) einmaliges Dashboard-Setup pro Nutzer (App anlegen, feste TMM-Redirect-URI einsetzen, ggf. eigene E-Mail in die User-Liste); (b) jeder Nutzer braucht selbst Premium (statt nur „der Owner"); (c) Spotifys **Developer Policy** gegenlesen, ob das „jeder bringt seine eigene App"-Modell für den Verteilungsweg zulässig ist. Eine **geteilte** Client-ID bliebe dagegen auf ≤5 Nutzer beschränkt.
- **BLOCKER (zuerst bauen):** Function-Calling ist V2-Stub — `LlmTurnRunner` ignoriert `response.toolCalls`, ruft nie `ToolRegistry.invoke`, baut nie `function_call_output`; ein Tool-only-Response wird `EmptyResponse`. Der Loop muss vor jedem Tool existieren.
- **Auth:** nur PKCE (kein Secret im APK). Redirect HTTPS via verifiziertem App Link — **⚠️ Custom-Schemes scheitern post-April-2025 mit „Insecure redirect URI"** (medium-confidence Community-Finding). Einmaliger Browser-Consent unvermeidlich.
- **Verben unterscheiden sich** (pause=PUT, next/previous=POST, volume=PUT mit `volume_percent` als QUERY); falsches Verb = 405. Transfer `device_ids` = genau eine ID. Device-IDs instabil → je Turn neu holen.
- **Latenz/Kosten:** der Loop kostet einen zweiten LLM-Round-Trip + 1–3 Spotify-Calls; mit `MAX_TOOL_ROUNDS` begrenzen, `device_id` übergeben. `429 Retry-After` auf xAI und Spotify ehren (Spotify rolling 30s).
- **Nicht-transaktional:** die Spotify-Aktion committet vor dem Summary-Turn → Rate-Slot nach Erfolg **nicht** refunden, keinen Fehler melden; bei Summary-Fehler generisch bestätigen.
- **Privacy/Logging:** diktierte Songtitel sind body-nah → nie `query`/Tracknamen/`refresh_token` in `LogBuffer`. `RedactingAuthInterceptor` auch für Spotify-Host. `refresh_token` AES-GCM, nicht auth-gated.
- **Cloud-vermittelt:** Steuerung über `api.spotify.com` (Internet), **nicht** über Bluetooth-MAP/PBAP — Phone und Tesla brauchen Internet, Tesla muss in **demselben** Spotify-Account angemeldet sein.

### Quellen

- https://developer.spotify.com/documentation/web-api/reference/get-a-users-available-devices
- https://developer.spotify.com/documentation/web-api/reference/transfer-a-users-playback
- https://developer.spotify.com/documentation/web-api/reference/start-a-users-playback
- https://developer.spotify.com/documentation/web-api/reference/pause-a-users-playback
- https://developer.spotify.com/documentation/web-api/reference/skip-users-playback-to-next-track
- https://developer.spotify.com/documentation/web-api/reference/set-volume-for-users-playback
- https://developer.spotify.com/documentation/web-api/reference/search
- https://developer.spotify.com/documentation/web-api/concepts/scopes
- https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow
- https://developer.spotify.com/documentation/web-api/tutorials/refreshing-tokens
- https://developer.spotify.com/documentation/web-api/concepts/redirect_uri
- https://developer.spotify.com/documentation/web-api/concepts/quota-modes
- https://developer.spotify.com/documentation/web-api/concepts/rate-limits
- https://developer.spotify.com/documentation/web-api/references/changes/february-2026
- https://developer.spotify.com/blog/2024-11-27-changes-to-the-web-api
- https://developer.spotify.com/blog/2026-02-06-update-on-developer-access-and-platform-security
- https://connect.spotify.com/devices/tesla
- https://commonsware.com/blog/2025/04/12/spotify-android-sdk-redirect-uri-schemes.html

---

## Fahrziel ins Tesla-Navi

### Empfohlener Ansatz

Zwei Schichten, gated hinter `developerMode` + explizitem BYO-Credentials-Opt-in:

**Layer 1 (Voraussetzung, backend-agnostisch, Großteil der Arbeit):** den fehlenden Tool-Execution-Loop in `LlmTurnRunner` bauen — identisch zu Spotify Teil A.

**Layer 2:** ein `navigate_to`-`AssistantTool`, dessen `invoke()` die Tesla Fleet API ruft: `POST /api/1/vehicles/{vin}/command/navigation_request` mit Body `{"type":"share_ext_content_raw","value":{"android.intent.extra.TEXT":"<address|maps-url>"},"locale":"<en-US|de-DE>","timestamp_ms":"<unix-ms>"}`.

**⚠️ Korrektur (load-bearing):** Der korrekte aktuelle Endpoint-Name ist `navigation_request` (nicht „share" — das ist die Legacy-Owner-API-Bezeichnung). Und entscheidend: dieser Command ist **kein** signierter End-to-End-Vehicle-Command. Tesals eigener Proxy gibt für ihn `ErrCommandUseRESTAPI` zurück → es ist ein **reiner OAuth-Bearer-REST-Call ohne Virtual-Key-Enrollment / Vehicle-Command-SDK-Signing**. Das eliminiert die größte Engineering-Kostenposition (kein Go/Rust-Signing-Port). Es braucht aber weiterhin volle Fleet-API-Partner-Registrierung (Domain + gehosteter EC-Public-Key + `POST /api/1/partner_accounts`), ein Per-User-`authorization_code`-Token mit `offline_access` + `vehicle_cmds`, und es ist pay-per-use.

**Default-Fallback** (keine Tesla-Credentials konfiguriert): `navigate_to` gibt einen Maps-Link/Adresse zurück, den `maybeInjectFollowUp` als normale Grok-SMS injiziert und der Fahrer antippt — kein Cloud, keine Kosten, passt zur bestehenden Architektur.

### Begründung

Der Android-Share-Intent-Pfad (`ACTION_SEND` an `com.teslamotors.tesla`) wird als Primärweg verworfen: **⚠️** es ist nur ein Share-Sheet-TARGET (kein dokumentierter programmatischer/stiller Intent), erfordert eine Foreground-Activity + Nutzer-Tap (Android 13+ blockt Background-Activity-Launches; alle TMM-Trigger sind ohne Foreground-Activity), und das Ziel lädt nur „nächste Fahrt" — falsch für einen live diktierenden Fahrer. Die Fleet API `navigation_request` ist still, dokumentiert und — verifiziert — **ungesignt** (im `ErrCommandUseRESTAPI`/Plain-REST-Set des Proxys), was die teuerste Position kollabieren lässt. Verbleibend schwer: Partner-Registrierung + Domain + OAuth + Cloud-Kosten + zweites Secret — daher opt-in BYO-Credentials, nicht Default; der SMS-Link-Fallback wahrt die No-Cloud-Haltung für alle anderen.

### Alternativen

| Ansatz | Pro | Contra |
|---|---|---|
| Fleet API `navigation_request` (ungesignt, opt-in) (**empfohlen**) | Einziger Weg, der das Auto-Navi still setzt; **verifiziert ohne Signing** | Partner-Registrierung + Domain + Public-Key; Pay-per-use; zweites Secret; bricht No-Cloud-Default |
| Maps-Link-in-SMS (kein Tesla-API) (**Default-Fallback**) | Null Permissions/Cloud/Kosten; passt exakt zur Architektur; schnell | Setzt das Navi **nicht** automatisch — Fahrer muss tippen; schwächere UX |
| Android `ACTION_SEND`-Share an Tesla-App | Kein OAuth/Account/Kosten/Signing | **⚠️** nur Share-Sheet-Target, kein stiller Intent; Foreground-Activity nötig; „nächste Fahrt"; undokumentiert, brüchig |
| Signiertes Vehicle-Command-Protocol (Proxy) | Genereller moderner Command-Pfad (für späteres Klima/Lock) | Unnötig für Navi (ist ungesignt); Go/Rust-Signing-Server + Virtual-Key-Enrollment; großer Maintenance-Aufwand |
| `navigation_gps_request` (lat/lon) | Kein Geocoding-Round-Trip; deterministisch; gleicher ungesignter Pfad | Grok muss korrekte Koordinaten liefern (Halluzinationsrisiko); Adress-String ist robuster (Tesla geocodiert serverseitig) — als optionaler Sekundärpfad behalten |

### Konkrete Umsetzungsschritte

1. **Spike/Validierung ZUERST:** mit echtem EU-Developer-Account manuell `POST /api/1/vehicles/{vin}/command/navigation_request` mit Bearer-User-Token (`vehicle_cmds`) gegen das eigene Auto curlen — bestätigen, dass es **ungesignt** (kein Virtual Key) funktioniert und das Auto das Ziel zeigt; Schlafzustand testen (ob `wake_up` nötig). De-riskt das ganze Feature vor dem Coden.
2. **Tool-Loop in `LlmTurnRunner.run`** unter `session.mutex` (siehe Spotify Teil A): `response.toolCalls` lesen, `toolRegistry.invoke`, `function_call_output`-Items, gebundenes Folge-`complete` (Round-Cap), nur finalen Content formatieren. Mutex/TTL/Rate-Limit/Refund/Cancellation + „nur Error-Class loggen" (LlmTurnRunner.kt:85) erhalten. Tests (MockWebServer): Tool-Call→invoke→Folge→finaler Text; Tool-only-dann-Text; Tool-Failure; Round-Cap.
3. `GrokProvider.buildRequest` + `GrokDtos` erweitern, um den Assistant-`function_call`-Echo + `function_call_output`-Input-Item zu serialisieren; `GrokProviderTest`-Case mit nicht-leerem Tool-Round-Trip (Bestand nutzt `tools=emptyList`).
4. **`TeslaTokenStore` + `KeystoreTeslaTokenStore`** (AES-256-GCM, AndroidKeyStore, neue DataStore-Datei) mit In-Memory-Fake für Tests; in TestInstallIn-barem Modul binden. Hält `refresh_token` + gecachtes `access_token` + Region-Host + VIN.
5. **`TeslaModule`** (eigene `@Tesla*`-Qualifier), `TeslaApi`/`TeslaDtos`/`TeslaNavClient`, Region-Resolution (`GET /users/region`), 401-Token-Refresh-`Authenticator`; Redacting-Interceptor (nur method+path+status — nie Token/Body/Ziel).
6. **`NavigateToTool`:** Creds vorhanden → REST-Nav (mit `wake_up`-Retry falls nötig); Creds fehlen → Maps-Link-Fallback als `Success`-JSON. Nie das Ziel in `LogBuffer` loggen.
7. **Registrierung:** eine `@Binds @IntoSet`-Zeile in `LlmModule.kt`.
8. **developerMode-gated Settings-UI:** `client_id` einfügen, PKCE-OAuth-Consent via Custom Tab, Vehicle-Picker (Fahrzeuge listen), Enable-Toggle; EN+DE-Strings mit Parität.
9. **End-to-End im echten Auto:** „navigiere zu <addr>" an Grok-Kontakt diktieren; Grok emittiert `navigate_to`, der Loop führt aus, das Auto-Navi aktualisiert, Groks gesprochene Bestätigung wird als Folge-SMS injiziert.
10. **Docs:** `channel/llm/README.md` (Tools nicht mehr V2-leer), Privacy/Kosten-Hinweis (Cloud-Call zu Tesla, pay-per-use), BYO-Credentials-Setup dokumentieren.

### Codebase-Integration

- **`AssistantTool`/`@IntoSet` in `LlmModule.kt`:** `@Binds @IntoSet abstract fun bindNavigateToTool(impl: NavigateToTool): AssistantTool` — eine Zeile; `@Multibinds Set<AssistantTool>` greift es auf.
- **`GrokProvider`/`GrokDtos`:** `buildRequest` emittiert aktuell nur role/content-Items (Zeile 60-68). Assistant-`function_call`-Item + `function_call_output`-Item ergänzen (`ResponsesInputItem` trägt `type/call_id/output` bereits — ggf. `name`/`arguments` am Input-Item für den Echo ergänzen). `ResponsesTool.toDto` existiert (GrokProvider.kt:148-153).
- **`LlmTurnRunner.kt`:** die Kernänderung (Loop, siehe Schritt 2).
- **`NavigateToTool.kt`** (neu): `schema = ToolSchema(name="navigate_to", description, parametersJson = {"type":"object","properties":{"destination":{"type":"string"},"latitude":{"type":"number"},"longitude":{"type":"number"}},"required":["destination"]})`.
- **Neue Dateien:** `channel/llm/tools/NavigateToTool.kt`; `channel/llm/tools/tesla/{TeslaNavClient, TeslaApi, TeslaDtos, TeslaConfig}.kt`; `core/security/{TeslaTokenStore, KeystoreTeslaTokenStore}.kt`; `core/di/TeslaModule.kt` (+ Binds-Modul für den TokenStore); developerMode-Settings-UI; EN/DE-Strings.
- **KeyStore:** **nicht** den xAI-`ApiKeyStore` (single `api_key`-Slot) wiederverwenden. Paralleler verschlüsselter Store (neue DataStore z.B. `mfs_tesla_secure`) mit `refresh_token` + `access_token` + Region-Host + VIN, AES-256-GCM unter dem AndroidKeyStore-Master-Key — gleiche Haltung wie `KeystoreApiKeyStore`.
- **OkHttp/DI:** `@TeslaJson/@TeslaHttpClient/@TeslaRetrofit` + `TeslaModule` (`HttpModule` bleibt xAI-only). `network_security_config.xml` (cleartext=false, kein Pinning, System-Trust) deckt `fleet-api.prd.eu.vn.cloud.tesla.com` + `auth.tesla.com` bereits — verifizieren, dass keine Per-Domain-Config nötig ist.
- **Unverändert:** `ChannelModule` (`LlmChannel` schon `@IntoSet`), der SMS-Injection-Pfad (`maybeInjectFollowUp` → `SmsContentProviderWriter.injectIncoming`, für den Fallback-Link wiederverwendet), Dispatcher-Qualifier (Tesla-Call auf `@IoDispatcher`).
- **`AndroidManifest`:** bei Custom-Tab/Redirect für OAuth den Redirect-`intent-filter`; keine neue Activity für den Bridge.

### APIs & Auth

**Primär-Command:** `POST {base}/api/1/vehicles/{vin}/command/navigation_request`. Body `{"type":"share_ext_content_raw","value":{"android.intent.extra.TEXT":"<addr und/oder maps URL>"},"locale":"de-DE","timestamp_ms":"<aktuelle unix MILLISEKUNDEN>"}`. **⚠️ Korrektur:** `timestamp_ms` ist Millisekunden (`int(now*1000)`), akzeptiert als JSON-String oder Int. Returns `{"response":{"result":true,"reason":""}}`. Scope `vehicle_cmds`; `Authorization: Bearer <user token>`. **Ungesigntes Plain-REST** (verifiziert via `teslamotors/vehicle-command` `command.go` → `ErrCommandUseRESTAPI`).

**Optional:** `navigation_gps_request` `{"lat","lon","order"}` falls Grok Koordinaten liefert. `navigation_sc_request` `{"id","order"}` — exakte Feldnamen unverifiziert, zurückstellen. `POST .../command/wake_up` wahrscheinlich nötig, bevor ein schlafendes Auto den Command akzeptiert; separat metered (~$1/50 Wakes).

**Region/Host:** EU-Host `https://fleet-api.prd.eu.vn.cloud.tesla.com` für den deutschen Nutzer; via `GET /api/1/users/region` auflösen; falscher Host ⇒ HTTP **412**.

**Auth-Flow:** (1) einmalig Partner-Setup auf `developer.tesla.com` → `client_id`/`client_secret` → Partner-Token via `POST https://auth.tesla.com/oauth2/v3/token grant_type=client_credentials` → `POST {base}/api/1/partner_accounts` einmal pro Region mit EC-Public-Key gehostet unter `https://<domain>/.well-known/appspecific/com.tesla.3p.public-key.pem`. (2) per-User: `authorization_code`-Flow bei `https://auth.tesla.com/oauth2/v3/authorize`, Scopes `openid offline_access vehicle_cmds`, getauscht bei `.../oauth2/v3/token` für Access+Refresh. Refresh persistiert; Access bei 401 erneuert.

**Token-Storage:** `refresh_token` + `access_token` + Region-Host + VIN AES-256-GCM verschlüsselt (neue DataStore, `KeystoreApiKeyStore`-Muster). **`client_secret` darf NICHT ins APK** — für eine Indie-No-Cloud-App PKCE-`authorization_code` (kein On-Device-Secret) nutzen und den `client_id` vom Nutzer einfügen lassen (BYO), oder ein nutzereigenes Mini-Backend. Das ist die zentrale Security-Entscheidung.

### Einschränkungen & Risiken

- **BLOCKER:** Function-Calling ist V2-Stub — der Tool-Loop muss zuerst gebaut werden (größte Einzelposition). Identisch zum Spotify-Blocker.
- **⚠️ Korrektur (positiv):** `navigation_request` ist **ungesignt** — kein Virtual-Key-Enrollment, kein Signing-SDK. Verifiziert über den offiziellen Proxy-Quellcode (`ErrCommandUseRESTAPI`). Damit entfällt die häufige (und falsche) Annahme, man brauche das volle Vehicle-Command-Signing.
- **Partner-Registrierung:** Domain + gehosteter EC-Public-Key (`/.well-known/appspecific/com.tesla.3p.public-key.pem`) + `partner_accounts` pro Region — nicht rein on-device machbar; der Maintainer braucht eine eigene Domain (einmalig, EU-Host).
- **Pay-per-use (seit 2025-01):** ~$1/1000 Commands, ~$1/50 Wakes, $10/Monat Credit. Ein schlafendes Auto erzwingt einen metered Wake; eine Billing-Beziehung widerspricht TMMs Free/No-Cloud-Framing → daher opt-in.
- **`client_secret` nie im APK** — PKCE + BYO `client_id` oder nutzereigenes Backend. Echte Architektur-Entscheidung.
- **Token-Revocation:** Nutzer kann `vehicle_cmds` jederzeit widerrufen; Feature muss graceful degradieren (Refresh → Re-Consent-Prompt → Maps-Link-Fallback).
- **Region-Mismatch ⇒ HTTP 412;** via `GET /users/region` auflösen und Host speichern.
- **⚠️ Restrisiko:** `navigation_request` ist *heute* via offiziellem Proxy-Quellcode als ungesignt verifiziert, aber Teslas Command-Regeln haben hohe Churn (Signing-Mandat + Pricing landeten beide 2024→2025); eine künftige Änderung könnte Signing verlangen — monitoren.
- **No-Logging-Regel doppelt:** nie das diktierte Ziel, die Tool-Argumente, das Tesla-Token oder Response-Bodies in `LogBuffer`/`DiagnosticsExporter` — nur Längen/Status/Error-Class, analog `RedactingAuthInterceptor` und LlmTurnRunner.kt:85.
- **Maps-Link-Fallback** routet das Auto-Navi **nicht** automatisch (SMS/MAP kann die Navi-UI nicht steuern) — Fahrer muss tippen; UX-Erwartung setzen.
- **⚠️ Share-Intent-Alternative ungeeignet:** Share-Sheet-only, Foreground-Activity nötig, Background-Launch auf minSdk 33 geblockt, „nächste Fahrt"-Timing — explizit verworfen.

### Quellen

- https://github.com/teslamotors/vehicle-command/blob/main/pkg/proxy/command.go
- https://github.com/Teslemetry/tesla_fleet_api
- https://developer.tesla.com/docs/fleet-api/endpoints/vehicle-commands
- https://developer.tesla.com/docs/fleet-api/authentication/third-party-tokens
- https://developer.tesla.com/docs/fleet-api/endpoints/partner-endpoints
- https://developer.tesla.com/docs/fleet-api/getting-started/base-urls
- https://developer.tesla.com/docs/fleet-api/billing-and-limits
- https://github.com/teslamotors/vehicle-command/blob/main/README.md
- https://tesla-api.timdorr.com/vehicle/commands/sharing
- https://www.slashgear.com/1229764/how-to-share-a-google-maps-destination-to-your-tesla/
- https://developer.android.com/training/sharing/receive

---

## Abschließender Hinweis: Ist Function-Calling im Code end-to-end verdrahtet?

**Nein — nur teilweise (am Draht, nicht in der Ausführung).** Verifiziert direkt im Quellcode:

- **Request-Seite verdrahtet:** `LlmTurnRunner.run` füllt `tools = toolRegistry.activeSchemas()` (LlmTurnRunner.kt:76); `GrokProvider.buildRequest` sendet sie via `tools = req.tools.takeIf { it.isNotEmpty() }?.map { it.toDto() }` (GrokProvider.kt:75), `ToolSchema.toDto()` mappt auf `ResponsesTool(type="function", …)` (GrokProvider.kt:148-153).
- **Response-Parse-Seite verdrahtet:** `GrokProvider.mapResponse` filtert `output` auf `type=="function_call"` und baut `List<ToolCall>` in `LlmResponse.toolCalls` (GrokProvider.kt:81-89).
- **⚠️ Der Orchestrierungs-Loop FEHLT:** `LlmTurnRunner.run` ruft `provider.complete(req)` einmal (Zeile 81) und macht danach **nur** `val cleaned = formatter.format(response.content.orEmpty())` (Zeile 100). Es liest **nie** `response.toolCalls`, ruft **nie** `toolRegistry.invoke(...)`, und konstruiert **nie** ein `function_call_output`-Item für einen Folge-Request. Gibt Grok ausschließlich Tool-Calls zurück (`content == null`), fällt der Runner in `if (cleaned.isBlank())` (Zeile 101) und liefert `TurnResult.EmptyResponse` — ein Tool-Call wird also als leerer/gescheiterter Turn behandelt, nicht ausgeführt. `ToolRegistry.invoke()` hat keinen Produktions-Aufrufer. Es existieren **null** `AssistantTool`-Implementierungen; `@Multibinds Set<AssistantTool>` ist in V2 leer.

**Konsequenz:** Function-Calling ist auf Protokoll-Ebene geplumbt (Schemata senden, `tool_calls` parsen), aber als End-to-End-Funktion ein nicht-funktionaler Stub. **Dieser Loop ist die gemeinsame, unumgängliche Voraussetzung für Spotify *und* Navi als Grok-Tools** — er ist backend-agnostisch und sollte einmal gebaut, getestet und für beide Features wiederverwendet werden. Der **Internetzugriff** über natives `web_search` ist hiervon **nicht** betroffen, weil server-seitige Tools die Schleife auf xAIs Infrastruktur fahren und eine fertige Antwort in einem Response liefern — er funktioniert ohne diesen Loop.
