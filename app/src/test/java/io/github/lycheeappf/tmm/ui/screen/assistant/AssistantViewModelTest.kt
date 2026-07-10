package io.github.lycheeappf.tmm.ui.screen.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.channel.llm.AssistantContactProvisioner
import io.github.lycheeappf.tmm.channel.llm.AssistantTriggerCoordinator
import io.github.lycheeappf.tmm.channel.llm.E2eResult
import io.github.lycheeappf.tmm.channel.llm.GrokKeyTester
import io.github.lycheeappf.tmm.channel.llm.GrokSelfTester
import io.github.lycheeappf.tmm.channel.llm.KeyTestOutcome
import io.github.lycheeappf.tmm.channel.llm.NavCheck
import io.github.lycheeappf.tmm.channel.llm.PositionEcho
import io.github.lycheeappf.tmm.channel.llm.PositionLocalResult
import io.github.lycheeappf.tmm.channel.llm.SelfTestEvent
import io.github.lycheeappf.tmm.channel.llm.SelfTestStage
import io.github.lycheeappf.tmm.contact.TeslaContactResync
import io.github.lycheeappf.tmm.core.security.ApiKeyStore
import io.github.lycheeappf.tmm.data.store.AssistantPreferencesStore
import io.github.lycheeappf.tmm.platform.permission.PermissionGate
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
    private val selfTester = mockk<GrokSelfTester> {
        every { run(any<String>()) } returns emptyFlow()
    }
    private val coordinator = mockk<AssistantTriggerCoordinator>(relaxed = true)
    private val contactProvisioner = mockk<AssistantContactProvisioner>(relaxed = true)
    private val teslaContactResync = mockk<TeslaContactResync>(relaxed = true)
    private val permissionGate = mockk<PermissionGate>(relaxed = true)

    private fun viewModel() = AssistantViewModel(
        context, prefs, apiKeyStore, keyTester, selfTester, coordinator, contactProvisioner,
        teslaContactResync, permissionGate, dispatcher
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

    @Test
    fun `setLocationContextEnabled persists and mirrors to ui state`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.setLocationContextEnabled(true)
        advanceUntilIdle()

        coVerify { prefs.setLocationContextEnabled(true) }
        assertThat(vm.uiState.value.locationContextEnabled).isTrue()
    }

    @Test
    fun `resetSystemPromptToDefault cancels a pending debounced prompt persist`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        // Tippen startet den 350-ms-Debounce-Job; der Reset direkt danach muss ihn
        // abbrechen — sonst überschreibt der verspätete Persist den Reset wieder.
        vm.setSystemPrompt("mein eigener Prompt")
        vm.resetSystemPromptToDefault()
        advanceUntilIdle()

        coVerify(exactly = 0) { prefs.setSystemPrompt(any()) }
        coVerify { prefs.resetSystemPromptToDefault() }
        assertThat(vm.uiState.value.isSystemPromptCustomized).isFalse()
    }

    @Test
    fun `location permission level NONE without any location grant`() = runTest(dispatcher) {
        every { permissionGate.hasLocationAccess() } returns false

        val vm = viewModel()
        advanceUntilIdle()

        assertThat(vm.uiState.value.locationPermission).isEqualTo(LocationPermissionLevel.NONE)
    }

    @Test
    fun `location permission level WHILE_IN_USE without background grant`() = runTest(dispatcher) {
        every { permissionGate.hasLocationAccess() } returns true
        every { permissionGate.hasBackgroundLocationAccess() } returns false

        val vm = viewModel()
        advanceUntilIdle()

        assertThat(vm.uiState.value.locationPermission)
            .isEqualTo(LocationPermissionLevel.WHILE_IN_USE)
    }

    @Test
    fun `location permission level ALWAYS with background grant and refresh picks up changes`() =
        runTest(dispatcher) {
            every { permissionGate.hasLocationAccess() } returns true
            every { permissionGate.hasBackgroundLocationAccess() } returns true

            val vm = viewModel()
            advanceUntilIdle()
            assertThat(vm.uiState.value.locationPermission).isEqualTo(LocationPermissionLevel.ALWAYS)

            // Rückkehr aus den System-Einstellungen (Grant entzogen) → refresh()
            // muss den neuen Stand spiegeln.
            every { permissionGate.hasLocationAccess() } returns false
            vm.refresh()
            advanceUntilIdle()
            assertThat(vm.uiState.value.locationPermission).isEqualTo(LocationPermissionLevel.NONE)
        }

    @Test
    fun `runSelfTest collects stage events into ui state and clears running at the end`() = runTest(dispatcher) {
        every { selfTester.run("Alexanderplatz, Berlin") } returns flowOf(
            SelfTestEvent.StageRunning(SelfTestStage.KEY),
            SelfTestEvent.KeyResult(KeyTestOutcome.VALID),
            SelfTestEvent.StageRunning(SelfTestStage.POSITION),
            SelfTestEvent.PositionResult(PositionLocalResult.Disabled),
            SelfTestEvent.StageRunning(SelfTestStage.TESLA_LOCAL),
            SelfTestEvent.TeslaLocalResult(credentialsSet = true, vinSelected = true),
            SelfTestEvent.StageRunning(SelfTestStage.E2E),
            SelfTestEvent.E2eDone(
                E2eResult.Completed(
                    nav = NavCheck.CalledOk("Alexanderplatz, Berlin"),
                    echo = PositionEcho.SKIPPED,
                    answer = "NO POSITION"
                )
            )
        )
        val vm = viewModel()
        advanceUntilIdle()

        vm.runSelfTest()
        advanceUntilIdle()

        val selfTest = vm.uiState.value.selfTest
        assertThat(selfTest.running).isFalse()
        assertThat(selfTest.currentStage).isNull()
        assertThat(selfTest.key.value).isEqualTo(KeyTestOutcome.VALID)
        assertThat(selfTest.position.value).isEqualTo(PositionLocalResult.Disabled)
        assertThat(selfTest.teslaLocal.value).isEqualTo(true to true)
        assertThat((selfTest.e2e.value as E2eResult.Completed).nav)
            .isEqualTo(NavCheck.CalledOk("Alexanderplatz, Berlin"))
    }

    @Test
    fun `skipped stages are marked skipped not merely empty`() = runTest(dispatcher) {
        every { selfTester.run(any()) } returns flowOf(
            SelfTestEvent.KeyResult(KeyTestOutcome.AUTH_ERROR),
            SelfTestEvent.StageSkipped(SelfTestStage.POSITION),
            SelfTestEvent.StageSkipped(SelfTestStage.TESLA_LOCAL),
            SelfTestEvent.StageSkipped(SelfTestStage.E2E)
        )
        val vm = viewModel()
        advanceUntilIdle()

        vm.runSelfTest()
        advanceUntilIdle()

        val selfTest = vm.uiState.value.selfTest
        assertThat(selfTest.position.skipped).isTrue()
        assertThat(selfTest.teslaLocal.skipped).isTrue()
        assertThat(selfTest.e2e.skipped).isTrue()
        assertThat(selfTest.position.value).isNull()
    }

    @Test
    fun `runSelfTest is a no-op while a run is already in flight`() = runTest(dispatcher) {
        every { selfTester.run(any()) } returns flow {
            emit(SelfTestEvent.StageRunning(SelfTestStage.KEY))
            awaitCancellation()
        }
        val vm = viewModel()
        advanceUntilIdle()

        vm.runSelfTest()
        advanceUntilIdle()
        vm.runSelfTest()
        advanceUntilIdle()

        verify(exactly = 1) { selfTester.run(any()) }
    }

    @Test
    fun `testApiKey is rejected while the self-test runs`() = runTest(dispatcher) {
        every { selfTester.run(any()) } returns flow {
            emit(SelfTestEvent.StageRunning(SelfTestStage.KEY))
            awaitCancellation()
        }
        val vm = viewModel()
        advanceUntilIdle()

        vm.runSelfTest()
        advanceUntilIdle()
        vm.testApiKey()
        advanceUntilIdle()

        coVerify(exactly = 0) { keyTester.run() }
    }

    @Test
    fun `refresh preserves self-test destination and results`() = runTest(dispatcher) {
        every { selfTester.run(any()) } returns flowOf(
            SelfTestEvent.KeyResult(KeyTestOutcome.VALID)
        )
        val vm = viewModel()
        advanceUntilIdle()

        vm.setSelfTestDestination("Brandenburger Tor, Berlin")
        vm.runSelfTest()
        advanceUntilIdle()
        vm.refresh()
        advanceUntilIdle()

        assertThat(vm.uiState.value.selfTest.destination).isEqualTo("Brandenburger Tor, Berlin")
        assertThat(vm.uiState.value.selfTest.key.value).isEqualTo(KeyTestOutcome.VALID)
    }
}
