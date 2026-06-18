package io.github.lycheeappf.tmm.sms.default_app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import io.github.lycheeappf.tmm.R
import io.github.lycheeappf.tmm.core.locale.localizedString

/**
 * Aktivität, die SEND/SENDTO-Intents (smsto://, mmsto://, text/plain) entgegennimmt,
 * damit die App als Default-SMS-App qualifiziert ist.
 *
 * Diese App ist KEIN vollwertiger SMS-Client – wir leiten an Google Messages weiter,
 * falls installiert. User-Education via Toast.
 */
class ShellSendActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forwardToGoogleMessagesOrAbort()
        finish()
    }

    private fun forwardToGoogleMessagesOrAbort() {
        val originalIntent = intent ?: return
        val recipient = extractRecipient(originalIntent)
        val body = extractBody(originalIntent)

        // Versuch: direkt zu Google Messages
        val googleMessages = packageManager.getLaunchIntentForPackage(GOOGLE_MESSAGES_PKG)
        if (googleMessages != null) {
            val forwarded = Intent(Intent.ACTION_SENDTO).apply {
                setPackage(GOOGLE_MESSAGES_PKG)
                data = if (recipient != null) Uri.parse("smsto:$recipient")
                       else Uri.parse("smsto:")
                if (body != null) putExtra("sms_body", body)
            }
            return try {
                startActivity(forwarded)
            } catch (e: Exception) {
                Log.w(TAG, "Forward to Google Messages failed", e)
                showFallbackToast()
            }
        }
        showFallbackToast()
    }

    private fun extractRecipient(intent: Intent): String? {
        val data = intent.data ?: return null
        return when (data.scheme) {
            "sms", "smsto", "mms", "mmsto" -> data.schemeSpecificPart
            else -> null
        }
    }

    private fun extractBody(intent: Intent): String? =
        intent.getStringExtra("sms_body")
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)

    private fun showFallbackToast() {
        Toast.makeText(
            this,
            localizedString(R.string.shell_not_full_client_toast),
            Toast.LENGTH_LONG
        ).show()
    }

    companion object {
        private const val TAG = "ShellSendActivity"
        private const val GOOGLE_MESSAGES_PKG = "com.google.android.apps.messaging"
    }
}
