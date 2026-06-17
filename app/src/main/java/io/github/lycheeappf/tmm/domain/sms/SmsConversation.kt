package io.github.lycheeappf.tmm.domain.sms

/**
 * Reine Domain-Modelle für echte SMS (Standalone-SMS-Client). Keine Android-/
 * Telephony-Abhängigkeiten — die Provider-Kopplung lebt in `sms/read/`.
 *
 * WICHTIG: Diese Modelle repräsentieren ausschließlich ECHTE SMS. Die `+888…`-
 * Bridge-Fakes werden im Reader per [io.github.lycheeappf.tmm.core.model.FakeAddress]
 * herausgefiltert und tauchen hier nie auf.
 */

/** Richtung/Status einer SMS, vom Telephony-`TYPE` im Reader gemappt. */
enum class SmsDirection { INBOX, SENT, OUTBOX, FAILED, OTHER }

/**
 * Eine Konversation (ein Thread) in der Inbox-Liste.
 *
 * @param threadId Telephony `thread_id` — stabiler Gruppierungsschlüssel.
 * @param address Roh-Adresse (E.164 o.ä.) des Gegenübers.
 * @param displayName aufgelöster Kontaktname via PhoneLookup, oder null → Adresse anzeigen.
 * @param snippet Body der neuesten Nachricht (Vorschau).
 * @param date Zeitstempel der neuesten Nachricht.
 * @param unreadCount ungelesene eingehende Nachrichten (im abgefragten Fenster).
 * @param messageCount Gesamtzahl Nachrichten im Thread (im abgefragten Fenster).
 */
data class SmsConversation(
    val threadId: Long,
    val address: String,
    val displayName: String?,
    val snippet: String,
    val date: Long,
    val unreadCount: Int,
    val messageCount: Int
)

/** Eine einzelne SMS in der Thread-Ansicht. */
data class SmsMessage(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val direction: SmsDirection,
    val read: Boolean
) {
    /** Eingehend (vom Gegenüber) vs. ausgehend (von uns gesendet). */
    val isIncoming: Boolean get() = direction == SmsDirection.INBOX
}
