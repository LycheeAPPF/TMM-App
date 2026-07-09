package io.github.lycheeappf.tmm.ui.navigation

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Deep-Link-Routen-Kontrakt für [smsComposeRoute]/[smsThreadRoute]: die
 * Query-Keys müssen den [ARG_RECIPIENT]/[ARG_BODY]-Konstanten entsprechen
 * (dieselben Keys liest das SmsComposeViewModel aus dem SavedStateHandle)
 * und Sonderzeichen müssen verlustfrei URL-codiert round-trippen.
 * Robolectric nur wegen `android.net.Uri`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SmsComposeRouteTest {

    @Test fun `route without arguments is the plain compose route`() {
        assertThat(smsComposeRoute()).isEqualTo(MfsDestination.SmsCompose.route)
        assertThat(smsComposeRoute(null, null)).isEqualTo(MfsDestination.SmsCompose.route)
    }

    @Test fun `blank arguments are omitted like null ones`() {
        assertThat(smsComposeRoute("  ", "")).isEqualTo(MfsDestination.SmsCompose.route)
    }

    @Test fun `recipient only route uses the ARG_RECIPIENT key`() {
        val route = smsComposeRoute(recipient = "+49170", body = null)

        assertThat(route).isEqualTo("${MfsDestination.SmsCompose.route}?$ARG_RECIPIENT=%2B49170")
    }

    @Test fun `body only route uses the ARG_BODY key`() {
        val route = smsComposeRoute(recipient = null, body = "Hi")

        assertThat(route).isEqualTo("${MfsDestination.SmsCompose.route}?$ARG_BODY=Hi")
    }

    @Test fun `special characters round-trip through uri encoding`() {
        val body = "Grüße & Tschüss? 100% sicher"
        val route = smsComposeRoute(recipient = "+49 170/123", body = body)

        // Route muss als URI parsebar bleiben und beide Werte verlustfrei tragen.
        val uri = Uri.parse("app://${route}")
        assertThat(uri.getQueryParameter(ARG_RECIPIENT)).isEqualTo("+49 170/123")
        assertThat(uri.getQueryParameter(ARG_BODY)).isEqualTo(body)
    }

    @Test fun `route template of the nav host matches the builder keys`() {
        val template = "${MfsDestination.SmsCompose.route}?$ARG_RECIPIENT={$ARG_RECIPIENT}&$ARG_BODY={$ARG_BODY}"

        assertThat(template).isEqualTo("sms_compose?recipient={recipient}&body={body}")
    }

    @Test fun `sms thread route embeds the thread id as path segment`() {
        assertThat(smsThreadRoute(42L)).isEqualTo("sms_thread/42")
        assertThat(MfsDestination.SmsThread.route).isEqualTo("sms_thread/{$ARG_THREAD_ID}")
    }
}
