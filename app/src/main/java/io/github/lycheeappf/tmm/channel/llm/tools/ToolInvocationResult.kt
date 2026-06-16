package io.github.lycheeappf.tmm.channel.llm.tools

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
