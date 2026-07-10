package io.github.lycheeappf.tmm.channel.notification

import android.app.Notification
import android.service.notification.StatusBarNotification
import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.domain.channel.ChannelPayload
import io.github.lycheeappf.tmm.listener.NotificationForwardingService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PendingIntentRebuilderTest {

    private val resolver = mockk<ActionResolver>(relaxed = true)
    private val logBuffer = mockk<LogBuffer>(relaxed = true)
    private val rebuilder = PendingIntentRebuilder(resolver, logBuffer)

    private val payload = ChannelPayload.Notification(
        sourcePackage = "com.whatsapp",
        notificationKey = "0|com.whatsapp|42|null|10042",
        remoteInputResultKey = "input_text",
        conversationLabel = "Anna",
        senderDisplayName = "Anna"
    )

    @After
    fun tearDown() {
        // NotificationForwardingService.instance ist ein statisches Companion-Feld —
        // Robolectric lädt die Klasse innerhalb eines Testlaufs nur einmal, also muss
        // jeder Test seinen Fake wieder entfernen, sonst leakt er in den nächsten Test.
        setNlsInstance(null)
    }

    @Test
    fun `null NLS instance returns null and logs NLS-null`() {
        // NotificationForwardingService.instance ist im Unit-Test null (Service nie
        // verbunden) → rebuild fällt sofort auf den NLS-null-Pfad, ohne Framework-Call.
        val result = rebuilder.rebuild(payload)

        assertThat(result).isNull()
        verify { logBuffer.warn("PIRebuilder", "rebuild NLS-null") }
    }

    @Test
    fun `logs active-but-no-action when matched notification has no reply action`() {
        // arrange: active notification key match, resolver returns null
        val sbn = mockk<StatusBarNotification> {
            every { key } returns payload.notificationKey
            every { notification } returns mockk<Notification>()
        }
        val nls = mockk<NotificationForwardingService> {
            every { activeNotifications } returns arrayOf(sbn)
        }
        setNlsInstance(nls)
        every { resolver.findReplyAction(any()) } returns null

        val result = rebuilder.rebuild(payload)

        assertThat(result).isNull()
        verify { logBuffer.warn(any(), match { it.contains("active-but-no-action") }) }
        verify(exactly = 0) { logBuffer.info(any(), match { it.contains("found-action") }) }
    }

    /**
     * `NotificationForwardingService.instance` hat einen `private set` (nur der
     * Service selbst darf ihn via [NotificationForwardingService.onListenerConnected]
     * setzen). Der bestehende Test faked dieses Feld NICHT — im Unit-Test bleibt es
     * einfach null. Für den neuen Test brauchen wir aber einen aktiven NLS-Fake, also
     * schreiben wir das private Feld per Reflection (mechanische Anpassung ggü. dem
     * Brief, das fälschlich ein bestehendes Fake-Setup annahm). Kotlin legt das
     * `@Volatile`-Backing-Field von Companion-Properties direkt auf die äußere Klasse
     * (nicht auf `Companion`) — verifiziert per javap am kompilierten Bytecode.
     */
    private fun setNlsInstance(service: NotificationForwardingService?) {
        val field = NotificationForwardingService::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, service)
    }
}
