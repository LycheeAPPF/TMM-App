package io.github.lycheeappf.tmm.platform.tesla.auth

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.R
import io.github.lycheeappf.tmm.core.security.TeslaCredentials
import io.github.lycheeappf.tmm.core.security.TeslaCredentialsStore
import io.github.lycheeappf.tmm.core.util.Clock
import io.github.lycheeappf.tmm.data.store.TeslaPendingAuth
import io.github.lycheeappf.tmm.data.store.TeslaRegionStore
import io.github.lycheeappf.tmm.data.store.TeslaTokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tests für [TeslaAuthManager] gegen einen MockWebServer und In-Memory-Fakes
 * der Stores. Robolectric ist hier unvermeidbar: der Manager parst Token-
 * Responses mit `org.json.JSONObject` und den Callback mit `android.net.Uri` —
 * beides sind im Plain-JVM-Test nur Stubs (`isReturnDefaultValues`).
 *
 * Der IO-Dispatcher ist ein ECHTER 2-Thread-Pool, damit der Single-Flight-Test
 * zwei WIRKLICH parallele Refreshes fahren kann (auf dem Test-Dispatcher würden
 * die blockierenden OkHttp-Calls stillschweigend serialisiert).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TeslaAuthManagerTest {

    private var nowMs = 1_700_000_000_000L

    private lateinit var server: MockWebServer
    private lateinit var endpoints: TeslaOAuthEndpoints
    private lateinit var manager: TeslaAuthManager

    private val tokenStore = FakeTeslaTokenStore()
    private val credentialsStore = FakeTeslaCredentialsStore()
    private val regionStore = FakeTeslaRegionStore()
    private val ioDispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
    private val appScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        endpoints = TeslaOAuthEndpoints(
            authUrl = server.url("/oauth2/v3/authorize").toString(),
            tokenUrl = server.url("/oauth2/v3/token").toString(),
            regionCandidates = listOf(server.url("/").toString())
        )
        manager = buildManager()
    }

    @After fun teardown() {
        server.shutdown()
        appScope.cancel()
        ioDispatcher.close()
    }

    private fun buildManager(client: OkHttpClient = plainClient()): TeslaAuthManager =
        TeslaAuthManager(
            tokenStore = tokenStore,
            credentialsStore = credentialsStore,
            regionStore = regionStore,
            httpClient = client,
            endpoints = endpoints,
            clock = Clock { nowMs },
            ioDispatcher = ioDispatcher,
            appScope = appScope
        )

    private fun plainClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    /** HTTP-Schicht, die Cancellation wirft (Coroutine-Abbruch mitten im Call). */
    private fun cancellingClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { _ -> throw CancellationException("cancelled in http layer") }
        .build()

    private fun callbackUri(code: String = "auth-code-1", state: String = VALID_STATE): Uri =
        Uri.parse(
            "${TeslaOAuthConfig.REDIRECT_URI}?code=${Uri.encode(code)}&state=${Uri.encode(state)}"
        )

    private fun seedPendingAuth(state: String = VALID_STATE, createdAtMs: Long = nowMs) {
        tokenStore.pendingAuth =
            TeslaPendingAuth(state = state, codeVerifier = VERIFIER, createdAtMs = createdAtMs)
    }

    private fun seedAuthenticated(expiresAtMs: Long) {
        tokenStore.accessToken = "access-old"
        tokenStore.refreshToken = "refresh-old"
        tokenStore.expiresAtMs = expiresAtMs
    }

    private fun tokenResponse(
        access: String,
        refresh: String?,
        expiresInSec: Long = 3600L
    ): MockResponse = MockResponse().setResponseCode(200).setBody(
        buildString {
            append("""{"access_token":"$access","expires_in":$expiresInSec""")
            if (refresh != null) append(""","refresh_token":"$refresh"""")
            append("}")
        }
    )

    private fun decodedForm(): String =
        URLDecoder.decode(server.takeRequest().body.readUtf8(), "UTF-8")

    // ---- Code-Exchange ------------------------------------------------------

    @Test fun `code exchange success persists tokens and emits Authenticated`() = runTest {
        regionStore.baseUrl = "https://fleet-api.example.test/"
        seedPendingAuth()
        server.enqueue(tokenResponse(access = "access-1", refresh = "refresh-1"))

        manager.exchangeCallback(callbackUri(code = "auth-code-1", state = VALID_STATE))

        assertThat(tokenStore.accessToken).isEqualTo("access-1")
        assertThat(tokenStore.refreshToken).isEqualTo("refresh-1")
        assertThat(tokenStore.expiresAtMs).isEqualTo(nowMs + 3_600_000L)
        // One-shot: der Pending-Flow ist nach dem Callback verbraucht.
        assertThat(tokenStore.pendingAuth).isNull()
        assertThat(manager.state.value)
            .isEqualTo(TeslaAuthState.Authenticated(selectedVin = null, expiresAtMs = nowMs + 3_600_000L))

        val form = decodedForm()
        assertThat(form).contains("grant_type=authorization_code")
        assertThat(form).contains("code=auth-code-1")
        assertThat(form).contains("code_verifier=$VERIFIER")
        assertThat(form).contains("client_id=client-id-1")
        assertThat(form).contains("client_secret=client-secret-1")
        // audience kommt aus der PERSISTIERTEN Region (Fake-RegionStore), nicht aus dem Default.
        assertThat(form).contains("audience=https://fleet-api.example.test")
    }

    @Test fun `first auth discovers the region and persists it to the region store`() = runTest {
        seedPendingAuth()
        server.enqueue(tokenResponse(access = "access-1", refresh = "refresh-1"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"response":[]}"""))

        manager.exchangeCallback(callbackUri())

        assertThat(regionStore.baseUrl).isEqualTo(endpoints.regionCandidates.first())
        assertThat(server.requestCount).isEqualTo(2)
        server.takeRequest() // Token-Exchange
        val probe = server.takeRequest()
        assertThat(probe.path).isEqualTo("/api/1/vehicles")
        assertThat(probe.getHeader("Authorization")).isEqualTo("Bearer access-1")
        assertThat(manager.state.value).isInstanceOf(TeslaAuthState.Authenticated::class.java)
    }

    // ---- State-Validierung --------------------------------------------------

    @Test fun `mismatched state aborts the exchange without a token request`() = runTest {
        seedPendingAuth(state = "expected-state")

        manager.exchangeCallback(callbackUri(state = "attacker-state"))

        assertThat(server.requestCount).isEqualTo(0)
        assertThat(tokenStore.accessToken).isNull()
        assertThat(tokenStore.refreshToken).isNull()
        // One-shot auch im Fehlerfall: kein Weiterprobieren mit demselben Flow.
        assertThat(tokenStore.pendingAuth).isNull()
        assertThat(manager.state.value)
            .isEqualTo(TeslaAuthState.Error(R.string.tesla_error_state_invalid))
    }

    @Test fun `pending flow older than fifteen minutes aborts the exchange`() = runTest {
        seedPendingAuth(createdAtMs = nowMs)
        nowMs += 15 * 60 * 1000L + 1 // Flow-TTL via Fake-Clock überschritten

        manager.exchangeCallback(callbackUri())

        assertThat(server.requestCount).isEqualTo(0)
        assertThat(tokenStore.accessToken).isNull()
        assertThat(manager.state.value)
            .isEqualTo(TeslaAuthState.Error(R.string.tesla_error_state_invalid))
    }

    // ---- Refresh ------------------------------------------------------------

    @Test fun `refresh rotates and persists the new refresh token`() = runTest {
        regionStore.baseUrl = "https://fleet-api.example.test/"
        seedAuthenticated(expiresAtMs = nowMs + 1_000L)
        manager.init()
        server.enqueue(tokenResponse(access = "access-2", refresh = "refresh-2"))

        manager.refreshIfNeeded()

        assertThat(tokenStore.accessToken).isEqualTo("access-2")
        assertThat(tokenStore.refreshToken).isEqualTo("refresh-2")
        assertThat(tokenStore.expiresAtMs).isEqualTo(nowMs + 3_600_000L)
        assertThat(manager.state.value)
            .isEqualTo(TeslaAuthState.Authenticated(selectedVin = null, expiresAtMs = nowMs + 3_600_000L))

        val form = decodedForm()
        assertThat(form).contains("grant_type=refresh_token")
        assertThat(form).contains("refresh_token=refresh-old")
        assertThat(form).contains("audience=https://fleet-api.example.test")
    }

    @Test fun `refresh response without rotated token keeps the existing refresh token`() = runTest {
        seedAuthenticated(expiresAtMs = nowMs + 1_000L)
        server.enqueue(tokenResponse(access = "access-2", refresh = null))

        manager.refreshIfNeeded()

        assertThat(tokenStore.accessToken).isEqualTo("access-2")
        assertThat(tokenStore.refreshToken).isEqualTo("refresh-old")
    }

    @Test fun `refresh is skipped while the token is outside the early-refresh window`() = runTest {
        seedAuthenticated(expiresAtMs = nowMs + TeslaOAuthConfig.REFRESH_EARLY_MS + 60_000L)

        manager.refreshIfNeeded()

        assertThat(server.requestCount).isEqualTo(0)
        assertThat(tokenStore.accessToken).isEqualTo("access-old")
    }

    @Test fun `advancing the fake clock into the early-refresh window triggers a refresh`() = runTest {
        seedAuthenticated(expiresAtMs = nowMs + TeslaOAuthConfig.REFRESH_EARLY_MS + 60_000L)

        manager.refreshIfNeeded()
        assertThat(server.requestCount).isEqualTo(0)

        nowMs += 61_000L // jetzt innerhalb des Refresh-Puffers
        server.enqueue(tokenResponse(access = "access-2", refresh = "refresh-2"))
        manager.refreshIfNeeded()

        assertThat(server.requestCount).isEqualTo(1)
        assertThat(tokenStore.accessToken).isEqualTo("access-2")
    }

    @Test fun `refresh is a no-op when never authenticated`() = runTest {
        // expiresAtMs == 0 → nie authentifiziert, es darf kein Request rausgehen.
        manager.refreshIfNeeded()

        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `two concurrent refreshes send exactly one token request`() = runTest {
        seedAuthenticated(expiresAtMs = nowMs + 1_000L)
        server.enqueue(
            tokenResponse(access = "access-2", refresh = "refresh-2")
                .setBodyDelay(300, TimeUnit.MILLISECONDS)
        )

        listOf(
            launch(ioDispatcher) { manager.refreshIfNeeded() },
            launch(ioDispatcher) { manager.refreshIfNeeded() }
        ).joinAll()

        // Single-flight: der zweite Caller wartet am Mutex und re-checkt die
        // Ablaufzeit — der rotierte Refresh-Token wird nicht doppelt verbrannt.
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(tokenStore.writeTokensCalls).isEqualTo(1)
        assertThat(tokenStore.accessToken).isEqualTo("access-2")
        assertThat(tokenStore.refreshToken).isEqualTo("refresh-2")
    }

    // ---- Cancellation -------------------------------------------------------

    @Test fun `cancellation during refresh propagates instead of being swallowed`() = runTest {
        seedAuthenticated(expiresAtMs = nowMs + 1_000L)
        val cancellingManager = buildManager(client = cancellingClient())

        val thrown = runCatching { cancellingManager.refreshIfNeeded() }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        assertThat(tokenStore.accessToken).isEqualTo("access-old")
        assertThat(tokenStore.refreshToken).isEqualTo("refresh-old")
    }

    @Test fun `cancellation during code exchange propagates instead of surfacing as an auth error`() = runTest {
        regionStore.baseUrl = "https://fleet-api.example.test/"
        seedPendingAuth()
        val cancellingManager = buildManager(client = cancellingClient())

        val thrown = runCatching { cancellingManager.exchangeCallback(callbackUri()) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        assertThat(tokenStore.accessToken).isNull()
        // Cancellation ist KEIN Auth-Fehler: der Error-Pfad darf nicht feuern.
        assertThat(cancellingManager.state.value).isEqualTo(TeslaAuthState.Loading)
    }

    private companion object {
        const val VALID_STATE = "state-token-1"
        const val VERIFIER = "verifier-1"
    }
}

// ---- In-Memory-Fakes (Muster: ApiKeyStore-Fakes) -----------------------------

private class FakeTeslaTokenStore : TeslaTokenStore {
    @Volatile var accessToken: String? = null
    @Volatile var refreshToken: String? = null
    @Volatile var expiresAtMs: Long = 0L
    @Volatile var selectedVin: String? = null
    @Volatile var selectedVehicleId: Long? = null
    @Volatile var pendingAuth: TeslaPendingAuth? = null
    @Volatile var writeTokensCalls: Int = 0

    override suspend fun readAccessToken(): String? = accessToken
    override suspend fun readRefreshToken(): String? = refreshToken
    override suspend fun readExpiresAtMs(): Long = expiresAtMs

    override suspend fun writeTokens(accessToken: String, refreshToken: String?, expiresAtMs: Long) {
        writeTokensCalls++
        this.accessToken = accessToken
        // Vertrag: null lässt den vorhandenen (rotierenden) Refresh-Token stehen.
        if (refreshToken != null) this.refreshToken = refreshToken
        this.expiresAtMs = expiresAtMs
    }

    override suspend fun readSelectedVin(): String? = selectedVin
    override suspend fun writeSelectedVin(vin: String) { selectedVin = vin }
    override fun selectedVinFlow(): Flow<String?> = flowOf(selectedVin)
    override suspend fun readSelectedVehicleId(): Long? = selectedVehicleId
    override suspend fun writeSelectedVehicleId(id: Long) { selectedVehicleId = id }
    override suspend fun readPendingAuth(): TeslaPendingAuth? = pendingAuth
    override suspend fun writePendingAuth(pending: TeslaPendingAuth?) { pendingAuth = pending }
    override suspend fun isAuthenticated(): Boolean = refreshToken != null

    override suspend fun clear() {
        accessToken = null
        refreshToken = null
        expiresAtMs = 0L
        selectedVin = null
        selectedVehicleId = null
        pendingAuth = null
    }
}

private class FakeTeslaCredentialsStore(
    @Volatile var credentials: TeslaCredentials? =
        TeslaCredentials(clientId = "client-id-1", clientSecret = "client-secret-1")
) : TeslaCredentialsStore {
    override suspend fun read(): TeslaCredentials? = credentials
    override suspend fun write(credentials: TeslaCredentials) { this.credentials = credentials }
    override suspend fun clear() { credentials = null }
    override suspend fun isSet(): Boolean = credentials != null
}

private class FakeTeslaRegionStore(@Volatile var baseUrl: String? = null) : TeslaRegionStore {
    override suspend fun readFleetApiBaseUrl(): String? = baseUrl
    override suspend fun writeFleetApiBaseUrl(url: String?) { baseUrl = url }
}
