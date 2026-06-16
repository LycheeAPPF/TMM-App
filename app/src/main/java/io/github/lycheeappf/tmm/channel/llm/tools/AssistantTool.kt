package io.github.lycheeappf.tmm.channel.llm.tools

import kotlinx.serialization.json.JsonObject

/**
 * Pluggable Tool-Definition für V3. V2 registriert keine Tools — die
 * `ToolRegistry` ist Hilt-injected mit leerem Set.
 */
interface AssistantTool {
    val schema: ToolSchema

    /**
     * Wird vom Turn-Runner aufgerufen, wenn Grok einen [ToolCall] mit
     * `name == schema.name` produziert.
     */
    suspend fun invoke(arguments: JsonObject): ToolInvocationResult
}
