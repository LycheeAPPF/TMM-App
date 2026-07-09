package io.github.lycheeappf.tmm.platform.tesla.auth

import android.net.Uri
import android.util.Base64
import android.util.Log
import io.github.lycheeappf.tmm.BuildConfig
import io.github.lycheeappf.tmm.core.di.IoDispatcher
import io.github.lycheeappf.tmm.core.di.TeslaHttpClient
import io.github.lycheeappf.tmm.core.util.Clock
import io.github.lycheeappf.tmm.core.util.coRunCatching
import io.github.lycheeappf.tmm.data.store.TeslaRegionStore
import io.github.lycheeappf.tmm.data.store.TeslaTokenStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 *
 * Alle blockierenden OkHttp-Calls sind INTERN auf den IO-Dispatcher confined —
 * Aufrufer müssen keinen Dispatcher wechseln.
 */
@Singleton
class TeslaAuthManager @Inject constructor(
    private val tokenStore: TeslaTokenStore,
    private val regionStore: TeslaRegionStore,
    @TeslaHttpClient private val httpClient: OkHttpClient,
    private val endpoints: TeslaOAuthEndpoints,
    private val clock: Clock,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val _state = MutableStateFlow<TeslaAuthState>(TeslaAuthState.Loading)
    val state: StateFlow<TeslaAuthState> = _state.asStateFlow()

    private val _pendingCode = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val pendingCode: SharedFlow<String> = _pendingCode.asSharedFlow()

    /**
     * Serialisiert Token-Refreshes: Teslas Refresh-Token ist single-use und
     * rotierend — zwei parallele Refreshes verbrennen den Token unwiederbringlich.
     */
    private val refreshMutex = Mutex()

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
            append(endpoints.authUrl)
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

    /** Tauscht den Authorization-Code gegen Access- und Refresh-Token ein. */
    suspend fun exchangeCode(code: String): Unit = withContext(ioDispatcher) {
        _state.update { TeslaAuthState.Loading }
        val verifier = pendingVerifier
        if (verifier == null) {
            _state.update { TeslaAuthState.Error("PKCE-Verifier fehlt — bitte erneut einloggen") }
            return@withContext
        }
        coRunCatching {
            requestToken(
                FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("code_verifier", verifier)
                    .add("client_id", TeslaOAuthConfig.CLIENT_ID)
                    .add("client_secret", BuildConfig.TESLA_CLIENT_SECRET)
                    .add("audience", tokenAudience())
                    .add("redirect_uri", TeslaOAuthConfig.REDIRECT_URI)
                    .build()
            )
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
     * erneuert ihn via Refresh-Token. Muss vor jedem Fleet-API-Call aufgerufen
     * werden. Single-flight: parallele Aufrufer warten am [refreshMutex]; nach
     * dem Lock wird die Ablaufzeit erneut geprüft, damit ein bereits erledigter
     * Refresh nicht wiederholt wird (der rotierte Refresh-Token wäre sonst verbrannt).
     */
    suspend fun refreshIfNeeded(): Unit = withContext(ioDispatcher) {
        if (!needsRefresh()) return@withContext
        refreshMutex.withLock {
            if (!needsRefresh()) return@withLock // paralleler Caller hat schon refresht
            val refreshToken = tokenStore.readRefreshToken() ?: return@withLock
            coRunCatching {
                requestToken(
                    FormBody.Builder()
                        .add("grant_type", "refresh_token")
                        .add("refresh_token", refreshToken)
                        .add("client_id", TeslaOAuthConfig.CLIENT_ID)
                        .add("client_secret", BuildConfig.TESLA_CLIENT_SECRET)
                        .add("audience", tokenAudience())
                        .build()
                )
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
        // Region ist account-abhängig (EU vs. NA) → beim Logout mit verwerfen.
        regionStore.writeFleetApiBaseUrl(null)
        _state.update { TeslaAuthState.NotAuthenticated }
    }

    // ---- Internals ----------------------------------------------------------

    private suspend fun needsRefresh(): Boolean {
        val expiresAt = tokenStore.readExpiresAtMs()
        if (expiresAt == 0L) return false // noch nicht authentifiziert
        return clock.now() >= expiresAt - TeslaOAuthConfig.REFRESH_EARLY_MS
    }

    /**
     * `audience` für Token-Requests: die entdeckte Region; solange unbekannt,
     * der erste Region-Kandidat (Discovery korrigiert das nachträglich).
     */
    private suspend fun tokenAudience(): String =
        regionStore.readTokenAudience() ?: endpoints.regionCandidates.first().trimEnd('/')

    /** Führt den Token-Request aus und persistiert das Ergebnis atomar. */
    private suspend fun requestToken(form: FormBody) {
        val req = Request.Builder().url(endpoints.tokenUrl).post(form).build()
        httpClient.newCall(req).execute().use { resp ->
            val respBody = resp.body?.string() ?: error("Leerer Token-Response")
            if (!resp.isSuccessful) error("Token-Request HTTP ${resp.code}: ${respBody.take(200)}")
            parseAndStoreTokens(respBody)
        }
    }

    private suspend fun parseAndStoreTokens(json: String) {
        val obj = JSONObject(json)
        val accessToken = obj.getString("access_token")
        val expiresIn = obj.optLong("expires_in", 3600L)
        // Rotierender Refresh-Token: nur überschreiben, wenn vorhanden.
        val refreshToken = obj.optString("refresh_token").takeIf { it.isNotBlank() }
        tokenStore.writeTokens(accessToken, refreshToken, clock.now() + expiresIn * 1000L)
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
