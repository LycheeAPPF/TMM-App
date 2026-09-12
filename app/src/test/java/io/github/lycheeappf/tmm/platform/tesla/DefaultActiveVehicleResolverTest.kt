package io.github.lycheeappf.tmm.platform.tesla

import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.core.util.LogFileStore
import io.github.lycheeappf.tmm.data.store.SettingsStore
import io.github.lycheeappf.tmm.data.store.TeslaTokenStore
import io.github.lycheeappf.tmm.domain.tesla.TeslaDevice
import io.github.lycheeappf.tmm.domain.tesla.VehicleRef
import io.github.lycheeappf.tmm.platform.bluetooth.BluetoothConnectionChecker
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Sichert die Auflösungs-Reihenfolge von [DefaultActiveVehicleResolver]: das
 * verbundene, verknüpfte Auto gewinnt; sonst das Standard-Fahrzeug (die alte
 * Einzelauswahl in [TeslaTokenStore]); sonst nichts. Ein verbundenes, aber
 * unverknüpftes Auto ändert nichts am heutigen Verhalten (Standard). Der Fallback
 * bei vorhandenen Links wird als Warnung geloggt — ohne VIN/MAC (LogBuffer-Regel).
 */
class DefaultActiveVehicleResolverTest {

    @get:Rule val tmp = TemporaryFolder()

    private val checker: BluetoothConnectionChecker = mockk()
    private val tokenStore: TeslaTokenStore = mockk()
    private val settingsStore: SettingsStore = mockk()
    private lateinit var logBuffer: LogBuffer
    private lateinit var resolver: DefaultActiveVehicleResolver

    private val modelY = TeslaDevice("AA:BB:CC:DD:EE:FF", "Model Y")
    private val model3 = TeslaDevice("11:22:33:44:55:66", "Model 3", vin = LINKED_VIN, vehicleId = 42L)

    @Before fun setup() {
        logBuffer = LogBuffer(
            LogFileStore(File(tmp.root, "diagnostics"), UnconfinedTestDispatcher()),
            UnconfinedTestDispatcher()
        )
        coEvery { checker.connectedTeslaDevices() } returns emptyList()
        coEvery { settingsStore.teslaDevices() } returns listOf(modelY, model3)
        coEvery { tokenStore.readSelectedVin() } returns DEFAULT_VIN
        coEvery { tokenStore.readSelectedVehicleId() } returns 7L
        resolver = DefaultActiveVehicleResolver(checker, tokenStore, settingsStore, logBuffer)
    }

    private fun loggedText() = logBuffer.snapshot().joinToString("\n") { "${it.tag} ${it.message}" }

    @Test fun `connected linked device wins over the default vehicle`() = runTest {
        coEvery { checker.connectedTeslaDevices() } returns listOf(model3)

        assertThat(resolver.resolve()).isEqualTo(VehicleRef(LINKED_VIN, 42L))
        assertThat(logBuffer.snapshot()).isEmpty()
    }

    @Test fun `connected but unlinked device falls back to the default vehicle`() = runTest {
        coEvery { checker.connectedTeslaDevices() } returns listOf(modelY)

        assertThat(resolver.resolve()).isEqualTo(VehicleRef(DEFAULT_VIN, 7L))
    }

    @Test fun `first connected linked device wins when several are connected`() = runTest {
        coEvery { checker.connectedTeslaDevices() } returns listOf(modelY, model3)

        assertThat(resolver.resolve()).isEqualTo(VehicleRef(LINKED_VIN, 42L))
    }

    @Test fun `no connected device falls back to the default and warns without vin or mac`() = runTest {
        val ref = resolver.resolve()

        assertThat(ref).isEqualTo(VehicleRef(DEFAULT_VIN, 7L))
        assertThat(logBuffer.snapshot()).isNotEmpty()
        val logged = loggedText()
        assertThat(logged).doesNotContain(DEFAULT_VIN)
        assertThat(logged).doesNotContain(LINKED_VIN)
        assertThat(logged).doesNotContain("11:22:33")
        assertThat(logged).doesNotContain("Model 3")
    }

    @Test fun `fallback without any links stays silent`() = runTest {
        coEvery { settingsStore.teslaDevices() } returns listOf(modelY)

        assertThat(resolver.resolve()).isEqualTo(VehicleRef(DEFAULT_VIN, 7L))
        assertThat(logBuffer.snapshot()).isEmpty()
    }

    @Test fun `default vehicle without a stored id carries a null vehicleId`() = runTest {
        coEvery { settingsStore.teslaDevices() } returns emptyList()
        coEvery { tokenStore.readSelectedVehicleId() } returns null

        assertThat(resolver.resolve()).isEqualTo(VehicleRef(DEFAULT_VIN, null))
    }

    @Test fun `nothing configured resolves to null`() = runTest {
        coEvery { settingsStore.teslaDevices() } returns emptyList()
        coEvery { tokenStore.readSelectedVin() } returns null

        assertThat(resolver.resolve()).isNull()
    }

    // ---- isAnyVehicleConfigured (Selbsttest-Stufe TESLA_LOCAL) -----------------

    @Test fun `only a default vehicle counts as configured`() = runTest {
        coEvery { settingsStore.teslaDevices() } returns listOf(modelY)

        assertThat(resolver.isAnyVehicleConfigured()).isTrue()
    }

    @Test fun `only a linked device counts as configured`() = runTest {
        coEvery { tokenStore.readSelectedVin() } returns null

        assertThat(resolver.isAnyVehicleConfigured()).isTrue()
    }

    @Test fun `neither default nor link means nothing is configured`() = runTest {
        coEvery { settingsStore.teslaDevices() } returns listOf(modelY)
        coEvery { tokenStore.readSelectedVin() } returns null

        assertThat(resolver.isAnyVehicleConfigured()).isFalse()
    }

    companion object {
        private const val DEFAULT_VIN = "5YJ3E1EA7KF000000"
        private const val LINKED_VIN = "5YJ3E1EA7KF000001"
    }
}
