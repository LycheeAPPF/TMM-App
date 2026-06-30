package io.github.lycheeappf.tmm.channel.llm.provider

import io.github.lycheeappf.tmm.channel.llm.tools.ToolSchema

/**
 * Provider-agnostische Beschreibung eines einzelnen Multi-Turn-API-Calls.
 *
 *  - [history] enthält bereits bestätigte User/Assistant-Turns (chronologisch).
 *  - [userMessage] ist der neue Turn, der hinzukommt (wird vom Provider als
 *    letzter Eintrag in `input` gehängt — *nicht* in [history] enthalten).
 *  - [systemPrompt] wird vom Provider an die Spitze gehängt (falls non-blank).
 *
 * Client-seitige [tools] werden mitgesendet. Davon getrennt schalten
 * [webSearch]/[xSearch] xAIs **server-seitige** Agent-Tools (`web_search`/`x_search`)
 * frei — der Server fährt die Such-Schleife selbst und liefert eine fertige Antwort
 * in einem Response, deshalb braucht es dafür keinen Tool-Execution-Loop.
 *
 * [inFlightToolCalls] + [inFlightToolResults] tragen die Zwischenstände des
 * Tool-Execution-Loops: das `function_call`-Output des Modells und unsere
 * `function_call_output`-Ergebnisse, die im Folge-Request mitgesendet werden.
 * Beide sind beim ersten Call leer und werden ausschließlich vom [LlmTurnRunner]
 * befüllt — nie zur [LlmConversationStore]-History persistiert.
 */
data class LlmRequest(
    val model: String,
    val systemPrompt: String?,
    val history: List<LlmTurn>,
    val userMessage: String,
    val tools: List<ToolSchema>,
    val maxTokens: Int,
    val temperature: Float,
    val webSearch: Boolean = false,
    val xSearch: Boolean = false,
    val inFlightToolCalls: List<ToolCall> = emptyList(),
    val inFlightToolResults: List<ToolResult> = emptyList()
)
