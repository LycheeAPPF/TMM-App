package io.github.lycheeappf.tmm.platform.tesla.api

import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.core.util.Clock
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.core.util.LogFileStore
import io.github.lycheeappf.tmm.data.store.TeslaRegionStore
import io.github.lycheeappf.tmm.data.store.TeslaTokenStore
import io.github.lycheeappf.tmm.platform.tesla.auth.TeslaAuthManager
import io.github.lycheeappf.tmm.platform.tesla.auth.TeslaOAuthConfig
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * MockWebServer-Tests für [TeslaVehicleCommandClient]: Region-Discovery
 * (Kandidaten-Probe + Persistenz in [TeslaRegionStore]), Wake-up-and-retry bei
 * schlafendem Fahrzeug, Mapping der HTTP-Fehler auf die sealed
 * [TeslaCommandError]-Hierarchie sowie die PII-Regel — [LogBuffer] darf nach
 * keinem Call Ziel-Adresse, VIN oder Koordinaten enthalten (nur Metadaten).
 */
class TeslaVehicleCommandClientTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var client: TeslaVehicleCommandClient
    private lateinit var regionStore: FakeTeslaRegionStore
    private lateinit var logBuffer: LogBuffer
    private lateinit var euBase: String
    private lateinit var naBase: String

    private val authManager: TeslaAuthManager = mockk()
    private val tokenStore: TeslaTokenStore = mockk()
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        euBase = server.url("/eu/").toString()
        naBase = server.url("/na/").toString()
        // REGION_CANDIDATES ist eine statische Liste echter Tesla-URLs — für die
        // Discovery-Pfade auf den MockWebServer umbiegen.
        mockkObject(TeslaOAuthConfig)
        every { TeslaOAuthConfig.REGION_CANDIDATES } returns listOf(euBase, naBase)

        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(
                OkHttpClient.Builder()
                    .connectTimeout(2, TimeUnit.SECONDS)
                    .readTimeout(2, TimeUnit.SECONDS)
                    .build()
            )
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        val api = retrofit.create(TeslaFleetApi::class.java)

        coEvery { authManager.refreshIfNeeded() } just Runs
        coEvery { authManager.hasCredentials() } returns true
        coEvery { authManager.readAccessToken() } returns "access-token"
        coEvery { tokenStore.readSelectedVehicleId() } returns VEHICLE_ID

        regionStore = FakeTeslaRegionStore()
        logBuffer = LogBuffer(
            LogFileStore(File(tmp.root, "diagnostics"), UnconfinedTestDispatcher()),
            UnconfinedTestDispatcher()
        )
        client = TeslaVehicleCommandClient(
            api, authManager, tokenStore, regionStore, logBuffer, Clock { FIXED_NOW }
        )
    }

    @After fun teardown() {
        unmockkObject(TeslaOAuthConfig)
        server.shutdown()
    }

    private fun vehiclesJson() =
        """{"response":[{"id":$VEHICLE_ID,"vin":"$VIN","display_name":"Karl"}],"count":1}"""

    private fun commandOk() = """{"response":{"result":true}}"""

    /**
     * 408 „Fahrzeug schläft". `Retry-After` ist nötig, weil OkHttp ein 408 OHNE
     * diesen Header transparent einmal wiederholt — der stille Client-Retry würde
     * die nächste enqueued Mock-Response verschlucken und die App-eigene
     * Wake-up-Logik käme nie zum Zug.
     */
    private fun vehicleAsleep() =
        MockResponse().setResponseCode(408).setHeader("Retry-After", "30").setBody("vehicle unavailable")

    private fun loggedText() = logBuffer.snapshot().joinToString("\n") { "${it.tag} ${it.message}" }

    // ---- Region-Discovery ----------------------------------------------------

    @Test fun `region discovery probes candidates in order and persists the matching region`() = runTest {
        server.enqueue(MockResponse().setResponseCode(412).setBody("wrong region"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(vehiclesJson()))

        val vehicles = client.listVehicles()

        assertThat(vehicles).hasSize(1)
        assertThat(vehicles.first().vin).isEqualTo(VIN)
        assertThat(vehicles.first().id).isEqualTo(VEHICLE_ID)
        assertThat(regionStore.baseUrl).isEqualTo(naBase)
        assertThat(server.requestCount).isEqualTo(2)
        val first = server.takeRequest()
        assertThat(first.path).isEqualTo("/eu/api/1/vehicles")
        assertThat(first.getHeader("Authorization")).isEqualTo("Bearer access-token")
        assertThat(server.takeRequest().path).isEqualTo("/na/api/1/vehicles")
    }

    @Test fun `cached region is reused without a discovery probe`() = runTest {
        regionStore.baseUrl = euBase
        server.enqueue(MockResponse().setResponseCode(200).setBody(vehiclesJson()))

        val vehicles = client.listVehicles()

        assertThat(vehicles.map { it.vin }).containsExactly(VIN)
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(server.takeRequest().path).isEqualTo("/eu/api/1/vehicles")
        assertThat(regionStore.baseUrl).isEqualTo(euBase)
    }

    @Test fun `stale cached region is discarded and rediscovered`() = runTest {
        regionStore.baseUrl = euBase
        server.enqueue(MockResponse().setResponseCode(412).setBody("moved")) // gecachte Region
        server.enqueue(MockResponse().setResponseCode(412).setBody("nope")) // Kandidat EU
        server.enqueue(MockResponse().setResponseCode(200).setBody(vehiclesJson())) // Kandidat NA

        val vehicles = client.listVehicles()

        assertThat(vehicles).hasSize(1)
        assertThat(server.requestCount).isEqualTo(3)
        assertThat(regionStore.baseUrl).isEqualTo(naBase)
    }

    @Test fun `region discovery failing on all candidates throws RegionDiscoveryFailed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(412).setBody("nope"))
        server.enqueue(MockResponse().setResponseCode(412).setBody("nope"))

        val ex = runCatching { client.listVehicles() }.exceptionOrNull()

        assertThat(ex).isInstanceOf(TeslaCommandError.RegionDiscoveryFailed::class.java)
        assertThat(regionStore.baseUrl).isNull()
    }

    @Test fun `unauthorized during discovery throws Unauthorized instead of probing on`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("expired"))

        val ex = runCatching { client.listVehicles() }.exceptionOrNull()

        assertThat(ex).isInstanceOf(TeslaCommandError.Unauthorized::class.java)
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(regionStore.baseUrl).isNull()
    }

    // ---- Wake-up-and-retry ---------------------------------------------------

    @Test fun `asleep vehicle is woken up and the command retried`() = runTest {
        regionStore.baseUrl = euBase
        server.enqueue(vehicleAsleep())
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"response":{"state":"online"}}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(commandOk()))

        client.navigate(VIN, ADDRESS)

        assertThat(server.requestCount).isEqualTo(3)
        val attempt = server.takeRequest()
        assertThat(attempt.path).isEqualTo("/eu/api/1/vehicles/$VIN/command/navigation_request")
        assertThat(attempt.body.readUtf8()).contains(ADDRESS)
        assertThat(server.takeRequest().path).isEqualTo("/eu/api/1/vehicles/$VEHICLE_ID/wake_up")
        assertThat(server.takeRequest().path).isEqualTo("/eu/api/1/vehicles/$VIN/command/navigation_request")
    }

    @Test fun `successful command does not wake the vehicle`() = runTest {
        regionStore.baseUrl = euBase
        server.enqueue(MockResponse().setResponseCode(200).setBody(commandOk()))

        client.navigate(VIN, ADDRESS)

        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun `navigation request body carries the injected clock timestamp`() = runTest {
        regionStore.baseUrl = euBase
        server.enqueue(MockResponse().setResponseCode(200).setBody(commandOk()))

        client.navigate(VIN, ADDRESS)

        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"timestamp_ms\":$FIXED_NOW")
    }

    // ---- Fehler-Mapping ------------------------------------------------------

    @Test fun `http 401 maps to Unauthorized`() = runTest {
        regionStore.baseUrl = euBase
        server.enqueue(MockResponse().setResponseCode(401).setBody("expired"))

        val ex = runCatching { client.navigate(VIN, ADDRESS) }.exceptionOrNull()

        assertThat(ex).isInstanceOf(TeslaCommandError.Unauthorized::class.java)
    }

    @Test fun `vehicle still 404 after wake up maps to VehicleNotFound`() = runTest {
        regionStore.baseUrl = euBase
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"response":{"state":"waking"}}"""))
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))

        val ex = runCatching { client.navigate(VIN, ADDRESS) }.exceptionOrNull()

        assertThat(ex).isInstanceOf(TeslaCommandError.VehicleNotFound::class.java)
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test fun `command result false maps to CommandRejected with the reason`() = runTest {
        regionStore.baseUrl = euBase
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"response":{"result":false,"reason":"car_in_drive"}}""")
        )

        val ex = runCatching { client.navigate(VIN, ADDRESS) }.exceptionOrNull()

        assertThat(ex).isInstanceOf(TeslaCommandError.CommandRejected::class.java)
        assertThat((ex as TeslaCommandError.CommandRejected).reason).isEqualTo("car_in_drive")
    }

    @Test fun `unexpected http code maps to Unknown with code and truncated body`() = runTest {
        regionStore.baseUrl = euBase
        server.enqueue(MockResponse().setResponseCode(500).setBody("x".repeat(300)))

        val ex = runCatching { client.navigate(VIN, ADDRESS) }.exceptionOrNull()

        assertThat(ex).isInstanceOf(TeslaCommandError.Unknown::class.java)
        val unknown = ex as TeslaCommandError.Unknown
        assertThat(unknown.code).isEqualTo(500)
        assertThat(unknown.body).hasLength(200)
    }

    @Test fun `missing credentials abort before any fleet call`() = runTest {
        regionStore.baseUrl = euBase
        coEvery { authManager.hasCredentials() } returns false

        val ex = runCatching { client.navigate(VIN, ADDRESS) }.exceptionOrNull()

        assertThat(ex).isInstanceOf(TeslaCommandError.MissingCredentials::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }

    // ---- PII-Regel: LogBuffer bleibt frei von Ziel, VIN und Koordinaten ------

    @Test fun `log buffer never contains destination or vin after navigate with wake up`() = runTest {
        regionStore.baseUrl = euBase
        server.enqueue(vehicleAsleep())
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"response":{"state":"online"}}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(commandOk()))

        client.navigate(VIN, ADDRESS)

        assertThat(logBuffer.snapshot()).isNotEmpty()
        val logged = loggedText()
        assertThat(logged).doesNotContain(ADDRESS)
        assertThat(logged).doesNotContain("Musterstraße")
        assertThat(logged).doesNotContain(VIN)
    }

    @Test fun `log buffer never contains coordinates or vin after navigateGps`() = runTest {
        regionStore.baseUrl = euBase
        server.enqueue(MockResponse().setResponseCode(200).setBody(commandOk()))

        client.navigateGps(VIN, 52.5200, 13.4050)

        assertThat(logBuffer.snapshot()).isNotEmpty()
        val logged = loggedText()
        assertThat(logged).doesNotContain("52.5")
        assertThat(logged).doesNotContain("13.4")
        assertThat(logged).doesNotContain(VIN)
    }

    @Test fun `log buffer never contains the rejection reason text`() = runTest {
        regionStore.baseUrl = euBase
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"response":{"result":false,"reason":"driver left car at $ADDRESS"}}""")
        )

        runCatching { client.navigate(VIN, ADDRESS) }

        assertThat(logBuffer.snapshot()).isNotEmpty()
        val logged = loggedText()
        assertThat(logged).doesNotContain(ADDRESS)
        assertThat(logged).doesNotContain("driver left car")
    }

    @Test fun `log buffer never echoes an error body after an unknown http error`() = runTest {
        regionStore.baseUrl = euBase
        server.enqueue(
            MockResponse().setResponseCode(500).setBody("""{"error":"could not route to $ADDRESS"}""")
        )

        runCatching { client.navigate(VIN, ADDRESS) }

        assertThat(logBuffer.snapshot()).isNotEmpty()
        val logged = loggedText()
        assertThat(logged).doesNotContain(ADDRESS)
        assertThat(logged).doesNotContain("could not route")
    }

    // ---- Fakes ---------------------------------------------------------------

    private class FakeTeslaRegionStore(var baseUrl: String? = null) : TeslaRegionStore {
        override suspend fun readFleetApiBaseUrl(): String? = baseUrl
        override suspend fun writeFleetApiBaseUrl(url: String?) { baseUrl = url }
    }

    companion object {
        private const val VIN = "5YJ3E1EA7KF317000"
        private const val VEHICLE_ID = 4711L
        private const val ADDRESS = "Musterstraße 42, 10999 Berlin"
        private const val FIXED_NOW = 1_720_000_000_000L
    }
}
