package io.github.lycheeappf.tmm.ui.screen.onboarding

import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.data.store.SettingsStore
import io.github.lycheeappf.tmm.domain.tesla.TeslaDevice
import io.github.lycheeappf.tmm.platform.bluetooth.BluetoothConnectionChecker
import io.github.lycheeappf.tmm.platform.bluetooth.PairedBtDevice
import io.github.lycheeappf.tmm.platform.bluetooth.TeslaDeviceStatus
import io.github.lycheeappf.tmm.platform.permission.PermissionGate
import io.github.lycheeappf.tmm.platform.role.DefaultSmsRoleManager
import io.mockk.coEvery
import io.mockk.coVerify
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
 * Sichert den Multi-Tesla-Teil des Onboardings (geteilte Karte mit den Settings):
 * die Geräteauswahl landet als Liste im Store, und die Zeilen-Zustände des
 * Checkers werden 1:1 in den UiState gespiegelt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val context = mockk<android.content.Context>(relaxed = true)
    private val roleManager = mockk<DefaultSmsRoleManager>(relaxed = true)
    private val permissionGate = mockk<PermissionGate>(relaxed = true)
    private val settingsStore = mockk<SettingsStore>(relaxed = true)
    private val preFlightTester = mockk<PreFlightTester>(relaxed = true)
    private val bluetoothConnectionChecker = mockk<BluetoothConnectionChecker>(relaxed = true)

    private fun viewModel() = OnboardingViewModel(
        context, roleManager, permissionGate, settingsStore, preFlightTester,
        bluetoothConnectionChecker, dispatcher
    )

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `setTeslaDevices persists the picked devices as a TeslaDevice list`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.setTeslaDevices(listOf(PairedBtDevice("AA:BB:CC:DD:EE:FF", "Model Y")))
        advanceUntilIdle()

        coVerify(exactly = 1) { settingsStore.setTeslaDevices(listOf(TeslaDevice("AA:BB:CC:DD:EE:FF", "Model Y"))) }
    }

    @Test
    fun `removeTeslaDevice removes exactly that device`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.removeTeslaDevice("AA:BB:CC:DD:EE:FF")
        advanceUntilIdle()

        coVerify(exactly = 1) { settingsStore.removeTeslaDevice("AA:BB:CC:DD:EE:FF") }
    }

    @Test
    fun `refresh mirrors the tesla device statuses into the ui state`() = runTest(dispatcher) {
        val statuses = listOf(
            TeslaDeviceStatus(TeslaDevice("AA:BB:CC:DD:EE:FF", "Model Y"), missing = false, connected = true)
        )
        coEvery { bluetoothConnectionChecker.teslaDeviceStatuses() } returns statuses

        val vm = viewModel()
        advanceUntilIdle()

        assertThat(vm.uiState.value.teslaDevices).isEqualTo(statuses)
    }
}
