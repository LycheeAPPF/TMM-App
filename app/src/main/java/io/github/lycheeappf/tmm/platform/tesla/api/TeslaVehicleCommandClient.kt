package io.github.lycheeappf.tmm.platform.tesla.api

import android.util.Log
import io.github.lycheeappf.tmm.data.store.TeslaTokenStore
import io.github.lycheeappf.tmm.platform.tesla.auth.TeslaAuthManager
import io.github.lycheeappf.tmm.platform.tesla.auth.TeslaOAuthConfig
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
    private val tokenStore: TeslaTokenStore
) {
    /**
     * Listet alle Fahrzeuge des Nutzers.
     * Erfordert, dass [authManager.refreshIfNeeded] bereits aufgerufen wurde.
     */
    suspend fun listVehicles(): List<VehicleInfo> {
        val base = ensureRegion()
        val token = requireToken()
        val resp = api.vehicles("${base}api/1/vehicles", "Bearer $token")
        if (!resp.isSuccessful) mapError(resp.code(), resp.errorBody()?.string())
        return resp.body()?.response ?: emptyList()
    }

    /** Sendet ein Text-Navigationsziel an das Fahrzeug. */
    suspend fun navigate(vin: String, address: String) {
        val base = ensureRegion()
        val token = requireToken()
        val body = NavigationRequestBody(
            locale = Locale.getDefault().toLanguageTag(),
            timestampMs = System.currentTimeMillis(),
            value = NavigationValue(text = address)
        )
        val resp = api.navigationRequest(
            "${base}api/1/vehicles/$vin/commands/navigation_request",
            "Bearer $token",
            body
        )
        if (!resp.isSuccessful) mapError(resp.code(), resp.errorBody()?.string())
        resp.body()?.response?.let { result ->
            if (!result.result) throw TeslaCommandError.CommandRejected(result.reason)
        }
        Log.i(TAG, "navigation_request OK (vin=$vin, address=$address)")
    }

    /** Sendet GPS-Koordinaten als Navigationsziel. */
    suspend fun navigateGps(vin: String, lat: Double, lon: Double) {
        val base = ensureRegion()
        val token = requireToken()
        val body = NavigationGpsBody(lat = lat, lon = lon)
        val resp = api.navigationGps(
            "${base}api/1/vehicles/$vin/commands/navigation_gps_request",
            "Bearer $token",
            body
        )
        if (!resp.isSuccessful) mapError(resp.code(), resp.errorBody()?.string())
        resp.body()?.response?.let { result ->
            if (!result.result) throw TeslaCommandError.CommandRejected(result.reason)
        }
        Log.i(TAG, "navigation_gps_request OK (vin=$vin, lat=$lat, lon=$lon)")
    }

    // ---- Internals ----------------------------------------------------------

    private suspend fun ensureRegion(): String {
        tokenStore.readFleetApiBaseUrl()?.let { return it }
        authManager.refreshIfNeeded()
        val token = requireToken()
        val resp = api.region(TeslaOAuthConfig.REGION_URL, "Bearer $token")
        if (!resp.isSuccessful) mapError(resp.code(), resp.errorBody()?.string())
        val url = resp.body()?.response?.fleetApiBaseUrl
            ?: throw TeslaCommandError.Unknown(200, "Keine Region-URL in Antwort")
        tokenStore.writeFleetApiBaseUrl(url)
        return url
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

    companion object {
        private const val TAG = "TeslaVehicleCmd"
    }
}
