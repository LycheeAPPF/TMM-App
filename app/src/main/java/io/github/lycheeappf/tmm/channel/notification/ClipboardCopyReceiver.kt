package io.github.lycheeappf.tmm.channel.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/**
 * Tap-Handler für die Fallback-Notification: kopiert den nicht-zustellbaren
 * Reply-Text in die Zwischenablage, damit der User ihn manuell in die Messaging-App
 * einfügen kann.
 */
class ClipboardCopyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != FallbackNotifier.ACTION_COPY_TO_CLIPBOARD) return
        val text = intent.getStringExtra(FallbackNotifier.EXTRA_TEXT)
        if (text.isNullOrEmpty()) {
            Log.w(TAG, "Empty text in clipboard intent")
            return
        }
        FallbackNotifier.copyToClipboard(context, text)
        Toast.makeText(context, "Antwort kopiert", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "ClipboardCopyReceiver"
    }
}
