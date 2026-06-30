package io.github.lycheeappf.tmm.platform.tesla.auth

import android.net.Uri
import android.util.Base64
import android.util.Log
import io.github.lycheeappf.tmm.BuildConfig
import io.github.lycheeappf.tmm.core.di.TeslaHttpClient
import io.github.lycheeappf.tmm.data.store.TeslaTokenStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

sealed class TeslaAuthState {
    data object NotAuthenticated : TeslaAuthState()
    data object Loading : TeslaAuthState()
    data class Authenticated(val selectedVin: String?, val expiresAtMs: Long) : TeslaAuthState()
    data class Error(val message: String) : TeslaAuthState()
}

/**
 * Verwaltet den Tesla OAuth2-PKCE-Flow und den Token-Lebenszyklus.
 *
 * Ablauf:
 *  1. [startAuth] → gibt die Auth-URL zurück; die UI öffnet einen Chrome Custom Tab.
 *  2. Tesla redirectet auf `io.github.lycheeappf.tmm://tesla/callback?code=...`
 *  3. [MainActivity] extrahiert den `code` und ruft [postCallbackUri] auf.
 *  4. [SettingsViewModel] collected [pendingCode] und ruft [exchangeCode] auf.
 *  5. [refreshIfNeeded] wird vor jedem Fleet-API-Call gerufen (lazy Refresh, 20 min Puffer).
 */
@Singleton
class TeslaAuthManager @Inject constructor(
    private val tokenStore: TeslaTokenStore,
    @TeslaHttpClient private val httpClient: OkHttpClient
) {
    private val _state = MutableStateFlow<TeslaAuthState>(TeslaAuthState.Loading)
    val state: StateFlow<TeslaAuthState> = _state.asStateFlow()

    private val _pendingCode = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val pendingCode: SharedFlow<String> = _pendingCode.asSharedFlow()

    // In-memory PKCE-Verifier — bleibt im Prozess, Chrome Custom Tabs killt die App nicht.
    @Volatile
    private var pendingVerifier: String? = null

    suspend fun init() {
        val authenticated = tokenStore.isAuthenticated()
        if (authenticated) {
            _state.update {
                TeslaAuthState.Authenticated(
                    selectedVin = tokenStore.readSelectedVin(),
                    expiresAtMs = tokenStore.readExpiresAtMs()
                )
            }
        } else {
            _state.update { TeslaAuthState.NotAuthenticated }
        }
    }

    /**
     * Generiert PKCE-Verifier + Challenge und baut die Authorization-URL auf.
     * Die UI öffnet diese URL in einem Chrome Custom Tab.
     */
    fun startAuth(): String {
        val verifier = generateCodeVerifier()
        pendingVerifier = verifier
        val challenge = generateCodeChallenge(verifier)
        return buildString {
            append(TeslaOAuthConfig.AUTH_URL)
            append("?response_type=code")
            append("&client_id=").append(Uri.encode(TeslaOAuthConfig.CLIENT_ID))
            append("&redirect_uri=").append(Uri.encode(TeslaOAuthConfig.REDIRECT_URI))
            append("&scope=").append(Uri.encode(TeslaOAuthConfig.SCOPES))
            append("&code_challenge=").append(challenge)
            append("&code_challenge_method=S256")
            append("&state=tmm_auth")
        }
    }

    /** Wird von [io.github.lycheeappf.tmm.MainActivity] nach dem Redirect aufgerufen. */
    fun postCallbackUri(uri: Uri) {
        val code = uri.getQueryParameter("code") ?: return
        _pendingCode.tryEmit(code)
    }

    /**
     * Tauscht den Authorization-Code gegen Access- und Refresh-Token ein.
     * Muss aus einem IO-Coroutine-Scope aufgerufen werden.
     */
    suspend fun exchangeCode(code: String) {
        _state.update { TeslaAuthState.Loading }
        val verifier = pendingVerifier
        if (verifier == null) {
            _state.update { TeslaAuthState.Error("PKCE-Verifier fehlt — bitte erneut einloggen") }
            return
        }
        runCatching {
            val body = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("code_verifier", verifier)
                .add("client_id", TeslaOAuthConfig.CLIENT_ID)
                .add("client_secret", BuildConfig.TESLA_CLIENT_SECRET)
                .add("audience", TeslaOAuthConfig.TOKEN_AUDIENCE)
                .add("redirect_uri", TeslaOAuthConfig.REDIRECT_URI)
                .build()
            val req = Request.Builder().url(TeslaOAuthConfig.TOKEN_URL).post(body).build()
            val resp = httpClient.newCall(req).execute()
            val respBody = resp.body?.string() ?: error("Leerer Token-Response")
            if (!resp.isSuccessful) error("Token-Exchange HTTP ${resp.code}: $respBody")
            parseAndStoreTokens(respBody)
        }.onSuccess {
            pendingVerifier = null
            _state.update {
                TeslaAuthState.Authenticated(
                    selectedVin = tokenStore.readSelectedVin(),
                    expiresAtMs = tokenStore.readExpiresAtMs()
                )
            }
        }.onFailure { e ->
            Log.e(TAG, "Token exchange failed", e)
            _state.update { TeslaAuthState.Error(e.message ?: "Token-Exchange fehlgeschlagen") }
        }
    }

    /**
     * Prüft, ob der Access-Token abgelaufen ist (oder bald abläuft), und
     * erneuert ihn via Refresh-Token. Muss vor jedem Fleet-API-Call aufgerufen werden.
     */
    suspend fun refreshIfNeeded() {
        val expiresAt = tokenStore.readExpiresAtMs()
        if (expiresAt == 0L) return  // noch nicht authentifiziert
        val now = System.currentTimeMillis()
        if (now < expiresAt - TeslaOAuthConfig.REFRESH_EARLY_MS) return  // noch gültig

        val refreshToken = tokenStore.readRefreshToken() ?: return
        runCatching {
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", TeslaOAuthConfig.CLIENT_ID)
                .add("client_secret", BuildConfig.TESLA_CLIENT_SECRET)
                .add("audience", TeslaOAuthConfig.TOKEN_AUDIENCE)
                .build()
            val req = Request.Builder().url(TeslaOAuthConfig.TOKEN_URL).post(body).build()
            val resp = httpClient.newCall(req).execute()
            val respBody = resp.body?.string() ?: error("Leerer Refresh-Response")
            if (!resp.isSuccessful) error("Token-Refresh HTTP ${resp.code}: $respBody")
            parseAndStoreTokens(respBody)
        }.onSuccess {
            _state.update { current ->
                if (current is TeslaAuthState.Authenticated) {
                    current.copy(expiresAtMs = tokenStore.readExpiresAtMs())
                } else current
            }
        }.onFailure { e ->
            Log.w(TAG, "Token refresh failed", e)
        }
    }

    /** Gibt den aktuell gespeicherten Access-Token zurück (nach ggf. Refresh via [refreshIfNeeded]). */
    suspend fun readAccessToken(): String? = tokenStore.readAccessToken()

    /** Aktualisiert den gespeicherten VIN + numerische ID und emittiert neuen State. */
    suspend fun selectVehicle(vin: String, id: Long) {
        tokenStore.writeSelectedVin(vin)
        tokenStore.writeSelectedVehicleId(id)
        _state.update { current ->
            if (current is TeslaAuthState.Authenticated) current.copy(selectedVin = vin) else current
        }
    }

    suspend fun logout() {
        tokenStore.clear()
        _state.update { TeslaAuthState.NotAuthenticated }
    }

    // ---- Internals ----------------------------------------------------------

    private suspend fun parseAndStoreTokens(json: String) {
        val obj = JSONObject(json)
        val accessToken = obj.getString("access_token")
        val expiresIn = obj.optLong("expires_in", 3600L)
        val expiresAtMs = System.currentTimeMillis() + expiresIn * 1000L
        // Rotierender Refresh-Token: nur überschreiben, wenn vorhanden.
        obj.optString("refresh_token").takeIf { it.isNotBlank() }?.let {
            tokenStore.writeRefreshToken(it)
        }
        tokenStore.writeAccessToken(accessToken)
        tokenStore.writeExpiresAtMs(expiresAtMs)
    }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            .take(86)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    companion object {
        private const val TAG = "TeslaAuthManager"
    }
}
