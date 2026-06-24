package io.github.lycheeappf.tmm.ui.screen.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.contact.ContactDiagnostics
import io.github.lycheeappf.tmm.core.util.DiagnosticsExporter
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.data.db.MappingDao
import io.github.lycheeappf.tmm.data.db.ReplyHistoryDao
import io.github.lycheeappf.tmm.data.store.AssistantPreferencesStore
import io.github.lycheeappf.tmm.domain.repository.MappingRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DiagnosticsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mappingDao = mockk<MappingDao>(relaxed = true)
    private val replyHistoryDao = mockk<ReplyHistoryDao>(relaxed = true)
    private val exporter = mockk<DiagnosticsExporter>()
    private val logBuffer = mockk<LogBuffer>(relaxed = true)
    private val contactDiagnostics = mockk<ContactDiagnostics>(relaxed = true)
    private val mappingRepository = mockk<MappingRepository>(relaxed = true)
    private val assistantPrefs = mockk<AssistantPreferencesStore>(relaxed = true)

    private fun vm() = DiagnosticsViewModel(
        context, mappingDao, exporter, replyHistoryDao, logBuffer,
        contactDiagnostics, mappingRepository, assistantPrefs, dispatcher
    )

    @Test fun `shareDiagnostics emits Share on success`() = runTest(dispatcher) {
        val file = File(context.cacheDir, "x.json").apply { writeText("{}") }
        coEvery { exporter.exportToCache() } returns file
        val viewModel = vm()

        viewModel.shareDiagnostics()
        advanceUntilIdle()

        assertThat(viewModel.events.first()).isEqualTo(DiagnosticsEvent.Share(file))
    }

    @Test fun `shareDiagnostics emits ExportFailed on error`() = runTest(dispatcher) {
        coEvery { exporter.exportToCache() } throws RuntimeException("boom")
        val viewModel = vm()

        viewModel.shareDiagnostics()
        advanceUntilIdle()

        assertThat(viewModel.events.first()).isEqualTo(DiagnosticsEvent.ExportFailed)
    }
}
