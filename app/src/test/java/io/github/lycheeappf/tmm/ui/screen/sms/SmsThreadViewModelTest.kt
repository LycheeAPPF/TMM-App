package io.github.lycheeappf.tmm.ui.screen.sms

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.core.locale.localizedString
import io.github.lycheeappf.tmm.domain.sms.SmsInboxReader
import io.github.lycheeappf.tmm.domain.sms.SmsSender
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Delete-Kontrakt des [SmsThreadViewModel]: Fehlschlag → Feedback, Erfolg → still. */
class SmsThreadViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val context = mockk<Context>(relaxed = true)
    private val reader = mockk<SmsInboxReader>()
    private val sender = mockk<SmsSender>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkStatic("io.github.lycheeappf.tmm.core.locale.LocaleExtKt")
        every { context.localizedString(any()) } returns "delete failed"
        every { reader.changes() } returns emptyFlow()
        coEvery { reader.loadThread(any(), any()) } returns emptyList()
        coEvery { reader.contactName(any()) } returns null
    }

    @After
    fun tearDown() {
        unmockkStatic("io.github.lycheeappf.tmm.core.locale.LocaleExtKt")
        Dispatchers.resetMain()
    }

    private fun vm() = SmsThreadViewModel(
        context,
        SavedStateHandle(mapOf(SmsThreadViewModel.ARG_THREAD_ID to 1L)),
        reader,
        sender,
        dispatcher
    )

    @Test
    fun `failed message delete surfaces feedback`() = runTest(dispatcher) {
        coEvery { reader.deleteMessage(42) } returns false
        val viewModel = vm()

        viewModel.deleteMessage(42)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.feedback).isEqualTo("delete failed")
    }

    @Test
    fun `successful message delete stays silent`() = runTest(dispatcher) {
        coEvery { reader.deleteMessage(42) } returns true
        val viewModel = vm()

        viewModel.deleteMessage(42)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.feedback).isNull()
    }
}
