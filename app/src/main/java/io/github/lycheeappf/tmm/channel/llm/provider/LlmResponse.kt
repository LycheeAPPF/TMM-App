package io.github.lycheeappf.tmm.channel.llm.provider

/**
 * Provider-agnostische Antwort. [content] kann null sein, wenn der Provider
 * ausschließlich tool_calls produziert hat (= keine Inline-Antwort).
 */
data class LlmResponse(
    val content: String?,
    val toolCalls: List<ToolCall>,
    val finishReason: String,
    val usage: TokenUsage?,
    /** Vom Provider vergebene Response-ID, fürs Logging (nicht für State-Tracking). */
    val responseId: String?
)

data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

data class TokenUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val cachedTokens: Int
)
