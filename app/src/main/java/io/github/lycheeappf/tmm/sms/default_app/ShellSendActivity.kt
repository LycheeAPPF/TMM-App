package io.github.lycheeappf.tmm.sms.default_app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.github.lycheeappf.tmm.MainActivity

/**
 * Aktivität, die SEND/SENDTO-Intents (smsto://, mmsto://, text/plain) entgegennimmt,
 * damit die App als Default-SMS-App qualifiziert ist.
 *
 * Leitet direkt an den eigenen SmsComposeScreen weiter (Empfänger und Text vorbelegt).
 */
class ShellSendActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val original = intent
        val recipient = original?.data?.let { data ->
            when (data.scheme) {
                "sms", "smsto", "mms", "mmsto" -> data.schemeSpecificPart
                else -> null
            }
        }
        val body = original?.getStringExtra("sms_body")
            ?: original?.getStringExtra(Intent.EXTRA_TEXT)

        startActivity(
            MainActivity.composeIntent(this, recipient, body)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}
