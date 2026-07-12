package io.github.lycheeappf.tmm.sms.outbound

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import io.github.lycheeappf.tmm.channel.llm.InjectedMessageLedger
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.data.repository.ReplyHistoryRecorder
import io.github.lycheeappf.tmm.data.store.SettingsStore
import io.github.lycheeappf.tmm.domain.routing.ReplyDispatcher
import io.github.lycheeappf.tmm.sms.send.SelfSendLedger
import io.github.lycheeappf.tmm.ui.screen.onboarding.PreFlightCoordinator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OutboundSmsObserverTest {

    private val resolver = mockk<ContentResolver>()
    private val context = mockk<Context>(relaxed = true) {
        every { contentResolver } returns resolver
    }
    private val classifier = mockk<OutboundSmsClassifier>()
    private val dispatcher = mockk<ReplyDispatcher>(relaxed = true)
    private val failedRowCleaner = mockk<FailedRowCleaner>(relaxed = true)
    private val settingsStore = mockk<SettingsStore>(relaxed = true)
    private val replyHistory = mockk<ReplyHistoryRecorder>(relaxed = true)
    private val preFlight = mockk<PreFlightCoordinator>()
    private val logBuffer = mockk<LogBuffer>(relaxed = true)
    private val injectedLedger = mockk<InjectedMessageLedger>()
    private val selfSendLedger = mockk<SelfSendLedger>()

    private fun failedRealSmsCursor(): MatrixCursor =
        MatrixCursor(arrayOf("_id", "address", "body", "type", "date")).apply {
            addRow(arrayOf(9460L, "+491711234567", "echte SMS", 5, 1_000L))
        }

    @Test
    fun `NotOurs row is logged once, not on every pass`() = runTest {
        coEvery { settingsStore.lastSeenOutboxId() } returns 9450L
        every { resolver.query(any(), any(), any(), any(), any()) } answers { failedRealSmsCursor() }
        every { preFlight.isReservedForPreflight(any()) } returns false
        every { injectedLedger.shouldIgnoreOutbound(any(), any()) } returns false
        every { selfSendLedger.isSelfSend(any<Long>()) } returns false
        every { selfSendLedger.isSelfSend(any<String>(), any<String>()) } returns false
        coEvery { classifier.classify(any()) } returns OutboundSmsClassifier.Classification.NotOurs

        val observer = OutboundSmsObserver(
            context, classifier, dispatcher, failedRowCleaner, settingsStore,
            replyHistory, preFlight, logBuffer, injectedLedger, selfSendLedger,
            StandardTestDispatcher(testScheduler)
        )

        observer.processChanges()
        observer.processChanges() // zweiter ContentObserver-Tick, gleiche Row, gleicher Type

        verify(exactly = 1) { logBuffer.info(any(), match { it.startsWith("Outbox-Row #9460") }) }
        verify(exactly = 1) { logBuffer.info(any(), match { it.contains("NotOurs") }) }
    }
}
