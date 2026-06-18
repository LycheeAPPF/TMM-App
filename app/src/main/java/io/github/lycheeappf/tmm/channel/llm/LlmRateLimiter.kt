package io.github.lycheeappf.tmm.channel.llm

import io.github.lycheeappf.tmm.core.util.Clock
import io.github.lycheeappf.tmm.data.store.AssistantPreferencesStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-Mapping Token-Bucket-Counter, der unabhängig vom globalen [SendBudget]
 * verhindert, dass eine einzelne Konversation den User innerhalb von Minuten
 * eine größere xAI-Rechnung kostet. Defaults (6/min, 30/h) sind defensiv und
 * können in den Settings hochgezogen werden.
 *
 * Beide Counter benutzen ein einfaches sliding window (Liste der Timestamps)
 * — keine Persistenz, beim App-Restart sind die Limits wieder frei.
 */
@Singleton
class LlmRateLimiter @Inject constructor(
    private val prefs: AssistantPreferencesStore,
    private val clock: Clock
) {

    sealed class Decision {
        data object Allow : Decision()
        /** Abgelehnt; [reason] wird erst im [LlmChannel] zu lokalisiertem TTS-Text aufgelöst. */
        data class Reject(val reason: Reason) : Decision()
    }

    enum class Reason { PER_MINUTE, PER_HOUR }

    private val timestampsByMapping = ConcurrentHashMap<Long, ArrayDeque<Long>>()
    private val mutex = Mutex()

    suspend fun checkAndAcquire(mappingId: Long): Decision {
        // DataStore-Reads VOR dem Lock — sonst blockiert ein langsamer File-IO
        // alle anderen Rate-Limit-Aufrufer global.
        val maxPerMin = prefs.maxRequestsPerMin()
        val maxPerHour = prefs.maxRequestsPerHour()
        return mutex.withLock {
            val now = clock.now()
            // computeIfAbsent ist atomar; Kotlins `getOrPut` auf ConcurrentMap nicht.
            val q = timestampsByMapping.computeIfAbsent(mappingId) { ArrayDeque() }

            // Evict älter als 1h
            while (q.isNotEmpty() && q.first() < now - HOUR_MS) q.removeFirst()

            val countInWindow = q.count { it >= now - MINUTE_MS }
            when {
                countInWindow >= maxPerMin -> Decision.Reject(Reason.PER_MINUTE)
                q.size >= maxPerHour -> Decision.Reject(Reason.PER_HOUR)
                else -> {
                    q.addLast(now)
                    Decision.Allow
                }
            }
        }
    }

    /**
     * Gibt den zuletzt acquirierten Slot zurück. Wird vom TurnRunner bei Provider-
     * Failure aufgerufen, damit ein flakiges Netz die User-Quota nicht aufbraucht.
     * Best-effort: entfernt den jüngsten Timestamp, falls vorhanden.
     */
    suspend fun refund(mappingId: Long) {
        mutex.withLock {
            val q = timestampsByMapping[mappingId] ?: return@withLock
            q.removeLastOrNull()
        }
    }

    /** Resettet nur diese Mapping-Reihe — Gegensatz zu [resetAll]. */
    suspend fun reset(mappingId: Long) {
        mutex.withLock { timestampsByMapping.remove(mappingId) }
    }

    fun resetAll() = timestampsByMapping.clear()

    companion object {
        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 3_600_000L
    }
}
