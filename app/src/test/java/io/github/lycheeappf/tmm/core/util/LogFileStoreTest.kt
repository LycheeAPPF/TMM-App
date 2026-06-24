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
        // Jede Zeile "N\tInfo\tT\tmN\n" = 12 Bytes (ASCII). Schwelle 70 → genau eine
        // Rotation (prev = m0..m5, current = m6..m9); m0 überlebt in prev und beweist,
        // dass readTail prev+current liest.
        val s = store(thresholdBytes = 70)
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
