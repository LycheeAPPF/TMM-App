package io.github.lycheeappf.tmm.platform.tesla.api

import android.util.Log
import io.github.lycheeappf.tmm.core.util.Clock
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.data.store.TeslaRegionStore
import io.github.lycheeappf.tmm.domain.tesla.VehicleRef
import io.github.lycheeappf.tmm.platform.tesla.auth.TeslaAuthManager
import io.github.lycheeappf.tmm.platform.tesla.auth.TeslaOAuthConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Typisierte Fleet-API-Fehler. Die Exception-`message` ist NUR für Logcat/Debugging
 * (englisch, PII-frei) — die nutzer­gerichtete, lokalisierte Meldung liefert
 * [userMessage] (EN+DE via `Context.localizedString`).
 */
sealed class TeslaCommandError(message: String?) : Exception(message) {
    /** Keine Nutzer-Credentials hinterlegt — Fleet-Features sind nicht eingerichtet. */
    class MissingCredentials : TeslaCommandError("Tesla API credentials missing")
    class Unauthorized : TeslaCommandError("Tesla auth expired")
    class VehicleNotFound : TeslaCommandError("vehicle not found or offline")
    class CommandRejected(val reason: String?) : TeslaCommandError("command rejected: $reason")
    class Network(cause: Throwable) : TeslaCommandError(cause.message)
    /** Kein Region-Endpunkt hat den Account akzeptiert (EU+NA jeweils 412). */
    class RegionDiscoveryFailed : TeslaCommandError("no matching Fleet API endpoint (EU+NA probed)")
    class Unknown(val code: Int, val body: String?) : TeslaCommandError("HTTP $code")
}

/**
 * Führt Fleet-API-Kommandos gegen ein konkretes Fahrzeug ([VehicleRef]) aus.
 * Das Ziel kommt vom Aufrufer (Multi-Tesla: `ActiveVehicleResolver`) — der
 * Client kennt keine „globale" Fahrzeugauswahl mehr; insbesondere `wake_up`
 * nutzt die ID des adressierten Fahrzeugs, nicht die eines anderen Autos.
 */
@Singleton
class TeslaVehicleCommandClient @Inject constructor(
    private val api: TeslaFleetApi,
    private val authManager: TeslaAuthManager,
    private val regionStore: TeslaRegionStore,
    private val logBuffer: LogBuffer,
    private val clock: Clock
) {
    /**
     * Listet alle Fahrzeuge des Nutzers.
     * Erfordert, dass [authManager.refreshIfNeeded] bereits aufgerufen wurde.
     */
    suspend fun listVehicles(): List<VehicleInfo> {
        authManager.refreshIfNeeded()
        val token = requireToken()
        val cached = regionStore.readFleetApiBaseUrl()
        if (cached != null) {
            val resp = api.vehicles("${cached}api/1/vehicles", "Bearer $token")
            if (resp.isSuccessful) return resp.body()?.response ?: emptyList()
            if (resp.code() != 412) mapError(resp.code(), resp.errorBody()?.string())
            // Gecachte Region passt nicht mehr → neu entdecken
            regionStore.writeFleetApiBaseUrl(null)
        }
        // Region-Discovery via vehicles-Probe
        for (base in TeslaOAuthConfig.REGION_CANDIDATES) {
            val resp = api.vehicles("${base}api/1/vehicles", "Bearer $token")
            when {
                resp.isSuccessful -> {
                    regionStore.writeFleetApiBaseUrl(base)
                    return resp.body()?.response ?: emptyList()
                }
                resp.code() == 401 || resp.code() == 403 -> throw TeslaCommandError.Unauthorized()
                resp.code() == 412 -> Log.d(TAG, "412 from $base, trying next")
                else -> Log.w(TAG, "HTTP ${resp.code()} from $base")
            }
        }
        logBuffer.error(TAG, "region discovery failed: no matching Fleet API endpoint (EU+NA probed)")
        throw TeslaCommandError.RegionDiscoveryFailed()
    }

    /** Sendet ein Text-Navigationsziel an das Fahrzeug, weckt es vorher auf falls nötig. */
    suspend fun navigate(vehicle: VehicleRef, address: String) {
        val base = ensureRegion()
        val body = NavigationRequestBody(
            locale = Locale.getDefault().toLanguageTag(),
            timestampMs = clock.now(),
            value = NavigationValue(text = address, extraText = address)
        )
        sendWithWakeUpRetry(vehicle, "navigation_request") {
            api.navigationRequest(
                "${base}api/1/vehicles/${vehicle.vin}/command/navigation_request",
                "Bearer ${requireToken()}",
                body
            )
        }
        // NIE Ziel/VIN loggen — nur Metadaten (siehe CLAUDE.md PII-Regel).
        logBuffer.info(TAG, "navigation_request OK (address len=${address.length})")
        Log.i(TAG, "navigation_request OK (address len=${address.length})")
    }

    /** Sendet GPS-Koordinaten als Navigationsziel, weckt das Fahrzeug vorher auf falls nötig. */
    suspend fun navigateGps(vehicle: VehicleRef, lat: Double, lon: Double) {
        val base = ensureRegion()
        val body = NavigationGpsBody(lat = lat, lon = lon)
        sendWithWakeUpRetry(vehicle, "navigation_gps_request") {
            api.navigationGps(
                "${base}api/1/vehicles/${vehicle.vin}/command/navigation_gps_request",
                "Bearer ${requireToken()}",
                body
            )
        }
        // NIE Koordinaten/VIN loggen — nur Metadaten (siehe CLAUDE.md PII-Regel).
        logBuffer.info(TAG, "navigation_gps_request OK")
        Log.i(TAG, "navigation_gps_request OK")
    }

    /**
     * Führt [call] aus. Bei 404/408 (Fahrzeug schläft) wird [wakeUp] aufgerufen,
     * 15 Sekunden gewartet und der Aufruf einmal wiederholt.
     */
    private suspend fun sendWithWakeUpRetry(
        vehicle: VehicleRef,
        endpoint: String,
        call: suspend () -> retrofit2.Response<CommandResponse>
    ) {
        var resp = call()
        if (!resp.isSuccessful && resp.code() in listOf(404, 408)) {
            // Error-Body NIE loggen (könnte Request-Daten spiegeln) — nur Metadaten.
            val bodyLen = resp.errorBody()?.string()?.length ?: 0
            Log.w(TAG, "$endpoint offline (HTTP ${resp.code()}, body len=$bodyLen) — waking up vehicle")
            logBuffer.warn(TAG, "$endpoint: HTTP ${resp.code()} — vehicle asleep, sending wake_up")
            wakeUp(vehicle)
            delay(15_000L)
            resp = call()
        }
        val errorBody = if (!resp.isSuccessful) resp.errorBody()?.string() else null
        if (!resp.isSuccessful) {
            logBuffer.error(TAG, "$endpoint failed: HTTP ${resp.code()} (body len=${errorBody?.length ?: 0})")
            mapError(resp.code(), errorBody)
        }
        resp.body()?.response?.let { result ->
            if (!result.result) {
                logBuffer.error(TAG, "$endpoint rejected (reason len=${result.reason?.length ?: 0})")
                throw TeslaCommandError.CommandRejected(result.reason)
            }
        }
    }

    /**
     * Weckt das adressierte Fahrzeug über seine numerische ID ([VehicleRef.vehicleId],
     * bevorzugt) oder schlägt sie über die Fahrzeugliste nach. Bewusst KEINE
     * globale „gewählte" ID — bei mehreren Autos würde sonst das falsche geweckt.
     */
    private suspend fun wakeUp(vehicle: VehicleRef) {
        val base = regionStore.readFleetApiBaseUrl() ?: return
        val vehicleId = vehicle.vehicleId
            ?: listVehicles().firstOrNull { it.vin == vehicle.vin }?.id
            ?: run {
                Log.w(TAG, "wakeUp: vehicle ID not found")
                logBuffer.warn(TAG, "wake_up: vehicle ID not found")
                return
            }
        val resp = api.wakeUp("${base}api/1/vehicles/$vehicleId/wake_up", "Bearer ${requireToken()}")
        val state = resp.body()?.response?.state ?: "unknown"
        Log.i(TAG, "wake_up HTTP ${resp.code()} state=$state")
        logBuffer.info(TAG, "wake_up HTTP ${resp.code()} state=$state")
    }

    // ---- Internals ----------------------------------------------------------

    private suspend fun ensureRegion(): String {
        regionStore.readFleetApiBaseUrl()?.let { return it }
        authManager.refreshIfNeeded()
        val token = requireToken()

        // /api/1/users/region liefert bei manchen App-Registrierungen 412 auf allen
        // Endpunkten. Fallback: /api/1/vehicles direkt aufrufen — der Endpunkt, der
        // mit 200 antwortet, ist die richtige Region.
        for (base in TeslaOAuthConfig.REGION_CANDIDATES) {
            val resp = api.vehicles("${base}api/1/vehicles", "Bearer $token")
            when {
                resp.isSuccessful -> {
                    Log.i(TAG, "Region discovered via vehicles: $base")
                    regionStore.writeFleetApiBaseUrl(base)
                    return base
                }
                resp.code() == 401 || resp.code() == 403 -> throw TeslaCommandError.Unauthorized()
                resp.code() == 412 -> {
                    Log.d(TAG, "412 from $base (vehicles probe), trying next")
                }
                else -> {
                    Log.w(TAG, "Unexpected ${resp.code()} from $base vehicles probe")
                }
            }
        }
        logBuffer.error(TAG, "ensureRegion: no matching endpoint (EU+NA), all 412")
        throw TeslaCommandError.RegionDiscoveryFailed()
    }

    private suspend fun requireToken(): String {
        // Turn-/Tool-Zeit-Re-Check: der Tesla-Auto-Reply-Pfad umgeht die UI-Gates,
        // daher wird das Credentials-Gate bei JEDEM Fleet-Call erneut geprüft.
        if (!authManager.hasCredentials()) throw TeslaCommandError.MissingCredentials()
        authManager.refreshIfNeeded()
        return authManager.readAccessToken() ?: throw TeslaCommandError.Unauthorized()
    }

    private fun mapError(code: Int, body: String?): Nothing = when (code) {
        401, 403 -> throw TeslaCommandError.Unauthorized()
        404 -> throw TeslaCommandError.VehicleNotFound()
        else -> throw TeslaCommandError.Unknown(code, body?.take(200))
    }

    /**
     * Ruft /api/1/users/region auf allen bekannten Endpunkten auf und gibt die
     * Rohantworten zurück; null, wenn kein Access-Token vorliegt (Caller lokalisiert).
     */
    suspend fun regionDiagnosticInfo(): String? {
        val token = authManager.readAccessToken() ?: return null
        return buildString {
            for (base in TeslaOAuthConfig.REGION_CANDIDATES) {
                val url = "${base}api/1/users/region"
                append("GET $url\n")
                try {
                    val resp = api.region(url, "Bearer $token")
                    append("HTTP ${resp.code()}\n")
                    val body = if (resp.isSuccessful) resp.body().toString()
                    else resp.errorBody()?.string()?.take(500)
                    append(body).append("\n\n")
                } catch (e: CancellationException) {
                    // NIE schlucken — sonst wird ein Abbruch als Diagnose-Text ausgegeben.
                    throw e
                } catch (e: Exception) {
                    append("Exception: ${e.message?.take(200)}\n\n")
                }
            }
        }
    }

    companion object {
        private const val TAG = "TeslaVehicleCmd"
    }
}
