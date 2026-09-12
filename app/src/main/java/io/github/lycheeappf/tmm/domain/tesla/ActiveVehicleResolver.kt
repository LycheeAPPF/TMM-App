package io.github.lycheeappf.tmm.domain.tesla

/**
 * Bestimmt, an welches Fleet-API-Fahrzeug ein Kommando (Grok-Navigation) geht,
 * wenn der User mehrere Teslas hat.
 *
 * Reihenfolge:
 * 1. Das per Bluetooth **verbundene** Tesla-Gerät, dem ein Fahrzeug **verknüpft**
 *    ist ([TeslaDevice.vin]) — der Fahrer sitzt gerade in diesem Auto.
 * 2. Sonst das **Standard-Fahrzeug** (die manuelle Einzelauswahl in den
 *    Fleet-API-Einstellungen) — Desk-Test, Selbsttest, kein Auto verbunden, oder
 *    das verbundene Auto ist (noch) nicht verknüpft.
 * 3. Sonst `null` — nichts konfiguriert.
 *
 * Interface-Seam (Muster `TeslaTokenStore`), damit Tool- und Selbsttest-Tests
 * einen In-Memory-Fake statt der Bluetooth-/DataStore-Implementierung nutzen.
 */
interface ActiveVehicleResolver {

    /** Siehe Klassen-Doc. `null` = kein Fahrzeug konfiguriert. */
    suspend fun resolve(): VehicleRef?

    /**
     * `true`, wenn ein Standard-Fahrzeug gesetzt ist ODER mindestens ein Gerät
     * verknüpft ist — d.h. es gibt überhaupt ein Fahrzeug, das [resolve] in
     * irgendeiner Situation liefern kann (Selbsttest-Stufe „Tesla lokal").
     */
    suspend fun isAnyVehicleConfigured(): Boolean
}
