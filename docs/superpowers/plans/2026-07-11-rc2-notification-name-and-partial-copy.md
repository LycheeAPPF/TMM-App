# v1.0.0-rc2: Notification Contact Name + SMS Partial-Text Copy — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the two rc1 field reports — (1) the incoming-SMS notification shows the raw phone number instead of the contact name; (2) long-press copy always copies the whole SMS with no way to select part of it (e.g. a 2FA code) — then release as 1.0.0-rc2 on `nightly`.

**Architecture:** (1) `DeliverSmsReceiver` resolves the sender via the existing `ContactNameResolver` (PhoneLookup, cached, permission-guarded), obtained through a Hilt `@EntryPoint` accessor because the receiver is a plain `BroadcastReceiver` (no `@AndroidEntryPoint`; keeps existing tests Hilt-free since they call `postIncomingSmsNotification` directly). (2) The bubble long-press opens a `DropdownMenu` with two actions — "Copy message" (existing whole-body copy) and "Select text" (an `AlertDialog` whose body is wrapped in `SelectionContainer` so the system selection toolbar handles partial copy). This sidesteps the documented gesture collision from the 2026-07-10 plan: `SelectionContainer` never competes with the bubble's long-press because it only exists inside the dialog.

**Tech Stack:** Kotlin, Hilt (EntryPointAccessors), Compose Material3 (`DropdownMenu`, `AlertDialog`, `SelectionContainer`), Robolectric + Compose UI tests (testDebug source set), JUnit4 + Truth.

## Global Constraints

- Every new user-facing string MUST exist in both `app/src/main/res/values/` (English, default locale) and `app/src/main/res/values-de/` (German).
- Never log message bodies into `LogBuffer` or Logcat beyond what already exists.
- Compose-UI tests (`createComposeRule`) live under `app/src/testDebug/java`, NOT `app/src/test/java`.
- Use `gradlew.bat` (Windows). Do NOT run `:app:lint` (known tooling crash, not app code).
- Commit messages: conventional style (`fix(...)`, `feat(...)`, `chore(...)`). Do NOT add any `Co-Authored-By` / AI-provenance trailer.
- Work directly on the `nightly` branch (no feature branch, per user instruction).
- If a build fails with "cannot delete app/build/generated/ksp": `gradlew.bat --stop`, delete `app/build`, rebuild (known Windows KSP lock).

---

### Task 1: Contact name in the incoming-SMS notification

**Files:**
- Modify: `app/src/main/java/io/github/lycheeappf/tmm/sms/default_app/DeliverSmsReceiver.kt`
- Test: `app/src/test/java/io/github/lycheeappf/tmm/sms/default_app/DeliverSmsReceiverTest.kt`

**Interfaces:**
- Consumes: `io.github.lycheeappf.tmm.sms.read.ContactNameResolver.resolve(address: String): String?` (existing `@Singleton`; returns null on missing READ_CONTACTS / no match / blank address).
- Produces: `postIncomingSmsNotification(context: Context, address: String, senderName: String?, body: String, threadId: Long)` — new `senderName` parameter, title falls back to `address` when null. Task 3's changelog references this fix.

- [ ] **Step 1: Write the failing tests**

In `DeliverSmsReceiverTest.kt`, add `import android.app.Notification` at the top, then add these two tests inside the class:

```kotlin
@Test fun `notification title shows the resolved sender name instead of the raw number`() {
    receiver.postIncomingSmsNotification(
        context, "+4917012345", senderName = "Anna Schmidt", body = "hello", threadId = 42L
    )

    val title = postedNotifications().first()
        .extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    assertThat(title).contains("Anna Schmidt")
    assertThat(title).doesNotContain("+4917012345")
}

@Test fun `notification title falls back to the raw number when no name resolves`() {
    receiver.postIncomingSmsNotification(
        context, "+4917012345", senderName = null, body = "hello", threadId = 42L
    )

    val title = postedNotifications().first()
        .extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    assertThat(title).contains("+4917012345")
}
```

Also update the FOUR existing tests that call `postIncomingSmsNotification(context, "+4917012345", "hello", threadId = ...)` — they must pass the new parameter. Change each call to:

```kotlin
receiver.postIncomingSmsNotification(context, "+4917012345", senderName = null, body = "hello", threadId = 42L)
```

(keep each test's original `threadId` value: 42L, -1L, 1L, 42L respectively).

- [ ] **Step 2: Run tests to verify they fail**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.sms.default_app.DeliverSmsReceiverTest"`
Expected: FAIL — compilation error (`postIncomingSmsNotification` has no `senderName` parameter yet).

- [ ] **Step 3: Implement name resolution in the receiver**

In `DeliverSmsReceiver.kt`:

Add imports:

```kotlin
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.lycheeappf.tmm.sms.read.ContactNameResolver
```

Change the last line of `doDeliver` from

```kotlin
postIncomingSmsNotification(context, address, body, threadId)
```

to

```kotlin
postIncomingSmsNotification(context, address, resolveSenderName(context, address), body, threadId)
```

Add below `doDeliver`:

```kotlin
/**
 * PhoneLookup über den bestehenden [ContactNameResolver] (derselbe wie in der
 * In-App-SMS-UI). Zugriff per Hilt-EntryPoint, weil dieser Receiver bewusst
 * ein plain BroadcastReceiver ohne @AndroidEntryPoint ist. runCatching: eine
 * fehlgeschlagene Namensauflösung darf nie die Notification verhindern.
 */
private fun resolveSenderName(context: Context, address: String): String? =
    runCatching {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            DeliverSmsReceiverEntryPoint::class.java
        ).contactNameResolver().resolve(address)
    }.getOrNull()
```

Change the signature and title line of `postIncomingSmsNotification`:

```kotlin
@VisibleForTesting
internal fun postIncomingSmsNotification(
    context: Context,
    address: String,
    senderName: String?,
    body: String,
    threadId: Long
) {
```

and

```kotlin
.setContentTitle(context.localizedString(R.string.sms_incoming_title, senderName ?: address))
```

(`address` keeps driving the PendingIntent request code and notification id — unchanged.)

Add at the bottom of the file (top level, after the class):

```kotlin
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DeliverSmsReceiverEntryPoint {
    fun contactNameResolver(): ContactNameResolver
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.sms.default_app.DeliverSmsReceiverTest"`
Expected: PASS (all 7 tests: 5 existing + 2 new).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/lycheeappf/tmm/sms/default_app/DeliverSmsReceiver.kt app/src/test/java/io/github/lycheeappf/tmm/sms/default_app/DeliverSmsReceiverTest.kt
git commit -m "fix(sms): show contact name in incoming-SMS notification"
```

---

### Task 2: Long-press menu on SMS bubbles — Copy message / Select text

**Files:**
- Modify: `app/src/main/java/io/github/lycheeappf/tmm/ui/screen/sms/SmsThreadScreen.kt`
- Modify: `app/src/main/res/values/strings_sms.xml`
- Modify: `app/src/main/res/values-de/strings_sms.xml`
- Test: `app/src/testDebug/java/io/github/lycheeappf/tmm/ui/screen/sms/SmsThreadScreenTest.kt`

**Interfaces:**
- Consumes: existing `MessageBubble(message: SmsMessage)` composable, `linkifySmsBody(body, linkStyle)`, string `R.string.sms_thread_copy_action` ("Copy message" / "Nachricht kopieren", reused as menu-item label).
- Produces: new strings `sms_thread_bubble_actions`, `sms_thread_select_action`, `sms_thread_select_close`. Behavior contract for Task 3's changelog: long-press opens menu; "Copy message" copies full body; "Select text" opens a selection dialog.

- [ ] **Step 1: Write the failing tests**

Replace the entire content of `app/src/testDebug/java/io/github/lycheeappf/tmm/ui/screen/sms/SmsThreadScreenTest.kt` with:

```kotlin
package io.github.lycheeappf.tmm.ui.screen.sms

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.R
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

    // Clipboard über den Composition-Context lesen, nicht über den
    // Application-Context: Robolectric hält Shadow-State pro
    // ClipboardManager-Instanz, und LocalClipboardManager schreibt in die
    // Instanz des Host-Activity-Contexts.
    private lateinit var composeContext: Context

    private fun setBubbleContent() {
        compose.setContent {
            composeContext = LocalContext.current
            MfsTheme { MessageBubble(message) }
        }
    }

    @Test
    fun `long press opens the actions menu instead of copying directly`() {
        setBubbleContent()

        compose.onNodeWithText(message.body).performTouchInput { longClick() }

        val clipboard = composeContext.getSystemService(ClipboardManager::class.java)
        assertThat(clipboard.primaryClip).isNull()
        compose.onNodeWithText(composeContext.getString(R.string.sms_thread_copy_action))
            .assertExists()
        compose.onNodeWithText(composeContext.getString(R.string.sms_thread_select_action))
            .assertExists()
    }

    @Test
    fun `copy action copies the full body to the clipboard`() {
        setBubbleContent()

        compose.onNodeWithText(message.body).performTouchInput { longClick() }
        compose.onNodeWithText(composeContext.getString(R.string.sms_thread_copy_action))
            .performClick()

        val clipboard = composeContext.getSystemService(ClipboardManager::class.java)
        val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        assertThat(copied).isEqualTo(message.body)
    }

    @Test
    fun `select text action opens a dialog with the message body`() {
        setBubbleContent()

        compose.onNodeWithText(message.body).performTouchInput { longClick() }
        compose.onNodeWithText(composeContext.getString(R.string.sms_thread_select_action))
            .performClick()

        // Body erscheint jetzt zweimal: in der Bubble und im Selektions-Dialog.
        compose.onAllNodesWithText(message.body).assertCountEquals(2)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.ui.screen.sms.SmsThreadScreenTest"`
Expected: FAIL — compilation error (`R.string.sms_thread_select_action` does not exist yet).

- [ ] **Step 3: Add the new strings (EN + DE)**

In `app/src/main/res/values/strings_sms.xml`, after the `sms_thread_copy_action` line, add:

```xml
    <string name="sms_thread_bubble_actions">Message options</string>
    <string name="sms_thread_select_action">Select text</string>
    <string name="sms_thread_select_close">Close</string>
```

In `app/src/main/res/values-de/strings_sms.xml`, after the `sms_thread_copy_action` line, add:

```xml
    <string name="sms_thread_bubble_actions">Nachrichtenoptionen</string>
    <string name="sms_thread_select_action">Text auswählen</string>
    <string name="sms_thread_select_close">Schließen</string>
```

- [ ] **Step 4: Implement the menu + selection dialog**

In `app/src/main/java/io/github/lycheeappf/tmm/ui/screen/sms/SmsThreadScreen.kt`:

Add imports (keep the existing ones):

```kotlin
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
```

Replace the entire `MessageBubble` composable (currently lines 169-240) with:

```kotlin
@Composable
internal fun MessageBubble(message: SmsMessage) {
    val incoming = message.isIncoming
    val failed = message.direction == SmsDirection.FAILED
    val outboxPending = message.direction == SmsDirection.OUTBOX

    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current

    var menuExpanded by remember { mutableStateOf(false) }
    var showSelectDialog by remember { mutableStateOf(false) }

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
        // Anker-Box: das DropdownMenu positioniert sich relativ zur Bubble,
        // nicht zur vollen Zeile; Start/End-Ausrichtung erzeugt das typische
        // Chat-Layout (eingehend links, ausgehend rechts).
        Box(modifier = Modifier.align(if (incoming) Alignment.CenterStart else Alignment.CenterEnd)) {
            Surface(color = container, shape = MaterialTheme.shapes.large) {
                Column(
                    modifier = Modifier
                        // Auf der inneren Column (nicht auf der Surface), damit der
                        // Ripple über dem Bubble-Hintergrund liegt und von der
                        // Surface-Shape geclippt wird.
                        .combinedClickable(
                            onClick = {},
                            onLongClickLabel = stringResource(R.string.sms_thread_bubble_actions),
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                menuExpanded = true
                            }
                        )
                        .padding(horizontal = MfsSpacing.md, vertical = MfsSpacing.sm)
                ) {
                    // Links im Body tappbar machen (LinkAnnotation.Url → Default-UriHandler).
                    // Unterstreichung statt eigener Farbe: bleibt auf allen drei
                    // Bubble-Containern (surfaceVariant/primaryContainer/errorContainer) lesbar.
                    val body = remember(message.body) { linkifySmsBody(message.body, linkStyle) }
                    Text(
                        body,
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
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sms_thread_copy_action)) },
                    onClick = {
                        menuExpanded = false
                        // Kein eigener Snackbar/Toast: ab Android 13 (minSdk 33)
                        // zeigt das System selbst ein Kopier-Overlay.
                        clipboard.setText(AnnotatedString(message.body))
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sms_thread_select_action)) },
                    onClick = {
                        menuExpanded = false
                        showSelectDialog = true
                    }
                )
            }
        }
    }

    if (showSelectDialog) {
        AlertDialog(
            onDismissRequest = { showSelectDialog = false },
            confirmButton = {
                TextButton(onClick = { showSelectDialog = false }) {
                    Text(stringResource(R.string.sms_thread_select_close))
                }
            },
            // Roher Body ohne Link-Spans: im Selektions-Dialog geht es ums
            // Markieren von Teiltext (z. B. ein 2FA-Code), nicht ums Öffnen
            // von Links; das System-Selektionsmenü übernimmt das Kopieren.
            text = {
                SelectionContainer {
                    Text(message.body, style = MaterialTheme.typography.bodyMedium)
                }
            }
        )
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.ui.screen.sms.SmsThreadScreenTest"`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/lycheeappf/tmm/ui/screen/sms/SmsThreadScreen.kt app/src/main/res/values/strings_sms.xml app/src/main/res/values-de/strings_sms.xml app/src/testDebug/java/io/github/lycheeappf/tmm/ui/screen/sms/SmsThreadScreenTest.kt
git commit -m "feat(ui): long-press menu on SMS bubbles with copy and select-text actions"
```

---

### Task 3: Version bump to 1.0.0-rc2 + changelog + full verification

**Files:**
- Modify: `app/build.gradle.kts:41-42`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: the behavior contracts of Tasks 1 and 2 (changelog wording below describes them).
- Produces: `versionCode = 12`, `versionName = "1.0.0-rc2"`.

- [ ] **Step 1: Bump the version**

In `app/build.gradle.kts`, change

```kotlin
        versionCode = 11
        versionName = "1.0.0-rc1"
```

to

```kotlin
        versionCode = 12
        versionName = "1.0.0-rc2"
```

- [ ] **Step 2: Add the changelog entry**

In `CHANGELOG.md`, insert directly after the intro block (before the `## [1.0.0-rc1] — 2026-07-10` heading):

```markdown
## [1.0.0-rc2] — 2026-07-11

Second release candidate: two field reports from rc1 testing.

### Fixed
- **Incoming-SMS notifications now show the contact's name** instead of the raw phone
  number, using the same contact lookup as the in-app conversation list (falls back to
  the number without contacts permission or without a match).

### Changed
- **Long-pressing a message bubble now opens a menu** with "Copy message" and
  "Select text". "Select text" shows the message in a dialog where any part of it
  (e.g. a 2FA code) can be selected and copied — previously long-press always copied
  the entire message.

```

- [ ] **Step 3: Run the full unit-test suite**

Run: `gradlew.bat :app:test`
Expected: BUILD SUCCESSFUL, no failing tests (aggregates debug + release unit tests).

- [ ] **Step 4: Build the debug APK as a smoke check**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL, artifact at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts CHANGELOG.md
git commit -m "chore(release): bump version to 1.0.0-rc2"
```

---

## Out of scope (known, deliberately deferred)

- `LocalClipboardManager` → `LocalClipboard`/`ClipEntry` migration and `ClipDescription.EXTRA_IS_SENSITIVE` (couples all three clipboard call sites + `FallbackNotifier`; tracked as separate follow-up).
- MessagingStyle/Person-based conversation notification and a dedicated "Messages" notification channel (polish; the fix here only corrects the title).
- TalkBack no-op-tap semantics of the bubble (`onClick = {}`) — separate accessibility follow-up.

---

### Task 4: Direct text selection on long-press (user decision overturning Task 2's menu)

**User decision (2026-07-11):** The Task-2 DropdownMenu + dialog flow is rejected. Long-press on a bubble must start native text selection directly on the message text (word under finger selected, drag handles, system selection toolbar for Copy/Select all) — no menu, no dialog, no direct whole-copy. This supersedes the 2026-07-10 plan's "Verworfene Alternative" reasoning by explicit user instruction.

**Files:**
- Modify: `app/src/main/java/io/github/lycheeappf/tmm/ui/screen/sms/SmsThreadScreen.kt`
- Modify: `app/src/main/res/values/strings_sms.xml`
- Modify: `app/src/main/res/values-de/strings_sms.xml`
- Modify: `CHANGELOG.md` (reword the rc2 "Changed" bullet)
- Test: `app/src/testDebug/java/io/github/lycheeappf/tmm/ui/screen/sms/SmsThreadScreenTest.kt`

**Interfaces:**
- Produces: `MessageBubble` renders the linkified body inside `SelectionContainer`; the meta line stays non-selectable. Strings `sms_thread_copy_action`, `sms_thread_bubble_actions`, `sms_thread_select_action`, `sms_thread_select_close` are removed from BOTH locales (verify no other usages first with a grep).

- [ ] **Step 1: Write the failing tests**

Replace the test class body of `SmsThreadScreenTest.kt` with two tests:

1. `long press does not copy and opens no menu`: set content with `MessageBubble(message)`, capture `composeContext` via `LocalContext.current`, `performTouchInput { longClick() }` on the body node, then assert the clipboard `primaryClip` is null and `compose.onAllNodesWithText(message.body).assertCountEquals(1)` (no dialog duplicating the body).
2. `long press starts native text selection`: provide a recording fake `TextToolbar` via `CompositionLocalProvider(LocalTextToolbar provides fakeToolbar)` around `MfsTheme { MessageBubble(message) }`; after `longClick()` on the body node (plus `compose.waitForIdle()`), assert the fake toolbar's `showMenu` was invoked (i.e. a selection exists and the system copy toolbar was requested). Adapt the fake to the exact `TextToolbar` interface of the project's Compose version (override `showMenu`, `hide`, `status`; if the interface in this Compose version has additional `showMenu` parameters/overloads, override them all — the compiler will tell you).

If assertion 2 proves impossible in Robolectric (toolbar never invoked in this environment), replace it with the strongest available real-behavior assertion and document why in your report — do NOT ship a test that asserts nothing.

- [ ] **Step 2: Run tests to verify they fail**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.ui.screen.sms.SmsThreadScreenTest"`
Expected: FAIL (current code copies nothing on long-press either — but the menu opens, so test 1's `assertCountEquals(1)` still passes; test 2 fails because no selection starts. At minimum test 2 must be RED before implementation.)

- [ ] **Step 3: Implement**

In `MessageBubble`:
- Delete: `clipboard`/`haptics` locals, `menuExpanded`/`showSelectDialog` state, the `combinedClickable` modifier, the inner anchor `Box`, the `DropdownMenu` block, and the entire `AlertDialog` block.
- Restore the `Surface` alignment modifier directly on `Surface` (as before Task 2): `modifier = Modifier.align(if (incoming) Alignment.CenterStart else Alignment.CenterEnd)`.
- Wrap ONLY the body `Text` (the linkified `body`) in `SelectionContainer { ... }`, with a German comment explaining: Long-Press startet die native Teiltext-Selektion (z. B. 2FA-Code); Kopieren übernimmt die System-Toolbar; Links bleiben per Tap öffenbar; die Meta-Zeile bleibt bewusst unselektierbar.
- Remove now-unused imports (`combinedClickable`, `HapticFeedbackType`, `LocalClipboardManager`, `LocalHapticFeedback`, `AnnotatedString`, `DropdownMenu`, `DropdownMenuItem`, `AlertDialog`, `TextButton`, `mutableStateOf`, `setValue`, `rememberScrollState`, `verticalScroll` — keep `SelectionContainer`, keep `getValue` only if still used).
- Remove the four strings from both `strings_sms.xml` files (grep first to confirm each is unused elsewhere).
- In `CHANGELOG.md`, replace the rc2 "Changed" bullet with:

```markdown
- **Long-pressing a message bubble now starts text selection** — select any part of a
  message (e.g. a 2FA code) directly in the bubble and copy it via the system toolbar;
  previously long-press always copied the entire message.
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.ui.screen.sms.SmsThreadScreenTest"`
Expected: PASS. Then run the full suite: `gradlew.bat :app:test` — BUILD SUCCESSFUL (release variant compiles = removed strings had no other usages).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/lycheeappf/tmm/ui/screen/sms/SmsThreadScreen.kt app/src/main/res/values/strings_sms.xml app/src/main/res/values-de/strings_sms.xml app/src/testDebug/java/io/github/lycheeappf/tmm/ui/screen/sms/SmsThreadScreenTest.kt CHANGELOG.md
git commit -m "feat(ui): native text selection on long-press in SMS bubbles"
```
