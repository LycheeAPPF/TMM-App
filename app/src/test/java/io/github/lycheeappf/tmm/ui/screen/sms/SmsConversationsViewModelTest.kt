package io.github.lycheeappf.tmm.ui.screen.sms

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.core.locale.localizedString
import io.github.lycheeappf.tmm.domain.sms.SmsInboxReader
import io.github.lycheeappf.tmm.platform.permission.PermissionGate
import io.github.lycheeappf.tmm.platform.role.DefaultSmsRoleManager
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

/** Delete-Kontrakt des [SmsConversationsViewModel]: Fehlschlag → Feedback, Erfolg → still. */
class SmsConversationsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val context = mockk<Context>(relaxed = true)
    private val reader = mockk<SmsInboxReader>()
    private val roleManager = mockk<DefaultSmsRoleManager>(relaxed = true)
    private val permissionGate = mockk<PermissionGate>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // LocaleExt ist eine Top-Level-Extension → statisch mocken, damit kein
        // echter Configuration-/LocaleManager-Zugriff im JVM-Test passiert.
        mockkStatic("io.github.lycheeappf.tmm.core.locale.LocaleExtKt")
        every { context.localizedString(any()) } returns "delete failed"
        every { reader.changes() } returns emptyFlow()
        coEvery { reader.loadConversations(any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        unmockkStatic("io.github.lycheeappf.tmm.core.locale.LocaleExtKt")
        Dispatchers.resetMain()
    }

    private fun vm() = SmsConversationsViewModel(context, reader, roleManager, permissionGate, dispatcher)

    @Test
    fun `failed thread delete surfaces feedback`() = runTest(dispatcher) {
        coEvery { reader.deleteThread(7) } returns false
        val viewModel = vm()

        viewModel.deleteThread(7)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.feedback).isEqualTo("delete failed")
    }

    @Test
    fun `successful thread delete stays silent`() = runTest(dispatcher) {
        coEvery { reader.deleteThread(7) } returns true
        val viewModel = vm()

        viewModel.deleteThread(7)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.feedback).isNull()
    }

    @Test
    fun `consumeFeedback clears feedback`() = runTest(dispatcher) {
        coEvery { reader.deleteThread(7) } returns false
        val viewModel = vm()

        viewModel.deleteThread(7)
        advanceUntilIdle()
        viewModel.consumeFeedback()

        assertThat(viewModel.uiState.value.feedback).isNull()
    }
}
