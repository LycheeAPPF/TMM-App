package io.github.lycheeappf.tmm.channel.llm.provider

/**
 * Provider-agnostischer `finishReason`-Wert: die Antwort wurde vom Token-Budget
 * abgeschnitten (`max_output_tokens` — bei Reasoning-Modellen inkl. Denk-Tokens),
 * bevor Text/Tool-Call fertig emittiert waren. Vertrag zwischen Provider-Mapping
 * und Auswertern (GrokSelfTester).
 */
const val FINISH_REASON_INCOMPLETE = "incomplete"

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

/** Ergebnis eines Tool-Calls, bereit zum Zurücksenden an den Provider. */
data class ToolResult(val callId: String, val output: String)

data class TokenUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val cachedTokens: Int
)
