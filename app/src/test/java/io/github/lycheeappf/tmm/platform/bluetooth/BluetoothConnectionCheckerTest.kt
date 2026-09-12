package io.github.lycheeappf.tmm.platform.bluetooth

import android.bluetooth.BluetoothManager
import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.data.store.SettingsStore
import io.github.lycheeappf.tmm.domain.tesla.TeslaDevice
import io.github.lycheeappf.tmm.platform.permission.PermissionGate
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Sichert den Fail-Open-Vertrag von [BluetoothConnectionChecker.isTeslaConnected]:
 * Solange kein Gerät gewählt ist, die BLUETOOTH_CONNECT-Permission fehlt oder kein
 * Adapter existiert, MUSS `true` (= weiterleiten wie früher) zurückkommen, damit die
 * Brücke nie stillschweigend ausfällt — auch mit mehreren gewählten Teslas.
 *
 * Die neuen Abfragen für Resolver und UI sind dagegen NICHT fail-open:
 * [BluetoothConnectionChecker.connectedTeslaDevices] liefert leer, wenn nichts
 * bestimmbar ist, und [BluetoothConnectionChecker.teslaDeviceStatuses] meldet ohne
 * Adapter kein `missing` (sonst False-Positive „nicht mehr gekoppelt").
 *
 * Die aktiv-gateenden Branches (BT aus, MAC-Match gegen connectedDevices) brauchen
 * einen echten BluetoothAdapter und werden über manuelle Geräte-Tests verifiziert.
 */
class BluetoothConnectionCheckerTest {

    private val context = mockk<Context>(relaxed = true)
    private val store = mockk<SettingsStore>(relaxed = true)
    private val permissionGate = mockk<PermissionGate>(relaxed = true)

    private val modelY = TeslaDevice("AA:BB:CC:DD:EE:FF", "Model Y")
    private val model3 = TeslaDevice("11:22:33:44:55:66", "Model 3", vin = "5YJ3E1EA7KF000001", vehicleId = 42L)

    private fun checker(): BluetoothConnectionChecker {
        // Kein BluetoothManager → adapter == null → die Adapter-Fail-Open-Branches greifen.
        every { context.getSystemService(BluetoothManager::class.java) } returns null
        return BluetoothConnectionChecker(context, store, permissionGate)
    }

    // ---- isTeslaConnected: Fail-Open-Leiter ----------------------------------

    @Test
    fun `no device selected fails open (forwards)`() = runTest {
        coEvery { store.teslaDevices() } returns emptyList()

        assertThat(checker().isTeslaConnected()).isTrue()
    }

    @Test
    fun `devices selected but permission missing fails open`() = runTest {
        coEvery { store.teslaDevices() } returns listOf(modelY, model3)
        every { permissionGate.hasBluetoothConnect() } returns false

        assertThat(checker().isTeslaConnected()).isTrue()
    }

    @Test
    fun `devices selected and permission granted but no adapter fails open`() = runTest {
        coEvery { store.teslaDevices() } returns listOf(modelY, model3)
        every { permissionGate.hasBluetoothConnect() } returns true

        assertThat(checker().isTeslaConnected()).isTrue()
    }

    // ---- connectedTeslaDevices: nicht fail-open ------------------------------

    @Test
    fun `connectedTeslaDevices is empty without permission`() = runTest {
        coEvery { store.teslaDevices() } returns listOf(modelY, model3)
        every { permissionGate.hasBluetoothConnect() } returns false

        assertThat(checker().connectedTeslaDevices()).isEmpty()
    }

    @Test
    fun `connectedTeslaDevices is empty without adapter`() = runTest {
        coEvery { store.teslaDevices() } returns listOf(modelY, model3)
        every { permissionGate.hasBluetoothConnect() } returns true

        assertThat(checker().connectedTeslaDevices()).isEmpty()
    }

    // ---- teslaDeviceStatuses: UI-Zeilen ---------------------------------------

    @Test
    fun `teslaDeviceStatuses lists every configured device in order`() = runTest {
        coEvery { store.teslaDevices() } returns listOf(modelY, model3)
        every { permissionGate.hasBluetoothConnect() } returns true

        assertThat(checker().teslaDeviceStatuses().map { it.device })
            .containsExactly(modelY, model3).inOrder()
    }

    @Test
    fun `teslaDeviceStatuses reports neither missing nor connected when no adapter exists`() = runTest {
        coEvery { store.teslaDevices() } returns listOf(modelY)
        every { permissionGate.hasBluetoothConnect() } returns true

        val status = checker().teslaDeviceStatuses().single()

        // Ohne Adapter ist „gekoppelt?" nicht bestimmbar → KEIN False-Positive.
        assertThat(status.missing).isFalse()
        assertThat(status.connected).isFalse()
    }

    @Test
    fun `teslaDeviceStatuses reports nothing missing without permission`() = runTest {
        coEvery { store.teslaDevices() } returns listOf(modelY)
        every { permissionGate.hasBluetoothConnect() } returns false

        assertThat(checker().teslaDeviceStatuses().single().missing).isFalse()
    }

    // ---- pairedDevices ---------------------------------------------------------

    @Test
    fun `pairedDevices is empty without permission`() {
        every { permissionGate.hasBluetoothConnect() } returns false

        assertThat(checker().pairedDevices()).isEmpty()
    }

    @Test
    fun `pairedDevices is empty without adapter`() {
        every { permissionGate.hasBluetoothConnect() } returns true

        assertThat(checker().pairedDevices()).isEmpty()
    }
}
