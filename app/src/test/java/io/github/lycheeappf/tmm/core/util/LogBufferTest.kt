package io.github.lycheeappf.tmm.core.util

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Sichert die Ring-Buffer-Semantik nach der Hot-Path-Optimierung ab: der Producer
 * kopiert nicht mehr pro Log-Call die Liste, sondern bumpt nur einen Versionszähler;
 * [LogBuffer.events] materialisiert den Snapshot erst beim Sammeln. Reihenfolge
 * (neueste zuerst), Kapazitäts-Cap und clear() müssen unverändert gelten.
 */
class LogBufferTest {

    private val buffer = LogBuffer()

    @Test fun `snapshot is newest-first`() {
        buffer.info("T", "a")
        buffer.warn("T", "b")
        buffer.error("T", "c")
        val snap = buffer.snapshot()
        assertThat(snap.map { it.message }).containsExactly("c", "b", "a").inOrder()
        assertThat(snap.first().level).isEqualTo(LogBuffer.Level.Error)
    }

    @Test fun `snapshot is capped at capacity, dropping oldest`() {
        // CAPACITY ist privat (500) — wir loggen deutlich darüber.
        repeat(560) { buffer.info("T", "m$it") }
        val snap = buffer.snapshot()
        assertThat(snap).hasSize(500)
        assertThat(snap.first().message).isEqualTo("m559") // neueste behalten
        assertThat(snap.last().message).isEqualTo("m60")   // m0..m59 verworfen
    }

    @Test fun `events emits current snapshot on subscription`() = runTest {
        buffer.info("T", "first")
        buffer.info("T", "second")
        val snap = buffer.events.first()
        assertThat(snap.map { it.message }).containsExactly("second", "first").inOrder()
    }

    @Test fun `clear empties buffer and events`() = runTest {
        buffer.info("T", "x")
        buffer.clear()
        assertThat(buffer.snapshot()).isEmpty()
        assertThat(buffer.events.first()).isEmpty()
    }
}
