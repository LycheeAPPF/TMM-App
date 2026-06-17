package io.github.lycheeappf.tmm.domain.sms

/**
 * Versendet echte SMS über das Modem (`SmsManager`) und schreibt – als
 * Standard-SMS-App-Pflicht – die OUTBOX-Row selbst in `content://sms`.
 *
 * Wirft NIE bei Benutzer-/Status-Fehlern: alles wird auf [SmsSendResult]
 * gemappt, damit die UI eine klare (deutsche) Rückmeldung geben kann.
 */
interface SmsSender {
    suspend fun send(address: String, body: String): SmsSendResult
}

/**
 * Ergebnis des Sende-Versuchs. [Enqueued] heißt „an SmsManager übergeben +
 * OUTBOX-Row geschrieben" — die tatsächliche Zustellung (SENT/FAILED) läuft
 * asynchron und wird über die Row-`TYPE`-Transition im Provider sichtbar
 * (der Thread aktualisiert sich via ContentObserver).
 */
sealed interface SmsSendResult {
    data object Enqueued : SmsSendResult

    /** Vorab-Ablehnung ohne Modem-Kontakt (kein SMS-Versand passiert). */
    enum class Rejected : SmsSendResult {
        NOT_DEFAULT_SMS_APP,
        MISSING_SEND_PERMISSION,
        NO_TELEPHONY,
        BLANK_RECIPIENT,
        BLANK_BODY,

        /**
         * Empfänger liegt im `+888…`-Fake-Adressraum. Der Send-Motor sendet dorthin
         * NIEMALS (Bridge-Schutz: eine echte Nummer darf nie in den Fake-Raum, und
         * eine Fake-Adresse darf nie real versendet werden → Carrier-Kosten/Routing).
         */
        FAKE_ADDRESS_REFUSED
    }

    /** SmsManager warf beim Senden eine Exception. */
    data class Error(val reason: String) : SmsSendResult
}
