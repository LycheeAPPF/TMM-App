# Persistenter Diagnose-Log + redigierter, teilbarer Export — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ein Tester kann mit **einem Tap** eine **einzige, redigierte** Diagnose-Datei teilen, aus der sich Reply-Probleme (z.B. „Reply nicht zugestellt") allein diagnostizieren lassen.

**Architecture:** Der bestehende In-Memory-`LogBuffer` bekommt einen rollierenden On-Disk-Sink (`LogFileStore`), sodass der Log Neustarts überlebt. Die Reply-Pipeline (bisher nur in logcat) loggt ihre Entscheidungs-Branches metadaten-only in den exportierbaren Buffer. Der `DiagnosticsExporter` redigiert PII (Kontaktnamen maskiert, `conversationKey` gehasht, Antworttext → Länge), ergänzt Build-Infos und liest den vollen Log aus der Datei. Ein neuer `FileProvider` + ein „Diagnose senden"-Button (in den normalen Einstellungen, **ohne** Developer-Mode) öffnen das Android-Share-Sheet mit der einen JSON-Datei.

**Tech Stack:** Kotlin, Hilt (`@Provides`/`@Inject`, `SingletonComponent`), Coroutines (`Channel`, `CoroutineScope`, `@IoDispatcher`), kotlinx.serialization JSON, Room (nur Lesen — **kein** Schema-Change), Jetpack Compose (MVVM), JUnit4 + Truth + MockK + kotlinx-coroutines-test + Robolectric.

## Global Constraints

Jeder Task erbt diese projektweiten Regeln (Werte wörtlich aus Spec/CLAUDE.md/Memory):

- **Privacy:** Niemals Nachrichten-Bodies, Diktate, Prompts oder **Kontaktnamen** in `LogBuffer` schreiben (er wird via `DiagnosticsExporter` exportiert). Nur Metadaten/Längen/IDs. Fake-`+888…`-Adressen sind ok; echte Nummern nur via `addrForLog` maskiert.
- **Coroutines:** `CancellationException` immer rethrowen — `io.github.lycheeappf.tmm.core.util.coRunCatching { … }` benutzen, nie blankes `runCatching` um suspend-Calls.
- **Dispatcher:** immer die Qualifier `@IoDispatcher`/`@DefaultDispatcher`/`@MainDispatcher` injizieren, nie `Dispatchers.IO` direkt im Produktivcode.
- **i18n (Pflicht):** jeder neue User-sichtbare String in **beiden** Dateien — `res/values/strings_*.xml` (EN, Default) **und** `res/values-de/strings_*.xml` (DE).
- **Room:** v1, keine Migration. Dieses Feature fügt **keine** Tabelle/Spalte hinzu (Logs sind datei-basiert). Falls ein Schritt eine Room-Änderung nahelegt → STOP, Plan ist falsch.
- **Build:** Wrapper `gradlew.bat` (nie System-Gradle). JDK 17, minSdk 33, `kotlin.code.style=official`, KSP (nicht kapt).
- **Lint:** `gradlew.bat :app:lint` crasht (bekannter Tooling-Bug, nicht App-Code) → **nicht** im Plan ausführen; i18n-Parität manuell prüfen (gleiche `name=`-Keys in `values/` und `values-de/`).
- **Commits:** Nur committen, wenn der Maintainer es verlangt. Commit-Messages tragen **keine** KI-Provenance (kein `Co-Authored-By: Claude`). Branch = aktueller Branch `nightly` (kein Feature-Branch nötig, da `nightly` ≠ Default `main`).

---

## File Structure

| Datei | Verantwortung | Task |
|---|---|---|
| `core/util/LogFileStore.kt` | **neu** — rollierende On-Disk-Persistenz für Log-Events (`append`/`readTail`/`clear`) | 1 |
| `core/di/AppModule.kt` | **modify** — `@Provides @Singleton LogFileStore` (Verzeichnis + `@IoDispatcher`) | 1 |
| `core/util/LogBuffer.kt` | **modify** — `LogFileStore`-Sink + Tail-Load beim Start | 2 |
| `channel/notification/NotificationReplyExecutor.kt` | **modify** — pro Reply-Branch eine `LogBuffer`-Zeile | 3 |
| `channel/notification/PendingIntentRebuilder.kt` | **modify** — NLS-/Aktiv-Status in `LogBuffer` | 4 |
| `channel/notification/NotificationCapture.kt` | **modify** — PII-Fix (Kontaktname raus aus Log-Zeile) | 4 |
| `core/util/Redaction.kt` | **neu** — zentrale Maskier-/Hash-Helfer (testbar) | 5 |
| `core/util/DiagnosticsExporter.kt` | **modify** — Build-Header + Redaktion + Logs aus `LogFileStore` | 6 |
| `AndroidManifest.xml` | **modify** — `FileProvider` | 7 |
| `res/xml/file_paths.xml` | **neu** — `cache-path` für den Export | 7 |
| `ui/screen/diagnostics/DiagnosticsShare.kt` | **neu** — Authority-/Chooser-Intent-Helfer | 7 |
| `ui/screen/diagnostics/DiagnosticsEvent.kt` | **neu** — One-Shot-Event-Typ (von beiden ViewModels genutzt) | 8 |
| `ui/screen/diagnostics/DiagnosticsViewModel.kt` | **modify** — `shareDiagnostics()` + Event statt `exportDiagnostics()` | 8 |
| `ui/screen/diagnostics/DiagnosticsScreen.kt` | **modify** — Toolbar-Button → Teilen, Event-Collector | 8 |
| `ui/screen/settings/SettingsViewModel.kt` | **modify** — `shareDiagnostics()` + Event | 9 |
| `ui/screen/settings/SettingsScreen.kt` | **modify** — „Diagnose senden"-Card (ohne Dev-Mode) | 9 |
| `res/values/strings_diagnostics.xml`, `res/values-de/strings_diagnostics.xml` | **modify** — Share-Strings (EN+DE) | 8 |
| `res/values/strings_settings.xml`, `res/values-de/strings_settings.xml` | **modify** — Support-/Senden-Strings (EN+DE) | 9 |
| `CHANGELOG.md`, `app/build.gradle.kts` | **modify** — Changelog-Eintrag + Version-Bump | 10 |
| Tests unter `app/src/test/.../core/util`, `.../channel/notification`, `.../ui/screen/*` | neu/erweitert | je Task |

---

## Task 1: `LogFileStore` (rollierende On-Disk-Persistenz)

**Files:**
- Create: `app/src/main/java/io/github/lycheeappf/tmm/core/util/LogFileStore.kt`
- Modify: `app/src/main/java/io/github/lycheeappf/tmm/core/di/AppModule.kt`
- Test: `app/src/test/java/io/github/lycheeappf/tmm/core/util/LogFileStoreTest.kt`

**Interfaces:**
- Consumes: `LogBuffer.LogEntry(timestamp: Long, level: LogBuffer.Level, tag: String, message: String)` und `LogBuffer.Level { Info, Warn, Error }` (existieren bereits in `LogBuffer.kt`).
- Produces:
  - `class LogFileStore(dir: File, ioDispatcher: CoroutineDispatcher, rotateThresholdBytes: Long = …)`
  - `fun append(entry: LogBuffer.LogEntry)` — non-blocking (Hot-Path)
  - `fun readTail(max: Int): List<LogBuffer.LogEntry>` — neueste zuerst
  - `fun clear()`
  - `fun close()` — stoppt den Consumer (nur Tests/Shutdown)
  - `internal fun writeEntry(entry: LogBuffer.LogEntry)` — synchroner Write (vom Consumer + Tests)
  - Companion: `const val CURRENT = "tmm-log.current"`, `const val PREV = "tmm-log.prev"`

- [ ] **Step 1: Failing test schreiben** — `app/src/test/java/io/github/lycheeappf/tmm/core/util/LogFileStoreTest.kt`

```kotlin
package io.github.lycheeappf.tmm.core.util

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LogFileStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store(thresholdBytes: Long = 1_000_000) =
        LogFileStore(File(tmp.root, "diagnostics"), UnconfinedTestDispatcher(), thresholdBytes)

    private fun entry(
        msg: String,
        ts: Long = 1L,
        tag: String = "T",
        level: LogBuffer.Level = LogBuffer.Level.Info
    ) = LogBuffer.LogEntry(ts, level, tag, msg)

    @Test fun `writeEntry then readTail returns entry`() {
        val s = store()
        s.writeEntry(entry("hello", ts = 5L))
        val tail = s.readTail(10)
        assertThat(tail).hasSize(1)
        assertThat(tail.first().message).isEqualTo("hello")
        assertThat(tail.first().timestamp).isEqualTo(5L)
    }

    @Test fun `readTail is newest-first and respects max`() {
        val s = store()
        s.writeEntry(entry("a", ts = 1L))
        s.writeEntry(entry("b", ts = 2L))
        s.writeEntry(entry("c", ts = 3L))
        assertThat(s.readTail(2).map { it.message }).containsExactly("c", "b").inOrder()
    }

    @Test fun `rotation moves current to prev and readTail spans both`() {
        val s = store(thresholdBytes = 30) // kleine Schwelle erzwingt Rotation
        repeat(10) { s.writeEntry(entry("m$it", ts = it.toLong())) }
        assertThat(File(tmp.root, "diagnostics/${LogFileStore.PREV}").exists()).isTrue()
        val tail = s.readTail(100)
        assertThat(tail.first().message).isEqualTo("m9")
        assertThat(tail.map { it.message }).contains("m0")
    }

    @Test fun `sanitize collapses tabs and newlines so one entry is one line`() {
        val s = store()
        s.writeEntry(entry("line1\nline2\tcol", ts = 1L))
        val tail = s.readTail(10)
        assertThat(tail).hasSize(1)
        assertThat(tail.first().message).isEqualTo("line1 line2 col")
    }

    @Test fun `unparseable lines are skipped`() {
        val dir = File(tmp.root, "diagnostics").apply { mkdirs() }
        File(dir, LogFileStore.CURRENT).writeText("garbage-without-tabs\n5\tInfo\tT\tgood\n")
        assertThat(store().readTail(10).map { it.message }).containsExactly("good")
    }

    @Test fun `clear deletes both files`() {
        val s = store(thresholdBytes = 30)
        repeat(10) { s.writeEntry(entry("m$it")) }
        s.clear()
        assertThat(s.readTail(100)).isEmpty()
    }

    @Test fun `append persists via consumer coroutine`() = runTest {
        val s = LogFileStore(File(tmp.root, "diagnostics"), StandardTestDispatcher(testScheduler))
        s.append(entry("async", ts = 7L))
        advanceUntilIdle()
        assertThat(s.readTail(10).map { it.message }).contains("async")
        s.close()
    }
}
```

- [ ] **Step 2: Test laufen lassen, FAIL bestätigen**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.core.util.LogFileStoreTest"`
Expected: FAIL — Kompilierfehler „unresolved reference: LogFileStore".

- [ ] **Step 3: `LogFileStore` implementieren** — `app/src/main/java/io/github/lycheeappf/tmm/core/util/LogFileStore.kt`

```kotlin
package io.github.lycheeappf.tmm.core.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Rollierende On-Disk-Persistenz für [LogBuffer]-Events. Hält den Log über
 * Prozess-Neustarts/lange Fahrten hinweg, sodass der Diagnose-Export mehr als die
 * 500 In-Memory-Zeilen enthält.
 *
 * - Zwei Dateien: [CURRENT] (aktiv) + [PREV] (vorige Rotation). Rotation, sobald
 *   CURRENT [rotateThresholdBytes] erreicht → PREV löschen, CURRENT → PREV, neue
 *   CURRENT. Gesamt-Cap ≈ 2 × Schwelle.
 * - [append] ist non-blocking (Hot-Path): Eintrag in einen gepufferten Channel,
 *   ein einzelner Consumer schreibt sequentiell. Voller Channel → verwerfen.
 * - Zeilenformat: `epochMillis \t LEVEL \t tag \t message`; tag/message werden
 *   sanitisiert (Tab/Newline → Space), damit eine Log-Zeile genau eine Dateizeile
 *   ist und [readTail] robust parst.
 * - Alle Datei-Operationen in runCatching: I/O-Fehler crashen weder Hot-Path noch
 *   Export.
 */
class LogFileStore(
    private val dir: File,
    ioDispatcher: CoroutineDispatcher,
    private val rotateThresholdBytes: Long = ROTATE_THRESHOLD_BYTES
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val channel = Channel<LogBuffer.LogEntry>(capacity = CHANNEL_CAPACITY)

    init {
        scope.launch { for (entry in channel) writeEntry(entry) }
    }

    /** Non-blocking: legt den Eintrag in den Channel. Voll → verwerfen (best-effort). */
    fun append(entry: LogBuffer.LogEntry) {
        channel.trySend(entry)
    }

    /** Synchroner Write mit Rotation. Vom Consumer (und von Tests) aufgerufen. */
    internal fun writeEntry(entry: LogBuffer.LogEntry) {
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            rotateIfNeeded()
            File(dir, CURRENT).appendText(format(entry) + "\n")
        }
    }

    private fun rotateIfNeeded() {
        val current = File(dir, CURRENT)
        if (current.exists() && current.length() >= rotateThresholdBytes) {
            val prev = File(dir, PREV)
            if (prev.exists()) prev.delete()
            current.renameTo(prev)
        }
    }

    /**
     * Liest PREV + CURRENT (Datei-Reihenfolge = alt→neu), parst und gibt die
     * jüngsten [max] Einträge **neueste zuerst** zurück (gleiche Semantik wie
     * [LogBuffer.snapshot]). Unparsebare Zeilen werden übersprungen.
     */
    fun readTail(max: Int): List<LogBuffer.LogEntry> {
        if (max <= 0) return emptyList()
        val lines = buildList {
            File(dir, PREV).takeIf { it.exists() }?.let { addAll(readLinesSafe(it)) }
            File(dir, CURRENT).takeIf { it.exists() }?.let { addAll(readLinesSafe(it)) }
        }
        return lines.mapNotNull { parseLine(it) }.takeLast(max).asReversed()
    }

    /** Löscht beide Log-Dateien. */
    fun clear() {
        runCatching { File(dir, PREV).delete() }
        runCatching { File(dir, CURRENT).delete() }
    }

    /** Stoppt den Consumer. Nur Tests/Shutdown — Produktion ist App-Lebensdauer-Singleton. */
    fun close() {
        channel.close()
        scope.cancel()
    }

    private fun readLinesSafe(file: File): List<String> =
        runCatching { file.readLines() }.getOrDefault(emptyList())

    private fun format(e: LogBuffer.LogEntry): String =
        "${e.timestamp}\t${e.level.name}\t${sanitize(e.tag)}\t${sanitize(e.message)}"

    private fun sanitize(s: String): String =
        s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')

    private fun parseLine(line: String): LogBuffer.LogEntry? {
        if (line.isBlank()) return null
        val parts = line.split('\t', limit = 4)
        if (parts.size < 4) return null
        val ts = parts[0].toLongOrNull() ?: return null
        val level = runCatching { LogBuffer.Level.valueOf(parts[1]) }.getOrNull() ?: return null
        return LogBuffer.LogEntry(ts, level, parts[2], parts[3])
    }

    companion object {
        const val CURRENT = "tmm-log.current"
        const val PREV = "tmm-log.prev"
        private const val ROTATE_THRESHOLD_BYTES = 2L * 1024 * 1024 // 2 MB → Cap ≈ 4 MB
        private const val CHANNEL_CAPACITY = 256
    }
}
```

- [ ] **Step 4: `LogFileStore` in Hilt bereitstellen** — `app/src/main/java/io/github/lycheeappf/tmm/core/di/AppModule.kt`

Importe ergänzen (zu den bestehenden Imports oben in der Datei):

```kotlin
import io.github.lycheeappf.tmm.core.util.LogFileStore
import java.io.File
```

Innerhalb `object AppModule` (z.B. nach `provideClock`) einfügen:

```kotlin
    /**
     * Rollierende On-Disk-Persistenz für [LogBuffer]. Liegt in `filesDir/diagnostics/`
     * (nicht cacheDir — Cache kann das System mitten in der Fahrt räumen).
     */
    @Provides @Singleton
    fun provideLogFileStore(
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: CoroutineDispatcher
    ): LogFileStore = LogFileStore(File(context.filesDir, "diagnostics"), ioDispatcher)
```

- [ ] **Step 5: Tests laufen lassen, PASS bestätigen**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.core.util.LogFileStoreTest"`
Expected: PASS (7 Tests grün).

- [ ] **Step 6: Commit** (nur auf Go-Maintainer, ohne KI-Provenance)

```bash
git add app/src/main/java/io/github/lycheeappf/tmm/core/util/LogFileStore.kt \
        app/src/main/java/io/github/lycheeappf/tmm/core/di/AppModule.kt \
        app/src/test/java/io/github/lycheeappf/tmm/core/util/LogFileStoreTest.kt
git commit -m "feat(diagnostics): add rolling on-disk LogFileStore"
```

---

## Task 2: `LogBuffer` persistiert + lädt Tail beim Start

**Files:**
- Modify: `app/src/main/java/io/github/lycheeappf/tmm/core/util/LogBuffer.kt`
- Test: `app/src/test/java/io/github/lycheeappf/tmm/core/util/LogBufferTest.kt` (bestehend, anpassen)

**Interfaces:**
- Consumes: `LogFileStore.append`, `LogFileStore.readTail`, `LogFileStore.clear` (Task 1); `@IoDispatcher` (`AppModule`).
- Produces: `LogBuffer` mit neuem Konstruktor `@Inject constructor(fileStore: LogFileStore, @IoDispatcher ioDispatcher: CoroutineDispatcher)`. Öffentliche API (`info`/`warn`/`error`/`events`/`snapshot`/`clear`/`LogEntry`/`Level`) bleibt unverändert.

- [ ] **Step 1: Bestehenden Test auf neuen Konstruktor umstellen + Persistenz-Tests ergänzen** — `app/src/test/java/io/github/lycheeappf/tmm/core/util/LogBufferTest.kt` (komplette Datei ersetzen)

```kotlin
package io.github.lycheeappf.tmm.core.util

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Ring-Buffer-Semantik (neueste zuerst, Kapazitäts-Cap, clear) bleibt unverändert;
 * zusätzlich: jede Zeile wird an [LogFileStore] durchgereicht und der persistierte
 * Tail wird beim Start in den Ring geladen.
 */
class LogBufferTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var fileStore: LogFileStore
    private lateinit var buffer: LogBuffer

    @Before fun setup() {
        fileStore = LogFileStore(File(tmp.root, "diagnostics"), UnconfinedTestDispatcher())
        buffer = LogBuffer(fileStore, UnconfinedTestDispatcher())
    }

    @Test fun `snapshot is newest-first`() {
        buffer.info("T", "a")
        buffer.warn("T", "b")
        buffer.error("T", "c")
        val snap = buffer.snapshot()
        assertThat(snap.map { it.message }).containsExactly("c", "b", "a").inOrder()
        assertThat(snap.first().level).isEqualTo(LogBuffer.Level.Error)
    }

    @Test fun `snapshot is capped at capacity, dropping oldest`() {
        repeat(560) { buffer.info("T", "m$it") }
        val snap = buffer.snapshot()
        assertThat(snap).hasSize(500)
        assertThat(snap.first().message).isEqualTo("m559")
        assertThat(snap.last().message).isEqualTo("m60")
    }

    @Test fun `events emits current snapshot on subscription`() = runTest {
        buffer.info("T", "first")
        buffer.info("T", "second")
        assertThat(buffer.events.first().map { it.message }).containsExactly("second", "first").inOrder()
    }

    @Test fun `clear empties buffer and events`() = runTest {
        buffer.info("T", "x")
        buffer.clear()
        assertThat(buffer.snapshot()).isEmpty()
        assertThat(buffer.events.first()).isEmpty()
    }

    @Test fun `log persists to file store`() = runTest {
        val store = LogFileStore(File(tmp.root, "persist"), StandardTestDispatcher(testScheduler))
        val buf = LogBuffer(store, StandardTestDispatcher(testScheduler))
        buf.info("T", "persisted")
        advanceUntilIdle()
        assertThat(store.readTail(10).map { it.message }).contains("persisted")
        store.close()
    }

    @Test fun `tail is loaded into ring on construction`() = runTest {
        val store = LogFileStore(File(tmp.root, "fresh"), StandardTestDispatcher(testScheduler))
        store.writeEntry(LogBuffer.LogEntry(1L, LogBuffer.Level.Info, "T", "from-disk"))
        val fresh = LogBuffer(store, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertThat(fresh.snapshot().map { it.message }).contains("from-disk")
        store.close()
    }
}
```

- [ ] **Step 2: Test laufen lassen, FAIL bestätigen**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.core.util.LogBufferTest"`
Expected: FAIL — `LogBuffer(...)` erwartet noch keinen Konstruktor-Parameter (`too many arguments`).

- [ ] **Step 3: `LogBuffer` erweitern** — `app/src/main/java/io/github/lycheeappf/tmm/core/util/LogBuffer.kt` (komplette Datei ersetzen)

```kotlin
package io.github.lycheeappf.tmm.core.util

import io.github.lycheeappf.tmm.core.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-Memory Ring-Buffer (Capacity 500) für die letzten Log-Events + persistenter
 * On-Disk-Sink ([LogFileStore]). Der Diagnostics-Screen beobachtet [events] als
 * Live-Tail; der Export liest den vollen Verlauf aus der Datei.
 *
 * Hot-Path: [log] kopiert nicht pro Aufruf die 500er-Liste, sondern bumpt nur einen
 * O(1)-Versionszähler und reicht den Eintrag non-blocking an [LogFileStore.append].
 * Beim Start wird der persistierte Tail einmalig in den Ring geladen, damit Live-Tab
 * und Export Historie über Neustarts hinweg zeigen.
 */
@Singleton
class LogBuffer @Inject constructor(
    private val fileStore: LogFileStore,
    @IoDispatcher ioDispatcher: CoroutineDispatcher
) {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val buffer = ArrayDeque<LogEntry>(CAPACITY)
    private val version = MutableStateFlow(0)

    init {
        // Tail ist neueste-zuerst und gehört hinter etwaige Live-Einträge (älter).
        scope.launch {
            val tail = fileStore.readTail(CAPACITY)
            synchronized(buffer) {
                tail.forEach { if (buffer.size < CAPACITY) buffer.addLast(it) }
            }
            version.update { it + 1 }
        }
    }

    val events: Flow<List<LogEntry>> = version
        .map { snapshot() }
        .onStart { emit(snapshot()) }
        .conflate()

    fun info(tag: String, message: String) = log(Level.Info, tag, message)
    fun warn(tag: String, message: String) = log(Level.Warn, tag, message)
    fun error(tag: String, message: String) = log(Level.Error, tag, message)

    private fun log(level: Level, tag: String, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, tag, message)
        synchronized(buffer) {
            buffer.addFirst(entry)
            while (buffer.size > CAPACITY) buffer.removeLast()
        }
        fileStore.append(entry)
        version.update { it + 1 }
    }

    fun clear() {
        synchronized(buffer) { buffer.clear() }
        fileStore.clear()
        version.update { it + 1 }
    }

    fun snapshot(): List<LogEntry> = synchronized(buffer) { buffer.toList() }

    data class LogEntry(
        val timestamp: Long,
        val level: Level,
        val tag: String,
        val message: String
    ) {
        fun formattedTime(): String = formatter.format(Date(timestamp))
    }

    enum class Level { Info, Warn, Error }

    companion object {
        private const val CAPACITY = 500
        private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.GERMANY)
    }
}
```

- [ ] **Step 4: Tests laufen lassen, PASS bestätigen**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.core.util.LogBufferTest"`
Expected: PASS (6 Tests grün).

- [ ] **Step 5: Smoke-Build (Hilt-Graph kompiliert)**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL — `LogBuffer` wird überall injiziert; der neue `LogFileStore`-Dep muss aufgelöst werden.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/lycheeappf/tmm/core/util/LogBuffer.kt \
        app/src/test/java/io/github/lycheeappf/tmm/core/util/LogBufferTest.kt
git commit -m "feat(diagnostics): persist LogBuffer to LogFileStore and reload tail on start"
```

---

## Task 3: Reply-Branches in `NotificationReplyExecutor` loggen

**Files:**
- Modify: `app/src/main/java/io/github/lycheeappf/tmm/channel/notification/NotificationReplyExecutor.kt`
- Test: `app/src/test/java/io/github/lycheeappf/tmm/channel/notification/NotificationReplyExecutorTest.kt` (bestehend, anpassen)

**Interfaces:**
- Consumes: `LogBuffer.info/warn/error` (existiert).
- Produces: `NotificationReplyExecutor` mit zusätzlichem Konstruktor-Parameter `logBuffer: LogBuffer` (letzter Parameter). Verhalten/`ReplyResult`-Rückgaben unverändert; pro Branch eine `LogBuffer`-Zeile mit `TAG = "ReplyExecutor"`:
  - Success: `reply SUCCESS notif=<key> mapping=<id> via cache|rebuild`
  - `reply NO_ACTION notif=<key> (cache-miss + rebuild-miss)`
  - `reply NO_REMOTE_INPUT notif=<key>`
  - `reply PI_CANCELED notif=<key> reason=immutable`
  - `reply PI_CANCELED notif=<key> reason=canceled-on-send`
  - `reply PROVIDER_ERROR notif=<key> msg=<exception-message>`

- [ ] **Step 1: Test auf neuen Konstruktor + Branch-Logs erweitern** — `app/src/test/java/io/github/lycheeappf/tmm/channel/notification/NotificationReplyExecutorTest.kt`

Import ergänzen (bei den bestehenden Imports):

```kotlin
import io.github.lycheeappf.tmm.core.util.LogBuffer
```

Felder/Konstruktor (ersetze die `executor`-Zeile durch beide Zeilen):

```kotlin
    private val logBuffer = mockk<LogBuffer>(relaxed = true)
    private val executor = NotificationReplyExecutor(context, cache, rebuilder, fallback, logBuffer)
```

Verifikationen ans Ende der jeweiligen Tests anhängen:

```kotlin
// in `cache hit triggers pending intent and returns Success`:
verify { logBuffer.info("ReplyExecutor", "reply SUCCESS notif=${payload.notificationKey} mapping=42 via cache") }

// in `cache miss falls back to rebuilder and triggers send`:
verify { logBuffer.info("ReplyExecutor", "reply SUCCESS notif=${payload.notificationKey} mapping=42 via rebuild") }

// in `cache miss AND rebuilder miss posts fallback and returns NoActionAvailable`:
verify { logBuffer.warn("ReplyExecutor", "reply NO_ACTION notif=${payload.notificationKey} (cache-miss + rebuild-miss)") }

// in `PendingIntent canceled exception triggers fallback and returns PendingIntentCanceled`:
verify { logBuffer.warn("ReplyExecutor", "reply PI_CANCELED notif=${payload.notificationKey} reason=canceled-on-send") }

// in `empty remoteInputs returns NoRemoteInput`:
verify { logBuffer.warn("ReplyExecutor", "reply NO_REMOTE_INPUT notif=${payload.notificationKey}") }
```

- [ ] **Step 2: Test laufen lassen, FAIL bestätigen**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.channel.notification.NotificationReplyExecutorTest"`
Expected: FAIL — `NotificationReplyExecutor(...)` nimmt noch keinen 5. Parameter.

- [ ] **Step 3: `NotificationReplyExecutor` umbauen** — `app/src/main/java/io/github/lycheeappf/tmm/channel/notification/NotificationReplyExecutor.kt`

Import ergänzen:

```kotlin
import io.github.lycheeappf.tmm.core.util.LogBuffer
```

Konstruktor (Parameter ergänzen):

```kotlin
@Singleton
class NotificationReplyExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val actionCache: ActionCache,
    private val rebuilder: PendingIntentRebuilder,
    private val fallbackNotifier: FallbackNotifier,
    private val logBuffer: LogBuffer
) {
```

`reply(...)`-Body komplett ersetzen (zwischen `): ReplyResult {` und der schließenden `}` vor `companion object`):

```kotlin
    suspend fun reply(
        payload: ChannelPayload.Notification,
        mappingId: Long,
        text: String
    ): ReplyResult {
        val cached = actionCache.get(payload.notificationKey)
        val resolved = cached
            ?: rebuilder.rebuild(payload)
            ?: run {
                Log.w(TAG, "No action available for ${payload.notificationKey}")
                logBuffer.warn(TAG, "reply NO_ACTION notif=${payload.notificationKey} (cache-miss + rebuild-miss)")
                fallbackNotifier.post(payload, text)
                return ReplyResult.NoActionAvailable
            }
        val via = if (cached != null) "cache" else "rebuild"

        if (resolved.remoteInputs.isEmpty()) {
            logBuffer.warn(TAG, "reply NO_REMOTE_INPUT notif=${payload.notificationKey}")
            fallbackNotifier.post(payload, text)
            return ReplyResult.NoRemoteInput
        }

        // PI-Mutability-Check: RemoteInput-fill-in braucht einen mutable
        // PendingIntent. Bei IMMUTABLE würde Android den Text silent dropen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && resolved.actionIntent.isImmutable) {
            Log.w(TAG, "PendingIntent is IMMUTABLE — RemoteInput would silently drop. Fallback.")
            logBuffer.warn(TAG, "reply PI_CANCELED notif=${payload.notificationKey} reason=immutable")
            actionCache.remove(payload.notificationKey)
            fallbackNotifier.post(payload, text)
            return ReplyResult.PendingIntentCanceled
        }

        val intent = Intent()
        val bundle = Bundle().apply {
            resolved.remoteInputs.forEach { ri -> putCharSequence(ri.resultKey, text) }
        }
        RemoteInput.addResultsToIntent(resolved.remoteInputs.toTypedArray(), intent, bundle)
        RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)

        return try {
            resolved.actionIntent.send(context, 0, intent)
            Log.i(TAG, "Reply sent via RemoteInput (notif=${payload.notificationKey}, mapping=$mappingId)")
            logBuffer.info(TAG, "reply SUCCESS notif=${payload.notificationKey} mapping=$mappingId via $via")
            ReplyResult.Success
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "PendingIntent canceled for ${payload.notificationKey}", e)
            logBuffer.warn(TAG, "reply PI_CANCELED notif=${payload.notificationKey} reason=canceled-on-send")
            // Canceled = PI ist tot. Cache aufräumen damit folgende Replies
            // nicht in derselben Sackgasse landen.
            actionCache.remove(payload.notificationKey)
            fallbackNotifier.post(payload, text)
            ReplyResult.PendingIntentCanceled
        } catch (e: Exception) {
            Log.e(TAG, "Reply trigger failed", e)
            logBuffer.error(TAG, "reply PROVIDER_ERROR notif=${payload.notificationKey} msg=${e.message}")
            fallbackNotifier.post(payload, text)
            ReplyResult.ProviderError(e.message ?: "Unknown")
        }
    }
```

- [ ] **Step 4: Tests laufen lassen, PASS bestätigen**

Run: `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.channel.notification.NotificationReplyExecutorTest"`
Expected: PASS (5 Tests grün).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/lycheeappf/tmm/channel/notification/NotificationReplyExecutor.kt \
        app/src/test/java/io/github/lycheeappf/tmm/channel/notification/NotificationReplyExecutorTest.kt
git commit -m "feat(diagnostics): log reply branches in NotificationReplyExecutor"
```

---

## Task 4: `PendingIntentRebuilder` logging + `NotificationCapture` PII-Fix

**Files:**
- Modify: `channel/notification/PendingIntentRebuilder.kt` — `logBuffer: LogBuffer` injizieren; je Pfad eine Zeile (`TAG = "PIRebuilder"`): `rebuild NLS-null`, `rebuild activeNotifications SecurityException`, `rebuild NLS disconnected mid-call`, `rebuild activeNotifications failed: <cls>`, `rebuild notif=<key> no-longer-active`, `rebuild notif=<key> found-action`.
- Modify: `channel/notification/NotificationCapture.kt` — die Inject-Log-Zeile von `"Inject <addr>: <senderName> (<n> chars)"` auf `"Inject <addr> (<n> chars, group=<bool>, replyable=<bool>)"` umstellen (Kontaktname raus, Diagnose-Signale rein).
- Test: `channel/notification/PendingIntentRebuilderTest.kt` (**neu**, Robolectric) — NLS-null-Pfad: `rebuild(payload)` → `null` + `verify { logBuffer.warn("PIRebuilder", "rebuild NLS-null") }`.

**Schritte:** (1) Test schreiben → FAIL (Konstruktor 1-arg). (2) Rebuilder umbauen. (3) Capture-Zeile fixen. (4) `gradlew.bat :app:testDebugUnitTest --tests "...PendingIntentRebuilderTest"` → PASS. (5) PII-Audit-Grep nach `senderName`/`conversationLabel`/`.body` in `logBuffer`-Calls → keine Treffer. (6) Commit.

## Task 5: `Redaction` Maskier-/Hash-Helfer

**Files:**
- Create: `core/util/Redaction.kt` — `object Redaction` mit `maskName(String)` (→ `•••(len=N)`, leer bleibt leer), `hashKey(String)` (→ `sha1:<12 hex>`), `redactConversationKey(String)` (Marker `::id::`/`::lbl::` → Präfix erhalten, Rest gehasht; sonst ganz gehasht), `redactPayloadJson(String)` (Notification → `conversationLabel`+`senderDisplayName` maskiert; sonst unverändert).
- Test: `core/util/RedactionTest.kt` (**neu**, pure JVM) — 7 Tests (maskName, leer, hashKey stabil/prefixed, conversationKey Präfix+Hash, unbekanntes Format ganz gehasht, payloadJson maskiert Notification-Namen, behält Llm-`assistantDisplayName`).

**Schritte:** Test → FAIL → `Redaction.kt` → PASS → Commit.

## Task 6: `DiagnosticsExporter` — Build-Header + Redaktion + Logs aus Datei

**Files:**
- Modify: `core/util/DiagnosticsExporter.kt` — Konstruktor: `logBuffer: LogBuffer` ersetzt durch `logFileStore: LogFileStore`. Neuer `BuildInfo`(versionName/versionCode/applicationId/debug aus `BuildConfig`) im `DiagnosticsSnapshot`. `MappingEntity.toSerializable`: `Redaction.redactConversationKey`/`Redaction.redactPayloadJson`. `ReplyHistoryEntity.toSerializable`: `text = "<len=N>"`. `logs = logFileStore.readTail(MAX_LOG_LINES=5000)`.
- Test: `core/util/DiagnosticsExporterTest.kt` (**neu**, Robolectric) — Notification-Mapping mit Name `Anna`, key `com.whatsapp::id::secretJid`, History `text="secret dictation"`. Assert: **nicht** `Anna`/`secretJid`/`secret dictation`; **enthält** `sha1:`, `<len=16>`, `com.whatsapp`, `BuildConfig.VERSION_NAME`.

**Schritte:** Test → FAIL → Exporter umbauen → PASS → Commit.

## Task 7: FileProvider + `file_paths.xml` + `DiagnosticsShare`

**Files:**
- Modify: `AndroidManifest.xml` — `FileProvider` mit `authorities="${applicationId}.fileprovider"`, `@xml/file_paths` (neben dem MMS-Stub-Provider).
- Create: `res/xml/file_paths.xml` — `<cache-path name="diagnostics" path="."/>`.
- Create: `ui/screen/diagnostics/DiagnosticsShare.kt` — `authority(packageName)`, `uriFor(context,file)`, `chooser(context,file,title)` (`ACTION_SEND`, `application/json`, `EXTRA_STREAM`, `FLAG_GRANT_READ_URI_PERMISSION`).
- Test: `ui/screen/diagnostics/DiagnosticsShareTest.kt` (**neu**, pure JVM) — `authority()` für Debug-/Release-Id. (`uriFor()` nicht unit-getestet — Robolectric trägt das `.debug`-Suffix in `context.packageName` nicht; Standard-AndroidX-Wiring, zur Laufzeit validiert.)

**Schritte:** Test → FAIL → Manifest/xml/Helper → PASS → Commit.

## Task 8: `DiagnosticsViewModel` Share + Event + Screen + Strings

**Files:**
- Create: `ui/screen/diagnostics/DiagnosticsEvent.kt` — `sealed interface { data class Share(file); data object ExportFailed }`.
- Modify: `DiagnosticsViewModel.kt` — `exportDiagnostics()`→`shareDiagnostics()` (`coRunCatching { exporter.exportToCache() }`, sendet Event); `events` über `Channel(BUFFERED).receiveAsFlow()`.
- Modify: `DiagnosticsScreen.kt` — Toolbar-Icon `Download`→`Share`, `shareDiagnostics`, `diagnostics_share_action`; `LaunchedEffect` → Chooser/Toast.
- Modify: `strings_diagnostics.xml` (EN+DE) — `diagnostics_share_action|share_chooser_title|share_failed`.
- Test: `DiagnosticsViewModelTest.kt` (**neu**, Robolectric, `Dispatchers.setMain`) — `Share`/`ExportFailed`.

**Schritte:** Event + VM + Screen + Strings → Test → PASS → Commit.

## Task 9: Settings „Diagnose senden" (ohne Developer-Mode)

**Files:**
- Modify: `SettingsViewModel.kt` — `diagnosticsExporter` injizieren; `sendingDiagnostics: Boolean`; `shareDiagnostics()` + `events` (spiegelt Task 8).
- Modify: `SettingsScreen.kt` — `LaunchedEffect` → Chooser/Toast; `SectionHeader(settings_section_support)` + `SettingCard`/`PrimaryActionButton` + Hinweis — **vor** `AnimatedVisibility(developerMode)`.
- Modify: `strings_settings.xml` (EN+DE) — `settings_section_support`, `settings_send_diagnostics_title|desc|button|hint`.
- Modify: `SettingsViewModelTest.kt` (bestehend) — Konstruktor um `diagnosticsExporter`-Mock erweitern.
- Test: `SettingsViewModelShareTest.kt` (**neu**) — `Share`/`ExportFailed`.

**Schritte:** VM + Screen + Strings → bestehenden Test fixen + neuen Test → PASS → Commit.

## Task 10: CHANGELOG + Version-Bump

- `app/build.gradle.kts` — `versionCode 6→7`, `versionName "0.6.0"→"0.7.0"`.
- `CHANGELOG.md` — `## [0.7.0] — 2026-06-21` (Added: One-Tap-Export; Changed: persistenter Log + Pipeline-Logging; Fixed: Privacy/Kontaktname).

## Finalisierung

- `gradlew.bat :app:testDebugUnitTest` (volle Suite) → PASS.
- `gradlew.bat :app:assembleDebug` → BUILD SUCCESSFUL.
- i18n-Parität EN/DE manuell prüfen (kein Lint — crasht laut Memory).

---

## Self-Review (Spec-Abgleich)

- **Spec-Abdeckung:** A=T1; B=T2; C=T3/T4; D=T5/T6; E=T7/T8; F=T9; Rollout=T10. „Komponente E" (ReplyHistory-Anreicherung) bewusst **nicht** umgesetzt (Spec-Non-Goal).
- **Privacy:** Pipeline loggt nur Metadaten; Export redigiert Mappings/History zusätzlich; PII-Audit per Grep verifiziert.
- **Typ-Konsistenz:** `DiagnosticsEvent` von beiden ViewModels genutzt; `LogFileStore.readTail/append/clear/close` einheitlich; `Redaction`-Signaturen über Exporter + Tests konsistent.
- **Kein Room-Schema-Change**, kein neuer Dispatcher-Direktzugriff, `coRunCatching` für suspend-Export (CancellationException-safe).

## Implementierungs-Status (unattended ausgeführt)

Alle Tasks 1–10 implementiert und verifiziert: jede Komponente per Unit-Test grün, volle `:app:testDebugUnitTest`-Suite grün, `:app:assembleDebug` erfolgreich, EN/DE-Parität bestätigt. **Noch nicht committet** — wartet auf Maintainer-Review (Commit ohne KI-Provenance, Branch `nightly`).
