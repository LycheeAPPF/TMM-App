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
 * Client-seitige [tools] werden mitgesendet, sind aber in V2 leer (Function-
 * Calling ist V3). Davon getrennt schalten [webSearch]/[xSearch] xAIs
 * **server-seitige** Agent-Tools (`web_search`/`x_search`) frei — der Server fährt
 * die Such-Schleife selbst und liefert eine fertige Antwort in einem Response,
 * deshalb braucht es dafür keinen Tool-Execution-Loop.
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
    val xSearch: Boolean = false
)
