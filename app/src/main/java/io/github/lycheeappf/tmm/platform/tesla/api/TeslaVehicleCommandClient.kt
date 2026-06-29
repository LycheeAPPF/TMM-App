package io.github.lycheeappf.tmm.platform.tesla.api

import android.util.Log
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.data.store.TeslaTokenStore
import io.github.lycheeappf.tmm.platform.tesla.auth.TeslaAuthManager
import io.github.lycheeappf.tmm.platform.tesla.auth.TeslaOAuthConfig
import kotlinx.coroutines.delay
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

sealed class TeslaCommandError(message: String?) : Exception(message) {
    class Unauthorized : TeslaCommandError("Tesla-Auth abgelaufen — bitte erneut einloggen")
    class VehicleNotFound : TeslaCommandError("Fahrzeug nicht gefunden oder offline")
    class CommandRejected(reason: String?) : TeslaCommandError("Befehl abgelehnt: $reason")
    class Network(cause: Throwable) : TeslaCommandError(cause.message)
    class Unknown(code: Int, body: String?) : TeslaCommandError("HTTP $code: $body")
}

@Singleton
class TeslaVehicleCommandClient @Inject constructor(
    private val api: TeslaFleetApi,
    private val authManager: TeslaAuthManager,
    private val tokenStore: TeslaTokenStore,
    private val logBuffer: LogBuffer
) {
    /**
     * Listet alle Fahrzeuge des Nutzers.
     * Erfordert, dass [authManager.refreshIfNeeded] bereits aufgerufen wurde.
     */
    suspend fun listVehicles(): List<VehicleInfo> {
        authManager.refreshIfNeeded()
        val token = requireToken()
        val cached = tokenStore.readFleetApiBaseUrl()
        if (cached != null) {
            val resp = api.vehicles("${cached}api/1/vehicles", "Bearer $token")
            if (resp.isSuccessful) return resp.body()?.response ?: emptyList()
            if (resp.code() != 412) mapError(resp.code(), resp.errorBody()?.string())
            // Gecachte Region passt nicht mehr → neu entdecken
            tokenStore.writeFleetApiBaseUrl(null)
        }
        // Region-Discovery via vehicles-Probe
        for (base in TeslaOAuthConfig.REGION_CANDIDATES) {
            val resp = api.vehicles("${base}api/1/vehicles", "Bearer $token")
            when {
                resp.isSuccessful -> {
                    tokenStore.writeFleetApiBaseUrl(base)
                    return resp.body()?.response ?: emptyList()
                }
                resp.code() == 401 || resp.code() == 403 -> throw TeslaCommandError.Unauthorized()
                resp.code() == 412 -> Log.d(TAG, "412 from $base, trying next")
                else -> Log.w(TAG, "HTTP ${resp.code()} from $base")
            }
        }
        val msg = "Region-Discovery: kein passender Fleet-API-Endpunkt (EU+NA probiert)"
        logBuffer.error(TAG, msg)
        throw TeslaCommandError.Unknown(412, "Kein passender Fleet-API-Endpunkt (EU+NA). Bitte Tesla-App-Registrierung prüfen.")
    }

    /** Sendet ein Text-Navigationsziel an das Fahrzeug, weckt es vorher auf falls nötig. */
    suspend fun navigate(vin: String, address: String) {
        val base = ensureRegion()
        val body = NavigationRequestBody(
            locale = Locale.getDefault().toLanguageTag(),
            timestampMs = System.currentTimeMillis(),
            value = NavigationValue(text = address, extraText = address)
        )
        sendWithWakeUpRetry(vin, "navigation_request") {
            api.navigationRequest("${base}api/1/vehicles/$vin/command/navigation_request", "Bearer ${requireToken()}", body)
        }
        logBuffer.info(TAG, "navigation_request OK")
        Log.i(TAG, "navigation_request OK (vin=$vin, address=$address)")
    }

    /** Sendet GPS-Koordinaten als Navigationsziel, weckt das Fahrzeug vorher auf falls nötig. */
    suspend fun navigateGps(vin: String, lat: Double, lon: Double) {
        val base = ensureRegion()
        val body = NavigationGpsBody(lat = lat, lon = lon)
        sendWithWakeUpRetry(vin, "navigation_gps_request") {
            api.navigationGps("${base}api/1/vehicles/$vin/command/navigation_gps_request", "Bearer ${requireToken()}", body)
        }
        logBuffer.info(TAG, "navigation_gps_request OK")
        Log.i(TAG, "navigation_gps_request OK (vin=$vin, lat=$lat, lon=$lon)")
    }

    /**
     * Führt [call] aus. Bei 404/408 (Fahrzeug schläft) wird [wakeUp] aufgerufen,
     * 15 Sekunden gewartet und der Aufruf einmal wiederholt.
     */
    private suspend fun sendWithWakeUpRetry(
        vin: String,
        endpoint: String,
        call: suspend () -> retrofit2.Response<CommandResponse>
    ) {
        var resp = call()
        if (!resp.isSuccessful && resp.code() in listOf(404, 408)) {
            val body = resp.errorBody()?.string()
            Log.w(TAG, "$endpoint offline (${resp.code()}): $body — waking up vehicle")
            logBuffer.warn(TAG, "$endpoint: HTTP ${resp.code()} — Fahrzeug schläft, wake_up wird gesendet")
            wakeUpByVin(vin)
            delay(15_000L)
            resp = call()
        }
        val errorBody = if (!resp.isSuccessful) resp.errorBody()?.string() else null
        if (!resp.isSuccessful) {
            logBuffer.error(TAG, "$endpoint fehlgeschlagen: HTTP ${resp.code()} — ${errorBody?.take(200)}")
            mapErrorWithBody(resp.code(), errorBody)
        }
        resp.body()?.response?.let { result ->
            if (!result.result) {
                logBuffer.error(TAG, "$endpoint abgelehnt: ${result.reason}")
                throw TeslaCommandError.CommandRejected(result.reason)
            }
        }
    }

    /** Weckt das Fahrzeug über seine numerische ID (bevorzugt) oder sucht via Fahrzeugliste. */
    private suspend fun wakeUpByVin(vin: String) {
        val base = tokenStore.readFleetApiBaseUrl() ?: return
        val vehicleId = tokenStore.readSelectedVehicleId()
            ?: listVehicles().firstOrNull { it.vin == vin }?.id
            ?: run {
                Log.w(TAG, "wakeUp: vehicle ID not found")
                logBuffer.warn(TAG, "wake_up: Fahrzeug-ID nicht gefunden")
                return
            }
        val resp = api.wakeUp("${base}api/1/vehicles/$vehicleId/wake_up", "Bearer ${requireToken()}")
        val state = resp.body()?.response?.state ?: "unknown"
        Log.i(TAG, "wake_up HTTP ${resp.code()} state=$state")
        logBuffer.info(TAG, "wake_up HTTP ${resp.code()} state=$state")
    }

    // ---- Internals ----------------------------------------------------------

    private suspend fun ensureRegion(): String {
        tokenStore.readFleetApiBaseUrl()?.let { return it }
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
                    tokenStore.writeFleetApiBaseUrl(base)
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
        logBuffer.error(TAG, "ensureRegion: kein passender Endpunkt (EU+NA), alle 412")
        throw TeslaCommandError.Unknown(
            412,
            "Kein passender Fleet-API-Endpunkt gefunden (EU+NA probiert). " +
                "Bitte App-Registrierung auf developer.tesla.com prüfen."
        )
    }

    private suspend fun requireToken(): String {
        authManager.refreshIfNeeded()
        return authManager.readAccessToken() ?: throw TeslaCommandError.Unauthorized()
    }

    private fun mapError(code: Int, body: String?): Nothing = when (code) {
        401, 403 -> throw TeslaCommandError.Unauthorized()
        404 -> throw TeslaCommandError.VehicleNotFound()
        else -> throw TeslaCommandError.Unknown(code, body?.take(200))
    }

    /** Wie [mapError], aber gibt bei 404 den Body mit aus (hilft bei der Fehlerdiagnose). */
    private fun mapErrorWithBody(code: Int, body: String?): Nothing = when (code) {
        401, 403 -> throw TeslaCommandError.Unauthorized()
        404 -> throw TeslaCommandError.Unknown(404, "Fahrzeug nicht gefunden/offline: ${body?.take(150)}")
        else -> throw TeslaCommandError.Unknown(code, body?.take(200))
    }

    /** Ruft /api/1/users/region auf allen bekannten Endpunkten auf und gibt die Rohantworten zurück. */
    suspend fun regionDiagnosticInfo(): String {
        val token = authManager.readAccessToken() ?: return "Kein Access-Token vorhanden"
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
