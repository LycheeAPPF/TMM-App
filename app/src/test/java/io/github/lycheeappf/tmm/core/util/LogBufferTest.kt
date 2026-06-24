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
