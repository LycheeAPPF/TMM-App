package io.github.lycheeappf.tmm.channel.notification

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-Memory Cache von PendingIntent + RemoteInputs pro Notification-Key.
 * Wird beim onNotificationPosted befüllt; bei Reply-Trigger primär konsultiert.
 *
 * Lebt nur in-process – nach App-Restart leer. PendingIntentRebuilder ist
 * der persistente Fallback.
 *
 * Eviction: capacity-based (oldest first) und age-based (TTL 24h).
 */
@Singleton
class ActionCache @Inject constructor() {

    private val store = ConcurrentHashMap<String, ResolvedReplyAction>()

    fun put(notificationKey: String, action: ResolvedReplyAction) {
        store[notificationKey] = action
        evictIfNeeded()
    }

    fun get(notificationKey: String): ResolvedReplyAction? {
        val entry = store[notificationKey] ?: return null
        if (System.currentTimeMillis() - entry.capturedAt > AGE_LIMIT_MS) {
            store.remove(notificationKey)
            return null
        }
        return entry
    }

    fun remove(notificationKey: String) {
        store.remove(notificationKey)
    }

    fun size(): Int = store.size

    private fun evictIfNeeded() {
        // Common-Path: unter Kapazität → keine Allokation, kein Scan. TTL wird
        // ohnehin lazy in get() durchgesetzt, daher hier kein Per-Put-Sweep nötig.
        if (store.size <= CAPACITY) return

        val now = System.currentTimeMillis()
        // Über Kapazität: erst abgelaufene Einträge rauswerfen ...
        store.entries.asSequence()
            .filter { now - it.value.capturedAt > AGE_LIMIT_MS }
            .map { it.key }
            .toList()
            .forEach { store.remove(it) }

        // ... und falls danach immer noch über Kapazität, die ältesten.
        if (store.size <= CAPACITY) return
        store.entries
            .sortedBy { it.value.capturedAt }
            .take(store.size - CAPACITY)
            .forEach { store.remove(it.key) }
    }

    companion object {
        private const val CAPACITY = 500
        private const val AGE_LIMIT_MS = 24L * 60 * 60 * 1000
    }
}
