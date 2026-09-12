package io.github.lycheeappf.tmm.channel.notification

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.core.util.SendBudget
import io.github.lycheeappf.tmm.data.store.SettingsStore
import io.github.lycheeappf.tmm.domain.channel.ChannelMapping
import io.github.lycheeappf.tmm.domain.channel.ChannelPayload
import io.github.lycheeappf.tmm.domain.repository.MappingRepository
import io.github.lycheeappf.tmm.core.model.ChannelId
import io.github.lycheeappf.tmm.listener.filter.ExtractedMessage
import io.github.lycheeappf.tmm.listener.filter.MessagingStyleExtractor
import io.github.lycheeappf.tmm.listener.filter.WhitelistFilter
import io.github.lycheeappf.tmm.platform.bluetooth.BluetoothConnectionChecker
import io.github.lycheeappf.tmm.platform.role.DefaultSmsRoleManager
import io.github.lycheeappf.tmm.sms.provider.SmsContentProviderWriter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationCaptureTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val whitelist = mockk<WhitelistFilter>()
    private val extractor = mockk<MessagingStyleExtractor>()
    private val actionResolver = mockk<ActionResolver>(relaxed = true)
    private val actionCache = mockk<ActionCache>(relaxed = true)
    private val mappingRepository = mockk<MappingRepository>(relaxed = true)
    private val smsWriter = mockk<SmsContentProviderWriter>(relaxed = true)
    private val sendBudget = mockk<SendBudget>(relaxed = true)
    private val roleManager = mockk<DefaultSmsRoleManager>(relaxed = true)
    private val bluetooth = mockk<BluetoothConnectionChecker>(relaxed = true)
    private val settingsStore = mockk<SettingsStore>(relaxed = true)
    private val logBuffer = mockk<LogBuffer>(relaxed = true)
    private val sentReplyLedger = mockk<SentReplyLedger>()

    private lateinit var capture: NotificationCapture

    private val extracted = ExtractedMessage(
        senderName = "Anna",
        body = "Hallo!",
        conversationLabel = "Anna",
        isGroup = false,
        conversationKey = "com.whatsapp::id::abc"
    )

    @Before
    fun setUp() {
        capture = NotificationCapture(
            whitelist, extractor, actionResolver, actionCache, mappingRepository,
            smsWriter, sendBudget, roleManager, bluetooth, settingsStore, logBuffer, sentReplyLedger
        )
        coEvery { whitelist.allow(any()) } returns true
        every { extractor.extract(any()) } returns extracted
        coEvery { roleManager.isDefault() } returns true
        coEvery { bluetooth.isTeslaConnected() } returns true
        coEvery { sendBudget.checkAndIncrement() } returns true
        coEvery { settingsStore.mappingTtlHours() } returns 24
        every { sentReplyLedger.isRecentReply(any(), any()) } returns false
        coEvery { mappingRepository.allocateOrReuse(any(), any(), any(), any()) } returns ChannelMapping(
            mappingId = 42L,
            channel = ChannelId.NOTIFICATION,
            fakeAddress = "+888000000042",
            conversationKey = extracted.conversationKey,
            payload = ChannelPayload.Notification("com.whatsapp", "key", "input", "Anna", "Anna"),
            createdAt = 0L, expiresAt = Long.MAX_VALUE, lastUsedAt = null,
            replyCount = 0, replyable = true
        )
        coEvery { smsWriter.injectIncoming(any(), any(), any(), any()) } returns mockk()
    }

    private fun sbnWith(flags: Int): StatusBarNotification {
        val notification = NotificationCompat.Builder(context, "ch")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
            .apply { this.flags = this.flags or flags }
        return mockk<StatusBarNotification> {
            every { key } returns "0|com.whatsapp|1|null|10467"
            every { packageName } returns "com.whatsapp"
            every { this@mockk.notification } returns notification
            every { postTime } returns 1_000L
        }
    }

    @Test
    fun `group summary notification is dropped before mapping and budget`() = runTest {
        capture.onPosted(sbnWith(Notification.FLAG_GROUP_SUMMARY))

        coVerify(exactly = 0) { mappingRepository.allocateOrReuse(any(), any(), any(), any()) }
        coVerify(exactly = 0) { sendBudget.checkAndIncrement() }
        coVerify(exactly = 0) { smsWriter.injectIncoming(any(), any(), any(), any()) }
    }

    @Test
    fun `regular notification passes through to inject`() = runTest {
        capture.onPosted(sbnWith(0))

        coVerify(exactly = 1) { mappingRepository.allocateOrReuse(any(), any(), any(), any()) }
        coVerify(exactly = 1) { smsWriter.injectIncoming("+888000000042", "Hallo!", 1_000L, "Anna") }
    }

    @Test
    fun `body matching a recent own reply is dropped before mapping and budget`() = runTest {
        every { sentReplyLedger.isRecentReply("com.whatsapp", "Hallo!") } returns true

        capture.onPosted(sbnWith(0))

        coVerify(exactly = 0) { mappingRepository.allocateOrReuse(any(), any(), any(), any()) }
        coVerify(exactly = 0) { sendBudget.checkAndIncrement() }
    }
}
