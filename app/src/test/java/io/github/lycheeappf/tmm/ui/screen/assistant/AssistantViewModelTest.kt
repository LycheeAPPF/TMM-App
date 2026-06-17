package io.github.lycheeappf.tmm.ui.screen.assistant

import io.github.lycheeappf.tmm.channel.llm.AssistantContactProvisioner
import io.github.lycheeappf.tmm.channel.llm.AssistantTriggerCoordinator
import io.github.lycheeappf.tmm.contact.TeslaContactResync
import io.github.lycheeappf.tmm.core.security.ApiKeyStore
import io.github.lycheeappf.tmm.data.store.AssistantPreferencesStore
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Sichert den Apply-Pfad des Tesla-Anzeigenamens
 * ([AssistantViewModel.applyAssistantName]): der Name wird erst persistiert und DANN
 * ein Tesla-Resync erzwungen (Reihenfolge ist load-bearing — der Backfill liest die
 * Pref), die Eingabe wird getrimmt, und leere Eingaben werden ignoriert.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssistantViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val prefs = mockk<AssistantPreferencesStore>(relaxed = true)
    private val apiKeyStore = mockk<ApiKeyStore>(relaxed = true)
    private val coordinator = mockk<AssistantTriggerCoordinator>(relaxed = true)
    private val contactProvisioner = mockk<AssistantContactProvisioner>(relaxed = true)
    private val teslaContactResync = mockk<TeslaContactResync>(relaxed = true)

    private fun viewModel() = AssistantViewModel(
        prefs, apiKeyStore, coordinator, contactProvisioner, teslaContactResync, dispatcher
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `applyAssistantName persists name then forces tesla resync`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle() // init refresh() abwarten

        vm.applyAssistantName("Walter Grok")
        advanceUntilIdle()

        coVerifyOrder {
            prefs.setAssistantDisplayName("Walter Grok")
            teslaContactResync.force()
        }
    }

    @Test
    fun `applyAssistantName trims surrounding whitespace`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.applyAssistantName("  xAI Grok  ")
        advanceUntilIdle()

        coVerify { prefs.setAssistantDisplayName("xAI Grok") }
    }

    @Test
    fun `applyAssistantName ignores blank input`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.applyAssistantName("   ")
        advanceUntilIdle()

        coVerify(exactly = 0) { prefs.setAssistantDisplayName(any()) }
        coVerify(exactly = 0) { teslaContactResync.force() }
    }
}
