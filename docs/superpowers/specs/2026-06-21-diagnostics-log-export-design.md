# Design: Persistenter Diagnose-Log + redigierter, teilbarer Export

- **Status:** Entwurf zur Review
- **Datum:** 2026-06-21
- **Kontext:** Auslöser ist ein Nutzer-Report „Reply nicht zugestellt" bei WhatsApp-Antworten über den Tesla (Beeper/Signal funktionieren). Die Ursache ließ sich aus der Ferne nicht eindeutig bestimmen, weil die diagnostisch entscheidenden Zeilen nur in `android.util.Log` (logcat) landen und der vorhandene JSON-Export unerreichbar im App-Cache liegt. Dieses Feature schafft die Voraussetzung, solche Fälle künftig **allein aus einer vom Tester geschickten Datei** zu fixen.

## Ziel

Ein Tester schickt **eine einzige Datei**, und der Entwickler kann daraus Probleme diagnostizieren/fixen — ohne Rückfragen, ohne adb, ohne Developer-Mode.

Zwei Leitplanken des Maintainers:
1. **Für den Tester maximal einfach** (ein Tap, eine Datei, fertig).
2. **Lieber zu viel als zu wenig loggen.**

## Nicht-Ziele

- Kein „Verbose"-/Erweitert-Schalter — es gibt **einen** Log, immer aktiv.
- Kein Voll-/Unredigiert-Modus — der geteilte Export ist **immer** redigiert (PII raus).
- Keine Room-Tabelle für Logs (vermeidet Version-Bump + Migration + Schema-Regen; siehe CLAUDE.md).
- Keine automatische Übertragung — nur manuelles Android-Share-Sheet.
- Kein Fix des WhatsApp-Bugs selbst (separater Schritt, sobald ein Tester-Log vorliegt).
- **Die optionale ReplyHistory-Anreicherung (`ReplyHistory.errorDetail` mit Sub-Grund füllen) ist verworfen** — der persistente Log trägt den Sub-Grund ohnehin; die strukturierte Doppelung lohnt den Eingriff in den geteilten `ReplyResult`-Typ (`data object` → `data class`) plus Test-Anpassungen nicht. (Im Chat war das „Komponente E"; die Buchstaben A–F unten bezeichnen die *umgesetzten* Komponenten.)

## Getroffene Entscheidungen (Zusammenfassung)

| Frage | Entscheidung |
|---|---|
| Umfang | Ein einzelner, persistenter Log + guter, teilbarer Export |
| Schalter | Keiner — immer an |
| Persistenz | Datei-basiert (rollierend, `filesDir`), kein Room |
| Datenschutz | Standardmäßig redigiert (PII maskiert/gehasht, Diagnose-Signale vollständig) |
| Tester-UX | „Diagnose senden"-Button **ohne** Developer-Mode in den normalen Einstellungen |
| Reichweite Logging | Ganze Pipeline (Inbound, Outbound, Reply-Branches, Rebuilder, LLM, Lifecycle) |

## Architektur-Überblick

```
Hot-Path-Logging                       Export/Share
─────────────────                      ─────────────
LogBuffer.info/warn/error  ──┐
  ├─ In-Memory Ring (500)    │         DiagnosticsExporter.export(redacted)
  └─ Channel(trySend) ──► LogFileStore   ├─ Build/Device/Settings
        (IO-Consumer)        rollierende  ├─ Mappings (redigiert)
                             Datei(en)     ├─ ReplyHistory (Text → Länge)
LogBuffer.init() ◄── readTail() ──────────┴─ Logs (aus LogFileStore-Tail)
                                                     │
                                          schreibt 1 JSON in cacheDir
                                                     │
                                          FileProvider-Uri ──► ACTION_SEND (Chooser)
```

## Komponenten

### A) `LogFileStore` (neu) — `core/util/LogFileStore.kt`
Kapselt die rollierende On-Disk-Logdatei.

- **Verzeichnis:** `filesDir/diagnostics/`. Konstruktor nimmt das Verzeichnis als `File` (in Tests Temp-Dir injizierbar) + `@IoDispatcher`.
- **Dateien:** `tmm-log.current` + `tmm-log.prev`. Rotation, wenn `current` einen Schwellwert überschreitet (z.B. 2 MB); dann `prev` löschen, `current` → `prev`, neue `current`. Gesamt-Cap ≈ 4 MB.
- **API:**
  - `append(entry: LogBuffer.LogEntry)` — non-blocking aus Sicht des Callers; serialisiert über einen internen Single-Consumer-Coroutine.
  - `readTail(max: Int): List<LogBuffer.LogEntry>` — liest `prev` + `current`, parst, gibt die letzten `max` (neueste zuerst) zurück.
  - `clear()` — beide Dateien löschen.
- **Zeilenformat:** `epochMillis \t levelName \t tag \t message`. `message` (und `tag`) werden beim Schreiben sanitisiert (`\n`, `\r`, `\t` → Leerzeichen/Escape), damit eine Log-Zeile genau eine Dateizeile ist und `readTail` robust parst. Unparsebare Zeilen werden übersprungen, nicht geworfen.
- **Robustheit:** alle Datei-Operationen in `runCatching`; I/O-Fehler dürfen nie den Hot-Path oder den Export crashen.

### B) `LogBuffer` erweitern — `core/util/LogBuffer.kt`
- Bekommt `LogFileStore` injiziert (bleibt `@Singleton`; interner `CoroutineScope(SupervisorJob + io)`).
- `log()` schreibt weiterhin in den In-Memory-Ring **und** reicht den Eintrag per `Channel.trySend` an `LogFileStore` weiter (bei vollem Channel: verwerfen — Logs sind best-effort, Hot-Path bleibt O(1) und nicht-blockierend).
- **Tail-Load beim Start:** einmalig `readTail()` in den Ring laden (off-main), sodass Live-Log-Tab **und** Export Historie über Neustarts/lange Fahrten hinweg zeigen. Reihenfolge: geladener Tail + Live-Einträge, neueste zuerst, auf Ring-Kapazität begrenzt.
- `clear()` leert Ring **und** Datei.
- In-Memory-Kapazität bleibt 500 (Live-Tail-UI); die Datei hält den „vollen" Verlauf für den Export.

### C) Pipeline-Logging vervollständigen (B-Audit, „lieber zu viel")
Ziel: Der Log ist eine zusammenhängende Spur von „Notification rein" bis „Reply raus". Alle Entscheidungspunkte emittieren **eine** `LogBuffer`-Zeile, **nur Metadaten** (keine Bodies/Namen).

Neu in den exportierbaren LogBuffer zu leiten (heute nur `android.util.Log`):
- **`NotificationReplyExecutor`** (`LogBuffer` injizieren) — pro Ausgang eine Zeile:
  - `reply SUCCESS notif=<key> mapping=<id> via cache|rebuild`
  - `reply NO_ACTION notif=<key> (cache-miss + rebuild-miss)`
  - `reply NO_REMOTE_INPUT notif=<key>`
  - `reply PI_CANCELED notif=<key> reason=immutable`
  - `reply PI_CANCELED notif=<key> reason=canceled-on-send`
  - `reply PROVIDER_ERROR notif=<key> msg=<exception-message>`
- **`PendingIntentRebuilder`** (`LogBuffer` injizieren): `rebuild NLS-null` / `rebuild notif=<key> no-longer-active` / `rebuild notif=<key> found-action`.
- `notif=<key>` = `sbn.key` (`userId|package|id|tag`) — enthält Paket + Notification-ID, **keine** PII.

**PII-Audit bestehender LogBuffer-Aufrufe (Pflicht):**
- `NotificationCapture.kt:142-145` loggt aktuell `msg.senderName` (= Kontaktname) → umstellen auf Längen/IDs ohne Namen.
- Restliche `logBuffer.*`-Aufrufe sichten und sicherstellen: keine Namen, keine Bodies, keine Klartext-Nummern (Fake-`+888…` ok; echte Nummern bereits via `addrForLog` maskiert).
- Ergebnis: Der Log-Abschnitt ist **per Konstruktion** share-sicher; der Export muss freien Log-Text nicht heuristisch scrubben.

### D) Redigierter, selbst-genügsamer Export — `core/util/DiagnosticsExporter.kt`
Export ist **immer** redigiert. Inhalt der einen JSON-Datei:

- **Build/Device-Header** *(neu: Build-Infos ergänzen)*: `versionName`, `versionCode`, `applicationId` (inkl. `.debug`-Suffix), debug-vs-release — zusätzlich zu den vorhandenen OS/Device-Feldern (`sdkInt`, `manufacturer`, `model`, `release`). Quelle: `BuildConfig`.
- **Settings:** wie heute (TTL, Send-Budget, Count, Preflight).
- **Mappings (redigiert):**
  - `conversationKey` → Schema-Präfix erhalten, PII-Teil gehasht: `com.whatsapp::id::<sha1:abcdef012345>` (zeigt Key-Strategie `::id::`/`::lbl::`, ohne shortcutId/jid/Nummer offenzulegen).
  - `payloadJson` → via `PayloadJson` dekodieren, **`conversationLabel` + `senderDisplayName` maskieren** (Drittkontakt-Namen); `sourcePackage`, `notificationKey`, `remoteInputResultKey` behalten; `assistantDisplayName` (eigenes Label) behalten.
  - `fakeAddress`, Timestamps, `replyCount`, `replyable` behalten.
- **ReplyHistory:** `text` → `"<len=NN>"` (Inhalt raus, Länge bleibt); `result` + `errorDetail` behalten; übrige Felder behalten.
- **Logs:** voller Tail aus `LogFileStore` (nicht nur die 500 In-Memory) — durch C bereits PII-frei, daher unverändert übernommen.
- **Maskierungs-Helfer:** zentral (z.B. `name → "•••(len=N)"`, `key → "sha1:<12>"`), wiederverwendbar + testbar.

Dateiname unverändert: `mfs-diagnostics-<timestamp>.json` in `cacheDir`.

### E) FileProvider + Share — *neu*
- **FileProvider neu anlegen** (existiert noch nicht; `…​.mms`/`…​.androidx-startup` sind andere Provider):
  - Manifest: `<provider android:name="androidx.core.content.FileProvider" android:authorities="${applicationId}.fileprovider" android:exported="false" android:grantUriPermissions="true">` mit `<meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/file_paths"/>`.
  - `res/xml/file_paths.xml`: `<cache-path name="diagnostics" path="."/>` (deckt `cacheDir`, wohin der Export schreibt). `androidx.core` ist bereits Dependency.
- **`DiagnosticsViewModel`:** `shareDiagnostics()` → schreibt redigierte Datei (IO) → emittiert One-Shot-Event (`Channel`/`SharedFlow`) mit der `content://`-Uri.
- **UI-Schicht** startet den Chooser mit Activity-Kontext (`LocalContext`):
  - `Intent(ACTION_SEND)`, `type="application/json"`, `EXTRA_STREAM=uri`, `FLAG_GRANT_READ_URI_PERMISSION`, `Intent.createChooser(...)`.
  - Authority-/Uri-Bau in einer reinen, testbaren Hilfsfunktion.

### F) Tester-UX: „Diagnose senden" ohne Developer-Mode
- **Neuer Eintrag in den normalen Einstellungen** (`SettingsScreen`, **nicht** hinter `developerMode`): Button „Diagnose senden" → ruft denselben `shareDiagnostics()`-Pfad → Share-Sheet → eine Datei. Das ist der einfachste Weg für Tester.
- Der detaillierte **Diagnostics-Screen** (dev-gated) behält zusätzlich seinen Teilen-Button (heute „Download"-Icon → wird zu „Teilen").
- **i18n (Pflicht, EN + DE):** neue Strings für Button-Label, Share-Sheet-Titel, Erfolg/Fehler-Hinweis, kurzer „enthält keine Nachrichteninhalte"-Hinweistext. `values/` (EN) **und** `values-de/` (DE).

## Datenfluss (Reply-Fehler, End-to-End)

1. `OutboundSmsObserver` loggt Row (type, addr maskiert, body-Länge) → `Dispatch … result=<X>`.
2. `OutboundSmsClassifier` → `TeslaReply mapping/channel` (geloggt).
3. `ReplyDispatcher` → Mapping/TTL/Expired (geloggt).
4. `NotificationReplyExecutor` → **Branch + Sub-Grund** (neu, C).
5. `PendingIntentRebuilder` → NLS-/Notification-Status (neu, C).
6. `FallbackNotifier` postet die sichtbare „Reply nicht zugestellt"-Notification.
→ Alle Zeilen liegen persistent in `LogFileStore` und damit im Export. Der Entwickler liest aus **einer** Datei: Build, Mapping-Zustand (`replyable`), exakte Fehler-Branch + Sub-Grund, Timing.

## Fehlerbehandlung

- Datei-I/O immer `runCatching`; Fehler degradieren still (kein Crash, keine verlorenen Replies).
- Voller Log-Channel → Eintrag verwerfen (best-effort), nie blockieren.
- Share ohne Ziel-App / Export-Fehler → lokalisierter Fehlerhinweis im UI-State.
- `CancellationException` in Coroutinen stets rethrowen (CLAUDE.md / `coRunCatching`).

## Teststrategie

- **`LogFileStore`** (JVM, Temp-Dir): Append, Rotation bei Schwelle, `prev`/`current`-Übergang, `readTail`-Reihenfolge + `max`, Sanitizing von `\n`/`\t`, Überspringen unparsebarer Zeilen, `clear`.
- **`LogBuffer`**: Tail wird beim Start in den Ring geladen; `log()` ruft Store-Append; non-blocking (kein Suspend im Hot-Path).
- **`DiagnosticsExporter`** (Redaktion): Ergebnis enthält **keine** Kontaktnamen und **keinen** Antworttext; `conversationKey` gehasht, Präfix erhalten; Notification- **und** LLM-Payload; Build-Header gesetzt.
- **Maskierungs-Helfer**: Unit-Tests für Name-/Key-Maskierung.
- **`NotificationReplyExecutor` / `PendingIntentRebuilder`**: je Branch wird die erwartete LogBuffer-Zeile emittiert (mockk) — bestehende Tests bleiben grün.
- **Share-Helfer**: Authority/Uri-Bau.
- i18n-Parität EN/DE manuell prüfen (Lint-Crash bekannt, siehe Memory).

## Betroffene Dateien (Überblick)

| Datei | Änderung |
|---|---|
| `core/util/LogFileStore.kt` | **neu** |
| `core/util/LogBuffer.kt` | Persistenz-Sink + Tail-Load |
| `core/util/DiagnosticsExporter.kt` | Build-Header + Redaktion + Logs aus Datei |
| `channel/notification/NotificationReplyExecutor.kt` | LogBuffer-Zeilen je Branch |
| `channel/notification/PendingIntentRebuilder.kt` | LogBuffer-Zeilen NLS/aktiv |
| `channel/notification/NotificationCapture.kt` | PII-Audit (senderName raus) |
| `ui/screen/diagnostics/DiagnosticsViewModel.kt` | `shareDiagnostics()` + Event |
| `ui/screen/diagnostics/DiagnosticsScreen.kt` | Export-Button → Teilen |
| `ui/screen/settings/SettingsScreen.kt` (+ ViewModel) | „Diagnose senden"-Eintrag (ohne Dev-Mode) |
| `AndroidManifest.xml` | FileProvider |
| `res/xml/file_paths.xml` | **neu** |
| `res/values/strings*.xml`, `res/values-de/strings*.xml` | neue Strings (EN+DE) |
| Tests unter `app/src/test/.../core/util`, `.../channel/notification` | neu/erweitert |

## Risiken & offene Punkte

- **Log-Hot-Path-Kosten:** `log()` läuft pro Notification/SMS. Mitigation: nur `Channel.trySend` im Hot-Path, eigentliches I/O im IO-Consumer; bei Überlast verwerfen.
- **Redaktions-Vollständigkeit:** Der `conversationKey` kann je nach App PII enthalten → wird gehasht. Falls künftig neue Payload-Felder mit Namen dazukommen, muss die Redaktion mitwachsen (zentraler Helfer reduziert das Risiko).
- **„Alles fixbar" vs. Redaktion:** Bugs, die vom **exakten Nachrichteninhalt** abhängen, sind im redigierten Log nicht sichtbar (bewusst akzeptiert; ein optionaler Voll-Modus wäre später ein kleiner Nachtrag).
- **Cache-Aufräumung:** Export liegt in `cacheDir` (vom System räumbar) — für sofortiges Teilen unkritisch.

## Rollout

CHANGELOG-Eintrag; Versions-Bump nach Maintainer-Konvention. Keine Datenmigration nötig (kein Room-Schema-Change).
