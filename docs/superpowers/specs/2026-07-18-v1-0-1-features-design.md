# v1.0.1 — Design: BT-Picker-Scroll, SMS-Delete, Unread-Badge

Datum: 2026-07-18 · Status: vom User genehmigt · Ziel-Release: 1.0.1 (nightly)

Drei unabhängige Features. Keine Room-/Schema-Änderung, keine neue Permission, keine neue Activity.

## 1. Scrollbarer Bluetooth-Geräte-Picker

**Problem:** `TeslaDevicePickerDialog` (`ui/component/TeslaConnection.kt`) rendert die
gekoppelten Geräte in einer einfachen `Column`. Bei vielen Geräten überläuft der
`AlertDialog`; untere Einträge sind nicht erreichbar.

**Lösung:** `Modifier.verticalScroll(rememberScrollState())` auf der Geräte-`Column`
(gleiches Muster wie der Select-Text-Dialog-Fix in 1.0.0-rc1, Commit 122cba9). Der
`AlertDialog` begrenzt die Höhe; die Liste scrollt darin. Wirkt in Settings und
Onboarding (geteilte Komponente). Keine ViewModel-Änderung.

## 2. SMS löschen

**Umfang (User-Entscheidung):** beides — ganze Konversationen und einzelne Nachrichten,
jeweils mit Bestätigungsdialog.

### Domain-Seam

`domain/sms/SmsInboxReader` (hostet bereits die Schreiboperation `markThreadRead`)
bekommt:

```kotlin
/** Löscht eine einzelne (echte) SMS. Nur als Standard-SMS-App; false bei Fehlschlag/no-op. */
suspend fun deleteMessage(messageId: Long): Boolean

/** Löscht alle (echten) Nachrichten eines Threads. Nur als Standard-SMS-App. */
suspend fun deleteThread(threadId: Long): Boolean
```

Implementierung in `sms/read/SmsInboxReaderImpl`, Idiom wie `markThreadRead`:
`roleManager.isDefault()`-Gate (sonst no-op → `false`), `runCatching` + `Log.w`,
Rückgabe `true` nur bei ≥1 gelöschter Row.

**Fake-Schutz (zweifach):**
1. Die UI sieht ausschließlich echte Threads/Nachrichten (Reader filtert
   `FakeAddress.isFakeAddress` bereits beim Laden).
2. Defensiv prüft der Delete vor dem Löschen die Adresse(n) der Ziel-Rows und
   überspringt Fake-Rows — eine Grok-/Bridge-Zeile ist damit auch bei einem
   fehlerhaften Aufrufer unlöschbar.

### UI Konversationsliste (`SmsConversationsScreen`)

- `MfsListItem` bekommt optionalen `onLongClick`-Parameter (`combinedClickable`).
- Long-Press auf Konversation → `AlertDialog` „Konversation löschen?" (zeigt
  Kontaktname/Adresse) → `SmsConversationsViewModel.deleteThread(threadId)`.
- Fehlschlag → Snackbar. Erfolg → `ContentObserver`-Flow lädt die Liste automatisch neu.

### UI Thread-Ansicht (`SmsThreadScreen`)

- `SwipeToDismissBox` (Material3) um jede `MessageBubble`; Swipe (beide Richtungen)
  zeigt roten Löschen-Hintergrund mit Delete-Icon.
- Swipe bestätigt NICHT direkt: erst `AlertDialog` „Nachricht löschen?" →
  `SmsThreadViewModel.deleteMessage(id)`; bei Abbruch springt die Bubble zurück
  (Dismiss-State reset).
- Long-Press-Textauswahl (RC2-Feature) bleibt unangetastet; Tap bleibt no-op.
- Fehlschlag → Snackbar (bestehender `feedback`-Mechanismus).

## 3. Unread-Badge in der Bottom-Bar

**Ziel:** rotes Zahlen-Badge am SMS-Item der `NavigationBar` (Start / SMS / Grok /
Einstellungen), Anzahl ungelesener echter SMS.

- `SmsInboxReader.unreadCount(): Int` — dedizierte billige Query
  (`TYPE = INBOX AND READ = 0`, Projektion nur `ADDRESS`), Fakes Kotlin-seitig via
  `FakeAddress.isFakeAddress` gefiltert. Ohne READ_SMS-Permission/Fehler → 0.
  Gleiche Ausschluss-Semantik wie die Konversationsliste; zählt aber ALLE Rows
  (kein Scan-/Thread-Limit wie in der Liste).
- Neues `UnreadBadgeViewModel` (`@HiltViewModel`): alle Trigger (Provider-Änderungen,
  initialer Load, manueller Refresh) laufen durch EINEN gemergten Flow mit einem
  einzigen Collector (250 ms Debounce, `onStart` vor `debounce`) und exponieren
  `StateFlow<Int>` — serialisiert, keine Refresh-Races.
- `MfsBottomBar`: bekommt das VM vom `MfsNavHost` gereicht (dort per `hiltViewModel()`
  im Activity-Scope aufgelöst — in den Destinations wäre der Owner der jeweilige
  BackStack-Entry und jeder Tab bekäme einen eigenen SMS-Observer); das SMS-Item bekommt
  `BadgedBox` mit `Badge` (Material3-Default = Error-Rot). Anzeige nur bei Count > 0;
  ab 100 → „99+". ContentDescription lokalisiert (Plural).

## Tests

- `SmsInboxReaderImplTest`-Umfeld: Pure-Logik-Tests (z. B. „99+"-Formatierung,
  Fake-Filter für unreadCount) nach bestehendem Muster.
- VM-Tests (MockK + `runTest`): Delete-Erfolg/-Fehlschlag setzt Feedback korrekt;
  Badge-VM emittiert Count und reagiert auf `changes()`.
- Keine Compose-UI-Tests nötig (kein neues komplexes Interaktionsverhalten jenseits
  Standard-Material-Komponenten); falls doch, unter `app/src/testDebug/java`.

## i18n

Neue Strings in `res/values/strings.xml` (EN, Default) + `res/values-de/strings.xml`:
Dialogtitel/-texte für beide Delete-Bestätigungen, Löschen/Abbrechen-Labels,
Delete-Fehlschlag-Feedback, Badge-ContentDescription (Plural EN+DE).

## Version

`app/build.gradle.kts`: `versionName = "1.0.1"`, `versionCode` +1.

## Nachtrag (2026-07-18): Codex-Review-Härtung

Der Implementierungsplan wurde vor Ausführung extern verifiziert (Codex `gpt-5.6-sol`,
xhigh). Übernommene Korrekturen: (1) Delete löscht atomar über die validierte
`_ID`-Liste des Guard-Snapshots statt über die breite Selection; (2) zusätzliche
Robolectric-Wiring-Tests des destruktiven Pfads (Default-App-Gate + Fake-Guard vor
jedem `delete`); (3) Badge-Trigger serialisiert (ein gemergter Flow, `onStart` vor
`debounce`) und VM Activity-scoped im `MfsNavHost`; (4) deprecated
`confirmValueChange` bewusst mit begründetem `@Suppress` beibehalten;
(5) `MfsListItem` behandelt Long-Click-only; (6) Badge-Semantik-Formulierung
korrigiert (zählt alle Rows, Liste hat Scan-Limits).

## Nicht-Ziele

- Kein Löschen von Fake-/Grok-Threads (bewusst unmöglich).
- Kein Multi-Select/Batch-Delete.
- Kein MMS-Delete (App verwaltet nur SMS-Rows).
- Kein Undo (Bestätigungsdialog ist der Schutz; Provider-Delete ist endgültig).
