package io.github.lycheeappf.tmm.channel.llm.provider

import kotlinx.serialization.Serializable

/**
 * Ein einzelner Konversations-Turn, wie der Provider ihn sieht. Bewusst flacher als
 * die xAI-API-Roles ("system" wird nicht hierin aufgeführt — System-Prompt liegt
 * separat in [LlmRequest.systemPrompt]).
 */
@Serializable
data class LlmTurn(
    val role: String,         // "user" | "assistant"
    val content: String,
    val timestamp: Long
)
