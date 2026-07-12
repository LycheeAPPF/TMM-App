package io.github.lycheeappf.tmm.platform.bluetooth

import android.bluetooth.BluetoothManager
import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.data.store.SettingsStore
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
 * Brücke nie stillschweigend ausfällt. Die aktiv-gateenden Branches (BT aus, MAC-Match
 * gegen connectedDevices) brauchen einen echten BluetoothAdapter und sind hier bewusst
 * nicht abgedeckt — sie werden über manuelle Geräte-Tests verifiziert.
 */
class BluetoothConnectionCheckerTest {

    private val context = mockk<Context>(relaxed = true)
    private val store = mockk<SettingsStore>(relaxed = true)
    private val permissionGate = mockk<PermissionGate>(relaxed = true)

    private fun checker(): BluetoothConnectionChecker {
        // Kein BluetoothManager → adapter == null → die Adapter-Fail-Open-Branches greifen.
        every { context.getSystemService(BluetoothManager::class.java) } returns null
        return BluetoothConnectionChecker(context, store, permissionGate)
    }

    @Test
    fun `no device selected fails open (forwards)`() = runTest {
        coEvery { store.teslaBtAddress() } returns null

        assertThat(checker().isTeslaConnected()).isTrue()
    }

    @Test
    fun `device selected but permission missing fails open`() = runTest {
        coEvery { store.teslaBtAddress() } returns "AA:BB:CC:DD:EE:FF"
        every { permissionGate.hasBluetoothConnect() } returns false

        assertThat(checker().isTeslaConnected()).isTrue()
    }

    @Test
    fun `device selected and permission granted but no adapter fails open`() = runTest {
        coEvery { store.teslaBtAddress() } returns "AA:BB:CC:DD:EE:FF"
        every { permissionGate.hasBluetoothConnect() } returns true

        assertThat(checker().isTeslaConnected()).isTrue()
    }

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
