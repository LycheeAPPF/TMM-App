package io.github.lycheeappf.tmm.contact

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Service-Hülle für den [FakeContactAuthenticator].
 *
 * Wird via Manifest-Eintrag mit `BIND_ACCOUNT_AUTHENTICATOR`-Permission gebunden.
 * Liefert nur den IBinder zurück — die eigentliche Logik ist im Authenticator.
 */
class FakeContactAuthenticatorService : Service() {

    private lateinit var authenticator: FakeContactAuthenticator

    override fun onCreate() {
        super.onCreate()
        authenticator = FakeContactAuthenticator(this)
    }

    override fun onBind(intent: Intent?): IBinder? = authenticator.iBinder
}
