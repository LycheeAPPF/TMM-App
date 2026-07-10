package io.github.lycheeappf.tmm.channel.llm.provider.grok

import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.channel.llm.provider.FINISH_REASON_INCOMPLETE
import io.github.lycheeappf.tmm.channel.llm.provider.LlmProviderError
import io.github.lycheeappf.tmm.channel.llm.provider.LlmRequest
import io.github.lycheeappf.tmm.channel.llm.provider.LlmTurn
import io.github.lycheeappf.tmm.channel.llm.provider.ToolCall
import io.github.lycheeappf.tmm.channel.llm.provider.ToolResult
import io.github.lycheeappf.tmm.channel.llm.tools.ToolSchema
import io.github.lycheeappf.tmm.core.network.ConnectivityChecker
import io.github.lycheeappf.tmm.core.security.ApiKeyStore
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
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

    // ---- Request-Serialisierung: In-Flight-Tool-Items (Tool-Execution-Loop) ----

    /** Parst den aufgezeichneten Request-Body zurück und liefert das `input`-Array. */
    private fun recordedInput(body: String) =
        json.parseToJsonElement(body).jsonObject.getValue("input").jsonArray

    @Test fun `in-flight tool call serializes as function_call item with call_id name and arguments`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"r","output_text":"ok"}"""))
        val call = ToolCall(id = "call_42", name = "tesla_navigate", argumentsJson = """{"destination":"Berlin Hbf"}""")
        provider.complete(sampleRequest().copy(inFlightToolCalls = listOf(call)))
        val input = recordedInput(server.takeRequest().body.readUtf8())
        val item = input.map { it.jsonObject }
            .single { it["type"]?.jsonPrimitive?.contentOrNull == "function_call" }
        assertThat(item["call_id"]?.jsonPrimitive?.content).isEqualTo("call_42")
        assertThat(item["name"]?.jsonPrimitive?.content).isEqualTo("tesla_navigate")
        // arguments bleibt ein JSON-*String* (Modell-Output wörtlich wiederholt), kein Objekt
        assertThat(item["arguments"]?.jsonPrimitive?.content)
            .isEqualTo("""{"destination":"Berlin Hbf"}""")
        // explicitNulls=false: role/content dürfen im function_call-Item nicht auftauchen
        assertThat(item.keys).containsNoneOf("role", "content", "output")
    }

    @Test fun `in-flight tool result serializes as function_call_output item with call_id and output`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"r","output_text":"ok"}"""))
        val result = ToolResult(callId = "call_42", output = """{"status":"ok","destination":"Berlin Hbf"}""")
        provider.complete(sampleRequest().copy(inFlightToolResults = listOf(result)))
        val input = recordedInput(server.takeRequest().body.readUtf8())
        val item = input.map { it.jsonObject }
            .single { it["type"]?.jsonPrimitive?.contentOrNull == "function_call_output" }
        assertThat(item["call_id"]?.jsonPrimitive?.content).isEqualTo("call_42")
        assertThat(item["output"]?.jsonPrimitive?.content)
            .isEqualTo("""{"status":"ok","destination":"Berlin Hbf"}""")
        assertThat(item.keys).containsNoneOf("role", "content", "name", "arguments")
    }

    @Test fun `tool items follow the user turn with calls before outputs in list order`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"r","output_text":"ok"}"""))
        val calls = listOf(
            ToolCall(id = "call_1", name = "tool_a", argumentsJson = """{"a":1}"""),
            ToolCall(id = "call_2", name = "tool_b", argumentsJson = """{"b":2}""")
        )
        val results = listOf(
            ToolResult(callId = "call_1", output = "ok-a"),
            ToolResult(callId = "call_2", output = "ok-b")
        )
        provider.complete(sampleRequest().copy(inFlightToolCalls = calls, inFlightToolResults = results))
        val input = recordedInput(server.takeRequest().body.readUtf8())
        // system + 2 History-Turns + User-Turn + 2 function_call + 2 function_call_output
        assertThat(input).hasSize(8)
        val types = input.map { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull }
        assertThat(types).containsExactly(
            null, null, null, null,
            "function_call", "function_call",
            "function_call_output", "function_call_output"
        ).inOrder()
        // User-Turn steht direkt vor den Tool-Items
        assertThat(input[3].jsonObject["role"]?.jsonPrimitive?.content).isEqualTo("user")
        assertThat(input[3].jsonObject["content"]?.jsonPrimitive?.content).isEqualTo("Wer bist du?")
        // Listen-Reihenfolge bleibt innerhalb der Blöcke erhalten
        assertThat(input[4].jsonObject["call_id"]?.jsonPrimitive?.content).isEqualTo("call_1")
        assertThat(input[5].jsonObject["call_id"]?.jsonPrimitive?.content).isEqualTo("call_2")
        assertThat(input[6].jsonObject["call_id"]?.jsonPrimitive?.content).isEqualTo("call_1")
        assertThat(input[7].jsonObject["call_id"]?.jsonPrimitive?.content).isEqualTo("call_2")
    }

    @Test fun `tool arguments and output with quotes backslashes and newlines survive serialization`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"r","output_text":"ok"}"""))
        val nastyArguments = "line1\nline2 \"quoted\" back\\slash tab\t umlaut ü"
        val nastyOutput = "{\"text\":\"a \\\"nested\\\" value\"}\nsecond line"
        provider.complete(
            sampleRequest().copy(
                inFlightToolCalls = listOf(ToolCall(id = "c1", name = "t", argumentsJson = nastyArguments)),
                inFlightToolResults = listOf(ToolResult(callId = "c1", output = nastyOutput))
            )
        )
        val input = recordedInput(server.takeRequest().body.readUtf8())
        val callItem = input.map { it.jsonObject }
            .single { it["type"]?.jsonPrimitive?.contentOrNull == "function_call" }
        val outputItem = input.map { it.jsonObject }
            .single { it["type"]?.jsonPrimitive?.contentOrNull == "function_call_output" }
        // Round-Trip: nach HTTP + JSON-Decoding kommen die Strings byteidentisch zurück
        assertThat(callItem["arguments"]?.jsonPrimitive?.content).isEqualTo(nastyArguments)
        assertThat(outputItem["output"]?.jsonPrimitive?.content).isEqualTo(nastyOutput)
    }

    @Test fun `first call sends no in-flight tool items`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"r","output_text":"ok"}"""))
        provider.complete(sampleRequest())
        val input = recordedInput(server.takeRequest().body.readUtf8())
        // Nur role/content-Items (System + History + User), keine Tool-Zwischenstände
        assertThat(input).hasSize(4)
        input.forEach { item ->
            assertThat(item.jsonObject.keys).containsExactly("role", "content")
        }
    }

    @Test fun `client function tools serialize with name description and parameters schema`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"r","output_text":"ok"}"""))
        val schema = ToolSchema(
            name = "tesla_navigate",
            description = "Startet die Navigation im Fahrzeug",
            parametersJson = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("destination") { put("type", "string") }
                }
            }
        )
        provider.complete(sampleRequest().copy(tools = listOf(schema)))
        val body = server.takeRequest().body.readUtf8()
        val tool = json.parseToJsonElement(body).jsonObject
            .getValue("tools").jsonArray.single().jsonObject
        assertThat(tool["type"]?.jsonPrimitive?.content).isEqualTo("function")
        assertThat(tool["name"]?.jsonPrimitive?.content).isEqualTo("tesla_navigate")
        assertThat(tool["description"]?.jsonPrimitive?.content).isEqualTo("Startet die Navigation im Fahrzeug")
        // parameters wird als strukturiertes JSON-Objekt gesendet (kein String-Encoding)
        assertThat(tool["parameters"]).isEqualTo(schema.parametersJson)
    }

    // ---- Truncation-Sichtbarkeit + tool_choice ----

    @Test fun `incomplete reasoning-only response maps to finishReason incomplete with no tool calls`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"resp_i","status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[{"type":"reasoning"}]}"""
            )
        )
        val response = provider.complete(sampleRequest())
        assertThat(response.finishReason).isEqualTo(FINISH_REASON_INCOMPLETE)
        assertThat(response.toolCalls).isEmpty()
        assertThat(response.content).isNull()
    }

    @Test fun `completed response keeps message-item finish reason`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"r","status":"completed","output":[{"type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"ok"}]}]}"""
            )
        )
        val response = provider.complete(sampleRequest())
        assertThat(response.finishReason).isEqualTo("completed")
    }

    @Test fun `tool_choice is sent only when tools are present`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"r","output_text":"ok"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"r","output_text":"ok"}"""))
        val schema = ToolSchema(
            name = "t", description = "d",
            parametersJson = buildJsonObject { put("type", "object") }
        )
        provider.complete(sampleRequest().copy(tools = listOf(schema), toolChoice = "required"))
        assertThat(server.takeRequest().body.readUtf8()).contains("\"tool_choice\":\"required\"")
        // Ohne Tools wäre tool_choice ein API-Fehler — Feld muss wegfallen.
        provider.complete(sampleRequest().copy(toolChoice = "required"))
        assertThat(server.takeRequest().body.readUtf8()).doesNotContain("tool_choice")
    }
}
