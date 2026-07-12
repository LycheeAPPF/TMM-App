package io.github.lycheeappf.tmm.ui.screen.settings

import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.contact.ContactSyncWriter
import io.github.lycheeappf.tmm.contact.TeslaContactResync
import io.github.lycheeappf.tmm.core.locale.AppLocaleManager
import io.github.lycheeappf.tmm.core.notification.AppNotificationChannels
import io.github.lycheeappf.tmm.core.util.DiagnosticsExporter
import io.github.lycheeappf.tmm.data.store.SettingsStore
import io.github.lycheeappf.tmm.platform.bluetooth.BluetoothConnectionChecker
import io.github.lycheeappf.tmm.platform.permission.PermissionGate
import io.github.lycheeappf.tmm.platform.tesla.api.TeslaVehicleCommandClient
import io.github.lycheeappf.tmm.platform.tesla.auth.TeslaAuthManager
import io.github.lycheeappf.tmm.platform.tesla.auth.TeslaAuthState
import io.github.lycheeappf.tmm.ui.screen.onboarding.PreFlightTester
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Deckt nur den „Diagnose senden"-Pfad ab (ohne Developer-Mode erreichbar). Der
 * restliche SettingsViewModel-State ist nicht Gegenstand dieses Tests.
 */
class SettingsViewModelShareTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val context = mockk<android.content.Context>(relaxed = true)
    private val store = mockk<SettingsStore>(relaxed = true)
    private val contactSyncWriter = mockk<ContactSyncWriter>(relaxed = true)
    private val teslaContactResync = mockk<TeslaContactResync>(relaxed = true)
    private val preFlightTester = mockk<PreFlightTester>(relaxed = true)
    private val appLocaleManager = mockk<AppLocaleManager>(relaxed = true)
    private val notificationChannels = mockk<AppNotificationChannels>(relaxed = true)
    private val exporter = mockk<DiagnosticsExporter>()
    private val permissionGate = mockk<PermissionGate>(relaxed = true)
    private val bluetoothConnectionChecker = mockk<BluetoothConnectionChecker>(relaxed = true)

    // Konkrete Manager-Klasse: state wird im init des ViewModels collected —
    // einen echten Flow liefern, sonst hinge der Collector auf einem Mock-Flow.
    // Der Rest bleibt relaxed.
    private val teslaAuthManager = mockk<TeslaAuthManager>(relaxed = true) {
        every { state } returns MutableStateFlow(TeslaAuthState.NotAuthenticated)
    }
    private val teslaCommandClient = mockk<TeslaVehicleCommandClient>(relaxed = true)

    private fun vm() = SettingsViewModel(
        context, store, contactSyncWriter, teslaContactResync, preFlightTester,
        appLocaleManager, notificationChannels, exporter,
        permissionGate, bluetoothConnectionChecker, teslaAuthManager,
        teslaCommandClient, dispatcher
    )

    @Test fun `shareDiagnostics emits Share on success`() = runTest(dispatcher) {
        val file = File.createTempFile("diag", ".json").apply { writeText("{}") }
        coEvery { exporter.exportToCache() } returns file
        val viewModel = vm()
        advanceUntilIdle() // init refresh() abarbeiten

        viewModel.shareDiagnostics()
        advanceUntilIdle()

        assertThat(viewModel.events.first()).isEqualTo(SettingsEvent.Share(file))
    }

    @Test fun `shareDiagnostics emits ExportFailed on error`() = runTest(dispatcher) {
        coEvery { exporter.exportToCache() } throws RuntimeException("boom")
        val viewModel = vm()
        advanceUntilIdle()

        viewModel.shareDiagnostics()
        advanceUntilIdle()

        assertThat(viewModel.events.first()).isEqualTo(SettingsEvent.ExportFailed)
    }
}
