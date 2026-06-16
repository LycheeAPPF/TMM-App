package io.github.lycheeappf.tmm.channel.llm.tools

import kotlinx.serialization.json.JsonElement

/**
 * Beschreibt ein vom Assistant aufrufbares Tool gegenüber dem LLM-Provider.
 * V2: leerer Tool-Pool. V3: NoteTool, CalendarTool, ggf. Tesla-Klima.
 */
data class ToolSchema(
    val name: String,
    val description: String,
    /** Raw-JSON-Schema (`{"type":"object","properties":{...},"required":[...]}` etc.) */
    val parametersJson: JsonElement
)
