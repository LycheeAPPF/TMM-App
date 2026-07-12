package io.github.lycheeappf.tmm.channel.llm

import io.github.lycheeappf.tmm.channel.llm.tools.ToolCallExecutor
import io.github.lycheeappf.tmm.channel.llm.tools.ToolInvocationResult
import io.github.lycheeappf.tmm.platform.location.LocationFix
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

/**
 * Reine Auswertungs-Funktionen des Grok-Selbsttests (kein Android, kein I/O) —
 * von [GrokSelfTester] nach dem E2E-Turn aufgerufen, separat testbar.
 */
internal object SelfTestEvaluation {

    const val NAV_TOOL_NAME = "tesla_navigate"

    /** Toleranz beim Koordinaten-Vergleich — die Prompt-Klausel hat 4 Nachkommastellen, Grok rundet gern auf 2. */
    const val COORD_TOLERANCE = 0.02

    /** Obergrenze gescannter Zahlen — schützt vor pathologisch zahlenreichen Antworten. */
    private const val MAX_NUMBERS_SCANNED = 64

    fun navCheck(destination: String, steps: List<ToolCallExecutor.ToolStep>): NavCheck {
        val step = steps.firstOrNull { it.call.name == NAV_TOOL_NAME } ?: return NavCheck.NotCalled
        val sent = runCatching {
            Json.parseToJsonElement(step.call.argumentsJson)
                .jsonObject["address"]?.jsonPrimitive?.content
        }.getOrNull().orEmpty()
        // Zuerst die Adresse: ein falsches Ziel ist auch bei Fleet-Erfolg der primäre Defekt.
        if (!addressMatches(destination, sent)) return NavCheck.WrongAddress(sent)
        return when (val result = step.result) {
            is ToolInvocationResult.Success -> NavCheck.CalledOk(sent)
            is ToolInvocationResult.Failure -> NavCheck.CalledFailed(sent, result.error)
            ToolInvocationResult.NotApplicable -> NavCheck.CalledFailed(sent, "tool not applicable")
        }
    }

    /** Case-/Whitespace-/Interpunktions-tolerant; Substring-Match in beide Richtungen. */
    fun addressMatches(expected: String, sent: String): Boolean {
        val e = normalize(expected)
        val s = normalize(sent)
        if (e.isEmpty() || s.isEmpty()) return false
        return s.contains(e) || e.contains(s)
    }

    fun positionEcho(fix: LocationFix?, systemPromptBlank: Boolean, answer: String): PositionEcho {
        if (fix == null) return PositionEcho.SKIPPED
        if (systemPromptBlank) return PositionEcho.NO_CLAUSE
        val numbers = extractNumbers(answer)
        // Vorzeichen-tolerant: die Prompt-Klausel formatiert abs() + Himmelsrichtung.
        val lat = abs(fix.latitude)
        val lon = abs(fix.longitude)
        for (i in numbers.indices) {
            for (j in numbers.indices) {
                if (i == j) continue
                if (abs(numbers[i] - lat) <= COORD_TOLERANCE &&
                    abs(numbers[j] - lon) <= COORD_TOLERANCE
                ) {
                    return PositionEcho.MATCHED
                }
            }
        }
        return PositionEcho.NOT_FOUND
    }

    /** Dezimalpunkt UND -komma (Grok antwortet ggf. deutsch: „52,52"); Grad-/Richtungszeichen fallen raus. */
    fun extractNumbers(text: String): List<Double> =
        Regex("""\d+(?:[.,]\d+)?""").findAll(text)
            .take(MAX_NUMBERS_SCANNED)
            .mapNotNull { it.value.replace(',', '.').toDoubleOrNull() }
            .toList()

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
}
