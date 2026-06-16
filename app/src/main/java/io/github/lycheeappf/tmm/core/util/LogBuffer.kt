package io.github.lycheeappf.tmm.core.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-Memory Ring-Buffer für die letzten N Log-Events. Wird vom Diagnostics-Screen
 * als Live-Log-Tail beobachtet.
 *
 * Capacity 500 — bei Overflow werden älteste Einträge verworfen.
 *
 * Hot-Path-Kosten: [log] läuft im Capture-/Inject-Pfad oft pro Notification. Statt
 * bei jedem Aufruf die komplette 500er-Liste zu kopieren, bumpen wir nur einen
 * O(1)-Versionszähler; die Liste wird ausschliesslich materialisiert, *während*
 * der Diagnose-Screen [events] sammelt (durch `conflate` ≤1 Kopie pro Frame).
 */
@Singleton
class LogBuffer @Inject constructor() {

    private val buffer = ArrayDeque<LogEntry>(CAPACITY)
    private val version = MutableStateFlow(0)

    /**
     * Live-Tail. Emittiert einen frischen Snapshot, wann immer sich der Buffer
     * ändert — aber `conflate`d, sodass ein Burst zu einer Kopie im Tempo des
     * Collectors zusammenfällt. Typ ist [Flow] (nicht StateFlow): es gibt keinen
     * "aktuellen Wert" ohne Kopie; Consumer nutzen `combine`/`collect`.
     */
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
        version.update { it + 1 }
    }

    fun clear() {
        synchronized(buffer) { buffer.clear() }
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
