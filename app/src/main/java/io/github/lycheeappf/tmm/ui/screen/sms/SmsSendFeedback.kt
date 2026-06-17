package io.github.lycheeappf.tmm.ui.screen.sms

import io.github.lycheeappf.tmm.domain.sms.SmsSendResult

/** Deutsche Snackbar-Texte für ein [SmsSendResult] (Thread-Reply + Compose teilen sich das). */
internal fun smsSendFeedback(result: SmsSendResult): String = when (result) {
    SmsSendResult.Enqueued -> "Wird gesendet…"
    SmsSendResult.Rejected.NOT_DEFAULT_SMS_APP -> "App ist nicht Standard-SMS-App."
    SmsSendResult.Rejected.MISSING_SEND_PERMISSION -> "SMS-Sendeberechtigung fehlt."
    SmsSendResult.Rejected.NO_TELEPHONY -> "Kein Mobilfunk verfügbar."
    SmsSendResult.Rejected.BLANK_RECIPIENT -> "Kein Empfänger."
    SmsSendResult.Rejected.BLANK_BODY -> "Nachricht ist leer."
    SmsSendResult.Rejected.FAKE_ADDRESS_REFUSED -> "Diese Adresse kann nicht angeschrieben werden."
    is SmsSendResult.Error -> "Senden fehlgeschlagen."
}
