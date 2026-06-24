# Channel: LLM — Grok-Assistent (V2)

Dieser Channel routet Tesla-Diktate an die xAI Grok API und schreibt die
Antwort als fake Inbox-SMS in den Provider, damit der Tesla TTS sie vorliest.

## Architektur in 1 Bild

```
[User tap "AI Chat starten" auf HomeScreen]
       │
       ▼
AssistantTriggerCoordinator.trigger(MANUAL_BUTTON)
       │
       ▼
LlmStarter.start()
   ├─ check: privacy consent + default SMS app + API-Key + sendBudget
   ├─ MappingRepository.allocateOrReuse(LLM, "default-assistant", payload)
   │     → deterministische FakeAddress +88810000001
   ├─ LlmConversationStore.reset(mappingId)
   └─ SmsContentProviderWriter.injectIncoming(addr, "Hey …", displayName="Grok")
              │
              ▼
   ┌──────────┴───────────┐
   │ AOSP MAP-Server      │
   │  → vCard mit "Grok"  │
   │  → Bluetooth → Tesla │
   └──────────────────────┘

[User diktiert im Tesla "Wie ist das Wetter?"]
       │
       ▼
AOSP MAP PushMessage → Outbox-Row "+88810000001"
       │
       ▼
OutboundSmsObserver.processRow
   ├─ InjectedMessageLedger.shouldIgnoreOutbound (Echo-Schutz, no-op normalerweise)
   └─ ReplyDispatcher.dispatch(addr, body)
              │
              ▼
        LlmChannel.handleTeslaReply
              │
              ▼
        LlmTurnRunner.run(mappingId, userText)
           [unter session.mutex.withLock]
           ├─ store.expireIfStale          (TTL-Check, ggf. history.clear)
           ├─ rateLimiter.checkAndAcquire  (per-min/per-hour cap)
           ├─ GrokProvider.complete        (xAI Responses API, store=false)
           ├─ LlmResponseFormatter.format  (Markdown strippen, TTS-safe)
           └─ store.append(user) + store.append(assistant)
              │
              ▼
        ReplyResult.FollowUp(body)
              │
              ▼
        LlmChannel.maybeInjectFollowUp
              │
              ▼
        SmsContentProviderWriter.injectIncoming(addr, body, displayName="Grok")
              │
              ▼
    Tesla TTS spricht Antwort
```

## Design-Entscheidungen

| Aspekt | Entscheidung |
|---|---|
| API | xAI Responses API (`POST /v1/responses`) |
| Modell | `grok-4.3` (User-konfigurierbar in Settings) |
| Stateful? | `store: false` — keine 30-Tage Server-History, wir senden History pro Turn neu. Privacy > Cost. |
| History | In-Memory only (`LlmConversationStore`) — App-Restart = clean state. Gilt nur für den LLM-Gesprächskontext. |
| Reply-Log | Tesla-Diktate (inkl. an Grok gesendete Fragen) landen in der lokalen Room-Tabelle `reply_history` (Diagnose/Retry) und sind im Diagnose-Export enthalten. Sie verlassen das Gerät nur Richtung gewähltem LLM-Provider. |
| TTL | 120 s Inactivity (User-konfigurierbar). Nach Ablauf wird die History bei nächstem Turn fresh. |
| Mutex | Per-Mapping in `LlmTurnRunner` — kompletter Turn (Provider-Call + History-Append) atomar |
| Rate-Limit | 6/min, 30/h pro Mapping (`LlmRateLimiter`) |
| API-Key | Android Keystore AES-256-GCM (`KeystoreApiKeyStore`). Backup ausgeschlossen via `data_extraction_rules.xml`. |
| Tools (server-seitig) | **Shipped:** `web_search` + `x_search` (xAI Agent-Tools) hinter zwei opt-in Toggles. Der xAI-Server fährt die Such-Schleife selbst und liefert eine fertige Antwort in **einem** Response — **kein** Tool-Execution-Loop nötig, `store=false` bleibt. Aktiviert per Flag in `LlmRequest.webSearch`/`xSearch` → `GrokProvider.buildRequest` hängt `{"type":"web_search"}`/`{"type":"x_search"}` ans `tools`-Array und setzt `include:["no_inline_citations"]`. |
| Tools (client-seitig) | Function-Calling-Architektur vorhanden (`AssistantTool`, `ToolRegistry`), V2 leer. V3 kann Notes/Calendar/… einklinken (braucht den noch fehlenden Tool-Execution-Loop). |
| Trigger | `AssistantTriggerCoordinator` als Single-Entry. V2: MANUAL_BUTTON. V3: BLE/QuickSettings/Intent. |
| TTS-Safe | `LlmResponseFormatter` strippt Markdown, Code-Blöcke, Listen → flowing Text, max 800 Zeichen. |
| Echo-Protection | `InjectedMessageLedger`: Outbox-Echos eigener Inserts (sollten praktisch nicht vorkommen) werden 10 s geblockt; Normalisierung strippt Display-Prefix vor Vergleich. |
| Sender-Display | `Telephony.Sms.ADDRESS` trägt die reine Fake-Number `"+88810000007"`; der Name kommt über den Contact-Sync-Pfad (PBAP-Cache), sodass Tesla MCU2 z.B. "Grok" sauber zeigt. Falls Tesla die Reply-Address in Bracket-Form (`"Grok <+88810000007>"`) zurückschickt, strippt `FakeAddress.parse` `[^+0-9]` und resolved daraus wieder die `(channel, mappingId)` für's Reply-Routing. |

## Trigger-Sources (V3-Vorbereitung)

```kotlin
enum class AssistantTriggerSource {
    MANUAL_BUTTON,        // V2 aktiv — HomeScreen-Card oder Assistant-Settings-Button
    BLE_DEVICE,           // V3 — z.B. Lenkrad-HID-Button
    QUICK_SETTINGS_TILE,  // V3 — Schnelle Aktion aus dem Statusbar
    INTENT_API,           // V3 — Externe App startet AI-Chat via Intent
    AUTOMATIC             // V3 — Tesla-Ignition-Sensor, Geofence, etc.
}
```

Implementierung neuer Sources fügt nur eine neue Aufruf-Stelle hinzu, kein
Refactor des Channel-Codes.

## Privacy / Security

- **API-Key** liegt verschlüsselt im AndroidKeyStore (`KeystoreApiKeyStore`),
  Ciphertext in einem eigenen DataStore. `allowBackup=false` + Backup-Exclude
  garantieren, dass der Key nicht über Google-Auto-Backup leakt.
- **Tesla-Diktate** gehen an xAI. Wir setzen `store: false` damit xAI die
  Konversation nicht 30 Tage cached — wir können aber kurzfristige Logs nicht
  ausschließen. Der User wird via Consent-Dialog beim Erst-Trigger gewarnt.
- **HTTPS-only** über `network_security_config.xml`. Cert-Pinning wurde
  bewusst weggelassen (Cert-Rotation würde die App brechen).
- **Logs** enthalten weder API-Key noch Body — der `RedactingAuthInterceptor`
  loggt nur HTTP-Methode + Path + Status.

## V3 Roadmap

- **previous_response_id-Mode**: Settings-Toggle "Privacy aufweichen für 90%
  Cache-Discount" → `store: true` + `previous_response_id`-Tracking pro Session.
- **Tool-Use**: Notes-Tool (Speichern in lokale Notes), Calendar-Tool
  (Eintrag im Google-Kalender), Tesla-Klima-Tool (via Tesla-Fleet-API).
- **BLE-Trigger-Service**: Foreground-Service, der auf ein HID-Pair aus dem
  Auto reagiert und `coordinator.trigger(BLE_DEVICE)` aufruft.
- **Multi-Persona**: Multiple LLM-Conversations mit unterschiedlichem
  System-Prompt (z.B. "Sachlich" / "Lustig" / "Co-Pilot-Modus") parallel.
- **Streaming-SSE**: SSE-Streaming statt Batch — Grok-Antwort wird in Chunks
  injiziert, sodass Tesla TTS schneller startet.
