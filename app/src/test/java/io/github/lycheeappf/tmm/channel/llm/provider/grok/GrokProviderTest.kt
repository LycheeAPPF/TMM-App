package io.github.lycheeappf.tmm.channel.llm.provider.grok

import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.channel.llm.provider.LlmProviderError
import io.github.lycheeappf.tmm.channel.llm.provider.LlmRequest
import io.github.lycheeappf.tmm.channel.llm.provider.LlmTurn
import io.github.lycheeappf.tmm.core.network.ConnectivityChecker
import io.github.lycheeappf.tmm.core.security.ApiKeyStore
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

class GrokProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: GrokProvider
    private val apiKeyStore: ApiKeyStore = mockk()
    private val connectivity: ConnectivityChecker = mockk()
    private val logBuffer: LogBuffer = mockk(relaxed = true)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        val api = retrofit.create(GrokApi::class.java)
        every { connectivity.isOnline() } returns true
        coEvery { apiKeyStore.read() } returns "test-key-123"
        provider = GrokProvider(api, apiKeyStore, connectivity, logBuffer)
    }

    @After fun teardown() { server.shutdown() }

    private fun sampleRequest(model: String = "grok-4.3") = LlmRequest(
        model = model,
        systemPrompt = "Du bist Grok",
        history = listOf(LlmTurn("user", "Hi", 1L), LlmTurn("assistant", "Hallo", 2L)),
        userMessage = "Wer bist du?",
        tools = emptyList(),
        maxTokens = 256,
        temperature = 0.7f
    )

    @Test fun `successful response with output_text shortcut`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"resp_1","model":"grok-4.3","output_text":"Ich bin Grok.","usage":{"input_tokens":10,"output_tokens":4}}"""
            )
        )
        val response = provider.complete(sampleRequest())
        assertThat(response.content).isEqualTo("Ich bin Grok.")
        assertThat(response.usage?.inputTokens).isEqualTo(10)
        assertThat(response.usage?.outputTokens).isEqualTo(4)
        assertThat(response.responseId).isEqualTo("resp_1")
    }

    @Test fun `successful response with nested output content blocks`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"id":"resp_2","model":"grok-4.3","output":[
                   {"type":"message","role":"assistant","content":[
                     {"type":"output_text","text":"Es ist "},
                     {"type":"output_text","text":"sonnig."}
                   ]}
                ]}
                """.trimIndent()
            )
        )
        val response = provider.complete(sampleRequest())
        assertThat(response.content).isEqualTo("Es ist sonnig.")
    }

    @Test fun `auth error maps to LlmProviderError Auth`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"error":{"message":"invalid api key","type":"auth"}}"""
            )
        )
        val ex = runCatching { provider.complete(sampleRequest()) }.exceptionOrNull()
        assertThat(ex).isInstanceOf(LlmProviderError.Auth::class.java)
        assertThat(ex?.message).contains("invalid api key")
    }

    @Test fun `rate limit error parses Retry-After header`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(429).setHeader("Retry-After", "42")
                .setBody("""{"error":{"message":"slow down"}}""")
        )
        val ex = runCatching { provider.complete(sampleRequest()) }.exceptionOrNull()
        assertThat(ex).isInstanceOf(LlmProviderError.RateLimit::class.java)
        assertThat((ex as LlmProviderError.RateLimit).retryAfterSec).isEqualTo(42)
    }

    @Test fun `5xx maps to Server error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("upstream gone"))
        val ex = runCatching { provider.complete(sampleRequest()) }.exceptionOrNull()
        assertThat(ex).isInstanceOf(LlmProviderError.Server::class.java)
        assertThat((ex as LlmProviderError.Server).code).isEqualTo(503)
    }

    @Test fun `missing api key throws MissingKey`() = runTest {
        coEvery { apiKeyStore.read() } returns null
        val ex = runCatching { provider.complete(sampleRequest()) }.exceptionOrNull()
        assertThat(ex).isInstanceOf(LlmProviderError.MissingKey::class.java)
    }

    @Test fun `offline throws NoNetwork`() = runTest {
        every { connectivity.isOnline() } returns false
        val ex = runCatching { provider.complete(sampleRequest()) }.exceptionOrNull()
        assertThat(ex).isInstanceOf(LlmProviderError.NoNetwork::class.java)
    }

    @Test fun `authorization header is sent`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"r","output_text":"ok"}"""))
        provider.complete(sampleRequest())
        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer test-key-123")
    }

    @Test fun `request body contains store=false and full history`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"r","output_text":"ok"}"""))
        provider.complete(sampleRequest())
        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        assertThat(body).contains("\"store\":false")
        assertThat(body).contains("\"model\":\"grok-4.3\"")
        assertThat(body).contains("\"role\":\"system\"")
        assertThat(body).contains("\"role\":\"user\"")
        assertThat(body).contains("Wer bist du?")
        assertThat(body).contains("Hi")
        assertThat(body).contains("Hallo")
    }

    @Test fun `web and x search add server tools and disable inline citations`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"r","output_text":"ok"}"""))
        provider.complete(sampleRequest().copy(webSearch = true, xSearch = true))
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"type\":\"web_search\"")
        assertThat(body).contains("\"type\":\"x_search\"")
        // exakte Key+Array-Form, nicht nur Substring
        assertThat(body).contains("\"include\":[\"no_inline_citations\"]")
        // store bleibt false, auch mit aktiver Suche
        assertThat(body).contains("\"store\":false")
    }

    @Test fun `web search only adds web_search tool`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"r","output_text":"ok"}"""))
        provider.complete(sampleRequest().copy(webSearch = true))
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"type\":\"web_search\"")
        assertThat(body).doesNotContain("x_search")
        assertThat(body).contains("\"include\":[\"no_inline_citations\"]")
    }

    @Test fun `x search only adds x_search tool`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"r","output_text":"ok"}"""))
        provider.complete(sampleRequest().copy(xSearch = true))
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"type\":\"x_search\"")
        assertThat(body).doesNotContain("web_search")
        assertThat(body).contains("\"include\":[\"no_inline_citations\"]")
    }

    @Test fun `without search flags no tools and no include are sent`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"r","output_text":"ok"}"""))
        provider.complete(sampleRequest())
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).doesNotContain("web_search")
        assertThat(body).doesNotContain("x_search")
        assertThat(body).doesNotContain("no_inline_citations")
        assertThat(body).doesNotContain("\"tools\"")
        assertThat(body).doesNotContain("\"include\"")
    }
}
