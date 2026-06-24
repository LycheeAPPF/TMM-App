package io.github.lycheeappf.tmm.platform.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lycheeappf.tmm.data.store.SettingsStore
import io.github.lycheeappf.tmm.platform.permission.PermissionGate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Prüft, ob das Handy aktuell mit dem vom User gewählten Tesla-Gerät per
 * Bluetooth verbunden ist. Damit leitet die App Nachrichten nur weiter, während
 * man tatsächlich im Auto sitzt — sonst landen Fake-SMS nur in der DB und
 * verbrennen unnötig das Tageslimit (MAP zieht sie erst beim nächsten Connect).
 *
 * **Warum Profile statt MAP/PBAP:** Das Handy ist bei MAP/PBAP der *Server*; die
 * passenden Profil-Proxies sind für Dritt-Apps nicht zugänglich. Eine
 * Tesla-Kopplung baut aber immer auch HFP (Headset) und/oder A2DP auf — über
 * diese (öffentlichen) Proxies fragen wir die verbundenen Geräte ab und matchen
 * die gespeicherte MAC.
 *
 * **Fail-Open-Vertrag:** Solange kein Tesla-Gerät gewählt ist, die
 * `BLUETOOTH_CONNECT`-Permission fehlt oder kein BT-Adapter existiert, gibt
 * [isTeslaConnected] `true` zurück — die Brücke leitet dann wie früher rund um
 * die Uhr weiter, statt stillschweigend nichts mehr zu tun. Erst ein gewähltes
 * Gerät + erteilte Permission aktivieren das Verbindungs-Gate.
 */
@Singleton
class BluetoothConnectionChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val permissionGate: PermissionGate
) {

    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    @Volatile private var headsetProxy: BluetoothHeadset? = null
    @Volatile private var a2dpProxy: BluetoothA2dp? = null

    init {
        acquireProxies()
    }

    /**
     * Holt die HFP-/A2DP-Profil-Proxies einmalig. `onServiceConnected` liefert den
     * Proxy asynchron — bis dahin (kurzes Fenster nach Prozessstart) liefert
     * [isTeslaConnected] fail-open `true`. Ein einmal verbundener Proxy spiegelt
     * sofort den aktuellen Verbindungszustand, auch wenn der Tesla schon vor
     * App-Start verbunden war.
     */
    private fun acquireProxies() {
        val a = adapter ?: return
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                when (profile) {
                    BluetoothProfile.HEADSET -> headsetProxy = proxy as? BluetoothHeadset
                    BluetoothProfile.A2DP -> a2dpProxy = proxy as? BluetoothA2dp
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                when (profile) {
                    BluetoothProfile.HEADSET -> headsetProxy = null
                    BluetoothProfile.A2DP -> a2dpProxy = null
                }
            }
        }
        runCatching {
            a.getProfileProxy(context, listener, BluetoothProfile.HEADSET)
            a.getProfileProxy(context, listener, BluetoothProfile.A2DP)
        }.onFailure { Log.w(TAG, "getProfileProxy failed: ${it.message}") }
    }

    /**
     * `true`, wenn das gewählte Tesla-Gerät gerade verbunden ist — oder fail-open
     * (siehe Klassen-Doc), wenn nicht geprüft werden kann/soll. Nur bei
     * gewähltem Gerät + Permission + vorhandenem, eingeschaltetem Adapter wird
     * tatsächlich gegated.
     */
    @SuppressLint("MissingPermission") // runtime-geprüft über permissionGate.hasBluetoothConnect()
    suspend fun isTeslaConnected(): Boolean {
        val target = settingsStore.teslaBtAddress() ?: return true   // kein Gerät gewählt → Gate aus
        if (!permissionGate.hasBluetoothConnect()) return true       // ohne Permission nicht prüfbar
        val a = adapter ?: return true                               // kein BT-Adapter → nicht prüfbar
        if (!a.isEnabled) return false                               // BT aus → sicher nicht im Auto

        val proxies = listOfNotNull(headsetProxy, a2dpProxy)
        if (proxies.isEmpty()) return true                           // Proxies noch nicht bereit → fail-open
        return try {
            proxies.any { proxy ->
                proxy.connectedDevices.any { it.address.equals(target, ignoreCase = true) }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "connectedDevices denied: ${e.message}")
            true                                                     // Permission zur Laufzeit entzogen → fail-open
        }
    }

    /**
     * Gekoppelte (bonded) Geräte für den Auswahl-Dialog. Leer ohne
     * `BLUETOOTH_CONNECT`-Permission oder ohne Adapter.
     */
    @SuppressLint("MissingPermission") // runtime-geprüft
    fun pairedDevices(): List<PairedBtDevice> {
        if (!permissionGate.hasBluetoothConnect()) return emptyList()
        val a = adapter ?: return emptyList()
        return try {
            a.bondedDevices.orEmpty().map { device ->
                PairedBtDevice(
                    address = device.address,
                    name = device.name?.takeIf { it.isNotBlank() } ?: device.address
                )
            }.sortedBy { it.name.lowercase() }
        } catch (e: SecurityException) {
            Log.w(TAG, "bondedDevices denied: ${e.message}")
            emptyList()
        }
    }

    companion object {
        private const val TAG = "BtConnChecker"
    }
}

/** Ein gekoppeltes Bluetooth-Gerät für die Tesla-Auswahl. */
data class PairedBtDevice(val address: String, val name: String)
