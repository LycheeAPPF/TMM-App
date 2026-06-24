package io.github.lycheeappf.tmm.channel.llm.provider.grok

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

class GrokDtosTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Test fun `request serializes with snake_case fields and store=false`() {
        val req = ResponsesRequest(
            model = "grok-4.3",
            input = listOf(
                ResponsesInputItem(role = "system", content = "Du bist Grok"),
                ResponsesInputItem(role = "user", content = "Hallo")
            ),
            maxOutputTokens = 256,
            temperature = 0.5,
            previousResponseId = null,
            store = false
        )
        val text = json.encodeToString(ResponsesRequest.serializer(), req)
        assertThat(text).contains("\"model\":\"grok-4.3\"")
        // Responses API erwartet `max_output_tokens` (Chat-Completions: `max_completion_tokens`).
        assertThat(text).contains("\"max_output_tokens\":256")
        assertThat(text).doesNotContain("max_completion_tokens")
        assertThat(text).contains("\"temperature\":0.5")
        assertThat(text).contains("\"store\":false")
        // Optionales `previous_response_id` darf nicht im Output stehen
        assertThat(text).doesNotContain("previous_response_id")
    }

    @Test fun `response parses output_text shortcut`() {
        val raw = """
            {"id":"resp_abc","model":"grok-4.3","output_text":"Hallo Welt","output":[],"usage":{"input_tokens":42,"output_tokens":7}}
        """.trimIndent()
        val r = json.decodeFromString(ResponsesResponse.serializer(), raw)
        assertThat(r.id).isEqualTo("resp_abc")
        assertThat(r.outputText).isEqualTo("Hallo Welt")
        assertThat(r.usage?.inputTokens).isEqualTo(42)
        assertThat(r.usage?.outputTokens).isEqualTo(7)
    }

    @Test fun `response parses output array with message blocks`() {
        val raw = """
            {
              "id": "resp_xyz",
              "model": "grok-4.3",
              "output": [
                {
                  "type": "message",
                  "role": "assistant",
                  "content": [
                    {"type": "output_text", "text": "Paris."}
                  ]
                }
              ]
            }
        """.trimIndent()
        val r = json.decodeFromString(ResponsesResponse.serializer(), raw)
        assertThat(r.output).hasSize(1)
        assertThat(r.output[0].type).isEqualTo("message")
        assertThat(r.output[0].content?.first()?.text).isEqualTo("Paris.")
    }

    @Test fun `response parses function_call output item`() {
        val raw = """
            {
              "id":"resp_tool",
              "output":[
                {"type":"function_call","call_id":"c1","name":"get_weather","arguments":"{\"city\":\"Berlin\"}"}
              ]
            }
        """.trimIndent()
        val r = json.decodeFromString(ResponsesResponse.serializer(), raw)
        assertThat(r.output[0].type).isEqualTo("function_call")
        assertThat(r.output[0].callId).isEqualTo("c1")
        assertThat(r.output[0].name).isEqualTo("get_weather")
        assertThat(r.output[0].arguments).isEqualTo("""{"city":"Berlin"}""")
    }

    @Test fun `unknown extra fields are ignored`() {
        val raw = """{"id":"resp_a","model":"grok-4.3","futuristic_field":42,"output":[]}"""
        val r = json.decodeFromString(ResponsesResponse.serializer(), raw)
        assertThat(r.id).isEqualTo("resp_a")
    }

    @Test fun `reasoning output item type decodes without crash`() {
        val raw = """
            {"id":"resp_r","output":[
              {"type":"reasoning","content":[{"type":"output_text","text":"…"}]}
            ]}
        """.trimIndent()
        val r = json.decodeFromString(ResponsesResponse.serializer(), raw)
        assertThat(r.output[0].type).isEqualTo("reasoning")
    }

    @Test fun `usage with cached_tokens decodes`() {
        val raw = """
            {"id":"r","usage":{"input_tokens":100,"output_tokens":50,"cached_tokens":80},"output":[]}
        """.trimIndent()
        val r = json.decodeFromString(ResponsesResponse.serializer(), raw)
        assertThat(r.usage?.cachedTokens).isEqualTo(80)
    }

    @Test fun `server-side tool serializes to type only`() {
        val text = json.encodeToString(ResponsesTool.serializer(), ResponsesTool(type = "web_search"))
        // Kein name/description/parameters — server-seitiges Agent-Tool ist nur {type}.
        assertThat(text).isEqualTo("""{"type":"web_search"}""")
    }

    @Test fun `function tool still serializes all four fields`() {
        val tool = ResponsesTool(
            type = "function",
            name = "get_weather",
            description = "Get weather",
            parameters = buildJsonObject { put("type", JsonPrimitive("object")) }
        )
        val text = json.encodeToString(ResponsesTool.serializer(), tool)
        assertThat(text).contains("\"type\":\"function\"")
        assertThat(text).contains("\"name\":\"get_weather\"")
        assertThat(text).contains("\"description\":\"Get weather\"")
        assertThat(text).contains("\"parameters\"")
    }

    @Test fun `request with web search emits tools and include`() {
        val req = ResponsesRequest(
            model = "grok-4.3",
            input = listOf(ResponsesInputItem(role = "user", content = "Hi")),
            tools = listOf(ResponsesTool(type = "web_search")),
            include = listOf("no_inline_citations")
        )
        val text = json.encodeToString(ResponsesRequest.serializer(), req)
        assertThat(text).contains("\"type\":\"web_search\"")
        // Key + Array-Form festnageln, nicht nur den Substring.
        assertThat(text).contains("\"include\":[\"no_inline_citations\"]")
    }

    @Test fun `request without temperature has temperature stripped`() {
        val req = ResponsesRequest(
            model = "grok-4.3",
            input = listOf(ResponsesInputItem(role = "user", content = "Hi")),
            temperature = null
        )
        val text = json.encodeToString(ResponsesRequest.serializer(), req)
        assertThat(text).doesNotContain("temperature")
    }
}
