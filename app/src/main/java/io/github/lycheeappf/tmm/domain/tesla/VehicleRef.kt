package io.github.lycheeappf.tmm.domain.tesla

/**
 * Referenz auf ein Fleet-API-Fahrzeug für Kommandos: [vin] adressiert die
 * Command-Endpunkte, [vehicleId] (numerisch) den `wake_up`-Endpunkt. `null`
 * = ID unbekannt → der Command-Client schlägt sie über die Fahrzeugliste nach.
 *
 * **PII:** enthält die VIN — nie loggen.
 */
data class VehicleRef(
    val vin: String,
    val vehicleId: Long?
)
