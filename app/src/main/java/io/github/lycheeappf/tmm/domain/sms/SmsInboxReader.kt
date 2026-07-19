package io.github.lycheeappf.tmm.domain.sms

import kotlinx.coroutines.flow.Flow

/**
 * Liest echte SMS direkt aus dem System-Provider (`content://sms`) für die
 * Inbox-/Thread-Anzeige. Hält KEINE eigene Kopie (kein Room-Mirror) — die
 * Bodies bleiben im OS-Store (CLAUDE.md: „never persist bodies").
 *
 * Die `+888…`-Bridge-Fakes werden konsequent über
 * [io.github.lycheeappf.tmm.core.model.FakeAddress.isFakeAddress] ausgeschlossen.
 */
interface SmsInboxReader {

    /** Neueste Konversationen (Threads), nach Datum absteigend, Fakes ausgeschlossen. */
    suspend fun loadConversations(limit: Int = DEFAULT_CONVERSATION_LIMIT): List<SmsConversation>

    /** Nachrichten eines Threads, nach Datum aufsteigend (älteste zuerst), Fakes ausgeschlossen. */
    suspend fun loadThread(threadId: Long, limit: Int = DEFAULT_THREAD_LIMIT): List<SmsMessage>

    /** Kontaktname zu einer Adresse via PhoneLookup, oder null (keine Permission / kein Treffer). */
    suspend fun contactName(address: String): String?

    /**
     * Markiert die eingehenden Nachrichten eines (echten) Threads als gelesen
     * (`READ=1, SEEN=1`). Nur als Standard-SMS-App; no-op sonst. Scoped auf
     * `thread_id` — echte und Fake-`+888…`-Threads haben getrennte thread_ids,
     * daher werden Fakes nie berührt.
     */
    suspend fun markThreadRead(threadId: Long)

    /**
     * Löscht eine einzelne (echte) SMS endgültig. Nur als Standard-SMS-App;
     * Fake-Rows (`+888…`) werden nie gelöscht. true nur bei ≥1 gelöschter Row.
     */
    suspend fun deleteMessage(messageId: Long): Boolean

    /**
     * Löscht alle (echten) Nachrichten eines Threads endgültig. Nur als
     * Standard-SMS-App; Threads mit Fake-Adressen werden nie gelöscht.
     */
    suspend fun deleteThread(threadId: Long): Boolean

    /**
     * Anzahl ungelesener eingehender echter SMS. Gleiche Ausschluss-Semantik wie die
     * Konversationsliste (Fakes/leere Adressen zählen nicht), aber ohne deren
     * Scan-/Thread-Limits — es zählen ALLE Rows. 0 bei fehlender Permission/Fehler.
     */
    suspend fun unreadCount(): Int

    /** Emittiert, wenn sich `content://sms` ändert (für Live-Refresh; im VM debounced). */
    fun changes(): Flow<Unit>

    companion object {
        const val DEFAULT_CONVERSATION_LIMIT = 200
        const val DEFAULT_THREAD_LIMIT = 500
    }
}
