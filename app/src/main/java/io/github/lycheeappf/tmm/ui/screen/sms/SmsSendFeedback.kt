package io.github.lycheeappf.tmm.ui.screen.sms

import android.content.Context
import io.github.lycheeappf.tmm.R
import io.github.lycheeappf.tmm.core.locale.localizedString
import io.github.lycheeappf.tmm.domain.sms.SmsSendResult

/** Lokalisierte Snackbar-Texte für ein [SmsSendResult] (Thread-Reply + Compose teilen sich das). */
internal fun smsSendFeedback(context: Context, result: SmsSendResult): String = when (result) {
    SmsSendResult.Enqueued -> context.localizedString(R.string.sms_feedback_enqueued)
    SmsSendResult.Rejected.NOT_DEFAULT_SMS_APP -> context.localizedString(R.string.sms_feedback_not_default)
    SmsSendResult.Rejected.MISSING_SEND_PERMISSION -> context.localizedString(R.string.sms_feedback_missing_permission)
    SmsSendResult.Rejected.NO_TELEPHONY -> context.localizedString(R.string.sms_feedback_no_telephony)
    SmsSendResult.Rejected.BLANK_RECIPIENT -> context.localizedString(R.string.sms_feedback_blank_recipient)
    SmsSendResult.Rejected.BLANK_BODY -> context.localizedString(R.string.sms_feedback_blank_body)
    SmsSendResult.Rejected.FAKE_ADDRESS_REFUSED -> context.localizedString(R.string.sms_feedback_fake_address)
    is SmsSendResult.Error -> context.localizedString(R.string.sms_feedback_error)
}
