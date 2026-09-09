package io.github.lycheeappf.tmm.platform.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lycheeappf.tmm.data.store.SettingsStore
import io.github.lycheeappf.tmm.domain.tesla.TeslaDevice
import io.github.lycheeappf.tmm.platform.permission.PermissionGate
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Prüft, ob das Handy aktuell mit EINEM der vom User gewählten Tesla-Geräte per
 * Bluetooth verbunden ist. Damit leitet die App Nachrichten nur weiter, während
 * man tatsächlich in einem seiner Autos sitzt — sonst landen Fake-SMS nur in der
 * DB und verbrennen unnötig das Tageslimit (MAP zieht sie erst beim nächsten Connect).
 *
 * **Warum Profile statt MAP/PBAP:** Das Handy ist bei MAP/PBAP der *Server*; die
 * passenden Profil-Proxies sind für Dritt-Apps nicht zugänglich. Eine
 * Tesla-Kopplung baut aber immer auch HFP (Headset) und/oder A2DP auf — über
 * diese (öffentlichen) Proxies fragen wir die verbundenen Geräte ab und matchen
 * die gespeicherten MACs. Zusätzlich verfolgen wir ACL-Connect/Disconnect-Broadcasts
 * ([connectedAddresses]) — die feuern profil-unabhängig (auch bei reinem MAP/PBAP)
 * und schließen so die Lücke, falls HFP/A2DP gerade nicht aufgebaut sind.
 *
 * **Fail-Open-Vertrag ([isTeslaConnected]):** Solange kein Tesla-Gerät gewählt
 * ist, die `BLUETOOTH_CONNECT`-Permission fehlt oder kein BT-Adapter existiert,
 * kommt `true` zurück — die Brücke leitet dann wie früher rund um die Uhr weiter,
 * statt stillschweigend nichts mehr zu tun. Erst gewählte Geräte + erteilte
 * Permission aktivieren das Verbindungs-Gate.
 *
 * [connectedTeslaDevices] und [teslaDeviceStatuses] sind dagegen **nicht**
 * fail-open: sie liefern „leer"/„unbekannt", wenn nichts bestimmbar ist — der
 * Fahrzeug-Resolver fällt dann aufs Standard-Fahrzeug zurück, die UI zeigt keine
 * falschen Warnungen.
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

    /** Aktuell auf ACL-Ebene verbundene Geräte-MACs (uppercase), profil-unabhängig. */
    private val connectedAddresses = Collections.synchronizedSet(mutableSetOf<String>())

    private val aclReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            val addr = device?.address?.uppercase() ?: return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> connectedAddresses.add(addr)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> connectedAddresses.remove(addr)
            }
        }
    }

    init {
        acquireProxies()
        registerAclReceiver()
    }

    /**
     * Registriert ACL-Connect/Disconnect-Broadcasts. Diese feuern für JEDES Profil
     * (inkl. MAP/PBAP), brauchen keine Permission zum Lesen der MAC und schließen
     * so die Lücke, falls bei verbundenem Tesla gerade kein HFP/A2DP aufgebaut ist.
     */
    private fun registerAclReceiver() {
        runCatching {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            ContextCompat.registerReceiver(context, aclReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onFailure { Log.w(TAG, "ACL receiver registration failed: ${it.message}") }
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
     * `true`, wenn eines der gewählten Tesla-Geräte gerade verbunden ist — oder
     * fail-open (siehe Klassen-Doc), wenn nicht geprüft werden kann/soll. Nur bei
     * gewählten Geräten + Permission + vorhandenem, eingeschaltetem Adapter wird
     * tatsächlich gegated.
     */
    suspend fun isTeslaConnected(): Boolean {
        val targets = settingsStore.teslaDevices().map { it.address.uppercase() }
        if (targets.isEmpty()) return true                           // kein Gerät gewählt → Gate aus
        if (!permissionGate.hasBluetoothConnect()) return true       // ohne Permission nicht prüfbar
        val a = adapter ?: return true                               // kein BT-Adapter → nicht prüfbar
        if (!a.isEnabled) return false                               // BT aus → sicher nicht im Auto

        // ACL-Ebene zuerst: erfasst auch reine MAP/PBAP-Verbindungen ohne HFP/A2DP.
        if (targets.any { it in connectedAddresses }) return true

        val viaProxy = proxyConnectedAddresses() ?: return true      // Proxies nicht bereit/denied → fail-open
        return targets.any { it in viaProxy }
    }

    /**
     * Die gewählten Tesla-Geräte, die gerade verbunden sind (ACL ∪ HFP/A2DP).
     * **Leer, wenn nicht bestimmbar** (keine Auswahl, keine Permission, kein
     * Adapter, BT aus) — bewusst kein Fail-Open: Aufrufer (Fahrzeug-Resolver)
     * fallen dann auf ihr Standard-Verhalten zurück.
     */
    suspend fun connectedTeslaDevices(): List<TeslaDevice> {
        val devices = settingsStore.teslaDevices()
        if (devices.isEmpty() || !permissionGate.hasBluetoothConnect()) return emptyList()
        val a = adapter ?: return emptyList()
        if (!a.isEnabled) return emptyList()
        val viaProxy = proxyConnectedAddresses().orEmpty()
        return devices.filter { device ->
            val addr = device.address.uppercase()
            addr in connectedAddresses || addr in viaProxy
        }
    }

    /**
     * Zustand jedes gewählten Geräts für die UI-Zeilen: [TeslaDeviceStatus.missing]
     * nur dann `true`, wenn die Kopplungsliste tatsächlich lesbar war (Permission +
     * Adapter + kein SecurityException) und das Gerät darin fehlt — sonst gäbe es
     * False-Positives „nicht mehr gekoppelt". [TeslaDeviceStatus.connected] ist ein
     * Snapshot zum Aufrufzeitpunkt (die Screens refreshen bei Resume).
     */
    suspend fun teslaDeviceStatuses(): List<TeslaDeviceStatus> {
        val devices = settingsStore.teslaDevices()
        if (devices.isEmpty()) return emptyList()
        val bonded = bondedAddressesOrNull()
        val connected = connectedTeslaDevices().map { it.address.uppercase() }.toSet()
        return devices.map { device ->
            val addr = device.address.uppercase()
            TeslaDeviceStatus(
                device = device,
                missing = bonded != null && addr !in bonded,
                connected = addr in connected
            )
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

    /** Uppercase-MACs der gekoppelten Geräte; `null` = nicht bestimmbar (Permission/Adapter/denied). */
    @SuppressLint("MissingPermission") // runtime-geprüft
    private fun bondedAddressesOrNull(): Set<String>? {
        if (!permissionGate.hasBluetoothConnect()) return null
        val a = adapter ?: return null
        return try {
            a.bondedDevices.orEmpty().map { it.address.uppercase() }.toSet()
        } catch (e: SecurityException) {
            Log.w(TAG, "bondedDevices denied: ${e.message}")
            null
        }
    }

    /**
     * Uppercase-MACs der über HFP/A2DP verbundenen Geräte; `null`, wenn die Proxies
     * noch nicht bereit sind oder die Permission zur Laufzeit entzogen wurde.
     */
    @SuppressLint("MissingPermission") // runtime-geprüft über permissionGate.hasBluetoothConnect()
    private fun proxyConnectedAddresses(): Set<String>? {
        val proxies = listOfNotNull(headsetProxy, a2dpProxy)
        if (proxies.isEmpty()) return null
        return try {
            proxies.flatMap { it.connectedDevices }.map { it.address.uppercase() }.toSet()
        } catch (e: SecurityException) {
            Log.w(TAG, "connectedDevices denied: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "BtConnChecker"
    }
}

/** Ein gekoppeltes Bluetooth-Gerät für die Tesla-Auswahl. */
data class PairedBtDevice(val address: String, val name: String)

/**
 * Zustand eines gewählten Tesla-Geräts für die UI: [missing] = nicht mehr in der
 * Kopplungsliste (nur gemeldet, wenn die Liste lesbar war), [connected] = gerade
 * per Bluetooth verbunden (Snapshot).
 */
data class TeslaDeviceStatus(
    val device: TeslaDevice,
    val missing: Boolean,
    val connected: Boolean
)
