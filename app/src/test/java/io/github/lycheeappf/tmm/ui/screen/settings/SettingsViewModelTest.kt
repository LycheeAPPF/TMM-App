package io.github.lycheeappf.tmm.ui.screen.settings

import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.contact.ContactSyncWriter
import io.github.lycheeappf.tmm.contact.TeslaContactResync
import io.github.lycheeappf.tmm.core.locale.AppLocaleManager
import io.github.lycheeappf.tmm.core.notification.AppNotificationChannels
import io.github.lycheeappf.tmm.data.store.SettingsStore
import io.github.lycheeappf.tmm.platform.bluetooth.BluetoothConnectionChecker
import io.github.lycheeappf.tmm.platform.permission.PermissionGate
import io.github.lycheeappf.tmm.platform.tesla.api.TeslaVehicleCommandClient
import io.github.lycheeappf.tmm.platform.tesla.auth.TeslaAuthManager
import io.github.lycheeappf.tmm.platform.tesla.auth.TeslaAuthState
import io.github.lycheeappf.tmm.ui.screen.onboarding.PreFlightTester
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Sichert die i18n-tragende Verdrahtung von [SettingsViewModel.setLanguage]: der Umschalter
 * MUSS sowohl das App-Locale setzen ([AppLocaleManager.setLanguageTag]) ALS AUCH die
 * Notification-Channels neu anlegen ([AppNotificationChannels.ensure]) — sonst blieben die
 * Channel-Namen in der alten Sprache hängen. Außerdem spiegelt der UiState die aktive Sprache.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val context = mockk<android.content.Context>(relaxed = true)
    private val store = mockk<SettingsStore>(relaxed = true)
    private val contactSyncWriter = mockk<ContactSyncWriter>(relaxed = true)
    private val teslaContactResync = mockk<TeslaContactResync>(relaxed = true)
    private val preFlightTester = mockk<PreFlightTester>(relaxed = true)
    private val appLocaleManager = mockk<AppLocaleManager>(relaxed = true)
    private val notificationChannels = mockk<AppNotificationChannels>(relaxed = true)
    private val diagnosticsExporter = mockk<io.github.lycheeappf.tmm.core.util.DiagnosticsExporter>(relaxed = true)
    private val permissionGate = mockk<PermissionGate>(relaxed = true)
    private val bluetoothConnectionChecker = mockk<BluetoothConnectionChecker>(relaxed = true)

    // Konkrete Manager-Klasse: state wird im init des ViewModels collected —
    // einen echten Flow liefern, sonst hinge der Collector auf einem Mock-Flow.
    // Der Rest bleibt relaxed.
    private val teslaAuthManager = mockk<TeslaAuthManager>(relaxed = true) {
        every { state } returns MutableStateFlow(TeslaAuthState.NotAuthenticated)
    }
    private val teslaCommandClient = mockk<TeslaVehicleCommandClient>(relaxed = true)

    private fun viewModel() = SettingsViewModel(
        context, store, contactSyncWriter, teslaContactResync, preFlightTester,
        appLocaleManager, notificationChannels, diagnosticsExporter,
        permissionGate, bluetoothConnectionChecker, teslaAuthManager,
        teslaCommandClient, dispatcher
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
    fun `setLanguage sets the locale tag AND re-creates the notification channels`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle() // init refresh() abwarten

        vm.setLanguage("en")

        verify(exactly = 1) { appLocaleManager.setLanguageTag("en") }
        // ensure() wird NUR von setLanguage gerufen (nicht im init) → load-bearing für i18n.
        verify(exactly = 1) { notificationChannels.ensure() }
    }

    @Test
    fun `language tag in UiState mirrors AppLocaleManager currentTag`() = runTest(dispatcher) {
        every { appLocaleManager.currentTag() } returns "de"

        val vm = viewModel()
        advanceUntilIdle()

        assertThat(vm.uiState.value.languageTag).isEqualTo("de")
    }

    @Test
    fun `setBudgetEnabled writes the flag to the store`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.setBudgetEnabled(false)
        advanceUntilIdle()

        coVerify(exactly = 1) { store.setSendBudgetEnabled(false) }
    }

    @Test
    fun `selectTeslaDevice persists address and name`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.selectTeslaDevice("AA:BB:CC:DD:EE:FF", "Model Y")
        advanceUntilIdle()

        coVerify(exactly = 1) { store.setTeslaBtDevice("AA:BB:CC:DD:EE:FF", "Model Y") }
    }

    @Test
    fun `clearTeslaDevice removes the stored device`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.clearTeslaDevice()
        advanceUntilIdle()

        coVerify(exactly = 1) { store.clearTeslaBtDevice() }
    }
}
