package io.github.lycheeappf.tmm.channel.notification

import io.github.lycheeappf.tmm.core.util.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Capture-seitiges Echo-Ledger (Spiegel von [io.github.lycheeappf.tmm.channel.llm.InjectedMessageLedger],
 * aber in Gegenrichtung): jeder erfolgreiche RemoteInput-Reply registriert
 * (sourcePackage, bodyHash). [NotificationCapture] verwirft Re-Captures, deren
 * Body einem in den letzten [TTL_MS] gesendeten Reply gleicht — Messenger
 * re-posten ihre Notification nach dem Reply mit der EIGENEN Antwort als
 * neuester Message. Primärfix ist der Self-Skip im MessagingStyleExtractor;
 * dieses Ledger fängt Apps ab, die die Self-Person falsch labeln.
 *
 * TTL bewusst kurz: false-positive = eine echte eingehende Nachricht, die
 * zufällig wortgleich mit der eigenen Antwort ist, würde gedroppt.
 */
@Singleton
class SentReplyLedger @Inject constructor(
    private val clock: Clock
) {

    private data class Entry(val sourcePackage: String, val bodyHash: Int, val at: Long)

    private val entries = ArrayDeque<Entry>()
    private val lock = Any()

    fun record(sourcePackage: String, body: String) {
        synchronized(lock) {
            entries.addLast(Entry(sourcePackage, body.hashCode(), clock.now()))
            evictOld()
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
    }

    fun isRecentReply(sourcePackage: String, body: String): Boolean = synchronized(lock) {
        evictOld()
        val hash = body.hashCode()
        entries.any { it.sourcePackage == sourcePackage && it.bodyHash == hash }
    }

    private fun evictOld() {
        val cutoff = clock.now() - TTL_MS
        while (entries.isNotEmpty() && entries.first().at < cutoff) {
            entries.removeFirst()
        }
    }

    companion object {
        private val TTL_MS = TimeUnit.SECONDS.toMillis(15)
        private const val MAX_ENTRIES = 50
    }
}
