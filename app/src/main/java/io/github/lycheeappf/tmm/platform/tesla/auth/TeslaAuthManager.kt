package io.github.lycheeappf.tmm.platform.tesla.auth

import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.annotation.StringRes
import io.github.lycheeappf.tmm.R
import io.github.lycheeappf.tmm.core.di.ApplicationScope
import io.github.lycheeappf.tmm.core.di.IoDispatcher
import io.github.lycheeappf.tmm.core.di.TeslaHttpClient
import io.github.lycheeappf.tmm.core.security.TeslaCredentials
import io.github.lycheeappf.tmm.core.security.TeslaCredentialsStore
import io.github.lycheeappf.tmm.core.util.Clock
import io.github.lycheeappf.tmm.core.util.coRunCatching
import io.github.lycheeappf.tmm.data.store.SettingsStore
import io.github.lycheeappf.tmm.data.store.TeslaPendingAuth
import io.github.lycheeappf.tmm.data.store.TeslaRegionStore
import io.github.lycheeappf.tmm.data.store.TeslaTokenStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    /**
     * Keine (oder nicht mehr entschlüsselbare) Nutzer-Credentials hinterlegt —
     * alle Fleet-Features sind aus, die UI zeigt den Einrichtungshinweis.
     * Pendant zu `LlmProviderError.MissingKey` beim xAI-Key.
     */
    data object MissingCredentials : TeslaAuthState()
    data object NotAuthenticated : TeslaAuthState()
    data object Loading : TeslaAuthState()
    data class Authenticated(val selectedVin: String?, val expiresAtMs: Long) : TeslaAuthState()

    /**
     * Fehlgeschlagener Auth-Schritt. [messageRes] ist eine lokalisierte
     * String-Ressource (EN+DE) und wird erst beim Rendern aufgelöst — so folgt
     * die Meldung einem Sprachwechsel. [detail] ist optionale technische
     * Zusatzinfo (HTTP-Status/OAuth-Fehlerbody), bewusst unübersetzt.
     */
    data class Error(@StringRes val messageRes: Int, val detail: String? = null) : TeslaAuthState()
}

/**
 * Verwaltet den Tesla OAuth2-PKCE-Flow und den Token-Lebenszyklus.
 *
 * Ablauf:
 *  1. [startAuth] → gibt die Auth-URL zurück; die UI öffnet einen Chrome Custom Tab.
 *     `state` + PKCE-Verifier werden PERSISTIERT (überleben Prozess-Tod im Custom Tab).
 *  2. Tesla redirectet auf `io.github.lycheeappf.tmm://tesla/callback?code=...&state=...`
 *  3. [MainActivity] reicht die Redirect-URI an [handleCallback] durch — der Exchange
 *     läuft eager im application-scoped [appScope] und funktioniert damit auch bei
 *     Kaltstart, ohne dass irgendein ViewModel lebt.
 *  4. Die UI beobachtet ausschließlich [state].
 *  5. [refreshIfNeeded] wird vor jedem Fleet-API-Call gerufen (lazy Refresh, 20 min Puffer).
 *
 * Alle blockierenden OkHttp-Calls sind INTERN auf den IO-Dispatcher confined —
 * Aufrufer müssen keinen Dispatcher wechseln.
 */
@Singleton
class TeslaAuthManager @Inject constructor(
    private val tokenStore: TeslaTokenStore,
    private val credentialsStore: TeslaCredentialsStore,
    private val regionStore: TeslaRegionStore,
    private val settingsStore: SettingsStore,
    @TeslaHttpClient private val httpClient: OkHttpClient,
    private val endpoints: TeslaOAuthEndpoints,
    private val clock: Clock,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {
    private val _state = MutableStateFlow<TeslaAuthState>(TeslaAuthState.Loading)
    val state: StateFlow<TeslaAuthState> = _state.asStateFlow()

    /**
     * Serialisiert Token-Refreshes: Teslas Refresh-Token ist single-use und
     * rotierend — zwei parallele Refreshes verbrennen den Token unwiederbringlich.
     */
    private val refreshMutex = Mutex()

    suspend fun init() {
        when {
            !credentialsStore.isSet() -> _state.update { TeslaAuthState.MissingCredentials }
            tokenStore.isAuthenticated() -> _state.update {
                TeslaAuthState.Authenticated(
                    selectedVin = tokenStore.readSelectedVin(),
                    expiresAtMs = tokenStore.readExpiresAtMs()
                )
            }
            else -> _state.update { TeslaAuthState.NotAuthenticated }
        }
    }

    /**
     * Schnellcheck für Turn-/Tool-Zeit-Gates (Grok-Navigations-Tool): sind
     * Nutzer-Credentials hinterlegt? Wird bei JEDEM Fleet-Call erneut geprüft,
     * weil der Tesla-Auto-Reply-Pfad die UI-Gates umgeht.
     */
    suspend fun hasCredentials(): Boolean = credentialsStore.isSet()

    /** Persistiert Nutzer-Credentials (verschlüsselt) und aktualisiert den Auth-State. */
    suspend fun setCredentials(clientId: String, clientSecret: String): Unit =
        withContext(ioDispatcher) {
            credentialsStore.write(TeslaCredentials(clientId = clientId, clientSecret = clientSecret))
            init()
        }

    /**
     * Entfernt die Credentials und ALLE davon abhängigen Artefakte (Tokens,
     * Region, Fahrzeug-Verknüpfungen der Tesla-Geräte) — ohne client_id/secret
     * sind die Tokens nicht mehr refreshbar, und die VINs gehören zum Account.
     */
    suspend fun clearCredentials(): Unit = withContext(ioDispatcher) {
        credentialsStore.clear()
        tokenStore.clear()
        regionStore.writeFleetApiBaseUrl(null)
        settingsStore.clearTeslaVehicleLinks()
        _state.update { TeslaAuthState.MissingCredentials }
    }

    /**
     * Generiert PKCE-Verifier + Challenge sowie einen frischen per-Flow
     * `state`-Parameter (SecureRandom) und baut die Authorization-URL auf.
     * Beides wird persistiert, damit der Callback auch nach Prozess-Tod
     * validiert und eingetauscht werden kann. Die UI öffnet die URL in einem
     * Chrome Custom Tab.
     * Null, wenn keine Credentials hinterlegt sind (State → [TeslaAuthState.MissingCredentials]).
     */
    suspend fun startAuth(): String? = withContext(ioDispatcher) {
        val credentials = credentialsStore.read()
        if (credentials == null) {
            _state.update { TeslaAuthState.MissingCredentials }
            return@withContext null
        }
        val verifier = generateCodeVerifier()
        val stateToken = generateStateToken()
        tokenStore.writePendingAuth(
            TeslaPendingAuth(state = stateToken, codeVerifier = verifier, createdAtMs = clock.now())
        )
        val challenge = generateCodeChallenge(verifier)
        buildString {
            append(endpoints.authUrl)
            append("?response_type=code")
            append("&client_id=").append(Uri.encode(credentials.clientId))
            append("&redirect_uri=").append(Uri.encode(TeslaOAuthConfig.REDIRECT_URI))
            append("&scope=").append(Uri.encode(TeslaOAuthConfig.SCOPES))
            append("&code_challenge=").append(challenge)
            append("&code_challenge_method=S256")
            append("&state=").append(Uri.encode(stateToken))
        }
    }

    /**
     * Wird von [io.github.lycheeappf.tmm.MainActivity] mit der Redirect-URI
     * aufgerufen. Startet den Exchange eager im application-scoped [appScope] —
     * er läuft auch dann zu Ende (Tokens persistiert), wenn keine UI mehr lebt.
     */
    fun handleCallback(uri: Uri) {
        if (!isTeslaCallback(uri)) return
        appScope.launch { exchangeCallback(uri) }
    }

    /**
     * Validiert den Redirect (persistierter per-Flow `state`, Flow-TTL) und
     * tauscht den Authorization-Code gegen Access-/Refresh-Token. Bricht bei
     * State-Mismatch ab. Sichtbar für Tests; Produktion geht über [handleCallback].
     */
    suspend fun exchangeCallback(uri: Uri): Unit = withContext(ioDispatcher) {
        if (!isTeslaCallback(uri)) return@withContext
        // One-shot: JEDER Callback konsumiert den Pending-Flow — ein Code ist
        // ohnehin single-use, und ein Angreifer darf nicht weiterprobieren können.
        val pending = tokenStore.readPendingAuth()
        tokenStore.writePendingAuth(null)

        uri.getQueryParameter("error")?.let { error ->
            Log.w(TAG, "OAuth callback returned error (len=${error.length})")
            _state.update { TeslaAuthState.Error(R.string.tesla_error_login_cancelled) }
            return@withContext
        }
        val code = uri.getQueryParameter("code")
        if (code.isNullOrBlank()) return@withContext

        val returnedState = uri.getQueryParameter("state")
        val expired = pending != null && clock.now() - pending.createdAtMs > AUTH_FLOW_TTL_MS
        if (pending == null || expired || returnedState.isNullOrBlank() || returnedState != pending.state) {
            Log.w(TAG, "OAuth state validation failed (pending=${pending != null}, expired=$expired)")
            _state.update { TeslaAuthState.Error(R.string.tesla_error_state_invalid) }
            return@withContext
        }
        val credentials = credentialsStore.read()
        if (credentials == null) {
            _state.update { TeslaAuthState.MissingCredentials }
            return@withContext
        }

        _state.update { TeslaAuthState.Loading }
        val knownAudience = regionStore.readTokenAudience()
        coRunCatching {
            requestToken(
                FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("code_verifier", pending.codeVerifier)
                    .add("client_id", credentials.clientId)
                    .add("client_secret", credentials.clientSecret)
                    .add("audience", knownAudience ?: defaultAudience())
                    .add("redirect_uri", TeslaOAuthConfig.REDIRECT_URI)
                    .build()
            )
        }.onSuccess {
            // Erste Anmeldung: Region noch unbekannt → jetzt entdecken, damit
            // künftige Token-Requests die richtige audience tragen. Best-effort —
            // der Command-Client discovert bei Bedarf erneut.
            if (knownAudience == null) coRunCatching { discoverRegion(credentials) }
            _state.update {
                TeslaAuthState.Authenticated(
                    selectedVin = tokenStore.readSelectedVin(),
                    expiresAtMs = tokenStore.readExpiresAtMs()
                )
            }
        }.onFailure { e ->
            Log.e(TAG, "Token exchange failed", e)
            _state.update {
                TeslaAuthState.Error(R.string.tesla_error_token_exchange, detail = e.message)
            }
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
            val credentials = credentialsStore.read()
            if (credentials == null) {
                // Turn-Zeit-Re-Check: Credentials wurden entfernt/unlesbar →
                // Refresh unmöglich, Fleet-Features typisiert gegated.
                _state.update { TeslaAuthState.MissingCredentials }
                return@withLock
            }
            coRunCatching {
                requestToken(
                    FormBody.Builder()
                        .add("grant_type", "refresh_token")
                        .add("refresh_token", refreshToken)
                        .add("client_id", credentials.clientId)
                        .add("client_secret", credentials.clientSecret)
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
        // Fahrzeug-Verknüpfungen hängen am Account (VINs) — wie die gewählte VIN in tokenStore.clear().
        settingsStore.clearTeslaVehicleLinks()
        _state.update {
            if (credentialsStore.isSet()) TeslaAuthState.NotAuthenticated
            else TeslaAuthState.MissingCredentials
        }
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
        regionStore.readTokenAudience() ?: defaultAudience()

    private fun defaultAudience(): String = endpoints.regionCandidates.first().trimEnd('/')

    /**
     * Region-Discovery direkt nach der Erstanmeldung: probt die Kandidaten mit
     * dem frischen Access-Token; antwortet einer 2xx, ist das die Account-Region.
     * Trägt das Token die falsche audience (412 überall), wird per Refresh-Grant
     * ein Token für den nächsten Kandidaten ausgestellt und erneut geprobt.
     */
    private suspend fun discoverRegion(credentials: TeslaCredentials) {
        for ((index, base) in endpoints.regionCandidates.withIndex()) {
            if (index > 0) {
                // Token für DIESE audience ausstellen lassen (Refresh rotiert mit).
                val refreshToken = tokenStore.readRefreshToken() ?: return
                val reissued = coRunCatching {
                    requestToken(
                        FormBody.Builder()
                            .add("grant_type", "refresh_token")
                            .add("refresh_token", refreshToken)
                            .add("client_id", credentials.clientId)
                            .add("client_secret", credentials.clientSecret)
                            .add("audience", base.trimEnd('/'))
                            .build()
                    )
                }
                if (reissued.isFailure) return
            }
            val accessToken = tokenStore.readAccessToken() ?: return
            if (probeRegion(base, accessToken)) {
                Log.i(TAG, "Region discovered during first auth")
                regionStore.writeFleetApiBaseUrl(base)
                return
            }
        }
        Log.w(TAG, "Region discovery during first auth failed for all candidates")
    }

    /** GET `${base}api/1/vehicles` mit Bearer-Token; 2xx = Region passt. */
    private fun probeRegion(base: String, accessToken: String): Boolean = try {
        val req = Request.Builder()
            .url("${base}api/1/vehicles")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        httpClient.newCall(req).execute().use { it.isSuccessful }
    } catch (e: java.io.IOException) {
        Log.w(TAG, "Region probe failed", e)
        false
    }

    private fun isTeslaCallback(uri: Uri): Boolean =
        uri.toString().startsWith(TeslaOAuthConfig.REDIRECT_URI)

    /** Führt den Token-Request aus und persistiert das Ergebnis atomar. */
    private suspend fun requestToken(form: FormBody) {
        val req = Request.Builder().url(endpoints.tokenUrl).post(form).build()
        httpClient.newCall(req).execute().use { resp ->
            // message landet nur als technisches Detail in TeslaAuthState.Error/Logcat.
            val respBody = resp.body?.string() ?: error("empty token response")
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${respBody.take(200)}")
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

    /** Per-Flow CSRF-Schutz: 256 Bit SecureRandom, URL-safe Base64. */
    private fun generateStateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    companion object {
        private const val TAG = "TeslaAuthManager"

        /** Ein Pending-OAuth-Flow ist maximal 15 Minuten gültig. */
        private const val AUTH_FLOW_TTL_MS = 15 * 60 * 1000L
    }
}
