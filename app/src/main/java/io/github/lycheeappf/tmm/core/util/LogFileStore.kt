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
