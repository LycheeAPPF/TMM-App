# Issue-Report Fixes (v1.0-RC1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the four verified defects from the jodox issue reports (analysis: `issue-reports/analyse-report-2026-07-10.md`) — group-summary payload clobber (A), own-reply echo (B), inert links + Google-Messages quick-reply intent (C2/C1-residual), observer log spam — and bump the app to 1.0.0-rc1.

**Architecture:** All A/B fixes land at the capture seam (`NotificationCapture`, `MessagingStyleExtractor`) plus payload stickiness in `MappingRepositoryImpl`. A new capture-side `SentReplyLedger` (mirror of `InjectedMessageLedger`) guards against echo re-capture. UI fix adds a linkifier for SMS bubbles. No DB schema changes, no new Hilt modules (all new classes are `@Singleton` + `@Inject constructor`).

**Tech Stack:** Kotlin, Hilt, Compose (BOM 2025.06.01), JUnit4 + Truth + MockK + kotlinx-coroutines-test, Robolectric (`@Config(sdk = [33])`) only where a real `Notification`/`Context` is needed.

## Global Constraints

- Windows: always `gradlew.bat`, never system gradle. Test command pattern: `gradlew.bat :app:testDebugUnitTest --tests "<fqcn>"`.
- Do NOT run `gradlew.bat :app:lint` (known tooling crash, not app code).
- If a build fails with "unable to delete app/build/generated/ksp": run `gradlew.bat --stop`, delete `app/build`, retry.
- Tests mirror the main package tree under `app/src/test/java`; test names are backtick sentences; Robolectric ONLY when a real Context/Notification is required.
- Never log message bodies/dictations into `LogBuffer` (it is exported) — metadata/lengths only.
- Time is injectable: use the `Clock` fun-interface (`io.github.lycheeappf.tmm.core.util.Clock`), never `System.currentTimeMillis()` in new logic.
- Never use `Dispatchers.*` directly — qualifiers `@IoDispatcher`/`@DefaultDispatcher`/`@MainDispatcher`.
- i18n: every user-visible string exists in `res/values/strings.xml` (EN, default) AND `res/values-de/strings.xml` (DE).
- Commit messages: conventional style (`fix(scope): …`). **Never add `Co-Authored-By: Claude` or any AI-provenance trailer.**
- Code comments: match the codebase's German/English mix; comments explain constraints, not narration.

---

### Task 1: Skip group-summary notifications in NotificationCapture

**Files:**
- Modify: `app/src/main/java/io/github/lycheeappf/tmm/channel/notification/NotificationCapture.kt`
- Create: `app/src/test/java/io/github/lycheeappf/tmm/channel/notification/NotificationCaptureTest.kt`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: nothing other tasks rely on (behavioral fix).

**Background (from the verified analysis):** WhatsApp posts a group-summary notification (`FLAG_GROUP_SUMMARY`, no RemoteInput) that carries the newest conversation's MessagingStyle. Without a filter it (a) injects summary text as a fake inbound SMS and (b) reaches `allocateOrReuse`, clobbering the mapping payload's `notificationKey` → replies fail `NO_ACTION`.

- [ ] **Step 1: Write the failing test**

Create `NotificationCaptureTest.kt`. `NotificationCapture` has many dependencies — mock all of them with MockK (relaxed). A real `Notification` is needed for flags → Robolectric. `StatusBarNotification` is mocked (it has no public constructor for tests).

```kotlin
package io.github.lycheeappf.tmm.channel.notification

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.core.util.SendBudget
import io.github.lycheeappf.tmm.data.store.SettingsStore
import io.github.lycheeappf.tmm.domain.channel.ChannelMapping
import io.github.lycheeappf.tmm.domain.channel.ChannelPayload
import io.github.lycheeappf.tmm.domain.repository.MappingRepository
import io.github.lycheeappf.tmm.core.model.ChannelId
import io.github.lycheeappf.tmm.listener.filter.ExtractedMessage
import io.github.lycheeappf.tmm.listener.filter.MessagingStyleExtractor
import io.github.lycheeappf.tmm.listener.filter.WhitelistFilter
import io.github.lycheeappf.tmm.platform.bluetooth.BluetoothConnectionChecker
import io.github.lycheeappf.tmm.platform.role.DefaultSmsRoleManager
import io.github.lycheeappf.tmm.sms.provider.SmsContentProviderWriter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationCaptureTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val whitelist = mockk<WhitelistFilter>()
    private val extractor = mockk<MessagingStyleExtractor>()
    private val actionResolver = mockk<ActionResolver>(relaxed = true)
    private val actionCache = mockk<ActionCache>(relaxed = true)
    private val mappingRepository = mockk<MappingRepository>(relaxed = true)
    private val smsWriter = mockk<SmsContentProviderWriter>(relaxed = true)
    private val sendBudget = mockk<SendBudget>(relaxed = true)
    private val roleManager = mockk<DefaultSmsRoleManager>(relaxed = true)
    private val bluetooth = mockk<BluetoothConnectionChecker>(relaxed = true)
    private val settingsStore = mockk<SettingsStore>(relaxed = true)
    private val logBuffer = mockk<LogBuffer>(relaxed = true)
    private val sentReplyLedger = SentReplyLedger { 0L } // Task 3 fügt den Parameter hinzu; bis dahin diese Zeile weglassen

    private lateinit var capture: NotificationCapture

    private val extracted = ExtractedMessage(
        senderName = "Anna",
        body = "Hallo!",
        conversationLabel = "Anna",
        isGroup = false,
        conversationKey = "com.whatsapp::id::abc"
    )

    @Before
    fun setUp() {
        capture = NotificationCapture(
            whitelist, extractor, actionResolver, actionCache, mappingRepository,
            smsWriter, sendBudget, roleManager, bluetooth, settingsStore, logBuffer
        )
        every { whitelist.allow(any()) } returns true
        every { extractor.extract(any()) } returns extracted
        coEvery { roleManager.isDefault() } returns true
        coEvery { bluetooth.isTeslaConnected() } returns true
        coEvery { sendBudget.checkAndIncrement() } returns true
        coEvery { settingsStore.mappingTtlHours() } returns 24
        coEvery { mappingRepository.allocateOrReuse(any(), any(), any(), any()) } returns ChannelMapping(
            mappingId = 42L,
            channel = ChannelId.NOTIFICATION,
            fakeAddress = "+88800000042",
            conversationKey = extracted.conversationKey,
            payload = ChannelPayload.Notification("com.whatsapp", "key", "input", "Anna", "Anna"),
            createdAt = 0L, expiresAt = Long.MAX_VALUE, lastUsedAt = null,
            replyCount = 0, replyable = true
        )
        coEvery { smsWriter.injectIncoming(any(), any(), any(), any()) } returns mockk()
    }

    private fun sbnWith(flags: Int): StatusBarNotification {
        val notification = NotificationCompat.Builder(context, "ch")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
            .apply { this.flags = this.flags or flags }
        return mockk<StatusBarNotification> {
            every { key } returns "0|com.whatsapp|1|null|10467"
            every { packageName } returns "com.whatsapp"
            every { this@mockk.notification } returns notification
            every { postTime } returns 1_000L
        }
    }

    @Test
    fun `group summary notification is dropped before mapping and budget`() = runTest {
        capture.onPosted(sbnWith(Notification.FLAG_GROUP_SUMMARY))

        coVerify(exactly = 0) { mappingRepository.allocateOrReuse(any(), any(), any(), any()) }
        coVerify(exactly = 0) { sendBudget.checkAndIncrement() }
        coVerify(exactly = 0) { smsWriter.injectIncoming(any(), any(), any(), any()) }
    }

    @Test
    fun `regular notification passes through to inject`() = runTest {
        capture.onPosted(sbnWith(0))

        coVerify(exactly = 1) { mappingRepository.allocateOrReuse(any(), any(), any(), any()) }
        coVerify(exactly = 1) { smsWriter.injectIncoming("+88800000042", "Hallo!", 1_000L, "Anna") }
    }
}
```

Note: delete the `sentReplyLedger` line above for now (it belongs to Task 3); shown here only so Task 3's diff is understandable.

- [ ] **Step 2: Run test to verify it fails**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.channel.notification.NotificationCaptureTest"`
Expected: `group summary notification is dropped…` FAILS (allocateOrReuse WAS called); `regular notification…` PASSES.

- [ ] **Step 3: Implement the filter**

In `NotificationCapture.captureInternal`, directly after the whitelist check (line ~84), insert:

```kotlin
// Group-Summary-Notifications (z.B. WhatsApps Sammel-Notification bei ≥2 aktiven
// Chats) tragen die MessagingStyle der neuesten Konversation, aber KEINE
// Reply-Action. Ohne Filter würde (a) ihr Summary-Text als fake Inbound-SMS
// injiziert und (b) via allocateOrReuse der notificationKey eines guten
// Mappings mit dem action-losen Summary-Key überschrieben → Reply = NO_ACTION.
if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
```

Add import `android.app.Notification`.

- [ ] **Step 4: Run test to verify it passes**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.channel.notification.NotificationCaptureTest"`
Expected: both tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/lycheeappf/tmm/channel/notification/NotificationCapture.kt app/src/test/java/io/github/lycheeappf/tmm/channel/notification/NotificationCaptureTest.kt
git commit -m "fix(capture): ignore group-summary notifications (reply payload clobber)"
```

---

### Task 2: Skip self-authored messages in MessagingStyleExtractor

**Files:**
- Modify: `app/src/main/java/io/github/lycheeappf/tmm/listener/filter/MessagingStyleExtractor.kt:51-57`
- Test: `app/src/test/java/io/github/lycheeappf/tmm/listener/filter/MessagingStyleExtractorTest.kt` (extend)

**Interfaces:**
- Consumes: nothing.
- Produces: `extractFromNotification` now returns the newest **non-self** message, or `null` when every MessagingStyle entry is self-authored. Task 3 relies on this as the primary echo fix.

**Background:** After a RemoteInput reply, WhatsApp re-posts its notification with the user's own reply as the newest MessagingStyle message. Convention (androidx): `Message.person == null` ⇒ authored by the device user; some apps instead set a `Person` equal to `style.user`. Both must be skipped.

- [ ] **Step 1: Write the failing tests**

Append to `MessagingStyleExtractorTest.kt` (uses existing Robolectric setup; `Person` is `androidx.core.app.Person`):

```kotlin
    private fun messagingStyleNotif(style: NotificationCompat.MessagingStyle): Notification =
        NotificationCompat.Builder(context, "ch")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setStyle(style)
            .build()

    @Test
    fun `skips trailing self message with null person and returns newest incoming`() {
        val me = Person.Builder().setName("Ich").setKey("me-key").build()
        val anna = Person.Builder().setName("Anna").setKey("anna-key").build()
        val style = NotificationCompat.MessagingStyle(me)
            .addMessage("Hallo!", 1L, anna)
            .addMessage("meine eigene Antwort", 2L, null as Person?)

        val result = extractor.extractFromNotification("com.whatsapp", messagingStyleNotif(style))

        assertThat(result).isNotNull()
        assertThat(result!!.body).isEqualTo("Hallo!")
        assertThat(result.senderName).isEqualTo("Anna")
    }

    @Test
    fun `skips trailing self message whose person equals style user by key`() {
        val me = Person.Builder().setName("Ich").setKey("me-key").build()
        val anna = Person.Builder().setName("Anna").setKey("anna-key").build()
        val style = NotificationCompat.MessagingStyle(me)
            .addMessage("Hallo!", 1L, anna)
            .addMessage("meine eigene Antwort", 2L, me)

        val result = extractor.extractFromNotification("com.whatsapp", messagingStyleNotif(style))

        assertThat(result).isNotNull()
        assertThat(result!!.body).isEqualTo("Hallo!")
    }

    @Test
    fun `skips trailing self message whose person matches style user by name when keys absent`() {
        val me = Person.Builder().setName("Ich").build()
        val anna = Person.Builder().setName("Anna").build()
        val style = NotificationCompat.MessagingStyle(me)
            .addMessage("Hallo!", 1L, anna)
            .addMessage("meine eigene Antwort", 2L, Person.Builder().setName("Ich").build())

        val result = extractor.extractFromNotification("com.whatsapp", messagingStyleNotif(style))

        assertThat(result).isNotNull()
        assertThat(result!!.body).isEqualTo("Hallo!")
    }

    @Test
    fun `returns null when all messages are self authored`() {
        val me = Person.Builder().setName("Ich").setKey("me-key").build()
        val style = NotificationCompat.MessagingStyle(me)
            .addMessage("nur ich", 1L, null as Person?)
            .addMessage("schon wieder ich", 2L, me)

        assertThat(extractor.extractFromNotification("com.whatsapp", messagingStyleNotif(style))).isNull()
    }

    @Test
    fun `incoming message from other person is still extracted normally`() {
        val me = Person.Builder().setName("Ich").setKey("me-key").build()
        val anna = Person.Builder().setName("Anna").setKey("anna-key").build()
        val style = NotificationCompat.MessagingStyle(me)
            .addMessage("Hallo!", 1L, anna)

        val result = extractor.extractFromNotification("com.whatsapp", messagingStyleNotif(style))

        assertThat(result).isNotNull()
        assertThat(result!!.body).isEqualTo("Hallo!")
        assertThat(result.senderName).isEqualTo("Anna")
    }
```

Add imports: `androidx.core.app.Person`.

- [ ] **Step 2: Run tests to verify the new ones fail**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.listener.filter.MessagingStyleExtractorTest"`
Expected: the three "skips…" tests and "returns null when all…" FAIL (body is the self message / not null); pre-existing tests PASS.

- [ ] **Step 3: Implement self-message skip**

In `MessagingStyleExtractor.extractFromNotification`, replace lines 51-57:

```kotlin
        if (style != null && style.messages.isNotEmpty()) {
            // Echo-Fix: nach einem RemoteInput-Reply re-postet der Messenger die
            // Notification mit der EIGENEN Antwort als neuester Message. Konvention
            // (androidx): person == null ⇒ vom Geräte-User; manche Apps setzen
            // stattdessen eine Person, die style.user entspricht. Beide Fälle
            // überspringen — sonst wird die eigene Antwort als neue Inbound-SMS
            // injiziert und im Auto vorgelesen.
            val last = style.messages.lastOrNull { !isSelfAuthored(it, style.user) }
                ?: return null
            val senderName = last.person?.name?.toString()
                ?: style.user.name?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: "Unknown"
```

(rest of the branch unchanged — `body`, `conversationLabel`, `isGroup`, `conversationKey` keep using `last`).

Add the private helper below `extractFromNotification`:

```kotlin
    /**
     * true, wenn die Message vom Geräte-User selbst stammt. `person == null` ist
     * die dokumentierte androidx-Konvention für Self-Messages; als Fallback
     * matchen wir die Person gegen style.user — bevorzugt über den stabilen
     * key, sonst über den Namen (nur wenn BEIDE keys fehlen, sonst wäre ein
     * Kontakt, der zufällig wie der User heißt, fälschlich "self").
     */
    private fun isSelfAuthored(
        message: NotificationCompat.MessagingStyle.Message,
        user: androidx.core.app.Person
    ): Boolean {
        val person = message.person ?: return true
        val personKey = person.key
        val userKey = user.key
        if (personKey != null && userKey != null) return personKey == userKey
        if (personKey != null || userKey != null) return false
        val personName = person.name?.toString() ?: return false
        return personName == user.name?.toString()
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.listener.filter.MessagingStyleExtractorTest"`
Expected: ALL tests PASS (including pre-existing ones — the fallback-extras tests don't enter the MessagingStyle branch).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/lycheeappf/tmm/listener/filter/MessagingStyleExtractor.kt app/src/test/java/io/github/lycheeappf/tmm/listener/filter/MessagingStyleExtractorTest.kt
git commit -m "fix(capture): skip self-authored MessagingStyle messages (own-reply echo)"
```

---

### Task 3: Capture-side SentReplyLedger (belt-and-suspenders echo guard)

**Files:**
- Create: `app/src/main/java/io/github/lycheeappf/tmm/channel/notification/SentReplyLedger.kt`
- Modify: `app/src/main/java/io/github/lycheeappf/tmm/channel/notification/NotificationReplyExecutor.kt` (record on Success)
- Modify: `app/src/main/java/io/github/lycheeappf/tmm/channel/notification/NotificationCapture.kt` (check before inject)
- Create: `app/src/test/java/io/github/lycheeappf/tmm/channel/notification/SentReplyLedgerTest.kt`
- Modify: `app/src/test/java/io/github/lycheeappf/tmm/channel/notification/NotificationReplyExecutorTest.kt`, `NotificationCaptureTest.kt`

**Interfaces:**
- Consumes: `Clock` fun-interface (`core/util/Clock.kt`), pattern from `channel/llm/InjectedMessageLedger.kt`.
- Produces: `SentReplyLedger.record(sourcePackage: String, body: String)` and `SentReplyLedger.isRecentReply(sourcePackage: String, body: String): Boolean`. Constructor: `@Inject constructor(clock: Clock)`, `@Singleton` — no Hilt module change needed.

**Background:** Task 2 is the primary fix; this ledger covers apps that mislabel the self `Person`. Keyed on `(sourcePackage, body.hashCode())` because the executor doesn't know the `conversationKey`. Short TTL keeps false positives (a contact echoing the exact text back) unlikely.

- [ ] **Step 1: Write the failing ledger test**

```kotlin
package io.github.lycheeappf.tmm.channel.notification

import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.core.util.Clock
import org.junit.Test

class SentReplyLedgerTest {

    private var now = 0L
    private val ledger = SentReplyLedger(Clock { now })

    @Test
    fun `recorded reply is recognized within ttl`() {
        ledger.record("com.whatsapp", "meine Antwort")
        now += 5_000
        assertThat(ledger.isRecentReply("com.whatsapp", "meine Antwort")).isTrue()
    }

    @Test
    fun `entry expires after ttl`() {
        ledger.record("com.whatsapp", "meine Antwort")
        now += 15_001
        assertThat(ledger.isRecentReply("com.whatsapp", "meine Antwort")).isFalse()
    }

    @Test
    fun `different body or package does not match`() {
        ledger.record("com.whatsapp", "meine Antwort")
        assertThat(ledger.isRecentReply("com.whatsapp", "andere Nachricht")).isFalse()
        assertThat(ledger.isRecentReply("org.telegram.messenger", "meine Antwort")).isFalse()
    }
}
```

- [ ] **Step 2: Run to verify it fails to compile** (class missing)

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.channel.notification.SentReplyLedgerTest"`
Expected: compilation error `Unresolved reference: SentReplyLedger`.

- [ ] **Step 3: Implement the ledger**

```kotlin
package io.github.lycheeappf.tmm.channel.notification

import io.github.lycheeappf.tmm.core.util.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Capture-seitiges Echo-Ledger (Spiegel von [io.github.lycheeappf.tmm.channel.llm.InjectedMessageLedger],
 * aber in Gegenrichtung): jeder erfolgreiche RemoteInput-Reply registriert
 * (sourcePackage, bodyHash). [NotificationCapture] verwirft Re-Captures, deren
 * Body einem in den letzten [TTL_MS] gesendeten Reply gleicht — Messenger
 * re-posten ihre Notification nach dem Reply mit der EIGENEN Antwort als
 * neuester Message. Primärfix ist der Self-Skip im MessagingStyleExtractor;
 * dieses Ledger fängt Apps ab, die die Self-Person falsch labeln.
 *
 * TTL bewusst kurz: false-positive = eine echte eingehende Nachricht, die
 * zufällig wortgleich mit der eigenen Antwort ist, würde gedroppt.
 */
@Singleton
class SentReplyLedger @Inject constructor(
    private val clock: Clock
) {

    private data class Entry(val sourcePackage: String, val bodyHash: Int, val at: Long)

    private val entries = ArrayDeque<Entry>()
    private val lock = Any()

    fun record(sourcePackage: String, body: String) {
        synchronized(lock) {
            entries.addLast(Entry(sourcePackage, body.hashCode(), clock.now()))
            evictOld()
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
    }

    fun isRecentReply(sourcePackage: String, body: String): Boolean = synchronized(lock) {
        evictOld()
        val hash = body.hashCode()
        entries.any { it.sourcePackage == sourcePackage && it.bodyHash == hash }
    }

    private fun evictOld() {
        val cutoff = clock.now() - TTL_MS
        while (entries.isNotEmpty() && entries.first().at < cutoff) {
            entries.removeFirst()
        }
    }

    companion object {
        private val TTL_MS = TimeUnit.SECONDS.toMillis(15)
        private const val MAX_ENTRIES = 50
    }
}
```

- [ ] **Step 4: Run ledger test — passes**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.channel.notification.SentReplyLedgerTest"`
Expected: PASS.

- [ ] **Step 5: Write failing integration tests**

`NotificationReplyExecutorTest.kt` — add (mirror the file's existing mock setup; add `private val sentReplyLedger = mockk<SentReplyLedger>(relaxed = true)` and pass it as new constructor arg):

```kotlin
    @Test
    fun `successful reply records body in sent-reply ledger`() = runTest {
        // arrange wie im bestehenden Success-Test (gecachte Action, send() ok)
        executor.reply(payload, mappingId = 7L, text = "meine Antwort")
        verify(exactly = 1) { sentReplyLedger.record(payload.sourcePackage, "meine Antwort") }
    }

    @Test
    fun `failed reply does not record in sent-reply ledger`() = runTest {
        // arrange wie im bestehenden NO_ACTION-Test (cache-miss + rebuild-miss)
        executor.reply(payload, mappingId = 7L, text = "meine Antwort")
        verify(exactly = 0) { sentReplyLedger.record(any(), any()) }
    }
```

`NotificationCaptureTest.kt` — replace the mock-less construction with a real ledger `SentReplyLedger { 0L }`… actually use a MockK: add `private val sentReplyLedger = mockk<SentReplyLedger>()`, default `every { sentReplyLedger.isRecentReply(any(), any()) } returns false` in `setUp`, pass as new constructor arg, and add:

```kotlin
    @Test
    fun `body matching a recent own reply is dropped before mapping and budget`() = runTest {
        every { sentReplyLedger.isRecentReply("com.whatsapp", "Hallo!") } returns true

        capture.onPosted(sbnWith(0))

        coVerify(exactly = 0) { mappingRepository.allocateOrReuse(any(), any(), any(), any()) }
        coVerify(exactly = 0) { sendBudget.checkAndIncrement() }
    }
```

- [ ] **Step 6: Run both test classes — new tests fail to compile** (constructor args missing)

- [ ] **Step 7: Wire the ledger**

`NotificationReplyExecutor`: add constructor param `private val sentReplyLedger: SentReplyLedger`. In the success path of `reply(...)` (after `resolved.actionIntent.send(context, 0, intent)`, before returning `ReplyResult.Success`):

```kotlin
            // Echo-Guard: der Messenger wird seine Notification gleich mit genau
            // diesem Text als neuester Message re-posten. Capture-Seite droppt
            // Re-Captures mit diesem Body für kurze Zeit.
            sentReplyLedger.record(payload.sourcePackage, text)
```

`NotificationCapture`: add constructor param `private val sentReplyLedger: SentReplyLedger`. In `captureInternal`, after the dedup check (`if (previousBody == msg.body) return`), insert:

```kotlin
        // Echo-Guard (Belt-and-Suspenders zum Self-Skip im Extractor): Body
        // gleicht einem soeben via RemoteInput gesendeten eigenen Reply → das
        // ist der Notification-Re-Post des Messengers, keine neue Nachricht.
        if (sentReplyLedger.isRecentReply(sbn.packageName, msg.body)) {
            logBuffer.info(TAG, "Echo drop ${sbn.key} (own reply re-post, ${msg.body.length} chars)")
            return
        }
```

- [ ] **Step 8: Run all three test classes**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.channel.notification.*"`
Expected: ALL PASS.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/io/github/lycheeappf/tmm/channel/notification/ app/src/test/java/io/github/lycheeappf/tmm/channel/notification/
git commit -m "fix(capture): drop re-captured own replies via SentReplyLedger"
```

---

### Task 4: Payload stickiness in MappingRepositoryImpl.allocateOrReuse

**Files:**
- Modify: `app/src/main/java/io/github/lycheeappf/tmm/data/repository/MappingRepositoryImpl.kt:46-82`
- Test: `app/src/test/java/io/github/lycheeappf/tmm/data/repository/MappingRepositoryImplTest.kt` (extend)

**Interfaces:**
- Consumes: `ChannelPayload.isReplyable` (`domain/channel/ChannelPayload.kt:18` — for `Notification` it is `remoteInputResultKey != null`), `PayloadJson.decodeOrFallback`.
- Produces: on reuse, a replyable persisted payload is never overwritten by a non-replyable one. Defense in depth for Task 1 (the CX7 comment proves action-less re-posts exist beyond summaries).

- [ ] **Step 1: Write the failing tests** (append to `MappingRepositoryImplTest.kt`)

```kotlin
    @Test
    fun `allocateOrReuse keeps replyable payload when new capture is action-less`() = runTest {
        val now = System.currentTimeMillis()
        val replyableJson = PayloadJson.encode(testPayload) // remoteInputResultKey = "input"
        val existing = MappingEntity(
            mappingId = 7L,
            channel = ChannelId.NOTIFICATION.code,
            fakeAddress = "+88800000007",
            conversationKey = "com.whatsapp::anna",
            payloadJson = replyableJson,
            createdAt = now - 60_000,
            expiresAt = now + 60_000,
            lastUsedAt = null,
            replyCount = 0,
            replyable = true
        )
        coEvery {
            dao.findByConversationKey(ChannelId.NOTIFICATION.code, "com.whatsapp::anna")
        } returns existing
        val writtenJson = slot<String>()
        coEvery { dao.refreshOnReuse(any(), any(), payloadJson = capture(writtenJson), any(), any(), any()) } just Runs

        val summaryPayload = ChannelPayload.Notification(
            sourcePackage = "com.whatsapp",
            notificationKey = "0|com.whatsapp|1|null|10467",
            remoteInputResultKey = null,
            conversationLabel = "WhatsApp",
            senderDisplayName = "WhatsApp"
        )
        val mapping = repository.allocateOrReuse(
            channel = ChannelId.NOTIFICATION,
            conversationKey = "com.whatsapp::anna",
            payload = summaryPayload,
            ttlMillis = 24L * 60 * 60 * 1000
        )

        assertThat(writtenJson.captured).isEqualTo(replyableJson)
        val kept = mapping.payload as ChannelPayload.Notification
        assertThat(kept.notificationKey).isEqualTo("key-1")
        assertThat(kept.remoteInputResultKey).isEqualTo("input")
    }

    @Test
    fun `allocateOrReuse overwrites payload when new capture is replyable`() = runTest {
        val now = System.currentTimeMillis()
        val existing = MappingEntity(
            mappingId = 7L,
            channel = ChannelId.NOTIFICATION.code,
            fakeAddress = "+88800000007",
            conversationKey = "com.whatsapp::anna",
            payloadJson = PayloadJson.encode(testPayload),
            createdAt = now - 60_000,
            expiresAt = now + 60_000,
            lastUsedAt = null,
            replyCount = 0,
            replyable = true
        )
        coEvery {
            dao.findByConversationKey(ChannelId.NOTIFICATION.code, "com.whatsapp::anna")
        } returns existing
        val writtenJson = slot<String>()
        coEvery { dao.refreshOnReuse(any(), any(), payloadJson = capture(writtenJson), any(), any(), any()) } just Runs

        val freshPayload = testPayload.copy(notificationKey = "key-2")
        val mapping = repository.allocateOrReuse(
            channel = ChannelId.NOTIFICATION,
            conversationKey = "com.whatsapp::anna",
            payload = freshPayload,
            ttlMillis = 24L * 60 * 60 * 1000
        )

        assertThat(writtenJson.captured).isEqualTo(PayloadJson.encode(freshPayload))
        assertThat((mapping.payload as ChannelPayload.Notification).notificationKey).isEqualTo("key-2")
    }

    @Test
    fun `allocateOrReuse overwrites action-less payload with action-less payload`() = runTest {
        // Kein Sticky-Fall: bestehendes Payload ist selbst nicht replyable →
        // neuestes Update gewinnt (frischerer notificationKey hilft dem Rebuilder).
        val now = System.currentTimeMillis()
        val actionless = testPayload.copy(remoteInputResultKey = null)
        val existing = MappingEntity(
            mappingId = 7L,
            channel = ChannelId.NOTIFICATION.code,
            fakeAddress = "+88800000007",
            conversationKey = "com.whatsapp::anna",
            payloadJson = PayloadJson.encode(actionless),
            createdAt = now - 60_000,
            expiresAt = now + 60_000,
            lastUsedAt = null,
            replyCount = 0,
            replyable = false
        )
        coEvery {
            dao.findByConversationKey(ChannelId.NOTIFICATION.code, "com.whatsapp::anna")
        } returns existing
        val writtenJson = slot<String>()
        coEvery { dao.refreshOnReuse(any(), any(), payloadJson = capture(writtenJson), any(), any(), any()) } just Runs

        val newer = actionless.copy(notificationKey = "key-3")
        repository.allocateOrReuse(
            channel = ChannelId.NOTIFICATION,
            conversationKey = "com.whatsapp::anna",
            payload = newer,
            ttlMillis = 24L * 60 * 60 * 1000
        )

        assertThat(writtenJson.captured).isEqualTo(PayloadJson.encode(newer))
    }
```

Note: if `dao.refreshOnReuse(...)` named-arg capture doesn't match the DAO signature order, capture positionally — check `MappingDao.refreshOnReuse` first.

- [ ] **Step 2: Run to verify the sticky test fails**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.data.repository.MappingRepositoryImplTest"`
Expected: `keeps replyable payload…` FAILS; the two overwrite tests PASS (current behavior).

- [ ] **Step 3: Implement stickiness**

In `allocateOrReuse`, replace `val newPayloadJson = PayloadJson.encode(payload)` (line ~52) with:

```kotlin
            // Payload-Stickiness (Erweiterung von CX7 aufs Payload selbst): ein
            // action-loses Update (Group-Summary, "delivered"-Re-Post) darf ein
            // replyfähiges Payload nicht überschreiben — sonst zeigt
            // notificationKey auf eine Notification ohne RemoteInput und der
            // nächste Reply endet in NO_ACTION (Cache-Miss + Rebuild-Miss).
            val existingPayload = PayloadJson.decodeOrFallback(existing.payloadJson)
            val newPayloadJson = if (existingPayload.isReplyable && !payload.isReplyable) {
                existing.payloadJson
            } else {
                PayloadJson.encode(payload)
            }
```

(`newPayloadJson` continues to flow into `dao.refreshOnReuse` and the returned `copy` unchanged.)

- [ ] **Step 4: Run tests — all pass**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.data.repository.MappingRepositoryImplTest"`
Expected: ALL PASS (including pre-existing tests — `testPayload` is replyable, so existing reuse tests still overwrite).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/lycheeappf/tmm/data/repository/MappingRepositoryImpl.kt app/src/test/java/io/github/lycheeappf/tmm/data/repository/MappingRepositoryImplTest.kt
git commit -m "fix(mapping): keep replyable payload on action-less reuse"
```

---

### Task 5: Tappable URLs in SMS thread bubbles

**Files:**
- Create: `app/src/main/java/io/github/lycheeappf/tmm/ui/screen/sms/SmsBodyLinkifier.kt`
- Modify: `app/src/main/java/io/github/lycheeappf/tmm/ui/screen/sms/SmsThreadScreen.kt:205-209` (MessageBubble body Text)
- Create: `app/src/test/java/io/github/lycheeappf/tmm/ui/screen/sms/SmsBodyLinkifierTest.kt`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: `fun linkifySmsBody(body: String, linkStyle: SpanStyle): AnnotatedString` (top-level function, package `io.github.lycheeappf.tmm.ui.screen.sms`).

**Background:** No link handling exists anywhere in the UI; `MessageBubble` renders plain `Text(message.body)`. Compose `Text` makes `LinkAnnotation.Url` ranges tappable via the default `LocalUriHandler`; this coexists with the bubble's `combinedClickable` long-press-to-copy (link taps take precedence inside the text).

- [ ] **Step 1: Write the failing linkifier test** (Robolectric — `android.util.Patterns` needs it)

```kotlin
package io.github.lycheeappf.tmm.ui.screen.sms

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SmsBodyLinkifierTest {

    private val style = SpanStyle()

    private fun urls(body: String): List<String> {
        val annotated = linkifySmsBody(body, style)
        return annotated.getLinkAnnotations(0, annotated.length)
            .map { (it.item as LinkAnnotation.Url).url }
    }

    @Test
    fun `plain text has no link annotations`() {
        assertThat(urls("nur Text ohne Link")).isEmpty()
    }

    @Test
    fun `https url is annotated as-is`() {
        assertThat(urls("schau mal https://example.com/pfad an"))
            .containsExactly("https://example.com/pfad")
    }

    @Test
    fun `scheme-less url gets https prefix`() {
        assertThat(urls("besuch example.com bitte"))
            .containsExactly("https://example.com")
    }

    @Test
    fun `multiple urls are all annotated`() {
        assertThat(urls("https://a.example.com und http://b.example.com"))
            .containsExactly("https://a.example.com", "http://b.example.com")
    }

    @Test
    fun `annotation range covers exactly the url text`() {
        val body = "vorne https://example.com hinten"
        val annotated = linkifySmsBody(body, style)
        val range = annotated.getLinkAnnotations(0, annotated.length).single()
        assertThat(body.substring(range.start, range.end)).isEqualTo("https://example.com")
        assertThat(annotated.text).isEqualTo(body)
    }
}
```

- [ ] **Step 2: Run to verify compile failure** (`linkifySmsBody` missing)

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.ui.screen.sms.SmsBodyLinkifierTest"`

- [ ] **Step 3: Implement the linkifier**

```kotlin
package io.github.lycheeappf.tmm.ui.screen.sms

import android.util.Patterns
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString

/**
 * Wandelt einen SMS-Body in einen AnnotatedString, in dem erkannte Web-URLs
 * als [LinkAnnotation.Url] markiert sind — Compose `Text` macht sie damit
 * über den Default-[androidx.compose.ui.platform.LocalUriHandler] tappbar.
 * Scheme-lose Treffer ("example.com") bekommen https:// vorangestellt, sonst
 * kann ACTION_VIEW sie nicht öffnen. Kein Composable: pur & testbar.
 */
fun linkifySmsBody(body: String, linkStyle: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(body)
    val matcher = Patterns.WEB_URL.matcher(body)
    while (matcher.find()) {
        val start = matcher.start()
        val end = matcher.end()
        val raw = body.substring(start, end)
        val url = if (raw.contains("://")) raw else "https://$raw"
        addLink(
            LinkAnnotation.Url(url = url, styles = TextLinkStyles(style = linkStyle)),
            start,
            end
        )
    }
}
```

- [ ] **Step 4: Run linkifier test — passes**

- [ ] **Step 5: Use it in MessageBubble**

In `SmsThreadScreen.kt`, `MessageBubble`, replace

```kotlin
                Text(
                    message.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = onContainer
                )
```

with

```kotlin
                // Links im Body tappbar machen (LinkAnnotation.Url → Default-UriHandler).
                // Unterstreichung statt eigener Farbe: bleibt auf allen drei
                // Bubble-Containern (surfaceVariant/primaryContainer/errorContainer) lesbar.
                val linkStyle = SpanStyle(textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Bold)
                val body = remember(message.body) { linkifySmsBody(message.body, linkStyle) }
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = onContainer
                )
```

Add imports: `androidx.compose.runtime.remember`, `androidx.compose.ui.text.SpanStyle`, `androidx.compose.ui.text.style.TextDecoration`, `androidx.compose.ui.text.font.FontWeight` (check which already exist).

Note: `linkStyle` is a constant — hoist it outside `remember`'s key or declare it as a top-level `private val` in the file; do NOT recompute the AnnotatedString on every recomposition (hence `remember(message.body)`).

- [ ] **Step 6: Compile + full sms-package tests**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.ui.screen.sms.*"`
Expected: PASS. Also run `gradlew.bat :app:assembleDebug` once to catch Compose compile errors.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/github/lycheeappf/tmm/ui/screen/sms/ app/src/test/java/io/github/lycheeappf/tmm/ui/screen/sms/
git commit -m "feat(ui): make URLs tappable in SMS thread bubbles"
```

---

### Task 6: Quick-reply fallback opens own compose screen (not Google Messages)

**Files:**
- Modify: `app/src/main/java/io/github/lycheeappf/tmm/sms/default_app/HeadlessSmsSendService.kt:68-79` (+ KDoc + strings)
- Modify: `app/src/main/res/values/strings.xml:100-102`, `app/src/main/res/values-de/strings.xml:98-100`
- Create: `app/src/test/java/io/github/lycheeappf/tmm/sms/default_app/HeadlessSmsSendServiceTest.kt`

**Interfaces:**
- Consumes: `MainActivity.composeIntent(context: Context, recipient: String?, body: String?): Intent` (`MainActivity.kt:116-120`) — opens TMM's own compose UI prefilled.
- Produces: nothing other tasks rely on.

**Background:** The quick-reply-failed notification's tap intent targets Google Messages via `setPackage` — the same defect pattern as the (already fixed) `DeliverSmsReceiver`. Since TMM holds `ROLE_SMS`, Google Messages only shows its set-as-default gate. TMM has its own compose surface and `RealSmsSender`, so tap should open TMM's composer prefilled with recipient + text.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.lycheeappf.tmm.sms.default_app

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.MainActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HeadlessSmsSendServiceTest {

    @Test
    fun `quick-reply-failed notification opens own compose screen prefilled`() {
        val controller = Robolectric.buildService(
            HeadlessSmsSendService::class.java,
            Intent("android.intent.action.RESPOND_VIA_MESSAGE", Uri.parse("smsto:+491711234567"))
                .putExtra(Intent.EXTRA_TEXT, "Bin gleich da")
        )
        val service = controller.create().get()
        controller.startCommand(0, 1)

        val nm = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSystemService(NotificationManager::class.java)
        val posted = shadowOf(nm).allNotifications.single()

        val contentIntent: PendingIntent = posted.contentIntent
        val saved = shadowOf(contentIntent).savedIntent
        assertThat(saved.component?.className).isEqualTo(MainActivity::class.java.name)
        assertThat(saved.getStringExtra("compose_recipient")).isEqualTo("+491711234567")
        assertThat(saved.getStringExtra("compose_body")).isEqualTo("Bin gleich da")
    }
}
```

Note: `Robolectric.buildService(...).create()` + `startCommand` drives `onStartCommand` with the intent given to `buildService`. POST_NOTIFICATIONS: Robolectric grants by default on sdk 33 shadows — if the permission check blocks, grant it via `shadowOf(application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)`.

- [ ] **Step 2: Run to verify it fails** (component is null — intent targets Google Messages package, not our component)

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.sms.default_app.HeadlessSmsSendServiceTest"`

- [ ] **Step 3: Implement**

In `HeadlessSmsSendService.postQuickReplyFailedNotification`, replace the `openIntent` block (lines 68-73):

```kotlin
        // Tap öffnet unseren eigenen Compose-Screen (vorbefüllt) — NICHT Google
        // Messages: solange TMM ROLE_SMS hält, zeigt Google Messages als
        // Nicht-Default nur sein "Standard-App festlegen"-Gate (Issue-Report C).
        val openIntent = MainActivity.composeIntent(this, recipient, text)
```

Remove the now-unused `GOOGLE_MESSAGES_PKG` constant and `Uri` import if unused. Update the class KDoc line "damit der User die SMS manuell senden kann" stays valid; remove any Google-Messages mention. Add import `io.github.lycheeappf.tmm.MainActivity`.

Update strings (EN `values/strings.xml`):

```xml
    <string name="quickreply_failed_text_to">Wanted to send to %1$s — tap to review and send</string>
    <string name="quickreply_failed_text_generic">Tap to review and send the SMS</string>
    <string name="quickreply_failed_big">The system tried to send a quick-reply SMS via this app (e.g. from the call UI). Tap to review the text and send it yourself.\n\n%1$s</string>
```

DE (`values-de/strings.xml`):

```xml
    <string name="quickreply_failed_text_to">Wollte an %1$s senden — tap zum Prüfen und Senden</string>
    <string name="quickreply_failed_text_generic">Tap, um die SMS zu prüfen und zu senden</string>
    <string name="quickreply_failed_big">Das System hat über diese App eine Quick-Reply-SMS senden wollen (z.B. aus der Anruf-UI). Tap, um den Text zu prüfen und selbst zu senden.\n\n%1$s</string>
```

(`quickreply_failed_title` unchanged in both locales.)

- [ ] **Step 4: Run test — passes**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.sms.default_app.HeadlessSmsSendServiceTest"`
Expected: PASS. Also run the whole package: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.sms.default_app.*"`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/lycheeappf/tmm/sms/default_app/HeadlessSmsSendService.kt app/src/main/res/values/strings.xml app/src/main/res/values-de/strings.xml app/src/test/java/io/github/lycheeappf/tmm/sms/default_app/HeadlessSmsSendServiceTest.kt
git commit -m "fix(sms): quick-reply fallback opens own compose screen instead of Google Messages"
```

---

### Task 7: Observer log hygiene + honest rebuilder logging

**Files:**
- Modify: `app/src/main/java/io/github/lycheeappf/tmm/sms/outbound/OutboundSmsObserver.kt` (processRow ordering + NotOurs state tracking; `processChanges` visibility `private` → `internal` for the test)
- Modify: `app/src/main/java/io/github/lycheeappf/tmm/channel/notification/PendingIntentRebuilder.kt:56-57`
- Create: `app/src/test/java/io/github/lycheeappf/tmm/sms/outbound/OutboundSmsObserverTest.kt`
- Modify: `app/src/test/java/io/github/lycheeappf/tmm/channel/notification/PendingIntentRebuilderTest.kt` (extend)

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: nothing other tasks rely on.

**Background:** (1) `NotOurs` rows never enter `dispatchedRowStates`, and the top-of-`processRow` "Outbox-Row #…" diagnostic logs on EVERY pass — two stale FAILED real-SMS rows produced 396 duplicate log lines in the July-2 diagnostics export. (2) `PIRebuilder` logs "found-action" BEFORE `findReplyAction` runs — it misled triage twice during the issue analysis.

- [ ] **Step 1: Write the failing observer test**

Robolectric (needs `MatrixCursor`/`HandlerThread`); mock the `ContentResolver` boundary via a mocked `Context`.

```kotlin
package io.github.lycheeappf.tmm.sms.outbound

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import io.github.lycheeappf.tmm.channel.llm.InjectedMessageLedger
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.data.repository.ReplyHistoryRecorder
import io.github.lycheeappf.tmm.data.store.SettingsStore
import io.github.lycheeappf.tmm.domain.routing.ReplyDispatcher
import io.github.lycheeappf.tmm.sms.send.SelfSendLedger
import io.github.lycheeappf.tmm.ui.screen.onboarding.PreFlightCoordinator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OutboundSmsObserverTest {

    private val resolver = mockk<ContentResolver>()
    private val context = mockk<Context>(relaxed = true) {
        every { contentResolver } returns resolver
    }
    private val classifier = mockk<OutboundSmsClassifier>()
    private val dispatcher = mockk<ReplyDispatcher>(relaxed = true)
    private val failedRowCleaner = mockk<FailedRowCleaner>(relaxed = true)
    private val settingsStore = mockk<SettingsStore>(relaxed = true)
    private val replyHistory = mockk<ReplyHistoryRecorder>(relaxed = true)
    private val preFlight = mockk<PreFlightCoordinator>()
    private val logBuffer = mockk<LogBuffer>(relaxed = true)
    private val injectedLedger = mockk<InjectedMessageLedger>()
    private val selfSendLedger = mockk<SelfSendLedger>()

    private fun failedRealSmsCursor(): MatrixCursor =
        MatrixCursor(arrayOf("_id", "address", "body", "type", "date")).apply {
            addRow(arrayOf(9460L, "+491711234567", "echte SMS", 5, 1_000L))
        }

    @Test
    fun `NotOurs row is logged once, not on every pass`() = runTest {
        coEvery { settingsStore.lastSeenOutboxId() } returns 9450L
        every { resolver.query(any(), any(), any(), any(), any()) } answers { failedRealSmsCursor() }
        every { preFlight.isReservedForPreflight(any()) } returns false
        every { injectedLedger.shouldIgnoreOutbound(any(), any()) } returns false
        every { selfSendLedger.isSelfSend(any<Long>()) } returns false
        every { selfSendLedger.isSelfSend(any<String>(), any<String>()) } returns false
        coEvery { classifier.classify(any()) } returns OutboundSmsClassifier.Classification.NotOurs

        val observer = OutboundSmsObserver(
            context, classifier, dispatcher, failedRowCleaner, settingsStore,
            replyHistory, preFlight, logBuffer, injectedLedger, selfSendLedger,
            StandardTestDispatcher(testScheduler)
        )

        observer.processChanges()
        observer.processChanges() // zweiter ContentObserver-Tick, gleiche Row, gleicher Type

        verify(exactly = 1) { logBuffer.info(any(), match { it.startsWith("Outbox-Row #9460") }) }
        verify(exactly = 1) { logBuffer.info(any(), match { it.contains("NotOurs") }) }
    }
}
```

Notes: `classifier.classify` may be non-suspend — use `every` instead of `coEvery` accordingly (check the signature). If `OutboundSmsClassifier.Classification.NotOurs` is an `object`, reference it directly; if it has parameters, construct accordingly (check the class). `observer.processChanges()` requires Step 3's visibility change — until then the test doesn't compile, which is the expected initial failure.

- [ ] **Step 2: Run to verify failure**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.sms.outbound.OutboundSmsObserverTest"`
Expected: compile error (`processChanges` private). After making it `internal` (part of Step 3) the assertion `exactly = 1` fails with 2 log calls.

- [ ] **Step 3: Implement observer fix**

In `OutboundSmsObserver`:
1. Change `private suspend fun processChanges()` to `internal suspend fun processChanges()` (test seam, mirrors `internal fun MessageBubble`).
2. In `processRow`, MOVE the leading "Outbox-Row #…" `logBuffer.info` block (lines ~180-187) to AFTER the `if (previousType == row.type) return` check (line ~218) — and move the `val previousType = dispatchedRowStates[row.id]` + same-type early-return up so the order becomes: preflight-skip → echo-skip → self-send-skip → previousType same-type return → "Outbox-Row" log → classify. Update the comment:

```kotlin
        val previousType = dispatchedRowStates[row.id]
        // Identische Row (gleiche rowId, gleicher TYPE) → schon verarbeitet.
        // MUSS vor dem Diagnose-Log stehen: der 50-Row-Lookback liest alte Rows
        // bei JEDEM content://sms-Change erneut — ohne den Early-Return spammen
        // stale FAILED-Rows das exportierte Log (396 Duplikate im Juli-Export).
        if (previousType == row.type) return

        // DIAGNOSE: erste Sichtung (oder Status-Wechsel) einer Row protokollieren,
        // damit der User in Settings > Diagnostics nachvollziehen kann, was Tesla
        // beim Reply in den Provider schreibt. Body bleibt draußen (Privacy).
        logBuffer.info(
            TAG,
            "Outbox-Row #${row.id} type=${row.type} addr='${addrForLog(row.address)}' body=${row.body.length}ch"
        )
```

3. In the `NotOurs` branch add state tracking before the return:

```kotlin
        if (cls is OutboundSmsClassifier.Classification.NotOurs) {
            // user-initiated SMS via Google Messages → nicht anfassen. Aber im
            // State-Tracking vermerken, sonst wird die Row bei jedem Lookback-Pass
            // erneut klassifiziert und geloggt.
            logBuffer.info(TAG, "Row ${row.id} ('${addrForLog(row.address)}') → NotOurs, kein Dispatch")
            dispatchedRowStates[row.id] = row.type
            return
        }
```

- [ ] **Step 4: Run observer test — passes**

- [ ] **Step 5: Extend PendingIntentRebuilderTest (failing)**

Check the existing test file's setup (it fakes `NotificationForwardingService.instance`). Add:

```kotlin
    @Test
    fun `logs active-but-no-action when matched notification has no reply action`() {
        // arrange: active notification key match, resolver returns null
        every { resolver.findReplyAction(any()) } returns null

        val result = rebuilder.rebuild(payload)

        assertThat(result).isNull()
        verify { logBuffer.warn(any(), match { it.contains("active-but-no-action") }) }
        verify(exactly = 0) { logBuffer.info(any(), match { it.contains("found-action") }) }
    }
```

- [ ] **Step 6: Implement honest rebuilder log**

Replace `PendingIntentRebuilder.kt:56-57`:

```kotlin
        // Erst NACH findReplyAction loggen — die alte "found-action"-Zeile feuerte
        // vor dem Check und hat bei der Issue-Triage zweimal in die Irre geführt
        // (Notification aktiv ≠ Reply-Action vorhanden, s. Group-Summary).
        val action = resolver.findReplyAction(match.notification)
        if (action == null) {
            logBuffer.warn(TAG, "rebuild notif=${payload.notificationKey} active-but-no-action")
        } else {
            logBuffer.info(TAG, "rebuild notif=${payload.notificationKey} action-rebuilt")
        }
        return action
```

- [ ] **Step 7: Run both test classes**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.sms.outbound.OutboundSmsObserverTest" --tests "io.github.lycheeappf.tmm.channel.notification.PendingIntentRebuilderTest"`
Expected: ALL PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/io/github/lycheeappf/tmm/sms/outbound/OutboundSmsObserver.kt app/src/main/java/io/github/lycheeappf/tmm/channel/notification/PendingIntentRebuilder.kt app/src/test/java/io/github/lycheeappf/tmm/sms/outbound/OutboundSmsObserverTest.kt app/src/test/java/io/github/lycheeappf/tmm/channel/notification/PendingIntentRebuilderTest.kt
git commit -m "fix(outbound): stop re-logging NotOurs rows; honest rebuilder action log"
```

---

### Task 8: Full test suite + version bump to 1.0.0-rc1

**Files:**
- Modify: `app/build.gradle.kts:41-42`
- Modify: `CHANGELOG.md` (new top entry)

**Interfaces:** none — final task, runs after all review fixes are in.

- [ ] **Step 1: Run the full JVM test suite**

Run: `gradlew.bat :app:test`
Expected: BUILD SUCCESSFUL, no failing tests (debug + release unit tests).

- [ ] **Step 2: Build the debug APK**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Bump version**

In `app/build.gradle.kts` replace:

```kotlin
        versionCode = 10
        versionName = "0.8.1"
```

with:

```kotlin
        versionCode = 11
        versionName = "1.0.0-rc1"
```

- [ ] **Step 4: Add CHANGELOG entry**

`CHANGELOG.md` follows Keep a Changelog, newest entry on top (directly under the intro paragraph, above the current top entry). Insert:

```markdown
## [1.0.0-rc1] — 2026-07-10

Release candidate for 1.0. Fixes the field-reported WhatsApp bridge failures
(root-cause analysis in `issue-reports/analyse-report-2026-07-10.md`).

### Fixed
- **1:1 replies no longer fail with "Reply not delivered".** WhatsApp's group-summary
  notification (which has no reply action) was captured like a message and overwrote the
  mapping's notification pointer; it is now filtered out, and an action-less update can
  no longer overwrite a replyable mapping payload.
- **Your own replies are no longer read back as new messages.** The messenger's
  notification re-post containing your just-sent reply is recognized (self-authored
  message skip + short-lived sent-reply ledger) and dropped instead of injected.
- **The quick-reply fallback notification now opens TMM's own compose screen** prefilled
  with recipient and text, instead of Google Messages (which only showed its
  "set default SMS app" prompt while TMM holds the SMS role).
- **Diagnostics log spam:** stale real-SMS rows are no longer re-logged on every
  SMS-provider change, and the reply-rebuilder log no longer claims "found-action"
  before actually checking for a reply action.

### Added
- **Links in SMS messages are now tappable** in the app's conversation view.
```

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts CHANGELOG.md
git commit -m "chore(release): bump version to 1.0.0-rc1"
```
