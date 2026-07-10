# SMS-Thread: Nachrichtentext kopierbar machen — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In der SMS-Thread-Ansicht (`SmsThreadScreen`) kopiert ein Long-Press auf eine Nachrichten-Bubble den kompletten Nachrichtentext ins Clipboard.

**Architecture:** Der Nachrichtentext wird in `MessageBubble` mit einem einfachen Compose-`Text` gerendert — Compose-`Text` ist (anders als ein `TextView` mit `textIsSelectable`) **standardmäßig nicht selektierbar**, und es gibt weder einen `SelectionContainer` noch eine Long-Press-Aktion. Fix: `combinedClickable` mit `onLongClick` auf der inneren `Column` der Bubble, Kopie via `LocalClipboardManager` (gleiches Muster wie `SettingsScreen`/`DiagnosticsScreen`), Haptik-Feedback via `LocalHapticFeedback`. Kein eigener Snackbar/Toast: minSdk 33 → Android 13+ zeigt systemseitig ein Kopier-Overlay.

**Verworfene Alternative:** `SelectionContainer` um den Body-Text (Teiltext-Selektion). Verworfen, weil (a) Messenger-Konvention Long-Press → ganze Nachricht kopieren ist (Google Messages, WhatsApp), (b) Selektions-Gesten in einer `LazyColumn` fummelig sind, (c) das Verhalten schlecht automatisiert testbar ist, (d) das Android-13-Clipboard-Overlay ohnehin Nachbearbeitung des kopierten Texts erlaubt. Beide Gesten gleichzeitig (SelectionContainer + Bubble-Long-Press) konkurrieren um dieselbe Geste und wären inkonsistent.

**Tech Stack:** Jetpack Compose (BOM 2025.06.01 / UI 1.8.3, Material3 1.4.0), Robolectric 4.14.1 + `androidx.compose.ui:ui-test-junit4` (neu, für JVM-Compose-UI-Tests), JUnit4 + Google Truth.

## Global Constraints

- Windows: immer `gradlew.bat` verwenden, nie system-gradle.
- Versionen/Libraries nur in `gradle/libs.versions.toml` ergänzen, nie inline in `build.gradle.kts`.
- i18n-Parität: jeder neue String muss **identisch benannt** in `app/src/main/res/values/strings_sms.xml` (EN, Default) **und** `app/src/main/res/values-de/strings_sms.xml` (DE) existieren. Parität manuell prüfen — `gradlew.bat :app:lint` crasht wegen eines bekannten Tooling-Bugs (Compose-Lint + Kotlin 2.0.21) und ist KEIN verlässlicher Check.
- Niemals Message-Bodies/Diktate in `LogBuffer` loggen (wird via `DiagnosticsExporter` exportiert) — der Fix darf den kopierten Text nirgends loggen.
- Test-Konventionen: Tests spiegeln den main-Package-Tree unter `app/src/test/java`; Testnamen sind Backtick-Sätze; Robolectric mit `@Config(sdk = [33])`.
- Commits: Conventional-Commit-Stil des Repos (`feat(ui): …`, `test(ui): …`). **KEIN `Co-Authored-By: Claude`** anhängen (Projektvorgabe).
- Branch: auf `nightly` arbeiten (aktueller Arbeits-Branch), nicht auf `main`.

## Root Cause (Beleg)

`app/src/main/java/io/github/lycheeappf/tmm/ui/screen/sms/SmsThreadScreen.kt:181-185`:

```kotlin
Text(
    message.body,
    style = MaterialTheme.typography.bodyMedium,
    color = onContainer
)
```

- Compose-`Text` bietet ohne `SelectionContainer` keinerlei Selektions-/Kopiermöglichkeit.
- Grep über `app/src/main/java`: **kein** `SelectionContainer`, **kein** `combinedClickable` im gesamten Projekt; Clipboard-Code existiert nur in `FallbackNotifier`, `SettingsScreen`, `DiagnosticsScreen`.
- Der Eingabe-`OutlinedTextField` (ReplyBar) ist davon nicht betroffen — Textfelder sind nativ selektierbar. Die Konversationsliste (`SmsConversationsScreen`, Snippets) bleibt bewusst außen vor: Kopieren aus der Listenansicht ist auch in anderen SMS-Apps unüblich.

---

### Task 1: Compose-UI-Test-Harness (Robolectric) einrichten

Das Projekt hat bisher keine Compose-UI-Test-Abhängigkeit (nur JUnit4/Robolectric/MockK). Robolectric 4.14.1 unterstützt Compose-UI-Tests als JVM-Unit-Tests; dafür `ui-test-junit4` + `ui-test-manifest` als `testImplementation` ergänzen (das Manifest-Artefakt registriert die von `createComposeRule()` gestartete `ComponentActivity`; es wird dank `isIncludeAndroidResources = true` in das Unit-Test-Manifest gemerged).

**Files:**
- Modify: `gradle/libs.versions.toml` (Sektion `[libraries]`)
- Modify: `app/build.gradle.kts` (Block `dependencies`, bei den bestehenden `testImplementation`-Zeilen ab Zeile 184)
- Create: `app/src/test/java/io/github/lycheeappf/tmm/ui/ComposeTestHarnessSmokeTest.kt`

**Interfaces:**
- Consumes: bestehende Compose-BOM (`androidx-compose-bom = "2025.06.01"`), Robolectric 4.14.1.
- Produces: lauffähige `createComposeRule()`-Tests unter `RobolectricTestRunner` — Task 2 hängt davon ab.

- [ ] **Step 1: Library-Einträge in `gradle/libs.versions.toml` ergänzen**

In der `[libraries]`-Sektion, direkt unter `androidx-compose-material-icons-extended` (Zeile 48), einfügen (Versionen kommen aus der BOM, daher ohne `version.ref`):

```toml
androidx-compose-ui-test-junit4 = { module = "androidx.compose.ui:ui-test-junit4" }
androidx-compose-ui-test-manifest = { module = "androidx.compose.ui:ui-test-manifest" }
```

- [ ] **Step 2: Test-Dependencies in `app/build.gradle.kts` ergänzen**

Im `dependencies`-Block, direkt nach `testImplementation(libs.okhttp.mockwebserver)` (Zeile 190), einfügen. Wichtig: die BOM-Platform muss für die `testImplementation`-Konfiguration separat deklariert werden — die `implementation(platform(...))`-Zeile gilt dort nicht:

```kotlin
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
```

- [ ] **Step 3: Smoke-Test schreiben (beweist, dass der Harness funktioniert)**

Create `app/src/test/java/io/github/lycheeappf/tmm/ui/ComposeTestHarnessSmokeTest.kt`:

```kotlin
package io.github.lycheeappf.tmm.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Kanarienvogel für den Compose-UI-Test-Harness unter Robolectric:
 * schlägt dieser Test fehl, ist der Harness kaputt — nicht der App-Code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposeTestHarnessSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `compose rule renders text under robolectric`() {
        compose.setContent { Text("smoke") }
        compose.onNodeWithText("smoke").assertExists()
    }
}
```

- [ ] **Step 4: Smoke-Test ausführen**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.ui.ComposeTestHarnessSmokeTest"`
Expected: `BUILD SUCCESSFUL`, 1 Test PASS.

Bekannte Stolperfalle, falls der Build scheitert: „cannot delete …app/build/generated/ksp“ = hängender Kotlin-Daemon (Windows-Lock). Fix: `gradlew.bat --stop`, dann `app/build` löschen, dann erneut bauen.

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts "app/src/test/java/io/github/lycheeappf/tmm/ui/ComposeTestHarnessSmokeTest.kt"
git commit -m "test(ui): add Compose UI test harness for Robolectric unit tests"
```

---

### Task 2: Long-Press-Copy in `MessageBubble` (TDD)

**Files:**
- Test: `app/src/test/java/io/github/lycheeappf/tmm/ui/screen/sms/SmsThreadScreenTest.kt` (neu)
- Modify: `app/src/main/java/io/github/lycheeappf/tmm/ui/screen/sms/SmsThreadScreen.kt:155-203` (`MessageBubble`)
- Modify: `app/src/main/res/values/strings_sms.xml` (EN) + `app/src/main/res/values-de/strings_sms.xml` (DE)

**Interfaces:**
- Consumes: `SmsMessage(id: Long, threadId: Long, address: String, body: String, date: Long, direction: SmsDirection, read: Boolean)` und `SmsDirection.{INBOX,SENT,OUTBOX,FAILED,OTHER}` aus `domain/sms/SmsConversation.kt`; `MfsTheme(darkTheme, dynamicColor, content)` aus `ui/theme/Theme.kt`; Test-Harness aus Task 1.
- Produces: `internal fun MessageBubble(message: SmsMessage)` (Sichtbarkeit `private` → `internal`, damit der Test im selben Modul zugreifen kann); String-Key `sms_thread_copy_action` (EN „Copy message“ / DE „Nachricht kopieren“).

- [ ] **Step 1: Sichtbarkeit von `MessageBubble` auf `internal` ändern**

In `SmsThreadScreen.kt` Zeile 155-156:

```kotlin
@Composable
internal fun MessageBubble(message: SmsMessage) {
```

(vorher `private fun MessageBubble`). Nur die Sichtbarkeit ändern, sonst nichts — das ist die minimale Voraussetzung, damit der Test kompiliert und in der Red-Phase aus dem **richtigen** Grund fehlschlägt (Assertion, nicht Compile-Error).

- [ ] **Step 2: Fehlschlagenden Test schreiben**

Create `app/src/test/java/io/github/lycheeappf/tmm/ui/screen/sms/SmsThreadScreenTest.kt`:

```kotlin
package io.github.lycheeappf.tmm.ui.screen.sms

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.domain.sms.SmsDirection
import io.github.lycheeappf.tmm.domain.sms.SmsMessage
import io.github.lycheeappf.tmm.ui.theme.MfsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmsThreadScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val message = SmsMessage(
        id = 1L,
        threadId = 7L,
        address = "+491701234567",
        body = "Treffen um 18:30 am Haupteingang",
        date = 1_720_000_000_000L,
        direction = SmsDirection.INBOX,
        read = true
    )

    @Test
    fun `long press on message bubble copies body to clipboard`() {
        // Clipboard über den Composition-Context lesen, nicht über den
        // Application-Context: Robolectric hält Shadow-State pro
        // ClipboardManager-Instanz, und LocalClipboardManager schreibt in die
        // Instanz des Host-Activity-Contexts.
        lateinit var composeContext: Context
        compose.setContent {
            composeContext = LocalContext.current
            MfsTheme { MessageBubble(message) }
        }

        compose.onNodeWithText(message.body).performTouchInput { longClick() }

        val clipboard = composeContext.getSystemService(ClipboardManager::class.java)
        val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        assertThat(copied).isEqualTo(message.body)
    }
}
```

- [ ] **Step 3: Test ausführen — muss fehlschlagen (Red)**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.ui.screen.sms.SmsThreadScreenTest"`
Expected: FAIL mit Truth-Assertion `expected: Treffen um 18:30 am Haupteingang … but was: null` (Long-Press bewirkt noch nichts, Clipboard bleibt leer). Schlägt er stattdessen mit Compile-/Harness-Fehler fehl, erst Task 1 bzw. Step 1 prüfen — NICHT mit der Implementierung beginnen.

- [ ] **Step 4: Strings anlegen (EN + DE, identischer Key)**

`app/src/main/res/values/strings_sms.xml`, direkt nach `sms_thread_send_action` (Zeile 17):

```xml
    <string name="sms_thread_copy_action">Copy message</string>
```

`app/src/main/res/values-de/strings_sms.xml`, an derselben Position (nach Zeile 17):

```xml
    <string name="sms_thread_copy_action">Nachricht kopieren</string>
```

- [ ] **Step 5: Implementierung in `MessageBubble`**

In `SmsThreadScreen.kt` folgende Imports ergänzen (alphabetisch in den bestehenden Block einsortieren):

```kotlin
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
```

Dann `MessageBubble` so ändern (kompletter neuer Funktionskörper; nur die markierten Stellen weichen vom Bestand ab):

```kotlin
@Composable
internal fun MessageBubble(message: SmsMessage) {
    val incoming = message.isIncoming
    val failed = message.direction == SmsDirection.FAILED
    val outboxPending = message.direction == SmsDirection.OUTBOX

    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current

    val container = when {
        failed -> MaterialTheme.colorScheme.errorContainer
        incoming -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val onContainer = when {
        failed -> MaterialTheme.colorScheme.onErrorContainer
        incoming -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = container,
            shape = MaterialTheme.shapes.large,
            // Surface umschließt seinen Inhalt; Start/End-Ausrichtung erzeugt das
            // typische Chat-Layout (eingehend links, ausgehend rechts).
            modifier = Modifier.align(if (incoming) Alignment.CenterStart else Alignment.CenterEnd)
        ) {
            Column(
                modifier = Modifier
                    // Auf der inneren Column (nicht auf der Surface), damit der
                    // Ripple über dem Bubble-Hintergrund liegt und von der
                    // Surface-Shape geclippt wird.
                    .combinedClickable(
                        onClick = {},
                        onLongClickLabel = stringResource(R.string.sms_thread_copy_action),
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            // Kein eigener Snackbar/Toast: ab Android 13 (minSdk 33)
                            // zeigt das System selbst ein Kopier-Overlay.
                            clipboard.setText(AnnotatedString(message.body))
                        }
                    )
                    .padding(horizontal = MfsSpacing.md, vertical = MfsSpacing.sm)
            ) {
                Text(
                    message.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = onContainer
                )
                val failedLabel = stringResource(R.string.sms_thread_meta_failed)
                val sendingLabel = stringResource(R.string.sms_thread_meta_sending)
                val meta = buildString {
                    append(SmsFormat.clockTime(message.date))
                    when {
                        failed -> append(" · $failedLabel")
                        outboxPending -> append(" · $sendingLabel")
                    }
                }
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = onContainer
                )
            }
        }
    }
}
```

Hinweise für den Implementierer:
- `combinedClickable` **vor** `.padding(...)`, damit die gesamte Bubble-Fläche (inkl. Innenabstand) auf den Long-Press reagiert.
- `onClick = {}` ist Pflichtparameter von `combinedClickable`; ein Tap zeigt dann nur den Ripple ohne Aktion — akzeptiert und konsistent mit gängigen Messengern.
- `onLongClickLabel` macht die Aktion für TalkBack als „Nachricht kopieren“ auffindbar (Accessibility).
- Falls der Compiler `ExperimentalFoundationApi` für `combinedClickable` verlangt (versionabhängig): `@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)` an `MessageBubble` annotieren; meldet er umgekehrt einen überflüssigen Opt-In, das `@OptIn` weglassen.
- `LocalClipboardManager` ist in UI 1.8 als deprecated markiert (Nachfolger `LocalClipboard`, suspend-basiert) — hier bewusst trotzdem verwenden, weil `SettingsScreen`/`DiagnosticsScreen` dasselbe Muster nutzen; eine Migration aller drei Stellen wäre ein separates Refactoring (YAGNI hier).
- Den kopierten Text NICHT loggen (LogBuffer wird via Diagnostics exportiert).

- [ ] **Step 6: Test ausführen — muss bestehen (Green)**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.ui.screen.sms.SmsThreadScreenTest"`
Expected: PASS.

- [ ] **Step 7: i18n-Parität manuell prüfen**

Beide Dateien müssen den Key exakt gleich benannt enthalten (Lint ist wegen des Tooling-Crashes kein Ersatz):

Run: `git grep -n "sms_thread_copy_action" -- app/src/main/res`
Expected: genau 2 Treffer — `values/strings_sms.xml` und `values-de/strings_sms.xml`.

- [ ] **Step 8: Commit**

```bash
git add "app/src/main/java/io/github/lycheeappf/tmm/ui/screen/sms/SmsThreadScreen.kt" "app/src/test/java/io/github/lycheeappf/tmm/ui/screen/sms/SmsThreadScreenTest.kt" app/src/main/res/values/strings_sms.xml app/src/main/res/values-de/strings_sms.xml
git commit -m "feat(ui): copy SMS message text to clipboard via long-press on bubble"
```

---

### Task 3: Verifikation (Suite, Build, Gerät)

**Files:** keine Änderungen — nur Verifikation.

**Interfaces:**
- Consumes: Ergebnisse aus Task 1 + 2.
- Produces: verifizierter Stand auf `nightly`.

- [ ] **Step 1: Komplette Unit-Test-Suite**

Run: `gradlew.bat :app:test`
Expected: `BUILD SUCCESSFUL`, keine Fehlschläge (aggregiert debug + release Unit-Tests).

- [ ] **Step 2: Debug-APK baut**

Run: `gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, Artefakt unter `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 3: Manuelle Geräteprüfung (einzige nicht automatisierbare Prüfung)**

1. `app-debug.apk` installieren (`adb install -r app/build/outputs/apk/debug/app-debug.apk`).
2. App öffnen → Konversation mit mindestens einer Nachricht öffnen.
3. Long-Press auf eine Nachrichten-Bubble (eingehend UND ausgehend testen).
4. Expected: kurze Vibration + Android-13-System-Overlay „Kopiert“ unten links; Einfügen in ein beliebiges Textfeld liefert exakt den Nachrichtentext.
5. Gegenprobe Sprache: App-Sprache auf Deutsch stellen (Einstellungen → Sprache) und mit TalkBack prüfen, dass die Long-Press-Aktion als „Nachricht kopieren“ angesagt wird (EN: „Copy message“).

- [ ] **Step 4: Abschluss**

Kein Merge/Push in diesem Plan — Integration (`nightly` → `main`, Release) läuft über den üblichen PR-Prozess des Repos (siehe superpowers:finishing-a-development-branch).
