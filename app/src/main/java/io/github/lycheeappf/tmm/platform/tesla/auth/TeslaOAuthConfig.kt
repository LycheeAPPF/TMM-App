package io.github.lycheeappf.tmm.platform.tesla.auth

/**
 * Statische, nicht-geheime OAuth-Konstanten. Die App-Credentials (client_id /
 * client_secret) sind bewusst NICHT hier — sie sind vollständig nutzer-
 * bereitgestellt und liegen verschlüsselt im
 * [io.github.lycheeappf.tmm.core.security.TeslaCredentialsStore]; es wird
 * nichts einkompiliert.
 */
object TeslaOAuthConfig {

    /**
     * Muss exakt so in der Tesla-App-Registrierung des Nutzers hinterlegt sein
     * (developer.tesla.com → Redirect URIs).
     */
    const val REDIRECT_URI = "io.github.lycheeappf.tmm://tesla/callback"
    const val AUTH_URL = "https://auth.tesla.com/oauth2/v3/authorize"
    const val TOKEN_URL = "https://auth.tesla.com/oauth2/v3/token"
    const val SCOPES = "openid offline_access vehicle_device_data vehicle_cmds"

    // Proaktiver Refresh 20 Minuten vor Ablauf (TeslaLogger-Muster).
    const val REFRESH_EARLY_MS = 20 * 60 * 1000L

    // Bekannte regionale Fleet-API-Endpunkte — werden der Reihe nach probiert.
    // Der `audience`-Parameter für Token-Requests wird aus der ENTDECKTEN Region
    // abgeleitet (TeslaRegionStore); solange sie unbekannt ist, dient der erste
    // Kandidat als Startwert für die Discovery.
    val REGION_CANDIDATES = listOf(
        "https://fleet-api.prd.eu.vn.cloud.tesla.com/",
        "https://fleet-api.prd.na.vn.cloud.tesla.com/",
    )
}

/**
 * Konfigurierbare Endpunkte für [TeslaAuthManager]. Produktion bekommt die
 * Defaults (via `TeslaModule`); Tests zeigen die URLs auf einen MockWebServer.
 */
data class TeslaOAuthEndpoints(
    val authUrl: String = TeslaOAuthConfig.AUTH_URL,
    val tokenUrl: String = TeslaOAuthConfig.TOKEN_URL,
    val regionCandidates: List<String> = TeslaOAuthConfig.REGION_CANDIDATES
)
