package io.github.lycheeappf.tmm.channel.llm.tools.tesla

import io.github.lycheeappf.tmm.channel.llm.tools.AssistantTool
import io.github.lycheeappf.tmm.channel.llm.tools.ToolInvocationResult
import io.github.lycheeappf.tmm.channel.llm.tools.ToolSchema
import io.github.lycheeappf.tmm.data.store.TeslaTokenStore
import io.github.lycheeappf.tmm.platform.tesla.api.TeslaCommandError
import io.github.lycheeappf.tmm.platform.tesla.api.TeslaVehicleCommandClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

/**
 * Grok-Tool: sendet ein Navigationsziel an das Tesla-Fahrzeug per Fleet API.
 *
 * Grok ruft dieses Tool auf, wenn der Fahrer einen Navigationsbefehl diktiert
 * (z.B. "Navigiere mich zur nächsten Apotheke"). Das Tool leitet den Zielort
 * an [TeslaVehicleCommandClient] weiter, der die Fleet-API-Call ausführt.
 *
 * GPS-Koordinaten haben Vorrang vor der Adresse (präziser für das Tesla-Navi).
 */
class TeslaNavigateTool @Inject constructor(
    private val commandClient: TeslaVehicleCommandClient,
    private val tokenStore: TeslaTokenStore
) : AssistantTool {

    override val schema = ToolSchema(
        name = "tesla_navigate",
        description = "Sends a navigation destination to the driver's Tesla vehicle via the Fleet API. " +
            "Call this when the driver asks to navigate somewhere, find a route, or go to a place. " +
            "Pass the destination as address text; do NOT pass GPS coordinates. " +
            "Requires Tesla account login in the app settings. " +
            "If it fails with 'Kein Tesla-Fahrzeug konfiguriert', tell the driver to connect their Tesla account in the app settings.",
        parametersJson = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("address") {
                    put("type", "string")
                    put("description", "Full address or place name to navigate to (e.g. 'Alexanderplatz Berlin' or 'Nächste Apotheke')")
                }
            }
            putJsonArray("required") { add("address") }
        }
    )

    override suspend fun invoke(arguments: JsonObject): ToolInvocationResult {
        val vin = tokenStore.readSelectedVin()
            ?: return ToolInvocationResult.Failure("Kein Tesla-Fahrzeug konfiguriert — bitte in den Einstellungen einloggen")

        val address = arguments["address"]?.jsonPrimitive?.content.orEmpty()
        if (address.isBlank()) return ToolInvocationResult.Failure("Kein Zielort angegeben")

        return try {
            commandClient.navigate(vin, address)
            ToolInvocationResult.Success("""{"status":"ok","destination":"${address.replace("\"", "\\\"")
                .take(200)}"}""")
        } catch (e: TeslaCommandError) {
            ToolInvocationResult.Failure(e.message ?: "Fahrzeugbefehl fehlgeschlagen")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ToolInvocationResult.Failure("Netzwerkfehler: ${e.message?.take(100)}")
        }
    }
}
