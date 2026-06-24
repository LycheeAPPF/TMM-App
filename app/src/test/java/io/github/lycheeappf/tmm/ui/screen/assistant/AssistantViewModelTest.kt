package io.github.lycheeappf.tmm.ui.screen.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.channel.llm.AssistantContactProvisioner
import io.github.lycheeappf.tmm.channel.llm.AssistantTriggerCoordinator
import io.github.lycheeappf.tmm.channel.llm.GrokKeyTester
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sichert den Apply-Pfad des zusätzlichen Sprach-Ansprech-Kontakts
 * ([AssistantViewModel.applyVoiceAlias]): aktivieren setzt Enabled+Name und erzwingt
 * DANN den Tesla-Resync; „Aus" setzt nur Enabled=false (kein Name) + Resync; leerer
 * Name bei aktiviert ist ein No-op.
 *
 * Robolectric: die VM löst Feedback-Texte über einen echten Context auf (localizedString).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AssistantViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()
    private val prefs = mockk<AssistantPreferencesStore>(relaxed = true)
    private val apiKeyStore = mockk<ApiKeyStore>(relaxed = true)
    private val keyTester = mockk<GrokKeyTester>(relaxed = true)
    private val coordinator = mockk<AssistantTriggerCoordinator>(relaxed = true)
    private val contactProvisioner = mockk<AssistantContactProvisioner>(relaxed = true)
    private val teslaContactResync = mockk<TeslaContactResync>(relaxed = true)

    private fun viewModel() = AssistantViewModel(
        context, prefs, apiKeyStore, keyTester, coordinator, contactProvisioner, teslaContactResync, dispatcher
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
    fun `applyVoiceAlias enabled persists name then forces resync`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle() // init refresh() abwarten

        vm.applyVoiceAlias(true, "xAI Grok")
        advanceUntilIdle()

        coVerifyOrder {
            prefs.setVoiceAliasEnabled(true)
            prefs.setVoiceAliasName("xAI Grok")
            teslaContactResync.force()
        }
    }

    @Test
    fun `applyVoiceAlias disabled does not set a name but resyncs`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.applyVoiceAlias(false, "ignored")
        advanceUntilIdle()

        coVerify { prefs.setVoiceAliasEnabled(false) }
        coVerify { teslaContactResync.force() }
        coVerify(exactly = 0) { prefs.setVoiceAliasName(any()) }
    }

    @Test
    fun `applyVoiceAlias enabled with blank name is a no-op`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.applyVoiceAlias(true, "   ")
        advanceUntilIdle()

        coVerify(exactly = 0) { prefs.setVoiceAliasEnabled(any()) }
        coVerify(exactly = 0) { prefs.setVoiceAliasName(any()) }
        coVerify(exactly = 0) { teslaContactResync.force() }
    }

    @Test
    fun `setWebSearchEnabled persists and mirrors to ui state`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle() // init refresh() abwarten

        vm.setWebSearchEnabled(true)
        advanceUntilIdle()

        coVerify { prefs.setWebSearchEnabled(true) }
        assertThat(vm.uiState.value.webSearchEnabled).isTrue()
    }

    @Test
    fun `setXSearchEnabled persists and mirrors to ui state`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.setXSearchEnabled(true)
        advanceUntilIdle()

        coVerify { prefs.setXSearchEnabled(true) }
        assertThat(vm.uiState.value.xSearchEnabled).isTrue()
    }
}
