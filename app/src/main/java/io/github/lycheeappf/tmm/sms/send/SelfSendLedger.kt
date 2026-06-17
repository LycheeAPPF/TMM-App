package io.github.lycheeappf.tmm.sms.send

import io.github.lycheeappf.tmm.core.util.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Merkt sich kurzzeitig die OUTBOX-Rows, die der [RealSmsSender] selbst
 * geschrieben hat, damit der [io.github.lycheeappf.tmm.sms.outbound.OutboundSmsObserver]
 * sie NICHT als Tesla-Reply fehlklassifiziert, dispatched oder löscht.
 *
 * Zwei Schlüssel:
 *  - **rowId** (exakt, bevorzugt) — gesetzt direkt nach dem Insert.
 *  - **(address, bodyHash)** (Fallback) — gesetzt VOR dem Insert, deckt das
 *    Race-Fenster ab, in dem der Observer die Row sieht, bevor wir ihre rowId kennen.
 *
 * Analog [io.github.lycheeappf.tmm.channel.llm.InjectedMessageLedger], aber mit
 * längerer TTL (30 s): ein Multipart-Send + die OUTBOX→SENT/FAILED-Transition
 * können über MAP-/Observer-Latenz mehrere Sekunden spannen.
 */
@Singleton
class SelfSendLedger @Inject constructor(
    private val clock: Clock
) {
    private data class Entry(val addressKey: String, val bodyHash: Int, val at: Long)

    private val entries = ArrayDeque<Entry>()
    private val rowIds = LinkedHashMap<Long, Long>() // rowId -> timestamp
    private val lock = Any()

    /** VOR dem Provider-Insert aufrufen (deckt die Observer-Race ab). */
    fun markSelfSend(address: String, body: String) = synchronized(lock) {
        entries.addLast(Entry(normalizeAddress(address), body.hashCode(), clock.now()))
        evictOld()
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    /** NACH dem Insert mit der vergebenen rowId aufrufen (exakter Schlüssel). */
    fun markRowId(rowId: Long) = synchronized(lock) {
        rowIds[rowId] = clock.now()
        evictOld()
        while (rowIds.size > MAX_ENTRIES) {
            val it = rowIds.keys.iterator(); it.next(); it.remove()
        }
    }

    fun isSelfSend(rowId: Long): Boolean = synchronized(lock) {
        evictOld()
        rowIds.containsKey(rowId)
    }

    fun isSelfSend(address: String, body: String): Boolean = synchronized(lock) {
        evictOld()
        val key = normalizeAddress(address)
        val hash = body.hashCode()
        entries.any { it.addressKey == key && it.bodyHash == hash }
    }

    fun clear() = synchronized(lock) {
        entries.clear()
        rowIds.clear()
    }

    private fun evictOld() {
        val cutoff = clock.now() - TTL_MS
        while (entries.isNotEmpty() && entries.first().at < cutoff) entries.removeFirst()
        val rit = rowIds.entries.iterator()
        while (rit.hasNext()) {
            if (rit.next().value < cutoff) rit.remove() else break // insertion-order ⇒ ältester zuerst
        }
    }

    /** Wie [io.github.lycheeappf.tmm.sms.outbound.OutboundSmsClassifier]: `[^+0-9]` strippen + `00→+`. */
    private fun normalizeAddress(raw: String): String {
        var clean = raw.replace(Regex("[^+0-9]"), "")
        if (clean.startsWith("00")) clean = "+" + clean.substring(2)
        return clean
    }

    companion object {
        private val TTL_MS = TimeUnit.SECONDS.toMillis(30)
        private const val MAX_ENTRIES = 200
    }
}
