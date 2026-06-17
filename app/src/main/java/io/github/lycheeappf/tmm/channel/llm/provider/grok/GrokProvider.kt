package io.github.lycheeappf.tmm.channel.llm.provider.grok

import io.github.lycheeappf.tmm.channel.llm.provider.LlmProvider
import io.github.lycheeappf.tmm.channel.llm.provider.LlmProviderError
import io.github.lycheeappf.tmm.channel.llm.provider.LlmRequest
import io.github.lycheeappf.tmm.channel.llm.provider.LlmResponse
import io.github.lycheeappf.tmm.channel.llm.provider.TokenUsage
import io.github.lycheeappf.tmm.channel.llm.provider.ToolCall
import io.github.lycheeappf.tmm.channel.llm.tools.ToolSchema
import io.github.lycheeappf.tmm.core.network.ConnectivityChecker
import io.github.lycheeappf.tmm.core.security.ApiKeyStore
import io.github.lycheeappf.tmm.core.util.LogBuffer
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `LlmProvider`-Implementation für die xAI Responses API.
 *
 *  - Sendet `input` als role/content-Array (System-Prompt + History + neuer User-Turn).
 *  - `store = false` (Privacy) → wir tracken kein `previous_response_id`,
 *    sondern senden die volle In-Memory-History bei jedem Turn.
 *  - HTTP-Failure → `LlmProviderError`-Hierarchie, vom Turn-Runner als
 *    `ReplyResult.ProviderError` an den User vorgelesen.
 */
@Singleton
class GrokProvider @Inject constructor(
    private val api: GrokApi,
    private val apiKeyStore: ApiKeyStore,
    private val connectivity: ConnectivityChecker,
    private val logBuffer: LogBuffer
) : LlmProvider {

    override suspend fun complete(req: LlmRequest): LlmResponse {
        if (!connectivity.isOnline()) throw LlmProviderError.NoNetwork
        val key = apiKeyStore.read() ?: throw LlmProviderError.MissingKey()
        if (key.isBlank()) throw LlmProviderError.MissingKey()

        val body = buildRequest(req)
        val resp = try {
            api.responses("Bearer $key", body)
        } catch (e: IOException) {
            throw LlmProviderError.Network(e)
        }

        if (!resp.isSuccessful) {
            val errorBody = runCatching { resp.errorBody()?.string() }.getOrNull()
            logBuffer.warn(TAG, "HTTP ${resp.code()} from xAI")
            throw mapHttpError(resp.code(), errorBody, resp.headers()["Retry-After"])
        }

        val payload = resp.body() ?: throw LlmProviderError.Parse("Leerer Response-Body")
        return mapResponse(payload)
    }

    // ---- Mapping helpers ---------------------------------------------------

    private fun buildRequest(req: LlmRequest): ResponsesRequest {
        val items = buildList {
            req.systemPrompt?.takeIf { it.isNotBlank() }?.let {
                add(ResponsesInputItem(role = "system", content = it))
            }
            req.history.forEach { turn ->
                add(ResponsesInputItem(role = turn.role, content = turn.content))
            }
            add(ResponsesInputItem(role = "user", content = req.userMessage))
        }
        return ResponsesRequest(
            model = req.model,
            input = items,
            maxOutputTokens = req.maxTokens.takeIf { it > 0 },
            temperature = req.temperature.toDouble().takeIf { it >= 0.0 },
            store = false,
            tools = req.tools.takeIf { it.isNotEmpty() }?.map { it.toDto() }
        )
    }

    private fun mapResponse(payload: ResponsesResponse): LlmResponse {
        val content = extractText(payload)
        val tools = payload.output
            .filter { it.type == "function_call" }
            .map {
                ToolCall(
                    id = it.callId.orEmpty(),
                    name = it.name.orEmpty(),
                    argumentsJson = it.arguments.orEmpty()
                )
            }
        val finish = payload.output.firstOrNull { it.type == "message" }?.status ?: "stop"
        val usage = payload.usage?.let {
            TokenUsage(
                inputTokens = it.inputTokens ?: 0,
                outputTokens = it.outputTokens ?: 0,
                cachedTokens = it.cachedTokens ?: 0
            )
        }
        return LlmResponse(
            content = content?.takeIf { it.isNotBlank() },
            toolCalls = tools,
            finishReason = finish,
            usage = usage,
            responseId = payload.id
        )
    }

    private fun extractText(payload: ResponsesResponse): String? {
        payload.outputText?.takeIf { it.isNotBlank() }?.let { return it }
        val sb = StringBuilder()
        for (item in payload.output) {
            if (item.type != "message") continue
            if (item.role != null && item.role != "assistant") continue
            item.content?.forEach { block ->
                if (block.type == "output_text") block.text?.let { sb.append(it) }
            }
        }
        return sb.toString().takeIf { it.isNotEmpty() }
    }

    private fun mapHttpError(code: Int, body: String?, retryAfterHeader: String?): LlmProviderError {
        val detail = parseErrorMessage(body) ?: "HTTP $code"
        return when (code) {
            401, 403 -> LlmProviderError.Auth(detail)
            429 -> LlmProviderError.RateLimit(retryAfterHeader?.toIntOrNull())
            else -> LlmProviderError.Server(code, body)
        }
    }

    private fun parseErrorMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            ERROR_JSON.decodeFromString(ResponsesErrorEnvelope.serializer(), body).error?.message
        } catch (e: Exception) {
            body.take(120)
        }
    }

    companion object {
        private const val TAG = "GrokProvider"
        private val ERROR_JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }
    }
}

internal fun ToolSchema.toDto(): ResponsesTool = ResponsesTool(
    type = "function",
    name = name,
    description = description,
    parameters = parametersJson
)
