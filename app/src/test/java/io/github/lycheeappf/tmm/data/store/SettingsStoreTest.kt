package io.github.lycheeappf.tmm.data.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.domain.channel.AssistantIdentity
import io.github.lycheeappf.tmm.domain.tesla.TeslaDevice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sichert (1) die Reservierungs-Invariante von [SettingsStore.nextMappingId] und
 * (2) die Multi-Tesla-Geräteliste: Read-through-Migration der alten Einzel-Keys
 * (`tesla_bt_address`/`tesla_bt_name`), atomares Entfernen der Legacy-Keys beim
 * ersten Write, Erhalt der Fahrzeug-Verknüpfung beim Neu-Auswählen sowie die
 * Link-Operationen. DataStore braucht einen echten Context → Robolectric.
 *
 * Der DataStore-Singleton überlebt Testmethoden in derselben Sandbox — jeder Fall
 * räumt die Tesla-Keys deshalb selbst auf (order-unabhängig).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = SettingsStore(context)

    private val legacyAddressKey = stringPreferencesKey("tesla_bt_address")
    private val legacyNameKey = stringPreferencesKey("tesla_bt_name")
    private val devicesKey = stringPreferencesKey("tesla_bt_devices")

    @Before
    fun clearTeslaKeys() = runTest {
        context.dataStore.edit {
            it.remove(legacyAddressKey)
            it.remove(legacyNameKey)
            it.remove(devicesKey)
        }
    }

    private suspend fun seedLegacy(address: String, name: String?) {
        context.dataStore.edit {
            it[legacyAddressKey] = address
            if (name != null) it[legacyNameKey] = name
        }
    }

    private suspend fun rawPrefs() = context.dataStore.data.first()

    @Test
    fun `nextMappingId never returns a reserved id and stays strictly increasing`() = runTest {
        val ids = (1..6).map { store.nextMappingId() }

        // Keine reservierte Id (0 = Grok, 1 = Sprach-Ansprech-Kontakt).
        assertThat(ids.none { it in AssistantIdentity.RESERVED_MAPPING_IDS }).isTrue()
        // Strikt monoton steigend, keine Dopplungen → NOTIFICATION-Vergabe nicht gestört.
        assertThat(ids).isInStrictOrder()
        assertThat(ids.toSet()).hasSize(ids.size)
        // Auf frischem Store beginnt die Vergabe direkt nach den reservierten {0,1}.
        assertThat(ids.first()).isEqualTo(2L)
    }

    // ---- Multi-Tesla: Migration ---------------------------------------------

    @Test
    fun `no devices and no legacy keys yield an empty list`() = runTest {
        assertThat(store.teslaDevices()).isEmpty()
    }

    @Test
    fun `legacy single device is read through as one-element list without rewriting the keys`() = runTest {
        seedLegacy("aa:bb:cc:dd:ee:ff", "Model Y")

        val devices = store.teslaDevices()

        assertThat(devices).containsExactly(TeslaDevice(address = "AA:BB:CC:DD:EE:FF", name = "Model Y"))
        // Read-through: Legacy bleibt, neuer Key wird NICHT beim Lesen geschrieben.
        val prefs = rawPrefs()
        assertThat(prefs[legacyAddressKey]).isEqualTo("aa:bb:cc:dd:ee:ff")
        assertThat(prefs[devicesKey]).isNull()
    }

    @Test
    fun `legacy device without a name falls back to its address as name`() = runTest {
        seedLegacy("AA:BB:CC:DD:EE:FF", name = null)

        assertThat(store.teslaDevices().single().name).isEqualTo("AA:BB:CC:DD:EE:FF")
    }

    @Test
    fun `first write folds the legacy device in and removes the legacy keys`() = runTest {
        seedLegacy("AA:BB:CC:DD:EE:FF", "Model Y")

        store.linkVehicleToDevice(vin = "5YJ3E1EA7KF000001", vehicleId = 42L, address = "AA:BB:CC:DD:EE:FF")

        assertThat(store.teslaDevices()).containsExactly(
            TeslaDevice("AA:BB:CC:DD:EE:FF", "Model Y", vin = "5YJ3E1EA7KF000001", vehicleId = 42L)
        )
        val prefs = rawPrefs()
        assertThat(prefs[legacyAddressKey]).isNull()
        assertThat(prefs[legacyNameKey]).isNull()
        assertThat(prefs[devicesKey]).isNotNull()
    }

    @Test
    fun `removing the migrated legacy device leaves no tesla keys behind`() = runTest {
        seedLegacy("AA:BB:CC:DD:EE:FF", "Model Y")

        store.removeTeslaDevice("aa:bb:cc:dd:ee:ff")

        assertThat(store.teslaDevices()).isEmpty()
        val prefs = rawPrefs()
        assertThat(prefs[legacyAddressKey]).isNull()
        assertThat(prefs[legacyNameKey]).isNull()
        // Leere Liste wird nicht als "[]" persistiert.
        assertThat(prefs[devicesKey]).isNull()
    }

    @Test
    fun `corrupt device json yields an empty list instead of crashing`() = runTest {
        context.dataStore.edit { it[devicesKey] = "{definitely not json" }

        assertThat(store.teslaDevices()).isEmpty()
    }

    // ---- Multi-Tesla: Auswahl -----------------------------------------------

    @Test
    fun `setTeslaDevices keeps the vehicle link of retained devices and drops deselected ones`() = runTest {
        store.setTeslaDevices(
            listOf(TeslaDevice("AA:BB:CC:DD:EE:FF", "Model Y"), TeslaDevice("11:22:33:44:55:66", "Model 3"))
        )
        store.linkVehicleToDevice("5YJ3E1EA7KF000001", 42L, "AA:BB:CC:DD:EE:FF")

        store.setTeslaDevices(
            listOf(TeslaDevice("AA:BB:CC:DD:EE:FF", "Model Y Renamed"), TeslaDevice("99:88:77:66:55:44", "Cybertruck"))
        )

        assertThat(store.teslaDevices()).containsExactly(
            TeslaDevice("AA:BB:CC:DD:EE:FF", "Model Y Renamed", vin = "5YJ3E1EA7KF000001", vehicleId = 42L),
            TeslaDevice("99:88:77:66:55:44", "Cybertruck")
        ).inOrder()
    }

    @Test
    fun `addresses are normalised to uppercase and deduplicated`() = runTest {
        store.setTeslaDevices(
            listOf(TeslaDevice("aa:bb:cc:dd:ee:ff", "Model Y"), TeslaDevice("AA:BB:CC:DD:EE:FF", "Model Y dup"))
        )

        assertThat(store.teslaDevices()).containsExactly(TeslaDevice("AA:BB:CC:DD:EE:FF", "Model Y"))
    }

    @Test
    fun `removeTeslaDevice ignores address case`() = runTest {
        store.setTeslaDevices(listOf(TeslaDevice("AA:BB:CC:DD:EE:FF", "Model Y")))

        store.removeTeslaDevice("aa:bb:cc:dd:ee:ff")

        assertThat(store.teslaDevices()).isEmpty()
    }

    // ---- Multi-Tesla: Fahrzeug-Verknüpfung ----------------------------------

    @Test
    fun `linkVehicleToDevice moves the vin from one device to another`() = runTest {
        store.setTeslaDevices(
            listOf(TeslaDevice("AA:BB:CC:DD:EE:FF", "Model Y"), TeslaDevice("11:22:33:44:55:66", "Model 3"))
        )
        store.linkVehicleToDevice("5YJ3E1EA7KF000001", 42L, "AA:BB:CC:DD:EE:FF")

        store.linkVehicleToDevice("5YJ3E1EA7KF000001", 42L, "11:22:33:44:55:66")

        assertThat(store.teslaDevices()).containsExactly(
            TeslaDevice("AA:BB:CC:DD:EE:FF", "Model Y"),
            TeslaDevice("11:22:33:44:55:66", "Model 3", vin = "5YJ3E1EA7KF000001", vehicleId = 42L)
        ).inOrder()
    }

    @Test
    fun `linking a second vehicle to a device replaces its previous link`() = runTest {
        store.setTeslaDevices(listOf(TeslaDevice("AA:BB:CC:DD:EE:FF", "Model Y")))
        store.linkVehicleToDevice("5YJ3E1EA7KF000001", 42L, "AA:BB:CC:DD:EE:FF")

        store.linkVehicleToDevice("5YJ3E1EA7KF000002", 43L, "AA:BB:CC:DD:EE:FF")

        assertThat(store.teslaDevices().single().vin).isEqualTo("5YJ3E1EA7KF000002")
        assertThat(store.teslaDevices().single().vehicleId).isEqualTo(43L)
    }

    @Test
    fun `linkVehicleToDevice with null address unlinks the vehicle`() = runTest {
        store.setTeslaDevices(listOf(TeslaDevice("AA:BB:CC:DD:EE:FF", "Model Y")))
        store.linkVehicleToDevice("5YJ3E1EA7KF000001", 42L, "AA:BB:CC:DD:EE:FF")

        store.linkVehicleToDevice("5YJ3E1EA7KF000001", 42L, address = null)

        assertThat(store.teslaDevices()).containsExactly(TeslaDevice("AA:BB:CC:DD:EE:FF", "Model Y"))
    }

    @Test
    fun `clearTeslaVehicleLinks removes all links but keeps the devices`() = runTest {
        store.setTeslaDevices(
            listOf(TeslaDevice("AA:BB:CC:DD:EE:FF", "Model Y"), TeslaDevice("11:22:33:44:55:66", "Model 3"))
        )
        store.linkVehicleToDevice("5YJ3E1EA7KF000001", 42L, "AA:BB:CC:DD:EE:FF")
        store.linkVehicleToDevice("5YJ3E1EA7KF000002", 43L, "11:22:33:44:55:66")

        store.clearTeslaVehicleLinks()

        assertThat(store.teslaDevices()).containsExactly(
            TeslaDevice("AA:BB:CC:DD:EE:FF", "Model Y"),
            TeslaDevice("11:22:33:44:55:66", "Model 3")
        ).inOrder()
    }
}
