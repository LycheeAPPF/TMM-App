package io.github.lycheeappf.tmm.channel.llm

import io.github.lycheeappf.tmm.core.util.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Belt-and-Suspenders gegen Echo-Loops: jeder erfolgreiche `injectIncoming()`
 * registriert sich hier. Der [OutboundSmsObserver] fragt vor dem Dispatch, ob
 * die gerade beobachtete Outbox-Row ein Echo eines eigenen Inbox-Inserts ist —
 * z.B. wenn Tesla die Welcome-Message irrtümlich als "PushMessage" wieder
 * ausreichen würde.
 *
 * In der Praxis (AOSP MAP) tritt dieser Pfad nicht auf, weil Tesla nur explizit
 * diktierte Antworten als Outbox-INSERT pushed. **Wichtig**: die TTL ist
 * absichtlich KURZ (2 s), damit legitime User-Antworten, die zufällig wortgleich
 * mit der vorigen Grok-Antwort sind ("OK", "Ja", "Verstanden"), nicht
 * gedroppt werden. False-positives kosten den User die Antwort.
 */
@Singleton
class InjectedMessageLedger @Inject constructor(
    private val clock: Clock
) {

    private data class Entry(val addressKey: String, val bodyHash: Int, val at: Long)

    private val entries = ArrayDeque<Entry>()
    private val lock = Any()

    fun markInjected(address: String, body: String) {
        synchronized(lock) {
            entries.addLast(Entry(normalizeAddress(address), body.hashCode(), clock.now()))
            evictOld()
            // Hard-Cap, falls jemand stark spammt
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
    }

    fun shouldIgnoreOutbound(address: String, body: String): Boolean = synchronized(lock) {
        evictOld()
        val hash = body.hashCode()
        val key = normalizeAddress(address)
        entries.any { it.addressKey == key && it.bodyHash == hash }
    }

    fun clear() = synchronized(lock) { entries.clear() }

    private fun evictOld() {
        val cutoff = clock.now() - TTL_MS
        while (entries.isNotEmpty() && entries.first().at < cutoff) {
            entries.removeFirst()
        }
    }

    /**
     * Normalisiert "Grok <+88810000007>" → "+88810000007", damit
     * markInjected (called mit der reinen Fake-Number) und
     * shouldIgnoreOutbound (called mit Tesla-Reply-ADDRESS, evtl. inkl.
     * Display-Prefix) gleichgesetzt werden können. Wir filtern auf
     * `[^+0-9]`, weil unsere internen Identitäten (mapping.fakeAddress) immer
     * numerische `+888x...`-Form haben.
     */
    private fun normalizeAddress(raw: String): String =
        raw.replace(Regex("[^+0-9]"), "")

    companion object {
        // 10 s — bei Bluetooth-MAP-Latenz auf MCU2 wandert eine soeben
        // gepostete Inbox-SMS bis zu mehreren Sekunden bis sie via MAP exportiert
        // ist. 2 s waren empirisch zu knapp und führten dazu, dass ein eigenes
        // Echo nicht mehr im Ledger war, wenn der Observer den Outbox-Row sah.
        // 10 s sind weiterhin kurz genug für legitime "OK"-Replies vom User
        // (die fast nie unter 10 s nach der Bot-Antwort kommen).
        private val TTL_MS = TimeUnit.SECONDS.toMillis(10)
        private const val MAX_ENTRIES = 200
    }
}
