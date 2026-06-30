package io.github.lycheeappf.tmm.channel.llm.provider.grok

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * DTOs für die xAI **Responses API** (`POST /v1/responses`). Die Responses API
 * akzeptiert ein `input`-Array mit role/content-Paaren statt des klassischen
 * `messages`-Arrays der Chat-Completions API. Felder mit `null` werden vom
 * konfigurierten `Json {explicitNulls = false}` weggelassen.
 *
 * Hinweis: Wir setzen `store = false` per Default, damit xAI die Konversation
 * nicht 30 Tage serverseitig cached. Das passt zu einem strengen Privacy-Profil;
 * der Trade-off ist, dass wir bei Multi-Turn immer die volle
 * In-Memory-History neu mitsenden müssen statt `previous_response_id` zu
 * referenzieren.
 */

@Serializable
data class ResponsesRequest(
    val model: String,
    val input: List<ResponsesInputItem>,
    // xAI Responses API verwendet `max_output_tokens` (nicht `max_completion_tokens`
    // wie die Chat-Completions-API). Falsche Bezeichnung führt zu HTTP 400.
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
    val temperature: Double? = null,
    @SerialName("previous_response_id") val previousResponseId: String? = null,
    val store: Boolean = false,
    val stream: Boolean = false,
    val tools: List<ResponsesTool>? = null,
    // Steuert optionale Response-Bestandteile. Bei aktivem server-seitigem
    // web_search/x_search setzen wir `["no_inline_citations"]`, damit Grok keine
    // `[[1]](url)`-Zitatmarker in den Text webt (die das Tesla-TTS vorlesen würde).
    val include: List<String>? = null
)

@Serializable
data class ResponsesInputItem(
    val role: String? = null,
    val content: String? = null,
    val type: String? = null,      // "function_call" | "function_call_output"
    @SerialName("call_id") val callId: String? = null,
    val output: String? = null,    // für function_call_output
    val name: String? = null,      // für function_call (Modell-Output, im Folge-Request wiederholt)
    val arguments: String? = null  // für function_call
)

/**
 * Trägt zwei Tool-Formen:
 *  - **client-seitige Function-Tools** (`type="function"`) — name/description/parameters
 *    gesetzt (V3-Seam, in V2 leer).
 *  - **server-seitige Agent-Tools** (`type="web_search"` / `"x_search"`) — nur `type`;
 *    die übrigen Felder bleiben `null`. Dank `Json {explicitNulls = false}` serialisiert
 *    ein `ResponsesTool(type = "web_search")` exakt zu `{"type":"web_search"}`.
 */
@Serializable
data class ResponsesTool(
    val type: String = "function",
    val name: String? = null,
    val description: String? = null,
    val parameters: JsonElement? = null
)

@Serializable
data class ResponsesResponse(
    val id: String? = null,
    val model: String? = null,
    val output: List<ResponsesOutputItem> = emptyList(),
    val usage: ResponsesUsage? = null,
    @SerialName("output_text") val outputText: String? = null
)

@Serializable
data class ResponsesOutputItem(
    val type: String,                                   // "message" | "function_call" | "reasoning"
    val role: String? = null,
    val content: List<ResponsesContentBlock>? = null,
    @SerialName("call_id") val callId: String? = null,
    val name: String? = null,
    val arguments: String? = null,
    val status: String? = null
)

@Serializable
data class ResponsesContentBlock(
    val type: String,                                   // "output_text" | "input_text"
    val text: String? = null
)

@Serializable
data class ResponsesUsage(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
    @SerialName("cached_tokens") val cachedTokens: Int? = null
)

@Serializable
data class ResponsesErrorEnvelope(
    val error: ResponsesError? = null
)

@Serializable
data class ResponsesError(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null
)
