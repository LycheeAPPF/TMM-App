package io.github.lycheeappf.tmm.channel.llm.tools

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton-Registry, in die V3 ihre [AssistantTool]-Implementations via
 * Hilt-`@IntoSet` einhängt. V2 wird mit dem Empty-Set initialisiert
 * (`LlmModule.provideEmptyTools`) — `activeSchemas()` returnt dann eine leere
 * Liste, und der Provider erhält kein `tools`-Feld im Request.
 */
@Singleton
class ToolRegistry @Inject constructor(
    private val tools: Set<@JvmSuppressWildcards AssistantTool>
) {
    private val byName: Map<String, AssistantTool> = tools.associateBy { it.schema.name }

    fun activeSchemas(): List<ToolSchema> = tools.map { it.schema }

    fun isEmpty(): Boolean = tools.isEmpty()

    suspend fun invoke(name: String, arguments: kotlinx.serialization.json.JsonObject):
        ToolInvocationResult =
        byName[name]?.invoke(arguments) ?: ToolInvocationResult.Failure(
            "Tool '$name' ist nicht registriert"
        )
}
