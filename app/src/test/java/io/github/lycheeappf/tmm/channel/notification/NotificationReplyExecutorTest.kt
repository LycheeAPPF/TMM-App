package io.github.lycheeappf.tmm.channel.notification

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.domain.channel.ChannelPayload
import io.github.lycheeappf.tmm.domain.reply.ReplyResult
import io.mockk.Runs
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationReplyExecutorTest {

    private val context = mockk<Context>(relaxed = true)
    private val cache = mockk<ActionCache>(relaxed = true)
    private val rebuilder = mockk<PendingIntentRebuilder>(relaxed = true)
    private val fallback = mockk<FallbackNotifier>(relaxed = true)
    private val logBuffer = mockk<LogBuffer>(relaxed = true)
    private val executor = NotificationReplyExecutor(context, cache, rebuilder, fallback, logBuffer)

    private val payload = ChannelPayload.Notification(
        sourcePackage = "com.whatsapp",
        notificationKey = "0|com.whatsapp|42|null|10042",
        remoteInputResultKey = "input_text",
        conversationLabel = "Anna",
        senderDisplayName = "Anna"
    )

    @Test
    fun `cache hit triggers pending intent and returns Success`() = runTest {
        val pi = mockk<PendingIntent>(relaxed = true)
        val ri = mockk<RemoteInput>(relaxed = true) { every { resultKey } returns "input_text" }
        val resolved = ResolvedReplyAction(pi, listOf(ri), 0L)
        every { cache.get(payload.notificationKey) } returns resolved
        every { pi.send(any<Context>(), any<Int>(), any()) } just Runs

        val result = executor.reply(payload, 42L, "hello")

        assertThat(result).isEqualTo(ReplyResult.Success)
        verify { pi.send(any<Context>(), any<Int>(), any()) }
        verify(exactly = 0) { fallback.post(any(), any()) }
        verify { logBuffer.info("ReplyExecutor", "reply SUCCESS notif=${payload.notificationKey} mapping=42 via cache") }
    }

    @Test
    fun `cache miss falls back to rebuilder and triggers send`() = runTest {
        val pi = mockk<PendingIntent>(relaxed = true)
        val ri = mockk<RemoteInput>(relaxed = true) { every { resultKey } returns "input_text" }
        val resolved = ResolvedReplyAction(pi, listOf(ri), 0L)
        every { cache.get(any()) } returns null
        every { rebuilder.rebuild(payload) } returns resolved
        every { pi.send(any<Context>(), any<Int>(), any()) } just Runs

        val result = executor.reply(payload, 42L, "hello")

        assertThat(result).isEqualTo(ReplyResult.Success)
        verify { rebuilder.rebuild(payload) }
        verify { pi.send(any<Context>(), any<Int>(), any()) }
        verify { logBuffer.info("ReplyExecutor", "reply SUCCESS notif=${payload.notificationKey} mapping=42 via rebuild") }
    }

    @Test
    fun `cache miss AND rebuilder miss posts fallback and returns NoActionAvailable`() = runTest {
        every { cache.get(any()) } returns null
        every { rebuilder.rebuild(payload) } returns null

        val result = executor.reply(payload, 42L, "hello")

        assertThat(result).isEqualTo(ReplyResult.NoActionAvailable)
        verify { fallback.post(payload, "hello") }
        verify { logBuffer.warn("ReplyExecutor", "reply NO_ACTION notif=${payload.notificationKey} (cache-miss + rebuild-miss)") }
    }

    @Test
    fun `PendingIntent canceled exception triggers fallback and returns PendingIntentCanceled`() = runTest {
        val pi = mockk<PendingIntent>(relaxed = true)
        val ri = mockk<RemoteInput>(relaxed = true) { every { resultKey } returns "input_text" }
        val resolved = ResolvedReplyAction(pi, listOf(ri), 0L)
        every { cache.get(any()) } returns resolved
        every { pi.send(any<Context>(), any<Int>(), any()) } throws PendingIntent.CanceledException()

        val result = executor.reply(payload, 42L, "hello")

        assertThat(result).isEqualTo(ReplyResult.PendingIntentCanceled)
        verify { fallback.post(payload, "hello") }
        verify { logBuffer.warn("ReplyExecutor", "reply PI_CANCELED notif=${payload.notificationKey} reason=canceled-on-send") }
    }

    @Test
    fun `empty remoteInputs returns NoRemoteInput`() = runTest {
        val pi = mockk<PendingIntent>(relaxed = true)
        val resolved = ResolvedReplyAction(pi, emptyList(), 0L)
        every { cache.get(any()) } returns resolved

        val result = executor.reply(payload, 42L, "hello")

        assertThat(result).isEqualTo(ReplyResult.NoRemoteInput)
        verify { fallback.post(payload, "hello") }
        coVerify(exactly = 0) { pi.send(any<Context>(), any<Int>(), any()) }
        verify { logBuffer.warn("ReplyExecutor", "reply NO_REMOTE_INPUT notif=${payload.notificationKey}") }
    }
}
