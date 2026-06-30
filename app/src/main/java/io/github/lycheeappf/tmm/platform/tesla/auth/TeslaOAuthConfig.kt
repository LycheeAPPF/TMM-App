package io.github.lycheeappf.tmm.platform.tesla.auth

internal object TeslaOAuthConfig {
    const val CLIENT_ID = "294e0fb4-83b4-4a23-b0f6-942fc8df5d8f"

    const val REDIRECT_URI = "io.github.lycheeappf.tmm://tesla/callback"
    const val AUTH_URL = "https://auth.tesla.com/oauth2/v3/authorize"
    const val TOKEN_URL = "https://auth.tesla.com/oauth2/v3/token"
    const val SCOPES = "openid offline_access vehicle_device_data vehicle_cmds"

    // Proaktiver Refresh 20 Minuten vor Ablauf (TeslaLogger-Muster).
    const val REFRESH_EARLY_MS = 20 * 60 * 1000L

    // Bekannte regionale Fleet-API-Endpunkte — werden der Reihe nach probiert.
    val REGION_CANDIDATES = listOf(
        "https://fleet-api.prd.eu.vn.cloud.tesla.com/",
        "https://fleet-api.prd.na.vn.cloud.tesla.com/",
    )

    // audience-Parameter für den Token-Exchange: muss zur Region des Tesla-Accounts passen.
    // EU-Accounts → eu-URL; NA-Accounts → na-URL. Ohne diesen Parameter ist das
    // ausgestellte Token für keine Fleet-API-Region gültig (412 auf allen Endpunkten).
    const val TOKEN_AUDIENCE = "https://fleet-api.prd.eu.vn.cloud.tesla.com"
}
