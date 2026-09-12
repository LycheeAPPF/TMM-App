package io.github.lycheeappf.tmm.platform.tesla

import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.data.store.SettingsStore
import io.github.lycheeappf.tmm.data.store.TeslaTokenStore
import io.github.lycheeappf.tmm.domain.tesla.ActiveVehicleResolver
import io.github.lycheeappf.tmm.domain.tesla.VehicleRef
import io.github.lycheeappf.tmm.platform.bluetooth.BluetoothConnectionChecker
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Produktions-Implementierung von [ActiveVehicleResolver]: verbundenes,
 * verknüpftes Tesla-Gerät ([BluetoothConnectionChecker.connectedTeslaDevices])
 * vor dem Standard-Fahrzeug ([TeslaTokenStore.readSelectedVin]).
 *
 * Fällt der Resolver aufs Standard-Fahrzeug zurück, obwohl Verknüpfungen
 * existieren (kein Auto verbunden, Proxies noch nicht bereit, verbundenes Auto
 * unverknüpft), wird das als Warnung geloggt — ohne VIN/MAC/Namen —, damit ein
 * „Ziel landete im falschen Auto" im Diagnose-Export erklärbar ist.
 */
@Singleton
class DefaultActiveVehicleResolver @Inject constructor(
    private val bluetoothConnectionChecker: BluetoothConnectionChecker,
    private val tokenStore: TeslaTokenStore,
    private val settingsStore: SettingsStore,
    private val logBuffer: LogBuffer
) : ActiveVehicleResolver {

    override suspend fun resolve(): VehicleRef? {
        val connected = bluetoothConnectionChecker.connectedTeslaDevices()
        connected.firstOrNull { it.isLinked }?.let { device ->
            return VehicleRef(vin = device.vin!!, vehicleId = device.vehicleId)
        }
        val defaultVin = tokenStore.readSelectedVin() ?: return null
        val linkedCount = settingsStore.teslaDevices().count { it.isLinked }
        if (linkedCount > 0) {
            logBuffer.warn(
                TAG,
                "vehicle resolved via default (linked=$linkedCount, connected=${connected.size}, " +
                    "no linked Tesla connected)"
            )
        }
        return VehicleRef(vin = defaultVin, vehicleId = tokenStore.readSelectedVehicleId())
    }

    override suspend fun isAnyVehicleConfigured(): Boolean =
        tokenStore.readSelectedVin() != null || settingsStore.teslaDevices().any { it.isLinked }

    companion object {
        private const val TAG = "ActiveVehicle"
    }
}
