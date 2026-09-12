package io.github.lycheeappf.tmm.domain.tesla

import kotlinx.serialization.Serializable

/**
 * Ein vom User als „mein Tesla" ausgewähltes, gekoppeltes Bluetooth-Gerät —
 * optional mit dem Fleet-API-Fahrzeug verknüpft, das in diesem Auto steckt.
 *
 * - [address]: BT-MAC, beim Persistieren UPPERCASE normalisiert; Vergleiche
 *   immer `ignoreCase` (ACL-Broadcasts liefern uppercase, Proxies gemischt).
 * - [name]: BT-Anzeigename, nur für die UI.
 * - [vin]/[vehicleId]: verknüpftes Fleet-Fahrzeug (VIN + numerische ID für
 *   `wake_up`); null = nicht verknüpft → Navigation fällt aufs Standard-Fahrzeug.
 *
 * **PII:** `toString()` enthält MAC + VIN — nie in den LogBuffer schreiben,
 * nur Counts loggen.
 */
@Serializable
data class TeslaDevice(
    val address: String,
    val name: String,
    val vin: String? = null,
    val vehicleId: Long? = null
) {
    /** `true`, wenn diesem Gerät ein Fleet-API-Fahrzeug zugeordnet ist. */
    val isLinked: Boolean get() = vin != null

    fun sameAddress(other: String): Boolean = address.equals(other, ignoreCase = true)
}
