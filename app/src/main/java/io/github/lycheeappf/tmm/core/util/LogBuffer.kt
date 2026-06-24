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

    /**
     * Live-Tail. Emittiert einen frischen Snapshot, wann immer sich der Buffer
     * ändert — aber `conflate`d, sodass ein Burst zu einer Kopie im Tempo des
     * Collectors zusammenfällt.
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
