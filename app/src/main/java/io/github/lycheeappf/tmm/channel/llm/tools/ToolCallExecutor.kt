package io.github.lycheeappf.tmm.channel.llm.tools

import io.github.lycheeappf.tmm.channel.llm.provider.LlmRequest
import io.github.lycheeappf.tmm.channel.llm.provider.LlmResponse
import io.github.lycheeappf.tmm.channel.llm.provider.ToolCall
import io.github.lycheeappf.tmm.channel.llm.provider.ToolResult
import io.github.lycheeappf.tmm.core.util.LogBuffer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Geteilter Tool-Execution-Loop (extrahiert aus [io.github.lycheeappf.tmm.channel.llm.LlmTurnRunner],
 * zusätzlich genutzt vom [io.github.lycheeappf.tmm.channel.llm.GrokSelfTester]): führt die
 * `function_calls` einer Response aus, hängt Call+Result als In-Flight-Items an den Request
 * und holt via [run]s `complete`-Callback die Folge-Response — bis eine Text-Antwort kommt
 * oder [MAX_TOOL_ITERATIONS] erreicht ist.
 *
 * Bewusst NUR der Loop: Formatter, Blank-Fallback, Rate-Limit-Refunds und History-Persist
 * bleiben beim Aufrufer. Fehler aus `complete` propagieren unverändert (kein Catch hier).
 */
@Singleton
class ToolCallExecutor @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val logBuffer: LogBuffer
) {

    /** Ein ausgeführter Tool-Call samt Ergebnis — für Auswertung durch den Aufrufer. */
    data class ToolStep(val call: ToolCall, val result: ToolInvocationResult)

    data class ToolLoopResult(val finalResponse: LlmResponse, val steps: List<ToolStep>)

    suspend fun run(
        initialRequest: LlmRequest,
        initialResponse: LlmResponse,
        complete: suspend (LlmRequest) -> LlmResponse
    ): ToolLoopResult {
        var currentReq = initialRequest
        var currentResponse = initialResponse
        val steps = mutableListOf<ToolStep>()
        var toolIterations = 0
        while (currentResponse.toolCalls.isNotEmpty() && toolIterations < MAX_TOOL_ITERATIONS) {
            val results = currentResponse.toolCalls.map { call ->
                val args = runCatching {
                    Json.parseToJsonElement(call.argumentsJson).jsonObject
                }.getOrDefault(buildJsonObject {})
                val out = toolRegistry.invoke(call.name, args)
                logBuffer.info(TAG, "Tool '${call.name}' → ${out::class.simpleName}")
                steps += ToolStep(call, out)
                ToolResult(callId = call.id, output = out.toOutputString())
            }
            currentReq = currentReq.copy(
                inFlightToolCalls = currentReq.inFlightToolCalls + currentResponse.toolCalls,
                inFlightToolResults = currentReq.inFlightToolResults + results
            )
            currentResponse = complete(currentReq)
            toolIterations++
        }
        return ToolLoopResult(finalResponse = currentResponse, steps = steps.toList())
    }

    companion object {
        private const val TAG = "ToolCallExecutor"
        internal const val MAX_TOOL_ITERATIONS = 3
    }
}
