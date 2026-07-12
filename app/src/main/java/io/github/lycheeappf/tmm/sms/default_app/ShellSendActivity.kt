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
        // RFC-5724-Parsing über die pure Funktion (rohe URI, NICHT schemeSpecificPart —
        // sonst landet `?body=…` im Empfänger). Body-Query hat Vorrang, dann die
        // klassischen Intent-Extras.
        val parsed = SmsUriParser.parse(original?.dataString)
        val body = parsed.body
            ?: original?.getStringExtra("sms_body")
            ?: original?.getStringExtra(Intent.EXTRA_TEXT)

        startActivity(
            MainActivity.composeIntent(this, parsed.recipient, body)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}
