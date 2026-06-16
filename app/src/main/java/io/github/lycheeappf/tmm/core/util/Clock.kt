package io.github.lycheeappf.tmm.core.util

/**
 * Einfache abstrahierte Uhr für Komponenten, die in Tests deterministisch
 * voranschreiten müssen (Rate-Limiter, Conversation-Store TTL, Echo-Ledger).
 * Production-Wert in [SystemClock]; DI-Binding in [AppModule].
 */
fun interface Clock {
    fun now(): Long
}

object SystemClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
}
