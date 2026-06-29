package io.github.lycheeappf.tmm.platform.tesla.auth

internal object TeslaOAuthConfig {
    // Nach Registrierung auf developer.tesla.com eintragen.
    const val CLIENT_ID = "TODO_REPLACE_WITH_YOUR_CLIENT_ID"

    const val REDIRECT_URI = "io.github.lycheeappf.tmm://tesla/callback"
    const val AUTH_URL = "https://auth.tesla.com/oauth2/v3/authorize"
    const val TOKEN_URL = "https://auth.tesla.com/oauth2/v3/token"
    const val SCOPES = "openid offline_access vehicle_device_data vehicle_cmds"

    // Proaktiver Refresh 20 Minuten vor Ablauf (TeslaLogger-Muster).
    const val REFRESH_EARLY_MS = 20 * 60 * 1000L

    const val REGION_URL = "https://fleet-api.prd.na.vn.cloud.tesla.com/api/1/users/region"
}
