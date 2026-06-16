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
 * Tools werden mitgesendet, sind aber in V2 leer (Tool-Use ist V3).
 */
data class LlmRequest(
    val model: String,
    val systemPrompt: String?,
    val history: List<LlmTurn>,
    val userMessage: String,
    val tools: List<ToolSchema>,
    val maxTokens: Int,
    val temperature: Float
)
