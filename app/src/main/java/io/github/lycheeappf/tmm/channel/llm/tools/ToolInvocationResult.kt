package io.github.lycheeappf.tmm.channel.llm.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.serializer

/**
 * Ergebnis eines [AssistantTool.invoke]-Calls. Wird vom Turn-Runner als
 * `function_call_output` an den Provider zurückgesendet.
 */
sealed class ToolInvocationResult {
    /** [output] sollte ein JSON-Object-String sein, vom LLM lesbar. */
    data class Success(val output: String) : ToolInvocationResult()
    data class Failure(val error: String) : ToolInvocationResult()
    /** Tool ist registriert, aber für diesen Call nicht zuständig. */
    data object NotApplicable : ToolInvocationResult()
}

/** Serialisiert das Ergebnis zu einem JSON-String für `function_call_output`. */
internal fun ToolInvocationResult.toOutputString(): String = when (this) {
    is ToolInvocationResult.Success -> output
    is ToolInvocationResult.Failure -> """{"error":${Json.encodeToString(String.serializer(), error)}}"""
    ToolInvocationResult.NotApplicable -> """{"error":"tool not applicable"}"""
}
