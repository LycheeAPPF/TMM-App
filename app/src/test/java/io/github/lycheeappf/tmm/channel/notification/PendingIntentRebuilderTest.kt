package io.github.lycheeappf.tmm.channel.notification

import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.domain.channel.ChannelPayload
import io.mockk.mockk
import io.mockk.verify
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

    @Test
    fun `null NLS instance returns null and logs NLS-null`() {
        // NotificationForwardingService.instance ist im Unit-Test null (Service nie
        // verbunden) → rebuild fällt sofort auf den NLS-null-Pfad, ohne Framework-Call.
        val result = rebuilder.rebuild(payload)

        assertThat(result).isNull()
        verify { logBuffer.warn("PIRebuilder", "rebuild NLS-null") }
    }
}
