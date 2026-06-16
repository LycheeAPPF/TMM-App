package io.github.lycheeappf.tmm.channel.llm

import io.github.lycheeappf.tmm.channel.llm.provider.LlmTurn
import io.github.lycheeappf.tmm.core.util.Clock
import io.github.lycheeappf.tmm.data.store.AssistantPreferencesStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-Memory-Konversations-State pro LLM-Mapping. Persistent ist *nichts*: bei
 * App-Restart oder TTL-Expire startet jede Session fresh — bewusst aggressiv,
 * damit private Inhalte nicht akkumulieren.
 *
 * Die [Session.mutex] schützt einen kompletten "Turn" (User-Append +
 * Provider-Call + Assistant-Append). Der Lock wird im [LlmTurnRunner] gehalten,
 * nicht hier — hier sind reine Datenoperationen.
 */
@Singleton
class LlmConversationStore @Inject constructor(
    private val prefs: AssistantPreferencesStore,
    private val clock: Clock
) {

    /**
     * Session-State. **Achtung:** Die Liste ist mutable und wird ausschließlich
     * unter [mutex] verändert. Reader ([snapshot]) kopieren raus.
     */
    class Session(
        val mutex: Mutex = Mutex(),
        internal val history: ArrayDeque<LlmTurn> = ArrayDeque(),
        @Volatile var lastInteractionAt: Long = 0L
    )

    private val sessions = ConcurrentHashMap<Long, Session>()

    /**
     * Holt (oder erzeugt) die Session **atomar** via [ConcurrentHashMap.computeIfAbsent].
     * Kotlins `getOrPut`-Extension auf `ConcurrentMap` ist NICHT atomar und kann unter
     * Last zwei separate Session-Instanzen + Mutexe für denselben mappingId produzieren —
     * was die Per-Turn-Serialisierung im `LlmTurnRunner` aushebelt.
     */
    fun sessionFor(mappingId: Long): Session =
        sessions.computeIfAbsent(mappingId) { Session() }

    /**
     * Hard-Replace (entfernt Map-Entry). NUR für Cleanup/Test-Reset gedacht.
     * Für produktiven "fresh start" stattdessen [resetUnderLock] verwenden, sodass
     * keine parallel laufenden Turn-Runner mit einer ungelinkten Session weiterarbeiten.
     */
    fun reset(mappingId: Long) { sessions.remove(mappingId) }

    /**
     * Leert die History **unter** dem Session-Mutex. Wartet auf einen evtl. laufenden
     * Turn und garantiert, dass danach die selbe Session-Instanz weiter benutzt werden
     * kann (kein replace, kein orphan).
     */
    suspend fun resetUnderLock(mappingId: Long) {
        val session = sessionFor(mappingId)
        session.mutex.withLock {
            session.history.clear()
            session.lastInteractionAt = 0L
        }
    }

    fun resetAll() { sessions.clear() }

    /**
     * Muss unter `session.mutex` gerufen werden. Verwirft die History, wenn die
     * letzte Interaktion länger als die konfigurierte TTL her ist.
     *
     * Wichtig: arbeitet auf der **mitgegebenen** Session-Instanz, nicht auf einem
     * frischen `sessionFor()`-Lookup. Sonst könnte parallel ein `reset(mappingId)`
     * eine neue Session unter die laufende Turn-Logik schieben.
     */
    suspend fun expireIfStale(session: Session) {
        val ttlMs = prefs.contextTtlSeconds() * 1000L
        if (session.lastInteractionAt != 0L && clock.now() - session.lastInteractionAt > ttlMs) {
            session.history.clear()
        }
        // Opportunistisch: alte Sessions aus der Map räumen, damit der `sessions`-
        // Container über die App-Lebensdauer nicht ungebremst wächst. V1 nutzt
        // nur einen Mapping, in V3 (mehrere Personas) ist das relevant.
        evictAbandonedSessions()
    }

    /**
     * Entfernt Map-Einträge, die seit [HARD_EVICT_MS] keine Interaktion mehr
     * hatten. Best-effort — die Map ist ConcurrentHashMap, also race-safe.
     */
    private fun evictAbandonedSessions() {
        val cutoff = clock.now() - HARD_EVICT_MS
        val it = sessions.entries.iterator()
        while (it.hasNext()) {
            val (_, s) = it.next()
            // Wir entfernen nur, wenn der Mutex frei ist UND lastInteraction lange her.
            // sonst könnte ein parallel laufender Turn auf dieser Session laufen.
            if (s.lastInteractionAt != 0L && s.lastInteractionAt < cutoff && !s.mutex.isLocked) {
                it.remove()
            }
        }
    }

    /** Muss unter `session.mutex` gerufen werden. */
    fun append(session: Session, turn: LlmTurn) {
        session.history.addLast(turn)
        while (session.history.size > HISTORY_CAP) session.history.removeFirst()
        session.lastInteractionAt = clock.now()
    }

    /**
     * Read-only Snapshot. **Muss unter `session.mutex` gerufen werden** — die
     * darunter liegende `ArrayDeque` ist nicht thread-safe für concurrent mutation.
     */
    fun snapshot(session: Session): List<LlmTurn> = session.history.toList()

    companion object {
        /** Hard-Cap. Drop oldest turn beyond this. */
        const val HISTORY_CAP = 20

        /**
         * Sessions, deren letzte Interaktion länger als [HARD_EVICT_MS] her ist,
         * werden komplett aus der Map entfernt — verhindert Memory-Growth, wenn
         * V3 mit vielen Mapping-IDs arbeitet.
         */
        private val HARD_EVICT_MS = java.util.concurrent.TimeUnit.HOURS.toMillis(1)
    }
}
